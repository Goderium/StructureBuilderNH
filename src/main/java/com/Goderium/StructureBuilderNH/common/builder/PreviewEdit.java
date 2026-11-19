package com.Goderium.StructureBuilderNH.common.builder;

import com.Goderium.StructureBuilderNH.StructureBuilderNH;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.*;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The wire request identifies what was displayed. The server checks the task, revision and sight line again. */
public final class PreviewEdit implements IMessage {
    public static final int MAX_STEPS = 64;
    private static final SimpleNetworkWrapper NET = NetworkRegistry.INSTANCE.newSimpleChannel("sbnh.pick2");
    private static final Map<EntityPlayerMP, PreviewEdit> pending = new ConcurrentHashMap<>();
    private UUID taskId;
    private int revision, x, y, z, steps;
    private long request;
    private boolean group;

    public static void register() {
        NET.registerMessage(Handler.class, PreviewEdit.class, 0, Side.SERVER);
        NET.registerMessage(ResultHandler.class, Result.class, 1, Side.CLIENT);
    }

    public static void send(UUID taskId, int revision, int x, int y, int z, int steps, boolean group, long request) {
        PreviewEdit edit = new PreviewEdit();
        edit.taskId = taskId;
        edit.revision = revision;
        edit.x = x;
        edit.y = y;
        edit.z = z;
        edit.group = group;
        edit.steps = steps;
        edit.request = request;
        NET.sendToServer(edit);
    }

    @Override
    public void toBytes(ByteBuf b) {
        b.writeLong(taskId.getMostSignificantBits());
        b.writeLong(taskId.getLeastSignificantBits());
        b.writeInt(revision);
        b.writeInt(x);
        b.writeInt(y);
        b.writeInt(z);
        b.writeBoolean(group);
        b.writeInt(steps);
        b.writeLong(request);
    }

    @Override
    public void fromBytes(ByteBuf b) {
        taskId = new UUID(b.readLong(), b.readLong());
        revision = b.readInt();
        x = b.readInt();
        y = b.readInt();
        z = b.readInt();
        group = b.readBoolean();
        steps = b.readInt();
        request = b.readLong();
    }

    public static final class Result implements IMessage {
        public UUID taskId;
        public long request;
        public int baseRevision, revision;
        public boolean accepted;

        public Result() {}

        Result(PreviewEdit edit, int revision, boolean accepted) {
            taskId = edit.taskId;
            request = edit.request;
            baseRevision = edit.revision;
            this.revision = revision;
            this.accepted = accepted;
        }

        @Override
        public void toBytes(ByteBuf b) {
            b.writeLong(taskId.getMostSignificantBits());
            b.writeLong(taskId.getLeastSignificantBits());
            b.writeLong(request);
            b.writeInt(baseRevision);
            b.writeInt(revision);
            b.writeBoolean(accepted);
        }

        @Override
        public void fromBytes(ByteBuf b) {
            taskId = new UUID(b.readLong(), b.readLong());
            request = b.readLong();
            baseRevision = b.readInt();
            revision = b.readInt();
            accepted = b.readBoolean();
        }
    }

    public static final class ResultHandler implements IMessageHandler<Result, IMessage> {
        @Override
        public IMessage onMessage(Result message, MessageContext ctx) {
            StructureBuilderNH.proxy.previewEditResult(message);
            return null;
        }
    }

    public static final class Handler implements IMessageHandler<PreviewEdit, IMessage> {
        @Override
        public IMessage onMessage(PreviewEdit message, MessageContext ctx) {
            pending.putIfAbsent(ctx.getServerHandler().playerEntity, message);
            return null;
        }
    }

    static void drain(BuilderTasks store) {
        pending.forEach((player, edit) -> {
            if (pending.remove(player, edit)) edit.apply(store, player);
        });
    }

    static void clear() {
        pending.clear();
    }

    /** Native use packets must not open a second GUI for an edit handled by the client C2S path. */
    public static boolean consumeUse(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP) || player.worldObj == null || player.worldObj.isRemote) return false;
        return pick(BuilderTasks.get(), (EntityPlayerMP) player) != null;
    }

    private static final class Hit {
        final BuildTask task;
        final ChunkCoordinates position;

        Hit(BuildTask task, ChunkCoordinates position) {
            this.task = task;
            this.position = position;
        }
    }

    private static Hit pick(BuilderTasks store, EntityPlayerMP player) {
        if (player.isDead || player.worldObj == null || player.worldObj.isRemote
                || !player.playerNetServerHandler.netManager.isChannelOpen()
                || player.openContainer != player.inventoryContainer) return null;
        ItemStack held = player.getHeldItem();
        if (held == null || held.getItem() != BuilderItem.ITEM || !held.hasTagCompound()
                || held.getTagCompound().getString("tool").isEmpty()) return null;
        List<BuildTask> candidates = new ArrayList<>();
        for (BuildTask task : store.list(player, held)) {
            if (task.dim == player.dimension && task.editable()
                    && (task.previewLayers & PreviewStream.SHELL) != 0 && task.plan != null && task.plan.ready)
                candidates.add(task);
        }
        if (candidates.isEmpty()) return null;
        String selected = held.getTagCompound().getString("selected");
        candidates.sort(Comparator.comparing((BuildTask task) -> !task.id.equals(selected)).thenComparing(task -> task.id));
        ChunkCoordinates hit = PreviewAim.first(player, (px, py, pz) -> {
            for (BuildTask task : candidates) if (task.materialSelectable(px, py, pz)) return true;
            return false;
        });
        if (hit == null) return null;
        for (BuildTask task : candidates)
            if (task.materialSelectable(hit.posX, hit.posY, hit.posZ)) return new Hit(task, hit);
        return null;
    }

    private void apply(BuilderTasks store, EntityPlayerMP player) {
        if (taskId == null) return;
        int actualRevision = revision;
        boolean accepted = false;
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() == BuilderItem.ITEM && held.hasTagCompound()
                && !held.getTagCompound().getString("tool").isEmpty()) {
            for (BuildTask task : store.list(player, held))
                if (task.id.equals(taskId.toString())) { actualRevision = task.revision; break; }
        }
        try {
            if (request <= 0 || (group ? steps != 0 : steps == 0 || steps < -MAX_STEPS || steps > MAX_STEPS)) return;
            Hit picked = pick(store, player);
            if (picked == null || !picked.task.id.equals(taskId.toString()) || picked.task.revision != revision
                    || x != picked.position.posX || y != picked.position.posY || z != picked.position.posZ) return;
            BuildTask task = picked.task;
            BuildTask.MaterialSelection selection = task.selection(x, y, z);
            if (selection == null || !selection.supported || group && !selection.group.groupSelectable()) return;
            int count = selection.group.optionCount();
            int displayed = task.previewIndex(selection, player.worldObj);
            if (displayed < 0 || displayed >= count) return;
            int option = group ? displayed : (int) Math.floorMod((long) displayed + steps, (long) count);
            if (task.setMaterialSelection(x, y, z, option, group)) {
                store.select(player.getHeldItem(), task);
                player.inventory.markDirty();
                player.inventoryContainer.detectAndSendChanges();
                actualRevision = task.revision;
                accepted = true;
            }
        } finally {
            if (player.playerNetServerHandler.netManager.isChannelOpen())
                NET.sendTo(new Result(this, actualRevision, accepted), player);
        }
    }
}
