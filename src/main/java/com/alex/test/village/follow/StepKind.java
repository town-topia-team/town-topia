package com.alex.test.village.follow;

/**
 * What one step between two neighbouring route tiles asks of the body. Decided once, when a
 * route is adopted, from the blocks' real shapes (see {@link StepRules}); the walker then knows
 * where to just walk, where to jump and where to hand over to the door or ladder code.
 */
public enum StepKind {
    /** Every rise on the way is a step Minecraft walks up by itself: stair fronts, slabs, carpets, paths. */
    WALK,
    /** One rise needs a jump: a full block, the side or back of a stair. */
    JUMP,
    /** The floor ends lower than a walking step: the body walks off and falls. */
    DROP,
    /**
     * A door or fence gate stands between the tiles. Not used since round 3: door tiles are
     * marked by the village side and their steps judged as ground with the door taken as open.
     */
    DOOR,
    /** A ladder rung on either side; the ladder code takes the step (set by the village side, not StepRules). */
    LADDER,
    /** Too high, too deep or no room overhead: the route has to go another way. */
    NONE
}
