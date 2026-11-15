package com.alex.test.village;

import com.alex.test.village.VillageManager.SavedBedAssignment;
import com.alex.test.village.VillageManager.SavedBuilding;
import com.alex.test.village.visitors.NomadSpawnManager;
import com.alex.test.village.visitors.VisitorSpawnManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * NBT layout of {@link VillageManagerRegistry}'s saved data.
 *
 * <p>The tag names, {@link #DATA_VERSION} and the migration rules in
 * {@link #read} are the save-file contract: changing any of them changes what
 * existing worlds load. The registry decides which villages are written; this
 * class only maps a {@link SavedVillage} to and from its compound tag.
 */
final class VillageRegistryCodec {

    static final int DATA_VERSION = 8;
    private static final String TAG_DATA_VERSION = "dataVersion";
    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_UUID    = "uuid";
    private static final String TAG_BELL_X  = "bellX";
    private static final String TAG_BELL_Y  = "bellY";
    private static final String TAG_BELL_Z  = "bellZ";
    private static final String TAG_RADIUS  = "radius";
    private static final String TAG_DIMENSION = "dimension";
    private static final String TAG_FRAME_VILLAGE = "townHallFrameVillage";
    private static final String TAG_TOWN_HALL_FRAME = "townHallFrame";
    private static final String TAG_INACTIVE = "inactive";
    private static final String TAG_MISSING_SINCE = "missingSince";
    // Dormancy is a separate lifecycle from inactive/missingSince (Town-Hall-marker
    // validity): it tracks whether any player has been near the village recently,
    // regardless of whether its Town Hall is intact.
    private static final String TAG_DORMANT = "dormant";
    private static final String TAG_DORMANT_SINCE = "dormantSince";
    private static final String TAG_RECONCILING = "reconciling";
    /** Away bookkeeping beside dormantSince ({@code VillageDormancy.saveExtra}); absent in older saves. */
    private static final String TAG_DORMANCY_EXTRA = "dormancyExtra";
    private static final String TAG_MAYOR = "mayor";
    private static final String TAG_VILLAGERS = "villagers";
    /** On a villager entry: it was a captain when last seen. Absent in older saves. */
    private static final String TAG_CAPTAIN = "captain";
    /** On a villager entry: its name when last seen. Absent in older saves. */
    private static final String TAG_NAME = "name";
    /** On a villager entry: where it was last seen, and that spot's dimension when not the village's own. Absent in older saves. */
    private static final String TAG_LAST_SEEN = "lastSeen";
    private static final String TAG_LAST_SEEN_DIMENSION = "lastSeenDimension";
    private static final String TAG_BUILDINGS = "buildings";
    private static final String TAG_BED_ASSIGNMENTS = "bedAssignments";
    private static final String TAG_BUILDING = "building";
    private static final String TAG_BED_X = "bedX";
    private static final String TAG_BED_Y = "bedY";
    private static final String TAG_BED_Z = "bedZ";
    private static final String TAG_TYPE = "type";
    private static final String TAG_HOUSING_CAPACITY = "housingCapacity";
    private static final String TAG_FRAME_X = "frameX";
    private static final String TAG_FRAME_Y = "frameY";
    private static final String TAG_FRAME_Z = "frameZ";
    private static final String TAG_DOOR_X = "doorX";
    private static final String TAG_DOOR_Y = "doorY";
    private static final String TAG_DOOR_Z = "doorZ";
    private static final String TAG_INTERIOR_DIRECTION = "interiorDirection";
    /** On a Butchery: the butcher's spot the player picked (10-24). Absent when automatic and in older saves. */
    private static final String TAG_WORK_SPOT_X = "workSpotX";
    private static final String TAG_WORK_SPOT_Y = "workSpotY";
    private static final String TAG_WORK_SPOT_Z = "workSpotZ";
    /** Pre-rework founding queue; read for old saves, no longer written. */
    private static final String TAG_NOMAD_WELCOME = "nomadWelcome";
    private static final String TAG_ACTIVE_NOMADS = "activeNomads";
    /** Opaque {@link NomadSpawnManager#saveSchedule()} state. */
    private static final String TAG_NOMAD_SCHEDULE = "nomadSchedule";
    private static final String TAG_DEPARTURE_AT = "departureAt";
    private static final String TAG_MERCHANT_INITIALIZED = "merchantInitialized";
    private static final String TAG_ACTIVE_MERCHANT = "activeMerchant";
    private static final String TAG_MERCHANT_DEPARTURE = "merchantDepartureAt";
    private static final String TAG_MERCHANT_ENDED = "merchantVisitEndedAt";
    private static final String TAG_MERCHANT_MORNINGS = "merchantMorningsSinceVisit";
    private static final String TAG_MERCHANT_LAST_MORNING = "merchantLastMorningDay";
    private static final String TAG_MERCHANT_FOUNDING_LEFT = "merchantFoundingVisitsLeft";
    private static final String TAG_MERCHANT_PENDING_AT = "merchantPendingArrivalAt";
    private static final String TAG_MERCHANT_PENDING_CANCEL = "merchantPendingCancelAt";
    private static final String TAG_AT_GAME_TIME = "atGameTime";
    private static final String TAG_ATTEMPTS = "attempts";
    /** Opaque FarmerManager state (farm memory and in-progress jobs); absent in older saves. */
    private static final String TAG_FARMS = "farms";
    private static final String TAG_MINER_SIDE_BRANCH_DISTANCE = "minerSideBranchDistance";
    private static final String TAG_VILLAGE_RULES = "villageRules";
    /** Each mine's own tunnel lengths and pause switch ({@code MineSettings.Book}); absent in older saves. */
    private static final String TAG_MINE_SETTINGS = "mineSettings";
    private static final String TAG_TOWN_HALL_TOKEN_GENERATION = "townHallTokenGeneration";
    /** The village's owner ({@code VillageClaim.save}); absent in older saves and for unowned villages. */
    private static final String TAG_CLAIM = "claim";

    /**
     * Every building-type string {@link VillageBuildings#createBuildingFromSaved}
     * actually knows how to construct. Kept here as one explicit set rather
     * than three hardcoded {@code .equals} checks so a future new building
     * type added to that switch can't silently reintroduce the same gap this
     * whitelist previously had (it used to only recognize house/storage/
     * town_hall and dropped every other persisted building type on load).
     */
    private static final Set<String> KNOWN_BUILDING_TYPES = Set.of(
            "2_bed_house", "4_bed_house", "6_bed_house",
            "storage", "town_hall", "kitchen", "butchery", "forge", "mine", "farm",
            "ranch", "tavern", "library", "school", "stall",
            com.alex.test.village.building.MerchantStallBuilding.OPEN_AIR_SAVED_TYPE, "barracks",
            com.alex.test.village.building.ArcheryRangeBuilding.SAVED_TYPE,
            com.alex.test.village.building.WatchtowerBuilding.SAVED_TYPE,
            com.alex.test.village.building.MageTowerBuilding.SAVED_TYPE,
            com.alex.test.village.building.ArcheryRangeBuilding.ROOM_SAVED_TYPE);

    private VillageRegistryCodec() {}

    /**
     * One village as it is written to disk. The registry also holds villages
     * in this form between loading the file and their dimension's first tick.
     */
    record SavedVillage(
            UUID id,
            ResourceKey<Level> dimension,
            BlockPos bell,
            int radius,
            boolean townHallFrameVillage,
            UUID townHallFrameId,
            boolean inactive,
            long missingSince,
            boolean dormant,
            boolean reconciling,
            long dormantSince,
            UUID mayorId,
            Set<UUID> villagerIds,
            List<SavedBuilding> buildings,
            List<SavedBedAssignment> bedAssignments,
            CompoundTag nomadSchedule,
            List<NomadSpawnManager.SavedArrival> legacyNomadWelcome,
            Map<UUID, Long> activeNomads,
            VisitorSpawnManager.SavedState merchantState,
            CompoundTag farmState,
            int minerSideBranchDistance,
            CompoundTag villageRules,
            /** Each mine's own settings, as {@code MineSettings.Book.save} wrote them. */
            CompoundTag mineSettings,
            int townHallTokenGeneration,
            /** Members last seen as captains, so one not loaded still holds its captaincy. */
            Set<UUID> captainIds,
            /** Members' names as last seen, so screens can name one not loaded. */
            Map<UUID, String> knownNames,
            /** Where members last unloaded, to tell a gone one from one that is away. */
            Map<UUID, BlockPos> lastSeenSpots,
            /** Dimensions of last-seen spots outside the village's own. */
            Map<UUID, ResourceKey<Level>> lastSeenDimensions,
            /** Away bookkeeping: empty-server and village clocks at dormancy, a catch-up part done. */
            CompoundTag dormancyExtra,
            /** Who owns the village, as {@code VillageClaim.save} wrote it; empty when nobody does. */
            CompoundTag claim) {}

    /**
     * What {@link #read} found: the villages in file order, whether anything
     * was upgraded or dropped (so the file should be rewritten), and the
     * number of villager ids, for the load log line.
     */
    record Loaded(List<SavedVillage> villages, boolean migrated, int villagerIds) {}

    // =========================================================================
    // Writing
    // =========================================================================

    /** Writes the data version and one entry per village, in the given order. */
    static CompoundTag write(CompoundTag tag, List<SavedVillage> villages) {
        ListTag list = new ListTag();
        for (SavedVillage village : villages) {
            list.add(writeVillage(village));
        }
        tag.putInt(TAG_DATA_VERSION, DATA_VERSION);
        tag.put(TAG_ENTRIES, list);
        return tag;
    }

    private static CompoundTag writeVillage(SavedVillage village) {
        UUID villageId = village.id();
        BlockPos bell = village.bell();
        int radius = village.radius();
        ResourceKey<Level> dimension = village.dimension();
        boolean frameVillage = village.townHallFrameVillage();
        UUID townHallFrameId = village.townHallFrameId();
        boolean inactive = village.inactive();
        long removedAt = village.missingSince();
        boolean dormant = village.dormant();
        boolean reconciling = village.reconciling();
        long dormantSince = village.dormantSince();
        UUID mayorId = village.mayorId();
        Collection<UUID> villagerIds = village.villagerIds();
        Collection<SavedBuilding> buildings = village.buildings();
        Collection<SavedBedAssignment> bedAssignments = village.bedAssignments();
        Map<UUID, Long> activeNomads = village.activeNomads();
        VisitorSpawnManager.SavedState merchantState = village.merchantState();

        CompoundTag entry = new CompoundTag();
        entry.putUUID(TAG_UUID, villageId);
        entry.putInt(TAG_BELL_X, bell.getX());
        entry.putInt(TAG_BELL_Y, bell.getY());
        entry.putInt(TAG_BELL_Z, bell.getZ());
        entry.putInt(TAG_RADIUS, radius);
        entry.putString(TAG_DIMENSION, dimension.location().toString());
        entry.putBoolean(TAG_FRAME_VILLAGE, frameVillage);
        if (townHallFrameId != null) entry.putUUID(TAG_TOWN_HALL_FRAME, townHallFrameId);
        entry.putBoolean(TAG_INACTIVE, inactive);
        if (removedAt >= 0L) entry.putLong(TAG_MISSING_SINCE, removedAt);
        entry.putBoolean(TAG_DORMANT, dormant);
        entry.putBoolean(TAG_RECONCILING, reconciling);
        if (dormantSince >= 0L) entry.putLong(TAG_DORMANT_SINCE, dormantSince);
        if (!village.dormancyExtra().isEmpty()) entry.put(TAG_DORMANCY_EXTRA, village.dormancyExtra());
        if (mayorId != null) entry.putUUID(TAG_MAYOR, mayorId);

        ListTag villagersTag = new ListTag();
        for (UUID villagerId : villagerIds) {
            CompoundTag villagerTag = new CompoundTag();
            villagerTag.putUUID(TAG_UUID, villagerId);
            if (village.captainIds().contains(villagerId)) villagerTag.putBoolean(TAG_CAPTAIN, true);
            String name = village.knownNames().get(villagerId);
            if (name != null) villagerTag.putString(TAG_NAME, name);
            BlockPos lastSeen = village.lastSeenSpots().get(villagerId);
            if (lastSeen != null) villagerTag.putLong(TAG_LAST_SEEN, lastSeen.asLong());
            ResourceKey<Level> lastSeenDimension = village.lastSeenDimensions().get(villagerId);
            if (lastSeen != null && lastSeenDimension != null)
                villagerTag.putString(TAG_LAST_SEEN_DIMENSION, lastSeenDimension.location().toString());
            villagersTag.add(villagerTag);
        }
        entry.put(TAG_VILLAGERS, villagersTag);

        ListTag buildingsTag = new ListTag();
        for (SavedBuilding building : buildings) {
            if (building == null || "unknown".equals(building.type())) continue;
            CompoundTag buildingTag = new CompoundTag();
            buildingTag.putUUID(TAG_UUID, building.itemFrameId());
            buildingTag.putString(TAG_TYPE, building.type());
            if (building.housingCapacity() > 0) {
                buildingTag.putInt(TAG_HOUSING_CAPACITY, building.housingCapacity());
            }
            buildingTag.putInt(TAG_FRAME_X, building.itemFramePos().getX());
            buildingTag.putInt(TAG_FRAME_Y, building.itemFramePos().getY());
            buildingTag.putInt(TAG_FRAME_Z, building.itemFramePos().getZ());
            buildingTag.putInt(TAG_DOOR_X, building.doorPos().getX());
            buildingTag.putInt(TAG_DOOR_Y, building.doorPos().getY());
            buildingTag.putInt(TAG_DOOR_Z, building.doorPos().getZ());
            buildingTag.putString(TAG_INTERIOR_DIRECTION, building.interiorDirection().getName());
            if (building.workSpot() != null) {
                buildingTag.putInt(TAG_WORK_SPOT_X, building.workSpot().getX());
                buildingTag.putInt(TAG_WORK_SPOT_Y, building.workSpot().getY());
                buildingTag.putInt(TAG_WORK_SPOT_Z, building.workSpot().getZ());
            }
            buildingsTag.add(buildingTag);
        }
        entry.put(TAG_BUILDINGS, buildingsTag);
        entry.putInt(TAG_MINER_SIDE_BRANCH_DISTANCE, village.minerSideBranchDistance());
        entry.put(TAG_VILLAGE_RULES, village.villageRules());
        entry.put(TAG_MINE_SETTINGS, village.mineSettings());
        if (village.townHallTokenGeneration() > 0) {
            entry.putInt(TAG_TOWN_HALL_TOKEN_GENERATION, village.townHallTokenGeneration());
        }
        if (village.claim() != null && !village.claim().isEmpty()) entry.put(TAG_CLAIM, village.claim().copy());

        ListTag bedAssignmentsTag = new ListTag();
        for (SavedBedAssignment assignment : bedAssignments) {
            if (assignment == null || assignment.villagerId() == null
                    || assignment.buildingId() == null || assignment.bedPos() == null) {
                continue;
            }
            CompoundTag assignmentTag = new CompoundTag();
            assignmentTag.putUUID(TAG_UUID, assignment.villagerId());
            assignmentTag.putUUID(TAG_BUILDING, assignment.buildingId());
            assignmentTag.putInt(TAG_BED_X, assignment.bedPos().getX());
            assignmentTag.putInt(TAG_BED_Y, assignment.bedPos().getY());
            assignmentTag.putInt(TAG_BED_Z, assignment.bedPos().getZ());
            assignmentTag.putBoolean("barracksCaptain", assignment.barracksCaptain());
            bedAssignmentsTag.add(assignmentTag);
        }
        entry.put(TAG_BED_ASSIGNMENTS, bedAssignmentsTag);
        CompoundTag nomadSchedule = village.nomadSchedule();
        entry.put(TAG_NOMAD_SCHEDULE, nomadSchedule != null ? nomadSchedule.copy() : new CompoundTag());
        ListTag activeNomadsTag = new ListTag();
        for (Map.Entry<UUID, Long> active : activeNomads.entrySet()) {
            CompoundTag activeTag = new CompoundTag();
            activeTag.putUUID(TAG_UUID, active.getKey());
            activeTag.putLong(TAG_DEPARTURE_AT, active.getValue());
            activeNomadsTag.add(activeTag);
        }
        entry.put(TAG_ACTIVE_NOMADS, activeNomadsTag);
        entry.putBoolean(TAG_MERCHANT_INITIALIZED, merchantState.initialized());
        if (merchantState.activeMerchantId() != null) {
            entry.putUUID(TAG_ACTIVE_MERCHANT, merchantState.activeMerchantId());
        }
        entry.putLong(TAG_MERCHANT_DEPARTURE, merchantState.departureAt());
        entry.putLong(TAG_MERCHANT_ENDED, merchantState.visitEndedAt());
        entry.putInt(TAG_MERCHANT_MORNINGS, merchantState.morningsSinceVisit());
        entry.putLong(TAG_MERCHANT_LAST_MORNING, merchantState.lastMorningDay());
        entry.putInt(TAG_MERCHANT_FOUNDING_LEFT, merchantState.foundingVisitsLeft());
        entry.putLong(TAG_MERCHANT_PENDING_AT, merchantState.pendingArrivalAt());
        entry.putLong(TAG_MERCHANT_PENDING_CANCEL, merchantState.pendingCancelAt());
        CompoundTag farmState = village.farmState();
        if (farmState != null && !farmState.isEmpty()) entry.put(TAG_FARMS, farmState.copy());
        return entry;
    }

    // =========================================================================
    // Reading
    // =========================================================================

    static Loaded read(CompoundTag tag) {
        List<SavedVillage> villages = new ArrayList<>();
        ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        int loadedDataVersion = tag.contains(TAG_DATA_VERSION) ? tag.getInt(TAG_DATA_VERSION) : 0;
        boolean migrated = loadedDataVersion < DATA_VERSION;
        Set<UUID> loadedIds = new HashSet<>();
        int loadedVillagers = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID(TAG_UUID)) {
                continue;
            }
            UUID     id   = entry.getUUID(TAG_UUID);
            if (!loadedIds.add(id)) {
                migrated = true;
                continue;
            }
            BlockPos bell = new BlockPos(
                    entry.getInt(TAG_BELL_X),
                    entry.getInt(TAG_BELL_Y),
                    entry.getInt(TAG_BELL_Z));
            int radius = entry.contains(TAG_RADIUS)
                    ? entry.getInt(TAG_RADIUS) : VillageManagerRegistry.DEFAULT_VILLAGE_RADIUS;
            if (loadedDataVersion < 4 && radius < VillageManagerRegistry.DEFAULT_VILLAGE_RADIUS) {
                radius = VillageManagerRegistry.DEFAULT_VILLAGE_RADIUS;
            }
            boolean frameVillage = entry.getBoolean(TAG_FRAME_VILLAGE);
            UUID townHallFrameId = entry.hasUUID(TAG_TOWN_HALL_FRAME)
                    ? entry.getUUID(TAG_TOWN_HALL_FRAME) : id;
            boolean inactive = entry.getBoolean(TAG_INACTIVE);
            long removedAt = entry.contains(TAG_MISSING_SINCE)
                    ? entry.getLong(TAG_MISSING_SINCE) : -1L;
            boolean dormant = entry.getBoolean(TAG_DORMANT);
            boolean reconciling = entry.getBoolean(TAG_RECONCILING);
            long dormantSince = entry.contains(TAG_DORMANT_SINCE)
                    ? entry.getLong(TAG_DORMANT_SINCE) : -1L;
            UUID mayorId = entry.hasUUID(TAG_MAYOR) ? entry.getUUID(TAG_MAYOR) : null;
            Set<UUID> villagerIds = new LinkedHashSet<>();
            Set<UUID> captainIds = new LinkedHashSet<>();
            Map<UUID, String> knownNames = new LinkedHashMap<>();
            Map<UUID, BlockPos> lastSeenSpots = new LinkedHashMap<>();
            Map<UUID, ResourceKey<Level>> lastSeenDimensions = new LinkedHashMap<>();
            ListTag villagersTag = entry.getList(TAG_VILLAGERS, Tag.TAG_COMPOUND);
            for (int j = 0; j < villagersTag.size(); j++) {
                CompoundTag villagerTag = villagersTag.getCompound(j);
                if (villagerTag.hasUUID(TAG_UUID)) {
                    villagerIds.add(villagerTag.getUUID(TAG_UUID));
                    if (villagerTag.getBoolean(TAG_CAPTAIN)) captainIds.add(villagerTag.getUUID(TAG_UUID));
                    // Older saves have none: names fill in as villagers load.
                    String name = villagerTag.getString(TAG_NAME);
                    if (!name.isEmpty()) knownNames.put(villagerTag.getUUID(TAG_UUID), name);
                    if (villagerTag.contains(TAG_LAST_SEEN, Tag.TAG_LONG))
                        lastSeenSpots.put(villagerTag.getUUID(TAG_UUID), BlockPos.of(villagerTag.getLong(TAG_LAST_SEEN)));
                    ResourceLocation lastSeenDimension = ResourceLocation.tryParse(villagerTag.getString(TAG_LAST_SEEN_DIMENSION));
                    if (villagerTag.contains(TAG_LAST_SEEN_DIMENSION, Tag.TAG_STRING) && lastSeenDimension != null)
                        lastSeenDimensions.put(villagerTag.getUUID(TAG_UUID),
                                ResourceKey.create(Registries.DIMENSION, lastSeenDimension));
                }
            }
            loadedVillagers += villagerIds.size();
            List<SavedBuilding> buildings = new ArrayList<>();
            ListTag buildingsTag = entry.getList(TAG_BUILDINGS, Tag.TAG_COMPOUND);
            for (int j = 0; j < buildingsTag.size(); j++) {
                SavedBuilding building = loadSavedBuilding(buildingsTag.getCompound(j));
                if (building != null) buildings.add(building);
                else migrated = true;
            }
            List<SavedBedAssignment> bedAssignments = new ArrayList<>();
            ListTag bedAssignmentsTag = entry.getList(TAG_BED_ASSIGNMENTS, Tag.TAG_COMPOUND);
            for (int j = 0; j < bedAssignmentsTag.size(); j++) {
                SavedBedAssignment assignment =
                        loadSavedBedAssignment(bedAssignmentsTag.getCompound(j));
                if (assignment != null) bedAssignments.add(assignment);
                else migrated = true;
            }
            CompoundTag nomadSchedule = entry.contains(TAG_NOMAD_SCHEDULE, Tag.TAG_COMPOUND)
                    ? entry.getCompound(TAG_NOMAD_SCHEDULE) : new CompoundTag();
            List<NomadSpawnManager.SavedArrival> nomadWelcome = new ArrayList<>();
            ListTag welcomeTag = entry.getList(TAG_NOMAD_WELCOME, Tag.TAG_COMPOUND);
            for (int j = 0; j < welcomeTag.size(); j++) {
                CompoundTag pendingTag = welcomeTag.getCompound(j);
                if (!pendingTag.contains(TAG_AT_GAME_TIME)) {
                    migrated = true;
                    continue;
                }
                nomadWelcome.add(new NomadSpawnManager.SavedArrival(
                        pendingTag.getLong(TAG_AT_GAME_TIME), pendingTag.getInt(TAG_ATTEMPTS)));
            }
            Map<UUID, Long> activeNomads = new LinkedHashMap<>();
            ListTag activeNomadsTag = entry.getList(TAG_ACTIVE_NOMADS, Tag.TAG_COMPOUND);
            for (int j = 0; j < activeNomadsTag.size(); j++) {
                CompoundTag activeTag = activeNomadsTag.getCompound(j);
                if (!activeTag.hasUUID(TAG_UUID) || !activeTag.contains(TAG_DEPARTURE_AT)) {
                    migrated = true;
                    continue;
                }
                activeNomads.put(activeTag.getUUID(TAG_UUID), activeTag.getLong(TAG_DEPARTURE_AT));
            }
            VisitorSpawnManager.SavedState merchantState = new VisitorSpawnManager.SavedState(
                    entry.getBoolean(TAG_MERCHANT_INITIALIZED),
                    entry.hasUUID(TAG_ACTIVE_MERCHANT) ? entry.getUUID(TAG_ACTIVE_MERCHANT) : null,
                    entry.contains(TAG_MERCHANT_DEPARTURE)
                            ? entry.getLong(TAG_MERCHANT_DEPARTURE) : -1L,
                    entry.contains(TAG_MERCHANT_ENDED) ? entry.getLong(TAG_MERCHANT_ENDED) : -1L,
                    entry.getInt(TAG_MERCHANT_MORNINGS),
                    entry.contains(TAG_MERCHANT_LAST_MORNING)
                            ? entry.getLong(TAG_MERCHANT_LAST_MORNING) : -1L,
                    entry.getInt(TAG_MERCHANT_FOUNDING_LEFT),
                    entry.contains(TAG_MERCHANT_PENDING_AT)
                            ? entry.getLong(TAG_MERCHANT_PENDING_AT) : -1L,
                    entry.contains(TAG_MERCHANT_PENDING_CANCEL)
                            ? entry.getLong(TAG_MERCHANT_PENDING_CANCEL) : -1L);
            ResourceLocation dimensionId = entry.contains(TAG_DIMENSION)
                    ? ResourceLocation.tryParse(entry.getString(TAG_DIMENSION))
                    : Level.OVERWORLD.location();
            if (!entry.contains(TAG_DIMENSION) || !entry.contains(TAG_RADIUS)
                    || !entry.contains(TAG_BUILDINGS)
                    || !entry.contains(TAG_BED_ASSIGNMENTS)
                    || !entry.contains(TAG_NOMAD_SCHEDULE)
                    || entry.contains(TAG_NOMAD_WELCOME)
                    || !entry.contains(TAG_ACTIVE_NOMADS)
                    || !entry.contains(TAG_MERCHANT_INITIALIZED)
                    || !entry.contains(TAG_MINER_SIDE_BRANCH_DISTANCE)) migrated = true;
            if (dimensionId == null) dimensionId = Level.OVERWORLD.location();
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            villages.add(new SavedVillage(
                    id, dimension, bell, radius, frameVillage, townHallFrameId,
                    inactive, removedAt, dormant, reconciling, dormantSince,
                    mayorId, villagerIds, buildings, bedAssignments,
                    nomadSchedule, nomadWelcome, activeNomads, merchantState,
                    entry.contains(TAG_FARMS, Tag.TAG_COMPOUND)
                            ? entry.getCompound(TAG_FARMS) : new CompoundTag(),
                    entry.contains(TAG_MINER_SIDE_BRANCH_DISTANCE)
                            ? entry.getInt(TAG_MINER_SIDE_BRANCH_DISTANCE)
                            : com.alex.test.village.villagers.miner.settings.MinerSettingsSchema
                                    .DEFAULT_SIDE_BRANCH_DISTANCE,
                    // Older saves have none: the rules keep their defaults.
                    entry.contains(TAG_VILLAGE_RULES, Tag.TAG_COMPOUND)
                            ? entry.getCompound(TAG_VILLAGE_RULES) : new CompoundTag(),
                    // Older saves have none: every mine on its defaults.
                    entry.contains(TAG_MINE_SETTINGS, Tag.TAG_COMPOUND)
                            ? entry.getCompound(TAG_MINE_SETTINGS) : new CompoundTag(),
                    // Older saves never replaced a Town Hall token: generation 0.
                    entry.getInt(TAG_TOWN_HALL_TOKEN_GENERATION),
                    captainIds,
                    knownNames,
                    lastSeenSpots,
                    lastSeenDimensions,
                    entry.contains(TAG_DORMANCY_EXTRA, Tag.TAG_COMPOUND)
                            ? entry.getCompound(TAG_DORMANCY_EXTRA) : new CompoundTag(),
                    // Older saves have none: the village has no owner until someone claims it.
                    entry.contains(TAG_CLAIM, Tag.TAG_COMPOUND)
                            ? entry.getCompound(TAG_CLAIM) : new CompoundTag()));
        }
        return new Loaded(villages, migrated, loadedVillagers);
    }

    @Nullable
    private static SavedBuilding loadSavedBuilding(CompoundTag tag) {
        if (!tag.hasUUID(TAG_UUID) || !tag.contains(TAG_TYPE)
                || !tag.contains(TAG_INTERIOR_DIRECTION)) return null;
        Direction direction = Direction.byName(tag.getString(TAG_INTERIOR_DIRECTION));
        if (direction == null) return null;
        String type = tag.getString(TAG_TYPE);
        if (!KNOWN_BUILDING_TYPES.contains(type)) {
            return null;
        }
        int expectedHousingCapacity = housingCapacityForType(type);
        int savedHousingCapacity = tag.contains(TAG_HOUSING_CAPACITY, Tag.TAG_INT)
                ? tag.getInt(TAG_HOUSING_CAPACITY)
                : expectedHousingCapacity;
        // The tier encoded by the token/type is authoritative. This repairs a
        // missing or inconsistent capacity rather than restoring a house with
        // a different limit from the item still displayed in its frame.
        int housingCapacity = savedHousingCapacity == expectedHousingCapacity
                ? savedHousingCapacity : expectedHousingCapacity;
        return new SavedBuilding(
                tag.getUUID(TAG_UUID),
                type,
                new BlockPos(
                        tag.getInt(TAG_DOOR_X),
                        tag.getInt(TAG_DOOR_Y),
                        tag.getInt(TAG_DOOR_Z)),
                new BlockPos(
                        tag.getInt(TAG_FRAME_X),
                        tag.getInt(TAG_FRAME_Y),
                        tag.getInt(TAG_FRAME_Z)),
                direction,
                housingCapacity,
                tag.contains(TAG_WORK_SPOT_X, Tag.TAG_INT) && tag.contains(TAG_WORK_SPOT_Y, Tag.TAG_INT)
                        && tag.contains(TAG_WORK_SPOT_Z, Tag.TAG_INT)
                        ? new BlockPos(tag.getInt(TAG_WORK_SPOT_X), tag.getInt(TAG_WORK_SPOT_Y), tag.getInt(TAG_WORK_SPOT_Z))
                        : null);
    }

    private static int housingCapacityForType(String type) {
        return switch (type) {
            case "2_bed_house" -> 2;
            case "4_bed_house" -> 4;
            case "6_bed_house" -> 6;
            default -> 0;
        };
    }

    @Nullable
    private static SavedBedAssignment loadSavedBedAssignment(CompoundTag tag) {
        if (!tag.hasUUID(TAG_UUID) || !tag.hasUUID(TAG_BUILDING)) return null;
        return new SavedBedAssignment(
                tag.getUUID(TAG_UUID),
                tag.getUUID(TAG_BUILDING),
                new BlockPos(
                        tag.getInt(TAG_BED_X),
                        tag.getInt(TAG_BED_Y),
                        tag.getInt(TAG_BED_Z)),
                tag.getBoolean("barracksCaptain"));
    }
}
