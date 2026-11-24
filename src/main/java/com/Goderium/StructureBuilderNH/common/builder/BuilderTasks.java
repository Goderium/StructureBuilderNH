package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.StructureBuilderNH;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

import net.minecraft.entity.player.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.*;
import net.minecraftforge.common.*;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.*;

public final class BuilderTasks extends WorldSavedData {
    private static final String NAME = "structurebuildernh_builds";
    final Map<String, BuildTask> jobs = new LinkedHashMap<>();
    final ChannelMemory channelMemory = new ChannelMemory();
    final Map<String, Integer> viewers = new HashMap<>();
    private final Map<String, Integer> credits = new HashMap<>();
    private final Map<String, Integer> dispatchTurns = new HashMap<>();
    private int terminalTurn;
    private static final long BUILD_NANOS = 10_000_000L, DISMANTLE_NANOS = 3_000_000L;

    private static final class TickBudget {
        long nanos;
        int tasks;

        TickBudget(long nanos) {
            this.nanos = nanos;
        }

        long nextShare() {
            return nanos / tasks--;
        }
    }

    private final Map<Integer, Map<Long, Set<PlacementLedger>>> watched = new HashMap<>();

    void watch(int dim, long key, PlacementLedger ledger) {
        watched.computeIfAbsent(dim, d -> new HashMap<>())
                .computeIfAbsent(key, k -> new HashSet<>())
                .add(ledger);
    }

    void unwatch(int dim, long key, PlacementLedger ledger) {
        Map<Long, Set<PlacementLedger>> parts = watched.get(dim);
        Set<PlacementLedger> ledgers = parts.get(key);
        ledgers.remove(ledger);
        if (ledgers.isEmpty()) parts.remove(key);
        if (parts.isEmpty()) watched.remove(dim);
    }

    List<PlacementLedger> placementOwners(World world, int x, int y, int z) {
        Map<Long, Set<PlacementLedger>> parts = watched.get(world.provider.dimensionId);
        Set<PlacementLedger> candidates = parts == null ? null : parts.get(PlacementLedger.key(x, y, z));
        if (candidates == null) return Collections.emptyList();
        List<PlacementLedger> owners = new ArrayList<>();
        for (PlacementLedger ledger : candidates)
            if (ledger == ledger.task.ledger && ledger.matches(world, x, y, z)) owners.add(ledger);
        return owners;
    }

    private void blockChanged(World world, int x, int y, int z) {
        PreviewStream.worldChanged(world.provider.dimensionId, x, y, z);
        Map<Long, Set<PlacementLedger>> parts = watched.get(world.provider.dimensionId);
        if (parts == null) return;
        Set<PlacementLedger> ledgers = parts.get(PlacementLedger.key(x, y, z));
        if (ledgers != null) {
            // Forget may detach the last section entry while iterating these tasks.
            for (PlacementLedger ledger : ledgers.toArray(new PlacementLedger[0]))
                ledger.forget(x, y, z);
        }
    }

    private final Set<String> autoRemoveOwners = new HashSet<>();

    boolean autoRemove(EntityPlayerMP player) {
        return autoRemoveOwners.contains(player.getUniqueID().toString());
    }

    void toggleAutoRemove(EntityPlayerMP player) {
        String owner = player.getUniqueID().toString();
        if (!autoRemoveOwners.remove(owner)) autoRemoveOwners.add(owner);
        markDirty();
    }

    void completed(BuildTask task, EntityPlayerMP player, WorldServer world) {
        if (!autoRemove(player)) return;
        if (task.hasReserved() || task.savedLink != null) {
            task.deleteRequested = true;
            task.cancel(player, world);
        } else remove(task, player);
    }

    private int turn;
    private int chunkLoadsLeft;
    private final Map<String, BuildTask> unbound = new HashMap<>();

    public BuilderTasks(String name) {
        super(name);
        com.Goderium.StructureBuilderNH.common.WorldHooks.trackedChanges = this::blockChanged;
    }

    static BuilderTasks get() {
        WorldServer world = DimensionManager.getWorld(0);
        BuilderTasks s = (BuilderTasks) world.mapStorage.loadData(BuilderTasks.class, NAME);
        if (s == null) {
            s = new BuilderTasks(NAME);
            world.mapStorage.setData(NAME, s);
        }
        return s;
    }

