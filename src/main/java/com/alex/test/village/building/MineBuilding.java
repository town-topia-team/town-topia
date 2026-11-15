package com.alex.test.village.building;

import com.alex.test.village.NavGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Mine: a marked entrance for a miner's shaft, not a building.
 *
 * <p>Every other building in the mod is a room you walk into. A mine used to be
 * one too — a walled room whose back wall happened to be stone — and it was the
 * one building nobody could place correctly. The room had to be built against
 * rock, the rock had to stay thick enough behind the wall to be worth digging,
 * and cutting the doorway through into the shaft ate the very wall that made
 * the room a mine. A player who did all of that and got it slightly wrong got a
 * valid Mine with nothing in it, and a miner that correctly reported no work
 * and stood still.
 *
 * <p>Hang the Mine token on any solid block above the planned entrance. The
 * miner opens a two-block-high passage beneath the marked block, leaving the
 * frame and its roof intact.
 *
 * <h3>The geometry</h3>
 * <pre>
 *   [frame] [anchor] ####        anchor  the block the token hangs on
 *     air   [head]  ####        mouth   two blocks below the anchor
 *      P    [mouth] ####        shaft   runs away from the frame
 *     ###   [floor] ####        P       where a miner stands to start
 * </pre>
 *
 * <p>The miner stands on {@code P} and cuts the mouth and the block above it.
 * The anchor itself is never cut: breaking it would drop the item frame and
 * delete the mine mid-shift.
 *
 * <p>The two blocks above the anchor are kept intact as the marked roof.
 */
public final class MineBuilding extends Building {

    /** Blocks above the anchor that are kept intact as the marked roof. */
    public static final int REQUIRED_DEPTH = 2;

    public MineBuilding(UUID itemFrameId, BlockPos anchorPos, BlockPos itemFramePos,
                        Direction shaftDirection, Set<BlockPos> initialApproach) {
        super(itemFrameId, anchorPos, itemFramePos, shaftDirection, initialApproach);
    }

    // =========================================================================
    // Geometry
    // =========================================================================

    /** The block the token hangs on. Never dug. */
    public BlockPos anchor() {
        return getDoorPos();
    }

    /** The first feet-level block of the shaft, two blocks below the token. */
    public BlockPos mouth() {
        return getDoorPos().below(2);
    }

    /** Which way the shaft runs: into the rock, away from the frame. */
    public Direction shaftDirection() {
        return getInteriorDirection();
    }

    /**
     * True for the anchor and the two blocks above it, the column that has to
     * stay standing for this to go on being a mine.
     */
    public boolean isAnchorColumn(BlockPos pos) {
        BlockPos anchor = anchor();
        return pos.getX() == anchor.getX() && pos.getZ() == anchor.getZ()
                && pos.getY() >= anchor.getY()
                && pos.getY() <= anchor.getY() + REQUIRED_DEPTH;
    }

    // =========================================================================
    // Scanning
    // =========================================================================

    /**
     * The one tile in front of the mouth, on the opposite side from the shaft.
     *
     * <p>Deliberately one tile and not a radius. {@code navMeshBlocks} is what
     * {@link Building#isNearRoom} builds its protected AABB from, and that AABB
     * is inflated by a block in every direction before the miner consults it —
     * so a tile beside the mouth would push the protected box one block into
     * the shaft and the miner would refuse to dig the first cell of its own
     * tunnel. Even this one tile's box reaches the mouth and the block above
     * it, so the miner leaves its own mine's box out for those two blocks
     * (MinerActivity.isProtected); the anchor and roof stay protected.
     *
     * <p>A matching navigation node is preferred so the recorded approach
     * reflects the terrain the miner will actually use, but it is not a mine
     * registration requirement. When neither candidate is currently in the
     * graph, the level tile is retained as a stable fallback. Pathfinding can
     * be rebuilt later, and the miner independently searches for somewhere in
     * reach of each block before trying to dig it.
     */
    public static Set<BlockPos> approachTiles(NavGraph graph, BlockPos mouth,
                                              Direction shaftDirection) {
        Set<BlockPos> tiles = new LinkedHashSet<>();
        BlockPos front = mouth.relative(shaftDirection.getOpposite());
        // Level with the mouth if the ground outside is as high as the passage,
        // one down if the outcrop stands a block proud of it. Both are a single
        // step for the nav graph, and which one it is depends on the terrain
        // the player found, not on anything this class can decide.
        if (graph != null) {
            for (BlockPos candidate : new BlockPos[] { front, front.below() }) {
                com.alex.test.village.NavNode node = graph.nodeAt(
                        candidate.getX(), candidate.getY(), candidate.getZ());
                if (node != null && node.isRoutable()) {
                    tiles.add(candidate);
                    return tiles;
                }
            }
        }
        tiles.add(front);
        return tiles;
    }

    @Override
    protected Set<BlockPos> scanRoomBlocks(ServerLevel level, NavGraph navGraph,
                                           Collection<BlockPos> rivalEntries) {
        // No room to divide, so nothing to divide it with: the rival list is
        // accepted for the shared signature and ignored.
        return approachTiles(navGraph, mouth(), shaftDirection());
    }

    /**
     * The marked roof column, which is kept intact and reported as mine
     * infrastructure. The material is unrestricted: miners can dig through
     * any block tagged as pickaxe-mineable.
     *
     * <p>Populating {@code functionalBlocks} with it rather than returning a
     * bare boolean is what makes the mine legible everywhere else: the state
     * dump, the token-feedback message and the builder's revalidation all
     * read functional blocks without making the roof material a registration
     * requirement.
     */
    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        BlockPos anchor = anchor();
        functionalBlocks.add(anchor);
        for (int dy = 1; dy <= REQUIRED_DEPTH; dy++) {
            BlockPos above = anchor.above(dy);
            functionalBlocks.add(above);
        }
        return true;
    }

    /** Whether the miner can dig through this block with a pickaxe. */
    public static boolean isMineableStone(BlockState state) {
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE);
    }
}
