package com.Goderium.StructureBuilderNH.client;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.IBlockAccess;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Translucent voxel surfaces share the world's solid depth, including across preview tasks. */
final class WorldPreviewGlass {
    /** ProjectedBlockFaces visits slots in order, with at most 64 cells in each compile step. */
    static final class Collector {
        final short[] slots = new short[64];
        final byte[] masks = new byte[64];
        final double[] heights = new double[64];
        final double[] bottoms = new double[64], tops = new double[64];
        int size;

        void face(int slot, int side) {
            if (size == 0 || slots[size - 1] != slot) {
                slots[size] = (short) slot;
                masks[size++] = 0;
            }
            masks[size - 1] |= 1 << side;
        }
    }

    private static final class Part {
        final int index;
        final short[] slots;
        final byte[] masks;
        final double[] heights;
        final byte[] ends;
        final int[] offsets;
        int first, groups, listCount;

        Part(int index, Collector source) {
            this.index = index;
            slots = Arrays.copyOf(source.slots, source.size);
            masks = Arrays.copyOf(source.masks, source.size);
            heights = Arrays.copyOf(source.heights, source.size);
            ends = new byte[source.size];
            offsets = new int[source.size];
            listCount = source.size;
        }

        // Each group occupies one node of the same spatial partition used for drawing.
        // Geometry from another compile step must never interleave that node.
        void partition(Collector source, int from, int end, int firstSlot, int endSlot) {
            int bit = Integer.highestOneBit(slots[from] ^ slots[end - 1]);
            int base = slots[from] & -(bit * 2);
            if (bit != 0 && (base < firstSlot || base + 2 * bit > endSlot
                    || !convex(source, from, end))) {
                int split = from + 1;
                while (slots[split] < base + bit) split++;
                partition(source, from, split, firstSlot, endSlot);
                partition(source, split, end, firstSlot, endSlot);
                return;
            }
            ends[groups] = (byte) end;
            offsets[groups++] = end == from + 1 ? from : listCount++;
        }

        // Back-face culling leaves at most one visible surface of a convex box along each ray.
        private boolean convex(Collector source, int from, int end) {
            for (int axis = 0; axis < 3; axis++) {
                int shift = axis == 0 ? 0 : axis == 1 ? 8 : 4;
                double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
                double negative = Double.NEGATIVE_INFINITY, positive = Double.POSITIVE_INFINITY;
                for (int i = from; i < end; i++) {
                    int p = slots[i] >> shift & 15;
                    double min = axis == 1 ? source.bottoms[i] : p;
                    double max = axis == 1 ? source.tops[i] : p + 1;
                    lo = Math.min(lo, min);
                    hi = Math.max(hi, max);
                    if ((source.masks[i] & 1 << (axis * 2)) != 0) negative = Math.max(negative, min);
                    if ((source.masks[i] & 2 << (axis * 2)) != 0) positive = Math.min(positive, max);
                }
                if (negative > lo || positive < hi) return false;
            }
            return true;
        }
    }

    /** One camera order for an isolated, stationary section. Original lists remain usable on the first frame of a move. */
    private static final class OrderedBatch {
        static final int CELLS_PER_LIST = 256;
        final int eye, first, lists;
        final int[] order;
        int compiled;

        OrderedBatch(Mesh mesh, int eye) {
            this.eye = eye;
            order = new int[mesh.size];
            lists = (order.length + CELLS_PER_LIST - 1) / CELLS_PER_LIST;
            first = GL11.glGenLists(lists);
            int count = 0;
            for (int p = 0; p < mesh.parts.size(); p++) {
                Part part = mesh.parts.get(p);
                for (int i = 0; i < part.slots.length; i++) {
                    // Each of the 64 compile steps contains at most 64 translucent cells.
                    order[count++] = rank(part.slots[i], eye) << 12 | p << 6 | i;
                }
            }
            Arrays.sort(order);
        }

        private static int rank(int slot, int eye) {
            return AXIS_ORDER[eye >> 8][slot >> 8] << 8
                    | AXIS_ORDER[eye >> 4 & 15][slot >> 4 & 15] << 4 | AXIS_ORDER[eye & 15][slot & 15];
        }

        int commandFor(int slot) {
            int key = rank(slot, eye) << 12;
            int index = Arrays.binarySearch(order, key);
            if (index < 0) index = -index - 1;
            return index < order.length && (order[index] & ~4095) == key ? first + index / CELLS_PER_LIST : 0;
        }

