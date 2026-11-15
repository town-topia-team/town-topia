package com.alex.test.village.villagers.lumberjack.data;

import com.alex.test.village.NavGraph;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.knowledge.SectionPalettes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;

/**
 * Scans a {@link NavGraph}'s bounding region for fully grown trees and returns
 * the base-log position of each one.
 *
 * <h3>Detection strategy</h3>
 * Every XZ column inside the graph's chunk-aligned bounding box is scanned
 * bottom-up looking for an upright log block whose block directly below is solid
 * ground and is not itself another log (distinguishing the base from a mid-trunk
 * position). Logs laid on their side — farm borders, fences — are never bases.
 * Once a base log is found, {@link TreeStructure#inspect} performs one bounded
 * tree-shape inspection. Diagonal logs are included, leaves cannot bridge the
 * search into another wooden component, and protected positions reject the
 * candidate.
 *
 * <h3>Deduplication</h3>
 * All log positions in a component are added to a {@code seen} set so
 * neighbouring columns don't trigger a second flood-fill for the same tree.
 */
public final class TreeScanner {

    /**
     * How far above and below the nav graph's own vertical extent a tree may sit
     * and still be found. Applied symmetrically: the graph tracks only terrain
     * walkable from the village, so the ground a tree grows on can be well
     * outside it in either direction.
     */
    private static final int GRAPH_SCAN_MARGIN = 40;

    private TreeScanner() {}

    /**
     * Scan every XZ column in the graph's bounding box for trees.
     *
     * @return List of base-log {@link BlockPos} values — one per valid tree.
     */
    public static List<BlockPos> scan(ServerLevel level, NavGraph graph) {
        return scan(level, graph, pos -> false);
    }

    public static List<BlockPos> scan(
            ServerLevel level, NavGraph graph, Predicate<BlockPos> protectedPosition) {
        if (graph == null || graph.isEmpty()) return Collections.emptyList();
        Sweep sweep = new Sweep(level, graph, protectedPosition);
        sweep.advance(Long.MAX_VALUE);
        return sweep.result();
    }

    /** The same scan, started now and run a slice at a time with {@link Sweep#advance}. */
    public static Sweep startSweep(ServerLevel level, NavGraph graph, Predicate<BlockPos> protectedPosition) {
        return new Sweep(level, graph, protectedPosition);
    }

    /** A tree scan that can stop between columns and carry on later. */
    public static final class Sweep {
        /** Columns between clock checks: one column is far too little work to time. */
        private static final int COLUMNS_PER_CHECK = 64;

        private final ServerLevel level;
        private final Predicate<BlockPos> protectedPosition;
        private final Set<Long> seen = new HashSet<>();
        private final List<BlockPos> result = new ArrayList<>();
        private final SectionPalettes logSections = new SectionPalettes(state -> state.is(BlockTags.LOGS));
        private final int minZ, maxX, maxZ, yLow, graphScanCeiling, levelMaxY;
        private int nextX, nextZ;

        private Sweep(ServerLevel level, NavGraph graph, Predicate<BlockPos> protectedPosition) {
            this.level = level;
            this.protectedPosition = protectedPosition;
            int levelMinY = level.getMinBuildHeight();
            levelMaxY = level.getMaxBuildHeight() - 1;
            // The nav graph only contains terrain connected to the village.  Its
            // highest node can therefore be far below the outdoor surface for an
            // underground town hall; a fixed offset from that node misses trees.
            // The floor needs the same allowance for the mirror-image case — a
            // village on a plateau or an upper floor with trees in the ravine,
            // quarry or courtyard below — so both ends share one margin instead of
            // the ceiling getting 40 blocks and the floor getting 5.
            yLow = Math.max(levelMinY + 1, graph.getBoundsMinY() - GRAPH_SCAN_MARGIN);
            graphScanCeiling = Math.min(levelMaxY - 1, graph.getBoundsMaxY() + GRAPH_SCAN_MARGIN);
            minZ = graph.getBoundsMinZ();
            maxX = graph.getBoundsMaxX();
            maxZ = graph.getBoundsMaxZ();
            nextX = graph.isEmpty() ? maxX + 1 : graph.getBoundsMinX();
            nextZ = minZ;
        }

