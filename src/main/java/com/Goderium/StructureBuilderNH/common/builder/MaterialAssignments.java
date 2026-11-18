package com.Goderium.StructureBuilderNH.common.builder;

import gregtech.api.util.GTUtility.ItemId;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.HashMap;
import java.util.Map;

/**
 * Physical inputs earmarked for each blueprint output. Escrow always contains the original stacks.
 */
final class MaterialAssignments {
    private final Materials outputs = new Materials();
    private final Map<ItemId, Materials> inputs = new HashMap<>();

    void add(ItemStack output, ItemStack input, int amount) {
        if (amount == 0) return;
        ItemId key = ItemId.create(output);
        Materials bill = inputs.computeIfAbsent(key, k -> new Materials());
        bill.add(input, amount);
        outputs.add(output, amount);
        if (bill.rows.isEmpty()) inputs.remove(key);
    }

    int count(ItemStack output) {
        return outputs.count(output);
    }

    ItemStack next(ItemStack output) {
        Materials bill = inputs.get(ItemId.create(output));
        return bill == null ? null : bill.rows.values().iterator().next().item;
    }

    void clear() {
        outputs.rows.clear();
        inputs.clear();
    }

    void addExact(Materials items) {
        for (Materials.Entry e : items.rows.values()) add(e.item, e.item, e.count);
    }

    void merge(MaterialAssignments other) {
        for (Map.Entry<ItemId, Materials.Entry> out : other.outputs.rows.entrySet())
            for (Materials.Entry in : other.inputs.get(out.getKey()).rows.values())
                add(out.getValue().item, in.item, in.count);
    }

    NBTTagList save() {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<ItemId, Materials.Entry> out : outputs.rows.entrySet()) {
            NBTTagCompound n = out.getValue().item.writeToNBT(new NBTTagCompound());
            n.setTag("inputs", inputs.get(out.getKey()).save());
            list.appendTag(n);
        }
        return list;
    }

    void read(NBTTagList list) {
        clear();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound n = list.getCompoundTagAt(i);
            ItemStack output = ItemStack.loadItemStackFromNBT(n);
            if (output == null) continue;
            Materials bill = new Materials();
            bill.read(n.getTagList("inputs", 10));
            for (Materials.Entry e : bill.rows.values()) add(output, e.item, e.count);
        }
    }
}
