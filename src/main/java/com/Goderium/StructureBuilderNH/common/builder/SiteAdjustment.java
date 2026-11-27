package com.Goderium.StructureBuilderNH.common.builder;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.util.MiscUtils;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Reviews both structures before reserving materials or touching the live site. */
final class SiteAdjustment {
    private final BuildTask task;
    private final int sourceTier;
    private final NBTTagCompound sourceChannels;
    private final boolean sourceActual, sourceSurvival;
    private final Set<String> exact = new HashSet<>();

    private Blueprint source, reviewed;
    private int cells, previousCells;
    private int reviewedRevision = -1;
    private final int[] position = new int[3];
    private final BuildTask.OrdinaryCell target = new BuildTask.OrdinaryCell();

    final Materials additions = new Materials(), removals = new Materials();
    final UpgradeBill replacements = new UpgradeBill();
    final PlacementLedger clearing;
    ChunkCoordinates blocker;
    String obstruction;
    boolean ready, approved, targetShapeChanged;
    int additionsCount, replacementCount, removalCount, completedClears;
    long reviewedAt;

    SiteAdjustment(BuildTask task) {
        this(task, task.tier, task.channels, task.readActualStructure, task.useSurvivalConstruct);
        source = task.plan;
    }

    private SiteAdjustment(BuildTask task, int sourceTier, NBTTagCompound channels, boolean actual, boolean survival) {
        this.task = task;
        this.sourceTier = sourceTier;
        sourceChannels = (NBTTagCompound) channels.copy();
        sourceActual = actual;
        sourceSurvival = survival;
        clearing = new PlacementLedger(task, false);
    }

    void selectChannels(Set<String> selected) {
        exact.addAll(selected);
        resetReview();
    }

    private void resetReview() {
        approved = ready = false;
        reviewed = null;
        cells = previousCells = additionsCount = replacementCount = removalCount = completedClears = 0;
        blocker = null;
        obstruction = null;
        additions.rows.clear();
        removals.rows.clear();
        replacements.clear();
        clearing.discard();
    }

    void discard() {
        resetReview();
        source = null;
    }

    ChannelUpgrade.Result replacement(Blueprint.Cell cell, World world, int x, int y, int z) {
        if (exact.isEmpty() || !exact.contains(task.plan.upgradeChannel(cell))) return null;
        return task.plan.upgrade(cell, world, x, y, z, exact);
    }

    void advance(EntityPlayerMP actor, WorldServer world, long deadline) {
        if (approved || task.plan == null || !task.plan.ready || System.nanoTime() >= deadline) return;
        if (reviewed != task.plan || reviewedRevision != task.revision
                || ready && blocker != null && world.getTotalWorldTime() - reviewedAt >= 30) {
            resetReview();
            reviewed = task.plan;
            reviewedRevision = task.revision;
        }
        if (ready) return;

        if (sourceTier > 0 && source == null) {
            ItemStack trigger = BuildTask.structureTrigger(task.target, sourceTier, sourceChannels);
            source = Blueprint.read(task.target, trigger, task.facing, task.x, task.y, task.z, actor, i -> false,
                    sourceActual || sourceSurvival, sourceActual);
        }
        if (source != null && !source.ready) {
            source.advance(deadline);
            if (!source.ready) return;
        }
        if (System.nanoTime() >= deadline) return;
        for (int steps = 0; blocker == null && steps < 8192 && System.nanoTime() < deadline; steps++) {
            if (cells < task.plan.size) {
                inspectGoal(world, cells++);
            } else if (source != null && previousCells < source.size) {
                Blueprint.Cell old = source.at(previousCells++, position);
                if (task.plan.cellAt(position[0], position[1], position[2]) == null)
                    inspectRemoval(world, position[0], position[1], position[2], old);
            } else break;
        }
        if (blocker != null || cells == task.plan.size && (source == null || previousCells == source.size)) {
            ready = true;
            reviewedAt = world.getTotalWorldTime();
            task.changed();
        }
    }

    private void inspectGoal(WorldServer world, int index) {
        task.ordinaryCell(index, world, target);
        int x = target.x, y = target.y, z = target.z;
        if (target.matches) return;
        if (target.status == BuildTask.OrdinaryStatus.MISSING) {
            if (!target.blankRitual) additions.add(target.material, 1);
            additionsCount++;
            return;
        }
        if (!target.canClear || target.status != BuildTask.OrdinaryStatus.BLOCKED || world.getBlock(x, y, z) == Blocks.air) {
            block(x, y, z, "obstruction");
            return;
        }
        Blueprint.Cell old = source == null ? null : source.cellAt(x, y, z);
        ChannelUpgrade.Result nativeSwap = replacement(target.cell, world, x, y, z);
        if (!ownedSource(world, x, y, z, old) && (nativeSwap == null || nativeSwap.kind != ChannelUpgrade.Kind.REPLACE)) {
            block(x, y, z, "obstruction");
            return;
        }
        if (target.material != null && UpgradePlacement.prepare(world, x, y, z, target.material, false) == null) {
            block(x, y, z, "upgradePlacement");
            return;
        }
        if (recordRemoval(world, x, y, z)) recordTarget(world, x, y, z);
    }

