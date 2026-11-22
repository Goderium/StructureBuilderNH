package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.builder.StructureFluids;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fluids.BlockFluidBase;
import net.minecraftforge.fluids.Fluid;

/** A passive model of a blueprint fluid cell, sampled against the preview's own block access. */
final class PreviewFluid {
    final Fluid type;
    final boolean rises;

    private PreviewFluid(Fluid type) {
        this.type = type;
        rises = type != null && type.getDensity() < 0;
    }

    static BlockPreviewTextures.Appearance resolve(Block block, int meta) {
        Fluid type = StructureFluids.fluid(block);
        int tint = type == null ? block.getRenderColor(meta) : type.getColor();
        boolean bright = type != null && type.getLuminosity() > 0;
        BlockPreviewTextures.Layer[][] faces = new BlockPreviewTextures.Layer[6][];
        for (int side = 0; side < 6; side++) {
            IIcon icon = null;
            try { icon = block.getIcon(side, meta); }
            catch (RuntimeException | LinkageError unsupported) { /* Some blocks only provide a Fluid atlas icon. */ }
            if (icon == null && type != null) {
                icon = side < 2 ? type.getStillIcon() : type.getFlowingIcon();
                if (icon == null) icon = type.getStillIcon();
            }
            if (icon == null || !BlockPreviewTextures.valid(icon)) return BlockPreviewTextures.FALLBACK;
            faces[side] = new BlockPreviewTextures.Layer[] { new BlockPreviewTextures.Layer(icon, tint, bright) };
        }
        return new BlockPreviewTextures.Appearance(block, meta, false, faces, false, false,
                block.getRenderBlockPass() == 1, false, new PreviewFluid(type));
    }

    boolean matches(Block self, Block other) {
        return self == other || type != null && StructureFluids.fluid(other) == type;
    }

    void face(RenderBlocks renderer, Tessellator t, BlockPreviewTextures.Appearance a, int side, int slot, int alpha,
            IBlockAccess access, int x, int y, int z) {
        int nativeSide = BlockPreviewTextures.nativeSide(side);
        BlockPreviewTextures.Layer layer = a.faces[nativeSide][0];
        double height = a.block instanceof BlockLiquid ? 1 - BlockLiquid.getLiquidHeightPercent(a.meta) : .875;
        int color = layer.color;
        if (access != null) {
            try {
                if (matches(a.block, access.getBlock(x, y + (rises ? -1 : 1), z))) height = 1;
                else if (a.block instanceof BlockFluidBase) {
                    float quanta = ((BlockFluidBase) a.block).getQuantaPercentage(access, x, y, z);
                    if (quanta > 0 && quanta <= 1) height = quanta * .875;
                }
                int worldColor = a.block.colorMultiplier(access, x, y, z);
                if ((worldColor & 0xffffff) != 0xffffff) color = worldColor;
            } catch (RuntimeException | LinkageError unsupported) { /* Use the static fluid on a passive block access. */ }
        }
        BlockPreviewTextures.shade(t, color, layer.fullBright, side, alpha);
        renderer.setRenderBounds(0, rises ? 1 - height : 0, 0, 1, rises ? 1 : height, 1);
        try { BlockPreviewTextures.plainFace(renderer, a.block, nativeSide, slot & 15, slot >> 8, slot >> 4 & 15, layer.icon); }
        finally { renderer.setRenderBounds(0, 0, 0, 1, 1, 1); }
    }
}
