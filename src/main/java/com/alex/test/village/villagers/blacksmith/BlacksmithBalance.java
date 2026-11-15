package com.alex.test.village.villagers.blacksmith;

/**
 * Shared blacksmith balance. One skill level, 1 to 100, drives every effect:
 * a little better every level, with milestones at 25, 50, 75 and 100.
 *
 * <ul>
 *   <li>Lv 1 — Apprentice: stone, leather, iron, shields, shears.</li>
 *   <li>Lv 25 — Diamond smithing.</li>
 *   <li>Lv 50 — Tempered steel: a chance of Unbreaking I on what it makes,
 *       5% at 50 rising to 25% at 100.</li>
 *   <li>Lv 75 — Netherite smithing at a smithing table.</li>
 *   <li>Lv 100 — Template copying.</li>
 * </ul>
 *
 * <p>Experience: the smith's work earns forge points (an iron tool is 4),
 * paid in full for a day's first {@link #FULL_DAY_POINTS}, a fifth after
 * ({@code stats.DailyJobExperience}). Six iron tools' worth is a busy day, so a
 * smith kept that busy reaches level 100 in {@link #DAYS_TO_MAX} in-game days
 * learning at 1x. A village that needs less levels slower; BLACKSMITH_DAY in
 * the diary shows the real count.
 *
 * <p>Pure numbers, no Minecraft types, so unit tests can load it.
 */
public final class BlacksmithBalance {

    public static final int MAX_LEVEL = 100;

    public static final int DIAMOND_LEVEL = 25;
    public static final int TEMPERED_LEVEL = 50;
    public static final int NETHERITE_LEVEL = 75;
    public static final int TEMPLATE_LEVEL = 100;

    /** Same curve as the guard: ~4,000 experience to level 100. */
    private static final float XP_LINEAR = 20f;
    private static final float XP_SQUARE = 0.2f;

    /** In-game days to level 100 learning at 1x, with a busy day at the forge every day. */
    public static final int DAYS_TO_MAX = 80;
    /** Forge points a day that pay in full: six iron tools' worth. */
    public static final int FULL_DAY_POINTS = 24;
    /** What points past the day's full ones still pay: a fifth. */
    public static final float OVER_FULL_DAY_SHARE = 0.2f;
    /** Raw experience per forge point at 1x: about 2.05 for 80 days at 24 a day. */
    public static final float EXPERIENCE_PER_POINT = experienceForLevel(MAX_LEVEL)
            / (DAYS_TO_MAX * (float) FULL_DAY_POINTS);

    /** Forge points per item, by material tier (wood/leather, stone, iron, diamond, netherite). */
    private static final float[] CRAFT_POINTS = {2f, 2f, 4f, 8f, 12f};
    /** Forge points per tool or armour piece melted down. */
    public static final float RECYCLE_POINTS = 2f;
    /** Forge points per ingot smelted (one per batch of eight). */
    public static final float SMELT_POINTS_PER_INGOT = 1f / 8f;
    /** Forge points per minute of forge work, so long jobs teach as well as many short ones. */
    public static final float WORK_POINTS_PER_MINUTE = 1f;

    private BlacksmithBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    /** Total experience for level L = 20·(L−1) + 0.2·(L−1)². */
    public static float experienceForLevel(int level) {
        int steps = Math.clamp(level, 1, MAX_LEVEL) - 1;
        return XP_LINEAR * steps + XP_SQUARE * steps * steps;
    }

    /** Ticks at the anvil per item: 200 at level 1, 80 at 100. */
    public static int craftTicks(int level) {
        return (int) Math.round(200 - 120 * progress(level));
    }

    /** Ticks per ingot at a plain furnace: 40 at level 1, 20 at 100. A blast furnace halves it. */
    public static int smeltTicksPerItem(int level) {
        return (int) Math.round(40 - 20 * progress(level));
    }

    /** Smelts one coal or charcoal pays for: 8 at level 1, 12 at 100. Wood is 3/16 of it. */
    public static float smeltsPerCoal(int level) {
        return (float) (8 + 4 * progress(level));
    }

    /** Share of the remaining material a melted-down tool gives back: 40% at 1, 80% at 100. */
    public static float recoveryRate(int level) {
        return (float) (0.40 + 0.40 * progress(level));
    }

    /** Chance a finished tool or armour piece comes out with Unbreaking I. */
    public static float temperedChance(int level) {
        if (level < TEMPERED_LEVEL) return 0f;
        return (float) (0.05 + 0.20 * (Math.clamp(level, TEMPERED_LEVEL, MAX_LEVEL) - TEMPERED_LEVEL)
                / (double) (MAX_LEVEL - TEMPERED_LEVEL));
    }

    /** Highest {@code ForgeOrderBook} tier this level may make: iron, diamond or netherite. */
    public static int maxTier(int level) {
        if (level >= NETHERITE_LEVEL) return 4;
        if (level >= DIAMOND_LEVEL) return 3;
        return 2;
    }

    /** Forge points for one finished item of a {@code ForgeOrderBook} tier. */
    public static float craftPoints(int tier) {
        return CRAFT_POINTS[Math.clamp(tier, 0, CRAFT_POINTS.length - 1)];
    }
}
