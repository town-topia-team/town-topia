package com.alex.test.village.follow;

import java.util.List;

/**
 * Steers along a {@link RouteLine}: each tick, where to aim and how far along the line the
 * villager has got.
 *
 * <p>Progress is distance along the line and never goes backward, so there is no waypoint to
 * "arrive" at, walk past and come back for, and a new line never starts by turning back to a
 * block's middle. The aim is a point {@link #LOOK_AHEAD} ahead on the line; it does not run past
 * a corner sharper than {@link #BEND} until the villager is within {@link #TURN_RADIUS} of it,
 * so the body cuts a corner by less than the line's {@link RouteLine#MARGIN} allows for.
 *
 * <p>A body pushed off the line aims closer (down to {@link #MIN_LOOK_AHEAD}), so it walks back
 * onto the line first instead of cutting across towards a far aim through whatever pushed it.
 *
 * <p>Only horizontal: heights come from the route's steps. No Minecraft in it. Per tick the
 * work is a few stretches; the corner and step lookups advance with the body instead of
 * searching from the start (round 3: routes of a few hundred tiles).
 */
public final class LinePursuit {
    /** How far ahead on the line the villager aims, in blocks. */
    public static final double LOOK_AHEAD = 1.0;
    /** The closest aim, for a body well off the line. */
    public static final double MIN_LOOK_AHEAD = 0.3;
    /** Within this of a sharp corner it counts as turned and aims along the next stretch. */
    public static final double TURN_RADIUS = 0.15;
    /** Corners sharper than this are walked to before turning. */
    public static final double BEND = Math.toRadians(15.0);
    /** Within this of the line's end, the line is done. */
    public static final double END_RADIUS = 0.25;
    /** A later stretch is only taken as the villager's own within this of it. */
    private static final double JOIN_DISTANCE = 0.5;
    /** How many stretches ahead a villager can be found on, for a body pushed past a corner. */
    private static final int WINDOW = 2;
    /** Curving through a level corner (step 5) starts this far before it along the line... */
    public static final double ROUND_FROM = 0.8;
    /** ...aiming straight at the point this far past it on the next stretch. */
    public static final double ROUND_PAST = 0.7;

    /**
     * Whether a body-wide box can slide straight from {@code a} to {@code b} near line point
     * {@code corner}: the village side answers from the map (no Minecraft here).
     */
    @FunctionalInterface
    public interface Clearance {
        boolean clear(double ax, double az, double bx, double bz, int corner);
    }

    private final RouteLine line;
    /** Null: corners are always turned on the spot. */
    private final Clearance clearance;
    /** The corner being curved through, or -1; the arc of the point aimed at meanwhile. */
    private int roundCorner = -1;
    private double roundArc;
    private int rounded;
    private double pointX;
    private double pointZ;
    /** Per line point: the first sharp corner at or after it, or the last point. */
    private final int[] nextSharp;
    private int segment;
    private double progress;
    private double offLine;
    private double aimX;
    private double aimZ;
    private boolean done;
    /** Index into the line's steps of the first one not yet left behind. */
    private int stepCursor;

    public LinePursuit(RouteLine line) {
        this(line, null);
    }

    /** With {@code clearance}, level corners with room are curved through instead of turned on the spot. */
    public LinePursuit(RouteLine line, Clearance clearance) {
        this.line = line;
        this.clearance = clearance;
        this.aimX = line.x(0);
        this.aimZ = line.z(0);
        int points = line.points();
        this.nextSharp = new int[points];
        int sharp = points - 1;
        for (int p = points - 1; p >= 0; p--) {
            if (p > 0 && p < points - 1 && line.bend(p) > BEND) sharp = p;
            nextSharp[p] = sharp;
        }
    }

    /** Moves the villager's place on the line to body position {@code x, z} and picks the aim. */
    public void update(double x, double z) {
        int last = line.points() - 1;
        if (last == 0) {
            aimX = line.x(0);
            aimZ = line.z(0);
            offLine = Math.hypot(x - aimX, z - aimZ);
            done = offLine <= END_RADIUS;
            return;
        }

        // Where on the line the body is: its own stretch, or one just ahead when it is right on it.
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestArc = progress;
        int bestSegment = segment;
        for (int s = segment; s <= Math.min(segment + WINDOW, last - 1); s++) {
            double ax = line.x(s), az = line.z(s);
            double dx = line.x(s + 1) - ax, dz = line.z(s + 1) - az;
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq < 1.0E-12 ? 0.0 : ((x - ax) * dx + (z - az) * dz) / lengthSq;
            t = Math.max(0.0, Math.min(1.0, t));
            double distance = Math.hypot(ax + dx * t - x, az + dz * t - z);
            if (s > segment && distance > JOIN_DISTANCE) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestSegment = s;
                bestArc = line.arc(s) + Math.sqrt(lengthSq) * t;
            }
        }
        segment = bestSegment;
        progress = Math.max(progress, bestArc);
        offLine = bestDistance;

