package com.alex.test.village.raid;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Who raids: each faction has its own goal, time of day and way to win.
 * The warband plunders storage at dusk from a camp; the horde comes after
 * dark from two or three sides to kill, until sunrise. The challenge is the
 * Bad Omen raid: waves of illagers from three sides, called by a player with
 * Bad Omen (or Minecraft's own raid on the village, steered by its raid director).
 *
 * <p>No Minecraft types: unit-tested on a plain JVM.
 */
public enum RaidFaction {
    WARBAND("Illager warband", "warband", 45),
    HORDE("Night horde", "horde", 25),
    /** Hit and run on the fields at dawn; only where there are fields or animals. */
    RIDERS("Skeleton riders", "riders", 15),
    /** A small team that leads one villager away to its camp; only in villages of ten or more. */
    KIDNAPPERS("Kidnappers", "kidnappers", 15),
    /** The Bad Omen raid, brought by a player with Bad Omen: never picked by the schedule. */
    CHALLENGE("Bad Omen raid", "raid", 0);

    private final String label;
    private final String noun;
    private final int weight;

    RaidFaction(String label, String noun, int weight) {
        this.label = label;
        this.noun = noun;
        this.weight = weight;
    }

    /** "Illager warband": the raid bar's name. */
    public String label() { return label; }

    /** "warband": "the warband is attacking". */
    public String noun() { return noun; }

    public int weight() { return weight; }

    /**
     * A faction by weight, never the one that came the last two times in a
     * row ({@code recent}, most recent first).
     */
    public static RaidFaction pick(List<RaidFaction> recent, RandomGenerator rng) {
        return pick(recent, rng, faction -> true);
    }

    /** {@link #pick(List, RandomGenerator)} among the factions this village {@code allowed}. */
    public static RaidFaction pick(List<RaidFaction> recent, RandomGenerator rng,
                                   java.util.function.Predicate<RaidFaction> allowed) {
        RaidFaction banned = recent.size() >= 2 && recent.get(0) == recent.get(1) ? recent.get(0) : null;
        List<RaidFaction> options = new ArrayList<>();
        int total = 0;
        for (RaidFaction faction : values()) {
            if (faction == banned || faction.weight <= 0 || !allowed.test(faction)) continue;
            options.add(faction);
            total += faction.weight;
        }
        if (options.isEmpty()) return WARBAND;
        int roll = rng.nextInt(total);
        for (RaidFaction faction : options) {
            roll -= faction.weight;
            if (roll < 0) return faction;
        }
        return options.get(options.size() - 1);
    }

    public static RaidFaction byName(String name) {
        for (RaidFaction faction : values()) {
            if (faction.name().equals(name)) return faction;
        }
        return WARBAND;
    }
}
