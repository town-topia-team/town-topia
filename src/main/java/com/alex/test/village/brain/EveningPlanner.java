package com.alex.test.village.brain;

import java.util.UUID;

/**
 * The evening routine's rules as pure functions: tonight's plan
 * ({@link #rollPlan}), the order of the evening ({@link #rollSequence},
 * {@link #steps}), who gets into the tavern ({@link #admit}) and, tick by
 * tick, what to do next ({@link #decide}).
 *
 * <p>{@link VillagerBrain} gathers the facts (what the villager is doing, what
 * the village has, what it already did tonight) and acts on the answer. Keeping
 * the rules here, free of Minecraft types, is what lets them be unit tested:
 *
 * <ol>
 *   <li>Danger, critical needs and a locked work step are never interrupted
 *       (the brain's earlier overrides handle danger and hunger).</li>
 *   <li>End-of-shift deposit first, once. A dirty farmer also washes at a
 *       cauldron, before or after the deposit by tonight's roll, and before
 *       bed whatever the time; only critical hunger puts the wash off.</li>
 *   <li>Then tonight's sequence, one step at a time: chores (a food top-up),
 *       home, the tavern, the library or a neighbour's house. Outings wait
 *       for the villager's head-out time; a full or unreachable venue becomes
 *       a visit or home.</li>
 *   <li>Bed at the personal leaving time, except that a guest at the night's
 *       last venue first uses up its stay, and a visit under way is
 *       finished. Tired, hurt or starving villagers leave early.</li>
 * </ol>
 */
public final class EveningPlanner {

    private EveningPlanner() {}

    /** Tonight's plan. */
    public enum Plan {
        /** One of tonight's early sleepers: home, then bed early. */
        EARLY,
        HOME,
        TAVERN,
        LIBRARY,
        VISIT,
        /** A bard playing its evening set at the tavern. */
        BARD
    }

    /** Tonight's order of outings; rolled per villager per night. */
    public enum Sequence {
        /** Just the plan's venue (home, a visit round, the library, the bard's set). */
        SINGLE,
        /** Food top-up if needed, home until head-out, the tavern, bed. */
        CHORES_THEN_TAVERN,
        /** The tavern for its stay, then one visit, home, bed. */
        TAVERN_THEN_VISIT,
        /** The tavern for its stay, then a food top-up if needed, home, bed. */
        TAVERN_THEN_CHORES,
        /** One visit, then the tavern, bed. */
        VISIT_THEN_TAVERN,
        /** Food top-up if needed, home until head-out, the library, bed. */
        CHORES_THEN_LIBRARY
    }

    /** One step of the evening. */
    public enum Step { CHORES, HOME, TAVERN, LIBRARY, VISIT, BARD }

    /** A villager's lasting evening favourite; the same every day. */
    public enum Favourite { TAVERN_REGULAR, BOOKWORM, VISITOR, HOMEBODY }

    /** What a job leans towards in the evening. */
    public enum JobLean { TAVERN, LIBRARY, NONE }

    /** The village's night of the week. */
    public enum NightType { NORMAL, TAVERN_NIGHT, QUIET_NIGHT }

    /** What the villager is doing now, as far as the evening rules care. */
    public enum Current {
        NONE,
        /** Routine work that may be stopped. */
        WORK,
        /** A work step that must reach its safe stopping point first. */
        WORK_LOCKED,
        CONVERSE,
        FLEE,
        EAT,
        FETCH_FOOD,
        /** Any storage trip, the end-of-shift deposit included. */
        CHEST,
        /** In bed, or on the way to it. */
        SLEEP,
        HOME,
        TAVERN,
        VISIT,
        BARD_SET,
        /** At the library, or walking there. */
        LIBRARY,
        /** A farmer's cauldron wash, or a cauldron refill, under way. */
        WASH,
        /** Dancing with a partner at the tavern (PairDance); short, and ends on its own. */
        DANCE,
        OTHER
    }

    public enum Decision {
        /** Leave the current activity alone. */
        KEEP,
        DEPOSIT,
        /** The evening chores: a food top-up. */
        CHORES,
        EAT,
        HOME,
        TAVERN,
        VISIT,
        BARD_SET,
        LIBRARY,
        /** A dirty farmer washes at a cauldron. */
        WASH,
        /** Pre-bed meal and food top-up if needed, then home to sleep. */
        BEDTIME
    }

