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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The wire request identifies what was displayed. The server checks the task, revision and sight line again. */
public final class PreviewEdit implements IMessage {
    public static final int MAX_STEPS = 64;
    static final SimpleNetworkWrapper NET = NetworkRegistry.INSTANCE.newSimpleChannel("sbnh.pick2");
    private static final Map<EntityPlayerMP, PreviewEdit> pending = new ConcurrentHashMap<>();
    private static final Map<EntityPlayerMP, TaskAction> actions = new ConcurrentHashMap<>();
    // MUI creates the server panel synchronously. The next ordinary terminal open has no picked cell.
    static final ThreadLocal<ChunkCoordinates> openedMarker = new ThreadLocal<>();
    private UUID taskId;
    private int revision, x, y, z, steps;
    private long request;
    private boolean group;

    public static void register() {
        PreviewDragMessage.register();
        NET.registerMessage(Handler.class, PreviewEdit.class, 0, Side.SERVER);
        NET.registerMessage(ResultHandler.class, Result.class, 1, Side.CLIENT);
        NET.registerMessage(TaskActionHandler.class, TaskAction.class, 2, Side.SERVER);
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
        PreviewDragMessage.drain(store);
        pending.forEach((player, edit) -> {
            if (pending.remove(player, edit)) edit.apply(store, player);
        });
        actions.forEach((player, action) -> {
            if (actions.remove(player, action)) action.apply(store, player);
        });
    }

    static void clear() {
        PreviewDragMessage.clear();
        pending.clear();
        actions.clear();
    }

