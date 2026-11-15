package com.alex.test.village.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One 16x16x16 region of the map and its current pieces. The record outlives its tiles: a cell
 * emptied by an edit keeps its entry so changedSince can still report the edit.
 */
public final class Cell<T> {
    public final long key;
    private final List<Piece<T>> pieces = new ArrayList<>();
    private final List<Piece<T>> view = Collections.unmodifiableList(pieces);
    private long changedVersion;

    Cell(long key) {
        this.key = key;
    }

    /** Unmodifiable; empty when the cell has no tiles. */
    public List<Piece<T>> pieces() {
        return view;
    }

    /** The map version of the last change in this cell. */
    public long changedVersion() {
        return changedVersion;
    }

    void replacePieces(List<Piece<T>> fresh) {
        pieces.clear();
        pieces.addAll(fresh);
    }

    void setChangedVersion(long version) {
        this.changedVersion = version;
    }
}
