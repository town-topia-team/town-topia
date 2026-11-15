package com.alex.test.village.brain;

/**
 * Spreads periodic per-villager checks over the ticks of their period, so a
 * village's villagers do not all run the same check on the same tick.
 */
public final class TickStagger {

    /** How often throttled villager checks run: 1.5 seconds. */
    public static final int CHECK_PERIOD_TICKS = 30;

    private TickStagger() {}

    /** Whether this is the entity's own tick in the period. */
    public static boolean isSlot(long time, int entityId, int period) {
        return Math.floorMod(time + entityId, period) == 0;
    }

    /** The entity's next own tick in the period, 1 to {@code period} ticks after {@code time}. */
    public static long nextSlot(long time, int entityId, int period) {
        int wait = Math.floorMod(-(time + entityId), period);
        return time + (wait == 0 ? period : wait);
    }

    /**
     * Holds a job picker back after a pick that finished no work -- a failed
     * walk, a job that turned out invalid -- so a villager with nothing it can
     * do does not re-run its search every tick. A pick after finished work is
     * never delayed.
     */
    public static final class RetryGate {
        private long nextAt;
        private boolean progressed = true;

        /** Call when a job actually got done. */
        public void progressed() { progressed = true; }

        /** Whether the picker may run now; if so, arms the gate for the next pick. */
        public boolean tryPass(long time, int entityId) {
            if (!progressed && time < nextAt) return false;
            progressed = false;
            // At least one period away, landing on the villager's own tick.
            nextAt = nextSlot(time + CHECK_PERIOD_TICKS - 1, entityId, CHECK_PERIOD_TICKS);
            return true;
        }
    }
}
