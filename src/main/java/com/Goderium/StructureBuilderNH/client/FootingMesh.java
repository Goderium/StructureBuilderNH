package com.Goderium.StructureBuilderNH.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exact 2-D union of collision tops, clipped to one tile. Input includes its one-cell halo. */
final class FootingMesh {
    final List<double[]> fills = new ArrayList<>(), edges = new ArrayList<>();

    double area;

    /** Coalesce full block tops before feeding the same contour sweep as partial collision boxes. */
    static FootingMesh build(List<double[]> rects, int[] fullRows, int x0, int z0, int size) {
        // The first row and bit correspond to the halo at one block before the tile origin.
        for (int row = 0; row < fullRows.length; row++) {
            int z = z0 + row - 1;
            for (int mask = fullRows[row]; mask != 0;) {
                int left = Integer.numberOfTrailingZeros(mask);
                int right = left + Integer.numberOfTrailingZeros(~(mask >>> left));
                rects.add(new double[] { x0 + left - 1, z, x0 + right - 1, z + 1 });
                mask &= -1 << right;
            }
        }
        return build(rects, x0, z0, size);
    }

    static FootingMesh build(List<double[]> rects, int x0, int z0, int size) {
        FootingMesh mesh = new FootingMesh();
        if (rects.isEmpty()) return mesh;
        int x1 = x0 + size, z1 = z0 + size;
        rects.sort(Comparator.comparingDouble(r -> r[0]));
        double[] cuts = new double[rects.size() * 2];
        int n = 0;
        for (double[] r : rects) {
            cuts[n++] = r[1];
            cuts[n++] = r[3];
        }
        Arrays.sort(cuts);
        double[] previous = new double[n], current = new double[n];
        int prev = 0;
        Map<Double, double[]> verticals = new HashMap<>(), fills = new HashMap<>();
        for (int i = 0; i < n;) {
            double z = cuts[i++];
            while (i < n && cuts[i] == z) i++;
            double nextZ = i < n ? cuts[i] : z;
            int used = 0;
            for (double[] r : rects) {
                if (r[1] > z || r[3] <= z) continue;
                if (used == 0 || r[0] > current[used - 1]) {
                    current[used++] = r[0];
                    current[used++] = r[2];
                } else current[used - 1] = Math.max(current[used - 1], r[2]);
            }
            // An edge exists where exactly one side of the cut is covered.
            if (z >= z0 && z <= z1) {
                int a = 0, b = 0;
                while (a < prev || b < used) {
                    double x = next(previous, a, prev, current, b, used);
                    if (a < prev && previous[a] == x) a++;
                    if (b < used && current[b] == x) b++;
                    if (((a ^ b) & 1) == 0 || z == z0 && (b & 1) == 0 || z == z1 && (a & 1) == 0) continue;
                    double left = Math.max(x0, x), right = Math.min(x1, next(previous, a, prev, current, b, used));
                    if (left < right) mesh.edges.add(new double[]{left, z, right, z});
                }
            }
            double near = Math.max(z0, z), far = Math.min(z1, nextZ);
            if (near < far) for (int j = 0; j < used; j += 2) {
                double lo = current[j], hi = current[j + 1];
                double left = Math.max(x0, lo), right = Math.min(x1, hi);
                if (left >= right) continue;
                mesh.area += (right - left) * (far - near);
                double[] fill = fills.get(left);
                if (fill != null && fill[2] == right && fill[3] == near) fill[3] = far;
                else {
                    fill = new double[]{left, near, right, far};
                    fills.put(left, fill);
                    mesh.fills.add(fill);
                }
                if (lo >= x0) mesh.vertical(verticals, lo, near, far);
                if (hi <= x1) mesh.vertical(verticals, hi, near, far);
            }
            double[] temp = previous; previous = current; current = temp;
            prev = used;
        }
        return mesh;
    }

    private static double next(double[] a, int i, int n, double[] b, int j, int m) {
        return Math.min(i < n ? a[i] : Double.POSITIVE_INFINITY, j < m ? b[j] : Double.POSITIVE_INFINITY);
    }

    private void vertical(Map<Double, double[]> tails, double x, double z, double end) {
        double[] last = tails.get(x);
        if (last != null && last[3] == z) last[3] = end;
        else {
            double[] edge = {x, z, x, end};
            edges.add(edge);
            tails.put(x, edge);
        }
    }
}
