package com.alex.test.ui.screen;

import com.alex.test.ModItems;
import com.alex.test.Tr;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.alex.test.ui.screen.StorageUi.ACCENT;
import static com.alex.test.ui.screen.StorageUi.DENIED;
import static com.alex.test.ui.screen.StorageUi.GOLD;
import static com.alex.test.ui.screen.StorageUi.MUTED;
import static com.alex.test.ui.screen.StorageUi.OUTER;
import static com.alex.test.ui.screen.StorageUi.PANEL;
import static com.alex.test.ui.screen.StorageUi.PANEL_BORDER;
import static com.alex.test.ui.screen.StorageUi.PANEL_DARK;
import static com.alex.test.ui.screen.StorageUi.ROW;
import static com.alex.test.ui.screen.StorageUi.ROW_HOVER;
import static com.alex.test.ui.screen.StorageUi.TEXT;
import static com.alex.test.ui.screen.StorageUi.VIEW;
import static com.alex.test.ui.screen.StorageUi.inside;

/**
 * The TownTopia Guide book, drawn like the Mayor's window instead of the vanilla
 * book: a dark frame, four tabs (Start, Wiki, Visitors, Mines) and a body of
 * cards. The words are the {@code guide.dynamicvillages.*} lang lines.
 *
 * <p>Each page is a list of {@link Block}s stacked top to bottom; a page taller
 * than the window (small GUI) scrolls. Links are {@link Hit}s recorded while
 * drawing, so a click always matches what was drawn last frame. A link opens the
 * vanilla "open this link?" screen, which comes back here afterwards.
 */
public final class GuideBookScreen extends Screen {
    /** The Mayor's width, so the two windows look like one family. */
    private static final int MAX_W = 356;
    private static final int MAX_H = 240;
    private static final int MARGIN = 4;
    private static final int CONTENT_X = 8;
    private static final int TAB_Y = 24;
    private static final int TAB_H = 23;
    private static final int TAB_GAP = 4;
    private static final int BODY_Y = 53;
    /** Space between the body's edge and the cards. */
    private static final int PAD = 8;
    private static final int BLOCK_GAP = 6;
    private static final int LINE_H = 10;
    /** Width taken from the cards when the page scrolls: 2 px gap + the 4 px bar. */
    private static final int SCROLLBAR_SPACE = 6;
    private static final int WHEEL_STEP = 20;

    private static final int TAB_ON = 0xFF3B4E3E;
    private static final int BUTTON = 0xFF3B4E3E;
    private static final int BUTTON_HOVER = 0xFF4E6F53;

    /** The recipe accepts any log, so the picture cycles through them once a second. */
    private static final Item[] LOGS = {Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG,
            Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG};

    /** The tab shown last, so the book opens again where the player left it. */
    private static Tab lastTab = Tab.START;

    private enum Tab { START, WIKI, VISITORS, MINES }

    /** A clickable link drawn this frame, already cut to the visible part of the body. */
    private record Hit(int x, int y, int w, int h, String url) {}

    private final Map<Tab, List<Block>> pages = new EnumMap<>(Tab.class);
    private final List<Hit> hits = new ArrayList<>();
    private Tab tab = lastTab;
    private int scroll;
    private int maxScroll;
    /** Hover text found while drawing the body, drawn last so nothing covers it. */
    @Nullable private List<FormattedCharSequence> tooltip;

    public GuideBookScreen() {
        super(Tr.c("item.towntopia.guide_book", "TownTopia Guide"));
    }

