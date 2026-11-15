package com.alex.test.village;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side logical model for one NPC puppet.
 *
 * <p>Owns the async A* pathfind request and the active {@link MoveToCommand}.
 * The in-world {@link NpcEntity} puppet is a separate object that may be null
 * while its chunk is unloaded.
 *
 * <h3>Thread-safety</h3>
 * All public methods must be called from the server thread, except the
 * {@link CompletableFuture} worker which only reads the captured (immutable)
 * NavGraph.
 */
public final class VillagerModel {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final float DEFAULT_SPEED = 0.5f;
    /**
     * The one walking speed every villager uses for every normal walk: jobs,
     * going home, meals, evenings, wandering, school. Runs, chases, fleeing
     * and leading animals have their own speeds. Sadness slows it for
     * everyone alike (NpcEntity.sadWalkPace). About 2.70 blocks a second;
     * RunPace.WALK_NATURAL_SPEED must match the blocks a second this gives.
     */
    public static final float WALK_SPEED = 0.55f;
    private static final int MAX_STUCK_REPATHS = 1;
    /** Safe-node relocations allowed per goal before it is written off. */
    private static final int MAX_STUCK_RECOVERIES = 3;
    /**
     * How long routes avoid a jump a villager got stuck against. Long enough
     * that the village stops walking into it; a block change beside it forgets
     * sooner, since that rebuilds the nodes (see {@link NavNode#markStepFailed}).
     */
    private static final long FAILED_STEP_MILLIS = 10L * 60L * 1000L;
    /** A rise vanilla walks up without jumping; only jumps can be failed. */
    private static final double FAILED_STEP_MIN_RISE = 0.6D;
    /** Past the villager's half-width and the half block to the step's face: still pressed against it. */
    private static final double FAILED_STEP_CONTACT_SLACK = 0.35D;
    /** Ticks between collision-overlap checks for a stuck villager. */
    private static final int BLOCKED_CHECK_INTERVAL = 20;
    private static final int RECOVERY_XZ_RADIUS = 6;
    private static final int RECOVERY_Y_RADIUS = 4;
    private static final int GOAL_SNAP_XZ_RADIUS = 3;
    private static final int GOAL_SNAP_Y_RADIUS = 3;
    private static final double OCCUPANCY_SCAN_DIAMETER = 32.0D;
    private static final int WATER_ESCAPE_XZ_RADIUS = 16;
    private static final int WATER_ESCAPE_Y_RADIUS = 6;
    private static final int WATER_ESCAPE_REPATH_TICKS = 20;
    private static final int WATER_ESCAPE_SETTLE_TICKS = 40;
    private static final int WATER_EXIT_STUCK_CHECK_TICKS = 20;
    private static final int WATER_EXIT_STUCK_PERIODS = 3;
    private static final int UI_PAUSE_GRACE_TICKS = 2;
    private static final long WATER_EXIT_BLACKLIST_TICKS = 20L * 8L;
    private static final double WATER_SWIM_UP_SPEED = 0.035D;
    private static final double WATER_SWIM_TO_LAND_ACCEL = 0.012D;
    private static final double WATER_SWIM_TO_LAND_MAX_SPEED = 0.06D;
    private static final double WATER_FOOT_DIP_TOLERANCE_BLOCKS = 20.0D / 16.0D;
    private static final double MAX_WATER_EXIT_STEP_UP = 1.25D;
    private static final double WATER_EXIT_APPROACH_DIST_SQ = 1.35D * 1.35D;
    private static final double WATER_EXIT_MIN_PROGRESS_SQ = 0.08D;
    private static final long FAILED_TARGET_BLACKLIST_TICKS = 20L * 30L;
    /** Different goals that must find no path from one tile before the villager counts as trapped there. */
    private static final int TRAPPED_NO_PATH_GOALS = 5;
    /** Those failures all fall within this long; as long as a failed goal stays blacklisted. */
    private static final long TRAPPED_WINDOW_TICKS = FAILED_TARGET_BLACKLIST_TICKS;
    /** A villager stuck on the same tile is reported again after this long. */
    private static final long STUCK_LOG_INTERVAL_TICKS = 20L * 60L;
    /** Stucks on one tile, for one reason, within {@link #LOOP_WINDOW_TICKS}: a stuck loop. */
    private static final int LOOP_STUCKS = 3;
    private static final long LOOP_WINDOW_TICKS = 20L * 30L;
    /** With {@link WalkFeel#stuckLoop}: at most one stuck rescue (move to a safe tile) this often. */
    private static final long STUCK_RESCUE_EVERY_TICKS = 20L * 30L;
    /** Water shallower than this over a grounded villager's soles is waded through. */
    private static final double WADE_DEPTH = 0.5D;
    /** How far above its soles a villager's waist is. */
    private static final double WAIST_HEIGHT = 0.9D;

    /** Stable identity linking this model to its puppet entity across chunk reloads. */
    public final UUID npcId;

    /** Decision authority — see {@link com.alex.test.village.brain.VillagerBrain}. */
    private final com.alex.test.village.brain.VillagerBrain brain;

    private NpcEntity puppet;
    /** Claimed sitting-sleep tile while GoHomeActivity is active. */
    private BlockPos reservedSleepSpot;

    public BlockPos reservedSleepSpot() { return reservedSleepSpot; }
    public void setReservedSleepSpot(BlockPos spot) { reservedSleepSpot = spot; }

    /**
     * The villager's own name as last read from its puppet, kept (and saved
     * with the village) so screens can show it while the puppet is not loaded.
     */
    private String knownName;

    /** The villager's name: from the loaded puppet, else the last one seen; null if never seen. */
    @javax.annotation.Nullable
    public String name() {
        if (puppet != null && puppet.givenName() != null) knownName = puppet.givenName();
        return knownName;
    }

    /** Copies the puppet's name; true when the remembered name changed. */
    public boolean noteName(NpcEntity entity) {
        String name = entity.givenName();
        if (name == null || name.equals(knownName)) return false;
        knownName = name;
        return true;
    }

    /** Restores the name saved with the village, before the puppet loads. */
    public void rememberName(String name) {
        if (knownName == null && name != null && !name.isEmpty()) knownName = name;
    }

    /**
     * Where the puppet was when it last unloaded: the chunk it is saved in.
     * Null until it has unloaded once since spots were saved.
     */
    @javax.annotation.Nullable
    private BlockPos lastSeenSpot;
    /** The dimension {@link #lastSeenSpot} is in; null for the village's own. */
    @javax.annotation.Nullable
    private net.minecraft.resources.ResourceKey<Level> lastSeenDimension;
    /** Checks in a row that found the last-seen spot's entities loaded and no puppet; not saved. */
    private int goneChecks;
    /** Waiting for the save files to be searched for its body; not saved. */
    private boolean lookingForBody;
    /** Clean searches of the save files in a row that found no body; not saved. */
    private int bodyMisses;

    @javax.annotation.Nullable
    public BlockPos lastSeenSpot() { return lastSeenSpot; }
    @javax.annotation.Nullable
    public net.minecraft.resources.ResourceKey<Level> lastSeenDimension() { return lastSeenDimension; }

    /** The puppet is here: nothing is missing. */
    public void noteSeen() {
        goneChecks = 0;
        lookingForBody = false;
        bodyMisses = 0;
    }

    /**
     * The loaded puppet is at {@code spot}; true when that is a different chunk
     * from the remembered one, so a save made now (or a quit, which never
     * unbinds) keeps where it really is.
     */
    public boolean noteLoadedAt(BlockPos spot) {
        if (lastSeenDimension == null && lastSeenSpot != null
                && (lastSeenSpot.getX() >> 4) == (spot.getX() >> 4)
                && (lastSeenSpot.getZ() >> 4) == (spot.getZ() >> 4)) return false;
        lastSeenSpot = spot.immutable();
        lastSeenDimension = null;
        return true;
    }

    /**
     * The puppet unloaded at {@code spot} in {@code dimension} (null: the
     * village's own); true when the remembered spot changed.
     */
    public boolean noteUnloadedAt(@javax.annotation.Nullable net.minecraft.resources.ResourceKey<Level> dimension,
                                  BlockPos spot) {
        noteSeen();
        return setSpot(dimension, spot);
    }

    /**
     * Its body was found at {@code spot} (in the save files, or loaded but not
     * this village's puppet); true when the remembered spot changed.
     */
    public boolean noteBodyAt(@javax.annotation.Nullable net.minecraft.resources.ResourceKey<Level> dimension,
                              BlockPos spot) {
        goneChecks = 0;
        lookingForBody = false;
        bodyMisses = 0;
        return setSpot(dimension, spot);
    }

    private boolean setSpot(@javax.annotation.Nullable net.minecraft.resources.ResourceKey<Level> dimension,
                            BlockPos spot) {
        if (spot.equals(lastSeenSpot) && java.util.Objects.equals(dimension, lastSeenDimension)) return false;
        lastSeenSpot = spot.immutable();
        lastSeenDimension = dimension;
        return true;
    }

    /** Restores what the village save knew, before the puppet loads. */
    public void rememberSighting(@javax.annotation.Nullable BlockPos spot,
                                 @javax.annotation.Nullable net.minecraft.resources.ResourceKey<Level> dimension) {
        if (lastSeenSpot == null && spot != null) {
            lastSeenSpot = spot;
            lastSeenDimension = dimension;
        }
    }

    /** One more check found its spot loaded and empty; returns how many in a row. */
    public int addGoneCheck() { return ++goneChecks; }
    public void clearGoneChecks() { goneChecks = 0; }

    public boolean lookingForBody() { return lookingForBody; }
    /** Asks for the save files to be searched for it before anything is decided. */
    public void lookForBody() { lookingForBody = true; }
    /** A clean search found no body anywhere; returns how many in a row. */
    public int addBodyMiss() { return ++bodyMisses; }

    // Async pathfind state
    private CompletableFuture<AStarPathfinder.Route> pendingPath;
    private NavGraph  pendingGraph;
    private long      pendingGraphVersion = -1L;
    private BlockPos  pendingGoal;
    private float     pendingSpeed;
    private boolean   pendingSmoothPath = true;
    private Set<Long> pendingExcludedCells = Set.of();

    private MoveToCommand activeMove;
    /** The walk-back spy (round 1 of the walking-feel plan); reads only. */
    private final WalkBackLog walkBack = new WalkBackLog();
    /** Stuck catcher: the last 15 s of this villager while /villager stuckwatch is on; reads only. */
    private final com.alex.test.village.stuckwatch.BlackBox blackBox = new com.alex.test.village.stuckwatch.BlackBox();
    /** Game time the outstanding route was asked for, for the spy. */
    private long pendingRequestedAt;
    /**
     * Doors and gates a finished or cancelled walk could not shut because a
     * villager or player stood in them; shut as soon as the doorway is clear.
     */
    private final RouteDoorHandler doorsLeftOpen = new RouteDoorHandler();
    /** The walk was held last tick while the swim-out steered in deep water; re-planned once out. */
    private boolean walkHeldInWater;
    /**
     * Frozen ground ({@link WalkFeel#frozenGround}, 10-18): game time a walk was first held
     * because the body stood where Minecraft does not move creatures, or MIN_VALUE. Kept while
     * walks come and go on that ground, so one episode gives one pair of log lines.
     */
    private long frozenGroundSince = Long.MIN_VALUE;
    private int stuckRepathAttempts = 0;
    private int stuckRecoveries     = 0;
    /**
     * Stuck loop ({@link WalkFeel#stuckLoop}, 10-18): the tile of the last stuck, its rule (up to
     * the detail), how many stucks in a row there and since when. The repath and rescue budgets
     * above are per goal, and the brain picks a new goal each time one is written off, so a
     * villager trapped at one spot was re-planned and moved to a safe tile about once a second
     * for as long as the world ran (a butcher at a tall gate: 65 stucks in two minutes).
     */
    private long loopFeet = Long.MIN_VALUE;
    private String loopRule = "";
    private int loopStucks;
    private long loopSince;
    /** Game time of the last stuck rescue (move to a nearby safe tile), or MIN_VALUE. */
    private long lastStuckRescueAt = Long.MIN_VALUE;
    private BlockPos waterEscapeGoal;
    private WaterExit waterExit;
    private int waterEscapeRepathTimer;
    private int waterEscapeSettleTicks;
    private int waterExitStuckCheckTimer;
    private int waterExitStuckPeriods;
    private int uiPauseTicks;
    private double lastWaterExitCheckX = Double.NaN;
    private double lastWaterExitCheckZ = Double.NaN;
    /** Game time the current swim-out began, or -1. Only its log lines read it. */
    private long waterEscapeStartedAt = -1L;

