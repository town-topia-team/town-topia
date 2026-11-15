package com.alex.test.village.villagers.archer;

import com.alex.test.village.villagers.guard.GuardBalance;

/**
 * The archer's 1..100 skill track. Pure numbers, like {@link GuardBalance}.
 *
 * <p>Practice is the guard's, at the guard's pay and full day: time on duty,
 * every arrow that hits, every kill and range practice (paid like a drill),
 * on the same experience curve, so a peaceful day with range practice reaches
 * level 100 in about 80 in-game days at 1x. Every level draws the bow a little
 * faster and aims a little truer; milestones at 25, 50, 75 and 100 add a
 * technique each.
 */
public final class ArcherBalance {

    public static final int MAX_LEVEL = 100;

    /** Quick Nock: half the pause after a shot before the next draw. */
    public static final int QUICK_NOCK_LEVEL = 25;
    /** Piercing Shot: an arrow goes through the first mob into the one behind. */
    public static final int PIERCING_LEVEL = 50;
    /** Eagle Eye: spots mobs half as far again, and shoots 4 blocks further. */
    public static final int EAGLE_EYE_LEVEL = 75;
    /** Volley: every fourth shot looses a second arrow at no cost. */
    public static final int VOLLEY_LEVEL = 100;

    /**
     * Ticks from the start of a draw to the arrow leaving (the AIM clip is paced so its
     * release frame lands here): 1.8 s for a novice,
     * 1.2 s at level 100.
     */
    private static final int NOVICE_DRAW_TICKS = 36;
    private static final int MASTER_DRAW_TICKS = 24;
    /** Spread passed to vanilla's shot: a skeleton on Normal is 6, a player 1. */
    private static final float NOVICE_INACCURACY = 4.0f;
    private static final float MASTER_INACCURACY = 1.0f;
    /**
     * How fast the arrow leaves the bow, in blocks a tick: a new archer 2.2,
     * a level 100 one 3.0, a player's full draw. Faster is flatter and gives a
     * walking mob less time to step aside, so it adds to the narrowing spread.
     * Each hit stays as hard whatever the speed (ArcherBowUtils.fullDrawArrow).
     */
    private static final float NOVICE_ARROW_SPEED = 2.2f;
    private static final float MASTER_ARROW_SPEED = 3.0f;
    private static final int SHOT_RECOVERY_TICKS = 10;
    private static final int QUICK_NOCK_RECOVERY_TICKS = 5;
    public static final double BASE_MAX_RANGE = 14.0D;
    private static final double EAGLE_EYE_EXTRA_RANGE = 4.0D;
    public static final double EAGLE_EYE_SPOT_MULTIPLIER = 1.5D;
    public static final int VOLLEY_EVERY_SHOTS = 4;

    private ArcherBalance() {}

    /** Same curve as the guard's: 20·(L−1) + 0.2·(L−1)². */
    public static float experienceForLevel(int level) {
        return GuardBalance.experienceForLevel(Math.clamp(level, 1, MAX_LEVEL));
    }

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    public static int drawTicks(int level) {
        return (int) Math.round(NOVICE_DRAW_TICKS - (NOVICE_DRAW_TICKS - MASTER_DRAW_TICKS) * progress(level));
    }

    public static float inaccuracy(int level) {
        return (float) (NOVICE_INACCURACY - (NOVICE_INACCURACY - MASTER_INACCURACY) * progress(level));
    }

    public static float arrowSpeed(int level) {
        return (float) (NOVICE_ARROW_SPEED + (MASTER_ARROW_SPEED - NOVICE_ARROW_SPEED) * progress(level));
    }

    public static int recoveryTicks(int level) {
        return level >= QUICK_NOCK_LEVEL ? QUICK_NOCK_RECOVERY_TICKS : SHOT_RECOVERY_TICKS;
    }

    public static int pierceLevel(int level) {
        return level >= PIERCING_LEVEL ? 1 : 0;
    }

    public static boolean hasEagleEye(int level) {
        return level >= EAGLE_EYE_LEVEL;
    }

    public static double maxShootRange(int level) {
        return BASE_MAX_RANGE + (hasEagleEye(level) ? EAGLE_EYE_EXTRA_RANGE : 0.0D);
    }

    public static boolean hasVolley(int level) {
        return level >= VOLLEY_LEVEL;
    }

    /** Seconds per shot, draw plus pause, for the skill panel. */
    public static double secondsPerShot(int level) {
        return (drawTicks(level) + recoveryTicks(level)) / 20.0D;
    }
}
