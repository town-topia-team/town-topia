package com.alex.test.ui.screen.mayor;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;
import com.alex.test.network.MayorBuildingPayloads;
import com.alex.test.town.BuildingInstanceEntry;
import com.alex.test.town.BuildingStatsEntry;
import com.alex.test.town.VillagerStatsEntry;
import com.alex.test.village.claims.ClaimLock;
import com.alex.test.village.stats.CrowdingBalance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * Village Info > Buildings: the type buttons, the search box and two columns of building cards; a
 * click opens one building's page (Back, Manage, Locate, and its facts in a card that scrolls).
 */
final class BuildingsPage extends MayorPart {

    // Type buttons, the search box, then two columns of cards like the villagers.
    // The same 4-pixel gap separates the summary cards, the buttons, the search box and the list.
    static final int BUILDING_GAP = 4;
    private static final int BUILDING_CHIP_Y = INFO_SELECTOR_Y + INFO_SELECTOR_H + BUILDING_GAP;
    private static final int BUILDING_CHIP_H = 17;
    private static final int BUILDING_SEARCH_Y = BUILDING_CHIP_Y + BUILDING_CHIP_H + BUILDING_GAP;
    private static final int BUILDING_SEARCH_H = 16;
    private static final int BUILDING_LIST_Y = BUILDING_SEARCH_Y + BUILDING_SEARCH_H + BUILDING_GAP;
    private static final int BUILDING_ROW_H = 23;
    private static final int FULL_BUILDING_ROWS = 6;
    private static final int PROBLEM_BUTTON_H = 12;
    /** Where the facts start in a building's card, below its name and place. */
    private static final int DETAIL_BODY_OFFSET = 37;
    private static final int DETAIL_LINE_H = 11;
    private static final int FULL_DETAIL_LINES = (FULL_DETAIL_H - DETAIL_BODY_OFFSET - 3) / DETAIL_LINE_H;
    private static final int HEADER_STAT_GAP = 14;

    /** The type buttons. The Town Hall and guard posts (OTHER) have no button of their own; they show under All. */
    private static final BuildingInstanceEntry.Category[] CATEGORIES = {
            null,
            BuildingInstanceEntry.Category.HOUSE,
            BuildingInstanceEntry.Category.WORK,
            BuildingInstanceEntry.Category.STORAGE};

    /**
     * A line of a building's facts, with an optional value on the right and a
     * coloured bar on the left; clicking one with {@code villager} >= 0 opens that villager.
     */
    private record DetailLine(FormattedCharSequence text, int color, String value, int valueColor,
                              int villager, int bar) {}

    /** One fact in a building's header: a label over its value, explained by {@code tip} on hover, one paragraph per string. */
    private record HeaderStat(String label, String value, int valueColor, List<String> tip) {
        HeaderStat(String label, String value, int valueColor, String... tip) {
            this(label, value, valueColor, List.of(tip));
        }
    }

    private final InfoTab infoTab;
    /** The type button that is on; null for All. */
    private BuildingInstanceEntry.Category categoryFilter;
    /** What is typed in the search box. */
    private String searchText = "";
    private int listScroll;
    private int selected = -1;
    private int detailScroll;

    /** The list of cards. */
    final ScrollList list = new ScrollList() {
        @Override public boolean shown() { return screen.tab() == MayorStatsScreen.Tab.INFO && infoTab.buildingListShown(); }
        @Override public int x() { return left() + CONTENT_X + 6; }
        @Override public int y() { return top() + BUILDING_LIST_Y; }
        @Override public int width() { return listW(true); }
        @Override public int height() { return listH(); }
        @Override public boolean sideBar() { return true; }
        @Override public int scroll() { return listScroll; }
        @Override public void setScroll(int value) { listScroll = value; }
        @Override public int maxScroll() { return listMaxScroll(); }
        @Override public int thumbHeight() { return sideThumbHeight(listH(), visibleRows(), gridRows(shownBuildings().size())); }
    };

    /** One building's facts. */
    final ScrollList detail = new ScrollList() {
        @Override public boolean shown() { return screen.tab() == MayorStatsScreen.Tab.INFO && infoTab.buildingDetailShown(); }
        @Override public int x() { return left() + CONTENT_X + 6; }
        @Override public int y() { return top() + DETAIL_Y; }
        @Override public int width() { return listW(true); }
        @Override public int height() { return detailH(); }
        @Override public boolean sideBar() { return true; }
        @Override public int scroll() { return detailScroll; }
        @Override public void setScroll(int value) { detailScroll = value; }
        @Override public int maxScroll() { return Math.max(0, detailLineCount() - visibleLines()); }
        @Override public int thumbHeight() { return sideThumbHeight(detailH(), visibleLines(), detailLineCount()); }
    };

    BuildingsPage(MayorStatsScreen screen, InfoTab infoTab) {
        super(screen);
        this.infoTab = infoTab;
    }

    /** The search box over the list; the screen adds it as a widget (it calls this from its init). */
    EditBox makeSearch() {
        EditBox search = new EditBox(font, left() + CONTENT_X + 6,
                top() + BUILDING_SEARCH_Y, innerW(), BUILDING_SEARCH_H,
                Tr.c("screen.dynamicvillages.mayor.search_buildings", "Search buildings"));
        search.setHint(Tr.c("screen.dynamicvillages.mayor.search_by_name_place_or_villager", "Search by name, place or villager..."));
        search.setMaxLength(128);
        search.setValue(searchText);
        search.setResponder(value -> {
            searchText = value;
            listScroll = 0;
        });
        return search;
    }