        void compileNext(Mesh mesh) {
            int from = compiled * CELLS_PER_LIST, end = Math.min(from + CELLS_PER_LIST, order.length);
            IBlockAccess access = BuilderRenderer.textureAccess(mesh.source);
            Tessellator t = Tessellator.instance;
            BuilderRenderer.beginSectionList(first + compiled, mesh.source);
            try {
                t.startDrawingQuads();
                t.setBrightness(0xf000f0);
                for (int i = from; i < end; i++) {
                    int entry = order[i] & 4095, cell = entry & 63;
                    Part part = mesh.parts.get(entry >> 6);
                    mesh.emitCell(t, mesh.source, access, part.slots[cell], part.masks[cell], part.heights[cell]);
                }
                t.draw();
            } finally { BuilderRenderer.endSectionList(); }
            compiled++;
        }
    }

    static final class Mesh {
        final int x, y, z;
        final UUID task;
        final BuilderRenderer.Section source;
        OrderedBatch ordered;
        boolean usingOrdered, compactable;
        final ChunkPosition section;
        final List<Part> parts = new ArrayList<>();
        boolean batchable, groupEnd;
        int orderEye, insertion;
        PreviewGrip grid;
        final int[] worldMin = new int[3];
        int size, batchCount;
        int sortedSize, poseX, poseY, poseZ, orientation;
        PreviewGrip pose;

        Mesh(BuilderRenderer.Section source, UUID task) {
            this.source = source;
            x = source.x; y = source.y; z = source.z; this.task = task;
            section = new ChunkPosition(x >> 4, y >> 4, z >> 4);
        }

        boolean replace(int index, int firstWord, int endWord, BuilderRenderer.Section section, IBlockAccess access, Collector source) {
            int position = 0;
            while (position < parts.size() && parts.get(position).index < index) position++;
            Part old = position < parts.size() && parts.get(position).index == index ? parts.get(position) : null;
            if (source == null || source.size == 0) {
                if (old != null) {
                    parts.remove(position);
                    discard(old);
                }
                return true;
            }
            // Use the same fluid height for the convexity test and for both display lists.
            for (int i = 0; i < source.size; i++) {
                int slot = source.slots[i], localY = slot >> 8;
                BlockPreviewTextures.Appearance a = section.appearance(slot);
                source.bottoms[i] = localY;
                source.tops[i] = localY + 1;
                if (a.fluid == null) continue;
                double height = a.fluid.height(a, access, x + (slot & 15), y + localY, z + (slot >> 4 & 15));
                source.heights[i] = height;
                source.bottoms[i] = localY + (a.fluid.rises ? 1 - height : 0);
                source.tops[i] = localY + (a.fluid.rises ? 1 : height);
            }
            Part fresh = new Part(index, source);
            fresh.partition(source, 0, source.size, firstWord << 6, endWord << 6);
            fresh.first = GL11.glGenLists(fresh.listCount);
            if (fresh.first == 0) return false;
            Tessellator t = Tessellator.instance;
            try {
                for (int i = 0; i < source.size; i++) compile(fresh.first + i, section, access, source, i, i + 1, t);
                int from = 0;
                for (int g = 0; g < fresh.groups; g++) {
                    int end = fresh.ends[g];
                    if (end > from + 1) compile(fresh.first + fresh.offsets[g], section, access, source, from, end, t);
                    from = end;
                }
            } catch (RuntimeException | Error failure) {
                GL11.glDeleteLists(fresh.first, fresh.listCount);
                throw failure;
            }
            if (old == null) parts.add(position, fresh);
            else {
                parts.set(position, fresh);
                discard(old);
            }
            clearOrdered();
            batchCount += fresh.groups;
            size += source.size;
            source.size = 0;
            return true;
        }

        private void compile(int list, BuilderRenderer.Section section, IBlockAccess access, Collector source,
                int from, int end, Tessellator t) {
            BuilderRenderer.beginSectionList(list, section);
            try {
                t.startDrawingQuads();
                t.setBrightness(0xf000f0);
                for (int i = from; i < end; i++) {
                    emitCell(t, section, access, source.slots[i], source.masks[i], source.heights[i]);
                }
                t.draw();
            } finally { BuilderRenderer.endSectionList(); }
        }

        private int localEye(int wx, int wy, int wz) {
            return MathHelper.clamp_int(wy - y, 0, 15) << 8 | MathHelper.clamp_int(wz - z, 0, 15) << 4
                    | MathHelper.clamp_int(wx - x, 0, 15);
        }

