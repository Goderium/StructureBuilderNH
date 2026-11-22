package com.Goderium.StructureBuilderNH.common.builder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.IFluidBlock;

/** Classification shared by definition capture and client projection. An ItemBlock is not required. */
public final class StructureFluids {
    public static boolean isFluid(Block block) {
        return block != null && block != Blocks.air && (block instanceof BlockLiquid || block instanceof IFluidBlock
                || block.getMaterial() != null && block.getMaterial().isLiquid() || FluidRegistry.lookupFluidForBlock(block) != null);
    }

    static boolean isFluidItem(ItemStack item) {
        return item != null && item.getItem() instanceof ItemBlock && isFluid(Block.getBlockFromItem(item.getItem()));
    }

    public static Fluid fluid(Block block) {
        if (block == null || block == Blocks.air) return null;
        if (block == Blocks.flowing_water || block == Blocks.water) return FluidRegistry.WATER;
        if (block == Blocks.flowing_lava || block == Blocks.lava) return FluidRegistry.LAVA;
        if (block instanceof IFluidBlock) {
            Fluid fluid = ((IFluidBlock) block).getFluid();
            if (fluid != null) return fluid;
        }
        return FluidRegistry.lookupFluidForBlock(block);
    }

    private StructureFluids() {}
}
