package com.Goderium.StructureBuilderNH.common.builder;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/** Voxel picking shared by the preview and the server. The first real surface can itself be selected. */
public final class PreviewAim {
    public static final int USE_REACH = 12;
    static final double HALF_BLOCK_DIAGONAL = Math.sqrt(3) / 2;
    static final double EPSILON = 1e-7;

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

    public static double dragReach(double maxDistance) {
        // The grab offset can span two half diagonals and snapping contributes one more.
        return Math.max(USE_REACH, maxDistance + 3 * HALF_BLOCK_DIAGONAL + EPSILON);
    }

    public static ChunkCoordinates first(EntityPlayer player, VoxelTest selectable) {
        return first(player, USE_REACH, selectable);
    }

    static Vec3 eye(EntityPlayer player) {
        // Forge 1.7 uses different player position origins on the client and the server.
        double eyeY = player.posY + player.getEyeHeight()
                - (player.worldObj.isRemote ? player.getDefaultEyeHeight() : 0);
        return Vec3.createVectorHelper(player.posX, eyeY, player.posZ);
    }

    public static ChunkCoordinates first(EntityPlayer player, double reach, VoxelTest selectable) {
        Vec3 start = eye(player);
        Vec3 dir = player.getLook(1.0f);
        if (reach > USE_REACH && !player.worldObj.isRemote) {
            reach = loadedReach(player.worldObj, start, dir, reach);
            if (reach <= 0) return null;
        }
        Vec3 end = start.addVector(dir.xCoord * reach, dir.yCoord * reach, dir.zCoord * reach);
        // The 1.7.10 ray tracer advances its first vector in place.
        Vec3 traceStart = Vec3.createVectorHelper(start.xCoord, start.yCoord, start.zCoord);
        MovingObjectPosition wall = player.worldObj.rayTraceBlocks(traceStart, end, false, true, false);
        double limit = wall == null ? reach : Math.min(reach, start.distanceTo(wall.hitVec) + EPSILON);
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

    /** Stop an extended ray before an unloaded chunk without causing it to load. */
    static double loadedReach(World world, Vec3 start, Vec3 direction, double reach) {
        double sx = start.xCoord / 16, sz = start.zCoord / 16;
        double dx = direction.xCoord / 16, dz = direction.zCoord / 16;
        int x = MathHelper.floor_double(sx), z = MathHelper.floor_double(sz);
        double nx = boundary(sx, x, dx), nz = boundary(sz, z, dz);
        double stepX = Math.abs(1 / dx), stepZ = Math.abs(1 / dz);
        double travelled = 0;
        while (world.blockExists(x << 4, 0, z << 4)) {
            travelled = Math.min(nx, nz);
            if (!(travelled <= reach)) return reach;
            if (nx == travelled) { x += dx > 0 ? 1 : -1; nx += stepX; }
            if (nz == travelled) { z += dz > 0 ? 1 : -1; nz += stepZ; }
        }
        return Math.max(0, travelled - EPSILON);
    }

    private static double boundary(double eye, int cell, double direction) {
        if (direction == 0) return Double.POSITIVE_INFINITY;
        return ((direction > 0 ? cell + 1 : cell) - eye) / direction;
    }
}