        private void clearOrdered() {
            if (ordered != null) GL11.glDeleteLists(ordered.first, ordered.lists);
            ordered = null;
            usingOrdered = false;
        }

        private void discard(Part old) {
            clearOrdered();
            GL11.glDeleteLists(old.first, old.listCount);
            batchCount -= old.groups;
            size -= old.slots.length;
            // The number of cells may be unchanged while their display-list IDs have changed.
            sortedSize = -1;
        }

        private void emitCell(Tessellator t, BuilderRenderer.Section section, IBlockAccess access, int slot, int mask,
                double height) {
            BlockPreviewTextures.Appearance appearance = section.appearance(slot);
            for (int faces = mask; faces != 0; faces &= faces - 1) {
                int side = Integer.numberOfTrailingZeros(faces);
                t.setBrightness(BuilderRenderer.markerData(section, slot, side, true));
                int wx = x + (slot & 15), wy = y + (slot >> 8), wz = z + (slot >> 4 & 15);
                if (appearance.fluid == null) {
                    BlockPreviewTextures.face(t, appearance, side, slot, 255, access, wx, wy, wz);
                } else {
                    appearance.fluid.face(BlockPreviewTextures.RENDERER, t, appearance, side, slot, 255, access,
                            wx, wy, wz, height);
                }
            }
        }

        int commandFor(int slot) {
            if (usingOrdered) return ordered.commandFor(slot);
            for (Part part : parts) {
                int index = Arrays.binarySearch(part.slots, (short) slot);
                if (index < 0) continue;
                if (!batchable) return part.first + index;
                int group = 0;
                while (part.ends[group] <= index) group++;
                return part.first + part.offsets[group];
            }
            return 0;
        }

        void release() {
            clearOrdered();
            for (Part part : parts) GL11.glDeleteLists(part.first, part.listCount);
            parts.clear();
            size = batchCount = 0;
        }

        boolean updatePose(PreviewGrip next, int[] opposite) {
            int px = 0, py = 0, pz = 0, facing = 0;
            if (next != null) {
                px = next.at.posX; py = next.at.posY; pz = next.at.posZ;
                facing = next.facing.ordinal();
            }
            boolean changed = size != sortedSize || pose != next || px != poseX || py != poseY
                    || pz != poseZ || facing != orientation;
            if (changed) {
                if (next == null) {
                    worldMin[0] = x; worldMin[1] = y; worldMin[2] = z;
                } else {
                    next.worldCell(x, y, z, worldMin);
                    next.worldCell(x + 15, y + 15, z + 15, opposite);
                    for (int axis = 0; axis < 3; axis++) worldMin[axis] = Math.min(worldMin[axis], opposite[axis]);
                }
            }
            sortedSize = size; pose = next; poseX = px; poseY = py; poseZ = pz; orientation = facing;
            return changed;
        }
    }

    private final List<Mesh> visible = new ArrayList<>();
    private final List<Mesh> partitions = new ArrayList<>(), drawMeshes = new ArrayList<>();
    private final int[] groupEye = new int[3], corner = new int[3];
    private int nextMesh, remainingMeshes;
    private final int[] worldCell = new int[3];
    private long[] keys = new long[0];
    private int[] ids = new int[0];
    private PreviewGrip[] poses = new PreviewGrip[0];
    private IntBuffer commands;
    private int[] runEnds = new int[0];
    private PreviewGrip[] runPoses = new PreviewGrip[0];
    private int runs, seen, count, eyeX, eyeY, eyeZ;
    private boolean dirty;
    private final Set<ChunkPosition> occupiedSections = new HashSet<>(), sharedSections = new HashSet<>();
    private Mesh targetMesh, cachedTarget;
    private int targetSlot, cachedSlot, selectedCommandIndex = -1;

    void include(Mesh mesh, PreviewGrip pose, boolean marked) {
        dirty |= mesh.updatePose(pose, worldCell);
        boolean stable = mesh.source.canCompactGlass(mesh);
        if (stable && !mesh.compactable) dirty = true;
        mesh.compactable = stable;
        if (seen == visible.size()) {
            visible.add(mesh);
            dirty = true;
        } else if (visible.get(seen) != mesh) {
            visible.set(seen, mesh);
            dirty = true;
        }
        int slot = marked ? MarkerStroke.slotFor(mesh.task, mesh.x, mesh.y, mesh.z) : -1;
        if (slot >= 0) {
            targetMesh = mesh;
            targetSlot = slot;
        }
        seen++;
        count += mesh.size;
    }

