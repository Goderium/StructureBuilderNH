package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.common.builder.PreviewEdit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** One acknowledged edit at a time; queued wheel deltas stay attached to their original voxel. */
final class PreviewEditQueue {
    private static final int MAX_OPERATIONS = 32, MAX_ACCUMULATED_STEPS = 4096;
    private static final long TIMEOUT = 2_000_000_000L;

    interface Transport {
        boolean aimed(Target target);
        void send(Target target, int revision, int steps, boolean group, long request);
    }

    static final class Target {
        final UUID task;
        final int revision, dimension, x, y, z;
        final String tool;
        final boolean groupSelectable;

        Target(UUID task, int revision, int dimension, String tool, int x, int y, int z, boolean groupSelectable) {
            this.task = task;
            this.revision = revision;
            this.dimension = dimension;
            this.tool = tool;
            this.x = x; this.y = y; this.z = z;
            this.groupSelectable = groupSelectable;
        }

        boolean same(Target other) {
            return other != null && task.equals(other.task) && tool.equals(other.tool) && dimension == other.dimension
                    && x == other.x && y == other.y && z == other.z;
        }
    }

    private static final class Operation {
        final Target target;
        final boolean group;
        int steps;

        Operation(Target target, int steps, boolean group) {
            this.target = target;
            this.steps = steps;
            this.group = group;
        }
    }

    private static final class Flight {
        final Target target;
        final int revision;
        final long request, deadline;

        Flight(Target target, int revision, long request, long now) {
            this.target = target;
            this.revision = revision;
            this.request = request;
            deadline = now + TIMEOUT;
        }
    }

    private final Transport transport;
    private final Deque<Operation> pending = new ArrayDeque<>();
    private final Deque<PreviewEdit.Result> replies = new ArrayDeque<>();
    private final Map<UUID, Integer> revisions = boundedVersions(), blocked = boundedVersions();
    private Flight flight;
    private Object world;
    private String tool;
    private int dimension;
    private long sequence;

    PreviewEditQueue(Transport transport) { this.transport = transport; }

    private static Map<UUID, Integer> boundedVersions() {
        return new LinkedHashMap<UUID, Integer>(16, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Integer> entry) { return size() > 64; }
        };
    }

    void context(Object world, String tool, int dimension) {
        if (this.world != world || !Objects.equals(this.tool, tool) || this.dimension != dimension) {
            clear();
            this.world = world;
            this.tool = tool;
            this.dimension = dimension;
        }
    }

    void clear() {
        pending.clear(); revisions.clear(); blocked.clear(); flight = null;
        synchronized (replies) { replies.clear(); }
        world = null; tool = null;
    }

    void cycle(Target target, int steps, long now) {
        tick(now);
        if (steps == 0 || !observe(target)) return;
        Operation last = pending.peekLast();
        if (last != null && !last.group && last.target.same(target)) {
            last.steps = (int) Math.max(-MAX_ACCUMULATED_STEPS,
                    Math.min(MAX_ACCUMULATED_STEPS, (long) last.steps + steps));
            if (last.steps == 0) pending.removeLast();
        } else {
            trim();
            pending.addLast(new Operation(target, Math.max(-MAX_ACCUMULATED_STEPS, Math.min(MAX_ACCUMULATED_STEPS, steps)), false));
        }
        tick(now);
    }

    void applyGroup(Target target, long now) {
        tick(now);
        if (!target.groupSelectable || !observe(target)) return;
        Operation last = pending.peekLast();
        if (last == null || !last.group || !last.target.same(target)) {
            trim();
            pending.addLast(new Operation(target, 0, true));
        }
        tick(now);
    }

    private void trim() {
        // Bound stale targets; removing one never redirects its steps to another voxel.
        if (pending.size() == MAX_OPERATIONS) pending.removeFirst();
    }

    private boolean observe(Target target) {
        Integer rejected = blocked.get(target.task);
        if (rejected != null) {
            if (target.revision < rejected) return false;
            blocked.remove(target.task);
        }
        Integer known = revisions.get(target.task);
        if (known == null) revisions.put(target.task, target.revision);
        else if (target.revision > known && (flight == null || !flight.target.task.equals(target.task))) {
            pending.removeIf(op -> op.target.task.equals(target.task) && op.target.revision < target.revision);
            revisions.put(target.task, target.revision);
        }
        return true;
    }

    void reply(PreviewEdit.Result result) {
        synchronized (replies) {
            if (replies.size() == 64) replies.removeFirst();
            replies.addLast(result);
        }
    }

    Target pendingAt(Predicate<Target> aimed) {
        for (Iterator<Operation> it = pending.descendingIterator(); it.hasNext();) {
            Target target = it.next().target;
            if (aimed.test(target)) return target;
        }
        return flight != null && aimed.test(flight.target) ? flight.target : null;
    }

    void tick(long now) {
        for (;;) {
            PreviewEdit.Result result;
            synchronized (replies) { result = replies.pollFirst(); }
            if (result == null) break;
            if (flight == null || result.request != flight.request || !flight.target.task.equals(result.taskId)
                    || result.baseRevision != flight.revision) continue;
            Target completed = flight.target;
            int expected = flight.revision;
            flight = null;
            if (result.accepted) {
                revisions.put(completed.task, result.revision);
            } else if (result.revision != expected) {
                revisions.put(completed.task, result.revision);
                blocked.put(completed.task, result.revision);
                pending.removeIf(op -> op.target.task.equals(completed.task) && op.target.revision < result.revision);
            } else {
                pending.removeIf(op -> op.target.same(completed));
            }
        }
        if (flight != null && now >= flight.deadline) {
            UUID stale = flight.target.task;
            pending.removeIf(op -> op.target.task.equals(stale));
            revisions.remove(stale);
            flight = null;
        }
        if (flight != null) return;
        while (!pending.isEmpty()) {
            Operation operation = pending.peekFirst();
            Target target = operation.target;
            if (!transport.aimed(target) || !observe(target)) { pending.removeFirst(); continue; }
            int steps = operation.group ? 0 : Math.max(-PreviewEdit.MAX_STEPS, Math.min(PreviewEdit.MAX_STEPS, operation.steps));
            operation.steps -= steps;
            if (operation.group || operation.steps == 0) pending.removeFirst();
            int revision = revisions.getOrDefault(target.task, target.revision);
            flight = new Flight(target, revision, ++sequence, now);
            transport.send(target, revision, steps, operation.group, flight.request);
            return;
        }
    }
}
