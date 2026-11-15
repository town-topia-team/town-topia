package com.alex.test.village.villagers.mage;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the mage's area spells go: the spot where the most mobs stand packed
 * together, and the order a Chain Spark jumps through them. Pure geometry over
 * plain points, so it is tested without a world (the activity turns mobs into
 * points and back).
 */
public final class MageTargeting {

    /** A mob's feet. */
    public record Point(double x, double y, double z) {
        double distanceSq(Point other) {
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /**
     * The best spot for an area spell: centred on one of the mobs, with the
     * most others within {@code radius} of it. {@code index} is that mob and
     * {@code count} how many the spell would catch, the centre mob included.
     */
    public record Cluster(int index, int count) {
        public static final Cluster NONE = new Cluster(-1, 0);
    }

    /** A mob more than this far above or below the centre is not caught (a nova hugs the ground). */
    private static final double CLUSTER_MAX_DY = 2.5;

    private MageTargeting() {}

    /**
     * The mob around which the most mobs stand within {@code radius}. Ties go to
     * the tighter pack (the smaller total distance), then to the earlier mob.
     */
    public static Cluster densest(List<Point> mobs, double radius) {
        if (mobs.isEmpty()) return Cluster.NONE;
        double radiusSq = radius * radius;
        int bestIndex = -1;
        int bestCount = 0;
        double bestSpread = Double.MAX_VALUE;
        for (int i = 0; i < mobs.size(); i++) {
            Point centre = mobs.get(i);
            int count = 0;
            double spread = 0.0;
            for (Point other : mobs) {
                if (Math.abs(other.y - centre.y) > CLUSTER_MAX_DY) continue;
                double dx = other.x - centre.x, dz = other.z - centre.z;
                double flatSq = dx * dx + dz * dz;
                if (flatSq > radiusSq) continue;
                count++;
                spread += Math.sqrt(flatSq);
            }
            if (count > bestCount || count == bestCount && spread < bestSpread) {
                bestIndex = i;
                bestCount = count;
                bestSpread = spread;
            }
        }
        return new Cluster(bestIndex, bestCount);
    }

    /**
     * The mobs a Chain Spark hits, in order: {@code first}, then each time the
     * nearest mob not hit yet within {@code jumpRange} of the last one, until
     * {@code maxHits} mobs are hit or none is in reach.
     */
    public static List<Integer> chain(List<Point> mobs, int first, double jumpRange, int maxHits) {
        List<Integer> path = new ArrayList<>();
        if (first < 0 || first >= mobs.size() || maxHits <= 0) return path;
        boolean[] hit = new boolean[mobs.size()];
        double rangeSq = jumpRange * jumpRange;
        int at = first;
        while (at >= 0 && path.size() < maxHits) {
            path.add(at);
            hit[at] = true;
            Point from = mobs.get(at);
            int next = -1;
            double nextSq = rangeSq;
            for (int i = 0; i < mobs.size(); i++) {
                if (hit[i]) continue;
                double d = from.distanceSq(mobs.get(i));
                if (d <= nextSq) {
                    next = i;
                    nextSq = d;
                }
            }
            at = next;
        }
        return path;
    }

    /** How many mobs a Chain Spark started on {@code first} would hit. */
    public static int chainLength(List<Point> mobs, int first, double jumpRange, int maxHits) {
        return chain(mobs, first, jumpRange, maxHits).size();
    }
}
