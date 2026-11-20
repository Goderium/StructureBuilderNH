package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.StructureBuilderNH;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import cpw.mods.fml.common.network.simpleimpl.*;
import cpw.mods.fml.relauncher.Side;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Capture the original hit on press. Movement while held is local; release commits once on the
 * server tick.
 */
public final class PreviewDragMessage implements IMessage {
    public static final int PRESS = 0, TAP = 1, DROP = 2, CANCEL = 3;
    private static final Map<EntityPlayerMP, Deque<PreviewDragMessage>> pending =
            new ConcurrentHashMap<>();
    private static final Map<EntityPlayerMP, PreviewDragMessage> grips = new HashMap<>();
    private UUID id;
    private long sequence;
    private int op, revision, x, y, z, pose;
    private boolean supportsMoveHandoff;
    private BuildTask task;
    private int slot;

    public PreviewDragMessage() {}

    public static void send(
            int op,
            UUID id,
            long sequence,
            int revision,
            ChunkCoordinates at,
            ExtendedFacing facing) {
        PreviewDragMessage m = new PreviewDragMessage();
        m.op = op;
        m.id = id;
        m.sequence = sequence;
        m.revision = revision;
        if (at != null) {
            m.x = at.posX;
            m.y = at.posY;
            m.z = at.posZ;
        }
        if (facing != null) m.pose = facing.getIndex();
        PreviewEdit.NET.sendToServer(m);
    }

    static void register() {
        PreviewEdit.NET.registerMessage(Handler.class, PreviewDragMessage.class, 3, Side.SERVER);
        PreviewEdit.NET.registerMessage(Reply.class, Rejection.class, 4, Side.CLIENT);
    }

    @Override
    public void toBytes(ByteBuf b) {
        b.writeByte(op);
        b.writeLong(id.getMostSignificantBits());
        b.writeLong(id.getLeastSignificantBits());
        b.writeLong(sequence);
        b.writeInt(revision);
        if (op == DROP) {
            b.writeInt(x);
            b.writeInt(y);
            b.writeInt(z);
            b.writeByte(pose);
            b.writeBoolean(true);
        }
    }

    @Override
    public void fromBytes(ByteBuf b) {
        op = b.readUnsignedByte();
        id = new UUID(b.readLong(), b.readLong());
        sequence = b.readLong();
        revision = b.readInt();
        if (op == DROP) {
            x = b.readInt();
            y = b.readInt();
            z = b.readInt();
            pose = b.readUnsignedByte();
            // Older clients cannot decode the move handoff frames on the projection stream.
            supportsMoveHandoff = b.isReadable() && b.readBoolean();
        }
    }

    public static final class Handler implements IMessageHandler<PreviewDragMessage, IMessage> {
        @Override
        public IMessage onMessage(PreviewDragMessage m, MessageContext ctx) {
            if (m.op <= CANCEL)
                pending.compute(
                        ctx.getServerHandler().playerEntity,
                        (p, q) -> {
                            if (q == null) q = new ArrayDeque<>();
                            if (q.size() < 12) q.addLast(m);
                            return q;
                        });
            return null;
        }
    }

    public static final class Rejection implements IMessage {
        private UUID id;
        private long sequence;

        @Override
        public void toBytes(ByteBuf b) {
            b.writeLong(id.getMostSignificantBits());
            b.writeLong(id.getLeastSignificantBits());
            b.writeLong(sequence);
        }

        @Override
        public void fromBytes(ByteBuf b) {
            id = new UUID(b.readLong(), b.readLong());
            sequence = b.readLong();
        }
    }

    public static final class Reply implements IMessageHandler<Rejection, IMessage> {
        @Override
        public IMessage onMessage(Rejection m, MessageContext ctx) {
            StructureBuilderNH.proxy.rejectDrag(m.id, m.sequence);
            return null;
        }
    }

    static boolean holding(EntityPlayerMP p) {
        return grips.containsKey(p);
    }

    static boolean holding(EntityPlayerMP p, BuildTask task) {
        PreviewDragMessage grip = grips.get(p);
        return grip != null && grip.task == task;
    }

    static void clear() {
        pending.clear();
        grips.clear();
    }

    private boolean valid(EntityPlayerMP p, BuilderTasks store) {
        ItemStack held = p.getHeldItem();
        return !p.isDead
                && p.playerNetServerHandler.netManager.isChannelOpen()
                && p.openContainer == p.inventoryContainer
                && p.dimension == task.dim
                && p.inventory.currentItem == slot
                && held != null
                && held.getItem() == BuilderItem.ITEM
                && held.hasTagCompound()
                && task.tool.equals(held.getTagCompound().getString("tool"))
                && store.jobs.get(task.id) == task
                && task.revision == revision
                && task.movable() && p.worldObj.isAirBlock(task.x, task.y, task.z);
    }

    static void drain(BuilderTasks store) {
        grips.entrySet().removeIf(e -> !e.getValue().valid(e.getKey(), store));
        for (EntityPlayerMP p : pending.keySet()) {
            Deque<PreviewDragMessage> batch = pending.remove(p);
            if (batch != null && p.playerNetServerHandler.netManager.isChannelOpen())
                for (PreviewDragMessage m : batch) m.apply(p, store);
        }
    }

    private void reject(EntityPlayerMP player) {
        Rejection reply = new Rejection();
        reply.id = id;
        reply.sequence = sequence;
        PreviewEdit.NET.sendTo(reply, player);
    }

    private void apply(EntityPlayerMP p, BuilderTasks store) {
        if (op == PRESS) {
            task = PreviewEdit.pickHost(store, p, id, revision);
            if (task == null || !task.movable() || !p.worldObj.isAirBlock(task.x, task.y, task.z)) {
                reject(p);
                return;
            }
            slot = p.inventory.currentItem;
            grips.put(p, this);
            return;
        }
        PreviewDragMessage grip = grips.get(p);
        if (grip == null || grip.sequence != sequence || !id.equals(grip.id)) {
            if (op != CANCEL) reject(p);
            return;
        }
        grips.remove(p);
        if (op == CANCEL) return;
        if (!grip.valid(p, store)) {
            reject(p);
            return;
        }
        BuildTask t = grip.task;
        if (op == TAP) {
            if (p.getDistanceSq(t.x + .5, t.y + .5, t.z + .5) > 15 * 15) {
                reject(p);
                return;
            }
            PreviewEdit.TaskAction.openMarker(store, p, t, new ChunkCoordinates(t.x, t.y, t.z));
            return;
        }
        Vec3 eye = Vec3.createVectorHelper(p.posX, p.posY + p.getEyeHeight(), p.posZ);
        Vec3 dest = Vec3.createVectorHelper(x + .5, y + .5, z + .5);
        boolean close = eye.distanceTo(dest) <= 13;
        MovingObjectPosition hit =
                close ? p.worldObj.rayTraceBlocks(eye, dest, false, true, false) : null;
        if (!close
                || hit != null && (hit.blockX != x || hit.blockY != y || hit.blockZ != z)
                || pose >= ExtendedFacing.VALUES.length
                || !t.movePreview(p.worldObj, x, y, z, ExtendedFacing.VALUES[pose])) {
            p.addChatMessage(new ChatComponentTranslation("structurebuildernh.drag.denied"));
            reject(p);
        } else {
            if (supportsMoveHandoff && t.revision != grip.revision) PreviewStream.acceptMove(t, p, sequence);
            store.select(p.getHeldItem(), t);
            p.inventory.markDirty();
            p.inventoryContainer.detectAndSendChanges();
        }
    }
}
