package com.alex.test.village;

/**
 * The village calendar: the day number behind everything that happens once a
 * day (nomad and merchant mornings, the daily XP limit, evening plans,
 * inspections, cauldron refills, the tavern cake...).
 *
 * <p>Minecraft's own day number is {@code dayTime / 24000}, and {@code /time set}
 * sets {@code dayTime} outright: {@code /time set day} puts it back to 1000 on
 * day 0 every time, so a world that skips its nights that way never saw a new
 * day. This calendar is the world clock plus a whole number of days, so the
 * time of day is always the world's own, and its day number only moves forward:
 * <ul>
 *   <li>The clock running on, sleeping, {@code /time add}: the same as the
 *       world. A world nobody sets the time in keeps Minecraft's day numbers.</li>
 *   <li>The clock turned back to daytime from the evening or night: the night
 *       was skipped, a new morning (once a minute was played that day, so
 *       flicking the time back and forth does not rush the days).</li>
 *   <li>The clock turned back from the afternoon: a new morning only once most
 *       of a day was played (7.5 minutes). A small turn back is the same day.</li>
 *   <li>A clock that barely moves for a full day of play (the daylight cycle
 *       off, a command block holding the time): a new day every 20 minutes.
 *       A slow clock (a mod making the days longer) is not a stopped one.</li>
 *   <li>A clock that runs but keeps being turned back before it reaches the
 *       next morning (a command block or server plugin looping it): a new day
 *       once it has run a whole day's worth, at the next daytime; held at
 *       night, after two days' worth.</li>
 * </ul>
 *
 * <p>The day number never goes back, not even for the rest of a tick in which
 * something set the clock: the calendar sees that at the start of the next one.
 *
 * <p>Pure logic without Minecraft types, so unit tests can use it. The server
 * feeds it the overworld clock at the start of every tick and saves it with
 * the world ({@link VillageDaysEvents}).
 */
public final class VillageDays {
    public static final long DAY_TICKS = 24_000L;
    /** From this time of day on, turning the clock back skips the evening or night. */
    public static final long EVENING_CLOCK = 12_000L;
    /** Play since the day began before a turn back from the evening or night is a new morning. */
    public static final long NIGHT_SKIP_MIN_PLAY = 1_200L;
    /** Play since the day began before a turn back from the afternoon is a new morning. */
    public static final long AFTERNOON_SKIP_MIN_PLAY = 9_000L;
    /** A smaller turn back is a nudge (or a command block holding the time), never a new morning. */
    public static final long MIN_TURN_BACK = 2_000L;
    /** Play with the clock nearly still before the day moves on by itself. */
    public static final long STOPPED_DAY_PLAY = DAY_TICKS;
    /**
     * Clock movement over {@link #STOPPED_DAY_PLAY} below which the clock counts
     * as stopped: 1 %, so days up to 100 times longer than Minecraft's (mods
     * that slow the clock or follow real time) still count as running.
     */
    public static final long STOPPED_MAX_PROGRESS = 240L;
    /** Clock running since the day began, across turn backs, that makes a new day once it reads daytime. */
    public static final long LOOPED_DAY_RUN = DAY_TICKS;
    /** The same at any time of day, for a clock held at night by turning it back. */
    public static final long LOOPED_NIGHT_RUN = 2 * DAY_TICKS;
    /** The clock run is saved in steps of this much, not every tick. */
    private static final long RUN_SAVE_STEP = 1_200L;

    /** From {@link #observe}: no night was skipped. */
    public static final long NO_SKIP = -1L;

    /** The running world's calendar; a fresh one (the world's own days) when none is loaded. */
    private static volatile VillageDays current = new VillageDays();

    /** Whole days added to the world clock: always a multiple of {@link #DAY_TICKS}. */
    private volatile long offset;
    private boolean primed;
    private long lastDayTime;
    private long lastGameTime;
    /** Game time the current day began. */
    private long dayStartGameTime;
    /** Start of the stretch checked for a stopped clock: game time and village time. */
    private long stillSinceGameTime;
    private long stillSinceVillageTime;
    /** Furthest village time reached in that stretch. */
    private long furthestVillageTime;
    /**
     * How long the clock ran forward since the day began, counted across turn
     * backs; at most one tick per tick of play, so jumps ahead do not count.
     */
    private long run;
    private boolean restored;
    private boolean dirty;

