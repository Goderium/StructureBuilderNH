package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.builder.PreviewPose;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;

import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.*;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.UUID;

/**
 * Transient client pose. Geometry stays in its original section display lists throughout the drag.
 */
final class PreviewGrip {
    final UUID id;
    final long sequence, pressedAt;
    final int revision, slot, dimension;
    final boolean near;
    final String tool;
    final ChunkCoordinates origin, at;
    final ExtendedFacing original;
    private final PreviewPose.Allowed allowed;
    ExtendedFacing facing;
    boolean moving, released, limited, mirrored, inputReleased;
    volatile boolean rejected;
    long releasedAt;
    private final double initialDepth, grabX, grabY, grabZ;
    private final int minDistance, maxDistance;
    private double depth;
    private boolean distanceAdjusted;
    private PreviewPose rotation;
    private ForgeDirection pitchAxis;
    ForgeDirection downwardFace;
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

    PreviewGrip(
            UUID id,
            long sequence,
            int revision,
            String tool,
            ChunkCoordinates p,
            ExtendedFacing original,
            PreviewPose.Allowed allowed,
            int minDistance,
            int maxDistance,
            boolean near,
            EntityPlayer actor,
            long now) {
        this.id = id;
        this.sequence = sequence;
        this.revision = revision;
        this.tool = tool;
        origin = new ChunkCoordinates(p);
        at = new ChunkCoordinates(p);
        this.original = original;
        this.allowed = allowed;
        this.minDistance = minDistance;
        this.maxDistance = maxDistance;
        this.near = near;
        pitchAxis = original.getRelativeLeftInWorld();
        dimension = actor.dimension;
        slot = actor.inventory.currentItem;
        pressedAt = now;
        double eyeY = eyeY(actor);
        double dx = p.posX + .5 - actor.posX,
                dy = p.posY + .5 - eyeY,
                dz = p.posZ + .5 - actor.posZ;
        initialDepth = Math.sqrt(dx * dx + dy * dy + dz * dz);
        depth = MathHelper.clamp_double(initialDepth, minDistance, maxDistance);
        Vec3 look = actor.getLook(1);
        grabX = actor.posX + look.xCoord * initialDepth;
        grabY = eyeY + look.yCoord * initialDepth;
        grabZ = actor.posZ + look.zCoord * initialDepth;
        orient(original);
    }

    private static double eyeY(EntityPlayer p) {
        return p.posY + p.getEyeHeight() - (p.worldObj.isRemote ? p.getDefaultEyeHeight() : 0);
    }

    void track(EntityPlayer p) {
        Vec3 look = p.getLook(1);
        double eye = eyeY(p);
        // Keep the original cell available for a turn in place at the edge of the distance range.
        if (!distanceAdjusted && depth != initialDepth
                && MathHelper.floor_double(.5 + p.posX + look.xCoord * initialDepth - grabX) == 0
                && MathHelper.floor_double(.5 + eye + look.yCoord * initialDepth - grabY) == 0
                && MathHelper.floor_double(.5 + p.posZ + look.zCoord * initialDepth - grabZ) == 0) {
            at.set(origin.posX, origin.posY, origin.posZ);
            return;
        }
        int x = origin.posX + MathHelper.floor_double(.5 + p.posX + look.xCoord * depth - grabX);
        int y = origin.posY + MathHelper.floor_double(.5 + eye + look.yCoord * depth - grabY);
        int z = origin.posZ + MathHelper.floor_double(.5 + p.posZ + look.zCoord * depth - grabZ);
        at.set(x, y, z);
    }

    void scrollDistance(int steps) {
        double next = MathHelper.clamp_double(depth + steps, minDistance, maxDistance);
        distanceAdjusted |= next != depth;
        depth = next;
        limited = false;
    }

    int displayDistance() {
        return (int) Math.round(depth);
    }