    /** The building whose page is open, or -1 for the list. */
    int selected() {
        return selected;
    }

    void select(int index) {
        selected = index;
        detailScroll = 0;
    }

    /** Back to the list. */
    void back() {
        selected = -1;
    }

    // ---------------------------------------------------------------------
    // Fitting small screens
    // ---------------------------------------------------------------------

    private int visibleRows() {
        return FULL_BUILDING_ROWS - screen.rowsLost(BUILDING_ROW_H, FULL_BUILDING_ROWS, 2);
    }

    private int listH() {
        return BUILDING_ROW_H * visibleRows() + 2;
    }

    private int visibleLines() {
        return FULL_DETAIL_LINES - screen.rowsLost(DETAIL_LINE_H, FULL_DETAIL_LINES, 3);
    }

    private int detailH() {
        return FULL_DETAIL_H - (FULL_DETAIL_LINES - visibleLines()) * DETAIL_LINE_H;
    }

    /** How much shorter the window is drawn on the list page. */
    int listCut() {
        return (FULL_BUILDING_ROWS - visibleRows()) * BUILDING_ROW_H;
    }

    /** How much shorter the window is drawn on a building's page. */
    int detailCut() {
        return (FULL_DETAIL_LINES - visibleLines()) * DETAIL_LINE_H;
    }

    // ---------------------------------------------------------------------
    // The list: type buttons, search, one card per building
    // ---------------------------------------------------------------------

    private List<BuildingInstanceEntry> instances() {
        return menu.snapshot().buildingInstances();
    }

    private static String categoryLabel(BuildingInstanceEntry.Category category) {
        if (category == null) return Tr.s("screen.dynamicvillages.mayor.all", "All");
        return switch (category) {
            case HOUSE -> Tr.s("screen.dynamicvillages.mayor.houses", "Houses");
            case WORK -> Tr.s("screen.dynamicvillages.mayor.work", "Work");
            case STORAGE -> Tr.s("screen.dynamicvillages.mayor.storage", "Storage");
            case OTHER -> Tr.s("screen.dynamicvillages.mayor.other", "Other");
        };
    }

    private int categoryCount(BuildingInstanceEntry.Category category) {
        if (category == null) return instances().size();
        int count = 0;
        for (BuildingInstanceEntry entry : instances()) if (entry.category() == category) count++;
        return count;
    }

    /** The type buttons fill exactly the width of the search box; the last one takes the leftover pixels. */
    int categoryButtonW(int index) {
        int width = (innerW() - BUILDING_GAP * (CATEGORIES.length - 1)) / CATEGORIES.length;
        if (index < CATEGORIES.length - 1) return width;
        return innerW() - (CATEGORIES.length - 1) * (width + BUILDING_GAP);
    }

    private int categoryButtonX(int index) {
        return left() + CONTENT_X + 6 + index * (categoryButtonW(0) + BUILDING_GAP);
    }

    /** What a row says on its right: the problem when there is one, else beds or "Working". */
    private static String statusLabel(BuildingInstanceEntry entry) {
        if (!entry.operational()) return Tr.s("screen.dynamicvillages.mayor.not_working", "Not working");
        if (entry.missingBeds() > 0) return Tr.s("screen.dynamicvillages.mayor.bed_gone", "Bed gone");
        if (entry.crowded()) return Tr.s("screen.dynamicvillages.mayor.crowded", "Crowded");
        if (entry.hasBeds() && entry.bedsFound() == 0 && !entry.bedsOptional()) return Tr.s("screen.dynamicvillages.mayor.no_beds", "No beds");
        if (entry.hasBeds()) return Tr.s("screen.dynamicvillages.mayor.beds_count", "%1$s/%2$s beds", entry.residents().size(), entry.capacity());
        return Tr.s("screen.dynamicvillages.mayor.working", "Working");
    }

    private static String position(net.minecraft.core.BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static boolean matches(BuildingInstanceEntry entry, String query) {
        if (query.isEmpty()) return true;
        net.minecraft.core.BlockPos door = entry.door();
        StringBuilder haystack = new StringBuilder(entry.type()).append(' ')
                .append(position(door)).append(' ')
                .append(door.getX()).append(' ').append(door.getY()).append(' ').append(door.getZ())
                .append(' ').append(statusLabel(entry));
        for (BuildingInstanceEntry.Resident resident : entry.residents()) haystack.append(' ').append(resident.name());
        return haystack.toString().toLowerCase(Locale.ROOT).contains(query);
    }

    /** Indexes of the buildings the type button and the search leave in the list. */
    private List<Integer> shownBuildings() {
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        List<BuildingInstanceEntry> instances = instances();
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < instances.size(); i++) {
            BuildingInstanceEntry entry = instances.get(i);
            if ((categoryFilter == null || entry.category() == categoryFilter) && matches(entry, query)) {
                shown.add(i);
            }
        }
        return shown;
    }

    private int listMaxScroll() {
        return Math.max(0, gridRows(shownBuildings().size()) - visibleRows());
    }

    /** The list, or the open building's page. */
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (selected >= instances().size()) selected = -1;
        if (selected >= 0) renderDetails(graphics, mouseX, mouseY);
        else renderList(graphics, mouseX, mouseY);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + 6;

