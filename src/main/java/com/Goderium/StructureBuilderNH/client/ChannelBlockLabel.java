package com.Goderium.StructureBuilderNH.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import blockrenderer6343.client.utils.ConstructableData;
import com.gtnewhorizon.structurelib.ChannelDescription;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import cpw.mods.fml.common.Loader;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

/** Resolves the representative block for a StructureLib channel. */
public final class ChannelBlockLabel {
    private final Map<Integer, ItemStack> samples = new HashMap<>();
    private final String channelName;
    private final int min;
    private final boolean main;

    public ChannelBlockLabel(String key, int min) {
        this.min = min;
        main = key.isEmpty();
        channelName = channelName(key);
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

    public ItemStack sample(int raw, int inherited, int max) {
        int level = raw == 0 ? inherited : raw;
        return samples.get(Math.max(min, Math.min(max, level)));
    }

    public String name() {
        return channelName;
    }

    public String range(int max) {
        if (main) return StatCollector.translateToLocal("structurebuildernh.defaultTier");
        if (min == max) return StatCollector.translateToLocalFormatted("structurebuildernh.fixedLevel", min);
        if (max == Integer.MAX_VALUE) return StatCollector.translateToLocalFormatted("structurebuildernh.minLevel", min);
        return StatCollector.translateToLocalFormatted("structurebuildernh.levelRange", min, max);
    }

    static Map<String, Integer> scannedMaximums(int controller) {
        if (controller <= 0 || controller >= GregTechAPI.METATILEENTITIES.length
                || !Loader.isModLoaded("blockrenderer6343")) return Collections.emptyMap();
        try {
            return NeiLimits.read(GregTechAPI.METATILEENTITIES[controller]);
        } catch (LinkageError unsupported) {
            return Collections.emptyMap();
        }
    }

    // The scan data is optional and belongs to the client.
    private static final class NeiLimits {
        static Map<String, Integer> read(IMetaTileEntity controller) {
            if (!(controller instanceof IConstructable)) return Collections.emptyMap();
            ConstructableData data = ConstructableData.getTierData((IConstructable) controller);
            return data.getChannelMaxTierMap();
        }
    }
}
