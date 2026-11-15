package com.alex.test.village.villagers.miner;

import com.alex.test.village.villagers.miner.OreHeights.Ore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** The blocks behind each {@link Ore}, kept out of OreHeights so tests can load that. */
public final class OreBlocks {

    private OreBlocks() {}

    /** The ore as it sits in stone. */
    public static Block stone(Ore ore) {
        return switch (ore) {
            case COAL -> Blocks.COAL_ORE;
            case COPPER -> Blocks.COPPER_ORE;
            case IRON -> Blocks.IRON_ORE;
            case LAPIS -> Blocks.LAPIS_ORE;
            case REDSTONE -> Blocks.REDSTONE_ORE;
            case GOLD -> Blocks.GOLD_ORE;
            case DIAMOND -> Blocks.DIAMOND_ORE;
        };
    }

    /** The ore as it sits in deepslate. */
    public static Block deepslate(Ore ore) {
        return switch (ore) {
            case COAL -> Blocks.DEEPSLATE_COAL_ORE;
            case COPPER -> Blocks.DEEPSLATE_COPPER_ORE;
            case IRON -> Blocks.DEEPSLATE_IRON_ORE;
            case LAPIS -> Blocks.DEEPSLATE_LAPIS_ORE;
            case REDSTONE -> Blocks.DEEPSLATE_REDSTONE_ORE;
            case GOLD -> Blocks.DEEPSLATE_GOLD_ORE;
            case DIAMOND -> Blocks.DEEPSLATE_DIAMOND_ORE;
        };
    }
}
