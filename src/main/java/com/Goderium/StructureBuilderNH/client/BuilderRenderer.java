package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.BuilderProxy;
import com.Goderium.StructureBuilderNH.common.builder.PreviewStream;
import com.Goderium.StructureBuilderNH.util.Bounds;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
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
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.EXTBlendColor;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class BuilderRenderer extends BuilderProxy {
    private final Queue<PreviewStream.Packet> packets = new ConcurrentLinkedQueue<>();
    private final Map<UUID, View> views = new LinkedHashMap<>();
    private final List<Section> visibleHatches = new ArrayList<>();
    private final List<net.minecraft.util.ChunkCoordinates> visibleControllers = new ArrayList<>();
    private long compilations;
    private String aimedMarker;
    private int aimedColor;

    private static final class View {
        final Map<ChunkPosition, Section> sections = new LinkedHashMap<>();
        Bounds bounds;
        net.minecraft.util.ChunkCoordinates controller, issue;
        AxisAlignedBB box;
        int dim, layers;
        long lastCompilation;

        void clear() {
            for (Section section : sections.values()) section.clear();
            sections.clear();
        }
    }

    private boolean unload;
    private static final int HOST_COLOR = 0xba72fa;
    // Index zero denotes an unnumbered hatch. Numbered StructureLib hints keep the same hue across tasks.
    private static final int[] HATCH_COLORS = {
        0xb1c5ce, 0xf3b54a, 0x55aef8, 0x77dc85, 0xff796c, 0xff99c6,
        0x55e3d4, 0xe4d353, 0x718bff, 0xee9157, 0x9dc85b, 0x56c6f0,
        0xd36994, 0x46b59b, 0xba9958, 0x638ba3, 0xf0dec5
    };
    private static final int[] COLORS = {
        0x317688, 0x61BFB5, 0x345D76, 0x91E2D7, 0x3D7792, 0x63A5BB
    };

    static final class Section {
        final int x, y, z;
        final long[] solid, hatch;
        final byte[] dots;
        long[] confirmed;
        final Section[] near = new Section[6];
        final AxisAlignedBB box;
        int faces, marks;
        boolean dirty = true, hasHatches, hasShell;
        long lastCompilation;

        Section(PreviewStream.Packet p) {
            x = p.x * 16;
            y = p.y * 16;
            z = p.z * 16;
            solid = p.solid;
            hatch = p.hatch;
            dots = p.dots;
            updateProgress(p.confirmed);
            for (long bits : hatch)
                if (bits != 0) {
                    hasHatches = true;
                    break;
                }
            box = AxisAlignedBB.getBoundingBox(x, y, z, x + 16, y + 16, z + 16);
        }

        void updateProgress(long[] mask) {
            confirmed = mask;
            dirty = true;
            hasShell = false;
            for (int i = 0; i < solid.length; i++) {
                if ((solid[i] & ~confirmed[i]) != 0) {
                    hasShell = true;
                    break;
                }
            }
            if (!hasShell && faces != 0) {
                GL11.glDeleteLists(faces, 1);
                faces = 0;
            }
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

    private void remove(UUID id) {
        View view = views.remove(id);
        if (view != null) view.clear();
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (unload) {
            for (View view : views.values()) view.clear();
            views.clear();
            unload = false;
        }
        for (int n = 0; n < 64; n++) {
            PreviewStream.Packet p = packets.poll();
            if (p == null) break;
            if (p.type == 0) {
                remove(p.id);
                continue;
            }
            if (Minecraft.getMinecraft().theWorld == null
                    || Minecraft.getMinecraft().theWorld.provider.dimensionId != p.dim) continue;
            if (p.type == 4) {
                View v = views.computeIfAbsent(p.id, id -> new View());
                v.dim = p.dim;
                v.controller = (p.layers & 1) == 0 ? null : new net.minecraft.util.ChunkCoordinates(p.x, p.y, p.z);
                v.issue = p.issue;
                continue;
            }
            if (p.type == 1) {
                View view = views.computeIfAbsent(p.id, id -> new View());
                if (p.reset) view.clear();
                view.bounds = p.bounds;
                view.box = AxisAlignedBB.getBoundingBox(p.bounds.minX, p.bounds.minY, p.bounds.minZ,
                        p.bounds.maxX + 1.0, p.bounds.maxY + 1.0, p.bounds.maxZ + 1.0);
                view.layers = p.layers;
                view.dim = p.dim;
                continue;
            }
            View view = views.get(p.id);
            if (view == null || view.dim != p.dim) continue;
            ChunkPosition pos = new ChunkPosition(p.x, p.y, p.z);
            if (p.type == 3) {
                Section section = view.sections.get(pos);
                if (section != null) section.updateProgress(p.confirmed);
                continue;
            }
            Section s = new Section(p);
            Section old = view.sections.put(pos, s);
            if (old != null) old.clear();
            for (int side = 0; side < 6; side++) {
                int axis = side / 2, d = (side % 2) * 2 - 1;
                Section near =
                        view.sections.get(
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
        updateAimedMarker();
    }

    private void updateAimedMarker() {
        aimedMarker = null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.currentScreen != null || views.isEmpty()) return;
        boolean enabled = false;
        for (View v : views.values())
            if (v.controller != null) {
                enabled = true;
                break;
            }
        if (!enabled) return;
        net.minecraft.util.Vec3 eye = mc.thePlayer.getPosition(1f), dir = mc.thePlayer.getLook(1f);
        int previousX = Integer.MIN_VALUE, previousY = 0, previousZ = 0;
        for (double distance = 0; distance <= 12; distance += .2) {
            int x = (int) Math.floor(eye.xCoord + dir.xCoord * distance);
            int y = (int) Math.floor(eye.yCoord + dir.yCoord * distance);
            int z = (int) Math.floor(eye.zCoord + dir.zCoord * distance);
            if (x == previousX && y == previousY && z == previousZ) continue;
            previousX = x;
            previousY = y;
            previousZ = z;
            ChunkPosition key = new ChunkPosition(x >> 4, y >> 4, z >> 4);
            for (View v : views.values()) {
                if (v.dim != mc.thePlayer.dimension || v.controller == null) continue;
                if (v.controller.posX == x && v.controller.posY == y && v.controller.posZ == z) {
                    aimedMarker =
                            net.minecraft.util.StatCollector.translateToLocal(
                                    "structurebuildernh.markerHost");
                    aimedColor = HOST_COLOR;
                    return;
                }
                Section s = v.sections.get(key);
                if (s == null || !s.hasHatches) continue;
                int i = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
                long bit = 1L << (i & 63);
                if ((s.hatch[i >> 6] & bit) == 0) continue;
                int rank = Long.bitCount(s.hatch[i >> 6] & (bit - 1));
                for (int word = 0; word < (i >> 6); word++) rank += Long.bitCount(s.hatch[word]);
                int dots = s.dots[rank] & 255;
                aimedMarker =
                        dots == 0
                                ? net.minecraft.util.StatCollector.translateToLocal(
                                        "structurebuildernh.markerHatch")
                                : net.minecraft.util.StatCollector.translateToLocalFormatted(
                                        "structurebuildernh.markerDots", dots);
                aimedColor = HATCH_COLORS[dots];
                return;
            }
        }
    }

    @SubscribeEvent
    public void markerLabel(net.minecraftforge.client.event.RenderGameOverlayEvent.Post e) {
        if (e.type != net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL
                || aimedMarker == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
        mc.fontRendererObj.drawStringWithShadow(
                aimedMarker,
                (e.resolution.getScaledWidth() - mc.fontRendererObj.getStringWidth(aimedMarker))
                        / 2,
                e.resolution.getScaledHeight() / 2 + 10,
                aimedColor);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void render(RenderWorldLastEvent e) {
        if (views.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity eye = mc.renderViewEntity;
        if (eye == null) return;
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
            visibleHatches.clear();
            visibleControllers.clear();
            View nextView = null;
            Section nextSection = null;
            for (View view : views.values()) {
                if (eye.dimension != view.dim) continue;

                if (view.box != null && !camera.isBoundingBoxInFrustum(view.box)) continue;
                if (view.controller != null) visibleControllers.add(view.controller);
                if (view.box == null) continue;
                if ((view.layers & PreviewStream.BOX) != 0) drawBounds(view.bounds);
                if ((view.layers & (PreviewStream.SHELL | PreviewStream.HATCH)) == 0) continue;
                Section candidate = drawSections(view, camera);
                if (candidate != null && (nextView == null || view.lastCompilation < nextView.lastCompilation)) {
                    nextView = view;
                    nextSection = candidate;
                }
            }
            drawMarkers();
            for (View view : views.values())
                if (eye.dimension == view.dim && view.issue != null) drawIssue(view.issue);
            if (nextView != null) {
                nextView.lastCompilation = ++compilations;
                nextSection.lastCompilation = compilations;
                if ((nextView.layers & PreviewStream.HATCH) != 0 && nextSection.hasHatches && nextSection.marks == 0) {
                    nextSection.marks = compile(nextSection, 0, true);
                } else {
                    nextSection.faces = compile(nextSection, nextSection.faces, false);
                    nextSection.dirty = nextSection.faces == 0;
                }
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void drawMarkers() {
        if (visibleHatches.isEmpty() && visibleControllers.isEmpty()) return;
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        // Show buried markers faintly against real blocks, then depth-test the solid voxels against each other.
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDepthFunc(GL11.GL_GREATER);
        GL14.glBlendColor(0, 0, 0, .20f);
        GL11.glBlendFunc(EXTBlendColor.GL_CONSTANT_ALPHA_EXT, EXTBlendColor.GL_ONE_MINUS_CONSTANT_ALPHA_EXT);
        drawVisibleCells();
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);
        drawVisibleCells();
        GL11.glDepthMask(false);
    }

    private void drawVisibleCells() {
        for (Section s : visibleHatches) {
            GL11.glPushMatrix();
            GL11.glTranslatef(s.x, s.y, s.z);
            GL11.glCallList(s.marks);
            GL11.glPopMatrix();
        }
        if (!visibleControllers.isEmpty()) {
            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            for (net.minecraft.util.ChunkCoordinates p : visibleControllers)
                cube(t, p.posX, p.posY, p.posZ, HOST_COLOR);
            t.draw();
        }
    }

    private static void cube(Tessellator t, int x, int y, int z, int color) {
        // A narrow gutter distinguishes adjacent cells without outlines or coplanar faces.
        for (int side = 0; side < 6; side++) {
            int axis = side / 2;
            boolean positive = (side & 1) != 0;
            double p = (axis == 0 ? x : axis == 1 ? y : z) + (positive ? .97 : .03);
            double u = (axis == 0 ? y : axis == 1 ? z : x) + .03;
            double v = (axis == 0 ? z : axis == 1 ? x : y) + .03;
            int light = axis == 0 ? 82 : axis == 1 && positive ? 100 : 69;
            t.setColorOpaque((color >> 16 & 255) * light / 100, (color >> 8 & 255) * light / 100, (color & 255) * light / 100);
            vertex(t, axis, p, u, v);
            vertex(t, axis, p, positive ? u + .94 : u, positive ? v : v + .94);
            vertex(t, axis, p, u + .94, v + .94);
            vertex(t, axis, p, positive ? u : u + .94, positive ? v + .94 : v);
        }
    }

    private static void drawIssue(net.minecraft.util.ChunkCoordinates p) {
        GL11.glPushMatrix();
        GL11.glTranslated(p.posX + .5, p.posY + .5, p.posZ + .5);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glLineWidth(3f);
        GL11.glColor3f(1f, .18f, .15f);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        for (int axis = 0; axis < 3; axis++)
            for (int i = -1; i <= 1; i += 2) for (int j = -1; j <= 1; j += 2) {
                vertex(t, axis, -.53, i * .53, j * .53);
                vertex(t, axis, .53, i * .53, j * .53);
            }
        t.addVertex(-.5, -.5, -.5); t.addVertex(.5, .5, .5);
        t.addVertex(.5, -.5, -.5); t.addVertex(-.5, .5, .5);
        t.addVertex(0, .55, 0); t.addVertex(0, 3, 0);
        t.draw();
        GL11.glPopMatrix();
    }

    private static void drawBounds(Bounds bounds) {
        GL11.glDisable(GL11.GL_BLEND);
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

    private Section drawSections(View view, Frustrum camera) {
        Section candidate = null;
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        // Real blocks win the depth test while a paced packet or mesh rebuild is pending.
        GL11.glPolygonOffset(1f, 1f);
        for (Section s : view.sections.values()) {
            if (!camera.isBoundingBoxInFrustum(s.box)) continue;
            GL11.glPushMatrix();
            GL11.glTranslatef(s.x, s.y, s.z);
            boolean shell = (view.layers & PreviewStream.SHELL) != 0 && s.hasShell;
            boolean hatches = (view.layers & PreviewStream.HATCH) != 0 && s.hasHatches;
            if (hatches && s.marks != 0) visibleHatches.add(s);
            if ((shell && s.dirty || hatches && s.marks == 0)
                    && (candidate == null || s.lastCompilation < candidate.lastCompilation)) candidate = s;
            if (shell && s.faces != 0) {
                GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                GL11.glCallList(s.faces);
                GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            }
            GL11.glPopMatrix();
        }
        return candidate;
    }

    private static int compile(Section s, int previous, boolean hatch) {
        int list = previous == 0 ? GL11.glGenLists(1) : previous;
        if (list == 0) return 0;
        GL11.glNewList(list, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        if (hatch) {
            int hatchIndex = 0;
            for (int word = 0; word < s.hatch.length; word++) {
                for (long bits = s.hatch[word]; bits != 0; bits &= bits - 1) {
                    int i = word * 64 + Long.numberOfTrailingZeros(bits);
                    cube(t, i & 15, i >> 8, i >> 4 & 15, HATCH_COLORS[s.dots[hatchIndex++] & 255]);
                }
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
                            // Confirmed blocks still occlude their neighbours, including across section edges.
                            if (!solid(s, i) || (s.confirmed[i >> 6] & (1L << (i & 63))) != 0) continue;
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

    private static void vertex(Tessellator t, int axis, double p, double u, double v) {
        if (axis == 0) t.addVertex(p, u, v);
        else if (axis == 1) t.addVertex(v, p, u);
        else t.addVertex(u, v, p);
    }
}
