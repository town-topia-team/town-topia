package com.alex.test.village.villagers.builder.copy;

/**
 * What {@link PlacedJudge} can see of the world: blocks sorted into the few groups the
 * copy's land test cares about, and the world generator's bare land.
 *
 * <p>Kept free of Minecraft so the judge runs in plain unit tests; {@link LevelLandView}
 * is the real world behind it.
 */
public interface LandView {

    /** What a block is, for the land test ({@link NaturalBlocks} sorts them). */
    enum Group {
        /** Only builds have it: planks, bricks, glass, cobblestone... */
        BUILT,
        /** Never part of a copy: air, water, plants, tree leaves, dripstone, mushroom blocks, bedrock... */
        NEVER,
        /** A log: a tree or a build, by today's rule. */
        LOG,
        /** Plain sandstone: desert rock or a wall. */
        SANDSTONE,
        /** Stone, granite, diorite, andesite, tuff, deepslate, calcite, nether stone, ores... */
        ROCK,
        /** Dirt, grass, sand, gravel, clay, paths, farmland. */
        SOIL,
        /** Ice, snow block, powder snow. */
        FROZEN,
        /** Counts as building today, but the world makes it too: terracotta (mesas), infested stone. */
        LOOKALIKE,
        /** Counts as building today, but icebergs are made of it: packed ice, blue ice. */
        HARD_ICE,
        /** In a chunk that is not loaded. */
        UNLOADED;

        /** A land block: above the lowest floor the bare-land test decides it. */
        public boolean isLand() {
            return this == ROCK || this == SOIL || this == FROZEN || this == SANDSTONE;
        }

        /** Building today, and left out only where the bare land shows the world made it. */
        public boolean isLookalike() {
            return this == LOOKALIKE || this == HARD_ICE;
        }
    }

    /** What the world generator's terrain alone puts at a spot, before surface layers, caves, trees and players. */
    enum Bare { SOLID, AIR, FLUID, UNKNOWN }

    Group group(int x, int y, int z);

    /** Solid ground, not air, water or a plant. Asked of {@link Group#NEVER} blocks under soil. */
    boolean solid(int x, int y, int z);

    Bare bare(int x, int y, int z);

    /** The copy's answer without the land test (today's rule): true for a block a player placed. */
    boolean builtToday(int x, int y, int z);

    /** Inside the rooms and the walls around them (the copy's footprint). */
    boolean insideFootprint(int x, int z);

    /** The highest row the copy may take: the "building above" look stops here. */
    int topY();

    /** A player or a Builder placed this block, as remembered ({@code PlacedLand}). */
    default boolean remembered(int x, int y, int z) {
        return false;
    }
}
