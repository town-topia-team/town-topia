package com.alex.test.ui.screen;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.alex.test.ui.screen.StorageUi.ACCENT;
import static com.alex.test.ui.screen.StorageUi.MUTED;
import static com.alex.test.ui.screen.StorageUi.PANEL_BORDER;
import static com.alex.test.ui.screen.StorageUi.ROW;
import static com.alex.test.ui.screen.StorageUi.TEXT;

/**
 * The Builder's plan's controls panel, on the left of the screen while the plan is in
 * hand: a title, one status line, and one row per control with its keys drawn as key caps
 * (mouse buttons as a small mouse), in the desk's colours. Only the main controls show;
 * the help key adds the rest. The panel shrinks to fit at any GUI scale.
 */
final class PlanHud {
    private static final int PAD = 5, ROW_H = 15, CAP_H = 13, CAP_GAP = 2, NOTE_W = 150, MOUSE_W = 10;
    private static final int BACK = 0xC0141414, CAP_FILL = ROW, CAP_EDGE = 0xFF666666;
    /** Most of the screen the panel may take before it shrinks. */
    private static final float MAX_W = 0.34F, MAX_H = 0.8F;

    /**
     * One control: its keys and what it does. {@code together}: pressed together (joined
     * with "+"); otherwise any one of them (side by side).
     */
    record Row(List<String> keys, String label, int color, boolean together) {}

    /** What the panel shows this frame. */
    static final class Content {
        final String title;
        final List<Component> status = new ArrayList<>();
        final List<Row> rows = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        Content(String title) {
            this.title = title;
        }

        Content status(Component line) {
            status.add(line);
            return this;
        }

        /** Keys pressed together. */
        Content row(String label, String... keys) {
            rows.add(new Row(List.of(keys), label, TEXT, true));
            return this;
        }

        Content row(int color, String label, String... keys) {
            rows.add(new Row(List.of(keys), label, color, true));
            return this;
        }

        /** Any one of these keys. */
        Content anyOf(String label, String... keys) {
            rows.add(new Row(List.of(keys), label, TEXT, false));
            return this;
        }

        Content note(String text) {
            notes.add(text);
            return this;
        }
    }

    // Mouse buttons and the wheel, drawn as a small mouse with that part lit.
    static final String RIGHT = "@right", LEFT = "@left", SCROLL = "@wheel";

    private PlanHud() {}

    /** The sneak key's short name, as the player bound it. */
    static String sneak() {
        return cap(Minecraft.getInstance().options.keyShift);
    }

    /** A key's short name for a cap: "R", "PgUp", "Shift", an arrow. */
    static String cap(KeyMapping mapping) {
        String name = mapping.getTranslatedKeyMessage().getString();
        return switch (name) {
            case "Page Up" -> "PgUp";
            case "Page Down" -> "PgDn";
            case "Left Shift" -> "Shift";
            case "Right Shift" -> "R Shift";
            case "Left Control" -> "Ctrl";
            case "Left Alt" -> "Alt";
            case "Up Arrow", "Up" -> "↑";
            case "Down Arrow", "Down" -> "↓";
            case "Left Arrow", "Left" -> "←";
            case "Right Arrow", "Right" -> "→";
            case "Right Button" -> RIGHT;
            case "Left Button" -> LEFT;
            default -> name;
        };
    }

