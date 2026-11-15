package com.alex.test.village.brain;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What one villager has planned and already done this evening, so the
 * evening routine does not repeat itself: tonight's plan, one end-of-shift
 * deposit, one meal, when it arrived at its venue, the house visits made and
 * the hosts already seen, and a decision to leave early that sticks.
 *
 * <p>Keyed by the day index. Any change of day, forwards or backwards, starts
 * a fresh evening, and so does seeing the daytime phase: {@code /time set day}
 * followed by an evening on the same day index must not find last evening's
 * deposit already marked done. The homebody streak is the one thing that
 * spans nights, so a fresh evening keeps it.
 *
 * <p>Minecraft-free; {@link VillagerBrain} stores the fields in the entity's
 * brain data.
 */
public final class EveningState {

    /** End-of-shift storage trips tried before the villager gives up for the day. */
    public static final int MAX_DEPOSIT_ATTEMPTS = 2;
    /** Hosts remembered per night; more visits than this never happen. */
    private static final int MAX_HOSTS = 4;

    long    day = Long.MIN_VALUE;
    boolean depositDone;
    int     depositAttempts;
    boolean mealDone;
    boolean leftEarly;
    /** The tavern could not be reached this evening. */
    boolean tavernFailed;
    /** The bard's evening set ended on its own (disabled, nowhere to play). */
    boolean bardSetFailed;
    /** The bard has reached tonight's performance step. */
    boolean bardSetStarted;
    /** The custom recording has started tonight; interruptions must not replay it. */
    boolean bardPianoPlayed;
    /** The library could not be reached or used this evening. */
    boolean libraryFailed;
    /** No chair and no standing room at the tavern tonight. */
    boolean tavernFull;
    /** The library was at its cap, or had no free place, tonight. */
    boolean libraryFull;
    /** A dirty farmer has washed at a cauldron tonight (or had nothing to wash off). */
    boolean washDone;

    /** Tonight's plan, or null until rolled. */
    @Nullable EveningPlanner.Plan plan;
    /** Game time of the first arrival at {@link #arrivalVenue}; {@code Long.MIN_VALUE} for none. */
    long    arrivalTick = Long.MIN_VALUE;
    @Nullable UUID arrivalVenue;
    /** Game time this villager leaves its venue, once known; for the record only. */
    long    leaveAt = Long.MIN_VALUE;
    int     visitsPlanned;
    int     visitsDone;
    final List<UUID> hostsVisited = new ArrayList<>();
    /** Last day index of a homebody streak (inclusive); spans nights. */
    long    homebodyUntil = Long.MIN_VALUE;
    /** The evening has begun (work over logged). */
    boolean started;
    /** Tonight's order of outings, and how far through it the villager is. */
    EveningPlanner.Sequence sequence = EveningPlanner.Sequence.SINGLE;
    int     stage;
    /** Something good happened tonight (a chat, the bard): tomorrow leans the same way. */
    boolean goodNight;
    /** Habit for the next night: which plan, how much weight, and for which night. Spans nights. */
    @Nullable EveningPlanner.Plan habitPlan;
    int     habitDelta;
    long    habitDay = Long.MIN_VALUE;

    /** Bring the state up to date with the clock; returns true when a fresh evening began. */
    public boolean sync(long dayIndex, EveningSchedule.Phase phase) {
        if (phase == EveningSchedule.Phase.DAY) {
            // Daytime clears the evening even on the same day index, so a
            // backwards time jump replays it from the start.
            boolean dirty = isDirty();
            reset(dayIndex);
            return dirty;
        }
        if (day != dayIndex) {
            reset(dayIndex);
            return true;
        }
        return false;
    }

    private boolean isDirty() {
        return depositDone || depositAttempts > 0 || mealDone
                || leftEarly || tavernFailed || bardSetFailed || bardSetStarted || bardPianoPlayed || libraryFailed
                || tavernFull || libraryFull || washDone || plan != null
                || arrivalTick != Long.MIN_VALUE || visitsDone > 0 || visitsPlanned > 0
                || started || stage > 0 || goodNight;
    }

    private void reset(long dayIndex) {
        day             = dayIndex;
        depositDone     = false;
        depositAttempts = 0;
        mealDone        = false;
        leftEarly       = false;
        tavernFailed    = false;
        bardSetFailed   = false;
        bardSetStarted  = false;
        bardPianoPlayed = false;
        libraryFailed   = false;
        tavernFull      = false;
        libraryFull     = false;
        washDone        = false;
        plan            = null;
        arrivalTick     = Long.MIN_VALUE;
        arrivalVenue    = null;
        leaveAt         = Long.MIN_VALUE;
        visitsPlanned   = 0;
        visitsDone      = 0;
        hostsVisited.clear();
        started         = false;
        sequence        = EveningPlanner.Sequence.SINGLE;
        stage           = 0;
        goodNight       = false;
    }

