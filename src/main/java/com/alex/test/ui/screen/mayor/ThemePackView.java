package com.alex.test.ui.screen.mayor;

import com.alex.test.ClientConfig;
import com.alex.test.Tr;
import com.alex.test.client.VillagerTheme;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * One skin set's own page, opened by clicking its card on the Theme tab. Like the Wiki view it has the whole Mayor
 * window with the tabs hidden ({@link #pageW}, {@link #pageLeft}). Top line: back to the Theme
 * tab, the set's name, how far it is painted, and Use. Left: every job and skin ({@link VillagerPreview#ENTRIES}) as a
 * grid of small villagers in this set, the ones it has not painted greyed (they keep the Classic look); the wheel
 * scrolls it a row at a time. Right: the clicked one big, turned by pressing on it and dragging, and the arrows.
 *
 * <p>The player's own changes ({@link ClientConfig#setSkinChange}, kept per set in use): under the big villager one
 * button leaves its skin out of the set in use or puts it back (on that set's page), or wears this set's picture in
 * the set in use or stops (on another set's page). Each tile's corner says what villagers in the world wear: a tick
 * for this very picture, a cross for one left out, an arrow for one brought in. The page of the set in use has
 * "Undo changes" in its top line, which asks once.
 *
 * <p>"Your look" ({@link #yourLook}) is the same page for the set in use, but every villager wears the picture seen
 * in the world (the set in use with the player's changes): no greying, no Use, a gold arrow on each skin changed, and
 * the top line says which set is in use and how many changes.
 *
 * <p>Drawing and clicks work out every spot from the same few methods and the same scroll, so they always agree.
 */
final class ThemePackView extends MayorPart {

    private static final int HEADER_Y = 3;
    private static final int BACK_Y = 4;
    private static final int BACK_H = 16;
    private static final int BODY_TOP = 24;
    private static final int PAD = 6;
    private static final int STAGE_W = 160;
    private static final int SCROLLBAR_W = 3;
    /** A small villager's tile: at least this wide (its name under it), the villager's box, then the name. */
    private static final int MIN_TILE_W = 52;
    private static final int TILE_H = 78;
    private static final int TILE_BOX_H = 62;
    /** Over a skin the set has not painted: the villager shows through, darker. */
    private static final int GREYED = 0xA0141414;
    private static final int BAR_W = 80;
    private static final int BAR_H = 4;
    /** The stage's villager: from its top to above the arrows, the skin line and the skin's button. */
    private static final int STAGE_BOX_TOP = 4;
    private static final int STAGE_BOX_BOTTOM_GAP = 55;
    /** The "<" and ">" buttons, this far above the stage's bottom; the skin line and the skin's button under them. */
    private static final int ARROWS_UP = 51;
    private static final int ARROW_W = 16;
    private static final int ARROW_H = 14;
    private static final int SKIN_LINE_UP = 33;
    private static final int SKIN_BUTTON_UP = 22;
    /** After a first click on Undo, a second one this soon undoes the changes. */
    private static final long UNDO_ASK_MS = 3000L;
    /** A tile's corner mark: a dark square with the sign in the middle, drawn pixel by pixel ('#' is lit). */
    private static final int MARK_W = 11;
    private static final int MARK_H = 10;
    private static final String[] TICK = {
            "......#",
            ".....##",
            "#...##.",
            "##.##..",
            ".###...",
            "..#...."};
    private static final String[] CROSS = {
            "#....#",
            "##..##",
            ".####.",
            ".####.",
            "##..##",
            "#....#"};
    private static final String[] ARROW = {
            "....#..",
            "....##.",
            "#######",
            "....##.",
            "....#.."};
    /** Degrees the big villager turns, or tips, for each pixel the mouse is dragged. */
    private static final float DEGREES_PER_PIXEL = 1.5F;
    /** How far it tips up or down: enough to see a hat's top or the soles, never upside down. */
    private static final float MAX_TILT = 30F;
    /** Two presses on the big villager this close together are a double click (vanilla's lists use the same). */
    private static final long DOUBLE_CLICK_MS = 250L;
    private static final long NO_PRESS = Long.MIN_VALUE;

    /** What a tile's corner says about the villagers in the world who have its skin. */
    private enum Mark {
        /** Nothing: they wear another set's picture. */
        NONE,
        /** They wear this very picture: a green tick. */
        WORN,
        /** On the set in use's page: the player gave this skin another set's picture. A red cross. */
        LEFT_OUT,
        /** On another set's page: the player brought this picture into the set in use. A gold arrow. */
        BROUGHT_IN
    }

    /** What the button under the big villager does to the skin shown. */
    private enum Action { LEAVE_OUT, PUT_BACK, WEAR, STOP_WEARING }

    private final VillagerPreview preview = new VillagerPreview();
    /** The set whose page is open (on "Your look": the set in use, kept up to date by {@link #follow}); null while closed. */
    @Nullable private VillagerTheme set;
    /** The "Your look" page: every skin as villagers in the world wear it. */
    private boolean yourLook;
    /** The job and skin on the stage, an index in {@link VillagerPreview#ENTRIES}; kept for the next set opened. */
    private int shown;
    /** The grid's first row in sight. */
    private int scrollRow;
    /** The big villager's turn and tip in degrees; kept while the window is open, also for another set or skin. */
    private float turn = VillagerPreview.SIDE_TURN;
    private float tilt;
    /** Pressed on the big villager and not let go yet: mouse moves turn it. */
    private boolean turning;
    /** Set at the first turn in this window: the hover on how to turn it stops showing. */
    private boolean turnedOnce;
    /** When the big villager was last pressed ({@link Util#getMillis}), for the double click. */
    private long lastPressMs = NO_PRESS;
    /** When Undo was first clicked; a second click before {@link #UNDO_ASK_MS} undoes. Any other click forgets it. */
    private long undoAskedMs = NO_PRESS;

    ThemePackView(MayorStatsScreen screen) {
        super(screen);
    }

    boolean isOpen() {
        return set != null;
    }

    /**
     * Opens {@code set}'s page (null: "Your look"), on the entry {@code entry} when it is one (a small villager clicked
     * on its card).
     */
    void open(@Nullable VillagerTheme set, int entry) {
        yourLook = set == null;
        this.set = set != null ? set : VillagerTheme.current();
        if (entry >= 0 && entry < VillagerPreview.ENTRIES.size()) shown = entry;
        turning = false;
        undoAskedMs = NO_PRESS;
        scrollRow = 0;
        scrollTo(shown);
    }

    void close() {
        set = null;
        yourLook = false;
        turning = false;
        undoAskedMs = NO_PRESS;
    }

    /** "Your look" shows the set in use, which the config file can change while the page is open. */
    private void follow() {
        if (yourLook && set != null) set = VillagerTheme.current();
    }

    /** Every client tick while the page is open: the villagers' idle clip moves on. */
    void tick() {
        preview.tick();
    }

    // ---------------------------------------------------------------------
    // Where things are
    // ---------------------------------------------------------------------

    /** The page is the Mayor window itself: as wide, in the same place. */
    int pageW() {
        return windowW();
    }

    int pageLeft() {
        return left();
    }

    private int bodyBottom() {
        return top() + screen.frameHeight() - CONTENT_X;
    }

    private int stageX() {
        return pageLeft() + pageW() - CONTENT_X - PAD - STAGE_W;
    }

    private int gridX() {
        return pageLeft() + CONTENT_X + PAD;
    }

    private int gridTop() {
        return top() + BODY_TOP + PAD;
    }

    /** The tiles' width all together: up to the scrollbar, which sits in the gap before the stage. */
    private int gridW() {
        return stageX() - PAD - SCROLLBAR_W - 2 - gridX();
    }

    private int columns() {
        return Math.max(1, gridW() / MIN_TILE_W);
    }

    private int tileW() {
        return gridW() / columns();
    }

    /** Whole rows only: three on the full window, two on the shortest. */
    private int visibleRows() {
        return Math.max(1, (bodyBottom() - PAD - gridTop()) / TILE_H);
    }

    private int maxScroll() {
        int rows = (VillagerPreview.ENTRIES.size() + columns() - 1) / columns();
        return Math.max(0, rows - visibleRows());
    }

    private int tileX(int index) {
        return gridX() + (index % columns()) * tileW();
    }

    private int tileY(int index) {
        return gridTop() + (index / columns() - scrollRow) * TILE_H;
    }

    /** The entries in sight: from the first row shown, as many whole rows as fit. */
    private int firstShown() {
        return scrollRow * columns();
    }

    private int endShown() {
        return Math.min(VillagerPreview.ENTRIES.size(), (scrollRow + visibleRows()) * columns());
    }

    /** The tile under the mouse, or -1. */
    private int tileAt(double mouseX, double mouseY) {
        for (int i = firstShown(); i < endShown(); i++) {
            if (inside(mouseX, mouseY, tileX(i), tileY(i), tileW() - 2, TILE_H - 2)) return i;
        }
        return -1;
    }

    /** Scrolls the least needed to bring that entry's row into sight. */
    private void scrollTo(int index) {
        int row = index / columns();
        if (row < scrollRow) scrollRow = row;
        if (row >= scrollRow + visibleRows()) scrollRow = row - visibleRows() + 1;
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
    }

    private String backLabel() {
        return Tr.s("screen.dynamicvillages.mayor.theme.back", "< Theme");
    }

    private int backW() {
        return font.width(backLabel()) + 4;
    }

    private int useW() {
        return ThemeSets.buttonW(set, 120);
    }

    private int pageRight() {
        return pageLeft() + pageW() - CONTENT_X;
    }

    private int useX() {
        return pageRight() - useW();
    }

    /** The bar's right end, 8 left of Use; "12 of 42 skins" ends there too. */
    private int barRight() {
        return useX() - 8;
    }

    /**
     * Where "12 of 42 skins" or its bar starts, whichever is further left (on "Your look": its two lines, which end
     * at the right edge instead of Use). Undo and the name stay left of it.
     */
    private int infoLeft() {
        if (yourLook) {
            return pageRight() - Math.max(font.width(ThemeSets.lookSetLine(set)), font.width(ThemeSets.lookChangesLine(set)));
        }
        return Math.min(barRight() - BAR_W, barRight() - font.width(ThemeSets.paintedLine(set)));
    }

    /** Undo shows on the page of the set in use while the player has changes to it. */
    private boolean undoShown() {
        return set == VillagerTheme.current() && VillagerTheme.changeCount(set) > 0;
    }

    private boolean undoAsking() {
        return undoAskedMs != NO_PRESS && Util.getMillis() - undoAskedMs < UNDO_ASK_MS;
    }

    private static String undoText() {
        return Tr.s("screen.dynamicvillages.mayor.theme.undo", "Undo changes");
    }

    private static String undoSureText() {
        return Tr.s("screen.dynamicvillages.mayor.theme.undo_sure", "Click again");
    }

    /** As wide as the longer of its two words, so it keeps its size when it asks. */
    private int undoW() {
        return Math.max(font.width(undoText()), font.width(undoSureText())) + 12;
    }

    private int undoX() {
        return infoLeft() - 8 - undoW();
    }

    /** The skin's button under the big villager: across the stage, above its bottom. */
    private int skinButtonY() {
        return bodyBottom() - PAD - SKIN_BUTTON_UP;
    }

    /** Whether the mouse is on the big villager's box: the stage above its arrows. */
    private boolean onVillager(double mouseX, double mouseY) {
        int boxTop = gridTop() + STAGE_BOX_TOP;
        int boxBottom = bodyBottom() - PAD - STAGE_BOX_BOTTOM_GAP;
        return inside(mouseX, mouseY, stageX() + 1, boxTop, STAGE_W - 2, boxBottom - boxTop);
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    /** The whole page, frame included; hover text goes through the screen, drawn after. */
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (set == null) return;
        follow();
        int x = pageLeft();
        int y = top();
        int w = pageW();
        int h = screen.frameHeight();
        graphics.fill(x - 2, y - 2, x + w + 2, y + h + 2, OUTER);
        graphics.fill(x, y, x + w, y + h, PANEL);
        graphics.fill(x + CONTENT_X, y + BODY_TOP, x + w - CONTENT_X, bodyBottom(), PANEL_DARK);
        drawHeader(graphics, mouseX, mouseY);
        drawGrid(graphics, mouseX, mouseY);
        drawStage(graphics, mouseX, mouseY);
        // No hover text pops up over the villager while it is being turned.
        if (turning) screen.setDeferredTooltip(null);
    }

    /**
     * Back, the set's icon and name, Undo (the set in use, with changes), "12 of 42 skins" over its bar, and
     * Use / In use at the right end. On "Your look": the set in use and how many changes at the right end instead.
     */
    private void drawHeader(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = pageLeft() + CONTENT_X;
        int y = top();
        boolean backHovered = inside(mouseX, mouseY, x, y + BACK_Y, backW(), BACK_H);
        graphics.drawString(font, backLabel(), x + 2, y + 8, backHovered ? TEXT : MUTED, false);

        int infoRight;
        if (yourLook) {
            infoRight = pageRight();
            String using = ThemeSets.lookSetLine(set);
            String changes = ThemeSets.lookChangesLine(set);
            graphics.drawString(font, using, infoRight - font.width(using), y + 4, MUTED, false);
            graphics.drawString(font, changes, infoRight - font.width(changes), y + 14,
                    VillagerTheme.changeCount(set) > 0 ? GOLD : DIM, false);
        } else {
            int useX = useX();
            ThemeSets.drawButton(graphics, set, useX, y + HEADER_Y, useW(), mouseX, mouseY);
            if (inside(mouseX, mouseY, useX, y + HEADER_Y, useW(), ThemeSets.BUTTON_H)) {
                screen.setDeferredTooltip(tooltipLines(ThemeSets.buttonTip(set)));
            }

            infoRight = barRight();
            int barX = infoRight - BAR_W;
            String painted = ThemeSets.paintedLine(set);
            graphics.drawString(font, painted, infoRight - font.width(painted), y + 4, MUTED, false);
            int count = set.paintedCount();
            int total = VillagerTheme.skinCount();
            graphics.fill(barX, y + 15, infoRight, y + 15 + BAR_H, 0xFF333333);
            if (total > 0) graphics.fill(barX, y + 15, barX + BAR_W * Math.min(count, total) / total, y + 15 + BAR_H, ACCENT);
        }

        // The name runs up to Undo when it shows, else up to the painted count.
        int nameEnd = infoLeft();
        if (undoShown()) {
            int undoX = undoX();
            boolean asking = undoAsking();
            ThemeSets.drawPlainButton(graphics, asking ? undoSureText() : undoText(), undoX, y + HEADER_Y, undoW(), true,
                    asking ? DENIED : PANEL_BORDER, mouseX, mouseY);
            if (inside(mouseX, mouseY, undoX, y + HEADER_Y, undoW(), ThemeSets.BUTTON_H)) {
                screen.setDeferredTooltip(tooltipLines(linesOf(asking
                        ? Tr.s("screen.dynamicvillages.mayor.theme.undo_sure_tip",
                                "Click again to undo\nyour changes to %s.", ThemeSets.name(set))
                        : Tr.s("screen.dynamicvillages.mayor.theme.undo_tip",
                                "Every %s skin wears\nits own picture again.\nAsks once before it undoes.", ThemeSets.name(set)))));
            }
            nameEnd = undoX;
        }

        int iconX = x + backW() + 8;
        graphics.renderFakeItem(new ItemStack(yourLook ? ThemeSets.LOOK_ICON : ThemeSets.icon(set)), iconX, y + HEADER_Y);
        int nameX = iconX + 20;
        String name = yourLook ? ThemeSets.lookName() : ThemeSets.name(set, nameEnd - 8 - nameX);
        // A set with an artist: the name moves up and the credit goes under it, like the count over its bar.
        String credit = yourLook ? null : ThemeSets.creditLine(set, nameEnd - 8 - nameX);
        graphics.drawString(font, trim(name, nameEnd - 8 - nameX), nameX, y + (credit == null ? 8 : 4), TEXT, false);
        if (credit != null) graphics.drawString(font, trim(credit, nameEnd - 8 - nameX), nameX, y + 14, GOLD, false);
        boolean onName = inside(mouseX, mouseY, iconX, y + HEADER_Y, nameEnd - 4 - iconX, ThemeSets.BUTTON_H);
        boolean onCount = inside(mouseX, mouseY, infoLeft(), y + HEADER_Y, infoRight - infoLeft(), ThemeSets.BUTTON_H);
        if (onName || onCount) {
            screen.setDeferredTooltip(tooltipLines(yourLook ? ThemeSets.lookAbout(set) : ThemeSets.about(set)));
        }
    }

    private void drawGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
        int hovered = tileAt(mouseX, mouseY);
        for (int i = firstShown(); i < endShown(); i++) drawTile(graphics, i, i == hovered);

        int max = maxScroll();
        if (max > 0) {
            int trackX = gridX() + columns() * tileW();
            int trackY = gridTop();
            int trackH = visibleRows() * TILE_H - 2;
            int rows = max + visibleRows();
            int thumbH = Math.max(12, trackH * visibleRows() / rows);
            int thumbY = trackY + (trackH - thumbH) * scrollRow / max;
            graphics.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, 0xFF333333);
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xFF888888);
        }

        if (hovered >= 0) {
            VillagerPreview.Entry entry = VillagerPreview.ENTRIES.get(hovered);
            Component[] lines = {Component.literal(entry.label())};
            if (yourLook) {
                lines = join(lines, linesOf(lookText(entry)));
            } else {
                if (set != VillagerTheme.CLASSIC) lines = join(lines, linesOf(paintedText(entry)));
                String mark = markText(entry, mark(entry));
                if (mark != null) lines = join(lines, linesOf(mark));
            }
            screen.setDeferredTooltip(tooltipLines(lines));
        }
    }

    /**
     * A small villager in this set (its Classic look, greyed, when the set has not painted it; on "Your look" as worn
     * in the world, never greyed), its corner mark and its name.
     */
    private void drawTile(GuiGraphics graphics, int index, boolean hovered) {
        VillagerPreview.Entry entry = VillagerPreview.ENTRIES.get(index);
        int x = tileX(index);
        int y = tileY(index);
        int w = tileW() - 2;
        int h = TILE_H - 2;
        boolean selected = index == shown;
        boolean painted = yourLook || preview.paintedIn(entry, set);
        graphics.fill(x, y, x + w, y + h, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, selected ? ROW_SELECTED : hovered ? ROW_HOVER : ROW);
        if (selected) graphics.fill(x + 1, y + h - 2, x + w - 1, y + h, ACCENT);
        int boxBottom = y + TILE_BOX_H;
        if (!preview.drawTurned(graphics, entry, drawnIn(entry), x + 1, y + 1, x + w - 1, boxBottom,
                VillagerPreview.SIDE_TURN, 0F)) {
            graphics.renderFakeItem(new ItemStack(Items.VILLAGER_SPAWN_EGG), x + w / 2 - 8, (y + boxBottom) / 2 - 8);
        }
        if (!painted) {
            // Over the villager, which is drawn nearer than the window (see VillagerPreview.renderTurned).
            graphics.pose().pushPose();
            graphics.pose().translate(0F, 0F, 300F);
            graphics.fill(x + 1, y + 1, x + w - 1, boxBottom, GREYED);
            graphics.pose().popPose();
        }
        drawMark(graphics, mark(entry), x + w - 2, y + 2);
        String label = tileLabel(entry, w - 4);
        graphics.drawString(font, label, x + (w - font.width(label)) / 2, boxBottom + 3,
                selected ? TEXT : painted ? MUTED : DIM, false);
    }

    /** "Guard 2"; a job name too long for the tile is cut ("Lumb... 2"), never its number. Full name on hover. */
    private String tileLabel(VillagerPreview.Entry entry, int width) {
        String label = entry.shortLabel();
        if (font.width(label) <= width || entry.variants() <= 1) return trim(label, width);
        String number = " " + (entry.variant() + 1);
        return trim(entry.job().shownName(), width - font.width(number)) + number;
    }

    /**
     * The big villager of the clicked tile (drag to turn), the arrows, its name, whether this set painted it, and the
     * button that leaves its skin out, brings it in, or undoes that.
     */
    private void drawStage(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = stageX();
        int y = gridTop();
        int bottom = bodyBottom() - PAD;
        drawListFrame(graphics, x, y, STAGE_W, bottom - y);
        int mid = x + STAGE_W / 2;

        VillagerPreview.Entry entry = VillagerPreview.ENTRIES.get(shown);
        int boxTop = y + STAGE_BOX_TOP;
        int boxBottom = bottom - STAGE_BOX_BOTTOM_GAP;
        if (!preview.drawTurned(graphics, entry, drawnIn(entry), x + 1, boxTop, x + STAGE_W - 1, boxBottom, turn, tilt)) {
            graphics.renderFakeItem(new ItemStack(Items.VILLAGER_SPAWN_EGG), mid - 8, (boxTop + boxBottom) / 2 - 8);
        } else if (!turnedOnce && onVillager(mouseX, mouseY)) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.theme.turn_tip",
                    "Press on it and drag to turn it round.\nDouble click to turn it back."))));
        }

        int rowY = bottom - ARROWS_UP;
        drawArrow(graphics, x + 6, rowY, "<", mouseX, mouseY);
        drawArrow(graphics, x + STAGE_W - 6 - ARROW_W, rowY, ">", mouseX, mouseY);
        int labelW = STAGE_W - 12 - 2 * (ARROW_W + 4);
        drawCentred(graphics, trim(entry.label(), labelW), mid, rowY + 3, TEXT);
        if (inside(mouseX, mouseY, mid - labelW / 2, rowY, labelW, ARROW_H)) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.theme.arrows_tip",
                    "The arrows show every job and skin.\nThe skin count also has the Mayor,\nmerchants, nomads and muddy farmers."))));
        }
        int lineY = bottom - SKIN_LINE_UP;
        if (yourLook) {
            VillagerTheme changed = VillagerPreview.changeOf(entry);
            VillagerTheme worn = preview.worn(entry);
            // "Your change: Victorian English" is wider than the stage: the short name keeps it whole.
            String line = changed != null
                    ? Tr.s("screen.dynamicvillages.mayor.theme.look_changed", "Your change: %s", ThemeSets.name(worn, 0))
                    : Tr.s("screen.dynamicvillages.mayor.theme.look_picture", "%s picture", ThemeSets.name(worn));
            drawCentred(graphics, trim(line, STAGE_W - 12), mid, lineY, changed != null ? GOLD : worn == set ? ACCENT : DIM);
            if (inside(mouseX, mouseY, x + 6, lineY - 1, STAGE_W - 12, 10)) {
                screen.setDeferredTooltip(tooltipLines(linesOf(lookText(entry))));
            }
        } else if (set != VillagerTheme.CLASSIC) {
            boolean own = preview.paintedIn(entry, set);
            drawCentred(graphics, trim(paintedText(entry), STAGE_W - 12), mid, lineY, own ? ACCENT : DIM);
        }

        Action action = action(entry);
        int buttonY = skinButtonY();
        boolean changed = action == Action.PUT_BACK || action == Action.STOP_WEARING;
        ThemeSets.drawPlainButton(graphics, actionLabel(action), x + 6, buttonY, STAGE_W - 12, blocked(entry, action) == null,
                changed ? GOLD : PANEL_BORDER, mouseX, mouseY);
        if (inside(mouseX, mouseY, x + 6, buttonY, STAGE_W - 12, ThemeSets.BUTTON_H)) {
            screen.setDeferredTooltip(tooltipLines(actionTip(entry, action)));
        }
    }

    /** The tick, cross or arrow in a tile's top right corner, nearer than the villager and the grey over it. */
    private static void drawMark(GuiGraphics graphics, Mark mark, int right, int top) {
        String[] sign;
        int color;
        switch (mark) {
            case WORN -> {
                sign = TICK;
                color = ACCENT;
            }
            case LEFT_OUT -> {
                sign = CROSS;
                color = DENIED;
            }
            case BROUGHT_IN -> {
                sign = ARROW;
                color = GOLD;
            }
            default -> {
                return;
            }
        }
        int x = right - MARK_W;
        graphics.pose().pushPose();
        graphics.pose().translate(0F, 0F, 310F);
        graphics.fill(x, top, x + MARK_W, top + MARK_H, PANEL_BORDER);
        graphics.fill(x + 1, top + 1, x + MARK_W - 1, top + MARK_H - 1, PANEL_DARK);
        int signX = x + (MARK_W - sign[0].length()) / 2;
        int signY = top + (MARK_H - sign.length) / 2;
        for (int row = 0; row < sign.length; row++) {
            String line = sign[row];
            // One fill per run of lit pixels.
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean lit = col < line.length() && line.charAt(col) == '#';
                if (lit && start < 0) start = col;
                if (!lit && start >= 0) {
                    graphics.fill(signX + start, signY + row, signX + col, signY + row + 1, color);
                    start = -1;
                }
            }
        }
        graphics.pose().popPose();
    }

    /** "Painted for Rustic", or "Not painted yet: Classic look". */
    private String paintedText(VillagerPreview.Entry entry) {
        return preview.paintedIn(entry, set)
                ? Tr.s("screen.dynamicvillages.mayor.theme.skin_painted", "Painted for %s", ThemeSets.name(set))
                : Tr.s("screen.dynamicvillages.mayor.theme.skin_not_painted", "Not painted yet: %s look",
                        ThemeSets.name(VillagerTheme.CLASSIC));
    }

    private void drawArrow(GuiGraphics graphics, int x, int y, String label, int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, x, y, ARROW_W, ARROW_H);
        graphics.fill(x, y, x + ARROW_W, y + ARROW_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + ARROW_W - 1, y + ARROW_H - 1, hovered ? ROW_HOVER : ROW);
        graphics.drawString(font, label, x + (ARROW_W - font.width(label)) / 2 + 1, y + 3, hovered ? TEXT : MUTED, false);
    }

    private void drawCentred(GuiGraphics graphics, String text, int midX, int y, int color) {
        graphics.drawString(font, text, midX - font.width(text) / 2, y, color, false);
    }

    // ---------------------------------------------------------------------
    // The player's changes (kept per set in use: VillagerTheme.changeOf)
    // ---------------------------------------------------------------------

    /** The set a villager on this page wears: the page's set, or on "Your look" the picture worn in the world. */
    private VillagerTheme drawnIn(VillagerPreview.Entry entry) {
        return yourLook ? preview.worn(entry) : set;
    }

    /** "Your look"'s hover on a skin: where its picture comes from. */
    private String lookText(VillagerPreview.Entry entry) {
        VillagerTheme worn = preview.worn(entry);
        if (VillagerPreview.changeOf(entry) != null) {
            return Tr.s("screen.dynamicvillages.mayor.theme.look_changed_tip",
                    "Your change: villagers wear\nthe %s picture.", ThemeSets.name(worn));
        }
        if (worn == set) {
            return Tr.s("screen.dynamicvillages.mayor.theme.look_own_tip", "Villagers wear the %s picture.", ThemeSets.name(worn));
        }
        return Tr.s("screen.dynamicvillages.mayor.theme.look_fallback_tip",
                "%s has no picture for it yet,\nso villagers wear the %s one.", ThemeSets.name(set), ThemeSets.name(worn));
    }

    private Mark mark(VillagerPreview.Entry entry) {
        VillagerTheme from = VillagerPreview.changeOf(entry);
        // "Your look" shows only what the player changed: every picture there is worn.
        if (yourLook) return from != null ? Mark.BROUGHT_IN : Mark.NONE;
        if (set == VillagerTheme.current()) return from != null ? Mark.LEFT_OUT : Mark.WORN;
        if (from == set) return Mark.BROUGHT_IN;
        return preview.looksLike(entry, set) == preview.worn(entry) ? Mark.WORN : Mark.NONE;
    }

    /** The tile's hover line for its mark; null for none. */
    @Nullable
    private String markText(VillagerPreview.Entry entry, Mark mark) {
        return switch (mark) {
            case WORN -> Tr.s("screen.dynamicvillages.mayor.theme.mark_worn", "Villagers wear this picture now.");
            case LEFT_OUT -> Tr.s("screen.dynamicvillages.mayor.theme.mark_left_out",
                    "Left out: villagers wear\nthe %s picture.", ThemeSets.name(preview.worn(entry)));
            case BROUGHT_IN -> Tr.s("screen.dynamicvillages.mayor.theme.mark_brought_in",
                    "Brought into %s:\nvillagers wear this picture.", ThemeSets.name(VillagerTheme.current()));
            case NONE -> null;
        };
    }

    /**
     * On the set in use's page: Leave out, or Put back for a skin changed. On another set's page: Wear in (the set in
     * use), or Stop wearing for this set's picture brought in.
     */
    private Action action(VillagerPreview.Entry entry) {
        VillagerTheme from = VillagerPreview.changeOf(entry);
        if (set == VillagerTheme.current()) return from != null ? Action.PUT_BACK : Action.LEAVE_OUT;
        return from == set ? Action.STOP_WEARING : Action.WEAR;
    }

    private static String actionLabel(Action action) {
        return switch (action) {
            case LEAVE_OUT -> Tr.s("screen.dynamicvillages.mayor.theme.leave_out", "Leave out");
            case PUT_BACK -> Tr.s("screen.dynamicvillages.mayor.theme.put_back", "Put back");
            case WEAR -> Tr.s("screen.dynamicvillages.mayor.theme.wear_in", "Wear in %s",
                    ThemeSets.name(VillagerTheme.current()));
            case STOP_WEARING -> Tr.s("screen.dynamicvillages.mayor.theme.stop_wearing", "Stop wearing");
        };
    }

    /** Why the button would change nothing for this skin now, as its hover text; null when it can be clicked. */
    @Nullable
    private String blocked(VillagerPreview.Entry entry, Action action) {
        VillagerTheme inUse = VillagerTheme.current();
        String classic = ThemeSets.name(VillagerTheme.CLASSIC);
        if (action == Action.LEAVE_OUT && inUse == VillagerTheme.CLASSIC) {
            return Tr.s("screen.dynamicvillages.mayor.theme.leave_out_classic_tip",
                    "Every skin falls back on its\n%s picture, so it can't\nbe left out. Open another set\nto wear one of its skins.", classic);
        }
        if (action == Action.LEAVE_OUT && !preview.paintedIn(entry, inUse)) {
            return Tr.s("screen.dynamicvillages.mayor.theme.leave_out_unpainted_tip",
                    "%s has no picture for this\nskin yet, so these villagers\nalready look %s.", ThemeSets.name(inUse), classic);
        }
        if (action == Action.WEAR && !preview.paintedIn(entry, set)) {
            return Tr.s("screen.dynamicvillages.mayor.theme.wear_unpainted_tip",
                    "%s has no picture for this\nskin yet, so there is\nnothing to wear.", ThemeSets.name(set));
        }
        if (action == Action.WEAR && VillagerPreview.changeOf(entry) == null && preview.worn(entry) == set) {
            return Tr.s("screen.dynamicvillages.mayor.theme.wear_already_tip",
                    "%s has no picture for this\nskin yet, so these villagers\nalready wear this one.", ThemeSets.name(inUse));
        }
        return null;
    }

    private Component[] actionTip(VillagerPreview.Entry entry, Action action) {
        String blocked = blocked(entry, action);
        if (blocked != null) return linesOf(blocked);
        String inUse = ThemeSets.name(VillagerTheme.current());
        Component[] lines = linesOf(switch (action) {
            case LEAVE_OUT -> Tr.s("screen.dynamicvillages.mayor.theme.leave_out_tip",
                    "Villagers with this skin wear\nthe %s picture instead.", ThemeSets.name(VillagerTheme.CLASSIC));
            case PUT_BACK -> Tr.s("screen.dynamicvillages.mayor.theme.put_back_tip",
                    "Villagers with this skin wear\nthe %s picture again.", inUse);
            case WEAR -> Tr.s("screen.dynamicvillages.mayor.theme.wear_in_tip",
                    "Villagers with this skin wear\nthis %s picture while\n%s is in use.", ThemeSets.name(set), inUse);
            case STOP_WEARING -> Tr.s("screen.dynamicvillages.mayor.theme.stop_wearing_tip",
                    "Villagers with this skin go\nback to the %s picture.", inUse);
        });
        VillagerPreview.Entry shared = sharedWith(entry);
        return shared == null ? lines : join(lines, linesOf(Tr.s("screen.dynamicvillages.mayor.theme.shared_tip",
                "%s wears this skin too.", shared.label())));
    }

    /** Another job and skin with the same picture (the captain wears Guard 1's), or null. */
    @Nullable
    private static VillagerPreview.Entry sharedWith(VillagerPreview.Entry entry) {
        String key = entry.skinKey();
        for (VillagerPreview.Entry other : VillagerPreview.ENTRIES) {
            if (!other.equals(entry) && other.skinKey().equals(key)) return other;
        }
        return null;
    }

    /** The button under the big villager was clicked: saves the change, and every villager in the world follows. */
    private void applyAction(VillagerPreview.Entry entry) {
        Action action = action(entry);
        if (blocked(entry, action) != null) return;
        ClientConfig.setSkinChange(VillagerTheme.current(), entry.skinKey(), switch (action) {
            case LEAVE_OUT -> VillagerTheme.CLASSIC;
            case WEAR -> set;
            case PUT_BACK, STOP_WEARING -> null;
        });
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    /**
     * Left clicks: Back closes the page; Use switches every villager to this set; Undo asks, then undoes the changes;
     * a tile puts it on the stage; the button under the big villager changes its skin; the arrows step through every
     * job and skin; a press on the big villager starts turning it, a double click turns it back. False when nothing
     * was hit.
     */
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (set == null || button != 0) return false;
        follow();
        // A release missed while the game was not in front can leave this set: any new press clears it.
        turning = false;
        // Undo asks once: only the very next click, on Undo itself, undoes.
        boolean undoWasAsking = undoAsking();
        undoAskedMs = NO_PRESS;
        int y = top();
        if (inside(mouseX, mouseY, pageLeft() + CONTENT_X, y + BACK_Y, backW(), BACK_H)) {
            close();
            return true;
        }
        if (!yourLook && inside(mouseX, mouseY, useX(), y + HEADER_Y, useW(), ThemeSets.BUTTON_H)) {
            if (ThemeSets.useClicked(set, mouseX, mouseY, useX(), y + HEADER_Y, useW())) ClientConfig.setVillagerTheme(set);
            return true;
        }
        if (undoShown() && inside(mouseX, mouseY, undoX(), y + HEADER_Y, undoW(), ThemeSets.BUTTON_H)) {
            if (undoWasAsking) {
                ClientConfig.clearSkinChanges(set);
            } else {
                undoAskedMs = Util.getMillis();
            }
            return true;
        }
        int tile = tileAt(mouseX, mouseY);
        if (tile >= 0) {
            shown = tile;
            return true;
        }
        int stageX = stageX();
        if (inside(mouseX, mouseY, stageX + 6, skinButtonY(), STAGE_W - 12, ThemeSets.BUTTON_H)) {
            applyAction(VillagerPreview.ENTRIES.get(shown));
            return true;
        }
        int rowY = bodyBottom() - PAD - ARROWS_UP;
        int count = VillagerPreview.ENTRIES.size();
        if (inside(mouseX, mouseY, stageX + 6, rowY, ARROW_W, ARROW_H)) {
            shown = (shown + count - 1) % count;
            scrollTo(shown);
            return true;
        }
        if (inside(mouseX, mouseY, stageX + STAGE_W - 6 - ARROW_W, rowY, ARROW_W, ARROW_H)) {
            shown = (shown + 1) % count;
            scrollTo(shown);
            return true;
        }
        if (preview.working() && onVillager(mouseX, mouseY)) {
            long now = Util.getMillis();
            if (lastPressMs != NO_PRESS && now - lastPressMs < DOUBLE_CLICK_MS) {
                turn = VillagerPreview.SIDE_TURN;
                tilt = 0F;
                lastPressMs = NO_PRESS;
            } else {
                lastPressMs = now;
            }
            turning = true;
            return true;
        }
        return false;
    }

    /**
     * The mouse moved with the left button held: after a press on the big villager, sideways turns it all the way
     * round and up and down tips it. False otherwise.
     */
    boolean mouseDragged(double dragX, double dragY) {
        if (!turning) return false;
        // Dragging right brings its face round to the right; dragging down shows it from above.
        turn = Mth.wrapDegrees(turn - (float) dragX * DEGREES_PER_PIXEL);
        tilt = Mth.clamp(tilt + (float) dragY * DEGREES_PER_PIXEL, -MAX_TILT, MAX_TILT);
        if (dragX != 0 || dragY != 0) turnedOnce = true;
        return true;
    }

    /** The left button let go, wherever the mouse is: the villager stays turned as it is. True if it was being turned. */
    boolean mouseReleased() {
        boolean was = turning;
        turning = false;
        return was;
    }

    /** The wheel over the grid (its scrollbar too) moves it a row. */
    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (set == null || scrollY == 0) return false;
        int gridTop = gridTop();
        if (!inside(mouseX, mouseY, gridX(), gridTop, stageX() - PAD - gridX(), bodyBottom() - PAD - gridTop)) return false;
        scrollRow = Mth.clamp(scrollRow + (scrollY < 0 ? 1 : -1), 0, maxScroll());
        return true;
    }
}
