package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.common.WorldHooks;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.BlockEvent;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * A synchronous, single-cell replacement. Escrow and old-item credit are committed by BuildTask
 * afterwards.
 */
final class UpgradePlacement {
    enum Outcome {
        SUCCESS,
        DENIED,
        FAILED,
        RECOVERY
    }

    private static final Map<Class<?>, Boolean> PLACERS = new HashMap<>();

    static final class Recovery {
        final int x, y, z, oldMeta, targetMeta;
        final Block oldBlock, targetBlock;
        final boolean owned;

        Recovery(
                int x,
                int y,
                int z,
                Block oldBlock,
                int oldMeta,
                Block targetBlock,
                int targetMeta,
                boolean owned) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.oldBlock = oldBlock;
            this.oldMeta = oldMeta;
            this.targetBlock = targetBlock;
            this.targetMeta = targetMeta;
            this.owned = owned;
        }

        boolean oldMatches(World world) {
            return matches(world, oldBlock, oldMeta);
        }

        boolean targetMatches(World world) {
            return matches(world, targetBlock, targetMeta);
        }

        private boolean matches(World world, Block block, int meta) {
            return world.getBlock(x, y, z) == block
                    && world.getBlockMetadata(x, y, z) == meta
                    && world.getTileEntity(x, y, z) == null;
        }

        NBTTagCompound save() {
            NBTTagCompound n = new NBTTagCompound();
            n.setIntArray("position", new int[] {x, y, z});
            n.setString("oldBlock", Block.blockRegistry.getNameForObject(oldBlock));
            n.setString("targetBlock", Block.blockRegistry.getNameForObject(targetBlock));
            n.setInteger("oldMeta", oldMeta);
            n.setInteger("targetMeta", targetMeta);
            n.setBoolean("owned", owned);
            return n;
        }

