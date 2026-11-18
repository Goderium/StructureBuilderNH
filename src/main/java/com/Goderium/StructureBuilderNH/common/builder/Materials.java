package com.Goderium.StructureBuilderNH.common.builder;

import gregtech.api.util.GTUtility.ItemId;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.LinkedHashMap;
import java.util.Map;

/** Quantities never pass through the vanilla byte-sized ItemStack Count field. */
final class Materials {
    static final class Entry {
        final ItemStack item;
        int count;

        Entry(ItemStack item) {
            this.item = item.copy();
            this.item.stackSize = 1;
        }
    }

    final Map<ItemId, Entry> rows = new LinkedHashMap<>();

    int count(ItemStack stack) {
        Entry e = rows.get(ItemId.create(stack));
        return e == null ? 0 : e.count;
    }

    void add(ItemStack stack, int count) {
        if (count == 0) return;
        ItemId id = ItemId.create(stack);
        Entry e = rows.computeIfAbsent(id, k -> new Entry(stack));
        e.count = Math.addExact(e.count, count);
        if (e.count < 0) throw new IllegalStateException("Negative builder inventory");
        if (e.count == 0) rows.remove(id);
    }

    boolean same(Materials other) {
        if (rows.size() != other.rows.size()) return false;
        for (Entry e : rows.values()) if (other.count(e.item) != e.count) return false;
        return true;
    }

    NBTTagList save() {
        NBTTagList list = new NBTTagList();
        for (Entry e : rows.values()) {
            NBTTagCompound n = e.item.writeToNBT(new NBTTagCompound());
            n.setInteger("amount", e.count);
            list.appendTag(n);
        }
        return list;
    }

    void read(NBTTagList list) {
        rows.clear();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound n = list.getCompoundTagAt(i);
            ItemStack s = ItemStack.loadItemStackFromNBT(n);
            if (s != null && n.getInteger("amount") > 0) add(s, n.getInteger("amount"));
        }
    }

    static boolean equal(ItemStack a, ItemStack b) {
        return a == b
                || a != null
                        && b != null
                        && a.isItemEqual(b)
                        && ItemStack.areItemStackTagsEqual(a, b);
    }
}