    /**
     * When true, {@link VillageManager} skips this model's
     * {@code brain().tick(...)} entirely while still advancing {@link #tick}
     * (and therefore any in-flight {@link MoveToCommand}) normally. Lets a
     * scenario drive a puppet with a manual {@code requestMoveTo}/
     * {@code requestMoveToDetailed} call without the brain's own leisure/work
     * activities re-issuing a competing move on the very next tick. Also set
     * by a raid for a villager the kidnappers are leading away (RaidManager
     * walks it); never saved, so a restart always lets it go.
     */
    private boolean brainSuspended;

    private BlockPos lastFailedGoal;
    private BlockPos lastCompletedGoal;
    private final Map<Long, Long> failedTargetUntil = new HashMap<>();
    private final Map<Long, Long> failedWaterExitUntil = new HashMap<>();

    /** The tile the current run of "no path" failures started on; see {@link #rescueIfTrapped}. */
    private BlockPos noPathFeet;
    private long noPathSince;
    private final List<BlockPos> noPathGoals = new ArrayList<>();
    /** Last tile {@link #logStuckTile} reported, and when. */
    private BlockPos stuckLoggedFeet;
    private long stuckLoggedAt;

    // -------------------------------------------------------------------------

    public VillagerModel(UUID npcId) {
        this.npcId  = npcId;
        this.brain  = new com.alex.test.village.brain.VillagerBrain(this);
        walkBack.recordTo(blackBox::event);
    }

    public com.alex.test.village.brain.VillagerBrain brain() { return brain; }

    public com.alex.test.village.stuckwatch.BlackBox blackBox() { return blackBox; }

    // -------------------------------------------------------------------------
    // Puppet binding
    // -------------------------------------------------------------------------

    public void      bind(NpcEntity entity) {
        puppet = entity;
        releasedUnloadedAt = Long.MIN_VALUE;
    }

    /** {@link #dueUnloadedRelease}: once when the body goes missing, then this often (10 s). */
    private static final int UNLOADED_RELEASE_EVERY = 200;
    /** Game time the village last let go of this missing villager's claims; reset on bind. */
    private long releasedUnloadedAt = Long.MIN_VALUE;

    /**
     * Whether the village should let go of this villager's claims, tree and farm job and stop its
     * walk now, while its body is missing: on the first tick, then every
     * {@link #UNLOADED_RELEASE_EVERY} ticks for anything claimed for it meanwhile (its brain does
     * not run, so nothing should be). True stamps the time.
     */
    public boolean dueUnloadedRelease(long now) {
        if (releasedUnloadedAt != Long.MIN_VALUE && now >= releasedUnloadedAt
                && now - releasedUnloadedAt < UNLOADED_RELEASE_EVERY) return false;
        releasedUnloadedAt = now;
        return true;
    }
    public void      unbind(NpcEntity entity) {
        if (puppet == entity) {
            stopMoving();
            puppet = null;
        }
    }
    public NpcEntity puppet()               { return puppet; }
    public boolean   isMoving()             { return activeMove != null || pendingPath != null; }

    // -------------------------------------------------------------------------
    // Movement telemetry
    //
    // Read-only views for observers. "Where is the villager" is answerable from
    // the entity alone; "where was it trying to get to, and how far along was
    // it" is not, and that is the difference between seeing a villager standing
    // still and knowing whether standing still is a bug.
    // -------------------------------------------------------------------------

    /** The destination of the current move, whether the path is found yet or not. */
    public BlockPos moveGoal() { return pendingGoal; }

    /** The waypoint being walked to right now, or null when not moving. */
    public BlockPos moveWaypoint() {
        return activeMove == null ? null : activeMove.currentWaypoint();
    }

    /** How far along the current path, as index/total; -1/0 when not moving. */
    public int moveWaypointIndex() {
        return activeMove == null ? -1 : activeMove.currentWaypointIndex();
    }

    public int moveWaypointCount() {
        return activeMove == null ? 0 : activeMove.waypointCount();
    }

    /** True while an A* request is outstanding — moving, but with no path yet. */
    public boolean isAwaitingPath() { return pendingPath != null && activeMove == null; }

    /** The walk being followed now, for {@link WalkBackLog}; null when none. */
    @javax.annotation.Nullable
    MoveToCommand activeMoveForSpy() { return activeMove; }

    /** The walk being followed now, for the stuck catcher; null when none. Read it, don't drive it. */
    @javax.annotation.Nullable
    public MoveToCommand activeMove() { return activeMove; }

    /** Stuck catcher: the current walk's speed, route kind, and stuck re-plans and rescues so far. */
    public float moveSpeed() { return pendingSpeed; }
    public boolean moveSmoothed() { return pendingSmoothPath; }
    public int stuckRepaths() { return stuckRepathAttempts; }
    public int stuckRecoveryCount() { return stuckRecoveries; }
    /** Cells the current walk must keep out of (empty for most walks); see FollowWalk.replay. */
    public Set<Long> moveExcludedCells() { return pendingExcludedCells; }

    /**
     * Stuck catcher rewind: walks a recorded route again from waypoint
     * {@code index}, with the walk's goal, speed and stuck counts as they were.
     * The caller has put the villager back where it stood and holds its brain.
     * False when there is no walk to replay: the map no longer has the route's tiles.
     */
    public boolean replayWalk(List<BlockPos> waypoints, int index, BlockPos goal, float speed,
                              boolean smoothed, int repaths, int recoveries,
                              @javax.annotation.Nullable NavGraph graph, Set<Long> excluded) {
        if (puppet == null || waypoints.isEmpty()) return false;
        stopMoving();
        MoveToCommand replay = graph != null
                ? FollowWalk.replay(graph, puppet, waypoints, speed, smoothed, repaths, excluded) : null;
        if (replay == null) return false;
        replay.keepOccupiedDoorsWith(doorsLeftOpen);
        replay.startAt(index);
        pendingGoal = goal;
        pendingSpeed = speed;
        pendingSmoothPath = smoothed;
        pendingExcludedCells = excluded;
        stuckRepathAttempts = repaths;
        stuckRecoveries = recoveries;
        lastFailedGoal = null;
        lastCompletedGoal = null;
        if (goal != null) failedTargetUntil.remove(goal.asLong());
        activeMove = replay;
        return true;
    }

    // -------------------------------------------------------------------------
    // Movement API
    // -------------------------------------------------------------------------

    /**
     * Submit an async A* request from the puppet's current position to {@code goal}.
     * Cancels any in-flight request.
     */
    public boolean requestMoveTo(NavGraph graph, BlockPos goal, float speed) {
        return requestMoveTo(graph, goal, speed, true, true, Set.of());
    }

    public boolean requestMoveToDetailed(NavGraph graph, BlockPos goal, float speed) {
        return requestMoveTo(graph, goal, speed, true, false, Set.of());
    }

    public boolean requestMoveToDetailedAvoiding(NavGraph graph, BlockPos goal, float speed, Set<Long> excluded) {
        return requestMoveTo(graph, goal, speed, true, false, Set.copyOf(excluded));
    }

    /** See {@link #brainSuspended}. */
    public void setBrainSuspended(boolean suspended) { brainSuspended = suspended; }

    /** See {@link #brainSuspended}. */
    public boolean isBrainSuspended() { return brainSuspended; }

    private boolean requestMoveTo(
            NavGraph graph, BlockPos goal, float speed, boolean resetStuckCounter, boolean smoothPath) {
        return requestMoveTo(graph, goal, speed, resetStuckCounter, smoothPath, pendingExcludedCells);
    }

    private boolean requestMoveTo(
            NavGraph graph, BlockPos goal, float speed, boolean resetStuckCounter,
            boolean smoothPath, Set<Long> excluded) {
        if (puppet == null || graph == null || goal == null) {
            markMoveFailed(goal);
            return false;
        }
        if (!graph.isInsideBounds(goal)
                || graph.nearestNonAvoided(goal, GOAL_SNAP_XZ_RADIUS, GOAL_SNAP_Y_RADIUS) == null) {
            markMoveFailed(goal);
            LOGGER.info("[VillagerModel {}] Refused out-of-village move target {}.", shortId(), fmt(goal));
            return false;
        }
        if (isTargetBlacklisted(goal)) {
            markMoveFailed(goal);
            LOGGER.debug("[VillagerModel {}] Skipping temporarily blacklisted goal {}.", shortId(), fmt(goal));
            return false;
        }

        walkBack.noteRequest(puppet, goal, !resetStuckCounter, brain.currentActivity());
        pendingRequestedAt = gameTime();
        BlockPos start = NavGraph.routeStart(graph, puppet);
        if (!puppet.onGround() && !start.equals(NavGraph.feetCell(puppet))) {
            LOGGER.debug("[AirStart] {} asked for a route in the air at y {}: starts on the tile under it {}, not {}",
                    shortId(), String.format(java.util.Locale.ROOT, "%.2f", puppet.getY()), fmt(start),
                    fmt(NavGraph.feetCell(puppet)));
        }
        pendingGoal  = goal;
        pendingSpeed = speed;
        pendingGraph = graph;
        // The graph is edited in place now, so its identity no longer changes
        // when the world does. Remember the version the path was computed
        // against; anything else and the route may cross a wall that has since
        // gone up.
        pendingGraphVersion = graph == null ? -1L : graph.version();
        pendingSmoothPath = smoothPath;
        pendingExcludedCells = excluded;
        lastFailedGoal = null;
        lastCompletedGoal = null;
        if (resetStuckCounter) { stuckRepathAttempts = 0; stuckRecoveries = 0; }

        if (pendingPath != null) pendingPath.cancel(false);
        // Capture entity positions on the server thread. The async pathfinder
        // may read the graph, but it must never query live world entities.
        Set<Long> occupied = nearbyOccupiedFeet(goal);
        pendingPath = CompletableFuture.supplyAsync(
                () -> !excluded.isEmpty()
                        ? AStarPathfinder.findRoute(graph, start, goal, false, occupied, excluded)
                        : AStarPathfinder.findRoute(graph, start, goal, smoothPath, occupied, Set.of()));

        return true;
    }

