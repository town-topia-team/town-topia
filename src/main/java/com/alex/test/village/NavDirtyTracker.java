package com.alex.test.village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import net.minecraft.core.BlockPos;

/**
 * The columns changed since a village's last {@link NavGraph} patch, how high
 * and low each change reached, and the debounce that decides when the next
 * patch is due.
 *
 * <p>Owned by {@link VillageManager}, which marks columns from block events and
 * asks once per tick whether to patch. The graph itself is patched by the
 * manager; this class only keeps the bookkeeping.
 */
final class NavDirtyTracker {

    /** Ticks to wait after the last nav-dirty event before patching (10 s). */
    private static final int DIRTY_REBUILD_COOLDOWN = 200;

    /**
     * Longest the graph may stay stale while blocks keep changing (10 s).
     *
     * <p>{@link #DIRTY_REBUILD_COOLDOWN} is a quiet-period debounce: every
     * change pushes the patch back another ten seconds, so that a player
     * laying a floor pays for one patch instead of one per block. The flaw is
     * that anything changing blocks steadily -- a player walling a field, a
     * miner cutting a shaft a block a second -- resets it forever and the graph
     * simply never catches up. The miner was the case that showed it: it dug
     * thirty blocks of corridor, none of which the village ever learned about,
     * and then stood at the mouth of its own tunnel unable to find anywhere to
     * stand.
     *
     * <p>Once the region has been dirty for ten seconds, patch even if
     * changes continue. A burst of changed columns must not patch early.
     */
    private static final int DIRTY_REBUILD_MAX_WAIT = 200;

    /**
     * Ticks a {@link #markSoon} change waits for its patch (1 s). A tree lands
     * whole in one tick, so the quiet wait buys nothing there; this second only
     * lets a burst of bonemeal share one patch.
     */
    private static final int SOON_REBUILD_TICKS = 20;

    /** Past this many changed chunks, {@link #boxes()} returns one box over all of them. */
    private static final int MAX_BOXES = 64;

    /** Per changed column: {lowest Y, highest Y, 1 if water was involved}. */
    private final Map<Long, int[]> dirtyXZ = new HashMap<>();

    /** Bounding box of the columns changed since the last patch. */
    private int dirtyMinX = Integer.MAX_VALUE, dirtyMaxX = Integer.MIN_VALUE;
    private int dirtyMinZ = Integer.MAX_VALUE, dirtyMaxZ = Integer.MIN_VALUE;

    private int dirtyCooldown = 0;

    /** Ticks the region has been dirty; see {@link #DIRTY_REBUILD_MAX_WAIT}. */
    private int dirtyAge = 0;

    /** Set when the debounce has fired; held until the patch runs. */
    private boolean due = false;

    /** Ticks until a {@link #markSoon} change is patched; 0 when none is waiting. */
    private int soonLeft = 0;

    /**
     * Records a changed block and restarts the quiet-period debounce.
     *
     * <p>The height matters: a tunnel dug at bedrock used to re-read the
     * surface above it too, because a changed column was a column from bedrock
     * to sky. The patch now takes a band around the heights marked here, and
     * the whole column only when water is involved.
     */
    void mark(int x, int y, int z, boolean water) {
        int[] range = dirtyXZ.get(NavNode.xzKey(x, z));
        if (range == null) {
            dirtyXZ.put(NavNode.xzKey(x, z), new int[] { y, y, water ? 1 : 0 });
        } else {
            if (y < range[0]) range[0] = y;
            if (y > range[1]) range[1] = y;
            if (water) range[2] = 1;
        }
        // The box the changes fall in is what decides whether the next patch
        // can be a local one. Tracking it here costs four comparisons and saves
        // re-reading ninety thousand columns.
        if (x < dirtyMinX) dirtyMinX = x;
        if (x > dirtyMaxX) dirtyMaxX = x;
        if (z < dirtyMinZ) dirtyMinZ = z;
        if (z > dirtyMaxZ) dirtyMaxZ = z;
        dirtyCooldown = DIRTY_REBUILD_COOLDOWN;
    }

    /**
     * As {@link #mark}, for a change that lands whole at once (a tree growing):
     * patched within {@link #SOON_REBUILD_TICKS}, not after the quiet wait. Until
     * then the map still offers the tiles a new trunk stands in, and a route
     * planned meanwhile walks into it. The first such change starts the count;
     * later ones join it, and ordinary changes meanwhile do not put it off.
     */
    void markSoon(int x, int y, int z, boolean water) {
        mark(x, y, z, water);
        if (soonLeft <= 0) soonLeft = SOON_REBUILD_TICKS;
    }

