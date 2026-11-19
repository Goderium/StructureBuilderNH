package com.Goderium.StructureBuilderNH.common.builder;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reviewed source/target identities packed as section-local bit sets, independent of blueprint walk
 * order.
 */
final class UpgradeSnapshot {
    private static final class Type {
        final Block previous, target;
        final int previousMeta, targetMeta;
        final String name;

        Type(Block previous, int previousMeta, Block target, int targetMeta) {
            this.previous = previous;
            this.previousMeta = previousMeta;
            this.target = target;
            this.targetMeta = targetMeta;
            name =
                    Block.blockRegistry.getNameForObject(previous)
                            + "/"
                            + previousMeta
                            + ">"
                            + Block.blockRegistry.getNameForObject(target)
                            + "/"
                            + targetMeta;
        }
    }

    private static final class Section {
        final int x, y, z;
        final Map<Integer, BitSet> types = new LinkedHashMap<>();

        Section(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final List<Type> types = new ArrayList<>();
    private final Map<Long, Integer> runtimeTypes = new HashMap<>();
    private final Map<String, Integer> namedTypes = new HashMap<>();
    private final Map<Long, Section> sections = new LinkedHashMap<>();
    int count;

    void clear() {
        types.clear();
        runtimeTypes.clear();
        namedTypes.clear();
        sections.clear();
        count = 0;
    }

    private int type(
            Block previous, int previousMeta, Block target, int targetMeta, boolean create) {
        if (previous == null || target == null) return -1;
        long key =
                ((long) Block.getIdFromBlock(previous) << 32)
                        | ((long) Block.getIdFromBlock(target) << 8)
                        | (previousMeta << 4)
                        | targetMeta;
        Integer found = runtimeTypes.get(key);
        if (found != null) return found;
        if (!create || types.size() >= UpgradeBill.MAX_ROWS) return -1;
        Type type = new Type(previous, previousMeta, target, targetMeta);
        int index = types.size();
        types.add(type);
        runtimeTypes.put(key, index);
        namedTypes.put(type.name, index);
        return index;
    }

    boolean add(int x, int y, int z, ChannelUpgrade.Result result) {
        int type =
                type(result.oldBlock, result.oldMeta, result.targetBlock, result.targetMeta, true);
        if (type < 0 || count == Integer.MAX_VALUE) return false;
        long key = PlacementLedger.key(x, y, z);
        Section section = sections.computeIfAbsent(key, k -> new Section(x >> 4, y >> 4, z >> 4));
        int slot = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        for (BitSet bits : section.types.values()) if (bits.get(slot)) return false;
        section.types.computeIfAbsent(type, k -> new BitSet()).set(slot);
        count++;
        return true;
    }

    boolean matches(int x, int y, int z, ChannelUpgrade.Result result) {
        int type =
                type(result.oldBlock, result.oldMeta, result.targetBlock, result.targetMeta, false);
        Section section = sections.get(PlacementLedger.key(x, y, z));
        if (type < 0 || section == null) return false;
        BitSet cells = section.types.get(type);
        return cells != null && cells.get((y & 15) << 8 | (z & 15) << 4 | (x & 15));
    }

    boolean same(UpgradeSnapshot other) {
        if (count != other.count || sections.size() != other.sections.size()) return false;
        for (Map.Entry<Long, Section> part : sections.entrySet()) {
            Section next = other.sections.get(part.getKey());
            if (next == null || part.getValue().types.size() != next.types.size()) return false;
            for (Map.Entry<Integer, BitSet> cells : part.getValue().types.entrySet()) {
                Integer type = other.namedTypes.get(types.get(cells.getKey()).name);
                if (type == null || !cells.getValue().equals(next.types.get(type))) return false;
            }
        }
        return true;
    }

    NBTTagCompound save() {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList palette = new NBTTagList(), parts = new NBTTagList();
        for (Type t : types) {
            NBTTagCompound n = new NBTTagCompound();
            n.setString("previous", Block.blockRegistry.getNameForObject(t.previous));
            n.setString("target", Block.blockRegistry.getNameForObject(t.target));
            n.setInteger("previousMeta", t.previousMeta);
            n.setInteger("targetMeta", t.targetMeta);
            palette.appendTag(n);
        }
        for (Section section : sections.values()) {
            NBTTagCompound part = new NBTTagCompound();
            part.setIntArray("position", new int[] {section.x, section.y, section.z});
            NBTTagList cells = new NBTTagList();
            for (Map.Entry<Integer, BitSet> entry : section.types.entrySet()) {
                NBTTagCompound n = new NBTTagCompound();
                n.setInteger("type", entry.getKey());
                n.setByteArray("bits", entry.getValue().toByteArray());
                cells.appendTag(n);
            }
            part.setTag("cells", cells);
            parts.appendTag(part);
        }
        result.setTag("types", palette);
        result.setTag("sections", parts);
        return result;
    }

    boolean load(NBTTagCompound data) {
        clear();
        NBTTagList palette = data.getTagList("types", 10), parts = data.getTagList("sections", 10);
        if (palette.tagCount() > UpgradeBill.MAX_ROWS) return false;
        for (int i = 0; i < palette.tagCount(); i++) {
            NBTTagCompound n = palette.getCompoundTagAt(i);
            Block previous = (Block) Block.blockRegistry.getObject(n.getString("previous"));
            Block target = (Block) Block.blockRegistry.getObject(n.getString("target"));
            int pm = n.getInteger("previousMeta"), tm = n.getInteger("targetMeta");
            if (previous == null
                    || target == null
                    || previous == Blocks.air
                    || target == Blocks.air
                    || pm < 0
                    || pm > 15
                    || tm < 0
                    || tm > 15
                    || previous.hasTileEntity(pm)
                    || target.hasTileEntity(tm)
                    || type(previous, pm, target, tm, true) != i) {
                clear();
                return false;
            }
        }
        for (int i = 0; i < parts.tagCount(); i++) {
            NBTTagCompound n = parts.getCompoundTagAt(i);
            int[] p = n.getIntArray("position");
            if (p.length != 3
                    || p[0] < -1875000
                    || p[0] >= 1875000
                    || p[2] < -1875000
                    || p[2] >= 1875000
                    || p[1] < 0
                    || p[1] > 15) {
                clear();
                return false;
            }
            long key = PlacementLedger.key(p[0] * 16, p[1] * 16, p[2] * 16);
            if (sections.containsKey(key)) {
                clear();
                return false;
            }
            Section section = new Section(p[0], p[1], p[2]);
            BitSet occupied = new BitSet();
            NBTTagList cells = n.getTagList("cells", 10);
            if (cells.tagCount() > types.size()) {
                clear();
                return false;
            }
            for (int j = 0; j < cells.tagCount(); j++) {
                NBTTagCompound row = cells.getCompoundTagAt(j);
                int type = row.getInteger("type");
                byte[] bytes = row.getByteArray("bits");
                if (type < 0
                        || type >= types.size()
                        || bytes.length > 512
                        || section.types.containsKey(type)) {
                    clear();
                    return false;
                }
                BitSet bits = BitSet.valueOf(bytes);
                if (bits.isEmpty()
                        || bits.intersects(occupied)
                        || count > Integer.MAX_VALUE - bits.cardinality()) {
                    clear();
                    return false;
                }
                occupied.or(bits);
                section.types.put(type, bits);
                count += bits.cardinality();
            }
            if (!section.types.isEmpty()) sections.put(key, section);
        }
        return true;
    }
}
