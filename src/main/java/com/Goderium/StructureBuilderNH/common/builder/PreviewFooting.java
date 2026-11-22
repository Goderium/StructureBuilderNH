package com.Goderium.StructureBuilderNH.common.builder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The occupied cells on each outer solid plane, in ForgeDirection order. */
final class PreviewFooting {
    static final int PAGE_ROWS = 128;
    final Blueprint plan;
    final int solidityRevision;
    private final int[] plane = new int[6];
    private final int[][] tile = new int[6][16];
    final Rows[] faces = new Rows[6];
    private int section, word;

    PreviewFooting(Blueprint plan) {
        this.plan = plan;
        solidityRevision = plan.solidityRevision;
        for (int face = 0; face < faces.length; face++) {
            plane[face] = (face & 1) == 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
            faces[face] = new Rows();
        }
    }

    static final class Rows {
        final List<int[]> pages = new ArrayList<>();
        int size;

        void add(int x, int y, int z, int mask) {
            int at = size % PAGE_ROWS * 4;
            if (at == 0) pages.add(new int[PAGE_ROWS * 4]);
            int[] page = pages.get(pages.size() - 1);
            page[at] = x;
            page[at + 1] = y;
            page[at + 2] = z;
            page[at + 3] = mask;
            size++;
        }
    }

    boolean ready() {
        return section == plan.sections.size();
    }

    int advance(int budget) {
        int consumed = 0;
        while (consumed < budget && !ready()) {
            Blueprint.Section part = plan.sections.get(section);
            int ox = part.x << 4, oy = part.y << 4, oz = part.z << 4;
            long bits = part.solid[word];
            int anchor = -1;
            if (part.x == plan.x >> 4 && part.y == plan.y >> 4 && part.z == plan.z >> 4) {
                anchor = (plan.x & 15) | ((plan.z & 15) << 4) | ((plan.y & 15) << 8);
                if (anchor >> 6 == word) bits |= 1L << (anchor & 63);
            }
            for (int slot = word << 6; bits != 0; slot += 16, bits >>>= 16) {
                int y = slot >> 8, z = slot >> 4 & 15;
                int mask = (int) bits & 0xffff;
                if (mask == 0) continue;
                if (oy + y > plane[0] && oy + y < plane[1] && oz + z > plane[2] && oz + z < plane[3]
                        && ox + Integer.numberOfTrailingZeros(mask) > plane[4]
                        && ox + 31 - Integer.numberOfLeadingZeros(mask) < plane[5]) continue;
                for (int occupied = part.fluidSlots == null ? 0 : mask; occupied != 0; occupied &= occupied - 1) {
                    int bit = Integer.numberOfTrailingZeros(occupied);
                    if (slot + bit != anchor && part.fluidSlots.get(slot + bit)) mask &= ~(1 << bit);
                }
                if (mask == 0) continue;
                include(0, oy + y, z, mask);
                include(1, oy + y, z, mask);
                include(2, oz + z, y, mask);
                include(3, oz + z, y, mask);
                include(4, ox + Integer.numberOfTrailingZeros(mask), y, 1 << z);
                include(5, ox + 31 - Integer.numberOfLeadingZeros(mask), y, 1 << z);
            }
            consumed++;
            if (++word == part.solid.length) {
                for (int face = 0; face < faces.length; face++) {
                    for (int v = 0; v < 16; v++) {
                        int mask = tile[face][v];
                        if (mask == 0) continue;
                        if (face < 2) faces[face].add(ox, plane[face], oz + v, mask);
                        else if (face < 4) faces[face].add(ox, oy + v, plane[face], mask);
                        else faces[face].add(plane[face], oy + v, oz, mask);
                        tile[face][v] = 0;
                    }
                }
                section++;
                word = 0;
            }
        }
        return consumed;
    }

    private void include(int face, int coordinate, int row, int bits) {
        if (coordinate != plane[face]) {
            if ((face & 1) == 0 ? coordinate > plane[face] : coordinate < plane[face]) return;
            plane[face] = coordinate;
            Arrays.fill(tile[face], 0);
            if (faces[face].size != 0) faces[face] = new Rows();
        }
        tile[face][row] |= bits;
    }
}
