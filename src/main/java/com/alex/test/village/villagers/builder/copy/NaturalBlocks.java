package com.alex.test.village.villagers.builder.copy;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import java.util.Set;

/**
 * Which blocks the world made rather than a player: the ground, water, plants and
 * trees. Copying a building spreads through everything else, so this list decides
 * where a copied building ends.
 *
 * <p>Two kinds need their surroundings and are left to {@link BuildingShapeScanner}:
 * logs (a tree trunk, or a log wall?) and plain sandstone (desert rock under the sand,
 * or a wall?). {@link #kind} says which.
 */
final class NaturalBlocks {
    private NaturalBlocks() {}

    enum Kind { BUILT, NATURAL, LOG, SANDSTONE }

    /** Natural blocks no tag covers. Ice is frozen water; packed and blue ice count as built. */
    private static final Set<Block> EXTRA = Set.of(
            Blocks.GRAVEL, Blocks.SUSPICIOUS_GRAVEL, Blocks.CLAY, Blocks.BEDROCK, Blocks.CALCITE,
            Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW, Blocks.ICE,
            Blocks.DIRT_PATH, Blocks.FARMLAND, Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.BAMBOO,
            Blocks.BAMBOO_SAPLING, Blocks.KELP, Blocks.KELP_PLANT, Blocks.LILY_PAD, Blocks.BROWN_MUSHROOM,
            Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM,
            Blocks.SWEET_BERRY_BUSH, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Blocks.MOSS_CARPET,
            Blocks.COBWEB, Blocks.BEE_NEST, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.MAGMA_BLOCK,
            Blocks.MANGROVE_ROOTS, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.SEA_PICKLE, Blocks.SMALL_DRIPLEAF,
            Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.SPORE_BLOSSOM, Blocks.AZALEA,
            Blocks.FLOWERING_AZALEA, Blocks.PITCHER_PLANT, Blocks.TORCHFLOWER);

    static Kind kind(BlockState state) {
        if (state.isAir()) return Kind.NATURAL;
        Block block = state.getBlock();
        if (block instanceof LiquidBlock) return Kind.NATURAL;
        // Grass, ferns, snow layers, vines, seagrass: the "replace me" plants.
        if (state.is(BlockTags.REPLACEABLE)) return Kind.NATURAL;
        if (block instanceof LeavesBlock) {
            // Leaves a player placed stay where they are; tree leaves decay and are natural.
            return state.getValue(LeavesBlock.PERSISTENT) ? Kind.BUILT : Kind.NATURAL;
        }
        if (state.is(BlockTags.LOGS)) return Kind.LOG;
        if (block == Blocks.SANDSTONE || block == Blocks.RED_SANDSTONE) return Kind.SANDSTONE;
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER) || state.is(Tags.Blocks.ORES)
                || state.is(Tags.Blocks.GRAVELS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.CROPS) || EXTRA.contains(block)) {
            return Kind.NATURAL;
        }
        return Kind.BUILT;
    }

    /**
     * What land a {@link Kind#NATURAL} block is, for the bare-land test ({@link PlacedJudge}): rock, soil
     * or frozen water a player may build with. NONE for the rest of NATURAL (air, water, plants, tree
     * leaves, dripstone, mushroom blocks, bedrock...), which is never part of a copy. Only meant for blocks
     * {@link #kind} calls NATURAL.
     */
    enum Land { ROCK, SOIL, FROZEN, NONE }

    private static final Set<Block> ROCK_EXTRA = Set.of(Blocks.CALCITE, Blocks.MAGMA_BLOCK, Blocks.AMETHYST_BLOCK);
    private static final Set<Block> SOIL_EXTRA = Set.of(
            Blocks.GRAVEL, Blocks.SUSPICIOUS_GRAVEL, Blocks.CLAY, Blocks.DIRT_PATH, Blocks.FARMLAND);
    private static final Set<Block> FROZEN = Set.of(Blocks.ICE, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW);

    static Land land(BlockState state) {
        Block block = state.getBlock();
        // In the dirt tag, but only mangroves grow it.
        if (block == Blocks.MUDDY_MANGROVE_ROOTS) return Land.NONE;
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(Tags.Blocks.ORES) || ROCK_EXTRA.contains(block)) {
            return Land.ROCK;
        }
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Tags.Blocks.GRAVELS)
                || SOIL_EXTRA.contains(block)) {
            return Land.SOIL;
        }
        return FROZEN.contains(block) ? Land.FROZEN : Land.NONE;
    }

    /**
     * Counts as built ({@link #kind}), but the world makes it too: terracotta (mesas, not glazed) and
     * infested stone (mountains, strongholds). {@link PlacedJudge} leaves it out where the bare land is solid.
     */
    static boolean isWorldRockLookalike(BlockState state) {
        return state.is(BlockTags.TERRACOTTA) || state.getBlock() instanceof InfestedBlock;
    }

    /** Counts as built, but icebergs are made of it: packed and blue ice. */
    static boolean isWorldIceLookalike(BlockState state) {
        return state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE);
    }

    /** Leaves that grew on a tree. */
    static boolean isTreeLeaves(BlockState state) {
        return state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT);
    }

    /** Ground worth keeping as a foundation layer: solid, not a plant or water. */
    static boolean isSolidGround(BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock) && !state.is(BlockTags.REPLACEABLE)
                && !(state.getBlock() instanceof LeavesBlock) && state.canOcclude();
    }
}
