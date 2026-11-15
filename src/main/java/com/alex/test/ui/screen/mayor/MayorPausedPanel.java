package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import com.alex.test.ModItems;
import com.alex.test.network.MayorTradePayloads;
import com.alex.test.town.MayorShop;
import com.alex.test.ui.MayorStatsMenu;
import com.alex.test.village.claims.ClaimLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * The Mayor's whole screen while the village is paused without its Town Hall
 * token: where the token goes, and a new token for sale, paid in emeralds or
 * in what crafting one takes.
 *
 * <p>The usual tabs need a running village, so the Mayor shows only this. It
 * is built from the same pieces as the other Mayor pages: the title row, a
 * banner where the tabs sit, bordered cards, and Settings-style rows with
 * their button on the right. Drawn and clicked through {@link MayorStatsScreen}.
 */
final class MayorPausedPanel {

    // Same frame as MayorLook: its gutter, title, tab row and body. The width is the Mayor window's, handed in.
    private static final int CONTENT_X = 8;
    private static final int TITLE_Y = 8;
    private static final int BANNER_Y = 24;
    private static final int BANNER_H = 23;
    private static final int BODY_Y = 53;

    private static final int CARD_X = CONTENT_X + 6;
    private static final int TOKEN_CARD_Y = BODY_Y + 6;
    private static final int TOKEN_CARD_H = 40;
    private static final int ROW_H = 28;
    private static final int EMERALD_ROW_Y = TOKEN_CARD_Y + TOKEN_CARD_H + 4;
    private static final int MATERIAL_ROW_Y = EMERALD_ROW_Y + ROW_H + 4;
    private static final int NOTE_Y = MATERIAL_ROW_Y + ROW_H + 6;
    private static final int LINE_H = 10;
    /** Space under the note, so the body ends as close to its content as it starts. */
    private static final int NOTE_BOTTOM_GAP = 4;
    private static final int BUTTON_W = 64;
    private static final int BUTTON_H = 18;

    // Same palette as MayorLook.
    private static final int OUTER = 0xFF080808;
    private static final int PANEL_BORDER = 0xFF454545;
    private static final int PANEL = 0xFF202020;
    private static final int PANEL_DARK = 0xFF141414;
    private static final int ROW = 0xFF292929;
    private static final int ROW_HOVER = 0xFF383838;
    private static final int TEXT = 0xFFF3F3F3;
    private static final int MUTED = 0xFFAAAAAA;
    private static final int ACCENT = 0xFF6BAF72;
    private static final int GOLD = 0xFFE6D49A;
    private static final int DENIED = 0xFFE07A6E;

    private MayorPausedPanel() {}

    static void render(GuiGraphics graphics, Font font, MayorStatsMenu menu,
                       int left, int top, int width, int mouseX, int mouseY) {
        int contentW = width - CONTENT_X * 2;
        int cardW = contentW - 12;
        // The last purchase's answer, or else what a new token does. It may wrap,
        // and the window ends just under it, with no empty space left below.
        boolean answered = !menu.status().isEmpty();
        // Village claims round 7a: only people the owner trusts buy the new token.
        boolean mayBuy = MayorStatsScreen.may(menu, ClaimLock.Act.SHOP);
        String note = answered ? menu.status()
                : !mayBuy ? Tr.s("screen.dynamicvillages.paused.not_trusted",
                        "Only people %s trusts may buy a new token here.", MayorStatsScreen.ownerName(menu))
                : Tr.s("screen.dynamicvillages.paused.note",
                        "A new token wakes this same village. Older tokens stop working.");
        List<FormattedCharSequence> noteLines = font.split(Component.literal(note), cardW);
        int bodyBottom = NOTE_Y + noteLines.size() * LINE_H + NOTE_BOTTOM_GAP;
        int height = bodyBottom + CONTENT_X;

        graphics.fill(left - 2, top - 2, left + width + 2, top + height + 2, OUTER);
        graphics.fill(left, top, left + width, top + height, PANEL);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.paused.title", "Mayor - Village Paused"),
                left + CONTENT_X, top + TITLE_Y, TEXT, false);
        drawBanner(graphics, font, left + CONTENT_X, top + BANNER_Y, contentW);
        graphics.fill(left + CONTENT_X, top + BODY_Y, left + CONTENT_X + contentW,
                top + bodyBottom, PANEL_DARK);

