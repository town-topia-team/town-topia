package com.alex.test.village;

import com.alex.test.village.nav.Cell;
import com.alex.test.village.nav.CellTable;
import com.alex.test.village.nav.EdgeKind;
import com.alex.test.village.nav.Piece;
import com.alex.test.village.nav.PieceMap;
import com.alex.test.village.nav.TileAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The layer above a {@link NavGraph}'s tiles: the graph cut into cells of one
 * chunk section each, the tiles of each cell grouped into pieces, and the
 * small piece map that answers the three questions that used to walk every
 * tile after an edit.
 *
 * <p>Those three questions -- can this villager get there, is this door
 * connected to the Town Hall, did the edit cut something off -- each used to
 * be a pass over the whole graph, sliced over the ticks after every edit and
 * felt as a second of heavier ticks. Here an edit regroups the few cells it
 * touched and redoes the piece map, a few thousand pieces, inside its own
 * tick.
 *
 * <p>The grouping and the map live in {@code com.alex.test.village.nav} and
 * know nothing about Minecraft, so they are unit tested on their own; this
 * class only tells them how a {@link NavNode} and its edges look, which tiles
 * a cell holds, and where the roots are. Server thread only, like the graph
 * edits it follows.
 */
public final class NavCells {

    /**
     * The three rules of the three questions, as one edge kind per stored
     * edge. A step the walker cannot take does not count. Ordinary floor onto
     * a chest top, a bed or deep water is a step only the island sweep
     * follows. A jump a villager recently failed is a step the token questions
     * follow but "can I get there" does not. So is a step into an iron door shut right now (raider plan
     * bug 23, 10-19, {@link WalkFeel#ironDoors}): nobody behind it is reachable, yet the house keeps its
     * Town Hall access. Everything else is ordinary. One per cells (it reads this graph's shut doors).
     */
    private final TileAdapter<NavNode> adapter = new TileAdapter<>() {
        @Override public NavNode[] neighbours(NavNode tile) { return tile.neighbours(); }

        @Override public int kind(NavNode from, NavNode to, long now) {
            if (!from.canStepTo(to)) return EdgeKind.NONE;
            if (from.isRoutable() && !to.isRoutable()) return EdgeKind.ENTER_OFF;
            if (from.failedStepUntil(to, now) != 0L) return EdgeKind.FAILED;
            if (to.portalKind == NavNode.DOOR_LOCKED && graph.isShutIronDoor(to)) return EdgeKind.FAILED;
            return EdgeKind.ORDINARY;
        }

        @Override public long cellKey(NavNode tile) { return cellKeyOf(tile); }
        @Override public int scratch(NavNode tile) { return tile.groupScratch; }
        @Override public void setScratch(NavNode tile, int value) { tile.groupScratch = value; }
        @Override public Piece<NavNode> piece(NavNode tile) { return tile.piece; }
        @Override public void setPiece(NavNode tile, Piece<NavNode> piece) { tile.piece = piece; }
    };

    /** A failed jump that lapses at {@code until}; the cell is regrouped then. */
    private record Expiry(long until, NavNode from, NavNode to) {}

    private final NavGraph graph;
    private final CellTable<NavNode> table = new CellTable<>(adapter);
    private final PriorityQueue<Expiry> expiries =
            new PriorityQueue<>(Comparator.comparingLong(Expiry::until));

    /** Timing only: the last piece map build. */
    private long lastMapNanos;
    private int lastMapPieces;

    /** Progress of {@link #regroupAllSliced}; null between runs. */
    private List<Long> slicedKeys;
    private int slicedIndex;

    NavCells(NavGraph graph) {
        this.graph = graph;
    }

    /** The cell a tile at these coordinates belongs to: the chunk section its feet are in. */
    public static long cellKey(int x, int feetY, int z) {
        return SectionPos.asLong(x >> 4, feetY >> 4, z >> 4);
    }

    static long cellKeyOf(NavNode tile) {
        return cellKey(tile.x, tile.feetY, tile.z);
    }

