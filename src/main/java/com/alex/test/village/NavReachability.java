package com.alex.test.village;

import net.minecraft.core.BlockPos;

/**
 * "Can I walk from here to there?" without running A*.
 *
 * <p>Reachability checks used to be a full A* search on the server thread,
 * cached per (start node, goal node). Every villager stands on a different
 * node, so the cache almost never hit, and a search that fails -- the goal is
 * not reachable -- explores everything reachable before giving up. At dusk
 * every villager checked every bed and town-hall tile that way at once.
 *
 * <p>The answer now comes from the graph's piece map ({@link NavCells}): the
 * tiles of each chunk section grouped into pieces that can all reach each
 * other, and the pieces labelled by component the way the whole graph used
 * to be, over a few thousand pieces instead of a hundred and sixty thousand
 * tiles. The map is redone inside the tick of every edit, so there are no
 * stale labels and no labelling to finish on the spot.
 *
 * <p>The step rule is exactly the one {@link AStarPathfinder} uses with no
 * occupancy and climbing allowed, which is the most permissive of its passes,
 * failed jumps excluded, and start and goal snap the same way {@code findPath}
 * snaps them. So the answer matches what {@code findPath(...) != null} returned.
 */
public final class NavReachability {

    private NavReachability() {}

    /** Same answer as {@code AStarPathfinder.findPath(graph, start, goal) != null}. */
    public static boolean canReach(NavGraph graph, BlockPos start, BlockPos goal) {
        if (graph == null || start == null || goal == null) return false;
        NavNode startNode = AStarPathfinder.nearestPreferredStart(graph, start);
        NavNode goalNode  = AStarPathfinder.nearestPreferredGoal(graph, goal);
        if (startNode == null || goalNode == null) return false;
        if (startNode == goalNode) return true;
        // A tile with no piece can only mean the graph changed without a
        // regroup, which no edit path allows; answer the slow way rather
        // than guess.
        if (startNode.piece() == null || goalNode.piece() == null) {
            return AStarPathfinder.findPath(graph, start, goal) != null;
        }
        return graph.cells().reaches(startNode, goalNode);
    }
}
