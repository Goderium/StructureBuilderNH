package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.builder.BrowserPacket;
import com.Goderium.StructureBuilderNH.common.builder.PreviewStream;
import com.Goderium.StructureBuilderNH.util.Bounds;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.ChunkPosition;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Window-owned, projection-only structure browser. All public coordinates are controller-relative. */
@SideOnly(Side.CLIENT)
public final class BrowserRenderer implements AutoCloseable {
    private static final int BATCH = 256, BATCHES = 4096 / BATCH;
    private static final int PROXY_SECTIONS = 8;
    private static final int PROXY_THRESHOLD = 384, PICK_STEPS = 8192;
    private static final int CONSTANT_ALPHA = 0x8003, ONE_MINUS_CONSTANT_ALPHA = 0x8004;
    private static final long FRAME_WINDOW = 16_000_000L, FRAME_BUDGET = 12_000_000L;
    private static final Deque<State> RETIRED = new ArrayDeque<>();
    private static final Deque<Framebuffer> RETIRED_IMAGES = new ArrayDeque<>();
    private static final int[] DX = { -1, 1, 0, 0, 0, 0 };
    private static final int[] DY = { 0, 0, -1, 1, 0, 0 };
    private static final int[] DZ = { 0, 0, 0, 0, -1, 1 };
    private static final int[] FACE_COLOR = { 0x477e8c, 0x68a6ad, 0x416773, 0x9dc9c5, 0x598998, 0x75a9ad };
    private final BrowserPacket.Assembler assembler = new BrowserPacket.Assembler();
    private final BrowserCamera camera = new BrowserCamera(), mapCamera = new BrowserCamera();
    private final Viewport modelViewport = new Viewport(), mapViewport = new Viewport();
    private State state, fallback;
    private boolean closed, hatches, sliced, sliceContext;
    private boolean cameraTouched, mapTouched;
    private Object focusKey;
    private String externalError = "";
    private UUID errorId;
    private int errorRevision = -1;
    private int focusGeneration;
    private Pick selected;
    private long frameWindow = -1, materialVersion = -1, sliceVersion = -1;
    private long compiledBatches, compiledCells;
    private long submittedCalls;
    private long cachedFrames, renderedFrames;
    private List<MaterialEntry> materialList = Collections.emptyList(), sliceList = Collections.emptyList();

    /** Immutable GUI row. The id also works with setFocus/locateMaterial. Counts include all recorded cells. */
    public static final class MaterialEntry {
        public final int id, x, y, z;
        public final ItemStack item;
        public final long count;
        public final boolean air, hatch;
        public final String name;

        private MaterialEntry(BrowserScene.Row row) {
            BrowserPacket.Material m = row.material.data;
            id = m.id;
            item = m.item;
            count = row.count;
            x = row.x;
            y = row.y;
            z = row.z;
            air = m.air;
            hatch = m.hatch;
            name = displayName(row.material);
        }
    }

    private static String displayName(BrowserScene.Material material) {
        if (material.displayName == null) {
            String name = "";
            try {
                if (material.data.item != null) name = material.data.item.getDisplayName();
                else if (!material.data.air) {
                    BlockPreviewTextures.Appearance model = appearance(material);
                    if (model.fluid != null) name = model.fluid.type != null ? model.fluid.type.getLocalizedName()
                            : model.block.getLocalizedName();
                }
            } catch (RuntimeException | LinkageError unsupported) { /* Keep the generic block label. */ }
            material.displayName = name;
        }
        return material.displayName;
    }

    /** A recorded model voxel; options preserve explicit null/leave-empty candidates. Never mutates a task. */
    public static final class Pick {
        public final int x, y, z, materialId, group, dots;
        public final String name;
        public final ItemStack item;
        public final ItemStack[] options;
        public final String piece, groupKey, hatchName;
        public final String[] hatchKeys, channels;
        public final char symbol;
        public final boolean air, hatch, groupSelectable;

        private Pick(int x, int y, int z, BrowserScene.Material material, BrowserPacket.Group choices, String label) {
            BrowserPacket.Material m = material.data;
            this.x = x;
            this.y = y;
            this.z = z;
            materialId = m.id;
            name = displayName(material);
            group = m.group;
            dots = m.dots;
            item = m.item;
            piece = m.piece;
            symbol = m.symbol;
            air = m.air;
            hatch = m.hatch;
            hatchName = label;
            hatchKeys = m.hatchKeys;
            channels = m.channels;
            options = choices == null ? new ItemStack[0] : choices.options;
            groupKey = choices == null ? "" : choices.key;
            groupSelectable = choices != null && choices.groupSelectable;
        }
    }

    /** GUI calls this with its window-scoped MUI packet, including fragments. No global network listener. */
    public void accept(BrowserPacket packet) {
        if (closed || packet == null) return;
        if (packet.id != null && packet.id.equals(errorId) && packet.revision < errorRevision) return;
        BrowserPacket complete;
        try { complete = assembler.accept(packet); }
        catch (RuntimeException malformed) {
            externalError = "Invalid browser data";
            assembler.clear();
            return;
        }
        if (complete == null) return;
        if (complete.id.equals(errorId) && complete.revision < errorRevision) return;
        if (complete.type == BrowserPacket.ERROR) {
            if (state != null && state.scene.id.equals(complete.id) && complete.revision < state.scene.revision) return;
            externalError = complete.error == null || complete.error.trim().isEmpty()
                    ? "Browser capture failed" : complete.error;
            errorId = complete.id;
            errorRevision = complete.revision;
            if (state != null) state.scene.ready = false;
            return;
        }
        if (complete.type == BrowserPacket.HEADER) {
            if (state != null && state.scene.id.equals(complete.id) && complete.revision < state.scene.revision) return;
            if (state != null && state.scene.matches(complete) && !complete.projection.reset) {
                externalError = "";
                errorId = null;
                Bounds before = state.scene.bounds;
                int labelsBefore = state.scene.labelVersion;
                boolean wasReady = state.scene.ready;
                state.scene.header(complete);
                if (labelsBefore != state.scene.labelVersion) {
                    java.util.Set<BrowserScene.Section> affected = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    for (Geometry geometry : state.geometry.values()) if (geometry.section.hasHatches) affected.add(geometry.section);
                    for (BrowserScene.Section section : affected) state.invalidate(section);
                    if (selected != null) selected = at(selected.x, selected.y, selected.z);
                }
                Bounds next = state.scene.bounds;
                boolean finalFit = complete.ready && !wasReady;
                if (!before.equals(next) || finalFit) {
                    double diagonal = Math.sqrt(square(next.maxX - (double) next.minX + 1)
                            + square(next.maxY - (double) next.minY + 1) + square(next.maxZ - (double) next.minZ + 1)) * .56;
                    if (!cameraTouched && (finalFit || diagonal > camera.radius * 1.45)) camera.reset(next);
                    if (!mapTouched && (finalFit || diagonal > mapCamera.radius * 1.45)) resetMapCamera();
                }
                return;
            }
            State previous = state;
            externalError = "";
            errorId = null;
            state = new State(new BrowserScene(complete));
            if (fallback != null) retire(fallback);
            boolean newSession = previous == null || !previous.scene.id.equals(complete.id);
            fallback = newSession ? null : previous;
            if (newSession && previous != null) retire(previous);
            Bounds bounds = state.scene.bounds;
            camera.setStructureFacing(complete.facing);
            if (newSession) {
                cameraTouched = mapTouched = false;
                camera.reset(bounds);
                resetMapCamera();
            } else {
                // A cached plan can be complete on its first header, without a later ready transition.
                if (complete.ready && !cameraTouched) camera.reset(bounds);
                state.scene.setSlice(previous.scene.axis, previous.scene.layer);
                mapCamera.x = state.scene.axis == 0 ? state.scene.layer + .5 : mapCamera.x;
                mapCamera.y = state.scene.axis == 1 ? state.scene.layer + .5 : mapCamera.y;
                mapCamera.z = state.scene.axis == 2 ? state.scene.layer + .5 : mapCamera.z;
            }
            selected = null;
            focusKey = null;
            focusGeneration++;
            materialVersion = sliceVersion = -1;
            materialList = sliceList = Collections.emptyList();
        } else if (state != null) state.scene.enqueue(complete);
    }

