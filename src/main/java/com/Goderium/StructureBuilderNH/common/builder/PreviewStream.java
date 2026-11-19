package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.util.Bounds;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.*;
import cpw.mods.fml.relauncher.Side;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** A paced stream of compact section masks, independent of the lifetime of the item GUI. */
public final class PreviewStream {
    public static final int BOX = 1, SHELL = 2, HATCH = 4;
    private static final SimpleNetworkWrapper NET =
            NetworkRegistry.INSTANCE.newSimpleChannel("sbnh.preview6");
    private static final Map<String, PreviewStream> views = new HashMap<>();
    private final UUID taskId;
    private final BuildTask task;
    private final EntityPlayerMP viewer;
    private Blueprint sent;
    private Bounds bounds;
    private int cursor, lastLayers, lastAmbiguousDots;
    private int revision = -1, epoch;
    private boolean lastEditable;
    private final Map<MaterialChoice, Integer> roles = new IdentityHashMap<>();
    private final Deque<Packet> outbound = new ArrayDeque<>();
    private int transfer, inFlight = -1;
    private boolean inFlightProgress;
    private ChunkCoordinates lastIssue;
    private boolean lastController;
    private final Set<Integer> changedSections = new LinkedHashSet<>();
    private final Set<Integer> materialSections = new LinkedHashSet<>(), materialRefresh = new LinkedHashSet<>();
    private final Map<ChunkPosition, Integer> materialLocations = new HashMap<>();
    private boolean preferUpdate = true;

    private PreviewStream(BuildTask task, EntityPlayerMP viewer) {
        this.taskId = UUID.fromString(task.id);
        this.task = task;
        this.viewer = viewer;
    }