    private Set<Long> nearbyOccupiedFeet(BlockPos goal) {
        if (!(puppet.level() instanceof ServerLevel level)) return Set.of();
        Set<Long> occupied = new HashSet<>();
        AABB startArea = AABB.ofSize(puppet.position(),
                OCCUPANCY_SCAN_DIAMETER, 12.0D, OCCUPANCY_SCAN_DIAMETER);
        AABB goalArea = AABB.ofSize(Vec3.atCenterOf(goal),
                OCCUPANCY_SCAN_DIAMETER, 12.0D, OCCUPANCY_SCAN_DIAMETER);
        for (NpcEntity other : level.getEntitiesOfClass(NpcEntity.class, startArea,
                other -> other != puppet && other.isAlive())) {
            occupied.add(NavGraph.feetCell(other).asLong());
        }
        for (NpcEntity other : level.getEntitiesOfClass(NpcEntity.class, goalArea,
                other -> other != puppet && other.isAlive())) {
            occupied.add(NavGraph.feetCell(other).asLong());
        }
        return Set.copyOf(occupied);
    }

    public void stopMoving() {
        if (puppet != null && isMoving()) {
            com.alex.test.village.brain.Activity doing = brain.currentActivity();
            walkBack.note(puppet, WalkBackLog.Kind.STOP, " by " + (doing == null ? "nothing" : doing.name()));
        }
        pendingExcludedCells = Set.of();
        cancelPendingPath();
        pendingGraph = null;
        if (activeMove != null && puppet != null) activeMove.closeAllOpenedDoors(puppet);
        activeMove   = null;
        pendingGoal  = null;
        pendingSmoothPath = true;
        waterEscapeGoal = null;
        waterExit = null;
        waterEscapeRepathTimer = 0;
        waterEscapeSettleTicks = 0;
        resetWaterExitStuckState();
        endSwimOutLog("its walk was stopped");
        if (puppet != null) {
            puppet.setLadderClimbTarget(null);
            puppet.getNavigation().stop();
            puppet.setAnimation(VillagerAnimationState.IDLE);
        }
    }

    /**
     * Freeze the puppet for an open UI without cancelling the current route.
     * When the UI closes, the existing MoveToCommand can re-issue its waypoint
     * instead of the activity mistaking a cancelled move for arrival.
     */
    public void pauseMovingForUi() {
        if (puppet == null) return;
        uiPauseTicks = UI_PAUSE_GRACE_TICKS;
        puppet.setLadderClimbTarget(null);
        puppet.getNavigation().stop();
        puppet.holdStillForAnimation();
        puppet.setAnimation(VillagerAnimationState.IDLE);
    }

    /**
     * The same pause for a villager a cleric is tending: the route is kept for
     * afterwards, but the pose is left alone, so the gestures the cleric's chat
     * plays on it are not reset to idle every tick.
     */
    public void holdStillForCare() {
        if (puppet == null) return;
        uiPauseTicks = UI_PAUSE_GRACE_TICKS;
        puppet.setLadderClimbTarget(null);
        puppet.getNavigation().stop();
        puppet.holdStillForAnimation();
    }

    /**
     * Stop runtime navigation during server shutdown without mutating the world.
     * Normal cancellation closes route doors; shutdown is already inside
     * Minecraft's save/teardown path, so block updates here can race chunk tickets.
     */
    public void stopMovingForShutdown() {
        pendingExcludedCells = Set.of();
        cancelPendingPath();
        pendingGraph = null;
        activeMove   = null;
        pendingGoal  = null;
        pendingSmoothPath = true;
        waterEscapeGoal = null;
        waterExit = null;
        waterEscapeRepathTimer = 0;
        waterEscapeSettleTicks = 0;
        resetWaterExitStuckState();
        waterEscapeStartedAt = -1L;
        if (puppet != null) {
            puppet.setLadderClimbTarget(null);
            puppet.getNavigation().stop();
            puppet.setAnimation(VillagerAnimationState.IDLE);
        }
    }

    public boolean canReach(NavGraph graph, BlockPos goal) {
        if (puppet == null || graph == null || goal == null || isTargetBlacklisted(goal)
                || !graph.isInsideBounds(goal)) return false;
        NavNode startNode = graph.nearestOnFloor(NavGraph.feetCell(puppet), 3, 3);
        NavNode goalNode = graph.nearestNonAvoided(goal, 3, 3);
        if (startNode == null || goalNode == null) return false;

        // A component lookup, not an A* search: see NavReachability.
        return NavReachability.canReach(graph, startNode.feetPos(), goalNode.feetPos());
    }

    /** Whether a failed walk has ruled {@code goal} out for now; {@link #canReach} answers false meanwhile. */
    public boolean isGoalBlacklisted(BlockPos goal) {
        return goal != null && isTargetBlacklisted(goal);
    }

    public boolean hasRecentMoveFailure(BlockPos goal) {
        return goal != null && goal.equals(lastFailedGoal);
    }

    public boolean hasRecentMoveSuccess(BlockPos goal) {
        return goal != null && goal.equals(lastCompletedGoal);
    }

    public boolean isEscapingWater() {
        return puppet != null && (needsWaterRescue()
                || waterExit != null
                || waterEscapeGoal != null
                || waterEscapeSettleTicks > 0);
    }

    /**
     * Whether the villager is in water it has to be got out of, rather than
     * just touching some.
     *
     * <p>Vanilla's {@code isInWaterOrBubble()} is true as soon as any sliver of
     * the hitbox overlaps water. A farmer walking past the waterfall columns
     * that feed its field's irrigation holes brushed one with an arm, or put a
     * toe over a hole, whose surface the waterfall above lifts to the full
     * block. The escape then took over: it dropped the farmer's route, pushed
     * it up to swim into the roof, and steered it at an exit through the very
     * water it had brushed. Its job waited on a route that no longer existed
     * until that timed out.
     *
     * <p>So the villager's own column has to be wet, at its feet or its waist.
     * Off the ground in water is always an escape. On the ground it is one only
     * where the water is too deep to wade, like the bottom of a pond or an
     * irrigation hole. Shallow run-off and a waterfall pouring onto solid
     * ground are walked through.
     */
    public boolean needsWaterRescue() {
        if (puppet == null || !puppet.isInWaterOrBubble()) return false;
        Level level = puppet.level();
        BlockPos feet = BlockPos.containing(puppet.getX(), puppet.getY() + 0.1D, puppet.getZ());
        BlockPos waist = BlockPos.containing(puppet.getX(), puppet.getY() + WAIST_HEIGHT, puppet.getZ());
        FluidState atFeet = level.getFluidState(feet);
        boolean feetInWater = atFeet.is(FluidTags.WATER);
        if (!feetInWater && !level.getFluidState(waist).is(FluidTags.WATER)) return false;
        if (!puppet.onGround()) return true;
        if (!feetInWater) return false;
        if (atFeet.hasProperty(FlowingFluid.FALLING) && atFeet.getValue(FlowingFluid.FALLING)) return false;
        double surface = feet.getY() + atFeet.getHeight(level, feet);
        return surface - puppet.getY() > WADE_DEPTH;
    }

    // -------------------------------------------------------------------------
    // Per-tick (called by VillageManager on the server thread)
    // -------------------------------------------------------------------------

    public void tick(NavGraph graph) {
        if (puppet == null || !com.alex.test.village.stuckwatch.StuckWatch.recording()) {
            tickWalk(graph);
            return;
        }
        // Stuck catcher: the body as Minecraft moved it, then the walker's turn.
        NpcEntity watched = puppet;
        blackBox.begin(this, watched);
        tickWalk(graph);
        blackBox.end(this, watched, graph);
    }