    public String name() { return state == null ? "" : state.scene.name; }
    public Bounds bounds() { return state == null ? null : state.scene.bounds; }
    public int loadedSections() { return state == null ? 0 : state.scene.indexedSections; }
    public int totalSections() { return state == null ? 0 : state.scene.totalSections; }
    public int capturedCells() { return state == null ? 0 : state.scene.capturedCells; }
    public boolean ready() { return externalError.isEmpty() && state != null && state.scene.complete(); }
    public boolean sliceReady() { return state != null && state.scene.planeComplete(); }
    public long compiledBatches() { return compiledBatches; }
    public long compiledCells() { return compiledCells; }
    public long submittedCalls() { return submittedCalls; }
    public long cachedFrames() { return cachedFrames; }
    public long renderedFrames() { return renderedFrames; }
    public String error() { return !externalError.isEmpty() ? externalError : state == null ? "" : state.scene.error; }
    public Pick selection() { return selected; }
    public Pick at(int x, int y, int z) {
        BrowserScene.Material material = state == null ? null : state.scene.at(x, y, z);
        return material == null ? null : makePick(x, y, z, material);
    }
    public void select(Pick pick) { selected = pick; }
    public void setHatches(boolean value) { hatches = value; }
    public boolean sliced() { return sliced; }
    public int sliceAxis() { return state == null ? 1 : state.scene.axis; }
    public int sliceLayer() { return state == null ? 0 : state.scene.layer; }
    public int sliceMin() { return state == null ? 0 : state.scene.min(state.scene.axis); }
    public int sliceMax() { return state == null ? 0 : state.scene.max(state.scene.axis); }
    public void setSliceContext(boolean value) { sliceContext = value; }

