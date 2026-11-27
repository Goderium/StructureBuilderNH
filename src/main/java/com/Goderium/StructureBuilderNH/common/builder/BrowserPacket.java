package com.Goderium.StructureBuilderNH.common.builder;

import cpw.mods.fml.common.network.ByteBufUtils;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import io.netty.buffer.Unpooled;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

/** Window-scoped, read-only model data. It never enters the world-preview channel. */
public final class BrowserPacket {
    public static final int HEADER = 1, SECTION = 2, MATERIALS = 3, GROUPS = 4,
            CHANNELS = 5, PROGRESS = 6, DONE = 7, CLOSE = 8, ERROR = 9, FRAGMENT = 10, CACHE_OFFER = 11, BATCH = 12;
    public static final int FRAME_BYTES = 28000, MAX_BYTES = 1048576;
    static final int BATCH_HEADER_BYTES = 24, MAX_BATCH_MEMBERS = 16;
    private static final int MAX_DICTIONARY = Integer.MAX_VALUE;

    public UUID id, cacheKey;
    public int revision, type;
    public PreviewStream.Packet projection;
    public String name = "", error = "";
    /** Subtract this anchor from section coordinates to obtain controller-relative coordinates. */
    public int originX, originY, originZ;
    /** Structure pose; headers from older servers used NORTH for private previews. */
    public ExtendedFacing facing = ExtendedFacing.NORTH_NORMAL_NONE;
    public int tier = 1, capturedCells, totalSections, sentSections;
    public boolean ready;
    public NBTTagCompound channels = new NBTTagCompound();
    public String[] channelNames = new String[0];
    public int[] materialIds;
    public short[] materialSlots;
    public List<Material> materials;
    public List<Group> groups;
    public int transfer, part, parts, totalBytes;
    public byte[] payload;
    public int batchMembers;
    byte[] encoded;
    int wireBytes, completedSection = -1;

    public static final class Material {
        public int id, group = -1, dots = -1;
        public ItemStack item;
        public PreviewStream.RenderMaterial render;
        public String piece = "";
        public char symbol;
        public boolean air, hatch;
        public String[] hatchKeys = new String[0], channels = new String[0];
    }

    public static final class Group {
        public int id, count, defaultIndex;
        public String key = "", piece = "";
        public char symbol;
        public boolean groupSelectable;
        /** A null option is the explicit leave-empty candidate. */
        public ItemStack[] options = new ItemStack[0];
    }

    public void write(PacketBuffer b) {
        if (encoded != null) {
            b.writeBytes(encoded);
            return;
        }
        b.writeByte(type);
        b.writeLong(id.getMostSignificantBits());
        b.writeLong(id.getLeastSignificantBits());
        b.writeInt(revision);
        switch (type) {
            case HEADER:
                projection.toBytes(b);
                string(b, name);
                b.writeInt(originX); b.writeInt(originY); b.writeInt(originZ);
                b.writeInt(tier);
                ByteBufUtils.writeTag(b, channels);
                progress(b);
                b.writeByte(facing.getIndex());
                break;
            case SECTION:
                projection.toBytes(b);
                b.writeShort(materialIds.length);
                for (int material : materialIds) b.writeInt(material);
                slots(b, materialSlots);
                break;
            case MATERIALS:
                b.writeShort(materials.size());
                for (Material m : materials) {
                    b.writeInt(m.id);
                    ByteBufUtils.writeItemStack(b, m.item);
                    b.writeBoolean(m.render != null);
                    if (m.render != null) {
                        b.writeInt(m.render.block); b.writeInt(m.render.meta);
                        b.writeInt(m.render.machine); b.writeByte(m.render.front);
                    }
                    string(b, m.piece); b.writeChar(m.symbol); b.writeInt(m.group);
                    b.writeBoolean(m.air); b.writeBoolean(m.hatch); b.writeByte(m.dots);
                    strings(b, m.hatchKeys); strings(b, m.channels);
                }
                break;
            case GROUPS:
                b.writeShort(groups.size());
                for (Group g : groups) {
                    b.writeInt(g.id); string(b, g.key); string(b, g.piece); b.writeChar(g.symbol);
                    b.writeInt(g.count); b.writeInt(g.defaultIndex); b.writeBoolean(g.groupSelectable);
                    b.writeShort(g.options.length);
                    for (ItemStack item : g.options) ByteBufUtils.writeItemStack(b, item);
                }
                break;
            case CHANNELS: strings(b, channelNames); break;
            case PROGRESS: progress(b); break;
            case DONE:
                progress(b);
                if (cacheKey != null) writeCacheKey(b);
                break;
            case CACHE_OFFER: writeCacheKey(b); break;
            case BATCH:
                b.writeByte(batchMembers);
                b.writeShort(payload.length);
                b.writeBytes(payload);
                break;
            case CLOSE: break;
            case ERROR: string(b, error); break;
            case FRAGMENT:
                b.writeInt(transfer); b.writeShort(part); b.writeShort(parts); b.writeInt(totalBytes);
                b.writeShort(payload.length); b.writeBytes(payload);
                break;
            default: throw new IllegalArgumentException("Unknown browser packet");
        }
    }

