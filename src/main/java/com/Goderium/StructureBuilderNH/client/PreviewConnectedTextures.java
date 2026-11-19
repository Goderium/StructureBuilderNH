package com.Goderium.StructureBuilderNH.client;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;

import team.chisel.ctmlib.ICTMBlock;
import team.chisel.ctmlib.ISubmapManager;
import team.chisel.ctmlib.RenderBlocksCTM;
import team.chisel.ctmlib.TextureSubmap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Passive native CTM resolution; only the returned quads are submitted to the preview tessellator. */
final class PreviewConnectedTextures {
    private static final ThreadLocal<Work> WORK = ThreadLocal.withInitial(Work::new);

    static final class Quad {
        IIcon icon;
        double minX, minY, minZ, maxX, maxY, maxZ;
        boolean fullUV, flipU;
    }

    /** Reused until the next face() call on this thread. Consume it immediately. */
    static final class Face {
        final Quad[] quads = { new Quad(), new Quad(), new Quad(), new Quad() };
        int count;
    }

    private static final class Work {
        final Face face = new Face();
        final RenderBlocks base = new RenderBlocks();
        final Recorder recorder = new Recorder(face);
        IIcon icon;
    }

    static Face face(Block block, IIcon icon, IBlockAccess access, int x, int y, int z, int side) {
        return resolve(block, icon, access, x, y, z, side, true);
    }

    /** A GT background/foreground layer retains its own icon name for matchTiles CTM rules. */
    static Face layer(Block block, IIcon icon, IBlockAccess access, int x, int y, int z, int side) {
        return resolve(block, icon, access, x, y, z, side, false);
    }

    private static Face resolve(Block block, IIcon icon, IBlockAccess access, int x, int y, int z, int side,
            boolean worldIcon) {
        Work work = WORK.get();
        Face result = work.face;
        result.count = 0;
        if (block == null || side < 0 || side >= 6) return result;
        IIcon original = icon;
        try {
            // Custom full-cube render IDs can still supply neighbor-sensitive world icons.
            // In particular, these icons may carry virtual names consumed by compact CTM.
            IIcon world = worldIcon ? block.getIcon(access, x, y, z, side) : icon;
            if (valid(world)) icon = world;
        } catch (RuntimeException | LinkageError unsupported) {
            icon = original;
        }
        if (!valid(icon)) return result;
        work.icon = icon;
        try {
            if (worldIcon && chisel(work, block, access, x, y, z, side)) return result;
        } catch (RuntimeException | LinkageError unsupported) {
            result.count = 0;
        } finally {
            work.base.blockAccess = null;
        }
        icon = work.icon;
        IIcon resolved = Angelica.resolve(work, icon, block, access, x, y, z, side);
        if (result.count == 0) whole(result, valid(resolved) ? resolved : icon);
        return result;
    }

    private static boolean chisel(Work work, Block block, IBlockAccess access, int x, int y, int z, int side) {
        if (!(block instanceof ICTMBlock<?>)) return false;
        ISubmapManager manager = ((ICTMBlock<?>) block).getManager(access, x, y, z, access.getBlockMetadata(x, y, z));
        if (manager == null) return false;
        IIcon nativeIcon = manager.getIcon(access, x, y, z, side);
        if (valid(nativeIcon)) work.icon = nativeIcon;
        // Resolve native icon data, without invoking renderStandardBlock: Chisel's implementation
        // reads Minecraft.theWorld rather than the supplied projection in that method.
        RenderBlocks context = manager.createRenderContext(work.base, block, access);
        if (context == null || context.getClass() != RenderBlocksCTM.class) return false;
        RenderBlocksCTM ctm = (RenderBlocksCTM) context;
        if (ctm.submap == null || ctm.submapSmall == null || ctm.ctm == null) return false;
        int[] indices = ctm.ctm.getSubmapIndices(access, x, y, z, side);
        if (indices == null || indices.length != 4) return false;
        for (int i = 0; i < 4; i++) {
            int index = indices[i];
            TextureSubmap map = index >= 16 ? ctm.submapSmall : ctm.submap;
            if (index >= 16) index -= 16;
            if (index < 0 || map.getWidth() <= 0 || map.getHeight() <= 0) return false;
            IIcon sub = map.getSubIcon(index % map.getWidth(), index / map.getHeight());
            if (!valid(sub)) return false;
            Quad quad = work.face.quads[i];
            quad.icon = sub;
            quad.fullUV = true;
            quad.flipU = false;
            quadrant(quad, side, i);
        }
        work.face.count = 4;
        return true;
    }

