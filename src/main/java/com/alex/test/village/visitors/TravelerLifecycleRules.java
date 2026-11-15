package com.alex.test.village.visitors;

import com.alex.test.village.VillageDays;

import java.util.Collection;
import java.util.function.IntUnaryOperator;

/** Pure timing rules shared by nomads and merchants. */
public final class TravelerLifecycleRules {
    public static final long DAY_TICKS = 24_000L;
    public static final int DEPARTURE_WINDOW_START = 11_500;
    public static final int DEPARTURE_WINDOW_END = 13_500;
    /** Organic arrivals are rolled during the first 1,000 ticks after sunrise. */
    public static final int ARRIVAL_WINDOW_END = 1_000;
    public static final long MIN_DEPARTURE_SPACING_TICKS = 120L;
    public static final long REPATH_INTERVAL_TICKS = 60L;
    public static final long RETURN_TIMEOUT_TICKS = 6_000L;
    public static final double RETURN_WALK_SPEED = 0.55D;
    public static final double HOME_RADIUS_SQ = 3.5D * 3.5D;
    public static final double SAFE_REMOVAL_PLAYER_RADIUS = 48.0D;

    private TravelerLifecycleRules() {}

    public static boolean isBeginningOfDay(long dayTime) {
        return Math.floorMod(dayTime, DAY_TICKS) <= ARRIVAL_WINDOW_END;
    }

    /**
     * The first {@link #ARRIVAL_WINDOW_END} ticks of the village day: from
     * sunrise, waking up, or the clock turned back to morning, which lands at
     * 1,000 and would miss a window read off the clock. Should the calendar not
     * be running, the clock decides as it used to.
     *
     * <p>Never once it is too late in the day to arrive ({@link #isTooLateToArrive}):
     * a day that begins then (a stopped clock, one held at night, a jump with
     * {@code /time add}) brings no travelers, rather than rolling ones who
     * could only be called off.
     */
    public static boolean isMorningWindow(long gameTime, long dayTime) {
        if (!VillageDays.isRunning()) return isBeginningOfDay(dayTime);
        if (isTooLateToArrive(dayTime)) return false;
        long into = VillageDays.ticksIntoDay(gameTime);
        return into >= 0L && into <= ARRIVAL_WINDOW_END;
    }

    /**
     * From {@link #ARRIVAL_CANCEL_CLOCK} (2 PM) until midnight's wrap, no
     * merchant or nomad turns up, however long it waited: arrival timers run
     * on game ticks, so a {@code /time set}, a long hold-up or a world closed
     * and reopened could otherwise bring them in the evening or at night.
     * The founding group is the one exception.
     */
    public static boolean isTooLateToArrive(long dayTime) {
        return Math.floorMod(dayTime, DAY_TICKS) >= ARRIVAL_CANCEL_CLOCK;
    }

    /**
     * Whether a traveler due to leave at {@code departureAt} would have left
     * during a night skip of {@code skippedTicks} that ends now: its leave time
     * falls inside the skipped stretch. Such a traveler sets off at once
     * instead of staying into the new morning. One that came at night (the
     * founding group) is due the next evening and keeps its day.
     */
    public static boolean dueDuringSkippedNight(long departureAt, long now, long skippedTicks) {
        return departureAt > now && departureAt - now <= skippedTicks;
    }

    /**
     * A traveler's leave time after a night skip of {@code skippedTicks} that
     * ends now. Leave times run on game ticks, which a skip does not move, so
     * each one comes that much sooner and keeps its time of day: a guest who
     * stayed the night still leaves between 6 and 8 AM, not that evening. One
     * due during the skipped stretch ({@link #dueDuringSkippedNight}) sets off now.
     */
    public static long afterSkippedNight(long departureAt, long now, long skippedTicks) {
        if (departureAt <= now || skippedTicks <= 0L) return departureAt;
        return Math.max(now, departureAt - skippedTicks);
    }

