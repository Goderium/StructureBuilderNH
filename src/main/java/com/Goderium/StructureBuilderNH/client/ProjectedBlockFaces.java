package com.Goderium.StructureBuilderNH.client;

import java.util.function.IntPredicate;

/** Sparse section traversal; axes and wrapped slots match BuilderRenderer (X, Z, Y bits). */
final class ProjectedBlockFaces {
    /** A non-cubic model is emitted once at its slot, even when all six neighboring cells are occupied. */
    static final int MODEL = 6;
    interface Boundary {
        boolean hidden(int side, int slot);
    }

    interface Face {
        void emit(int side, int slot);
    }

    static void emit(long[] solid, long[] occupied, int firstWord, int endWord,
            IntPredicate model, Boundary boundary, Face face) {
        for (int word = firstWord; word < endWord; word++) {
            long pending = solid[word] & ~occupied[word];
            while (pending != 0) {
                int slot = (word << 6) | Long.numberOfTrailingZeros(pending);
                pending &= pending - 1;
                if (model.test(slot)) {
                    face.emit(MODEL, slot);
                    continue;
                }
                for (int side = 0; side < 6; side++) {
                    if (!boundary.hidden(side, slot)) face.emit(side, slot);
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

    /** Replays the untextured fallback faces without resolving the neighborhood twice. */
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
