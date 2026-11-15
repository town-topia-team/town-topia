package com.alex.test.village.economy;

import java.util.List;
import java.util.Map;

/**
 * Result of one away catch-up ({@code economy/away/AwayRun}), retained by
 * {@code VillageManager} and surfaced through {@code StateDump} so a test can
 * assert the offline-economy math actually ran instead of only observing its
 * downstream effects.
 *
 * @param ticksReconciled       ticks the village lived through: the time away, less the
 *                              server's empty time, at most {@link AwayClock#MAX_AWAY_DAYS} days
 * @param villagersProcessed    logical members that had a live puppet to project onto
 * @param hungerRestored        total hunger points restored across every simulated meal
 * @param mealsEaten            simulated meals eaten across all villagers
 * @param starvationDamageEvents starvation-damage events applied (1 HP each, difficulty-floored)
 * @param itemsProduced         item registry id -> count actually deposited into storage
 * @param itemsConsumed         item registry id -> count actually withdrawn from storage
 * @param birthsSpawned         retained for the state-dump schema; zero because births require live sleep
 * @param truncationNotes       human-readable notes on any bounded loop that hit its cap
 * @param ticksAway             game ticks nobody was near, the server's empty time included
 * @param hungryVillagers       villagers who ran out of food while away
 */
public record ReconciliationSummary(
        long ticksReconciled,
        int villagersProcessed,
        float hungerRestored,
        int mealsEaten,
        int starvationDamageEvents,
        Map<String, Integer> itemsProduced,
        Map<String, Integer> itemsConsumed,
        int birthsSpawned,
        List<String> truncationNotes,
        long ticksAway,
        int hungryVillagers) {

    public static ReconciliationSummary empty() {
        return new ReconciliationSummary(0L, 0, 0f, 0, 0, Map.of(), Map.of(), 0, List.of(), 0L, 0);
    }

    /**
     * Placeholder summary recorded when the catch-up threw instead of
     * completing. Keeps {@code VillageManager.lastReconciliation}
     * populated with a recognizable marker rather than left stale or null, so
     * {@code StateDump}/logs can surface that the catch-up for this village
     * did not actually apply (or only in part).
     */
    public static ReconciliationSummary failed(long ticksReconciled, long ticksAway, String reason) {
        return new ReconciliationSummary(
                ticksReconciled, 0, 0f, 0, 0, Map.of(), Map.of(), 0,
                List.of("RECONCILIATION FAILED: " + reason), ticksAway, 0);
    }
}
