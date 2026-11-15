package com.alex.test.village.raid;

import java.util.random.RandomGenerator;

/**
 * When the next raid is due, in village days. Raids first saw the village on
 * {@link #firstDay()}; the first raid is due {@link #FIRST_RAID_DAYS} later,
 * every later one a rolled gap after the last one ended.
 *
 * <p>No Minecraft types: unit-tested on a plain JVM.
 */
public final class RaidSchedule {

    /** Days after raids first saw a village before its first raid. */
    public static final int FIRST_RAID_DAYS = 8;
    /** Extra days after a lost raid, so a beaten village can recover. */
    public static final int DEFEAT_GRACE_DAYS = 2;
    /** Never fewer days between raids than this. */
    public static final int MIN_GAP_DAYS = 3;

    private long firstDay = -1;
    private long nextRaidDay = -1;

    /** Remembers the first village day raids saw; later days change nothing. */
    public void observe(long day) {
        observe(day, FIRST_RAID_DAYS);
    }

    /** {@link #observe(long)} with the server's own wait before the first raid. */
    public void observe(long day, int firstRaidDays) {
        if (firstDay < 0) firstDay = day;
        if (nextRaidDay < 0) nextRaidDay = firstDay + Math.max(0, firstRaidDays);
    }

    /** Whether a raid is due on {@code day} at this frequency. */
    public boolean isDue(long day, RaidFrequency frequency) {
        if (frequency == RaidFrequency.OFF || nextRaidDay < 0) return false;
        return day >= nextRaidDay;
    }

    /** Sets the next raid a rolled gap after {@code endDay}, plus grace after a defeat. */
    public void scheduleAfter(long endDay, RaidFrequency frequency, int tier, boolean defeat, RandomGenerator rng) {
        nextRaidDay = endDay + gapDays(frequency, tier, rng) + (defeat ? DEFEAT_GRACE_DAYS : 0);
    }

    /** Moves the next raid {@code days} later (time away the village did not live through). */
    public void shiftBy(long days) {
        if (days > 0 && nextRaidDay >= 0) nextRaidDay += days;
    }

    /** Pushes the next raid back to no earlier than {@code day}. */
    public void postponeTo(long day) {
        nextRaidDay = Math.max(nextRaidDay, day);
    }

    /** The frequency's gap, a day shorter at tier 2 and two at tier 3, never under {@link #MIN_GAP_DAYS}. */
    public static int gapDays(RaidFrequency frequency, int tier, RandomGenerator rng) {
        int min = frequency.minGap();
        int max = frequency.maxGap();
        int gap = min + rng.nextInt(max - min + 1);
        int shorter = tier >= 3 ? 2 : tier == 2 ? 1 : 0;
        return Math.max(MIN_GAP_DAYS, gap - shorter);
    }

    public long firstDay() { return firstDay; }

    public long nextRaidDay() { return nextRaidDay; }

    /** Puts back saved days (negative = not set). */
    public void restore(long firstDay, long nextRaidDay) {
        this.firstDay = firstDay;
        this.nextRaidDay = nextRaidDay;
    }
}
