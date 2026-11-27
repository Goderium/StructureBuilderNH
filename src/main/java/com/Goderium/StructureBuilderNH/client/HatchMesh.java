package com.Goderium.StructureBuilderNH.client;

import java.util.Arrays;

/** Merged exterior faces of the visible hatch markers in a section. */
final class HatchMesh {
    /** Looks up a visible hatch's role in the packet's rank-packed array while compiling outlines. */
    static final class Roles {
        final long[] occupied, hidden;
        private final byte[] packed;
        private final short[] first;

        Roles(long[] occupied, long[] hidden, byte[] packed) {
            this.occupied = occupied;
            this.hidden = hidden;
            this.packed = packed;
            first = packed.length > 1 ? new short[occupied.length] : null;
            if (first != null) {
                int rank = 0;
                for (int word = 0; word < first.length; word++) {
                    first[word] = (short) rank;
                    rank += Long.bitCount(occupied[word]);
                }
            }
        }

        int type(int slot) {
            int word = slot >>> 6;
            long bit = 1L << (slot & 63);
            if ((occupied[word] & ~hidden[word] & bit) == 0) return 0;
            int rank = first == null ? 0 : first[word] + Long.bitCount(occupied[word] & (bit - 1));
            return 1 + (packed[rank] & 255);
        }
    }

    interface RoleAt { int role(int x, int y, int z); }

    interface Edge { void line(int axis, int x, int y, int z, int role); }

    static void outline(long[] hatches, RoleAt roles, Edge sink) {
        for (int word = 0; word < hatches.length; word++) {
            for (long bits = hatches[word]; bits != 0; bits &= bits - 1) {
                int slot = word * 64 + Long.numberOfTrailingZeros(bits);
                int x = slot & 15, y = slot >> 8, z = slot >> 4 & 15;
                for (int axis = 0; axis < 3; axis++) {
                    for (int corner = 0; corner < 4; corner++) {
                        int u = corner & 1, v = corner >> 1;
                        int axisU = (axis + 1) % 3, axisV = (axis + 2) % 3;
                        int ex = x + (axisU == 0 ? u : axisV == 0 ? v : 0);
                        int ey = y + (axisU == 1 ? u : axisV == 1 ? v : 0);
                        int ez = z + (axisU == 2 ? u : axisV == 2 ? v : 0);
                        emitEdge(roles, sink, axis, axisU, axisV, ex, ey, ez, 3 - corner);
                    }
                }
            }
        }
    }

    private static void emitEdge(RoleAt roles, Edge sink, int axis, int axisU, int axisV, int x, int y, int z,
            int owner) {
        int mask = 0, first = 0;
        boolean different = false;
        for (int i = 0; i < 4; i++) {
            int u = (i & 1) - 1, v = (i >> 1) - 1;
            int role = roles.role(x + (axisU == 0 ? u : axisV == 0 ? v : 0),
                    y + (axisU == 1 ? u : axisV == 1 ? v : 0), z + (axisU == 2 ? u : axisV == 2 ? v : 0));
            if (role == 0) continue;
            // The first hatch around an edge owns it, including across section boundaries.
            if (first == 0) {
                if (i != owner) return;
                first = role;
            }
            different |= role != first;
            mask |= 1 << i;
        }
        // Two adjacent hatches of one role share a flat surface. Only its outer contour is drawn.
        if (first == 0 || mask == 15 || !different && (mask == 3 || mask == 5 || mask == 10 || mask == 12)) return;
        sink.line(axis, x, y, z, first);
    }

    interface Boundary {
        boolean occupied(int side, int wrappedSlot);
    }

    interface Face {
        void quad(int side, int plane, int u, int v, int w, int h, int dot);
    }

    /** Merged exterior faces of the one section under the cursor, kept for moves within that section. */
    static final class Selection implements Face {
        private byte[] faces = new byte[7 * 8];
        private int size;

        @Override
        public void quad(int side, int plane, int u, int v, int width, int height, int role) {
            if (size == faces.length) faces = Arrays.copyOf(faces, size * 2);
            faces[size++] = (byte) side;
            faces[size++] = (byte) plane;
            faces[size++] = (byte) u;
            faces[size++] = (byte) v;
            faces[size++] = (byte) width;
            faces[size++] = (byte) height;
            faces[size++] = (byte) role;
        }

        void draw(int slot, Face base, Face selected) {
            int x = slot & 15, y = slot >>> 8, z = slot >>> 4 & 15;
            for (int i = 0; i < size; i += 7) {
                int side = faces[i], plane = faces[i + 1], u = faces[i + 2], v = faces[i + 3];
                int w = faces[i + 4], h = faces[i + 5], role = faces[i + 6] & 255;
                int axis = side / 2;
                int p = axis == 0 ? x : axis == 1 ? y : z;
                int col = axis == 0 ? y : axis == 1 ? z : x;
                int row = axis == 0 ? z : axis == 1 ? x : y;
                if (plane != p + (side & 1) || col < u || col >= u + w || row < v || row >= v + h) {
                    base.quad(side, plane, u, v, w, h, role);
                    continue;
                }
                // Partition the original rectangle; no internal face or coplanar overlay is introduced.
                if (row > v) base.quad(side, plane, u, v, w, row - v, role);
                if (row + 1 < v + h) base.quad(side, plane, u, row + 1, w, v + h - row - 1, role);
                if (col > u) base.quad(side, plane, u, row, col - u, 1, role);
                if (col + 1 < u + w) base.quad(side, plane, col + 1, row, u + w - col - 1, 1, role);
                selected.quad(side, plane, col, row, 1, 1, role);
            }
        }
    }

