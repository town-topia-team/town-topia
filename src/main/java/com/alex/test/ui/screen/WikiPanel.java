package com.alex.test.ui.screen;

import com.alex.test.Tr;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

import static com.alex.test.ui.screen.StorageUi.GOLD;
import static com.alex.test.ui.screen.StorageUi.MUTED;
import static com.alex.test.ui.screen.StorageUi.PANEL_BORDER;
import static com.alex.test.ui.screen.StorageUi.PANEL_DARK;
import static com.alex.test.ui.screen.StorageUi.TEXT;
import static com.alex.test.ui.screen.StorageUi.inside;

/**
 * The Mayor's Wiki view. While it is open it has the whole Mayor window: a back
 * link on the title line, then the same page as the guide book's Wiki tab (title,
 * one line of help, the wiki's pages as two columns of cards). A card opens the
 * vanilla "open this link?" screen, which comes back to the Mayor.
 *
 * <p>The layout is fixed and fits the shortest Mayor window (200 px), so drawing
 * and clicking work out the card spots the same way and nothing scrolls.
 *
 * <p>Public for the Mayor screen (ui/screen/mayor); it stays here because it uses
 * {@link StorageUi} and {@link TownTopiaWiki}, which are package-private.
 */
public final class WikiPanel {

    /** What a click did, for the Mayor screen. */
    public enum Click { NONE, HANDLED, LEAVE }

    private static final int HEADER_Y = 4;
    private static final int HEADER_H = 16;
    private static final int BODY_Y = 24;
    private static final int PAD = 8;
    private static final int HEADING_H = 13;
    private static final int GAP = 6;

    private final Font font;
    private final int windowWidth;
    private final int contentX;

    public WikiPanel(Font font, int windowWidth, int contentX) {
        this.font = font;
        this.windowWidth = windowWidth;
        this.contentX = contentX;
    }

    public void render(GuiGraphics graphics, int left, int top, int frameHeight, int mouseX, int mouseY) {
        int x = left + contentX;
        boolean backHovered = inside(mouseX, mouseY, x, top + HEADER_Y, backW(), HEADER_H);
        graphics.drawString(font, backLabel(), x + 2, top + 8, backHovered ? TEXT : MUTED, false);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.wiki", "Wiki"), x + backW() + 8, top + 8, TEXT, false);
        graphics.fill(x, top + BODY_Y, left + windowWidth - contentX, top + frameHeight - contentX, PANEL_DARK);

        int cx = textX(left);
        int cy = top + BODY_Y + PAD;
        int width = textW();
        graphics.drawString(font, Component.literal(Tr.s("guide.dynamicvillages.wiki.title", "The TownTopia Wiki"))
                .withStyle(ChatFormatting.BOLD), cx, cy, GOLD, false);
        graphics.fill(cx, cy + 12, cx + width, cy + 13, PANEL_BORDER);
        graphics.drawString(font, StorageUi.trim(font,
                Tr.s("guide.dynamicvillages.wiki.intro", "Click a page to open it in your browser."), width),
                cx, cy + HEADING_H + GAP, MUTED, false);

        List<TownTopiaWiki.Page> pages = TownTopiaWiki.pages();
        for (int i = 0; i < pages.size(); i++) {
            TownTopiaWiki.drawCard(graphics, font, pages.get(i), cardX(left, i), cardY(top, i), cardW(),
                    i == cardAt(mouseX, mouseY, left, top, pages.size()));
        }
    }

    /** The hovered card's link text, drawn by the Mayor after everything else. */
    public void renderTooltip(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        List<TownTopiaWiki.Page> pages = TownTopiaWiki.pages();
        int hovered = cardAt(mouseX, mouseY, left, top, pages.size());
        if (hovered >= 0) graphics.renderTooltip(font, TownTopiaWiki.tooltip(pages.get(hovered).url()), mouseX, mouseY);
    }

    public Click mouseClicked(Screen screen, double mouseX, double mouseY, int button, int left, int top) {
        if (button != 0) return Click.NONE;
        if (inside(mouseX, mouseY, left + contentX, top + HEADER_Y, backW(), HEADER_H)) return Click.LEAVE;
        List<TownTopiaWiki.Page> pages = TownTopiaWiki.pages();
        int card = cardAt(mouseX, mouseY, left, top, pages.size());
        if (card < 0) return Click.NONE;
        TownTopiaWiki.open(screen, pages.get(card).url());
        return Click.HANDLED;
    }

    private String backLabel() {
        return Tr.s("screen.dynamicvillages.mayor.wiki_back", "< Village");
    }

    private int backW() {
        return font.width(backLabel()) + 4;
    }

    private int textX(int left) { return left + contentX + PAD; }
    private int textW() { return windowWidth - 2 * contentX - 2 * PAD; }
    private int gridY(int top) { return top + BODY_Y + PAD + HEADING_H + GAP + 8 + GAP; }
    private int cardW() { return (textW() - TownTopiaWiki.CARD_GAP) / 2; }
    private int cardX(int left, int i) { return textX(left) + (i % 2) * (cardW() + TownTopiaWiki.CARD_GAP); }
    private int cardY(int top, int i) { return gridY(top) + (i / 2) * (TownTopiaWiki.CARD_H + TownTopiaWiki.CARD_GAP); }

    /** Index of the card under the mouse, or -1. */
    private int cardAt(double mouseX, double mouseY, int left, int top, int count) {
        for (int i = 0; i < count; i++) {
            if (inside(mouseX, mouseY, cardX(left, i), cardY(top, i), cardW(), TownTopiaWiki.CARD_H)) return i;
        }
        return -1;
    }
}
