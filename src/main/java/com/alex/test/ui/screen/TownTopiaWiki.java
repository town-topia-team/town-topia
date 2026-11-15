package com.alex.test.ui.screen;

import com.alex.test.ModItems;
import com.alex.test.Tr;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The TownTopia wiki's pages and the card that links to one, shared by the guide
 * book's Wiki tab and the Mayor's Wiki view so both look and behave the same.
 */
final class TownTopiaWiki {
    static final String HOME = "https://www.town-topia.com/wiki/";
    /** Wiki link cards: two columns, this tall, this far apart. */
    static final int CARD_H = 22;
    static final int CARD_GAP = 4;
    /** Light green link text, white under the mouse. */
    private static final int LINK = 0xFF9CCB9C;

    record Page(Item icon, String label, String url) {}

    private TownTopiaWiki() {}

    /** The wiki's pages in the site's menu order. */
    static List<Page> pages() {
        return List.of(
                new Page(Items.OAK_SAPLING,
                        Tr.s("guide.dynamicvillages.wiki.getting_started", "Getting Started"), HOME + "getting-started/"),
                new Page(ModItems.TOWN_HALL.get(),
                        Tr.s("guide.dynamicvillages.wiki.mayor_tokens", "The Mayor and Tokens"), HOME + "the-mayor-and-tokens/"),
                new Page(Items.BRICKS,
                        Tr.s("guide.dynamicvillages.wiki.buildings", "Buildings"), HOME + "buildings/"),
                new Page(Items.WHEAT,
                        Tr.s("guide.dynamicvillages.wiki.farms", "Farms"), HOME + "farms/"),
                new Page(Items.IRON_AXE,
                        Tr.s("guide.dynamicvillages.wiki.jobs", "Jobs"), HOME + "jobs/"),
                new Page(Items.RED_BED,
                        Tr.s("guide.dynamicvillages.wiki.villager_life", "Villager Life"), HOME + "villager-life/"),
                new Page(Items.SHIELD,
                        Tr.s("guide.dynamicvillages.wiki.defending", "Defending Your Village"), HOME + "defending-your-village/"),
                new Page(Items.BOOK,
                        Tr.s("guide.dynamicvillages.wiki.help", "Help and FAQ"), HOME + "help-and-faq/"));
    }

    /** Height of {@code count} cards laid out in two columns. */
    static int gridHeight(int count) {
        int rows = (count + 1) / 2;
        return rows * (CARD_H + CARD_GAP) - CARD_GAP;
    }

    /** One link card: bordered row, the page's icon, its name in link green. */
    static void drawCard(GuiGraphics graphics, Font font, Page page, int x, int y, int width, boolean hover) {
        StorageUi.row(graphics, x, y, width, CARD_H, false, hover);
        graphics.renderFakeItem(new ItemStack(page.icon()), x + 3, y + 3);
        graphics.drawString(font, StorageUi.trim(font, page.label(), width - 30), x + 24, y + 7,
                hover ? StorageUi.TEXT : LINK, false);
    }

    /** Hover text for a link: what a click does, and the address in grey. */
    static List<FormattedCharSequence> tooltip(String url) {
        return List.of(
                Component.literal(Tr.s("guide.dynamicvillages.wiki.hover", "Opens the TownTopia wiki in your browser."))
                        .getVisualOrderText(),
                Component.literal(url).withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
    }

    /** Click sound, then the vanilla "open this link?" screen, which comes back to {@code from}. */
    static void open(Screen from, String url) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        ConfirmLinkScreen.confirmLinkNow(from, url);
    }
}