    /** The live tiles of a cell, read off the graph's column index. */
    List<NavNode> tilesOf(long cellKey) {
        int cy = SectionPos.y(cellKey);
        int baseX = SectionPos.x(cellKey) << 4;
        int baseZ = SectionPos.z(cellKey) << 4;
        List<NavNode> tiles = new ArrayList<>();
        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                List<NavNode> column = graph.columnNodes(x, z);
                if (column == null) continue;
                for (NavNode tile : column) {
                    if ((tile.feetY >> 4) == cy && graph.isLive(tile)) tiles.add(tile);
                }
            }
        }
        return tiles;
    }

    void regroupCells(Collection<Long> cellKeys, long now) {
        for (long key : cellKeys) table.regroup(key, tilesOf(key), now);
    }

    void regroupAll(long now) {
        regroupCells(cellKeysWithTiles(), now);
    }

    /**
     * Regroups every cell, about {@code tileBudget} tiles per call; true when
     * done. For the sliced full build, where the grouping of a hundred and
     * sixty thousand tiles is spread over ticks like the fill before it.
     */
    boolean regroupAllSliced(int tileBudget, long now) {
        if (slicedKeys == null) {
            slicedKeys = new ArrayList<>(cellKeysWithTiles());
            slicedIndex = 0;
        }
        int done = 0;
        while (slicedIndex < slicedKeys.size() && done < tileBudget) {
            long key = slicedKeys.get(slicedIndex++);
            List<NavNode> tiles = tilesOf(key);
            table.regroup(key, tiles, now);
            done += Math.max(1, tiles.size());
        }
        if (slicedIndex < slicedKeys.size()) return false;
        slicedKeys = null;
        return true;
    }

    private Set<Long> cellKeysWithTiles() {
        Set<Long> keys = new HashSet<>();
        for (NavNode tile : graph.allNodes()) keys.add(cellKeyOf(tile));
        return keys;
    }

    /**
     * Redoes the piece map from every cell's pieces. The island root is the
     * tile at the Town Hall door the graph was built from; the access root is
     * the routable tile nearest the bell, looked up afresh each time, as the
     * flood used to.
     */
    PieceMap rebuildMap() {
        long started = System.nanoTime();
        NavNode islandRoot = graph.rootNode();
        NavNode accessRoot = graph.nearestRoutable(graph.origin(), 3, 3);
        PieceMap before = lastBuilt;
        PieceMap map = table.build(islandRoot, accessRoot);
        lastBuilt = map;
        lastChangedAccess = table.cellsChangedAccess(before, map);
        lastMapNanos = System.nanoTime() - started;
        lastMapPieces = table.pieceCount();
        return map;
    }

    /** The map as last built here, kept to tell which pieces changed access in the next build. */
    private PieceMap lastBuilt;
    private Set<Long> lastChangedAccess = Set.of();

    /** Cells of pieces whose Town Hall access flipped, either way, in the last build. */
    Set<Long> lastChangedAccess() {
        return lastChangedAccess;
    }

    /** Tiles of the pieces the last map build could not reach from the Town Hall. */
    List<NavNode> islandTiles() {
        List<NavNode> tiles = new ArrayList<>();
        for (Piece<NavNode> piece : table.islandPieces()) tiles.addAll(piece.members);
        return tiles;
    }

    long lastMapNanos() { return lastMapNanos; }
    int lastMapPieces() { return lastMapPieces; }
    int pieceCount() { return table.pieceCount(); }
    public int staleQueries() { return table.staleQueries(); }

    /** For the check command: every cell with its pieces. */
    public Collection<Cell<NavNode>> cells() { return table.cells(); }

    public boolean reaches(NavNode from, NavNode to) { return table.reaches(from, to); }
    public boolean accessible(NavNode tile) { return table.accessible(tile); }
    public boolean isIsland(NavNode tile) { return table.isIsland(tile); }

    boolean changedSince(long version, long[] cellKeys) {
        return table.changedSince(version, cellKeys);
    }

    boolean changedSince(long version, BlockPos center, int radiusXZ, int radiusY) {
        // Most asks come when nothing changed at all: no cell to look at, no list to make.
        if (table.unchangedSince(version)) return false;
        int minCX = (center.getX() - radiusXZ) >> 4, maxCX = (center.getX() + radiusXZ) >> 4;
        int minCZ = (center.getZ() - radiusXZ) >> 4, maxCZ = (center.getZ() + radiusXZ) >> 4;
        int minCY = (center.getY() - radiusY) >> 4, maxCY = (center.getY() + radiusY) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                for (int cy = minCY; cy <= maxCY; cy++) {
                    if (table.changedSince(version, SectionPos.asLong(cx, cy, cz))) return true;
                }
            }
        }
        return false;
    }

    void markChanged(Collection<Long> cellKeys, long version) {
        for (long key : cellKeys) table.markChanged(key, version);
    }

    /**
     * A villager could not make the jump {@code from -> to}. The edge's kind
     * changed, so the cell is regrouped and the map redone now, and again when
     * the verdict lapses ({@link #tickExpiry}).
     */
    void noteStepFailed(NavNode from, NavNode to, long untilMillis, long now) {
        regroupCells(List.of(cellKeyOf(from)), now);
        rebuildMap();
        expiries.add(new Expiry(untilMillis, from, to));
    }

    /** Regroups the cells of failed jumps that have lapsed; the cells it regrouped, empty if none. */
    Set<Long> tickExpiry(long nowMillis) {
        Set<Long> keys = null;
        while (!expiries.isEmpty() && expiries.peek().until() <= nowMillis) {
            Expiry expiry = expiries.poll();
            if (!graph.isLive(expiry.from())) continue;
            if (keys == null) keys = new HashSet<>();
            keys.add(cellKeyOf(expiry.from()));
        }
        if (keys == null) return Set.of();
        regroupCells(keys, nowMillis);
        rebuildMap();
        return keys;
    }
}
