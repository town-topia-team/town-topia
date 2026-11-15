package com.alex.test.village.farawake;

import com.alex.test.performance.PerformanceCost;

/**
 * The live cost meter behind the Mayor's Performance page: how long far villagers took per server tick, over
 * the last {@link #WINDOW} ticks (5 s). {@link FarAwake#tickLevel} and {@link FarAwake#tickVillage} add all
 * their time; {@link FarSync#send} adds its own share as well, so "far villagers" is everything but the sends
 * and "smooth movement" is the sends alone (the two never overlap).
 * <p>Slots are by server tick number; a tick nobody added to counts as 0. A server start, or a tick number
 * going back (single player: another world), empties it. Server thread only; no Minecraft classes.
 */
public final class FarCost {

    /** Ticks averaged over: 5 s. */
    static final int WINDOW = 100;

    private static final long[] ALL = new long[WINDOW];
    private static final long[] SEND = new long[WINDOW];
    /** The server tick the newest slot belongs to. */
    private static long tick = Long.MIN_VALUE;

    private FarCost() {}

    /** All far villager time, sends included. */
    static void addAll(long tickNow, long nanos) {
        advance(tickNow);
        ALL[slot(tickNow)] += nanos;
    }

    /** The sends' share ({@link FarSync}); always inside a time given to {@link #addAll} too. */
    static void addSend(long tickNow, long nanos) {
        advance(tickNow);
        SEND[slot(tickNow)] += nanos;
    }

    static void reset() {
        java.util.Arrays.fill(ALL, 0);
        java.util.Arrays.fill(SEND, 0);
        tick = Long.MIN_VALUE;
    }

    /** Far villager work without the sends: average and worst tick of the last 5 s. */
    public static PerformanceCost work(long tickNow) {
        advance(tickNow);
        long sum = 0, worst = 0;
        for (int i = 0; i < WINDOW; i++) {
            long work = Math.max(0, ALL[i] - SEND[i]);
            sum += work;
            if (work > worst) worst = work;
        }
        return new PerformanceCost(sum / WINDOW, worst);
    }

    /** The sends alone (smooth far movement): average and worst tick of the last 5 s. */
    public static PerformanceCost sends(long tickNow) {
        advance(tickNow);
        long sum = 0, worst = 0;
        for (int i = 0; i < WINDOW; i++) {
            sum += SEND[i];
            if (SEND[i] > worst) worst = SEND[i];
        }
        return new PerformanceCost(sum / WINDOW, worst);
    }

    /** Empties the slots of the ticks since the newest one, so they count as 0. */
    private static void advance(long tickNow) {
        if (tickNow == tick) return;
        if (tick == Long.MIN_VALUE || tickNow < tick || tickNow - tick >= WINDOW) {
            java.util.Arrays.fill(ALL, 0);
            java.util.Arrays.fill(SEND, 0);
        } else {
            for (long t = tick + 1; t <= tickNow; t++) {
                int i = slot(t);
                ALL[i] = 0;
                SEND[i] = 0;
            }
        }
        tick = tickNow;
    }

    private static int slot(long tickNow) {
        return (int) Math.floorMod(tickNow, (long) WINDOW);
    }
}
