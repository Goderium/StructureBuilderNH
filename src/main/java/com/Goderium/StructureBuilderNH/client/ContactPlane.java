package com.Goderium.StructureBuilderNH.client;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.common.util.ForgeDirection;

/** Converts a world-aligned contact plane and its two mesh coordinates to XYZ. */
final class ContactPlane {
    private static final ContactPlane[] FACES = new ContactPlane[ForgeDirection.VALID_DIRECTIONS.length];

    static {
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS)
            FACES[direction.ordinal()] = new ContactPlane(direction);
    }

    final boolean positive, horizontal;
    private final boolean normalX, normalZ;

    private ContactPlane(ForgeDirection direction) {
        positive = direction.offsetX + direction.offsetY + direction.offsetZ > 0;
        horizontal = direction.offsetY != 0;
        normalX = direction.offsetX != 0;
        normalZ = direction.offsetZ != 0;
    }

    static ContactPlane of(ForgeDirection direction) { return FACES[direction.ordinal()]; }

    int normal(int x, int y, int z) { return horizontal ? y : normalX ? x : z; }
    int u(int x, int y, int z) { return normalX ? z : x; }
    int v(int x, int y, int z) { return horizontal ? z : y; }
    double normal(double x, double y, double z) { return horizontal ? y : normalX ? x : z; }
    double u(double x, double y, double z) { return normalX ? z : x; }
    double v(double x, double y, double z) { return horizontal ? z : y; }

    int surface(int plane) { return positive ? plane + 1 : plane; }

    int x(int normal, int u, int v) { return normalX ? normal : u; }
    int y(int normal, int u, int v) { return horizontal ? normal : v; }
    int z(int normal, int u, int v) { return normalZ ? normal : horizontal ? v : u; }

    void world(int normal, int u, int v, int[] out) {
        out[0] = x(normal, u, v);
        out[1] = y(normal, u, v);
        out[2] = z(normal, u, v);
    }

    private double x(double normal, double u, double v) { return normalX ? normal : u; }
    private double y(double normal, double u, double v) { return horizontal ? normal : v; }
    private double z(double normal, double u, double v) { return normalZ ? normal : horizontal ? v : u; }

    AxisAlignedBB bounds(double minNormal, double maxNormal, double minU, double minV, double maxU, double maxV) {
        return AxisAlignedBB.getBoundingBox(x(minNormal, minU, minV), y(minNormal, minU, minV), z(minNormal, minU, minV),
                x(maxNormal, maxU, maxV), y(maxNormal, maxU, maxV), z(maxNormal, maxU, maxV));
    }

    boolean visible(Frustrum camera, double surface, double minU, double minV, double maxU, double maxV) {
        double minNormal = surface - .1, maxNormal = surface + .1;
        return camera.isBoxInFrustum(x(minNormal, minU, minV), y(minNormal, minU, minV), z(minNormal, minU, minV),
                x(maxNormal, maxU, maxV), y(maxNormal, maxU, maxV), z(maxNormal, maxU, maxV));
    }

    void vertex(Tessellator tessellator, double surface, double u, double v) {
        tessellator.addVertex(x(surface, u, v), y(surface, u, v), z(surface, u, v));
    }
}
