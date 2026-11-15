package com.alex.test.animation;

/**
 * Playback speed for a {@link HurryGait}: the legs keep up with the ground.
 *
 * <p>A clip shows a villager covering {@link HurryGait#naturalSpeed} blocks a
 * second; a villager hurrying covers more, by its job's speed (a guard answering
 * an alarm about 6-7 blocks a second, the cleric 5.7, a villager fleeing 5.3).
 * Played at 1 the body would glide over the ground with its feet left behind.
 *
 * <p>The yardstick is the walk, which every villager plays at 1 and which nobody
 * has called off: 2.70 blocks a second over a clip whose planted foot moves 1.32,
 * so the ground passes about {@value #DEFAULT_STRIDE} times as fast as the feet.
 * A hurry clip is played at the speed that gives it that same relation at the
 * pace the villager is actually going (measured on the client, so corners, stairs
 * and slowing down show), so every gait looks as grounded as the walk. The feet
 * can be planted exactly with a stride of 1, but the legs then have to turn over
 * twice as fast, which at these speeds is a blur.
 *
 * <p>On the way up a hop the ground has nothing to say about the legs: the stride
 * is carried to its widest split within {@link #AIR_CATCH_TICKS} ticks and held
 * there at {@link #DEFAULT_AIR}, which reads as a running leap instead of legs
 * running on nothing. Over the top they run again, so they are running when the
 * feet come down.
 *
 * <p>The plain walk keeps its playback of 1 -- at a villager's walking pace that
 * is this same match -- except where a villager walks at another pace on walking
 * legs: behind a raised shield ({@link #walkRate}).
 *
 * <p>Client side, and live tuned with {@code /runpace}; not saved.
 */
public final class RunPace {

    public static final float DEFAULT_STRIDE = 2.05f;
    /**
     * Slowest and fastest playback. The floor was 0.7, which kept the legs going
     * at seven tenths while a villager stood pressed against a block face before
     * its hop; matched to the pace, a near stop now shows as one.
     */
    public static final float DEFAULT_MIN = 0.3f;
    /** A master captain answering an alarm needs about 1.8 of the sword run. */
    public static final float DEFAULT_MAX = 1.9f;
    /** Playback while a leap is held in the air: a creep, so the pose stays alive. Never 0: GeckoLib reads speed 0 as time 0, the loop's first frame. */
    public static final float DEFAULT_AIR = 0.05f;

    /** Ticks to carry the stride to its widest split once the feet leave the ground. */
    public static final double AIR_CATCH_TICKS = 3.0;
    /** This close to a widest split (share of the loop) counts as there. */
    public static final double LEAP_WINDOW = 0.06;

    private static volatile float stride = DEFAULT_STRIDE;
    private static volatile float min = DEFAULT_MIN;
    private static volatile float max = DEFAULT_MAX;
    private static volatile float air = DEFAULT_AIR;

    private RunPace() {}

    /** Playback speed for {@code gait} at {@code blocksPerSecond} over the ground. */
    public static double rate(HurryGait gait, double blocksPerSecond) {
        float natural = gait.naturalSpeed();
        if (natural <= 0f) return 1.0;
        double rate = blocksPerSecond / (natural * stride);
        return Math.max(min, Math.min(max, rate));
    }

    /** Blocks a second mixamo_walk covers at playback 1 (its planted foot, as for HurryGait). */
    public static final float WALK_NATURAL_SPEED = 1.32f;

    /**
     * Playback for the plain walk at {@code blocksPerSecond}: 1 at a villager's
     * ordinary walking pace (2.7), less for the slower walk behind a raised
     * shield (a guard 2.0-2.5, a captain 2.2-2.8), so the legs keep up with it.
     * A bard following its listener mid-tune goes at a quarter of the walk (about
     * 0.7), under the run's floor: the walk has its own, lower one, or the feet
     * would step faster than the body moves.
     */
    public static double walkRate(double blocksPerSecond) {
        double rate = blocksPerSecond / (WALK_NATURAL_SPEED * stride);
        return Math.max(WALK_MIN, Math.min(max, rate));
    }

    /** The walk's slowest playback: an eighth of its pace. Never 0 (GeckoLib reads speed 0 as frame zero). */
    public static final float WALK_MIN = 0.12f;

    /**
     * Playback that brings the loop from {@code phase} to the next widest split
     * in {@link #AIR_CATCH_TICKS} ticks. {@code loopTicks} is the clip's length.
     */
    public static double catchUpRate(double ahead, double loopTicks) {
        return Math.max(air, Math.min(max, ahead * loopTicks / AIR_CATCH_TICKS));
    }

    /** Ground speed at which {@code gait} plays at 1. */
    public static double speedAtRateOne(HurryGait gait) {
        return gait.naturalSpeed() * stride;
    }

    public static float stride() { return stride; }
    public static float min() { return min; }
    public static float max() { return max; }
    public static float air() { return air; }

    public static void setStride(float value) { stride = value; }
    public static void setMin(float value) { min = Math.min(value, max); }
    public static void setMax(float value) { max = Math.max(value, min); }
    public static void setAir(float value) { air = value; }

    public static void reset() {
        stride = DEFAULT_STRIDE;
        min = DEFAULT_MIN;
        max = DEFAULT_MAX;
        air = DEFAULT_AIR;
    }
}
