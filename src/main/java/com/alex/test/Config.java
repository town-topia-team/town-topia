package com.alex.test;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side gameplay configuration for the mod.
 */
public final class Config {

    private static final int MIN_TREE_BLOCKS_PER_TICK = 1;
    private static final int DEFAULT_TREE_BLOCKS_PER_TICK = 12;
    private static final int MAX_TREE_BLOCKS_PER_TICK = 64;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.IntValue LUMBERJACK_TREE_BLOCKS_REMOVED_PER_TICK =
            BUILDER
                    .comment(
                            "Maximum number of logs and leaves each Lumberjack removes per tick",
                            "after its chopping animation has finished.",
                            "Lower values reduce possible lag; higher values clear trees faster.")
                    .defineInRange(
                            "lumberjackTreeBlocksRemovedPerTick",
                            DEFAULT_TREE_BLOCKS_PER_TICK,
                            MIN_TREE_BLOCKS_PER_TICK,
                            MAX_TREE_BLOCKS_PER_TICK);

    private static final ModConfigSpec.BooleanValue GENERATED_VILLAGES_BECOME_DYNAMIC =
            BUILDER
                    .comment(
                            "Brand-new villages from world generation become TownTopia villages:",
                            "their villagers are swapped for ours and the village sets itself up",
                            "with a Town Hall, a Storage room and homes when a player comes near.",
                            "Turned off, new villages stay vanilla, and villages still waiting to",
                            "set themselves up get their vanilla villagers back. Villages already",
                            "set up stay TownTopia villages either way.")
                    .define("generatedVillagesBecomeDynamic", true);

    private static final ModConfigSpec.BooleanValue RAIDS_ENABLED =
            BUILDER
                    .comment(
                            "Raids come to villages by themselves (illager warbands, night hordes).",
                            "Turned off, no raid starts on its own in any village, whatever each",
                            "village's Mayor says. Raids already going on finish normally.")
                    .define("raidsEnabled", true);

    private static final ModConfigSpec.IntValue MAX_RAIDERS_ALIVE =
            BUILDER
                    .comment(
                            "Most raiders of one raid in the world at once. Bigger raids come in",
                            "more waves instead. Lower it if big raids make the server lag.")
                    .defineInRange("maxRaidersAlive", 20, 4, 60);

    private static final ModConfigSpec.IntValue FIRST_RAID_DAYS =
            BUILDER
                    .comment(
                            "Village days before a village's first raid, counted from when raids",
                            "first saw it. The village also needs six grown villagers.")
                    .defineInRange("firstRaidDays", 8, 0, 100);

    private static final ModConfigSpec.BooleanValue FAR_VILLAGERS_AWAKE =
            BUILDER
                    .comment(
                            "Villagers out past the simulation distance keep living their day while",
                            "a player is in their village. Their ground stays loaded but quiet, and",
                            "the mod moves them itself. Turned off, they stand still out there like before.")
                    .define("farVillagersAwake", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    /** Whether raids may start by themselves anywhere on this server. */
    public static boolean raidsEnabled() {
        return !SPEC.isLoaded() || RAIDS_ENABLED.getAsBoolean();
    }

    /** Most raiders of one raid alive at once. */
    public static int maxRaidersAlive() {
        return SPEC.isLoaded() ? Math.clamp(MAX_RAIDERS_ALIVE.getAsInt(), 4, 60) : 20;
    }

    /** Village days before a village's first raid. */
    public static int firstRaidDays() {
        return SPEC.isLoaded() ? Math.clamp(FIRST_RAID_DAYS.getAsInt(), 0, 100) : 8;
    }

    /** Whether far villagers stay awake (FarAwake); {@code /villager farawake on|off} changes it live. */
    public static boolean farVillagersAwake() {
        return !SPEC.isLoaded() || FAR_VILLAGERS_AWAKE.getAsBoolean();
    }

    private Config() {}

    /** Whether brand-new generated villages turn into Dynamic Villages. */
    public static boolean generatedVillagesBecomeDynamic() {
        // Read before the config has loaded (it loads with the mod, long before
        // any world): the default.
        return !SPEC.isLoaded() || GENERATED_VILLAGES_BECOME_DYNAMIC.getAsBoolean();
    }

    public static int lumberjackTreeBlocksRemovedPerTick() {
        return Math.clamp(
                LUMBERJACK_TREE_BLOCKS_REMOVED_PER_TICK.getAsInt(),
                MIN_TREE_BLOCKS_PER_TICK,
                MAX_TREE_BLOCKS_PER_TICK);
    }
}