    private static void whole(Face face, IIcon icon) {
        Quad quad = face.quads[0];
        quad.icon = icon;
        quad.minX = quad.minY = quad.minZ = 0;
        quad.maxX = quad.maxY = quad.maxZ = 1;
        quad.fullUV = quad.flipU = false;
        face.count = 1;
    }

    private static void quadrant(Quad quad, int side, int quadrant) {
        double u0 = quadrant == 1 || quadrant == 2 ? .5 : 0;
        double v0 = quadrant >= 2 ? .5 : 0;
        double u1 = u0 + .5, v1 = v0 + .5;
        quad.minX = quad.minY = quad.minZ = 0;
        quad.maxX = quad.maxY = quad.maxZ = 1;
        switch (side) {
            case 0: quad.minX = u0; quad.maxX = u1; quad.minZ = v0; quad.maxZ = v1; break;
            case 1: quad.minX = u0; quad.maxX = u1; quad.minZ = 1-v1; quad.maxZ = 1-v0; break;
            case 2: quad.minX = 1-u1; quad.maxX = 1-u0; quad.minY = v0; quad.maxY = v1; break;
            case 3: quad.minX = u0; quad.maxX = u1; quad.minY = v0; quad.maxY = v1; break;
            case 4: quad.minZ = u0; quad.maxZ = u1; quad.minY = v0; quad.maxY = v1; break;
            case 5: quad.minZ = 1-u1; quad.maxZ = 1-u0; quad.minY = v0; quad.maxY = v1; break;
            default: break;
        }
    }

    static void draw(Face face, Block block, RenderBlocks renderer, Tessellator tessellator,
            int side, double x, double y, double z) {
        double minX = renderer.renderMinX, minY = renderer.renderMinY, minZ = renderer.renderMinZ;
        double maxX = renderer.renderMaxX, maxY = renderer.renderMaxY, maxZ = renderer.renderMaxZ;
        boolean flip = renderer.field_152631_f;
        try {
            for (int i = 0; i < face.count; i++) {
                Quad quad = face.quads[i];
                if (quad.fullUV) {
                    chiselQuad(tessellator, quad, side, x, y, z, renderer.renderFromInside);
                    continue;
                }
                renderer.renderMinX = quad.minX; renderer.renderMinY = quad.minY; renderer.renderMinZ = quad.minZ;
                renderer.renderMaxX = quad.maxX; renderer.renderMaxY = quad.maxY; renderer.renderMaxZ = quad.maxZ;
                renderer.field_152631_f = quad.flipU;
                switch (side) {
                    case 0: renderer.renderFaceYNeg(block, x, y, z, quad.icon); break;
                    case 1: renderer.renderFaceYPos(block, x, y, z, quad.icon); break;
                    case 2: renderer.renderFaceZNeg(block, x, y, z, quad.icon); break;
                    case 3: renderer.renderFaceZPos(block, x, y, z, quad.icon); break;
                    case 4: renderer.renderFaceXNeg(block, x, y, z, quad.icon); break;
                    case 5: renderer.renderFaceXPos(block, x, y, z, quad.icon); break;
                    default: break;
                }
            }
        } finally {
            renderer.renderMinX = minX; renderer.renderMinY = minY; renderer.renderMinZ = minZ;
            renderer.renderMaxX = maxX; renderer.renderMaxY = maxY; renderer.renderMaxZ = maxZ;
            renderer.field_152631_f = flip;
        }
    }

