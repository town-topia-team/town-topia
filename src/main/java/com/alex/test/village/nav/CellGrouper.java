package com.alex.test.village.nav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits the tiles of one cell into pieces: strongly connected groups over ORDINARY edges that
 * stay inside the given list. Every counted edge that leaves a piece becomes a link, lowest kind
 * per target tile, whether the target sits in another piece of this cell or in another cell.
 */
public final class CellGrouper {
    private CellGrouper() {}

    /**
     * Pieces = strongly connected groups over ORDINARY edges between tiles of this list.
     * Sets adapter.setPiece on every tile. Links = every counted edge (kind != NONE) from a member
     * to a tile outside the piece (other piece, same or other cell), lowest kind per target tile.
     */
    public static <T> List<Piece<T>> group(long cellKey, List<T> tiles, TileAdapter<T> adapter, long now) {
        int n = tiles.size();
        if (n == 0) return List.of();
        // A tile's scratch is its position here; a neighbour is "in this cell" when the position
        // points back at the very same object, so stale scratch values on outside tiles cannot lie.
        for (int i = 0; i < n; i++) adapter.setScratch(tiles.get(i), i);

        // Neighbours and kinds are read once and reused for both the search and the links.
        List<T[]> neighbours = new ArrayList<>(n);
        List<int[]> kinds = new ArrayList<>(n);
        int[] offsets = new int[n + 1];
        int[] targets = new int[Math.max(16, n * 4)];
        int m = 0;
        for (int i = 0; i < n; i++) {
            T u = tiles.get(i);
            T[] ns = adapter.neighbours(u);
            int[] ks = new int[ns.length];
            for (int j = 0; j < ns.length; j++) {
                T v = ns[j];
                int k = adapter.kind(u, v, now);
                ks[j] = k;
                if (k != EdgeKind.ORDINARY) continue;
                int inCell = indexInCell(v, tiles, adapter, cellKey);
                if (inCell < 0) continue;
                if (m == targets.length) targets = Arrays.copyOf(targets, m * 2);
                targets[m++] = inCell;
            }
            neighbours.add(ns);
            kinds.add(ks);
            offsets[i + 1] = m;
        }
        Tarjan.Result scc = Tarjan.run(n, offsets, targets);
        int[] component = scc.component();

        List<List<T>> members = new ArrayList<>(scc.count());
        for (int c = 0; c < scc.count(); c++) members.add(new ArrayList<>());
        for (int i = 0; i < n; i++) members.get(component[i]).add(tiles.get(i));

        List<Piece<T>> pieces = new ArrayList<>(scc.count());
        for (int c = 0; c < scc.count(); c++) {
            List<T> mine = members.get(c);
            Map<T, Integer> lowest = new IdentityHashMap<>();
            List<T> order = new ArrayList<>();
            for (T u : mine) {
                int i = adapter.scratch(u);
                T[] ns = neighbours.get(i);
                int[] ks = kinds.get(i);
                for (int j = 0; j < ns.length; j++) {
                    int k = ks[j];
                    if (k == EdgeKind.NONE) continue;
                    T v = ns[j];
                    int inCell = indexInCell(v, tiles, adapter, cellKey);
                    if (inCell >= 0 && component[inCell] == c) continue;
                    Integer seen = lowest.get(v);
                    if (seen == null) {
                        lowest.put(v, k);
                        order.add(v);
                    } else if (k < seen) {
                        lowest.put(v, k);
                    }
                }
            }
            List<Piece.Link<T>> links = new ArrayList<>(order.size());
            for (T v : order) links.add(new Piece.Link<>(v, lowest.get(v)));
            Piece<T> piece = new Piece<>(cellKey, mine, links);
            for (T u : mine) adapter.setPiece(u, piece);
            pieces.add(piece);
        }
        return pieces;
    }

    /** The tile's position in the list, or -1 when it is not one of this cell's tiles. */
    private static <T> int indexInCell(T v, List<T> tiles, TileAdapter<T> adapter, long cellKey) {
        int s = adapter.scratch(v);
        if (s < 0 || s >= tiles.size() || tiles.get(s) != v) return -1;
        return adapter.cellKey(v) == cellKey ? s : -1;
    }
}
