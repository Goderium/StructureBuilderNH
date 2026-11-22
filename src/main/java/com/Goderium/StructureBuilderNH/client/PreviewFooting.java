package com.Goderium.StructureBuilderNH.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.IntBuffer;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Incremental world-space contact overlay for the selected structure. */
final class PreviewFooting {
    static final int COLOR = 0x8affd9;
    private static final int TILE = 8;
    private final Face[] faces = new Face[6];
    private int[][] indexedFooting;
    private final ArrayDeque<Scan> retired = new ArrayDeque<>();
    private final List<FootingMesh> visible = new ArrayList<>();
    private IntBuffer visibleLists = BufferUtils.createIntBuffer(16);
    private long renderedFrames;
    private boolean surfaceReady;
    private volatile Scan scan;

    void refresh(int[][] footing) {
        if (scan != null && indexedFooting == footing) scan.revisit = scan.lifetime.size();
    }

    boolean sample(World world, ProjectionOccupancy observer, int[][] footing, PreviewGrip moving,
            double eyeX, double eyeZ, int renderDistance, PreviewGrip acceptedDrop) {
        long deadline = System.nanoTime() + 1_250_000L;
        drainRetired();
        if (footing != indexedFooting) {
            Arrays.fill(faces, null);
            indexedFooting = footing;
        }
        int side = moving == null ? ForgeDirection.DOWN.ordinal() : moving.downwardFace.ordinal();
        int[] rows = footing == null || side >= footing.length ? null : footing[side];
        boolean handoff = moving == null && acceptedDrop != null && scan != null && scan.world == world
                && scan.atAcceptedDrop(acceptedDrop) && scan.settledFace == null;
        if (rows == null || rows.length < 4 || rows.length % 4 != 0) {
            if (!handoff) { retire(); return false; }
        } else {
            Face face = faces[side];
            if (face == null || face.rows != rows) faces[side] = face = new Face(rows, side);
            boolean indexed = face.index(deadline);
            if (handoff && indexed) handoff = scan.reconcileDrop(face, deadline);
            if (!handoff && scan != null && !scan.matches(world, face, moving)) retire();
            if (scan == null) {
                if (!indexed) return false;
                scan = new Scan(world, observer, face, moving);
            }
        }
        if (scan.y <= 0 || scan.y >= world.getHeight()) return false;
        scan.window((int) Math.floor(eyeX) >> 4, (int) Math.floor(eyeZ) >> 4, renderDistance);
        scan.applyChunkChanges(deadline);
        scan.advance(deadline);
        return !scan.contacts.isEmpty();
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    /** Source face index persists across translations. A row may cross a source tile boundary. */
    private static final class Face {
        final int[] rows;
        final int side, plane;
        final Map<Long, short[]> masks = new HashMap<>();
        long cells;
        int cursor, minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;

        Face(int[] rows, int side) {
            this.rows = rows;
            this.side = side;
            plane = rows[side < 2 ? 1 : side < 4 ? 2 : 0];
        }

        boolean index(long deadline) {
            for (int end = Math.min(rows.length, cursor + 512 * 4); cursor < end; cursor += 4) {
                if ((cursor & 63) == 0 && System.nanoTime() >= deadline) break;
                int u = rows[cursor + (side < 4 ? 0 : 2)], v = rows[cursor + (side < 2 ? 2 : 1)];
                int mask = rows[cursor + 3] & 0xffff;
                if (mask == 0) continue;
                minU = Math.min(minU, u + Integer.numberOfTrailingZeros(mask));
                maxU = Math.max(maxU, u + 31 - Integer.numberOfLeadingZeros(mask));
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
                int shifted = mask << (u & 15);
                put(u >> 4, v, shifted & 0xffff);
                put((u >> 4) + 1, v, shifted >>> 16);
            }
            return cursor == rows.length && minU <= maxU;
        }

        private void put(int tileU, int v, int bits) {
            if (bits == 0) return;
            short[] mask = masks.computeIfAbsent(key(tileU, v >> 4), ignored -> new short[16]);
            cells += Integer.bitCount(bits & ~mask[v & 15]);
            mask[v & 15] |= (short) bits;
        }

        void worldPoint(int u, int v, PreviewGrip moving, int[] out) {
            int x = side < 4 ? u : plane, y = side < 2 ? plane : v, z = side < 2 ? v : side < 4 ? plane : u;
            if (moving != null) moving.worldCell(x, y, z, out);
            else { out[0] = x; out[1] = y; out[2] = z; }
        }
    }

    private static final class Scan {
        final World world;
        final ProjectionOccupancy observer;
        final Face face;
        final PreviewGrip pose;
        final ExtendedFacing facing;
        final int atX, atY, atZ, originX, y, originZ, ux, uz, vx, vz;
        final int minX, minZ, maxX, maxZ;
        final boolean unmoved;
        Face settledFace, comparingFace;
        int compared;
        final Map<Long, Tile> tiles = new HashMap<>();
        final ArrayDeque<Tile> lifetime = new ArrayDeque<>(), pending = new ArrayDeque<>();
        final LinkedHashSet<Tile> contacts = new LinkedHashSet<>();
        final Map<Long, Boolean> changedColumns = new ConcurrentHashMap<>();
        final Map<Long, Group> groups = new HashMap<>();
        final List<AxisAlignedBB> collisions = new ArrayList<>(4);
        boolean closed;
        int cameraX = Integer.MIN_VALUE, cameraZ, distance, left, right, near, far, centerX, centerZ;
        int ix, iz, dx, dz, revisit;
        long remaining;
        double knownUnsupportedArea;

        boolean showFill() {
            // Until half the actual footprint is confirmed unsupported, undiscovered terrain could still put
            // the contact area over half. Draw the outline immediately without flashing a large filled surface.
            return knownUnsupportedArea >= face.cells * 0.5;
        }

        Scan(World world, ProjectionOccupancy observer, Face face, PreviewGrip pose) {
            this.world = world; this.observer = observer; this.face = face; this.pose = pose;
            facing = pose == null ? null : pose.facing;
            unmoved = pose == null || !pose.changed();
            atX = pose == null ? 0 : pose.at.posX; atY = pose == null ? 0 : pose.at.posY; atZ = pose == null ? 0 : pose.at.posZ;
            int[] p = new int[3];
            face.worldPoint(0, 0, pose, p);
            originX = p[0]; y = p[1]; originZ = p[2];
            face.worldPoint(1, 0, pose, p); ux = p[0] - originX; uz = p[2] - originZ;
            face.worldPoint(0, 1, pose, p); vx = p[0] - originX; vz = p[2] - originZ;
            face.worldPoint(face.minU, face.minV, pose, p);
            int x = p[0], z = p[2];
            face.worldPoint(face.maxU, face.maxV, pose, p);
            minX = Math.floorDiv(Math.min(x, p[0]), TILE); maxX = Math.floorDiv(Math.max(x, p[0]), TILE);
            minZ = Math.floorDiv(Math.min(z, p[2]), TILE); maxZ = Math.floorDiv(Math.max(z, p[2]), TILE);
        }

        boolean matches(World w, Face f, PreviewGrip p) {
            if (w != world) return false;
            if (p == null || !p.changed()) return f == settledFace || f == face && unmoved;
            return f == face && atAcceptedDrop(p);
        }

        boolean atAcceptedDrop(PreviewGrip p) {
            return p == pose && p.facing == facing && p.at.posX == atX && p.at.posY == atY && p.at.posZ == atZ;
        }

        /** The fixed drag pose is already in destination world coordinates. Verify the new bottom
         * face against it incrementally, without rereading terrain or rebuilding an identical mesh. */
        boolean reconcileDrop(Face destination, long deadline) {
            if (destination.plane != y || destination.cells != face.cells) return false;
            if (comparingFace != destination) {
                comparingFace = destination;
                compared = 0;
            }
            int[] rows = destination.rows;
            for (int end = Math.min(rows.length, compared + 128 * 4); compared < end; compared += 4) {
                if ((compared & 31) == 0 && System.nanoTime() >= deadline) break;
                int x = rows[compared], z = rows[compared + 2];
                for (int mask = rows[compared + 3] & 0xffff; mask != 0; mask &= mask - 1)
                    if (!candidate(x + Integer.numberOfTrailingZeros(mask), z)) return false;
            }
            if (compared == rows.length) {
                settledFace = destination;
                comparingFace = null;
            }
            return true;
        }

        void window(int cx, int cz, int range) {
            if (cx == cameraX && cz == cameraZ && range == distance) return;
            cameraX = cx; cameraZ = cz; distance = range;
            int radius = Math.max(1, range + 1) * (16 / TILE);
            left = Math.max(minX, cx * (16 / TILE) - radius); right = Math.min(maxX, (cx + 1) * (16 / TILE) + radius);
            near = Math.max(minZ, cz * (16 / TILE) - radius); far = Math.min(maxZ, (cz + 1) * (16 / TILE) + radius);
            centerX = Math.max(left, Math.min(right, cx * (16 / TILE)));
            centerZ = Math.max(near, Math.min(far, cz * (16 / TILE)));
            int extent = Math.max(Math.max(right - centerX, centerX - left), Math.max(far - centerZ, centerZ - near));
            remaining = left > right || near > far ? 0 : (2L * extent + 1) * (2L * extent + 1);
            ix = iz = dx = 0; dz = -1;
        }

        boolean inside(int tx, int tz) { return tx >= left && tx <= right && tz >= near && tz <= far; }
        int u(int x, int z) { return (x - originX) * ux + (z - originZ) * uz; }
        int v(int x, int z) { return (x - originX) * vx + (z - originZ) * vz; }

        boolean candidate(int x, int z) {
            int u = u(x, z), v = v(x, z);
            short[] rows = face.masks.get(key(u >> 4, v >> 4));
            return rows != null && (rows[v & 15] & 1 << (u & 15)) != 0;
        }

        void applyChunkChanges(long deadline) {
            for (Iterator<Map.Entry<Long, Boolean>> it = changedColumns.entrySet().iterator(); it.hasNext();) {
                if (System.nanoTime() >= deadline) return;
                Map.Entry<Long, Boolean> event = it.next();
                long col = event.getKey();
                boolean unload = event.getValue();
                if (!changedColumns.remove(col, unload)) continue;
                int x = (int) (col >> 32) << 4, z = (int) col << 4;
                for (int tx = Math.floorDiv(x - 2, TILE); tx <= Math.floorDiv(x + 17, TILE); tx++)
                    for (int tz = Math.floorDiv(z - 2, TILE); tz <= Math.floorDiv(z + 17, TILE); tz++) {
                        Tile t = tiles.get(key(tx, tz));
                        if (t != null) {
                            if (unload) t.publish(null, true, 0);
                            t.run();
                        }
                    }
            }
        }

        boolean hasPendingUnload(int x, int z, int blocks) {
            if (changedColumns.isEmpty()) return false;
            for (int sx = (x - 2) >> 4; sx <= ((x + blocks + 1) >> 4); sx++)
                for (int sz = (z - 2) >> 4; sz <= ((z + blocks + 1) >> 4); sz++)
                    if (Boolean.TRUE.equals(changedColumns.get(key(sx, sz)))) return true;
            return false;
        }

        private boolean hasMask(int x, int z) {
            int u0 = u(x, z), v0 = v(x, z), u1 = u(x + TILE - 1, z + TILE - 1), v1 = v(x + TILE - 1, z + TILE - 1);
            for (int a = Math.min(u0, u1) >> 4; a <= (Math.max(u0, u1) >> 4); a++)
                for (int b = Math.min(v0, v1) >> 4; b <= (Math.max(v0, v1) >> 4); b++)
                    if (face.masks.containsKey(key(a, b))) return true;
            return false;
        }

        void advance(long deadline) {
            // Retain overlapping tiles as the camera moves, releasing distant subscriptions gradually.
            for (int i = 0, n = Math.min(8, lifetime.size()); i < n; i++) {
                Tile t = lifetime.removeFirst();
                if (revisit > 0) {
                    revisit--;
                    if (inside(t.tx, t.tz)) t.run();
                }
                if (inside(t.tx, t.tz)) lifetime.addLast(t);
                else { t.unbind(); tiles.remove(key(t.tx, t.tz)); }
            }
            for (int work = 0; work < 160 && System.nanoTime() < deadline; work++) {
                Tile t = pending.peekFirst();
                if (t != null && (remaining == 0 || (work & 7) != 7)) {
                    if (!t.bound || t.step()) {
                        pending.removeFirst();
                        t.queued = false;
                        if (t.rescan) {
                            t.rescan = false;
                            t.run();
                        }
                    }
                    continue;
                }
                if (remaining-- <= 0) { remaining = 0; return; }
                int tx = centerX + ix, tz = centerZ + iz;
                if (ix == iz || ix < 0 && ix == -iz || ix > 0 && ix == 1 - iz) {
                    int tmp = dx; dx = -dz; dz = tmp;
                }
                ix += dx; iz += dz;
                if (!inside(tx, tz) || tiles.containsKey(key(tx, tz)) || !hasMask(tx * TILE, tz * TILE)) continue;
                t = new Tile(this, tx, tz);
                tiles.put(key(tx, tz), t);
                lifetime.addLast(t);
                t.watch(true);
                t.run();
            }
        }

        boolean readCell(int x, int z, List<double[]> rects) {
            for (int sx = (x - 1) >> 4; sx <= ((x + 1) >> 4); sx++)
                for (int sz = (z - 1) >> 4; sz <= ((z + 1) >> 4); sz++)
                    if (world.getChunkFromChunkCoords(sx, sz).isEmpty()) return false;
            if (!world.isAirBlock(x, y, z)) return true;
            Block support = world.getBlock(x, y - 1, z);
            if (support.isAir(world, x, y - 1, z) || support.getMaterial().isLiquid()) return true;
            AxisAlignedBB base = AxisAlignedBB.getBoundingBox(x, y - .001, z, x + 1., y + .001, z + 1.);
            collisions.clear();
            try {
                support.addCollisionBoxesToList(world, x, y - 1, z, base, collisions, null);
            } catch (RuntimeException unsupportedShape) { return false; }
            for (AxisAlignedBB box : collisions) if (box.intersectsWith(base) && box.maxY > y) return true;
            for (AxisAlignedBB box : collisions) {
                if (box.maxY != y || !box.intersectsWith(base)) continue;
                double x0 = Math.max(x, box.minX), x1 = Math.min(x + 1., box.maxX);
                double z0 = Math.max(z, box.minZ), z1 = Math.min(z + 1., box.maxZ);
                if (x0 < x1 && z0 < z1) rects.add(new double[]{x0, z0, x1, z1});
            }
            return true;
        }
    }

    private static final class Tile implements Runnable {
        final Scan scan;
        final int tx, tz, x, z;
        final ArrayList<double[]> rectangles = new ArrayList<>();
        int cursor, sampledCells;
        double unsupportedArea;
        boolean queued, rescan, bound = true;
        FootingMesh mesh;

        Tile(Scan scan, int tx, int tz) { this.scan = scan; this.tx = tx; this.tz = tz; x = tx * TILE; z = tz * TILE; }

        void watch(boolean add) {
            // The contour needs adjacent cells, and each collision shape can depend on its neighbours.
            for (int sx = (x - 2) >> 4; sx <= ((x + TILE + 1) >> 4); sx++)
                for (int sz = (z - 2) >> 4; sz <= ((z + TILE + 1) >> 4); sz++)
                    for (int sy = Math.max(0, scan.y - 2) >> 4; sy <= (scan.y >> 4); sy++) {
                        if (add) scan.observer.watchChanges(sx, sy, sz, this);
                        else scan.observer.unwatch(sx, sy, sz, this);
                    }
        }

        void unbind() { bound = false; watch(false); publish(null, true, 0); }

        @Override public void run() {
            if (!bound || scan.closed) return;
            if (queued) {
                // Keep making progress under continuous block updates. Revisit cells already
                // sampled once this pass is published, behind the other waiting tiles.
                rescan |= cursor != 0;
                return;
            }
            cursor = 0;
            sampledCells = 0;
            rectangles.clear();
            queued = true;
            scan.pending.addLast(this);
        }

        boolean step() {
            int span = TILE + 2;
            if (cursor < span * span) {
                int wx = x - 1 + cursor % span, wz = z - 1 + cursor / span;
                cursor++;
                if (scan.candidate(wx, wz) && scan.readCell(wx, wz, rectangles)
                        && wx >= x && wx < x + TILE && wz >= z && wz < z + TILE) sampledCells++;
                return false;
            }
            FootingMesh next = FootingMesh.build(rectangles, x, z, TILE);
            rectangles.clear();
            publish(next.fills.isEmpty() ? null : next, false, sampledCells - next.area);
            return true;
        }

        void publish(FootingMesh next, boolean immediate, double nextUnsupportedArea) {
            scan.knownUnsupportedArea += nextUnsupportedArea - unsupportedArea;
            unsupportedArea = nextUnsupportedArea;
            if (next == mesh || next != null && mesh != null
                    && same(next.fills, mesh.fills) && same(next.edges, mesh.edges)) return;
            mesh = next;
            if (next == null) scan.contacts.remove(this);
            else scan.contacts.add(this);
            long id = key(tx >> 2, tz >> 2);
            Group group = scan.groups.computeIfAbsent(id, ignored -> new Group((tx >> 2) * 4, (tz >> 2) * 4));
            int slot = (tx & 3) + ((tz & 3) << 2);
            if (group.tiles[slot] != null) group.count--;
            group.tiles[slot] = next != null ? this : null;
            if (next != null) group.count++;
            group.dirty = true;
            group.stale |= immediate;
            if (group.count == 0) { group.release(); scan.groups.remove(id); }
        }

        private static boolean same(List<double[]> a, List<double[]> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) return false;
            return true;
        }
    }