        static Recovery load(NBTTagCompound n) {
            int[] p = n.getIntArray("position");
            Block old = (Block) Block.blockRegistry.getObject(n.getString("oldBlock"));
            Block target = (Block) Block.blockRegistry.getObject(n.getString("targetBlock"));
            int om = n.getInteger("oldMeta"), tm = n.getInteger("targetMeta");
            if (p.length != 3
                    || old == null
                    || target == null
                    || old == Blocks.air
                    || target == Blocks.air
                    || om < 0
                    || om > 15
                    || tm < 0
                    || tm > 15
                    || old.hasTileEntity(om)
                    || target.hasTileEntity(tm)) return null;
            return new Recovery(p[0], p[1], p[2], old, om, target, tm, n.getBoolean("owned"));
        }
    }

    private static final class Scope implements WorldHooks.WriteGuard {
        final World world;
        final Recovery cell;
        int phase; // 0=protection callbacks, 1=placement, 2=rollback

        Scope(World world, Recovery cell) {
            this.world = world;
            this.cell = cell;
        }

        public Boolean setBlock(
                World source, int x, int y, int z, Block block, int meta, int flags) {
            if (source != world || x != cell.x || y != cell.y || z != cell.z) return false;
            if (phase == 1 && block == cell.targetBlock && meta == cell.targetMeta) return null;
            if (phase == 2 && block == cell.oldBlock && meta == cell.oldMeta) return null;
            return false;
        }

        // Native, no-tile ItemBlock placement supplies its metadata in setBlock. Never allow
        // callbacks
        // to add/remove a tile or rotate/change another cell's metadata during this transaction.
        public boolean protect(World source, int x, int y, int z) {
            return true;
        }
    }

    static boolean supports(ChannelUpgrade.Result result) {
        return result.kind == ChannelUpgrade.Kind.REPLACE
                && result.oldBlock != null
                && result.targetBlock != null
                && result.oldBlock != Blocks.air
                && result.targetBlock != Blocks.air
                && !result.oldBlock.hasTileEntity(result.oldMeta)
                && !result.targetBlock.hasTileEntity(result.targetMeta)
                && result.previous != null
                && !result.previous.hasTagCompound()
                && result.target != null
                && !result.target.hasTagCompound()
                && simplePlacer(result.target.getItem());
    }

    static Recovery prepare(WorldServer world, int x, int y, int z, ItemStack material, boolean owned) {
        if (material == null || material.hasTagCompound() || !simplePlacer(material.getItem())) return null;
        Block old = world.getBlock(x, y, z), next = Block.getBlockFromItem(material.getItem());
        int oldMeta = world.getBlockMetadata(x, y, z), nextMeta = material.getItem().getMetadata(material.getMetadata());
        if (old == Blocks.air || next == null || next == Blocks.air || nextMeta < 0 || nextMeta > 15
                || old.hasTileEntity(oldMeta) || next.hasTileEntity(nextMeta) || world.getTileEntity(x, y, z) != null)
            return null;
        return new Recovery(x, y, z, old, oldMeta, next, nextMeta, owned);
    }

    private static boolean simplePlacer(Item item) {
        if (!(item instanceof ItemBlock)) return false;
        return PLACERS.computeIfAbsent(
                item.getClass(),
                type -> {
                    // Compare signatures rather than development/SRG names. Custom use callbacks
                    // could consume
                    // unrelated inventory or edit external state, which a block rollback cannot
                    // restore.
                    for (Method method : type.getMethods()) {
                        Class<?>[] p = method.getParameterTypes();
                        if (method.getReturnType() != boolean.class
                                || p.length != 10 && p.length != 11
                                || p[0] != ItemStack.class
                                || p[1] != EntityPlayer.class
                                || p[2] != World.class) continue;
                        if (method.getDeclaringClass() != Item.class
                                && method.getDeclaringClass() != ItemBlock.class) return false;
                    }
                    return true;
                });
    }

    static Outcome replace(
            Recovery cell, EntityPlayerMP actor, WorldServer world, ItemStack material) {
        if (WorldHooks.WRITE.get() != null || world.captureBlockSnapshots || world.restoringBlockSnapshots
                || !world.capturedBlockSnapshots.isEmpty()
                || !cell.oldMatches(world)
                || cell.oldBlock.getBlockHardness(world, cell.x, cell.y, cell.z) < 0
                || !world.canMineBlock(actor, cell.x, cell.y, cell.z)
                || !actor.canPlayerEdit(cell.x, cell.y, cell.z, 1, material)) return Outcome.DENIED;
        Scope scope = new Scope(world, cell);
        WorldHooks.ItemDropSink previousDrops = WorldHooks.DISMANTLE_DROPS.get();
        WorldHooks.WRITE.set(scope);
        // Reclaim exactly the proven old component once after success, never callback-generated
        // drops.
        // This also discards creative output and failed-placement byproducts without spawning
        // entities.
        WorldHooks.DISMANTLE_DROPS.set(
                (source, stack) ->
                        source == world
                                || previousDrops != null && previousDrops.accept(source, stack));
        try {
            BlockEvent.BreakEvent event =
                    new BlockEvent.BreakEvent(
                            cell.x, cell.y, cell.z, world, cell.oldBlock, cell.oldMeta, actor);
            if (MinecraftForge.EVENT_BUS.post(event) || !cell.oldMatches(world))
                return Outcome.DENIED;
            ItemStack unit = material.copy();
            unit.stackSize = 1;
            BlockSnapshot before = BlockSnapshot.getBlockSnapshot(world, cell.x, cell.y, cell.z, 3);
            scope.phase = 1;
            boolean placed;
            // Use ItemBlock's native write at this exact coordinate. Delay physics until it is committed.
            world.captureBlockSnapshots = true;
            try {
                placed = ((ItemBlock) unit.getItem()).placeBlockAt(unit, actor, world, cell.x, cell.y, cell.z,
                        ForgeDirection.UP.ordinal(), .5f, .5f, .5f, cell.targetMeta);
            } finally {
                world.captureBlockSnapshots = false;
                world.capturedBlockSnapshots.clear();
            }
            scope.phase = 0;
            if (!placed || !cell.targetMatches(world)) return rollback(scope, world);
            if (ForgeEventFactory.onPlayerBlockPlace(actor, before, ForgeDirection.UP).isCanceled())
                return rollback(scope, world) == Outcome.FAILED ? Outcome.DENIED : Outcome.RECOVERY;
            return cell.targetMatches(world) ? Outcome.SUCCESS : rollback(scope, world);
        } catch (RuntimeException | LinkageError failure) {
            return rollback(scope, world);
        } finally {
            WorldHooks.WRITE.remove();
            if (previousDrops == null) WorldHooks.DISMANTLE_DROPS.remove();
            else WorldHooks.DISMANTLE_DROPS.set(previousDrops);
        }
    }

    static void notifyReplacement(Recovery cell, WorldServer world) {
        world.markAndNotifyBlock(cell.x, cell.y, cell.z, null, cell.oldBlock, cell.targetBlock, 3);
    }

    private static Outcome rollback(Scope scope, WorldServer world) {
        scope.phase = 2;
        try {
            if (!scope.cell.oldMatches(world)
                    && !world.isAirBlock(scope.cell.x, scope.cell.y, scope.cell.z)
                    && !scope.cell.targetMatches(world)) return Outcome.RECOVERY;
            if (!scope.cell.oldMatches(world)
                    && world.getTileEntity(scope.cell.x, scope.cell.y, scope.cell.z) == null)
                world.setBlock(
                        scope.cell.x,
                        scope.cell.y,
                        scope.cell.z,
                        scope.cell.oldBlock,
                        scope.cell.oldMeta,
                        2);
            if (scope.cell.oldMatches(world)) {
                world.markBlockForUpdate(scope.cell.x, scope.cell.y, scope.cell.z);
                return Outcome.FAILED;
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        return Outcome.RECOVERY;
    }

    /**
     * Retry a failed rollback only while the cell is still our old/target/empty identity. Never
     * overwrite a foreign block.
     */
    static boolean recover(Recovery cell, WorldServer world) {
        if (cell.oldMatches(world)) return true;
        if (WorldHooks.WRITE.get() != null
                || world.getTileEntity(cell.x, cell.y, cell.z) != null
                || !world.isAirBlock(cell.x, cell.y, cell.z) && !cell.targetMatches(world))
            return false;
        Scope scope = new Scope(world, cell);
        WorldHooks.ItemDropSink previousDrops = WorldHooks.DISMANTLE_DROPS.get();
        WorldHooks.WRITE.set(scope);
        WorldHooks.DISMANTLE_DROPS.set(
                (source, stack) ->
                        source == world
                                || previousDrops != null && previousDrops.accept(source, stack));
        try {
            return rollback(scope, world) == Outcome.FAILED;
        } finally {
            WorldHooks.WRITE.remove();
            if (previousDrops == null) WorldHooks.DISMANTLE_DROPS.remove();
            else WorldHooks.DISMANTLE_DROPS.set(previousDrops);
        }
    }
}
