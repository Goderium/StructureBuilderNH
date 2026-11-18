package com.Goderium.StructureBuilderNH.common.builder;

import appeng.api.config.*;

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

import java.util.*;

final class BuildTask {
    enum State {
        DRAFT,
        PREVIEW,
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
    final TaskChunks workChunks = new TaskChunks();
    boolean deleteRequested, dismantleRequested;
    final BuilderTasks store;
    final String id, owner, tool;
    final int dim, x, y, z;
    final ExtendedFacing facing;
    final boolean existing;
    String key = "", orderId = "", detail = "", reason = "";
    State state = State.DRAFT;
    boolean auto, received;
    ChunkCoordinates issue;

    void pointAt(int x, int y, int z) {
        if (issue == null || issue.posX != x || issue.posY != y || issue.posZ != z) {
            issue = new ChunkCoordinates(x, y, z);
            changed();
        }
    }

    ItemStack target;
    int tier = 1, cursor, total, placed, revision;
    int previewLayers;
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
    private final int[] xyz = new int[3];
    private BitSet pending = new BitSet();
    private BitSet confirmed = new BitSet();
    private long nextSalvageRetry;

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

    void resetPlan() {
        plan = null;
        confirmed = new BitSet();
        revision++;
        reason = "";
        issue = null;
        changed();
    }

    boolean editable() {
        return state == State.DRAFT || state == State.PREVIEW || state == State.MISSING && !hasReserved();
    }

    void configureTarget(ItemStack stack) {
        target = ItemStack.copyItemStack(stack);
        if (target == null) previewLayers = 0;
        else target.stackSize = 1;
        observedChannels.clear();
        store.channelMemory.restore(this);
        resetDraft();
    }

    void resetDraft() {
        state = previewLayers == 0 ? State.DRAFT : State.PREVIEW;
        resetPlan();
    }

