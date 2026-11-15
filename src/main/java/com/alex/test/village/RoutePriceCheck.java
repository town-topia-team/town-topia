package com.alex.test.village;

import com.alex.test.village.follow.StepKind;
import com.alex.test.village.nav.StepPrice;

import java.util.ArrayList;
import java.util.List;

/**
 * Climb cost 2D (10-18): the {@code [RoutePrice]} minute line. The route planner prices each
 * level-ground step from the map's heights and the stairs' low sides ({@link StepPrice#call});
 * the walker then judges the same step from the real block shapes ({@code StepRules}). Where they
 * disagree, the price was wrong: a "stair front" the body has to jump was priced too cheap, a
 * "block" it walks up was priced too dear. Counted as routes are laid out for walking, server
 * thread only, with up to three spots named so they can be looked at with {@code /vtest node}.
 * Ladders, water and steps the walker refuses are not counted. Since 10-23 (raider plan item 20)
 * raiders' steps are only a count on their own line, {@code [RoutePrice] raiders}.
 */
public final class RoutePriceCheck {

    private static final int EXAMPLES = 3;

    private static long checked;
    private static long climbsPriced, stairStepsPriced, dropsPriced;
    private static long walkedButJumped, climbButWalked, walkDownButDropped, dropButWalked, other;
    private static final List<String> examples = new ArrayList<>(EXAMPLES);
    /** Raiders' ground steps since the last line, and how many the walker judged other than priced. */
    private static long raiderChecked, raiderWrong;

    private RoutePriceCheck() {}

    /** The planner's reading of the step from {@code a} to the side-by-side {@code b}. */
    public static StepPrice.Call call(NavNode a, NavNode b) {
        int dx = b.x - a.x, dz = b.z - a.z;
        return StepPrice.call(b.standY - a.standY, b.halfStepToward(-dx, -dz), a.halfStepToward(dx, dz));
    }

    /** One ground step of a route about to be walked, with the walker's verdict on it; {@code raider} for a raid body. */
    static void note(NavNode a, NavNode b, StepKind walker, boolean raider) {
        if (walker != StepKind.WALK && walker != StepKind.JUMP && walker != StepKind.DROP) return;
        StepPrice.Call planned = call(a, b);
        if (raider) {
            raiderChecked++;
            if (walker != expected(planned)) raiderWrong++;
            return;
        }
        checked++;
        switch (planned) {
            case CLIMB -> climbsPriced++;
            case WALK_UP, WALK_DOWN -> stairStepsPriced++;
            case DROP -> dropsPriced++;
            default -> { }
        }
        StepKind expected = expected(planned);
        if (walker == expected) return;
        if (walker == StepKind.JUMP && expected == StepKind.WALK) walkedButJumped++;
        else if (planned == StepPrice.Call.CLIMB && walker == StepKind.WALK) climbButWalked++;
        else if (planned == StepPrice.Call.WALK_DOWN && walker == StepKind.DROP) walkDownButDropped++;
        else if (planned == StepPrice.Call.DROP && walker == StepKind.WALK) dropButWalked++;
        else other++;
        if (examples.size() < EXAMPLES) {
            examples.add(String.format(java.util.Locale.ROOT, "%d %d %d -> %d %d %d (rise %.2f) priced %s, walker %s",
                    a.x, a.feetY, a.z, b.x, b.feetY, b.z, b.standY - a.standY,
                    planned.name().toLowerCase(java.util.Locale.ROOT), walker.name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    /** The walker's step a correct price means: a climb is jumped, a drop dropped, the rest walked. */
    private static StepKind expected(StepPrice.Call planned) {
        return switch (planned) {
            case CLIMB -> StepKind.JUMP;
            case DROP -> StepKind.DROP;
            default -> StepKind.WALK;
        };
    }

    /** Logs the lines and starts a new count. Server thread, once a minute, with the {@code [Follow]} line. */
    static void flush() {
        if (raiderChecked > 0) {
            VillageManager.LOGGER.info("[RoutePrice] raiders last 60 s: {} ground steps on routes laid out, {} priced wrong",
                    raiderChecked, raiderWrong);
            raiderChecked = raiderWrong = 0;
        }
        if (checked == 0) return;
        long wrong = walkedButJumped + climbButWalked + walkDownButDropped + dropButWalked + other;
        VillageManager.LOGGER.info("[RoutePrice] last 60 s: {} ground steps on routes laid out ({} priced as climbs,"
                        + " {} as stair steps, {} as drops), {} priced wrong: walk but jumped {}, climb but walked {},"
                        + " stair down but dropped {}, drop but walked {}, other {}; climbcost {}, jumpcost {}, dropcost {}{}",
                checked, climbsPriced, stairStepsPriced, dropsPriced, wrong, walkedButJumped, climbButWalked,
                walkDownButDropped, dropButWalked, other, WalkFeel.climbCost ? "on" : "off",
                StepPrice.climbExtra, StepPrice.dropExtraPerBlock,
                examples.isEmpty() ? "" : "; e.g. " + String.join("; ", examples));
        checked = climbsPriced = stairStepsPriced = dropsPriced = 0;
        walkedButJumped = climbButWalked = walkDownButDropped = dropButWalked = other = 0;
        examples.clear();
    }
}
