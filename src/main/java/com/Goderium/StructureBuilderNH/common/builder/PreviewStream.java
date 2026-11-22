package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.config.RebuildConfig;
import com.Goderium.StructureBuilderNH.util.Bounds;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.*;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.BlockDecorativeFrame;
import gregtech.common.blocks.BlockFrameBox;
import gregtech.common.blocks.ItemMachines;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** A paced stream of compact section masks, independent of the lifetime of the item GUI. */
public final class PreviewStream {
    public static final int BOX = 1, SHELL = 2, HATCH = 4;
    public static final int FOOTING_ROWS = 10;
    public static final int BLOCK_NAME = 11;
    /** A committed drop. The revision and epoch identify its upcoming replacement projection. */
    public static final int MOVE_ACCEPTED = 8;
    /** All initial geometry for this drop's revision and current epoch has been sent. */
    public static final int MOVE_READY = 9;
    private static final SimpleNetworkWrapper NET =
            NetworkRegistry.INSTANCE.newSimpleChannel("sbnh.preview9");
    private static final int FRAGMENT_BYTES = 28000, MAX_SECTION_BYTES = 524288;
    private static final int MAX_FRAME_META = BlockFrameBox.MATERIAL_MASK | BlockFrameBox.MTE_BIT;
    private static final Map<String, PreviewStream> views = new HashMap<>();
    private static final Map<EntityPlayerMP, NameRequest> nameRequests = new java.util.concurrent.ConcurrentHashMap<>();
    private final UUID taskId;
    private final BuildTask task;
    private final EntityPlayerMP viewer;
    private Blueprint sent;
    private Bounds bounds;
    private PreviewFooting footingScan;
    private int footingFace, footingPage;
    private int cursor, lastLayers, lastAmbiguousDots;
    private int revision = -1, epoch;
    private Long moveSequence;
    private int moveRevision;
    private boolean lastEditable, lastTextured, lastLocateHatches;
    private final Map<MaterialChoice, Integer> roles = new IdentityHashMap<>();
    private final Deque<Packet> outbound = new ArrayDeque<>();
    private int transfer, inFlight = -1;
    private boolean inFlightProgress;
    private ChunkCoordinates lastIssue;
    private boolean lastController;
    private boolean lastRepairable;
    private int lastPose=-1, lastPoseRevision=-1, lastX, lastY, lastZ;
    private int lastDragMinDistance, lastDragMaxDistance;
    private final Set<Integer> changedSections = new LinkedHashSet<>();
    private final Set<Integer> materialSections = new LinkedHashSet<>(), materialRefresh = new LinkedHashSet<>();
    private final Set<Integer> renderRefresh = new LinkedHashSet<>();
    private final BitSet projectedSections = new BitSet();
    private final Map<ChunkPosition, Integer> siteSections = new HashMap<>();
    private static final long[] NO_INSTALLED_HATCHES = new long[0];
    private final Map<Integer, long[]> installedHatches = new HashMap<>();
    private boolean preferUpdate = true, preferRender;

    private PreviewStream(BuildTask task, EntityPlayerMP viewer) {
        this.taskId = UUID.fromString(task.id);
        this.task = task;
        this.viewer = viewer;
    }

    public static void register() {
        NET.registerMessage(Handler.class, Packet.class, 0, Side.CLIENT);
        NET.registerMessage(NameHandler.class, NameRequest.class, 1, Side.SERVER);
        MinecraftForge.EVENT_BUS.register(new ChunkWatcher());
    }

    /** The color-only stream never needs a render palette merely to name one targeted voxel. */
    public static final class NameRequest implements IMessage {
        public UUID id;
        public int epoch, revision, x, y, z;

        public void toBytes(ByteBuf b) {
            b.writeLong(id.getMostSignificantBits()); b.writeLong(id.getLeastSignificantBits());
            b.writeInt(epoch); b.writeInt(revision); b.writeInt(x); b.writeInt(y); b.writeInt(z);
        }

        public void fromBytes(ByteBuf b) {
            id = new UUID(b.readLong(), b.readLong());
            epoch = b.readInt(); revision = b.readInt(); x = b.readInt(); y = b.readInt(); z = b.readInt();
        }

        public void send() { NET.sendToServer(this); }
    }

    public static final class NameHandler implements IMessageHandler<NameRequest, IMessage> {
        public IMessage onMessage(NameRequest message, MessageContext context) {
            nameRequests.put(context.getServerHandler().playerEntity, message);
            return null;
        }
    }

    private Packet name(NameRequest request) {
        if (task.plan == null || sent != task.plan || epoch != request.epoch || revision != request.revision
                || revision != task.revision || (task.previewLayers & (SHELL | HATCH)) == 0
                || viewer.dimension != task.dim || viewer.isDead
                || viewer.getDistanceSq(request.x + .5, request.y + .5, request.z + .5) > 16 * 16) return null;
        Blueprint.Cell cell = sent.cellAt(request.x, request.y, request.z);
        if (cell == null) return null;
        Packet reply = packet(BLOCK_NAME);
        reply.x = request.x; reply.y = request.y; reply.z = request.z;
        BuildTask.MaterialSelection selected = task.cachedSelection(reply.x, reply.y, reply.z);
        ItemStack item = cell.item;
        Block block = cell.block;
        int meta = cell.meta;
        if (selected != null && selected.supported && selected.group != null) {
            // The client only requests a projected (empty-world) position. Use the selected plan identity.
            item = selected.group.optionItem(selected.index);
            block = selected.group.optionBlock(selected.index);
            meta = selected.group.optionMeta(selected.index);
        }
        if (block != null && block != Blocks.air && block != com.gtnewhorizon.structurelib.StructureLibAPI.getBlockHint()) {
            reply.nameBlock = Block.getIdFromBlock(block);
            reply.nameMeta = meta;
        }
        if (item != null && item.getItem() != null) {
            reply.nameItem = Item.getIdFromItem(item.getItem());
            reply.nameDamage = item.getMetadata();
        }
        return reply;
    }