        // A sharp corner within reach counts as turned; aim along the stretch after it.
        int corner = nextSharpCorner();
        while (corner < last && Math.hypot(x - line.x(corner), z - line.z(corner)) <= TURN_RADIUS) {
            progress = Math.max(progress, line.arc(corner));
            segment = Math.max(segment, corner);
            corner = nextSharpCorner();
        }

        double look = Math.max(MIN_LOOK_AHEAD, LOOK_AHEAD - offLine);
        double aimArc = Math.min(progress + look, line.arc(corner));
        // Step 5: close to a level corner with room, aim straight across it at a point on the
        // next stretch. The box test is made once, from where the curve starts: the body then
        // walks that checked chord until it is on the next stretch, so the aim never flips back.
        if (roundCorner != corner) roundCorner = -1;
        if (roundCorner < 0 && clearance != null && corner < last && line.roundable(corner)
                && line.arc(corner) - progress <= ROUND_FROM) {
            int after = corner + 1 < last ? nextSharp[corner + 1] : last;
            double past = Math.min(line.arc(corner) + ROUND_PAST, line.arc(after));
            pointAt(past);
            if (clearance.clear(x, z, pointX, pointZ, corner)) {
                roundCorner = corner;
                roundArc = past;
                rounded++;
            }
        }
        if (roundCorner == corner) aimArc = roundArc;
        setAimAt(aimArc);
        done = segment >= last - 1
                && Math.hypot(x - line.x(last), z - line.z(last)) <= END_RADIUS;

        List<RouteLine.StepMark> steps = line.steps();
        while (stepCursor < steps.size()
                && steps.get(stepCursor).edgeArc() < progress - RouteLine.HALF_WIDTH) {
            stepCursor++;
        }
    }

    /** The first line point ahead of the progress that is a sharp corner, or the end. */
    private int nextSharpCorner() {
        int last = line.points() - 1;
        int p = segment + 1;
        while (p < last) {
            int sharp = nextSharp[p];
            if (sharp >= last) return last;
            if (line.arc(sharp) > progress) return sharp;
            p = sharp + 1;
        }
        return last;
    }

    private void setAimAt(double arc) {
        pointAt(arc);
        aimX = pointX;
        aimZ = pointZ;
    }

    /** The line's point at distance {@code arc} from its start, into {@link #pointX}/{@link #pointZ}. */
    private void pointAt(double arc) {
        int last = line.points() - 1;
        int s = segment;
        while (s < last - 1 && line.arc(s + 1) < arc) s++;
        double span = line.arc(s + 1) - line.arc(s);
        double t = span < 1.0E-12 ? 1.0 : Math.max(0.0, Math.min(1.0, (arc - line.arc(s)) / span));
        pointX = line.x(s) + (line.x(s + 1) - line.x(s)) * t;
        pointZ = line.z(s) + (line.z(s + 1) - line.z(s)) * t;
    }

    /** How many corners this walk has curved through so far (step 5). */
    public int rounded() {
        return rounded;
    }

    /** True while the body is curving through a corner rather than following the line. */
    public boolean rounding() {
        return roundCorner >= 0;
    }

    public double aimX() {
        return aimX;
    }

    public double aimZ() {
        return aimZ;
    }

    /** Distance along the line covered so far; never goes down. */
    public double progress() {
        return progress;
    }

    public double remaining() {
        return Math.max(0.0, line.length() - progress);
    }

    /** How far the body is from the line: a big number means it fell off or was pushed away. */
    public double offLine() {
        return offLine;
    }

    /** True once the body is on the last stretch and within {@link #END_RADIUS} of the end. */
    public boolean done() {
        return done;
    }

    /**
     * The next jump or drop whose edge is still ahead of the body's back, or null. Reads only:
     * the cursor behind it moves in {@link #update}.
     */
    public RouteLine.StepMark nextStep() {
        List<RouteLine.StepMark> steps = line.steps();
        return stepCursor < steps.size() ? steps.get(stepCursor) : null;
    }

    /** Distance along the line from the body's middle to that step's riser (negative once past it). */
    public double toRiser(RouteLine.StepMark mark) {
        return mark.riserArc() - progress;
    }

    /** The stretch the body is on: from line point {@code segment()} to the next. */
    public int segment() {
        return segment;
    }

    public RouteLine line() {
        return line;
    }
}
