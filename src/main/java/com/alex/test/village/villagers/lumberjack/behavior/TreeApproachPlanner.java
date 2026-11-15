package com.alex.test.village.villagers.lumberjack.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a lumberjack gets from the village's walkable ground to a spot beside a
 * trunk.
 *
 * <p>The nav graph treats leaves as solid, which is right for everybody else
 * and wrong for the lumberjack. A spruce whose lowest boughs sit on the
 * ground has no graph node anywhere near its trunk, so the old approach --
 * walk to whatever node is nearest a log, then push towards the trunk --
 * ended at the edge of the canopy with the villager pressed into the leaves.
 * It also never asked whether that nearest node was one the lumberjack could
 * reach at all: it could be on the far side of a fence or a house wall, and
 * the villager walked against the wall until something called it stuck, once
 * for every log in the tree.
 *
 * <p>This searches outward from the spots beside the trunk through cells a
 * villager could stand in if natural leaves were cut away, and stops at the
 * first graph node the lumberjack can actually walk to. The result is that
 * node plus the short walk from it to the trunk. Fences, walls, buildings,
 * player-placed hedges and gates all stop the search: the graph already knows
 * how to use a gate, so a fenced tree with one is reached through the
 * enclosure's own nodes, and a fenced tree without one has no plan at all --
 * which is the signal to leave it be rather than try.
 */
public final class TreeApproachPlanner {

    /** How far out from the trunk the search reaches. A mega spruce's ground boughs spread about five. */
    private static final int SEARCH_RADIUS = 8;
    /** How far above or below the trunk's base the search reaches. */
    private static final int SEARCH_DY = 3;
    /** How far from the trunk the lumberjack may stand to chop. Beside it (one block) is tried first. */
    static final int STAND_MAX_RADIUS = 2;

    private static final long NO_PARENT = Long.MIN_VALUE;

    /**
     * A way in to a tree.
     *
     * @param entry a nav node the lumberjack can walk to
     * @param path  cells from {@code entry} to the chopping spot, both included,
     *              each one step from the last; natural leaves on the way are
     *              cut as it is walked
     */
    record Plan(NavNode entry, List<BlockPos> path) {
        BlockPos entryPos() { return path.get(0); }
        BlockPos standSpot() { return path.get(path.size() - 1); }
    }

    private TreeApproachPlanner() {}

