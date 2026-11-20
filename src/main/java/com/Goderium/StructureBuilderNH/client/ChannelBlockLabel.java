package com.Goderium.StructureBuilderNH.client;

import java.util.HashMap;
import java.util.Map;

import com.gtnewhorizon.structurelib.ChannelDescription;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

/** Resolves the representative block for a StructureLib channel in either terminal view. */
public final class ChannelBlockLabel {
    private final Map<Integer, ItemStack> samples = new HashMap<>();
    private final String channelName, range;
    private final int min, max;

    public ChannelBlockLabel(String key, int min, int max) {
        this.min = min;
        this.max = max;
        channelName = channelName(key);
        range = key.isEmpty() ? StatCollector.translateToLocal("structurebuildernh.defaultTier")
                : max == Integer.MAX_VALUE ? StatCollector.translateToLocalFormatted("structurebuildernh.minLevel", min)
                : StatCollector.translateToLocalFormatted("structurebuildernh.levelRange", min, max);
        if (!key.isEmpty() && ChannelDescription.has(key)) {
            for (Map.Entry<ItemStack, Integer> entry : ChannelDescription.get(key).getItems().entrySet()) {
                samples.putIfAbsent(entry.getValue(), entry.getKey());
            }
        }
    }

    public static String channelName(String key) {
        if (key.isEmpty()) return StatCollector.translateToLocal("structurebuildernh.tier");
        String translation = "structurebuildernh.channel." + key;
        return StatCollector.canTranslate(translation) ? StatCollector.translateToLocal(translation) : key;
    }

    public ItemStack sample(int raw, int main) {
        int level = raw == 0 ? main : raw;
        return samples.get(Math.max(min, Math.min(max, level)));
    }

    public String title(ItemStack block) {
        return block == null ? channelName : block.getDisplayName();
    }

    public String detail(ItemStack block) {
        return block == null ? range : channelName;
    }
}
