package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.client.BardTrackPreview;
import com.alex.test.network.GenericSettingsPayloads;
import com.alex.test.ui.GenericSettingsMenu;
import com.alex.test.village.villagers.bard.BardInstrument;
import com.alex.test.village.villagers.bard.settings.BardSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsText;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Bard's Music tab, drawn by hand instead of the plain row list (10-24, round 6 of
 * docs/plans/2026-10-24-bard-music-settings.md): the instruments on the left, the tracks of the
 * one clicked on the right, volume and listening range along the bottom.
 *
 * Clicking an instrument only shows its tracks; the button under the list (or a double click)
 * makes the bard play it. Every value is still one of the schema's rows, changed through the
 * menu's lines and the usual Update payload, so the server and the saved settings are the same
 * as with the plain list.
 */
final class BardMusicPage {

    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFAAAAAA;
    private static final int GREEN = 0xFF30D158;
    private static final int OFF = 0xFF71312E;
    private static final int OFF_TEXT = 0xFF8A8A8A;
    private static final int INSET = 0xFF111111;
    private static final int ROW = 0xFF282828;
    private static final int ROW_HOVER = 0xFF303030;
    private static final int SELECTED = 0xFF3B4E3E;
    private static final int ACCENT = 0xFF6BAF72;
    private static final int BUTTON = 0xFF3B3B3B;
    private static final int BUTTON_HOVER = 0xFF4A4A4A;
    private static final int LOCKED = 0xFF4B5E4B;

    /** The settings window's width (GenericSettingsScreen.WIDTH). */
    private static final int WIDTH = 302;
    /** The column headings use SettingsHeading's 20 px slot: text at +8, so 59. */
    private static final int HEAD_Y = 51;
    private static final int ROW_TOP = 71;
    /** Track rows. */
    private static final int ROW_STEP = 20;
    private static final int ROW_H = 18;
    /**
     * Instrument rows: the track rows' size since the bongos came (B1, 10-24), so six of them and the button end
     * on the line where seven tracks end, each row level with a track row (6b had five at 22 / 20).
     */
    private static final int INST_STEP = 20;
    private static final int INST_H = 18;
    private static final int BUTTON_GAP = 2;
    private static final int LEFT_X = 10;
    /** Fits "Bard's choice" beside "plays", and the Volume stepper with its word, under the same column. */
    private static final int LEFT_W = 118;
    private static final int RIGHT_X = LEFT_X + LEFT_W + 4;
    /** Fits "Get Up and Go 18s" beside the switch, with room for round 7's play button on the left. */
    private static final int RIGHT_W = WIDTH - 10 - RIGHT_X;
    private static final int BUTTON_H = ROW_H;
    private static final int SWITCH_W = 22;
    private static final int SWITCH_H = 10;
    /** The play button's click area at a track row's left end; the 12 px button is drawn inside it (round 7). */
    private static final int PLAY_HIT_W = 17;
    private static final int PLAYING_BOX = 0xFF1F3D27;
    /**
     * The instrument rows top to bottom, as saved values: Violin, Flute, Bass, Guitar, Bongos, Bard's choice.
     * The bongos are saved after Bard's choice (BardInstrument.BONGOS_CHOICE), so the order here is the page's own.
     */
    private static final int[] ORDER = {0, 1, 2, 3, BardInstrument.BONGOS_CHOICE, BardInstrument.BARDS_CHOICE};
    private static final int CHOICES = ORDER.length;
    private static final int DOUBLE_CLICK_MS = 300;
    /**
     * Track rows shown at once. Seven keeps the window at 240 px, the height GUI scale Auto gives on
     * 1280x720, 2560x1440, 3840x2160 and the default 854x480 window (F8, 10-24); a longer list scrolls.
     */
    private static final int VISIBLE_TRACKS = 7;
    private static final int BAR_W = 4;

