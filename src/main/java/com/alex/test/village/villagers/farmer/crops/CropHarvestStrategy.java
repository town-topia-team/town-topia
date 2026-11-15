package com.alex.test.village.villagers.farmer.crops;

/**
 * What harvesting this crop family does to the world. Execution itself always
 * rides normal block-drop calculation and destruction (see
 * {@code FarmerActivity.harvestBlock}); this only tells {@code FarmerManager}
 * the shape of the job to create.
 */
public sealed interface CropHarvestStrategy {

    /** Break the crop block outright; the tile becomes plantable again. */
    record BreakAndReplant() implements CropHarvestStrategy {}

    /** Break blocks above a base tile while they match the crop block; the base survives. */
    record PreserveBaseStack() implements CropHarvestStrategy {}

    /** Harvest an adjacent fruit block with shears; the stem/crop block is untouched. */
    record AdjacentFruit(BlockMatcher fruitBlock) implements CropHarvestStrategy {}

    /** Pick the produce off and leave the plant to grow again (sweet berries). */
    record PickKeepPlant() implements CropHarvestStrategy {}
}
