package com.alex.test.village.visitors;

import java.util.function.DoubleSupplier;
import java.util.function.IntUnaryOperator;

/** Pure, deterministic nomad rules shared by production and unit tests. */
public final class NomadSpawnRules {
    private NomadSpawnRules() {}

    public record Probe(int x, int z) {}

    /**
     * One population band's numbers. {@code base} is the first morning's
     * arrival chance and {@code step} is added per failed morning. Once a group
     * is coming, each further member joins at {@code groupChance} (plus
     * {@code groupStep} per failed morning) up to {@code maxGroup}.
     */
    public record Band(double base, double step, double groupChance, double groupStep,
                       int maxGroup) {}

    private static final Band SMALL = new Band(0.65, 0.25, 0.60, 0.00, 2);
    private static final Band MEDIUM = new Band(0.45, 0.20, 0.50, 0.00, 3);
    private static final Band LARGE = new Band(0.20, 0.10, 0.55, 0.00, 4);
    private static final Band HUGE = new Band(0.15, 0.08, 0.35, 0.02, 5);

    /** Added to the base once a quiet morning (after a full group) comes up empty. */
    public static final double QUIET_MORNING_BONUS = 0.05;

    /** Scripted days, counted from the founding day as day 1. */
    public static final int SCRIPTED_SINGLE_DAY = 3;
    public static final int SCRIPTED_GROUP_DAY = 4;
    public static final int SCRIPTED_GROUP_SIZE = 3;
    public static final int FOUNDING_GROUP_SIZE = 3;

    public static Band bandFor(int villagersExcludingMayor) {
        if (villagersExcludingMayor <= 5) return SMALL;
        if (villagersExcludingMayor <= 10) return MEDIUM;
        if (villagersExcludingMayor <= 15) return LARGE;
        return HUGE;
    }

    /**
     * Chance a group turns up this morning.
     *
     * @param failedMornings mornings in a row nobody came (thunder included)
     * @param quietMorning   the morning right after a full size group: half the base
     * @param quietBonus     a quiet morning already came up empty since the last group
     */
    public static double arrivalChance(int villagersExcludingMayor, int failedMornings,
                                       boolean quietMorning, boolean quietBonus) {
        Band band = bandFor(villagersExcludingMayor);
        if (quietMorning) return band.base() / 2.0;
        double chance = band.base() + (quietBonus ? QUIET_MORNING_BONUS : 0.0)
                + Math.max(0, failedMornings) * band.step();
        return Math.min(1.0, chance);
    }

    /** Chance each extra member joins a group that is already coming. */
    public static double groupChance(int villagersExcludingMayor, int failedMornings) {
        Band band = bandFor(villagersExcludingMayor);
        return Math.min(1.0, band.groupChance() + Math.max(0, failedMornings) * band.groupStep());
    }

    public static int maxGroup(int villagersExcludingMayor) {
        return bandFor(villagersExcludingMayor).maxGroup();
    }

    /** One guaranteed member, then another at {@code groupChance} until a miss or the cap. */
    public static int rollGroupSize(double groupChance, int maxGroup, DoubleSupplier nextRoll) {
        int size = 1;
        while (size < maxGroup && nextRoll.getAsDouble() < groupChance) size++;
        return size;
    }

    /** Length-weighted side selection followed by a random point and inward band offset. */
    public static Probe perimeterProbe(int minX, int maxX, int minZ, int maxZ, int band,
                                       IntUnaryOperator nextInt) {
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int sideRoll = nextInt.applyAsInt(2 * width + 2 * depth);
        int inset = nextInt.applyAsInt(band + 1);
        if (sideRoll < width) return new Probe(minX + nextInt.applyAsInt(width), minZ + inset);
        sideRoll -= width;
        if (sideRoll < width) return new Probe(minX + nextInt.applyAsInt(width), maxZ - inset);
        sideRoll -= width;
        if (sideRoll < depth) return new Probe(minX + inset, minZ + nextInt.applyAsInt(depth));
        return new Probe(maxX - inset, minZ + nextInt.applyAsInt(depth));
    }
}