    private void tickWalk(NavGraph graph) {
        // Watches only; first, so it sees every tick whatever returns early below.
        if (puppet != null) walkBack.sample(this, puppet);
        // Before the early returns below: a door left open for someone in its
        // doorway shuts the tick they step out, whatever this villager is doing.
        if (puppet != null) doorsLeftOpen.closeVacatedDoors(puppet);

        // Seated, the chair's seat entity places the villager every tick, and
        // its body is inside the chair block by design: no walking, and no
        // "physically blocked" rescue teleporting it out of its own chair.
        if (puppet != null && puppet.isChairSeated()) {
            puppet.setLadderClimbTarget(null);
            puppet.getNavigation().stop();
            return;
        }
        if (puppet != null && puppet.isEating()) {
            puppet.setLadderClimbTarget(null);
            puppet.holdStillWhileEating();
            return;
        }

        if (uiPauseTicks > 0) {
            uiPauseTicks--;
            if (puppet != null) {
                puppet.setLadderClimbTarget(null);
                puppet.getNavigation().stop();
                puppet.holdStillForAnimation();
            }
            return;
        }

        boolean inWater = needsWaterRescue();
        logSwimOutProgress(inWater);
        if (inWater && tickWaterEscape(graph)) {
            return;
        }
        if (puppet != null && !inWater) {
            // Out of the water, the swim-out stays on for the settle that
            // tickWaterEscape set on every tick in it, then clears. The settle
            // used to re-arm itself whenever it ran out with an escape goal
            // still set, and the goal was always still set, so a villager
            // counted as escaping water until something stopped its walk. A
            // miner dropped into water by a stair trap waited out its walk's
            // 10 s timeout instead of carrying on to its dig spot.
            if (waterEscapeSettleTicks > 0) {
                waterEscapeSettleTicks--;
            } else {
                if (waterEscapeGoal != null || waterExit != null) {
                    waterEscapeGoal = null;
                    waterExit = null;
                    waterEscapeRepathTimer = 0;
                    resetWaterExitStuckState();
                }
                endSwimOutLog("out of the water and settled");
            }
        }

        if (puppet != null && graph != null && enforceVillageBounds(graph)) {
            return;
        }

        if (puppet != null && graph != null && !puppet.isSleeping()
                && isMoving() && isOnAvoidedSurface(graph)) {
            BlockPos goal = pendingGoal;
            float speed = pendingSpeed;
            if (tryRecoverToNearbySafePosition(graph, goalsOf(goal), false)) {
                cancelActivePathState();
                if (goal != null) {
                    LOGGER.info("[VillagerModel {}] Re-pathing goal {} with detailed graph steps after avoided-surface recovery.",
                            shortId(), fmt(goal));
                    requestMoveTo(graph, goal, speed, false, false);
                }
                return;
            }
        }

        // The overlap test is a full collision query. A sleeping villager is
        // posed into its bed, and anyone else wedged in a block can wait a
        // second for recovery, so it runs once every BLOCKED_CHECK_INTERVAL,
        // offset by id so a village's checks do not all land on one tick.
        boolean checkBlocked = puppet != null && !puppet.isSleeping()
                && Math.floorMod(puppet.tickCount + npcId.hashCode(), BLOCKED_CHECK_INTERVAL) == 0;
        if (checkBlocked && isPhysicallyBlocked(puppet)) {
            BlockPos goal = pendingGoal;
            float speed = pendingSpeed;
            boolean smoothPath = pendingSmoothPath;
            LOGGER.info("[VillagerModel {}] Physically blocked at {}; attempting nearby recovery.",
                    shortId(), fmtNpc());
            com.alex.test.village.stuckwatch.StuckWatch.caught(this, graph,
                    com.alex.test.village.stuckwatch.StuckWatch.Kind.BLOCKED, "its body is inside a block at " + fmtNpc());
            if (tryRecoverToNearbySafePosition(graph, goalsOf(goal), false)) {
                cancelActivePathState();
                if (goal != null) requestMoveTo(graph, goal, speed, false, smoothPath);
                return;
            }
            LOGGER.info("[VillagerModel {}] No safe recovery position near {}; failing goal {}.",
                    shortId(), fmtNpc(), fmt(goal));
            markMoveFailed(goal);
            return;
        }

        // Poll a completed async pathfind result
        if (pendingPath != null && pendingPath.isDone()) {
            AStarPathfinder.Route route = pendingPath.getNow(null);
            pendingPath = null;

            if (route != null && !route.waypoints().isEmpty()) {
                // Stale only when a cell the route crosses changed since it
                // was planned, or the graph was rebuilt whole. An edit at the
                // far end of the village used to make every walker re-plan.
                if (graph != pendingGraph
                        || (graph != null && graph.changedSince(pendingGraphVersion, route.cells()))) {
                    LOGGER.debug("[VillagerModel {}] Path stale — re-pathing.", shortId());
                    if (pendingGoal != null) {
                        requestMoveTo(graph, pendingGoal, pendingSpeed, false, pendingSmoothPath);
                    }
                } else {
                    // The route follower walks every route (the old walker is gone, 10-18).
                    MoveToCommand next = FollowWalk.forRoute(this, graph, puppet, route, pendingSpeed,
                            pendingSmoothPath, pendingExcludedCells);
                    if (next == null) {
                        // Only a route with no tiles to walk, or no body or map.
                        LOGGER.info("[VillagerModel {}] Route to {} has nothing to walk; goal failed.",
                                shortId(), fmt(pendingGoal));
                        markMoveFailed(pendingGoal);
                    } else {
                        // A door still occupied when this walk ends is not shut on
                        // whoever stands in it; see doorsLeftOpen.
                        next.keepOccupiedDoorsWith(doorsLeftOpen);
                        // A re-plan mid-walk used to drop the old route together
                        // with its list of doors to close, so those stayed open for
                        // good. Closing them now could shut one on a villager still
                        // in the doorway; the new route closes them once it is clear.
                        if (activeMove != null) next.inheritOpenedDoors(activeMove);
                        if (puppet != null) walkBack.noteRoute(puppet, route.waypoints(), pendingRequestedAt, 0);
                        activeMove = next;
                    }
                }
            } else {
                LOGGER.debug("[VillagerModel {}] No path to goal {}.", shortId(), fmt(pendingGoal));
                BlockPos failed = pendingGoal;
                boolean restricted = !pendingExcludedCells.isEmpty();
                markMoveFailed(failed);
                if (!restricted) rescueIfTrapped(graph, failed);
            }
        }

        // In deep water the swim-out steers by itself (tickWaterEscape) and the walk waits. Once
        // out, the walk it was given is planned again from where the villager came ashore: the
        // swim-out carried it off the route's line, so the waiting walk would only stick there.
        if (activeMove != null && puppet != null && inWater) {
            walkHeldInWater = true;
            return;
        }
        if (walkHeldInWater) {
            walkHeldInWater = false;
            if (activeMove != null && puppet != null) {
                LOGGER.debug("[VillagerModel {}] Out of the water at {}; planning the walk to {} again from here.",
                        shortId(), fmtNpc(), fmt(pendingGoal));
                activeMove.closeAllOpenedDoors(puppet);
                activeMove = null;
                // A route already being planned (the swim-out's) takes over when it comes.
                if (pendingPath == null) requestMoveTo(graph, pendingGoal, pendingSpeed, false, pendingSmoothPath);
                return;
            }
        }

        // Frozen ground (10-18): outside every player's simulation distance Minecraft does not
        // move the body, so the walk read as "no progress": re-plans, safe-spot hops, good steps
        // marked failed, jobs given up (a druid at the village's far edge). The walk waits
        // instead and carries on where it was once the body moves again: the follower counts
        // its own ticks, so nothing is stale. Since round 5 of far villagers stay awake the mod
        // moves far bodies itself (FarAwake); a wait is then short (ground still loading).
        if (puppet != null && WalkFeel.frozenGround
                && (activeMove != null || frozenGroundSince != Long.MIN_VALUE)) {
            if (!com.alex.test.village.farawake.FarAwake.bodyMovesThisTick(puppet)) {
                if (activeMove != null) {
                    if (frozenGroundSince == Long.MIN_VALUE) {
                        frozenGroundSince = gameTime();
                        LOGGER.info("[FrozenGround] {} ({}) waits at {}: its body did not move this tick (past every"
                                        + " player's simulation distance, ground not loaded yet); walk to {} held until it moves",
                                shortId(), puppet.getProfession(), fmtNpc(), fmt(pendingGoal));
                    }
                    return;
                }
            } else if (frozenGroundSince != Long.MIN_VALUE) {
                LOGGER.info("[FrozenGround] {} ({}) at {}: the body moves again after {} s{}",
                        shortId(), puppet.getProfession(), fmtNpc(), (gameTime() - frozenGroundSince) / 20,
                        activeMove != null ? "; walks on to " + fmt(pendingGoal) : "");
                frozenGroundSince = Long.MIN_VALUE;
            }
        }

        // Advance active movement command
        if (activeMove != null && puppet != null) {
            switch (activeMove.tick(puppet)) {
                case DONE -> {
                    if (pendingPath != null) {
                        // The walk it finished had already been replaced by a
                        // newer request still being planned (a guard re-aiming
                        // at a mob that moved keeps its old route running
                        // meanwhile). That request is not done: drop the old
                        // route and wait for the new one.
                        activeMove = null;
                        puppet.setLadderClimbTarget(null);
                        puppet.getNavigation().stop();
                        break;
                    }
                    LOGGER.debug("[VillagerModel {}] Reached destination {} at npc {}.",
                            shortId(), fmt(pendingGoal), fmtNpc());
                    walkBack.note(puppet, WalkBackLog.Kind.DONE, " at " + fmt(pendingGoal));
                    lastCompletedGoal = pendingGoal;
                    lastFailedGoal = null;
                    activeMove  = null;
                    pendingGoal = null;
                    pendingSmoothPath = true;
                    pendingExcludedCells = Set.of();
                    stuckRepathAttempts = 0;
                    stuckRecoveries = 0;
                    puppet.setLadderClimbTarget(null);
                    puppet.getNavigation().stop();
                    puppet.setAnimation(VillagerAnimationState.IDLE);
                }
                case STUCK -> {
                    LOGGER.debug("[VillagerModel {}] Stuck — re-pathing. goal={} {}",
                            shortId(), fmt(pendingGoal), activeMove.debugState(puppet));
                    walkBack.note(puppet, WalkBackLog.Kind.STUCK,
                            " at wp " + (activeMove.currentWaypointIndex() + 1)
                                    + (activeMove.stuckOffRoute() ? " (off the route)" : ""));
                    LOGGER.info("[StuckSpot] {}", stuckSpotLine(graph));
                    com.alex.test.village.stuckwatch.StuckWatch.caught(this, graph,
                            com.alex.test.village.stuckwatch.StuckWatch.Kind.STUCK,
                            // The follower says off-route for every stuck but repeated failed jumps at one step
                            // (so only that step is blamed); its rule names the cause either way.
                            "route follower: " + activeMove.stuckRule()
                                    + (activeMove.stuckOffRoute() ? "" : " (step marked failed in the map)"));
                    // Off its route (fell, or pacing under a waypoint it cannot
                    // reach) the step ahead was never tried, so it is not to blame.
                    if (!activeMove.stuckOffRoute()) rememberFailedStep(graph, activeMove.currentWaypoint());
                    // Stuck loop (10-18): the same tile and reason three times in 30 s.
                    if (WalkFeel.stuckLoop) noteStuckLoop(graph);
                    // Stuck spots B2: a second stuck on a smoothed route walks
                    // the graph step by step instead (one barrel on a walkway
                    // took 92 smoothed re-plans), and that detailed re-plan gets
                    // one extra try before the recovery teleport.
                    boolean goDetailed = pendingSmoothPath && WalkFeel.stuckDetailed && stuckRepathAttempts >= 1;
                    if (goDetailed) {
                        pendingSmoothPath = false;
                        LOGGER.debug("[VillagerModel {}] Stuck again on a smoothed route; re-pathing goal {} with detailed graph steps.",
                                shortId(), fmt(pendingGoal));
                    }
                    int repathsAllowed = MAX_STUCK_REPATHS + (goDetailed && WalkFeel.stuckDetailed ? 1 : 0);
                    activeMove.closeAllOpenedDoors(puppet);
                    activeMove = null;
                    if (pendingGoal != null && stuckRepathAttempts++ < repathsAllowed) {
                        requestMoveTo(graph, pendingGoal, pendingSpeed, false, pendingSmoothPath);
                    } else if (pendingGoal != null
                            && stuckRecoveries++ < MAX_STUCK_RECOVERIES
                            && stuckRescueAllowed()
                            && tryRecoverToNearbySafePosition(graph, goalsOf(pendingGoal), false)) {
                        lastStuckRescueAt = gameTime();
                        // Bounded: relocating to a nearby safe node nearly always
                        // succeeds, so without a cap this branch never falls
                        // through to the blacklist below and a goal that simply
                        // cannot be reached -- a bed behind a doorway somebody
                        // bricked up -- is re-pathed every few seconds forever,
                        // burning a full A* over the village graph each time.
                        LOGGER.debug("[VillagerModel {}] Recovered from repeated stuck; re-pathing goal {}.",
                                shortId(), fmt(pendingGoal));
                        requestMoveTo(graph, pendingGoal, pendingSpeed, false, pendingSmoothPath);
                    } else {
                        LOGGER.debug("[VillagerModel {}] Stuck at goal {}; blacklisting temporarily. npc={}",
                                shortId(), fmt(pendingGoal), fmtNpc());
                        com.alex.test.village.stuckwatch.StuckWatch.caught(this, graph,
                                com.alex.test.village.stuckwatch.StuckWatch.Kind.GAVE_UP,
                                "gave up on " + fmt(pendingGoal) + " after " + stuckRepathAttempts
                                        + " stuck re-plans and " + stuckRecoveries + " moves to a safe spot");
                        markMoveFailed(pendingGoal);
                    }
                }
                case REPLAN -> {
                    // A route too long to read at once, walked as far as it was read: the
                    // rest is planned from here. Not a stuck, so no stuck counts.
                    LOGGER.debug("[VillagerModel {}] Walked as far as the route to {} was read; planning the rest from {}.",
                            shortId(), fmt(pendingGoal), fmtNpc());
                    activeMove.closeAllOpenedDoors(puppet);
                    activeMove = null;
                    if (pendingGoal != null && graph != null) {
                        requestMoveTo(graph, pendingGoal, pendingSpeed, false, pendingSmoothPath);
                    } else {
                        markMoveFailed(pendingGoal);
                    }
                }
                case RUNNING -> { /* keep going */ }
            }
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Stuck loop ({@link WalkFeel#stuckLoop}, 10-18): counts stucks on one tile for one reason.
     * Every {@link #LOOP_STUCKS}th within {@link #LOOP_WINDOW_TICKS} marks the step ahead failed in
     * the map, unless the walk or the jump rule already marked one, so the next route leaves the
     * tile another way or the goal is given up; one {@code [StuckLoop]} line says so.
     */
    private void noteStuckLoop(NavGraph graph) {
        if (puppet == null || activeMove == null) return;
        long feet = NavGraph.feetCell(puppet).asLong();
        String rule = activeMove.stuckRule();
        int cut = rule.indexOf(" (");
        if (cut > 0) rule = rule.substring(0, cut);
        long now = gameTime();
        if (feet == loopFeet && rule.equals(loopRule) && now - loopSince <= LOOP_WINDOW_TICKS) {
            loopStucks++;
        } else {
            loopFeet = feet;
            loopRule = rule;
            loopStucks = 1;
            loopSince = now;
        }
        if (loopStucks % LOOP_STUCKS != 0) return;
        boolean already = !activeMove.stuckOffRoute() || activeMove.stepMarkedFailed();
        String done = already ? "the step was already marked failed" : blameStepAhead(graph);
        LOGGER.info("[StuckLoop] {} ({}) stuck {} times in {} s at {} ({}); {}",
                shortId(), puppet.getProfession(), loopStucks, (now - loopSince) / 20,
                fmt(BlockPos.of(feet)), activeMove.stuckRule(), done);
    }

    /** Marks the step from the villager's tile to the walk's next tile failed in the map; says what it did. */
    private String blameStepAhead(NavGraph graph) {
        if (graph == null || puppet == null || puppet.isInWaterOrBubble()) return "no step marked (no map, or in water)";
        BlockPos feet = NavGraph.feetCell(puppet);
        NavNode from = graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
        if (from == null) from = graph.nodeAt(feet.getX(), feet.getY() - 1, feet.getZ());
        BlockPos wp = activeMove.currentWaypoint();
        if (from == null || wp == null) return "no step marked (no map tile here)";
        NavNode to = null;
        for (NavNode nb : from.neighbours()) {
            if (nb.x == wp.getX() && nb.z == wp.getZ() && Math.abs(nb.feetY - wp.getY()) <= 1) {
                to = nb;
                break;
            }
        }
        if (to == null) return "no step marked (the next tile " + fmt(wp) + " is not beside it)";
        graph.markStepFailed(from, to, FAILED_STEP_MILLIS);
        return "step " + from + " -> " + to + " marked failed";
    }

    /** With {@link WalkFeel#stuckLoop}, a stuck rescue at most every {@link #STUCK_RESCUE_EVERY_TICKS}. */
    private boolean stuckRescueAllowed() {
        if (!WalkFeel.stuckLoop || lastStuckRescueAt == Long.MIN_VALUE) return true;
        long since = gameTime() - lastStuckRescueAt;
        return since < 0 || since >= STUCK_RESCUE_EVERY_TICKS;
    }

    private void markMoveFailed(BlockPos goal) {
        if (puppet != null) walkBack.note(puppet, WalkBackLog.Kind.FAILED, " to " + fmt(goal));
        pendingExcludedCells = Set.of();
        if (activeMove != null && puppet != null) activeMove.closeAllOpenedDoors(puppet);
        activeMove = null;
        pendingPath = null;
        pendingGraph = null;
        pendingGoal = null;
        pendingSmoothPath = true;
        stuckRepathAttempts = 0;
        stuckRecoveries = 0;
        waterEscapeGoal = null;
        waterExit = null;
        waterEscapeRepathTimer = 0;
        waterEscapeSettleTicks = 0;
        resetWaterExitStuckState();
        lastFailedGoal = goal;
        lastCompletedGoal = null;
        if (goal != null) failedTargetUntil.put(goal.asLong(), gameTime() + FAILED_TARGET_BLACKLIST_TICKS);
        if (puppet != null) {
            puppet.setLadderClimbTarget(null);
            puppet.getNavigation().stop();
            puppet.setAnimation(VillagerAnimationState.IDLE);
        }
    }

    private void cancelActivePathState() {
        if (activeMove != null && puppet != null) activeMove.closeAllOpenedDoors(puppet);
        activeMove = null;
        cancelPendingPath();
        pendingGraph = null;
        if (puppet != null) {
            puppet.setLadderClimbTarget(null);
            puppet.getNavigation().stop();
        }
    }

    private void cancelPendingPath() {
        if (pendingPath != null) pendingPath.cancel(false);
        pendingPath = null;
    }

    private boolean tickWaterEscape(NavGraph graph) {
        if (puppet == null) return false;

        if (waterEscapeStartedAt < 0) {
            waterEscapeStartedAt = gameTime();
            LOGGER.info("[VillagerModel {}] Swim-out started at {} (onGround={}); walk to {} handed over to it.",
                    shortId(), fmtNpc(), puppet.onGround(), fmt(pendingGoal));
            walkBack.note(puppet, WalkBackLog.Kind.WATER, "");
        }
        puppet.setLadderClimbTarget(null);
        waterEscapeSettleTicks = WATER_ESCAPE_SETTLE_TICKS;
        swimUpLikeVanilla();
        if (waterExit != null) {
            swimTowardWaterExit(waterExit);
            if (isStuckOnWaterExit()) {
                LOGGER.info("[VillagerModel {}] Swim-out made no headway toward exit {} (via {}) for 3 s at {}; trying another.",
                        shortId(), fmt(waterExit.land()), fmt(waterExit.approach()), fmtNpc());
                blacklistWaterExit(waterExit);
                waterExit = null;
                waterEscapeGoal = null;
                waterEscapeRepathTimer = 0;
                cancelActivePathState();
                return true;
            }
        } else if (waterEscapeGoal != null) {
            swimTowardLand(waterEscapeGoal);
        }
        if (graph == null) {
            puppet.getNavigation().stop();
            return true;
        }

        if (--waterEscapeRepathTimer <= 0
                || waterEscapeGoal == null
                || hasRecentMoveFailure(waterEscapeGoal)
                || !isMoving()) {
            waterEscapeRepathTimer = WATER_ESCAPE_REPATH_TICKS;
            WaterExit exit = bestWaterExit(graph);
            BlockPos dryGoal = exit != null ? exit.land() : nearestDryNode(graph);
            if (dryGoal == null) {
                puppet.getNavigation().stop();
                return true;
            }

            waterExit = exit;
            waterEscapeGoal = dryGoal;
            if (waterExit != null) {
                resetWaterExitStuckState();
                swimTowardWaterExit(waterExit);
            } else {
                swimTowardLand(dryGoal);
            }
            cancelActivePathState();
            requestWaterEscapeMove(graph, dryGoal);
        }
        return false;
    }

    private void requestWaterEscapeMove(NavGraph graph, BlockPos dryGoal) {
        if (puppet == null || graph == null || dryGoal == null) return;

        // No map tile near: the swim-out's own steering (swimTowardLand) carries it on.
        NavNode startNode = graph.nearest(
                puppet.blockPosition(), WATER_ESCAPE_XZ_RADIUS, WATER_ESCAPE_Y_RADIUS);
        if (startNode == null) return;

        pendingGoal = dryGoal;
        pendingSpeed = DEFAULT_SPEED * 0.45F;
        pendingGraph = graph;
        pendingSmoothPath = true;
        lastFailedGoal = null;
        lastCompletedGoal = null;

        cancelPendingPath();
        pendingGraphVersion = graph.version();
        pendingPath = CompletableFuture.supplyAsync(
                () -> AStarPathfinder.findRoute(graph, startNode.feetPos(), dryGoal, true, Set.of(), Set.of()));
    }

    private void swimUpLikeVanilla() {
        Vec3 movement = puppet.getDeltaMovement();
        double upward = movement.y < WATER_SWIM_UP_SPEED ? WATER_SWIM_UP_SPEED : movement.y;
        puppet.setDeltaMovement(movement.x, upward, movement.z);
        puppet.setSwimming(true);
    }

    private void swimTowardLand(BlockPos dryGoal) {
        swimToward(dryGoal, dryGoal.getY() - WATER_FOOT_DIP_TOLERANCE_BLOCKS);
    }

    private void swimToward(BlockPos targetPos, double wantedY) {
        Vec3 movement = puppet.getDeltaMovement();
        Vec3 target = Vec3.atCenterOf(targetPos);
        Vec3 horizontal = new Vec3(target.x - puppet.getX(), 0.0D, target.z - puppet.getZ());
        double distSqr = horizontal.lengthSqr();
        if (distSqr < 0.01D) return;

        Vec3 push = horizontal.normalize().scale(WATER_SWIM_TO_LAND_ACCEL);
        double nextX = movement.x + push.x;
        double nextZ = movement.z + push.z;
        double horizontalSpeed = Math.sqrt(nextX * nextX + nextZ * nextZ);
        if (horizontalSpeed > WATER_SWIM_TO_LAND_MAX_SPEED) {
            double scale = WATER_SWIM_TO_LAND_MAX_SPEED / horizontalSpeed;
            nextX *= scale;
            nextZ *= scale;
        }

        puppet.setDeltaMovement(nextX, movement.y, nextZ);
        faceMovement(push);
        puppet.getMoveControl().setWantedPosition(
                target.x, wantedY,
                target.z, DEFAULT_SPEED * 0.4F);
    }

    private void swimTowardWaterExit(WaterExit exit) {
        BlockPos target = waterExitSwimTarget(exit);
        double wantedY = target.equals(exit.approach())
                ? exit.approach().getY() + 0.35D
                : exit.land().getY() - WATER_FOOT_DIP_TOLERANCE_BLOCKS;
        swimToward(target, wantedY);
    }

    private BlockPos waterExitSwimTarget(WaterExit exit) {
        double dx = puppet.getX() - (exit.approach().getX() + 0.5D);
        double dz = puppet.getZ() - (exit.approach().getZ() + 0.5D);
        boolean nearApproach = dx * dx + dz * dz <= WATER_EXIT_APPROACH_DIST_SQ;
        boolean highEnough = puppet.getY() + MAX_WATER_EXIT_STEP_UP >= exit.land().getY();
        return nearApproach && highEnough ? exit.land() : exit.approach();
    }

    private void faceMovement(Vec3 direction) {
        if (direction.lengthSqr() < 1.0E-6D) return;

        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        puppet.setYRot(yaw);
        puppet.setYHeadRot(yaw);
        puppet.yBodyRot = yaw;
        puppet.yBodyRotO = yaw;
        puppet.getLookControl().setLookAt(
                puppet.getX() + direction.x,
                puppet.getEyeY(),
                puppet.getZ() + direction.z,
                30.0F,
                30.0F);
    }

    private BlockPos nearestDryNode(NavGraph graph) {
        if (puppet == null || !(puppet.level() instanceof ServerLevel sl)) return null;

        NavNode best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos current = puppet.blockPosition();
        for (NavNode node : graph.nodesInBox(current, WATER_ESCAPE_XZ_RADIUS,
                WATER_ESCAPE_Y_RADIUS)) {
            if (node.isAvoidedSurface()) continue;
            if (Math.abs(node.x - current.getX()) > WATER_ESCAPE_XZ_RADIUS
                    || Math.abs(node.z - current.getZ()) > WATER_ESCAPE_XZ_RADIUS
                    || Math.abs(node.feetY - current.getY()) > WATER_ESCAPE_Y_RADIUS) {
                continue;
            }

            BlockPos feet = node.feetPos();
            if (!NavGraph.isDryWalkable(sl, feet)) continue;
            if (!hasEntityRoomAt(puppet, feet)) continue;

            double dist = feet.distSqr(current);
            if (dist < bestDist) {
                bestDist = dist;
                best = node;
            }
        }
        return best != null ? best.feetPos() : null;
    }

    private WaterExit bestWaterExit(NavGraph graph) {
        if (puppet == null || !(puppet.level() instanceof ServerLevel sl)) return null;

        WaterExit best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos current = puppet.blockPosition();
        long now = gameTime();
        pruneWaterExitBlacklist(now);

        for (NavNode node : graph.nodesInBox(current, WATER_ESCAPE_XZ_RADIUS,
                WATER_ESCAPE_Y_RADIUS)) {
            if (node.isAvoidedSurface()) continue;
            if (Math.abs(node.x - current.getX()) > WATER_ESCAPE_XZ_RADIUS
                    || Math.abs(node.z - current.getZ()) > WATER_ESCAPE_XZ_RADIUS
                    || Math.abs(node.feetY - current.getY()) > WATER_ESCAPE_Y_RADIUS) {
                continue;
            }

            BlockPos land = node.feetPos();
            if (isWaterExitBlacklisted(land, now)) continue;
            if (!NavGraph.isDryWalkable(sl, land)) continue;
            if (!hasEntityRoomAt(puppet, land)) continue;

            BlockPos approach = bestApproachForExit(sl, land);
            if (approach == null) continue;

            double climb = land.getY() - approach.getY();
            double distToApproach = approach.distSqr(current);
            double distToLand = land.distSqr(current);
            double score = distToApproach + distToLand * 0.15D + climb * climb * 6.0D;
            if (score < bestScore) {
                bestScore = score;
                best = new WaterExit(land, approach);
            }
        }
        return best;
    }

    private BlockPos bestApproachForExit(ServerLevel sl, BlockPos land) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos current = puppet.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy >= -2; dy--) {
                BlockPos approach = land.relative(dir).offset(0, dy, 0);
                if (!isValidWaterExitApproach(sl, land, approach)) continue;
                double score = approach.distSqr(current) + Math.abs(land.getY() - approach.getY()) * 4.0D;
                if (score < bestScore) {
                    bestScore = score;
                    best = approach;
                }
            }
        }
        return best;
    }

    private boolean isValidWaterExitApproach(ServerLevel sl, BlockPos land, BlockPos approach) {
        double climb = land.getY() - approach.getY();
        if (climb < -0.25D || climb > MAX_WATER_EXIT_STEP_UP) return false;
        if (sl.getFluidState(approach).isEmpty()) return false;
        if (!sl.getBlockState(approach).getCollisionShape(sl, approach).isEmpty()) return false;
        BlockPos head = approach.above();
        return sl.getBlockState(head).getCollisionShape(sl, head).isEmpty();
    }

    private boolean isStuckOnWaterExit() {
        if (++waterExitStuckCheckTimer < WATER_EXIT_STUCK_CHECK_TICKS) return false;
        waterExitStuckCheckTimer = 0;

        double x = puppet.getX();
        double z = puppet.getZ();
        if (!Double.isNaN(lastWaterExitCheckX)) {
            double dx = x - lastWaterExitCheckX;
            double dz = z - lastWaterExitCheckZ;
            waterExitStuckPeriods = (dx * dx + dz * dz < WATER_EXIT_MIN_PROGRESS_SQ)
                    ? waterExitStuckPeriods + 1
                    : 0;
        }
        lastWaterExitCheckX = x;
        lastWaterExitCheckZ = z;
        return waterExitStuckPeriods >= WATER_EXIT_STUCK_PERIODS;
    }

    /**
     * Once a second during a swim-out: where the villager is and what the
     * swim-out is doing about it. A miner dropped into water by a stair trap
     * stood still with nothing at all in the log, so nobody could tell the
     * swim-out's part from its walk's.
     */
    private void logSwimOutProgress(boolean inWater) {
        if (waterEscapeStartedAt < 0 || puppet == null) return;
        long elapsed = gameTime() - waterEscapeStartedAt;
        if (elapsed <= 0 || elapsed % 20 != 0) return;
        String toward = waterExit != null
                ? "exit " + fmt(waterExit.land()) + " via " + fmt(waterExit.approach())
                : waterEscapeGoal != null ? "dry tile " + fmt(waterEscapeGoal) : "nothing";
        String route = activeMove != null ? "walking" : pendingPath != null ? "pathing" : "idle";
        LOGGER.info("[VillagerModel {}] Swim-out {} s at {}: inWater={} onGround={} touchingWater={} toward {}; route to {} {}; settle {}.",
                shortId(), elapsed / 20, fmtNpc(), inWater, puppet.onGround(), puppet.isInWaterOrBubble(),
                toward, fmt(pendingGoal), route, waterEscapeSettleTicks);
    }

    private void endSwimOutLog(String why) {
        if (waterEscapeStartedAt < 0) return;
        LOGGER.info("[VillagerModel {}] Swim-out over at {} after {} s: {}.",
                shortId(), fmtNpc(), String.format(java.util.Locale.ROOT, "%.1f",
                        (gameTime() - waterEscapeStartedAt) / 20.0D), why);
        waterEscapeStartedAt = -1L;
    }

    private void resetWaterExitStuckState() {
        waterExitStuckCheckTimer = 0;
        waterExitStuckPeriods = 0;
        lastWaterExitCheckX = Double.NaN;
        lastWaterExitCheckZ = Double.NaN;
    }

    private void blacklistWaterExit(WaterExit exit) {
        failedWaterExitUntil.put(exit.land().asLong(), gameTime() + WATER_EXIT_BLACKLIST_TICKS);
    }

    private boolean isWaterExitBlacklisted(BlockPos land, long now) {
        Long until = failedWaterExitUntil.get(land.asLong());
        return until != null && now < until;
    }

    private void pruneWaterExitBlacklist(long now) {
        failedWaterExitUntil.entrySet().removeIf(entry -> now >= entry.getValue());
    }

    /**
     * Marks the jump a stuck villager is standing against as one routes should
     * not take, so the re-path goes another way — or fails at once if there is
     * none, instead of walking into the same ledge for another five seconds.
     * The graph allowed the jump from block geometry alone; five seconds
     * pressed against it says the villager cannot actually make it.
     *
     * <p>Only a real jump up, ahead toward the waypoint and close enough to be
     * touching, is blamed. Stuck against a wall or another villager leaves the
     * graph alone.
     */
    private void rememberFailedStep(NavGraph graph, BlockPos waypoint) {
        if (graph == null || puppet == null || waypoint == null
                || puppet.isInWaterOrBubble()) return;
        // It may well be mid-attempt, its feet a cell above the floor it jumped from.
        BlockPos feet = NavGraph.feetCell(puppet);
        NavNode from = graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
        if (from == null) from = graph.nodeAt(feet.getX(), feet.getY() - 1, feet.getZ());
        if (from == null || from.climb) return;

        double toX = waypoint.getX() + 0.5D - puppet.getX();
        double toZ = waypoint.getZ() + 0.5D - puppet.getZ();
        NavNode step = null;
        double bestAhead = 0.0D;
        for (NavNode nb : from.neighbours()) {
            if (nb.climb || nb.standY - from.standY <= FAILED_STEP_MIN_RISE) continue;
            double ahead = (nb.x - from.x) * toX + (nb.z - from.z) * toZ;
            if (ahead > bestAhead) {
                bestAhead = ahead;
                step = nb;
            }
        }
        if (step == null) return;

        double dx = step.x + 0.5D - puppet.getX();
        double dz = step.z + 0.5D - puppet.getZ();
        double reach = 0.5D + puppet.getBbWidth() * 0.5D + FAILED_STEP_CONTACT_SLACK;
        if (dx * dx + dz * dz > reach * reach) return;

        graph.markStepFailed(from, step, FAILED_STEP_MILLIS);
        LOGGER.info("[VillagerModel {}] Could not jump from {} up to {}; routing around it.",
                shortId(), from, step);
    }

    /**
     * One {@code [StuckSpot]} line per stuck, with what the stuck rules cannot
     * see: the villager's exact spot and the cell its feet are in, the waypoint
     * and how far above the feet it stands, how it was being steered, the hops
     * tried, which re-plan this is and whether the route was smoothed, and the
     * blocks at feet and head height in the next cell toward the waypoint. Meant
     * to be read in bulk: sort by spot, then by the blocks ahead.
     */
    public String stuckSpotLine(NavGraph graph) {
        if (puppet == null || activeMove == null) return shortId() + " (no walk)";
        StringBuilder line = new StringBuilder(220);
        line.append(shortId()).append(" (").append(puppet.getProfession()).append(") at ").append(fmtNpc());
        BlockPos feet = NavGraph.feetCell(puppet);
        line.append(" feet ").append(fmt(feet));
        line.append(puppet.onGround() ? " on ground" : " in air");
        if (puppet.isInWaterOrBubble()) line.append(" in water");
        if (puppet.horizontalCollision) line.append(" pressed");
        BlockPos wp = activeMove.currentWaypoint();
        line.append(" wp ").append(activeMove.currentWaypointIndex() + 1).append('/').append(activeMove.waypointCount())
                .append(' ').append(fmt(wp));
        if (wp != null) {
            double rise = NavGraph.standY(puppet.level(), wp) - puppet.getY();
            line.append(String.format(java.util.Locale.ROOT, " rise %.2f", rise));
            double dx = wp.getX() + 0.5 - puppet.getX();
            double dz = wp.getZ() + 0.5 - puppet.getZ();
            line.append(String.format(java.util.Locale.ROOT, " away %.2f", Math.sqrt(dx * dx + dz * dz)));
            Direction toward = Math.abs(dx) >= Math.abs(dz)
                    ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                    : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
            BlockPos aheadFeet = feet.relative(toward);
            line.append(" ahead ").append(toward.getSerializedName())
                    .append(" feet=").append(blockName(aheadFeet))
                    .append(" head=").append(blockName(aheadFeet.above()))
                    .append(" floor=").append(blockName(aheadFeet.below()));
            NavNode here = graph == null ? null : graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
            NavNode next = graph == null ? null : graph.nodeAt(aheadFeet.getX(), aheadFeet.getY(), aheadFeet.getZ());
            if (next == null && graph != null) next = graph.nodeAt(aheadFeet.getX(), aheadFeet.getY() + 1, aheadFeet.getZ());
            line.append(" map=").append(here == null ? "no node here"
                    : next == null ? "no node ahead"
                    : here.canStepTo(next) ? (here.hasFailedStepTo(next) ? "step failed before" : "step ok") : "no step");
        }
        line.append(" mode=").append(activeMove.spyMode());
        line.append(" hops=").append(activeMove.spyHops());
        line.append(" repath=").append(stuckRepathAttempts).append(pendingSmoothPath ? " smoothed" : " detailed");
        if (activeMove.stuckOffRoute()) line.append(" off-route");
        // Which stuck rule fired, and for a route follower what it ran into (10-18).
        if (!activeMove.stuckRule().isEmpty()) line.append(" rule=").append(activeMove.stuckRule());
        line.append(" goal=").append(fmt(pendingGoal));
        return line.toString();
    }

    private String blockName(BlockPos pos) {
        if (puppet == null) return "?";
        return BuiltInRegistries.BLOCK.getKey(puppet.level().getBlockState(pos).getBlock()).getPath();
    }

    private boolean isPhysicallyBlocked(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel sl)) return false;
        return !sl.noCollision(npc, npc.getBoundingBox().deflate(0.05D));
    }

