package com.Goderium.StructureBuilderNH.client;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.Tessellator;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLContext;

/** Tests conservative section boxes against this frame's opaque depth, including cutout textures. */
final class WorldPreviewOcclusion {
    private static final int LIMIT = 3;
    private static final int GROUP_MASK = ~63;
    private static final int MIN_GROUP_LISTS = 160;
    private final int[] queries = new int[LIMIT];
    static final class History { int next; boolean hidden; }
    private final History[] last = new History[LIMIT];
    private final Group[] groups = new Group[LIMIT];
    private final List<BuilderRenderer.Section> frontShells = new ArrayList<>(), frontRoles = new ArrayList<>();
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16), model = BufferUtils.createFloatBuffer(16);
    private final double[] near = new double[4];
    private int frame, count, discover;

    /** At most 4 by 4 adjacent sections at the same depth along the dominant viewing axis. */
    private static final class Group {
        final List<BuilderRenderer.Section> shells = new ArrayList<>(), roles = new ArrayList<>();
        int axis, x, y, z, lists, id;
        int x0, y0, z0, x1, y1, z1;
        History history;

        void mark(int value) {
            for (BuilderRenderer.Section s : shells) s.probe = value;
            for (BuilderRenderer.Section s : roles) s.probe = value;
        }
    }

    void prepare(List<BuilderRenderer.Section> plain, List<BuilderRenderer.Section> marked,
            double x, double y, double z) {
        if (plain.size() + marked.size() < 24 || !GLContext.getCapabilities().OpenGL33) return;
        frame++;
        // Previous results only select candidates. They never determine visibility in a later frame.
        for (int i = 0; i < last.length; i++) {
            if (last[i] == null) continue;
            last[i].hidden = available(queries[i]) && samples(queries[i]) == 0;
            last[i] = null;
        }
        discover = 1;
        candidates(plain, false, x, y, z);
        candidates(marked, true, x, y, z);
        boolean checked = false;
        for (int i = 0; i < count; i++) {
            Group g = groups[i];
            // Replaying a few small lists costs less than issuing and resolving a GPU query.
            if (g.lists < MIN_GROUP_LISTS) continue;
            if (!checked) {
                if (!captureNearPlane()) break;
                checked = true;
            }
            double nearest = near[3] + near[0] * (near[0] < 0 ? g.x1 : g.x0)
                    + near[1] * (near[1] < 0 ? g.y1 : g.y0) + near[2] * (near[2] < 0 ? g.z1 : g.z0);
            if (!(nearest > margin(g) * (Math.abs(near[0]) + Math.abs(near[1]) + Math.abs(near[2])))) continue;
            if (queries[i] == 0) queries[i] = GL15.glGenQueries();
            g.id = queries[i];
            if (g.id != 0) { last[i] = g.history; g.mark(g.id); }
        }
    }

    private void candidates(List<BuilderRenderer.Section> source, boolean marked,
            double x, double y, double z) {
        for (BuilderRenderer.Section s : source) {
            if (s.dirty || s.publishedTexture == null) continue;
            double dx = x - s.x - 8, dy = y - s.y - 8, dz = z - s.z - 8;
            int axis = Math.abs(dx) > Math.abs(dy) ? 0 : 1;
            double delta = axis == 0 ? dx : dy;
            if (Math.abs(dz) > Math.abs(delta)) { axis = 2; delta = dz; }
            BuilderRenderer.Section front = s.near[axis * 2 + (delta > 0 ? 1 : 0)];
            if (Math.abs(delta) < 24 || front == null || !front.facesTextured || !front.hasShell) {
                (marked ? frontRoles : frontShells).add(s);
                continue;
            }
            int gx = axis == 0 ? s.x : s.x & GROUP_MASK;
            int gy = axis == 1 ? s.y : s.y & GROUP_MASK;
            int gz = axis == 2 ? s.z : s.z & GROUP_MASK;
            int i = 0;
            while (i < count && !(groups[i].axis == axis && groups[i].x == gx && groups[i].y == gy && groups[i].z == gz)) i++;
            Group g;
            if (i == count) {
                History h = s.probeHistory;
                boolean retry = h != null && h.hidden;
                if (count == LIMIT || !retry && (discover == 0 || h != null && h.next > frame)) continue;
                if (!retry) discover--;
                if (groups[i] == null) groups[i] = new Group();
                g = groups[count++];
                g.axis = axis; g.x = gx; g.y = gy; g.z = gz;
                g.x0 = s.x; g.y0 = s.y; g.z0 = s.z;
                g.x1 = s.x + 16; g.y1 = s.y + 16; g.z1 = s.z + 16;
                g.history = h == null ? new History() : h;
                g.history.hidden = false; g.history.next = frame + 32;
            } else {
                g = groups[i];
                g.x0 = Math.min(g.x0, s.x); g.y0 = Math.min(g.y0, s.y); g.z0 = Math.min(g.z0, s.z);
                g.x1 = Math.max(g.x1, s.x + 16); g.y1 = Math.max(g.y1, s.y + 16); g.z1 = Math.max(g.z1, s.z + 16);
            }
            s.probeHistory = g.history;
            g.lists += Long.bitCount(s.visiblePartMask);
            (marked ? g.roles : g.shells).add(s);
        }
    }

    private boolean captureNearPlane() {
        if (GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0
                || GL15.glGetQueryi(GL33.GL_ANY_SAMPLES_PASSED, GL15.GL_CURRENT_QUERY) != 0
                || GL15.glGetQueryi(GL15.GL_SAMPLES_PASSED, GL15.GL_CURRENT_QUERY) != 0) return false;
        if ((GLContext.getCapabilities().OpenGL43 || GLContext.getCapabilities().GL_ARB_ES3_compatibility)
                && GL15.glGetQueryi(GL43.GL_ANY_SAMPLES_PASSED_CONSERVATIVE, GL15.GL_CURRENT_QUERY) != 0) return false;
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, model);
        // Near clip equation is row Z + row W of projection * modelview.
        for (int c = 0; c < 4; c++) {
            double sum = 0;
            for (int k = 0; k < 4; k++)
                sum += ((double) projection.get(4 * k + 2) + projection.get(4 * k + 3)) * model.get(4 * c + k);
            near[c] = sum;
        }
        return true;
    }

    void issue(BuilderRenderer renderer) {
        int first = 0;
        while (first < count && groups[first].id == 0) first++;
        if (first == count) return;
        for (BuilderRenderer.Section s : frontShells) s.probe = -2;
        for (BuilderRenderer.Section s : frontRoles) s.probe = -2;
        renderer.drawTestedSections(frontShells, frontRoles);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_POLYGON_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);
            GL11.glDisable(GL13.GL_SAMPLE_COVERAGE);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            for (int i = 0; i < 6; i++) GL11.glDisable(GL11.GL_CLIP_PLANE0 + i);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            for (int i = first; i < count; i++) {
                Group g = groups[i];
                if (g.id == 0) continue;
                GL15.glBeginQuery(GL33.GL_ANY_SAMPLES_PASSED, g.id);
                try { box(g); } finally { GL15.glEndQuery(GL33.GL_ANY_SAMPLES_PASSED); }
            }
        } finally { GL11.glPopAttrib(); }
    }

    void draw(BuilderRenderer renderer) {
        for (int i = 0; i < count; i++) {
            Group g = groups[i];
            if (g.id == 0) continue;
            if (available(g.id)) {
                if (samples(g.id) == 0) g.mark(-1);
                else renderer.drawTestedSections(g.shells, g.roles);
            } else {
                GL30.glBeginConditionalRender(g.id, GL30.GL_QUERY_NO_WAIT);
                try { renderer.drawTestedSections(g.shells, g.roles); } finally { GL30.glEndConditionalRender(); }
            }
        }
    }

    private static double margin(Group g) {
        int max = Math.max(Math.max(-g.x0, g.x1), Math.max(Math.max(-g.y0, g.y1), Math.max(-g.z0, g.z1)));
        return Math.max(.0625, 4 * Math.ulp((float)max));
    }

    private static void box(Group g) {
        double m = margin(g);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (int side = 0; side < 6; side++) {
            for (int corner = 0; corner < 4; corner++) {
                boolean p = (side & 1) != 0, u = corner == 1 || corner == 2, v = corner >= 2;
                t.addVertex((side < 2 ? p : u) ? g.x1 + m : g.x0 - m,
                        (side < 2 ? u : side < 4 ? p : v) ? g.y1 + m : g.y0 - m,
                        (side < 4 ? v : p) ? g.z1 + m : g.z0 - m);
            }
        }
        t.draw();
    }

    private static boolean available(int id) { return GL15.glGetQueryObjecti(id, GL15.GL_QUERY_RESULT_AVAILABLE) != 0; }
    private static int samples(int id) { return GL15.glGetQueryObjecti(id, GL15.GL_QUERY_RESULT); }

    void finish() {
        for (BuilderRenderer.Section s : frontShells) s.probe = 0;
        for (BuilderRenderer.Section s : frontRoles) s.probe = 0;
        frontShells.clear();
        frontRoles.clear();
        for (int i = 0; i < count; i++) {
            Group g = groups[i];
            if (g.id != 0) g.mark(0);
            g.shells.clear(); g.roles.clear(); g.lists = 0; g.id = 0; g.history = null;
        }
        count = 0;
    }

    void release() {
        finish();
        for (int i = 0; i < queries.length; i++) {
            last[i] = null;
            if (queries[i] != 0) GL15.glDeleteQueries(queries[i]);
            queries[i] = 0;
        }
    }
}
