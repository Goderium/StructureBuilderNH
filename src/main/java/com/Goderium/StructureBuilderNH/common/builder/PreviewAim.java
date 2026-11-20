package com.Goderium.StructureBuilderNH.common.builder;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Voxel picking shared by the preview and the server. The first real surface can itself be selected. */
public final class PreviewAim {
    private static final double REACH = 12.0;

    public interface VoxelTest {
        boolean test(int x, int y, int z);
    }

    private PreviewAim() {}

    /** Break same-voxel ties identically on both sides, after the ray has determined distance. */
    public static boolean prefer(boolean owned, String id, boolean previousOwned, String previousId, String selected) {
        if (owned != previousOwned) return owned;
        if (owned && !id.equals(previousId) && (id.equals(selected) || previousId.equals(selected))) return id.equals(selected);
        return id.compareTo(previousId) < 0;
    }

    public static ChunkCoordinates first(EntityPlayer player, VoxelTest selectable) {
        // Forge 1.7 uses different player position origins on the client and the server.
        double eyeY = player.posY + player.getEyeHeight()
                - (player.worldObj.isRemote ? player.getDefaultEyeHeight() : 0);
        Vec3 start = Vec3.createVectorHelper(player.posX, eyeY, player.posZ);
        Vec3 dir = player.getLook(1.0f);
        Vec3 end = start.addVector(dir.xCoord * REACH, dir.yCoord * REACH, dir.zCoord * REACH);
        // The 1.7.10 ray tracer advances its first vector in place.
        Vec3 traceStart = Vec3.createVectorHelper(start.xCoord, start.yCoord, start.zCoord);
        MovingObjectPosition wall = player.worldObj.rayTraceBlocks(traceStart, end, false, true, false);
        double limit = wall == null ? REACH : start.distanceTo(wall.hitVec) + 1e-7;
        int x = MathHelper.floor_double(start.xCoord);
        int y = MathHelper.floor_double(start.yCoord);
        int z = MathHelper.floor_double(start.zCoord);
        double nx = boundary(start.xCoord, x, dir.xCoord);
        double ny = boundary(start.yCoord, y, dir.yCoord);
        double nz = boundary(start.zCoord, z, dir.zCoord);
        double dx = Math.abs(1 / dir.xCoord), dy = Math.abs(1 / dir.yCoord), dz = Math.abs(1 / dir.zCoord);
        while (true) {
            if (selectable.test(x, y, z)) return new ChunkCoordinates(x, y, z);
            if (wall != null && x == wall.blockX && y == wall.blockY && z == wall.blockZ) return null;
            double next = Math.min(nx, Math.min(ny, nz));
            if (!(next <= limit)) return null;
            if (nx == next) { x += dir.xCoord > 0 ? 1 : -1; nx += dx; }
            if (ny == next) { y += dir.yCoord > 0 ? 1 : -1; ny += dy; }
            if (nz == next) { z += dir.zCoord > 0 ? 1 : -1; nz += dz; }
        }
    }

    private static double boundary(double eye, int cell, double direction) {
        if (direction == 0) return Double.POSITIVE_INFINITY;
        return ((direction > 0 ? cell + 1 : cell) - eye) / direction;
    }
}
