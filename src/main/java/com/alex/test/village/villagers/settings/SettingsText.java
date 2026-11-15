package com.alex.test.village.villagers.settings;

import com.alex.test.Tr;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Translations for the text in a {@link SettingsSchema}.
 *
 * <p>Schemas keep writing plain English. Each piece of text gets a key built
 * from the profession and the setting's id, and screens show
 * {@code Tr.sOr(key, english)}: the translation when one exists, the schema's
 * English otherwise. So adding or renaming a setting needs no lang work; the
 * {@code data} run ({@code ./gradlew runData}) copies every key and its English
 * into {@code en_us.json} for translators.
 *
 * <pre>
 * settings.dynamicvillages.miner.section.behaviour       tab title
 * settings.dynamicvillages.miner.work_enabled            row label
 * settings.dynamicvillages.miner.work_enabled.desc       hover text
 * settings.dynamicvillages.miner.allowed_pickaxe_tiers.option.wooden   tier / choice name
 * settings.dynamicvillages.cook.dishes.entry.bread       allow-list entry
 * settings.dynamicvillages.cook.dishes.entry.bread.desc  its hover text
 * </pre>
 */
public final class SettingsText {

    private SettingsText() {}

    // ------------------------------------------------------------------ keys

    static String slug(String text) {
        String s = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        s = s.replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "_" : s;
    }

    private static String prefix(SettingsSchema schema) {
        return "settings.dynamicvillages." + schema.profession().name().toLowerCase(Locale.ROOT);
    }

    public static String sectionKey(SettingsSchema schema, SettingsSchema.Section section) {
        return prefix(schema) + ".section." + slug(section.title());
    }

    public static String rowKey(SettingsSchema schema, SettingsSchema.Row row) {
        return prefix(schema) + "." + slug(row.settingId());
    }

    public static String optionKey(SettingsSchema schema, SettingsSchema.Row row, String option) {
        return rowKey(schema, row) + ".option." + slug(option);
    }

    public static String entryKey(SettingsSchema schema, SettingsSchema.Row row, String entryId) {
        return rowKey(schema, row) + ".entry." + slug(entryId);
    }

    // ------------------------------------------------------------------ text

    public static String section(SettingsSchema schema, SettingsSchema.Section section) {
        return Tr.sOr(sectionKey(schema, section), section.title());
    }

    /** A tab title when only its English is at hand (screens pass tab names around as text). */
    public static String sectionTitle(SettingsSchema schema, String englishTitle) {
        return Tr.sOr(prefix(schema) + ".section." + slug(englishTitle), englishTitle);
    }

    public static String label(SettingsSchema schema, SettingsSchema.Row row) {
        return Tr.sOr(rowKey(schema, row), row.label());
    }

    public static String description(SettingsSchema schema, SettingsSchema.Row row) {
        if (row.description().isEmpty()) return "";
        return Tr.sOr(rowKey(schema, row) + ".desc", row.description());
    }

    /** A tier or choice name. {@code option} is also its stored id, so only the shown text changes. */
    public static String option(SettingsSchema schema, SettingsSchema.Row row, String option) {
        return Tr.sOr(optionKey(schema, row, option), option);
    }

    public static String entry(SettingsSchema schema, SettingsSchema.Row row, SettingsSchema.AllowListEntry entry) {
        return Tr.sOr(entryKey(schema, row, entry.id()), entry.label());
    }

    public static String entryDescription(SettingsSchema schema, SettingsSchema.Row row,
                                          SettingsSchema.AllowListEntry entry) {
        if (entry.description().isEmpty()) return "";
        return Tr.sOr(entryKey(schema, row, entry.id()) + ".desc", entry.description());
    }

    // ------------------------------------------------------------------ for en_us.json

    /** Every key in {@code schema} with its English, in screen order. */
    public static Map<String, String> english(SettingsSchema schema) {
        Map<String, String> out = new LinkedHashMap<>();
        for (SettingsSchema.Section section : schema.sections()) {
            out.put(sectionKey(schema, section), section.title());
            for (SettingsSchema.Row row : section.rows()) {
                out.put(rowKey(schema, row), row.label());
                if (!row.description().isEmpty()) out.put(rowKey(schema, row) + ".desc", row.description());
                for (String option : row.tierNames()) out.put(optionKey(schema, row, option), option);
                for (SettingsSchema.AllowListEntry entry : row.allowListEntries()) {
                    String key = entryKey(schema, row, entry.id());
                    out.put(key, entry.label());
                    if (!entry.description().isEmpty()) out.put(key + ".desc", entry.description());
                }
            }
        }
        return out;
    }
}