    /**
     * How far the day clock ran ahead of the game ticks since it was last
     * seen: a jump forward ({@code /time add}, or {@code /time set} to a later
     * time), which no night skip reports. 0 for none, for a clock turned back,
     * or when nothing was seen yet.
     */
    public static long clockJumpAhead(long seenDayTime, long dayTime, long seenGameTime, long gameTime) {
        if (seenDayTime < 0L || seenGameTime < 0L || gameTime < seenGameTime) return 0L;
        return Math.max(0L, (dayTime - seenDayTime) - (gameTime - seenGameTime));
    }

    /** A rolled arrival on an ordinary morning lands somewhere between sunrise and this clock. */
    public static final int ARRIVAL_LATEST_CLOCK = 6_000;
    /** An arrival still held back by thunder at this clock is called off for the day. */
    public static final int ARRIVAL_CANCEL_CLOCK = 8_000;
    /** After a night skip, arrivals land anywhere from waking up to this many ticks later. */
    public static final int WAKE_ARRIVAL_SPREAD_TICKS = 4_800;
    /** A morning roll this soon after a night skip counts as "just woke up". */
    public static final long RECENT_WAKE_TICKS = 200L;

    /**
     * Ticks from now until a rolled arrival shows up. After a night skip it is
     * anywhere from right away to four minutes later, picked evenly; otherwise
     * any time from now until {@link #ARRIVAL_LATEST_CLOCK}.
     */
    public static long arrivalDelay(long dayTime, boolean justWoke, IntUnaryOperator nextInt) {
        if (justWoke) return nextInt.applyAsInt(WAKE_ARRIVAL_SPREAD_TICKS + 1);
        long clock = Math.floorMod(dayTime, DAY_TICKS);
        int room = (int) Math.max(0L, ARRIVAL_LATEST_CLOCK - clock);
        return nextInt.applyAsInt(room + 1);
    }

    /** Game time at which a thunder-delayed arrival for this morning is called off. */
    public static long arrivalCancelAt(long gameTime, long dayTime) {
        long clock = Math.floorMod(dayTime, DAY_TICKS);
        return gameTime + Math.max(0L, ARRIVAL_CANCEL_CLOCK - clock);
    }

    /** Game time of the next sunrise (day clock 0), strictly after now. */
    public static long nextSunrise(long gameTime, long dayTime) {
        return nextOccurrence(gameTime, dayTime, 0);
    }

    public record Departure(long gameTime, int dayClock) {}

    public static Departure staggeredDepartureSchedule(long gameTime, long dayTime,
                                                        Collection<Long> reserved,
                                                        IntUnaryOperator nextInt) {
        int width = DEPARTURE_WINDOW_END - DEPARTURE_WINDOW_START + 1;
        long best = -1L;
        int bestClock = DEPARTURE_WINDOW_START;
        for (int attempt = 0; attempt < 24; attempt++) {
            int clock = DEPARTURE_WINDOW_START + nextInt.applyAsInt(width);
            long candidate = nextOccurrence(gameTime, dayTime, clock);
            best = candidate;
            bestClock = clock;
            if (isSpaced(candidate, reserved)) return new Departure(candidate, clock);
        }
        while (!isSpaced(best, reserved)) best += MIN_DEPARTURE_SPACING_TICKS;
        int shiftedClock = (int) Math.floorMod(dayTime + (best - gameTime), DAY_TICKS);
        return new Departure(best, shiftedClock);
    }

    public static long staggeredDeparture(long gameTime, long dayTime,
                                          Collection<Long> reserved,
                                          IntUnaryOperator nextInt) {
        return staggeredDepartureSchedule(gameTime, dayTime, reserved, nextInt).gameTime();
    }

    private static boolean isSpaced(long candidate, Collection<Long> reserved) {
        for (long existing : reserved) {
            if (Math.abs(existing - candidate) < MIN_DEPARTURE_SPACING_TICKS) return false;
        }
        return true;
    }

    static long nextOccurrence(long gameTime, long dayTime, int targetClock) {
        long clock = Math.floorMod(dayTime, DAY_TICKS);
        long delta = targetClock - clock;
        if (delta <= 0L) delta += DAY_TICKS;
        return gameTime + delta;
    }

