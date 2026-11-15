package com.alex.test.village;

import com.alex.test.block.WoodenChairBlock;
import com.alex.test.block.ZalithPianoBenchBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/**
 * Sparse multi-level navigation graph built by BFS flood-fill from a seed.
 *
 * <p>The graph is <b>immutable</b> after {@link #build} and can safely be
 * passed to any thread for A* pathfinding.
 *
 * <h3>Node rules</h3>
 * A position becomes a node when the surface block is a normal path surface and
 * there is room above it for a {@value #ENTITY_HEIGHT}-tall entity standing on
 * the surface's real top ({@link NavNode#standY}): normally two clear blocks,
 * but a bottom-slab floor under a top-slab ceiling fits too.
 * Contiguous ladder blocks also become climb nodes at each rung.
 *
 * <h3>Edge rules</h3>
 * From a node with feetY=F we expand in 4 cardinal directions and accept a
 * neighbour if its surface Y falls within [F−2, F] (step down/flat/step up),
 * the lower of the two columns has head room for the step, and — for the fill
 * to spread there — the real rise is no more than {@value #MAX_STEP_UP}.
 * Edges are stored both ways; {@link NavNode#canStepTo} says which way a
 * route may take them.
 */
public final class NavGraph {

    public static final int DEFAULT_CHUNK_RADIUS = 9;

    /** "More than 1 block", per the water-shortcut spec — a straight step down is not a shortcut. */
    private static final int MIN_WATER_DROP = 2;
    /** Bound on how far the water scan looks below the shore — a deep ravine should not scan to bedrock. */
    private static final int MAX_WATER_SCAN = 64;

    /**
     * Height of every walker this graph serves; {@code DynamicVillages.NPC_HEIGHT}
     * sizes the entities from it. Kept under 1.9375 (two blocks less a carpet)
     * so a villager fits on a carpet beneath a two-block ceiling or lintel.
     */
    public static final double ENTITY_HEIGHT = 1.9;

    /**
     * The highest rise vanilla ground navigation will take in one move, which
     * is a jump. Mirrors {@code WalkNodeEvaluator}'s {@code max(1.125, maxUpStep)}.
     */
    public static final double MAX_STEP_UP = 1.125;
    /** Stuck spots E: failures of one step before it stays failed for the session. */
    private static final int  STICKY_STEP_FAILURES = 3;
    /** "The session": a week, well past any play session; the node is rebuilt long before. */
    private static final long STICKY_STEP_MILLIS = 7L * 24L * 60L * 60L * 1000L;

    /**
     * How far above or below a lookup position a node still counts as the same
     * floor. One block covers a step, a slab, or an entity half a block up a
     * stair. The next storey of a building is at least three blocks away: two
     * to stand in and one for the floor between them.
     */
    private static final int SAME_FLOOR_DY = 1;

    // Primary key → node, XZ-only secondary index, version tag.
    //
    // These are concurrent and mutable because the graph is edited in place.
    // Rebuilding it from scratch after every block change cost about a fifth of
    // a second of server thread for ninety thousand nodes -- and almost none of
    // that was reading blocks; it was allocating a hundred thousand objects and
    // filling three hash maps. Editing in place makes a block placement cost
    // what it should: the columns that changed.
    private final Map<Long, NavNode> nodes;
    private final Map<Long, NavNode> xzIndex;
    private final Map<Long, List<NavNode>> xzNodes;

    /** Bumped on every edit; callers use it to notice their cached answers are stale. */
    private volatile long version;

    /**
     * The bell this graph was built from. Every question about reaching the
     * village starts here: the island sweep from the tile at its key, the
     * token access flood from the routable tile nearest it.
     */
    private final BlockPos origin;

    /**
     * The layer above the tiles: cells, pieces, and the piece map that answers
     * "can I get there", "is this connected to the Town Hall" and "did the
     * edit cut something off" without walking every tile. Redone inside the
     * tick of every edit, so there is no sliced follow-up work after a patch.
     */
    private final NavCells cells = new NavCells(this);

    /**
     * Key of the node the full build started from -- the Town Hall door for a
     * village. Every node in the graph was reached from it, so the island
     * sweep starts there too. Only meaningful while {@link #hasRoot}.
     */
    private long rootKey;
    private boolean hasRoot;

    /** Set when islands were refused for being too much of the graph; see {@link #takeFullBuildRequest}. */
    private boolean fullBuildRequested;

    // Chunk-aligned XZ bounding box (for TreeScanner column iteration).
    private final int boundsMinX, boundsMaxX, boundsMinZ, boundsMaxZ;
    // feetY range across all nodes (for TreeScanner vertical window).
    private volatile int boundsMinY, boundsMaxY;

    private NavGraph(Map<Long, NavNode> nodes, Map<Long, NavNode> xzIndex,
                     Map<Long, List<NavNode>> xzNodes, long version, BlockPos origin,
                     int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
        this.nodes      = nodes;
        this.xzIndex    = xzIndex;
        this.xzNodes    = xzNodes;
        this.version    = version;
        this.origin     = origin.immutable();
        this.boundsMinX = minX; this.boundsMaxX = maxX;
        this.boundsMinZ = minZ; this.boundsMaxZ = maxZ;
        this.boundsMinY = minY; this.boundsMaxY = maxY;
    }

    // =========================================================================
    // Build
    // =========================================================================

