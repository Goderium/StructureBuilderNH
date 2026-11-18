package com.Goderium.StructureBuilderNH.common.builder;

import static com.cleanroommc.modularui.network.NetworkUtils.readStringSafe;
import static com.cleanroommc.modularui.network.NetworkUtils.writeStringSafe;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.*;
import com.cleanroommc.modularui.drawable.text.TextRenderer;
import com.cleanroommc.modularui.factory.PlayerInventoryGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.GuiContext;
import com.cleanroommc.modularui.theme.WidgetTheme;
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

    private static final int W = 316,
            H = 232,
            INK = 0xffecf2f0,
            MUTED = 0xffadc0c2,
            ACCENT = 0xff9ee3cb,
            SURFACE = 0xff243437,
            TRACK = 0xff19282b,
            STRIPE = 0xff2b3e41,
            RULE = 0xff456064,
            PRIMARY = 0xff346759;
    private static final int LIST_WIDTH = W - 18, ROW_WIDTH = LIST_WIDTH - 7;
    private static final int OPTIONS_X = 220, OPTIONS_WIDTH = W - 9 - OPTIONS_X, OPTION_TEXT = 18;
    private static final int MATERIAL_WIDTH = OPTIONS_X - 23, ACTION_WIDTH = 49;
    private static final int TASK_BODY = ROW_WIDTH - ACTION_WIDTH - 4;
    private static final int EDGE = 9, FOOTER_Y = 206, BUTTON_H = 17;
    private static final int LEVEL_X = ROW_WIDTH - 49, LEVEL_WIDTH = 43;
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
        static final int HEIGHT = 37, CONTENT = TASK_BODY - 10, PROGRESS_Y = 24;
        final String name, position;
        String phase = "", progress = "";
        int placed, total;
        boolean canDismantle, confirmDismantle;
        final ParentWidget<?> entry = new ParentWidget<>().size(ROW_WIDTH, HEIGHT).marginBottom(2);
        final ParentWidget<?> actions = new ParentWidget<>().size(ACTION_WIDTH, BUTTON_H)
                .left(TASK_BODY + 4).verticalCenter();
        final ButtonWidget<?> body = new ButtonWidget<>().size(TASK_BODY, HEIGHT);
        final TextRenderer renderer = new TextRenderer();
        final TextRenderer.Line machine, location;
        TextRenderer.Line status;
        final IDrawable count;
        int barStart, countWidth;
        final Rectangle track = fill(TRACK), bar = fill(0xff73bfaa);

        QueueLine(String name, String position) {
            this.name = name;
            this.position = position;
            machine = renderer.line(name);
            location = renderer.line(position);
            count = IKey.dynamic(() -> progress).color(INK).shadow(false).alignment(Alignment.CenterRight);
        }

        void layout(boolean dismantle) {
            status = renderer.line(phase);
            countWidth = progress.isEmpty() ? 0 : textWidth(progress) + 2;
            barStart = total > 0 ? Math.min(96, CONTENT - countWidth - 25) : CONTENT;
            if (canDismantle != dismantle) {
                canDismantle = dismantle;
                actions.size(ACTION_WIDTH, canDismantle ? 2 * BUTTON_H + 2 : BUTTON_H);
                if (entry.isValid()) entry.scheduleResize();
            }
        }

        void draw(GuiContext ctx, int x, int y, int w, int h, WidgetTheme theme) {
            int left = x + 5, line = y + PROGRESS_Y;
            drawLine(machine, left, y + 2, CONTENT, INK);
            drawLine(location, left, y + 13, CONTENT, MUTED);
            drawLine(status, left, line, total > 0 ? barStart - 4 : CONTENT, ACCENT);
            if (total <= 0) return;
            int barY = line + 3;
            int width = CONTENT - barStart - countWidth - 5;
            track.draw(ctx, left + barStart, barY, width, 6, theme);
            int filled = (int) (Math.min(placed, (long) total) * width / total);
            if (filled > 0) bar.draw(ctx, left + barStart, barY, filled, 6, theme);
            count.draw(ctx, left + CONTENT - countWidth, line, countWidth, 12, theme);
        }

        private void drawLine(TextRenderer.Line value, int x, int y, int width, int color) {
            renderer.setPos(x, y);
            renderer.setAlignment(Alignment.CenterLeft, width, 11);
            renderer.setColor(color);
            renderer.drawCut(value);
        }

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
            if (now == null && task != null && task.target != null && server()
                    && tool() != null && tool().getItem() == BuilderItem.ITEM
                    && (task.deleteRequested || !task.store.jobs.containsKey(task.id))) {
                page = Page.TASKS;
                if (!task.existing && task.dim == player().dimension) {
                    // Keep the selected build site. An unmarked draft stays out of the task list.
                    task.store.create(player(), tool(), task.x, task.y, task.z, null, task.facing);
                    now = current();
                }
            }
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
                if (!server()) return ItemStack.copyItemStack(target.getValue());
                BuildTask t = current();
                return t == null ? null : ItemStack.copyItemStack(t.target);
            }

            public void setStackInSlot(int index, ItemStack stack) {
                if (!server()) return;
                BuildTask t = current();
                if (page != Page.MAIN
                        || t == null
                        || !t.editable()
                        || t.existing
                        || Materials.equal(t.target, stack)
                        || stack != null && Blueprint.prototype(stack) == null) return;
                t.configureTarget(stack);
                if (stack != null) t.store.register(t);
                else if (t.placed == 0 && t.ledger.count == 0 && !t.hasReserved()) t.store.unbind(t);
            }
        };
    }

    private ModularSlot markerSlot() {
        // MUI's default insertion probes temporarily clear a slot. The marker is a task setting.
        return new ModularSlot(marker(), 0) {
            @Override
            public boolean isItemValid(ItemStack stack) {
                return Blueprint.prototype(stack) != null;
            }

            @Override
            public int getItemStackLimit(ItemStack stack) {
                return 1;
            }
        }.singletonSlotGroup();
    }

    ModularPanel build() {
        update();
        selectedId = str("selectedId", () -> task == null ? "" : task.id);
        view = new IntSyncValue(() -> page.ordinal());
        sync.syncValue("view", view);
        IntSyncValue autoRemove = new IntSyncValue(() -> server() && BuilderTasks.get().autoRemove(player()) ? 1 : 0);
        sync.syncValue("autoRemove", autoRemove);
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
        sync.registerSyncedAction(
                "queueAction",
                false,
                true,
                b -> {
                    String id = readStringSafe(b);
                    boolean dismantle = b.readBoolean();
                    if (page != Page.TASKS || tool() == null) return;
                    for (BuildTask t : BuilderTasks.get().list(player(), tool())) {
                        if (!t.id.equals(id)) continue;
                        WorldServer world = DimensionManager.getWorld(t.dim);
                        if (dismantle) {
                            if (world == player().worldObj) t.requestDismantle(player(), world);
                        } else delete(t, world);
                        previous = "";
                        sendRows();
                        break;
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
        StringSyncValue position = str("position", () -> task == null ? "" : location(task));
        StringSyncValue facing = str("facing", () -> task == null ? "" : task.facing.getDirection().name());
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
        Supplier<String> status = () -> {
            String state = phase.getStringValue();
            if (target.getValue() == null && state.equals("state.DRAFT")) return tr("markController");
            String message = tr(state.equals("state.REFUND") && (flags.getIntValue() & 264) == 0 ? "refundDone" : state);
            if (!reason.getStringValue().isEmpty()) {
                String why = tr(reason.getStringValue());
                if (!why.equals(message)) message += ": " + why;
            }
            return detail.getStringValue().isEmpty() ? message
                    : message + "  " + StatCollector.translateToLocal(detail.getStringValue());
        };
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
                                                | (task.key.isEmpty() && task.salvage.rows.isEmpty() ? 128 : 0)
                                                | (task.hasReserved() ? 8 : 0)
                                                | (task.previewLayers << 4));
        sync.syncValue("flags", flags);
        IntSyncValue rate =
                new IntSyncValue(
                                () -> tool() == null ? 256 : BuilderItem.rate(tool()),
                                v -> {
                                    if (server() && tool() != null)
                                        BuilderTasks.tag(tool())
                                                .setInteger("rate", BuilderItem.clampRate(v));
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
                        .slot(markerSlot())
                        .pos(EDGE, 9)
                        .tooltip(t -> t.addLine(IKey.str(tr("marker")))));
        singleLine(main, this::name, 33, 4, 205, 17, INK);
        label(main, () -> position.getStringValue().isEmpty() ? ""
                : site(position.getStringValue()) + " | " + tr("facing." + facing.getStringValue()),
                33, 22, W - EDGE - 33, 12, MUTED);
        button(main, W - EDGE - 63, 5, 63, () -> tr("queue"), "tasks", false);
        rule(main, EDGE, 36, LIST_WIDTH);
        BooleanSupplier settled =
                () -> in(phase, "DONE", "REFUND", "DISMANTLING", "DISMANTLED", "TEARDOWN_PAUSED");
        label(
                main,
                () -> tr(in(phase, "DISMANTLING") ? "salvage" : in(phase, "REFUND") ? "leftovers" : "materials"),
                10,
                38,
                99,
                13,
                ACCENT);
        label(main, progress::getStringValue, 104, 38, EDGE + MATERIAL_WIDTH - 104, 13, MUTED, Alignment.CenterRight)
                .setEnabledIf(w -> !in(phase, "REFUND"));
        list(main, materials, EDGE, 52, MATERIAL_WIDTH + 7, 80);
        button(main, OPTIONS_X, 39, OPTIONS_WIDTH, () -> tr("channels"), "channels", false)
                .height(15).padding(OPTION_TEXT, 2, 0, 0)
                .overlay(IKey.str(tr("channels")).color(INK).shadow(false).alignment(Alignment.CenterLeft))
                .setEnabledIf(w -> target.getValue() != null && !settled.getAsBoolean());
        label(main, () -> tr("preview"), OPTIONS_X + 3, 55, OPTIONS_WIDTH - 3, 12, ACCENT)
                .setEnabledIf(w -> target.getValue() != null && !settled.getAsBoolean());
        for (int i = 0; i < 3; i++) {
            final int mask = 16 << i;
            String key = new String[] {"box", "shell", "hatches"}[i];
            checkbox(main, OPTIONS_X, 68 + i * 16, OPTIONS_WIDTH, key, () -> (flags.getIntValue() & mask) != 0)
                    .height(15)
                    .setEnabledIf(w -> target.getValue() != null && !settled.getAsBoolean());
        }
        singleLine(main, status, 10, 133, LIST_WIDTH - 2, 13, MUTED);
        rule(main, EDGE, 147, LIST_WIDTH);
        main.child(SlotGroupWidget.playerInventory(false).left(EDGE).top(152));
        label(main, () -> tr("rate"), 178, 152, 129, 14, MUTED)
                .setEnabledIf(w -> !settled.getAsBoolean());
        main.child(
                new TextFieldWidget()
                        .value(rate)
                        .numbersInt(1, BuilderItem.MAX_RATE)
                        .pos(178, 169)
                        .size(129, 16)
                        .setEnabledIf(w -> !settled.getAsBoolean()));
        button(main, 178, FOOTER_Y, 60, () -> tr("cancel"), "cancel", false)
                .setEnabledIf(
                        w ->
                                target.getValue() != null
                                        && !settled.getAsBoolean()
                                        && !in(phase, "DRAFT", "PREVIEW"));
        button(
                        main,
                        242,
                        FOOTER_Y,
                        65,
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
                                        && in(
                                                phase, "DRAFT", "PREVIEW", "BLOCKED", "READY",
                                                "MISSING"));
        button(
                        main,
                        178,
                        FOOTER_Y,
                        129,
                        () ->
                                tr(
                                        (flags.getIntValue() & 264) == 0
                                                ? "remove"
                                                : (flags.getIntValue() & 128) == 0
                                                        ? "retryRefund"
                                                        : "recover"),
                        "cancel",
                        false)
                .setEnabledIf(w -> settled.getAsBoolean() && !in(phase, "DISMANTLING"));
        button(
                        main,
                        178,
                        FOOTER_Y,
                        60,
                        () -> tr(in(phase, "PREVIEW") ? "remove" : "discard"),
                        "cancel",
                        false)
                .setEnabledIf(w -> in(phase, "DRAFT", "PREVIEW") && target.getValue() != null);

        heading(channel, "channels", this::name);
        label(channel, () -> tr("channelName"), EDGE + 25, 44, LEVEL_X - 29, 12, ACCENT);
        label(channel, () -> tr("level"), EDGE + LEVEL_X, 44, LEVEL_WIDTH, 12, ACCENT, Alignment.CENTER);
        list(channel, settings, EDGE, 59, LIST_WIDTH, 128);
        label(channel, () -> tr("inherit"), 10, 190, LIST_WIDTH - 2, 14, MUTED);
        control(channel, 121, FOOTER_Y, 112, () -> tr("shareDefaults"), () -> submitChannels(true), false)
                .tooltip(t -> t.addLine(IKey.str(tr("shareHelp"))))
                .setEnabledIf(w -> (flags.getIntValue() & 1) != 0);
        control(channel, 239, FOOTER_Y, 68, () -> tr("apply"), () -> submitChannels(false), true)
                .setEnabledIf(w -> (flags.getIntValue() & 1) != 0);

        heading(order, "shortage", this::name);
        label(
                order,
                () -> tr((flags.getIntValue() & 4) == 0 ? "missingItems" : "aeInputs"),
                10,
                43,
                LIST_WIDTH - 2,
                14,
                ACCENT);
        list(order, deficits, EDGE, 59, LIST_WIDTH, 112);
        singleLine(order, status, 10, 171, LIST_WIDTH - 2, 22, MUTED);
        rule(order, 9, 194, LIST_WIDTH);
        checkbox(order, EDGE, FOOTER_Y, 132, "auto", () -> (flags.getIntValue() & 2) != 0);
        button(order, 149, FOOTER_Y, 63, () -> tr("recheck"), "recheck", false);
        button(order, 218, FOOTER_Y, 89, () -> tr("order"), "order", true);

        heading(tasks, "queue", BuilderGui::terminalName);
        list(tasks, jobs, EDGE, 44, LIST_WIDTH, 4 * (QueueLine.HEIGHT + 2));
        checkbox(tasks, EDGE, FOOTER_Y, 178, "autoRemove", () -> autoRemove.getIntValue() != 0)
                .tooltip(t -> t.addLine(IKey.str(tr("autoRemoveHelp"))));
        return panel;
    }

    private static boolean in(StringSyncValue phase, String... states) {
        for (String state : states)
            if (("state." + state).equals(phase.getStringValue())) return true;
        return false;
    }

    private String name() {
        return target.getValue() == null ? terminalName() : target.getValue().getDisplayName();
    }

    private static String terminalName() {
        return StatCollector.translateToLocal("item.sbnh.builder.name");
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
        label(group, () -> tr(title), 10, 5, W - 87, 14, INK);
        singleLine(group, subtitle, 10, 23, W - 20, 12, MUTED);
        button(group, W - EDGE - 63, 5, 63, () -> tr("back"), "back", false);
        rule(group, 9, 38, LIST_WIDTH);
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
        group.child(list.scrollDirection(scroll).crossAxisAlignment(Alignment.CrossAxis.START)
                .pos(x, y).size(w, h).background(fill(TRACK)));
    }

    private void action(String action) {
        update();
        ItemStack s = tool();
        if (s == null || s.getItem() != BuilderItem.ITEM) return;
        if ("back".equals(action)) {
            if (task == null) BuilderItem.selectAtFeet(player(), s);
            show(Page.MAIN);
            return;
        }
        if ("tasks".equals(action)) {
            show(Page.TASKS);
            return;
        }
        if ("autoRemove".equals(action)) {
            if (page == Page.TASKS) BuilderTasks.get().toggleAutoRemove(player());
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
                if (page == Page.MAIN)
                    task.togglePreview(action.equals("box") ? 1 : action.equals("shell") ? 2 : 4);
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
                    if (task.state == BuildTask.State.REFUND) {
                        task.cancel(player(),world);
                        if (task.savedLink == null && !task.hasReserved()) delete(task, world);
                    } else if (task.ledger.count > 0 && task.state != BuildTask.State.DONE)
                        task.cancel(player(), world);
                    else delete(task, world);
                    show(Page.TASKS);
                }
                break;
            default:
                return;
        }
        update();
        previous = "";
        sendRows();
    }

    private void delete(BuildTask t, WorldServer world) {
        boolean selected = current() == t;
        t.deleteRequested = true;
        t.dismantleRequested = false;
        if (t.state != BuildTask.State.REFUND) t.cancel(player(), world);
        else if (world != null && player().worldObj == world) t.retryRefund(player(), world);
        if (t.savedLink == null && !t.hasReserved()) {
            t.store.remove(t, player());
        }
        if (selected) t.store.select(tool(), null);
        t.changed();
    }

    private void queueAction(String id, boolean dismantle) {
        sync.callSyncedAction(
                "queueAction",
                b -> {
                    writeStringSafe(b, id);
                    b.writeBoolean(dismantle);
                });
    }

    private void show(Page next) {
        page = next;
        previous = "";
        update();
        sendRows();
    }

    private void submitChannels(boolean share) {
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
                    b.writeBoolean(share);
                });
    }

    private void applyChannels(PacketBuffer b) {
        String id = readStringSafe(b);
        int revision = b.readInt();
        int count = b.readVarIntFromBuffer();
        update();
        if (page != Page.CHANNELS
                || task == null
                || task.target == null
                || !task.id.equals(id)
                || !task.editable()
                || task.revision != revision
                || count != channels.size() + 1) return;
        Map<String, Integer> values = new HashMap<>();
        for (int i = 0; i < count; i++) values.put(readStringSafe(b), b.readVarIntFromBuffer());
        boolean share = b.isReadable() && b.readBoolean();
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
            task.resetDraft();
        }
        task.store.channelMemory.remember(task, channels, share);
        show(Page.MAIN);
    }

    private String dimensions() {
        com.Goderium.StructureBuilderNH.util.Bounds b = task.plan.bounds;
        return (b.maxX - b.minX + 1)
                + " x "
                + (b.maxY - b.minY + 1)
                + " x "
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

    private static void singleLine(ParentWidget<?> group, Supplier<String> text,
            int x, int y, int w, int h, int color) {
        ScrollingTextWidget widget = new ScrollingTextWidget(IKey.dynamic(text)).pos(x, y).size(w, h)
                .color(color).textAlign(Alignment.CenterLeft).shadow(false);
        group.child(widget);
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
                IKey.dynamic(text)
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
        return control(group, x, y, w, title, () -> sendAction(act), primary);
    }

    private void sendAction(String action) {
        sync.callSyncedAction("act", b -> {
            writeStringSafe(b, selectedId.getStringValue());
            writeStringSafe(b, action);
        });
    }

    private ButtonWidget<?> checkbox(ParentWidget<?> group, int x, int y, int width, String action,
            BooleanSupplier checked) {
        ButtonWidget<?> button = control(group, x, y, width, () -> tr(action), () -> sendAction(action), false);
        IDrawable text = IKey.str(tr(action)).color(INK).shadow(false).alignment(Alignment.CenterLeft);
        return button.overlay((ctx, left, top, w, h, theme) -> {
            (checked.getAsBoolean() ? GuiTextures.CHECK_BOX_FULL : GuiTextures.CHECK_BOX_EMPTY)
                    .draw(ctx, left + 3, top + (h - 12) / 2, 12, 12, theme);
            text.draw(ctx, left + OPTION_TEXT, top, w - OPTION_TEXT - 2, h, theme);
        }).tooltip(t -> t.addLine(IKey.str(tr(action))));
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
                        .size(w, BUTTON_H)
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

    private static int textWidth(String text) {
        return TextRenderer.getFontRenderer().getStringWidth(text);
    }

    private static String location(BuildTask t) {
        return t.dim + " | " + t.x + ", " + t.y + ", " + t.z;
    }

    private static String site(String location) {
        return StatCollector.translateToLocalFormatted("structurebuildernh.site", location);
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
                        t.state.name()
                                + "/"
                                + t.placed
                                + "/"
                                + t.total
                                + "/"
                                + t.revision
                                + "/"
                                + t.previewLayers
                                + "/"
                                + (t.ledger.count > 0);
                String last = sentProgress.put(t.id, progress);
                if (progress.equals(last)) continue;
                sync.callSyncedAction(
                        "rows",
                        b -> {
                            b.writeByte(last == null ? 2 : 3);
                            writeStringSafe(b, t.id);
                            if (last == null) {
                                ByteBufAdapters.ITEM_STACK.serializeSafe(b, t.target);
                                writeStringSafe(b, location(t));
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
                            b.writeBoolean(t.previewLayers != 0 && t.state != BuildTask.State.PREVIEW);
                            b.writeBoolean(t.dim == player().dimension && t.ledger.count > 0
                                    && t.state != BuildTask.State.DISMANTLING && !t.deleteRequested
                                    && (!t.dismantleRequested || t.state == BuildTask.State.TEARDOWN_PAUSED));
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
                                + (task.state == BuildTask.State.DISMANTLING && task.salvage.rows.isEmpty()
                                        ? -1 : task.revision)
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
                        : task.state == BuildTask.State.DISMANTLING
                                ? task.salvage
                                : task.plan != null && task.plan.ready ? task.plan.bill : null;
        if (task.state == BuildTask.State.REFUND
                || task.state == BuildTask.State.DONE && task.hasReserved()) {
            list = new Materials();
            list.read(task.held.save());
            for (Materials.Entry entry : task.backpack.rows.values())
                list.add(entry.item, entry.count);
            for (Materials.Entry entry : task.salvage.rows.values())
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
            QueueLine row;
            if (type == 2) {
                ItemStack target = ByteBufAdapters.ITEM_STACK.deserializeSafe(b);
                row = new QueueLine(target == null ? terminalName() : target.getDisplayName(), site(readStringSafe(b)));
                lines.put(id, row);
            } else row = lines.get(id);
            String phase = tr("state." + readStringSafe(b));
            int placed = b.readVarIntFromBuffer(), total = b.readVarIntFromBuffer();
            boolean preview = b.readBoolean(), canDismantle = b.readBoolean();
            if (row == null) return;
            row.phase = preview ? phase + " | " + tr("state.PREVIEW") : phase;
            row.placed = placed;
            row.total = total;
            row.progress = total == 0 ? "" : placed + " / " + total;
            if (!canDismantle) row.confirmDismantle = false;
            row.layout(canDismantle);
            if (type == 2) {
                row.entry.child(row.body
                        .background(fill(jobs.getChildren().size() % 2 == 0 ? STRIPE : SURFACE))
                        .overlay(row::draw)
                        .tooltip(t -> t.addLine(IKey.dynamic(row::description)))
                        .onMousePressed(i -> {
                            sync.callSyncedAction("select", buf -> writeStringSafe(buf, id));
                            return true;
                        }));
                row.entry.child(row.actions);
                control(row.actions, 0, 0, ACTION_WIDTH, () -> tr("delete"), () -> queueAction(id, false), false)
                        .tooltip(t -> t.addLine(IKey.str(tr("deleteHint"))));
                control(row.actions, 0, BUTTON_H + 2, ACTION_WIDTH, () -> tr(row.confirmDismantle ? "confirm" : "dismantle"), () -> {
                    if (row.confirmDismantle) queueAction(id, true);
                    row.confirmDismantle = !row.confirmDismantle;
                }, false).setEnabledIf(w -> row.canDismantle)
                        .tooltip(t -> t.addLine(IKey.str(tr("dismantleHint"))));
                jobs.child(row.entry);
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
        int rowWidth = type == 5 ? ROW_WIDTH : MATERIAL_WIDTH;
        int nameWidth = rowWidth - 26 - width;
        String itemName = s.getDisplayName();
        ParentWidget<?> row = new ParentWidget<>()
                        .size(rowWidth, 16)
                        .background(
                                new Rectangle()
                                        .color(
                                                rows.getChildren().size() % 2 == 0
                                                        ? 0xff263539
                                                        : 0xff304248))
                        .child(new ItemDrawable(s).asWidget().pos(1, 0))
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
                                });
        singleLine(row, () -> itemName, 19, 0, nameWidth, 16, 0xffefffff);
        rows.child(row);
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
        String label = main ? tr("tier") : key;
        String alias = "structurebuildernh.channel." + key;
        if (!main && StatCollector.canTranslate(alias))
            label = StatCollector.translateToLocal(alias);
        final String title = label;
        String fallback = main ? tr("defaultTier") : max == Integer.MAX_VALUE
                ? StatCollector.translateToLocalFormatted("structurebuildernh.minLevel", min)
                : StatCollector.translateToLocalFormatted("structurebuildernh.levelRange", min, max);
        int textWidth = LEVEL_X - 25 - 6;
        int rowHeight = 32;
        ParentWidget<?> row = new ParentWidget<>().size(ROW_WIDTH, rowHeight)
                .background(fill(settings.getChildren().size() % 2 == 0 ? STRIPE : SURFACE));
        ItemDrawable icon = new ItemDrawable();
        row.child(
                new Widget<>()
                        .left(4).verticalCenter()
                        .size(16)
                        .background(
                                (ctx, x, y, w, h, theme) -> {
                                    ItemStack stack = main ? target.getValue() : sample.get();
                                    if (stack != null)
                                        icon.setItem(stack).draw(ctx, x, y, w, h, theme);
                                }));
        singleLine(row, () -> title, 25, 3, textWidth, 12, INK);
        singleLine(
                row,
                () -> {
                    ItemStack block = sample.get();
                    if (block != null) return block.getDisplayName();
                    return fallback;
                },
                25,
                17,
                textWidth,
                12,
                MUTED);
        row.child(
                new TextFieldWidget()
                        .value(value)
                        .numbersInt(main ? 1 : 0, max)
                        .left(LEVEL_X).verticalCenter()
                        .size(LEVEL_WIDTH, 16)
                        .setEnabledIf(w -> (flags.getIntValue() & 1) != 0));
        label(row, value::getStringValue, LEVEL_X, (rowHeight - 16) / 2, LEVEL_WIDTH, 16, INK, Alignment.CENTER)
                .setEnabledIf(w -> (flags.getIntValue() & 1) == 0);
        settings.child(row);
    }
}
