package com.alex.test.ui.screen;

import com.alex.test.network.BuilderDeskPayloads;
import com.alex.test.network.GenericSettingsPayloads;
import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.ui.GenericSettingsMenu;
import com.alex.test.village.villagers.builder.settings.BuilderSettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.alex.test.ui.screen.StorageUi.MUTED;
import static com.alex.test.ui.screen.StorageUi.PANEL_BORDER;
import static com.alex.test.ui.screen.StorageUi.PANEL_DARK;
import static com.alex.test.ui.screen.StorageUi.ROW;
import static com.alex.test.ui.screen.StorageUi.ROW_HOVER;
import static com.alex.test.ui.screen.StorageUi.TEXT;
import static com.alex.test.ui.screen.StorageUi.inside;

/**
 * The desk's Settings tab: the Builder's work settings drawn right here, in place of the
 * separate settings window it used to open. One column per section of the Builder's
 * settings (Behaviour, Buildings); switches and steppers look like the old window's, in
 * the desk's colours. The values come with the desk's opening data; a click changes the
 * value here at once and sends the same {@link GenericSettingsPayloads.Update} the old
 * window sent, which the server now also takes from an open desk.
 */
final class BuilderDeskSettingsTab {
    private static final int ROW_H = 20, ROW_STEP = 22, PAD = 10, SECTION_GAP = 12;
    private static final int GRID_MIN_W = 130, SWITCH_W = 34, STEP_W = 18;
    private static final int ON = 0xFF3FA34D, OFF = 0xFF71312E, KNOB = 0xFFFFFFFF, OFF_TEXT = 0xFFD06A5E;

    /** One drawn line: where it sits and which settings line it shows ({@code line} -1: a caption, {@link #FREE}). */
    private record Cell(int section, int line, int x, int y, int width, @Nullable String caption) {}

    /** {@code Cell.line} of the Free Building switch: not a settings row, drawn for players in Creative only. */
    private static final int FREE = -2;
    private static final String FREE_TIP = "ON: builds without using any blocks.\nIt still places them one at a time.\n"
            + "Only for Creative: leaving Creative\nor logging off switches it off.";

    private final BuilderDeskMenu menu;
    private final List<Cell> cells = new ArrayList<>();
    private int left, top, right, bottom;
    private boolean creative;
    private Font font;

    BuilderDeskSettingsTab(BuilderDeskMenu menu) {
        this.menu = menu;
    }

    private List<List<GenericSettingsMenu.RowLine>> sections() {
        return menu.snapshot().settings();
    }

