package com.alex.test.village.economy.away.jobs;

/** Walk times the away jobs share (butcher, lumberjack). */
final class AwayWalk {

    /** Blocks a tick at a normal walk (WALK_SPEED 0.55). */
    static final double WALK_BLOCKS_PER_TICK = 0.163;
    /** Routes bend round fences and houses: the straight line times this (my call). */
    static final double ROUTE_FACTOR = 1.25;
    /** A walk to storage and back (a bank, a tool fetched), as the other away jobs. */
    static final long TRIP_TICKS = 200L;

    private AwayWalk() {}

    /** Ticks to walk {@code blocks} in a straight line at {@code perTick}, the route's bends included. */
    static long ticks(double blocks, double perTick) {
        return Math.round(blocks * ROUTE_FACTOR / perTick);
    }

    /** {@link #ticks} at a normal walk. */
    static long walk(double blocks) {
        return ticks(blocks, WALK_BLOCKS_PER_TICK);
    }
}
