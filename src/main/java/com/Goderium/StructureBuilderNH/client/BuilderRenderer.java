package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.BuilderProxy;
import com.Goderium.StructureBuilderNH.common.builder.PreviewStream;
import com.Goderium.StructureBuilderNH.util.Bounds;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.ChunkPosition;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class BuilderRenderer extends BuilderProxy {
    private final Queue<PreviewStream.Packet> packets = new ConcurrentLinkedQueue<>();
    private final Map<ChunkPosition, Section> sections = new LinkedHashMap<>();
    private Bounds bounds;
    private int dim, layers;
    private boolean unload;
    private static final int[] COLORS = {
        0x317688, 0x61BFB5, 0x345D76, 0x91E2D7, 0x3D7792, 0x63A5BB
    };

    static final class Section {
        final int x, y, z;
        final long[] solid, hatch;
        final Section[] near = new Section[6];
        final AxisAlignedBB box;
        int faces, marks;
        boolean dirty = true, hasHatches;

        Section(PreviewStream.Packet p) {
            x = p.x * 16;
            y = p.y * 16;
            z = p.z * 16;
            solid = p.solid;
            hatch = p.hatch;
            for (long bits : hatch)
                if (bits != 0) {
                    hasHatches = true;
                    break;
                }
            box = AxisAlignedBB.getBoundingBox(x, y, z, x + 16, y + 16, z + 16);
        }

        void clear() {
            if (faces != 0) GL11.glDeleteLists(faces, 1);
            if (marks != 0) GL11.glDeleteLists(marks, 1);
        }
    }

    @Override
    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    @Override
    public void preview(PreviewStream.Packet p) {
        packets.add(p);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload e) {
        if (e.world.isRemote) {
            packets.clear();
            unload = true;
        }
    }

    private void clear() {
        for (Section s : sections.values()) s.clear();
        sections.clear();
        bounds = null;
        layers = 0;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (unload) {
            clear();
            unload = false;
        }
        for (int n = 0; n < 64; n++) {
            PreviewStream.Packet p = packets.poll();
            if (p == null) break;
            if (p.type == 0) {
                clear();
                continue;
            }
            if (Minecraft.getMinecraft().theWorld == null
                    || Minecraft.getMinecraft().theWorld.provider.dimensionId != p.dim) continue;
            if (p.type == 1) {
                if (p.reset) clear();
                bounds = p.bounds;
                layers = p.layers;
                dim = p.dim;
                continue;
            }
            if (bounds == null || dim != p.dim) continue;
            Section s = new Section(p);
            Section old = sections.put(new ChunkPosition(p.x, p.y, p.z), s);
            if (old != null) old.clear();
            for (int side = 0; side < 6; side++) {
                int axis = side / 2, d = (side % 2) * 2 - 1;
                Section near =
                        sections.get(
                                new ChunkPosition(
                                        p.x + (axis == 0 ? d : 0),
                                        p.y + (axis == 1 ? d : 0),
                                        p.z + (axis == 2 ? d : 0)));
                if (near != null) {
                    s.near[side] = near;
                    near.near[side ^ 1] = s;
                    near.dirty = true;
                }
            }
        }
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent e) {
        if (bounds == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity eye = mc.renderViewEntity;
        if (eye == null || eye.dimension != dim) return;
        double x = eye.lastTickPosX + (eye.posX - eye.lastTickPosX) * e.partialTicks;
        double y = eye.lastTickPosY + (eye.posY - eye.lastTickPosY) * e.partialTicks;
        double z = eye.lastTickPosZ + (eye.posZ - eye.lastTickPosZ) * e.partialTicks;
        Frustrum camera = new Frustrum();
        camera.setPosition(x, y, z);
        GL11.glPushAttrib(
                GL11.GL_ENABLE_BIT
                        | GL11.GL_DEPTH_BUFFER_BIT
                        | GL11.GL_COLOR_BUFFER_BIT
                        | GL11.GL_CURRENT_BIT
                        | GL11.GL_LINE_BIT
                        | GL11.GL_POLYGON_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(-x, -y, -z);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDepthMask(false);
            if ((layers & PreviewStream.BOX) != 0) {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glLineWidth(2);
                GL11.glColor3f(.3f, .9f, 1);
                Tessellator t = Tessellator.instance;
                t.startDrawing(GL11.GL_LINES);
                for (int axis = 0; axis < 3; axis++)
                    for (int j = 0; j < 4; j++) {
                        double[] a = {bounds.minX, bounds.minY, bounds.minZ},
                                b = {bounds.maxX + 1., bounds.maxY + 1., bounds.maxZ + 1.};
                        int u = (axis + 1) % 3, v = (axis + 2) % 3;
                        if ((j & 1) != 0) a[u] = b[u];
                        if ((j & 2) != 0) a[v] = b[v];
                        t.addVertex(a[0], a[1], a[2]);
                        a[axis] = b[axis];
                        t.addVertex(a[0], a[1], a[2]);
                    }
                t.draw();
            }
            if ((layers & (PreviewStream.SHELL | PreviewStream.HATCH)) == 0) return;
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            int compiled = 0;
            for (Section s : sections.values()) {
                if (!camera.isBoundingBoxInFrustum(s.box)) continue;
                GL11.glPushMatrix();
                GL11.glTranslatef(s.x, s.y, s.z);
                if ((layers & PreviewStream.SHELL) != 0) {
                    if (s.dirty && compiled < 1) {
                        s.faces = compile(s, s.faces, false);
                        s.dirty = false;
                        compiled++;
                    }
                    if (s.faces != 0) GL11.glCallList(s.faces);
                }
                if (s.hasHatches && (layers & PreviewStream.HATCH) != 0) {
                    if (s.marks == 0 && compiled < 1) {
                        s.marks = compile(s, 0, true);
                        compiled++;
                    }
                    if (s.marks != 0) GL11.glCallList(s.marks);
                }
                GL11.glPopMatrix();
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static int compile(Section s, int previous, boolean hatch) {
        int list = previous == 0 ? GL11.glGenLists(1) : previous;
        if (list == 0) return 0;
        GL11.glNewList(list, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        t.startDrawing(hatch ? GL11.GL_LINES : GL11.GL_QUADS);
        if (hatch) {
            t.setColorRGBA_I(0xffb449, 255);
            for (int word = 0; word < 64; word++)
                for (long bits = s.hatch[word]; bits != 0; bits &= bits - 1) {
                    int i = word * 64 + Long.numberOfTrailingZeros(bits);
                    double x = (i & 15) + .5, y = (i >> 8) + .5, z = (i >> 4 & 15) + .5;
                    t.addVertex(x - .5, y, z);
                    t.addVertex(x + .5, y, z);
                    t.addVertex(x, y - .5, z);
                    t.addVertex(x, y + .5, z);
                    t.addVertex(x, y, z - .5);
                    t.addVertex(x, y, z + .5);
                }
        } else {
            int[] rows = new int[16];
            for (int side = 0; side < 6; side++) {
                int axis = side / 2, dir = (side & 1) * 2 - 1;
                t.setColorRGBA_I(COLORS[side], 100);
                for (int plane = 0; plane < 16; plane++) {
                    Arrays.fill(rows, 0);
                    for (int v = 0; v < 16; v++)
                        for (int u = 0; u < 16; u++) {
                            int i = slot(axis, plane, u, v);
                            if (!solid(s, i)) continue;
                            int n = plane + dir;
                            Section next = n < 0 || n > 15 ? s.near[side] : s;
                            if (next == null || !solid(next, slot(axis, n & 15, u, v)))
                                rows[v] |= 1 << u;
                        }
                    for (int v = 0; v < 16; v++)
                        while (rows[v] != 0) {
                            int u = Integer.numberOfTrailingZeros(rows[v]);
                            int w = Integer.numberOfTrailingZeros(~(rows[v] >>> u)),
                                    mask = ((1 << w) - 1) << u,
                                    h = 1;
                            while (v + h < 16 && (rows[v + h] & mask) == mask) h++;
                            for (int k = 0; k < h; k++) rows[v + k] &= ~mask;
                            int p = plane + (side & 1);
                            vertex(t, axis, p, u, v);
                            vertex(t, axis, p, dir > 0 ? u + w : u, dir > 0 ? v : v + h);
                            vertex(t, axis, p, u + w, v + h);
                            vertex(t, axis, p, dir > 0 ? u : u + w, dir > 0 ? v + h : v);
                        }
                }
            }
        }
        t.draw();
        GL11.glEndList();
        return list;
    }

    private static boolean solid(Section s, int i) {
        return (s.solid[i >> 6] & (1L << (i & 63))) != 0;
    }

    private static int slot(int axis, int p, int u, int v) {
        return axis == 0
                ? p + (v << 4) + (u << 8)
                : axis == 1 ? v + (u << 4) + (p << 8) : u + (p << 4) + (v << 8);
    }

    private static void vertex(Tessellator t, int axis, int p, int u, int v) {
        if (axis == 0) t.addVertex(p, u, v);
        else if (axis == 1) t.addVertex(v, p, u);
        else t.addVertex(u, v, p);
    }
}