    static void emit(long[] hatches, long[] hidden, byte[] dots, Boundary neighbors, Face sink) {
        int visible = 0;
        for (int word = 0; word < hatches.length; word++)
            visible += Long.bitCount(hatches[word] & ~hidden[word]);
        if (visible == 0) return;

        // Sparse sections never allocate a section-sized face mask. One hatch needs no scratch array.
        int[] faces = visible == 1 ? null : new int[Math.min(128, visible * 6)];
        int count = 0, rankBase = 0;
        for (int word = 0; word < hatches.length; word++) {
            long occupied = hatches[word];
            for (long bits = occupied & ~hidden[word]; bits != 0; bits &= bits - 1) {
                long bit = Long.lowestOneBit(bits);
                int slot = (word << 6) + Long.numberOfTrailingZeros(bit);
                // Dots are packed over every hatch, including the confirmed ones.
                int dot = dots[rankBase + Long.bitCount(occupied & (bit - 1))] & 255;
                int x = slot & 15, y = slot >>> 8, z = slot >>> 4 & 15;
                for (int side = 0; side < 6; side++) {
                    if (occluded(hatches, hidden, neighbors, side, slot, x, y, z)) continue;
                    int axis = side >>> 1;
                    int plane = axis == 0 ? x : axis == 1 ? y : z;
                    int u = axis == 0 ? y : axis == 1 ? z : x;
                    int v = axis == 0 ? z : axis == 1 ? x : y;
                    if (faces == null) {
                        sink.quad(side, plane + (side & 1), u, v, 1, 1, dot);
                    } else {
                        if (count == faces.length) faces = Arrays.copyOf(faces, faces.length * 2);
                        faces[count++] = ((side << 4 | plane) << 16) | (dot << 8) | (v << 4) | u;
                    }
                }
            }
            rankBase += Long.bitCount(occupied);
        }
        if (count == 0) return;

        Arrays.sort(faces, 0, count);
        int[] rows = null;
        for (int start = 0; start < count;) {
            int key = faces[start] >>> 8;
            int end = start + 1;
            while (end < count && faces[end] >>> 8 == key) end++;
            int side = key >>> 12, plane = (key >>> 8 & 15) + (side & 1), dot = key & 255;
            if (end == start + 1) {
                sink.quad(side, plane, faces[start] & 15, faces[start] >>> 4 & 15, 1, 1, dot);
            } else {
                if (rows == null) rows = new int[16];
                int activeRows = 0;
                for (int i = start; i < end; i++) {
                    int u = faces[i] & 15, v = faces[i] >>> 4 & 15;
                    rows[v] |= 1 << u;
                    activeRows |= 1 << v;
                }
                while (activeRows != 0) {
                    int v = Integer.numberOfTrailingZeros(activeRows);
                    int u = Integer.numberOfTrailingZeros(rows[v]);
                    int width = Integer.numberOfTrailingZeros(~(rows[v] >>> u));
                    int mask = ((1 << width) - 1) << u;
                    int height = 1;
                    while (v + height < 16 && (rows[v + height] & mask) == mask) height++;
                    for (int row = v; row < v + height; row++) {
                        rows[row] &= ~mask;
                        if (rows[row] == 0) activeRows &= ~(1 << row);
                    }
                    sink.quad(side, plane, u, v, width, height, dot);
                }
            }
            start = end;
        }
    }

    static int exposedSides(long[] hatches, long[] hidden, Boundary neighbors, int slot) {
        int sides = 0;
        for (int side = 0; side < 6; side++) {
            if (!occluded(hatches, hidden, neighbors, side, slot, slot & 15, slot >> 8, slot >> 4 & 15))
                sides |= 1 << side;
        }
        return sides;
    }

    private static boolean occluded(long[] hatches, long[] hidden, Boundary neighbors, int side, int slot, int x, int y, int z) {
        int axis = side >>> 1;
        int plane = axis == 0 ? x : axis == 1 ? y : z;
        int step = axis == 0 ? 1 : axis == 1 ? 256 : 16;
        boolean positive = (side & 1) != 0;
        if (positive ? plane == 15 : plane == 0) {
            int wrapped = slot + (positive ? -15 : 15) * step;
            return neighbors != null && neighbors.occupied(side, wrapped);
        }
        int adjacent = slot + (positive ? step : -step);
        int word = adjacent >>> 6;
        return (hatches[word] & ~hidden[word] & (1L << (adjacent & 63))) != 0;
    }
}
