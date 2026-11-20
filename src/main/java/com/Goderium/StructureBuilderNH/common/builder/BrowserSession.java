package com.Goderium.StructureBuilderNH.common.builder;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.util.MiscUtils;
import com.Goderium.StructureBuilderNH.util.Bounds;
import com.Goderium.StructureBuilderNH.config.RebuildConfig;
import gregtech.api.GregTechAPI;
import gregtech.common.blocks.BlockDecorativeFrame;
import gregtech.common.blocks.BlockFrameBox;
import gregtech.common.blocks.ItemMachines;
import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/** A GUI-owned read-only blueprint. No tasks, chunk tickets, world queries or material reservations. */
public final class BrowserSession implements AutoCloseable {
    private static final long CAPTURE_NANOS = 2_000_000L;
    private static final int CACHE_CELLS = 65536;
    private final UUID id = UUID.randomUUID();
    private final EntityPlayerMP player;
    private final BuildTask source;
    private final ExtendedFacing facing;
    private final ItemStack target;
    private final Consumer<BrowserPacket> sender;
    private final Set<String> observed = new TreeSet<>();
    private final Set<String> readOnlyObserved = Collections.unmodifiableSet(observed);
    private final Deque<BrowserPacket> outbound = new ArrayDeque<>();
    private final Map<Blueprint.Cell, Map<Integer, Integer>> materialIds = new IdentityHashMap<>();
    private HatchNames hatchTypes = new HatchNames(true);
    private final Map<MaterialChoice, Integer> groupIds = new IdentityHashMap<>();
    private final BitSet dirtySections = new BitSet(), emittedSections = new BitSet(), choiceSections = new BitSet();
    private final Consumer<Blueprint.Section> observer = section -> dirtySections.set(section.id);
    private final LinkedHashMap<String, Blueprint> cache = new LinkedHashMap<>(4, .75f, true);
    private Blueprint plan;
    private NBTTagCompound channels;
    private int tier, revision, cursor, nextMaterial = 1, transfer, knownChannels = -1;
    private int dirtyCursor;
    private int originX, originY, originZ;
    private boolean closed, header, completed, borrowed, finalHeader;
    private boolean forcePrivate, borrowedSelectionsReady;
    private int sharedIdle;
    private long sharedWalks;
    private String configKey;
    private Bounds lastBounds;
    private Iterator<Map.Entry<MaterialChoice, Integer>> finalGroups;
    private boolean groupsComplete;

    public BrowserSession(EntityPlayerMP player, BuildTask task, Consumer<BrowserPacket> sender) {
        this(player, task, task == null ? null : task.target, task == null ? 1 : task.tier,
                task == null ? new NBTTagCompound() : task.channels, sender);
        if (task != null && !player.getUniqueID().toString().equals(task.owner))
            throw new IllegalArgumentException("Browser task is not owned by this player");
    }

    public BrowserSession(EntityPlayerMP player, ItemStack target, int tier, NBTTagCompound channels,
            Consumer<BrowserPacket> sender) {
        this(player, null, target, tier, channels, sender);
    }

    private BrowserSession(EntityPlayerMP player, BuildTask source, ItemStack target, int tier,
            NBTTagCompound channels, Consumer<BrowserPacket> sender) {
        if (player == null || sender == null) throw new IllegalArgumentException("Missing browser owner");
        this.player = player;
        this.source = source;
        this.facing = source == null ? ExtendedFacing.SOUTH_NORMAL_NONE : source.facing;
        this.target = ItemStack.copyItemStack(target);
        this.sender = sender;
        this.tier = normalizedTier(tier);
        this.channels = normalize(channels);
        configKey = key(this.tier, this.channels);
    }

    public UUID id() { return id; }
    public int revision() { return revision; }
    public boolean ready() { return plan != null && plan.ready; }
    public boolean closed() { return closed; }
    /** A live, unmodifiable, small set; callers may compare its contents between GUI ticks. */
    public Set<String> observedChannels() { return readOnlyObserved; }

    /** Preview-only configuration; the caller separately validates any eventual task changes. */
    public void configure(int tier, NBTTagCompound channels) {
        if (closed) return;
        int nextTier = normalizedTier(tier);
        NBTTagCompound next = normalize(channels);
        String nextKey = key(nextTier, next);
        if (nextKey.equals(configKey)) return;
        remember();
        this.tier = nextTier; this.channels = next; configKey = nextKey;
        restart(false);
        // Keep discovered channel names when a lower tier temporarily omits an optional piece.
    }

