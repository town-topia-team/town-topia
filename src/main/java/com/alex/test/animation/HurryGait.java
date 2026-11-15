package com.alex.test.animation;

import javax.annotation.Nullable;

/**
 * How a villager in a hurry moves: the loop the walk controller plays in place of
 * the walk while it moves. The job sets it on the server every tick it wants it
 * ({@code NpcEntity.hurry}); it is synced, and it drops by itself half a second
 * after the job stops asking, so no villager is left running on a stroll.
 *
 * <p>{@link #naturalSpeed} is how fast the clip itself "moves" at playback 1, in
 * blocks a second: the speed its planted foot slides back under the villager,
 * measured by forward kinematics on the shipping rig at 60 samples a second
 * (median of the lower foot while the other is lifted, cross-checked against the
 * lowest foot on every sample). {@link RunPace} turns it into the playback speed
 * that suits how fast the villager is really going. For comparison the walk is 1.32.
 *
 * <p>{@link #leapPhases} are where in the loop (0 to 1) the feet are split widest,
 * front to back: the pose a running leap holds. In the air the stride is carried
 * to the next of them and held there (NpcEntity, round 1 of the running hop).
 */
public enum HurryGait {
    NONE(null, 0f),
    /** Mixamo "Running": a cleric to someone badly hurt, a villager fleeing, an archer to a far mob. */
    RUN("mixamo_run", 2.50f, 0.211f, 0.684f),
    /** Mixamo "Run With Sword", one hand: a guard or the captain on its way to a fight. */
    SWORD_RUN("mixamo_run_sword", 1.96f, 0.25f, 0.708f),
    /** Mixamo "Injured Run": a hurt defender falling back to a building. One long stride a loop. */
    INJURED_RUN("mixamo_injured_run", 1.39f, 0.632f),
    /** Mixamo "Jogging", a light jog: everyone hurrying indoors when the alarm bell rings. */
    JOG("mixamo_jog", 1.04f, 0.091f, 0.247f, 0.416f, 0.747f, 0.909f);

    private static final HurryGait[] VALUES = values();

    @Nullable private final String clip;
    private final float naturalSpeed;
    private final float[] leapPhases;

    HurryGait(@Nullable String clip, float naturalSpeed, float... leapPhases) {
        this.clip = clip;
        this.naturalSpeed = naturalSpeed;
        this.leapPhases = leapPhases;
    }

    /** The clip's name in the animation files; null for {@link #NONE}. */
    @Nullable
    public String clip() {
        return clip;
    }

    /** Blocks a second the clip covers at playback speed 1. */
    public float naturalSpeed() {
        return naturalSpeed;
    }

    /**
     * How far the loop is from its nearest widest stride, as a share of the loop:
     * [0] ahead (still to come), [1] behind (just passed). Both 1 for a gait with none.
     */
    public double[] leapDistance(double phase) {
        double ahead = 1.0, behind = 1.0;
        for (float leap : leapPhases) {
            double a = leap - phase;
            if (a < 0) a += 1.0;
            double b = phase - leap;
            if (b < 0) b += 1.0;
            ahead = Math.min(ahead, a);
            behind = Math.min(behind, b);
        }
        return new double[] {ahead, behind};
    }

    public static HurryGait byId(int id) {
        return id > 0 && id < VALUES.length ? VALUES[id] : NONE;
    }
}