    static NBTTagCompound tag(ItemStack stack) {
        if (stack.getTagCompound() == null) stack.setTagCompound(new NBTTagCompound());
        return stack.getTagCompound();
    }

    static String tool(ItemStack stack) {
        NBTTagCompound tag = tag(stack);
        if (!tag.hasKey("tool")) tag.setString("tool", UUID.randomUUID().toString());
        return tag.getString("tool");
    }

    BuildTask selected(EntityPlayer p, ItemStack stack) {
        if (stack == null || stack.getItem() != BuilderItem.ITEM) return null;
        BuildTask t = jobs.get(tag(stack).getString("selected"));
        if (t == null) {
            t = unbound.get(p.getUniqueID().toString());
            if (t == null || !t.id.equals(tag(stack).getString("selected"))) return null;
        }
        return owns(t, p, stack) ? t : null;
    }

    private boolean owns(BuildTask t, EntityPlayer p, ItemStack stack) {
        return t != null
                && t.owner.equals(p.getUniqueID().toString())
                && t.tool.equals(tool(stack));
    }

    List<BuildTask> list(EntityPlayer p, ItemStack stack) {
        List<BuildTask> owned = new ArrayList<>();
        for (BuildTask t : jobs.values()) if (owns(t, p, stack)) owned.add(t);
        return owned;
    }

    void select(ItemStack stack, BuildTask t) {
        NBTTagCompound n = tag(stack);
        if (t != null && jobs.containsKey(t.id)) n.setString("lastTask", t.id);
        else if (jobs.containsKey(n.getString("selected"))) n.setString("lastTask", n.getString("selected"));
        n.setString("selected", t == null ? "" : t.id);
    }

    public static String shortcutTarget(ItemStack stack) {
        if (stack == null || stack.getItem() != BuilderItem.ITEM || !stack.hasTagCompound()) return "";
        NBTTagCompound n = stack.getTagCompound();
        return n.hasKey("lastTask") ? n.getString("lastTask") : n.getString("selected");
    }

    boolean shortcutDelete(EntityPlayerMP player, ItemStack stack, String id) {
        if (id.isEmpty() || !id.equals(shortcutTarget(stack))) return false;
        BuildTask t = jobs.get(id), selected = selected(player, stack);
        if (!owns(t, player, stack) || t.target == null || (selected != null && selected.target != null && selected != t)
                || t.deleteRequested) return false;
        if (!t.shortcutDeletable()) {
            player.addChatMessage(new net.minecraft.util.ChatComponentTranslation("structurebuildernh.chat.shortcutState"));
            return false;
        }
        delete(t, player, stack, DimensionManager.getWorld(t.dim));
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
        player.addChatMessage(new net.minecraft.util.ChatComponentTranslation(jobs.containsKey(id)
                ? "structurebuildernh.chat.taskDeleteRefund" : "structurebuildernh.chat.taskDeleted",
                t.target.getDisplayName(), t.x, t.y, t.z));
        return true;
    }

    void delete(BuildTask t, EntityPlayerMP player, ItemStack stack, WorldServer world) {
        boolean current = selected(player, stack) == t;
        t.deleteRequested = true;
        t.dismantleRequested = false;
        t.discardRemovalSite();
        if (t.state != BuildTask.State.REFUND) t.cancel(player, world);
        else if (world != null && player.worldObj == world) t.retryRefund(player, world);
        if (t.savedLink == null && !t.hasReserved()) remove(t, player);
        if (current) select(stack, null);
        if (t.id.equals(tag(stack).getString("lastTask"))) tag(stack).removeTag("lastTask");
        t.changed();
    }

