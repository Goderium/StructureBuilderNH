package com.Goderium.StructureBuilderNH.client;

/** Inclusive bounds for the cells read by one texture list, or changed by one world update. */
final class PreviewRegion {
    int minX, minY, minZ, maxX, maxY, maxZ;

    PreviewRegion() { clear(); }

    void clear() {
        minX = minY = minZ = Integer.MAX_VALUE;
        maxX = maxY = maxZ = Integer.MIN_VALUE;
    }

    boolean empty() { return minX > maxX; }

    void include(int x, int y, int z) {
        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
    }

    boolean intersects(PreviewRegion other) {
        return maxX >= other.minX && minX <= other.maxX && maxY >= other.minY && minY <= other.maxY
                && maxZ >= other.minZ && minZ <= other.maxZ;
    }
}