    public List<BrowserPacket.Group> groups() {
        return state == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(state.scene.groups.values()));
    }

    public List<MaterialEntry> materials() {
        if (state != null && materialVersion != state.scene.rowsVersion) {
            materialList = rows(state.scene.rows);
            materialVersion = state.scene.rowsVersion;
        }
        return materialList;
    }

    public List<MaterialEntry> sliceMaterials() {
        if (state != null && sliceVersion != state.scene.planeVersion) {
            sliceList = rows(state.scene.planeRows);
            sliceVersion = state.scene.planeVersion;
        }
        return sliceList;
    }

    private static List<MaterialEntry> rows(Map<Object, BrowserScene.Row> rows) {
        List<MaterialEntry> result = new ArrayList<>(rows.size());
        for (BrowserScene.Row row : rows.values()) result.add(new MaterialEntry(row));
        return Collections.unmodifiableList(result);
    }

    public void setSlice(int axis, int layer) {
        if (state == null) return;
        axis = Math.max(0, Math.min(2, axis));
        layer = Math.max(state.scene.min(axis), Math.min(state.scene.max(axis), layer));
        boolean newAxis = axis != state.scene.axis;
        boolean changed = axis != state.scene.axis || layer != state.scene.layer;
        if (!sliced || changed) {
            state.retirePlane();
            if (focusKey != null) { state.retireFocus(); focusGeneration++; state.focusCursor = 0; }
        }
        sliced = true;
        if (changed) {
            state.scene.setSlice(axis, layer);
            state.planeCursor = 0;
            state.planeGeneration++;
            if (newAxis) resetMapCamera();
        }
        mapCamera.x = axis == 0 ? layer + .5 : mapCamera.x;
        mapCamera.y = axis == 1 ? layer + .5 : mapCamera.y;
        mapCamera.z = axis == 2 ? layer + .5 : mapCamera.z;
    }

    public void clearSlice() {
        if (sliced && state != null) {
            state.retirePlane();
            state.planeCursor = 0;
            if (focusKey != null) { state.retireFocus(); focusGeneration++; state.focusCursor = 0; }
        }
        sliced = false;
        mapViewport.width = 0;
    }
    public void rotate(double dx, double dy) { cameraTouched = true; camera.rotate(dx, dy); }
    public void zoom(double steps) { cameraTouched = true; camera.zoom(steps); prioritizeCenter(); }
    public void pan(double dx, double dy) { cameraTouched = true; camera.pan(dx, dy); prioritizeCenter(); }
    public void zoomSlice(double steps) { mapTouched = true; mapCamera.zoom(steps); }
    public void panSlice(double dx, double dy) { mapTouched = true; mapCamera.pan(dx, dy); }
    public void resetCamera() {
        cameraTouched = mapTouched = false;
        if (state != null) camera.reset(state.scene.bounds);
        resetMapCamera();
    }

    public void focus(Pick pick) {
        if (pick == null) return;
        selected = pick;
        cameraTouched = true;
        camera.x = pick.x + .5;
        camera.y = pick.y + .5;
        camera.z = pick.z + .5;
        camera.zoom = Math.max(camera.zoom, Math.min(12, camera.radius / 4));
        if (state != null) queuePriority(pick);
    }

    private void prioritizeCenter() {
        if (state == null || modelViewport.width == 0) return;
        Pick center = pick(modelViewport.x + modelViewport.width / 2, modelViewport.y + modelViewport.height / 2);
        if (center != null) queuePriority(center);
    }

    private void queuePriority(Pick pick) {
        int x = (pick.x + state.scene.originX) >> 4, y = (pick.y + state.scene.originY) >> 4;
        int z = (pick.z + state.scene.originZ) >> 4;
        state.priority = state.scene.sections.get(new ChunkPosition(x, y, z));
        state.priorityNeighbors.clear();
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            BrowserScene.Section section = state.scene.sections.get(new ChunkPosition(x + dx, y + dy, z + dz));
            if (section != null && (!sliced || state.scene.intersects(section, state.scene.axis, state.scene.layer)))
                state.priorityNeighbors.addLast(section);
        }
    }

    public void setFocus(int materialId) {
        BrowserScene.Material material = state == null ? null : state.scene.materials.get(materialId);
        Object next = material == null ? null : material.key;
        if (Objects.equals(focusKey, next)) return;
        focusKey = next;
        focusGeneration++;
        if (state != null) { state.retireFocus(); state.focusCursor = 0; }
    }

    public void clearFocus() {
        if (focusKey != null) {
            focusKey = null;
            focusGeneration++;
            if (state != null) state.retireFocus();
        }
    }

    public Pick locateMaterial(int materialId) {
        if (state == null) return null;
        BrowserScene.Material material = state.scene.materials.get(materialId);
        BrowserScene.Row row = material == null ? null : state.scene.rows.get(material.key);
        if (row == null) return null;
        Pick pick = makePick(row.x, row.y, row.z, row.material);
        focus(pick);
        return pick;
    }

    /** Absolute, scaled GUI pixels. Restores caller viewport, scissor, matrices, texture and GL state. */
    public void draw(int x, int y, int width, int height, int mouseX, int mouseY) {
        drawViewport(modelViewport, camera, x, y, width, height, false);
    }

    public void drawSlice(int x, int y, int width, int height, int mouseX, int mouseY) {
        drawViewport(mapViewport, mapCamera, x, y, width, height, true);
    }

    public Pick pick(int mouseX, int mouseY) { return pick(modelViewport, camera, mouseX, mouseY, sliced); }
    public Pick pickSlice(int mouseX, int mouseY) { return pick(mapViewport, mapCamera, mouseX, mouseY, true); }

    private void drawViewport(Viewport viewport, BrowserCamera cam, int x, int y, int width, int height,
            boolean map) {
        if (closed || width <= 0 || height <= 0) return;
        viewport.set(x, y, width, height);
        cam.width = width;
        cam.height = height;
        try (GLScope ignored = new GLScope(x, y, width, height, null)) {
            pump();
            State shown = state != null && state.presented ? state : fallback != null ? fallback : state;
            if (shown == null) return;
            FrameCache image = viewport.image;
            int flags = (map ? 1 : 0) | (sliced ? 2 : 0) | (sliceContext ? 4 : 0) | (hatches ? 8 : 0);
            if (image.ensure(width, height)) {
                long now = System.nanoTime();
                if (!image.matches(shown, cam, flags, focusGeneration, now)) {
                    try (GLScope capture = new GLScope(0, 0, width, height, cam, image.framebuffer)) {
                        drawContent(shown, cam, map, true);
                    }
                    image.remember(shown, cam, flags, focusGeneration, now);
                    renderedFrames++;
                } else cachedFrames++;
                image.blit(width, height);
            } else {
                applyCamera(cam);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                drawContent(shown, cam, map, false);
                renderedFrames++;
            }
            boolean plane = map || sliced;
            if (shown == state && selected != null && (!plane || coordinate(selected, sliceAxis()) == sliceLayer()))
                { applyCamera(cam); drawSelection(cam, selected); }
        }
    }

    private void drawContent(State shown, BrowserCamera camera, boolean map, boolean capture) {
        normalBlend(capture);
        boolean plane = map || sliced;
        if (plane && !map && sliceContext) {
            GL14.glBlendColor(1, 1, 1, .16f);
            GL11.glBlendFunc(CONSTANT_ALPHA, ONE_MINUS_CONSTANT_ALPHA);
            drawGeometry(shown, camera, 0, false);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            normalBlend(capture);
        }
        if (focusKey != null && shown == state) {
            GL14.glBlendColor(1, 1, 1, .16f);
            GL11.glBlendFunc(CONSTANT_ALPHA, ONE_MINUS_CONSTANT_ALPHA);
        }
        drawGeometry(shown, camera, plane ? 1 : 0, hatches);
        if (focusKey != null && shown == state) {
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            normalBlend(capture);
            drawGeometry(shown, camera, 2, hatches);
        }
    }

    private static void normalBlend(boolean capture) {
        if (capture) GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        else GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void pump() {
        long now = System.nanoTime(), window = now / FRAME_WINDOW;
        if (window == frameWindow) return;
        frameWindow = window;
        long deadline = now + FRAME_BUDGET;
        drainRetired(Math.min(deadline, now + 300000L));
        if (state == null) return;
        boolean needsPlane = sliced || mapViewport.width > 0;
        state.discardOldViews(Math.min(deadline, System.nanoTime() + 300000L));
        state.scene.advance(Math.min(deadline, System.nanoTime() + 4_400_000L), 65536, needsPlane);
        for (int n = 0; n < 16 && System.nanoTime() < deadline; n++) {
            BrowserScene.Replacement replacement = state.scene.replacements.pollFirst();
            if (replacement == null) break;
            state.replace(replacement);
        }
        while (System.nanoTime() < deadline) {
            Geometry geometry = nextGeometry(needsPlane);
            if (geometry == null) break;
            geometry.compileBatch();
            if (geometry.finished()) {
                geometry.complete = true;
                state.renderRevision++;
                geometry.releasePrevious();
                if (geometry.mode == 0) {
                    geometry.section.detailed = true;
                    state.dirtyProxy(geometry.section.orderIndex / PROXY_SECTIONS);
                }
                state.presented |= geometry.hasLists() || state.scene.complete();
                state.current = null;
                if (state.presented && fallback != null) {
                    retire(fallback);
                    fallback = null;
                }
            }
        }
    }

    private Geometry nextGeometry(boolean needsPlane) {
        if (state.current != null && state.current.valid()) return state.current;
        state.current = null;
        BrowserScene scene = state.scene;
        // Handle a bounded number of completed sections before choosing a mesh job.
        for (int n = 0; n < 4; n++) {
            BrowserScene.Section completed = scene.indexed.pollFirst();
            if (completed == null) break;
            if (scene.sections.get(completed.key) != completed) continue;
            state.dirtyProxy(completed.orderIndex / PROXY_SECTIONS);
            for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                BrowserScene.Section neighbor = scene.sections.get(new ChunkPosition((completed.x >> 4) + dx,
                        (completed.y >> 4) + dy, (completed.z >> 4) + dz));
                if (neighbor != null && neighbor != completed) state.invalidate(neighbor);
            }
            state.whole.addLast(completed);
        }
        Proxy dirtyProxy = state.dirtyProxies.pollFirst();
        if (dirtyProxy != null) dirtyProxy.compile();
        if (state.priority != null && state.priority.complete) {
            BrowserScene.Section priority = state.priority;
            state.priority = null;
            Geometry g = state.get(priority, sliced ? 1 : 0, true);
            if (!g.complete) return state.current = g;
        }
        for (int n = 0; n < 27; n++) {
            BrowserScene.Section priority = state.priorityNeighbors.pollFirst();
            if (priority == null) break;
            if (!priority.complete) continue;
            Geometry geometry = state.get(priority, sliced ? 1 : 0, true);
            if (!geometry.complete) return state.current = geometry;
        }
        if (needsPlane) {
            List<BrowserScene.Section> plane = scene.planeSections();
            int visited = 0;
            while (state.planeCursor < plane.size() && visited++ < 64) {
                BrowserScene.Section section = plane.get(state.planeCursor);
                if (!section.complete) break;
                state.planeCursor++;
                Geometry geometry = state.get(section, 1, true);
                if (!geometry.complete) return state.current = geometry;
            }
        }
        if (focusKey != null) {
            int visited = 0;
            List<BrowserScene.Section> focused = sliced ? scene.planeSections() : scene.order;
            while (state.focusCursor < focused.size() && visited++ < 64) {
                BrowserScene.Section section = focused.get(state.focusCursor);
                if (!section.complete) break;
                state.focusCursor++;
                if (!section.counts.containsKey(focusKey)) continue;
                Geometry geometry = state.get(section, 2, true);
                if (!geometry.complete) return state.current = geometry;
            }
        }
        BrowserScene.Section section;
        int visited = 0;
        while (visited++ < 64 && (section = state.whole.pollFirst()) != null) {
            if (scene.sections.get(section.key) != section || !section.complete) continue;
            Geometry geometry = state.get(section, 0, true);
            if (!geometry.complete) return state.current = geometry;
        }
        for (int n = 0; n < 2; n++) {
            Proxy proxy = state.dirtyProxies.pollFirst();
            if (proxy == null) break;
            proxy.compile();
        }
        return null;
    }

    private void drawGeometry(State shown, BrowserCamera cam, int mode, boolean roles) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        Submission submission = shown.submissions[mode];
        submission.prepare(shown, cam);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        // Holes in cutout icons must leave the depth buffer available for the surface behind them.
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0);
        GL11.glPushMatrix();
        GL11.glTranslated(-cam.x, -cam.y, -cam.z);
        try {
            call(submission.coarse);
            call(submission.opaque);
            call(submission.markers);
            if (roles) {
                GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                GL11.glPolygonOffset(-1, -1);
                call(submission.roles);
                GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            }
            GL11.glDepthMask(false);
            call(submission.glass);
            GL11.glDepthMask(true);
        } finally { GL11.glPopMatrix(); }
    }

    private void call(ListBuffer lists) {
        // Angelica's GLSMDraws consumes IntBuffer.position; native LWJGL leaves it untouched.
        // Keep the immutable limit as the count and rewind on every path, including optional API failures.
        lists.data.position(0);
        if (lists.data.hasRemaining()) {
            GL11.glListBase(0);
            try { GL11.glCallLists(lists.data); }
            finally { lists.data.position(0); }
            submittedCalls++;
        }
    }

    private static double distance(BrowserScene.Section s, BrowserScene scene, BrowserCamera cam, double[] eye) {
        double dx = s.x - scene.originX + 8 - cam.x, dy = s.y - scene.originY + 8 - cam.y;
        double dz = s.z - scene.originZ + 8 - cam.z;
        return -(dx * eye[0] + dy * eye[1] + dz * eye[2]);
    }

    /** Stable frames reuse native buffers. Only actual geometry changes rebuild the opaque submission. */
    private final class Submission {
        final int mode;
        final ListBuffer coarse = new ListBuffer(), opaque = new ListBuffer(), markers = new ListBuffer();
        final ListBuffer roles = new ListBuffer(), glass = new ListBuffer();
        final List<Geometry> transparent = new ArrayList<>();
        long revision = -1;
        int token;
        double yaw = Double.NaN, pitch;

        Submission(int mode) { this.mode = mode; }

        void prepare(State owner, BrowserCamera camera) {
            int nextToken = mode == 1 ? owner.planeToken() : mode == 2 ? focusGeneration : 0;
            boolean changed = revision != owner.renderRevision || token != nextToken;
            if (changed) {
                coarse.clear(); opaque.clear(); markers.clear(); roles.clear(); transparent.clear();
                if (mode == 0 && owner.scene.sections.size() > PROXY_THRESHOLD)
                    for (Proxy proxy : owner.proxies) if (!proxy.dirty && proxy.faces > 0) coarse.add(proxy.list);
                for (Geometry geometry : owner.geometry.values()) {
                    if (geometry.mode != mode || !geometry.validForDraw()) continue;
                    Geometry drawn = geometry.drawable();
                    if (drawn == null) continue;
                    opaque.add(drawn.opaque); markers.add(drawn.markers); roles.add(drawn.roles);
                    if (containsLists(drawn.glass)) transparent.add(drawn);
                }
                coarse.finish(); opaque.finish(); markers.finish(); roles.finish();
                revision = owner.renderRevision;
                token = nextToken;
            }
            if (changed || yaw != camera.yaw || pitch != camera.pitch) {
                double[] eye = camera.eye();
                for (Geometry geometry : transparent) geometry.distance = distance(geometry.section, owner.scene, camera, eye);
                transparent.sort((a, b) -> Double.compare(b.distance, a.distance));
                glass.clear();
                for (Geometry geometry : transparent) glass.add(geometry.glass);
                glass.finish();
                yaw = camera.yaw;
                pitch = camera.pitch;
            }
        }
    }

    private static boolean containsLists(int[] lists) {
        for (int list : lists) if (list != 0) return true;
        return false;
    }

    private static final class ListBuffer {
        IntBuffer data = BufferUtils.createIntBuffer(16);
        void clear() { data.clear(); }
        void add(int[] lists) { for (int list : lists) if (list != 0) add(list); }
        void add(int list) {
            if (!data.hasRemaining()) {
                IntBuffer next = BufferUtils.createIntBuffer(data.capacity() * 2);
                data.flip(); next.put(data); data = next;
            }
            data.put(list);
        }
        void finish() { data.flip(); }
    }

    private Pick pick(Viewport viewport, BrowserCamera cam, int mouseX, int mouseY, boolean plane) {
        if (state == null || !viewport.contains(mouseX, mouseY) || !state.presented) return null;
        double[] ray = cam.ray(mouseX - viewport.x, mouseY - viewport.y);
        return trace(ray, plane);
    }

    private Pick trace(double[] ray, boolean plane) {
        BrowserScene scene = state.scene;
        Bounds b = scene.bounds;
        double near = 0, far = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            double low = scene.min(axis), high = scene.max(axis) + 1.0;
            if (plane && axis == scene.axis) { low = scene.layer; high = low + 1; }
            if (Math.abs(ray[axis + 3]) < 1e-12) {
                if (ray[axis] < low || ray[axis] > high) return null;
            } else {
                double a = (low - ray[axis]) / ray[axis + 3], z = (high - ray[axis]) / ray[axis + 3];
                near = Math.max(near, Math.min(a, z));
                far = Math.min(far, Math.max(a, z));
            }
        }
        if (far < near) return null;
        double t = near + 1e-6;
        for (int steps = 0; steps < PICK_STEPS && t <= far; steps++) {
            int x = floor(ray[0] + ray[3] * t), y = floor(ray[1] + ray[4] * t), z = floor(ray[2] + ray[5] * t);
            if (!b.contains(x, y, z)) break;
            BrowserScene.Section section = scene.sections.get(new ChunkPosition((x + scene.originX) >> 4,
                    (y + scene.originY) >> 4, (z + scene.originZ) >> 4));
            BrowserScene.Material material = section == null ? null : scene.material(section,
                    ((x + scene.originX) & 15) | (((z + scene.originZ) & 15) << 4) | (((y + scene.originY) & 15) << 8));
            if (material != null && (!material.data.air || material.data.group >= 0)
                    && (focusKey == null || focusKey.equals(material.key))) return makePick(x, y, z, material);
            double next = Double.POSITIVE_INFINITY;
            for (int a = 0; a < 3; a++) {
                double direction = ray[a + 3];
                if (Math.abs(direction) < 1e-12) continue;
                int cell = a == 0 ? x : a == 1 ? y : z;
                int nativeCell = cell + scene.origin(a);
                double edge = section == null ? (direction > 0 ? (nativeCell >> 4) * 16 + 16
                        : (nativeCell >> 4) * 16) - scene.origin(a) : cell + (direction > 0 ? 1 : 0);
                next = Math.min(next, (edge - ray[a]) / direction);
            }
            if (!Double.isFinite(next)) break;
            t = Math.max(t + 1e-6, next + 1e-6);
        }
        return null;
    }

    private Pick makePick(int x, int y, int z, BrowserScene.Material material) {
        return new Pick(x, y, z, material, state.scene.groups.get(material.data.group), label(material));
    }

    private String label(BrowserScene.Material material) {
        if (!material.data.hatch) return "";
        if (material.hatchName == null || material.labelVersion != state.scene.labelVersion) {
            material.labelVersion = state.scene.labelVersion;
            material.color = 0;
            if (state.hints == null) state.hints = HatchLabels.dotHints(state.scene.controllerMeta, state.scene.tier);
            int dot = material.data.dots;
            String hint = dot >= 0 && dot < state.hints.length ? state.hints[dot] : "";
            boolean ambiguous = dot >= 0 && dot < 32 && (state.scene.ambiguousDots & (1 << dot)) != 0;
            material.hatchName = HatchLabels.select(hint, material.data.hatchKeys, ambiguous);
        }
        return material.hatchName;
    }

    private int color(BrowserScene.Material material) {
        if (material.data.hatch) label(material);
        if (material.color != 0) return material.color;
        if (material.data.hatch) {
            int dot = material.data.dots;
            String hint = dot >= 0 && state.hints != null && dot < state.hints.length ? state.hints[dot] : "";
            material.color = HatchLabels.role(hint, material.data.hatchKeys,
                    dot >= 0 && dot < 32 && (state.scene.ambiguousDots & (1 << dot)) != 0).color;
        } else material.color = material.data.group >= 0 ? 0xe2ad64 : 0x77a6aa;
        return material.color;
    }

    private static BlockPreviewTextures.Appearance appearance(BrowserScene.Material material) {
        if (material == null || material.data.air) return BlockPreviewTextures.FALLBACK;
        if (material.appearance == null) {
            PreviewStream.RenderMaterial render = material.data.render;
            material.appearance = render == null ? BlockPreviewTextures.FALLBACK
                    : BlockPreviewTextures.get(render.block, render.meta, render.machine, render.front);
        }
        return material.appearance;
    }

    private void resetMapCamera() {
        if (state == null) return;
        BrowserScene scene = state.scene;
        Bounds b = scene.bounds;
        Bounds plane = scene.axis == 0 ? new Bounds(scene.layer, b.minY, b.minZ, scene.layer, b.maxY, b.maxZ)
                : scene.axis == 1 ? new Bounds(b.minX, scene.layer, b.minZ, b.maxX, scene.layer, b.maxZ)
                : new Bounds(b.minX, b.minY, scene.layer, b.maxX, b.maxY, scene.layer);
        mapCamera.reset(plane);
        mapCamera.yaw = scene.axis == 0 ? 90 : 0;
        mapCamera.pitch = scene.axis == 1 ? 90 : 0;
        mapCamera.fit(plane);
        if (scene.axis == 0) mapCamera.x = scene.layer + .5;
        else if (scene.axis == 1) mapCamera.y = scene.layer + .5;
        else mapCamera.z = scene.layer + .5;
    }

    private static void drawSelection(BrowserCamera cam, Pick pick) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(1.5f);
        GL11.glColor4f(.96f, .92f, .68f, 1);
        GL11.glPushMatrix();
        GL11.glTranslated(pick.x - cam.x, pick.y - cam.y, pick.z - cam.z);
        GL11.glBegin(GL11.GL_LINES);
        for (int axis = 0; axis < 3; axis++) for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            if (axis == 0) { GL11.glVertex3d(0, a, b); GL11.glVertex3d(1, a, b); }
            else if (axis == 1) { GL11.glVertex3d(a, 0, b); GL11.glVertex3d(a, 1, b); }
            else { GL11.glVertex3d(a, b, 0); GL11.glVertex3d(a, b, 1); }
        }
        GL11.glEnd();
        GL11.glPopMatrix();
    }

    private static int coordinate(Pick pick, int axis) { return axis == 0 ? pick.x : axis == 1 ? pick.y : pick.z; }
    private static int floor(double value) { return (int) Math.floor(value); }
    private static double square(double value) { return value * value; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        assembler.clear();
        retire(state);
        retire(fallback);
        state = fallback = null;
        selected = null;
        modelViewport.image.retire();
        mapViewport.image.retire();
        materialList = sliceList = Collections.emptyList();
        // Large windows retire GL handles incrementally, including when the next browser opens.
        drainRetired(System.nanoTime() + 1000000L);
    }

    private static void retire(State state) {
        if (state != null && !RETIRED.contains(state)) {
            state.scene = null;
            state.current = null;
            state.priority = null;
            state.whole.clear();
            state.planeMeshes.clear();
            state.focusMeshes.clear();
            state.oldViews.clear();
            state.priorityNeighbors.clear();
            state.dirtyProxies.clear();
            for (Geometry geometry : state.geometry.values()) geometry.access = null;
            RETIRED.addLast(state);
        }
    }

    /** Client tick hook: closing a browser also releases GPU resources when no browser remains open. */
    static void cleanupClosed() {
        if (!RETIRED.isEmpty() || !RETIRED_IMAGES.isEmpty()) drainRetired(System.nanoTime() + 500000L);
    }

    private static void drainRetired(long deadline) {
        while (!RETIRED_IMAGES.isEmpty() && System.nanoTime() < deadline) {
            FramebufferBinding binding = new FramebufferBinding();
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            try { RETIRED_IMAGES.removeFirst().deleteFramebuffer(); }
            finally { binding.close(); GL11.glPopAttrib(); }
        }
        while (!RETIRED.isEmpty() && System.nanoTime() < deadline) {
            State retired = RETIRED.peekFirst();
            Iterator<Geometry> iterator = retired.geometry.values().iterator();
            if (!iterator.hasNext()) {
                if (!retired.proxies.isEmpty()) {
                    Proxy proxy = retired.proxies.remove(retired.proxies.size() - 1);
                    if (proxy.list != 0) GL11.glDeleteLists(proxy.list, 1);
                } else RETIRED.removeFirst();
                continue;
            }
            Geometry geometry = iterator.next();
            geometry.delete();
            iterator.remove();
        }
    }

    private final class State {
        BrowserScene scene;
        final LinkedHashMap<MeshKey, Geometry> geometry = new LinkedHashMap<>(32, .75f, true);
        final List<Geometry> transparent = new ArrayList<>();
        final Submission[] submissions = { new Submission(0), new Submission(1), new Submission(2) };
        final Deque<BrowserScene.Section> whole = new ArrayDeque<>();
        Deque<MeshKey> planeMeshes = new ArrayDeque<>(), focusMeshes = new ArrayDeque<>();
        final Deque<Deque<MeshKey>> oldViews = new ArrayDeque<>();
        final int[] batchFaces = new int[BATCH * 6];
        final int[] batchSlots = new int[BATCH], batchAir = new int[BATCH];
        final BrowserScene.Material[] batchMaterials = new BrowserScene.Material[BATCH];
        final Deque<BrowserScene.Section> priorityNeighbors = new ArrayDeque<>();
        final List<Proxy> proxies = new ArrayList<>();
        final Deque<Proxy> dirtyProxies = new ArrayDeque<>();
        Geometry current;
        BrowserScene.Section priority;
        String[] hints;
        int planeCursor, focusCursor, planeGeneration, lists;
        long renderRevision = 1;
        boolean presented;

        State(BrowserScene scene) { this.scene = scene; }

        Geometry get(BrowserScene.Section section, int mode, boolean create) {
            int generation = mode == 1 ? planeToken() : mode == 2 ? focusGeneration : 0;
            MeshKey key = new MeshKey(section, mode, generation);
            Geometry result = geometry.get(key);
            if (result != null && (result.stale || result.sourceVersion != section.neighborhoodVersion)) {
                Geometry next = new Geometry(this, section, mode, generation);
                next.previous = result.drawable();
                if (next.previous != result) result.deleteOwn();
                if (mode == 0) section.detailed = next.previous != null;
                geometry.put(key, next);
                result = next;
            }
            if (result == null && create) {
                result = new Geometry(this, section, mode, generation);
                addMesh(key, result);
            }
            return result;
        }

        void addMesh(MeshKey key, Geometry mesh) {
            geometry.put(key, mesh);
            if (key.mode == 1) planeMeshes.addLast(key);
            else if (key.mode == 2) focusMeshes.addLast(key);
        }

        // The entire model stays resident. Only transient slice and focus meshes expire on view changes.
        void retirePlane() {
            if (planeMeshes.isEmpty()) return;
            oldViews.addLast(planeMeshes);
            planeMeshes = new ArrayDeque<>();
        }

        void retireFocus() {
            if (focusMeshes.isEmpty()) return;
            oldViews.addLast(focusMeshes);
            focusMeshes = new ArrayDeque<>();
        }

        void discardOldViews(long deadline) {
            while (!oldViews.isEmpty() && System.nanoTime() < deadline) {
                Deque<MeshKey> keys = oldViews.peekFirst();
                MeshKey key = keys.removeFirst();
                if (keys.isEmpty()) oldViews.removeFirst();
                // A quick return to the same slice can reuse a mesh awaiting disposal.
                if (sliced && key.mode == 1 && key.generation == planeToken()
                        && scene.sections.get(key.section.key) == key.section) {
                    planeMeshes.addLast(key);
                } else {
                    Geometry mesh = geometry.remove(key);
                    if (mesh != null) mesh.delete();
                }
            }
        }

        void invalidate(BrowserScene.Section section) {
            section.neighborhoodVersion++;
            dirtyProxy(section.orderIndex / PROXY_SECTIONS);
            for (int mode = 0; mode < 3; mode++) {
                int generation = mode == 0 ? 0 : mode == 1 ? planeToken() : focusGeneration;
                Geometry geometry = this.geometry.get(new MeshKey(section, mode, generation));
                if (geometry != null && !geometry.stale) {
                    geometry.stale = true;
                    if (mode == 0) whole.addLast(section);
                    else if (mode == 1) planeCursor = Math.min(planeCursor, section.planeIndices[scene.axis]);
                    else focusCursor = Math.min(focusCursor, sliced ? section.planeIndices[scene.axis] : section.orderIndex);
                }
            }
        }

        void replace(BrowserScene.Replacement replacement) {
            renderRevision++;
            if (priority == replacement.oldSection) priority = replacement.newSection;
            for (int mode = 0; mode < 3; mode++) {
                int generation = mode == 0 ? 0 : mode == 1 ? planeToken() : focusGeneration;
                Geometry old = geometry.remove(new MeshKey(replacement.oldSection, mode, generation));
                if (old == null) continue;
                Geometry next = new Geometry(this, replacement.newSection, mode, generation);
                next.previous = old.drawable();
                if (next.previous != old) old.deleteOwn();
                if (mode == 0) replacement.newSection.detailed = next.previous != null;
                addMesh(new MeshKey(replacement.newSection, mode, generation), next);
            }
            BrowserScene.Section section = replacement.newSection;
            if (scene.intersects(section, scene.axis, scene.layer)) {
                planeCursor = Math.min(planeCursor, section.planeIndices[scene.axis]);
                if (sliced) focusCursor = Math.min(focusCursor, section.planeIndices[scene.axis]);
            }
            if (!sliced) focusCursor = Math.min(focusCursor, section.orderIndex);
        }

        int planeToken() { return scene.layer * 3 + scene.axis; }

        void dirtyProxy(int index) {
            while (proxies.size() <= index) proxies.add(new Proxy(this, proxies.size()));
            Proxy proxy = proxies.get(index);
            if (!proxy.dirty) { proxy.dirty = true; dirtyProxies.addLast(proxy); renderRevision++; }
        }
    }

    /** Four-block occupancy shows sparse structures while their full meshes are being built. */
    private static final class Proxy {
        final State owner;
        final int index;
        int list;
        int sectionsDrawn, faces;
        boolean dirty;

        Proxy(State owner, int index) { this.owner = owner; this.index = index; }

        void compile() {
            dirty = false;
            if (list == 0) list = GL11.glGenLists(1);
            if (list == 0) return;
            BrowserScene scene = owner.scene;
            sectionsDrawn = faces = 0;
            GL11.glNewList(list, GL11.GL_COMPILE);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            int stop = Math.min(scene.order.size(), (index + 1) * PROXY_SECTIONS);
            for (int i = index * PROXY_SECTIONS; i < stop; i++) {
                BrowserScene.Section section = scene.order.get(i);
                if (section.detailed || !section.complete) continue;
                if (section.coarseMask != 0) sectionsDrawn++;
                BrowserScene.Section[] adjacent = new BrowserScene.Section[6];
                for (int side = 0; side < 6; side++) adjacent[side] = scene.sections.get(new ChunkPosition(
                        (section.x >> 4) + DX[side], (section.y >> 4) + DY[side], (section.z >> 4) + DZ[side]));
                for (long bits = section.coarseMask; bits != 0; bits &= bits - 1) {
                    int cell = Long.numberOfTrailingZeros(bits), x = cell & 3, y = cell >> 4, z = cell >> 2 & 3;
                    for (int side = 0; side < 6; side++) {
                        int nx = x + DX[side], ny = y + DY[side], nz = z + DZ[side];
                        boolean crossing = nx < 0 || nx > 3 || ny < 0 || ny > 3 || nz < 0 || nz > 3;
                        BrowserScene.Section neighbor = crossing ? adjacent[side] : section;
                        int other = (nx & 3) | ((nz & 3) << 2) | ((ny & 3) << 4);
                        if (neighbor != null && (neighbor.coarseMask & (1L << other)) != 0) continue;
                        faces++;
                        t.setColorRGBA_I(FACE_COLOR[side], 255);
                        coarseFace(t, side, section.x - scene.originX + x * 4,
                                section.y - scene.originY + y * 4, section.z - scene.originZ + z * 4);
                    }
                }
            }
            t.draw();
            GL11.glEndList();
            owner.renderRevision++;
        }
    }

    private static void coarseFace(Tessellator t, int side, int x, int y, int z) {
        int axis = side / 2, positive = side & 1;
        int plane = (axis == 0 ? x : axis == 1 ? y : z) + positive * 4;
        int u = axis == 0 ? y : axis == 1 ? z : x, v = axis == 0 ? z : axis == 1 ? x : y;
        vertex(t, axis, plane, u, v);
        vertex(t, axis, plane, positive != 0 ? u + 4 : u, positive != 0 ? v : v + 4);
        vertex(t, axis, plane, u + 4, v + 4);
        vertex(t, axis, plane, positive != 0 ? u : u + 4, positive != 0 ? v + 4 : v);
    }

    private static final class MeshKey {
        final BrowserScene.Section section;
        final int mode, generation;
        MeshKey(BrowserScene.Section section, int mode, int generation) {
            this.section = section;
            this.mode = mode;
            this.generation = generation;
        }
        @Override public int hashCode() { return (System.identityHashCode(section) * 31 + mode) * 31 + generation; }
        @Override public boolean equals(Object value) {
            if (!(value instanceof MeshKey)) return false;
            MeshKey other = (MeshKey) value;
            return section == other.section && mode == other.mode && generation == other.generation;
        }
    }

    private final class Geometry {
        final State owner;
        final BrowserScene.Section section;
        final int mode, generation, sourceVersion;
        final int[] opaque = new int[BATCHES], glass = new int[BATCHES], roles = new int[BATCHES];
        final int[] markers = new int[BATCHES];
        BrowserBlockAccess access;
        Geometry previous;
        int batch, cursor;
        boolean complete, deleted, stale;
        double distance;

        Geometry(State owner, BrowserScene.Section section, int mode, int generation) {
            this.owner = owner;
            this.section = section;
            this.mode = mode;
            this.generation = generation;
            sourceVersion = section.neighborhoodVersion;
        }

        boolean valid() { return !deleted && !stale && validForDraw(); }
        boolean validForDraw() {
            return owner.scene != null && owner.scene.sections.get(section.key) == section
                    && (mode == 0 || mode == 1 && generation == owner.planeToken()
                    || mode == 2 && generation == focusGeneration && focusKey != null);
        }

        Geometry drawable() { return complete && !deleted ? this : previous; }
        void releasePrevious() { if (previous != null) { previous.delete(); previous = null; } }
        boolean hasLists() {
            for (int i = 0; i < BATCHES; i++)
                if (opaque[i] != 0 || glass[i] != 0 || roles[i] != 0 || markers[i] != 0) return true;
            return false;
        }

        void compileBatch() {
            compiledBatches++;
            if (access == null) access = new BrowserBlockAccess(owner.scene, section);
            int[] faces = owner.batchFaces;
            int count = 0;
            int solidRuns = 0, blendedRuns = 0;
            boolean hatches = false;
            int airChoices = 0;
            int cut = 0;
            if (planeOnly()) {
                int axis = owner.scene.axis;
                cut = owner.scene.intersects(section, axis, owner.scene.layer) ? 3 << (axis * 2) : 63;
            }
            int begin = cursor, end = planeOnly() ? BATCH : 4096;
            int cells = 0;
            // Pack sparse rows into one list without increasing the per-batch material lookup budget.
            while (cursor < end && cells < BATCH) {
                int slot = visitedSlot(cursor++);
                if (section.slots[slot] == 0) continue;
                int cell = cells++;
                BrowserScene.Material material = owner.scene.material(section, slot);
                if (material == null || !inFocus(material)) continue;
                if (material.data.air) {
                    if (cut != 63 && material.data.group >= 0) {
                        owner.batchSlots[cell] = slot;
                        owner.batchMaterials[cell] = material;
                        owner.batchAir[airChoices++] = cell;
                    }
                    continue;
                }
                int x = section.x + (slot & 15), y = section.y + (slot >> 8), z = section.z + (slot >> 4 & 15);
                BlockPreviewTextures.Appearance current = appearance(material);
                int firstFace = count;
                if (current.fence) {
                    faces[count++] = cell * 8 + ProjectedBlockFaces.MODEL;
                } else for (int side = 0; side < 6; side++) {
                    int nx = x + DX[side], ny = y + DY[side], nz = z + DZ[side];
                    BrowserScene.Material neighbor = (cut & (1 << side)) == 0 ? access.material(nx, ny, nz) : null;
                    if (neighbor != null && !neighbor.data.air && inFocus(neighbor)) {
                        BlockPreviewTextures.Appearance next = appearance(neighbor);
                        if (next.browserSolidSurface) continue;
                        if (current.fluid != null || next.fluid != null) {
                            if (BlockPreviewTextures.occludes(next, current)) continue;
                        } else if (current.textured() && current.block != null && !current.frame) {
                            try {
                                if (!current.block.shouldSideBeRendered(access, nx, ny, nz,
                                        BlockPreviewTextures.nativeSide(side))) continue;
                            } catch (RuntimeException | LinkageError unsupported) { /* Conservative visible face. */ }
                        } else if (BlockPreviewTextures.occludes(next, current)) continue;
                    }
                    faces[count++] = cell * 8 + side;
                }
                if (firstFace == count) continue;
                owner.batchSlots[cell] = slot;
                owner.batchMaterials[cell] = material;
                int run = 1 << textureRun(0, current);
                if (current.translucent && !current.browserSolidSurface) blendedRuns |= run;
                else solidRuns |= run;
                hatches |= material.data.hatch;
            }
            if (count != 0 || airChoices != 0) {
                try {
                    if (solidRuns != 0) opaque[batch] = compile(faces, count, 0, solidRuns);
                    if (blendedRuns != 0) glass[batch] = compile(faces, count, 1, blendedRuns);
                    if (hatches) roles[batch] = compile(faces, count, 2, 1);
                    if (airChoices != 0) markers[batch] = compileMarkers(airChoices, markers[batch], cut);
                } finally {
                    Arrays.fill(owner.batchMaterials, null);
                }
            }
            compiledCells += cursor - begin;
            batch++;
            if (finished()) access = null;
        }

        boolean planeOnly() { return mode == 1 || mode == 2 && sliced; }
        boolean finished() { return cursor >= (planeOnly() ? BATCH : 4096); }
        int visitedSlot(int index) {
            return planeOnly() ? BrowserScene.planeSlot(owner.scene.axis,
                    (owner.scene.layer + owner.scene.origin(owner.scene.axis)) & 15, index) : index;
        }

        private boolean inFocus(BrowserScene.Material material) {
            return mode != 2 || Objects.equals(focusKey, material.key);
        }

        private int cutSides(int x, int y, int z) {
            int cut = planeOnly() ? 3 << (2 * owner.scene.axis) : 0;
            if (mode == 2) for (int side = 0; side < 6; side++) {
                BrowserScene.Material neighbor = access.material(x + DX[side], y + DY[side], z + DZ[side]);
                if (neighbor == null || !inFocus(neighbor)) cut |= 1 << side;
            }
            return cut;
        }

        private int compile(int[] faces, int count, int pass, int runs) {
            int list = GL11.glGenLists(1);
            if (list == 0) return 0;
            owner.lists++;
            GL11.glNewList(list, GL11.GL_COMPILE);
            GL11.glPushMatrix();
            GL11.glTranslated(section.x - owner.scene.originX, section.y - owner.scene.originY,
                    section.z - owner.scene.originZ);
            Tessellator tessellator = Tessellator.instance;
            try {
                // Separate atlas and semantic fallback runs; no texture changes inside glBegin/glEnd.
                for (int texture = 0; texture < 3; texture++) {
                    if ((runs & (1 << texture)) == 0) continue;
                    if (texture != 0) GL11.glEnable(GL11.GL_TEXTURE_2D); else GL11.glDisable(GL11.GL_TEXTURE_2D);
                    if (texture == 2) {
                        // Amber has a uniformly translucent atlas alpha even on its closed outer surface.
                        // Use the normal shaded atlas RGB and the vertex's solid coverage.
                        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
                        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL11.GL_MODULATE);
                        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL11.GL_TEXTURE);
                        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE1_RGB, GL13.GL_PRIMARY_COLOR);
                        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL11.GL_REPLACE);
                        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL13.GL_PRIMARY_COLOR);
                    }
                    tessellator.startDrawingQuads();
                    tessellator.setBrightness(0xf000f0);
                    for (int i = 0; i < count; i++) {
                        int cell = faces[i] >> 3, side = faces[i] & 7;
                        int slot = owner.batchSlots[cell];
                        BrowserScene.Material material = owner.batchMaterials[cell];
                        BlockPreviewTextures.Appearance appearance = appearance(material);
                        if (textureRun(pass, appearance) != texture) continue;
                        boolean blended = appearance.translucent && !appearance.browserSolidSurface;
                        if (pass == 2 ? !material.data.hatch : (pass == 1) != blended) continue;
                        if (texture != 0) {
                            try {
                                int x = section.x + (slot & 15), y = section.y + (slot >> 8), z = section.z + (slot >> 4 & 15);
                                if (side == ProjectedBlockFaces.MODEL) {
                                    PreviewFence.draw(tessellator, appearance, slot, 255, access, x, y, z, cutSides(x, y, z));
                                } else BlockPreviewTextures.face(tessellator, appearance, side, slot, 255, access, x, y, z);
                            } catch (RuntimeException | LinkageError unsupported) {
                                material.appearance = BlockPreviewTextures.FALLBACK;
                                // Rebuild into the untextured run; changing atlas state inside this batch is unsafe.
                                owner.invalidate(section);
                            }
                        } else {
                            int first = side == ProjectedBlockFaces.MODEL ? 0 : side;
                            int last = side == ProjectedBlockFaces.MODEL ? 5 : side;
                            for (int f = first; f <= last; f++) {
                                int color = pass == 2 ? color(material) : material.data.group >= 0 ? 0xe2ad64 : FACE_COLOR[f];
                                tessellator.setColorRGBA_I(color, pass == 2 ? 224 : 255);
                                face(tessellator, f, slot);
                            }
                        }
                    }
                    tessellator.draw();
                    if (texture == 2) GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
                }
            } finally { GL11.glPopMatrix(); GL11.glEndList(); }
            return list;
        }

        private int textureRun(int pass, BlockPreviewTextures.Appearance appearance) {
            if (pass == 2 || !appearance.textured()) return 0;
            return appearance.browserSolidSurface ? 2 : 1;
        }

        private int compileMarkers(int count, int previous, int cut) {
            int list = previous;
            boolean open = false;
            for (int i = 0; i < count; i++) {
                int cell = owner.batchAir[i];
                int slot = owner.batchSlots[cell];
                BrowserScene.Material material = owner.batchMaterials[cell];
                int x = slot & 15, y = slot >> 8, z = slot >> 4 & 15;
                int neighbors = 0;
                for (int side = 0; side < 6; side++)
                    if ((cut & (1 << side)) == 0 && sameAir(material, x + DX[side], y + DY[side], z + DZ[side]))
                        neighbors |= 1 << side;
                for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int c = 0; c < 2; c++) {
                    if ((neighbors & ((1 << a) | (1 << (2 + b)) | (1 << (4 + c)))) != 0) continue;
                    if (!open) {
                        if (list == 0) { list = GL11.glGenLists(1); if (list != 0) owner.lists++; }
                        if (list == 0) return 0;
                        GL11.glNewList(list, GL11.GL_COMPILE);
                        GL11.glPushMatrix();
                        GL11.glTranslated(section.x - owner.scene.originX, section.y - owner.scene.originY,
                                section.z - owner.scene.originZ);
                        GL11.glDisable(GL11.GL_TEXTURE_2D);
                        GL11.glColor4f(.8f, .78f, .64f, .75f);
                        GL11.glLineWidth(1);
                        GL11.glBegin(GL11.GL_LINES);
                        open = true;
                    }
                    double px = x + a, py = y + b, pz = z + c;
                    GL11.glVertex3d(px, py, pz); GL11.glVertex3d(px + (a == 0 ? .2 : -.2), py, pz);
                    GL11.glVertex3d(px, py, pz); GL11.glVertex3d(px, py + (b == 0 ? .2 : -.2), pz);
                    GL11.glVertex3d(px, py, pz); GL11.glVertex3d(px, py, pz + (c == 0 ? .2 : -.2));
                }
            }
            if (open) { GL11.glEnd(); GL11.glPopMatrix(); GL11.glEndList(); }
            else if (previous != 0) { GL11.glDeleteLists(previous, 1); owner.lists--; list = 0; }
            return list;
        }

        private boolean sameAir(BrowserScene.Material material, int x, int y, int z) {
            BrowserScene.Material other = access.material(section.x + x, section.y + y, section.z + z);
            return other != null && other.data.air && other.data.group == material.data.group;
        }

        void delete() {
            if (deleted) return;
            releasePrevious();
            deleteOwn();
        }

        void deleteOwn() {
            if (deleted) return;
            deleted = true;
            owner.renderRevision++;
            if (mode == 0 && owner.scene != null) {
                section.detailed = false;
                owner.dirtyProxy(section.orderIndex / PROXY_SECTIONS);
            }
            for (int[] lists : new int[][] { opaque, glass, roles, markers }) for (int list : lists)
                if (list != 0) { GL11.glDeleteLists(list, 1); owner.lists--; }
            access = null;
        }
    }

    private static void face(Tessellator t, int side, int slot) {
        int x = slot & 15, y = slot >> 8, z = slot >> 4 & 15;
        int axis = side / 2, dir = (side & 1) * 2 - 1;
        int plane = (axis == 0 ? x : axis == 1 ? y : z) + (side & 1);
        int u = axis == 0 ? y : axis == 1 ? z : x, v = axis == 0 ? z : axis == 1 ? x : y;
        vertex(t, axis, plane, u, v);
        vertex(t, axis, plane, dir > 0 ? u + 1 : u, dir > 0 ? v : v + 1);
        vertex(t, axis, plane, u + 1, v + 1);
        vertex(t, axis, plane, dir > 0 ? u : u + 1, dir > 0 ? v + 1 : v);
    }

    private static void vertex(Tessellator t, int axis, int plane, int u, int v) {
        if (axis == 0) t.addVertex(plane, u, v);
        else if (axis == 1) t.addVertex(v, plane, u);
        else t.addVertex(u, v, plane);
    }

    private static final class Viewport {
        int x, y, width, height;
        final FrameCache image = new FrameCache();
        void set(int x, int y, int width, int height) { this.x = x; this.y = y; this.width = width; this.height = height; }
        boolean contains(int x, int y) { return x >= this.x && y >= this.y && x < this.x + width && y < this.y + height; }
    }

    /** Two bounded color/depth images per browser. Animated atlas sprites refresh at least ten times a second. */
    private static final class FrameCache {
        static final long REFRESH_NANOS = 100000000L;
        Framebuffer framebuffer;
        boolean disabled;
        int maxTexture, flags, focus, layer, axis, width, height;
        State owner;
        long revision = -1, renderedAt;
        double x, y, z, radius, zoom, yaw, pitch;

        boolean ensure(int guiWidth, int guiHeight) {
            if (disabled || !OpenGlHelper.isFramebufferEnabled()) return false;
            Minecraft mc = Minecraft.getMinecraft();
            int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
            long px = (long) guiWidth * scale, py = (long) guiHeight * scale;
            if (maxTexture == 0) maxTexture = Math.min(4096, GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE));
            if (px > maxTexture || py > maxTexture || px * py > 4194304L) return false;
            if (framebuffer != null && framebuffer.framebufferWidth == px && framebuffer.framebufferHeight == py) return true;
            try {
                FramebufferBinding binding = new FramebufferBinding();
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                try {
                    if (framebuffer != null) framebuffer.deleteFramebuffer();
                    framebuffer = new Framebuffer((int) px, (int) py, true);
                    revision = -1;
                    owner = null;
                    return true;
                } finally { binding.close(); GL11.glPopAttrib(); }
            } catch (RuntimeException | LinkageError unsupported) {
                disabled = true;
                retire();
                return false;
            }
        }

        boolean matches(State state, BrowserCamera camera, int nextFlags, int nextFocus, long now) {
            return owner == state && revision == state.renderRevision && flags == nextFlags && focus == nextFocus
                    && layer == state.scene.layer && axis == state.scene.axis && width == camera.width && height == camera.height
                    && x == camera.x && y == camera.y && z == camera.z && radius == camera.radius && zoom == camera.zoom
                    && yaw == camera.yaw && pitch == camera.pitch && now - renderedAt < REFRESH_NANOS;
        }

        void remember(State state, BrowserCamera camera, int nextFlags, int nextFocus, long now) {
            owner = state; revision = state.renderRevision; flags = nextFlags; focus = nextFocus;
            layer = state.scene.layer; axis = state.scene.axis; width = camera.width; height = camera.height;
            x = camera.x; y = camera.y; z = camera.z; radius = camera.radius; zoom = camera.zoom;
            yaw = camera.yaw; pitch = camera.pitch; renderedAt = now;
        }

        void blit(int width, int height) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, framebuffer.framebufferTexture);
            // The transparent render target already contains premultiplied RGB.
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1, 1, 1, 1);
            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            t.setColorRGBA_I(0xffffff, 255);
            t.addVertexWithUV(0, height, 0, 0, 0);
            t.addVertexWithUV(width, height, 0, 1, 0);
            t.addVertexWithUV(width, 0, 0, 1, 1);
            t.addVertexWithUV(0, 0, 0, 0, 1);
            t.draw();
        }

        void retire() {
            if (framebuffer != null) { RETIRED_IMAGES.addLast(framebuffer); framebuffer = null; }
            owner = null;
        }
    }

    /** MUI/Iris may have a nonzero target, or distinct read/draw targets. Never restore blindly to zero. */
    private static final class FramebufferBinding implements AutoCloseable {
        final int draw, read;
        final boolean split;
        FramebufferBinding() {
            draw = GL11.glGetInteger(0x8CA6); // DRAW_FRAMEBUFFER_BINDING / FRAMEBUFFER_BINDING
            split = GLContext.getCapabilities().OpenGL30 || GLContext.getCapabilities().GL_ARB_framebuffer_object;
            read = split ? GL11.glGetInteger(0x8CAA) : draw;
        }
        @Override public void close() {
            if (split) {
                OpenGlHelper.func_153171_g(0x8CA9, draw);
                OpenGlHelper.func_153171_g(0x8CA8, read);
            } else OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, draw);
        }
    }

    private static void applyCamera(BrowserCamera camera) {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(-camera.halfWidth(), camera.halfWidth(), -camera.halfHeight(), camera.halfHeight(),
                -camera.far(), camera.far());
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glRotated(camera.pitch, 1, 0, 0);
        GL11.glRotated(camera.yaw, 0, 1, 0);
        FloatBuffer basis = camera.worldToPreview();
        if (basis != null) GL11.glMultMatrix(basis);
    }

    private static final class GLScope implements AutoCloseable {
        // LWJGL 2 validates all glGetInteger buffers against the largest 16-element query.
        private static final IntBuffer CLIP = BufferUtils.createIntBuffer(16);
        final int matrixMode;
        final FramebufferBinding framebuffer;

        GLScope(int x, int y, int width, int height, BrowserCamera camera) {
            this(x, y, width, height, camera, null);
        }

        GLScope(int x, int y, int width, int height, BrowserCamera camera, Framebuffer target) {
            Minecraft mc = Minecraft.getMinecraft();
            ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
            int scale = resolution.getScaleFactor();
            int px = x * scale, py = mc.displayHeight - (y + height) * scale, pw = width * scale, ph = height * scale;
            matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            framebuffer = target == null ? null : new FramebufferBinding();
            IntBuffer clip = CLIP;
            clip.clear();
            boolean clipped = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            if (clipped) GL11.glGetInteger(GL11.GL_SCISSOR_BOX, clip);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushClientAttrib(-1); // GL_CLIENT_ALL_ATTRIB_BITS, absent from some bundled LWJGL stubs.
            if (target != null) {
                target.bindFramebuffer(false);
                px = py = 0;
                GL11.glDisable(GL11.GL_STENCIL_TEST); // The parent's MUI stencil belongs to its framebuffer.
                GL11.glColorMask(true, true, true, true);
            }
            GL11.glViewport(px, py, pw, ph);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            if (clipped && target == null) {
                int left = Math.max(px, clip.get(0)), bottom = Math.max(py, clip.get(1));
                int right = Math.min(px + pw, clip.get(0) + clip.get(2)), top = Math.min(py + ph, clip.get(1) + clip.get(3));
                GL11.glScissor(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
            } else GL11.glScissor(px, py, pw, ph);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            if (camera != null) applyCamera(camera);
            else {
                GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
                GL11.glOrtho(0, width, height, 0, -1, 1);
                GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
            }
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glClearDepth(1);
            if (camera != null) {
                if (target != null) {
                    GL11.glClearColor(0, 0, 0, 0);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                } else GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            }
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1, 1, 1, 1);
        }

        @Override public void close() {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
            GL11.glPopClientAttrib();
            if (framebuffer != null) framebuffer.close();
            GL11.glPopAttrib();
        }
    }
}