        BlockPos townHall = menu.pausedTownHall();
        String where = townHall != null ? townHall.toShortString()
                : Tr.s("screen.dynamicvillages.paused.the_town_hall", "the Town Hall");
        drawCard(graphics, font, left + CARD_X, top + TOKEN_CARD_Y, cardW, TOKEN_CARD_H,
                new ItemStack(Items.ITEM_FRAME), Tr.s("screen.dynamicvillages.paused.have_token", "Still have the token?"),
                Tr.s("screen.dynamicvillages.paused.have_token.desc",
                        "Hang it in a frame at %s and the villagers get back to work.", where));

        Payment payment = payment(menu);
        drawBuyRow(graphics, font, left + CARD_X, top + EMERALD_ROW_Y, cardW, new ItemStack(Items.EMERALD),
                Tr.s("screen.dynamicvillages.paused.buy_emeralds", "New token: %s emeralds",
                        MayorShop.REPLACEMENT_TOWN_HALL_EMERALDS),
                payment.emeralds() == 1
                        ? Tr.s("screen.dynamicvillages.paused.have_one_emerald", "You have 1 emerald.")
                        : Tr.s("screen.dynamicvillages.paused.have_emeralds", "You have %s emeralds.", payment.emeralds()),
                payment.canPayEmeralds(), mayBuy, mouseX, mouseY);
        drawBuyRow(graphics, font, left + CARD_X, top + MATERIAL_ROW_Y, cardW, new ItemStack(Items.OAK_LOG),
                Tr.s("screen.dynamicvillages.paused.buy_materials", "New token: %s logs, 1 crafting table",
                        MayorShop.REPLACEMENT_TOWN_HALL_LOGS),
                payment.tables() == 1
                        ? Tr.s("screen.dynamicvillages.paused.have_materials_one", "You have %s logs and 1 crafting table.",
                                payment.logs())
                        : Tr.s("screen.dynamicvillages.paused.have_materials", "You have %1$s logs and %2$s crafting tables.",
                                payment.logs(), payment.tables()),
                payment.canPayMaterials(), mayBuy, mouseX, mouseY);

