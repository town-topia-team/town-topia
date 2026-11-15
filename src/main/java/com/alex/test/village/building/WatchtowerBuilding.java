package com.alex.test.village.building;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Watchtower: a high platform where one archer keeps watch, the lookout. Made
 * by hanging the Watchtower token on the platform itself (on a fence post, a
 * wall or flat on the floor); there is no door or room, like an open-air stall.
 *
 * <p>The platform is the walkable floor within {@value #PLATFORM_RADIUS}
 * blocks of the token, at its height. It must stand at least
 * {@value #MIN_HEIGHT} blocks above the ground around the tower, measured a
 * few blocks out on eight sides. Each two blocks of height let the lookout see
 * one block farther, up to {@value #MAX_SPOT_BONUS}. A bell on the platform is
 * rung for big danger. Edge tiles with a drop and no railing are counted, so
 * the player can be warned that a lookout could be knocked off.
 */
public final class WatchtowerBuilding extends Building {

    public static final String SAVED_TYPE = "watchtower";
    /** How far from the token the platform's floor is looked for. */
    public static final int PLATFORM_RADIUS = 3;
    public static final int MIN_HEIGHT = 5;
    /** Blocks of height per extra block of sight. */
    private static final int HEIGHT_PER_SPOT_BLOCK = 2;
    public static final int MAX_SPOT_BONUS = 8;
    /** How far beyond the platform's edge the ground is sampled. */
    private static final int GROUND_SAMPLE_GAP = 3;
    /** The bell is looked for this far above the platform floor. */
    private static final int BELL_ABOVE = 3;

    /** Ground samples in chunks not loaded yet are measured again this often. */
    private static final int REMEASURE_TICKS = 200;

    private int heightAboveGround;
    /** Some ground around was not loaded when the height was measured: it is measured again later. */
    private boolean heightIncomplete;
    private long nextRemeasureAt;
    @Nullable private BlockPos bell;
    private int unrailedEdges;
    /** Where the ground was measured from last time (top of the ground, feet level). */
    private final List<BlockPos> groundSamples = new ArrayList<>();
    /** Samples left out for standing as tall as the platform (their tops). */
    private final List<BlockPos> blockedSamples = new ArrayList<>();

    public WatchtowerBuilding(UUID itemFrameId, BlockPos anchor, BlockPos itemFramePos,
                              Direction facing, Set<BlockPos> platform) {
        super(itemFrameId, anchor, itemFramePos, facing, platform);
    }

    /**
     * The walkable graph nodes within {@value #PLATFORM_RADIUS} blocks of the
     * token, from two below it (a token flat on the floor or low on a post) to
     * one above (a token hung high on a wall). Ladder rungs are not floor: the
     * rungs in the hatch fit that box, and a lookout used to keep watch
     * hanging on the ladder, in the way of whoever climbed up next. A token on
     * the tower's outside wall could even make the ladder shaft the platform.
     */
    public static Set<BlockPos> platformTiles(@Nullable NavGraph graph, BlockPos framePos) {
        Set<BlockPos> tiles = new LinkedHashSet<>();
        if (graph == null) return tiles;
        for (NavNode node : graph.nodesInBox(framePos, PLATFORM_RADIUS, 2)) {
            if (!node.isRoutable() || node.isAvoidedSurface() || node.climb) continue;
            BlockPos pos = node.feetPos();
            if (Math.abs(pos.getX() - framePos.getX()) > PLATFORM_RADIUS
                    || Math.abs(pos.getZ() - framePos.getZ()) > PLATFORM_RADIUS) continue;
            int dy = pos.getY() - framePos.getY();
            if (dy < -2 || dy > 1) continue;
            tiles.add(pos.immutable());
        }
        return tiles;
    }

    @Override
    protected Set<BlockPos> scanRoomBlocks(ServerLevel level, NavGraph navGraph,
                                           Collection<BlockPos> rivalEntries) {
        return platformTiles(navGraph, itemFramePos);
    }

    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        bell = null;
        unrailedEdges = 0;
        heightAboveGround = 0;
        if (navMeshBlocks.isEmpty()) return false;
        heightAboveGround = measureHeight(level);
        bell = findBell(level);
        if (bell != null) functionalBlocks.add(bell);
        unrailedEdges = countUnrailedEdges(level);
        return true;
    }

    /** The platform floor's height: the floor tile nearest the token. */
    public int platformFeetY() {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos tile : navMeshBlocks) {
            double d = tile.distSqr(itemFramePos);
            if (d < bestSq) {
                bestSq = d;
                best = tile;
            }
        }
        return best != null ? best.getY() : itemFramePos.getY();
    }

    /**
     * Platform height over the ground around the tower: the middle of eight
     * samples taken {@value #GROUND_SAMPLE_GAP} blocks beyond the platform's
     * edge. A sample on something as tall as the platform (the tower itself,
     * a roof next to it) does not count; nothing left to measure is height 0.
     */
    private int measureHeight(ServerLevel level) {
        int platformY = platformFeetY();
        double cx = 0, cz = 0;
        for (BlockPos tile : navMeshBlocks) {
            cx += tile.getX() + 0.5D;
            cz += tile.getZ() + 0.5D;
        }
        cx /= navMeshBlocks.size();
        cz /= navMeshBlocks.size();
        double reach = 0;
        for (BlockPos tile : navMeshBlocks) {
            reach = Math.max(reach, Math.hypot(tile.getX() + 0.5D - cx, tile.getZ() + 0.5D - cz));
        }
        reach += GROUND_SAMPLE_GAP;
        List<Integer> grounds = new ArrayList<>();
        heightIncomplete = false;
        groundSamples.clear();
        blockedSamples.clear();
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI / 4.0D * i;
            int x = (int) Math.floor(cx + Math.cos(angle) * reach);
            int z = (int) Math.floor(cz + Math.sin(angle) * reach);
            if (!level.hasChunk(x >> 4, z >> 4)) {
                heightIncomplete = true;
                continue;
            }
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (ground >= platformY - 1) {
                blockedSamples.add(new BlockPos(x, ground, z));
                continue;
            }
            grounds.add(ground);
            groundSamples.add(new BlockPos(x, ground, z));
        }
        if (grounds.isEmpty()) return 0;
        grounds.sort(Integer::compare);
        return platformY - grounds.get(grounds.size() / 2);
    }

    @Nullable
    private BlockPos findBell(ServerLevel level) {
        int platformY = platformFeetY();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                itemFramePos.getX() - PLATFORM_RADIUS - 1, platformY - 1, itemFramePos.getZ() - PLATFORM_RADIUS - 1,
                itemFramePos.getX() + PLATFORM_RADIUS + 1, platformY + BELL_ABOVE,
                itemFramePos.getZ() + PLATFORM_RADIUS + 1)) {
            if (!level.isLoaded(pos) || !(level.getBlockState(pos).getBlock() instanceof BellBlock)) continue;
            double d = pos.distSqr(itemFramePos);
            if (d < bestSq) {
                bestSq = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** Floor tiles with a side open to a drop: no floor there and nothing to stop a fall. */
    private int countUnrailedEdges(ServerLevel level) {
        int count = 0;
        for (BlockPos tile : navMeshBlocks) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos side = tile.relative(dir);
                if (navMeshBlocks.contains(side) || navMeshBlocks.contains(side.above())
                        || navMeshBlocks.contains(side.below())) continue;
                if (!level.isLoaded(side)) continue;
                boolean railed = !level.getBlockState(side).getCollisionShape(level, side).isEmpty();
                boolean floored = !level.getBlockState(side.below()).getCollisionShape(level, side.below()).isEmpty();
                if (!railed && !floored) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    public int getHeightAboveGround() {
        return heightAboveGround;
    }

    /**
     * Measures the height again, at most every ten seconds, if some of the
     * ground around the tower was not loaded the last time. A tower restored
     * with the world before the chunks next to it were in was measured 0
     * high, counted as too low, and no lookout went up until a block near the
     * platform happened to change.
     */
    public void remeasureIfIncomplete(ServerLevel level) {
        if (!heightIncomplete || navMeshBlocks.isEmpty()) return;
        long now = level.getGameTime();
        if (now < nextRemeasureAt && nextRemeasureAt - now <= REMEASURE_TICKS) return;
        nextRemeasureAt = now + REMEASURE_TICKS;
        heightAboveGround = measureHeight(level);
    }

    public boolean isHighEnough() {
        return heightAboveGround >= MIN_HEIGHT;
    }

    /** Where the ground was measured from, for showing the player. */
    public List<BlockPos> getGroundSamples() {
        return List.copyOf(groundSamples);
    }

    /** Samples left out for being as tall as the platform, for showing the player. */
    public List<BlockPos> getBlockedSamples() {
        return List.copyOf(blockedSamples);
    }

    /**
     * No ground to measure from because what stands around is taller than
     * the platform: buildings as tall as the tower, all round it.
     */
    public boolean isBoxedIn() {
        if (!groundSamples.isEmpty() || blockedSamples.isEmpty() || heightIncomplete) return false;
        int platformY = platformFeetY();
        for (BlockPos sample : blockedSamples) {
            if (sample.getY() >= platformY + 2) return true;
        }
        return false;
    }

    /** The "platform" is level with the ground all round: the token hangs down at ground level. */
    public boolean isAtGroundLevel() {
        return groundSamples.isEmpty() && !blockedSamples.isEmpty() && !heightIncomplete && !isBoxedIn();
    }

    /**
     * Floor a villager could stand on high up near {@code framePos}: within
     * {@value #PLATFORM_RADIUS} + 1 blocks sideways, at least
     * {@value #MIN_HEIGHT} blocks above the token. Found when the token hangs
     * at the foot of a tower whose real floor is up above it.
     */
    public static List<BlockPos> highFloorAbove(@Nullable NavGraph graph, BlockPos framePos) {
        return highFloorAbove(graph, framePos, MIN_HEIGHT);
    }

    /**
     * As above, for floor at least {@code minAbove} blocks above the token:
     * a token on a lower floor of a tower, {@code minAbove} being what it is
     * short of the height.
     */
    public static List<BlockPos> highFloorAbove(@Nullable NavGraph graph, BlockPos framePos, int minAbove) {
        List<BlockPos> floor = new ArrayList<>();
        if (graph == null) return floor;
        int reach = PLATFORM_RADIUS + 1;
        int lowest = Math.max(2, minAbove);
        for (NavNode node : graph.nodesInBox(framePos.above(22), reach, 20)) {
            if (!node.isRoutable() || node.isAvoidedSurface() || node.climb) continue;
            BlockPos pos = node.feetPos();
            if (Math.abs(pos.getX() - framePos.getX()) > reach || Math.abs(pos.getZ() - framePos.getZ()) > reach) continue;
            if (pos.getY() - framePos.getY() < lowest) continue;
            floor.add(pos.immutable());
        }
        return floor;
    }

    /** Extra blocks of sight from up here: one per two blocks of height, up to {@value #MAX_SPOT_BONUS}. */
    public int spotBonus() {
        return Math.min(MAX_SPOT_BONUS, Math.max(0, heightAboveGround) / HEIGHT_PER_SPOT_BLOCK);
    }

    @Nullable
    public BlockPos getBell() {
        return bell;
    }

    public int getUnrailedEdges() {
        return unrailedEdges;
    }

    /** The platform floor, where the lookout stands; empty while the platform is gone. */
    public List<BlockPos> watchSpots() {
        return List.copyOf(navMeshBlocks);
    }

    /** Whether {@code pos} (an entity's block position) is on the platform. */
    public boolean isOnPlatform(BlockPos pos) {
        return navMeshBlocks.contains(pos) || navMeshBlocks.contains(pos.below())
                || navMeshBlocks.contains(pos.above());
    }

    /** Changes whenever what the player would be told about the tower changes. */
    public String summaryKey() {
        return heightAboveGround + "/" + (bell != null) + "/" + unrailedEdges;
    }
}