    private boolean isOnAvoidedSurface(NavGraph graph) {
        if (puppet == null || graph == null) return false;
        BlockPos feet = puppet.blockPosition();
        NavNode node = graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
        if (node != null && node.isAvoidedSurface()) return true;
        node = graph.nodeAt(feet.getX(), feet.getY() + 1, feet.getZ());
        return node != null && node.isAvoidedSurface();
    }

    /**
     * Teleports a stuck villager to a nearby node, preferring one it can walk
     * on from.
     *
     * <p>The nearest node on the same floor used to win outright. A villager
     * standing on a vanilla chest in the storage room was moved from there onto
     * the stack of blocks beside it, two blocks above the floor. That top is
     * ordinary floor, but every tile around it is a chest top, which routes may
     * not enter, or a two-block drop, which the graph has no step for. It had
     * neighbours, so it passed as safe, and the villager stood on it failing
     * every goal for as long as the world ran.
     *
     * <p>So candidates are ranked exactly as before, and the first one that
     * reaches one of {@code goals} wins; failing that, the first with a step it
     * may take off itself. With {@code requireGoalReach} the search stops there.
     * Otherwise the nearest candidate still wins as a last resort, as it always
     * did: a villager wedged inside a block is better moved somewhere than left.
     */
    private boolean tryRecoverToNearbySafePosition(NavGraph graph, List<BlockPos> goals,
                                                   boolean requireGoalReach) {
        if (puppet == null || graph == null || !(puppet.level() instanceof ServerLevel sl)) return false;

        List<NavNode> candidates = new ArrayList<>();
        BlockPos current = NavGraph.feetCell(puppet);
        for (NavNode node : graph.nodesInBox(current, RECOVERY_XZ_RADIUS,
                RECOVERY_Y_RADIUS)) {
            if (pendingExcludedCells.contains(node.feetPos().asLong())) continue;
            // isRoutable, not isAvoidedSurface: the latter lets deep-water
            // landings through, and "recovering" a stuck villager by dropping
            // it into a lake is not a recovery.
            if (!node.isRoutable()) continue;
            // A node exists wherever the surface is standable, which is not the
            // same as reachable: a tile ringed by a drop on every side gets one
            // too, with no neighbours. Relocating onto an island like that is
            // worse than the stuck state it is meant to cure — the villager can
            // stand there but can never path off it again, so every later goal
            // fails too.
            if (node.degree() == 0) continue;
            // Recovering onto the tile it is already standing on burns one of
            // three attempts, then re-plans the identical failing route.
            if (node.x == current.getX() && node.z == current.getZ()
                    && Math.abs(node.feetY - current.getY()) <= 1) {
                continue;
            }
            if (Math.abs(node.x - current.getX()) > RECOVERY_XZ_RADIUS
                    || Math.abs(node.z - current.getZ()) > RECOVERY_XZ_RADIUS
                    || Math.abs(node.feetY - current.getY()) > RECOVERY_Y_RADIUS) {
                continue;
            }

            if (!NavGraph.hasStandingRoom(sl, node)) continue;
            if (!hasEntityRoomAt(puppet, node)) continue;
            candidates.add(node);
        }
        if (candidates.isEmpty()) return false;

        // F2 (10-18, WalkFeel.rescueNoWall): a trapped villager is moved only to a tile the map
        // joins to where it stands by its own steps, over beds and chests too, and the fewest such
        // steps wins. Nearest in a straight line put a farmer boxed in beside its bed outside the
        // house, through the wall corner, when the room past the bed was just as near.
        if (requireGoalReach && WalkFeel.rescueNoWall) {
            Map<NavNode, Integer> stepsAway = stepsFromFeet(graph, current);
            candidates.removeIf(node -> !stepsAway.containsKey(node));
            if (candidates.isEmpty()) {
                LOGGER.info("[VillagerModel {}] Trapped at {}: no tile nearby joins its spot without going through a wall.",
                        shortId(), fmtNpc());
                return false;
            }
            candidates.sort(Comparator
                    .comparingInt((NavNode node) -> stepsAway.get(node))
                    .thenComparingDouble(node -> node.feetPos().distSqr(current)));
        } else {
            // Stay on this floor if there is anywhere on it to go. The search
            // reaches four blocks up and down, and in a house whose storeys
            // share one layer of blocks the tile straight overhead is three
            // away -- nearer than a same-floor tile across the room. Picking
            // by distance alone teleported stuck villagers through the ceiling.
            // (A stable sort: ties keep the order the old first-found loop gave.)
            candidates.sort(Comparator
                    .comparing((NavNode node) -> Math.abs(node.feetY - current.getY()) > 1)
                    .thenComparingDouble(node -> node.feetPos().distSqr(current)));
        }

        NavNode best = null;
        String why = "reaches its goal";
        for (NavNode node : candidates) {
            if (reachesAny(graph, node, goals)) { best = node; break; }
        }
        if (best == null && !requireGoalReach) {
            why = "has a way on";
            for (NavNode node : candidates) {
                if (hasRoutableStep(node)) { best = node; break; }
            }
            if (best == null) {
                why = "nearest";
                best = candidates.get(0);
            }
        }
        if (best == null) return false;
        if (!placeReady(best, "safe spot rescue")) return false;

        BlockPos safe = best.feetPos();
        com.alex.test.village.stuckwatch.StuckWatch.caught(this, graph,
                com.alex.test.village.stuckwatch.StuckWatch.Kind.RESCUE,
                "moved without walking to the safe spot " + fmt(safe) + " (" + why + ")");
        if (activeMove != null) activeMove.closeAllOpenedDoors(puppet);
        placeOn(best);
        LOGGER.debug("[VillagerModel {}] Recovered to nearby safe node {} ({}).", shortId(), fmt(safe), why);
        return true;
    }

