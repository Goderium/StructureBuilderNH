package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.common.WorldHooks;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

/** One server-side edit session, scoped to one GUI, task and world coordinate. */
final class SingleCellPlacement {
    final BuildTask task;
    final ChunkCoordinates at;
    final boolean host;
    final int x, y, z;
    private Block oldBlock;
    private int oldMeta, oldMachine, revision, editGeneration;
    private TileEntity oldTile;
    String message = "";
    ItemStack oldDisplay;
    ItemStack selected;
    private int preferredSlot = -1;
    int ticket;

    SingleCellPlacement(BuildTask task, ChunkCoordinates at, EntityPlayerMP actor) {
        this.task = task;
        this.at = new ChunkCoordinates(at);
        x = at.posX; y = at.posY; z = at.posZ;
        host = x == task.x && y == task.y && z == task.z;
        refresh(actor);
        if (host) mark(task.target, -1);
    }

    boolean reachable(EntityPlayerMP actor) {
        if (actor.isDead || actor.dimension != task.dim || !actor.worldObj.blockExists(x, y, z)) return false;
        Vec3 eye = Vec3.createVectorHelper(actor.posX, actor.posY + actor.getEyeHeight(), actor.posZ);
        Vec3 center = Vec3.createVectorHelper(x + .5, y + .5, z + .5);
        if (eye.distanceTo(center) > 13) return false;
        MovingObjectPosition hit = actor.worldObj.rayTraceBlocks(eye, center, false, true, false);
        return hit == null || hit.blockX == x && hit.blockY == y && hit.blockZ == z;
    }

    private static int machine(TileEntity tile) {
        return tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getMetaTileID() : -1;
    }

    private void refresh(EntityPlayerMP actor) {
        World w = actor.worldObj;
        oldBlock = w.getBlock(x, y, z);
        oldMeta = w.getBlockMetadata(x, y, z);
        oldTile = w.getTileEntity(x, y, z);
        oldMachine = machine(oldTile);
        ItemStack display = null;
        if (oldTile instanceof IGregTechTileEntity && ((IGregTechTileEntity) oldTile).getMetaTileEntity() != null)
            display = ((IGregTechTileEntity) oldTile).getMetaTileEntity().getStackForm(1);
        else if (oldBlock != Blocks.air)
            display = new ItemStack(oldBlock, 1, oldBlock.damageDropped(oldMeta));
        oldDisplay = display != null && display.getItem() != null && oldBlock != Blocks.air ? display : null;
        revision = task.revision;
        editGeneration = task.manualEditGeneration;
        ticket++;
    }

    boolean occupied() { return oldBlock != Blocks.air; }

    void select(EntityPlayerMP actor, int index) {
        if (index >= 0 && index < actor.inventory.mainInventory.length) mark(actor.inventory.mainInventory[index], index);
    }

    void mark(ItemStack stack, int index) {
        if (stack != null && (!(stack.getItem() instanceof ItemBlock) || host && !task.target.isItemEqual(stack))) return;
        selected = ItemStack.copyItemStack(stack);
        if (selected != null) selected.stackSize = 1;
        preferredSlot = index;
        message = "";
        ticket++;
    }

    private boolean stackMatches(ItemStack stack) {
        return stack != null && stack.stackSize > 0 && Materials.equal(selected, stack);
    }

    private int findInventorySlot(EntityPlayerMP actor) {
        ItemStack[] bag = actor.inventory.mainInventory;
        if (preferredSlot >= 0 && preferredSlot < bag.length && stackMatches(bag[preferredSlot])) return preferredSlot;
        for (int i = 0; i < bag.length; i++) if (stackMatches(bag[i])) return i;
        return -1;
    }

    private boolean targetUnchanged(World w) {
        return revision == task.revision && editGeneration == task.manualEditGeneration
                && w.getBlock(x, y, z) == oldBlock && w.getBlockMetadata(x, y, z) == oldMeta
                && w.getTileEntity(x, y, z) == oldTile && machine(oldTile) == oldMachine;
    }

