package com.alex.test.animation;

/**
 * The lumberjack's three sideways two-hand chops, baked in tools/chop/lumberjack-chop-sideways.html:
 * which clip, how long one loop is, the clip ticks the blade bites and where it lands on the bark,
 * and where the clip's first frame holds the axe (for the turn into the hold, HeldItemGeoLayer).
 * He picks one per tree and loops it until the tree is down (ChopTreeActivity).
 *
 * <p>All three stand side-on, left shoulder to the trunk, and were baked for a trunk whose centre
 * is 1.5 blocks straight ahead of the hitbox (ChopTreeActivity.STAND_DISTANCE), with the axe at
 * HeldItemGeoLayer.LUMBERJACK_AXE_SCALE. Re-bake them if either changes, and copy the new hit
 * table and first axe_hold keys in here.
 */
public enum LumberChopSwing {
    /** One big level swing a loop, like a baseball bat: hips lead, the back knee drives in. */
    BAT("lumberjack_chop_bat", 29, new float[][] {{13.6f, 4f, 17.05f, 16.6f}},
            new float[] {104.1f, 15.3f, 129.5f}, new float[] {2.85f, -2.76f, 1.22f}),
    /** Opens a notch: a cut slanting down from high up, then a level cut just under it. */
    NOTCH("lumberjack_chop_notch", 56, new float[][] {{13.8f, 4f, 17.9f, 16.6f}, {41f, 4.18f, 15.55f, 16.86f}},
            new float[] {115.8f, 25.6f, 137.5f}, new float[] {2.65f, -3.18f, 0.4f}),
    /** Two quick, compact level chops a loop, with little body turn. */
    SHORT("lumberjack_chop_short", 32, new float[][] {{7.2f, 4f, 17.49f, 16.6f}, {23.2f, 4.44f, 16.49f, 16.54f}},
            new float[] {121.4f, 7.7f, 137.1f}, new float[] {2.83f, -3.05f, 0.03f});

    private static final LumberChopSwing[] ALL = values();

    /** The clip's name in base_villager.animation.json. */
    public final String clip;
    /** One loop of the clip, in ticks. */
    public final int loopTicks;
    /** Per bite in a loop: {clip tick, px to the hitbox's right, px up, px ahead}. */
    private final float[][] bites;
    /** The axe_hold bone's keys at the clip's first frame, as written in the clip: rotation (deg), position (px). */
    private final float[] firstHoldRotation, firstHoldPosition;

    LumberChopSwing(String clip, int loopTicks, float[][] bites,
                    float[] firstHoldRotation, float[] firstHoldPosition) {
        this.clip = clip;
        this.loopTicks = loopTicks;
        this.bites = bites;
        this.firstHoldRotation = firstHoldRotation;
        this.firstHoldPosition = firstHoldPosition;
    }

    /** Clip ticks from the clip's first frame to the n-th bite (0-based, counting across loops). */
    public float biteTick(int n) {
        return (n / bites.length) * loopTicks + bites[n % bites.length][0];
    }

    /** How many bites have landed by {@code clipTick} ticks into the clip (blend in not counted), across loops. */
    public int bitesBefore(float clipTick) {
        if (clipTick < 0f) return 0;
        int loops = (int) (clipTick / loopTicks);
        float inLoop = clipTick - loops * loopTicks;
        int n = loops * bites.length;
        for (float[] bite : bites) if (bite[0] <= inLoop) n++;
        return n;
    }

    /** Where the n-th bite lands, in blocks: to the hitbox's right, up, and ahead. */
    public float biteRight(int n) { return bites[n % bites.length][1] / 16f; }
    public float biteUp(int n) { return bites[n % bites.length][2] / 16f; }
    public float biteAhead(int n) { return bites[n % bites.length][3] / 16f; }

    /** The first frame's axe_hold rotation key (deg; axis 0 x, 1 y, 2 z, as keyed). */
    public float firstHoldRotation(int axis) { return firstHoldRotation[axis]; }
    /** The first frame's axe_hold position key (px; axis 0 x, 1 y, 2 z, as keyed). */
    public float firstHoldPosition(int axis) { return firstHoldPosition[axis]; }

    /**
     * The swing a chop clip plays, read off the synced work sequence, so the server's sounds and
     * every client's clip agree with nothing else sent. Each step of the sequence is the next
     * swing: NpcEntity.startLumberChop moves it on to the swing the tree was given.
     */
    public static LumberChopSwing forSequence(int sequence) {
        return ALL[Math.floorMod(sequence, ALL.length)];
    }
}
