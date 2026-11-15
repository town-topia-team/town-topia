package com.alex.test.village.villagers.archer.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.settings.GuardMobPolicy;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Archer's settings schema — the reference profession for the generic
 * settings framework. See {@code HOW_TO_ADD_SETTINGS.md} for the worked
 * walkthrough this class is the subject of.
 *
 * <p>Three tabs: Behaviour (its work and its duty), Arrows, Monsters. Names and
 * explanations are kept simple enough for a child. The night shift, the guard
 * posts and the time at each post are the guard's own settings, which the
 * archer has always followed; they are stored here too, so this screen can
 * show them, and copied over to {@code GuardSettings} whenever they change
 * ({@code ArcherSettingsBridge}). Their defaults are the guard's.
 */
public final class ArcherSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String PROACTIVE_TARGETING = "proactive_targeting";
    public static final String KITE_FROM_MELEE = "kite_from_melee";
    public static final String HELP_ME = "help_me_in_fights";
    public static final String NIGHT_SHIFT = "night_shift";
    public static final String USE_GUARD_POSTS = "use_guard_posts";
    public static final String POST_SECONDS = "post_seconds";
    public static final String LOOKOUT_RINGS_BELL = "lookout_rings_bell";
    public static final String TOWER_WATCH = "tower_watch";
    public static final String RANGE_PRACTICE = "range_practice";
    public static final String ALLOWED_ARROW_TYPES = "allowed_arrow_types";
    public static final String ARROWS_TO_CARRY = "arrows_to_carry";
    public static final String MAKE_ARROWS = "make_arrows";
    public static final String PICK_UP = "pick_up_after_fights";
    public static final String ALLOWED_MOB_TARGETS = "allowed_mob_targets";

    /** The guard's own defaults (GuardSettings), for the settings shared with it. */
    public static final boolean DEFAULT_NIGHT_SHIFT = true;
    public static final boolean DEFAULT_USE_GUARD_POSTS = true;
    public static final int DEFAULT_POST_SECONDS = 60;

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.ARCHER,
            "ArcherSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: keeps the village safe with its bow.\n"
                                            + "OFF: does no archer work at all."),
                            SettingsSchema.Row.behaviorToggle(
                                    PROACTIVE_TARGETING, "Hunting", true)
                                    .describedAs("ON: looks for monsters and shoots them first.\n"
                                            + "OFF: only shoots a monster that hits it."),
                            SettingsSchema.Row.behaviorToggle(
                                    KITE_FROM_MELEE, "Keeping Distance", true)
                                    .describedAs("ON: walks backwards when a monster comes close,\nso it can keep shooting.\n"
                                            + "OFF: stays where it is."),
                            SettingsSchema.Row.behaviorToggle(
                                    HELP_ME, "Defending You", false)
                                    .describedAs("ON: if a monster hurts you nearby, it shoots\nthat monster (while it is working).\n"
                                            + "OFF: lets you fight on your own."),
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
                                    TOWER_WATCH, "Tower Watch", true)
                                    .describedAs("ON: climbs an empty watchtower and watches\nfrom the top.\n"
                                            + "OFF: stays on the ground."),
                            SettingsSchema.Row.behaviorToggle(
                                    LOOKOUT_RINGS_BELL, "Bell Ringing", true)
                                    .describedAs("ON: on a watchtower, it rings the bell when\nmany monsters come. The bell calls every guard.\n"
                                            + "OFF: never rings it."),
                            SettingsSchema.Row.header("Additional Behaviour"),
                            SettingsSchema.Row.behaviorToggle(
                                    RANGE_PRACTICE, "Practising", true)
                                    .describedAs("ON: shoots at targets when nothing is going on,\nand gets better at it.\n"
                                            + "OFF: never practises."),
                            SettingsSchema.Row.behaviorToggle(
                                    MAKE_ARROWS, "Bow and Arrow Crafting", true)
                                    .describedAs("ON: makes new arrows and bows from things\nin storage when it runs out.\n"
                                            + "OFF: only uses ready-made ones."))),
                    new SettingsSchema.Section("Arrows", List.of(
                            SettingsSchema.Row.allowList(ALLOWED_ARROW_TYPES, "Arrows it may use", arrowTypes())
                                    .describedAs("ON: may shoot this kind of arrow.\nOFF: never uses it."),
                            SettingsSchema.Row.numericStepper(
                                    ARROWS_TO_CARRY, "Arrows to carry", 32, 16, 64, 8)
                                    .describedAs("How many arrows it keeps with it.\nWhen half are used up, it gets more."),
                            SettingsSchema.Row.behaviorToggle(
                                    PICK_UP, "Picking Up Arrows", true)
                                    .describedAs("ON: when the fight is over, it picks up its\narrows and what the monsters dropped.\n"
                                            + "OFF: leaves them on the ground."))),
                    new SettingsSchema.Section("Monsters", List.of(
                            SettingsSchema.Row.allowList(ALLOWED_MOB_TARGETS, "Monsters to shoot", mobTargets())
                                    .describedAs("ON: shoots this monster.\nOFF: leaves it alone.")))));

    private ArcherSettingsSchema() {}

    private static List<SettingsSchema.AllowListEntry> arrowTypes() {
        return List.of(
                new SettingsSchema.AllowListEntry("minecraft:arrow", "Arrow", true),
                new SettingsSchema.AllowListEntry("minecraft:spectral_arrow", "Spectral Arrow", true),
                new SettingsSchema.AllowListEntry("minecraft:tipped_arrow", "Tipped Arrow", true));
    }

    /** Reuses GuardMobPolicy's own list/shape rather than inventing a second one. */
    private static List<SettingsSchema.AllowListEntry> mobTargets() {
        List<SettingsSchema.AllowListEntry> entries = new ArrayList<>();
        for (GuardMobPolicy.MobOption option : GuardMobPolicy.options()) {
            entries.add(new SettingsSchema.AllowListEntry(
                    option.id().toString(), option.displayName(), option.defaultAllowed()));
        }
        return entries;
    }
}