    public static void register() {
        NET.registerMessage(Handler.class, Packet.class, 0, Side.CLIENT);
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
            if (view.task.dim != dim || view.sent != view.task.plan) continue;
            Integer section = view.materialLocations.get(position);
            if (section == null) continue;
            BuildTask.MaterialSelection selection = view.task.selection(x, y, z);
            if (selection != null && selection.supported) view.materialRefresh.add(section);
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
            if ((task.previewLayers == 0 || task.plan == null) && issue == null
                    || p == null
                    || p.isDead
                    || p.dimension != task.dim
                    || !tools.containsKey(task.owner + task.tool)) {
                stop(task, p);
                continue;
            }
            PreviewStream view = views.get(task.id);
            if (view == null || view.viewer != p) {
                view = new PreviewStream(task, p);
                views.put(task.id, view);
            }
            view.markers(task, issue);
            if (task.plan != null)
                view.send(task, budgets.computeIfAbsent(task.owner, k -> new Budget()));
        }
    }

    private void markers(BuildTask task, ChunkCoordinates issue) {
        boolean controller = (task.previewLayers & HATCH) != 0;
        if (lastController == controller && Objects.equals(issue, lastIssue)) return;
        Packet pkt = packet(4);
        pkt.layers = (controller ? 1 : 0) | (issue != null ? 2 : 0);
        pkt.x = task.x;
        pkt.y = task.y;
        pkt.z = task.z;
        pkt.issue = issue;
        NET.sendTo(pkt, viewer);
        lastController = controller;
        lastIssue = issue;
    }

    private void send(BuildTask task, Budget budget) {
        Blueprint plan = task.plan;
        boolean editable = task.editable() && plan.ready;
        boolean reset = sent != plan, refresh = reset || revision != task.revision;
        int ambiguous = plan.ready ? plan.ambiguousHatchDots() : 0;
        if (refresh || lastLayers != task.previewLayers || !plan.bounds.equals(bounds)
                || lastEditable != editable || lastAmbiguousDots != ambiguous) {
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
                    materialLocations.clear();
                    materialRefresh.clear();
                    roles.clear();
                } else {
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
            }
            Packet pkt = packet(1);
            pkt.reset = reset;
            pkt.editable = editable;
            pkt.tool = task.tool;
            pkt.layers = task.previewLayers;
            pkt.bounds = plan.bounds;
            pkt.controllerMeta = task.target.getMetadata();
            pkt.tier = task.tier;
            pkt.ambiguousDots = ambiguous;
            NET.sendTo(pkt, viewer);
            sent = plan;
            lastLayers = task.previewLayers;
            lastEditable = editable;
            lastAmbiguousDots = ambiguous;
            bounds = plan.bounds;
        }
        if (!plan.ready || (lastLayers & (SHELL | HATCH)) == 0) return;
        Iterator<Integer> updates = changedSections.iterator();
        while (budget.scans > 0 && budget.sections > 0 && budget.bytes > 0
                && (cursor < plan.sections.size() || updates.hasNext() || !materialRefresh.isEmpty() || !outbound.isEmpty())) {
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
            boolean material = !materialRefresh.isEmpty();
            boolean update = !material && updates.hasNext() && (preferUpdate || cursor == plan.sections.size());
            Blueprint.Section section;
            if (material) {
                int id = materialRefresh.iterator().next();
                materialRefresh.remove(id);
                section = plan.sections.get(id);
            } else if (update) {
                section = plan.sections.get(updates.next());
                updates.remove();
            } else section = plan.sections.get(cursor);
            preferUpdate = !update;
            budget.scans--;
            Packet pkt = update ? progress(section) : section(task, section);
            if (!update && !material) cursor++;
            if (!update && empty(pkt.solid) && empty(pkt.optional)) continue;
            if (!update && !pkt.styles.isEmpty()) {
                materialSections.add(section.id);
                materialLocations.put(new ChunkPosition(section.x, section.y, section.z), section.id);
            }
            inFlight = section.id;
            inFlightProgress = update;
            enqueue(pkt);
        }
    }

    private void enqueue(Packet packet) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            packet.toBytes(encoded);
            int length = encoded.readableBytes();
            if (length <= 28000) {
                packet.wireBytes = length;
                outbound.add(packet);
                return;
            }
            if (length > 524288) throw new IllegalStateException("Preview section exceeds projection bound");
            int id = ++transfer, count = (length + 27999) / 28000;
            for (int part = 0; part < count; part++) {
                Packet fragment = packet(6);
                fragment.transfer = id;
                fragment.part = part;
                fragment.parts = count;
                fragment.totalBytes = length;
                fragment.payload = new byte[Math.min(28000, encoded.readableBytes())];
                encoded.readBytes(fragment.payload);
                fragment.wireBytes = fragment.payload.length + 41;
                outbound.add(fragment);
            }
        } finally {
            encoded.release();
        }
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
        pkt.type = 2;
        pkt.solid = section.solid.clone();
        pkt.hatch = section.hatch.clone();
        pkt.optional = new long[64];
        pkt.styles = new ArrayList<>();
        pkt.styleSlots = new short[4096];
        Map<String, Integer> palette = new HashMap<>();
        WorldServer world = DimensionManager.getWorld(task.dim);
        for (int i = 0; i < section.cells.length; i++) {
            Blueprint.Cell cell = section.cells[i];
            if (cell == null) continue;
            int x = section.x * 16 + (i & 15), y = section.y * 16 + (i >> 8),
                    z = section.z * 16 + (i >> 4 & 15);
            BuildTask.MaterialSelection selection = task.selection(x, y, z);
            if (selection == null || !selection.supported || selection.group == null) continue;
            MaterialChoice group = selection.group;
            boolean loaded = world != null && world.blockExists(x, y, z);
            int selected = loaded ? task.previewIndex(selection, world) : selection.index;
            ItemStack item = loaded ? task.previewItem(selection, world) : group.optionItem(selected);
            Style style = new Style();
            style.group = roles.computeIfAbsent(group, ignored -> roles.size());
            style.item = item == null ? -1 : Item.getIdFromItem(item.getItem());
            style.damage = item == null ? 0 : item.getMetadata();
            style.option = selected;
            style.air = item == null;
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
            } else pkt.solid[i >> 6] |= bit;
        }
        int count = 0;
        for (long bits : pkt.hatch) count += Long.bitCount(bits);
        pkt.dots = new byte[count];
        pkt.hatchTypes = HatchTypes.collect(task.plan, section, pkt.dots);
        return pkt;
    }

    private static boolean empty(long[] mask) {
        for (long word : mask) if (word != 0) return false;
        return true;
    }

    static void clear() {
        views.clear();
    }

    public static final class Style {
        public int item, damage, group, option;
        public boolean air, conflict, groupSelectable;
        public String piece;
        public char symbol;
    }

    public static final class Packet implements IMessage {
        public UUID id;
        public int type, dim, layers, x, y, z, revision, epoch;
        public int controllerMeta, tier, ambiguousDots;
        public HatchTypes hatchTypes;
        public boolean reset, editable;
        public String tool;
        public Bounds bounds;
        public long[] solid, hatch, confirmed, optional, painted;
        public List<Style> styles;
        public short[] styleSlots;
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
                return;
            }
            if (type == 1) {
                b.writeByte(layers);
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
                if (type == 2) {
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
            }
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

        private static long[] readMask(ByteBuf b) {
            long[] mask = new long[64];
            long present = b.readLong();
            while (present != 0) {
                int i = Long.numberOfTrailingZeros(present);
                mask[i] = b.readLong();
                present &= present - 1;
            }
            return mask;
        }

        public void fromBytes(ByteBuf b) {
            type = b.readUnsignedByte();
            id = new UUID(b.readLong(), b.readLong());
            if (type == 0) return;
            dim = b.readInt();
            epoch = b.readInt();
            revision = b.readInt();
            if (type == 6) {
                transfer = b.readInt();
                part = b.readUnsignedByte();
                parts = b.readUnsignedByte();
                totalBytes = b.readInt();
                int length = b.readUnsignedShort();
                if (parts == 0 || parts > 19 || part >= parts || totalBytes <= 28000 || totalBytes > 524288
                        || length > 28000 || length != Math.min(28000, totalBytes - part * 28000)
                        || parts != (totalBytes + 27999) / 28000)
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
                return;
            }
            if (type == 1) {
                layers = b.readUnsignedByte();
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
            } else {
                x = b.readInt();
                y = b.readInt();
                z = b.readInt();
                if (type == 2) {
                    solid = readMask(b);
                    hatch = readMask(b);
                    int count = 0;
                    for (long h : hatch) count += Long.bitCount(h);
                    dots = new byte[count];
                    b.readBytes(dots);
                    for (byte dot : dots) if ((dot & 255) > 16) throw new IllegalArgumentException("Invalid hatch dots");
                    hatchTypes = count == 0 ? HatchTypes.EMPTY : HatchTypes.read(b, count);
                    optional = readMask(b);
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
                    painted = readMask(b);
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
                confirmed = readMask(b);
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
