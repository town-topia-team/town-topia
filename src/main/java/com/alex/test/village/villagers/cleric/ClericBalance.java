package com.alex.test.village.villagers.cleric;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;

/**
 * Shared cleric balance. One skill level, 1 to 100, makes the healing throw
 * faster and stronger, and opens four unlocks:
 *
 * <pre>
 *   level   throw     heal        unlocks
 *   1       3.85 s    2 hearts    the healing orb, thrown from 8 blocks
 *   25                            Cleanse: the orb cures poison and the like, and puts out fire
 *   50                            Splash: the orb also heals everyone close to the patient
 *   75                            Renewal: a few seconds of Regeneration after each heal
 *   100     1.95 s    4 hearts    Guardian Angel: once a day, saves a villager near it from death
 * </pre>
 *
 * <p>Experience is paid in care points rather than per heal, because a
 * cleric in a safe village has few patients: one point per heart healed, and
 * points for the rest of its day too (bringing food, cheering someone up, the
 * morning prayer, each stop on its rounds). A day's first
 * {@link #FULL_DAY_POINTS} points pay in full and the rest a fifth, so a full
 * day, not a war, sets the pace: 80 days to level 100 learning at 1x.
 */
public final class ClericBalance {

    public static final int MAX_LEVEL = 100;

    /** First levels of the four unlocks. */
    public static final int CLEANSE_LEVEL = 25;
    public static final int SPLASH_LEVEL = 50;
    public static final int RENEWAL_LEVEL = 75;
    public static final int GUARDIAN_LEVEL = 100;

    /** How far the cleric throws its orb from, in blocks. */
    public static final double CAST_RANGE = 8.0;

    /** Splash: everyone this close to the patient gets half the heal. */
    public static final double SPLASH_RADIUS = 3.0;
    public static final float SPLASH_SHARE = 0.5f;

    /** Renewal: Regeneration I for this long after a heal. */
    public static final int RENEWAL_TICKS = 100;

    /** Guardian Angel: a villager about to die this close to the cleric is saved. */
    public static final double GUARDIAN_RADIUS = 16.0;
    public static final int GUARDIAN_SAVES_PER_DAY = 1;
    /** Health a saved villager is left on, and its Regeneration II afterwards. */
    public static final float GUARDIAN_HEALTH = 6f;
    public static final int GUARDIAN_REGEN_TICKS = 200;

    /**
     * Walking speed while hurrying to a badly hurt patient: about 1.3 times a walk
     * (VillagerModel.WALK_SPEED, 0.55), a little quicker than fleeing (0.7).
     * Written out rather than read from there so the skill tracks stay free of
     * game classes for their tests.
     */
    public static final float RUN_SPEED = 0.725f;

    /** Food handed over on one visit, and what the cleric keeps for itself. */
    public static final int FOOD_GIVEN = 4;
    public static final int FOOD_KEPT = 4;

    /** In-game days to level 100 learning at 1x, with a full day of care every day. */
    public static final int TARGET_DAYS_TO_MAX = 80;

    /** Care points a day that pay in full; every point after that pays a fifth. */
    public static final int FULL_DAY_POINTS = 40;
    public static final float OVER_FULL_DAY_SHARE = 0.2f;

    /** Care points per job. */
    public static final float POINTS_PER_HEART = 1f;
    public static final float POINTS_FOOD_DELIVERED = 3f;
    public static final float POINTS_CHEERED_UP = 3f;
    public static final float POINTS_MORNING_PRAYER = 5f;
    public static final float POINTS_ROUNDS_STOP = 1f;

    /** Cumulative experience for level 100: the scale saved cleric experience is measured on. */
    public static final float EXPERIENCE_FOR_MAX_LEVEL = 4000f;

    /** Raw experience per care point at 1x: 1.25, for 80 days at 40 points a day. */
    public static final float EXPERIENCE_PER_POINT = EXPERIENCE_FOR_MAX_LEVEL
            / (TARGET_DAYS_TO_MAX * (float) FULL_DAY_POINTS);

    /** Share of the total that is linear in the level; the rest is quadratic. */
    private static final double XP_LINEAR_SHARE = 1.0 / 3.0;

    private ClericBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    /** Cumulative experience needed for {@code level}. */
    public static float experienceForLevel(int level) {
        double p = progress(level);
        return (float) (EXPERIENCE_FOR_MAX_LEVEL
                * (XP_LINEAR_SHARE * p + (1.0 - XP_LINEAR_SHARE) * p * p));
    }

    /**
     * The throw clip's playback rate before skill: 30% slower than the take
     * (asked for 10-04, it read as rushed at the take's own pace).
     */
    private static final double CLIP_PACE = 0.7;

    /** How fast the throw plays: 0.7x at level 1, twice that at 100. */
    public static float castSpeed(int level) {
        return (float) (CLIP_PACE * (1.0 + progress(level)));
    }

    /**
     * Ticks from the start of a throw until the orb leaves the hand: the
     * throw's own blend in (cast_controller), then the clip up to the snap,
     * both at the throw's pace.
     */
    public static int releaseTicks(int level) {
        return Math.round((VillagerAnimationState.CAST_BLEND_TICKS
                + VillagerAnimationState.CAST_RELEASE_TICKS) / castSpeed(level));
    }

    /** Ticks one whole throw takes, blend in, wind-up and follow-through. */
    public static int castTicks(int level) {
        return Math.round((VillagerAnimationState.CAST_BLEND_TICKS
                + VillagerAnimationState.cycleTicks(VillagerAnimationState.CAST)) / castSpeed(level));
    }

    /** Health one orb restores: 4 (two hearts) at level 1, 8 at 100, in half hearts. */
    public static float healAmount(int level) {
        return Math.round((4.0 + 4.0 * progress(level)) * 2.0) / 2f;
    }

    /** Happiness one chat cheering up a sad villager gives: 6 at level 1, 12 at 100. */
    public static float cheerUpAmount(int level) {
        return (float) (6.0 + 6.0 * progress(level));
    }

    /** Battle blessing: guards and archers this close to the cleric are blessed. */
    public static final double BLESSING_RADIUS = 16.0;
    /** One cleric blesses at most once in this long: a bell rung again and again does not keep it up. */
    public static final int BLESSING_COOLDOWN_TICKS = 20 * 60 * 5;
    public static final float POINTS_BATTLE_BLESSING = 3f;

    /** How long the battle blessing's Resistance and Regeneration last: 15 s at level 1, 40 s at 100. */
    public static int blessingTicks(int level) {
        return (int) Math.round(20.0 * (15.0 + 25.0 * progress(level)));
    }

    public static boolean cleanses(int level) { return level >= CLEANSE_LEVEL; }

    public static boolean splashes(int level) { return level >= SPLASH_LEVEL; }

    public static boolean renews(int level) { return level >= RENEWAL_LEVEL; }

    public static boolean guardianAngel(int level) { return level >= GUARDIAN_LEVEL; }
}
