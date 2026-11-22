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
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
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
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.EXTBlendColor;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;
import java.util.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class BuilderRenderer extends BuilderProxy {
    private static BuilderRenderer instance;
    private static final long[] EMPTY_MASK = new long[64];
    private final PreviewInbox packets = new PreviewInbox();
    private final Map<UUID, View> views = new LinkedHashMap<>();
    private final Deque<Iterator<Section>> retiredMoveSections = new ArrayDeque<>();
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
    private final PositionedLists sectionLists = new PositionedLists();
    private final WorldPreviewOcclusion occlusion = new WorldPreviewOcclusion();
    private WorldPreviewGlass glassPass = new WorldPreviewGlass();
    private final List<Section> visibleSections = new ArrayList<>();
    private final List<Section> roleTextures = new ArrayList<>();
    private final List<Section> texturedSections = new ArrayList<>(), colorSections = new ArrayList<>();
    private final List<View> visiblePreviews = new ArrayList<>();
    private final List<View> visibleBounds = new ArrayList<>();
    private final List<ChunkCoordinates> visibleIssues = new ArrayList<>();
    private final int[] boundsStart = new int[3], boundsEnd = new int[3];
    private double cameraX, cameraY, cameraZ;
    private World observedWorld;
    private ProjectionOccupancy occupancy;
    private final PreviewFooting footing = new PreviewFooting();
    private boolean groundContactHud;
    private final long[] sampledOccupancy = new long[64];
    private long samplingBudget;
    private int sampledSites;
    private long sampleSequence;
    private Section samplingTurn, nextSamplingTurn;
    private boolean compiledThisFrame;
    private boolean sampledReleasedSite;
    private final List<View> visibleControllers = new ArrayList<>();
    private long compilations;
    private String aimedMarker;
    private int aimedColor;
    private Pick aimed;
    private ChunkCoordinates highlighted;
    private int highlightColor;
    private View highlightedController;
    private Section highlightedHatch;
    private int highlightedHatchSlot;
    private final int[] highlightOffset = new int[3], highlightLocal = new int[3];
    private String aimedHint, aimedAction;
    private boolean aimedHatch;
    private final PreviewTooltip tooltip = new PreviewTooltip();
    private static final int NAME_RETRY_TICKS = 40;
    private Section nameRequestSection;
    private int nameRequestSlot, nameRequestEpoch, nameRequestRevision, nameRequestTick, hudTick;
    private final java.util.concurrent.atomic.AtomicReference<PreviewStream.Packet> nameReply =
            new java.util.concurrent.atomic.AtomicReference<>();
    private volatile PreviewGrip grip;
    private long nextGrip;
    private volatile boolean textureReload;
    private static final long GRIP_HOLD_NANOS = 300_000_000L;
    private static final int SNEAK_GESTURE = 1, LEFT_CTRL_GESTURE = 2, RIGHT_CTRL_GESTURE = 4;
    private final List<DragInput> dragInputs = new ArrayList<>(4);
    private boolean dragUseHeld, freshDragPress, longReleasedPress, pitchGesture;
    private long dragPressTime;
    private int heldDragModifiers, modifiersAtPress, axisKey;

    private static final class DragInput {
        final long time;
        final int key, wheel;
        final boolean pressed;

        DragInput(long time, int key, boolean pressed, int wheel) {
            this.time = time;
            this.key = key;
            this.pressed = pressed;
            this.wheel = wheel;
        }
    }

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
        return bindingDown(minecraft.gameSettings.keyBindSneak);
    }

    private static boolean bindingDown(KeyBinding binding) {
        if (binding == null || binding.getKeyCode() == 0) return false;
        return binding.getIsKeyPressed() || physicalKeyDown(binding.getKeyCode());
    }

    private static boolean physicalKeyDown(int code) {
        if (code < 0) {
            int button = code + 100;
            return button >= 0 && button < Mouse.getButtonCount() && Mouse.isButtonDown(button);
        }
        return code > 0 && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }

    @Override
    public boolean editPreview(EntityPlayer player) {
        if (!editContext(player)) return false;
        applyDragInputs();
        boolean pressed = freshDragPress;
        boolean heldLong = longReleasedPress;
        freshDragPress = longReleasedPress = false;
        if (grip != null) return true;
        boolean sneak = pressed ? (modifiersAtPress & SNEAK_GESTURE) != 0 : previewModifierDown();
        boolean ctrl = pressed ? (modifiersAtPress & (LEFT_CTRL_GESTURE | RIGHT_CTRL_GESTURE)) != 0
                : GuiScreen.isCtrlKeyDown();
        WorldHit hit = worldHit(player);
        if (hit == null) return pressed && !sneak && beginGrip(remoteGripHit(player), player, false, heldLong);
        if (!hit.owned || hit.view.fixedPose != null) return true;
        if (sneak && !ctrl) {
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
        if (sneak) return true;
        if (hit.marker()) {
            if (!beginGrip(hit, player, true, heldLong)) PreviewEdit.TaskAction.open(hit.view.id, hit.view.revision, hit.at);
        } else PreviewEdit.TaskAction.openTask(hit.view.id, hit.at);
        return true;
    }

    private static boolean canGrip(WorldHit hit, EntityPlayer player) {
        return hit != null && hit.owned && hit.host && hit.view.fixedPose == null
                && hit.view.pose >= 0 && hit.view.allowed != null && player.worldObj.isAirBlock(hit.at.posX, hit.at.posY, hit.at.posZ);
    }

    private boolean beginGrip(WorldHit hit, EntityPlayer player, boolean near, boolean heldLong) {
        if (!canGrip(hit, player)) return false;
        PreviewGrip g = new PreviewGrip(hit.view.id, ++nextGrip, hit.view.revision, hit.view.tool, hit.at,
                ExtendedFacing.byIndex(hit.view.pose), hit.view.allowed, hit.view.dragMinDistance,
                hit.view.dragMaxDistance, near, player, System.nanoTime());
        g.moving = heldLong;
        grip = g;
        PreviewDragMessage.send(PreviewDragMessage.PRESS, g.id, g.sequence, g.revision, null, null);
        return true;
    }

    private static boolean useDown() {
        return bindingDown(Minecraft.getMinecraft().gameSettings.keyBindUseItem);
    }

    private static int leftControl() {
        return Minecraft.isRunningOnMac ? Keyboard.KEY_LMETA : Keyboard.KEY_LCONTROL;
    }

    private static int rightControl() {
        return Minecraft.isRunningOnMac ? Keyboard.KEY_RMETA : Keyboard.KEY_RCONTROL;
    }

    private static int dragModifier(int code) {
        int modifiers = 0;
        if (code == Minecraft.getMinecraft().gameSettings.keyBindSneak.getKeyCode()) modifiers |= SNEAK_GESTURE;
        if (code == leftControl()) modifiers |= LEFT_CTRL_GESTURE;
        if (code == rightControl()) modifiers |= RIGHT_CTRL_GESTURE;
        return modifiers;
    }

    private void queueDragKey(int code, boolean pressed, long time) {
        if (code != 0 && (code == Minecraft.getMinecraft().gameSettings.keyBindUseItem.getKeyCode()
                || dragModifier(code) != 0)) dragInputs.add(new DragInput(time, code, pressed, 0));
    }

    private void applyDragInputs() {
        if (dragInputs.isEmpty()) return;
        // Minecraft drains mouse input before keyboard input, including when the key arrived first.
        dragInputs.sort(Comparator.comparingLong(input -> input.time));
        int use = Minecraft.getMinecraft().gameSettings.keyBindUseItem.getKeyCode();
        PreviewGrip g = grip;
        for (DragInput input : dragInputs) {
            if (input.wheel != 0) {
                if (dragUseHeld && g != null && g.moving && !g.released && !g.rejected && !g.inputReleased) {
                    if (axisKey == 0) g.scrollDistance(input.wheel);
                    else g.scrollRotation(pitchGesture, input.wheel);
                }
            } else if (input.key == use) {
                if (input.pressed && !dragUseHeld) {
                    freshDragPress = true;
                    longReleasedPress = false;
                    dragPressTime = input.time;
                    modifiersAtPress = heldDragModifiers;
                }
                if (!input.pressed) {
                    axisKey = 0;
                    boolean heldLong = dragUseHeld && input.time - dragPressTime >= GRIP_HOLD_NANOS;
                    if (freshDragPress) longReleasedPress = heldLong;
                    if (g != null && !g.released && !g.inputReleased) {
                        g.inputReleased = true;
                        // A release between client ticks can cross the hold threshold.
                        g.moving |= heldLong;
                    }
                }
                dragUseHeld = input.pressed;
            } else {
                int modifier = dragModifier(input.key);
                if (input.pressed) {
                    if (modifier != 0 && dragUseHeld && axisKey == 0 && (heldDragModifiers & modifier) == 0) {
                        axisKey = input.key;
                        pitchGesture = (modifier & SNEAK_GESTURE) == 0;
                    }
                    heldDragModifiers |= modifier;
                } else {
                    heldDragModifiers &= ~modifier;
                    if (input.key == axisKey) {
                        axisKey = 0;
                        if (g != null) g.limited = false;
                    }
                }
            }
        }
        dragInputs.clear();
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
        if (!Keyboard.isRepeatEvent())
            queueDragKey(Keyboard.getEventKey(), Keyboard.getEventKeyState(), Keyboard.getEventNanoseconds());
        if (Keyboard.getEventKeyState() && deleteShortcutKey(Keyboard.getEventKey())) deleteInWorld();
    }

    @SubscribeEvent
    public void worldMouseButton(InputEvent.MouseInputEvent e) {
        int button = Mouse.getEventButton();
        if (button >= 0) queueDragKey(button - 100, Mouse.getEventButtonState(), Mouse.getEventNanoseconds());
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
        PreviewGrip g = grip;
        if (g != null && !g.released) {
            if (g.moving && !g.rejected)
                dragInputs.add(new DragInput(Mouse.getEventNanoseconds(), 0, false, event.dwheel > 0 ? notches : -notches));
            event.setCanceled(true);
            return;
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
        applyDragInputs();
        freshDragPress = longReleasedPress = false;
        heldDragModifiers = previewModifierDown() ? SNEAK_GESTURE : 0;
        if (physicalKeyDown(leftControl())) heldDragModifiers |= LEFT_CTRL_GESTURE;
        if (physicalKeyDown(rightControl())) heldDragModifiers |= RIGHT_CTRL_GESTURE;
        boolean holdingUse = useDown();
        if (!holdingUse) {
            dragUseHeld = false;
            axisKey = 0;
        } else if (axisKey != 0 && !physicalKeyDown(axisKey)) axisKey = 0;
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
        holdingUse &= !g.inputReleased;
        if (holdingUse && !g.rejected && now - g.pressedAt >= GRIP_HOLD_NANOS) g.moving = true;
        if (g.moving && !g.rejected) g.track(player);
        if (!holdingUse) {
            int op = g.rejected ? PreviewDragMessage.CANCEL : g.moving ? PreviewDragMessage.DROP
                    : g.near ? PreviewDragMessage.TAP : PreviewDragMessage.CANCEL;
            PreviewDragMessage.send(op,g.id,g.sequence,g.revision,g.at,g.facing);
            g.released=true; g.releasedAt=now;
            if (op!=PreviewDragMessage.DROP || !g.changed() && player.worldObj.isAirBlock(g.at.posX,g.at.posY,g.at.posZ)) grip=null;
        }
    }

    @SubscribeEvent
    public void previewInputUnload(WorldEvent.Unload event) {
        if (event.world.isRemote) {
            grip = null;
            dragInputs.clear();
            freshDragPress = longReleasedPress = dragUseHeld = false;
            heldDragModifiers = axisKey = 0;
            editQueue.clear();
        }
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
        if (hit == null || !hit.owned || hit.view.fixedPose != null || !hit.view.editable
                || (hit.view.layers & PreviewStream.SHELL) == 0 || hit.section == null || !hit.section.choiceMaterial(hit.slot)) return null;
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
        Map<ChunkPosition, Section> sections = new LinkedHashMap<>();
        final Set<Section> textureSwitch = new LinkedHashSet<>();
        Bounds bounds;
        int[][] footing = new int[6][];
        final List<int[]> footingPages = new ArrayList<>();
        int receivingFace = -1, footingInts;
        net.minecraft.util.ChunkCoordinates controller, issue;
        ChunkCoordinates indexedController;
        ChunkPosition controllerSection;
        AxisAlignedBB box;
        int dim, layers, pose=-1;
        int dragMinDistance = 1, dragMaxDistance = 64;
        PreviewPose.Allowed allowed;
        boolean repairable;
        int controllerMeta, tier, ambiguousDots;
        String[] dotHints;
        final Map<List<String>, HatchInfo[]> labels = new HashMap<>();
        int epoch, revision;
        boolean editable, textured, locateHatches;
        final Map<Integer, Assembly> fragments = new HashMap<>();
        long lastCompilation, lastSample;
        View previous;
        Section partialFaces;
        PreviewGrip fixedPose;
        PreviewGrip acceptedFootingPose;
        boolean moveStreamReady, waitingForSite;

        View(UUID id) {
            this.id = id;
            idText = id.toString();
        }

        boolean texturedMarkers() {
            return textured && (layers & PreviewStream.SHELL) != 0;
        }

        boolean controllerOutlineOnly() {
            if ((layers & PreviewStream.SHELL) == 0 || controller == null) return false;
            if (controller != indexedController) {
                indexedController = controller;
                controllerSection = new ChunkPosition(controller.posX >> 4, controller.posY >> 4, controller.posZ >> 4);
            }
            Section s = sections.get(controllerSection);
            return s == null ? textured : s.outlineOnly();
        }

        void resetFooting() {
            footing = new int[6][];
            footingPages.clear();
            receivingFace = -1;
            footingInts = 0;
        }

        void receiveFooting(PreviewStream.Packet page) {
            if (page.footingFirst) {
                footingPages.clear();
                footingInts = 0;
                receivingFace = page.footingFace;
            }
            if (receivingFace != page.footingFace) return;
            footingPages.add(page.footingRows);
            footingInts += page.footingRows.length;
            if (!page.footingLast) return;
            int[] rows = page.footingRows;
            if (footingPages.size() > 1) {
                rows = new int[footingInts];
                int at = 0;
                for (int[] part : footingPages) {
                    System.arraycopy(part, 0, rows, at, part.length);
                    at += part.length;
                }
            }
            footing[receivingFace] = rows;
            footingPages.clear();
            receivingFace = -1;
        }

        void invalidateLabels() {
            dotHints = null;
            labels.clear();
            for (Section s : sections.values()) {
                s.labels = null;
                s.markerRoles = null;
                s.roleIndex = null;
                if (s.hasHatches) {
                    s.marksDirty = true;
                    if (s.textured) s.markFacesDirty();
                }
            }
        }

    }

    private static final class Assembly {
        final byte[][] parts;
        final int bytes;
        int received;
        Assembly(PreviewStream.Packet p) {
            bytes = p.totalBytes;
            parts = new byte[p.parts][];
        }
    }

    private boolean unload;
    private static final int HOST_COLOR = 0xba72fa;
    private static final int BOUNDS_COLOR = 0xffb52e;
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
        HatchMesh.Roles roleIndex;
        final long[] optional;
        final short[] styleSlots;
        final List<PreviewStream.Style> styles;
        final int[] colors;
        final int epoch, revision;
        boolean textured;
        short[] renderSlots;
        Map<Integer, PreviewStream.Packet> names;
        List<PreviewStream.RenderMaterial> renders;
        BlockPreviewTextures.Appearance[] appearances;
        long[] confirmed, occupied = EMPTY_MASK;
        final ProjectionSite site = new ProjectionSite();
        final Section[] near = new Section[6];
        final AxisAlignedBB box;
        int faces, marks;
        int faceParts, visibleParts = 1;
        long visiblePartMask = 1;
        WorldPreviewGlass.Mesh glass;
        TexturedBuild texturedBuild, publishedTexture;
        int visibleWords = 64;
        boolean facesTextured;
        boolean dirty = true, marksDirty = true, hasHatches, hasShell;
        boolean replaceFaces, replaceMarks;
        int probe;
        WorldPreviewOcclusion.History probeHistory;
        long lastCompilation, lastSample;

        boolean containsController() {
            ChunkCoordinates c = owner.controller;
            return c != null && (c.posX & ~15) == x && (c.posY & ~15) == y && (c.posZ & ~15) == z;
        }

        boolean needsMesh(int layers) {
            return (layers & PreviewStream.SHELL) != 0 && hasShell && dirty
                    || (layers & PreviewStream.HATCH) != 0 && hasHatches && needsMarkFaces();
        }

        boolean needsMarkFaces() { return marksDirty && !owner.texturedMarkers(); }

        boolean outlineOnly() {
            // Follow the mesh on screen until the replacement for this section is ready.
            return (owner.layers & PreviewStream.SHELL) != 0 && (faces != 0 ? facesTextured : owner.textured);
        }

        void markFacesDirty() {
            dirty = true;
            publishedTexture = null;
            // Keep the already visible prefix until a replacement can take over its GL lists.
            if (faces == 0 && drawableFaces() != 0) {
                TexturedBuild partial = texturedBuild;
                texturedBuild = null;
                faces = partial.head;
                faceParts = partial.parts - 1;
                visibleParts = partial.part;
                visiblePartMask = partial.shellParts;
                visibleWords = partial.ends[partial.part - 1];
                glass = partial.glass;
                facesTextured = true;
            }
            cancelTexturedBuild();
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
            return paletteAppearance(index - 1);
        }

        BlockPreviewTextures.Appearance paletteAppearance(int index) {
            if (appearances == null) appearances = new BlockPreviewTextures.Appearance[renders.size()];
            BlockPreviewTextures.Appearance appearance = appearances[index];
            if (appearance == null) {
                PreviewStream.RenderMaterial material = renders.get(index);
                appearance = BlockPreviewTextures.get(material.block, material.meta, material.machine, material.front);
                appearances[index] = appearance;
            }
            return appearance;
        }

        void dropTextures(boolean keepVisibleMesh) {
            cancelTexturedBuild();
            textured = false;
            renderSlots = null;
            renders = Collections.emptyList();
            appearances = null;
            if (!keepVisibleMesh && facesTextured) clearFaces();
        }

        void clearFaces() {
            cancelTexturedBuild();
            publishedTexture = null;
            visibleWords = 64;
            clearGlass();
            if (faces != 0) GL11.glDeleteLists(faces, faceParts + 1);
            faces = 0;
            faceParts = 0;
            visibleParts = 1;
            visiblePartMask = 1;
        }

        void takeFaceLists(Section old) {
            if (old.faces == 0 && old.drawableFaces() != 0) old.markFacesDirty();
            visibleWords = old.visibleWords;
            glass = old.glass;
            old.glass = null;
            faces = old.faces;
            facesTextured = old.facesTextured;
            faceParts = old.faceParts;
            visibleParts = old.visibleParts;
            visiblePartMask = old.visiblePartMask;
            old.faces = old.faceParts = 0;
        }

        void releaseFaceParts() {
            clearGlass();
            if (faceParts != 0) GL11.glDeleteLists(faces + 1, faceParts);
            faceParts = 0;
            visibleParts = 1;
            visiblePartMask = 1;
        }

        private void clearGlass() {
            if (glass != null) glass.release();
            glass = null;
        }

        WorldPreviewGlass.Mesh drawableGlass() {
            if (faces != 0) return glass;
            return drawableFaces() == 0 ? null : texturedBuild.glass;
        }

        int drawableFaces() {
            if (faces != 0) return faces;
            return owner.previous == null && texturedBuild != null && texturedBuild.part > 0 ? texturedBuild.head : 0;
        }

        boolean drawsShellSlot(int slot) {
            if (faces != 0) return (slot >>> 6) < visibleWords;
            return drawableFaces() != 0 && (slot >> 6) < texturedBuild.ends[texturedBuild.part - 1];
        }

        void cancelTexturedBuild() {
            if (texturedBuild != null) {
                texturedBuild.release();
                texturedBuild = null;
            }
            if (owner.partialFaces == this) owner.partialFaces = null;
        }

        int color(int slot) {
            if (styleSlots == null) return 0;
            int index = styleSlots[slot] & 65535;
            return index == 0 ? 0 : colors[index - 1];
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
            boolean shellChanged = false, hatchChanged = false;
            int borders = 0;
            for (int i = 0; i < hatch.length; i++) {
                long shell = solid[i];
                long changed = occupied[i] ^ mask[i];
                hasShell |= (shell & ~mask[i]) != 0;
                hasHatches |= (hatch[i] & ~mask[i]) != 0;
                shellChanged |= (changed & shell) != 0;
                if ((changed & hatch[i]) != 0) {
                    hatchChanged = true;
                    borders |= borderSides(i, changed & hatch[i]);
                }
            }
            // Real blocks already occlude coplanar preview faces via polygon offset. Keep the
            // remaining projection drawable until this section receives its next compile slot.
            if (!hasShell) clearFaces();
            if (!hasHatches) clearHatches();
            if (shellChanged && !owner.textured) markFacesDirty();
            else if (shellChanged && faces == 0) dirty = true;
            if (hatchChanged) marksDirty = true;
            return borders;
        }

        void clear() {
            site.close();
            clearFaces();
            clearHatches();
        }

        void clearHatches() {
            if (marks != 0) GL11.glDeleteLists(marks, 1);
            marks = 0;
            marksDirty = true;
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
            section.cancelTexturedBuild();
            if (section.facesTextured && section.faces != 0) {
                // Old atlas UVs cannot be retained across a resource-pack rebuild.
                section.clearFaces();
                section.markFacesDirty();
            }
        }
    }

    @Override
    public void preview(PreviewStream.Packet p) {
        if (p.type == PreviewStream.BLOCK_NAME) nameReply.set(p);
        else packets.add(p);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload e) {
        if (e.world.isRemote) {
            packets.clear();
            unload = true;
        }
    }

    @SubscribeEvent
    public void footingChunkChanged(ChunkEvent e) {
        if (e.world == observedWorld && occupancy != null) {
            occupancy.chunkChanged(e.getChunk().xPosition, e.getChunk().zPosition);
            footing.chunkChanged(e.getChunk().xPosition, e.getChunk().zPosition, e instanceof ChunkEvent.Unload);
        }
    }

    private void remove(UUID id) {
        View view = views.remove(id);
        if (view != null) {
            retireMoveSource(view);
            retireSections(view);
        }
        if (views.isEmpty()) observe(null);
        visibleHatches.clear();
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
        replacement.acceptedFootingPose = released;
        replacement.previous = old;
        views.put(p.id, replacement);
        grip = null;
    }

    private void retireMoveSource(View destination) {
        destination.moveStreamReady = false;
        View source = destination.previous;
        destination.previous = null;
        while (source != null) {
            retireSections(source);
            View previous = source.previous;
            source.previous = null;
            source = previous;
        }
    }

    private void retireSections(View view) {
        view.textureSwitch.clear();
        if (samplingTurn != null && samplingTurn.owner == view) samplingTurn = null;
        if (nextSamplingTurn != null && nextSamplingTurn.owner == view) nextSamplingTurn = null;
        if (!view.sections.isEmpty()) {
            retiredMoveSections.addLast(view.sections.values().iterator());
            view.sections = new LinkedHashMap<>();
        }
        view.fragments.clear();
    }

    private void drainRetiredMoveSections() {
        if (retiredMoveSections.isEmpty()) return;
        long deadline = System.nanoTime() + 500_000L;
        for (int i = 0; i < 8 && !retiredMoveSections.isEmpty(); i++) {
            Iterator<Section> sections = retiredMoveSections.peekFirst();
            sections.next().clear();
            sections.remove();
            if (!sections.hasNext()) retiredMoveSections.removeFirst();
            if (System.nanoTime() >= deadline) break;
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        hudTick++;
        BrowserRenderer.cleanupClosed();
        drainRetiredMoveSections();
        reloadPreviewTextures();
        if (unload) {
            occlusion.release();
            glassPass = new WorldPreviewGlass();
            observe(null);
            BlockPreviewTextures.clear();
            PreviewConnectedTextures.clear();
            visibleHatches.clear();
            for (View view : views.values()) {
                retireMoveSource(view);
                retireSections(view);
            }
            views.clear();
            nameRequestSection = null;
            nameReply.set(null);
            unload = false;
        }
        receivePackets(8);
        acceptNameReply();
        // Keep highlighting the visible material between the revision header and its section payload.
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        WorldHit hit = grip == null || grip.rejected ? worldHit(player, true) : null;
        if ((hit == null || hit.section != nameRequestSection) && hudTick - nameRequestTick >= NAME_RETRY_TICKS)
            nameRequestSection = null;
        aimed = choice(hit, false);
        updateAimedMarker(hit);
        applyHighlight(player, hit);
    }

    private void receivePackets(int limit) {
        if (!packets.hasPackets()) return;
        long deadline = System.nanoTime() + 450_000L;
        for (int n = 0; n < limit && (n == 0 || System.nanoTime() < deadline); n++) {
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
                    if (at != null) at.markFacesDirty();
                }
                v.controller = controllerOn ? new ChunkCoordinates(p.x, p.y, p.z) : null;
                v.issue = p.issue;
                v.pose=p.pose;
                v.allowed=p.allowed;
                v.dragMinDistance = p.dragMinDistance;
                v.dragMaxDistance = p.dragMaxDistance;
                v.repairable = (p.layers & 4) != 0;
                continue;
            }
            if (p.type == 1) {
                View view = views.computeIfAbsent(p.id, View::new);
                if (p.epoch < view.epoch) continue;
                if (view.epoch != p.epoch || view.revision != p.revision || (p.layers & PreviewStream.BOX) == 0)
                    view.acceptedFootingPose = null;
                if (view.previous != null && (p.epoch != view.epoch || p.revision != view.revision || p.layers != view.previous.layers
                        || p.textured != view.previous.textured)) retireMoveSource(view);
                if (p.reset) retireSections(view);
                if (view.epoch != p.epoch) view.fragments.clear();
                view.epoch = p.epoch;
                view.revision = p.revision;
                view.editable = p.editable;
                view.locateHatches = p.locateHatches;
                if (view.textured != p.textured) {
                    view.textureSwitch.clear();
                    footing.refresh(view.footing);
                    for (Section s : view.sections.values()) {
                        s.site.dirty = true;
                        // A color mesh is already the correct placeholder until the palette for
                        // this section arrives. Avoid compiling that same placeholder again.
                        if (!p.textured && s.hasShell) s.markFacesDirty();
                        if (!p.textured) s.dropTextures((p.layers & PreviewStream.SHELL) != 0);
                    }
                }
                view.textured = p.textured;
                view.tool = p.tool;
                view.bounds = p.bounds;
                if (p.footingReset) view.resetFooting();
                view.box = AxisAlignedBB.getBoundingBox(p.bounds.minX, p.bounds.minY, p.bounds.minZ,
                        p.bounds.maxX + 1.0, p.bounds.maxY + 1.0, p.bounds.maxZ + 1.0);
                if (((view.layers ^ p.layers) & PreviewStream.HATCH) != 0)
                    for (Section s : view.sections.values()) if (s.hasHatches || s.containsController()) s.markFacesDirty();
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
            if (p.type == PreviewStream.FOOTING_ROWS) {
                if (view.revision == p.revision && (view.layers & PreviewStream.BOX) != 0) view.receiveFooting(p);
                continue;
            }
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
                if (assembly.bytes != p.totalBytes || assembly.parts.length != p.parts || assembly.parts[p.part] != null)
                    continue;
                assembly.parts[p.part] = p.payload;
                if (++assembly.received != assembly.parts.length) continue;
                view.fragments.remove(p.transfer);
                ByteBuf bytes = Unpooled.wrappedBuffer(assembly.parts.length, assembly.parts);
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
                    if (!Arrays.equals(section.confirmed, p.confirmed) && occupancy != null) {
                        // A build-progress packet can arrive independently of vanilla block/chunk data.
                        // Wake all observers of this section, including contact tiles at its base.
                        occupancy.markBlockRangeForRenderUpdate(section.x, section.y, section.z,
                                section.x + 15, section.y + 15, section.z + 15);
                    }
                    section.confirmed = p.confirmed;
                }
                continue;
            }
            if (p.type != 2 && p.type != 7) continue;
            Section s = new Section(view, p);
            Section old = view.sections.put(pos, s);
            invalidateTextureNeighbors(view, p.x, p.y, p.z);
            if (old != null) {
                view.textureSwitch.remove(old);
                s.updateVisibility(old.occupied);
                s.occupied = old.occupied;
                if (s.textured && !old.facesTextured && s.hasShell) view.textureSwitch.add(s);
            }
            boolean sameRoles = old != null && Arrays.equals(old.hatch, s.hatch)
                    && Arrays.equals(old.dots, s.dots) && s.hatchTypes.sameAs(old.hatchTypes);
            invalidateHatchNeighbors(view, s, changedHatchBorders(old, s, sameRoles), false);
            if (old != null) {
                // A replacement projection must not punch a hole while waiting for its paced mesh rebuild.
                if (s.hasShell) {
                    s.takeFaceLists(old);
                }
                if (sameRoles) {
                    s.marks = old.marks;
                    s.marksDirty = old.marksDirty;
                    s.labels = old.labels;
                    s.markerRoles = old.markerRoles;
                    old.marks = 0;
                }
                if (highlightedHatch == old) {
                    int word = highlightedHatchSlot >>> 6;
                    long bit = 1L << (highlightedHatchSlot & 63);
                    if ((view.layers & PreviewStream.HATCH) != 0 && (s.hatch[word] & ~s.occupied[word] & bit) != 0) {
                        highlightedHatch = s;
                    } else {
                        highlightedHatch = null;
                        highlighted = null;
                    }
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
                    if (!near.textured) near.markFacesDirty();
                }
            }
        }
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
            near.marksDirty = true;
            near.replaceMarks |= presenting && near.marks != 0;
        }
    }

    private static void invalidateTextureNeighbors(View view, int x, int y, int z) {
        if (!view.textured) return;
        PreviewRegion changed = new PreviewRegion();
        changed.include(x * 16, y * 16, z * 16);
        changed.include(x * 16 + 15, y * 16 + 15, z * 16 + 15);
        // CTM and native world icons can consult diagonals and the previous/next section layer.
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            Section neighbor = view.sections.get(new ChunkPosition(x + dx, y + dy, z + dz));
            if (neighbor == null || !neighbor.textured || !neighbor.hasShell) continue;
            TexturedBuild mesh = neighbor.texturedBuild != null ? neighbor.texturedBuild : neighbor.publishedTexture;
            if (mesh != null) {
                mesh.invalidate(changed, 0);
                neighbor.dirty |= mesh.repairs != 0;
            }
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
        return worldHit(player, false);
    }

    private WorldHit remoteGripHit(EntityPlayer player) {
        ItemStack held = player.getHeldItem();
        String tool = held.getTagCompound().getString("tool");
        String selected = held.getTagCompound().getString("selected");
        double eyeY = player.posY + player.getEyeHeight() - player.getDefaultEyeHeight();
        double reach = 0;
        List<View> candidates = new ArrayList<>();
        for (View view : views.values()) {
            ChunkCoordinates at = view.controller;
            if (view.dim != player.dimension || !tool.equals(view.tool) || at == null || view.pose < 0
                    || view.allowed == null || view.previous != null || view.fixedPose != null
                    || !player.worldObj.blockExists(at.posX, at.posY, at.posZ)
                    || !player.worldObj.isAirBlock(at.posX, at.posY, at.posZ)) continue;
            double limit = PreviewAim.dragReach(view.dragMaxDistance);
            double x = at.posX + .5 - player.posX, y = at.posY + .5 - eyeY, z = at.posZ + .5 - player.posZ;
            if (x * x + y * y + z * z > limit * limit) continue;
            candidates.add(view);
            reach = Math.max(reach, limit);
        }
        if (candidates.isEmpty()) return null;
        View[] picked = {null};
        ChunkCoordinates at = PreviewAim.first(player, reach, (x, y, z) -> {
            for (View view : candidates) {
                ChunkCoordinates controller = view.controller;
                if (controller.posX == x && controller.posY == y && controller.posZ == z
                        && (picked[0] == null || PreviewAim.prefer(true, view.idText, true, picked[0].idText, selected)))
                    picked[0] = view;
            }
            return picked[0] != null;
        });
        return at == null ? null : new WorldHit(picked[0], null, 0, at.posX, at.posY, at.posZ, true, true, true, false, false);
    }

    private WorldHit worldHit(EntityPlayer player, boolean inspect) {
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
                WorldHit hit = hitAt(v, player.worldObj, x, y, z, owned, inspect && !terminal);
                if (hit != null) result[0] = hit;
            }
            return result[0] != null;
        });
        return result[0];
    }

    private WorldHit hitAt(View view, World world, int worldX, int worldY, int worldZ, boolean owned, boolean inspect) {
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
        boolean drawn = roles && controller || s != null && (!s.site.dirty || pose != null && pose.released)
                && (s.occupied[word] & bit) == 0
                && (roles && hatch ? (s.outlineOnly() ? s.drawsShellSlot(slot) : s.marks != 0)
                        : shells && structure && s.drawsShellSlot(slot));
        // An empty material option has no mesh, but its slot remains editable under the crosshair.
        if (shells && owned && view.editable && s != null && (s.optional[word] & bit) != 0) drawn = true;
        boolean air = world.isAirBlock(worldX, worldY, worldZ);
        if (!issue && !(air ? drawn : owned && (controller || structure || hatch)
                || inspect && (shells && structure || roles && (controller || hatch)))) return null;
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
        aimedAction = null;
        aimedHatch = false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.currentScreen != null || views.isEmpty()) return;
        PreviewGrip g=grip;
        if (g!=null && !g.rejected) {
            aimed=null;
            aimedColor=HOST_COLOR;
            View view = views.get(g.id);
            aimedMarker=roleName("markerHost", view == null ? "" : PreviewBlockName.machine(view.controllerMeta))
                    +"   "+g.at.posX+", "+g.at.posY+", "+g.at.posZ;
            aimedHint=!g.moving ? StatCollector.translateToLocal(
                    g.near ? "structurebuildernh.drag.hold" : "structurebuildernh.drag.remoteHold")
                    : g.limited ? StatCollector.translateToLocal("structurebuildernh.drag.limited")
                    : StatCollector.translateToLocalFormatted("structurebuildernh.drag.controls",
                            g.displayDistance(), PreviewTooltip.modifierName(mc));
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (marker != null && marker.marker() && (marker.owned || held == null || held.getItem() != BuilderItem.ITEM)) {
            View v = marker.view;
            if (marker.host) {
                aimedMarker = roleName("markerHost", hitName(mc, marker));
                aimedColor = HOST_COLOR;
                if (canGrip(marker, mc.thePlayer)) aimedHint=StatCollector.translateToLocal("structurebuildernh.drag.hold");
            } else if (marker.issue) {
                aimedMarker = roleName("markerHatch", hitName(mc, marker));
                aimedColor = HOST_COLOR;
                aimedHatch = true;
            } else {
                Section s = marker.section;
                int i = marker.slot;
                HatchInfo label = s.hatchInfo(v, hatchRank(s, i));
                aimedMarker = roleName("markerHatch", hitName(mc, marker));
                aimedHatch = true;
                aimedColor = label.role.color;
                aimedHint = label.text;
            }
            if (aimedHatch && marker.owned && (v.editable || marker.issue)) {
                aimedAction = StatCollector.translateToLocalFormatted("structurebuildernh.hatchAction", PreviewTooltip.modifierName(mc));
                if (aimed != null) {
                    aimedMarker = roleName("markerHatch", choiceTitle(mc, marker, aimed.style));
                    if (previewModifierDown()) aimedAction = choiceHint(mc, aimed.style);
                }
            }
            return;
        }
        if (aimed != null) {
            aimedMarker = choiceTitle(mc, marker, aimed.style);
            aimedColor = materialColor(aimed.style);
            aimedHint = choiceHint(mc, aimed.style);
            return;
        }
        if (marker != null && (marker.view.layers & PreviewStream.SHELL) != 0
                && (marker.owned || held == null || held.getItem() != BuilderItem.ITEM)) {
            String name = hitName(mc, marker);
            aimedMarker = name.isEmpty() ? StatCollector.translateToLocal("structurebuildernh.browser.block") : name;
            aimedColor = 0xa8c4c8;
        }
    }

    private String choiceTitle(Minecraft mc, WorldHit hit, PreviewStream.Style style) {
        String name = style.air ? StatCollector.translateToLocal("structurebuildernh.previewAir")
                : PreviewBlockName.item(style.item, style.damage);
        if (name.isEmpty()) name = hitName(mc, hit);
        if (name.isEmpty()) name = StatCollector.translateToLocal("structurebuildernh.browser.block");
        if (style.optionCount > 0) name += "  " + (style.option + 1) + "/" + style.optionCount;
        if (style.conflict) name += " · " + StatCollector.translateToLocal("structurebuildernh.previewConflict");
        return name;
    }

    private static String choiceHint(Minecraft mc, PreviewStream.Style style) {
        if (mc.gameSettings.keyBindSneak.getKeyCode() == 0) return null;
        String key = style.groupSelectable && previewModifierDown() ? "previewPickHint" : "previewPickSingleHint";
        return StatCollector.translateToLocalFormatted("structurebuildernh." + key, PreviewTooltip.modifierName(mc));
    }

    private static String roleName(String key, String name) {
        String role = StatCollector.translateToLocal("structurebuildernh." + key);
        return name.isEmpty() || role.equals(name) ? role : role + " · " + name;
    }

    private String hitName(Minecraft mc, WorldHit hit) {
        if (!hit.projected && !mc.theWorld.isAirBlock(hit.at.posX, hit.at.posY, hit.at.posZ))
            return PreviewBlockName.installed(mc.theWorld, mc.thePlayer, hit.at, mc.objectMouseOver);
        if (hit.host) {
            String host = PreviewBlockName.machine(hit.view.controllerMeta);
            if (!host.isEmpty()) return host;
        }
        Section s = hit.section;
        if (s == null) return "";
        PreviewStream.Style style = s.style(hit.slot);
        if (style != null) {
            if (style.air) return StatCollector.translateToLocal("structurebuildernh.previewAir");
            String option = PreviewBlockName.item(style.item, style.damage);
            if (!option.isEmpty()) return option;
        }
        int index = s.renderSlots == null ? 0 : s.renderSlots[hit.slot] & 65535;
        if (index > 0 && index <= s.renders.size()) {
            PreviewStream.RenderMaterial identity = s.renders.get(index - 1);
            String name = PreviewBlockName.planned(identity.block, identity.meta, identity.machine, -1, 0);
            if (!name.isEmpty()) return name;
        }
        PreviewStream.Packet known = s.names == null ? null : s.names.get(hit.slot);
        if (known != null && known.revision == hit.view.revision && known.epoch == hit.view.epoch)
            return PreviewBlockName.planned(known.nameBlock, known.nameMeta, -1, known.nameItem, known.nameDamage);
        if (hit.view.fixedPose == null && (s != nameRequestSection || hit.slot != nameRequestSlot
                || nameRequestEpoch != hit.view.epoch || nameRequestRevision != hit.view.revision
                || hudTick - nameRequestTick >= NAME_RETRY_TICKS)) {
            PreviewStream.NameRequest request = new PreviewStream.NameRequest();
            request.id = hit.view.id; request.epoch = hit.view.epoch; request.revision = hit.view.revision;
            request.x = hit.at.posX; request.y = hit.at.posY; request.z = hit.at.posZ;
            nameRequestSection = s; nameRequestSlot = hit.slot; nameRequestTick = hudTick;
            nameRequestEpoch = request.epoch; nameRequestRevision = request.revision;
            request.send();
        }
        return "";
    }

    private void acceptNameReply() {
        PreviewStream.Packet p = nameReply.getAndSet(null);
        if (p == null) return;
        View view = views.get(p.id);
        if (view == null || view.dim != p.dim || view.epoch != p.epoch || view.revision != p.revision) return;
        Section s = view.sections.get(new ChunkPosition(p.x >> 4, p.y >> 4, p.z >> 4));
        if (s == null) return;
        if (s.names == null) s.names = new HashMap<>();
        if (s.names.size() >= 64) s.names.clear();
        s.names.put((p.y & 15) << 8 | (p.z & 15) << 4 | (p.x & 15), p);
    }

    @SubscribeEvent
    public void markerLabel(net.minecraftforge.client.event.RenderGameOverlayEvent.Post e) {
        if (e.type != net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL
                || aimedMarker == null && !groundContactHud) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
        if (aimedMarker != null) tooltip.draw(mc, e.resolution, aimedMarker, aimedHint, aimedAction, aimedColor, aimedHatch);
        if (groundContactHud) {
            String text = StatCollector.translateToLocal("structurebuildernh.groundContact");
            mc.fontRendererObj.drawStringWithShadow(text, (e.resolution.getScaledWidth() - mc.fontRendererObj.getStringWidth(text)) / 2,
                    e.resolution.getScaledHeight() / 2 - mc.fontRendererObj.FONT_HEIGHT - 12, PreviewFooting.COLOR);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void render(RenderWorldLastEvent e) {
        if (!unload) receivePackets(2);
        drainRetiredMoveSections();
        reloadPreviewTextures();
        Minecraft mc = Minecraft.getMinecraft();
        Entity eye = mc.renderViewEntity;
        groundContactHud = false;
        if (views.isEmpty() || TerminalBrowserWidget.coversWorld(mc.currentScreen) || eye == null) {
            glassPass.clear();
            footing.clear();
            return;
        }
        observe(eye.worldObj);
        occupancy.drain();
        samplingBudget = 450_000L;
        sampledSites = 0;
        samplingTurn = nextSamplingTurn;
        nextSamplingTurn = null;
        compiledThisFrame = false;
        sampledReleasedSite = false;
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
                        | GL11.GL_LIST_BIT
                        | GL11.GL_LINE_BIT
                        | GL11.GL_POLYGON_BIT);
        GL11.glPushMatrix();
        boolean sampledFooting = false;
        try {
            GL11.glTranslated(-x, -y, -z);
            // World-last callbacks may leave the lightmap or a different texture combiner active.
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDepthMask(false);
            visibleHatches.clear();
            visibleControllers.clear();
            visiblePreviews.clear();
            visibleBounds.clear();
            visibleIssues.clear();
            View nextView = null;
            Section nextSection = null;
            ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
            boolean terminal = held != null && held.getItem() == BuilderItem.ITEM;
            String selected = terminal && held.hasTagCompound() ? held.getTagCompound().getString("selected") : "";
            String tool = terminal && held.hasTagCompound() ? held.getTagCompound().getString("tool") : "";
            for (View incoming : views.values()) {
                if (eye.dimension != incoming.dim) continue;
                ChunkCoordinates issue = incoming.issue;
                if (issue != null && camera.isBoxInFrustum(issue.posX - .04, issue.posY - .04, issue.posZ - .04,
                        issue.posX + 1.04, issue.posY + 3.51, issue.posZ + 1.04)) visibleIssues.add(issue);
                Section warming = null;
                View view = incoming;
                if (incoming.previous != null) {
                    warming = pendingMoveMesh(incoming, camera);
                    if (warming == null && !incoming.waitingForSite && incoming.moveStreamReady) {
                        retireMoveSource(incoming);
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
                    if (camera.isBoxInFrustum(at.posX, at.posY, at.posZ, at.posX + 1., at.posY + 1., at.posZ + 1.)
                            && (moving != null && !moving.released || !occupancy.occupied(at.posX, at.posY, at.posZ)))
                        visibleControllers.add(view);
                }
                if (view.box == null) continue;
                visiblePreviews.add(view);
                if ((view.layers & PreviewStream.BOX) == 0) continue;
                visibleBounds.add(view);
                if (view.idText.equals(selected) && tool.equals(view.tool)) {
                    sampledFooting = true;
                    boolean contact = footing.sample(eye.worldObj, occupancy, view.footing, moving,
                            x, z, mc.gameSettings.renderDistanceChunks, view.acceptedFootingPose);
                    groundContactHud = contact && moving != null && moving == grip && !moving.released;
                }
            }
            if (sampledFooting) footing.writeDepth(camera);
            for (View view : visiblePreviews) {
                if ((view.layers & (PreviewStream.SHELL | PreviewStream.HATCH)) == 0) continue;
                Section candidate = drawSections(view, camera);
                if (candidate != null && (nextView == null || view.lastCompilation < nextView.lastCompilation)) {
                    nextView = view;
                    nextSection = candidate;
                }
            }
            // Opaque projections establish depth across all tasks before any translucent shell is blended.
            boolean drawSelection = terminal && shouldDrawPick(mc.thePlayer);
            boolean selectedMarker = drawSelection && highlightColor == SOLID_MARKER;
            targetMarker(selectedMarker);
            if (visiblePreviews.size() == 1 && renderGrip(visiblePreviews.get(0)) == null)
                occlusion.prepare(texturedSections, roleTextures, x, y, z);
            occlusion.issue(this);
            drawShellSections(texturedSections, true);
            if (!roleTextures.isEmpty()) {
                int previous = MarkerStroke.begin();
                try { drawShellSections(roleTextures, true); } finally { MarkerStroke.end(previous); }
            }
            occlusion.draw(this);
            drawShellSections(colorSections, false);
            if (!visibleHatches.isEmpty() || !visibleControllers.isEmpty()) {
                int previous = MarkerStroke.begin();
                try { drawMarkers(terminal); } finally { MarkerStroke.end(previous); }
            }
            if (drawSelection && !selectedMarker) drawPick(highlighted, highlightColor);
            drawGlassSections();
            drawBounds();
            drawIssues();
            if (sampledFooting) footing.drawSurface();
            if (!compiledThisFrame) {
                if (nextSection == null || (compilations & 7) == 7) {
                    Section pending = pendingTextureSwitch(eye.dimension);
                    if (pending != null) nextSection = pending;
                }
                if (nextSection != null) compileNext(nextSection);
            }
        } finally {
            occlusion.finish();
            roleTextures.clear();
            texturedSections.clear();
            colorSections.clear();
            visiblePreviews.clear();
            visibleBounds.clear();
            visibleIssues.clear();
            if (!sampledFooting) footing.clear();
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void targetMarker(boolean selected) {
        View v = highlightedController;
        ChunkCoordinates at = selected && v != null ? v.controller : null;
        if (selected && highlightedHatch != null) {
            Section s = highlightedHatch;
            v = s.owner;
            int i = highlightedHatchSlot;
            MarkerStroke.target(v.id, s.x + (i & 15), s.y + (i >> 8), s.z + (i >> 4 & 15),
                    markerTint(HATCH_ROLES[s.markerRoles(v)[hatchRank(s, i)] & 255].color));
        } else if (at != null) MarkerStroke.target(v.id, at.posX, at.posY, at.posZ, markerTint(HOST_COLOR));
        else MarkerStroke.target(null, 0, 0, 0, 0);
    }

    private void selectMarkerSection(Section section) {
        int next = MarkerStroke.slotFor(section.owner.id, section.x, section.y, section.z);
        if (MarkerStroke.changesSlot(next)) {
            sectionLists.flush();
            MarkerStroke.select(next);
        }
    }

    private void drawMarkers(boolean held) {
        if (visibleHatches.isEmpty() && visibleControllers.isEmpty()) return;
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glBlendFunc(EXTBlendColor.GL_CONSTANT_ALPHA_EXT, EXTBlendColor.GL_ONE_MINUS_CONSTANT_ALPHA_EXT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        if (held) GL11.glDisable(GL11.GL_BLEND);
        else {
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendColor(0, 0, 0, SHELL_ALPHA / 255f);
        }
        GL11.glDepthMask(held);
        GL11.glPolygonOffset(1f, 1f);
        for (Section s : visibleHatches) {
            if (s.marks == 0 || s.outlineOnly()) continue;
            selectMarkerSection(s);
            sectionLists.add(s.marks, renderGrip(s.owner));
        }
        sectionLists.flush();
        Tessellator t = Tessellator.instance;
        for (View view : visibleControllers) {
            if (view.controllerOutlineOnly()) continue;
            PreviewGrip moving = renderGrip(view);
            ChunkCoordinates at = moving == null ? view.controller : moving.at;
            ChunkCoordinates source = view.controller;
            MarkerStroke.select(MarkerStroke.slotFor(view.id, source.posX, source.posY, source.posZ));
            GL11.glPushMatrix();
            GL11.glTranslated(at.posX - (source.posX & 15), at.posY - (source.posY & 15), at.posZ - (source.posZ & 15));
            t.startDrawingQuads();
            markerCell(t, source.posX & 15, source.posY & 15, source.posZ & 15, 63, HOST_COLOR);
            t.draw();
            GL11.glPopMatrix();
        }
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDepthMask(false);
    }

    private static void markerCell(Tessellator t, int x, int y, int z, int sides, int color) {
        for (int side = 0; side < 6; side++) {
            if ((sides & 1 << side) == 0) continue;
            int axis = side / 2;
            int plane = axis == 0 ? x : axis == 1 ? y : z;
            int u = axis == 0 ? y : axis == 1 ? z : x;
            int v = axis == 0 ? z : axis == 1 ? x : y;
            t.setBrightness(MarkerStroke.face(x | z << 4 | y << 8, side, 15, false));
            markerFace(t, side, plane + (side & 1), u, v, 1, 1, color);
        }
    }

    private static void markerFace(Tessellator t, int side, int plane, int u, int v, int width, int height, int color) {
        int axis = side / 2;
        boolean positive = (side & 1) != 0;
        t.setColorRGBA_I(color, 255);
        markerVertex(t, axis, plane, u, v, 0, 0);
        markerVertex(t, axis, plane, u, v, positive ? width : 0, positive ? 0 : height);
        markerVertex(t, axis, plane, u, v, width, height);
        markerVertex(t, axis, plane, u, v, positive ? 0 : width, positive ? height : 0);
    }

    private static void markerVertex(Tessellator t, int axis, int plane, int u, int v, int du, int dv) {
        // Solid-color faces have no atlas sprite. Carry their face coordinates through display-list transforms.
        t.setTextureUV(du, dv);
        vertex(t, axis, plane, u + du, v + dv);
    }

    private void drawIssues() {
        if (visibleIssues.isEmpty()) return;
        ChunkCoordinates origin = visibleIssues.get(0);
        GL11.glPushMatrix();
        GL11.glTranslated(origin.posX + .5, origin.posY + .5, origin.posZ + .5);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glLineWidth(3f);
        GL11.glColor3f(1f, .18f, .15f);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        for (ChunkCoordinates p : visibleIssues) {
            double x = p.posX - origin.posX, y = p.posY - origin.posY, z = p.posZ - origin.posZ;
            // Keep axis lines directed from minus to plus so shared end pixels rasterize consistently.
            for (int i = -1; i <= 1; i += 2) for (int j = -1; j <= 1; j += 2) {
                t.addVertex(x - .53, y + i * .53, z + j * .53); t.addVertex(x + .53, y + i * .53, z + j * .53);
                t.addVertex(x + j * .53, y - .53, z + i * .53); t.addVertex(x + j * .53, y + .53, z + i * .53);
                t.addVertex(x + i * .53, y + j * .53, z - .53); t.addVertex(x + i * .53, y + j * .53, z + .53);
            }
            t.addVertex(x - .5, y - .5, z - .5); t.addVertex(x + .5, y + .5, z + .5);
            t.addVertex(x + .5, y - .5, z - .5); t.addVertex(x - .5, y + .5, z + .5);
            t.addVertex(x, y + .55, z); t.addVertex(x, y + 3, z);
        }
        t.draw();
        GL11.glPopMatrix();
    }

    private void drawBounds() {
        if (visibleBounds.isEmpty()) return;
        int x = MathHelper.floor_double(cameraX), y = MathHelper.floor_double(cameraY), z = MathHelper.floor_double(cameraZ);
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(2.5f);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        t.setColorRGBA_I(BOUNDS_COLOR, 255);
        for (View view : visibleBounds) {
            Bounds b = view.bounds;
            PreviewGrip moving = renderGrip(view);
            if (moving == null) {
                emitBox(t, b.minX - x, b.minY - y, b.minZ - z,
                        b.maxX - x + 1., b.maxY - y + 1., b.maxZ - z + 1.);
            } else {
                moving.worldCell(b.minX, b.minY, b.minZ, boundsStart);
                moving.worldCell(b.maxX, b.maxY, b.maxZ, boundsEnd);
                emitBox(t, Math.min(boundsStart[0], boundsEnd[0]) - x, Math.min(boundsStart[1], boundsEnd[1]) - y,
                        Math.min(boundsStart[2], boundsEnd[2]) - z, Math.max(boundsStart[0], boundsEnd[0]) - x + 1.,
                        Math.max(boundsStart[1], boundsEnd[1]) - y + 1., Math.max(boundsStart[2], boundsEnd[2]) - z + 1.);
            }
        }
        t.draw();
        GL11.glPopMatrix();
    }

    private static void emitBox(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1) {
        boundsRing(t, x0, y1, z0, x1, z1);
        for (int corner = 0; corner < 4; corner++) {
            double x = (corner & 1) == 0 ? x0 : x1;
            double z = (corner & 2) == 0 ? z0 : z1;
            t.addVertex(x, y0, z);
            t.addVertex(x, y1, z);
        }
        boundsRing(t, x0, y0, z0, x1, z1);
    }

    private static void boundsRing(Tessellator t, double x0, double y, double z0, double x1, double z1) {
        t.addVertex(x0, y, z0); t.addVertex(x1, y, z0);
        t.addVertex(x1, y, z0); t.addVertex(x1, y, z1);
        t.addVertex(x1, y, z1); t.addVertex(x0, y, z1);
        t.addVertex(x0, y, z1); t.addVertex(x0, y, z0);
    }

    private Section drawSections(View view, Frustrum camera) {
        Section candidate = null;
        PreviewGrip moving = renderGrip(view);
        boolean shells = (view.layers & PreviewStream.SHELL) != 0;
        boolean hatches = (view.layers & PreviewStream.HATCH) != 0;
        visibleSections.clear();
        for (Section s : view.sections.values()) {
            if (!(moving==null ? camera.isBoundingBoxInFrustum(s.box) : moving.visible(camera,s.box))) continue;
            if (refreshSite(s, moving, true)) visibleSections.add(s);
        }
        // Reserve alternating slots for the regular fair queue even during continuous repairs.
        for (int i = 0; i < visibleSections.size(); i++) {
            Section s = visibleSections.get(i);
            boolean shell = shells && s.hasShell;
            boolean hatch = hatches && s.hasHatches;
            if (!compiledThisFrame && (view.partialFaces == null || view.partialFaces == s)
                    && (compilations & 1) == 0 && (shell && s.dirty && s.replaceFaces
                    || hatch && s.replaceMarks && s.needsMarkFaces())) compileNext(s);
            if (hatch && !s.outlineOnly() && s.marks != 0) visibleHatches.add(s);
            if (s.needsMesh(view.layers) && preferMesh(s, candidate)) candidate = s;
            if (shell && s.drawableFaces() != 0) {
                if (s.faces != 0 && !s.facesTextured) colorSections.add(s);
                else if (hatches && (s.hasHatches || s.containsController())) roleTextures.add(s);
                else texturedSections.add(s);
            }
        }
        visibleSections.clear();
        return candidate;
    }

    private void drawShellSections(List<Section> sections, boolean textured) {
        drawShellSections(sections, textured, sections == roleTextures, false);
    }

    void drawTestedSections(List<Section> shells, List<Section> roles) {
        drawShellSections(shells, true, false, true);
        if (!roles.isEmpty()) {
            int previous = MarkerStroke.begin();
            try { drawShellSections(roles, true, true, true); } finally { MarkerStroke.end(previous); }
        }
    }

    private void drawShellSections(List<Section> sections, boolean textured, boolean markers, boolean tested) {
        if (sections.isEmpty()) return;
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        // Real blocks win the depth test while a paced packet or mesh rebuild is pending.
        GL11.glPolygonOffset(1f, 1f);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDepthMask(textured);
        if (textured) {
            Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0f);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_BLEND);
        } else {
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        for (Section s : sections) {
            int list = s.drawableFaces();
            if (list == 0 || s.probe != 0 && !tested) continue;
            if (markers) selectMarkerSection(s);
            PreviewGrip pose = renderGrip(s.owner);
            long parts = s.faces != 0 ? s.visiblePartMask : s.texturedBuild.shellParts & (-1L >>> (64 - s.texturedBuild.part));
            for (; parts != 0; parts &= parts - 1) sectionLists.add(list + Long.numberOfTrailingZeros(parts), pose);
        }
        sectionLists.flush();
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
    }

    private void drawGlassSections() {
        for (Section section : texturedSections) {
            if (section.probe == -1) continue;
            WorldPreviewGlass.Mesh glass = section.drawableGlass();
            if (glass != null) glassPass.include(glass, renderGrip(section.owner), false);
        }
        for (Section section : roleTextures) {
            if (section.probe == -1) continue;
            WorldPreviewGlass.Mesh glass = section.drawableGlass();
            if (glass != null) glassPass.include(glass, renderGrip(section.owner), true);
        }
        glassPass.draw(cameraX, cameraY, cameraZ);
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
            if (s.needsMesh(destination.layers) && preferMesh(s, candidate)) candidate = s;
        }
        return candidate;
    }

    private void observe(World world) {
        if (observedWorld == world) return;
        footing.worldChanged();
        groundContactHud = false;
        if (occupancy != null) occupancy.close();
        observedWorld = world;
        occupancy = world == null ? null : new ProjectionOccupancy(world);
    }

    private static boolean preferMesh(Section next, Section current) {
        if (current == null) return true;
        // Finish the visible section's private GL lists before opening more for the same task.
        if ((next.texturedBuild != null) != (current.texturedBuild != null)) return next.texturedBuild != null;
        return next.lastCompilation < current.lastCompilation;
    }

    /** Finish previously streamed color sections even when they have moved outside the camera frustum. */
    private Section pendingTextureSwitch(int dimension) {
        Section candidate = null;
        for (View view : views.values()) {
            if (view.dim != dimension || view.previous != null || renderGrip(view) != null) continue;
            Iterator<Section> it = view.textureSwitch.iterator();
            while (it.hasNext()) {
                Section next = it.next();
                if (!next.hasShell || !next.dirty || next.facesTextured) { it.remove(); continue; }
                if (candidate == null || view.lastCompilation < candidate.owner.lastCompilation) candidate = next;
                break;
            }
        }
        return candidate != null && refreshSite(candidate, null, false) && candidate.hasShell ? candidate : null;
    }

    private void compileNext(Section s) {
        compiledThisFrame = true;
        s.owner.lastCompilation = s.lastCompilation = ++compilations;
        int layers = s.owner.layers;
        boolean roles = (layers & PreviewStream.HATCH) != 0 && s.hasHatches;
        if (s.replaceFaces && s.dirty && s.textured && s.hasShell && (layers & PreviewStream.SHELL) != 0) rebuildFaces(s);
        else if (roles && s.needsMarkFaces()) rebuildMarks(s);
        else if ((layers & PreviewStream.SHELL) != 0 && s.hasShell && s.dirty) rebuildFaces(s);
    }

    private boolean refreshSite(Section s, PreviewGrip moving, boolean presenting) {
        boolean freeDrag = moving != null && !moving.released;
        ProjectionOccupancy world = freeDrag ? null : occupancy;
        PreviewGrip pose = freeDrag ? null : moving;
        if (!s.site.dirty && s.site.boundTo(world, pose)) return true;
        boolean faces = presenting && s.drawableFaces() != 0 && (s.owner.layers & PreviewStream.SHELL) != 0;
        boolean marks = presenting && !s.outlineOnly() && s.marks != 0 && (s.owner.layers & PreviewStream.HATCH) != 0;
        boolean cached = faces || marks;
        // A drop changes the binding of every visible section at once. Present its existing
        // mesh until its share of the destination sampling budget becomes available.
        boolean releasedSite = moving != null && moving.released;
        if (!freeDrag && (samplingBudget <= 0 || sampledSites >= 4 || releasedSite && sampledReleasedSite
                || samplingTurn != null && samplingTurn != s)) {
            if (nextSamplingTurn == null || s.owner.lastSample < nextSamplingTurn.owner.lastSample
                    || s.owner == nextSamplingTurn.owner && s.lastSample < nextSamplingTurn.lastSample) nextSamplingTurn = s;
            // World notifications cover whole sections; continue displaying the sampled mesh
            // until this section gets its sampling turn, including while standing still.
            return cached;
        }
        if (!freeDrag) {
            samplingTurn = null;
            s.owner.lastSample = s.lastSample = ++sampleSequence;
            sampledSites++;
        }
        if (releasedSite) sampledReleasedSite = true;
        long start = System.nanoTime();
        s.site.bind(world, pose, s.x, s.y, s.z);
        s.site.read(s, sampledOccupancy, s.owner.textured);
        long changedWords = 0;
        if (!Arrays.equals(s.occupied, sampledOccupancy)) {
            for (int word = 0; word < sampledOccupancy.length; word++) {
                long changed = s.occupied[word] ^ sampledOccupancy[word];
                if (changed == 0) continue;
                changedWords |= 1L << word;
                for (; changed != 0; changed &= changed - 1) {
                    int slot = word * 64 + Long.numberOfTrailingZeros(changed);
                    s.site.changedCells.include(s.x + (slot & 15), s.y + (slot >> 8), s.z + (slot >> 4 & 15));
                }
            }
            int shellBorders = changedBorders(s.occupied, sampledOccupancy);
            int borders = s.updateVisibility(sampledOccupancy);
            s.occupied = Arrays.equals(sampledOccupancy, EMPTY_MASK) ? EMPTY_MASK : sampledOccupancy.clone();
            s.replaceFaces |= faces && s.hasShell && !s.owner.textured;
            s.replaceMarks |= marks && s.hasHatches && s.marksDirty;
            boolean immediateNeighbors = presenting && !releasedSite;
            invalidateHatchNeighbors(s.owner, s, borders, immediateNeighbors && (s.owner.layers & PreviewStream.HATCH) != 0);
            for (int side = 0; !s.owner.textured && side < s.near.length; side++) {
                Section near = s.near[side];
                if (near == null || (shellBorders & (1 << side)) == 0) continue;
                near.markFacesDirty();
                near.replaceFaces |= immediateNeighbors && near.faces != 0 && (s.owner.layers & PreviewStream.SHELL) != 0;
            }
        }
        if (s.owner.textured && !s.site.changedCells.empty())
            invalidateWorldTextures(s, changedWords);
        if (!freeDrag) samplingBudget -= System.nanoTime() - start;
        return true;
    }

    private static void invalidateWorldTextures(Section source, long changedWords) {
        // Native column/CTM icons may have read diagonally or further than their immediate neighbor.
        // Each compiled part records its reads, even when the queried value came from the cache.
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            Section s = dx == 0 && dy == 0 && dz == 0 ? source : source.owner.sections.get(
                    new ChunkPosition((source.x >> 4) + dx, (source.y >> 4) + dy, (source.z >> 4) + dz));
            if (s == null || !s.hasShell) continue;
            if (!s.textured) {
                if (s == source || dx * dx + dy * dy + dz * dz == 1) s.markFacesDirty();
                continue;
            }
            TexturedBuild mesh = s.texturedBuild != null ? s.texturedBuild : s.publishedTexture;
            if (mesh != null) {
                mesh.invalidate(source.site.changedCells, s == source ? changedWords : 0);
                s.dirty |= mesh.repairs != 0;
                s.replaceFaces |= mesh.urgent != 0;
            }
        }
    }

    private static void rebuildMarks(Section s) {
        s.marks = compileHatches(s.owner, s);
        s.marksDirty = s.marks == 0;
        s.replaceMarks = false;
    }

    private static void rebuildFaces(Section s) {
        if (s.owner.textured && s.textured) {
            // A visible partial build wins selection. Starting another one means the camera
            // has left the old section; release its private lists before allocating more.
            Section previous = s.owner.partialFaces;
            if (previous != null && previous != s && s.publishedTexture == null
                    && !s.owner.textureSwitch.contains(previous)) previous.markFacesDirty();
            boolean switching = s.owner.textureSwitch.contains(s);
            long deadline = switching ? System.nanoTime() + 450_000L : 0;
            for (int step = 0; ; step++) {
                s.dirty = !compileTexturedStep(s.owner, s);
                if (!s.dirty || !switching || step == 7 || System.nanoTime() >= deadline) break;
            }
            if (!s.dirty) s.owner.textureSwitch.remove(s);
            if (s.texturedBuild != null) s.owner.partialFaces = s;
            else if (s.owner.partialFaces == s) s.owner.partialFaces = null;
        }
        else {
            s.cancelTexturedBuild();
            s.faces = compileShell(s.owner, s, s.faces);
            s.dirty = s.faces == 0;
            if (!s.dirty) {
                s.facesTextured = false;
                s.visibleWords = 64;
                s.releaseFaceParts();
            }
        }
        if (!s.dirty) s.replaceFaces = false;
    }

    private PreviewGrip renderGrip(View v) {
        if (v != null && v.fixedPose != null) return v.fixedPose;
        PreviewGrip g=grip;
        return g!=null && g.moving && !g.rejected && v!=null && v.revision==g.revision && g.id.equals(v.id) ? g : null;
    }

    /** Section vertices stay local; their translation is replayed by the GPU for every camera pose. */
    static void beginSectionList(int list, Section s) {
        GL11.glNewList(list, GL11.GL_COMPILE);
        GL11.glPushMatrix();
        GL11.glTranslatef(s.x, s.y, s.z);
    }

    static void endSectionList() {
        GL11.glPopMatrix();
        GL11.glEndList();
    }

    private static final class PositionedLists {
        private final IntBuffer ids = BufferUtils.createIntBuffer(256);
        private PreviewGrip pose;

        void add(int id, PreviewGrip nextPose) {
            if (pose != nextPose || !ids.hasRemaining()) flush();
            pose = nextPose;
            ids.put(id);
        }

        void flush() {
            if (ids.position() == 0) return;
            if (pose != null) {
                GL11.glPushMatrix();
                pose.transform();
                if (pose.mirrored) GL11.glCullFace(GL11.GL_FRONT);
            }
            ids.flip();
            if (ids.remaining() == 1) GL11.glCallList(ids.get(0));
            else {
                GL11.glListBase(0);
                GL11.glCallLists(ids);
            }
            ids.clear();
            if (pose != null) {
                if (pose.mirrored) GL11.glCullFace(GL11.GL_BACK);
                GL11.glPopMatrix();
                pose = null;
            }
        }
    }

    private static int compileHatches(View view, Section s) {
        int list = s.marks != 0 ? s.marks : GL11.glGenLists(1);
        if (list == 0) return 0;
        beginSectionList(list, s);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        HatchMesh.Boundary boundary = hatchBoundary(s);
        for (int word = 0; word < 64; word++) {
            for (long bits = s.hatch[word] & ~s.occupied[word]; bits != 0; bits &= bits - 1) {
                int cell = word * 64 + Long.numberOfTrailingZeros(bits);
                int role = s.markerRoles(view)[hatchRank(s, cell)] & 255;
                int sides = HatchMesh.exposedSides(s.hatch, s.occupied, boundary, cell);
                for (int side = 0; side < 6; side++) {
                    if ((sides & 1 << side) == 0) continue;
                    int x = cell & 15, y = cell >> 8, z = cell >> 4 & 15, axis = side / 2;
                    t.setBrightness(markerData(s, cell, side, false));
                    markerFace(t, side, (axis == 0 ? x : axis == 1 ? y : z) + (side & 1),
                            axis == 0 ? y : axis == 1 ? z : x, axis == 0 ? z : axis == 1 ? x : y,
                            1, 1, HATCH_ROLES[role].color);
                }
            }
        }
        t.draw();
        endSectionList();
        return list;
    }

    private static HatchMesh.Boundary hatchBoundary(Section s) {
        return (side, slot) -> {
            Section neighbor = s.near[side];
            int word = slot >> 6;
            return neighbor != null && (neighbor.hatch[word] & ~neighbor.occupied[word] & (1L << (slot & 63))) != 0;
        };
    }

    private static int markerTint(int color) {
        return color + shade(0xffffff - color, 40);
    }

    private static boolean texturedFaceCovered(Section s, int slot, int side, IBlockAccess access) {
        int shift = side < 2 ? 0 : side < 4 ? 8 : 4;
        int next = (slot >> shift & 15) + ((side & 1) == 0 ? -1 : 1);
        Section adjacent = next < 0 || next > 15 ? s.near[side] : s;
        if (adjacent == null) return false;
        int neighbor = (slot & ~(15 << shift)) | ((next & 15) << shift);
        long bit = 1L << (neighbor & 63);
        if ((adjacent.occupied[neighbor >> 6] & bit) == 0) {
            return (adjacent.solid[neighbor >> 6] & bit) != 0
                    && BlockPreviewTextures.worldOccludes(adjacent.appearance(neighbor), s.appearance(slot));
        }
        BlockPreviewTextures.Appearance appearance = s.appearance(slot);
        int sideId = BlockPreviewTextures.nativeSide(side);
        net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(sideId);
        int x = s.x + (slot & 15) + d.offsetX, y = s.y + (slot >> 8) + d.offsetY, z = s.z + (slot >> 4 & 15) + d.offsetZ;
        try {
            return BlockPreviewTextures.worldOccludes(access.getBlock(x, y, z), access.getBlockMetadata(x, y, z), appearance);
        } catch (RuntimeException | LinkageError unsupported) {
            return false;
        }
    }

    private static final class TexturedBuild {
        // Keep expensive native/transparent models on the short incremental-build path.
        final int[] ends = new int[64];
        final int parts, head;
        final PreviewRegion[] dependencies;
        PreviewBlockAccess access;
        long repairs, urgent;
        long shellParts;
        int part;
        WorldPreviewGlass.Collector glassFaces;
        WorldPreviewGlass.Mesh glass;

        void rememberGlass(int slot, int side) {
            if (glassFaces == null) glassFaces = new WorldPreviewGlass.Collector();
            glassFaces.face(slot, side);
        }

        TexturedBuild(Section s) {
            int count = 0, cells = 0;
            boolean[] complex = new boolean[s.renders.size() + 1];
            int complexTypes = 0;
            for (int i = 0; i < s.renders.size(); i++) {
                BlockPreviewTextures.Appearance a = s.paletteAppearance(i);
                complex[i + 1] = a.glass || a.fluid != null || a.frame || a.fence;
                if (complex[i + 1]) complexTypes++;
            }
            for (int word = 0; word < s.solid.length; word++) {
                // Include occupied slots: they can reappear after a real block is removed.
                long bits = s.solid[word];
                int next = Long.bitCount(bits);
                if (complexTypes != 0) {
                    if (complexTypes == s.renders.size() || s.renderSlots == null) {
                        next *= 4;
                    } else {
                        for (; bits != 0; bits &= bits - 1) {
                            int slot = word * 64 + Long.numberOfTrailingZeros(bits);
                            int material = s.renderSlots[slot] & 65535;
                            if (material < complex.length && complex[material]) next += 3;
                        }
                    }
                }
                if (cells + next > 256) { ends[count++] = word; cells = 0; }
                cells += next;
            }
            ends[count++] = s.solid.length;
            parts = count;
            dependencies = new PreviewRegion[parts];
            head = GL11.glGenLists(parts);
        }

        void release() {
            if (head != 0) GL11.glDeleteLists(head, parts);
            if (glass != null) glass.release();
        }

        void invalidate(PreviewRegion changed, long localWords) {
            access = null;
            for (int i = 0; i < part; i++) {
                if (!dependencies[i].intersects(changed)) continue;
                repairs |= 1L << i;
                int first = i == 0 ? 0 : ends[i - 1];
                long words = (-1L << first) & (-1L >>> (64 - ends[i]));
                if ((localWords & words) != 0) urgent |= 1L << i;
            }
        }
    }

    private static boolean compileTexturedStep(View view, Section s) {
        TexturedBuild build = s.texturedBuild;
        if (build == null && s.publishedTexture != null && s.publishedTexture.repairs != 0) build = s.publishedTexture;
        if (build == null) {
            build = new TexturedBuild(s);
            if (build.head == 0) return false;
            s.texturedBuild = build;
        }
        try {
            int index = build.repairs != 0 ? Long.numberOfTrailingZeros(build.urgent != 0 ? build.urgent : build.repairs)
                    : build.part;
            if (index >= build.parts) return true;
            if (build.access == null) build.access = textureAccess(view, s);
            int firstWord = index == 0 ? 0 : build.ends[index - 1], endWord = build.ends[index];
            PreviewRegion reads = build.dependencies[index];
            if (reads == null) build.dependencies[index] = reads = new PreviewRegion();
            reads.clear();
            // Face culling also reads the six immediate neighbors without consulting IBlockAccess.
            for (int word = firstWord; word < endWord; word++) {
                int y = s.y + (word >> 2), z = s.z + (word & 3) * 4;
                if (s.solid[word] == 0) continue;
                reads.include(s.x - 1, y - 1, z - 1);
                reads.include(s.x + 16, y + 1, z + 4);
            }
            build.access.reads = reads;
            // Submit completed parts directly. A cached parent can capture empty or outdated
            // child geometry when the renderer emulates legacy display lists.
            beginSectionList(build.head + index, s);
            try {
                build.shellParts &= ~(1L << index);
                emitTexturedPart(s, build, firstWord, endWord, index);
            } finally {
                endSectionList();
            }
            if (build.glass == null && build.glassFaces != null && build.glassFaces.size != 0)
                build.glass = new WorldPreviewGlass.Mesh(s.x, s.y, s.z, view.id);
            if (build.glass != null && !build.glass.replace(index, s, build.access, build.glassFaces)) {
                build.glassFaces.size = 0;
                return false;
            }
            if (s.publishedTexture == build) {
                s.glass = build.glass;
                s.visiblePartMask = build.shellParts;
            }
            build.repairs &= ~(1L << index);
            build.urgent &= ~(1L << index);
            s.replaceFaces = build.urgent != 0;
            if (index == build.part) build.part++;
            if (build.part < build.parts || build.repairs != 0) return false;
            build.access = null;
            build.glassFaces = null;
            if (s.publishedTexture == build) return true;
            s.texturedBuild = null;
            s.clearFaces();
            s.faces = build.head;
            s.glass = build.glass;
            s.faceParts = build.parts - 1;
            s.visibleParts = build.parts;
            s.visiblePartMask = build.shellParts;
            s.facesTextured = true;
            s.publishedTexture = build;
            return true;
        } catch (RuntimeException | Error failure) {
            s.cancelTexturedBuild();
            throw failure;
        }
    }

    private static void emitTexturedPart(Section s, TexturedBuild build, int firstWord, int endWord, int index) {
        IBlockAccess access = build.access;
        Tessellator tessellator = Tessellator.instance;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        tessellator.startDrawingQuads();
        tessellator.setBrightness(0xf000f0);
        long[][] fallback = new long[6][];
        long[][] overlays = new long[6][];
        ProjectedBlockFaces.emit(s.solid, s.occupied, firstWord, endWord, slot -> s.appearance(slot).fence,
                (side, slot) -> texturedFaceCovered(s, slot, side, access), (side, slot) -> {
            BlockPreviewTextures.Appearance appearance = s.appearance(slot);
            if (appearance.glass || appearance.fluid != null && appearance.translucent) {
                build.rememberGlass(slot, side);
                return;
            }
            build.shellParts |= 1L << index;
            int x = s.x + (slot & 15), y = s.y + (slot >> 8), z = s.z + (slot >> 4 & 15);
            tessellator.setBrightness(side == ProjectedBlockFaces.MODEL ? 0xf000f0 : markerData(s, slot, side, true));
            if (side == ProjectedBlockFaces.MODEL) {
                int covered = 0;
                for (int face = 0; face < 6; face++)
                    if (texturedFaceCovered(s, slot, face, access)) covered |= 1 << face;
                PreviewFence.draw(tessellator, appearance, slot, 255, access, x, y, z, 0, covered, s);
            } else if (!appearance.textured()) ProjectedBlockFaces.remember(fallback, side, slot);
            else if (appearance.hasGlowOverlay(side)) {
                BlockPreviewTextures.faceLayers(tessellator, appearance, side, slot, 255, access, x, y, z, 0, 1);
                ProjectedBlockFaces.remember(overlays, side, slot);
            } else BlockPreviewTextures.face(tessellator, appearance, side, slot, 255, access, x, y, z);
        });
        tessellator.draw();
        // Filtered glow masks contain fractional coverage even when the source PNG is binary.
        // Composite these texels over the opaque CTM substrate instead of overwriting it.
        for (long[] faces : overlays) if (faces != null) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            tessellator.startDrawingQuads();
            ProjectedBlockFaces.emit(overlays, (side, slot) -> {
                BlockPreviewTextures.Appearance a = s.appearance(slot);
                tessellator.setBrightness(markerData(s, slot, side, true));
                BlockPreviewTextures.faceLayers(tessellator, a, side, slot, 255, access, s.x + (slot & 15),
                        s.y + (slot >> 8), s.z + (slot >> 4 & 15), 1, a.faces[BlockPreviewTextures.nativeSide(side)].length);
            });
            tessellator.draw();
            GL11.glDisable(GL11.GL_BLEND);
            break;
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        for (long[] faces : fallback) if (faces != null) {
            tessellator.startDrawingQuads();
            ProjectedBlockFaces.emit(fallback, (side, slot) -> {
                tessellator.setBrightness(markerData(s, slot, side, false));
                int color = s.color(slot);
                color = color == 0 ? COLORS[side] : shade(color, side == 3 ? 100 : side < 2 ? 82 : 69);
                int axis = side / 2, x = slot & 15, y = slot >> 8, z = slot >> 4 & 15;
                int plane = (axis == 0 ? x : axis == 1 ? y : z) + (side & 1);
                int u = axis == 0 ? y : axis == 1 ? z : x;
                int v = axis == 0 ? z : axis == 1 ? x : y;
                markerFace(tessellator, side, plane, u, v, 1, 1, color);
            });
            tessellator.draw();
            break;
        }
    }

    private static PreviewBlockAccess textureAccess(View view, Section section) {
        World world = Minecraft.getMinecraft().theWorld;
        Section[] halo = new Section[27];
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++)
            halo[13 + dx + 3 * dy + 9 * dz] = view.sections.get(
                    new ChunkPosition((section.x >> 4) + dx, (section.y >> 4) + dy, (section.z >> 4) + dz));
        return PreviewBlockAccess.lazy(section.x, section.y, section.z, (x, y, z) -> {
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
        beginSectionList(list, s);
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
                        if ((s.occupied[i >> 6] & bit) != 0 || (s.solid[i >> 6] & bit) == 0)
                            continue;
                        // The role mesh supplies this face. Keeping it in both layers would blend it twice.
                        if (roleLayer && (i == host || (s.hatch[i >> 6] & (1L << (i & 63))) != 0)) continue;
                        int n = plane + dir;
                        Section next = n < 0 || n > 15 ? s.near[side] : s;
                        int neighbor = slot(axis, n & 15, u, v);
                        // Empty choices leave the adjacent block's exposed face visible.
                        if (next != null && (next.occupied[neighbor >> 6] & (1L << (neighbor & 63))) == 0
                                && solid(next, neighbor) && (next.choiceMaterial(neighbor) || !s.choiceMaterial(i))) continue;
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
        endSectionList();
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

    private static int hatchType(Section s, int x, int y, int z) {
        Section near = s;
        if ((x | y | z) < 0 || x >= 16 || y >= 16 || z >= 16) {
            near = s.owner.sections.get(new ChunkPosition((s.x + x) >> 4, (s.y + y) >> 4, (s.z + z) >> 4));
            if (near == null) return 0;
        }
        int i = (x & 15) | (z & 15) << 4 | (y & 15) << 8;
        if ((near.hatch[i >> 6] & ~near.occupied[i >> 6] & (1L << i)) == 0) return 0;
        if (near.roleIndex == null || near.roleIndex.hidden != near.occupied) {
            near.roleIndex = new HatchMesh.Roles(near.hatch, near.occupied, near.markerRoles(near.owner));
        }
        return near.roleIndex.type(i);
    }

    static int markerData(Section s, int i, int side, boolean textured) {
        int ordinary = textured ? 0xf000f0 : 0;
        if ((s.owner.layers & PreviewStream.HATCH) == 0) return ordinary;
        int x = i & 15, y = i >> 8, z = i >> 4 & 15;
        ChunkCoordinates host = s.owner.controller;
        if (host != null && s.x + x == host.posX && s.y + y == host.posY && s.z + z == host.posZ)
            return MarkerStroke.face(i, side, 15, textured);
        int type = hatchType(s, x, y, z);
        if (type == 0) return ordinary;
        int axis = side / 2, edges = 0, outward = (side & 1) == 0 ? -1 : 1;
        for (int edge = 0; edge < 4; edge++) {
            int across = (axis + 1 + edge / 2) % 3, step = (edge & 1) == 0 ? -1 : 1;
            int u = x + (across == 0 ? step : 0), v = y + (across == 1 ? step : 0), w = z + (across == 2 ? step : 0);
            if (hatchType(s, u, v, w) != type || hatchType(s, u + (axis == 0 ? outward : 0),
                    v + (axis == 1 ? outward : 0), w + (axis == 2 ? outward : 0)) != 0) edges |= 1 << edge;
        }
        return MarkerStroke.face(i, side, edges, textured);
    }

    private static void drawPick(ChunkCoordinates at, int color) {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        // The opaque selection also occludes the later translucent glass pass.
        GL11.glDepthMask(true);
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
        GL11.glDepthMask(false);
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
