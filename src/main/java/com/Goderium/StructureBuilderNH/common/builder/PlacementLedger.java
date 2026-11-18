package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.common.WorldHooks;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Only successful writes by this task become removable. Coordinates use world sections, not plan
 * order.
 */
final class PlacementLedger {
    private static final byte FORMAT = 1;

    static final class Section {
        final int x, y, z;
        final BitSet cells = new BitSet(4096);
        final short[] types = new short[4096];

        Section(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    final BuildTask task;
    private final Map<Long, Section> sections = new LinkedHashMap<>();
    private final Map<String, Integer> types = new HashMap<>();
    private final List<String> palette = new ArrayList<>();
    int count;

    PlacementLedger(BuildTask task) {
        this.task = task;
    }

    static long key(int x, int y, int z) {
        return ((long) (x >> 4 & 0x3fffff) << 26)
                | ((long) (z >> 4 & 0x3fffff) << 4)
                | (y >> 4 & 15);
    }

    private static int slot(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }

    private static String identity(World w, int x, int y, int z) {
        Block block = w.getBlock(x, y, z);
        TileEntity tile = w.getTileEntity(x, y, z);
        return Block.blockRegistry.getNameForObject(block)
                + "/"
                + w.getBlockMetadata(x, y, z)
                + "/"
                + (tile instanceof IGregTechTileEntity
                        ? ((IGregTechTileEntity) tile).getMetaTileID()
                        : -1);
    }

    void record(World world, int x, int y, int z) {
        String name = identity(world, x, y, z);
        Integer type = types.get(name);
        if (type == null) {
            if (palette.size() >= 65536) return;
            type = palette.size();
            palette.add(name);
            types.put(name, type);
        }
        long key = key(x, y, z);
        Section section = sections.get(key);
        if (section == null) {
            section = new Section(x >> 4, y >> 4, z >> 4);
            sections.put(key, section);
            task.store.watch(task.dim, key, this);
        }
        int i = slot(x, y, z);
        if (!section.cells.get(i)) count++;
        section.cells.set(i);
        section.types[i] = type.shortValue();
        task.changed();
    }

    boolean matches(World world, int x, int y, int z) {
        Section section = sections.get(key(x, y, z));
        int i = slot(x, y, z);
        return section != null
                && section.cells.get(i)
                && palette.get(section.types[i] & 65535).equals(identity(world, x, y, z));
    }

    void forget(int x, int y, int z) {
        long key = key(x, y, z);
        Section section = sections.get(key);
        int i = slot(x, y, z);
        if (section == null || !section.cells.get(i)) return;
        section.cells.clear(i);
        count--;
        if (section.cells.isEmpty()) {
            sections.remove(key);
            task.store.unwatch(task.dim, key, this);
        }
        task.changed();
    }

    void discard() {
        for (long key : sections.keySet()) task.store.unwatch(task.dim, key, this);
        sections.clear();
        palette.clear();
        types.clear();
        count = 0;
    }

    boolean harvest(EntityPlayerMP actor, WorldServer world, ItemStack blueprint, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        boolean creative = actor.capabilities.isCreativeMode;
        if (!world.canMineBlock(actor, x, y, z)
                || !actor.canPlayerEdit(x, y, z, 1, blueprint)
                || (!creative && block.getBlockHardness(world, x, y, z) < 0)) return false;
        // The blueprint breaks this block on behalf of its owner. Protection handlers can veto it.
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(x, y, z, world, block, meta, actor);
        if (MinecraftForge.EVENT_BUS.post(event) || !matches(world, x, y, z)) return false;
        WorldHooks.ItemDropSink previous = WorldHooks.DISMANTLE_DROPS.get();
        WorldHooks.DISMANTLE_DROPS.set((source, stack) -> {
            if (source != world) return previous != null && previous.accept(source, stack);
            task.salvage.add(stack, stack.stackSize);
            task.changed();
            return true;
        });
        try {
            block.onBlockHarvested(world, x, y, z, meta, actor);
            if (!block.removedByPlayer(world, actor, x, y, z, !creative)) return false;
            block.onBlockDestroyedByPlayer(world, x, y, z, meta);
            if (!creative) block.harvestBlock(world, actor, x, y, z, meta);
            return true;
        } finally {
            if (previous == null) WorldHooks.DISMANTLE_DROPS.remove();
            else WorldHooks.DISMANTLE_DROPS.set(previous);
        }
    }

    ChunkCoordinates next() {
        for (Section s : sections.values()) {
            int i = s.cells.nextSetBit(0);
            if (count > 1
                    && s.x == task.x >> 4
                    && s.y == task.y >> 4
                    && s.z == task.z >> 4
                    && i == slot(task.x, task.y, task.z)) i = s.cells.nextSetBit(i + 1);
            if (i >= 0)
                return new ChunkCoordinates(
                        s.x * 16 + (i & 15), s.y * 16 + (i >> 8), s.z * 16 + (i >> 4 & 15));
        }
        return null;
    }

    NBTTagCompound save() {
        NBTTagCompound n = new NBTTagCompound();
        n.setByte("format", FORMAT);
        NBTTagList names = new NBTTagList(), data = new NBTTagList();
        for (String name : palette) names.appendTag(new NBTTagString(name));
        for (Section s : sections.values()) {
            NBTTagCompound part = new NBTTagCompound();
            part.setIntArray("section", new int[] {s.x, s.y, s.z});
            int size = s.cells.cardinality();
            ByteArrayOutputStream deltas = new ByteArrayOutputStream(size);
            ByteArrayOutputStream typeIds = new ByteArrayOutputStream(size);
            // Starting at -1 makes every delta positive, including a first placement at slot zero.
            int previous = -1;
            for (int i = s.cells.nextSetBit(0); i >= 0; i = s.cells.nextSetBit(i + 1)) {
                writeVarInt(deltas, i - previous);
                writeVarInt(typeIds, s.types[i] & 0xffff);
                previous = i;
            }
            part.setByteArray("deltas", deltas.toByteArray());
            part.setByteArray("typeIds", typeIds.toByteArray());
            data.appendTag(part);
        }
        n.setTag("types", names);
        n.setTag("sections", data);
        return n;
    }

    void load(NBTTagCompound n) {
        if (n.getByte("format") != FORMAT) return;
        NBTTagList names = n.getTagList("types", 8), data = n.getTagList("sections", 10);
        if (names.tagCount() > 65536) return;
        for (int i = 0; i < names.tagCount(); i++) {
            String name = names.getStringTagAt(i);
            palette.add(name);
            types.put(name, i);
        }
        for (int i = 0; i < data.tagCount(); i++) {
            Section s = readSection(data.getCompoundTagAt(i), palette.size());
            if (s == null) continue;
            long key = key(s.x * 16, s.y * 16, s.z * 16);
            if (sections.containsKey(key)) continue;
            sections.put(key, s);
            count += s.cells.cardinality();
            task.store.watch(task.dim, key, this);
        }
    }

    private static Section readSection(NBTTagCompound part, int paletteSize) {
        int[] pos = part.getIntArray("section");
        ByteBuffer deltas = ByteBuffer.wrap(part.getByteArray("deltas"));
        ByteBuffer typeIds = ByteBuffer.wrap(part.getByteArray("typeIds"));
        if (pos.length != 3 || !deltas.hasRemaining() || !typeIds.hasRemaining()) return null;
        Section section = new Section(pos[0], pos[1], pos[2]);
        int cell = -1;
        while (deltas.hasRemaining()) {
            int delta = readVarInt(deltas);
            int type = readVarInt(typeIds);
            if (delta <= 0 || delta > 4095 - cell || type < 0 || type >= paletteSize) return null;
            cell += delta;
            section.cells.set(cell);
            section.types[cell] = (short) type;
        }
        return typeIds.hasRemaining() ? null : section;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while (value >= 128) {
            out.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(ByteBuffer in) {
        // Neither section deltas nor unsigned 16-bit palette IDs can exceed three bytes.
        int value = 0;
        for (int shift = 0; shift <= 14 && in.hasRemaining(); shift += 7) {
            int b = in.get() & 0xff;
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return value;
        }
        return -1;
    }
}
