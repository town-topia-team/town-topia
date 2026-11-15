package com.alex.test.village.stats;

/**
 * How intelligence changes learning on every 1..100 skill track: only the gap
 * between a villager's intelligence and their level counts.
 *
 * <pre>
 *   intelligence vs level     learning speed
 *   13 or more above          1.25x (the most)
 *   10 above                  1.20x
 *   5 above                   1.10x
 *   equal                     1.00x
 *   5 below                   0.50x
 *   10 below                  0.25x
 *   20 below                  0.06x
 *   30 or more below          0.02x (never zero)
 * </pre>
 *
 * Ahead of their level a villager learns a little faster, +2% a point up to
 * +25%; past it, speed halves every 5 levels. Each profession's pace ("80
 * days to level 100") is measured at 1x, a villager whose intelligence
 * matches their level.
 */
public final class IntelligenceLearning {
    /** Extra speed per point of intelligence above the level. */
    private static final float BONUS_PER_POINT = 0.02f;
    /** The most extra speed being ahead can give. */
    private static final float MAX_BONUS = 0.25f;
    /** Levels past intelligence that halve the speed. */
    private static final float HALVING_LEVELS = 5f;
    /** The slowest learning gets: very slow, never stopped. */
    private static final float MIN_SPEED = 0.02f;

    private IntelligenceLearning() {}

    /** Learning speed at {@code level} with {@code intelligence} (fractional, so every small gain helps). */
    public static float speed(int level, float intelligence) {
        float gap = intelligence - level;
        if (gap >= 0f) return 1f + Math.min(MAX_BONUS, BONUS_PER_POINT * gap);
        return Math.max(MIN_SPEED, (float) Math.pow(0.5, -gap / HALVING_LEVELS));
    }
}
