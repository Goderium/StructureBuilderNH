package com.Goderium.StructureBuilderNH.common.builder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

import io.netty.buffer.ByteBuf;

/** Logical hatch categories shared by all matching markers in one preview section. */
public final class HatchTypes {
    private static final String[] NONE = new String[0];
    static final HatchTypes EMPTY = new HatchTypes();
    private final List<String[]> types = new ArrayList<>();
    private short[] slots;

    public String[] at(int rank) {
        return types.isEmpty() ? NONE : types.get(index(rank));
    }

    public int index(int rank) { return slots == null ? 0 : slots[rank] & 0xffff; }

    public int size() { return Math.max(1, types.size()); }

    public boolean sameAs(HatchTypes other) {
        if (this == other) return true;
        if (types.size() != other.types.size() || !Arrays.equals(slots, other.slots)) return false;
        for (int i = 0; i < types.size(); i++)
            if (!Arrays.equals(types.get(i), other.types.get(i))) return false;
        return true;
    }

    static HatchTypes collect(Blueprint plan, Blueprint.Section section, byte[] dots) {
        if (dots.length == 0) return EMPTY;
        HatchTypes result = new HatchTypes();
        IdentityHashMap<String[], Integer> indexes = new IdentityHashMap<>();
        int rank = 0;
        for (int word = 0; word < section.hatch.length; word++) {
            for (long bits = section.hatch[word]; bits != 0; bits &= bits - 1) {
                Blueprint.Cell cell = section.cells[word * 64 + Long.numberOfTrailingZeros(bits)];
                dots[rank] = (byte) cell.dots;
                String[] keys = plan.hatchNames(cell);
                Integer index = indexes.get(keys);
                if (index == null) {
                    index = result.types.size();
                    indexes.put(keys, index);
                    result.types.add(keys);
                    if (index == 1) result.slots = new short[dots.length];
                }
                if (result.slots != null) result.slots[rank] = index.shortValue();
                rank++;
            }
        }
        return result.types.size() == 1 && result.types.get(0).length == 0 ? EMPTY : result;
    }

    void write(ByteBuf b) {
        int start = b.writerIndex();
        b.writeShort(types.size());
        for (String[] keys : types) {
            // A third-party description must fit alongside the existing, bounded section projection.
            if (keys.length > 256 || b.writerIndex() - start > 32768) {
                b.writerIndex(start).writeShort(0);
                return;
            }
            b.writeShort(keys.length);
            for (String key : keys) {
                byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 1024 || b.writerIndex() - start + bytes.length + 2 > 32768) {
                    b.writerIndex(start).writeShort(0);
                    return;
                }
                b.writeShort(bytes.length);
                b.writeBytes(bytes);
            }
        }
        if (slots != null) {
            for (short slot : slots) {
                if (types.size() <= 256) b.writeByte(slot);
                else b.writeShort(slot);
            }
        }
    }

    static HatchTypes read(ByteBuf b, int count) {
        int size = b.readUnsignedShort();
        if (size > count) throw new IllegalArgumentException("Too many hatch types");
        if (size == 0) return EMPTY;
        HatchTypes result = new HatchTypes();
        for (int i = 0; i < size; i++) {
            int length = b.readUnsignedShort();
            if (length > 256) throw new IllegalArgumentException("Too many hatch categories");
            String[] keys = new String[length];
            for (int k = 0; k < length; k++) {
                int n = b.readUnsignedShort();
                if (n > 1024 || n > b.readableBytes()) throw new IllegalArgumentException("Invalid hatch category");
                keys[k] = b.toString(b.readerIndex(), n, StandardCharsets.UTF_8);
                b.skipBytes(n);
            }
            result.types.add(keys);
        }
        if (size > 1) {
            result.slots = new short[count];
            for (int i = 0; i < count; i++) {
                int index = size <= 256 ? b.readUnsignedByte() : b.readUnsignedShort();
                if (index >= size) throw new IllegalArgumentException("Invalid hatch type");
                result.slots[i] = (short) index;
            }
        }
        return result;
    }
}
