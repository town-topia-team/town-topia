package com.alex.test.village.building;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.chat.RegistrationFailure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Access to a building from the Town Hall's connected navigation graph.
 *
 * <p>"Connected" used to be a flood over every routable tile from the bell,
 * redone after every edit and sliced over the ticks that followed it. It is
 * now one lookup in the graph's piece map ({@link NavGraph#cells()}), which
 * the edit itself keeps current: the flood runs over a few thousand pieces
 * inside the edit's tick, following the same rule as before -- ordinary
 * steps and steps a villager recently failed, never onto furniture or
 * water. The {@code origin} every caller passes is the bell the graph was
 * built from, so the graph owns the one root; the parameter stays so that
 * callers need not change.
 */
public final class BuildingAccess {

    private BuildingAccess() {}

    @Nullable
    public static RegistrationFailure room(NavGraph graph, BlockPos origin, BlockPos door, Direction insideDirection,
                                           Set<BlockPos> roomTiles) {
        if (graph == null || graph.isEmpty()) return RegistrationFailure.PATH_GRAPH_UNAVAILABLE;
        BlockPos firstTile = door.relative(insideDirection);
        NavNode outside = graph.nearestRoutable(door.relative(insideDirection.getOpposite()), 0, 1);
        NavNode portal = graph.nearestRoutable(door, 0, 1);
        NavNode inside = graph.nearestRoutable(firstTile, 0, 1);
        return inside != null && roomTiles.contains(inside.feetPos())
                && connected(graph, origin, outside)
                && edge(outside, portal) && edge(portal, inside)
                ? null : RegistrationFailure.ROOM_UNREACHABLE;
    }

    @Nullable
    public static RegistrationFailure ranch(NavGraph graph, BlockPos origin, BlockPos gate, Direction insideDirection,
                                            Set<BlockPos> penTiles) {
        if (graph == null || graph.isEmpty()) return RegistrationFailure.PATH_GRAPH_UNAVAILABLE;
        NavNode outside = graph.nearestRoutable(gate.relative(insideDirection.getOpposite()), 0, 1);
        if (!connected(graph, origin, outside)) {
            return RegistrationFailure.RANCH_GATE_UNREACHABLE;
        }
        NavNode portal = graph.nearestRoutable(gate, 0, 1);
        if (!edge(outside, portal)) return RegistrationFailure.RANCH_CANNOT_ENTER_GATE;
        NavNode inside = graph.nearestRoutable(gate.relative(insideDirection), 0, 1);
        if (inside == null || !penTiles.contains(inside.feetPos())) {
            return RegistrationFailure.RANCH_NO_REACHABLE_TILES;
        }
        return edge(portal, inside) ? null : RegistrationFailure.RANCH_CANNOT_ENTER_GATE;
    }

    @Nullable
    public static RegistrationFailure mine(NavGraph graph, BlockPos origin, Set<BlockPos> approach) {
        if (graph == null || graph.isEmpty()) return RegistrationFailure.PATH_GRAPH_UNAVAILABLE;
        for (BlockPos tile : approach) {
            NavNode node = graph.nodeAt(tile.getX(), tile.getY(), tile.getZ());
            if (isAccessibleNode(graph, origin, node)) return null;
        }
        return RegistrationFailure.MINE_UNREACHABLE;
    }

    public static int reachableStallTiles(NavGraph graph, BlockPos origin, Set<BlockPos> tiles) {
        return connectedStallTiles(graph, origin, tiles).size();
    }

    public static Set<BlockPos> connectedStallTiles(NavGraph graph, BlockPos origin,
                                                    Set<BlockPos> tiles) {
        Set<BlockPos> result = new LinkedHashSet<>();
        if (graph == null) return result;
        for (BlockPos tile : tiles) {
            NavNode node = graph.nodeAt(tile.getX(), tile.getY(), tile.getZ());
            if (isAccessibleNode(graph, origin, node)) result.add(tile);
        }
        return result;
    }

    @Nullable
    public static RegistrationFailure stall(NavGraph graph, BlockPos origin, Set<BlockPos> tiles) {
        if (graph == null || graph.isEmpty()) return RegistrationFailure.PATH_GRAPH_UNAVAILABLE;
        return reachableStallTiles(graph, origin, tiles) >= MerchantStallBuilding.MIN_OPEN_TILES
                ? null : RegistrationFailure.STALL_TOO_FEW_TILES;
    }

    /** A post needs its own patrol position, rather than any node in a 12-block area. */
    public static boolean guardPost(NavGraph graph, BlockPos origin, BlockPos framePos) {
        if (graph == null || graph.isEmpty()) return false;
        // Asked for every guard on every tick, yet the answer only moves when the
        // path map is edited (its version counter then goes up). Reused until
        // then, and for at most GUARD_POST_MEMO_NANOS as a safety net.
        long version = graph.version();
        long now = System.nanoTime();
        PostQuery query = new PostQuery(framePos.asLong(), origin == null ? Long.MIN_VALUE : origin.asLong());
        synchronized (GUARD_POST_MEMO) {
            PostMemo memo = GUARD_POST_MEMO.computeIfAbsent(graph, g -> new PostMemo());
            if (memo.version != version || now - memo.stamp > GUARD_POST_MEMO_NANOS) {
                memo.answers.clear();
                memo.version = version;
                memo.stamp = now;
            }
            Boolean known = memo.answers.get(query);
            if (known != null) return known;
        }
        boolean answer = computeGuardPost(graph, origin, framePos);
        synchronized (GUARD_POST_MEMO) {
            PostMemo memo = GUARD_POST_MEMO.get(graph);
            if (memo != null && memo.version == version) memo.answers.put(query, answer);
        }
        return answer;
    }

    private static final long GUARD_POST_MEMO_NANOS = 5_000_000_000L;
    private static final java.util.Map<NavGraph, PostMemo> GUARD_POST_MEMO = new java.util.WeakHashMap<>();

    private record PostQuery(long frame, long origin) {}

    private static final class PostMemo {
        long version = Long.MIN_VALUE;
        long stamp;
        final java.util.Map<PostQuery, Boolean> answers = new java.util.HashMap<>();
    }

    private static boolean computeGuardPost(NavGraph graph, BlockPos origin, BlockPos framePos) {
        for (NavNode node : graph.nodesInBox(framePos, 4, 3)) {
            BlockPos pos = node.feetPos();
            int dx = pos.getX() - framePos.getX();
            int dz = pos.getZ() - framePos.getZ();
            int distance = dx * dx + dz * dz;
            if (distance >= 4 && distance <= 16 && isAccessibleNode(graph, origin, node)) {
                return true;
            }
        }
        return false;
    }

    private static boolean edge(@Nullable NavNode from, @Nullable NavNode to) {
        if (from == null || to == null) return false;
        for (NavNode next : from.neighbours()) {
            if (next == to) return from.canStepTo(to);
        }
        return false;
    }

    public static boolean isUsableNode(@Nullable NavNode node) {
        if (node == null || !node.isRoutable()) return false;
        for (NavNode neighbour : node.neighbours()) {
            if (neighbour.isRoutable() && neighbour.canStepTo(node)) return true;
        }
        return false;
    }

    public static boolean isAccessibleNode(NavGraph graph, BlockPos origin,
                                           @Nullable NavNode node) {
        return graph != null && isUsableNode(node) && connected(graph, origin, node);
    }

    /** The exact target must have a walking route; nearby-node snapping is not proof. */
    private static boolean connected(NavGraph graph, BlockPos origin, @Nullable NavNode target) {
        return target != null && target.isRoutable() && graph.cells().accessible(target);
    }
}
