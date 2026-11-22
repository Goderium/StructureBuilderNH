package com.Goderium.StructureBuilderNH.client;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.LongHashMap;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Observes block and chunk changes only where a client projection needs to sample the world. */
final class ProjectionOccupancy implements IWorldAccess {
    interface MaterialSink {
        void record(int slot, int blockId, int metadata);
    }

    private static final class Column {
        final int x, z;
        final List<Set<Runnable>> listeners = new ArrayList<>(Collections.nCopies(16, null));
        int watched, dirty;

        Column(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private final World world;
    private final LongHashMap columnIndex = new LongHashMap();
    private final Set<Column> columns = new HashSet<>();
    private final Set<Column> pending = new HashSet<>();
    private final Set<Column> awaitingChunkTick = new HashSet<>();
    private long lastChunkTick = Long.MIN_VALUE;
    private boolean closed;

    ProjectionOccupancy(World world) {
        if (!world.isRemote) throw new IllegalArgumentException("Expected a client world");
        this.world = world;
        world.addWorldAccess(this);
    }

    void watch(int sx, int sy, int sz, Runnable listener) {
        watch(sx, sy, sz, listener, true);
    }

    /** The caller samples on registration. Adding a second tile does not invalidate its neighbours. */
    void watchChanges(int sx, int sy, int sz, Runnable listener) {
        watch(sx, sy, sz, listener, false);
    }

    private synchronized void watch(int sx, int sy, int sz, Runnable listener, boolean initialNotification) {
        if (closed || sy < 0 || sy >= 16) return;
        long key = ChunkCoordIntPair.chunkXZ2Int(sx, sz);
        Column column = (Column) columnIndex.getValueByKey(key);
        if (column == null) {
            column = new Column(sx, sz);
            columnIndex.add(key, column);
            columns.add(column);
            if (!initialNotification) {
                // watchChanges callers already sample this world state. Only an unticked
                // column needs another observation before normal block notifications begin.
                Chunk chunk = world.getChunkFromChunkCoords(sx, sz);
                if (!chunk.isEmpty() && !chunk.func_150802_k()) awaitingChunkTick.add(column);
            }
        }
        Set<Runnable> section = column.listeners.get(sy);
        if (section == null) {
            section = Collections.newSetFromMap(new IdentityHashMap<>());
            column.listeners.set(sy, section);
            column.watched |= 1 << sy;
        }
        if (section.add(listener) && initialNotification) mark(column, 1 << sy);
    }

    synchronized void unwatch(int sx, int sy, int sz, Runnable listener) {
        if (sy < 0 || sy >= 16) return;
        long key = ChunkCoordIntPair.chunkXZ2Int(sx, sz);
        Column column = (Column) columnIndex.getValueByKey(key);
        if (column == null) return;
        Set<Runnable> section = column.listeners.get(sy);
        if (section == null || !section.remove(listener) || !section.isEmpty()) return;
        column.listeners.set(sy, null);
        column.watched &= ~(1 << sy);
        column.dirty &= column.watched;
        if (column.dirty == 0) pending.remove(column);
        if (column.watched == 0) {
            columnIndex.remove(key);
            columns.remove(column);
            awaitingChunkTick.remove(column);
        }
    }

    private void mark(Column column, int sections) {
        int added = sections & column.watched & ~column.dirty;
        if (added == 0) return;
        if (column.dirty == 0) pending.add(column);
        column.dirty |= added;
    }

    @Override
    public synchronized void markBlockForUpdate(int x, int y, int z) {
        if (y < 0 || y >= 256 || columns.isEmpty()) return;
        Column column = (Column) columnIndex.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x >> 4, z >> 4));
        if (column != null) mark(column, 1 << (y >> 4));
    }

