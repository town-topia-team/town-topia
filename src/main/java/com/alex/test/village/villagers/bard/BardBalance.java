package com.alex.test.village.villagers.bard;

/** One 1–100 performance skill drives audience reach, happiness and arrangement. */
public final class BardBalance {
    public static final int MAX_LEVEL = 100;
    public static final float EXPERIENCE_PER_BURST = 1f;
    public static final int HARMONY_LEVEL = 26;
    public static final int RHYTHM_LEVEL = 51;
    public static final int VIRTUOSO_LEVEL = 76;

    private BardBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    /** Each completed performance advances the same persistent 1–100 track. */
    public static float experienceForLevel(int level) {
        int steps = Math.clamp(level, 1, MAX_LEVEL) - 1;
        return 8f * steps + 0.09f * steps * steps;
    }

    /** The setting remains the ceiling; skill grows toward it from 16 blocks. */
    public static double audienceRadius(int level, int configuredRadius) {
        int ceiling = Math.max(1, configuredRadius);
        return Math.min(ceiling, 16.0 + (ceiling - 16.0) * progress(level));
    }

    public static float happinessPerBurst(int level) {
        return (float) (2.0 + 2.0 * progress(level));
    }

    /** The villager a bard walked over to play for gets three normal bursts' worth. */
    public static float serenadeBoost(int level) {
        return 3f * happinessPerBurst(level);
    }

    public static float sessionCap(int level) {
        return (float) (20.0 + 15.0 * progress(level));
    }

    /** More skilled players move through additional parts of the tune. */
    public static int arrangement(int level) {
        if (level >= VIRTUOSO_LEVEL) return 4;
        if (level >= RHYTHM_LEVEL) return 3;
        if (level >= HARMONY_LEVEL) return 2;
        return 1;
    }
}
