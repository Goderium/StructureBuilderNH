package com.Goderium.StructureBuilderNH.client;

import java.util.function.IntPredicate;

/** Sparse section traversal; axes and wrapped slots match BuilderRenderer (X, Z, Y bits). */
final class ProjectedBlockFaces {
    /** A non-cubic model is emitted once at its slot, even when all six neighboring cells are occupied. */
    static final int MODEL = 6;
    interface Boundary {
        boolean hidden(int side, int slot, int neighbor, boolean crossing);
    }

    interface Face {
        void emit(int side, int slot);
    }

    static void emit(long[] solid, long[] optional, long[] confirmed, long[] hatch, int host,
            boolean roles, Boundary boundary, Face face) {
        emit(solid, optional, confirmed, hatch, host, roles, slot -> false, boundary, face);
    }

    static void emit(long[] solid, long[] optional, long[] confirmed, long[] hatch, int host,
            boolean roles, IntPredicate model, Boundary boundary, Face face) {
        for (int word = 0; word < 64; word++) {
            long pending = (solid[word] | optional[word]) & ~confirmed[word];
            if (roles) pending &= ~hatch[word];
            while (pending != 0) {
                int slot = (word << 6) | Long.numberOfTrailingZeros(pending);
                pending &= pending - 1;
                if (roles && slot == host) continue;
                if (model.test(slot)) {
                    face.emit(MODEL, slot);
                    continue;
                }
                for (int side = 0; side < 6; side++) {
                    int shift = side < 2 ? 0 : side < 4 ? 8 : 4;
                    int coordinate = slot >> shift & 15;
                    int next = coordinate + ((side & 1) == 0 ? -1 : 1);
                    int neighbor = (slot & ~(15 << shift)) | ((next & 15) << shift);
                    if (!boundary.hidden(side, slot, neighbor, next < 0 || next > 15)) face.emit(side, slot);
                }
            }
        }
    }

    static long[][] remember(long[][] faces, int side, int slot) {
        if (faces == null) faces = new long[6][];
        if (faces[side] == null) faces[side] = new long[64];
        faces[side][slot >> 6] |= 1L << (slot & 63);
        return faces;
    }

    /** Replays only already-proved faces; deferred glass/fallback passes do not repeat native neighbor checks. */
    static void emit(long[][] faces, Face sink) {
        if (faces == null) return;
        for (int side = 0; side < 6; side++) {
            if (faces[side] == null) continue;
            for (int word = 0; word < 64; word++) {
                for (long bits = faces[side][word]; bits != 0; bits &= bits - 1)
                    sink.emit(side, (word << 6) | Long.numberOfTrailingZeros(bits));
            }
        }
    }

    private ProjectedBlockFaces() {}
}
