package com.Goderium.StructureBuilderNH.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFence;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;

/** Exterior faces of the vanilla fence post and rails, measured in sixteenths of a block. */
final class PreviewFence {
    private static final Quad[][] SHAPES = new Quad[16][];
    private static final RenderBlocks RENDERER = new RenderBlocks();

    static void draw(Tessellator tess, BlockPreviewTextures.Appearance appearance, int slot, int alpha,
            IBlockAccess access, int x, int y, int z, int cutSides) {
        draw(tess, appearance, slot, alpha, access, x, y, z, cutSides, -1);
    }

    static void draw(Tessellator tess, BlockPreviewTextures.Appearance appearance, int slot, int alpha,
            IBlockAccess access, int x, int y, int z, int cutSides, int coveredSides) {
        draw(tess, appearance, slot, alpha, access, x, y, z, cutSides, coveredSides, null);
    }

    static void draw(Tessellator tess, BlockPreviewTextures.Appearance appearance, int slot, int alpha,
            IBlockAccess access, int x, int y, int z, int cutSides, int coveredSides, BuilderRenderer.Section section) {
        BlockFence fence = (BlockFence) appearance.block;
        // The world mesh provides material-aware coverage. Browser slices use native cube coverage.
        int connections = 0, hidden = Math.max(0, coveredSides);
        for (int side = 0; side < 6; side++) {
            ForgeDirection d = ForgeDirection.getOrientation(BlockPreviewTextures.nativeSide(side));
            int nx = x + d.offsetX, ny = y + d.offsetY, nz = z + d.offsetZ;
            Block neighbor = access.getBlock(nx, ny, nz);
            if (d.offsetY == 0) {
                boolean connects;
                try { connects = fence.canConnectFenceTo(access, nx, ny, nz); }
                catch (RuntimeException | LinkageError unsupported) { connects = neighbor == fence; }
                if (connects) connections |= 1 << (side < 2 ? side : side - 2);
            }
            if (neighbor == fence || coveredSides < 0 && neighbor.isOpaqueCube()) hidden |= 1 << side;
        }
        hidden &= ~cutSides;
        Quad[] shape = SHAPES[connections];
        if (shape == null) SHAPES[connections] = shape = bake(connections);
        for (Quad q : shape) {
            if ((q.boundary & hidden) != 0) continue;
            if (section != null) tess.setBrightness(BuilderRenderer.markerData(section, slot, q.side, true));
            int side = BlockPreviewTextures.nativeSide(q.side);
            RENDERER.setRenderBounds(q.x0, q.y0, q.z0, q.x1, q.y1, q.z1);
            // Native rails mirror U on the north/east faces. The centered post has the same
            // mapping; keeping it anchored to the cell preserves UVs when a post face is split.
            RENDERER.field_152631_f = true;
            for (BlockPreviewTextures.Layer layer : appearance.faces[side]) {
                BlockPreviewTextures.shade(tess, layer, q.side, alpha);
                BlockPreviewTextures.plainFace(RENDERER, fence, side, slot & 15, slot >> 8, slot >> 4 & 15, layer.icon);
            }
        }
    }

    private static Quad[] bake(int connections) {
        long[] occupied = new long[64];
        for (int slot = 0; slot < 4096; slot++) {
            int x = slot & 15, y = slot >> 8, z = slot >> 4 & 15;
            boolean post = x >= 6 && x < 10 && z >= 6 && z < 10;
            if (!post) {
                if (!(y >= 6 && y < 9 || y >= 12 && y < 15)) continue;
                boolean railX = z >= 7 && z < 9 && (x < 6 && (connections & 1) != 0 || x >= 10 && (connections & 2) != 0);
                boolean railZ = x >= 7 && x < 9 && (z < 6 && (connections & 4) != 0 || z >= 10 && (connections & 8) != 0);
                if (!railX && !railZ) continue;
            }
            occupied[slot >> 6] |= 1L << (slot & 63);
        }
        List<Quad> faces = new ArrayList<>();
        HatchMesh.emit(occupied, new long[64], new byte[4096], null, (side, plane, u, v, width, height, ignored) ->
                faces.add(new Quad(side, plane, u, v, width, height)));
        return faces.toArray(new Quad[0]);
    }

    private static final class Quad {
        final int side, boundary;
        final double x0, y0, z0, x1, y1, z1;

        Quad(int side, int plane, int u, int v, int width, int height) {
            this.side = side;
            boundary = plane == 0 || plane == 16 ? 1 << side : 0;
            int axis = side / 2;
            double p = plane / 16d, u0 = u / 16d, v0 = v / 16d;
            double u1 = (u + width) / 16d, v1 = (v + height) / 16d;
            x0 = axis == 0 ? p : axis == 1 ? v0 : u0;
            x1 = axis == 0 ? p : axis == 1 ? v1 : u1;
            y0 = axis == 1 ? p : axis == 0 ? u0 : v0;
            y1 = axis == 1 ? p : axis == 0 ? u1 : v1;
            z0 = axis == 2 ? p : axis == 0 ? v0 : u0;
            z1 = axis == 2 ? p : axis == 0 ? v1 : u1;
        }
    }

    private PreviewFence() {}
}