    public record Facts(
            EveningSchedule.Phase phase,
            Current current,
            /** Guards and archers keep their own shift. */
            boolean defender,
            boolean escapingWater,
            /** The end-of-shift deposit is still due and something is worth depositing. */
            boolean depositDue,
            /** What tonight's sequence says to do now (outings only from head-out time). */
            Step step,
            /** The step is the night's last: its venue keeps the guest until its leaving time. */
            boolean lastStep,
            /** A valid tavern this villager can reach, not failed or full tonight. */
            boolean tavernAvailable,
            /** A valid library this villager can reach, not failed or full tonight. */
            boolean libraryAvailable,
            /** A bard with duty enabled, rested enough, and a tavern to play in. */
            boolean bardCanPerform,
            /** Arrived at this step's tavern or library and its stay is used up. */
            boolean stayComplete,
            /** A house visit is under way: a host chosen, on the way or there, time not used. */
            boolean visitActive,
            /** A host is chosen for a visit that has not started yet. */
            boolean visitWanted,
            /** The chores step has something to do (a food top-up is due). */
            boolean choresDue,
            /** Tired, hurt or starving: head home now. */
            boolean leaveEarly,
            /** Hungry enough, carrying food, settled in, and this is where it eats tonight. */
            boolean mealNow,
            /** In a chat with a visiting guest (or its host): finished even past leaving time. */
            boolean visitChat,
            /** A dirty farmer, not washed tonight, with a cauldron to wash in (or to wait for). */
            boolean washDue,
            /** Tonight's roll: wash before the end-of-shift deposit rather than after it. */
            boolean washFirst,
            /** Critically hungry: the wash waits for food. */
            boolean hungerCritical) {}

    public static Decision decide(Facts f) {
        if (f.defender() || f.escapingWater()) return Decision.KEEP;
        if (f.phase() == EveningSchedule.Phase.DAY) return Decision.KEEP;

        switch (f.current()) {
            case FLEE, EAT, FETCH_FOOD, CHEST, WORK_LOCKED, WASH, DANCE -> { return Decision.KEEP; }
            default -> { }
        }

        // A farmer already in bed is not woken to wash.
        boolean washNow = f.washDue() && !f.hungerCritical() && f.current() != Current.SLEEP;
        boolean night = f.phase() == EveningSchedule.Phase.NIGHT;
        boolean bedtime = f.leaveEarly() || (night && !stillStaying(f));
        if (bedtime) {
            // A host or guest finishes a visit chat before going to bed.
            if (f.current() == Current.CONVERSE && f.visitChat() && !f.leaveEarly()) return Decision.KEEP;
            // Nobody goes to bed dirty while there is a cauldron to wash in.
            if (washNow) return Decision.WASH;
            // Any other conversation does not hold anyone past its leaving time.
            return f.current() == Current.SLEEP ? Decision.KEEP : Decision.BEDTIME;
        }

        // Talking to a housemate or a neighbour is part of the evening; the
        // next pick resumes it (seated, see EveningActivity) once the chat ends.
        if (f.current() == Current.CONVERSE) return Decision.KEEP;

        if (washNow && (f.washFirst() || !f.depositDue())) return Decision.WASH;
        if (f.depositDue()) return Decision.DEPOSIT;
        if (f.mealNow() && isSettled(f.current())) return Decision.EAT;

        switch (f.step()) {
            case CHORES -> {
                if (f.choresDue()) return Decision.CHORES;
            }
            case BARD -> {
                if (f.bardCanPerform() && f.tavernAvailable()) {
                    return want(f.current(), Current.BARD_SET, Decision.BARD_SET);
                }
            }
            case TAVERN -> {
                if (f.tavernAvailable() && !stayOver(f)) {
                    return want(f.current(), Current.TAVERN, Decision.TAVERN);
                }
                return visitOrHome(f, night);
            }
            case LIBRARY -> {
                if (f.libraryAvailable() && !stayOver(f)) {
                    return want(f.current(), Current.LIBRARY, Decision.LIBRARY);
                }
                return visitOrHome(f, night);
            }
            case VISIT -> { return visitOrHome(f, night); }
            default -> { }
        }
        return want(f.current(), Current.HOME, Decision.HOME);
    }

