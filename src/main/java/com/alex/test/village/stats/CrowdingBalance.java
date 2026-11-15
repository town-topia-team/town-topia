package com.alex.test.village.stats;

/**
 * Pure arithmetic of overcrowding, shared by the live rules and the screens.
 *
 * <p>Every villager wants {@value #TILES_PER_VILLAGER} tiles of space in the
 * building it is in. Below that it loses wellbeing on a straight line, from
 * nothing at 12 tiles each to {@value #MAX_PENALTY} at 6 or fewer, so 9 tiles
 * each costs 2.5. A tile under a ceiling of two blocks or less counts as
 * {@value #LOW_CEILING_WEIGHT} of a tile, so a partly cramped room loses a
 * little space instead of flipping from fine to crowded at some cut-off.
 *
 * <p>Crowding takes company: a villager alone in a small room (a cook in a
 * tiny kitchen) is never crowded, so every room fits at least one.
 *
 * <p>The player-facing lines live here too, so chat, the house screen and the
 * Mayor say the same thing. They use plain words and are only shown when
 * something is wrong.
 */
public final class CrowdingBalance {
    private CrowdingBalance() {}

    /** Space each villager needs before the building feels crowded. */
    public static final int TILES_PER_VILLAGER = 12;
    /** Space each at which the penalty is at its worst. */
    public static final float WORST_TILES_PER_VILLAGER = 6f;
    /** Largest penalty one crowded visit can cost. */
    public static final float MAX_PENALTY = 5f;
    /** Most wellbeing crowding can take from one villager in one day. */
    public static final float DAILY_CAP = 5f;
    /** Worth of a tile whose headroom is {@value #LOW_CEILING_HEADROOM} blocks or less. */
    public static final float LOW_CEILING_WEIGHT = 0.5f;
    /** Headroom at or under which a tile counts as low-ceilinged. */
    public static final double LOW_CEILING_HEADROOM = 2.0;

    /** Wellbeing lost by one visit at this much space per body; 0 when there is room or nobody else. */
    public static float penalty(float space, int bodies) {
        if (bodies <= 1) return 0f;
        float each = space / bodies;
        if (each >= TILES_PER_VILLAGER) return 0f;
        float severity = (TILES_PER_VILLAGER - each) / (TILES_PER_VILLAGER - WORST_TILES_PER_VILLAGER);
        return MAX_PENALTY * Math.min(1f, Math.max(0f, severity));
    }

    /** Whether this many bodies make the building crowded. */
    public static boolean isCrowded(float space, int bodies) {
        return penalty(space, bodies) > 0f;
    }

    /** How many villagers the space holds before anyone feels crowded; at least one in any room. */
    public static int fits(float space) {
        if (space <= 0f) return 0;
        return Math.max(1, (int) Math.floor(space / TILES_PER_VILLAGER + 1e-4f));
    }

    /** The part of {@code wanted} still allowed today after {@code takenToday}. */
    public static float allowedToday(float wanted, float takenToday) {
        return Math.max(0f, Math.min(wanted, DAILY_CAP - takenToday));
    }

    /** Space for display: whole numbers without a decimal, halves with one. */
    public static String format(float space) {
        float rounded = Math.round(space * 2f) / 2f;
        return rounded == Math.floor(rounded)
                ? Integer.toString((int) rounded)
                : String.format(java.util.Locale.ROOT, "%.1f", rounded);
    }

    // -------------------------------------------------------------------------
    // What the player reads
    // -------------------------------------------------------------------------

    /*
     * Simple enough for a child: what is wrong, that it makes them unhappy,
     * and what to do. Never a number of how many "fit": the player read that
     * as beds that would not be used, when every bed is still slept in.
     */

    /** More villagers live here than fit: "Too crowded! The 4 villagers living here are unhappy." */
    public static String crampedLine(int living) {
        return "Too crowded! The " + living + " villagers living here are unhappy.";
    }

    /** More villagers were inside at once than fit: "Too crowded! 5 villagers were inside at once and got unhappy." */
    public static String busyLine(int bodies) {
        return "Too crowded! " + bodies + " villagers were inside at once and got unhappy.";
    }

    /** Its beds would crowd it once all are taken: "This house is too small for 4 villagers, so they will be unhappy." */
    public static String bedsLine(int beds) {
        return "This house is too small for " + beds + " villagers, so they will be unhappy.";
    }

    /** What to do about it, naming the ceiling only when the room has low ones. */
    public static String fixLine(int lowCeilingTiles) {
        return lowCeilingTiles > 0
                ? "Make the room bigger, or make the ceiling at least 3 blocks high."
                : "Make the room bigger.";
    }
}
