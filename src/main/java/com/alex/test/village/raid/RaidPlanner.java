package com.alex.test.village.raid;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Splits a warband's budget into waves of raiders. One wave under 10 points,
 * two up to 25, three above; later waves are bigger. Ravagers and evokers only
 * come in the last wave (tier 2 and tier 3), witches from tier 2.
 *
 * <p>No Minecraft types: unit-tested on a plain JVM.
 */
public final class RaidPlanner {

    private static final double[] ONE_WAVE = {1.0};
    private static final double[] TWO_WAVES = {0.45, 0.55};
    private static final double[] THREE_WAVES = {0.27, 0.33, 0.40};
    /** Chance a raider bought with 3 or more points left is a vindicator rather than a pillager. */
    static final double VINDICATOR_CHANCE = 0.4;

    /** The horde gets this many times the budget: the dead are weak, and burn at sunrise. */
    public static final double HORDE_SCALE = 1.5;
    /** Never more of the dead than this in one night. */
    public static final int MAX_HORDE = 40;

    private RaidPlanner() {}

    /**
     * The kidnappers' team, one wave: two vindicators (the first is the
     * snatcher) and a pillager to cover them; one more pillager at tier 2, an
     * evoker whose vexes keep the guards busy at tier 3.
     */
    public static List<List<RaiderKind>> planKidnappers(int tier) {
        List<RaiderKind> team = new ArrayList<>(List.of(RaiderKind.VINDICATOR, RaiderKind.VINDICATOR, RaiderKind.PILLAGER));
        if (tier >= 2) team.add(RaiderKind.PILLAGER);
        if (tier >= 3) team.add(RaiderKind.EVOKER);
        List<List<RaiderKind>> plan = new ArrayList<>();
        plan.add(team);
        return plan;
    }

    /**
     * Skeleton riders: one group under 20 points, two above. Mostly riders
     * (strays in the snow, bogged in swamps); from tier 2 a third of a group's
     * points go on skeletons on foot. Every group has at least one rider.
     */
    public static List<List<RaiderKind>> planRiders(int budget, int tier, int maxAlive, RandomGenerator rng,
                                                    boolean snowy, boolean swamp) {
        RaiderKind rider = snowy ? RaiderKind.STRAY_RIDER : swamp ? RaiderKind.BOGGED_RIDER : RaiderKind.SKELETON_RIDER;
        int groups = budget >= 20 ? 2 : 1;
        int cap = Math.max(1, maxAlive);
        List<List<RaiderKind>> plan = new ArrayList<>();
        int spent = 0;
        for (int i = 0; i < groups; i++) {
            int groupBudget = i == groups - 1 ? budget - spent : budget / groups;
            spent += groupBudget;
            int left = groupBudget;
            int onFoot = tier >= 2 ? groupBudget / 3 : 0;
            List<RaiderKind> group = new ArrayList<>();
            if (left >= rider.cost()) {
                group.add(rider);
                left -= rider.cost();
            }
            while (left - onFoot >= rider.cost() && group.size() < cap) {
                group.add(rider);
                left -= rider.cost();
            }
            while (tier >= 2 && left >= RaiderKind.SKELETON.cost() && group.size() < cap) {
                group.add(RaiderKind.SKELETON);
                left -= RaiderKind.SKELETON.cost();
            }
            if (!group.isEmpty()) plan.add(group);
        }
        if (plan.isEmpty()) plan.add(new ArrayList<>(List.of(rider)));
        return plan;
    }

    /**
     * The night horde in groups of three to five (the last may be smaller),
     * spending {@link #HORDE_SCALE} times the budget. Husks in the desert,
     * some drowned near water, babies and zombie villagers anywhere, brutes
     * at tier 3.
     */
    public static List<List<RaiderKind>> planHorde(int budget, int tier, int maxAlive, RandomGenerator rng,
                                                   boolean desert, boolean water) {
        int left = (int) Math.round(Math.max(0, budget) * HORDE_SCALE);
        int cap = Math.max(1, maxAlive);
        int total = 0;
        List<List<RaiderKind>> groups = new ArrayList<>();
        while (left > 0 && total < MAX_HORDE) {
            int size = Math.min(Math.min(left, cap), 3 + rng.nextInt(3));
            List<RaiderKind> group = new ArrayList<>();
            while (group.size() < size && left > 0 && total < MAX_HORDE) {
                RaiderKind kind;
                if (tier >= 3 && left >= RaiderKind.BRUTE.cost() && rng.nextDouble() < 0.15) {
                    kind = RaiderKind.BRUTE;
                } else {
                    double roll = rng.nextDouble();
                    if (roll < 0.15) kind = RaiderKind.BABY_ZOMBIE;
                    else if (roll < 0.25) kind = RaiderKind.ZOMBIE_VILLAGER;
                    else if (water && !desert && roll < 0.40) kind = RaiderKind.DROWNED;
                    else kind = desert ? RaiderKind.HUSK : RaiderKind.ZOMBIE;
                }
                group.add(kind);
                left -= kind.cost();
                total++;
            }
            if (group.isEmpty()) break;
            groups.add(group);
        }
        if (groups.isEmpty()) groups.add(new ArrayList<>(List.of(desert ? RaiderKind.HUSK : RaiderKind.ZOMBIE)));
        return groups;
    }

    public static int waveCount(int budget) {
        if (budget < 10) return 1;
        if (budget <= 25) return 2;
        return 3;
    }

    public static List<List<RaiderKind>> plan(int budget, int tier, int maxAlive, RandomGenerator rng) {
        int waves = waveCount(budget);
        double[] shares = waves == 1 ? ONE_WAVE : waves == 2 ? TWO_WAVES : THREE_WAVES;
        int cap = Math.max(1, maxAlive);
        List<List<RaiderKind>> plan = new ArrayList<>();
        int spent = 0;
        for (int i = 0; i < waves; i++) {
            boolean last = i == waves - 1;
            int waveBudget = last ? budget - spent : (int) Math.floor(budget * shares[i]);
            spent += waveBudget;
            plan.add(wave(waveBudget, tier, last, cap, rng));
        }
        return plan;
    }

    private static List<RaiderKind> wave(int budget, int tier, boolean last, int cap, RandomGenerator rng) {
        List<RaiderKind> wave = new ArrayList<>();
        int left = budget;
        if (last && tier >= 2 && left >= 12 && wave.size() < cap) {
            wave.add(RaiderKind.RAVAGER);
            left -= RaiderKind.RAVAGER.cost();
        }
        if (last && tier >= 3 && left >= 8 && wave.size() < cap) {
            wave.add(RaiderKind.EVOKER);
            left -= RaiderKind.EVOKER.cost();
        }
        if (tier >= 2 && left >= 7 && wave.size() < cap && rng.nextBoolean()) {
            wave.add(RaiderKind.WITCH);
            left -= RaiderKind.WITCH.cost();
        }
        while (left >= RaiderKind.PILLAGER.cost() && wave.size() < cap) {
            RaiderKind kind = left >= RaiderKind.VINDICATOR.cost() && rng.nextDouble() < VINDICATOR_CHANCE
                    ? RaiderKind.VINDICATOR : RaiderKind.PILLAGER;
            wave.add(kind);
            left -= kind.cost();
        }
        if (wave.isEmpty()) wave.add(RaiderKind.PILLAGER);
        return wave;
    }
}