    void draw(double cameraX, double cameraY, double cameraZ) {
        if (visible.size() != seen) {
            visible.subList(seen, visible.size()).clear();
            dirty = true;
        }
        int x = MathHelper.floor_double(cameraX), y = MathHelper.floor_double(cameraY), z = MathHelper.floor_double(cameraZ);
        if (count > 0) {
            if (dirty || x != eyeX || y != eyeY || z != eyeZ) sort(x, y, z);
            if (targetMesh != cachedTarget || targetSlot != cachedSlot) {
                cachedTarget = targetMesh;
                cachedSlot = targetSlot;
                selectedCommandIndex = -1;
                if (targetMesh != null) {
                    int list = targetMesh.commandFor(targetSlot);
                    if (list != 0) for (int i = 0; i < commands.limit(); i++) {
                        if (commands.get(i) == list) {
                            selectedCommandIndex = i;
                            break;
                        }
                    }
                }
            }
            // Every translucent mesh uses the atlas, regardless of which marker sections are in the frustum.
            int previous = MarkerStroke.begin(true);
            try { drawOrdered(); } finally { MarkerStroke.end(previous); }
        } else if (runs != 0) clear();
        targetMesh = null;
        targetSlot = -1;
        seen = count = 0;
        dirty = false;
    }

    boolean compactNext() {
        int checks = Math.min(32, remainingMeshes);
        while (checks-- > 0) {
            if (nextMesh >= visible.size()) nextMesh = 0;
            remainingMeshes--;
            Mesh mesh = visible.get(nextMesh++);
            if (!mesh.batchable || mesh.batchCount <= (mesh.size + OrderedBatch.CELLS_PER_LIST - 1)
                    / OrderedBatch.CELLS_PER_LIST || !mesh.source.canCompactGlass(mesh)) continue;
            int eye = mesh.orderEye;
            if (mesh.ordered == null || mesh.ordered.eye != eye) {
                mesh.clearOrdered();
                OrderedBatch ordered = new OrderedBatch(mesh, eye);
                if (ordered.first == 0) return false;
                mesh.ordered = ordered;
            }
            if (mesh.ordered.compiled == mesh.ordered.lists) continue;
            mesh.ordered.compileNext(mesh);
            if (mesh.ordered.compiled == mesh.ordered.lists) dirty = true;
            else {
                remainingMeshes++;
                nextMesh--;
            }
            return true;
        }
        return false;
    }

