package com.Goderium.StructureBuilderNH.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

/** Passive parts of verified native cube renderers, without optional-mod linkage or renderer side effects. */
final class PreviewCubeProfiles {
    private static final Probe ACCESS = new Probe();
    private static final ClassValue<Profile> PROFILES = new ClassValue<Profile>() {
        @Override protected Profile computeValue(Class<?> type) { return new Profile(type); }
    };

    private static final class Profile {
        final boolean translucentCube, fullBright, declaresCube;
        final Method base, glow;

        Profile(Class<?> type) {
            // Automagy's shining amber inherits exactly this full-cube TC renderer. A subclass
            // selecting another renderer must establish its own cube contract instead.
            translucentCube = declares(type, "getRenderType", "thaumcraft.common.blocks.BlockCosmeticOpaque")
                    || declares(type, "func_149645_b", "thaumcraft.common.blocks.BlockCosmeticOpaque");
            fullBright = inherits(type, "com.rwtema.extrautils.block.BlockGreenScreen");
            Method normal = method(type, "isNormalCube", IBlockAccess.class, int.class, int.class, int.class);
            declaresCube = normal != null && normal.getDeclaringClass() != Block.class;
            base = iconMethod(type, "getBaseIcon");
            glow = base == null ? null : iconMethod(type, "getGlowIcon");
        }
    }

    static boolean cube(Block block, int meta) {
        Profile profile = PROFILES.get(block.getClass());
        if (profile.translucentCube) return meta >= 0 && meta <= 2;
        if (!profile.declaresCube) return false;
        ACCESS.block = block; ACCESS.meta = meta;
        try {
            return block.isNormalCube(ACCESS, 0, 0, 0)
                    && block.getBlockBoundsMinX() == 0 && block.getBlockBoundsMinY() == 0 && block.getBlockBoundsMinZ() == 0
                    && block.getBlockBoundsMaxX() == 1 && block.getBlockBoundsMaxY() == 1 && block.getBlockBoundsMaxZ() == 1;
        }
        finally { ACCESS.block = null; }
    }

    static boolean fullBright(Block block) { return PROFILES.get(block.getClass()).fullBright; }

    static boolean amberSurface(Block block, int meta) {
        return (meta == 0 || meta == 1) && PROFILES.get(block.getClass()).translucentCube;
    }

    /** The same public base/glow getters used by the native world renderer, resolved once per class. */
    static BlockPreviewTextures.Layer[][] layers(Block block, int meta, int tint) {
        Profile profile = PROFILES.get(block.getClass());
        if (profile.base == null || profile.glow == null) return null;
        BlockPreviewTextures.Layer[][] result = new BlockPreviewTextures.Layer[6][];
        for (int side = 0; side < 6; side++) {
            IIcon base = icon(profile.base, block, side, meta), glow = icon(profile.glow, block, side, meta);
            if (base == null) return null;
            BlockPreviewTextures.Layer background = new BlockPreviewTextures.Layer(base, tint);
            result[side] = glow == null ? new BlockPreviewTextures.Layer[] { background }
                    : new BlockPreviewTextures.Layer[] {
                            background, new BlockPreviewTextures.Layer(glow, 0xffffff, true) };
        }
        return result;
    }

    private static IIcon icon(Method method, Block block, int side, int meta) {
        try { return (IIcon) method.invoke(block, side, meta); }
        catch (IllegalAccessException failure) { return null; }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof VirtualMachineError) throw (VirtualMachineError) cause;
            if (cause instanceof ThreadDeath) throw (ThreadDeath) cause;
            if (cause instanceof Error && !(cause instanceof LinkageError)) throw (Error) cause;
            return null;
        }
    }

    private static Method iconMethod(Class<?> type, String name) {
        Method method = method(type, name, int.class, int.class);
        return method != null && IIcon.class.isAssignableFrom(method.getReturnType()) ? method : null;
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) {
        try { return type.getMethod(name, arguments); }
        catch (NoSuchMethodException | SecurityException unavailable) { return null; }
    }

    private static boolean declares(Class<?> type, String method, String owner) {
        Method declared = method(type, method);
        return declared != null && owner.equals(declared.getDeclaringClass().getName());
    }

    private static boolean inherits(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            if (name.equals(current.getName())) return true;
        return false;
    }

    /** Metadata-only cube proof. It has no real world, chunks or tile entities. */
    private static final class Probe implements IBlockAccess {
        Block block;
        int meta;
        @Override public Block getBlock(int x, int y, int z) {
            return x == 0 && y == 0 && z == 0 && block != null ? block : Blocks.air;
        }
        @Override public int getBlockMetadata(int x, int y, int z) { return x == 0 && y == 0 && z == 0 ? meta : 0; }
        @Override public TileEntity getTileEntity(int x, int y, int z) { return null; }
        @Override public int getLightBrightnessForSkyBlocks(int x, int y, int z, int light) { return 0xf000f0; }
        @Override public boolean isAirBlock(int x, int y, int z) { return getBlock(x, y, z) == Blocks.air; }
        @Override public BiomeGenBase getBiomeGenForCoords(int x, int z) { return BiomeGenBase.plains; }
        @Override public int getHeight() { return 256; }
        @Override public boolean extendedLevelsInChunkCache() { return false; }
        @Override public int isBlockProvidingPowerTo(int x, int y, int z, int side) { return 0; }
        @Override public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
            return getBlock(x, y, z).isOpaqueCube();
        }
    }

    private PreviewCubeProfiles() {}
}