    /** Native use packets must not open a second GUI for an edit handled by the client C2S path. */
    public static boolean consumeUse(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP) || player.worldObj == null || player.worldObj.isRemote) return false;
        EntityPlayerMP actor = (EntityPlayerMP) player;
        if (actor.openContainer != actor.inventoryContainer || PreviewDragMessage.holding(actor)) return true;
        BuilderTasks store = BuilderTasks.get();
        Hit hit = pick(store, actor);
        if (hit == null) return false;
        if (actor.isSneaking() && hit.owned) {
            TaskAction.openMarker(store, actor, hit.task, null);
            return true;
        }
        return hit.projected || hit.marker;
    }

    private static final class Hit {
        final BuildTask task;
        final ChunkCoordinates position;
        final boolean owned, projected, marker;

        Hit(BuildTask task, int x, int y, int z, boolean owned, boolean projected, boolean marker) {
            this.task = task;
            this.position = new ChunkCoordinates(x, y, z);
            this.owned = owned; this.projected = projected; this.marker = marker;
        }
    }

    private static Hit pick(BuilderTasks store, EntityPlayerMP player) {
        if (player.isDead || player.worldObj == null || player.worldObj.isRemote
                || !player.playerNetServerHandler.netManager.isChannelOpen()
                || player.openContainer != player.inventoryContainer) return null;
        ItemStack held = player.getHeldItem();
        if (held == null || held.getItem() != BuilderItem.ITEM || !held.hasTagCompound()) return null;
        String tool = held.getTagCompound().getString("tool"), selected = held.getTagCompound().getString("selected");
        if (tool.isEmpty()) return null;
        List<BuildTask> candidates = new ArrayList<>();
        String owner = player.getUniqueID().toString();
        for (BuildTask task : store.jobs.values())
            if (task.dim == player.dimension && owner.equals(task.owner) && !task.deleteRequested && task.target != null
                    && (tool.equals(task.tool) || PreviewStream.visibleTo(task, player))) candidates.add(task);
        if (candidates.isEmpty()) return null;
        Hit[] result = {null};
        PreviewAim.first(player, (x, y, z) -> {
            boolean air = player.worldObj.isAirBlock(x, y, z);
            for (BuildTask task : candidates) {
                boolean owned = tool.equals(task.tool);
                Hit best = result[0];
                if (best != null && !PreviewAim.prefer(owned, task.id, best.owned, best.task.id, selected)) continue;
                boolean roles = (task.previewLayers & PreviewStream.HATCH) != 0, shells = (task.previewLayers & PreviewStream.SHELL) != 0;
                boolean host = task.x == x && task.y == y && task.z == z;
                Blueprint.Cell cell = task.plan == null ? null : task.plan.cellAt(x, y, z);
                BuildTask.MaterialSelection optional = shells && cell != null && cell.air && cell.choice != null
                        ? task.selection(x, y, z) : null;
                boolean shell = shells && cell != null && (!cell.air || optional != null && optional.supported);
                boolean hatch = cell != null && cell.hatch;
                boolean issue = task.repairableIssue() && task.issue.posX == x && task.issue.posY == y && task.issue.posZ == z;
                boolean marker = issue || (host || hatch) && (roles || shell);
                boolean projected = air && (marker || shell);
                if (issue || projected || owned && !air && task.structureAt(player.worldObj, x, y, z)) {
                    result[0] = new Hit(task, x, y, z, owned, projected, marker);
                }
            }
            return result[0] != null;
        });
        return result[0];
    }

    static BuildTask pickHost(BuilderTasks store, EntityPlayerMP player, UUID id, int revision) {
        Hit h=pick(store,player);
        return h!=null && h.owned && h.marker && h.projected && h.task.id.equals(id.toString()) && h.task.revision==revision
                && h.position.posX==h.task.x && h.position.posY==h.task.y && h.position.posZ==h.task.z ? h.task : null;
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
            if (picked == null || !picked.owned || !picked.projected || (picked.task.previewLayers & PreviewStream.SHELL) == 0
                    || !picked.task.editable() || !picked.task.materialSelectable(x, y, z) || !picked.task.id.equals(taskId.toString()) || picked.task.revision != revision
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

    public static final class TaskAction implements IMessage {
        UUID id;
        private static final int DELETE = 0, OPEN_MARKER = 1, OPEN_TASK = 2;
        int action;
        int revision, x, y, z;

        public TaskAction() {}

        public static void open(UUID id, int revision, ChunkCoordinates at) {
            sendOpen(OPEN_MARKER, id, revision, at);
        }

        public static void openTask(UUID id, ChunkCoordinates at) {
            sendOpen(OPEN_TASK, id, 0, at);
        }

        private static void sendOpen(int action, UUID id, int revision, ChunkCoordinates at) {
            TaskAction a = new TaskAction();
            a.id = id; a.action = action; a.revision = revision;
            a.x = at.posX; a.y = at.posY; a.z = at.posZ;
            NET.sendToServer(a);
        }

        public static void delete(String id) {
            TaskAction a = new TaskAction();
            try { a.id = UUID.fromString(id); }
            catch (IllegalArgumentException invalid) { return; }
            NET.sendToServer(a);
        }

        @Override public void toBytes(ByteBuf b) {
            b.writeLong(id.getMostSignificantBits()); b.writeLong(id.getLeastSignificantBits());
            b.writeByte(action);
            if (action != DELETE) { b.writeInt(revision); b.writeInt(x); b.writeInt(y); b.writeInt(z); }
        }
        @Override public void fromBytes(ByteBuf b) {
            id = new UUID(b.readLong(), b.readLong()); action = b.readUnsignedByte();
            if (action != DELETE) { revision = b.readInt(); x = b.readInt(); y = b.readInt(); z = b.readInt(); }
        }

        void apply(BuilderTasks store, EntityPlayerMP player) {
            ItemStack held = player.getHeldItem();
            if (player.isDead || !player.playerNetServerHandler.netManager.isChannelOpen()
                    || player.openContainer != player.inventoryContainer || held == null || held.getItem() != BuilderItem.ITEM) return;
            if (action == DELETE) { store.shortcutDelete(player, held, id.toString()); return; }
            if (action != OPEN_TASK && action != OPEN_MARKER) return;
            Hit hit = pick(store, player);
            // Opening reads current state. A preceding wheel edit may already have advanced the revision.
            if (hit == null || !hit.owned || action == OPEN_MARKER && !hit.marker || !hit.task.id.equals(id.toString())
                    || hit.position.posX != x || hit.position.posY != y || hit.position.posZ != z) return;
            openMarker(store, player, hit.task, action == OPEN_MARKER ? hit.position : null);
        }

        static void openMarker(BuilderTasks store, EntityPlayerMP player, BuildTask task, ChunkCoordinates position) {
            ItemStack held=player.getHeldItem();
            store.select(held, task);
            BuilderTasks.tag(held).setBoolean("openQueue", false);
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            openedMarker.set(position);
            try {
                com.cleanroommc.modularui.factory.PlayerInventoryGuiFactory.INSTANCE.openFromMainHand(player);
            } finally {
                openedMarker.remove();
            }
        }
    }

    public static final class TaskActionHandler implements IMessageHandler<TaskAction, IMessage> {
        @Override public IMessage onMessage(TaskAction action, MessageContext ctx) {
            actions.putIfAbsent(ctx.getServerHandler().playerEntity, action);
            return null;
        }
    }
}