    public static NavGraph build(Level level, BlockPos origin,
                                 int chunkRadius, long version) {
        int originCX = origin.getX() >> 4;
        int originCZ = origin.getZ() >> 4;
        int minX = (originCX - chunkRadius)      << 4;
        int maxX = ((originCX + chunkRadius + 1) << 4) - 1;
        int minZ = (originCZ - chunkRadius)      << 4;
        int maxZ = ((originCZ + chunkRadius + 1) << 4) - 1;

        Map<Long, NavNode> nodes = new LinkedHashMap<>(2048);
        Queue<NavNode>     queue = new ArrayDeque<>(512);

        NavNode seed = tryCreateSeed(level, origin);
        if (seed == null) {
            NavGraph empty = new NavGraph(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                    version, origin, minX, maxX, minZ, maxZ,
                    level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
            empty.cells.rebuildMap();
            return empty;
        }

        nodes.put(NavNode.key(seed.x, seed.feetY, seed.z), seed);
        queue.add(seed);

        final int   levelMinY = level.getMinBuildHeight();
        final int   levelMaxY = level.getMaxBuildHeight() - 1;

        floodFill(level, nodes, queue, minX, maxX, minZ, maxZ, levelMinY, levelMaxY);

        NavGraph graph = index(nodes, origin, version, minX, maxX, minZ, maxZ,
                levelMinY, levelMaxY);
        graph.setRoot(seed);
        // The pieces and the piece map in one go, beside the build, where the
        // labelling used to run: a fresh graph has nothing to answer from
        // until they exist.
        graph.cells.regroupAll(System.currentTimeMillis());
        graph.cells.rebuildMap();
        return graph;
    }

    private void setRoot(NavNode seed) {
        rootKey = NavNode.key(seed.x, seed.feetY, seed.z);
        hasRoot = true;
    }

    /**
     * A full build whose world reads, index construction and piece grouping
     * run in bounded tick slices.
     */
    public static final class BuildTask {
        private final BlockPos origin;
        private final int minX, maxX, minZ, maxZ, minY, maxY;
        private final long version;
        private final Map<Long, NavNode> nodes = new LinkedHashMap<>(2048);
        private final Queue<NavNode> queue = new ArrayDeque<>(512);
        private Iterator<NavNode> indexCursor;
        private NavGraph result;
        private boolean grouped;
        private int minFeetY = Integer.MAX_VALUE, maxFeetY = Integer.MIN_VALUE;
        private NavNode seed;

        private BuildTask(Level level, BlockPos origin, int chunkRadius, long version) {
            this.origin = origin;
            this.version = version;
            int cx = origin.getX() >> 4;
            int cz = origin.getZ() >> 4;
            minX = (cx - chunkRadius) << 4;
            maxX = ((cx + chunkRadius + 1) << 4) - 1;
            minZ = (cz - chunkRadius) << 4;
            maxZ = ((cz + chunkRadius + 1) << 4) - 1;
            minY = level.getMinBuildHeight();
            maxY = level.getMaxBuildHeight() - 1;
            seed = tryCreateSeed(level, origin);
            if (seed != null) {
                nodes.put(NavNode.key(seed.x, seed.feetY, seed.z), seed);
                queue.add(seed);
            }
        }

        /**
         * One slice: fills about {@code maxNodes} tiles, or indexes that many,
         * or groups about four times that many into pieces (grouping reads no
         * blocks, so it is cheaper per tile than the fill).
         *
         * @return the completed graph, or null while more ticks are needed
         */
        public NavGraph advance(Level level, int maxNodes) {
            if (grouped) return result;
            int remaining = Math.max(1, maxNodes);
            if (!queue.isEmpty()) {
                remaining -= floodFillInner(level, nodes, queue, minX, maxX, minZ, maxZ,
                        minY, maxY, remaining);
                if (!queue.isEmpty()) return null;
            }
            if (result == null) {
                result = new NavGraph(new java.util.concurrent.ConcurrentHashMap<>(Math.max(16, nodes.size() * 2)),
                        new java.util.concurrent.ConcurrentHashMap<>(Math.max(16, nodes.size() * 2)),
                        new java.util.concurrent.ConcurrentHashMap<>(Math.max(16, nodes.size() * 2)),
                        version, origin, minX, maxX, minZ, maxZ, minY, maxY);
                if (seed != null) result.setRoot(seed);
                indexCursor = nodes.values().iterator();
            }
            while (remaining-- > 0 && indexCursor.hasNext()) {
                NavNode node = indexCursor.next();
                result.nodes.put(NavNode.key(node.x, node.feetY, node.z), node);
                long column = NavNode.xzKey(node.x, node.z);
                NavNode indexed = result.xzIndex.get(column);
                if (indexed == null || Math.abs(node.feetY - origin.getY())
                        < Math.abs(indexed.feetY - origin.getY())) result.xzIndex.put(column, node);
                result.xzNodes.computeIfAbsent(column,
                        ignored -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(node);
                minFeetY = Math.min(minFeetY, node.feetY);
                maxFeetY = Math.max(maxFeetY, node.feetY);
            }
            if (indexCursor.hasNext()) return null;
            result.boundsMinY = nodes.isEmpty() ? minY : minFeetY;
            result.boundsMaxY = nodes.isEmpty() ? maxY : maxFeetY;
            if (!result.cells.regroupAllSliced(Math.max(1, maxNodes) * 4, System.currentTimeMillis())) return null;
            result.cells.rebuildMap();
            grouped = true;
            return result;
        }
    }

    public static BuildTask startBuild(Level level, BlockPos origin, int chunkRadius, long version) {
        return new BuildTask(level, origin, chunkRadius, version);
    }

    // =========================================================================
    // Region rebuild
    // =========================================================================

    /**
     * How far past the changed columns a region rebuild re-scans.
     *
     * <p>Whether a column carries a node depends on its neighbours as well as
     * itself — {@code isLedgeSafe} needs a landing in one of the four adjacent
     * columns, and the step window reaches one block up or down. Three columns
     * of margin covers both with room to spare.
     */
    private static final int REGION_MARGIN = 3;

    /**
     * Past this fraction of the claim, a region rebuild is not worth the
     * bookkeeping and a full build is simpler and no slower.
     */
    private static final double REGION_MAX_FRACTION = 0.25;

    /**
     * An XZ rectangle of changed columns, inclusive on both ends, with the
     * band of heights the changes reached and whether water was involved.
     *
     * <p>The band is what keeps a tunnel edit from re-reading the surface
     * above it: a patch takes out only the tiles within {@link #BAND_MARGIN}
     * of it. Water takes the whole column, because a ledge at the top of a
     * column can depend on a pool far below it.
     */
    public record DirtyBox(int minX, int maxX, int minZ, int maxZ, int minY, int maxY, boolean water) {
        long area() {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }

        /** The union of two boxes: all bounds widened, water if either was. */
        DirtyBox merged(DirtyBox other) {
            return new DirtyBox(Math.min(minX, other.minX), Math.max(maxX, other.maxX),
                    Math.min(minZ, other.minZ), Math.max(maxZ, other.maxZ),
                    Math.min(minY, other.minY), Math.max(maxY, other.maxY),
                    water || other.water);
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /**
     * How far above and below the changed heights a patch re-reads. Three
     * blocks covers every way a block at Y can matter to a tile: feet and head
     * room (feet at Y-1 and Y), standing on it (feet at Y+1), the open-trapdoor
     * check two above the feet (feet at Y-2), and the ledge and step checks
     * that look one or two blocks up or down in the next column.
     */
    private static final int BAND_MARGIN = 3;

    /** Cheap preflight so a large update can be scheduled without touching the live graph. */
    public boolean canUpdateRegion(BlockPos origin, int chunkRadius, List<DirtyBox> dirty) {
        return regionsInClaim(origin, chunkRadius, dirty) != null;
    }

    /**
     * The boxes a region update would re-scan: each dirty box grown by
     * {@link #REGION_MARGIN} and clipped to the claim, with boxes that overlap
     * or touch merged into one.
     *
     * <p>The changes are kept as separate boxes rather than one rectangle
     * around all of them. Two small edits at opposite ends of the village used
     * to make a box a quarter of the claim wide, and re-scanning the untouched
     * ground between them cost 370 ms to gain two nodes.
     *
     * @return the boxes, or {@code null} if a region update can't be done and
     *         the caller should fall back to a full build
     */
    private List<DirtyBox> regionsInClaim(BlockPos origin, int chunkRadius, List<DirtyBox> dirty) {
        if (nodes.isEmpty() || dirty.isEmpty()) return null;
        int cx = origin.getX() >> 4;
        int cz = origin.getZ() >> 4;
        int minX = (cx - chunkRadius) << 4;
        int maxX = ((cx + chunkRadius + 1) << 4) - 1;
        int minZ = (cz - chunkRadius) << 4;
        int maxZ = ((cz + chunkRadius + 1) << 4) - 1;
        // The claim may have moved since this graph was built.
        if (boundsMinX != minX || boundsMaxX != maxX
                || boundsMinZ != minZ || boundsMaxZ != maxZ) return null;

        List<DirtyBox> regions = new ArrayList<>(dirty.size());
        for (DirtyBox box : dirty) {
            int rMinX = Math.max(minX, box.minX() - REGION_MARGIN);
            int rMaxX = Math.min(maxX, box.maxX() + REGION_MARGIN);
            int rMinZ = Math.max(minZ, box.minZ() - REGION_MARGIN);
            int rMaxZ = Math.min(maxZ, box.maxZ() + REGION_MARGIN);
            if (rMinX > rMaxX || rMinZ > rMaxZ) continue;
            regions.add(new DirtyBox(rMinX, rMaxX, rMinZ, rMaxZ, box.minY(), box.maxY(), box.water()));
        }
        if (regions.isEmpty()) return null;

        // Merge until no two boxes overlap or touch, so every column is in at
        // most one box and a box's ring never lies inside another box.
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < regions.size(); i++) {
                DirtyBox a = regions.get(i);
                for (int j = i + 1; j < regions.size(); j++) {
                    DirtyBox b = regions.get(j);
                    if (a.minX() <= b.maxX() + 1 && b.minX() <= a.maxX() + 1
                            && a.minZ() <= b.maxZ() + 1 && b.minZ() <= a.maxZ() + 1) {
                        regions.set(i, a.merged(b));
                        regions.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        }

        long regionArea = 0;
        for (DirtyBox region : regions) regionArea += region.area();
        long claimArea = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        if (regionArea > claimArea * REGION_MAX_FRACTION) return null;
        return regions;
    }

    /** Box sizes and bands for the timing log, e.g. "[7x8@62..66, 12x10@-40..-38w]"; the first eight only. */
    private static String describeBoxes(List<DirtyBox> regions) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < regions.size() && i < 8; i++) {
            DirtyBox r = regions.get(i);
            if (i > 0) sb.append(", ");
            sb.append(r.maxX() - r.minX() + 1).append('x').append(r.maxZ() - r.minZ() + 1)
                    .append('@').append(r.minY()).append("..").append(r.maxY());
            if (r.water()) sb.append('w');
        }
        if (regions.size() > 8) sb.append(", ...");
        return sb.append(']').toString();
    }

    /**
     * How many tiles a patch of {@code box} would take out and read again:
     * those in its band in its columns and in the margin around them. What a
     * patch costs follows this, not the box's area -- a mine shaft has many
     * tiles to a column.
     */
    public int nodesAround(DirtyBox box) {
        int count = 0;
        for (int x = box.minX() - REGION_MARGIN; x <= box.maxX() + REGION_MARGIN; x++) {
            for (int z = box.minZ() - REGION_MARGIN; z <= box.maxZ() + REGION_MARGIN; z++) {
                List<NavNode> members = xzNodes.get(NavNode.xzKey(x, z));
                if (members == null) continue;
                if (box.water()) {
                    count += members.size();
                    continue;
                }
                for (NavNode n : members) {
                    if (n.feetY >= box.minY() - BAND_MARGIN && n.feetY <= box.maxY() + BAND_MARGIN) count++;
                }
            }
        }
        return count;
    }

    private static boolean inAnyRegion(int x, int z, List<DirtyBox> regions) {
        for (DirtyBox r : regions) {
            if (r.contains(x, z)) return true;
        }
        return false;
    }

    /**
     * What a patch did, for the caller's bookkeeping: whether it was local
     * at all ({@code done} false means fall back to a full build), which
     * dirty boxes it covered, columns the fill could not read because their
     * chunk was not loaded (to keep dirty), how many boxes it skipped for the
     * same reason (their columns are still dirty), which cells it regrouped,
     * and whether it found islands too large to remove.
     */
    public record UpdateResult(boolean done, List<DirtyBox> ran, List<DirtyBox> keep, int skippedBoxes,
                               Set<Long> touchedCells, boolean fullBuildRequested) {
        static UpdateResult notLocal() {
            return new UpdateResult(false, List.of(), List.of(), 0, Set.of(), false);
        }
    }

    /**
     * Re-reads only the columns that changed, editing this graph in place,
     * then regroups the cells it touched and redoes the piece map, all in
     * this call.
     *
     * <p>A full build re-reads every block in the claim and allocates a fresh
     * node for every one of them: on a superflat world that is ninety thousand
     * columns and about 280 ms of server thread, on generated terrain about half
     * a second. It ran two seconds after any block was placed or broken, so a
     * player laying a floor paid it over and over.
     *
     * <p>A block change can only alter the tiles within a few blocks of it. So
     * this drops the tiles in the dirty box's band of heights, lets the
     * ordinary flood fill spread back in from the surviving boundary — which it
     * does naturally, because the fill links to nodes already in the map instead
     * of enqueuing them — and reindexes only the columns it touched.
     *
     * <p>Everything outside the box keeps its identity, so a pathfind already
     * running on a worker thread stays valid. Adjacency is an array behind a
     * volatile field, replaced rather than edited, so that thread sees either
     * the old neighbours or the new ones and never a half-written list.
     *
     * <p>After the fill, the three whole-graph passes that used to follow an
     * edit over the next ticks -- the island sweep, the Town Hall flood, the
     * reachability labels -- are one regroup of the touched cells and one
     * rebuild of the piece map, a few thousand pieces, well under a
     * millisecond. Islands the map finds are removed here too.
     */
    public UpdateResult updateRegion(Level level, BlockPos origin, int chunkRadius,
                                     List<DirtyBox> dirty) {
        return updateRegion(level, origin, chunkRadius, dirty, Integer.MAX_VALUE);
    }

    /**
     * As {@link #updateRegion(Level, BlockPos, int, List)}, with the patch's fill
     * stopped after {@code maxFillNodes} tiles. The fill spreads wherever the map
     * has no tiles yet, so beside never-built ground a 7x7 edit once filled 176,000
     * tiles in one 1.7 s tick (beta server, 2026-10-18). A capped fill keeps what it
     * built, which joins the graph like any other patch, and asks for a full build
     * ({@link #takeFullBuildRequest}) to finish the rest.
     */
    public UpdateResult updateRegion(Level level, BlockPos origin, int chunkRadius,
                                     List<DirtyBox> dirty, int maxFillNodes) {
        List<DirtyBox> regions = regionsInClaim(origin, chunkRadius, dirty);
        if (regions == null) return UpdateResult.notLocal();

        int originCX = origin.getX() >> 4;
        int originCZ = origin.getZ() >> 4;
        int minX = (originCX - chunkRadius)      << 4;
        int maxX = ((originCX + chunkRadius + 1) << 4) - 1;
        int minZ = (originCZ - chunkRadius)      << 4;
        int maxZ = ((originCZ + chunkRadius + 1) << 4) - 1;
        long claimArea  = (long) (maxX - minX + 1) * (maxZ - minZ + 1);

        // A box whose chunk is not loaded cannot be read: reading it would load
        // the chunk on the spot, which was a four-second tick in a village
        // waking next to a player. Such a box waits; its columns stay dirty.
        List<DirtyBox> loaded = new ArrayList<>(regions.size());
        int skippedBoxes = 0;
        for (DirtyBox r : regions) {
            if (chunksLoaded(level, r)) {
                loaded.add(r);
            } else {
                skippedBoxes++;
                VillageManager.LOGGER.info("[NavGraph] box {}x{} within X[{}..{}] Z[{}..{}] skipped: a chunk is not loaded",
                        r.maxX() - r.minX() + 1, r.maxZ() - r.minZ() + 1, r.minX(), r.maxX(), r.minZ(), r.maxZ());
            }
        }
        if (loaded.isEmpty()) return new UpdateResult(true, List.of(), List.of(), skippedBoxes, Set.of(), false);
        regions = loaded;
        // Which of the caller's boxes this patch covers: each lies inside
        // exactly one region, since regions are the boxes grown and merged.
        List<DirtyBox> ran = new ArrayList<>(dirty.size());
        for (DirtyBox box : dirty) {
            if (inAnyRegion(box.minX(), box.minZ(), regions) && inAnyRegion(box.maxX(), box.maxZ(), regions)) ran.add(box);
        }

        long regionArea = 0;
        int extMinX = Integer.MAX_VALUE, extMaxX = Integer.MIN_VALUE;
        int extMinZ = Integer.MAX_VALUE, extMaxZ = Integer.MIN_VALUE;
        for (DirtyBox r : regions) {
            regionArea += r.area();
            extMinX = Math.min(extMinX, r.minX());
            extMaxX = Math.max(extMaxX, r.maxX());
            extMinZ = Math.min(extMinZ, r.minZ());
            extMaxZ = Math.max(extMaxZ, r.maxZ());
        }

        // Timing only: which step a slow local update spends its time in.
        long tStart = System.nanoTime();
        int nodesBefore = nodes.size();
        int piecesBefore = cells.pieceCount();

        //  1. Take the regions' tiles out -- only the band of heights the edit
        //     reached, the whole column when water is involved -- remembering
        //     which columns to reindex and which survivors lost an edge.
        // Walk the regions' columns through the XZ index rather than the whole
        // node map: an edit touches a few hundred columns out of ninety
        // thousand, and scanning them all was most of what a "local" rebuild
        // still cost.
        Set<Long> touchedColumns = new HashSet<>();
        Set<NavNode> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (DirtyBox r : regions) {
            int bandMin = r.minY() - BAND_MARGIN;
            int bandMax = r.maxY() + BAND_MARGIN;
            // Water anywhere in the box's band takes the whole box whole-column,
            // not just the wet column: a ledge in one column holds a one-way
            // drop into a landing in the next, stored only at the ledge, so
            // the two must be re-read together or the ledge keeps a dead edge.
            boolean whole = r.water() || bandHoldsWater(r, bandMin, bandMax);
            for (int x = r.minX(); x <= r.maxX(); x++) {
                for (int z = r.minZ(); z <= r.maxZ(); z++) {
                    long column = NavNode.xzKey(x, z);
                    List<NavNode> members = xzNodes.get(column);
                    if (members == null) continue;
                    for (NavNode node : members) {
                        if (!whole && (node.feetY < bandMin || node.feetY > bandMax)) continue;
                        if (nodes.remove(NavNode.key(node.x, node.feetY, node.z), node)) {
                            removed.add(node);
                        }
                    }
                    touchedColumns.add(column);
                }
            }
        }

        Deque<NavNode> queue = new ArrayDeque<>(256);
        Set<NavNode> seeded = Collections.newSetFromMap(new IdentityHashMap<>());
        // Survivors with an edge to or from a removed or created node: where
        // any old route through the regions entered and left them. Their
        // cells are regrouped with the regions' own.
        Set<NavNode> boundary = Collections.newSetFromMap(new IdentityHashMap<>());
        for (NavNode node : removed) {
            for (NavNode neighbour : node.neighbours()) {
                if (removed.contains(neighbour)) continue;
                neighbour.retainNeighbours(n -> !removed.contains(n));
                boundary.add(neighbour);
                if (seeded.add(neighbour)) queue.add(neighbour);
            }
        }
        // Also seed from the ring around each region, so a column that gained a
        // node where there was none before is still reached. Again by column,
        // not by walking the graph.
        for (DirtyBox r : regions) {
            for (int x = r.minX() - 1; x <= r.maxX() + 1; x++) {
                for (int z = r.minZ() - 1; z <= r.maxZ() + 1; z++) {
                    if (inAnyRegion(x, z, regions)) continue;
                    List<NavNode> members = xzNodes.get(NavNode.xzKey(x, z));
                    if (members == null) continue;
                    for (NavNode node : members) {
                        if (nodes.get(NavNode.key(node.x, node.feetY, node.z)) != node) continue;
                        // A ledge here may hold a one-way drop into a water
                        // node just removed. The removed node does not list
                        // the ledge, so the loop above never cut that edge,
                        // and it kept a dead node -- and through it the whole
                        // old region -- linked into the graph. The fill below
                        // links the ledge to the new landing.
                        if (!removed.isEmpty()) {
                            int degree = node.degree();
                            node.retainNeighbours(n -> !removed.contains(n));
                            if (node.degree() != degree) boundary.add(node);
                        }
                        if (seeded.add(node)) queue.add(node);
                    }
                }
            }
        }
        // Survivors inside the regions but outside the band keep their tiles
        // and their edges: a block three or more above or below a tile cannot
        // change it, and one that touches a removed tile is a boundary seed
        // already. They are not seeded, so a tunnel edit under a cave-riddled
        // surface does not re-probe every cave tile in the box.
        // Nothing of the graph in or around the regions: there is nothing to
        // patch, and the edit is done. (A passage opened from an existing tile
        // always has that tile within the margin, so this is never a missed
        // connection.)
        if (queue.isEmpty()) {
            // Tiles were taken out and nothing around them survives to grow
            // them back: the whole walkable world fits inside the regions (a
            // sky platform, a sealed compound, a tiny test map). Only a full
            // build can draw it again, and the caller does one.
            if (!removed.isEmpty()) return UpdateResult.notLocal();
            return new UpdateResult(true, ran, List.of(), skippedBoxes, Set.of(), false);
        }
        int seedCount = queue.size();
        long tRemoved = System.nanoTime();

        //  2. Re-scan the regions. Nodes still in the map are linked, never
        //     enqueued, so the fill spreads inward and stops at the boundary.
        // The fill runs the very same code as a full build, with no timing
        // branches of its own: a branch only a patch takes made the compiler
        // throw away the version it had built for the full build, and the
        // first patches of a session ran slow until it had built another.
        List<NavNode> created = new ArrayList<>();
        Set<Long> unreadable = new HashSet<>();
        CHUNK_GUARD.set(unreadable);
        CREATED.set(created);
        try {
            // The same fill a sliced full build runs, with the same budget argument.
            floodFillInner(level, nodes, queue, minX, maxX, minZ, maxZ,
                    level.getMinBuildHeight(), level.getMaxBuildHeight() - 1, maxFillNodes);
        } finally {
            CREATED.remove();
            CHUNK_GUARD.remove();
        }
        boolean fillCapped = !queue.isEmpty();
        if (fillCapped) {
            fullBuildRequested = true;
            VillageManager.LOGGER.warn("[NavGraph] the patch's fill reached its limit of {} tiles ({} created): "
                    + "kept them and asked for a full rebuild", maxFillNodes, created.size());
        }
        int createdOutside = 0;
        for (NavNode node : created) {
            touchedColumns.add(NavNode.xzKey(node.x, node.z));
            if (!inAnyRegion(node.x, node.z, regions)) createdOutside++;
        }
        // The rest of the boundary: survivors the fill linked to, both ways
        // (a one-way drop is stored only at the ledge, which was a seed).
        Set<NavNode> createdSet = Collections.newSetFromMap(new IdentityHashMap<>());
        createdSet.addAll(created);
        for (NavNode node : created) {
            for (NavNode neighbour : node.neighbours()) {
                if (!createdSet.contains(neighbour)) boundary.add(neighbour);
            }
        }
        for (NavNode node : seeded) {
            if (boundary.contains(node)) continue;
            for (NavNode neighbour : node.neighbours()) {
                if (createdSet.contains(neighbour)) {
                    boundary.add(node);
                    break;
                }
            }
        }
        long tFilled = System.nanoTime();

        //  3. Reindex only the columns that changed.
        int reindexed = reindexTouched(touchedColumns, created, origin);
        long tIndexed = System.nanoTime();

        //  4. Regroup the cells of every tile whose edges changed -- removed,
        //     created, boundary -- and redo the piece map.
        long now = System.currentTimeMillis();
        Set<Long> touched = new HashSet<>();
        for (NavNode node : removed) touched.add(NavCells.cellKeyOf(node));
        for (NavNode node : created) touched.add(NavCells.cellKeyOf(node));
        for (NavNode node : boundary) touched.add(NavCells.cellKeyOf(node));
        cells.regroupCells(touched, now);
        long tGrouped = System.nanoTime();
        cells.rebuildMap();
        long tMapped = System.nanoTime();

        //  5. Whatever the edit cut off from the Town Hall goes now, tile by
        //     tile from the island pieces. One edit cuts off a room or a yard,
        //     never most of the village: islands that large mean the root is
        //     wrong (an old version seeded in a cave under the Town Hall and
        //     threw away every surface node), so keep the graph whole and let
        //     a full build, which needs no root beyond the origin, decide.
        int islandTiles = 0;
        boolean islandsRefused = false;
        List<NavNode> islands = cells.islandTiles();
        if (!islands.isEmpty()) {
            if (islands.size() > nodes.size() * ISLAND_MAX_FRACTION) {
                islandsRefused = true;
                fullBuildRequested = true;
                VillageManager.LOGGER.warn("[NavGraph] the patch left {} of {} tiles cut off from the Town Hall; "
                                + "kept them and asked for a full rebuild", islands.size(), nodes.size());
            } else {
                Set<NavNode> islandSet = Collections.newSetFromMap(new IdentityHashMap<>());
                islandSet.addAll(islands);
                Set<Long> emptied = new HashSet<>();
                Set<Long> touchedAgain = new HashSet<>();
                for (NavNode tile : islands) {
                    if (!nodes.remove(NavNode.key(tile.x, tile.feetY, tile.z), tile)) continue;
                    emptied.add(NavNode.xzKey(tile.x, tile.z));
                    touchedAgain.add(NavCells.cellKeyOf(tile));
                    islandTiles++;
                }
                // Only a survivor next to an island tile can hold an edge to
                // one. That holds for the one edge stored in a single
                // direction, a drop into deep water, because it is always
                // walkable: a survivor holding one reached its landing, so
                // the landing was no island.
                for (NavNode tile : islands) {
                    for (NavNode neighbour : tile.neighbours()) {
                        if (islandSet.contains(neighbour) || !isLive(neighbour)) continue;
                        neighbour.retainNeighbours(n -> !islandSet.contains(n));
                        touchedAgain.add(NavCells.cellKeyOf(neighbour));
                    }
                }
                reindexTouched(emptied, List.of(), origin);
                cells.regroupCells(touchedAgain, now);
                cells.rebuildMap();
                touched.addAll(touchedAgain);
            }
        }

        //  6. The height span only ever needs to grow: it is a scan window, so
        //     leaving it a little wide after a removal costs nothing, and
        //     recomputing it would mean walking every node again.
        for (NavNode node : created) {
            if (node.feetY < boundsMinY) boundsMinY = node.feetY;
            if (node.feetY > boundsMaxY) boundsMaxY = node.feetY;
        }

        version++;
        cells.markChanged(touched, version);
        // Where the door hunt and the building re-check should look: the
        // cells this patch regrouped, and the cells of pieces whose Town Hall
        // access flipped either way. A token hung on a house while its only
        // path was broken used to be found by a hunt under every tile in the
        // village after every edit; this finds it the moment the path is
        // mended, and a building cut off by a far-away edit is still seen.
        Set<Long> scanCells = new HashSet<>(touched);
        scanCells.addAll(cells.lastChangedAccess());
        long tDone = System.nanoTime();

        VillageManager.LOGGER.info("[NavGraph] local update {} ms: {} box(es) {} ({} cols, {}% of claim) within X[{}..{}] Z[{}..{}]"
                        + " | remove {} ms: {} tiles, {} seeds | fill {} ms: {} created ({} outside boxes)"
                        + " | reindex {} ms: {} cols | cells {} regrouped in {} ms | pieces {} -> {}, piece map {} ms"
                        + " | islands {} removed ({} tiles){} | tiles {} -> {}{}{}",
                (tDone - tStart) / 1_000_000L,
                regions.size(), describeBoxes(regions), regionArea,
                String.format(Locale.ROOT, "%.1f", 100.0 * regionArea / claimArea),
                extMinX, extMaxX, extMinZ, extMaxZ,
                (tRemoved - tStart) / 1_000_000L, removed.size(), seedCount,
                (tFilled - tRemoved) / 1_000_000L, created.size(), createdOutside,
                (tIndexed - tFilled) / 1_000_000L, reindexed,
                touched.size(), String.format(Locale.ROOT, "%.2f", (tGrouped - tIndexed) / 1_000_000.0),
                piecesBefore, cells.pieceCount(),
                String.format(Locale.ROOT, "%.2f", (tMapped - tGrouped) / 1_000_000.0),
                islandTiles == 0 && !islandsRefused ? 0 : 1, islandTiles,
                islandsRefused ? " (refused, full build requested)" : "",
                nodesBefore, nodes.size(),
                skippedBoxes > 0 ? " | " + skippedBoxes + " box(es) skipped, chunk not loaded" : "",
                unreadable.isEmpty() ? "" : " | " + unreadable.size() + " column(s) left for a loaded chunk");
        return new UpdateResult(true, ran, keepBoxes(unreadable, level), skippedBoxes, scanCells,
                islandsRefused || fillCapped);
    }

    /** Whether any tile in the band of any column of the box is a water tile or a deep-water landing. */
    private boolean bandHoldsWater(DirtyBox r, int bandMin, int bandMax) {
        for (int x = r.minX(); x <= r.maxX(); x++) {
            for (int z = r.minZ(); z <= r.maxZ(); z++) {
                List<NavNode> members = xzNodes.get(NavNode.xzKey(x, z));
                if (members == null) continue;
                for (NavNode node : members) {
                    if (node.feetY < bandMin || node.feetY > bandMax) continue;
                    if (node.waterDrop || node.surfaceBlock instanceof LiquidBlock) return true;
                }
            }
        }
        return false;
    }

    /** Every chunk a region and its ring touch is loaded. */
    private static boolean chunksLoaded(Level level, DirtyBox r) {
        for (int cx = (r.minX() - 1) >> 4; cx <= (r.maxX() + 1) >> 4; cx++) {
            for (int cz = (r.minZ() - 1) >> 4; cz <= (r.maxZ() + 1) >> 4; cz++) {
                if (!level.hasChunkAt(new BlockPos(cx << 4, 0, cz << 4))) return false;
            }
        }
        return true;
    }

    /** One whole-column box per column the fill could not read, for the tracker to keep dirty. */
    private static List<DirtyBox> keepBoxes(Set<Long> columns, Level level) {
        if (columns.isEmpty()) return List.of();
        List<DirtyBox> boxes = new ArrayList<>(columns.size());
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        for (long column : columns) {
            int x = BlockPos.getX(column);
            int z = BlockPos.getZ(column);
            boxes.add(new DirtyBox(x, x, z, z, minY, maxY, false));
        }
        return boxes;
    }

    /** True if {@code node} is still in this graph (not removed or replaced). */
    boolean isLive(NavNode node) {
        return nodes.get(NavNode.key(node.x, node.feetY, node.z)) == node;
    }

    // =========================================================================
    // Cells, pieces, and what they answer
    // =========================================================================

    /** The bell this graph was built from. */
    public BlockPos origin() {
        return origin;
    }

    /** The tile at the Town Hall door the build started from, or null if that spot has no tile now. */
    public NavNode rootNode() {
        return hasRoot ? nodes.get(rootKey) : null;
    }

    /** The tiles of one column, or null when it has none. */
    List<NavNode> columnNodes(int x, int z) {
        return xzNodes.get(NavNode.xzKey(x, z));
    }

    /** The layer above the tiles: cells, pieces and the piece map. */
    public NavCells cells() {
        return cells;
    }

    /** The cell a tile at these coordinates belongs to; see {@link NavCells#cellKey}. */
    public static long cellKey(int x, int feetY, int z) {
        return NavCells.cellKey(x, feetY, z);
    }

    /** The live tiles of these cells, for a scan that only needs to look where a patch changed something. */
    public List<NavNode> nodesInCells(Collection<Long> cellKeys) {
        List<NavNode> tiles = new ArrayList<>();
        for (long key : cellKeys) tiles.addAll(cells.tilesOf(key));
        return tiles;
    }

    /**
     * Whether any of these cells changed after {@code version}. A route or a
     * cache that remembers the cells it crossed asks this instead of comparing
     * the village-wide version, so an edit at the far end of the village no
     * longer makes every walker re-plan.
     */
    public boolean changedSince(long version, long[] cellKeys) {
        return cells.changedSince(version, cellKeys);
    }

    /** As {@link #changedSince(long, long[])} for every cell within a box around {@code center}. */
    public boolean changedSince(long version, BlockPos center, int radiusXZ, int radiusY) {
        return cells.changedSince(version, center, radiusXZ, radiusY);
    }

    /**
     * A villager pressed against the step {@code from -> to} and could not
     * make it. The route planner avoids the step for {@code durationMillis}
     * (see {@link NavNode#markStepFailed}); here the edge's kind changed, so
     * the cell is regrouped and the piece map redone, now and again when the
     * verdict lapses ({@link #tickFailedSteps}). That replaces throwing away
     * the labels of the whole village twice per failed jump.
     */
    public void markStepFailed(NavNode from, NavNode to, long durationMillis) {
        markStepFailed(from, to, durationMillis, true);
    }

    /**
     * As {@link #markStepFailed(NavNode, NavNode, long)}. With {@code countsTowardSticky} false
     * the step's failure count is left alone, so this mark always lapses after
     * {@code durationMillis} and never keeps the step failed for the session: ladder blames
     * ({@code FollowWalk.blameLadderStep}), where a walker fault must not lose a good ladder.
     */
    public void markStepFailed(NavNode from, NavNode to, long durationMillis, boolean countsTowardSticky) {
        long now = System.currentTimeMillis();
        // Stuck spots E: a step that fails again and again is not a fluke of
        // one jump. After the third failure it stays failed for the session
        // (the node forgets it when the blocks there change) instead of coming
        // back every ten minutes to catch the next villager.
        if (countsTowardSticky) {
            int failures = from.countStepFailure(to);
            if (WalkFeel.stickySteps && failures >= STICKY_STEP_FAILURES) {
                durationMillis = Math.max(durationMillis, STICKY_STEP_MILLIS);
                VillageManager.LOGGER.info("[NavGraph] step {} -> {} failed {} times; kept failed for the session",
                        from, to, failures);
            }
        } else {
            // Never cuts short a longer verdict already in force (a session-long one from other marks).
            long existing = from.failedStepUntil(to, now);
            if (existing > 0L) durationMillis = Math.max(durationMillis, existing - now);
        }
        // One clock reading for both the tile's verdict and the expiry entry:
        // a millisecond between two readings could leave the edge failed for
        // the rest of the session, regrouped a moment before it lapsed.
        long until = now + durationMillis;
        from.markStepFailedUntil(to, until);
        long started = System.nanoTime();
        cells.noteStepFailed(from, to, until, now);
        version++;
        cells.markChanged(List.of(NavCells.cellKeyOf(from)), version);
        long took = System.nanoTime() - started;
        if (took >= 1_000_000L) {
            VillageManager.LOGGER.info("[NavGraph] step failed at {}: regrouped 1 cell, piece map {} ms",
                    from, String.format(Locale.ROOT, "%.1f", cells.lastMapNanos() / 1_000_000.0));
        }
    }

    /** Lets lapsed failed jumps count again; true when a regroup happened. Once per village tick. */
    public boolean tickFailedSteps(long nowMillis) {
        Set<Long> regrouped = cells.tickExpiry(nowMillis);
        if (regrouped.isEmpty()) return false;
        version++;
        cells.markChanged(regrouped, version);
        return true;
    }

    /**
     * Reindexes the given columns plus those of {@code created}. Members are
     * gathered from the surviving index plus whatever the fill just made, so
     * this costs the size of the change rather than the size of the graph.
     *
     * @return how many columns were reindexed
     */
    private int reindexTouched(Set<Long> touchedColumns, List<NavNode> created, BlockPos origin) {
        Map<Long, List<NavNode>> members = new HashMap<>();
        for (long column : touchedColumns) {
            List<NavNode> existing = xzNodes.get(column);
            List<NavNode> kept = new ArrayList<>(2);
            if (existing != null) {
                for (NavNode n : existing) {
                    if (nodes.get(NavNode.key(n.x, n.feetY, n.z)) == n) kept.add(n);
                }
            }
            members.put(column, kept);
        }
        for (NavNode node : created) {
            if (nodes.get(NavNode.key(node.x, node.feetY, node.z)) != node) continue;
            members.computeIfAbsent(NavNode.xzKey(node.x, node.z),
                    ignored -> new ArrayList<>(2)).add(node);
        }
        for (Map.Entry<Long, List<NavNode>> entry : members.entrySet()) {
            reindexColumn(entry.getKey(), entry.getValue(), origin);
        }
        return members.size();
    }

    private static boolean inRegion(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private static boolean isAdjacentToRegion(int x, int z,
                                              int minX, int maxX, int minZ, int maxZ) {
        return x >= minX - 1 && x <= maxX + 1 && z >= minZ - 1 && z <= maxZ + 1;
    }

    /**
     * A patch whose islands would be more than this share of the tiles keeps
     * them and asks for a full build instead; see {@link #updateRegion}.
     */
    private static final double ISLAND_MAX_FRACTION = 0.10;

    /**
     * True once after a patch refused to remove islands that were more than
     * {@link #ISLAND_MAX_FRACTION} of the graph; the caller should replace this
     * graph with a full build.
     */
    public boolean takeFullBuildRequest() {
        boolean requested = fullBuildRequested;
        fullBuildRequested = false;
        return requested;
    }

    /** Builds the XZ indexes and Y bounds, then freezes the graph. */
    private static NavGraph index(Map<Long, NavNode> nodes, BlockPos origin, long version,
                                  int minX, int maxX, int minZ, int maxZ,
                                  int levelMinY, int levelMaxY) {
        Map<Long, NavNode> live = new java.util.concurrent.ConcurrentHashMap<>(nodes.size() * 2);
        live.putAll(nodes);
        Map<Long, NavNode> xzIdx = new java.util.concurrent.ConcurrentHashMap<>(nodes.size() * 2);
        Map<Long, List<NavNode>> xzNodeIdx =
                new java.util.concurrent.ConcurrentHashMap<>(nodes.size() * 2);
        NavGraph graph = new NavGraph(live, xzIdx, xzNodeIdx, version, origin,
                minX, maxX, minZ, maxZ, levelMinY, levelMaxY);
        graph.reindexAll(origin, levelMinY, levelMaxY);
        return graph;
    }

    /**
     * The flood fill itself, shared by a full build and a region rebuild.
     *
     * <p>Nodes already present in {@code nodes} are linked to but never
     * enqueued, which is what lets a region rebuild pre-populate the map with
     * everything outside the dirty box and have the fill spread only inward.
     */
    private static void floodFill(Level level, Map<Long, NavNode> nodes,
                                  Queue<NavNode> queue,
                                  int minX, int maxX, int minZ, int maxZ,
                                  int levelMinY, int levelMaxY) {
        floodFill(level, nodes, queue, minX, maxX, minZ, maxZ, levelMinY, levelMaxY, null);
    }

    /**
     * @param created if non-null, every node the fill adds is recorded here, so
     *                a region update can reindex just those columns instead of
     *                walking the whole graph
     */
    private static void floodFill(Level level, Map<Long, NavNode> nodes,
                                  Queue<NavNode> queue,
                                  int minX, int maxX, int minZ, int maxZ,
                                  int levelMinY, int levelMaxY,
                                  java.util.Collection<NavNode> created) {
        CREATED.set(created);
        try {
            floodFillInner(level, nodes, queue, minX, maxX, minZ, maxZ, levelMinY, levelMaxY);
        } finally {
            CREATED.remove();
        }
    }

    /** Where {@link #floodFill} records new nodes, when the caller asked for them. */
    private static final ThreadLocal<java.util.Collection<NavNode>> CREATED = new ThreadLocal<>();

    /**
     * Where a patch's fill records the columns it would not read because
     * their chunk is not loaded; null in a full build, whose claim is loaded
     * ahead. Set only by {@link #updateRegion}.
     */
    private static final ThreadLocal<Set<Long>> CHUNK_GUARD = new ThreadLocal<>();

    private static void record(NavNode node) {
        java.util.Collection<NavNode> sink = CREATED.get();
        if (sink != null) sink.add(node);
    }

    private static void floodFillInner(Level level, Map<Long, NavNode> nodes,
                                       Queue<NavNode> queue,
                                       int minX, int maxX, int minZ, int maxZ,
                                       int levelMinY, int levelMaxY) {
        floodFillInner(level, nodes, queue, minX, maxX, minZ, maxZ,
                levelMinY, levelMaxY, Integer.MAX_VALUE);
    }

    private static int floodFillInner(Level level, Map<Long, NavNode> nodes,
                                      Queue<NavNode> queue,
                                      int minX, int maxX, int minZ, int maxZ,
                                      int levelMinY, int levelMaxY, int maxProcessed) {
        final int[] DX = { 1, -1, 0, 0 };
        final int[] DZ = { 0,  0, 1, -1 };
        final Set<Long> guard = CHUNK_GUARD.get();

        int processed = 0;
        while (!queue.isEmpty() && processed < maxProcessed) {
            processed++;
            NavNode cur = queue.poll();

            // Expand the ladder just like any other graph edge. One rung at a
            // time lets the same flood fill reach both ends of any height run,
            // including a ladder exposed by a local block update.
            if (cur.climb) {
                for (int dy = -1; dy <= 1; dy += 2) {
                    int rungY = cur.feetY + dy;
                    if (rungY < levelMinY || !isClimbableRung(level, cur.x, rungY, cur.z, levelMaxY)) continue;
                    NavNode rung = obtainLadderNode(level, nodes, queue, cur.x, rungY, cur.z);
                    cur.addNeighbour(rung);
                    rung.addNeighbour(cur);
                }
            }

            for (int d = 0; d < 4; d++) {
                int nx = cur.x + DX[d];
                int nz = cur.z + DZ[d];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                // A patch never reads a chunk that is not loaded: reading one
                // loads it on the spot, which was a four-second tick in a
                // village waking beside a player. The column waits for its
                // chunk; the tracker keeps it dirty.
                if (guard != null && ((nx >> 4) != (cur.x >> 4) || (nz >> 4) != (cur.z >> 4))
                        && !level.hasChunkAt(new BlockPos(nx, 0, nz))) {
                    guard.add(NavNode.xzKey(nx, nz));
                    continue;
                }

                // A ladder with a floor beneath its first rung also looks like
                // an ordinary walkable surface. Resolve the ladder first so
                // that its bottom node keeps the vertical chain attached.
                NavNode ladderEntry = scanLadderNeighbour(level, nodes, queue, cur, nx, nz, levelMinY, levelMaxY);
                if (ladderEntry != null) continue;

                Integer surfY = findReachableSurfaceY(level, cur.feetY, nx, nz, levelMinY, levelMaxY);
                if (surfY != null) {
                    // A ladder one block above this node is not a floor. It
                    // becomes reachable only through a real ladder entry.
                    if (isLadder(level, nx, surfY + 1, nz)) continue;
                    // A higher landing is usable only if the villager can rise
                    // to that height while still in the ladder column. A roof
                    // over the rung can block that climb even when the landing
                    // itself has two clear blocks above it.
                    if (cur.climb && surfY + 1 > cur.feetY
                            && (!isPassable(level, cur.x, surfY + 1, cur.z)
                            || !isPassable(level, cur.x, surfY + 2, cur.z))) continue;
                    // A door or gate is walked through the way it faces, never
                    // sideways. The two leaves of a double door are neighbouring
                    // cells, and a route that stepped from one into the other
                    // went in through one leaf and turned round inside the frame.
                    if (!isAlongPortalAxis(level, cur, nx, surfY + 1, nz)) continue;
                    // An open trapdoor's cell is passable, but not through its
                    // plate: a row of them set up as a wall is still a wall.
                    if (crossesTrapdoorPlate(level, cur, nx, surfY + 1, nz)) continue;
                    double nbStand = standY(level, nx, surfY, nz);
                    if (!hasStepHeadroom(level, cur.x, cur.surfaceY, cur.z, cur.standY,
                            nx, surfY, nz, nbStand)) continue;
                    long key = NavNode.key(nx, surfY + 1, nz);

                    // Edges stay two-way so a region rebuild can find and cut
                    // every link into the box it removes. A route checks the
                    // climb direction itself (NavNode.canStepTo).
                    if (nodes.containsKey(key)) {
                        NavNode existing = nodes.get(key);
                        cur.addNeighbour(existing);
                        existing.addNeighbour(cur);
                        continue;
                    }

                    // But the fill only spreads the way a villager can walk:
                    // a ledge one and a half blocks up gets a node only if it
                    // can be reached some other way.
                    if (!cur.climb && !cur.waterDrop
                            && nbStand - cur.standY > MAX_STEP_UP + 1.0E-4) continue;

                    NavNode nb = makeNode(level, nx, nz, surfY);
                    nodes.put(key, nb);
                    record(nb);
                    cur.addNeighbour(nb);
                    nb.addNeighbour(cur);
                    queue.add(nb);
                    continue;
                }

                // A survivable plunge into deep water is the remaining
                // non-walking connection.
                tryLinkWaterNeighbour(level, nodes, queue, cur, nx, nz, levelMinY, levelMaxY);
            }
        }
        return processed;
    }

    /** Rebuilds both XZ indexes and the Y bounds from the node map. */
    private void reindexAll(BlockPos origin, int levelMinY, int levelMaxY) {
        xzIndex.clear();
        xzNodes.clear();
        int minFeetY = Integer.MAX_VALUE, maxFeetY = Integer.MIN_VALUE;
        for (NavNode n : nodes.values()) {
            long xzKey = NavNode.xzKey(n.x, n.z);
            NavNode indexed = xzIndex.get(xzKey);
            if (indexed == null
                    || Math.abs(n.feetY - origin.getY()) < Math.abs(indexed.feetY - origin.getY())) {
                xzIndex.put(xzKey, n);
            }
            xzNodes.computeIfAbsent(xzKey,
                    ignored -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(n);
            // (index() calls this once per full build; region updates patch
            //  individual columns through reindexColumn instead.)
            if (n.feetY < minFeetY) minFeetY = n.feetY;
            if (n.feetY > maxFeetY) maxFeetY = n.feetY;
        }
        if (nodes.isEmpty()) {
            minFeetY = levelMinY;
            maxFeetY = levelMaxY;
        }
        boundsMinY = minFeetY;
        boundsMaxY = maxFeetY;
    }

    /**
     * Rebuilds the indexes for one column, given the nodes it now holds.
     *
     * <p>The members are passed in rather than searched for: an earlier version
     * scanned the whole node map per column, which turned a fifty-column edit
     * into four million comparisons and made a "local" rebuild slower than the
     * full one it replaced.
     */
    private void reindexColumn(long xzKey, List<NavNode> members, BlockPos origin) {
        if (members == null || members.isEmpty()) {
            xzNodes.remove(xzKey);
            xzIndex.remove(xzKey);
            return;
        }
        NavNode nearest = null;
        for (NavNode n : members) {
            if (nearest == null
                    || Math.abs(n.feetY - origin.getY()) < Math.abs(nearest.feetY - origin.getY())) {
                nearest = n;
            }
        }
        xzNodes.put(xzKey, new java.util.concurrent.CopyOnWriteArrayList<>(members));
        xzIndex.put(xzKey, nearest);
    }

    private static Map<Long, List<NavNode>> freezeColumnIndex(Map<Long, List<NavNode>> index) {
        Map<Long, List<NavNode>> frozen = new HashMap<>(index.size() * 2);
        for (Map.Entry<Long, List<NavNode>> entry : index.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Integer findReachableSurfaceY(Level level, int fromFeetY,
                                                 int nx, int nz,
                                                 int minY, int maxY) {
        for (int ny = fromFeetY; ny >= fromFeetY - 2; ny--) {
            if (ny < minY || ny > maxY) continue;
            if (!isSolidSurface(level, nx, ny, nz)) continue;
            int feetY = ny + 1;
            if (feetY + 1 > maxY) continue;
            if (!hasStandingRoom(level, nx, ny, nz)) continue;
            if (!isLedgeSafe(level, nx, feetY, nz, minY, maxY)) continue;
            return ny;
        }
        return null;
    }

    /**
     * The height an entity stands at on this surface block: the top of its
     * collision shape. A bottom slab or a stair's lower step is half a block
     * below the cell above it. A floor covering on the surface (see
     * {@link #floorCoveringHeight}) lifts it by the covering's thickness: a
     * sixteenth for a carpet.
     */
    private static double standY(Level level, int x, int surfY, int z) {
        double cover = floorCoveringHeight(level, x, surfY + 1, z);
        if (cover >= 0) return surfY + 1 + cover;
        BlockPos pos = new BlockPos(x, surfY, z);
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) return surfY + 1.0;
        return surfY + Math.min(1.0, shape.max(Direction.Axis.Y));
    }

    /**
     * True when an entity standing on this surface has room for its full
     * height.
     *
     * <p>This used to demand two completely empty blocks above the surface
     * block. That is right for a full-block floor, but a bottom slab floor puts
     * the entity half a block lower, so a top slab above the head still leaves
     * exactly two blocks — and a villager is 1.9 tall. Slab-built rooms, where
     * one slab layer is the ceiling below and the floor above, had no nodes at
     * all on their lower storey.
     */
    private static boolean hasStandingRoom(Level level, int x, int surfY, int z) {
        return isClearUpTo(level, x, surfY + 1, z, standY(level, x, surfY, z) + ENTITY_HEIGHT);
    }

    /** The same local standing rules used while constructing the graph. */
    public record StandingCandidate(boolean surface, boolean headroom, boolean ledgeSafe) {
        public boolean walkable() { return surface && headroom && ledgeSafe; }
    }

    /** Read-only checks for a missing room-entry node; does not add graph nodes. */
    public static StandingCandidate standingCandidate(Level level, BlockPos feetPos) {
        int x = feetPos.getX(), feetY = feetPos.getY(), z = feetPos.getZ();
        int surfaceY = feetY - 1;
        boolean surface = isSolidSurface(level, x, surfaceY, z);
        boolean headroom = surface && hasStandingRoom(level, x, surfaceY, z);
        boolean ledge = headroom && isLedgeSafe(level, x, feetY, z,
                level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        return new StandingCandidate(surface, headroom, ledge);
    }

    public static String describeStandingCandidate(Level level, BlockPos feetPos) {
        StandingCandidate candidate = standingCandidate(level, feetPos);
        return "surface=" + candidate.surface() + ", headroom=" + candidate.headroom()
                + ", ledgeSafe=" + candidate.ledgeSafe();
    }

    /**
     * True when every cell of a column from {@code fromY} up to world height
     * {@code topY} is free for an entity to occupy. The topmost cell may be
     * partly filled, as long as its collision starts at or above {@code topY}:
     * a top slab, say, whose lower half is empty.
     */
    private static boolean isClearUpTo(Level level, int x, int fromY, int z, double topY) {
        for (int y = fromY; y < topY - 1.0E-4; y++) {
            if (isPassable(level, x, y, z)) continue;
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || isVanillaImpassable(state)) return false;
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty() || y + shape.min(Direction.Axis.Y) < topY - 1.0E-4) return false;
        }
        return true;
    }

    /**
     * True when a step between two adjacent columns has room for the
     * villager's head.
     *
     * <p>Each node only guarantees room above its own floor. Going up, the
     * villager rises to the higher floor while it is still over the lower one —
     * a jump happens where you stand, and even a half-block step lifts the whole
     * body before it moves across. Going down it walks out level at the upper
     * height and only then drops. Either way the lower column needs room for
     * the villager at the <em>higher</em> standing height, so a slab or a roof
     * just above its head blocks the step both ways, even though both nodes
     * look fine on their own. Vanilla navigation knows this and will not make
     * the move, so without this check the graph promised an edge the villager
     * then stood under forever.
     */
    private static boolean hasStepHeadroom(Level level,
                                           int ax, int aSurfY, int az, double aStand,
                                           int bx, int bSurfY, int bz, double bStand) {
        if (aStand < bStand) return isClearUpTo(level, ax, aSurfY + 1, az, bStand + ENTITY_HEIGHT);
        if (bStand < aStand) return isClearUpTo(level, bx, bSurfY + 1, bz, aStand + ENTITY_HEIGHT);
        return true;
    }

    private static NavNode tryCreateSeed(Level level, BlockPos origin) {
        int x = origin.getX(), z = origin.getZ();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;

        int surfY = origin.getY() - 1;
        if (surfY >= minY
                && isSolidSurface(level, x, surfY, z)
                && hasStandingRoom(level, x, surfY, z)
                && isLedgeSafe(level, x, surfY + 1, z, minY, maxY)) {
            return makeNode(level, x, z, surfY);
        }

        surfY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        if (surfY < minY || surfY + 2 > maxY) return null;
        if (!isSolidSurface(level, x, surfY, z)) return null;
        if (!hasStandingRoom(level, x, surfY, z)) return null;
        if (!isLedgeSafe(level, x, surfY + 1, z, minY, maxY)) return null;
        return makeNode(level, x, z, surfY);
    }

    private static NavNode makeNode(Level level, int x, int z, int surfY) {
        BlockState surf = level.getBlockState(new BlockPos(x, surfY, z));
        BlockState feet = level.getBlockState(new BlockPos(x, surfY + 1, z));
        boolean portal = feet.getBlock() instanceof DoorBlock
                || feet.getBlock() instanceof FenceGateBlock;
        boolean openTrapdoor = isOpenTrapdoor(feet)
                || isOpenTrapdoor(level.getBlockState(new BlockPos(x, surfY + 2, z)));
        return new NavNode(x, z, surfY, standY(level, x, surfY, z), surf.getBlock(),
                portal, false, false, false, openTrapdoor, isBesideCauldron(level, x, surfY + 1, z),
                halfStepSides(level, x, surfY, z, surf), NavNode.portalKindOf(feet.getBlock()));
    }

    /** Half the body's width: the strip a body coming in sweeps over a side of the cell. */
    private static final double BODY_HALF_WIDTH = 0.3;

    /**
     * Climb cost 2B (10-18): the sides of a stair floor where a body coming in meets the stair's
     * low half first ({@link NavNode#halfStepSides}). A side counts when nothing in the body-wide
     * strip over the half of the cell next to it reaches above half height, the way the walker's
     * step rule ({@code FollowRoute.step}) would find it: the front of a straight stair, two sides
     * of an outer corner, no side of an inner corner. Only stairs: a slab already stands half low.
     */
    private static byte halfStepSides(Level level, int x, int surfY, int z, BlockState surf) {
        if (!(surf.getBlock() instanceof StairBlock)) return 0;
        BlockPos pos = new BlockPos(x, surfY, z);
        VoxelShape shape = surf.getCollisionShape(level, pos);
        if (shape.isEmpty() || shape.max(Direction.Axis.Y) < 1.0 - 1.0E-6) return 0;
        List<net.minecraft.world.phys.AABB> boxes = shape.toAabbs();
        double lo = 0.5 - BODY_HALF_WIDTH, hi = 0.5 + BODY_HALF_WIDTH;
        int mask = 0;
        if (lowHalf(boxes, 0.5, 1.0, lo, hi)) mask |= NavNode.sideBit(1, 0);
        if (lowHalf(boxes, 0.0, 0.5, lo, hi)) mask |= NavNode.sideBit(-1, 0);
        if (lowHalf(boxes, lo, hi, 0.5, 1.0)) mask |= NavNode.sideBit(0, 1);
        if (lowHalf(boxes, lo, hi, 0.0, 0.5)) mask |= NavNode.sideBit(0, -1);
        return (byte) mask;
    }

    /** True when the boxes inside this part of the cell top out at half height (and some do). */
    private static boolean lowHalf(List<net.minecraft.world.phys.AABB> boxes,
                                   double minX, double maxX, double minZ, double maxZ) {
        double top = -1.0;
        for (net.minecraft.world.phys.AABB b : boxes) {
            if (b.maxX > minX + 1.0E-6 && b.minX < maxX - 1.0E-6 && b.maxZ > minZ + 1.0E-6 && b.minZ < maxZ - 1.0E-6) {
                top = Math.max(top, b.maxY);
            }
        }
        return top > 0.4 && top <= 0.5 + 1.0E-6;
    }

    /**
     * A cauldron in one of the four cells beside this foot cell.
     *
     * <p>Asked for every node a build makes, so it first asks the chunk
     * section's palette whether it holds a cauldron at all, and remembers the
     * answer for the section until the tick changes. A cell on a section edge
     * has neighbours in the next section and skips that shortcut.
     */
    private static boolean isBesideCauldron(Level level, int x, int feetY, int z) {
        int lx = x & 15, lz = z & 15;
        if (lx > 0 && lx < 15 && lz > 0 && lz < 15 && !sectionMayHaveCauldron(level, x, feetY, z)) return false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = new BlockPos(x + dir.getStepX(), feetY, z + dir.getStepZ());
            if (level.getBlockState(side).getBlock() instanceof AbstractCauldronBlock) return true;
        }
        return false;
    }

    /** Last section asked about, the tick it was asked on, and the answer. Server thread only. */
    private static long cauldronMemoSection = Long.MIN_VALUE;
    private static long cauldronMemoTick = Long.MIN_VALUE;
    private static boolean cauldronMemoAnswer;

    private static boolean sectionMayHaveCauldron(Level level, int x, int y, int z) {
        long section = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        long tick = level.getGameTime();
        if (section == cauldronMemoSection && tick == cauldronMemoTick) return cauldronMemoAnswer;
        boolean may = true;
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk != null) {
            int index = chunk.getSectionIndex(y);
            if (index >= 0 && index < chunk.getSections().length) {
                LevelChunkSection s = chunk.getSection(index);
                may = !s.hasOnlyAir() && s.maybeHas(state -> state.getBlock() instanceof AbstractCauldronBlock);
            }
        }
        cauldronMemoSection = section;
        cauldronMemoTick = tick;
        cauldronMemoAnswer = may;
        return may;
    }

    // =========================================================================
    // Ladder shortcut
    // =========================================================================

    /**
     * Links a floor to a ladder at its height or one rung below it. The flood
     * fill extends the ladder vertically after this entry is queued. Movement
     * through these nodes is handled by {@link MoveToCommand}.
     *
     * @return the rung linked to {@code cur} (for the caller's "did this
     *         direction resolve" check), or {@code null} if there is no
     *         ladder within reach.
     */
    private static NavNode scanLadderNeighbour(Level level, Map<Long, NavNode> nodes, Queue<NavNode> queue,
                                               NavNode cur, int nx, int nz, int minY, int maxY) {
        int entryY = Integer.MIN_VALUE;
        for (int ny = cur.feetY; ny >= cur.feetY - 1 && ny >= minY; ny--) {
            if (isClimbableRung(level, nx, ny, nz, maxY)) { entryY = ny; break; }
        }
        if (entryY == Integer.MIN_VALUE) return null;
        // Stepping down onto a rung, or up off it, happens at the floor's
        // height inside the ladder column.
        if (!hasStepHeadroom(level, cur.x, cur.surfaceY, cur.z, cur.standY,
                nx, entryY - 1, nz, entryY)) return null;

        NavNode entry = obtainLadderNode(level, nodes, queue, nx, entryY, nz);
        cur.addNeighbour(entry);
        entry.addNeighbour(cur);
        return entry;
    }

    private static NavNode obtainLadderNode(Level level, Map<Long, NavNode> nodes,
                                            Queue<NavNode> queue, int x, int y, int z) {
        long key = NavNode.key(x, y, z);
        NavNode rung = nodes.get(key);
        if (rung == null) {
            rung = makeClimbNode(level, x, z, y);
            nodes.put(key, rung);
            record(rung);
            queue.add(rung);
        }
        return rung;
    }

    private static boolean isLadder(Level level, int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z)).is(Blocks.LADDER);
    }

    private static boolean isClimbableRung(Level level, int x, int y, int z, int maxY) {
        return y + 1 <= maxY && isLadder(level, x, y, z)
                && isPassable(level, x, y, z)
                && isPassable(level, x, y + 1, z);
    }

    private static NavNode makeClimbNode(Level level, int x, int z, int feetY) {
        BlockState rung = level.getBlockState(new BlockPos(x, feetY, z));
        boolean ladderFloor = isSolidSurface(level, x, feetY - 1, z);
        return new NavNode(x, z, feetY - 1, rung.getBlock(),
                false, true, false, ladderFloor);
    }

    // =========================================================================
    // Deep-water shortcut
    // =========================================================================

    /**
     * Two distinct edges, both gated on the ordinary ±1-step scan already
     * having failed for this neighbour column:
     *
     * <ul>
     *   <li>dry → water: a genuine plunge of more than 1 block into water at
     *       least 2 deep (survivable). One-way — {@code cur} is linked to the
     *       landing but not back, matching the plain fact that you cannot
     *       climb the drop in reverse. A route back out only exists if the
     *       water node's own neighbour scan (run later from the BFS queue,
     *       through this same method or the ordinary dry-surface path above)
     *       finds a shore or ladder — i.e. a real climb-out, never fabricated.
     *   <li>water → water: two already-wet nodes at about the same depth,
     *       linked normally in both directions so a lake can be swum across
     *       rather than only ever entered.
     * </ul>
     */
    private static void tryLinkWaterNeighbour(Level level, Map<Long, NavNode> nodes, Queue<NavNode> queue,
                                              NavNode cur, int nx, int nz, int minY, int maxY) {
        if (cur.waterDrop) {
            linkWaterToWater(level, nodes, queue, cur, nx, nz, minY, maxY);
        } else {
            linkDryToWaterDrop(level, nodes, queue, cur, nx, nz, minY, maxY);
        }
    }

    private static void linkDryToWaterDrop(Level level, Map<Long, NavNode> nodes, Queue<NavNode> queue,
                                           NavNode cur, int nx, int nz, int minY, int maxY) {
        int from  = cur.feetY - MIN_WATER_DROP; // "more than 1 block" per spec
        int floor = Math.max(minY, cur.feetY - MAX_WATER_SCAN);
        for (int ny = from; ny >= floor; ny--) {
            if (!isDeepWaterLanding(level, nx, ny, nz)) continue;
            NavNode landing = obtainWaterNode(level, nodes, queue, nx, nz, ny);
            cur.addNeighbour(landing);
            return;
        }
    }

    private static void linkWaterToWater(Level level, Map<Long, NavNode> nodes, Queue<NavNode> queue,
                                         NavNode cur, int nx, int nz, int minY, int maxY) {
        int from  = Math.min(maxY, cur.feetY + 1); // allow the neighbour's surface to sit a touch higher
        int floor = Math.max(minY, cur.feetY - MAX_WATER_SCAN);
        for (int ny = from; ny >= floor; ny--) {
            if (!isDeepWaterLanding(level, nx, ny, nz)) continue;
            NavNode nb = obtainWaterNode(level, nodes, queue, nx, nz, ny);
            cur.addNeighbour(nb);
            nb.addNeighbour(cur);
            return;
        }
    }

    private static NavNode obtainWaterNode(Level level, Map<Long, NavNode> nodes, Queue<NavNode> queue,
                                           int x, int z, int feetY) {
        long key = NavNode.key(x, feetY, z);
        NavNode node = nodes.get(key);
        if (node == null) {
            node = makeWaterNode(level, x, z, feetY);
            nodes.put(key, node);
            record(node);
            queue.add(node);
        }
        return node;
    }

    /** At least 2 deep: the landing block and the one under it are both water. */
    private static boolean isDeepWaterLanding(Level level, int x, int y, int z) {
        return isWater(level, x, y, z) && isWater(level, x, y - 1, z);
    }

    private static boolean isWater(Level level, int x, int y, int z) {
        return level.getFluidState(new BlockPos(x, y, z)).is(FluidTags.WATER);
    }

    private static NavNode makeWaterNode(Level level, int x, int z, int feetY) {
        BlockState surf = level.getBlockState(new BlockPos(x, feetY, z));
        return new NavNode(x, z, feetY - 1, surf.getBlock(), false, false, true);
    }

    /**
     * True when the block is suitable as a normal path surface.
     *
     * <p>Leaves are intentionally excluded here, but not made passable in
     * {@link #isPassable}. That keeps their real collision/standing behaviour
     * intact while preventing tree canopies from becoming long-range path nodes.
     * Door blocks are excluded — they are treated as passable air for nav purposes.
     *
     * <p>{@link MoveToCommand} asks the same question about the block under a
     * ladder rung, so a rung with nothing under it means the same there as in
     * {@link NavNode#ladderFloor}.
     */
    static boolean isSolidSurface(Level level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        // Plain water (and kelp, seagrass...) is no floor, but a waterlogged
        // slab, stair or trapdoor still has its solid part to stand on, and
        // vanilla walks it exactly like the dry block. Rejecting every fluid
        // left sugar cane walkways built from waterlogged slabs with no nodes.
        if (!state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()) return false;
        // The ladder's thin collision shape is a wall attachment, not a floor.
        // Its rungs are represented separately by climb nodes.
        if (state.is(Blocks.LADDER)) return false;
        if (state.getBlock() instanceof DoorBlock) return false;
        if (state.getBlock() instanceof FenceGateBlock) return false;
        // Thin barriers are not usable floors. A pane or iron bar has an empty
        // cell above it, but vanilla navigation cannot step up onto its narrow
        // top. Treating it as a surface creates a false route over the barrier.
        if (state.getBlock() instanceof IronBarsBlock) return false;
        // Fences and walls are 1.5 blocks tall: entities cannot step onto them,
        // so a node on top would be a lie the pathfinder acts on — villagers
        // wedge against the fence forever trying to climb a route that only
        // exists in the graph.
        if (state.getBlock() instanceof FenceBlock) return false;
        if (state.getBlock() instanceof WallBlock) return false;
        // A chair is furniture to sit on, not a step: its back rises above a
        // block, so a node on top of it is the same lie as a fence top. With
        // no node there, routes go around it and villagers sit from beside it.
        if (state.getBlock() instanceof WoodenChairBlock) return false;
        // ZalithYT's piano bench: the same 9 px seat, and a fence post to villagers like the chairs.
        if (state.getBlock() instanceof ZalithPianoBenchBlock) return false;
        // An open trapdoor is a thin plate standing on one edge of its cell;
        // its collision shape is full height, so it read as a solid block to
        // stand on. Set in a floor it is a hole.
        if (isOpenTrapdoor(state)) return false;
        // A carpet, or a closed trapdoor lying on the floor, is not a floor of
        // its own: the block under it is. Counting it put every tile on a
        // carpet one block above the room's real floor — above the bed and
        // chest standing on that same floor.
        if (floorCoveringHeight(level, x, y, z) >= 0) return false;
        // A pressure plate has no collision at all — the feet go straight
        // through it to the block below, which is the floor. Vanilla still
        // forces blocksMotion() on for it, so the catch-all below took it.
        if (state.getBlock() instanceof BasePressurePlateBlock) return false;
        if (state.is(BlockTags.LEAVES)) return false;
        // Standing on a hazard vanilla will not walk into is the same lie as
        // routing through one; see isVanillaImpassable.
        if (isVanillaImpassable(state)) return false;
        return state.blocksMotion() || !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * True when a block can be walked through (head/feet space).
     * Doors and fence gates are always treated as passable regardless of their
     * open/closed state — {@code RouteDoorHandler} opens them on approach and
     * closes them again behind the villager. Fences and walls stay impassable,
     * which is what makes a fenced farm/ranch enclosure enterable only through
     * its gate.
     */
    public static boolean isPassable(Level level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (isVanillaImpassable(state)) return false;
        if (state.getBlock() instanceof DoorBlock) return true;
        if (state.getBlock() instanceof FenceGateBlock) return true;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) return false;
        // The same plate leaves the middle of the cell clear for a 0.6-wide
        // villager, and vanilla navigation walks past it too.
        if (isOpenTrapdoor(state)) return true;
        // A floor covering is where the feet go, a step of a sixteenth or so up.
        if (floorCoveringHeight(level, x, y, z) >= 0) return true;
        // LadderBlock has a thin collision shape, so the generic shape test
        // below rejects it even though an entity can occupy and climb its cell.
        if (state.is(Blocks.LADDER)) return true;
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Whether replacing a block can change the graph's walkable nodes or edges.
     * Plants with no collision or fluid (ordinary crops included) leave the
     * graph unchanged; they still need their separate farm/knowledge updates.
     */
    public static boolean blockChangeAffectsNavigation(Level level, BlockPos pos,
                                                        BlockState before, BlockState after) {
        if (before.equals(after)) return false;
        return !isNavigationNeutral(level, pos, before)
                || !isNavigationNeutral(level, pos, after);
    }

    private static boolean isNavigationNeutral(Level level, BlockPos pos, BlockState state) {
        return !state.blocksMotion()
                && state.getFluidState().isEmpty()
                && state.getCollisionShape(level, pos).isEmpty()
                && !isVanillaImpassable(state)
                && !(state.getBlock() instanceof DoorBlock)
                && !(state.getBlock() instanceof FenceGateBlock)
                && !(state.getBlock() instanceof TrapDoorBlock)
                && !state.is(Blocks.LADDER);
    }

    /**
     * Blocks with no collision that vanilla navigation refuses to walk through
     * anyway.
     *
     * <p>This graph decides passability from collision geometry alone, but the
     * actual walking is done by vanilla's own pathfinder, which classifies some
     * blocks by identity and gives them a malus of -1: not "expensive", but
     * "never". A sweet berry bush and a block of powder snow both have an empty
     * collision shape, so without this the graph promises a route straight
     * through them and vanilla quietly refuses to take it — which surfaces as a
     * villager standing still until {@code MoveToCommand} calls it stuck,
     * re-paths, gets teleported, and finally blacklists a destination it could
     * have reached the long way round.
     *
     * <p>Neither block occurs on a superflat world, which is why every existing
     * scenario is blind to this and why it only shows up in taiga and snowy
     * terrain.
     */
    /** True unless the step from {@code cur} to the neighbour crosses a door or gate side-on. */
    private static boolean isAlongPortalAxis(Level level, NavNode cur, int nx, int nFeetY, int nz) {
        Direction.Axis step = nx != cur.x ? Direction.Axis.X : Direction.Axis.Z;
        if (cur.portal) {
            Direction.Axis axis = portalAxis(level.getBlockState(new BlockPos(cur.x, cur.feetY, cur.z)));
            if (axis != null && axis != step) return false;
        }
        Direction.Axis axis = portalAxis(level.getBlockState(new BlockPos(nx, nFeetY, nz)));
        return axis == null || axis == step;
    }

    /** The axis a door or fence gate is walked through along, or {@code null} for anything else. */
    @javax.annotation.Nullable
    private static Direction.Axis portalAxis(BlockState state) {
        if (state.getBlock() instanceof DoorBlock) return state.getValue(DoorBlock.FACING).getAxis();
        if (state.getBlock() instanceof FenceGateBlock) return state.getValue(FenceGateBlock.FACING).getAxis();
        return null;
    }

    private static boolean isOpenTrapdoor(BlockState state) {
        return state.getBlock() instanceof TrapDoorBlock && state.getValue(TrapDoorBlock.OPEN);
    }

    /** Thickest collision a floor covering may have: a closed trapdoor is 3/16. */
    private static final double MAX_COVERING_HEIGHT = 0.25;

    /**
     * How thick the floor covering in this cell is, or −1 if it holds none.
     *
     * <p>A floor covering is a carpet or trapdoor whose collision is a thin
     * plate at the very bottom of its cell, lying on a real walking surface.
     * The surface below it is the floor; the covering is only how high the
     * feet sit on it. Decided by the actual collision shape, not the variant,
     * so for a trapdoor:
     * <ul>
     *   <li>closed, bottom half, on a floor — a covering;</li>
     *   <li>closed, top half — a plate at the top of the cell with open space
     *       under it, so a floor of its own;</li>
     *   <li>closed, bottom half, over air — nothing under it to be the floor,
     *       so it stays a floor of its own;</li>
     *   <li>open — a hole, handled by {@link #isOpenTrapdoor}.</li>
     * </ul>
     * A carpet on something that is not itself a floor (another covering, say)
     * likewise stays its own surface.
     */
    private static double floorCoveringHeight(Level level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CarpetBlock || state.getBlock() instanceof TrapDoorBlock)) return -1;
        if (!state.getFluidState().isEmpty() || isOpenTrapdoor(state)) return -1;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return -1;
        if (shape.min(Direction.Axis.Y) > 1.0E-4) return -1;
        double height = shape.max(Direction.Axis.Y);
        if (height > MAX_COVERING_HEIGHT + 1.0E-4) return -1;
        return isSolidSurface(level, x, y - 1, z) ? height : -1;
    }

