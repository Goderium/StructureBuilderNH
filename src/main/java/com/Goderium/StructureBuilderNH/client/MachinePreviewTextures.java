package com.Goderium.StructureBuilderNH.client;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

import net.minecraft.block.Block;
import net.minecraftforge.common.util.ForgeDirection;

/** Native inactive shell/front icons from a registered prototype, without constructing or attaching a tile. */
final class MachinePreviewTextures {
    static BlockPreviewTextures.Appearance get(Block block, int meta, int machine, int front) {
        if (block == null || front < 0 || front > 5 || machine < 0 || machine >= GregTechAPI.METATILEENTITIES.length)
            return BlockPreviewTextures.FALLBACK;
        IMetaTileEntity prototype = GregTechAPI.METATILEENTITIES[machine];
        if (prototype == null) return BlockPreviewTextures.FALLBACK;
        try {
            BlockPreviewTextures.Layer[][] faces = new BlockPreviewTextures.Layer[6][];
            for (int side = 0; side < 6; side++) {
                ITexture[] textures = prototype.getTexture(prototype.getBaseMetaTileEntity(), ForgeDirection.getOrientation(side),
                        ForgeDirection.getOrientation(front), -1, false, false);
                faces[side] = BlockPreviewTextures.layers(textures, side);
                if (faces[side] == null) return BlockPreviewTextures.FALLBACK;
            }
            return new BlockPreviewTextures.Appearance(block, meta, false, faces, false, false, false, false);
        } catch (RuntimeException | LinkageError requiresTileContext) {
            return BlockPreviewTextures.FALLBACK;
        }
    }

    private MachinePreviewTextures() {}
}
