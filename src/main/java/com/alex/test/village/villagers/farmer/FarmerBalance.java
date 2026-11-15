package com.alex.test.village.villagers.farmer;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.village.brain.EveningSchedule;

/**
 * Shared live/offline farmer balance. The tool sets how fast each stroke
 * plays (ToolSpeed); skill sets how many strokes an action takes, by band.
 */
public final class FarmerBalance {
    public static final int MAX_LEVEL = 100;
    public static final int PLANT_HARVEST_CLIP_TICKS =
            Math.max(VillagerAnimationState.cycleTicks(VillagerAnimationState.PLANT),
                    VillagerAnimationState.cycleTicks(VillagerAnimationState.HARVEST));
    public static final int TILL_CLIP_TICKS =
            VillagerAnimationState.cycleTicks(VillagerAnimationState.TILL);
    public static final double MAX_EXTRA_FOOD_CHANCE = 0.20;
    public static final double MIN_SOIL_RETENTION = 0.40;
    public static final double MAX_SOIL_RETENTION = 0.80;
    public static final int MIN_CARRY_ITEMS = 16;
    public static final int MAX_CARRY_ITEMS = 64;

    /*
     * Experience. Every till, plant and harvest pays EXPERIENCE_PER_JOB, from
     * any crop, for a day's first FULL_DAY_JOBS jobs; every job after that
     * pays OVER_FULL_DAY_SHARE of it. A big field or many ripe crops speed a
     * farmer up only a little: the full day sets the pace. Paying per
     * harvest alone made the pace follow the size of the farm (10-15
     * harvests a day with twelve farmers sharing fields, many more for a
     * lone farmer), which is why every estimate was off.
     *
     * A farmer doing exactly a full day of jobs every day, learning at 1x,
     * reaches level 100 in DAYS_TO_MAX days. Intelligence ahead of the level
     * speeds that up to 1.25x (about 65 days at intelligence 100); a level
     * past it slows ever more (IntelligenceLearning).
     */
    /** In-game days to level 100 learning at 1x, doing a full day of jobs every day. */
    public static final int DAYS_TO_MAX = 80;
    /** Jobs a day that pay in full; 10-03 test: busy farmers did 22-32, average 25. */
    public static final int FULL_DAY_JOBS = 24;
    /**
     * Cumulative XP for level 100: the scale saved experience is measured on.
     * Changing the days changes what a job pays, never this, so saved levels
     * and progress stay where they are.
     */
    public static final float EXPERIENCE_FOR_MAX_LEVEL = 3888f;
    /** Raw experience per till, plant or harvest, at 1x: about 2.03 for 80 days. */
    public static final float EXPERIENCE_PER_JOB = EXPERIENCE_FOR_MAX_LEVEL
            / (DAYS_TO_MAX * FULL_DAY_JOBS);
    /** What each job past the day's full ones still pays: a fifth, so extra work is never worth nothing. */
    public static final float OVER_FULL_DAY_SHARE = 0.2f;
    /** Raw experience a day's full-pay jobs come to, before intelligence. */
    public static final float EXPERIENCE_PER_DAY_CAP = FULL_DAY_JOBS * EXPERIENCE_PER_JOB;
    public static final int PRODUCTIVE_WORK_TICKS_PER_DAY = (int) (EveningSchedule.DAY_TICKS
            - EveningSchedule.WAKE_TIME + EveningSchedule.MEAN_END_WORK);
    public static final int ACTIONS_PER_WORK_SESSION = 24;
    public static final float INTELLIGENCE_PER_WORK_SESSION = 0.1f;
    /** Six strokes initially, dropping by one at each quarter-century level (seven until 10-24). */
    public static final int FIVE_STROKE_LEVEL = 25;
    public static final int FOUR_STROKE_LEVEL = 50;
    public static final int THREE_STROKE_LEVEL = 75;
    public static final int TWO_STROKE_LEVEL = 100;
    /**
     * The curve's straight part; the rest grows with the square, so early
     * levels come a little quicker (the miner's shape). At 1x with full
     * days: level 25 about day 10, 50 about day 27, 75 about day
     * 50, 100 day 80.
     */
    private static final double XP_LINEAR_SHARE = 0.38;
    /** The curve saves were on until 10-03; only for converting them. */
    private static final float LEGACY_EXPERIENCE_FOR_MAX_LEVEL = 3800f;
    private static final int ANIMATION_FINISH_TICKS = 2;

    private FarmerBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    public static double extraFoodChance(int level) {
        return MAX_EXTRA_FOOD_CHANCE * progress(level);
    }

