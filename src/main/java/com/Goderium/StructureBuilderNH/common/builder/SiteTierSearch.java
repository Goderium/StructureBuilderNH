package com.Goderium.StructureBuilderNH.common.builder;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Set;

/** Read-only inference for nested shapes whose native entry point takes the main channel. */
final class SiteTierSearch {
    private static final int MAX_MAIN_CHANNEL = 4096;
    private static final long MAX_PROBE_ENTRIES = 262_144;
    private final BuildTask task;
    private final ItemStack trigger;
    private final Set<String> selectedChannels = new HashSet<>(), candidateChannels = new HashSet<>();
    private final int[] position = new int[3];
    private Blueprint selected, candidate;
    private int tier, nextTier, cursor, distinct, matched, obstructed;
    private long remainingEntries = MAX_PROBE_ENTRIES;

    SiteTierSearch(BuildTask task, ItemStack trigger, Blueprint prior, Set<String> priorChannels) {
        this.task = task;
        this.trigger = trigger;
        tier = trigger.stackSize;
        nextTier = tier + 1;
        selected = prior;
        if (prior != null) selectedChannels.addAll(priorChannels);
    }

    int tier() { return tier; }
    Set<String> channels() { return selectedChannels; }

    /** Returns the selected blueprint, or null while the current tick slice is exhausted. */
    Blueprint advance(EntityPlayerMP actor, long deadline) {
        World world = actor.worldObj;
        if (selected == null) selected = read(actor, tier, selectedChannels);
        if (!advance(selected, selectedChannels, deadline)) return null;
        if (nextTier > MAX_MAIN_CHANNEL || selected.shapeEntries > remainingEntries) return selected;
        if (System.nanoTime() >= deadline) return null;

        if (candidate == null) {
            try {
                candidate = read(actor, nextTier, candidateChannels);
            } catch (RuntimeException | LinkageError unsupported) {
                return selected;
            }
            if (candidate.shapeEntries == selected.shapeEntries && candidate.shapeFingerprint == selected.shapeFingerprint) {
                // Some providers repeat a layout for one channel value before adding another segment.
                candidate = null;
                candidateChannels.clear();
                return nextTier++ > tier + 1 ? selected : null;
            }
            remainingEntries -= candidate.shapeEntries;
            if (remainingEntries < 0) return selected;
        }
        try {
            if (!advance(candidate, candidateChannels, deadline)) return null;
        } catch (RuntimeException | LinkageError unsupported) {
            return selected;
        }

        for (int n = 0; cursor < candidate.size && n < 2048 && System.nanoTime() < deadline; n++, cursor++) {
            Blueprint.Cell cell = candidate.at(cursor, position);
            int x = position[0], y = position[1], z = position[2];
            if (cell.element == null || cell.hatch || cell.air || cell.isFluid() || cell.item == null
                    || selected.projectsBlock(x, y, z)) continue;
            Blueprint.Cell lower = selected.cellAt(x, y, z);
            if (lower != null && lower.choice != null) continue;
            distinct++;
            if (world.blockExists(x, y, z) && !world.isAirBlock(x, y, z)) {
                try {
                    if (BuildTask.identifiesSiteBlock(candidate, cell, world, x, y, z)) matched++;
                    else obstructed++;
                } catch (RuntimeException | LinkageError unavailable) {
                    obstructed++;
                }
            }
        }
        if (cursor < candidate.size) return null;
        if (!supportedExtension(distinct, matched, obstructed)) return selected;
        selected = candidate;
        selectedChannels.clear();
        selectedChannels.addAll(candidateChannels);
        candidate = null;
        candidateChannels.clear();
        tier = nextTier++;
        cursor = distinct = matched = obstructed = 0;
        return null;
    }

    static boolean supportedExtension(int distinct, int matched, int obstructed) {
        return distinct > 0 && matched >= Math.min(4, distinct) && matched > obstructed
                && matched >= (distinct + 1) / 2;
    }

    private Blueprint read(EntityPlayerMP actor, int level, Set<String> channels) {
        ItemStack stack = trigger.copy();
        stack.stackSize = level;
        ChannelProbe.begin(channels);
        try {
            return Blueprint.read(task.target, stack, task.facing, task.x, task.y, task.z, actor, ignored -> false, true);
        } finally {
            ChannelProbe.end();
        }
    }

    private static boolean advance(Blueprint plan, Set<String> channels, long deadline) {
        if (plan.ready) return true;
        ChannelProbe.begin(channels);
        try {
            plan.advance(deadline);
            return plan.ready;
        } finally {
            ChannelProbe.end();
        }
    }
}
