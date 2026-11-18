package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.util.Bounds;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.*;
import cpw.mods.fml.relauncher.Side;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;

import java.util.*;

/** A paced stream of compact section masks, independent of the lifetime of the item GUI. */
public final class PreviewStream {
    public static final int BOX = 1, SHELL = 2, HATCH = 4;
    private static final SimpleNetworkWrapper NET =
            NetworkRegistry.INSTANCE.newSimpleChannel("sbnh.preview4");
    private static final Map<String, PreviewStream> views = new HashMap<>();
    private final UUID taskId;
    private final EntityPlayerMP viewer;
    private Blueprint sent;
    private Bounds bounds;
    private int cursor, lastLayers;
    private ChunkCoordinates lastIssue;
    private boolean lastController;
    private final Set<Integer> changedSections = new LinkedHashSet<>();
    private boolean preferUpdate = true;

    private PreviewStream(BuildTask task, EntityPlayerMP viewer) {
        this.taskId = UUID.fromString(task.id);
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
        return p;
    }

    static void stop(BuildTask task, EntityPlayerMP player) {
        PreviewStream view = views.remove(task.id);
        if (view != null && view.viewer == player) NET.sendTo(view.packet(0), player);
    }

    private static final class Budget {
        int scans = 128, sections = 8;
    }

    static void changed(BuildTask task, Blueprint.Section section) {
        PreviewStream view = views.get(task.id);
        if (view != null && view.sent == task.plan && section.id < view.cursor)
            view.changedSections.add(section.id);
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
        boolean reset = sent != plan;
        if (reset || lastLayers != task.previewLayers || !plan.bounds.equals(bounds)) {
            if (reset) {
                cursor = 0;
                changedSections.clear();
            }
            Packet pkt = packet(1);
            pkt.reset = reset;
            pkt.layers = task.previewLayers;
            pkt.bounds = plan.bounds;
            NET.sendTo(pkt, viewer);
            sent = plan;
            lastLayers = task.previewLayers;
            bounds = plan.bounds;
        }
        if (!plan.ready || (lastLayers & (SHELL | HATCH)) == 0) return;
        Iterator<Integer> updates = changedSections.iterator();
        while (budget.scans > 0 && budget.sections > 0
                && (cursor < plan.sections.size() || updates.hasNext())) {
            boolean update = updates.hasNext() && (preferUpdate || cursor == plan.sections.size());
            Blueprint.Section section;
            if (update) {
                section = plan.sections.get(updates.next());
                updates.remove();
            } else section = plan.sections.get(cursor++);
            preferUpdate = !update;
            budget.scans--;
            for (long bits : section.solid) {
                if (bits == 0) continue;
                Packet pkt = packet(update ? 3 : 2);
                pkt.x = section.x;
                pkt.y = section.y;
                pkt.z = section.z;
                pkt.solid = section.solid;
                pkt.hatch = section.hatch;
                if (!update) {
                    int count = 0;
                    for (long bitsInWord : section.hatch) count += Long.bitCount(bitsInWord);
                    pkt.dots = new byte[count];
                    int j = 0;
                    for (int w = 0; w < 64; w++)
                        for (long h = section.hatch[w]; h != 0; h &= h - 1)
                            pkt.dots[j++] =
                                    (byte)
                                            section.cells[w * 64 + Long.numberOfTrailingZeros(h)]
                                                    .dots;
                }
                pkt.confirmed = section.confirmed.clone();
                NET.sendTo(pkt, viewer);
                budget.sections--;
                break;
            }
        }
    }

    static void clear() {
        views.clear();
    }

    public static final class Packet implements IMessage {
        public UUID id;
        public int type, dim, layers, x, y, z;
        public boolean reset;
        public Bounds bounds;
        public long[] solid, hatch, confirmed;
        public byte[] dots;
        public ChunkCoordinates issue;

        public void toBytes(ByteBuf b) {
            b.writeByte(type);
            b.writeLong(id.getMostSignificantBits());
            b.writeLong(id.getLeastSignificantBits());
            if (type == 0) return;
            b.writeInt(dim);
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
                b.writeInt(bounds.minX);
                b.writeInt(bounds.minY);
                b.writeInt(bounds.minZ);
                b.writeInt(bounds.maxX);
                b.writeInt(bounds.maxY);
                b.writeInt(bounds.maxZ);
            } else {
                b.writeInt(x);
                b.writeInt(y);
                b.writeInt(z);
                if (type == 2) {
                    writeMask(b, solid);
                    writeMask(b, hatch);
                    b.writeBytes(dots);
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
                bounds =
                        new Bounds(
                                b.readInt(),
                                b.readInt(),
                                b.readInt(),
                                b.readInt(),
                                b.readInt(),
                                b.readInt());
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