    private static void chiselQuad(Tessellator tessellator, Quad quad, int side,
            double x, double y, double z, boolean inside) {
        for (int v = 0; v < 4; v++) {
            int vertex = inside ? 3-v : v;
            boolean right = vertex == 1 || vertex == 2, top = vertex >= 2;
            double px = 0, py = 0, pz = 0;
            switch (side) {
                case 0: px = right ? quad.maxX : quad.minX; py = quad.minY; pz = top ? quad.maxZ : quad.minZ; break;
                case 1: px = right ? quad.maxX : quad.minX; py = quad.maxY; pz = top ? quad.minZ : quad.maxZ; break;
                case 2: px = right ? quad.minX : quad.maxX; py = top ? quad.maxY : quad.minY; pz = quad.minZ; break;
                case 3: px = right ? quad.maxX : quad.minX; py = top ? quad.maxY : quad.minY; pz = quad.maxZ; break;
                case 4: px = quad.minX; py = top ? quad.maxY : quad.minY; pz = right ? quad.maxZ : quad.minZ; break;
                case 5: px = quad.maxX; py = top ? quad.maxY : quad.minY; pz = right ? quad.minZ : quad.maxZ; break;
                default: break;
            }
            tessellator.addVertexWithUV(x+px, y+py, z+pz,
                    right ? quad.icon.getMaxU() : quad.icon.getMinU(),
                    top ? quad.icon.getMinV() : quad.icon.getMaxV());
        }
    }

    private static boolean valid(IIcon icon) {
        if (icon == null) return false;
        float u0 = icon.getMinU(), u1 = icon.getMaxU(), v0 = icon.getMinV(), v1 = icon.getMaxV();
        return Float.isFinite(u0) && Float.isFinite(u1) && Float.isFinite(v0) && Float.isFinite(v1)
                && u0 >= 0 && u1 <= 1 && v0 >= 0 && v1 <= 1 && u0 < u1 && v0 < v1;
    }

    static void clear() { WORK.remove(); }

    /** Captures the native compact processor's face decisions without touching the tessellator. */
    private static final class Recorder extends RenderBlocks {
        private final Face face;
        Recorder(Face face) { this.face = face; }
        void reset(IBlockAccess access) {
            face.count = 0;
            blockAccess = access;
            renderMinX = renderMinY = renderMinZ = 0;
            renderMaxX = renderMaxY = renderMaxZ = 1;
            enableAO = false;
            field_152631_f = false;
            overrideBlockTexture = null;
        }
        private void record(IIcon icon) {
            if (face.count >= 4 || !valid(icon)) throw new IllegalArgumentException("Unsupported compact face");
            Quad quad = face.quads[face.count++];
            quad.icon = icon;
            quad.minX = renderMinX; quad.minY = renderMinY; quad.minZ = renderMinZ;
            quad.maxX = renderMaxX; quad.maxY = renderMaxY; quad.maxZ = renderMaxZ;
            quad.fullUV = false;
            quad.flipU = field_152631_f;
        }
        @Override public void renderFaceYNeg(Block block, double x, double y, double z, IIcon icon) { record(icon); }
        @Override public void renderFaceYPos(Block block, double x, double y, double z, IIcon icon) { record(icon); }
        @Override public void renderFaceZNeg(Block block, double x, double y, double z, IIcon icon) { record(icon); }
        @Override public void renderFaceZPos(Block block, double x, double y, double z, IIcon icon) { record(icon); }
        @Override public void renderFaceXNeg(Block block, double x, double y, double z, IIcon icon) { record(icon); }
        @Override public void renderFaceXPos(Block block, double x, double y, double z, IIcon icon) { record(icon); }
    }