    @Override
    protected void init() {
        // Built here so a language change shows up the next time the book opens.
        pages.put(Tab.START, List.of(
                new Heading(Tr.s("guide.dynamicvillages.title", "Two ways to start")),
                new Card("1", ACCENT, new ItemStack(Items.BELL),
                        Tr.s("guide.dynamicvillages.village", "Walk into a village. It becomes yours."),
                        TEXT, null, false),
                new Card("2", ACCENT, new ItemStack(ModItems.TOWN_HALL.get()),
                        Tr.s("guide.dynamicvillages.build",
                                "Build a house. Put an item frame next to the door with the Town Hall token "
                                        + "in it to found your village."),
                        TEXT, Tr.s("guide.dynamicvillages.recipe", "Recipe: 8 logs around a crafting table."), true),
                new Split(
                        new Card(null, 0, new ItemStack(Items.EMERALD),
                                Tr.s("guide.dynamicvillages.mayor", "Then visit the Mayor."), GOLD, null, false),
                        new LinkButton(new ItemStack(Items.BOOK),
                                Tr.s("guide.dynamicvillages.more", "More help on the wiki"), TownTopiaWiki.HOME))));
        pages.put(Tab.WIKI, List.of(
                new Heading(Tr.s("guide.dynamicvillages.wiki.title", "The TownTopia Wiki")),
                new Text(Tr.s("guide.dynamicvillages.wiki.intro", "Click a page to open it in your browser."), MUTED),
                new LinkGrid(TownTopiaWiki.pages())));
        pages.put(Tab.VISITORS, List.of(
                new Heading(Tr.s("guide.dynamicvillages.travelers.title", "Visitors")),
                new Card(null, 0, new ItemStack(Items.RED_BED),
                        Tr.s("guide.dynamicvillages.travelers.stay", "Merchants and nomads who come late may stay "
                                + "the night. They sleep in beds you put in the tavern, or camp by a small fire, "
                                + "and leave in the morning."), TEXT, null, false),
                new Card("!", DENIED, new ItemStack(Items.CAMPFIRE),
                        Tr.s("guide.dynamicvillages.travelers.fire", "Leave their fire alone. Break it and they "
                                + "leave upset, and nobody visits the next day."), TEXT, null, false)));
        pages.put(Tab.MINES, List.of(
                new Heading(Tr.s("guide.dynamicvillages.ores.title", "Mines and ores")),
                new Text(Tr.s("guide.dynamicvillages.ores.intro", "A mine's ore follows its height, as in vanilla:"),
                        MUTED),
                new OreGrid(List.of(
                        new Ore(Items.COAL, Tr.s("guide.dynamicvillages.ores.coal", "Coal"),
                                Tr.s("guide.dynamicvillages.ores.coal.y", "Y 96 and up")),
                        new Ore(Items.RAW_COPPER, Tr.s("guide.dynamicvillages.ores.copper", "Copper"),
                                Tr.s("guide.dynamicvillages.ores.copper.y", "Y 48")),
                        new Ore(Items.RAW_IRON, Tr.s("guide.dynamicvillages.ores.iron", "Iron"),
                                Tr.s("guide.dynamicvillages.ores.iron.y", "Y 16")),
                        new Ore(Items.LAPIS_LAZULI, Tr.s("guide.dynamicvillages.ores.lapis", "Lapis"),
                                Tr.s("guide.dynamicvillages.ores.lapis.y", "Y 0")),
                        new Ore(Items.RAW_GOLD, Tr.s("guide.dynamicvillages.ores.gold", "Gold"),
                                Tr.s("guide.dynamicvillages.ores.gold.y", "Y -16")),
                        new Ore(Items.DIAMOND, Tr.s("guide.dynamicvillages.ores.diamond", "Diamond"),
                                Tr.s("guide.dynamicvillages.ores.diamond.y", "Y -58")))),
                new Card(null, 0, new ItemStack(Items.IRON_PICKAXE),
                        Tr.s("guide.dynamicvillages.ores.settings", "Dig shafts at the heights you need. Tell a "
                                + "miner which ores it wants in its settings."), TEXT, null, false)));
    }

    // ---------------------------------------------------------------------
    // Window
    // ---------------------------------------------------------------------

