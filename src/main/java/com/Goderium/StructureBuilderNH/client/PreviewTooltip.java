package com.Goderium.StructureBuilderNH.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class PreviewTooltip {
    private final List<String> lines = new ArrayList<>();
    private String title, hint;
    private int widthLimit, panelWidth, titleLines, modifier;
    private boolean hatch, expanded;

    static String modifierName(Minecraft mc) {
        int key = mc.gameSettings.keyBindSneak.getKeyCode();
        return key == Keyboard.KEY_LSHIFT || key == Keyboard.KEY_RSHIFT ? "Shift" : GameSettings.getKeyDisplayString(key);
    }

    void clear() {
        title = null;
    }

    void draw(Minecraft mc, ScaledResolution screen, String title, String hint, int color, boolean hatch) {
        FontRenderer font = mc.fontRendererObj;
        int width = Math.max(1, Math.min(256, screen.getScaledWidth() - 14));
        int key = mc.gameSettings.keyBindSneak.getKeyCode();
        boolean expand = hatch && (key == 0 || BuilderRenderer.previewModifierDown());
        if (width != widthLimit || !title.equals(this.title) || !Objects.equals(hint, this.hint)
                || hatch != this.hatch || expand != expanded || key != modifier) {
            widthLimit = width;
            this.title = title;
            this.hint = hint;
            this.hatch = hatch;
            expanded = expand;
            modifier = key;
            lines.clear();
            lines.addAll(font.listFormattedStringToWidth(title, width));
            titleLines = lines.size();
            if (hint != null && !hint.isEmpty()) {
                if (hatch && font.getStringWidth(hint) > width && hint.contains(HatchLabels.SEPARATOR))
                    addHatchLines(font, modifierName(mc));
                else lines.addAll(font.listFormattedStringToWidth(hint, width));
            }
            panelWidth = 0;
            for (String line : lines) panelWidth = Math.max(panelWidth, font.getStringWidth(line));
        }
        int center = screen.getScaledWidth() / 2;
        int y = screen.getScaledHeight() / 2 + 12;
        int step = font.FONT_HEIGHT + 1;
        Gui.drawRect(center - (panelWidth + 1) / 2 - 4, y - 3, center + (panelWidth + 1) / 2 + 4,
                y + step * lines.size() + 1, 0x99131c20);
        // Lighten the role color so the name stays legible against the dark panel.
        int titleColor = 0x808080 + ((color & 0xfefefe) >> 1);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            font.drawStringWithShadow(line, center - font.getStringWidth(line) / 2, y,
                    i < titleLines ? titleColor : 0xe0e8ea);
            y += step;
        }
    }

    private void addHatchLines(FontRenderer font, String keyName) {
        String more = "…  " + StatCollector.translateToLocalFormatted("structurebuildernh.previewMoreHatches", keyName);
        int width = expanded ? widthLimit : widthLimit - font.getStringWidth("  " + more);
        String row = "";
        for (String name : hint.split(" \\| ")) {
            String next = row.isEmpty() ? name : row + HatchLabels.SEPARATOR + name;
            if (font.getStringWidth(next) <= width) {
                row = next;
                continue;
            }
            if (!expanded) {
                if (row.isEmpty()) {
                    lines.addAll(font.listFormattedStringToWidth(name, widthLimit));
                    lines.add(more);
                } else lines.add(row + "  " + more);
                return;
            }
            if (!row.isEmpty()) lines.addAll(font.listFormattedStringToWidth(row, widthLimit));
            row = name;
        }
        if (!row.isEmpty()) lines.addAll(font.listFormattedStringToWidth(row, widthLimit));
    }
}
