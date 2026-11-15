package com.alex.test.village;

import com.alex.test.block.WoodenChairBlock;
import com.alex.test.entity.ChairSeatEntity;
import com.alex.test.village.brain.VillagerPause;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mod's chairs, as villagers see them: where they are, where to stand to
 * sit on one, and who is already on the way to which.
 *
 * <p>Chairs are found by scanning blocks around a point when a villager wants
 * one, not kept in a registry. A villager asks rarely (once per meal or visit
 * to the venue), the scan is a few thousand block reads, and a chair placed a
 * second ago or outdoors in the square is found with no bookkeeping.
 *
 * <p>Claims live only in memory. The one holding a claim refreshes it every
 * tick it still wants the chair; a claim nobody refreshed for
 * {@link #CLAIM_TIMEOUT_TICKS} is free again, so an activity that ends without
 * cleaning up cannot keep a chair forever. Once somebody is actually sitting,
 * the seat entity itself marks the chair taken.
 */
public final class VillageChairs {

    /** A chair plus the nav tile a villager walks to before sitting down. */
    public record Chair(BlockPos pos, Direction front, BlockPos approach) {}

    private record Claim(UUID owner, long refreshedAt) {}

    private static final int CLAIM_TIMEOUT_TICKS = 40;
    /** How far above and below the search centre chairs are looked for. */
    private static final int SEARCH_DY = 3;

    private static final Map<ResourceKey<Level>, Map<BlockPos, Claim>> CLAIMS = new HashMap<>();

    private VillageChairs() {}

    /**
     * Every chair within {@code radius} blocks (flat distance) of
     * {@code center} that has a walkable tile to sit down from.
     */
    public static List<Chair> findNear(ServerLevel level, NavGraph graph, BlockPos center, int radius) {
        List<Chair> found = new ArrayList<>();
        if (graph == null) return found;
        double maxDistSq = (double) radius * radius;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = center.getY() - SEARCH_DY; y <= center.getY() + SEARCH_DY; y++) {
            for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    int dx = x - center.getX(), dz = z - center.getZ();
                    if (dx * dx + dz * dz > maxDistSq) continue;
                    cursor.set(x, y, z);
                    Chair chair = chairAt(level, graph, cursor);
                    if (chair != null) found.add(chair);
                }
            }
        }
        return found;
    }

    /** Every chair in the box between {@code min} and {@code max}, both inclusive. */
    public static List<Chair> findInBox(ServerLevel level, NavGraph graph, BlockPos min, BlockPos max) {
        List<Chair> found = new ArrayList<>();
        if (graph == null) return found;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            Chair chair = chairAt(level, graph, pos);
            if (chair != null) found.add(chair);
        }
        return found;
    }

    /** The chair at {@code pos}, or null if there is none or no way to sit on it. */
    @Nullable
    public static Chair chairAt(ServerLevel level, NavGraph graph, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof WoodenChairBlock)) return null;
        Direction front = state.getValue(WoodenChairBlock.FACING);
        BlockPos approach = approachTile(graph, pos.immutable(), front);
        return approach == null ? null : new Chair(pos.immutable(), front, approach);
    }

    /**
     * The tile to sit down from: in front of the chair, else beside it, else
     * behind it. Behind is last, but it is what makes a chair tucked in at a
     * table usable at all: the table is in front and, in a row, the chairs
     * either side are obstacles, so the back is the only way in -- the way a
     * person pulls a chair out from a table.
     */
    @Nullable
    private static BlockPos approachTile(NavGraph graph, BlockPos chair, Direction front) {
        for (Direction side : new Direction[] {
                front, front.getClockWise(), front.getCounterClockWise(), front.getOpposite()}) {
            BlockPos tile = chair.relative(side);
            NavNode node = graph.nodeAt(tile.getX(), tile.getY(), tile.getZ());
            if (node != null && node.isRoutable()) return tile;
        }
        return null;
    }

    /** Somebody (villager or player) is sitting on this chair right now. */
    public static boolean isOccupied(ServerLevel level, BlockPos chair) {
        for (ChairSeatEntity seat : level.getEntitiesOfClass(
                ChairSeatEntity.class, new AABB(chair).inflate(0.15))) {
            if (seat.isVehicle()) return true;
        }
        return false;
    }

    /** Nobody is sitting here and nobody else is on the way. */
    public static boolean isFreeFor(ServerLevel level, BlockPos chair, UUID who) {
        if (isOccupied(level, chair)) return false;
        Claim claim = claims(level).get(chair);
        return claim == null || claim.owner().equals(who)
                || VillagerPause.stale(claim.owner(), claim.refreshedAt(), CLAIM_TIMEOUT_TICKS, level.getGameTime());
    }

    /** Take or refresh a claim. Call every tick the chair is still wanted. */
    public static void claim(ServerLevel level, BlockPos chair, UUID who) {
        claims(level).put(chair.immutable(), new Claim(who, level.getGameTime()));
    }

    /** Drop {@code who}'s claim on the chair, if it holds one. */
    public static void release(ServerLevel level, BlockPos chair, UUID who) {
        Map<BlockPos, Claim> claims = claims(level);
        Claim claim = claims.get(chair);
        if (claim != null && claim.owner().equals(who)) claims.remove(chair);
    }

    private static Map<BlockPos, Claim> claims(ServerLevel level) {
        Map<BlockPos, Claim> claims = CLAIMS.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        // Expired claims are otherwise only overwritten; prune now and then so
        // the map stays the size of the chairs actually in use.
        if (claims.size() > 64 && level.getGameTime() % 200 == 0) {
            long now = level.getGameTime();
            claims.values().removeIf(c -> VillagerPause.stale(c.owner(), c.refreshedAt(), CLAIM_TIMEOUT_TICKS, now));
        }
        return claims;
    }
}