    private void sort(int x, int y, int z) {
        eyeX = x; eyeY = y; eyeZ = z;
        remainingMeshes = visible.size();
        partitions.clear();
        drawMeshes.clear();
        for (Mesh mesh : visible) {
            mesh.insertion = partitions.size();
            partitions.add(mesh);
        }
        partition(0, partitions.size(), 0);
        int commandCount = 0;
        for (Mesh mesh : visible) {
            mesh.usingOrdered = mesh.batchable && mesh.ordered != null && mesh.ordered.compiled == mesh.ordered.lists
                    && mesh.ordered.eye == mesh.orderEye;
            commandCount += mesh.usingOrdered ? mesh.ordered.lists : mesh.batchable ? mesh.batchCount : mesh.size;
        }
        if (keys.length < commandCount) {
            int capacity = Math.max(commandCount, keys.length + (keys.length >> 1));
            keys = new long[capacity];
            ids = new int[capacity];
            poses = new PreviewGrip[capacity];
            commands = BufferUtils.createIntBuffer(capacity);
        }
        int n = 0;
        cachedTarget = null;
        selectedCommandIndex = -1;
        int indexBits = 32 - Integer.numberOfLeadingZeros(commandCount - 1);
        long indexMask = (1L << indexBits) - 1;
        int groupStart = 0;
        for (Mesh mesh : drawMeshes) {
            if (n == groupStart) toGrid(null, mesh.grid, x, y, z, groupEye);
            // Equal-distance sections have disjoint ray intervals. Within each section the batches
            // follow exactly the same far-to-near cell ranks as the individual draws.
            if (mesh.usingOrdered) {
                OrderedBatch batch = mesh.ordered;
                for (int i = 0; i < batch.lists; i++) {
                    int entry = batch.order[i * OrderedBatch.CELLS_PER_LIST] & 4095;
                    int slot = mesh.parts.get(entry >> 6).slots[entry & 63];
                    keys[n] = drawOrder(mesh.x + (slot & 15), mesh.y + (slot >> 8), mesh.z + (slot >> 4 & 15),
                            groupEye[0], groupEye[1], groupEye[2]) << indexBits | n;
                    poses[n] = mesh.pose;
                    ids[n++] = batch.first + i;
                }
            } else {
                for (Part part : mesh.parts) {
                    int g = 0;
                    for (int i = 0; i < part.slots.length;) {
                        int slot = part.slots[i];
                        int wx = mesh.x + (slot & 15), wy = mesh.y + (slot >> 8), wz = mesh.z + (slot >> 4 & 15);
                        if (mesh.pose != mesh.grid) {
                            toGrid(mesh.pose, mesh.grid, wx, wy, wz, worldCell);
                            wx = worldCell[0]; wy = worldCell[1]; wz = worldCell[2];
                        }
                        keys[n] = drawOrder(wx, wy, wz, groupEye[0], groupEye[1], groupEye[2]) << indexBits | n;
                        poses[n] = mesh.pose;
                        if (mesh.batchable) {
                            ids[n++] = part.first + part.offsets[g];
                            i = part.ends[g++];
                        } else {
                            ids[n++] = part.first + i;
                            i++;
                        }
                    }
                }
            }
            if (mesh.groupEnd) {
                Arrays.sort(keys, groupStart, n);
                groupStart = n;
            }
        }
        int previousRuns = runs;
        runs = 0;
        commands.clear();
        for (int i = 0; i < n; i++) {
            int index = (int) (keys[i] & indexMask);
            PreviewGrip pose = poses[index];
            if (runs == 0 || runPoses[runs - 1] != pose) {
                if (runs == runEnds.length) {
                    int capacity = Math.max(8, runs * 2);
                    runEnds = Arrays.copyOf(runEnds, capacity);
                    runPoses = Arrays.copyOf(runPoses, capacity);
                }
                runPoses[runs++] = pose;
            }
            runEnds[runs - 1] = i + 1;
            commands.put(ids[index]);
        }
        if (runs < previousRuns) Arrays.fill(runPoses, runs, previousRuns, null);
        Arrays.fill(poses, 0, n, null);
        commands.flip();
    }

    private static void toGrid(PreviewGrip pose, PreviewGrip grid, int x, int y, int z, int[] out) {
        if (pose == grid) {
            out[0] = x; out[1] = y; out[2] = z;
            return;
        }
        if (pose != null) {
            pose.worldCell(x, y, z, out);
            x = out[0]; y = out[1]; z = out[2];
        }
        if (grid != null) grid.sourceCell(x, y, z, out);
    }

    // A plane between two sets of sections lets each side keep its own transparency order.
    private void partition(int from, int end, int depth) {
        Mesh first = partitions.get(from);
        PreviewGrip grid = first.pose;
        boolean uniform = true, singleTask = true;
        int balance = 0;
        for (int i = from; i < end; i++) {
            Mesh mesh = partitions.get(i);
            uniform &= mesh.pose == first.pose;
            singleTask &= first.task.equals(mesh.task);
            if (balance <= 0) {
                grid = mesh.pose;
                balance = 0;
            }
            balance += mesh.pose == grid ? mesh.size : -mesh.size;
        }
        List<Mesh> region = partitions.subList(from, end);
        if (!uniform && depth < 12) {
            for (int axis = 0; axis < 3; axis++) {
                final int a = axis;
                region.sort(Comparator.comparingInt(m -> m.worldMin[a]));
                int split = -1, best = 0;
                for (int i = from + 1; i < end; i++) {
                    int score = Math.min(i - from, end - i);
                    if (score > best && partitions.get(i - 1).worldMin[a] + 16 <= partitions.get(i).worldMin[a]) {
                        split = i;
                        best = score;
                    }
                }
                if (split < 0) continue;
                int eye = a == 0 ? eyeX : a == 1 ? eyeY : eyeZ;
                boolean lowFirst = eye >= partitions.get(split).worldMin[a];
                partition(lowFirst ? from : split, lowFirst ? split : end, depth + 1);
                partition(lowFirst ? split : from, lowFirst ? end : split, depth + 1);
                return;
            }
        }
        // For equal keys preserve the original order of coincident cells from different tasks.
        if (!uniform || depth != 0) region.sort(Comparator.comparingInt(m -> m.insertion));
        toGrid(null, grid, eyeX, eyeY, eyeZ, groupEye);
        if (!uniform || !singleTask) findSharedSections(region, grid);
        for (Mesh mesh : region) {
            mesh.grid = grid;
            mesh.orderEye = mesh.localEye(groupEye[0], groupEye[1], groupEye[2]);
            mesh.batchable = mesh.pose == grid && !sharedSections.contains(mesh.section);
            mesh.groupEnd = false;
            drawMeshes.add(mesh);
        }
        drawMeshes.get(drawMeshes.size() - 1).groupEnd = true;
        occupiedSections.clear();
        sharedSections.clear();
    }

