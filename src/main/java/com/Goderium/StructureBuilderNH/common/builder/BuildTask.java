package com.Goderium.StructureBuilderNH.common.builder;

import appeng.api.config.*;

import com.Goderium.StructureBuilderNH.config.RebuildConfig;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.constructable.*;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.*;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.world.WorldServer;
import net.minecraft.world.World;

import java.util.*;
import net.minecraftforge.common.util.ForgeDirection;

final class BuildTask {
    enum State {
        DRAFT,
        PREVIEW,
        INSPECTING_UPGRADE,
        UPGRADE_REVIEW,
        SCANNING,
        MISSING,
        PLANNING,
        CPU,
        ORDERED,
        READY,
        QUEUED,
        BUILDING,
        VERIFYING,
        BLOCKED,
        DONE,
        REFUND, DISMANTLING, TEARDOWN_PAUSED, DISMANTLED
    }

    final PlacementLedger ledger = new PlacementLedger(this);
    private Map<Long, BitSet> structureCells;
    private final Set<ChunkCoordinates> manualHatches = new HashSet<>();
    private final ChunkCoordinates lookupHatch = new ChunkCoordinates();
    final TaskChunks workChunks = new TaskChunks();
    boolean deleteRequested, dismantleRequested;
    final BuilderTasks store;
    final String id, owner, tool;
    final int dim;
    private String dimensionName;
    int x, y, z;
    ExtendedFacing facing;
    boolean existing;
    private boolean temporaryHost;
    boolean unlimitedRange;
    String key = "", orderId = "", detail = "", reason = "";
    State state = State.DRAFT;
    boolean auto, received;
    private boolean checkMaterialsOnly;
    ChunkCoordinates issue;

    void pointAt(int x, int y, int z) {
        if (issue == null || issue.posX != x || issue.posY != y || issue.posZ != z) {
            issue = new ChunkCoordinates(x, y, z);
            changed();
        }
    }

    ItemStack target;
    int tier = 1, cursor, total, placed, revision;
    boolean useSurvivalConstruct = RebuildConfig.useSurvivalConstruct;
    int siteRevision = -1, siteInstalled, siteTotal, siteUnknown;
    int manualEditGeneration;
    int previewLayers;
    boolean texturedPreview;
    final Set<String> upgradeChannels = new LinkedHashSet<>();
    private final UpgradeBill upgradeReview = new UpgradeBill(), upgradeScan = new UpgradeBill();
    private UpgradeSnapshot upgradeReviewSources = new UpgradeSnapshot(), upgradeScanSources = new UpgradeSnapshot();
    final List<UpgradeBill.Row> upgradeRows = Collections.unmodifiableList(upgradeReview.rows);
    int upgradeCount;
    private boolean upgradeApproved, upgradeRecoveryFault;
    private boolean upgradeReturnToBuild;
    private int upgradeReturnLayers;
    private String upgradeNetwork = "";
    private BitSet upgradeCommitted = new BitSet();
    private UpgradePlacement.Recovery upgradeRecovery;
    NBTTagCompound materialSelections = new NBTTagCompound();
    NBTTagCompound materialCells = new NBTTagCompound(), materialGroups = new NBTTagCompound();
    private Blueprint selectionPlan;
    private MaterialSelection[] selectedMaterials;
    private final Map<MaterialChoice, int[]> materialTotals = new IdentityHashMap<>();
    NBTTagCompound pendingDefaults = new NBTTagCompound();
    NBTTagCompound channels = new NBTTagCompound(), savedLink;
    final Materials needed = new Materials(),
            held = new Materials(),
            salvage = new Materials(),
            backpack = new Materials(),
            shortage = new Materials(),
            orderItems = new Materials(),
            errors = new Materials();
    final Set<String> observedChannels = new HashSet<>();
    final MaterialAssignments funding = new MaterialAssignments(),
            orderFunding = new MaterialAssignments();
    Blueprint plan;
    MaterialOrder order;
    private boolean obsoleteFluidOrder;
    private final int[] xyz = new int[3];
    private BitSet pending = new BitSet();
    private BitSet confirmed = new BitSet();
    private long nextSalvageRetry;
    private Blueprint ordinaryProgressPlan;
    private BitSet ordinaryCounted = new BitSet(), ordinaryInstalled = new BitSet();
    private int ordinaryProgressCursor;
    private boolean ordinaryProgressReady;
    private final OrdinaryCell ordinaryWork = new OrdinaryCell();

    enum OrdinaryStatus { INSTALLED, MISSING, BLOCKED, UNLOADED, UNKNOWN, AIR }

    /** Reusable read-only result; neither classification nor material lookup acquires chunks. */
    static final class OrdinaryCell {
        Blueprint.Cell cell;
        ItemStack material, installedMaterial;
        OrdinaryStatus status = OrdinaryStatus.UNKNOWN;
        int x, y, z;
        boolean counted, matches, blankRitual, host;
        private final int[] position = new int[3];
    }

    void ordinaryCell(int index, World world, OrdinaryCell result) {
        ordinaryCell(index, world, result, true);
    }

    private void ordinaryCell(int index, World world, OrdinaryCell result, boolean itemDetails) {
        result.cell = null;
        result.material = result.installedMaterial = null;
        result.status = OrdinaryStatus.UNKNOWN;
        result.x = result.y = result.z = 0;
        result.counted = result.matches = result.blankRitual = result.host = false;
        if (upgrading() || plan == null || !plan.ready || index < 0 || index >= plan.size) return;
        try {
            Blueprint.Cell cell = plan.at(index, result.position);
            result.cell = cell;
            result.x = result.position[0]; result.y = result.position[1]; result.z = result.position[2];
            result.host = index == 0;
            MaterialSelection selection = cell.choice == null ? null : selection(result.x, result.y, result.z);
            result.material = result.host ? target : selection == null ? cell.item : selection.item();
            result.counted = result.material != null && result.material.getItem() != null
                    && (result.host || selection != null || !cell.air);
            if (world == null || !world.blockExists(result.x, result.y, result.z)) {
                result.status = OrdinaryStatus.UNLOADED;
                return;
            }
            boolean air = world.isAirBlock(result.x, result.y, result.z);
            boolean previewFluid = selection == null ? cell.isFluid() : selection.group.isFluid(selection.index);
            if (previewFluid) {
                // Leave process fluids to the machine or player, and keep empty cells visible in the projection.
                if (air) {
                    result.matches = (selection == null || !selection.conflict) && (cell.constraints == null
                            || cell.constraints.stream().allMatch(c -> c.isFluid() || c.air || c.choice == cell.choice && c.choice != null));
                    result.status = result.matches ? OrdinaryStatus.AIR : OrdinaryStatus.BLOCKED;
                } else {
                    result.matches = (selection != null || world.getBlock(result.x, result.y, result.z) == cell.block)
                            && materialMatches(cell, world, result.x, result.y, result.z);
                    result.status = result.matches ? OrdinaryStatus.INSTALLED : OrdinaryStatus.BLOCKED;
                    if (result.matches && !StructureFluids.isFluid(world.getBlock(result.x, result.y, result.z))) {
                        result.installedMaterial = ordinaryInstalledMaterial(world, result);
                        result.counted = result.installedMaterial != null;
                    }
                }
                return;
            }
            result.matches = result.host ? anchorValid(world)
                    : materialMatches(cell, world, result.x, result.y, result.z);
            if (result.matches) {
                if (air) {
                    result.counted = false;
                    result.status = OrdinaryStatus.AIR;
                } else {
                    result.status = OrdinaryStatus.INSTALLED;
                    if (StructureFluids.isFluid(world.getBlock(result.x, result.y, result.z))) {
                        result.counted = false;
                        return;
                    }
                    if (itemDetails || result.material == null && selection != null && !selection.explicit)
                        result.installedMaterial = ordinaryInstalledMaterial(world, result);
                    // An implicit optional-air choice preserves an already installed native-valid solid.
                    if (result.material == null && selection != null && !selection.explicit)
                        result.counted = result.installedMaterial != null;
                }
                return;
            }
            result.blankRitual = cell.inscribedRitual
                    && world.getBlock(result.x, result.y, result.z) == cell.block
                    && world.getBlockMetadata(result.x, result.y, result.z) == 0;
            if (result.host && (existing || !plan.controller.isFacingValid(facing.getDirection()))
                    || selection != null && selection.conflict || !air && !result.blankRitual) {
                result.status = OrdinaryStatus.BLOCKED;
            } else result.status = result.material == null ? OrdinaryStatus.UNKNOWN : OrdinaryStatus.MISSING;
        } catch (RuntimeException | LinkageError failure) {
            result.matches = false;
            result.status = OrdinaryStatus.UNKNOWN;
        }
    }

    private ItemStack ordinaryInstalledMaterial(World world, OrdinaryCell cell) {
        TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
        if (tile instanceof IGregTechTileEntity) {
            IGregTechTileEntity gt = (IGregTechTileEntity) tile;
            if (gt.getMetaTileEntity() != null) {
                ItemStack stack = gt.getMetaTileEntity().getStackForm(1);
                if (stack != null && stack.getItem() != null) return stack;
            }
            return cell.material;
        }
        IStructureElement.BlocksToPlace present = IStructureElement.BlocksToPlace.create(
                world.getBlock(cell.x, cell.y, cell.z), world.getBlockMetadata(cell.x, cell.y, cell.z));
        if (present != null && present.getStacks() != null)
            for (ItemStack item : present.getStacks()) if (item != null && item.getItem() != null) return item;
        return cell.material;
    }

    private void invalidateOrdinaryProgress() {
        ordinaryProgressPlan = null;
        ordinaryProgressCursor = 0;
        ordinaryProgressReady = false;
        ordinaryCounted = new BitSet();
        ordinaryInstalled = new BitSet();
    }

    /** Rebuild once for an executing/resumed plan, without trusting persisted historical counters. */
    private boolean prepareOrdinaryProgress(World world, long deadline) {
        if (ordinaryProgressPlan != plan) {
            invalidateOrdinaryProgress();
            ordinaryProgressPlan = plan;
        }
        for (int n = 0; ordinaryProgressCursor < plan.size && n < 8192 && System.nanoTime() < deadline;
                n++, ordinaryProgressCursor++) {
            ordinaryCell(ordinaryProgressCursor, world, ordinaryWork, false);
            updateOrdinaryProgress(ordinaryProgressCursor, ordinaryWork);
        }
        if (ordinaryProgressCursor < plan.size) return false;
        if (!ordinaryProgressReady) {
            total = ordinaryCounted.cardinality();
            placed = ordinaryInstalled.cardinality();
            ordinaryProgressReady = true;
            changed();
        }
        return true;
    }

    private void updateOrdinaryProgress(int index, OrdinaryCell cell) {
        boolean installed = cell.counted && cell.status == OrdinaryStatus.INSTALLED;
        if (ordinaryCounted.get(index) != cell.counted) {
            if (ordinaryProgressReady) total += cell.counted ? 1 : -1;
            ordinaryCounted.set(index, cell.counted);
            if (ordinaryProgressReady) changed();
        }
        if (ordinaryInstalled.get(index) != installed) {
            if (ordinaryProgressReady) placed += installed ? 1 : -1;
            ordinaryInstalled.set(index, installed);
            if (ordinaryProgressReady) changed();
        }
    }

    static final class MaterialSelection {
        final int x, y, z;
        final String groupKey, piece;
        final char symbol;
        final Blueprint.Cell cell;
        final MaterialChoice group;
        final boolean supported;
        int index;
        boolean explicit, conflict;

        MaterialSelection(Blueprint.Cell cell, int x, int y, int z) {
            this.cell = cell;
            this.x = x; this.y = y; this.z = z;
            group = cell.choice;
            supported = group != null;
            groupKey = group == null ? "" : group.key;
            piece = cell.piece;
            symbol = cell.symbol;
            index = group == null ? -1 : group.defaultIndex;
        }

