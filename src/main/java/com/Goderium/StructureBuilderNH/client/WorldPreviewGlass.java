package com.Goderium.StructureBuilderNH.client;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Translucent voxel surfaces share the world's solid depth, including across preview tasks. */
final class WorldPreviewGlass {
    private static final int GLASS_ALPHA = 184;

    /** ProjectedBlockFaces visits slots in order, with at most 64 cells in each compile step. */
    static final class Collector {
        final short[] slots = new short[64];
        final byte[] masks = new byte[64];
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
        final int index, first;
        final short[] slots;
        final boolean combined;
        Part(int index, int first, Collector source, boolean combined) {
            this.index = index;
            this.first = first;
            this.slots = Arrays.copyOf(source.slots, source.size);
            this.combined = combined;
        }
    }

    static final class Mesh {
        final int x, y, z;
        final UUID task;
        final List<Part> parts = new ArrayList<>();
        final int[] bounds = {16, -1, 16, -1, 16, -1}, planes = {-1, -1, -1, -1, -1, -1};
        boolean exterior = true;
        int size;
        int sortedSize, poseX, poseY, poseZ, orientation;
        PreviewGrip pose;

        Mesh(int x, int y, int z, UUID task) { this.x = x; this.y = y; this.z = z; this.task = task; }

        // Any subset of the outward faces of one convex box has at most one front-facing
        // fragment along a ray. A window hole, internal pane or second skin can break this.
        private boolean exteriorFaces(Collector source) {
            for (int i = 0; i < source.size; i++) {
                int slot = source.slots[i];
                for (int axis = 0; axis < 3; axis++) {
                    int p = axis == 0 ? slot & 15 : axis == 1 ? slot >> 8 : slot >> 4 & 15;
                    int low = axis * 2;
                    bounds[low] = Math.min(bounds[low], p);
                    bounds[low + 1] = Math.max(bounds[low + 1], p);
                    for (int side = low; side <= low + 1; side++) {
                        if ((source.masks[i] & 1 << side) == 0) continue;
                        if (planes[side] != -1 && planes[side] != p) return false;
                        planes[side] = p;
                    }
                }
            }
            for (int side = 0; side < 6; side++)
                if (planes[side] != -1 && planes[side] != bounds[side]) return false;
            return true;
        }

        boolean replace(int index, BuilderRenderer.Section section, IBlockAccess access, Collector source) {
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
            // A fluid surface can be below the grid plane or against the ceiling of its cell.
            // Its integer voxel bounds alone cannot prove the surfaces form a convex box.
            for (int i = 0; exterior && i < source.size; i++)
                if (section.appearance(source.slots[i]).fluid != null) exterior = false;
            exterior = exterior && exteriorFaces(source);
            boolean combined = exterior && source.size > 1;
            int listCount = source.size + (combined ? 1 : 0);
            int first = GL11.glGenLists(listCount);
            if (first == 0) return false;
            Part fresh = new Part(index, first, source, combined);
            Tessellator t = Tessellator.instance;
            for (int i = 0; i < listCount; i++) {
                BuilderRenderer.beginSectionList(first + i, section);
                try {
                    t.startDrawingQuads();
                    t.setBrightness(0xf000f0);
                    if (i == source.size) {
                        for (int cell = 0; cell < source.size; cell++) emitCell(t, section, access, source, cell);
                    } else emitCell(t, section, access, source, i);
                    t.draw();
                } finally { BuilderRenderer.endSectionList(); }
            }
            if (old == null) parts.add(position, fresh);
            else {
                parts.set(position, fresh);
                discard(old);
            }
            size += source.size;
            source.size = 0;
            return true;
        }

        private void discard(Part old) {
            GL11.glDeleteLists(old.first, old.slots.length + (old.combined ? 1 : 0));
            size -= old.slots.length;
            // The number of cells may be unchanged while their display-list IDs have changed.
            sortedSize = -1;
        }

        private void emitCell(Tessellator t, BuilderRenderer.Section section, IBlockAccess access, Collector source, int index) {
            int slot = source.slots[index];
            BlockPreviewTextures.Appearance appearance = section.appearance(slot);
            for (int faces = source.masks[index]; faces != 0; faces &= faces - 1) {
                int side = Integer.numberOfTrailingZeros(faces);
                t.setBrightness(BuilderRenderer.markerData(section, slot, side, true));
                BlockPreviewTextures.face(t, appearance, side,
                        slot, appearance.fluid == null ? GLASS_ALPHA : 255, access, x + (slot & 15), y + (slot >> 8), z + (slot >> 4 & 15));
            }
        }

