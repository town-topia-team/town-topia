package com.alex.test.village.villagers.miner.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.miner.OreHeights;
import com.alex.test.village.villagers.miner.OreHeights.Ore;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Miner's settings schema, built off the generic settings framework -- see
 * {@code ArcherSettingsSchema} (the reference implementation) and
 * {@code HOW_TO_ADD_SETTINGS.md} for the shape this follows.
 *
 * <p>The decisions are whether the miner works at all, which pickaxe tiers it
 * is willing to equip (mirrors {@code GuardSettings.SwordTier}'s
 * equipment-tier gate), and which ores it wants. A shaft's ore follows its
 * height ({@link OreHeights}), so wanting an ore means picking the free shaft
 * at the height that has the most of it; with none wanted the miner keeps to
 * the nearest shaft. How far a tunnel branches sideways is a village-wide
 * Mayor setting (or a mine's own, in its Mine screen), not here.
 */
public final class MinerSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String ALLOWED_PICKAXE_TIERS = "allowed_pickaxe_tiers";
    /** Ores the miner wants, by {@link #oreEntry} id; the settings screen shows them with ore icons. */
    public static final String WANTED_ORES = "wanted_ores";
    /** Village-wide limit edited in the Mayor's profession settings. */
    public static final int DEFAULT_SIDE_BRANCH_DISTANCE = 6;
    public static final int MAX_SIDE_BRANCH_DISTANCE = 12;

    /** Tier names double as their own settingId/entryId -- see SettingsSchema.Row.tierToggles. */
    public static final String TIER_WOODEN = "Wooden";
    public static final String TIER_STONE = "Stone";
    public static final String TIER_GOLDEN = "Golden";
    public static final String TIER_IRON = "Iron";
    public static final String TIER_DIAMOND = "Diamond";
    public static final String TIER_NETHERITE = "Netherite";

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.MINER,
            "MinerSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: digs the mine for stone\nand ore.\n"
                                            + "OFF: stops digging."))),
                    new SettingsSchema.Section("Equipment", List.of(
                            SettingsSchema.Row.tierToggles(ALLOWED_PICKAXE_TIERS, "Pickaxe tiers",
                                    List.of(TIER_WOODEN, TIER_STONE, TIER_GOLDEN,
                                            TIER_IRON, TIER_DIAMOND, TIER_NETHERITE))
                                    .describedAs("ON: may use this kind of pickaxe.\nOFF: never uses it.\n"
                                            + "Better pickaxes dig faster."))),
                    new SettingsSchema.Section("Ores", List.of(
                            SettingsSchema.Row.allowList(WANTED_ORES, "Wanted ores", wantedOres())
                                    .describedAs("ON: the miner goes to whichever of\nyour mines is at the best depth for it.\n"
                                            + "It keeps every ore it digs either way.\n"
                                            + "All OFF: works the nearest mine.")))));

    private MinerSettingsSchema() {}

    /** The setting entry id for {@code ore}: its ore block's item id, which the screen draws as the icon. */
    public static String oreEntry(Ore ore) {
        return ore.id() + "_ore";
    }

    /** Every ore that follows height, all off to start, each with where it is most common. */
    private static List<SettingsSchema.AllowListEntry> wantedOres() {
        List<SettingsSchema.AllowListEntry> entries = new ArrayList<>();
        for (Ore ore : Ore.values()) {
            entries.add(new SettingsSchema.AllowListEntry(oreEntry(ore), ore.label(), false,
                    "ON: prefers your mine with the\nmost " + ore.id() + ".\n" + whereMost(ore)
                            + "\nOFF: no wish for it."));
        }
        return entries;
    }

    private static String whereMost(Ore ore) {
        return switch (ore) {
            case COAL -> "Best mine depth: Y 96 and up.";
            case COPPER -> "Best mine depth: around Y 48.";
            case IRON -> "Best mine depth: around Y 16.";
            case LAPIS -> "Best mine depth: around Y 0.";
            case REDSTONE -> "Best mine depth: the bottom, Y -58.";
            case GOLD -> "Best mine depth: around Y -16.";
            case DIAMOND -> "Best mine depth: the bottom, Y -58.";
        };
    }
}