        for (int i = 0; i < CATEGORIES.length; i++) {
            BuildingInstanceEntry.Category category = CATEGORIES[i];
            int count = categoryCount(category);
            int bx = categoryButtonX(i);
            int by = top() + BUILDING_CHIP_Y;
            int bw = categoryButtonW(i);
            boolean on = categoryFilter == category;
            boolean hovered = count > 0 && inside(mouseX, mouseY, bx, by, bw, BUILDING_CHIP_H);
            graphics.fill(bx, by, bx + bw, by + BUILDING_CHIP_H, PANEL_BORDER);
            graphics.fill(bx + 1, by + 1, bx + bw - 1, by + BUILDING_CHIP_H - 1,
                    on ? ROW_SELECTED : hovered ? ROW_HOVER : ROW);
            if (on) graphics.fill(bx + 1, by + BUILDING_CHIP_H - 2, bx + bw - 1, by + BUILDING_CHIP_H, ACCENT);
            String label = categoryLabel(category) + " " + count;
            graphics.drawString(font, label, bx + (bw - font.width(label)) / 2, by + (BUILDING_CHIP_H - 8) / 2,
                    on ? TEXT : count > 0 ? MUTED : 0xFF5A5A5A, false);
        }

        int y = top() + BUILDING_LIST_Y;
        List<Integer> shown = shownBuildings();
        int totalRows = gridRows(shown.size());
        int maxScroll = Math.max(0, totalRows - visibleRows());
        int listW = listW(maxScroll > 0);
        int cardW = cardWidth(listW);
        drawListFrame(graphics, x, y, listW, listH());
        listScroll = Math.min(listScroll, maxScroll);

