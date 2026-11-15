package com.alex.test.village.nav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cells of one map and the piece map built over them.
 *
 * <p>Every piece keeps a stable id for as long as it lives, and beside it the
 * list of pieces its links lead to, worked out once ({@link #resolve}) and
 * kept until something it depends on changes. Regrouping a cell replaces that
 * cell's pieces, resolves the new ones, and resolves again only the pieces
 * elsewhere whose links lead into the cell, found through a reverse index.
 * A build then reads the resolved lists straight into the piece map. So the
 * work an edit costs follows the pieces around it, not the size of the
 * village: the first version resolved every link in the village on every
 * build, eighty thousand of them, and that loop alone was 25 ms while the
 * code was still cold.
 */
public final class CellTable<T> {
    private final TileAdapter<T> adapter;
    private final Map<Long, Cell<T>> cells = new LinkedHashMap<>();
    private final Collection<Cell<T>> cellView = Collections.unmodifiableCollection(cells.values());

    /** Pieces by stable id; null where the id is free. */
    @SuppressWarnings("unchecked")
    private Piece<T>[] byId = (Piece<T>[]) new Piece[64];
    /** Per id: the pieces its links lead to, packed as {@code target << 2 | kind}, one entry per target with the lowest kind. */
    private int[][] resolved = new int[64][];
    private int idCount;
    private int[] freeIds = new int[16];
    private int freeCount;

    /** Pieces whose tile links lead into tiles of this cell: the ones to resolve again when it is regrouped. */
    private final Map<Long, Set<Piece<T>>> inbound = new HashMap<>();

    // Scratch for resolve(): a stamp per id in place of clearing an array per piece.
    private int[] mark = new int[64];
    private int[] bestKind = new int[64];
    private int[] targetBuffer = new int[64];
    private int stamp;

    private PieceMap map;
    private int pieceCount;
    private int staleQueries;

    public CellTable(TileAdapter<T> adapter) {
        this.adapter = adapter;
    }

    /**
     * Replaces the cell's pieces; an empty list leaves an empty cell record so
     * changedSince can still see it. The last build no longer describes the
     * map after this, so questions are stale until the next {@link #build}.
     */
    public void regroup(long cellKey, List<T> tiles, long now) {
        Cell<T> cell = cells.computeIfAbsent(cellKey, Cell::new);
        for (Piece<T> old : cell.pieces()) discard(old);
        pieceCount -= cell.pieces().size();
        List<Piece<T>> fresh = CellGrouper.group(cellKey, tiles, adapter, now);
        // Every new piece needs its id before any of them resolves, since
        // they link to each other within the cell.
        for (Piece<T> piece : fresh) {
            int id = allocateId();
            piece.setIndex(id);
            byId[id] = piece;
        }
        cell.replacePieces(fresh);
        pieceCount += fresh.size();
        for (Piece<T> piece : fresh) resolve(piece);
        // Pieces elsewhere whose links lead into this cell were pointing at
        // the old pieces; they find the new ones now. Copied first, because
        // resolving re-registers them in the same set.
        Set<Piece<T>> into = inbound.get(cellKey);
        if (into != null && !into.isEmpty()) {
            for (Piece<T> piece : new ArrayList<>(into)) {
                if (piece.index() >= 0 && piece.cellKey != cellKey) resolve(piece);
            }
        }
        map = null;
    }

    /** Frees a piece that a regroup replaced: its id, its resolved links, and its place in the reverse index. */
    private void discard(Piece<T> old) {
        int id = old.index();
        if (id < 0) return;
        for (Piece.Link<T> link : old.links) {
            Set<Piece<T>> set = inbound.get(adapter.cellKey(link.to()));
            if (set != null) set.remove(old);
        }
        old.setIndex(-1);
        byId[id] = null;
        resolved[id] = null;
        if (freeCount == freeIds.length) freeIds = Arrays.copyOf(freeIds, freeCount * 2);
        freeIds[freeCount++] = id;
    }

    private int allocateId() {
        if (freeCount > 0) return freeIds[--freeCount];
        int id = idCount++;
        if (id == byId.length) {
            int grown = id * 2;
            byId = Arrays.copyOf(byId, grown);
            resolved = Arrays.copyOf(resolved, grown);
            mark = Arrays.copyOf(mark, grown);
            bestKind = Arrays.copyOf(bestKind, grown);
        }
        return id;
    }

    /**
     * Works out which pieces this piece's links lead to, one entry per target
     * piece with the lowest kind, and registers the piece against every cell
     * its links lead into. A link whose tile has no piece yet (a cell not
     * grouped yet, during a full build) is registered too, so that grouping
     * that cell later resolves this piece again.
     */
    private void resolve(Piece<T> piece) {
        if (++stamp == Integer.MAX_VALUE) {
            Arrays.fill(mark, 0);
            stamp = 1;
        }
        int n = 0;
        for (Piece.Link<T> link : piece.links) {
            T to = link.to();
            inbound.computeIfAbsent(adapter.cellKey(to), ignored -> newIdentitySet()).add(piece);
            Piece<T> target = adapter.piece(to);
            if (target == null || target == piece) continue;
            int id = target.index();
            if (id < 0) continue;
            int kind = link.kind();
            if (mark[id] != stamp) {
                mark[id] = stamp;
                bestKind[id] = kind;
                if (n == targetBuffer.length) targetBuffer = Arrays.copyOf(targetBuffer, n * 2);
                targetBuffer[n++] = id;
            } else if (kind < bestKind[id]) {
                bestKind[id] = kind;
            }
        }
        int[] packed = new int[n];
        for (int i = 0; i < n; i++) {
            int id = targetBuffer[i];
            packed[i] = (id << 2) | bestKind[id];
        }
        resolved[piece.index()] = packed;
    }

    private static <T> Set<Piece<T>> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** null root = no root. Reads the resolved links of every live piece into a fresh piece map. */
    public PieceMap build(T islandRoot, T accessRoot) {
        int n = idCount;
        boolean[] live = new boolean[n];
        PieceMap.Builder builder = new PieceMap.Builder(n);
        for (int id = 0; id < n; id++) {
            if (byId[id] == null) continue;
            live[id] = true;
            int[] packed = resolved[id];
            if (packed == null) continue;
            for (int entry : packed) builder.link(id, entry >>> 2, entry & 3);
        }
        map = builder.build(rootIndex(islandRoot), rootIndex(accessRoot), live);
        return map;
    }

    /**
     * The cells of pieces whose accessibility differs between {@code before}
     * and {@code after}, by stable id. A piece whose id was reused since
     * {@code before} compares against the old piece's answer, which can
     * only add a cell that was regrouped anyway. Empty when either map is
     * null.
     */
    public Set<Long> cellsChangedAccess(PieceMap before, PieceMap after) {
        if (before == null || after == null) return Set.of();
        Set<Long> changed = null;
        int n = Math.min(before.pieceCount(), after.pieceCount());
        for (int id = 0; id < n; id++) {
            Piece<T> piece = byId[id];
            if (piece == null || after.accessible(id) == before.accessible(id)) continue;
            if (changed == null) changed = new java.util.HashSet<>();
            changed.add(piece.cellKey);
        }
        return changed == null ? Set.of() : changed;
    }

    private int rootIndex(T root) {
        if (root == null) return -1;
        Piece<T> piece = adapter.piece(root);
        return piece == null ? -1 : piece.index();
    }

    /** The last build, or null after a regroup until the next build. */
    public PieceMap map() {
        return map;
    }

    /** null if never regrouped or marked. */
    public Cell<T> cell(long key) {
        return cells.get(key);
    }

    public Collection<Cell<T>> cells() {
        return cellView;
    }

    /** Pieces across all cells as grouped now, built or not. */
    public int pieceCount() {
        return pieceCount;
    }

    /** Creates an empty cell record if absent. */
    public void markChanged(long cellKey, long version) {
        cells.computeIfAbsent(cellKey, Cell::new).setChangedVersion(version);
        newestChange = Math.max(newestChange, version);
    }

    /** The highest version any cell was marked changed at: nothing changed after it. */
    private long newestChange = Long.MIN_VALUE;

    public boolean changedSince(long version, long[] cellKeys) {
        if (newestChange <= version) return false;
        for (long key : cellKeys) {
            if (changedSince(version, key)) return true;
        }
        return false;
    }

    /** Whether the cell {@code cellKey} changed after {@code version}. */
    public boolean changedSince(long version, long cellKey) {
        if (newestChange <= version) return false;
        Cell<T> cell = cells.get(cellKey);
        return cell != null && cell.changedVersion() > version;
    }

    /** Whether nothing at all changed after {@code version}: no cell needs looking at. */
    public boolean unchangedSince(long version) {
        return newestChange <= version;
    }

    /** Over ORDINARY links; false and counts a stale query if either piece is null or not in the last build. */
    public boolean reaches(T from, T to) {
        int a = indexOf(from);
        int b = indexOf(to);
        if (a < 0 || b < 0) {
            staleQueries++;
            return false;
        }
        return map.reaches(a, b);
    }

    public boolean accessible(T tile) {
        int p = indexOf(tile);
        if (p < 0) {
            staleQueries++;
            return false;
        }
        return map.accessible(p);
    }

    public boolean isIsland(T tile) {
        int p = indexOf(tile);
        if (p < 0) {
            staleQueries++;
            return false;
        }
        return map.isIsland(p);
    }

    /** Of the last build. */
    public List<Piece<T>> islandPieces() {
        if (map == null) return List.of();
        int[] islands = map.islands();
        List<Piece<T>> out = new ArrayList<>(islands.length);
        for (int p : islands) {
            Piece<T> piece = byId[p];
            if (piece != null) out.add(piece);
        }
        return out;
    }

    /** Questions answered false for lack of a piece or a build. */
    public int staleQueries() {
        return staleQueries;
    }

    /** The tile's piece id in the last build, or -1 when it has no piece or the build is stale. */
    private int indexOf(T tile) {
        if (map == null) return -1;
        Piece<T> piece = adapter.piece(tile);
        if (piece == null) return -1;
        int id = piece.index();
        return id < 0 || id >= map.pieceCount() ? -1 : id;
    }
}