    public static BrowserPacket read(PacketBuffer b) {
        BrowserPacket p = new BrowserPacket();
        p.type = b.readUnsignedByte();
        p.id = new UUID(b.readLong(), b.readLong());
        p.revision = b.readInt();
        switch (p.type) {
            case HEADER:
                p.projection = projection(b, p, 1);
                p.name = string(b);
                p.originX = b.readInt(); p.originY = b.readInt(); p.originZ = b.readInt();
                p.tier = b.readInt(); p.channels = ByteBufUtils.readTag(b);
                if (p.channels == null) p.channels = new NBTTagCompound();
                p.readProgress(b);
                if (b.isReadable()) {
                    int index = count(b.readUnsignedByte(), ExtendedFacing.VALUES.length - 1);
                    p.facing = ExtendedFacing.VALUES[index];
                }
                break;
            case SECTION:
                p.projection = projection(b, p, 2);
                p.materialIds = new int[count(b.readUnsignedShort(), 4096)];
                for (int i = 0; i < p.materialIds.length; i++)
                    p.materialIds[i] = count(b.readInt(), MAX_DICTIONARY);
                p.materialSlots = slots(b, p.materialIds.length);
                break;
            case MATERIALS:
                p.materials = new ArrayList<>();
                int materialCount = count(b.readUnsignedShort(), 4096);
                for (int i = 0; i < materialCount; i++) {
                    Material m = new Material(); m.id = count(b.readInt(), MAX_DICTIONARY);
                    m.item = ByteBufUtils.readItemStack(b);
                    if (b.readBoolean()) {
                        m.render = new PreviewStream.RenderMaterial();
                        m.render.block = b.readInt(); m.render.meta = b.readInt();
                        m.render.machine = b.readInt(); m.render.front = b.readByte();
                        if (m.render.block <= 0 || m.render.block > 65535 || m.render.meta < 0
                                || m.render.meta > 32767 || m.render.machine < -1 || m.render.machine > 32767
                                || m.render.front < -1 || m.render.front > 5)
                            throw new IllegalArgumentException("Invalid browser material");
                    }
                    m.piece = string(b); m.symbol = b.readChar(); m.group = b.readInt();
                    m.air = b.readBoolean(); m.hatch = b.readBoolean(); m.dots = b.readByte();
                    m.hatchKeys = strings(b); m.channels = strings(b);
                    p.materials.add(m);
                }
                break;
            case GROUPS:
                p.groups = new ArrayList<>();
                int groupCount = count(b.readUnsignedShort(), 4096);
                for (int i = 0; i < groupCount; i++) {
                    Group g = new Group(); g.id = count(b.readInt(), MAX_DICTIONARY);
                    g.key = string(b); g.piece = string(b); g.symbol = b.readChar();
                    g.count = count(b.readInt(), Integer.MAX_VALUE); g.defaultIndex = b.readInt();
                    g.groupSelectable = b.readBoolean();
                    g.options = new ItemStack[count(b.readUnsignedShort(), 4096)];
                    for (int j = 0; j < g.options.length; j++) g.options[j] = ByteBufUtils.readItemStack(b);
                    if (g.defaultIndex < 0 || g.defaultIndex >= g.options.length)
                        throw new IllegalArgumentException("Invalid browser default");
                    p.groups.add(g);
                }
                break;
            case CHANNELS: p.channelNames = strings(b); break;
            case PROGRESS: p.readProgress(b); break;
            case DONE:
                p.readProgress(b);
                if (b.isReadable()) p.cacheKey = new UUID(b.readLong(), b.readLong());
                break;
            case CACHE_OFFER: p.cacheKey = new UUID(b.readLong(), b.readLong()); break;
            case BATCH:
                p.batchMembers = count(b.readUnsignedByte(), MAX_BATCH_MEMBERS);
                int payloadBytes = count(b.readUnsignedShort(), FRAME_BYTES - BATCH_HEADER_BYTES);
                if (p.batchMembers < 2 || payloadBytes < p.batchMembers * 23 || payloadBytes != b.readableBytes())
                    throw new IllegalArgumentException("Invalid browser batch");
                p.payload = new byte[payloadBytes];
                b.readBytes(p.payload);
                break;
            case CLOSE: break;
            case ERROR: p.error = string(b); break;
            case FRAGMENT:
                p.transfer = b.readInt(); p.part = b.readUnsignedShort(); p.parts = b.readUnsignedShort();
                p.totalBytes = b.readInt(); int length = b.readUnsignedShort();
                if (p.totalBytes <= FRAME_BYTES || p.totalBytes > MAX_BYTES || p.part >= p.parts
                        || p.parts != (p.totalBytes + FRAME_BYTES - 1) / FRAME_BYTES
                        || length != Math.min(FRAME_BYTES, p.totalBytes - p.part * FRAME_BYTES)
                        || length > b.readableBytes()) throw new IllegalArgumentException("Invalid browser fragment");
                p.payload = new byte[length]; b.readBytes(p.payload);
                break;
            default: throw new IllegalArgumentException("Unknown browser packet");
        }
        return p;
    }