    private final GenericSettingsMenu menu;
    private final int section;
    /** The longest track list sets the window's height, up to VISIBLE_TRACKS rows. */
    private final int maxTracks;
    /** The instrument whose tracks are shown, as its saved value (0..5); not the one the bard plays. */
    private int viewing;
    private int lastClicked = -1;
    private long lastClickAt;
    /** First track row shown, for a list longer than VISIBLE_TRACKS; back to 0 when another instrument is shown. */
    private int trackScroll;
    private boolean draggingBar;

    private BardMusicPage(GenericSettingsMenu menu, int section) {
        this.menu = menu;
        this.section = section;
        int longest = 1;
        for (int choice : ORDER) {
            if (!isPick(choice)) longest = Math.max(longest, trackLines(choice).size());
        }
        this.maxTracks = Math.min(longest, VISIBLE_TRACKS);
        this.viewing = played();
    }

    /** The page for a Bard's settings menu (on the tab holding the Instrument row), or null. */
    @Nullable
    static BardMusicPage of(GenericSettingsMenu menu) {
        if (menu.schema().profession() != Profession.BARD) return null;
        for (int s = 0; s < menu.sectionCount(); s++) {
            for (GenericSettingsMenu.RowLine line : menu.linesForSection(s)) {
                if (!line.isHeader() && BardSettingsSchema.INSTRUMENT.equals(line.row().settingId())) {
                    return new BardMusicPage(menu, s);
                }
            }
        }
        return null;
    }

    int section() {
        return section;
    }

    /** Window height on this tab. */
    int height() {
        return stripY() + ROW_H + 3 + 8;
    }

    private int tracksBottom() {
        return ROW_TOP + maxTracks * ROW_STEP - (ROW_STEP - ROW_H);
    }

    /** Where both columns end: the last track, or the button under the instruments, whichever is lower. */
    private int listBottom() {
        int instrumentsBottom = ROW_TOP + CHOICES * INST_STEP - (INST_STEP - INST_H);
        return Math.max(tracksBottom(), instrumentsBottom + BUTTON_GAP + BUTTON_H);
    }

    /** Whether {@code choice} (a saved value) is "Bard's choice", which has no tracks of its own. */
    private static boolean isPick(int choice) {
        return choice == BardInstrument.BARDS_CHOICE;
    }

    /** The button sits at the bottom of the left column, level with the last track (6b, 10-24). */
    private int buttonY() {
        return listBottom() - BUTTON_H;
    }

    private int stripY() {
        // A 2 px gap (was 5): with seven tracks the window is 240 px, not 243 (F8, 10-24).
        return listBottom() + 2;
    }

    /** Rows past the visible ones in the shown list; 0 when it fits (or Bard's choice is shown). */
    private int maxTrackScroll() {
        if (isPick(viewing)) return 0;
        return Math.max(0, trackLines(viewing).size() - VISIBLE_TRACKS);
    }

    /** Track rows are narrower while the list scrolls, leaving room for the bar on their right. */
    private int trackW() {
        return maxTrackScroll() > 0 ? RIGHT_W - BAR_W - 2 : RIGHT_W;
    }

    private int barX() {
        return RIGHT_X + RIGHT_W - BAR_W;
    }

    private void scrollTracksTo(int row) {
        trackScroll = Math.clamp(row, 0, maxTrackScroll());
    }

    /** The shown list's line index for a visible row slot, or -1 for an empty or hidden slot. */
    private int trackLineAt(int slot) {
        if (isPick(viewing) || slot < 0 || slot >= VISIBLE_TRACKS) return -1;
        List<Integer> tracks = trackLines(viewing);
        int row = slot + Math.min(trackScroll, maxTrackScroll());
        return row < tracks.size() ? tracks.get(row) : -1;
    }

    // -------------------------------------------------------------------------
    // The menu's lines
    // -------------------------------------------------------------------------

    private List<GenericSettingsMenu.RowLine> lines() {
        return menu.linesForSection(section);
    }

    /** Index in this tab's lines of a whole-row setting (entryId null) or one list entry; -1 if missing. */
    private int indexOf(String settingId, @Nullable String entryId) {
        List<GenericSettingsMenu.RowLine> lines = lines();
        for (int i = 0; i < lines.size(); i++) {
            GenericSettingsMenu.RowLine line = lines.get(i);
            if (!line.isHeader() && line.row().settingId().equals(settingId)
                    && Objects.equals(line.entryId(), entryId)) return i;
        }
        return -1;
    }

