package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.api.AnimatedStructureBlocks;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods;
import tectech.thing.metaTileEntity.multi.godforge.structure.ForgeOfGodsRingsStructureString;
import tectech.thing.metaTileEntity.multi.godforge.structure.ForgeOfGodsStructureString;

import java.lang.reflect.Method;

/** Reads animation ownership without restoring blocks or changing machine state. */
final class AnimatedOccupancy {
    private final int[] offset = new int[3], local = new int[3];
    private Class<?> controllerClass;
    private boolean supported;
    private Class<?> receiverClass;
    private Method ownsRingPosition;
    private int receiverB, receiverC;

    boolean supports(IMetaTileEntity prototype) {
        if (prototype == null) return false;
        if (controllerClass == prototype.getClass()) return supported;
        supported = prototype instanceof AnimatedStructureBlocks || prototype instanceof MTEForgeOfGods;
        receiverClass = null;
        if (!supported) {
            for (Class<?> type = prototype.getClass(); type != null; type = type.getSuperclass()) {
                if (!DismantleAnimations.STAR.equals(type.getName())) continue;
                try {
                    ClassLoader loader = type.getClassLoader();
                    Class<?> structure = loader.loadClass(DismantleAnimations.STAR_PACKAGE + "machine.starthatneverwas.StarStructure");
                    receiverB = structure.getField("CONTROLLER_Y").getInt(null) - structure.getField("RECEIVER_Y").getInt(null);
                    receiverC = structure.getField("CONTROLLER_Z").getInt(null);
                    receiverClass = loader.loadClass(DismantleAnimations.STAR_PACKAGE + "tile.TileStarContainment");
                    ownsRingPosition = receiverClass.getMethod("ownsRingPosition", int.class, int.class, int.class);
                    supported = true;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot read star ring ownership", e);
                }
                break;
            }
        }
        controllerClass = prototype.getClass();
        return supported;
    }

    // A null result leaves the cell to the normal physical-block check.
    BuildTask.OrdinaryStatus status(IMetaTileEntity machine, BuildTask task, World world, int x, int y, int z) {
        if (!controllerClass.isInstance(machine)) return null;
        if (machine instanceof AnimatedStructureBlocks)
            return ((AnimatedStructureBlocks) machine).ownsAnimatedBlock(x, y, z) ? BuildTask.OrdinaryStatus.INSTALLED : null;
        if (machine instanceof MTEForgeOfGods) {
            MTEForgeOfGods forge = (MTEForgeOfGods) machine;
            if (!forge.getData().isRenderActive()) return null;
            offset[0] = x - task.x; offset[1] = y - task.y; offset[2] = z - task.z;
            task.facing.getOffsetABC(offset, local);
            return forgeRing(forge.getData().getRingAmount(), local[0], local[1], local[2]) ? BuildTask.OrdinaryStatus.INSTALLED : null;
        }
        // The receiver persists the actual captured cells, including partially captured/restored rings.
        local[0] = 0; local[1] = receiverB; local[2] = receiverC;
        task.facing.getWorldOffset(local, offset);
        int rx = task.x + offset[0], ry = task.y + offset[1], rz = task.z + offset[2];
        if (!world.blockExists(rx, ry, rz)) return BuildTask.OrdinaryStatus.UNLOADED;
        TileEntity receiver = world.getTileEntity(rx, ry, rz);
        if (!receiverClass.isInstance(receiver) || receiver.isInvalid()) return null;
        try {
            return (boolean) ownsRingPosition.invoke(receiver, x, y, z) ? BuildTask.OrdinaryStatus.INSTALLED : null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read stored ring position", e);
        }
    }

    static boolean forgeRing(int rings, int a, int b, int c) {
        return marked(ForgeOfGodsStructureString.FIRST_RING_AIR, a + 63, b + 14, c - 59)
                || rings >= 2 && marked(ForgeOfGodsRingsStructureString.SECOND_RING_AIR, a + 55, b + 11, c - 67)
                || rings >= 3 && marked(ForgeOfGodsRingsStructureString.THIRD_RING_AIR, a + 47, b + 13, c - 76);
    }

    private static boolean marked(String[][] shape, int a, int b, int c) {
        return c >= 0 && c < shape.length && b >= 0 && b < shape[c].length
                && a >= 0 && a < shape[c][b].length() && shape[c][b].charAt(a) == 'L';
    }
}
