package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.network.BookOrderPayloads;
import com.alex.test.network.StorageRoomPayloads;
import com.alex.test.village.villagers.enchanter.BookOrders;
import com.alex.test.village.villagers.enchanter.EnchantBooks;
import com.alex.test.village.villagers.enchanter.EnchanterBalance;
import com.alex.test.network.StorageRoomPayloads.StockDoneView;
import com.alex.test.network.StorageRoomPayloads.StockRowView;
import com.alex.test.ui.StorageRoomMenu;
import com.alex.test.village.economy.StockOrderStatus;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.alex.test.ui.screen.StorageUi.*;

/**
 * The Stock tab: what the player asks the blacksmith, the cook, the archers and the
 * enchanter to make, one at a time behind a Blacksmith / Cook / Archer / Enchanter switch.
 * The enchanter's page orders enchanted books (see {@code BookOrders}), which are always
 * the player's.
 *
 * <p>Each row has a count and two switches. <b>Once</b> is an order, made one time and
 * then moved to the Completed list; <b>Always</b> keeps that many in storage. <b>For me</b>
 * locks what is made in storage until the player takes it; <b>Villagers</b> leaves it free
 * for them. Each row says what it is waiting for. The list on the right adds rows.
 */
final class StorageStockPage {
    static final int SIDE_W = 164;
    private static final int ROW_H = 40, ROW_GAP = 3, DONE_H = 26, DISH_H = 20, DISH_GAP = 2, BTN = 14;
    private static final int SEG_H = 13, MODE_W = 38, WHO_W = 48;
    private static final int REFRESH_TICKS = 40;
    private static final int BAR_H = 22;

    private final StorageRoomMenu menu;
    private final Font font;
    private final EditBox search;
    private int listScroll, dishScroll;
    private int left, top, right, bottom;
    /** Top of the Blacksmith / Cook switch; the panels start below it. */
    private int barTop;
    private boolean showDone;
    /** Whose tab this is: the blacksmith's or the cook's. */
    private Profession maker = Profession.COOK;
    private int refreshTimer;

    StorageStockPage(StorageRoomMenu menu, Font font, EditBox search) {
        this.menu = menu;
        this.font = font;
        this.search = search;
        search.setHint(Tr.c("screen.dynamicvillages.orders.search_dishes", "Search dishes..."));
    }

    void layout(int left, int top, int right, int bottom) {
        this.left = left;
        this.barTop = top;
        this.top = top + BAR_H + 4;
        this.right = right;
        this.bottom = bottom;
        search.setX(sideLeft() + 6);
        search.setY(this.top + 20);
        search.setWidth(SIDE_W - 12);
    }

    private int sideLeft() { return right - SIDE_W; }
    private int listRight() { return sideLeft() - 6; }
    private int listTop() { return top + 22; }
    private int listBottom() { return bottom - 40; }
    private int dishTop() { return top + 42; }
    /**
     * Choices that fit under the search box. The last one needs no gap under
     * it: counting one left the space for a whole row empty at the bottom on
     * GUI scale Auto.
     */
    private int dishRows() { return Math.max(1, (bottom - 4 - dishTop() + DISH_GAP) / (DISH_H + DISH_GAP)); }

    /** Switches to the blacksmith's or the cook's orders. */
    void setMaker(Profession maker) {
        if (this.maker == maker) return;
        this.maker = maker;
        listScroll = 0;
        dishScroll = 0;
        showDone = false;
        search.setValue("");
        search.setHint(smith() ? Tr.c("screen.dynamicvillages.orders.search_smith", "Search tools and armour...")
                : archer() ? Tr.c("screen.dynamicvillages.orders.search_archer", "Search arrows and bows...")
                : enchanter() ? Tr.c("screen.dynamicvillages.orders.search_enchanter", "Search enchantments...")
                : Tr.c("screen.dynamicvillages.orders.search_dishes", "Search dishes..."));
        // The book orders come with the next refresh; ask now rather than in two seconds.
        if (enchanter()) refresh();
    }

    private boolean smith() {
        return maker == Profession.BLACKSMITH;
    }

    private boolean archer() {
        return maker == Profession.ARCHER;
    }

    private boolean enchanter() {
        return maker == Profession.ENCHANTER;
    }

    /** Left edge and width of the four maker buttons (Blacksmith, Cook, Archer, Enchanter). */
    private int makerX(int index) {
        int quarter = (right - left - 6) / 4;
        return left + index * (quarter + 2);
    }

    private int makerW(int index) {
        int quarter = (right - left - 6) / 4;
        return index < 3 ? quarter : right - makerX(3);
    }

    private boolean mine(ResourceLocation item) {
        return StockReserves.producerOf(item(item)) == maker;
    }

    /** Asks the server for fresh progress. */
    void refresh() {
        refreshTimer = 0;
        PacketDistributor.sendToServer(new StorageRoomPayloads.StockRequest(menu.frameId()));
    }

    void tick() {
        if (++refreshTimer >= REFRESH_TICKS) refresh();
    }

    // =========================================================================
    // Data
    // =========================================================================

    private List<StockRowView> rows() {
        List<StockRowView> rows = new ArrayList<>();
        for (StockRowView row : menu.stockStatus().rows()) if (mine(row.item())) rows.add(row);
        return rows;
    }

    private List<StockDoneView> history() {
        List<StockDoneView> done = new ArrayList<>();
        for (StockDoneView entry : menu.stockStatus().history()) if (mine(entry.item())) done.add(entry);
        return done;
    }

    private static Item item(ResourceLocation id) {
        return BuiltInRegistries.ITEM.get(id);
    }

    private static ResourceLocation key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    @Nullable private String blocked(Item item) {
        return menu.stockStatus().blocked().get(key(item));
    }

    private boolean hasRow(Item item) {
        ResourceLocation id = key(item);
        for (StockRowView row : rows()) if (row.item().equals(id)) return true;
        return false;
    }

    /** What the right-hand list offers: the blacksmith's items, or the cook's dishes. */
    private List<Item> matchingChoices() {
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Item> choices = new ArrayList<>();
        if (smith()) choices.addAll(StockReserves.forgeItems());
        else if (archer()) choices.addAll(StockReserves.archerItems());
        else for (StorageRoomMenu.Dish dish : menu.dishes()) choices.add(dish.result());
        List<Item> matches = new ArrayList<>();
        for (Item item : choices) {
            if (query.isEmpty() || name(item).toLowerCase(Locale.ROOT).contains(query)
                    || key(item).toString().contains(query)) {
                matches.add(item);
            }
        }
        return matches;
    }

