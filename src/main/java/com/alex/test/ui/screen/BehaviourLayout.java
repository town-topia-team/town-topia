package com.alex.test.ui.screen;

import com.alex.test.Tr;
import java.util.List;

/**
 * The visible rows of a hand-built Behaviour tab (Farmer, Lumberjack): the
 * Working switch, heading lines, and the settings in between. A screen asks this
 * one place what row N is, so drawing, clicking, hover text and scrolling cannot
 * disagree about where the headings pushed everything.
 */
final class BehaviourLayout {

    enum Kind { WORKS, HEADING, SETTING, STEPPER }

    /** {@code setting} is an index into the menu's behaviour list; -1 for Working, headings and the stepper. */
    record Entry(Kind kind, String label, int setting) {
        /** The label in the player's language (the layouts are built once, so translate when drawn). */
        String shown() {
            return switch (label) {
                case "Working" -> Tr.s("screen.dynamicvillages.settings.working", "Working");
                case "Additional Behaviour" -> Tr.s("screen.dynamicvillages.settings.additional_behaviour",
                        "Additional Behaviour");
                default -> label;
            };
        }
    }

    private final List<Entry> entries;

    private BehaviourLayout(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    static BehaviourLayout of(Entry... entries) {
        return new BehaviourLayout(List.of(entries));
    }

    static Entry works() {
        return new Entry(Kind.WORKS, "Working", -1);
    }

    static Entry heading(String label) {
        return new Entry(Kind.HEADING, label, -1);
    }

    /** A number row that adjusts the switch above it (the Guard's "Time at each guard post"). */
    static Entry stepper() {
        return new Entry(Kind.STEPPER, "", -1);
    }

    static Entry setting(int index) {
        return new Entry(Kind.SETTING, "", index);
    }

    int size() {
        return entries.size();
    }

    /** Row {@code index} of the tab, or null past the end. */
    Entry at(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }
}
