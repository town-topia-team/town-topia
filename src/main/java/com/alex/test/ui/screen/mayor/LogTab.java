package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import com.alex.test.client.ClientVillageLog;
import com.alex.test.network.VillageLogPayloads;
import com.alex.test.village.log.VillageLog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * The Village Log tab: what happened in this village, newest first, one row each (icon, line, day), with the
 * details on hover, and filter buttons on top. The list comes from the server ({@link ClientVillageLog}): asked
 * when the tab opens and every 5 s while it shows; the server only answers when something changed.
 */
final class LogTab extends MayorPart {

    /** Which rows show: all of them, or one group; each {@link VillageLog.Kind} is in exactly one ({@link #group}). */
    enum Filter { ALL, DEATHS, VILLAGERS, RAIDS, BUILDINGS }

    /** The filter buttons, where other tabs have their section buttons, as wide as the list and its bar. */
    private static final int FILTER_Y = INFO_SELECTOR_Y;
    private static final int FILTER_GAP = 4;
    private static final int LOG_LIST_Y = 88;
    private static final int LOG_ROW_H = 28;
    private static final int LOG_ROW_STEP = 30;
    private static final int FULL_LOG_ROWS = 6;
    /** Placed like the Shop's list, 6 inside the body; sized like it too, its bar beside it ({@link #logW}). */
    private static final int LOG_X = CONTENT_X + 6;
    private static final int ASK_TICKS = 100;

    private Filter filter = Filter.ALL;
    private int listScroll;
    private int askTicks;

    final ScrollList list = new ScrollList() {
        @Override public boolean shown() { return screen.tab() == MayorStatsScreen.Tab.LOG; }
        @Override public int x() { return left() + LOG_X; }
        @Override public int y() { return top() + LOG_LIST_Y; }
        @Override public int width() { return logW(); }
        @Override public int height() { return listH(); }
        @Override public boolean sideBar() { return true; }
        @Override public int scroll() { return listScroll; }
        @Override public void setScroll(int value) { listScroll = value; }
        @Override public int maxScroll() { return Math.max(0, entryCount() - visibleRows()); }
        @Override public int thumbHeight() { return thumbHeightFor(entryCount()); }
    };

    LogTab(MayorStatsScreen screen) {
        super(screen);
    }

    /** The list, its scrollbar beside it (322 in a 356 window). */
    private int logW() {
        return listW(true);
    }

    /** Every filter button but the last: an even share of the row (62 in a 356 window). */
    private int evenFilterW() {
        int filters = Filter.values().length;
        return (innerW() - FILTER_GAP * (filters - 1)) / filters;
    }

    /** The tab was clicked open: ask at once (the server stays quiet when the window already has the newest list). */
    void opened() {
        askTicks = 0;
        ask();
    }

    /** Every client tick while this tab shows. */
    void tick() {
        if (++askTicks >= ASK_TICKS) {
            askTicks = 0;
            ask();
        }
    }

    private void ask() {
        int mayor = screen.mayorId();
        PacketDistributor.sendToServer(new VillageLogPayloads.RequestLog(mayor, ClientVillageLog.version(mayor)));
    }

    // ---------------------------------------------------------------------
    // Fitting small screens
    // ---------------------------------------------------------------------

    private int visibleRows() {
        return FULL_LOG_ROWS - screen.rowsLost(LOG_ROW_STEP, FULL_LOG_ROWS, 2);
    }

    private int listH() {
        return LOG_ROW_STEP * visibleRows();
    }

    /** How much shorter the window is drawn on this tab. */
    int listCut() {
        return (FULL_LOG_ROWS - visibleRows()) * LOG_ROW_STEP;
    }