    void togglePreview(int layer) {
        if (target == null || state == State.DONE || state == State.REFUND || dismantleRequested || state == State.DISMANTLED) return;
        previewLayers ^= layer;
        if (state == State.DRAFT || state == State.PREVIEW)
            state = previewLayers == 0 ? State.DRAFT : State.PREVIEW;
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
                plan = new Blueprint(target, trigger, facing, x, y, z, actor, this::initiallyConfirmed);
            }
            if (!plan.ready) plan.advance(deadline);
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
                        target == null ? "" : target.getDisplayName(),
                        x,
                        y,
                        z));
    }

    void start() {
        if (state == State.READY) {
            state = State.QUEUED;
            changed();
            return;
        }
        if (state != State.DRAFT && state != State.PREVIEW && state != State.MISSING && state != State.BLOCKED) return;
        if (target == null) return;
        if (confirmed == null) resetPlan();
        if (state != State.BLOCKED) placed = 0;
        issue = null;
        state = State.SCANNING;
        cursor = 0;
        total = placed;
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
                                target.getDisplayName(),
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

    private boolean anchorValid(WorldServer world) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof IGregTechTileEntity)) return false;
        IGregTechTileEntity gt = (IGregTechTileEntity) te;
        return gt.getMetaTileID() == target.getMetadata()
                && gt.getFrontFacing() == facing.getDirection()
                && (!(gt.getMetaTileEntity() instanceof IAlignment)
                        || ((IAlignment) gt.getMetaTileEntity()).getExtendedFacing() == facing);
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
        if (plan == null || !plan.ready) return;
        for (int n = 0;
                cursor < plan.size && n < 8192 && System.nanoTime() < deadline;
                n++, cursor++) {
            Blueprint.Cell c = plan.at(cursor, xyz);
            int px = xyz[0], py = xyz[1], pz = xyz[2];
            if (!requireChunk(world, px, py, pz)) return;
            boolean matches = cursor == 0 ? anchorValid(world) : plan.matches(c, world, px, py, pz);
            if (!c.air) confirm(cursor, matches);
            if (matches) continue;
            if (cursor == 0
                    && (existing || !plan.controller.isFacingValid(facing.getDirection()))) {
                stop("anchor", px, py, pz);
                return;
            }
            boolean blankRitual =
                    c.inscribedRitual
                            && world.getBlock(px, py, pz) == c.block
                            && world.getBlockMetadata(px, py, pz) == 0;
            if ((!blankRitual && !world.isAirBlock(px, py, pz)) || c.item == null) {
                stop("obstruction", px, py, pz);
                return;
            }
            if (!blankRitual) needed.add(c.item, 1);
            pending.set(cursor);
            total++;
        }
        changed();
        if (cursor < plan.size) return;
        if (total == placed) {
            finish(actor, world);
            return;
        }
        if (actor.capabilities.isCreativeMode) {
            cursor = 0;
            reason = "";
            state = State.QUEUED;
            changed();
            return;
        }
        Wireless net = Wireless.find(this, world, actor);
        cursor = 0;
        reason = "";
        quote(actor, net, false);
        if (shortage.rows.isEmpty()) {
            quote(actor, net, true);
            if (shortage.rows.isEmpty()) {
                state = State.QUEUED;
                return;
            }
        }
        state = State.MISSING;
        revision++;
        notify(actor, "missing");
    }

    boolean hasReserved() {
        return !backpack.rows.isEmpty() || !held.rows.isEmpty() || !salvage.rows.isEmpty();
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
        Wireless net = Wireless.find(this, world, actor);
        if (net == null) {
            reason = "network";
            detach();
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
        if (state == State.REFUND) {
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
        if (net != null) deposit(net, held);
    }

    private void deposit(Wireless net, Materials from) {
        for (Materials.Entry e : new ArrayList<>(from.rows.values())) {
            int accepted = net.refund(e);
            if (accepted > 0) {
                from.add(e.item, -accepted);
                revision++;
                changed();
            }
        }
    }

    void cancel(EntityPlayerMP actor, WorldServer world) {
        boolean manualRecovery = state == State.REFUND && key.isEmpty();
        if (state != State.REFUND) {
            state = State.REFUND;
            previewLayers = 0;
            funding.clear();
            plan = null;
            reason = "";
            issue = null;
            detail = "";
            // Preserve a detached link until its CPU can see the cancellation.
            if (order != null) order.saveLink();
            if (savedLink == null) clearOrder();
            changed();
        }
        if (manualRecovery && savedLink == null) {
            recover(actor);
            revision++;
            changed();
        }
        if (world != null && actor.worldObj == world) retryRefund(actor, world);
    }

    void retryRefund(EntityPlayerMP actor, WorldServer world) {
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
        if (hasReserved()) {
            Wireless net = Wireless.find(this, world, actor);
            status = depositProblem(net, !salvage.rows.isEmpty());
            if (status.isEmpty()) {
                deposit(net, held);
                deposit(net, backpack);
                deposit(net, salvage);
                if (hasReserved()) status = "refundSpace";
            }
        }
        if (!reason.equals(status)) {
            reason = status;
            changed();
        }
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
        for (Materials.Entry e : new ArrayList<>(backpack.rows.values()))
            backpack.add(e.item, -giveToInventory(actor, e.item, e.count));
    }

    void recover(EntityPlayerMP actor) {
        returnBackpack(actor);
        for (Materials.Entry e : new ArrayList<>(held.rows.values()))
            held.add(e.item, -giveToInventory(actor, e.item, e.count));
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
            if (ledger.matches(world, x, y, z) && !ledger.harvest(actor, world, blueprint, x, y, z)) {
                stop("harvest", x, y, z);
                state = State.TEARDOWN_PAUSED;
                changed();
                break;
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
        String status = depositProblem(net, true);
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
        if (plan == null || !plan.ready) return;
        for (int n = 0;
                cursor < plan.size && n < 8192 && System.nanoTime() < deadline;
                n++, cursor++) {
            Blueprint.Cell c = plan.at(cursor, xyz);
            int px = xyz[0], py = xyz[1], pz = xyz[2];
            if (!requireChunk(world, px, py, pz)) return;
            boolean matches = cursor == 0 ? anchorValid(world) : plan.matches(c, world, px, py, pz);
            if (!c.air) confirm(cursor, matches);
            if (!matches) {
                stop("obstruction", px, py, pz);
                return;
            }
        }
        changed();
        if (cursor == plan.size) finish(actor, world);
    }

    void finish(EntityPlayerMP actor, WorldServer world) {
        refund(Wireless.find(this, world, actor));
        returnBackpack(actor);
        funding.clear();
        confirmed = new BitSet();
        issue = null;
        state = State.DONE;
        previewLayers = 0;
        reason = "";
        changed();
        notify(actor, "done");
        plan = null;
        store.completed(this, actor, world);
    }

    int build(EntityPlayerMP actor, WorldServer world, int budget, long deadline) {
        if (plan == null || !plan.ready || budget <= 0) return 0;
        boolean creative = actor.capabilities.isCreativeMode;
        if (!requireChunk(world, x, y, z)) return 0;
        if (cursor > 0 && !anchorValid(world)) {
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
            boolean matches = cursor == 0 ? anchorValid(world) : plan.matches(c, world, px, py, pz);
            if (!c.air) confirm(cursor, matches);
            if (matches) {
                if (pending.get(cursor)) {
                    placed++;
                    pending.clear(cursor);
                }
                continue;
            }
            boolean blankRitual =
                    c.inscribedRitual
                            && world.getBlock(px, py, pz) == c.block
                            && world.getBlockMetadata(px, py, pz) == 0;
            if (cursor == 0 && existing
                    || c.item == null
                    || !blankRitual && !world.isAirBlock(px, py, pz)) {
                stop("obstruction", px, py, pz);
                break;
            }
            ItemStack input = creative || blankRitual ? null : funding.next(c.item);
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
                    || !actor.canPlayerEdit(px, py, pz, 1, c.item)) {
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
            ItemStack unit = c.item.copy();
            unit.stackSize = 1;
            if (!blankRitual
                    && !unit.tryPlaceItemIntoWorld(actor, world, px, py, pz, 1, .5f, .5f, .5f)) {
                stop("denied", px, py, pz);
                break;
            }
            if (c.inscribedRitual
                    && world.getBlock(px, py, pz) == c.block
                    && world.getBlockMetadata(px, py, pz) == 0) {
                world.setBlockMetadataWithNotify(px, py, pz, c.meta, 3);
            }
            if (!creative && !blankRitual) {
                if (backpack.count(input) > 0) backpack.add(input, -1);
                else held.add(input, -1);
                funding.add(c.item, input, -1);
            }
            if (!pending.get(cursor)) total++;
            pending.clear(cursor);
            placed++;
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
                    stop("anchor", px, py, pz);
                    break;
                }
            } else if (!plan.matches(c, world, px, py, pz)) {
                stop("obstruction", px, py, pz);
                break;
            }
            if (!blankRitual && !c.air) ledger.record(world, px, py, pz);
            if (!c.air) confirm(cursor, true);
        }
        changed();
        if (cursor >= plan.size) {
            cursor = 0;
            state = State.VERIFYING;
            changed();
        }
        return used;
    }

    NBTTagCompound save() {
        NBTTagCompound n = new NBTTagCompound();
        if (ledger.count > 0) n.setTag("placements", ledger.save());
        n.setBoolean("deleteRequested",deleteRequested);
        n.setBoolean("dismantleRequested",dismantleRequested);
        n.setString("id", id);
        n.setString("owner", owner);
        n.setString("tool", tool);
        n.setString("key", key);
        n.setInteger("dim", dim);
        n.setInteger("x", x);
        n.setInteger("y", y);
        n.setInteger("z", z);
        n.setInteger("facing", facing.getIndex());
        n.setBoolean("existing", existing);
        n.setString("state", state.name());
        n.setString("reason", reason);
        n.setString("detail", detail);
        if (issue != null) n.setIntArray("issue", new int[]{issue.posX, issue.posY, issue.posZ});
        n.setBoolean("auto", auto);
        n.setBoolean("received", received);
        n.setInteger("tier", tier);
        n.setByte("previewLayers", (byte) previewLayers);
        if (!pendingDefaults.hasNoTags()) n.setTag("pendingDefaults", pendingDefaults);
        n.setInteger("cursor", cursor);
        n.setInteger("total", total);
        n.setInteger("placed", placed);
        n.setByteArray("pending", pending.toByteArray());
        if (confirmed != null) n.setByteArray("previewConfirmed", confirmed.toByteArray());
        n.setTag("channels", channels);
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
        t.ledger.load(n.getCompoundTag("placements"));
        t.deleteRequested = n.getBoolean("deleteRequested");
        t.dismantleRequested = n.getBoolean("dismantleRequested");
        t.key = n.getString("key");
        t.state = State.valueOf(n.getString("state"));
        t.reason = n.getString("reason");
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
        t.received = n.getBoolean("received");
        t.tier = n.getInteger("tier");
        t.previewLayers = n.getByte("previewLayers") & 7;
        t.pendingDefaults = n.getCompoundTag("pendingDefaults");
        t.cursor = n.getInteger("cursor");
        t.total = n.getInteger("total");
        t.placed = n.getInteger("placed");
        t.channels = n.getCompoundTag("channels");
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
        t.orderId = n.getString("orderId");
        if (n.hasKey("link")) t.savedLink = n.getCompoundTag("link");
        return t;
    }
}