        ItemStack item() {
            if (!supported) return cell.item;
            return group.isFluid(index) ? null : group.optionItem(index);
        }
        String name() { return supported ? group.optionName(index) : cell.item == null ? "" : cell.item.getDisplayName(); }
    }

    private String materialPosition(int px, int py, int pz) {
        return (px-x) + "," + (py-y) + "," + (pz-z);
    }

    private void ensureSelections() {
        if (plan == null || !plan.ready || selectionPlan == plan) return;
        materialTotals.clear();
        if (plan.choices.isEmpty()) {
            selectedMaterials = null;
            selectionPlan = plan;
            return;
        }
        Map<String, Integer> legacyRoles = new HashMap<>();
        Map<MaterialChoice, IStructureElement<?>> legacySources = new IdentityHashMap<>();
        Map<IStructureElement<?>, Integer> sourceRoles = new IdentityHashMap<>();
        for (MaterialChoice group : plan.choices) {
            if (group.legacyKey != null) legacyRoles.merge(group.legacyKey, 1, Integer::sum);
            Blueprint.Cell first = plan.cellAt(group.x, group.y, group.z);
            if (first != null && first.choice == group) {
                legacySources.put(group, first.element);
                sourceRoles.merge(first.element, 1, Integer::sum);
            }
        }
        for (MaterialChoice group : plan.choices) {
            IStructureElement<?> source = legacySources.get(group);
            if (group.allowsAir || group.legacyKey == null || materialSelections.hasKey(group.key)
                    || source == null || sourceRoles.get(source) != 1
                    || legacyRoles.get(group.legacyKey) != 1 || !materialSelections.hasKey(group.legacyKey, 11)) continue;
            int[] legacy = materialSelections.getIntArray(group.legacyKey).clone();
            long secondary = 0;
            boolean valid = legacy.length == group.optionCount();
            for (int i = 0; i < legacy.length; i++) {
                if (legacy[i] < 0) valid = false;
                if (i > 0) secondary += legacy[i];
            }
            // The old editor stored only secondary quantities; index zero was implicit.
            if (valid && secondary <= group.count) {
                legacy[0] = group.count - (int) secondary;
                materialSelections.setIntArray(group.key, legacy);
                changed();
            }
        }
        selectedMaterials = new MaterialSelection[plan.size];
        Map<MaterialChoice, List<MaterialSelection>> groups = new LinkedHashMap<>();
        int[] position = new int[3];
        for (int i = 0; i < plan.size; i++) {
            Blueprint.Cell cell = plan.at(i, position);
            if (cell.choice == null) continue;
            MaterialSelection selection = new MaterialSelection(cell, position[0], position[1], position[2]);
            selectedMaterials[i] = selection;
            if (selection.supported) groups.computeIfAbsent(selection.group, g -> new ArrayList<>()).add(selection);
        }
        for (Map.Entry<MaterialChoice, List<MaterialSelection>> entry : groups.entrySet()) {
            MaterialChoice group = entry.getKey();
            List<MaterialSelection> cells = entry.getValue();
            group.count = cells.size();
            int[] remaining = group.allocation(materialSelections);
            int groupIndex = materialGroups.hasKey(group.key) ? group.optionIndex(materialGroups.getString(group.key)) : -1;
            boolean hasLocal = !materialCells.hasNoTags();
            for (MaterialSelection selection : cells) {
                NBTTagCompound local = hasLocal ? materialCells.getCompoundTag(materialPosition(selection.x, selection.y, selection.z)) : null;
                int selected = local != null && group.key.equals(local.getString("group"))
                        ? group.optionIndex(local.getString("option")) : -1;
                if (selected < 0) selected = groupIndex;
                if (selected >= 0) {
                    selection.index = selected;
                    selection.explicit = true;
                    if (remaining[selected] > 0) remaining[selected]--;
                }
            }
            int next = 0;
            for (MaterialSelection selection : cells) {
                if (!selection.explicit) {
                    while (next < remaining.length && remaining[next] == 0) next++;
                    selection.index = next < remaining.length ? next : group.defaultIndex;
                    if (next < remaining.length) remaining[next]--;
                }
                if (!plan.accepts(selection.cell, selection.index, selection.x, selection.y, selection.z)) {
                    selection.conflict = true;
                    if (!selection.explicit) {
                        for (int i = 0; i < group.optionCount(); i++) {
                            if (plan.accepts(selection.cell, i, selection.x, selection.y, selection.z)) {
                                selection.index = i;
                                selection.conflict = false;
                                break;
                            }
                        }
                    }
                }
            }
            materialTotals.put(group, new int[group.optionCount()]);
        }
        for (int i = 0; i < selectedMaterials.length; i++) {
            MaterialSelection selection = selectedMaterials[i];
            if (selection != null) {
                plan.displayChoice(i, selection.group.isAir(selection.index), selection.group.isFluid(selection.index));
                materialTotals.get(selection.group)[selection.index]++;
            }
        }
        selectionPlan = plan;
    }

    MaterialSelection selection(int px, int py, int pz) {
        ensureSelections();
        return cachedSelection(px, py, pz);
    }

    MaterialSelection cachedSelection(int px, int py, int pz) {
        if (plan == null || selectionPlan != plan || !plan.ready || selectedMaterials == null || plan.choices.isEmpty()) return null;
        int index = plan.indexAt(px, py, pz);
        return index < 0 ? null : selectedMaterials[index];
    }

    boolean hasCachedSelections() {
        return plan != null && selectionPlan == plan;
    }

    boolean materialSelectable(int px, int py, int pz) {
        if (!editable() || plan == null || !plan.ready) return false;
        // Editing a planned choice invalidates its previous confirmation in materialsChanged.
        MaterialSelection selection = selection(px, py, pz);
        return selection != null && selection.supported;
    }

    int previewIndex(MaterialSelection selection, World world) {
        if (selection == null || !selection.supported) return -1;
        if (!selection.explicit && world != null && !world.isAirBlock(selection.x, selection.y, selection.z)
                && plan.matches(selection.cell, world, selection.x, selection.y, selection.z)) {
            int present = selection.group.indexAt(world, selection.x, selection.y, selection.z);
            if (present >= 0) return present;
            if (selection.group.allowsAir) return 0;
        }
        return selection.index;
    }

    ItemStack previewItem(MaterialSelection selection, World world) {
        if (selection == null || !selection.supported) return null;
        if (!selection.explicit && world != null && !world.isAirBlock(selection.x, selection.y, selection.z)
                && plan.matches(selection.cell, world, selection.x, selection.y, selection.z)) {
            if (StructureFluids.isFluid(world.getBlock(selection.x, selection.y, selection.z))) return null;
            IStructureElement.BlocksToPlace present = IStructureElement.BlocksToPlace.create(
                    world.getBlock(selection.x, selection.y, selection.z),
                    world.getBlockMetadata(selection.x, selection.y, selection.z));
            if (present != null && present.getStacks() != null)
                for (ItemStack item : present.getStacks()) if (item != null) return item;
        }
        return selection.item();
    }

    boolean previewConflict(MaterialSelection selection, World world) {
        return selection != null && (selection.conflict || selection.explicit && world != null
                && !world.isAirBlock(selection.x, selection.y, selection.z)
                && selection.group.indexAt(world, selection.x, selection.y, selection.z) != selection.index);
    }

    boolean setMaterialSelection(int px, int py, int pz, int index, boolean wholeGroup) {
        if (!editable()) return false;
        MaterialSelection selection = selection(px, py, pz);
        if (selection == null || !selection.supported || index < 0 || index >= selection.group.optionCount()
                || wholeGroup && !selection.group.groupSelectable()) return false;
        if (wholeGroup) {
            materialGroups.setString(selection.group.key, selection.group.optionToken(index));
            for (Object value : new ArrayList<>(com.gtnewhorizon.structurelib.util.MiscUtils.getTagKeys(materialCells))) {
                String key = (String) value;
                if (selection.group.key.equals(materialCells.getCompoundTag(key).getString("group"))) materialCells.removeTag(key);
            }
            int[] quantities = new int[selection.group.optionCount()];
            quantities[index] = selection.group.count;
            materialSelections.setIntArray(selection.group.key, quantities);
        } else {
            NBTTagCompound local = new NBTTagCompound();
            local.setString("group", selection.group.key);
            local.setString("option", selection.group.optionToken(index));
            materialCells.setTag(materialPosition(px, py, pz), local);
        }
        materialsChanged(selection.group, plan.indexAt(px, py, pz), wholeGroup);
        return true;
    }

    int[] effectiveAllocation(MaterialChoice group) {
        ensureSelections();
        int[] counts = materialTotals.get(group);
        return counts == null ? new int[group.optionCount()] : counts.clone();
    }

    void setMaterialQuantities(MaterialChoice group, int[] counts) {
        if (!editable() || plan == null || !plan.ready || !plan.choices.contains(group) || counts.length != group.optionCount()) return;
        ensureSelections();
        int[] minimum = new int[group.optionCount()];
        for (MaterialSelection selection : selectedMaterials) {
            if (selection == null || selection.group != group || materialCells.hasNoTags()) continue;
            NBTTagCompound local = materialCells.getCompoundTag(materialPosition(selection.x, selection.y, selection.z));
            if (!group.key.equals(local.getString("group"))) continue;
            int index = group.optionIndex(local.getString("option"));
            if (index >= 0) minimum[index]++;
        }
        int left = group.count;
        for (int value : minimum) left -= value;
        int[] quantities = minimum.clone();
        for (int i = 1; i < quantities.length; i++) {
            int extra = Math.min(left, Math.max(0, counts[i] - quantities[i]));
            quantities[i] += extra;
            left -= extra;
        }
        quantities[0] += left;
        materialSelections.setIntArray(group.key, quantities);
        materialGroups.removeTag(group.key);
        materialsChanged(group, -1, true);
    }

    private void materialsChanged(MaterialChoice group, int position, boolean wholeGroup) {
        state = previewLayers == 0 ? State.DRAFT : State.PREVIEW;
        needed.rows.clear(); shortage.rows.clear(); errors.rows.clear();
        if (confirmed == null) {
            confirmed = new BitSet(plan.size);
            confirmed.set(0, plan.size);
            confirmed.andNot(pending);
        }
        if (wholeGroup) {
            for (int i = 0; i < selectedMaterials.length; i++) {
                MaterialSelection selection = selectedMaterials[i];
                if (selection != null && selection.group == group) confirm(i, false);
            }
        } else if (position >= 0) confirm(position, false);
        pending.clear();
        reason = detail = "";
        issue = null;
        selectionPlan = null;
        revision++;
        ensureSelections();
        changed();
    }

    private boolean materialMatches(Blueprint.Cell cell, World world, int px, int py, int pz) {
        MaterialSelection selection = cell.choice == null ? null : selection(px, py, pz);
        if (selection != null && selection.supported) {
            if (selection.conflict) return false;
            if (selection.explicit && selection.group.indexAt(world, px, py, pz) != selection.index) return false;
            if (world.isAirBlock(px, py, pz) && selection.item() != null) return false;
        }
        if (!plan.matches(cell, world, px, py, pz)) return false;
        if (cell.hatch && !manualHatches.isEmpty() && !world.isAirBlock(px, py, pz)) {
            lookupHatch.set(px, py, pz);
            if (manualHatches.contains(lookupHatch))
                return plan.checkManualHatch(cell, world, px, py, pz, target, facing);
        }
        return true;
    }

    private boolean initiallyConfirmed(int index) {
        // Older tasks that finished scanning stored only the complementary pending mask.
        return confirmed == null ? !pending.get(index) : confirmed.get(index);
    }

    private void confirm(int index, boolean matches) {
        if (confirmed.get(index) == matches) return;
        confirmed.set(index, matches);
        Blueprint.Section section = plan.setConfirmed(index, matches);
        PreviewStream.changed(this, section);
        changed();
    }