    synchronized void chunkChanged(int sx, int sz) {
        Column column = (Column) columnIndex.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(sx, sz));
        if (column != null) mark(column, column.watched);
    }

    @Override
    public synchronized void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
        if (columns.isEmpty() || y2 < 0 || y1 >= 256 || x1 > x2 || y1 > y2 || z1 > z2) return;
        int minY = Math.max(0, y1 >> 4), maxY = Math.min(15, y2 >> 4);
        int sections = ((2 << maxY) - 1) & (-1 << minY);
        int minX = x1 >> 4, maxX = x2 >> 4, minZ = z1 >> 4, maxZ = z2 >> 4;
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) <= columns.size()) {
            for (int sx = minX; sx <= maxX; sx++) for (int sz = minZ; sz <= maxZ; sz++) {
                Column column = (Column) columnIndex.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(sx, sz));
                if (column != null) mark(column, sections);
            }
        } else {
            for (Column column : columns)
                if (column.x >= minX && column.x <= maxX && column.z >= minZ && column.z <= maxZ)
                    mark(column, sections);
        }
    }

    /** Deliver at most once per listener. A listener can safely change its subscriptions. */
    void drain() {
        Set<Runnable> notify;
        synchronized (this) {
            if (pending.isEmpty() && awaitingChunkTick.isEmpty()) return;
            long now = world.getTotalWorldTime();
            if (!awaitingChunkTick.isEmpty() && lastChunkTick != now) {
                // Vanilla suppresses single-block notifications until the new chunk has ticked.
                for (Column column : awaitingChunkTick) mark(column, column.watched);
            }
            lastChunkTick = now;
            if (pending.isEmpty()) return;
            notify = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Column column : pending) {
                Chunk chunk = world.getChunkFromChunkCoords(column.x, column.z);
                if (!chunk.isEmpty() && !chunk.func_150802_k()) awaitingChunkTick.add(column);
                else awaitingChunkTick.remove(column);
                for (int bits = column.dirty; bits != 0; bits &= bits - 1)
                    notify.addAll(column.listeners.get(Integer.numberOfTrailingZeros(bits)));
                column.dirty = 0;
            }
            pending.clear();
        }
        for (Runnable listener : notify) listener.run();
    }

    /** Overwrite the 64 words with occupied projected slots, returning whether any word changed. */
    boolean read(int sx, int sy, int sz, long[] solid, long[] optional, long[] hatch, long[] result) {
        return read(sx, sy, sz, solid, optional, hatch, result, null);
    }

    boolean read(int sx, int sy, int sz, long[] solid, long[] optional, long[] hatch, long[] result,
        MaterialSink materials) {
        ExtendedBlockStorage storage = storage(sx, sy, sz);
        boolean changed = false;
        int startX = sx << 4, startY = sy << 4, startZ = sz << 4;
        for (int word = 0; word < 64; word++) {
            long nonAir = 0;
            if (storage != null) {
                long bits = maskWord(solid, word) | maskWord(optional, word) | maskWord(hatch, word);
                while (bits != 0) {
                    int slot = (word << 6) + Long.numberOfTrailingZeros(bits);
                    int x = startX + (slot & 15), y = startY + (slot >> 8), z = startZ + (slot >> 4 & 15);
                    if (occupied(storage, x, y, z, slot, materials)) nonAir |= 1L << (slot & 63);
                    bits &= bits - 1;
                }
            }
            changed |= result[word] != nonAir;
            result[word] = nonAir;
        }
        return changed;
    }

    private static long maskWord(long[] mask, int index) {
        return mask == null || mask.length == 0 ? 0 : mask[index];
    }

    boolean emptySection(int sx, int sy, int sz) {
        return storage(sx, sy, sz) == null;
    }

    boolean occupied(int x, int y, int z) {
        return occupied(x, y, z, 0, null);
    }

    boolean occupied(int x, int y, int z, int slot, MaterialSink materials) {
        ExtendedBlockStorage storage = storage(x >> 4, y >> 4, z >> 4);
        return storage != null && occupied(storage, x, y, z, slot, materials);
    }

    private ExtendedBlockStorage storage(int sx, int sy, int sz) {
        if (sy < 0 || sy >= 16) return null;
        // ChunkProviderClient returns a shared EmptyChunk for missing columns; chunkExists always returns true.
        Chunk chunk = world.getChunkFromChunkCoords(sx, sz);
        if (chunk.isEmpty()) return null;
        ExtendedBlockStorage storage = chunk.getBlockStorageArray()[sy];
        return storage == null || storage.isEmpty() ? null : storage;
    }

    private boolean occupied(ExtendedBlockStorage storage, int x, int y, int z, int slot, MaterialSink materials) {
        Block block = storage.getBlockByExtId(x & 15, y & 15, z & 15);
        if (block.isAir(world, x, y, z)) return false;
        if (materials != null) {
            int metadata = storage.getExtBlockMetadata(x & 15, y & 15, z & 15);
            materials.record(slot, Block.getIdFromBlock(block), metadata);
        }
        return true;
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        world.removeWorldAccess(this);
        for (Column column : columns) columnIndex.remove(ChunkCoordIntPair.chunkXZ2Int(column.x, column.z));
        columns.clear();
        pending.clear();
        awaitingChunkTick.clear();
    }

    // Vanilla calls this for light-value changes. They do not change the block occupying a slot.
    @Override public void markBlockForRenderUpdate(int x, int y, int z) {}
    @Override public void playSound(String name, double x, double y, double z, float volume, float pitch) {}
    @Override public void playSoundToNearExcept(EntityPlayer player, String name, double x, double y, double z, float volume, float pitch) {}
    @Override public void spawnParticle(String type, double x, double y, double z, double vx, double vy, double vz) {}
    @Override public void onEntityCreate(Entity entity) {}
    @Override public void onEntityDestroy(Entity entity) {}
    @Override public void playRecord(String name, int x, int y, int z) {}
    @Override public void broadcastSound(int sound, int x, int y, int z, int data) {}
    @Override public void playAuxSFX(EntityPlayer player, int sound, int x, int y, int z, int data) {}
    @Override public void destroyBlockPartially(int entity, int x, int y, int z, int stage) {}
    @Override public void onStaticEntitiesChanged() {}
}
