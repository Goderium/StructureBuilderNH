package com.Goderium.StructureBuilderNH.common.builder;

import static com.cleanroommc.modularui.network.NetworkUtils.readStringSafe;
import static com.cleanroommc.modularui.network.NetworkUtils.writeStringSafe;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.*;
import com.cleanroommc.modularui.drawable.text.TextRenderer;
import com.cleanroommc.modularui.factory.PlayerInventoryGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.utils.item.ItemStackHandler;
import com.cleanroommc.modularui.utils.serialization.ByteBufAdapters;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.value.sync.*;
import com.cleanroommc.modularui.widget.*;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;
import com.cleanroommc.modularui.widgets.*;
import com.cleanroommc.modularui.widgets.slot.*;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnewhorizon.structurelib.ChannelDescription;
import com.gtnewhorizon.structurelib.structure.IChannelDefinition;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.StatCollector;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class BuilderGui {
    private enum Page {
        MAIN,
        CHANNELS,
        ORDER,
        TASKS
    }

    private static final int W = 264,
            H = 232,
            INK = 0xffecf2f0,
            MUTED = 0xffadc0c2,
            ACCENT = 0xff9ee3cb,
            SURFACE = 0xff243437,
            TRACK = 0xff19282b,
            STRIPE = 0xff2b3e41,
            RULE = 0xff456064,
            PRIMARY = 0xff346759;
    final PlayerInventoryGuiData data;
    final PanelSyncManager sync;
    final ModularPanel panel = ModularPanel.defaultPanel("wireless_builder", W, H);
    final ListWidget<IWidget, ?> materials = new ListWidget<>(),
            deficits = new ListWidget<>(),
            jobs = new ListWidget<>(),
            settings = new ListWidget<>();
    private final Map<String, String> sentProgress = new HashMap<>();
    private final Map<String, QueueLine> lines = new HashMap<>();
    private final Map<String, IntValue> channelValues = new LinkedHashMap<>();
    private int channelRevision;
    BuildTask task;
    Page page = Page.MAIN;
    BuildTask.State observed;
    List<IChannelDefinition> channels = Collections.emptyList();
    ItemStack channelTarget;
    int channelReads = -1;
    String previous = "";
    StringSyncValue selectedId;
    IntSyncValue view, flags;
    GenericSyncValue<ItemStack, ?> target;

    private static final class QueueLine {
        String name, position, phase, progress;

        String description() {
            return name + "  " + position + "  " + phase + "  " + progress;
        }
    }

    BuilderGui(PlayerInventoryGuiData d, PanelSyncManager s) {
        data = d;
        sync = s;
        if (server() && tool() != null && BuilderTasks.tag(tool()).getBoolean("openQueue"))
            page = Page.TASKS;
    }

    static String tr(String k) {
        return StatCollector.translateToLocal("structurebuildernh." + k);
    }

    private EntityPlayerMP player() {
        return (EntityPlayerMP) data.getPlayer();
    }

    private ItemStack tool() {
        return data.getUsedItemStack();
    }

    private boolean server() {
        return !data.getPlayer().worldObj.isRemote;
    }

    private BuildTask current() {
        if (!server() || tool() == null || tool().getItem() != BuilderItem.ITEM) return null;
        return BuilderTasks.get().selected(data.getPlayer(), tool());
    }

    private void update() {
        BuildTask now = current();
        if (now != task) {
            task = now;
            channelTarget = null;
            channelReads = -1;
            channels = Collections.emptyList();
            observed = null;
            previous = "";
        }
        if (task == null) return;
        task.store.touch(task);
        if (!Materials.equal(channelTarget, task.target)
                || task.plan != null
                        && task.plan.ready
                        && channelReads != task.observedChannels.size()) {
            channelReads = task.observedChannels.size();
            channels = task.definitions();
            channelTarget = task.target == null ? null : task.target.copy();
        }
        if (observed != task.state) {
            if (page == Page.MAIN && task.state == BuildTask.State.MISSING) page = Page.ORDER;
            if (page == Page.ORDER && task.state != BuildTask.State.MISSING) page = Page.MAIN;
            observed = task.state;
        }
    }

    private ItemStackHandler marker() {
        return new ItemStackHandler(1) {
            public int getSlotLimit(int index) {
                return 1;
            }

            public ItemStack getStackInSlot(int index) {
                if (!server()) return super.getStackInSlot(index);
                BuildTask t = current();
                return t == null || t.target == null ? null : t.target.copy();
            }

            public void setStackInSlot(int index, ItemStack stack) {
                if (!server()) {
                    super.setStackInSlot(index, stack);
                    return;
                }
                BuildTask t = current();
                if (page != Page.MAIN
                        || t == null
                        || !t.editable()
                        || t.existing
                        || Materials.equal(t.target, stack)
                        || stack != null && Blueprint.prototype(stack) == null) return;
                t.target = stack == null ? null : stack.copy();
                if (t.target != null) t.target.stackSize = 1;
                t.observedChannels.clear();
                t.tier = 1;
                t.channels = new NBTTagCompound();
                t.state = BuildTask.State.DRAFT;
                t.resetPlan();
            }
        };
    }

    ModularPanel build() {
        update();
        selectedId = str("selectedId", () -> task == null ? "" : task.id);
        view = new IntSyncValue(() -> page.ordinal());
        sync.syncValue("view", view);
        sync.registerSyncedAction(
                "act",
                false,
                true,
                b -> {
                    String id = readStringSafe(b), action = readStringSafe(b);
                    update();
                    if (id.equals(task == null ? "" : task.id)) action(action);
                });
        sync.registerSyncedAction(
                "select",
                false,
                true,
                b -> {
                    String id = readStringSafe(b);
                    if (tool() == null || page != Page.TASKS) return;
                    for (BuildTask t : BuilderTasks.get().list(player(), tool())) {
                        if (t.id.equals(id)) {
                            t.store.select(tool(), t);
                            show(t.state == BuildTask.State.MISSING ? Page.ORDER : Page.MAIN);
                            break;
                        }
                    }
                });
        sync.registerSyncedAction("apply", false, true, this::applyChannels);
        sync.registerSyncedAction("rows", true, false, this::receive);
        sync.onServerTick(
                () -> {
                    update();
                    if (data.getPlayer().ticksExisted % 5 == 0) sendRows();
                });
        target =
                GenericSyncValue.builder(ItemStack.class)
                        .getter(() -> task == null ? null : task.target)
                        .adapter(ByteBufAdapters.ITEM_STACK)
                        .copy(ItemStack::copy)
                        .nullable()
                        .build();
        sync.syncValue("target", target);
        StringSyncValue position =
                str(
                        "position",
                        () ->
                                task == null
                                        ? ""
                                        : task.x
                                                + ", "
                                                + task.y
                                                + ", "
                                                + task.z
                                                + "   ·   "
                                                + task.facing.getDirection().name());
        StringSyncValue phase =
                str("phase", () -> task == null ? "pick" : "state." + task.state.name());
        StringSyncValue reason = str("reason", () -> task == null ? "" : task.reason);
        StringSyncValue detail = str("detail", () -> task == null ? "" : task.detail);
        StringSyncValue progress =
                str(
                        "progress",
                        () ->
                                task == null
                                        ? ""
                                        : task.total > 0
                                                ? task.placed + " / " + task.total
                                                : task.plan != null && task.plan.ready
                                                        ? dimensions()
                                                        : "");
        Supplier<String> status =
                () ->
                        tr(
                                        "state.REFUND".equals(phase.getStringValue())
                                                        && (flags.getIntValue() & 264) == 0
                                                ? "refundDone"
                                                : phase.getStringValue())
                                + (reason.getStringValue().isEmpty()
                                        ? ""
                                        : " · " + tr(reason.getStringValue()))
                                + (detail.getStringValue().isEmpty()
                                        ? ""
                                        : "  "
                                                + StatCollector.translateToLocal(
                                                        detail.getStringValue()));
        flags =
                new IntSyncValue(
                        () ->
                                task == null
                                        ? 0
                                        : (task.editable() ? 1 : 0)
                                                | (task.auto ? 2 : 0)
                                                | (task.errors.rows.isEmpty() ? 0 : 4)
                                                | (task.savedLink != null
                                                                && task.state
                                                                        == BuildTask.State.REFUND
                                                        ? 256
                                                        : 0)
                                                | (task.key.isEmpty() ? 128 : 0)
                                                | (task.hasReserved() ? 8 : 0)
                                                | (PreviewStream.layers(task) << 4));
        sync.syncValue("flags", flags);
        IntSyncValue rate =
                new IntSyncValue(
                                () -> tool() == null ? 256 : BuilderItem.rate(tool()),
                                v -> {
                                    if (server() && tool() != null)
                                        BuilderTasks.tag(tool())
                                                .setInteger("rate", Math.max(1, Math.min(4096, v)));
                                })
                        .allowC2S();
        sync.syncValue("rate", rate);

        panel.child(new Widget<>().pos(2, 2).size(W - 4, H - 4).background(fill(SURFACE)));
        ParentWidget<?> main = page(Page.MAIN),
                channel = page(Page.CHANNELS),
                order = page(Page.ORDER),
                tasks = page(Page.TASKS);
        main.child(
                new PhantomItemSlot()
                        .slot(
                                new ModularSlot(marker(), 0)
                                        .filter(s -> Blueprint.prototype(s) != null))
                        .pos(9, 10)
                        .tooltip(t -> t.addLine(IKey.str(tr("marker")))));
        label(main, this::name, 33, 6, 154, 14, INK);
        label(main, position::getStringValue, 33, 21, 154, 12, MUTED);
        button(main, 193, 10, 62, () -> tr("queue"), "tasks", false);
        rule(main, 9, 38, 246);
        BooleanSupplier settled = () -> in(phase, "DONE", "REFUND");
        label(
                main,
                () -> tr(in(phase, "REFUND") ? "leftovers" : "materials"),
                10,
                42,
                75,
                13,
                ACCENT);
        label(main, progress::getStringValue, 88, 42, 82, 13, MUTED, Alignment.CenterRight)
                .setEnabledIf(w -> !in(phase, "REFUND"));
        list(main, materials, 9, 57, 162, 81);
        button(main, 180, 43, 75, () -> tr("channels"), "channels", false)
                .setEnabledIf(w -> target.getValue() != null && !settled.getAsBoolean());
        label(main, () -> tr("preview"), 181, 67, 74, 12, ACCENT)
                .setEnabledIf(w -> target.getValue() != null && !settled.getAsBoolean());
        for (int i = 0; i < 3; i++) {
            final int mask = 16 << i;
            String key = new String[] {"box", "shell", "hatches"}[i];
            button(
                            main,
                            180,
                            82 + i * 19,
                            75,
                            () -> ((flags.getIntValue() & mask) != 0 ? "✓ " : "") + tr(key),
                            key,
                            false)
                    .setEnabledIf(w -> target.getValue() != null && !settled.getAsBoolean());
        }
        label(main, status, 10, 140, 245, 13, MUTED);
        rule(main, 9, 155, 246);
        main.child(SlotGroupWidget.playerInventory(false).left(9).bottom(2));
        label(main, () -> tr("rate"), 180, 160, 75, 12, MUTED)
                .setEnabledIf(w -> !settled.getAsBoolean());
        main.child(
                new TextFieldWidget()
                        .value(rate)
                        .numbersInt(1, 4096)
                        .pos(180, 174)
                        .size(75, 14)
                        .setEnabledIf(w -> !settled.getAsBoolean()));
        button(main, 180, 191, 75, () -> tr("cancel"), "cancel", false)
                .setEnabledIf(
                        w ->
                                target.getValue() != null
                                        && !settled.getAsBoolean()
                                        && !in(phase, "DRAFT"));
        button(
                        main,
                        180,
                        210,
                        75,
                        () ->
                                tr(
                                        in(phase, "BLOCKED")
                                                ? "resume"
                                                : in(phase, "MISSING") ? "shortage" : "start"),
                        "go",
                        true)
                .setEnabledIf(
                        w ->
                                target.getValue() != null
                                        && in(phase, "DRAFT", "BLOCKED", "READY", "MISSING"));
        button(
                        main,
                        180,
                        210,
                        75,
                        () ->
                                tr(
                                        (flags.getIntValue() & 264) == 0
                                                ? "remove"
                                                : (flags.getIntValue() & 128) == 0
                                                        ? "retryRefund"
                                                        : "recover"),
                        "cancel",
                        false)
                .setEnabledIf(w -> settled.getAsBoolean());
        button(main, 180, 191, 75, () -> tr("discard"), "cancel", false)
                .setEnabledIf(w -> in(phase, "DRAFT") && target.getValue() != null);

        heading(channel, "channels", this::name);
        label(channel, () -> tr("channelName"), 12, 44, 177, 12, ACCENT);
        label(channel, () -> tr("level"), 199, 44, 48, 12, ACCENT);
        list(channel, settings, 9, 60, 246, 132);
        label(channel, () -> tr("inherit"), 10, 201, 168, 19, MUTED);
        control(channel, 189, 201, 66, () -> tr("apply"), this::submitChannels, true)
                .setEnabledIf(w -> (flags.getIntValue() & 1) != 0);

        heading(order, "shortage", this::name);
        label(
                order,
                () -> tr((flags.getIntValue() & 4) == 0 ? "missingItems" : "aeInputs"),
                10,
                43,
                242,
                14,
                ACCENT);
        list(order, deficits, 9, 59, 246, 113);
        label(order, status, 10, 176, 242, 13, MUTED);
        rule(order, 9, 194, 246);
        button(
                order,
                9,
                202,
                99,
                () -> ((flags.getIntValue() & 2) != 0 ? "✓ " : "□ ") + tr("auto"),
                "auto",
                false);
        button(order, 120, 202, 61, () -> tr("recheck"), "recheck", false);
        button(order, 187, 202, 68, () -> tr("order"), "order", true);

        heading(tasks, "queue", () -> tr("title"));
        list(tasks, jobs, 9, 46, 246, 176);
        return panel;
    }

    private static boolean in(StringSyncValue phase, String... states) {
        for (String state : states)
            if (("state." + state).equals(phase.getStringValue())) return true;
        return false;
    }

    private String name() {
        return target.getValue() == null ? tr("title") : target.getValue().getDisplayName();
    }

    private ParentWidget<?> page(Page p) {
        ParentWidget<?> group =
                new ParentWidget<>()
                        .size(W, H)
                        .setEnabledIf(w -> view.getIntValue() == p.ordinal());
        panel.child(group);
        return group;
    }

    private void heading(ParentWidget<?> group, String title, Supplier<String> subtitle) {
        label(group, () -> tr(title), 10, 6, 174, 14, INK);
        label(group, subtitle, 10, 21, 174, 12, MUTED);
        button(group, 194, 10, 61, () -> tr("back"), "back", false);
        rule(group, 9, 38, 246);
    }

    private static Rectangle fill(int color) {
        return new Rectangle().color(color);
    }

    private void rule(ParentWidget<?> group, int x, int y, int width) {
        group.child(new Widget<>().pos(x, y).size(width, 1).background(fill(RULE)));
    }

    private void list(
            ParentWidget<?> group, ListWidget<IWidget, ?> list, int x, int y, int w, int h) {
        VerticalScrollData scroll = new VerticalScrollData(false, 5);
        scroll.setScrollSpeed(18);
        group.child(list.scrollDirection(scroll).pos(x, y).size(w, h).background(fill(TRACK)));
    }

    private void action(String action) {
        update();
        ItemStack s = tool();
        if (s == null || s.getItem() != BuilderItem.ITEM) return;
        if ("back".equals(action)) {
            show(Page.MAIN);
            return;
        }
        if ("tasks".equals(action)) {
            show(Page.TASKS);
            return;
        }
        if (task == null) return;
        WorldServer world = DimensionManager.getWorld(task.dim);
        switch (action) {
            case "channels":
                if (task.target != null) show(Page.CHANNELS);
                return;
            case "auto":
                if (page == Page.ORDER && task.state == BuildTask.State.MISSING) {
                    task.auto = !task.auto;
                    task.changed();
                }
                return;
            case "box":
            case "shell":
            case "hatches":
                if (page == Page.MAIN && player().dimension == task.dim)
                    PreviewStream.toggle(
                            player(),
                            task,
                            action.equals("box") ? 1 : action.equals("shell") ? 2 : 4);
                return;
            case "go":
                if (page != Page.MAIN) return;
                if (task.state == BuildTask.State.MISSING) {
                    show(Page.ORDER);
                    return;
                }
                if (world != null && player().worldObj == world) task.start();
                break;
            case "recheck":
            case "order":
                if (page != Page.ORDER
                        || task.state != BuildTask.State.MISSING
                        || player().worldObj != world) return;
                if (action.equals("recheck") || player().capabilities.isCreativeMode) task.start();
                else task.submit(player(), world);
                break;
            case "cancel":
                if (page == Page.MAIN && world != null && player().worldObj == world) {
                    task.cancel(player(), world);
                    if (task.state == BuildTask.State.REFUND
                            && task.savedLink == null
                            && !task.hasReserved()) {
                        task.store.jobs.remove(task.id);
                        task.store.viewers.remove(task.id);
                        PreviewStream.stop(task.owner, player());
                        task.store.markDirty();
                        List<BuildTask> rest = task.store.list(player(), s);
                        task.store.select(s, rest.isEmpty() ? null : rest.get(0));
                        show(Page.TASKS);
                    }
                }
                break;
            default:
                return;
        }
        update();
        previous = "";
        sendRows();
    }

    private void show(Page next) {
        page = next;
        previous = "";
        update();
        sendRows();
    }

    private void submitChannels() {
        sync.callSyncedAction(
                "apply",
                b -> {
                    writeStringSafe(b, selectedId.getStringValue());
                    b.writeInt(channelRevision);
                    b.writeVarIntToBuffer(channelValues.size());
                    for (Map.Entry<String, IntValue> e : channelValues.entrySet()) {
                        writeStringSafe(b, e.getKey());
                        b.writeVarIntToBuffer(e.getValue().getIntValue());
                    }
                });
    }

    private void applyChannels(PacketBuffer b) {
        String id = readStringSafe(b);
        int revision = b.readInt(), count = b.readVarIntFromBuffer();
        update();
        if (page != Page.CHANNELS
                || task == null
                || !task.id.equals(id)
                || !task.editable()
                || task.revision != revision
                || count != channels.size() + 1) return;
        Map<String, Integer> values = new HashMap<>();
        for (int i = 0; i < count; i++) values.put(readStringSafe(b), b.readVarIntFromBuffer());
        if (!values.containsKey("")) return;
        int tier = Math.max(1, Math.min(4096, values.get("")));
        NBTTagCompound next = new NBTTagCompound();
        for (IChannelDefinition d : channels) {
            Integer v = values.get(d.getChannel());
            if (v == null) return;
            if (v > 0) next.setInteger(d.getChannel(), d.normalizeValue(v));
        }
        if (task.tier != tier || !task.channels.equals(next)) {
            task.tier = tier;
            task.channels = next;
            task.state = BuildTask.State.DRAFT;
            task.resetPlan();
        }
        show(Page.MAIN);
    }

    private String dimensions() {
        com.Goderium.StructureBuilderNH.util.Bounds b = task.plan.bounds;
        return (b.maxX - b.minX + 1)
                + " × "
                + (b.maxY - b.minY + 1)
                + " × "
                + (b.maxZ - b.minZ + 1);
    }

    private StringSyncValue str(String id, Supplier<String> get) {
        StringSyncValue s = new StringSyncValue(get);
        sync.syncValue(id, s);
        return s;
    }

    private Widget<?> label(
            ParentWidget<?> group, Supplier<String> text, int x, int y, int w, int h, int color) {
        return label(group, text, x, y, w, h, color, Alignment.CenterLeft);
    }

    private Widget<?> label(
            ParentWidget<?> group,
            Supplier<String> text,
            int x,
            int y,
            int w,
            int h,
            int color,
            Alignment align) {
        Widget<?> widget =
                IKey.dynamic(() -> fit(text.get(), w))
                        .asWidget()
                        .pos(x, y)
                        .size(w, h)
                        .textAlign(align)
                        .color(color)
                        .shadow(false)
                        .tooltip(t -> t.addLine(IKey.dynamic(text)));
        group.child(widget);
        return widget;
    }

    private ButtonWidget<?> button(
            ParentWidget<?> group,
            int x,
            int y,
            int w,
            Supplier<String> title,
            String act,
            boolean primary) {
        return control(
                group,
                x,
                y,
                w,
                title,
                () ->
                        sync.callSyncedAction(
                                "act",
                                b -> {
                                    writeStringSafe(b, selectedId.getStringValue());
                                    writeStringSafe(b, act);
                                }),
                primary);
    }

    private ButtonWidget<?> control(
            ParentWidget<?> group,
            int x,
            int y,
            int w,
            Supplier<String> title,
            Runnable click,
            boolean primary) {
        ButtonWidget<?> btn =
                new ButtonWidget<>()
                        .pos(x, y)
                        .size(w, 17)
                        .background(fill(primary ? PRIMARY : 0xff3b5054))
                        .hoverBackground(fill(primary ? 0xff45806c : 0xff51686b))
                        .overlay(IKey.dynamic(title).color(INK).shadow(false))
                        .onMousePressed(
                                b -> {
                                    click.run();
                                    return true;
                                });
        group.child(btn);
        return btn;
    }

    private static String fit(String s, int width) {
        if (s == null) return "";
        if (TextRenderer.getFontRenderer().getStringWidth(s) <= width) return s;
        return TextRenderer.getFontRenderer().trimStringToWidth(s, Math.max(0, width - 6)) + "…";
    }

    private void sendRows() {
        if (tool() == null || tool().getItem() != BuilderItem.ITEM) return;
        if (page == Page.CHANNELS) {
            if (task == null) return;
            String key = "channels/" + task.id + "/" + task.revision + "/" + channels.size();
            if (key.equals(previous)) return;
            previous = key;
            sync.callSyncedAction(
                    "rows",
                    b -> {
                        b.writeByte(4);
                        b.writeInt(task.revision);
                        b.writeVarIntToBuffer(task.tier);
                        b.writeVarIntToBuffer(channels.size());
                        for (IChannelDefinition d : channels) {
                            writeStringSafe(b, d.getChannel());
                            b.writeVarIntToBuffer(task.channels.getInteger(d.getChannel()));
                            b.writeVarIntToBuffer(d.getMinimumValue());
                            b.writeVarIntToBuffer(d.getMaximumValue());
                        }
                    });
            return;
        }
        if (page == Page.TASKS) {
            List<BuildTask> tasks = BuilderTasks.get().list(player(), tool());
            StringBuilder membership = new StringBuilder("queue");
            for (BuildTask t : tasks) membership.append(t.id);
            if (!previous.contentEquals(membership)) {
                previous = membership.toString();
                sentProgress.clear();
                sync.callSyncedAction("rows", b -> b.writeByte(0));
            }
            for (BuildTask t : tasks) {
                String progress =
                        t.state.name() + "/" + t.placed + "/" + t.total + "/" + t.revision;
                String last = sentProgress.put(t.id, progress);
                if (progress.equals(last)) continue;
                sync.callSyncedAction(
                        "rows",
                        b -> {
                            b.writeByte(last == null ? 2 : 3);
                            writeStringSafe(b, t.id);
                            if (last == null) {
                                ByteBufAdapters.ITEM_STACK.serializeSafe(b, t.target);
                                writeStringSafe(
                                        b, t.x + ", " + t.y + ", " + t.z + "  (DIM " + t.dim + ")");
                            }
                            writeStringSafe(
                                    b,
                                    t.state == BuildTask.State.REFUND
                                                    && !t.hasReserved()
                                                    && t.savedLink == null
                                            ? "REFUNDED"
                                            : t.state.name());
                            b.writeVarIntToBuffer(t.placed);
                            b.writeVarIntToBuffer(t.total);
                        });
            }
            return;
        }
        boolean missing = page == Page.ORDER;
        String key =
                task == null
                        ? "empty"
                        : page.name()
                                + "/"
                                + task.id
                                + "/"
                                + task.revision
                                + "/"
                                + task.state
                                + "/"
                                + (task.plan != null && task.plan.ready);
        if (previous.equals(key)) return;
        previous = key;
        sync.callSyncedAction("rows", b -> b.writeByte(0));
        if (task == null) return;
        Materials list =
                missing
                        ? (task.errors.rows.isEmpty() ? task.shortage : task.errors)
                        : task.state == BuildTask.State.REFUND
                                ? task.held
                                : task.plan != null && task.plan.ready ? task.plan.bill : null;
        if (task.state == BuildTask.State.REFUND
                || task.state == BuildTask.State.DONE && task.hasReserved()) {
            list = new Materials();
            list.read(task.held.save());
            for (Materials.Entry entry : task.backpack.rows.values())
                list.add(entry.item, entry.count);
        }
        if (list == null) return;
        for (Materials.Entry e : list.rows.values()) {
            int amount = e.count;
            if (!missing
                    && task.plan != null
                    && list == task.plan.bill
                    && task.existing
                    && Materials.equal(e.item, task.target)) amount--;
            if (amount <= 0) continue;
            final int n = amount;
            sync.callSyncedAction(
                    "rows",
                    b -> {
                        b.writeByte(missing ? 5 : 1);
                        ByteBufAdapters.ITEM_STACK.serializeSafe(b, e.item);
                        b.writeVarIntToBuffer(n);
                        boolean quoted = missing && task.errors.rows.isEmpty();
                        b.writeBoolean(quoted);
                        if (quoted) {
                            b.writeVarIntToBuffer(task.needed.count(e.item));
                            b.writeVarIntToBuffer(task.reserved(e.item));
                        }
                    });
        }
    }

    private void receive(PacketBuffer b) {
        int type = b.readUnsignedByte();
        if (type == 0) {
            lines.clear();
            jobs.removeAll();
            materials.removeAll();
            deficits.removeAll();
            return;
        }
        if (type == 4) {
            channelRevision = b.readInt();
            settings.removeAll();
            channelValues.clear();
            channelRow("", b.readVarIntFromBuffer(), 1, 4096);
            int count = b.readVarIntFromBuffer();
            for (int i = 0; i < count; i++) {
                channelRow(
                        readStringSafe(b),
                        b.readVarIntFromBuffer(),
                        b.readVarIntFromBuffer(),
                        b.readVarIntFromBuffer());
            }
            return;
        }
        if (type == 2 || type == 3) {
            String id = readStringSafe(b);
            QueueLine row = type == 2 ? new QueueLine() : lines.get(id);
            if (type == 2) {
                ItemStack target = ByteBufAdapters.ITEM_STACK.deserializeSafe(b);
                row.name = target == null ? tr("title") : target.getDisplayName();
                row.position = readStringSafe(b);
                lines.put(id, row);
            }
            String phase = tr("state." + readStringSafe(b));
            String progress = b.readVarIntFromBuffer() + " / " + b.readVarIntFromBuffer();
            if (row != null) {
                row.phase = phase;
                row.progress = progress;
            }
            if (type == 2) {
                com.cleanroommc.modularui.api.drawable.IDrawable name =
                        IKey.dynamic(() -> fit(row.name + "   ·   " + row.position, 229))
                                .color(0xffefffff)
                                .shadow(false)
                                .alignment(Alignment.CenterLeft);
                com.cleanroommc.modularui.api.drawable.IDrawable state =
                        IKey.dynamic(() -> fit(row.phase, 153))
                                .color(0xffa8e4d8)
                                .shadow(false)
                                .alignment(Alignment.CenterLeft);
                com.cleanroommc.modularui.api.drawable.IDrawable count =
                        IKey.dynamic(() -> row.progress)
                                .color(0xffefffff)
                                .shadow(false)
                                .alignment(Alignment.CenterRight);
                jobs.child(
                        new ButtonWidget<>()
                                .size(239, 27)
                                .background(
                                        new Rectangle()
                                                .color(
                                                        jobs.getChildren().size() % 2 == 0
                                                                ? 0xff263539
                                                                : 0xff304248))
                                .overlay(
                                        (ctx, x, y, w, h, theme) -> {
                                            name.draw(ctx, x + 3, y, w - 6, 13, theme);
                                            state.draw(ctx, x + 3, y + 12, 153, 12, theme);
                                            count.draw(ctx, x + 158, y + 12, w - 161, 12, theme);
                                        })
                                .tooltip(t -> t.addLine(IKey.dynamic(row::description)))
                                .onMousePressed(
                                        i -> {
                                            sync.callSyncedAction(
                                                    "select", buf -> writeStringSafe(buf, id));
                                            return true;
                                        }));
            }
            return;
        }
        ItemStack s = ByteBufAdapters.ITEM_STACK.deserializeSafe(b);
        int n = b.readVarIntFromBuffer();
        boolean quoted = b.readBoolean();
        int required = quoted ? b.readVarIntFromBuffer() : 0,
                reserved = quoted ? b.readVarIntFromBuffer() : 0;
        if (s == null) return;
        String count = String.format(Locale.ROOT, "%,d", n);
        int width = TextRenderer.getFontRenderer().getStringWidth(count) + 3;
        ListWidget<IWidget, ?> rows = type == 5 ? deficits : materials;
        int rowWidth = type == 5 ? 239 : 155;
        rows.child(
                new ParentWidget<>()
                        .size(rowWidth, 16)
                        .background(
                                new Rectangle()
                                        .color(
                                                rows.getChildren().size() % 2 == 0
                                                        ? 0xff263539
                                                        : 0xff304248))
                        .child(new ItemDrawable(s).asWidget().pos(1, 0))
                        .child(
                                IKey.str(fit(s.getDisplayName(), rowWidth - 22 - width))
                                        .asWidget()
                                        .pos(19, 0)
                                        .size(rowWidth - 22 - width, 16)
                                        .color(0xffefffff)
                                        .textAlign(Alignment.CenterLeft)
                                        .shadow(false))
                        .child(
                                IKey.str(count)
                                        .asWidget()
                                        .pos(rowWidth - 3 - width, 0)
                                        .size(width, 16)
                                        .textAlign(Alignment.CenterRight)
                                        .color(0xffa8e4d8)
                                        .shadow(false))
                        .tooltip(
                                t -> {
                                    t.addFromItem(s);
                                    if (quoted)
                                        t.addLine(
                                                IKey.str(
                                                        StatCollector.translateToLocalFormatted(
                                                                "structurebuildernh.quote",
                                                                required,
                                                                reserved,
                                                                Math.max(
                                                                        0, required - reserved - n),
                                                                n)));
                                }));
    }

    private void channelRow(String key, int current, int min, int max) {
        boolean main = key.isEmpty();
        IntValue value = new IntValue(current);
        channelValues.put(key, value);
        Map<Integer, ItemStack> samples = new HashMap<>();
        if (!main && ChannelDescription.has(key)) {
            for (Map.Entry<ItemStack, Integer> e :
                    ChannelDescription.get(key).getItems().entrySet())
                samples.putIfAbsent(e.getValue(), e.getKey());
        }
        Supplier<ItemStack> sample =
                () ->
                        samples.get(
                                Math.max(
                                        min,
                                        Math.min(
                                                max,
                                                value.getIntValue() == 0
                                                        ? channelValues.get("").getIntValue()
                                                        : value.getIntValue())));
        ParentWidget<?> row =
                new ParentWidget<>()
                        .size(239, 26)
                        .background(
                                fill(settings.getChildren().size() % 2 == 0 ? STRIPE : SURFACE));
        String label = main ? tr("tier") : key;
        String alias = "structurebuildernh.channel." + key;
        if (!main && StatCollector.canTranslate(alias))
            label = StatCollector.translateToLocal(alias);
        final String title = label;
        ItemDrawable icon = new ItemDrawable();
        row.child(
                new Widget<>()
                        .pos(4, 5)
                        .size(16)
                        .background(
                                (ctx, x, y, w, h, theme) -> {
                                    ItemStack stack = main ? target.getValue() : sample.get();
                                    if (stack != null)
                                        icon.setItem(stack).draw(ctx, x, y, w, h, theme);
                                }));
        label(row, () -> title, 25, 1, 157, 13, INK);
        label(
                row,
                () -> {
                    ItemStack block = sample.get();
                    if (block != null) return block.getDisplayName();
                    if (main) return tr("defaultTier");
                    return key + (max == Integer.MAX_VALUE ? "" : "   " + min + "–" + max);
                },
                25,
                14,
                157,
                11,
                MUTED);
        row.child(
                new TextFieldWidget()
                        .value(value)
                        .numbersInt(main ? 1 : 0, max)
                        .pos(190, 5)
                        .size(43, 16)
                        .setEnabledIf(w -> (flags.getIntValue() & 1) != 0));
        label(row, value::getStringValue, 190, 5, 43, 16, INK)
                .setEnabledIf(w -> (flags.getIntValue() & 1) == 0);
        settings.child(row);
    }
}
