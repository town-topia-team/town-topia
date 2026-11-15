package com.alex.test.village.villagers.cleric.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.List;

/**
 * Cleric's settings schema. See {@code HOW_TO_ADD_SETTINGS.md} in the
 * settings package for the worked walkthrough this class follows.
 *
 * <p>Two tabs (10-08 rework): <b>Behaviour</b> is what the cleric does and
 * when (fights, the battle blessing, night shift, rounds, then the side jobs
 * under Additional Behaviour); <b>Patients</b> is who it heals and how hurt
 * they must be. Values are stored by id, so moving rows between tabs keeps
 * every saved setting.
 *
 * <p><b>Start Healing</b> (10-09) replaced "Heal when hurt by", a stepper of
 * half hearts lost that players could not read ("2 is one heart"). It is a
 * choice of how hurt someone looks, measured against their own full health
 * so a guard with extra hearts is judged the same way; see
 * {@code ClericCare.hurtEnough}. A saved old number is turned into the
 * nearest choice the first time it is read ({@code ClericCare.startHealing}).
 */
public final class ClericSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String HEAL_IN_FIGHTS = "heal_in_fights";
    public static final String BATTLE_BLESSING = "battle_blessing";
    public static final String NIGHT_SHIFT = "night_shift";
    public static final String START_HEALING = "start_healing";
    /** The old "Heal when hurt by" stepper (half hearts lost, 1 to 12): read once to fill in Start Healing. */
    public static final String LEGACY_HEAL_THRESHOLD = "heal_threshold";

    /** Start Healing choices, by index. */
    public static final int START_ANY_SCRATCH = 0;
    public static final int START_A_LITTLE_HURT = 1;
    public static final int START_BADLY_HURT = 2;
    public static final int START_NEARLY_DEAD = 3;
    public static final String GUARDS_FIRST = "guards_first";
    public static final String HEAL_PLAYERS = "heal_players";
    public static final String HEAL_ANIMALS = "heal_animals";
    public static final String BRING_FOOD = "bring_food";
    public static final String CHEER_UP = "cheer_up";
    public static final String MORNING_PRAYER = "morning_prayer";
    public static final String ROUNDS = "rounds";

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.CLERIC,
            "ClericSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: heals and looks after\nthe village.\n"
                                            + "OFF: does none of it."),
                            SettingsSchema.Row.behaviorToggle(
                                    HEAL_IN_FIGHTS, "Healing in Fights", true)
                                    .describedAs("ON: stays out when the bell rings\nand heals guards from behind.\n"
                                            + "OFF: hides indoors and only heals\nwho it sees from the door."),
                            SettingsSchema.Row.behaviorToggle(
                                    BATTLE_BLESSING, "Battle Blessing", true)
                                    .describedAs("ON: when the bell rings, blesses\nnearby guards so they take less\n"
                                            + "harm and heal over time.\nOFF: no blessing."),
                            SettingsSchema.Row.behaviorToggle(
                                    NIGHT_SHIFT, "Night Shift", false)
                                    .describedAs("ON: works through the night with\nthe guards and sleeps by day.\n"
                                            + "OFF: works by day, and still gets\nup at night if someone needs it."),
                            SettingsSchema.Row.behaviorToggle(
                                    ROUNDS, "Walking Rounds", true)
                                    .describedAs("ON: walks between busy places\nwhen nobody needs it, ready to help.\n"
                                            + "OFF: rests instead."),
                            SettingsSchema.Row.header("Additional Behaviour"),
                            SettingsSchema.Row.behaviorToggle(
                                    BRING_FOOD, "Feeding the Hungry", true)
                                    .describedAs("ON: carries food to villagers who\nhave none and hands it over.\n"
                                            + "OFF: they fetch their own."),
                            SettingsSchema.Row.behaviorToggle(
                                    CHEER_UP, "Cheering Up", true)
                                    .describedAs("ON: visits sad villagers for a\nkind word that lifts their mood.\n"
                                            + "OFF: leaves them alone."),
                            SettingsSchema.Row.behaviorToggle(
                                    MORNING_PRAYER, "Praying", true)
                                    .describedAs("ON: prays at the Town Hall each\nmorning; villagers nearby feel better.\n"
                                            + "OFF: skips it."))),
                    new SettingsSchema.Section("Patients", List.of(
                            SettingsSchema.Row.choice(
                                    START_HEALING, "Start Healing", START_ANY_SCRATCH,
                                    List.of("Any Scratch", "A Little Hurt", "Badly Hurt", "Nearly Dead"))
                                    .describedAs("When the cleric starts healing someone.\n"
                                            + "Any Scratch heals every little hit.\n"
                                            + "Nearly Dead saves its magic for\nthose about to die."),
                            SettingsSchema.Row.behaviorToggle(
                                    GUARDS_FIRST, "Healing Guards First", true)
                                    .describedAs("ON: hurt guards and archers are\nhealed before other villagers.\n"
                                            + "OFF: whoever is worst off first."),
                            SettingsSchema.Row.behaviorToggle(
                                    HEAL_PLAYERS, "Healing Players", false)
                                    .describedAs("ON: also heals you and other\nplayers in the village.\n"
                                            + "OFF: heals villagers only."),
                            SettingsSchema.Row.behaviorToggle(
                                    HEAL_ANIMALS, "Healing Pets and Golems", true)
                                    .describedAs("ON: also heals tame animals\nand iron golems nearby.\n"
                                            + "OFF: leaves them be.")))));

    private ClericSettingsSchema() {}
}
