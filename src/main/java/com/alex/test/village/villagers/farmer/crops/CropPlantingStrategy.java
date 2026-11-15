package com.alex.test.village.villagers.farmer.crops;

import java.util.List;

/** Where and on what soil a crop family may be tilled/planted. */
public sealed interface CropPlantingStrategy {

    /** Ordinary farmland: needs a tilled, hydrated farmland tile below an air tile. */
    record Farmland() implements CropPlantingStrategy {}

    /**
     * Sugar-cane-style: needs one of {@code soilBlocks} below an air tile, with a
     * water source adjacent horizontally. Never tilled.
     */
    record NearWater(List<BlockMatcher> soilBlocks) implements CropPlantingStrategy {
        public boolean matchesSoil(net.minecraft.world.level.block.Block block) {
            for (BlockMatcher matcher : soilBlocks) if (matcher.matches(block)) return true;
            return false;
        }
    }

    /**
     * Cocoa-style: grows on the side of one of {@code logBlocks}, facing it.
     * Needs no soil and no water. Worked by {@code FarmerPerennials}, not the
     * field survey.
     */
    record OnLog(List<BlockMatcher> logBlocks) implements CropPlantingStrategy {
        public boolean matchesLog(net.minecraft.world.level.block.Block block) {
            for (BlockMatcher matcher : logBlocks) if (matcher.matches(block)) return true;
            return false;
        }
    }

    /**
     * Sweet-berry-style: a bush planted straight onto one of {@code soilBlocks}
     * (grass, dirt...). Needs no farmland and no water. Worked by
     * {@code FarmerPerennials}, which grows every patch in walkway rows.
     */
    record OnSoil(List<BlockMatcher> soilBlocks) implements CropPlantingStrategy {
        public boolean matchesSoil(net.minecraft.world.level.block.Block block) {
            for (BlockMatcher matcher : soilBlocks) if (matcher.matches(block)) return true;
            return false;
        }
    }

    /**
     * Glow berry vines hanging from a ceiling. Farmers only pick them and never
     * plant any: where vines grow is the player's choice.
     */
    record Hanging() implements CropPlantingStrategy {}

    /** Planted and tended outside the water-anchored field survey. */
    default boolean perennial() {
        return this instanceof OnLog || this instanceof OnSoil || this instanceof Hanging;
    }

    /** Never planted by a farmer, only harvested. */
    default boolean harvestOnly() {
        return this instanceof Hanging;
    }
}
