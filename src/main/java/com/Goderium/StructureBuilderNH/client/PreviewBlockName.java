package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.builder.StructureFluids;
import com.gtnewhorizon.structurelib.structure.ISpecialItemBlock;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.BlockDecorativeFrame;
import gregtech.common.blocks.BlockFrameBox;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StatCollector;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

/** Resolves display text against the client's active language, without resolving a texture or touching a plan. */
final class PreviewBlockName {
    private PreviewBlockName() {}

    static String machine(int id) {
        try {
            return id > 0 && id < GregTechAPI.METATILEENTITIES.length && GregTechAPI.METATILEENTITIES[id] != null
                    ? stack(GregTechAPI.METATILEENTITIES[id].getStackForm(1)) : "";
        } catch (RuntimeException | LinkageError unsupported) { return ""; }
    }

    static String item(int id, int damage) {
        Item item = id < 0 ? null : Item.getItemById(id);
        return item == null ? "" : stack(new ItemStack(item, 1, damage));
    }

    private static String stack(ItemStack stack) {
        try {
            return stack == null || stack.getItem() == null ? "" : text(stack.getDisplayName());
        } catch (RuntimeException | LinkageError unsupported) { return ""; }
    }

    private static String fluid(Block block) {
        if (!StructureFluids.isFluid(block)) return "";
        Fluid fluid = StructureFluids.fluid(block);
        if (fluid == null) return text(block.getLocalizedName());
        FluidStack sample = new FluidStack(fluid, 1000);
        String name = text(fluid.getLocalizedName(sample));
        if (!name.isEmpty() && (!name.equals(fluid.getUnlocalizedName(sample)) || StatCollector.canTranslate(name))) return name;
        String fallback = text(block.getLocalizedName());
        return fallback.isEmpty() || fallback.equals(block.getUnlocalizedName() + ".name") ? name : fallback;
    }

    private static String text(String name) { return name == null ? "" : name; }

    static String planned(int id, int meta, int machine, int item, int damage) {
        Block block = id <= 0 ? null : Block.getBlockById(id);
        String name = "";
        try {
            if (block != null && block != Blocks.air) name = fluid(block);
            if (name.isEmpty() && item >= 0) name = item(item, damage);
            if (name.isEmpty() && machine > 0) name = machine(machine);
            if (name.isEmpty() && block != null && block != Blocks.air) {
                if (block instanceof BlockFrameBox) return text(BlockFrameBox.getLocalizedName(meta));
                if (block instanceof BlockDecorativeFrame) return text(((BlockDecorativeFrame) block).getLocalizedName(meta));
                Item form = Item.getItemFromBlock(block);
                if (form != null) {
                    int itemMeta = form instanceof ISpecialItemBlock
                            ? ((ISpecialItemBlock) form).getItemMetaFromBlockMeta(block, meta) : meta;
                    name = stack(new ItemStack(form, 1, itemMeta));
                }
                if (name.isEmpty()) name = text(block.getLocalizedName());
            }
        } catch (RuntimeException | LinkageError unsupported) { /* Keep any usable identity from this cell. */ }
        return name;
    }

    static String installed(World world, EntityPlayer player, ChunkCoordinates at, MovingObjectPosition crosshair) {
        int x = at.posX, y = at.posY, z = at.posZ;
        Block block = world.getBlock(x, y, z);
        if (block == null || block == Blocks.air) return "";
        try {
            String name = fluid(block);
            if (!name.isEmpty()) return name;
            TileEntity tile = world.getTileEntity(x, y, z);
            if (tile instanceof IGregTechTileEntity) {
                name = machine(((IGregTechTileEntity) tile).getMetaTileID());
                if (!name.isEmpty()) return name;
            }
            MovingObjectPosition hit = crosshair;
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                    || hit.blockX != x || hit.blockY != y || hit.blockZ != z)
                hit = new MovingObjectPosition(x, y, z, 1, Vec3.createVectorHelper(x + .5, y + .5, z + .5));
            name = stack(block.getPickBlock(hit, world, x, y, z, player));
            if (!name.isEmpty()) return name;
        } catch (RuntimeException | LinkageError unsupported) { /* Fall back to the real block and metadata. */ }
        return planned(Block.getIdFromBlock(block), world.getBlockMetadata(x, y, z), -1, -1, 0);
    }
}
