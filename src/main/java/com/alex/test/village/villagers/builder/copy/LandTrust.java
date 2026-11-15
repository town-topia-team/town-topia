package com.alex.test.village.villagers.builder.copy;

import java.util.List;

/**
 * Can the world generator's bare land be trusted around a building? It is only right where this
 * same generator made the land: not in chunks from an older Minecraft version, at the blended seams
 * next to them, with a terrain mod that brings its own generator class, or in void worlds.
 *
 * <p>Each sample is a column of natural surface (grass-like soil or sand under open sky) with the
 * height of that surface and of the bare land's top. Surface layers never change the height, so in
 * a matching world they agree. Only ground standing HIGHER than the bare land is dangerous: there
 * the bare land says air, so {@link PlacedJudge} would call the hill placed. Ground lower than the
 * bare land (dug out, flattened) only makes the judge fall back to today's rule, so it doesn't count.
 *
 * <p>No Minecraft in here. The thresholds are placeholders until round 0's measurements
 * ({@code /villager copycheck} prints the gaps).
 */
public final class LandTrust {

    /** A height that could not be found: an unusable sample. */
    public static final int NONE = Integer.MIN_VALUE;
    /** Gaps counted in {@link Vote#byGap}: 0, 1, 2, 3 and 4 or more blocks. */
    public static final int GAP_BUCKETS = 5;

    private LandTrust() {}

    /**
     * @param tolerance        a gap this small still agrees (blocks)
     * @param maxHigherPercent at most this share of usable samples may stand higher than the bare land
     * @param minUsable        fewer usable samples than this: not trusted
     */
    public record Thresholds(int tolerance, int maxHigherPercent, int minUsable) {
        /** Placeholders until round 0's measurements. */
        public static final Thresholds DEFAULT = new Thresholds(1, 20, 8);
    }

    /** One column: its natural surface and the top of its bare land, either {@link #NONE}. */
    public record Sample(int x, int z, int surfaceY, int bareTopY) {
        public boolean usable() {
            return surfaceY != NONE && bareTopY != NONE;
        }

        /** Above zero: the ground stands higher than the bare land. */
        public int gap() {
            return surfaceY - bareTopY;
        }
    }

    /**
     * @param looked  columns looked at
     * @param usable  of those, natural surface with a bare-land answer
     * @param within  gap within the tolerance
     * @param higher  ground higher than the bare land by more than the tolerance
     * @param lower   ground lower than the bare land by more than the tolerance
     * @param byGap   usable samples by the size of the gap (either way): 0, 1, 2, 3, 4+
     */
    public record Vote(boolean trusted, int looked, int usable, int within, int higher, int lower, int[] byGap) {}

    public static Vote vote(List<Sample> samples, Thresholds thresholds) {
        int usable = 0, within = 0, higher = 0, lower = 0;
        int[] byGap = new int[GAP_BUCKETS];
        for (Sample sample : samples) {
            if (!sample.usable()) continue;
            usable++;
            int gap = sample.gap();
            byGap[Math.min(Math.abs(gap), GAP_BUCKETS - 1)]++;
            if (gap > thresholds.tolerance()) higher++;
            else if (gap < -thresholds.tolerance()) lower++;
            else within++;
        }
        boolean trusted = usable >= thresholds.minUsable()
                && higher * 100L <= (long) thresholds.maxHigherPercent() * usable;
        return new Vote(trusted, samples.size(), usable, within, higher, lower, byGap);
    }
}