    void chunkChanged(int sx, int sz, boolean unload) {
        Scan current = scan;
        if (current == null || (sx << 4) + 17 < current.minX * TILE || (sx << 4) - 2 >= (current.maxX + 1) * TILE
                || (sz << 4) + 17 < current.minZ * TILE || (sz << 4) - 2 >= (current.maxZ + 1) * TILE) return;
        current.changedColumns.merge(key(sx, sz), unload, (a, b) -> a || b);
    }

    /** One pair of display lists per 32 x 32 region, shared by depth and color passes. */
    private static final class Group {
        final int tx, tz;
        final Tile[] tiles = new Tile[16];
        int count, lists;
        long retryFrame;
        boolean dirty = true, stale;

        Group(int tx, int tz) { this.tx = tx; this.tz = tz; }

        void compile(double y, long frame) {
            if (lists == 0) lists = GL11.glGenLists(2);
            if (lists == 0) {
                retryFrame = frame + 60;
                return;
            }
            retryFrame = 0;
            Tessellator t = Tessellator.instance;
            for (int pass = 0; pass < 2; pass++) {
                GL11.glNewList(lists + pass, GL11.GL_COMPILE);
                t.startDrawing(pass == 0 ? GL11.GL_QUADS : GL11.GL_LINES);
                for (Tile tile : tiles) if (tile != null) emit(t, tile.mesh, y, pass == 0);
                t.draw();
                GL11.glEndList();
            }
            dirty = stale = false;
        }

