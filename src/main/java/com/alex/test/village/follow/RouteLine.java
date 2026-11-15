package com.alex.test.village.follow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The line a villager walks: a route's tiles straightened into a few long stretches, checked
 * with the body's real width rather than a thin line through block middles.
 *
 * <p>The old smoother tested a thin line, so a straightened stretch could clip a fence post
 * the line missed by a hair. Here a box as wide as the body plus {@link #MARGIN} is slid along
 * every stretch and must stay over open tiles the whole way.
 *
 * <p>A stretch never spans a change of floor height: every rise or drop, slab edges included,
 * is crossed from one tile's middle to the next tile's middle, straight on, which is the way
 * {@link StepRules} judged it. Tiles marked {@link RouteTile#anchor} are passed through their
 * middle too. Corners are only cut on level ground.
 *
 * <p>Built once per route on the server thread; no Minecraft in it. Round 3 bounds the work
 * for long routes: each stretch is found with a few box tests (a binary search) instead of one
 * per tile, and a box test skips the cells the previous sample already checked.
 */
public final class RouteLine {
    /** Half the villager's width. */
    public static final double HALF_WIDTH = 0.3;
    /** Room kept between the body and anything off the line, for steering slack at corners. */
    public static final double MARGIN = 0.1;
    /** Floors this close count as level: carpets, dirt paths and farmland beside grass. */
    public static final double FLAT = 0.2;
    /** The longest straightened stretch, to bound the work per route. */
    public static final double MAX_STRETCH = 16.0;
    /**
     * The most route tiles one stretch may span. A 16-block diagonal is about 23 tiles of a
     * four-way route; a winding one can pack more into 16 blocks, and the search is capped here.
     */
    public static final int MAX_STRETCH_TILES = 32;
    /** Spacing of the box test along a stretch. */
    private static final double SAMPLE = 0.1;

    /** A step on the line that the walker must act on (jump or drop). */
    public record StepMark(int tile, Step step, double edgeArc, double riserArc) {}

    /** Whether the body may be over column {@code x, z}: the test a box sweep asks per cell. */
    @FunctionalInterface
    public interface Cells {
        boolean open(int x, int z);
    }

    private final List<RouteTile> tiles;
    private final double[] xs;
    private final double[] zs;
    private final int[] tileOf;
    private final double[] arc;
    private final List<StepMark> steps;

    private RouteLine(List<RouteTile> tiles, double[] xs, double[] zs, int[] tileOf, List<StepMark> steps) {
        this.tiles = tiles;
        this.xs = xs;
        this.zs = zs;
        this.tileOf = tileOf;
        this.arc = new double[xs.length];
        for (int i = 1; i < xs.length; i++) {
            arc[i] = arc[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        this.steps = steps;
    }

    /** Straightens {@code route} (at least one tile) over {@code area}. */
    public static RouteLine build(List<RouteTile> route, OpenArea area) {
        int n = route.size();
        if (n == 0) throw new IllegalArgumentException("empty route");
        boolean[] hard = new boolean[n];
        for (int i = 0; i < n; i++) {
            RouteTile t = route.get(i);
            hard[i] = t.anchor() || i == 0 || i == n - 1
                    || (i < n - 1 && t.toNext().kind() != StepKind.WALK)
                    || (i > 0 && route.get(i - 1).toNext().kind() != StepKind.WALK);
        }

        List<Integer> picked = new ArrayList<>();
        picked.add(0);
        int i = 0;
        while (i < n - 1) {
            int limit = i + 1;
            while (limit < n - 1 && !hard[limit]) limit++;
            picked.add(furthestClear(route, i, Math.min(limit, i + MAX_STRETCH_TILES), area));
            i = picked.get(picked.size() - 1);
        }

        double[] xs = new double[picked.size()];
        double[] zs = new double[picked.size()];
        int[] tileOf = new int[picked.size()];
        for (int p = 0; p < picked.size(); p++) {
            RouteTile t = route.get(picked.get(p));
            xs[p] = t.centreX();
            zs[p] = t.centreZ();
            tileOf[p] = picked.get(p);
        }

        RouteLine line = new RouteLine(List.copyOf(route), xs, zs, tileOf, new ArrayList<>());
        // Every non-walking step has both its tiles picked, one after the other: the step is
        // crossed from middle to middle, its shared edge half a block along.
        for (int p = 0; p + 1 < tileOf.length; p++) {
            Step step = route.get(tileOf[p]).toNext();
            if (step.kind() == StepKind.WALK || tileOf[p + 1] != tileOf[p] + 1) continue;
            line.steps.add(new StepMark(tileOf[p], step, line.arc[p] + 0.5, line.arc[p] + step.riserAt()));
        }
        return line;
    }

    /**
     * The furthest tile up to {@code hi} that a straight stretch from tile {@code from} reaches
     * over clear ground, at least the next tile. A binary search, which takes clearness to fall
     * off with distance: when it does not quite (a far tile clear past a blocked nearer one), the
     * answer is still a tile whose stretch was tested clear, just not always the furthest.
     */
    private static int furthestClear(List<RouteTile> route, int from, int hi, OpenArea area) {
        int lo = from + 1;
        if (hi <= lo) return lo;
        if (isClear(route, from, hi, area)) return hi;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (isClear(route, from, mid, area)) lo = mid; else hi = mid;
        }
        return lo;
    }

    /**
     * True when a body-wide box slid from tile {@code from}'s middle to tile {@code to}'s stays
     * over level, open ground the whole way.
     */
    static boolean isClear(List<RouteTile> route, int from, int to, OpenArea area) {
        RouteTile a = route.get(from);
        RouteTile b = route.get(to);
        for (int k = from + 1; k <= to; k++) {
            if (Math.abs(route.get(k).stand() - a.stand()) > FLAT) return false;
            if (route.get(k - 1).toNext().kind() != StepKind.WALK) return false;
        }
        double length = Math.hypot(b.centreX() - a.centreX(), b.centreZ() - a.centreZ());
        if (length > MAX_STRETCH) return false;
        double stand = a.stand();
        return sweepClear(a.centreX(), a.centreZ(), b.centreX(), b.centreZ(), HALF_WIDTH + MARGIN,
                (x, z) -> onRoute(route, from, to, x, z) || area.open(x, z, stand));
    }

    /**
     * True when a square box of half-size {@code radius}, slid from ({@code ax}, {@code az}) to
     * ({@code bx}, {@code bz}) in steps of a tenth of a block, only ever covers columns
     * {@code cells} calls open. Cells the previous sample covered are not asked again: along a
     * stretch most samples cover the same two to four cells.
     */
    public static boolean sweepClear(double ax, double az, double bx, double bz, double radius, Cells cells) {
        double dx = bx - ax;
        double dz = bz - az;
        int samples = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / SAMPLE));
        int px0 = Integer.MAX_VALUE, px1 = Integer.MIN_VALUE, pz0 = Integer.MAX_VALUE, pz1 = Integer.MIN_VALUE;
        for (int s = 0; s <= samples; s++) {
            double t = (double) s / samples;
            double cx = ax + dx * t;
            double cz = az + dz * t;
            int x0 = (int) Math.floor(cx - radius), x1 = (int) Math.floor(cx + radius - 1.0E-9);
            int z0 = (int) Math.floor(cz - radius), z1 = (int) Math.floor(cz + radius - 1.0E-9);
            if (x0 == px0 && x1 == px1 && z0 == pz0 && z1 == pz1) continue;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (x >= px0 && x <= px1 && z >= pz0 && z <= pz1) continue;
                    if (!cells.open(x, z)) return false;
                }
            }
            px0 = x0;
            px1 = x1;
            pz0 = z0;
            pz1 = z1;
        }
        return true;
    }

    private static boolean onRoute(List<RouteTile> route, int from, int to, int x, int z) {
        for (int k = from; k <= to; k++) {
            RouteTile t = route.get(k);
            if (t.x() == x && t.z() == z) return true;
        }
        return false;
    }

    public int points() {
        return xs.length;
    }

    public double x(int point) {
        return xs[point];
    }

    public double z(int point) {
        return zs[point];
    }

    /** Distance along the line from its start to {@code point}. */
    public double arc(int point) {
        return arc[point];
    }

    public double length() {
        return arc[arc.length - 1];
    }

    /** The route tile a line point sits on. */
    public RouteTile tile(int point) {
        return tiles.get(tileOf[point]);
    }

    /** Index of that tile in the route. */
    public int tileIndex(int point) {
        return tileOf[point];
    }

    /** The line point sitting on route tile {@code tile}, or -1 when the line cuts past that tile. */
    public int pointOfTile(int tile) {
        int found = Arrays.binarySearch(tileOf, tile);
        return found >= 0 ? found : -1;
    }

    /** The jumps and drops along the line, in order. */
    public List<StepMark> steps() {
        return Collections.unmodifiableList(steps);
    }

    /**
     * A corner the walker may curve through instead of turning on the spot (step 5, 10-18): a
     * plain tile, no stair, door or a door's neighbour, with both route tiles either side of it
     * walked on level with it. Whether the curve has room is checked live by the walker.
     */
    public boolean roundable(int point) {
        if (point <= 0 || point >= xs.length - 1) return false;
        int k = tileOf[point];
        if (k <= 0 || k >= tiles.size() - 1) return false;
        RouteTile before = tiles.get(k - 1), corner = tiles.get(k), after = tiles.get(k + 1);
        if (before.anchor() || corner.anchor() || after.anchor()) return false;
        if (before.toNext().kind() != StepKind.WALK || corner.toNext().kind() != StepKind.WALK) return false;
        return Math.abs(before.stand() - corner.stand()) <= FLAT && Math.abs(after.stand() - corner.stand()) <= FLAT;
    }

    /** Turn at {@code point} between the stretch in and the stretch out, in radians (0 = straight). */
    public double bend(int point) {
        if (point <= 0 || point >= xs.length - 1) return 0.0;
        double ax = xs[point] - xs[point - 1], az = zs[point] - zs[point - 1];
        double bx = xs[point + 1] - xs[point], bz = zs[point + 1] - zs[point];
        double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
        if (la < 1.0E-9 || lb < 1.0E-9) return 0.0;
        double cos = (ax * bx + az * bz) / (la * lb);
        return Math.acos(Math.max(-1.0, Math.min(1.0, cos)));
    }
}
