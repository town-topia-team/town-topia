package com.alex.test.village.villagers.miner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * How common each overworld ore is at a given height, from vanilla 1.21.1's
 * own ore placements: how many veins a chunk tries, how big a vein is, and the
 * height range with its shape. A triangle range is densest in its middle and
 * thins out to nothing at both ends; a uniform one is the same all the way.
 *
 * <p>The numbers are the plain-biome ones. Mountain emeralds, badlands gold and
 * dripstone copper are left out, and so are the veins vanilla throws away for
 * touching air: this is a guide to the mix, not a block count.
 *
 * <pre>
 *   Y 96    coal 75, copper 13, iron 12
 *   Y 48    copper 48, coal 34, iron 14, lapis 4
 *   Y 16    iron 50, copper 25, coal 12, lapis 9, gold 5
 *   Y 0     iron 41, copper 16, lapis 16, gold 13, redstone 10, diamond 4
 *   Y -16   iron 26, gold 26, diamond 20, lapis 15, redstone 14
 *   Y -58   redstone 52, diamond 25, gold 9, iron 8, lapis 6
 * </pre>
 *
 * <p>A druid's regrown ore and a miner's scrap ore both take their mix from
 * here, each with caps of its own (DruidBalance, MinerBalance), and a miner
 * told which ores it wants picks the shaft whose height fits them best. No
 * Minecraft classes, so tests can load it.
 */
public final class OreHeights {

    /** The overworld's floor and roof. */
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 320;

    /** Below this share an ore is not named among a height's main ores. */
    private static final double MAIN_ORE_SHARE = 0.08;
    /** At most this many main ores are named for one height. */
    private static final int MAIN_ORE_COUNT = 3;

    /** The overworld ores that follow height. Emerald and ancient debris do not, here. */
    public enum Ore {
        COAL("coal", "Coal"),
        COPPER("copper", "Copper"),
        IRON("iron", "Iron"),
        LAPIS("lapis", "Lapis"),
        REDSTONE("redstone", "Redstone"),
        GOLD("gold", "Gold"),
        DIAMOND("diamond", "Diamond");

        private final String id;
        private final String label;

        Ore(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** Short lower-case name, used in setting ids and translation keys. */
        public String id() { return id; }

        /** English name with a capital, for screens and chat. */
        public String label() { return label; }
    }

    private static final Ore[] ORES = Ore.values();

    /**
     * One of vanilla's ore placements: veins tried per chunk, blocks per vein,
     * and the height range. {@code triangle} false means uniform.
     */
    private record Placement(Ore ore, double veins, int size, boolean triangle, int low, int high) {}

    private static final Placement[] PLACEMENTS = {
            new Placement(Ore.COAL, 30, 17, false, 136, MAX_Y),
            new Placement(Ore.COAL, 20, 17, true, 0, 192),
            new Placement(Ore.IRON, 90, 9, true, 80, 384),
            new Placement(Ore.IRON, 10, 9, true, -24, 56),
            new Placement(Ore.IRON, 10, 4, false, MIN_Y, 72),
            new Placement(Ore.COPPER, 16, 10, true, -16, 112),
            new Placement(Ore.GOLD, 4, 9, true, -64, 32),
            new Placement(Ore.GOLD, 0.5, 9, false, MIN_Y, -48),
            new Placement(Ore.REDSTONE, 4, 8, false, MIN_Y, 15),
            new Placement(Ore.REDSTONE, 8, 8, true, -96, -32),
            new Placement(Ore.LAPIS, 2, 7, true, -32, 32),
            new Placement(Ore.LAPIS, 4, 7, false, MIN_Y, 64),
            new Placement(Ore.DIAMOND, 7, 4, true, -144, 16),
            new Placement(Ore.DIAMOND, 1 / 9.0, 12, true, -144, 16),
            new Placement(Ore.DIAMOND, 4, 8, true, -144, 16),
            new Placement(Ore.DIAMOND, 2, 8, false, MIN_Y, -4),
    };

    /** Each ore's highest density at any height, for {@link #fit}. */
    private static final double[] PEAK = new double[ORES.length];

    static {
        for (int y = MIN_Y; y <= MAX_Y; y++) {
            double[] here = densities(y);
            for (int i = 0; i < ORES.length; i++) PEAK[i] = Math.max(PEAK[i], here[i]);
        }
    }

    private OreHeights() {}

    /** The world height clamped into the overworld's range. */
    public static int clampY(int y) {
        return Math.clamp(y, MIN_Y, MAX_Y);
    }

