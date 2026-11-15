package com.alex.test.village.nav;

/**
 * Climb cost 2C (10-18): what one step of a route costs the planner, in tenths of a level tile at
 * walking pace. A walking villager loses about 0.45 s on a full-block climb (Minecraft's jump: up
 * half a block before it, bump the face, creep over), roughly one and a half tiles of walking, and
 * nothing on a stair front or a slab, which Minecraft walks up as two half steps. Going down, the
 * player wants stairs used rather than hopping off blocks, so a drop costs as much per block as a
 * climb (its real time is only a tick or three). A staircase then wins whenever the walk round to
 * it is under about one and a half tiles per block, up or down.
 *
 * <p>No Minecraft here (unit-testable): the village side passes the rise between the two tiles'
 * standing heights and whether the step goes in or out over a stair's low half.
 */
public final class StepPrice {

    /** One level tile. The planner's distance guess is in the same unit: tiles times this. */
    public static final int FLAT = 10;
    /** A rise Minecraft walks up without a jump (its step height). */
    public static final double STEP_UP = 0.6;
    /** A stair's low half stands this far below its top. */
    public static final double HALF_STEP = 0.5;
    private static final double EPS = 1.0E-4;

    /** Extra for a full-block climb or a jump onto a stair's side; live: {@code /villager walkfeel jumpcost}. */
    public static final int CLIMB_EXTRA_DEFAULT = 15;
    /** Extra per block dropped off a ledge; live: {@code /villager walkfeel dropcost}. */
    public static final int DROP_EXTRA_DEFAULT = 15;
    public static volatile int climbExtra = CLIMB_EXTRA_DEFAULT;
    public static volatile int dropExtraPerBlock = DROP_EXTRA_DEFAULT;

    private StepPrice() {}

    /**
     * A level-ground step (no ladder, no water landing).
     *
     * @param rise        the next tile's standing height minus this one's
     * @param halfStepIn  the step goes onto the next tile over a stair's low half
     * @param halfStepOut the step leaves this tile over a stair's low half
     */
    public static int ground(double rise, boolean halfStepIn, boolean halfStepOut) {
        return switch (call(rise, halfStepIn, halfStepOut)) {
            case CLIMB -> FLAT + climbExtra;
            case DROP -> FLAT + dropExtraPerBlock * Math.max(1, (int) Math.round(-rise));
            default -> FLAT;
        };
    }

    /** How the planner reads a level-ground step: what it expects the body to do there. */
    public enum Call {
        /** Within a step's height either way: slabs, carpets, paths, flat. */
        LEVEL,
        /** Up a stair's front: two half steps. */
        WALK_UP,
        /** A jump: a full block, or a stair's side or back. */
        CLIMB,
        /** Down off a stair's low half: two half steps. */
        WALK_DOWN,
        /** Off a ledge, falling. */
        DROP
    }

    /** The planner's reading of a step, from the same facts {@link #ground} prices. */
    public static Call call(double rise, boolean halfStepIn, boolean halfStepOut) {
        if (rise > STEP_UP + EPS) return walksUp(rise, halfStepIn) ? Call.WALK_UP : Call.CLIMB;
        if (-rise > STEP_UP + EPS) return walksDown(rise, halfStepOut) ? Call.WALK_DOWN : Call.DROP;
        return Call.LEVEL;
    }

    /** A rise over a step's height that is two half steps (a stair's front): walked, not jumped. */
    public static boolean walksUp(double rise, boolean halfStepIn) {
        return halfStepIn && rise - HALF_STEP <= STEP_UP + EPS;
    }

    /** A fall over a step's height off a stair's low half: two half steps down, walked. */
    public static boolean walksDown(double rise, boolean halfStepOut) {
        return halfStepOut && -rise - HALF_STEP <= STEP_UP + EPS;
    }
}
