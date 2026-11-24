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
    private static final int HALO_SPAN = TILE + 2;
    private final ArrayDeque<Scan> retired = new ArrayDeque<>();
    private final List<FootingMesh> visible = new ArrayList<>();
    private final List<Group> visibleBorders = new ArrayList<>();
    private IntBuffer visibleLists = BufferUtils.createIntBuffer(16);
    private long renderedFrames;
    private boolean surfaceReady, drawFill;
    boolean building;
    private volatile Scan scan;

    boolean sample(World world, ProjectionOccupancy observer, Face face, ForgeDirection direction, PreviewGrip moving,
            int cx, int cy, int cz, int renderDistance, PreviewGrip acceptedDrop, long deadline) {
        drainRetired();
        boolean handoff = moving == null && acceptedDrop != null && scan != null && scan.world == world
                && scan.atAcceptedDrop(acceptedDrop) && scan.settledFace == null;
        if (face == null) {
            if (!handoff) { retire(); return false; }
        } else {
            boolean indexed = face.index(deadline);
            if (handoff && indexed) handoff = scan.reconcileDrop(face, deadline);
            if (!handoff && scan != null && !scan.matches(world, face, moving)) retire();
            if (scan == null) {
                if (!indexed) return false;
                scan = new Scan(world, observer, face, moving, direction);
            }
        }
        if (!scan.validHeight()) return false;
        scan.window(cx, cy, cz, renderDistance);
        scan.applyChunkChanges(deadline);
        scan.advance(deadline);
        return !scan.contacts.isEmpty();
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    /** Source face index persists across translations. A row may cross a source tile boundary. */
    static final class Face {
        final int[] rows;
        final int side, plane;
        final ContactPlane axes;
        final Map<Long, short[]> masks = new HashMap<>();
        long cells;
        int cursor, minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;

        Face(int[] rows, int side) {
            this.rows = rows;
            this.side = side;
            axes = ContactPlane.of(ForgeDirection.getOrientation(side));
            plane = axes.normal(rows[0], rows[1], rows[2]);
        }

        boolean index(long deadline) {
            for (int end = Math.min(rows.length, cursor + 512 * 4); cursor < end; cursor += 4) {
                if ((cursor & 63) == 0 && System.nanoTime() >= deadline) break;
                int x = rows[cursor], y = rows[cursor + 1], z = rows[cursor + 2];
                int u = axes.u(x, y, z), v = axes.v(x, y, z);
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
            axes.world(plane, u, v, out);
            if (moving != null) moving.worldCell(out[0], out[1], out[2], out);
        }
    }

    private static final class Scan {
        final World world;
        final ProjectionOccupancy observer;
        final Face face;
        final PreviewGrip pose;
        final ExtendedFacing facing;
        final ForgeDirection direction;
        final ContactPlane axes;
        final int atX, atY, atZ, plane, surface, minNormal, maxNormal;
        final int originU, originV, uToU, uToV, vToU, vToV;
        final int minU, minV, maxU, maxV;
        final int edgeMinU, edgeMaxU, edgeMinV, edgeMaxV;
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
        int cameraU = Integer.MIN_VALUE, cameraV, cameraNormal, distance, left, right, near, far, centerU, centerV;
        int ix, iz, dx, dz;
        long remaining;
        double knownUnsupportedArea;

        boolean showFill() {
            // Until half the actual footprint is confirmed unsupported, undiscovered terrain could still put
            // the contact area over half. Draw the outline immediately without flashing a large filled surface.
            return knownUnsupportedArea >= face.cells * 0.5;
        }

        Scan(World world, ProjectionOccupancy observer, Face face, PreviewGrip pose, ForgeDirection direction) {
            this.world = world; this.observer = observer; this.face = face; this.pose = pose;
            this.direction = direction;
            axes = ContactPlane.of(direction);
            facing = pose == null ? null : pose.facing;
            unmoved = pose == null || !pose.changed();
            atX = pose == null ? 0 : pose.at.posX; atY = pose == null ? 0 : pose.at.posY; atZ = pose == null ? 0 : pose.at.posZ;
            int[] p = new int[3];
            face.worldPoint(0, 0, pose, p);
            plane = axes.normal(p[0], p[1], p[2]);
            surface = axes.surface(plane);
            minNormal = (axes.positive ? plane : plane - 1) - 1;
            maxNormal = (axes.positive ? plane + 1 : plane) + 1;
            originU = axes.u(p[0], p[1], p[2]);
            originV = axes.v(p[0], p[1], p[2]);
            face.worldPoint(1, 0, pose, p);
            uToU = axes.u(p[0], p[1], p[2]) - originU;
            uToV = axes.v(p[0], p[1], p[2]) - originV;
            face.worldPoint(0, 1, pose, p);
            vToU = axes.u(p[0], p[1], p[2]) - originU;
            vToV = axes.v(p[0], p[1], p[2]) - originV;
            face.worldPoint(face.minU, face.minV, pose, p);
            int firstU = axes.u(p[0], p[1], p[2]), firstV = axes.v(p[0], p[1], p[2]);
            face.worldPoint(face.maxU, face.maxV, pose, p);
            int lastU = axes.u(p[0], p[1], p[2]), lastV = axes.v(p[0], p[1], p[2]);
            edgeMinU = Math.min(firstU, lastU); edgeMaxU = Math.max(firstU, lastU) + 1;
            edgeMinV = Math.min(firstV, lastV); edgeMaxV = Math.max(firstV, lastV) + 1;
            minU = Math.floorDiv(edgeMinU, TILE);
            maxU = Math.floorDiv(edgeMaxU - 1, TILE);
            minV = Math.floorDiv(axes.horizontal ? edgeMinV : Math.max(0, edgeMinV), TILE);
            maxV = Math.floorDiv((axes.horizontal ? edgeMaxV : Math.min(world.getHeight(), edgeMaxV)) - 1, TILE);
        }

        boolean validHeight() {
            if (!axes.horizontal) return minV <= maxV;
            int outside = plane + direction.offsetY;
            return plane >= 0 && plane < world.getHeight() && outside >= 0 && outside < world.getHeight();
        }

        boolean matches(World w, Face f, PreviewGrip p) {
            if (w != world) return false;
            if (p == null || !p.changed()) return f == settledFace || f == face && unmoved;
            return f == face && atAcceptedDrop(p);
        }

        boolean atAcceptedDrop(PreviewGrip p) {
            return p == pose && p.facing == facing && p.at.posX == atX && p.at.posY == atY && p.at.posZ == atZ;
        }

        /** Compare the destination face against the fixed drag pose without rereading terrain. */
        boolean reconcileDrop(Face destination, long deadline) {
            if (destination.side != direction.ordinal() || destination.plane != plane || destination.cells != face.cells)
                return false;
            if (comparingFace != destination) {
                comparingFace = destination;
                compared = 0;
            }
            int[] rows = destination.rows;
            for (int end = Math.min(rows.length, compared + 128 * 4); compared < end; compared += 4) {
                if ((compared & 31) == 0 && System.nanoTime() >= deadline) break;
                int x = rows[compared], y = rows[compared + 1], z = rows[compared + 2];
                int u = axes.u(x, y, z), v = axes.v(x, y, z);
                for (int mask = rows[compared + 3] & 0xffff; mask != 0; mask &= mask - 1)
                    if (!candidate(u + Integer.numberOfTrailingZeros(mask), v)) return false;
            }
            if (compared == rows.length) {
                settledFace = destination;
                comparingFace = null;
            }
            return true;
        }

        void window(int cx, int cy, int cz, int range) {
            int cu = axes.u(cx, cy, cz), cv = axes.v(cx, cy, cz);
            int cn = axes.horizontal ? 0 : axes.normal(cx, cy, cz);
            if (cu == cameraU && cv == cameraV && cn == cameraNormal && range == distance) return;
            cameraU = cu; cameraV = cv; cameraNormal = cn; distance = range;
            int radius = Math.max(1, range + 1);
            int tilesPerChunk = 16 / TILE;
            left = Math.max(minU, (cu - radius) * tilesPerChunk);
            right = Math.min(maxU, (cu + 1 + radius) * tilesPerChunk);
            if (axes.horizontal) {
                near = Math.max(minV, (cv - radius) * tilesPerChunk);
                far = Math.min(maxV, (cv + 1 + radius) * tilesPerChunk);
            } else {
                near = minV;
                far = maxV;
                int normalChunk = plane >> 4;
                if (normalChunk < cn - radius || normalChunk > cn + 1 + radius) left = right + 1;
            }
            centerU = Math.max(left, Math.min(right, cu * tilesPerChunk));
            centerV = Math.max(near, Math.min(far, cv * tilesPerChunk));
            int extent = Math.max(Math.max(right - centerU, centerU - left), Math.max(far - centerV, centerV - near));
            remaining = left > right || near > far ? 0 : (2L * extent + 1) * (2L * extent + 1);
            ix = iz = dx = 0; dz = -1;
        }

        boolean inside(int tu, int tv) { return tu >= left && tu <= right && tv >= near && tv <= far; }

        boolean overlaps(int fromU, int fromV, int size) {
            return left <= right && near <= far && fromU <= right && fromV <= far
                    && fromU + size - 1 >= left && fromV + size - 1 >= near;
        }

        private int sourceU(int u, int v) { return (u - originU) * uToU + (v - originV) * uToV; }
        private int sourceV(int u, int v) { return (u - originU) * vToU + (v - originV) * vToV; }

        boolean candidate(int u, int v) {
            int su = sourceU(u, v), sv = sourceV(u, v);
            short[] rows = face.masks.get(key(su >> 4, sv >> 4));
            return rows != null && (rows[sv & 15] & 1 << (su & 15)) != 0;
        }

        void chunkChanged(int sx, int sz, boolean unload) {
            int x = sx << 4, z = sz << 4;
            int u = axes.u(x, 0, z);
            if (u + 17 < minU * TILE || u - 2 >= (maxU + 1) * TILE) return;
            if (axes.horizontal) {
                int v = axes.v(x, 0, z);
                if (v + 17 < minV * TILE || v - 2 >= (maxV + 1) * TILE) return;
            } else {
                int normal = axes.normal(x, 0, z);
                if (normal + 15 < minNormal || normal > maxNormal) return;
            }
            changedColumns.merge(key(sx, sz), unload, (a, b) -> a || b);
        }

        void applyChunkChanges(long deadline) {
            for (Iterator<Map.Entry<Long, Boolean>> it = changedColumns.entrySet().iterator(); it.hasNext();) {
                if (System.nanoTime() >= deadline) return;
                Map.Entry<Long, Boolean> event = it.next();
                long col = event.getKey();
                boolean unload = event.getValue();
                if (!changedColumns.remove(col, unload)) continue;
                int x = (int) (col >> 32) << 4, z = (int) col << 4;
                int u = axes.u(x, 0, z), v = axes.v(x, 0, z);
                int firstV = axes.horizontal ? Math.floorDiv(v - 2, TILE) : minV;
                int lastV = axes.horizontal ? Math.floorDiv(v + 17, TILE) : maxV;
                for (int tu = Math.floorDiv(u - 2, TILE); tu <= Math.floorDiv(u + 17, TILE); tu++)
                    for (int tv = firstV; tv <= lastV; tv++) {
                        Tile t = tiles.get(key(tu, tv));
                        if (t != null) {
                            if (unload) t.unload();
                            else t.run();
                        }
                    }
            }
        }

        boolean hasPendingUnload(int u, int v, int blocks) {
            if (changedColumns.isEmpty()) return false;
            int lowU = u - 2, lowV = v - 2, highU = u + blocks + 1, highV = v + blocks + 1;
            int minX = axes.x(minNormal, lowU, lowV) >> 4, maxX = axes.x(maxNormal, highU, highV) >> 4;
            int minZ = axes.z(minNormal, lowU, lowV) >> 4, maxZ = axes.z(maxNormal, highU, highV) >> 4;
            for (int sx = minX; sx <= maxX; sx++)
                for (int sz = minZ; sz <= maxZ; sz++)
                    if (Boolean.TRUE.equals(changedColumns.get(key(sx, sz)))) return true;
            return false;
        }

        private boolean hasMask(int u, int v) {
            int u0 = sourceU(u, v), v0 = sourceV(u, v);
            int u1 = sourceU(u + TILE - 1, v + TILE - 1), v1 = sourceV(u + TILE - 1, v + TILE - 1);
            for (int a = Math.min(u0, u1) >> 4; a <= (Math.max(u0, u1) >> 4); a++)
                for (int b = Math.min(v0, v1) >> 4; b <= (Math.max(v0, v1) >> 4); b++)
                    if (face.masks.containsKey(key(a, b))) return true;
            return false;
        }

        void advance(long deadline) {
            // Retain overlapping tiles as the camera moves, releasing distant subscriptions gradually.
            for (int i = 0, n = Math.min(8, lifetime.size()); i < n; i++) {
                Tile t = lifetime.removeFirst();
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
                int tx = centerU + ix, tz = centerV + iz;
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

        boolean readCell(int u, int v, Tile tile) {
            int x = axes.x(plane, u, v), y = axes.y(plane, u, v), z = axes.z(plane, u, v);
            int nx = x + direction.offsetX, ny = y + direction.offsetY, nz = z + direction.offsetZ;
            if (y < 0 || y >= world.getHeight() || ny < 0 || ny >= world.getHeight()) return false;
            for (int sx = (Math.min(x, nx) - 1) >> 4; sx <= ((Math.max(x, nx) + 1) >> 4); sx++)
                for (int sz = (Math.min(z, nz) - 1) >> 4; sz <= ((Math.max(z, nz) + 1) >> 4); sz++)
                    if (world.getChunkFromChunkCoords(sx, sz).isEmpty()) return false;
            if (!world.isAirBlock(x, y, z)) return true;
            Block support = world.getBlock(nx, ny, nz);
            if (support.isAir(world, nx, ny, nz) || support.getMaterial().isLiquid()) return true;
            AxisAlignedBB base = axes.bounds(surface - .001, surface + .001, u, v, u + 1., v + 1.);
            collisions.clear();
            try {
                support.addCollisionBoxesToList(world, nx, ny, nz, base, collisions, null);
            } catch (RuntimeException unsupportedShape) { return false; }
            boolean full = false;
            for (AxisAlignedBB box : collisions) {
                if (!box.intersectsWith(base)) continue;
                double inner = innerBound(box);
                if (axes.positive ? inner < surface : inner > surface) return true;
                if (inner == surface && axes.u(box.minX, box.minY, box.minZ) <= u
                        && axes.u(box.maxX, box.maxY, box.maxZ) >= u + 1
                        && axes.v(box.minX, box.minY, box.minZ) <= v
                        && axes.v(box.maxX, box.maxY, box.maxZ) >= v + 1) full = true;
            }
            if (full) {
                tile.fullRows[v - tile.z + 1] |= 1 << (u - tile.x + 1);
                return true;
            }
            for (AxisAlignedBB box : collisions) {
                if (innerBound(box) != surface || !box.intersectsWith(base)) continue;
                double u0 = Math.max(u, axes.u(box.minX, box.minY, box.minZ));
                double u1 = Math.min(u + 1., axes.u(box.maxX, box.maxY, box.maxZ));
                double v0 = Math.max(v, axes.v(box.minX, box.minY, box.minZ));
                double v1 = Math.min(v + 1., axes.v(box.maxX, box.maxY, box.maxZ));
                if (u0 < u1 && v0 < v1) tile.rectangles.add(new double[] { u0, v0, u1, v1 });
            }
            return true;
        }

        private double innerBound(AxisAlignedBB box) {
            return axes.positive ? axes.normal(box.minX, box.minY, box.minZ)
                    : axes.normal(box.maxX, box.maxY, box.maxZ);
        }
    }

    private static final class Tile implements Runnable {
        final Scan scan;
        final int tx, tz, x, z;
        final ArrayList<double[]> rectangles = new ArrayList<>();
        final int[] fullRows = new int[HALO_SPAN];
        int cursor, sampledCells;
        double unsupportedArea;
        boolean queued, rescan, bound = true;
        FootingMesh mesh;

        Tile(Scan scan, int tx, int tz) { this.scan = scan; this.tx = tx; this.tz = tz; x = tx * TILE; z = tz * TILE; }

        void watch(boolean add) {
            // Subscribe to the tile halo and neighbouring blocks read by collision shapes.
            ContactPlane axes = scan.axes;
            int loU = x - 2, loV = z - 2, hiU = x + TILE + 1, hiV = z + TILE + 1;
            int minX = axes.x(scan.minNormal, loU, loV) >> 4, maxX = axes.x(scan.maxNormal, hiU, hiV) >> 4;
            int minY = Math.max(0, axes.y(scan.minNormal, loU, loV)) >> 4;
            int maxY = Math.min(scan.world.getHeight() - 1, axes.y(scan.maxNormal, hiU, hiV)) >> 4;
            int minZ = axes.z(scan.minNormal, loU, loV) >> 4, maxZ = axes.z(scan.maxNormal, hiU, hiV) >> 4;
            for (int sx = minX; sx <= maxX; sx++)
                for (int sz = minZ; sz <= maxZ; sz++)
                    for (int sy = minY; sy <= maxY; sy++) {
                        if (add) scan.observer.watchChanges(sx, sy, sz, this);
                        else scan.observer.unwatch(sx, sy, sz, this);
                    }
        }

        void unload() {
            publish(null, true, 0);
            if (queued) {
                // Discard partial samples collected before the chunk left.
                resetSampling();
                rescan = false;
            } else run();
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
            resetSampling();
            queued = true;
            scan.pending.addLast(this);
        }

        private void resetSampling() {
            cursor = 0;
            sampledCells = 0;
            Arrays.fill(fullRows, 0);
            rectangles.clear();
        }

        boolean step() {
            if (cursor < HALO_SPAN * HALO_SPAN) {
                int wx = x - 1 + cursor % HALO_SPAN, wz = z - 1 + cursor / HALO_SPAN;
                cursor++;
                if (scan.candidate(wx, wz) && scan.readCell(wx, wz, this)
                        && wx >= x && wx < x + TILE && wz >= z && wz < z + TILE) sampledCells++;
                return false;
            }
            FootingMesh next = FootingMesh.build(rectangles, fullRows, x, z, TILE);
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
        if (current != null) current.chunkChanged(sx, sz, unload);
    }

    /** One pair of display lists per 32 x 32 region, shared by depth and color passes. */
    private static final class Group {
        final int tx, tz;
        final Tile[] tiles = new Tile[16];
        final List<ContactEdges.Line> borders = new ArrayList<>();
        int count, lists;
        long retryFrame;
        boolean dirty = true, stale;

        Group(int tx, int tz) { this.tx = tx; this.tz = tz; }

        void compile(Scan scan, long frame) {
            if (lists == 0) lists = GL11.glGenLists(2);
            if (lists == 0) {
                retryFrame = frame + 60;
                return;
            }
            retryFrame = 0;
            borders.clear();
            Tessellator t = Tessellator.instance;
            for (int pass = 0; pass < 2; pass++) {
                GL11.glNewList(lists + pass, GL11.GL_COMPILE);
                t.startDrawing(pass == 0 ? GL11.GL_QUADS : GL11.GL_LINES);
                for (Tile tile : tiles) if (tile != null) emit(t, tile.mesh, scan, pass == 0, borders);
                t.draw();
                GL11.glEndList();
            }
            dirty = stale = false;
        }

        void release() {
            if (lists != 0) { GL11.glDeleteLists(lists, 2); lists = 0; }
            borders.clear();
        }
    }

    private static boolean outerBorder(Scan scan, double[] line) {
        if (line[0] == line[2]) return line[0] == scan.edgeMinU || line[0] == scan.edgeMaxU;
        return line[1] == scan.edgeMinV || line[1] == scan.edgeMaxV;
    }

    private static void emit(Tessellator t, FootingMesh mesh, Scan scan, boolean faces, List<ContactEdges.Line> borders) {
        ContactPlane axes = scan.axes;
        int surface = scan.surface;
        if (faces) for (double[] r : mesh.fills) {
            axes.vertex(t, surface, r[0], r[1]); axes.vertex(t, surface, r[0], r[3]);
            axes.vertex(t, surface, r[2], r[3]); axes.vertex(t, surface, r[2], r[1]);
        } else for (double[] e : mesh.edges) {
            if (outerBorder(scan, e)) {
                if (borders != null) borders.add(ContactEdges.line(axes, surface, e));
            } else {
                axes.vertex(t, surface, e[0], e[1]); axes.vertex(t, surface, e[2], e[3]);
            }
        }
    }

    boolean writeDepth(Frustrum camera, boolean allowCompile) {
        surfaceReady = false;
        visibleBorders.clear();
        Scan current = scan;
        if (current == null || current.groups.isEmpty()) {
            if (!building) drawFill = false;
            return false;
        }
        visible.clear();
        if (visibleLists.capacity() < current.groups.size())
            visibleLists = BufferUtils.createIntBuffer(current.groups.size() * 2);
        visibleLists.clear();
        ContactPlane axes = current.axes;
        double surface = current.surface;
        long frame = ++renderedFrames;
        boolean compiled = false;
        for (Group g : current.groups.values()) {
            int u = g.tx * TILE, v = g.tz * TILE;
            if (!current.overlaps(g.tx, g.tz, 4)
                    || !axes.visible(camera, surface, u, v, u + 4 * TILE, v + 4 * TILE)) continue;
            boolean unloading = current.hasPendingUnload(u, v, 4 * TILE);
            if (allowCompile && g.dirty && !unloading && !compiled && frame >= g.retryFrame) {
                g.compile(current, frame);
                compiled = true;
            }
            if (g.lists != 0 && !g.stale && !unloading) {
                visibleLists.put(g.lists);
                if (!g.borders.isEmpty()) visibleBorders.add(g);
            }
            else if (g.lists != 0 || g.retryFrame != 0) {
                // Keep unaffected parts of a previously drawn region during unload, or recover from a GL allocation failure.
                for (Tile t : g.tiles) if (t != null && (!unloading || !current.hasPendingUnload(t.x, t.z, TILE)))
                    visible.add(t.mesh);
            }
        }
        visibleLists.flip();
        surfaceReady = visibleLists.hasRemaining() || !visible.isEmpty();
        if (!building) drawFill = surfaceReady && current.showFill();
        if (!surfaceReady || !drawFill) return compiled;
        pushPaintState();
        try {
            // Solid supports already supply their own color and depth. Back only the pixels where
            // the first world fragment is farther away (for example, an opening in a glass texture).
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(1f, 1f);
            GL11.glDepthFunc(GL11.GL_LESS);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);
            paint(0x477d70, 255);
            geometry(current, true);
        } finally { GL11.glPopAttrib(); }
        return compiled;
    }

    static void drawSurfaces(PreviewFooting[] faces, ContactEdges edges) {
        boolean ready = false;
        for (PreviewFooting face : faces) ready |= face.surfaceReady;
        if (!ready) return;
        pushPaintState();
        try {
            // The footprint is a placement aid. Keep even an interior contact visible through the finished shell.
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            paint(0x63ffd1, 100);
            for (PreviewFooting face : faces) {
                Scan current = face.scan;
                if (!face.surfaceReady || current == null) continue;
                if (face.drawFill) face.geometry(current, true);
                for (Group group : face.visibleBorders) edges.addAll(group.borders);
                for (FootingMesh mesh : face.visible) for (double[] line : mesh.edges)
                    if (outerBorder(current, line)) edges.add(current.axes, current.surface, line);
            }
            GL11.glLineWidth(8f);
            paint(0x174b46, 255);
            drawOutlines(faces, edges);
            GL11.glLineWidth(5f);
            paint(COLOR, 255);
            drawOutlines(faces, edges);
        } finally {
            for (PreviewFooting face : faces) {
                face.surfaceReady = false;
                face.visibleBorders.clear();
            }
            edges.clear();
            GL11.glPopAttrib();
        }
    }

    private static void drawOutlines(PreviewFooting[] faces, ContactEdges edges) {
        for (PreviewFooting face : faces) {
            Scan current = face.scan;
            if (face.surfaceReady && current != null) face.geometry(current, false);
        }
        edges.draw();
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

    private static void paint(int color, int alpha) {
        GL11.glColor4ub((byte)(color >> 16), (byte)(color >> 8), (byte)color, (byte)alpha);
    }

    private void geometry(Scan scan, boolean faces) {
        // Angelica consumes the IntBuffer during glCallLists. Replay the same visible groups for every pass.
        visibleLists.rewind();
        if (visibleLists.hasRemaining()) {
            GL11.glListBase(faces ? 0 : 1);
            GL11.glCallLists(visibleLists);
        }
        if (visible.isEmpty()) return;
        Tessellator t = Tessellator.instance;
        t.startDrawing(faces ? GL11.GL_QUADS : GL11.GL_LINES);
        for (FootingMesh mesh : visible) emit(t, mesh, scan, faces, null);
        t.draw();
    }

    private void retire() {
        surfaceReady = false;
        visibleBorders.clear();
        if (scan == null) return;
        scan.closed = true;
        retired.addLast(scan);
        scan = null;
        visible.clear();
    }

    private void drainRetired() {
        for (int i = 0; i < 3 && !retired.isEmpty(); i++) {
            Tile t = retired.peekFirst().lifetime.pollFirst();
            if (t != null) t.unbind();
            else retired.removeFirst();
        }
    }

    boolean clear() {
        retire();
        drainRetired();
        return retired.isEmpty();
    }

    /** The old observer drops all its subscriptions on world replacement. */
    void worldChanged() {
        retire();
        for (Scan old : retired) for (Group g : old.groups.values()) g.release();
        retired.clear();
    }
}
