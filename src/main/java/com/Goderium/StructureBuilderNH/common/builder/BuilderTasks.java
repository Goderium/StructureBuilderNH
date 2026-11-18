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
    final Map<String, Integer> viewers = new HashMap<>();
    private final Map<String, Integer> credits = new HashMap<>();
    private final Map<String, Integer> dispatchTurns = new HashMap<>();
    private int turn;

    public BuilderTasks(String name) {
        super(name);
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
        tag(stack).setString("selected", t == null ? "" : t.id);
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
                    && t.state != BuildTask.State.REFUND) {
                select(stack, t);
                return;
            }
        // Reuse an untouched draft, so ordinary browsing does not accumulate empty tasks.
        owned.removeIf(
                t -> t.state != BuildTask.State.DRAFT || t.target != null || t.hasReserved());
        for (BuildTask empty : owned) {
            jobs.remove(empty.id);
            viewers.remove(empty.id);
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
        if (host != null) t.target = host.getMetaTileEntity().getStackForm(1);
        t.key = tag(stack).getString("network");
        jobs.put(t.id, t);
        select(stack, t);
        markDirty();
    }

    void touch(BuildTask t) {
        viewers.put(t.id, turn);
    }

    public void readFromNBT(NBTTagCompound n) {
        jobs.clear();
        NBTTagList l = n.getTagList("jobs", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            BuildTask t = BuildTask.load(this, l.getCompoundTagAt(i));
            jobs.put(t.id, t);
        }
    }

    public void writeToNBT(NBTTagCompound n) {
        NBTTagList l = new NBTTagList();
        for (BuildTask t : jobs.values()) l.appendTag(t.save());
        n.setTag("jobs", l);
    }

    private void tick() {
        turn++;
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
        Map<String, List<BuildTask>> running = new LinkedHashMap<>();
        long deadline = System.nanoTime() + 4_000_000L;
        List<BuildTask> active = new ArrayList<>(jobs.values());
        // Rotate the first inspection slot so a large capture cannot starve another task.
        if (!active.isEmpty()) Collections.rotate(active, -Math.floorMod(turn, active.size()));
        for (BuildTask t : active) {
            WorldServer world = DimensionManager.getWorld(t.dim);
            EntityPlayerMP player = players.get(t.owner);
            ItemStack item = tools.get(t.owner + t.tool);
            boolean present =
                    player != null && !player.isDead && player.worldObj == world && item != null;
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
                if (turn - viewers.getOrDefault(t.id, turn - 2000) > 600) t.plan = null;
                if (t.state == BuildTask.State.QUEUED || t.state == BuildTask.State.BUILDING)
                    t.reason = player == null ? "offline" : "tool";
                continue;
            }
            if (t.state == BuildTask.State.REFUND) {
                if (t.key.isEmpty()) t.key = tag(item).getString("network");
                if (Math.floorMod(turn - t.id.hashCode(), 100) == 0
                        && (t.hasReserved() || t.savedLink != null)) t.retryRefund(player, world);
                continue;
            }
            if (!t.ordering()
                    && t.state != BuildTask.State.DONE
                    && t.state != BuildTask.State.REFUND
                    && t.held.rows.isEmpty()) t.key = tag(item).getString("network");
            boolean work =
                    t.state == BuildTask.State.VERIFYING
                            || t.state == BuildTask.State.SCANNING
                            || t.state == BuildTask.State.QUEUED
                            || t.state == BuildTask.State.BUILDING;
            boolean viewed =
                    turn - viewers.getOrDefault(t.id, turn - 2000) < 30
                            || PreviewStream.active(player, t);
            if (!work && !viewed) {
                if (turn - viewers.getOrDefault(t.id, turn - 2000) > 600) t.plan = null;
                continue;
            }
            if (t.state == BuildTask.State.DONE || t.state == BuildTask.State.REFUND) continue;
            try {
                if (System.nanoTime() < deadline) t.prepare(player, deadline);
                if (t.state == BuildTask.State.SCANNING) t.scan(player, world, deadline);
                else if (t.state == BuildTask.State.VERIFYING) t.verify(player, world, deadline);
                if (t.state == BuildTask.State.QUEUED || t.state == BuildTask.State.BUILDING)
                    running.computeIfAbsent(t.owner + t.tool, k -> new ArrayList<>()).add(t);
            } catch (RuntimeException e) {
                failure(t, e);
            }
        }
        for (Map.Entry<String, List<BuildTask>> e : running.entrySet()) {
            ItemStack item = tools.get(e.getKey());
            int rate = BuilderItem.rate(item);
            int credit = credits.getOrDefault(e.getKey(), 0) + rate;
            int budget = credit / 20;
            credits.put(e.getKey(), credit % 20);
            if (budget == 0) continue;
            List<BuildTask> r = e.getValue();
            r.sort(Comparator.comparing(t -> t.id));
            int first = Math.floorMod(dispatchTurns.getOrDefault(e.getKey(), 0), r.size());
            Collections.rotate(r, -first);
            dispatchTurns.put(e.getKey(), (first + 1) % r.size());
            int remaining = r.size();
            long buildDeadline = System.nanoTime() + 3_000_000L;
            for (BuildTask t : r) {
                int share = (budget + remaining - 1) / remaining--;
                EntityPlayerMP actor = players.get(t.owner);
                try {
                    budget -=
                            t.build(actor, DimensionManager.getWorld(t.dim), share, buildDeadline);
                } catch (RuntimeException ex) {
                    failure(t, ex);
                }
            }
        }
        credits.keySet().retainAll(running.keySet());
        dispatchTurns.keySet().retainAll(running.keySet());
        PreviewStream.tick(players);
    }

    private void failure(BuildTask t, RuntimeException e) {
        StructureBuilderNH.LOG.warn("Cannot process builder task {}", t.id, e);
        t.state = BuildTask.State.BLOCKED;
        t.reason = "unsupported";
        t.plan = null;
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
            for (BuildTask t : get().jobs.values()) t.detach();
        PreviewStream.clear();
    }
}
