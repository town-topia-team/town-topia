package com.alex.test.village.villagers.settings;

import com.alex.test.village.villagers.core.Profession;

import java.util.List;
import java.util.Optional;

/**
 * Declarative description of one profession's settings screen. Plain data —
 * no rendering, no NBT, no networking. A schema is built once (typically as
 * a {@code static final SettingsSchema SCHEMA} constant on a per-profession
 * class, see {@code ArcherSettingsSchema}) and looked up at runtime via
 * {@link SettingsSchemaRegistry}.
 *
 * <p>{@link ProfessionSettings} reads/writes values against a schema and
 * {@code GenericSettingsMenu}/{@code GenericSettingsScreen} render one,
 * so every profession that wants a settings screen only has to author a
 * schema instance — see {@code HOW_TO_ADD_SETTINGS.md} in this package.
 */
public record SettingsSchema(Profession profession, String tagName, List<Section> sections) {

    public SettingsSchema {
        sections = List.copyOf(sections);
    }

    /** One tab in the rendered screen. */
    public record Section(String title, List<Row> rows) {
        public Section {
            rows = List.copyOf(rows);
        }
    }

    /** The kinds of rows a schema can declare. See factory methods on {@link Row}. */
    public enum RowKind {
        /** Single on/off switch that gates whether the profession works at all. */
        MASTER_TOGGLE,
        /** An ordered set of named tiers, each independently allow/deny (e.g. sword material). */
        TIER_TOGGLES,
        /** An unordered set of id+label entries, each independently allow/deny (e.g. mob types). */
        ALLOW_LIST,
        /** A single on/off switch for one behavior flag. */
        BEHAVIOR_TOGGLE,
        /** An integer value steppable between a min and max by a fixed increment. */
        NUMERIC_STEPPER,
        /** One choice from a short ordered list of named options. */
        CHOICE,
        /**
         * A heading line that groups the rows under it. Display only: it has no
         * value, is never saved or sent, and {@link SettingsSchema#findRow} skips it.
         */
        HEADER
    }

    /** Prefix of a heading row's dummy {@code settingId}. */
    public static final String HEADER_ID_PREFIX = "header_";

    /**
     * One allow-list entry: a stable id, a display label, whether it starts
     * allowed, and an optional hover description (empty falls back to the
     * row's own description).
     */
    public record AllowListEntry(String id, String label, boolean defaultAllowed, String description) {
        public AllowListEntry(String id, String label, boolean defaultAllowed) {
            this(id, label, defaultAllowed, "");
        }
    }

    /**
     * One configurable row. Only the fields relevant to {@link #kind()} are
     * populated for a given row — use the factory methods below rather than
     * the canonical constructor so irrelevant fields don't need to be spelled
     * out at every call site.
     */
    public record Row(
            RowKind kind,
            String settingId,
            String label,
            boolean defaultBool,
            int defaultInt,
            int minInt,
            int maxInt,
            int stepInt,
            List<String> tierNames,
            List<AllowListEntry> allowListEntries,
            String description,
            boolean ticksAsSeconds,
            boolean keepsOne) {

        public Row {
            tierNames = List.copyOf(tierNames);
            allowListEntries = List.copyOf(allowListEntries);
        }

        public Row(RowKind kind, String settingId, String label, boolean defaultBool,
                   int defaultInt, int minInt, int maxInt, int stepInt, List<String> tierNames,
                   List<AllowListEntry> allowListEntries, String description) {
            this(kind, settingId, label, defaultBool, defaultInt, minInt, maxInt, stepInt,
                    tierNames, allowListEntries, description, false, false);
        }

        /**
         * A copy of this row with a hover description for the settings screen.
         * Use "\n" for line breaks; keep lines near 35 characters.
         */
        public Row describedAs(String text) {
            return new Row(kind, settingId, label, defaultBool, defaultInt, minInt, maxInt,
                    stepInt, tierNames, allowListEntries, text, ticksAsSeconds, keepsOne);
        }

        /**
         * A copy of this stepper that still stores ticks but shows them to the
         * player as seconds (20 ticks = 1 s), so saved values keep their meaning.
         */
        public Row shownInSeconds() {
            return new Row(kind, settingId, label, defaultBool, defaultInt, minInt, maxInt,
                    stepInt, tierNames, allowListEntries, description, true, keepsOne);
        }

        /**
         * A copy of this allow list where the last entry left on cannot be switched
         * off ({@link ProfessionSettings#setAllowed} refuses it, the screen greys it).
         */
        public Row keepingOne() {
            return new Row(kind, settingId, label, defaultBool, defaultInt, minInt, maxInt,
                    stepInt, tierNames, allowListEntries, description, ticksAsSeconds, true);
        }

        /** The entry ids of a tier or allow-list row, in screen order. */
        public List<String> entryIds() {
            if (kind == RowKind.TIER_TOGGLES) return tierNames;
            return allowListEntries.stream().map(AllowListEntry::id).toList();
        }

        public static Row masterToggle(String settingId, String label, boolean defaultValue) {
            return new Row(RowKind.MASTER_TOGGLE, settingId, label, defaultValue,
                    0, 0, 0, 0, List.of(), List.of(), "");
        }

        /** A heading line, e.g. "Farming Behaviour". Never saved, sent or clicked. */
        public static Row header(String label) {
            return new Row(RowKind.HEADER, HEADER_ID_PREFIX + label, label, false,
                    0, 0, 0, 0, List.of(), List.of(), "");
        }

        public static Row behaviorToggle(String settingId, String label, boolean defaultValue) {
            return new Row(RowKind.BEHAVIOR_TOGGLE, settingId, label, defaultValue,
                    0, 0, 0, 0, List.of(), List.of(), "");
        }

        /** Every tier starts allowed (mirrors {@code GuardSettings.SwordTier}'s all-true default). */
        public static Row tierToggles(String settingId, String label, List<String> tierNames) {
            return new Row(RowKind.TIER_TOGGLES, settingId, label, true,
                    0, 0, 0, 0, tierNames, List.of(), "");
        }

        public static Row allowList(String settingId, String label, List<AllowListEntry> entries) {
            return new Row(RowKind.ALLOW_LIST, settingId, label, true,
                    0, 0, 0, 0, List.of(), entries, "");
        }

        public static Row numericStepper(
                String settingId, String label, int defaultValue, int min, int max, int step) {
            return new Row(RowKind.NUMERIC_STEPPER, settingId, label, false,
                    defaultValue, min, max, step, List.of(), List.of(), "");
        }

        public static Row choice(String settingId, String label, int defaultIndex,
                                 List<String> options) {
            if (options.isEmpty() || defaultIndex < 0 || defaultIndex >= options.size())
                throw new IllegalArgumentException("Choice requires options and a valid default");
            return new Row(RowKind.CHOICE, settingId, label, false,
                    defaultIndex, 0, options.size() - 1, 1, options, List.of(), "");
        }
    }

    /**
     * Finds a row by its {@code settingId}, searching every section in order.
     * Heading rows are skipped, so no save, packet or command can reach one.
     */
    public Optional<Row> findRow(String settingId) {
        for (Section section : sections) {
            for (Row row : section.rows()) {
                if (row.kind() == RowKind.HEADER) continue;
                if (row.settingId().equals(settingId)) return Optional.of(row);
            }
        }
        return Optional.empty();
    }
}
