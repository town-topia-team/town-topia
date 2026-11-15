package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.alex.test.ui.screen.StorageUi.*;
import static com.alex.test.ui.screen.VillagerScreen.COL_INTELLIGENCE;

/**
 * The Skill Progression window. It opens centred over the villager screen and
 * shows one skill's 1..100 track: the milestones along it, how far this
 * villager has come, where its intelligence starts slowing learning, and a
 * list of skill bonuses at any level the player hovers, each row with a line
 * explaining it. Drawn in the Mayor screen's style.
 *
 * <p>The window sizes itself to the GUI: narrower on a thin screen, and on a
 * short one (GUI scale Auto) its list shows fewer rows and scrolls.
 */
final class SkillProgressionPanel {
    private static final int PREFERRED_WIDTH = 280;
    /** Free space kept between the window and the screen edge. */
    private static final int SCREEN_EDGE = 4;
    /** Height of the window for jobs without a track. */
    private static final int EMPTY_HEIGHT = 100;
    /** Rows shown before the list starts scrolling, even on a tall screen. */
    private static final int MAX_ROWS = 6;

    private static final int TITLE_Y = 8;
    private static final int CLOSE_SIZE = 14;
    /** The dark body under the title, inset like the Mayor screen's. */
    private static final int BODY_Y = 24;
    private static final int BODY_MARGIN = 6;
    private static final int TRACK_INSET = 22;
    private static final int NODE_SIZE = 22;
    private static final int NODE_Y = 30;
    private static final int LINE_Y = 58;
    private static final int LINE_HEIGHT = 6;
    private static final int LABEL_Y = 68;
    private static final int LABEL_GAP = 22;
    private static final int HEADING_Y = 82;
    private static final int LIST_Y = 93;
    private static final int LIST_INSET = 4;
    private static final int ROW_HEIGHT = 26;
    private static final int ROW_GAP = 2;
    private static final int ROW_PITCH = ROW_HEIGHT + ROW_GAP;
    private static final int VALUE_WIDTH = 88;
    private static final int VALUE_HEIGHT = 16;
    private static final float SMALL = 0.75F;
    /** Explanation text: about 6 GUI pixels tall (three quarters); see descriptionScale(). */
    private static final float DESCRIPTION_SCALE = 0.75F;

    private static final int BAR_TRACK = 0xFF090909;
    private static final int NODE_REACHED = 0xFF3B4E3E;
    private static final int VALUE_BOX = 0xFF222222;
    private static final int SHADE = 0x99141414;
    private static final int SCROLLBAR_W = 4;
    private static final int SCROLLBAR_GAP = 2;

    /** One line of the list: a stat compared now → then, or an unlock. */
    private record Row(String title, @Nullable String from, String value, int valueColor,
                       String description) {}

    private final Font font;
    private final Profession profession;
    private final Item token;
    @Nullable private final SkillProgression progression;
    private final int allRows;

    @Nullable private Integer pinned;
    private int scrollMouseX = Integer.MIN_VALUE;
    private int scrollMouseY = Integer.MIN_VALUE;

    private int listScroll;
    private int maxListScroll;
    private int listRows;
    private List<Row> shownRows = List.of();
    private int shownPreview = -1;
    private boolean draggingScrollbar;

    private int x;
    private int y;
    private int width = PREFERRED_WIDTH;
    private int panelHeight = EMPTY_HEIGHT;

    SkillProgressionPanel(Font font, Profession profession, Item token) {
        this.font = font;
        this.profession = profession;
        this.token = token;
        this.progression = SkillProgression.of(profession);
        int rows = 0;
        if (progression != null) {
            // At level 100 every stat and every unlock is listed.
            rows = progression.stats().apply(1).size();
            for (SkillProgression.Milestone milestone : progression.milestones()) {
                rows += milestone.unlocks().size();
            }
        }
        this.allRows = rows;
    }

    Profession profession() {
        return profession;
    }

