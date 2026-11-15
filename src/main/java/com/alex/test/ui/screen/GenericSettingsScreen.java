package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.client.BardTrackPreview;
import com.alex.test.ui.GenericSettingsMenu;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.network.GenericSettingsPayloads;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsText;
import com.alex.test.village.villagers.tradesman.TradesmanLedger;
import com.alex.test.village.villagers.tradesman.settings.TradesmanSalePolicy;
import com.alex.test.village.villagers.tradesman.settings.TradesmanSettingsSchema;
import com.alex.test.village.visitors.MerchantPricing;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.glfw.GLFW;

/**
 * Renders generic profession settings in the same style as the hand-written
 * settings screens. The Tradesman displays its four schema rows together;
 * other professions retain section tabs.
 */
public final class GenericSettingsScreen extends AbstractContainerScreen<GenericSettingsMenu> {

    private static final int WIDTH = 302;
    private static final int HEIGHT = 205;
    private static final int TRADESMAN_WIDTH = 356;
    /** 229 plus one row (23) for "Stop selling below % of price". */
    private static final int TRADESMAN_SETTINGS_HEIGHT = 252;
    private static final int TRADESMAN_TAB_WIDTH = 110;
    private static final int TRADESMAN_ROW_TOP = 57;
    private static final int TRADESMAN_ROW_STEP = 23;
    private static final int TRADESMAN_ROW_HEIGHT = 20;
    private static final int SALE_LIST_TOP = 84;
    private static final int SALE_ROW_STEP = 26;
    private static final int SALE_ROW_HEIGHT = 24;
    private static final int MAX_SALE_ROWS = 7;
    private static final int MIN_SALE_ROWS = 2;
    private static final int SALE_COLUMNS = 2;
    private static final int SALE_CARD_WIDTH = 159;
    private static final int SALE_CARD_GAP = 3;
    private static final int SALE_SCROLLBAR_GAP = 3;
    // 6 px from the inset on the left, and 6 px past the scrollbar on the right.
    private static final int SALE_CARD_X = 14;
    private static final int SALE_SCROLLBAR_X = SALE_CARD_X
            + SALE_COLUMNS * SALE_CARD_WIDTH + (SALE_COLUMNS - 1) * SALE_CARD_GAP
            + SALE_SCROLLBAR_GAP;
    /** Inner padding of a card, the same on both sides. */
    private static final int SALE_PAD = 4;
    private static final int SALE_SWITCH_W = 40;
    private static final int SALE_SWITCH_H = 14;
    private static final int SALE_BAR_HEIGHT = 38;
    private static final int SALE_BAR_WIDTH = SALE_SCROLLBAR_X + 4 - SALE_CARD_X;
    private static final int SALE_BUTTON_H = 16;
    private static final int SALE_AUTO_W = 34;
    private static final int SALE_VALUE_W = 34;
    /** One per Behaviour page slot: Working, Selling Spare Food, then the three numbers. */
    private static final int TRADESMAN_ROWS = 5;

    private static String tradesmanDescription(int slot) {
        return switch (slot) {
            case 0 -> Tr.s("screen.dynamicvillages.trade.desc.working", "ON: sells village surplus to the\nvisiting merchant.\nOFF: sells nothing.");
            case 1 -> Tr.s("screen.dynamicvillages.trade.desc.food", "ON: food the merchant wants is sold\ntoo, once the village has enough.\nOFF: never sells food.");
            case 2 -> Tr.s("screen.dynamicvillages.trade.desc.keep_items", "Of each item (not food), storage\nkeeps this many, and only the rest\nis sold. Sale items can set its\nown number for any item.");
            case 3 -> Tr.s("screen.dynamicvillages.trade.desc.keep_food", "Of each food, keeps this many for\nevery villager. Only the rest is sold.\nNever lower than 16, because each\nvillager carries up to 16 food.\nSale items can set it per food.");
            default -> Tr.s("screen.dynamicvillages.trade.desc.floor", "Every lot sold lowers that good's\nprice. Once it falls below this share\nof normal, the tradesman keeps the\nrest for a later visit. Sell box\ngoods sell at any price.");
        };
    }

    private static String tradesmanInfo() {
        return Tr.s("screen.dynamicvillages.trade.info", "When the merchant visits, the tradesman sells the village's surplus to it on its own: items on its offer list, best price first, lot by lot at today's price. Village goods you put in the stall's barrel are sold too, and their emeralds come back to the barrel. Items set to KEEP on the Sale items tab are never taken from storage.");
    }
    private static final int CONTENT_X = 8;
    private static final int TAB_Y = 24;
    private static final int TAB_H = 23;
    private static final int TAB_GAP = 5;
    private static final int LIST_TOP = 57;
    private static final int LIST_BOTTOM = 195;
    private static final int ROW_HEIGHT = 23;
    private static final int SCROLLBAR_BOTTOM_PADDING = 3;
    private static final int BODY = 0xFF242424;
    private static final int INSET = 0xFF111111;
    private static final int BORDER = 0xFF121212;
    private static final int TAB_BORDER = 0xFF3B3B3B;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFAAAAAA;
    private static final int GREEN = 0xFF30D158;
    private static final int RED = 0xFFFF453A;
    private static final int KEEP = 0xFFE6D49A;
    private static final int ROW = 0xFF292929;
    private static final int ROW_HOVER = 0xFF383838;
    private static final int TAB_SELECTED = 0xFF3B4E3E;
    private static final int ACCENT = 0xFF6BAF72;
    private static final int BUTTON = 0xFF3B3B3B;

    private int activeTab;
    private int[] sectionScroll;
    private boolean draggingScrollbar;
    private boolean showingSaleItems;
    private int saleScroll;
    private boolean draggingSaleScrollbar;
    /** The Sale items card whose keep number the bar under the list edits. */
    @Nullable private ResourceLocation selectedSale;
    private boolean showingSalesLog;
    private int logScroll;
    private static final int LOG_LIST_TOP = 84;
    private static final int LOG_ROW_STEP = 19;
    private static final int LOG_ROW_HEIGHT = 18;
    private static final int MAX_LOG_ROWS = 11;
    private static final int MIN_LOG_ROWS = 3;
    /** Free space kept above and below a page that has to shrink to fit (GUI scale Auto). */
    private static final int SCREEN_MARGIN = 4;

    /** One line of the sales log: a day heading with its totals, or one sale. */
    private record LogRow(long day, int daySales, int dayEmeralds, @Nullable TradesmanLedger.Entry sale) {}
    private EditBox saleSearch;
    private String saleQuery = "";
    /** The Bard's hand-built Music tab (null for other jobs); the other tabs stay plain lists. */
    @Nullable private final BardMusicPage bardMusic;

    public GenericSettingsScreen(GenericSettingsMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        bardMusic = BardMusicPage.of(menu);
        imageWidth = isTradesman() ? TRADESMAN_WIDTH : WIDTH;
        imageHeight = isTradesman() ? tradesmanHeight() : pageHeight();
        titleLabelX = 10;
        titleLabelY = 9;
        inventoryLabelY = -1000;
        sectionScroll = new int[menu.sectionCount()];
    }

