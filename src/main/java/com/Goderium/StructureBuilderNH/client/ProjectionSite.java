package com.Goderium.StructureBuilderNH.client;

import java.util.Arrays;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

/** Tracks the world sections underneath one local mesh. A rotated, unaligned mesh can straddle eight. */
final class ProjectionSite implements Runnable, ProjectionOccupancy.MaterialSink {
    private ProjectionOccupancy world;
    private PreviewGrip pose;
    private ExtendedFacing facing;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private final int[] cell = new int[3];
    // Triples (source slot, block ID, metadata) in the same ascending slot order for every pose.
    private int[] materials, nextMaterials;
    private int materialLength, writeIndex, readIndex;
    final PreviewRegion changedCells = new PreviewRegion();
    private int sectionX, sectionY, sectionZ;
    boolean dirty = true;

    boolean boundTo(ProjectionOccupancy next, PreviewGrip nextPose) {
        return next == world && nextPose == pose && (pose == null || facing == pose.facing);
    }

    void bind(ProjectionOccupancy next, PreviewGrip nextPose, int x, int y, int z) {
        // While dragging, the complete blueprint follows the controller independently of either site.
        if (nextPose != null && !nextPose.released) {
            next = null;
            nextPose = null;
        }
        if (boundTo(next, nextPose)) return;
        unwatch();
        world = next;
        pose = nextPose;
        if (world == null) return;
        if (pose == null) {
            minX = maxX = x >> 4;
            minY = maxY = y >> 4;
            minZ = maxZ = z >> 4;
        } else {
            facing = pose.facing;
            pose.worldCell(x, y, z, cell);
            int ax = cell[0], ay = cell[1], az = cell[2];
            pose.worldCell(x + 15, y + 15, z + 15, cell);
            minX = Math.min(ax, cell[0]) >> 4;
            minY = Math.min(ay, cell[1]) >> 4;
            minZ = Math.min(az, cell[2]) >> 4;
            maxX = Math.max(ax, cell[0]) >> 4;
            maxY = Math.max(ay, cell[1]) >> 4;
            maxZ = Math.max(az, cell[2]) >> 4;
        }
        for (int sy = minY; sy <= maxY; sy++) for (int sz = minZ; sz <= maxZ; sz++)
            for (int sx = minX; sx <= maxX; sx++) world.watchChanges(sx, sy, sz, this);
    }

    void close() {
        unwatch();
        materials = null;
        materialLength = 0;
        nextMaterials = null;
    }

    private void unwatch() {
        if (world != null) {
            for (int sy = minY; sy <= maxY; sy++) for (int sz = minZ; sz <= maxZ; sz++)
                for (int sx = minX; sx <= maxX; sx++) world.unwatch(sx, sy, sz, this);
        }
        world = null;
        pose = null;
        dirty = true;
    }

    @Override
    public void run() {
        dirty = true;
    }

    void read(BuilderRenderer.Section s, long[] result) {
        read(s, result, false);
    }

    void read(BuilderRenderer.Section s, long[] result, boolean textured) {
        dirty = false;
        changedCells.clear();
        sectionX = s.x; sectionY = s.y; sectionZ = s.z;
        writeIndex = readIndex = 0;
        ProjectionOccupancy.MaterialSink sink = textured ? this : null;
        if (world != null && pose == null) {
            world.read(minX, minY, minZ, s.solid, s.optional, s.hatch, result, sink);
        } else {
            readMoved(s, result, sink);
        }
        if (nextMaterials != null) {
            materials = nextMaterials;
            nextMaterials = null;
        }
        materialLength = writeIndex;
        if (materialLength == 0) materials = null;
        else if (materialLength <= materials.length / 4) materials = Arrays.copyOf(materials, materialLength);
    }

    @Override
    public void record(int slot, int blockId, int metadata) {
        // Both snapshots are sorted by slot. Inserting one early cell leaves later cells unchanged.
        while (readIndex < materialLength && materials[readIndex] < slot) readIndex += 3;
        boolean found = readIndex < materialLength && materials[readIndex] == slot;
        if (!found || materials[readIndex + 1] != blockId || materials[readIndex + 2] != metadata)
            changedCells.include(sectionX + (slot & 15), sectionY + (slot >> 8), sectionZ + (slot >> 4 & 15));
        if (nextMaterials == null && (!found || readIndex != writeIndex)) {
            nextMaterials = new int[Math.max(3, materialLength)];
            if (writeIndex != 0) System.arraycopy(materials, 0, nextMaterials, 0, writeIndex);
        }
        if (nextMaterials != null && writeIndex == nextMaterials.length)
            nextMaterials = Arrays.copyOf(nextMaterials, Math.min(4096 * 3, writeIndex * 2));
        int[] target = nextMaterials == null ? materials : nextMaterials;
        target[writeIndex] = slot;
        target[writeIndex + 1] = blockId;
        target[writeIndex + 2] = metadata;
        if (found) readIndex += 3;
        writeIndex += 3;
    }

    private void readMoved(BuilderRenderer.Section s, long[] result, ProjectionOccupancy.MaterialSink sink) {
        Arrays.fill(result, 0L);
        if (world == null) return;
        boolean empty = true;
        for (int y = minY; y <= maxY && empty; y++) for (int z = minZ; z <= maxZ && empty; z++)
            for (int x = minX; x <= maxX && empty; x++) empty = world.emptySection(x, y, z);
        if (empty) return;
        for (int word = 0; word < result.length; word++) {
            for (long bits = s.solid[word] | s.optional[word] | s.hatch[word]; bits != 0; bits &= bits - 1) {
                int slot = (word << 6) + Long.numberOfTrailingZeros(bits);
                int[] p = worldCell(s.x + (slot & 15), s.y + (slot >> 8), s.z + ((slot >> 4) & 15));
                if (world.occupied(p[0], p[1], p[2], slot, sink)) result[word] |= Long.lowestOneBit(bits);
            }
        }
    }

    int[] worldCell(int x, int y, int z) {
        if (pose != null) pose.worldCell(x, y, z, cell);
        else { cell[0] = x; cell[1] = y; cell[2] = z; }
        return cell;
    }
}