    /** The choice the bard plays now, as its saved value (0..5). */
    private int played() {
        int at = indexOf(BardSettingsSchema.INSTRUMENT, null);
        return at < 0 ? BardInstrument.BARDS_CHOICE
                : Math.clamp(lines().get(at).intValue(), 0, BardInstrument.BONGOS_CHOICE);
    }

    private static String tracksSetting(int choice) {
        return BardSettingsSchema.tracksOf(BardInstrument.fromSetting(choice));
    }

    /** Line indices of one instrument's tracks, in the schema's order. */
    private List<Integer> trackLines(int choice) {
        String setting = tracksSetting(choice);
        List<Integer> out = new ArrayList<>();
        List<GenericSettingsMenu.RowLine> lines = lines();
        for (int i = 0; i < lines.size(); i++) {
            GenericSettingsMenu.RowLine line = lines.get(i);
            if (!line.isHeader() && line.entryId() != null && line.row().settingId().equals(setting)) out.add(i);
        }
        return out;
    }

    private String choiceName(int choice) {
        int at = indexOf(BardSettingsSchema.INSTRUMENT, null);
        if (at < 0) return "";
        SettingsSchema.Row row = lines().get(at).row();
        return SettingsText.option(menu.schema(), row, row.tierNames().get(choice));
    }

    /** The heading over the right column: the schema's "Violin Tracks" heading, or "Bard's choice". */
    private String rightHeading() {
        if (isPick(viewing)) return choiceName(viewing);
        List<Integer> tracks = trackLines(viewing);
        if (!tracks.isEmpty() && tracks.get(0) > 0 && lines().get(tracks.get(0) - 1).isHeader()) {
            return lines().get(tracks.get(0) - 1).label();
        }
        return choiceName(viewing);
    }

    /** The last track still on in its list: greyed, and a click is refused (the server refuses it too). */
    private boolean lockedOn(GenericSettingsMenu.RowLine line) {
        if (!line.row().keepsOne() || line.entryId() == null || !line.boolValue()) return false;
        for (GenericSettingsMenu.RowLine other : lines()) {
            if (other.boolValue() && other.row().settingId().equals(line.row().settingId())
                    && !line.entryId().equals(other.entryId())) return false;
        }
        return true;
    }

    private void send(int lineIndex) {
        GenericSettingsMenu.RowLine line = menu.line(section, lineIndex);
        PacketDistributor.sendToServer(new GenericSettingsPayloads.Update(
                menu.villagerId(), line.row().settingId(), line.encodeValue()));
    }

