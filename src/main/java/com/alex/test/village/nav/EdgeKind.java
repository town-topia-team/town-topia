package com.alex.test.village.nav;

/**
 * What a stored edge between two tiles counts for. The three answers of the piece map each follow
 * a prefix of these: "can I get there" takes ORDINARY only, token access takes ORDINARY and FAILED,
 * the island sweep takes everything but NONE. Keeping them ordered lets a flood compare kinds with
 * a single "at most" test.
 */
public final class EdgeKind {
    /** The stored edge does not count at all (cannot be stepped). */
    public static final int NONE = -1;
    /** A step every answer follows. */
    public static final int ORDINARY = 0;
    /** A jump a villager recently failed; "can I get there" skips it until it wears off. */
    public static final int FAILED = 1;
    /** Ordinary floor onto furniture or water; only the island sweep follows it. */
    public static final int ENTER_OFF = 2;

    private EdgeKind() {}
}