    void apply(EntityPlayerMP actor, int shownTicket, boolean remove) {
        if (shownTicket != ticket || remove && (host || !occupied())) return;
        if (!remove && selected == null) { message = "cell.select"; return; }
        if (host && occupied()) { message = "cell.changed"; return; }
        if (!task.canChangeCell(at) || !reachable(actor)) { message = "cell.unavailable"; return; }
        WorldServer w = (WorldServer) actor.worldObj;
        if (!targetUnchanged(w)) {
            refresh(actor);
            message = "cell.changed";
            return;
        }
        ItemStack source = remove ? actor.getHeldItem() : selected;
        if (!w.canMineBlock(actor, x, y, z) || !actor.canPlayerEdit(x, y, z, ForgeDirection.UP.ordinal(), source)
                || occupied() && !actor.capabilities.isCreativeMode && oldBlock.getBlockHardness(w, x, y, z) < 0
                || !remove && !w.checkNoEntityCollision(AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1))
                || WorldHooks.WRITE.get() != null || WorldHooks.DISMANTLE_DROPS.get() != null
                || w.captureBlockSnapshots || w.restoringBlockSnapshots || !w.capturedBlockSnapshots.isEmpty()) {
            message = remove ? "cell.removeDenied" : "cell.denied";
            return;
        }
        if (occupied() && MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(x, y, z, w, oldBlock, oldMeta, actor))) {
            message = remove ? "cell.removeDenied" : "cell.denied";
            return;
        }
        if (!targetUnchanged(w)) {
            refresh(actor);
            message = "cell.changed";
            return;
        }
        ItemStack terminal = actor.getHeldItem();
        TerminalEnergy operation = remove ? TerminalEnergy.REMOVE : occupied() ? TerminalEnergy.REPLACE : TerminalEnergy.PLACE;
        double charge = operation.reserve(actor, terminal);
        if (charge < 0) {
            message = "noEnergy";
            return;
        }
        edit(actor, w, terminal, remove, charge);
    }

    private void edit(EntityPlayerMP actor, WorldServer w, ItemStack terminal, boolean remove, double charge) {
        boolean success = false;
        try {
            BlockSnapshot before = BlockSnapshot.getBlockSnapshot(w, x, y, z);
            java.util.List<PlacementLedger> owners = task.store.placementOwners(w, x, y, z);
            boolean creative = actor.capabilities.isCreativeMode;
            int slot = remove || creative ? -1 : findInventorySlot(actor);
            ItemStack reserved = null;
            if (!remove) {
                if (slot >= 0) reserved = actor.inventory.decrStackSize(slot, 1);
                else {
                    if (!creative) {
                        Wireless net = Wireless.find(task, w, actor);
                        if (net != null && !net.allows(appeng.api.config.SecurityPermissions.EXTRACT)) {
                            message = "permission";
                            return;
                        }
                        if (net == null || net.take(selected, 1, appeng.api.config.Actionable.MODULATE) != 1) {
                            message = "cell.shortage";
                            return;
                        }
                    }
                    reserved = selected.copy();
                }
            }
            Materials drops = new Materials();
            CellGuard guard = new CellGuard(w);
            boolean restored = true, attempted = false;
            WorldHooks.WRITE.set(guard);
            WorldHooks.DISMANTLE_DROPS.set((world, stack) -> {
                if (world != w) return false;
                if (guard.harvesting && stack.stackSize > 0) drops.add(stack, stack.stackSize);
                return true;
            });
            try {
                boolean empty = !occupied() || PlacementLedger.harvestBlock(actor, w, x, y, z, oldBlock, oldMeta);
                guard.harvesting = false;
                if (empty && w.isAirBlock(x, y, z) && remove) {
                    success = true;
                } else if (empty && w.isAirBlock(x, y, z) && !remove) {
                    attempted = true;
                    success = reserved.copy().tryPlaceItemIntoWorld(actor, w, x, y, z, ForgeDirection.UP.ordinal(), .5f, .5f, .5f)
                            && !guard.outside && !w.isAirBlock(x, y, z);
                    if (success) orient(w.getTileEntity(x, y, z));
                }
            } catch (RuntimeException | LinkageError failure) {
                success = false;
                com.Goderium.StructureBuilderNH.StructureBuilderNH.LOG.warn("Single block placement failed at " + at, failure);
            } finally {
                try {
                    if (!success) {
                        guard.harvesting = false;
                        w.captureBlockSnapshots = false;
                        w.capturedBlockSnapshots.clear();
                        w.restoringBlockSnapshots = true;
                        restored = restore(w, before);
                        if (restored) for (PlacementLedger owner : owners) owner.record(w, x, y, z);
                    }
                } finally {
                    w.restoringBlockSnapshots = false;
                    WorldHooks.WRITE.remove();
                    WorldHooks.DISMANTLE_DROPS.remove();
                    if (!success && !creative && reserved != null && (restored || !attempted || !containsPlacement(w, reserved)))
                        returnInput(actor, reserved, slot);
                    actor.inventory.markDirty();
                }
            }
            if (success || !restored && (w.getBlock(x, y, z) != oldBlock || w.getBlockMetadata(x, y, z) != oldMeta
                    || oldMachine >= 0 && machine(w.getTileEntity(x, y, z)) != oldMachine)) {
                for (Materials.Entry e : drops.rows.values()) task.salvage.add(e.item, e.count);
                task.singleCellChanged(w, at, success ? !remove : attempted && containsPlacement(w, reserved));
            }
            if (!task.salvage.rows.isEmpty()) task.depositManualDrops(actor, w);
            refresh(actor);
            if (!restored) message = "cell.recheck";
            else if (!success) message = remove ? "cell.removeDenied" : "cell.denied";
            else message = !task.salvage.rows.isEmpty() ? "cell.refund" : remove ? "cell.removed" : "cell.placed";
        } finally {
            if (!success) TerminalEnergy.refund(terminal, charge);
        }
    }

    private void orient(TileEntity placed) {
        if (!(placed instanceof IGregTechTileEntity)) return;
        IGregTechTileEntity gt = (IGregTechTileEntity) placed;
        ForgeDirection front = task.facing.getDirection();
        if (!host) front = task.plan.cellAt(x, y, z).previewFront(front);
        if (gt.getMetaTileEntity() != null && gt.getMetaTileEntity().isFacingValid(front)) gt.setFrontFacing(front);
        if (host && gt.getMetaTileID() == task.target.getMetadata() && gt.getMetaTileEntity() instanceof IAlignment)
            ((IAlignment) gt.getMetaTileEntity()).setExtendedFacing(task.facing);
    }

    private boolean containsPlacement(World w, ItemStack input) {
        if (input == null || Block.getBlockFromItem(input.getItem()) != w.getBlock(x,y,z)) return false;
        return !(input.getItem() instanceof gregtech.common.blocks.ItemMachines)
                || machine(w.getTileEntity(x,y,z)) == input.getMetadata();
    }

    private boolean restore(WorldServer world, BlockSnapshot before) {
        try {
            before.restore(true, false);
            TileEntity tile = world.getTileEntity(x, y, z);
            return world.getBlock(x, y, z) == oldBlock && world.getBlockMetadata(x, y, z) == oldMeta
                    && (oldTile == null ? tile == null : oldTile.getClass().isInstance(tile) && machine(tile) == oldMachine);
        } catch (RuntimeException | LinkageError failure) {
            com.Goderium.StructureBuilderNH.StructureBuilderNH.LOG.error("Could not restore block at " + at, failure);
            return false;
        }
    }

    private void returnInput(EntityPlayerMP actor, ItemStack reserved, int slot) {
        if (slot >= 0) {
            ItemStack current = actor.inventory.mainInventory[slot];
            if (current == null) { actor.inventory.setInventorySlotContents(slot, reserved); return; }
            if (Materials.equal(current, reserved) && current.stackSize < current.getMaxStackSize()) { current.stackSize++; return; }
            if (actor.inventory.addItemStackToInventory(reserved)) return;
        }
        task.salvage.add(reserved, reserved.stackSize);
        task.changed();
    }

    private final class CellGuard implements WorldHooks.WriteGuard {
        final World world;
        boolean harvesting = true, outside;
        CellGuard(World world) { this.world = world; }
        @Override public Boolean setBlock(World w, int px, int py, int pz, Block block, int meta, int flags) {
            if (!protect(w, px, py, pz)) return null;
            if (!harvesting) outside = true;
            return false;
        }
        @Override public boolean protect(World w, int px, int py, int pz) {
            return w != world || px != x || py != y || pz != z;
        }
    }
}
