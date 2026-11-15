package com.alex.test.village.economy.away;

import java.util.ArrayList;
import java.util.List;

/**
 * The counted time away cut into village days, from the moment the village
 * fell asleep, each cut at dawn. A day knows its length and how much of it
 * is work hours (dawn to the end of work), evening (end of work to bed) and
 * night (bed to dawn). The first and last day are usually partial.
 *
 * <p>Times are village calendar times ({@code VillageDays.villageTime}); the
 * day lengths and the clock times come in as numbers
 * ({@code EveningSchedule.WAKE_TIME}, {@code MEAN_END_WORK},
 * {@code PROJECTED_BEDTIME}). Pure logic without Minecraft types, so unit
 * tests can use it. Meal times come with the meals (round 2 of the plan).
 */
public final class AwayDays {

    /**
     * One day of the time away.
     *
     * @param index       0 for the first day
     * @param start       village time this day's part of the time away starts at
     * @param length      ticks of the time away in this day
     * @param calendarDay the village day number its morning belongs to
     */
    public record Day(int index, long start, long length, long workTicks, long eveningTicks, long nightTicks,
                      long calendarDay) {
        public long end() { return start + length; }
    }

    private AwayDays() {}

    /**
     * Cuts {@code counted} ticks from {@code start} into days at {@code wakeTime}.
     * Nothing when {@code counted} is 0 or less.
     */
    public static List<Day> cut(long start, long counted, long dayTicks, long wakeTime, long endWorkTime,
                                long bedTime) {
        List<Day> days = new ArrayList<>();
        if (counted <= 0L || dayTicks <= 0L) return days;
        long workLength = Math.floorMod(endWorkTime - wakeTime, dayTicks);
        long eveningLength = Math.floorMod(bedTime - endWorkTime, dayTicks);
        // The morning after a dawn late in the day belongs to the next calendar day.
        long morningShift = Math.floorMod(dayTicks - wakeTime, dayTicks);
        long end = start + counted;
        long t = start;
        int index = 0;
        while (t < end) {
            long dawn = Math.floorDiv(t - wakeTime, dayTicks) * dayTicks + wakeTime;
            long sliceEnd = Math.min(dawn + dayTicks, end);
            long from = t - dawn;
            long to = sliceEnd - dawn;
            long work = overlap(from, to, 0L, workLength);
            long evening = overlap(from, to, workLength, workLength + eveningLength);
            long night = (to - from) - work - evening;
            long calendarDay = Math.floorDiv(dawn + morningShift, dayTicks);
            days.add(new Day(index++, t, sliceEnd - t, work, evening, night, calendarDay));
            t = sliceEnd;
        }
        return days;
    }

    private static long overlap(long from, long to, long windowFrom, long windowTo) {
        return Math.max(0L, Math.min(to, windowTo) - Math.max(from, windowFrom));
    }
}
