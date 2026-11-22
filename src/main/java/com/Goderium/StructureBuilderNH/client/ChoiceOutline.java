package com.Goderium.StructureBuilderNH.client;

/** Cache-time outlines of contiguous voxel regions with a shared type. */
final class ChoiceOutline {
    interface Voxels {
        /**
         * Zero means unmarked. Equal positive values form a marker region.
         * IDs must compare equally across sections. Coordinates are local and range from -1 through 16;
         * a query can cross two section faces at once, so looking up only six face neighbours is insufficient.
         */
        long type(int x, int y, int z);
    }

    interface Segment {
        /** Axis 0/1/2 is X/Y/Z; the local starting point and length are integers, with endpoints in [0,16]. */
        void line(int axis, int x, int y, int z, int length);
    }

    interface Band {
        void strip(int axis, int x, int y, int z, int length, int direction);
    }

    /** Keep the stroke on the exposed voxel faces that meet along each merged edge. */
    static void emitBands(long[] occupied, long[] hidden, Voxels voxels, Band sink) {
        emit(occupied, hidden, voxels, null, (axis, x, y, z, length, face) ->
                sink.strip(axis, x, y, z, length, face & 3));
    }

    /** Two low bits identify the transverse direction, bit 2 selects the positive outward normal. */
    static void emitOrientedBands(long[] occupied, long[] hidden, Voxels voxels, Band sink) {
        emit(occupied, hidden, voxels, null, sink);
    }

    private static final int LINES = 17 * 17;

    private ChoiceOutline() {}

    /**
     * Emits each geometric edge once, merging consecutive collinear units within this section.
     * Occupancy and hidden bits use slot = x | z << 4 | y << 8.
     * Empty sections do not allocate scratch storage or call the sampler. Nonempty sections use 3.4 KiB
     * of temporary primitive masks, with no per-voxel/edge objects. Call only when rebuilding a cache.
     * Changes at a boundary must invalidate sections sharing that face or edge (up to 18 neighbours).
     */
    static void emit(long[] occupied, long[] hidden, Voxels voxels, Segment sink) {
        emit(occupied, hidden, voxels, sink, null);
    }

    private static void emit(long[] occupied, long[] hidden, Voxels voxels, Segment edges, Band bands) {
        int words = occupied.length;
        boolean any = false;
        for (int word = 0; word < words; word++)
            if ((occupied[word] & ~hidden[word]) != 0) { any = true; break; }
        if (!any) return;

        int[] lines = new int[3 * LINES];
        for (int word = 0; word < words; word++)
            for (long bits = occupied[word] & ~hidden[word]; bits != 0; bits &= bits - 1) {
                int slot = word * 64 + Long.numberOfTrailingZeros(bits);
                int x = slot & 15, y = slot >>> 8, z = slot >>> 4 & 15;
                candidates(lines, 0, x, y, z);
                candidates(lines, 1, y, z, x);
                candidates(lines, 2, z, x, y);
            }

        int[] runs = new int[bands == null ? 1 : 8];
        for (int axis = 0; axis < 3; axis++)
            for (int v = 0; v <= 16; v++)
                for (int u = 0; u <= 16; u++) {
                    for (int bits = lines[axis * LINES + v * 17 + u]; bits != 0; bits &= bits - 1) {
                        int p = Integer.numberOfTrailingZeros(bits);
                        int sides = exposedBands(voxels, axis, p, u, v);
                        if (bands == null) {
                            if (sides != 0) runs[0] |= 1 << p;
                        } else for (; sides != 0; sides &= sides - 1)
                            runs[Integer.numberOfTrailingZeros(sides)] |= 1 << p;
                    }
                    for (int dir = 0; dir < runs.length; dir++) {
                        int visible = runs[dir];
                        runs[dir] = 0;
                        while (visible != 0) {
                            int p = Integer.numberOfTrailingZeros(visible);
                            int length = Integer.numberOfTrailingZeros(~(visible >>> p));
                            int x = axis == 0 ? p : axis == 1 ? v : u;
                            int y = axis == 0 ? u : axis == 1 ? p : v;
                            int z = axis == 0 ? v : axis == 1 ? u : p;
                            if (bands == null) edges.line(axis, x, y, z, length);
                            else bands.strip(axis, x, y, z, length, dir);
                            visible &= ~(((1 << length) - 1) << p);
                        }
                    }
                }
    }

    private static void candidates(int[] lines, int axis, int p, int u, int v) {
        int row = axis * LINES + v * 17 + u, bit = 1 << p;
        lines[row] |= bit;
        lines[row + 1] |= bit;
        lines[row + 17] |= bit;
        lines[row + 18] |= bit;
    }

    private static int exposedBands(Voxels voxels, int axis, int p, int u, int v) {
        long a = type(voxels, axis, p, u - 1, v - 1);
        long b = type(voxels, axis, p, u, v - 1);
        long c = type(voxels, axis, p, u - 1, v);
        long d = type(voxels, axis, p, u, v);
        int mask = (a == 0 ? 0 : 1) | (b == 0 ? 0 : 2) | (c == 0 ? 0 : 4) | (d == 0 ? 0 : 8);
        if (mask == 0 || mask == 15) return 0; // No exposed surface around an interior edge.

        // Two equal adjacent quadrants form a single flat surface, so there is no cell grid line.
        // Different types on that surface keep one seam. Convex, concave and diagonal turns remain.
        if (mask == 3 && a == b || mask == 5 && a == c
                || mask == 10 && b == d || mask == 12 && c == d) return 0;

        // A unit edge belongs to the first occupied quadrant in this translation-invariant order.
        // This eliminates duplicate shared lines even at diagonally neighbouring section corners.
        int owner = a != 0 ? 0 : b != 0 ? 1 : c != 0 ? 2 : 3;
        int ownerU = u - 1 + (owner & 1);
        int ownerV = v - 1 + (owner >>> 1);
        if (ownerU < 0 || ownerU >= 16 || ownerV < 0 || ownerV >= 16) return 0;
        // Each bit points across the edge into one incident exposed face: -u,+u,-v,+v.
        return face(a, c, 0) | face(b, d, 1) | face(a, b, 2) | face(c, d, 3);
    }

    private static int face(long low, long high, int dir) {
        if ((low == 0) == (high == 0)) return 0;
        return 1 << (dir + (low == 0 ? 0 : 4));
    }

    private static long type(Voxels voxels, int axis, int p, int u, int v) {
        return axis == 0 ? voxels.type(p, u, v)
                : axis == 1 ? voxels.type(v, p, u) : voxels.type(u, v, p);
    }
}
