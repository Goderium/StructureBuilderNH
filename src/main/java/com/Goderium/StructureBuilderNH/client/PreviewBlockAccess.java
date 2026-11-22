package com.Goderium.StructureBuilderNH.client;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.HashMap;
import java.util.Map;

/** Cached projection neighborhood with bounded reads for deeper native icon queries. */
final class PreviewBlockAccess implements IBlockAccess {
    interface Cells {
        /** Zero means air/unknown. This source must read the projection and never acquire world chunks. */
        long state(int x, int y, int z);
    }

    private static final int EDGE = 18, MAX_EXTRA = 4096;
    private final int x, y, z;
    private final Cells source;
    PreviewRegion reads;
    private final long[] near = new long[EDGE * EDGE * EDGE];
    private final Map<Long, Long> extra = new HashMap<>();

    private PreviewBlockAccess(int x, int y, int z, Cells source, boolean eager) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.source = source;
        if (!eager) return;
        for (int dy = -1; dy <= 16; dy++)
            for (int dz = -1; dz <= 16; dz++)
                for (int dx = -1; dx <= 16; dx++)
                    near[slot(dx, dy, dz)] = read(x + dx, y + dy, z + dz) + 1;
    }

    static PreviewBlockAccess snapshot(int x, int y, int z, Cells source) {
        if (source == null) throw new IllegalArgumentException("Missing preview cell source");
        return new PreviewBlockAccess(x, y, z, source, true);
    }

    /** A world mesh reads only the cells its emitted faces and native CTM actually inspect. */
    static PreviewBlockAccess lazy(int x, int y, int z, Cells source) {
        return new PreviewBlockAccess(x, y, z, source, false);
    }

    static long state(Block block, int meta) {
        return block == null || block == Blocks.air ? 0 : state(Block.getIdFromBlock(block), meta);
    }

    static long state(int block, int meta) {
        return block <= 0 ? 0 : ((long) block << 32) | (meta & 0xffffffffL);
    }

    private static int slot(int dx, int dy, int dz) {
        return ((dy + 1) * EDGE + dz + 1) * EDGE + dx + 1;
    }

    private long read(int px, int py, int pz) {
        if (py < 0 || py >= 256) return 0;
        try {
            return source.state(px, py, pz);
        } catch (RuntimeException | LinkageError unsupported) {
            return 0;
        }
    }

    private long at(int px, int py, int pz) {
        if (reads != null) reads.include(px, py, pz);
        int dx = px - x, dy = py - y, dz = pz - z;
        if (dx >= -1 && dx <= 16 && dy >= -1 && dy <= 16 && dz >= -1 && dz <= 16) {
            int i = slot(dx, dy, dz);
            if (near[i] == 0) near[i] = read(px, py, pz) + 1;
            return near[i] - 1;
        }
        // Native column icons may inspect up to fifteen blocks below a section boundary.
        // Retain those reads within the same 27-section neighborhood used for mesh invalidation.
        if (dx < -16 || dx > 31 || dy < -16 || dy > 31 || dz < -16 || dz > 31
                || py < 0 || py >= 256) return 0;
        long key = ((long) (dx + 16) * 48 + dy + 16) * 48 + dz + 16;
        Long value = extra.get(key);
        if (value != null) return value;
        if (extra.size() >= MAX_EXTRA) return read(px, py, pz);
        long state = read(px, py, pz);
        extra.put(key, state);
        return state;
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        long state = at(x, y, z);
        Block block = state == 0 ? Blocks.air : Block.getBlockById((int) (state >>> 32));
        return block == null ? Blocks.air : block;
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) { return (int) at(x, y, z); }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) { return null; }

    @Override
    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int light) { return 0xf000f0; }

    @Override
    public boolean isAirBlock(int x, int y, int z) { return getBlock(x, y, z) == Blocks.air; }

    @Override
    public BiomeGenBase getBiomeGenForCoords(int x, int z) { return BiomeGenBase.plains; }

    @Override
    public int getHeight() { return 256; }

    @Override
    public boolean extendedLevelsInChunkCache() { return false; }

    @Override
    public int isBlockProvidingPowerTo(int x, int y, int z, int side) { return 0; }

    @Override
    public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
        return getBlock(x, y, z).isOpaqueCube();
    }
}
