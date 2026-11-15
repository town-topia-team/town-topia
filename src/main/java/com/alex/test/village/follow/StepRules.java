package com.alex.test.village.follow;

/**
 * Judges one step between neighbouring tiles from the floor's real shape under the villager's
 * whole width, the way Minecraft's collision will meet it, instead of from one height per tile.
 *
 * <p>The map stores one standing height per tile, so a stair is "one block up" from every side.
 * From its front the body walks it (half a block, then half a block); from its side the strip
 * the body covers meets the full-height back half and needs a jump. A slab then a full block is
 * two walking steps; a slab straight up onto a full block is one and a half, too high. All of
 * that falls out of walking the body's footprint along the step slice by slice.
 *
 * <p>The caller samples the world (round 2); this class only does the arithmetic, so it has no
 * Minecraft in it and can be unit tested.
 */
public final class StepRules {
    /** Minecraft's step-up for walking mobs: a rise this high or lower is walked onto. */
    public static final double STEP_UP = 0.6;
    /** The highest rise a jump is planned for; matches {@code NavGraph.MAX_STEP_UP}. */
    public static final double JUMP_UP = 1.125;
    /** The deepest drop walked off on purpose: {@code NpcEntity.getMaxFallDistance} with no target. */
    public static final double MAX_DROP = 3.0;

    private static final double EPS = 1.0E-4;

    private StepRules() {}

    /**
     * Walks the body's leading edge from where it stands on the start tile (its middle on the
     * start tile's middle) across the step, one slice at a time, and judges the step.
     *
     * @param fromStand the floor height the body stands on before the step
     * @param tops      floor top per slice along the step, nearest first: the highest collision
     *                  top within the strip the body's width covers, NaN where there is no floor
     *                  within reach. Slice 0 starts at the body's leading edge.
     * @param ceilings  lowest collision bottom above each slice's floor (absolute Y), or
     *                  {@code Double.POSITIVE_INFINITY} when open; same length as {@code tops}
     * @param slice     length of one slice, in blocks
     * @param depth     the body's depth along the step (its width), in blocks
     * @param height    the body's height
     */
    public static Step classify(double fromStand, double[] tops, double[] ceilings,
                                double slice, double depth, double height) {
        if (tops.length == 0 || tops.length != ceilings.length || slice <= 0.0) return Step.none();
        int footprint = Math.max(1, (int) Math.round(depth / slice));
        double support = fromStand;
        double maxRise = 0.0;
        double riserAt = 0.5;
        boolean jump = false;

        for (int i = 0; i < tops.length; i++) {
            double lead = tops[i];
            if (!Double.isNaN(lead)) {
                double rise = lead - support;
                if (rise > JUMP_UP + EPS) return Step.none();
                if (rise > STEP_UP + EPS) {
                    if (!jump) riserAt = depth / 2.0 + i * slice;
                    jump = true;
                    maxRise = Math.max(maxRise, rise);
                }
            }
            // The body rests on the highest floor anywhere under its footprint. Slices behind
            // slice 0 are the start tile's floor.
            double next = Double.NEGATIVE_INFINITY;
            double ceiling = Double.POSITIVE_INFINITY;
            for (int k = i - footprint + 1; k <= i; k++) {
                if (k < 0) {
                    next = Math.max(next, fromStand);
                    continue;
                }
                if (!Double.isNaN(tops[k])) next = Math.max(next, tops[k]);
                ceiling = Math.min(ceiling, ceilings[k]);
            }
            // No floor under the whole body within reach: a fall deeper than a route allows.
            if (next == Double.NEGATIVE_INFINITY) return Step.none();
            if (fromStand - next > MAX_DROP + EPS) return Step.none();
            if (ceiling - next < height - EPS) return Step.none();
            support = next;
        }

        if (jump) return new Step(StepKind.JUMP, maxRise, riserAt);
        double rise = support - fromStand;
        if (-rise > STEP_UP + EPS) return new Step(StepKind.DROP, rise, 0.5);
        return new Step(StepKind.WALK, rise, 0.5);
    }
}
