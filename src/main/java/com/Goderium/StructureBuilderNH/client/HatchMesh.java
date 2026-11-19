package com.Goderium.StructureBuilderNH.client;

import java.util.Arrays;

/** Section-local hatch faces. Confirmed hatches occlude neighbours but emit no faces themselves. */
final class HatchMesh {
    interface Boundary {
        boolean occupied(int side, int wrappedSlot);
    }

    interface Face {
        void quad(int side, int plane, int u, int v, int w, int h, int dot);
    }

    static void emit(long[] hatches, long[] confirmed, byte[] dots, Boundary neighbors, Face sink) {
        int visible = 0;
        for (int word = 0; word < hatches.length; word++)
            visible += Long.bitCount(hatches[word] & ~confirmed[word]);
        if (visible == 0) return;

        // Sparse sections never allocate a section-sized face mask. One hatch needs no scratch array.
        int[] faces = visible == 1 ? null : new int[Math.min(128, visible * 6)];
        int count = 0, rankBase = 0;
        for (int word = 0; word < hatches.length; word++) {
            long occupied = hatches[word];
            for (long bits = occupied & ~confirmed[word]; bits != 0; bits &= bits - 1) {
                long bit = Long.lowestOneBit(bits);
                int slot = (word << 6) + Long.numberOfTrailingZeros(bit);
                // Dots are packed over every hatch, including the confirmed ones.
                int dot = dots[rankBase + Long.bitCount(occupied & (bit - 1))] & 255;
                int x = slot & 15, y = slot >>> 8, z = slot >>> 4 & 15;
                for (int side = 0; side < 6; side++) {
                    if (occluded(hatches, neighbors, side, slot, x, y, z)) continue;
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

    private static boolean occluded(long[] hatches, Boundary neighbors, int side, int slot, int x, int y, int z) {
        int axis = side >>> 1;
        int plane = axis == 0 ? x : axis == 1 ? y : z;
        int step = axis == 0 ? 1 : axis == 1 ? 256 : 16;
        boolean positive = (side & 1) != 0;
        if (positive ? plane == 15 : plane == 0) {
            int wrapped = slot + (positive ? -15 : 15) * step;
            return neighbors != null && neighbors.occupied(side, wrapped);
        }
        int adjacent = slot + (positive ? step : -step);
        return (hatches[adjacent >>> 6] & (1L << (adjacent & 63))) != 0;
    }
}
