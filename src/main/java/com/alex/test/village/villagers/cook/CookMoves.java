package com.alex.test.village.villagers.cook;

import com.alex.test.animation.VillagerAnimationState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Which of the cook's moves play in one batch, and when (round 2f of
 * docs/plans/2026-10-24-cook-animations.md). Only the looks: the batch takes
 * exactly as long as it always did, and the plan is laid inside it.
 *
 * <p>Tick 0 is the batch start. The moves before the wait run back to back
 * from 0; the take (and the present after it) are pinned to the end, so the
 * dish comes out on the batch's last tick. The waits fill the time between,
 * whole waits only, slowed a little to fit; less than one wait and the cook
 * just stands in the ready pose. Short of time, the extras go first: the
 * opener, then the present, then the waits; then the rest plays faster, up to
 * {@link #MAX_SQUEEZE}; then the load goes; then only the take is left. At the
 * table the present is kept whenever it fits at all, and the dish loop is never
 * played faster than {@link #MAX_SQUEEZE}.
 *
 * <p>No Minecraft classes, so the JUnit tests can reach it.
 */
public final class CookMoves {
    public enum Station { TABLE, FURNACE, SMOKER, CAMPFIRE }

    /** A move before the batch proper: none, sleeves up (the shift's first batch), or the chef's kiss (after a bonus). */
    public enum Opener { NONE, SLEEVES, KISS }

    /** One move: its clip, the tick of the batch it starts on, and how fast it plays. */
    public record Piece(VillagerAnimationState clip, int start, float speed) {}

    /** The fastest a move is ever played to fit a batch, short of the last resort (only the take, up to 3x). */
    public static final float MAX_SQUEEZE = 1.3f;

    /** The opener before the table move is kept only if the table move still gets this much clip time after it. */
    private static final int MIN_TABLE_ROOM = 20;

    /** The table move before the present is kept only with at least this many ticks; the present itself always stays if it fits. */
    private static final int MIN_DISH_BEFORE_PRESENT = 12;

    /** The take on its own may go this fast in a batch too short for anything else. */
    private static final float MAX_FINAL_SPEED = 3f;

    /** The cook's dishes that end their last batch held up for show. */
    private static final Set<String> SHOWPIECES = Set.of(
            "cake", "golden_apple", "golden_carrot", "pumpkin_pie", "rabbit_stew");

    /** The waits at a furnace or smoker; the last one only at a smoker. */
    private static final List<VillagerAnimationState> HEAT_WAITS = List.of(
            VillagerAnimationState.COOK_WAIT_PEEK, VillagerAnimationState.COOK_WAIT_HIPS,
            VillagerAnimationState.COOK_WAIT_SPOON, VillagerAnimationState.COOK_WAIT_TIDY,
            VillagerAnimationState.COOK_WAIT_SMOKE);

    /** Loops of cook_camp_wait between two turns of the food. */
    private static final int CAMP_WAIT_LOOPS = 2;

    private CookMoves() {}

    /** A move as it is planned: clip, speed and the ticks it is given. */
    private record Step(VillagerAnimationState clip, float speed, int ticks) {}

    /** The table move for a dish, by the result's registry path; anything else keeps the shared craft_table. */
    public static VillagerAnimationState tableClip(String resultPath) {
        if (resultPath == null) return VillagerAnimationState.CRAFT_TABLE;
        return switch (resultPath) {
            case "bread" -> VillagerAnimationState.COOK_KNEAD;
            case "cookie" -> VillagerAnimationState.COOK_COOKIES;
            case "cake" -> VillagerAnimationState.COOK_WHISK;
            case "pumpkin_pie" -> VillagerAnimationState.COOK_PIE;
            case "mushroom_stew", "beetroot_soup" -> VillagerAnimationState.COOK_CHOP_STIR;
            case "golden_carrot", "golden_apple" -> VillagerAnimationState.COOK_GILD;
            case "bowl" -> VillagerAnimationState.COOK_WHITTLE;
            case "sugar" -> VillagerAnimationState.COOK_PRESS_CANE;
            default -> resultPath.endsWith("_planks")
                    ? VillagerAnimationState.COOK_WHITTLE : VillagerAnimationState.CRAFT_TABLE;
        };
    }

    /** Cake, golden apple, golden carrot, pumpkin pie, rabbit stew: the last batch ends with the dish held up. */
    public static boolean isShowpiece(String resultPath) {
        return resultPath != null && SHOWPIECES.contains(resultPath);
    }

    /**
     * Ticks one play of {@code clip} takes at {@code speed}, its blend in included:
     * ceil((WORK_BLEND_TICKS + cycleTicks) / speed). Rounded up from a hair under,
     * so float noise (56 / 1.4f is 40.0000007) does not add a tick.
     */
    public static int pieceTicks(VillagerAnimationState clip, float speed) {
        float ticks = VillagerAnimationState.WORK_BLEND_TICKS + VillagerAnimationState.cycleTicks(clip);
        return (int) Math.ceil(ticks / speed - 1e-3);
    }

    /**
     * The moves for one batch of {@code batchTicks}, in time order, the first at tick 0 (except a
     * table batch too short for any dish move before the present: then the present alone, ending
     * on the last tick).
     *
     * @param tableClip the dish's table move ({@link #tableClip}); only used at the TABLE
     * @param light the station was cold and a fuel goes in (furnace and smoker)
     * @param feed a fuel goes into a lit station; ignored when {@code light}
     * @param present the dish is held up at the end (last batch of a showpiece)
     */
    public static List<Piece> plan(Station station, VillagerAnimationState tableClip, int batchTicks, boolean light,
                                   boolean feed, Opener opener, boolean present, RandomGenerator random) {
        int total = Math.max(1, batchTicks);
        VillagerAnimationState openerClip = switch (opener == null ? Opener.NONE : opener) {
            case NONE -> null;
            case SLEEVES -> VillagerAnimationState.COOK_SLEEVES;
            case KISS -> VillagerAnimationState.COOK_KISS;
        };
        if (station == Station.TABLE) {
            return planTable(tableClip == null ? VillagerAnimationState.CRAFT_TABLE : tableClip,
                    total, openerClip, present);
        }
        return planHeat(station, total, light, feed, openerClip, present, random);
    }

    /**
     * Opener, the dish's move looped to the end (or up to the present), present. The present
     * (a master chef showing off) stays whenever it fits the batch at all; the opener goes
     * first, then the dish move before the present if it would be under
     * {@link #MIN_DISH_BEFORE_PRESENT} ticks (the present then starts late, nothing before it).
     */
    private static List<Piece> planTable(VillagerAnimationState dish, int total,
                                         VillagerAnimationState openerClip, boolean present) {
        int presentTicks = pieceTicks(VillagerAnimationState.COOK_PRESENT, 1f);
        if (!present || total < presentTicks) {
            present = false;
            presentTicks = 0;
        }
        int openerTicks = openerClip == null ? 0 : pieceTicks(openerClip, 1f);
        if (openerClip != null
                && total - openerTicks - presentTicks - VillagerAnimationState.WORK_BLEND_TICKS < MIN_TABLE_ROOM) {
            openerClip = null;
            openerTicks = 0;
        }
        List<Step> head = new ArrayList<>();
        if (openerClip != null) head.add(new Step(openerClip, 1f, openerTicks));
        int dishTicks = total - openerTicks - presentTicks;
        List<Step> middle = present && dishTicks < MIN_DISH_BEFORE_PRESENT ? List.of()
                : List.of(new Step(dish, loopSpeed(dish, dishTicks), dishTicks));
        List<Step> tail = present
                ? List.of(new Step(VillagerAnimationState.COOK_PRESENT, 1f, presentTicks)) : List.of();
        return layout(head, middle, tail, total);
    }

    /**
     * Furnace or smoker: opener, light or feed, load, waits, take, present.
     * Campfire: opener, lay, (camp_wait, camp_turn) repeated, camp_take, present.
     */
    private static List<Piece> planHeat(Station station, int total, boolean light, boolean feed,
                                        VillagerAnimationState openerClip, boolean present, RandomGenerator random) {
        boolean camp = station == Station.CAMPFIRE;
        VillagerAnimationState fire = camp ? null
                : light ? VillagerAnimationState.COOK_FIRE_LIGHT
                : feed ? VillagerAnimationState.COOK_FIRE_FEED : null;
        VillagerAnimationState put = camp ? VillagerAnimationState.COOK_CAMP_LAY : VillagerAnimationState.COOK_LOAD;
        VillagerAnimationState take = camp ? VillagerAnimationState.COOK_CAMP_TAKE : VillagerAnimationState.COOK_TAKE;
        VillagerAnimationState show = present ? VillagerAnimationState.COOK_PRESENT : null;

        if (onceTicks(openerClip, fire, put, take, show) > total) openerClip = null;
        if (onceTicks(openerClip, fire, put, take, show) > total) show = null;
        int need = onceTicks(openerClip, fire, put, take, show);
        if (need <= total) {
            List<Step> middle = camp ? campWaits(total - need) : heatWaits(station, total - need, random);
            return layout(steps(1f, openerClip, fire, put), middle, steps(1f, take, show), total);
        }
        // No time for any wait: the rest plays faster to fit.
        float squeeze = need / (float) total;
        if (squeeze <= MAX_SQUEEZE) {
            return layout(steps(squeeze, fire, put), List.of(), steps(squeeze, take), total);
        }
        // Still too fast: the load (or the lay) goes.
        if (fire != null) {
            need = onceTicks(fire, take);
            squeeze = Math.max(1f, need / (float) total);
            if (squeeze <= MAX_SQUEEZE) {
                return layout(steps(squeeze, fire), List.of(), steps(squeeze, take), total);
            }
        }
        // Last resort: only the take, at whatever speed fills the batch (slower or faster, up to 3x).
        float speed = Math.clamp(pieceTicks(take, 1f) / (float) total, 0.25f, MAX_FINAL_SPEED);
        return List.of(new Piece(take, Math.max(0, total - pieceTicks(take, speed)), speed));
    }

    /**
     * Whole furnace or smoker waits filling {@code gap}: n waits played at n x W / gap (a wait
     * is W ticks at full speed), never the same one twice in a row, the smoke wave only at a
     * smoker. Less than one wait: none, and the cook holds the ready pose.
     */
    private static List<Step> heatWaits(Station station, int gap, RandomGenerator random) {
        int wait = pieceTicks(VillagerAnimationState.COOK_WAIT_PEEK, 1f);
        int count = gap / wait;
        if (count == 0) return List.of();
        int kinds = station == Station.SMOKER ? HEAT_WAITS.size() : HEAT_WAITS.size() - 1;
        float speed = count * wait / (float) gap;
        List<Step> waits = new ArrayList<>(count);
        int last = -1;
        for (int i = 0; i < count; i++) {
            int pick = random.nextInt(last < 0 ? kinds : kinds - 1);
            if (last >= 0 && pick >= last) pick++;
            waits.add(new Step(HEAT_WAITS.get(pick), speed, share(gap, count, i)));
            last = pick;
        }
        return waits;
    }

    /**
     * Campfire time between the lay and the take: as many (two loops of camp_wait, one
     * camp_turn) as fit whole, the waits slowed to fill the rest. Not enough for one turn:
     * a single camp_wait if one loop fits, or the ready pose.
     */
    private static List<Step> campWaits(int gap) {
        VillagerAnimationState wait = VillagerAnimationState.COOK_CAMP_WAIT;
        VillagerAnimationState turn = VillagerAnimationState.COOK_CAMP_TURN;
        int cycle = VillagerAnimationState.cycleTicks(wait);
        int turnTicks = pieceTicks(turn, 1f);
        int unit = VillagerAnimationState.WORK_BLEND_TICKS + CAMP_WAIT_LOOPS * cycle + turnTicks;
        int turns = gap / unit;
        if (turns == 0) {
            if (gap < VillagerAnimationState.WORK_BLEND_TICKS + cycle) return List.of();
            return List.of(new Step(wait, loopSpeed(wait, gap), gap));
        }
        int waitTicks = gap - turns * turnTicks;
        List<Step> steps = new ArrayList<>(2 * turns);
        for (int i = 0; i < turns; i++) {
            int ticks = share(waitTicks, turns, i);
            float speed = CAMP_WAIT_LOOPS * cycle / (float) (ticks - VillagerAnimationState.WORK_BLEND_TICKS);
            steps.add(new Step(wait, speed, ticks));
            steps.add(new Step(turn, 1f, turnTicks));
        }
        return steps;
    }

    /**
     * Speed for a looping move given {@code ticks}: the time after the blend in, rounded to
     * whole loops, the move slowed or sped to end on one. Under half a loop: full speed, and
     * the next move (or the batch end) cuts it. Never faster than {@link #MAX_SQUEEZE}: one
     * loop fewer instead (so never a frantic 2x knead).
     */
    private static float loopSpeed(VillagerAnimationState clip, int ticks) {
        int room = ticks - VillagerAnimationState.WORK_BLEND_TICKS;
        if (room <= 0) return 1f;
        int cycle = VillagerAnimationState.cycleTicks(clip);
        int loops = Math.round(room / (float) cycle);
        if (loops >= 1 && loops * cycle / (float) room > MAX_SQUEEZE) loops--;
        return loops == 0 ? 1f : loops * cycle / (float) room;
    }

    /** Part {@code i} of {@code ticks} cut into {@code parts} whole-tick shares that add up to it. */
    private static int share(int ticks, int parts, int i) {
        return ticks / parts + (i < ticks % parts ? 1 : 0);
    }

    /** Ticks the given once moves take back to back at full speed (nulls skipped). */
    private static int onceTicks(VillagerAnimationState... clips) {
        int sum = 0;
        for (VillagerAnimationState clip : clips) {
            if (clip != null) sum += pieceTicks(clip, 1f);
        }
        return sum;
    }

    /** The given once moves (nulls skipped) at {@code speed}. */
    private static List<Step> steps(float speed, VillagerAnimationState... clips) {
        List<Step> steps = new ArrayList<>(clips.length);
        for (VillagerAnimationState clip : clips) {
            if (clip != null) steps.add(new Step(clip, speed, pieceTicks(clip, speed)));
        }
        return steps;
    }

    /**
     * Head and middle back to back from tick 0; the tail pinned so its last move ends on
     * {@code total}. A gap between them is the ready pose held. If rounding ran the head
     * a tick or two past the tail's start, the tail's start wins (a head move that would
     * only begin there is left out).
     */
    private static List<Piece> layout(List<Step> head, List<Step> middle, List<Step> tail, int total) {
        int tailTicks = 0;
        for (Step step : tail) tailTicks += step.ticks();
        int tailStart = tail.isEmpty() ? total : Math.max(0, total - tailTicks);
        List<Piece> pieces = new ArrayList<>();
        int at = 0;
        for (List<Step> part : List.of(head, middle)) {
            for (Step step : part) {
                if (at >= tailStart) break;
                pieces.add(new Piece(step.clip(), at, step.speed()));
                at += step.ticks();
            }
        }
        at = tailStart;
        for (Step step : tail) {
            pieces.add(new Piece(step.clip(), at, step.speed()));
            at += step.ticks();
        }
        return pieces;
    }
}
