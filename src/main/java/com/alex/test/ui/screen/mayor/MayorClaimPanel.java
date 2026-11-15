package com.alex.test.ui.screen.mayor;

import com.alex.test.ModItems;
import com.alex.test.Tr;
import com.alex.test.network.MayorTradePayloads;
import com.alex.test.ui.MayorStatsMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * The Mayor's whole screen while its running village has no owner and claims are on (village claims, round 2):
 * one Claim button. The first player to press it owns the village; the server then opens the Mayor again, with the
 * whole window. Built like {@link MayorPausedPanel}, with its own copy of the frame sizes and palette (MayorLook's
 * values); drawn and clicked through {@link MayorStatsScreen}.
 */
final class MayorClaimPanel {

    // Same gutter and title as MayorLook; the body starts where the tab row would. The width is the Mayor window's.
    // Banner and Claim row are twin boxes: same edges, height, icon and text columns, the same gap all round.
    private static final int CONTENT_X = 8;
    private static final int TITLE_Y = 8;
    private static final int BODY_Y = 24;

    private static final int GAP = 6;
    private static final int CARD_X = CONTENT_X + GAP;
    private static final int ROW_H = 28;
    private static final int ICON_X = 6;
    private static final int TEXT_X = 28;
    private static final int BANNER_Y = BODY_Y + GAP;
    private static final int CLAIM_ROW_Y = BANNER_Y + ROW_H + GAP;
    private static final int NOTE_Y = CLAIM_ROW_Y + ROW_H + GAP;
    private static final int LINE_H = 10;
    // A line is 8 high in its 10: 4 more puts the body's edge GAP under the last line's letters.
    private static final int NOTE_BOTTOM_GAP = GAP - 2;
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
    private static final int GOLD = 0xFFE6D49A;

    private MayorClaimPanel() {}

    static void render(GuiGraphics graphics, Font font, MayorStatsMenu menu,
                       int left, int top, int width, int mouseX, int mouseY) {
        int contentW = width - CONTENT_X * 2;
        int cardW = contentW - GAP * 2;
        String note = Tr.s("screen.dynamicvillages.claim.note",
                "A village you found with your own Town Hall token is yours from the start.");
        List<FormattedCharSequence> noteLines = font.split(Component.literal(note), cardW);
        int bodyBottom = NOTE_Y + noteLines.size() * LINE_H + NOTE_BOTTOM_GAP;
        int height = bodyBottom + CONTENT_X;

        graphics.fill(left - 2, top - 2, left + width + 2, top + height + 2, OUTER);
        graphics.fill(left, top, left + width, top + height, PANEL);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.claim.title", "Mayor - Unclaimed Village"),
                left + CONTENT_X, top + TITLE_Y, TEXT, false);
        graphics.fill(left + CONTENT_X, top + BODY_Y, left + CONTENT_X + contentW, top + bodyBottom, PANEL_DARK);
        drawBanner(graphics, font, left + CARD_X, top + BANNER_Y, cardW);

        // Each thing said once: the title says it is unclaimed, the banner the rule, this row what the button does.
        var player = Minecraft.getInstance().player;
        String name = player != null ? player.getGameProfile().getName() : "";
        drawClaimRow(graphics, font, left + CARD_X, top + CLAIM_ROW_Y, cardW, name,
                Tr.s("screen.dynamicvillages.claim.row.becomes", "Becomes the owner"),
                !menu.claimSent(), mouseX, mouseY);

        int lineY = top + NOTE_Y;
        for (FormattedCharSequence line : noteLines) {
            graphics.drawString(font, line, left + (width - font.width(line)) / 2, lineY, MUTED, false);
            lineY += LINE_H;
        }
    }

    /** True when the click landed on the Claim button; it is sent once per window. */
    static boolean mouseClicked(MayorStatsMenu menu, int left, int top, int width, double mouseX, double mouseY) {
        int cardW = width - CONTENT_X * 2 - GAP * 2;
        if (!overButton(left + CARD_X, top + CLAIM_ROW_Y, cardW, mouseX, mouseY)) return false;
        if (!menu.claimSent()) {
            menu.markClaimSent();
            PacketDistributor.sendToServer(new MayorTradePayloads.ClaimVillage(menu.mayorId()));
        }
        return true;
    }

    /** The Claim row's twin, its bottom edge a gold stripe: the village waits for an owner. */
    private static void drawBanner(GuiGraphics graphics, Font font, int x, int y, int cardW) {
        graphics.fill(x, y, x + cardW, y + ROW_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + cardW - 1, y + ROW_H, ROW);
        graphics.fill(x + 1, y + ROW_H - 2, x + cardW - 1, y + ROW_H, GOLD);
        graphics.renderFakeItem(new ItemStack(ModItems.TOWN_HALL.get()), x + ICON_X, y + 5);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.claim.banner", "The first player to claim it owns it"),
                x + TEXT_X, y + 9, TEXT, false);
    }

    /** The Claim row, laid out like the paused page's Buy rows; the button greys out once pressed. */
    private static void drawClaimRow(GuiGraphics graphics, Font font, int x, int y, int cardW,
                                     String name, String result, boolean ready, int mouseX, int mouseY) {
        graphics.fill(x, y, x + cardW, y + ROW_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + cardW - 1, y + ROW_H - 1, ROW);
        graphics.renderFakeItem(new ItemStack(Items.PLAYER_HEAD), x + ICON_X, y + 6);
        int buttonX = x + cardW - BUTTON_W - 6;
        int textWidth = buttonX - (x + TEXT_X) - 6;
        graphics.drawString(font, fit(font, name, textWidth), x + TEXT_X, y + 5, TEXT, false);
        graphics.drawString(font, fit(font, result, textWidth), x + TEXT_X, y + 16, GOLD, false);

        int buttonY = y + 5;
        boolean hovered = overButton(x, y, cardW, mouseX, mouseY);
        graphics.fill(buttonX, buttonY, buttonX + BUTTON_W, buttonY + BUTTON_H, PANEL_BORDER);
        graphics.fill(buttonX + 1, buttonY + 1, buttonX + BUTTON_W - 1, buttonY + BUTTON_H - 1,
                ready ? hovered ? 0xFF4E6F53 : 0xFF3B4E3E : hovered ? ROW_HOVER : 0xFF222222);
        String label = Tr.s("screen.dynamicvillages.claim.button", "Claim");
        graphics.drawString(font, label, buttonX + (BUTTON_W - font.width(label)) / 2, buttonY + 5,
                ready ? TEXT : MUTED, false);
    }

    /** {@code text}, cut with "..." if it would run into the Claim button. */
    private static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, width - font.width("...")) + "...";
    }

    /** Whether the mouse is on the Claim button of the row at ({@code rowX}, {@code rowY}). */
    private static boolean overButton(int rowX, int rowY, int cardW, double mouseX, double mouseY) {
        int buttonX = rowX + cardW - BUTTON_W - 6;
        int buttonY = rowY + 5;
        return mouseX >= buttonX && mouseX < buttonX + BUTTON_W
                && mouseY >= buttonY && mouseY < buttonY + BUTTON_H;
    }
}