    BuildTask(
            BuilderTasks s,
            String id,
            String owner,
            String tool,
            int dim,
            int x,
            int y,
            int z,
            ExtendedFacing facing,
            boolean existing) {
        store = s;
        this.id = id;
        this.owner = owner;
        this.tool = tool;
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.facing = facing;
        this.existing = existing;
    }

    void changed() {
        store.markDirty();
    }

    String dimensionName() {
        return dimensionName == null ? "" : dimensionName;
    }

    void rememberDimension(World world) {
        if (dimensionName != null || world == null) return;
        dimensionName = TaskDimension.identify(dim, world);
        changed();
    }

    void discardCachedPlan() {
        plan = null;
        invalidateOrdinaryProgress();
        selectionPlan = null;
        selectedMaterials = null;
        materialTotals.clear();
        ordinaryWork.cell = null;
        ordinaryWork.material = ordinaryWork.installedMaterial = null;
    }

    void resetPlan() {
        discardCachedPlan();
        structureCells = null;
        confirmed = new BitSet();
        if (upgrading()) {
            cursor=0;
            if (state==State.INSPECTING_UPGRADE) { upgradeReview.clear(); upgradeReviewSources.clear(); upgradeCount=0; }
            if (state==State.SCANNING) {
                upgradeScan.clear(); upgradeScanSources.clear(); needed.rows.clear(); pending.clear(); total=placed=0;
            }
        }
        revision++;
        reason = "";
        issue = null;
        changed();
    }

    boolean editable() {
        return !upgrading() && (state == State.DRAFT || state == State.PREVIEW || state == State.MISSING && !hasReserved());
    }

    boolean shortcutDeletable() {
        return state == State.DONE || state == State.DISMANTLED || state == State.PREVIEW && previewLayers != 0;
    }

    /** A preview grip edits the plan. Only a drop inspects the proposed site. */
    boolean movable() {
        return !existing && canCompletePreviewMove();
    }

    boolean canCompletePreviewMove() {
        return state == State.PREVIEW && (previewLayers & (PreviewStream.SHELL | PreviewStream.HATCH)) != 0
                && (!existing || temporaryHost) && target != null && !deleteRequested && !dismantleRequested && !upgrading()
                && ledger.count == 0 && !hasReserved() && savedLink == null;
    }

