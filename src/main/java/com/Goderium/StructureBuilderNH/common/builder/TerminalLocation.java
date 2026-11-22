package com.Goderium.StructureBuilderNH.common.builder;

import com.cleanroommc.modularui.utils.GlStateManager;
import com.cleanroommc.modularui.utils.Platform;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.network.PacketBuffer;

import static com.cleanroommc.modularui.network.NetworkUtils.readStringSafe;
import static com.cleanroommc.modularui.network.NetworkUtils.writeStringSafe;

/** A server location and its client-side, font-measured representation. */
final class TerminalLocation {
    final int dimension, x, y, z;
    final String nameKey;

    TerminalLocation(int dimension, String nameKey, int x, int y, int z) {
        this.dimension = dimension;
        this.nameKey = nameKey;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    boolean matches(int dimension, String nameKey, int x, int y, int z) {
        return this.dimension == dimension && this.x == x && this.y == y && this.z == z && this.nameKey.equals(nameKey);
    }

    void write(PacketBuffer buffer) {
        buffer.writeInt(dimension);
        writeStringSafe(buffer, nameKey);
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
    }

    static TerminalLocation read(PacketBuffer buffer) {
        return new TerminalLocation(buffer.readInt(), readStringSafe(buffer), buffer.readInt(), buffer.readInt(), buffer.readInt());
    }

    static final class Display {
        final String number, name, x, y, z, facing, full;
        final int numberWidth, nameWidth, xWidth, yWidth, zWidth, facingWidth;
        private String shownName;

        Display(TerminalLocation value, String facing, FontRenderer font) {
            number = Integer.toString(value.dimension);
            name = TaskDimension.localizedName(value.nameKey).replace('\n', ' ').replace('\r', ' ');
            x = Integer.toString(value.x);
            y = Integer.toString(value.y);
            z = Integer.toString(value.z);
            this.facing = facing;
            full = "[" + number + "]" + name + " | " + x + ", " + y + ", " + z + (facing.isEmpty() ? "" : " | " + facing);
            numberWidth = font.getStringWidth(number);
            nameWidth = font.getStringWidth(name);
            xWidth = font.getStringWidth(x);
            yWidth = font.getStringWidth(y);
            zWidth = font.getStringWidth(z);
            facingWidth = font.getStringWidth(facing);
        }

        void fit(Columns columns) {
            if (nameWidth <= columns.nameWidth) {
                shownName = name;
                return;
            }
            int available = columns.nameWidth - columns.ellipsisWidth;
            shownName = available < 0 ? "" : columns.font.trimStringToWidth(name, available) + "...";
        }
    }

    static final class Columns {
        static final int LINE_HEIGHT = 11;
        final FontRenderer font;
        final int width, firstLineWidth, openWidth, closeWidth, gap, separatorWidth, commaWidth, digitWidth, ellipsisWidth;
        private int numberWidth, nameWidth, xWidth, yWidth, zWidth, facingWidth, coordinatesStart, coordinatesWidth;
        private float coordinateScale;
        boolean split;

        Columns(FontRenderer font, int width, int firstLineWidth) {
            this.font = font;
            this.width = width;
            this.firstLineWidth = firstLineWidth;
            openWidth = font.getStringWidth("[");
            closeWidth = font.getStringWidth("]");
            gap = Math.max(1, font.getStringWidth(" "));
            separatorWidth = font.getStringWidth("|");
            commaWidth = font.getStringWidth(",") + gap;
            ellipsisWidth = font.getStringWidth("...");
            int widest = font.getStringWidth("-");
            for (char digit = '0'; digit <= '9'; digit++) widest = Math.max(widest, font.getCharWidth(digit));
            digitWidth = widest;
            numberWidth = 4 * digitWidth;
        }

        void include(Display row) {
            numberWidth = Math.max(numberWidth, row.numberWidth);
            nameWidth = Math.max(nameWidth, row.nameWidth);
            xWidth = Math.max(xWidth, row.xWidth);
            yWidth = Math.max(yWidth, row.yWidth);
            zWidth = Math.max(zWidth, row.zWidth);
            facingWidth = Math.max(facingWidth, row.facingWidth);
        }

        void finish() {
            int nameStart = openWidth + numberWidth + closeWidth + gap;
            int separator = gap + separatorWidth + gap;
            coordinatesWidth = xWidth + commaWidth + yWidth + commaWidth + zWidth;
            if (facingWidth > 0) coordinatesWidth += separator + facingWidth;
            int available = width - nameStart - separator - coordinatesWidth;
            // Large world-border coordinates get their own line across the whole page.
            split = Math.max(xWidth, Math.max(yWidth, zWidth)) >= 8 * digitWidth
                    || available < Math.min(nameWidth, 3 * digitWidth + ellipsisWidth);
            nameWidth = Math.max(0, Math.min(nameWidth, split ? firstLineWidth - nameStart : available));
            coordinatesStart = split ? 0 : nameStart + nameWidth + separator;
            coordinateScale = coordinatesWidth > width ? width / (float) coordinatesWidth : 1;
        }

        void draw(Display row, int left, int top, int color) {
            Platform.setupDrawFont();
            font.drawString("[", left, top, color);
            int start = left + openWidth;
            font.drawString(row.number, start + (numberWidth - row.numberWidth) / 2, top, color);
            start += numberWidth;
            font.drawString("]", start, top, color);
            font.drawString(row.shownName, start + closeWidth + gap, top, color);
            if (!split) font.drawString("|", left + coordinatesStart - gap - separatorWidth, top, color);
            int coordX = left + coordinatesStart, coordY = split ? top + LINE_HEIGHT : top;
            if (coordinateScale < 1) {
                GlStateManager.pushMatrix();
                GlStateManager.translate(coordX, coordY, 0);
                GlStateManager.scale(coordinateScale, coordinateScale, 1);
                coordX = coordY = 0;
            }
            int right = coordX + xWidth;
            font.drawString(row.x, right - row.xWidth, coordY, color);
            font.drawString(",", right, coordY, color);
            right += commaWidth + yWidth;
            font.drawString(row.y, right - row.yWidth, coordY, color);
            font.drawString(",", right, coordY, color);
            right += commaWidth + zWidth;
            font.drawString(row.z, right - row.zWidth, coordY, color);
            if (!row.facing.isEmpty()) {
                font.drawString("|", right + gap, coordY, color);
                font.drawString(row.facing, right + gap + separatorWidth + gap, coordY, color);
            }
            if (coordinateScale < 1) GlStateManager.popMatrix();
        }
    }
}
