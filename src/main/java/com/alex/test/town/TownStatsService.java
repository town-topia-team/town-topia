package com.alex.test.town;

import com.alex.test.DynamicVillages;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.stats.DietMemory;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.BuildingRequirements;
import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.TavernBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.villagers.guard.behavior.GuardPostRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Produces an immutable town-stat snapshot on demand. No world scan is needed:
 * both villagers and buildings come from the manager's cached registries.
 */
public final class TownStatsService {

    private TownStatsService() {}

    public static MayorStatsSnapshot snapshot(VillageManager manager) {
        return snapshot(manager, null);
    }

    /** The snapshot, with shop prices counting the tokens {@code viewer} carries (MayorShop.owned). */
    public static MayorStatsSnapshot snapshot(VillageManager manager,
                                              @javax.annotation.Nullable net.minecraft.world.entity.player.Player viewer) {
        if (manager == null) return MayorStatsSnapshot.EMPTY;

        Map<String, BuildingStatsEntry> grouped = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, List<BuildingInstanceEntry>> instances = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<java.util.UUID, Crowding> crowding = crowding(manager);
        Map<java.util.UUID, String> names = new java.util.HashMap<>();
        for (VillagerModel model : manager.getVillagers()) names.put(model.npcId, villagerName(model));
        ServerLevel level = manager.getLevel();
        int buildingCount = 0;
        for (Building building : manager.getBuildings()) {
            buildingCount++;
            TokenType tokenType = tokenType(building);
            String name = tokenType == null
                    ? displayName(building) : BuildingRequirements.displayName(tokenType);
            String icon = buildingIcon(building);
            List<String> acceptedFeatures = tokenType == null
                    ? List.of() : BuildingRequirements.acceptedFeatures(tokenType);
            Crowding crowded = crowding.get(building.getItemFrameId());
            instances.computeIfAbsent(name, key -> new ArrayList<>())
                    .add(instance(manager, level, building, name, icon, crowded, names));
            grouped.compute(name, (key, old) -> new BuildingStatsEntry(
                    name,
                    old == null ? 1 : old.count() + 1,
                    (old == null ? 0 : old.operationalCount()) + (building.isValid() ? 1 : 0),
                    (old == null ? 0 : old.roomTiles()) + building.getNavMeshBlocks().size(),
                    (old == null ? 0 : old.functionalBlocks()) + building.getFunctionalBlocks().size(),
                    icon,
                    acceptedFeatures));
        }

        if (level != null) {
            List<BlockPos> posts = GuardPostRegistry.get(level).validPostPositions(level, manager);
            int guardPostCount = posts.size();
            if (guardPostCount > 0) {
                buildingCount += guardPostCount;
                grouped.put("Guard Post", new BuildingStatsEntry(
                        "Guard Post", guardPostCount, guardPostCount, 0, 0,
                        DynamicVillages.MODID + ":guard_post",
                        BuildingRequirements.acceptedFeatures(TokenType.GUARD_POST)));
                List<BuildingInstanceEntry> postEntries = new ArrayList<>();
                for (BlockPos post : posts) {
                    postEntries.add(new BuildingInstanceEntry(net.minecraft.Util.NIL_UUID, "Guard Post",
                            DynamicVillages.MODID + ":guard_post", BuildingInstanceEntry.Category.OTHER, post,
                            BuildingInstanceEntry.Status.OPERATIONAL,
                            0, 0, 0, 0, false, 0, 0, List.of(), -1, false, "", List.of()));
                }
                instances.put("Guard Post", postEntries);
            }
        }
        // Same order as the groups; inside a group by position, so the numbering stays put.
        List<BuildingInstanceEntry> buildingInstances = new ArrayList<>();
        for (List<BuildingInstanceEntry> group : instances.values()) {
            group.sort(Comparator.comparingInt((BuildingInstanceEntry entry) -> entry.door().getX())
                    .thenComparingInt(entry -> entry.door().getZ())
                    .thenComparingInt(entry -> entry.door().getY()));
            buildingInstances.addAll(group);
        }

        List<VillagerStatsEntry> villagers = new ArrayList<>();
        for (VillagerModel model : manager.getVillagers()) {
            var npc = model.puppet();
            if (npc != null && (!npc.isAlive() || npc.isRemoved())) continue;
            Activity current = model.brain().currentActivity();
            String activity = current == null ? "Idle" : titleCase(current.name());
            Profession profession = npc != null ? npc.getProfession() : null;
            String professionName = profession != null ? profession.displayName() : "Unknown";
            String name = names.get(model.npcId);
            VillagerStats needs = model.brain().needs();
            BlockPos bed = manager.getAssignedBed(model.npcId);
            String home = homeForBed(manager.getAssignedBedBuilding(model.npcId));
            String bedDescription = bed == null ? "Not claimed" : position(bed);

            villagers.add(new VillagerStatsEntry(
                    model.npcId,
                    name,
                    professionName,
                    activity,
                    npc != null ? "Active" : "Away",
                    home,
                    bedDescription,
                    com.alex.test.village.villagers.child.ChildProfession.workplaceNote(npc, workplace(manager, profession)),
                    npc != null && npc.isAlive()
                            ? Math.round(npc.getHealth() / npc.getMaxHealth() * 100.0F) : -1,
                    npc != null ? Math.round(needs.hunger) : -1,
                    Math.round(needs.wellbeing),
                    Math.round(model.brain().skills().intelligence()),
                    professionIcon(profession),
                    dietSummary(needs.diet)));
        }
        villagers.sort(Comparator
                .comparing(VillagerStatsEntry::profession, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(VillagerStatsEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(VillagerStatsEntry::id));

        return new MayorStatsSnapshot(
                buildingCount,
                List.copyOf(grouped.values()),
                List.copyOf(buildingInstances),
                villagers.size(),
                List.copyOf(villagers),
                MayorShop.villageSize(manager),
                MayorShop.offers(manager, viewer),
                manager.breeding().birthsEnabled(),
                manager.minerSideBranchDistance(),
                manager.rules().playerHurtBell().ordinal(),
                manager.rules().towerAlarmEndsWhenClear(),
                manager.rules().defenderTeams());
    }

    /**
     * Every crowded building: a house where more villagers have beds than its
     * space holds, or any building somebody felt crowded in during the last
     * day. A barracks is judged only by who is inside: its defenders sleep in
     * shifts, so its beds are never all slept in at once.
     */
    private static Map<java.util.UUID, Crowding> crowding(VillageManager manager) {
        Map<java.util.UUID, Crowding> warnings = new java.util.HashMap<>();
        for (Building building : manager.getBuildings()) {
            if (!(building instanceof HouseBuilding) || !building.countsCrowding()) continue;
            int living = manager.buildingBedClaims(building.getItemFrameId()).size();
            int fits = building.getSpace().fits();
            if (fits > 0 && living > fits) {
                warnings.put(building.getItemFrameId(), new Crowding(living, true));
            }
        }
        ServerLevel level = manager.getLevel();
        if (level == null) return warnings;
        for (var incident : manager.crowding().recentIncidents(manager, level.getGameTime())) {
            warnings.putIfAbsent(incident.buildingId(), new Crowding(incident.bodies(), false));
        }
        return warnings;
    }

    /** A crowded building: {@code living} when too many live there, else too many were inside at once. */
    private record Crowding(int bodies, boolean living) {}

    /** One building on its own: where it is, whether it works, and who sleeps in it. */
    private static BuildingInstanceEntry instance(VillageManager manager, ServerLevel level, Building building,
                                                  String type, String icon, Crowding crowded,
                                                  Map<java.util.UUID, String> names) {
        BuildingInstanceEntry.Status status = !building.isShapeValid() ? BuildingInstanceEntry.Status.NO_ROOM
                : !building.isAccessValid() ? BuildingInstanceEntry.Status.UNREACHABLE
                : BuildingInstanceEntry.Status.OPERATIONAL;
        int capacity = 0;
        int bedsFound = 0;
        List<BuildingInstanceEntry.Resident> residents = new ArrayList<>();
        if (building instanceof HouseBuilding house) {
            capacity = house.getCapacity();
            List<BlockPos> beds = house.getAllBeds();
            bedsFound = beds.size();
            manager.houseBedClaims(house.getItemFrameId()).forEach((villager, bed) ->
                    residents.add(new BuildingInstanceEntry.Resident(villager,
                            names.getOrDefault(villager, "Villager"), bed, !bedPresent(level, beds, bed))));
            residents.sort(Comparator.comparing(BuildingInstanceEntry.Resident::name,
                    String.CASE_INSENSITIVE_ORDER));
        } else if (building instanceof BarracksBuilding barracks) {
            capacity = BarracksBuilding.CAPACITY;
            List<BlockPos> beds = barracks.getBeds();
            bedsFound = beds.size();
            manager.buildingBedClaims(barracks.getItemFrameId()).forEach((villager, bed) ->
                    residents.add(new BuildingInstanceEntry.Resident(villager,
                            names.getOrDefault(villager, "Villager"), bed, !bedPresent(level, beds, bed))));
            residents.sort(Comparator.comparing(BuildingInstanceEntry.Resident::name,
                    String.CASE_INSENSITIVE_ORDER));
        } else if (building instanceof com.alex.test.village.building.MageTowerBuilding tower) {
            capacity = com.alex.test.village.building.MageTowerBuilding.CAPACITY;
            List<BlockPos> beds = tower.getBeds();
            bedsFound = beds.size();
            manager.buildingBedClaims(tower.getItemFrameId()).forEach((villager, bed) ->
                    residents.add(new BuildingInstanceEntry.Resident(villager,
                            names.getOrDefault(villager, "Villager"), bed, !bedPresent(level, beds, bed))));
            residents.sort(Comparator.comparing(BuildingInstanceEntry.Resident::name,
                    String.CASE_INSENSITIVE_ORDER));
        }
        return new BuildingInstanceEntry(
                building.getItemFrameId(),
                type,
                icon,
                category(building),
                building.getDoorPos(),
                status,
                building.getNavMeshBlocks().size(),
                building.getFunctionalBlocks().size(),
                building.getSpace().lowTiles(),
                crowded == null ? 0 : crowded.bodies(),
                crowded != null && crowded.living(),
                capacity,
                bedsFound,
                residents,
                building instanceof com.alex.test.village.building.MerchantStallBuilding stall
                        && level != null && stall == com.alex.test.village.villagers.tradesman.SellBox.stall(manager)
                        ? com.alex.test.village.villagers.tradesman.SellBox.goodsCount(level, manager, stall) : -1,
                building instanceof BarracksBuilding,
                building instanceof BarracksBuilding
                        ? captainsByBarracks(manager).getOrDefault(building.getItemFrameId(), "") : "",
                building instanceof SchoolBuilding school && level != null
                        ? com.alex.test.village.villagers.teacher.SchoolReport.rows(level, manager, school)
                        : building instanceof ButcheryBuilding butchery
                        ? com.alex.test.village.villagers.butcher.ButcheryReport.rows(manager, butchery)
                        : building instanceof com.alex.test.village.building.MageTowerBuilding tower
                        ? mageTowerRows(tower)
                        : building instanceof MineBuilding mine
                        ? mineRows(manager, level, mine)
                        : List.of());
    }

    /**
     * A Mine's card rows: the shaft's height, the ores most of its ore is, and
     * how its tunnels run ({@code MineReport.tunnelRows}, shared with the Mine
     * screen).
     */
    private static List<String> mineRows(VillageManager manager, ServerLevel level, MineBuilding mine) {
        int y = mine.mouth().getY();
        List<String> ores = new ArrayList<>();
        for (var ore : com.alex.test.village.villagers.miner.OreHeights.mainOres(y)) ores.add(ore.label());
        List<String> rows = new ArrayList<>(List.of(
                "Height\tY " + y,
                "Main ores\t" + String.join(", ", ores)));
        if (level == null || mine.shaftDirection() == null) return rows;
        var reservations = com.alex.test.village.villagers.miner.MineReservations.snapshot(level);
        if (!reservations.knows(mine.getItemFrameId())) return rows;
        rows.addAll(com.alex.test.village.villagers.miner.MineReport.tunnelRows(
                com.alex.test.village.villagers.miner.MineReservations.planFor(mine, reservations, manager)));
        return rows;
    }

    /**
     * Saved captain homes take precedence; captains without one use the nearest
     * available valid barracks. Unloaded home owners still hold their places.
     */
    /** The Mage Tower's card rows: how fast mages learn here and what it has to learn with. */
    private static List<String> mageTowerRows(com.alex.test.village.building.MageTowerBuilding tower) {
        int shelves = tower.getBookshelves().size();
        String more = shelves < com.alex.test.village.building.MageTowerBuilding.MAX_USEFUL_BOOKSHELVES
                ? " (more shelves, faster)" : " (the most)";
        return List.of(
                "Study speed\t" + Math.round(tower.studyPay() * 100) + "%" + more,
                "Study spots\t" + tower.getStudyStations().size(),
                "Bookshelves\t" + shelves,
                "Focus crystals\t" + tower.getCrystals().size());
    }

    private static Map<java.util.UUID, String> captainsByBarracks(VillageManager manager) {
        List<BarracksBuilding> open = new ArrayList<>();
        for (Building building : manager.getBuildings()) {
            if (building instanceof BarracksBuilding barracks && barracks.isValid()) open.add(barracks);
        }
        Map<java.util.UUID, String> result = new java.util.HashMap<>();
        java.util.Set<java.util.UUID> paired = new java.util.HashSet<>();
        // A home claim takes precedence over where a captain happens to stand now.
        for (var claim : manager.getSavedBedAssignments()) {
            if (!claim.barracksCaptain()) continue;
            BarracksBuilding home = open.stream()
                    .filter(b -> b.getItemFrameId().equals(claim.buildingId())).findFirst().orElse(null);
            if (home == null) continue;
            VillagerModel model = manager.getModel(claim.villagerId());
            var npc = model == null ? null : model.puppet();
            if (npc != null && (!npc.isAlive() || npc.getProfession() != Profession.CAPTAIN)) continue;
            String name = model == null ? "Captain" : villagerName(model);
            if (npc == null) name += " (unloaded)";
            else {
                int level = npc.getProfessionSkills().stream()
                        .filter(skill -> skill.profession() == Profession.CAPTAIN)
                        .mapToInt(com.alex.test.village.villagers.core.ProfessionSkill::level).findFirst().orElse(1);
                name += " (Lv " + level + ")";
            }
            result.put(home.getItemFrameId(), name);
            paired.add(claim.villagerId());
            open.remove(home);
        }
        for (VillagerModel model : manager.getVillagers()) {
            var npc = model.puppet();
            if (paired.contains(model.npcId) || npc == null || !npc.isAlive() || npc.getProfession() != Profession.CAPTAIN) continue;
            BarracksBuilding nearest = null;
            double best = Double.MAX_VALUE;
            for (BarracksBuilding barracks : open) {
                double dist = barracks.getDoorPos().distSqr(npc.blockPosition());
                if (dist < best) {
                    best = dist;
                    nearest = barracks;
                }
            }
            if (nearest == null) break;
            open.remove(nearest);
            int level = npc.getProfessionSkills().stream()
                    .filter(skill -> skill.profession() == Profession.CAPTAIN)
                    .mapToInt(com.alex.test.village.villagers.core.ProfessionSkill::level)
                    .findFirst().orElse(1);
            result.put(nearest.getItemFrameId(), villagerName(model) + " (Lv " + level + ")");
        }
        return result;
    }

    /** Which of the Mayor's type buttons a building falls under. */
    private static BuildingInstanceEntry.Category category(Building building) {
        if (building instanceof HouseBuilding) return BuildingInstanceEntry.Category.HOUSE;
        if (building instanceof StorageBuilding) return BuildingInstanceEntry.Category.STORAGE;
        if (building instanceof KitchenBuilding || building instanceof ButcheryBuilding
                || building instanceof ForgeBuilding
                || building instanceof MineBuilding || building instanceof RanchBuilding
                || building instanceof MerchantStallBuilding || building instanceof BarracksBuilding
                || building instanceof SchoolBuilding || building instanceof TavernBuilding
                || building instanceof LibraryBuilding || building instanceof ArcheryRangeBuilding
                || building instanceof WatchtowerBuilding
                || building instanceof com.alex.test.village.building.MageTowerBuilding) {
            return BuildingInstanceEntry.Category.WORK;
        }
        return BuildingInstanceEntry.Category.OTHER;
    }

    /**
     * Whether a claimed bed is still there. Same rule as the house screen, but
     * an unloaded bed counts as present so the overview never loads chunks.
     */
    private static boolean bedPresent(ServerLevel level, List<BlockPos> roomBeds, BlockPos bed) {
        if (!roomBeds.contains(bed)) return false;
        if (level == null || !level.isLoaded(bed)) return true;
        BlockState state = level.getBlockState(bed);
        return state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    private static String villagerName(VillagerModel model) {
        // The remembered name, so a villager far away keeps its name too.
        return com.alex.test.village.VillageManager.displayName(model);
    }

    private static String displayName(Building building) {
        if (building instanceof HouseBuilding house) return house.getDisplayName();
        if (building instanceof StorageBuilding) return "Storage";

        String simple = building.getClass().getSimpleName();
        return simple.endsWith("Building")
                ? simple.substring(0, simple.length() - "Building".length())
                : simple;
    }

    private static String homeForBed(Building building) {
        if (building instanceof HouseBuilding house) {
            return house.getDisplayName() + " at " + position(house.getDoorPos());
        }
        if (building instanceof TownHallBuilding hall) {
            return "Town Hall at " + position(hall.getDoorPos());
        }
        return "Not assigned";
    }

    private static String position(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String buildingIcon(Building building) {
        if (building instanceof HouseBuilding house) {
            return DynamicVillages.MODID + ":" + house.getSavedBuildingType();
        }
        if (building instanceof StorageBuilding) return "minecraft:chest";
        if (building instanceof RanchBuilding) return DynamicVillages.MODID + ":ranch";
        if (building instanceof KitchenBuilding) return DynamicVillages.MODID + ":kitchen";
        if (building instanceof ButcheryBuilding) return DynamicVillages.MODID + ":butchery";
        if (building instanceof MineBuilding) return DynamicVillages.MODID + ":mine";
        if (building instanceof ForgeBuilding) return DynamicVillages.MODID + ":forge";
        if (building instanceof MerchantStallBuilding) return DynamicVillages.MODID + ":stall";
        if (building instanceof BarracksBuilding) return DynamicVillages.MODID + ":barracks";
        if (building instanceof ArcheryRangeBuilding) return DynamicVillages.MODID + ":archery_range";
        if (building instanceof WatchtowerBuilding) return DynamicVillages.MODID + ":watchtower";
        if (building instanceof SchoolBuilding) return DynamicVillages.MODID + ":school";
        if (building instanceof TavernBuilding) return DynamicVillages.MODID + ":tavern";
        if (building instanceof LibraryBuilding) return DynamicVillages.MODID + ":library";
        if (building instanceof com.alex.test.village.building.MageTowerBuilding) return DynamicVillages.MODID + ":mage_tower";
        if (building instanceof TownHallBuilding) return DynamicVillages.MODID + ":town_hall";
        return "minecraft:oak_planks";
    }

    private static TokenType tokenType(Building building) {
        if (building instanceof HouseBuilding house) {
            return switch (house.getCapacity()) {
                case 2 -> TokenType.TWO_BED_HOUSE;
                case 4 -> TokenType.FOUR_BED_HOUSE;
                case 6 -> TokenType.SIX_BED_HOUSE;
                default -> null;
            };
        }
        if (building instanceof TownHallBuilding) return TokenType.TOWN_HALL;
        if (building instanceof StorageBuilding) return TokenType.STORAGE;
        if (building instanceof RanchBuilding) return TokenType.RANCH;
        if (building instanceof KitchenBuilding) return TokenType.KITCHEN;
        if (building instanceof ButcheryBuilding) return TokenType.BUTCHERY;
        if (building instanceof MineBuilding) return TokenType.MINE;
        if (building instanceof ForgeBuilding) return TokenType.FORGE;
        if (building instanceof MerchantStallBuilding) return TokenType.STALL;
        if (building instanceof BarracksBuilding) return TokenType.BARRACKS;
        if (building instanceof ArcheryRangeBuilding) return TokenType.ARCHERY_RANGE;
        if (building instanceof WatchtowerBuilding) return TokenType.WATCHTOWER;
        if (building instanceof SchoolBuilding) return TokenType.SCHOOL;
        if (building instanceof TavernBuilding) return TokenType.TAVERN;
        if (building instanceof LibraryBuilding) return TokenType.LIBRARY;
        if (building instanceof com.alex.test.village.building.MageTowerBuilding) return TokenType.MAGE_TOWER;
        return null;
    }

    /** Item id of the token that stands for a profession in the management screens. */
    public static String professionIcon(Profession profession) {
        if (profession == null) return "minecraft:emerald";
        return switch (profession) {
            case LUMBERJACK -> DynamicVillages.MODID + ":lumberjack_token";
            case GUARD -> DynamicVillages.MODID + ":guard_token";
            case CAPTAIN -> DynamicVillages.MODID + ":captain_token";
            case FARMER -> DynamicVillages.MODID + ":farmer_token";
            case RANCHER -> DynamicVillages.MODID + ":rancher_token";
            case COOK -> DynamicVillages.MODID + ":cook_token";
            case BLACKSMITH -> DynamicVillages.MODID + ":blacksmith_token";
            case BUTCHER -> DynamicVillages.MODID + ":butcher_token";
            case MINER -> DynamicVillages.MODID + ":miner_token";
            case BUILDER -> DynamicVillages.MODID + ":builder_token";
            case BARD -> DynamicVillages.MODID + ":bard_token";
            case CLERIC -> DynamicVillages.MODID + ":cleric_token";
            case DRUID -> DynamicVillages.MODID + ":druid_token";
            case ENCHANTER -> DynamicVillages.MODID + ":enchanter_token";
            case TEACHER -> DynamicVillages.MODID + ":teacher_token";
            case TRADESMAN -> DynamicVillages.MODID + ":tradesman_token";
            case ARCHER -> DynamicVillages.MODID + ":archer_token";
            case MAGE -> DynamicVillages.MODID + ":mage_token";
            case NONE, CHILD -> "minecraft:emerald";
        };
    }

    private static String workplace(VillageManager manager, Profession profession) {
        if (profession == null || profession == Profession.NONE) return "Unassigned";
        if (profession == Profession.CHILD) return available(manager, SchoolBuilding.class, "School");
        return switch (profession) {
            case FARMER -> manager.farmers().snapshots().isEmpty()
                    ? "Discovering village fields" : "Village fields available";
            case DRUID -> manager.farmers().snapshots().isEmpty()
                    ? "Discovering village fields" : "Village fields available";
            case RANCHER -> available(manager, RanchBuilding.class, "Ranch");
            case BUTCHER -> butcherWorkplace(manager);
            case COOK -> available(manager, KitchenBuilding.class, "Kitchen");
            case BLACKSMITH -> available(manager, ForgeBuilding.class, "Forge");
            case MINER -> available(manager, MineBuilding.class, "Mine");
            case BARD -> available(manager, TavernBuilding.class, "Tavern");
            case ENCHANTER -> available(manager, LibraryBuilding.class, "Library");
            case TEACHER -> available(manager, SchoolBuilding.class, "School");
            case TRADESMAN -> available(manager, MerchantStallBuilding.class, "Merchant Stall");
            // Archers defend without a range; it is only where they practise.
            case ARCHER -> hasValid(manager, ArcheryRangeBuilding.class)
                    ? "Village patrol, Archery Range available" : "Village patrol";
            case GUARD -> "Village patrol";
            case CAPTAIN -> available(manager, BarracksBuilding.class, "Barracks");
            case MAGE -> hasValid(manager, com.alex.test.village.building.MageTowerBuilding.class)
                    ? "Mage Tower available" : available(manager, BarracksBuilding.class, "Barracks");
            case LUMBERJACK -> "Village woodland";
            case BUILDER -> "Village projects";
            case CLERIC -> "Village grounds";
            case NONE, CHILD -> "Unassigned";
        };
    }

    /** The butcher leads animals from a Ranch to a Butchery, so it needs both. */
    private static String butcherWorkplace(VillageManager manager) {
        if (!hasValid(manager, RanchBuilding.class)) return "Needs a Ranch";
        if (!hasValid(manager, ButcheryBuilding.class)) return "Needs a Butchery";
        boolean chop = manager.getBuildings().stream()
                .anyMatch(building -> building instanceof ButcheryBuilding butchery && butchery.canButcher());
        return chop ? "Ranch and Butchery available" : "Needs a chopping block";
    }

    private static String available(VillageManager manager,
                                    Class<? extends Building> type,
                                    String name) {
        return hasValid(manager, type) ? name + " available" : "No " + name;
    }

    private static boolean hasValid(VillageManager manager, Class<? extends Building> type) {
        return manager.getBuildings().stream()
                .anyMatch(building -> building.isValid() && type.isInstance(building));
    }

    /**
     * One line for a villager's card: what it loves, what it dislikes, and the
     * food it has had too often lately (three or more of its last six meals).
     */
    private static String dietSummary(DietMemory diet) {
        if (diet.favourite() == null) return "No meals yet";
        StringBuilder line = new StringBuilder("loves ")
                .append(diet.favourite().getDescription().getString())
                .append(", dislikes ")
                .append(diet.disliked().getDescription().getString());
        for (Item item : new java.util.LinkedHashSet<>(diet.recent())) {
            if (item != diet.favourite() && diet.timesEaten(item) >= 3) {
                line.append(", tired of ").append(item.getDescription().getString());
                break;
            }
        }
        return line.toString();
    }

    private static String titleCase(String value) {
        if (value == null || value.isBlank()) return "Unknown";
        String[] words = value.replace('_', ' ').toLowerCase(Locale.ROOT).split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
