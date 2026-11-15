package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import com.alex.test.network.LocateVillagerPayload;
import com.alex.test.town.VillagerStatsEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * Village Info > Villagers: the profession search, the happiness badge and two columns of villager
 * cards; a click opens one villager's card (Back, Locate, task, home, bars). On a small screen the card
 * page scrolls (the screen does that) and its four bars sit two by two.
 */
final class VillagersPage extends MayorPart {

    private static final int VILLAGER_ROW_H = 26;
    private static final int FULL_VILLAGER_ROWS = 6;
    private static final int VILLAGER_LIST_Y = 110;
    private static final int WELLBEING_BADGE_W = 116;
    /** A villager's card at full size, under its Back and Locate buttons. */
    private static final int FULL_VILLAGER_CARD_H = 180 - 24;

    private final InfoTab infoTab;
    /** What is typed in the search box; it matches professions. */
    private String searchText = "";
    private int listScroll;
    private int selected = -1;

    /** The list of cards. */
    final ScrollList list = new ScrollList() {
        @Override public boolean shown() { return screen.tab() == MayorStatsScreen.Tab.INFO && infoTab.villagerListShown(); }
        @Override public int x() { return left() + CONTENT_X + 6; }
        @Override public int y() { return top() + VILLAGER_LIST_Y; }
        @Override public int width() { return listW(true); }
        @Override public int height() { return listH(); }
        @Override public boolean sideBar() { return true; }
        @Override public int scroll() { return listScroll; }
        @Override public void setScroll(int value) { listScroll = value; }
        @Override public int maxScroll() { return listMaxScroll(); }
        @Override public int thumbHeight() { return thumbHeightFor(gridRows(menu.snapshot().villagers().size())); }
    };

    VillagersPage(MayorStatsScreen screen, InfoTab infoTab) {
        super(screen);
        this.infoTab = infoTab;
    }

    /** The search box over the list; the screen adds it as a widget (it calls this from its init). */
    EditBox makeSearch() {
        EditBox search = new EditBox(font, left() + CONTENT_X + 6,
                top() + INFO_LIST_Y, searchW(), 18,
                Tr.c("screen.dynamicvillages.mayor.search_professions", "Search professions"));
        search.setHint(Tr.c("screen.dynamicvillages.mayor.search_professions_224", "Search professions..."));
        search.setMaxLength(128);
        search.setValue(searchText);
        search.setResponder(value -> {
            searchText = value;
            listScroll = 0;
        });
        return search;
    }

    /** The search box, then the happiness badge at the right end of the row. */
    private int searchW() {
        return innerW() - WELLBEING_BADGE_W - 4;
    }

    /** The villager whose card is open, or -1 for the list. */
    int selected() {
        return selected;
    }

    void select(int index) {
        selected = index;
    }

    // ---------------------------------------------------------------------
    // Fitting small screens
    // ---------------------------------------------------------------------

    private int visibleRows() {
        return FULL_VILLAGER_ROWS - screen.rowsLost(VILLAGER_ROW_H, FULL_VILLAGER_ROWS, 2);
    }

    private int listH() {
        return VILLAGER_ROW_H * visibleRows() + 2;
    }

    /** How much shorter the window is drawn on the list page. */
    int listCut() {
        return (FULL_VILLAGER_ROWS - visibleRows()) * VILLAGER_ROW_H;
    }

    /** On a small screen the villager's four bars sit two by two, 24 px shorter. */
    private boolean compactCard() {
        return screen.need() > 0;
    }

    /** The villager card: full height, or when compact as tall as fits but never cut short. */
    int cardH() {
        if (!compactCard()) return FULL_VILLAGER_CARD_H;
        return Math.max(FULL_VILLAGER_CARD_H - 24, FULL_VILLAGER_CARD_H - screen.need());
    }

    // ---------------------------------------------------------------------
    // The list
    // ---------------------------------------------------------------------

    private String averageWellbeingValue() {
        List<VillagerStatsEntry> villagers = menu.snapshot().villagers();
        if (villagers.isEmpty()) return "--";
        int total = 0;
        for (VillagerStatsEntry villager : villagers) total += villager.wellbeing();
        return Math.round((float) total / villagers.size()) + "%";
    }