    private static boolean viewerInCreative() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.isCreative();
    }

    private void layout(Font font, int left, int top, int right, int bottom) {
        boolean creative = viewerInCreative();
        if (this.font == font && left == this.left && top == this.top && right == this.right
                && bottom == this.bottom && creative == this.creative && !cells.isEmpty()) {
            return;
        }
        this.font = font;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.creative = creative;
        cells.clear();
        List<List<GenericSettingsMenu.RowLine>> sections = sections();
        int count = Math.max(1, sections.size());
        int columnW = (right - left - PAD * (count + 1)) / count;
        for (int s = 0; s < sections.size(); s++) {
            int x = left + PAD + s * (columnW + PAD);
            int y = top + PAD + 14; // under the section title
            List<GenericSettingsMenu.RowLine> lines = sections.get(s);
            int perRow = Math.max(1, (columnW + 4) / (GRID_MIN_W + 4));
            // A short window: more switches per row (down to 80 wide) so the list still fits.
            int entries = 0, others = 0;
            for (GenericSettingsMenu.RowLine line : lines) {
                if (line.entryId() != null) entries++;
                else others++;
            }
            int fitRows = Math.max(1, (bottom - top - PAD - 14 - 12 - others * ROW_STEP) / ROW_STEP);
            int needed = (entries + fitRows - 1) / fitRows;
            int widest = Math.max(1, (columnW + 4) / (80 + 4));
            perRow = Math.max(perRow, Math.min(widest, needed));
            int cellW = (columnW - (perRow - 1) * 4) / perRow;
            int slot = 0;
            SettingsSchema.Row gridRow = null;
            for (int i = 0; i < lines.size(); i++) {
                GenericSettingsMenu.RowLine line = lines.get(i);
                boolean entry = line.entryId() != null;
                if (entry) {
                    if (line.row() != gridRow) {
                        // A list's own name above its switches.
                        if (slot > 0) y += ROW_STEP;
                        gridRow = line.row();
                        slot = 0;
                        cells.add(new Cell(s, -1, x, y, columnW, line.row().label()));
                        y += 12;
                    }
                    int col = slot % perRow;
                    cells.add(new Cell(s, i, x + col * (cellW + 4), y, cellW, null));
                    slot++;
                    if (slot % perRow == 0) y += ROW_STEP;
                } else {
                    if (slot % perRow != 0) y += ROW_STEP;
                    slot = 0;
                    gridRow = null;
                    if (line.isHeader()) {
                        cells.add(new Cell(s, -1, x, y + 2, columnW, line.label()));
                        y += 14;
                    } else {
                        cells.add(new Cell(s, i, x, y, columnW, null));
                        y += ROW_STEP;
                    }
                }
            }
            if (s == 0 && creative) {
                // Under the Behaviour rows.
                if (slot % perRow != 0) y += ROW_STEP;
                cells.add(new Cell(s, FREE, x, y, columnW, null));
            }
        }
    }

    // =========================================================================
    // Drawing
    // =========================================================================

    void render(GuiGraphics graphics, Font font, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        layout(font, left, top, right, bottom);
        graphics.fill(left, top, right, bottom, PANEL_DARK);
        List<List<GenericSettingsMenu.RowLine>> sections = sections();
        if (sections.isEmpty()) {
            graphics.drawCenteredString(font, "This Builder has no settings.", (left + right) / 2,
                    (top + bottom) / 2 - 4, MUTED);
            return;
        }
        int count = sections.size();
        int columnW = (right - left - PAD * (count + 1)) / count;
        for (int s = 0; s < count; s++) {
            int x = left + PAD + s * (columnW + PAD);
            graphics.drawString(font, BuilderSettingsSchema.SCHEMA.sections().get(s).title(), x, top + PAD, TEXT, false);
            if (s > 0) graphics.fill(x - PAD / 2 - 1, top + PAD, x - PAD / 2, bottom - PAD, PANEL_BORDER);
        }
        for (Cell cell : cells) {
            if (cell.y() + ROW_H > bottom - 2) continue; // no room left (very small window)
            if (cell.line() == FREE) {
                boolean hover = inside(mouseX, mouseY, cell.x(), cell.y(), cell.width(), ROW_H);
                graphics.fill(cell.x(), cell.y(), cell.x() + cell.width(), cell.y() + ROW_H, hover ? ROW_HOVER : ROW);
                drawSwitch(graphics, font, cell, "Free Building", !menu.freeBy().isEmpty(), true);
                continue;
            }
            if (cell.line() < 0) {
                graphics.drawString(font, StorageUi.trim(font, cell.caption(), cell.width()), cell.x(), cell.y(),
                        MUTED, false);
                continue;
            }
            GenericSettingsMenu.RowLine line = sections.get(cell.section()).get(cell.line());
            boolean hover = inside(mouseX, mouseY, cell.x(), cell.y(), cell.width(), ROW_H);
            graphics.fill(cell.x(), cell.y(), cell.x() + cell.width(), cell.y() + ROW_H, hover ? ROW_HOVER : ROW);
            if (isStepper(line)) {
                stepper(graphics, font, cell, line);
            } else {
                toggle(graphics, font, cell, line);
            }
        }
    }

    private void toggle(GuiGraphics graphics, Font font, Cell cell, GenericSettingsMenu.RowLine line) {
        // The whole-row switches say ON/OFF; list entries have only the switch (narrow cells).
        drawSwitch(graphics, font, cell, line.label(), line.boolValue(), line.entryId() == null);
    }

    private static void drawSwitch(GuiGraphics graphics, Font font, Cell cell, String label, boolean on, boolean words) {
        int switchX = cell.x() + cell.width() - SWITCH_W - 6;
        int labelRoom = switchX - cell.x() - 12 - (words ? 26 : 0);
        graphics.drawString(font, StorageUi.trim(font, label, labelRoom), cell.x() + 6, cell.y() + 6, TEXT,
                false);
        if (words) {
            graphics.drawString(font, on ? "ON" : "OFF", switchX - 24, cell.y() + 6, on ? ON : OFF_TEXT, false);
        }
        graphics.fill(switchX, cell.y() + 4, switchX + SWITCH_W, cell.y() + 16, on ? ON : OFF);
        int knobX = on ? switchX + SWITCH_W - 12 : switchX + 2;
        graphics.fill(knobX, cell.y() + 5, knobX + 10, cell.y() + 15, KNOB);
    }

    private void stepper(GuiGraphics graphics, Font font, Cell cell, GenericSettingsMenu.RowLine line) {
        int[] layout = stepperLayout(font, line, cell);
        int minusX = layout[0], valueX = layout[1], valueW = layout[2], plusX = layout[3];
        graphics.drawString(font, StorageUi.trim(font, line.label(), minusX - cell.x() - 10), cell.x() + 6,
                cell.y() + 6, TEXT, false);
        int y = cell.y();
        graphics.fill(minusX, y + 3, minusX + STEP_W, y + 17, PANEL_BORDER);
        graphics.fill(plusX, y + 3, plusX + STEP_W, y + 17, PANEL_BORDER);
        graphics.drawString(font, "-", minusX + 7, y + 6, TEXT, false);
        graphics.drawString(font, "+", plusX + 6, y + 6, TEXT, false);
        String value = valueText(line);
        graphics.drawString(font, value, valueX + (valueW - font.width(value)) / 2, y + 6, TEXT, false);
    }

    private static String valueText(GenericSettingsMenu.RowLine line) {
        if (line.row().kind() == SettingsSchema.RowKind.CHOICE) {
            List<String> options = line.row().tierNames();
            return options.get(Math.clamp(line.intValue(), 0, options.size() - 1));
        }
        if (line.row().ticksAsSeconds()) {
            // Ticks as seconds with at most one decimal: 60 is "3s", 50 is "2.5s".
            int tenths = Math.round(line.intValue() / 2f);
            return tenths % 10 == 0 ? tenths / 10 + "s" : tenths / 10 + "." + tenths % 10 + "s";
        }
        return Integer.toString(line.intValue());
    }

    /** {minusX, valueX, valueWidth, plusX}; a choice's value area fits its longest option. */
    private static int[] stepperLayout(Font font, GenericSettingsMenu.RowLine line, Cell cell) {
        int valueW = 30;
        if (line.row().kind() == SettingsSchema.RowKind.CHOICE) {
            for (String option : line.row().tierNames()) valueW = Math.max(valueW, font.width(option) + 8);
        }
        int plusX = cell.x() + cell.width() - STEP_W - 4;
        int valueX = plusX - 3 - valueW;
        int minusX = valueX - 3 - STEP_W;
        return new int[] {minusX, valueX, valueW, plusX};
    }

    private static boolean isStepper(GenericSettingsMenu.RowLine line) {
        return line.row().kind() == SettingsSchema.RowKind.NUMERIC_STEPPER
                || line.row().kind() == SettingsSchema.RowKind.CHOICE;
    }

    // =========================================================================
    // Input and hover text
    // =========================================================================

    @Nullable
    private Cell cellAt(double mouseX, double mouseY) {
        for (Cell cell : cells) {
            if ((cell.line() >= 0 || cell.line() == FREE) && cell.y() + ROW_H <= bottom - 2
                    && inside(mouseX, mouseY, cell.x(), cell.y(), cell.width(), ROW_H)) {
                return cell;
            }
        }
        return null;
    }

    /** A switch flips on a click anywhere on its row; a stepper moves on its - and + buttons. */
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || font == null) return false;
        Cell cell = cellAt(mouseX, mouseY);
        if (cell == null) return false;
        if (cell.line() == FREE) {
            // Flipped here at once; the server's Projects message brings the truth back.
            LocalPlayer player = Minecraft.getInstance().player;
            boolean on = menu.freeBy().isEmpty();
            menu.setFreeBy(on && player != null ? player.getGameProfile().getName() : "");
            PacketDistributor.sendToServer(new BuilderDeskPayloads.FreeBuilding(menu.villagerId(), on));
            return true;
        }
        List<GenericSettingsMenu.RowLine> lines = sections().get(cell.section());
        GenericSettingsMenu.RowLine line = lines.get(cell.line());
        GenericSettingsMenu.RowLine changed;
        if (isStepper(line)) {
            int[] layout = stepperLayout(font, line, cell);
            int next = line.intValue();
            if (mouseX >= layout[0] && mouseX < layout[0] + STEP_W) next -= line.row().stepInt();
            else if (mouseX >= layout[3] && mouseX < layout[3] + STEP_W) next += line.row().stepInt();
            next = Math.max(line.row().minInt(), Math.min(line.row().maxInt(), next));
            if (next == line.intValue()) return true;
            changed = new GenericSettingsMenu.RowLine(line.row(), line.entryId(), line.label(), line.boolValue(), next);
        } else {
            changed = new GenericSettingsMenu.RowLine(line.row(), line.entryId(), line.label(), !line.boolValue(),
                    line.intValue());
        }
        lines.set(cell.line(), changed);
        PacketDistributor.sendToServer(new GenericSettingsPayloads.Update(menu.villagerId(),
                changed.row().settingId(), changed.encodeValue()));
        return true;
    }

    /** The hover text of the row under the mouse (the schema's own words), or null. */
    @Nullable
    String tooltip(double mouseX, double mouseY) {
        Cell cell = cellAt(mouseX, mouseY);
        if (cell == null) return null;
        if (cell.line() == FREE) return FREE_TIP;
        GenericSettingsMenu.RowLine line = sections().get(cell.section()).get(cell.line());
        String description = line.row().description();
        if (line.entryId() != null) {
            for (SettingsSchema.AllowListEntry entry : line.row().allowListEntries()) {
                if (entry.id().equals(line.entryId()) && !entry.description().isEmpty()) {
                    description = entry.description();
                    break;
                }
            }
        }
        return description == null || description.isEmpty() ? null : description;
    }
}
