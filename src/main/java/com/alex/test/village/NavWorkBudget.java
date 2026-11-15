package com.alex.test.village;

/**
 * Nav-graph work shared out between every village on the server, per server
 * tick.
 *
 * <p>Each village owns its own {@link NavGraph}, and before this each one
 * decided on its own when to rebuild and how long to spend on its slices. On a
 * server with several villages nothing kept those apart: two local updates
 * landing on the same tick cost twice as much, and the slices (a full build's
 * 512 nodes, the reachability labels, the island sweep) of every village ran
 * back to back.
 *
 * <p>Two rules, both per server tick:
 * <ul>
 *   <li>Only one village may run a graph rebuild (a local update, or the start
 *       of a full build). A village whose rebuild is due but finds the slot
 *       taken keeps it due and tries again next tick; the debounce already
 *       holds a rebuild back for seconds, so a tick or two more is not seen.</li>
 *   <li>Sliced work -- a full build in progress; a local patch has no sliced
 *       follow-ups any more -- draws on one budget. A village gets at most
 *       {@link #VILLAGE_MAX_NANOS} of it, and none once it is spent. A village
 *       that wanted a slice and got none is "starved", and next tick gets at
 *       least {@link #STARVED_MIN_NANOS} whatever the others took, so a village
 *       late in the tick order still finishes.</li>
 * </ul>
 *
 * <p>Server thread only. Keyed by the server's tick count, so villages in
 * every dimension share one budget.
 */
final class NavWorkBudget {

    /** Sliced nav work all villages together may do in one server tick. */
    static final long TICK_BUDGET_NANOS = 6_000_000L;

    /** Most one village's slices may take in one tick (what labels alone had before). */
    static final long VILLAGE_MAX_NANOS = 4_000_000L;

    /** Below this a slice is not worth starting; also what a starved village is guaranteed. */
    static final long STARVED_MIN_NANOS = 1_500_000L;

    private static int currentTick = Integer.MIN_VALUE;
    private static long remainingNanos;
    private static boolean rebuildTaken;

    private NavWorkBudget() {}

    private static void roll(int serverTick) {
        if (serverTick == currentTick) return;
        currentTick = serverTick;
        remainingNanos = TICK_BUDGET_NANOS;
        rebuildTaken = false;
    }

    /** Claims this tick's one rebuild slot; false if another village has it. */
    static boolean tryTakeRebuild(int serverTick) {
        roll(serverTick);
        if (rebuildTaken) return false;
        rebuildTaken = true;
        return true;
    }

    /**
     * How long a village's slices may run this tick, or 0 to skip them.
     *
     * @param starved whether the village wanted a slice last tick and got none
     */
    static long sliceBudget(int serverTick, boolean starved) {
        roll(serverTick);
        long budget = Math.min(VILLAGE_MAX_NANOS, remainingNanos);
        if (starved) return Math.max(budget, STARVED_MIN_NANOS);
        return budget >= STARVED_MIN_NANOS ? budget : 0L;
    }

    /** Charges time a village's slices took against this tick's budget. */
    static void spent(int serverTick, long nanos) {
        roll(serverTick);
        remainingNanos = Math.max(0L, remainingNanos - nanos);
    }
}