    private void restart(boolean forcePrivate) {
        if (plan != null) plan.removeBrowserObserver(observer);
        revision++;
        this.forcePrivate = forcePrivate;
        plan = null; borrowed = false; header = false; finalHeader = false; completed = false;
        borrowedSelectionsReady = false; sharedIdle = 0;
        cursor = 0; nextMaterial = 1; knownChannels = -1;
        originX = originY = originZ = 0;
        outbound.clear(); materialIds.clear(); groupIds.clear();
        hatchTypes = new HatchNames(true);
        lastBounds = null;
        dirtySections.clear(); emittedSections.clear(); choiceSections.clear(); finalGroups = null; groupsComplete = false;
        dirtyCursor = 0;
    }

    /** Called only by the owning GUI's server tick. Each native callback remains an indivisible operation. */
    public void tick() {
        if (closed) return;
        if (player.isDead || player.playerNetServerHandler == null) { release(); return; }
        long deadline = System.nanoTime() + CAPTURE_NANOS;
        try {
            if (borrowed) {
                boolean compatible = source.plan == plan && matchesSource();
                if (!compatible || !plan.ready && !activeSource()) restart(true);
                else if (!plan.ready) {
                    if (sharedWalks != plan.browserWalks) { sharedWalks = plan.browserWalks; sharedIdle = 0; }
                    else if (++sharedIdle >= 20) restart(true);
                }
            }
            if (plan == null) start();
            if (!header) sendHeader(true);
            if (!borrowed && !plan.ready) {
                ChannelProbe.begin(observed);
                try { plan.advance(Math.min(deadline, System.nanoTime() + CAPTURE_NANOS / 2)); }
                finally { ChannelProbe.end(); }
            }
            observed.addAll(plan.browserChannels.observed());
            observed.remove("chisel");
            if (borrowed && plan.ready && !borrowedSelectionsReady && source.hasCachedSelections()) {
                borrowedSelectionsReady = true;
                if (!choiceSections.isEmpty()) {
                    dirtySections.or(choiceSections); completed = false;
                    finalGroups = null; groupsComplete = false;
                }
            }
            if (knownChannels != observed.size()) {
                BrowserPacket names = packet(BrowserPacket.CHANNELS);
                names.channelNames = observed.toArray(new String[0]);
                enqueue(names); knownChannels = observed.size();
            }
            if (plan.ready && !finalHeader) {
                sendHeader(false); finalHeader = true; remember();
            } else if (!plan.ready && !plan.bounds.equals(lastBounds)) {
                sendHeader(false);
            }
            int frames = 0, bytes = 0;
            while (frames < 2 && bytes < 57000) {
                if (outbound.isEmpty()) {
                    if (System.nanoTime() >= deadline) break;
                    int dirty = dirtySections.nextSetBit(dirtyCursor);
                    if (dirty < 0) dirty = dirtySections.nextSetBit(0);
                    if (dirty >= 0) {
                        dirtySections.clear(dirty);
                        dirtyCursor = dirty + 1;
                        section(plan.sections.get(dirty));
                    } else if (plan.ready && !groupsComplete) {
                        finalGroups();
                        if (outbound.isEmpty()) break;
                    } else break;
                }
                BrowserPacket next = outbound.pollFirst();
                if (next == null) break;
                sender.accept(next);
                bytes += next.wireBytes;
                if (next.completedSection >= 0 && !emittedSections.get(next.completedSection)) {
                    emittedSections.set(next.completedSection); cursor++;
                }
                frames++;
            }
            if (plan.ready && dirtySections.isEmpty() && groupsComplete && outbound.isEmpty() && !completed) {
                BrowserPacket done = packet(BrowserPacket.DONE); progress(done);
                sender.accept(done); completed = true;
            } else if (!completed && (outbound.isEmpty() || !plan.ready)) {
                BrowserPacket progress = packet(BrowserPacket.PROGRESS); progress(progress); sender.accept(progress);
            }
        } catch (RuntimeException | LinkageError failure) {
            BrowserPacket error = packet(BrowserPacket.ERROR);
            error.error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            if (error.error.length() > 4096) error.error = error.error.substring(0, 4096);
            release();
            try { sender.accept(error); } catch (RuntimeException ignored) { /* Window already closed. */ }
        }
    }

