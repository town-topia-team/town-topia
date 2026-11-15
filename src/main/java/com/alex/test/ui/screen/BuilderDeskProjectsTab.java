package com.alex.test.ui.screen;

import com.alex.test.network.BuildOrderPayloads;
import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.village.villagers.builder.project.BuildOrders;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static com.alex.test.ui.screen.StorageUi.ACCENT;
import static com.alex.test.ui.screen.StorageUi.BUTTON_HOVER_BORDER;
import static com.alex.test.ui.screen.StorageUi.DENIED;
import static com.alex.test.ui.screen.StorageUi.GOLD;
import static com.alex.test.ui.screen.StorageUi.MUTED;
import static com.alex.test.ui.screen.StorageUi.PANEL;
import static com.alex.test.ui.screen.StorageUi.PANEL_BORDER;
import static com.alex.test.ui.screen.StorageUi.PANEL_DARK;
import static com.alex.test.ui.screen.StorageUi.ROW;
import static com.alex.test.ui.screen.StorageUi.ROW_HOVER;
import static com.alex.test.ui.screen.StorageUi.TEXT;
import static com.alex.test.ui.screen.StorageUi.inside;

/**
 * The desk's Projects tab (redesigned 10-25): the village's orders as cards on the left, the
 * picked one on the right with the blocks it still needs, always shown (no Blocks button).
 * Drawn like the other desk tabs (10-25 restyle): framed cards and rows ({@link StorageUi#row}),
 * green titles, framed progress bars. Calm on purpose: text is white or grey; green only for titles,
 * the picked card or row and the bars,
 * gold only for "N short", red only for a broken file. Buttons appear where they apply: the
 * move arrows on the picked card, Give only when you carry something the order is short of.
 * Clicking a block line opens the give bar (round 3): that one block, as many as you choose.
 */
final class BuilderDeskProjectsTab {
    private static final int GAP = 8, CARD_H = 38, ROW_H = 20, LINE_H = ROW_H + 2, BUTTON_H = 20, CELL = 18,
            BAR_W = 4;
    // Odd sizes, so the little triangles sit dead centre.
    private static final int ARROW_W = 15, ARROW_H = 11;
    private static final int PAUSE_W = 56, CANCEL_W = 52, MAX_ORDERS = 8;
    /** Asked again every 2 s once read; every 0.5 s while the server is still reading the file. */
    private static final int ASK_TICKS = 40, WAIT_TICKS = 10;
    // Progress bars as the mod draws them: grey frame, dark trough, green fill (grey while paused).
    private static final int TROUGH = 0xFF111111, PAUSED_BAR = 0xFF777777;
    private static final String GIVE_ALL = "Give all I carry";
    /** The give bar under the block list: a line with the block, then a line of buttons. */
    private static final int GIVE_BAR_H = 50, SMALL_H = 15;

    /** The give bar's controls; widths of the fixed ones (Give and Take back fit their text). */
    private enum BarControl {
        LESS16("-16", 22), LESS1("-1", 16), AMOUNT("", 32), MORE1("+1", 16), MORE16("+16", 22), ALL("All", 24),
        GIVE("", 0), TAKE("Take back", 0), CLOSE("", SMALL_H);

        final String label;
        final int width;

        BarControl(String label, int width) {
            this.label = label;
            this.width = width;
        }
    }

    private final BuilderDeskMenu menu;
    private Font font = Minecraft.getInstance().font;
    private int left, top, right, bottom;

    /** The order shown on the right; the one at the same place is picked when it goes. */
    @Nullable private UUID picked;
    private int pickedIndex;
    private int cardScroll, blockScroll;
    private boolean draggingBar;

    /** The picked order's blocks, most short first; null until the first answer. */
    @Nullable private List<BuildOrderPayloads.BlockNeed> blocks;
    private boolean ready;
    private int ticksToAsk;

    /** The order whose Cancel was clicked once; a second click within 3 seconds cancels it. */
    @Nullable private UUID cancelAsked;
    private long cancelAskedUntil;
    /** The server's answer to Give or a take back, at the bottom for a few seconds. */
    @Nullable private String notice;
    private boolean noticeOk;
    private long noticeUntil;
    /** The block line clicked to give some of it (the give bar shows), or null. */
    @Nullable private ResourceLocation chosen;
    private int amount;

    BuilderDeskProjectsTab(BuilderDeskMenu menu) {
        this.menu = menu;
    }

    // =========================================================================
    // The picked order and its blocks
    // =========================================================================

    /** While the tab shows: keeps a picked order and asks for its blocks on time. */
    void tick() {
        List<BuildOrderPayloads.ProjectRow> rows = menu.projects();
        if (rows == null) return;
        syncPicked(rows);
        if (picked != null && --ticksToAsk <= 0) ask();
    }

