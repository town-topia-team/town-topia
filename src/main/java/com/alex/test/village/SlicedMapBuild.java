package com.alex.test.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * A village map drawn a slice per tick, its pieces grouped and its piece map
 * built before it is handed over, so the village that takes it starts
 * without a stall.
 *
 * <p>How much of a tick a slice may take depends on how busy the server is: a
 * calm tick (a few ms of other work) lets a slice run up to
 * {@link #MAX_SLICE_NANOS}, so the map is done sooner; a tick already near its
 * 50 ms (chunk generation right after a teleport, a fight) gets only
 * {@link #MIN_SLICE_NANOS}, so the map adds no lag on top. A fixed slice was
 * too much when busy and too little when calm.
 *
 * <p>Server thread only. Every build running in a tick shares that tick's
 * allowance.
 */
public final class SlicedMapBuild {

    /** Steps between clock reads: often enough that a slice ends close to its allowance. */
    private static final int STEPS_PER_CHECK = 128;
    /** Server tick time (ms) the slices aim to stay under, other work included. */
    private static final double TARGET_TICK_MILLIS = 35.0;
    private static final long MIN_SLICE_NANOS = 2_000_000L;
    private static final long MAX_SLICE_NANOS = 15_000_000L;

    /** This kind of work's own share of the server's smoothed tick time, smoothed the same way. */
    private static float ownSmoothedMillis;
    private static int currentTick = Integer.MIN_VALUE;
    private static long spentThisTick;
    private static long spentLastTick;

    private final NavGraph.BuildTask task;
    @Nullable private NavGraph graph;
    private long workNanos;
    private int ticks;

    public SlicedMapBuild(ServerLevel level, BlockPos origin, int chunkRadius, long version) {
        this.task = NavGraph.startBuild(level, origin, chunkRadius, version);
    }

    /**
     * Spends this tick's share on the map, then on its labels.
     *
     * @return the finished, labelled map, or null while more ticks are needed
     */
    @Nullable
    public NavGraph advance(ServerLevel level) {
        long began = System.nanoTime();
        long allowance = allowanceNanos(level.getServer());
        if (graph == null) {
            NavGraph built = null;
            while (built == null && System.nanoTime() - began < allowance) {
                built = task.advance(level, STEPS_PER_CHECK);
            }
            graph = built;
        }
        // The task groups the tiles into pieces and builds the piece map
        // before it hands the graph over, so a finished graph is ready to
        // answer every reachability question.
        boolean done = graph != null;
        long spent = System.nanoTime() - began;
        workNanos += spent;
        ticks++;
        spentThisTick += spent;
        return done ? graph : null;
    }

    /** Time spent so far, in ms. */
    public long workMillis() {
        return workNanos / 1_000_000L;
    }

    /** Ticks it has run so far. */
    public int ticks() {
        return ticks;
    }

    /**
     * How long a slice may run this tick: what is left of the target once the
     * server's other work is counted, less what other builds took this tick.
     */
    private static long allowanceNanos(MinecraftServer server) {
        int tick = server.getTickCount();
        if (tick != currentTick) {
            if (tick == currentTick + 1) spentLastTick = spentThisTick;
            else spentLastTick = 0L;
            // The same smoothing the server applies to its own tick time.
            ownSmoothedMillis = ownSmoothedMillis * 0.8F + spentLastTick / 1_000_000.0F * 0.2F;
            currentTick = tick;
            spentThisTick = 0L;
        }
        double otherWork = Math.max(0.0, server.getCurrentSmoothedTickTime() - ownSmoothedMillis);
        long allowance = (long) ((TARGET_TICK_MILLIS - otherWork) * 1_000_000.0);
        allowance = Math.max(MIN_SLICE_NANOS, Math.min(MAX_SLICE_NANOS, allowance));
        // A second build in the same tick gets what the first left, but never nothing.
        return Math.max(MIN_SLICE_NANOS / 2, allowance - spentThisTick);
    }
}