    private void play(int choice) {
        int at = indexOf(BardSettingsSchema.INSTRUMENT, null);
        if (at < 0 || played() == choice) return;
        menu.setIntClient(section, at, choice);
        send(at);
    }

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    void render(GuiGraphics graphics, Font font, int left, int top, int mouseX, int mouseY) {
        graphics.fill(left + 8, top + 55, left + WIDTH - 8, top + height() - 8, INSET);
        int instrumentAt = indexOf(BardSettingsSchema.INSTRUMENT, null);
        String leftHeading = instrumentAt < 0 ? "" : lines().get(instrumentAt).label();
        SettingsHeading.draw(graphics, font, left + LEFT_X, top + HEAD_Y, LEFT_W, leftHeading);
        SettingsHeading.draw(graphics, font, left + RIGHT_X, top + HEAD_Y, RIGHT_W, rightHeading());

        int played = played();
        for (int slot = 0; slot < CHOICES; slot++) {
            instrumentRow(graphics, font, left + LEFT_X, top + ROW_TOP + slot * INST_STEP,
                    ORDER[slot], ORDER[slot] == played, mouseX, mouseY);
        }
        playButton(graphics, font, left + LEFT_X, top + buttonY(), played, mouseX, mouseY);

        if (!isPick(viewing)) {
            BardInstrument shown = BardInstrument.fromSetting(viewing);
            for (int slot = 0; slot < VISIBLE_TRACKS; slot++) {
                int at = trackLineAt(slot);
                if (at < 0) break;
                GenericSettingsMenu.RowLine line = lines().get(at);
                trackRow(graphics, font, left + RIGHT_X, top + ROW_TOP + slot * ROW_STEP, trackW(), line,
                        BardTrackPreview.isPlaying(shown, line.entryId()), mouseX, mouseY);
            }
            trackBar(graphics, left, top);
        } else {
            bardsChoiceText(graphics, font, left + RIGHT_X + 4, top + ROW_TOP + 2);
        }

        // Volume under the instruments, Range under the tracks: one split line top to bottom (6b, 10-24).
        int stripY = top + stripY();
        stepper(graphics, font, left + LEFT_X, stripY, LEFT_W, BardSettingsSchema.MUSIC_VOLUME,
                Tr.s("screen.dynamicvillages.bard.music.volume", "Volume"), mouseX, mouseY);
        stepper(graphics, font, left + RIGHT_X, stripY, RIGHT_W, BardSettingsSchema.LISTEN_RADIUS,
                Tr.s("screen.dynamicvillages.bard.music.range", "Range"), mouseX, mouseY);

        String hover = hoverText(left, top, mouseX, mouseY);
        if (hover != null) SettingsTooltip.render(graphics, font, hover, mouseX, mouseY);
    }

    private void instrumentRow(GuiGraphics graphics, Font font, int x, int y, int choice, boolean played,
                               int mouseX, int mouseY) {
        boolean viewed = choice == viewing;
        boolean hovered = inside(mouseX, mouseY, x, y, LEFT_W, INST_H);
        graphics.fill(x, y, x + LEFT_W, y + INST_H, viewed ? SELECTED : hovered ? ROW_HOVER : ROW);
        if (viewed) graphics.fill(x, y, x + 2, y + INST_H, ACCENT);
        int textY = y + (INST_H - 8) / 2;
        int room = LEFT_W - 11;
        if (played) {
            String plays = Tr.s("screen.dynamicvillages.bard.music.plays", "plays");
            int playsX = x + LEFT_W - 4 - font.width(plays);
            graphics.drawString(font, plays, playsX, textY, GREEN, false);
            room = playsX - 3 - (x + 7);
        }
        graphics.drawString(font, fit(font, choiceName(choice), room), x + 7, textY, TEXT, false);
    }

    private void playButton(GuiGraphics graphics, Font font, int x, int y, int played, int mouseX, int mouseY) {
        boolean inUse = played == viewing;
        boolean pick = isPick(viewing);
        String label = pick
                ? inUse ? Tr.s("screen.dynamicvillages.bard.music.picking", "The bard picks")
                        : Tr.s("screen.dynamicvillages.bard.music.let_pick", "Let the bard pick")
                : inUse ? Tr.s("screen.dynamicvillages.bard.music.playing", "Playing now")
                        : Tr.s("screen.dynamicvillages.bard.music.play", "Play this");
        boolean hovered = !inUse && inside(mouseX, mouseY, x, y, LEFT_W, BUTTON_H);
        graphics.fill(x, y, x + LEFT_W, y + BUTTON_H, inUse ? SELECTED : hovered ? BUTTON_HOVER : BUTTON);
        String shown = fit(font, label, LEFT_W - 8);
        graphics.drawString(font, shown, x + (LEFT_W - font.width(shown)) / 2, y + (BUTTON_H - 8) / 2,
                inUse ? MUTED : TEXT, false);
    }

    private void trackRow(GuiGraphics graphics, Font font, int x, int y, int width, GenericSettingsMenu.RowLine line,
                          boolean previewing, int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, x, y, width, ROW_H);
        graphics.fill(x, y, x + width, y + ROW_H, hovered ? ROW_HOVER : ROW);