    private Packet packet(int type) {
        Packet p = new Packet();
        p.id = taskId;
        p.type = type;
        p.dim = viewer.dimension;
        p.epoch = epoch;
        p.revision = revision;
        return p;
    }

    static void stop(BuildTask task, EntityPlayerMP player) {
        PreviewStream view = views.remove(task.id);
        if (view != null && view.viewer == player) NET.sendTo(view.packet(0), player);
    }

    private static final class Budget {
        int scans = 128, sections = 8, bytes = 98304;
        int footingWords = 512, footingBytes = 8192;
    }

    static void acceptMove(BuildTask task, EntityPlayerMP player, long sequence) {
        PreviewStream view = views.get(task.id);
        if (view == null || view.viewer != player) {
            view = new PreviewStream(task, player);
            views.put(task.id, view);
        }
        view.moveSequence = sequence;
        view.moveRevision = task.revision;
        Packet accepted = view.packet(MOVE_ACCEPTED);
        accepted.sequence = sequence;
        accepted.revision = task.revision;
        accepted.epoch = view.epoch + 1;
        accepted.pose = task.facing.getIndex();
        NET.sendTo(accepted, player);
    }

    static void changed(BuildTask task, Blueprint.Section section) {
        PreviewStream view = views.get(task.id);
        if (view != null && view.sent == task.plan && section.id < view.cursor)
            view.changedSections.add(section.id);
    }