        int color = !answered ? mayBuy ? MUTED : DENIED : menu.statusSuccess() ? ACCENT : DENIED;
        int lineY = top + NOTE_Y;
        for (FormattedCharSequence line : noteLines) {
            graphics.drawString(font, line, left + CARD_X, lineY, color, false);
            lineY += LINE_H;
        }
    }

    /** True when the click landed on a Buy button, paid for or not. */
    static boolean mouseClicked(MayorStatsMenu menu, int left, int top, int width, double mouseX, double mouseY) {
        int cardW = width - CONTENT_X * 2 - 12;
        Payment payment = payment(menu);
        boolean mayBuy = MayorStatsScreen.may(menu, ClaimLock.Act.SHOP);
        if (overButton(left + CARD_X, top + EMERALD_ROW_Y, cardW, mouseX, mouseY)) {
            if (mayBuy && payment.canPayEmeralds()) {
                PacketDistributor.sendToServer(new MayorTradePayloads.BuyReplacementTownHall(menu.mayorId(), true));
            }
            return true;
        }
        if (overButton(left + CARD_X, top + MATERIAL_ROW_Y, cardW, mouseX, mouseY)) {
            if (mayBuy && payment.canPayMaterials()) {
                PacketDistributor.sendToServer(new MayorTradePayloads.BuyReplacementTownHall(menu.mayorId(), false));
            }
            return true;
        }
        return false;
    }

    /** Sits where the tabs do, like a selected tab, its stripe red: the village is stopped. */
    private static void drawBanner(GuiGraphics graphics, Font font, int x, int y, int contentW) {
        graphics.fill(x, y, x + contentW, y + BANNER_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + contentW - 1, y + BANNER_H, ROW);
        graphics.fill(x + 1, y + BANNER_H - 2, x + contentW - 1, y + BANNER_H, DENIED);
        graphics.renderFakeItem(new ItemStack(ModItems.TOWN_HALL.get()), x + 6, y + 3);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.paused.banner", "Your village is paused"), x + 27, y + 8, TEXT, false);
        String reason = Tr.s("screen.dynamicvillages.paused.reason", "Town Hall token missing");
        graphics.drawString(font, reason, x + contentW - 8 - font.width(reason), y + 8, GOLD, false);
    }

    /** A bordered card whose explanation wraps, as on the Mayor's Settings pages. */
    private static void drawCard(GuiGraphics graphics, Font font, int x, int y, int cardW, int height,
                                 ItemStack icon, String title, String description) {
        graphics.fill(x, y, x + cardW, y + height, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + cardW - 1, y + height - 1, ROW);
        graphics.renderFakeItem(icon, x + 8, y + (height - 16) / 2);
        graphics.drawString(font, title, x + 31, y + 6, TEXT, false);
        int lineY = y + 17;
        for (FormattedCharSequence line : font.split(Component.literal(description), cardW - 39)) {
            if (lineY + 9 > y + height - 2) break;
            graphics.drawString(font, line, x + 31, lineY, MUTED, false);
            lineY += 10;
        }
    }

    /** One way to pay, laid out like a village rule row with its button on the right. */
    private static void drawBuyRow(GuiGraphics graphics, Font font, int x, int y, int cardW, ItemStack icon,
                                   String title, String holding, boolean affordable, boolean allowed,
                                   int mouseX, int mouseY) {
        boolean live = affordable && allowed;
        graphics.fill(x, y, x + cardW, y + ROW_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + cardW - 1, y + ROW_H - 1, ROW);
        graphics.renderFakeItem(icon, x + 6, y + 6);
        int buttonX = x + cardW - BUTTON_W - 6;
        int textWidth = buttonX - (x + 28) - 6;
        graphics.drawString(font, fit(font, title, textWidth), x + 28, y + 5, TEXT, false);
        graphics.drawString(font, fit(font, holding, textWidth), x + 28, y + 16,
                affordable ? GOLD : DENIED, false);

        int buttonY = y + 5;
        boolean hovered = overButton(x, y, cardW, mouseX, mouseY);
        graphics.fill(buttonX, buttonY, buttonX + BUTTON_W, buttonY + BUTTON_H, PANEL_BORDER);
        graphics.fill(buttonX + 1, buttonY + 1, buttonX + BUTTON_W - 1, buttonY + BUTTON_H - 1,
                live ? hovered ? 0xFF4E6F53 : 0xFF3B4E3E : hovered ? ROW_HOVER : 0xFF222222);
        String buy = Tr.s("screen.dynamicvillages.paused.buy", "Buy");
        graphics.drawString(font, buy, buttonX + (BUTTON_W - font.width(buy)) / 2, buttonY + 5,
                live ? TEXT : MUTED, false);
    }

    /** {@code text}, cut with "..." if it would run into the Buy button. */
    private static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, width - font.width("...")) + "...";
    }

    /** Whether the mouse is on the Buy button of the row at ({@code rowX}, {@code rowY}). */
    private static boolean overButton(int rowX, int rowY, int cardW, double mouseX, double mouseY) {
        int buttonX = rowX + cardW - BUTTON_W - 6;
        int buttonY = rowY + 5;
        return mouseX >= buttonX && mouseX < buttonX + BUTTON_W
                && mouseY >= buttonY && mouseY < buttonY + BUTTON_H;
    }

    /**
     * What the player holds that the Mayor takes for a new token: plain items,
     * or the paused village's own, counted as the server counts them.
     */
    private record Payment(int emeralds, int logs, int tables) {
        boolean canPayEmeralds() {
            return emeralds >= MayorShop.REPLACEMENT_TOWN_HALL_EMERALDS;
        }

        boolean canPayMaterials() {
            return logs >= MayorShop.REPLACEMENT_TOWN_HALL_LOGS && tables >= 1;
        }
    }

    private static Payment payment(MayorStatsMenu menu) {
        var player = Minecraft.getInstance().player;
        if (player == null) return new Payment(0, 0, 0);
        Inventory inventory = player.getInventory();
        UUID villageId = menu.pausedVillageId();
        return new Payment(MayorShop.replacementEmeralds(inventory, villageId),
                MayorShop.replacementLogs(inventory, villageId),
                MayorShop.replacementCraftingTables(inventory, villageId));
    }
}