    private int w() { return Math.min(MAX_W, width - 2 * MARGIN); }
    private int h() { return Math.min(MAX_H, height - 2 * MARGIN); }
    private int left() { return (width - w()) / 2; }
    private int top() { return (height - h()) / 2; }
    private int bodyTop() { return top() + BODY_Y; }
    private int bodyBottom() { return top() + h() - CONTENT_X; }
    private int tabW() {
        int count = Tab.values().length;
        return (w() - 2 * CONTENT_X - (count - 1) * TAB_GAP) / count;
    }
    private int tabX(Tab target) { return left() + CONTENT_X + target.ordinal() * (tabW() + TAB_GAP); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        hits.clear();
        tooltip = null;
        int x = left();
        int y = top();
        graphics.fill(x - 2, y - 2, x + w() + 2, y + h() + 2, OUTER);
        graphics.fill(x, y, x + w(), y + h(), PANEL);

        graphics.renderFakeItem(new ItemStack(ModItems.GUIDE_BOOK.get()), x + CONTENT_X, y + 4);
        graphics.drawString(font, Component.literal(title.getString()).withStyle(ChatFormatting.BOLD),
                x + CONTENT_X + 21, y + 8, GOLD, false);

        for (Tab target : Tab.values()) drawTab(graphics, target, mouseX, mouseY);

        graphics.fill(x + CONTENT_X, bodyTop(), x + w() - CONTENT_X, bodyBottom(), PANEL_DARK);
        renderPage(graphics, mouseX, mouseY);
        if (tooltip != null) graphics.renderTooltip(font, tooltip, mouseX, mouseY);
    }

    /** The Mayor's tab: item icon and label, green fill and bar while selected. */
    private void drawTab(GuiGraphics graphics, Tab target, int mouseX, int mouseY) {
        int x = tabX(target);
        int y = top() + TAB_Y;
        int w = tabW();
        boolean selected = tab == target;
        boolean hovered = inside(mouseX, mouseY, x, y, w, TAB_H);
        graphics.fill(x, y, x + w, y + TAB_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + TAB_H, selected ? TAB_ON : hovered ? ROW_HOVER : ROW);
        if (selected) graphics.fill(x + 1, y + TAB_H - 2, x + w - 1, y + TAB_H, ACCENT);
        graphics.renderFakeItem(tabIcon(target), x + 5, y + 3);
        graphics.drawString(font, StorageUi.trim(font, tabLabel(target), w - 29), x + 25, y + 8, TEXT, false);
    }

    private static ItemStack tabIcon(Tab target) {
        return new ItemStack(switch (target) {
            case START -> ModItems.TOWN_HALL.get();
            case WIKI -> Items.COMPASS;
            case VISITORS -> Items.CAMPFIRE;
            case MINES -> Items.IRON_PICKAXE;
        });
    }

    private static String tabLabel(Tab target) {
        return switch (target) {
            case START -> Tr.s("guide.dynamicvillages.tab.start", "Start");
            case WIKI -> Tr.s("guide.dynamicvillages.tab.wiki", "Wiki");
            case VISITORS -> Tr.s("guide.dynamicvillages.tab.visitors", "Visitors");
            case MINES -> Tr.s("guide.dynamicvillages.tab.mines", "Mines");
        };
    }

