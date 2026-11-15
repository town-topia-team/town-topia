package com.alex.test.village.knowledge;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.lumberjack.data.TreeScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * One-shot world scanner that populates a {@link KnowledgeBase} at NavGraph
 * build time.
 *
 * <h3>Contract</h3>
 * This is the <em>only</em> place where the NavGraph and the KnowledgeBase
 * interact directly.  After {@link #populate} returns, all further updates
 * to the KnowledgeBase must come through Minecraft events wired in
 * {@link VillageManager}.
 *
 * <h3>Adding new categories</h3>
 * Add a private {@code populate<Category>} method and call it from
 * {@link #populate}.  Use the NavGraph bounding box for spatial scope.
 */
public final class KnowledgeScanner {

    private KnowledgeScanner() {}

    /**
     * Scan the live world within the NavGraph's bounding region and fill
     * {@code kb} with initial knowledge for all supported categories.
     *
     * @param level The server-side world to read from.
     * @param graph The freshly built NavGraph that defines the village area.
     * @param kb    The KnowledgeBase to populate (caller must have called
     *              {@link KnowledgeBase#clearAll()} or be using a fresh instance).
     */
    public static void populate(ServerLevel level, NavGraph graph, KnowledgeBase kb) {
        populate(level, graph, kb, pos -> false);
    }

    public static void populate(
            ServerLevel level, NavGraph graph, KnowledgeBase kb,
            Predicate<BlockPos> protectedTreePosition) {
        populateTrees(level, graph, kb, protectedTreePosition);
        populateCrops(level, graph, kb);
        populateSugarcane(level, graph, kb);
        populateRooms(level, graph, kb);
    }

    /** The same population, run a slice at a time with {@link Scan#advance}. */
    public static Scan start(ServerLevel level, NavGraph graph, KnowledgeBase kb,
                             Predicate<BlockPos> protectedTreePosition) {
        return new Scan(level, graph, kb, protectedTreePosition);
    }

    /**
     * {@link #populate} split into slices, for a village that wakes up while a
     * player is near: the whole scan at once is a stall of up to a couple of
     * seconds. Trees, then ripe crops, then sugarcane, then item frames, each
     * resuming where the last slice stopped. Trees are added together once all
     * are found, so a lumberjack never chooses from half a forest.
     */
    public static final class Scan {
        /** Work between clock checks: single nodes and columns are too little to time. */
        private static final int NODES_PER_CHECK = 1024;
        private static final int COLUMNS_PER_CHECK = 64;

        private final ServerLevel level;
        private final NavGraph graph;
        private final KnowledgeBase kb;
        private final TreeScanner.Sweep trees;
        private final SectionPalettes caneSections =
                new SectionPalettes(state -> state.is(Blocks.SUGAR_CANE));
        private Iterator<NavNode> crops;
        private int caneX, caneZ;
        private int phase;

        private Scan(ServerLevel level, NavGraph graph, KnowledgeBase kb,
                     Predicate<BlockPos> protectedTreePosition) {
            this.level = level;
            this.graph = graph;
            this.kb = kb;
            this.trees = TreeScanner.startSweep(level, graph, protectedTreePosition);
        }

        /** Scans until done or {@code budgetNanos} is spent; true when done. */
        public boolean advance(long budgetNanos) {
            long startedAt = System.nanoTime();
            if (phase == 0) {
                if (!trees.advance(budgetNanos)) return false;
                for (BlockPos base : trees.result()) kb.add(KnowledgeBase.Category.TREE, base);
                crops = graph.allNodes().iterator();
                phase = 1;
            }
            if (phase == 1) {
                int sinceCheck = 0;
                while (crops.hasNext()) {
                    addIfRipeCrop(level, crops.next(), kb);
                    if (++sinceCheck >= NODES_PER_CHECK) {
                        sinceCheck = 0;
                        if (System.nanoTime() - startedAt >= budgetNanos) return false;
                    }
                }
                caneX = graph.getBoundsMinX();
                caneZ = graph.getBoundsMinZ();
                phase = 2;
            }
            if (phase == 2) {
                int yLow = graph.getBoundsMinY() - 2;
                int yHigh = graph.getBoundsMaxY() + 20;
                int sinceCheck = 0;
                while (caneX <= graph.getBoundsMaxX()) {
                    scanCaneColumn(level, caneX, caneZ, yLow, yHigh, caneSections, kb);
                    if (++caneZ > graph.getBoundsMaxZ()) {
                        caneZ = graph.getBoundsMinZ();
                        caneX++;
                    }
                    if (++sinceCheck >= COLUMNS_PER_CHECK) {
                        sinceCheck = 0;
                        if (System.nanoTime() - startedAt >= budgetNanos) return false;
                    }
                }
                phase = 3;
            }
            if (phase == 3) {
                populateRooms(level, graph, kb);
                phase = 4;
            }
            return true;
        }
    }

    // =========================================================================
    // Trees
    // =========================================================================

    /**
     * Runs {@link TreeScanner#scan} and registers each result as
     * {@link KnowledgeBase.Category#TREE}.
     */
    private static void populateTrees(
            ServerLevel level, NavGraph graph, KnowledgeBase kb,
            Predicate<BlockPos> protectedTreePosition) {
        List<BlockPos> trees = TreeScanner.scan(level, graph, protectedTreePosition);
        for (BlockPos base : trees) kb.add(KnowledgeBase.Category.TREE, base);
    }

    // =========================================================================
    // Crops
    // =========================================================================

    /**
     * Walks all NavGraph surface nodes.  Any node whose surface block is a
     * {@link CropBlock} at maximum age is registered as
     * {@link KnowledgeBase.Category#CROP}.
     *
     * <p>Immature crops are ignored — we only care about harvestable ones.
     * Event-driven updates ({@code BlockEvent.CropGrowEvent.Post}) will add
     * them when they ripen.
     */
    private static void populateCrops(ServerLevel level, NavGraph graph, KnowledgeBase kb) {
        for (NavNode node : graph.allNodes()) addIfRipeCrop(level, node, kb);
    }

    private static void addIfRipeCrop(ServerLevel level, NavNode node, KnowledgeBase kb) {
        if (!(node.surfaceBlock instanceof CropBlock)) return;
        BlockPos pos = new BlockPos(node.x, node.surfaceY, node.z);
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof CropBlock crop && crop.getAge(state) >= crop.getMaxAge()) {
            kb.add(KnowledgeBase.Category.CROP, pos);
        }
    }

    // =========================================================================
    // Sugarcane
    // =========================================================================

    /**
     * Scans every XZ column in the graph bounding box for base-level sugarcane
     * (a cane block whose block immediately below is NOT also cane). Sections
     * whose palette has no cane are skipped without reading their blocks.
     */
    private static void populateSugarcane(ServerLevel level, NavGraph graph, KnowledgeBase kb) {
        int yLow  = graph.getBoundsMinY() - 2;
        int yHigh = graph.getBoundsMaxY() + 20;
        SectionPalettes caneSections = new SectionPalettes(state -> state.is(Blocks.SUGAR_CANE));

        for (int x = graph.getBoundsMinX(); x <= graph.getBoundsMaxX(); x++) {
            for (int z = graph.getBoundsMinZ(); z <= graph.getBoundsMaxZ(); z++) {
                scanCaneColumn(level, x, z, yLow, yHigh, caneSections, kb);
            }
        }
    }

    private static void scanCaneColumn(ServerLevel level, int x, int z, int yLow, int yHigh,
                                       SectionPalettes caneSections, KnowledgeBase kb) {
        LevelChunk chunk = level.getChunk(
                SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        for (int y = yLow; y <= yHigh; y++) {
            if (!caneSections.mayHold(chunk, y)) {
                y |= 15; // on to the next section
                continue;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos).is(Blocks.SUGAR_CANE)) continue;
            // Only register the base of each cane plant
            if (!level.getBlockState(pos.below()).is(Blocks.SUGAR_CANE)) {
                kb.add(KnowledgeBase.Category.SUGARCANE, pos);
            }
            break; // one cane stack per column, skip upward
        }
    }

    // =========================================================================
    // Rooms (item frames)
    // =========================================================================

    /**
     * Finds all {@link ItemFrame} entities inside the graph's bounding box.
     * Each frame is assumed to mark the presence of a room; its block position
     * is registered as {@link KnowledgeBase.Category#ROOM}.
     */
    private static void populateRooms(ServerLevel level, NavGraph graph, KnowledgeBase kb) {
        AABB box = new AABB(
                graph.getBoundsMinX(), graph.getBoundsMinY() - 8, graph.getBoundsMinZ(),
                graph.getBoundsMaxX(), graph.getBoundsMaxY() + 32, graph.getBoundsMaxZ());

        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, box);
        for (ItemFrame frame : frames) {
            kb.add(KnowledgeBase.Category.ROOM, frame.blockPosition());
        }
    }
}