    /**
     * Sizes the window to the screen and centres it. On a short screen the
     * list keeps fewer rows (at least one) and the window shrinks to match.
     */
    void layout(int screenWidth, int screenHeight) {
        width = Math.min(PREFERRED_WIDTH, screenWidth - 2 * SCREEN_EDGE);
        int room = screenHeight - 2 * SCREEN_EDGE;
        if (progression == null) {
            panelHeight = Math.min(EMPTY_HEIGHT, room);
        } else {
            int fit = (room - heightFor(0)) / ROW_PITCH;
            panelHeight = heightFor(Math.clamp(fit, 1, Math.max(1, Math.min(MAX_ROWS, allRows))));
        }
        x = (screenWidth - width) / 2;
        y = (screenHeight - panelHeight) / 2;
        // Fewer rows may fit now: keep the scroll inside the shorter list.
        maxListScroll = Math.max(0, listRows - visibleRows());
        listScroll = Math.min(listScroll, maxListScroll);
    }

    /** Window height for a list of {@code rows} rows: the rows, then the body's padding and edge. */
    private static int heightFor(int rows) {
        return LIST_Y + rows * ROW_PITCH - ROW_GAP + LIST_INSET + BODY_MARGIN;
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + panelHeight;
    }

    // =========================================================================
    // Drawing
    // =========================================================================

    void render(GuiGraphics graphics, int level, int intelligence, int mouseX, int mouseY) {
        graphics.fill(x - 2, y - 2, x + width + 2, y + panelHeight + 2, OUTER);
        graphics.fill(x, y, x + width, y + panelHeight, PANEL);
        graphics.fill(x + BODY_MARGIN, y + BODY_Y, x + width - BODY_MARGIN,
                y + panelHeight - BODY_MARGIN, PANEL_DARK);
        drawTitle(graphics, level, mouseX, mouseY);

        if (progression == null) {
            int center = x + width / 2;
            graphics.drawCenteredString(font, Tr.s("screen.dynamicvillages.skills.none",
                    "No skill progression for %s yet.", profession.shownName()), center, y + 46, TEXT);
            String[] which = Tr.s("screen.dynamicvillages.skills.none.desc",
                    "Farmers, lumberjacks, miners, druids,\ncooks, bards, guards and captains have one.").split("\n");
            for (int i = 0; i < which.length; i++) {
                graphics.drawCenteredString(font, which[i], center, y + 64 + i * 11, MUTED);
            }
            return;
        }

        int preview = previewLevel(level, mouseX, mouseY);
        if (preview != shownPreview) {
            // A different level is a different list; start it from the top.
            shownPreview = preview;
            listScroll = 0;
        }
        drawTrack(graphics, level, intelligence, preview, mouseX, mouseY);
        drawList(graphics, level, preview, mouseX, mouseY);
    }

    private void drawTitle(GuiGraphics graphics, int level, int mouseX, int mouseY) {
        int closeX = closeX();
        int closeY = closeY();
        button(graphics, font, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE, "x",
                inside(mouseX, mouseY, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE), true);

        String levelText = Tr.s("screen.dynamicvillages.skills.level", "Level %s", level);
        int levelX = closeX - 6 - font.width(levelText);
        graphics.drawString(font, levelText, levelX, y + TITLE_Y, GOLD, false);

        graphics.renderFakeItem(new ItemStack(token), x + BODY_MARGIN, y + 4);
        int titleX = x + BODY_MARGIN + 20;
        graphics.drawString(font, trim(font, Tr.s("screen.dynamicvillages.skills.title", "%s Skill Progression",
                        profession.shownName()),
                levelX - 6 - titleX), titleX, y + TITLE_Y, TEXT, false);
    }

    private void drawTrack(GuiGraphics graphics, int level, int intelligence, int preview,
                           int mouseX, int mouseY) {
        int left = trackLeft();
        int right = trackRight();
        int lineY = y + LINE_Y;
        int reached = levelX(level);

        SkillProgression.Milestone next = progression.nextMilestone(level);
        for (SkillProgression.Milestone milestone : progression.milestones()) {
            drawNode(graphics, milestone, level, next, preview, mouseX, mouseY);
        }

        // The bar: a grey border round a black track, the fill inset by one.
        graphics.fill(left - 2, lineY - 1, right + 2, lineY + LINE_HEIGHT + 1, PANEL_BORDER);
        graphics.fill(left - 1, lineY, right + 1, lineY + LINE_HEIGHT, BAR_TRACK);
        graphics.fill(left, lineY + 1, reached + 1, lineY + LINE_HEIGHT - 1, ACCENT);

        if (intelligence >= 1 && intelligence <= 100) {
            int wall = levelX(intelligence);
            graphics.fill(wall, lineY - 2, wall + 2, lineY + LINE_HEIGHT + 2, COL_INTELLIGENCE);
        }

        if (progression.milestoneExactly(preview) == null) {
            int marker = levelX(preview);
            graphics.fill(marker, lineY - 2, marker + 1, lineY + LINE_HEIGHT + 2, TEXT);
        }

        drawLevelLabels(graphics, level, preview, next);
    }