    private void start() {
        if (Blueprint.prototype(target) == null) throw new IllegalArgumentException("Controller has no constructable blueprint");
        if (!forcePrivate && source != null && source.plan != null && (source.plan.ready || activeSource())
                && matchesSource()) {
            plan = source.plan; borrowed = true;
            originX = source.x; originY = source.y; originZ = source.z;
            observed.addAll(source.observedChannels);
            sharedWalks = plan.browserWalks;
        } else {
            plan = cache.get(configKey);
            if (plan == null) {
                ItemStack trigger = new ItemStack(StructureLibAPI.getDefaultHologramItem(), tier);
                for (Object raw : MiscUtils.getTagKeys(channels)) {
                    String name = (String) raw;
                    ChannelDataAccessor.setChannelData(trigger, name, channels.getInteger(name));
                }
                if (!channels.hasKey("chisel")) ChannelDataAccessor.setChannelData(trigger, "chisel", 1);
                ChannelProbe.begin(observed);
                try {
                    plan = Blueprint.read(target, trigger, facing,
                            0, 0, 0, player, ignored -> false, RebuildConfig.useSurvivalConstruct);
                } finally { ChannelProbe.end(); }
            }
        }
        observed.remove("chisel");
        dirtySections.set(0, plan.sections.size());
        if (!plan.ready) plan.addBrowserObserver(observer);
    }

    private boolean matchesSource() {
        return source.facing == facing && source.usesConfiguredSource() && Materials.equal(target, source.target)
                && source.tier == tier && key(source.tier, normalize(source.channels)).equals(configKey);
    }

    private boolean activeSource() {
        return source != null && (source.state == BuildTask.State.DRAFT || source.state == BuildTask.State.PREVIEW);
    }

    private void sendHeader(boolean reset) {
        BrowserPacket p = packet(BrowserPacket.HEADER);
        p.name = target.getDisplayName(); p.tier = tier;
        p.originX = originX; p.originY = originY; p.originZ = originZ;
        p.facing = facing;
        p.channels = (NBTTagCompound) channels.copy();
        p.projection = projection(1);
        p.projection.reset = reset; p.projection.bounds = plan.bounds;
        p.projection.controllerMeta = target.getMetadata(); p.projection.tier = tier;
        p.projection.ambiguousDots = plan.ambiguousHatchDots();
        progress(p); enqueue(p); header = true; lastBounds = plan.bounds;
    }

