package com.alex.test.village;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The walk-back spy (round 1 of the walking-feel plan, 2026-10-15; switch:
 * {@code /villager walkfeel spylog}).
 *
 * <p>Players see villagers walk, spin, walk back a block or a few, then carry
 * on. This watches every walking villager for the moment it turns round on a
 * walk and then goes on the way it was going, and writes one {@code [WalkBack]}
 * line saying how far back it went and what the walk was doing just before:
 * lining up for a door, a new route, the brain picking another destination.
 * Every minute a summary counts them by cause. It reads only: nothing here steers.
 *
 * <p>One per {@link VillagerModel}. The stretch notes (a stretch handed to
 * Minecraft's pathfinder) went with the old walker on 2026-10-18, and the
 * causes and counts that read them went the same day.
 */
public final class WalkBackLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ticks of position kept. */
    private static final int RING = 48;
    /** The way the villager was going is measured from this many ticks ago... */
    private static final int HEADING_FROM = 16;
    /** ...to this many. */
    private static final int HEADING_TO = 8;
    /** Ground covered over that stretch for it to count as walking somewhere. */
    private static final double MIN_HEADING_MOVE = 0.5;
    /** Recent motion is measured over this many ticks. */
    private static final int RECENT_TICKS = 3;
    private static final double MIN_RECENT_MOVE = 0.06;
    /** Recent motion this far off the way it was going is going back. */
    private static final double BACK_ANGLE = 110.0;
    /** Facing this far off the way it was going is turned round, moving back yet or not. */
    private static final double FACING_BACK_ANGLE = 100.0;
    /** Moving within this of the old way again, for {@link #RESUME_TICKS}, is carrying on. */
    private static final double RESUME_ANGLE = 60.0;
    private static final int RESUME_TICKS = 4;
    /** A turn not carried on from within this long is a real change of direction. */
    private static final int MAX_EPISODE_TICKS = 200;
    private static final int MAX_STILL_TICKS = 60;
    /** Back at least this far is a walk-back; less, with a big enough turn, a spin. */
    private static final double WALK_BACK_BLOCKS = 0.5;
    private static final double SPIN_BACK_BLOCKS = 0.1;
    private static final double SPIN_TURN = 100.0;
    /** Events this long before a turn can have caused it. */
    private static final int CAUSE_WINDOW = 40;
    private static final int EVENT_KEEP_TICKS = 240;
    private static final int MAX_EVENTS = 24;
    private static final int RECENT_EVENTS_SHOWN = 8;
    /** Events up to this long after the turn are shown too: the new route a new destination brings. */
    private static final int EVENTS_AFTER_SHOWN = 10;
    /** A new destination this far from the old one's end means the old walk was not finished. */
    private static final double MID_WALK_BLOCKS = 3.0;
    private static final double TELEPORT_BLOCKS = 1.5;
    private static final int SUMMARY_TICKS = 1200;
    private static final int LINE_GAP_TICKS = 60;
    private static final int SPIN_LINES_PER_CAUSE = 3;

    enum Cause {
        BRAIN("brain changed its mind mid-walk"),
        NEXT_TASK("next destination was behind"),
        REPLAN_BEHIND("new route started behind"),
        MISSED_WAYPOINT("went back for a waypoint it walked past"),
        DOOR("lining up for a door"),
        ROUTE_TURNS("the route itself turns back here"),
        GRID_CORNER("right-angle corner on a step-by-step route"),
        STUCK("stuck re-plan"),
        TELEPORT("teleported"),
        WATER("swim-out"),
        LADDER_FLIP("ladder: turned round to the rung's middle"),
        LADDER_EXIT("ladder: stepping on or off"),
        UNKNOWN("unknown");

        final String label;

        Cause(String label) { this.label = label; }
    }

    public enum Kind {
        DOOR("lining up for a door"),
        WAYPOINT("reached waypoint"),
        ROUTE("new route"),
        GOAL("new destination"),
        REPLAN("re-plan"),
        STOP("walk stopped"),
        DONE("arrived"),
        FAILED("walk failed"),
        STUCK("stuck"),
        WATER("swim-out"),
        TELEPORT("moved without walking");

        final String label;

        Kind(String label) { this.label = label; }
    }

    /**
     * {@code flag}: for a new destination, the old walk was not finished.
     * {@code value}: for a new route, how far behind its first waypoint lies.
     */
    private record Event(long tick, Kind kind, String detail, boolean flag, double value) {}

    private static final class Episode {
        long start;
        double hx;
        double hz;
        float headingYaw;
        double anchorX;
        double anchorZ;
        double maxBack;
        double maxTurn;
        int forwardTicks;
        int stillTicks;
        String where = "";
        double waypointAngle = Double.NaN;
        double legAngle = Double.NaN;
        boolean door;
        boolean ladder;
        /** On a ladder, aimed at the rung's own column (climbing) rather than a landing. */
        boolean ladderOnRung;
        boolean water;
        /** How the walk was steered when it turned: nav, direct, door, ladder. */
        String mode = "";
    }

    private final double[] xs = new double[RING];
    private final double[] zs = new double[RING];
    private final float[] yaws = new float[RING];
    private final boolean[] walking = new boolean[RING];
    private int newest = -1;
    private int size;
    private long lastSampleTick = Long.MIN_VALUE;

    private final ArrayDeque<Event> events = new ArrayDeque<>();
    @Nullable private Episode episode;
    private long lastLineAt = Long.MIN_VALUE / 2;
    @Nullable private BlockPos lastGoal;

    // A minute's counts across every villager, for the summary line.
    private static final Map<Cause, Integer> WALK_BACKS = new EnumMap<>(Cause.class);
    private static final Map<Cause, Integer> SPINS = new EnumMap<>(Cause.class);
    private static final Map<Cause, Integer> SPIN_LINES = new EnumMap<>(Cause.class);
    private static int turnedAway;
    private static long nextSummaryAt = Long.MIN_VALUE;

    /** Stuck catcher: gets every walk note as one line while /villager stuckwatch records. */
    @Nullable private java.util.function.Consumer<String> recorder;

    void recordTo(java.util.function.Consumer<String> recorder) { this.recorder = recorder; }

    /** The notes are wanted by the spy log or by the stuck catcher. */
    private static boolean listening() {
        return WalkFeel.spyLog || com.alex.test.village.stuckwatch.StuckWatch.recording();
    }

    // -------------------------------------------------------------------------
    // What the walking code reports
    // -------------------------------------------------------------------------

    /** A walk was asked for. Notes a new destination; re-plans of the same one show as their route. */
    void noteRequest(NpcEntity npc, BlockPos goal, boolean replan, @Nullable Activity activity) {
        if (!listening()) return;
        long now = npc.level().getGameTime();
        if (replan) {
            note(now, Kind.REPLAN, "", false, 0.0);
        } else if (lastGoal != null && !lastGoal.equals(goal)) {
            double ox = lastGoal.getX() + 0.5 - npc.getX();
            double oz = lastGoal.getZ() + 0.5 - npc.getZ();
            boolean midWalk = ox * ox + oz * oz > MID_WALK_BLOCKS * MID_WALK_BLOCKS;
            note(now, Kind.GOAL, String.format(Locale.ROOT, " %s -> %s by %s%s",
                    lastGoal.toShortString(), goal.toShortString(), activityName(activity),
                    midWalk ? ", old one not reached" : ""), midWalk, 0.0);
        }
        lastGoal = goal;
    }

    /** A planned route took over the walk, its first {@code skipped} waypoints already walked past. */
    void noteRoute(NpcEntity npc, List<BlockPos> waypoints, long askedAt, int skipped) {
        if (!listening() || waypoints.isEmpty()) return;
        long now = npc.level().getGameTime();
        BlockPos first = waypoints.get(Math.min(skipped, waypoints.size() - 1));
        double fx = first.getX() + 0.5 - npc.getX();
        double fz = first.getZ() + 0.5 - npc.getZ();
        double away = Math.sqrt(fx * fx + fz * fz);
        double behind = 0.0;
        double[] heading = heading(0, HEADING_TO);
        // Within the arrival radius it counts as reached at once and is never walked to.
        if (heading != null && away > 1.5) behind = Math.max(0.0, -(fx * heading[0] + fz * heading[1]));
        note(now, Kind.ROUTE, String.format(Locale.ROOT, " (%d waypoints, %sfirst %.1f away%s, asked %d t before)",
                waypoints.size(), skipped > 0 ? "skipped " + skipped + " already passed, " : "", away,
                behind >= 0.5 ? String.format(Locale.ROOT, ", %.1f behind", behind) : "",
                now - askedAt), false, behind);
    }

    void note(NpcEntity npc, Kind kind, String detail) {
        if (!listening()) return;
        note(npc.level().getGameTime(), kind, detail, false, 0.0);
    }

    private void note(long now, Kind kind, String detail, boolean flag, double value) {
        if (recorder != null && com.alex.test.village.stuckwatch.StuckWatch.recording()) {
            recorder.accept(kind.label + detail);
        }
        if (!WalkFeel.spyLog) return;
        events.addLast(new Event(now, kind, detail, flag, value));
        while (events.size() > MAX_EVENTS
                || (!events.isEmpty() && now - events.peekFirst().tick() > EVENT_KEEP_TICKS)) {
            events.removeFirst();
        }
    }

    // -------------------------------------------------------------------------
    // Watching
    // -------------------------------------------------------------------------

    /** Once a tick, from {@link VillagerModel#tick}, before anything else there. */
    void sample(VillagerModel model, NpcEntity npc) {
        if (!WalkFeel.spyLog) {
            if (size > 0 || episode != null) {
                size = 0;
                episode = null;
                events.clear();
            }
            return;
        }
        long now = npc.level().getGameTime();
        if (now == lastSampleTick) return;
        lastSampleTick = now;
        summarise(now);

        double x = npc.getX();
        double z = npc.getZ();
        if (size > 0) {
            double jx = x - xs[newest];
            double jz = z - zs[newest];
            if (jx * jx + jz * jz > TELEPORT_BLOCKS * TELEPORT_BLOCKS) {
                note(now, Kind.TELEPORT, String.format(Locale.ROOT, " %.1f blocks", Math.sqrt(jx * jx + jz * jz)),
                        false, 0.0);
                size = 0;
                if (episode != null) finish(model, npc, now, false);
            }
        }
        boolean onWalk = model.isMoving() && !npc.isPassenger() && !npc.isSleeping()
                && !npc.isChairSeated();
        if (onWalk && model.moveGoal() != null) lastGoal = model.moveGoal();
        push(x, z, npc.getYRot(), onWalk);

        if (episode != null) {
            follow(model, npc, now, onWalk);
        } else if (onWalk) {
            watch(model, npc, now);
        }
    }

    private void push(double x, double z, float yaw, boolean onWalk) {
        newest = (newest + 1) % RING;
        xs[newest] = x;
        zs[newest] = z;
        yaws[newest] = yaw;
        walking[newest] = onWalk;
        if (size < RING) size++;
    }

    private int at(int ticksBack) {
        return Math.floorMod(newest - ticksBack, RING);
    }

    /** The unit direction walked from {@code from} ticks ago to {@code to} ticks ago, or null if it hardly moved. */
    @Nullable
    private double[] heading(int to, int from) {
        if (size <= from) return null;
        double hx = xs[at(to)] - xs[at(from)];
        double hz = zs[at(to)] - zs[at(from)];
        double length = Math.sqrt(hx * hx + hz * hz);
        if (length < MIN_HEADING_MOVE * (from - to) / (double) (HEADING_FROM - HEADING_TO)) return null;
        return new double[] { hx / length, hz / length };
    }

    private void watch(VillagerModel model, NpcEntity npc, long now) {
        if (size <= HEADING_FROM) return;
        if (!walking[at(HEADING_FROM)] || !walking[at(HEADING_TO)]) return;
        double[] heading = heading(HEADING_TO, HEADING_FROM);
        if (heading == null) return;
        double hx = heading[0];
        double hz = heading[1];
        float headingYaw = yawOf(hx, hz);

        double rx = xs[newest] - xs[at(RECENT_TICKS)];
        double rz = zs[newest] - zs[at(RECENT_TICKS)];
        double recent = Math.sqrt(rx * rx + rz * rz);
        boolean goingBack = recent >= MIN_RECENT_MOVE && angle(hx, hz, rx, rz) > BACK_ANGLE;
        boolean facingBack = Math.abs(Mth.wrapDegrees(yaws[newest] - headingYaw)) > FACING_BACK_ANGLE;
        if (!goingBack && !facingBack) return;

        Episode e = new Episode();
        e.start = now;
        e.hx = hx;
        e.hz = hz;
        e.headingYaw = headingYaw;
        // The furthest it got along the old way, from where that way was measured.
        int base = at(HEADING_TO);
        double best = 0.0;
        e.anchorX = xs[base];
        e.anchorZ = zs[base];
        for (int back = HEADING_TO - 1; back >= 0; back--) {
            int i = at(back);
            double along = (xs[i] - xs[base]) * hx + (zs[i] - zs[base]) * hz;
            if (along > best) {
                best = along;
                e.anchorX = xs[i];
                e.anchorZ = zs[i];
            }
        }
        describe(model, npc, e);
        episode = e;
        follow(model, npc, now, true);
    }

    private void follow(VillagerModel model, NpcEntity npc, long now, boolean onWalk) {
        Episode e = episode;
        if (e == null) return;
        double along = (xs[newest] - e.anchorX) * e.hx + (zs[newest] - e.anchorZ) * e.hz;
        e.maxBack = Math.max(e.maxBack, -along);
        e.maxTurn = Math.max(e.maxTurn, Math.abs(Mth.wrapDegrees(yaws[newest] - e.headingYaw)));
        if (!onWalk) {
            if (++e.stillTicks > MAX_STILL_TICKS) finish(model, npc, now, false);
            return;
        }
        e.stillTicks = 0;
        if (size > RECENT_TICKS) {
            double rx = xs[newest] - xs[at(RECENT_TICKS)];
            double rz = zs[newest] - zs[at(RECENT_TICKS)];
            boolean forward = Math.sqrt(rx * rx + rz * rz) >= MIN_RECENT_MOVE
                    && angle(e.hx, e.hz, rx, rz) < RESUME_ANGLE;
            e.forwardTicks = forward ? e.forwardTicks + 1 : 0;
        }
        if (e.forwardTicks >= RESUME_TICKS && now > e.start) {
            finish(model, npc, now, true);
        } else if (now - e.start > MAX_EPISODE_TICKS) {
            finish(model, npc, now, false);
        }
    }

    /** What the walk looked like the moment it turned round. */
    private static void describe(VillagerModel model, NpcEntity npc, Episode e) {
        MoveToCommand move = model.activeMoveForSpy();
        e.ladder = npc.isOnLadderLeg();
        e.water = model.isEscapingWater();
        StringBuilder where = new StringBuilder(String.format(Locale.ROOT, "at (%.1f, %.1f, %.1f)",
                npc.getX(), npc.getY(), npc.getZ()));
        BlockPos goal = model.moveGoal();
        if (goal != null) where.append(" trip to ").append(goal.toShortString());
        if (move == null) {
            where.append(", waiting for a route");
        } else {
            BlockPos wp = move.currentWaypoint();
            int index = move.currentWaypointIndex();
            List<BlockPos> waypoints = move.waypoints();
            e.door = move.spyThreadingDoor();
            e.ladderOnRung = move.spyLadderOnRung();
            e.mode = move.spyMode();
            where.append(String.format(Locale.ROOT, ", wp %d/%d", index + 1, waypoints.size()));
            if (wp != null) {
                where.append(' ').append(wp.toShortString());
                double wx = wp.getX() + 0.5 - npc.getX();
                double wz = wp.getZ() + 0.5 - npc.getZ();
                if (wx * wx + wz * wz > 0.09) e.waypointAngle = angle(e.hx, e.hz, wx, wz);
                if (index > 0) {
                    BlockPos previous = waypoints.get(index - 1);
                    double lx = wp.getX() - previous.getX();
                    double lz = wp.getZ() - previous.getZ();
                    if (lx != 0 || lz != 0) e.legAngle = angle(e.hx, e.hz, lx, lz);
                } else {
                    e.legAngle = e.waypointAngle;
                }
            }
            where.append(" mode ").append(move.spyMode());
        }
        where.append(" | waypoint ").append(degrees(e.waypointAngle))
                .append(" off the old way, its stretch ").append(degrees(e.legAngle));
        if (e.ladder) where.append(", on a ladder");
        if (e.water) where.append(", in water");
        Activity activity = model.brain().currentActivity();
        where.append(" | doing ").append(activityName(activity));
        e.where = where.toString();
    }

    private void finish(VillagerModel model, NpcEntity npc, long now, boolean carriedOn) {
        Episode e = episode;
        episode = null;
        if (e == null) return;
        if (!carriedOn) {
            turnedAway++;
            return;
        }
        boolean walkBack = e.maxBack >= WALK_BACK_BLOCKS;
        boolean spin = !walkBack && (e.maxBack >= SPIN_BACK_BLOCKS || e.maxTurn >= SPIN_TURN);
        if (!walkBack && !spin) return;

        List<Cause> causes = causes(e, now);
        Cause main = causes.get(0);
        (walkBack ? WALK_BACKS : SPINS).merge(main, 1, Integer::sum);
        if (walkBack) {
            if (now - lastLineAt < LINE_GAP_TICKS) return;
            lastLineAt = now;
        } else if (SPIN_LINES.merge(main, 1, Integer::sum) > SPIN_LINES_PER_CAUSE) {
            return;
        }

        StringJoiner why = new StringJoiner(" + ");
        for (Cause cause : causes) why.add(cause.label);
        String name = model.name();
        LOGGER.info("[WalkBack] {} {} ({}) {} {} blocks back, turned {} deg, for {} s, then carried on | why: {} | {} | recent: {}",
                model.npcId.toString().substring(0, 8), name == null ? "?" : name, npc.getProfession(),
                walkBack ? "WALKED" : "spun,",
                String.format(Locale.ROOT, "%.1f", Math.max(0.0, e.maxBack)),
                Math.round(e.maxTurn),
                String.format(Locale.ROOT, "%.1f", (now - e.start) / 20.0),
                why, e.where, recentEvents(e.start, now));
    }

    private List<Cause> causes(Episode e, long now) {
        List<Cause> out = new ArrayList<>();
        long from = e.start - CAUSE_WINDOW;
        boolean newRoute = false;
        boolean changedMind = false;
        boolean nextTask = false;
        boolean replanBehind = false;
        boolean door = e.door;
        boolean stuck = false;
        boolean teleport = false;
        boolean water = e.water;
        for (Event ev : events) {
            if (ev.tick() < from || ev.tick() > now) continue;
            switch (ev.kind()) {
                case GOAL -> {
                    if (ev.flag()) changedMind = true; else nextTask = true;
                }
                case ROUTE -> {
                    newRoute = true;
                    if (ev.value() >= 1.0) replanBehind = true;
                }
                case DOOR -> door = true;
                case STUCK, REPLAN -> stuck = true;
                case TELEPORT -> teleport = true;
                case WATER -> water = true;
                default -> { }
            }
        }
        if (changedMind) out.add(Cause.BRAIN);
        if (nextTask) out.add(Cause.NEXT_TASK);
        if (replanBehind) out.add(Cause.REPLAN_BEHIND);
        if (!newRoute && !changedMind && !nextTask) {
            boolean waypointBehind = !Double.isNaN(e.waypointAngle) && e.waypointAngle > BACK_ANGLE;
            boolean legForward = !Double.isNaN(e.legAngle) && e.legAngle < 70.0;
            if (waypointBehind && legForward) out.add(Cause.MISSED_WAYPOINT);
            if (!Double.isNaN(e.legAngle) && e.legAngle > BACK_ANGLE) out.add(Cause.ROUTE_TURNS);
            // A step-by-step route turning a right angle: the villager slides
            // past the corner tile, so the next tile lies behind-sideways.
            boolean rightAngle = !Double.isNaN(e.legAngle) && e.legAngle >= 60.0 && e.legAngle <= BACK_ANGLE;
            boolean pastCorner = !Double.isNaN(e.waypointAngle) && e.waypointAngle > 95.0;
            if ("direct".equals(e.mode) && !door && !e.ladder && rightAngle && pastCorner) {
                out.add(Cause.GRID_CORNER);
            }
        }
        if (door) out.add(Cause.DOOR);
        if (stuck) out.add(Cause.STUCK);
        if (teleport) out.add(Cause.TELEPORT);
        if (water) out.add(Cause.WATER);
        if (e.ladder) out.add(e.ladderOnRung ? Cause.LADDER_FLIP : Cause.LADDER_EXIT);
        if (out.isEmpty()) out.add(Cause.UNKNOWN);
        return out;
    }

    /**
     * The events around the turn, nearest it: the ones just before it are the
     * likely cause, so events well after it (waypoints reached on the way on)
     * are left out rather than crowding those out of the line.
     */
    private String recentEvents(long start, long now) {
        List<String> shown = new ArrayList<>();
        Iterator<Event> it = events.descendingIterator();
        while (it.hasNext() && shown.size() < RECENT_EVENTS_SHOWN) {
            Event ev = it.next();
            if (ev.tick() < start - CAUSE_WINDOW) break;
            if (ev.tick() > Math.min(now, start + EVENTS_AFTER_SHOWN)) continue;
            long ago = start - ev.tick();
            String when = ago >= 0 ? ago + "t before" : -ago + "t after";
            shown.add(ev.kind().label + ev.detail() + " (" + when + ")");
        }
        if (shown.isEmpty()) return "nothing";
        StringJoiner joined = new StringJoiner("; ");
        for (int i = shown.size() - 1; i >= 0; i--) joined.add(shown.get(i));
        return joined.toString();
    }

    private static void summarise(long now) {
        if (nextSummaryAt == Long.MIN_VALUE) {
            nextSummaryAt = now + SUMMARY_TICKS;
            return;
        }
        if (now < nextSummaryAt) return;
        nextSummaryAt = now + SUMMARY_TICKS;
        int walkBacks = total(WALK_BACKS);
        int spins = total(SPINS);
        if (walkBacks > 0 || spins > 0) {
            LOGGER.info("[WalkBack] last 60 s: {} walk-backs ({}), {} spins ({}), {} turned for good | rounds on: {}",
                    walkBacks, breakdown(WALK_BACKS), spins, breakdown(SPINS), turnedAway,
                    WalkFeel.roundsOn());
        }
        WALK_BACKS.clear();
        SPINS.clear();
        SPIN_LINES.clear();
        turnedAway = 0;
    }

    private static int total(Map<Cause, Integer> counts) {
        int sum = 0;
        for (int n : counts.values()) sum += n;
        return sum;
    }

    private static String breakdown(Map<Cause, Integer> counts) {
        if (counts.isEmpty()) return "none";
        StringJoiner out = new StringJoiner(", ");
        counts.forEach((cause, n) -> out.add(cause.label + " " + n));
        return out.toString();
    }

    private static String activityName(@Nullable Activity activity) {
        return activity == null ? "nothing" : activity.name();
    }

    private static float yawOf(double dx, double dz) {
        return (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /** Degrees between two directions across the ground, 0 to 180. */
    private static double angle(double ax, double az, double bx, double bz) {
        double la = Math.sqrt(ax * ax + az * az);
        double lb = Math.sqrt(bx * bx + bz * bz);
        if (la < 1.0E-9 || lb < 1.0E-9) return Double.NaN;
        double cos = Mth.clamp((ax * bx + az * bz) / (la * lb), -1.0, 1.0);
        return Math.toDegrees(Math.acos(cos));
    }

    private static String degrees(double angle) {
        return Double.isNaN(angle) ? "?" : Math.round(angle) + " deg";
    }
}