        // Play button (round 7, 10-24): a triangle, or a green stop square while this track plays for you.
        int boxX = x + 3;
        int boxY = y + (ROW_H - 12) / 2;
        boolean overPlay = inside(mouseX, mouseY, x, y, PLAY_HIT_W, ROW_H);
        graphics.fill(boxX, boxY, boxX + 12, boxY + 12, previewing ? PLAYING_BOX : overPlay ? BUTTON_HOVER : BUTTON);
        if (previewing) {
            graphics.fill(boxX + 3, boxY + 3, boxX + 9, boxY + 9, GREEN);
        } else {
            for (int i = 0; i < 4; i++) graphics.fill(boxX + 4 + i, boxY + 2 + i, boxX + 5 + i, boxY + 10 - i, TEXT);
        }
        if (previewing) {
            graphics.fill(x, y + ROW_H - 2, x + Math.round(width * BardTrackPreview.progress()), y + ROW_H, GREEN);
        }

        // A small switch and no ON/OFF word (6b, 10-24): green with the knob right is on, dark red with the
        // knob left is off, grey is the last one on (locked).
        boolean on = line.boolValue();
        boolean locked = lockedOn(line);
        int switchX = x + width - SWITCH_W - 6;
        int switchY = y + (ROW_H - SWITCH_H) / 2;
        graphics.fill(switchX, switchY, switchX + SWITCH_W, switchY + SWITCH_H, locked ? LOCKED : on ? GREEN : OFF);
        int knob = SWITCH_H - 2;
        int knobX = on ? switchX + SWITCH_W - 1 - knob : switchX + 1;
        graphics.fill(knobX, switchY + 1, knobX + knob, switchY + 1 + knob, locked ? 0xFFB0B0B0 : 0xFFFFFFFF);