    /** Leave times from here on (5 PM) are the evening ones a clock turned back can still bring due. */
    static final int EVENING_TARGET_FROM = 11_000;

    /** True when forward-moving day time crossed this clock, including /time jumps. */
    public static boolean crossedDayClock(long previousDayTime, long currentDayTime,
                                          int targetClock) {
        if (previousDayTime < 0L) return false;
        if (currentDayTime <= previousDayTime) {
            // /time set uses an absolute value and commonly moves a mature
            // world's dayTime backwards, even when the clock itself only moves
            // on (3 PM on day 4 to 4:30 PM on day 0). Read the clock, not the
            // day number.
            long previousClock = Math.floorMod(previousDayTime, DAY_TICKS);
            long currentClock = Math.floorMod(currentDayTime, DAY_TICKS);
            if (currentClock >= previousClock) {
                return previousClock < targetClock && targetClock <= currentClock;
            }
            // The clock was turned back. A leave time in the evening still
            // comes due when the clock lands in the evening past it; one in the
            // morning (a visitor staying the night) waits for its morning, which
            // a skipped night brings forward (see dueDuringSkippedNight).
            return targetClock >= EVENING_TARGET_FROM
                    && currentClock >= targetClock && currentClock <= 18_000L;
        }
        long occurrence = Math.floorDiv(previousDayTime, DAY_TICKS) * DAY_TICKS + targetClock;
        if (occurrence <= previousDayTime) occurrence += DAY_TICKS;
        return occurrence <= currentDayTime;
    }

    // -------------------------------------------------------------------------
    // Staying the evening or the night
    // -------------------------------------------------------------------------

    /** How long a visitor stays, picked when it arrives. */
    public enum StayPlan {
        /** Leaves around sunset, 5:30 to 7:30 PM, as visitors always did. */
        DUSK,
        /** Stays the evening and leaves around 9 PM. */
        EVENING,
        /** Sleeps in the village and leaves the next morning, 6 to 8 AM. */
        NIGHT
    }

    /** An evening-only visitor leaves somewhere in this stretch of the clock (about 8:50 to 9:30 PM). */
    public static final int EVENING_LEAVE_START = 14_800;
    public static final int EVENING_LEAVE_END = 15_500;
    /** A visitor who stayed the night leaves this long after sunrise at the latest (8 AM). */
    public static final int MORNING_LEAVE_SPREAD = 2_000;
    /**
     * A visitor due to leave this soon after now is on its way out: it holds
     * back no morning roll. Covers the whole morning leave spread.
     */
    public static final long LEAVING_SOON_TICKS = MORNING_LEAVE_SPREAD + 400L;

    // Rows: arrives 6 to 8 AM, 8 to 10 AM, 10 AM to noon, after noon. Columns:
    // chance of DUSK, of EVENING; NIGHT is the rest.
    private static final double[][] STAY_WITH_GUEST_BEDS = {
            {0.55, 0.20}, {0.30, 0.20}, {0.0, 0.10}, {0.0, 0.0}};
    private static final double[][] STAY_WITHOUT_GUEST_BEDS = {
            {0.70, 0.18}, {0.45, 0.30}, {0.0, 0.55}, {0.0, 0.50}};

    /**
     * How long a visitor arriving at {@code dayTime} stays. A village with free
     * guest beds keeps more of them overnight; without, about half as many
     * sleep over and more stay the evening instead.
     */
    public static StayPlan rollStayPlan(long dayTime, boolean guestBeds, double roll) {
        long clock = Math.floorMod(dayTime, DAY_TICKS);
        int row = clock < 2_000 ? 0 : clock < 4_000 ? 1 : clock < 6_000 ? 2 : 3;
        double[] chances = (guestBeds ? STAY_WITH_GUEST_BEDS : STAY_WITHOUT_GUEST_BEDS)[row];
        if (roll < chances[0]) return StayPlan.DUSK;
        if (roll < chances[0] + chances[1]) return StayPlan.EVENING;
        return StayPlan.NIGHT;
    }

