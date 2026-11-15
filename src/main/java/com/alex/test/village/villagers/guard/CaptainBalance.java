package com.alex.test.village.villagers.guard;

/**
 * Captain balance: the barracks' elite guard. One skill level, 1 to 100, on
 * the same experience curve as the guard ({@link GuardBalance}); every stat
 * starts where a seasoned guard ends and keeps climbing.
 *
 * <p>A captain knows shield block and sweeping strike from the start, with a
 * bigger guard meter and a stronger sweep than a guard's. Its milestones are its own:
 *
 * <pre>
 *   level   technique
 *   25      Rallying cry    -- defenders near a fighting captain hit harder and hold longer
 *   50      Shockwave bash  -- every 3rd hit with a shield staggers everything around the target
 *   75      Veteran's eye   -- spots hostiles from half as far again (15 blocks, not 10)
 *   100     Unbreakable     -- second wind twice a day, and it heals nearby defenders too
 * </pre>
 */
public final class CaptainBalance {
    public static final int MAX_LEVEL = 100;

    public static final int RALLY_LEVEL = 25;
    public static final int SHOCKWAVE_LEVEL = 50;
    public static final int VETERAN_EYE_LEVEL = 75;
    public static final int UNBREAKABLE_LEVEL = 100;

    /** Extra health over the villagers' base 30: 40 at level 1, 60 at 100. */
    private static final double NOVICE_BONUS_HEALTH = 10.0;
    private static final double MASTER_BONUS_HEALTH = 30.0;
    private static final int NOVICE_ATTACK_TICKS = 22;
    private static final int MASTER_ATTACK_TICKS = 16;
    private static final double NOVICE_RESPONSE_SPEED = 1.50;
    private static final double MASTER_RESPONSE_SPEED = 1.70;
    /** One health every 40 ticks out of a fight at level 1, every 20 at 100. */
    private static final int NOVICE_REGEN_TICKS = 40;

    // Shield stance, as the guard's but with a bigger meter from the first level.
    private static final float METER_NOVICE = 20f;
    private static final float METER_MASTER = 45f;
    /** Defenders standing right behind a raised captain have this share of a hit blocked. */
    public static final float SHIELD_WALL_SHARE = 0.25f;
    public static final double SHIELD_WALL_DEPTH = 2.5;

    public static final float SWEEP_DAMAGE_FRACTION = 0.5f;
    public static final int BASH_EVERY_HITS = 3;
    /** Shockwave bash also staggers every hostile this close to the target. */
    public static final double SHOCKWAVE_RADIUS = 2.0;
    public static final int SECOND_WINDS_PER_DAY = 2;
    /** Unbreakable: share of their health defenders nearby regain with the captain. */
    public static final float UNBREAKABLE_ALLY_HEAL_FRACTION = 0.2f;
    public static final double UNBREAKABLE_RADIUS = 12.0;

    /** Rallying cry reach, and how long a defender stays rallied after the last call. */
    public static final double RALLY_RADIUS = 16.0;
    public static final int RALLY_TICKS = 60;
    public static final double RALLY_DAMAGE_BONUS = 0.15;
    /** A rallied defender falls back at this share of its usual retreat health. */
    public static final double RALLY_RETREAT_FACTOR = 0.8;

    /** Veteran's eye: the captain spots hostiles this much farther than a guard. */
    public static final double VETERAN_EYE_FACTOR = 1.5;

    private CaptainBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    private static double lerp(double from, double to, int level) {
        return from + (to - from) * progress(level);
    }

    /** Same curve and full day as the guard: about 80 in-game days to 100 learning at 1x. */
    public static float experienceForLevel(int level) {
        return GuardBalance.experienceForLevel(level);
    }

    public static double bonusHealth(int level) {
        return lerp(NOVICE_BONUS_HEALTH, MASTER_BONUS_HEALTH, level);
    }

    /** Ticks from one sword swing to the next: 22 (1.1 s) at level 1 down to 16 (0.8 s) at 100. */
    public static int attackTicks(int level) {
        return (int) Math.round(lerp(NOVICE_ATTACK_TICKS, MASTER_ATTACK_TICKS, level));
    }

    public static double responseSpeed(int level) {
        return lerp(NOVICE_RESPONSE_SPEED, MASTER_RESPONSE_SPEED, level);
    }

    public static int regenTicks(int level) {
        return (int) Math.round(NOVICE_REGEN_TICKS / (1.0 + progress(level)));
    }

    public static float guardMeter(int level) {
        return (float) lerp(METER_NOVICE, METER_MASTER, level);
    }

    public static boolean hasRallyingCry(int level) {
        return level >= RALLY_LEVEL;
    }

    public static boolean hasShockwave(int level) {
        return level >= SHOCKWAVE_LEVEL;
    }

    public static boolean hasVeteranEye(int level) {
        return level >= VETERAN_EYE_LEVEL;
    }

    public static boolean isUnbreakable(int level) {
        return level >= UNBREAKABLE_LEVEL;
    }
}