    private static int maxCount(boolean once) {
        return once ? StockReserves.MAX_ONCE : StockReserves.MAX_ALWAYS;
    }

    private void edit(StockRowView row, int count, boolean once, boolean forPlayer) {
        // Switching Once and Always starts the row over, so nothing is made yet.
        int min = once && row.once() ? Math.max(1, row.made()) : 1;
        count = Mth.clamp(count, min, maxCount(once));
        menu.editStockRowLocally(row.id(), count, once, forPlayer);
        PacketDistributor.sendToServer(new StorageRoomPayloads.StockEdit(menu.frameId(), row.id(),
                count, once, forPlayer));
    }

    private void remove(StockRowView row) {
        menu.removeStockRowLocally(row.id());
        PacketDistributor.sendToServer(new StorageRoomPayloads.StockRemove(menu.frameId(), row.id()));
    }

    private void add(Item item) {
        PacketDistributor.sendToServer(new StorageRoomPayloads.StockAdd(menu.frameId(), key(item),
                true, true, 1));
    }

    private static String name(Item item) {
        return item == BlacksmithActivity.WOOD_STAND_IN ? Tr.s("screen.dynamicvillages.orders.wood", "Wood (any log, or 2 planks)")
                : item.getDescription().getString();
    }

    private static int toneColor(int tone) {
        return switch (tone) {
            case StockOrderStatus.FINE -> ACCENT;
            case StockOrderStatus.WAITING -> GOLD;
            case StockOrderStatus.BLOCKED -> DENIED;
            default -> MUTED;
        };
    }

    /** "2/5 made, 1 ready", "2/5 stored", "3/8 in storage". */
    private static String progress(StockRowView row) {
        if (!row.once()) return Tr.s("screen.dynamicvillages.orders.in_storage", "%1$s/%2$s in storage", row.have(), row.count());
        if (!row.forPlayer()) return Tr.s("screen.dynamicvillages.orders.stored", "%1$s/%2$s stored", row.delivered(), row.count());
        return row.ready() > 0
                ? Tr.s("screen.dynamicvillages.orders.made_ready", "%1$s/%2$s made, %3$s ready", row.made(), row.count(), row.ready())
                : Tr.s("screen.dynamicvillages.orders.made", "%1$s/%2$s made", row.made(), row.count());
    }

    // =========================================================================
    // Layout helpers, shared by render, tooltip and click
    // =========================================================================

    private int visibleRows() {
        return Math.max(1, (listBottom() - listTop()) / ((showDone ? DONE_H : ROW_H) + ROW_GAP));
    }

    private int rowWidth(int count) {
        return listRight() - left - 12 - (count > visibleRows() ? 5 : 0);
    }

    private int rowY(int i) {
        return listTop() + i * ((showDone ? DONE_H : ROW_H) + ROW_GAP);
    }

    /** Orders / Completed share the panel's whole width, 2 px apart. */
    private int segmentWidth() {
        return (listRight() - left - 14) / 2;
    }

    // Button x positions within a row.
    private static int removeX(int x, int width) { return x + width - BTN - 4; }
    private static int plusX(int x, int width) { return removeX(x, width) - BTN - 4; }
    private static int minusX(int x, int width) { return plusX(x, width) - 30 - BTN; }
    private static int whoX(int x, int width) { return x + width - 4 - 2 * WHO_W; }
    private static int modeX(int x, int width) { return whoX(x, width) - 6 - 2 * MODE_W; }

    // =========================================================================
    // Rendering
    // =========================================================================

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        // Whose orders: the blacksmith's, the cook's or the archers'.
        makerButton(graphics, makerX(0), makerW(0), Items.ANVIL, Profession.BLACKSMITH.shownName(), smith(),
                inside(mouseX, mouseY, makerX(0), barTop, makerW(0), BAR_H));
        makerButton(graphics, makerX(1), makerW(1), Items.SMOKER, Profession.COOK.shownName(), maker == Profession.COOK,
                inside(mouseX, mouseY, makerX(1), barTop, makerW(1), BAR_H));
        makerButton(graphics, makerX(2), makerW(2), Items.BOW, Profession.ARCHER.shownName(), archer(),
                inside(mouseX, mouseY, makerX(2), barTop, makerW(2), BAR_H));
        makerButton(graphics, makerX(3), makerW(3), Items.ENCHANTING_TABLE, Profession.ENCHANTER.shownName(), enchanter(),
                inside(mouseX, mouseY, makerX(3), barTop, makerW(3), BAR_H));
        if (enchanter()) {
            renderBooks(graphics, mouseX, mouseY);
            return;
        }

        graphics.fill(left, top, listRight(), bottom, PANEL_DARK);
        int segW = segmentWidth();
        segment(graphics, font, left + 6, top + 4, segW, 14, Tr.s("screen.dynamicvillages.orders.tab.orders", "Orders (%s)", rows().size()),
                !showDone, inside(mouseX, mouseY, left + 6, top + 4, segW, 14));
        segment(graphics, font, left + 8 + segW, top + 4, segW, 14, Tr.s("screen.dynamicvillages.orders.tab.completed", "Completed (%s)", history().size()),
                showDone, inside(mouseX, mouseY, left + 8 + segW, top + 4, segW, 14));

        if (showDone) renderHistory(graphics);
        else renderRows(graphics, mouseX, mouseY);

        int noteY = listBottom() + 4;
        String note = showDone
                ? Tr.s("screen.dynamicvillages.orders.note.done", "Recent finished orders. An order for you is finished when you take it out of storage.")
                : (smith() ? Tr.s("screen.dynamicvillages.orders.note.smith", "Orders come first for the blacksmith, and storage holds its materials.")
                        : archer() ? Tr.s("screen.dynamicvillages.orders.note.archer", "Archers make orders in quiet time, and storage holds their flint, feathers and string.")
                        : Tr.s("screen.dynamicvillages.orders.note.cook", "Orders come first for the cook, and storage holds its ingredients."))
                        + " " + Tr.s("screen.dynamicvillages.orders.note.modes", "Once: made one time. Always: kept topped up. For me: locked in storage until you take it. Villagers: theirs to use.");
        for (FormattedCharSequence line : font.split(Component.literal(note), listRight() - left - 12)) {
            if (noteY + 9 > bottom - 2) break;
            graphics.drawString(font, line, left + 6, noteY, MUTED, false);
            noteY += 10;
        }

