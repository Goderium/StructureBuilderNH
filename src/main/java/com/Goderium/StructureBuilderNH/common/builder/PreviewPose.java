package com.Goderium.StructureBuilderNH.common.builder;

import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.ArrayDeque;
import java.util.EnumSet;

/** Integer transform from one StructureLib world orientation to another, about the controller. */
public final class PreviewPose {
    /** The same wrench limits drive client feedback and the authoritative release check. */
    public static final class Allowed {
        public final long low, high;

        public Allowed(long low, long high) {
            this.low = low;
            this.high = high;
        }

        private boolean contains(ExtendedFacing facing) {
            int index = facing.getIndex();
            return ((index < 64 ? low : high) & (1L << (index & 63))) != 0;
        }

        public ExtendedFacing step(ExtendedFacing from, ForgeDirection axis, int sign) {
            ExtendedFacing next = rotate(from, axis, sign);
            if (contains(next)) return next;
            // Some controllers only encode a front face. Use their supported roll for the new
            // front,
            // in the same order as StructureLib.toolSetDirection. Never introduce a mirror.
            if (next.getDirection() != from.getDirection()) {
                for (ExtendedFacing candidate :
                        ExtendedFacing.FOR_FACING.get(next.getDirection())) {
                    if (candidate.getFlip() == from.getFlip() && contains(candidate))
                        return candidate;
                }
            }
            return from;
        }

        boolean reachable(ExtendedFacing from, ExtendedFacing goal) {
            if (from == goal) return true;
            if (!contains(goal) || goal.getFlip() != from.getFlip()) return false;
            EnumSet<ExtendedFacing> seen = EnumSet.of(from);
            ArrayDeque<ExtendedFacing> queue = new ArrayDeque<>();
            queue.add(from);
            while (!queue.isEmpty()) {
                ExtendedFacing current = queue.remove();
                for (ForgeDirection axis : ForgeDirection.VALID_DIRECTIONS) {
                    ExtendedFacing next = step(current, axis, 1);
                    if (next == goal) return true;
                    if (seen.add(next)) queue.add(next);
                }
            }
            return false;
        }
    }

    public final int xx, xy, xz, yx, yy, yz, zx, zy, zz;

    public PreviewPose(ExtendedFacing from, ExtendedFacing to) {
        int[][] basis = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        int[] local = new int[3];
        for (int[] column : basis) {
            from.getOffsetABC(column, local);
            to.getWorldOffset(local, column);
        }
        xx = basis[0][0];
        xy = basis[1][0];
        xz = basis[2][0];
        yx = basis[0][1];
        yy = basis[1][1];
        yz = basis[2][1];
        zx = basis[0][2];
        zy = basis[1][2];
        zz = basis[2][2];
    }

    private static boolean supports(IMetaTileEntity controller, ExtendedFacing pose) {
        return pose != null && controller.isFacingValid(pose.getDirection())
                && (controller instanceof IAlignment ? ((IAlignment) controller).isNewExtendedFacingValid(pose)
                        : pose == ExtendedFacing.of(pose.getDirection()));
    }

    static ExtendedFacing initialFacing(IMetaTileEntity controller, ExtendedFacing preferred) {
        try {
            if (controller == null || supports(controller, preferred)) return preferred;
            for (ExtendedFacing pose : ExtendedFacing.FOR_FACING.get(preferred.getDirection()))
                if (pose.getFlip().isNotFlipped() && supports(controller, pose)) return pose;
            for (ExtendedFacing pose : ExtendedFacing.VALUES)
                if (pose.getFlip().isNotFlipped() && supports(controller, pose)) return pose;
            for (ExtendedFacing pose : ExtendedFacing.VALUES)
                if (supports(controller, pose)) return pose;
        } catch (RuntimeException | LinkageError unavailable) {
            // Some prototypes can only check their orientation once attached to a tile entity.
        }
        return preferred;
    }

    public static ExtendedFacing rotate(ExtendedFacing from, ForgeDirection axis, int steps) {
        ForgeDirection a = from.getRelativeLeftInWorld(),
                b = from.getRelativeDownInWorld(),
                c = from.getRelativeBackInWorld();
        for (int n = Math.floorMod(steps, 4); n > 0; --n) {
            a = a.getRotation(axis);
            b = b.getRotation(axis);
            c = c.getRotation(axis);
        }
        for (ExtendedFacing f : ExtendedFacing.VALUES)
            if (f.getFlip() == from.getFlip()
                    && f.getRelativeLeftInWorld() == a
                    && f.getRelativeDownInWorld() == b
                    && f.getRelativeBackInWorld() == c) return f;
        throw new IllegalStateException("Missing StructureLib orientation");
    }

    private String rotatePosition(String key) {
        String[] fields = key.split(",", -1);
        if (fields.length != 3) return key;
        try {
            int x = Integer.parseInt(fields[0]),
                    y = Integer.parseInt(fields[1]),
                    z = Integer.parseInt(fields[2]);
            return (xx * x + xy * y + xz * z)
                    + ","
                    + (yx * x + yy * y + yz * z)
                    + ","
                    + (zx * x + zy * y + zz * z);
        } catch (NumberFormatException ignored) {
            return key;
        }
    }

    private String rotateGroup(String key) {
        if (key.startsWith("cell:")) return "cell:" + rotatePosition(key.substring(5));
        int slash = key.indexOf('/');
        return slash < 0 ? key : rotatePosition(key.substring(0, slash)) + key.substring(slash);
    }

    NBTTagCompound rotateSelections(NBTTagCompound old, boolean perCell) {
        NBTTagCompound next = new NBTTagCompound();
        for (Object raw : com.gtnewhorizon.structurelib.util.MiscUtils.getTagKeys(old)) {
            String key = (String) raw;
            NBTBase value = old.getTag(key).copy();
            if (perCell && value instanceof NBTTagCompound) {
                NBTTagCompound entry = (NBTTagCompound) value;
                if (entry.hasKey("group"))
                    entry.setString("group", rotateGroup(entry.getString("group")));
            }
            next.setTag(perCell ? rotatePosition(key) : rotateGroup(key), value);
        }
        return next;
    }
}
