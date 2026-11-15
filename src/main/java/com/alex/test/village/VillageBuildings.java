package com.alex.test.village;

import com.alex.test.Tr;
import com.alex.test.village.VillageManager.SavedBuilding;
import com.alex.test.village.VillageManager.TownHallScan;
import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.BuildingAccess;
import com.alex.test.village.building.BuildingRequirements;
import com.alex.test.village.building.EnclosureBuilding;
import com.alex.test.village.building.EnclosureScanner;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.building.MageTowerBuilding;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.building.RoomScanner;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.TavernBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.chat.BuildingRegistrationMessages;
import com.alex.test.village.chat.PlacementHelp;
import com.alex.test.village.chat.PlacementSparkles;
import com.alex.test.village.chat.RegistrationFailure;
import com.alex.test.village.chat.TokenFeedback;
import com.alex.test.village.log.VillageLog;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.tokens.VillageTokenBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The buildings of one village: how token frames become buildings, how they
 * are restored from saved markers, kept valid, and removed.
 *
 * <p>Owned by {@link VillageManager}, which calls in from its tick and its
 * block/entity event handlers and exposes the public building queries. A
 * building is keyed by the UUID of the item frame that marks it. Rooms are
 * discovered through doors, farms and ranches through fence gates, and mines
 * through a token hung on bare rock.
 *
 * <p>Every change that can move a room boundary asks {@link VillageBeds} to
 * reconcile, and Town Hall changes are reported to the village's
 * {@code MayorSpawnManager}.
 */
final class VillageBuildings {

    /** Shares the manager's logger so log categories stay unchanged. */
    private static final Logger LOGGER = VillageManager.LOGGER;

    private final VillageManager village;

    /**
     * All known buildings, keyed by the UUID of their item-frame entity.
     */
    private final Map<UUID, Building> buildings = new LinkedHashMap<>();
    private final Map<UUID, SavedBuilding> savedBuildingMarkers = new LinkedHashMap<>();

    /**
     * Time one tick may spend restoring saved building markers during play.
     * Restoring 12 / 12 / 29 markers in a single tick cost 33 / 74 / 128 ms at
     * world join (room and enclosure scans, storage container scans), so the
     * work is spread over a few ticks instead; the tick retry already exists.
     */
    static final long RESTORE_BUDGET_NANOS = 6_000_000L;

    /**
     * Item-frame block positions queued for building-creation checks.
     * Populated by {@link VillageManager#onEntityInteract} /
     * {@link VillageManager#routeItemFrameInteract} one tick before processing
     * so the item placement has already occurred.
     */
    private final List<BlockPos> pendingFrameChecks = new ArrayList<>();
    /**
     * Queued frames already said to be waiting for a map update. A frame waits
     * by being queued again every tick; saying so every tick put five lines a
     * tick in the log for the ten seconds a generated village's tokens waited.
     */
    private final Set<BlockPos> frameChecksWaiting = new HashSet<>();

    /** A few seconds of sparkles after a Watchtower or Archery Range token is checked. */
    private final PlacementSparkles sparkles = new PlacementSparkles();

    /**
     * A Watchtower or Archery Range token whose last try failed: the next
     * failed try gets more help (the whole recipe, or "getting closer"), and
     * the token is checked again by itself while the player builds.
     */
    private static final class FailedToken {
        final BlockPos framePos;
        final ItemFrameTokenRegistry.TokenType type;
        /** The last failure the player was told about, at this spot. */
        RegistrationFailure lastReason;
        long lastFailAt;
        /** The village map as it was at the last quiet re-check, and when that was. */
        long checkedGraphVersion = Long.MIN_VALUE;
        long checkedAt;

        FailedToken(BlockPos framePos, ItemFrameTokenRegistry.TokenType type) {
            this.framePos = framePos.immutable();
            this.type = type;
        }
    }

    private final Map<UUID, FailedToken> failedTokens = new LinkedHashMap<>();

    /**
     * Failed tries at one kind of building, whichever frame they were in: a
     * player trying things moves the token from frame to frame, and counting
     * per frame started afresh each time, so the recipe never showed.
     */
    private static final class Tries {
        int fails;
        long lastFailAt;
        long lastRecipeAt = Long.MIN_VALUE / 2;
    }

    private final Map<ItemFrameTokenRegistry.TokenType, Tries> triesByType =
            new java.util.EnumMap<>(ItemFrameTokenRegistry.TokenType.class);
    /** A failed try is remembered this long (10 minutes); after that the next try starts afresh. */
    private static final int FAIL_MEMORY_TICKS = 12_000;
    /** The whole recipe is printed again at most this often (a minute), however often the token is clicked. */
    private static final int RECIPE_GAP_TICKS = 1200;
    /** Failed tokens are looked at again this often, when the village map has changed since. */
    private static final int RECHECK_TICKS = 40;
    /** ...and this often even when it has not (a change the map does not show). */
    private static final int RECHECK_ANYWAY_TICKS = 600;
    /** A failed token is re-checked by itself for this long (30 minutes) after the last try. */
    private static final int RECHECK_FOR_TICKS = 36_000;
    private long nextRecheckAt;
    /** True while a failed token is re-checked by itself: a failure then says nothing. */
    private boolean quietChecks;

    /**
     * UUIDs of buildings whose AABB contains a recently changed block.
     * These are rebuilt from the next tick on, oldest first, a few
     * milliseconds' worth per tick (see {@link #REBUILD_BUDGET_NANOS}).
     */
    private final Set<UUID> dirtyBuildings = new LinkedHashSet<>();

    /**
     * Room rescans per tick stop once this much time has gone (4 ms); the
     * rest wait for the next tick. At least one always runs. Rescanning all
     * 24 buildings of a village in one go took 70-135 ms, a visible stutter.
     */
    private static final long REBUILD_BUDGET_NANOS = 4_000_000L;

    /**
     * How close (blocks sideways / up-down) another room must come to a door
     * that was added or removed to be rescanned. Rooms that share space or
     * meet at a doorway touch, so this only adds slack.
     */
    private static final int NEIGHBOUR_MARGIN_XZ = 2, NEIGHBOUR_MARGIN_Y = 3;

    /** Timing only: the rescans of the pass in progress, logged when it ends. */
    private int passRebuilt, passFollowUps;
    private long passNanos, passSlowestNanos;
    private int passTicks;
    private String passSlowest = "-";

    /**
     * Buildings rebuilt while a nav graph update was still pending.
     *
     * <p>Buildings are rebuilt the tick after a block change, but the nav
     * graph is debounced and catches up later. Placing a ladder therefore
     * rescanned the house against a graph with no rungs in it, and the floor
     * upstairs stayed missing until some unrelated block change. Each of
     * these gets one more rebuild once the graph has been updated.
     */
    private final Set<UUID> rescanAfterNavRebuild = new HashSet<>();

    /**
     * Buildings queued by {@link #onNavGraphRebuilt}. Their rebuild is the
     * follow-up itself, so it does not queue another one — otherwise steady
     * block changes elsewhere would keep them rescanning after every update.
     */
    private final Set<UUID> navFollowUps = new HashSet<>();

    /** How long a lost route must stay lost before the building loses access: twenty seconds. */
    private static final long ACCESS_LOSS_GRACE_TICKS = 400L;

    /**
     * Chunks around the door mapped to check a new Town Hall's room. The room
     * and its doorway are all that is checked, so the whole village's square is
     * not needed: mapping it here loaded and walked ~360 chunks one at a time,
     * about 7 of the 10 seconds a generated village froze the game for. The
     * village's real map is built right after, with its chunks loaded in bulk.
     */
    static final int TOWN_HALL_CHECK_RADIUS = 2;

    /**
     * Buildings whose route was found cut, and the game tick it was first seen.
     *
     * <p>A graph update can cut a route for a moment and the next one restore
     * it -- a trapdoor toggled by hand is only noticed when something nearby
     * changes -- and every such blip used to turn the token off and back on
     * with a pair of chat lines. A building keeps its access until the loss
     * has lasted {@link #ACCESS_LOSS_GRACE_TICKS}. Keyed by the building
     * itself, so a building replaced under the same frame starts clean.
     */
    private final Map<Building, Long> accessLostSince = new IdentityHashMap<>();

    private int revalidateIndex = 0;
    private Iterator<NavNode> autoScanNodes;
    private final Set<BlockPos> autoScanDoors = new LinkedHashSet<>();
    private final Set<BlockPos> autoScanGates = new LinkedHashSet<>();
    private Iterator<BlockPos> autoScanCandidates;
    private int autoScanPhase;

    /** Suppresses generic Town Hall feedback only while a new village is founded. */
    private boolean suppressTownHallRegistrationFeedback;

