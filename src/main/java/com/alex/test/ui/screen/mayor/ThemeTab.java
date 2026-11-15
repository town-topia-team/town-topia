package com.alex.test.ui.screen.mayor;

import com.alex.test.ClientConfig;
import com.alex.test.Tr;
import com.alex.test.client.VillagerTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * The Theme tab: which set of villager skins this computer draws ({@link VillagerTheme}, saved in the client
 * config, never in the world). Tall cards side by side: first "Your look" (every skin as seen in the world now: the
 * set in use with the player's changes, and how many changes), then one per set: its name, who made it, small villagers
 * in it, how far it is painted, and a Use button, or an "In use" badge on the set in use. Clicking a card opens its page
 * ({@link ThemePackView}) with every skin; clicking one of its small villagers opens it on that one.
 */
final class ThemeTab extends MayorPart {

    /** The cards' column, where the Performance tab's buttons start too. */
    private static final int PAGE_X = CONTENT_X + 6;
    private static final int CARDS_Y = INFO_SELECTOR_Y;
    private static final int CARD_GAP = 6;
    /**
     * The row under the icon and name: "Your look"'s tagline, or a set's credit (a set has no tagline since 10-23).
     * A card is too narrow to have it beside the icon.
     */
    private static final int TAGLINE_Y = 26;
    /** A tagline too long for its card goes on in a second row this much lower. */
    private static final int LINE_H = 10;
    /**
     * The small villagers start under the tagline (a row lower for each extra row any card has: "Your look"'s two-row
     * tagline), and end above the text row over the Use row.
     */
    private static final int STRIP_TOP = 38;
    /** The line over the Use row: how far a set is painted, or which set "Your look" wears. */
    private static final int TEXT_ROW_H = 11;
    /** Less room than this and the card shows no villagers. */
    private static final int STRIP_MIN_H = 28;
    /** A small villager's box is at most this many times as tall as it is wide, so its arms stay inside. */
    private static final float VILLAGER_ASPECT = 2.6F;

    private final VillagerPreview preview = new VillagerPreview();

    ThemeTab(MayorStatsScreen screen) {
        super(screen);
    }

    /** Every client tick while the tab shows: the small villagers' idle clip moves on. */
    void tick() {
        preview.tick();
    }

    /** The cards' bottom, for the screen's page scroll: as low as the body allows, so the tab never scrolls. */
    int pageBottom() {
        return cardsBottom();
    }

    /** As low as the lists of the other tabs, moved up as much as a small screen shortens the window. */
    private int cardsBottom() {
        return INFO_BOTTOM - screen.need();
    }

    // ---------------------------------------------------------------------
    // Where things are
    // ---------------------------------------------------------------------

    /** "Your look", then one per set. */
    private static int cards() {
        return VillagerTheme.values().length + 1;
    }

    /** The set on this card; null for "Your look" (card 0). */
    @Nullable
    private static VillagerTheme setOf(int card) {
        return card == 0 ? null : VillagerTheme.values()[card - 1];
    }

    /** "Your look" and three sets: about 93 wide each in the 420 Theme window, 77 in the narrowest (356). */
    private int cardW() {
        int cards = cards();
        return (innerW() - CARD_GAP * (cards - 1)) / cards;
    }

    private int cardX(int index) {
        return left() + PAGE_X + index * (cardW() + CARD_GAP);
    }

    private int cardY() {
        return top() + CARDS_Y;
    }

    /** 209 on the full window, 125 on the shortest. */
    private int cardH() {
        return cardsBottom() - CARDS_Y;
    }

    /** The Use row: a button across the card. */
    private int buttonW() {
        return cardW() - 12;
    }

    private static int buttonLeft(int cardX) {
        return cardX + 6;
    }

    private int buttonTop(int cardY) {
        return cardY + cardH() - 6 - ThemeSets.BUTTON_H;
    }

    /**
     * The small villagers' room: 122 high on the full window, 38 on the shortest ("Your look"'s tagline takes two rows
     * on every window width).
     */
    private int stripH() {
        return cardH() - 6 - ThemeSets.BUTTON_H - 4 - TEXT_ROW_H - stripTop();
    }

    /** Where the small villagers start: under the card with the most rows under its name, so they line up. */
    private int stripTop() {
        int rows = 1;
        for (int i = 0; i < cards(); i++) rows = Math.max(rows, headRows(setOf(i)));
        return STRIP_TOP + (rows - 1) * LINE_H;
    }

    /** "Your look": its tagline's rows. A set: one for the artist's credit, none without an artist. */
    private int headRows(@Nullable VillagerTheme set) {
        if (set == null) return lookTaglineRows().size();
        return ThemeSets.artist(set) != null ? 1 : 0;
    }

    /**
     * "Your look"'s tagline in one row, or two when it is too long for the card: broken after the last space or dash
     * that fits, the second row cut short if it still does not fit.
     */
    private List<String> lookTaglineRows() {
        String tagline = ThemeSets.lookTagline();
        int width = cardW() - 12;
        if (font.width(tagline) <= width) return List.of(tagline);
        String fits = font.plainSubstrByWidth(tagline, width);
        int space = fits.lastIndexOf(' ');
        int dash = fits.lastIndexOf('-');
        if (Math.max(space, dash) <= 0) return List.of(trim(tagline, width));
        // A dash stays at the end of the first row; a space is dropped.
        int end = dash > space ? dash + 1 : space;
        return List.of(tagline.substring(0, end), trim(tagline.substring(dash > space ? end : end + 1), width));
    }

    private boolean stripShown() {
        return stripH() >= STRIP_MIN_H && preview.working();
    }

    /** One row of six, or two rows of three when the card is tall: whichever draws them bigger. */
    private int stripRows() {
        return villagerH(2) > villagerH(1) ? 2 : 1;
    }

    private static int stripColumns(int rows) {
        return (VillagerPreview.STRIP_SIZE + rows - 1) / rows;
    }

    private int slotW(int rows) {
        return (cardW() - 12) / stripColumns(rows);
    }

    /** How tall the small villagers' boxes can be in that many rows: as tall as a row, as narrow as a slot allows. */
    private int villagerH(int rows) {
        return Math.min(stripH() / rows, (int) (slotW(rows) * VILLAGER_ASPECT));
    }

    /** A small villager's box on a card, in the middle of its row's height. */
    private record Box(int x, int y, int w, int h) {}

    private Box stripBox(int cardX, int cardY, int index) {
        int rows = stripRows();
        int columns = stripColumns(rows);
        int rowH = stripH() / rows;
        int h = villagerH(rows);
        int w = slotW(rows);
        return new Box(cardX + 6 + (index % columns) * w, cardY + stripTop() + (index / columns) * rowH + (rowH - h) / 2, w, h);
    }

    /** The small villagers on a card: "Your look" shows the skins the player changed first. */
    private List<VillagerPreview.Entry> strip(@Nullable VillagerTheme set) {
        return set == null ? preview.lookStrip() : preview.strip(set);
    }

    /** The small villager under the mouse on this card, or -1. */
    private int stripSlotAt(double mouseX, double mouseY, int cardX, int cardY, int count) {
        if (!stripShown()) return -1;
        for (int i = 0; i < count; i++) {
            Box box = stripBox(cardX, cardY, i);
            if (inside(mouseX, mouseY, box.x(), box.y(), box.w(), box.h())) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = cardY();
        for (int i = 0; i < cards(); i++) {
            VillagerTheme set = setOf(i);
            int x = cardX(i);
            drawCard(graphics, set, x, y, mouseX, mouseY);
            if (!inside(mouseX, mouseY, x, y, cardW(), cardH())) continue;
            List<VillagerPreview.Entry> strip = strip(set);
            int slot = stripSlotAt(mouseX, mouseY, x, y, strip.size());
            int changes = VillagerTheme.changeCount(VillagerTheme.current());
            boolean onButtonRow = inside(mouseX, mouseY, buttonLeft(x), buttonTop(y), buttonW(), ThemeSets.BUTTON_H);
            if (set != null && onButtonRow) {
                screen.setDeferredTooltip(tooltipLines(ThemeSets.buttonTip(set)));
            } else if (set == null && onButtonRow && changes > 0) {
                screen.setDeferredTooltip(tooltipLines(ThemeSets.changesTip(changes)));
            } else if (slot >= 0) {
                screen.setDeferredTooltip(tooltipLines(join(
                        new Component[]{Component.literal(strip.get(slot).label())}, openTip())));
            } else {
                screen.setDeferredTooltip(tooltipLines(openTip()));
            }
        }
    }

    private static Component[] openTip() {
        return linesOf(Tr.s("screen.dynamicvillages.mayor.theme.open_tip", "Click to see every skin."));
    }

    /** One card: "Your look" for a null set. */
    private void drawCard(GuiGraphics graphics, @Nullable VillagerTheme set, int x, int y, int mouseX, int mouseY) {
        int w = cardW();
        int h = cardH();
        boolean hovered = inside(mouseX, mouseY, x, y, w, h);
        graphics.fill(x, y, x + w, y + h, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, hovered ? ROW_HOVER : ROW);
        graphics.renderFakeItem(new ItemStack(set == null ? ThemeSets.LOOK_ICON : ThemeSets.icon(set)), x + 6, y + 6);
        String name = set == null ? ThemeSets.lookName() : ThemeSets.name(set, w - 34);
        graphics.drawString(font, trim(name, w - 34), x + 28, y + 10, TEXT, false);
        if (set == null) {
            List<String> tagline = lookTaglineRows();
            for (int row = 0; row < tagline.size(); row++) {
                graphics.drawString(font, tagline.get(row), x + 6, y + TAGLINE_Y + row * LINE_H, MUTED, false);
            }
        } else {
            String credit = ThemeSets.creditLine(set, w - 12);
            if (credit != null) graphics.drawString(font, trim(credit, w - 12), x + 6, y + TAGLINE_Y, GOLD, false);
        }

        int by = buttonTop(y);
        int textY = by - TEXT_ROW_H;
        if (set == null) {
            // Which set villagers wear, and the player's changes to it where a set has its Use button.
            VillagerTheme inUse = VillagerTheme.current();
            boolean changed = VillagerTheme.changeCount(inUse) > 0;
            graphics.drawString(font, trim(ThemeSets.lookSetLine(inUse, w - 12), w - 12), x + 6, textY, MUTED, false);
            graphics.drawString(font, trim(ThemeSets.lookChangesLine(inUse), w - 12), x + 6, by + 5,
                    changed ? GOLD : DIM, false);
        } else {
            String painted = ThemeSets.paintedLine(set);
            if (font.width(painted) > w - 12) painted = ThemeSets.paintedLineShort(set);
            graphics.drawString(font, trim(painted, w - 12), x + 6, textY, MUTED, false);
            ThemeSets.drawButton(graphics, set, buttonLeft(x), by, buttonW(), mouseX, mouseY);
        }
        if (stripShown()) drawStrip(graphics, set, x, y);
    }

    /** The card's small villagers, each turned a little to the side: in the set's skins, or as worn on "Your look". */
    private void drawStrip(GuiGraphics graphics, @Nullable VillagerTheme set, int x, int y) {
        List<VillagerPreview.Entry> strip = strip(set);
        for (int i = 0; i < strip.size(); i++) {
            VillagerPreview.Entry entry = strip.get(i);
            Box box = stripBox(x, y, i);
            preview.drawTurned(graphics, entry, set == null ? preview.worn(entry) : set,
                    box.x(), box.y(), box.x() + box.w(), box.y() + box.h(), VillagerPreview.SIDE_TURN, 0F);
        }
    }

    // ---------------------------------------------------------------------
    // Clicks
    // ---------------------------------------------------------------------

    /**
     * Left clicks on a card: a set's Use button switches every villager to it; anywhere else opens the card's page,
     * on the small villager clicked when it was one.
     */
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        int y = cardY();
        for (int i = 0; i < cards(); i++) {
            VillagerTheme set = setOf(i);
            int x = cardX(i);
            if (!inside(mouseX, mouseY, x, y, cardW(), cardH())) continue;
            if (set != null && ThemeSets.useClicked(set, mouseX, mouseY, buttonLeft(x), buttonTop(y), buttonW())) {
                ClientConfig.setVillagerTheme(set);
                return true;
            }
            List<VillagerPreview.Entry> strip = strip(set);
            int slot = stripSlotAt(mouseX, mouseY, x, y, strip.size());
            screen.openPack(set, slot >= 0 ? VillagerPreview.ENTRIES.indexOf(strip.get(slot)) : -1);
            return true;
        }
        return false;
    }
}
