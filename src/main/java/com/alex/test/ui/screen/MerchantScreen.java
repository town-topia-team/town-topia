package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.network.MerchantTradePayloads;
import com.alex.test.ui.MerchantMenu;
import com.alex.test.village.visitors.MerchantCatalog;
import com.alex.test.village.visitors.MerchantOffer;
import com.alex.test.village.visitors.MerchantPricing;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** Paged icon grid with one quantity selector and one sell action. */
public final class MerchantScreen extends AbstractContainerScreen<MerchantMenu> {
    private static final int WIDTH = 356, HEIGHT = 244;
    private static final int PAGE_SIZE = 24;
    private static final int TEXT = 0xFFF3F3F3, MUTED = 0xFFAAAAAA;
    private static final int ACCENT = 0xFF6BAF72;
    /** A price under its base after recent sales, and one above it. */
    private static final int PRICE_DOWN = 0xFFE07070, PRICE_UP = 0xFF8BE08B;
    private static final int BORDER = 0xFF454545, OUTER = 0xFF080808;
    private static final int PANEL = 0xFF202020, PANEL_DARK = 0xFF141414;
    private static final int CARD = 0xFF292929, HOVER = 0xFF383838;
    private static final int SELECTED = 0xFF3C5641;
    private static final int GRID_X = 12, GRID_Y = 68;
    private static final int GRID_W = 332, GRID_H = 100;
    private static final int GRID_COLUMNS = 8;
    private static final int CARD_W = 34, CARD_H = 30, CARD_GAP = 4;
    private static final int DETAIL_Y = 175;

    private record Tile(int x, int y) {}

    private int page;
    private int selected;
    private boolean halfStack;
    private int lastAttempt = -1;

    public MerchantScreen(MerchantMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelY = 1000;
    }