    void create(
            EntityPlayer p,
            ItemStack stack,
            int x,
            int y,
            int z,
            IGregTechTileEntity host,
            ExtendedFacing facing) {
        List<BuildTask> owned = list(p, stack);
        for (BuildTask t : owned)
            if (t.dim == p.dimension
                    && t.x == x
                    && t.y == y
                    && t.z == z
                    && t.state != BuildTask.State.DONE
                    && t.state != BuildTask.State.REFUND && t.state != BuildTask.State.DISMANTLED) {
                if (host != null && !t.existing && p.worldObj instanceof WorldServer)
                    t.attachExisting((WorldServer) p.worldObj);
                if (host != null) t.requestSiteSurvey();
                select(stack, t);
                return;
            }
        BuildTask t =
                new BuildTask(
                        this,
                        UUID.randomUUID().toString(),
                        p.getUniqueID().toString(),
                        tool(stack),
                        p.dimension,
                        x,
                        y,
                        z,
                        facing,
                        host != null);
        t.rememberDimension(p.worldObj);
        if (host != null) {
            ItemStack target = host.getMetaTileEntity().getStackForm(1);
            NBTTagCompound itemTag = target.hasTagCompound()
                    ? target.getTagCompound() : new NBTTagCompound();
            host.getMetaTileEntity().setItemNBT(itemTag);
            if (!itemTag.hasNoTags()) target.setTagCompound(itemTag);
            t.configureTarget(target);
            t.requestSiteSurvey();
        }
        t.key = tag(stack).getString("network");
        t.unlimitedRange = TerminalCardRecipe.hasRange(stack);
        if (host == null) unbound.put(t.owner, t);
        else register(t);
        select(stack, t);
        markDirty();
    }

    void unbind(BuildTask t) {
        jobs.remove(t.id);
        viewers.remove(t.id);
        unbound.put(t.owner, t);
        markDirty();
    }

    void register(BuildTask t) {
        unbound.remove(t.owner, t);
        jobs.put(t.id, t);
        markDirty();
    }

    void remove(BuildTask t, EntityPlayerMP player) {
        jobs.remove(t.id);
        viewers.remove(t.id);
        t.workChunks.release();
        t.ledger.discard();
        t.discardRemovalSite();
        PreviewStream.stop(t, player);
        markDirty();
    }

    void touch(BuildTask t) {
        if (t.target == null) return;
        viewers.put(t.id, turn);
    }

    public void readFromNBT(NBTTagCompound n) {
        for (BuildTask t : jobs.values()) t.workChunks.release();
        jobs.clear();
        watched.clear();
        channelMemory.read(n);
        autoRemoveOwners.clear();
        NBTTagList owners = n.getTagList("autoRemoveOwners", 8);
        for (int i = 0; i < owners.tagCount(); i++) autoRemoveOwners.add(owners.getStringTagAt(i));
        NBTTagList l = n.getTagList("jobs", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            BuildTask t = BuildTask.load(this, l.getCompoundTagAt(i));
            if (t.target != null || t.ledger.count > 0 || t.hasReserved() || t.savedLink != null) jobs.put(t.id, t);
        }
    }

    public void writeToNBT(NBTTagCompound n) {
        NBTTagList l = new NBTTagList();
        for (BuildTask t : jobs.values()) l.appendTag(t.save());
        n.setTag("jobs", l);
        channelMemory.write(n);
        NBTTagList owners = new NBTTagList();
        for (String owner : autoRemoveOwners) owners.appendTag(new NBTTagString(owner));
        n.setTag("autoRemoveOwners", owners);
    }

    boolean claimChunkLoad() {
        if (chunkLoadsLeft <= 0) return false;
        chunkLoadsLeft--;
        return true;
    }