    private void findSharedSections(List<Mesh> region, PreviewGrip grid) {
        for (Mesh mesh : region) {
            if (mesh.pose == grid) {
                if (!occupiedSections.add(mesh.section)) sharedSections.add(mesh.section);
                continue;
            }
            toGrid(mesh.pose, grid, mesh.x, mesh.y, mesh.z, corner);
            toGrid(mesh.pose, grid, mesh.x + 15, mesh.y + 15, mesh.z + 15, worldCell);
            for (int sx = Math.min(corner[0], worldCell[0]) >> 4; sx <= (Math.max(corner[0], worldCell[0]) >> 4); sx++)
                for (int sy = Math.min(corner[1], worldCell[1]) >> 4; sy <= (Math.max(corner[1], worldCell[1]) >> 4); sy++)
                    for (int sz = Math.min(corner[2], worldCell[2]) >> 4; sz <= (Math.max(corner[2], worldCell[2]) >> 4); sz++)
                        sharedSections.add(new ChunkPosition(sx, sy, sz));
        }
    }

    // Along a ray, each section crossing increases Manhattan distance. Inside the section visit
    // the far child of each split first, so all cells of a combined list have consecutive ranks.
    static long drawOrder(int x, int y, int z, int eyeX, int eyeY, int eyeZ) {
        int distance = Math.abs((x >> 4) - (eyeX >> 4)) + Math.abs((y >> 4) - (eyeY >> 4))
                + Math.abs((z >> 4) - (eyeZ >> 4));
        return (-(long) distance << 12) | axisOrder(y, eyeY) << 8 | axisOrder(z, eyeZ) << 4 | axisOrder(x, eyeX);
    }

    private static final int[][] AXIS_ORDER = new int[16][16];
    static {
        for (int eye = 0; eye < 16; eye++) {
            for (int cell = 0; cell < 16; cell++) {
                int rank = 0;
                for (int bit = 8; bit != 0; bit >>= 1) {
                    int split = (cell & -(bit * 2)) + bit;
                    if ((eye >= split) == ((cell & bit) != 0)) rank |= bit;
                }
                AXIS_ORDER[eye][cell] = rank;
            }
        }
    }

    private static int axisOrder(int cell, int eye) {
        int localEye = Math.max(0, Math.min(15, eye - (cell & ~15)));
        return AXIS_ORDER[localEye][cell & 15];
    }

    private void drawOrdered() {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(1f, 1f);
        GL11.glListBase(0);
        int start = 0;
        for (int run = 0; run < runs; run++) {
            PreviewGrip pose = runPoses[run];
            if (pose != null) {
                GL11.glPushMatrix();
                pose.transform();
                if (pose.mirrored) GL11.glCullFace(GL11.GL_FRONT);
            }
            int end = runEnds[run];
            if (start <= selectedCommandIndex && selectedCommandIndex < end) {
                submit(start, selectedCommandIndex);
                MarkerStroke.select(targetSlot);
                submit(selectedCommandIndex, selectedCommandIndex + 1);
                MarkerStroke.select(-1);
                start = selectedCommandIndex + 1;
            }
            submit(start, end);
            if (pose != null) {
                if (pose.mirrored) GL11.glCullFace(GL11.GL_BACK);
                GL11.glPopMatrix();
            }
            start = runEnds[run];
        }
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    private void submit(int start, int end) {
        if (start == end) return;
        commands.limit(end).position(start);
        GL11.glCallLists(commands);
    }

    void clear() {
        targetMesh = cachedTarget = null;
        selectedCommandIndex = -1;
        visible.clear();
        partitions.clear();
        drawMeshes.clear();
        remainingMeshes = nextMesh = 0;
        Arrays.fill(runPoses, 0, runs, null);
        runs = seen = count = 0;
        dirty = true;
    }
}