    private void drawNode(GuiGraphics graphics, SkillProgression.Milestone milestone, int level,
                          @Nullable SkillProgression.Milestone next, int preview,
                          int mouseX, int mouseY) {
        int nodeX = nodeX(milestone);
        int nodeY = y + NODE_Y;
        int center = levelX(milestone.level());
        boolean unlocked = level >= milestone.level();
        boolean isNext = milestone == next;
        boolean selected = preview == milestone.level();
        boolean hovered = inside(mouseX, mouseY, nodeX, nodeY, NODE_SIZE, NODE_SIZE);

        // A line from the node down to its level on the bar.
        graphics.fill(center, nodeY + NODE_SIZE, center + 1, y + LINE_Y, PANEL_BORDER);

        // Styled like the Mayor screen's tabs: reached ones green with an accent underline.
        int border = selected || hovered ? BUTTON_HOVER_BORDER : isNext ? GOLD : PANEL_BORDER;
        graphics.fill(nodeX, nodeY, nodeX + NODE_SIZE, nodeY + NODE_SIZE, border);
        graphics.fill(nodeX + 1, nodeY + 1, nodeX + NODE_SIZE - 1, nodeY + NODE_SIZE - 1,
                unlocked ? NODE_REACHED : selected || hovered ? ROW_HOVER : ROW);
        if (unlocked) {
            graphics.fill(nodeX + 1, nodeY + NODE_SIZE - 2, nodeX + NODE_SIZE - 1,
                    nodeY + NODE_SIZE - 1, ACCENT);
        }

        Item icon = milestone.icon() == null ? token : milestone.icon();
        graphics.renderFakeItem(new ItemStack(icon), nodeX + 3, nodeY + 3);
        if (!unlocked && !isNext && !hovered && !selected) {
            // Items draw 150 deep; lift the shade just above them, still under tooltips.
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 160.0F);
            graphics.fill(nodeX + 1, nodeY + 1, nodeX + NODE_SIZE - 1, nodeY + NODE_SIZE - 1, SHADE);
            graphics.pose().popPose();
        }
    }

    /**
     * Level numbers under the bar. The previewed level is always shown, in white,
     * then milestones, then the ends and quarters; any that would crowd one
     * already placed is left out.
     */
    private void drawLevelLabels(GuiGraphics graphics, int level, int preview,
                                 @Nullable SkillProgression.Milestone next) {
        List<Integer> order = new ArrayList<>();
        order.add(preview);
        for (SkillProgression.Milestone milestone : progression.milestones()) {
            order.add(milestone.level());
        }
        for (int fixed : new int[]{1, 100, 25, 50, 75}) order.add(fixed);

        List<Integer> placed = new ArrayList<>();
        for (int label : order) {
            int labelX = levelX(label);
            boolean crowded = false;
            for (int other : placed) crowded |= Math.abs(other - labelX) < LABEL_GAP;
            if (crowded) continue;
            placed.add(labelX);

            SkillProgression.Milestone milestone = progression.milestoneExactly(label);
            int color = label == preview ? TEXT
                    : milestone == null ? MUTED
                    : level >= label ? ACCENT
                    : milestone == next ? GOLD : MUTED;
            String text = Integer.toString(label);
            graphics.drawString(font, text, labelX + 1 - font.width(text) / 2, y + LABEL_Y, color, false);
        }
    }

    private void drawList(GuiGraphics graphics, int level, int preview, int mouseX, int mouseY) {
        List<Row> rows = rows(level, preview);
        shownRows = rows;
        int visible = visibleRows();
        listRows = rows.size();
        maxListScroll = Math.max(0, rows.size() - visible);
        listScroll = Math.clamp(listScroll, 0, maxListScroll);

        graphics.drawString(font, Tr.s("screen.dynamicvillages.skills.bonuses", "Skill bonuses"),
                listLeft(), y + HEADING_Y, MUTED, false);

        int left = listLeft();
        int right = listRight();
        int hoveredRow = rowAt(mouseX, mouseY);
        for (int i = 0; i < visible && listScroll + i < rows.size(); i++) {
            Row row = rows.get(listScroll + i);
            int rowY = y + LIST_Y + i * ROW_PITCH;
            boolean hovered = listScroll + i == hoveredRow;

            // As the Mayor screen's rule rows: title, explanation under it, value box on the right.
            graphics.fill(left, rowY, right, rowY + ROW_HEIGHT, PANEL_BORDER);
            graphics.fill(left + 1, rowY + 1, right - 1, rowY + ROW_HEIGHT - 1, hovered ? ROW_HOVER : ROW);

            int boxX = valueBoxX();
            int boxY = rowY + (ROW_HEIGHT - VALUE_HEIGHT) / 2;
            graphics.fill(boxX, boxY, boxX + valueWidth(), boxY + VALUE_HEIGHT, PANEL_BORDER);
            graphics.fill(boxX + 1, boxY + 1, boxX + valueWidth() - 1, boxY + VALUE_HEIGHT - 1, VALUE_BOX);
            drawValue(graphics, row, boxX, boxY);

            int textWidth = textWidth();
            graphics.drawString(font, trim(font, row.title(), textWidth), left + 6, rowY + 4, TEXT, false);
            graphics.pose().pushPose();
            graphics.pose().translate(left + 6, rowY + 16, 0.0F);
            graphics.pose().scale(descriptionScale(), descriptionScale(), 1.0F);
            graphics.drawString(font, trim(font, row.description(), Math.round(textWidth / descriptionScale())),
                    0, 0, MUTED, false);
            graphics.pose().popPose();
        }

        if (maxListScroll > 0) {
            scrollbar(graphics, scrollbarX(), y + LIST_Y, listHeight(), listScroll, maxListScroll, visible);
        }
    }

    /**
     * "now → then" centred in the value box. Full size when it fits, else
     * smaller; if even that is too wide only the new value is shown.
     */
    private void drawValue(GuiGraphics graphics, Row row, int boxX, int boxY) {
        String from = row.from() == null ? "" : row.from() + " → ";
        int room = valueWidth() - 6;
        float scale = 1.0F;
        if (font.width(from + row.value()) > room) {
            scale = SMALL;
            if (font.width(from + row.value()) * SMALL > room) from = "";
        }
        float textWidth = font.width(from + row.value()) * scale;
        float textX = boxX + (valueWidth() - textWidth) / 2.0F;
        float textY = boxY + (scale == 1.0F ? 4 : 5);

        graphics.pose().pushPose();
        graphics.pose().translate(textX, textY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, from, 0, 0, MUTED, false);
        graphics.drawString(font, row.value(), font.width(from), 0, row.valueColor(), false);
        graphics.pose().popPose();
    }

    /**
     * What the villager can do at {@code preview}: unlocks it would newly gain
     * first, then every stat compared with today, then unlocks it already has,
     * newest first.
     */
    private List<Row> rows(int level, int preview) {
        List<Row> gained = new ArrayList<>();
        List<Row> owned = new ArrayList<>();
        for (SkillProgression.Milestone milestone : progression.milestones().reversed()) {
            if (milestone.level() > preview) continue;
            boolean has = level >= milestone.level();
            for (SkillProgression.Unlock unlock : milestone.unlocks()) {
                (has ? owned : gained).add(new Row(unlock.title(), null,
                        Tr.s("screen.dynamicvillages.skills.level", "Level %s", milestone.level()), has ? ACCENT : GOLD,
                        unlock.description()));
            }
        }

        List<Row> rows = new ArrayList<>(gained.reversed());
        List<SkillProgression.Stat> now = progression.stats().apply(level);
        List<SkillProgression.Stat> then = progression.stats().apply(preview);
        for (int i = 0; i < then.size(); i++) {
            SkillProgression.Stat stat = then.get(i);
            String nowValue = now.get(i).value();
            boolean same = nowValue.equals(stat.value());
            int color = same ? TEXT : preview > level ? ACCENT : MUTED;
            rows.add(new Row(stat.label(), same ? null : nowValue, stat.value(), color,
                    stat.description()));
        }
        rows.addAll(owned);
        return rows;
    }

    // =========================================================================
    // Input
    // =========================================================================

    /** @return true when the close button was pressed. */
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inside((int) mouseX, (int) mouseY, closeX(), closeY(), CLOSE_SIZE, CLOSE_SIZE)) {
            return true;
        }
        if (progression == null) return false;
        if (button == 0 && maxListScroll > 0 && insideScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            scrollListTo(mouseY);
            return false;
        }
        if (button == 1) {
            pinned = null;
            return false;
        }
        int hovered = hoverLevel((int) mouseX, (int) mouseY);
        if (button == 0 && hovered > 0) {
            pinned = pinned != null && pinned == hovered ? null : hovered;
        }
        return false;
    }

    void mouseDragged(double mouseY) {
        if (draggingScrollbar) scrollListTo(mouseY);
    }

    void mouseReleased() {
        draggingScrollbar = false;
    }

    /** The wheel scrolls the list over the list, and steps the level over the bar. */
    void mouseScrolled(double mouseX, double mouseY, double scrollY, int level) {
        if (progression == null || scrollY == 0) return;
        if (mouseY >= y + HEADING_Y) {
            listScroll = Math.clamp(listScroll + (scrollY < 0 ? 1 : -1), 0, maxListScroll);
            return;
        }
        int from = previewLevel(level, (int) mouseX, (int) mouseY);
        pinned = Math.clamp(from + (scrollY > 0 ? 1 : -1), 1, 100);
        scrollMouseX = (int) mouseX;
        scrollMouseY = (int) mouseY;
    }

    void renderTooltip(GuiGraphics graphics, int intelligence, int mouseX, int mouseY) {
        if (progression == null) return;
        int hoveredRow = rowAt(mouseX, mouseY);
        if (hoveredRow >= 0) {
            // Only when the row had to cut its text short; otherwise it is all on show.
            Row row = shownRows.get(hoveredRow);
            if (font.width(row.title()) > textWidth()
                    || font.width(row.description()) * descriptionScale() > textWidth()) {
                List<FormattedCharSequence> lines = new ArrayList<>();
                lines.add(Component.literal(row.title()).getVisualOrderText());
                lines.addAll(font.split(Component.literal(row.description())
                        .withStyle(ChatFormatting.GRAY), 180));
                graphics.renderTooltip(font, lines, mouseX, mouseY);
            }
            return;
        }
        for (SkillProgression.Milestone milestone : progression.milestones()) {
            if (inside(mouseX, mouseY, nodeX(milestone), y + NODE_Y, NODE_SIZE, NODE_SIZE)) {
                graphics.renderTooltip(font, Tr.c("screen.dynamicvillages.skills.milestone",
                        "Level %1$s: %2$s", milestone.level(), milestone.title()), mouseX, mouseY);
                return;
            }
        }
        if (intelligence < 1 || intelligence > 100) return;
        int wall = levelX(intelligence);
        if (mouseX >= wall - 1 && mouseX <= wall + 2
                && mouseY >= y + LINE_Y - 3 && mouseY < y + LINE_Y + LINE_HEIGHT + 3) {
            graphics.renderComponentTooltip(font, Tr.lines("screen.dynamicvillages.skills.intelligence",
                    "Intelligence %1$s\nBelow level %1$s this villager learns a little faster.\n"
                            + "Past it, learning slows more with every level.", intelligence),
                    mouseX, mouseY);
        }
    }

    /**
     * The level the list describes: whatever the mouse is on, else the pinned
     * level, else the next milestone (or 100 once they are all reached). Right
     * after a scroll the mouse has not moved, so the scrolled level wins.
     */
    private int previewLevel(int level, int mouseX, int mouseY) {
        int hovered = hoverLevel(mouseX, mouseY);
        boolean justScrolled = mouseX == scrollMouseX && mouseY == scrollMouseY;
        if (hovered > 0 && !justScrolled) return hovered;
        if (pinned != null) return pinned;
        SkillProgression.Milestone next = progression.nextMilestone(level);
        return next != null ? next.level() : 100;
    }

    /** The level under the mouse on the nodes or the bar, snapping onto milestones; -1 if none. */
    private int hoverLevel(int mouseX, int mouseY) {
        for (SkillProgression.Milestone milestone : progression.milestones()) {
            if (inside(mouseX, mouseY, nodeX(milestone), y + NODE_Y, NODE_SIZE, NODE_SIZE)) {
                return milestone.level();
            }
        }
        int left = trackLeft();
        int right = trackRight();
        if (mouseY < y + LINE_Y - 4 || mouseY >= y + LABEL_Y + 9
                || mouseX < left - 3 || mouseX > right + 3) {
            return -1;
        }
        for (SkillProgression.Milestone milestone : progression.milestones()) {
            if (Math.abs(levelX(milestone.level()) - mouseX) <= 2) return milestone.level();
        }
        return Math.clamp(1 + Math.round((mouseX - left) * 99F / (right - left)), 1, 100);
    }

    /** Index into the shown list of the row under the mouse, or -1 (gaps between rows count as none). */
    private int rowAt(int mouseX, int mouseY) {
        int top = y + LIST_Y;
        if (mouseX < listLeft() || mouseX >= listRight() || mouseY < top) return -1;
        int i = (mouseY - top) / ROW_PITCH;
        if (i >= visibleRows() || (mouseY - top) % ROW_PITCH >= ROW_HEIGHT) return -1;
        int index = listScroll + i;
        return index < shownRows.size() ? index : -1;
    }

    private boolean insideScrollbar(double mouseX, double mouseY) {
        int barX = scrollbarX();
        return mouseX >= barX - 1 && mouseX < barX + SCROLLBAR_W + 1
                && mouseY >= y + LIST_Y && mouseY < y + LIST_Y + listHeight();
    }

    private void scrollListTo(double mouseY) {
        if (maxListScroll <= 0) return;
        int height = listHeight();
        int total = Math.max(1, visibleRows() + maxListScroll);
        int thumb = Math.min(height, Math.max(18, height * visibleRows() / total));
        double t = (mouseY - y - LIST_Y - thumb / 2.0) / Math.max(1, height - thumb);
        listScroll = Math.clamp(Math.round(t * maxListScroll), 0, maxListScroll);
    }

    // =========================================================================
    // Layout and helpers
    // =========================================================================

    private int visibleRows() {
        return Math.max(1, (panelHeight - heightFor(0) + ROW_GAP) / ROW_PITCH);
    }

    /** The rows and the scrollbar beside them share this height. */
    private int listHeight() {
        return visibleRows() * ROW_PITCH - ROW_GAP;
    }

    private int listLeft() {
        return x + BODY_MARGIN + LIST_INSET;
    }

    /** The rows narrow to make room for the scrollbar beside them when one is needed. */
    private int listRight() {
        return x + width - BODY_MARGIN - LIST_INSET
                - (maxListScroll > 0 ? SCROLLBAR_W + SCROLLBAR_GAP : 0);
    }

    private int scrollbarX() {
        return x + width - BODY_MARGIN - LIST_INSET - SCROLLBAR_W;
    }

    /** The value box gives way on a thin screen. */
    private int valueWidth() {
        return Math.min(VALUE_WIDTH, (listRight() - listLeft()) * 2 / 5);
    }

    private int valueBoxX() {
        return listRight() - 5 - valueWidth();
    }

    private int textWidth() {
        return valueBoxX() - 6 - (listLeft() + 6);
    }

    /**
     * {@link #DESCRIPTION_SCALE} nudged so each font pixel covers a whole number
     * of screen pixels at the current GUI scale: 3/4 at scale 4, 2/3 at scale 3.
     * A size in between draws some strokes thicker than others and looks blocky.
     */
    private static float descriptionScale() {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        return (float) (Math.max(1, Math.round(DESCRIPTION_SCALE * guiScale)) / guiScale);
    }

    private int trackLeft() {
        return x + TRACK_INSET;
    }

    private int trackRight() {
        return x + width - TRACK_INSET;
    }

    private int levelX(int level) {
        return trackLeft() + Math.round((Math.clamp(level, 1, 100) - 1)
                * (trackRight() - trackLeft()) / 99F);
    }

    private int nodeX(SkillProgression.Milestone milestone) {
        return Math.clamp(levelX(milestone.level()) - NODE_SIZE / 2,
                x + BODY_MARGIN + 3, x + width - BODY_MARGIN - 3 - NODE_SIZE);
    }

    private int closeX() {
        return x + width - BODY_MARGIN - CLOSE_SIZE;
    }

    private int closeY() {
        return y + 5;
    }

    private static boolean inside(int px, int py, int left, int top, int w, int h) {
        return px >= left && px < left + w && py >= top && py < top + h;
    }
}
