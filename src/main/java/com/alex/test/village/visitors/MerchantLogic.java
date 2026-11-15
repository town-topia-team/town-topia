package com.alex.test.village.visitors;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VanillaNavCount;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.MerchantStallBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Behaviour for a merchant {@link VisitorEntity}: walk to the merchant
 * stall (or the bell, if the village has no stall), then remain available
 * for player trades until the scheduled departure.
 *
 * <p>A room stall is traded from beside its door. An open-air stall is a
 * square the merchant strolls around in, pausing between short walks.
 *
 * <p>Inside the village every walk runs on the villagers' own pathfinder
 * ({@link TravelerGraphWalk}), with its stuck recovery. Vanilla navigation is
 * only the fallback off the village map.
 *
 * <p>Called from {@code VisitorEntity.tickTraveler} every tick. Trades live
 * in {@link MerchantTransactions}.
 */
public final class MerchantLogic {

    private MerchantLogic() {}

    /** Ticks between vanilla repath attempts while walking in off the village map. */
    private static final int REPATH_INTERVAL = 40;
    /**
     * Ticks spent walking in before the merchant settles for where it stands.
     * Long enough for the walk in from the edge of the simulation distance.
     */
    private static final int MAX_APPROACH_TICKS = 3_600;
    private static final float WALK_SPEED = 0.5f;
    /** Browsing pace inside an open-air stall. */
    private static final float STROLL_SPEED = 0.35f;
    /** Shortest pause between strolls, and the random extra on top of it. */
    private static final int STROLL_PAUSE_TICKS = 60;
    private static final int STROLL_PAUSE_JITTER = 140;
    /**
     * Times the villagers' pathfinder may give up on the walk in before the
     * merchant stops trying and opens where it is. Each give-up already
     * follows that pathfinder's own re-paths and nudges.
     */
    private static final int MAX_WALK_FAILURES = 2;
    /** Wait before trying again after a give-up: the pathfinder skips a failed spot for 30 s. */
    private static final int WALK_RETRY_TICKS = 640;
    /** How far the merchant may step to get off a wall when it opens where it stands. */
    private static final int OPEN_TILE_RADIUS = 4;
    /** Random tiles tried for the next stroll before waiting another pause. */
    private static final int STROLL_PICK_ATTEMPTS = 6;

    private static final WeakHashMap<VisitorEntity, State> STATES = new WeakHashMap<>();
    private static final TravelerGraphWalk WALK = new TravelerGraphWalk("Merchant");

    private static final class State {
        @Nullable BlockPos standPos;
        @Nullable BlockPos strollTarget;
        int repathTimer;
        int retryTimer;
        int strollTimer;
        int walkFailures;
        /** The old-save stand checks run once per load, not against every later settle. */
        boolean migrationChecked;
    }

    /** How one tick of the walk in went. */
    private enum Step { WALKING, ARRIVED, GAVE_UP }

    /** Entry point called once per tick by {@code VisitorEntity} for merchants. */
    public static void tick(VisitorEntity visitor, ServerLevel level) {
        VillageManager village = villageOf(visitor, level);
        // An unavailable village is not an ended visit. The persistent manager
        // record continues to reserve this merchant until it can resume.
        if (village == null) return;

        State state = STATES.computeIfAbsent(visitor, ignored -> new State());
        // Talking business with the tradesman: stand still and face it.
        var tradesman = visitor.tradeTalkPartner(level.getGameTime());
        if (tradesman != null) {
            WALK.clear(visitor);
            visitor.getMoveControl().setWantedPosition(visitor.getX(), visitor.getY(), visitor.getZ(), 0);
            visitor.holdStillForAnimation();
            com.alex.test.entity.NpcUiInteractionHandler.faceEntity(visitor, tradesman, true);
            return;
        }
        MerchantStallBuilding stall = stallFor(village);
        if (stall != null && stall.isOpenAir()) {
            tickOpenStall(visitor, level, village, stall, state);
            return;
        }

        boolean hadReservedSpot = visitor.getGatheringSpot() != null;
        if (state.standPos == null) state.standPos = destination(visitor, village);
        if (!state.migrationChecked) {
            state.migrationChecked = true;
            migrateOldStand(visitor, level, stall, state, hadReservedSpot);
        }

        if (!visitor.hasArrivedForTrading()) {
            tickApproach(visitor, level, village, state);
        }
        // Once arrived, the merchant simply waits: player trades go through
        // MerchantTransactions until the scheduled departure.
    }

