package com.alex.test.village.nav;

import java.util.List;

/**
 * A group of tiles in one cell that can all reach each other over ordinary edges without leaving
 * the cell. Links point at tiles rather than pieces: when a neighbouring cell is regrouped its
 * pieces are thrown away, but a link stored as a tile still finds the right new piece through the
 * adapter, so a piece's links only need recomputing when its own cell is regrouped.
 */
public final class Piece<T> {
    public record Link<T>(T to, int kind) {}

    public final long cellKey;
    /** Unmodifiable, never empty. */
    public final List<T> members;
    /** Unmodifiable; at most one link per target tile, lowest kind wins. */
    public final List<Link<T>> links;
    private int index = -1;

    Piece(long cellKey, List<T> members, List<Link<T>> links) {
        if (members.isEmpty()) throw new IllegalArgumentException("a piece needs at least one tile");
        this.cellKey = cellKey;
        this.members = List.copyOf(members);
        this.links = List.copyOf(links);
    }

    /** The piece's stable id in its CellTable, or -1 before it is grouped in and again once a regroup has replaced it. */
    public int index() {
        return index;
    }

    void setIndex(int index) {
        this.index = index;
    }

    @Override
    public String toString() {
        return "Piece[cell=" + cellKey + ", tiles=" + members.size() + ", links=" + links.size() + ", index=" + index + "]";
    }
}
