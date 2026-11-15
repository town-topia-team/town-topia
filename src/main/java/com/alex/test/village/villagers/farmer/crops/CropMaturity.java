package com.alex.test.village.villagers.farmer.crops;

/**
 * Rule for whether a planted crop block is ready to harvest. Purely
 * descriptive: {@link CropDefinitionRegistry#isMature} does the actual
 * evaluation, since some rules (stack height) need the block above, not just
 * the state.
 */
public sealed interface CropMaturity {

    /** Ordinary {@code CropBlock}s and any block with a generic {@code age} property. */
    record AgeProperty() implements CropMaturity {}

    /** Stacked plants (sugar cane): mature when the block above is the same crop block. */
    record StackHeight() implements CropMaturity {}

    /** The crop block (a stem) is never itself "mature"; an adjacent fruit block is the signal. */
    record AttachedFruit() implements CropMaturity {}

    /** Glow berry vines: ripe while the segment's {@code berries} property is true. */
    record BerriesProperty() implements CropMaturity {}
}