        renderChoices(graphics, mouseX, mouseY);
    }

    /**
     * A button for the Blacksmith / Cook switch: icon and label in a 1 px border, and the
     * green fill with accent bar when chosen.
     */
    private void makerButton(GuiGraphics graphics, int x, int width, Item icon, String label,
                             boolean selected, boolean hover) {
        int y = barTop, h = BAR_H;
        graphics.fill(x, y, x + width, y + h, hover && !selected ? BUTTON_HOVER_BORDER : PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + h - 1,
                selected ? ROW_SELECTED : hover ? ROW_HOVER : 0xFF303030);
        // The same 1 px border all round; the chosen one gets the accent bar along the bottom.
        if (selected) graphics.fill(x + 1, y + h - 3, x + width - 1, y + h - 1, ACCENT);
        int content = x + (width - 20 - font.width(label)) / 2;
        graphics.renderFakeItem(new ItemStack(icon), content, y + (h - 16) / 2);
        graphics.drawString(font, label, content + 20, y + (h - 8) / 2, selected ? TEXT : MUTED, false);
    }

    private void renderRows(GuiGraphics graphics, int mouseX, int mouseY) {
        List<StockRowView> rows = rows();
        int visible = visibleRows();
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, rows.size() - visible));
        if (rows.isEmpty()) {
            String empty = smith() ? Tr.s("screen.dynamicvillages.orders.empty.smith", "Nothing ordered yet. Pick a tool or armour piece on the right.")
                    : archer() ? Tr.s("screen.dynamicvillages.orders.empty.archer", "Nothing ordered yet. Pick arrows or a bow on the right.")
                    : Tr.s("screen.dynamicvillages.orders.empty.cook", "Nothing ordered yet. Pick a dish on the right.");
            graphics.drawString(font, trim(font, empty, listRight() - left - 12),
                    left + 6, listTop() + 8, MUTED, false);
        }
        int width = rowWidth(rows.size());
        for (int i = 0; i < visible && listScroll + i < rows.size(); i++) {
            renderRow(graphics, rows.get(listScroll + i), left + 6, rowY(i), width, mouseX, mouseY);
        }
        if (rows.size() > visible) {
            scrollbar(graphics, listRight() - 9, listTop(), listBottom() - listTop(),
                    listScroll, rows.size() - visible, visible);
        }
    }

    private void renderRow(GuiGraphics graphics, StockRowView row, int x, int y, int width,
                           int mouseX, int mouseY) {
        Item item = item(row.item());
        row(graphics, x, y, width, ROW_H, false, false);
        graphics.renderFakeItem(new ItemStack(item), x + 4, y + 4);

        int removeX = removeX(x, width), plusX = plusX(x, width), minusX = minusX(x, width);
        button(graphics, font, removeX, y + 3, BTN, BTN, "x", inside(mouseX, mouseY, removeX, y + 3, BTN, BTN), true);
        button(graphics, font, plusX, y + 3, BTN, BTN, "+", inside(mouseX, mouseY, plusX, y + 3, BTN, BTN),
                row.count() < maxCount(row.once()));
        int min = row.once() ? Math.max(1, row.made()) : 1;
        button(graphics, font, minusX, y + 3, BTN, BTN, "-", inside(mouseX, mouseY, minusX, y + 3, BTN, BTN),
                row.count() > min);
        String value = "x" + row.count();
        graphics.drawString(font, value, minusX + BTN + (30 - font.width(value)) / 2, y + 6, TEXT, false);

        String progress = progress(row);
        int nameRoom = minusX - x - 30;
        String title = trim(font, name(item), nameRoom);
        graphics.drawString(font, title, x + 24, y + 6, TEXT, false);
        int progressX = x + 24 + font.width(title) + 8;
        if (progressX + font.width(progress) < minusX - 6) {
            graphics.drawString(font, progress, progressX, y + 6, MUTED, false);
        }

        int modeX = modeX(x, width), whoX = whoX(x, width), segY = y + ROW_H - SEG_H - 4;
        segment(graphics, font, modeX, segY, MODE_W, SEG_H, Tr.s("screen.dynamicvillages.orders.once", "Once"), row.once(),
                inside(mouseX, mouseY, modeX, segY, MODE_W, SEG_H));
        segment(graphics, font, modeX + MODE_W, segY, MODE_W, SEG_H, Tr.s("screen.dynamicvillages.orders.always", "Always"), !row.once(),
                inside(mouseX, mouseY, modeX + MODE_W, segY, MODE_W, SEG_H));
        segment(graphics, font, whoX, segY, WHO_W, SEG_H, Tr.s("screen.dynamicvillages.orders.for_me", "For me"), row.forPlayer(),
                inside(mouseX, mouseY, whoX, segY, WHO_W, SEG_H));
        segment(graphics, font, whoX + WHO_W, segY, WHO_W, SEG_H, Tr.s("screen.dynamicvillages.orders.villagers", "Villagers"), !row.forPlayer(),
                inside(mouseX, mouseY, whoX + WHO_W, segY, WHO_W, SEG_H));

        graphics.drawString(font, trim(font, row.status(), modeX - x - 30), x + 24, segY + 3,
                toneColor(row.tone()), false);
    }

    private void renderHistory(GuiGraphics graphics) {
        List<StockDoneView> done = history();
        int visible = visibleRows();
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, done.size() - visible));
        if (done.isEmpty()) {
            graphics.drawString(font, Tr.s("screen.dynamicvillages.orders.no_finished", "No finished orders yet."), left + 6, listTop() + 8, MUTED, false);
        }
        int width = rowWidth(done.size());
        for (int i = 0; i < visible && listScroll + i < done.size(); i++) {
            StockDoneView entry = done.get(listScroll + i);
            int x = left + 6, y = rowY(i);
            Item item = item(entry.item());
            row(graphics, x, y, width, DONE_H, false, false);
            graphics.renderFakeItem(new ItemStack(item), x + 4, y + 5);
            String outcome = outcome(entry);
            int outcomeColor = entry.outcome() == StockReserves.Outcome.CANCELLED.ordinal() ? DENIED : ACCENT;
            graphics.drawString(font, outcome, x + width - 6 - font.width(outcome), y + 4, outcomeColor, false);
            graphics.drawString(font, trim(font, entry.count() + " " + name(item),
                    width - 40 - font.width(outcome)), x + 24, y + 4, TEXT, false);
            String detail = entry.forPlayer()
                    ? Tr.s("screen.dynamicvillages.orders.done.for_you", "Day %s, for you", entry.day())
                    : Tr.s("screen.dynamicvillages.orders.done.for_villagers", "Day %s, for the villagers", entry.day());
            if (!entry.by().isEmpty()) detail += Tr.s("screen.dynamicvillages.orders.done.ordered_by", ", ordered by %s", entry.by());
            graphics.drawString(font, trim(font, detail, width - 30), x + 24, y + 15, MUTED, false);
        }
        if (done.size() > visible) {
            scrollbar(graphics, listRight() - 9, listTop(), listBottom() - listTop(),
                    listScroll, done.size() - visible, visible);
        }
    }

    private static String outcome(StockDoneView entry) {
        if (entry.outcome() == StockReserves.Outcome.COLLECTED.ordinal()) return Tr.s("screen.dynamicvillages.orders.collected", "Collected");
        if (entry.outcome() == StockReserves.Outcome.DELIVERED.ordinal()) return Tr.s("screen.dynamicvillages.orders.delivered", "Delivered");
        return Tr.s("screen.dynamicvillages.orders.cancelled_partly", "Cancelled, %1$s of %2$s stored", entry.delivered(), entry.count());
    }

    private void renderChoices(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(sideLeft(), top, right, bottom, PANEL_DARK);
        graphics.drawString(font, smith() ? Tr.s("screen.dynamicvillages.orders.order_from.smith", "Order from the blacksmith")
                        : archer() ? Tr.s("screen.dynamicvillages.orders.order_from.archer", "Order from the archers")
                        : Tr.s("screen.dynamicvillages.orders.order_from.cook", "Order from the cook"),
                sideLeft() + 6, top + 6, TEXT, false);
        List<Item> choices = matchingChoices();
        int visible = dishRows();
        dishScroll = Mth.clamp(dishScroll, 0, Math.max(0, choices.size() - visible));
        int width = SIDE_W - 12 - (choices.size() > visible ? 5 : 0);
        for (int i = 0; i < visible && dishScroll + i < choices.size(); i++) {
            Item choice = choices.get(dishScroll + i);
            int x = sideLeft() + 6, y = dishTop() + i * (DISH_H + DISH_GAP);
            boolean blocked = blocked(choice) != null;
            boolean listed = hasRow(choice);
            row(graphics, x, y, width, DISH_H, listed, !blocked && inside(mouseX, mouseY, x, y, width, DISH_H));
            if (blocked) graphics.fill(x + 1, y + 1, x + width - 1, y + DISH_H - 1, ROW_DISABLED);
            graphics.renderFakeItem(new ItemStack(choice), x + 2, y + 2);
            String mark = blocked ? "!" : "+";
            graphics.drawString(font, mark, x + width - 5 - font.width(mark), y + 6,
                    blocked ? DENIED : listed ? ACCENT : MUTED, false);
            graphics.drawString(font, trim(font, name(choice), width - 30 - font.width(mark)),
                    x + 20, y + 6, blocked ? MUTED : listed ? TEXT : MUTED, false);
        }
        if (choices.isEmpty()) {
            graphics.drawString(font, Tr.s("screen.dynamicvillages.orders.nothing_matches", "Nothing matches"), sideLeft() + 6, dishTop() + 4, MUTED, false);
        } else if (choices.size() > visible) {
            scrollbar(graphics, right - 9, dishTop(), visible * (DISH_H + DISH_GAP) - DISH_GAP,
                    dishScroll, choices.size() - visible, visible);
        }
    }

    // =========================================================================
    // Tooltips
    // =========================================================================

    @Nullable List<Component> tooltip(int mouseX, int mouseY) {
        if (enchanter()) return bookTooltip(mouseX, mouseY);
        int segW = segmentWidth();
        if (inside(mouseX, mouseY, left + 8 + segW, top + 4, segW, 14)) {
            return List.of(Tr.c("screen.dynamicvillages.orders.tab.completed.desc", "Finished, collected and cancelled orders"));
        }
        if (!showDone) {
            List<StockRowView> rows = rows();
            int width = rowWidth(rows.size());
            for (int i = 0; i < visibleRows() && listScroll + i < rows.size(); i++) {
                List<Component> lines = rowTooltip(rows.get(listScroll + i), left + 6, rowY(i), width, mouseX, mouseY);
                if (lines != null) return lines;
            }
        }
        List<Item> choices = matchingChoices();
        int visible = dishRows();
        for (int i = 0; i < visible && dishScroll + i < choices.size(); i++) {
            int y = dishTop() + i * (DISH_H + DISH_GAP);
            if (!inside(mouseX, mouseY, sideLeft() + 6, y, SIDE_W - 12, DISH_H)) continue;
            Item choice = choices.get(dishScroll + i);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(name(choice)));
            String reason = blocked(choice);
            if (reason != null) {
                lines.add(Component.literal(reason).withStyle(ChatFormatting.RED));
            } else {
                costLines(choice, lines);
                lines.add(Tr.c("screen.dynamicvillages.orders.click_to_order", "Click: order one for you").withStyle(ChatFormatting.GRAY));
            }
            return lines;
        }
        return null;
    }

    @Nullable
    private List<Component> rowTooltip(StockRowView row, int x, int y, int width, int mouseX, int mouseY) {
        int modeX = modeX(x, width), whoX = whoX(x, width), segY = y + ROW_H - SEG_H - 4;
        if (inside(mouseX, mouseY, modeX, segY, MODE_W, SEG_H)) {
            return List.of(Tr.c("screen.dynamicvillages.orders.once", "Once"), Tr.c("screen.dynamicvillages.orders.once.desc", "Made one time, then moved to Completed")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (inside(mouseX, mouseY, modeX + MODE_W, segY, MODE_W, SEG_H)) {
            return List.of(Tr.c("screen.dynamicvillages.orders.always", "Always"), Tr.c("screen.dynamicvillages.orders.always.desc", "Kept topped up in storage; more is made when it runs short")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (inside(mouseX, mouseY, whoX, segY, WHO_W, SEG_H)) {
            return List.of(Tr.c("screen.dynamicvillages.orders.for_me", "For me"), Tr.c("screen.dynamicvillages.orders.for_me.desc", "Locked in storage until you take it: no villager uses, sells or eats it")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (inside(mouseX, mouseY, whoX + WHO_W, segY, WHO_W, SEG_H)) {
            return List.of(Tr.c("screen.dynamicvillages.orders.villagers", "Villagers"), Tr.c("screen.dynamicvillages.orders.villagers.desc", "Free for villagers to use once it is in storage")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (inside(mouseX, mouseY, x + 24, segY, modeX - x - 30, SEG_H)) {
            return List.of(Component.literal(row.status()));
        }
        if (!inside(mouseX, mouseY, x, y, minusX(x, width) - x, ROW_H - SEG_H - 4)) return null;
        Item item = item(row.item());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(name(item)));
        lines.add(Component.literal(progress(row)).withStyle(ChatFormatting.GRAY));
        costLines(item, lines);
        if (!row.by().isEmpty()) {
            lines.add(Tr.c("screen.dynamicvillages.orders.ordered_by", "Ordered by %s", row.by()).withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    /** "Each takes:" and one line per material, with what storage holds. */
    private void costLines(Item item, List<Component> lines) {
        Map<Item, Integer> cost = StockOrderStatus.unitCost(item);
        if (cost.isEmpty()) return;
        lines.add((StockReserves.isForgeItem(item) ? Tr.c("screen.dynamicvillages.orders.cost.smith", "Made by the blacksmith from:")
                        : StockReserves.isArcherItem(item)
                        ? (item == Items.ARROW ? Tr.c("screen.dynamicvillages.orders.cost.arrows", "4 made by an archer from (6 at a fletching table):")
                                : Tr.c("screen.dynamicvillages.orders.cost.archer", "Made by an archer from:"))
                        : Tr.c("screen.dynamicvillages.orders.cost.cook", "Cooked from:"))
                .withStyle(ChatFormatting.GRAY));
        for (Map.Entry<Item, Integer> entry : cost.entrySet()) {
            int have = menu.total(entry.getKey());
            if (entry.getKey() == Items.IRON_INGOT) have += menu.total(Items.RAW_IRON);
            lines.add(Tr.c("screen.dynamicvillages.orders.cost.line", "  %1$s %2$s  (%3$s stored)", entry.getValue(), name(entry.getKey()), have)
                    .withStyle(have >= entry.getValue() ? ChatFormatting.GRAY : ChatFormatting.GOLD));
        }
    }

    // =========================================================================
    // Input
    // =========================================================================

    boolean mouseClicked(double mouseX, double mouseY, boolean shift) {
        if (inside(mouseX, mouseY, makerX(0), barTop, makerW(0), BAR_H)) {
            setMaker(Profession.BLACKSMITH);
            return true;
        }
        if (inside(mouseX, mouseY, makerX(1), barTop, makerW(1), BAR_H)) {
            setMaker(Profession.COOK);
            return true;
        }
        if (inside(mouseX, mouseY, makerX(2), barTop, makerW(2), BAR_H)) {
            setMaker(Profession.ARCHER);
            return true;
        }
        if (inside(mouseX, mouseY, makerX(3), barTop, makerW(3), BAR_H)) {
            setMaker(Profession.ENCHANTER);
            return true;
        }
        int segW = segmentWidth();
        if (inside(mouseX, mouseY, left + 6, top + 4, segW, 14)) {
            showDone = false;
            listScroll = 0;
            return true;
        }
        if (inside(mouseX, mouseY, left + 8 + segW, top + 4, segW, 14)) {
            showDone = true;
            listScroll = 0;
            return true;
        }
        if (enchanter()) return clickBooks(mouseX, mouseY, shift ? 5 : 1);
        if (!showDone && clickRow(mouseX, mouseY, shift ? 10 : 1)) return true;

        List<Item> choices = matchingChoices();
        int visible = dishRows();
        for (int i = 0; i < visible && dishScroll + i < choices.size(); i++) {
            int y = dishTop() + i * (DISH_H + DISH_GAP);
            if (inside(mouseX, mouseY, sideLeft() + 6, y, SIDE_W - 12, DISH_H)) {
                Item choice = choices.get(dishScroll + i);
                if (blocked(choice) == null) add(choice);
                return true;
            }
        }
        return false;
    }

    private boolean clickRow(double mouseX, double mouseY, int step) {
        List<StockRowView> rows = rows();
        int width = rowWidth(rows.size());
        for (int i = 0; i < visibleRows() && listScroll + i < rows.size(); i++) {
            StockRowView row = rows.get(listScroll + i);
            int x = left + 6, y = rowY(i);
            if (inside(mouseX, mouseY, removeX(x, width), y + 3, BTN, BTN)) {
                remove(row);
                return true;
            }
            if (inside(mouseX, mouseY, plusX(x, width), y + 3, BTN, BTN)) {
                edit(row, row.count() + step, row.once(), row.forPlayer());
                return true;
            }
            if (inside(mouseX, mouseY, minusX(x, width), y + 3, BTN, BTN)) {
                edit(row, row.count() - step, row.once(), row.forPlayer());
                return true;
            }
            int modeX = modeX(x, width), whoX = whoX(x, width), segY = y + ROW_H - SEG_H - 4;
            if (inside(mouseX, mouseY, modeX, segY, MODE_W, SEG_H)) {
                if (!row.once()) edit(row, Math.min(row.count(), StockReserves.MAX_ONCE), true, row.forPlayer());
                return true;
            }
            if (inside(mouseX, mouseY, modeX + MODE_W, segY, MODE_W, SEG_H)) {
                if (row.once()) edit(row, row.count(), false, row.forPlayer());
                return true;
            }
            if (inside(mouseX, mouseY, whoX, segY, WHO_W, SEG_H)) {
                if (!row.forPlayer()) edit(row, row.count(), row.once(), true);
                return true;
            }
            if (inside(mouseX, mouseY, whoX + WHO_W, segY, WHO_W, SEG_H)) {
                if (row.forPlayer()) edit(row, row.count(), row.once(), false);
                return true;
            }
        }
        return false;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        int delta = scrollY > 0 ? -1 : 1;
        if (inside(mouseX, mouseY, left, top, listRight() - left, bottom - top)) {
            listScroll = Math.max(0, listScroll + delta);
            return true;
        }
        if (inside(mouseX, mouseY, sideLeft(), top, SIDE_W, bottom - top)) {
            dishScroll = Math.max(0, dishScroll + delta);
            return true;
        }
        return false;
    }

    void resetDishScroll() {
        dishScroll = 0;
    }

    // =========================================================================
    // The Enchanter page: enchanted book orders, always for the player
    // =========================================================================

    private List<BookOrderPayloads.RowView> bookRows() {
        return menu.bookStatus().rows();
    }

    private List<BookOrderPayloads.DoneView> bookHistory() {
        return menu.bookStatus().history();
    }

    private List<BookOrderPayloads.ChoiceView> bookChoices() {
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<BookOrderPayloads.ChoiceView> matches = new ArrayList<>();
        for (BookOrderPayloads.ChoiceView choice : menu.bookStatus().choices()) {
            if (query.isEmpty() || bookName(choice.kind(), choice.level()).toLowerCase(Locale.ROOT).contains(query)) {
                matches.add(choice);
            }
        }
        return matches;
    }

    private static String bookName(String kindId, int level) {
        EnchantBooks.Kind kind = EnchantBooks.byId(kindId);
        return kind == null ? kindId : EnchantBooks.name(kind, level);
    }

    private boolean hasBookRow(BookOrderPayloads.ChoiceView choice) {
        for (BookOrderPayloads.RowView row : bookRows()) {
            if (row.kind().equals(choice.kind()) && row.level() == choice.level()) return true;
        }
        return false;
    }

    private static int bookMax(boolean once) {
        return once ? BookOrders.MAX_ONCE : BookOrders.MAX_ALWAYS;
    }

    /** "1/2 made, 1 ready" or "1/2 in storage". */
    private static String bookProgress(BookOrderPayloads.RowView row) {
        if (!row.once()) return Tr.s("screen.dynamicvillages.orders.in_storage", "%1$s/%2$s in storage", row.stored(), row.count());
        return row.stored() > 0
                ? Tr.s("screen.dynamicvillages.orders.made_ready", "%1$s/%2$s made, %3$s ready", row.made(), row.count(), row.stored())
                : Tr.s("screen.dynamicvillages.orders.made", "%1$s/%2$s made", row.made(), row.count());
    }

    private void renderBooks(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(left, top, listRight(), bottom, PANEL_DARK);
        int segW = segmentWidth();
        segment(graphics, font, left + 6, top + 4, segW, 14, Tr.s("screen.dynamicvillages.orders.tab.orders", "Orders (%s)", bookRows().size()),
                !showDone, inside(mouseX, mouseY, left + 6, top + 4, segW, 14));
        segment(graphics, font, left + 8 + segW, top + 4, segW, 14, Tr.s("screen.dynamicvillages.orders.tab.completed", "Completed (%s)", bookHistory().size()),
                showDone, inside(mouseX, mouseY, left + 8 + segW, top + 4, segW, 14));

        if (showDone) renderBookHistory(graphics);
        else renderBookRows(graphics, mouseX, mouseY);

        int noteY = listBottom() + 4;
        String header = menu.bookStatus().header();
        String note = (header.isEmpty() ? "" : header + ". ")
                + (showDone ? Tr.s("screen.dynamicvillages.orders.note.books_done", "An order is finished when you have taken its books out of storage.")
                : Tr.s("screen.dynamicvillages.orders.note.books", "Orders come first for the enchanter, within its books a day. Books you order are yours: no villager uses or sells them. Books for the villagers' tools need no order, the enchanter plans those itself."));
        for (FormattedCharSequence line : font.split(Component.literal(note), listRight() - left - 12)) {
            if (noteY + 9 > bottom - 2) break;
            graphics.drawString(font, line, left + 6, noteY, MUTED, false);
            noteY += 10;
        }
        renderBookChoices(graphics, mouseX, mouseY);
    }

    private void renderBookRows(GuiGraphics graphics, int mouseX, int mouseY) {
        List<BookOrderPayloads.RowView> rows = bookRows();
        int visible = visibleRows();
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, rows.size() - visible));
        if (rows.isEmpty()) {
            graphics.drawString(font, trim(font, Tr.s("screen.dynamicvillages.orders.empty.books", "Nothing ordered yet. Pick a book on the right."),
                    listRight() - left - 12), left + 6, listTop() + 8, MUTED, false);
        }
        int width = rowWidth(rows.size());
        for (int i = 0; i < visible && listScroll + i < rows.size(); i++) {
            renderBookRow(graphics, rows.get(listScroll + i), left + 6, rowY(i), width, mouseX, mouseY);
        }
        if (rows.size() > visible) {
            scrollbar(graphics, listRight() - 9, listTop(), listBottom() - listTop(),
                    listScroll, rows.size() - visible, visible);
        }
    }

    private void renderBookRow(GuiGraphics graphics, BookOrderPayloads.RowView row, int x, int y, int width,
                               int mouseX, int mouseY) {
        row(graphics, x, y, width, ROW_H, false, false);
        graphics.renderFakeItem(new ItemStack(Items.ENCHANTED_BOOK), x + 4, y + 4);

        int removeX = removeX(x, width), plusX = plusX(x, width), minusX = minusX(x, width);
        button(graphics, font, removeX, y + 3, BTN, BTN, "x", inside(mouseX, mouseY, removeX, y + 3, BTN, BTN), true);
        button(graphics, font, plusX, y + 3, BTN, BTN, "+", inside(mouseX, mouseY, plusX, y + 3, BTN, BTN),
                row.count() < bookMax(row.once()));
        int min = row.once() ? Math.max(1, row.made()) : 1;
        button(graphics, font, minusX, y + 3, BTN, BTN, "-", inside(mouseX, mouseY, minusX, y + 3, BTN, BTN),
                row.count() > min);
        String value = "x" + row.count();
        graphics.drawString(font, value, minusX + BTN + (30 - font.width(value)) / 2, y + 6, TEXT, false);

        String progress = bookProgress(row);
        String title = trim(font, bookName(row.kind(), row.level()), minusX - x - 30);
        graphics.drawString(font, title, x + 24, y + 6, TEXT, false);
        int progressX = x + 24 + font.width(title) + 8;
        if (progressX + font.width(progress) < minusX - 6) {
            graphics.drawString(font, progress, progressX, y + 6, MUTED, false);
        }

        int modeX = modeX(x, width), whoX = whoX(x, width), segY = y + ROW_H - SEG_H - 4;
        segment(graphics, font, modeX, segY, MODE_W, SEG_H, Tr.s("screen.dynamicvillages.orders.once", "Once"), row.once(),
                inside(mouseX, mouseY, modeX, segY, MODE_W, SEG_H));
        segment(graphics, font, modeX + MODE_W, segY, MODE_W, SEG_H, Tr.s("screen.dynamicvillages.orders.always", "Always"), !row.once(),
                inside(mouseX, mouseY, modeX + MODE_W, segY, MODE_W, SEG_H));
        String forYou = Tr.s("screen.dynamicvillages.orders.for_you", "For you");
        graphics.drawString(font, forYou, whoX + WHO_W - font.width(forYou) / 2, segY + 3, MUTED, false);

        graphics.drawString(font, trim(font, row.status(), modeX - x - 30), x + 24, segY + 3,
                toneColor(row.tone()), false);
    }

    private void renderBookHistory(GuiGraphics graphics) {
        List<BookOrderPayloads.DoneView> done = bookHistory();
        int visible = visibleRows();
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, done.size() - visible));
        if (done.isEmpty()) {
            graphics.drawString(font, Tr.s("screen.dynamicvillages.orders.no_finished", "No finished orders yet."), left + 6, listTop() + 8, MUTED, false);
        }
        int width = rowWidth(done.size());
        for (int i = 0; i < visible && listScroll + i < done.size(); i++) {
            BookOrderPayloads.DoneView entry = done.get(listScroll + i);
            int x = left + 6, y = rowY(i);
            row(graphics, x, y, width, DONE_H, false, false);
            graphics.renderFakeItem(new ItemStack(Items.ENCHANTED_BOOK), x + 4, y + 5);
            boolean cancelled = entry.outcome() == BookOrders.CANCELLED;
            String outcome = cancelled ? Tr.s("screen.dynamicvillages.orders.cancelled", "Cancelled") : Tr.s("screen.dynamicvillages.orders.collected", "Collected");
            graphics.drawString(font, outcome, x + width - 6 - font.width(outcome), y + 4,
                    cancelled ? DENIED : ACCENT, false);
            graphics.drawString(font, trim(font, entry.count() + " " + bookName(entry.kind(), entry.level()),
                    width - 40 - font.width(outcome)), x + 24, y + 4, TEXT, false);
            String detail = Tr.s("screen.dynamicvillages.orders.done.day", "Day %s", entry.day())
                    + (entry.by().isEmpty() ? "" : Tr.s("screen.dynamicvillages.orders.done.ordered_by", ", ordered by %s", entry.by()));
            graphics.drawString(font, trim(font, detail, width - 30), x + 24, y + 15, MUTED, false);
        }
        if (done.size() > visible) {
            scrollbar(graphics, listRight() - 9, listTop(), listBottom() - listTop(),
                    listScroll, done.size() - visible, visible);
        }
    }

    private void renderBookChoices(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(sideLeft(), top, right, bottom, PANEL_DARK);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.orders.order_from.enchanter", "Order from the enchanter"), sideLeft() + 6, top + 6, TEXT, false);
        List<BookOrderPayloads.ChoiceView> choices = bookChoices();
        int visible = dishRows();
        dishScroll = Mth.clamp(dishScroll, 0, Math.max(0, choices.size() - visible));
        int width = SIDE_W - 12 - (choices.size() > visible ? 5 : 0);
        for (int i = 0; i < visible && dishScroll + i < choices.size(); i++) {
            BookOrderPayloads.ChoiceView choice = choices.get(dishScroll + i);
            int x = sideLeft() + 6, y = dishTop() + i * (DISH_H + DISH_GAP);
            boolean blocked = !choice.blocked().isEmpty();
            boolean listed = hasBookRow(choice);
            row(graphics, x, y, width, DISH_H, listed, !blocked && inside(mouseX, mouseY, x, y, width, DISH_H));
            if (blocked) graphics.fill(x + 1, y + 1, x + width - 1, y + DISH_H - 1, ROW_DISABLED);
            graphics.renderFakeItem(new ItemStack(Items.ENCHANTED_BOOK), x + 2, y + 2);
            String mark = blocked ? "!" : "+";
            graphics.drawString(font, mark, x + width - 5 - font.width(mark), y + 6,
                    blocked ? DENIED : listed ? ACCENT : MUTED, false);
            graphics.drawString(font, trim(font, bookName(choice.kind(), choice.level()), width - 30 - font.width(mark)),
                    x + 20, y + 6, blocked ? MUTED : listed ? TEXT : MUTED, false);
        }
        if (choices.isEmpty()) {
            graphics.drawString(font, menu.bookStatus().choices().isEmpty() ? Tr.s("screen.dynamicvillages.orders.loading", "Loading...") : Tr.s("screen.dynamicvillages.orders.nothing_matches", "Nothing matches"),
                    sideLeft() + 6, dishTop() + 4, MUTED, false);
        } else if (choices.size() > visible) {
            scrollbar(graphics, right - 9, dishTop(), visible * (DISH_H + DISH_GAP) - DISH_GAP,
                    dishScroll, choices.size() - visible, visible);
        }
    }

    @Nullable
    private List<Component> bookTooltip(int mouseX, int mouseY) {
        int segW = segmentWidth();
        if (inside(mouseX, mouseY, left + 8 + segW, top + 4, segW, 14)) {
            return List.of(Tr.c("screen.dynamicvillages.orders.tab.completed_books.desc", "Collected and cancelled book orders"));
        }
        if (!showDone) {
            List<BookOrderPayloads.RowView> rows = bookRows();
            int width = rowWidth(rows.size());
            for (int i = 0; i < visibleRows() && listScroll + i < rows.size(); i++) {
                BookOrderPayloads.RowView row = rows.get(listScroll + i);
                int x = left + 6, y = rowY(i);
                int modeX = modeX(x, width), whoX = whoX(x, width), segY = y + ROW_H - SEG_H - 4;
                if (inside(mouseX, mouseY, modeX, segY, MODE_W, SEG_H)) {
                    return List.of(Tr.c("screen.dynamicvillages.orders.once", "Once"), Tr.c("screen.dynamicvillages.orders.once.books.desc", "Written one time, then moved to Completed")
                            .withStyle(ChatFormatting.GRAY));
                }
                if (inside(mouseX, mouseY, modeX + MODE_W, segY, MODE_W, SEG_H)) {
                    return List.of(Tr.c("screen.dynamicvillages.orders.always", "Always"), Tr.c("screen.dynamicvillages.orders.always.books.desc", "Kept waiting in storage; another is written when you take one")
                            .withStyle(ChatFormatting.GRAY));
                }
                if (inside(mouseX, mouseY, whoX, segY, 2 * WHO_W, SEG_H)) {
                    return List.of(Tr.c("screen.dynamicvillages.orders.for_you", "For you"), Tr.c("screen.dynamicvillages.orders.for_you.books.desc", "Ordered books are always yours: no villager uses or sells them")
                            .withStyle(ChatFormatting.GRAY));
                }
                if (inside(mouseX, mouseY, x + 24, segY, modeX - x - 30, SEG_H)) {
                    return List.of(Component.literal(row.status()));
                }
                if (inside(mouseX, mouseY, x, y, minusX(x, width) - x, ROW_H - SEG_H - 4)) {
                    List<Component> lines = new ArrayList<>();
                    lines.add(Tr.c("screen.dynamicvillages.orders.book", "%s book", bookName(row.kind(), row.level())));
                    lines.add(Component.literal(bookProgress(row)).withStyle(ChatFormatting.GRAY));
                    lines.add(Tr.c("screen.dynamicvillages.orders.book.each_takes", "Each takes a book and %s lapis", EnchanterBalance.lapisFor(row.level()))
                            .withStyle(ChatFormatting.GRAY));
                    if (!row.by().isEmpty()) {
                        lines.add(Tr.c("screen.dynamicvillages.orders.ordered_by", "Ordered by %s", row.by()).withStyle(ChatFormatting.DARK_GRAY));
                    }
                    return lines;
                }
            }
        }
        List<BookOrderPayloads.ChoiceView> choices = bookChoices();
        int visible = dishRows();
        for (int i = 0; i < visible && dishScroll + i < choices.size(); i++) {
            int y = dishTop() + i * (DISH_H + DISH_GAP);
            if (!inside(mouseX, mouseY, sideLeft() + 6, y, SIDE_W - 12, DISH_H)) continue;
            BookOrderPayloads.ChoiceView choice = choices.get(dishScroll + i);
            List<Component> lines = new ArrayList<>();
            lines.add(Tr.c("screen.dynamicvillages.orders.book", "%s book", bookName(choice.kind(), choice.level())));
            if (!choice.blocked().isEmpty()) {
                lines.add(Component.literal(choice.blocked()).withStyle(ChatFormatting.RED));
            } else {
                lines.add(Tr.c("screen.dynamicvillages.orders.book.takes", "Takes a book and %s lapis", EnchanterBalance.lapisFor(choice.level()))
                        .withStyle(ChatFormatting.GRAY));
                lines.add(Tr.c("screen.dynamicvillages.orders.click_to_order", "Click: order one for you").withStyle(ChatFormatting.GRAY));
            }
            return lines;
        }
        return null;
    }

    private boolean clickBooks(double mouseX, double mouseY, int step) {
        if (!showDone) {
            List<BookOrderPayloads.RowView> rows = bookRows();
            int width = rowWidth(rows.size());
            for (int i = 0; i < visibleRows() && listScroll + i < rows.size(); i++) {
                BookOrderPayloads.RowView row = rows.get(listScroll + i);
                int x = left + 6, y = rowY(i);
                if (inside(mouseX, mouseY, removeX(x, width), y + 3, BTN, BTN)) {
                    PacketDistributor.sendToServer(new BookOrderPayloads.Remove(menu.frameId(), row.id()));
                    return true;
                }
                int min = row.once() ? Math.max(1, row.made()) : 1;
                if (inside(mouseX, mouseY, plusX(x, width), y + 3, BTN, BTN)) {
                    editBook(row, Mth.clamp(row.count() + step, min, bookMax(row.once())), row.once());
                    return true;
                }
                if (inside(mouseX, mouseY, minusX(x, width), y + 3, BTN, BTN)) {
                    editBook(row, Mth.clamp(row.count() - step, min, bookMax(row.once())), row.once());
                    return true;
                }
                int modeX = modeX(x, width), segY = y + ROW_H - SEG_H - 4;
                if (inside(mouseX, mouseY, modeX, segY, MODE_W, SEG_H)) {
                    if (!row.once()) editBook(row, Math.min(row.count(), BookOrders.MAX_ONCE), true);
                    return true;
                }
                if (inside(mouseX, mouseY, modeX + MODE_W, segY, MODE_W, SEG_H)) {
                    if (row.once()) editBook(row, Math.min(row.count(), BookOrders.MAX_ALWAYS), false);
                    return true;
                }
            }
        }
        List<BookOrderPayloads.ChoiceView> choices = bookChoices();
        int visible = dishRows();
        for (int i = 0; i < visible && dishScroll + i < choices.size(); i++) {
            int y = dishTop() + i * (DISH_H + DISH_GAP);
            if (inside(mouseX, mouseY, sideLeft() + 6, y, SIDE_W - 12, DISH_H)) {
                BookOrderPayloads.ChoiceView choice = choices.get(dishScroll + i);
                if (choice.blocked().isEmpty()) {
                    PacketDistributor.sendToServer(new BookOrderPayloads.Add(menu.frameId(), choice.kind(),
                            choice.level(), true, 1));
                }
                return true;
            }
        }
        return false;
    }

    private void editBook(BookOrderPayloads.RowView row, int count, boolean once) {
        PacketDistributor.sendToServer(new BookOrderPayloads.Edit(menu.frameId(), row.id(), count, once));
    }
}