    /** Sends a merchant from an old save, settled where stands no longer are, to its stand. */
    private static void migrateOldStand(VisitorEntity visitor, ServerLevel level,
                                        @Nullable MerchantStallBuilding stall, State state,
                                        boolean hadReservedSpot) {
        if (!visitor.hasArrivedForTrading()
                || isAtStand(level, stall, visitor.blockPosition(), state.standPos)) return;
        if (!hadReservedSpot) {
            // Merchants that were already marked arrived before per-traveller
            // standing positions existed.
            visitor.setArrivedForTrading(false);
            visitor.resetApproachTicks();
        } else if (stall != null
                && TravelerGatheringSpots.blocksDoor(level, visitor.blockPosition(), stall)) {
            // Merchants that settled in the stall doorway before stands moved
            // beside the door: walk over to the new one.
            visitor.setArrivedForTrading(false);
            visitor.resetApproachTicks();
        }
    }

    // =========================================================================
    // Approach
    // =========================================================================

    private static void tickApproach(VisitorEntity visitor, ServerLevel level,
                                     VillageManager village, State state) {
        if (state.standPos == null) {
            state.standPos = destination(visitor, village);
        }
        BlockPos target = state.standPos;

        if (isAtStand(level, stallFor(village), visitor.blockPosition(), target)) {
            openForBusiness(visitor, level, village, target, true);
            return;
        }
        // Giving up on the walk still opens for business. A merchant that
        // cannot reach the stall trades from near wherever it got to, because
        // the alternative is a visitor that silently does nothing at all until
        // its despawn deadline — which is exactly what happened when the
        // stand point was unreachable.
        if (visitor.incrementApproachTicks() >= MAX_APPROACH_TICKS) {
            openForBusiness(visitor, level, village, target, false);
            return;
        }
        switch (walkIn(visitor, village, target, state)) {
            case ARRIVED -> openForBusiness(visitor, level, village, target, true);
            case GAVE_UP -> openForBusiness(visitor, level, village, target, false);
            case WALKING -> { }
        }
    }

    /**
     * One tick of the walk to {@code target}: the villagers' pathfinder on the
     * village map, vanilla navigation off it.
     */
    private static Step walkIn(VisitorEntity visitor, VillageManager village, BlockPos target, State state) {
        // No headway for 60 s: hop off the ledge or corner and walk again.
        ServerLevel level = (ServerLevel) visitor.level();
        long stuck = visitor.walkStuckTicks(level.getGameTime(), Vec3.atBottomCenterOf(target));
        if (visitor.hopIfStuck(level, stuck)) {
            WALK.clear(visitor);
            state.retryTimer = 0;
            state.repathTimer = 0;
            return Step.WALKING;
        }
        if (state.retryTimer > 0) {
            state.retryTimer--;
            return Step.WALKING;
        }
        switch (WALK.tick(visitor, village, target, WALK_SPEED)) {
            case WALKING -> { return Step.WALKING; }
            // Reached the stand, or the nearest tile to it the map has.
            case ARRIVED -> { return Step.ARRIVED; }
            case FAILED -> {
                WALK.clear(visitor);
                if (++state.walkFailures >= MAX_WALK_FAILURES) return Step.GAVE_UP;
                DynamicVillages.LOGGER.info("[Merchant {}] Could not reach {}; trying again in {} s.",
                        TravelerApproach.shortId(visitor), target.toShortString(), WALK_RETRY_TICKS / 20);
                state.retryTimer = WALK_RETRY_TICKS;
                return Step.WALKING;
            }
            case OUTSIDE -> { }
        }
        // Off the village map: vanilla navigation until the merchant is on it.
        if (state.repathTimer > 0) {
            state.repathTimer--;
            return Step.WALKING;
        }
        if (visitor.getNavigation().isDone()) {
            VanillaNavCount.note(VanillaNavCount.Site.MERCHANT_OFF_MAP, visitor);
            visitor.getNavigation().moveTo(
                    target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, WALK_SPEED);
            state.repathTimer = REPATH_INTERVAL;
        }
        return Step.WALKING;
    }