    private void recordTarget(WorldServer world, int x, int y, int z) {
        ItemStack before = installed(world, x, y, z);
        if (target.material == null) {
            if (before != null) removals.add(before, 1);
            removalCount++;
        } else {
            if (before == null || !replacements.add(before, target.material)) {
                block(x, y, z, "upgradeRows");
                return;
            }
            replacementCount++;
        }
    }

    private void inspectRemoval(WorldServer world, int x, int y, int z, Blueprint.Cell old) {
        if (x == task.x && y == task.y && z == task.z || old.isFluid()
                || old.air && !old.hatch && source.upgradeChannel(old).isEmpty()) return;
        if (!world.blockExists(x, y, z)) {
            block(x, y, z, "obstruction");
            return;
        }
        if (world.getBlock(x, y, z) == Blocks.air || !ownedSource(world, x, y, z, old)) return;
        if (!recordRemoval(world, x, y, z)) return;
        ItemStack before = installed(world, x, y, z);
        if (before != null) removals.add(before, 1);
        removalCount++;
    }

    private boolean ownedSource(WorldServer world, int x, int y, int z, Blueprint.Cell old) {
        if (old == null || old.isFluid()) return false;
        if ((!old.air || old.hatch) && source.matches(old, world, x, y, z)) return true;
        String channel = source.upgradeChannel(old);
        return !channel.isEmpty() && source.upgrade(old, world, x, y, z, Collections.singleton(channel)).sourceValue > 0;
    }

    private boolean recordRemoval(WorldServer world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (world.getBlock(x, y, z).getBlockHardness(world, x, y, z) < 0
                || tile instanceof IGregTechTileEntity && ((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEMultiBlockBase) {
            block(x, y, z, "obstruction");
            return false;
        }
        clearing.record(world, x, y, z);
        if (clearing.matches(world, x, y, z)) return true;
        block(x, y, z, "upgradeRows");
        return false;
    }

    static ItemStack installed(WorldServer world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof IGregTechTileEntity && ((IGregTechTileEntity) tile).getMetaTileEntity() != null)
            return ((IGregTechTileEntity) tile).getMetaTileEntity().getStackForm(1);
        IStructureElement.BlocksToPlace place = IStructureElement.BlocksToPlace.create(world.getBlock(x, y, z),
                world.getBlockMetadata(x, y, z));
        if (place != null && place.getStacks() != null) {
            for (ItemStack stack : place.getStacks()) if (stack != null && stack.getItem() != null) return stack;
        }
        return null;
    }

    private void block(int x, int y, int z, String reason) {
        blocker = new ChunkCoordinates(x, y, z);
        obstruction = reason;
    }

    boolean canStart() {
        return ready && blocker == null && additionsCount + replacementCount + removalCount > 0;
    }

    NBTTagCompound save() {
        NBTTagCompound n = new NBTTagCompound();
        n.setInteger("sourceTier", sourceTier);
        n.setTag("sourceChannels", sourceChannels);
        n.setBoolean("sourceActual", sourceActual);
        n.setBoolean("sourceSurvival", sourceSurvival);
        n.setBoolean("targetShapeChanged", targetShapeChanged);
        NBTTagCompound selected = new NBTTagCompound();
        for (String channel : exact) selected.setBoolean(channel, true);
        n.setTag("exact", selected);
        if (approved) {
            n.setBoolean("approved", true);
            n.setTag("clearing", clearing.save());
            n.setInteger("adds", additionsCount);
            n.setInteger("swaps", replacementCount);
            n.setInteger("removals", removalCount);
            n.setInteger("cleared", completedClears);
            n.setTag("additionRows", additions.save());
            n.setTag("removalRows", removals.save());
            n.setTag("replacementRows", replacements.save());
        }
        return n;
    }

    static SiteAdjustment load(BuildTask task, NBTTagCompound n, boolean resumeExecution) {
        int sourceTier = n.getInteger("sourceTier");
        if (sourceTier <= 0) return null;
        SiteAdjustment result = new SiteAdjustment(task, sourceTier, n.getCompoundTag("sourceChannels"),
                n.getBoolean("sourceActual"), n.getBoolean("sourceSurvival"));
        for (Object key : MiscUtils.getTagKeys(n.getCompoundTag("exact"))) result.exact.add((String) key);
        result.targetShapeChanged = n.getBoolean("targetShapeChanged");
        result.approved = resumeExecution && n.getBoolean("approved");
        if (result.approved) {
            result.ready = true;
            result.clearing.load(n.getCompoundTag("clearing"));
            result.additionsCount = n.getInteger("adds");
            result.replacementCount = n.getInteger("swaps");
            result.removalCount = n.getInteger("removals");
            result.completedClears = n.getInteger("cleared");
            result.additions.read(n.getTagList("additionRows", 10));
            result.removals.read(n.getTagList("removalRows", 10));
            result.replacements.load(n.getTagList("replacementRows", 10));
        }
        return result;
    }
}