    boolean batchable() {
        return type == SECTION || type == MATERIALS || type == GROUPS;
    }

    /** Keep the packed frame in the queue and decode one member inside the indexing budget. */
    public int memberLength(int offset) {
        if (offset < 0 || offset > payload.length - 2) throw new IllegalArgumentException("Missing browser member");
        int length = (payload[offset] & 255) << 8 | (payload[offset + 1] & 255);
        if (length < 21 || length > payload.length - offset - 2)
            throw new IllegalArgumentException("Invalid browser member length");
        return length;
    }

    public BrowserPacket readMember(int offset, int length) {
        PacketBuffer b = new PacketBuffer(Unpooled.wrappedBuffer(payload, offset + 2, length));
        try {
            int childType = b.getUnsignedByte(b.readerIndex());
            if (childType != SECTION && childType != MATERIALS && childType != GROUPS)
                throw new IllegalArgumentException("Invalid browser member type");
            BrowserPacket child = read(b);
            if (!id.equals(child.id) || revision != child.revision || b.isReadable())
                throw new IllegalArgumentException("Mismatched browser member");
            return child;
        } finally { b.release(); }
    }

    private static PreviewStream.Packet projection(PacketBuffer b, BrowserPacket owner, int type) {
        PreviewStream.Packet p = new PreviewStream.Packet(); p.read(b, false);
        if (p.type != type || !owner.id.equals(p.id) || p.revision != owner.revision)
            throw new IllegalArgumentException("Mismatched browser projection");
        return p;
    }

    private void writeCacheKey(PacketBuffer b) {
        b.writeLong(cacheKey.getMostSignificantBits());
        b.writeLong(cacheKey.getLeastSignificantBits());
    }

    private void progress(PacketBuffer b) {
        b.writeInt(capturedCells); b.writeInt(totalSections); b.writeInt(sentSections); b.writeBoolean(ready);
    }

    private void readProgress(PacketBuffer b) {
        capturedCells = count(b.readInt(), Integer.MAX_VALUE);
        totalSections = count(b.readInt(), Integer.MAX_VALUE);
        sentSections = count(b.readInt(), totalSections); ready = b.readBoolean();
    }

    private static void string(PacketBuffer b, String value) {
        if (value == null || value.length() > 4096) throw new IllegalArgumentException("Browser string too long");
        ByteBufUtils.writeUTF8String(b, value);
    }

