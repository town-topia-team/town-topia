package com.alex.test.village.raid;

import com.alex.test.village.AStarPathfinder;
import com.alex.test.village.FollowWalk;
import com.alex.test.village.MoveToCommand;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.NavReachability;
import com.alex.test.village.RouteDoorHandler;
import com.alex.test.village.RouteRules;
import com.alex.test.village.VillageManager;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.raid.warlord.RaidWarlord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Round 3 of docs/plans/2026-10-18-raider-map-walking.md: one raider's walk along the village map,
 * by the villagers' route follower ({@link FollowWalk}) under the raider's own {@link RouteRules}
 * (illagers: doors and gates but no ranch gate; the dead: no doors or gates; nobody: ladders).
 * Behind {@link WalkFeel#raidMap}, on at start since 10-23.
 *
 * <p>{@link #tick} answers false whenever the caller should walk on Minecraft's path as before:
 * switch off, mounted, bigger than a villager, in water, outside the village or off its map tiles,
 * no map route (asked again after 2 s), a route walked to its end without arriving (10 s), or two
 * map stucks in a row (10 s). True: the map walk has this tick (walking, or planning, standing).
 *
 * <p>Routes are planned on a worker thread, as villagers' are, at most one a second. Toward someone
 * moving the old route is walked on while the next is planned ({@link WalkFeel#raidReplan}). A goal off
 * the map (the camp) is walked to the map tile the raider first stood on (the way it came in),
 * else the last map tile on the straight line toward it; Minecraft's path does the rest.
 */
final class RaiderMapWalk {

    /** What the walk is to. */
    enum Goal {
        /** A spot that stays put (a door, the camp). */
        PLACE,
        /** A storage container: walked to a floor tile beside it in its room. */
        CONTAINER,
        /** Someone moving (a villager to grab, a foe in round 4): planned again as it moves on. */
        MOVING
    }

    /** Villager-sized bodies only: the map's tiles are one wide and two high. */
    static final double MAX_WIDTH = 0.61;
    static final double MAX_HEIGHT = 2.0;
    /** A map tile this close (blocks across, up or down) to the feet: on the map. */
    private static final int ON_MAP_XZ = 1;
    private static final int ON_MAP_Y = 2;
    /** A goal snaps to a usable tile this close. */
    private static final int GOAL_SNAP = 3;
    /** Item 14 ({@code raidfoenear}): someone moving off the tiles (a roof, a hole) snaps this far across, up or down. */
    private static final int FOE_SNAP_XZ = 6;
    private static final int FOE_SNAP_Y = 8;
    /** At most one route planned a second. */
    private static final long PLAN_GAP_TICKS = 20L;
    /** No route: Minecraft's path for this long (2 s), then the map is asked again. */
    private static final long NO_ROUTE_TICKS = 40L;
    /** Route walked without arriving, or stuck twice running: Minecraft's path for 10 s. */
    private static final long VANILLA_TICKS = 200L;
    private static final int STUCKS_TO_VANILLA = 2;
    /** A moving goal this far from where its route ends is planned again. */
    private static final double MOVED_SQ = 3.0 * 3.0;
    /** Item 21 ({@code raidchaseend}): a route to someone moving walked; they moved on this far since it was planned. */
    private static final double CHASE_MOVED_SQ = 1.5 * 1.5;
    /** The ranch gates can change: the rules are made again this often (30 s). */
    private static final long RULES_TICKS = 600L;
    /** The off-map exit: probed every 2 blocks along the line, up to 128 blocks, given up after 6 bare. */
    private static final int EXIT_STEP = 2;
    private static final int EXIT_MOST_STEPS = 64;
    private static final int EXIT_MISSES = 3;
    /** Round 5a ({@code raidwater}): dry map tiles this close (across, up or down) are ways out of the water. */
    private static final int SHORE_XZ = 12;
    private static final int SHORE_Y = 5;
    /** Round 5a: the nearest this many ways out are asked "does the map walk on from here to the goal". */
    private static final int SHORE_JOIN_CHECKS = 24;
    /** Bug B5 ({@code raidjoinrules}): of those, this many are asked of the raider's own rules, each search at most this many tiles. */
    private static final int SHORE_RULE_CHECKS = 2;
    private static final int SHORE_RULE_TILES = 4000;
    /** Round 5a: the swim is asked of Minecraft's path once a second; a way out not a block nearer after 5 s is dropped. */
    private static final long SWIM_ASK_TICKS = 20L;
    private static final long SHORE_TRY_TICKS = 100L;
    /** Round 5a: as {@code VillagerModel.needsWaterRescue}: wading is water this deep at most, the waist this high. */
    private static final double WADE_DEPTH = 0.5;
    private static final double WAIST_HEIGHT = 0.9;

    private final PathfinderMob raider;
    private final RaidManager raid;

    @Nullable private RouteRules rules;
    @Nullable private NavGraph rulesGraph;
    private long rulesAt = Long.MIN_VALUE;

    /** What the caller asked for, and the tile the route was planned to. */
    @Nullable private BlockPos goal;
    @Nullable private BlockPos routeGoal;
    /** Item 21 ({@code raidchaseend}): where the goal was when the route being walked was planned. */
    @Nullable private BlockPos plannedFor;
    /** The first map tile this raider planned from: the way it came in. */
    @Nullable private BlockPos entryTile;

    @Nullable private CompletableFuture<AStarPathfinder.Route> pending;
    @Nullable private NavGraph pendingGraph;
    private long pendingVersion;
    private float pendingSpeed;

    @Nullable private MoveToCommand walk;
    /**
     * Gates a dropped walk could not shut because the raider itself stood in them (it found a fight
     * in the gateway): shut once it is clear, by {@link #tickHeldGates} even while it fights or after
     * it dies.
     */
    private final RouteDoorHandler heldGates = new RouteDoorHandler();
    /** Walks under way or holding gates, for {@link #tickHeldGates}. Server thread only. */
    private static final Set<RaiderMapWalk> HOLDING = new java.util.LinkedHashSet<>();
    private static long heldTickedAt = Long.MIN_VALUE;
    private long lastPlan = Long.MIN_VALUE / 2;
    private long vanillaUntil = Long.MIN_VALUE;
    private int stucksInRow;
    /**
     * Round 4 of docs/plans/2026-10-23-raider-pathfinding.md: the goal the last false answer gave up
     * on (no map tile near it, no route, or two stucks in a row); null otherwise. With
     * {@code raidnovanilla} the march goal then tries another door instead of Minecraft's path.
     */
    @Nullable private BlockPos gaveUp;
    /**
     * Bug B2 (10-23): two stucks gave up on a place goal, not yet counted as "back to Minecraft":
     * the march goal may send the raider to another door instead, so it counts the give-up only
     * when Minecraft's path really takes over ({@link #countHandover}).
     */
    private boolean backUncounted;
    /** Round 5a ({@code raidwater}): the dry tile being swum to, since when, and how far off it was then. */
    @Nullable private BlockPos shore;
    private long shoreSince;
    private double shoreStartDistance;
    private long swimAskedAt = Long.MIN_VALUE;
    /** Round 5a: ways out that gained nothing in 5 s (a bank too high), until the raider is ashore. */
    private final Set<BlockPos> badShores = new HashSet<>();

    RaiderMapWalk(PathfinderMob raider, RaidManager raid) {
        this.raider = raider;
        this.raid = raid;
    }

    /**
     * One tick toward {@code to} at {@code speed}. True: the map walk has the raider this tick;
     * false: walk it on Minecraft's path (see the class note).
     */
    boolean tick(BlockPos to, double speed, Goal kind) {
        if (!WalkFeel.raidMap || !fits() || !(raider.level() instanceof ServerLevel level)) {
            drop();
            return false;
        }
        VillageManager village = raid.village();
        NavGraph graph = village.getGraph();
        // Round 5a (raidwater): water is handled below; off, any water at all is Minecraft's, as before.
        boolean water = !WalkFeel.raidWater && raider.isInWater();
        if (graph == null || water || !village.contains(raider.blockPosition())) {
            dropWalk();
            clearShore();
            return false;
        }
        long now = level.getGameTime();
        if (kind == Goal.MOVING) {
            goal = to.immutable();
            // Someone on the move: a new route once they are well away from the old one's end.
            if (walk != null && pending == null && routeGoal != null && routeGoal.distSqr(to) > MOVED_SQ
                    && now - lastPlan >= PLAN_GAP_TICKS) {
                if (!WalkFeel.raidReplan) {
                    dropWalk();
                } else {
                    // Bug 10 (10-19): the old route is walked on while the new one is planned, swapped
                    // when it lands (before, the raider stood still a tick or two each second).
                    RouteRules r = rules(village, graph, now);
                    BlockPos start = NavGraph.routeStart(graph, raider);
                    // Off the tiles just now (mid-jump): walk on, asked again next tick.
                    if (graph.nearestRoutable(start, ON_MAP_XZ, ON_MAP_Y, r::mayEnter) != null) {
                        if (!startPlan(level, village, graph, r, start, speed, kind, now)) {
                            dropWalk();
                            return false;
                        }
                        RaidNavCount.noteMap(RaidNavCount.MapEvent.PLANNED_AHEAD, raider);
                    }
                }
            }
        } else if (goal == null || !goal.equals(to)) {
            dropWalk();
            goal = to.immutable();
            stucksInRow = 0;
            vanillaUntil = Long.MIN_VALUE;
            gaveUp = null;
            backUncounted = false;
        }
        if (WalkFeel.raidWater) {
            if (mustSwim(raider)) {
                dropWalk();
                return swimAshore(level, graph, rules(village, graph, now), to, speed, now);
            }
            clearShore();
        }
        if (now < vanillaUntil) return false;

        if (walk == null && pending == null) {
            RouteRules r = rules(village, graph, now);
            BlockPos start = NavGraph.routeStart(graph, raider);
            if (graph.nearestRoutable(start, ON_MAP_XZ, ON_MAP_Y, r::mayEnter) == null) return false;
            // Planned once a second at most: standing still meanwhile, as a villager does after a stuck.
            if (now - lastPlan < PLAN_GAP_TICKS) {
                raider.getNavigation().stop();
                return true;
            }
            if (!startPlan(level, village, graph, r, start, speed, kind, now)) return false;
            raider.getNavigation().stop();
            return true;
        }

        if (pending != null) {
            if (pending.isDone()) {
                AStarPathfinder.Route route = pending.isCompletedExceptionally() ? null : pending.getNow(null);
                pending = null;
                if (route == null || route.tiles().isEmpty()) {
                    RaidNavCount.noteMap(RaidNavCount.MapEvent.NO_ROUTE, raider);
                    dropWalk();
                    vanillaUntil = now + NO_ROUTE_TICKS;
                    gaveUp = goal;
                    return false;
                }
                // The map changed under the route (a wall went up): plan again, not walk into it.
                if (graph != pendingGraph || graph.changedSince(pendingVersion, route.cells())) {
                    dropWalk();
                    lastPlan = Long.MIN_VALUE / 2;
                    return true;
                }
                // After a stuck the walk keeps to the route's own tiles, as a villager's does.
                MoveToCommand next = FollowWalk.forRoute(graph, raider, route, pendingSpeed, stucksInRow == 0,
                        stucksInRow, Set.of());
                if (next == null) {
                    RaidNavCount.noteMap(RaidNavCount.MapEvent.NO_ROUTE, raider);
                    dropWalk();
                    vanillaUntil = now + NO_ROUTE_TICKS;
                    gaveUp = goal;
                    return false;
                }
                // A route planned while walking on (bug 10) takes over from the old walk here.
                closeWalk();
                walk = next;
                // Its own closing (end of route, a stuck) also keeps a gate the raider still stands in.
                walk.keepOccupiedDoorsWith(heldGates);
                HOLDING.add(this);
            } else if (walk == null) {
                return true;
            }
        }

        switch (walk.tick(raider)) {
            case RUNNING -> { return true; }
            case DONE -> {
                RaidNavCount.noteMap(RaidNavCount.MapEvent.DONE, raider);
                stucksInRow = 0;
                // The new route toward someone moving is on its way: wait for it.
                if (pending != null) {
                    closeWalk();
                    return true;
                }
                BlockPos plannedAt = plannedFor;
                dropWalk();
                // A goal that stays put and is still not reached: Minecraft's path does the last of it.
                if (kind != Goal.MOVING) {
                    vanillaUntil = now + VANILLA_TICKS;
                } else if (WalkFeel.raidChaseEnd && plannedAt != null) {
                    // Item 21 (10-23): they moved on, so plan again now, not after the one-a-second wait.
                    if (plannedAt.distSqr(goal) > CHASE_MOVED_SQ) {
                        lastPlan = Long.MIN_VALUE / 2;
                    } else {
                        // They did not: the same route would come back, and a raider stood beside a foe
                        // behind a wall for good. Minecraft's path (the attack goal, the grab) takes the last of it.
                        vanillaUntil = now + NO_ROUTE_TICKS;
                        return false;
                    }
                }
                return true;
            }
            case REPLAN -> {
                // Walked as far as a very long route was read: the rest is planned now (or is already).
                if (pending != null) {
                    closeWalk();
                    return true;
                }
                dropWalk();
                lastPlan = Long.MIN_VALUE / 2;
                return true;
            }
            default -> {
                RaidNavCount.noteMap(RaidNavCount.MapEvent.STUCK, raider);
                dropWalk();
                if (++stucksInRow >= STUCKS_TO_VANILLA) {
                    // Bug B2: someone moving (snatch, chase) is always Minecraft's now; a place
                    // may get another door instead (raidnovanilla), so the march goal counts it.
                    if (kind == Goal.MOVING) RaidNavCount.noteMap(RaidNavCount.MapEvent.BACK_TO_MINECRAFT, raider);
                    else backUncounted = true;
                    stucksInRow = 0;
                    vanillaUntil = now + VANILLA_TICKS;
                    gaveUp = goal;
                    return false;
                }
                return true;
            }
        }
    }

    /** Ends any walk or plan (gates it opened are shut) and forgets the goal. */
    void drop() {
        dropWalk();
        goal = null;
        stucksInRow = 0;
        vanillaUntil = Long.MIN_VALUE;
        gaveUp = null;
        backUncounted = false;
        clearShore();
    }

    /** Round 4: the last false answer gave up on {@code to} itself (see {@link #gaveUp}). */
    boolean gaveUpOn(BlockPos to) {
        return gaveUp != null && gaveUp.equals(to);
    }

    /** Bug B2: Minecraft's path takes over after a two-stuck give-up on a place: counted once, here. */
    void countHandover() {
        if (!backUncounted) return;
        backUncounted = false;
        RaidNavCount.noteMap(RaidNavCount.MapEvent.BACK_TO_MINECRAFT, raider);
    }

    /**
     * Round 5a of docs/plans/2026-10-23-raider-pathfinding.md (switch {@code raidwater}): in water too
     * deep to wade (the map has no tiles in water), swim to a dry map tile. The nearest one the map walks
     * on from to the goal ({@code to}): a raider knocked into a stream climbs out on its own side when a
     * bridge joins it, crosses when only the far bank does. None joins (or the goal is off the map): the
     * least swim plus walk on. The few blocks of swimming are Minecraft's path, which swims well; a way
     * out not a block nearer after 5 s (a bank too high) is dropped for another. Until 10-23 any water
     * meant Minecraft's path toward the goal itself. False when no dry tile is near: that, as before.
     */
    private boolean swimAshore(ServerLevel level, NavGraph graph, RouteRules r, BlockPos to, double speed, long now) {
        BlockPos at = raider.blockPosition();
        if (shore != null && now - shoreSince >= SHORE_TRY_TICKS) {
            if (shoreStartDistance - Math.sqrt(shore.distSqr(at)) < 1.0) badShores.add(shore);
            shore = null;
        }
        if (shore == null) {
            shore = pickShore(level, graph, r, at, to);
            if (shore == null) return false;
            shoreSince = now;
            shoreStartDistance = Math.sqrt(shore.distSqr(at));
            swimAskedAt = Long.MIN_VALUE;
        }
        // Once a second, never on isDone(): that is also true when the last ask found no path.
        if (now - swimAskedAt >= SWIM_ASK_TICKS) {
            swimAskedAt = now;
            boolean found = raider.getNavigation().moveTo(shore.getX() + 0.5, shore.getY(), shore.getZ() + 0.5, speed);
            RaidNavCount.note(RaidNavCount.Site.SWIM, raider, found);
        }
        return true;
    }

    /** Round 5a: the way out of the water (see {@link #swimAshore}); null when no dry tile is near. */
    @Nullable
    private BlockPos pickShore(ServerLevel level, NavGraph graph, RouteRules r, BlockPos at, BlockPos to) {
        List<BlockPos> dry = new ArrayList<>();
        for (NavNode node : graph.nodesInBox(at, SHORE_XZ, SHORE_Y)) {
            if (!node.isRoutable() || !r.mayEnter(node)) continue;
            BlockPos feet = node.feetPos();
            if (!badShores.contains(feet) && NavGraph.isDryWalkable(level, feet)) dry.add(feet);
        }
        if (dry.isEmpty()) return null;
        dry.sort(Comparator.comparingDouble(feet -> feet.distSqr(at)));
        int ruleChecks = WalkFeel.raidJoinRules ? SHORE_RULE_CHECKS : 0;
        for (int i = 0; i < Math.min(SHORE_JOIN_CHECKS, dry.size()); i++) {
            BlockPos feet = dry.get(i);
            if (!NavReachability.canReach(graph, feet, to)) continue;
            // Bug B5: the first few also by the raider's own rules; after that the quick answer.
            if (ruleChecks-- <= 0 || raiderReaches(graph, r, feet, to, SHORE_RULE_TILES)) return feet;
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos feet : dry) {
            double score = Math.sqrt(feet.distSqr(at)) + Math.sqrt(feet.distSqr(to));
            if (score < bestScore) {
                bestScore = score;
                best = feet;
            }
        }
        return best;
    }

    /**
     * Bug B5 (10-23, {@code raidjoinrules}): the map's quick "can I walk there" ({@link NavReachability})
     * goes by villager rules, so a ladder or a ranch gate counts. Asked after it: a route search under
     * the raider's own {@code rules}, from {@code to} back to {@code from} (a fenced-in goal side fails
     * fast), at most {@code maxTiles} tiles. No answer in that many: the quick answer stands.
     */
    static boolean raiderReaches(NavGraph graph, RouteRules rules, BlockPos from, BlockPos to, int maxTiles) {
        Boolean found = AStarPathfinder.reaches(graph, to, from, rules, maxTiles);
        return found == null || found;
    }

    /** Round 5a: ashore (or off the map): the swim is over. */
    private void clearShore() {
        shore = null;
        if (!badShores.isEmpty()) badShores.clear();
    }

    /**
     * Round 5a: in water it has to swim out of, judged as {@code VillagerModel.needsWaterRescue} judges a
     * villager: its own column wet at the feet or the waist, and off the ground or deeper than wading.
     * A toe in the water at a bank is walked on.
     */
    static boolean mustSwim(PathfinderMob body) {
        if (!body.isInWater()) return false;
        Level level = body.level();
        BlockPos feet = BlockPos.containing(body.getX(), body.getY() + 0.1, body.getZ());
        BlockPos waist = BlockPos.containing(body.getX(), body.getY() + WAIST_HEIGHT, body.getZ());
        FluidState atFeet = level.getFluidState(feet);
        boolean feetInWater = atFeet.is(FluidTags.WATER);
        if (!feetInWater && !level.getFluidState(waist).is(FluidTags.WATER)) return false;
        if (!body.onGround()) return true;
        if (!feetInWater) return false;
        if (atFeet.hasProperty(FlowingFluid.FALLING) && atFeet.getValue(FlowingFluid.FALLING)) return false;
        return feet.getY() + atFeet.getHeight(level, feet) - body.getY() > WADE_DEPTH;
    }

    private void dropWalk() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        closeWalk();
        routeGoal = null;
        plannedFor = null;
    }

    /** Ends the walk under way (its gates shut, one the raider stands in held); a plan on its way stays. */
    private void closeWalk() {
        if (walk == null) return;
        walk.keepOccupiedDoorsWith(heldGates);
        walk.closeAllOpenedDoors(raider);
        walk = null;
        if (heldGates.hasDoors()) HOLDING.add(this);
    }

    /**
     * Plans a route from {@code start} to the goal on a worker thread (one a second at most, kept by
     * the callers). False: no tile to plan to near the goal, Minecraft's path for 2 s.
     */
    private boolean startPlan(ServerLevel level, VillageManager village, NavGraph graph, RouteRules r,
                              BlockPos start, double speed, Goal kind, long now) {
        BlockPos target = routeGoal(level, village, graph, r, goal, kind);
        if (target == null) {
            RaidNavCount.noteMap(RaidNavCount.MapEvent.NO_ROUTE, raider);
            vanillaUntil = now + NO_ROUTE_TICKS;
            gaveUp = goal;
            return false;
        }
        if (entryTile == null) entryTile = start.immutable();
        lastPlan = now;
        gaveUp = null;
        backUncounted = false;
        routeGoal = target;
        plannedFor = goal;
        pendingGraph = graph;
        pendingVersion = graph.version();
        pendingSpeed = (float) speed;
        pending = CompletableFuture.supplyAsync(
                () -> AStarPathfinder.findRoute(graph, start, target, true, Set.of(), Set.of(), r));
        RaidNavCount.noteMap(RaidNavCount.MapEvent.PLANNED, raider);
        return true;
    }

    /**
     * Every tick, from any raid: shuts held gates once their raider is out of them, and every gate
     * of a raider killed or gone mid-walk (a raider dies in gateways; its walk would have held the
     * gate open for good). Server thread.
     */
    static void tickHeldGates(ServerLevel level) {
        if (HOLDING.isEmpty()) return;
        long now = level.getGameTime();
        if (now == heldTickedAt) return;
        heldTickedAt = now;
        HOLDING.removeIf(w -> {
            if (w.raider.isRemoved() || !w.raider.isAlive()) {
                if (w.pending != null) w.pending.cancel(false);
                w.pending = null;
                if (w.walk != null) w.walk.closeAllOpenedDoors(w.raider);
                w.walk = null;
                w.heldGates.closeAllOpenedDoors(w.raider);
                return true;
            }
            if (w.heldGates.hasDoors()) w.heldGates.closeVacatedDoors(w.raider);
            return w.walk == null && !w.heldGates.hasDoors();
        });
    }

    /** On foot, and no bigger than a villager (the Warlord, a ravager or a horse is not). */
    boolean fits() {
        return !raider.isPassenger() && !raider.isVehicle() && !(raider instanceof RaidWarlord)
                && raider.getBbWidth() <= MAX_WIDTH && raider.getBbHeight() <= MAX_HEIGHT;
    }

    /** Illagers (and witches) open doors and gates, as Minecraft lets them in a raid; the dead do not. */
    private RouteRules rules(VillageManager village, NavGraph graph, long now) {
        if (rules == null || rulesGraph != graph || now - rulesAt >= RULES_TICKS) {
            rules = RouteRules.raider(village, graph, raider instanceof Raider);
            rulesGraph = graph;
            rulesAt = now;
        }
        return rules;
    }

    /** The map tile the route goes to; null when there is none to plan to. */
    @Nullable
    private BlockPos routeGoal(ServerLevel level, VillageManager village, NavGraph graph, RouteRules r,
                               BlockPos to, Goal kind) {
        if (kind == Goal.CONTAINER) {
            BlockPos beside = besideContainer(village, graph, r, to);
            if (beside != null) return beside;
        }
        NavNode near = graph.nearestRoutable(to, GOAL_SNAP, GOAL_SNAP, r::mayEnter);
        if (near != null) return near.feetPos();
        boolean foeNear = WalkFeel.raidFoeNear && kind == Goal.MOVING;
        if (foeNear) {
            // Item 14 (10-23): on a roof or down a hole: the tile nearest them (the foot of the house);
            // once the route is walked, raidchaseend hands the last of it to Minecraft's attack.
            near = graph.nearestRoutable(to, FOE_SNAP_XZ, FOE_SNAP_Y, r::mayEnter);
            if (near != null) return near.feetPos();
        }
        if (village.contains(to)) return null;
        // Item 14: someone just outside the border is walked to the map's edge toward them, not back
        // out the way the raider came in.
        if (foeNear) return exitToward(graph, r, raider.blockPosition(), to);
        // Off the map (the camp): out the way it came in, else as far along the line as the map goes.
        // Only when it lies nearer the goal: a raider whose first map walk began in the middle came in
        // some other way.
        if (entryTile != null && entryTile.distSqr(to) < raider.blockPosition().distSqr(to)) {
            NavNode entry = graph.nodeAt(entryTile.getX(), entryTile.getY(), entryTile.getZ());
            if (entry != null && entry.isRoutable() && r.mayEnter(entry)) return entryTile;
        }
        return exitToward(graph, r, raider.blockPosition(), to);
    }

    /** The floor tile in the container's own room beside it, as a villager would stand to use it. */
    @Nullable
    private BlockPos besideContainer(VillageManager village, NavGraph graph, RouteRules r, BlockPos container) {
        BlockPos from = raider.blockPosition();
        for (Building building : village.getBuildings()) {
            if (!building.getFunctionalBlocks().contains(container)) continue;
            BlockPos tile = building instanceof StorageBuilding storage
                    ? storage.getWalkTargetForContainer(container, graph, from)
                    : building.getWalkTargetBeside(container, graph, from);
            if (tile == null) continue;
            NavNode node = graph.nodeAt(tile.getX(), tile.getY(), tile.getZ());
            if (node != null && r.mayEnter(node)) return tile;
        }
        return null;
    }

    /** The last map tile on the straight line from {@code from} toward {@code to}, before 6 blocks with none. */
    @Nullable
    static BlockPos exitToward(NavGraph graph, RouteRules r, BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0) return null;
        BlockPos last = null;
        int misses = 0;
        int steps = Math.min(EXIT_MOST_STEPS, (int) (length / EXIT_STEP));
        for (int i = 1; i <= steps; i++) {
            double t = i * EXIT_STEP / length;
            BlockPos probe = BlockPos.containing(from.getX() + 0.5 + dx * t, from.getY() + dy * t, from.getZ() + 0.5 + dz * t);
            NavNode node = graph.nearestRoutable(probe, 2, 4, r::mayEnter);
            if (node != null) {
                last = node.feetPos();
                misses = 0;
            } else if (++misses >= EXIT_MISSES) {
                break;
            }
        }
        return last;
    }
}
