package com.alex.test.village.villagers.enchanter;

/**
 * The enchanter's trance: a player who walks close while it writes a book
 * sees it seize, raise its arms with white eyes, and hears the ominous loop;
 * stepping back past {@link #OUT_RANGE} lets it go. Each client decides this
 * for its own player, so a friend further off sees a calm enchanter.
 */
public final class EnchanterTrance {
    /** A player this close to a writing enchanter pulls it into the trance. */
    public static final double IN_RANGE = 6.0;
    /** And has to step this far back to let it go; the gap stops flicker at the edge. */
    public static final double OUT_RANGE = 9.0;

    /** Client-side phases, kept on {@code NpcEntity} (not synced). */
    public static final int NORMAL = 0;
    public static final int SEIZE = 1;
    public static final int TRANCE = 2;
    public static final int RELEASE = 3;

    private EnchanterTrance() {}
}