    private static void openForBusiness(VisitorEntity visitor, ServerLevel level, VillageManager village,
                                        BlockPos target, boolean reached) {
        WALK.clear(visitor);
        visitor.getNavigation().stop();
        if (!reached) {
            logGaveUp(visitor, target);
            stepOffWall(visitor, level, village);
        }
        visitor.setArrivedForTrading(true);
    }

    /**
     * A merchant that gave up walking opens where it stands, and that was often
     * pressed against the wall it gave up on, for the whole visit. When it is
     * touching a block sideways, it steps onto the nearest open map tile.
     */
    private static void stepOffWall(VisitorEntity visitor, ServerLevel level, VillageManager village) {
        if (level.noCollision(visitor, visitor.getBoundingBox().inflate(0.2D, -0.05D, 0.2D))) return;
        BlockPos feet = visitor.blockPosition();
        if (!visitor.hopToOpenMapTile(level, OPEN_TILE_RADIUS, 1)) return;
        DynamicVillages.LOGGER.info("[Merchant {}] Stepped off a wall at {} to open at {}.",
                TravelerApproach.shortId(visitor), feet.toShortString(),
                visitor.blockPosition().toShortString());
    }

    // =========================================================================
    // Open-air stall
    // =========================================================================

    /**
     * Walk into the square, then browse it: short strolls to random tiles
     * with a pause between each. Trading needs no particular tile, and a
     * player opening the menu pauses this whole tick (see
     * {@code NpcUiInteractionHandler.tickTraveler}).
     */
    private static void tickOpenStall(VisitorEntity visitor, ServerLevel level, VillageManager village,
                                      MerchantStallBuilding stall, State state) {
        // The Town Hall gathering tile is not where this merchant stands, so
        // it should not keep another traveller off it.
        if (visitor.getGatheringSpot() != null) visitor.setGatheringSpot(null);
        List<BlockPos> tiles = List.copyOf(stall.getNavMeshBlocks());
        if (tiles.isEmpty()) return;

        if (!visitor.hasArrivedForTrading()) {
            BlockPos target = stall.centerTile();
            if (target == null) target = tiles.get(0);
            boolean inStall = stall.isInOpenArea(visitor.blockPosition());
            Step step = inStall ? Step.ARRIVED
                    : visitor.incrementApproachTicks() >= MAX_APPROACH_TICKS ? Step.GAVE_UP
                    : walkIn(visitor, village, target, state);
            if (step == Step.WALKING) return;
            // As at a room stall, giving up on the walk still opens for business.
            openForBusiness(visitor, level, village, target, step == Step.ARRIVED);
            state.strollTimer = STROLL_PAUSE_TICKS;
            return;
        }

        if (state.strollTarget != null) {
            if (WALK.tick(visitor, village, state.strollTarget, STROLL_SPEED)
                    == TravelerGraphWalk.Result.WALKING) return;
            // Got there, or it could not: either way, pause and pick another.
            WALK.clear(visitor);
            state.strollTarget = null;
            state.strollTimer = STROLL_PAUSE_TICKS + visitor.getRandom().nextInt(STROLL_PAUSE_JITTER);
            return;
        }
        if (state.strollTimer > 0) {
            state.strollTimer--;
            return;
        }
        state.strollTarget = pickStrollTile(visitor, level, village, tiles);
        if (state.strollTarget == null) state.strollTimer = STROLL_PAUSE_TICKS;
    }

