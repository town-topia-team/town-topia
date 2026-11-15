package com.alex.test.village.building;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Merchant stall, in one of two shapes.
 *
 * <ul>
 *   <li><b>Room</b>: a walled room with a barrel counter the tradesman sells
 *       from. Created like every room building -- Stall token in a frame beside
 *       a door. The visiting merchant waits at the door.</li>
 *   <li><b>Open-air</b>: like a guard post, no room at all. A Stall token hung
 *       anywhere without a sealed room behind a door marks the
 *       {@value #OPEN_AREA_SIZE}x{@value #OPEN_AREA_SIZE} square in front of it
 *       (or around it, for a token lying flat), and the visiting merchant
 *       strolls that square. A barrel in the square is used as the counter;
 *       without one the tradesman sells from the middle tile.</li>
 * </ul>
 *
 * <p>{@code functionalBlocks} holds the barrels. For an open-air stall
 * {@code doorPos} is the block the token hangs on: there is no door, and
 * breaking that block drops the frame, which is what ends the stall.
 */
public final class MerchantStallBuilding extends Building {

    public static final int REQUIRED_BARRELS = 1;
    /** Saved-building type of the open-air shape; the room shape saves as {@code "stall"}. */
    public static final String OPEN_AIR_SAVED_TYPE = "open_stall";
    /** Side length of the square an open-air stall covers. */
    public static final int OPEN_AREA_SIZE = 5;
    /** Walkable tiles an open-air stall needs for a merchant to stroll it. */
    public static final int MIN_OPEN_TILES = 6;

    private final boolean openAir;
    @Nullable private BlockPos villageOrigin;

    public MerchantStallBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                                 Direction interiorDirection, Set<BlockPos> initialNavMeshBlocks) {
        this(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks, false);
    }

    private MerchantStallBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                                  Direction interiorDirection, Set<BlockPos> initialNavMeshBlocks,
                                  boolean openAir) {
        super(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks);
        this.openAir = openAir;
    }

    /**
     * An open-air stall.
     *
     * @param anchor the block the token hangs on
     * @param facing the way the token faces, out of {@code anchor}
     */
    public static MerchantStallBuilding openAir(UUID itemFrameId, BlockPos anchor,
                                                BlockPos itemFramePos, Direction facing,
                                                Set<BlockPos> tiles) {
        return new MerchantStallBuilding(itemFrameId, anchor, itemFramePos, facing, tiles, true);
    }

    /** Village-owned stalls keep only tiles that a visitor can actually reach. */
    public static MerchantStallBuilding openAir(UUID itemFrameId, BlockPos anchor,
                                                BlockPos itemFramePos, Direction facing,
                                                Set<BlockPos> tiles, NavGraph graph,
                                                BlockPos villageOrigin) {
        MerchantStallBuilding stall = openAir(itemFrameId, anchor, itemFramePos, facing,
                BuildingAccess.connectedStallTiles(graph, villageOrigin, tiles));
        stall.villageOrigin = villageOrigin.immutable();
        return stall;
    }

    public boolean isOpenAir() {
        return openAir;
    }

    // =========================================================================
    // Open-air geometry
    // =========================================================================

    /** Middle of the square: in front of a token on a wall, around a token lying flat. */
    public static BlockPos openAreaCenter(BlockPos framePos, Direction facing) {
        return facing.getAxis().isHorizontal()
                ? framePos.relative(facing, OPEN_AREA_SIZE / 2) : framePos;
    }

    /** Walkable nav-graph tiles in the square an open-air token at {@code framePos} marks. */
    public static Set<BlockPos> openAreaTiles(NavGraph graph, BlockPos framePos, Direction facing) {
        Set<BlockPos> tiles = new LinkedHashSet<>();
        if (graph == null) return tiles;
        BlockPos center = openAreaCenter(framePos, facing);
        int half = OPEN_AREA_SIZE / 2;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                BlockPos tile = standableTile(graph,
                        center.getX() + dx, framePos.getY(), center.getZ() + dz);
                if (tile != null) tiles.add(tile);
            }
        }
        return tiles;
    }

    /**
     * The floor tile of one column. A token hangs at head height or on an
     * awning as often as at the feet, so look down to the ground first, then
     * one block up for a raised floor.
     */
    @Nullable
    private static BlockPos standableTile(NavGraph graph, int x, int frameY, int z) {
        for (int y = frameY; y >= frameY - 3; y--) {
            NavNode node = graph.nodeAt(x, y, z);
            if (BuildingAccess.isUsableNode(node)) return node.feetPos();
        }
        NavNode above = graph.nodeAt(x, frameY + 1, z);
        return BuildingAccess.isUsableNode(above) ? above.feetPos() : null;
    }

    /** Whether {@code pos} stands inside this open-air stall's square. */
    public boolean isInOpenArea(BlockPos pos) {
        if (!openAir) return false;
        BlockPos center = openAreaCenter(itemFramePos, interiorDirection);
        int half = OPEN_AREA_SIZE / 2;
        return Math.abs(pos.getX() - center.getX()) <= half
                && Math.abs(pos.getZ() - center.getZ()) <= half
                && pos.getY() >= minY - 1 && pos.getY() <= maxY + 1;
    }

    /** The walkable tile nearest the middle of an open-air stall's square. */
    @Nullable
    public BlockPos centerTile() {
        BlockPos center = openAreaCenter(itemFramePos, interiorDirection);
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (BlockPos tile : navMeshBlocks) {
            int dx = tile.getX() - center.getX();
            int dz = tile.getZ() - center.getZ();
            int dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = tile;
            }
        }
        return best;
    }

    // =========================================================================
    // Scanning
    // =========================================================================

    @Override
    protected Set<BlockPos> scanRoomBlocks(ServerLevel level, NavGraph navGraph,
                                           Collection<BlockPos> rivalEntries) {
        if (!openAir) return super.scanRoomBlocks(level, navGraph, rivalEntries);
        Set<BlockPos> tiles = openAreaTiles(navGraph, itemFramePos, interiorDirection);
        if (villageOrigin != null) {
            tiles = BuildingAccess.connectedStallTiles(navGraph, villageOrigin, tiles);
        }
        return tiles.size() >= MIN_OPEN_TILES ? tiles : Set.of();
    }

    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 2; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isFunctionalBlockAccessibleFromRoom(pos)) continue;
                    if (level.getBlockState(pos).is(Blocks.BARREL)) {
                        functionalBlocks.add(pos);
                    }
                }
            }
        }
        return functionalBlocks.size() >= REQUIRED_BARRELS;
    }

    /**
     * The counter the tradesman trades over: a barrel when there is one. An
     * open-air stall does not need one, so it falls back to its middle tile.
     */
    @Nullable
    public BlockPos getCounterPos() {
        if (!functionalBlocks.isEmpty()) return functionalBlocks.iterator().next();
        return openAir ? centerTile() : null;
    }
}
