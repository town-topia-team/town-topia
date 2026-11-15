package com.alex.test.village;

import com.alex.test.village.nav.StepPrice;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * A* pathfinder that operates directly on a {@link NavGraph}.
 *
 * <p>Thread-safe: all state is local to each {@link #findPath} call.
 * The NavGraph is immutable after build, so no locking is required.
 *
 * <p>Heuristic: XZ Manhattan distance (admissible for 4-directional movement).
 * A jump up costs 2, anything lower (flat, down, or a half-block slab step
 * vanilla walks up without jumping) costs 1, to discourage unnecessary climbing.
 * Ordinary floor routes are tried before ladder routes.
 *
 * <p>Path smoothing: Bresenham line-of-sight string-pulling eliminates
 * staircase zigzag on diagonal stretches, leaving only direction-change
 * "anchor" waypoints.
 */
public final class AStarPathfinder {

    private static final int SNAP_XZ_RADIUS = 3;
    private static final int SNAP_Y_RADIUS = 3;
    /** Prefer an ordinary stair or floor route over a ladder shortcut. */
    private static final int LADDER_ENTRY_COST = 24;
    /**
     * Walking an open trapdoor's cell leaves about a centimetre between a
     * villager and the plate, so it snags on the plate's end going in or out.
     * Use those cells only when the floor beside them is no real way round.
     */
    private static final int OPEN_TRAPDOOR_COST = 4;
    /**
     * The tile beside a cauldron is where a farmer stands to wash or refill.
     * Through-traffic goes round it when there is floor to go round by, so
     * nobody walks through a farmer at the cauldron; when it is the only way,
     * the tile is still used. The walker's own goal tile never pays it.
     */
    private static final int CAULDRON_SIDE_COST = 3;
    /**
     * Longest straight leg string-pulling may make, in blocks across the ground.
     *
     * <p>Vanilla navigation walks each leg and rounds the small things the graph
     * line skirts, but it cannot plan past the villager's 32-block follow range.
     * A lumberjack's walk out to the trees pulled into legs of 40 to 170 blocks;
     * vanilla gave up on those, and {@link MoveToCommand} steered straight at the
     * far end from wherever the villager stood, into the column beside the
     * storage door, until the stuck timer fired. Half the range leaves vanilla
     * room to detour.
     */
    private static final int MAX_SMOOTHED_LEG = 16;

    private AStarPathfinder() {}

    /**
     * Find a path from {@code start} to {@code goal}.
     *
     * <p>Both positions are snapped to the nearest graph node within 3 blocks
     * (XZ), so the caller does not need to pass exact node coordinates.
     *
     * @return Sparse waypoint list (feetPos BlockPos values) or {@code null}
     *         if no path exists or either position cannot be snapped.
     */
    public static List<BlockPos> findPath(NavGraph graph, BlockPos start, BlockPos goal) {
        return findPath(graph, start, goal, true, Set.of());
    }

    /** Plan around the current positions of nearby villagers when another route exists. */
    public static List<BlockPos> findPath(
            NavGraph graph, BlockPos start, BlockPos goal, Set<Long> occupied) {
        return findPath(graph, start, goal, true, occupied);
    }

    /**
     * Find a path without string-pulling. Combat uses this in cramped houses and
     * stairwells so vanilla navigation gets every graph step instead of a long
     * shortcut that can point into a wall or balcony edge.
     */
    public static List<BlockPos> findDetailedPath(NavGraph graph, BlockPos start, BlockPos goal) {
        return findPath(graph, start, goal, false, Set.of());
    }

    public static List<BlockPos> findDetailedPath(
            NavGraph graph, BlockPos start, BlockPos goal, Set<Long> occupied) {
        return findPath(graph, start, goal, false, occupied);
    }

    private static List<BlockPos> findPath(
            NavGraph graph, BlockPos start, BlockPos goal, boolean smoothPath, Set<Long> occupied) {
        return findPath(graph, start, goal, smoothPath, occupied, Set.of());
    }

    /** Detailed routes that never enter excluded cells, even when occupancy is ignored. */
    public static List<BlockPos> findDetailedPathAvoiding(
            NavGraph graph, BlockPos start, BlockPos goal, Set<Long> occupied, Set<Long> excluded) {
        return findPath(graph, start, goal, false, occupied, excluded);
    }

    private static List<BlockPos> findPath(
            NavGraph graph, BlockPos start, BlockPos goal, boolean smoothPath,
            Set<Long> occupied, Set<Long> excluded) {
        Route route = findRoute(graph, start, goal, smoothPath, occupied, excluded);
        return route == null ? null : route.waypoints();
    }

    /**
     * A planned route: its waypoints, and the cells its full trace crossed.
     * A walker keeps the cells so it can re-plan only when one of them
     * changes, instead of on any edit anywhere in the village.
     *
     * <p>{@code tiles} is the full tile path the waypoints were pulled from,
     * every tile in order (route follower round 2 walks it); may be empty.
     */
    public record Route(List<BlockPos> waypoints, long[] cells, List<NavNode> tiles) {
        public Route(List<BlockPos> waypoints, long[] cells) {
            this(waypoints, cells, List.of());
        }
    }

    /**
     * As {@link #findPath(NavGraph, BlockPos, BlockPos)} with every option,
     * also returning the cells the route crosses. The cells come from the
     * full trace before string-pulling, so a leg that skips a column still
     * names the cells it passes over.
     *
     * @return the route, or {@code null} if there is none
     */
    public static Route findRoute(NavGraph graph, BlockPos start, BlockPos goal, boolean smoothPath,
                                  Set<Long> occupied, Set<Long> excluded) {
        return findRoute(graph, start, goal, smoothPath, occupied, excluded, RouteRules.VILLAGER);
    }

    /**
     * As {@link #findRoute(NavGraph, BlockPos, BlockPos, boolean, Set, Set)}, keeping to the tiles
     * {@code rules} lets the body use (raider plan round 2): a raider's route goes only through
     * doors and gates it can open, and never up a ladder.
     */
    public static Route findRoute(NavGraph graph, BlockPos start, BlockPos goal, boolean smoothPath,
                                  Set<Long> occupied, Set<Long> excluded, RouteRules rules) {
        NavNode startNode = nearestPreferredStart(graph, start);
        if (!excluded.isEmpty()) {
            NavNode exactStart = graph.nodeAt(start.getX(), start.getY(), start.getZ());
            if (exactStart != null) startNode = exactStart;
        }
        // Restricted routes end at the requested cell; snapping could put the
        // walker back inside the room the caller explicitly excluded.
        NavNode goalNode = excluded.isEmpty() ? nearestPreferredGoal(graph, goal, occupied)
                : graph.nodeAt(goal.getX(), goal.getY(), goal.getZ());
        if (startNode == null || goalNode == null) return null;
        if (!excluded.isEmpty() && (!goalNode.isRoutable() || excluded.contains(goalNode.feetPos().asLong())))
            return null;
        if (startNode == goalNode) {
            return new Route(List.of(goalNode.feetPos()),
                    new long[] { NavGraph.cellKey(goalNode.x, goalNode.feetY, goalNode.z) },
                    List.of(goalNode));
        }

        // A cost alone still chooses a climb when the ordinary route is long.
        // Ground routes may pass through a supported ladder cell, but only the
        // fallback search may move vertically along its rungs.
        long started = System.nanoTime();
        int[] looked = new int[1];
        // Raider plan item 20 (10-23): raiders' and big bodies' searches are counted on their own line.
        boolean raider = !rules.allowsEverything();
        SearchResult found = search(graph, startNode, goalNode, smoothPath, occupied, true, false, excluded, rules, looked, Integer.MAX_VALUE);
        if (found != null) return SearchStats.done(found.route(), looked[0], started, false, raider);
        // The second search differs only on ladders: no use to a body that never climbs.
        if (rules.climbs()) found = search(graph, startNode, goalNode, smoothPath, occupied, true, true, excluded, rules, looked, Integer.MAX_VALUE);
        if (found != null) return SearchStats.done(found.route(), looked[0], started, true, raider);
        if (occupied.isEmpty()) return SearchStats.done(null, looked[0], started, true, raider);

        // Occupancy is transient. If all clear routes are blocked, let the
        // normal stuck/repath logic retry rather than declaring no route.
        NavNode originalGoal = excluded.isEmpty() ? graph.nearestRoutable(goal, SNAP_XZ_RADIUS, SNAP_Y_RADIUS) : goalNode;
        found = search(graph, startNode, originalGoal, smoothPath, occupied, false, false, excluded, rules, looked, Integer.MAX_VALUE);
        if (found == null && rules.climbs()) found = search(graph, startNode, originalGoal, smoothPath, occupied, false, true, excluded, rules, looked, Integer.MAX_VALUE);
        return SearchStats.done(found == null ? null : found.route(), looked[0], started, true, raider);
    }

    /**
     * Raider pathfinding bug B5 (10-23): can a body under {@code rules} walk from {@code start} to
     * {@code goal}? True or false; null when {@code maxTiles} tiles were looked at with no answer (a
     * long way round, or a wide area with no way out), and the caller keeps its quick answer. Snaps as
     * {@link #findRoute} does; no smoothing, no ladder pass for a body that never climbs. Counted on the
     * raiders' {@code [RouteSearch]} line. {@link NavReachability} answers by villager rules only.
     */
    @javax.annotation.Nullable
    public static Boolean reaches(NavGraph graph, BlockPos start, BlockPos goal, RouteRules rules, int maxTiles) {
        NavNode startNode = nearestPreferredStart(graph, start);
        NavNode goalNode = nearestPreferredGoal(graph, goal);
        if (startNode == null || goalNode == null) return false;
        if (startNode == goalNode) return true;
        long started = System.nanoTime();
        int[] looked = new int[2];
        SearchResult found = search(graph, startNode, goalNode, false, Set.of(), false, false, Set.of(), rules, looked, maxTiles);
        if (found == null && looked[1] == 0 && rules.climbs()) {
            found = search(graph, startNode, goalNode, false, Set.of(), false, true, Set.of(), rules, looked, maxTiles);
        }
        SearchStats.done(found == null ? null : found.route(), looked[0], started, false, !rules.allowsEverything());
        if (found != null) return true;
        return looked[1] != 0 ? null : Boolean.FALSE;
    }

    /**
     * Climb cost 2A (10-18): the {@code [RouteSearch]} minute line, how much work route planning
     * takes: routes planned, how many found one, how many needed more than the first search, tiles
     * looked at per route and time per route (mean, most). Village walks plan on worker threads
     * and guards on the server thread, hence the atomics. Flushed with the {@code [Follow]} line.
     * Since 10-23 (raider plan item 20) raiders' and big bodies' searches get their own line,
     * {@code [RouteSearch] raiders}, so a raid no longer muddles the villagers' numbers.
     */
    static final class SearchStats {
        private static final SearchStats VILLAGERS = new SearchStats();
        private static final SearchStats RAIDERS = new SearchStats();

        private final java.util.concurrent.atomic.LongAdder routes = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder foundCount = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder morePasses = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder tiles = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder nanos = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.AtomicLong mostTiles = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong mostNanos = new java.util.concurrent.atomic.AtomicLong();

        private SearchStats() {}

        static Route done(Route route, int tiles, long startedNanos, boolean morePasses, boolean raider) {
            long nanos = System.nanoTime() - startedNanos;
            SearchStats s = raider ? RAIDERS : VILLAGERS;
            s.routes.increment();
            if (route != null) s.foundCount.increment();
            if (morePasses) s.morePasses.increment();
            s.tiles.add(tiles);
            s.nanos.add(nanos);
            s.mostTiles.accumulateAndGet(tiles, Math::max);
            s.mostNanos.accumulateAndGet(nanos, Math::max);
            return route;
        }

        /** Logs the lines and starts a new count. Server thread, once a minute. */
        static void flush() {
            VILLAGERS.flushLine("[RouteSearch]");
            RAIDERS.flushLine("[RouteSearch] raiders");
        }

        private void flushLine(String tag) {
            long routes = this.routes.sumThenReset();
            long found = foundCount.sumThenReset(), more = morePasses.sumThenReset();
            long tiles = this.tiles.sumThenReset(), nanos = this.nanos.sumThenReset();
            long mostTiles = this.mostTiles.getAndSet(0), mostNanos = this.mostNanos.getAndSet(0);
            if (routes == 0) return;
            VillageManager.LOGGER.info("{} last 60 s: {} routes planned ({} found, {} needed a second search),"
                            + " tiles looked at mean {} most {}, time mean {} ms most {} ms, tiebreak {}, climbcost {}",
                    tag, routes, found, more, tiles / routes, mostTiles,
                    String.format(Locale.ROOT, "%.2f", nanos / 1.0E6 / routes),
                    String.format(Locale.ROOT, "%.2f", mostNanos / 1.0E6), WalkFeel.tieBreak ? "on" : "off",
                    WalkFeel.climbCost ? "on" : "off");
        }
    }

    /**
     * A tile waiting in the search, with its price so far plus the guess to the goal ({@code f})
     * and the guess alone ({@code h}), both taken when it was queued.
     */
    private record Entry(NavNode node, int f, int h) {}

    /**
     * Climb cost 2E (10-18): the cheapest tile first, and of equally cheap tiles the one nearer the
     * goal. Open ground is full of ties, and taking them in any order looked at every tile in the
     * box between start and goal. Keys are the ones stored when the tile was queued; the old order
     * re-read the price map, which changes while an entry waits, so the heap could hand out a tile
     * that was not the cheapest.
     */
    private static final Comparator<Entry> TIE_BREAK_ORDER =
            Comparator.comparingInt(Entry::f).thenComparingInt(Entry::h);

    /** What one search found: the waypoints to walk and the full trace they were pulled from. */
    private record SearchResult(List<BlockPos> path, List<NavNode> traced) {
        Route route() {
            Set<Long> cells = new HashSet<>();
            for (NavNode node : traced) cells.add(NavGraph.cellKey(node.x, node.feetY, node.z));
            long[] keys = new long[cells.size()];
            int i = 0;
            for (long key : cells) keys[i++] = key;
            return new Route(path, keys, List.copyOf(traced));
        }
    }

    private static SearchResult search(NavGraph graph, NavNode startNode, NavNode goalNode,
                                       boolean smoothPath, Set<Long> occupied,
                                       boolean avoidOccupied, boolean allowClimb, Set<Long> excluded,
                                       RouteRules rules, int[] looked, int maxLooked) {
        // Villagers' rules allow every tile: their search never asks (raider plan round 2).
        boolean everyTile = rules.allowsEverything();
        // Bug 5 (10-19, irondoors): a villager's route never goes through an iron door shut right now
        // (raiders' rules refuse every iron door). Read once, as the switch.
        it.unimi.dsi.fastutil.longs.LongSet shutIron = everyTile && WalkFeel.ironDoors
                ? graph.shutIronDoors() : it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET;

        Map<NavNode, NavNode>  cameFrom = new HashMap<>();
        Map<NavNode, Integer>  gScore   = new HashMap<>();
        Set<NavNode>           closed   = new HashSet<>();
        // Climb cost 2C: prices in tenths of a tile (StepPrice), read once so a switch flipped
        // mid-search cannot mix the two scales.
        int scale = WalkFeel.climbCost ? StepPrice.FLAT : 1;
        // Switch off: the old order exactly, which reads the price map at every comparison.
        PriorityQueue<Entry>   open     = new PriorityQueue<>(WalkFeel.tieBreak ? TIE_BREAK_ORDER
                : Comparator.comparingInt((Entry e) ->
                        gScore.getOrDefault(e.node(), Integer.MAX_VALUE / 2) + scale * h(e.node(), goalNode)));

        gScore.put(startNode, 0);
        int startH = scale * h(startNode, goalNode);
        open.add(new Entry(startNode, startH, startH));

        while (!open.isEmpty()) {
            NavNode cur = open.poll().node();
            if (!closed.add(cur)) continue; // lazy deletion of stale entries
            if (++looked[0] > maxLooked) {
                // Raider bug B5: only {@link #reaches} caps a search; it gives up with no answer.
                looked[1] = 1;
                return null;
            }

            if (cur == goalNode) {
                List<NavNode> traced = trace(cameFrom, cur);
                List<BlockPos> path = smoothPath
                        ? smooth(graph, traced, avoidOccupied ? occupied : Set.of())
                        : toFeetPositions(traced);
                return new SearchResult(trimAvoidedStart(path, startNode), traced);
            }

            int curG = gScore.get(cur);
            for (NavNode nb : cur.neighbours()) {
                if (closed.contains(nb)) continue;
                // A legacy walker already inside may leave, but an exterior
                // route must never enter the excluded room or its doorway.
                if (!excluded.isEmpty() && excluded.contains(nb.feetPos().asLong())
                        && !excluded.contains(cur.feetPos().asLong())) continue;
                // A route may leave an unroutable node (a villager standing on a
                // chest, or already in the water) but never enter one.
                if (cur.isRoutable() && !nb.isRoutable()) continue;
                // A raider keeps to the doors and gates it can open, and off ladders.
                if (!everyTile && !rules.mayEnter(nb)) continue;
                if (nb.portalKind == NavNode.DOOR_LOCKED && !shutIron.isEmpty()
                        && shutIron.contains(NavNode.key(nb.x, nb.feetY, nb.z))) continue;
                // Edges are stored both ways, but dropping off a full block
                // onto a bottom slab below is not a jump back up.
                if (!cur.canStepTo(nb)) continue;
                // A villager already stood against this jump and could not
                // make it; send the route another way (see VillagerModel).
                if (cur.hasFailedStepTo(nb)) continue;
                if (!allowClimb && (nb.climb || cur.climb)
                        && (Math.abs(nb.feetY - cur.feetY) > 1
                        || nb.x == cur.x && nb.z == cur.z && nb.feetY != cur.feetY
                        || nb.climb && !nb.ladderFloor
                        || cur.climb && !cur.ladderFloor)) continue;
                if (avoidOccupied && nb != goalNode && occupied.contains(nb.feetPos().asLong())) continue;
                int tentativeG = curG + edgeCost(cur, nb, goalNode, scale);
                if (tentativeG < gScore.getOrDefault(nb, Integer.MAX_VALUE)) {
                    cameFrom.put(nb, cur);
                    gScore.put(nb, tentativeG);
                    int nbH = scale * h(nb, goalNode);
                    open.add(new Entry(nb, tentativeG + nbH, nbH));
                }
            }
        }

        return null;
    }

    // -------------------------------------------------------------------------

    /**
     * Where the journey begins. A villager standing somewhere it should not be —
     * on a chest, in deep water — starts from exactly that node, because the
     * first thing it has to do is leave. Everyone else starts from the nearest
     * node a route may legally use.
     */
    static NavNode nearestPreferredStart(NavGraph graph, BlockPos start) {
        NavNode exact = graph.nodeAt(start.getX(), start.getY(), start.getZ());
        if (exact != null && !exact.isRoutable()) return exact;

        exact = graph.nodeAt(start.getX(), start.getY() + 1, start.getZ());
        if (exact != null && !exact.isRoutable()) return exact;

        NavNode normal = graph.nearestRoutable(start, SNAP_XZ_RADIUS, SNAP_Y_RADIUS);
        return normal != null ? normal : graph.nearestOnFloor(start, SNAP_XZ_RADIUS, SNAP_Y_RADIUS);
    }

    /**
     * Where the journey ends.
     *
     * <p>This used to walk every node in the graph to find the closest one.
     * That is a hundred and fifty thousand nodes on generated terrain, on every
     * single pathfind, to look inside a three-block radius — the indexed lookup
     * has identical tie-breaking and touches at most forty-nine columns.
     */
    /** The goal node an unoccupied {@link #findPath(NavGraph, BlockPos, BlockPos)} ends at. */
    static NavNode nearestPreferredGoal(NavGraph graph, BlockPos goal) {
        return nearestPreferredGoal(graph, goal, Set.of());
    }

    private static NavNode nearestPreferredGoal(NavGraph graph, BlockPos goal, Set<Long> occupied) {
        NavNode original = graph.nearestRoutable(goal, SNAP_XZ_RADIUS, SNAP_Y_RADIUS);
        if (original == null || !occupied.contains(original.feetPos().asLong())) return original;
        // Keep the alternate landing on the requested floor. A different
        // floor in the same XZ column is geometrically closer but can send
        // the villager down through a cramped stairwell.
        NavNode free = graph.nearestRoutable(goal, SNAP_XZ_RADIUS, SNAP_Y_RADIUS,
                node -> node.feetY == original.feetY && !node.climb
                        && !occupied.contains(node.feetPos().asLong()));
        return free != null ? free : original;
    }

    private static int h(NavNode n, NavNode goal) {
        return Math.abs(n.x - goal.x) + Math.abs(n.z - goal.z);
    }

    /** The price of the step from {@code cur} onto {@code nb}, extras included. */
    private static int edgeCost(NavNode cur, NavNode nb, NavNode goalNode, int scale) {
        return stepCost(cur, nb, scale)
                + scale * ((nb.climb && !cur.climb ? LADDER_ENTRY_COST : 0)
                + (nb.openTrapdoor ? OPEN_TRAPDOOR_COST : 0)
                + (nb.besideCauldron && nb != goalNode ? CAULDRON_SIDE_COST : 0));
    }

    /**
     * Climb cost 2D (10-18): what a planned route's tiles cost at the prices now set, in the
     * planner's own units (tenths of a tile with {@link WalkFeel#climbCost}, else whole tiles).
     * For {@code /vtest path}.
     */
    public static int price(List<NavNode> tiles) {
        int scale = WalkFeel.climbCost ? StepPrice.FLAT : 1;
        NavNode goal = tiles.isEmpty() ? null : tiles.get(tiles.size() - 1);
        int total = 0;
        for (int i = 1; i < tiles.size(); i++) total += edgeCost(tiles.get(i - 1), tiles.get(i), goal, scale);
        return total;
    }

    /**
     * One step's own price, before the ladder, trapdoor and cauldron extras. Climb cost 2C
     * ({@code scale} 10): a level-ground step to the next tile is priced by {@link StepPrice} from
     * the real heights and the stairs' low sides, so a stair front costs a level tile and a block
     * a tile and a half more, up or down. Ladders and water keep the old price, times the scale.
     * Switch off ({@code scale} 1): the old 2 for any rise over a step, 1 for the rest.
     */
    static int stepCost(NavNode cur, NavNode nb, int scale) {
        boolean ground = !cur.climb && !nb.climb && !cur.waterDrop && !nb.waterDrop
                && Math.abs(nb.x - cur.x) + Math.abs(nb.z - cur.z) == 1;
        if (scale == 1 || !ground) return scale * (nb.standY > cur.standY + 0.6 ? 2 : 1);
        int dx = nb.x - cur.x, dz = nb.z - cur.z;
        return StepPrice.ground(nb.standY - cur.standY,
                nb.halfStepToward(-dx, -dz), cur.halfStepToward(dx, dz));
    }

    private static List<NavNode> trace(Map<NavNode, NavNode> cameFrom, NavNode end) {
        List<NavNode> full = new ArrayList<>();
        for (NavNode n = end; n != null; n = cameFrom.get(n)) full.add(n);
        Collections.reverse(full);
        return full;
    }

    private static List<BlockPos> toFeetPositions(List<NavNode> full) {
        List<BlockPos> out = new ArrayList<>(full.size());
        for (NavNode n : full) out.add(n.feetPos());
        return Collections.unmodifiableList(out);
    }

    /**
     * String-pulling smoother: greedily skip intermediate nodes while a
     * straight Bresenham line-of-sight exists through walkable XZ columns.
     */
    private static List<BlockPos> smooth(NavGraph graph, List<NavNode> full, Set<Long> occupied) {
        if (full.size() <= 2) {
            List<BlockPos> out = new ArrayList<>(full.size());
            for (NavNode n : full) out.add(n.feetPos());
            return Collections.unmodifiableList(out);
        }

        List<BlockPos> out = new ArrayList<>();
        out.add(full.get(0).feetPos());

        int i = 0;
        while (i < full.size() - 1) {
            // The first floor tile after a ladder decides which side the mob
            // steps off onto. Keep that exact tile: smoothing from the rung to
            // a later waypoint can aim straight into the backing wall even
            // when the route actually exits to an open side first.
            if (full.get(i).climb && !full.get(i + 1).climb) {
                i++;
                out.add(full.get(i).feetPos());
                continue;
            }
            // Portals (doors, fence gates) are hard waypoints: string-pulling
            // across one produces a segment vanilla navigation cannot walk
            // (the door is closed) and hides the door from the route-based
            // door-opening logic. Ladder rungs (climb) get the same treatment
            // as insurance: every rung already breaks lineOfSight on its own
            // (its Y never matches a same-level node elsewhere on the route),
            // but forcing the hard break here means a future terrain case
            // can't silently collapse a multi-rung climb into one big jump.
            // Smooth only up to the next portal/rung.
            int limit = full.size() - 1;
            for (int k = i + 1; k < full.size(); k++) {
                if (full.get(k).portal || full.get(k).climb) {
                    limit = k;
                    break;
                }
            }
            int j = limit;
            while (j > i + 1 && (!withinLegLength(full.get(i), full.get(j))
                    || !lineOfSight(graph, full.get(i), full.get(j), occupied))) j--;
            out.add(full.get(j).feetPos());
            i = j;
        }

        return Collections.unmodifiableList(out);
    }

    /** Drops the first waypoint when it is the unroutable node the walker is already on. */
    private static List<BlockPos> trimAvoidedStart(List<BlockPos> path, NavNode startNode) {
        if (startNode.isRoutable() || path.size() < 2) return path;
        return Collections.unmodifiableList(new ArrayList<>(path.subList(1, path.size())));
    }

    /** See {@link #MAX_SMOOTHED_LEG}. */
    private static boolean withinLegLength(NavNode from, NavNode to) {
        int dx = to.x - from.x, dz = to.z - from.z;
        return dx * dx + dz * dz <= MAX_SMOOTHED_LEG * MAX_SMOOTHED_LEG;
    }

    private static boolean lineOfSight(NavGraph graph, NavNode from, NavNode to, Set<Long> occupied) {
        if (from.feetY != to.feetY) return false;

        int x = from.x, z = from.z;
        int tx = to.x,  tz = to.z;
        int dx = Math.abs(tx - x), dz = Math.abs(tz - z);
        int sx = (x < tx) ? 1 : -1;
        int sz = (z < tz) ? 1 : -1;
        int err = dx - dz;

        while (true) {
            if (!allowsLineOfSightNode(graph, from, x, z, occupied)) return false;
            if (x == tx && z == tz)           return true;
            int e2    = 2 * err;
            boolean stepX = e2 > -dz;
            boolean stepZ = e2 <  dx;
            if (stepX && stepZ) {
                if (!allowsLineOfSightNode(graph, from, x + sx, z, occupied)) return false;
                if (!allowsLineOfSightNode(graph, from, x, z + sz, occupied)) return false;
            }
            if (stepX) { err -= dz; x += sx; }
            if (stepZ) { err += dx; z += sz; }
        }
    }

    private static boolean allowsLineOfSightNode(
            NavGraph graph, NavNode from, int x, int z, Set<Long> occupied) {
        NavNode node = graph.nodeAt(x, from.feetY, z);
        if (node == null) return false;
        // The line cannot tell which side of the cell the plate is on; only
        // the graph's own edges can take a route past an open trapdoor.
        if (node.openTrapdoor) return false;
        return node == from || (!node.climb && !node.isAvoidedSurface()
                && !occupied.contains(node.feetPos().asLong()));
    }
}