    /**
     * A random free tile of the square to stroll to: one on the village map
     * with room to stand, nobody on it, and not the tile the merchant is on.
     */
    @Nullable
    private static BlockPos pickStrollTile(VisitorEntity visitor, ServerLevel level,
                                           VillageManager village, List<BlockPos> tiles) {
        NavGraph graph = village.getGraph();
        if (graph == null) return null;
        BlockPos here = visitor.blockPosition();
        for (int attempt = 0; attempt < STROLL_PICK_ATTEMPTS; attempt++) {
            BlockPos tile = tiles.get(visitor.getRandom().nextInt(tiles.size()));
            NavNode node = graph.nearestRoutable(tile, 0, 1);
            if (node == null || !NavGraph.hasStandingRoom(level, node)) continue;
            BlockPos feet = node.feetPos();
            if (Math.abs(feet.getX() - here.getX()) <= 1 && Math.abs(feet.getZ() - here.getZ()) <= 1) continue;
            if (!level.getEntities(visitor, new AABB(feet)).isEmpty()) continue;
            return feet;
        }
        return null;
    }

    private static void logGaveUp(VisitorEntity visitor, BlockPos target) {
        DynamicVillages.LOGGER.info("[Merchant {}] Gave up walking to {}; trading from {}.",
                TravelerApproach.shortId(visitor), target.toShortString(),
                visitor.blockPosition().toShortString());
    }

    /**
     * At the stand, or one tile off it, so a barrel or a villager on the exact
     * tile no longer keeps the merchant walking. One tile off never counts in
     * the stall doorway.
     */
    private static boolean isAtStand(ServerLevel level, @Nullable MerchantStallBuilding stall,
                                     BlockPos current, BlockPos target) {
        if (Math.abs(current.getY() - target.getY()) > 3) return false;
        if (current.getX() == target.getX() && current.getZ() == target.getZ()) return true;
        return Math.abs(current.getX() - target.getX()) <= 1
                && Math.abs(current.getZ() - target.getZ()) <= 1
                && (stall == null || !TravelerGatheringSpots.blocksDoor(level, current, stall));
    }

    /**
     * Where the merchant sets up shop.
     *
     * <p>Beside the stall <em>door</em>, not the counter inside it: standing
     * outside the door is both reachable and how a travelling trader would
     * really work.
     *
     * <p>Beside the door, not in it: a merchant parked on the door block spent
     * the visit inside a door that its own route closed on it and the
     * tradesman kept opening and closing.
     */
    private static BlockPos destination(VisitorEntity visitor, VillageManager village) {
        MerchantStallBuilding stall = stallFor(village);
        ServerLevel level = (ServerLevel) visitor.level();
        return stall != null
                ? TravelerGatheringSpots.claimBesideDoor(visitor, level, village, stall)
                : TravelerGatheringSpots.claimAtTownHall(visitor, level, village);
    }

    // =========================================================================
    // Village queries
    // =========================================================================

    /** The stall a merchant visiting {@code village} uses: its first valid one. */
    @Nullable
    public static MerchantStallBuilding stallFor(VillageManager village) {
        for (Building building : village.getBuildings()) {
            if (building instanceof MerchantStallBuilding stall && stall.isValid()) return stall;
        }
        return null;
    }

    @Nullable
    private static VillageManager villageOf(VisitorEntity visitor, ServerLevel level) {
        UUID villageId = visitor.getVillageId();
        if (villageId == null || level.getServer() == null) return null;
        return VillageManagerRegistry.get(level.getServer()).get(villageId);
    }
}