    public boolean depositDue() {
        return !depositDone && depositAttempts < MAX_DEPOSIT_ATTEMPTS;
    }

    public void onDepositStarted()  { depositAttempts++; }
    public void onDepositFinished() { depositDone = true; }
    public void onMealStarted()     { mealDone = true; }
    public void onLeftEarly()       { leftEarly = true; }
    public void onTavernFailed()    { tavernFailed = true; }
    public void onBardSetFailed()   { bardSetFailed = true; }
    public void onBardSetStarted()  { bardSetStarted = true; }
    public boolean tryStartBardPiano() {
        if (bardPianoPlayed) return false;
        bardPianoPlayed = true;
        return true;
    }
    public void onLibraryFailed()   { libraryFailed = true; }
    public void onTavernFull()      { tavernFull = true; }
    public void onLibraryFull()     { libraryFull = true; }
    public void onWashFinished()    { washDone = true; }

    /** Tonight's plan, rolled once. */
    public void setPlan(EveningPlanner.Plan plan, int visits) {
        this.plan = plan;
        this.visitsPlanned = Math.max(0, visits);
    }

    /** A full or unreachable venue turns into one neighbour visit, if none was planned. */
    public void allowOverflowVisit() {
        if (visitsPlanned == 0) visitsPlanned = 1;
    }

    /**
     * Settled at {@code venue} at {@code gameTime}. The first arrival counts:
     * coming back to the same venue after an interruption keeps it, so only
     * the remaining time counts. Returns true when this is a new arrival.
     */
    public boolean onArrived(UUID venue, long gameTime) {
        if (venue.equals(arrivalVenue) && arrivalTick != Long.MIN_VALUE) return false;
        arrivalVenue = venue;
        arrivalTick = gameTime;
        leaveAt = Long.MIN_VALUE;
        return true;
    }

    /** Arrival tick at {@code venue}, or {@code Long.MIN_VALUE} if not arrived there. */
    public long arrivalAt(UUID venue) {
        return venue.equals(arrivalVenue) ? arrivalTick : Long.MIN_VALUE;
    }

    public void setLeaveAt(long gameTime) { leaveAt = gameTime; }

    /** One house visit ended; the host is not visited again tonight. */
    public void onVisitFinished(UUID host) {
        visitsDone++;
        if (!hostsVisited.contains(host) && hostsVisited.size() < MAX_HOSTS) hostsVisited.add(host);
        arrivalVenue = null;
        arrivalTick = Long.MIN_VALUE;
    }

    /** Marks the evening begun; true the first time only. */
    public boolean onStarted() {
        if (started) return false;
        started = true;
        return true;
    }

    public void setSequence(EveningPlanner.Sequence sequence) {
        this.sequence = sequence;
        this.stage = 0;
    }

    public EveningPlanner.Sequence sequence() { return sequence; }
    public int stage() { return stage; }
    public void advanceStage() { stage++; }

    public void onGoodNight() { goodNight = true; }
    public boolean goodNight() { return goodNight; }

    /** Sets tomorrow's habit: {@code delta} weight for {@code plan} on night {@code forDay}. */
    public void setHabit(@Nullable EveningPlanner.Plan plan, int delta, long forDay) {
        habitPlan = plan;
        habitDelta = delta;
        habitDay = forDay;
    }

    /** Habit weight for {@code plan} on night {@code day}; 0 when none applies. */
    public int habitFor(long day, EveningPlanner.Plan plan) {
        return habitDay == day && habitPlan == plan ? habitDelta : 0;
    }

    @Nullable public EveningPlanner.Plan habitPlan() { return habitPlan; }
    public int habitDelta() { return habitDelta; }
    public long habitDay() { return habitDay; }

    public void startHomebodyStreak(long lastDay) { homebodyUntil = lastDay; }
    public boolean homebodyTonight() { return homebodyOn(day); }

    /**
     * Whether night {@code day} falls in the stay-home streak. A streak ending
     * further ahead than any streak can last was saved on a world day number
     * that /time set later took back; it is over.
     */
    public boolean homebodyOn(long day) {
        return homebodyUntil != Long.MIN_VALUE && day <= homebodyUntil
                && homebodyUntil - day <= EveningPlanner.HOMEBODY_MAX_NIGHTS;
    }