        /** Scans columns until done or {@code budgetNanos} is spent; true when done. */
        public boolean advance(long budgetNanos) {
            long startedAt = System.nanoTime();
            int sinceCheck = 0;
            while (nextX <= maxX) {
                scanColumn(nextX, nextZ);
                if (++nextZ > maxZ) {
                    nextZ = minZ;
                    nextX++;
                }
                if (++sinceCheck >= COLUMNS_PER_CHECK) {
                    sinceCheck = 0;
                    if (System.nanoTime() - startedAt >= budgetNanos) return nextX > maxX;
                }
            }
            return true;
        }

        /** Base logs of the trees found so far. */
        public List<BlockPos> result() {
            return result;
        }

        private void scanColumn(int cx, int cz) {
            // MOTION_BLOCKING includes a tree canopy, so scanning up to this
            // column's top also includes its base log.  Keep the old local
            // ceiling as a floor to preserve the normal above-ground scan.
            int terrainCeiling = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz) - 1;
            int yHigh = Math.min(levelMaxY - 1, Math.max(graphScanCeiling, terrainCeiling));
            LevelChunk chunk = level.getChunk(
                    SectionPos.blockToSectionCoord(cx), SectionPos.blockToSectionCoord(cz));
            for (int cy = yLow; cy <= yHigh; cy++) {
                // A section with no log in its palette can't hold a base,
                // and every position in seen is a log, so it can't end the
                // column either: jump to the next section.
                if (!logSections.mayHold(chunk, cy)) {
                    cy |= 15;
                    continue;
                }
                BlockPos candidate = new BlockPos(cx, cy, cz);
                long key = candidate.asLong();
                if (seen.contains(key)) break; // already part of another tree

                BlockState state = level.getBlockState(candidate);
                if (!state.is(BlockTags.LOGS)) continue;
                if (!TreeStructure.isUpright(state)) continue; // laid on its side: a build

                BlockState below = level.getBlockState(candidate.below());
                if (!below.blocksMotion())    continue; // not solid ground
                if (below.is(BlockTags.LOGS)) continue; // mid-trunk, not base

                TreeStructure tree = TreeStructure.inspect(level, candidate, protectedPosition);
                if (tree != null) {
                    result.add(tree.base());
                    for (BlockPos log : tree.logs()) seen.add(log.asLong());
                }
                break; // one tree per column
            }
        }
    }

    /**
     * The foot of the tree a sapling at {@code near} grew into. With
     * {@code growfoot}, {@link GrowFoot}: the sapling's own column first, and
     * leaves are never ground. Without it, or when that finds none, the old
     * search: a 2-block XZ / -1..+4 Y window round {@code near}, from its
     * west/north corner, for an upright log whose block directly below is
     * solid and is not itself a log.
     */
    @Nullable
    public static BlockPos findBaseLogNear(ServerLevel level, BlockPos near) {
        if (WalkFeel.growFoot) {
            long foot = GrowFoot.find(new LevelTreeView(level, pos -> false), near.getX(), near.getY(), near.getZ());
            if (foot != TreeJudge.NO_POS) return BlockPos.of(foot);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 4; dy++) {
                    BlockPos check = near.offset(dx, dy, dz);
                    // Reading a block in an unloaded chunk would load it, right here on the tick.
                    if (!level.hasChunkAt(check)) continue;
                    BlockState state = level.getBlockState(check);
                    if (!state.is(BlockTags.LOGS) || !TreeStructure.isUpright(state)) continue;
                    BlockState below = level.getBlockState(check.below());
                    if (below.blocksMotion() && !below.is(BlockTags.LOGS)) return check;
                }
            }
        }
        return null;
    }
}
