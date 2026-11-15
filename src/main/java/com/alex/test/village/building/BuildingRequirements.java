package com.alex.test.village.building;

import com.alex.test.Tr;
import com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType;

import java.util.List;

/**
 * The shape and optional features understood by each structure token.
 *
 * <p>The room/enclosure shape is required for registration. Furniture and work
 * blocks are optional features: scanners still discover them so villagers can
 * use them, while an empty room can already register as its declared type.
 */
public final class BuildingRequirements {

    private BuildingRequirements() {}

    /** Human name of the structure a token declares. */
    public static String displayName(TokenType type) {
        return name(type).getString();
    }

    /** The structure's name as text each player's game translates (for chat and tooltips). */
    public static net.minecraft.network.chat.MutableComponent name(TokenType type) {
        return switch (type) {
            case TOWN_HALL -> Tr.c("building.dynamicvillages.town_hall", "Town Hall");
            case TWO_BED_HOUSE -> Tr.c("building.dynamicvillages.2_bed_house", "2 Bed House");
            case FOUR_BED_HOUSE -> Tr.c("building.dynamicvillages.4_bed_house", "4 Bed House");
            case SIX_BED_HOUSE -> Tr.c("building.dynamicvillages.6_bed_house", "6 Bed House");
            case STORAGE -> Tr.c("building.dynamicvillages.storage_room", "Storage Room");
            case GUARD_POST -> Tr.c("building.dynamicvillages.guard_post", "Guard Post");
            case KITCHEN -> Tr.c("building.dynamicvillages.kitchen", "Kitchen");
            case BUTCHERY -> Tr.c("building.dynamicvillages.butchery", "Butchery");
            case FORGE -> Tr.c("building.dynamicvillages.forge", "Forge");
            case MINE -> Tr.c("building.dynamicvillages.mine", "Mine");
            case RANCH -> Tr.c("building.dynamicvillages.ranch", "Ranch");
            case TAVERN -> Tr.c("building.dynamicvillages.tavern", "Tavern");
            case LIBRARY -> Tr.c("building.dynamicvillages.library", "Library");
            case SCHOOL -> Tr.c("building.dynamicvillages.school", "School");
            case STALL -> Tr.c("building.dynamicvillages.stall", "Merchant Stall");
            case BARRACKS -> Tr.c("building.dynamicvillages.barracks", "Barracks");
            case ARCHERY_RANGE -> Tr.c("building.dynamicvillages.archery_range", "Archery Range");
            case WATCHTOWER -> Tr.c("building.dynamicvillages.watchtower", "Watchtower");
            case MAGE_TOWER -> Tr.c("building.dynamicvillages.mage_tower", "Mage Tower");
        };
    }

