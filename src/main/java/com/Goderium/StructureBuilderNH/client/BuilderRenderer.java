package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.BuilderProxy;
import com.Goderium.StructureBuilderNH.common.builder.PreviewStream;
import com.Goderium.StructureBuilderNH.common.builder.HatchTypes;
import com.Goderium.StructureBuilderNH.common.builder.PreviewAim;
import com.Goderium.StructureBuilderNH.common.builder.PreviewEdit;
import com.Goderium.StructureBuilderNH.common.builder.BuilderItem;
import com.Goderium.StructureBuilderNH.util.Bounds;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import gregtech.api.GregTechAPI;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.ResourceLocation;
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
    private final Queue<PreviewStream.Packet> packets = new ConcurrentLinkedQueue<>();
    private final Map<UUID, View> views = new LinkedHashMap<>();
    private final PreviewEditQueue editQueue = new PreviewEditQueue(new PreviewEditQueue.Transport() {
        @Override
        public boolean aimed(PreviewEditQueue.Target target) { return editTargetAimed(target); }

        @Override
        public void send(PreviewEditQueue.Target target, int revision, int steps, boolean group, long request) {
            PreviewEdit.send(target.task, revision, target.x, target.y, target.z, steps, group, request);
        }
    });
    private final List<Section> visibleHatches = new ArrayList<>();
    private final List<Section> transparentSections = new ArrayList<>();
    private final List<Section> texturedOutlines = new ArrayList<>();
    private double cameraX, cameraY, cameraZ;
    private final List<net.minecraft.util.ChunkCoordinates> visibleControllers = new ArrayList<>();
    private long compilations;
    private String aimedMarker;
    private int aimedColor;
    private Pick aimed;
    private String aimedHint;
    private boolean aimedHatch;
    private final PreviewTooltip tooltip = new PreviewTooltip();
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
        return instance == null ? null : instance.pick(Minecraft.getMinecraft().thePlayer);
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
        Pick hit = pick(player);
        PreviewEditQueue.Target target = hit == null ? editQueue.pendingAt(this::editTargetAimed) : editTarget(hit, player);
        if (target == null) return false;
        if (previewModifierDown() && target.groupSelectable) editQueue.applyGroup(target, System.nanoTime());
        // Ordinary right-click consumes the editable projection without opening a new task.
        return true;
    }

    @Override
    public void previewEditResult(PreviewEdit.Result result) { editQueue.reply(result); }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void scrollPreview(MouseEvent event) {
        if (event.dwheel == 0 || !previewModifierDown()) return;
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (!editContext(player)) return;
        Pick hit = pick(player);
        PreviewEditQueue.Target target = hit == null ? editQueue.pendingAt(this::editTargetAimed) : editTarget(hit, player);
        if (target == null) return;
        int notches = (int) Math.min(PreviewEdit.MAX_STEPS, Math.max(1, Math.abs((long) event.dwheel) / 120));
        editQueue.cycle(target, event.dwheel < 0 ? notches : -notches, System.nanoTime());
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void previewInputTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && editContext(Minecraft.getMinecraft().thePlayer))
            editQueue.tick(System.nanoTime());
    }

    @SubscribeEvent
    public void previewInputUnload(WorldEvent.Unload event) {
        if (event.world.isRemote) editQueue.clear();
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
        Pick current = pick(player);
        if (current != null) return current.taskId.equals(target.task) && current.x == target.x
                && current.y == target.y && current.z == target.z;
        View view = views.get(target.task);
        if (view == null || !view.editable || !target.tool.equals(view.tool) || player.dimension != view.dim
                || (view.layers & PreviewStream.SHELL) == 0 || !view.bounds.contains(target.x, target.y, target.z)) return false;
        Section section = view.sections.get(new ChunkPosition(target.x >> 4, target.y >> 4, target.z >> 4));
        if (section != null && section.epoch == view.epoch && section.revision == view.revision) return false;
        // Acknowledged edits can temporarily leave an old section while its replacement arrives.
        ChunkCoordinates hit = PreviewAim.first(player, (x, y, z) -> x == target.x && y == target.y && z == target.z);
        return hit != null;
    }

    private Pick pick(EntityPlayer player) {
        return pick(player, true);
    }

    private Pick pick(EntityPlayer player, boolean requireCurrentSection) {
        if (player == null || Minecraft.getMinecraft().currentScreen != null) return null;
        ItemStack held = player.getHeldItem();
        if (held == null || held.getItem() != BuilderItem.ITEM || !held.hasTagCompound()) return null;
        String tool = held.getTagCompound().getString("tool");
        String selected = held.getTagCompound().getString("selected");
        if (views.isEmpty() || tool.isEmpty()) return null;
        ChunkCoordinates pos = PreviewAim.first(player, (x, y, z) ->
                editableAt(player.dimension, tool, selected, x, y, z, requireCurrentSection) != null);
        if (pos == null) return null;
        View view = editableAt(player.dimension, tool, selected, pos.posX, pos.posY, pos.posZ, requireCurrentSection);
        Section s = view.sections.get(new ChunkPosition(pos.posX >> 4, pos.posY >> 4, pos.posZ >> 4));
        return new Pick(view.id, s.revision, pos.posX, pos.posY, pos.posZ,
                s.style((pos.posX & 15) | ((pos.posZ & 15) << 4) | ((pos.posY & 15) << 8)));
    }

    private View editableAt(int dim, String tool, String selected, int x, int y, int z, boolean requireCurrentSection) {
        View best = null;
        ChunkPosition pos = null;
        int slot = (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
        for (View view : views.values()) {
            if (!view.editable || dim != view.dim || !tool.equals(view.tool)
                    || (view.layers & PreviewStream.SHELL) == 0 || !view.bounds.contains(x, y, z)) continue;
            if (pos == null) pos = new ChunkPosition(x >> 4, y >> 4, z >> 4);
            Section s = view.sections.get(pos);
            if (s == null || s.choice(slot) == 0) continue;
            if (requireCurrentSection && (s.epoch != view.epoch || s.revision != view.revision)) continue;
            if (view.idText.equals(selected)) return view;
            if (best == null || view.idText.compareTo(best.idText) < 0) best = view;
        }
        return best;
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
        int dim, layers;
        int controllerMeta, tier, ambiguousDots;
        String[] dotHints;
        final Map<List<String>, HatchInfo[]> labels = new HashMap<>();
        int epoch, revision;
        boolean editable, textured;
        long controllerCheckTick = Long.MIN_VALUE;
        boolean showController;
        final Map<Integer, Assembly> fragments = new HashMap<>();
        final Map<Integer, Map<Long, Integer>> choiceTypes = new HashMap<>();
        int nextChoiceType;
        long lastCompilation;

        View(UUID id) {
            this.id = id;
            idText = id.toString();
        }

        int choiceType(PreviewStream.Style style) {
            if (style.group < 0) return 0;
            long material = ((long) style.item << 32) | (style.damage & 0xffffffffL);
            return choiceTypes.computeIfAbsent(style.group, k -> new HashMap<>())
                    .computeIfAbsent(material, k -> ++nextChoiceType);
        }

        void invalidateLabels() {
            dotHints = null;
            labels.clear();
            for (Section s : sections.values()) {
                s.labels = null;
                s.markerRoles = null;
                if (s.hasHatches) s.marksDirty = true;
            }
        }

        void clear() {
            for (Section section : sections.values()) section.clear();
            sections.clear();
            fragments.clear();
            choiceTypes.clear();
            nextChoiceType = 0;
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
    private static final ResourceLocation MARKER_GRID = new ResourceLocation("structurebuildernh", "preview/marker_grid");
    private static final HatchRole[] HATCH_ROLES = HatchRole.values();
    private static final int SHELL_ALPHA = 100;
    private static final int[] COLORS = {
        0x317688, 0x61BFB5, 0x345D76, 0x91E2D7, 0x3D7792, 0x63A5BB
    };
    private static final int[] MATERIAL_COLORS = {
        0xe2ad64, 0x87c5df, 0x96cf98, 0xc4a1df, 0xe59491, 0xcdd47d, 0x92d6c9, 0xdba0c4
    };

    static final class Section {
        final int x, y, z;
        final long[] solid, hatch;
        final byte[] dots;
        final HatchTypes hatchTypes;
        HatchInfo[][] labels;
        byte[] markerRoles;
        final long[] optional, choices;
        final short[] styleSlots;
        final List<PreviewStream.Style> styles;
        final int[] colors;
        final int[] choiceTypes;
        final int epoch, revision;
        boolean textured;
        short[] renderSlots;
        List<PreviewStream.RenderMaterial> renders;
        BlockPreviewTextures.Appearance[] appearances;
        long[] confirmed;
        final Section[] near = new Section[6];
        final AxisAlignedBB box;
        int faces, glassFaces, marks, outline;
        double sortDistance;
        boolean facesTextured;
        boolean dirty = true, marksDirty = true, outlineDirty = true, hasHatches, hasShell, hasChoices;
        long lastCompilation;

        Section(View view, PreviewStream.Packet p) {
            x = p.x * 16;
            y = p.y * 16;
            z = p.z * 16;
            solid = p.solid;
            hatch = p.hatch;
            dots = p.dots;
            hatchTypes = p.hatchTypes;
            optional = p.optional;
            choices = p.styles.isEmpty() ? null : p.painted;
            styleSlots = p.styleSlots;
            styles = p.styles;
            epoch = p.epoch;
            revision = p.revision;
            textured = view.textured && p.textured;
            renderSlots = textured ? p.renderSlots : null;
            renders = !textured || p.renders == null ? Collections.emptyList() : p.renders;
            colors = new int[styles.size()];
            choiceTypes = new int[styles.size()];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = materialColor(styles.get(i));
                choiceTypes[i] = view.choiceType(styles.get(i));
            }
            updateProgress(p.confirmed);
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

        long choice(int slot) {
            if (choices == null || (confirmed[slot >> 6] & (1L << (slot & 63))) != 0) return 0;
            int index = styleSlots[slot] & 65535;
            return index == 0 ? 0 : choiceTypes[index - 1];
        }

        boolean sameChoices(Section other) {
            if (!Arrays.equals(choiceTypes, other.choiceTypes) || !Arrays.equals(styleSlots, other.styleSlots)) return false;
            if (choices == null) return other.choices == null;
            for (int word = 0; word < choices.length; word++)
                if (((confirmed[word] ^ other.confirmed[word]) & choices[word]) != 0) return false;
            return true;
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

        boolean updateProgress(long[] mask) {
            dirty = true;
            hasHatches = false;
            hasChoices = false;
            boolean choicesChanged = false;
            for (int i = 0; i < hatch.length; i++) {
                if ((hatch[i] & ~mask[i]) != 0) hasHatches = true;
                if (confirmed != null && ((confirmed[i] ^ mask[i]) & hatch[i]) != 0) marksDirty = true;
                if (choices != null) {
                    if ((choices[i] & ~mask[i]) != 0) hasChoices = true;
                    if (confirmed != null && ((confirmed[i] ^ mask[i]) & choices[i]) != 0) choicesChanged = true;
                }
            }
            if (choicesChanged) outlineDirty = true;
            confirmed = mask;
            if (marksDirty && marks != 0) {
                GL11.glDeleteLists(marks, 1);
                marks = 0;
            }
            hasShell = false;
            for (int i = 0; i < solid.length; i++) {
                if (((solid[i] | optional[i]) & ~confirmed[i]) != 0) {
                    hasShell = true;
                    break;
                }
            }
            if (!hasShell) clearFaces();
            if (!hasChoices && outline != 0) {
                GL11.glDeleteLists(outline, 1);
                outline = 0;
            }
            return choicesChanged;
        }

        void clear() {
            clearFaces();
            if (marks != 0) GL11.glDeleteLists(marks, 1);
            if (outline != 0) GL11.glDeleteLists(outline, 1);
        }
    }

    @Override
    public void register() {
        instance = this;
        IResourceManager resources = Minecraft.getMinecraft().getResourceManager();
        if (resources instanceof IReloadableResourceManager)
            ((IReloadableResourceManager) resources).registerReloadListener(reloaded -> {
                HatchLabels.clear();
                for (View view : views.values()) view.invalidateLabels();
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
        for (View view : views.values()) for (Section section : view.sections.values()) {
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
        transparentSections.clear();
        texturedOutlines.clear();
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        reloadPreviewTextures();
        if (unload) {
            BlockPreviewTextures.clear();
            PreviewConnectedTextures.clear();
            transparentSections.clear();
            texturedOutlines.clear();
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
                View v = views.computeIfAbsent(p.id, View::new);
                v.dim = p.dim;
                boolean controllerOn = (p.layers & 1) != 0;
                if ((v.controller != null) != controllerOn) {
                    Section at = v.sections.get(new ChunkPosition(p.x >> 4, p.y >> 4, p.z >> 4));
                    if (at != null) at.dirty = true;
                }
                v.controller = controllerOn ? new ChunkCoordinates(p.x, p.y, p.z) : null;
                v.controllerCheckTick = Long.MIN_VALUE;
                v.issue = p.issue;
                continue;
            }
            if (p.type == 1) {
                View view = views.computeIfAbsent(p.id, View::new);
                if (p.epoch < view.epoch) continue;
                if (p.reset) view.clear();
                if (view.epoch != p.epoch) view.fragments.clear();
                view.epoch = p.epoch;
                view.revision = p.revision;
                view.editable = p.editable;
                if (view.textured != p.textured)
                    for (Section s : view.sections.values()) {
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
                    if (section.updateProgress(p.confirmed)) invalidateOutlineNeighbors(view, p.x, p.y, p.z);
                    for (Section near : section.near) if (near != null) near.dirty = true;
                    invalidateTextureNeighbors(view, p.x, p.y, p.z);
                }
                continue;
            }
            if (p.type != 2 && p.type != 7) continue;
            Section s = new Section(view, p);
            Section old = view.sections.put(pos, s);
            invalidateTextureNeighbors(view, p.x, p.y, p.z);
            int changedBorders = changedHatchBorders(old, s);
            boolean sameChoices = s.hasChoices && old != null && s.sameChoices(old);
            if (!sameChoices && (s.hasChoices || old != null && old.hasChoices)) {
                invalidateOutlineNeighbors(view, p.x, p.y, p.z);
            }
            if (old != null) {
                // A replacement projection must not punch a hole while waiting for its paced mesh rebuild.
                if (s.hasChoices) {
                    s.outline = old.outline;
                    s.outlineDirty = !sameChoices || old.outlineDirty;
                    old.outline = 0;
                }
                if (s.hasShell) {
                    s.faces = old.faces;
                    s.glassFaces = old.glassFaces;
                    s.facesTextured = old.facesTextured;
                    old.faces = old.glassFaces = 0;
                }
                if (Arrays.equals(old.hatch, s.hatch) && Arrays.equals(old.dots, s.dots) && s.hatchTypes.sameAs(old.hatchTypes)
                        && Arrays.equals(old.confirmed, s.confirmed)) {
                    s.marks = old.marks;
                    s.marksDirty = old.marksDirty;
                    s.labels = old.labels;
                    s.markerRoles = old.markerRoles;
                    old.marks = 0;
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
                    if ((changedBorders & (1 << side)) != 0) near.marksDirty = true;
                }
            }
        }
        // Keep highlighting the visible material between the revision header and its section payload.
        aimed = pick(Minecraft.getMinecraft().thePlayer, false);
        updateAimedMarker();
    }

    private static void invalidateOutlineNeighbors(View view, int x, int y, int z) {
        // A grid edge can belong to any of its four touching cells, including across a section diagonal.
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 2 || (dx | dy | dz) == 0) continue;
            Section near = view.sections.get(new ChunkPosition(x + dx, y + dy, z + dz));
            if (near != null && near.hasChoices) near.outlineDirty = true;
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

    private static int changedHatchBorders(Section old, Section current) {
        int sides = 0;
        for (int word = 0; word < current.hatch.length && sides != 63; word++) {
            long changed = current.hatch[word] ^ (old == null ? 0L : old.hatch[word]);
            while (changed != 0) {
                int i = word * 64 + Long.numberOfTrailingZeros(changed);
                int x = i & 15, y = i >> 8, z = i >> 4 & 15;
                if (x == 0) sides |= 1;
                if (x == 15) sides |= 2;
                if (y == 0) sides |= 4;
                if (y == 15) sides |= 8;
                if (z == 0) sides |= 16;
                if (z == 15) sides |= 32;
                changed &= changed - 1;
            }
        }
        return sides;
    }

    private void updateAimedMarker() {
        aimedMarker = null;
        aimedHint = null;
        aimedHatch = false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.currentScreen != null || views.isEmpty()) return;
        if (aimed != null) {
            PreviewStream.Style style = aimed.style;
            String material = style.air ? StatCollector.translateToLocal("structurebuildernh.previewAir")
                    : materialName(style);
            if (style.conflict) material += " · " + StatCollector.translateToLocal("structurebuildernh.previewConflict");
            aimedMarker = material;
            aimedColor = materialColor(style);
            if (mc.gameSettings.keyBindSneak.getKeyCode() != 0) {
                aimedHint = previewModifierDown()
                        ? StatCollector.translateToLocal("structurebuildernh."
                                + (style.groupSelectable ? "previewPickHint" : "previewPickSingleHint"))
                        : StatCollector.translateToLocalFormatted("structurebuildernh.previewChooseHint",
                                PreviewTooltip.modifierName(mc));
            }
            return;
        }
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
                if ((s.hatch[i >> 6] & bit) == 0 || (s.confirmed[i >> 6] & bit) != 0) continue;
                int rank = Long.bitCount(s.hatch[i >> 6] & (bit - 1));
                for (int word = 0; word < (i >> 6); word++) rank += Long.bitCount(s.hatch[word]);
                HatchInfo label = s.hatchInfo(v, rank);
                aimedMarker = StatCollector.translateToLocal("structurebuildernh.markerHatch");
                aimedHatch = true;
                aimedColor = label.role.color;
                aimedHint = label.text;
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
        tooltip.draw(mc, e.resolution, aimedMarker, aimedHint, aimedColor, aimedHatch);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void render(RenderWorldLastEvent e) {
        reloadPreviewTextures();
        if (views.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity eye = mc.renderViewEntity;
        if (eye == null) return;
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
            texturedOutlines.clear();
            visibleControllers.clear();
            View nextView = null;
            Section nextSection = null;
            ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
            boolean terminal = held != null && held.getItem() == BuilderItem.ITEM;
            String tool = terminal && held.hasTagCompound() ? held.getTagCompound().getString("tool") : "";
            for (View view : views.values()) {
                if (eye.dimension != view.dim) continue;

                if (view.box != null && !camera.isBoundingBoxInFrustum(view.box)) continue;
                if (view.controller != null && (view.layers & PreviewStream.HATCH) != 0
                        && showController(view, eye.worldObj)) visibleControllers.add(view.controller);
                if (view.box == null) continue;
                if ((view.layers & PreviewStream.BOX) != 0) drawBounds(view.bounds);
                if ((view.layers & (PreviewStream.SHELL | PreviewStream.HATCH)) == 0) continue;
                Section candidate = drawSections(view, camera, view.editable && tool.equals(view.tool));
                if (candidate != null && (nextView == null || view.lastCompilation < nextView.lastCompilation)) {
                    nextView = view;
                    nextSection = candidate;
                }
            }
            drawTransparentSections();
            drawTexturedOutlines();
            drawMarkers(terminal);
            if (terminal && aimed != null) drawPick(aimed);
            for (View view : views.values())
                if (eye.dimension == view.dim && view.issue != null) drawIssue(view.issue);
            if (nextView != null) {
                nextView.lastCompilation = ++compilations;
                nextSection.lastCompilation = compilations;
                if ((nextView.layers & PreviewStream.HATCH) != 0 && nextSection.hasHatches && nextSection.marksDirty) {
                    nextSection.marks = compileHatches(nextView, nextSection);
                    nextSection.marksDirty = nextSection.marks == 0;
                } else if (nextSection.dirty && nextSection.hasShell && (nextView.layers & PreviewStream.SHELL) != 0) {
                    boolean textured = nextView.textured && nextSection.textured;
                    nextSection.faces = textured ? compileTexturedShell(nextView, nextSection, nextSection.faces)
                            : compileShell(nextView, nextSection, nextSection.faces);
                    if (!textured && nextSection.faces != 0 && nextSection.glassFaces != 0) {
                        GL11.glDeleteLists(nextSection.glassFaces, 1);
                        nextSection.glassFaces = 0;
                    }
                    if (nextSection.faces != 0) nextSection.facesTextured = textured;
                    nextSection.dirty = nextSection.faces == 0;
                } else {
                    nextSection.outline = compileOutline(nextView, nextSection);
                    nextSection.outlineDirty = nextSection.outline == 0;
                }
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void drawMarkers(boolean held) {
        if (visibleHatches.isEmpty() && visibleControllers.isEmpty()) return;
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(EXTBlendColor.GL_CONSTANT_ALPHA_EXT, EXTBlendColor.GL_ONE_MINUS_CONSTANT_ALPHA_EXT);
        if (held) {
            // The terminal can reveal buried markers. Other held items use only the normal translucent pass.
            GL11.glDepthFunc(GL11.GL_GREATER);
            GL14.glBlendColor(0, 0, 0, .20f);
            drawVisibleCells();
            GL11.glDisable(GL11.GL_BLEND);
        } else {
            GL14.glBlendColor(0, 0, 0, SHELL_ALPHA / 255f);
        }
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(held);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        TextureManager textures = Minecraft.getMinecraft().getTextureManager();
        if (textures.getTexture(MARKER_GRID) == null) textures.loadTexture(MARKER_GRID, new MarkerGridTexture());
        textures.bindTexture(MARKER_GRID);
        // Full-size markers can coincide with a real machine before the build scan confirms that position.
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(1f, 1f);
        drawVisibleCells();
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDepthMask(false);
    }

    private static boolean confirmed(View view, ChunkCoordinates position) {
        Section section = view.sections.get(new ChunkPosition(position.posX >> 4, position.posY >> 4, position.posZ >> 4));
        if (section == null) return false;
        int i = (position.posX & 15) | ((position.posZ & 15) << 4) | ((position.posY & 15) << 8);
        return (section.confirmed[i >> 6] & (1L << (i & 63))) != 0;
    }

    private static boolean showController(View view, World world) {
        long tick = world.getTotalWorldTime();
        if (view.controllerCheckTick != tick) {
            view.controllerCheckTick = tick;
            ChunkCoordinates p = view.controller;
            // Machine fronts can have recessed geometry; preserve the real face even before the task scans it.
            view.showController = !confirmed(view, p) && !(world.blockExists(p.posX, p.posY, p.posZ)
                    && world.getBlock(p.posX, p.posY, p.posZ) == GregTechAPI.sBlockMachines);
        }
        return view.showController;
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
            for (ChunkCoordinates p : visibleControllers) {
                for (int side = 0; side < 6; side++) {
                    int axis = side / 2;
                    int plane = axis == 0 ? p.posX : axis == 1 ? p.posY : p.posZ;
                    int u = axis == 0 ? p.posY : axis == 1 ? p.posZ : p.posX;
                    int v = axis == 0 ? p.posZ : axis == 1 ? p.posX : p.posY;
                    markerFace(t, side, plane + (side & 1), u, v, 1, 1, HOST_COLOR);
                }
            }
            t.draw();
        }
    }

    private static final class MarkerGridTexture extends AbstractTexture {
        @Override
        public void loadTexture(IResourceManager resources) {
            // One opaque tile per voxel. Mipmaps fade the thin grid in the distance.
            int size = 32, levels = 5;
            int[][] pixels = new int[levels + 1][];
            pixels[0] = new int[size * size];
            Arrays.fill(pixels[0], 0xffffffff);
            for (int i = 0; i < size; i++) {
                pixels[0][i] = 0xffbbbbbb;
                pixels[0][i * size] = 0xffbbbbbb;
            }
            TextureUtil.allocateTextureImpl(getGlTextureId(), levels, size, size, 1f);
            TextureUtil.uploadTextureMipmap(TextureUtil.generateMipmapData(levels, size, pixels),
                    size, size, 0, 0, true, false);
        }
    }

    private static void markerFace(Tessellator t, int side, int plane, int u, int v, int width, int height, int color) {
        int axis = side / 2;
        boolean positive = (side & 1) != 0;
        t.setColorRGBA_I(shade(color, axis == 0 ? 82 : axis == 1 && positive ? 100 : 69), 255);
        t.setTextureUV(0, 0);
        vertex(t, axis, plane, u, v);
        t.setTextureUV(positive ? width : 0, positive ? 0 : height);
        vertex(t, axis, plane, positive ? u + width : u, positive ? v : v + height);
        t.setTextureUV(width, height);
        vertex(t, axis, plane, u + width, v + height);
        t.setTextureUV(positive ? 0 : width, positive ? height : 0);
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

    private Section drawSections(View view, Frustrum camera, boolean editable) {
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
            boolean outline = editable && shell && s.hasChoices;
            if (hatches && s.marks != 0) visibleHatches.add(s);
            if ((shell && s.dirty || hatches && s.marksDirty || outline && s.outlineDirty)
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
                s.sortDistance = dx * dx + dy * dy + dz * dz;
                transparentSections.add(s);
            }
            if (outline && s.outline != 0 && s.facesTextured) texturedOutlines.add(s);
            if (outline && s.outline != 0 && !s.facesTextured) {
                GL11.glDisable(GL11.GL_LINE_STIPPLE);
                GL11.glLineWidth(1.5f);
                GL11.glColor4f(.90f, .95f, .96f, .90f);
                GL11.glCallList(s.outline);
            }
            GL11.glPopMatrix();
        }
        return candidate;
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
        for (Section section : transparentSections) {
            GL11.glPushMatrix();
            GL11.glTranslatef(section.x, section.y, section.z);
            GL11.glCallList(section.glassFaces);
            GL11.glPopMatrix();
        }
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    private void drawTexturedOutlines() {
        if (texturedOutlines.isEmpty()) return;
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LINE_STIPPLE);
        GL11.glLineWidth(1.5f);
        GL11.glColor4f(.90f, .95f, .96f, .90f);
        for (Section section : texturedOutlines) {
            GL11.glPushMatrix();
            GL11.glTranslatef(section.x, section.y, section.z);
            GL11.glCallList(section.outline);
            GL11.glPopMatrix();
        }
    }

    private static int compileHatches(View view, Section s) {
        int list = s.marks != 0 ? s.marks : GL11.glGenLists(1);
        if (list == 0) return 0;
        GL11.glNewList(list, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        HatchMesh.emit(s.hatch, s.confirmed, s.markerRoles(view), (side, slot) -> {
            Section neighbor = s.near[side];
            return neighbor != null && (neighbor.hatch[slot >> 6] & (1L << (slot & 63))) != 0;
        }, (side, plane, u, v, width, height, role) ->
                markerFace(t, side, plane, u, v, width, height, HATCH_ROLES[role].color));
        t.draw();
        GL11.glEndList();
        return list;
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
            if (((adjacent.solid[neighbor >> 6] | adjacent.optional[neighbor >> 6]) & bit) == 0) return false;
            // Installed blocks still occlude. Glass keeps the opaque faces behind it visible.
            return BlockPreviewTextures.occludes(adjacent.appearance(neighbor), current);
        };
        long[][][] deferred = new long[2][][];
        GL11.glNewList(list, GL11.GL_COMPILE);
        try {
            Tessellator tessellator = Tessellator.instance;
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            tessellator.startDrawingQuads();
            tessellator.setBrightness(0xf000f0);
            ProjectedBlockFaces.emit(s.solid, s.optional, s.confirmed, s.hatch, host, roles, boundary, (side, slot) -> {
                BlockPreviewTextures.Appearance appearance = s.appearance(slot);
                if (!appearance.textured()) deferred[0] = ProjectedBlockFaces.remember(deferred[0], side, slot);
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
            if ((source.confirmed[slot >> 6] & (1L << (slot & 63))) != 0 && world != null
                    && world.provider.dimensionId == view.dim) {
                try {
                    if (world.blockExists(x, y, z)) {
                        Block installed = world.getBlock(x, y, z);
                        if (installed != Blocks.air)
                            return PreviewBlockAccess.state(installed, world.getBlockMetadata(x, y, z));
                    }
                } catch (RuntimeException | LinkageError unavailable) {
                    // An unavailable native client chunk never replaces a proved packet identity with air.
                }
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
                        // Confirmed blocks still occlude their neighbours, including across section edges.
                        long bit = 1L << (i & 63);
                        if ((s.confirmed[i >> 6] & bit) != 0 || ((s.solid[i >> 6] | s.optional[i >> 6]) & bit) == 0)
                            continue;
                        // The role mesh supplies this face. Keeping it in both layers would blend it twice.
                        if (roleLayer && (i == host || (s.hatch[i >> 6] & (1L << (i & 63))) != 0)) continue;
                        int n = plane + dir;
                        Section next = n < 0 || n > 15 ? s.near[side] : s;
                        int neighbor = slot(axis, n & 15, u, v);
                        // Render the editable region's exterior, including deliberately empty choices.
                        // Faces between projected options share a plane and need no interior fill.
                        long choice = s.choice(i);
                        if (next != null && (next.choice(neighbor) != 0
                                || solid(next, neighbor) && (choice == 0
                                    || (next.confirmed[neighbor >> 6] & (1L << (neighbor & 63))) != 0))) continue;
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

    private static int compileOutline(View view, Section s) {
        int list = s.outline == 0 ? GL11.glGenLists(1) : s.outline;
        if (list == 0) return 0;
        // Resolve the halo once. An edge can touch a diagonal section even if both face neighbours are empty.
        Section[] halo = new Section[27];
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            halo[13 + dx + 3 * dy + 9 * dz] = view.sections.get(
                    new ChunkPosition((s.x >> 4) + dx, (s.y >> 4) + dy, (s.z >> 4) + dz));
        }
        GL11.glNewList(list, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        ChoiceOutline.emit(s.choices, (x, y, z) -> {
            Section neighbor = halo[13 + (x >> 4) + 3 * (y >> 4) + 9 * (z >> 4)];
            return neighbor == null ? 0 : neighbor.choice((x & 15) | ((z & 15) << 4) | ((y & 15) << 8));
        }, (axis, x, y, z, length) -> {
            t.addVertex(x, y, z);
            t.addVertex(x + (axis == 0 ? length : 0), y + (axis == 1 ? length : 0), z + (axis == 2 ? length : 0));
        });
        t.draw();
        GL11.glEndList();
        return list;
    }

    private static void drawPick(Pick pick) {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-1f, -1f);
        GL11.glPushMatrix();
        GL11.glTranslated(pick.x, pick.y, pick.z);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        int color = materialColor(pick.style);
        for (int side = 0; side < 6; side++) markerFace(t, side, side & 1, 0, 0, 1, 1, color);
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