    private int thumbHeightFor(int count) {
        return Math.max(18, listH() * visibleRows() / Math.max(1, count));
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private int entryCount() {
        return filteredEntries().size();
    }

    /** The rows the filter lets through, newest first. */
    private List<VillageLog.Entry> filteredEntries() {
        List<VillageLog.Entry> all = ClientVillageLog.entries(screen.mayorId());
        List<VillageLog.Entry> out = new ArrayList<>(all.size());
        for (int i = all.size() - 1; i >= 0; i--) {
            if (filter == Filter.ALL || group(all.get(i).kind()) == filter) out.add(all.get(i));
        }
        return out;
    }

    /** The filter each kind of row is under. */
    private static Filter group(VillageLog.Kind kind) {
        return switch (kind) {
            case DEATH -> Filter.DEATHS;
            case BIRTH, GREW_UP, JOINED, TAKEN, JOB -> Filter.VILLAGERS;
            case RAID, RAID_WON, RAID_LOST, RAID_LEFT -> Filter.RAIDS;
            case BUILDING, BUILDING_LOST, TOWN_HALL -> Filter.BUILDINGS;
        };
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left() + LOG_X;
        int y = top();
        Filter hoveredFilter = null;
        for (Filter target : Filter.values()) {
            int buttonX = x + filterX(target);
            drawFilterButton(graphics, target, buttonX, y + FILTER_Y, mouseX, mouseY);
            if (inside(mouseX, mouseY, buttonX, y + FILTER_Y, filterW(target), INFO_SELECTOR_H)) hoveredFilter = target;
        }

        int listY = y + LOG_LIST_Y;
        drawListFrame(graphics, x, listY, logW(), listH());
        if (hoveredFilter != null) screen.setDeferredTooltip(tooltipLines(filterTip(hoveredFilter)));
        if (!ClientVillageLog.isFor(screen.mayorId())) {
            drawEmpty(graphics, x, listY, logW(), listH(), Tr.s("screen.dynamicvillages.mayor.log.asking", "Asking the Mayor..."));
            return;
        }
        List<VillageLog.Entry> entries = filteredEntries();
        if (entries.isEmpty()) {
            drawEmpty(graphics, x, listY, logW(), listH(), filter == Filter.ALL
                    ? Tr.s("screen.dynamicvillages.mayor.log.empty", "Nothing has happened here yet.")
                    : Tr.s("screen.dynamicvillages.mayor.log.empty_filter", "Nothing like this has happened here yet."));
            return;
        }
        int maxScroll = Math.max(0, entries.size() - visibleRows());
        listScroll = Math.min(listScroll, maxScroll);

        VillageLog.Entry hovered = null;
        int rowX = x + SHOP_CARD_INSET;
        int rowW = logW() - SHOP_CARD_INSET * 2;
        graphics.enableScissor(x + 1, listY + 1, x + logW() - 1, listY + listH() - 1);
        for (int row = 0; row < visibleRows(); row++) {
            int index = listScroll + row;
            if (index >= entries.size()) break;
            int rowY = listY + 1 + row * LOG_ROW_STEP;
            boolean over = inside(mouseX, mouseY, rowX, rowY, rowW, LOG_ROW_H);
            renderRow(graphics, entries.get(index), rowX, rowY, rowW, over);
            if (over) hovered = entries.get(index);
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int barX = x + logW() + SHOP_SCROLLBAR_GAP;
            int thumbH = thumbHeightFor(entries.size());
            int thumbY = listY + (listH() - thumbH) * listScroll / maxScroll;
            graphics.fill(barX, listY, barX + SHOP_SCROLLBAR_W, listY + listH(), 0xFF333333);
            graphics.fill(barX, thumbY, barX + SHOP_SCROLLBAR_W, thumbY + thumbH, 0xFF888888);
        }
        if (hovered != null) screen.setDeferredTooltip(tooltipLines(details(hovered)));
    }

    private void renderRow(GuiGraphics graphics, VillageLog.Entry entry, int x, int y, int width, boolean hovered) {
        graphics.fill(x, y, x + width, y + LOG_ROW_H, hovered ? ROW_HOVER : ROW);
        graphics.renderFakeItem(new ItemStack(icon(entry.kind())), x + 5, y + 6);
        graphics.drawString(font, trim(entry.line().getString(), width - 30), x + 26, y + 5, TEXT, false);
        graphics.drawString(font, when(entry.dayTime()), x + 26, y + 16, MUTED, false);
    }

    private static Item icon(VillageLog.Kind kind) {
        return switch (kind) {
            case DEATH -> Items.SKELETON_SKULL;
            case BIRTH -> Items.CAKE;
            case GREW_UP -> Items.BOOK;
            case JOINED -> Items.OAK_DOOR;
            case TAKEN -> Items.LEAD;
            case JOB -> Items.IRON_PICKAXE;
            case RAID -> Items.CROSSBOW;
            case RAID_WON -> Items.TOTEM_OF_UNDYING;
            case RAID_LOST -> Items.WITHER_SKELETON_SKULL;
            case RAID_LEFT -> Items.WHITE_BANNER;
            case BUILDING -> Items.BRICKS;
            case BUILDING_LOST -> Items.BARRIER;
            case TOWN_HALL -> Items.BELL;
        };
    }

    // ---------------------------------------------------------------------
    // Filter buttons
    // ---------------------------------------------------------------------

    /** A filter button's left edge from the list's. Drawing, hover and clicks all use it. */
    private int filterX(Filter target) {
        return target.ordinal() * (evenFilterW() + FILTER_GAP);
    }

    /** A filter button's width; the last one takes what the even split leaves, so the row ends with the bar. */
    private int filterW(Filter target) {
        return target.ordinal() == Filter.values().length - 1 ? innerW() - filterX(target) : evenFilterW();
    }

    private static String filterLabel(Filter target) {
        return switch (target) {
            case ALL -> Tr.s("screen.dynamicvillages.mayor.log.filter.all", "All");
            case DEATHS -> Tr.s("screen.dynamicvillages.mayor.log.filter.deaths", "Deaths");
            case VILLAGERS -> Tr.s("screen.dynamicvillages.mayor.log.filter.villagers", "Villagers");
            case RAIDS -> Tr.s("screen.dynamicvillages.mayor.log.filter.raids", "Raids");
            case BUILDINGS -> Tr.s("screen.dynamicvillages.mayor.log.filter.buildings", "Buildings");
        };
    }

    private static Item filterIcon(Filter target) {
        return switch (target) {
            case ALL -> Items.LECTERN;
            case DEATHS -> Items.SKELETON_SKULL;
            case VILLAGERS -> Items.CAKE;
            case RAIDS -> Items.CROSSBOW;
            case BUILDINGS -> Items.BRICKS;
        };
    }

    private static Component[] filterTip(Filter target) {
        return switch (target) {
            case ALL -> linesOf(Tr.s("screen.dynamicvillages.mayor.log.filter.all_tip",
                    "Everything that happened here, newest first.\nThe village keeps the last %s lines.",
                    VillageLog.MAX_ENTRIES));
            case DEATHS -> linesOf(Tr.s("screen.dynamicvillages.mayor.log.filter.deaths_tip",
                    "Villagers, visitors and the Mayor\nwho died."));
            case VILLAGERS -> linesOf(Tr.s("screen.dynamicvillages.mayor.log.filter.villagers_tip",
                    "Babies born, children growing up,\nnewcomers, new jobs from a token,\nand villagers carried off by kidnappers."));
            case RAIDS -> linesOf(Tr.s("screen.dynamicvillages.mayor.log.filter.raids_tip",
                    "Raids that came, and how each one ended.\nHover an ending to see how it went."));
            case BUILDINGS -> linesOf(Tr.s("screen.dynamicvillages.mayor.log.filter.buildings_tip",
                    "Buildings that started or stopped working,\nbuildings taken away, and the Town Hall:\nthe village founded, paused or back."));
        };
    }

    /**
     * Whether every button has room for its icon beside its whole name. Five buttons in the list's width
     * do not in English ("Buildings"), so the row is then names only: all buttons alike, never a few with icons.
     */
    private boolean filterIconsFit() {
        for (Filter target : Filter.values()) {
            if (4 + 16 + 4 + font.width(filterLabel(target)) + 4 > filterW(target)) return false;
        }
        return true;
    }

    /** Styled like the Shop's section buttons: icon (when all fit) and name centred, the chosen one underlined. */
    private void drawFilterButton(GuiGraphics graphics, Filter target, int x, int y, int mouseX, int mouseY) {
        boolean selected = filter == target;
        int w = filterW(target);
        boolean hovered = inside(mouseX, mouseY, x, y, w, INFO_SELECTOR_H);
        graphics.fill(x, y, x + w, y + INFO_SELECTOR_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + INFO_SELECTOR_H - 1,
                selected ? ROW_SELECTED : hovered ? ROW_HOVER : ROW);
        if (selected) graphics.fill(x + 1, y + INFO_SELECTOR_H - 2, x + w - 1, y + INFO_SELECTOR_H, ACCENT);
        int iconW = filterIconsFit() ? 16 + 4 : 0;
        String label = trim(filterLabel(target), w - 4 - iconW - 4);
        int contentX = x + (w - (iconW + font.width(label))) / 2;
        if (iconW > 0) graphics.renderFakeItem(new ItemStack(filterIcon(target)), contentX, y + 4);
        graphics.drawString(font, label, contentX + iconW, y + 8, selected ? TEXT : MUTED, false);
    }

    /** "Day 41, evening": the day as the world counts it, and a rough time of day. */
    private static String when(long dayTime) {
        long day = Math.floorDiv(dayTime, 24000L) + 1;
        long time = Math.floorMod(dayTime, 24000L);
        // The day starts at 6:00: 0 is sunrise, 6000 noon, 12000 sunset, 18000 midnight.
        String part = time < 6000 ? Tr.s("screen.dynamicvillages.mayor.log.morning", "morning")
                : time < 12000 ? Tr.s("screen.dynamicvillages.mayor.log.afternoon", "afternoon")
                : time < 14000 ? Tr.s("screen.dynamicvillages.mayor.log.evening", "evening")
                : Tr.s("screen.dynamicvillages.mayor.log.night", "night");
        return Tr.s("screen.dynamicvillages.mayor.log.when", "Day %s, %s", day, part);
    }

    /** The hover: the whole line, the detail (for a death, the game's own death line), when and where. */
    private static Component[] details(VillageLog.Entry entry) {
        List<Component> lines = new ArrayList<>();
        lines.add(entry.line());
        Component detail = entry.detail();
        if (detail != null) lines.add(detail.copy().withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(Tr.s("screen.dynamicvillages.mayor.log.when_where", "%s, at %s %s %s",
                when(entry.dayTime()), entry.pos().getX(), entry.pos().getY(), entry.pos().getZ()))
                .withStyle(ChatFormatting.DARK_GRAY));
        return lines.toArray(Component[]::new);
    }

    // ---------------------------------------------------------------------
    // Clicks
    // ---------------------------------------------------------------------

    /** A filter button picks its rows (from the top of the list); a click on the list itself is used up, as in the Shop. */
    boolean mouseClicked(double mouseX, double mouseY) {
        int x = left() + LOG_X;
        for (Filter target : Filter.values()) {
            if (inside(mouseX, mouseY, x + filterX(target), top() + FILTER_Y, filterW(target), INFO_SELECTOR_H)) {
                filter = target;
                listScroll = 0;
                return true;
            }
        }
        return inside(mouseX, mouseY, x, top() + LOG_LIST_Y, logW(), listH());
    }
}
