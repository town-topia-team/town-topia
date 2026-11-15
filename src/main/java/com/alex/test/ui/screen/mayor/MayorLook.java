package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mayor window's shared sizes, colours and drawing pieces, used by every tab. Sizes are GUI pixels
 * from the window's top-left; a page's own sizes stay in its own file.
 */
final class MayorLook {

    private MayorLook() {}

    // ---------------------------------------------------------------------
    // The window
    // ---------------------------------------------------------------------

    /**
     * The window is never narrower than this. Its width on a screen is {@code MayorPart.windowW()}; every width
     * inside comes from that, asked each time ({@code contentW()}, {@code innerW()}, {@code listW()}), never kept here.
     */
    static final int MIN_WIDTH = 356;
    /** The window's width on every tab, when the screen has room. */
    static final int NORMAL_WIDTH = 380;
    /** Wider on the Theme tab and a set's page (its villagers need the room). */
    static final int THEME_WIDTH = 420;
    static final int HEIGHT = 284;
    static final int CONTENT_X = 8;
    /** Top of the dark body under the tabs. */
    static final int BODY_Y = 53;

    /** The Buildings / Villagers cards (and the Shop's section buttons) under the tabs. */
    static final int INFO_SELECTOR_Y = 59;
    static final int INFO_SELECTOR_H = 24;
    /** Under those cards: the search boxes, and the Back / Locate row of a building's or villager's page. */
    static final int INFO_LIST_Y = 88;
    /** Back, Locate and the segment buttons (Village rules | Professions). */
    static final int SUBNAV_H = 18;
    /** Bottom edge of the Info lists and detail cards. */
    static final int INFO_BOTTOM = 268;
    /** Top of a building's or villager's card, under its Back and Locate buttons. */
    static final int DETAIL_Y = INFO_LIST_Y + 24;
    static final int FULL_DETAIL_H = INFO_BOTTOM - DETAIL_Y;

    // Every two-column list (shop, buildings, villagers) is laid out like the shop's: two cards and a
    // 4-wide scrollbar beside them, across the body less 6 a side. The names say SHOP for that reason.
    static final int SHOP_COLUMNS = 2;
    static final int SHOP_CARD_GAP = 2;
    static final int SHOP_CARD_INSET = 1;
    static final int SHOP_SCROLLBAR_W = 4;
    static final int SHOP_SCROLLBAR_GAP = 2;
    /** The buildings and villagers lists: two cards a row. */
    static final int VILLAGER_COLUMNS = 2;
    static final int VILLAGER_CARD_GAP = 2;

    /** A Settings row (icon, title, one line, button on the right) and its button. */
    static final int RULE_ROW_H = 28;
    static final int RULE_TOGGLE_W = 64;

    /** Widest line of most hover text. */
    static final int TIP_WRAP_W = 220;

    // ---------------------------------------------------------------------
    // Colours
    // ---------------------------------------------------------------------

    static final int OUTER = 0xFF080808;
    static final int PANEL_BORDER = 0xFF454545;
    static final int PANEL = 0xFF202020;
    static final int PANEL_DARK = 0xFF141414;
    static final int ROW = 0xFF292929;
    static final int ROW_HOVER = 0xFF383838;
    static final int ROW_SELECTED = 0xFF3C5641;
    static final int TEXT = 0xFFF3F3F3;
    static final int MUTED = 0xFFAAAAAA;
    /** Secondary detail, such as coordinates, that should stay in the background. */
    static final int DIM = 0xFF7A7A7A;
    static final int ACCENT = 0xFF6BAF72;
    static final int GOLD = 0xFFE6D49A;
    static final int DENIED = 0xFFE07A6E;