    /** Matching professions first, then (while searching) everyone else. */
    private List<Integer> orderedIndexes() {
        List<Integer> ordered = new ArrayList<>();
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        List<VillagerStatsEntry> entries = menu.snapshot().villagers();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).profession().toLowerCase(Locale.ROOT).contains(query)) {
                ordered.add(i);
            }
        }
        if (!query.isEmpty()) {
            for (int i = 0; i < entries.size(); i++) {
                if (!entries.get(i).profession().toLowerCase(Locale.ROOT).contains(query)) {
                    ordered.add(i);
                }
            }
        }
        return ordered;
    }

    private int listMaxScroll() {
        return Math.max(0, gridRows(menu.snapshot().villagers().size()) - visibleRows());
    }

    /** The list, or the open villager's card. */
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (selected >= menu.snapshot().villagers().size()) selected = -1;
        if (selected >= 0) renderDetails(graphics, mouseX, mouseY);
        else renderList(graphics, mouseX, mouseY);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + 6;
        int y = top() + VILLAGER_LIST_Y;
        List<VillagerStatsEntry> entries = menu.snapshot().villagers();
        List<Integer> ordered = orderedIndexes();

        int badgeX = x + searchW() + 4;
        int badgeY = top() + INFO_LIST_Y;
        graphics.fill(badgeX, badgeY, badgeX + WELLBEING_BADGE_W,
                badgeY + 18, PANEL_BORDER);
        graphics.fill(badgeX + 1, badgeY + 1, badgeX + WELLBEING_BADGE_W - 1,
                badgeY + 17, ROW_SELECTED);
        graphics.drawCenteredString(font, Tr.s("screen.dynamicvillages.mayor.happiness_badge", "Happiness %s", averageWellbeingValue()),
                badgeX + WELLBEING_BADGE_W / 2, badgeY + 5, ACCENT);

        int maxScroll = Math.max(0, gridRows(ordered.size()) - visibleRows());
        int listW = listW(maxScroll > 0);
        int cardW = cardWidth(listW);
        drawListFrame(graphics, x, y, listW, listH());
        listScroll = Math.min(listScroll, maxScroll);

        graphics.enableScissor(x + 1, y + 1, x + listW - 1, y + listH() - 1);
        for (int row = 0; row < visibleRows(); row++) {
            for (int column = 0; column < VILLAGER_COLUMNS; column++) {
                int position = (listScroll + row) * VILLAGER_COLUMNS + column;
                if (position >= ordered.size()) break;
                VillagerStatsEntry entry = entries.get(ordered.get(position));
                int cardX = cardX(x, column, cardW);
                int cardY = y + 1 + row * VILLAGER_ROW_H;
                int cardH = cardHeight(row);
                boolean hovered = inside(mouseX, mouseY, cardX, cardY,
                        cardW, cardH);
                graphics.fill(cardX, cardY, cardX + cardW,
                        cardY + cardH, hovered ? ROW_HOVER : ROW);
                graphics.renderFakeItem(icon(entry.iconItemId()), cardX + 4, cardY + 4);
                graphics.drawString(font, trim(entry.name(), cardW - 28),
                        cardX + 24, cardY + 3, TEXT, false);
                boolean matchesSearch = !searchText.isBlank()
                        && entry.profession().toLowerCase(Locale.ROOT).contains(
                        searchText.trim().toLowerCase(Locale.ROOT));
                graphics.drawString(font, trim(entry.profession(), cardW - 28),
                        cardX + 24, cardY + 14, matchesSearch ? ACCENT : MUTED, false);
            }
        }
        graphics.disableScissor();
        if (entries.isEmpty()) {
            drawEmpty(graphics, x, y, listW, listH(), Tr.s("screen.dynamicvillages.mayor.no_villagers_registered", "No villagers registered"));
        }
        if (maxScroll > 0) {
            int barX = x + listW + SHOP_SCROLLBAR_GAP;
            int thumbH = thumbHeightFor(gridRows(entries.size()));
            int thumbY = y + (listH() - thumbH) * listScroll / maxScroll;
            graphics.fill(barX, y, barX + SHOP_SCROLLBAR_W,
                    y + listH(), 0xFF333333);
            graphics.fill(barX, thumbY, barX + SHOP_SCROLLBAR_W,
                    thumbY + thumbH, 0xFF888888);
        }

    }

    private int cardHeight(int row) {
        return VILLAGER_ROW_H - (row == visibleRows() - 1 ? 0 : 2);
    }

    private int thumbHeightFor(int rowCount) {
        return Math.max(18, listH() * visibleRows() / rowCount);
    }

    /** Hover on the happiness badge: what the number is. */
    void renderBadgeTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (inside(mouseX, mouseY,
                left() + CONTENT_X + 6 + searchW() + 4,
                top() + INFO_LIST_Y, WELLBEING_BADGE_W, 18)) {
            graphics.renderTooltip(font, Tr.c("screen.dynamicvillages.mayor.average_happiness", "Average happiness of this village: %s", averageWellbeingValue()),
                    mouseX, mouseY);
        }
    }

    // ---------------------------------------------------------------------
    // One villager's card
    // ---------------------------------------------------------------------

    private void renderDetails(GuiGraphics graphics, int mouseX, int mouseY) {
        List<VillagerStatsEntry> entries = menu.snapshot().villagers();
        if (selected < 0 || selected >= entries.size()) {
            selected = -1;
            return;
        }

        int x = left() + CONTENT_X + 6;
        int y = top() + INFO_LIST_Y;
        int width = innerW();
        VillagerStatsEntry entry = entries.get(selected);

        boolean backHovered = inside(mouseX, mouseY, x, y, 66, SUBNAV_H);
        graphics.fill(x, y, x + 66, y + SUBNAV_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + 65, y + SUBNAV_H - 1,
                backHovered ? ROW_HOVER : ROW);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.back", "< Back"), x + 10, y + 5, TEXT, false);

        int locateX = x + width - 72;
        boolean locateHovered = inside(mouseX, mouseY, locateX, y, 72, SUBNAV_H);
        graphics.fill(locateX, y, locateX + 72, y + SUBNAV_H, PANEL_BORDER);
        graphics.fill(locateX + 1, y + 1, locateX + 71, y + SUBNAV_H - 1,
                locateHovered ? ROW_HOVER : ROW_SELECTED);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.locate", "Locate"), locateX + (72 - font.width(Tr.s("screen.dynamicvillages.mayor.locate", "Locate"))) / 2,
                y + 5, TEXT, false);

        int cardY = y + 24;
        drawListFrame(graphics, x, cardY, width, cardH());
        int textX = x + 9;
        int textW = width - 18;
        graphics.renderFakeItem(icon(entry.iconItemId()), textX, cardY + 7);
        graphics.drawString(font, trim(entry.name(), textW - 24), textX + 24, cardY + 7, TEXT, false);
        String role = entry.profession() + " - " + entry.status();
        graphics.drawString(font, trim(role, textW - 24), textX + 24, cardY + 19,
                "Active".equals(entry.status()) ? ACCENT : MUTED, false);
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.card.task", "Task: %s", entry.activity()), textW), textX, cardY + 39, MUTED, false);
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.card.work", "Work: %s", entry.workplace()), textW), textX, cardY + 51, MUTED, false);
        if (infoTab.buildings().homeOf(entry.id()) >= 0) {
            // Links to the house's own card in the Buildings list.
            boolean homeHovered = inside(mouseX, mouseY, textX - 2, cardY + 61, textW + 2, 11);
            if (homeHovered) graphics.fill(textX - 2, cardY + 61, textX + textW, cardY + 72, ROW_HOVER);
            graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.card.home", "Home: %s", entry.home()) + " >", textW), textX, cardY + 63,
                    homeHovered ? TEXT : ACCENT, false);
        } else {
            graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.card.home", "Home: %s", entry.home()), textW), textX, cardY + 63, MUTED, false);
        }
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.card.bed", "Claimed bed: %s", entry.bed()), textW), textX, cardY + 75, MUTED, false);
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.card.diet", "Diet: %s", entry.diet()), textW), textX, cardY + 87, MUTED, false);
        if (compactCard()) {
            // Small screen: the four bars two by two, two rows shorter.
            int columnW = (textW - 10) / 2;
            int rightX = textX + columnW + 10;
            drawMiniStat(graphics, textX, cardY + 99, columnW, Tr.s("screen.dynamicvillages.mayor.stat.health", "Health"), entry.health(), 0xFFE64B45);
            drawMiniStat(graphics, rightX, cardY + 99, columnW, Tr.s("screen.dynamicvillages.mayor.stat.food", "Food"), entry.hunger(), 0xFFE58A32);
            drawMiniStat(graphics, textX, cardY + 111, columnW, Tr.s("screen.dynamicvillages.mayor.stat.happiness", "Happiness"), entry.wellbeing(), 0xFF4CCB68);
            drawMiniStat(graphics, rightX, cardY + 111, columnW, Tr.s("screen.dynamicvillages.mayor.stat.intellect", "Intellect"), entry.intelligence(), 0xFF4B85DE);
        } else {
            drawMiniStat(graphics, textX, cardY + 99, textW, Tr.s("screen.dynamicvillages.mayor.stat.health", "Health"), entry.health(), 0xFFE64B45);
            drawMiniStat(graphics, textX, cardY + 111, textW, Tr.s("screen.dynamicvillages.mayor.stat.food", "Food"), entry.hunger(), 0xFFE58A32);
            drawMiniStat(graphics, textX, cardY + 123, textW, Tr.s("screen.dynamicvillages.mayor.stat.happiness", "Happiness"), entry.wellbeing(), 0xFF4CCB68);
            drawMiniStat(graphics, textX, cardY + 135, textW, Tr.s("screen.dynamicvillages.mayor.stat.intellect", "Intellect"), entry.intelligence(), 0xFF4B85DE);
        }
    }

    private void drawMiniStat(GuiGraphics graphics, int x, int y, int width,
                              String label, int value, int color) {
        String valueText = value < 0 ? "?" : value + "%";
        graphics.drawString(font, label, x, y, MUTED, false);
        graphics.drawString(font, valueText, x + width - font.width(valueText), y, TEXT, false);
        int barX = x + 62;
        int barW = Math.max(12, width - 92);
        graphics.fill(barX, y + 2, barX + barW, y + 6, 0xFF090909);
        if (value >= 0) {
            int fill = Math.max(0, Math.min(barW - 2, value * (barW - 2) / 100));
            graphics.fill(barX + 1, y + 3, barX + 1 + fill, y + 5, color);
        }
    }

    // ---------------------------------------------------------------------
    // Clicks
    // ---------------------------------------------------------------------

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        int contentX = left() + CONTENT_X + 6;
        int y = top();
        if (selected >= 0 && inside(mouseX, mouseY, contentX, y + INFO_LIST_Y, 66, SUBNAV_H)) {
            selected = -1;
            return true;
        }
        if (selected >= 0 && inside(mouseX, mouseY, contentX + innerW() - 72,
                y + INFO_LIST_Y, 72, SUBNAV_H)) {
            List<VillagerStatsEntry> villagers = menu.snapshot().villagers();
            if (selected < villagers.size()) {
                PacketDistributor.sendToServer(new LocateVillagerPayload(
                        menu.mayorId(), villagers.get(selected).id()));
            }
            return true;
        }
        if (selected >= 0 && button == 0 && clickHome(mouseX, mouseY)) {
            return true;
        }
        if (selected < 0 && inside(mouseX, mouseY, contentX, y + VILLAGER_LIST_Y,
                innerW(), listH())) {
            List<Integer> ordered = orderedIndexes();
            int cardW = cardWidth(listW(listMaxScroll() > 0));
            for (int row = 0; row < visibleRows(); row++) {
                for (int column = 0; column < VILLAGER_COLUMNS; column++) {
                    int position = (listScroll + row) * VILLAGER_COLUMNS + column;
                    if (position >= ordered.size()) break;
                    int cardX = cardX(contentX, column, cardW);
                    int cardY = y + VILLAGER_LIST_Y + 1 + row * VILLAGER_ROW_H;
                    if (inside(mouseX, mouseY, cardX, cardY,
                            cardW, cardHeight(row))) {
                        selected = ordered.get(position);
                        return true;
                    }
                }
            }
            return true;
        }
        return false;
    }

    /** "Home: ..." on a villager's card opens that house's card. */
    private boolean clickHome(double mouseX, double mouseY) {
        List<VillagerStatsEntry> villagers = menu.snapshot().villagers();
        if (selected < 0 || selected >= villagers.size()) return false;
        int textX = left() + CONTENT_X + 6 + 9;
        int textW = innerW() - 18;
        int cardY = top() + DETAIL_Y;
        if (!inside(mouseX, mouseY, textX - 2, cardY + 61, textW + 2, 11)) return false;
        int home = infoTab.buildings().homeOf(villagers.get(selected).id());
        if (home < 0) return false;
        infoTab.showBuilding(home);
        return true;
    }
}
