package com.alex.test.village.villagers.mage.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.settings.GuardMobPolicy;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * The mage's settings screen: Behaviour (its duty and its off-duty day),
 * Spells, Reagents, Monsters. Like the archer's, the night shift, the guard
 * posts and the time at each post are the guard's own settings, stored here
 * so this screen can show them and copied to {@code GuardSettings} whenever
 * they change ({@code MageSettingsBridge}).
 */
public final class MageSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String PROACTIVE_TARGETING = "proactive_targeting";
    public static final String NIGHT_SHIFT = "night_shift";
    public static final String USE_GUARD_POSTS = "use_guard_posts";
    public static final String POST_SECONDS = "post_seconds";
    public static final String STUDY = "study";
    public static final String RANGE_PRACTICE = "range_practice";
    public static final String NIGHT_LIGHTS = "night_lights";
    public static final String FROST_NOVA = "spell_frost_nova";
    public static final String BLINK = "spell_blink";
    public static final String WARD = "spell_ward";
    public static final String CHAIN_SPARK = "spell_chain_spark";
    public static final String STARFALL = "spell_starfall";
    public static final String ALLOWED_REAGENTS = "allowed_reagents";
    public static final String ALLOWED_MOB_TARGETS = "allowed_mob_targets";

    /** The guard's own defaults (GuardSettings), for the settings shared with it. */
    public static final boolean DEFAULT_NIGHT_SHIFT = true;
    public static final boolean DEFAULT_USE_GUARD_POSTS = true;
    public static final int DEFAULT_POST_SECONDS = 60;

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.MAGE,
            "MageSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: keeps the village safe with its magic.\n"
                                            + "OFF: does no mage work at all."),
                            SettingsSchema.Row.behaviorToggle(
                                    PROACTIVE_TARGETING, "Hunting", true)
                                    .describedAs("ON: looks for monsters and casts at them first.\n"
                                            + "OFF: only casts at a monster that hits it."),
                            SettingsSchema.Row.behaviorToggle(
                                    NIGHT_SHIFT, "Working Nights", DEFAULT_NIGHT_SHIFT)
                                    .describedAs("ON: works at night and sleeps in the day.\n"
                                            + "OFF: works in the day and sleeps at night."),
                            SettingsSchema.Row.behaviorToggle(
                                    USE_GUARD_POSTS, "Guard Posts", DEFAULT_USE_GUARD_POSTS)
                                    .describedAs("ON: walks from guard post to guard post\nand stands at each one.\n"
                                            + "OFF: walks around the village."),
                            SettingsSchema.Row.numericStepper(
                                    POST_SECONDS, "Time at each post (seconds)", DEFAULT_POST_SECONDS, 5, 300, 5)
                                    .describedAs("How many seconds it stands at a guard post\nbefore it walks to the next one."),
                            SettingsSchema.Row.behaviorToggle(
                                    NIGHT_LIGHTS, "Night Lights", true)
                                    .describedAs("ON: on night duty it leaves floating lights in\ndark spots; they fade at dawn.\n"
                                            + "OFF: leaves the dark alone."),
                            SettingsSchema.Row.header("Additional Behaviour"),
                            SettingsSchema.Row.behaviorToggle(
                                    STUDY, "Studying", true)
                                    .describedAs("ON: studies at the Mage Tower or the Library\nwhen off duty, and gets better.\n"
                                            + "OFF: never studies."),
                            SettingsSchema.Row.behaviorToggle(
                                    RANGE_PRACTICE, "Practising", true)
                                    .describedAs("ON: casts at the Archery Range targets when\noff duty, and gets better.\n"
                                            + "OFF: never practises."))),
                    new SettingsSchema.Section("Spells", List.of(
                            SettingsSchema.Row.behaviorToggle(FROST_NOVA, "Frost Nova", true)
                                    .describedAs("ON: freezes packs of monsters (from level 25).\n"
                                            + "OFF: never casts it."),
                            SettingsSchema.Row.behaviorToggle(BLINK, "Blink", true)
                                    .describedAs("ON: jumps away when cornered (from level 40).\n"
                                            + "OFF: runs instead."),
                            SettingsSchema.Row.behaviorToggle(WARD, "Ward", true)
                                    .describedAs("ON: shields a guard in trouble (from level 50).\n"
                                            + "OFF: never casts it."),
                            SettingsSchema.Row.behaviorToggle(CHAIN_SPARK, "Chain Spark", true)
                                    .describedAs("ON: lightning that jumps between monsters\n(from level 75).\n"
                                            + "OFF: never casts it."),
                            SettingsSchema.Row.behaviorToggle(STARFALL, "Starfall", true)
                                    .describedAs("ON: once a fight, stars fall on a big pack\n(at level 100).\n"
                                            + "OFF: never casts it."))),
                    new SettingsSchema.Section("Reagents", List.of(
                            SettingsSchema.Row.allowList(ALLOWED_REAGENTS, "Reagents it may take", reagents())
                                    .describedAs("ON: takes this from storage for its big spells.\n"
                                            + "OFF: leaves it in storage."))),
                    new SettingsSchema.Section("Monsters", List.of(
                            SettingsSchema.Row.allowList(ALLOWED_MOB_TARGETS, "Monsters to cast at", mobTargets())
                                    .describedAs("ON: casts at this monster.\nOFF: leaves it alone.")))));

    private MageSettingsSchema() {}

    private static List<SettingsSchema.AllowListEntry> reagents() {
        return List.of(
                new SettingsSchema.AllowListEntry("minecraft:amethyst_shard", "Amethyst Shard", true),
                new SettingsSchema.AllowListEntry("minecraft:lapis_lazuli", "Lapis Lazuli", true));
    }

    /** The guards' own list (GuardMobPolicy), as the archer's screen uses it. */
    private static List<SettingsSchema.AllowListEntry> mobTargets() {
        List<SettingsSchema.AllowListEntry> entries = new ArrayList<>();
        for (GuardMobPolicy.MobOption option : GuardMobPolicy.options()) {
            entries.add(new SettingsSchema.AllowListEntry(
                    option.id().toString(), option.displayName(), option.defaultAllowed()));
        }
        return entries;
    }
}