    /**
     * Furniture and blocks the building recognises, one phrase per line.
     */
    public static List<String> acceptedFeatures(TokenType type) {
        return switch (type) {
            case TOWN_HALL -> List.of(
                    "beds for the Mayor");
            case TWO_BED_HOUSE, FOUR_BED_HOUSE, SIX_BED_HOUSE -> List.of(
                    "beds for up to " + type.housingCapacity() + " villagers");
            case STORAGE -> List.of(
                    "chests and barrels for village storage",
                    "crafting tables for crafting (optional)",
                    "a furnace where the miner burns logs into charcoal for torches (optional)",
                    "a composter where farmers turn spare seeds and plant scraps into bone meal (optional)");
            case KITCHEN -> List.of(
                    "furnaces, smokers, blast furnaces and campfires as heat sources",
                    "crafting tables for recipes");
            case BUTCHERY -> List.of(
                    "a door: the butcher leads the animals in through it",
                    "a stripped log as a chopping block, with open floor beside it for the animal",
                    "a grindstone (optional): once a day the butcher sharpens its sword there, "
                            + "so the sword wears out slower");
            case FORGE -> List.of(
                    "anvils as work stations",
                    "furnaces for smelting");
            case MINE -> List.of(
                    "open ground in front of the marked block for a miner to stand on",
                    "side branches: set 0-12 blocks under Mayor > Settings > Professions > Miner; nearby mines "
                            + "may shorten them (optional)");
            case TAVERN -> List.of(
                    "note blocks and jukeboxes as performance stages");
            case LIBRARY -> List.of(
                    "bookshelves",
                    "enchanting tables as work stations");
            case SCHOOL -> List.of("lecterns as teaching stations, one per teacher",
                    "chairs facing a lectern as desks (optional)");
            case STALL -> List.of("a walled room behind a door, or open ground in front of the token",
                    "barrels as trading counters (optional)");
            case BARRACKS -> List.of(
                    "armor stands as training posts, one guard each",
                    "beds for up to " + BarracksBuilding.CAPACITY
                            + " defenders (optional; one is kept for the captain)",
                    "a Captain token to give it a captain (optional)");
            case RANCH -> List.of(
                    "walkable pen tiles; larger pens support more animals");
            case ARCHERY_RANGE -> List.of(
                    "a fenced yard with a gate, or a room with walls, a roof and a door",
                    "target blocks, each with " + (int) ArcheryRangeBuilding.MIN_LANE + " to "
                            + (int) ArcheryRangeBuilding.MAX_LANE
                            + " blocks of open floor in front for an archer to stand on",
                    "a fletching table, where archers make " + ArcheryRangeBuilding.ARROWS_PER_FLETCH
                            + " arrows at a time (optional)",
                    "hay bales behind the targets: arrows that land in hay never break (optional)");
            case GUARD_POST -> List.of("a standable patrol position");
            case MAGE_TOWER -> List.of(
                    "an enchanting table or a lectern, where mages study",
                    "at least " + MageTowerBuilding.REQUIRED_BOOKSHELVES + " bookshelves",
                    "an amethyst block, cluster or bud as a focus crystal",
                    "more bookshelves, up to " + MageTowerBuilding.MAX_USEFUL_BOOKSHELVES
                            + ", so mages study faster (optional)",
                    "beds, where up to " + MageTowerBuilding.CAPACITY + " mages sleep (optional)");
            case WATCHTOWER -> List.of(
                    "a floor at least " + WatchtowerBuilding.MIN_HEIGHT
                            + " blocks above the ground around it, with the token near its middle",
                    "a ladder or stairs villagers can climb up to it",
                    "a bell up there: the lookout rings it if raiders come or monsters hurt villagers (optional)",
                    "fences around the edge, so the lookout can't be knocked off (optional)",
                    "more height: every 2 blocks lets the lookout see 1 block farther, up to "
                            + WatchtowerBuilding.MAX_SPOT_BONUS + " (optional)");
        };
    }

    /** Compatibility name for callers that still refer to room contents. */
    public static List<String> contents(TokenType type) {
        return acceptedFeatures(type);
    }

    /** What kind of space the structure has to be, before any furniture counts. */
    public static String shape(TokenType type) {
        return switch (type) {
            case RANCH ->
                    "a fully fenced pen with a gate; hang the token beside the gate";
            case ARCHERY_RANGE ->
                    "a fully fenced yard with a gate (outdoor), or a walled, roofed room with a door "
                            + "(indoor); hang the token beside the gate or the door. "
                            + "A yard's edge must be fence, wall or 2 blocks tall: villagers step over a single block";
            // The one token that marks a block rather than a space.
            case MINE ->
                    "no room at all — hang the token on any solid block, facing "
                            + "the way you want the shaft dug";
            case GUARD_POST -> "any standable block out in the open";
            case BUTCHERY -> "a walled room with a door; hang the token beside the door";
            case WATCHTOWER ->
                    "a high platform reached by stairs or a ladder; hang the token on the platform, "
                            + "on a fence post, a wall or flat on the floor";
            default ->
                    "a walled, roofed room entered through a door; "
                            + "hang the token beside that door";
        };
    }

    /** The whole briefing, ready to print one line at a time. */
    public static List<String> briefing(TokenType type) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("Structure: " + shape(type) + ".");
        List<String> features = acceptedFeatures(type);
        if (features.size() == 1) {
            lines.add("Accepted feature: " + features.get(0) + ".");
        } else {
            lines.add("Accepted features:");
            for (String item : features) lines.add("  - " + item);
        }
        return List.copyOf(lines);
    }
}