    /** Roughly how many blocks of each ore one layer of a chunk holds at {@code y}, by ordinal. */
    public static double[] densities(int y) {
        int at = clampY(y);
        double[] out = new double[ORES.length];
        for (Placement placement : PLACEMENTS) {
            out[placement.ore().ordinal()] += placement.veins() * placement.size()
                    * spread(placement, at);
        }
        return out;
    }

    /** Share of one placement's veins that lands on layer {@code y}. */
    private static double spread(Placement placement, int y) {
        if (y < placement.low() || y > placement.high()) return 0.0;
        if (!placement.triangle()) return 1.0 / (placement.high() - placement.low() + 1);
        double middle = (placement.low() + placement.high()) / 2.0;
        double half = (placement.high() - placement.low()) / 2.0;
        return Math.max(0.0, 1.0 - Math.abs(y - middle) / half) / half;
    }

    /** The ore mix at {@code y}: each ore's share of the ore there, by ordinal, summing to 1. */
    public static double[] mix(int y) {
        return normalise(densities(y));
    }

    /** {@code weights} scaled to sum to 1; all zero when they are. */
    public static double[] normalise(double[] weights) {
        double total = 0.0;
        for (double weight : weights) total += Math.max(0.0, weight);
        double[] out = new double[weights.length];
        if (total <= 0.0) return out;
        for (int i = 0; i < weights.length; i++) out[i] = Math.max(0.0, weights[i]) / total;
        return out;
    }

    /** The mix with {@code ore}'s share lowered to {@code cap} when it is above it. */
    public static double[] atMost(double[] shares, Ore ore, double cap) {
        return shares[ore.ordinal()] > cap ? withShare(shares, ore, cap) : shares.clone();
    }

    /** The mix with {@code ore}'s share raised to {@code floor} when it is below it. */
    public static double[] atLeast(double[] shares, Ore ore, double floor) {
        return shares[ore.ordinal()] < floor ? withShare(shares, ore, floor) : shares.clone();
    }

    /**
     * The mix with {@code ore} set to {@code share} and the other ores scaled
     * to fill the rest, keeping their order. Unchanged when there are no
     * other ores to scale.
     */
    private static double[] withShare(double[] shares, Ore ore, double share) {
        int index = ore.ordinal();
        double others = 1.0 - shares[index];
        double[] out = shares.clone();
        if (others <= 0.0) return out;
        double scale = (1.0 - share) / others;
        for (int i = 0; i < out.length; i++) out[i] = i == index ? share : shares[i] * scale;
        return out;
    }

    /** The ore a roll in [0, 1) lands on in {@code shares}; null when the mix is empty. */
    public static Ore pick(double[] shares, double roll) {
        Ore last = null;
        for (int i = 0; i < ORES.length; i++) {
            if (shares[i] <= 0.0) continue;
            last = ORES[i];
            roll -= shares[i];
            if (roll < 0.0) return last;
        }
        return last;   // rounding left a sliver past the end
    }

    /**
     * How well a shaft at {@code y} suits a miner that wants {@code wanted}:
     * each wanted ore's density there against its best height anywhere,
     * added up. A shaft at the very best height for one wanted ore scores 1
     * for it, so a wish for two ores weighs them evenly.
     */
    public static double fit(Collection<Ore> wanted, int y) {
        double[] here = densities(y);
        double score = 0.0;
        for (Ore ore : wanted) {
            double peak = PEAK[ore.ordinal()];
            if (peak > 0.0) score += here[ore.ordinal()] / peak;
        }
        return score;
    }

    /** The ores most of the ore at {@code y} is, biggest share first: at most three. */
    public static List<Ore> mainOres(int y) {
        double[] shares = mix(y);
        List<Ore> main = new ArrayList<>();
        for (Ore ore : ORES) if (shares[ore.ordinal()] >= MAIN_ORE_SHARE) main.add(ore);
        main.sort(Comparator.comparingDouble((Ore ore) -> shares[ore.ordinal()]).reversed());
        return main.size() > MAIN_ORE_COUNT ? List.copyOf(main.subList(0, MAIN_ORE_COUNT)) : List.copyOf(main);
    }

    /** "Redstone 52%, Diamond 25%, ..." for every ore with a share of at least half a percent. */
    public static String describe(double[] shares) {
        List<Ore> order = new ArrayList<>(List.of(ORES));
        order.sort(Comparator.comparingDouble((Ore ore) -> shares[ore.ordinal()]).reversed());
        StringBuilder out = new StringBuilder();
        for (Ore ore : order) {
            double share = shares[ore.ordinal()];
            if (share < 0.005) continue;
            if (!out.isEmpty()) out.append(", ");
            out.append(ore.label()).append(' ').append(Math.round(share * 100)).append('%');
        }
        return out.isEmpty() ? "none" : out.toString();
    }
}