    @Override
    protected void init() {
        imageHeight = isTradesman() ? tradesmanHeight() : pageHeight();
        super.init();
        if (!isTradesman()) return;
        saleSearch = new EditBox(font, leftPos + SALE_CARD_X, topPos + 59,
                SALE_SCROLLBAR_X + 4 - SALE_CARD_X, 18,
                Tr.c("screen.dynamicvillages.trade.search", "Search sale items"));
        saleSearch.setHint(Tr.c("screen.dynamicvillages.trade.search.hint", "Search, also finds items you have none of yet..."));
        saleSearch.setMaxLength(128);
        saleSearch.setValue(saleQuery);
        saleSearch.setResponder(value -> {
            saleQuery = value;
            saleScroll = 0;
        });
        saleSearch.visible = showingSaleItems;
        addRenderableWidget(saleSearch);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        if (isTradesman()) {
            renderTradesman(graphics, mouseX, mouseY);
            return;
        }
        panel(graphics, leftPos, topPos, WIDTH, imageHeight);

        stepTabWidths();
        for (int i = 0; i < menu.sectionCount(); i++) {
            tab(graphics, tabX(i), topPos + TAB_Y, tabWidth(i),
                    menu.section(i).title(), i == activeTab, mouseX, mouseY);
        }
        if (onBardMusic()) {
            bardMusic.render(graphics, font, leftPos, topPos, mouseX, mouseY);
            renderTabTooltip(graphics, mouseX, mouseY);
            return;
        }

        graphics.fill(
                leftPos + CONTENT_X, topPos + LIST_TOP - 2,
                leftPos + WIDTH - 8, topPos + LIST_BOTTOM,
                INSET);

        List<GenericSettingsMenu.RowLine> lines = menu.linesForSection(activeTab);
        int scroll = currentScroll();
        int visibleRows = visibleRows();
        int rowX = leftPos + 10;
        int rowWidth = rowWidth();
        for (int visible = 0; visible < visibleRows; visible++) {
            int index = scroll + visible;
            if (index >= lines.size()) break;
            int rowY = topPos + LIST_TOP + visible * ROW_HEIGHT;
            GenericSettingsMenu.RowLine line = lines.get(index);
            if (line.isHeader()) {
                SettingsHeading.draw(graphics, font, rowX, rowY, rowWidth, line.label());
            } else if (isStepper(line)) {
                stepperRow(graphics, rowX, rowY, rowWidth, line, mouseX, mouseY);
            } else {
                toggleRow(graphics, rowX, rowY, rowWidth, line, mouseX, mouseY);
            }
        }

        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            int x = leftPos + WIDTH - 14;
            int height = LIST_BOTTOM - LIST_TOP - SCROLLBAR_BOTTOM_PADDING;
            graphics.fill(x, topPos + LIST_TOP, x + 4, topPos + LIST_TOP + height, 0xFF333333);
            int thumbHeight = Math.max(18, height * visibleRows / lines.size());
            int thumbY = topPos + LIST_TOP + (height - thumbHeight) * scroll / maxScroll;
            graphics.fill(x, thumbY, x + 4, thumbY + thumbHeight, 0xFF888888);
        }
        renderRowTooltip(graphics, mouseX, mouseY);
        renderTabTooltip(graphics, mouseX, mouseY);
    }

    private boolean isTradesman() {
        return menu.schema().profession() == Profession.TRADESMAN;
    }

    private boolean onBardMusic() {
        return bardMusic != null && activeTab == bardMusic.section();
    }

    /** The window's height for the open tab: the Bard's Music tab is taller than the plain lists. */
    private int pageHeight() {
        return onBardMusic() ? bardMusic.height() : HEIGHT;
    }

    /** Opens a tab; a tab of another height re-centres the window, like the Tradesman's pages. */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (bardMusic == null) return;
        // The Music tab's track preview plays only while its row is on screen (round 7, 10-24).
        if (onBardMusic()) bardMusic.tick();
        else BardTrackPreview.stop();
    }

    @Override
    public void removed() {
        // Every way the window goes (Esc, E, walking away, another screen) ends up here.
        BardTrackPreview.stop();
        super.removed();
    }

    private void showTab(int tab) {
        if (tab != activeTab) BardTrackPreview.stop();
        activeTab = tab;
        int wanted = pageHeight();
        if (wanted == imageHeight) return;
        imageHeight = wanted;
        topPos = (height - imageHeight) / 2;
    }

    private int tradesmanHeight() {
        if (showingSaleItems) return saleItemsHeight();
        return showingSalesLog ? salesLogHeight() : TRADESMAN_SETTINGS_HEIGHT;
    }

    /**
     * Height the window has for a page. Before the screen is sized (the
     * constructor) there is no limit yet; init() measures again.
     */
    private int roomForPage() {
        return height > 0 ? height - 2 * SCREEN_MARGIN : Integer.MAX_VALUE;
    }

    /**
     * Card rows on the Sale items page: as many as fit, up to 7. A small GUI
     * (scale Auto on a 1080p or 720p screen) shows fewer and scrolls.
     */
    private int saleRows() {
        long room = (long) roomForPage() - saleItemsHeight(0);
        return (int) Math.clamp(room / SALE_ROW_STEP, MIN_SALE_ROWS, MAX_SALE_ROWS);
    }

    /** The Sale items page for {@code rows} card rows: header, grid, keep bar, 6 px under it. */
    private static int saleItemsHeight(int rows) {
        return SALE_LIST_TOP + rows * SALE_ROW_STEP + 4 + SALE_BAR_HEIGHT + 6 + 8;
    }

    private int saleItemsHeight() {
        return saleItemsHeight(saleRows());
    }

    /** Top of the keep bar, 4 px under the grid's last row slot. */
    private int saleBarTop() {
        return SALE_LIST_TOP + saleRows() * SALE_ROW_STEP + 4;
    }

    /** Sales log rows: as many as fit, up to 11. */
    private int logRows() {
        long room = (long) roomForPage() - salesLogHeight(0);
        return (int) Math.clamp(room / LOG_ROW_STEP, MIN_LOG_ROWS, MAX_LOG_ROWS);
    }

    /** The Sales log page for {@code rows} rows, with 6 px under the last one. */
    private static int salesLogHeight(int rows) {
        return LOG_LIST_TOP + rows * LOG_ROW_STEP - (LOG_ROW_STEP - LOG_ROW_HEIGHT) + 6 + 8;
    }

    private int salesLogHeight() {
        return salesLogHeight(logRows());
    }

    /** 0 = Settings, 1 = Sale items, 2 = Sales log. */
    private void showTradesmanPage(int page) {
        boolean saleItems = page == 1;
        boolean salesLog = page == 2;
        if (showingSaleItems == saleItems && showingSalesLog == salesLog) return;
        showingSaleItems = saleItems;
        showingSalesLog = salesLog;
        imageHeight = tradesmanHeight();
        topPos = (height - imageHeight) / 2;
        if (saleSearch != null) {
            saleSearch.setY(topPos + 59);
            saleSearch.visible = saleItems;
            if (!saleItems) saleSearch.setFocused(false);
        }
    }

    private int tradesmanTabX(int index) {
        return leftPos + CONTENT_X + index * (TRADESMAN_TAB_WIDTH + TAB_GAP);
    }

    private void renderTradesman(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        if (saleSearch != null) {
            saleSearch.visible = showingSaleItems;
            if (!showingSaleItems) saleSearch.setFocused(false);
        }
        panel(graphics, x, y, TRADESMAN_WIDTH, tradesmanHeight());
        tradesmanTab(graphics, tradesmanTabX(0), y + TAB_Y,
                Tr.s("screen.dynamicvillages.settings.tab.behaviour", "Behaviour"), Items.COMPASS,
                !showingSaleItems && !showingSalesLog, mouseX, mouseY);
        tradesmanTab(graphics, tradesmanTabX(1), y + TAB_Y,
                Tr.s("screen.dynamicvillages.trade.tab.sale_items", "Sale items"), Items.CHEST, showingSaleItems, mouseX, mouseY);
        tradesmanTab(graphics, tradesmanTabX(2), y + TAB_Y,
                Tr.s("screen.dynamicvillages.trade.tab.sales_log", "Sales log"), Items.EMERALD, showingSalesLog, mouseX, mouseY);
        if (showingSaleItems) {
            renderSaleItems(graphics, mouseX, mouseY);
            return;
        }
        if (showingSalesLog) {
            renderSalesLog(graphics, mouseX, mouseY);
            return;
        }
        graphics.fill(x + 8, y + 55, x + TRADESMAN_WIDTH - 8,
                y + TRADESMAN_SETTINGS_HEIGHT - 8, INSET);

        int rowX = x + 10;
        int rowWidth = TRADESMAN_WIDTH - 20;
        for (int slot = 0; slot < TRADESMAN_ROWS; slot++) {
            GenericSettingsMenu.RowLine line = tradesmanLine(slot);
            int rowY = y + TRADESMAN_ROW_TOP + slot * TRADESMAN_ROW_STEP;
            if (line.isHeader()) {
                SettingsHeading.draw(graphics, font, rowX, rowY, rowWidth, line.label());
            } else if (isStepper(line)) {
                stepperRow(graphics, rowX, rowY, rowWidth, line, mouseX, mouseY);
            } else {
                toggleRow(graphics, rowX, rowY, rowWidth, line, mouseX, mouseY);
            }
        }

        int infoY = y + TRADESMAN_ROW_TOP + (TRADESMAN_ROWS - 1) * TRADESMAN_ROW_STEP
                + TRADESMAN_ROW_HEIGHT + 8;
        int infoBottom = y + TRADESMAN_SETTINGS_HEIGHT - 10;
        for (FormattedCharSequence line : font.split(Component.literal(tradesmanInfo()), rowWidth - 8)) {
            if (infoY + 9 > infoBottom) break;
            graphics.drawString(font, line, rowX + 4, infoY, MUTED, false);
            infoY += 10;
        }

        int hoveredRow = tradesmanRowAt(mouseX, mouseY);
        if (hoveredRow >= 0) {
            SettingsTooltip.render(graphics, font, tradesmanDescription(hoveredRow), mouseX, mouseY);
        }
    }

    private void tradesmanTab(GuiGraphics graphics, int x, int y, String label, Item icon,
                              boolean selected, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + TRADESMAN_TAB_WIDTH
                && mouseY >= y && mouseY < y + TAB_H;
        graphics.fill(x, y, x + TRADESMAN_TAB_WIDTH, y + TAB_H, TAB_BORDER);
        graphics.fill(x + 1, y + 1, x + TRADESMAN_TAB_WIDTH - 1, y + TAB_H,
                selected ? TAB_SELECTED : hovered ? ROW_HOVER : ROW);
        if (selected) graphics.fill(x + 1, y + TAB_H - 2,
                x + TRADESMAN_TAB_WIDTH - 1, y + TAB_H, ACCENT);
        graphics.renderFakeItem(new ItemStack(icon), x + 6, y + 3);
        graphics.drawString(font, label, x + 27, y + 8, TEXT, false);
    }

    private void renderSaleItems(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x + 8, y + 53, x + TRADESMAN_WIDTH - 8,
                y + saleItemsHeight() - 8, INSET);

        List<GenericSettingsMenu.SaleItem> items = filteredSaleItems();
        int maxScroll = Math.max(0, saleRowCount(items.size()) - saleRows());
        saleScroll = Math.min(saleScroll, maxScroll);
        String tooltip = null;
        if (items.isEmpty()) {
            boolean searching = !saleQuery.trim().isEmpty();
            int middle = y + SALE_LIST_TOP + (saleRows() * SALE_ROW_STEP - 2) / 2;
            graphics.drawCenteredString(font, searching ? Tr.s("screen.dynamicvillages.trade.no_match", "No matching items") : Tr.s("screen.dynamicvillages.trade.no_items", "No sale items in storage yet"),
                    x + TRADESMAN_WIDTH / 2, middle - 10, MUTED);
            // A search for dirt finds nothing: say why.
            graphics.drawCenteredString(font, searching ? Tr.s("screen.dynamicvillages.trade.no_match.desc", "The merchant only buys what villagers make.")
                            : Tr.s("screen.dynamicvillages.trade.no_items.desc", "Search above to set an item up ahead of time."),
                    x + TRADESMAN_WIDTH / 2, middle + 2, MUTED);
        } else {
            for (int visible = 0; visible < saleRows(); visible++) {
                int rowY = y + SALE_LIST_TOP + visible * SALE_ROW_STEP;
                for (int column = 0; column < SALE_COLUMNS; column++) {
                    int index = (saleScroll + visible) * SALE_COLUMNS + column;
                    if (index >= items.size()) break;
                    String hover = saleCard(graphics, items.get(index), x + saleCardX(column), rowY,
                            mouseX, mouseY);
                    if (hover != null) tooltip = hover;
                }
            }
        }
        // The track is always drawn so the grid keeps the same margins either way.
        int trackTop = y + SALE_LIST_TOP;
        int trackHeight = saleRows() * SALE_ROW_STEP - 2;
        graphics.fill(x + SALE_SCROLLBAR_X, trackTop,
                x + SALE_SCROLLBAR_X + 4, trackTop + trackHeight, 0xFF333333);
        if (maxScroll > 0) {
            int thumbHeight = Math.max(17,
                    trackHeight * saleRows() / saleRowCount(items.size()));
            int thumbTop = trackTop + (trackHeight - thumbHeight) * saleScroll / maxScroll;
            graphics.fill(x + SALE_SCROLLBAR_X, thumbTop,
                    x + SALE_SCROLLBAR_X + 4, thumbTop + thumbHeight, 0xFF888888);
        }
        String barHover = saleKeepBar(graphics, mouseX, mouseY);
        if (barHover != null) tooltip = barHover;
        SettingsTooltip.render(graphics, font, tooltip, mouseX, mouseY);
    }

    /** One Sale items card; returns its hover text when the mouse is on it. */
    @Nullable
    private String saleCard(GuiGraphics graphics, GenericSettingsMenu.SaleItem row,
                            int cardX, int rowY, int mouseX, int mouseY) {
        Item item = BuiltInRegistries.ITEM.getOptional(row.id()).orElse(Items.AIR);
        boolean hovered = mouseX >= cardX && mouseX < cardX + SALE_CARD_WIDTH
                && mouseY >= rowY && mouseY < rowY + SALE_ROW_HEIGHT;
        graphics.fill(cardX, rowY, cardX + SALE_CARD_WIDTH, rowY + SALE_ROW_HEIGHT,
                hovered ? ROW_HOVER : ROW);
        if (row.id().equals(selectedSale)) outline(graphics, cardX, rowY, SALE_CARD_WIDTH, SALE_ROW_HEIGHT, ACCENT);
        graphics.renderFakeItem(new ItemStack(item), cardX + SALE_PAD, rowY + (SALE_ROW_HEIGHT - 16) / 2);

        int switchX = cardX + SALE_CARD_WIDTH - SALE_PAD - SALE_SWITCH_W;
        int switchY = rowY + (SALE_ROW_HEIGHT - SALE_SWITCH_H) / 2;
        saleSwitch(graphics, switchX, switchY, row.allowed());

        int textX = cardX + SALE_PAD + 20;
        int textWidth = switchX - SALE_PAD - textX;
        String name = item.getDescription().getString();
        graphics.drawString(font, fit(name, textWidth), textX, rowY + 3, TEXT, false);
        String stored = row.count() > 0 ? Tr.s("screen.dynamicvillages.trade.stored", "%s stored", row.count()) : Tr.s("screen.dynamicvillages.trade.none_stored", "None stored yet");
        graphics.drawString(font, fit(stored, textWidth), textX, rowY + 13, MUTED, false);
        if (row.keep() != TradesmanSalePolicy.AUTO && row.allowed()) {
            int ownX = textX + font.width(stored);
            graphics.drawString(font, fit(isFood(item) ? Tr.s("screen.dynamicvillages.trade.keep_each", " · keep %s each", row.keep())
                            : Tr.s("screen.dynamicvillages.trade.keep", " · keep %s", row.keep()),
                    textX + textWidth - ownX), ownX, rowY + 13, ACCENT, false);
        }

        if (!hovered) return null;
        if (mouseX >= switchX && mouseX < switchX + SALE_SWITCH_W
                && mouseY >= switchY && mouseY < switchY + SALE_SWITCH_H) {
            return Tr.s("screen.dynamicvillages.trade.switch.desc", "SELL: may be sold to the merchant.\nKEEP: never sold.\nClick to switch.");
        }
        if (row.count() <= 0) {
            return name + "\n" + Tr.s("screen.dynamicvillages.trade.row.none", "None stored yet. Set it up now:\nKEEP means it is never sold once\nthe village has some.");
        }
        if (!row.allowed()) return name + "\n" + Tr.s("screen.dynamicvillages.trade.row.kept", "%s stored. Set to KEEP,\nso none of it is sold.", row.count());
        if (foodSalesOff(item)) {
            return name + "\n" + Tr.s("screen.dynamicvillages.trade.row.food_off", "%s stored. Selling Spare Food\nis OFF, so none of it is sold.", row.count());
        }
        int today = sellableToday(row, item);
        int waiting = sellable(row, item) - today;
        return name + "\n" + Tr.s("screen.dynamicvillages.trade.row.sells", "%1$s stored. Keeps %2$s,\nso up to %3$s can be sold\nin lots of %4$s.", row.count(), keptBack(row, item), sellable(row, item), MerchantPricing.lotSize(item))
                + " " + priceNote(row)
                + (waiting > 0 ? "\n" + Tr.s("screen.dynamicvillages.trade.row.waiting", "%s wait for a better price.", waiting) : "")
                + "\n" + Tr.s("screen.dynamicvillages.trade.row.click", "Click to change how many to keep.");
    }

    /** A SELL/KEEP switch with its word inside, knob on the far side. */
    private void saleSwitch(GuiGraphics graphics, int x, int y, boolean sell) {
        graphics.fill(x, y, x + SALE_SWITCH_W, y + SALE_SWITCH_H, sell ? GREEN : 0xFF685630);
        int knob = SALE_SWITCH_H - 4;
        int knobX = sell ? x + SALE_SWITCH_W - 2 - knob : x + 2;
        graphics.fill(knobX, y + 2, knobX + knob, y + 2 + knob, TEXT);
        String word = sell ? Tr.s("screen.dynamicvillages.trade.sell", "SELL") : Tr.s("screen.dynamicvillages.trade.keep_word", "KEEP");
        int areaX = sell ? x + 2 : knobX + knob;
        int areaW = SALE_SWITCH_W - 4 - knob;
        graphics.drawString(font, word, areaX + (areaW - font.width(word) + 1) / 2,
                y + (SALE_SWITCH_H - 7) / 2, sell ? 0xFF0F3D1C : KEEP, false);
    }

    private static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    /**
     * The bar under the grid that edits the selected item's keep number;
     * returns hover text when the mouse is on one of its parts.
     */
    @Nullable
    private String saleKeepBar(GuiGraphics graphics, int mouseX, int mouseY) {
        int bx = leftPos + SALE_CARD_X;
        int by = topPos + saleBarTop();
        graphics.fill(bx, by, bx + SALE_BAR_WIDTH, by + SALE_BAR_HEIGHT, ROW);
        GenericSettingsMenu.SaleItem row = selectedSaleRow();
        if (row == null) {
            graphics.drawCenteredString(font, Tr.s("screen.dynamicvillages.trade.bar.pick", "Click an item above to set how many to keep."),
                    bx + SALE_BAR_WIDTH / 2, by + (SALE_BAR_HEIGHT - 8) / 2, MUTED);
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(row.id()).orElse(Items.AIR);
        boolean food = isFood(item);
        boolean own = row.keep() != TradesmanSalePolicy.AUTO;
        boolean active = row.allowed();
        int[] at = saleBarLayout();
        int cy = by + 4;

        graphics.renderFakeItem(new ItemStack(item), bx + 6, cy);
        String label = food ? Tr.s("screen.dynamicvillages.trade.bar.keep_per_villager", "Keep per villager") : Tr.s("screen.dynamicvillages.trade.bar.keep_at_least", "Keep at least");
        int labelX = at[0] - 5 - font.width(label);
        int nameX = bx + 26;
        graphics.drawString(font, fit(item.getDescription().getString(), labelX - 6 - nameX),
                nameX, cy + 4, TEXT, false);
        graphics.drawString(font, label, labelX, cy + 4, active ? TEXT : MUTED, false);

        int button = active ? BUTTON : 0xFF2A2A2A;
        int buttonText = active ? TEXT : MUTED;
        graphics.fill(at[0], cy, at[0] + 18, cy + SALE_BUTTON_H, button);
        graphics.fill(at[2], cy, at[2] + 18, cy + SALE_BUTTON_H, button);
        graphics.drawString(font, "-", at[0] + 7, cy + 4, buttonText, false);
        graphics.drawString(font, "+", at[2] + 6, cy + 4, buttonText, false);
        graphics.fill(at[1], cy, at[1] + SALE_VALUE_W, cy + SALE_BUTTON_H, INSET);
        String value = Integer.toString(shownKeep(row, item));
        graphics.drawString(font, value, at[1] + (SALE_VALUE_W - font.width(value) + 1) / 2, cy + 4,
                active && own ? TEXT : MUTED, false);
        boolean autoOn = active && own;
        graphics.fill(at[3], cy, at[3] + SALE_AUTO_W, cy + SALE_BUTTON_H, autoOn ? BUTTON : 0xFF2A2A2A);
        String auto = Tr.s("screen.dynamicvillages.trade.bar.auto", "Auto");
        graphics.drawString(font, auto, at[3] + (SALE_AUTO_W - font.width(auto) + 1) / 2, cy + 4,
                autoOn ? TEXT : MUTED, false);

        int kept = keptBack(row, item);
        boolean stockWins = row.stockHeld() >= kept && row.stockHeld() > 0;
        String summary;
        if (!active) {
            summary = Tr.s("screen.dynamicvillages.trade.bar.kept", "Set to KEEP, so none of it is sold.");
        } else if (row.count() <= 0) {
            summary = Tr.s("screen.dynamicvillages.trade.bar.none", "None stored yet. This number applies once some comes in.");
        } else if (foodSalesOff(item)) {
            summary = Tr.s("screen.dynamicvillages.trade.bar.food_off", "Selling Spare Food is OFF on the Behaviour tab.");
        } else if (sellableToday(row, item) < sellable(row, item)) {
            // The floor holds some back today: say how much sells and how much waits.
            summary = Tr.s("screen.dynamicvillages.trade.bar.today", "Sells %1$s of %2$s today, %3$s wait for a better price.", sellableToday(row, item), row.count(), sellable(row, item) - sellableToday(row, item));
        } else if (stockWins) {
            summary = Tr.s("screen.dynamicvillages.trade.bar.stock", "The Stock tab holds %1$s back. Up to %2$s of %3$s can be sold.", kept, sellable(row, item), row.count());
        } else if (food) {
            summary = Tr.s("screen.dynamicvillages.trade.bar.food", "Keeps %1$s for %2$s villagers. Up to %3$s of %4$s can be sold.", kept, menu.villagers(), sellable(row, item), row.count());
        } else {
            summary = Tr.s("screen.dynamicvillages.trade.bar.items", "Keeps %1$s. Up to %2$s of %3$s can be sold.", kept, sellable(row, item), row.count());
        }
        graphics.drawString(font, fit(summary, SALE_BAR_WIDTH - 12), bx + 6, by + 26, MUTED, false);

        if (mouseY >= cy && mouseY < cy + SALE_BUTTON_H) {
            if (mouseX >= at[0] && mouseX < at[2] + 18) {
                return food
                        ? Tr.s("screen.dynamicvillages.trade.bar.keep_food.desc", "Keep this many of it for each\nvillager. Only the rest is sold.\nNever lower than 16, because each\nvillager carries up to 16 food.\nGrey means Auto: Food to keep per\nvillager on the Behaviour tab.\nShift-click for steps of 1.")
                        : Tr.s("screen.dynamicvillages.trade.bar.keep_items.desc", "Keep this many in storage.\nOnly the rest is sold.\nGrey means Auto: Keep of each item\non the Behaviour tab.\nShift-click for steps of 1.");
            }
            if (mouseX >= at[3] && mouseX < at[3] + SALE_AUTO_W) {
                return Tr.s("screen.dynamicvillages.trade.bar.auto.desc", "Go back to the number from\nthe Behaviour tab.");
            }
        }
        if (active && mouseY >= by + 24 && mouseY < by + SALE_BAR_HEIGHT
                && mouseX >= bx && mouseX < bx + SALE_BAR_WIDTH) {
            if (foodSalesOff(item)) {
                return Tr.s("screen.dynamicvillages.trade.bar.food_off.desc", "No food is sold while Selling\nSpare Food is OFF. Turn it on in the\nBehaviour tab.");
            }
            String lots = Tr.s("screen.dynamicvillages.trade.bar.lots", "Sold in half stacks, here %s\nat a time, and only if the merchant\nwants it that day.", MerchantPricing.lotSize(item))
                    + " " + priceNote(row) + "\n"
                    + Tr.s("screen.dynamicvillages.trade.bar.lots.floor", "Each lot sold lowers the price.\nUnder the Stop selling floor on\nthe Behaviour tab, the rest waits.");
            if (!stockWins) return lots;
            return Tr.s("screen.dynamicvillages.trade.bar.stock.desc", "The storage room's Stock tab holds\nthis back for your orders and for\nthe cook and blacksmith. To see it,\nopen any storage room chest and\nclick Manage Chest, then Stock.") + "\n" + lots;
        }
        return null;
    }

    /** {minusX, valueX, plusX, autoX} of the keep bar, right-aligned with 6 px to spare. */
    private int[] saleBarLayout() {
        int autoX = leftPos + SALE_CARD_X + SALE_BAR_WIDTH - 6 - SALE_AUTO_W;
        int plusX = autoX - 6 - 18;
        int valueX = plusX - 3 - SALE_VALUE_W;
        int minusX = valueX - 3 - 18;
        return new int[]{minusX, valueX, plusX, autoX};
    }

    @Nullable
    private GenericSettingsMenu.SaleItem selectedSaleRow() {
        if (selectedSale == null) return null;
        for (GenericSettingsMenu.SaleItem row : menu.saleItems()) {
            if (row.id().equals(selectedSale)) return row;
        }
        return null;
    }

    private static boolean isFood(Item item) {
        return VillageItemOwnership.isVillagerFood(new ItemStack(item));
    }

    /** What Auto means for this item right now: the Behaviour tab's number. */
    private int autoKeep(Item item) {
        return isFood(item)
                ? Math.max(TradesmanSettingsSchema.MIN_FOOD_PER_VILLAGER,
                        tradesmanValue(TradesmanSettingsSchema.FOOD_KEEP_PER_VILLAGER))
                : tradesmanValue(TradesmanSettingsSchema.RESERVE_KEEP);
    }

    /** The number in the bar: the item's own, or Auto's. Per villager for food. */
    private int shownKeep(GenericSettingsMenu.SaleItem row, Item item) {
        return row.keep() != TradesmanSalePolicy.AUTO
                ? TradesmanSalePolicy.clampKeep(item, row.keep()) : autoKeep(item);
    }

    /** How many storage holds back, the same rule the tradesman sells by. */
    private int keptBack(GenericSettingsMenu.SaleItem row, Item item) {
        return TradesmanSalePolicy.keptBack(isFood(item), row.keep(),
                tradesmanValue(TradesmanSettingsSchema.RESERVE_KEEP),
                tradesmanValue(TradesmanSettingsSchema.FOOD_KEEP_PER_VILLAGER), menu.villagers(), row.stockHeld());
    }

    /** "Selling Spare Food" on the Behaviour tab is off, so no food is sold at all. */
    private boolean foodSalesOff(Item item) {
        return isFood(item) && !menu.lineFor(TradesmanSettingsSchema.SELL_FOOD_SURPLUS).boolValue();
    }

    /** A number from the Trading rows, found by its id so headings cannot shift it. */
    private int tradesmanValue(String settingId) {
        return menu.lineFor(settingId).intValue();
    }

    /** Units above the keep number, in whole lots at today's price: what the reserves let go. */
    private int sellable(GenericSettingsMenu.SaleItem row, Item item) {
        if (!row.allowed() || foodSalesOff(item)) return 0;
        int units = Math.max(0, row.count() - keptBack(row, item));
        int lot = Math.max(1, MerchantPricing.lotSize(item));
        return units - units % lot;
    }

    /**
     * Of {@link #sellable}, what sells today before the price falls under the
     * "Stop selling below" floor, worked out the same way the tradesman does
     * (MarketMemory.lotsUntilFloor) for the floor set on the Behaviour tab now.
     */
    private int sellableToday(GenericSettingsMenu.SaleItem row, Item item) {
        int units = sellable(row, item);
        int half = Math.max(1, new ItemStack(item).getMaxStackSize() / 2);
        int floorLots = com.alex.test.village.economy.MarketMemory.lotsUntilFloor(row.soldLots(),
                row.otherPaid(), row.basePrice(),
                tradesmanValue(TradesmanSettingsSchema.PRICE_FLOOR), menu.tuning());
        int today = Math.min(units, floorLots * half);
        int lot = Math.max(1, MerchantPricing.lotSize(item));
        return today - today % lot;
    }

    /** Today's price and its share of normal, for the hover lines. {@code livePrice} is in cents. */
    private static String priceNote(GenericSettingsMenu.SaleItem row) {
        int percent = row.basePrice() <= 0 ? 100 : Math.round(row.livePrice() / (float) row.basePrice());
        return percent == 100
                ? Tr.s("screen.dynamicvillages.trade.price", "Today %s a stack.", MerchantPricing.format(row.livePrice()))
                : Tr.s("screen.dynamicvillages.trade.price_changed", "Today %1$s a stack (%2$s%% of normal).", MerchantPricing.format(row.livePrice()), percent);
    }

    /** Steps the selected item's keep number; Auto starts from the Behaviour tab's. */
    private void stepSaleKeep(int direction) {
        GenericSettingsMenu.SaleItem row = selectedSaleRow();
        if (row == null || !row.allowed()) return;
        Item item = BuiltInRegistries.ITEM.getOptional(row.id()).orElse(Items.AIR);
        int step = hasShiftDown() ? 1 : isFood(item) ? 4 : 8;
        int next = TradesmanSalePolicy.clampKeep(item, shownKeep(row, item) + direction * step);
        if (row.keep() != TradesmanSalePolicy.AUTO && next == row.keep()) return;
        setSaleKeep(row, next);
    }

    private void setSaleKeep(GenericSettingsMenu.SaleItem row, int keep) {
        menu.setSaleKeepClient(row.id(), keep);
        PacketDistributor.sendToServer(new GenericSettingsPayloads.SaleKeepUpdate(
                menu.villagerId(), row.id(), keep));
    }

    /** Clicks on the keep bar's buttons. */
    private boolean clickSaleKeepBar(double mouseX, double mouseY) {
        GenericSettingsMenu.SaleItem row = selectedSaleRow();
        int cy = topPos + saleBarTop() + 4;
        if (row == null || mouseY < cy || mouseY >= cy + SALE_BUTTON_H) return false;
        int[] at = saleBarLayout();
        if (mouseX >= at[0] && mouseX < at[0] + 18) {
            stepSaleKeep(-1);
            return true;
        }
        if (mouseX >= at[2] && mouseX < at[2] + 18) {
            stepSaleKeep(1);
            return true;
        }
        if (mouseX >= at[3] && mouseX < at[3] + SALE_AUTO_W) {
            if (row.allowed() && row.keep() != TradesmanSalePolicy.AUTO) setSaleKeep(row, TradesmanSalePolicy.AUTO);
            return true;
        }
        return false;
    }

    /** Newest first, each day opened by a heading that carries that day's totals. */
    private List<LogRow> salesLogRows() {
        List<TradesmanLedger.Entry> sales = menu.ledger().entries();
        java.util.Map<Long, int[]> perDay = new java.util.HashMap<>();
        for (TradesmanLedger.Entry sale : sales) {
            int[] totals = perDay.computeIfAbsent(sale.day(), ignored -> new int[2]);
            totals[0]++;
            totals[1] += sale.emeralds();
        }
        List<LogRow> rows = new ArrayList<>();
        long lastDay = Long.MIN_VALUE;
        for (TradesmanLedger.Entry sale : sales) {
            if (sale.day() != lastDay) {
                lastDay = sale.day();
                int[] totals = perDay.get(lastDay);
                rows.add(new LogRow(lastDay, totals[0], totals[1], null));
            }
            rows.add(new LogRow(lastDay, 0, 0, sale));
        }
        return rows;
    }

    private void renderSalesLog(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x + 8, y + 53, x + TRADESMAN_WIDTH - 8,
                y + salesLogHeight() - 8, INSET);

        TradesmanLedger.Snapshot ledger = menu.ledger();
        int right = x + SALE_SCROLLBAR_X - 2;
        graphics.drawString(font, Tr.s("screen.dynamicvillages.trade.log.earned", "Earned in total: %s emeralds", ledger.totalEmeralds()),
                x + SALE_CARD_X, y + 59, TEXT, false);
        String sold = Tr.s("screen.dynamicvillages.trade.log.sold", "Items sold: %s", ledger.totalItems());
        graphics.drawString(font, sold, right - font.width(sold), y + 59, MUTED, false);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.trade.log.desc", "Sales to the merchant, newest first."),
                x + SALE_CARD_X, y + 71, MUTED, false);

        List<LogRow> rows = salesLogRows();
        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, Tr.s("screen.dynamicvillages.trade.log.none", "No sales yet"),
                    x + TRADESMAN_WIDTH / 2, y + 150, MUTED);
            graphics.drawCenteredString(font, Tr.s("screen.dynamicvillages.trade.log.none.desc", "The tradesman sells when the merchant visits."),
                    x + TRADESMAN_WIDTH / 2, y + 162, MUTED);
            return;
        }
        int maxScroll = Math.max(0, rows.size() - logRows());
        logScroll = Math.min(logScroll, maxScroll);
        int left = x + SALE_CARD_X;
        for (int visible = 0; visible < logRows(); visible++) {
            int index = logScroll + visible;
            if (index >= rows.size()) break;
            LogRow row = rows.get(index);
            int rowY = y + LOG_LIST_TOP + visible * LOG_ROW_STEP;
            if (row.sale() == null) {
                graphics.fill(left, rowY, right, rowY + LOG_ROW_HEIGHT, TAB_SELECTED);
                graphics.drawString(font, Tr.s("screen.dynamicvillages.trade.log.day", "Day %s", row.day()), left + 6, rowY + 5, TEXT, false);
                String totals = row.daySales() == 1
                        ? Tr.s("screen.dynamicvillages.trade.log.day_one", "1 sale, %s emeralds", row.dayEmeralds())
                        : Tr.s("screen.dynamicvillages.trade.log.day_many", "%1$s sales, %2$s emeralds", row.daySales(), row.dayEmeralds());
                graphics.drawString(font, totals, right - 6 - font.width(totals), rowY + 5, KEEP, false);
                continue;
            }
            TradesmanLedger.Entry sale = row.sale();
            Item item = BuiltInRegistries.ITEM.getOptional(sale.item()).orElse(Items.AIR);
            boolean hovered = mouseX >= left && mouseX < right
                    && mouseY >= rowY && mouseY < rowY + LOG_ROW_HEIGHT;
            graphics.fill(left, rowY, right, rowY + LOG_ROW_HEIGHT, hovered ? ROW_HOVER : ROW);
            graphics.renderFakeItem(new ItemStack(item), left + 3, rowY + 1);
            String name = item.getDescription().getString();
            // A tag for a sell box sale and for a price away from normal ("Box 70%").
            String tag = sale.percent() != 100
                    ? (sale.sellBox() ? Tr.s("screen.dynamicvillages.trade.log.box_percent", "Box %s%%", sale.percent()) : sale.percent() + "%")
                    : sale.sellBox() ? Tr.s("screen.dynamicvillages.trade.log.sell_box", "Sell box") : "";
            if (!tag.isEmpty()) {
                int tagColour = sale.percent() < 100 ? 0xFFE07070 : sale.percent() > 100 ? GREEN : KEEP;
                graphics.drawString(font, fit(name, 92), left + 24, rowY + 5, TEXT, false);
                graphics.drawString(font, tag, left + 122, rowY + 5, tagColour, false);
            } else {
                graphics.drawString(font, fit(name, 140), left + 24, rowY + 5, TEXT, false);
            }
            graphics.drawString(font, "x" + sale.count(), left + 172, rowY + 5, MUTED, false);
            graphics.drawString(font, clock(sale.dayTime()), left + 214, rowY + 5, MUTED, false);
            graphics.renderFakeItem(new ItemStack(Items.EMERALD), right - 19, rowY + 1);
            String earned = "+" + sale.emeralds();
            graphics.drawString(font, earned, right - 22 - font.width(earned), rowY + 5, GREEN, false);
        }
        if (maxScroll > 0) {
            int trackTop = y + LOG_LIST_TOP;
            int trackHeight = logRows() * LOG_ROW_STEP - 1;
            int thumbHeight = Math.max(17, trackHeight * logRows() / rows.size());
            int thumbTop = trackTop + (trackHeight - thumbHeight) * logScroll / maxScroll;
            graphics.fill(x + SALE_SCROLLBAR_X, trackTop,
                    x + SALE_SCROLLBAR_X + 4, trackTop + trackHeight, 0xFF333333);
            graphics.fill(x + SALE_SCROLLBAR_X, thumbTop,
                    x + SALE_SCROLLBAR_X + 4, thumbTop + thumbHeight, 0xFF888888);
        }
    }

    /** The world clock as hours and minutes; day time 0 is 06:00. */
    private static String clock(long dayTime) {
        long ticks = Math.floorMod(dayTime, 24_000L);
        long hours = (ticks / 1000 + 6) % 24;
        long minutes = (ticks % 1000) * 60 / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    private static int saleRowCount(int itemCount) {
        return (itemCount + SALE_COLUMNS - 1) / SALE_COLUMNS;
    }

    private static int saleCardX(int column) {
        return SALE_CARD_X + column * (SALE_CARD_WIDTH + SALE_CARD_GAP);
    }

    /**
     * Without a search: goods in storage this village's jobs could be asked
     * for, and what the player set up. A search also finds the other goods a
     * merchant might want, so they can be set to KEEP ahead of time. Things
     * no merchant buys (dirt, sand) are never rows at all.
     */
    private List<GenericSettingsMenu.SaleItem> filteredSaleItems() {
        String query = saleQuery.trim().toLowerCase(Locale.ROOT);
        List<GenericSettingsMenu.SaleItem> matches = new ArrayList<>();
        for (GenericSettingsMenu.SaleItem row : menu.saleItems()) {
            if (query.isEmpty()) {
                if (row.listed()) matches.add(row);
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getOptional(row.id()).orElse(Items.AIR);
            if (item.getDescription().getString().toLowerCase(Locale.ROOT).contains(query)
                    || row.id().toString().toLowerCase(Locale.ROOT).contains(query)) {
                matches.add(row);
            }
        }
        return matches;
    }

    private void tab(
            GuiGraphics graphics, int x, int y, int width, String label,
            boolean selected, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + TAB_H;
        int color = selected ? TAB_SELECTED : hovered ? ROW_HOVER : ROW;
        graphics.fill(x, y, x + width, y + TAB_H, TAB_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + TAB_H, color);
        if (selected) graphics.fill(x + 1, y + TAB_H - 2, x + width - 1, y + TAB_H, ACCENT);
        // An item icon like the guard's tabs, then the name.
        int textRoom = width - TAB_TEXT_X - TAB_TEXT_PAD;
        if (textRoom < font.width("...") + 4) {
            // Folded down to its icon (see tabTargets): the icon centred, the name on hover.
            graphics.renderFakeItem(new ItemStack(SettingsTabIcons.iconFor(menu.schema().profession(), label)),
                    x + (width - 16) / 2, y + (TAB_H - 16) / 2);
            return;
        }
        graphics.renderFakeItem(new ItemStack(SettingsTabIcons.iconFor(menu.schema().profession(), label)),
                x + 4, y + (TAB_H - 16) / 2);
        // Scissored, so a name is uncovered as its tab glides open rather than jumping in.
        graphics.enableScissor(x + TAB_TEXT_X, y, x + width - TAB_TEXT_PAD, y + TAB_H);
        String shown = SettingsText.sectionTitle(menu.schema(), label);
        graphics.drawString(font, fit(shown, Math.max(textRoom, font.width(shown))), x + TAB_TEXT_X, y + 8,
                TEXT, false);
        graphics.disableScissor();
    }

    // -------------------------------------------------------------------------
    // Tab layout. Four tabs split evenly left about 40 pixels for each name, so
    // "Behaviour" showed as "Beha...". Now the tabs share the row three ways,
    // whichever is the first that shows every name in full:
    //   1. evenly, when every name fits that way (most screens, as before);
    //   2. each as wide as its own name, the spare room shared out;
    //   3. folded: the open tab as wide as it likes, the others down to their
    //      icons, their names on hover.
    // Widths glide to their new size when another tab is opened.
    // -------------------------------------------------------------------------

    /** Where a tab's name starts, and the room kept after it. */
    private static final int TAB_TEXT_X = 22;
    private static final int TAB_TEXT_PAD = 4;
    /** A tab folded down to its icon. */
    private static final int TAB_FOLDED = 24;
    /** Share of the way to its target width a tab moves each frame (about 150 ms to settle at 60 fps). */
    private static final float TAB_GLIDE = 0.3f;

    /** The widths on screen now; null until the first frame. */
    @Nullable private float[] tabShown;

    /** Where every tab wants to be, for the tab open now. */
    private int[] tabTargets() {
        int count = menu.sectionCount();
        int room = WIDTH - CONTENT_X * 2 - TAB_GAP * (count - 1);
        int[] out = new int[count];
        int[] natural = new int[count];
        int naturalSum = 0;
        int even = room / count;
        boolean evenFits = true;
        for (int i = 0; i < count; i++) {
            natural[i] = font.width(SettingsText.section(menu.schema(), menu.section(i))) + TAB_TEXT_X + TAB_TEXT_PAD;
            naturalSum += natural[i];
            if (natural[i] > even) evenFits = false;
        }
        if (evenFits) {
            java.util.Arrays.fill(out, even);
        } else if (naturalSum <= room) {
            int spare = room - naturalSum;
            for (int i = 0; i < count; i++) out[i] = natural[i] + spare / count + (i < spare % count ? 1 : 0);
        } else {
            int open = room - TAB_FOLDED * (count - 1);
            for (int i = 0; i < count; i++) out[i] = i == activeTab ? open : TAB_FOLDED;
        }
        return out;
    }

    /** One frame of the glide; snaps once a tab is within a pixel of its target. */
    private void stepTabWidths() {
        int[] target = tabTargets();
        if (tabShown == null || tabShown.length != target.length) {
            tabShown = new float[target.length];
            for (int i = 0; i < target.length; i++) tabShown[i] = target[i];
            return;
        }
        for (int i = 0; i < target.length; i++) {
            float gap = target[i] - tabShown[i];
            tabShown[i] = Math.abs(gap) < 1f ? target[i] : tabShown[i] + gap * TAB_GLIDE;
        }
    }

    private int tabWidth(int index) {
        return tabShown == null ? tabTargets()[index] : Math.round(tabShown[index]);
    }

    private int tabX(int index) {
        int x = leftPos + CONTENT_X;
        for (int i = 0; i < index; i++) x += tabWidth(i) + TAB_GAP;
        return x;
    }

    /** The tab under the mouse, or -1. */
    private int tabAt(double mouseX, double mouseY) {
        if (mouseY < topPos + TAB_Y || mouseY >= topPos + TAB_Y + TAB_H) return -1;
        for (int i = 0; i < menu.sectionCount(); i++) {
            int x = tabX(i);
            if (mouseX >= x && mouseX < x + tabWidth(i)) return i;
        }
        return -1;
    }

    /** A tab's name on hover when the tab cannot show all of it. */
    private void renderTabTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int hovered = tabAt(mouseX, mouseY);
        if (hovered < 0) return;
        String label = SettingsText.section(menu.schema(), menu.section(hovered));
        if (font.width(label) <= tabWidth(hovered) - TAB_TEXT_X - TAB_TEXT_PAD) return;
        SettingsTooltip.render(graphics, font, label, mouseX, mouseY);
    }

    private void toggleRow(
            GuiGraphics graphics, int x, int y, int width,
            GenericSettingsMenu.RowLine line, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + 20;
        graphics.fill(x, y, x + width, y + 20, hovered ? 0xFF303030 : 0xFF282828);
        Item icon = entryItem(line);
        int labelX = x + 7;
        if (icon != null) {
            graphics.renderItem(new ItemStack(icon), x + 4, y + 2);
            labelX = x + 24;
        }
        graphics.drawString(font, fit(line.label(), width - 78 - (labelX - x - 7)), labelX, y + 6, TEXT, false);

        boolean enabled = line.boolValue();
        // The last entry on in a keep-one list is greyed: it cannot be switched off.
        boolean locked = lockedOn(line);
        int switchX = x + width - 43;
        int switchColor = locked ? LOCKED : enabled ? GREEN : 0xFF71312E;
        graphics.fill(switchX, y + 4, switchX + 34, y + 16, switchColor);
        int knobX = enabled ? switchX + 22 : switchX + 2;
        graphics.fill(knobX, y + 5, knobX + 10, y + 15, locked ? 0xFFB0B0B0 : 0xFFFFFFFF);
        graphics.drawString(font, enabled ? Tr.s("screen.dynamicvillages.settings.on", "ON")
                : Tr.s("screen.dynamicvillages.settings.off", "OFF"), switchX - 24, y + 6,
                locked ? 0xFF8A8A8A : enabled ? GREEN : RED, false);
    }

    private static final int LOCKED = 0xFF4B5E4B;

    /** The last entry still on in a {@code keepingOne} list (the server refuses switching it off too). */
    private boolean lockedOn(GenericSettingsMenu.RowLine line) {
        if (!line.row().keepsOne() || line.entryId() == null || !line.boolValue()) return false;
        for (GenericSettingsMenu.RowLine other : menu.linesForSection(activeTab)) {
            if (other.boolValue() && other.row().settingId().equals(line.row().settingId())
                    && !line.entryId().equals(other.entryId())) return false;
        }
        return true;
    }

    private void stepperRow(
            GuiGraphics graphics, int x, int y, int width,
            GenericSettingsMenu.RowLine line, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + 20;
        graphics.fill(x, y, x + width, y + 20, hovered ? 0xFF303030 : 0xFF282828);
        int[] layout = stepperLayout(line, x, width);
        int minusX = layout[0];
        int valueX = layout[1];
        int valueWidth = layout[2];
        int plusX = layout[3];
        graphics.drawString(font, fit(line.label(), minusX - x - 12), x + 7, y + 6, TEXT, false);

        graphics.fill(minusX, y + 3, minusX + 18, y + 17, BUTTON);
        graphics.fill(plusX, y + 3, plusX + 18, y + 17, BUTTON);
        graphics.drawString(font, "-", minusX + 7, y + 6, TEXT, false);
        graphics.drawString(font, "+", plusX + 6, y + 6, TEXT, false);

        String value = line.row().kind() == SettingsSchema.RowKind.CHOICE
                ? SettingsText.option(menu.schema(), line.row(), line.row().tierNames().get(Math.clamp(line.intValue(),
                        0, line.row().tierNames().size() - 1)))
                : line.row().ticksAsSeconds() ? seconds(line.intValue())
                : Integer.toString(line.intValue());
        graphics.drawString(font, value, valueX + (valueWidth - font.width(value)) / 2, y + 6, TEXT, false);
    }

    /** Ticks as seconds with at most one decimal: 60 is "3s", 50 is "2.5s". */
    private static String seconds(int ticks) {
        int tenths = Math.round(ticks / 2f);
        return tenths % 10 == 0 ? tenths / 10 + "s" : tenths / 10 + "." + tenths % 10 + "s";
    }

    /**
     * {minusX, valueX, valueWidth, plusX} for a stepper row. A choice row's value
     * area is as wide as its longest option, so words never run under the buttons.
     */
    private int[] stepperLayout(GenericSettingsMenu.RowLine line, int x, int width) {
        int valueWidth = 30;
        if (line.row().kind() == SettingsSchema.RowKind.CHOICE) {
            for (String option : line.row().tierNames()) {
                valueWidth = Math.max(valueWidth,
                        font.width(SettingsText.option(menu.schema(), line.row(), option)) + 8);
            }
        }
        int plusX = x + width - 23;
        int valueX = plusX - 3 - valueWidth;
        int minusX = valueX - 3 - 18;
        return new int[]{minusX, valueX, valueWidth, plusX};
    }

    /**
     * The item a "products" or miner's "wanted ores" allow-list entry names
     * (their ids are vanilla item paths), or null. Other allow-lists hold mob
     * and block ids, some of which share a name with an item, so only item
     * lists get icons.
     */
    @Nullable
    private static Item entryItem(GenericSettingsMenu.RowLine line) {
        if (line.row().kind() != SettingsSchema.RowKind.ALLOW_LIST || line.entryId() == null) return null;
        String setting = line.row().settingId();
        if (!"products".equals(setting)
                && !com.alex.test.village.villagers.miner.settings.MinerSettingsSchema.WANTED_ORES.equals(setting)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(line.entryId());
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
        return item == Items.AIR ? null : item;
    }

    /** Wrapped hover text for the row under the mouse, from the schema row's description. */
    private void renderRowTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int rowX = leftPos + 10;
        int rowWidth = rowWidth();
        if (mouseX < rowX || mouseX >= rowX + rowWidth
                || mouseY < topPos + LIST_TOP || mouseY >= topPos + LIST_BOTTOM) return;
        int offset = (mouseY - topPos - LIST_TOP) / ROW_HEIGHT;
        if ((mouseY - topPos - LIST_TOP) % ROW_HEIGHT >= 20) return;
        List<GenericSettingsMenu.RowLine> lines = menu.linesForSection(activeTab);
        int index = currentScroll() + offset;
        if (index >= lines.size()) return;
        GenericSettingsMenu.RowLine line = lines.get(index);
        if (line.isHeader()) return;
        String description = SettingsText.description(menu.schema(), line.row());
        if (line.entryId() != null) {
            for (SettingsSchema.AllowListEntry entry : line.row().allowListEntries()) {
                if (entry.id().equals(line.entryId()) && !entry.description().isEmpty()) {
                    description = SettingsText.entryDescription(menu.schema(), line.row(), entry);
                    break;
                }
            }
        }
        if (lockedOn(line)) {
            description += (description.isEmpty() ? "" : "\n")
                    + Tr.s("screen.dynamicvillages.settings.keep_one", "Keep at least one on:\nthis is the last one.");
        }
        SettingsTooltip.render(graphics, font, description, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (saleSearch != null && saleSearch.visible && saleSearch.isFocused()
                && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            saleSearch.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isTradesman()) {
            if (button == 0 && mouseY >= topPos + TAB_Y && mouseY < topPos + TAB_Y + TAB_H) {
                for (int page = 0; page < 3; page++) {
                    if (mouseX >= tradesmanTabX(page) && mouseX < tradesmanTabX(page) + TRADESMAN_TAB_WIDTH) {
                        showTradesmanPage(page);
                        return true;
                    }
                }
            }
            if (showingSaleItems) {
                if (button == 0 && mouseX >= leftPos + SALE_SCROLLBAR_X - 1
                        && mouseX < leftPos + SALE_SCROLLBAR_X + 5
                        && mouseY >= topPos + SALE_LIST_TOP
                        && mouseY < topPos + SALE_LIST_TOP + saleRows() * SALE_ROW_STEP
                        && saleRowCount(filteredSaleItems().size()) > saleRows()) {
                    if (saleSearch != null) saleSearch.setFocused(false);
                    draggingSaleScrollbar = true;
                    updateSaleScrollFromMouse(mouseY);
                    return true;
                }
                if (button == 0 && mouseX >= leftPos + SALE_CARD_X
                        && mouseX < leftPos + saleCardX(SALE_COLUMNS - 1) + SALE_CARD_WIDTH
                        && mouseY >= topPos + SALE_LIST_TOP
                        && mouseY < topPos + SALE_LIST_TOP + saleRows() * SALE_ROW_STEP) {
                    int visible = ((int) mouseY - topPos - SALE_LIST_TOP) / SALE_ROW_STEP;
                    int rowY = topPos + SALE_LIST_TOP + visible * SALE_ROW_STEP;
                    int column = ((int) mouseX - leftPos - SALE_CARD_X)
                            / (SALE_CARD_WIDTH + SALE_CARD_GAP);
                    int cardX = leftPos + saleCardX(column);
                    int index = (saleScroll + visible) * SALE_COLUMNS + column;
                    List<GenericSettingsMenu.SaleItem> matches = filteredSaleItems();
                    if (mouseX < cardX + SALE_CARD_WIDTH
                            && mouseY < rowY + SALE_ROW_HEIGHT && index < matches.size()) {
                        if (saleSearch != null) saleSearch.setFocused(false);
                        GenericSettingsMenu.SaleItem row = matches.get(index);
                        selectedSale = row.id();
                        int switchX = cardX + SALE_CARD_WIDTH - SALE_PAD - SALE_SWITCH_W;
                        if (mouseX >= switchX) {
                            boolean allowed = !row.allowed();
                            menu.setSaleItemClient(row.id(), allowed);
                            PacketDistributor.sendToServer(new GenericSettingsPayloads.SaleItemUpdate(
                                    menu.villagerId(), row.id(), allowed));
                        }
                        return true;
                    }
                }
                if (button == 0 && clickSaleKeepBar(mouseX, mouseY)) {
                    if (saleSearch != null) saleSearch.setFocused(false);
                    return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }
            if (button == 0 && clickTradesmanRow(mouseX, mouseY)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            int clicked = tabAt(mouseX, mouseY);
            if (clicked >= 0) {
                showTab(clicked);
                return true;
            }
        }
        // Before the scrollbar: the Music tab's lines are many, but it draws no list or scrollbar.
        if (onBardMusic()) {
            if (button == 0 && bardMusic.mouseClicked(leftPos, topPos, mouseX, mouseY)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (button == 0 && maxScroll() > 0 && insideScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            updateScrollFromMouse(mouseY);
            return true;
        }

        int rowX = leftPos + 10;
        int rowWidth = rowWidth();
        if (button == 0 && mouseX >= rowX && mouseX < rowX + rowWidth
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM) {
            List<GenericSettingsMenu.RowLine> lines = menu.linesForSection(activeTab);
            int index = currentScroll() + ((int) mouseY - topPos - LIST_TOP) / ROW_HEIGHT;
            if (index < lines.size()) {
                GenericSettingsMenu.RowLine line = lines.get(index);
                if (line.isHeader()) return true;
                if (isStepper(line)) {
                    int[] layout = stepperLayout(line, rowX, rowWidth);
                    int minusX = layout[0];
                    int plusX = layout[3];
                    int step = line.row().stepInt();
                    int next = line.intValue();
                    if (mouseX >= minusX && mouseX < minusX + 18) {
                        next -= step;
                    } else if (mouseX >= plusX && mouseX < plusX + 18) {
                        next += step;
                    }
                    if (next != line.intValue()) {
                        menu.setIntClient(activeTab, index, next);
                        sendUpdate(activeTab, index);
                        return true;
                    }
                } else {
                    if (lockedOn(line)) return true;
                    menu.setBoolClient(activeTab, index, !line.boolValue());
                    sendUpdate(activeTab, index);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean clickTradesmanRow(double mouseX, double mouseY) {
        int row = tradesmanRowAt(mouseX, mouseY);
        if (row < 0) return false;
        int rowX = leftPos + 10;
        int rowWidth = TRADESMAN_WIDTH - 20;
        int rowY = topPos + TRADESMAN_ROW_TOP + row * TRADESMAN_ROW_STEP;
        int section = tradesmanSection(row);
        int lineIndex = tradesmanLineIndex(row);
        GenericSettingsMenu.RowLine line = menu.line(section, lineIndex);
        if (line.isHeader()) return true;
        if (isStepper(line)) {
            int minusX = rowX + rowWidth - 83;
            int plusX = rowX + rowWidth - 23;
            int next = line.intValue();
            if (mouseY >= rowY + 4 && mouseY < rowY + 18) {
                if (mouseX >= minusX && mouseX < minusX + 18)
                    next -= line.row().stepInt();
                else if (mouseX >= plusX && mouseX < plusX + 18)
                    next += line.row().stepInt();
            }
            next = Math.max(line.row().minInt(), Math.min(line.row().maxInt(), next));
            if (next != line.intValue()) {
                menu.setIntClient(section, lineIndex, next);
                sendUpdate(section, lineIndex);
            }
        } else {
            menu.setBoolClient(section, lineIndex, !line.boolValue());
            sendUpdate(section, lineIndex);
        }
        return true;
    }

    /**
     * The Settings page shows the schema's two sections one after the other:
     * Behaviour (Working, Selling Spare Food), then Trading (three numbers).
     */
    private static int tradesmanSection(int slot) {
        return slot < 2 ? 0 : 1;
    }

    private static int tradesmanLineIndex(int slot) {
        return slot < 2 ? slot : slot - 2;
    }

    private GenericSettingsMenu.RowLine tradesmanLine(int slot) {
        return menu.line(tradesmanSection(slot), tradesmanLineIndex(slot));
    }

    private int tradesmanRowAt(double mouseX, double mouseY) {
        int rowX = leftPos + 10;
        if (mouseX < rowX || mouseX >= rowX + TRADESMAN_WIDTH - 20) return -1;
        for (int row = 0; row < TRADESMAN_ROWS; row++) {
            int rowY = topPos + TRADESMAN_ROW_TOP + row * TRADESMAN_ROW_STEP;
            if (mouseY >= rowY && mouseY < rowY + TRADESMAN_ROW_HEIGHT) return row;
        }
        return -1;
    }

    private void sendUpdate(int sectionIndex, int lineIndex) {
        GenericSettingsMenu.RowLine line = menu.line(sectionIndex, lineIndex);
        PacketDistributor.sendToServer(new GenericSettingsPayloads.Update(
                menu.villagerId(), line.row().settingId(), line.encodeValue()));
    }

    private static boolean isStepper(GenericSettingsMenu.RowLine line) {
        return line.row().kind() == SettingsSchema.RowKind.NUMERIC_STEPPER
                || line.row().kind() == SettingsSchema.RowKind.CHOICE;
    }

    @Override
    public boolean mouseDragged(
            double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingSaleScrollbar) {
            updateSaleScrollFromMouse(mouseY);
            return true;
        }
        if (button == 0 && draggingScrollbar) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        if (button == 0 && onBardMusic() && bardMusic.mouseDragged(topPos, mouseY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingSaleScrollbar) {
            draggingSaleScrollbar = false;
            return true;
        }
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        if (button == 0 && bardMusic != null && bardMusic.mouseReleased()) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isTradesman()) {
            if (showingSalesLog && mouseX >= leftPos + SALE_CARD_X
                    && mouseX < leftPos + SALE_SCROLLBAR_X + 4
                    && mouseY >= topPos + LOG_LIST_TOP
                    && mouseY < topPos + LOG_LIST_TOP + logRows() * LOG_ROW_STEP) {
                int max = Math.max(0, salesLogRows().size() - logRows());
                logScroll = Math.max(0, Math.min(max, logScroll + (scrollY < 0 ? 1 : -1)));
                return true;
            }
            if (showingSaleItems && mouseX >= leftPos + SALE_CARD_X
                    && mouseX < leftPos + SALE_SCROLLBAR_X + 4
                    && mouseY >= topPos + SALE_LIST_TOP
                    && mouseY < topPos + SALE_LIST_TOP + saleRows() * SALE_ROW_STEP) {
                int max = Math.max(0,
                        saleRowCount(filteredSaleItems().size()) - saleRows());
                saleScroll = Math.max(0, Math.min(max, saleScroll + (scrollY < 0 ? 1 : -1)));
                return true;
            }
            if (showingSaleItems && selectedSaleRow() != null) {
                int[] at = saleBarLayout();
                int cy = topPos + saleBarTop() + 4;
                if (mouseX >= at[0] && mouseX < at[2] + 18 && mouseY >= cy && mouseY < cy + SALE_BUTTON_H) {
                    stepSaleKeep(scrollY > 0 ? 1 : -1);
                    return true;
                }
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (onBardMusic()) {
            if (bardMusic.mouseScrolled(leftPos, topPos, mouseX, mouseY, scrollY)) return true;
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (mouseX >= leftPos + CONTENT_X
                && mouseX < leftPos + WIDTH - 8
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM) {
            setCurrentScroll(Math.max(0, Math.min(maxScroll(), currentScroll() + (scrollY < 0 ? 1 : -1))));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, TEXT, false);
    }

    private int visibleRows() {
        return (LIST_BOTTOM - LIST_TOP) / ROW_HEIGHT;
    }

    private int maxScroll() {
        return Math.max(0, menu.linesForSection(activeTab).size() - visibleRows());
    }

    private int rowWidth() {
        return maxScroll() > 0 ? WIDTH - 26 : WIDTH - 20;
    }

    private int currentScroll() {
        return sectionScroll[activeTab];
    }

    private void setCurrentScroll(int value) {
        sectionScroll[activeTab] = value;
    }

    private boolean insideScrollbar(double mouseX, double mouseY) {
        return mouseX >= leftPos + WIDTH - 16 && mouseX < leftPos + WIDTH - 8
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM;
    }

    private void updateScrollFromMouse(double mouseY) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return;
        int rowCount = menu.linesForSection(activeTab).size();
        int trackY = topPos + LIST_TOP;
        int trackHeight = LIST_BOTTOM - LIST_TOP - SCROLLBAR_BOTTOM_PADDING;
        int thumbHeight = Math.max(18, trackHeight * visibleRows() / rowCount);
        double t = (mouseY - trackY - thumbHeight / 2.0) / Math.max(1, trackHeight - thumbHeight);
        setCurrentScroll(Math.max(0, Math.min(maxScroll, (int) Math.round(t * maxScroll))));
    }

    private void updateSaleScrollFromMouse(double mouseY) {
        int rows = saleRowCount(filteredSaleItems().size());
        int max = rows - saleRows();
        if (max <= 0) return;
        int trackHeight = saleRows() * SALE_ROW_STEP - 2;
        int thumbHeight = Math.max(17, trackHeight * saleRows() / rows);
        double t = (mouseY - topPos - SALE_LIST_TOP - thumbHeight / 2.0)
                / Math.max(1, trackHeight - thumbHeight);
        saleScroll = Math.max(0, Math.min(max, (int) Math.round(t * max)));
    }

    private String fit(String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
    }

    private void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, BORDER);
        graphics.fill(x, y, x + width, y + height, BODY);
    }
}
