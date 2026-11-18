package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.StructureBuilderNH;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import java.util.Set;

/** A small moving window over the chunks a running task has needed to load itself. */
final class TaskChunks {
    private static final int WINDOW = 4;
    private Ticket ticket;
    private long nextTicketAttempt;

    boolean load(BuildTask task, WorldServer world, int cx, int cz) {
        if (ticket == null) {
            long now = world.getTotalWorldTime();
            if (now < nextTicketAttempt) return false;
            ticket = ForgeChunkManager.requestTicket(StructureBuilderNH.instance, world, ForgeChunkManager.Type.NORMAL);
            if (ticket == null) {
                nextTicketAttempt = now + 20;
                return false;
            }
        }
        if (!task.store.claimChunkLoad()) return false;
        ChunkCoordIntPair next = new ChunkCoordIntPair(cx, cz);
        // Keep the controller in the window when possible: each placement batch rechecks it.
        ForgeChunkManager.reorderChunk(ticket, new ChunkCoordIntPair(task.x >> 4, task.z >> 4));
        Set<ChunkCoordIntPair> held = ticket.getChunkList();
        if (!held.contains(next)) {
            int depth = ticket.getChunkListDepth();
            if (held.size() >= (depth > 0 ? Math.min(WINDOW, depth) : WINDOW)) {
                ChunkCoordIntPair oldest = held.iterator().next();
                ForgeChunkManager.unforceChunk(ticket, oldest);
                dropIfUnused(world, oldest);
            }
            ForgeChunkManager.forceChunk(ticket, next);
        }
        world.theChunkProviderServer.loadChunk(cx, cz);
        return world.theChunkProviderServer.chunkExists(cx, cz);
    }

    void release() {
        if (ticket != null) {
            WorldServer world = (WorldServer) ticket.world;
            if (DimensionManager.getWorld(world.provider.dimensionId) == world) {
                Set<ChunkCoordIntPair> held = ticket.getChunkList();
                ForgeChunkManager.releaseTicket(ticket);
                for (ChunkCoordIntPair pos : held) dropIfUnused(world, pos);
            }
            ticket = null;
        }
        nextTicketAttempt = 0;
    }

    private static void dropIfUnused(WorldServer world, ChunkCoordIntPair pos) {
        if (!world.getPlayerManager().func_152621_a(pos.chunkXPos, pos.chunkZPos)
                && !world.getPersistentChunks().containsKey(pos))
            world.theChunkProviderServer.dropChunk(pos.chunkXPos, pos.chunkZPos);
    }
}