    public boolean depositDone()     { return depositDone; }
    public boolean washDone()        { return washDone; }
    public int     depositAttempts() { return depositAttempts; }
    public boolean mealDone()        { return mealDone; }
    public boolean leftEarly()       { return leftEarly; }
    public boolean tavernFailed()    { return tavernFailed; }
    public boolean bardSetFailed()   { return bardSetFailed; }
    public boolean bardSetStarted()  { return bardSetStarted; }
    public boolean bardPianoPlayed() { return bardPianoPlayed; }
    public boolean libraryFailed()   { return libraryFailed; }
    public boolean tavernFull()      { return tavernFull; }
    public boolean libraryFull()     { return libraryFull; }
    @Nullable public EveningPlanner.Plan plan() { return plan; }
    public long    arrivalTick()     { return arrivalTick; }
    @Nullable public UUID arrivalVenue() { return arrivalVenue; }
    public long    leaveAt()         { return leaveAt; }
    public int     visitsPlanned()   { return visitsPlanned; }
    public int     visitsDone()      { return visitsDone; }
    public boolean visitsLeft()      { return visitsDone < visitsPlanned; }
    public List<UUID> hostsVisited() { return hostsVisited; }
    public long    homebodyUntil()   { return homebodyUntil; }

    // -------------------------------------------------------------------------
    // Saving
    // -------------------------------------------------------------------------

    /** Packed flags for saving; see {@link #unpack}. */
    public int packFlags() {
        return (depositDone ? 1 : 0)
                | (mealDone ? 2 : 0)
                | (leftEarly ? 8 : 0)
                | (tavernFailed ? 16 : 0)
                | (bardSetFailed ? 32 : 0)
                | (libraryFailed ? 64 : 0)
                | (Math.min(depositAttempts, 15) << 8)
                | (tavernFull ? 1 << 12 : 0)
                | (libraryFull ? 1 << 13 : 0)
                | (started ? 1 << 14 : 0)
                | (goodNight ? 1 << 15 : 0)
                | (bardSetStarted ? 1 << 16 : 0)
                | (washDone ? 1 << 17 : 0)
                | (bardPianoPlayed ? 1 << 18 : 0);
    }

    /**
     * Restores the flags. Bit 4 (a visit made, in the old single-visit
     * format) is ignored; an old save simply has no plan yet and rolls one.
     */
    public void unpack(long savedDay, int flags) {
        day             = savedDay;
        depositDone     = (flags & 1) != 0;
        mealDone        = (flags & 2) != 0;
        leftEarly       = (flags & 8) != 0;
        tavernFailed    = (flags & 16) != 0;
        bardSetFailed   = (flags & 32) != 0;
        libraryFailed   = (flags & 64) != 0;
        depositAttempts = (flags >> 8) & 15;
        tavernFull      = (flags & (1 << 12)) != 0;
        libraryFull     = (flags & (1 << 13)) != 0;
        started         = (flags & (1 << 14)) != 0;
        goodNight       = (flags & (1 << 15)) != 0;
        bardSetStarted  = (flags & (1 << 16)) != 0;
        washDone        = (flags & (1 << 17)) != 0;
        bardPianoPlayed = (flags & (1 << 18)) != 0;
    }

    /** Plan and visit counts packed into one int: plan ordinal + 1 (0 = none), visits planned, visits done. */
    public int packPlan() {
        int p = plan == null ? 0 : plan.ordinal() + 1;
        return (p & 0xF) | ((Math.min(visitsPlanned, 15)) << 4) | ((Math.min(visitsDone, 15)) << 8)
                | (sequence.ordinal() << 12) | (Math.min(stage, 15) << 16);
    }

    public void unpackPlan(int packed) {
        int p = packed & 0xF;
        EveningPlanner.Plan[] plans = EveningPlanner.Plan.values();
        plan = p >= 1 && p <= plans.length ? plans[p - 1] : null;
        visitsPlanned = (packed >> 4) & 0xF;
        visitsDone = (packed >> 8) & 0xF;
        EveningPlanner.Sequence[] seqs = EveningPlanner.Sequence.values();
        int s = (packed >> 12) & 0xF;
        sequence = s < seqs.length ? seqs[s] : EveningPlanner.Sequence.SINGLE;
        stage = (packed >> 16) & 0xF;
        // Earlier piano builds advanced the bard to socializing after one track.
        // Resume those saves as an instrumental set, preserving the played flag.
        if (plan == EveningPlanner.Plan.BARD) stage = 0;
    }

    /** Restores the arrival and leave moments; an unknown venue means no arrival. */
    public void restoreArrival(@Nullable UUID venue, long arrival, long leave) {
        arrivalVenue = venue;
        arrivalTick = venue == null ? Long.MIN_VALUE : arrival;
        leaveAt = leave;
    }

    public void restoreHosts(List<UUID> hosts) {
        hostsVisited.clear();
        for (UUID h : hosts) if (hostsVisited.size() < MAX_HOSTS) hostsVisited.add(h);
    }

    public long day() { return day; }
}