    public static void worldChanged(int dim, int x, int y, int z) {
        if (views.isEmpty()) return;
        ChunkPosition position = new ChunkPosition(x >> 4, y >> 4, z >> 4);
        for (PreviewStream view : views.values()) {
            if (view.task.dim != dim || view.sent == null || view.sent != view.task.plan) continue;
            Integer section = view.siteSections.get(position);
            if (section == null) continue;
            int slot = (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
            if ((view.sent.sections.get(section).hatch[slot >> 6] & (1L << (slot & 63))) != 0) {
                view.installedHatches.remove(section);
                view.changedSections.add(section);
            }
            if (!view.materialSections.contains(section)) continue;
            BuildTask.MaterialSelection selection = view.task.selection(x, y, z);
            if (selection != null && selection.supported) view.materialRefresh.add(section);
        }
    }

    public static final class ChunkWatcher {
        @SubscribeEvent public void load(ChunkEvent.Load event) { refresh(event, true); }
        @SubscribeEvent public void unload(ChunkEvent.Unload event) { refresh(event, false); }

        private static void refresh(ChunkEvent event, boolean loading) {
            if (views.isEmpty() || !(event.world instanceof WorldServer)) return;
            int x = event.getChunk().xPosition, z = event.getChunk().zPosition;
            for (PreviewStream view : views.values()) {
                if (view.siteSections.isEmpty() || view.task.dim != event.world.provider.dimensionId
                        || view.sent == null || view.sent != view.task.plan) continue;
                for (int y = 0; y < event.getChunk().getBlockStorageArray().length; y++) {
                    Integer id = view.siteSections.get(new ChunkPosition(x, y, z));
                    if (id == null || empty(view.sent.sections.get(id).hatch)) continue;
                    if (loading) view.installedHatches.remove(id);
                    else view.installedHatches.put(id, NO_INSTALLED_HATCHES);
                    view.changedSections.add(id);
                }
            }
        }
    }

    static void tick(
            List<BuildTask> tasks,
            Map<String, EntityPlayerMP> players,
            Map<String, ItemStack> tools) {
        Map<String, Budget> budgets = new HashMap<>();
        // BuilderTasks rotates this list each tick. All previews of one player share the send
        // budget.
        for (BuildTask task : tasks) {
            EntityPlayerMP p = players.get(task.owner);
            ChunkCoordinates issue =
                    task.issue != null
                                    && (task.state == BuildTask.State.BLOCKED
                                            || task.state == BuildTask.State.TEARDOWN_PAUSED
                                            || "harvestTool".equals(task.reason)
                                            || "occupied".equals(task.reason)
                                            || "unloaded".equals(task.reason)
                                            || "denied".equals(task.reason))
                            ? task.issue
                            : null;
            PreviewStream view = views.get(task.id);
            if (view != null && view.moveSequence != null
                    && (view.moveRevision != task.revision || (task.previewLayers & (SHELL | HATCH)) == 0
                            || task.plan == null && !task.canCompletePreviewMove())) {
                // End the accepted handoff even when an issue marker or a box still needs a new view.
                stop(task, p);
                view = null;
            }
            boolean waitingForMovePlan = view != null && view.viewer == p && view.moveSequence != null;
            if ((task.previewLayers == 0 || task.plan == null && !waitingForMovePlan) && issue == null
                    || p == null
                    || p.isDead
                    || p.dimension != task.dim
                    || !tools.containsKey(task.owner + task.tool)) {
                stop(task, p);
                continue;
            }
            if (view == null || view.viewer != p) {
                view = new PreviewStream(task, p);
                views.put(task.id, view);
            }
            // Keep the captured projection stable; queued site updates resume on release.
            if (waitingForMovePlan && task.plan == null) continue;
            boolean held = PreviewDragMessage.holding(p, task);
            if (!held) view.markers(task, issue);
            if (task.plan != null)
                view.send(task, budgets.computeIfAbsent(task.owner, k -> new Budget()), held);
        }
        nameRequests.forEach((player, request) -> {
            if (!nameRequests.remove(player, request)) return;
            PreviewStream view = views.get(request.id.toString());
            if (view != null && view.viewer == player) {
                Packet reply = view.name(request);
                if (reply != null) NET.sendTo(reply, player);
            }
        });
    }

    private void markers(BuildTask task, ChunkCoordinates issue) {
        boolean controller = (task.previewLayers & (HATCH | SHELL)) != 0;
        boolean repairable = issue != null && task.repairableIssue();
        int pose = controller && task.plan != null && task.plan.ready && task.movable() ? task.facing.getIndex() : -1;
        int dragMinDistance = RebuildConfig.dragMinDistance, dragMaxDistance = RebuildConfig.dragMaxDistance;
        if (lastController == controller && lastRepairable == repairable && Objects.equals(issue, lastIssue)
                && pose==lastPose && (pose < 0 || lastPoseRevision == task.revision
                        && lastDragMinDistance == dragMinDistance && lastDragMaxDistance == dragMaxDistance)
                && lastX==task.x && lastY==task.y && lastZ==task.z) return;
        Packet pkt = packet(4);
        pkt.layers = (controller ? 1 : 0) | (issue != null ? 2 : 0) | (repairable ? 4 : 0) | (pose>=0 ? 8 : 0);
        pkt.pose=pose;
        if (pose >= 0) {
            pkt.allowed = task.allowedPoses();
            pkt.dragMinDistance = dragMinDistance;
            pkt.dragMaxDistance = dragMaxDistance;
        }
        pkt.x = task.x;
        pkt.y = task.y;
        pkt.z = task.z;
        pkt.issue = issue;
        NET.sendTo(pkt, viewer);
        lastController = controller;
        lastRepairable = repairable;
        lastPose=pose; lastPoseRevision=task.revision; lastX=task.x; lastY=task.y; lastZ=task.z;
        lastDragMinDistance = dragMinDistance;
        lastDragMaxDistance = dragMaxDistance;
        lastIssue = issue;
    }

    private void sendFooting(Budget budget) {
        if (footingScan == null || !footingScan.ready()) return;
        while (footingFace < footingScan.faces.length) {
            PreviewFooting.Rows face = footingScan.faces[footingFace];
            if (footingPage == face.pages.size()) {
                face.pages.clear();
                footingFace++;
                footingPage = 0;
                continue;
            }
            int count = Math.min(PreviewFooting.PAGE_ROWS, face.size - footingPage * PreviewFooting.PAGE_ROWS);
            int bytes = 32 + count * 14;
            if (bytes > budget.bytes || bytes > budget.footingBytes) return;
            Packet page = packet(FOOTING_ROWS);
            page.footingFace = footingFace;
            page.footingFirst = footingPage == 0;
            page.footingLast = footingPage + 1 == face.pages.size();
            page.footingRows = face.pages.get(footingPage);
            page.footingCount = count;
            NET.sendTo(page, viewer);
            budget.bytes -= bytes;
            budget.footingBytes -= bytes;
            footingPage++;
        }
    }

    private void send(BuildTask task, Budget budget, boolean held) {
        Blueprint plan = task.plan;
        boolean editable = task.editable() && plan.ready;
        boolean locateHatches = task.state == BuildTask.State.PREVIEW && (task.previewLayers & HATCH) != 0;
        boolean textured = task.useTexturedWorldPreview();
        boolean reset = sent != plan, modeChanged = lastTextured != textured;
        boolean contentChanged = revision != task.revision;
        boolean refresh = reset || contentChanged || modeChanged;
        if (held && (refresh || lastLayers != task.previewLayers)) return;
        PreviewFooting oldFooting = footingScan;
        boolean hasBox = (task.previewLayers & BOX) != 0 && plan.ready;
        if (!hasBox || footingScan != null && (footingScan.plan != plan || footingScan.solidityRevision != plan.solidityRevision))
            footingScan = null;
        // Only the held terminal's selected task draws contacts, even if many other boxes are visible.
        ItemStack hand = hasBox ? viewer.getHeldItem() : null;
        boolean showFooting = hand != null && hand.getItem() == BuilderItem.ITEM && hand.hasTagCompound()
                && task.id.equals(hand.getTagCompound().getString("selected"))
                && task.tool.equals(hand.getTagCompound().getString("tool"));
        if (showFooting) {
            if (footingScan == null) footingScan = new PreviewFooting(plan);
            budget.footingWords -= footingScan.advance(budget.footingWords);
        }
        boolean footingChanged = footingScan != oldFooting;
        if (footingChanged) footingFace = footingPage = 0;
        if (held && !footingChanged) {
            if (showFooting) sendFooting(budget);
            return;
        }
        int ambiguous = plan.ready ? plan.ambiguousHatchDots() : 0;
        if (refresh || lastLayers != task.previewLayers || !plan.bounds.equals(bounds) || footingChanged
                || lastEditable != editable || lastAmbiguousDots != ambiguous || lastLocateHatches != locateHatches) {
            if (refresh) {
                if (!reset && inFlight >= 0) {
                    if (inFlightProgress) changedSections.add(inFlight);
                    else materialRefresh.add(inFlight);
                }
                outbound.clear();
                inFlight = -1;
                epoch++;
                revision = task.revision;
                if (reset) {
                    cursor = 0;
                    changedSections.clear();
                    materialSections.clear();
                    siteSections.clear();
                    installedHatches.clear();
                    materialRefresh.clear();
                    renderRefresh.clear();
                    projectedSections.clear();
                    roles.clear();
                } else if (contentChanged) {
                    // Keep the old meshes until each affected section is replaced. Aim gets first priority.
                    ChunkCoordinates aim = PreviewAim.first(viewer, (x, y, z) -> {
                        BuildTask.MaterialSelection s = task.selection(x, y, z);
                        return s != null && s.supported;
                    });
                    Set<Integer> pending = new LinkedHashSet<>();
                    if (aim != null)
                        for (Blueprint.Section section : plan.sections)
                            if (section.x == aim.posX >> 4 && section.y == aim.posY >> 4
                                    && section.z == aim.posZ >> 4 && materialSections.contains(section.id)) {
                                pending.add(section.id);
                                break;
                            }
                    pending.addAll(materialRefresh);
                    pending.addAll(materialSections);
                    materialRefresh.clear();
                    materialRefresh.addAll(pending);
                }
                if (modeChanged && !reset) {
                    renderRefresh.clear();
                    if (textured)
                        for (int i = projectedSections.nextSetBit(0); i >= 0;
                                i = projectedSections.nextSetBit(i + 1)) renderRefresh.add(i);
                }
            }
            Packet pkt = packet(1);
            pkt.reset = reset;
            pkt.editable = editable;
            pkt.locateHatches = locateHatches;
            pkt.tool = task.tool;
            pkt.layers = task.previewLayers;
            pkt.textured = textured;
            pkt.bounds = plan.bounds;
            pkt.footingReset = footingChanged || reset;
            pkt.controllerMeta = task.target.getMetadata();
            pkt.tier = task.tier;
            pkt.ambiguousDots = ambiguous;
            NET.sendTo(pkt, viewer);
            sent = plan;
            lastLayers = task.previewLayers;
            lastEditable = editable;
            lastLocateHatches = locateHatches;
            lastTextured = textured;
            lastAmbiguousDots = ambiguous;
            bounds = plan.bounds;
        }
        if (showFooting) sendFooting(budget);
        if (held || !plan.ready || (lastLayers & (SHELL | HATCH)) == 0) return;
        Iterator<Integer> updates = changedSections.iterator();
        while (budget.scans > 0 && budget.sections > 0 && budget.bytes > 0
                && (cursor < plan.sections.size() || updates.hasNext() || !materialRefresh.isEmpty()
                        || !renderRefresh.isEmpty() || !outbound.isEmpty())) {
            if (!outbound.isEmpty()) {
                Packet next = outbound.peek();
                if (next.wireBytes > budget.bytes) break;
                outbound.remove();
                NET.sendTo(next, viewer);
                budget.bytes -= next.wireBytes;
                budget.sections--;
                if (outbound.isEmpty()) inFlight = -1;
                continue;
            }
            // Progress can stay nonempty for many ticks. Reserve alternate sends for missing palettes.
            boolean render = !renderRefresh.isEmpty() && (preferRender || materialRefresh.isEmpty() && !updates.hasNext());
            boolean material = !render && !materialRefresh.isEmpty();
            boolean update = !render && !material && updates.hasNext() && (preferUpdate || cursor == plan.sections.size());
            Blueprint.Section section;
            if (material) {
                int id = materialRefresh.iterator().next();
                materialRefresh.remove(id);
                renderRefresh.remove(id);
                section = plan.sections.get(id);
            } else if (render) {
                int id = renderRefresh.iterator().next();
                renderRefresh.remove(id);
                materialRefresh.remove(id);
                section = plan.sections.get(id);
            } else if (update) {
                section = plan.sections.get(updates.next());
                updates.remove();
            } else section = plan.sections.get(cursor);
            preferUpdate = !update;
            preferRender = !render;
            budget.scans--;
            Packet pkt = update ? progress(section) : section(task, section);
            if (!update && !material && !render) cursor++;
            if (!update && empty(pkt.solid) && empty(pkt.optional) && empty(pkt.hatch)
                    && !projectedSections.get(section.id)) continue;
            if (!update) projectedSections.set(section.id);
            if (!update && !pkt.styles.isEmpty()) materialSections.add(section.id);
            if (!empty(section.hatch)) {
                long[] installed = installedHatches.computeIfAbsent(section.id, ignored -> inspectHatches(task.dim, section));
                if (installed != NO_INSTALLED_HATCHES) pkt.installedHatches = installed;
            }
            if (!update && (!pkt.styles.isEmpty() || pkt.dots.length != 0))
                siteSections.put(new ChunkPosition(section.x, section.y, section.z), section.id);
            inFlight = section.id;
            inFlightProgress = update;
            enqueue(pkt);
        }
        if (moveSequence != null && cursor == plan.sections.size() && outbound.isEmpty()
                && materialRefresh.isEmpty() && renderRefresh.isEmpty()) {
            Packet ready = packet(MOVE_READY);
            ready.sequence = moveSequence;
            NET.sendTo(ready, viewer);
            moveSequence = null;
        }
    }

    private static long[] inspectHatches(int dimension, Blueprint.Section section) {
        WorldServer world = DimensionManager.getWorld(dimension);
        if (world == null || !world.blockExists(section.x << 4, section.y << 4, section.z << 4))
            return NO_INSTALLED_HATCHES;
        long[] installed = NO_INSTALLED_HATCHES;
        for (Object entry : world.getChunkFromChunkCoords(section.x, section.z).chunkTileEntityMap.values()) {
            if (!(entry instanceof IGregTechTileEntity)) continue;
            TileEntity tile = (TileEntity) entry;
            if (tile.isInvalid() || tile.yCoord >> 4 != section.y) continue;
            int slot = (tile.xCoord & 15) | ((tile.zCoord & 15) << 4) | ((tile.yCoord & 15) << 8);
            long bit = 1L << (slot & 63);
            if ((section.hatch[slot >> 6] & bit) == 0
                    || !(((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEHatch)) continue;
            if (installed == NO_INSTALLED_HATCHES) installed = new long[64];
            installed[slot >> 6] |= bit;
        }
        return installed;
    }

    private void enqueue(Packet packet) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            packet.toBytes(encoded);
            int length = encoded.readableBytes();
            if (length <= FRAGMENT_BYTES) {
                packet.wireBytes = length;
                outbound.add(packet);
                return;
            }
            if (length > MAX_SECTION_BYTES) throw new IllegalStateException("Preview section exceeds projection bound");
            int id = ++transfer, count = (length + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES;
            for (int part = 0; part < count; part++) {
                Packet fragment = packet(6);
                fragment.transfer = id;
                fragment.part = part;
                fragment.parts = count;
                fragment.totalBytes = length;
                fragment.payload = new byte[Math.min(FRAGMENT_BYTES, encoded.readableBytes())];
                encoded.readBytes(fragment.payload);
                fragment.wireBytes = fragment.payload.length + 41;
                outbound.add(fragment);
            }
        } finally {
            encoded.release();
        }
    }

    static boolean visibleTo(BuildTask task, EntityPlayerMP player) {
        PreviewStream stream = views.get(task.id);
        return stream != null && stream.viewer == player;
    }

    private Packet progress(Blueprint.Section section) {
        Packet pkt = packet(3);
        pkt.x = section.x;
        pkt.y = section.y;
        pkt.z = section.z;
        pkt.confirmed = section.confirmed.clone();
        return pkt;
    }

    private Packet section(BuildTask task, Blueprint.Section section) {
        Packet pkt = progress(section);
        pkt.textured = task.useTexturedWorldPreview();
        pkt.type = pkt.textured ? 7 : 2;
        pkt.solid = section.solid.clone();
        pkt.hatch = section.hatch.clone();
        pkt.optional = new long[64];
        pkt.styles = new ArrayList<>();
        pkt.styleSlots = new short[4096];
        Map<String, Integer> palette = new HashMap<>();
        Map<Long, Integer> renderPalette = null;
        Map<Blueprint.Cell, RenderMaterial> fixedRenders = null;
        if (pkt.textured) {
            pkt.renders = new ArrayList<>();
            pkt.renderSlots = new short[4096];
            renderPalette = new HashMap<>();
            fixedRenders = new IdentityHashMap<>();
        }
        WorldServer world = DimensionManager.getWorld(task.dim);
        for (int i = 0; i < section.cells.length; i++) {
            Blueprint.Cell cell = section.cells[i];
            if (cell == null) continue;
            int x = section.x * 16 + (i & 15), y = section.y * 16 + (i >> 8),
                    z = section.z * 16 + (i >> 4 & 15);
            BuildTask.MaterialSelection selection = task.selection(x, y, z);
            boolean selectable = selection != null && selection.supported && selection.group != null;
            if (!selectable) {
                if (pkt.textured && (pkt.solid[i >> 6] & (1L << (i & 63))) != 0) {
                    if (!fixedRenders.containsKey(cell))
                        fixedRenders.put(cell, fixedRender(cell, cell.previewFront(task.facing.getDirection()).ordinal()));
                    paintRender(pkt, renderPalette, i, fixedRenders.get(cell));
                }
                continue;
            }
            MaterialChoice group = selection.group;
            boolean loaded = world != null && world.blockExists(x, y, z);
            int selected = loaded ? task.previewIndex(selection, world) : selection.index;
            ItemStack item = loaded ? task.previewItem(selection, world)
                    : group.isFluid(selected) ? null : group.optionItem(selected);
            Style style = new Style();
            style.group = roles.computeIfAbsent(group, ignored -> roles.size());
            style.item = item == null ? -1 : Item.getIdFromItem(item.getItem());
            style.damage = item == null ? 0 : item.getMetadata();
            style.option = selected;
            style.optionCount = group.optionCount();
            boolean fluid = group.isFluid(selected);
            style.air = item == null && !fluid;
            style.conflict = loaded ? task.previewConflict(selection, world) : selection.conflict;
            style.groupSelectable = group.groupSelectable();
            style.piece = selection.piece == null ? "" : selection.piece;
            style.symbol = selection.symbol;
            String key = style.group + "/" + selected + "/" + style.item + "/" + style.damage + "/" + style.conflict;
            Integer index = palette.get(key);
            if (index == null) {
                index = pkt.styles.size();
                palette.put(key, index);
                pkt.styles.add(style);
            }
            pkt.styleSlots[i] = (short) (index + 1);
            long bit = 1L << (i & 63);
            if (style.air) {
                pkt.solid[i >> 6] &= ~bit;
                pkt.optional[i >> 6] |= bit;
            } else {
                pkt.solid[i >> 6] |= bit;
                if (pkt.textured) {
                    RenderMaterial render = null;
                    if (loaded && !selection.explicit && !world.isAirBlock(x, y, z)
                            && task.plan.matches(cell, world, x, y, z)) {
                        TileEntity tile = world.getTileEntity(x, y, z);
                        IGregTechTileEntity gt = tile instanceof IGregTechTileEntity ? (IGregTechTileEntity) tile : null;
                        render = renderMaterial(world.getBlock(x, y, z), world.getBlockMetadata(x, y, z),
                                gt == null ? -1 : gt.getMetaTileID(), gt == null ? -1 : gt.getFrontFacing().ordinal());
                    }
                    if (render == null) render = fluid ? renderMaterial(group.optionBlock(selected), group.optionMeta(selected), -1, -1)
                            : itemRender(item, cell.previewFront(task.facing.getDirection()).ordinal());
                    paintRender(pkt, renderPalette, i, render);
                }
            }
        }
        int count = 0;
        for (long bits : pkt.hatch) count += Long.bitCount(bits);
        pkt.dots = new byte[count];
        pkt.hatchTypes = HatchTypes.collect(task.plan, section, pkt.dots);
        return pkt;
    }

    private static void paintRender(Packet packet, Map<Long, Integer> palette, int slot, RenderMaterial render) {
        if (render == null) return;
        long key = ((long) render.block << 35) | ((long) render.meta << 19)
                | ((long) (render.machine + 1) << 3) | (render.front + 1);
        Integer index = palette.get(key);
        if (index == null) {
            index = packet.renders.size();
            palette.put(key, index);
            packet.renders.add(render);
        }
        packet.renderSlots[slot] = (short) (index + 1);
    }

    private static RenderMaterial fixedRender(Blueprint.Cell cell, int front) {
        if (cell.item != null && cell.item.getItem() instanceof ItemMachines) return itemRender(cell.item, front);
        if (cell.block != null && cell.block != Blocks.air
                && cell.block != com.gtnewhorizon.structurelib.StructureLibAPI.getBlockHint())
            return renderMaterial(cell.block, cell.meta, -1, -1);
        return itemRender(cell.item, front);
    }

    private static RenderMaterial itemRender(ItemStack item, int front) {
        if (item == null || item.getItem() == null) return null;
        try {
            if (item.getItem() instanceof ItemMachines) {
                int machine = item.getMetadata();
                if (machine <= 0 || machine >= GregTechAPI.METATILEENTITIES.length
                        || GregTechAPI.METATILEENTITIES[machine] == null) return null;
                return renderMaterial(GregTechAPI.sBlockMachines,
                        GregTechAPI.METATILEENTITIES[machine].getTileEntityBaseType(), machine, front);
            }
            if (!(item.getItem() instanceof ItemBlock)) return null;
            return renderMaterial(Block.getBlockFromItem(item.getItem()),
                    item.getItem().getMetadata(item.getMetadata()), -1, -1);
        } catch (RuntimeException | LinkageError failure) {
            // Unknown placement conventions keep the existing color projection.
            return null;
        }
    }

    private static RenderMaterial renderMaterial(Block block, int meta, int machine, int front) {
        int maxMeta = block instanceof BlockFrameBox ? MAX_FRAME_META
                : block instanceof BlockDecorativeFrame ? Short.MAX_VALUE : 15;
        if (block == null || block == Blocks.air
                || block == com.gtnewhorizon.structurelib.StructureLibAPI.getBlockHint()
                || meta < 0 || meta > maxMeta
                || machine < -1 || machine > 32767) return null;
        int id = Block.getIdFromBlock(block);
        if (id <= 0 || id > 65535) return null;
        RenderMaterial render = new RenderMaterial();
        render.block = id;
        render.meta = meta;
        render.machine = machine;
        if (machine >= 0 && front >= 0 && front < 6) render.front = front;
        return render;
    }

    private static boolean empty(long[] mask) {
        for (long word : mask) if (word != 0) return false;
        return true;
    }

    static void clear() {
        views.clear();
        nameRequests.clear();
    }

    public static final class Style {
        public int item, damage, group, option, optionCount;
        public boolean air, conflict, groupSelectable;
        public String piece;
        public char symbol;
    }

    /** A projected placement identity. Zero slots deliberately retain the color fallback. */
    public static final class RenderMaterial {
        public int block, meta;
        public int machine = -1, front = -1;

        private void validate() {
            if (block <= 0 || block > 65535 || meta < 0 || meta > Short.MAX_VALUE || machine < -1
                    || machine > 32767 || front < -1 || front > 5 || (machine < 0 && front != -1))
                throw new IllegalArgumentException("Invalid preview render material");
        }
    }

    public static final class Packet implements IMessage {
        private static final long[] EMPTY_WORLD_MASK = new long[64];
        public UUID id;
        public int type, dim, layers, x, y, z, revision, epoch;
        /** Client grip sequence echoed by MOVE_ACCEPTED and MOVE_READY. */
        public long sequence;
        public int controllerMeta, tier, ambiguousDots, pose=-1;
        // A legacy pose frame limits the client to the original short pickup and drop range.
        public int dragMinDistance = RebuildConfig.DEFAULT_DRAG_MIN_DISTANCE, dragMaxDistance = PreviewAim.USE_REACH;
        public PreviewPose.Allowed allowed;
        public HatchTypes hatchTypes;
        public boolean reset, editable, textured, locateHatches;
        public String tool;
        public Bounds bounds;
        public boolean footingReset, footingFirst, footingLast;
        public int footingFace, footingCount;
        public int[] footingRows;
        public long[] solid, hatch, confirmed, optional, painted;
        /** Actual GT hatches at projected hatch slots. Independent of structure validation and GUI picking. */
        public long[] installedHatches;
        public List<Style> styles;
        public short[] styleSlots;
        public List<RenderMaterial> renders;
        public short[] renderSlots;
        /** One requested plan identity, localized by the viewer. */
        public int nameBlock, nameMeta, nameItem = -1, nameDamage;
        public byte[] dots;
        public int transfer, part, parts, totalBytes;
        public byte[] payload;
        private int wireBytes;
        public ChunkCoordinates issue;

        public void toBytes(ByteBuf b) {
            b.writeByte(type);
            b.writeLong(id.getMostSignificantBits());
            b.writeLong(id.getLeastSignificantBits());
            if (type == 0) return;
            b.writeInt(dim);
            b.writeInt(epoch);
            b.writeInt(revision);
            if (type == BLOCK_NAME) {
                b.writeInt(x); b.writeInt(y); b.writeInt(z);
                b.writeInt(nameBlock); b.writeInt(nameMeta); b.writeInt(nameItem); b.writeInt(nameDamage);
                return;
            }
            if (type == FOOTING_ROWS) {
                b.writeByte(footingFace | (footingFirst ? 8 : 0) | (footingLast ? 16 : 0));
                b.writeShort(footingCount);
                for (int i = 0; i < footingCount * 4; i += 4) {
                    b.writeInt(footingRows[i]);
                    b.writeInt(footingRows[i + 1]);
                    b.writeInt(footingRows[i + 2]);
                    b.writeShort(footingRows[i + 3]);
                }
                return;
            }
            if (type == MOVE_ACCEPTED || type == MOVE_READY) {
                b.writeLong(sequence);
                if (type == MOVE_ACCEPTED && pose >= 0) b.writeByte(pose);
                return;
            }
            if (type == 6) {
                b.writeInt(transfer);
                b.writeByte(part);
                b.writeByte(parts);
                b.writeInt(totalBytes);
                b.writeShort(payload.length);
                b.writeBytes(payload);
                return;
            }
            if (type == 4) {
                b.writeByte(layers);
                b.writeInt(x);
                b.writeInt(y);
                b.writeInt(z);
                if (issue != null) {
                    b.writeInt(issue.posX);
                    b.writeInt(issue.posY);
                    b.writeInt(issue.posZ);
                }
                if ((layers & 8) != 0) {
                    b.writeByte(pose);
                    b.writeLong(allowed.low);
                    b.writeLong(allowed.high);
                    b.writeInt(dragMinDistance);
                    b.writeInt(dragMaxDistance);
                }
                return;
            }
            if (type == 1) {
                b.writeByte((layers & 7) | (footingReset ? 32 : 0) | (locateHatches ? 64 : 0) | (textured ? 128 : 0));
                b.writeBoolean(reset);
                b.writeBoolean(editable);
                ByteBufUtils.writeUTF8String(b, tool);
                b.writeInt(bounds.minX);
                b.writeInt(bounds.minY);
                b.writeInt(bounds.minZ);
                b.writeInt(bounds.maxX);
                b.writeInt(bounds.maxY);
                b.writeInt(bounds.maxZ);
                b.writeInt(controllerMeta);
                b.writeInt(tier);
                b.writeInt(ambiguousDots);
            } else {
                b.writeInt(x);
                b.writeInt(y);
                b.writeInt(z);
                if (type == 2 || type == 7) {
                    writeMask(b, solid);
                    writeMask(b, hatch);
                    b.writeBytes(dots);
                    if (dots.length > 0) hatchTypes.write(b);
                    writeMask(b, optional);
                    b.writeShort(styles.size());
                    for (Style style : styles) {
                        b.writeInt(style.item);
                        b.writeInt(style.damage);
                        b.writeInt(style.group);
                        b.writeInt(style.option);
                        b.writeByte((style.air ? 1 : 0) | (style.conflict ? 2 : 0)
                                | (style.groupSelectable ? 4 : 0));
                        b.writeChar(style.symbol);
                        byte[] name = style.piece.getBytes(StandardCharsets.UTF_8);
                        int length = Math.min(64, name.length);
                        b.writeByte(length);
                        b.writeBytes(name, 0, length);
                    }
                    long[] painted = new long[64];
                    for (int i = 0; i < 4096; i++)
                        if (styleSlots[i] != 0) painted[i >> 6] |= 1L << (i & 63);
                    writeMask(b, painted);
                    // Runs follow only painted cells. Empty space costs just its compressed mask.
                    int previous = 0, run = 0;
                    for (short slot : styleSlots) {
                        int style = slot & 65535;
                        if (style == 0) continue;
                        if (run != 0 && previous != style) {
                            b.writeShort(run);
                            b.writeShort(previous);
                            run = 0;
                        }
                        previous = style;
                        run++;
                    }
                    if (run != 0) {
                        b.writeShort(run);
                        b.writeShort(previous);
                    }
                }
                writeMask(b, confirmed);
                if (type == 7) writeRenders(b);
                if (installedHatches != null) writeMask(b, installedHatches);
                if ((type == 2 || type == 7) && !styles.isEmpty()) {
                    // Keep old readers aligned through the optional installed-hatch mask.
                    if (installedHatches == null) writeMask(b, EMPTY_WORLD_MASK);
                    for (Style style : styles) ByteBufUtils.writeVarInt(b, style.optionCount, 5);
                }
            }
        }

        private void writeRenders(ByteBuf b) {
            int count = renders == null ? 0 : renders.size();
            if (count > 4096) throw new IllegalArgumentException("Too many preview render materials");
            b.writeShort(count);
            if (renders != null) for (RenderMaterial material : renders) {
                material.validate();
                b.writeInt(material.block);
                // Ordinary metadata still takes one byte. GT frames carry a material index and a tile flag.
                ByteBufUtils.writeVarInt(b, material.meta, 3);
                b.writeInt(material.machine < 0 ? -1 : ((material.front + 1) << 16) | material.machine);
            }
            long[] projected = new long[64];
            if (renderSlots != null) {
                if (renderSlots.length != 4096) throw new IllegalArgumentException("Invalid preview render slots");
                for (int i = 0; i < 4096; i++) if (renderSlots[i] != 0) {
                    if ((renderSlots[i] & 65535) > count || (solid[i >> 6] & (1L << (i & 63))) == 0)
                        throw new IllegalArgumentException("Invalid preview render index");
                    projected[i >> 6] |= 1L << (i & 63);
                }
            }
            writeMask(b, projected);
            int previous = 0, run = 0;
            if (renderSlots != null) for (short slot : renderSlots) {
                int material = slot & 65535;
                if (material == 0) continue;
                if (run != 0 && previous != material) {
                    b.writeShort(run);
                    b.writeShort(previous);
                    run = 0;
                }
                previous = material;
                run++;
            }
            if (run != 0) {
                b.writeShort(run);
                b.writeShort(previous);
            }
        }

        private void readRenders(ByteBuf b) {
            int count = b.readUnsignedShort();
            if (count > 4096) throw new IllegalArgumentException("Too many preview render materials");
            renders = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                RenderMaterial material = new RenderMaterial();
                material.block = b.readInt();
                material.meta = ByteBufUtils.readVarInt(b, 3);
                int machine = b.readInt();
                if (machine != -1) {
                    material.machine = machine & 0xffff;
                    material.front = (machine >>> 16) - 1;
                }
                material.validate();
                renders.add(material);
            }
            long[] projected = readMask(b, true);
            renderSlots = count == 0 ? null : new short[4096];
            int run = 0, material = 0;
            for (int word = 0; word < 64; word++) {
                if ((projected[word] & ~solid[word]) != 0)
                    throw new IllegalArgumentException("Preview render projects outside solids");
                for (long bits = projected[word]; bits != 0; bits &= bits - 1) {
                    if (run == 0) {
                        run = b.readUnsignedShort();
                        material = b.readUnsignedShort();
                        if (run == 0 || material == 0 || material > count)
                            throw new IllegalArgumentException("Invalid preview render run");
                    }
                    renderSlots[word * 64 + Long.numberOfTrailingZeros(bits)] = (short) material;
                    run--;
                }
            }
            if (run != 0) throw new IllegalArgumentException("Preview render run overflow");
        }

        private static void writeMask(ByteBuf b, long[] mask) {
            long present = 0;
            for (int i = 0; i < 64; i++) if (mask[i] != 0) present |= 1L << i;
            b.writeLong(present);
            while (present != 0) {
                int i = Long.numberOfTrailingZeros(present);
                b.writeLong(mask[i]);
                present &= present - 1;
            }
        }

        private static long[] readMask(ByteBuf b, boolean readOnly) {
            long present = b.readLong();
            // World payloads and the temporary render-slot mask are never mutated after decoding.
            if (present == 0 && readOnly) return EMPTY_WORLD_MASK;
            long[] mask = new long[64];
            while (present != 0) {
                int i = Long.numberOfTrailingZeros(present);
                mask[i] = b.readLong();
                present &= present - 1;
            }
            return mask;
        }

        public void fromBytes(ByteBuf b) {
            read(b, true);
        }

        void read(ByteBuf b, boolean worldProjection) {
            type = b.readUnsignedByte();
            textured = type == 7;
            id = new UUID(b.readLong(), b.readLong());
            if (type == 0) return;
            dim = b.readInt();
            epoch = b.readInt();
            revision = b.readInt();
            if (type == BLOCK_NAME) {
                x = b.readInt(); y = b.readInt(); z = b.readInt();
                nameBlock = b.readInt(); nameMeta = b.readInt(); nameItem = b.readInt(); nameDamage = b.readInt();
                return;
            }
            if (type == FOOTING_ROWS) {
                int flags = b.readUnsignedByte();
                footingFace = flags & 7;
                footingFirst = (flags & 8) != 0;
                footingLast = (flags & 16) != 0;
                footingCount = b.readUnsignedShort();
                if (footingFace >= 6 || footingCount == 0 || footingCount > PreviewFooting.PAGE_ROWS
                        || b.readableBytes() != footingCount * 14)
                    throw new IllegalArgumentException("Invalid footing page");
                footingRows = new int[footingCount * 4];
                for (int i = 0; i < footingRows.length; i += 4) {
                    footingRows[i] = b.readInt();
                    footingRows[i + 1] = b.readInt();
                    footingRows[i + 2] = b.readInt();
                    footingRows[i + 3] = b.readUnsignedShort();
                }
                return;
            }
            if (type == MOVE_ACCEPTED || type == MOVE_READY) {
                sequence = b.readLong();
                if (type == MOVE_ACCEPTED && b.isReadable()) pose = b.readUnsignedByte();
                return;
            }
            if (type == 6) {
                transfer = b.readInt();
                part = b.readUnsignedByte();
                parts = b.readUnsignedByte();
                totalBytes = b.readInt();
                int length = b.readUnsignedShort();
                if (parts == 0 || parts > 19 || part >= parts || totalBytes <= FRAGMENT_BYTES || totalBytes > MAX_SECTION_BYTES
                        || length > FRAGMENT_BYTES || length != Math.min(FRAGMENT_BYTES, totalBytes - part * FRAGMENT_BYTES)
                        || parts != (totalBytes + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES)
                    throw new IllegalArgumentException("Invalid preview fragment");
                payload = new byte[length];
                b.readBytes(payload);
                return;
            }
            if (type == 4) {
                layers = b.readUnsignedByte();
                x = b.readInt();
                y = b.readInt();
                z = b.readInt();
                if ((layers & 2) != 0)
                    issue = new ChunkCoordinates(b.readInt(), b.readInt(), b.readInt());
                if ((layers & 8) != 0) {
                    pose = b.readUnsignedByte();
                    allowed = new PreviewPose.Allowed(b.readLong(), b.readLong());
                    if (b.readableBytes() >= 2 * Integer.BYTES) {
                        dragMinDistance = b.readInt();
                        dragMaxDistance = b.readInt();
                    }
                }
                return;
            }
            if (type == 1) {
                int flags = b.readUnsignedByte();
                layers = flags & 7;
                locateHatches = (flags & 64) != 0;
                textured = (flags & 128) != 0;
                reset = b.readBoolean();
                editable = b.readBoolean();
                tool = ByteBufUtils.readUTF8String(b);
                bounds =
                        new Bounds(
                                b.readInt(),
                                b.readInt(),
                                b.readInt(),
                                b.readInt(),
                                b.readInt(),
                                b.readInt());
                controllerMeta = b.readInt();
                tier = b.readInt();
                ambiguousDots = b.readInt();
                footingReset = (flags & 32) != 0;
            } else {
                x = b.readInt();
                y = b.readInt();
                z = b.readInt();
                if (type == 2 || type == 7) {
                    solid = readMask(b, worldProjection);
                    hatch = readMask(b, worldProjection);
                    int count = 0;
                    for (long h : hatch) count += Long.bitCount(h);
                    dots = new byte[count];
                    b.readBytes(dots);
                    for (byte dot : dots) if ((dot & 255) > 16) throw new IllegalArgumentException("Invalid hatch dots");
                    hatchTypes = count == 0 ? HatchTypes.EMPTY : HatchTypes.read(b, count);
                    optional = readMask(b, worldProjection);
                    int styleCount = b.readUnsignedShort();
                    if (styleCount > 4096) throw new IllegalArgumentException("Too many preview styles");
                    styles = new ArrayList<>(styleCount);
                    for (int i = 0; i < styleCount; i++) {
                        Style style = new Style();
                        style.item = b.readInt();
                        style.damage = b.readInt();
                        style.group = b.readInt();
                        style.option = b.readInt();
                        int flags = b.readUnsignedByte();
                        style.air = (flags & 1) != 0;
                        style.conflict = (flags & 2) != 0;
                        style.groupSelectable = (flags & 4) != 0;
                        style.symbol = b.readChar();
                        int length = b.readUnsignedByte();
                        if (length > 64) throw new IllegalArgumentException("Preview role name too long");
                        byte[] name = new byte[length];
                        b.readBytes(name);
                        style.piece = new String(name, StandardCharsets.UTF_8);
                        styles.add(style);
                    }
                    painted = readMask(b, worldProjection);
                    styleSlots = styleCount == 0 ? null : new short[4096];
                    int run = 0, style = 0;
                    for (int word = 0; word < 64; word++)
                        for (long bits = painted[word]; bits != 0; bits &= bits - 1) {
                            if (run == 0) {
                                run = b.readUnsignedShort();
                                style = b.readUnsignedShort();
                                if (run == 0 || style == 0 || style > styleCount)
                                    throw new IllegalArgumentException("Invalid preview material run");
                            }
                            styleSlots[word * 64 + Long.numberOfTrailingZeros(bits)] = (short) style;
                            run--;
                        }
                    if (run != 0) throw new IllegalArgumentException("Preview material run overflow");
                }
                confirmed = readMask(b, worldProjection);
                if (type == 7) readRenders(b);
                installedHatches = worldProjection && b.isReadable() ? readMask(b, true) : null;
                if (worldProjection && (type == 2 || type == 7) && b.isReadable())
                    for (Style style : styles) style.optionCount = ByteBufUtils.readVarInt(b, 5);
            }
        }
    }

    public static final class Handler implements IMessageHandler<Packet, IMessage> {
        public IMessage onMessage(Packet p, MessageContext ctx) {
            com.Goderium.StructureBuilderNH.StructureBuilderNH.proxy.preview(p);
            return null;
        }
    }
}
