package com.alex.test.village.brain;

import com.alex.test.village.VillageDays;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One villager's evening timetable, rolled afresh every night.
 *
 * <p>Every time and length comes from the villager's UUID plus the day index,
 * each with its own hash salt: the same all night and across reloads, never
 * the same two nights running, and no two of them moving together (an early
 * finisher is not also an early leaver). They are the moments a transition
 * <em>begins</em>, not deadlines for arriving anywhere. {@link #night} gives
 * one night's values.
 *
 * <p>Rolls that belong to a whole village or building for one night (the
 * early-sleeper share, a tavern's standing room) use {@link #sharedRoll} with
 * that id instead of a villager's.
 *
 * <p>This is deliberately separate from the world night boundary
 * ({@link VillagerBrain#isNight}), which still decides monster danger and
 * waking. Civilian work hours end before it and bedtime starts well after it.
 *
 * <p>Kept free of Minecraft types so the timetable can be unit tested.
 */
public final class EveningSchedule {

    public static final long DAY_TICKS = 24_000L;

    public static final int END_WORK_MIN  = 11_834;
    public static final int END_WORK_MAX  = 12_500;
    /**
     * Tavern, library and house visits start at the villager's own head-out
     * time, rolled right after the working day: the first outing never starts
     * before the villager's own end of work and its chores.
     */
    public static final int HEAD_OUT_MIN  = 12_000;
    public static final int HEAD_OUT_MAX  = 12_800;
    /** Personal leaving time: bed for those at home, the earliest a guest leaves. */
    public static final int LEAVE_MIN     = 14_500;
    public static final int LEAVE_MAX     = 15_500;
    /** Early sleepers go to bed in this window instead. */
    public static final int EARLY_BED_MIN = 13_000;
    public static final int EARLY_BED_MAX = 14_000;

    /** Time at a venue, counted from arrival, so the walk does not eat into it. */
    public static final int TAVERN_STAY_MIN  = 1_500;
    public static final int TAVERN_STAY_MAX  = 2_400;
    public static final int LIBRARY_STAY_MIN = 1_200;
    public static final int LIBRARY_STAY_MAX = 2_100;
    public static final int VISIT_MIN        = 400;
    public static final int VISIT_MAX        = 900;
    public static final int MAX_VISITS       = 2;

    /** Share of a village's civilians that goes to bed early each night. */
    public static final double EARLY_SHARE = 0.10;

    /** The world's dawn: the same value {@link VillagerBrain#isNight} ends night at. */
    public static final int WAKE_TIME = 23_200;

    /**
     * Where a typical villager is in bed, for the offline projection only;
     * live villagers sleep when they actually reach their beds. Early sleepers
     * (10%) lie down around 13,500. Everyone else leaves around 15,000: tavern
     * and library guests head out at 12,000-12,800 and their stay mostly ends
     * before their leaving time, so bed is about 14,900 on average, plus a
     * walk home and a pre-bed meal.
     */
    public static final int PROJECTED_BEDTIME = 15_300;

    /** Mean end-of-work time, for the offline projection. */
    public static final int MEAN_END_WORK = (END_WORK_MIN + END_WORK_MAX) / 2;

    private static final long SALT_END_WORK  = 0x5EED_E0D5_0001L;
    private static final long SALT_HEAD_OUT  = 0x5EED_E0D5_0002L;
    private static final long SALT_LEAVE     = 0x5EED_E0D5_0003L;
    private static final long SALT_EARLY_BED = 0x5EED_E0D5_0004L;
    private static final long SALT_TAVERN    = 0x5EED_E0D5_0005L;
    private static final long SALT_LIBRARY   = 0x5EED_E0D5_0006L;
    private static final long SALT_VISITS    = 0x5EED_E0D5_0007L;
    private static final long SALT_VISIT_1   = 0x5EED_E0D5_0008L;
    private static final long SALT_VISIT_2   = 0x5EED_E0D5_0009L;
    private static final long SALT_EARLY_ROUND = 0x5EED_E0D5_0011L;
    private static final long SALT_EARLY_RANK  = 0x5EED_E0D5_0012L;

    /** Where a villager is in its evening. */
    public enum Phase {
        /** Work hours, or still asleep before dawn. */
        DAY,
        /** Work over: end-of-shift deposit, then home to relax. */
        WIND_DOWN,
        /** From head-out time: tavern, library, visits, or still relaxing at home. */
        GATHER,
        /** Personal leaving time has passed (guests still finish their stay first). */
        NIGHT
    }

    /**
     * One villager's rolls for one night. Times are times of day; stays and
     * visit lengths are ticks after arrival.
     */
    public record Night(long day, int endWork, int headOut, int leave, int earlyBed,
                        int tavernStay, int libraryStay, int visits, int visit1, int visit2) {

        /** Length of visit {@code index} (0 first). */
        public int visitLength(int index) { return index <= 0 ? visit1 : visit2; }

        /** The leaving time that applies: the early bedtime for early sleepers. */
        public int leaveFor(boolean earlySleeper) { return earlySleeper ? earlyBed : leave; }

        public Phase phaseAt(long dayTime, boolean earlySleeper) {
            long t = timeOfDay(dayTime);
            if (t < endWork || t > WAKE_TIME) return Phase.DAY;
            if (t >= leaveFor(earlySleeper)) return Phase.NIGHT;
            if (t < headOut) return Phase.WIND_DOWN;
            return Phase.GATHER;
        }

        /** Ticks left before the leaving time that applies, or 0 once it passed or outside the evening. */
        public long ticksUntilLeave(long dayTime, boolean earlySleeper) {
            long t = timeOfDay(dayTime);
            int leaveAt = leaveFor(earlySleeper);
            if (t < endWork || t >= leaveAt) return 0L;
            return leaveAt - t;
        }

        /**
         * Time of day a bard playing the tavern tonight stops work and sets
         * off: early enough to be at the tavern by {@link #BARD_ARRIVE_BY}
         * given how far away it is. Its own end-of-work roll does not apply on
         * a set night.
         */
        public int bardDeparture(double blocksToTavern) {
            long travel = BARD_TRAVEL_MARGIN_TICKS + Math.round(BARD_TICKS_PER_BLOCK * Math.max(0.0, blocksToTavern));
            return (int) Math.max(BARD_EARLIEST_DEPARTURE, BARD_ARRIVE_BY - travel);
        }

        /**
         * Whether the bard is still needed at the tavern: all through its own
         * evening, and after its own leaving time for as long as any guest is
         * still there. There is no last possible time: guests stay until
         * their stay is used up, and the music lasts as long as they do.
         */
        public boolean bardStillPlaying(long dayTime, boolean guestsPresent) {
            Phase phase = phaseAt(dayTime, false);
            if (phase == Phase.GATHER) return true;
            return phase == Phase.NIGHT && guestsPresent;
        }
    }

    /** The bard aims to be at the tavern when the first guests can arrive. */
    public static final int BARD_ARRIVE_BY = HEAD_OUT_MIN;
    /** However far the tavern, a bard never leaves work before this. */
    public static final int BARD_EARLIEST_DEPARTURE = 10_500;
    /** Rough walking cost for the departure estimate: winding routes included, plus a margin. */
    private static final int BARD_TICKS_PER_BLOCK = 7;
    private static final int BARD_TRAVEL_MARGIN_TICKS = 100;

    private final UUID villagerId;
    private Night cached;

    private EveningSchedule(UUID villagerId) {
        this.villagerId = villagerId;
    }

    public static EveningSchedule forVillager(UUID villagerId) {
        return new EveningSchedule(villagerId);
    }

    /** This villager's rolls for night {@code day}; cached for the last day asked. */
    public Night night(long day) {
        Night n = cached;
        if (n != null && n.day() == day) return n;
        n = new Night(day,
                timeInWindow(villagerId, day, SALT_END_WORK, END_WORK_MIN, END_WORK_MAX),
                timeInWindow(villagerId, day, SALT_HEAD_OUT, HEAD_OUT_MIN, HEAD_OUT_MAX),
                timeInWindow(villagerId, day, SALT_LEAVE, LEAVE_MIN, LEAVE_MAX),
                timeInWindow(villagerId, day, SALT_EARLY_BED, EARLY_BED_MIN, EARLY_BED_MAX),
                timeInWindow(villagerId, day, SALT_TAVERN, TAVERN_STAY_MIN, TAVERN_STAY_MAX),
                timeInWindow(villagerId, day, SALT_LIBRARY, LIBRARY_STAY_MIN, LIBRARY_STAY_MAX),
                timeInWindow(villagerId, day, SALT_VISITS, 1, MAX_VISITS),
                timeInWindow(villagerId, day, SALT_VISIT_1, VISIT_MIN, VISIT_MAX),
                timeInWindow(villagerId, day, SALT_VISIT_2, VISIT_MIN, VISIT_MAX));
        cached = n;
        return n;
    }

    /** The night {@code dayTime} falls in. */
    public Night nightAt(long dayTime) {
        return night(dayIndex(dayTime));
    }

    /** Phase for an ordinary (not early-sleeping) villager. */
    public Phase phaseAt(long dayTime) {
        return nightAt(dayTime).phaseAt(dayTime, false);
    }

    /** Time of day, 0-23999, whatever {@code /time set} did to the absolute clock. */
    public static long timeOfDay(long dayTime) {
        return Math.floorMod(dayTime, DAY_TICKS);
    }

    /**
     * Which village day {@code dayTime} falls on ({@link VillageDays}): the
     * world's day number, except that {@code /time set} never takes it back.
     * Per-night evening state, and everything else done once a day, is keyed
     * by this.
     */
    public static long dayIndex(long dayTime) {
        return VillageDays.dayIndex(dayTime);
    }

    /**
     * The game time a tavern or library guest leaves: the later of its
     * personal leaving time and its arrival plus its stay. Never before the
     * stay is used up, and no hard cutoff.
     */
    public static long guestLeavesAt(long personalLeaveGameTime, long arrivalGameTime, int stay) {
        return Math.max(personalLeaveGameTime, arrivalGameTime + stay);
    }

    /** Whether a stay that began at {@code arrivalGameTime} is used up; false before arrival. */
    public static boolean stayUsedUp(long nowGameTime, long arrivalGameTime, int stay) {
        return arrivalGameTime != Long.MIN_VALUE && nowGameTime >= arrivalGameTime + stay;
    }

    /**
     * A choice that is fixed for this villager for one whole day and differs
     * from day to day: 0 to {@code bound - 1}. Each use passes its own salt so
     * the choices are independent of each other.
     */
    public int dailyRoll(long dayIndex, long salt, int bound) {
        return sharedRoll(villagerId, dayIndex, salt, bound);
    }

    /** As {@link #dailyRoll} but the same every day: a lasting preference. */
    public int lastingRoll(long salt, int bound) {
        return dailyRoll(0L, salt ^ 0x1A57_1A57L, bound);
    }

    /**
     * A roll fixed for one id (a village, a tavern, a villager) for one night:
     * 0 to {@code bound - 1}.
     */
    public static int sharedRoll(UUID id, long dayIndex, long salt, int bound) {
        if (bound <= 1) return 0;
        return (int) Math.floorMod(hash(id, dayIndex, salt), (long) bound);
    }

    /** As {@link #sharedRoll} but a fraction in [0, 1). */
    public static double sharedFraction(UUID id, long dayIndex, long salt) {
        return (hash(id, dayIndex, salt) >>> 11) * 0x1.0p-53;
    }

    // -------------------------------------------------------------------------
    // Early sleepers, per village per night
    // -------------------------------------------------------------------------

    /** Tonight's early-sleeper share for the village: a flat {@value #EARLY_SHARE}. */
    public static double earlyShare(UUID villageId, long day) {
        return EARLY_SHARE;
    }

    /**
     * How many of {@code rosterSize} civilians sleep early tonight: the share,
     * its fraction settled by one roll for the village and night, so a small
     * village gets none on some nights and one or two on others.
     */
    public static int earlySleeperCount(UUID villageId, long day, int rosterSize) {
        if (rosterSize <= 0) return 0;
        double expected = rosterSize * earlyShare(villageId, day);
        int count = (int) Math.floor(expected);
        if (sharedFraction(villageId, day, SALT_EARLY_ROUND) < expected - count) count++;
        return Math.min(count, rosterSize);
    }

    /** Whether {@code villager} is one of tonight's early sleepers: the lowest of a UUID + day ranking. */
    public static boolean isEarlySleeper(UUID villager, Collection<UUID> roster, UUID villageId, long day) {
        if (!roster.contains(villager)) return false;
        int count = earlySleeperCount(villageId, day, roster.size());
        if (count <= 0) return false;
        List<UUID> order = new ArrayList<>(roster);
        order.sort(Comparator.comparingLong((UUID id) -> hash(id, day, SALT_EARLY_RANK))
                .thenComparing(UUID::compareTo));
        return order.indexOf(villager) < count;
    }

    // -------------------------------------------------------------------------

    /** Inclusive, uniform-ish pick in {@code [min, max]} for one villager and night. */
    static int timeInWindow(UUID id, long day, long salt, int min, int max) {
        return min + sharedRoll(id, day, salt, max - min + 1);
    }

    private static long hash(UUID id, long day, long salt) {
        return mix(id.getMostSignificantBits()
                ^ Long.rotateLeft(id.getLeastSignificantBits(), 17)
                ^ salt * 0x9E37_79B9_7F4A_7C15L
                ^ day * 0xC2B2_AE3D_27D4_EB4FL);
    }

    /** SplitMix64 finaliser: spreads close inputs (and similar UUIDs) apart. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
        return z ^ (z >>> 31);
    }
}