    public static double soilRetentionChance(int level) {
        return MIN_SOIL_RETENTION
                + (MAX_SOIL_RETENTION - MIN_SOIL_RETENTION) * progress(level);
    }

    /** Harvest surplus limit: 16/32/48/64 at levels 1/34/67/100; supplies stay separate. */
    public static int carryItems(int level) {
        return MIN_CARRY_ITEMS
                + (int) Math.round((MAX_CARRY_ITEMS - MIN_CARRY_ITEMS) * progress(level));
    }

    /** All three farm actions use the same visible stroke count at each skill band. */
    public static int animationStrokes(int level) {
        int skill = Math.clamp(level, 1, MAX_LEVEL);
        if (skill < FIVE_STROKE_LEVEL) return 6;
        if (skill < FOUR_STROKE_LEVEL) return 5;
        if (skill < THREE_STROKE_LEVEL) return 4;
        if (skill < TWO_STROKE_LEVEL) return 3;
        return 2;
    }

    /** Total action time includes the controller blend and every harvest/plant stroke. */
    public static int plantHarvestTicks(double toolSpeed, int level) {
        return VillagerAnimationState.WORK_BLEND_TICKS + ANIMATION_FINISH_TICKS
                + (int) Math.ceil(animationStrokes(level) * PLANT_HARVEST_CLIP_TICKS
                / Math.max(1.0, toolSpeed));
    }

    /** The same, timed on the clip that plays (picks and the melon chop run a tick or two longer). */
    public static int plantHarvestTicks(double toolSpeed, int level, VillagerAnimationState animation) {
        int cycle = Math.max(1, VillagerAnimationState.cycleTicks(animation));
        return VillagerAnimationState.WORK_BLEND_TICKS + ANIMATION_FINISH_TICKS
                + (int) Math.ceil(animationStrokes(level) * cycle / Math.max(1.0, toolSpeed));
    }

    public static int tillTicks(double toolSpeed, int level) {
        return VillagerAnimationState.WORK_BLEND_TICKS + ANIMATION_FINISH_TICKS
                + (int) Math.ceil(animationStrokes(level) * TILL_CLIP_TICKS
                / Math.max(1.0, toolSpeed));
    }

    /** Finish the requested full loops before the job changes the block and returns to idle. */
    public static float animationSpeed(VillagerAnimationState animation, int actionTicks,
                                       int level) {
        int playbackTicks = Math.max(1, actionTicks
                - VillagerAnimationState.WORK_BLEND_TICKS - ANIMATION_FINISH_TICKS);
        return animationStrokes(level) * VillagerAnimationState.cycleTicks(animation)
                / (float) playbackTicks;
    }

    /**
     * Cumulative XP: level one starts at zero and level 100 requires
     * {@link #EXPERIENCE_FOR_MAX_LEVEL}. About 15 XP for level 2, rising to
     * about 63 for the last level. Intelligence's learning rate and soft
     * wall are applied separately by VillagerSkills.
     */
    public static float experienceForLevel(int level) {
        double progress = (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
        return (float) (EXPERIENCE_FOR_MAX_LEVEL
                * (XP_LINEAR_SHARE * progress + (1.0 - XP_LINEAR_SHARE) * progress * progress));
    }

    /** The 3,800-XP curve used until 10-03, kept only to convert saved experience. */
    private static float legacyExperienceForLevel(int level) {
        int steps = Math.clamp(level, 1, MAX_LEVEL) - 1;
        double progress = steps / (double) (MAX_LEVEL - 1);
        double remaining = LEGACY_EXPERIENCE_FOR_MAX_LEVEL - 7.5 * (MAX_LEVEL - 1);
        return (float) (7.5 * steps + remaining * progress * progress);
    }

    /**
     * Experience saved on the old curve moved onto the current one: the same
     * level, and the same share of the way to the next.
     */
    public static float convertLegacyExperience(float legacyXp) {
        float xp = Math.max(0f, legacyXp);
        int level = 1;
        while (level < MAX_LEVEL && xp >= legacyExperienceForLevel(level + 1)) level++;
        if (level >= MAX_LEVEL) return experienceForLevel(MAX_LEVEL);
        float from = legacyExperienceForLevel(level);
        float to = legacyExperienceForLevel(level + 1);
        double share = to > from ? Math.clamp((xp - from) / (double) (to - from), 0.0, 1.0) : 0.0;
        float newFrom = experienceForLevel(level);
        float newTo = experienceForLevel(level + 1);
        // Never quite the next level's own figure, so rounding cannot level it up.
        return (float) Math.min(newFrom + share * (newTo - newFrom), Math.nextDown(newTo));
    }
}
