package com.alex.test.village.raid;

import com.alex.test.village.AStarPathfinder;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.RouteRules;
import com.alex.test.village.VillageManager;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.WideTiles;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Round 5 of docs/plans/2026-10-18-raider-map-walking.md (switch {@code raidbig}, needs
 * {@code raidmap}): a big body's walk (the Warlord, a ravager) by a village map route. The route is
 * planned on a worker thread over the tiles {@link WideTiles} found room for (2 by 2 squares, 3
 * blocks high, no doors); the body then walks it in stretches of about 10 blocks on Minecraft's
 * own path, which knows how a wide body steps. The villagers' route follower is not used: its line
 * maths are for a 0.6-wide body.
 *
 * <p>{@link #tick} answers false whenever the caller should walk on Minecraft's path as before:
 * switch off, mounted or carrying a rider, not big, in water, outside the village or where no big
 * tile is near, the tile map still being made (a few ticks), no route (5 s), the route walked
 * without arriving or stuck twice running (10 s). Planning never stops the body: the stretch
 * under way goes on meanwhile.
 */
public final class WideMapWalk {

    /** What the walk is to. */
    public enum Goal {
        /** A spot that stays put (the march target, the camp, a detour spot). */
        PLACE,
        /** Someone moving (the Warlord's foe): planned again as it moves on. */
        MOVING
    }

    private static final double MAX_WIDTH = 2.0;
    private static final double MAX_HEIGHT = WideTiles.BODY_HEIGHT;
    /** A big tile this close to the body's middle: on the map (a big body's middle may sit on a tile edge). */
    private static final int ON_MAP_XZ = 2;
    private static final int ON_MAP_Y = 2;
    private static final int GOAL_SNAP = 3;
    private static final long PLAN_GAP_TICKS = 20L;
    /** No route: Minecraft's path for 5 s. Route walked without arriving, or stuck twice: 10 s. */
    private static final long NO_ROUTE_TICKS = 100L;
    private static final long VANILLA_TICKS = 200L;
    private static final int STUCKS_TO_VANILLA = 2;
    private static final double MOVED_SQ = 3.0 * 3.0;
    /** Each stretch: Minecraft's path to the route tile up to this far ahead (straight line). */
    private static final double LEG_BLOCKS = 10.0;
    /** A new stretch once this near the last one's end, or after 3 s; 1 s after one with no path. */
    private static final double LEG_NEAR_SQ = 4.0 * 4.0;
    private static final long LEG_TICKS = 60L;
    private static final long LEG_RETRY_TICKS = 20L;
    private static final int LEG_MISSES = 3;
    /** The body is looked for this many tiles along the route from where it was. */
    private static final int AHEAD_TILES = 24;
    private static final double SAME_FLOOR = 2.5;
    /** No headway along the route in 5 s: stuck. */
    private static final long STUCK_TICKS = 100L;
    private static final double ARRIVED_SQ = 2.5 * 2.5;
    /** "Is there a way" asked at most once a second; an answer kept 3 s. */
    private static final long REACH_TICKS = 20L;
    private static final long REACH_KEPT_TICKS = 60L;
    /** The way test counts only a route ending this near its foe (a tile 3 blocks off may be outside its house wall). */
    private static final int REACH_SNAP_XZ = 1;
    private static final int REACH_SNAP_Y = 2;

    /** Shared tile maps: this far round a body, made again after 30 s or 24 blocks away from its middle. */
    private static final int TILES_XZ = 64;
    private static final int TILES_Y = 16;
    private static final long TILES_TICKS = 600L;
    private static final double TILES_MOVED_SQ = 24.0 * 24.0;
    private static final int TILES_BUDGET = 3000;
    private static final int TILES_KEPT = 4;
    /** Item 17 ({@code raidbigfresh}): made again once 5 s old when the map changed this near the body since. */
    private static final long TILES_FRESH_TICKS = 100L;
    private static final int FRESH_XZ = 16;
    private static final int FRESH_Y = 8;

    /** A tile map and the game time it was started; server thread only. */
    private static final class TileMap {
        final WideTiles tiles;
        final long startedAt;
        long advancedAt = Long.MIN_VALUE;

        TileMap(WideTiles tiles, long startedAt) {
            this.tiles = tiles;
            this.startedAt = startedAt;
        }
    }

    /** Newest first; shared by every big body (a raid's ravagers and its Warlord walk the same streets). */
    private static final List<TileMap> MAPS = new ArrayList<>();

    private final PathfinderMob body;
    private final Supplier<VillageManager> village;

    @Nullable private BlockPos goal;
    @Nullable private BlockPos routeGoal;
    /** The first big tile this body planned from: the way it came in. */
    @Nullable private BlockPos entryTile;

    @Nullable private CompletableFuture<AStarPathfinder.Route> pending;
    @Nullable private NavGraph pendingGraph;
    private long pendingVersion;
    private long lastPlan = Long.MIN_VALUE / 2;
    private long vanillaUntil = Long.MIN_VALUE;
    private int stucksInRow;

    /** The route being walked, how far along it the body is, and since when it got no further. */
    @Nullable private List<NavNode> route;
    private int along;
    private long headwayAt;
    private double routeSpeed;
    /** The stretch under way: its end tile, when asked, whether Minecraft found a path. */
    @Nullable private NavNode legEnd;
    private long legAt;
    private boolean legFound;
    private int legMisses;

    @Nullable private CompletableFuture<AStarPathfinder.Route> reachPending;
    @Nullable private BlockPos reachFor;
    private long reachAt = Long.MIN_VALUE / 2;
    @Nullable private BlockPos answerFor;
    private boolean answer;
    private long answeredAt = Long.MIN_VALUE / 2;

    public WideMapWalk(PathfinderMob body, Supplier<VillageManager> village) {
        this.body = body;
        this.village = village;
    }

    /**
     * One tick toward {@code to} at {@code speed}. True: the map walk has the body this tick;
     * false: walk it on Minecraft's path (see the class note).
     */
    public boolean tick(BlockPos to, double speed, Goal kind) {
        if (!WalkFeel.raidMap || !WalkFeel.raidBig || !fits() || !(body.level() instanceof ServerLevel level)) {
            drop();
            return false;
        }
        VillageManager v = village.get();
        NavGraph graph = v == null ? null : v.getGraph();
        if (graph == null || body.isInWater() || !v.contains(body.blockPosition())) {
            dropWalk();
            return false;
        }
        long now = level.getGameTime();
        if (kind == Goal.MOVING) {
            if ((route != null || pending != null) && routeGoal != null && routeGoal.distSqr(to) > MOVED_SQ
                    && now - lastPlan >= PLAN_GAP_TICKS) {
                dropWalk();
            }
            goal = to.immutable();
        } else if (goal == null || !goal.equals(to)) {
            dropWalk();
            goal = to.immutable();
            stucksInRow = 0;
            vanillaUntil = Long.MIN_VALUE;
        }
        if (now < vanillaUntil) return false;

        if (route == null && pending == null) {
            // Once a second at most; the stretch under way walks on meanwhile.
            if (now - lastPlan < PLAN_GAP_TICKS) return true;
            RouteRules rules = tileRules(level, graph, body.blockPosition(), now);
            if (rules == null) return false;
            NavNode start = graph.nearestRoutable(NavGraph.routeStart(graph, body), ON_MAP_XZ, ON_MAP_Y, rules::mayEnter);
            if (start == null) return false;
            BlockPos target = routeGoal(graph, rules, v, goal);
            if (target == null) {
                RaidNavCount.noteMap(RaidNavCount.MapEvent.NO_ROUTE, body);
                vanillaUntil = now + NO_ROUTE_TICKS;
                return false;
            }
            BlockPos from = start.feetPos();
            if (entryTile == null) entryTile = from;
            lastPlan = now;
            routeGoal = target;
            routeSpeed = speed;
            pendingGraph = graph;
            pendingVersion = graph.version();
            pending = CompletableFuture.supplyAsync(
                    () -> AStarPathfinder.findRoute(graph, from, target, true, Set.of(), Set.of(), rules));
            RaidNavCount.noteMap(RaidNavCount.MapEvent.PLANNED, body);
            return true;
        }

        if (pending != null) {
            if (!pending.isDone()) return true;
            AStarPathfinder.Route found = pending.isCompletedExceptionally() ? null : pending.getNow(null);
            pending = null;
            if (found == null || found.tiles().isEmpty()) {
                RaidNavCount.noteMap(RaidNavCount.MapEvent.NO_ROUTE, body);
                vanillaUntil = now + NO_ROUTE_TICKS;
                return false;
            }
            // The map changed under the route (a wall went up): plan again, not walk into it.
            if (graph != pendingGraph || graph.changedSince(pendingVersion, found.cells())) {
                lastPlan = Long.MIN_VALUE / 2;
                return true;
            }
            route = found.tiles();
            along = 0;
            headwayAt = now;
            legEnd = null;
            legMisses = 0;
        }
        routeSpeed = speed;
        return walk(now, kind);
    }

    /** Ends any walk or plan and forgets the goal. Minecraft's path under way is left to the caller. */
    public void drop() {
        dropWalk();
        goal = null;
        stucksInRow = 0;
        vanillaUntil = Long.MIN_VALUE;
    }

    private void dropWalk() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        route = null;
        routeGoal = null;
        legEnd = null;
    }

    /**
     * Whether the village map has a way for this body to {@code to}, for the Warlord's once-a-second
     * "is there a way" test; null when the map cannot say (switch off, either side off the map, not
     * known yet), and the caller asks Minecraft as before. Its own route search, at most once a
     * second, unless the walk under way already goes there.
     */
    @Nullable
    public Boolean mapReach(BlockPos to) {
        if (!WalkFeel.raidMap || !WalkFeel.raidBig || !fits() || !(body.level() instanceof ServerLevel level)) return null;
        VillageManager v = village.get();
        NavGraph graph = v == null ? null : v.getGraph();
        if (graph == null || body.isInWater() || !v.contains(body.blockPosition()) || !v.contains(to)) return null;
        long now = level.getGameTime();
        // Walking a route that ends at it now: there is a way.
        if (route != null && routeGoal != null && nextTo(routeGoal, to)) return true;
        if (reachPending != null && reachPending.isDone()) {
            AStarPathfinder.Route found = reachPending.isCompletedExceptionally() ? null : reachPending.getNow(null);
            reachPending = null;
            answerFor = reachFor;
            answer = found != null && !found.tiles().isEmpty();
            answeredAt = now;
        }
        if (reachPending == null && now - reachAt >= REACH_TICKS) {
            RouteRules rules = tileRules(level, graph, body.blockPosition(), now);
            NavNode start = rules == null ? null
                    : graph.nearestRoutable(NavGraph.routeStart(graph, body), ON_MAP_XZ, ON_MAP_Y, rules::mayEnter);
            if (start != null) {
                reachAt = now;
                reachFor = to.immutable();
                NavNode end = graph.nearestRoutable(to, REACH_SNAP_XZ, REACH_SNAP_Y, rules::mayEnter);
                if (end == null) {
                    // Nowhere near it a big body fits: no way on the map.
                    answerFor = reachFor;
                    answer = false;
                    answeredAt = now;
                } else {
                    BlockPos from = start.feetPos();
                    BlockPos target = end.feetPos();
                    reachPending = CompletableFuture.supplyAsync(
                            () -> AStarPathfinder.findRoute(graph, from, target, false, Set.of(), Set.of(), rules));
                    RaidNavCount.noteMap(RaidNavCount.MapEvent.WAY_CHECK, body);
                }
            }
        }
        if (answerFor != null && answerFor.distSqr(to) <= MOVED_SQ && now - answeredAt <= REACH_KEPT_TICKS) return answer;
        return null;
    }

    /** Big (a villager-sized raider has its own map walk), but no bigger than the big tiles, and on foot. */
    public boolean fits() {
        double width = body.getBbWidth();
        double height = body.getBbHeight();
        return !body.isPassenger() && !body.isVehicle()
                && (width > RaiderMapWalk.MAX_WIDTH || height > RaiderMapWalk.MAX_HEIGHT)
                && width <= MAX_WIDTH && height <= MAX_HEIGHT + 1.0E-6;
    }

    /** Follows the route: how far along, arrived, stuck, and the next stretch on Minecraft's path. */
    private boolean walk(long now, Goal kind) {
        List<NavNode> tiles = route;
        int best = along;
        double bestSq = flatSq(tiles.get(along));
        int last = Math.min(tiles.size() - 1, along + AHEAD_TILES);
        for (int i = along + 1; i <= last; i++) {
            NavNode tile = tiles.get(i);
            if (Math.abs(tile.standY - body.getY()) > SAME_FLOOR) continue;
            double d = flatSq(tile);
            if (d < bestSq) {
                bestSq = d;
                best = i;
            }
        }
        if (best > along) {
            along = best;
            headwayAt = now;
        }
        NavNode end = tiles.get(tiles.size() - 1);
        if (flatSq(end) <= ARRIVED_SQ && Math.abs(end.standY - body.getY()) <= SAME_FLOOR) {
            RaidNavCount.noteMap(RaidNavCount.MapEvent.DONE, body);
            dropWalk();
            stucksInRow = 0;
            // A goal that stays put and is still not reached: Minecraft's path does the last of it.
            if (kind != Goal.MOVING) vanillaUntil = now + VANILLA_TICKS;
            return true;
        }
        if (now - headwayAt > STUCK_TICKS) return stuck(now);

        boolean due = legEnd == null
                || (legFound ? body.getNavigation().isDone() || flatSq(legEnd) <= LEG_NEAR_SQ || now - legAt >= LEG_TICKS
                             : now - legAt >= LEG_RETRY_TICKS);
        if (!due) return true;
        NavNode aim = legAim(tiles);
        // Near the route's end the aim stays the same: no new path while the last one is walked.
        if (aim == legEnd && legFound && !body.getNavigation().isDone() && now - legAt < LEG_TICKS) return true;
        legAt = now;
        legEnd = aim;
        legFound = body.getNavigation().moveTo(aim.x + 0.5, aim.standY, aim.z + 0.5, routeSpeed);
        RaidNavCount.note(RaidNavCount.Site.BIG_LEG, body, legFound);
        if (legFound) {
            legMisses = 0;
        } else if (++legMisses >= LEG_MISSES) {
            return stuck(now);
        }
        return true;
    }

    /** Stuck on the route: plan again, or after two in a row Minecraft's path for 10 s. */
    private boolean stuck(long now) {
        RaidNavCount.noteMap(RaidNavCount.MapEvent.STUCK, body);
        dropWalk();
        if (++stucksInRow >= STUCKS_TO_VANILLA) {
            RaidNavCount.noteMap(RaidNavCount.MapEvent.BACK_TO_MINECRAFT, body);
            stucksInRow = 0;
            vanillaUntil = now + VANILLA_TICKS;
            return false;
        }
        return true;
    }

    /** The furthest route tile from where the body is, before the first one over {@link #LEG_BLOCKS} away. */
    private NavNode legAim(List<NavNode> tiles) {
        NavNode aim = tiles.get(Math.min(tiles.size() - 1, along + 1));
        for (int i = along + 1; i < tiles.size(); i++) {
            NavNode tile = tiles.get(i);
            if (flatSq(tile) > LEG_BLOCKS * LEG_BLOCKS) break;
            aim = tile;
        }
        return aim;
    }

    /** Within a block across (and two up or down) of each other. */
    private static boolean nextTo(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= REACH_SNAP_XZ && Math.abs(a.getZ() - b.getZ()) <= REACH_SNAP_XZ
                && Math.abs(a.getY() - b.getY()) <= REACH_SNAP_Y;
    }

    private double flatSq(NavNode tile) {
        double dx = tile.x + 0.5 - body.getX();
        double dz = tile.z + 0.5 - body.getZ();
        return dx * dx + dz * dz;
    }

    /** The big tile the route goes to; null when there is none to plan to. */
    @Nullable
    private BlockPos routeGoal(NavGraph graph, RouteRules rules, VillageManager v, BlockPos to) {
        NavNode near = graph.nearestRoutable(to, GOAL_SNAP, GOAL_SNAP, rules::mayEnter);
        if (near != null) return near.feetPos();
        if (v.contains(to)) return null;
        // Off the map (the camp): out the way it came in, else as far along the line as the big tiles go.
        if (entryTile != null && entryTile.distSqr(to) < body.blockPosition().distSqr(to)) {
            NavNode entry = graph.nodeAt(entryTile.getX(), entryTile.getY(), entryTile.getZ());
            if (entry != null && rules.mayEnter(entry)) return entryTile;
        }
        return RaiderMapWalk.exitToward(graph, rules, body.blockPosition(), to);
    }

    /**
     * The big-body rules round {@code at}: the newest finished tile map near enough, while a new one
     * is made a slice a tick once it is 30 s old (or none is near); null until one is finished.
     */
    @Nullable
    private static RouteRules tileRules(ServerLevel level, NavGraph graph, BlockPos at, long now) {
        MAPS.removeIf(map -> now < map.startedAt || now - map.startedAt > 4 * TILES_TICKS);
        TileMap done = null;
        TileMap making = null;
        for (TileMap map : MAPS) {
            if (map.tiles.graph() != graph || map.tiles.centre().distSqr(at) > TILES_MOVED_SQ) continue;
            if (map.tiles.rules() == null) {
                if (making == null) making = map;
            } else if (done == null) {
                done = map;
            }
        }
        boolean stale = done == null || now - done.startedAt >= TILES_TICKS
                // Item 17 (10-23): a wall built or broken near the body is known after 5 s, not 30.
                || (WalkFeel.raidBigFresh && now - done.startedAt >= TILES_FRESH_TICKS
                    && graph.changedSince(done.tiles.version(), at, FRESH_XZ, FRESH_Y));
        if (stale && making == null) {
            making = new TileMap(WideTiles.start(graph, at, TILES_XZ, TILES_Y), now);
            MAPS.add(0, making);
            while (MAPS.size() > TILES_KEPT) MAPS.remove(MAPS.size() - 1);
        }
        if (making != null && making.advancedAt != now) {
            making.advancedAt = now;
            if (making.tiles.advance(level, TILES_BUDGET)) {
                RaidNavCount.noteTileMap(making.tiles, level);
                done = making;
            }
        }
        return done == null ? null : done.tiles.rules();
    }
}
