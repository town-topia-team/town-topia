package com.alex.test.animation;

/**
 * Gives simultaneous workers a small, stable start offset so their looping
 * animations do not play in lockstep.
 */
public final class WorkAnimationStagger {

    private static final int STAGGER_WINDOW_TICKS = 7;

    private WorkAnimationStagger() {}

    /** Returns a 0-6 tick delay based on the entity's level-unique id. */
    public static int delayTicks(int entityId) {
        return Math.floorMod(entityId, STAGGER_WINDOW_TICKS);
    }
}