    /**
     * F2: every tile within the rescue box and how many of the map's own steps it lies from the
     * villager's spot. Beds, chests and other furniture tiles count (a body can step over them),
     * deep-water drops do not. The start is the villager's own tile; with none, or one with no
     * steps off it (K2: a corner the map has no tile for), the tiles in the columns beside it the
     * body could step, climb or drop onto from where it stands, with room to rise to them there.
     */
    private Map<NavNode, Integer> stepsFromFeet(NavGraph graph, BlockPos feet) {
        Map<NavNode, Integer> steps = new HashMap<>();
        ArrayDeque<NavNode> queue = new ArrayDeque<>();
        NavNode own = ownTile(graph, feet);
        if (own != null) {
            steps.put(own, 0);
            queue.add(own);
        }
        if (own == null || own.degree() == 0) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos side = feet.relative(dir);
                for (int dy : new int[] {0, 1, -1}) {
                    NavNode node = graph.nodeAt(side.getX(), side.getY() + dy, side.getZ());
                    if (node == null || node.waterDrop || steps.containsKey(node)) continue;
                    if (node.standY - puppet.getY() > NavGraph.MAX_STEP_UP + 1.0E-4) continue;
                    if (!hasEntityRoomAt(puppet, puppet.getX(), Math.max(puppet.getY(), node.standY), puppet.getZ())) continue;
                    steps.put(node, 1);
                    queue.add(node);
                }
            }
        }
        while (!queue.isEmpty()) {
            NavNode cur = queue.poll();
            int next = steps.get(cur) + 1;
            for (NavNode nb : cur.neighbours()) {
                if (steps.containsKey(nb) || nb.waterDrop || !cur.canStepTo(nb)) continue;
                if (Math.abs(nb.x - feet.getX()) > RECOVERY_XZ_RADIUS
                        || Math.abs(nb.z - feet.getZ()) > RECOVERY_XZ_RADIUS
                        || Math.abs(nb.feetY - feet.getY()) > RECOVERY_Y_RADIUS) continue;
                steps.put(nb, next);
                queue.add(nb);
            }
        }
        return steps;
    }

    private static List<BlockPos> goalsOf(BlockPos goal) {
        return goal == null ? List.of() : List.of(goal);
    }

    /** A component lookup per goal, not an A* search; see {@link NavReachability}. */
    private static boolean reachesAny(NavGraph graph, NavNode node, List<BlockPos> goals) {
        for (BlockPos goal : goals) {
            if (NavReachability.canReach(graph, node.feetPos(), goal)) return true;
        }
        return false;
    }

    /** True when a route may leave {@code node} for an ordinary tile. */
    private static boolean hasRoutableStep(NavNode node) {
        for (NavNode next : node.neighbours()) {
            if (next.isRoutable() && node.canStepTo(next) && !node.hasFailedStepTo(next)) return true;
        }
        return false;
    }

    /**
     * Frees a villager that can no longer walk anywhere from where it stands.
     *
     * <p>A route may leave a chest top but never enter one, and the graph has
     * no step for a two-block drop. A tile ringed by those -- the top of a
     * block stack between chests -- is ordinary floor with no way off it. None
     * of the other rescues notice: the villager is not on furniture, not inside
     * a block, and never stuck mid-walk, because no route to anywhere is ever
     * found. It stood on one in the storage room failing a new goal every few
     * ticks, for as long as the world ran.
     *
     * <p>So {@value #TRAPPED_NO_PATH_GOALS} different goals with no path from
     * the same tile, beside furniture, count as trapped. The villager is moved
     * only to a nearby tile that reaches one of those goals: if nowhere near
     * does, the goals are simply out of reach and it stays put. Everything it
     * wrote off as unreachable while trapped is then forgiven, so it goes
     * straight back to its task.
     */
    private void rescueIfTrapped(NavGraph graph, BlockPos failedGoal) {
        if (puppet == null || graph == null || failedGoal == null || puppet.isSleeping()) return;
        BlockPos feet = NavGraph.feetCell(puppet);
        long now = gameTime();
        if (!feet.equals(noPathFeet) || now - noPathSince > TRAPPED_WINDOW_TICKS) {
            noPathFeet = feet;
            noPathSince = now;
            noPathGoals.clear();
        }
        if (!noPathGoals.contains(failedGoal)) noPathGoals.add(failedGoal);
        if (noPathGoals.size() < TRAPPED_NO_PATH_GOALS) return;

        long trappedSince = noPathSince;
        List<BlockPos> goals = List.copyOf(noPathGoals);
        noPathFeet = null;
        noPathGoals.clear();
        boolean noTile = trappedWithoutTile(graph, feet);
        if (!noTile && !bordersFurniture(graph, feet)) {
            logStuckTile(graph, feet, goals.size(), now);
            return;
        }

        String where = fmt(feet) + (noTile ? " (no map tile there)" : "");
        LOGGER.info("[VillagerModel {}] Trapped at {}{}: {} goals in a row had no path.",
                shortId(), fmtNpc(), noTile ? " (no map tile there)" : "", goals.size());
        com.alex.test.village.stuckwatch.StuckWatch.caught(this, graph,
                com.alex.test.village.stuckwatch.StuckWatch.Kind.TRAPPED,
                "trapped: " + goals.size() + " destinations in a row had no route from " + where);
        if (!tryRecoverToNearbySafePosition(graph, goals, true)) {
            LOGGER.info("[VillagerModel {}] No nearby tile reaches those goals; staying put.", shortId());
            return;
        }
        // Blacklisted while trapped (see markMoveFailed): nothing wrong with them.
        failedTargetUntil.values().removeIf(until -> until - FAILED_TARGET_BLACKLIST_TICKS >= trappedSince);
        brain.onRescuedFromTrap(trappedSince);
    }

    /**
     * K2 (10-18, {@link WalkFeel#trapNoTile}): a villager standing where the map has no tile at all
     * counts as trapped too, not only one beside furniture. A cook woke into a bedroom corner the
     * map has none for (walls, the bed and a table round it) and failed every walk for half an hour:
     * with no tile, "beside furniture" could not be asked. Only with the F2 wall rule on
     * ({@link WalkFeel#rescueNoWall}), and not while the map has a re-read waiting there (a spot
     * just built round is no trap yet).
     */
    private boolean trappedWithoutTile(NavGraph graph, BlockPos feet) {
        if (!WalkFeel.trapNoTile || !WalkFeel.rescueNoWall || ownTile(graph, feet) != null) return false;
        if (puppet.level() instanceof ServerLevel sl) {
            VillageManager village = puppet.resolveVillageManager(sl);
            if (village != null && village.isNavCatchingUpNear(feet, 2)) return false;
        }
        return true;
    }

    /**
     * Says where a villager that no route leaves is standing, when the trap
     * rescue above does not apply (no furniture next to it).
     *
     * <p>A miner stood in its own shaft failing every walk -- the mine face, the
     * storage chests, its evening outing -- and nothing in the log said where it
     * was or why no route left that tile. This names the tile, the blocks it is
     * made of, and how many of the graph's steps off it are usable. Once per
     * tile per minute, since a trapped villager fails a new goal every few ticks.
     */
    private void logStuckTile(NavGraph graph, BlockPos feet, int goals, long now) {
        if (feet.equals(stuckLoggedFeet) && now - stuckLoggedAt < STUCK_LOG_INTERVAL_TICKS) return;
        stuckLoggedFeet = feet;
        stuckLoggedAt = now;

        Level level = puppet.level();
        String exits;
        NavNode here = graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
        if (here == null) {
            exits = "no nav tile here";
        } else {
            int usable = 0;
            int total = 0;
            for (NavNode next : here.neighbours()) {
                total++;
                if (next.isRoutable() && here.canStepTo(next) && !here.hasFailedStepTo(next)) usable++;
            }
            exits = usable + " of " + total + " steps off it usable";
        }
        LOGGER.debug("[VillagerModel {}] Stuck at {} (npc {}): {} goals in a row had no path. "
                        + "Floor {}, feet {}, head {}; {}.",
                shortId(), fmt(feet), fmtNpc(), goals,
                blockName(level, feet.below()), blockName(level, feet),
                blockName(level, feet.above()), exits);
    }

    private static String blockName(Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    /** True when {@code feet}'s node is furniture or touches some: a chest, bed or crafting table top. */
    private static boolean bordersFurniture(NavGraph graph, BlockPos feet) {
        NavNode here = graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
        if (here == null) return false;
        if (isFurniture(here)) return true;
        for (NavNode next : here.neighbours()) {
            if (isFurniture(next)) return true;
        }
        return false;
    }

    /** An avoided surface other than water, which is avoided for being water. */
    private static boolean isFurniture(NavNode node) {
        return node.isAvoidedSurface() && !(node.surfaceBlock instanceof LiquidBlock);
    }

    private boolean enforceVillageBounds(NavGraph graph) {
        BlockPos current = puppet.blockPosition();
        if (graph.isInsideBounds(current)) return false;

        LOGGER.info("[VillagerModel {}] Outside village border at {}; recovering inside.",
                shortId(), fmtNpc());
        BlockPos goal = pendingGoal;
        cancelActivePathState();
        if (tryRecoverToBestVillageNode(graph)) {
            if (goal != null) markMoveFailed(goal);
            return true;
        }

        puppet.getNavigation().stop();
        puppet.setDeltaMovement(Vec3.ZERO);
        puppet.setAnimation(VillagerAnimationState.IDLE);
        markMoveFailed(goal);
        return true;
    }

    private boolean tryRecoverToBestVillageNode(NavGraph graph) {
        if (puppet == null || graph == null || !(puppet.level() instanceof ServerLevel sl)) return false;

        NavNode best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos current = puppet.blockPosition();
        for (NavNode node : graph.allNodes()) {
            if (!node.isRoutable()) continue;
            if (!NavGraph.hasStandingRoom(sl, node)) continue;
            if (!hasEntityRoomAt(puppet, node)) continue;

            double dist = node.feetPos().distSqr(current);
            if (dist < bestDist) {
                bestDist = dist;
                best = node;
            }
        }

        if (best == null) return false;
        if (!placeReady(best, "border rescue")) return false;

        BlockPos safe = best.feetPos();
        if (activeMove != null) activeMove.closeAllOpenedDoors(puppet);
        placeOn(best);
        LOGGER.info("[VillagerModel {}] Border recovery moved villager to {}.",
                shortId(), fmt(safe));
        return true;
    }

    /**
     * Wake spot (10-18): the puppet just got out of bed onto Minecraft's stand-up tile. That tile
     * stays when the village map reaches {@code layDownFrom} from it (the tile the villager stepped
     * into bed from); otherwise the villager is put back on that tile, if it is still a free floor
     * tile. A piece-map lookup, no route search. See {@code NpcEntity#stopSleeping}.
     *
     * <p>K1 ({@link WalkFeel#wakeCell}): the stand-up tile is the map's tile in the villager's own
     * column. It used to be the nearest tile within 3 blocks, so a corner with no tile of its own
     * (walls, the bed and a table round it) was judged by the bed tile next to it, which connects:
     * a cook stood in such a corner for half an hour. No tile of its own now counts as boxed in,
     * and the lie-down tile is looked up the same way.
     * K1b ({@link WalkFeel#wakeBedside}): with no lie-down tile saved (asleep since before the wake
     * spot existed), or one that is no ordinary map tile (it lay down from such a corner: next to
     * the bed counts as at the bed), see {@link #standBesideBed}.
     */
    public void standClearOfBed(@javax.annotation.Nullable NavGraph graph,
                                @javax.annotation.Nullable BlockPos layDownFrom,
                                @javax.annotation.Nullable BlockPos bed) {
        if (puppet == null || graph == null) return;
        BlockPos feet = NavGraph.feetCell(puppet);
        NavNode back = layDownFrom == null ? null
                : WalkFeel.wakeCell ? ownTile(graph, layDownFrom) : graph.nearestOnFloor(layDownFrom, 1, 1);
        if (back == null || !back.isRoutable()) {
            if (layDownFrom == null || WalkFeel.wakeCell) standBesideBed(graph, feet, bed);
            return;
        }
        NavNode stood;
        if (WalkFeel.wakeCell) {
            stood = ownTile(graph, feet);
        } else {
            stood = graph.nearestOnFloor(feet, 3, 3);
            if (stood == null) return;
        }
        if (stood == back) return;
        if (stood != null && NavReachability.canReach(graph, stood.feetPos(), back.feetPos())) return;
        String spot = stood != null ? fmt(stood.feetPos()) : fmt(feet) + " (no map tile there)";
        if (!hasEntityRoomAt(puppet, back)) {
            LOGGER.info("[WakeSpot] {} stood up boxed in at {}; the tile it lay down from, {}, is taken",
                    shortId(), spot, fmt(back.feetPos()));
            return;
        }
        LOGGER.info("[WakeSpot] {} stood up boxed in at {}; put back on {}, where it lay down from",
                shortId(), spot, fmt(back.feetPos()));
        placeOn(back);
    }

    /**
     * K1b (10-18, {@link WalkFeel#wakeBedside}): out of bed with no usable lie-down tile. Minecraft's
     * stand-up tile stays when it is a routable tile the map connects to the Town Hall door (with no
     * door tile: one with a step off it). Otherwise the villager goes on the nearest free floor tile
     * beside either half of the bed that does connect; none: it stays where Minecraft put it.
     */
    private void standBesideBed(NavGraph graph, BlockPos feet, @javax.annotation.Nullable BlockPos bed) {
        if (!WalkFeel.wakeBedside || bed == null) return;
        NavNode root = graph.rootNode();
        NavNode stood = ownTile(graph, feet);
        if (stood != null && stood.isRoutable() && connectsToVillage(graph, stood, root)) return;

        List<NavNode> beside = bedsideTiles(graph, bed);
        beside.sort(Comparator.comparingDouble(node -> node.feetPos().distSqr(feet)));
        String spot = stood != null ? fmt(stood.feetPos()) : fmt(feet) + " (no map tile there)";
        for (NavNode tile : beside) {
            if (tile == stood || !tile.isRoutable() || !hasEntityRoomAt(puppet, tile)) continue;
            if (!connectsToVillage(graph, tile, root)) continue;
            LOGGER.info("[WakeSpot] {} stood up boxed in at {} with no usable lie-down tile; put on {} beside its bed",
                    shortId(), spot, fmt(tile.feetPos()));
            placeOn(tile);
            return;
        }
        LOGGER.info("[WakeSpot] {} stood up boxed in at {} with no usable lie-down tile; no free tile beside its bed "
                + "connects to the village, left there", shortId(), spot);
    }

    /** The map's tile in this cell's own column: the cell itself, else one up, else one down. */
    @javax.annotation.Nullable
    private static NavNode ownTile(NavGraph graph, BlockPos cell) {
        for (int dy : new int[] {0, 1, -1}) {
            NavNode node = graph.nodeAt(cell.getX(), cell.getY() + dy, cell.getZ());
            if (node != null) return node;
        }
        return null;
    }

    /** The floor tiles round both halves of a bed (the bed's own tiles left out). */
    private List<NavNode> bedsideTiles(NavGraph graph, BlockPos bed) {
        List<BlockPos> halves = new ArrayList<>(2);
        halves.add(bed);
        BlockState state = puppet.level().getBlockState(bed);
        if (state.getBlock() instanceof BedBlock) {
            halves.add(bed.relative(BedBlock.getConnectedDirection(state)));
        }
        List<NavNode> tiles = new ArrayList<>();
        for (BlockPos half : halves) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos side = half.relative(dir);
                if (halves.contains(side)) continue;
                NavNode tile = ownTile(graph, side);
                if (tile != null && !tiles.contains(tile)) tiles.add(tile);
            }
        }
        return tiles;
    }

    /** True when the map connects {@code tile} to the Town Hall door tile; with none, when a step leaves it. */
    private static boolean connectsToVillage(NavGraph graph, NavNode tile, @javax.annotation.Nullable NavNode root) {
        if (root == null) return hasRoutableStep(tile);
        return tile == root || NavReachability.canReach(graph, tile.feetPos(), root.feetPos());
    }

    /**
     * Teleports the puppet onto a node, at the height it would really stand
     * there: half a block below the node's feet cell on a bottom slab.
     */
    private void placeOn(NavNode node) {
        // Never into a chunk that is not loaded (FarAwake): a skipped wake-spot hop leaves the
        // villager where Minecraft stood it up; the rescues check first and report "not moved".
        if (!placeReady(node, "wake spot move")) return;
        puppet.getNavigation().stop();
        puppet.setDeltaMovement(Vec3.ZERO);
        puppet.setPos(node.x + 0.5D, node.standY, node.z + 0.5D);
        puppet.setOnGround(true);
        puppet.setAnimation(VillagerAnimationState.IDLE);
    }

    /** The node's chunk is loaded, so the puppet may be put there now; if not it is asked for and logged. */
    private boolean placeReady(NavNode node, String what) {
        return com.alex.test.village.farawake.FarAwake.mayPlace(puppet.level(), node.x + 0.5D, node.z + 0.5D,
                shortId() + " " + what);
    }

    private static boolean hasEntityRoomAt(NpcEntity npc, BlockPos feet) {
        return hasEntityRoomAt(npc, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
    }

    /** Same, at the node's real standing height rather than its feet cell. */
    private static boolean hasEntityRoomAt(NpcEntity npc, NavNode node) {
        return hasEntityRoomAt(npc, node.x + 0.5D, node.standY, node.z + 0.5D);
    }

    private static boolean hasEntityRoomAt(NpcEntity npc, double x, double y, double z) {
        AABB movedBox = npc.getBoundingBox().move(
                x - npc.getX(), y - npc.getY(), z - npc.getZ()).deflate(0.05D);
        return npc.level().noCollision(npc, movedBox);
    }

    private boolean isTargetBlacklisted(BlockPos goal) {
        Long until = failedTargetUntil.get(goal.asLong());
        if (until == null) return false;
        if (gameTime() >= until) {
            failedTargetUntil.remove(goal.asLong());
            return false;
        }
        return true;
    }

    private long gameTime() {
        return puppet != null && puppet.level() instanceof ServerLevel sl ? sl.getGameTime() : 0L;
    }

    private static String fmt(BlockPos pos) {
        return pos == null ? "null" : "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    private String fmtNpc() {
        return puppet == null ? "null" : String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)",
                puppet.getX(), puppet.getY(), puppet.getZ());
    }


    private String shortId() { return npcId.toString().substring(0, 8); }

    private record WaterExit(BlockPos land, BlockPos approach) {}
}
