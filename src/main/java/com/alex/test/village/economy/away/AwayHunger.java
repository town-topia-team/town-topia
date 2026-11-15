package com.alex.test.village.economy.away;

/**
 * The numbers of hunger and starving while away: how hunger falls over a
 * stretch of time, how long it sat at 0 or under a line, and how many
 * starving hits that time is worth.
 *
 * <p>Pure logic without Minecraft types, so unit tests can use it;
 * {@code AwayHealth} feeds it the live rates.
 */
public final class AwayHunger {

    /** Hunger runs at this share of live while away (user 10-22); work runs at {@code AwayClock.AWAY_PACE}. */
    public static final float HUNGER_PACE = 0.9f;

    /**
     * Share of its work hours a villager spends working, for its effort and
     * the work's toll on mood: about 7,800 of the 12,967 ticks, the same effort
     * the old calculator charged (my call, 10-22).
     */
    public static final float WORKING_SHARE = 0.6f;

    /**
     * Where hunger ends after {@code ticks}, and how many of them it spent at 0
     * and under {@code line}.
     */
    public record Drain(float hunger, long zeroTicks, long belowTicks) {}

    /** Ticks toward the next starving hit, and the hits that landed. */
    public record Starve(int hits, long timer) {}

    private AwayHunger() {}

    /** Hunger falling {@code rate} a tick for {@code ticks} ticks from {@code hunger}. */
    public static Drain drain(float hunger, float rate, long ticks, float line) {
        if (ticks <= 0L) return new Drain(hunger, 0L, 0L);
        float start = Math.max(0f, hunger);
        if (rate <= 0f) {
            return new Drain(start, start <= 0f ? ticks : 0L, start < line ? ticks : 0L);
        }
        float end = Math.max(0f, start - rate * ticks);
        long toZero = (long) Math.ceil(start / rate);
        long zero = Math.max(0L, ticks - toZero);
        long below;
        if (start < line) {
            below = ticks;
        } else {
            long toLine = (long) Math.ceil((start - line) / rate);
            below = Math.max(0L, ticks - toLine);
        }
        return new Drain(end, zero, below);
    }

    /**
     * Starving hits over {@code zeroTicks} at hunger 0: one every
     * {@code interval} ticks. {@code timer} is the part toward the next hit
     * carried in from before, and comes back the same way.
     */
    public static Starve starve(long timer, long zeroTicks, int interval) {
        if (interval <= 0 || zeroTicks <= 0L) return new Starve(0, Math.max(0L, timer));
        long total = Math.max(0L, timer) + zeroTicks;
        return new Starve((int) Math.min(Integer.MAX_VALUE, total / interval), total % interval);
    }
}