    /** Angelica is optional. Resolve its public API once and avoid per-face reflection/argument boxing. */
    private static final class Angelica {
        private static final MethodHandle ICON, CURRENT, CLEAR, COMPACT, STATE, PROCESSOR, PROCESS, SET;
        static {
            MethodHandle icon=null, current=null, clear=null, compact=null, state=null, processor=null, process=null, set=null;
            try {
                ClassLoader loader = PreviewConnectedTextures.class.getClassLoader();
                Class<?> api = Class.forName("com.prupe.mcpatcher.ctm.CTMUtils", false, loader);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                icon = lookup.findStatic(api, "getBlockIcon", MethodType.methodType(IIcon.class,
                        IIcon.class, Block.class, IBlockAccess.class, int.class, int.class, int.class, int.class));
                Class<?> context = Class.forName("com.prupe.mcpatcher.ctm.CTMUtils$CTMCompactContext", false, loader);
                Class<?> rule = Class.forName("com.prupe.mcpatcher.ctm.TileOverrideImpl$CTMCompact", false, loader);
                Class<?> blockState = Class.forName("com.prupe.mcpatcher.ctm.RenderBlockState", false, loader);
                Class<?> engine = Class.forName("com.prupe.mcpatcher.ctm.CompactCtmQuadProcessor", false, loader);
                current = lookup.findStatic(api, "getCurrentCompact", MethodType.methodType(context))
                        .asType(MethodType.methodType(Object.class));
                clear = lookup.findStatic(api, "clearCurrentCompact", MethodType.methodType(void.class));
                compact = lookup.findVirtual(context, "compact", MethodType.methodType(rule))
                        .asType(MethodType.methodType(Object.class, Object.class));
                state = lookup.findVirtual(context, "renderBlockState", MethodType.methodType(blockState))
                        .asType(MethodType.methodType(Object.class, Object.class));
                processor = lookup.findVirtual(rule, "getProcessor", MethodType.methodType(engine))
                        .asType(MethodType.methodType(Object.class, Object.class));
                process = lookup.findVirtual(engine, "processFace", MethodType.methodType(boolean.class,
                        RenderBlocks.class, blockState, IIcon.class, int.class))
                        .asType(MethodType.methodType(boolean.class, Object.class, RenderBlocks.class, Object.class, IIcon.class, int.class));
                set = lookup.findStatic(api, "setCurrentCompact", MethodType.methodType(void.class, rule, blockState))
                        .asType(MethodType.methodType(void.class, Object.class, Object.class));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                current=clear=compact=state=processor=process=set=null;
            }
            ICON=icon; CURRENT=current; CLEAR=clear; COMPACT=compact; STATE=state; PROCESSOR=processor; PROCESS=process; SET=set;
        }

        static IIcon resolve(Work work, IIcon icon, Block block, IBlockAccess access, int x, int y, int z, int side) {
            if (ICON == null) return icon;
            Object previous = null;
            try {
                if (CURRENT != null) {
                    previous = CURRENT.invokeExact();
                    CLEAR.invokeExact();
                }
                IIcon resolved = (IIcon) ICON.invokeExact(icon, block, access, x, y, z, side);
                Object current = CURRENT == null ? null : CURRENT.invokeExact();
                if (current != null && PROCESS != null) {
                    Object rule = COMPACT.invokeExact(current);
                    Object state = STATE.invokeExact(current);
                    Object processor = PROCESSOR.invokeExact(rule);
                    work.recorder.reset(access);
                    boolean processed = (boolean) PROCESS.invokeExact(processor, (RenderBlocks) work.recorder, state, icon, side);
                    if (!processed) work.face.count = 0;
                }
                return resolved;
            } catch (Throwable unsupported) {
                rethrowFatal(unsupported);
                work.face.count = 0;
                return icon;
            } finally {
                work.recorder.blockAccess = null;
                if (CLEAR != null) try {
                    CLEAR.invokeExact();
                    if (previous != null) {
                        Object rule = COMPACT.invokeExact(previous), state = STATE.invokeExact(previous);
                        SET.invokeExact(rule, state);
                    }
                } catch (Throwable unsupported) {
                    rethrowFatal(unsupported);
                    // A missing optional API must not break preview meshes.
                }
            }
        }
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
        if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
    }

    private PreviewConnectedTextures() {}
}
