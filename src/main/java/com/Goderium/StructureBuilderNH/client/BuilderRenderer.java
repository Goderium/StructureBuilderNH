package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.BuilderProxy;
import com.Goderium.StructureBuilderNH.common.builder.PreviewStream;
import com.Goderium.StructureBuilderNH.common.builder.HatchTypes;
import com.Goderium.StructureBuilderNH.common.builder.PreviewAim;
import com.Goderium.StructureBuilderNH.common.builder.PreviewEdit;
import com.Goderium.StructureBuilderNH.common.builder.PreviewDragMessage;
import com.Goderium.StructureBuilderNH.common.builder.PreviewPose;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.Goderium.StructureBuilderNH.common.builder.BuilderItem;
import com.Goderium.StructureBuilderNH.common.builder.BuilderTasks;
import com.Goderium.StructureBuilderNH.util.Bounds;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.client.registry.ClientRegistry;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.util.StatCollector;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.EXTBlendColor;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class BuilderRenderer extends BuilderProxy {
    private static BuilderRenderer instance;
    private static final long[] EMPTY_MASK = new long[64];
    private final Queue<PreviewStream.Packet> packets = new ConcurrentLinkedQueue<>();
    private final Map<UUID, View> views = new LinkedHashMap<>();
    private final KeyBinding deleteTask = new KeyBinding("structurebuildernh.key.deleteTask", Keyboard.KEY_Z, "StructureBuilderNH");
    private final PreviewEditQueue editQueue = new PreviewEditQueue(new PreviewEditQueue.Transport() {
        @Override
        public boolean aimed(PreviewEditQueue.Target target) { return editTargetAimed(target); }

        @Override
        public void send(PreviewEditQueue.Target target, int revision, int steps, boolean group, long request) {
            PreviewEdit.send(target.task, revision, target.x, target.y, target.z, steps, group, request);
        }
    });
    private final List<Section> visibleHatches = new ArrayList<>();
    private final List<Section> visibleSections = new ArrayList<>();
    private final List<Section> transparentSections = new ArrayList<>();
    private double cameraX, cameraY, cameraZ;
    private World observedWorld;
    private ProjectionOccupancy occupancy;
    private final long[] sampledOccupancy = new long[64];
    private long samplingBudget;
    private final List<View> visibleControllers = new ArrayList<>();
    private long compilations;
    private String aimedMarker;
    private int aimedColor;
    private Pick aimed;
    private ChunkCoordinates highlighted;
    private int highlightColor;
    private View highlightedController;
    private Section highlightedHatch, cachedHatchSection;
    private int highlightedHatchSlot, cachedHatchSlot, cachedMarksRevision, hoverMarks;
    private HatchMesh.Selection cachedHatchFaces;
    private final int[] highlightOffset = new int[3], highlightLocal = new int[3];
    private String aimedHint;
    private boolean aimedHatch;
    private final PreviewTooltip tooltip = new PreviewTooltip();
    private volatile PreviewGrip grip;
    private long nextGrip;
    private volatile boolean textureReload;

    public static final class Pick {
        public final UUID taskId;
        public final int revision, x, y, z, group;
        public final boolean wholeGroup;
        private final PreviewStream.Style style;

        private Pick(UUID taskId, int revision, int x, int y, int z, PreviewStream.Style style) {
            this.taskId = taskId;
            this.revision = revision;
            this.x = x;
            this.y = y;
            this.z = z;
            this.group = style.group;
            this.wholeGroup = style.groupSelectable;
            this.style = style;
        }
    }

    public static Pick aimedPick() {
        return instance == null ? null : choice(instance.worldHit(Minecraft.getMinecraft().thePlayer), true);
    }

    public static boolean previewModifierDown() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.gameSettings == null) return false;
        KeyBinding binding = minecraft.gameSettings.keyBindSneak;
        if (binding == null || binding.getKeyCode() == 0) return false;
        if (binding.getIsKeyPressed()) return true;
        int code = binding.getKeyCode();
        if (code < 0) {
            int button = code + 100;
            return button >= 0 && button < Mouse.getButtonCount() && Mouse.isButtonDown(button);
        }
        return code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }

    @Override
    public boolean editPreview(EntityPlayer player) {
        if (!editContext(player)) return false;
        if (grip != null) return true;
        WorldHit hit = worldHit(player);
        if (hit == null) return false;
        if (!hit.owned || hit.view.fixedPose != null) return true;
        boolean sneak = previewModifierDown();
        if (sneak && !GuiScreen.isCtrlKeyDown()) {
            PreviewEdit.TaskAction.openTask(hit.view.id, hit.at);
            return true;
        }
        Pick pick = choice(hit, true);
        if (sneak && pick != null && pick.wholeGroup) {
            editQueue.applyGroup(editTarget(pick, player), System.nanoTime());
            return true;
        }
        if (sneak && pick == null) {
            PreviewEditQueue.Target pending = editQueue.pendingAt(this::editTargetAimed);
            if (pending != null && pending.groupSelectable) {
                editQueue.applyGroup(pending, System.nanoTime());
                return true;
            }
        }
        if (hit.marker()) {
            if (!beginGrip(hit, player)) PreviewEdit.TaskAction.open(hit.view.id, hit.view.revision, hit.at);
            return true;
        }
        return hit.projected;
    }

    private static boolean canGrip(WorldHit hit, EntityPlayer player) {
        return hit != null && hit.owned && hit.host && hit.view.fixedPose == null
                && hit.view.pose >= 0 && hit.view.allowed != null && player.worldObj.isAirBlock(hit.at.posX, hit.at.posY, hit.at.posZ);
    }

    private boolean beginGrip(WorldHit hit, EntityPlayer player) {
        if (!canGrip(hit, player)) return false;
        PreviewGrip g = new PreviewGrip(hit.view.id, ++nextGrip, hit.view.revision, hit.view.tool, hit.at,
                ExtendedFacing.byIndex(hit.view.pose), hit.view.allowed, player, System.nanoTime());
        grip = g;
        PreviewDragMessage.send(PreviewDragMessage.PRESS, g.id, g.sequence, g.revision, null, null);
        return true;
    }

    private static boolean useDown() {
        KeyBinding use = Minecraft.getMinecraft().gameSettings.keyBindUseItem;
        if (use.getIsKeyPressed()) return true;
        int code=use.getKeyCode();
        if (code<0) return code+100>=0 && code+100<Mouse.getButtonCount() && Mouse.isButtonDown(code+100);
        return code>0 && code<Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }

    @Override public void rejectDrag(UUID id,long sequence) {
        PreviewGrip g=grip;
        if (g!=null && g.id.equals(id) && g.sequence==sequence) g.rejected=true;
    }

    @Override
    public boolean deleteShortcutKey(int key) {
        return key != 0 && key == deleteTask.getKeyCode() && !Keyboard.isRepeatEvent();
    }

    @Override
    public int hotbarSlot(int key) {
        KeyBinding[] keys = Minecraft.getMinecraft().gameSettings.keyBindsHotbar;
        if (key != 0) for (int i = 0; i < keys.length; i++) if (keys[i].getKeyCode() == key) return i;
        return -1;
    }

    @SubscribeEvent
    public void worldKey(InputEvent.KeyInputEvent e) {
        if (Keyboard.getEventKeyState() && deleteShortcutKey(Keyboard.getEventKey())) deleteInWorld();
    }

    @SubscribeEvent
    public void worldMouseButton(InputEvent.MouseInputEvent e) {
        int button = Mouse.getEventButton();
        if (button >= 0 && Mouse.getEventButtonState() && deleteTask.getKeyCode() == button - 100) deleteInWorld();
    }

    private void deleteInWorld() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.thePlayer == null) return;
        String id = BuilderTasks.shortcutTarget(mc.thePlayer.getHeldItem());
        if (!id.isEmpty()) PreviewEdit.TaskAction.delete(id);
    }

    @Override
    public void previewEditResult(PreviewEdit.Result result) { editQueue.reply(result); }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void scrollPreview(MouseEvent event) {
        if (event.dwheel == 0) return;
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (!editContext(player)) return;
        int notches = (int) Math.min(PreviewEdit.MAX_STEPS, Math.max(1, Math.abs((long) event.dwheel) / 120));
        if (useDown()) {
            if (grip==null) beginGrip(worldHit(player), player);
            PreviewGrip g=grip;
            if (g!=null && !g.released) {
                if (!g.rejected) g.scroll(previewModifierDown(),event.dwheel>0 ? notches : -notches);
                event.setCanceled(true);
                return;
            }
        }
        if (!previewModifierDown()) return;
        Pick hit = choice(worldHit(player), true);
        PreviewEditQueue.Target target = hit == null ? editQueue.pendingAt(this::editTargetAimed) : editTarget(hit, player);
        if (target == null) return;
        editQueue.cycle(target, event.dwheel < 0 ? notches : -notches, System.nanoTime());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void previewInputTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc=Minecraft.getMinecraft();
        EntityPlayer player=mc.thePlayer;
        boolean context=editContext(player);
        long now=System.nanoTime();
        PreviewGrip g=grip;
        if (g==null) { if (context) editQueue.tick(now); return; }
        View view=views.get(g.id);
        if (g.released) {
            if (g.rejected || view == null || view.revision != g.revision || player == null
                    || player.dimension != g.dimension || now - g.releasedAt > 15_000_000_000L) grip = null;
            return;
        }
        if (!context || !player.worldObj.isAirBlock(g.origin.posX, g.origin.posY, g.origin.posZ)
                || player.dimension!=g.dimension || player.inventory.currentItem!=g.slot ||
                !g.tool.equals(player.getHeldItem().getTagCompound().getString("tool"))) {
            if (!g.released && player!=null && mc.getNetHandler()!=null)
                PreviewDragMessage.send(PreviewDragMessage.CANCEL,g.id,g.sequence,g.revision,null,null);
            grip=null; return;
        }
        if (view==null || view.revision!=g.revision || view.pose<0) g.rejected=true;
        if (!g.rejected && now-g.pressedAt>=300_000_000L) g.moving=true;
        if (g.moving && !g.rejected) g.track(player);
        if (!useDown()) {
            int op=g.rejected ? PreviewDragMessage.CANCEL : g.moving ? PreviewDragMessage.DROP : PreviewDragMessage.TAP;
            PreviewDragMessage.send(op,g.id,g.sequence,g.revision,g.at,g.facing);
            g.released=true; g.releasedAt=now;
            if (op!=PreviewDragMessage.DROP || !g.changed() && player.worldObj.isAirBlock(g.at.posX,g.at.posY,g.at.posZ)) grip=null;
        }
    }

    @SubscribeEvent
    public void previewInputUnload(WorldEvent.Unload event) {
        if (event.world.isRemote) { grip=null; editQueue.clear(); }
    }

    private boolean editContext(EntityPlayer player) {
        Minecraft minecraft = Minecraft.getMinecraft();
        ItemStack held = player == null ? null : player.getHeldItem();
        if (player == null || player.worldObj == null || !player.worldObj.isRemote || minecraft.currentScreen != null
                || held == null || held.getItem() != BuilderItem.ITEM || !held.hasTagCompound()
                || held.getTagCompound().getString("tool").isEmpty()) {
            editQueue.clear();
            return false;
        }
        editQueue.context(player.worldObj, held.getTagCompound().getString("tool"), player.dimension);
        return true;
    }

    private PreviewEditQueue.Target editTarget(Pick hit, EntityPlayer player) {
        return new PreviewEditQueue.Target(hit.taskId, hit.revision, player.dimension,
                player.getHeldItem().getTagCompound().getString("tool"), hit.x, hit.y, hit.z, hit.wholeGroup);
    }

    private boolean editTargetAimed(PreviewEditQueue.Target target) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft.thePlayer;
        ItemStack held = player == null ? null : player.getHeldItem();
        if (player == null || minecraft.currentScreen != null || player.dimension != target.dimension
                || held == null || held.getItem() != BuilderItem.ITEM || !held.hasTagCompound()
                || !target.tool.equals(held.getTagCompound().getString("tool"))) return false;
        WorldHit hit = worldHit(player);
        return hit != null && hit.owned && hit.view.fixedPose == null && hit.view.id.equals(target.task)
                && hit.at.posX == target.x && hit.at.posY == target.y && hit.at.posZ == target.z
                && choice(hit, false) != null;
    }

    private static Pick choice(WorldHit hit, boolean current) {
        if (hit == null || !hit.owned || !hit.projected || hit.view.fixedPose != null || !hit.view.editable
                || (hit.view.layers & PreviewStream.SHELL) == 0 || hit.section == null || !hit.section.hasChoice(hit.slot)) return null;
        Section s = hit.section;
        if (current && (s.epoch != hit.view.epoch || s.revision != hit.view.revision)) return null;
        return new Pick(hit.view.id, s.revision, hit.at.posX, hit.at.posY, hit.at.posZ, s.style(hit.slot));
    }

    private static final class HatchInfo {
        final String text;
        final HatchRole role;

        HatchInfo(String dotHint, String[] keys, boolean ambiguous) {
            text = HatchLabels.select(dotHint, keys, ambiguous);
            role = HatchLabels.role(dotHint, keys, ambiguous);
        }
    }

    private static final class View {
        final UUID id;
        final String idText;
        String tool;
        final Map<ChunkPosition, Section> sections = new LinkedHashMap<>();
        Bounds bounds;
        net.minecraft.util.ChunkCoordinates controller, issue;
        AxisAlignedBB box;
        int dim, layers, pose=-1;
        PreviewPose.Allowed allowed;
        boolean repairable;
        int controllerMeta, tier, ambiguousDots;
        String[] dotHints;
        final Map<List<String>, HatchInfo[]> labels = new HashMap<>();
        int epoch, revision;
        boolean editable, textured, locateHatches;
        final Map<Integer, Assembly> fragments = new HashMap<>();
        long lastCompilation;
        View previous;
        PreviewGrip fixedPose;
        boolean moveStreamReady, waitingForSite;

        View(UUID id) {
            this.id = id;
            idText = id.toString();
        }

        void invalidateLabels() {
            dotHints = null;
            labels.clear();
            for (Section s : sections.values()) {
                s.labels = null;
                s.markerRoles = null;
                if (s.hasHatches) s.marksDirty = s.outlineDirty = true;
            }
        }

        void clear() {
            releasePrevious();
            clearSections();
        }

        void releasePrevious() {
            if (previous != null) {
                previous.clear();
                previous = null;
            }
            moveStreamReady = false;
        }

        void clearSections() {
            for (Section section : sections.values()) section.clear();
            sections.clear();
            fragments.clear();
        }
    }

    private static final class Assembly {
        final byte[] bytes;
        final BitSet parts = new BitSet();
        final int count;
        Assembly(PreviewStream.Packet p) {
            bytes = new byte[p.totalBytes];
            count = p.parts;
        }
    }

    private boolean unload;
    private static final int HOST_COLOR = 0xba72fa;
    private static final int SOLID_MARKER = -2;
    private static final HatchRole[] HATCH_ROLES = HatchRole.values();
    private static final int SHELL_ALPHA = 100;
    private static final int[] COLORS = {
        0x317688, 0x61BFB5, 0x345D76, 0x91E2D7, 0x3D7792, 0x63A5BB
    };
    private static final int[] MATERIAL_COLORS = {
        0xe2ad64, 0x87c5df, 0x96cf98, 0xc4a1df, 0xe59491, 0xcdd47d, 0x92d6c9, 0xdba0c4
    };

    static final class Section {
        final View owner;
        final int x, y, z;
        final long[] solid, hatch;
        final byte[] dots;
        final HatchTypes hatchTypes;
        HatchInfo[][] labels;
        byte[] markerRoles;
        final long[] optional;
        final short[] styleSlots;
        final List<PreviewStream.Style> styles;
        final int[] colors;
        final int epoch, revision;
        boolean textured;
        short[] renderSlots;
        List<PreviewStream.RenderMaterial> renders;
        BlockPreviewTextures.Appearance[] appearances;
        long[] confirmed, occupied = EMPTY_MASK;
        final ProjectionSite site = new ProjectionSite();
        final Section[] near = new Section[6];
        final AxisAlignedBB box;
        int faces, glassFaces, marks, outline, marksRevision;
        double sortDistance;
        boolean facesTextured;
        boolean dirty = true, marksDirty = true, outlineDirty = true, hasHatches, hasShell;
        boolean replaceFaces, replaceMarks, replaceOutline;
        long lastCompilation;

        boolean needsMesh(int layers) {
            return (layers & PreviewStream.SHELL) != 0 && hasShell && dirty
                    || (layers & PreviewStream.HATCH) != 0 && hasHatches && (marksDirty || outlineDirty);
        }

        Section(View view, PreviewStream.Packet p) {
            owner=view;
            x = p.x * 16;
            y = p.y * 16;
            z = p.z * 16;
            solid = p.solid;
            hatch = p.hatch;
            dots = p.dots;
            hatchTypes = p.hatchTypes;
            optional = p.optional;
            styleSlots = p.styles.isEmpty() ? null : p.styleSlots;
            styles = p.styles;
            epoch = p.epoch;
            revision = p.revision;
            textured = view.textured && p.textured;
            renderSlots = textured ? p.renderSlots : null;
            renders = !textured || p.renders == null ? Collections.emptyList() : p.renders;
            colors = new int[styles.size()];
            for (int i = 0; i < colors.length; i++) colors[i] = materialColor(styles.get(i));
            confirmed = p.confirmed;
            updateVisibility(EMPTY_MASK);
            box = AxisAlignedBB.getBoundingBox(x, y, z, x + 16, y + 16, z + 16);
        }

        PreviewStream.Style style(int slot) {
            if (styleSlots == null) return null;
            int index = styleSlots[slot] & 65535;
            return index == 0 ? null : styles.get(index - 1);
        }

        BlockPreviewTextures.Appearance appearance(int slot) {
            int index = renderSlots == null ? 0 : renderSlots[slot] & 65535;
            if (index == 0 || index > renders.size()) return BlockPreviewTextures.FALLBACK;
            if (appearances == null) appearances = new BlockPreviewTextures.Appearance[renders.size()];
            BlockPreviewTextures.Appearance appearance = appearances[index - 1];
            if (appearance == null) {
                PreviewStream.RenderMaterial material = renders.get(index - 1);
                appearance = BlockPreviewTextures.get(material.block, material.meta, material.machine, material.front);
                appearances[index - 1] = appearance;
            }
            return appearance;
        }

        void dropTextures(boolean keepVisibleMesh) {
            textured = false;
            renderSlots = null;
            renders = Collections.emptyList();
            appearances = null;
            if (!keepVisibleMesh && facesTextured) clearFaces();
        }

        void clearFaces() {
            if (faces != 0) GL11.glDeleteLists(faces, 1);
            if (glassFaces != 0) GL11.glDeleteLists(glassFaces, 1);
            faces = glassFaces = 0;
        }

        int color(int slot) {
            if (styleSlots == null) return 0;
            int index = styleSlots[slot] & 65535;
            return index == 0 ? 0 : colors[index - 1];
        }

        boolean hasChoice(int slot) {
            return (confirmed[slot >> 6] & (1L << (slot & 63))) == 0 && choiceMaterial(slot);
        }

        boolean choiceMaterial(int slot) {
            PreviewStream.Style style = style(slot);
            return style != null && style.group >= 0;
        }

        HatchInfo hatchInfo(View view, int rank) {
            if (labels == null) labels = new HatchInfo[hatchTypes.size()][];
            int index = hatchTypes.index(rank);
            HatchInfo[] byDot = labels[index];
            if (byDot == null) {
                byDot = view.labels.computeIfAbsent(Arrays.asList(hatchTypes.at(rank)), k -> new HatchInfo[17]);
                labels[index] = byDot;
            }
            int dot = dots[rank] & 255;
            if (byDot[dot] == null) {
                if (view.dotHints == null) view.dotHints = HatchLabels.dotHints(view.controllerMeta, view.tier);
                String hint = dot < view.dotHints.length ? view.dotHints[dot] : null;
                byDot[dot] = new HatchInfo(hint, hatchTypes.at(rank), (view.ambiguousDots & (1 << dot)) != 0);
            }
            return byDot[dot];
        }

        byte[] markerRoles(View view) {
            if (markerRoles == null) {
                markerRoles = new byte[dots.length];
                for (int i = 0; i < markerRoles.length; i++) markerRoles[i] = (byte) hatchInfo(view, i).role.ordinal();
            }
            return markerRoles;
        }

        int updateVisibility(long[] mask) {
            hasHatches = false;
            hasShell = false;
            boolean addedShell = false, addedHatch = false, shellChanged = false, hatchChanged = false;
            int borders = 0;
            for (int i = 0; i < hatch.length; i++) {
                long shell = solid[i] | optional[i];
                long changed = occupied[i] ^ mask[i];
                hasShell |= (shell & ~mask[i]) != 0;
                hasHatches |= (hatch[i] & ~mask[i]) != 0;
                shellChanged |= (changed & shell) != 0;
                addedShell |= (changed & shell & mask[i]) != 0;
                if ((changed & hatch[i]) != 0) {
                    hatchChanged = true;
                    addedHatch |= (changed & hatch[i] & mask[i]) != 0;
                    borders |= borderSides(i, changed & hatch[i]);
                }
            }
            // Never replay old geometry over a newly occupied cell while a replacement list is pending.
            if (addedShell || !hasShell) clearFaces();
            if (addedHatch || !hasHatches) clearHatches();
            if (shellChanged) dirty = true;
            if (hatchChanged) marksDirty = outlineDirty = true;
            return borders;
        }

        void clear() {
            site.close();
            clearFaces();
            clearHatches();
        }

        void clearHatches() {
            if (marks != 0) GL11.glDeleteLists(marks, 1);
            if (outline != 0) GL11.glDeleteLists(outline, 1);
            marks = outline = 0;
            marksDirty = outlineDirty = true;
        }
    }

    @Override
    public void register() {
        instance = this;
        ClientRegistry.registerKeyBinding(deleteTask);
        IResourceManager resources = Minecraft.getMinecraft().getResourceManager();
        if (resources instanceof IReloadableResourceManager)
            ((IReloadableResourceManager) resources).registerReloadListener(reloaded -> {
                HatchLabels.clear();
                for (View view : views.values()) {
                    view.invalidateLabels();
                    if (view.previous != null) view.previous.invalidateLabels();
                }
                tooltip.clear();
                textureReload = true;
            });
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void textureStitch(TextureStitchEvent.Post event) { textureReload = true; }

    private void reloadPreviewTextures() {
        if (!textureReload) return;
        textureReload = false;
        BlockPreviewTextures.clear();
        PreviewConnectedTextures.clear();
        for (View view : views.values()) {
            invalidateTextures(view);
            if (view.previous != null) invalidateTextures(view.previous);
        }
    }

    private static void invalidateTextures(View view) {
        for (Section section : view.sections.values()) {
            section.appearances = null;
            if (section.facesTextured && (section.faces != 0 || section.glassFaces != 0)) {
                // Old atlas UVs cannot be retained across a resource-pack rebuild.
                section.clearFaces();
                section.dirty = true;
            }
        }
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
        if (cachedHatchSection != null && cachedHatchSection.owner.id.equals(id)) clearHatchSelection();
        if (views.isEmpty()) observe(null);
        visibleHatches.clear();
        transparentSections.clear();
    }

    private void acceptMove(PreviewStream.Packet p) {
        PreviewGrip released = grip;
        View old = views.get(p.id);
        if (released == null || old == null || !released.released || released.rejected
                || !p.id.equals(released.id) || p.sequence != released.sequence || p.dim != released.dimension
                || old.revision != released.revision || p.revision != old.revision + 1) return;
        View replacement = new View(p.id);
        replacement.dim = p.dim;
        replacement.epoch = p.epoch;
        replacement.revision = p.revision;
        replacement.tool = old.tool;
        replacement.layers = old.layers;
        replacement.controller = new ChunkCoordinates(released.at);
        if (p.pose >= 0 && p.pose < ExtendedFacing.VALUES.length) released.orient(ExtendedFacing.VALUES[p.pose]);
        old.fixedPose = released;
        replacement.previous = old;
        views.put(p.id, replacement);
        grip = null;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        BrowserRenderer.cleanupClosed();
        reloadPreviewTextures();
        if (unload) {
            observe(null);
            clearHatchSelection();
            BlockPreviewTextures.clear();
            PreviewConnectedTextures.clear();
            visibleHatches.clear();
            transparentSections.clear();
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
            if (p.type == PreviewStream.MOVE_ACCEPTED) {
                acceptMove(p);
                continue;
            }
            if (p.type == 4) {
                View v = views.computeIfAbsent(p.id, View::new);
                v.dim = p.dim;
                boolean controllerOn = (p.layers & 1) != 0;
                if ((v.controller != null) != controllerOn) {
                    Section at = v.sections.get(new ChunkPosition(p.x >> 4, p.y >> 4, p.z >> 4));
                    if (at != null) at.dirty = true;
                }
                v.controller = controllerOn ? new ChunkCoordinates(p.x, p.y, p.z) : null;
                v.issue = p.issue;
                v.pose=p.pose;
                v.allowed=p.allowed;
                v.repairable = (p.layers & 4) != 0;
                continue;
            }
            if (p.type == 1) {
                View view = views.computeIfAbsent(p.id, View::new);
                if (p.epoch < view.epoch) continue;
                if (view.previous != null && (p.epoch != view.epoch || p.revision != view.revision || p.layers != view.previous.layers
                        || p.textured != view.previous.textured)) view.releasePrevious();
                if (p.reset) view.clearSections();
                if (view.epoch != p.epoch) view.fragments.clear();
                view.epoch = p.epoch;
                view.revision = p.revision;
                view.editable = p.editable;
                view.locateHatches = p.locateHatches;
                if (view.textured != p.textured)
                    for (Section s : view.sections.values()) {
                        s.site.dirty = true;
                        if (s.hasShell) s.dirty = true;
                        if (!p.textured) s.dropTextures((p.layers & PreviewStream.SHELL) != 0);
                    }
                view.textured = p.textured;
                view.tool = p.tool;
                view.bounds = p.bounds;
                view.box = AxisAlignedBB.getBoundingBox(p.bounds.minX, p.bounds.minY, p.bounds.minZ,
                        p.bounds.maxX + 1.0, p.bounds.maxY + 1.0, p.bounds.maxZ + 1.0);
                if (((view.layers ^ p.layers) & PreviewStream.HATCH) != 0)
                    for (Section s : view.sections.values()) if (s.hasHatches) s.dirty = true;
                view.layers = p.layers;
                view.dim = p.dim;
                if (view.controllerMeta != p.controllerMeta || view.tier != p.tier
                        || view.ambiguousDots != p.ambiguousDots || p.reset) view.invalidateLabels();
                view.controllerMeta = p.controllerMeta;
                view.tier = p.tier;
                view.ambiguousDots = p.ambiguousDots;
                continue;
            }
            View view = views.get(p.id);
            if (view == null || view.dim != p.dim || view.epoch != p.epoch) continue;
            if (p.type == PreviewStream.MOVE_READY) {
                if (view.previous != null && view.previous.fixedPose.sequence == p.sequence
                        && view.revision == p.revision) view.moveStreamReady = true;
                continue;
            }
            if (p.type == 6) {
                if (!view.fragments.containsKey(p.transfer) && view.fragments.size() >= 2) continue;
                Assembly assembly = view.fragments.get(p.transfer);
                if (assembly == null) {
                    assembly = new Assembly(p);
                    view.fragments.put(p.transfer, assembly);
                }
                if (assembly.bytes.length != p.totalBytes || assembly.count != p.parts) continue;
                System.arraycopy(p.payload, 0, assembly.bytes, p.part * 28000, p.payload.length);
                assembly.parts.set(p.part);
                if (assembly.parts.cardinality() != assembly.count) continue;
                view.fragments.remove(p.transfer);
                ByteBuf bytes = Unpooled.wrappedBuffer(assembly.bytes);
                try {
                    PreviewStream.Packet complete = new PreviewStream.Packet();
                    complete.fromBytes(bytes);
                    if ((complete.type != 2 && complete.type != 7) || !complete.id.equals(p.id)
                            || complete.epoch != view.epoch) continue;
                    p = complete;
                } finally {
                    bytes.release();
                }
            }
            ChunkPosition pos = new ChunkPosition(p.x, p.y, p.z);
            if (p.type == 3) {
                Section section = view.sections.get(pos);
                if (section != null) {
                    section.confirmed = p.confirmed;
                }
                continue;
            }
            if (p.type != 2 && p.type != 7) continue;
            Section s = new Section(view, p);
            Section old = view.sections.put(pos, s);
            invalidateTextureNeighbors(view, p.x, p.y, p.z);
            if (old != null) {
                s.updateVisibility(old.occupied);
                s.occupied = old.occupied;
            }
            boolean sameRoles = old != null && Arrays.equals(old.hatch, s.hatch)
                    && Arrays.equals(old.dots, s.dots) && s.hatchTypes.sameAs(old.hatchTypes);
            invalidateHatchNeighbors(view, s, changedHatchBorders(old, s, sameRoles), false);
            if (old != null) {
                // A replacement projection must not punch a hole while waiting for its paced mesh rebuild.
                if (s.hasShell) {
                    s.faces = old.faces;
                    s.glassFaces = old.glassFaces;
                    s.facesTextured = old.facesTextured;
                    old.faces = old.glassFaces = 0;
                }
                if (sameRoles) {
                    s.marks = old.marks;
                    s.marksDirty = old.marksDirty;
                    s.outline = old.outline;
                    s.outlineDirty = old.outlineDirty;
                    s.labels = old.labels;
                    s.markerRoles = old.markerRoles;
                    old.marks = old.outline = 0;
                }
                old.clear();
            }
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
        // Keep highlighting the visible material between the revision header and its section payload.
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        WorldHit hit = grip == null || grip.rejected ? worldHit(player) : null;
        aimed = choice(hit, false);
        updateAimedMarker(hit);
        applyHighlight(player, hit);
    }

    private static void invalidateHatchNeighbors(View view, Section section, int borders, boolean presenting) {
        if (borders == 0) return;
        // Four cells share a grid edge. Two sections meeting at that edge can affect one another.
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int distance = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            if (distance == 0 || distance == 3) continue;
            int sides = (dx < 0 ? 1 : dx > 0 ? 2 : 0) | (dy < 0 ? 4 : dy > 0 ? 8 : 0) | (dz < 0 ? 16 : dz > 0 ? 32 : 0);
            if ((borders & sides) != sides) continue;
            Section near = view.sections.get(new ChunkPosition((section.x >> 4) + dx, (section.y >> 4) + dy, (section.z >> 4) + dz));
            if (near == null || !near.hasHatches) continue;
            near.outlineDirty = true;
            near.replaceOutline |= presenting && near.outline != 0;
            if (distance == 1) {
                near.marksDirty = true;
                near.replaceMarks |= presenting && near.marks != 0;
            }
        }
    }

    private static void invalidateTextureNeighbors(View view, int x, int y, int z) {
        if (!view.textured) return;
        // CTM and native world icons can consult diagonals and the previous/next section layer.
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            Section neighbor = view.sections.get(new ChunkPosition(x + dx, y + dy, z + dz));
            if (neighbor != null && neighbor.hasShell) neighbor.dirty = true;
        }
    }

    private static int changedHatchBorders(Section old, Section current, boolean sameRoles) {
        int sides = 0;
        for (int word = 0; word < current.hatch.length && sides != 63; word++) {
            long before = old == null ? 0 : old.hatch[word];
            long after = current.hatch[word];
            long changed = before | after;
            if (sameRoles) changed = (before ^ after) | ((old.occupied[word] ^ current.occupied[word]) & changed);
            if (changed != 0) sides |= borderSides(word, changed);
        }
        return sides;
    }

    // Each word covers four rows of sixteen X coordinates, all at the same Y.
    private static int borderSides(int word, long cells) {
        int sides = 0;
        if ((cells & 0x0001000100010001L) != 0) sides |= 1;
        if ((cells & 0x8000800080008000L) != 0) sides |= 2;
        if (word < 4) sides |= 4;
        if (word >= 60) sides |= 8;
        if ((word & 3) == 0 && (cells & 0xffffL) != 0) sides |= 16;
        if ((word & 3) == 3 && (cells >>> 48) != 0) sides |= 32;
        return sides;
    }

    private static int changedBorders(long[] before, long[] after) {
        int sides = 0;
        for (int word = 0; word < before.length && sides != 63; word++) {
            long changed = before[word] ^ after[word];
            if (changed != 0) sides |= borderSides(word, changed);
        }
        return sides;
    }

    private static final class WorldHit {
        final View view;
        final Section section;
        final ChunkCoordinates at;
        final int slot;
        final boolean owned, projected, host, hatch, issue;
        WorldHit(View view, Section section, int slot, int x, int y, int z, boolean owned,
                boolean projected, boolean host, boolean hatch, boolean issue) {
            this.view = view; this.section = section; this.slot = slot;
            this.at = new ChunkCoordinates(x, y, z); this.owned = owned;
            this.projected = projected; this.host = host; this.hatch = hatch; this.issue = issue;
        }
        boolean marker() { return host || hatch || issue; }
    }

    private WorldHit worldHit(EntityPlayer player) {
        if (player == null || player.worldObj == null || Minecraft.getMinecraft().currentScreen != null || views.isEmpty()) return null;
        ItemStack held = player.getHeldItem();
        boolean terminal = held != null && held.getItem() == BuilderItem.ITEM && held.hasTagCompound();
        String tool = terminal ? held.getTagCompound().getString("tool") : "";
        String selected = terminal ? held.getTagCompound().getString("selected") : "";
        WorldHit[] result = {null};
        PreviewAim.first(player, (x, y, z) -> {
            for (View incoming : views.values()) {
                if (incoming.dim != player.dimension) continue;
                View v = incoming.previous != null ? incoming.previous : incoming;
                boolean owned = !tool.isEmpty() && tool.equals(v.tool);
                WorldHit best = result[0];
                if (best != null && !PreviewAim.prefer(owned, v.idText, best.owned, best.view.idText, selected)) continue;
                WorldHit hit = hitAt(v, player.worldObj, x, y, z, owned);
                if (hit != null) result[0] = hit;
            }
            return result[0] != null;
        });
        return result[0];
    }

    private WorldHit hitAt(View view, World world, int worldX, int worldY, int worldZ, boolean owned) {
        int x = worldX, y = worldY, z = worldZ;
        PreviewGrip pose = view.fixedPose;
        if (pose != null) {
            highlightOffset[0] = x - pose.at.posX; highlightOffset[1] = y - pose.at.posY; highlightOffset[2] = z - pose.at.posZ;
            pose.facing.getOffsetABC(highlightOffset, highlightLocal);
            pose.original.getWorldOffset(highlightLocal, highlightOffset);
            x = pose.origin.posX + highlightOffset[0]; y = pose.origin.posY + highlightOffset[1]; z = pose.origin.posZ + highlightOffset[2];
        }
        boolean roles = (view.layers & PreviewStream.HATCH) != 0, shells = (view.layers & PreviewStream.SHELL) != 0;
        boolean issue = view.repairable && view.issue != null && view.issue.posX == x && view.issue.posY == y && view.issue.posZ == z;
        boolean controller = view.controller != null && view.controller.posX == x && view.controller.posY == y && view.controller.posZ == z;
        if (!issue && !(roles && controller) && (view.bounds == null || !view.bounds.contains(x,y,z))) return null;
        Section s = view.sections.get(new ChunkPosition(x >> 4, y >> 4, z >> 4));
        int slot = (y & 15) << 8 | (z & 15) << 4 | (x & 15), word = slot >> 6;
        long bit = 1L << (slot & 63);
        boolean hatch = s != null && (s.hatch[word] & bit) != 0;
        boolean structure = s != null && ((s.solid[word] | s.optional[word]) & bit) != 0;
        boolean drawn = roles && controller || s != null && (s.occupied[word] & bit) == 0
                && (roles && hatch ? s.marks != 0 || s.outline != 0 : shells && structure && s.faces != 0);
        boolean air = world.isAirBlock(worldX, worldY, worldZ);
        if (!issue && !(air ? drawn : owned && (controller || structure || hatch))) return null;
        return new WorldHit(view, s, slot, worldX, worldY, worldZ, owned, air && drawn,
                controller && (roles || shells && structure), hatch && (roles || shells && structure), issue);
    }

    private void updateHighlight(EntityPlayer player) {
        applyHighlight(player, grip == null || grip.rejected ? worldHit(player) : null);
    }

    private void applyHighlight(EntityPlayer player, WorldHit hit) {
        highlighted = null; highlightedController = null; highlightedHatch = null;
        if (hit == null || !hit.owned || !hit.projected || player.getHeldItem() == null) return;
        highlighted = hit.at;
        boolean roles = (hit.view.layers & PreviewStream.HATCH) != 0;
        if (roles && hit.host) highlightedController = hit.view;
        else if (roles && hit.hatch) { highlightedHatch = hit.section; highlightedHatchSlot = hit.slot; }
        highlightColor = roles && (hit.host || hit.hatch) ? SOLID_MARKER : hit.section.color(hit.slot);
    }

    private boolean shouldDrawPick(EntityPlayer player) {
        if (highlighted == null) return false;
        int x = highlighted.posX, y = highlighted.posY, z = highlighted.posZ;
        if (x == MathHelper.floor_double(player.posX) && z == MathHelper.floor_double(player.posZ)
                && y < player.boundingBox.maxY && y + 1.0 > player.boundingBox.minY) return false;
        return player.worldObj.isAirBlock(x, y, z);
    }

    private static int hatchRank(Section section, int slot) {
        int word = slot >> 6;
        int rank = Long.bitCount(section.hatch[word] & ((1L << (slot & 63)) - 1));
        for (int i = 0; i < word; i++) rank += Long.bitCount(section.hatch[i]);
        return rank;
    }

    private void updateAimedMarker(WorldHit marker) {
        aimedMarker = null;
        aimedHint = null;
        aimedHatch = false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.currentScreen != null || views.isEmpty()) return;
        PreviewGrip g=grip;
        if (g!=null && !g.rejected) {
            aimed=null;
            aimedColor=HOST_COLOR;
            aimedMarker=StatCollector.translateToLocal("structurebuildernh.markerHost")+"   "+g.at.posX+", "+g.at.posY+", "+g.at.posZ;
            aimedHint=!g.moving ? StatCollector.translateToLocal("structurebuildernh.drag.hold")
                    : g.limited ? StatCollector.translateToLocal("structurebuildernh.drag.limited")
                    : StatCollector.translateToLocalFormatted("structurebuildernh.drag.controls",PreviewTooltip.modifierName(mc));
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (marker != null && marker.marker() && (marker.owned || held == null || held.getItem() != BuilderItem.ITEM)) {
            aimed = null;
            View v = marker.view;
            ChunkCoordinates p = marker.at;
            if (marker.host) {
                aimedMarker = StatCollector.translateToLocal("structurebuildernh.markerHost");
                aimedColor = HOST_COLOR;
                if (canGrip(marker, mc.thePlayer)) aimedHint=StatCollector.translateToLocal("structurebuildernh.drag.hold");
            } else if (marker.issue) {
                aimedMarker = StatCollector.translateToLocal("structurebuildernh.markerHatch");
                aimedColor = HOST_COLOR;
                aimedHatch = true;
            } else {
                Section s = marker.section;
                int i = marker.slot;
                HatchInfo label = s.hatchInfo(v, hatchRank(s, i));
                aimedMarker = StatCollector.translateToLocal("structurebuildernh.markerHatch");
                aimedHatch = true;
                aimedColor = label.role.color;
                aimedHint = label.text;
            }
            return;
        }
        if (aimed != null) {
            PreviewStream.Style style = aimed.style;
            String material = style.air ? StatCollector.translateToLocal("structurebuildernh.previewAir")
                    : materialName(style);
            if (style.conflict) material += " · " + StatCollector.translateToLocal("structurebuildernh.previewConflict");
            aimedMarker = material;
            aimedColor = materialColor(style);
            if (mc.gameSettings.keyBindSneak.getKeyCode() != 0) {
                aimedHint = previewModifierDown()
                        ? StatCollector.translateToLocalFormatted("structurebuildernh."
                                + (style.groupSelectable ? "previewPickHint" : "previewPickSingleHint"), PreviewTooltip.modifierName(mc))
                        : StatCollector.translateToLocalFormatted("structurebuildernh.previewChooseHint",
                                PreviewTooltip.modifierName(mc));
            }
            return;
        }
    }

    @SubscribeEvent
    public void markerLabel(net.minecraftforge.client.event.RenderGameOverlayEvent.Post e) {
        if (e.type != net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL
                || aimedMarker == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
        tooltip.draw(mc, e.resolution, aimedMarker, aimedHint, aimedColor, aimedHatch);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void render(RenderWorldLastEvent e) {
        reloadPreviewTextures();
        if (views.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (TerminalBrowserWidget.coversWorld(mc.currentScreen)) return;
        Entity eye = mc.renderViewEntity;
        if (eye == null) return;
        observe(eye.worldObj);
        occupancy.drain();
        samplingBudget = 2_000_000L;
        double x = eye.lastTickPosX + (eye.posX - eye.lastTickPosX) * e.partialTicks;
        double y = eye.lastTickPosY + (eye.posY - eye.lastTickPosY) * e.partialTicks;
        double z = eye.lastTickPosZ + (eye.posZ - eye.lastTickPosZ) * e.partialTicks;
        cameraX = x; cameraY = y; cameraZ = z;
        Frustrum camera = new Frustrum();
        camera.setPosition(x, y, z);
        GL11.glPushAttrib(
                GL11.GL_ENABLE_BIT
                        | GL11.GL_DEPTH_BUFFER_BIT
                        | GL11.GL_COLOR_BUFFER_BIT
                        | GL11.GL_CURRENT_BIT
                        | GL11.GL_TEXTURE_BIT
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
            transparentSections.clear();
            visibleControllers.clear();
            View nextView = null;
            Section nextSection = null;
            ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
            boolean terminal = held != null && held.getItem() == BuilderItem.ITEM;
            for (View incoming : views.values()) {
                if (eye.dimension != incoming.dim) continue;
                Section warming = null;
                View view = incoming;
                if (incoming.previous != null) {
                    warming = pendingMoveMesh(incoming, camera);
                    if (warming == null && !incoming.waitingForSite && incoming.moveStreamReady) {
                        incoming.releasePrevious();
                    } else {
                        view = incoming.previous;
                        if (warming != null && (nextView == null || incoming.lastCompilation < nextView.lastCompilation)) {
                            nextView = incoming;
                            nextSection = warming;
                        }
                    }
                }

                PreviewGrip moving=renderGrip(view);
                if (view.box != null && !(moving==null ? camera.isBoundingBoxInFrustum(view.box) : moving.visible(camera,view.box))) continue;
                if (view.controller != null && (view.layers & PreviewStream.HATCH) != 0) {
                    ChunkCoordinates at = moving == null ? view.controller : moving.at;
                    if (moving != null && !moving.released || !occupancy.occupied(at.posX, at.posY, at.posZ))
                        visibleControllers.add(view);
                }
                if (view.box == null) continue;
                if ((view.layers & PreviewStream.BOX) != 0) {
                    GL11.glPushMatrix();
                    if (moving!=null) moving.transform();
                    drawBounds(view.bounds);
                    GL11.glPopMatrix();
                }
                if ((view.layers & (PreviewStream.SHELL | PreviewStream.HATCH)) == 0) continue;
                Section candidate = drawSections(view, camera);
                if (candidate != null && (nextView == null || view.lastCompilation < nextView.lastCompilation)) {
                    nextView = view;
                    nextSection = candidate;
                }
            }
            drawTransparentSections();
            boolean drawSelection = terminal && shouldDrawPick(mc.thePlayer);
            drawMarkers(terminal, drawSelection && highlightColor == SOLID_MARKER);
            if (drawSelection && highlightColor != SOLID_MARKER) drawPick(highlighted, highlightColor);
            drawHatchOutlines(terminal);
            for (View view : views.values())
                if (eye.dimension == view.dim && view.issue != null) drawIssue(view.issue);
            if (nextView != null) {
                nextView.lastCompilation = ++compilations;
                nextSection.lastCompilation = compilations;
                if ((nextView.layers & PreviewStream.HATCH) != 0 && nextSection.hasHatches && nextSection.marksDirty)
                    rebuildMarks(nextSection);
                else if (nextSection.dirty && nextSection.hasShell && (nextView.layers & PreviewStream.SHELL) != 0)
                    rebuildFaces(nextSection);
                else rebuildOutline(nextSection);
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void drawMarkers(boolean held, boolean highlightMarker) {
        if (visibleHatches.isEmpty() && visibleControllers.isEmpty()) return;
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(EXTBlendColor.GL_CONSTANT_ALPHA_EXT, EXTBlendColor.GL_ONE_MINUS_CONSTANT_ALPHA_EXT);
        // Only empty world cells contribute marker faces. Keep the exterior and its merged edges aligned.
        if (held) GL11.glDisable(GL11.GL_BLEND);
        else GL14.glBlendColor(0, 0, 0, SHELL_ALPHA / 255f);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(held);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(1f, 1f);
        drawVisibleCells(highlightMarker);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDepthMask(false);
    }

    private void drawHatchOutlines(boolean held) {
        if (!visibleHatches.isEmpty()) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_LINE_STIPPLE);
            GL11.glLineWidth(1.5f);
            GL11.glColor4f(.90f, .95f, .96f, held ? .90f : SHELL_ALPHA / 255f);
            // The faces are biased back, so the edges can use normal world depth.
            for (Section s : visibleHatches) if (s.outline != 0) drawSectionList(s, s.outline);
        }
    }

    private void drawVisibleCells(boolean highlightMarker) {
        for (Section s : visibleHatches) if (s.marks != 0) {
            int list = highlightMarker && s == highlightedHatch && !s.marksDirty ? hoveredHatchList(s) : s.marks;
            drawSectionList(s, list);
        }
        if (!visibleControllers.isEmpty()) {
            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            for (View view : visibleControllers) {
                PreviewGrip moving = renderGrip(view);
                ChunkCoordinates p = moving == null ? view.controller : moving.at;
                int color = highlightMarker && view == highlightedController ? markerTint(HOST_COLOR) : HOST_COLOR;
                for (int side = 0; side < 6; side++) {
                    int axis = side / 2;
                    int plane = axis == 0 ? p.posX : axis == 1 ? p.posY : p.posZ;
                    int u = axis == 0 ? p.posY : axis == 1 ? p.posZ : p.posX;
                    int v = axis == 0 ? p.posZ : axis == 1 ? p.posX : p.posY;
                    markerFace(t, side, plane + (side & 1), u, v, 1, 1, color);
                }
            }
            t.draw();
        }
    }

    private static void markerFace(Tessellator t, int side, int plane, int u, int v, int width, int height, int color) {
        int axis = side / 2;
        boolean positive = (side & 1) != 0;
        t.setColorRGBA_I(color, 255);
        vertex(t, axis, plane, u, v);
        vertex(t, axis, plane, positive ? u + width : u, positive ? v : v + height);
        vertex(t, axis, plane, u + width, v + height);
        vertex(t, axis, plane, positive ? u : u + width, positive ? v + height : v);
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
        PreviewGrip moving=renderGrip(view);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_CULL_FACE);
        boolean mirrored = moving != null && moving.mirrored;
        GL11.glCullFace(mirrored ? GL11.GL_FRONT : GL11.GL_BACK);
        // Real blocks win the depth test while a paced packet or mesh rebuild is pending.
        GL11.glPolygonOffset(1f, 1f);
        visibleSections.clear();
        for (Section s : view.sections.values()) {
            if (!(moving==null ? camera.isBoundingBoxInFrustum(s.box) : moving.visible(camera,s.box))) continue;
            if (refreshSite(s, moving, true)) visibleSections.add(s);
        }
        // Resolve all visible neighbour masks first. Replace previously drawn mixed sections in
        // the same frame, so a newly placed block never blanks the other cells in its batch.
        for (Section s : visibleSections) {
            if (s.replaceFaces && s.hasShell && (view.layers & PreviewStream.SHELL) != 0) rebuildFaces(s);
            if (s.replaceMarks && s.hasHatches && (view.layers & PreviewStream.HATCH) != 0) rebuildMarks(s);
            if (s.replaceOutline && s.hasHatches && (view.layers & PreviewStream.HATCH) != 0) rebuildOutline(s);
            GL11.glPushMatrix();
            translateSection(s, moving);
            boolean shell = (view.layers & PreviewStream.SHELL) != 0 && s.hasShell;
            boolean hatches = (view.layers & PreviewStream.HATCH) != 0 && s.hasHatches;
            if (hatches && (s.marks != 0 || s.outline != 0)) visibleHatches.add(s);
            if (s.needsMesh(view.layers)
                    && (candidate == null || s.lastCompilation < candidate.lastCompilation)) candidate = s;
            if (shell && s.faces != 0) {
                GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                if (s.facesTextured) {
                    Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    GL11.glEnable(GL11.GL_ALPHA_TEST);
                    GL11.glAlphaFunc(GL11.GL_GREATER, 1f / 255f);
                }
                GL11.glCallList(s.faces);
                if (s.facesTextured) {
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glDisable(GL11.GL_ALPHA_TEST);
                }
                GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            }
            if (shell && s.facesTextured && s.glassFaces != 0) {
                double dx = s.x + 8 - cameraX, dy = s.y + 8 - cameraY, dz = s.z + 8 - cameraZ;
                s.sortDistance = moving==null ? dx * dx + dy * dy + dz * dz
                        : moving.distance(s.x+8,s.y+8,s.z+8,cameraX,cameraY,cameraZ);
                transparentSections.add(s);
            }
            GL11.glPopMatrix();
        }
        visibleSections.clear();
        if (mirrored) GL11.glCullFace(GL11.GL_BACK);
        return candidate;
    }

    /** Warm the destination under the normal one-list-per-frame budget while the old lists stay visible. */
    private Section pendingMoveMesh(View destination, Frustrum camera) {
        destination.waitingForSite = false;
        if (destination.box == null || !camera.isBoundingBoxInFrustum(destination.box)) return null;
        Section candidate = null;
        for (Section s : destination.sections.values()) {
            if (!camera.isBoundingBoxInFrustum(s.box)) continue;
            if (!refreshSite(s, null, false)) {
                destination.waitingForSite = true;
                continue;
            }
            if (s.needsMesh(destination.layers)
                    && (candidate == null || s.lastCompilation < candidate.lastCompilation)) candidate = s;
        }
        return candidate;
    }

    private void observe(World world) {
        if (observedWorld == world) return;
        if (occupancy != null) occupancy.close();
        observedWorld = world;
        occupancy = world == null ? null : new ProjectionOccupancy(world);
    }

    private boolean refreshSite(Section s, PreviewGrip moving, boolean presenting) {
        s.site.bind(occupancy, moving, s.x, s.y, s.z);
        if (!s.site.dirty) return true;
        boolean freeDrag = moving != null && !moving.released;
        boolean faces = presenting && s.faces != 0 && (s.owner.layers & PreviewStream.SHELL) != 0;
        boolean marks = presenting && s.marks != 0 && (s.owner.layers & PreviewStream.HATCH) != 0;
        boolean outline = presenting && s.outline != 0 && (s.owner.layers & PreviewStream.HATCH) != 0;
        if (!freeDrag && samplingBudget <= 0 && !faces && !marks && !outline) return false;
        long start = System.nanoTime();
        s.site.read(s, sampledOccupancy, s.owner.textured);
        if (!Arrays.equals(s.occupied, sampledOccupancy)) {
            int shellBorders = changedBorders(s.occupied, sampledOccupancy);
            int borders = s.updateVisibility(sampledOccupancy);
            s.occupied = Arrays.equals(sampledOccupancy, EMPTY_MASK) ? EMPTY_MASK : sampledOccupancy.clone();
            s.replaceFaces |= faces && s.hasShell && s.faces == 0;
            s.replaceMarks |= marks && s.hasHatches && s.marks == 0;
            s.replaceOutline |= outline && s.hasHatches && s.outline == 0;
            invalidateHatchNeighbors(s.owner, s, borders, presenting && (s.owner.layers & PreviewStream.HATCH) != 0);
            for (int side = 0; side < s.near.length; side++) {
                Section near = s.near[side];
                if (near == null || (shellBorders & (1 << side)) == 0) continue;
                near.dirty = true;
                near.replaceFaces |= presenting && near.faces != 0 && (s.owner.layers & PreviewStream.SHELL) != 0;
            }
            invalidateTextureNeighbors(s.owner, s.x >> 4, s.y >> 4, s.z >> 4);
        } else if (s.site.materialsChanged) {
            // A replacement can change CTM neighbours even when the cell stays occupied.
            invalidateTextureNeighbors(s.owner, s.x >> 4, s.y >> 4, s.z >> 4);
        }
        if (!freeDrag) samplingBudget -= System.nanoTime() - start;
        return true;
    }

    private static void rebuildMarks(Section s) {
        s.marks = compileHatches(s.owner, s);
        s.marksDirty = s.marks == 0;
        s.replaceMarks = false;
    }

    private static void rebuildOutline(Section s) {
        s.outline = compileHatchOutline(s.owner, s);
        s.outlineDirty = s.outline == 0;
        s.replaceOutline = false;
    }

    private static void rebuildFaces(Section s) {
        boolean textured = s.owner.textured && s.textured;
        s.faces = textured ? compileTexturedShell(s.owner, s, s.faces) : compileShell(s.owner, s, s.faces);
        if (!textured && s.faces != 0 && s.glassFaces != 0) {
            GL11.glDeleteLists(s.glassFaces, 1);
            s.glassFaces = 0;
        }
        if (s.faces != 0) s.facesTextured = textured;
        s.dirty = s.faces == 0;
        s.replaceFaces = false;
    }

    private void drawTransparentSections() {
        if (transparentSections.isEmpty()) return;
        transparentSections.sort((a, b) -> Double.compare(b.sortDistance, a.sortDistance));
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 1f / 255f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        GL11.glPolygonOffset(1f, 1f);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        for (Section section : transparentSections) drawSectionList(section, section.glassFaces);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    private PreviewGrip renderGrip(View v) {
        if (v != null && v.fixedPose != null) return v.fixedPose;
        PreviewGrip g=grip;
        return g!=null && g.moving && !g.rejected && v!=null && v.revision==g.revision && g.id.equals(v.id) ? g : null;
    }

    private static void translateSection(Section section, PreviewGrip grip) {
        if (grip != null) grip.transform();
        GL11.glTranslatef(section.x,section.y,section.z);
    }

    private void drawSectionList(Section section, int list) {
        PreviewGrip grip = renderGrip(section.owner);
        boolean mirrored = grip != null && grip.mirrored;
        GL11.glPushMatrix();
        if (mirrored) GL11.glCullFace(GL11.GL_FRONT);
        translateSection(section, grip);
        GL11.glCallList(list);
        if (mirrored) GL11.glCullFace(GL11.GL_BACK);
        GL11.glPopMatrix();
    }

    private static int compileHatches(View view, Section s) {
        int list = s.marks != 0 ? s.marks : GL11.glGenLists(1);
        if (list == 0) return 0;
        GL11.glNewList(list, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        emitHatchFaces(view, s, (side, plane, u, v, width, height, role) ->
                markerFace(t, side, plane, u, v, width, height, HATCH_ROLES[role].color));
        t.draw();
        GL11.glEndList();
        s.marksRevision++;
        return list;
    }

    private static void emitHatchFaces(View view, Section s, HatchMesh.Face faces) {
        HatchMesh.emit(s.hatch, s.occupied, s.markerRoles(view), (side, slot) -> {
            Section neighbor = s.near[side];
            int word = slot >> 6;
            return neighbor != null && (neighbor.hatch[word] & ~neighbor.occupied[word] & (1L << (slot & 63))) != 0;
        }, faces);
    }

    private static int markerTint(int color) {
        return color + shade(0xffffff - color, 40);
    }

    private int hoveredHatchList(Section s) {
        if (hoverMarks == 0) hoverMarks = GL11.glGenLists(1);
        if (hoverMarks == 0) return s.marks;
        if (s != cachedHatchSection || s.marksRevision != cachedMarksRevision) {
            cachedHatchFaces = new HatchMesh.Selection();
            emitHatchFaces(s.owner, s, cachedHatchFaces);
            cachedHatchSection = s;
            cachedMarksRevision = s.marksRevision;
            cachedHatchSlot = -1;
        }
        if (highlightedHatchSlot == cachedHatchSlot) return hoverMarks;
        GL11.glNewList(hoverMarks, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        cachedHatchFaces.draw(highlightedHatchSlot, (side, plane, u, v, width, height, role) ->
                markerFace(t, side, plane, u, v, width, height, HATCH_ROLES[role].color),
                (side, plane, u, v, width, height, role) ->
                markerFace(t, side, plane, u, v, width, height, markerTint(HATCH_ROLES[role].color)));
        t.draw();
        GL11.glEndList();
        cachedHatchSlot = highlightedHatchSlot;
        return hoverMarks;
    }

    private void clearHatchSelection() {
        cachedHatchSection = null;
        cachedHatchFaces = null;
        if (hoverMarks != 0) GL11.glDeleteLists(hoverMarks, 1);
        hoverMarks = 0;
    }

    private static int compileTexturedShell(View view, Section s, int previous) {
        int list = previous == 0 ? GL11.glGenLists(1) : previous;
        if (list == 0) return 0;
        IBlockAccess access = textureAccess(view, s);
        boolean roles = (view.layers & PreviewStream.HATCH) != 0;
        ChunkCoordinates controller = view.controller;
        int host = roles && controller != null && (controller.posX & ~15) == s.x
                && (controller.posY & ~15) == s.y && (controller.posZ & ~15) == s.z
                ? (controller.posX & 15) | ((controller.posZ & 15) << 4) | ((controller.posY & 15) << 8) : -1;
        ProjectedBlockFaces.Boundary boundary = (side, slot, neighbor, crossing) -> {
            BlockPreviewTextures.Appearance current = s.appearance(slot);
            if (current.textured()) {
                int x = s.x + (slot & 15), y = s.y + (slot >> 8), z = s.z + (slot >> 4 & 15);
                int nativeSide = BlockPreviewTextures.nativeSide(side);
                net.minecraftforge.common.util.ForgeDirection direction =
                        net.minecraftforge.common.util.ForgeDirection.getOrientation(nativeSide);
                try {
                    return !current.block.shouldSideBeRendered(access, x + direction.offsetX,
                            y + direction.offsetY, z + direction.offsetZ, nativeSide);
                } catch (RuntimeException | LinkageError unsupported) {
                    // Unknown context-sensitive blocks retain the previous conservative mask rule.
                }
            }
            Section adjacent = crossing ? s.near[side] : s;
            if (adjacent == null) return false;
            long bit = 1L << (neighbor & 63);
            if (((adjacent.solid[neighbor >> 6] | adjacent.optional[neighbor >> 6]) & ~adjacent.occupied[neighbor >> 6] & bit) == 0) return false;
            return BlockPreviewTextures.occludes(adjacent.appearance(neighbor), current);
        };
        long[][][] deferred = new long[2][][];
        GL11.glNewList(list, GL11.GL_COMPILE);
        try {
            Tessellator tessellator = Tessellator.instance;
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            tessellator.startDrawingQuads();
            tessellator.setBrightness(0xf000f0);
            ProjectedBlockFaces.emit(s.solid, s.optional, s.occupied, s.hatch, host, roles,
                    slot -> s.appearance(slot).fence, boundary, (side, slot) -> {
                BlockPreviewTextures.Appearance appearance = s.appearance(slot);
                if (side == ProjectedBlockFaces.MODEL) PreviewFence.draw(tessellator, appearance, slot, SHELL_ALPHA, access,
                        s.x + (slot & 15), s.y + (slot >> 8), s.z + (slot >> 4 & 15), 0);
                else if (!appearance.textured()) deferred[0] = ProjectedBlockFaces.remember(deferred[0], side, slot);
                else if (appearance.translucent) deferred[1] = ProjectedBlockFaces.remember(deferred[1], side, slot);
                else BlockPreviewTextures.face(tessellator, appearance, side, slot, SHELL_ALPHA, access,
                            s.x + (slot & 15), s.y + (slot >> 8), s.z + (slot >> 4 & 15));
            });
            tessellator.draw();
            // An unsupported model retains its semantic color without sampling a misleading atlas placeholder.
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            if (deferred[0] != null) {
                tessellator.startDrawingQuads();
                ProjectedBlockFaces.emit(deferred[0], (side, slot) -> {
                    int color = s.color(slot);
                    color = color == 0 ? COLORS[side] : shade(color, side == 3 ? 100 : side < 2 ? 82 : 69);
                    tessellator.setColorRGBA_I(color, SHELL_ALPHA);
                    int axis = side / 2, dir = (side & 1) * 2 - 1;
                    int x = slot & 15, y = slot >> 8, z = slot >> 4 & 15;
                    int plane = (axis == 0 ? x : axis == 1 ? y : z) + (side & 1);
                    int u = axis == 0 ? y : axis == 1 ? z : x;
                    int v = axis == 0 ? z : axis == 1 ? x : y;
                    vertex(tessellator, axis, plane, u, v);
                    vertex(tessellator, axis, plane, dir > 0 ? u + 1 : u, dir > 0 ? v : v + 1);
                    vertex(tessellator, axis, plane, u + 1, v + 1);
                    vertex(tessellator, axis, plane, dir > 0 ? u : u + 1, dir > 0 ? v + 1 : v);
                });
                tessellator.draw();
            }
        } finally {
            GL11.glEndList();
        }
        if (deferred[1] != null) {
            int glass = s.glassFaces == 0 ? GL11.glGenLists(1) : s.glassFaces;
            if (glass != 0) {
                GL11.glNewList(glass, GL11.GL_COMPILE);
                try {
                    Tessellator tessellator = Tessellator.instance;
                    tessellator.startDrawingQuads();
                    tessellator.setBrightness(0xf000f0);
                    ProjectedBlockFaces.emit(deferred[1], (side, slot) -> {
                        BlockPreviewTextures.Appearance appearance = s.appearance(slot);
                        if (appearance.textured() && appearance.translucent)
                            BlockPreviewTextures.face(tessellator, appearance, side, slot, SHELL_ALPHA, access,
                                    s.x + (slot & 15), s.y + (slot >> 8), s.z + (slot >> 4 & 15));
                    });
                    tessellator.draw();
                } finally { GL11.glEndList(); }
                s.glassFaces = glass;
            }
        } else if (s.glassFaces != 0) {
            GL11.glDeleteLists(s.glassFaces, 1);
            s.glassFaces = 0;
        }
        return list;
    }

    private static IBlockAccess textureAccess(View view, Section section) {
        World world = Minecraft.getMinecraft().theWorld;
        Section[] halo = new Section[27];
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++)
            halo[13 + dx + 3 * dy + 9 * dz] = view.sections.get(
                    new ChunkPosition((section.x >> 4) + dx, (section.y >> 4) + dy, (section.z >> 4) + dz));
        return PreviewBlockAccess.snapshot(section.x, section.y, section.z, (x, y, z) -> {
            int dx = (x >> 4) - (section.x >> 4), dy = (y >> 4) - (section.y >> 4), dz = (z >> 4) - (section.z >> 4);
            if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) return 0;
            Section source = halo[13 + dx + 3 * dy + 9 * dz];
            if (source == null) return 0;
            int slot = (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
            if ((source.occupied[slot >> 6] & (1L << (slot & 63))) != 0 && world != null
                    && world.provider.dimensionId == view.dim) {
                try {
                    int[] at = source.site.worldCell(x, y, z);
                    if (world.blockExists(at[0], at[1], at[2])) {
                        Block installed = world.getBlock(at[0], at[1], at[2]);
                        if (installed != Blocks.air)
                            return PreviewBlockAccess.state(installed, world.getBlockMetadata(at[0], at[1], at[2]));
                    }
                } catch (RuntimeException | LinkageError unavailable) { /* Treat the unknown neighbour as air. */ }
                return 0;
            }
            if (source.renderSlots == null) return 0;
            int index = source.renderSlots[slot] & 65535;
            if (index == 0 || index > source.renders.size()) return 0;
            // Confirmed material remains part of the projection, without constructing a real tile.
            BlockPreviewTextures.Appearance appearance = source.appearance(slot);
            if (appearance.textured()) return PreviewBlockAccess.state(appearance.block, appearance.meta);
            PreviewStream.RenderMaterial material = source.renders.get(index - 1);
            return PreviewBlockAccess.state(material.block, material.meta);
        });
    }

    private static int compileShell(View view, Section s, int previous) {
        int list = previous == 0 ? GL11.glGenLists(1) : previous;
        if (list == 0) return 0;
        GL11.glNewList(list, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        long[] cells = new long[256];
        boolean roleLayer = (view.layers & PreviewStream.HATCH) != 0;
        int host = -1;
        ChunkCoordinates c = view.controller;
        if (roleLayer && c != null && (c.posX & ~15) == s.x && (c.posY & ~15) == s.y && (c.posZ & ~15) == s.z)
            host = (c.posX & 15) | ((c.posZ & 15) << 4) | ((c.posY & 15) << 8);
        for (int side = 0; side < 6; side++) {
            int axis = side / 2, dir = (side & 1) * 2 - 1;
            for (int plane = 0; plane < 16; plane++) {
                Arrays.fill(cells, 0);
                for (int v = 0; v < 16; v++)
                    for (int u = 0; u < 16; u++) {
                        int i = slot(axis, plane, u, v);
                        // Occupied world cells never contribute projected faces.
                        long bit = 1L << (i & 63);
                        if ((s.occupied[i >> 6] & bit) != 0 || ((s.solid[i >> 6] | s.optional[i >> 6]) & bit) == 0)
                            continue;
                        // The role mesh supplies this face. Keeping it in both layers would blend it twice.
                        if (roleLayer && (i == host || (s.hatch[i >> 6] & (1L << (i & 63))) != 0)) continue;
                        int n = plane + dir;
                        Section next = n < 0 || n > 15 ? s.near[side] : s;
                        int neighbor = slot(axis, n & 15, u, v);
                        // Render the editable region's exterior, including deliberately empty choices.
                        // Faces between projected options share a plane and need no interior fill.
                        if (next != null && (next.occupied[neighbor >> 6] & (1L << (neighbor & 63))) == 0
                                && (next.choiceMaterial(neighbor) || solid(next, neighbor) && !s.choiceMaterial(i))) continue;
                        int color = s.color(i);
                        cells[v * 16 + u] = color == 0 ? -1 : color;
                    }
                for (int v = 0; v < 16; v++)
                    for (int u = 0; u < 16; u++) {
                        long key = cells[v * 16 + u];
                        if (key == 0) continue;
                        int w = 1, h = 1;
                        while (u + w < 16 && cells[v * 16 + u + w] == key) w++;
                        outer: while (v + h < 16) {
                            for (int k = 0; k < w; k++)
                                if (cells[(v + h) * 16 + u + k] != key) break outer;
                            h++;
                        }
                        for (int k = 0; k < h; k++) Arrays.fill(cells, (v + k) * 16 + u, (v + k) * 16 + u + w, 0);
                        int color = (int) key;
                        if (color == -1) color = COLORS[side];
                        else color = shade(color, axis == 1 && dir > 0 ? 100 : axis == 0 ? 82 : 69);
                        t.setColorRGBA_I(color, SHELL_ALPHA);
                        int p = plane + (side & 1);
                        vertex(t, axis, p, u, v);
                        vertex(t, axis, p, dir > 0 ? u + w : u, dir > 0 ? v : v + h);
                        vertex(t, axis, p, u + w, v + h);
                        vertex(t, axis, p, dir > 0 ? u : u + w, dir > 0 ? v + h : v);
                    }
            }
        }
        t.draw();
        GL11.glEndList();
        return list;
    }

    private static int shade(int color, int light) {
        return ((color >> 16 & 255) * light / 100 << 16)
                | ((color >> 8 & 255) * light / 100 << 8) | (color & 255) * light / 100;
    }

    private static int materialColor(PreviewStream.Style style) {
        if (style.conflict) return 0xff756d;
        if (style.air) return 0xc3d4dc;
        return MATERIAL_COLORS[Math.floorMod(style.option, MATERIAL_COLORS.length)];
    }

    private static String materialName(PreviewStream.Style style) {
        Item item = Item.getItemById(style.item);
        return item == null ? "?" : new ItemStack(item, 1, style.damage).getDisplayName();
    }

    private static int compileHatchOutline(View view, Section s) {
        int list = s.outline == 0 ? GL11.glGenLists(1) : s.outline;
        if (list == 0) return 0;
        // Resolve the halo once. An edge can touch a diagonal section even if both face neighbours are empty.
        HatchMesh.Roles[] halo = new HatchMesh.Roles[27];
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            if (dx != 0 && dy != 0 && dz != 0) continue;
            Section neighbor = view.sections.get(
                    new ChunkPosition((s.x >> 4) + dx, (s.y >> 4) + dy, (s.z >> 4) + dz));
            if (neighbor != null && neighbor.hasHatches)
                halo[13 + dx + 3 * dy + 9 * dz] = new HatchMesh.Roles(
                        neighbor.hatch, neighbor.occupied, neighbor.markerRoles(view));
        }
        GL11.glNewList(list, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        ChoiceOutline.emit(s.hatch, s.occupied, (x, y, z) -> {
            HatchMesh.Roles neighbor = halo[13 + (x >> 4) + 3 * (y >> 4) + 9 * (z >> 4)];
            return neighbor == null ? 0 : neighbor.type((x & 15) | ((z & 15) << 4) | ((y & 15) << 8));
        }, (axis, x, y, z, length) -> {
            t.addVertex(x, y, z);
            t.addVertex(x + (axis == 0 ? length : 0), y + (axis == 1 ? length : 0), z + (axis == 2 ? length : 0));
        });
        t.draw();
        GL11.glEndList();
        return list;
    }

    private static void drawPick(ChunkCoordinates at, int color) {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(0f, -3f);
        GL11.glPushMatrix();
        GL11.glTranslated(at.posX, at.posY, at.posZ);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (int side = 0; side < 6; side++)
            markerFace(t, side, side & 1, 0, 0, 1, 1, color == 0 ? COLORS[side]
                    : shade(color, side < 2 ? 82 : side == 3 ? 100 : 69));
        t.draw();
        GL11.glPopMatrix();
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
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
