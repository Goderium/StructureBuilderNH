package com.Goderium.StructureBuilderNH.common.builder;

import gregtech.api.util.GTUtility.ItemId;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Bounded, paired inspection results. Physical tiers and native channel values are deliberately
 * separate.
 */
final class UpgradeBill {
    static final int MAX_ROWS = 256;

    static final class Row {
        final ItemStack previous, target;
        int count;
        // Values are native channel indices. Tiers are physical grades; -1 means a mixed/unknown
        // label.
        int sourceValue, targetValue, sourceTier, targetTier;

        Row(
                ItemStack previous,
                ItemStack target,
                int count,
                int sourceValue,
                int targetValue,
                int sourceTier,
                int targetTier) {
            this.previous = previous.copy();
            this.previous.stackSize = 1;
            this.target = target.copy();
            this.target.stackSize = 1;
            this.count = count;
            this.sourceValue = sourceValue;
            this.targetValue = targetValue;
            this.sourceTier = sourceTier;
            this.targetTier = targetTier;
        }
    }

    final List<Row> rows = new ArrayList<>();
    private final Map<ItemId, Map<ItemId, Row>> pairs = new HashMap<>();
    int count;

    void clear() {
        rows.clear();
        pairs.clear();
        count = 0;
    }

    /**
     * The displayed rows remain; their aggregation index is unnecessary once an inspection is
     * frozen.
     */
    void releaseIndex() {
        pairs.clear();
    }

    boolean add(ChannelUpgrade.Result result) {
        return add(
                result.previous,
                result.target,
                1,
                result.sourceValue,
                result.targetValue,
                result.sourceTier,
                result.targetTier);
    }

    private boolean add(
            ItemStack previous,
            ItemStack target,
            int amount,
            int sourceValue,
            int targetValue,
            int sourceTier,
            int targetTier) {
        if (previous == null || target == null || amount <= 0 || count > Integer.MAX_VALUE - amount)
            return false;
        ItemId oldKey = ItemId.create(previous), newKey = ItemId.create(target);
        Map<ItemId, Row> targets = pairs.get(oldKey);
        Row row = targets == null ? null : targets.get(newKey);
        if (row != null) {
            row.count += amount;
            if (row.sourceValue != sourceValue) row.sourceValue = -1;
            if (row.targetValue != targetValue) row.targetValue = -1;
            if (row.sourceTier != sourceTier) row.sourceTier = -1;
            if (row.targetTier != targetTier) row.targetTier = -1;
            count += amount;
            return true;
        }
        if (rows.size() >= MAX_ROWS) return false;
        row = new Row(previous, target, amount, sourceValue, targetValue, sourceTier, targetTier);
        rows.add(row);
        if (targets == null) {
            targets = new HashMap<>();
            pairs.put(oldKey, targets);
        }
        targets.put(newKey, row);
        count += amount;
        return true;
    }

    boolean same(UpgradeBill other) {
        if (count != other.count || rows.size() != other.rows.size()) return false;
        for (Row row : rows) {
            boolean found = false;
            for (Row next : other.rows)
                if (Materials.equal(row.previous, next.previous)
                        && Materials.equal(row.target, next.target)
                        && row.count == next.count
                        && row.sourceValue == next.sourceValue
                        && row.targetValue == next.targetValue
                        && row.sourceTier == next.sourceTier
                        && row.targetTier == next.targetTier) {
                    found = true;
                    break;
                }
            if (!found) return false;
        }
        return true;
    }

    void copyFrom(UpgradeBill other) {
        clear();
        for (Row row : other.rows)
            add(
                    row.previous,
                    row.target,
                    row.count,
                    row.sourceValue,
                    row.targetValue,
                    row.sourceTier,
                    row.targetTier);
    }

    NBTTagList save() {
        NBTTagList result = new NBTTagList();
        for (Row row : rows) {
            NBTTagCompound n = new NBTTagCompound();
            n.setTag("previous", row.previous.writeToNBT(new NBTTagCompound()));
            n.setTag("target", row.target.writeToNBT(new NBTTagCompound()));
            n.setInteger("count", row.count);
            n.setInteger("sourceValue", row.sourceValue);
            n.setInteger("targetValue", row.targetValue);
            n.setInteger("sourceTier", row.sourceTier);
            n.setInteger("targetTier", row.targetTier);
            result.appendTag(n);
        }
        return result;
    }

    boolean load(NBTTagList data) {
        clear();
        if (data.tagCount() > MAX_ROWS) return false;
        for (int i = 0; i < data.tagCount(); i++) {
            NBTTagCompound n = data.getCompoundTagAt(i);
            if (!add(
                    ItemStack.loadItemStackFromNBT(n.getCompoundTag("previous")),
                    ItemStack.loadItemStackFromNBT(n.getCompoundTag("target")),
                    n.getInteger("count"),
                    n.getInteger("sourceValue"),
                    n.getInteger("targetValue"),
                    n.hasKey("sourceTier") ? n.getInteger("sourceTier") : -1,
                    n.hasKey("targetTier") ? n.getInteger("targetTier") : -1)) {
                clear();
                return false;
            }
        }
        return true;
    }
}