    /**
     * Finds a way in, or null when the tree cannot be reached from where the
     * lumberjack is. {@code excludedEntries} are entry cells a walk already
     * failed to get to.
     */
    @Nullable
    static Plan plan(Activity.Context ctx, TreeStructure tree, Set<BlockPos> excludedEntries) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        List<BlockPos> trunk = groundTrunk(tree);
        // Squarely beside the trunk first. Only if none of those can be got
        // to, one block further out.
        Plan plan = search(ctx, graph, tree, trunk, 1, excludedEntries);
        return plan != null ? plan
                : search(ctx, graph, tree, trunk, STAND_MAX_RADIUS, excludedEntries);
    }

    /**
     * Whether {@link #plan} finds a way in (the away lumberjack's check). It reads
     * blocks up to {@link #REACH} from the base: the caller makes sure those
     * chunks are loaded, or reading them would load them.
     */
    public static boolean canApproach(Activity.Context ctx, TreeStructure tree) {
        return plan(ctx, tree, Set.of()) != null;
    }

    /** How far from the tree's base {@link #plan} reads blocks: its search, a 2x2 trunk and the cell beside. */
    public static final int REACH = SEARCH_RADIUS + 2;

    /** The trunk's bottom layer: one log, or four for a 2x2 tree. */
    static List<BlockPos> groundTrunk(TreeStructure tree) {
        List<BlockPos> trunk = tree.logs().stream()
                .filter(log -> log.getY() == tree.base().getY())
                .toList();
        return trunk.isEmpty() ? List.of(tree.base()) : trunk;
    }

    /**
     * True for a leaf block a lumberjack may cut through: grown, not placed
     * (a hedge is somebody's build), and not up against a building.
     */
    public static boolean isClearableLeaf(Activity.Context ctx, BlockPos pos) {
        BlockState state = ctx.level().getBlockState(pos);
        return state.is(BlockTags.LEAVES)
                && TreeStructure.isNaturalLeaf(state)
                && !ctx.mgr().isProtectedTreePosition(pos);
    }

    /**
     * Cuts the natural leaves a villager is pressed up against. A walk over
     * the nav graph never plans into leaves, but a route along the edge of a
     * canopy clips its corners, and the lumberjack used to grind against them
     * until the movement code called it stuck, seconds later; only the walk in
     * after that cut anything. Checked only while the villager is actually
     * blocked, so an open walk costs a single field read. Each leaf cut rolls
     * its sticks and apples through {@code rewards}.
     */
    static void cutLeavesPressedAgainst(Activity.Context ctx, LeafRewards rewards) {
        NpcEntity npc = ctx.npc();
        if (npc == null || !npc.horizontalCollision) return;
        // A little ahead on every side, feet and head height only.
        AABB box = npc.getBoundingBox().inflate(0.3, 0.0, 0.3);
        int feetY = (int) Math.floor(box.minY);
        boolean stepUp = false;
        for (BlockPos pos : BlockPos.betweenClosed(
                (int) Math.floor(box.minX), feetY, (int) Math.floor(box.minZ),
                (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            if (isClearableLeaf(ctx, pos)) {
                rewards.cut(ctx, pos.immutable());
            } else if (pos.getY() == feetY && !pos.equals(npc.blockPosition())
                    && !NavGraph.isPassable(ctx.level(), pos.getX(), pos.getY(), pos.getZ())) {
                stepUp = true;
            }
        }
        // Ground rising ahead: the jump takes the head a block higher, so
        // the leaves over the head go too. On the flat they are left.
        if (stepUp) {
            for (BlockPos pos : BlockPos.betweenClosed(
                    (int) Math.floor(box.minX), feetY + 2, (int) Math.floor(box.minZ),
                    (int) Math.floor(box.maxX), feetY + 2, (int) Math.floor(box.maxZ))) {
                if (isClearableLeaf(ctx, pos)) rewards.cut(ctx, pos.immutable());
            }
        }
    }

    /**
     * Whether a villager could stand in {@code feet} once any natural leaves
     * in it were cut. Graph nodes count as they are; elsewhere this needs a
     * floor, two cells of body room, and no water. Doors and gates are left
     * to the graph, which knows to open them.
     */
    public static boolean isWalkable(Activity.Context ctx, NavGraph graph, BlockPos feet) {
        NavNode node = graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
        if (node != null) return node.isRoutable() && !node.climb && !node.portal;

        BlockPos floor = feet.below();
        BlockState floorState = ctx.level().getBlockState(floor);
        if (floorState.is(BlockTags.LEAVES)
                || floorState.getBlock() instanceof FenceBlock
                || floorState.getBlock() instanceof WallBlock
                || floorState.getBlock() instanceof FenceGateBlock
                || floorState.getCollisionShape(ctx.level(), floor).isEmpty()) {
            return false;
        }
        if (!ctx.level().getFluidState(feet).isEmpty()
                || !ctx.level().getFluidState(feet.above()).isEmpty()
                || !ctx.level().getFluidState(floor).isEmpty()) {
            return false;
        }
        return isBodyRoom(ctx, feet) && isBodyRoom(ctx, feet.above());
    }

    /** Free for a villager's body now, or once its natural leaves are cut. */
    static boolean isBodyRoom(Activity.Context ctx, BlockPos pos) {
        BlockState state = ctx.level().getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) {
            return false;
        }
        return NavGraph.isPassable(ctx.level(), pos.getX(), pos.getY(), pos.getZ())
                || isClearableLeaf(ctx, pos);
    }

    /**
     * A step between two neighbouring cells a block apart in height needs
     * room over the lower one for the head on the way up or down.
     */
    static boolean canStep(Activity.Context ctx, BlockPos a, BlockPos b) {
        if (a.getY() == b.getY()) return true;
        BlockPos lower = a.getY() < b.getY() ? a : b;
        return isBodyRoom(ctx, lower.above(2));
    }

    /**
     * The shortest way out of a canopy from {@code from}, first cell to last:
     * cells a villager could stand in once natural leaves are cut, ending on
     * a graph node in the open -- no leaves in its body space or beside it.
     * For a villager standing where a tree has just grown round it: the graph
     * only catches up with new leaves after its rebuild debounce, and until
     * then it still offers nodes inside the canopy.
     *
     * <p>When nothing in reach is fully in the open (a hedge-lined path, a
     * grove), the nearest leaf-free node will do. Empty when {@code from} is
     * already in the open or there is no way out at all.
     */
    public static List<BlockPos> wayOut(Activity.Context ctx, BlockPos from) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || isOpenNode(ctx, graph, from)) return List.of();
        Map<Long, Long> towardsStart = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        towardsStart.put(from.asLong(), NO_PARENT);
        queue.add(from);
        BlockPos fallback = null;

        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            if (!cell.equals(from)) {
                if (isOpenNode(ctx, graph, cell)) return outward(towardsStart, cell);
                if (fallback == null && isClearNode(ctx, graph, cell)) fallback = cell;
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos next = cell.offset(dir.getStepX(), dy, dir.getStepZ());
                    if (Math.abs(next.getX() - from.getX()) > SEARCH_RADIUS
                            || Math.abs(next.getZ() - from.getZ()) > SEARCH_RADIUS
                            || Math.abs(next.getY() - from.getY()) > SEARCH_DY) continue;
                    if (towardsStart.containsKey(next.asLong())) continue;
                    // A stale node can have a log in it now, so the body room
                    // is checked even on graph nodes.
                    if (!isWalkable(ctx, graph, next)
                            || !isBodyRoom(ctx, next) || !isBodyRoom(ctx, next.above())
                            || !canStep(ctx, cell, next)) continue;
                    towardsStart.put(next.asLong(), cell.asLong());
                    queue.add(next);
                }
            }
        }
        return fallback != null ? outward(towardsStart, fallback) : List.of();
    }

    /** A routable graph node with no leaves where the body goes. */
    private static boolean isClearNode(Activity.Context ctx, NavGraph graph, BlockPos cell) {
        NavNode node = graph.nodeAt(cell.getX(), cell.getY(), cell.getZ());
        return node != null && node.isRoutable() && !node.climb && !node.portal
                && !isLeaf(ctx, cell) && !isLeaf(ctx, cell.above());
    }

    /** A clear node with no leaves beside the body either: out from under the canopy. */
    private static boolean isOpenNode(Activity.Context ctx, NavGraph graph, BlockPos cell) {
        if (!isClearNode(ctx, graph, cell)) return false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos beside = cell.relative(dir);
            if (isLeaf(ctx, beside) || isLeaf(ctx, beside.above())) return false;
        }
        return true;
    }

    private static boolean isLeaf(Activity.Context ctx, BlockPos pos) {
        return ctx.level().getBlockState(pos).is(BlockTags.LEAVES);
    }

    /** {@link #trace} turned round: from the start of the search out to {@code end}. */
    private static List<BlockPos> outward(Map<Long, Long> towardsStart, BlockPos end) {
        List<BlockPos> path = new ArrayList<>(trace(towardsStart, end));
        Collections.reverse(path);
        return path;
    }

    // -------------------------------------------------------------------------

    /**
     * Breadth-first from every chopping spot at once, outward. The first cell
     * that is a graph node the lumberjack can reach ends it, so the walk
     * through leaves is as short as it can be and the lumberjack chops from
     * the side of the tree it comes from.
     */
    @Nullable
    private static Plan search(Activity.Context ctx, NavGraph graph, TreeStructure tree,
                               List<BlockPos> trunk, int maxRing, Set<BlockPos> excludedEntries) {
        BlockPos base = tree.base();
        Map<Long, Long> towardsTrunk = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        for (BlockPos spot : standSpots(trunk, maxRing)) {
            if (towardsTrunk.containsKey(spot.asLong())) continue;
            if (!isWalkable(ctx, graph, spot)) continue;
            towardsTrunk.put(spot.asLong(), NO_PARENT);
            queue.add(spot);
        }

        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            NavNode entry = reachableEntry(ctx, graph, cell, excludedEntries);
            if (entry != null) return new Plan(entry, trace(towardsTrunk, cell));

            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos next = cell.offset(dir.getStepX(), dy, dir.getStepZ());
                    if (Math.abs(next.getX() - base.getX()) > SEARCH_RADIUS
                            || Math.abs(next.getZ() - base.getZ()) > SEARCH_RADIUS
                            || Math.abs(next.getY() - base.getY()) > SEARCH_DY) continue;
                    if (towardsTrunk.containsKey(next.asLong())) continue;
                    if (!isWalkable(ctx, graph, next) || !canStep(ctx, next, cell)) continue;
                    towardsTrunk.put(next.asLong(), cell.asLong());
                    queue.add(next);
                }
            }
        }
        return null;
    }

    @Nullable
    private static NavNode reachableEntry(Activity.Context ctx, NavGraph graph, BlockPos cell,
                                          Set<BlockPos> excludedEntries) {
        if (excludedEntries.contains(cell)) return null;
        NavNode node = graph.nodeAt(cell.getX(), cell.getY(), cell.getZ());
        if (node == null || !node.isRoutable() || node.climb || node.portal) return null;
        return ctx.model().canReach(graph, cell) ? node : null;
    }

    /** Cells in rings 1..{@code maxRing} round the trunk, squarely beside it first. */
    private static List<BlockPos> standSpots(List<BlockPos> trunk, int maxRing) {
        List<BlockPos> spots = new ArrayList<>();
        for (int ring = 1; ring <= maxRing; ring++) {
            for (BlockPos log : trunk) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        for (int dy = 1; dy >= -1; dy--) {
                            spots.add(log.offset(dx, dy, dz));
                        }
                    }
                }
            }
        }
        spots.sort(Comparator.comparingDouble(spot -> trunkDistSq(trunk, spot)));
        return spots;
    }

    private static double trunkDistSq(List<BlockPos> trunk, BlockPos spot) {
        double best = Double.MAX_VALUE;
        for (BlockPos log : trunk) {
            double dx = spot.getX() - log.getX();
            double dz = spot.getZ() - log.getZ();
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    /** From {@code entry} back along the search to the chopping spot it came from. */
    private static List<BlockPos> trace(Map<Long, Long> towardsTrunk, BlockPos entry) {
        List<BlockPos> path = new ArrayList<>();
        long key = entry.asLong();
        while (key != NO_PARENT) {
            path.add(BlockPos.of(key));
            key = towardsTrunk.get(key);
        }
        return Collections.unmodifiableList(path);
    }
}