    PreviewPose.Allowed allowedPoses() {
        long low = 0, high = 0;
        IAlignment alignment = plan.controller instanceof IAlignment ? (IAlignment) plan.controller : null;
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            if (!plan.controller.isFacingValid(direction)) continue;
            for (ExtendedFacing pose : ExtendedFacing.FOR_FACING.get(direction)) {
                if (alignment == null ? pose != ExtendedFacing.of(direction) : !alignment.isNewExtendedFacingValid(pose)) continue;
                int index = pose.getIndex();
                if (index < 64) low |= 1L << index;
                else high |= 1L << (index - 64);
            }
        }
        return new PreviewPose.Allowed(low, high);
    }

    boolean movePreview(World world, int px, int py, int pz, ExtendedFacing next) {
        if (!movable() || plan == null || !plan.ready || world == null || py < 0 || py >= world.getHeight()
                || Math.abs((long)px) >= 30000000 || Math.abs((long)pz) >= 30000000 || !world.blockExists(px,py,pz)) return false;
        boolean sameAnchor = x == px && y == py && z == pz;
        // A real host at the source stays anchored. Dropping onto that same host only aligns the projection.
        if (!sameAnchor && (!world.blockExists(x, y, z) || targetHostFacing(world, x, y, z) != null)) return false;
        boolean attach = !world.isAirBlock(px, py, pz);
        if (attach) {
            next = targetHostFacing(world, px, py, pz);
            if (next == null) return false;
        } else if (next == null || next != facing && !allowedPoses().reachable(facing, next)) return false;
        if (sameAnchor && !attach && next == facing) return true;
        applyPreviewMove(px, py, pz, next, attach);
        return true;
    }

    private void applyPreviewMove(int px, int py, int pz, ExtendedFacing next, boolean attach) {
        if (next != facing) {
            PreviewPose transform=new PreviewPose(facing,next);
            materialCells=transform.rotateSelections(materialCells,true);
            materialGroups=transform.rotateSelections(materialGroups,false);
            materialSelections=transform.rotateSelections(materialSelections,false);
        }
        x=px; y=py; z=pz; facing=next;
        if (attach) existing = temporaryHost = true;
        pending.clear(); manualHatches.clear();
        needed.rows.clear(); shortage.rows.clear(); errors.rows.clear();
        cursor=total=placed=0; siteRevision=-1; detail="";
        resetPlan();
    }

    boolean previewMarkerAt(int px, int py, int pz) {
        if (issue != null && issue.posX == px && issue.posY == py && issue.posZ == pz && repairableIssue()) return true;
        if ((previewLayers & (PreviewStream.HATCH | PreviewStream.SHELL)) == 0 || target == null || deleteRequested) return false;
        boolean markers = (previewLayers & PreviewStream.HATCH) != 0;
        boolean host = x == px && y == py && z == pz;
        if (markers && host) return true;
        if (plan == null || !plan.ready) return false;
        Blueprint.Cell cell = plan.cellAt(px, py, pz);
        if (cell == null || (!host && !cell.hatch)) return false;
        if (markers && state == State.PREVIEW) return true;
        if (initiallyConfirmed(plan.indexAt(px, py, pz))) return false;
        MaterialSelection choice = !markers && cell.air ? selection(px, py, pz) : null;
        return markers || !cell.air || choice != null && choice.supported;
    }

    boolean upgrading() { return !upgradeChannels.isEmpty(); }

    boolean structureAt(World world, int px, int py, int pz) {
        if (deleteRequested || target == null) return false;
        if (previewMarkerAt(px, py, pz)) return true;
        if (px == x && py == y && pz == z) return !world.isAirBlock(px, py, pz);
        if (plan == null || !plan.ready) {
            if (py < 0 || py >= 256) return false;
            if (structureCells == null) return ledger.matches(world, px, py, pz);
            BitSet section = structureCells.get(PlacementLedger.key(px, py, pz));
            return section != null && section.get(Blueprint.slot(px, py, pz)) && !world.isAirBlock(px, py, pz);
        }
        Blueprint.Cell cell = plan.cellAt(px, py, pz);
        if (cell == null || cell.air && !cell.hatch && cell.choice == null) return false;
        if (!world.isAirBlock(px, py, pz)) return true;
        if ((previewLayers & PreviewStream.SHELL) == 0 || initiallyConfirmed(plan.indexAt(px, py, pz))) return false;
        if (!cell.air) return true;
        MaterialSelection choice = selection(px, py, pz);
        return choice != null && choice.supported;
    }

    private void captureStructureCells() {
        Map<Long, BitSet> snapshot = new HashMap<>();
        for (Blueprint.Section section : plan.sections) {
            // Ledger keys reserve four bits for the section's world height.
            if (section.y < 0 || section.y >= 16) continue;
            BitSet cells = section.structureCells();
            if (!cells.isEmpty()) snapshot.put(PlacementLedger.key(section.x << 4, section.y << 4, section.z << 4), cells);
        }
        if (!snapshot.equals(structureCells)) {
            structureCells = snapshot;
            changed();
        }
    }

    private NBTTagList saveStructureCells() {
        NBTTagList data = new NBTTagList();
        for (Map.Entry<Long, BitSet> section : structureCells.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("section", section.getKey());
            tag.setByteArray("cells", section.getValue().toByteArray());
            data.appendTag(tag);
        }
        return data;
    }

    private void loadStructureCells(NBTTagList data) {
        structureCells = new HashMap<>();
        for (int i = 0; i < data.tagCount(); i++) {
            NBTTagCompound tag = data.getCompoundTagAt(i);
            byte[] bits = tag.getByteArray("cells");
            if (!tag.hasKey("section", 4) || bits.length == 0 || bits.length > 512) continue;
            structureCells.put(tag.getLong("section"), BitSet.valueOf(bits));
        }
    }

    boolean canChangeCell(ChunkCoordinates p) {
        if (p == null || target == null || deleteRequested || dismantleRequested || upgrading()
                || !(editable() || state == State.BLOCKED) || savedLink != null) return false;
        if (p.posX == x && p.posY == y && p.posZ == z) return true;
        Blueprint.Cell cell = plan == null || !plan.ready ? null : plan.cellAt(p.posX, p.posY, p.posZ);
        return cell != null && cell.hatch;
    }

    boolean repairableIssue() {
        return state == State.BLOCKED && canChangeCell(issue) && (issue.posX != x || issue.posY != y || issue.posZ != z);
    }

    void singleCellChanged(World world, ChunkCoordinates p, boolean placedBlock) {
        if (placedBlock) {
            if (p.posX != x || p.posY != y || p.posZ != z) manualHatches.add(new ChunkCoordinates(p));
            else if (!existing && anchorValid(world)) existing = temporaryHost = true;
            ledger.record(world, p.posX, p.posY, p.posZ);
        } else {
            manualHatches.remove(p);
            ledger.forget(p.posX, p.posY, p.posZ);
        }
        if (plan != null && plan.ready && confirmed != null) {
            int index = plan.indexAt(p.posX, p.posY, p.posZ);
            if (index >= 0) confirm(index, false);
        }
        // A write proves ownership. Only the ordinary scan can certify that the part fits.
        siteRevision = -1;
        manualEditGeneration++;
        invalidateOrdinaryProgress();
        if (state == State.MISSING) {
            needed.rows.clear(); shortage.rows.clear(); errors.rows.clear();
            state = previewLayers == 0 ? State.DRAFT : State.PREVIEW;
        }
        changed();
    }

    void depositManualDrops(EntityPlayerMP player, WorldServer world) {
        Wireless net = Wireless.find(this, world, player);
        if (net != null) deposit(net, salvage, false);
    }

    /** Channel-page permission only; review must never enable the world/material editor. */
    boolean canConfigureChannels() {
        return !hasReserved() && savedLink == null && !ordering()
                && (state == State.DRAFT || state == State.PREVIEW || state == State.MISSING
                    || state == State.UPGRADE_REVIEW || state == State.BLOCKED && upgrading() && !upgradeApproved);
    }

    boolean usesConfiguredSource() {
        return useSurvivalConstruct == RebuildConfig.useSurvivalConstruct;
    }

    boolean keepsUpgradeBinding() { return !upgradeNetwork.isEmpty(); }

    private void releaseUpgradePhaseData() {
        upgradeReviewSources=new UpgradeSnapshot(); upgradeScanSources=new UpgradeSnapshot();
        upgradeCommitted=new BitSet(); upgradeScan.clear(); upgradeReview.releaseIndex();
    }

    boolean attachExisting(WorldServer world) {
        if (!canConfigureChannels() || target == null || world == null || world.provider.dimensionId != dim
                || !world.blockExists(x,y,z)) return false;
        ExtendedFacing actual = targetHostFacing(world, x, y, z);
        if (actual == null) return false;
        if (actual != facing) {
            if (existing || ledger.count != 0 || deleteRequested || dismantleRequested || upgrading()
                    || state != State.DRAFT && state != State.PREVIEW) return false;
            applyPreviewMove(x, y, z, actual, true);
        } else if (!existing) { existing = temporaryHost = true; changed(); }
        return true;
    }

    void releaseMissingTemporaryHost(World world) {
        if (temporaryHost && editable() && world.blockExists(x, y, z) && world.isAirBlock(x, y, z)) {
            existing = temporaryHost = false;
            ledger.forget(x, y, z);
            changed();
        }
    }

    /** Call before writing new tier/channel values, while the old ready plan can validate the selected scope. */
    boolean setUpgradeChannels(Collection<String> enabled) {
        if (!canConfigureChannels() || enabled == null) return false;
        Set<String> selected = new TreeSet<>();
        for (String channel : enabled) {
            if (channel == null || channel.trim().isEmpty()) return false;
            selected.add(channel);
        }
        if (selected.size()>256) return false;
        if (!selected.isEmpty() && (!existing || plan == null || !plan.ready
                || !plan.upgradeChannels().containsAll(selected))) return false;
        upgradeChannels.clear(); upgradeChannels.addAll(selected);
        useSurvivalConstruct = RebuildConfig.useSurvivalConstruct;
        upgradeReview.clear(); releaseUpgradePhaseData();
        upgradeCount = 0; upgradeApproved = false; upgradeNetwork = "";
        needed.rows.clear(); shortage.rows.clear(); errors.rows.clear(); funding.clear();
        pending.clear(); cursor = total = placed = 0;
        auto = false;
        resetPlan();
        state = selected.isEmpty() ? (previewLayers == 0 ? State.DRAFT : State.PREVIEW) : State.INSPECTING_UPGRADE;
        changed();
        return true;
    }

    private boolean upgradeAnchor(WorldServer world) {
        if (!requireChunk(world,x,y,z)) return false;
        if (!existing || !anchorValid(world)) { stop("upgradeAnchor",x,y,z); return false; }
        return true;
    }

    private void restartReplacementInspection() {
        upgradeReview.clear();
        upgradeReviewSources.clear();
        upgradeCount = cursor = total = placed = 0;
        reason = detail = "";
        issue = null;
        state = State.INSPECTING_UPGRADE;
        revision++;
        changed();
    }

    void inspectUpgrade(EntityPlayerMP actor, WorldServer world, long deadline) {
        if (state != State.INSPECTING_UPGRADE || plan == null || !plan.ready || !upgradeAnchor(world)) return;
        if (!plan.upgradeChannels().containsAll(upgradeChannels)) { stop("upgradeScope",x,y,z); return; }
        for (int n = 0; cursor < plan.size && n < 8192 && System.nanoTime() < deadline; n++,cursor++) {
            Blueprint.Cell cell = plan.at(cursor,xyz);
            if (!upgradeChannels.contains(plan.upgradeChannel(cell))) continue;
            int px=xyz[0],py=xyz[1],pz=xyz[2];
            if (!requireChunk(world,px,py,pz)) return;
            ChannelUpgrade.Result result = plan.upgrade(cell,world,px,py,pz,upgradeChannels);
            if (result.kind == ChannelUpgrade.Kind.BLOCKED) { stop("obstruction",px,py,pz); return; }
            if (result.kind != ChannelUpgrade.Kind.REPLACE) continue;
            if (!UpgradePlacement.supports(result)) { stop("upgradePlacement",px,py,pz); return; }
            if (!upgradeReview.add(result)) { stop("upgradeRows",px,py,pz); return; }
            if (!upgradeReviewSources.add(px,py,pz,result)) { stop("upgradeRows",px,py,pz); return; }
            upgradeCount = upgradeReview.count;
        }
        changed();
        if (cursor == plan.size) {
            cursor = 0; total = upgradeCount; placed = 0;
            reason = upgradeCount == 0 ? "upgradeEmpty" : "";
            state = State.UPGRADE_REVIEW;
            upgradeReview.releaseIndex();
            revision++;
            changed();
        }
    }

    boolean startUpgrade() {
        if (!upgrading() || state != State.UPGRADE_REVIEW || upgradeCount == 0) return false;
        upgradeApproved = true;
        beginUpgradeScan();
        return true;
    }

    /** An unexecuted review returns to ordinary mode only after every real reserved item is recovered. */
    boolean continueOrdinaryBuild(EntityPlayerMP actor, WorldServer world) {
        if (!upgrading() || state!=State.UPGRADE_REVIEW || upgradeApproved
                || deleteRequested || dismantleRequested) return false;
        if (!hasReserved() && savedLink==null) return setUpgradeChannels(Collections.emptySet());
        upgradeReturnToBuild=true;
        upgradeReturnLayers=previewLayers;
        auto=false;
        cancelForRefund(actor,world);
        return true;
    }

    private void resumeOrdinaryAfterUpgradeRefund() {
        if (!upgradeReturnToBuild || hasReserved() || savedLink!=null || deleteRequested || dismantleRequested) return;
        upgradeReturnToBuild=false;
        upgradeChannels.clear(); upgradeReview.clear(); releaseUpgradePhaseData();
        upgradeCount=0; upgradeApproved=false; upgradeNetwork="";
        cursor=total=placed=0; pending.clear();
        needed.rows.clear(); shortage.rows.clear(); errors.rows.clear(); funding.clear();
        previewLayers=upgradeReturnLayers;
        state=previewLayers==0 ? State.DRAFT : State.PREVIEW;
        resetPlan();
        changed();
    }

    private void beginUpgradeScan() {
        upgradeScan.clear();
        if (upgradeScanSources==upgradeReviewSources) upgradeScanSources=new UpgradeSnapshot(); else upgradeScanSources.clear();
        needed.rows.clear(); shortage.rows.clear(); errors.rows.clear();
        pending.clear(); cursor = total = placed = 0;
        reason = detail = ""; issue = null;
        state = State.SCANNING;
        revision++;
        changed();
    }

    boolean configureTarget(ItemStack stack) {
        if (!Materials.equal(target, stack)) {
            if (ledger.count > 0) return false;
            manualHatches.clear();
            materialSelections = new NBTTagCompound();
            materialCells = new NBTTagCompound();
            materialGroups = new NBTTagCompound();
        }
        target = ItemStack.copyItemStack(stack);
        if (target == null) previewLayers = 0;
        else target.stackSize = 1;
        observedChannels.clear();
        store.channelMemory.restore(this);
        resetDraft();
        return true;
    }

    void resetDraft() {
        useSurvivalConstruct = RebuildConfig.useSurvivalConstruct;
        state = previewLayers == 0 ? State.DRAFT : State.PREVIEW;
        resetPlan();
    }

    void togglePreview(int layer) {
        if (!canChangePreview()) return;
        previewLayers ^= layer;
        if (state == State.DRAFT || state == State.PREVIEW)
            state = previewLayers == 0 ? State.DRAFT : State.PREVIEW;
        changed();
    }

    private boolean canChangePreview() {
        return target != null && state != State.DONE && state != State.REFUND && !dismantleRequested
                && state != State.DISMANTLED && state != State.DISMANTLING && state != State.TEARDOWN_PAUSED;
    }

    boolean useTexturedWorldPreview() {
        return texturedPreview && (previewLayers & PreviewStream.SHELL) != 0
                && state != State.QUEUED && state != State.BUILDING && state != State.VERIFYING;
    }

    void setTexturedPreview(boolean textured) {
        if (!canChangePreview() || textured == texturedPreview) return;
        texturedPreview = textured;
        changed();
    }

    List<IChannelDefinition> definitions() {
        if (Blueprint.prototype(target) == null) return Collections.emptyList();
        IStructureDefinition<?> def =
                ((IConstructable) Blueprint.prototype(target)).getStructureDefinition();
        List<IChannelDefinition> list = new ArrayList<>();
        if (def != null) list.addAll(def.getChannelDefinitions());
        Set<String> extra = new HashSet<>(observedChannels);
        for (IChannelDefinition d : list) extra.remove(d.getChannel());
        for (String id : extra) list.add(IChannelDefinition.unbounded(id));
        // This presence-only channel is built into the wireless blueprint.
        list.removeIf(d -> "chisel".equals(d.getChannel()));
        list.sort(Comparator.comparing(IChannelDefinition::getChannel));
        return list;
    }

    void prepare(EntityPlayerMP actor, long deadline) {
        if (target == null || "unsupported".equals(reason)) return;
        if (plan != null && plan.ready) return;
        int known = observedChannels.size();
        ChannelProbe.begin(observedChannels);
        try {
            if (plan == null) {
                ItemStack trigger = new ItemStack(StructureLibAPI.getDefaultHologramItem(), tier);
                for (Object name :
                        com.gtnewhorizon.structurelib.util.MiscUtils.getTagKeys(channels)) {
                    String key = (String) name;
                    int value = channels.getInteger(key);
                    if (value > 0) ChannelDataAccessor.setChannelData(trigger, key, value);
                }
                if (!channels.hasKey("chisel"))
                    ChannelDataAccessor.setChannelData(trigger, "chisel", 1);
                plan = Blueprint.read(target, trigger, facing, x, y, z, actor, this::initiallyConfirmed,
                        useSurvivalConstruct);
            }
            if (!plan.ready) plan.advance(deadline);
            if (plan.ready) {
                ensureSelections();
                captureStructureCells();
            }
            if (plan.ready && confirmed == null) {
                confirmed = new BitSet(plan.size);
                confirmed.set(0, plan.size);
                confirmed.andNot(pending);
                changed();
            }
        } finally {
            ChannelProbe.end();
        }
        if (!pendingDefaults.hasNoTags() && known != observedChannels.size()
                && ChannelMemory.applyKnown(this)) {
            resetPlan();
        } else if (plan.ready && !pendingDefaults.hasNoTags()) {
            pendingDefaults = new NBTTagCompound();
            changed();
        }
    }

    private void notify(EntityPlayerMP actor, String key) {
        actor.addChatMessage(
                new ChatComponentTranslation(
                        "structurebuildernh.chat." + key,
                        target == null ? new ChatComponentTranslation("item.sbnh.builder.name") : target.getDisplayName(),
                        x,
                        y,
                        z));
    }

    void start() {
        start(false);
    }

    void recheckMaterials() {
        if (state == State.MISSING) start(true);
    }

    private void start(boolean checkOnly) {
        if (state == State.SCANNING) return;
        checkMaterialsOnly = checkOnly;
        if (upgrading()) {
            if (state == State.UPGRADE_REVIEW) { startUpgrade(); return; }
            if (state == State.BLOCKED && !upgradeApproved) {
                restartReplacementInspection();
                return;
            }
            if (!upgradeApproved) return;
            if (state == State.BLOCKED || state == State.MISSING) { beginUpgradeScan(); return; }
        }
        if (state == State.READY) {
            invalidateOrdinaryProgress();
            state = State.QUEUED;
            changed();
            return;
        }
        if (state != State.DRAFT && state != State.PREVIEW && state != State.MISSING && state != State.BLOCKED) return;
        if (target == null) return;
        if (confirmed == null) resetPlan();
        invalidateOrdinaryProgress();
        total = placed = 0;
        issue = null;
        state = State.SCANNING;
        cursor = 0;
        pending.clear();
        needed.rows.clear();
        errors.rows.clear();
        shortage.rows.clear();
        reason = "";
        detail = "";
        revision++;
        changed();
    }

    private void stop(String why, int px, int py, int pz) {
        for (Object online :
                net.minecraft.server.MinecraftServer.getServer()
                        .getConfigurationManager()
                        .playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) online;
            if (owner.equals(p.getUniqueID().toString()))
                p.addChatMessage(
                        new ChatComponentTranslation(
                                "structurebuildernh.chat.blocked",
                                target == null ? new ChatComponentTranslation("item.sbnh.builder.name") : target.getDisplayName(),
                                px,
                                py,
                                pz,
                                new ChatComponentTranslation("structurebuildernh." + why)));
        }
        pointAt(px, py, pz);
        reason = why;
        detail = px + ", " + py + ", " + pz;
        state = State.BLOCKED;
        changed();
    }

    private boolean anchorValid(World world) {
        return targetHostFacing(world, x, y, z) == facing;
    }

    private ExtendedFacing targetHostFacing(World world, int px, int py, int pz) {
        TileEntity te = world.getTileEntity(px, py, pz);
        if (!(te instanceof IGregTechTileEntity)) return null;
        IGregTechTileEntity gt = (IGregTechTileEntity) te;
        if (gt.getMetaTileEntity() == null || gt.getMetaTileID() != target.getMetadata()) return null;
        ForgeDirection front = gt.getFrontFacing();
        if (front == null || front == ForgeDirection.UNKNOWN) return null;
        ExtendedFacing pose = gt.getMetaTileEntity() instanceof IAlignment
                ? ((IAlignment) gt.getMetaTileEntity()).getExtendedFacing() : ExtendedFacing.of(front);
        return pose != null && pose.getDirection() == front ? pose : null;
    }

    private boolean requireChunk(WorldServer world, int px, int py, int pz) {
        if (py < 0 || py >= world.getHeight() || px < -30000000 || px >= 30000000
                || pz < -30000000 || pz >= 30000000) {
            boolean teardown = state == State.DISMANTLING;
            stop(py < 0 || py >= world.getHeight() ? "height" : "outsideWorld", px, py, pz);
            if (teardown) state = State.TEARDOWN_PAUSED;
            return false;
        }
        if (!world.blockExists(px, py, pz) && !workChunks.load(this, world, px >> 4, pz >> 4)) {
            reason = "unloaded";
            pointAt(px, py, pz);
            return false;
        }
        if ("unloaded".equals(reason) && issue != null
                && (issue.posX >> 4) == (px >> 4) && (issue.posZ >> 4) == (pz >> 4)) {
            reason = "";
            issue = null;
            changed();
        }
        return true;
    }

    void scan(EntityPlayerMP actor, WorldServer world, long deadline) {
        if (upgrading()) { scanUpgrade(actor,world,deadline); return; }
        if (plan == null || !plan.ready) return;
        if (!prepareOrdinaryProgress(world, deadline)) return;
        for (int n = 0;
                cursor < plan.size && n < 8192 && System.nanoTime() < deadline;
                n++, cursor++) {
            plan.at(cursor, xyz);
            int px = xyz[0], py = xyz[1], pz = xyz[2];
            if (!requireChunk(world, px, py, pz)) return;
            ordinaryCell(cursor, world, ordinaryWork, false);
            updateOrdinaryProgress(cursor, ordinaryWork);
            confirm(cursor, ordinaryWork.status == OrdinaryStatus.INSTALLED);
            if (ordinaryWork.matches) {
                continue;
            }
            if (cursor == 0
                    && (existing || !plan.controller.isFacingValid(facing.getDirection()))) {
                stop("anchor", px, py, pz);
                return;
            }
            ItemStack material = ordinaryWork.material;
            if (ordinaryWork.status != OrdinaryStatus.MISSING) {
                stop("obstruction", px, py, pz);
                return;
            }
            if (!ordinaryWork.blankRitual) needed.add(material, 1);
            pending.set(cursor);
        }
        changed();
        if (cursor < plan.size) return;
        if (pending.isEmpty() && !checkMaterialsOnly) {
            finish(actor, world);
            return;
        }
        if (pending.isEmpty() || actor.capabilities.isCreativeMode) {
            cursor = 0;
            reason = "";
            materialsAvailable();
            return;
        }
        Wireless net = Wireless.find(this, world, actor);
        cursor = 0;
        reason = "";
        quote(actor, net, false);
        if (shortage.rows.isEmpty()) {
            quote(actor, net, true);
            if (shortage.rows.isEmpty()) {
                materialsAvailable();
                return;
            }
        }
        state = State.MISSING;
        revision++;
        notify(actor, "missing");
    }

    boolean hasReserved() {
        return !backpack.rows.isEmpty() || !held.rows.isEmpty() || !salvage.rows.isEmpty()
                || upgradeRecovery != null || upgradeRecoveryFault;
    }

    private void materialsAvailable() {
        state = checkMaterialsOnly ? State.READY : State.QUEUED;
        checkMaterialsOnly = false;
        changed();
    }

    private void scanUpgrade(EntityPlayerMP actor, WorldServer world, long deadline) {
        if (plan == null || !plan.ready || !upgradeApproved || !recoverUpgrade(world) || !upgradeAnchor(world)) return;
        if (!plan.upgradeChannels().containsAll(upgradeChannels)) { stop("upgradeScope",x,y,z); return; }
        for (int n=0; cursor<plan.size && n<8192 && System.nanoTime()<deadline; n++,cursor++) {
            Blueprint.Cell cell=plan.at(cursor,xyz);
            if (!upgradeChannels.contains(plan.upgradeChannel(cell))) continue;
            int px=xyz[0],py=xyz[1],pz=xyz[2];
            if (!requireChunk(world,px,py,pz)) return;
            ChannelUpgrade.Result result=plan.upgrade(cell,world,px,py,pz,upgradeChannels);
            if (result.kind==ChannelUpgrade.Kind.BLOCKED) { stop("obstruction",px,py,pz); return; }
            if (result.kind!=ChannelUpgrade.Kind.REPLACE) continue;
            if (!UpgradePlacement.supports(result)) { stop("upgradePlacement",px,py,pz); return; }
            if (!upgradeScan.add(result)) { stop("upgradeRows",px,py,pz); return; }
            if (!upgradeScanSources.add(px,py,pz,result)) { stop("upgradeRows",px,py,pz); return; }
            needed.add(result.target,1);
            pending.set(cursor);
            total++;
        }
        changed();
        if (cursor<plan.size) return;
        cursor=0;
        // A changed live bill is reviewable before any new extraction, even when resuming a partial run.
        if (!upgradeScan.same(upgradeReview) || !upgradeScanSources.same(upgradeReviewSources) || upgradeScan.count==0) {
            upgradeReview.copyFrom(upgradeScan); upgradeCount=upgradeReview.count;
            upgradeReviewSources=upgradeScanSources;
            upgradeScan.clear();
            upgradeReview.releaseIndex();
            upgradeApproved=false;
            state=State.UPGRADE_REVIEW;
            reason=upgradeCount==0 ? "upgradeEmpty" : "upgradeChanged";
            revision++; changed(); return;
        }
        // The approved scan is identical to the review: keep one coordinate mask, not two.
        upgradeReviewSources=upgradeScanSources;
        upgradeScan.clear();
        if (actor.capabilities.isCreativeMode) {
            reason=""; materialsAvailable(); return;
        }
        Wireless net=Wireless.find(this,world,actor);
        String status=upgradeDepositProblem(net);
        if (!status.isEmpty()) { reason=status; state=State.MISSING; changed(); return; }
        if (upgradeNetwork.isEmpty()) { upgradeNetwork=key; changed(); }
        quote(actor,net,false);
        if (shortage.rows.isEmpty()) {
            quote(actor,net,true);
            if (shortage.rows.isEmpty()) { reason=""; materialsAvailable(); return; }
        }
        state=State.MISSING;
        revision++; changed(); notify(actor,"missing");
    }

    private String upgradeDepositProblem(Wireless net) {
        if (!upgradeNetwork.isEmpty() && !upgradeNetwork.equals(key)) return "upgradeNetwork";
        if (key.isEmpty()) return "upgradeUnbound";
        return depositProblem(net,true);
    }

    private boolean recoverUpgrade(WorldServer world) {
        if (upgradeRecoveryFault) {
            reason="upgradeRollback"; if (state!=State.REFUND) state=State.BLOCKED; changed(); return false;
        }
        if (upgradeRecovery==null) return true;
        UpgradePlacement.Recovery cell=upgradeRecovery;
        if (!requireChunk(world,cell.x,cell.y,cell.z)) return false;
        if (!UpgradePlacement.recover(cell,world)) {
            pointAt(cell.x,cell.y,cell.z); reason="upgradeRollback";
            if (state!=State.REFUND) state=State.BLOCKED;
            changed(); return false;
        }
        ledger.restoreOwnership(world,cell.x,cell.y,cell.z,cell.owned);
        upgradeRecovery=null;
        changed();
        return true;
    }

    int reserved(ItemStack item) {
        return funding.count(item);
    }

    private void quote(EntityPlayerMP actor, Wireless net, boolean reserve) {
        new MaterialQuote(this, actor, net, reserve).run();
    }

    void submit(EntityPlayerMP actor, WorldServer world) {
        if (state != State.MISSING) return;
        Wireless net = Wireless.find(this, world, actor);
        if (upgrading()) {
            String status=upgradeDepositProblem(net);
            if (!status.isEmpty()) { reason=status; changed(); return; }
            if (upgradeNetwork.isEmpty()) { upgradeNetwork=key; changed(); }
        }
        if (net == null || !net.allows(SecurityPermissions.EXTRACT)) {
            reason = "permission";
            changed();
            return;
        }
        quote(actor, net, false);
        if (!shortage.rows.isEmpty() && !net.allows(SecurityPermissions.CRAFT)) {
            reason = "permission";
            changed();
            return;
        }
        if (!shortage.rows.isEmpty()
                && net.security.isAvailable()
                && !net.allows(SecurityPermissions.BUILD)) {
            reason = "nodePermission";
            changed();
            return;
        }
        errors.rows.clear();
        detail = "";
        reason = "";
        quote(actor, net, true);
        revision++;
        if (shortage.rows.isEmpty()) {
            state = auto ? State.QUEUED : State.READY;
            notify(actor, "ready");
            return;
        }
        orderItems.rows.clear();
        orderFunding.clear();
        ChiselMaterials chisel = new ChiselMaterials();
        for (Materials.Entry e : shortage.rows.values()) {
            ItemStack input = e.item;
            if (!net.canCraft(input)) {
                for (ItemStack variant : chisel.inputs(e.item)) {
                    if (net.canCraft(variant)) {
                        input = variant;
                        break;
                    }
                }
            }
            orderItems.add(input, e.count);
            orderFunding.add(e.item, input, e.count);
        }
        orderId = UUID.randomUUID().toString();
        received = false;
        state = State.PLANNING;
        changed();
    }

    boolean ordering() {
        return state == State.PLANNING || state == State.CPU || state == State.ORDERED;
    }

    void tickOrder(WorldServer world, EntityPlayerMP actor, boolean online) throws Exception {
        if (!upgradeNetwork.isEmpty() && !upgradeNetwork.equals(key)) {
            reason="upgradeNetwork"; detach(); changed(); return;
        }
        Wireless net = Wireless.find(this, world, actor);
        if (net == null) {
            reason = "network";
            detach();
            return;
        }
        if (obsoleteFluidOrder && ordering()) {
            // A persisted AE request owns its original pattern. Cancel the original link before
            // requesting a corrected bill; stock which already arrived stays in refundable escrow.
            if (savedLink != null) {
                if (order == null) order = new MaterialOrder(this, net, world);
                if (order.link != null) order.link.cancel();
            }
            clearOrder();
            state = State.MISSING;
            reason = "missing";
            if (online) submit(actor, world);
            return;
        }
        if (order != null
                && (order.access != net.node
                        || order.getActionableNode() == null
                        || order.getActionableNode().getGrid() != net.grid)) detach();
        if (order == null) {
            if (net.security.isAvailable() && !net.allows(SecurityPermissions.BUILD)) {
                reason = "nodePermission";
                return;
            }
            order = new MaterialOrder(this, net, world);
            try {
                order.attach();
            } catch (Exception e) {
                detach();
                reason = "network";
                return;
            }
        }
        reason = "";
        if (state == State.REFUND || state == State.DONE) {
            if (order.link != null) order.link.cancel();
            clearOrder();
        } else order.tick(net, online);
    }

    void detach() {
        if (order != null) {
            order.saveLink();
            order.close();
            order = null;
        }
    }

    void clearOrder() {
        obsoleteFluidOrder = false;
        detach();
        savedLink = null;
        orderId = "";
        orderFunding.clear();
        orderItems.rows.clear();
        received = false;
        revision++;
        changed();
    }

    void orderFailed(Wireless net) {
        clearOrder();
        refund(net);
        state = State.MISSING;
        reason = "aeMissing";
        quote(
                net.source.player instanceof EntityPlayerMP
                        ? (EntityPlayerMP) net.source.player
                        : null,
                net,
                false);
    }

    void orderEnded(boolean success) {
        boolean stocked = received;
        clearOrder();
        if (stocked) state = auto ? State.QUEUED : State.READY;
        else {
            state = State.MISSING;
            reason = success ? "missing" : "cancelled";
        }
        changed();
    }

    private void refund(Wireless net) {
        if (!upgradeNetwork.isEmpty() && !upgradeNetwork.equals(key)) { reason="upgradeNetwork"; changed(); return; }
        if (net != null) deposit(net, held);
    }

    private void deposit(Wireless net, Materials from) {
        deposit(net, from, true);
    }

    private void deposit(Wireless net, Materials from, boolean updateRevision) {
        for (Materials.Entry e : new ArrayList<>(from.rows.values())) {
            int accepted = net.refund(e);
            if (accepted > 0) {
                from.add(e.item, -accepted);
                if (updateRevision) revision++;
                changed();
            }
        }
    }

    void cancel(EntityPlayerMP actor, WorldServer world) {
        if (state!=State.REFUND || deleteRequested || dismantleRequested) upgradeReturnToBuild=false;
        cancelForRefund(actor,world);
    }

    private void cancelForRefund(EntityPlayerMP actor, WorldServer world) {
        if (state != State.REFUND) {
            state = State.REFUND;
            previewLayers = 0;
            funding.clear();
            discardCachedPlan();
            reason = "";
            issue = null;
            detail = "";
            // Preserve a detached link until its CPU can see the cancellation.
            if (order != null) order.saveLink();
            if (savedLink == null) clearOrder();
            releaseUpgradePhaseData();
            changed();
        }
        if (world != null && actor.worldObj == world) retryRefund(actor, world);
    }

    void retryRefund(EntityPlayerMP actor, WorldServer world) {
        if (!recoverUpgrade(world)) return;
        returnBackpack(actor);
        if (!upgradeNetwork.isEmpty() && !upgradeNetwork.equals(key)) {
            reason="upgradeNetwork"; changed(); return;
        }
        if (savedLink != null) {
            try {
                tickOrder(world, actor, false);
            } catch (Exception e) {
                detach();
                reason = "reconnect";
            }
            if (savedLink != null) return;
        }
        String status = "";
        if (!held.rows.isEmpty() || !salvage.rows.isEmpty()) {
            Wireless net = Wireless.find(this, world, actor);
            status = depositProblem(net, !salvage.rows.isEmpty());
            if (status.equals("network")) status = "refundNetwork";
            if (status.isEmpty()) {
                deposit(net, held);
                deposit(net, salvage);
                if (!held.rows.isEmpty() || !salvage.rows.isEmpty()) status = "refundSpace";
            }
        }
        if (status.isEmpty() && !backpack.rows.isEmpty()) status = "refundBagSpace";
        if (!reason.equals(status)) {
            reason = status;
            changed();
        }
        if (!hasReserved() && savedLink==null) { upgradeNetwork=""; changed(); }
        if (!hasReserved() && savedLink==null) releaseUpgradePhaseData();
        resumeOrdinaryAfterUpgradeRefund();
    }

    private static int giveToInventory(EntityPlayerMP actor, ItemStack type, int count) {
        // Vanilla addItemStackToInventory discards overflow in creative mode.
        int remaining = count;
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int i = 0; i < actor.inventory.mainInventory.length && remaining > 0; i++) {
                ItemStack slot = actor.inventory.mainInventory[i];
                if (pass == 0 ? slot == null || !Materials.equal(slot, type) : slot != null)
                    continue;
                int limit =
                        Math.min(type.getMaxStackSize(), actor.inventory.getInventoryStackLimit());
                int moved = Math.min(remaining, limit - (slot == null ? 0 : slot.stackSize));
                if (moved <= 0) continue;
                if (slot == null) {
                    slot = type.copy();
                    slot.stackSize = moved;
                    actor.inventory.setInventorySlotContents(i, slot);
                } else slot.stackSize += moved;
                remaining -= moved;
            }
        }
        if (count != remaining) actor.inventory.markDirty();
        return count - remaining;
    }

    private void returnBackpack(EntityPlayerMP actor) {
        for (Materials.Entry e : new ArrayList<>(backpack.rows.values())) {
            int moved = giveToInventory(actor, e.item, e.count);
            if (moved > 0) {
                backpack.add(e.item, -moved);
                revision++;
                changed();
            }
        }
    }

    void requestDismantle(EntityPlayerMP actor, WorldServer world) {
        if (ledger.count == 0 || state == State.DISMANTLING) return;
        deleteRequested = false;
        dismantleRequested = true;
        if (state != State.REFUND) cancel(actor, world);
        beginDismantle();
        changed();
    }

    void beginDismantle() {
        if (!dismantleRequested || state != State.REFUND || hasReserved() || savedLink != null)
            return;
        state = State.DISMANTLING;
        total = ledger.count;
        placed = 0;
        reason = detail = "";
        issue = null;
        changed();
    }

    int dismantle(EntityPlayerMP actor, WorldServer world, ItemStack blueprint, int budget, long deadline) {
        int used = 0;
        if (world.getTotalWorldTime() < nextSalvageRetry) return 0;
        if (key.isEmpty()) key = BuilderTasks.tag(blueprint).getString("network");
        // A wireless access point at the build site can itself be in the unloaded controller chunk.
        if (!key.isEmpty() && !requireChunk(world, x, y, z)) return 0;
        Wireless net = Wireless.find(this, world, actor);
        if ((!actor.capabilities.isCreativeMode || !salvage.rows.isEmpty()) && !returnSalvage(net, world)) return 0;
        while (ledger.count > 0 && used < budget && System.nanoTime() < deadline) {
            ChunkCoordinates p = ledger.next();
            int x = p.posX, y = p.posY, z = p.posZ;
            if (!requireChunk(world, x, y, z)) break;
            if (ledger.matches(world, x, y, z)) {
                double charge = TerminalEnergy.REMOVE.reserve(actor, blueprint);
                if (charge < 0) { reason = "noEnergy"; changed(); break; }
                boolean removed = false;
                try {
                    removed = ledger.harvest(actor, world, blueprint, x, y, z);
                } finally {
                    if (!removed) TerminalEnergy.refund(blueprint, charge);
                }
                if (!removed) {
                    stop("harvest", x, y, z);
                    state = State.TEARDOWN_PAUSED;
                    changed();
                    break;
                }
            }
            ledger.forget(x, y, z);
            used++;
            reason = "";
            placed = total - ledger.count;
            changed();
            if (!salvage.rows.isEmpty() && !returnSalvage(net, world)) break;
        }
        if (ledger.count == 0 && salvage.rows.isEmpty()) {
            placed = total;
            state = State.DISMANTLED;
            dismantleRequested = false;
            reason = detail = "";
            issue = null;
            releaseUpgradePhaseData();
            notify(actor, "dismantled");
            changed();
            store.completed(this, actor, world);
        }
        return used;
    }

    private String depositProblem(Wireless net, boolean requiresME) {
        if (key.isEmpty()) return requiresME ? "dismantleUnbound" : "refundUnbound";
        if (net == null || !net.node.isActive() || net.node.getGrid() != net.grid) return "network";
        return net.allows(SecurityPermissions.INJECT) ? "" : "refundPermission";
    }

    private boolean returnSalvage(Wireless net, WorldServer world) {
        if (!salvage.rows.isEmpty() && world.getTotalWorldTime()<nextSalvageRetry) return false;
        String status = upgrading() ? upgradeDepositProblem(net) : depositProblem(net, true);
        if (status.isEmpty() && !salvage.rows.isEmpty()) {
            deposit(net, salvage);
            if (!salvage.rows.isEmpty()) status = "refundSpace";
        }
        if (!status.equals(reason)) {
            reason = status;
            changed();
        }
        if (!status.isEmpty()) {
            if (!"network".equals(status)) workChunks.release();
            nextSalvageRetry = world.getTotalWorldTime() + 20;
        }
        return status.isEmpty();
    }

    void verify(EntityPlayerMP actor, WorldServer world, long deadline) {
        if (upgrading()) { verifyUpgrade(actor,world,deadline); return; }
        if (plan == null || !plan.ready) return;
        if (!prepareOrdinaryProgress(world, deadline)) return;
        for (int n = 0;
                cursor < plan.size && n < 8192 && System.nanoTime() < deadline;
                n++, cursor++) {
            plan.at(cursor, xyz);
            int px = xyz[0], py = xyz[1], pz = xyz[2];
            if (!requireChunk(world, px, py, pz)) return;
            ordinaryCell(cursor, world, ordinaryWork, false);
            updateOrdinaryProgress(cursor, ordinaryWork);
            confirm(cursor, ordinaryWork.status == OrdinaryStatus.INSTALLED);
            if (!ordinaryWork.matches) {
                stop("obstruction", px, py, pz);
                return;
            }
        }
        changed();
        if (cursor == plan.size) finish(actor, world);
    }

    void finish(EntityPlayerMP actor, WorldServer world) {
        if (!upgradeNetwork.isEmpty() && !upgradeNetwork.equals(key)) { reason="upgradeNetwork"; changed(); return; }
        if (upgrading() && !salvage.rows.isEmpty() && !returnSalvage(Wireless.find(this,world,actor),world)) return;
        funding.clear();
        confirmed = new BitSet();
        issue = null;
        state = State.DONE;
        previewLayers = 0;
        reason = "";
        retryRefund(actor, world);
        changed();
        notify(actor, upgrading() ? "upgraded" : "done");
        discardCachedPlan();
        if (upgrading()) releaseUpgradePhaseData();
        if (!hasReserved()) upgradeNetwork="";
        store.completed(this, actor, world);
    }

    int build(EntityPlayerMP actor, WorldServer world, ItemStack terminal, int budget, long deadline) {
        if (upgrading()) return buildUpgrade(actor,world,terminal,budget,deadline);
        if (plan == null || !plan.ready || budget <= 0) return 0;
        if (!prepareOrdinaryProgress(world, deadline)) return 0;
        boolean creative = actor.capabilities.isCreativeMode;
        if (!requireChunk(world, x, y, z)) return 0;
        if (cursor > 0 && !anchorValid(world)) {
            ordinaryCell(0, world, ordinaryWork, false);
            updateOrdinaryProgress(0, ordinaryWork);
            confirm(0, false);
            stop("anchor", x, y, z);
            return 0;
        }
        state = State.BUILDING;
        reason = "";
        int used = 0;
        for (int n = 0;
                cursor < plan.size && used < budget && n < 8192 && System.nanoTime() < deadline;
                n++, cursor++) {
            Blueprint.Cell c = plan.at(cursor, xyz);
            int px = xyz[0], py = xyz[1], pz = xyz[2];
            if (!requireChunk(world, px, py, pz)) break;
            ordinaryCell(cursor, world, ordinaryWork, false);
            updateOrdinaryProgress(cursor, ordinaryWork);
            confirm(cursor, ordinaryWork.status == OrdinaryStatus.INSTALLED);
            if (ordinaryWork.matches) {
                pending.clear(cursor);
                continue;
            }
            boolean blankRitual = ordinaryWork.blankRitual;
            ItemStack material = ordinaryWork.material;
            if (ordinaryWork.status != OrdinaryStatus.MISSING) {
                stop("obstruction", px, py, pz);
                break;
            }
            ItemStack input = creative || blankRitual ? null : funding.next(material);
            if (!creative
                    && !blankRitual
                    && (input == null || backpack.count(input) == 0 && held.count(input) == 0)) {
                stop("rescan", px, py, pz);
                break;
            }
            if (!world.checkNoEntityCollision(
                    AxisAlignedBB.getBoundingBox(px, py, pz, px + 1, py + 1, pz + 1))) {
                pointAt(px, py, pz);
                reason = "occupied";
                break;
            }
            if (!world.canMineBlock(actor, px, py, pz)
                    || !actor.canPlayerEdit(px, py, pz, 1, material)) {
                stop("denied", px, py, pz);
                break;
            }
            if (blankRitual) {
                net.minecraftforge.event.entity.player.PlayerInteractEvent interaction =
                        net.minecraftforge.event.ForgeEventFactory.onPlayerInteract(
                                actor,
                                net.minecraftforge.event.entity.player.PlayerInteractEvent.Action
                                        .RIGHT_CLICK_BLOCK,
                                px,
                                py,
                                pz,
                                1,
                                world);
                if (interaction.isCanceled()
                        || interaction.useBlock
                                == cpw.mods.fml.common.eventhandler.Event.Result.DENY) {
                    stop("denied", px, py, pz);
                    break;
                }
            }
            double charge = TerminalEnergy.PLACE.reserve(actor, terminal);
            if (charge < 0) { reason = "noEnergy"; break; }
            boolean placedBlock = false;
            try {
                placedBlock = placeCell(actor, world, c, material, px, py, pz, blankRitual);
            } finally {
                if (!placedBlock) TerminalEnergy.refund(terminal, charge);
            }
            if (!placedBlock) {
                stop("denied", px, py, pz);
                break;
            }
            if (!creative && !blankRitual) {
                if (backpack.count(input) > 0) backpack.add(input, -1);
                else held.add(input, -1);
                funding.add(material, input, -1);
            }
            pending.clear(cursor);
            used++;
            changed();
            if (cursor == 0) {
                TileEntity te = world.getTileEntity(px, py, pz);
                if (te instanceof IGregTechTileEntity) {
                    IGregTechTileEntity gt = (IGregTechTileEntity) te;
                    gt.setFrontFacing(facing.getDirection());
                    if (gt.getMetaTileEntity() instanceof IAlignment)
                        ((IAlignment) gt.getMetaTileEntity()).setExtendedFacing(facing);
                }
                if (!anchorValid(world)) {
                    ordinaryCell(cursor, world, ordinaryWork, false);
                    updateOrdinaryProgress(cursor, ordinaryWork);
                    stop("anchor", px, py, pz);
                    break;
                }
            } else if (!materialMatches(c, world, px, py, pz)) {
                ordinaryCell(cursor, world, ordinaryWork, false);
                updateOrdinaryProgress(cursor, ordinaryWork);
                if (!blankRitual && !world.isAirBlock(px, py, pz)) ledger.record(world, px, py, pz);
                stop("obstruction", px, py, pz);
                break;
            }
            if (!blankRitual && !world.isAirBlock(px, py, pz)) ledger.record(world, px, py, pz);
            ordinaryCell(cursor, world, ordinaryWork, false);
            updateOrdinaryProgress(cursor, ordinaryWork);
            confirm(cursor, ordinaryWork.status == OrdinaryStatus.INSTALLED);
        }
        changed();
        if (cursor >= plan.size) {
            cursor = 0;
            state = State.VERIFYING;
            changed();
        }
        return used;
    }

    private boolean placeCell(EntityPlayerMP actor, WorldServer world, Blueprint.Cell cell, ItemStack material,
            int x, int y, int z, boolean blankRitual) {
        net.minecraft.block.Block before = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        if (!blankRitual) {
            ItemStack unit = material.copy();
            unit.stackSize = 1;
            if (!unit.tryPlaceItemIntoWorld(actor, world, x, y, z, 1, .5f, .5f, .5f)) return false;
        }
        if (cell.inscribedRitual && world.getBlock(x, y, z) == cell.block && world.getBlockMetadata(x, y, z) == 0)
            world.setBlockMetadataWithNotify(x, y, z, cell.meta, 3);
        return before != world.getBlock(x, y, z) || meta != world.getBlockMetadata(x, y, z);
    }

    private int buildUpgrade(EntityPlayerMP actor, WorldServer world, ItemStack terminal, int budget, long deadline) {
        if (plan==null || !plan.ready || budget<=0 || !recoverUpgrade(world) || !upgradeAnchor(world)) return 0;
        if (world.getTotalWorldTime()<nextSalvageRetry) return 0;
        Wireless net=Wireless.find(this,world,actor);
        if (!salvage.rows.isEmpty() && !returnSalvage(net,world)) return 0;
        boolean creative=actor.capabilities.isCreativeMode;
        if (!creative) {
            String status=upgradeDepositProblem(net);
            if (!status.isEmpty()) { reason=status; changed(); return 0; }
        }
        state=State.BUILDING; reason="";
        int used=0;
        for (int n=0; cursor<plan.size && used<budget && n<8192 && System.nanoTime()<deadline; n++,cursor++) {
            Blueprint.Cell cell=plan.at(cursor,xyz);
            if (!upgradeChannels.contains(plan.upgradeChannel(cell))) continue;
            int px=xyz[0],py=xyz[1],pz=xyz[2];
            if (!requireChunk(world,px,py,pz)) break;
            ChannelUpgrade.Result result=plan.upgrade(cell,world,px,py,pz,upgradeChannels);
            if (result.kind==ChannelUpgrade.Kind.BLOCKED) { stop("obstruction",px,py,pz); break; }
            if (pending.get(cursor) && !upgradeScanSources.matches(px,py,pz,result)) {
                stop("rescan",px,py,pz); break;
            }
            if (result.kind==ChannelUpgrade.Kind.KEEP || result.kind==ChannelUpgrade.Kind.SKIP) {
                if (pending.get(cursor)) { pending.clear(cursor); placed++; changed(); }
                continue;
            }
            if (!pending.get(cursor)) { stop("rescan",px,py,pz); break; }
            if (px==x && py==y && pz==z || !UpgradePlacement.supports(result)
                    || !ledger.canRecord(result.targetBlock,result.targetMeta)) {
                stop("upgradePlacement",px,py,pz); break;
            }
            ItemStack material=result.target;
            ItemStack input=creative ? null : funding.next(material);
            if (!creative && (input==null || funding.count(material)<1
                    || backpack.count(input)==0 && held.count(input)==0)) { stop("rescan",px,py,pz); break; }
            if (!world.checkNoEntityCollision(AxisAlignedBB.getBoundingBox(px,py,pz,px+1,py+1,pz+1))) {
                pointAt(px,py,pz); reason="occupied"; changed(); break;
            }
            if (!creative) {
                // Simulation is not a storage reservation. A later race leaves the real old item in
                // persistent salvage and pauses before the next replacement instead of dropping it.
                appeng.api.storage.data.IAEItemStack remainder=net.items.injectItems(
                        appeng.util.item.AEItemStack.create(result.previous).setStackSize(1),Actionable.SIMULATE,net.source);
                if (remainder!=null && remainder.getStackSize()>0) {
                    reason="refundSpace"; pointAt(px,py,pz); nextSalvageRetry=world.getTotalWorldTime()+20; changed(); break;
                }
                if (salvage.count(result.previous)==Integer.MAX_VALUE) { stop("upgradeRows",px,py,pz); break; }
            }
            UpgradePlacement.Recovery transaction=new UpgradePlacement.Recovery(px,py,pz,result.oldBlock,result.oldMeta,
                    result.targetBlock,result.targetMeta,ledger.matches(world,px,py,pz));
            double charge = TerminalEnergy.REPLACE.reserve(actor, terminal);
            if (charge < 0) { reason = "noEnergy"; changed(); break; }
            upgradeRecovery=transaction;
            changed();
            UpgradePlacement.Outcome outcome = null;
            try {
                outcome = UpgradePlacement.replace(transaction,actor,world,material);
            } finally {
                if (outcome != UpgradePlacement.Outcome.SUCCESS) TerminalEnergy.refund(terminal, charge);
            }
            if (outcome!=UpgradePlacement.Outcome.SUCCESS) {
                if (transaction.oldMatches(world)) {
                    ledger.restoreOwnership(world,px,py,pz,transaction.owned);
                    upgradeRecovery=null;
                }
                stop(upgradeRecovery==null ? outcome==UpgradePlacement.Outcome.DENIED ? "denied" : "upgradePlacement"
                        : "upgradeRollback",px,py,pz);
                break;
            }
            // Commit exactly one reserved target and one proven old component. Creative contributes neither.
            if (!creative) {
                if (backpack.count(input)>0) backpack.add(input,-1); else held.add(input,-1);
                funding.add(material,input,-1);
                salvage.add(result.previous,1);
            }
            ledger.record(world,px,py,pz);
            upgradeCommitted.set(cursor);
            upgradeRecovery=null;
            pending.clear(cursor); placed++; used++;
            confirm(cursor,true);
            revision++; changed();
            if (!salvage.rows.isEmpty() && !returnSalvage(net,world)) {
                cursor++;
                break;
            }
        }
        changed();
        if (cursor>=plan.size) { cursor=0; state=State.VERIFYING; changed(); }
        return used;
    }

    private void verifyUpgrade(EntityPlayerMP actor, WorldServer world, long deadline) {
        if (plan==null || !plan.ready || !recoverUpgrade(world) || !upgradeAnchor(world)) return;
        for (int n=0; cursor<plan.size && n<8192 && System.nanoTime()<deadline; n++,cursor++) {
            if (!upgradeCommitted.get(cursor)) continue;
            plan.at(cursor,xyz);
            if (!requireChunk(world,xyz[0],xyz[1],xyz[2])) return;
            if (!ledger.matches(world,xyz[0],xyz[1],xyz[2])) { stop("obstruction",xyz[0],xyz[1],xyz[2]); return; }
        }
        changed();
        if (cursor==plan.size) finish(actor,world);
    }

    NBTTagCompound save() {
        NBTTagCompound n = new NBTTagCompound();
        if (ledger.count > 0) n.setTag("placements", ledger.save());
        if (structureCells != null) n.setTag("structureCells", saveStructureCells());
        if (!manualHatches.isEmpty()) {
            int[] positions = new int[manualHatches.size() * 3];
            int i = 0;
            for (ChunkCoordinates p : manualHatches) {
                positions[i++] = p.posX; positions[i++] = p.posY; positions[i++] = p.posZ;
            }
            n.setIntArray("manualHatches", positions);
        }
        n.setBoolean("deleteRequested",deleteRequested);
        n.setBoolean("dismantleRequested",dismantleRequested);
        n.setString("id", id);
        n.setString("owner", owner);
        n.setString("tool", tool);
        n.setString("key", key);
        n.setBoolean("unlimitedRange", unlimitedRange);
        n.setInteger("dim", dim);
        if (dimensionName != null) n.setString("dimensionName", dimensionName);
        n.setInteger("x", x);
        n.setInteger("y", y);
        n.setInteger("z", z);
        n.setInteger("facing", facing.getIndex());
        n.setBoolean("existing", existing);
        if (temporaryHost) n.setBoolean("temporaryHost", true);
        if (upgrading()) {
            NBTTagList scope=new NBTTagList();
            n.setBoolean("exactReplacement", true);
            for (String channel : upgradeChannels) scope.appendTag(new NBTTagString(channel));
            n.setTag("upgradeChannels",scope);
            n.setTag("upgradeRows",upgradeReview.save());
            if (state==State.SCANNING) n.setTag("upgradeScan",upgradeScan.save());
            if (upgradeReviewSources.count>0) n.setTag("upgradeReviewSources",upgradeReviewSources.save());
            if (upgradeScanSources==upgradeReviewSources && upgradeScanSources.count>0) n.setBoolean("upgradeSourcesShared",true);
            else if (upgradeScanSources.count>0) n.setTag("upgradeScanSources",upgradeScanSources.save());
            n.setInteger("upgradeCount",upgradeCount);
            n.setBoolean("upgradeApproved",upgradeApproved);
            if (!upgradeCommitted.isEmpty()) n.setByteArray("upgradeCommitted",upgradeCommitted.toByteArray());
        }
        if (!upgradeNetwork.isEmpty()) n.setString("upgradeNetwork",upgradeNetwork);
        if (upgradeReturnToBuild) {
            n.setBoolean("upgradeReturnToBuild",true);
            n.setByte("upgradeReturnLayers",(byte)upgradeReturnLayers);
        }
        if (upgradeRecovery!=null) n.setTag("upgradeRecovery",upgradeRecovery.save());
        if (upgradeRecoveryFault) n.setBoolean("upgradeRecoveryFault",true);
        n.setString("state", state.name());
        n.setString("reason", reason);
        n.setString("detail", detail);
        if (issue != null) n.setIntArray("issue", new int[]{issue.posX, issue.posY, issue.posZ});
        n.setBoolean("auto", auto);
        if (checkMaterialsOnly && state == State.SCANNING) n.setBoolean("checkMaterialsOnly", true);
        n.setBoolean("received", received);
        n.setInteger("tier", tier);
        n.setBoolean("useSurvivalConstruct", useSurvivalConstruct);
        n.setByte("previewLayers", (byte) previewLayers);
        if (texturedPreview) n.setBoolean("texturedPreview", true);
        if (!pendingDefaults.hasNoTags()) n.setTag("pendingDefaults", pendingDefaults);
        n.setInteger("cursor", cursor);
        n.setInteger("total", total);
        n.setInteger("placed", placed);
        n.setByteArray("pending", pending.toByteArray());
        if (confirmed != null) n.setByteArray("previewConfirmed", confirmed.toByteArray());
        n.setTag("channels", channels);
        if (!materialSelections.hasNoTags()) n.setTag("materialSelections", materialSelections);
        if (!materialCells.hasNoTags()) n.setTag("materialCells", materialCells);
        if (!materialGroups.hasNoTags()) n.setTag("materialGroups", materialGroups);
        if (target != null) n.setTag("target", target.writeToNBT(new NBTTagCompound()));
        n.setTag("backpack", backpack.save());
        n.setTag("funding", funding.save());
        n.setTag("orderFunding", orderFunding.save());
        n.setTag("needed", needed.save());
        n.setTag("held", held.save());
        if (!salvage.rows.isEmpty()) n.setTag("salvage", salvage.save());
        n.setTag("short", shortage.save());
        n.setTag("order", orderItems.save());
        n.setTag("errors", errors.save());
        n.setString("orderId", orderId);
        if (order != null) order.saveLink();
        if (savedLink != null) n.setTag("link", savedLink);
        return n;
    }

    static BuildTask load(BuilderTasks store, NBTTagCompound n) {
        BuildTask t =
                new BuildTask(
                        store,
                        n.getString("id"),
                        n.getString("owner"),
                        n.getString("tool"),
                        n.getInteger("dim"),
                        n.getInteger("x"),
                        n.getInteger("y"),
                        n.getInteger("z"),
                        ExtendedFacing.byIndex(n.getInteger("facing")),
                        n.getBoolean("existing"));
        t.temporaryHost = t.existing && n.getBoolean("temporaryHost");
        t.dimensionName = n.hasKey("dimensionName", 8)
                ? n.getString("dimensionName") : TaskDimension.identify(t.dim, null);
        t.ledger.load(n.getCompoundTag("placements"));
        if (n.hasKey("structureCells", 9)) t.loadStructureCells(n.getTagList("structureCells", 10));
        int[] positions = n.getIntArray("manualHatches");
        for (int i = 0; i + 2 < positions.length; i += 3)
            t.manualHatches.add(new ChunkCoordinates(positions[i], positions[i + 1], positions[i + 2]));
        t.deleteRequested = n.getBoolean("deleteRequested");
        t.dismantleRequested = n.getBoolean("dismantleRequested");
        t.key = n.getString("key");
        t.unlimitedRange = n.getBoolean("unlimitedRange");
        t.state = State.valueOf(n.getString("state"));
        t.reason = n.getString("reason");
        NBTTagList scope=n.getTagList("upgradeChannels",8);
        if (scope.tagCount()<=256)
            for (int i=0;i<scope.tagCount();i++) {
                String channel=scope.getStringTagAt(i);
                if (!channel.trim().isEmpty()) t.upgradeChannels.add(channel);
            }
        t.upgradeApproved=n.getBoolean("upgradeApproved");
        t.upgradeNetwork=n.getString("upgradeNetwork");
        t.upgradeReturnToBuild=n.getBoolean("upgradeReturnToBuild");
        t.upgradeReturnLayers=n.getByte("upgradeReturnLayers")&7;
        t.upgradeCommitted=BitSet.valueOf(n.getByteArray("upgradeCommitted"));
        boolean validReview=t.upgradeReview.load(n.getTagList("upgradeRows",10));
        boolean validScan=t.upgradeScan.load(n.getTagList("upgradeScan",10));
        boolean validReviewSources=t.upgradeReviewSources.load(n.getCompoundTag("upgradeReviewSources"));
        boolean validScanSources;
        if (n.getBoolean("upgradeSourcesShared")) {
            t.upgradeScanSources=t.upgradeReviewSources; validScanSources=validReviewSources;
        } else validScanSources=t.upgradeScanSources.load(n.getCompoundTag("upgradeScanSources"));
        t.upgradeCount=t.upgradeReview.count;
        if (n.hasKey("upgradeRecovery")) t.upgradeRecovery=UpgradePlacement.Recovery.load(n.getCompoundTag("upgradeRecovery"));
        t.upgradeRecoveryFault=n.getBoolean("upgradeRecoveryFault") || n.hasKey("upgradeRecovery") && t.upgradeRecovery==null;
        boolean reviewPhase=t.state==State.INSPECTING_UPGRADE || t.state==State.UPGRADE_REVIEW || t.state==State.SCANNING
                || t.state==State.BLOCKED || t.state==State.MISSING || t.ordering() || t.state==State.READY
                || t.state==State.QUEUED || t.state==State.BUILDING || t.state==State.VERIFYING;
        boolean scanMismatch=t.state==State.SCANNING ? t.upgradeScan.count!=t.upgradeScanSources.count
                : t.upgradeApproved && reviewPhase && t.upgradeReview.count!=t.upgradeScanSources.count;
        if (scope.tagCount()>256 || !validReview || !validScan || !validReviewSources || !validScanSources
                || t.upgrading() && reviewPhase && (t.upgradeReview.count!=t.upgradeReviewSources.count || scanMismatch)) {
            t.state=State.BLOCKED; t.reason="upgradeRows"; t.upgradeApproved=false;
        }
        if (t.upgradeRecoveryFault) { t.state=State.REFUND; t.reason="upgradeRollback"; }
        if (!t.upgrading() && t.upgradeRecovery!=null) { t.state=State.REFUND; t.reason="upgradeRollback"; }
        if (t.state == State.DISMANTLING && "harvestTool".equals(t.reason)) t.reason = "";
        t.detail = n.getString("detail");
        int[] problem = n.getIntArray("issue");
        if (problem.length == 3) t.issue = new ChunkCoordinates(problem[0], problem[1], problem[2]);
        else if (t.state == State.BLOCKED) {
            String[] old = t.detail.split(", ");
            if (old.length == 3) try {
                t.issue = new ChunkCoordinates(Integer.parseInt(old[0]), Integer.parseInt(old[1]), Integer.parseInt(old[2]));
            } catch (NumberFormatException ignored) {}
        }
        t.auto = n.getBoolean("auto");
        t.checkMaterialsOnly = n.getBoolean("checkMaterialsOnly");
        t.received = n.getBoolean("received");
        t.tier = n.getInteger("tier");
        t.previewLayers = n.getByte("previewLayers") & 7;
        t.texturedPreview = n.getBoolean("texturedPreview");
        t.pendingDefaults = n.getCompoundTag("pendingDefaults");
        t.cursor = n.getInteger("cursor");
        t.total = n.getInteger("total");
        t.placed = n.getInteger("placed");
        t.channels = n.getCompoundTag("channels");
        t.materialSelections = n.getCompoundTag("materialSelections");
        t.materialCells = n.getCompoundTag("materialCells");
        t.materialGroups = n.getCompoundTag("materialGroups");
        t.pending = BitSet.valueOf(n.getByteArray("pending"));
        if (n.hasKey("previewConfirmed")) {
            t.confirmed = BitSet.valueOf(n.getByteArray("previewConfirmed"));
        } else {
            switch (t.state) {
                case SCANNING:
                case BLOCKED:
                    t.confirmed.set(0, t.cursor);
                    t.confirmed.andNot(t.pending);
                    break;
                case MISSING:
                case PLANNING:
                case CPU:
                case ORDERED:
                case READY:
                case QUEUED:
                case BUILDING:
                case VERIFYING:
                    t.confirmed = null;
                    break;
                default:
                    break;
            }
        }
        t.target = ItemStack.loadItemStackFromNBT(n.getCompoundTag("target"));
        // Queued builds and reservations address cells by ordinal. Resume with the source
        // that produced those ordinals, including construct-only saves from earlier versions.
        boolean savedSource = n.getBoolean("useSurvivalConstruct");
        if (t.state == State.DRAFT || t.state == State.PREVIEW) {
            if (t.useSurvivalConstruct != savedSource) {
                t.structureCells = null;
                t.confirmed = new BitSet();
                t.pending.clear();
                t.cursor = t.placed = t.total = 0;
                t.revision++;
                t.reason = "";
                t.issue = null;
            }
        } else {
            t.useSurvivalConstruct = savedSource;
        }
        t.needed.read(n.getTagList("needed", 10));
        t.held.read(n.getTagList("held", 10));
        t.salvage.read(n.getTagList("salvage", 10));
        t.backpack.read(n.getTagList("backpack", 10));
        // The old controller slot held one actual item outside the material ledger.
        ItemStack legacy = ItemStack.loadItemStackFromNBT(n.getCompoundTag("physical"));
        if (legacy != null) {
            t.backpack.add(legacy, 1);
            if (t.pending.get(0)) t.needed.add(legacy, 1);
        }
        t.shortage.read(n.getTagList("short", 10));
        t.orderItems.read(n.getTagList("order", 10));
        // Older tasks only accepted the exact displayed item for each position.
        if (n.hasKey("funding")) t.funding.read(n.getTagList("funding", 10));
        else {
            t.funding.addExact(t.held);
            t.funding.addExact(t.backpack);
        }
        if (n.hasKey("orderFunding")) t.orderFunding.read(n.getTagList("orderFunding", 10));
        else t.orderFunding.addExact(t.orderItems);
        t.errors.read(n.getTagList("errors", 10));
        // Older read paths could quote the technical ItemBlock for a fluid. Physical escrow remains refundable.
        t.needed.rows.values().removeIf(e -> StructureFluids.isFluidItem(e.item));
        t.shortage.rows.values().removeIf(e -> StructureFluids.isFluidItem(e.item));
        t.errors.rows.values().removeIf(e -> StructureFluids.isFluidItem(e.item));
        t.orderId = n.getString("orderId");
        t.obsoleteFluidOrder = t.ordering() && !t.received && t.orderItems.rows.values().stream()
                .anyMatch(e -> StructureFluids.isFluidItem(e.item));
        if (n.hasKey("link")) t.savedLink = n.getCompoundTag("link");
        // An old, unconfirmed list omits higher tiers and same-tier variants. Reinspect before presenting it.
        if (t.upgrading() && !t.upgradeApproved && !n.getBoolean("exactReplacement")
                && (t.state == State.UPGRADE_REVIEW || t.state == State.INSPECTING_UPGRADE)) {
            t.restartReplacementInspection();
        }
        if (t.state!=State.INSPECTING_UPGRADE) t.upgradeReview.releaseIndex();
        if (t.state==State.DONE || t.state==State.DISMANTLED || t.state==State.REFUND) t.releaseUpgradePhaseData();
        t.resumeOrdinaryAfterUpgradeRefund();
        return t;
    }
}
