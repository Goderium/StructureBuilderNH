package com.Goderium.StructureBuilderNH.common.builder;

import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** A read-only sample for the open channel editor. Never loads chunks for a tooltip. */
final class ChannelSurvey {
    final Blueprint plan;
    final Map<String, Set<Integer>> values = new HashMap<>();
    final Set<String> incomplete = new HashSet<>();
    private final int[] xyz = new int[3];
    private int cursor;

    ChannelSurvey(Blueprint plan) {
        this.plan = plan;
    }

    boolean advance(World world) {
        Set<String> supported = plan.upgradeChannels();
        long deadline = System.nanoTime() + 350_000L;
        for (int n = 0; cursor < plan.size && n < 2048 && System.nanoTime() < deadline; n++) {
            Blueprint.Cell cell = plan.at(cursor++, xyz);
            String channel = plan.upgradeChannel(cell);
            if (!supported.contains(channel)) continue;
            if (!world.blockExists(xyz[0], xyz[1], xyz[2])) {
                incomplete.add(channel);
                continue;
            }
            ChannelUpgrade.Result result = plan.upgrade(cell, world, xyz[0], xyz[1], xyz[2], supported);
            if (result.sourceValue > 0) {
                values.computeIfAbsent(channel, k -> new TreeSet<>()).add(result.sourceValue);
            } else if (!world.isAirBlock(xyz[0], xyz[1], xyz[2])) incomplete.add(channel);
        }
        return cursor == plan.size;
    }
}