    /** Whether a column within {@code radius} of x, z (a square) is still waiting for its patch. */
    boolean isDirtyNear(int x, int z, int radius) {
        if (dirtyXZ.isEmpty()
                || x + radius < dirtyMinX || x - radius > dirtyMaxX
                || z + radius < dirtyMinZ || z - radius > dirtyMaxZ) {
            return false;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dirtyXZ.containsKey(NavNode.xzKey(x + dx, z + dz))) return true;
            }
        }
        return false;
    }

    /**
     * Keeps every column of a box dirty with the box's band and water flag,
     * for columns a patch could not read because their chunk was not loaded.
     * Does not restart the debounce: the patch stays due until they are done.
     */
    void keep(NavGraph.DirtyBox box) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                int[] range = dirtyXZ.get(NavNode.xzKey(x, z));
                if (range == null) {
                    dirtyXZ.put(NavNode.xzKey(x, z), new int[] { box.minY(), box.maxY(), box.water() ? 1 : 0 });
                } else {
                    if (box.minY() < range[0]) range[0] = box.minY();
                    if (box.maxY() > range[1]) range[1] = box.maxY();
                    if (box.water()) range[2] = 1;
                }
                if (x < dirtyMinX) dirtyMinX = x;
                if (x > dirtyMaxX) dirtyMaxX = x;
                if (z < dirtyMinZ) dirtyMinZ = z;
                if (z > dirtyMaxZ) dirtyMaxZ = z;
            }
        }
        due = !dirtyXZ.isEmpty();
    }

    /**
     * Puts the patch off for {@code ticks}, for a patch that could read
     * nothing because every box had a chunk that was not loaded. Without
     * this the patch would try, and log, every tick until the chunk came.
     */
    void retryIn(int ticks) {
        due = false;
        dirtyCooldown = Math.max(1, ticks);
        dirtyAge = 0;
        soonLeft = 0;
    }

    /**
     * Advances the debounce by one tick. True when the region has either gone
     * quiet or been dirty for too long, and the patch should run now.
     */
    boolean tickRebuildDue() {
        // Once due, stay due until clear(): the patch may have to wait a
        // tick for another village's (see NavWorkBudget).
        if (due) return !dirtyXZ.isEmpty();
        if (dirtyCooldown <= 0) return false;
        dirtyCooldown--;
        dirtyAge++;
        boolean quiet = dirtyCooldown == 0;
        boolean overdue = dirtyAge >= DIRTY_REBUILD_MAX_WAIT;
        boolean soon = soonLeft > 0 && --soonLeft == 0;
        due = (quiet || overdue || soon) && !dirtyXZ.isEmpty();
        return due;
    }

    /** True while a patch is pending; used by tests to wait one out. */
    boolean hasPendingRebuild() {
        return (dirtyCooldown > 0 || due) && !dirtyXZ.isEmpty();
    }

    /** True once at least one column has been marked since the last clear. */
    boolean hasDirtyBounds() {
        return dirtyMaxX >= dirtyMinX && dirtyMaxZ >= dirtyMinZ;
    }

    /**
     * The changed columns as one tight box per chunk, each with the band of
     * heights its columns were marked at.
     *
     * <p>Not one box around everything: a change at the farm and another at
     * the far end of the village made a box covering a quarter of the claim,
     * and the patch re-scanned all the untouched ground in between.
     * {@link NavGraph} adds the margin and merges boxes that end up touching.
     * Past {@link #MAX_BOXES} chunks the changes are so scattered that one box
     * over all of them is the simpler answer, as before.
     */
    List<NavGraph.DirtyBox> boxes() {
        Map<Long, List<Long>> byChunk = columnsByChunk();
        if (byChunk.isEmpty()) return List.of();
        if (byChunk.size() > MAX_BOXES) return List.of(boundsOf(dirtyXZ.keySet()));
        List<NavGraph.DirtyBox> boxes = new ArrayList<>(byChunk.size());
        for (List<Long> columns : byChunk.values()) boxes.add(boundsOf(columns));
        return boxes;
    }

    /**
     * The boxes to patch this tick: as {@link #boxes()}, but only as many as
     * hold about {@code maxNodes} tiles between them. The rest stay dirty for
     * the next tick ({@link #finish}).
     *
     * <p>What a patch costs follows the tiles it takes out and reads again.
     * Two or three chunks changed at once, or one chunk over a mine shaft
     * with five tiles to a column, made a single patch of 16 ms. Always
     * returns something to do: a chunk too big on its own is halved along its
     * longer side until a half fits.
     *
     * @param nodesAround how many tiles a patch of a box would re-read
     */
    List<NavGraph.DirtyBox> nextBatch(ToIntFunction<NavGraph.DirtyBox> nodesAround, int maxNodes) {
        Map<Long, List<Long>> byChunk = columnsByChunk();
        if (byChunk.isEmpty()) return List.of();
        if (byChunk.size() > MAX_BOXES) return boxes();

        List<NavGraph.DirtyBox> batch = new ArrayList<>();
        int total = 0;
        for (List<Long> columns : byChunk.values()) {
            NavGraph.DirtyBox box = boundsOf(columns);
            int count = nodesAround.applyAsInt(box);
            if (batch.isEmpty()) {
                while (count > maxNodes && columns.size() > 1) {
                    columns = firstHalf(columns, box);
                    box = boundsOf(columns);
                    count = nodesAround.applyAsInt(box);
                }
            } else if (total + count > maxNodes) {
                continue;
            }
            batch.add(box);
            total += count;
        }
        return batch;
    }

    private Map<Long, List<Long>> columnsByChunk() {
        Map<Long, List<Long>> byChunk = new HashMap<>();
        for (long column : dirtyXZ.keySet()) {
            int x = BlockPos.getX(column);
            int z = BlockPos.getZ(column);
            long chunk = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(column);
        }
        return byChunk;
    }

    /** The tight box around these columns, with the union of their bands. */
    private NavGraph.DirtyBox boundsOf(Iterable<Long> columns) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        boolean water = false;
        for (long column : columns) {
            int x = BlockPos.getX(column);
            int z = BlockPos.getZ(column);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
            int[] range = dirtyXZ.get(column);
            if (range == null) continue;
            if (range[0] < minY) minY = range[0];
            if (range[1] > maxY) maxY = range[1];
            if (range[2] != 0) water = true;
        }
        return new NavGraph.DirtyBox(minX, maxX, minZ, maxZ, minY, maxY, water);
    }

    /**
     * The columns in the lower half of {@code box} along its longer side.
     * {@code box} is tight around {@code columns}, so with more than one
     * column both halves hold at least one and the list always shrinks.
     */
    private static List<Long> firstHalf(List<Long> columns, NavGraph.DirtyBox box) {
        boolean alongX = box.maxX() - box.minX() >= box.maxZ() - box.minZ();
        int mid = alongX ? (box.minX() + box.maxX()) >> 1 : (box.minZ() + box.maxZ()) >> 1;
        List<Long> half = new ArrayList<>();
        for (long column : columns) {
            int at = alongX ? BlockPos.getX(column) : BlockPos.getZ(column);
            if (at <= mid) half.add(column);
        }
        return half;
    }

    /**
     * Forgets the columns inside the boxes just patched. With columns left
     * over the patch stays due, so the next tick takes the next batch.
     *
     * @return how many columns are still dirty
     */
    int finish(List<NavGraph.DirtyBox> ran) {
        dirtyXZ.keySet().removeIf(column -> {
            int x = BlockPos.getX(column);
            int z = BlockPos.getZ(column);
            for (NavGraph.DirtyBox box : ran) {
                if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) return true;
            }
            return false;
        });
        if (dirtyXZ.isEmpty()) {
            clear();
            return 0;
        }
        due = true;
        soonLeft = 0;
        dirtyMinX = dirtyMinZ = Integer.MAX_VALUE;
        dirtyMaxX = dirtyMaxZ = Integer.MIN_VALUE;
        for (long column : dirtyXZ.keySet()) {
            int x = BlockPos.getX(column);
            int z = BlockPos.getZ(column);
            if (x < dirtyMinX) dirtyMinX = x;
            if (x > dirtyMaxX) dirtyMaxX = x;
            if (z < dirtyMinZ) dirtyMinZ = z;
            if (z > dirtyMaxZ) dirtyMaxZ = z;
        }
        return dirtyXZ.size();
    }

    void clear() {
        dirtyXZ.clear();
        dirtyMinX = dirtyMinZ = Integer.MAX_VALUE;
        dirtyMaxX = dirtyMaxZ = Integer.MIN_VALUE;
        dirtyCooldown = 0;
        dirtyAge = 0;
        due = false;
        soonLeft = 0;
    }
}