        graphics.enableScissor(x + 1, y + 1, x + listW - 1, y + listH() - 1);
        for (int row = 0; row < visibleRows(); row++) {
            for (int column = 0; column < VILLAGER_COLUMNS; column++) {
                int position = (listScroll + row) * VILLAGER_COLUMNS + column;
                if (position >= shown.size()) break;
                BuildingInstanceEntry entry = instances().get(shown.get(position));
                int cardX = cardX(x, column, cardW);
                int cardY = cardY(row);
                int cardH = cardHeight(row);
                boolean onProblem = problemButtonAt(entry, cardX, cardY, cardW, cardH, mouseX, mouseY);
                boolean hovered = !onProblem && inside(mouseX, mouseY, cardX, cardY, cardW, cardH);
                graphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, hovered ? ROW_HOVER : ROW);
                graphics.renderFakeItem(icon(entry.iconItemId()), cardX + 3, cardY + (cardH - 16) / 2);

                int textX = cardX + 22;
                int textRight = cardX + cardW - 5;
                String problem = problemLabel(entry);
                if (problem != null) {
                    // Dark red with light red text: still clearly a button, without shouting.
                    int buttonW = font.width(shownProblem(problem)) + 8;
                    int buttonX = textRight - buttonW;
                    int buttonY = cardY + (cardH - PROBLEM_BUTTON_H) / 2;
                    graphics.fill(buttonX, buttonY, buttonX + buttonW, buttonY + PROBLEM_BUTTON_H,
                            onProblem ? 0xFFE07A6E : 0xFF9A3B33);
                    graphics.fill(buttonX + 1, buttonY + 1, buttonX + buttonW - 1, buttonY + PROBLEM_BUTTON_H - 1,
                            onProblem ? 0xFF7E2B25 : 0xFF4A1C19);
                    graphics.drawString(font, shownProblem(problem), buttonX + 4, buttonY + 2,
                            onProblem ? 0xFFFFFFFF : 0xFFF2A39A, false);
                    textRight = buttonX - 4;
                } else if (entry.hasBeds()) {
                    String beds = Tr.s("screen.dynamicvillages.mayor.beds_count", "%1$s/%2$s beds", entry.residents().size(), entry.capacity());
                    graphics.drawString(font, beds, textRight - font.width(beds), cardY + (cardH - 8) / 2,
                            MUTED, false);
                    textRight -= font.width(beds) + 4;
                }
                graphics.drawString(font, trim(entry.type(), textRight - textX), textX, cardY + 2, TEXT, false);
                graphics.drawString(font, trim(position(entry.door()), textRight - textX), textX, cardY + 12,
                        DIM, false);
            }
        }
        graphics.disableScissor();

        if (instances().isEmpty()) {
            drawEmpty(graphics, x, y, listW, listH(), Tr.s("screen.dynamicvillages.mayor.no_buildings_yet", "No buildings yet"));
        } else if (shown.isEmpty()) {
            drawEmpty(graphics, x, y, listW, listH(), Tr.s("screen.dynamicvillages.mayor.no_buildings_found", "No buildings found"));
        }
        if (maxScroll > 0) {
            drawSideScrollbar(graphics, x + listW + SHOP_SCROLLBAR_GAP, y, listH(),
                    listScroll, maxScroll, visibleRows(), totalRows);
        }
    }

    /** The red button's text for a building with something wrong, or null when all is well. */
    private static String problemLabel(BuildingInstanceEntry entry) {
        if (!entry.operational()) return "Broken";
        if (entry.missingBeds() > 0) return "Bed gone";
        if (entry.crowded()) return "Crowded";
        if (entry.hasBeds() && entry.bedsFound() == 0 && !entry.bedsOptional()) return "No beds";
        return null;
    }

    /** A building's problem word ("Broken", ...) as drawn; the English stays the switch key. */
    private static String shownProblem(String problem) {
        return switch (problem) {
            case "Broken" -> Tr.s("screen.dynamicvillages.mayor.problem.broken", "Broken");
            case "Bed gone" -> Tr.s("screen.dynamicvillages.mayor.problem.bed_gone", "Bed gone");
            case "Crowded" -> Tr.s("screen.dynamicvillages.mayor.problem.crowded", "Crowded");
            case "No beds" -> Tr.s("screen.dynamicvillages.mayor.problem.no_beds", "No beds");
            default -> problem;
        };
    }

    private int cardHeight(int row) {
        return BUILDING_ROW_H - (row == visibleRows() - 1 ? 0 : 2);
    }

    private int cardY(int row) {
        return top() + BUILDING_LIST_Y + 1 + row * BUILDING_ROW_H;
    }

    private boolean problemButtonAt(BuildingInstanceEntry entry, int cardX, int cardY, int cardW, int cardH,
                                    double mouseX, double mouseY) {
        String problem = problemLabel(entry);
        if (problem == null) return false;
        int buttonW = font.width(shownProblem(problem)) + 8;
        int buttonX = cardX + cardW - 5 - buttonW;
        return inside(mouseX, mouseY, buttonX, cardY + (cardH - PROBLEM_BUTTON_H) / 2, buttonW, PROBLEM_BUTTON_H);
    }

    void renderProblemTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int[] hit = cardAt(mouseX, mouseY);
        if (hit == null || hit[1] == 0) return;
        BuildingInstanceEntry entry = instances().get(hit[0]);
        String why = switch (problemLabel(entry)) {
            case "Broken" -> Tr.s("screen.dynamicvillages.mayor.problem.broken.desc", "This building is not working");
            case "Bed gone" -> Tr.s("screen.dynamicvillages.mayor.problem.bed_gone.desc", "A villager's bed is gone");
            case "Crowded" -> Tr.s("screen.dynamicvillages.mayor.problem.crowded.desc", "Too many villagers inside");
            default -> Tr.s("screen.dynamicvillages.mayor.there_are_no_beds_in_this", "There are no beds in this house");
        };
        graphics.renderComponentTooltip(font, List.of(Component.literal(why),
                Tr.c("screen.dynamicvillages.mayor.click_to_see_what_s_wrong", "Click to see what's wrong").withStyle(net.minecraft.ChatFormatting.GRAY)),
                mouseX, mouseY);
    }

    /** The mod's usual scrollbar: a 4-wide track beside the list, not inside it. */
    private static void drawSideScrollbar(GuiGraphics graphics, int x, int y, int height,
                                          int scroll, int maxScroll, int visible, int total) {
        int thumbH = sideThumbHeight(height, visible, total);
        int thumbY = y + (height - thumbH) * scroll / maxScroll;
        graphics.fill(x, y, x + SHOP_SCROLLBAR_W, y + height, 0xFF333333);
        graphics.fill(x, thumbY, x + SHOP_SCROLLBAR_W, thumbY + thumbH, 0xFF888888);
    }

    private static int sideThumbHeight(int height, int visible, int total) {
        return Math.max(18, height * visible / Math.max(1, total));
    }

    /**
     * The card under the cursor: {index into the snapshot, 1 when on its red button else 0},
     * or null.
     */
    private int[] cardAt(double mouseX, double mouseY) {
        int x = left() + CONTENT_X + 6;
        List<Integer> shown = shownBuildings();
        int cardW = cardWidth(listW(listMaxScroll() > 0));
        for (int row = 0; row < visibleRows(); row++) {
            for (int column = 0; column < VILLAGER_COLUMNS; column++) {
                int position = (listScroll + row) * VILLAGER_COLUMNS + column;
                if (position >= shown.size()) return null;
                int cardX = cardX(x, column, cardW);
                int cardY = cardY(row);
                int cardH = cardHeight(row);
                if (!inside(mouseX, mouseY, cardX, cardY, cardW, cardH)) continue;
                int index = shown.get(position);
                boolean button = problemButtonAt(instances().get(index), cardX, cardY, cardW, cardH, mouseX, mouseY);
                return new int[]{index, button ? 1 : 0};
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // One building's page
    // ---------------------------------------------------------------------

    /**
     * "Manage beds" for a house or the Town Hall (the Mayor's bed can be picked, 10-24), "Inspect beds" for a
     * Barracks, "Manage storage" for a storage room, "Manage mine" for a mine, "Manage Butchery" for a Butchery
     * and "Manage Ranch" for a Ranch (10-24), else null.
     */
    private static String manageLabel(BuildingInstanceEntry entry) {
        if (entry.barracks()) return Tr.s("screen.dynamicvillages.mayor.inspect_beds", "Inspect beds");
        if (entry.hasBeds() || isTownHall(entry)) return Tr.s("screen.dynamicvillages.mayor.manage_beds", "Manage beds");
        if (entry.category() == BuildingInstanceEntry.Category.STORAGE) return Tr.s("screen.dynamicvillages.mayor.manage_storage", "Manage storage");
        if (isMine(entry)) return Tr.s("screen.dynamicvillages.mayor.manage_mine", "Manage mine");
        if (isButchery(entry)) return Tr.s("screen.dynamicvillages.mayor.manage_butchery", "Manage Butchery");
        if (isRanch(entry)) return Tr.s("screen.dynamicvillages.mayor.manage_ranch", "Manage Ranch");
        return null;
    }

    /**
     * Village claims round 7a: the storage room is the village's chests, so its Manage is greyed for a Visitor or a
     * Hostile player (the server refuses it anyway, round 6). Bed, mine, Butchery and Ranch windows open to look.
     */
    private boolean manageRefused(BuildingInstanceEntry entry) {
        return !entry.barracks() && !entry.hasBeds() && !isTownHall(entry)
                && entry.category() == BuildingInstanceEntry.Category.STORAGE
                && !MayorStatsScreen.may(menu, ClaimLock.Act.USE);
    }

    /** By its icon: the type name is in the server's language, so it may not match this game's. */
    private static boolean isMine(BuildingInstanceEntry entry) {
        return (DynamicVillages.MODID + ":mine").equals(entry.iconItemId());
    }

    /** By its icon, like {@link #isMine}. */
    private static boolean isButchery(BuildingInstanceEntry entry) {
        return (DynamicVillages.MODID + ":butchery").equals(entry.iconItemId());
    }

    /** By its icon, like {@link #isMine}. */
    private static boolean isRanch(BuildingInstanceEntry entry) {
        return (DynamicVillages.MODID + ":ranch").equals(entry.iconItemId());
    }

    /** By its icon, like {@link #isMine}. Its beds open the bed screen, though it has no capacity of its own. */
    private static boolean isTownHall(BuildingInstanceEntry entry) {
        return (DynamicVillages.MODID + ":town_hall").equals(entry.iconItemId());
    }

    private int manageWidth(String label) {
        return font.width(label) + 20;
    }

    private int manageX(String label) {
        return left() + CONTENT_X + 6 + innerW() - 72 - 4 - manageWidth(label);
    }

    /** One building's page: Back, Manage beds and Locate on top, its facts in the card. */
    private void renderDetails(GuiGraphics graphics, int mouseX, int mouseY) {
        List<BuildingInstanceEntry> instances = instances();
        if (selected < 0 || selected >= instances.size()) {
            selected = -1;
            return;
        }
        BuildingInstanceEntry entry = instances.get(selected);
        int x = left() + CONTENT_X + 6;
        int y = top() + INFO_LIST_Y;
        int width = innerW();

        // Same buttons as a villager's page.
        boolean backHovered = inside(mouseX, mouseY, x, y, 66, SUBNAV_H);
        graphics.fill(x, y, x + 66, y + SUBNAV_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + 65, y + SUBNAV_H - 1, backHovered ? ROW_HOVER : ROW);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.back", "< Back"), x + 10, y + 5, TEXT, false);
        String manage = manageLabel(entry);
        if (manage != null) {
            int manageX = manageX(manage);
            int manageW = manageWidth(manage);
            boolean manageHovered = inside(mouseX, mouseY, manageX, y, manageW, SUBNAV_H);
            boolean refused = manageRefused(entry);
            graphics.fill(manageX, y, manageX + manageW, y + SUBNAV_H, PANEL_BORDER);
            graphics.fill(manageX + 1, y + 1, manageX + manageW - 1, y + SUBNAV_H - 1,
                    refused ? ROW : manageHovered ? ROW_HOVER : ROW_SELECTED);
            graphics.drawString(font, manage, manageX + (manageW - font.width(manage)) / 2, y + 5,
                    refused ? MUTED : TEXT, false);
            if (refused && manageHovered) {
                screen.setDeferredTooltip(font.split(Component.literal(Tr.s(
                        "screen.dynamicvillages.mayor.storage_not_trusted",
                        "Only people %s trusts may open the storage room.", MayorStatsScreen.ownerName(menu))), 200));
            }
        }
        int locateX = x + width - 72;
        boolean locateHovered = inside(mouseX, mouseY, locateX, y, 72, SUBNAV_H);
        graphics.fill(locateX, y, locateX + 72, y + SUBNAV_H, PANEL_BORDER);
        graphics.fill(locateX + 1, y + 1, locateX + 71, y + SUBNAV_H - 1,
                locateHovered ? ROW_HOVER : ROW_SELECTED);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.locate", "Locate"), locateX + (72 - font.width(Tr.s("screen.dynamicvillages.mayor.locate", "Locate"))) / 2, y + 5, TEXT, false);

        int cardY = top() + DETAIL_Y;
        int cardW = detailCardW();
        drawListFrame(graphics, x, cardY, cardW, detailH());
        int textX = x + 9;
        int textW = detailTextW();
        // Status and room tiles line up on the right; hover one to learn what it means.
        List<HeaderStat> stats = headerStats(entry);
        int[] statXs = headerStatXs(stats, textX + textW);
        int statsLeft = stats.isEmpty() ? textX + textW : statXs[0];
        for (int i = 0; i < stats.size(); i++) {
            HeaderStat stat = stats.get(i);
            boolean statHovered = inside(mouseX, mouseY, statXs[i] - 2, cardY + 5, headerStatW(stat) + 4, 24);
            graphics.drawString(font, stat.label(), statXs[i], cardY + 7, statHovered ? TEXT : MUTED, false);
            // A dotted underline says "hover me".
            int labelW = font.width(stat.label());
            for (int dot = 0; dot < labelW; dot += 2) {
                graphics.fill(statXs[i] + dot, cardY + 16, statXs[i] + dot + 1, cardY + 17, statHovered ? TEXT : 0xFF6A6A6A);
            }
            graphics.drawString(font, stat.value(), statXs[i], cardY + 19, stat.valueColor(), false);
        }
        int nameW = statsLeft - 10 - (textX + 24);
        graphics.renderFakeItem(icon(entry.iconItemId()), textX, cardY + 7);
        graphics.drawString(font, trim(entry.type(), nameW), textX + 24, cardY + 7, TEXT, false);
        graphics.drawString(font, trim(position(entry.door()), nameW), textX + 24, cardY + 19, MUTED, false);
        graphics.fill(textX, cardY + 31, textX + textW, cardY + 32, PANEL_BORDER);

        List<DetailLine> lines = facts(entry, textW);
        int maxScroll = Math.max(0, lines.size() - visibleLines());
        detailScroll = Math.min(detailScroll, maxScroll);
        int bodyY = cardY + DETAIL_BODY_OFFSET;
        for (int i = 0; i < visibleLines(); i++) {
            int index = detailScroll + i;
            if (index >= lines.size()) break;
            DetailLine line = lines.get(index);
            int lineY = bodyY + i * DETAIL_LINE_H;
            if (line.villager() >= 0 && inside(mouseX, mouseY, textX - 2, lineY - 2, textW + 4, DETAIL_LINE_H)) {
                graphics.fill(textX - 3, lineY - 2, textX + textW + 2, lineY + 9, ROW_HOVER);
            }
            int indent = 0;
            if (line.bar() != 0) {
                graphics.fill(textX, lineY - 2, textX + 2, lineY + 9, line.bar());
                indent = 6;
            }
            graphics.drawString(font, line.text(), textX + indent, lineY, line.color(), false);
            if (!line.value().isEmpty()) {
                graphics.drawString(font, line.value(), textX + textW - font.width(line.value()), lineY,
                        line.valueColor(), false);
            }
        }
        if (maxScroll > 0) {
            drawSideScrollbar(graphics, x + cardW + SHOP_SCROLLBAR_GAP, cardY, detailH(),
                    detailScroll, maxScroll, visibleLines(), lines.size());
        }
    }

    private List<HeaderStat> headerStats(BuildingInstanceEntry entry) {
        List<HeaderStat> stats = new ArrayList<>();
        stats.add(entry.operational()
                ? new HeaderStat(Tr.s("screen.dynamicvillages.mayor.status", "Status"), Tr.s("screen.dynamicvillages.mayor.working", "Working"), ACCENT,
                        Tr.s("screen.dynamicvillages.mayor.working.desc", "Villagers can use this building."))
                : new HeaderStat(Tr.s("screen.dynamicvillages.mayor.status", "Status"), Tr.s("screen.dynamicvillages.mayor.not_working", "Not working"), DENIED,
                        Tr.s("screen.dynamicvillages.mayor.not_working.desc", "Villagers can't use this building. The reason is written below.")));
        if (entry.roomTiles() > 0) {
            stats.add(new HeaderStat(Tr.s("screen.dynamicvillages.mayor.room_tiles", "Room tiles"), Integer.toString(entry.roomTiles()), TEXT,
                    Tr.s("screen.dynamicvillages.mayor.room_tiles.desc", "Blocks of floor inside the room that villagers can walk on.")));
        }
        return stats;
    }

    private int headerStatW(HeaderStat stat) {
        return Math.max(font.width(stat.label()), font.width(stat.value()));
    }

    /** Left edge of each stat, laid out from {@code right} leftwards so the last one ends there. */
    private int[] headerStatXs(List<HeaderStat> stats, int right) {
        int[] xs = new int[stats.size()];
        int x = right;
        for (int i = stats.size() - 1; i >= 0; i--) {
            x -= headerStatW(stats.get(i));
            xs[i] = x;
            x -= HEADER_STAT_GAP;
        }
        return xs;
    }

    void renderHeaderStatTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        List<BuildingInstanceEntry> instances = instances();
        if (selected < 0 || selected >= instances.size()) return;
        List<HeaderStat> stats = headerStats(instances.get(selected));
        int textX = left() + CONTENT_X + 6 + 9;
        int[] xs = headerStatXs(stats, textX + detailTextW());
        int cardY = top() + DETAIL_Y;
        for (int i = 0; i < stats.size(); i++) {
            if (inside(mouseX, mouseY, xs[i] - 2, cardY + 5, headerStatW(stats.get(i)) + 4, 24)) {
                // Each paragraph wraps on its own; an empty one leaves a blank line.
                List<FormattedCharSequence> lines = new ArrayList<>();
                for (String paragraph : stats.get(i).tip()) {
                    if (paragraph.isEmpty()) lines.add(FormattedCharSequence.EMPTY);
                    else lines.addAll(font.split(Component.literal(paragraph), TIP_WRAP_W));
                }
                graphics.renderTooltip(font, lines, mouseX, mouseY);
                return;
            }
        }
    }

    /** Whether the chosen building's facts need the scrollbar, measured at the narrow width. */
    private boolean detailScrolls() {
        if (selected < 0 || selected >= instances().size()) return false;
        return facts(instances().get(selected), listW(true) - 18).size()
                > visibleLines();
    }

    /** The building card, like the lists, takes the scrollbar's place when it has none. */
    private int detailCardW() {
        return listW(detailScrolls());
    }

    private int detailTextW() {
        return detailCardW() - 18;
    }

    /**
     * The facts about one building, each said once, in words a new player
     * understands: whether it works, how big it is, who sleeps there, extras.
     * Wrapped to {@code width}; the card shows {@link #visibleLines()} of them at a time.
     */
    private List<DetailLine> facts(BuildingInstanceEntry entry, int width) {
        List<DetailLine> lines = new ArrayList<>();
        boolean post = entry.id().equals(net.minecraft.Util.NIL_UUID);

        // Status and room tiles sit in the card's header; the facts below are the rest.
        switch (entry.status()) {
            case NO_ROOM -> addWrapped(lines, Tr.s("screen.dynamicvillages.mayor.no_room", "Not working: no room was found behind the door. It needs walls, a roof and a door."),
                    DENIED, width, DENIED);
            case UNREACHABLE -> addWrapped(lines, Tr.s("screen.dynamicvillages.mayor.unreachable", "Not working: villagers can't walk to its door from the village."),
                    DENIED, width, DENIED);
            case OPERATIONAL -> {
                if (post) addWrapped(lines, Tr.s("screen.dynamicvillages.mayor.guards_patrol_within_12_blocks_of", "Guards patrol within 12 blocks of this post."), MUTED, width, 0);
            }
        }
        if (entry.crowded()) {
            gap(lines);
            addWrapped(lines, entry.crowdedLiving()
                    ? CrowdingBalance.crampedLine(entry.crowdedCount())
                    : CrowdingBalance.busyLine(entry.crowdedCount()), DENIED, width, DENIED);
            addWrapped(lines, CrowdingBalance.fixLine(entry.lowCeilingTiles()), MUTED, width, DENIED);
        }

        if (entry.sellBoxGoods() >= 0) {
            gap(lines);
            lines.add(row(Tr.s("screen.dynamicvillages.mayor.sell_box", "Sell box"), entry.sellBoxGoods() == 0 ? Tr.s("screen.dynamicvillages.mayor.sell_box.empty", "Empty")
                    : Tr.s("screen.dynamicvillages.mayor.sell_box.waiting", "%s goods waiting", entry.sellBoxGoods()), entry.sellBoxGoods() == 0 ? MUTED : TEXT));
            addWrapped(lines, Tr.s("screen.dynamicvillages.mayor.sell_box.desc", "Village goods put in the stall's barrel are sold to the merchant by your tradesman, on top of the surplus it sells on its own."),
                    MUTED, width, 0);
        }

        if (entry.barracks() && entry.operational()) {
            gap(lines);
            boolean none = entry.captain().isEmpty();
            lines.add(row(Tr.s("screen.dynamicvillages.mayor.captain", "Captain"), none ? Tr.s("screen.dynamicvillages.mayor.captain.none", "None") : entry.captain(), none ? GOLD : TEXT));
            addWrapped(lines, none
                    ? Tr.s("screen.dynamicvillages.mayor.captain.none.desc", "Give a Captain token to a guard to promote it (it keeps its guard level), or to a jobless villager. The Mayor sells one while this Barracks has no captain.")
                    : Tr.s("screen.dynamicvillages.mayor.captain.desc", "Leads the barracks' defenders."), MUTED, width, 0);
        }

        if (!entry.info().isEmpty() && entry.operational()) {
            gap(lines);
            for (String info : entry.info()) {
                int tab = info.indexOf('	');
                String label = tab >= 0 ? info.substring(0, tab) : info;
                String value = tab >= 0 ? info.substring(tab + 1) : "";
                boolean problem = "None".equals(value) || value.startsWith("Needs ")
                        || value.startsWith("Too far") || value.startsWith("No ");
                lines.add(row(label, value, problem ? GOLD : TEXT));
            }
        }

        if (entry.hasBeds()) {
            gap(lines);
            lines.add(row(Tr.s("screen.dynamicvillages.mayor.beds_used", "Beds used"), Tr.s("screen.dynamicvillages.mayor.beds_used.value", "%1$s of %2$s", entry.residents().size(), entry.capacity()), TEXT));
            if (entry.bedsFound() == 0 && entry.bedsOptional()) {
                addWrapped(lines, Tr.s("screen.dynamicvillages.mayor.beds_optional", "No beds yet. Beds are optional: until there are some, its mages sleep in a Barracks or a house."),
                        MUTED, width, 0);
            } else if (entry.bedsFound() == 0) {
                addWrapped(lines, Tr.s("screen.dynamicvillages.mayor.there_are_no_beds_in_the", "There are no beds in the room yet."), GOLD, width, 0);
            } else if (entry.bedsFound() < entry.capacity() && !entry.bedsOptional()) {
                addWrapped(lines, Tr.s("screen.dynamicvillages.mayor.beds_placed", "Only %1$s of %2$s beds are placed.", entry.bedsFound(), entry.capacity()),
                        GOLD, width, 0);
            }
            if (entry.residents().isEmpty()) {
                addWrapped(lines, Tr.s("screen.dynamicvillages.mayor.nobody_sleeps_here_yet", "Nobody sleeps here yet."), MUTED, width, 0);
            }
            for (BuildingInstanceEntry.Resident resident : entry.residents()) {
                int villager = villagerIndex(resident.id());
                String value = resident.missing() ? Tr.s("screen.dynamicvillages.mayor.resident.bed_gone", "bed is gone") : villager >= 0 ? ">" : "";
                String name = trim(resident.name(), width - font.width(value) - 6);
                lines.add(new DetailLine(Component.literal(name).getVisualOrderText(), TEXT,
                        value, resident.missing() ? DENIED : ACCENT, villager, 0));
            }
        }

        // What the building needs, apart from what is only nice to have: the
        // lines marked "(optional)". Everything used to be listed as optional
        // extras, a watchtower's high floor and ladder included.
        List<String> needs = new ArrayList<>();
        List<String> niceToHave = new ArrayList<>();
        for (String feature : acceptedFeatures(entry.type())) {
            if (feature.contains("(optional)")) niceToHave.add(feature.replace(" (optional)", ""));
            else if (feature.contains("(optional; ")) niceToHave.add(feature.replace("(optional; ", "("));
            else needs.add(feature);
        }
        if (!needs.isEmpty()) {
            gap(lines);
            addWrapped(lines, Tr.s("screen.dynamicvillages.mayor.needs", "Needs"), TEXT, width, 0);
            for (String feature : needs) addWrapped(lines, "- " + feature, MUTED, width, 0);
        }
        if (!niceToHave.isEmpty()) {
            gap(lines);
            addWrapped(lines, Tr.s("screen.dynamicvillages.mayor.nice_to_have", "Nice to have"), TEXT, width, 0);
            for (String feature : niceToHave) addWrapped(lines, "- " + feature, MUTED, width, 0);
        }
        return lines;
    }

    private DetailLine row(String label, String value, int valueColor) {
        return new DetailLine(Component.literal(label).getVisualOrderText(), MUTED, value, valueColor, -1, 0);
    }

    /** An empty line between sections, but none above the first. */
    private void gap(List<DetailLine> lines) {
        if (!lines.isEmpty()) lines.add(new DetailLine(FormattedCharSequence.EMPTY, MUTED, "", 0, -1, 0));
    }

    private void addWrapped(List<DetailLine> lines, String text, int color, int width, int bar) {
        for (FormattedCharSequence line : font.split(Component.literal(text), bar != 0 ? width - 6 : width)) {
            lines.add(new DetailLine(line, color, "", 0, -1, bar));
        }
    }

    private List<String> acceptedFeatures(String type) {
        for (BuildingStatsEntry group : menu.snapshot().buildings()) {
            if (group.name().equalsIgnoreCase(type)) return group.acceptedFeatures();
        }
        return List.of();
    }

    private int villagerIndex(java.util.UUID id) {
        List<VillagerStatsEntry> villagers = menu.snapshot().villagers();
        for (int i = 0; i < villagers.size(); i++) if (villagers.get(i).id().equals(id)) return i;
        return -1;
    }

    /** The house a villager has a bed claim in, as an index into the building list, or -1. */
    int homeOf(java.util.UUID villager) {
        List<BuildingInstanceEntry> instances = instances();
        for (int i = 0; i < instances.size(); i++) {
            for (BuildingInstanceEntry.Resident resident : instances.get(i).residents()) {
                if (resident.id().equals(villager)) return i;
            }
        }
        return -1;
    }

    private int detailLineCount() {
        if (selected < 0 || selected >= instances().size()) return 0;
        return facts(instances().get(selected), detailTextW()).size();
    }

    // ---------------------------------------------------------------------
    // Clicks (left button only; the Info tab checks)
    // ---------------------------------------------------------------------

    boolean mouseClicked(double mouseX, double mouseY) {
        return selected >= 0 ? clickDetails(mouseX, mouseY) : clickList(mouseX, mouseY);
    }

    private boolean clickList(double mouseX, double mouseY) {
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (inside(mouseX, mouseY, categoryButtonX(i), top() + BUILDING_CHIP_Y,
                    categoryButtonW(i), BUILDING_CHIP_H)) {
                if (categoryCount(CATEGORIES[i]) > 0) {
                    categoryFilter = CATEGORIES[i];
                    listScroll = 0;
                }
                return true;
            }
        }
        int[] hit = cardAt(mouseX, mouseY);
        if (hit == null) return false;
        select(hit[0]);
        if (hit[1] == 1) {
            // The red button opens the page at what is wrong.
            List<DetailLine> lines = facts(instances().get(hit[0]), detailTextW());
            for (int i = 0; i < lines.size(); i++) {
                DetailLine line = lines.get(i);
                if (line.bar() != 0 || line.valueColor() == DENIED || line.color() == GOLD) {
                    detailScroll = Math.max(0, Math.min(i - 1, lines.size() - visibleLines()));
                    break;
                }
            }
        }
        return true;
    }

    private boolean clickDetails(double mouseX, double mouseY) {
        List<BuildingInstanceEntry> instances = instances();
        if (selected < 0 || selected >= instances.size()) return false;
        BuildingInstanceEntry entry = instances.get(selected);
        int x = left() + CONTENT_X + 6;
        int y = top() + INFO_LIST_Y;
        if (inside(mouseX, mouseY, x, y, 66, SUBNAV_H)) {
            selected = -1;
            return true;
        }
        String manage = manageLabel(entry);
        if (manage != null && inside(mouseX, mouseY, manageX(manage), y, manageWidth(manage), SUBNAV_H)) {
            if (manageRefused(entry)) return true;
            PacketDistributor.sendToServer(entry.hasBeds() || isTownHall(entry)
                    ? new MayorBuildingPayloads.OpenHouse(menu.mayorId(), entry.id())
                    : isMine(entry) ? new MayorBuildingPayloads.OpenMine(menu.mayorId(), entry.id())
                    : isButchery(entry) ? new MayorBuildingPayloads.OpenButchery(menu.mayorId(), entry.id())
                    : isRanch(entry) ? new MayorBuildingPayloads.OpenRanch(menu.mayorId(), entry.id())
                    : new MayorBuildingPayloads.OpenStorage(menu.mayorId(), entry.id()));
            return true;
        }
        if (inside(mouseX, mouseY, x + innerW() - 72, y, 72, SUBNAV_H)) {
            PacketDistributor.sendToServer(new MayorBuildingPayloads.Locate(menu.mayorId(), entry.id(), entry.door()));
            return true;
        }
        // A resident's line opens that villager.
        int textX = x + 9;
        int textW = detailTextW();
        int bodyY = top() + DETAIL_Y + DETAIL_BODY_OFFSET;
        if (inside(mouseX, mouseY, textX - 2, bodyY - 2, textW + 4, visibleLines() * DETAIL_LINE_H)) {
            List<DetailLine> lines = facts(entry, textW);
            int index = detailScroll + ((int) mouseY - bodyY + 2) / DETAIL_LINE_H;
            if (index >= 0 && index < lines.size() && lines.get(index).villager() >= 0) {
                infoTab.showVillager(lines.get(index).villager());
                return true;
            }
        }
        return false;
    }
}