    /**
     * A stay is over when it is used up; at the night's last venue only once
     * the leaving time has come as well (the later of the two).
     */
    private static boolean stayOver(Facts f) {
        if (!f.stayComplete()) return false;
        return !f.lastStep() || f.phase() == EveningSchedule.Phase.NIGHT;
    }

    private static Decision visitOrHome(Facts f, boolean night) {
        if (f.visitActive()) return want(f.current(), Current.VISIT, Decision.VISIT);
        if (f.visitWanted() && !night) return Decision.VISIT;
        return want(f.current(), Current.HOME, Decision.HOME);
    }

    /** After its leaving time, a guest still at (or on the way to) its venue stays until its stay is used up. */
    private static boolean stillStaying(Facts f) {
        if (f.visitActive()) return true;
        return switch (f.step()) {
            case TAVERN  -> f.tavernAvailable() && !f.stayComplete();
            case LIBRARY -> f.libraryAvailable() && !f.stayComplete();
            // The bard's phase is kept at GATHER by the brain while guests remain.
            default -> false;
        };
    }

    private static boolean isSettled(Current c) {
        return c == Current.HOME || c == Current.TAVERN || c == Current.VISIT;
    }

    private static Decision want(Current current, Current matching, Decision decision) {
        return current == matching ? Decision.KEEP : decision;
    }

    // =========================================================================
    // Tonight's plan
    // =========================================================================

    /** Most weight moved between HOME and going out by sociability. */
    public static final double MAX_SOCIAL_SHIFT = 10.0;
    /** Chance that a HOME night starts a homebody streak. */
    public static final int HOMEBODY_CHANCE = 25;
    public static final int HOMEBODY_MAX_NIGHTS = 3;
    /** A favourite's weight is multiplied by this. */
    public static final double FAVOURITE_FACTOR = 2.0;
    /** Weight a good (or bad) night adds to (or takes from) the same plan the next night. */
    public static final int HABIT_WEIGHT = 5;

    /** Index of each plan in a weights array. */
    public static final int W_HOME = 0, W_TAVERN = 1, W_LIBRARY = 2, W_VISIT = 3;

    /**
     * Weights for HOME, TAVERN, LIBRARY, VISIT, by what the village has.
     * A second venue takes its share from home and visits, not from the
     * tavern, so a village with both is never quieter at the tavern.
     */
    public static double[] weights(boolean villageTavern, boolean villageLibrary) {
        if (villageTavern && villageLibrary) return new double[] {10, 60, 20, 10};
        if (villageTavern)                   return new double[] {10, 60, 0, 30};
        if (villageLibrary)                  return new double[] {20, 0, 50, 30};
        return new double[] {35, 0, 0, 65};
    }

    /**
     * Weights after personality: {@code sociability} in [0, 1] moves up to
     * {@value #MAX_SOCIAL_SHIFT} points between HOME and going out, spread
     * over the going-out options in proportion to their weight. A quiet
     * villager still goes out sometimes.
     */
    public static double[] socialWeights(double[] base, double sociability) {
        double shift = (Math.max(0.0, Math.min(1.0, sociability)) - 0.5) * 2.0 * MAX_SOCIAL_SHIFT;
        double out = base[1] + base[2] + base[3];
        double[] w = base.clone();
        if (out <= 0) return w;
        shift = Math.max(-(out - 1.0), Math.min(base[0] - 1.0, shift));
        w[0] = base[0] - shift;
        for (int i = 1; i < 4; i++) w[i] = base[i] + shift * base[i] / out;
        return w;
    }

    /** The favourite's weight doubled; a bookworm only counts where there is a library. */
    public static double[] favouriteWeights(double[] w, Favourite favourite) {
        double[] out = w.clone();
        int i = switch (favourite) {
            case TAVERN_REGULAR -> W_TAVERN;
            case BOOKWORM       -> W_LIBRARY;
            case VISITOR        -> W_VISIT;
            case HOMEBODY       -> W_HOME;
        };
        if (out[i] > 0) out[i] *= FAVOURITE_FACTOR;
        return out;
    }

    /** Adds last night's habit to its plan's weight, never below zero, never adding a venue the village lacks. */
    public static double[] habitWeights(double[] w, Plan plan, int delta) {
        double[] out = w.clone();
        int i = switch (plan) {
            case TAVERN  -> W_TAVERN;
            case LIBRARY -> W_LIBRARY;
            case VISIT   -> W_VISIT;
            case HOME, EARLY -> W_HOME;
            default -> -1;
        };
        if (i >= 0 && out[i] > 0) out[i] = Math.max(0.0, out[i] + delta);
        return out;
    }

