package com.alex.test.village.villagers.lumberjack;

/**
 * Shared live/offline lumberjack balance. Skill drives log yield and carry
 * size; the axe alone sets chop speed (ToolSpeed).
 */
public final class LumberjackBalance {
    public static final int MAX_LEVEL = 100;

    /*
     * Experience. Every felled tree pays EXPERIENCE_PER_TREE, whatever its
     * size, for a day's first FULL_DAY_TREES trees; every tree after that
     * pays OVER_FULL_DAY_SHARE of it (stats.DailyJobExperience).
     * A lumberjack felling a full day's trees every day, learning at 1x,
     * reaches level 100 in DAYS_TO_MAX days. Intelligence ahead of the level
     * speeds that up to 1.25x (about 65 days at intelligence 100); a level
     * past it slows ever more (IntelligenceLearning). Trees are
     * the limit: in a village short of them (1-4 a day each, 10-03) it is
     * far slower; LUMBERJACK_DAY in the diary shows the real count.
     */
    /** In-game days to level 100 learning at 1x, felling a full day's trees every day. */
    public static final int DAYS_TO_MAX = 80;
    /** Trees a day that pay in full: a busy lumberjack with trees nearby fells about 11 (10-03 test, 11.4). */
    public static final int FULL_DAY_TREES = 11;
    /** What each tree past the day's full ones still pays: a fifth. */
    public static final float OVER_FULL_DAY_SHARE = 0.2f;
    /**
     * Cumulative XP for level 100: the scale saved experience is measured on
     * (the farmer's). Changing the days changes what a tree pays, never
     * this, so saved levels and progress stay where they are.
     */
    public static final float EXPERIENCE_FOR_MAX_LEVEL = 3888f;
    /** Raw experience per felled tree, at 1x: about 4.42 for 80 days at 11 a day. */
    public static final float EXPERIENCE_PER_TREE = EXPERIENCE_FOR_MAX_LEVEL
            / (DAYS_TO_MAX * FULL_DAY_TREES);
    /**
     * The curve's straight part; the rest grows with the square (the farmer's
     * and miner's shape). At 1x with full days: level 25 about
     * day 10, 50 about day 27, 75 about day 50, 100 day 80.
     */
    private static final double XP_LINEAR_SHARE = 0.38;
    /** The 1,500-XP curve saves were on until 10-03; only for converting them. */
    private static final float LEGACY_EXPERIENCE_FOR_MAX_LEVEL = 1500f;
    public static final int TICKS_PER_LOG = 90;
    public static final int MIN_TREE_TICKS = 240;
    public static final int MAX_TREE_TICKS = 3600;
    public static final int EXTRA_CHOP_TICKS = 100; // five seconds at 20 TPS, after speed bonuses
    public static final double TWO_BY_TWO_TIME = 1.15;
    public static final double START_LOG_CHANCE = 0.50;
    public static final double MAX_LOG_CHANCE = 0.90;
    /** Carry limit per tier: 16 logs up to level 25, 32 to 50, 48 to 75, 64 to 100. */
    public static final int CARRY_LOGS_PER_TIER = 16;
    public static final int LEVELS_PER_CARRY_TIER = 25;

    private LumberjackBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    /**
     * Cumulative XP: level one starts at zero and level 100 requires
     * {@link #EXPERIENCE_FOR_MAX_LEVEL}. About 15 XP for level 2, rising to
     * about 63 for the last level. Intelligence learning and its soft wall
     * apply separately.
     */
    public static float experienceForLevel(int level) {
        double progress = progress(level);
        return (float) (EXPERIENCE_FOR_MAX_LEVEL
                * (XP_LINEAR_SHARE * progress + (1.0 - XP_LINEAR_SHARE) * progress * progress));
    }

    /** The 1,500-XP curve used until 10-03, kept only to convert saved experience. */
    private static float legacyExperienceForLevel(int level) {
        int steps = Math.clamp(level, 1, MAX_LEVEL) - 1;
        int maxSteps = MAX_LEVEL - 1;
        double curve = 2.0 * steps + 0.04 * steps * steps;
        double maximum = 2.0 * maxSteps + 0.04 * maxSteps * maxSteps;
        return (float) (LEGACY_EXPERIENCE_FOR_MAX_LEVEL * curve / maximum);
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

    public static double logChance(int level) {
        return START_LOG_CHANCE + (MAX_LOG_CHANCE - START_LOG_CHANCE) * progress(level);
    }

    /**
     * Logs carried before the lumberjack banks them instead of starting another
     * tree. A soft limit: the tree in progress is always finished, so the
     * pack can end up over it.
     */
    public static int carryLogs(int level) {
        int tier = Math.ceilDiv(Math.clamp(level, 1, MAX_LEVEL), LEVELS_PER_CARRY_TIER);
        return CARRY_LOGS_PER_TIER * tier;
    }

    /**
     * Ticks to fell a tree with an axe of {@code toolSpeed} (ToolSpeed: 1× for
     * a plain wooden axe up to 1.4× for netherite with Efficiency V). Skill
     * does not change it; the swing clip plays at the same speed.
     */
    public static int chopTicks(int logs, boolean twoByTwo, double toolSpeed) {
        int treeTicks = Math.clamp(Math.max(1, logs) * TICKS_PER_LOG,
                MIN_TREE_TICKS, MAX_TREE_TICKS);
        return EXTRA_CHOP_TICKS + Math.max(1, (int) Math.round(
                treeTicks * (twoByTwo ? TWO_BY_TWO_TIME : 1.0) / Math.max(1.0, toolSpeed)));
    }
}