    static void render(GuiGraphics graphics, @Nullable Content content) {
        if (content == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        String title = StorageUi.trim(font, content.title, NOTE_W);
        int capsW = 0, labelW = font.width(title);
        for (Row row : content.rows) {
            capsW = Math.max(capsW, capsWidth(font, row));
            labelW = Math.max(labelW, font.width(row.label()));
        }
        int width = Math.max(capsW + 6 + labelW, Math.min(NOTE_W, font.width(title))) + 2 * PAD;
        for (Component line : content.status) width = Math.max(width, Math.min(NOTE_W, font.width(line)) + 2 * PAD);
        List<FormattedCharSequence> status = new ArrayList<>();
        for (Component line : content.status) status.addAll(font.split(line, width - 2 * PAD));
        List<FormattedCharSequence> notes = new ArrayList<>();
        for (String note : content.notes) notes.addAll(font.split(Component.literal(note), width - 2 * PAD));
        int height = PAD + 11 + status.size() * 10 + 3 + content.rows.size() * ROW_H
                + (notes.isEmpty() ? 0 : 3 + notes.size() * 10) + PAD - 2;

        // Shrinks (never grows) so it fits beside the crosshair at any GUI scale and window size.
        float scale = Math.min(1F, Math.min(graphics.guiWidth() * MAX_W / width, graphics.guiHeight() * MAX_H / height));
        int x = 4;
        float y = Math.max(4, (graphics.guiHeight() - height * scale) / 2);
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);

        graphics.fill(0, 0, width, height, BACK);
        graphics.fill(0, 0, width, 1, PANEL_BORDER);
        graphics.fill(0, height - 1, width, height, PANEL_BORDER);
        graphics.fill(0, 0, 1, height, PANEL_BORDER);
        graphics.fill(width - 1, 0, width, height, PANEL_BORDER);
        graphics.fill(1, 1, width - 1, 2, ACCENT);

        int ty = PAD;
        graphics.drawString(font, title, PAD, ty, ACCENT, false);
        ty += 11;
        for (FormattedCharSequence line : status) {
            graphics.drawString(font, line, PAD, ty, MUTED, false);
            ty += 10;
        }
        ty += 3;
        for (Row row : content.rows) {
            int cx = PAD;
            for (int i = 0; i < row.keys().size(); i++) {
                if (i > 0 && row.together()) {
                    graphics.drawString(font, "+", cx + 1, ty + 3, MUTED, false);
                    cx += font.width("+") + 3;
                }
                cx = keyCap(graphics, font, cx, ty, row.keys().get(i)) + CAP_GAP;
            }
            graphics.drawString(font, row.label(), PAD + capsW + 6, ty + 3, row.color(), false);
            ty += ROW_H;
        }
        if (!notes.isEmpty()) {
            ty += 3;
            for (FormattedCharSequence note : notes) {
                graphics.drawString(font, note, PAD, ty, MUTED, false);
                ty += 10;
            }
        }
        graphics.pose().popPose();
    }

    private static int capsWidth(Font font, Row row) {
        int w = 0;
        for (int i = 0; i < row.keys().size(); i++) {
            if (i > 0 && row.together()) w += font.width("+") + 3;
            w += capWidth(font, row.keys().get(i)) + CAP_GAP;
        }
        return w;
    }

    private static int capWidth(Font font, String key) {
        if (key.startsWith("@")) return MOUSE_W;
        return Math.max(CAP_H, font.width(key) + 6);
    }

    /** A key cap with the key's name, or a mouse; returns its right edge. */
    private static int keyCap(GuiGraphics graphics, Font font, int x, int y, String key) {
        int w = capWidth(font, key);
        if (key.startsWith("@")) {
            mouse(graphics, x, y, key);
            return x + w;
        }
        graphics.fill(x, y, x + w, y + CAP_H, CAP_EDGE);
        graphics.fill(x + 1, y + 1, x + w - 1, y + CAP_H - 2, CAP_FILL);
        graphics.fill(x + 1, y + CAP_H - 2, x + w - 1, y + CAP_H - 1, PANEL_BORDER);
        graphics.drawString(font, key, x + (w - font.width(key)) / 2, y + 2, TEXT, false);
        return x + w;
    }

    /** A small mouse, 10 by 13: two buttons on top, the wheel between them; the used part lit. */
    private static void mouse(GuiGraphics graphics, int x, int y, String part) {
        int w = MOUSE_W, split = y + 5, mid = x + w / 2;
        graphics.fill(x + 1, y, x + w - 1, y + CAP_H, CAP_EDGE);
        graphics.fill(x, y + 1, x + w, y + CAP_H - 1, CAP_EDGE);
        graphics.fill(x + 1, y + 1, x + w - 1, y + CAP_H - 1, CAP_FILL);
        graphics.fill(x + 1, split, x + w - 1, split + 1, CAP_EDGE);
        graphics.fill(mid - 1, y + 1, mid, split, CAP_EDGE);
        switch (part) {
            case LEFT -> graphics.fill(x + 1, y + 1, mid - 1, split, ACCENT);
            case RIGHT -> graphics.fill(mid, y + 1, x + w - 1, split, ACCENT);
            default -> graphics.fill(mid - 1, y + 1, mid + 1, split + 1, ACCENT);
        }
    }
}