    /** The screen's own font ({@code Screen.font} is protected; it is this same one). */
    private static Font font() {
        return Minecraft.getInstance().font;
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    static boolean inside(double mouseX, double mouseY,
                          int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    static String trim(String value, int width) {
        if (width <= 6) return "";
        Font font = font();
        return font.width(value) <= width ? value : font.plainSubstrByWidth(value, width - 6) + "...";
    }

    static ItemStack icon(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        return new ItemStack(item == null ? Items.BARRIER : item);
    }

    /** Tooltip lines from one text ("\n" starts a line), all in one style. */
    static Component[] linesOf(String text, ChatFormatting... style) {
        String[] parts = text.split("\n", -1);
        Component[] out = new Component[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = style.length == 0 ? Component.literal(parts[i]) : Component.literal(parts[i]).withStyle(style);
        }
        return out;
    }

    static Component[] join(Component[]... groups) {
        List<Component> all = new ArrayList<>();
        for (Component[] group : groups) all.addAll(List.of(group));
        return all.toArray(Component[]::new);
    }

    /** Hover text as lines, each wrapped to {@link #TIP_WRAP_W}; an empty line stays as a gap. */
    static List<FormattedCharSequence> tooltipLines(Component... lines) {
        Font font = font();
        List<FormattedCharSequence> out = new ArrayList<>();
        for (Component line : lines) {
            List<FormattedCharSequence> parts = font.split(line, TIP_WRAP_W);
            if (parts.isEmpty()) out.add(FormattedCharSequence.EMPTY);
            else out.addAll(parts);
        }
        return out;
    }

    // Translation helpers: the rule words below come from enums whose English
    // labels are also used as ids, so only the drawn text is translated.

    static String ruleWord(String english) {
        return switch (english) {
            case "On" -> Tr.s("screen.dynamicvillages.mayor.rule.on", "On");
            case "Off" -> Tr.s("screen.dynamicvillages.mayor.rule.off", "Off");
            case "Rare" -> Tr.s("screen.dynamicvillages.mayor.rule.rare", "Rare");
            case "Normal" -> Tr.s("screen.dynamicvillages.mayor.rule.normal", "Normal");
            case "Often" -> Tr.s("screen.dynamicvillages.mayor.rule.often", "Often");
            case "Never" -> Tr.s("screen.dynamicvillages.mayor.rule.never", "Never");
            case "Badly hurt" -> Tr.s("screen.dynamicvillages.mayor.rule.badly_hurt", "Badly hurt");
            case "Always" -> Tr.s("screen.dynamicvillages.mayor.rule.always", "Always");
            case "Few" -> Tr.s("screen.dynamicvillages.mayor.rule.few", "Few");
            case "Many" -> Tr.s("screen.dynamicvillages.mayor.rule.many", "Many");
            case "Everyone" -> Tr.s("screen.dynamicvillages.mayor.rule.everyone", "Everyone");
            case "When short" -> Tr.s("screen.dynamicvillages.mayor.rule.when_short", "When short");
            case "Every fight" -> Tr.s("screen.dynamicvillages.mayor.rule.every_fight", "Every fight");
            case "Bell only" -> Tr.s("screen.dynamicvillages.mayor.rule.bell_only", "Bell only");
            case "Closest" -> Tr.s("screen.dynamicvillages.mayor.rule.closest", "Closest");
            case "Nearby only" -> Tr.s("screen.dynamicvillages.mayor.rule.nearby_only", "Nearby only");
            case "No limit" -> Tr.s("screen.dynamicvillages.mayor.rule.no_limit", "No limit");
            case "All" -> Tr.s("screen.dynamicvillages.mayor.rule.all", "All");
            case "Deaths" -> Tr.s("screen.dynamicvillages.mayor.rule.deaths", "Deaths");
            case "Smooth" -> Tr.s("screen.dynamicvillages.mayor.rule.smooth", "Smooth");
            case "Blocky" -> Tr.s("screen.dynamicvillages.mayor.rule.blocky", "Blocky");
            default -> english;
        };
    }

    // ---------------------------------------------------------------------
    // Two-column lists (buildings, villagers)
    // ---------------------------------------------------------------------

    /** Rows a two-column list of {@code count} cards needs. */
    static int gridRows(int count) {
        return (count + VILLAGER_COLUMNS - 1) / VILLAGER_COLUMNS;
    }

    /** Width of one of the two cards in a row of a list {@code listW} wide. */
    static int cardWidth(int listW) {
        return (listW - SHOP_CARD_INSET * 2 - VILLAGER_CARD_GAP) / VILLAGER_COLUMNS;
    }

    static int cardX(int listX, int column, int cardW) {
        return listX + SHOP_CARD_INSET + column * (cardW + VILLAGER_CARD_GAP);
    }

    // ---------------------------------------------------------------------
    // Drawing pieces
    // ---------------------------------------------------------------------

    static void drawListFrame(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF111111);
    }

    static void drawEmpty(GuiGraphics graphics, int x, int y, int width, int height, String label) {
        Font font = font();
        graphics.drawString(font, label, x + (width - font.width(label)) / 2, y + height / 2 - 4,
                MUTED, false);
    }

    /** An Info tab Buildings / Villagers card: icon, label, and a value on the right. (The Shop draws its own, narrower.) */
    static void drawSummaryCard(GuiGraphics graphics, int x, int y, int width,
                                Item icon, String label, String value,
                                boolean selected, int mouseX, int mouseY) {
        Font font = font();
        boolean hovered = inside(mouseX, mouseY, x, y, width, INFO_SELECTOR_H);
        graphics.fill(x, y, x + width, y + INFO_SELECTOR_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + INFO_SELECTOR_H - 1,
                selected ? ROW_SELECTED : hovered ? ROW_HOVER : ROW);
        if (selected) {
            graphics.fill(x + 1, y + INFO_SELECTOR_H - 2,
                    x + width - 1, y + INFO_SELECTOR_H, ACCENT);
        }
        graphics.renderFakeItem(new ItemStack(icon), x + 6, y + 4);
        graphics.drawString(font, label, x + 28, y + 8, selected ? TEXT : MUTED, false);
        graphics.drawString(font, value, x + width - 8 - font.width(value),
                y + 8, TEXT, false);
    }

    /** A segment button (Village rules | Professions), or a small - / + button. */
    static void drawSegment(GuiGraphics graphics, int x, int y, int width,
                            boolean selected, String label, int mouseX, int mouseY) {
        Font font = font();
        boolean hovered = inside(mouseX, mouseY, x, y, width, SUBNAV_H);
        graphics.fill(x, y, x + width, y + SUBNAV_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + SUBNAV_H - 1,
                selected ? ROW_SELECTED : hovered ? ROW_HOVER : ROW);
        if (selected) graphics.fill(x + 1, y + SUBNAV_H - 2, x + width - 1, y + SUBNAV_H, ACCENT);
        graphics.drawString(font, label, x + (width - font.width(label)) / 2, y + 5,
                selected ? TEXT : MUTED, false);
    }

    /** A village rule: icon, title, one line of what it does now, and its button on the right. */
    static void drawRuleRow(GuiGraphics graphics, int x, int y, int width, Item icon, String title,
                            String description, String buttonLabel, boolean on, int mouseX, int mouseY) {
        drawRuleRow(graphics, x, y, width, icon, title, description, buttonLabel, on, true, mouseX, mouseY);
    }

    /** {@code enabled} false: the button is greyed (darker, no hover colour) but still shows on or off. */
    static void drawRuleRow(GuiGraphics graphics, int x, int y, int width, Item icon, String title,
                            String description, String buttonLabel, boolean on, boolean enabled, int mouseX, int mouseY) {
        Font font = font();
        graphics.fill(x, y, x + width, y + RULE_ROW_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + RULE_ROW_H - 1, ROW);
        graphics.renderFakeItem(new ItemStack(icon), x + 6, y + 6);
        graphics.drawString(font, title, x + 28, y + 5, TEXT, false);
        graphics.drawString(font, trim(description, width - 28 - RULE_TOGGLE_W - 14), x + 28, y + 16, MUTED, false);
        int buttonX = x + width - RULE_TOGGLE_W - 6;
        int buttonY = y + 5;
        boolean hovered = enabled && inside(mouseX, mouseY, buttonX, buttonY, RULE_TOGGLE_W, 18);
        drawToggleButton(graphics, buttonX, buttonY, RULE_TOGGLE_W, 18, buttonLabel, on, enabled, hovered);
    }

    /**
     * A Settings row's button (also the Land tab's Rights and Protection switches): green with white text when on,
     * dark with grey text when off. {@code enabled} false greys it (darker, no hover) but still shows on or off.
     */
    static void drawToggleButton(GuiGraphics graphics, int x, int y, int width, int height, String label,
                                 boolean on, boolean enabled, boolean hovered) {
        Font font = font();
        graphics.fill(x, y, x + width, y + height, PANEL_BORDER);
        int fill = !enabled ? on ? 0xFF2C3A2E : 0xFF1A1A1A
                : on ? hovered ? 0xFF4E6F53 : 0xFF3B4E3E : hovered ? ROW_HOVER : 0xFF222222;
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
        graphics.drawString(font, label, x + (width - font.width(label)) / 2, y + (height - 8) / 2,
                !enabled ? on ? MUTED : DIM : on ? TEXT : MUTED, false);
    }
}