    private void tick() {
        PreviewEdit.drain(this);
        turn++;
        chunkLoadsLeft = 2;
        Map<String, EntityPlayerMP> players = new HashMap<>();
        Map<String, ItemStack> tools = new HashMap<>();
        for (Object o : MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) o;
            String id = p.getUniqueID().toString();
            players.put(id, p);
            for (ItemStack s : p.inventory.mainInventory)
                if (s != null && s.getItem() == BuilderItem.ITEM && s.hasTagCompound())
                    tools.put(id + s.getTagCompound().getString("tool"), s);
        }
        unbound.keySet().retainAll(players.keySet());
        Map<String, List<BuildTask>> running = new LinkedHashMap<>();
        long deadline = System.nanoTime() + 4_000_000L;
        List<BuildTask> active = new ArrayList<>(jobs.values());
        // Rotate the first inspection slot so a large capture cannot starve another task.
        if (!active.isEmpty()) Collections.rotate(active, -Math.floorMod(turn, active.size()));
        for (BuildTask t : active) {
            WorldServer world = DimensionManager.getWorld(t.dim);
            t.rememberDimension(world);
            EntityPlayerMP player = players.get(t.owner);
            ItemStack item = tools.get(t.owner + t.tool);
            if (item != null && t.unlimitedRange != TerminalCardRecipe.hasRange(item)) {
                t.unlimitedRange = TerminalCardRecipe.hasRange(item);
                markDirty();
            }
            boolean present =
                    player != null && !player.isDead && player.worldObj == world && item != null;
            boolean work = t.state == BuildTask.State.INSPECTING_UPGRADE
                    || t.state == BuildTask.State.VERIFYING || t.state == BuildTask.State.SCANNING
                    || t.state == BuildTask.State.QUEUED || t.state == BuildTask.State.BUILDING;
            if (!present || (!work && t.state != BuildTask.State.DISMANTLING)) t.workChunks.release();
            if (world == null) {
                if (t.order != null) t.detach();
                continue;
            }
            if (t.ordering() && (turn % 10 == 0 || t.received)) {
                try {
                    EntityPlayerMP identity =
                            player != null
                                    ? player
                                    : FakePlayerFactory.get(
                                            world, new GameProfile(UUID.fromString(t.owner), null));
                    t.tickOrder(world, identity, present);
                } catch (Exception e) {
                    StructureBuilderNH.LOG.warn("Wireless material order {}", t.id, e);
                    if (t.savedLink == null) {
                        t.clearOrder();
                        t.state = BuildTask.State.MISSING;
                    } else t.detach();
                    t.reason = "orderError";
                    t.changed();
                }
            }
            if (!present) {
                if (t.plan != null && turn - viewers.getOrDefault(t.id, turn - 2000) > 600) t.discardCachedPlan();
                if (t.state == BuildTask.State.QUEUED || t.state == BuildTask.State.BUILDING)
                    t.reason = player == null ? "offline" : "tool";
                continue;
            }
            if ("noEnergy".equals(t.reason) && Math.floorMod(turn - t.id.hashCode(), 20) != 0
                    && (t.state == BuildTask.State.BUILDING || t.state == BuildTask.State.DISMANTLING)) {
                t.workChunks.release();
                continue;
            }
            if (t.state == BuildTask.State.DISMANTLING) {
                running.computeIfAbsent(t.owner + t.tool, k -> new ArrayList<>()).add(t);
                continue;
            }
            t.releaseMissingTemporaryHost(world);
            if (t.state == BuildTask.State.DISMANTLED || t.state == BuildTask.State.TEARDOWN_PAUSED) continue;
            if (t.state == BuildTask.State.REFUND || t.state == BuildTask.State.DONE) {
                // Salvage from an unbound world edit can be bound for the first time.
                // Held ME stock and crafting links stay with the original network.
                if (t.key.isEmpty() && t.held.rows.isEmpty() && t.savedLink == null && !t.keepsUpgradeBinding()
                        && !t.salvage.rows.isEmpty()) t.key = tag(item).getString("network");
                if (Math.floorMod(turn - t.id.hashCode(), 100) == 0
                        && (t.hasReserved() || t.savedLink != null)) t.retryRefund(player, world);
                if (t.state != BuildTask.State.REFUND) continue;
                if (t.deleteRequested && !t.hasReserved() && t.savedLink == null) remove(t,player);
                else t.beginDismantle();
                continue;
            }
            if (!t.ordering()
                    && t.state != BuildTask.State.DONE
                    && t.state != BuildTask.State.REFUND
                    && t.held.rows.isEmpty() && (t.salvage.rows.isEmpty() || t.key.isEmpty())
                    && !t.keepsUpgradeBinding()) t.key = tag(item).getString("network");
            if (!t.upgrading() && !t.salvage.rows.isEmpty() && Math.floorMod(turn - t.id.hashCode(), 100) == 0) {
                if (t.key.isEmpty()) t.key = tag(item).getString("network");
                t.depositManualDrops(player, world);
            }
            boolean viewed =
                    turn - viewers.getOrDefault(t.id, turn - 2000) < 30
                            || t.previewLayers != 0;
            if (!work && !viewed && !t.needsSiteSurvey()) {
                if (t.plan != null && turn - viewers.getOrDefault(t.id, turn - 2000) > 600) t.discardCachedPlan();
                continue;
            }
            if (t.state == BuildTask.State.DONE || t.state == BuildTask.State.REFUND) continue;
            try {
                if (System.nanoTime() < deadline) t.prepare(player, deadline);
                if (t.needsSiteSurvey()) t.inspectSite(world, Math.min(deadline, System.nanoTime() + 2_000_000L));
                if (t.state == BuildTask.State.INSPECTING_UPGRADE) t.inspectUpgrade(player, world, deadline);
                else if (t.state == BuildTask.State.SCANNING) t.scan(player, world, deadline);
                else if (t.state == BuildTask.State.VERIFYING) t.verify(player, world, deadline);
                if (t.state == BuildTask.State.QUEUED || t.state == BuildTask.State.BUILDING)
                    running.computeIfAbsent(t.owner + t.tool, k -> new ArrayList<>()).add(t);
            } catch (RuntimeException e) {
                failure(t, e);
            }
        }
        dispatch(running, tools, players);
        PreviewStream.tick(active, players, tools);
    }

    private void dispatch(Map<String, List<BuildTask>> running, Map<String, ItemStack> tools,
            Map<String, EntityPlayerMP> players) {
        credits.keySet().retainAll(running.keySet());
        dispatchTurns.keySet().retainAll(running.keySet());
        if (running.isEmpty()) return;

        TickBudget installation = new TickBudget(BUILD_NANOS);
        Map<String, TickBudget> dismantling = new HashMap<>();
        Map<String, Integer> blockBudgets = new HashMap<>();
        for (Map.Entry<String, List<BuildTask>> entry : running.entrySet()) {
            String tool = entry.getKey();
            int rate = BuilderItem.rate(tools.get(tool));
            int remainder = rate % 20 + credits.getOrDefault(tool, 0);
            int blocks = rate / 20 + remainder / 20;
            credits.put(tool, remainder % 20);
            if (blocks == 0) continue;

            blockBudgets.put(tool, blocks);
            for (BuildTask task : entry.getValue()) {
                if (task.state == BuildTask.State.DISMANTLING)
                    dismantling.computeIfAbsent(task.owner, key -> new TickBudget(DISMANTLE_NANOS)).tasks++;
                else installation.tasks++;
            }
        }
        if (blockBudgets.isEmpty()) return;
        List<String> terminals = new ArrayList<>(blockBudgets.keySet());
        Collections.sort(terminals);
        Collections.rotate(terminals, -Math.floorMod(terminalTurn++, terminals.size()));
        for (String tool : terminals) {
            List<BuildTask> tasks = running.get(tool);
            tasks.sort(Comparator.comparing(t -> t.id));
            int first = Math.floorMod(dispatchTurns.getOrDefault(tool, 0), tasks.size());
            Collections.rotate(tasks, -first);
            dispatchTurns.put(tool, (first + 1) % tasks.size());
            int blocks = blockBudgets.get(tool), remaining = tasks.size();
            for (BuildTask task : tasks) {
                int share = (blocks + remaining - 1) / remaining--;
                boolean remove = task.state == BuildTask.State.DISMANTLING;
                TickBudget time = remove ? dismantling.get(task.owner) : installation;
                long slice = time.nextShare();
                if (share == 0 || slice <= 0) continue;
                long start = System.nanoTime();
                try {
                    EntityPlayerMP actor = players.get(task.owner);
                    WorldServer world = DimensionManager.getWorld(task.dim);
                    ItemStack item = tools.get(tool);
                    long deadline = start + slice;
                    blocks -= remove ? task.dismantle(actor, world, item, share, deadline)
                            : task.build(actor, world, item, share, deadline);
                } catch (RuntimeException ex) {
                    failure(task, ex);
                } finally {
                    // Charge only this pool, including time spent in a placement that overran its deadline.
                    time.nanos -= System.nanoTime() - start;
                }
            }
        }
    }

    private void failure(BuildTask t, RuntimeException e) {
        StructureBuilderNH.LOG.warn("Cannot process builder task {}", t.id, e);
        t.state = t.dismantleRequested ? BuildTask.State.TEARDOWN_PAUSED : BuildTask.State.BLOCKED;
        t.reason = "unsupported";
        t.discardCachedPlan();
        t.changed();
    }

    public static final class Events {
        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent e) {
            if (e.phase == TickEvent.Phase.END && DimensionManager.getWorld(0) != null)
                get().tick();
        }
    }

    public static void shutdown() {
        if (DimensionManager.getWorld(0) != null)
            for (BuildTask t : get().jobs.values()) {
                t.workChunks.release();
                t.detach();
            }
        com.Goderium.StructureBuilderNH.common.WorldHooks.trackedChanges = null;
        PreviewEdit.clear();
        PreviewStream.clear();
    }
}
