package com.alex.test.village.villagers.butcher;

/**
 * One 1–100 butchery skill. The kill itself is vanilla: sword blows, the
 * animal's own loot table on the floor. Skill adds to that haul and, at
 * milestones, to the blow.
 *
 * <p>Experience: every kill pays {@link #EXPERIENCE_PER_KILL}, in full for a
 * day's first {@link #FULL_DAY_KILLS}, a fifth after
 * ({@code stats.DailyJobExperience}). Eight a day is a ranch with all four
 * species, each pair bred twice a working day, so a butcher kept busy reaches
 * level 100 in {@link #DAYS_TO_MAX} in-game days learning at 1x. A smaller
 * ranch levels slower; BUTCHER_DAY in the diary shows the real count.
 *
 * <p>Must load without Minecraft (JUnit).
 */
public final class ButcherBalance {
    public static final int MAX_LEVEL = 100;
    /** In-game days to level 100 learning at 1x, culling a full day every day. */
    public static final int DAYS_TO_MAX = 80;
    /** Kills a day that pay in full: four species, each pair bred twice a day. */
    public static final int FULL_DAY_KILLS = 8;
    /** What each kill past the day's full ones still pays: a fifth. */
    public static final float OVER_FULL_DAY_SHARE = 0.2f;
    /** Raw experience per kill at 1x: about 2.0 for 80 days at 8 a day. */
    public static final float EXPERIENCE_PER_KILL = experienceForLevel(MAX_LEVEL)
            / (DAYS_TO_MAX * (float) FULL_DAY_KILLS);
    /** A stray brought home pays this share of a kill. */
    public static final float STRAY_KILLS = 0.5f;

    /** Extra meat: this chance at level 100, nothing at level 1. */
    public static final double MAX_EXTRA_MEAT_CHANCE = 0.50;
    /** Extra leather (cow) or feather (chicken): this chance at level 100. */
    public static final double MAX_EXTRA_HIDE_CHANCE = 0.50;
    public static final int MIN_CULLS_PER_SHIFT = 3;
    public static final int MAX_CULLS_PER_SHIFT = 5;

    /*
     * Milestones.
     */
    /** Skinner: leather and feathers never roll 0 (vanilla 0 to 2 becomes 1 to 2). */
    public static final int SKINNER_LEVEL = 25;
    /** Clean Kill: every blow is a vanilla critical hit. */
    public static final int CLEAN_KILL_LEVEL = 50;
    /** Vanilla's critical hit: one and a half times the damage. */
    public static final float CRIT_MULTIPLIER = 1.5f;
    /** Prime Cuts: the extra meat is 1 to 2 instead of 1. */
    public static final int PRIME_CUTS_LEVEL = 75;
    /** Master Butcher: one Looting level on top of the sword's own. */
    public static final int MASTER_LEVEL = 100;

    private ButcherBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    public static double extraMeatChance(int level) {
        return MAX_EXTRA_MEAT_CHANCE * progress(level);
    }

    /** Most extra meat one kill can add: 1, or 2 from Prime Cuts. */
    public static int extraMeatMax(int level) {
        return level >= PRIME_CUTS_LEVEL ? 2 : 1;
    }

    public static double extraHideChance(int level) {
        return MAX_EXTRA_HIDE_CHANCE * progress(level);
    }

    /** Fewest leather or feathers a cow or chicken gives: 0, or 1 from Skinner. */
    public static int hideFloor(int level) {
        return level >= SKINNER_LEVEL ? 1 : 0;
    }

    public static boolean alwaysCrit(int level) {
        return level >= CLEAN_KILL_LEVEL;
    }

    /** Looting levels the butcher's own hand adds to the sword's. */
    public static int bonusLooting(int level) {
        return level >= MASTER_LEVEL ? 1 : 0;
    }

    public static int cullsPerShift(int level) {
        return MIN_CULLS_PER_SHIFT + (int) Math.round(
                (MAX_CULLS_PER_SHIFT - MIN_CULLS_PER_SHIFT) * progress(level));
    }

    /** One blow's damage from a sword dealing {@code swordDamage}. */
    public static float blowDamage(int level, float swordDamage) {
        return alwaysCrit(level) ? swordDamage * CRIT_MULTIPLIER : swordDamage;
    }

    /**
     * About 1,281 experience to level 100: 80 days of full days learning at
     * 1x. Intelligence ahead of the level cuts that by up to a fifth, and
     * levels past it take ever longer ({@code IntelligenceLearning}).
     */
    public static float experienceForLevel(int level) {
        int steps = Math.clamp(level, 1, MAX_LEVEL) - 1;
        return 7f * steps + 0.06f * steps * steps;
    }
}