    public VillageDays() {}

    // -------------------------------------------------------------------------
    // The running world's calendar
    // -------------------------------------------------------------------------

    public static VillageDays current() { return current; }

    public static void install(VillageDays days) { current = days != null ? days : new VillageDays(); }

    /** Back to the world's own days, for the next world loaded in this game. */
    public static void reset() { current = new VillageDays(); }

    /** Which village day {@code dayTime} falls on. */
    public static long dayIndex(long dayTime) { return current.day(dayTime); }

    /**
     * {@code dayTime} on the village calendar: the same time of day, with the
     * village's day number. For timestamps shown as "day N".
     */
    public static long villageTime(long dayTime) { return current.toVillageTime(dayTime); }

    /** Game ticks since the current village day began. */
    public static long ticksIntoDay(long gameTime) { return current.ticksIntoToday(gameTime); }

    /** Whether the calendar has seen the world clock (false before a world is running). */
    public static boolean isRunning() { return current.primed; }

    // -------------------------------------------------------------------------

    /**
     * The village day {@code dayTime} falls on, never before the day last seen:
     * a command block or another mod setting the clock in the middle of a tick
     * is only seen at the start of the next one, and until then the old offset
     * would put the new clock on an earlier day.
     */
    public long day(long dayTime) {
        long day = Math.floorDiv(dayTime + offset, DAY_TICKS);
        return primed ? Math.max(day, seenDay()) : day;
    }

    /** {@code dayTime} on the village calendar, on the day last seen at the earliest (see {@link #day}). */
    public long toVillageTime(long dayTime) {
        long time = dayTime + offset;
        if (!primed) return time;
        long seen = seenDay();
        return Math.floorDiv(time, DAY_TICKS) < seen
                ? seen * DAY_TICKS + Math.floorMod(dayTime, DAY_TICKS) : time;
    }

    private long seenDay() { return Math.floorDiv(lastDayTime + offset, DAY_TICKS); }

    public long ticksIntoToday(long gameTime) { return gameTime - dayStartGameTime; }

    /**
     * Looks at the world clock; call once per tick, before anything reads the
     * day. Returns how many ticks of evening and night a turn back of the clock
     * skipped when that started a new morning, else {@link #NO_SKIP}.
     */
    public long observe(long dayTime, long gameTime) {
        if (!primed) {
            prime(dayTime, gameTime);
            return NO_SKIP;
        }
        if (dayTime == lastDayTime && gameTime == lastGameTime) return NO_SKIP;
        long previousDay = day(lastDayTime);
        if (gameTime < lastGameTime) {
            // Game time never runs backwards in one world. Keep the day and
            // start the checks over rather than guess.
            offset = (previousDay - Math.floorDiv(dayTime, DAY_TICKS)) * DAY_TICKS;
            lastDayTime = dayTime;
            lastGameTime = gameTime;
            startDay(gameTime, dayTime + offset);
            return NO_SKIP;
        }

        long skipped = NO_SKIP;
        if (dayTime < lastDayTime) {
            // /time set to an earlier time. The world's day number may have
            // gone back too; ours stays, or moves on by one for a new morning.
            long previousClock = Math.floorMod(lastDayTime, DAY_TICKS);
            long clock = Math.floorMod(dayTime, DAY_TICKS);
            boolean fromEvening = previousClock >= EVENING_CLOCK;
            long played = gameTime - dayStartGameTime;
            boolean newMorning = clock < EVENING_CLOCK
                    && previousClock - clock >= MIN_TURN_BACK
                    && played >= (fromEvening ? NIGHT_SKIP_MIN_PLAY : AFTERNOON_SKIP_MIN_PLAY);
            long day = newMorning ? previousDay + 1 : previousDay;
            offset = (day - Math.floorDiv(dayTime, DAY_TICKS)) * DAY_TICKS;
            dirty = true;
            if (newMorning && fromEvening) skipped = DAY_TICKS - previousClock + clock;
        } else {
            addRun(Math.min(dayTime - lastDayTime, gameTime - lastGameTime));
        }
        lastDayTime = dayTime;
        lastGameTime = gameTime;

        long villageTime = dayTime + offset;
        if (Math.floorDiv(villageTime, DAY_TICKS) > previousDay) {
            startDay(gameTime, villageTime);
            return skipped;
        }
        if (run >= LOOPED_NIGHT_RUN
                || run >= LOOPED_DAY_RUN && Math.floorMod(dayTime, DAY_TICKS) < EVENING_CLOCK) {
            // The clock runs, but something keeps turning it back before the
            // next morning, in turn backs too small or too late to count. One
            // turn back of that kind does not get here: the clock reaches the
            // next morning first (only running counts, not jumps ahead).
            offset += DAY_TICKS;
            startDay(gameTime, villageTime + DAY_TICKS);
            return NO_SKIP;
        }
        if (villageTime > furthestVillageTime) furthestVillageTime = villageTime;
        if (gameTime - stillSinceGameTime >= STOPPED_DAY_PLAY) {
            if (furthestVillageTime - stillSinceVillageTime < STOPPED_MAX_PROGRESS) {
                // The clock stood still for a whole day of play: move on by a day.
                offset += DAY_TICKS;
                startDay(gameTime, villageTime + DAY_TICKS);
            } else {
                // The clock is moving; check the next stretch.
                stillSinceGameTime = gameTime;
                stillSinceVillageTime = villageTime;
                furthestVillageTime = villageTime;
                dirty = true;
            }
        }
        return skipped;
    }

