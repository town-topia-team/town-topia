package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.EnclosureBuilding;
import com.alex.test.village.building.MineBuilding;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Outdoor places to sleep sitting against a building (round 4 of
 * docs/plans/2026-10-23-sleep-against-walls.md): a walkable tile just outside a
 * registered building, with a full block of its outer wall right behind it.
 * Nothing sends villagers here yet (round 5); {@code /villager wallspots} shows them.
 *
 * <p>Found the way the Town Hall picks its spots: cheap geometry for the whole
 * village first (kept for a minute), then the costlier checks -- still a wall,
 * nobody there, reachable -- only on the tiles tried, nearest first.
 */
public final class WallSleepSpots {

    /** A tile to sit on, the side its wall is on, and whether a lamp lights it. */
    public record Spot(BlockPos tile, Direction back, boolean lit) {}

    /** Walls up to this thick are walked through to find the outside. */
    private static final int MAX_WALL = 3;
    /** Outside ground this far below the room's floor (a raised floor) or above it still counts. */
    private static final int DROP = 3, RISE = 1;
    /** Tiles within this many of a doorway or gate are left out, so 3 or more away is fine. */
    private static final int DOOR_GAP = 2;
    /** Block light from this up counts as lit: a torch within about seven blocks. */
    private static final int LIT = 8;
    /** Spots are tried nearest first in bands this wide, the lit ones first within a band. */
    private static final int BAND = 16;
    /** At most this many free spots are asked "can I walk there" in one pick. */
    private static final int MAX_REACH_TRIES = 24;
    /** How long a village's list is kept before it is found again. */
    private static final long KEEP_TICKS = 1200;

    private record Kept(long at, List<Spot> spots) {}
    private static final Map<VillageManager, Kept> KEPT = new WeakHashMap<>();

    private WallSleepSpots() {}

    /**
     * A full block a sleeper can lean on: planks, stone, logs, glass; not a fence,
     * chest, door, stair or slab, which would leave a gap or swing open.
     */
    public static boolean isLeanWall(Level level, BlockPos pos) {
        return level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
    }

    /**
     * Every outdoor wall spot in the village, cheap checks only. Kept for a
     * minute; {@code fresh} finds them again now (the debug command).
     */
    public static List<Spot> spots(VillageManager mgr, ServerLevel level, boolean fresh) {
        long now = level.getGameTime();
        Kept kept = KEPT.get(mgr);
        if (!fresh && kept != null && now >= kept.at() && now - kept.at() < KEEP_TICKS) return kept.spots();
        List<Spot> spots = find(mgr, level);
        KEPT.put(mgr, new Kept(now, spots));
        return spots;
    }