    private void section(Blueprint.Section section) {
        BrowserPacket p = packet(BrowserPacket.SECTION);
        p.projection = projection(2);
        PreviewStream.Packet view = p.projection;
        view.x = section.x; view.y = section.y; view.z = section.z;
        view.solid = section.solid.clone(); view.hatch = section.hatch.clone();
        view.confirmed = new long[64]; view.optional = new long[64];
        view.styles = Collections.emptyList(); view.styleSlots = new short[4096];
        int hatches = 0;
        for (long word : view.hatch) hatches += Long.bitCount(word);
        view.dots = new byte[hatches]; view.hatchTypes = HatchTypes.collect(plan, section, view.dots);
        p.materialSlots = new short[4096];
        List<Integer> palette = new ArrayList<>();
        Map<Integer, Integer> localIds = new HashMap<>();
        List<BrowserPacket.Material> added = new ArrayList<>();
        List<BrowserPacket.Group> groups = new ArrayList<>();
        for (int slot = 0; slot < 4096; slot++) {
            Blueprint.Cell cell = section.cells[slot];
            if (cell == null) continue;
            if (cell.choice != null) choiceSections.set(section.id);
            int option = cell.choice == null ? -1 : cell.choice.defaultIndex;
            if (borrowed && source.plan == plan && cell.choice != null) {
                BuildTask.MaterialSelection selection = source.cachedSelection(section.x * 16 + (slot & 15),
                        section.y * 16 + (slot >> 8), section.z * 16 + (slot >> 4 & 15));
                if (selection != null && selection.supported && selection.cell == cell) option = selection.index;
            }
            Map<Integer, Integer> variants = materialIds.get(cell);
            if (variants == null) { variants = new HashMap<>(); materialIds.put(cell, variants); }
            Integer materialId = variants.get(option);
            if (materialId == null) {
                if (nextMaterial <= 0) throw new IllegalArgumentException("Browser material index overflow");
                materialId = nextMaterial++;
                variants.put(option, materialId);
                added.add(material(cell, option, materialId, groups));
            }
            Integer local = localIds.get(materialId);
            if (local == null) { palette.add(materialId); local = palette.size(); localIds.put(materialId, local); }
            p.materialSlots[slot] = local.shortValue();
            ItemStack item = cell.choice == null ? cell.item : cell.choice.optionItem(option);
            boolean air = cell.choice == null ? cell.air : item == null;
            long bit = 1L << (slot & 63);
            if (air) { view.solid[slot >> 6] &= ~bit; view.optional[slot >> 6] |= bit; }
            else view.solid[slot >> 6] |= bit;
        }
        p.materialIds = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) p.materialIds[i] = palette.get(i);
        // Dictionaries precede every referencing section, including when either needs fragments.
        for (int i = 0; i < groups.size(); i += 16) {
            BrowserPacket entries = packet(BrowserPacket.GROUPS);
            entries.groups = new ArrayList<>(groups.subList(i, Math.min(i + 16, groups.size()))); enqueue(entries);
        }
        for (int i = 0; i < added.size(); i += 128) {
            BrowserPacket entries = packet(BrowserPacket.MATERIALS);
            entries.materials = new ArrayList<>(added.subList(i, Math.min(i + 128, added.size()))); enqueue(entries);
        }
        p.completedSection = section.id;
        enqueue(p);
    }

    private BrowserPacket.Material material(Blueprint.Cell cell, int option, int id, List<BrowserPacket.Group> added) {
        BrowserPacket.Material m = new BrowserPacket.Material();
        m.id = id; m.piece = cell.piece; m.symbol = cell.symbol;
        m.item = ItemStack.copyItemStack(cell.choice == null ? cell.item : cell.choice.optionItem(option));
        if (m.item != null) m.item.stackSize = 1;
        m.air = cell.choice == null ? cell.air : m.item == null;
        m.hatch = cell.hatch; m.dots = cell.dots;
        if (cell.hatch) m.hatchKeys = hatchTypes.resolve(cell.element, plan.controller);
        m.channels = plan.browserChannels.names(cell);
        if (cell.choice != null) {
            Integer group = groupIds.get(cell.choice);
            if (group == null) {
                group = groupIds.size(); groupIds.put(cell.choice, group);
                added.add(group(cell.choice, group));
            }
            m.group = group;
        }
        if (!m.air) {
            try { m.render = appearance(cell, m.item, cell.choice != null); }
            catch (RuntimeException | LinkageError unsupported) { /* Keep semantic/color fallback. */ }
        }
        return m;
    }

    private BrowserPacket.Group group(MaterialChoice choice, int id) {
        BrowserPacket.Group g = new BrowserPacket.Group();
        g.id = id; g.key = choice.key; g.piece = choice.piece; g.symbol = choice.symbol;
        g.count = choice.count; g.defaultIndex = choice.defaultIndex;
        g.groupSelectable = choice.groupSelectable();
        if (choice.optionCount() > 4096) throw new IllegalArgumentException("Too many browser candidates");
        g.options = new ItemStack[choice.optionCount()];
        for (int i = 0; i < g.options.length; i++) g.options[i] = ItemStack.copyItemStack(choice.optionItem(i));
        return g;
    }

    private void finalGroups() {
        if (finalGroups == null) finalGroups = groupIds.entrySet().iterator();
        BrowserPacket p = packet(BrowserPacket.GROUPS); p.groups = new ArrayList<>();
        while (p.groups.size() < 16 && finalGroups.hasNext()) {
            Map.Entry<MaterialChoice, Integer> entry = finalGroups.next();
            p.groups.add(group(entry.getKey(), entry.getValue()));
        }
        if (!p.groups.isEmpty()) enqueue(p);
        groupsComplete = !finalGroups.hasNext();
    }

    private PreviewStream.RenderMaterial appearance(Blueprint.Cell cell, ItemStack item, boolean selected) {
        if (item != null && item.getItem() instanceof ItemMachines) {
            int machine = item.getMetadata();
            if (machine > 0 && machine < GregTechAPI.METATILEENTITIES.length
                    && GregTechAPI.METATILEENTITIES[machine] != null)
                return render(GregTechAPI.sBlockMachines, GregTechAPI.METATILEENTITIES[machine].getTileEntityBaseType(),
                        machine, cell.previewFront(facing.getDirection()).ordinal());
            return null;
        }
        if (!selected && cell.block != null && cell.block != Blocks.air && cell.block != StructureLibAPI.getBlockHint())
            return render(cell.block, cell.meta, -1, -1);
        if (item != null && item.getItem() instanceof ItemBlock)
            return render(Block.getBlockFromItem(item.getItem()), item.getItem().getMetadata(item.getMetadata()), -1, -1);
        return null;
    }

    private static PreviewStream.RenderMaterial render(Block block, int meta, int machine, int front) {
        int maxMeta = block instanceof BlockFrameBox ? BlockFrameBox.MATERIAL_MASK | BlockFrameBox.MTE_BIT
                : block instanceof BlockDecorativeFrame ? Short.MAX_VALUE : 15;
        int blockId = Block.getIdFromBlock(block);
        if (blockId <= 0 || blockId > 65535 || meta < 0 || meta > maxMeta) return null;
        PreviewStream.RenderMaterial m = new PreviewStream.RenderMaterial();
        m.block = blockId; m.meta = meta; m.machine = machine; m.front = front;
        return m;
    }

    private PreviewStream.Packet projection(int type) {
        PreviewStream.Packet p = new PreviewStream.Packet();
        p.id = id; p.type = type; p.revision = revision; p.epoch = revision;
        p.layers = PreviewStream.BOX | PreviewStream.SHELL | PreviewStream.HATCH;
        p.tool = "browser"; p.editable = false; p.textured = true;
        return p;
    }

    private BrowserPacket packet(int type) {
        BrowserPacket p = new BrowserPacket(); p.id = id; p.revision = revision; p.type = type; return p;
    }

    private void progress(BrowserPacket p) {
        p.capturedCells = plan.size; p.totalSections = plan.sections.size();
        p.sentSections = cursor; p.ready = plan.ready;
    }

    private void enqueue(BrowserPacket p) {
        PacketBuffer b = new PacketBuffer(Unpooled.buffer());
        try {
            p.write(b);
            int length = b.readableBytes();
            if (length > BrowserPacket.MAX_BYTES) throw new IllegalArgumentException("Browser packet exceeds model limit");
            if (length <= BrowserPacket.FRAME_BYTES) { p.wireBytes = length; outbound.add(p); return; }
            int transfer = ++this.transfer, parts = (length + BrowserPacket.FRAME_BYTES - 1) / BrowserPacket.FRAME_BYTES;
            for (int part = 0; part < parts; part++) {
                BrowserPacket fragment = packet(BrowserPacket.FRAGMENT);
                fragment.transfer = transfer; fragment.part = part; fragment.parts = parts; fragment.totalBytes = length;
                fragment.payload = new byte[Math.min(BrowserPacket.FRAME_BYTES, b.readableBytes())];
                fragment.wireBytes = fragment.payload.length + 37;
                if (part == parts - 1) fragment.completedSection = p.completedSection;
                b.readBytes(fragment.payload); outbound.add(fragment);
            }
        } finally { b.release(); }
    }

    private void remember() {
        if (plan == null || !plan.ready || borrowed || plan.size > CACHE_CELLS) return;
        cache.put(configKey, plan);
        int total = 0;
        for (Blueprint cached : cache.values()) total += cached.size;
        Iterator<Blueprint> i = cache.values().iterator();
        while ((cache.size() > 2 || total > CACHE_CELLS) && i.hasNext()) { total -= i.next().size; i.remove(); }
    }

    private static int normalizedTier(int tier) {
        if (tier < 1 || tier > 4096) throw new IllegalArgumentException("Invalid browser tier");
        return tier;
    }

    private static NBTTagCompound normalize(NBTTagCompound input) {
        NBTTagCompound result = new NBTTagCompound();
        if (input == null) return result;
        Set<?> names = MiscUtils.getTagKeys(input);
        if (names.size() > 256) throw new IllegalArgumentException("Too many browser channels");
        for (Object raw : names) {
            String name = (String) raw; int value = input.getInteger(name);
            if (name.isEmpty() || name.length() > 128 || value < 0)
                throw new IllegalArgumentException("Invalid browser channel");
            if (value > 0) result.setInteger(name, value);
        }
        return result;
    }

    private static String key(int tier, NBTTagCompound channels) {
        Map<String, Integer> values = new TreeMap<>();
        for (Object raw : MiscUtils.getTagKeys(channels)) values.put((String) raw, channels.getInteger((String) raw));
        StringBuilder key = new StringBuilder().append(tier).append(':');
        for (Map.Entry<String, Integer> entry : values.entrySet())
            key.append(entry.getKey().length()).append(':').append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        return key.toString();
    }

    @Override public void close() {
        if (closed) return;
        BrowserPacket p = packet(BrowserPacket.CLOSE);
        release();
        try { sender.accept(p); } catch (RuntimeException ignored) { /* Window already gone. */ }
    }

    private void release() {
        if (plan != null) plan.removeBrowserObserver(observer);
        closed = true; plan = null; outbound.clear(); materialIds.clear(); groupIds.clear(); cache.clear(); observed.clear();
        dirtySections.clear(); emittedSections.clear(); choiceSections.clear(); finalGroups = null;
    }
}
