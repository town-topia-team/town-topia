package com.alex.test.village.visitors;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.AStarPathfinder;
import com.alex.test.village.FollowWalk;
import com.alex.test.village.MoveToCommand;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageManager;
import com.alex.test.village.WalkFeel;
import net.minecraft.core.BlockPos;

import java.util.Set;
import java.util.WeakHashMap;

/**
 * Walks a nomad or merchant to a spot inside a village along the village nav
 * graph, the way villagers walk, instead of on vanilla navigation alone.
 *
 * <p>Vanilla only plans about 32 blocks ahead. For a farther target it plans to
 * whatever reachable tile is closest in a straight line, which in a built-up
 * village is often a dead-end corner against a house or pillar; re-planning
 * from that corner finds the same corner, and the traveller stays pressed
 * there. The graph knows the way round through streets and doors.
 *
 * <p>When a route gets stuck, the next one follows the graph step by step
 * instead of rebuilding the same string-pulled shortcut, which can clip a wall
 * post the thin line-of-sight test missed. That lasts until the goal changes
 * or the route is cleared.
 *
 * <p>Since 2026-10-18 the route is walked by the villagers' route follower
 * ({@link FollowWalk}), so travellers and villagers share one walker; the old
 * walker that asked Minecraft's pathfinder for each stretch is gone. Outside
 * the village ({@link Result#USE_VANILLA}) callers still walk on Minecraft's
 * path; inside with no map route ({@link Result#NO_ROUTE}) they no longer do
 * while their switch is on (docs/plans/2026-10-18-vanilla-pathfinder-leftovers.md).
 *
 * <p>A walk stuck {@value #MAX_STUCKS} times gives up ({@code WalkFeel.travelGiveUp}):
 * from then on it reads as {@link Result#NO_ROUTE} until the goal changes or the
 * route is cleared, instead of being planned again and again from the same spot.
 */
final class TravelerApproach {

    enum Result {
        /** A graph route is being walked; the caller should do nothing else this tick. */
        WALKING,
        /** Outside the village, or the village has no map: the caller walks on vanilla. */
        USE_VANILLA,
        /**
         * Inside the village, but the map has no route to the goal (asked again after
         * {@link #NO_ROUTE_RETRY_TICKS}), or the walk there gave up after {@value #MAX_STUCKS}
         * stucks (until the goal changes). With {@code WalkFeel.campMap} / {@code nomadMap}
         * off the caller treats it as {@link #USE_VANILLA}, as before.
         */
        NO_ROUTE,
        /** The route finished; the caller handles the last step to its exact tile. */
        FINISHED
    }

    /**
     * A goal the map had no route to is not planned again for this long (2 s). A failed
     * search reads every tile it can reach, and callers ask every tick.
     */
    private static final long NO_ROUTE_RETRY_TICKS = 40L;

    /** Stucks on one walk before it gives up (the first two re-plan with detailed steps). */
    private static final int MAX_STUCKS = 3;

    private static final class Route {
        final BlockPos goal;
        MoveToCommand move;
        boolean detailed;
        boolean finished;
        int stuckCount;
        /** Stuck {@link #MAX_STUCKS} times: no route until the goal changes. */
        boolean gaveUp;
        /** Game time before which a goal with no route is not planned again. */
        long retryAt = Long.MIN_VALUE;

        Route(BlockPos goal) { this.goal = goal; }
    }

    private final String label;
    private final WeakHashMap<NpcEntity, Route> routes = new WeakHashMap<>();

    TravelerApproach(String label) { this.label = label; }

    Result tick(NpcEntity traveler, VillageManager village, BlockPos goal, float speed) {
        BlockPos pos = traveler.blockPosition();
        if (!village.contains(pos) || village.getGraph() == null) {
            clear(traveler);
            return Result.USE_VANILLA;
        }

        Route route = routes.get(traveler);
        if (route == null || !route.goal.equals(goal)) {
            clear(traveler);
            route = new Route(goal);
            routes.put(traveler, route);
        }
        if (route.finished) return Result.FINISHED;
        if (route.gaveUp) return Result.NO_ROUTE;

        if (route.move == null) {
            long now = traveler.level().getGameTime();
            if (now < route.retryAt) return Result.NO_ROUTE;
            NavGraph graph = village.getGraph();
            AStarPathfinder.Route planned = AStarPathfinder.findRoute(
                    graph, pos, goal, !route.detailed, Set.of(), Set.of());
            if (planned == null) {
                route.retryAt = now + NO_ROUTE_RETRY_TICKS;
                return Result.NO_ROUTE;
            }
            // The villagers' own walker (the route follower), by the same rules
            // as theirs; a detailed re-plan after a stuck keeps to the route's
            // own tiles.
            route.move = FollowWalk.forRoute(graph, traveler, planned, speed, !route.detailed,
                    route.stuckCount, Set.of());
            if (route.move == null) {
                route.retryAt = now + NO_ROUTE_RETRY_TICKS;
                return Result.NO_ROUTE;
            }
        }

        switch (route.move.tick(traveler)) {
            case RUNNING -> { return Result.WALKING; }
            case DONE -> {
                route.move = null;
                route.finished = true;
                traveler.setLadderClimbTarget(null);
                return Result.FINISHED;
            }
            case REPLAN -> {
                // Walked as far as a very long route was read: plan the rest next
                // tick. Not a stuck.
                route.move.closeAllOpenedDoors(traveler);
                route.move = null;
                return Result.WALKING;
            }
            default -> {
                route.stuckCount++;
                if (WalkFeel.travelGiveUp && route.stuckCount >= MAX_STUCKS) {
                    DynamicVillages.LOGGER.info("[{} {}] Stuck walking to {} ({}, stuck #{}); giving up: "
                                    + "no map route until the goal changes.",
                            label, shortId(traveler), goal.toShortString(),
                            route.move.debugState(traveler), route.stuckCount);
                    route.move.closeAllOpenedDoors(traveler);
                    route.move = null;
                    route.gaveUp = true;
                    traveler.setLadderClimbTarget(null);
                    return Result.NO_ROUTE;
                }
                DynamicVillages.LOGGER.info("[{} {}] Stuck walking to {} ({}, stuck #{}); {}",
                        label, shortId(traveler), goal.toShortString(),
                        route.move.debugState(traveler), route.stuckCount,
                        route.detailed ? "re-pathing." : "re-pathing with detailed graph steps.");
                route.move.closeAllOpenedDoors(traveler);
                route.move = null;
                route.detailed = true;
                // Rebuilt next tick, not in a tight loop inside this one.
                return Result.WALKING;
            }
        }
    }

    /** Drops the traveller's route and closes any doors it opened. */
    void clear(NpcEntity traveler) {
        Route route = routes.remove(traveler);
        if (route != null && route.move != null) route.move.closeAllOpenedDoors(traveler);
        traveler.setLadderClimbTarget(null);
    }

    static String shortId(NpcEntity traveler) {
        return traveler.getUUID().toString().substring(0, 8);
    }
}
