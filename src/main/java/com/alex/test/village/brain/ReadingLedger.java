package com.alex.test.village.brain;

/**
 * Intelligence earned by reading in the library: {@value #PER_AWARD} for each
 * {@value #TICKS_PER_AWARD} ticks actually spent settled with a book open, at
 * most {@value #DAILY_AWARDS} awards (0.2) a day.
 *
 * <p>The count of awards already paid today is saved with the villager, so an
 * interruption, a reload or a time change cannot pay twice. The daily budget
 * renews on a new village day ({@code VillageDays}); setting the clock back
 * only brings one when it skips a night. Reading ticks short of a whole
 * award are not saved; a reload loses at most one award's worth, never pays
 * extra.
 */
public final class ReadingLedger {

    public static final float PER_AWARD = 0.02f;
    public static final int   TICKS_PER_AWARD = 100;
    public static final int   DAILY_AWARDS = 10;

    private long day = Long.MIN_VALUE;
    private int  awards;
    private int  pendingTicks;

    /**
     * Record {@code ticks} of reading on {@code dayIndex}; returns the
     * intelligence to grant now, usually zero.
     */
    public float onReadingTicks(long dayIndex, int ticks) {
        roll(dayIndex);
        if (ticks <= 0 || awards >= DAILY_AWARDS) {
            pendingTicks = 0;
            return 0f;
        }
        pendingTicks += ticks;
        int earned = Math.min(pendingTicks / TICKS_PER_AWARD, DAILY_AWARDS - awards);
        pendingTicks %= TICKS_PER_AWARD;
        awards += earned;
        return earned * PER_AWARD;
    }

    /**
     * A new day renews the budget. Village days only move forward (/time set
     * cannot take them back), so a day behind the saved one is a save from
     * before that, on the world's own day number: renewed too, instead of
     * holding the budget back until the days catch up.
     */
    private void roll(long dayIndex) {
        if (day == Long.MIN_VALUE || dayIndex != day) {
            day = dayIndex;
            awards = 0;
            pendingTicks = 0;
        }
    }

    /** Intelligence paid out on {@link #day()} so far. */
    public float awardedToday() { return awards * PER_AWARD; }
    public int  awards() { return awards; }
    public long day()    { return day; }

    public void load(long savedDay, int savedAwards) {
        day = savedDay;
        awards = Math.max(0, Math.min(DAILY_AWARDS, savedAwards));
        pendingTicks = 0;
    }
}