    private void renderPage(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Block> blocks = pages.get(tab);
        if (blocks == null) return;
        int viewTop = bodyTop() + PAD;
        int visible = bodyBottom() - PAD - viewTop;
        int width = w() - 2 * CONTENT_X - 2 * PAD;
        int total = totalHeight(blocks, width);
        boolean scrolls = total > visible;
        if (scrolls) {
            width -= SCROLLBAR_SPACE;
            total = totalHeight(blocks, width);
        }
        maxScroll = Math.max(0, total - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int x = left() + CONTENT_X + PAD;
        int y = viewTop - scroll;
        graphics.enableScissor(left() + CONTENT_X, bodyTop(), left() + w() - CONTENT_X, bodyBottom());
        for (Block block : blocks) {
            int blockH = block.height(width);
            if (y + blockH > bodyTop() && y < bodyBottom()) block.draw(graphics, x, y, width, blockH, mouseX, mouseY);
            y += blockH + BLOCK_GAP;
        }
        graphics.disableScissor();
        if (scrolls) StorageUi.scrollbar(graphics, x + width + 2, viewTop, visible, scroll, maxScroll, visible);
    }

    private static int totalHeight(List<Block> blocks, int width) {
        int total = 0;
        for (Block block : blocks) total += block.height(width);
        return total + Math.max(0, blocks.size() - 1) * BLOCK_GAP;
    }

    /** Under the mouse and inside the body, so a card scrolled out of sight is not hovered. */
    private boolean hovered(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseY >= bodyTop() && mouseY < bodyBottom() && inside(mouseX, mouseY, x, y, w, h);
    }

    private void addLink(int x, int y, int w, int h, String url) {
        int top = Math.max(y, bodyTop());
        int bottom = Math.min(y + h, bodyBottom());
        if (bottom > top) hits.add(new Hit(x, top, w, bottom - top, url));
    }

    private void linkTooltip(String url) {
        tooltip = TownTopiaWiki.tooltip(url);
    }

    private List<FormattedCharSequence> wrap(String text, int width) {
        return font.split(FormattedText.of(text), Math.max(20, width));
    }

    private static int linesHeight(int lines) {
        return lines <= 0 ? 0 : lines * LINE_H - 2;
    }

    private void drawLines(GuiGraphics graphics, List<FormattedCharSequence> lines, int x, int y, int color) {
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), x, y + i * LINE_H, color, false);
        }
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Tab target : Tab.values()) {
                if (inside(mouseX, mouseY, tabX(target), top() + TAB_Y, tabW(), TAB_H)) {
                    select(target);
                    return true;
                }
            }
            for (Hit hit : hits) {
                if (inside(mouseX, mouseY, hit.x(), hit.y(), hit.w(), hit.h())) {
                    TownTopiaWiki.open(this, hit.url());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll <= 0) return false;
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(scrollY * WHEEL_STEP)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Page Up / Page Down turn pages as in the vanilla book; the arrows do too.
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            select(Tab.values()[Math.max(0, tab.ordinal() - 1)]);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            select(Tab.values()[Math.min(Tab.values().length - 1, tab.ordinal() + 1)]);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void select(Tab target) {
        if (target == tab) return;
        tab = target;
        lastTab = target;
        scroll = 0;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0F));
    }

    // ---------------------------------------------------------------------
    // Page pieces
    // ---------------------------------------------------------------------

    /** One piece of a page. {@code h} in draw is the height it was given, at least {@link #height}. */
    private abstract static class Block {
        abstract int height(int width);

        abstract void draw(GuiGraphics graphics, int x, int y, int width, int h, int mouseX, int mouseY);
    }

    /** Bold gold title with a thin line under it. */
    private final class Heading extends Block {
        private final String text;

        Heading(String text) { this.text = text; }

        @Override int height(int width) { return 13; }

        @Override
        void draw(GuiGraphics graphics, int x, int y, int width, int h, int mouseX, int mouseY) {
            graphics.drawString(font, Component.literal(text).withStyle(ChatFormatting.BOLD), x, y, GOLD, false);
            graphics.fill(x, y + 12, x + width, y + 13, PANEL_BORDER);
        }
    }

    /** Plain wrapped text. */
    private final class Text extends Block {
        private final String text;
        private final int color;

        Text(String text, int color) {
            this.text = text;
            this.color = color;
        }

        @Override int height(int width) { return linesHeight(wrap(text, width).size()); }

        @Override
        void draw(GuiGraphics graphics, int x, int y, int width, int h, int mouseX, int mouseY) {
            drawLines(graphics, wrap(text, width), x, y, color);
        }
    }

    /**
     * A bordered card: an optional badge (step number, or "!" in red), an item icon
     * and wrapped text, with an optional muted note under it and, for the Town Hall
     * step, the crafting recipe drawn on the right.
     */
    private final class Card extends Block {
        private static final int IN = 6;
        private static final int BADGE = 14;
        private static final int RECIPE_W = 98;
        private static final int RECIPE_H = 54;

        @Nullable private final String badge;
        private final int badgeColor;
        private final ItemStack icon;
        private final String text;
        private final int textColor;
        @Nullable private final String note;
        private final boolean recipe;

        Card(@Nullable String badge, int badgeColor, ItemStack icon, String text, int textColor,
             @Nullable String note, boolean recipe) {
            this.badge = badge;
            this.badgeColor = badgeColor;
            this.icon = icon;
            this.text = text;
            this.textColor = textColor;
            this.note = note;
            this.recipe = recipe;
        }

        private int textOffset() { return IN + (badge != null ? BADGE + 6 : 0) + 16 + 8; }
        private int textW(int width) { return width - textOffset() - IN - (recipe ? RECIPE_W + 8 : 0); }

        private int textBlockH(int width) {
            int textH = linesHeight(wrap(text, textW(width)).size());
            if (note == null) return textH;
            return textH + 4 + linesHeight(wrap(note, textW(width)).size());
        }

        @Override
        int height(int width) {
            return IN + Math.max(Math.max(16, textBlockH(width)), recipe ? RECIPE_H : 0) + IN;
        }

        @Override
        void draw(GuiGraphics graphics, int x, int y, int width, int h, int mouseX, int mouseY) {
            StorageUi.row(graphics, x, y, width, h, false, false);
            int mid = y + h / 2;
            int cx = x + IN;
            if (badge != null) {
                int by = mid - BADGE / 2;
                graphics.fill(cx, by, cx + BADGE, by + BADGE, badgeColor);
                graphics.fill(cx + 1, by + 1, cx + BADGE - 1, by + BADGE - 1, darker(badgeColor));
                graphics.drawString(font, badge, cx + (BADGE + 1 - font.width(badge)) / 2, by + 3, TEXT, false);
                cx += BADGE + 6;
            }
            graphics.renderFakeItem(icon, cx, mid - 8);

            int tw = textW(width);
            List<FormattedCharSequence> lines = wrap(text, tw);
            int ty = mid - textBlockH(width) / 2;
            drawLines(graphics, lines, x + textOffset(), ty, textColor);
            if (note != null) {
                drawLines(graphics, wrap(note, tw), x + textOffset(), ty + linesHeight(lines.size()) + 4, MUTED);
            }
            if (recipe) drawRecipe(graphics, x + width - IN - RECIPE_W, mid - RECIPE_H / 2, mouseX, mouseY);
        }

        /** 8 logs around a crafting table, an arrow, and the Town Hall in a gold slot. */
        private void drawRecipe(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
            ItemStack log = new ItemStack(LOGS[(int) (Util.getMillis() / 1000L % LOGS.length)]);
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    boolean table = row == 1 && col == 1;
                    ItemStack stack = table ? new ItemStack(Items.CRAFTING_TABLE) : log;
                    int sx = x + col * 18;
                    int sy = y + row * 18;
                    slot(graphics, sx, sy, 18, PANEL_BORDER);
                    graphics.renderFakeItem(stack, sx + 1, sy + 1);
                    if (hovered(mouseX, mouseY, sx, sy, 18, 18)) {
                        tooltip = table ? List.of(stack.getHoverName().getVisualOrderText()) : List.of(
                                stack.getHoverName().getVisualOrderText(),
                                Component.literal(Tr.s("guide.dynamicvillages.recipe.any_log", "Any kind of log works."))
                                        .withStyle(ChatFormatting.GRAY).getVisualOrderText());
                    }
                }
            }
            int ax = x + 54 + 5;
            int ay = y + RECIPE_H / 2;
            graphics.fill(ax, ay - 1, ax + 7, ay + 1, MUTED);
            for (int i = 0; i < 5; i++) graphics.fill(ax + 7 + i, ay - 5 + i, ax + 8 + i, ay + 5 - i, MUTED);
            int ox = x + RECIPE_W - 22;
            int oy = ay - 11;
            ItemStack result = new ItemStack(ModItems.TOWN_HALL.get());
            slot(graphics, ox, oy, 22, GOLD);
            graphics.renderFakeItem(result, ox + 3, oy + 3);
            if (hovered(mouseX, mouseY, ox, oy, 22, 22)) tooltip = List.of(result.getHoverName().getVisualOrderText());
        }
    }

    private static void slot(GuiGraphics graphics, int x, int y, int size, int border) {
        graphics.fill(x, y, x + size, y + size, border);
        graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, VIEW);
    }

    /** Half as bright: the inside of a badge under its coloured rim. */
    private static int darker(int color) {
        return 0xFF000000 | ((color >> 1) & 0x7F7F7F);
    }

    /** Two pieces side by side; the right one is as wide as it asks to be. */
    private final class Split extends Block {
        private final Block left;
        private final LinkButton right;

        Split(Block left, LinkButton right) {
            this.left = left;
            this.right = right;
        }

        private int leftW(int width) { return width - right.preferredWidth() - BLOCK_GAP; }

        @Override
        int height(int width) {
            return Math.max(left.height(leftW(width)), right.height(right.preferredWidth()));
        }

        @Override
        void draw(GuiGraphics graphics, int x, int y, int width, int h, int mouseX, int mouseY) {
            int leftW = leftW(width);
            left.draw(graphics, x, y, leftW, h, mouseX, mouseY);
            right.draw(graphics, x + leftW + BLOCK_GAP, y, width - leftW - BLOCK_GAP, h, mouseX, mouseY);
        }
    }

    /** A green button with an icon that opens a wiki page. */
    private final class LinkButton extends Block {
        private final ItemStack icon;
        private final String label;
        private final String url;

        LinkButton(ItemStack icon, String label, String url) {
            this.icon = icon;
            this.label = label;
            this.url = url;
        }

        int preferredWidth() { return 6 + 16 + 6 + font.width(label) + 10; }

        @Override int height(int width) { return 24; }

        @Override
        void draw(GuiGraphics graphics, int x, int y, int width, int h, int mouseX, int mouseY) {
            boolean hover = hovered(mouseX, mouseY, x, y, width, h);
            graphics.fill(x, y, x + width, y + h, ACCENT);
            graphics.fill(x + 1, y + 1, x + width - 1, y + h - 1, hover ? BUTTON_HOVER : BUTTON);
            graphics.renderFakeItem(icon, x + 6, y + h / 2 - 8);
            graphics.drawString(font, StorageUi.trim(font, label, width - 38), x + 28, y + h / 2 - 4, TEXT, false);
            addLink(x, y, width, h, url);
            if (hover) linkTooltip(url);
        }
    }

    /** The wiki's pages as two columns of clickable cards, the same as the Mayor's Wiki view. */
    private final class LinkGrid extends Block {
        private final List<TownTopiaWiki.Page> links;

        LinkGrid(List<TownTopiaWiki.Page> links) { this.links = links; }

        @Override int height(int width) { return TownTopiaWiki.gridHeight(links.size()); }

        @Override
        void draw(GuiGraphics graphics, int x, int y, int width, int h, int mouseX, int mouseY) {
            int cellW = (width - TownTopiaWiki.CARD_GAP) / 2;
            for (int i = 0; i < links.size(); i++) {
                TownTopiaWiki.Page link = links.get(i);
                int cx = x + (i % 2) * (cellW + TownTopiaWiki.CARD_GAP);
                int cy = y + (i / 2) * (TownTopiaWiki.CARD_H + TownTopiaWiki.CARD_GAP);
                boolean hover = hovered(mouseX, mouseY, cx, cy, cellW, TownTopiaWiki.CARD_H);
                TownTopiaWiki.drawCard(graphics, font, link, cx, cy, cellW, hover);
                addLink(cx, cy, cellW, TownTopiaWiki.CARD_H, link.url());
                if (hover) linkTooltip(link.url());
            }
        }
    }

    private record Ore(Item icon, String name, String height) {}

    /** Ores in three columns: icon, name, and the height in gold. */
    private final class OreGrid extends Block {
        private static final int COLUMNS = 3;
        private static final int CELL_H = 24;
        private static final int GAP = 4;
        private final List<Ore> ores;

        OreGrid(List<Ore> ores) { this.ores = ores; }

        @Override
        int height(int width) {
            int rows = (ores.size() + COLUMNS - 1) / COLUMNS;
            return rows * (CELL_H + GAP) - GAP;
        }

        @Override
        void draw(GuiGraphics graphics, int x, int y, int width, int h, int mouseX, int mouseY) {
            int cellW = (width - (COLUMNS - 1) * GAP) / COLUMNS;
            for (int i = 0; i < ores.size(); i++) {
                Ore ore = ores.get(i);
                int cx = x + (i % COLUMNS) * (cellW + GAP);
                int cy = y + (i / COLUMNS) * (CELL_H + GAP);
                StorageUi.row(graphics, cx, cy, cellW, CELL_H, false, false);
                graphics.renderFakeItem(new ItemStack(ore.icon()), cx + 4, cy + 4);
                graphics.drawString(font, StorageUi.trim(font, ore.name(), cellW - 28), cx + 24, cy + 3, TEXT, false);
                graphics.drawString(font, StorageUi.trim(font, ore.height(), cellW - 28), cx + 24, cy + 13, GOLD, false);
            }
        }
    }
}
