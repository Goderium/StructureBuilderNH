package com.Goderium.StructureBuilderNH.common.builder;

import com.cricketcraft.chisel.api.carving.CarvingUtils;

import cpw.mods.fml.common.Loader;

import gregtech.api.util.GTUtility.ItemId;

import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import java.util.*;

/** Per-quote cache of directions accepted by the installed Chisel carving registry. */
final class ChiselMaterials {
    private final Map<ItemId, List<ItemStack>> outputs = new HashMap<>();

    List<ItemStack> inputs(ItemStack target) {
        if (!Loader.isModLoaded("chisel") || !plainBlock(target)) return Collections.emptyList();
        List<ItemStack> inputs = new ArrayList<>();
        for (ItemStack candidate : outputs(target)) {
            if (!plainBlock(candidate) || Materials.equal(candidate, target)) continue;
            for (ItemStack result : outputs(candidate)) {
                if (Materials.equal(result, target)) {
                    inputs.add(candidate);
                    break;
                }
            }
        }
        return inputs;
    }

    private List<ItemStack> outputs(ItemStack source) {
        return outputs.computeIfAbsent(
                ItemId.create(source),
                k -> CarvingUtils.getChiselRegistry().getItemsForChiseling(source));
    }

    private static boolean plainBlock(ItemStack s) {
        return s != null
                && s.getItem() instanceof ItemBlock
                && !s.hasTagCompound()
                && s.getMetadata() != OreDictionary.WILDCARD_VALUE;
    }
}
