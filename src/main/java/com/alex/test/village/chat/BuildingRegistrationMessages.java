package com.alex.test.village.chat;

import com.alex.test.Tr;
import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.BuildingRequirements;
import com.alex.test.village.building.BuildingSpace;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.stats.CrowdingBalance;
import com.alex.test.village.villagers.miner.OreHeights;
import com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Player-facing chat lines for a successfully registered building.
 *
 * <p>Every line is a translatable component ({@link Tr#c}), so each player
 * reads it in their own language; numbers and names go in as arguments.
 */
public final class BuildingRegistrationMessages {

    private BuildingRegistrationMessages() {}

    public static List<Component> forType(TokenType type) {
        List<Component> messages = new ArrayList<>();
        messages.add(Tr.c("chat.dynamicvillages.building.registered", "[Village] %s registered.",
                BuildingRequirements.name(type)).withStyle(ChatFormatting.GREEN));
        messages.addAll(featureLines(type));
        if (type == TokenType.STORAGE) {
            messages.add(Tr.c("chat.dynamicvillages.building.storage_manage",
                    "Right-click the Storage token or open a storage chest or barrel to manage this room.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return List.copyOf(messages);
    }

    /**
     * The only word on space when a room registers: that its beds would crowd
     * it once all are taken. Null when there is room for them all, so roomy
     * houses and workshops say nothing.
     */
    @Nullable
    public static Component forSpace(Building building) {
        int beds = building.livingBeds();
        BuildingSpace.Result space = building.getSpace();
        int fits = space.fits();
        if (fits == 0 || beds <= fits) return null;
        return Tr.c("chat.dynamicvillages.building.crowded", "[Village] %1$s %2$s",
                        CrowdingBalance.bedsLine(beds), CrowdingBalance.fixLine(space.lowTiles()))
                .withStyle(ChatFormatting.GOLD);
    }

    /**
     * A School: how many classes (one per lectern) and places it has, and one
     * short fix-it line when something is missing. Kept short for small screens.
     */
    public static List<Component> forSchool(net.minecraft.server.level.ServerLevel level,
                                            @Nullable com.alex.test.village.NavGraph graph,
                                            com.alex.test.village.building.SchoolBuilding school) {
        List<Component> messages = new ArrayList<>();
        messages.add(Tr.c("chat.dynamicvillages.building.school", "[Village] School registered.")
                .withStyle(ChatFormatting.GREEN));
        List<net.minecraft.core.BlockPos> lecterns = school.getLecterns();
        if (lecterns.isEmpty()) {
            messages.add(Tr.c("chat.dynamicvillages.building.school.no_lectern",
                    "Put a lectern inside: one for each teacher.").withStyle(ChatFormatting.GOLD));
            return List.copyOf(messages);
        }
        int desks = 0, places = 0, boxedIn = 0;
        if (graph != null) {
            for (net.minecraft.core.BlockPos lectern : lecterns) {
                List<net.minecraft.core.BlockPos> others = new ArrayList<>(lecterns);
                others.remove(lectern);
                var layout = com.alex.test.village.villagers.teacher.SchoolSeats
                        .layout(level, graph, school, lectern, others);
                if (layout.teacherStand() == null) boxedIn++;
                for (var seat : layout.seats()) {
                    if (seat.overflow()) continue;
                    if (seat.chair() != null) desks++;
                    else places++;
                }
            }
        }
        int classes = lecterns.size();
        messages.add(Tr.c("chat.dynamicvillages.building.school.places",
                "Classes: %1$s, desks: %2$s, standing places: %3$s.", classes, desks, places)
                .withStyle(ChatFormatting.GRAY));
        if (boxedIn > 0) {
            messages.add((boxedIn == 1 && classes == 1
                    ? Tr.c("chat.dynamicvillages.building.school.boxed_in_one",
                            "A teacher needs a free floor tile beside the lectern.")
                    : Tr.c("chat.dynamicvillages.building.school.boxed_in",
                            "A teacher needs a free floor tile beside each lectern."))
                    .withStyle(ChatFormatting.GOLD));
        }
        if (desks == 0) {
            messages.add(Tr.c("chat.dynamicvillages.building.school.chairs",
                    "Chairs facing a lectern become desks.").withStyle(ChatFormatting.GRAY));
        }
        return List.copyOf(messages);
    }

    /**
     * The Mine briefing, kept to two lines (three when deep): its height and
     * ores, then what to keep clear and where its settings are. Tunnel lengths,
     * ore heights and the Mayor's setting are left to the Mine screen and the guide.
     *
     * @param shaftY height of the shaft's floor, or null when the mine could not be found
     */
    public static List<Component> forMine(@Nullable Integer shaftY) {
        List<Component> messages = new ArrayList<>();
        messages.add((shaftY == null
                ? Tr.c("chat.dynamicvillages.building.mine", "[Village] Mine registered.")
                : Tr.c("chat.dynamicvillages.building.mine.at", "[Village] Mine registered at Y %1$s: mostly %2$s.",
                        shaftY, oreList(OreHeights.mainOres(shaftY)).withStyle(ChatFormatting.WHITE)))
                .withStyle(ChatFormatting.GREEN));
        if (shaftY != null && shaftY < 0) {
            messages.add(Tr.c("chat.dynamicvillages.building.mine.low_coal",
                    "Little coal this deep: keep coal or charcoal in storage for torches.")
                    .withStyle(ChatFormatting.GOLD));
        }
        messages.add(Tr.c("chat.dynamicvillages.building.mine.next",
                "Keep the ground in front of the token open. Right-click it to set the tunnels or pause the mine.")
                .withStyle(ChatFormatting.GRAY));
        return List.copyOf(messages);
    }

    /** "redstone, diamond and gold", each name in the player's own language. */
    private static MutableComponent oreList(List<OreHeights.Ore> ores) {
        MutableComponent list = Component.empty();
        for (int i = 0; i < ores.size(); i++) {
            if (i > 0) {
                list.append(i == ores.size() - 1
                        ? Tr.c("chat.dynamicvillages.list.and", " and ")
                        : Component.literal(", "));
            }
            list.append(oreName(ores.get(i)));
        }
        return list;
    }

    private static MutableComponent oreName(OreHeights.Ore ore) {
        return switch (ore) {
            case COAL -> Tr.c("ore.dynamicvillages.coal", "coal");
            case COPPER -> Tr.c("ore.dynamicvillages.copper", "copper");
            case IRON -> Tr.c("ore.dynamicvillages.iron", "iron");
            case LAPIS -> Tr.c("ore.dynamicvillages.lapis", "lapis");
            case REDSTONE -> Tr.c("ore.dynamicvillages.redstone", "redstone");
            case GOLD -> Tr.c("ore.dynamicvillages.gold", "gold");
            case DIAMOND -> Tr.c("ore.dynamicvillages.diamond", "diamond");
        };
    }

    /**
     * A registered Merchant Stall says which of its two styles it took, and
     * that the other exists. Neither is the better one, so neither is worded
     * as a fix for the other.
     */
    public static List<Component> forStall(boolean openAir, int walkableTiles) {
        List<Component> messages = new ArrayList<>();
        if (openAir) {
            messages.add(Tr.c("chat.dynamicvillages.building.stall.open",
                    "[Village] Merchant Stall set up as an open-air stall.").withStyle(ChatFormatting.GREEN));
            int size = MerchantStallBuilding.OPEN_AREA_SIZE;
            messages.add(Tr.c("chat.dynamicvillages.building.stall.open.desc",
                    "The traveling merchant will stroll the %1$sx%1$s area in front of the token (%2$s walkable "
                            + "tiles), and your tradesman sells the village's surplus to it on its own.",
                    size, walkableTiles).withStyle(ChatFormatting.GRAY));
            messages.add(Tr.c("chat.dynamicvillages.building.stall.open.barrel",
                    "Place a barrel there to get a sell box: village goods you put in it are sold to the "
                            + "merchant as well, and the emeralds come back to the barrel.")
                    .withStyle(ChatFormatting.GRAY));
            messages.add(Tr.c("chat.dynamicvillages.building.stall.open.styles",
                    "Stalls come in two styles: open-air like this one, or a building when the token hangs "
                            + "beside the door of a walled room.")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            messages.add(Tr.c("chat.dynamicvillages.building.stall.building",
                    "[Village] Merchant Stall registered as a building.").withStyle(ChatFormatting.GREEN));
            messages.add(Tr.c("chat.dynamicvillages.building.stall.building.desc",
                    "The traveling merchant will trade at its door, and your tradesman sells the village's "
                            + "surplus to it on its own.").withStyle(ChatFormatting.GRAY));
            messages.add(Tr.c("chat.dynamicvillages.building.stall.building.barrel",
                    "The barrel inside is also a sell box: village goods you put in it are sold to the "
                            + "merchant as well, and the emeralds come back to the barrel.")
                    .withStyle(ChatFormatting.GRAY));
            messages.add(Tr.c("chat.dynamicvillages.building.stall.building.styles",
                    "Stalls come in two styles: a building like this one, or open-air when the token hangs on "
                            + "its own, on a fence post or flat on a block.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return List.copyOf(messages);
    }

    /**
     * A ready Archery Range, in plain words: how many targets work (the green
     * sparkles show where archers stand, red the targets nobody can shoot
     * at), the fletching table, and a tip when the village has no archer.
     */
    public static List<Component> forRange(ArcheryRangeBuilding range, boolean noArcher) {
        List<Component> messages = new ArrayList<>();
        messages.add((range.isIndoor()
                ? Tr.c("chat.dynamicvillages.building.range.indoor", "[Village] Your indoor archery range is ready!")
                : Tr.c("chat.dynamicvillages.building.range", "[Village] Your archery range is ready!"))
                .withStyle(ChatFormatting.GREEN));
        messages.addAll(rangeStatus(range));
        if (noArcher) {
            messages.add(Tr.c("chat.dynamicvillages.building.range.no_archer",
                    "Nobody will practise here yet. You need an archer: buy an Archer token from the Mayor.")
                    .withStyle(ChatFormatting.GOLD));
        }
        return List.copyOf(messages);
    }

    /** One line after a target or fletching table came or went in a registered range. */
    public static Component forRangeUpdate(ArcheryRangeBuilding range) {
        int ready = range.getLanes().size();
        int blocked = range.targetsWithoutLane();
        MutableComponent line = Tr.c("chat.dynamicvillages.building.range.update",
                "[Village] Archery Range: targets ready: %s", ready);
        if (blocked > 0) {
            line.append(Tr.c("chat.dynamicvillages.building.range.update.blocked",
                    ", with nowhere for an archer to stand: %s (red sparkles)", blocked));
        }
        line.append(range.hasFletchingTable()
                ? Tr.c("chat.dynamicvillages.building.range.update.table", ", fletching table found.")
                : Tr.c("chat.dynamicvillages.building.range.update.no_table", ", no fletching table."));
        return line.withStyle(blocked > 0 || ready == 0 ? ChatFormatting.GOLD : ChatFormatting.GRAY);
    }

    private static List<Component> rangeStatus(ArcheryRangeBuilding range) {
        List<Component> messages = new ArrayList<>();
        int ready = range.getLanes().size();
        int blocked = range.targetsWithoutLane();
        int minLane = (int) ArcheryRangeBuilding.MIN_LANE, maxLane = (int) ArcheryRangeBuilding.MAX_LANE;
        if (range.getTargets().isEmpty()) {
            messages.add(Tr.c("chat.dynamicvillages.building.range.no_targets",
                    "No targets yet. Put target blocks inside, with %1$s to %2$s blocks of open floor in front of each.",
                    minLane, maxLane).withStyle(ChatFormatting.GOLD));
        } else if (ready > 0) {
            messages.add((ready == 1
                    ? Tr.c("chat.dynamicvillages.building.range.ready_one",
                            "1 target is ready. Archers stand on the green sparkles to shoot.")
                    : Tr.c("chat.dynamicvillages.building.range.ready",
                            "%s targets are ready. Archers stand on the green sparkles to shoot.", ready))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (blocked > 0) {
            messages.add((blocked == 1
                    ? Tr.c("chat.dynamicvillages.building.range.blocked_one",
                            "1 target (red sparkles) has nowhere for an archer to stand. Give each one %1$s to %2$s "
                                    + "blocks of open floor, with a clear view, and keep targets at least 2 blocks apart.",
                            minLane, maxLane)
                    : Tr.c("chat.dynamicvillages.building.range.blocked",
                            "%3$s targets (red sparkles) have nowhere for an archer to stand. Give each one %1$s to %2$s "
                                    + "blocks of open floor, with a clear view, and keep targets at least 2 blocks apart.",
                            minLane, maxLane, blocked))
                    .withStyle(ChatFormatting.GOLD));
        }
        messages.add((range.hasFletchingTable()
                ? Tr.c("chat.dynamicvillages.building.range.table",
                        "Fletching table found: archers make arrows here, %s at a time.",
                        ArcheryRangeBuilding.ARROWS_PER_FLETCH)
                : Tr.c("chat.dynamicvillages.building.range.no_table",
                        "No fletching table. Add one and archers make more arrows here (%s at a time instead of 4).",
                        ArcheryRangeBuilding.ARROWS_PER_FLETCH))
                .withStyle(ChatFormatting.GRAY));
        return messages;
    }

    /**
     * A ready Watchtower, in plain words: its height and what that gives, the
     * bell and the railing, and tips when the token leaves part of the floor
     * out or the village has no archer. The green sparkles show the floor.
     */
    public static List<Component> forTower(WatchtowerBuilding tower, boolean noArcher, boolean floorCutOff) {
        List<Component> messages = new ArrayList<>();
        messages.add(Tr.c("chat.dynamicvillages.building.tower",
                "[Village] Your watchtower is ready! An archer will keep watch up there.")
                .withStyle(ChatFormatting.GREEN));
        messages.add(towerHeightLine(tower).append(" ")
                .append(Tr.c("chat.dynamicvillages.building.tower.floor", "The green sparkles show the floor it uses."))
                .withStyle(ChatFormatting.GRAY));
        messages.add((tower.getBell() != null
                ? Tr.c("chat.dynamicvillages.building.tower.bell",
                        "Bell found: the lookout rings it if raiders come or monsters hurt villagers.")
                : Tr.c("chat.dynamicvillages.building.tower.no_bell",
                        "No bell. Put a bell up there if you want the lookout to sound the alarm."))
                .withStyle(ChatFormatting.GRAY));
        if (tower.getUnrailedEdges() > 0) {
            messages.add(railingLine(tower).withStyle(ChatFormatting.GOLD));
        }
        if (floorCutOff) {
            messages.add(Tr.c("chat.dynamicvillages.building.tower.cut_off",
                    "Only the floor within %s blocks of the token counts. Move the token to the middle of the "
                            + "floor to use all of it.", WatchtowerBuilding.PLATFORM_RADIUS)
                    .withStyle(ChatFormatting.GOLD));
        }
        if (noArcher) {
            messages.add(Tr.c("chat.dynamicvillages.building.tower.no_archer",
                    "Nobody can keep watch yet. You need an archer: buy an Archer token from the Mayor.")
                    .withStyle(ChatFormatting.GOLD));
        }
        return List.copyOf(messages);
    }

    /** One line after the tower's bell, railing or height changed. */
    public static Component forTowerUpdate(WatchtowerBuilding tower) {
        MutableComponent line = Tr.c("chat.dynamicvillages.building.tower.update", "[Village] Watchtower: %s",
                towerHeightLine(tower));
        line.append(" ").append(tower.getBell() != null
                ? Tr.c("chat.dynamicvillages.building.tower.update.bell", "Bell found.")
                : Tr.c("chat.dynamicvillages.building.tower.update.no_bell", "No bell."));
        if (tower.getUnrailedEdges() > 0) line.append(" ").append(railingLine(tower));
        return line.withStyle(tower.isHighEnough() && tower.getUnrailedEdges() == 0
                ? ChatFormatting.GRAY : ChatFormatting.GOLD);
    }

    private static MutableComponent towerHeightLine(WatchtowerBuilding tower) {
        int height = tower.getHeightAboveGround();
        if (!tower.isHighEnough()) {
            return height == 1
                    ? Tr.c("chat.dynamicvillages.building.tower.too_low_one",
                            "It is only 1 block high now, too low for a lookout (it needs %s).",
                            WatchtowerBuilding.MIN_HEIGHT)
                    : Tr.c("chat.dynamicvillages.building.tower.too_low",
                            "It is only %1$s blocks high now, too low for a lookout (it needs %2$s).",
                            height, WatchtowerBuilding.MIN_HEIGHT);
        }
        return tower.spotBonus() == 1
                ? Tr.c("chat.dynamicvillages.building.tower.height_one",
                        "It is %s blocks high, so the lookout sees 1 block farther than normal.", height)
                : Tr.c("chat.dynamicvillages.building.tower.height",
                        "It is %1$s blocks high, so the lookout sees %2$s blocks farther than normal.",
                        height, tower.spotBonus());
    }

    private static MutableComponent railingLine(WatchtowerBuilding tower) {
        int n = tower.getUnrailedEdges();
        return n == 1
                ? Tr.c("chat.dynamicvillages.building.tower.railing_one",
                        "1 spot at the edge has no railing, so the lookout could get knocked off. Put fences around the edge.")
                : Tr.c("chat.dynamicvillages.building.tower.railing",
                        "%s spots at the edge have no railing, so the lookout could get knocked off. Put fences around the edge.", n);
    }

    /** Follows a Merchant Stall that did not register, so the player knows both ways to place it. */
    public static Component stallPlacementHelp() {
        return Tr.c("chat.dynamicvillages.building.stall.help",
                "A Stall token can be placed two ways: beside the door of a walled room as a building, or on its "
                        + "own (on a fence post, or flat on a block) with open ground in front of it.")
                .withStyle(ChatFormatting.GRAY);
    }

    /** Shared with the founding announcement so Town Hall uses the same wording. */
    public static List<Component> featureLines(TokenType type) {
        List<Component> messages = new ArrayList<>();
        messages.add(Tr.c("chat.dynamicvillages.building.uses", "%s uses:", BuildingRequirements.name(type))
                .withStyle(ChatFormatting.GRAY));
        for (String feature : BuildingRequirements.acceptedFeatures(type)) {
            messages.add(Component.literal("  - " + feature).withStyle(ChatFormatting.GRAY));
        }
        return List.copyOf(messages);
    }
}
