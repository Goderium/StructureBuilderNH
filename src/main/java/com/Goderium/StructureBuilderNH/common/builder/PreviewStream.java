package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.util.Bounds;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.*;
import cpw.mods.fml.relauncher.Side;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;

import java.util.*;

/** A paced stream of compact section masks, independent of the lifetime of the item GUI. */
public final class PreviewStream {
    public static final int BOX = 1, SHELL = 2, HATCH = 4;
    private static final SimpleNetworkWrapper NET =
            NetworkRegistry.INSTANCE.newSimpleChannel("sbnh.preview");
    private static final Map<String, PreviewStream> views = new HashMap<>();
    private final BuildTask task;
    private Blueprint sent;
    private Bounds bounds;
    private int cursor, layers, lastLayers = -1;

    private PreviewStream(BuildTask task) {
        this.task = task;
    }

    public static void register() {
        NET.registerMessage(Handler.class, Packet.class, 0, Side.CLIENT);
    }

    static boolean active(EntityPlayerMP p, BuildTask t) {
        PreviewStream v = views.get(t.owner);
        return v != null && v.task == t;
    }

    static int layers(BuildTask t) {
        PreviewStream v = views.get(t.owner);
        return v != null && v.task == t ? v.layers : 0;
    }

    static void toggle(EntityPlayerMP p, BuildTask t, int bit) {
        PreviewStream v = views.get(t.owner);
        if (v == null || v.task != t) {
            v = new PreviewStream(t);
            views.put(t.owner, v);
        }
        v.layers ^= bit;
        if (v.layers == 0) stop(t.owner, p);
    }

    static void stop(String owner, EntityPlayerMP player) {
        if (views.remove(owner) != null && player != null) NET.sendTo(new Packet(), player);
    }

    static void tick(Map<String, EntityPlayerMP> players) {
        for (String owner : new ArrayList<>(views.keySet())) {
            PreviewStream v = views.get(owner);
            EntityPlayerMP p = players.get(owner);
            if (p == null
                    || p.dimension != v.task.dim
                    || v.task.state == BuildTask.State.DONE
                    || v.task.state == BuildTask.State.REFUND) {
                stop(owner, p);
                continue;
            }
            Blueprint plan = v.task.plan;
            if (plan == null) {
                if (v.sent != null) {
                    NET.sendTo(new Packet(), p);
                    v.sent = null;
                }
                continue;
            }
            boolean reset = v.sent != plan;
            if (reset || v.lastLayers != v.layers || !plan.bounds.equals(v.bounds)) {
                if (reset) v.cursor = 0;
                Packet pkt = new Packet();
                pkt.type = 1;
                pkt.dim = p.dimension;
                pkt.reset = reset;
                pkt.layers = v.layers;
                pkt.bounds = plan.bounds;
                NET.sendTo(pkt, p);
                v.sent = plan;
                v.lastLayers = v.layers;
                v.bounds = plan.bounds;
            }
            if (!plan.ready || (v.layers & (SHELL | HATCH)) == 0) continue;
            for (int i = 0, sentSections = 0;
                    i < 128 && sentSections < 8 && v.cursor < plan.sections.size();
                    i++, v.cursor++) {
                Blueprint.Section s = plan.sections.get(v.cursor);
                boolean visible = false;
                for (long bits : s.solid)
                    if (bits != 0) {
                        visible = true;
                        break;
                    }
                if (!visible) continue;
                sentSections++;
                Packet pkt = new Packet();
                pkt.type = 2;
                pkt.dim = p.dimension;
                pkt.x = s.x;
                pkt.y = s.y;
                pkt.z = s.z;
                pkt.solid = s.solid;
                pkt.hatch = s.hatch;
                NET.sendTo(pkt, p);
            }
        }
    }

    static void clear() {
        views.clear();
    }

    public static final class Packet implements IMessage {
        public int type, dim, layers, x, y, z;
        public boolean reset;
        public Bounds bounds;
        public long[] solid, hatch;

        public void toBytes(ByteBuf b) {
            b.writeByte(type);
            if (type == 0) return;
            b.writeInt(dim);
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
                writeMask(b, solid);
                writeMask(b, hatch);
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
            if (type == 0) return;
            dim = b.readInt();
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
                solid = readMask(b);
                hatch = readMask(b);
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