        void release() { if (lists != 0) { GL11.glDeleteLists(lists, 2); lists = 0; } }
    }

    private static void emit(Tessellator t, FootingMesh mesh, double y, boolean faces) {
        if (faces) for (double[] r : mesh.fills) {
            t.addVertex(r[0], y, r[1]); t.addVertex(r[0], y, r[3]);
            t.addVertex(r[2], y, r[3]); t.addVertex(r[2], y, r[1]);
        } else for (double[] e : mesh.edges) {
            t.addVertex(e[0], y, e[1]); t.addVertex(e[2], y, e[3]);
        }
    }

    void writeDepth(Frustrum camera) {
        surfaceReady = false;
        if (scan == null || scan.groups.isEmpty()) return;
        visible.clear();
        if (visibleLists.capacity() < scan.groups.size()) visibleLists = BufferUtils.createIntBuffer(scan.groups.size() * 2);
        visibleLists.clear();
        double y = scan.y;
        long frame = ++renderedFrames;
        boolean compiled = false;
        for (Group g : scan.groups.values()) {
            int x = g.tx * TILE, z = g.tz * TILE;
            if (g.tx + 3 < scan.left || g.tz + 3 < scan.near || g.tx > scan.right || g.tz > scan.far
                    || !camera.isBoxInFrustum(x, y - .1, z, x + 4 * TILE, y + .1, z + 4 * TILE)) continue;
            boolean unloading = scan.hasPendingUnload(x, z, 4 * TILE);
            if (g.dirty && !unloading && !compiled && frame >= g.retryFrame) { g.compile(y, frame); compiled = true; }
            if (g.lists != 0 && !g.stale && !unloading) visibleLists.put(g.lists);
            else if (g.lists != 0 || g.retryFrame != 0) {
                // Keep unaffected parts of a previously drawn region during unload, or recover from a GL allocation failure.
                for (Tile t : g.tiles) if (t != null && (!unloading || !scan.hasPendingUnload(t.x, t.z, TILE)))
                    visible.add(t.mesh);
            }
        }
        visibleLists.flip();
        if (!visibleLists.hasRemaining() && visible.isEmpty()) return;
        surfaceReady = true;
        if (!scan.showFill()) return;
        pushPaintState();
        try {
            // Solid supports already supply their own color and depth. Back only the pixels where
            // the first world fragment is farther away (for example, an opening in a glass texture).
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(1f, 1f);
            GL11.glDepthFunc(GL11.GL_LESS);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);
            geometry(y, true, 0x477d70, 255);
        } finally { GL11.glPopAttrib(); }
    }

    void drawSurface() {
        if (!surfaceReady) return;
        surfaceReady = false;
        double y = scan.y;
        pushPaintState();
        try {
            // The footprint is a placement aid. Keep even an interior contact visible through the finished shell.
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (scan.showFill()) geometry(y, true, 0x63ffd1, 100);
            GL11.glLineWidth(8f);
            geometry(y, false, 0x174b46, 255);
            GL11.glLineWidth(5f);
            geometry(y, false, COLOR, 255);
        } finally { GL11.glPopAttrib(); }
    }

    private static void pushPaintState() {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_LINE_BIT | GL11.GL_POLYGON_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIST_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDepthMask(false);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_LINE_STIPPLE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void geometry(double y, boolean faces, int color, int alpha) {
        GL11.glColor4ub((byte)(color >> 16), (byte)(color >> 8), (byte)color, (byte)alpha);
        // Angelica consumes the IntBuffer during glCallLists. Replay the same visible groups for every pass.
        visibleLists.rewind();
        if (visibleLists.hasRemaining()) {
            GL11.glListBase(faces ? 0 : 1);
            GL11.glCallLists(visibleLists);
        }
        if (visible.isEmpty()) return;
        Tessellator t = Tessellator.instance;
        t.startDrawing(faces ? GL11.GL_QUADS : GL11.GL_LINES);
        for (FootingMesh mesh : visible) emit(t, mesh, y, faces);
        t.draw();
    }

    private void retire() {
        surfaceReady = false;
        if (scan == null) return;
        scan.closed = true;
        retired.addLast(scan);
        scan = null;
        visible.clear();
    }

    private void drainRetired() {
        for (int i = 0; i < 16 && !retired.isEmpty(); i++) {
            Tile t = retired.peekFirst().lifetime.pollFirst();
            if (t != null) t.unbind();
            else retired.removeFirst();
        }
    }

    void clear() {
        retire();
        drainRetired();
    }

    /** The old observer drops all its subscriptions on world replacement. */
    void worldChanged() {
        retire();
        for (Scan old : retired) for (Group g : old.groups.values()) g.release();
        retired.clear();
        Arrays.fill(faces, null);
        indexedFooting = null;
    }
}