    VillageBuildings(VillageManager village) {
        this.village = village;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /** Forgets every building, saved marker and queued check (village teardown). */
    void clear() {
        if (buildings.values().stream().anyMatch(MineBuilding.class::isInstance)
                || savedBuildingMarkers.values().stream().anyMatch(saved -> "mine".equals(saved.type()))) {
            village.onMineLayoutChanged();
        }
        cancelAutoScan();
        buildings.clear();
        savedBuildingMarkers.clear();
        pendingFrameChecks.clear();
        frameChecksWaiting.clear();
        dirtyBuildings.clear();
        rescanAfterNavRebuild.clear();
        navFollowUps.clear();
        accessLostSince.clear();
    }

    /** Drops queued work but keeps the buildings themselves (server shutdown). */
    void clearPendingWork() {
        cancelAutoScan();
        pendingFrameChecks.clear();
        frameChecksWaiting.clear();
        dirtyBuildings.clear();
        rescanAfterNavRebuild.clear();
        navFollowUps.clear();
        accessLostSince.clear();
    }

    void setSuppressTownHallRegistrationFeedback(boolean suppress) {
        this.suppressTownHallRegistrationFeedback = suppress;
    }

    // =========================================================================
    // Queries
    // =========================================================================

    /** Returns all currently registered buildings (valid or not). */
    Collection<Building> all() {
        return Collections.unmodifiableCollection(buildings.values());
    }

    @Nullable
    Building get(UUID frameId) {
        return buildings.get(frameId);
    }

    boolean isRegistered(UUID frameId) {
        return buildings.containsKey(frameId);
    }

    boolean hasSavedMarker(UUID frameId) {
        return savedBuildingMarkers.containsKey(frameId);
    }

    /**
     * A saved building with its door near {@code pos} that is not back yet
     * (the world just loaded, or its chunk is not loaded): its beds are unknown.
     */
    boolean hasUnrestoredBuildingNear(BlockPos pos, double reachSq) {
        for (Map.Entry<UUID, SavedBuilding> entry : savedBuildingMarkers.entrySet()) {
            if (buildings.containsKey(entry.getKey())) continue;
            if (entry.getValue().doorPos().distSqr(pos) <= reachSq) return true;
        }
        return false;
    }

    boolean isBarracks(UUID frameId) {
        Building building = buildings.get(frameId);
        if (building != null) return building instanceof BarracksBuilding;
        SavedBuilding saved = savedBuildingMarkers.get(frameId);
        return saved != null && "barracks".equals(saved.type());
    }

    boolean isEmpty() {
        return buildings.isEmpty();
    }

    /** True only when this exact marker frame owns a currently valid town hall. */
    boolean hasValidTownHall(UUID frameId) {
        Building building = buildings.get(frameId);
        return building instanceof TownHallBuilding && building.isValid();
    }

    boolean hasSavedTownHall(UUID frameId) {
        SavedBuilding saved = savedBuildingMarkers.get(frameId);
        return saved != null && "town_hall".equals(saved.type());
    }

    /** Where this Town Hall's marker frame hangs: the live building's, else its saved marker's. */
    @Nullable
    BlockPos townHallFramePos(UUID frameId) {
        Building building = buildings.get(frameId);
        if (building instanceof TownHallBuilding) return building.getItemFramePos();
        SavedBuilding saved = savedBuildingMarkers.get(frameId);
        return saved != null && "town_hall".equals(saved.type()) ? saved.itemFramePos() : null;
    }

    /**
     * True when this exact frame still hangs where the Town Hall was saved and
     * still holds a Town Hall token that is not another village's. Read-only,
     * unlike {@link #acceptTokenFrame}. Only meaningful once the frame's chunk
     * has its entities loaded; before that every frame reads as missing.
     */
    boolean isTownHallFramePresent(ServerLevel sl, UUID frameId) {
        BlockPos pos = townHallFramePos(frameId);
        if (pos == null) return false;
        ItemFrame frame = ItemFrameTokenRegistry.findExactFrameAt(sl, pos);
        return frame != null && frame.getUUID().equals(frameId)
                && ItemFrameTokenRegistry.tokenType(frame.getItem())
                        == ItemFrameTokenRegistry.TokenType.TOWN_HALL
                && !VillageTokenBinding.belongsToAnotherVillage(frame.getItem(), village)
                // A replaced token no longer works, even back in the hall's own frame.
                && !VillageManagerRegistry.get(sl.getServer()).isReplacedTownHallToken(frame.getItem());
    }

    /**
     * Checks a Town Hall again whose frame is in place but which is not valid.
     * A hall that was never restored is restored from its saved marker. A hall
     * that was restored but failed its room or access check is rescanned;
     * restoreSavedBuildings skips it because it is already registered.
     * Sends no chat messages. The caller reports a recovery.
     */
    boolean retryTownHall(ServerLevel sl, UUID frameId) {
        if (hasValidTownHall(frameId)) return true;
        if (village.getGraph() == null) return false;
        Building existing = buildings.get(frameId);
        if (existing == null) {
            // Unbounded: this is a targeted retry and the hall may be anywhere
            // in the saved order.
            restoreSavedBuildings(sl, Long.MAX_VALUE);
            return hasValidTownHall(frameId);
        }
        if (!(existing instanceof TownHallBuilding townHall)) return false;
        if (!sl.hasChunkAt(existing.getDoorPos())) return false;
        ItemFrame frame = ItemFrameTokenRegistry.findExactFrameAt(sl, existing.getItemFramePos());
        if (frame == null || !frame.getUUID().equals(frameId) || !acceptTokenFrame(sl, frame)) {
            return false;
        }
        existing.rebuild(sl, village.getGraph(), rivalEntries(frameId));
        existing.setAccessValid(accessFailure(existing) == null);
        boolean valid = existing.isValid();
        ItemFrameTokenRegistry.setRegisteredGlint(frame, valid);
        if (valid) village.mayors().onTownHallRegistered(sl, townHall);
        return valid;
    }

    /**
     * Whether {@code pos} is inside or directly around any registered room;
     * see {@link Building#isNearRoom}.
     */
    boolean isNearAnyRoom(BlockPos pos) {
        for (Building building : buildings.values()) {
            if (building.isNearRoom(pos)) return true;
        }
        return false;
    }

    /** As {@link #isNearAnyRoom}, leaving out one building's own box. */
    boolean isNearAnyRoomExcept(BlockPos pos, UUID skipFrameId) {
        for (Building building : buildings.values()) {
            if (building.getItemFrameId().equals(skipFrameId)) continue;
            if (building.isNearRoom(pos)) return true;
        }
        return false;
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    List<SavedBuilding> getSavedBuildings() {
        LinkedHashMap<UUID, SavedBuilding> result = new LinkedHashMap<>(savedBuildingMarkers);
        for (Building building : buildings.values()) {
            result.put(building.getItemFrameId(), savedBuilding(building));
        }
        return List.copyOf(result.values());
    }

    void rememberSavedBuildings(Collection<SavedBuilding> savedBuildings) {
        for (SavedBuilding saved : savedBuildings) {
            if (saved == null || saved.itemFrameId() == null) continue;
            savedBuildingMarkers.put(saved.itemFrameId(), saved);
        }
        ServerLevel level = village.getLevel();
        if (level != null) restoreSavedBuildings(level, Long.MAX_VALUE);
    }

    /**
     * Rebuilds saved buildings whose marker frame is back, in saved order so
     * the Town Hall comes first. Stops once {@code budgetNanos} is spent,
     * checked after each restored building, so at least one is restored per
     * call; markers not reached stay in the map for the next tick.
     */
    void restoreSavedBuildings(ServerLevel sl, long budgetNanos) {
        if (savedBuildingMarkers.isEmpty() || village.getGraph() == null) return;

        long began = System.nanoTime();
        int restored = 0;
        for (SavedBuilding saved : new ArrayList<>(savedBuildingMarkers.values())) {
            if (buildings.containsKey(saved.itemFrameId())) continue;
            ItemFrame frame = ItemFrameTokenRegistry.findExactFrameAt(sl, saved.itemFramePos());
            if (frame != null && (!frame.getUUID().equals(saved.itemFrameId())
                    || !ItemFrameTokenRegistry.savedTypeMatches(saved.type(), frame.getItem())
                    || !acceptTokenFrame(sl, frame))) {
                savedBuildingMarkers.remove(saved.itemFrameId());
                if ("mine".equals(saved.type())) village.onMineLayoutChanged();
                ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
                village.markPersistentDataDirty();
                continue;
            }
            Building building = createBuildingFromSaved(sl, saved);
            if (building == null) {
                if (frame != null && sl.hasChunkAt(saved.doorPos())
                        && sl.hasChunkAt(saved.itemFramePos())) {
                    ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
                }
                continue;
            }

            installRestoredBuilding(sl, saved.itemFrameId(), building, frame);
            restored++;
            if (System.nanoTime() - began >= budgetNanos) break;
        }

        if (restored > 0) {
            village.beds().hydrateSavedBedAssignments();
            village.beds().reconcileBedAssignments();
            int waiting = 0;
            for (UUID id : savedBuildingMarkers.keySet()) {
                if (!buildings.containsKey(id)) waiting++;
            }
            LOGGER.info("[VillageManager {}] Restored {} saved building marker(s), {} waiting.",
                    village.shortId(), restored, waiting);
        }
    }

    /** Registers a building rebuilt from its saved marker. Silent: it was already registered before. */
    private void installRestoredBuilding(ServerLevel sl, UUID frameId, Building building,
                                         @Nullable ItemFrame frame) {
        building.setAccessValid(accessFailure(building) == null);
        buildings.put(frameId, building);
        if (frame != null) ItemFrameTokenRegistry.setRegisteredGlint(frame, building.isValid());
        if (building.isValid() && building instanceof TownHallBuilding townHall) {
            village.mayors().onTownHallRegistered(sl, townHall);
        }
    }

    @Nullable
    private Building createBuildingFromSaved(ServerLevel sl, SavedBuilding saved) {
        return createBuildingFromSaved(sl, saved, null);
    }

    /**
     * @param knownFrame the marker frame when the caller already holds it. A
     *        frame inside its EntityJoinLevelEvent is not in the level yet, so
     *        looking it up by position would miss it.
     */
    @Nullable
    private Building createBuildingFromSaved(ServerLevel sl, SavedBuilding saved,
                                             @Nullable ItemFrame knownFrame) {
        if (!sl.hasChunkAt(saved.doorPos()) || !sl.hasChunkAt(saved.itemFramePos())) return null;
        boolean enclosure = "farm".equals(saved.type()) || "ranch".equals(saved.type())
                || ArcheryRangeBuilding.SAVED_TYPE.equals(saved.type());
        // A mine has no door or room, and its anchor material is unrestricted.
        boolean outcrop = "mine".equals(saved.type());
        // An open-air stall has no entrance at all; the frame check below is
        // the whole test.
        boolean openStall = MerchantStallBuilding.OPEN_AIR_SAVED_TYPE.equals(saved.type());
        // A watchtower is its token and the platform around it: no entrance either.
        boolean tower = WatchtowerBuilding.SAVED_TYPE.equals(saved.type());
        boolean entranceIntact = openStall || outcrop || tower
                || (enclosure ? isGateBase(sl, saved.doorPos()) : isDoorBase(sl, saved.doorPos()));
        if (!entranceIntact) return null;
        ItemFrame frame = knownFrame != null
                ? knownFrame
                : ItemFrameTokenRegistry.findExactFrameAt(sl, saved.itemFramePos());
        if (frame == null || !frame.getUUID().equals(saved.itemFrameId())
                || !ItemFrameTokenRegistry.savedTypeMatches(saved.type(), frame.getItem())
                || !acceptTokenFrame(sl, frame)) {
            return null;
        }

        NavGraph navGraph = village.getGraph();
        Set<BlockPos> roomBlocks = openStall
                ? MerchantStallBuilding.openAreaTiles(navGraph, saved.itemFramePos(),
                saved.interiorDirection())
                : tower
                ? WatchtowerBuilding.platformTiles(navGraph, saved.itemFramePos())
                : outcrop
                ? MineBuilding.approachTiles(navGraph, saved.doorPos().below(2),
                saved.interiorDirection())
                : enclosure
                  ? EnclosureScanner.scan(sl, saved.doorPos(), saved.interiorDirection())
                  : RoomScanner.scan(sl, navGraph, saved.doorPos(), saved.interiorDirection(),
                rivalEntries(saved.itemFrameId()));
        if (roomBlocks == null || roomBlocks.isEmpty()) return null;

        Building building = switch (saved.type()) {
            case "2_bed_house" -> new HouseBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks),
                    saved.housingCapacity());
            case "4_bed_house" -> new HouseBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks),
                    saved.housingCapacity());
            case "6_bed_house" -> new HouseBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks),
                    saved.housingCapacity());
            case "storage" -> new StorageBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case "town_hall" -> new TownHallBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case "kitchen" -> new KitchenBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case "forge" -> new ForgeBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case ButcheryBuilding.SAVED_TYPE -> new ButcheryBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case "mine" -> new MineBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case "ranch" -> new RanchBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case "tavern" -> new TavernBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case "library" -> new LibraryBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case "school" -> new SchoolBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case "stall" -> new MerchantStallBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case MerchantStallBuilding.OPEN_AIR_SAVED_TYPE -> MerchantStallBuilding.openAir(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks),
                    navGraph, village.getBellPos());
            case "barracks" -> new BarracksBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case MageTowerBuilding.SAVED_TYPE -> new MageTowerBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case WatchtowerBuilding.SAVED_TYPE -> new WatchtowerBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            case ArcheryRangeBuilding.ROOM_SAVED_TYPE -> new ArcheryRangeBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks), true);
            case ArcheryRangeBuilding.SAVED_TYPE -> new ArcheryRangeBuilding(
                    saved.itemFrameId(), saved.doorPos(), saved.itemFramePos(),
                    saved.interiorDirection(), new LinkedHashSet<>(roomBlocks));
            default -> null;
        };
        if (building == null) return null;
        // Before the scan, so the scan puts the player's chosen butcher's spot first.
        if (building instanceof ButcheryBuilding butchery) butchery.chooseKillSpot(saved.workSpot());
        building.validate(sl);
        return building;
    }

    private SavedBuilding savedBuilding(Building building) {
        return new SavedBuilding(
                building.getItemFrameId(),
                buildingType(building),
                building.getDoorPos(),
                building.getItemFramePos(),
                building.getInteriorDirection(),
                building instanceof HouseBuilding house ? house.getCapacity() : 0,
                building instanceof ButcheryBuilding butchery ? butchery.chosenKillSpot() : null);
    }

    private static String buildingType(Building building) {
        if (building instanceof HouseBuilding house) return house.getSavedBuildingType();
        if (building instanceof StorageBuilding) return "storage";
        if (building instanceof TownHallBuilding) return "town_hall";
        if (building instanceof KitchenBuilding) return "kitchen";
        if (building instanceof ButcheryBuilding) return ButcheryBuilding.SAVED_TYPE;
        if (building instanceof ForgeBuilding) return "forge";
        if (building instanceof MineBuilding) return "mine";
        if (building instanceof RanchBuilding) return "ranch";
        if (building instanceof TavernBuilding) return "tavern";
        if (building instanceof LibraryBuilding) return "library";
        if (building instanceof SchoolBuilding) return "school";
        if (building instanceof MerchantStallBuilding stall)
            return stall.isOpenAir() ? MerchantStallBuilding.OPEN_AIR_SAVED_TYPE : "stall";
        if (building instanceof BarracksBuilding) return "barracks";
        if (building instanceof MageTowerBuilding) return MageTowerBuilding.SAVED_TYPE;
        if (building instanceof ArcheryRangeBuilding range)
            return range.isIndoor() ? ArcheryRangeBuilding.ROOM_SAVED_TYPE : ArcheryRangeBuilding.SAVED_TYPE;
        if (building instanceof WatchtowerBuilding) return WatchtowerBuilding.SAVED_TYPE;
        return "unknown";
    }

    // =========================================================================
    // Discovery of pre-existing buildings
    // =========================================================================

    /**
     * Walk every nav-graph node; when the block at the node's feetY position is a
     * door lower-half, search a small box around it for item frames that carry a
     * recognised building-trigger item.  Any frame whose door resolves back to this
     * same door position (via {@link #findDoorNear}) and that isn't already
     * registered gets handed to {@link #tryCreateBuilding}.
     *
     * <p>Called after every nav-graph build so that buildings constructed before
     * the village was created, or before the mod was loaded, are detected
     * automatically without requiring the player to right-click the frame.
     */
    void scanExistingBuildings(ServerLevel sl) {
        cancelAutoScan();
        NavGraph navGraph = village.getGraph();
        if (navGraph == null || navGraph.isEmpty()) return;
        scanExistingRooms(sl);
        // Both of these used to hang off the end of the room scan, which
        // returned early when the village had no doors on the graph — so a
        // village whose only marked things were pens and outcrops discovered
        // none of them.
        scanExistingEnclosures(sl);
        scanExistingOutcrops(sl);
        scanExistingOpenStalls(sl);
    }

    /**
     * Registers the frame hung by {@code door} only: for a generated village
     * founding itself, whose one building is the Town Hall just hung there.
     */
    void registerTownHallAt(ServerLevel sl, BlockPos door) {
        cancelAutoScan();
        NavGraph navGraph = village.getGraph();
        if (navGraph == null || navGraph.isEmpty()) return;
        if (tryAutoRegisterRoom(sl, door)) {
            LOGGER.info("[VillageManager {}] Auto-scan: registered the Town Hall at {}.", village.shortId(), door);
        }
    }

    /** Full builds and joining discover old token frames without walking the whole graph in one tick. */
    void scheduleExistingBuildingsScan() {
        cancelAutoScan();
        NavGraph graph = village.getGraph();
        if (graph == null || graph.isEmpty()) return;
        autoScanNodes = graph.allNodes().iterator();
        autoScanPhase = 1;
    }

    /**
     * As {@link #scheduleExistingBuildingsScan()}, but only under the tiles of
     * these cells. After a local patch a door or gate can only have become
     * registerable where the patch changed something, so the hunt reads a few
     * hundred blocks instead of one under every tile in the village, which
     * was eighty ticks of work after every edit.
     */
    void scheduleExistingBuildingsScan(java.util.Collection<Long> cellKeys) {
        cancelAutoScan();
        NavGraph graph = village.getGraph();
        if (graph == null || graph.isEmpty()) return;
        autoScanNodes = graph.nodesInCells(cellKeys).iterator();
        autoScanPhase = 1;
    }

    void tickExistingBuildingsScan(ServerLevel sl) {
        if (autoScanPhase == 0) return;
        if (autoScanPhase == 1) {
            int remaining = 2048;
            while (remaining-- > 0 && autoScanNodes.hasNext()) {
                BlockPos pos = autoScanNodes.next().feetPos();
                BlockState state = sl.getBlockState(pos);
                if (state.getBlock() instanceof DoorBlock
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                    autoScanDoors.add(pos);
                }
                if (isGateBase(sl, pos)) autoScanGates.add(pos);
            }
            if (autoScanNodes.hasNext()) return;
            autoScanNodes = null;
            autoScanCandidates = autoScanDoors.iterator();
            autoScanPhase = 2;
            return;
        }
        if (autoScanPhase == 2 || autoScanPhase == 3) {
            int remaining = 4;
            while (remaining-- > 0 && autoScanCandidates.hasNext()) {
                BlockPos pos = autoScanCandidates.next();
                if (autoScanPhase == 2) tryAutoRegisterRoom(sl, pos);
                else tryAutoRegisterEnclosure(sl, pos);
            }
            if (autoScanCandidates.hasNext()) return;
            if (autoScanPhase == 2) {
                autoScanCandidates = autoScanGates.iterator();
                autoScanPhase = 3;
            } else {
                autoScanCandidates = null;
                autoScanDoors.clear();
                autoScanGates.clear();
                autoScanPhase = 4;
            }
            return;
        }
        if (autoScanPhase == 4) {
            scanExistingOutcrops(sl);
            autoScanPhase = 5;
            return;
        }
        scanExistingOpenStalls(sl);
        cancelAutoScan();
    }

    private void cancelAutoScan() {
        autoScanNodes = null;
        autoScanCandidates = null;
        autoScanDoors.clear();
        autoScanGates.clear();
        autoScanPhase = 0;
    }

    private void scanExistingRooms(ServerLevel sl) {
        NavGraph navGraph = village.getGraph();
        //        1. Collect door-base positions that are on nav-graph nodes
        Set<BlockPos> doorBases = new LinkedHashSet<>();
        for (NavNode node : navGraph.allNodes()) {
            BlockPos fp = node.feetPos();
            BlockState state = sl.getBlockState(fp);
            if (state.getBlock() instanceof DoorBlock
                    && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                doorBases.add(fp);
            }
        }

        if (doorBases.isEmpty()) return;
        LOGGER.info("[VillageManager {}] Auto-scan: {} door(s) on nav-graph.", village.shortId(), doorBases.size());

        //        2. For each door, find a matching recognised item frame nearby
        int registered = 0;
        for (BlockPos doorBase : doorBases) {
            if (tryAutoRegisterRoom(sl, doorBase)) registered++;
        }

        if (registered > 0) {
            LOGGER.info("[VillageManager {}] Auto-scan: registered {} pre-existing building(s).",
                    village.shortId(), registered);
        }
    }

    private boolean tryAutoRegisterRoom(ServerLevel sl, BlockPos doorBase) {
        AABB searchBox = new AABB(
                doorBase.getX() - 3, doorBase.getY() - 1, doorBase.getZ() - 3,
                doorBase.getX() + 4, doorBase.getY() + 6, doorBase.getZ() + 4);
        for (ItemFrame frame : sl.getEntitiesOfClass(ItemFrame.class, searchBox)) {
            if (buildings.containsKey(frame.getUUID())) continue;
            ItemStack itemStack = frame.getItem();
            if (itemStack.isEmpty() || !ItemFrameTokenRegistry.isBuildingToken(itemStack)) continue;
            BlockPos support = frame.blockPosition().relative(frame.getDirection().getOpposite());
            if (!doorBase.equals(findDoorNear(sl, support))) continue;
            LOGGER.info("[VillageManager {}] Auto-scan: frame at {}     door {}.",
                    village.shortId(), frame.blockPosition(), doorBase);
            tryCreateBuildingFromScan(sl, frame);
            return true;
        }
        return false;
    }

    /**
     * Auto-discovers marked rock outcrops (mines).
     *
     * <p>Neither of the other two scans works here. There is no door and no
     * gate to walk the nav graph looking for — the marker hangs on a block,
     * which the graph never visits because you cannot stand in it. So this one
     * goes the other way round: sweep the graph's own bounds for item frames.
     */
    private void scanExistingOutcrops(ServerLevel sl) {
        NavGraph navGraph = village.getGraph();
        AABB bounds = new AABB(
                navGraph.getBoundsMinX(), navGraph.getBoundsMinY() - 4, navGraph.getBoundsMinZ(),
                navGraph.getBoundsMaxX() + 1, navGraph.getBoundsMaxY() + 4,
                navGraph.getBoundsMaxZ() + 1);

        int registered = 0;
        for (ItemFrame frame : sl.getEntitiesOfClass(ItemFrame.class, bounds)) {
            if (buildings.containsKey(frame.getUUID())) continue;
            if (!ItemFrameTokenRegistry.isOutcropToken(frame.getItem())) continue;

            LOGGER.info("[VillageManager {}] Auto-scan: mine frame at {}.",
                    village.shortId(), frame.blockPosition());
            tryCreateBuilding(sl, frame);
            registered++;
        }
        if (registered > 0) {
            LOGGER.info("[VillageManager {}] Auto-scan: registered {} outcrop(s).",
                    village.shortId(), registered);
        }
    }

    /**
     * Auto-discovers open-air Merchant Stalls, which the door scan cannot see.
     *
     * <p>Like outcrops, sweeps the graph's bounds for Stall tokens not yet
     * registered. Only frames whose square already has enough walkable tiles
     * are tried, so a half-built stall is not re-announced as a failure after
     * every graph update.
     */
    private void scanExistingOpenStalls(ServerLevel sl) {
        NavGraph navGraph = village.getGraph();
        AABB bounds = new AABB(
                navGraph.getBoundsMinX(), navGraph.getBoundsMinY() - 4, navGraph.getBoundsMinZ(),
                navGraph.getBoundsMaxX() + 1, navGraph.getBoundsMaxY() + 4,
                navGraph.getBoundsMaxZ() + 1);

        int registered = 0;
        for (ItemFrame frame : sl.getEntitiesOfClass(ItemFrame.class, bounds)) {
            if (buildings.containsKey(frame.getUUID())) continue;
            // A watchtower token has no door or gate for the other scans to find
            // it by. Tried here too, quietly: after a world load a tower that
            // failed before starts by itself once it is fixed.
            if (ItemFrameTokenRegistry.isTowerToken(frame.getItem())) {
                // A saved tower is restored silently from its marker instead.
                if (!savedBuildingMarkers.containsKey(frame.getUUID())) tryCreateBuildingFromScan(sl, frame);
                continue;
            }
            if (ItemFrameTokenRegistry.tokenType(frame.getItem())
                    != ItemFrameTokenRegistry.TokenType.STALL) continue;
            if (MerchantStallBuilding.openAreaTiles(navGraph, frame.blockPosition(),
                    frame.getDirection()).size() < MerchantStallBuilding.MIN_OPEN_TILES) continue;

            LOGGER.info("[VillageManager {}] Auto-scan: stall frame at {}.",
                    village.shortId(), frame.blockPosition());
            tryCreateBuilding(sl, frame);
            if (buildings.containsKey(frame.getUUID())) registered++;
        }
        if (registered > 0) {
            LOGGER.info("[VillageManager {}] Auto-scan: registered {} stall(s).",
                    village.shortId(), registered);
        }
    }

    /**
     * Auto-discovers farm/ranch enclosures the way {@code scanExistingBuildings}
     * discovers rooms: every fence gate on the nav graph is checked for a
     * nearby enclosure-token frame.
     */
    private void scanExistingEnclosures(ServerLevel sl) {
        NavGraph navGraph = village.getGraph();
        Set<BlockPos> gateBases = new LinkedHashSet<>();
        for (NavNode node : navGraph.allNodes()) {
            BlockPos fp = node.feetPos();
            if (isGateBase(sl, fp)) gateBases.add(fp);
        }
        if (gateBases.isEmpty()) return;

        int registered = 0;
        for (BlockPos gateBase : gateBases) {
            if (tryAutoRegisterEnclosure(sl, gateBase)) registered++;
        }
        if (registered > 0) {
            LOGGER.info("[VillageManager {}] Auto-scan: registered {} enclosure(s).",
                    village.shortId(), registered);
        }
    }

    private boolean tryAutoRegisterEnclosure(ServerLevel sl, BlockPos gateBase) {
        AABB searchBox = new AABB(
                gateBase.getX() - 3, gateBase.getY() - 1, gateBase.getZ() - 3,
                gateBase.getX() + 4, gateBase.getY() + 4, gateBase.getZ() + 4);
        for (ItemFrame frame : sl.getEntitiesOfClass(ItemFrame.class, searchBox)) {
            if (buildings.containsKey(frame.getUUID())) continue;
            if (!ItemFrameTokenRegistry.isEnclosureToken(frame.getItem())) continue;
            BlockPos support = frame.blockPosition()
                    .relative(frame.getDirection().getOpposite());
            if (!gateBase.equals(findGateNear(sl, support))) continue;
            LOGGER.info("[VillageManager {}] Auto-scan: enclosure frame at {} gate {}.",
                    village.shortId(), frame.blockPosition(), gateBase);
            tryCreateBuildingFromScan(sl, frame);
            return true;
        }
        return false;
    }

    // =========================================================================
    // Token frames: queued interaction checks and registration
    // =========================================================================

    /**
     * Queue a frame-position check for the next tick.
     *
     * @return true if the position was not already queued
     */
    boolean queueFrameCheck(BlockPos framePos) {
        if (pendingFrameChecks.contains(framePos)) return false;
        pendingFrameChecks.add(framePos);
        return true;
    }

    /**
     * For each queued item-frame position, find the frame entity there and (if a
     * recognised item has been placed) attempt to create the appropriate building.
     * Called each tick; the 1-tick delay ensures the item is already in the frame
     * (placement occurs after the interaction event that queued the position).
     */
    void processPendingFrameChecks(ServerLevel sl) {
        // Runs every tick, so the placement sparkles and the quiet re-checks
        // of failed Watchtower and Archery Range tokens are kept going from here.
        sparkles.tick(sl);
        tickFailedTokens(sl);
        if (pendingFrameChecks.isEmpty()) return;
        List<BlockPos> toCheck = new ArrayList<>(pendingFrameChecks);
        pendingFrameChecks.clear();

        for (BlockPos framePos : toCheck) {
            // A frame waiting for a map update is looked up again every tick:
            // only its first look is logged, and the one that goes through.
            boolean quiet = frameChecksWaiting.contains(framePos);
            if (!quiet) LOGGER.info("[VillageManager {}] Processing frame check at {}.", village.shortId(), framePos);

            // Locate the ItemFrame entity at this position
            AABB box = new AABB(framePos).inflate(1.0);
            List<ItemFrame> found = sl.getEntitiesOfClass(ItemFrame.class, box);
            ItemFrame frame = found.stream()
                    .filter(f -> f.blockPosition().equals(framePos))
                    .findFirst().orElse(null);

            if (frame == null) {
                // Fallback: accept nearest frame within the inflated box (handles sub-block positioning)
                frame = found.isEmpty() ? null : found.get(0);
                if (frame == null) {
                    frameChecksWaiting.remove(framePos);
                    LOGGER.info("[VillageManager {}]      No ItemFrame entity found near {}.", village.shortId(), framePos);
                    continue;
                }
                if (!quiet) {
                    LOGGER.info("[VillageManager {}]      Using nearest frame at {} (blockPos={}).",
                            village.shortId(), frame.position(), frame.blockPosition());
                }
            }

            if (belongsToPersistentVillage(sl, frame) || !isResponsibleForFrame(sl, frame)) {
                frameChecksWaiting.remove(framePos);
                continue;
            }

            UUID      frameId   = frame.getUUID();
            ItemStack frameStack = frame.getItem();

            // A token taken out or swapped needs no route: remove the building
            // now rather than wait for the map with the placements below.
            Building existing = buildings.get(frameId);
            if (existing != null
                    && (!ItemFrameTokenRegistry.savedTypeMatches(buildingType(existing), frameStack)
                    || !acceptTokenFrame(sl, frame))) {
                frameChecksWaiting.remove(framePos);
                if (VillageTokenBinding.belongsToAnotherVillage(frameStack, village)) {
                    TokenFeedback.announceForeignVillageToken(sl, frame.blockPosition());
                }
                removeBuilding(frameId, sl,
                        "item removed or changed in frame " + frameId.toString().substring(0, 8),
                        tokenOut(), frame);
                LOGGER.info("[VillageManager {}]      Building removed     item taken from frame {} at {}.",
                        village.shortId(), frameId.toString().substring(0, 8), framePos);
                continue;
            }

            // A recently changed path has not yet been copied into the graph.
            // Retry after the scheduled rebuild instead of reporting a false disconnection.
            if (village.hasPendingNavRebuild()
                    && ItemFrameTokenRegistry.tokenType(frame.getItem())
                            != ItemFrameTokenRegistry.TokenType.TOWN_HALL) {
                if (frameChecksWaiting.add(framePos)) {
                    LOGGER.info("[VillageManager {}]      Waiting for the map to update before checking it.",
                            village.shortId());
                }
                pendingFrameChecks.add(framePos);
                continue;
            }
            if (frameChecksWaiting.remove(framePos)) {
                LOGGER.info("[VillageManager {}] Processing frame check at {} (the map is up to date now).",
                        village.shortId(), framePos);
            }

            // Still the same token: the player asked about this building.
            if (existing != null) {
                boolean wasValid = existing.isValid();
                boolean wasAccessible = existing.isAccessValid();
                existing.rebuild(sl, village.getGraph(), rivalEntries(frameId));
                // A player asked about this building: answer now, no grace period.
                accessLostSince.remove(existing);
                existing.setAccessValid(accessFailure(existing) == null);
                boolean valid = existing.isValid();
                ItemFrameTokenRegistry.setRegisteredGlint(frame, valid);
                // Frame interaction events can arrive more than once (and
                // more than one manager can see a frame). Only report a
                // changed registration state, not every rescan.
                if (wasValid != valid) {
                    if (existing.isShapeValid() && wasAccessible != existing.isAccessValid()) {
                        announceAccessChange(sl, existing, valid);
                    } else {
                        logValidity(sl, existing, valid);
                        announceBuildingOutcome(sl, frame, existing, valid);
                    }
                }
                LOGGER.info("[VillageManager {}]      Frame {} already registered as a building; item still present.", village.shortId(), frameId.toString().substring(0, 8));
                continue;
            }

            if (frameStack.isEmpty()) {
                LOGGER.info("[VillageManager {}]      Frame at {} has no item yet (interaction may not have completed).", village.shortId(), framePos);
                continue;
            }

            LOGGER.info("[VillageManager {}]      Frame item: {}, facing: {}",
                    village.shortId(), frameStack.getItem(), frame.getDirection());
            tryCreateBuilding(sl, frame);
        }
    }

    /**
     * Re-register a Town Hall whose item-frame entity loaded after the manager.
     * This is intentionally local and does not rebuild the village.
     */
    boolean registerLoadedTownHall(ServerLevel sl, ItemFrame frame) {
        if (!acceptTokenFrame(sl, frame)) return false;
        UUID frameId = frame.getUUID();
        if (hasValidTownHall(frameId)) return true;
        SavedBuilding saved = savedBuildingMarkers.get(frameId);
        if (saved != null && "town_hall".equals(saved.type()) && !buildings.containsKey(frameId)
                && frame.blockPosition().equals(saved.itemFramePos())) {
            // A world load: the frame's entity arrived after the restore pass
            // had already skipped the hall. Restore it from its saved marker,
            // without the new-placement chat or a rescan of every other room.
            // Not ready yet: the tick's restore pass picks it up, silently.
            if (!sl.hasChunkAt(saved.doorPos()) || village.getGraph() == null) return false;
            Building restored = createBuildingFromSaved(sl, saved, frame);
            if (restored != null) {
                installRestoredBuilding(sl, frameId, restored, frame);
                village.beds().hydrateSavedBedAssignments();
                village.beds().reconcileBedAssignments();
                LOGGER.info("[VillageManager {}] Restored Town Hall {} from its saved marker as its frame loaded ({}).",
                        village.shortId(), frameId.toString().substring(0, 8),
                        restored.isValid() ? "valid" : "not valid yet");
                return restored.isValid();
            }
            // Door or room no longer matches the saved marker: the hall really
            // changed, so register it afresh and tell the player.
        }
        tryCreateBuilding(sl, frame);
        return hasValidTownHall(frameId);
    }

    /**
     * Attempt to create a building from the item placed in {@code frame}.
     * Finds the nearest door, runs the room BFS, instantiates the right subclass,
     * validates it, and stores it in {@link #buildings}.
     */
    private void tryCreateBuilding(ServerLevel sl, ItemFrame frame) {
        if (belongsToPersistentVillage(sl, frame)) return;
        if (!isResponsibleForFrame(sl, frame)) return;
        if (!ItemFrameTokenRegistry.isToken(frame.getItem())
                || ItemFrameTokenRegistry.isGuardPostToken(frame.getItem())) return;
        if (!acceptTokenFrame(sl, frame)) {
            ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
            announceFailure(sl, frame, village.contains(frame.blockPosition())
                    ? RegistrationFailure.WRONG_TOWN_HALL : RegistrationFailure.OUTSIDE_VILLAGE,
                    frame.blockPosition());
            return;
        }
        BlockPos framePos     = frame.blockPosition();
        Direction facingOut   = frame.getDirection();       // direction the frame faces (outward)
        Direction interiorDir = facingOut.getOpposite();    // direction into the room
        // The wall block the frame is physically attached to
        BlockPos supportBlock = framePos.relative(facingOut.getOpposite());

        LOGGER.info("[VillageManager {}]      tryCreateBuilding: frame={} facing={} support={}",
                village.shortId(), framePos, facingOut, supportBlock);

        // Fenced ranch enclosures have a gate instead of a door and no
        // room to scan — they take a completely separate path.
        // An Archery Range token beside a door (and no gate) is an indoor range:
        // it carries on to the room scan below instead.
        boolean rangeRoom = ItemFrameTokenRegistry.tokenType(frame.getItem())
                == ItemFrameTokenRegistry.TokenType.ARCHERY_RANGE
                && findGateNear(sl, supportBlock) == null && findDoorNear(sl, supportBlock) != null;
        if (ItemFrameTokenRegistry.isEnclosureToken(frame.getItem()) && !rangeRoom) {
            tryCreateEnclosure(sl, frame, supportBlock, interiorDir);
            return;
        }

        // Outcrops (mine) have neither. The block the frame is nailed to *is*
        // the building.
        if (ItemFrameTokenRegistry.isOutcropToken(frame.getItem())) {
            tryCreateOutcrop(sl, frame, supportBlock, interiorDir);
            return;
        }

        // A watchtower is the platform the token hangs on.
        if (ItemFrameTokenRegistry.isTowerToken(frame.getItem())) {
            tryCreateWatchtower(sl, frame, facingOut);
            return;
        }

        // A Stall token without a sealed room behind a door is an open-air
        // stall instead, like a guard post: the square in front of the token.
        boolean stallToken = ItemFrameTokenRegistry.tokenType(frame.getItem())
                == ItemFrameTokenRegistry.TokenType.STALL;

        //        1. Locate door
        BlockPos doorBase = findDoorNear(sl, supportBlock);
        if (doorBase == null) {
            if (stallToken) {
                tryCreateOpenStall(sl, frame, supportBlock, facingOut);
                return;
            }
            LOGGER.info("[VillageManager {}]      FAIL: no door found near support block {}. "
                    + "Checked below(1-3) and horizontal neighbours  below(1-3).", village.shortId(), supportBlock);
            announceFailure(sl, frame, RegistrationFailure.ROOM_NO_DOOR, supportBlock);
            return;
        }
        LOGGER.info("[VillageManager {}]      Door found at {}.", village.shortId(), doorBase);

        //        2. Room BFS
        NavGraph navGraph = village.getGraph();
        java.util.List<BlockPos> rivals = rivalEntries(frame.getUUID());
        AccessibleRoom room = scanAccessibleRoom(sl, navGraph, doorBase, interiorDir,
                rivals, village.getBellPos());
        Set<BlockPos> roomBlocks = room.tiles();
        if ((roomBlocks == null || roomBlocks.isEmpty()) && stallToken
                && (room.failure() == RegistrationFailure.ROOM_NO_CEILING
                || room.failure() == RegistrationFailure.ROOM_OPEN_TO_OUTSIDE
                || room.failure() == RegistrationFailure.ROOM_TOO_LARGE
                || room.failure() == RegistrationFailure.ROOM_NOT_ASSIGNED)) {
            tryCreateOpenStall(sl, frame, supportBlock, facingOut);
            return;
        }
        if (roomBlocks == null || roomBlocks.isEmpty()) {
            LOGGER.info("[VillageManager {}]      FAIL: room scan found 0 nodes from door {}. "
                    + "Check that the navgraph was rebuilt after the door was placed "
                    + "(run /villager graph scan to rebuild).", village.shortId(), doorBase);
            announceFailure(sl, frame, room.failure(), room.problemPos());
            return;
        }
        interiorDir = room.direction();
        LOGGER.info("[VillageManager {}]      Room scan: {} nodes.", village.shortId(), roomBlocks.size());

        //        3. Identify building type
        UUID  frameId = frame.getUUID();
        ItemStack markerStack = frame.getItem();
        ItemFrameTokenRegistry.TokenType tokenType = ItemFrameTokenRegistry.tokenType(markerStack);
        Building building;

        if (tokenType == ItemFrameTokenRegistry.TokenType.TOWN_HALL) {
            for (Building existing : buildings.values()) {
                if (existing instanceof TownHallBuilding
                        && !existing.getItemFrameId().equals(frameId)) {
                    LOGGER.info("[VillageManager {}]      REJECTED: village already has Town Hall {}.",
                            village.shortId(), existing.getItemFrameId().toString().substring(0, 8));
                    announceFailure(sl, frame, RegistrationFailure.DUPLICATE_TOWN_HALL, doorBase);
                    return;
                }
            }
        }

        if (tokenType != null && tokenType.isHouse()) {
            building = new HouseBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks), tokenType.housingCapacity());
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.TOWN_HALL) {
            building = new TownHallBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.STORAGE) {
            building = new StorageBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.KITCHEN) {
            building = new KitchenBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.BUTCHERY) {
            building = new ButcheryBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.FORGE) {
            building = new ForgeBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.MINE) {
            building = new MineBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.TAVERN) {
            building = new TavernBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.LIBRARY) {
            building = new LibraryBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.SCHOOL) {
            building = new SchoolBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.STALL) {
            building = new MerchantStallBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.ARCHERY_RANGE) {
            building = new ArcheryRangeBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks), true);
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.BARRACKS) {
            building = new BarracksBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else if (tokenType == ItemFrameTokenRegistry.TokenType.MAGE_TOWER) {
            building = new MageTowerBuilding(frameId, doorBase, framePos, interiorDir,
                    new java.util.LinkedHashSet<>(roomBlocks));
        } else {
            LOGGER.info("[VillageManager {}]      FAIL: item {} is not a recognised building trigger "
                            + "(need a house / town_hall / storage_room / kitchen / forge / mine token).",
                    village.shortId(), markerStack.getItem());
            return;
        }

        //        4. Validate & register
        boolean valid = building.validate(sl);
        buildings.put(frameId, building);
        saveNewMarker(sl, frameId, building, valid);
        if (building instanceof MineBuilding) village.onMineLayoutChanged();
        // A new door changes where the boundary between rooms falls, and the
        // buildings already registered were scanned without knowing about it.
        // The house next door may have been counting this room's beds; make it
        // look again. Only rooms near this one can be affected.
        markNeighboursDirty(building, frameId, "registered");
        village.beds().reconcileBedAssignments();
        village.markPersistentDataDirty();

        ItemFrameTokenRegistry.setRegisteredGlint(frame, valid);
        // During first-time village initialization, VillageFounding sends a
        // combined founding message with the accepted Town Hall features. Once
        // initialization is over, registering the hall again gets the standard
        // placement feedback, including when the original frame is reused.
        boolean foundingTownHall = valid
                && building instanceof TownHallBuilding
                && suppressTownHallRegistrationFeedback;
        if (!foundingTownHall) announceBuildingOutcome(sl, frame, building, valid);
        if (valid && building instanceof TownHallBuilding townHall) {
            village.mayors().onTownHallRegistered(sl, townHall);
        }

        LOGGER.info("[VillageManager {}] {} {} at {} (frame={}, room={} nodes, funcBlocks={}).",
                village.shortId(),
                valid ? "VALID" : "INCOMPLETE",
                building.getClass().getSimpleName(),
                framePos,
                frameId.toString().substring(0, 8),
                roomBlocks.size(),
                building.getFunctionalBlocks().size());
    }

    /** The command/debug manager must not inspect a real village's token frames. */
    private boolean belongsToPersistentVillage(ServerLevel sl, ItemFrame frame) {
        return village == VillageManager.INSTANCE
                && VillageManagerRegistry.get(sl.getServer())
                        .findVillageAt(sl, frame.blockPosition()) != null;
    }

    /** Only the village at the frame may accept or reject it; the registry
     * broadcasts interaction checks to every manager in the dimension. */
    private boolean isResponsibleForFrame(ServerLevel sl, ItemFrame frame) {
        VillageManager atFrame = VillageManagerRegistry.get(sl.getServer())
                .findVillageAt(sl, frame.blockPosition());
        if (atFrame != null) return atFrame == village;
        if (village == VillageManager.INSTANCE) return village.contains(frame.blockPosition());
        VillageTokenBinding binding = VillageTokenBinding.read(frame.getItem());
        return binding != null && binding.townHallId().equals(village.getVillageId());
    }

    /**
     * Registers a mine from its frame marker, regardless of the anchor block.
     *
     * <p>Everything else a room building needs — a walkable approach, a
     * door, a sealed shell, a ceiling — has no meaning here.
     */
    private void tryCreateOutcrop(ServerLevel sl, ItemFrame frame,
                                  BlockPos supportBlock, Direction shaftDirection) {
        BlockPos mouth = supportBlock.below(2);
        Set<BlockPos> approach =
                MineBuilding.approachTiles(village.getGraph(), mouth, shaftDirection);

        RegistrationFailure accessFailure = BuildingAccess.mine(village.getGraph(),
                village.getBellPos(), approach);
        if (accessFailure != null) {
            announceFailure(sl, frame, accessFailure, mouth);
            return;
        }

        UUID frameId = frame.getUUID();
        Building building = new MineBuilding(frameId, supportBlock, frame.blockPosition(),
                shaftDirection, new LinkedHashSet<>(approach));

        boolean valid = building.validate(sl);
        buildings.put(frameId, building);
        saveNewMarker(sl, frameId, building, valid);
        village.onMineLayoutChanged();
        village.markPersistentDataDirty();
        ItemFrameTokenRegistry.setRegisteredGlint(frame, valid);
        announceTokenOutcome(sl, frame, valid, null);

        LOGGER.info("[VillageManager {}] {} Mine at anchor {} (shaft runs {}, approach={}).",
                village.shortId(), valid ? "VALID" : "INCOMPLETE", supportBlock,
                shaftDirection, approach.size());
    }

    /**
     * Registers a Watchtower: the walkable platform around its token, reached
     * from the village, at least {@link WatchtowerBuilding#MIN_HEIGHT} blocks
     * above the ground around it. A token too low, or with no floor a villager
     * can reach, is refused with the reason.
     */
    private void tryCreateWatchtower(ServerLevel sl, ItemFrame frame, Direction facing) {
        NavGraph graph = village.getGraph();
        if (graph == null || graph.isEmpty()) {
            announceFailure(sl, frame, RegistrationFailure.PATH_GRAPH_UNAVAILABLE, frame.blockPosition());
            return;
        }
        Set<BlockPos> platform = WatchtowerBuilding.platformTiles(graph, frame.blockPosition());
        if (platform.isEmpty()) {
            announceFailure(sl, frame, RegistrationFailure.TOWER_NO_PLATFORM, frame.blockPosition());
            return;
        }
        if (BuildingAccess.connectedStallTiles(graph, village.getBellPos(), platform).isEmpty()) {
            // Green on the floor it did find: that part is right, only the way up is missing.
            announceFailure(sl, frame, RegistrationFailure.TOWER_UNREACHABLE, frame.blockPosition(), 0, 0,
                    PlacementHelp.TowerLow.SHORT, PlacementSparkles.onFloors(platform), List.of());
            return;
        }

        UUID frameId = frame.getUUID();
        WatchtowerBuilding building = new WatchtowerBuilding(frameId, frame.blockPosition(),
                frame.blockPosition(), facing, new LinkedHashSet<>(platform));
        boolean valid = building.validate(sl);
        if (!building.isHighEnough()) {
            LOGGER.info("[VillageManager {}]      FAIL: watchtower at {} is only {} block(s) up (need {}).",
                    village.shortId(), frame.blockPosition(), building.getHeightAboveGround(),
                    WatchtowerBuilding.MIN_HEIGHT);
            // Said the plainest way for what was found, with the floor in green
            // and the ground it was measured against in red.
            List<BlockPos> floorAbove = WatchtowerBuilding.highFloorAbove(graph, frame.blockPosition());
            PlacementHelp.TowerLow low;
            List<Vec3> good;
            List<Vec3> bad;
            if (building.getHeightAboveGround() <= 1 && !floorAbove.isEmpty()) {
                low = PlacementHelp.TowerLow.TOKEN_AT_BOTTOM;
                good = PlacementSparkles.onFloors(floorAbove);
                bad = List.of(PlacementSparkles.inBlock(frame.blockPosition()));
            } else if (building.isBoxedIn()) {
                low = PlacementHelp.TowerLow.BOXED_IN;
                good = PlacementSparkles.onFloors(platform);
                bad = PlacementSparkles.onFloors(building.getBlockedSamples());
            } else if (building.isAtGroundLevel()) {
                low = PlacementHelp.TowerLow.ON_GROUND;
                good = List.of();
                bad = List.of(PlacementSparkles.inBlock(frame.blockPosition()));
            } else if (!(floorAbove = WatchtowerBuilding.highFloorAbove(graph, frame.blockPosition(),
                    WatchtowerBuilding.MIN_HEIGHT - building.getHeightAboveGround())).isEmpty()) {
                // A tower of several floors with the token on a lower one: the
                // floor high enough is up there already, in green.
                low = PlacementHelp.TowerLow.LOWER_FLOOR;
                good = PlacementSparkles.onFloors(floorAbove);
                bad = List.of(PlacementSparkles.inBlock(frame.blockPosition()));
            } else {
                low = PlacementHelp.TowerLow.SHORT;
                good = PlacementSparkles.onFloors(platform);
                bad = PlacementSparkles.onFloors(building.getGroundSamples());
            }
            announceFailure(sl, frame, RegistrationFailure.TOWER_TOO_LOW, frame.blockPosition(),
                    Math.max(0, building.getHeightAboveGround()), WatchtowerBuilding.MIN_HEIGHT, low, good, bad);
            return;
        }
        buildings.put(frameId, building);
        saveNewMarker(sl, frameId, building, valid);
        village.markPersistentDataDirty();
        ItemFrameTokenRegistry.setRegisteredGlint(frame, valid);
        announceBuildingOutcome(sl, frame, building, valid);

        LOGGER.info("[VillageManager {}] {} Watchtower at frame {} ({} tiles, {} up, bell={}).",
                village.shortId(), valid ? "VALID" : "INCOMPLETE", frame.blockPosition(),
                platform.size(), building.getHeightAboveGround(), building.getBell());
    }

    /**
     * Registers an open-air Merchant Stall: the square in front of a Stall
     * token that has no sealed room behind a door. The walkable tiles of that
     * square are the whole building; see {@link MerchantStallBuilding}.
     */
    private void tryCreateOpenStall(ServerLevel sl, ItemFrame frame,
                                    BlockPos supportBlock, Direction facing) {
        Set<BlockPos> tiles = MerchantStallBuilding.openAreaTiles(
                village.getGraph(), frame.blockPosition(), facing);
        int reachableTiles = BuildingAccess.reachableStallTiles(village.getGraph(),
                village.getBellPos(), tiles);
        if (reachableTiles < MerchantStallBuilding.MIN_OPEN_TILES) {
            LOGGER.info("[VillageManager {}]      FAIL: no sealed room beside stall frame {} and "
                            + "only {} walkable tile(s) in its open-air square (need {}).",
                    village.shortId(), frame.blockPosition(), reachableTiles,
                    MerchantStallBuilding.MIN_OPEN_TILES);
            announceFailure(sl, frame, village.getGraph() == null || village.getGraph().isEmpty()
                    ? RegistrationFailure.PATH_GRAPH_UNAVAILABLE : RegistrationFailure.STALL_TOO_FEW_TILES,
                    frame.blockPosition(), reachableTiles, MerchantStallBuilding.MIN_OPEN_TILES);
            return;
        }

        UUID frameId = frame.getUUID();
        MerchantStallBuilding building = MerchantStallBuilding.openAir(
                frameId, supportBlock, frame.blockPosition(), facing, new LinkedHashSet<>(tiles),
                village.getGraph(), village.getBellPos());

        boolean valid = building.validate(sl);
        buildings.put(frameId, building);
        saveNewMarker(sl, frameId, building, valid);
        village.markPersistentDataDirty();
        ItemFrameTokenRegistry.setRegisteredGlint(frame, valid);
        announceBuildingOutcome(sl, frame, building, valid);

        LOGGER.info("[VillageManager {}] {} open-air MerchantStall at frame {} ({} tiles, barrels={}).",
                village.shortId(), valid ? "VALID" : "INCOMPLETE", frame.blockPosition(),
                tiles.size(), building.getFunctionalBlocks().size());
    }

    /**
     * Creates a ranch or archery range from a token frame hung on the fence beside the
     * enclosure's gate. The gate plays the role the door plays for rooms: the
     * scan enters through it, and breaking it unregisters the building.
     */
    private void tryCreateEnclosure(ServerLevel sl, ItemFrame frame,
                                    BlockPos supportBlock, Direction interiorDir) {
        BlockPos gateBase = findGateNear(sl, supportBlock);
        if (gateBase == null) {
            LOGGER.info("[VillageManager {}]      FAIL: no fence gate near support block {}. "
                            + "Enclosure tokens must hang on the fence beside the gate.",
                    village.shortId(), supportBlock);
            announceFailure(sl, frame, RegistrationFailure.RANCH_NO_GATE, supportBlock);
            return;
        }

        Set<BlockPos> tiles = null;
        RegistrationFailure accessFailure = null;
        Direction accessFailureDirection = null;
        RegistrationFailure shapeFailure = null;
        BlockPos shapeFailurePos = gateBase;
        Direction.Axis gateAxis = sl.getBlockState(gateBase)
                .getValue(FenceGateBlock.FACING).getAxis();
        List<Direction> directions = new ArrayList<>();
        if (interiorDir.getAxis() == gateAxis) directions.add(interiorDir);
        for (Direction candidate : Direction.Plane.HORIZONTAL) {
            if (candidate.getAxis() == gateAxis && !directions.contains(candidate)) {
                directions.add(candidate);
            }
        }
        for (Direction candidate : directions) {
            EnclosureScanner.ScanResult scan = EnclosureScanner.scanDetailed(sl, gateBase, candidate);
            if (!scan.found()) {
                if (shapeFailure == null) {
                    shapeFailure = scan.failure();
                    shapeFailurePos = gateBase.relative(candidate);
                }
                continue;
            }
            RegistrationFailure access = BuildingAccess.ranch(village.getGraph(), village.getBellPos(),
                    gateBase, candidate, scan.tiles());
            if (access == null) { tiles = scan.tiles(); interiorDir = candidate; break; }
            if (accessFailure == null) {
                accessFailure = access;
                accessFailureDirection = candidate;
            }
        }
        if (tiles == null || tiles.isEmpty()) {
            LOGGER.info("[VillageManager {}]      FAIL: enclosure scan from gate {} found no "
                            + "closed pen (hole in the fence, or larger than {} tiles?).",
                    village.shortId(), gateBase, EnclosureScanner.MAX_TILES);
            RegistrationFailure reason = accessFailure != null ? accessFailure
                    : shapeFailure != null ? shapeFailure : RegistrationFailure.RANCH_OPEN_OR_TOO_LARGE;
            BlockPos problemPos = reason == RegistrationFailure.RANCH_BLOCKED_ENTRANCE
                    ? shapeFailurePos
                    : reason == RegistrationFailure.RANCH_GATE_UNREACHABLE
                            && accessFailureDirection != null
                    ? gateBase.relative(accessFailureDirection.getOpposite()) : gateBase;
            announceFailure(sl, frame, reason, problemPos);
            return;
        }

        UUID frameId = frame.getUUID();
        ItemFrameTokenRegistry.TokenType tokenType =
                ItemFrameTokenRegistry.tokenType(frame.getItem());
        Building building = tokenType == ItemFrameTokenRegistry.TokenType.ARCHERY_RANGE
                ? new ArcheryRangeBuilding(frameId, gateBase, frame.blockPosition(), interiorDir,
                        new LinkedHashSet<>(tiles))
                : new RanchBuilding(frameId, gateBase, frame.blockPosition(), interiorDir,
                        new LinkedHashSet<>(tiles));

        boolean valid = building.validate(sl);
        buildings.put(frameId, building);
        saveNewMarker(sl, frameId, building, valid);
        village.markPersistentDataDirty();
        ItemFrameTokenRegistry.setRegisteredGlint(frame, valid);
        if (valid && building instanceof ArcheryRangeBuilding range && tokenType != null) {
            TokenFeedback.announceRegistered(sl, frame.blockPosition(), tokenType,
                    BuildingRegistrationMessages.forRange(range, villageHasNoArcher()));
            showLanes(sl, range);
            triesByType.remove(tokenType);
        } else {
            announceTokenOutcome(sl, frame, valid, null);
        }

        LOGGER.info("[VillageManager {}] {} {} at gate {} ({} tiles, funcBlocks={}).",
                village.shortId(), valid ? "VALID" : "INCOMPLETE",
                building.getClass().getSimpleName(), gateBase,
                tiles.size(), building.getFunctionalBlocks().size());
    }

    /**
     * Reports a building crossing between valid and invalid to nearby players.
     *
     * <p>The placement message says what is missing; this is the other half —
     * the player puts the anvil in and hears that the Forge is now a Forge,
     * without having to walk back out and look for a glint on the frame. It
     * also catches the reverse: somebody mining out the last chest of a storage
     * room is told, rather than wondering later why nobody deposits anything.
     */
    private void announceBuildingChange(ServerLevel sl, Building building, boolean nowValid) {
        logValidity(sl, building, nowValid);
        ItemFrame frame = ItemFrameTokenRegistry.findFrameAt(sl, building.getItemFramePos());
        if (frame == null) return;
        announceBuildingOutcome(sl, frame, building, nowValid);
    }

    /**
     * Reports the outcome for a building that was scanned. A Merchant Stall
     * also says which of its two shapes it took, since one token makes either.
     */
    private void announceBuildingOutcome(ServerLevel sl, ItemFrame frame, Building building,
                                         boolean valid) {
        ItemFrameTokenRegistry.TokenType type =
                ItemFrameTokenRegistry.tokenType(frame.getItem());
        if (type == null) return;
        if (quietChecks && !valid) return;
        if (valid && building instanceof MerchantStallBuilding stall) {
            TokenFeedback.announceRegistered(sl, frame.blockPosition(), type,
                    BuildingRegistrationMessages.forStall(stall.isOpenAir(),
                            stall.getNavMeshBlocks().size()));
            return;
        }
        if (valid && building instanceof com.alex.test.village.building.SchoolBuilding school) {
            TokenFeedback.announceRegistered(sl, frame.blockPosition(), type,
                    BuildingRegistrationMessages.forSchool(sl, village.getGraph(), school));
            return;
        }
        if (valid && type == ItemFrameTokenRegistry.TokenType.MINE
                && building instanceof MineBuilding mine) {
            TokenFeedback.announceRegistered(sl, frame.blockPosition(), type,
                    BuildingRegistrationMessages.forMine(mine.mouth().getY()));
            return;
        }
        if (valid && building instanceof ArcheryRangeBuilding range) {
            TokenFeedback.announceRegistered(sl, frame.blockPosition(), type,
                    BuildingRegistrationMessages.forRange(range, villageHasNoArcher()));
            showLanes(sl, range);
            triesByType.remove(type);
            return;
        }
        if (valid && building instanceof WatchtowerBuilding tower) {
            TokenFeedback.announceRegistered(sl, frame.blockPosition(), type,
                    BuildingRegistrationMessages.forTower(tower, villageHasNoArcher(), towerFloorCutOff(tower)));
            sparkles.show(sl.getGameTime(), PlacementSparkles.onFloors(tower.getNavMeshBlocks()), List.of());
            triesByType.remove(type);
            return;
        }
        TokenFeedback.announcePlacement(sl, frame.blockPosition(), type, valid, null);
        net.minecraft.network.chat.Component cramped = valid && building.countsCrowding()
                ? BuildingRegistrationMessages.forSpace(building) : null;
        if (cramped != null) TokenFeedback.announce(sl, frame.blockPosition(), cramped);
        if (valid && building instanceof BarracksBuilding) {
            com.alex.test.village.chat.CaptainNotices.barracksReady(sl, village, frame.blockPosition());
        }
    }

    /**
     * Reports a token placement to whoever is standing nearby.
     *
     * <p>Every registration outcome funnels through here so the player is never
     * left with a frame that silently did nothing. A structure that scanned but
     * lacks furniture gets the full requirement list; one that never found its
     * room gets the reason instead.
     */
    private void announceTokenOutcome(ServerLevel sl, ItemFrame frame, boolean valid,
                                       @Nullable String problem) {
        ItemFrameTokenRegistry.TokenType type =
                ItemFrameTokenRegistry.tokenType(frame.getItem());
        if (type == null) return;
        if (quietChecks && !valid) return;
        if (valid && type == ItemFrameTokenRegistry.TokenType.MINE) {
            Integer shaftY = village.getBuilding(frame.getUUID()) instanceof MineBuilding mine
                    ? mine.mouth().getY() : null;
            TokenFeedback.announceRegistered(sl, frame.blockPosition(), type,
                    BuildingRegistrationMessages.forMine(shaftY));
            return;
        }
        TokenFeedback.announcePlacement(sl, frame.blockPosition(), type, valid, problem);
    }

    private void announceFailure(ServerLevel sl, ItemFrame frame, RegistrationFailure reason,
                                 BlockPos problemPos) {
        announceFailure(sl, frame, reason, problemPos, 0, 0);
    }

    private void announceFailure(ServerLevel sl, ItemFrame frame, RegistrationFailure reason,
                                 BlockPos problemPos, int count, int required) {
        announceFailure(sl, frame, reason, problemPos, count, required, PlacementHelp.TowerLow.SHORT, null, null);
    }

    /**
     * A failed token placement. A Watchtower or Archery Range gets plain help
     * ({@link PlacementHelp}): what is wrong, how to fix it, and a few seconds
     * of sparkles, green on what the check found ({@code good}) and red on
     * what is wrong ({@code bad}; null works it out from the reason). Every
     * other token keeps its one-line reason.
     */
    private void announceFailure(ServerLevel sl, ItemFrame frame, RegistrationFailure reason,
                                 BlockPos problemPos, int count, int required, PlacementHelp.TowerLow low,
                                 @Nullable Collection<Vec3> good, @Nullable Collection<Vec3> bad) {
        ItemFrameTokenRegistry.TokenType type = ItemFrameTokenRegistry.tokenType(frame.getItem());
        if (type == null) return;
        ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
        // Re-checked by itself while the player builds: only success is worth saying.
        if (quietChecks) return;
        if (!PlacementHelp.covers(type)) {
            TokenFeedback.announceFailure(sl, frame.blockPosition(), type, reason,
                    problemPos, count, required);
            return;
        }
        // A second failed try at this kind of building, in any frame, adds the
        // whole recipe; one that got further than the last try at this same
        // spot says the earlier part is fixed now.
        long now = sl.getGameTime();
        Tries tries = triesByType.computeIfAbsent(type, t -> new Tries());
        if (now - tries.lastFailAt > FAIL_MEMORY_TICKS || now < tries.lastFailAt) tries.fails = 0;
        boolean sameTry = tries.fails > 0 && now == tries.lastFailAt;
        if (!sameTry) tries.fails++;
        boolean recipe = tries.fails >= 2
                && (now - tries.lastRecipeAt >= RECIPE_GAP_TICKS || now < tries.lastRecipeAt);
        if (recipe) tries.lastRecipeAt = now;
        tries.lastFailAt = now;

        FailedToken failed = failedTokens.get(frame.getUUID());
        if (failed == null || failed.type != type
                || now - failed.lastFailAt > FAIL_MEMORY_TICKS || now < failed.lastFailAt) {
            failed = new FailedToken(frame.blockPosition(), type);
            failedTokens.put(frame.getUUID(), failed);
        }
        boolean closer = !sameTry && failed.lastReason != null
                && PlacementHelp.progress(reason) > PlacementHelp.progress(failed.lastReason);
        failed.lastReason = reason;
        failed.lastFailAt = now;

        PlacementHelp.Problem problem = PlacementHelp.problem(type, reason, problemPos, count, required, low);
        TokenFeedback.announceLines(sl, frame.blockPosition(), type, PlacementHelp.failureLines(
                type, reason, problem, problemPos, count, required, closer, recipe, tryAgain()));
        sparkles.show(sl.getGameTime(), good != null ? good : List.of(),
                bad != null ? bad : badSpotsFor(reason, frame.blockPosition(), problemPos));
    }

    /**
     * A token found by the background scan, which runs after every change to
     * the village map. A Watchtower or Archery Range is checked quietly: only
     * "it's ready" is said. A failed range near its gate used to repeat its
     * whole help in chat for every block the player placed while building.
     */
    private void tryCreateBuildingFromScan(ServerLevel sl, ItemFrame frame) {
        boolean wasQuiet = quietChecks;
        if (PlacementHelp.covers(ItemFrameTokenRegistry.tokenType(frame.getItem()))) quietChecks = true;
        try {
            tryCreateBuilding(sl, frame);
        } finally {
            quietChecks = wasQuiet;
        }
    }

    private static net.minecraft.network.chat.Component tryAgain() {
        return Tr.c("chat.dynamicvillages.placement.try_again",
                "When it's fixed, it starts by itself. Or right click the token to check now.");
    }

    /**
     * Looks again at Watchtower and Archery Range tokens whose last try
     * failed, quietly, so a player who fixes the build does not have to
     * click the token again: once it passes, the usual "ready" message comes.
     * Only when the village map has changed since the last look (or every
     * half minute regardless), never while the map is still catching up
     * with a change, and for half an hour after the last try. Called every
     * tick.
     */
    private void tickFailedTokens(ServerLevel sl) {
        if (failedTokens.isEmpty()) return;
        long now = sl.getGameTime();
        failedTokens.entrySet().removeIf(entry -> buildings.containsKey(entry.getKey())
                || now - entry.getValue().lastFailAt > RECHECK_FOR_TICKS || now < entry.getValue().lastFailAt);
        if (failedTokens.isEmpty()) return;
        if (now < nextRecheckAt && nextRecheckAt - now <= RECHECK_TICKS) return;
        nextRecheckAt = now + RECHECK_TICKS;
        NavGraph graph = village.getGraph();
        if (graph == null || graph.isEmpty() || village.hasPendingNavRebuild()) return;
        for (Map.Entry<UUID, FailedToken> entry : new ArrayList<>(failedTokens.entrySet())) {
            FailedToken failed = entry.getValue();
            boolean mapChanged = graph.changedSince(failed.checkedGraphVersion, failed.framePos, 32, 16);
            if (!mapChanged && now - failed.checkedAt < RECHECK_ANYWAY_TICKS && now >= failed.checkedAt) continue;
            failed.checkedGraphVersion = graph.version();
            failed.checkedAt = now;
            if (!sl.isLoaded(failed.framePos)) continue;
            ItemFrame frame = ItemFrameTokenRegistry.findExactFrameAt(sl, failed.framePos);
            if (frame == null || !frame.getUUID().equals(entry.getKey())
                    || ItemFrameTokenRegistry.tokenType(frame.getItem()) != failed.type) {
                // The token was taken down or swapped: nothing to look after.
                failedTokens.remove(entry.getKey());
                continue;
            }
            quietChecks = true;
            try {
                tryCreateBuilding(sl, frame);
            } finally {
                quietChecks = false;
            }
            if (buildings.containsKey(entry.getKey())) failedTokens.remove(entry.getKey());
        }
    }

    /** Green where a range's archers stand to shoot, red on the targets nobody can shoot at. */
    private void showLanes(ServerLevel sl, ArcheryRangeBuilding range) {
        List<Vec3> stands = new ArrayList<>();
        for (ArcheryRangeBuilding.Lane lane : range.getLanes()) stands.add(PlacementSparkles.onFloor(lane.stand()));
        List<Vec3> unused = new ArrayList<>();
        for (BlockPos target : range.getTargets()) {
            if (range.laneFor(target) == null) unused.add(PlacementSparkles.inBlock(target));
        }
        sparkles.show(sl.getGameTime(), stands, unused);
    }

    /**
     * Whether nobody in the village is an archer, as far as can be told: a
     * villager whose body is not loaded could be one, and then this says no.
     */
    private boolean villageHasNoArcher() {
        boolean unknown = false;
        for (VillagerModel model : village.getVillagers()) {
            com.alex.test.entity.NpcEntity npc = model.puppet();
            if (npc == null) {
                unknown = true;
                continue;
            }
            if (npc.getProfession() == com.alex.test.village.villagers.core.Profession.ARCHER) return false;
        }
        return !unknown;
    }

    /**
     * Whether the tower's floor carries on past the part that counts
     * ({@value WatchtowerBuilding#PLATFORM_RADIUS} blocks around the token):
     * the token hangs near one side of a bigger floor.
     */
    private static final int FLOOR_CUT_OFF_TILES = 4;

    private boolean towerFloorCutOff(WatchtowerBuilding tower) {
        NavGraph graph = village.getGraph();
        if (graph == null) return false;
        BlockPos frame = tower.getItemFramePos();
        int radius = WatchtowerBuilding.PLATFORM_RADIUS;
        int carriesOn = 0;
        for (BlockPos tile : tower.getNavMeshBlocks()) {
            if (Math.max(Math.abs(tile.getX() - frame.getX()), Math.abs(tile.getZ() - frame.getZ())) != radius) {
                continue;
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = tile.relative(dir);
                if (Math.max(Math.abs(next.getX() - frame.getX()), Math.abs(next.getZ() - frame.getZ())) <= radius) {
                    continue;
                }
                NavNode node = graph.nodeAt(next.getX(), next.getY(), next.getZ());
                if (node != null && node.isRoutable() && !node.climb) {
                    carriesOn++;
                    break;
                }
            }
        }
        // A whole side carrying on, not a bridge or a wall walk 1 to 3 wide:
        // those joined a platform and wrongly said to move the token.
        return carriesOn >= FLOOR_CUT_OFF_TILES;
    }

    /** Where to show red for a failure that names a place: the token, or the spot it points at. */
    private static List<Vec3> badSpotsFor(RegistrationFailure reason, BlockPos framePos, BlockPos problemPos) {
        return switch (reason) {
            case RANCH_NO_GATE, ROOM_NO_DOOR, TOWER_NO_PLATFORM, OUTSIDE_VILLAGE, WRONG_TOWN_HALL ->
                    List.of(PlacementSparkles.inBlock(framePos));
            case RANCH_BLOCKED_ENTRANCE, RANCH_GATE_UNREACHABLE, RANCH_CANNOT_ENTER_GATE, ROOM_NO_CEILING,
                 ROOM_BAD_FLOOR, ROOM_BLOCKED_HEADROOM, ROOM_UNSAFE_LEDGE, ROOM_UNREACHABLE ->
                    List.of(PlacementSparkles.inBlock(problemPos));
            default -> List.of();
        };
    }

    @Nullable
    private RegistrationFailure accessFailure(Building building) {
        NavGraph graph = village.getGraph();
        // An indoor range is a room behind a door, though its class is the yard's.
        if (building instanceof ArcheryRangeBuilding range && range.isIndoor()) {
            return BuildingAccess.room(graph, village.getBellPos(), building.getDoorPos(),
                    building.getInteriorDirection(), building.getNavMeshBlocks());
        }
        if (building instanceof EnclosureBuilding) {
            return BuildingAccess.ranch(graph, village.getBellPos(), building.getDoorPos(),
                    building.getInteriorDirection(), building.getNavMeshBlocks());
        }
        if (building instanceof MineBuilding) {
            return BuildingAccess.mine(graph, village.getBellPos(), building.getNavMeshBlocks());
        }
        if (building instanceof MerchantStallBuilding stall && stall.isOpenAir()) {
            return BuildingAccess.stall(graph, village.getBellPos(), stall.getNavMeshBlocks());
        }
        if (building instanceof WatchtowerBuilding) {
            if (graph == null || graph.isEmpty()) return RegistrationFailure.PATH_GRAPH_UNAVAILABLE;
            return BuildingAccess.connectedStallTiles(graph, village.getBellPos(),
                    building.getNavMeshBlocks()).isEmpty() ? RegistrationFailure.TOWER_UNREACHABLE : null;
        }
        return BuildingAccess.room(graph, village.getBellPos(), building.getDoorPos(),
                building.getInteriorDirection(), building.getNavMeshBlocks());
    }

    /** Central ownership check for discovery, interaction, restoration and revalidation. */
    private boolean acceptTokenFrame(ServerLevel sl, ItemFrame frame) {
        if (ItemFrameTokenRegistry.tokenType(frame.getItem())
                == ItemFrameTokenRegistry.TokenType.TOWN_HALL) {
            UUID expectedFrame = VillageManagerRegistry.get(sl.getServer()).getTownHallFrameId(village.getVillageId());
            if (expectedFrame != null && !expectedFrame.equals(frame.getUUID())) return false;
            return VillageTokenBinding.bindTownHallFrame(frame, village)
                    || VillageTokenBinding.bindSavedTownHallFrame(frame, village);
        }
        return VillageTokenBinding.bindFrame(frame, village);
    }

    // =========================================================================
    // Town Hall bootstrap scan (before a village exists)
    // =========================================================================

    private record AccessibleRoom(@Nullable Set<BlockPos> tiles, @Nullable Direction direction,
                                  @Nullable RegistrationFailure failure, BlockPos problemPos) {}

    /**
     * Share of a scanned room's tiles opening onto open ground from which the
     * other side of the door is looked at. Below it the room is walled in and
     * the second side is never scanned, so ordinary doors cost nothing extra.
     */
    private static final double PORCH_OPEN_SHARE = 0.25;

    /** The other side must be under half as open as the first to replace it. */
    private static final double ROOM_OVER_PORCH = 0.5;

    /**
     * Try the two sides of the door, keeping the most useful proven failure.
     * The frame's side is tried first. When that room is mostly open to the
     * street and the other side is a closed room, the closed one wins: a door
     * recessed into its wall has a roofed porch outside that scans as a room.
     */
    private static AccessibleRoom scanAccessibleRoom(ServerLevel sl, NavGraph graph,
                                                     BlockPos door, Direction preferred,
                                                     Collection<BlockPos> rivals, BlockPos origin) {
        Direction.Axis doorAxis = sl.getBlockState(door).getValue(DoorBlock.FACING).getAxis();
        List<Direction> directions = new ArrayList<>();
        if (preferred.getAxis() == doorAxis) directions.add(preferred);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (direction.getAxis() == doorAxis && !directions.contains(direction)) {
                directions.add(direction);
            }
        }
        RegistrationFailure best = null;
        BlockPos failurePos = door;
        AccessibleRoom chosen = null;
        double chosenOpen = 0.0;
        for (Direction direction : directions) {
            BlockPos entry = door.relative(direction);
            RoomScanner.ScanResult scan = RoomScanner.scanDetailed(sl, graph, door,
                    direction, rivals);
            RegistrationFailure failure = scan.failure();
            if (scan.found()) {
                failure = BuildingAccess.room(graph, origin, door, direction, scan.tiles());
                if (failure == null) {
                    double open = RoomScanner.openEdgeShare(sl, graph, scan);
                    if (chosen == null) {
                        chosen = new AccessibleRoom(scan.tiles(), direction, null, entry);
                        chosenOpen = open;
                        // The usual case: a walled room on the preferred side.
                        if (open < PORCH_OPEN_SHARE) return chosen;
                        continue;
                    }
                    // Both sides scan. The first is mostly open to the street, so it
                    // is a covered porch in front of a recessed door; take the other
                    // side if it is clearly more closed in.
                    if (open < chosenOpen * ROOM_OVER_PORCH) {
                        LOGGER.info("[Rooms] Door {}: {} side is {}% open ground, {} side {}%; "
                                        + "using the {} side.", door.toShortString(),
                                chosen.direction(), Math.round(chosenOpen * 100),
                                direction, Math.round(open * 100), direction);
                        return new AccessibleRoom(scan.tiles(), direction, null, entry);
                    }
                    return chosen;
                }
            }
            if (best == null || failurePriority(failure) > failurePriority(best)) {
                best = failure;
                failurePos = entry;
            }
        }
        if (chosen != null) return chosen;
        return new AccessibleRoom(null, null,
                best != null ? best : RegistrationFailure.ROOM_NOT_ASSIGNED, failurePos);
    }

    private static int failurePriority(@Nullable RegistrationFailure failure) {
        if (failure == RegistrationFailure.PATH_GRAPH_UNAVAILABLE) return 6;
        if (failure == RegistrationFailure.ROOM_UNREACHABLE) return 5;
        if (failure == RegistrationFailure.ROOM_BAD_FLOOR
                || failure == RegistrationFailure.ROOM_BLOCKED_HEADROOM
                || failure == RegistrationFailure.ROOM_UNSAFE_LEDGE) return 4;
        if (failure == RegistrationFailure.ROOM_OPEN_TO_OUTSIDE
                || failure == RegistrationFailure.ROOM_TOO_LARGE
                || failure == RegistrationFailure.ROOM_NOT_ASSIGNED) return 3;
        if (failure == RegistrationFailure.ROOM_NO_CEILING) return 2;
        return 0;
    }

    /** @see VillageManager#scanTownHallMarker */
    @Nullable
    static TownHallScan scanTownHallMarker(ServerLevel sl, ItemFrame frame, int chunkRadius) {
        return scanTownHallMarkerDetailed(sl, frame, chunkRadius).scan();
    }

    record TownHallScanResult(@Nullable TownHallScan scan,
                              @Nullable RegistrationFailure failure, BlockPos problemPos) {}

    static TownHallScanResult scanTownHallMarkerDetailed(ServerLevel sl, ItemFrame frame, int chunkRadius) {
        return scanTownHallMarkerDetailed(sl, frame, chunkRadius, null);
    }

    /**
     * @param drawnAhead the village's whole map, already drawn from this door
     *        (a generated village founding itself), checked instead of drawing
     *        a small map of its own (~45 ms in the founding tick)
     */
    static TownHallScanResult scanTownHallMarkerDetailed(ServerLevel sl, ItemFrame frame, int chunkRadius,
                                                         @Nullable NavGraph drawnAhead) {
        BlockPos framePos = frame.blockPosition();
        if (ItemFrameTokenRegistry.tokenType(frame.getItem())
                != ItemFrameTokenRegistry.TokenType.TOWN_HALL) {
            return new TownHallScanResult(null, RegistrationFailure.ROOM_NOT_ASSIGNED, framePos);
        }

        Direction interiorDir = frame.getDirection().getOpposite();
        BlockPos supportBlock = framePos.relative(frame.getDirection().getOpposite());
        BlockPos doorBase = findDoorNear(sl, supportBlock);
        if (doorBase == null) {
            LOGGER.info("[TownHallScan] FAIL: no door near support block {} "
                    + "(checked below(1-3) and horizontal neighbours).", supportBlock);
            return new TownHallScanResult(null, RegistrationFailure.ROOM_NO_DOOR, supportBlock);
        }

        int checkRadius = Math.min(chunkRadius, TOWN_HALL_CHECK_RADIUS);
        NavGraph bootstrapGraph = drawnAhead != null && !drawnAhead.isEmpty()
                ? drawnAhead : NavGraph.build(sl, doorBase, checkRadius, 0L);
        if (bootstrapGraph.isEmpty()) {
            LOGGER.info("[TownHallScan] FAIL: bootstrap nav graph from door {} is empty "
                    + "(radius={} chunks).", doorBase, checkRadius);
            return new TownHallScanResult(null, RegistrationFailure.PATH_GRAPH_UNAVAILABLE, doorBase);
        }

        AccessibleRoom room = scanAccessibleRoom(sl, bootstrapGraph, doorBase, interiorDir,
                List.of(), doorBase);
        Set<BlockPos> roomBlocks = room.tiles();
        if (roomBlocks == null || roomBlocks.isEmpty()) {
            LOGGER.info("[TownHallScan] FAIL: room scan from door {} found no interior in any "
                            + "direction. Graph: {}. Check the room is walled and has a ceiling.",
                    doorBase, bootstrapGraph.dumpStats());
            return new TownHallScanResult(null, room.failure(), room.problemPos());
        }
        interiorDir = room.direction();

        TownHallBuilding townHall = new TownHallBuilding(
                frame.getUUID(), doorBase, framePos, interiorDir,
                new LinkedHashSet<>(roomBlocks));
        if (!townHall.validate(sl)) {
            LOGGER.info("[TownHallScan] FAIL: room of {} tile(s) at door {} did not validate.",
                    roomBlocks.size(), doorBase);
            return new TownHallScanResult(null, RegistrationFailure.ROOM_NOT_ASSIGNED,
                    doorBase.relative(interiorDir));
        }
        LOGGER.info("[TownHallScan] OK: door {} interior {} room {} tile(s).",
                doorBase, interiorDir, roomBlocks.size());
        return new TownHallScanResult(new TownHallScan(doorBase, interiorDir,
                new LinkedHashSet<>(roomBlocks)), null, doorBase);
    }

    /** The door a Town Hall frame marks, found the way its room check finds it; null when there is none. */
    @Nullable
    static BlockPos townHallDoor(Level level, ItemFrame frame) {
        return findDoorNear(level, frame.blockPosition().relative(frame.getDirection().getOpposite()));
    }

    // =========================================================================
    // Entrances: doors (rooms) and fence gates (enclosures)
    // =========================================================================

    /**
     * Search for a door's lower half near {@code supportBlock} (the wall block the
     * item frame is attached to).
     *
     * <p>Search order:
     * <ol>
     *   <li>1   3 blocks directly below the support block     covers frames mounted flush
     *       with the door top, one above it, or two above it.</li>
     *   <li>4 cardinal horizontal neighbours at the same level and 1   3 below     covers
     *       frames beside the door rather than directly above it.</li>
     * </ol>
     *
     * @return the door-base {@link BlockPos} (lower half), or {@code null} if none found
     */
    @Nullable
    private static BlockPos findDoorNear(Level level, BlockPos supportBlock) {
        // 1   3 blocks below the support block
        // dy=1     frame at door-top level (common)
        // dy=2     frame one block above door top (most common "above a door" placement)
        // dy=3     frame two blocks above door top (extra headroom)
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos below = supportBlock.below(dy);
            if (isDoorBase(level, below)) return below;
        }

        // Horizontal neighbours at same level and 1   3 below (door beside the frame)
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos nb = supportBlock.relative(d);
            if (isDoorBase(level, nb)) return nb;
            for (int dy = 1; dy <= 3; dy++) {
                BlockPos nbBelow = nb.below(dy);
                if (isDoorBase(level, nbBelow)) return nbBelow;
            }
        }
        return null;
    }

    private static boolean isDoorBase(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof DoorBlock
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
    }

    /** Gate analogue of {@link #findDoorNear}: fence-gate base near the frame support. */
    @Nullable
    private static BlockPos findGateNear(Level level, BlockPos supportBlock) {
        if (isGateBase(level, supportBlock)) return supportBlock;
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos below = supportBlock.below(dy);
            if (isGateBase(level, below)) return below;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos nb = supportBlock.relative(d);
            for (int dy = 0; dy <= 3; dy++) {
                BlockPos candidate = nb.below(dy);
                if (isGateBase(level, candidate)) return candidate;
            }
        }
        return null;
    }

    private static boolean isGateBase(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock()
                instanceof net.minecraft.world.level.block.FenceGateBlock;
    }

    // =========================================================================
    // Keeping buildings current: dirty rebuilds, revalidation, removal
    // =========================================================================

    /**
     * The tiles every registered building's door opens onto, except one.
     *
     * <p>Handed to each room scan so the doors can divide shared space between
     * them. Without it, two houses in a terrace each scan both rooms and the
     * village double-counts its beds.
     */
    private java.util.List<BlockPos> rivalEntries(UUID exceptFrameId) {
        java.util.List<BlockPos> entries = new ArrayList<>();
        for (Map.Entry<UUID, Building> entry : buildings.entrySet()) {
            if (entry.getKey().equals(exceptFrameId)) continue;
            // An open-air stall has no doorway; seeding a room scan from its
            // anchor would flood the street it stands in.
            if (entry.getValue() instanceof MerchantStallBuilding stall && stall.isOpenAir()) continue;
            if (entry.getValue() instanceof WatchtowerBuilding) continue;
            entries.add(entry.getValue().entryTile());
        }
        return entries;
    }

    /**
     * Queues the rescan of every other building whose room comes near
     * {@code changed}'s: the ones whose share of a common floor a new or
     * removed door can move.
     */
    private void markNeighboursDirty(Building changed, UUID exceptFrameId, String what) {
        int marked = 0;
        for (Map.Entry<UUID, Building> entry : buildings.entrySet()) {
            if (entry.getKey().equals(exceptFrameId)) continue;
            if (!entry.getValue().roomBoxNear(changed, NEIGHBOUR_MARGIN_XZ, NEIGHBOUR_MARGIN_Y)) continue;
            dirtyBuildings.add(entry.getKey());
            marked++;
        }
        LOGGER.info("[VillageManager {}] {} {}: rescanning {} nearby building(s) of {}.",
                village.shortId(), changed.getClass().getSimpleName(), what, marked,
                buildings.size() - (buildings.containsKey(exceptFrameId) ? 1 : 0));
    }

    /**
     * Rebuild the buildings that have been flagged dirty due to a block change
     * within their bounding box, oldest first, until this tick's
     * {@link #REBUILD_BUDGET_NANOS} is spent; the rest carry over to the next
     * tick. Updates validity and syncs frame glint.
     */
    void rebuildDirtyBuildings(ServerLevel sl) {
        if (dirtyBuildings.isEmpty() || village.getGraph() == null) return;
        boolean graphStale = village.hasPendingNavRebuild();
        long start = System.nanoTime();
        passTicks++;

        while (!dirtyBuildings.isEmpty()) {
            if (System.nanoTime() - start >= REBUILD_BUDGET_NANOS) break;
            Iterator<UUID> next = dirtyBuildings.iterator();
            UUID id = next.next();
            next.remove();
            boolean followUp = navFollowUps.remove(id);
            Building b = buildings.get(id);
            if (b == null) continue;
            long t0 = System.nanoTime();
            rebuildOne(sl, id, b, graphStale, followUp);
            long took = System.nanoTime() - t0;
            passRebuilt++;
            if (followUp) passFollowUps++;
            if (took > passSlowestNanos) {
                passSlowestNanos = took;
                passSlowest = b.getClass().getSimpleName() + " at " + b.getDoorPos().toShortString();
            }
        }
        passNanos += System.nanoTime() - start;
        if (dirtyBuildings.isEmpty()) logRebuildPass();
    }

    /**
     * Timing only: one line per finished pass of rescans that cost anything
     * worth seeing (2 ms or more in all), then the counters start again.
     */
    private void logRebuildPass() {
        if (passNanos >= 2_000_000L) {
            LOGGER.info("[VillageManager {}] Building rescans: {} in {} ms over {} tick(s) "
                            + "({} after a map update), slowest {} {} ms.",
                    village.shortId(), passRebuilt,
                    String.format(Locale.ROOT, "%.1f", passNanos / 1_000_000.0), passTicks,
                    passFollowUps, passSlowest,
                    String.format(Locale.ROOT, "%.1f", passSlowestNanos / 1_000_000.0));
        }
        passRebuilt = passFollowUps = passTicks = 0;
        passNanos = passSlowestNanos = 0;
        passSlowest = "-";
    }

    /** One building's rescan and everything that follows from it. */
    private void rebuildOne(ServerLevel sl, UUID id, Building b, boolean graphStale, boolean followUp) {
        if (graphStale && !followUp) rescanAfterNavRebuild.add(id);
        ItemFrame frame = ItemFrameTokenRegistry.findExactFrameAt(sl, b.getItemFramePos());
        if (frame != null && (!frame.getUUID().equals(id)
                || !ItemFrameTokenRegistry.savedTypeMatches(buildingType(b), frame.getItem())
                || !acceptTokenFrame(sl, frame))) {
            removeBuilding(id, sl, "token ownership changed", tokenOut(), frame);
            return;
        }

        boolean wasValid = b.isValid();
        boolean wasAccessible = b.isAccessValid();
        String rangeBefore = b instanceof ArcheryRangeBuilding range ? range.summaryKey()
                : b instanceof WatchtowerBuilding tower ? tower.summaryKey() : null;
        b.rebuild(sl, village.getGraph(), rivalEntries(id));
        if (!graphStale || followUp) {
            recheckAccessWithGrace(sl, b);
        }
        boolean nowValid = b.isValid();

        // A target or fletching table placed or broken: say what the yard has now.
        if (nowValid && wasValid && b instanceof ArcheryRangeBuilding range
                && !range.summaryKey().equals(rangeBefore)) {
            ItemFrame rangeFrame = ItemFrameTokenRegistry.findFrameAt(sl, range.getItemFramePos());
            if (rangeFrame != null) {
                TokenFeedback.announce(sl, rangeFrame.blockPosition(),
                        BuildingRegistrationMessages.forRangeUpdate(range));
                // Where archers will stand now, and which targets nobody can shoot at.
                showLanes(sl, range);
            }
        }
        // A bell or railing added or taken away, or the ground dug out below.
        if (nowValid && wasValid && b instanceof WatchtowerBuilding tower
                && !tower.summaryKey().equals(rangeBefore)) {
            ItemFrame towerFrame = ItemFrameTokenRegistry.findFrameAt(sl, tower.getItemFramePos());
            if (towerFrame != null) {
                TokenFeedback.announce(sl, towerFrame.blockPosition(),
                        BuildingRegistrationMessages.forTowerUpdate(tower));
            }
        }

        if (b instanceof HouseBuilding || b instanceof BarracksBuilding || b instanceof MageTowerBuilding) {
            village.beds().reconcileBedAssignments();
        }

        if (nowValid != wasValid) {
            ItemFrameTokenRegistry.setRegisteredGlintAt(sl, b.getItemFramePos(), nowValid);
            if (b instanceof TownHallBuilding townHall) {
                if (nowValid) village.mayors().onTownHallRegistered(sl, townHall);
                else village.mayors().onTownHallRemoved(sl, townHall);
            }
            if (b.isShapeValid() && wasAccessible != b.isAccessValid()) {
                announceAccessChange(sl, b, nowValid);
            } else {
                announceBuildingChange(sl, b, nowValid);
            }
            LOGGER.info("[VillageManager {}] Building {} is now {}.",
                    village.shortId(), id.toString().substring(0, 8),
                    nowValid ? "VALID" : "INVALID");
        }
    }

    /**
     * Re-checks one building's functional blocks, round-robin, one per sweep.
     *
     * <p>{@link VillageManager#onBlockBreak} fires only for blocks a
     * <em>player</em> breaks, so a chest lost to a creeper, fire, lava, a
     * piston or a command leaves its building still flagged valid with nothing
     * behind it. The village then believes it owns a storage room it cannot
     * deposit into, and reports it as healthy to the Mayor and the job
     * assignment.
     *
     * <p>Only the functional-block scan is re-run, not the room BFS -- that is
     * what {@link #rebuildDirtyBuildings} is for, and the BFS is far too
     * expensive to repeat on a timer. Taking one building per sweep keeps the
     * cost flat however many rooms a village grows to.
     */
    void revalidateNextBuilding(ServerLevel sl) {
        if (buildings.isEmpty()) return;
        List<Building> all = new ArrayList<>(buildings.values());
        if (revalidateIndex >= all.size()) revalidateIndex = 0;
        recheck(sl, all.get(revalidateIndex++));
    }

    /**
     * One building's re-check, as {@link #revalidateNextBuilding} does it.
     * The Builder's door check comes here too (10-25): checking a building
     * itself skipped the bookkeeping the rebuild queue does.
     *
     * @return true when the building was queued for the full rebuild
     */
    boolean recheck(ServerLevel sl, Building building) {
        if (buildings.get(building.getItemFrameId()) != building) return false;
        // An unloaded chunk reads as air, which would condemn a perfectly good
        // building for being asleep.
        if (!sl.hasChunkAt(building.getDoorPos())) return false;
        ItemFrame frame = ItemFrameTokenRegistry.findExactFrameAt(sl, building.getItemFramePos());
        if (frame != null && (!frame.getUUID().equals(building.getItemFrameId())
                || !ItemFrameTokenRegistry.savedTypeMatches(buildingType(building), frame.getItem())
                || !acceptTokenFrame(sl, frame))) {
            removeBuilding(building.getItemFrameId(), sl, "token ownership changed", tokenOut(), frame);
            return false;
        }

        // A broken building is queued for the full rebuild rather than
        // re-validated here. `validate` re-runs only the functional-block scan
        // and leaves the room alone, and validity also requires a non-empty
        // room -- so once a scan has failed, `validate` can never bring the
        // building back however many times it runs. That is what left a
        // repaired farm invalid until its token was re-hung. Routing through
        // dirtyBuildings gets the room BFS *and* the bookkeeping that goes with
        // a validity change (frame glint, bed reconciliation, the chat notice).
        if (!building.isShapeValid()) {
            dirtyBuildings.add(building.getItemFrameId());
            return true;
        }

        boolean wasValid = building.isValid();
        Set<BlockPos> featuresBefore = new HashSet<>(building.getFunctionalBlocks());
        List<BlockPos> bedsBefore = separateBeds(building);
        boolean nowValid = building.validate(sl);
        if (nowValid != wasValid) {
            LOGGER.info("[VillageManager {}] {} at {} re-checked as {}.",
                    village.shortId(), building.getClass().getSimpleName(),
                    building.getDoorPos(), wasValid ? "invalid" : "valid again");
        }

        // A bed or chest came or went without any event reporting it. The
        // re-check above only refreshed the list; queue the full rebuild so
        // the room is re-read and the bookkeeping that follows a change (bed
        // claims, frame glint, the chat notice) runs too.
        if (!featuresBefore.equals(building.getFunctionalBlocks())
                || !bedsBefore.equals(separateBeds(building))) {
            LOGGER.info("[VillageManager {}] {} at {} features changed ({} -> {}); rescanning.",
                    village.shortId(), building.getClass().getSimpleName(),
                    building.getDoorPos(), featuresBefore.size(),
                    building.getFunctionalBlocks().size());
            dirtyBuildings.add(building.getItemFrameId());
            return true;
        }
        return false;
    }

    /**
     * Mark any building whose AABB (inflated by 1 block XZ, and up to the
     * highest ceiling the room scan reads) contains {@code pos} as dirty. The
     * wider range catches changes to room walls and tall ceilings that sit
     * outside the floor-tile nav-mesh AABB; see {@link Building#mayAffectRoom}.
     * Called from block-break and block-place handlers.
     */
    void markBuildingsDirtyAt(BlockPos pos) {
        for (Map.Entry<UUID, Building> entry : buildings.entrySet()) {
            if (entry.getValue().mayAffectRoom(pos)) {
                dirtyBuildings.add(entry.getKey());
                // A fresh change of its own: this rebuild is not just a
                // follow-up, and may need one after the graph catches up.
                navFollowUps.remove(entry.getKey());
            }
        }
    }

    /**
     * Called once the nav graph has been updated: rebuilds the buildings that
     * were last scanned against the graph as it stood before.
     */
    void onNavGraphRebuilt(ServerLevel sl) {
        onNavGraphRebuilt(sl, null);
    }

    /**
     * As above after a local patch: only buildings whose door sits in or
     * beside one of {@code cells} are re-checked. The patch names the cells
     * it regrouped and the cells of pieces whose Town Hall access flipped,
     * so a building cut off by a far-away edit is still among them; the
     * other thirty are left alone.
     *
     * @param cells the cells to look at, or null for every building
     */
    void onNavGraphRebuilt(ServerLevel sl, @Nullable Set<Long> cells) {
        for (UUID id : rescanAfterNavRebuild) {
            if (!buildings.containsKey(id)) continue;
            dirtyBuildings.add(id);
            navFollowUps.add(id);
        }
        rescanAfterNavRebuild.clear();

        // A distant break can sever the only street to an intact building.
        // Recheck access after each graph rebuild without rescanning every room.
        int checked = 0;
        long slowestNanos = 0;
        String slowestName = "-";
        for (Building building : buildings.values()) {
            if (!sl.hasChunkAt(building.getDoorPos())) continue;
            if (cells != null && !doorNear(building.getDoorPos(), cells)) continue;
            long t0 = System.nanoTime();
            boolean wasValid = building.isValid();
            boolean wasAccessible = building.isAccessValid();
            recheckAccessWithGrace(sl, building);
            applyAccessChange(sl, building, wasValid, wasAccessible);
            long took = System.nanoTime() - t0;
            checked++;
            if (took > slowestNanos) {
                slowestNanos = took;
                slowestName = building.getClass().getSimpleName();
            }
        }
        lastAccessRecheckTiming = checked + " checked, slowest " + slowestName + " "
                + String.format(Locale.ROOT, "%.1f", slowestNanos / 1_000_000.0) + " ms";
    }

    /** Whether the door's cell, or any of the 26 around it, is one of {@code cells}. */
    private static boolean doorNear(BlockPos door, Set<Long> cells) {
        if (cells.isEmpty()) return false;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (cells.contains(NavGraph.cellKey(door.getX() + dx * 16, door.getY() + dy * 16,
                            door.getZ() + dz * 16))) return true;
                }
            }
        }
        return false;
    }

    /** Timing only: how the last {@link #onNavGraphRebuilt} access recheck went. */
    private String lastAccessRecheckTiming = "";

    String lastAccessRecheckTiming() { return lastAccessRecheckTiming; }

    /**
     * Re-checks a building's route. Access that comes back counts at once;
     * access that is lost only counts once it has stayed lost for
     * {@link #ACCESS_LOSS_GRACE_TICKS}. Until then the building keeps its
     * access, and {@link #tickAccessGrace} looks again when the time is up.
     */
    private void recheckAccessWithGrace(ServerLevel sl, Building building) {
        if (accessFailure(building) == null) {
            if (accessLostSince.remove(building) != null) {
                LOGGER.info("[VillageManager {}] {} at {} is reachable again within the grace "
                                + "period; access kept.",
                        village.shortId(), building.getClass().getSimpleName(), building.getDoorPos());
            }
            building.setAccessValid(true);
            return;
        }
        if (!building.isAccessValid()) return;
        long now = sl.getGameTime();
        Long since = accessLostSince.putIfAbsent(building, now);
        if (since == null) {
            LOGGER.info("[VillageManager {}] {} at {} looks unreachable; waiting {} ticks "
                            + "before turning its token off.",
                    village.shortId(), building.getClass().getSimpleName(), building.getDoorPos(),
                    ACCESS_LOSS_GRACE_TICKS);
            return;
        }
        if (now - since >= ACCESS_LOSS_GRACE_TICKS) {
            accessLostSince.remove(building);
            building.setAccessValid(false);
        }
    }

    /**
     * Settles lost routes whose grace period has run out: still lost, the
     * building loses its access and the chat says so; found again, the loss
     * is forgotten without a word.
     *
     * <p>Waits while a graph update is pending. The graph is debounced, so a
     * route the player has just repaired may not be in it yet, and that
     * update re-checks every building through {@link #onNavGraphRebuilt}.
     */
    void tickAccessGrace(ServerLevel sl) {
        if (accessLostSince.isEmpty()) return;
        if (village.getGraph() == null || village.hasPendingNavRebuild()) return;
        long now = sl.getGameTime();
        List<Building> due = new ArrayList<>();
        Iterator<Map.Entry<Building, Long>> iterator = accessLostSince.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Building, Long> entry = iterator.next();
            Building building = entry.getKey();
            // Removed or replaced since the loss was seen; the same goes for
            // a building whose chunk unloaded, which the graph check skips too.
            if (buildings.get(building.getItemFrameId()) != building
                    || !sl.hasChunkAt(building.getDoorPos())) {
                iterator.remove();
                continue;
            }
            if (now - entry.getValue() >= ACCESS_LOSS_GRACE_TICKS) due.add(building);
        }
        for (Building building : due) {
            boolean wasValid = building.isValid();
            boolean wasAccessible = building.isAccessValid();
            recheckAccessWithGrace(sl, building);
            applyAccessChange(sl, building, wasValid, wasAccessible);
        }
    }

    /** Beds a room keeps apart from its furniture (Barracks, Mage Tower): a change there is a change too. */
    private static List<BlockPos> separateBeds(Building building) {
        if (building instanceof BarracksBuilding barracks) return barracks.getBeds();
        if (building instanceof MageTowerBuilding tower) return tower.getBeds();
        return List.of();
    }

    /** The bookkeeping and chat line that follow a change in a building's access. */
    private void applyAccessChange(ServerLevel sl, Building building,
                                   boolean wasValid, boolean wasAccessible) {
        boolean nowValid = building.isValid();
        if (wasValid == nowValid) return;
        ItemFrameTokenRegistry.setRegisteredGlintAt(sl, building.getItemFramePos(), nowValid);
        if (building instanceof HouseBuilding || building instanceof BarracksBuilding
                || building instanceof MageTowerBuilding) {
            village.beds().reconcileBedAssignments();
        }
        if (building instanceof TownHallBuilding townHall) {
            if (nowValid) village.mayors().onTownHallRegistered(sl, townHall);
            else village.mayors().onTownHallRemoved(sl, townHall);
        }
        if (wasAccessible != building.isAccessValid()) {
            announceAccessChange(sl, building, nowValid);
        }
    }

    private void announceAccessChange(ServerLevel sl, Building building, boolean reachable) {
        boolean noMap = village.getGraph() == null || village.getGraph().isEmpty();
        // No village map yet is the village's trouble, not the building's: no log line for that.
        if (!noMap) {
            logBuilding(sl, building, reachable ? VillageLog.Kind.BUILDING : VillageLog.Kind.BUILDING_LOST,
                    reachable ? "reachable" : "unreachable",
                    reachable ? Tr.c("log.dynamicvillages.building.reachable", "%s can be reached again", buildingName(building))
                            : Tr.c("log.dynamicvillages.building.unreachable", "%s can't be reached", buildingName(building)),
                    reachable ? null : Tr.c("log.dynamicvillages.building.unreachable.detail",
                            "Villagers can't find a way to its door. Its token is off until they can."));
        }
        ItemFrame frame = ItemFrameTokenRegistry.findExactFrameAt(sl, building.getItemFramePos());
        if (frame == null) return;
        ItemFrameTokenRegistry.TokenType type = ItemFrameTokenRegistry.tokenType(frame.getItem());
        if (type == null) return;
        TokenFeedback.announceAccessChange(sl, frame.blockPosition(), type, reachable,
                building.getDoorPos(), noMap);
    }

    // ---------------------------------------------------------------------
    // The village log (Mayor > Village Log)
    // ---------------------------------------------------------------------

    /** A building writes at most one row a minute: it can flip back and forth. */
    private static final long LOG_REPEAT_TICKS = 1200L;
    /** Per building (frame id): its last row and a newer one waiting for the minute to pass. Not saved. */
    private final Map<UUID, BuildingRows> buildingRows = new HashMap<>();
    /** Off while the village is set up from its save: what registers then is no news. */
    private boolean buildingRowsOff;

    private static final class BuildingRows {
        String lastWhat;
        long lastAt;
        @Nullable HeldRow held;
    }

    private record HeldRow(String what, VillageLog.Kind kind, Component line, @Nullable Component detail, BlockPos pos) {}

    /** Turns building rows off (true) while the village is set up from its save, and back on. */
    void setBuildingRowsOff(boolean off) {
        buildingRowsOff = off;
    }

    /** A new token's building: its marker saved, and a log line when it works from the start. */
    private void saveNewMarker(ServerLevel sl, UUID frameId, Building building, boolean valid) {
        // A marker already saved (one waiting to be restored, or the same token again) is no news.
        boolean known = savedBuildingMarkers.containsKey(frameId);
        savedBuildingMarkers.put(frameId, savedBuilding(building));
        if (valid && !known) {
            logBuilding(sl, building, VillageLog.Kind.BUILDING, "registered",
                    Tr.c("log.dynamicvillages.building.registered", "%s registered", buildingName(building)), null);
        }
    }

    /** A building that turned valid ("ready") or stopped being valid, its token still up. */
    private void logValidity(ServerLevel sl, Building building, boolean nowValid) {
        logBuilding(sl, building, nowValid ? VillageLog.Kind.BUILDING : VillageLog.Kind.BUILDING_LOST,
                nowValid ? "ready" : "stopped",
                nowValid ? Tr.c("log.dynamicvillages.building.ready", "%s is ready", buildingName(building))
                        : Tr.c("log.dynamicvillages.building.stopped", "%s stopped working", buildingName(building)),
                nowValid ? null : Tr.c("log.dynamicvillages.building.stopped.detail",
                        "It no longer has everything it needs. Its token is off until it does."));
    }

    /**
     * One building row, at most one a minute per building. A change inside that minute waits for it to
     * pass ({@link #tickBuildingRows}); a newer change replaces the waiting one, and one that brings back
     * the row already shown cancels it. So the newest row always tells the building's state.
     * The Town Hall has rows of its own (founded, paused, back: {@link VillageManagerRegistry}).
     */
    private void logBuilding(ServerLevel sl, Building building, VillageLog.Kind kind, String what,
                             Component line, @Nullable Component detail) {
        if (buildingRowsOff || building instanceof TownHallBuilding) return;
        long now = sl.getGameTime();
        BuildingRows rows = buildingRows.computeIfAbsent(building.getItemFrameId(), id -> new BuildingRows());
        boolean recent = rows.lastWhat != null && now >= rows.lastAt && now - rows.lastAt < LOG_REPEAT_TICKS;
        if (!recent) {
            rows.held = null;
            writeBuildingRow(sl, rows, what, kind, line, detail, building.getDoorPos());
            return;
        }
        rows.held = what.equals(rows.lastWhat) ? null : new HeldRow(what, kind, line, detail, building.getDoorPos());
    }

    private void writeBuildingRow(ServerLevel sl, BuildingRows rows, String what, VillageLog.Kind kind,
                                  Component line, @Nullable Component detail, BlockPos pos) {
        rows.lastWhat = what;
        rows.lastAt = sl.getGameTime();
        VillageLog.record(village, sl, kind, line, detail, pos);
    }

    /** Every village tick: a waiting building row whose minute has passed is written; quiet ones are forgotten. */
    void tickBuildingRows(ServerLevel sl) {
        if (buildingRows.isEmpty()) return;
        long now = sl.getGameTime();
        Iterator<BuildingRows> iterator = buildingRows.values().iterator();
        while (iterator.hasNext()) {
            BuildingRows rows = iterator.next();
            if (now >= rows.lastAt && now - rows.lastAt < LOG_REPEAT_TICKS) continue;
            HeldRow held = rows.held;
            if (held == null) {
                iterator.remove();
                continue;
            }
            rows.held = null;
            writeBuildingRow(sl, rows, held.what(), held.kind(), held.line(), held.detail(), held.pos());
        }
    }

    /** The removal reason for a token taken out of its frame or swapped for another. */
    private static Component tokenOut() {
        return Tr.c("log.dynamicvillages.building.removed.token", "Its token was taken out or changed.");
    }

    /** "Storage Room": the building's name as its token calls it. */
    private static Component buildingName(Building building) {
        String saved = buildingType(building);
        if (MerchantStallBuilding.OPEN_AIR_SAVED_TYPE.equals(saved)) saved = ItemFrameTokenRegistry.TokenType.STALL.savedBuildingType();
        if (ArcheryRangeBuilding.ROOM_SAVED_TYPE.equals(saved)) saved = ItemFrameTokenRegistry.TokenType.ARCHERY_RANGE.savedBuildingType();
        for (ItemFrameTokenRegistry.TokenType type : ItemFrameTokenRegistry.TokenType.values()) {
            if (type.savedBuildingType().equals(saved)) return BuildingRequirements.name(type);
        }
        return Tr.c("log.dynamicvillages.building.some", "A building");
    }

    /**
     * If {@code pos} is the lower or upper half of a building's door, delete
     * that building immediately.  Called from block-break handlers so that
     * removing a door destroys the associated building.
     */
    void checkDoorBreak(BlockPos pos) {
        ServerLevel level = village.getLevel();
        if (level == null) return;
        // Collected first: removeBuilding changes the map this loop walks.
        List<UUID> broken = new ArrayList<>();
        for (Map.Entry<UUID, Building> entry : buildings.entrySet()) {
            Building building = entry.getValue();
            // No door to break. Its anchor holds the frame, and losing the
            // frame is what removes the stall (routeEntityLeave).
            if (building instanceof MerchantStallBuilding stall && stall.isOpenAir()) continue;
            if (building instanceof WatchtowerBuilding) continue;
            BlockPos dp = building.getDoorPos();
            if (pos.equals(dp) || pos.equals(dp.above())) broken.add(entry.getKey());
        }
        for (UUID id : broken) {
            removeBuilding(id, level, "door of " + id.toString().substring(0, 8) + " at " + pos + " was broken",
                    Tr.c("log.dynamicvillages.building.removed.door", "Its door was broken."), null);
        }
    }

    /**
     * The one way a building leaves the village (token out or swapped, frame
     * or door broken): anything that must hear about it goes at the end here.
     * {@link #clearTownHallsForRecovery} is not a removal; the hall comes back.
     */
    void removeBuilding(
            UUID frameId,
            ServerLevel sl,
            String reason,
            Component why,
            @Nullable ItemFrame frame) {
        Building removed = buildings.remove(frameId);
        if (removed == null) return;
        logBuilding(sl, removed, VillageLog.Kind.BUILDING_LOST, "removed",
                Tr.c("log.dynamicvillages.building.removed", "%s removed", buildingName(removed)), why);
        if (removed instanceof MineBuilding) village.onMineLayoutChanged();
        savedBuildingMarkers.remove(frameId);
        if (frame != null) ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
        else ItemFrameTokenRegistry.setRegisteredGlintAt(sl, removed.getItemFramePos(), false);
        if (removed instanceof TownHallBuilding townHall) {
            village.mayors().onTownHallRemoved(sl, townHall);
        }
        // Its door no longer divides anything: a house whose inside door it
        // was takes that room back. Only rooms next to it can.
        markNeighboursDirty(removed, frameId, "removed");
        village.beds().reconcileBedAssignments();
        village.markPersistentDataDirty();
        LOGGER.info("[VillageManager {}] Building removed - {}.", village.shortId(), reason);
        // After the claims are settled, so its beds are never handed back.
        village.onBuildingRemoved(removed);
    }

    /** Remove stale Town Hall building objects before orphan restoration. */
    void clearTownHallsForRecovery() {
        ServerLevel level = village.getLevel();
        boolean removed = false;
        Iterator<Map.Entry<UUID, Building>> iterator = buildings.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Building> entry = iterator.next();
            if (!(entry.getValue() instanceof TownHallBuilding building)) continue;
            if (level != null) {
                ItemFrameTokenRegistry.setRegisteredGlintAt(level, building.getItemFramePos(), false);
            }
            savedBuildingMarkers.remove(entry.getKey());
            iterator.remove();
            removed = true;
        }
        village.beds().reconcileBedAssignments();
        if (removed) village.markPersistentDataDirty();
    }
}
