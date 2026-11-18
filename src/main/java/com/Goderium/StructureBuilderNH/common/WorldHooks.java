package com.Goderium.StructureBuilderNH.common;

import com.Goderium.StructureBuilderNH.StructureBuilderNH;
import com.Goderium.StructureBuilderNH.config.RebuildConfig;
import com.Goderium.StructureBuilderNH.util.Bounds;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** Early Minecraft hooks deliberately have no GT/StructureLib references. Inactive outside scoped operations. */
public final class WorldHooks {
    public interface ItemDropSink { boolean accept(World world, ItemStack stack); }
    public static final ThreadLocal<ItemDropSink> DISMANTLE_DROPS = new ThreadLocal<>();
    public interface BlockChange { void changed(World world, int x, int y, int z); }
    public static BlockChange trackedChanges;
    public interface ReadObserver { void read(World world, int x, int y, int z); }
    public interface WriteGuard {
        Boolean setBlock(World world, int x, int y, int z, Block block, int meta, int flags);
        boolean protect(World world, int x, int y, int z);
    }
    public interface WriteObserver {
        void before(World world, int x, int y, int z, Block block, int meta, int flags);
        void after(World world, int x, int y, int z, boolean success);
    }
    public static final ThreadLocal<ReadObserver> READ = new ThreadLocal<ReadObserver>();
    public static final ThreadLocal<WriteGuard> WRITE = new ThreadLocal<WriteGuard>();
    public static final ThreadLocal<WriteObserver> TRACE = new ThreadLocal<WriteObserver>();
}