    /** When a visitor on {@code plan} leaves, spaced from {@code reserved} leave times. */
    public static Departure departureFor(StayPlan plan, long gameTime, long dayTime,
                                         Collection<Long> reserved, IntUnaryOperator nextInt) {
        if (plan == StayPlan.DUSK) {
            return staggeredDepartureSchedule(gameTime, dayTime, reserved, nextInt);
        }
        long at;
        if (plan == StayPlan.EVENING) {
            int clock = EVENING_LEAVE_START
                    + nextInt.applyAsInt(EVENING_LEAVE_END - EVENING_LEAVE_START + 1);
            at = nextOccurrence(gameTime, dayTime, clock);
        } else {
            at = morningAfter(gameTime, dayTime) + nextInt.applyAsInt(MORNING_LEAVE_SPREAD + 1);
        }
        while (!isSpaced(at, reserved)) at += MIN_DEPARTURE_SPACING_TICKS;
        return new Departure(at, (int) Math.floorMod(dayTime + (at - gameTime), DAY_TICKS));
    }

    /**
     * Game time of the coming sunrise that ends tonight: the next clock 0,
     * which for a visitor arriving at sunrise is the following one.
     */
    public static long morningAfter(long gameTime, long dayTime) {
        return gameTime + DAY_TICKS - Math.floorMod(dayTime, DAY_TICKS);
    }

    /** A visitor leaving at {@code departure} is still here after the coming sunrise. */
    public static boolean staysPastSunrise(long departure, long gameTime, long dayTime) {
        return departure >= morningAfter(gameTime, dayTime);
    }

    /**
     * A visitor staying past the coming sunrise only to leave in the morning
     * (not one, like the founding group, staying another whole day).
     */
    public static boolean leavesAfterTonight(long departure, long gameTime, long dayTime) {
        long sunrise = morningAfter(gameTime, dayTime);
        return departure >= sunrise && departure <= sunrise + LEAVING_SOON_TICKS;
    }

    /** Village day a day-clock value belongs to ({@link VillageDays}); it only moves forward. */
    public static long dayIndex(long dayTime) {
        return VillageDays.dayIndex(dayTime);
    }

    /** A new village gets a merchant on each of its first mornings, this many times. */
    public static final int FOUNDING_MERCHANT_VISITS = 3;
    public static final int MEDIUM_VILLAGE_VILLAGERS = 10;
    public static final int LARGE_VILLAGE_VILLAGERS = 16;

    // Roll chance on each morning after the last merchant left, if none has
    // come yet (index 0 = the first morning). The last entry repeats.
    private static final double[] SMALL_VILLAGE_ROLLS = {0.625, 0.70, 0.90};             // avg 1.5
    private static final double[] MEDIUM_VILLAGE_ROLLS = {0.10, 0.50, 0.70, 0.90};       // avg 2.5
    private static final double[] LARGE_VILLAGE_ROLLS = {0.0, 0.20, 0.35, 0.70, 0.90};   // avg 3.5

    /**
     * Every village size now gets the small village's schedule, a merchant
     * about every day and a half. Bigger villages used to wait longer so they
     * could not sell without limit; the merchant's purse (MarketMemory.purseFor)
     * now caps what one visit buys instead, as in Tektopia. The medium and
     * large tables stay for reference.
     */
    static double[] merchantRollsFor(int villagers) {
        return SMALL_VILLAGE_ROLLS;
    }

    /**
     * Chance a merchant arrives on this morning, given that none has since the
     * last one left. Evaluated per morning so a village that grows mid-wait
     * switches schedule.
     */
    public static double merchantChanceForMorning(int morning, int villagers,
                                                  int foundingVisitsLeft) {
        if (morning < 1) return 0.0D;
        if (foundingVisitsLeft > 0) return 1.0D;
        double[] rolls = merchantRollsFor(villagers);
        return rolls[Math.min(morning, rolls.length) - 1];
    }
}
