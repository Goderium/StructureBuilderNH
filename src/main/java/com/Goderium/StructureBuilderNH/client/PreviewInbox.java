package com.Goderium.StructureBuilderNH.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.Goderium.StructureBuilderNH.common.builder.PreviewStream;

/** Each task keeps network order and gives the next task a turn after one packet. */
final class PreviewInbox {
    private final Map<UUID, Deque<PreviewStream.Packet>> tasks = new HashMap<>();
    private final Deque<UUID> ready = new ArrayDeque<>();
    private volatile boolean waiting;

    synchronized void add(PreviewStream.Packet packet) {
        Deque<PreviewStream.Packet> task = tasks.get(packet.id);
        if (task == null) {
            task = new ArrayDeque<>();
            tasks.put(packet.id, task);
            ready.addLast(packet.id);
        } else if (packet.type == 0) {
            // Every earlier packet for a stopped view is obsolete, including partial sections.
            task = new ArrayDeque<>();
            tasks.put(packet.id, task);
        }
        task.addLast(packet);
        waiting = true;
    }

    boolean hasPackets() { return waiting; }

    synchronized PreviewStream.Packet poll() {
        UUID id = ready.pollFirst();
        if (id == null) return null;
        Deque<PreviewStream.Packet> task = tasks.get(id);
        PreviewStream.Packet packet = task.removeFirst();
        if (task.isEmpty()) tasks.remove(id);
        else ready.addLast(id);
        waiting = !ready.isEmpty();
        return packet;
    }

    synchronized void clear() {
        tasks.clear();
        ready.clear();
        waiting = false;
    }
}