    /**
     * The spot for this villager: nearest band first, lit before dark in a band,
     * then nearest. Skips other sleepers' reserved spots and the tiles beside
     * them, tiles someone stands on, and any whose wall or ground has gone since
     * the list was made. Null when none is free and reachable.
     */
    @Nullable
    public static Spot pick(Activity.Context ctx) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        List<Spot> order = new ArrayList<>(spots(ctx.mgr(), ctx.level(), false));
        if (order.isEmpty()) return null;
        BlockPos from = ctx.npc().blockPosition();
        order.sort(Comparator.comparingInt((Spot s) -> (int) (Math.sqrt(flatDistSqr(s.tile(), from)) / BAND))
                .thenComparing(s -> !s.lit())
                .thenComparingDouble(s -> flatDistSqr(s.tile(), from)));
        LongOpenHashSet blocked = blockedByOthers(ctx);
        int tries = 0;
        for (Spot s : order) {
            BlockPos tile = s.tile();
            if (blocked.contains(tile.asLong()) || ctx.model().hasRecentMoveFailure(tile)) continue;
            if (!stillThere(ctx.level(), graph, s) || someoneOn(ctx, tile)) continue;
            if (++tries > MAX_REACH_TRIES) break;
            if (ctx.model().canReach(graph, tile)) return s;
        }
        return null;
    }

    // -------------------------------------------------------------------------

    private static List<Spot> find(VillageManager mgr, ServerLevel level) {
        NavGraph graph = mgr.getGraph();
        if (graph == null) return List.of();
        // Every building's own tiles (rooms, pens, the range): nobody sleeps on those out here.
        LongOpenHashSet taken = new LongOpenHashSet();
        List<BlockPos> doors = new ArrayList<>();
        for (Building b : mgr.getBuildings()) {
            for (BlockPos p : b.getNavMeshBlocks()) taken.add(p.asLong());
            if (b.getDoorPos() != null) doors.add(b.getDoorPos());
        }
        Predicate<NavNode> open = OpenGround.openTest(mgr);
        LongOpenHashSet seen = new LongOpenHashSet();
        List<Spot> spots = new ArrayList<>();
        for (Building b : mgr.getBuildings()) {
            if (!hasWalls(b)) continue;
            Set<BlockPos> room = b.getNavMeshBlocks();
            for (BlockPos p : room) {
                for (Direction out : Direction.Plane.HORIZONTAL) {
                    if (room.contains(p.relative(out))) continue;
                    NavNode node = outsideOf(level, graph, p, out);
                    if (node == null) continue;
                    BlockPos tile = node.feetPos();
                    // A corner tile is reached from two walls: the first one found is its back.
                    if (!seen.add(tile.asLong())) continue;
                    if (taken.contains(tile.asLong()) || !mgr.contains(tile) || !goodGround(level, node)
                            || !open.test(node) || nearDoor(graph, doors, tile)) continue;
                    spots.add(new Spot(tile, out.getOpposite(),
                            level.getBrightness(LightLayer.BLOCK, tile) >= LIT));
                }
            }
        }
        return List.copyOf(spots);
    }

    /** A building with walls: not a fenced pen or range, not a mine entrance. */
    private static boolean hasWalls(Building b) {
        return b.isValid() && !(b instanceof EnclosureBuilding) && !(b instanceof MineBuilding)
                && !b.getNavMeshBlocks().isEmpty();
    }

    /**
     * The outdoor tile on the far side of the wall beside room tile {@code p},
     * going {@code out}: through one to {@link #MAX_WALL} full blocks at the
     * room's feet height, then the highest walkable tile in that column from a
     * block up to {@link #DROP} down, with a full block right behind it at its
     * own feet height. Null when there is no such wall or tile.
     */
    @Nullable
    private static NavNode outsideOf(Level level, NavGraph graph, BlockPos p, Direction out) {
        BlockPos w = p.relative(out);
        int thick = 0;
        while (thick < MAX_WALL && isLeanWall(level, w)) {
            w = w.relative(out);
            thick++;
        }
        if (thick == 0 || isLeanWall(level, w)) return null;
        for (int y = w.getY() + RISE; y >= w.getY() - DROP; y--) {
            NavNode node = graph.nodeAt(w.getX(), y, w.getZ());
            if (node == null) continue;
            return isLeanWall(level, node.feetPos().relative(out.getOpposite())) ? node : null;
        }
        return null;
    }

    /**
     * Plain ground to sit on: a whole-block floor (not a slab or stair top, where the
     * wall behind starts half a block up), not a path, field, crop, water, ladder,
     * doorway or furniture top.
     */
    private static boolean goodGround(Level level, NavNode node) {
        if (!node.isRoutable() || node.climb || node.portal || node.isTransient()) return false;
        if (Math.abs(node.standY - node.feetY) > 0.05) return false;
        Block ground = node.surfaceBlock;
        if (ground == Blocks.DIRT_PATH || ground == Blocks.FARMLAND) return false;
        BlockPos feet = node.feetPos();
        return level.getFluidState(feet).isEmpty() && !level.getBlockState(feet).is(BlockTags.CLIMBABLE);
    }

    /** Within {@link #DOOR_GAP} of a building's door, or of any doorway or gate on the map. */
    private static boolean nearDoor(NavGraph graph, List<BlockPos> doors, BlockPos tile) {
        for (BlockPos d : doors) {
            if (Math.abs(d.getX() - tile.getX()) <= DOOR_GAP && Math.abs(d.getZ() - tile.getZ()) <= DOOR_GAP
                    && Math.abs(d.getY() - tile.getY()) <= 2) return true;
        }
        boolean[] hit = {false};
        graph.forEachNodeInBox(tile, DOOR_GAP, 2, n -> { if (n.portal) hit[0] = true; });
        return hit[0];
    }

    /** The list is up to a minute old: the tile and the wall behind it must still be there. */
    private static boolean stillThere(Level level, NavGraph graph, Spot s) {
        BlockPos tile = s.tile();
        return graph.nodeAt(tile.getX(), tile.getY(), tile.getZ()) != null
                && isLeanWall(level, tile.relative(s.back()));
    }

    /** Another villager is standing on the tile. */
    private static boolean someoneOn(Activity.Context ctx, BlockPos tile) {
        AABB box = new AABB(tile.getX() + 0.15, tile.getY(), tile.getZ() + 0.15,
                tile.getX() + 0.85, tile.getY() + 1.9, tile.getZ() + 0.85);
        return !ctx.level().getEntitiesOfClass(NpcEntity.class, box,
                other -> other != ctx.npc() && other.isAlive()).isEmpty();
    }

    /** Every other villager's reserved sleep spot and the tiles around it, a block up and down too. */
    private static LongOpenHashSet blockedByOthers(Activity.Context ctx) {
        LongOpenHashSet keys = new LongOpenHashSet();
        for (VillagerModel other : ctx.mgr().getVillagers()) {
            if (other == ctx.model()) continue;
            BlockPos r = other.reservedSleepSpot();
            if (r == null) continue;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dy = -1; dy <= 1; dy++)
                        keys.add(BlockPos.asLong(r.getX() + dx, r.getY() + dy, r.getZ() + dz));
        }
        return keys;
    }

    private static double flatDistSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
