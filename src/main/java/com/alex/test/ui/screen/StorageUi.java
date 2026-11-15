package com.alex.test.ui.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Palette and widgets of the storage room screen, matching the Mayor's management screen. */
final class StorageUi {
    static final int OUTER = 0xFF080808;
    static final int PANEL_BORDER = 0xFF454545;
    static final int PANEL = 0xFF202020;
    static final int PANEL_DARK = 0xFF141414;
    static final int VIEW = 0xFF111111;
    static final int ROW = 0xFF292929;
    static final int ROW_HOVER = 0xFF383838;
    static final int ROW_SELECTED = 0xFF3C5641;
    static final int ROW_DISABLED = 0xFF222222;
    static final int BUTTON_HOVER_BORDER = 0xFF666666;
    static final int TEXT = 0xFFF3F3F3;
    static final int MUTED = 0xFFAAAAAA;
    static final int ACCENT = 0xFF6BAF72;
    static final int GOLD = 0xFFE6D49A;
    static final int DENIED = 0xFFE07A6E;

    private StorageUi() {}

    static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** A bordered row or card; selected ones get the green fill and accent bar. */
    static void row(GuiGraphics graphics, int x, int y, int width, int height, boolean selected, boolean hover) {
        graphics.fill(x, y, x + width, y + height, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1,
                selected ? ROW_SELECTED : hover ? ROW_HOVER : ROW);
        if (selected) graphics.fill(x + 1, y + height - 2, x + width - 1, y + height, ACCENT);
    }

    /** One half of a two-way switch, as the Mayor's info selector. */
    static void segment(GuiGraphics graphics, Font font, int x, int y, int width, int height, String label,
                        boolean selected, boolean hover) {
        row(graphics, x, y, width, height, selected, hover);
        graphics.drawString(font, label, x + (width - font.width(label)) / 2, y + (height - 8) / 2,
                selected ? TEXT : MUTED, false);
    }

    /** On/Off switch, as the Mayor's settings toggles. */
    static void toggle(GuiGraphics graphics, Font font, int x, int y, int width, int height, String label,
                       boolean on, boolean hover) {
        graphics.fill(x, y, x + width, y + height, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1,
                on ? hover ? 0xFF4E6F53 : 0xFF3B4E3E : hover ? ROW_HOVER : ROW_DISABLED);
        graphics.drawString(font, label, x + (width - font.width(label)) / 2, y + (height - 8) / 2,
                on ? TEXT : MUTED, false);
    }

    /** A plain bordered button with a centred label. */
    static void button(GuiGraphics graphics, Font font, int x, int y, int width, int height, String label,
                       boolean hover, boolean enabled) {
        graphics.fill(x, y, x + width, y + height, hover && enabled ? BUTTON_HOVER_BORDER : PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1,
                !enabled ? ROW_DISABLED : hover ? ROW_HOVER : ROW);
        graphics.drawString(font, label, x + (width - font.width(label) + 1) / 2, y + (height - 8) / 2,
                enabled ? TEXT : MUTED, false);
    }

    /** A 10 px tick box with its label 4 px to the right; ticked fills it green. */
    static void tickBox(GuiGraphics graphics, Font font, int x, int y, String label, boolean ticked, boolean hover) {
        graphics.fill(x, y, x + 10, y + 10, hover ? BUTTON_HOVER_BORDER : PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + 9, y + 9, PANEL_DARK);
        if (ticked) graphics.fill(x + 2, y + 2, x + 8, y + 8, ACCENT);
        graphics.drawString(font, label, x + 14, y + 1, TEXT, false);
    }

    static int tickBoxWidth(Font font, String label) {
        return 14 + font.width(label);
    }

    /**
     * The scrollbar the work-settings screens (farmer, lumberjack, ...) use: a 4 px grey
     * track, the thumb as tall as the share of the list on screen, at least 18 px.
     */
    static void scrollbar(GuiGraphics graphics, int x, int y, int height, int scroll, int maxScroll,
                          int visible) {
        graphics.fill(x, y, x + 4, y + height, 0xFF333333);
        int total = Math.max(1, visible + maxScroll);
        int thumb = Math.min(height, Math.max(18, height * visible / total));
        int thumbY = y + (height - thumb) * scroll / Math.max(1, maxScroll);
        graphics.fill(x, thumbY, x + 4, thumbY + thumb, 0xFF888888);
    }

    static String trim(Font font, String value, int width) {
        if (width <= 6) return "";
        return font.width(value) <= width ? value : font.plainSubstrByWidth(value, width - 6) + "...";
    }
}
