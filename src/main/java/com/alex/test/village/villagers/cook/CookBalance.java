package com.alex.test.village.villagers.cook;

import com.alex.test.animation.VillagerAnimationState;

/**
 * One 1–100 cooking skill drives pace, useful batch size, carrying and staple
 * yield. Each station has its own time per batch (CookPlanner.stationTicks);
 * skill takes up to a fifth off it.
 *
 * <p>Experience: every finished batch pays {@link #EXPERIENCE_PER_BATCH}, in
 * full for a day's first {@link #FULL_DAY_BATCHES}, a fifth after
 * ({@code stats.DailyJobExperience}). 24 batches is about what ten villagers
 * eat in cooked dishes a day, so a cook feeding them reaches level 100 in
 * {@link #DAYS_TO_MAX} in-game days learning at 1x. A cook short of raw food
 * levels slower; COOK_DAY in the diary shows the real count.
 */
public final class CookBalance {
    public static final int MAX_LEVEL = 100;
    /** In-game days to level 100 learning at 1x, cooking a full day every day. */
    public static final int DAYS_TO_MAX = 80;
    /** Lessons a day that pay in full: about ten villagers' cooked meals of roasts. */
    public static final int FULL_DAY_BATCHES = 24;
    /** What each batch past the day's full ones still pays: a fifth. */
    public static final float OVER_FULL_DAY_SHARE = 0.2f;
    /** Raw experience per lesson (a roast batch) at 1x: about 0.67 for 80 days at 24 a day. */
    public static final float EXPERIENCE_PER_BATCH = experienceForLevel(MAX_LEVEL)
            / (DAYS_TO_MAX * (float) FULL_DAY_BATCHES);
    /** Ticks a batch takes at level 1: twice vanilla (smoker 10 s, furnace 20 s, campfire 60 s); the table, instant in vanilla, 5 s. */
    public static final int TABLE_TICKS = 100;
    public static final int SMOKER_TICKS = 200;
    public static final int FURNACE_TICKS = 400;
    public static final int CAMPFIRE_TICKS = 1200;
    /*
     * Milestones. Every recipe is known from level 1; levels add quality, and
     * the bonuses go to the whole village's food from the best cook it has
     * (CookBonuses), so dishes from different cooks stay the same item.
     */
    /** Seasoned: the cook's dishes taste this much better. */
    public static final int SEASONED_LEVEL = 25;
    public static final float SEASONED_TASTE = 1f;
    /** Baker's Hands: any dish batch, not only staples, may make one extra. */
    public static final int BAKERS_HANDS_LEVEL = 50;
    public static final double BAKERS_HANDS_CHANCE = 0.10;
    /** Hearty Portions: the cook's dishes fill this much more (nutrition). */
    public static final int HEARTY_LEVEL = 75;
    public static final int HEARTY_NUTRITION = 1;
    /** Signature Dish: the dish this cook has made most tastes this much better again. */
    public static final int SIGNATURE_LEVEL = 100;
    public static final float SIGNATURE_TASTE = 2f;

    /*
     * Experience is paid in lessons: a dish's weight (CookPlanner.lessons),
     * the day's first REPEAT_FULL_BATCHES of one dish in full and the rest
     * at REPEAT_SHARE, a NEW_DISH_LESSONS bonus the first time a dish is
     * ever made, and PRAISE_LESSONS when a villager enjoys one.
     */
    public static final int REPEAT_FULL_BATCHES = 8;
    public static final double REPEAT_SHARE = 0.5;
    public static final double NEW_DISH_LESSONS = 6;
    public static final double PRAISE_LESSONS = 0.25;

    public static final double MAX_SPEED_BONUS = 0.25;
    public static final double MAX_EXTRA_STAPLE_CHANCE = 0.20;
    public static final int MIN_CARRY_ITEMS = 16;
    public static final int MAX_CARRY_ITEMS = 64;
    public static final int MIN_BATCHES_PER_VISIT = 4;
    public static final int MAX_BATCHES_PER_VISIT = 8;
    private static final int FINISH_TICKS = 2;

    private CookBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    public static double speed(int level) {
        return 1.0 + MAX_SPEED_BONUS * progress(level);
    }

    /** One batch at the crafting table. */
    public static int batchTicks(int level) {
        return batchTicks(level, TABLE_TICKS);
    }

    /** One batch at a station taking {@code stationTicks} at level 1, skill taking up to a fifth off. */
    public static int batchTicks(int level, int stationTicks) {
        return Math.max(VillagerAnimationState.WORK_BLEND_TICKS + FINISH_TICKS + 1,
                (int) Math.round(stationTicks / speed(level)));
    }

    // The work clip's speed is no longer worked out here: CookMoves fits each
    // of the cook's moves into the batch (since 10-24).

    public static int carryItems(int level) {
        return MIN_CARRY_ITEMS + (int) Math.round(
                (MAX_CARRY_ITEMS - MIN_CARRY_ITEMS) * progress(level));
    }

    public static int batchesPerVisit(int level) {
        return MIN_BATCHES_PER_VISIT + (int) Math.round(
                (MAX_BATCHES_PER_VISIT - MIN_BATCHES_PER_VISIT) * progress(level));
    }

    public static double extraStapleChance(int level) {
        return MAX_EXTRA_STAPLE_CHANCE * progress(level);
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
