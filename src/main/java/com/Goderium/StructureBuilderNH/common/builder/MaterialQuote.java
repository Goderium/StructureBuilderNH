package com.Goderium.StructureBuilderNH.common.builder;

import appeng.api.config.Actionable;

import gregtech.api.util.GTUtility.ItemId;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntBiFunction;

/** Plans shared inputs across the whole bill before allowing any decorative substitutions. */
final class MaterialQuote {
    private final BuildTask task;
    private final EntityPlayerMP actor;
    private final Wireless net;
    private final boolean reserve;
    private final ChiselMaterials chisel = new ChiselMaterials();
    private final Materials bag = new Materials();
    private final Map<ItemId, Integer> simulatedME = new HashMap<>();

    MaterialQuote(BuildTask task, EntityPlayerMP actor, Wireless net, boolean reserve) {
        this.task = task;
        this.actor = actor instanceof FakePlayer ? null : actor;
        this.net = net;
        this.reserve = reserve;
    }

    void run() {
        Materials escrow = new Materials();
        for (Materials.Entry e : task.held.rows.values()) escrow.add(e.item, e.count);
        for (Materials.Entry e : task.backpack.rows.values()) escrow.add(e.item, e.count);
        if (actor != null)
            for (ItemStack s : actor.inventory.mainInventory)
                if (s != null && s.stackSize > 0) bag.add(s, s.stackSize);
        task.shortage.rows.clear();
        for (Materials.Entry e : task.needed.rows.values()) task.shortage.add(e.item, e.count);
        task.funding.clear();
        if (!escrow.rows.isEmpty())
            allocate(
                    (item, wanted) -> {
                        int take = Math.min(wanted, escrow.count(item));
                        escrow.add(item, -take);
                        return take;
                    },
                    true);
        allocate(this::takeExternal, reserve);
        task.changed();
    }

    private void allocate(ToIntBiFunction<ItemStack, Integer> take, boolean earmark) {
        for (Materials.Entry e : new ArrayList<>(task.shortage.rows.values()))
            assign(e, e.item, take, earmark);
        for (Materials.Entry e : new ArrayList<>(task.shortage.rows.values())) {
            for (ItemStack input : chisel.inputs(e.item)) {
                assign(e, input, take, earmark);
                if (e.count == 0) break;
            }
        }
    }

    private void assign(
            Materials.Entry demand,
            ItemStack input,
            ToIntBiFunction<ItemStack, Integer> take,
            boolean earmark) {
        int count = take.applyAsInt(input, demand.count);
        task.shortage.add(demand.item, -count);
        if (earmark) task.funding.add(demand.item, input, count);
    }

    private int takeExternal(ItemStack item, int wanted) {
        int carried = Math.min(wanted, bag.count(item));
        if (reserve && carried > 0) {
            int left = carried;
            for (int i = 0; i < actor.inventory.mainInventory.length && left > 0; i++) {
                ItemStack slot = actor.inventory.mainInventory[i];
                if (!Materials.equal(slot, item)) continue;
                int removed = Math.min(slot.stackSize, left);
                actor.inventory.decrStackSize(i, removed);
                left -= removed;
            }
            carried -= left;
            actor.inventory.markDirty();
            task.backpack.add(item, carried);
        }
        bag.add(item, -carried);
        int need = wanted - carried;
        if (net == null || need == 0) return carried;
        if (reserve) {
            int extracted = net.take(item, need, Actionable.MODULATE);
            task.held.add(item, extracted);
            return carried + extracted;
        }
        ItemId key = ItemId.create(item);
        int available =
                simulatedME.computeIfAbsent(
                        key, k -> net.take(item, Integer.MAX_VALUE, Actionable.SIMULATE));
        int taken = Math.min(need, available);
        simulatedME.put(key, available - taken);
        return carried + taken;
    }
}
