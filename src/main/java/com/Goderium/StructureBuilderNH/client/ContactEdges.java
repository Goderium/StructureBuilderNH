package com.Goderium.StructureBuilderNH.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.renderer.Tessellator;
import org.lwjgl.opengl.GL11;

/** Merges collinear contact borders in world space before either outline pass. */
final class ContactEdges {
    private static final Comparator<Line> ORDER = Comparator.comparingInt((Line line) -> line.axis)
            .thenComparingDouble(line -> line.fixedA)
            .thenComparingDouble(line -> line.fixedB)
            .thenComparingDouble(line -> line.from);
    private final List<Line> candidates = new ArrayList<>(), union = new ArrayList<>();
    private boolean dirty;

    static final class Line {
        final int axis;
        /** Fixed coordinates are in X/Y/Z order, omitting the axis along the line. */
        final double fixedA, fixedB, from, to;

        Line(int axis, double fixedA, double fixedB, double from, double to) {
            this.axis = axis;
            this.fixedA = fixedA == 0 ? 0 : fixedA;
            this.fixedB = fixedB == 0 ? 0 : fixedB;
            this.from = from;
            this.to = to;
        }

        boolean collinear(Line other) {
            return axis == other.axis && fixedA == other.fixedA && fixedB == other.fixedB;
        }

        private void vertex(Tessellator t, double along) {
            if (axis == 0) t.addVertex(along, fixedA, fixedB);
            else if (axis == 1) t.addVertex(fixedA, along, fixedB);
            else t.addVertex(fixedA, fixedB, along);
        }
    }

    static Line line(ContactPlane axes, int surface, double[] edge) {
        int uAxis = axes.u(1, 0, 0) == 1 ? 0 : 2;
        int vAxis = axes.horizontal ? 2 : 1;
        int normalAxis = 3 - uAxis - vAxis;
        boolean alongU = edge[1] == edge[3];
        int axis = alongU ? uAxis : vAxis;
        int other = alongU ? vAxis : uAxis;
        double constant = edge[alongU ? 1 : 0];
        double first = edge[alongU ? 0 : 1], last = edge[alongU ? 2 : 3];
        return new Line(axis, normalAxis < other ? surface : constant, normalAxis < other ? constant : surface,
                Math.min(first, last), Math.max(first, last));
    }

    void add(ContactPlane axes, int surface, double[] edge) {
        candidates.add(line(axes, surface, edge));
        dirty = true;
    }

    void addAll(List<Line> lines) {
        if (!lines.isEmpty()) {
            candidates.addAll(lines);
            dirty = true;
        }
    }

    List<Line> merged() {
        if (!dirty) return union;
        dirty = false;
        union.clear();
        candidates.sort(ORDER);
        for (int i = 0; i < candidates.size();) {
            Line line = candidates.get(i++);
            double end = line.to;
            while (i < candidates.size()) {
                Line next = candidates.get(i);
                if (!line.collinear(next) || next.from > end) break;
                end = Math.max(end, next.to);
                i++;
            }
            if (end > line.from)
                union.add(end == line.to ? line : new Line(line.axis, line.fixedA, line.fixedB, line.from, end));
        }
        return union;
    }

    void draw() {
        List<Line> lines = merged();
        if (lines.isEmpty()) return;
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        for (Line line : lines) {
            line.vertex(t, line.from);
            line.vertex(t, line.to);
        }
        t.draw();
    }

    void clear() {
        candidates.clear();
        union.clear();
        dirty = false;
    }
}
