package com.alex.test.village.villagers.builder.blueprint;

import com.alex.test.village.building.BuildingRequirements;
import com.alex.test.village.building.MageTowerBuilding;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;

/**
 * Which building type a blueprint looks like, from the blocks in it: beds make a house,
 * anvils a forge, and so on. Only a hint for the desk ("Looks like a Kitchen") and for
 * its "Village needs" group. The real check stays the room scan once the building
 * stands and its token hangs, so a wrong guess costs nothing.
 *
 * <p>Mirrors the features in {@link BuildingRequirements}: the rarer, more telling
 * blocks are checked first, so a tavern with guest beds still reads as a tavern.
 * Common code: the server may use it for ordered builds later.
 */
public final class BlueprintRoomGuess {

    private BlueprintRoomGuess() {}

    /** The building type it most looks like, or null when nothing stands out. */
    @Nullable
    public static TokenType guess(Blueprint blueprint) {
        // Count per palette entry first: far fewer states than cells.
        int[] perState = new int[blueprint.palette().size()];
        for (int i = 0; i < blueprint.volume(); i++) {
            int p = blueprint.paletteIndex(i);
            if (p != Blueprint.VOID) perState[p]++;
        }
        Counts c = new Counts();
        for (int p = 0; p < perState.length; p++) {
            if (perState[p] > 0) c.add(blueprint.palette().get(p), perState[p]);
        }

        if (c.bookshelves >= MageTowerBuilding.REQUIRED_BOOKSHELVES && c.amethyst > 0
                && (c.enchantingTables > 0 || c.lecterns > 0)) {
            return TokenType.MAGE_TOWER;
        }
        if (c.targets > 0) return TokenType.ARCHERY_RANGE;
        if (c.anvils > 0) return TokenType.FORGE;
        if (c.lecterns > 0 && c.enchantingTables == 0) return TokenType.SCHOOL;
        if (c.enchantingTables > 0 || c.bookshelves >= 4) return TokenType.LIBRARY;
        if (c.musicBlocks > 0) return TokenType.TAVERN;
        if (c.grindstones > 0 && c.strippedLogs > 0) return TokenType.BUTCHERY;
        // Tall and narrow with a ladder: a lookout.
        if (c.ladders > 0 && blueprint.sizeY() >= WatchtowerBuilding.MIN_HEIGHT + 2
                && blueprint.sizeX() <= 9 && blueprint.sizeZ() <= 9) {
            return TokenType.WATCHTOWER;
        }
        if (c.beds > 0) {
            return c.beds <= 2 ? TokenType.TWO_BED_HOUSE
                    : c.beds <= 4 ? TokenType.FOUR_BED_HOUSE : TokenType.SIX_BED_HOUSE;
        }
        if (c.heat > 0) return TokenType.KITCHEN;
        if (c.containers >= 2) return TokenType.STORAGE;
        // A fenced pen with a gate and no door.
        if (c.gates > 0 && c.fences >= 8 && c.doors == 0) return TokenType.RANCH;
        return null;
    }

    /** "Kitchen", "2 Bed House"; "No match" when null. */
    public static String displayName(@Nullable TokenType type) {
        return type == null ? "No match" : BuildingRequirements.displayName(type);
    }

    /**
     * The simple class name of the building a token type registers as, matching the
     * names the desk's opening data lists, or null for a guard post (not a building).
     */
    @Nullable
    public static String buildingClassName(TokenType type) {
        return switch (type) {
            case TOWN_HALL -> "TownHallBuilding";
            case TWO_BED_HOUSE, FOUR_BED_HOUSE, SIX_BED_HOUSE -> "HouseBuilding";
            case STORAGE -> "StorageBuilding";
            case GUARD_POST -> null;
            case KITCHEN -> "KitchenBuilding";
            case BUTCHERY -> "ButcheryBuilding";
            case FORGE -> "ForgeBuilding";
            case MINE -> "MineBuilding";
            case RANCH -> "RanchBuilding";
            case TAVERN -> "TavernBuilding";
            case LIBRARY -> "LibraryBuilding";
            case SCHOOL -> "SchoolBuilding";
            case STALL -> "MerchantStallBuilding";
            case BARRACKS -> "BarracksBuilding";
            case ARCHERY_RANGE -> "ArcheryRangeBuilding";
            case WATCHTOWER -> "WatchtowerBuilding";
            case MAGE_TOWER -> "MageTowerBuilding";
        };
    }

    /** Telling blocks, counted once per object (bed foot, lower door half). */
    private static final class Counts {
        int beds, containers, heat, anvils, bookshelves, enchantingTables, lecterns, amethyst;
        int musicBlocks, targets, grindstones, strippedLogs, ladders, fences, gates, doors;

        void add(BlockState state, int n) {
            Block block = state.getBlock();
            if (block instanceof BedBlock) {
                if (state.getValue(BedBlock.PART) == BedPart.FOOT) beds += n;
            } else if (block instanceof ChestBlock || block instanceof BarrelBlock) {
                containers += n;
            } else if (block instanceof AbstractFurnaceBlock || block instanceof CampfireBlock) {
                heat += n;
            } else if (block instanceof AnvilBlock) {
                anvils += n;
            } else if (block == Blocks.BOOKSHELF) {
                bookshelves += n;
            } else if (block == Blocks.ENCHANTING_TABLE) {
                enchantingTables += n;
            } else if (block == Blocks.LECTERN) {
                lecterns += n;
            } else if (block == Blocks.AMETHYST_BLOCK || block == Blocks.AMETHYST_CLUSTER
                    || block == Blocks.LARGE_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD
                    || block == Blocks.SMALL_AMETHYST_BUD) {
                amethyst += n;
            } else if (block == Blocks.NOTE_BLOCK || block == Blocks.JUKEBOX) {
                musicBlocks += n;
            } else if (block == Blocks.TARGET) {
                targets += n;
            } else if (block == Blocks.GRINDSTONE) {
                grindstones += n;
            } else if (block instanceof LadderBlock) {
                ladders += n;
            } else if (block instanceof FenceGateBlock) {
                gates += n;
            } else if (block instanceof FenceBlock) {
                fences += n;
            } else if (block instanceof DoorBlock) {
                if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) doors += n;
            } else if (state.is(BlockTags.LOGS)
                    && BuiltInRegistries.BLOCK.getKey(block).getPath().startsWith("stripped_")) {
                strippedLogs += n;
            }
        }
    }
}
