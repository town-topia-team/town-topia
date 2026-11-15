package com.alex.test.village.villagers.rancher;

import com.alex.test.animation.VillagerAnimationState;

/**
 * One 1–100 ranching skill. Every level makes the rancher quicker at each
 * animal and a little better at shearing; milestones add eggs, faster-growing
 * babies, extra hide and cheaper breeding.
 *
 * <pre>
 *   Lv      unlock
 *   25      Egg Finder    -- chickens lay every 7,500 ticks instead of 9,000
 *   50      Good Breeder  -- babies in the pen grow up 25% faster
 *   75      Fine Hides    -- combing a cow has a 25% chance of a second leather
 *   100     Master Rancher -- one feeding sets a pair breeding: half the food
 * </pre>
 *
 * <p>Experience: every job pays {@link #EXPERIENCE_PER_JOB} (a shear, a
 * milking, an egg, a combed hide; feeding counts half per animal, so a pair is
 * one job; an animal brought home counts {@link #STRAY_JOBS}), in full for a
 * day's first {@link #FULL_DAY_JOBS}, a fifth after
 * ({@code stats.DailyJobExperience}). A pen of four sheep, cows and chickens
 * gives about that many, so a rancher kept busy reaches level 100 in
 * {@link #DAYS_TO_MAX} in-game days learning at 1x. A smaller ranch levels
 * slower; RANCHER_DAY in the diary shows the real count.
 *
 * <p>Must load without Minecraft (JUnit).
 */
public final class RancherBalance {
    public static final int MAX_LEVEL = 100;
    /** In-game days to level 100 learning at 1x, tending a full day every day. */
    public static final int DAYS_TO_MAX = 80;
    /** Jobs a day that pay in full: about what a pen of four of each gives. */
    public static final int FULL_DAY_JOBS = 24;
    /** What each job past the day's full ones still pays: a fifth. */
    public static final float OVER_FULL_DAY_SHARE = 0.2f;
    /** Raw experience per job at 1x: about 0.67 for 80 days at 24 a day. */
    public static final float EXPERIENCE_PER_JOB = experienceForLevel(MAX_LEVEL)
            / (DAYS_TO_MAX * (float) FULL_DAY_JOBS);
    /** Feeding one animal of a pair: the pair together is one job. */
    public static final float FEED_JOBS = 0.5f;
    /** An animal led home or rounded up into the pen: a long walk, two jobs. */
    public static final float STRAY_JOBS = 2f;

    public static final int EGG_FINDER_LEVEL = 25;
    public static final int GOOD_BREEDER_LEVEL = 50;
    public static final int FINE_HIDES_LEVEL = 75;
    public static final int MASTER_LEVEL = 100;

    /**
     * Ticks at an animal per job: 50 at level 1, 30 at 100. Long enough for the
     * blend in, one whole swing and {@link #FINISH_TICKS}; at 24 / 12 the swing
     * was cut before it landed and read as a try that failed.
     */
    private static final int ACT_TICKS_START = 50;
    private static final int ACT_TICKS_END = 30;
    /** Ticks the job runs on past the swing's last frame, for the client drawing behind. */
    private static final int FINISH_TICKS = 4;
    /** Chance of one more wool per shear at level 100: average 2 → 2.5. */
    private static final double MAX_EXTRA_WOOL_CHANCE = 0.5;
    /** Ticks between owned eggs per chicken, before and from Egg Finder. */
    public static final long EGG_INTERVAL_TICKS = 9000L;
    private static final long EGG_FINDER_INTERVAL_TICKS = 7500L;
    /** Share of the time since the last check a Good Breeder adds to a baby's growing up. */
    public static final double GROW_BOOST = 0.25;
    /** Chance a combed cow gives a second leather from Fine Hides. */
    public static final double SECOND_HIDE_CHANCE = 0.25;

    private RancherBalance() {}

    static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    /** Ticks spent at the animal for one job. */
    public static int actTicks(int level) {
        return (int) Math.round(ACT_TICKS_START - (ACT_TICKS_START - ACT_TICKS_END) * progress(level));
    }

    /**
     * Speed for a work clip {@code cycleTicks} long so the blend in and one
     * whole loop play in the job's time, less {@link #FINISH_TICKS} (the client
     * draws a few ticks behind the server). GeckoLib plays the blend at the
     * clip's speed too, so both are paced together: 1.22x at level 1, 2.15x at
     * 100 for the 48-tick chop, inside {@code NpcEntity.startWorkAnimation}'s 3x cap.
     */
    public static float clipSpeed(int level, int cycleTicks) {
        int playback = Math.max(1, actTicks(level) - FINISH_TICKS);
        return (cycleTicks + VillagerAnimationState.WORK_BLEND_TICKS) / (float) playback;
    }

    /** Chance one shear gives one more wool than the vanilla 1–3. */
    public static double extraWoolChance(int level) {
        return MAX_EXTRA_WOOL_CHANCE * progress(level);
    }

    /** Average wool per shear: 2 at level 1, 2.5 at 100. */
    public static double averageWool(int level) {
        return 2.0 + extraWoolChance(level);
    }

    /** Ticks between owned eggs per chicken this rancher tends. */
    public static long eggIntervalTicks(int level) {
        return level >= EGG_FINDER_LEVEL ? EGG_FINDER_INTERVAL_TICKS : EGG_INTERVAL_TICKS;
    }

    /** Whether babies in this rancher's pen grow up faster. */
    public static boolean goodBreeder(int level) {
        return level >= GOOD_BREEDER_LEVEL;
    }

    /** Chance of a second leather from one combed cow. */
    public static double secondHideChance(int level) {
        return level >= FINE_HIDES_LEVEL ? SECOND_HIDE_CHANCE : 0.0;
    }

    /** Average leather per combed cow. */
    public static double averageHides(int level) {
        return 1.0 + secondHideChance(level);
    }

    /** Whether one feeding sets a pair breeding (the partner needs no food). */
    public static boolean masterRancher(int level) {
        return level >= MASTER_LEVEL;
    }

    /** Breeding food a pair costs. */
    public static int foodPerPair(int level) {
        return masterRancher(level) ? 1 : 2;
    }

    /**
     * About 1,281 experience to level 100 (the cook's and butcher's curve):
     * 80 days of full days learning at 1x. Intelligence ahead of the level
     * speeds that up, and levels past it take ever longer
     * ({@code IntelligenceLearning}).
     */
    public static float experienceForLevel(int level) {
        int steps = Math.clamp(level, 1, MAX_LEVEL) - 1;
        return 7f * steps + 0.06f * steps * steps;
    }
}
