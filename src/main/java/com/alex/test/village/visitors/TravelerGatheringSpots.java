package com.alex.test.village.visitors;

import com.alex.test.entity.visitor.AbstractTravelerEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.TownHallBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Assigns persistent, non-overlapping standing positions around a gathering point. */
public final class TravelerGatheringSpots {
    private static final int[][] OFFSETS = {
            {0, 0},
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1},
            {2, 0}, {0, 2}, {-2, 0}, {0, -2},
            {2, 1}, {1, 2}, {-1, 2}, {-2, 1},
            {-2, -1}, {-1, -2}, {1, -2}, {2, -1}
    };

    /** How far out from a door, and to either side of it, a traveller may stand. */
    private static final int DOOR_REACH = 4;
    /** Vertical slack for a doorstep that steps down or up. */
    private static final int DOOR_DY = 3;
    /** The spot a traveller would ideally stand: this many blocks out, this many to the side. */
    private static final int IDEAL_OUT = 2;
    private static final int IDEAL_SIDE = 2;

    private TravelerGatheringSpots() {}

    public static BlockPos claim(AbstractTravelerEntity traveler, ServerLevel level,
                                 VillageManager village, BlockPos anchor) {
        Set<BlockPos> assigned = new HashSet<>();
        Set<BlockPos> occupied = new HashSet<>();
        collectOthers(traveler, level, anchor, assigned, occupied);

        BlockPos current = traveler.getGatheringSpot();
        if (current != null && !assigned.contains(current)) return current;
        // A village asleep since the world loaded draws its map only once a
        // player comes near; until then there is no spot to pick from.
        if (village.getGraph() == null) return current != null ? current : anchor;

        for (int[] offset : OFFSETS) {
            BlockPos probe = anchor.offset(offset[0], 0, offset[1]);
            NavNode node = village.getGraph().nearestRoutable(probe, 1, 4);
            if (node == null) continue;
            BlockPos candidate = new BlockPos(node.x, node.feetY, node.z);
            if (assigned.contains(candidate) || occupied.contains(candidate)) continue;
            traveler.setGatheringSpot(candidate);
            return candidate;
        }

        // All nearby graph tiles are occupied. Keeping the anchor lets normal
        // entity pushing separate the crowd without inventing an unsafe tile.
        traveler.setGatheringSpot(anchor);
        return anchor;
    }

    /**
     * Where a traveller waits at the Town Hall: outside its door, off to one
     * side. Falls back to {@link #claim} around the gathering tile when the
     * village has no Town Hall or no side tile can be reached.
     */
    public static BlockPos claimAtTownHall(AbstractTravelerEntity traveler, ServerLevel level,
                                           VillageManager village) {
        for (Building building : village.getBuildings()) {
            if (building instanceof TownHallBuilding hall && hall.isValid()) {
                return claimBesideDoor(traveler, level, village, hall);
            }
        }
        return claim(traveler, level, village, village.getVisitorGatheringSpot());
    }

    /**
     * A standing tile outside {@code building}'s door, but never in the
     * doorway or on the lane straight out of it.
     *
     * <p>Travellers used to stand on the door block itself (the merchant, at
     * its stall) or on the doorstep right in front of it (nomads, at the Town
     * Hall). The merchant then spent its whole visit inside a door that its
     * own route closed on it and the tradesman kept opening and closing; the
     * nomads simply blocked the way in. The spot is found by a short walk over
     * the graph from the doorstep, so it is always one the traveller can reach
     * without going through the building.
     */
    public static BlockPos claimBesideDoor(AbstractTravelerEntity traveler, ServerLevel level,
                                           VillageManager village, Building building) {
        BlockPos door = building.getDoorPos();
        Direction out = building.getInteriorDirection().getOpposite();
        BlockPos doorstep = door.relative(out);
        NavGraph graph = village.getGraph();
        if (graph == null) return claim(traveler, level, village, doorstep);

        Set<BlockPos> assigned = new HashSet<>();
        Set<BlockPos> occupied = new HashSet<>();
        collectOthers(traveler, level, door, assigned, occupied);

        BlockPos current = traveler.getGatheringSpot();
        if (current != null && !assigned.contains(current)
                && isBesideDoor(level, current, door, out, building)) {
            return current;
        }

        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (NavNode node : reachableNearDoor(level, graph, door, doorstep, out, building)) {
            BlockPos feet = node.feetPos();
            if (!isBesideDoor(level, feet, door, out, building)) continue;
            if (assigned.contains(feet) || occupied.contains(feet)) continue;
            int score = score(feet, door, out);
            if (score < bestScore) {
                bestScore = score;
                best = feet;
            }
        }
        if (best == null) return claim(traveler, level, village, doorstep);
        traveler.setGatheringSpot(best);
        return best;
    }

    /** True when {@code pos} is in {@code building}'s doorway or on the lane straight out of it. */
    public static boolean blocksDoor(ServerLevel level, BlockPos pos, Building building) {
        if (level.getBlockState(pos).getBlock() instanceof DoorBlock) return true;
        BlockPos door = building.getDoorPos();
        Direction out = building.getInteriorDirection().getOpposite();
        return Math.abs(pos.getY() - door.getY()) <= DOOR_DY
                && side(pos, door, out) == 0 && outward(pos, door, out) >= 0
                && outward(pos, door, out) <= DOOR_REACH;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** A stall worker stands outside enclosed rooms and clear of the entrance lane. */
    public static boolean isExteriorStand(ServerLevel level, BlockPos pos, Building building) {
        return !building.isInRoom(pos) && !building.getNavMeshBlocks().contains(pos)
                && !blocksDoor(level, pos, building);
    }

    private static void collectOthers(AbstractTravelerEntity traveler, ServerLevel level, BlockPos anchor,
                                      Set<BlockPos> assigned, Set<BlockPos> occupied) {
        List<AbstractTravelerEntity> nearby = level.getEntitiesOfClass(
                AbstractTravelerEntity.class, new AABB(anchor).inflate(12.0D),
                other -> other != traveler && other.isAlive() && !other.isRemoved()
                        && !other.isReturningHome());
        for (AbstractTravelerEntity other : nearby) {
            if (other.getGatheringSpot() != null) assigned.add(other.getGatheringSpot());
            occupied.add(other.blockPosition());
        }
    }

    /**
     * Outside the door, within reach of it, off the lane straight out of it,
     * not a door and not a tile of the building's own room.
     */
    private static boolean isBesideDoor(ServerLevel level, BlockPos pos, BlockPos door,
                                        Direction out, Building building) {
        int o = outward(pos, door, out);
        int s = Math.abs(side(pos, door, out));
        return o >= 1 && o <= DOOR_REACH && s >= 1 && s <= DOOR_REACH
                && Math.abs(pos.getY() - door.getY()) <= DOOR_DY
                && !building.getNavMeshBlocks().contains(pos)
                && !(level.getBlockState(pos).getBlock() instanceof DoorBlock);
    }

    /** Lower is better: close to {@link #IDEAL_OUT} out and {@link #IDEAL_SIDE} aside, on the door's level. */
    private static int score(BlockPos pos, BlockPos door, Direction out) {
        return 2 * Math.abs(outward(pos, door, out) - IDEAL_OUT)
                + 2 * Math.abs(Math.abs(side(pos, door, out)) - IDEAL_SIDE)
                + 3 * Math.abs(pos.getY() - door.getY());
    }

    /**
     * Graph nodes a traveller can walk to from the doorstep without leaving
     * the box around the door and without passing through any door, so the
     * search never wanders through the building and out the other side.
     */
    private static List<NavNode> reachableNearDoor(ServerLevel level, NavGraph graph, BlockPos door,
                                                   BlockPos doorstep, Direction out, Building building) {
        NavNode start = graph.nearestRoutable(doorstep, 1, 2);
        if (start == null) return List.of();
        List<NavNode> found = new java.util.ArrayList<>();
        Set<NavNode> seen = new HashSet<>();
        ArrayDeque<NavNode> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            NavNode current = queue.removeFirst();
            found.add(current);
            for (NavNode next : current.neighbours()) {
                if (seen.contains(next) || !next.isRoutable() || !current.canStepTo(next)) continue;
                BlockPos feet = next.feetPos();
                if (outward(feet, door, out) < 1 || outward(feet, door, out) > DOOR_REACH + 1
                        || Math.abs(side(feet, door, out)) > DOOR_REACH + 1
                        || Math.abs(feet.getY() - door.getY()) > DOOR_DY
                        || building.getNavMeshBlocks().contains(feet)
                        || level.getBlockState(feet).getBlock() instanceof DoorBlock) continue;
                seen.add(next);
                queue.addLast(next);
            }
        }
        return found;
    }

    /** Blocks from the door's plane out along {@code out}; the doorstep is 1. */
    private static int outward(BlockPos pos, BlockPos door, Direction out) {
        return (pos.getX() - door.getX()) * out.getStepX() + (pos.getZ() - door.getZ()) * out.getStepZ();
    }

    /** Blocks to the side of the door's lane; negative on one side, positive on the other. */
    private static int side(BlockPos pos, BlockPos door, Direction out) {
        Direction right = out.getClockWise();
        return (pos.getX() - door.getX()) * right.getStepX() + (pos.getZ() - door.getZ()) * right.getStepZ();
    }
}