    void scrollRotation(boolean vertical, int steps) {
        ForgeDirection axis = vertical ? pitchAxis : ForgeDirection.UP;
        ExtendedFacing current = facing;
        for (int i = 0; i < Math.abs(steps); i++) {
            ExtendedFacing next = allowed.step(current, axis, Integer.signum(steps));
            limited = next == current;
            if (limited) break;
            current = next;
        }
        if (current != facing) {
            orient(current);
            // Preserve the pitch plane through straight up/down, even on front-only controllers.
            if (!vertical) pitchAxis = facing.getRelativeLeftInWorld();
        }
    }

    void orient(ExtendedFacing next) {
        facing = next;
        rotation = new PreviewPose(original, next);
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            if (rotation.yx * face.offsetX + rotation.yy * face.offsetY + rotation.yz * face.offsetZ == -1) {
                downwardFace = face;
                break;
            }
        }
        mirrored = reversesWinding(original.getFlip()) != reversesWinding(next.getFlip());
        matrix.clear();
        matrix.put(
                        new float[] {
                            rotation.xx,
                            rotation.yx,
                            rotation.zx,
                            0,
                            rotation.xy,
                            rotation.yy,
                            rotation.zy,
                            0,
                            rotation.xz,
                            rotation.yz,
                            rotation.zz,
                            0,
                            0,
                            0,
                            0,
                            1
                        })
                .flip();
    }

    private static boolean reversesWinding(Flip flip) {
        return flip == Flip.HORIZONTAL || flip == Flip.VERTICAL;
    }

    boolean changed() {
        return facing != original || !at.equals(origin);
    }

    void worldCell(int x, int y, int z, int[] out) {
        int dx = x - origin.posX, dy = y - origin.posY, dz = z - origin.posZ;
        out[0] = at.posX + rotation.xx * dx + rotation.xy * dy + rotation.xz * dz;
        out[1] = at.posY + rotation.yx * dx + rotation.yy * dy + rotation.yz * dz;
        out[2] = at.posZ + rotation.zx * dx + rotation.zy * dy + rotation.zz * dz;
    }

    void transform() {
        GL11.glTranslated(at.posX + .5, at.posY + .5, at.posZ + .5);
        GL11.glMultMatrix(matrix);
        GL11.glTranslated(-origin.posX - .5, -origin.posY - .5, -origin.posZ - .5);
    }

    private double x(double a, double b, double c) {
        return at.posX + .5 + rotation.xx * a + rotation.xy * b + rotation.xz * c;
    }

    private double y(double a, double b, double c) {
        return at.posY + .5 + rotation.yx * a + rotation.yy * b + rotation.yz * c;
    }

    private double z(double a, double b, double c) {
        return at.posZ + .5 + rotation.zx * a + rotation.zy * b + rotation.zz * c;
    }

    boolean visible(Frustrum camera, AxisAlignedBB box) {
        double a = box.minX - origin.posX - .5,
                b = box.minY - origin.posY - .5,
                c = box.minZ - origin.posZ - .5;
        double u = box.maxX - origin.posX - .5,
                v = box.maxY - origin.posY - .5,
                w = box.maxZ - origin.posZ - .5;
        double x1 = x(a, b, c),
                x2 = x(u, v, w),
                y1 = y(a, b, c),
                y2 = y(u, v, w),
                z1 = z(a, b, c),
                z2 = z(u, v, w);
        return camera.isBoxInFrustum(
                Math.min(x1, x2),
                Math.min(y1, y2),
                Math.min(z1, z2),
                Math.max(x1, x2),
                Math.max(y1, y2),
                Math.max(z1, z2));
    }

    double distance(double px, double py, double pz, double eyeX, double eyeY, double eyeZ) {
        double a = px - origin.posX - .5, b = py - origin.posY - .5, c = pz - origin.posZ - .5;
        double dx = x(a, b, c) - eyeX, dy = y(a, b, c) - eyeY, dz = z(a, b, c) - eyeZ;
        return dx * dx + dy * dy + dz * dz;
    }
}
