package com.alex.test.village.brain;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.stats.CrowdingBalance;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Spreads villagers hiding from monsters over the buildings around them, so
 * they do not all pile into the one nearest small house.
 *
 * <p>When choosing where to hide, a building counts as
 * {@link #PER_EXTRA_VILLAGER} blocks farther off for every villager past what
 * it holds (CrowdingBalance's fits), counting those already inside and those
 * hiding in it or on their way there. A roomy hall a little farther away
 * then beats a crammed hut next door.
 */
public final class ShelterCrowd {

    /** Blocks added to a building's distance per villager past what it holds. */
    public static final double PER_EXTRA_VILLAGER = 3.0;

    private ShelterCrowd() {}

    /** How many villagers besides {@code self} are hiding in, or heading to, each building. */
    public static Map<Building, Integer> sheltering(VillageManager mgr, @Nullable VillagerModel self) {
        Map<Building, Integer> counts = new IdentityHashMap<>();
        for (VillagerModel other : mgr.getVillagers()) {
            if (other == self) continue;
            Activity activity = other.brain().currentActivity();
            Building target = activity instanceof FleeActivity flee ? flee.shelter()
                    : activity instanceof ShelterActivity shelter ? shelter.home()
                    : null;
            if (target != null) counts.merge(target, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Blocks to add to {@code building}'s distance with one more villager in
     * it: the bodies counted inside (hiders are not, see VillageCrowding),
     * plus those hiding or heading there, plus this one, past what it holds.
     */
    public static double penalty(VillageManager mgr, Building building, Map<Building, Integer> sheltering) {
        if (!building.countsCrowding()) return 0.0;
        int there = mgr.crowding().bodiesIn(building) + sheltering.getOrDefault(building, 0) + 1;
        int extra = there - CrowdingBalance.fits(building.getSpace().space());
        return extra > 0 ? extra * PER_EXTRA_VILLAGER : 0.0;
    }
}
