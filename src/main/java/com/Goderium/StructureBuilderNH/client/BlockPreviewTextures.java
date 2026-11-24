package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.builder.StructureFluids;

import bartworks.common.blocks.BWBlocksGlass;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.Dyes;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.interfaces.IBlockWithTextures;
import gregtech.api.interfaces.IBlockContainer;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.ITexture;
import gregtech.api.metatileentity.implementations.MTEFrame;
import gregtech.api.util.GlassTier;
import gregtech.common.blocks.BlockCasingsAbstract;
import gregtech.common.blocks.BlockDecorativeFrame;
import gregtech.common.blocks.BlockFrameBox;
import gregtech.common.blocks.BlockMachines;
import gregtech.common.render.GTRenderedTexture;
import gregtech.common.render.GTCopiedBlockTextureRender;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBreakable;
import net.minecraft.block.BlockFence;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Passive block models and atlas icons for cached preview geometry. */
final class BlockPreviewTextures {
    static final Appearance FALLBACK = new Appearance(null, -1, true, null);
    private static final int[] SIDES = { 4, 5, 0, 1, 2, 3 };
    private static final int[] SHADE = { 60, 60, 50, 100, 80, 80 };
    private static final Map<Long, Appearance> CACHE = new LinkedHashMap<Long, Appearance>(128, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Appearance> eldest) { return size() > 4096; }
    };
    static final RenderBlocks RENDERER = renderer();
    private static final PreviewLayerAccess LAYER_ACCESS = new PreviewLayerAccess();

    private static RenderBlocks renderer() {
        RenderBlocks renderer = new RenderBlocks();
        renderer.renderMaxX = renderer.renderMaxY = renderer.renderMaxZ = 1;
        renderer.enableAO = false;
        return renderer;
    }

    static final class Layer {
        final IIcon icon;
        final int color;
        final Block source;
        final int meta;
        final boolean worldIcon;
        final boolean fullBright;

        Layer(IIcon icon, int color) { this(icon, color, null, 0, false); }
        Layer(IIcon icon, int color, boolean fullBright) { this(icon, color, null, 0, false, fullBright); }

        Layer(IIcon icon, int color, Block source, int meta, boolean worldIcon) {
            this(icon, color, source, meta, worldIcon, false);
        }

        Layer(IIcon icon, int color, Block source, int meta, boolean worldIcon, boolean fullBright) {
            this.icon = icon; this.color = color; this.source = source; this.meta = meta; this.worldIcon = worldIcon;
            this.fullBright = fullBright;
        }
    }

    static final class Appearance {
        final Block block;
        final int meta;
        final boolean opaque;
        final boolean glass, frame, fence, translucent, worldIcon;
        final boolean browserSolidSurface;
        final PreviewFluid fluid;
        final Layer[][] faces;

        Appearance(Block block, int meta, boolean opaque, Layer[][] faces) {
            this(block, meta, opaque, faces, false, false, false, true);
        }

        Appearance(Block block, int meta, boolean opaque, Layer[][] faces, boolean glass, boolean frame,
                boolean translucent, boolean worldIcon) {
            this(block, meta, opaque, faces, glass, frame, translucent, worldIcon, null);
        }

        Appearance(Block block, int meta, boolean opaque, Layer[][] faces, boolean glass, boolean frame,
                boolean translucent, boolean worldIcon, PreviewFluid fluid) {
            this.block = block;
            this.meta = meta;
            this.opaque = opaque;
            this.faces = faces;
            this.glass = glass;
            this.fluid = fluid;
            this.frame = frame;
            this.fence = isFence(block);
            this.translucent = translucent;
            browserSolidSurface = translucent && !glass && fluid == null && block != null
                    && PreviewCubeProfiles.amberSurface(block, meta);
            this.worldIcon = worldIcon;
        }

        boolean textured() { return faces != null; }

        boolean hasGlowOverlay(int side) {
            if (!opaque || fluid != null) return false;
            Layer[] layers = faces[SIDES[side]];
            return layers.length > 1 && layers[layers.length - 1].fullBright;
        }
    }

    static Appearance get(int id, int meta, int machine) {
        return get(id, meta, machine, -1);
    }

    static Appearance get(int id, int meta, int machine, int front) {
        if (id <= 0 || id > 65535 || meta < 0 || meta > 32767 || machine < -1 || machine > 32767
                || front < -1 || front > 5) return FALLBACK;
        long key = ((long) id << 48) | ((long) meta << 32) | ((machine + 1L) << 8) | (front + 1L & 7);
        Appearance appearance = CACHE.get(key);
        if (appearance == null) {
            if (machine >= 0) {
                appearance = machine < GregTechAPI.METATILEENTITIES.length
                        && GregTechAPI.METATILEENTITIES[machine] instanceof MTEFrame
                        ? frame(((MTEFrame) GregTechAPI.METATILEENTITIES[machine]).mMaterial)
                        : MachinePreviewTextures.get(Block.getBlockById(id), meta, machine, front);
            } else appearance = resolve(Block.getBlockById(id), meta);
            CACHE.put(key, appearance);
        }
        return appearance;
    }

    static void clear() { CACHE.clear(); LAYER_ACCESS.clear(); }

    private static boolean isFence(Block block) {
        return block instanceof BlockFence && block.getRenderType() == 11 && block.getRenderBlockPass() == 0;
    }

    private static boolean isGlass(Block block, int meta, boolean opaque) {
        return block instanceof BWBlocksGlass || !opaque && block.getMaterial() == Material.glass
                || GlassTier.getGlassBlockTier(block, meta) != null;
    }

    private static Appearance resolve(Block block, int meta) {
        if (block == null || block == Blocks.air) return FALLBACK;
        try {
            if (StructureFluids.isFluid(block)) return PreviewFluid.resolve(block, meta);
            // GT casings/coils and BW glass use custom render IDs despite being complete cubes.
            boolean casing = block instanceof BlockCasingsAbstract;
            boolean frame = block instanceof BlockFrameBox || block instanceof BlockDecorativeFrame;
            if (meta > 15 && !(block instanceof BlockDecorativeFrame)
                    && (!(block instanceof BlockFrameBox) || meta > (BlockFrameBox.MATERIAL_MASK | BlockFrameBox.MTE_BIT)))
                return FALLBACK;
            if (block instanceof BlockFrameBox) meta &= BlockFrameBox.MATERIAL_MASK;
            boolean opaque = block.isOpaqueCube();
            boolean glass = isGlass(block, meta, opaque);
            boolean fence = isFence(block);
            if (!casing && !frame && !glass && !fence && !(block instanceof BlockBreakable)
                    && !block.renderAsNormalBlock() && !PreviewCubeProfiles.cube(block, meta)) return FALLBACK;
            Layer[][] faces = new Layer[6][];
            ITexture[][] nativeLayers = (casing || frame) && block instanceof IBlockWithTextures
                    ? ((IBlockWithTextures) block).getTextures(meta) : null;
            int tint = block.getRenderColor(meta);
            Layer[][] separate = nativeLayers == null ? PreviewCubeProfiles.layers(block, meta, tint) : null;
            boolean fullBright = PreviewCubeProfiles.fullBright(block);
            for (int side = 0; side < 6; side++) {
                if (nativeLayers != null) {
                    if (nativeLayers.length != 6 || nativeLayers[side] == null || nativeLayers[side].length > 8)
                        return FALLBACK;
                    faces[side] = layers(nativeLayers[side], side);
                    if (faces[side] == null) return FALLBACK;
                } else if (separate != null) {
                    faces[side] = separate[side];
                    for (Layer layer : faces[side]) if (!valid(layer.icon)) return FALLBACK;
                } else {
                    IIcon icon = block.getIcon(side, meta);
                    if (icon == null || !valid(icon)) return FALLBACK;
                    faces[side] = new Layer[] { new Layer(icon, tint, fullBright) };
                }
            }
            return new Appearance(block, meta, opaque, faces, glass, frame,
                    glass || !frame && block.getRenderBlockPass() == 1, nativeLayers == null && separate == null);
        } catch (RuntimeException | LinkageError unsupported) {
            return FALLBACK;
        }
    }

    static Layer[] layers(ITexture[] textures, int side) {
        if (textures == null || textures.length > 8) return null;
        List<Layer> layers = new ArrayList<>();
        for (ITexture texture : textures) {
            if (texture == null) continue;
            if (texture.getClass() == GTCopiedBlockTextureRender.class) {
                IIcon icon = ((GTCopiedBlockTextureRender) texture).getIcon(side, null);
                if (icon == null || !valid(icon)) return null;
                IBlockContainer container = (IBlockContainer) texture;
                layers.add(new Layer(icon, 0xffffff, container.getBlock(), container.getMeta(), false));
                continue;
            }
            if (texture instanceof IBlockContainer && texture.getClass().getName().equals(
                    "gregtech.common.render.GTCopiedCTMBlockTexture")) {
                IBlockContainer container = (IBlockContainer) texture;
                Block source = container.getBlock();
                if (source == null) return null;
                IIcon icon = source.getIcon(side, container.getMeta());
                if (icon == null || !valid(icon)) return null;
                layers.add(new Layer(icon, 0xffffff, source, container.getMeta(), true));
                continue;
            }
            // Only passive public getters of the exact native implementations are invoked.
            if (texture.getClass() != GTRenderedTexture.class) return null;
            GTRenderedTexture rendered = (GTRenderedTexture) texture;
            short[] rgba = rendered.getRGBA();
            if (rgba == null || rgba.length < 3) return null;
            int color = (rgba[0] & 255) << 16 | (rgba[1] & 255) << 8 | (rgba[2] & 255);
            IIcon base = rendered.getIcon(side, null);
            if (base != null) {
                if (!valid(base)) return null;
                layers.add(new Layer(base, color));
            }
            IIcon overlay = rendered.getIcon(ForgeDirection.getOrientation(side), true, null);
            if (overlay != null) {
                if (!valid(overlay)) return null;
                layers.add(new Layer(overlay, 0xffffff));
            }
        }
        return layers.isEmpty() ? null : layers.toArray(new Layer[0]);
    }

    private static Appearance frame(Materials material) {
        try {
            if (material == null || material.mMetaItemSubID < 0 || material.mIconSet == null
                    || GregTechAPI.sBlockFrames == null) return FALLBACK;
            IIconContainer container = material.mIconSet.mTextures[OrePrefixes.frameGt.getTextureIndex()];
            if (container == null || container.getIcon() == null || !valid(container.getIcon())) return FALLBACK;
            short[] rgba = Dyes.getModulation(-1, material.mRGBa);
            if (rgba == null || rgba.length < 3) return FALLBACK;
            int color = (rgba[0] & 255) << 16 | (rgba[1] & 255) << 8 | (rgba[2] & 255);
            IIcon overlay = container.getOverlayIcon();
            if (overlay != null && !valid(overlay)) return FALLBACK;
            Layer[] layers = overlay == null ? new Layer[] { new Layer(container.getIcon(), color) }
                    : new Layer[] { new Layer(container.getIcon(), color), new Layer(overlay, 0xffffff) };
            Layer[][] faces = { layers, layers, layers, layers, layers, layers };
            return new Appearance(GregTechAPI.sBlockFrames, material.mMetaItemSubID, false, faces, false, true, false, false);
        } catch (RuntimeException | LinkageError unsupported) {
            return FALLBACK;
        }
    }

    static boolean valid(IIcon icon) {
        float u0 = icon.getMinU(), u1 = icon.getMaxU(), v0 = icon.getMinV(), v1 = icon.getMaxV();
        return Float.isFinite(u0) && Float.isFinite(u1) && Float.isFinite(v0) && Float.isFinite(v1)
                && u0 >= 0 && u1 <= 1 && v0 >= 0 && v1 <= 1 && u0 < u1 && v0 < v1;
    }

    static boolean occludes(Appearance neighbor, Appearance current) {
        if (neighbor.fluid != null) return current.fluid != null && current.fluid.matches(current.block, neighbor.block);
        if (neighbor.frame) return current.frame;
        if (neighbor.fence) return false;
        return neighbor.opaque || current.textured() && neighbor.block == current.block && neighbor.meta == current.meta;
    }

    /** Connected frames omit their internal faces. Keep a solid backing face visible through a cutout. */
    static boolean worldOccludes(Appearance neighbor, Appearance current) {
        if (neighbor.fluid != null) return current.fluid != null && current.fluid.matches(current.block, neighbor.block);
        if (neighbor.frame) return current.frame;
        if (neighbor.fence) return false;
        if (neighbor.glass) return current.glass && neighbor.block == current.block && neighbor.meta == current.meta;
        // Passive GT machine appearances have six complete sides. Legacy frame MTEs are flagged above.
        return neighbor.opaque || neighbor.block instanceof BlockMachines;
    }

    static boolean worldOccludes(Block installed, int meta, Appearance current) {
        if (installed == null || installed == Blocks.air) return false;
        if (current.fluid != null && current.fluid.matches(current.block, installed)) return true;
        if (current.frame && (installed instanceof BlockFrameBox || installed instanceof BlockDecorativeFrame)) return true;
        if (current.glass && current.block == installed && current.meta == meta) return true;
        // Some registered tiered glasses share an otherwise opaque block ID with normal casings.
        return installed.isOpaqueCube() && !isGlass(installed, meta, true);
    }

    static int nativeSide(int side) { return SIDES[side]; }

    static void face(Tessellator tessellator, Appearance appearance, int side, int slot, int alpha) {
        face(tessellator, appearance, side, slot, alpha, null, 0, 0, 0);
    }

    static void face(Tessellator tessellator, Appearance appearance, int side, int slot, int alpha,
            IBlockAccess access, int worldX, int worldY, int worldZ) {
        if (appearance.fluid != null) {
            appearance.fluid.face(RENDERER, tessellator, appearance, side, slot, alpha, access, worldX, worldY, worldZ,
                    appearance.fluid.height(appearance, access, worldX, worldY, worldZ));
            return;
        }
        faceLayers(tessellator, appearance, side, slot, alpha, access, worldX, worldY, worldZ, 0,
                appearance.faces[SIDES[side]].length);
    }

    static void faceLayers(Tessellator tessellator, Appearance appearance, int side, int slot, int alpha,
            IBlockAccess access, int worldX, int worldY, int worldZ, int first, int end) {
        int nativeSide = SIDES[side];
        int x = slot & 15, y = slot >> 8, z = slot >> 4 & 15;
        for (int i = first; i < end; i++) {
            Layer layer = appearance.faces[nativeSide][i];
            shade(tessellator, layer, side, alpha);
            IIcon icon = layer.icon;
            if (access != null) {
                Block source = layer.source == null ? appearance.block : layer.source;
                IBlockAccess neighborhood = layer.source == null ? access
                        : LAYER_ACCESS.at(access, worldX, worldY, worldZ, source, layer.meta);
                try {
                    PreviewConnectedTextures.Face connected = appearance.worldIcon || layer.worldIcon
                            ? PreviewConnectedTextures.face(source, icon, neighborhood, worldX, worldY, worldZ, nativeSide)
                            : PreviewConnectedTextures.layer(source, icon, neighborhood, worldX, worldY, worldZ, nativeSide);
                    PreviewConnectedTextures.draw(connected, appearance.block, RENDERER, tessellator, nativeSide, x, y, z);
                    if (appearance.frame) {
                        RENDERER.renderFromInside = true;
                        PreviewConnectedTextures.draw(connected, appearance.block, RENDERER, tessellator, nativeSide, x, y, z);
                    }
                } finally { RENDERER.renderFromInside = false; LAYER_ACCESS.clear(); }
                continue;
            }
            plainFace(RENDERER, appearance.block, nativeSide, x, y, z, icon);
            if (appearance.frame) {
                RENDERER.renderFromInside = true;
                try { plainFace(RENDERER, appearance.block, nativeSide, x, y, z, icon); }
                finally { RENDERER.renderFromInside = false; }
            }
        }
    }

    static void shade(Tessellator tessellator, Layer layer, int side, int alpha) {
        shade(tessellator, layer.color, layer.fullBright, side, alpha);
    }

    static void shade(Tessellator tessellator, int color, boolean fullBright, int side, int alpha) {
        int light = fullBright ? 100 : SHADE[side];
        color = ((color >> 16 & 255) * light / 100 << 16)
                | ((color >> 8 & 255) * light / 100 << 8) | (color & 255) * light / 100;
        tessellator.setColorRGBA_I(color, alpha);
        tessellator.setNormal(side < 2 ? (side & 1) * 2 - 1 : 0,
                side >= 2 && side < 4 ? (side & 1) * 2 - 1 : 0, side >= 4 ? (side & 1) * 2 - 1 : 0);
    }

    static void plainFace(RenderBlocks renderer, Block block, int side, int x, int y, int z, IIcon icon) {
        switch (side) {
            case 0: renderer.renderFaceYNeg(block, x, y, z, icon); break;
            case 1: renderer.renderFaceYPos(block, x, y, z, icon); break;
            case 2: renderer.renderFaceZNeg(block, x, y, z, icon); break;
            case 3: renderer.renderFaceZPos(block, x, y, z, icon); break;
            case 4: renderer.renderFaceXNeg(block, x, y, z, icon); break;
            case 5: renderer.renderFaceXPos(block, x, y, z, icon); break;
            default: break;
        }
    }

    private BlockPreviewTextures() {}
}
