package com.alex.test.village.raid;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Something that makes one warband raid unlike the last. About half of all
 * raids get one; tier 3 raids may get a second.
 *
 * <p>No Minecraft types: unit-tested on a plain JVM.
 */
public enum RaidTwist {
    /** Attacks after dark (clock 14000 to 20000) instead of at dusk. */
    NIGHT_ATTACK("Night attack", "they will attack after dark", 1),
    /** A ravager leads the first wave, straight at the village. */
    SIEGE_BEAST("Siege beast", "a ravager leads the first wave", 2),
    /** A third of the raiders carry enchanted weapons and iron armour, and drop more. */
    ELITE_GEAR("Elite gear", "some of them carry enchanted weapons", 3),
    /** Looters go for food, in storage rooms and kitchens. */
    HUNGRY("Hungry", "their looters are after your food", 1),
    /** No scout, and the warning comes only when the camp goes up. */
    QUIET_SCOUTS("Quiet scouts", "they sent no scout", 1);

    private final String label;
    private final String description;
    private final int minTier;

    RaidTwist(String label, String description, int minTier) {
        this.label = label;
        this.description = description;
        this.minTier = minTier;
    }

    public String label() { return label; }

    /** "a ravager leads the first wave": fits after "Twist: ". */
    public String description() { return description; }

    public int minTier() { return minTier; }

    /** Half of all raids get a twist; at tier 3, half of those get a second. */
    public static List<RaidTwist> roll(int tier, RandomGenerator rng) {
        List<RaidTwist> twists = new ArrayList<>();
        if (rng.nextDouble() >= 0.5) return twists;
        RaidTwist first = pickOther(tier, twists, rng);
        if (first != null) twists.add(first);
        if (tier >= 3 && !twists.isEmpty() && rng.nextDouble() < 0.5) {
            RaidTwist second = pickOther(tier, twists, rng);
            if (second != null) twists.add(second);
        }
        return twists;
    }

    private static RaidTwist pickOther(int tier, List<RaidTwist> taken, RandomGenerator rng) {
        List<RaidTwist> options = new ArrayList<>();
        for (RaidTwist twist : values()) {
            if (twist.minTier <= tier && !taken.contains(twist)) options.add(twist);
        }
        return options.isEmpty() ? null : options.get(rng.nextInt(options.size()));
    }

    /**
     * The same raid with a ravager at the head of the first wave. A ravager
     * planned for a later wave moves up; otherwise one is added and raiders
     * worth about as much are taken from the last waves, so the raid costs
     * about the same. No wave is left empty.
     */
    public static List<List<RaiderKind>> withSiegeBeast(List<List<RaiderKind>> waves) {
        List<List<RaiderKind>> out = new ArrayList<>();
        boolean hadRavager = false;
        for (List<RaiderKind> wave : waves) {
            List<RaiderKind> copy = new ArrayList<>(wave);
            hadRavager |= copy.remove(RaiderKind.RAVAGER);
            out.add(copy);
        }
        if (!hadRavager) {
            int toRemove = RaiderKind.RAVAGER.cost();
            for (int w = out.size() - 1; w >= 0 && toRemove > 0; w--) {
                List<RaiderKind> wave = out.get(w);
                for (int i = wave.size() - 1; i >= 0 && toRemove > 0; i--) {
                    RaiderKind kind = wave.get(i);
                    if (kind == RaiderKind.PILLAGER || kind == RaiderKind.VINDICATOR) {
                        wave.remove(i);
                        toRemove -= kind.cost();
                    }
                }
            }
        }
        if (out.isEmpty()) out.add(new ArrayList<>());
        out.get(0).add(0, RaiderKind.RAVAGER);
        out.removeIf(List::isEmpty);
        return out;
    }

    public static RaidTwist byName(String name) {
        for (RaidTwist twist : values()) {
            if (twist.name().equals(name)) return twist;
        }
        return null;
    }
}
