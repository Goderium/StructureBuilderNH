package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.builder.PreviewStream;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

/** Projection-only native icon neighborhood, including fifteen-block column queries across section edges. */
final class BrowserBlockAccess implements IBlockAccess {
    private final BrowserScene scene;
    private final BrowserScene.Section[] near = new BrowserScene.Section[27];
    private final int sx, sy, sz;
    private int neighborReads;

    void beginBatch() { neighborReads = 0; }

    int neighborReads() { return neighborReads; }

    static int neighborBit(int dx, int dy, int dz) {
        return 1 << (13 + dx + 3 * dy + 9 * dz);
    }

    BrowserBlockAccess(BrowserScene scene, BrowserScene.Section section) {
        this.scene = scene;
        sx = section.x >> 4;
        sy = section.y >> 4;
        sz = section.z >> 4;
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++)
            near[13 + dx + 3 * dy + 9 * dz] = scene.sections.get(new ChunkPosition(sx + dx, sy + dy, sz + dz));
    }

    BrowserScene.Material material(int x, int y, int z) {
        int dx = (x >> 4) - sx, dy = (y >> 4) - sy, dz = (z >> 4) - sz;
        if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) return null;
        int index = 13 + dx + 3 * dy + 9 * dz;
        if (index != 13) neighborReads |= 1 << index;
        BrowserScene.Section section = near[index];
        return section == null ? null : scene.material(section, (x & 15) | ((z & 15) << 4) | ((y & 15) << 8));
    }

    private PreviewStream.RenderMaterial render(int x, int y, int z) {
        BrowserScene.Material material = material(x, y, z);
        return material == null || material.data.air ? null : material.data.render;
    }

    @Override public Block getBlock(int x, int y, int z) {
        PreviewStream.RenderMaterial render = render(x, y, z);
        Block result = render == null ? null : Block.getBlockById(render.block);
        return result == null ? Blocks.air : result;
    }

    @Override public int getBlockMetadata(int x, int y, int z) {
        PreviewStream.RenderMaterial render = render(x, y, z);
        return render == null ? 0 : render.meta;
    }

    @Override public TileEntity getTileEntity(int x, int y, int z) { return null; }
    @Override public int getLightBrightnessForSkyBlocks(int x, int y, int z, int light) { return 0xf000f0; }
    @Override public boolean isAirBlock(int x, int y, int z) { return getBlock(x, y, z) == Blocks.air; }
    @Override public BiomeGenBase getBiomeGenForCoords(int x, int z) { return BiomeGenBase.plains; }
    @Override public int getHeight() { return 256; }
    @Override public boolean extendedLevelsInChunkCache() { return false; }
    @Override public int isBlockProvidingPowerTo(int x, int y, int z, int side) { return 0; }
    @Override public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
        Block block = getBlock(x, y, z);
        try { return block != Blocks.air && block.isSideSolid(this, x, y, z, side); }
        catch (RuntimeException | LinkageError unsupported) { return fallback; }
    }
}