    /** True when the step from {@code cur} to the neighbour walks through an open trapdoor's plate. */
    private static boolean crossesTrapdoorPlate(Level level, NavNode cur, int nx, int nFeetY, int nz) {
        Direction step = nx > cur.x ? Direction.EAST : nx < cur.x ? Direction.WEST
                : nz > cur.z ? Direction.SOUTH : Direction.NORTH;
        int lo = Math.min(cur.feetY, nFeetY);
        int hi = Math.max(cur.feetY, nFeetY) + 1;
        for (int y = lo; y <= hi; y++) {
            if (hasTrapdoorPlateOn(level, cur.x, y, cur.z, step)) return true;
            if (hasTrapdoorPlateOn(level, nx, y, nz, step.getOpposite())) return true;
        }
        return false;
    }

    /** True when an open trapdoor in this cell has its plate on the given side. */
    private static boolean hasTrapdoorPlateOn(Level level, int x, int y, int z, Direction side) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        // Opened, the plate swings to the edge opposite the way it faces.
        return isOpenTrapdoor(state) && state.getValue(TrapDoorBlock.FACING).getOpposite() == side;
    }

    private static boolean isVanillaImpassable(BlockState state) {
        return state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW)
                || isHazard(state);
    }

    /**
     * Blocks that hurt to walk through or stand on. Fire has no collision, so
     * the graph routed through it; a campfire is slab height, so villagers
     * stood on top of lit ones; magma and cactus read as ordinary floor.
     * Campfires count lit or not: lighting one by hand does not refresh the graph.
     */
    public static boolean isHazard(BlockState state) {
        return state.is(BlockTags.FIRE)
                || state.is(BlockTags.CAMPFIRES)
                || state.getBlock() instanceof com.alex.test.block.TravelerCampfireBlock
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.WITHER_ROSE);
    }

    /**
     * The graph feet cell an entity is standing in, for looking up its node.
     * Nodes put the feet one block above the surface block, but an entity on a
     * bottom slab, a stair's lower step or a carpet stands partway inside that
     * surface block, so its {@code blockPosition()} is one lower than its node.
     * A floor covering is the exception: its node's feet cell is the covering's
     * own cell, which the entity's feet are in.
     */
    public static BlockPos feetCell(Entity entity) {
        BlockPos cell = BlockPos.containing(entity.getX(), Math.ceil(entity.getY() - 0.01), entity.getZ());
        BlockPos below = cell.below();
        if (floorCoveringHeight(entity.level(), below.getX(), below.getY(), below.getZ()) >= 0) return below;
        return cell;
    }

    /** How far below an airborne body's feet cell {@link #routeStart} looks for the tile under it. */
    private static final int AIR_START_DEPTH = 4;

    /**
     * Where a route for {@code entity} starts. On the ground (or in water, or with a tile at the feet
     * cell, as on a ladder) its {@link #feetCell}. In the air, J1 (10-18, {@link WalkFeel#airStart}):
     * the highest tile in its column at or below the body. Mid-jump the feet cell is up to two blocks
     * over the floor, and the start snap then preferred a tile one block up beside it: a bard asked
     * for a new route at the top of a jump started it on the ledge to the west, fell back down and
     * walked into that ledge without jumping. No tile in the column: the feet cell, as before.
     */
    public static BlockPos routeStart(NavGraph graph, Entity entity) {
        BlockPos cell = feetCell(entity);
        if (!WalkFeel.airStart || graph == null || entity.onGround() || entity.isInWaterOrBubble()) return cell;
        if (graph.nodeAt(cell.getX(), cell.getY(), cell.getZ()) != null) return cell;
        for (int y = cell.getY() - 1; y >= cell.getY() - AIR_START_DEPTH; y--) {
            NavNode node = graph.nodeAt(cell.getX(), y, cell.getZ());
            if (node != null && node.standY <= entity.getY() + 0.05) return node.feetPos();
        }
        return cell;
    }

    /**
     * The height an entity stands at in this graph feet cell: the top of the
     * block below it, raised by any carpet or floor trapdoor lying on that block.
     */
    public static double standY(Level level, BlockPos feet) {
        return standY(level, feet.getX(), feet.getY() - 1, feet.getZ());
    }

    /** True when an entity standing on {@code node} has room for its full height right now. */
    public static boolean hasStandingRoom(Level level, NavNode node) {
        return hasStandingRoom(level, node.x, node.surfaceY, node.z);
    }

    public static boolean isDryWalkable(Level level, BlockPos feet) {
        return isPassable(level, feet.getX(), feet.getY(), feet.getZ())
                && isPassable(level, feet.getX(), feet.getY() + 1, feet.getZ())
                && level.getFluidState(feet).isEmpty()
                && level.getFluidState(feet.above()).isEmpty()
                && level.getFluidState(feet.below()).isEmpty();
    }

    /**
     * True when a villager standing here has somewhere safe to step.
     *
     * <p>This used to veto a cell if <em>any</em> neighbour was an unlanded
     * drop, which quietly made ordinary stairs unusable: every step of a
     * one-wide staircase has open air on both sides, so the fill climbed
     * exactly one step and stopped. A staircase had to be three blocks wide
     * before its middle lane worked — and players build one-wide stairs
     * constantly, which is why guards could not reach posts above or below
     * the Town Hall.
     *
     * <p>What the check is actually for is refusing a precarious perch: a
     * pillar top or cliff edge with nothing but a fall in every direction.
     * So it now asks for at least one safe way onward instead of demanding
     * that nothing nearby is a drop. Villagers move along graph edges rather
     * than wandering freely, so a drop beside a step they never step into is
     * not a hazard, while a perch with no safe neighbour still has no node.
     */
    private static boolean isLedgeSafe(Level level, int x, int feetY, int z, int minY, int maxY) {
        // Room to walk out sideways at this node's own standing height -- which
        // under a slab ceiling is less than two full blocks.
        double headTop = standY(level, x, feetY - 1, z) + ENTITY_HEIGHT;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int nx = x + dir.getStepX();
            int nz = z + dir.getStepZ();
            if (!isClearUpTo(level, nx, feetY, nz, headTop)) continue;
            if (hasReachableLanding(level, feetY, nx, nz, minY, maxY)) return true;
            if (isClimbableRung(level, nx, feetY, nz, maxY)
                    || (feetY - 1 >= minY
                    && isClimbableRung(level, nx, feetY - 1, nz, maxY))) return true;
            // A drop with no floor within 2 blocks is still safe if it lands
            // in deep water — that is precisely the shortcut this class adds,
            // so the same veto that protects against a fall into a pit must
            // not also veto the ledge a villager is meant to jump from. Without
            // this, no candidate node could ever exist directly overlooking a
            // water-drop landing, and dry→water edges would be unreachable
            // from any real node regardless of how the edge itself is built.
            if (hasDeepWaterLandingBelow(level, feetY, nx, nz, minY)) return true;
        }
        return false;
    }

    private static boolean hasReachableLanding(Level level, int fromFeetY,
                                               int nx, int nz,
                                               int minY, int maxY) {
        for (int ny = fromFeetY; ny >= fromFeetY - 2; ny--) {
            if (ny < minY || ny > maxY) continue;
            if (!isSolidSurface(level, nx, ny, nz)) continue;
            int feetY = ny + 1;
            if (feetY + 1 > maxY) continue;
            if (!hasStandingRoom(level, nx, ny, nz)) continue;
            return true;
        }
        return false;
    }

    private static boolean hasDeepWaterLandingBelow(Level level, int fromFeetY, int nx, int nz, int minY) {
        int floor = Math.max(minY, fromFeetY - MAX_WATER_SCAN);
        for (int ny = fromFeetY; ny >= floor; ny--) {
            if (isDeepWaterLanding(level, nx, ny, nz)) return true;
        }
        return false;
    }

    // =========================================================================
    // Lookup (thread-safe — immutable after build)
    // =========================================================================

    /** Nearest node to {@code pos} within {@code maxRadius} XZ blocks. */
    public NavNode nearest(BlockPos pos, int maxRadius) {
        NavNode best   = null;
        int     bestXZ = maxRadius * maxRadius + 1;
        int     bestDY = Integer.MAX_VALUE;
        int     px = pos.getX();
        int     py = pos.getY();
        int     pz = pos.getZ();
        int     radiusSq = maxRadius * maxRadius;

        for (int dz = -maxRadius; dz <= maxRadius; dz++) {
            for (int dx = -maxRadius; dx <= maxRadius; dx++) {
                int xzD = dx * dx + dz * dz;
                if (xzD > radiusSq || xzD > bestXZ) continue;

                List<NavNode> column = xzNodes.get(NavNode.xzKey(px + dx, pz + dz));
                if (column == null) continue;

                for (NavNode n : column) {
                    int dyD = Math.abs(n.feetY - py);
                    if (xzD < bestXZ || (xzD == bestXZ && dyD < bestDY)) {
                        bestXZ = xzD;
                        bestDY = dyD;
                        best = n;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Nearest node within {@code xzRadius} XZ blocks <em>and</em> {@code yRadius}
     * blocks vertically. Use this instead of {@link #nearest(BlockPos, int)} whenever
     * multi-floor structures (e.g. caves beneath a bed) could produce a node at the
     * right XZ but wrong Y.
     *
     * <p>Ranks by horizontal distance first, which suits finding a spot to work
     * a block from. For where a route starts or ends, use {@link #nearestOnFloor}
     * or the routable lookups, which keep to the floor {@code pos} is on.
     */
    public NavNode nearest(BlockPos pos, int xzRadius, int yRadius) {
        return nearestMatching(pos, xzRadius, yRadius, node -> true, false);
    }

    /** As {@link #nearest(BlockPos, int, int)}, preferring the floor {@code pos} is on; see {@link #nearestMatching}. */
    public NavNode nearestOnFloor(BlockPos pos, int xzRadius, int yRadius) {
        return nearestMatching(pos, xzRadius, yRadius, node -> true, true);
    }

    /**
     * Nearest ordinary path node, excluding furniture/storage/water recovery
     * nodes. Use this for destinations; avoided nodes should only be used as
     * emergency start nodes when a villager is already standing on one.
     */
    public NavNode nearestNonAvoided(BlockPos pos, int xzRadius, int yRadius) {
        return nearestMatching(pos, xzRadius, yRadius, node -> !node.isAvoidedSurface(), true);
    }

    /**
     * Nearest node ordinary routing may use — {@link NavNode#isRoutable()}.
     * Prefer this over {@link #nearestNonAvoided} for anything a villager is
     * meant to walk to: it also rejects the deep-water landings, which are
     * reachable on paper and unreachable on foot.
     */
    public NavNode nearestRoutable(BlockPos pos, int xzRadius, int yRadius) {
        return nearestRoutable(pos, xzRadius, yRadius, node -> true);
    }

    /** As above, with a caller-supplied filter for temporarily occupied cells. */
    public NavNode nearestRoutable(BlockPos pos, int xzRadius, int yRadius,
                                   java.util.function.Predicate<NavNode> acceptable) {
        return nearestMatching(pos, xzRadius, yRadius,
                node -> node.isRoutable() && acceptable.test(node), true);
    }

    /**
     * The shared lookup behind the Y-bounded {@code nearest*} methods.
     *
     * <p>With {@code preferSameFloor}, a node within {@value #SAME_FLOOR_DY}
     * block of {@code pos} vertically beats any node further up or down,
     * however close that one is sideways; only then does horizontal distance
     * decide, and height breaks ties.
     *
     * <p>Route lookups used to be horizontal distance first. In a house whose
     * storeys share one layer of blocks — two blocks of room, one of floor —
     * the upper floor is exactly three blocks up, inside every three-block snap
     * window, so the node straight overhead beat a ground-floor node one step
     * to the side. A villager at the foot of the stairs, or a goal beside the
     * door, landed on the storey above: the villager walked down, turned round,
     * climbed back up and stood over the door.
     */
    private NavNode nearestMatching(BlockPos pos, int xzRadius, int yRadius,
                                    java.util.function.Predicate<NavNode> accept,
                                    boolean preferSameFloor) {
        NavNode best          = null;
        boolean bestSameFloor = false;
        int     bestXZ        = Integer.MAX_VALUE;
        int     bestDY        = Integer.MAX_VALUE;
        int     px = pos.getX();
        int     py = pos.getY();
        int     pz = pos.getZ();
        int     radiusSq = xzRadius * xzRadius;

        for (int dz = -xzRadius; dz <= xzRadius; dz++) {
            for (int dx = -xzRadius; dx <= xzRadius; dx++) {
                int xzD = dx * dx + dz * dz;
                if (xzD > radiusSq) continue;
                // Nothing further out can beat a same-floor node already found.
                if (bestSameFloor && xzD > bestXZ) continue;

                List<NavNode> column = xzNodes.get(NavNode.xzKey(px + dx, pz + dz));
                if (column == null) continue;

                for (NavNode n : column) {
                    int dy = Math.abs(n.feetY - py);
                    if (dy > yRadius || !accept.test(n)) continue;

                    boolean sameFloor = !preferSameFloor || dy <= SAME_FLOOR_DY;
                    boolean better = best == null
                            || (sameFloor != bestSameFloor ? sameFloor
                            : xzD < bestXZ || (xzD == bestXZ && dy < bestDY));
                    if (better) {
                        best          = n;
                        bestSameFloor = sameFloor;
                        bestXZ        = xzD;
                        bestDY        = dy;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Raider plan bug 5 (10-19, {@link WalkFeel#ironDoors}): feet-cell keys ({@link NavNode#key}) of
     * the iron doors (doors no hand opens) shut right now. A villager's route treats them as walls,
     * as Minecraft's villagers do; one held open by redstone is walked through. Redstone changes no
     * map tile, so the doors are looked at instead: once a second, on the server thread
     * ({@link #refreshShutIronDoors}). Replaced whole, so a route search on a worker thread may read it.
     */
    private volatile it.unimi.dsi.fastutil.longs.LongSet shutIronDoors = it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET;
    /** The map's iron door tiles, found again whenever {@link #version} moved. Server thread only. */
    private long[] ironDoorKeys = new long[0];
    private long ironDoorsVersion = -1L;

    /** The switch as the reachability cells were last grouped under it (bug 23). Server thread only. */
    private boolean ironDoorsGroupedOn = WalkFeel.ironDoors;

    /** The iron doors shut at the last look (empty with none, or before the first look). */
    public it.unimi.dsi.fastutil.longs.LongSet shutIronDoors() { return shutIronDoors; }

    /** Bug 23: true for an iron door tile shut at the last look, while {@link WalkFeel#ironDoors} is on. */
    boolean isShutIronDoor(NavNode node) {
        return WalkFeel.ironDoors && shutIronDoors.contains(NavNode.key(node.x, node.feetY, node.z));
    }

    /**
     * Once a second from the village tick: which of the map's iron doors are shut now. Server thread.
     * Bug 23 (10-19): a door that opened or shut, or the switch flipped, regroups the reachability
     * cells round it, so "can I get there" agrees with the route search at once.
     */
    public void refreshShutIronDoors(Level level) {
        if (ironDoorsVersion != version) {
            ironDoorsVersion = version;
            it.unimi.dsi.fastutil.longs.LongArrayList keys = new it.unimi.dsi.fastutil.longs.LongArrayList();
            for (NavNode node : nodes.values()) {
                if (node.portalKind == NavNode.DOOR_LOCKED) keys.add(NavNode.key(node.x, node.feetY, node.z));
            }
            ironDoorKeys = keys.toLongArray();
        }
        it.unimi.dsi.fastutil.longs.LongSet before = shutIronDoors;
        boolean switchFlipped = ironDoorsGroupedOn != WalkFeel.ironDoors;
        ironDoorsGroupedOn = WalkFeel.ironDoors;
        if (ironDoorKeys.length == 0) {
            if (!before.isEmpty()) {
                shutIronDoors = it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET;
                regroupRoundDoors(before);
            }
            return;
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet shut = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (long key : ironDoorKeys) {
            NavNode node = nodes.get(key);
            if (node == null) continue;
            at.set(node.x, node.feetY, node.z);
            // An unloaded door is left as the map has it (open to routes), as before this fix.
            if (!level.isLoaded(at)) continue;
            BlockState state = level.getBlockState(at);
            if (state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN)) shut.add(key);
        }
        shutIronDoors = shut.isEmpty() ? it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET
                : it.unimi.dsi.fastutil.longs.LongSets.unmodifiable(shut);
        // The doors that opened or shut since the last look; every shut one when the switch flipped.
        it.unimi.dsi.fastutil.longs.LongOpenHashSet flipped = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (long key : shut) if (switchFlipped || !before.contains(key)) flipped.add(key);
        for (long key : before) if (switchFlipped || !shut.contains(key)) flipped.add(key);
        if (!flipped.isEmpty()) regroupRoundDoors(flipped);
    }

    /**
     * Bug 23: regroups the cells of these door tiles and of every tile next to them (the steps into
     * a door start there), and redoes the piece map, as a failed jump does ({@link #markStepFailed}).
     */
    private void regroupRoundDoors(it.unimi.dsi.fastutil.longs.LongSet doorKeys) {
        Set<Long> touched = new HashSet<>();
        for (long key : doorKeys) {
            NavNode door = nodes.get(key);
            if (door == null) continue;
            touched.add(NavCells.cellKeyOf(door));
            for (NavNode nb : door.neighbours()) {
                if (nb != null) touched.add(NavCells.cellKeyOf(nb));
            }
        }
        if (touched.isEmpty()) return;
        cells.regroupCells(touched, System.currentTimeMillis());
        cells.rebuildMap();
        // Its own bump: the door tiles are the same, so they need not be looked for again.
        boolean doorsCurrent = ironDoorsVersion == version;
        version++;
        if (doorsCurrent) ironDoorsVersion = version;
        cells.markChanged(touched, version);
    }

    public Collection<NavNode> allNodes()        { return nodes.values(); }
    public int                 size()            { return nodes.size();   }
    public long                version()         { return version;        }
    public boolean             isEmpty()         { return nodes.isEmpty();}

    /**
     * Nodes in a square X/Z window and a Y window around a position. A column
     * may contain several floors, so use the per-column list rather than the
     * single preferred node in {@code xzIndex}.
     */
    public List<NavNode> nodesInBox(BlockPos center, int xzRadius, int yRadius) {
        List<NavNode> nearby = new ArrayList<>();
        forEachNodeInBox(center, xzRadius, yRadius, nearby::add);
        return nearby;
    }

    /**
     * Visits what {@link #nodesInBox} would return, in the same order, without
     * building the list. A wander's 81x81 box holds thousands of nodes, and
     * collecting them was most of the cost of picking one.
     */
    public void forEachNodeInBox(BlockPos center, int xzRadius, int yRadius,
                                 java.util.function.Consumer<NavNode> visitor) {
        if (xzRadius < 0 || yRadius < 0) return;
        int minX = Math.max(boundsMinX, center.getX() - xzRadius);
        int maxX = Math.min(boundsMaxX, center.getX() + xzRadius);
        int minZ = Math.max(boundsMinZ, center.getZ() - xzRadius);
        int maxZ = Math.min(boundsMaxZ, center.getZ() + xzRadius);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                List<NavNode> column = xzNodes.get(NavNode.xzKey(x, z));
                if (column == null) continue;
                for (NavNode node : column) {
                    if (Math.abs(node.feetY - center.getY()) <= yRadius) visitor.accept(node);
                }
            }
        }
    }

    public NavNode nodeAt(int x, int feetY, int z) {
        return nodes.get(NavNode.key(x, feetY, z));
    }

    public int getBoundsMinX() { return boundsMinX; }
    public int getBoundsMaxX() { return boundsMaxX; }
    public int getBoundsMinZ() { return boundsMinZ; }
    public int getBoundsMaxZ() { return boundsMaxZ; }
    public int getBoundsMinY() { return boundsMinY; }
    public int getBoundsMaxY() { return boundsMaxY; }

    public boolean isInsideBounds(BlockPos pos) {
        return pos.getX() >= boundsMinX && pos.getX() <= boundsMaxX
                && pos.getZ() >= boundsMinZ && pos.getZ() <= boundsMaxZ;
    }

    /** True when the XZ column has at least one node (any Y level). */
    public boolean isWalkableAtXZ(int x, int z) {
        return xzIndex.containsKey(NavNode.xzKey(x, z));
    }

    // =========================================================================
    // Debug
    // =========================================================================

    /**
     * The rebuild log's line. Unlike {@link #dumpStats} it reads no nodes:
     * walking all of them for a log line cost a few ms on every rebuild.
     * The feet-Y span only ever grows between full builds.
     */
    public String summary() {
        return String.format(Locale.ROOT, "NavGraph v%d: %d nodes | feetY[%d..%d]",
                version, nodes.size(), boundsMinY, boundsMaxY);
    }

    public String dumpStats() {
        if (nodes.isEmpty()) return "NavGraph: empty (v" + version + ")";
        int mnX = Integer.MAX_VALUE, mxX = Integer.MIN_VALUE;
        int mnZ = Integer.MAX_VALUE, mxZ = Integer.MIN_VALUE;
        int mnY = Integer.MAX_VALUE, mxY = Integer.MIN_VALUE;
        int transient_ = 0;
        for (NavNode n : nodes.values()) {
            if (n.x < mnX) mnX = n.x; if (n.x > mxX) mxX = n.x;
            if (n.z < mnZ) mnZ = n.z; if (n.z > mxZ) mxZ = n.z;
            if (n.surfaceY < mnY) mnY = n.surfaceY;
            if (n.surfaceY > mxY) mxY = n.surfaceY;
            if (n.isTransient()) transient_++;
        }
        return String.format("NavGraph v%d: %d nodes | X[%d..%d] Z[%d..%d] surfY[%d..%d] transient=%d",
                version, nodes.size(), mnX, mxX, mnZ, mxZ, mnY, mxY, transient_);
    }

    public String dumpSlice(int x1, int z1, int x2, int z2) {
        Set<Long> xzPresent = new HashSet<>(nodes.size() * 2);
        for (NavNode n : nodes.values()) xzPresent.add(NavNode.xzKey(n.x, n.z));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("NavGraph slice [%d,%d]→[%d,%d] (v%d):%n", x1, z1, x2, z2, version));
        for (int wz = z1; wz <= z2; wz++) {
            for (int wx = x1; wx <= x2; wx++)
                sb.append(xzPresent.contains(NavNode.xzKey(wx, wz)) ? 'N' : '.');
            sb.append('\n');
        }
        return sb.toString();
    }
}