    private void prime(long dayTime, long gameTime) {
        primed = true;
        lastDayTime = dayTime;
        lastGameTime = gameTime;
        long villageTime = dayTime + offset;
        if (!restored) {
            // No saved calendar: the world's own days, this one begun at sunrise.
            dayStartGameTime = gameTime - Math.floorMod(villageTime, DAY_TICKS);
            run = Math.floorMod(villageTime, DAY_TICKS);
            stillSinceGameTime = gameTime;
            stillSinceVillageTime = villageTime;
            furthestVillageTime = villageTime;
            dirty = true;
            return;
        }
        // A saved calendar from another copy of the world can point ahead of now.
        if (dayStartGameTime > gameTime) dayStartGameTime = gameTime;
        if (stillSinceGameTime > gameTime) {
            stillSinceGameTime = gameTime;
            stillSinceVillageTime = villageTime;
            furthestVillageTime = villageTime;
        }
        if (villageTime > furthestVillageTime) furthestVillageTime = villageTime;
    }

    private void startDay(long gameTime, long villageTime) {
        dayStartGameTime = gameTime;
        stillSinceGameTime = gameTime;
        stillSinceVillageTime = villageTime;
        furthestVillageTime = villageTime;
        run = 0L;
        dirty = true;
    }

    private void addRun(long ticks) {
        if (ticks <= 0L) return;
        long before = run;
        run += ticks;
        if (run / RUN_SAVE_STEP != before / RUN_SAVE_STEP) dirty = true;
    }

    // -------------------------------------------------------------------------
    // Saving
    // -------------------------------------------------------------------------

    /** True once since the last call when something worth saving changed. */
    public boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    public long offset() { return offset; }
    public long dayStartGameTime() { return dayStartGameTime; }
    public long stillSinceGameTime() { return stillSinceGameTime; }
    public long stillSinceVillageTime() { return stillSinceVillageTime; }
    public long furthestVillageTime() { return furthestVillageTime; }
    public long run() { return run; }

    /** Restores a saved calendar; the first {@link #observe} then carries on from it. */
    public void restore(long offset, long dayStartGameTime, long stillSinceGameTime,
                        long stillSinceVillageTime, long furthestVillageTime, long run) {
        this.offset = Math.floorDiv(offset, DAY_TICKS) * DAY_TICKS;
        this.dayStartGameTime = dayStartGameTime;
        this.stillSinceGameTime = stillSinceGameTime;
        this.stillSinceVillageTime = stillSinceVillageTime;
        this.furthestVillageTime = Math.max(furthestVillageTime, stillSinceVillageTime);
        this.run = Math.max(0L, run);
        this.restored = true;
        this.primed = false;
    }
}
