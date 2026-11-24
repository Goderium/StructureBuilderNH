package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.api.PrepareStructureDismantleEvent;
import com.Goderium.StructureBuilderNH.common.WorldHooks;

import java.lang.reflect.Method;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods;

final class DismantleAnimations {
    static final String STAR_PACKAGE = "com.Nxer.TwistSpaceTechnology.common.";
    static final String STAR = STAR_PACKAGE + "machine.TST_StarThatNeverWas";

    /** Record direct air-to-solid writes by the restore callback, including rings outside the selected preview piece. */
    static final class RestoredBlocks implements WorldHooks.WriteObserver {
        private final World world;
        private final PlacementLedger restored;
        private final TileEntity receiver;
        private final Method ownsPosition;
        private int depth, meta;
        private Block block;

        RestoredBlocks(World world, PlacementLedger restored, TileEntity receiver, Method ownsPosition) {
            this.world = world;
            this.restored = restored;
            this.receiver = receiver;
            this.ownsPosition = ownsPosition;
        }

        @Override
        public void before(World w, int x, int y, int z, Block next, int metadata, int flags) {
            if (depth++ != 0) return;
            block = null;
            if (w != world || next == null || next == Blocks.air) return;
            try {
                if ((Boolean) ownsPosition.invoke(receiver, x, y, z) && w.isAirBlock(x, y, z)) {
                    block = next;
                    meta = metadata;
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot identify a stored ring block", e);
            }
        }

        @Override
        public void after(World w, int x, int y, int z, boolean success) {
            if (--depth == 0 && success && block != null && w == world && w.getBlock(x, y, z) == block
                    && !w.isAirBlock(x, y, z) && w.getBlockMetadata(x, y, z) == meta)
                restored.record(w, x, y, z);
        }
    }

    static boolean prepare(BuildTask task, WorldServer world, PlacementLedger restored, PrepareStructureDismantleEvent event) {
        if (WorldHooks.TRACE.get() != null || WorldHooks.WRITE.get() != null) return false;
        Object machine = event.controller.getMetaTileEntity();
        if (machine instanceof MTEForgeOfGods) {
            MTEForgeOfGods forge = (MTEForgeOfGods) machine;
            event.trigger.stackSize = Math.max(event.trigger.stackSize, forge.getData().getRingAmount());
            event.useSurvivalConstruct = false;
            if (!forge.getData().isRendererDisabled()) {
                forge.getData().setRendererDisabled(true);
                event.controller.markDirty();
            }
            if (forge.getData().isRenderActive()) forge.destroyRenderer();
            if (forge.getData().isRenderActive()) event.awaitRestoration();
        } else {
            for (Class<?> type = machine.getClass(); type != null; type = type.getSuperclass()) {
                if (!STAR.equals(type.getName())) continue;
                try {
                    prepareStar(task, world, restored, event, machine, type);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot prepare Star That Never Was rings", e);
                }
                break;
            }
        }
        if (!event.isWaiting()) MinecraftForge.EVENT_BUS.post(event);
        return !event.isWaiting();
    }

    // TST is optional. Resolve its public receiver and rendering API only for the live star.
    private static void prepareStar(BuildTask task, WorldServer world, PlacementLedger restored, PrepareStructureDismantleEvent event,
            Object machine, Class<?> type) throws ReflectiveOperationException {
        Class<?> structure = type.getClassLoader().loadClass(STAR_PACKAGE + "machine.starthatneverwas.StarStructure");
        int receiverY = structure.getField("RECEIVER_Y").getInt(null);
        int[] offset = new int[3];
        task.facing.getWorldOffset(new int[] {0, structure.getField("CONTROLLER_Y").getInt(null) - receiverY,
                structure.getField("CONTROLLER_Z").getInt(null)}, offset);
        int x = task.x + offset[0], y = task.y + offset[1], z = task.z + offset[2];
        if (!task.requireChunk(world, x, y, z)) {
            event.awaitRestoration();
            return;
        }
        TileEntity receiver = world.getTileEntity(x, y, z);
        if (receiver == null || !type.getClassLoader().loadClass(STAR_PACKAGE + "tile.TileStarContainment").isInstance(receiver))
            return;
        Class<?> tile = receiver.getClass();
        Method owns = tile.getMethod("ownsRingPosition", int.class, int.class, int.class);
        WorldHooks.TRACE.set(new RestoredBlocks(world, restored, receiver, owns));
        try {
            if ((Boolean) type.getMethod("isRingRenderEnabled").invoke(machine))
                type.getMethod("setRingRenderEnabled", boolean.class).invoke(machine, false);
            if ((Boolean) tile.getMethod("hasStoredRings").invoke(receiver)) {
                // The removal path also restores distant rings. Chunk generation writes are not owned ring cells.
                tile.getMethod("restoreRings", boolean.class).invoke(receiver, true);
                if ((Boolean) tile.getMethod("hasStoredRings").invoke(receiver)) event.awaitRestoration();
            }
        } finally {
            WorldHooks.TRACE.remove();
        }
    }
}
