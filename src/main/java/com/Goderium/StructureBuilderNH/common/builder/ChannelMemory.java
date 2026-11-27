package com.Goderium.StructureBuilderNH.common.builder;

import com.gtnewhorizon.structurelib.structure.IChannelDefinition;
import com.gtnewhorizon.structurelib.util.MiscUtils;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.util.List;

/** Player preferences. Each task takes its own snapshot when its controller is selected. */
final class ChannelMemory {
    private NBTTagCompound players = new NBTTagCompound();

    void read(NBTTagCompound save) {
        players = save.getCompoundTag("channelMemory");
    }

    void write(NBTTagCompound save) {
        if (!players.hasNoTags()) save.setTag("channelMemory", players);
    }

    private static String machineKey(ItemStack stack) {
        return Item.itemRegistry.getNameForObject(stack.getItem()) + "@" + stack.getMetadata();
    }

    private static NBTTagCompound child(NBTTagCompound parent, String name) {
        if (!parent.hasKey(name, 10)) parent.setTag(name, new NBTTagCompound());
        return parent.getCompoundTag(name);
    }

    void restore(BuildTask task) {
        task.tier = 1;
        task.channels = new NBTTagCompound();
        task.pendingDefaults = new NBTTagCompound();
        if (task.target == null || !players.hasKey(task.owner, 10)) return;
        NBTTagCompound player = players.getCompoundTag(task.owner);
        NBTTagCompound machine = player.getCompoundTag("machines").getCompoundTag(machineKey(task.target));
        if (machine.hasKey("tier")) task.tier = Math.max(1, Math.min(4096, machine.getInteger("tier")));
        task.pendingDefaults = (NBTTagCompound) player.getCompoundTag("common").copy();
        NBTTagCompound local = machine.getCompoundTag("channels");
        for (Object name : MiscUtils.getTagKeys(local)) {
            String key = (String) name;
            task.pendingDefaults.setInteger(key, local.getInteger(key));
        }
        applyKnown(task);
    }

    // Some machines reveal additional channel names only while their blueprint is being read.
    static boolean applyKnown(BuildTask task) {
        if (task.pendingDefaults.hasNoTags()) return false;
        boolean changed = false;
        for (IChannelDefinition d : task.definitions()) {
            String key = d.getChannel();
            if (!task.pendingDefaults.hasKey(key)) continue;
            int value = task.pendingDefaults.getInteger(key);
            if (value > 0) {
                task.channels.setInteger(key, d.normalizeValue(value));
                changed = true;
            }
            task.pendingDefaults.removeTag(key);
        }
        return changed;
    }

    void remember(BuildTask task, int tier, NBTTagCompound levels, List<IChannelDefinition> definitions) {
        NBTTagCompound player = child(players, task.owner);
        NBTTagCompound machine = child(child(player, "machines"), machineKey(task.target));
        NBTTagCompound local = child(machine, "channels");
        NBTTagCompound common = child(player, "common");
        machine.setInteger("tier", tier);
        for (IChannelDefinition d : definitions) {
            String key = d.getChannel();
            int value = levels.getInteger(key);
            local.setInteger(key, value); // Zero explicitly inherits the main tier on this machine.
            if (value > 0) common.setInteger(key, value);
            else common.removeTag(key);
        }
        task.changed();
    }
}