        int commandFor(int slot, boolean grouped) {
            for (Part part : parts) {
                int index = Arrays.binarySearch(part.slots, (short) slot);
                if (index >= 0) return part.first + (grouped && part.combined ? part.slots.length : index);
            }
            return 0;
        }

        void release() {
            for (Part part : parts) GL11.glDeleteLists(part.first, part.slots.length + (part.combined ? 1 : 0));
            parts.clear();
            size = 0;
        }

        boolean updatePose(PreviewGrip next) {
            int px = 0, py = 0, pz = 0, facing = 0;
            if (next != null) {
                px = next.at.posX; py = next.at.posY; pz = next.at.posZ;
                facing = next.facing.ordinal();
            }
            boolean changed = size != sortedSize || pose != next || px != poseX || py != poseY
                    || pz != poseZ || facing != orientation;
            sortedSize = size; pose = next; poseX = px; poseY = py; poseZ = pz; orientation = facing;
            return changed;
        }
    }

    private final List<Mesh> visible = new ArrayList<>();
    private final int[] worldCell = new int[3];
    private long[] keys = new long[0];
    private int[] ids = new int[0];
    private PreviewGrip[] poses = new PreviewGrip[0];
    private IntBuffer commands;
    private int[] runEnds = new int[0];
    private PreviewGrip[] runPoses = new PreviewGrip[0];
    private int runs, seen, count, eyeX, eyeY, eyeZ;
    private boolean dirty, grouped;
    private Mesh targetMesh, cachedTarget;
    private int targetSlot, cachedSlot, selectedCommandIndex = -1;

    void include(Mesh mesh, PreviewGrip pose, boolean marked) {
        dirty |= mesh.updatePose(pose);
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
                    int list = targetMesh.commandFor(targetSlot, grouped);
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

    private void sort(int x, int y, int z) {
        eyeX = x; eyeY = y; eyeZ = z;
        if (keys.length < count) {
            int capacity = Math.max(count, keys.length + (keys.length >> 1));
            keys = new long[capacity];
            ids = new int[capacity];
            poses = new PreviewGrip[capacity];
            commands = BufferUtils.createIntBuffer(capacity);
        }
        int n = 0;
        // Non-overlapping sections of a single stationary projection share a voxel grid.
        // Its convex exteriors can therefore be submitted in section order, in whole parts.
        grouped = true;
        cachedTarget = null;
        selectedCommandIndex = -1;
        UUID task = visible.get(0).task;
        for (Mesh mesh : visible) if (!mesh.exterior || mesh.pose != null || !mesh.task.equals(task)) {
            grouped = false;
            break;
        }
        for (Mesh mesh : visible) {
            if (grouped) {
                int distance = Math.abs((mesh.x >> 4) - (x >> 4)) + Math.abs((mesh.y >> 4) - (y >> 4))
                        + Math.abs((mesh.z >> 4) - (z >> 4));
                for (Part part : mesh.parts) {
                    keys[n] = ((long) -distance << 32) | n;
                    ids[n++] = part.combined ? part.first + part.slots.length : part.first;
                }
                continue;
            }
            for (Part part : mesh.parts) for (int i = 0; i < part.slots.length; i++) {
                int slot = part.slots[i];
                int wx = mesh.x + (slot & 15), wy = mesh.y + (slot >> 8), wz = mesh.z + (slot >> 4 & 15);
                if (mesh.pose != null) {
                    mesh.pose.worldCell(wx, wy, wz, worldCell);
                    wx = worldCell[0]; wy = worldCell[1]; wz = worldCell[2];
                }
                // Each voxel boundary a ray crosses increases its Manhattan distance from the eye cell.
                // Equal-distance voxels never overlap along that ray. Turning in place needs no re-sort.
                int distance = Math.abs(wx - x) + Math.abs(wy - y) + Math.abs(wz - z);
                keys[n] = ((long) -distance << 32) | n;
                poses[n] = mesh.pose;
                ids[n++] = part.first + i;
            }
        }
        Arrays.sort(keys, 0, n);
        int previousRuns = runs;
        runs = 0;
        commands.clear();
        for (int i = 0; i < n; i++) {
            int index = (int) keys[i];
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
        Arrays.fill(runPoses, 0, runs, null);
        runs = seen = count = 0;
        dirty = true;
    }
}
