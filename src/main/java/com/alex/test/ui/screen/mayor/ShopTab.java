package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import com.alex.test.network.MayorTradePayloads;
import com.alex.test.town.MayorShop;
import com.alex.test.town.ShopTokenDescriptions;
import com.alex.test.town.TradeOfferEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * The Shop tab: Buildings | Professions | Traversal on top with the emerald balance at the right end, and
 * two columns of tokens to buy. Traversal has nothing to sell yet. Only this village's emeralds pay
 * ({@link MayorShop#villageEmeralds}).
 */
final class ShopTab extends MayorPart {

    /** The section buttons, in their order from the left. */
    enum Section { BUILDINGS, PROFESSIONS, TRAVERSAL }

    private static final int SHOP_NAV_Y = INFO_SELECTOR_Y;
    private static final int SHOP_LIST_Y = 88;
    private static final int SHOP_CARD_H = 28;
    private static final int SHOP_ROW_STEP = 30;
    private static final int FULL_SHOP_ROWS = 6;
    /** A shop card's hover text wraps at this width. */
    private static final int SHOP_TOOLTIP_W = 200;
    /** 6 inside the body, where every tab's lists start. */
    private static final int SHOP_X = CONTENT_X + 6;
    private static final int SHOP_NAV_GAP = 4;
    /** The balance needs about this much; it also gets what the equal section buttons leave over. */
    private static final int SHOP_BALANCE_MIN_W = 54;
    private static final int SHOP_BUY_W = 44;
    private static final int SHOP_BUY_H = 16;

    private Section section = Section.BUILDINGS;
    private int listScroll;

    /** The cards. */
    final ScrollList list = new ScrollList() {
        @Override public boolean shown() { return screen.tab() == MayorStatsScreen.Tab.SHOP; }
        @Override public int x() { return left() + SHOP_X; }
        @Override public int y() { return top() + SHOP_LIST_Y; }
        @Override public int width() { return listW(true); }
        @Override public int height() { return listH(); }
        @Override public boolean sideBar() { return true; }
        @Override public int scroll() { return listScroll; }
        @Override public void setScroll(int value) { listScroll = value; }
        @Override public int maxScroll() { return Math.max(0, rowCount(offers().size()) - visibleRows()); }
        @Override public int thumbHeight() { return thumbHeightFor(rowCount(offers().size())); }
    };

    ShopTab(MayorStatsScreen screen) {
        super(screen);
    }

    // ---------------------------------------------------------------------
    // Widths (from the window's, each time; 356 wide: buttons 87, balance 55, cards 159)
    // ---------------------------------------------------------------------

    /** The section buttons share the row equally; the balance at its right end gets what they leave. */
    private int navW() {
        int sections = Section.values().length;
        return (innerW() - SHOP_BALANCE_MIN_W - SHOP_NAV_GAP * sections) / sections;
    }

    /** The balance's left edge from the shop's. */
    private int balanceX() {
        return Section.values().length * (navW() + SHOP_NAV_GAP);
    }

    private int balanceW() {
        return innerW() - balanceX();
    }

    /** Two cards a row in the list, which always keeps its scrollbar's place. */
    private int cardW() {
        return (listW(true) - SHOP_CARD_INSET * 2 - SHOP_CARD_GAP * (SHOP_COLUMNS - 1)) / SHOP_COLUMNS;
    }

    // ---------------------------------------------------------------------
    // Fitting small screens
    // ---------------------------------------------------------------------

    private int visibleRows() {
        return FULL_SHOP_ROWS - screen.rowsLost(SHOP_ROW_STEP, FULL_SHOP_ROWS, 2);
    }

    private int listH() {
        return SHOP_ROW_STEP * visibleRows();
    }

    /** How much shorter the window is drawn on this tab. */
    int listCut() {
        return (FULL_SHOP_ROWS - visibleRows()) * SHOP_ROW_STEP;
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private List<TradeOfferEntry> offers() {
        // Nothing the server sells belongs to Traversal yet.
        if (section == Section.TRAVERSAL) return List.of();
        boolean profession = section == Section.PROFESSIONS;
        List<TradeOfferEntry> filtered = new ArrayList<>();
        for (TradeOfferEntry offer : menu.snapshot().offers()) {
            if (offer.profession() == profession) filtered.add(offer);
        }
        // Cheapest first, by the price shown; equal prices keep the catalogue's order (sort is stable).
        filtered.sort(Comparator.comparingInt(TradeOfferEntry::price));
        return filtered;
    }

    private int emeralds() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null
                ? 0 : MayorShop.villageEmeralds(minecraft.player.getInventory(), menu.villageId());
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left() + SHOP_X;
        int y = top();
        int held = emeralds();

        String balance = Integer.toString(held);
        int balanceX = x + balanceX();
        int balanceY = y + SHOP_NAV_Y;
        int balanceContentX = balanceX + (balanceW() - 20 - font.width(balance)) / 2;
        graphics.fill(balanceX, balanceY, balanceX + balanceW(),
                balanceY + INFO_SELECTOR_H, PANEL_BORDER);
        graphics.fill(balanceX + 1, balanceY + 1, balanceX + balanceW() - 1,
                balanceY + INFO_SELECTOR_H - 1, ROW);
        graphics.renderFakeItem(new ItemStack(Items.EMERALD), balanceContentX, balanceY + 4);
        graphics.drawString(font, balance, balanceContentX + 20, balanceY + 8, GOLD, false);

        for (Section target : Section.values()) {
            drawSectionButton(graphics, target, x + sectionX(target), y + SHOP_NAV_Y, mouseX, mouseY);
        }

        List<TradeOfferEntry> entries = offers();
        int maxScroll = Math.max(0, rowCount(entries.size()) - visibleRows());
        listScroll = Math.min(listScroll, maxScroll);
        int width = listW(true);
        int cardW = cardW();
        int listY = y + SHOP_LIST_Y;
        drawListFrame(graphics, x, listY, width, listH());
        if (section == Section.TRAVERSAL) {
            drawEmpty(graphics, x, listY, width, listH(),
                    Tr.s("screen.dynamicvillages.mayor.shop.traversal_empty", "Nothing to buy here yet."));
        }

        String hoveredDescription = null;
        graphics.enableScissor(x + 1, listY + 1, x + width - 1, listY + listH() - 1);
        for (int row = 0; row < visibleRows(); row++) {
            for (int column = 0; column < SHOP_COLUMNS; column++) {
                int index = (listScroll + row) * SHOP_COLUMNS + column;
                if (index >= entries.size()) break;
                int cardX = cardX(x, column, cardW);
                int cardY = listY + 1 + row * SHOP_ROW_STEP;
                TradeOfferEntry offer = entries.get(index);
                renderCard(graphics, offer, cardX, cardY, cardW, held, mouseX, mouseY);
                if (inside(mouseX, mouseY, cardX, cardY, cardW, SHOP_CARD_H)) {
                    hoveredDescription = description(offer);
                }
            }
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int barX = x + width + SHOP_SCROLLBAR_GAP;
            int thumbH = thumbHeightFor(rowCount(entries.size()));
            int thumbY = listY + (listH() - thumbH) * listScroll / maxScroll;
            graphics.fill(barX, listY, barX + SHOP_SCROLLBAR_W,
                    listY + listH(), 0xFF333333);
            graphics.fill(barX, thumbY, barX + SHOP_SCROLLBAR_W,
                    thumbY + thumbH, 0xFF888888);
        }

        if (hoveredDescription != null) {
            // Wrapped: the building cards say how to build, and one line ran off the screen.
            graphics.renderTooltip(font, font.split(Component.literal(hoveredDescription), SHOP_TOOLTIP_W),
                    mouseX, mouseY);
        } else if (mouseX >= balanceX && mouseX < balanceX + balanceW()
                && mouseY >= balanceY && mouseY < balanceY + INFO_SELECTOR_H) {
            // Only this village's emeralds pay: say which ones, and how many others are ignored.
            List<Component> lines = new ArrayList<>(List.of(join(
                    new Component[] {Tr.c("screen.dynamicvillages.mayor.balance.spendable", "Emeralds you can spend here: %s", held).withStyle(ChatFormatting.GOLD)},
                    linesOf(Tr.s("screen.dynamicvillages.mayor.balance.desc", "The Mayor only takes emeralds\nwith this village's green name and ID.")),
                    linesOf(Tr.s("screen.dynamicvillages.mayor.balance.earn", "Your village earns them by selling\nto the merchant."), ChatFormatting.GRAY))));
            Minecraft minecraft = Minecraft.getInstance();
            int other = minecraft.player == null
                    ? 0 : MayorShop.countCurrency(minecraft.player.getInventory()) - held;
            if (other > 0) {
                lines.add(Tr.c("screen.dynamicvillages.mayor.balance.other", "You carry %s other emeralds.", other).withStyle(ChatFormatting.RED));
                lines.add(Tr.c("screen.dynamicvillages.mayor.the_mayor_will_not_take_those", "The Mayor will not take those.").withStyle(ChatFormatting.RED));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    /** A section button's left edge from the shop's. Drawing and clicking both use it. */
    private int sectionX(Section target) {
        return target.ordinal() * (navW() + SHOP_NAV_GAP);
    }

    private static String sectionLabel(Section target) {
        return switch (target) {
            case BUILDINGS -> Tr.s("screen.dynamicvillages.mayor.buildings", "Buildings");
            case PROFESSIONS -> Tr.s("screen.dynamicvillages.mayor.professions_tab", "Professions");
            case TRAVERSAL -> Tr.s("screen.dynamicvillages.mayor.shop.traversal", "Traversal");
        };
    }

    private static Item sectionIcon(Section target) {
        return switch (target) {
            case BUILDINGS -> Items.CHEST;
            case PROFESSIONS -> Items.LECTERN;
            case TRAVERSAL -> Items.MINECART;
        };
    }

    /**
     * A section button, styled like the Info tab's cards, but with its icon and name centred: three buttons
     * leave about 58 px beside a left icon, and "Professions" needs 61. A name too long even so is cut.
     */
    private void drawSectionButton(GuiGraphics graphics, Section target, int x, int y, int mouseX, int mouseY) {
        boolean selected = section == target;
        boolean hovered = inside(mouseX, mouseY, x, y, navW(), INFO_SELECTOR_H);
        graphics.fill(x, y, x + navW(), y + INFO_SELECTOR_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + navW() - 1, y + INFO_SELECTOR_H - 1,
                selected ? ROW_SELECTED : hovered ? ROW_HOVER : ROW);
        if (selected) graphics.fill(x + 1, y + INFO_SELECTOR_H - 2, x + navW() - 1, y + INFO_SELECTOR_H, ACCENT);
        // Icon, a 4 px gap, the name; at least 2 px free inside each side (87 px in a 356 window: "Professions" leaves 3).
        String label = trim(sectionLabel(target), navW() - 4 - 16 - 4);
        int contentX = x + (navW() - (16 + 4 + font.width(label))) / 2;
        graphics.renderFakeItem(new ItemStack(sectionIcon(target)), contentX, y + 4);
        graphics.drawString(font, label, contentX + 16 + 4, y + 8, selected ? TEXT : MUTED, false);
    }

    private static int rowCount(int offerCount) {
        return (offerCount + SHOP_COLUMNS - 1) / SHOP_COLUMNS;
    }

    private int thumbHeightFor(int rowCount) {
        return Math.max(18, listH() * visibleRows() / rowCount);
    }

    private static String description(TradeOfferEntry offer) {
        String description = ShopTokenDescriptions.forItem(offer.itemId());
        if (description.isEmpty()) {
            description = offer.profession() ? Tr.s("screen.dynamicvillages.mayor.shop.assigns", "Assigns this profession.") : Tr.s("screen.dynamicvillages.mayor.shop.registers", "Registers this building type.");
        }
        return offer.hint().isEmpty() ? description
                : description + " " + capitalize(offer.hint()) + ".";
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return "";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void renderCard(GuiGraphics graphics, TradeOfferEntry offer, int x, int y, int cardW,
                            int held, int mouseX, int mouseY) {
        boolean affordable = held >= offer.price();
        boolean hovered = inside(mouseX, mouseY, x, y, cardW, SHOP_CARD_H);
        graphics.fill(x, y, x + cardW, y + SHOP_CARD_H, hovered ? ROW_HOVER : ROW);
        if (hovered && affordable) graphics.fill(x, y, x + 2, y + SHOP_CARD_H, ACCENT);
        graphics.renderFakeItem(icon(offer.itemId()), x + 4, y + 6);

        int buttonX = x + cardW - SHOP_BUY_W - 5;
        graphics.drawString(font, trim(offer.displayName(), buttonX - x - 30),
                x + 25, y + 4, TEXT, false);
        graphics.renderFakeItem(new ItemStack(Items.EMERALD), x + 24, y + 10);
        graphics.drawString(font, Integer.toString(offer.price()), x + 41, y + 15,
                affordable ? GOLD : MUTED, false);

        boolean buttonHover = affordable && inside(mouseX, mouseY,
                buttonX, y + 6, SHOP_BUY_W, SHOP_BUY_H);
        graphics.fill(buttonX, y + 6, buttonX + SHOP_BUY_W, y + 6 + SHOP_BUY_H,
                !affordable ? 0xFF242824 : buttonHover ? 0xFF4E6F53 : 0xFF3B4E3E);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.buy", "Buy"), buttonX + (SHOP_BUY_W - font.width(Tr.s("screen.dynamicvillages.mayor.buy", "Buy"))) / 2,
                y + 10, affordable ? TEXT : MUTED, false);
    }

    // ---------------------------------------------------------------------
    // Clicks
    // ---------------------------------------------------------------------

    boolean mouseClicked(double mouseX, double mouseY) {
        int shopX = left() + SHOP_X;
        int y = top();
        for (Section target : Section.values()) {
            if (inside(mouseX, mouseY, shopX + sectionX(target), y + SHOP_NAV_Y, navW(), INFO_SELECTOR_H)) {
                section = target;
                listScroll = 0;
                return true;
            }
        }
        return clickCards(mouseX, mouseY);
    }

    /** Buy buttons; the rest of the list swallows the click. */
    private boolean clickCards(double mouseX, double mouseY) {
        int x = left() + SHOP_X;
        int listY = top() + SHOP_LIST_Y;
        List<TradeOfferEntry> entries = offers();
        int width = listW(true);
        int cardW = cardW();
        if (inside(mouseX, mouseY, x, listY, width, listH())) {
            for (int row = 0; row < visibleRows(); row++) {
                for (int column = 0; column < SHOP_COLUMNS; column++) {
                    int index = (listScroll + row) * SHOP_COLUMNS + column;
                    if (index >= entries.size()) break;
                    int cardX = cardX(x, column, cardW);
                    int cardY = listY + 1 + row * SHOP_ROW_STEP;
                    int buttonX = cardX + cardW - SHOP_BUY_W - 5;
                    if (inside(mouseX, mouseY, buttonX, cardY + 6,
                            SHOP_BUY_W, SHOP_BUY_H)) {
                        TradeOfferEntry offer = entries.get(index);
                        if (emeralds() >= offer.price()) {
                            PacketDistributor.sendToServer(new MayorTradePayloads.Buy(menu.mayorId(), offer.itemId()));
                        }
                        return true;
                    }
                }
            }
            return true;
        }

        return false;
    }
}
