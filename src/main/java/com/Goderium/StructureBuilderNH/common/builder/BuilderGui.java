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
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
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
import com.gtnewhorizon.structurelib.structure.IChannelDefinition;

import gregtech.api.util.GTUtility.ItemId;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class BuilderGui {
    private enum Page {
        MAIN,
        ORDER,
        TASKS, CHOICES, GROUPS, BROWSER, CELL
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
    private static final int STYLE_ROW_HEIGHT = 11;
    private static final int MATERIAL_WIDTH = OPTIONS_X - 23, ACTION_WIDTH = 49;
    private static final int TASK_BODY = ROW_WIDTH - ACTION_WIDTH - 4;
    private static final int EDGE = 9, FOOTER_Y = 206, BUTTON_H = 17;
    private static final int UPGRADE = 512, HAS_REPLACEMENTS = 2048;
    private static final int SITE_COUNTS = 4096, PARTIAL_BUILD = 8192, CHECKING_SITE = 16384;
    private static final int TEXTURED_PREVIEW = 1 << 15;
    private static final int REPAIR_CELL = 1 << 16;
    private static final int LOCKED_MARKER = 128;
    private static final int CELL_SLOT_X = 13, CELL_SLOT_Y = 78, BAG_X = EDGE, BAG_Y = 152;
    final PlayerInventoryGuiData data;
    final PanelSyncManager sync;
    final ModularPanel panel = new ModularPanel("wireless_builder") {
        @Override
        public boolean onKeyPressed(char c, int key) {
            if (onCellPage() && !getContext().isFocused()) {
                if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE) { sendAction("back"); return true; }
                if (cellSlotHovered() && pickHotbar(key)) return true;
                if (key == org.lwjgl.input.Keyboard.KEY_RETURN || key == org.lwjgl.input.Keyboard.KEY_NUMPADENTER) {
                    if (!org.lwjgl.input.Keyboard.isRepeatEvent()) confirmCell(false);
                    return true;
                }
            }
            if (!getContext().isFocused()
                    && com.Goderium.StructureBuilderNH.StructureBuilderNH.proxy.deleteShortcutKey(key)) {
                String id = BuilderTasks.shortcutTarget(tool());
                if (!id.isEmpty()) sync.callSyncedAction("shortcutDelete", b -> writeStringSafe(b, id));
                return true;
            }
            return super.onKeyPressed(c, key);
        }
        @Override
        public boolean onMousePressed(int button) {
            if (onCellPage() && cellSlotHovered() && pickHotbar(button - 100)) return true;
            if (onCellPage() && (button == 0 || button == 1)) {
                int slot = bagSlot(getContext().getAbsMouseX() - getArea().x, getContext().getAbsMouseY() - getArea().y);
                if (slot >= 0 && data.getPlayer().inventory.getItemStack() == null) {
                    selectCellSlot(slot);
                    return true;
                }
            }
            if (button == 1 && view != null && view.getIntValue() != Page.MAIN.ordinal()
                    && view.getIntValue() != Page.BROWSER.ordinal()) {
                int x = getContext().getAbsMouseX() - getArea().x;
                int y = getContext().getAbsMouseY() - getArea().y;
                if (onCellPage() && (cellSlotHovered() || bagSlot(x, y) >= 0)) return super.onMousePressed(button);
                if (x >= 0 && x < W && y >= 0 && y < H) {
                    getContext().removeFocus();
                    sendAction("back");
                    return true;
                }
            }
            return super.onMousePressed(button);
        }
    }.size(W, H);
    final ListWidget<IWidget, ?> materials = new ListWidget<>(),
            deficits = new ListWidget<>(),
            jobs = new ListWidget<>();
    private final Map<String, String> sentProgress = new HashMap<>();
    private final Map<String, QueueLine> lines = new HashMap<>();
    private OrdinaryBuildSurvey checkingSite, site, sentSite;
    private int nextSiteCheck;
    private final Map<ItemId, SiteRow> siteRows = new HashMap<>();
    private final ListWidget<IWidget, ?> alternatives = new ListWidget<>();
    private final List<IntValue> amounts = new ArrayList<>();
    private final ListWidget<IWidget, ?> roles = new ListWidget<>();
    private final List<Integer> matchingGroups = new ArrayList<>();
    private int choiceIndex = -1, choiceRevision, choiceSize;
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
    private TerminalBrowser browser;
    private SingleCellPlacement cell;
    private int markerNoticeUntil;
    private IntSyncValue cellTicket;

    private boolean onCellPage() { return view != null && view.getIntValue() == Page.CELL.ordinal(); }

    private boolean cellSlotHovered() {
        int x = panel.getContext().getAbsMouseX() - panel.getArea().x - CELL_SLOT_X;
        int y = panel.getContext().getAbsMouseY() - panel.getArea().y - CELL_SLOT_Y;
        return x >= 0 && y >= 0 && x < 18 && y < 18;
    }

    static int bagSlot(int x, int y) {
        int dx = x - BAG_X, dy = y - BAG_Y;
        if (dx < 0 || dx >= 162 || dy < 0) return -1;
        if (dy < 54) return 9 + (dy / 18) * 9 + dx / 18;
        return dy >= 58 && dy < 76 ? dx / 18 : -1;
    }

    private boolean pickHotbar(int key) {
        int index = com.Goderium.StructureBuilderNH.StructureBuilderNH.proxy.hotbarSlot(key);
        if (index < 0) return false;
        selectCellSlot(index);
        return true;
    }

    private void selectCellSlot(int index) {
        sync.callSyncedAction("cellSelect", b -> b.writeByte(index));
    }

    private void confirmCell(boolean remove) {
        sync.callSyncedAction("cellConfirm", b -> { b.writeInt(cellTicket.getIntValue()); b.writeBoolean(remove); });
    }

    private static final class QueueLine {
        static final int HEIGHT = 37, CONTENT = TASK_BODY - 10, PROGRESS_Y = 24;
        final String name, position;
        String phase = "", progress = "";
        int placed, total;
        boolean canDismantle, confirmDismantle, surveyed;
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
            return name + "  " + position + "  " + phase + "  " + (surveyed ? tr("lastSiteCheck") + " " : "") + progress;
        }
    }

    BuilderGui(PlayerInventoryGuiData d, PanelSyncManager s) {
        data = d;
        sync = s;
        if (server() && tool() != null && BuilderTasks.tag(tool()).getBoolean("openQueue"))
            page = Page.TASKS;
        net.minecraft.util.ChunkCoordinates p = PreviewEdit.openedMarker.get();
        if (server() && p != null) openCell(current(), p);
    }

    private void openCell(BuildTask t, net.minecraft.util.ChunkCoordinates p) {
        if (t == null || !t.canChangeCell(p) || t.dim != player().dimension || !player().worldObj.blockExists(p.posX,p.posY,p.posZ)) return;
        if (p.posX == t.x && p.posY == t.y && p.posZ == t.z && !player().worldObj.isAirBlock(t.x, t.y, t.z)) return;
        cell = new SingleCellPlacement(t, p, player());
        page = Page.CELL;
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
            markerNoticeUntil = 0;
            if (cell != null && cell.task != now) { cell = null; if (page == Page.CELL) page = Page.MAIN; }
            if (page == Page.BROWSER) {
                browser.leave();
                page = Page.MAIN;
            }
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
            checkingSite = site = sentSite = null;
            previous = "";
        }
        if (task == null) return;
        if (page == Page.CELL && (cell == null || !task.canChangeCell(cell.at))) {
            cell = null;
            page = Page.MAIN;
        }
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
                if (!t.configureTarget(stack)) {
                    markerNoticeUntil = player().ticksExisted + 100;
                    return;
                }
                if (stack != null) { t.store.register(t); t.store.select(tool(), t); }
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
        sync.registerSyncedAction("shortcutDelete", false, true, b -> {
            String id = readStringSafe(b);
            if (tool() != null && BuilderTasks.get().shortcutDelete(player(), tool(), id)) {
                show(Page.TASKS);
            }
        });
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
        sync.registerSyncedAction("openMaterial", false, true, this::openMaterial);
        sync.registerSyncedAction("materialChoice", false, true, this::editChoice);
        sync.registerSyncedAction("rows", true, false, this::receive);
        browser = new TerminalBrowser(sync, this::current, this::commitChannels, () -> sendAction("back"));
        sync.addCloseListener(p -> browser.close());
        sync.onServerTick(
                () -> {
                    update();
                    if (page == Page.BROWSER) {
                        if (!browser.tick(task, channels, player())) show(Page.MAIN);
                        return;
                    }
                    surveyBuildSite();
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
        StringSyncValue formation = str("formation", this::controllerFormation);
        StringSyncValue reason = str("reason", () -> task == null ? ""
                : page == Page.MAIN && markerNoticeUntil > player().ticksExisted ? "markerLocked" : task.reason);
        StringSyncValue detail = str("detail", () -> task == null ? "" : task.detail);
        StringSyncValue progress = str("progress", this::mainProgress);
        IntSyncValue unknownAtSite = new IntSyncValue(() -> site == null ? 0 : site.unknownCells);
        IntSyncValue blockedAtSite = new IntSyncValue(() -> site == null ? 0 : site.blockedCells);
        sync.syncValue("unknownAtSite", unknownAtSite);
        sync.syncValue("blockedAtSite", blockedAtSite);
        Supplier<String> status = () -> {
            String state = phase.getStringValue();
            if ("markerLocked".equals(reason.getStringValue())) return tr("markerLocked");
            if (target.getValue() == null && state.equals("state.DRAFT"))
                return tr((flags.getIntValue() & LOCKED_MARKER) != 0 ? "markerLocked" : "markController");
            String message = tr(state.equals("state.REFUND") && (flags.getIntValue() & 264) == 0 ? "refundDone"
                    : (flags.getIntValue() & UPGRADE) != 0 && state.equals("state.DONE") ? "upgradeDone"
                    : (flags.getIntValue() & UPGRADE) != 0 && state.equals("state.BUILDING") ? "upgrading" : state);
            if (state.equals("state.DONE") && !formation.getStringValue().isEmpty())
                message += " | " + tr(formation.getStringValue());
            if (!reason.getStringValue().isEmpty()) {
                String why = tr(reason.getStringValue());
                if (!why.equals(message)) message += ": " + why;
            }
            if (in(phase, "DRAFT", "PREVIEW") && (flags.getIntValue() & UPGRADE) == 0) {
                if ((flags.getIntValue() & CHECKING_SITE) != 0) message = tr("checkingSite");
                if (unknownAtSite.getIntValue() > 0) message += "  " + quantity("siteUnverified", unknownAtSite.getIntValue());
                if (blockedAtSite.getIntValue() > 0) message += "  " + quantity("siteBlocked", blockedAtSite.getIntValue());
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
                                                | (task.ledger.count > 0 ? LOCKED_MARKER : 0)
                                                | (task.hasReserved() ? 8 : 0)
                                                | (task.upgrading() ? UPGRADE : 0)
                                                | (task.upgradeCount > 0 ? HAS_REPLACEMENTS : 0)
                                                | (site != null ? SITE_COUNTS : 0)
                                                | (site == null && checkingSite != null ? CHECKING_SITE : 0)
                                                | (site != null && site.installedCells > 0 && site.installedCells < site.totalCells
                                                        ? PARTIAL_BUILD : 0)
                                                | (task.texturedPreview ? TEXTURED_PREVIEW : 0)
                                                | (task.repairableIssue() ? REPAIR_CELL : 0)
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
                order = page(Page.ORDER),
                tasks = page(Page.TASKS),
                choice = page(Page.CHOICES),
                groups = page(Page.GROUPS);
        ParentWidget<?> browserPage = page(Page.BROWSER);
        installCellPage(page(Page.CELL));
        if (!server()) browser.install(browserPage, this::name);
        main.child(
                new PhantomItemSlot()
                        .slot(markerSlot())
                        .pos(EDGE, 9)
                        .tooltip(t -> t.addLine(IKey.str(tr((flags.getIntValue() & LOCKED_MARKER) != 0 ? "markerLocked" : "marker")))));
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
                () -> tr(in(phase, "DISMANTLING") ? "salvage"
                        : in(phase, "REFUND") || in(phase, "DONE") && (flags.getIntValue() & 8) != 0 ? "leftovers"
                        : (flags.getIntValue() & UPGRADE) != 0 ? "replacementList"
                        : (flags.getIntValue() & SITE_COUNTS) != 0 ? "installedTotal" : "materials"),
                10,
                38,
                99,
                13,
                ACCENT);
        label(main, () -> in(phase, "UPGRADE_REVIEW")
                ? StatCollector.translateToLocalFormatted("structurebuildernh.replaceCount", progress.getStringValue())
                : progress.getStringValue(), 104, 38, EDGE + MATERIAL_WIDTH - 104, 13, MUTED, Alignment.CenterRight)
                .setEnabledIf(w -> !in(phase, "REFUND"));
        list(main, materials, EDGE, 52, MATERIAL_WIDTH + 7, 80);
        ParentWidget<?> preview = new ParentWidget<>().pos(OPTIONS_X, 65).size(OPTIONS_WIDTH, 66)
                .setEnabledIf(w -> target.getValue() != null && !settled.getAsBoolean());
        main.child(preview);
        IDrawable browseLabel = IKey.str(tr("browser.open")).color(INK).shadow(false).alignment(Alignment.CenterLeft);
        IDrawable arrow = IKey.str("›").color(ACCENT).shadow(false).alignment(Alignment.CenterRight);
        button(main, OPTIONS_X, 40, OPTIONS_WIDTH, () -> tr("browser.open"), "browser", true)
                .height(22).overlay((ctx, x, y, w, h, theme) -> {
                    browseLabel.draw(ctx, x + 7, y, w - 22, h, theme);
                    arrow.draw(ctx, x + w - 15, y, 8, h, theme);
                }).setEnabledIf(w -> target.getValue() != null);
        checkbox(preview, 0, 0, OPTIONS_WIDTH, "hatches", () -> layerVisible(PreviewStream.HATCH)).height(15);
        checkbox(preview, 0, 17, OPTIONS_WIDTH, "box", () -> layerVisible(PreviewStream.BOX)).height(15);
        checkbox(preview, 0, 34, OPTIONS_WIDTH, "shell", () -> layerVisible(PreviewStream.SHELL)).height(20);
        ParentWidget<?> shellStyles = new ParentWidget<>().pos(0, 54).size(OPTIONS_WIDTH, STYLE_ROW_HEIGHT)
                .setEnabledIf(w -> layerVisible(PreviewStream.SHELL));
        preview.child(shellStyles);
        int solidWidth = 31;
        previewStyle(shellStyles, 0, solidWidth, false);
        previewStyle(shellStyles, solidWidth + 1, OPTIONS_WIDTH - solidWidth - 1, true);
        singleLine(main, status, 10, 133, LIST_WIDTH - 2, 13, MUTED);
        rule(main, EDGE, 147, LIST_WIDTH);
        panel.child(SlotGroupWidget.playerInventory(false).left(BAG_X).top(BAG_Y)
                .setEnabledIf(w -> view.getIntValue() == Page.MAIN.ordinal() || onCellPage()));
        button(main, 178, 187, 129, () -> tr("cell.title"), "repairCell", false)
                .setEnabledIf(w -> (flags.getIntValue() & REPAIR_CELL) != 0);
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
                                        && !in(phase, "DRAFT", "PREVIEW")
                                        && !(in(phase, "UPGRADE_REVIEW") && (flags.getIntValue() & HAS_REPLACEMENTS) != 0));
        button(main, 178, FOOTER_Y, 60,
                () -> tr((flags.getIntValue() & 8) != 0 ? "refundAndReturn" : "buildList"), "buildList", false)
                .setEnabledIf(w -> in(phase, "UPGRADE_REVIEW") && (flags.getIntValue() & HAS_REPLACEMENTS) != 0);
        button(
                        main,
                        242,
                        FOOTER_Y,
                        65,
                        () -> primaryAction(phase.getStringValue()),
                        "go",
                        true)
                .setEnabledIf(
                        w ->
                                target.getValue() != null
                                        && in(
                                                phase, "DRAFT", "PREVIEW", "BLOCKED", "READY",
                                                "MISSING", "UPGRADE_REVIEW"));
        button(main, 178, FOOTER_Y, 129, () -> tr("retryRefund"), "retryRefund", false)
                .setEnabledIf(w -> in(phase, "DONE", "REFUND") && (flags.getIntValue() & 264) != 0);
        button(main, 178, FOOTER_Y, 129, () -> tr("remove"), "deleteTask", false)
                .setEnabledIf(w -> settled.getAsBoolean() && !in(phase, "DISMANTLING")
                        && (!in(phase, "DONE", "REFUND") || (flags.getIntValue() & 264) == 0));
        button(
                        main,
                        178,
                        FOOTER_Y,
                        60,
                        () -> tr(in(phase, "PREVIEW") ? "remove" : "discard"),
                        "deleteTask",
                        false)
                .setEnabledIf(w -> in(phase, "DRAFT", "PREVIEW") && target.getValue() != null);

        heading(groups, "materialRoles", this::name);
        list(groups, roles, EDGE, 44, LIST_WIDTH, 150);
        heading(choice, "alternatives", this::name);
        label(choice, () -> StatCollector.translateToLocalFormatted("structurebuildernh.choiceTotal", String.format(Locale.ROOT, "%,d", choiceSize)),
                EDGE + 2, 43, ROW_WIDTH, 14, ACCENT);
        list(choice, alternatives, EDGE, 59, LIST_WIDTH, 130);
        label(choice, () -> remainder() < 0 ? tr("choiceOverflow") : tr("choiceRemainder"),
                EDGE + 2, 190, ROW_WIDTH, 14, MUTED);
        control(choice, 239, FOOTER_Y, 68, () -> tr("apply"), () -> sendChoice(true, choiceIndex, choiceRevision), true)
                .setEnabledIf(w -> !amounts.isEmpty() && remainder() >= 0);

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

    private String primaryAction(String phase) {
        switch (phase) {
            case "state.DRAFT":
            case "state.PREVIEW": return tr((flags.getIntValue() & PARTIAL_BUILD) != 0 ? "resumeBuild" : "start");
            case "state.BLOCKED": return tr("resume");
            case "state.MISSING": return tr("shortage");
            case "state.UPGRADE_REVIEW":
                if ((flags.getIntValue() & HAS_REPLACEMENTS) != 0) return tr("startUpgrade");
                return tr((flags.getIntValue() & 8) != 0 ? "refundAndReturn" : "continueBuilding");
            default: return tr("start");
        }
    }

    private String controllerFormation() {
        if (task == null || task.state != BuildTask.State.DONE || task.target == null) return "";
        WorldServer world = DimensionManager.getWorld(task.dim);
        if (world == null || !world.blockExists(task.x, task.y, task.z)) return "";
        TileEntity tile = world.getTileEntity(task.x, task.y, task.z);
        if (!(tile instanceof IGregTechTileEntity)) return "";
        IGregTechTileEntity gt = (IGregTechTileEntity) tile;
        if (gt.getMetaTileID() != task.target.getMetadata() || !(gt.getMetaTileEntity() instanceof MTEMultiBlockBase)) return "";
        return ((MTEMultiBlockBase) gt.getMetaTileEntity()).mMachine
                ? "machineFormed" : "machineUnformed";
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

    private void installCellPage(ParentWidget<?> group) {
        GenericSyncValue<ItemStack, ?> selected = GenericSyncValue.builder(ItemStack.class)
                .getter(() -> cell == null ? null : cell.selected).adapter(ByteBufAdapters.ITEM_STACK)
                .copy(ItemStack::copy).nullable().build();
        IntSyncValue mode = new IntSyncValue(() -> cell == null ? 0 : (cell.host ? 1 : 0) | (cell.occupied() ? 2 : 0));
        cellTicket = new IntSyncValue(() -> cell == null ? 0 : cell.ticket);
        sync.syncValue("cellSlot", selected);
        sync.syncValue("cellMode", mode);
        sync.syncValue("cellTicket", cellTicket);
        StringSyncValue pos = str("cellPos", () -> cell == null ? "" : cell.x + ", " + cell.y + ", " + cell.z);
        StringSyncValue result = str("cellResult", () -> cell == null ? "" : cell.message);
        GenericSyncValue<ItemStack, ?> old = GenericSyncValue.builder(ItemStack.class)
                .getter(() -> cell == null ? null : cell.oldDisplay).adapter(ByteBufAdapters.ITEM_STACK)
                .copy(ItemStack::copy).nullable().build();
        sync.syncValue("cellOld", old);
        sync.registerSyncedAction("cellSelect", false, true, b -> {
            int index = b.readByte();
            if (cellActive()) cell.select(player(), index);
        });
        sync.registerSyncedAction("cellConfirm", false, true, b -> {
            int ticket = b.readInt();
            boolean remove = b.readBoolean();
            if (cellActive()) {
                cell.apply(player(), ticket, remove);
                if (cell.host && cell.occupied()) show(Page.MAIN);
            }
        });
        heading(group, "cell.title", () -> tr((mode.getIntValue() & 1) != 0 ? "markerHost" : "markerHatch")
                + "   " + pos.getStringValue());
        label(group, () -> tr("cell.current"), 13, 42, 85, 13, MUTED);
        singleLine(group, () -> old.getValue() == null ? tr((mode.getIntValue() & 2) == 0 ? "cell.empty" : "cell.occupied")
                : old.getValue().getDisplayName(), 13, 56, W - 26, 15, INK);
        ItemStackHandler mark = new ItemStackHandler(1) {
            @Override public int getSlotLimit(int index) { return 1; }
            @Override public ItemStack getStackInSlot(int index) {
                return ItemStack.copyItemStack(server() ? cell == null ? null : cell.selected : selected.getValue());
            }
            @Override public void setStackInSlot(int index, ItemStack stack) {
                if (server() && cellActive()) cell.mark(stack, -1);
            }
        };
        ModularSlot phantom = new ModularSlot(mark, 0) {
            @Override public boolean isItemValid(ItemStack s) {
                if (s == null || !(s.getItem() instanceof net.minecraft.item.ItemBlock)) return false;
                if (server()) return cellActive() && (!cell.host || cell.task.target.isItemEqual(s));
                return onCellPage() && ((mode.getIntValue() & 1) == 0 || target.getValue() != null && target.getValue().isItemEqual(s));
            }
            @Override public int getItemStackLimit(ItemStack s) { return 1; }
        }.singletonSlotGroup();
        group.child(new PhantomItemSlot().slot(phantom).pos(CELL_SLOT_X, CELL_SLOT_Y)
                .tooltip(t -> t.addLine(IKey.str(tr("cell.slotHint")))));
        singleLine(group, () -> selected.getValue() == null ? tr("cell.select") : selected.getValue().getDisplayName(),
                40, 80, W - 53, 16, INK);
        rule(group, EDGE, 110, LIST_WIDTH);
        label(group, () -> StatCollector.translateToLocal("container.inventory"), BAG_X, 136, 130, 14, MUTED);
        singleLine(group, () -> result.getStringValue().isEmpty() ? "" : tr(result.getStringValue()),
                EDGE, 115, LIST_WIDTH, 16, ACCENT);
        control(group, 178, FOOTER_Y, 59, () -> tr("cell.remove"), () -> confirmCell(true), false)
                .setEnabledIf(w -> mode.getIntValue() == 2);
        control(group, 241, FOOTER_Y, 66, () -> tr((mode.getIntValue() & 2) == 0 ? "cell.place" : "cell.replace"),
                () -> confirmCell(false), true);
    }

    private boolean cellActive() {
        return page == Page.CELL && cell != null && current() == cell.task && cell.task.canChangeCell(cell.at)
                && player().getHeldItem() == tool() && player().dimension == cell.task.dim;
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
            if (page == Page.MAIN) return;
            if (page == Page.CHOICES && matchingGroups.size() > 1) { show(Page.GROUPS); return; }
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
            case "repairCell":
                if (page == Page.MAIN && task.repairableIssue() && task.dim == player().dimension)
                    openCell(task, task.issue);
                return;
            case "browser":
                if (page == Page.MAIN && task.target != null) {
                    browser.open(player(), task, channels);
                    show(Page.BROWSER);
                }
                return;
            case "buildList":
                if (page == Page.MAIN && task.state == BuildTask.State.UPGRADE_REVIEW
                        && world != null && player().worldObj == world) task.continueOrdinaryBuild(player(), world);
                break;
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
            case "previewSolid":
            case "previewTextured":
                if (page == Page.MAIN && (task.previewLayers & PreviewStream.SHELL) != 0)
                    task.setTexturedPreview(action.equals("previewTextured"));
                return;
            case "go":
                if (page != Page.MAIN) return;
                if (task.state == BuildTask.State.UPGRADE_REVIEW && task.upgradeCount == 0) {
                    if (world != null && player().worldObj == world) task.continueOrdinaryBuild(player(), world);
                    break;
                }
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
                if (action.equals("recheck")) task.recheckMaterials();
                else if (player().capabilities.isCreativeMode) task.start();
                else task.submit(player(), world);
                break;
            case "retryRefund":
                if (page == Page.MAIN && world != null && player().worldObj == world
                        && (task.state == BuildTask.State.REFUND || task.state == BuildTask.State.DONE)) {
                    task.retryRefund(player(), world);
                    task.beginDismantle();
                }
                break;
            case "deleteTask":
                if (page == Page.MAIN && (task.state == BuildTask.State.DRAFT || task.state == BuildTask.State.PREVIEW
                        || task.state == BuildTask.State.REFUND || task.state == BuildTask.State.DONE
                        || task.state == BuildTask.State.DISMANTLED || task.state == BuildTask.State.TEARDOWN_PAUSED)) {
                    delete(task, world);
                    show(Page.TASKS);
                }
                break;
            case "cancel":
                if (page == Page.MAIN && world != null && player().worldObj == world && task.state != BuildTask.State.DONE
                        && task.state != BuildTask.State.REFUND && task.state != BuildTask.State.DISMANTLED
                        && task.state != BuildTask.State.DISMANTLING && task.state != BuildTask.State.TEARDOWN_PAUSED
                        && task.state != BuildTask.State.DRAFT && task.state != BuildTask.State.PREVIEW) {
                    task.cancel(player(), world);
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
        t.store.delete(t, player(), tool(), world);
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
        if (page == Page.CELL && next != Page.CELL) cell = null;
        if (page == Page.BROWSER && next != Page.BROWSER) browser.leave();
        page = next;
        previous = "";
        update();
        sendRows();
    }

    private void commitChannels(int tier, NBTTagCompound next, Set<String> specified, boolean share) {
        if (page != Page.BROWSER || task == null || !task.canConfigureChannels()
                || task.existing && (task.plan == null || !task.plan.ready)) return;
        boolean changed = !task.usesConfiguredSource() || task.tier != tier || !task.channels.equals(next);
        if (!share && task.existing && task.plan != null && task.plan.ready) {
            specified.retainAll(task.plan.upgradeChannels());
        } else specified.clear();
        if (!specified.isEmpty()) {
            if (!task.setUpgradeChannels(specified)) return;
            task.tier = tier;
            task.channels = next;
        } else if (changed || !share && task.upgrading()) {
            if (task.upgrading() && !task.setUpgradeChannels(Collections.emptySet())) return;
            task.tier = tier;
            task.channels = next;
            task.resetDraft();
        }
        task.store.channelMemory.remember(task, channels, share);
    }

    private String dimensions() {
        com.Goderium.StructureBuilderNH.util.Bounds b = task.plan.bounds;
        return (b.maxX - b.minX + 1)
                + " x "
                + (b.maxY - b.minY + 1)
                + " x "
                + (b.maxZ - b.minZ + 1);
    }

    private String mainProgress() {
        if (task == null) return "";
        if (task.state == BuildTask.State.UPGRADE_REVIEW) return String.valueOf(task.upgradeCount);
        boolean live = task.state == BuildTask.State.BUILDING || task.state == BuildTask.State.VERIFYING
                || task.state == BuildTask.State.QUEUED;
        if (site != null && (!live || task.total == 0))
            return site.installedCells + " / " + site.totalCells + (site.unknownCells > 0 ? " *" : "");
        if (task.total > 0) return task.placed + " / " + task.total;
        return task.plan != null && task.plan.ready ? dimensions() : "";
    }

    private static String quantity(String key, int count) {
        return StatCollector.translateToLocalFormatted("structurebuildernh." + key, String.format(Locale.ROOT, "%,d", count));
    }

    private void surveyBuildSite() {
        if (page != Page.MAIN || task == null || task.plan == null || !task.plan.ready || task.upgrading()
                || player().dimension != task.dim || task.state == BuildTask.State.REFUND
                || task.state == BuildTask.State.DONE || task.state == BuildTask.State.DISMANTLED
                || task.state == BuildTask.State.DISMANTLING || task.state == BuildTask.State.TEARDOWN_PAUSED) {
            checkingSite = site = null;
            return;
        }
        if (site != null && !site.valid(task)) site = null;
        if (checkingSite != null && !checkingSite.valid(task)) checkingSite = null;
        int now = player().ticksExisted;
        if (checkingSite == null) {
            if (site != null && now - nextSiteCheck < 0) return;
            checkingSite = new OrdinaryBuildSurvey(task);
        }
        if (checkingSite.advance(player().worldObj)) {
            site = checkingSite;
            checkingSite = null;
            if (task.state == BuildTask.State.DRAFT || task.state == BuildTask.State.PREVIEW) {
                task.siteInstalled = site.installedCells;
                task.siteTotal = site.totalCells;
                task.siteUnknown = site.unknownCells;
                task.siteRevision = task.revision;
            }
            // Allow edits by other players to appear while the material list is open.
            nextSiteCheck = now + 60;
        }
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
                        .tooltipDynamic(t -> {
                            String line = text.get();
                            if (!line.isEmpty()) t.addLine(IKey.str(line));
                        })
                        .tooltipAutoUpdate(true);
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

    private boolean layerVisible(int layer) {
        return (flags.getIntValue() & (layer << 4)) != 0;
    }

    private void previewStyle(ParentWidget<?> group, int x, int width, boolean textured) {
        String key = textured ? "previewTextured" : "previewSolid";
        IDrawable text = IKey.str(tr(key)).color(INK).shadow(false).scale(0.875f).alignment(Alignment.CENTER);
        Rectangle selected = fill(PRIMARY), underline = fill(ACCENT);
        button(group, x, 0, width, () -> tr(key), key, false).height(STYLE_ROW_HEIGHT).background(fill(STRIPE))
                .overlay((ctx, left, top, w, h, theme) -> {
                    if (((flags.getIntValue() & TEXTURED_PREVIEW) != 0) == textured) {
                        selected.draw(ctx, left, top, w, h, theme);
                        underline.draw(ctx, left, top + h - 1, w, 1, theme);
                    }
                    text.draw(ctx, left, top, w, h, theme);
                }).tooltip(t -> t.addLine(IKey.str(tr(key + "Hint"))));
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
                                    if (b != 0) return false;
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

    private void sendChoice(boolean apply, int index, int revision) {
        sync.callSyncedAction("materialChoice", b -> {
            writeStringSafe(b, selectedId.getStringValue());
            b.writeInt(revision);
            b.writeVarIntToBuffer(index);
            b.writeBoolean(apply);
            if (apply) {
                b.writeVarIntToBuffer(amounts.size());
                for (int i = 1; i < amounts.size(); i++) b.writeVarIntToBuffer(amounts.get(i).getIntValue());
            }
        });
    }

    private void openMaterial(PacketBuffer b) {
        String id = readStringSafe(b);
        int revision = b.readInt(), groupIndex = b.readVarIntFromBuffer(), option = b.readVarIntFromBuffer();
        update();
        if (page != Page.MAIN || task == null || !task.id.equals(id) || !task.editable() || task.revision != revision
                || task.plan == null || !task.plan.ready || groupIndex < 0 || groupIndex >= task.plan.choices.size()) return;
        MaterialChoice clicked = task.plan.choices.get(groupIndex);
        if (option < 0 || option >= clicked.items.size()) return;
        matchingGroups.clear();
        ItemStack item = clicked.items.get(option);
        for (int i = 0; i < task.plan.choices.size(); i++) {
            MaterialChoice group = task.plan.choices.get(i);
            for (ItemStack candidate : group.items) {
                if (Materials.equal(candidate, item)) {
                    matchingGroups.add(i);
                    break;
                }
            }
        }
        if (matchingGroups.isEmpty()) return;
        choiceIndex = matchingGroups.get(0);
        show(matchingGroups.size() == 1 ? Page.CHOICES : Page.GROUPS);
    }

    private void editChoice(PacketBuffer b) {
        String id = readStringSafe(b);
        int revision = b.readInt(), index = b.readVarIntFromBuffer();
        boolean apply = b.readBoolean();
        update();
        if (task == null || !task.id.equals(id) || !task.editable() || task.revision != revision
                || task.plan == null || !task.plan.ready || index < 0 || index >= task.plan.choices.size()) return;
        if (!apply) {
            if (page == Page.GROUPS && matchingGroups.contains(index)) {
                choiceIndex = index;
                show(Page.CHOICES);
            }
            return;
        }
        if (page != Page.CHOICES || index != choiceIndex) return;
        MaterialChoice group = task.plan.choices.get(index);
        if (b.readVarIntFromBuffer() != group.optionCount()) return;
        int[] counts = new int[group.optionCount()];
        int left = group.count;
        for (int i = 1; i < counts.length; i++) {
            counts[i] = b.readVarIntFromBuffer();
            if (counts[i] < 0 || counts[i] > left) return;
            left -= counts[i];
        }
        counts[0] = left;
        task.setMaterialQuantities(group, counts);
        show(Page.MAIN);
    }

    private static final class ChoiceEntry {
        final ItemStack item;
        final int group, option;
        int allocated;

        ChoiceEntry(ItemStack item, int group, int option) {
            this.item = item;
            this.group = group;
            this.option = option;
        }
    }

    private void sendRows() {
        if (page == Page.BROWSER) return;
        if (tool() == null || tool().getItem() != BuilderItem.ITEM) return;
        if (page == Page.GROUPS) {
            if (task == null || !task.editable() || task.plan == null || !task.plan.ready) {
                show(Page.MAIN);
                return;
            }
            String key = "roles/" + task.id + "/" + task.revision;
            if (previous.equals(key)) return;
            previous = key;
            sync.callSyncedAction("rows", b -> b.writeByte(9));
            for (int i : matchingGroups) {
                if (i >= task.plan.choices.size()) continue;
                MaterialChoice group = task.plan.choices.get(i);
                sync.callSyncedAction("rows", b -> {
                    b.writeByte(10);
                    b.writeInt(task.revision);
                    b.writeVarIntToBuffer(i);
                    b.writeVarIntToBuffer(group.count);
                    b.writeInt(group.x);
                    b.writeInt(group.y);
                    b.writeInt(group.z);
                });
            }
            return;
        }
        if (page == Page.CHOICES) {
            if (task == null || !task.editable() || task.plan == null || !task.plan.ready
                    || choiceIndex < 0 || choiceIndex >= task.plan.choices.size()) {
                show(Page.MAIN);
                return;
            }
            MaterialChoice group = task.plan.choices.get(choiceIndex);
            String key = "choice/" + task.id + "/" + choiceIndex + "/" + task.revision;
            if (previous.equals(key)) return;
            previous = key;
            sync.callSyncedAction("rows", b -> {
                b.writeByte(7);
                b.writeInt(task.revision);
                b.writeVarIntToBuffer(choiceIndex);
                b.writeVarIntToBuffer(group.count);
            });
            int[] counts = task.effectiveAllocation(group);
            for (int i = 0; i < counts.length; i++) {
                final int option = i;
                sync.callSyncedAction("rows", b -> {
                    b.writeByte(8);
                    b.writeBoolean(group.isAir(option));
                    if (!group.isAir(option)) ByteBufAdapters.ITEM_STACK.serializeSafe(b, group.optionItem(option));
                    b.writeVarIntToBuffer(counts[option]);
                });
            }
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
                boolean surveyed = t.siteRevision == t.revision
                        && (t.state == BuildTask.State.DRAFT || t.state == BuildTask.State.PREVIEW);
                int installed = surveyed ? t.siteInstalled : t.placed;
                int total = surveyed ? t.siteTotal : t.total;
                int unknown = surveyed ? t.siteUnknown : 0;
                String progress =
                        t.state.name()
                                + "/"
                                + installed
                                + "/"
                                + total
                                + "/"
                                + (surveyed ? unknown : -1)
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
                            b.writeVarIntToBuffer(installed);
                            b.writeVarIntToBuffer(total);
                            b.writeBoolean(t.previewLayers != 0 && t.state != BuildTask.State.PREVIEW);
                            b.writeBoolean(t.dim == player().dimension && t.ledger.count > 0
                                    && t.state != BuildTask.State.DISMANTLING && !t.deleteRequested
                                    && (!t.dismantleRequested || t.state == BuildTask.State.TEARDOWN_PAUSED));
                            b.writeBoolean(t.upgrading());
                            b.writeBoolean(surveyed);
                            if (surveyed) b.writeVarIntToBuffer(unknown);
                        });
            }
            return;
        }
        boolean missing = page == Page.ORDER;
        OrdinaryBuildSurvey currentSite = !missing && site != null && site.valid(task) ? site : null;
        // Building updates the placement ledger every block; the approved replacement rows stay the same.
        boolean stableRows = task != null && (task.state == BuildTask.State.DISMANTLING && task.salvage.rows.isEmpty()
                || !missing && task.upgrading() && (task.state == BuildTask.State.QUEUED
                        || task.state == BuildTask.State.BUILDING || task.state == BuildTask.State.VERIFYING));
        String key =
                task == null
                        ? "empty"
                        : page.name()
                                + "/"
                                + task.id
                                + "/"
                                + (stableRows ? -1 : task.revision)
                                + "/"
                                + task.state
                                + "/"
                                + (task.plan != null && task.plan.ready);
        if (previous.equals(key)) {
            if (sentSite == currentSite) return;
            if (sentSite != null && currentSite != null && currentSite.total.same(sentSite.total)) {
                // Keep the scroll position and selectable rows while only the counts change.
                for (Materials.Entry entry : currentSite.total.rows.values()) {
                    ItemStack item = entry.item;
                    if (currentSite.installed.count(item) == sentSite.installed.count(item)
                            && currentSite.missing.count(item) == sentSite.missing.count(item)
                            && currentSite.blocked.count(item) == sentSite.blocked.count(item)
                            && currentSite.unverified.count(item) == sentSite.unverified.count(item)) continue;
                    sync.callSyncedAction("rows", b -> {
                        b.writeByte(14);
                        ByteBufAdapters.ITEM_STACK.serializeSafe(b, item);
                        writeSiteCounts(b, currentSite, item);
                    });
                }
                sentSite = currentSite;
                return;
            }
        }
        previous = key;
        sentSite = currentSite;
        sync.callSyncedAction("rows", b -> b.writeByte(0));
        if (task == null) return;
        if (task.upgrading() && !missing && task.state != BuildTask.State.REFUND
                && !(task.state == BuildTask.State.DONE && task.hasReserved())
                && task.state != BuildTask.State.DISMANTLING && task.state != BuildTask.State.TEARDOWN_PAUSED) {
            if (task.state != BuildTask.State.INSPECTING_UPGRADE) {
                for (UpgradeBill.Row row : task.upgradeRows) sync.callSyncedAction("rows", b -> {
                    b.writeByte(11);
                    ByteBufAdapters.ITEM_STACK.serializeSafe(b, row.previous);
                    ByteBufAdapters.ITEM_STACK.serializeSafe(b, row.target);
                    b.writeVarIntToBuffer(row.count);
                });
            }
            return;
        }
        Materials list =
                missing
                        ? (task.errors.rows.isEmpty() ? task.shortage : task.errors)
                        : task.state == BuildTask.State.DISMANTLING
                                ? task.salvage
                                : task.plan != null && task.plan.ready ? task.plan.bill : null;
        if (task.state == BuildTask.State.REFUND || task.state == BuildTask.State.DONE && task.hasReserved()) {
            sendRefundRows(task.backpack, 15);
            sendRefundRows(task.held, 16);
            sendRefundRows(task.salvage, 16);
            return;
        }
        if (list == null) return;
        boolean catalog = !missing && task.plan != null && list == task.plan.bill;
        Map<ItemId, ChoiceEntry> alternatives = new LinkedHashMap<>();
        if (catalog) {
            Materials combined = new Materials();
            for (int index = 0; index < task.plan.choices.size(); index++) {
                MaterialChoice group = task.plan.choices.get(index);
                int[] quantities = task.effectiveAllocation(group);
                for (int i = 0; i < quantities.length; i++) {
                    if (group.isAir(i)) continue;
                    ItemStack item = group.optionItem(i);
                    ItemId id = ItemId.create(item);
                    ChoiceEntry entry = alternatives.get(id);
                    if (entry == null) {
                        entry = new ChoiceEntry(item, index, i);
                        alternatives.put(id, entry);
                    }
                    entry.allocated += quantities[i];
                }
            }
            if (currentSite != null) {
                list = currentSite.total;
            } else {
                for (ChoiceEntry entry : alternatives.values())
                    combined.add(entry.item, list.count(entry.item) + entry.allocated);
                for (Map.Entry<ItemId, Materials.Entry> row : list.rows.entrySet())
                    if (!alternatives.containsKey(row.getKey())) combined.add(row.getValue().item, row.getValue().count);
                list = combined;
            }
        }
        for (Materials.Entry e : list.rows.values()) {
            int amount = e.count;
            if (catalog && currentSite == null
                    && task.existing
                    && Materials.equal(e.item, task.target)) amount--;
            if (amount <= 0) continue;
            final int n = amount;
            ChoiceEntry entry = alternatives.get(ItemId.create(e.item));
            if (catalog && currentSite != null) {
                boolean selectable = entry != null && task.editable();
                sync.callSyncedAction("rows", b -> {
                    b.writeByte(13);
                    b.writeBoolean(selectable);
                    if (selectable) {
                        b.writeInt(task.revision);
                        b.writeVarIntToBuffer(entry.group);
                        b.writeVarIntToBuffer(entry.option);
                        b.writeVarIntToBuffer(entry.allocated);
                        b.writeVarIntToBuffer(task.plan.bill.count(e.item));
                    }
                    ByteBufAdapters.ITEM_STACK.serializeSafe(b, e.item);
                    b.writeVarIntToBuffer(n);
                    writeSiteCounts(b, currentSite, e.item);
                });
                continue;
            }
            if (entry != null && task.editable()) {
                sync.callSyncedAction("rows", b -> {
                    b.writeByte(6);
                    b.writeInt(task.revision);
                    b.writeVarIntToBuffer(entry.group);
                    b.writeVarIntToBuffer(entry.option);
                    b.writeVarIntToBuffer(entry.allocated);
                    ByteBufAdapters.ITEM_STACK.serializeSafe(b, e.item);
                    b.writeVarIntToBuffer(n);
                });
                continue;
            }
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

    private void sendRefundRows(Materials source, int type) {
        for (Materials.Entry e : source.rows.values()) sync.callSyncedAction("rows", b -> {
            b.writeByte(type);
            ByteBufAdapters.ITEM_STACK.serializeSafe(b, e.item);
            b.writeVarIntToBuffer(e.count);
        });
    }

    private static void writeSiteCounts(PacketBuffer b, OrdinaryBuildSurvey site, ItemStack item) {
        b.writeVarIntToBuffer(site.installed.count(item));
        b.writeVarIntToBuffer(site.missing.count(item));
        b.writeVarIntToBuffer(site.blocked.count(item));
        b.writeVarIntToBuffer(site.unverified.count(item));
    }

    private static final class SiteRow {
        final int total;
        int installed, missing, blocked, unverified;
        String count;

        SiteRow(int total) { this.total = total; }

        void read(PacketBuffer b) {
            installed = b.readVarIntFromBuffer();
            missing = b.readVarIntFromBuffer();
            blocked = b.readVarIntFromBuffer();
            unverified = b.readVarIntFromBuffer();
            count = String.format(Locale.ROOT, "%,d / %,d", installed, total);
            if (unverified > 0) count += " *";
        }

        String details() {
            String text = quantity("siteToSupply", missing);
            int inPlace = total - installed - missing - blocked - unverified;
            if (inPlace > 0) text += "  " + quantity("siteInPlace", inPlace);
            if (blocked > 0) text += "  " + quantity("siteBlocked", blocked);
            if (unverified > 0) text += "  " + quantity("siteUnverified", unverified);
            return text;
        }
    }

    private void receive(PacketBuffer b) {
        int type = b.readUnsignedByte();
        if (type == 14) {
            ItemStack stack = ByteBufAdapters.ITEM_STACK.deserializeSafe(b);
            SiteRow row = stack == null ? null : siteRows.get(ItemId.create(stack));
            if (row != null) row.read(b);
            return;
        }
        if (type == 11) {
            ItemStack old = ByteBufAdapters.ITEM_STACK.deserializeSafe(b);
            ItemStack next = ByteBufAdapters.ITEM_STACK.deserializeSafe(b);
            int count = b.readVarIntFromBuffer();
            if (old != null && next != null) replacementRow(old, next, count);
            return;
        }
        if (type == 0) {
            siteRows.clear();
            lines.clear();
            jobs.removeAll();
            materials.removeAll();
            deficits.removeAll();
            return;
        }
        if (type == 9) {
            roles.removeAll();
            return;
        }
        if (type == 10) {
            int revision = b.readInt(), index = b.readVarIntFromBuffer(), count = b.readVarIntFromBuffer();
            String position = b.readInt() + ", " + b.readInt() + ", " + b.readInt();
            String title = StatCollector.translateToLocalFormatted("structurebuildernh.rolePosition", position, count);
            ParentWidget<?> row = new ParentWidget<>().size(ROW_WIDTH, 20);
            control(row, 0, 1, ROW_WIDTH, () -> title, () -> sendChoice(false, index, revision), false);
            roles.child(row);
            return;
        }
        if (type == 7) {
            choiceRevision = b.readInt();
            choiceIndex = b.readVarIntFromBuffer();
            choiceSize = b.readVarIntFromBuffer();
            amounts.clear();
            alternatives.removeAll();
            return;
        }
        if (type == 8) {
            ItemStack stack = b.readBoolean() ? null : ByteBufAdapters.ITEM_STACK.deserializeSafe(b);
            optionRow(stack, b.readVarIntFromBuffer());
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
            String state = readStringSafe(b);
            int placed = b.readVarIntFromBuffer(), total = b.readVarIntFromBuffer();
            boolean preview = b.readBoolean(), canDismantle = b.readBoolean();
            boolean upgrade = b.readBoolean();
            boolean surveyed = b.readBoolean();
            boolean partial = surveyed && b.readVarIntFromBuffer() > 0;
            String phase = tr(upgrade && state.equals("DONE") ? "upgradeDone"
                    : upgrade && state.equals("BUILDING") ? "upgrading" : "state." + state);
            if (upgrade && !state.equals("DONE") && !state.equals("BUILDING")
                    && !state.equals("INSPECTING_UPGRADE") && !state.equals("UPGRADE_REVIEW"))
                phase = tr("upgradeTask") + " | " + phase;
            if (row == null) return;
            row.phase = preview ? phase + " | " + tr("state.PREVIEW") : phase;
            row.placed = placed;
            row.total = total;
            row.surveyed = surveyed;
            row.progress = total == 0 ? "" : placed + " / " + total + (partial ? " *" : "");
            if (!canDismantle) row.confirmDismantle = false;
            row.layout(canDismantle);
            if (type == 2) {
                row.entry.child(row.body
                        .background(fill(jobs.getChildren().size() % 2 == 0 ? STRIPE : SURFACE))
                        .overlay(row::draw)
                        .tooltip(t -> t.addLine(IKey.dynamic(row::description)))
                        .onMousePressed(i -> {
                            if (i != 0) return false;
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
        boolean surveyed = type == 13;
        boolean selectable = surveyed ? b.readBoolean() : type == 6;
        int revision = selectable ? b.readInt() : 0;
        int group = selectable ? b.readVarIntFromBuffer() : 0;
        int option = selectable ? b.readVarIntFromBuffer() : 0;
        int flexible = selectable ? b.readVarIntFromBuffer() : 0;
        int fixed = surveyed && selectable ? b.readVarIntFromBuffer() : 0;
        ItemStack s = ByteBufAdapters.ITEM_STACK.deserializeSafe(b);
        int n = b.readVarIntFromBuffer();
        boolean refundRow = type == 15 || type == 16;
        boolean quoted = !surveyed && !selectable && !refundRow && b.readBoolean();
        int required = quoted ? b.readVarIntFromBuffer() : 0,
                reserved = quoted ? b.readVarIntFromBuffer() : 0;
        if (s == null) return;
        String count = refundRow ? quantity(type == 15 ? "refundBagCount" : "refundMECount", n)
                : String.format(Locale.ROOT, "%,d", n);
        SiteRow progress = surveyed ? new SiteRow(n) : null;
        if (surveyed) {
            progress.read(b);
            siteRows.put(ItemId.create(s), progress);
        }
        int width = textWidth(surveyed ? count + " / " + count + " *" : count) + 3;
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
                                (surveyed ? IKey.dynamic(() -> progress.count) : IKey.str(count))
                                        .asWidget()
                                        .pos(rowWidth - 3 - width, 0)
                                        .size(width, 16)
                                        .textAlign(Alignment.CenterRight)
                                        .color(0xffa8e4d8)
                                        .shadow(false))
                        .tooltip(
                                t -> {
                                    t.addFromItem(s);
                                    if (surveyed) t.addLine(IKey.dynamic(progress::details));
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
        if (selectable) {
            ButtonWidget<?> hit = new ButtonWidget<>().size(rowWidth, 16)
                    .background(fill(0x00000000)).hoverBackground(fill(0x2273bfaa))
                    .onMousePressed(button -> {
                        if (button != 0) return false;
                        sync.callSyncedAction("openMaterial", packet -> {
                            writeStringSafe(packet, selectedId.getStringValue());
                            packet.writeInt(revision);
                            packet.writeVarIntToBuffer(group);
                            packet.writeVarIntToBuffer(option);
                        });
                        return true;
                    }).tooltip(t -> {
                        t.addFromItem(s);
                        if (surveyed) t.addLine(IKey.dynamic(progress::details));
                        t.addLine(IKey.str(StatCollector.translateToLocalFormatted(
                                "structurebuildernh.choiceHint", String.format(Locale.ROOT, "%,d", surveyed ? fixed : n - flexible),
                                String.format(Locale.ROOT, "%,d", flexible))));
                    });
            row.child(hit);
        }
        rows.child(row);
    }

    private void replacementRow(ItemStack old, ItemStack next, int count) {
        ParentWidget<?> row = new ParentWidget<>().size(MATERIAL_WIDTH, 32)
                .background(fill(materials.getChildren().size() % 2 == 0 ? STRIPE : SURFACE));
        row.child(new ItemDrawable(old).asWidget().pos(1, 0));
        row.child(new ItemDrawable(next).asWidget().pos(1, 16));
        String quantity = String.format(Locale.ROOT, "%,d", count);
        int countWidth = textWidth(quantity) + 6;
        singleLine(row, old::getDisplayName, 20, 1, MATERIAL_WIDTH - 23, 14, MUTED);
        singleLine(row, () -> "→ " + next.getDisplayName(), 20, 17, MATERIAL_WIDTH - 23 - countWidth, 14, INK);
        label(row, () -> quantity, MATERIAL_WIDTH - countWidth - 2, 17, countWidth, 14, ACCENT, Alignment.CenterRight);
        materials.child(row);
    }

    private long remainder() {
        long left = choiceSize;
        for (int i = 1; i < amounts.size(); i++) left -= amounts.get(i).getIntValue();
        return left;
    }

    private void optionRow(ItemStack item, int count) {
        int index = amounts.size();
        IntValue value = new IntValue(count);
        amounts.add(value);
        ParentWidget<?> row = new ParentWidget<>().size(ROW_WIDTH, 22).marginBottom(2).background(fill(STRIPE));
        if (item != null) row.child(new ItemDrawable(item).asWidget().pos(3, 3));
        Supplier<String> name = () -> item == null ? tr("previewAir") : item.getDisplayName();
        singleLine(row, name, 25, 1, ROW_WIDTH - 136, 20, INK);
        if (index == 0) label(row, () -> String.format(Locale.ROOT, "%,d", Math.max(0, remainder())),
                ROW_WIDTH - 105, 2, 57, 18, ACCENT, Alignment.CenterRight);
        else row.child(new TextFieldWidget().value(value).numbersInt(0, choiceSize)
                .pos(ROW_WIDTH - 104, 3).size(56, 16));
        control(row, ROW_WIDTH - 43, 2, 41, () -> tr("choiceAll"), () -> {
            for (int i = 1; i < amounts.size(); i++) amounts.get(i).setIntValue(i == index ? choiceSize : 0);
        }, false).tooltip(t -> t.addLine(IKey.str(tr("choiceAll") + " - " + name.get())));
        alternatives.child(row);
    }
}