    private int pageCount() {
        return Math.max(1, (menu.offers().size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        String changeTooltip = null;
        page = Math.min(page, pageCount() - 1);
        if (!menu.offers().isEmpty()) selected = Math.min(selected, menu.offers().size() - 1);
        graphics.fill(x - 2, y - 2, x + WIDTH + 2, y + HEIGHT + 2, OUTER);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, PANEL);
        graphics.fill(x + 5, y + 5, x + WIDTH - 5, y + HEIGHT - 5, PANEL_DARK);
        frame(graphics, x + 12, y + 12, 332, 32, PANEL);
        graphics.renderFakeItem(new ItemStack(Items.CHEST), x + 18, y + 20);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.merchant.title", "Traveling Merchant"), x + 42, y + 24, TEXT, false);
        if (menu.purse() == 0) {
            String outLabel = Tr.s("screen.dynamicvillages.merchant.out_of_emeralds", "Merchant is out of emeralds");
            int labelX = x + 336 - font.width(outLabel);
            graphics.renderFakeItem(new ItemStack(Items.EMERALD), labelX - 22, y + 20);
            graphics.drawString(font, outLabel, labelX, y + 24, PRICE_DOWN, false);
        } else if (menu.change() > 0) {
            String changeLabel = Tr.s("screen.dynamicvillages.merchant.saved_change", "Saved change: %s",
                    MerchantPricing.formatChange(menu.change()));
            int labelX = x + 336 - font.width(changeLabel);
            graphics.renderFakeItem(new ItemStack(Items.EMERALD), labelX - 22, y + 20);
            graphics.drawString(font, changeLabel, labelX, y + 24, ACCENT, false);
            if (inside(mouseX, mouseY, labelX - 24, y + 16, x + 340 - (labelX - 24), 24))
                changeTooltip = Tr.s("screen.dynamicvillages.merchant.saved_change_tip",
                        "Part of an emerald the merchants owe you here. It's added to your next sale.");
        }

        graphics.fill(x + 12, y + 49, x + 344, y + 65, PANEL);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.merchant.select_item", "Select an item"), x + 20, y + 53, TEXT, false);
        String pageLabel = (page + 1) + " / " + pageCount();
        arrow(graphics, x + 278, y + 49, "<", page > 0, mouseX, mouseY);
        graphics.drawString(font, pageLabel, x + 311 - font.width(pageLabel) / 2,
                y + 53, TEXT, false);
        arrow(graphics, x + 326, y + 49, ">", page + 1 < pageCount(), mouseX, mouseY);

        String tooltip = changeTooltip;
        int first = page * PAGE_SIZE;
        int last = Math.min(first + PAGE_SIZE, menu.offers().size());
        graphics.fill(x + GRID_X, y + GRID_Y, x + GRID_X + GRID_W,
                y + GRID_Y + GRID_H, PANEL_DARK);
        if (first == last) {
            String empty = Tr.s("screen.dynamicvillages.merchant.no_goods", "No goods to trade today.");
            graphics.drawString(font, empty, x + WIDTH / 2 - font.width(empty) / 2,
                    y + GRID_Y + (GRID_H - 8) / 2, MUTED, false);
        }
        for (int index = first; index < last; index++) {
            int local = index - first;
            Tile tile = tilePosition(local, last - first);
            int cardX = x + tile.x(), cardY = y + tile.y();
            MerchantOffer offer = menu.offers().get(index);
            boolean active = selected == index;
            boolean hovered = inside(mouseX, mouseY, cardX, cardY, CARD_W, CARD_H);
            frame(graphics, cardX, cardY, CARD_W, CARD_H,
                    active ? SELECTED : hovered ? HOVER : CARD,
                    active ? ACCENT : BORDER);
            ItemStack item = new ItemStack(MerchantCatalog.item(offer));
            smallItem(graphics, item, cardX + (CARD_W - 12) / 2,
                    cardY + (CARD_H - 12) / 2);
            if (hovered) tooltip = Tr.s("screen.dynamicvillages.merchant.offer", "%1$s - %2$s (%3$s in storage)",
                    offer.profession(), item.getHoverName().getString(), menu.count(index));
        }

        frame(graphics, x + 12, y + DETAIL_Y, 332, 57, PANEL);
        if (!menu.offers().isEmpty()) {
            MerchantOffer offer = menu.offers().get(selected);
            ItemStack item = new ItemStack(MerchantCatalog.item(offer));
            // Today's price in cents; the offer itself carries the base it moved from.
            int price = menu.priceCents(selected);
            int base = offer.fullPrice() * 100;
            int priceColour = price < base ? PRICE_DOWN : price > base ? PRICE_UP : ACCENT;
            boolean halfAllowed = true;
            boolean half = halfStack;
            int quantity = half ? item.getMaxStackSize() / 2 : item.getMaxStackSize();
            // Whole emeralds this sale pays right now, and the change it leaves saved.
            int worth = half ? MerchantPricing.lotCents(price) : price;
            int payout = menu.payout(worth);
            int saves = menu.changeAfter(worth);
            String reason = tradeReason(selected, item, quantity, payout);
            String name = item.getHoverName().getString();
            graphics.renderFakeItem(item, x + 20, y + DETAIL_Y + 8);
            graphics.drawString(font, trim(name + " / " + offer.profession(), 200),
                    x + 42, y + DETAIL_Y + 11, TEXT, false);
            String inventoryLabel = Tr.s("screen.dynamicvillages.merchant.you_have", "You have %s", owned(item));
            graphics.drawString(font, inventoryLabel,
                    x + 336 - font.width(inventoryLabel), y + DETAIL_Y + 11, MUTED, false);
            quantityToggle(graphics, x + 20, y + DETAIL_Y + 30,
                    item.getMaxStackSize() / 2, item.getMaxStackSize(), half, halfAllowed, mouseX, mouseY);
            graphics.drawString(font, Tr.s("screen.dynamicvillages.merchant.items", "items"), x + 114, y + DETAIL_Y + 37, MUTED, false);
            graphics.renderFakeItem(new ItemStack(Items.EMERALD),
                    x + 153, y + DETAIL_Y + 32);
            String payoutLabel = Integer.toString(payout);
            graphics.drawString(font, payoutLabel, x + 174, y + DETAIL_Y + 37, priceColour, false);
            if (saves > 0) {
                graphics.drawString(font, Tr.s("screen.dynamicvillages.merchant.saved_suffix", "+ %s saved",
                                MerchantPricing.formatChange(saves)),
                        x + 178 + font.width(payoutLabel), y + DETAIL_Y + 37, MUTED, false);
            }
            if (inside(mouseX, mouseY, x + 150, y + DETAIL_Y + 30, 92, 22)) {
                tooltip = price < base ? Tr.s("screen.dynamicvillages.merchant.price_down",
                                "Price is down because you sold a lot of this lately.")
                        : price > base ? Tr.s("screen.dynamicvillages.merchant.price_up",
                                "Price is up because the merchant wants variety.")
                        : base == 100 ? Tr.s("screen.dynamicvillages.merchant.price_one",
                                "Pays %s emerald for a full stack.", MerchantPricing.format(base))
                        : Tr.s("screen.dynamicvillages.merchant.price", "Pays %s emeralds for a full stack.",
                                MerchantPricing.format(base));
                if (saves > 0)
                    tooltip += " " + Tr.s("screen.dynamicvillages.merchant.change_saved_tip",
                            "The %s emerald is saved for you and added to your next sale here.",
                            MerchantPricing.formatChange(saves));
            }
            button(graphics, x + 248, y + DETAIL_Y + 30, 88, 22,
                    Tr.s("screen.dynamicvillages.merchant.sell", "SELL GOODS"), reason.isEmpty(), true, mouseX, mouseY);

            if (inside(mouseX, mouseY, x + 20, y + DETAIL_Y + 30, 88, 22))
                tooltip = Tr.s("screen.dynamicvillages.merchant.choose_amount", "Choose %1$s or %2$s items",
                        item.getMaxStackSize() / 2, item.getMaxStackSize());
            if (inside(mouseX, mouseY, x + 248, y + DETAIL_Y + 30, 88, 22))
                tooltip = !reason.isEmpty() ? reason
                        : lastAttempt == selected && !menu.status().isEmpty() ? menu.status()
                        : saves > 0
                        ? (payout == 1
                                ? Tr.s("screen.dynamicvillages.merchant.sell_for_one_save",
                                        "Sell %1$s for 1 emerald and save %2$s", quantity, MerchantPricing.formatChange(saves))
                                : Tr.s("screen.dynamicvillages.merchant.sell_for_save",
                                        "Sell %1$s for %2$s emeralds and save %3$s", quantity, payout,
                                        MerchantPricing.formatChange(saves)))
                        : payout == 1
                        ? Tr.s("screen.dynamicvillages.merchant.sell_for_one", "Sell %s for 1 emerald", quantity)
                        : Tr.s("screen.dynamicvillages.merchant.sell_for", "Sell %1$s for %2$s emeralds", quantity, payout);
        } else {
            graphics.drawString(font, Tr.s("screen.dynamicvillages.merchant.no_requests", "The merchant has no requests today."),
                    x + 20, y + DETAIL_Y + 24, MUTED, false);
        }
        if (tooltip != null)
            graphics.renderTooltip(font, font.split(Component.literal(tooltip), 160), mouseX, mouseY);
    }

    private String tradeReason(int index, ItemStack wanted, int quantity, int payout) {
        if (owned(wanted) < quantity) return Tr.s("screen.dynamicvillages.merchant.not_enough_goods",
                "Not enough goods with this village's ID.");
        if (menu.purse() == 0 || (menu.purse() > 0 && payout > menu.purse()))
            return menu.purse() == 0 ? Tr.s("screen.dynamicvillages.merchant.purse_empty",
                    "The merchant is out of emeralds for today.")
                    : Tr.s("screen.dynamicvillages.merchant.purse_short",
                    "The merchant doesn't have enough emeralds left for this.");
        return "";
    }

    private int owned(ItemStack wanted) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = minecraft.player.getInventory().getItem(i);
            if (stack.is(wanted.getItem())
                    && menu.owner().equals(com.alex.test.village.tokens.VillageTokenBinding.read(stack)))
                total += stack.getCount();
        }
        return total;
    }

    private Tile tilePosition(int local, int visible) {
        int rows = (visible + GRID_COLUMNS - 1) / GRID_COLUMNS;
        int smallerRow = visible / rows;
        int largerRows = visible % rows;
        int rowTop = GRID_Y + (GRID_H - rows * CARD_H - (rows - 1) * CARD_GAP) / 2;
        int first = 0;
        for (int row = 0; row < rows; row++) {
            int count = smallerRow + (row < largerRows ? 1 : 0);
            if (local < first + count) {
                int width = count * CARD_W + (count - 1) * CARD_GAP;
                return new Tile(GRID_X + (GRID_W - width) / 2
                        + (local - first) * (CARD_W + CARD_GAP),
                        rowTop + row * (CARD_H + CARD_GAP));
            }
            first += count;
        }
        throw new IllegalArgumentException("Offer outside visible page");
    }

    private void frame(GuiGraphics graphics, int x, int y, int width, int height, int fill) {
        frame(graphics, x, y, width, height, fill, BORDER);
    }

    private void frame(GuiGraphics graphics, int x, int y, int width, int height,
                       int fill, int border) {
        graphics.fill(x, y, x + width, y + height, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }

    private void smallItem(GuiGraphics graphics, ItemStack item, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(0.75F, 0.75F, 1.0F);
        graphics.renderFakeItem(item, 0, 0);
        graphics.pose().popPose();
    }

    /** One quantity control with two stack sizes; the half side is greyed out for odd prices. */
    private void quantityToggle(GuiGraphics graphics, int x, int y,
                                int half, int full, boolean halfChosen, boolean halfAllowed,
                                int mouseX, int mouseY) {
        int width = 88, height = 22;
        boolean hovered = inside(mouseX, mouseY, x, y, width, height);
        graphics.fill(x, y, x + width, y + height, BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1,
                hovered ? HOVER : CARD);
        graphics.fill(x + (halfChosen ? 2 : 45), y + 2,
                x + (halfChosen ? 43 : 86), y + height - 2, SELECTED);
        if (!halfAllowed) graphics.fill(x + 2, y + 2, x + 43, y + height - 2, PANEL_DARK);
        graphics.fill(x + 43, y + 2, x + 45, y + height - 2, BORDER);
        graphics.drawString(font, Integer.toString(half),
                x + 22 - font.width(Integer.toString(half)) / 2,
                y + 7, halfChosen ? TEXT : MUTED, false);
        graphics.drawString(font, Integer.toString(full),
                x + 66 - font.width(Integer.toString(full)) / 2,
                y + 7, halfChosen ? MUTED : TEXT, false);
    }

    private void arrow(GuiGraphics graphics, int x, int y, String label,
                       boolean enabled, int mouseX, int mouseY) {
        button(graphics, x, y, 18, 15, label, enabled, false, mouseX, mouseY);
    }

    private void button(GuiGraphics graphics, int x, int y, int width, int height,
                        String label, boolean enabled, boolean primary, int mouseX, int mouseY) {
        int edge = primary && enabled ? ACCENT : BORDER;
        graphics.fill(x, y, x + width, y + height, edge);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1,
                !enabled ? PANEL_DARK : inside(mouseX, mouseY, x, y, width, height)
                        ? primary ? 0xFF476A4B : HOVER : primary ? SELECTED : CARD);
        graphics.drawString(font, label, x + (width - font.width(label)) / 2,
                y + (height - 8) / 2, enabled ? TEXT : MUTED, false);
    }

    private void changePage(int delta) {
        int next = Math.max(0, Math.min(pageCount() - 1, page + delta));
        if (next == page) return;
        page = next;
        selected = Math.min(menu.offers().size() - 1, page * PAGE_SIZE);
        lastAttempt = -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int x = leftPos, y = topPos;
            if (inside(mouseX, mouseY, x + 278, y + 49, 18, 15)) {
                changePage(-1);
                return true;
            }
            if (inside(mouseX, mouseY, x + 326, y + 49, 18, 15)) {
                changePage(1);
                return true;
            }
            int first = page * PAGE_SIZE;
            int last = Math.min(first + PAGE_SIZE, menu.offers().size());
            for (int index = first; index < last; index++) {
                int local = index - first;
                Tile tile = tilePosition(local, last - first);
                int cardX = x + tile.x(), cardY = y + tile.y();
                if (inside(mouseX, mouseY, cardX, cardY, CARD_W, CARD_H)) {
                    selected = index;
                    lastAttempt = -1;
                    return true;
                }
            }
            if (!menu.offers().isEmpty()
                    && inside(mouseX, mouseY, x + 20, y + DETAIL_Y + 30, 88, 22)) {
                halfStack = mouseX < x + 20 + 44;
                lastAttempt = -1;
                return true;
            }
            if (!menu.offers().isEmpty()
                    && inside(mouseX, mouseY, x + 248, y + DETAIL_Y + 30, 88, 22)) {
                MerchantOffer offer = menu.offers().get(selected);
                ItemStack item = new ItemStack(MerchantCatalog.item(offer));
                boolean half = halfStack;
                int quantity = half ? item.getMaxStackSize() / 2 : item.getMaxStackSize();
                int payout = menu.payout(half ? MerchantPricing.lotCents(menu.priceCents(selected))
                        : menu.priceCents(selected));
                if (tradeReason(selected, item, quantity, payout).isEmpty()) {
                    lastAttempt = selected;
                    PacketDistributor.sendToServer(new MerchantTradePayloads.Sell(
                            menu.merchantId(), selected, half));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inside(mouseX, mouseY, leftPos + GRID_X, topPos + GRID_Y, GRID_W, GRID_H)) {
            if (scrollY > 0) changePage(-1);
            if (scrollY < 0) changePage(1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private String trim(String value, int width) {
        return font.width(value) <= width ? value : font.plainSubstrByWidth(value, width - 6) + "...";
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
}
