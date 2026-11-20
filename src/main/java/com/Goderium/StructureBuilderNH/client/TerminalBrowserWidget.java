package com.Goderium.StructureBuilderNH.client;

import static com.cleanroommc.modularui.network.NetworkUtils.readStringSafe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.Goderium.StructureBuilderNH.common.builder.BrowserPacket;
import com.Goderium.StructureBuilderNH.common.builder.TerminalBrowser;
import com.Goderium.StructureBuilderNH.util.Bounds;
import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.value.IntValue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.StatCollector;

/** A client-only MUI viewport. The side panel draws just its visible rows. */
public final class TerminalBrowserWidget extends ParentWidget<TerminalBrowserWidget>
        implements Interactable, TerminalBrowser.Display {
    private enum Drawer { NONE, MATERIALS, CHANNELS }
    private static final int X = 9, RIGHT = 307, TOP = 47, BOTTOM = 187, SIDE = 197, CHANNEL_SIDE = 174;
    private static final int LIST = 67, PANEL_END = 206, LINE = 17, BUTTON = 17;
    private static final int CHANNEL_ROWS = 4, CHANNEL_HEIGHT = 28, ACTION_Y = 187;
    private static final int DETAIL_TOP = TOP + 3, DETAIL_PADDING = 4, DETAIL_TEXT_WIDTH = 108,
            DETAIL_ROWS = 8, DETAIL_LINE = 11;
    private enum Control {
        BACK(X, 5, 44, "back"), WHOLE(X, 27, 47, "browser.whole"), SECTION(58, 27, 52, "browser.slice"),
        HATCHES(137, 27, 49, "browser.hatches"), MATERIALS(188, 27, 56, "browser.materials"),
        CHANNELS(246, 27, 61, "channels");

        final int x, y, width;
        final String key;
        Control(int x, int y, int width, String key) {
            this.x = x; this.y = y; this.width = width; this.key = key;
        }
    }
    private static final int TEXT = 0xffecf2f0, MUTED = 0xffadc0c2, ACCENT = 0xff9ee3cb,
            RULE = 0xff456064, BASE = 0xff243437, WELL = 0xff19282b, KEY = 0xff394f53, ACTIVE = 0xff346759;

    private static final class Channel {
        final String key;
        final int min, max;
        final ChannelBlockLabel label;
        boolean upgrade;
        String site = "";
        int value, pending;

        Channel(String key, int value, int min, int max) {
            this.key = key;
            this.value = value;
            this.min = min;
            this.max = max;
            label = new ChannelBlockLabel(key, min, max);
        }
    }

    private final TerminalBrowser port;
    private static TerminalBrowserWidget visibleBrowser;
    private GuiScreen hostScreen;
    private final Supplier<String> title;
    private final List<Channel> channels = new ArrayList<>();
    private final Channel[] editingRows = new Channel[CHANNEL_ROWS];
    private final Set<String> replacementTargets = new LinkedHashSet<>();
    private boolean scopeEdited;
    private final RenderItem icons = new RenderItem();
    private BrowserRenderer model = new BrowserRenderer();
    private Drawer drawer = Drawer.NONE;
    private String fullText = "";
    private boolean dual, isolated, hatches, writable, dirty, dragMap;
    private int axis = 1, layer, scroll, focus = -1, drag = -1, lastX, lastY, travel;
    private long generation = -1;
    private int sequence;
    private BrowserRenderer.Pick selected, hovered;
    private BrowserRenderer.Pick restorePick;
    private boolean restoreFocus;
    private int modelRevision = -1;
    private int lastHitX = Integer.MIN_VALUE, lastHitY, lastHitSections;

    public TerminalBrowserWidget(TerminalBrowser port, Supplier<String> title) {
        this.port = port;
        this.title = title;
        for (int i = 0; i < CHANNEL_ROWS; i++) {
            final int row = i;
            IntValue.Dynamic v = new IntValue.Dynamic(() -> rowValue(row), n -> setRow(row, n));
            ChannelLevelField field = new ChannelLevelField(v::getIntValue, n -> setRow(row, n)) {
                @Override public Result onMousePressed(int button) {
                    if (button == 1) { back(); return Result.SUCCESS; }
                    return super.onMousePressed(button);
                }
                @Override public void onFocus(ModularGuiContext context) {
                    editingRows[row] = channelAt(row);
                    if (writable && editingRows[row] != null && editingRows[row].upgrade) selectTarget(editingRows[row]);
                    super.onFocus(context);
                }
                @Override public void onRemoveFocus(ModularGuiContext context) {
                    try { super.onRemoveFocus(context); }
                    finally { editingRows[row] = null; }
                }
            };
            child(field.value(v).numbersInt(0, Integer.MAX_VALUE).pos(RIGHT - 36, LIST + i * CHANNEL_HEIGHT + 5).size(33, 16)
                    .setEnabledIf(w -> channelAt(row) != null));
            child(new ButtonWidget<>().pos(CHANNEL_SIDE + 2, LIST + i * CHANNEL_HEIGHT + 13).size(14, 13)
                    .overlay((ctx, x, y, w, h, theme) -> {
                        Channel c = channelAt(row);
                        if (c != null) (replacementTargets.contains(c.key) ? GuiTextures.CHECK_BOX_FULL : GuiTextures.CHECK_BOX_EMPTY)
                                .draw(ctx, x + 1, y + 1, 12, 12, theme);
                    }).tooltip(t -> t.addLine(IKey.str(tr("replaceChannel"))))
                    .onMousePressed(button -> {
                        if (button != 0) return false;
                        getContext().removeFocus();
                        Channel c = channelAt(row);
                        if (c == null || !c.upgrade || !writable) return false;
                        if (!replacementTargets.remove(c.key)) replacementTargets.add(c.key);
                        scopeEdited = true;
                        return true;
                    }).setEnabledIf(w -> writable && channelAt(row) != null && channelAt(row).upgrade));
        }
    }

    private void selectTarget(Channel channel) {
        replacementTargets.add(channel.key);
        scopeEdited = true;
    }

    private boolean canApply() {
        return dirty || scopeEdited || !replacementTargets.isEmpty();
    }

    private Channel channelAt(int row) {
        int i = scroll + row;
        return drawer == Drawer.CHANNELS && i >= 0 && i < channels.size() ? channels.get(i) : null;
    }

    private int rowValue(int row) {
        Channel c = editingRows[row] != null ? editingRows[row] : channelAt(row);
        return c == null ? 0 : c.value;
    }

    private int setRow(int row, int n) {
        Channel c = editingRows[row] != null ? editingRows[row] : channelAt(row);
        if (c == null || !channels.contains(c)) return 0;
        int value = n <= 0 && !c.key.isEmpty() ? 0 : cap(n, Math.max(1, c.min), c.max);
        if (value != c.value) {
            if (writable && c.upgrade) selectTarget(c);
            c.value = value;
            dirty = true;
            c.pending = ++sequence;
            port.edit(generation, c.pending, c.key, value);
        }
        return value;
    }

    @Override
    public void settings(PacketBuffer b) {
        long id = b.readLong();
        if (generation != id) {
            close();
            generation = id;
        }
        int ack = b.readInt();
        writable = b.readBoolean();
        dirty = b.readBoolean();
        int main = b.readVarIntFromBuffer(), count = b.readVarIntFromBuffer();
        if (count < 0 || count > 4096) return;
        Map<String, Channel> old = new HashMap<>();
        for (Channel c : channels) old.put(c.key, c);
        channels.clear();
        channel(old, "", main, 1, 4096, ack);
        for (int i = 0; i < count; i++) {
            String key = readStringSafe(b);
            Channel c = channel(old, key, b.readVarIntFromBuffer(), b.readVarIntFromBuffer(), b.readVarIntFromBuffer(), ack);
            c.upgrade = b.readBoolean();
            boolean previouslySelected = b.readBoolean();
            if (!c.upgrade) replacementTargets.remove(key);
            else if (!scopeEdited) {
                if (previouslySelected) replacementTargets.add(key);
                else replacementTargets.remove(key);
            }
            c.site = "";
            if (b.readBoolean()) {
                int size = b.readVarIntFromBuffer();
                if (size < 0 || size > 4096) return;
                StringJoiner found = new StringJoiner(" / ");
                for (int j = 0; j < size; j++) found.add(String.valueOf(b.readVarIntFromBuffer()));
                boolean partial = b.readBoolean();
                c.site = size == 0 ? tr(partial ? "siteUnknown" : "siteEmpty")
                        : StatCollector.translateToLocalFormatted("structurebuildernh."
                                + (partial ? "siteLevelsPartial" : "siteLevels"), found);
            }
        }
        replacementTargets.removeAll(old.keySet());
    }

    private Channel channel(Map<String, Channel> old, String key, int value, int min, int max, int ack) {
        Channel c = old.remove(key);
        if (c == null || c.min != min || c.max != max) c = new Channel(key, value, min, max);
        if (c.pending <= ack) c.value = value;
        else dirty = true;
        channels.add(c);
        return c;
    }

    @Override
    public void accept(BrowserPacket packet) {
        if (packet.type == BrowserPacket.HEADER && packet.revision != modelRevision) {
            modelRevision = packet.revision;
            // Palette ids belong to one revision. Re-resolve the same position after its section arrives.
            if (selected != null) {
                restorePick = selected;
                restoreFocus = focus >= 0;
            }
            selected = null;
            focus = -1;
            details.clear();
        }
        model.accept(packet);
        lastHitX = Integer.MIN_VALUE;
    }

    @Override
    public void close() {
        hideViewport();
        model.close();
        model = new BrowserRenderer();
        generation = -1;
        sequence = 0;
        drawer = Drawer.NONE;
        selected = hovered = null;
        restorePick = null;
        restoreFocus = false;
        details.clear();
        modelRevision = -1;
        channels.clear();
        java.util.Arrays.fill(editingRows, null);
        replacementTargets.clear();
        scopeEdited = false;
        isolated = dual = hatches = writable = dirty = false;
        axis = 1;
        focus = drag = -1;
        scroll = 0;
        lastHitX = Integer.MIN_VALUE;
    }

    @Override
    public void dispose() {
        hideViewport();
        model.close();
        super.dispose();
    }

    private void hideViewport() {
        if (visibleBrowser == this) visibleBrowser = null;
        hostScreen = null;
    }

    static boolean coversWorld(GuiScreen screen) {
        return screen != null && visibleBrowser != null && visibleBrowser.hostScreen == screen;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("structurebuildernh." + key);
    }

    private static FontRenderer font() {
        return Minecraft.getMinecraft().fontRendererObj;
    }

    private static void rect(int x, int y, int w, int h, int color) {
        if (w > 0 && h > 0) Gui.drawRect(x, y, x + w, y + h, color);
    }

    private int mx() { return getContext().getMouseX(); }
    private int my() { return getContext().getMouseY(); }

    // MUI updates its hover coordinates on draw. A fast drag can start and end between two draws.
    private int eventX() {
        Minecraft mc = Minecraft.getMinecraft();
        return Mouse.getEventX() * mc.currentScreen.width / mc.displayWidth - getArea().x;
    }

    private int eventY() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.currentScreen.height - 1 - Mouse.getEventY() * mc.currentScreen.height / mc.displayHeight - getArea().y;
    }
    private boolean inside(int x, int y, int w, int h) {
        return mx() >= x && mx() < x + w && my() >= y && my() < y + h;
    }

    private void line(String value, int x, int y, int w, int color) {
        if (value == null || w <= 0) return;
        String text = font().trimStringToWidth(value, w);
        if (text.length() < value.length() && inside(x, y - 2, w, 12)) fullText = value;
        font().drawString(text, x, y, color);
    }

    private void key(int x, int y, int w, String value, boolean active, boolean enabled) {
        int fill = !enabled ? WELL : active ? ACTIVE : inside(x, y, w, BUTTON) ? RULE : KEY;
        rect(x, y, w, BUTTON, fill);
        if (active) rect(x, y + BUTTON - 1, w, 1, ACCENT);
        int offset = Math.max(3, (w - font().getStringWidth(value)) / 2);
        line(value, x + offset, y + (BUTTON - font().FONT_HEIGHT) / 2, w - offset - 2, enabled ? TEXT : MUTED);
    }

    private void key(Control control, boolean active) {
        key(control.x, control.y, control.width, tr(control.key), active, true);
    }

    private boolean hit(Control control) { return inside(control.x, control.y, control.width, BUTTON); }

    private void icon(ItemStack item, int x, int y) {
        if (item == null) return;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glColor4f(1, 1, 1, 1);
            RenderHelper.enableGUIStandardItemLighting();
            icons.renderItemAndEffectIntoGUI(font(), Minecraft.getMinecraft().getTextureManager(), item, x, y);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private int drawerLeft() { return drawer == Drawer.CHANNELS ? CHANNEL_SIDE : SIDE; }
    private int viewportEnd() { return drawer == Drawer.NONE ? RIGHT : drawerLeft() - 3; }
    private int splitX() { return X + (viewportEnd() - X - 1) / 2; }
    private static int component(Bounds b, int axis, boolean max) {
        if (axis == 0) return max ? b.maxX : b.minX;
        if (axis == 2) return max ? b.maxZ : b.minZ;
        return max ? b.maxY : b.minY;
    }
    private String axisName() { return String.valueOf("XYZ".charAt(axis)); }

    @Override
    public void draw(ModularGuiContext ctx, WidgetThemeEntry<?> theme) {
        hostScreen = Minecraft.getMinecraft().currentScreen;
        visibleBrowser = this;
        fullText = "";
        Bounds b = model.bounds();
        key(Control.BACK, false);
        String dims = b == null ? "" : (b.maxX - b.minX + 1) + " × " + (b.maxY - b.minY + 1)
                + " × " + (b.maxZ - b.minZ + 1);
        int sizeWidth = font().getStringWidth(dims);
        line(title.get(), 58, 9, RIGHT - sizeWidth - 63, TEXT);
        line(dims, RIGHT - sizeWidth, 9, sizeWidth, MUTED);
        rect(X, 24, RIGHT - X, 1, RULE);
        key(Control.WHOLE, !dual);
        key(Control.SECTION, dual);
        key(Control.HATCHES, hatches);
        key(Control.MATERIALS, drawer == Drawer.MATERIALS);
        key(Control.CHANNELS, drawer == Drawer.CHANNELS);
        int end = viewportEnd(), split = dual ? splitX() : end;
        rect(X, TOP, end - X, BOTTOM - TOP, WELL);
        if (isolated && b != null) {
            int valid = Math.max(component(b, axis, false), Math.min(component(b, axis, true), layer));
            if (valid != layer || !model.sliced() || model.sliceAxis() != axis || model.sliceLayer() != valid) setLayer(valid);
        }
        int ox = getArea().x, oy = getArea().y, ax = ctx.getAbsMouseX(), ay = ctx.getAbsMouseY();
        model.draw(ox + X, oy + TOP, split - X, BOTTOM - TOP, ax, ay);
        if (restorePick != null) {
            BrowserRenderer.Pick p = model.at(restorePick.x, restorePick.y, restorePick.z);
            if (p != null || model.ready()) {
                restorePick = null;
                selected = p;
                model.select(p);
                if (restoreFocus && p != null) { focus = p.materialId; model.setFocus(focus); }
                restoreFocus = false;
                rebuildDetails();
            }
        }
        if (dual) {
            rect(split, TOP, 1, BOTTOM - TOP, RULE);
            model.drawSlice(ox + split + 1, oy + TOP, end - split - 1, BOTTOM - TOP, ax, ay);
            if (!model.sliceReady()) line(tr("browser.loadingSlice"), split + 4, TOP + 4, end - split - 8, MUTED);
        }
        if (drag < 0 && (ax != lastHitX || ay != lastHitY || lastHitSections != model.loadedSections())) {
            hovered = !overDetails() && inside(X, TOP, end - X, BOTTOM - TOP) ?
                    (dual && mx() > split ? model.pickSlice(ax, ay) : model.pick(ax, ay)) : null;
            lastHitX = ax;
            lastHitY = ay;
            lastHitSections = model.loadedSections();
        }
        if (b == null || !model.ready() && model.loadedSections() == 0) {
            String error = model.error();
            line(error == null || error.isEmpty() ? tr("browser.loading") : localError(error),
                    X + 4, TOP + 6, end - X - 8, ACCENT);
        }
        key(end - 41, BOTTOM - BUTTON, 41, tr("browser.reset"), false, true);
        drawSlider(b);
        if (drawer != Drawer.NONE) drawDrawer();
        if (selected != null) drawDetails();
        drawFooter();
    }

    private String localError(String message) {
        String key = "structurebuildernh." + message;
        return StatCollector.canTranslate(key) ? StatCollector.translateToLocal(key) : message;
    }

    private void drawSlider(Bounds b) {
        key(X, 191, 21, axisName(), false, true);
        int right = viewportEnd(), labelWidth = 30;
        String text = tr("browser.all");
        if (b != null && isolated) {
            text = (layer - component(b, axis, false) + 1) + "/" + (component(b, axis, true) - component(b, axis, false) + 1);
            labelWidth = Math.max(labelWidth, font().getStringWidth(text) + 3);
        }
        int end = right - labelWidth - 3, start = 35;
        rect(start, 197, end - start, 3, RULE);
        double t = 0;
        if (b != null && isolated) t = (layer - component(b, axis, false) + (dual ? 0d : 1d))
                / Math.max(1, component(b, axis, true) - component(b, axis, false) + (dual ? 0 : 1));
        rect(start + (int) (t * (end - start - 4)), 194, 4, 9, ACCENT);
        line(text, right - labelWidth, 194, labelWidth, MUTED);
    }

    private void drawFooter() {
        rect(X, 209, RIGHT - X, 1, RULE);
        String status = fullText;
        if (status.isEmpty() && hovered != null) status = pickName(hovered) + "   " + coords(hovered);
        if (status.isEmpty() && !model.ready()) status = model.error() == null || model.error().isEmpty()
                ? tr("browser.loading") + "  " + model.loadedSections() + "/" + model.totalSections()
                : localError(model.error());
        if (status.isEmpty()) status = tr(dual ? "browser.sliceControls" : "browser.controls");
        line(status, X, 217, RIGHT - X, MUTED);
    }

    private String pickName(BrowserRenderer.Pick p) {
        return p.item == null ? tr(p.air ? "previewAir" : "browser.block") : p.item.getDisplayName();
    }
    private static String coords(BrowserRenderer.Pick p) { return p.x + ", " + p.y + ", " + p.z; }

    private List<BrowserRenderer.MaterialEntry> materialRows() {
        return isolated ? model.sliceMaterials() : model.materials();
    }
    private static int cap(long value, long lo, long hi) { return (int) Math.min(hi, Math.max(lo, value)); }
    private static int shownRows() { return (PANEL_END - LIST) / LINE; }

    private void drawDrawer() {
        int left = drawerLeft();
        rect(left, TOP, RIGHT - left, 159, WELL);
        String caption = drawer == Drawer.MATERIALS ? tr("materials") : tr("channels");
        line(caption, left + 4, TOP + 5, RIGHT - left - 24, ACCENT);
        key(RIGHT - 15, TOP, 15, "×", false, true);
        if (drawer == Drawer.CHANNELS) {
            drawChannels();
        } else {
            drawMaterials();
        }
    }

    private void drawChannels() {
        scroll = cap(scroll, 0, Math.max(0, channels.size() - CHANNEL_ROWS));
        int inherited = channels.isEmpty() ? 1 : channels.get(0).value;
        for (int i = 0; i < CHANNEL_ROWS && scroll + i < channels.size(); i++) {
            Channel c = channels.get(scroll + i);
            int y = LIST + i * CHANNEL_HEIGHT;
            ItemStack block = c.label.sample(c.value, inherited);
            rect(CHANNEL_SIDE + 1, y, RIGHT - CHANNEL_SIDE - 2, CHANNEL_HEIGHT - 1, i % 2 == 0 ? BASE : WELL);
            line(c.label.title(block), CHANNEL_SIDE + 3, y + 3, RIGHT - CHANNEL_SIDE - 41, TEXT);
            int detailX = CHANNEL_SIDE + (writable && c.upgrade ? 18 : 3);
            line(c.site.isEmpty() ? c.label.detail(block) : c.site, detailX, y + 15, RIGHT - 38 - detailX, MUTED);
        }
        scrollBar(channels.size(), CHANNEL_ROWS, CHANNEL_ROWS * CHANNEL_HEIGHT);
        if (writable) {
            key(CHANNEL_SIDE + 2, ACTION_Y, 60, tr("browser.share"), false, true);
            key(CHANNEL_SIDE + 64, ACTION_Y, RIGHT - CHANNEL_SIDE - 66,
                    tr(replacementTargets.isEmpty() ? "apply" : "checkUpgrade"), false, canApply());
            if (inside(CHANNEL_SIDE + 2, ACTION_Y, 60, BUTTON)) fullText = tr("shareHelp");
        }
    }

    private void drawMaterials() {
        List<BrowserRenderer.MaterialEntry> rows = materialRows();
        scroll = cap(scroll, 0, Math.max(0, rows.size() - shownRows()));
        for (int i = 0; i < shownRows() && scroll + i < rows.size(); i++) {
            BrowserRenderer.MaterialEntry m = rows.get(scroll + i);
            int y = LIST + i * LINE;
            String n = Long.toString(m.count);
            int nw = font().getStringWidth(n);
            rect(SIDE + 1, y, RIGHT - SIDE - 2, LINE - 1, focus == m.id ? ACTIVE : KEY);
            icon(m.item, SIDE + 1, y);
            line(m.name, SIDE + 18, y + 3, RIGHT - SIDE - nw - 23, TEXT);
            line(n, RIGHT - nw - 3, y + 3, nw, ACCENT);
        }
        scrollBar(rows.size(), shownRows(), shownRows() * LINE);
    }

    private final List<String> details = new ArrayList<>();
    private int detailWidth, detailScroll;

    private void rebuildDetails() {
        details.clear();
        detailScroll = 0;
        lastHitX = Integer.MIN_VALUE;
        if (selected == null) return;
        detailWidth = font().getStringWidth(tr("browser.locateSlice")) + 4 + 2 * DETAIL_PADDING;
        String blockName = pickName(selected);
        addDetail(blockName + ":");
        addDetail(coords(selected));
        if (selected.hatch) {
            addDetail(selected.dots > 0
                    ? StatCollector.translateToLocalFormatted("structurebuildernh.browser.dotHatches", selected.dots)
                    : tr("browser.supportedHatches"));
            List<String> names = HatchLabels.browserNames(selected.hatchKeys);
            if (names.isEmpty()) addDetail(tr("browser.unspecifiedHatches"));
            else addHatchRows(names);
        }
        Set<String> labels = new LinkedHashSet<>();
        if (selected.channels != null) for (String key : selected.channels) labels.add(ChannelBlockLabel.channelName(key));
        if (selected.options != null) for (ItemStack s : selected.options) {
            String option = s == null ? tr("previewAir") : s.getDisplayName();
            if (!option.equals(blockName)) labels.add(option);
        }
        for (String label : labels) addDetail(label);
    }

    private void addHatchRows(List<String> names) {
        String row = "";
        for (int i = 0; i < names.size(); i++) {
            String entry = names.get(i) + (i + 1 == names.size() ? "" : ",");
            String next = row.isEmpty() ? entry : row + " " + entry;
            if (!row.isEmpty() && font().getStringWidth(next) > DETAIL_TEXT_WIDTH) {
                addDetail(row);
                row = entry;
            } else {
                row = next;
            }
        }
        addDetail(row);
    }

    private void addDetail(String text) {
        details.addAll(font().listFormattedStringToWidth(text, DETAIL_TEXT_WIDTH));
        int width = Math.min(DETAIL_TEXT_WIDTH, font().getStringWidth(text));
        detailWidth = Math.max(detailWidth, width + 2 * DETAIL_PADDING);
    }

    private int detailX() { return viewportEnd() - 3 - detailWidth; }
    private int detailRows() { return Math.min(DETAIL_ROWS, details.size()); }
    private int detailActionY() { return DETAIL_TOP + DETAIL_PADDING + detailRows() * DETAIL_LINE + 1; }
    private boolean overDetails() {
        return selected != null && inside(detailX(), DETAIL_TOP, detailWidth,
                detailActionY() + BUTTON + DETAIL_PADDING - DETAIL_TOP);
    }

    private void drawDetails() {
        int x = detailX(), y = DETAIL_TOP + DETAIL_PADDING, rows = detailRows();
        int height = detailActionY() + BUTTON + DETAIL_PADDING - DETAIL_TOP;
        rect(x, DETAIL_TOP, detailWidth, height, RULE);
        rect(x + 1, DETAIL_TOP + 1, detailWidth - 2, height - 2, WELL);
        for (int i = 0; i < rows; i++) {
            int index = detailScroll + i;
            line(details.get(index), x + DETAIL_PADDING, y + i * DETAIL_LINE, detailWidth - 2 * DETAIL_PADDING,
                    index == 0 ? ACCENT : TEXT);
        }
        scrollBar(x + detailWidth - 3, y, details.size(), rows, rows * DETAIL_LINE, detailScroll);
        key(x + 2, detailActionY(), detailWidth - 4, tr("browser.locateSlice"), false, true);
    }

    private void scrollBar(int count, int visible, int height) {
        scrollBar(RIGHT - 1, LIST, count, visible, height, scroll);
    }

    private void scrollBar(int x, int y, int count, int visible, int height, int first) {
        if (count <= visible) return;
        int thumb = Math.max(4, (int) ((long) height * visible / count));
        int offset = (int) ((long) (height - thumb) * first / (count - visible));
        rect(x, y, 1, height, BASE);
        rect(x, y + offset, 1, thumb, MUTED);
    }

    private void show(Drawer next) {
        getContext().removeFocus();
        if ((drawer == Drawer.CHANNELS) != (next == Drawer.CHANNELS) && generation >= 0)
            port.channelsVisible(generation, next == Drawer.CHANNELS);
        drawer = next;
        scroll = 0;
        hovered = null;
        lastHitX = Integer.MIN_VALUE;
    }

    private void back() {
        if (selected != null || restorePick != null) {
            inspect(null);
            model.clearFocus();
            focus = -1;
        } else if (drawer != Drawer.NONE) {
            show(Drawer.NONE);
        } else {
            port.back();
        }
    }

    private void inspect(BrowserRenderer.Pick pick) {
        restorePick = null;
        restoreFocus = false;
        selected = pick;
        model.select(pick);
        rebuildDetails();
    }

    private void setLayer(int coordinate) {
        layer = coordinate;
        isolated = true;
        model.setSliceContext(dual);
        model.setSlice(axis, layer);
        lastHitX = Integer.MIN_VALUE;
    }

    private void switchView(boolean cut) {
        dual = cut;
        model.setSliceContext(cut);
        Bounds bounds = model.bounds();
        if (cut) {
            if (bounds != null) setLayer(isolated ? layer : (component(bounds, axis, false) + component(bounds, axis, true)) / 2);
            else isolated = true;
        } else {
            isolated = false;
            model.clearSlice();
        }
        lastHitX = Integer.MIN_VALUE;
    }

    private void slide(int x) {
        Bounds b = model.bounds();
        if (b == null) return;
        int first = component(b, axis, false), last = component(b, axis, true);
        int labelWidth = Math.max(30, isolated ? font().getStringWidth((layer - first + 1) + "/" + (last - first + 1)) + 3 : 0);
        int start = 35, end = viewportEnd() - labelWidth - 7;
        double fraction = Math.max(0D, Math.min(1D, (x - start) / (double) Math.max(1, end - start)));
        int base = dual ? first : first - 1;
        int next = base + (int) Math.round(fraction * (last - base));
        if (!dual && next < first) switchView(false);
        else if (!isolated || next != layer) setLayer(next);
    }

    @Override
    public Result onMousePressed(int button) {
        int x = mx(), y = my(), end = viewportEnd();
        boolean viewport = inside(X, TOP, end - X, BOTTOM - TOP);
        if (button == 1 && (!viewport || overDetails() || inside(end - 41, BOTTOM - BUTTON, 41, BUTTON))) {
            back();
            return Result.SUCCESS;
        }
        if (button == 0 && hit(Control.BACK)) { back(); return Result.SUCCESS; }
        if (button == 0 && y >= Control.WHOLE.y && y < Control.WHOLE.y + BUTTON) {
            if (hit(Control.WHOLE)) switchView(false);
            else if (hit(Control.SECTION)) switchView(true);
            else if (hit(Control.HATCHES)) { hatches = !hatches; model.setHatches(hatches); }
            else if (hit(Control.MATERIALS)) show(drawer == Drawer.MATERIALS ? Drawer.NONE : Drawer.MATERIALS);
            else if (hit(Control.CHANNELS)) show(drawer == Drawer.CHANNELS ? Drawer.NONE : Drawer.CHANNELS);
            else return Result.IGNORE;
            return Result.SUCCESS;
        }
        if (overDetails()) {
            if (button == 0 && inside(detailX() + 2, detailActionY(), detailWidth - 4, BUTTON)) {
                dual = true;
                setLayer(axis == 0 ? selected.x : axis == 2 ? selected.z : selected.y);
            }
            return Result.SUCCESS;
        }
        if (drawer != Drawer.NONE && x >= drawerLeft() && x < RIGHT && y >= TOP && y < 206) {
            if (button != 0) return Result.IGNORE;
            clickDrawer(x, y);
            return Result.SUCCESS;
        }
        if (button == 0 && inside(X, 191, end - X, BUTTON)) {
            if (x < 31) {
                axis = (axis + 1) % 3;
                if (isolated && model.bounds() != null) setLayer(cap(layer, component(model.bounds(), axis, false), component(model.bounds(), axis, true)));
            } else { drag = 3; slide(x); }
            return Result.SUCCESS;
        }
        if (!viewport || button > 2) return Result.IGNORE;
        if (button == 0 && inside(end - 41, BOTTOM - BUTTON, 41, BUTTON)) {
            model.resetCamera();
            lastHitX = Integer.MIN_VALUE;
            return Result.SUCCESS;
        }
        drag = button;
        lastX = eventX();
        lastY = eventY();
        dragMap = dual && lastX > splitX();
        travel = 0;
        return Result.SUCCESS;
    }

    private void clickDrawer(int x, int y) {
        if (y < TOP + BUTTON && x >= RIGHT - 15) {
            show(Drawer.NONE);
            return;
        }
        if (drawer == Drawer.CHANNELS && writable && y >= ACTION_Y && y < ACTION_Y + BUTTON) {
            getContext().removeFocus();
            boolean share = inside(CHANNEL_SIDE + 2, ACTION_Y, 60, BUTTON);
            boolean apply = inside(CHANNEL_SIDE + 64, ACTION_Y, RIGHT - CHANNEL_SIDE - 66, BUTTON);
            if (share || apply && canApply()) {
                port.adopt(generation, new LinkedHashSet<>(replacementTargets), share);
                scopeEdited = false;
            }
        } else if (drawer == Drawer.MATERIALS) {
            if (y >= LIST && y < LIST + shownRows() * LINE) {
                List<BrowserRenderer.MaterialEntry> rows = materialRows();
                int n = scroll + (y - LIST) / LINE;
                if (n >= rows.size()) return;
                BrowserRenderer.MaterialEntry row = rows.get(n);
                int id = row.id;
                if (focus == id) {
                    model.clearFocus(); focus = -1; inspect(null);
                } else {
                    model.setFocus(id); focus = id;
                    inspect(model.at(row.x, row.y, row.z));
                }
            }
        }
    }

    @Override
    public void onMouseDrag(int button, long elapsed) {
        if (drag < 0) return;
        updateDrag(button, eventX(), eventY());
    }

    private void updateDrag(int button, int x, int y) {
        if (drag == 3) { slide(x); return; }
        int dx = x - lastX, dy = y - lastY;
        if (dx == 0 && dy == 0) return;
        travel += Math.abs(dx) + Math.abs(dy);
        if (travel >= 2) {
            if (dragMap) {
                if (button == 1) model.zoomSlice(-dy / 10D);
                else model.panSlice(dx, dy);
            } else if (button == 0) model.rotate(dx, dy);
            else if (button == 1) model.zoom(-dy / 10D);
            else if (button == 2) model.pan(dx, dy);
            hovered = null;
            lastHitX = Integer.MIN_VALUE;
        }
        lastX = x;
        lastY = y;
    }

    @Override
    public boolean onMouseRelease(int button) {
        if (drag < 0) return false;
        int x = eventX(), y = eventY();
        updateDrag(button, x, y);
        if (drag != 3 && travel < 2 && button != 2 && x >= X && x < viewportEnd() && y >= TOP && y < BOTTOM) {
            if (button == 1) {
                back();
            } else {
                int ax = x + getArea().x, ay = y + getArea().y;
                BrowserRenderer.Pick p = dual && x > splitX() ? model.pickSlice(ax, ay) : model.pick(ax, ay);
                if (p != null) inspect(p);
            }
        }
        drag = -1;
        return true;
    }

    @Override
    public boolean onMouseScroll(UpOrDown dir, int amount) {
        int step = dir.modifier;
        if (overDetails()) {
            detailScroll = cap((long) detailScroll - step, 0, Math.max(0, details.size() - DETAIL_ROWS));
            return true;
        }
        if (drawer != Drawer.NONE && inside(drawerLeft(), LIST, RIGHT - drawerLeft(), PANEL_END - LIST)) {
            getContext().removeFocus();
            int limit = drawer == Drawer.CHANNELS ? channels.size() - CHANNEL_ROWS : materialRows().size() - shownRows();
            scroll = cap((long) scroll - step, 0, Math.max(0, limit));
            return true;
        }
        if (inside(X, 191, viewportEnd() - X, BUTTON)) {
            Bounds b = model.bounds();
            if (b == null) return true;
            int min = component(b, axis, false), max = component(b, axis, true);
            if (!isolated) {
                if (step > 0) setLayer(min);
            } else if (!dual && layer == min && step < 0) {
                switchView(false);
            } else {
                int next = cap((long) layer + step, min, max);
                if (next != layer) setLayer(next);
            }
            return true;
        }
        if (inside(X, TOP, viewportEnd() - X, BOTTOM - TOP)) {
            if (dual && mx() > splitX()) model.zoomSlice(step);
            else model.zoom(step);
            lastHitX = Integer.MIN_VALUE;
            return true;
        }
        return false;
    }

    @Override
    public Result onKeyPressed(char c, int keyCode) {
        if (keyCode != Keyboard.KEY_ESCAPE) return Result.IGNORE;
        back();
        return Result.SUCCESS;
    }
}
