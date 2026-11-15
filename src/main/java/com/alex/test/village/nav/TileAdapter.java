package com.alex.test.village.nav;

/**
 * How the nav core reads and marks tiles without knowing what a tile is. The village side adapts
 * NavNode; the tests adapt a toy tile. Keeping Minecraft out of this package is what lets the
 * grouping and the piece map be unit tested.
 */
public interface TileAdapter<T> {
    /** Stored neighbours, as stored (one-way reverses included; kind() sorts them out). */
    T[] neighbours(T tile);

    /** An EdgeKind value for the stored edge from -> to at wall-clock time now. */
    int kind(T from, T to, long now);

    long cellKey(T tile);

    /** One int the grouper may use freely between calls. */
    int scratch(T tile);

    void setScratch(T tile, int value);

    /** The tile's current piece, or null until it has been grouped. */
    Piece<T> piece(T tile);

    void setPiece(T tile, Piece<T> piece);
}
