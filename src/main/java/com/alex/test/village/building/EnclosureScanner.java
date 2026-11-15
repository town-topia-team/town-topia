package com.alex.test.village.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.alex.test.village.chat.RegistrationFailure;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Discovers the ground tiles inside a fence-bounded, open-air enclosure.
 *
 * <p>This is the outdoor sibling of {@link RoomScanner}. A room is bounded by
 * walls and proven interior by its ceiling; an enclosure has no ceiling, so the
 * proof is <em>closure</em>: a BFS across ground tiles that stops at fences,
 * walls, gates and solid barriers must terminate before {@link #MAX_TILES}. If
 * it spills past that, the pen leaks somewhere and the scan fails, which is
 * exactly the feedback a player with a hole in their fence should get.
 *
 * <h3>Tile rules</h3>
 * A tile is part of the enclosure when a villager could stand on it: solid
 * ground below, two passable blocks above. Fences/walls/gates at feet level are
 * boundaries. Water is treated as <em>traversable but not a tile</em> — an
 * irrigation channel inside a farm doesn't split the plot into two enclosures,
 * but nobody is asked to stand in it.
 */
public final class EnclosureScanner {

    /** Above this many tiles the pen is considered not closed. */
    public static final int MAX_TILES = 512;

    /** A bounded continuation can prove that a closed pen is merely oversized. */
    private static final int MAX_DIAGNOSTIC_TILES = 1024;

    /** Vertical drift tolerated between neighbouring tiles (gentle terrain). */
    private static final int MAX_STEP = 1;

    private EnclosureScanner() {}

    /**
     * {@code water} holds the feet-level water spots crossed inside the fence
     * (a pond, a channel): never tiles, but animals swim there.
     */
    public record ScanResult(@Nullable Set<BlockPos> tiles, @Nullable RegistrationFailure failure,
                             Set<BlockPos> water) {
        public ScanResult(@Nullable Set<BlockPos> tiles, @Nullable RegistrationFailure failure) {
            this(tiles, failure, Set.of());
        }

        public boolean found() { return tiles != null && !tiles.isEmpty(); }
    }

    /**
     * Scan the enclosure entered by stepping from {@code gatePos} in
     * {@code interiorDirection}.
     *
     * @param gatePos           position of the fence gate (feet level)
     * @param interiorDirection direction pointing from the gate into the pen
     * @return feet-level tile positions inside the enclosure, or {@code null}
     *         if the entry is invalid or the pen is not closed
     */
    @Nullable
    public static Set<BlockPos> scan(ServerLevel level, BlockPos gatePos,
                                     Direction interiorDirection) {
        return scanDetailed(level, gatePos, interiorDirection).tiles();
    }

    public static ScanResult scanDetailed(ServerLevel level, BlockPos gatePos,
                                          Direction interiorDirection) {
        BlockPos start = snapToGround(level, gatePos.relative(interiorDirection));
        if (start == null) return new ScanResult(null, RegistrationFailure.RANCH_BLOCKED_ENTRANCE);

        Set<BlockPos> tiles = new LinkedHashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> water = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();

        visited.add(start);
        queue.add(start);
        if (isStandable(level, start)) tiles.add(start);
        else if (isTraversableWater(level, start)) water.add(start);
        else {
            return new ScanResult(null, RegistrationFailure.RANCH_BLOCKED_ENTRANCE);
        }

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();

            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos flat = cur.relative(dir);
                BlockPos next = snapToGround(level, flat);
                BlockPos candidate = next != null ? next : flat;
                if (!visited.add(candidate)) continue;
                if (isBoundary(level, candidate)) continue;

                if (next != null && isStandable(level, next)) {
                    tiles.add(next);
                    queue.add(next);
                    if (tiles.size() > MAX_DIAGNOSTIC_TILES) {
                        return new ScanResult(null, RegistrationFailure.RANCH_OPEN_OR_TOO_LARGE);
                    }
                } else if (isTraversableWater(level, candidate)) {
                    water.add(candidate);
                    queue.add(candidate);
                    // Water never counted toward the limit, so a pen open onto
                    // a lake or the sea walked all of it, loading chunks as it went.
                    if (water.size() > MAX_DIAGNOSTIC_TILES) {
                        return new ScanResult(null, RegistrationFailure.RANCH_OPEN_OR_TOO_LARGE);
                    }
                }
            }
        }
        if (tiles.isEmpty()) {
            return new ScanResult(null, RegistrationFailure.RANCH_BLOCKED_ENTRANCE);
        }
        if (tiles.size() > MAX_TILES) {
            return new ScanResult(null, RegistrationFailure.RANCH_TOO_LARGE);
        }
        return new ScanResult(tiles, null, water);
    }

    // =========================================================================
    // Tile classification
    // =========================================================================

    /** Finds the feet level at or within {@link #MAX_STEP} of {@code feet}. */
    @Nullable
    private static BlockPos snapToGround(ServerLevel level, BlockPos feet) {
        for (int dy = MAX_STEP; dy >= -MAX_STEP; dy--) {
            BlockPos candidate = feet.above(dy);
            if (isStandable(level, candidate) || isTraversableWater(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Solid ground below, two clear blocks at feet/head.
     *
     * <p>Fence-like ground is explicitly rejected: a fence's collision box IS
     * solid, so without this check the BFS would climb on top of the pen wall
     * and march straight over it into open terrain.
     */
    private static boolean isStandable(ServerLevel level, BlockPos feet) {
        BlockPos ground = feet.below();
        BlockState below = level.getBlockState(ground);
        if (!below.getFluidState().isEmpty()) return false;
        if (isFenceLike(below)) return false;
        boolean solidBelow = below.blocksMotion()
                || !below.getCollisionShape(level, ground).isEmpty();
        return solidBelow && isClear(level, feet) && isClear(level, feet.above());
    }

    /** Water at feet level with air above — traversed but never a tile. */
    private static boolean isTraversableWater(ServerLevel level, BlockPos feet) {
        return !level.getFluidState(feet).isEmpty() && isClear(level, feet.above());
    }

    /** Fence, wall or gate at feet or head level marks the pen edge. */
    private static boolean isBoundary(ServerLevel level, BlockPos feet) {
        return isFenceLike(level.getBlockState(feet))
                || isFenceLike(level.getBlockState(feet.above()));
    }

    private static boolean isFenceLike(BlockState state) {
        return state.getBlock() instanceof FenceBlock
                || state.getBlock() instanceof WallBlock
                || state.getBlock() instanceof FenceGateBlock;
    }

    /** Passable, non-fluid space (crops and other no-collision plants count). */
    private static boolean isClear(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        if (isFenceLike(state)) return false;
        return state.getCollisionShape(level, pos).isEmpty();
    }
}