    /** The picked order still listed, else the one now at its place (the next one), else none. */
    private void syncPicked(List<BuildOrderPayloads.ProjectRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(picked)) {
                pickedIndex = i;
                return;
            }
        }
        pick(rows.isEmpty() ? null : rows.get(Math.min(pickedIndex, rows.size() - 1)).id());
    }

    private void pick(@Nullable UUID id) {
        if (Objects.equals(id, picked)) return;
        picked = id;
        blocks = null;
        ready = false;
        blockScroll = 0;
        cancelAsked = null;
        chosen = null;
        ticksToAsk = 0;
    }

    private void ask() {
        ticksToAsk = ready ? ASK_TICKS : WAIT_TICKS;
        PacketDistributor.sendToServer(new BuildOrderPayloads.OrderBlocksRequest(menu.villagerId(), picked));
    }

    void answer(BuildOrderPayloads.OrderBlocksReply reply) {
        if (!reply.projectId().equals(picked)) return;
        List<BuildOrderPayloads.BlockNeed> sorted = new ArrayList<>(reply.rows());
        // Short first (most short on top), then the ones all there, biggest first.
        sorted.sort(Comparator.comparingInt(BuilderDeskProjectsTab::shortOf).reversed()
                .thenComparing(Comparator.comparingInt(BuildOrderPayloads.BlockNeed::need).reversed())
                .thenComparing(row -> stack(row).getHoverName().getString()));
        blocks = sorted;
        ready = reply.ready();
        // The chosen block is all placed: its line is gone, so is the bar.
        if (chosenRow() == null) chosen = null;
    }

    void notice(boolean ok, String message) {
        notice = message;
        noticeOk = ok;
        noticeUntil = System.currentTimeMillis() + 6000;
        // A give or take back changes the numbers: show them soon.
        ticksToAsk = Math.min(ticksToAsk, 5);
    }

    @Nullable
    private BuildOrderPayloads.ProjectRow pickedRow() {
        List<BuildOrderPayloads.ProjectRow> rows = menu.projects();
        if (rows == null) return null;
        for (BuildOrderPayloads.ProjectRow row : rows) {
            if (row.id().equals(picked)) return row;
        }
        return null;
    }

    /** What is still missing after the village's blocks and what players gave. */
    private static int shortOf(BuildOrderPayloads.BlockNeed row) {
        return Math.max(0, row.need() - row.have() - row.given());
    }

    private static ItemStack stack(BuildOrderPayloads.BlockNeed row) {
        return new ItemStack(BuiltInRegistries.ITEM.get(row.item()));
    }

    private static boolean broken(BuildOrderPayloads.ProjectRow row) {
        // The status BuildSiteWork sets when the order's file can't be read.
        return row.status().startsWith("The blueprint file is missing");
    }

    /** "Give all I carry" would take something: the player carries a block the order is short of. */
    private boolean canGiveAny() {
        Player player = Minecraft.getInstance().player;
        if (player == null || blocks == null || !ready || !menu.freeBy().isEmpty()) return false;
        for (BuildOrderPayloads.BlockNeed row : blocks) {
            if (shortOf(row) > 0 && carried(player, BuiltInRegistries.ITEM.get(row.item())) > 0) return true;
        }
        return false;
    }

    /** Plain stacks of {@code item} in the main inventory and off hand: what the server's Give takes from. */
    private static int carried(@Nullable Player player, Item item) {
        if (player == null) return 0;
        int count = 0;
        for (List<ItemStack> list : List.of(player.getInventory().items, player.getInventory().offhand)) {
            for (ItemStack stack : list) {
                if (BuildOrders.plain(stack, item)) count += stack.getCount();
            }
        }
        return count;
    }

    // The give bar (round 3): one block, as many as you want.

    @Nullable
    private BuildOrderPayloads.BlockNeed chosenRow() {
        if (chosen == null || blocks == null || !ready) return null;
        for (BuildOrderPayloads.BlockNeed row : blocks) {
            if (row.item().equals(chosen)) return row;
        }
        return null;
    }

    private boolean barOpen() {
        return chosenRow() != null && menu.freeBy().isEmpty() && pickedRow() != null;
    }

    private int carriedChosen() {
        return chosen == null ? 0 : carried(Minecraft.getInstance().player, BuiltInRegistries.ITEM.get(chosen));
    }

    /** What the viewing player gave of the chosen block (all orders of the village). */
    private int givenByMe() {
        for (BuildOrderPayloads.GivenRow pile : menu.given()) {
            if (pile.item().equals(chosen)) return pile.count();
        }
        return 0;
    }

    /** The most to give: what you carry, up to what this order still needs past what players gave. */
    private int maxGive() {
        BuildOrderPayloads.BlockNeed row = chosenRow();
        return row == null ? 0 : Math.max(0, Math.min(carriedChosen(), row.need() - row.given()));
    }

    /** Opens the bar for a block line: the amount starts at what is short and you carry. */
    private void choose(BuildOrderPayloads.BlockNeed row) {
        chosen = row.item();
        int missing = shortOf(row);
        amount = missing > 0 ? Math.min(missing, carriedChosen()) : maxGive();
        clampAmount();
    }

    private void clampAmount() {
        int max = maxGive();
        amount = max <= 0 ? 0 : Mth.clamp(amount, 1, max);
    }

    // =========================================================================
    // Layout
    // =========================================================================

    private int ordersRight() { return left + Mth.clamp((right - left) / 3, 150, 190); }
    private int detailLeft() { return ordersRight() + GAP; }

    // Left: the cards, and under them the blocks you gave (only when you gave some).
    private int cardsTop() { return top + 20; }
    private int cardsBottom() { return menu.given().isEmpty() ? bottom - 4 : givenTop() - 6; }
    // Framed cards and rows as on the other tabs: 6 in from the panel, 2 apart, the scrollbar 8 from its edge.
    private int cardX() { return left + 6; }
    private int cardW() { return ordersRight() - left - 12 - (cardMaxScroll() > 0 ? 6 : 0); }
    private int cardY(int index) { return cardsTop() + index * (CARD_H + 2) - cardScroll; }

    private int cardMaxScroll() {
        List<BuildOrderPayloads.ProjectRow> rows = menu.projects();
        int count = rows == null ? 0 : rows.size();
        return Math.max(0, count * (CARD_H + 2) - 2 - (cardsBottom() - cardsTop()));
    }

    private int upX() { return cardX() + cardW() - 5 - 2 * ARROW_W - 2; }
    private int downX() { return upX() + ARROW_W + 2; }
    private int arrowY(int index) { return cardY(index) + 4; }

    private int givenPerRow() { return Math.max(1, (ordersRight() - left - 12 + 2) / (CELL + 2)); }
    private int givenRows() { return Math.min(2, (menu.given().size() + givenPerRow() - 1) / givenPerRow()); }
    private int givenTop() { return bottom - 6 - 16 - givenRows() * (CELL + 2) + 2; }
    private int givenCellX(int index) { return left + 6 + index % givenPerRow() * (CELL + 2); }
    private int givenCellY(int index) { return givenTop() + 16 + index / givenPerRow() * (CELL + 2); }

    // Right: header, status line, the block list, and a footer line with Give.
    private int textX() { return detailLeft() + 8; }
    private int textRight() { return right - 8; }
    private int cancelX() { return textRight() - CANCEL_W; }
    private int pauseX() { return cancelX() - 4 - PAUSE_W; }
    private int buttonsY() { return top + 4; }
    private int listTop() { return top + 62; }
    private int footerY() { return bottom - 6 - BUTTON_H; }
    /** The block rows: framed, from 6 in on the left to the scrollbar (or 6 in) on the right. */
    private int rowX() { return detailLeft() + 6; }
    private int rowW() { return right - 6 - rowX() - (blockMaxScroll() > 0 ? 6 : 0); }
    /** The give bar (a framed box) takes the footer's place, and a little more, while it is open. */
    private int listBottom() { return barOpen() ? barTop() - 6 : footerY() - 6; }
    private int barTop() { return bottom - 6 - GIVE_BAR_H; }
    private int barLeft() { return detailLeft() + 6; }
    private int barRight() { return right - 6; }

    /** Where a give bar control sits: {x, y, width, height}, or null when it isn't shown. */
    @Nullable
    private int[] barSpot(BarControl control) {
        int lineY = barTop() + 5, buttonsY = barTop() + 25, inLeft = barLeft() + 6, inRight = barRight() - 6;
        switch (control) {
            case CLOSE -> { return new int[] {inRight - SMALL_H, lineY, SMALL_H, SMALL_H}; }
            case TAKE -> {
                if (givenByMe() <= 0) return null;
                int w = font.width(control.label) + 10;
                return new int[] {inRight - SMALL_H - 4 - w, lineY, w, SMALL_H};
            }
            case GIVE -> { return new int[] {inRight - giveItemW(), buttonsY, giveItemW(), BUTTON_H}; }
            default -> { }
        }
        // The steppers from the left; -16 and +16 only when they fit beside Give.
        int all = 0;
        for (BarControl each : BarControl.values()) {
            if (each.ordinal() <= BarControl.ALL.ordinal()) all += each.width + 2;
        }
        boolean wide = inLeft + all + 4 + 8 <= inRight - giveItemW();
        int x = inLeft;
        for (BarControl each : List.of(BarControl.LESS16, BarControl.LESS1, BarControl.AMOUNT, BarControl.MORE1,
                BarControl.MORE16, BarControl.ALL)) {
            boolean big = each == BarControl.LESS16 || each == BarControl.MORE16;
            if (big && !wide) {
                if (each == control) return null;
                continue;
            }
            if (each == BarControl.ALL) x += 4;
            if (each == control) return new int[] {x, buttonsY, each.width, BUTTON_H};
            x += each.width + 2;
        }
        return null;
    }

    /** Give fits its biggest label, so it doesn't change width while the amount steps. */
    private int giveItemW() {
        return Math.max(44, font.width(String.format(Locale.ROOT, "Give %,d", Math.max(1, maxGive()))) + 12);
    }
    private int giveW() { return font.width(GIVE_ALL) + 14; }
    private int giveX() { return textRight() - giveW(); }
    private int listBarX() { return right - 8; }
    private int shortRight() { return rowX() + rowW() - 6; }
    private int pairRight() { return shortRight() - font.width("99,999 short") - 10; }

    private int blockMaxScroll() {
        return blocks == null || !ready ? 0 : Math.max(0, blocks.size() * LINE_H - 2 - (listBottom() - listTop()));
    }

    // =========================================================================
    // Drawing
    // =========================================================================

    void render(GuiGraphics graphics, Font font, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        this.font = font;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        List<BuildOrderPayloads.ProjectRow> rows = menu.projects();
        if (rows != null) syncPicked(rows);
        cardScroll = Mth.clamp(cardScroll, 0, cardMaxScroll());
        blockScroll = Mth.clamp(blockScroll, 0, blockMaxScroll());
        renderOrders(graphics, rows, mouseX, mouseY);
        renderGiven(graphics, mouseX, mouseY);
        renderDetail(graphics, rows, mouseX, mouseY);
    }

    private void renderOrders(GuiGraphics graphics, @Nullable List<BuildOrderPayloads.ProjectRow> rows,
                              int mouseX, int mouseY) {
        graphics.fill(left, top, ordersRight(), bottom, PANEL_DARK);
        graphics.drawString(font, "Orders", left + 8, top + 6, ACCENT, false);
        String count = (rows == null ? 0 : rows.size()) + " of " + MAX_ORDERS;
        graphics.drawString(font, count, ordersRight() - 8 - font.width(count), top + 6, MUTED, false);
        if (rows == null || rows.isEmpty()) return;
        int x = cardX(), w = cardW();
        graphics.enableScissor(left, cardsTop(), ordersRight(), cardsBottom());
        for (int i = 0; i < rows.size(); i++) {
            int y = cardY(i);
            if (y + CARD_H <= cardsTop() || y >= cardsBottom()) continue;
            BuildOrderPayloads.ProjectRow row = rows.get(i);
            boolean isPicked = row.id().equals(picked);
            // The mod's card: grey frame; the picked one green with the green line along its bottom.
            StorageUi.row(graphics, x, y, w, CARD_H, isPicked,
                    overCards(mouseX, mouseY) && inside(mouseX, mouseY, x, y, w, CARD_H));
            graphics.drawString(font, Integer.toString(i + 1), x + 6, y + 5, MUTED, false);
            int nameW = w - 18 - 6 - (isPicked ? 2 * ARROW_W + 6 : 0);
            graphics.drawString(font, StorageUi.trim(font, row.name(), nameW), x + 18, y + 5, TEXT, false);
            String percent = row.progress() + "%";
            int percentX = x + w - 6 - font.width(percent);
            graphics.drawString(font, StorageUi.trim(font, row.status(), percentX - 6 - (x + 18)), x + 18, y + 16,
                    broken(row) ? DENIED : MUTED, false);
            graphics.drawString(font, percent, percentX, y + 16, MUTED, false);
            bar(graphics, x + 18, y + 27, x + w - 6, 6, row.progress(), row.paused());
            if (isPicked) {
                arrowButton(graphics, upX(), arrowY(i), true, i > 0, mouseX, mouseY);
                arrowButton(graphics, downX(), arrowY(i), false, i < rows.size() - 1, mouseX, mouseY);
            }
        }
        graphics.disableScissor();
        if (cardMaxScroll() > 0) {
            StorageUi.scrollbar(graphics, ordersRight() - 8, cardsTop(), cardsBottom() - cardsTop(), cardScroll,
                    cardMaxScroll(), cardsBottom() - cardsTop());
        }
    }

    /** A progress bar: grey frame, dark trough, green fill (grey while paused). */
    private static void bar(GuiGraphics graphics, int x0, int y, int x1, int height, int progress, boolean paused) {
        if (x1 - x0 < 3) return;
        graphics.fill(x0, y, x1, y + height, PANEL_BORDER);
        graphics.fill(x0 + 1, y + 1, x1 - 1, y + height - 1, TROUGH);
        int fill = (x1 - x0 - 2) * Mth.clamp(progress, 0, 100) / 100;
        if (fill > 0) graphics.fill(x0 + 1, y + 1, x0 + 1 + fill, y + height - 1, paused ? PAUSED_BAR : ACCENT);
    }

    /** A small button with a 5 by 3 triangle in its middle. */
    private void arrowButton(GuiGraphics graphics, int x, int y, boolean up, boolean enabled, int mouseX, int mouseY) {
        StorageUi.button(graphics, font, x, y, ARROW_W, ARROW_H, "", inside(mouseX, mouseY, x, y, ARROW_W, ARROW_H),
                enabled);
        int color = enabled ? TEXT : MUTED, cx = x + ARROW_W / 2, ty = y + (ARROW_H - 3) / 2;
        for (int row = 0; row < 3; row++) {
            int half = up ? row : 2 - row;
            graphics.fill(cx - half, ty + row, cx + half + 1, ty + row + 1, color);
        }
    }

    private void renderGiven(GuiGraphics graphics, int mouseX, int mouseY) {
        List<BuildOrderPayloads.GivenRow> given = menu.given();
        if (given.isEmpty()) return;
        graphics.drawString(font, StorageUi.trim(font, "Your blocks with the Builder", ordersRight() - left - 16),
                left + 8, givenTop() + 4, ACCENT, false);
        int shown = Math.min(given.size(), givenRows() * givenPerRow());
        for (int i = 0; i < shown; i++) {
            int cx = givenCellX(i), cy = givenCellY(i);
            ItemStack stack = givenStack(given.get(i));
            boolean hover = inside(mouseX, mouseY, cx, cy, CELL, CELL);
            graphics.fill(cx, cy, cx + CELL, cy + CELL, hover ? BUTTON_HOVER_BORDER : PANEL_BORDER);
            graphics.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, hover ? ROW_HOVER : ROW);
            graphics.renderFakeItem(stack, cx + 1, cy + 1);
            graphics.renderItemDecorations(font, stack, cx + 1, cy + 1, shortCount(given.get(i).count()));
        }
    }

    private void renderDetail(GuiGraphics graphics, @Nullable List<BuildOrderPayloads.ProjectRow> rows,
                              int mouseX, int mouseY) {
        graphics.fill(detailLeft(), top, right, bottom, PANEL_DARK);
        int x = textX(), width = textRight() - x, middle = (detailLeft() + right) / 2;
        BuildOrderPayloads.ProjectRow row = pickedRow();
        if (row == null) {
            if (rows == null) {
                graphics.drawCenteredString(font, "Loading the orders...", middle, (top + bottom) / 2 - 4, MUTED);
            } else {
                graphics.drawCenteredString(font, "Nothing ordered yet.", middle, (top + bottom) / 2 - 10, TEXT);
                graphics.drawCenteredString(font, "Pick a building on the Build or Themes tab.", middle,
                        (top + bottom) / 2 + 2, MUTED);
            }
            renderFooter(graphics, mouseX, mouseY, false);
            return;
        }
        // Header: name and who ordered it where; Pause and Cancel on the right.
        int textW = pauseX() - 8 - x;
        // The order's name is this pane's title, green like "Orders" and the other tabs' titles.
        graphics.drawString(font, StorageUi.trim(font, row.name(), textW), x, top + 6, ACCENT, false);
        String where = String.format(Locale.ROOT, "at %d, %d, %d", row.origin().getX(), row.origin().getY(),
                row.origin().getZ());
        if (!row.orderedBy().isEmpty()) where = "by " + row.orderedBy() + " · " + where;
        graphics.drawString(font, StorageUi.trim(font, where, textW), x, top + 17, MUTED, false);
        StorageUi.button(graphics, font, pauseX(), buttonsY(), PAUSE_W, BUTTON_H, row.paused() ? "Resume" : "Pause",
                inside(mouseX, mouseY, pauseX(), buttonsY(), PAUSE_W, BUTTON_H), true);
        boolean sure = row.id().equals(cancelAsked) && System.currentTimeMillis() < cancelAskedUntil;
        StorageUi.button(graphics, font, cancelX(), buttonsY(), CANCEL_W, BUTTON_H, sure ? "Sure?" : "Cancel",
                inside(mouseX, mouseY, cancelX(), buttonsY(), CANCEL_W, BUTTON_H), true);

        // Status, a wide thin bar, the percent.
        int statusW = width * 2 / 5;
        String percent = row.progress() + "%";
        graphics.drawString(font, StorageUi.trim(font, row.status(), statusW), x, top + 32,
                broken(row) ? DENIED : MUTED, false);
        graphics.drawString(font, percent, textRight() - font.width(percent), top + 32, MUTED, false);
        bar(graphics, x + statusW + 8, top + 33, textRight() - font.width(percent) - 6, 6, row.progress(),
                row.paused());

        // The blocks it still needs.
        graphics.drawString(font, "Blocks", x, listTop() - 12, ACCENT, false);
        String pairHead = "have / need";
        graphics.drawString(font, pairHead, pairRight() - font.width(pairHead), listTop() - 12, MUTED, false);
        renderBlocks(graphics, mouseX, mouseY);
        if (barOpen()) {
            renderGiveBar(graphics, mouseX, mouseY);
        } else {
            renderFooter(graphics, mouseX, mouseY, true);
        }
    }

    private void renderBlocks(GuiGraphics graphics, int mouseX, int mouseY) {
        int middle = (detailLeft() + right) / 2;
        String empty = blocks == null ? "Counting the blocks..." : !ready ? "Reading the blueprint..."
                : blocks.isEmpty() ? "Nothing left to place." : null;
        if (empty != null) {
            graphics.drawCenteredString(font, empty, middle, (listTop() + listBottom()) / 2 - 4, MUTED);
            return;
        }
        int x = rowX(), w = rowW(), shortRight = shortRight(), pairRight = pairRight();
        graphics.enableScissor(detailLeft(), listTop(), right, listBottom());
        for (int i = 0; i < blocks.size(); i++) {
            int y = listTop() + i * LINE_H - blockScroll;
            if (y + ROW_H <= listTop()) continue;
            if (y >= listBottom()) break;
            BuildOrderPayloads.BlockNeed row = blocks.get(i);
            // A framed row each; the one the give bar is open for shows as picked.
            StorageUi.row(graphics, x, y, w, ROW_H, barOpen() && row.item().equals(chosen),
                    overList(mouseX, mouseY) && inside(mouseX, mouseY, x, y, w, ROW_H));
            int missing = shortOf(row);
            ItemStack stack = stack(row);
            graphics.renderFakeItem(stack, x + 2, y + 2);
            graphics.drawString(font, StorageUi.trim(font, stack.getHoverName().getString(),
                    pairRight - font.width("99,999 / 99,999") - 8 - (x + 22)), x + 22, y + 6,
                    missing > 0 ? TEXT : MUTED, false);
            // Have counts storage, the Builder's pack and what players gave; never shown over the need.
            String pair = String.format(Locale.ROOT, "%,d / %,d", Math.min(row.need(), row.have() + row.given()),
                    row.need());
            graphics.drawString(font, pair, pairRight - font.width(pair), y + 6, missing > 0 ? TEXT : MUTED, false);
            if (missing > 0) {
                String text = String.format(Locale.ROOT, "%,d short", missing);
                graphics.drawString(font, text, shortRight - font.width(text), y + 6, GOLD, false);
            }
        }
        graphics.disableScissor();
        if (blockMaxScroll() > 0) {
            StorageUi.scrollbar(graphics, listBarX(), listTop(), listBottom() - listTop(), blockScroll,
                    blockMaxScroll(), listBottom() - listTop());
        }
    }

    /** The bottom line: the latest answer, else free building, else how much is short; Give on the right. */
    private void renderFooter(GuiGraphics graphics, int mouseX, int mouseY, boolean orderShown) {
        boolean give = orderShown && canGiveAny();
        int x = textX(), width = (give ? giveX() - 8 : textRight()) - x, y = footerY() + 6;
        String line = null;
        int color = MUTED;
        if (notice != null && System.currentTimeMillis() < noticeUntil) {
            line = notice;
            color = noticeOk ? TEXT : GOLD;
        } else if (orderShown && !menu.freeBy().isEmpty()) {
            line = "Free building is on (" + menu.freeBy() + "): the Builder uses none of these.";
            color = TEXT;
        } else if (orderShown && blocks != null && ready && !blocks.isEmpty()) {
            int total = 0, kinds = 0;
            for (BuildOrderPayloads.BlockNeed row : blocks) {
                if (shortOf(row) > 0) {
                    total += shortOf(row);
                    kinds++;
                }
            }
            line = total == 0 ? "Everything is there." : String.format(Locale.ROOT, "%,d blocks short (%d %s)",
                    total, kinds, kinds == 1 ? "kind" : "kinds");
        }
        if (line != null) graphics.drawString(font, StorageUi.trim(font, line, width), x, y, color, false);
        if (give) {
            StorageUi.button(graphics, font, giveX(), footerY(), giveW(), BUTTON_H, GIVE_ALL,
                    inside(mouseX, mouseY, giveX(), footerY(), giveW(), BUTTON_H), true);
        }
    }

    /**
     * The give bar: the block with what you carry and gave (the server's answer in their place for
     * a few seconds), then the amount with its steppers, All, and Give.
     */
    private void renderGiveBar(GuiGraphics graphics, int mouseX, int mouseY) {
        BuildOrderPayloads.BlockNeed row = chosenRow();
        if (row == null) return;
        clampAmount();
        // A framed box, like the Mayor's rule rows; its buttons and the dark amount field sit inside.
        int y0 = barTop(), x = barLeft() + 6;
        graphics.fill(barLeft(), y0, barRight(), y0 + GIVE_BAR_H, PANEL_BORDER);
        graphics.fill(barLeft() + 1, y0 + 1, barRight() - 1, y0 + GIVE_BAR_H - 1, PANEL);
        ItemStack stack = stack(row);
        graphics.renderFakeItem(stack, x, y0 + 4);
        int[] take = barSpot(BarControl.TAKE), close = barSpot(BarControl.CLOSE);
        int textEnd = (take != null ? take[0] : close[0]) - 6;
        if (notice != null && System.currentTimeMillis() < noticeUntil) {
            graphics.drawString(font, StorageUi.trim(font, notice, textEnd - (x + 20)), x + 20, y0 + 9,
                    noticeOk ? TEXT : GOLD, false);
        } else {
            String name = StorageUi.trim(font, stack.getHoverName().getString(), textEnd - (x + 20));
            graphics.drawString(font, name, x + 20, y0 + 9, TEXT, false);
            int carry = carriedChosen(), gave = givenByMe();
            String info = (carry > 0 ? String.format(Locale.ROOT, "you carry %,d", carry) : "you carry none")
                    + (gave > 0 ? String.format(Locale.ROOT, " · you gave %,d", gave) : "");
            int infoX = x + 20 + font.width(name) + 8;
            graphics.drawString(font, StorageUi.trim(font, info, textEnd - infoX), infoX, y0 + 9, MUTED, false);
        }
        if (take != null) {
            StorageUi.button(graphics, font, take[0], take[1], take[2], take[3], BarControl.TAKE.label,
                    inside(mouseX, mouseY, take[0], take[1], take[2], take[3]), true);
        }
        StorageUi.button(graphics, font, close[0], close[1], close[2], close[3], "",
                inside(mouseX, mouseY, close[0], close[1], close[2], close[3]), true);
        // A small cross, drawn like the arrows so it sits dead centre.
        int cx = close[0] + close[2] / 2, cy = close[1] + close[3] / 2;
        for (int i = -2; i <= 2; i++) {
            graphics.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, TEXT);
            graphics.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, TEXT);
        }

        boolean can = maxGive() > 0;
        for (BarControl control : BarControl.values()) {
            if (control == BarControl.TAKE || control == BarControl.CLOSE) continue;
            int[] spot = barSpot(control);
            if (spot == null) continue;
            boolean hover = inside(mouseX, mouseY, spot[0], spot[1], spot[2], spot[3]);
            if (control == BarControl.AMOUNT) {
                // The amount: a plain dark field, not a button.
                graphics.fill(spot[0], spot[1], spot[0] + spot[2], spot[1] + spot[3], PANEL_BORDER);
                graphics.fill(spot[0] + 1, spot[1] + 1, spot[0] + spot[2] - 1, spot[1] + spot[3] - 1, PANEL_DARK);
                String text = String.format(Locale.ROOT, "%,d", amount);
                graphics.drawString(font, text, spot[0] + (spot[2] - font.width(text) + 1) / 2, spot[1] + 6,
                        can ? TEXT : MUTED, false);
                continue;
            }
            String label = control == BarControl.GIVE ? String.format(Locale.ROOT, "Give %,d", amount) : control.label;
            StorageUi.button(graphics, font, spot[0], spot[1], spot[2], spot[3], label, hover, can);
        }
    }

    private static ItemStack givenStack(BuildOrderPayloads.GivenRow row) {
        return new ItemStack(BuiltInRegistries.ITEM.get(row.item()));
    }

    private static String shortCount(int count) {
        return count < 1000 ? Integer.toString(count) : String.format(Locale.ROOT, "%.1fk", count / 1000f);
    }

    // =========================================================================
    // Hit tests and hover text
    // =========================================================================

    private boolean overCards(double mouseX, double mouseY) {
        return mouseX >= left && mouseX < ordersRight() && mouseY >= cardsTop() && mouseY < cardsBottom();
    }

    private boolean overList(double mouseX, double mouseY) {
        return mouseX >= detailLeft() && mouseX < right && mouseY >= listTop() && mouseY < listBottom();
    }

    /** The card under the mouse, or -1. */
    private int cardAt(double mouseX, double mouseY) {
        List<BuildOrderPayloads.ProjectRow> rows = menu.projects();
        if (rows == null || !overCards(mouseX, mouseY)) return -1;
        for (int i = 0; i < rows.size(); i++) {
            if (inside(mouseX, mouseY, cardX(), cardY(i), cardW(), CARD_H)) return i;
        }
        return -1;
    }

    /** -1 up, 1 down, 0 neither: the arrows on the picked card. */
    private int arrowAt(double mouseX, double mouseY) {
        if (!overCards(mouseX, mouseY) || pickedRow() == null) return 0;
        if (inside(mouseX, mouseY, upX(), arrowY(pickedIndex), ARROW_W, ARROW_H)) return -1;
        if (inside(mouseX, mouseY, downX(), arrowY(pickedIndex), ARROW_W, ARROW_H)) return 1;
        return 0;
    }

    private int givenAt(double mouseX, double mouseY) {
        List<BuildOrderPayloads.GivenRow> given = menu.given();
        int shown = Math.min(given.size(), givenRows() * givenPerRow());
        for (int i = 0; i < shown; i++) {
            if (inside(mouseX, mouseY, givenCellX(i), givenCellY(i), CELL, CELL)) return i;
        }
        return -1;
    }

    @Nullable
    private BuildOrderPayloads.BlockNeed blockAt(double mouseX, double mouseY) {
        if (blocks == null || !ready || !overList(mouseX, mouseY)) return null;
        int index = (int) Math.floor((mouseY - listTop() + blockScroll) / LINE_H);
        return index >= 0 && index < blocks.size() ? blocks.get(index) : null;
    }

    private boolean overPause(double mouseX, double mouseY) {
        return pickedRow() != null && inside(mouseX, mouseY, pauseX(), buttonsY(), PAUSE_W, BUTTON_H);
    }

    private boolean overCancel(double mouseX, double mouseY) {
        return pickedRow() != null && inside(mouseX, mouseY, cancelX(), buttonsY(), CANCEL_W, BUTTON_H);
    }

    private boolean overGive(double mouseX, double mouseY) {
        return pickedRow() != null && !barOpen() && canGiveAny()
                && inside(mouseX, mouseY, giveX(), footerY(), giveW(), BUTTON_H);
    }

    /** The give bar control under the mouse, or null. */
    @Nullable
    private BarControl barAt(double mouseX, double mouseY) {
        if (!barOpen()) return null;
        for (BarControl control : BarControl.values()) {
            int[] spot = barSpot(control);
            if (spot != null && inside(mouseX, mouseY, spot[0], spot[1], spot[2], spot[3])) return control;
        }
        return null;
    }

    @Nullable
    List<Component> tooltip(int mouseX, int mouseY) {
        int arrow = arrowAt(mouseX, mouseY);
        if (arrow < 0) return List.of(Component.literal("Sooner"), gray("Moves this order up. The Builder"),
                gray("starts with the top one."));
        if (arrow > 0) return List.of(Component.literal("Later"), gray("Moves this order one place down."));
        BuildOrderPayloads.ProjectRow row = pickedRow();
        if (overPause(mouseX, mouseY) && row != null) {
            return row.paused()
                    ? List.of(Component.literal("Resume"), gray("The Builder picks this order up again."))
                    : List.of(Component.literal("Pause"), gray("The Builder skips this order"),
                            gray("until you press Resume."));
        }
        if (overCancel(mouseX, mouseY)) {
            return List.of(Component.literal("Cancel this order"), gray("Click twice to be sure."),
                    gray("The spot is free again after."));
        }
        if (overGive(mouseX, mouseY)) {
            return List.of(Component.literal(GIVE_ALL), gray("Hands the Builder every block this order"),
                    gray("is short of from your inventory."), gray("They stay yours."));
        }
        int given = givenAt(mouseX, mouseY);
        if (given >= 0) {
            BuildOrderPayloads.GivenRow pile = menu.given().get(given);
            return List.of(Component.literal(String.format(Locale.ROOT, "%,d %s", pile.count(),
                            givenStack(pile).getHoverName().getString())),
                    gray("Still yours. The Builder uses them"), gray("before the village's blocks."),
                    gray("Click to take them back."));
        }
        BarControl control = barAt(mouseX, mouseY);
        if (control != null) return barTooltip(control);
        BuildOrderPayloads.BlockNeed block = blockAt(mouseX, mouseY);
        if (block != null) {
            // How "have" is made up; the line itself already says how many are short.
            String name = stack(block).getHoverName().getString();
            String counts = String.format(Locale.ROOT, "%,d in storage or the pack, %,d given", block.have(),
                    block.given());
            String still = String.format(Locale.ROOT, "%,d still to place", block.need());
            if (!menu.freeBy().isEmpty()) return List.of(Component.literal(name), gray(still), gray(counts));
            boolean open = block.item().equals(chosen) && barOpen();
            return List.of(Component.literal(name), gray(still), gray(counts),
                    gray(open ? "Click again to close." : "Click to give some of yours."));
        }
        return null;
    }

    @Nullable
    private List<Component> barTooltip(BarControl control) {
        BuildOrderPayloads.BlockNeed row = chosenRow();
        if (row == null) return null;
        String name = stack(row).getHoverName().getString();
        return switch (control) {
            case ALL -> List.of(Component.literal("All"), gray("As many as you carry and"),
                    gray("this order still needs."));
            case GIVE -> maxGive() > 0
                    ? List.of(Component.literal(String.format(Locale.ROOT, "Give %,d %s", amount, name)),
                            gray("They stay yours. The Builder uses"), gray("them before the village's blocks."))
                    : carriedChosen() <= 0
                            ? List.of(Component.literal("You carry none"), gray("Pick some up, then come back."))
                            : List.of(Component.literal("Nothing to give"), gray("Players already gave all of these."));
            case TAKE -> List.of(Component.literal("Take back"),
                    gray(String.format(Locale.ROOT, "Returns all %,d %s you gave", givenByMe(), name)),
                    gray("to your inventory."));
            case CLOSE -> List.of(Component.literal("Close"), gray("Hides this bar."));
            default -> null;
        };
    }

    private static Component gray(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    // =========================================================================
    // Input
    // =========================================================================

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        int arrow = arrowAt(mouseX, mouseY);
        BuildOrderPayloads.ProjectRow row = pickedRow();
        List<BuildOrderPayloads.ProjectRow> rows = menu.projects();
        if (arrow != 0 && row != null && rows != null) {
            boolean can = arrow < 0 ? pickedIndex > 0 : pickedIndex < rows.size() - 1;
            if (can) action(row, arrow < 0 ? BuildOrderPayloads.UP : BuildOrderPayloads.DOWN);
            return true;
        }
        int card = cardAt(mouseX, mouseY);
        if (card >= 0 && rows != null) {
            pickedIndex = card;
            pick(rows.get(card).id());
            return true;
        }
        int given = givenAt(mouseX, mouseY);
        if (given >= 0) {
            PacketDistributor.sendToServer(new BuildOrderPayloads.GivenTakeBack(menu.villagerId(),
                    menu.given().get(given).item()));
            return true;
        }
        if (row == null) return false;
        if (overPause(mouseX, mouseY)) {
            action(row, BuildOrderPayloads.PAUSE);
            return true;
        }
        if (overCancel(mouseX, mouseY)) {
            if (row.id().equals(cancelAsked) && System.currentTimeMillis() < cancelAskedUntil) {
                cancelAsked = null;
                PacketDistributor.sendToServer(new BuildOrderPayloads.ProjectCancel(menu.villagerId(), row.id()));
            } else {
                cancelAsked = row.id();
                cancelAskedUntil = System.currentTimeMillis() + 3000;
            }
            return true;
        }
        if (overGive(mouseX, mouseY)) {
            action(row, BuildOrderPayloads.GIVE);
            return true;
        }
        BarControl control = barAt(mouseX, mouseY);
        if (control != null) {
            barClicked(row, control);
            return true;
        }
        if (blockMaxScroll() > 0 && mouseX >= listBarX() - 2 && mouseX < listBarX() + BAR_W + 2
                && mouseY >= listTop() && mouseY < listBottom()) {
            draggingBar = true;
            scrollTo(mouseY);
            return true;
        }
        BuildOrderPayloads.BlockNeed block = blockAt(mouseX, mouseY);
        if (block != null && menu.freeBy().isEmpty()) {
            // A block line opens the give bar for it; the same line again closes it.
            if (block.item().equals(chosen) && barOpen()) {
                chosen = null;
            } else {
                choose(block);
            }
            return true;
        }
        return false;
    }

    private void barClicked(BuildOrderPayloads.ProjectRow row, BarControl control) {
        switch (control) {
            case LESS16 -> amount -= 16;
            case LESS1 -> amount -= 1;
            case MORE1 -> amount += 1;
            case MORE16 -> amount += 16;
            case ALL -> amount = maxGive();
            case GIVE -> {
                if (maxGive() > 0 && amount > 0) {
                    PacketDistributor.sendToServer(new BuildOrderPayloads.GiveItem(menu.villagerId(), row.id(),
                            chosen, amount));
                }
            }
            case TAKE -> PacketDistributor.sendToServer(new BuildOrderPayloads.GivenTakeBack(menu.villagerId(),
                    chosen));
            case CLOSE -> chosen = null;
            default -> { }
        }
        clampAmount();
    }

    private void action(BuildOrderPayloads.ProjectRow row, int action) {
        PacketDistributor.sendToServer(new BuildOrderPayloads.ProjectAction(menu.villagerId(), row.id(), action));
    }

    boolean mouseDragged(double mouseY) {
        if (!draggingBar) return false;
        scrollTo(mouseY);
        return true;
    }

    boolean mouseReleased() {
        boolean was = draggingBar;
        draggingBar = false;
        return was;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (overCards(mouseX, mouseY)) {
            cardScroll = Mth.clamp(cardScroll - (int) Math.round(scrollY * (CARD_H + 2)), 0, cardMaxScroll());
            return true;
        }
        if (overList(mouseX, mouseY)) {
            blockScroll = Mth.clamp(blockScroll - (int) Math.round(scrollY * LINE_H), 0, blockMaxScroll());
            return true;
        }
        return false;
    }

    private void scrollTo(double mouseY) {
        double part = (mouseY - listTop()) / Math.max(1, listBottom() - listTop());
        blockScroll = (int) Math.round(Mth.clamp(part, 0, 1) * blockMaxScroll());
    }
}
