package com.alex.test.village.economy;

/**
 * How much of the time nobody was near a village it lives through.
 *
 * <p>A village asleep keeps its clock only for {@link #MAX_AWAY_DAYS} days,
 * then waits for a player: work, meals, mood, XP and children growing up
 * all stop there. On a server the world runs on with nobody online (1.21.1
 * never pauses an empty server), so a weekend away was 144 village days of
 * goods. Time the server was empty does not count at all; for a village
 * with an owner (claims on), no time with its owner and every Officer off
 * the server (owner offline, 10-25).
 *
 * <p>Pure logic without Minecraft types, so unit tests can use it.
 */
public final class AwayClock {

    /** Days a village lives through while nobody is near; the rest it waits. */
    public static final int MAX_AWAY_DAYS = 3;
    public static final long DAY_TICKS = 24_000L;
    public static final long MAX_AWAY_TICKS = MAX_AWAY_DAYS * DAY_TICKS;

    /**
     * Share of a live villager's work done while nobody is near: being there
     * always pays more than being gone.
     */
    public static final double AWAY_PACE = 0.8;

    private AwayClock() {}

    /**
     * Ticks the village lives through: the time away less the time the
     * server had nobody online, at most {@link #MAX_AWAY_TICKS}.
     */
    public static long countedTicks(long awayTicks, long emptyServerTicks) {
        long worldTicks = Math.max(0L, awayTicks) - Math.max(0L, emptyServerTicks);
        return Math.clamp(worldTicks, 0L, MAX_AWAY_TICKS);
    }

    /**
     * Time away nobody it belongs to was there for: the server's empty time, or, for a village
     * that rests with its owner and Officers offline, their time off the server. That one holds
     * the empty time (nobody on is nobody of theirs on), so the larger counts out, never both.
     */
    public static long offlineTicks(long emptyServerTicks, long ownerOfflineTicks) {
        return Math.max(Math.max(0L, emptyServerTicks), Math.max(0L, ownerOfflineTicks));
    }

    /** Work ticks a villager puts in while away: its live share of {@code ticks}, at the away pace. */
    public static long workTicks(long ticks, double workingShare) {
        return Math.round(Math.max(0L, ticks) * workingShare * AWAY_PACE);
    }
}
