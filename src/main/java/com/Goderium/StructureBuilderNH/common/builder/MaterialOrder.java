package com.Goderium.StructureBuilderNH.common.builder;

import appeng.api.AEApi;
import appeng.api.config.*;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.storage.data.*;
import appeng.api.util.*;
import appeng.helpers.PatternHelper;
import appeng.me.cache.CraftingGridCache;
import appeng.me.helpers.*;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;

import com.google.common.collect.ImmutableSet;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.EnumSet;
import java.util.concurrent.Future;

/**
 * A zero-idle-power, non-world-facing requester. The final processing input goes directly into this
 * job's escrow.
 */
final class MaterialOrder implements IGridProxyable, ICraftingProvider, ICraftingRequester {
    final BuildTask task;
    final World world;
    final AENetworkProxy proxy;
    final ItemStack receipt;
    final ICraftingPatternDetails pattern;
    ICraftingLink link;
    private Future<ICraftingJob> future;
    private ICraftingJob calculated;
    private boolean closed;
    final IGridNode access;

    MaterialOrder(BuildTask task, Wireless net, World world) {
        this.task = task;
        this.world = world;
        access = net.node;
        receipt = new ItemStack(BuilderItem.RECEIPT);
        receipt.setTagCompound(new NBTTagCompound());
        receipt.getTagCompound().setString("order", task.orderId);
        receipt.getTagCompound().setTag("controller", task.target.writeToNBT(new NBTTagCompound()));
        ItemStack encoded =
                AEApi.instance().definitions().items().encodedPattern().maybeStack(1).get();
        NBTTagList inputs = new NBTTagList(), outputs = new NBTTagList();
        for (Materials.Entry e : task.orderItems.rows.values()) {
            ItemStack s = e.item.copy();
            s.stackSize = e.count;
            inputs.appendTag(Platform.writeItemStackToNBT(s, new NBTTagCompound()));
        }
        outputs.appendTag(Platform.writeItemStackToNBT(receipt, new NBTTagCompound()));
        NBTTagCompound tags = new NBTTagCompound();
        tags.setTag("in", inputs);
        tags.setTag("out", outputs);
        encoded.setTagCompound(tags);
        pattern = new PatternHelper(encoded, world);
        proxy = new AENetworkProxy(this, "node", null, false);
        proxy.setValidSides(EnumSet.noneOf(ForgeDirection.class));
        proxy.setIdlePowerUsage(0);
        proxy.setOwner(net.source.player);
        if (task.savedLink != null)
            link = AEApi.instance().storage().loadCraftingLink(task.savedLink, this);
    }

    void attach() throws Exception {
        proxy.onReady();
        AEApi.instance().createGridConnection(proxy.getNode(), access);
    }

    void close() {
        closed = true;
        if (future != null) future.cancel(false);
        proxy.invalidate();
    }

    void saveLink() {
        if (link != null) {
            task.savedLink = new NBTTagCompound();
            link.writeToNBT(task.savedLink);
        }
    }

    void tick(Wireless net, boolean online) throws Exception {
        if (closed || proxy.getNode() == null || proxy.getNode().getGrid() != net.grid) return;
        ICraftingGrid crafting = net.grid.getCache(ICraftingGrid.class);
        if (link != null) {
            if (link.isCanceled() || link.isDone()) {
                task.orderEnded(link.isDone());
                return;
            }
            if (task.received && crafting instanceof CraftingGridCache) {
                // Offer only to waiting CPUs. A late/cancelled receipt must never enter general
                // storage.
                ((CraftingGridCache) crafting)
                        .injectItems(
                                (IAEStack) AEItemStack.create(receipt),
                                Actionable.MODULATE,
                                net.source);
            }
            return;
        }
        if (!online
                || !net.allows(SecurityPermissions.CRAFT)
                || !net.allows(SecurityPermissions.EXTRACT)) return;
        if (calculated == null) {
            if (future == null) {
                future =
                        crafting.beginCraftingJob(
                                world, net.grid, net.source, AEItemStack.create(receipt), null);
                return;
            }
            if (!future.isDone()) return;
            calculated = future.get();
            if (calculated.isSimulation()) {
                task.detail = calculated.getErrorMessage();
                IItemList<IAEStack<?>> list = AEApi.instance().storage().createAEStackList();
                calculated.populatePlan(list);
                task.errors.rows.clear();
                for (IAEStack<?> s : list)
                    if (s instanceof IAEItemStack && s.getStackSize() > 0) {
                        IAEStack available =
                                calculated
                                        .getStorageAtBeginning()
                                        .extractItems(
                                                (IAEStack) s, Actionable.SIMULATE, net.source);
                        long missing =
                                s.getStackSize()
                                        - (available == null ? 0 : available.getStackSize());
                        if (missing > 0)
                            task.errors.add(
                                    ((IAEItemStack) s).getItemStack(),
                                    (int) Math.min(Integer.MAX_VALUE, missing));
                    }
                task.orderFailed(net);
                return;
            }
        }
        link = crafting.submitJob(calculated, this, null, false, net.source, true);
        task.state = link == null ? BuildTask.State.CPU : BuildTask.State.ORDERED;
        saveLink();
        task.changed();
    }

    public void provideCrafting(ICraftingProviderHelper helper) {
        if (!closed && task.ordering() && !task.orderId.isEmpty())
            helper.addCraftingOption(this, pattern);
    }

    public boolean pushPattern(ICraftingPatternDetails p, InventoryCrafting table) {
        if (closed
                || !task.ordering()
                || link == null
                || link.isCanceled()
                || task.received
                || !pattern.equals(p)) return false;
        Materials arrived = new Materials();
        for (int i = 0; i < table.getSizeInventory(); i++) {
            ItemStack s = table.getStackInSlot(i);
            if (s != null && s.stackSize > 0) arrived.add(s, s.stackSize);
        }
        if (!arrived.same(task.orderItems)) return false;
        for (Materials.Entry e : arrived.rows.values()) task.held.add(e.item, e.count);
        task.funding.merge(task.orderFunding);
        task.received = true;
        task.changed();
        return true;
    }

    public boolean isBusy() {
        return closed || !task.ordering() || task.received;
    }

    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        return link == null ? ImmutableSet.of() : ImmutableSet.of(link);
    }

    @Override
    public IAEStack<?> injectCraftedItems(ICraftingLink job, IAEStack<?> stack, Actionable mode) {
        return job == link
                        && task.received
                        && stack instanceof IAEItemStack
                        && Materials.equal(((IAEItemStack) stack).getItemStack(), receipt)
                ? null
                : stack;
    }

    public void jobStateChange(ICraftingLink job) {
        if (job == link) {
            saveLink();
            task.changed();
        }
    }

    public AENetworkProxy getProxy() {
        return proxy;
    }

    public DimensionalCoord getLocation() {
        return new DimensionalCoord(world, task.x, task.y, task.z);
    }

    public IGridNode getGridNode(ForgeDirection dir) {
        return proxy.getNode();
    }

    public IGridNode getActionableNode() {
        return proxy.getNode();
    }

    public AECableType getCableConnectionType(ForgeDirection dir) {
        return AECableType.NONE;
    }

    public void gridChanged() {}

    public void securityBreak() {
        if (link != null) link.cancel();
    }
}
