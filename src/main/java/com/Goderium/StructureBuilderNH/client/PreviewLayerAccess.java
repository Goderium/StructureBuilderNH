package com.Goderium.StructureBuilderNH.client;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

/** Reused render-thread view of a native copied casing layer; never writes its delegate. */
final class PreviewLayerAccess implements IBlockAccess {
    private IBlockAccess delegate;
    private int x, y, z, meta;
    private Block block;

    IBlockAccess at(IBlockAccess delegate, int x, int y, int z, Block block, int meta) {
        this.delegate = delegate;
        this.x = x; this.y = y; this.z = z;
        this.block = block;
        this.meta = meta;
        return this;
    }

    void clear() { delegate = null; block = null; }

    @Override
    public Block getBlock(int x, int y, int z) {
        return this.x == x && this.y == y && this.z == z ? block : delegate.getBlock(x, y, z);
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        return this.x == x && this.y == y && this.z == z ? meta : delegate.getBlockMetadata(x, y, z);
    }

    @Override public TileEntity getTileEntity(int x, int y, int z) { return null; }
    @Override public int getLightBrightnessForSkyBlocks(int x, int y, int z, int light) {
        return delegate.getLightBrightnessForSkyBlocks(x, y, z, light);
    }
    @Override public boolean isAirBlock(int x, int y, int z) { return getBlock(x, y, z).isAir(this, x, y, z); }
    @Override public BiomeGenBase getBiomeGenForCoords(int x, int z) { return delegate.getBiomeGenForCoords(x, z); }
    @Override public int getHeight() { return delegate.getHeight(); }
    @Override public boolean extendedLevelsInChunkCache() { return delegate.extendedLevelsInChunkCache(); }
    @Override public int isBlockProvidingPowerTo(int x, int y, int z, int side) { return 0; }
    @Override public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
        return getBlock(x, y, z).isOpaqueCube();
    }
}