    private static String string(PacketBuffer b) {
        String value = ByteBufUtils.readUTF8String(b);
        if (value.length() > 4096) throw new IllegalArgumentException("Browser string too long");
        return value;
    }

    private static void strings(PacketBuffer b, String[] values) {
        if (values.length > 256) throw new IllegalArgumentException("Too many browser labels");
        b.writeShort(values.length);
        for (String value : values) string(b, value);
    }

    private static String[] strings(PacketBuffer b) {
        String[] result = new String[count(b.readUnsignedShort(), 256)];
        for (int i = 0; i < result.length; i++) result[i] = string(b);
        return result;
    }

    private static int count(int value, int maximum) {
        if (value < 0 || value > maximum) throw new IllegalArgumentException("Invalid browser count");
        return value;
    }

    private static void slots(PacketBuffer b, short[] slots) {
        if (slots == null || slots.length != 4096) throw new IllegalArgumentException("Invalid browser slots");
        for (int i = 0; i < 4096;) {
            short value = slots[i]; int end = i + 1;
            while (end < 4096 && slots[end] == value) end++;
            b.writeShort(end - i); b.writeShort(value); i = end;
        }
    }

    private static short[] slots(PacketBuffer b, int palette) {
        short[] result = new short[4096];
        for (int i = 0; i < result.length;) {
            int run = b.readUnsignedShort(), value = b.readUnsignedShort();
            if (run == 0 || run > result.length - i || value > palette)
                throw new IllegalArgumentException("Invalid browser slot run");
            Arrays.fill(result, i, i + run, (short) value); i += run;
        }
        return result;
    }

    /** At most one bounded transfer per window; stale revisions cannot complete a new model. */
    public static final class Assembler {
        private UUID id;
        private int revision = -1, transfer, parts;
        private UUID activeId;
        private int activeRevision = -1;
        private byte[] bytes;
        private final BitSet received = new BitSet();

        public BrowserPacket accept(BrowserPacket p) {
            if (p.type != FRAGMENT) {
                if (p.type == HEADER || p.type == CACHE_OFFER) {
                    if (p.id.equals(activeId) && p.revision < activeRevision) return null;
                    resetTransfer(); activeId = p.id; activeRevision = p.revision;
                } else if (activeId != null && (!activeId.equals(p.id) || activeRevision != p.revision)) return null;
                if (p.type == CLOSE || p.type == ERROR) clear();
                return p;
            }
            if (activeId != null && (!activeId.equals(p.id) || p.revision < activeRevision)) return null;
            if (activeId == null || activeRevision < p.revision) {
                resetTransfer(); activeId = p.id; activeRevision = p.revision;
            }
            if (id != null && id.equals(p.id) && p.revision < revision) return null;
            if (bytes == null || !p.id.equals(id) || revision != p.revision || transfer != p.transfer) {
                if (p.totalBytes <= FRAME_BYTES || p.totalBytes > MAX_BYTES)
                    throw new IllegalArgumentException("Invalid browser transfer");
                resetTransfer(); id = p.id; revision = p.revision; transfer = p.transfer;
                parts = p.parts; bytes = new byte[p.totalBytes];
            }
            if (parts != p.parts || bytes.length != p.totalBytes || p.part < 0 || p.part >= parts
                    || p.payload == null || p.payload.length != Math.min(FRAME_BYTES, bytes.length - p.part * FRAME_BYTES))
                throw new IllegalArgumentException("Changed browser transfer");
            System.arraycopy(p.payload, 0, bytes, p.part * FRAME_BYTES, p.payload.length);
            received.set(p.part);
            if (received.cardinality() != parts) return null;
            PacketBuffer b = new PacketBuffer(Unpooled.wrappedBuffer(bytes));
            try {
                BrowserPacket result = read(b);
                if (result.type == FRAGMENT || !id.equals(result.id) || revision != result.revision || b.isReadable())
                    throw new IllegalArgumentException("Invalid assembled browser packet");
                return result;
            } finally { b.release(); resetTransfer(); }
        }

        private void resetTransfer() { id = null; revision = -1; bytes = null; received.clear(); }
        public void clear() { resetTransfer(); activeId = null; activeRevision = -1; }
    }
}
