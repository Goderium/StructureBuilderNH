package com.Goderium.StructureBuilderNH.common;

import com.Goderium.StructureBuilderNH.StructureBuilderNH;
import com.Goderium.StructureBuilderNH.config.RebuildConfig;
import com.Goderium.StructureBuilderNH.util.Bounds;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

public final class ShapeRegistry {
    public static final class Shape {
        public final String piece, symbols;
        private final int[] size;
        private final Map<Character, String> roles = new ConcurrentHashMap<>();

        Shape(String piece, String symbols, int[] size) {
            this.piece = piece;
            this.symbols = symbols;
            this.size = size.clone();
        }

        public char symbol(int cursor) {
            return symbols != null && cursor >= 0 && cursor < symbols.length() ? symbols.charAt(cursor) : '\0';
        }

        public String role(char symbol) {
            return roles.computeIfAbsent(symbol, value -> "piece:" + piece.length() + ":" + piece + ":" + (int) value);
        }
    }

    private static final Map<IStructureElement<?>[], Shape> SHAPES =
        Collections.synchronizedMap(new WeakHashMap<IStructureElement<?>[], Shape>());
    public static void put(IStructureElement<?>[] elements, int[] size) {
        put(elements, size, "", null);
    }
    public static void put(IStructureElement<?>[] elements, int[] size, String piece, String symbols) {
        SHAPES.put(elements, new Shape(piece, symbols != null && symbols.length() == elements.length ? symbols : null, size));
    }
    public static Shape get(IStructureElement<?>[] elements) { return SHAPES.get(elements); }
    public static Bounds bounds(IStructureElement<?>[] elements, ExtendedFacing facing,
        int x, int y, int z, int a, int b, int c) {
        Shape shape = SHAPES.get(elements);
        int[] size = shape == null ? null : shape.size;
        if (size == null || size[0] < 1 || size[1] < 1 || size[2] < 1) {
            throw new IllegalStateException("No complete registered shape dimensions; rebuild cancelled");
        }
        Bounds result = null;
        for (int corner = 0; corner < 8; corner++) {
            int[] abc = { ((corner & 1) == 0 ? 0 : size[0] - 1) - a,
                ((corner & 2) == 0 ? 0 : size[1] - 1) - b, ((corner & 4) == 0 ? 0 : size[2] - 1) - c };
            int[] xyz = new int[3];
            facing.getWorldOffset(abc, xyz);
            Bounds point = Bounds.point(Math.addExact(x, xyz[0]), Math.addExact(y, xyz[1]), Math.addExact(z, xyz[2]));
            result = result == null ? point : result.union(point);
        }
        return result;
    }
}