        int seconds = BardSettingsSchema.trackSeconds(line.row().settingId(), line.entryId());
        int labelRight = switchX - 6;
        if (seconds > 0) {
            String length = seconds + "s";
            labelRight -= font.width(length);
            graphics.drawString(font, length, labelRight, y + 5, MUTED, false);
            labelRight -= 3;
        }
        // An OFF track's name is greyed, so the list reads at a glance without the word.
        int nameX = x + PLAY_HIT_W + 2;
        graphics.drawString(font, fit(font, line.label(), labelRight - nameX), nameX, y + 5,
                on ? TEXT : OFF_TEXT, false);
    }

    /** The track list's scrollbar, only while it scrolls: a dark groove the rows' height, a lighter thumb. */
    private void trackBar(GuiGraphics graphics, int left, int top) {
        int max = maxTrackScroll();
        if (max <= 0) return;
        int x = left + barX();
        int y = top + ROW_TOP;
        int groove = tracksBottom() - ROW_TOP;
        int rows = trackLines(viewing).size();
        int thumb = Math.max(12, groove * VISIBLE_TRACKS / rows);
        int thumbY = y + (groove - thumb) * Math.min(trackScroll, max) / max;
        graphics.fill(x, y, x + BAR_W, y + groove, ROW);
        graphics.fill(x, thumbY, x + BAR_W, thumbY + thumb, MUTED);
    }

    /** Puts the thumb's middle under the mouse. */
    private void scrollBarTo(int top, double mouseY) {
        int max = maxTrackScroll();
        int groove = tracksBottom() - ROW_TOP;
        int thumb = Math.max(12, groove * VISIBLE_TRACKS / trackLines(viewing).size());
        double along = (mouseY - top - ROW_TOP - thumb / 2.0) / Math.max(1, groove - thumb);
        scrollTracksTo((int) Math.round(along * max));
    }

    private void bardsChoiceText(GuiGraphics graphics, Font font, int x, int y) {
        String text = Tr.s("screen.dynamicvillages.bard.music.choice_text",
                "The bard picks a new instrument for every stop and visit, and takes a free part in the tavern band.\n"
                        + "It only plays tracks that are ON. Click an instrument to choose its tracks.");
        for (String paragraph : text.split("\n")) {
            for (FormattedCharSequence part : font.split(FormattedText.of(paragraph), RIGHT_W - 8)) {
                graphics.drawString(font, part, x, y, MUTED, false);
                y += 10;
            }
            y += 6;
        }
    }

    private void stepper(GuiGraphics graphics, Font font, int x, int y, int width, String settingId, String label,
                         int mouseX, int mouseY) {
        int at = indexOf(settingId, null);
        if (at < 0) return;
        GenericSettingsMenu.RowLine line = lines().get(at);
        boolean hovered = inside(mouseX, mouseY, x, y, width, ROW_H);
        graphics.fill(x, y, x + width, y + ROW_H, hovered ? ROW_HOVER : ROW);
        int[] layout = stepperLayout(x, width);
        int minusX = layout[0];
        int valueX = layout[1];
        int plusX = layout[2];
        graphics.drawString(font, fit(font, label, minusX - x - 11), x + 7, y + 5, TEXT, false);
        graphics.fill(minusX, y + 2, minusX + 16, y + 16, BUTTON);
        graphics.fill(plusX, y + 2, plusX + 16, y + 16, BUTTON);
        graphics.drawString(font, "-", minusX + 6, y + 5, TEXT, false);
        graphics.drawString(font, "+", plusX + 5, y + 5, TEXT, false);
        String value = Integer.toString(line.intValue());
        graphics.drawString(font, value, valueX + (30 - font.width(value)) / 2, y + 5, TEXT, false);
    }

    /** {minusX, valueX, plusX}: two 16 px buttons around a 30 px value, at the row's right end. */
    private static int[] stepperLayout(int x, int width) {
        int plusX = x + width - 20;
        int valueX = plusX - 2 - 30;
        int minusX = valueX - 2 - 16;
        return new int[]{minusX, valueX, plusX};
    }

    // -------------------------------------------------------------------------
    // Hover text
    // -------------------------------------------------------------------------

    @Nullable
    private String hoverText(int left, int top, int mouseX, int mouseY) {
        int slot = slotAt(top, mouseY, INST_STEP, INST_H);
        if (mouseX >= left + LEFT_X && mouseX < left + LEFT_X + LEFT_W) {
            if (slot >= 0 && slot < CHOICES) {
                int choice = ORDER[slot];
                boolean played = choice == played();
                if (isPick(choice)) {
                    return played
                            ? Tr.s("screen.dynamicvillages.bard.music.hover.choice_playing",
                                    "The bard picks the instrument\nitself, new for every stop.")
                            : Tr.s("screen.dynamicvillages.bard.music.hover.choice",
                                    "The bard picks the instrument\nitself, new for every stop.\nDouble click to use it.");
                }
                if (choice == BardInstrument.BONGOS_CHOICE) {
                    // The bongos are played sitting down, so Playing on the Move never applies (B3, 10-24).
                    return played
                            ? Tr.s("screen.dynamicvillages.bard.music.hover.bongos_playing",
                                    "The bard plays this now.\nIt sits on a stool to drum,\nso it walks with empty hands.\nClick to see its tracks.")
                            : Tr.s("screen.dynamicvillages.bard.music.hover.bongos",
                                    "Sits on a stool to drum,\nso it walks with empty hands.\nClick to see its tracks.\nDouble click to play it.");
                }
                return played
                        ? Tr.s("screen.dynamicvillages.bard.music.hover.instrument_playing",
                                "The bard plays this now.\nClick to see its tracks.")
                        : Tr.s("screen.dynamicvillages.bard.music.hover.instrument",
                                "Click to see its tracks.\nDouble click to play it.");
            }
            if (inside(mouseX, mouseY, left + LEFT_X, top + buttonY(), LEFT_W, BUTTON_H)) {
                // Same in-use test as playButton, so a greyed button never offers to switch (F6, 10-24).
                boolean inUse = played() == viewing;
                if (isPick(viewing)) {
                    return inUse
                            ? Tr.s("screen.dynamicvillages.bard.music.hover.picking",
                                    "The bard picks a new instrument\nfor every stop.")
                            : Tr.s("screen.dynamicvillages.bard.music.hover.let_pick",
                                    "Lets the bard pick a new instrument\nfor every stop. It starts once the\ncurrent tune ends.");
                }
                return inUse
                        ? Tr.s("screen.dynamicvillages.bard.music.hover.playing",
                                "The bard plays this instrument now.")
                        : Tr.s("screen.dynamicvillages.bard.music.hover.play",
                                "Makes the bard play this instrument.\nIt switches once its current tune ends.");
            }
        }
        if (mouseX >= left + RIGHT_X && mouseX < left + RIGHT_X + trackW()) {
            int at = trackLineAt(slotAt(top, mouseY, ROW_STEP, ROW_H));
            if (at >= 0 && mouseX < left + RIGHT_X + PLAY_HIT_W) {
                return BardTrackPreview.isPlaying(BardInstrument.fromSetting(viewing), lines().get(at).entryId())
                        ? Tr.s("screen.dynamicvillages.bard.music.hover.stop_listening",
                                "Stops the track.\nIt also stops when you close\nthis window.")
                        : Tr.s("screen.dynamicvillages.bard.music.hover.listen",
                                "Plays this track for you.\nNobody else hears it.");
            }
            if (at >= 0) return trackHover(lines().get(at));
        }
        int stripY = top + stripY();
        if (mouseY >= stripY && mouseY < stripY + ROW_H) {
            String setting = mouseX >= left + LEFT_X && mouseX < left + LEFT_X + LEFT_W ? BardSettingsSchema.MUSIC_VOLUME
                    : mouseX >= left + RIGHT_X && mouseX < left + RIGHT_X + RIGHT_W
                    ? BardSettingsSchema.LISTEN_RADIUS : null;
            int at = setting == null ? -1 : indexOf(setting, null);
            if (at >= 0) return SettingsText.description(menu.schema(), lines().get(at).row());
        }
        return null;
    }

    private String trackHover(GenericSettingsMenu.RowLine line) {
        String description = "";
        for (SettingsSchema.AllowListEntry entry : line.row().allowListEntries()) {
            if (entry.id().equals(line.entryId())) {
                description = SettingsText.entryDescription(menu.schema(), line.row(), entry);
                break;
            }
        }
        if (lockedOn(line)) {
            description += (description.isEmpty() ? "" : "\n")
                    + Tr.s("screen.dynamicvillages.settings.keep_one", "Keep at least one on:\nthis is the last one.");
        }
        return description;
    }

    // -------------------------------------------------------------------------
    // Clicks
    // -------------------------------------------------------------------------

    /** Left click on this tab's body; true when it hit something. */
    boolean mouseClicked(int left, int top, double mouseX, double mouseY) {
        int slot = slotAt(top, mouseY, INST_STEP, INST_H);
        if (mouseX >= left + LEFT_X && mouseX < left + LEFT_X + LEFT_W) {
            if (slot >= 0 && slot < CHOICES) {
                int choice = ORDER[slot];
                long now = Util.getMillis();
                boolean twice = choice == lastClicked && now - lastClickAt < DOUBLE_CLICK_MS;
                lastClicked = choice;
                lastClickAt = now;
                if (choice != viewing) trackScroll = 0;
                viewing = choice;
                if (twice) play(choice);
                return true;
            }
            if (inside(mouseX, mouseY, left + LEFT_X, top + buttonY(), LEFT_W, BUTTON_H)) {
                play(viewing);
                return true;
            }
        }
        if (maxTrackScroll() > 0 && inside(mouseX, mouseY, left + barX(), top + ROW_TOP, BAR_W,
                tracksBottom() - ROW_TOP)) {
            draggingBar = true;
            scrollBarTo(top, mouseY);
            return true;
        }
        if (mouseX >= left + RIGHT_X && mouseX < left + RIGHT_X + trackW()) {
            int at = trackLineAt(slotAt(top, mouseY, ROW_STEP, ROW_H));
            if (at >= 0) {
                GenericSettingsMenu.RowLine line = lines().get(at);
                BardInstrument shown = BardInstrument.fromSetting(viewing);
                boolean previewing = BardTrackPreview.isPlaying(shown, line.entryId());
                if (mouseX < left + RIGHT_X + PLAY_HIT_W) {
                    if (previewing) BardTrackPreview.stop();
                    else BardTrackPreview.play(shown, line.entryId());
                    return true;
                }
                if (lockedOn(line)) return true;
                boolean nowOn = !line.boolValue();
                menu.setBoolClient(section, at, nowOn);
                send(at);
                // Switching a track on plays it for you; switching it off stops it (round 7, 10-24).
                if (nowOn) BardTrackPreview.play(shown, line.entryId());
                else if (previewing) BardTrackPreview.stop();
                return true;
            }
        }
        int stripY = top + stripY();
        if (mouseY >= stripY + 2 && mouseY < stripY + 16) {
            if (clickStepper(BardSettingsSchema.MUSIC_VOLUME, left + LEFT_X, LEFT_W, mouseX)) return true;
            if (clickStepper(BardSettingsSchema.LISTEN_RADIUS, left + RIGHT_X, RIGHT_W, mouseX)) return true;
        }
        return false;
    }

    /**
     * One client tick while this tab is shown: the preview goes on only while its row is on screen, so
     * another instrument clicked or the row scrolled away stops it (round 7, 10-24). The screen stops it
     * itself on another tab and on closing.
     */
    void tick() {
        if (!BardTrackPreview.isPlaying()) return;
        if (!previewOnScreen()) {
            BardTrackPreview.stop();
            return;
        }
        BardTrackPreview.tick();
    }

    private boolean previewOnScreen() {
        if (isPick(viewing)) return false;
        BardInstrument shown = BardInstrument.fromSetting(viewing);
        for (int slot = 0; slot < VISIBLE_TRACKS; slot++) {
            int at = trackLineAt(slot);
            if (at < 0) return false;
            if (BardTrackPreview.isPlaying(shown, lines().get(at).entryId())) return true;
        }
        return false;
    }

    /** Left drag after a click on the scrollbar; true while it holds the bar. */
    boolean mouseDragged(int top, double mouseY) {
        if (!draggingBar) return false;
        scrollBarTo(top, mouseY);
        return true;
    }

    /** Left button up; true when it let go of the scrollbar. */
    boolean mouseReleased() {
        boolean was = draggingBar;
        draggingBar = false;
        return was;
    }

    /** Mouse wheel over the track list; true when the list scrolls (it fits otherwise, and the wheel does nothing). */
    boolean mouseScrolled(int left, int top, double mouseX, double mouseY, double scrollY) {
        if (maxTrackScroll() <= 0 || !inside(mouseX, mouseY, left + RIGHT_X, top + ROW_TOP, RIGHT_W,
                tracksBottom() - ROW_TOP)) return false;
        scrollTracksTo(trackScroll + (scrollY < 0 ? 1 : -1));
        return true;
    }

    private boolean clickStepper(String settingId, int x, int width, double mouseX) {
        int at = indexOf(settingId, null);
        if (at < 0) return false;
        int[] layout = stepperLayout(x, width);
        GenericSettingsMenu.RowLine line = lines().get(at);
        int next = line.intValue();
        if (mouseX >= layout[0] && mouseX < layout[0] + 16) {
            next -= line.row().stepInt();
        } else if (mouseX >= layout[2] && mouseX < layout[2] + 16) {
            next += line.row().stepInt();
        } else {
            return false;
        }
        menu.setIntClient(section, at, next);
        if (menu.line(section, at).intValue() != line.intValue()) send(at);
        return true;
    }

    // -------------------------------------------------------------------------

    /**
     * The row slot under the mouse, or -1 in a gap or outside. The columns have their own rows (6b, 10-24):
     * instruments INST_STEP / INST_H, tracks ROW_STEP / ROW_H.
     */
    private static int slotAt(int top, double mouseY, int step, int height) {
        double inList = mouseY - top - ROW_TOP;
        if (inList < 0) return -1;
        int slot = (int) (inList / step);
        return inList - slot * step < height ? slot : -1;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
    }
}