    /** Tonight's weekly rhythm: a tavern night livens the tavern; a quiet night favours home and books. */
    public static double[] nightWeights(double[] w, NightType night) {
        double[] out = w.clone();
        switch (night) {
            case TAVERN_NIGHT -> out[W_TAVERN] *= 1.5;
            case QUIET_NIGHT -> {
                out[W_HOME] *= 1.5;
                out[W_LIBRARY] *= 1.5;
                out[W_TAVERN] *= 0.75;
            }
            default -> { }
        }
        return out;
    }

    /**
     * Tonight's plan from the weights. {@code roll} and {@code reroll} are
     * independent uniform picks in [0, 1). If the first pick lands on a venue
     * this villager cannot use tonight (none it can reach), the second picks
     * again among what it can.
     */
    public static Plan rollPlan(double[] weights, boolean tavernReachable, boolean libraryReachable,
                                double roll, double reroll) {
        Plan first = pick(weights, roll);
        if (usable(first, tavernReachable, libraryReachable)) return first;
        double[] w = weights.clone();
        if (!tavernReachable) w[W_TAVERN] = 0;
        if (!libraryReachable) w[W_LIBRARY] = 0;
        return pick(w, reroll);
    }

    /** Nights a HOME roll keeps the villager home after tonight: 0, or 1-3 with a 25% chance. */
    public static int homebodyNights(int roll100, int nightsRoll) {
        if (roll100 >= HOMEBODY_CHANCE) return 0;
        return 1 + Math.floorMod(nightsRoll, HOMEBODY_MAX_NIGHTS);
    }

    /**
     * A villager's lasting favourite from a 0-999 roll: an even start, then
     * intelligence (0-1) towards the library, sociability (0-1) towards the
     * tavern and visits (and away from home), and its job's lean.
     */
    public static Favourite favourite(int roll1000, double intelligence, double sociability, JobLean lean) {
        double soc = Math.max(0, Math.min(1, sociability)), iq = Math.max(0, Math.min(1, intelligence));
        double[] w = {
                25 + 20 * soc + (lean == JobLean.TAVERN ? 25 : 0),
                25 + 40 * iq + (lean == JobLean.LIBRARY ? 25 : 0),
                25 + 20 * soc,
                25 + 20 * (1 - soc)};
        double total = w[0] + w[1] + w[2] + w[3];
        double at = Math.floorMod(roll1000, 1000) / 1000.0 * total;
        Favourite[] order = Favourite.values();
        for (int i = 0; i < 4; i++) {
            at -= w[i];
            if (at < 0) return order[i];
        }
        return Favourite.HOMEBODY;
    }

    private static final long SALT_WEEK_TAVERN = 0x3EE4_0001L;
    private static final long SALT_WEEK_QUIET  = 0x3EE4_0002L;

    /** The village's night of the week: one tavern night and one quiet night in every seven, rolled per village per week. */
    public static NightType nightType(UUID villageId, long day) {
        long week = Math.floorDiv(day, 7L);
        int dayOfWeek = (int) Math.floorMod(day, 7L);
        int tavernDay = EveningSchedule.sharedRoll(villageId, week, SALT_WEEK_TAVERN, 7);
        int quietDay = (tavernDay + 1 + EveningSchedule.sharedRoll(villageId, week, SALT_WEEK_QUIET, 6)) % 7;
        if (dayOfWeek == tavernDay) return NightType.TAVERN_NIGHT;
        if (dayOfWeek == quietDay) return NightType.QUIET_NIGHT;
        return NightType.NORMAL;
    }

    private static boolean usable(Plan plan, boolean tavern, boolean library) {
        return switch (plan) {
            case TAVERN  -> tavern;
            case LIBRARY -> library;
            default -> true;
        };
    }

    private static Plan pick(double[] w, double roll) {
        double total = 0;
        for (double x : w) total += Math.max(0, x);
        if (total <= 0) return Plan.HOME;
        double at = Math.max(0.0, Math.min(roll, 0.999_999)) * total;
        Plan[] order = {Plan.HOME, Plan.TAVERN, Plan.LIBRARY, Plan.VISIT};
        for (int i = 0; i < 4; i++) {
            at -= Math.max(0, w[i]);
            if (at < 0) return order[i];
        }
        return Plan.VISIT;
    }

    // =========================================================================
    // Tonight's order of outings
    // =========================================================================

    /**
     * The order of the evening from two 0-99 rolls. Tavern-goers: chores
     * first 40%, the tavern first 30% (then a visit or chores, half each),
     * a visit first 30%. Library-goers: chores first 40%. Everyone else:
     * just the plan.
     */
    public static Sequence rollSequence(Plan plan, int roll100, int afterRoll100) {
        if (plan == Plan.TAVERN) {
            if (roll100 < 40) return Sequence.CHORES_THEN_TAVERN;
            if (roll100 < 70) return afterRoll100 < 50 ? Sequence.TAVERN_THEN_VISIT : Sequence.TAVERN_THEN_CHORES;
            return Sequence.VISIT_THEN_TAVERN;
        }
        if (plan == Plan.LIBRARY && roll100 < 40) return Sequence.CHORES_THEN_LIBRARY;
        return Sequence.SINGLE;
    }

    /** The steps of the evening, in order; after the last, the villager is home. */
    public static Step[] steps(Plan plan, Sequence sequence) {
        return switch (plan) {
            case EARLY, HOME -> new Step[] {Step.HOME};
            case BARD -> new Step[] {Step.BARD};
            case VISIT -> new Step[] {Step.VISIT};
            case LIBRARY -> sequence == Sequence.CHORES_THEN_LIBRARY
                    ? new Step[] {Step.CHORES, Step.LIBRARY} : new Step[] {Step.LIBRARY};
            case TAVERN -> switch (sequence) {
                case CHORES_THEN_TAVERN -> new Step[] {Step.CHORES, Step.TAVERN};
                case TAVERN_THEN_VISIT  -> new Step[] {Step.TAVERN, Step.VISIT};
                case TAVERN_THEN_CHORES -> new Step[] {Step.TAVERN, Step.CHORES};
                case VISIT_THEN_TAVERN  -> new Step[] {Step.VISIT, Step.TAVERN};
                default -> new Step[] {Step.TAVERN};
            };
        };
    }

    /** The step at {@code stage}, or HOME once the sequence is done. */
    public static Step stepAt(Plan plan, Sequence sequence, int stage) {
        Step[] s = steps(plan, sequence);
        return stage < s.length ? s[stage] : Step.HOME;
    }

    /**
     * Whether the step at {@code stage} is the night's last: its venue keeps
     * the guest until the later of its leaving time and the end of its stay.
     * An earlier step ends as soon as its stay is used up.
     */
    public static boolean isLastStep(Plan plan, Sequence sequence, int stage) {
        return stage >= steps(plan, sequence).length - 1;
    }

    /** Whether a step goes out of the house (and so waits for the head-out time). */
    public static boolean isOuting(Step step) {
        return step == Step.TAVERN || step == Step.LIBRARY || step == Step.VISIT;
    }

    // =========================================================================
    // Who gets in
    // =========================================================================

    /**
     * Admits candidates, in rank order, whose presence {@code [start, end)}
     * never takes the number of admitted guests there at the same time over
     * {@code capacity}. Someone who arrives after an earlier guest leaves can
     * take that guest's place, so a tavern with turnover sees more of the
     * village in one night without ever being over full.
     */
    public static boolean[] admit(long[] starts, long[] ends, int capacity) {
        int n = starts.length;
        boolean[] in = new boolean[n];
        if (capacity <= 0) return in;
        for (int i = 0; i < n; i++) {
            if (ends[i] <= starts[i]) continue;
            boolean fits = true;
            // The busiest moment inside this interval is at its own start or
            // at the start of an admitted guest who arrives during it.
            for (int p = -1; p < i && fits; p++) {
                long at = p < 0 ? starts[i] : starts[p];
                if (p >= 0 && (!in[p] || at < starts[i] || at >= ends[i])) continue;
                int present = 0;
                for (int j = 0; j < i; j++) {
                    if (in[j] && starts[j] <= at && at < ends[j]) present++;
                }
                if (present + 1 > capacity) fits = false;
            }
            in[i] = fits;
        }
        return in;
    }
}
