package com.alex.test.village.stuckwatch;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.FollowWalk;
import com.alex.test.village.MoveToCommand;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillagerModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Stuck catcher round 2: one villager's flight recorder. While
 * {@link StuckWatch} is on and the villager is near a player, every tick keeps
 * one {@link Frame}: where the body ended up after Minecraft moved it, then
 * what the walker decided that tick. The last {@value #FRAMES} ticks are kept
 * (15 s), and once a second a {@link RestorePoint} the rewind can put the
 * villager back to. Reads only; nothing here steers.
 *
 * <p>One per {@link VillagerModel}; nothing is allocated until it first records.
 */
public final class BlackBox {

    public static final int FRAMES = 300;
    private static final int RESTORE_EVERY = 20;
    private static final int RESTORE_POINTS = 20;
    private static final int RANGE_CHECK_EVERY = 20;
    private static final int ROUTES_KEPT = 24;
    private static final int DOOR_SCAN_XZ = 4;
    public static final long NONE = Long.MIN_VALUE;

    // Body flags.
    public static final int GROUND = 1;
    public static final int PRESSED = 2;
    public static final int HEAD_BUMP = 4;
    public static final int WATER = 8;
    public static final int JUMPED = 16;
    public static final int JUMP_REFUSED = 32;
    public static final int WALKING = 64;
    public static final int AWAITING_PATH = 128;
    public static final int AIR_GAP = 256;
    public static final int THREADING_DOOR = 512;

    // What the walker did this tick, beyond its steering branch (MoveToCommand sets them).
    public static final int SF_ISSUED = 1;
    public static final int SF_STALL = 2;
    public static final int SF_LIP = 4;
    public static final int SF_HOP_PUSH = 8;
    public static final int SF_OWN_HOP = 16;

    // Steering, for the ghost's colours.
    public static final int STEER_IDLE = 0;
    public static final int STEER_NAV = 1;
    public static final int STEER_STRAIGHT = 2;
    public static final int STEER_DOOR = 3;
    public static final int STEER_LADDER = 4;
    public static final int STEER_HOP = 5;
    public static final int STEER_AIR = 6;
    public static final int STEER_WAITING = 7;
    public static final int STEER_FOLLOW = 8;

    /** One tick of one villager. */
    public static final class Frame {
        public long tick;
        public double x, y, z;
        public float yaw, bodyYaw;
        public double vx, vy, vz;
        public int flags;
        public String anim = "";
        public String job = "";
        // The walker's turn (MoveToCommand.describeTo fills most of these).
        @Nullable public List<BlockPos> route;
        public int routeSeq = -1;
        public int wp = -1;
        public int wpCount;
        public long wpPos = NONE;
        public double wpAway = Double.NaN;
        public double wpRise = Double.NaN;
        public String steer = "";
        public int steerFlags;
        public String lastSteer = "";
        public int still, stillOf, under, underOf, noCloser, noCloserOf;
        public int hops;
        public String hopResult = "";
        // The route follower (FollowWalk) only; the still/under/no-closer counters stay 0 for it.
        public boolean follow;
        public double fProgress, fRemaining, fOffLine, fToRiser, fNoGain;
        public String fNext = "";
        public String fStep = "";
        public int fBlocked;
        public long navNext = NONE;
        public int navIndex, navCount;
        public long navEnd = NONE;
        public boolean hasWanted;
        public double wantX, wantY, wantZ, wantSpeed;
        public boolean moved;
        public double mvWantX, mvWantY, mvWantZ, mvGotX, mvGotY, mvGotZ;
        public long blockedPos = NONE;
        public String blockedBy = "";
        @Nullable public List<String> events;
        public int steerCode;

        void clear() {
            tick = 0L;
            x = y = z = 0.0;
            yaw = bodyYaw = 0.0F;
            vx = vy = vz = 0.0;
            flags = 0;
            anim = "";
            job = "";
            route = null;
            routeSeq = -1;
            wp = -1;
            wpCount = 0;
            wpPos = NONE;
            wpAway = wpRise = Double.NaN;
            steer = "";
            steerFlags = 0;
            lastSteer = "";
            still = stillOf = under = underOf = noCloser = noCloserOf = 0;
            hops = 0;
            hopResult = "";
            follow = false;
            fProgress = fRemaining = fOffLine = fToRiser = fNoGain = 0.0;
            fNext = "";
            fStep = "";
            fBlocked = 0;
            navNext = NONE;
            navIndex = navCount = 0;
            navEnd = NONE;
            hasWanted = false;
            wantX = wantY = wantZ = wantSpeed = 0.0;
            moved = false;
            mvWantX = mvWantY = mvWantZ = mvGotX = mvGotY = mvGotZ = 0.0;
            blockedPos = NONE;
            blockedBy = "";
            events = null;
            steerCode = STEER_IDLE;
        }

        Frame copy() {
            Frame c = new Frame();
            c.tick = tick;
            c.x = x; c.y = y; c.z = z;
            c.yaw = yaw; c.bodyYaw = bodyYaw;
            c.vx = vx; c.vy = vy; c.vz = vz;
            c.flags = flags;
            c.anim = anim;
            c.job = job;
            c.route = route;
            c.routeSeq = routeSeq;
            c.wp = wp; c.wpCount = wpCount; c.wpPos = wpPos;
            c.wpAway = wpAway; c.wpRise = wpRise;
            c.steer = steer; c.steerFlags = steerFlags; c.lastSteer = lastSteer;
            c.still = still; c.stillOf = stillOf; c.under = under; c.underOf = underOf;
            c.noCloser = noCloser; c.noCloserOf = noCloserOf;
            c.hops = hops; c.hopResult = hopResult;
            c.follow = follow;
            c.fProgress = fProgress; c.fRemaining = fRemaining; c.fOffLine = fOffLine;
            c.fToRiser = fToRiser; c.fNoGain = fNoGain; c.fNext = fNext; c.fBlocked = fBlocked; c.fStep = fStep;
            c.navNext = navNext; c.navIndex = navIndex; c.navCount = navCount; c.navEnd = navEnd;
            c.hasWanted = hasWanted;
            c.wantX = wantX; c.wantY = wantY; c.wantZ = wantZ; c.wantSpeed = wantSpeed;
            c.moved = moved;
            c.mvWantX = mvWantX; c.mvWantY = mvWantY; c.mvWantZ = mvWantZ;
            c.mvGotX = mvGotX; c.mvGotY = mvGotY; c.mvGotZ = mvGotZ;
            c.blockedPos = blockedPos; c.blockedBy = blockedBy;
            c.events = events == null ? null : List.copyOf(events);
            c.steerCode = steerCode;
            return c;
        }

        public boolean has(int flag) { return (flags & flag) != 0; }

        /** How the walker steered, in words, with what else it did this tick. */
        public String steerText() {
            if (!has(WALKING)) return has(AWAITING_PATH) ? "waiting for its route" : "not walking";
            StringBuilder s = new StringBuilder(switch (steer) {
                case "nav" -> "Minecraft path";
                case "direct" -> "straight (step-by-step route)";
                case "nav-refused->direct" -> "straight (Minecraft refused)";
                case "door" -> "lining up for a door";
                case "ladder" -> "ladder";
                case "follow" -> "route follower (along its line)";
                case "join" -> "walking onto its route (straight at its first tile)";
                default -> steer;
            });
            if (has(AIR_GAP)) s.append(", feet off the ground");
            if ((steerFlags & SF_ISSUED) != 0) s.append(", stretch asked");
            if ((steerFlags & SF_STALL) != 0) s.append(" (Minecraft had stopped)");
            if ((steerFlags & SF_LIP) != 0) s.append(", lip step");
            if ((steerFlags & SF_OWN_HOP) != 0) s.append(", walker hopped");
            if ((steerFlags & SF_HOP_PUSH) != 0) s.append(", hop push");
            return s.toString();
        }

        public String bodyText() {
            StringBuilder s = new StringBuilder(has(GROUND) ? "on ground" : "in air");
            if (has(PRESSED)) s.append(", pressed");
            if (has(HEAD_BUMP)) s.append(", head bumped");
            if (has(WATER)) s.append(", in water");
            if (has(JUMPED)) s.append(", jumped");
            if (has(JUMP_REFUSED)) s.append(", jump refused (stair assist)");
            return s.toString();
        }

        /** The lines the ghost viewer's panel shows for this tick. */
        public String panelText() {
            StringBuilder s = new StringBuilder(400);
            s.append(String.format(Locale.ROOT, "at %.2f %.2f %.2f  yaw %.0f  %s", x, y, z, yaw, bodyText()));
            s.append('\n').append(String.format(Locale.ROOT, "motion x%+.2f y%+.2f z%+.2f", vx, vy, vz));
            if (moved) {
                s.append(String.format(Locale.ROOT, "   move asked x%+.2f z%+.2f, got x%+.2f z%+.2f",
                        mvWantX, mvWantZ, mvGotX, mvGotZ));
            }
            if (!blockedBy.isEmpty()) s.append('\n').append("stopped by ").append(blockedBy);
            s.append('\n').append("job ").append(job.isEmpty() ? "none" : job).append("   pose ").append(anim);
            if (has(WALKING)) {
                s.append('\n').append(String.format(Locale.ROOT, "waypoint %d/%d %s  %.2f away, rise %.2f",
                        wp + 1, wpCount, fmt(wpPos), wpAway, wpRise));
            }
            s.append('\n').append("steering: ").append(steerText());
            if (has(WALKING) && !lastSteer.isEmpty()) s.append('\n').append("last choice: ").append(lastSteer);
            if (navNext != NONE) {
                s.append('\n').append(String.format(Locale.ROOT, "Minecraft next %s (%d/%d), ends %s",
                        fmt(navNext), navIndex + 1, navCount, fmt(navEnd)));
            }
            if (hasWanted) {
                s.append('\n').append(String.format(Locale.ROOT, "move control to %.2f %.2f %.2f, speed %.2f",
                        wantX, wantY, wantZ, wantSpeed));
            }
            if (has(WALKING) && follow) {
                s.append('\n').append(String.format(Locale.ROOT,
                        "follower: %s now; %.2f along, %.2f left, %.2f off the line; next step %s%s",
                        fStep, fProgress, fRemaining, fOffLine, fNext,
                        Double.isNaN(fToRiser) ? "" : String.format(Locale.ROOT, " (%.2f to its edge)", fToRiser)));
                s.append('\n').append(String.format(Locale.ROOT,
                        "follower rules: no gain %.1f s (limit 2, 5 by a jump), blocked %d/10 t; hops %d (%s)",
                        fNoGain, fBlocked, hops, hopResult));
            } else if (has(WALKING)) {
                s.append('\n').append(String.format(Locale.ROOT,
                        "stuck rules: still %d/%d s, under %d/%d t, no closer %d/%d s; hops %d (%s)",
                        still, stillOf, under, underOf, noCloser, noCloserOf, hops, hopResult));
            }
            if (events != null && !events.isEmpty()) s.append('\n').append("events: ").append(String.join("; ", events));
            return s.toString();
        }

        /** One line of the report's tick table. */
        public String tableRow(long catchTick) {
            StringBuilder s = new StringBuilder(260);
            s.append(String.format(Locale.ROOT, "t%-5d %8.2f %7.2f %8.2f %4.0f  %+.2f %+.2f %+.2f  %s%s%s%s%s  ",
                    tick - catchTick, x, y, z, yaw, vx, vy, vz,
                    has(GROUND) ? "G" : ".", has(PRESSED) ? "P" : ".", has(JUMPED) ? "J" : has(JUMP_REFUSED) ? "j" : ".",
                    has(WATER) ? "W" : ".", has(AIR_GAP) ? "A" : "."));
            if (has(WALKING)) {
                s.append(String.format(Locale.ROOT, "wp %d/%d %.2f r%+.2f  ", wp + 1, wpCount, wpAway, wpRise));
            } else {
                s.append(has(AWAITING_PATH) ? "awaiting route        " : "not walking           ");
            }
            s.append(steerShort());
            if (moved) {
                s.append(String.format(Locale.ROOT, "  ask %+.2f %+.2f got %+.2f %+.2f", mvWantX, mvWantZ, mvGotX, mvGotZ));
            }
            if (!blockedBy.isEmpty()) s.append("  stop: ").append(blockedBy);
            if (has(WALKING) && follow) {
                s.append(String.format(Locale.ROOT, "  %s along %.2f off %.2f next %s nogain %.1f blk %d",
                        fStep, fProgress, fOffLine, fNext, fNoGain, fBlocked));
            } else if (has(WALKING)) {
                s.append(String.format(Locale.ROOT, "  still %d under %d nocl %d", still, under, noCloser));
            }
            if (navNext != NONE) s.append("  mc ").append(fmt(navNext));
            if (events != null && !events.isEmpty()) {
                s.append("\n        > ").append(String.join("\n        > ", events));
            }
            return s.toString();
        }

        private String steerShort() {
            if (!has(WALKING)) return "-";
            String base = switch (steer) {
                case "nav" -> "nav";
                case "direct" -> "straight";
                case "nav-refused->direct" -> "straight*";
                case "door" -> "door";
                case "ladder" -> "ladder";
                default -> steer;
            };
            StringBuilder s = new StringBuilder(base);
            if ((steerFlags & SF_ISSUED) != 0) s.append(" asked");
            if ((steerFlags & SF_STALL) != 0) s.append("(stall)");
            if ((steerFlags & SF_LIP) != 0) s.append(" lip");
            if ((steerFlags & SF_OWN_HOP) != 0) s.append(" hop");
            if ((steerFlags & SF_HOP_PUSH) != 0) s.append(" push");
            return s.toString();
        }
    }

    public record Route(int seq, List<BlockPos> waypoints) {}

    public record DoorState(BlockPos pos, boolean open) {}

    /** What the rewind needs to put a walking villager back as it was. */
    public record RestorePoint(long tick, double x, double y, double z, float yRot, float bodyRot, float headRot,
                               double vx, double vy, double vz, List<BlockPos> waypoints, int wpIndex,
                               BlockPos goal, float speed, boolean smoothed, int repaths, int recoveries,
                               List<DoorState> doors, String job, boolean follow, Set<Long> excluded) {}

    @Nullable private Frame[] ring;
    private int newest = -1;
    private int size;
    private final ArrayDeque<Route> routes = new ArrayDeque<>();
    @Nullable private List<BlockPos> lastRouteList;
    private int routeSeqCounter;
    private final ArrayDeque<RestorePoint> restorePoints = new ArrayDeque<>();
    private long generation = -1L;
    private boolean inRange;
    private int rangeCheckIn;
    @Nullable private Frame open;
    @Nullable private StuckWatch.PendingCatch pendingCatch;

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    /** Start of the villager's tick: where Minecraft's move left the body. */
    public void begin(VillagerModel model, NpcEntity npc) {
        if (generation != StuckWatch.generation()) {
            generation = StuckWatch.generation();
            clear();
        }
        if (--rangeCheckIn <= 0) {
            rangeCheckIn = RANGE_CHECK_EVERY;
            inRange = StuckWatch.wantsRecording(model, npc);
        }
        open = null;
        pendingCatch = null;
        if (!inRange) return;
        long now = npc.level().getGameTime();
        // A break in the recording (out of range, unloaded): start afresh, so the
        // ghost and the rewind never join old moments to new ones.
        if (size > 0 && ring[newest].tick != now - 1) {
            clear();
            rangeCheckIn = RANGE_CHECK_EVERY;
        }
        if (ring == null) {
            ring = new Frame[FRAMES];
            for (int i = 0; i < FRAMES; i++) ring[i] = new Frame();
        }
        newest = (newest + 1) % FRAMES;
        if (size < FRAMES) size++;
        Frame f = ring[newest];
        f.clear();
        f.tick = now;
        f.x = npc.getX();
        f.y = npc.getY();
        f.z = npc.getZ();
        f.yaw = npc.getYRot();
        f.bodyYaw = npc.yBodyRot;
        Vec3 motion = npc.getDeltaMovement();
        f.vx = motion.x;
        f.vy = motion.y;
        f.vz = motion.z;
        if (npc.onGround()) f.flags |= GROUND;
        if (npc.horizontalCollision) f.flags |= PRESSED;
        if (npc.verticalCollision && !npc.onGround()) f.flags |= HEAD_BUMP;
        if (npc.isInWaterOrBubble()) f.flags |= WATER;
        MoveProbe probe = npc.moveProbe();
        if (probe.moveTick == now) {
            f.moved = true;
            f.mvWantX = probe.wantX;
            f.mvWantY = probe.wantY;
            f.mvWantZ = probe.wantZ;
            f.mvGotX = probe.gotX;
            f.mvGotY = probe.gotY;
            f.mvGotZ = probe.gotZ;
            if (probe.blockedAt != null) {
                f.blockedPos = probe.blockedAt.asLong();
                f.blockedBy = probe.blockedBy;
            }
        }
        if (probe.jumpTick == now) f.flags |= JUMPED;
        if (probe.jumpRefusedTick == now) f.flags |= JUMP_REFUSED;
        open = f;
    }

    /** End of the villager's tick: what the walker decided. */
    public void end(VillagerModel model, NpcEntity npc, @Nullable NavGraph graph) {
        Frame f = open;
        open = null;
        if (f == null) return;
        f.anim = npc.getAnimation().name().toLowerCase(Locale.ROOT);
        var activity = model.brain().currentActivity();
        f.job = activity == null ? "" : activity.name();
        MoveToCommand move = model.activeMove();
        if (move != null) {
            f.flags |= WALKING;
            move.describeTo(f);
            if (move instanceof FollowWalk follower) {
                f.follow = true;
                f.fProgress = follower.followProgress();
                f.fRemaining = follower.followRemaining();
                f.fOffLine = follower.followOffLine();
                f.fNext = follower.followNextStepKind();
                f.fToRiser = follower.followToRiser();
                f.fNoGain = follower.followNoProgressSeconds();
                f.fBlocked = follower.followBlockedTicks();
                f.fStep = follower.followStepNow();
            }
            if (f.route != lastRouteList) {
                lastRouteList = f.route;
                routes.addLast(new Route(++routeSeqCounter, f.route));
                while (routes.size() > ROUTES_KEPT) routes.removeFirst();
            }
            f.routeSeq = routeSeqCounter;
            if (f.wpPos != NONE) {
                BlockPos wp = BlockPos.of(f.wpPos);
                double stand = NavGraph.standY(npc.level(), wp);
                double dx = wp.getX() + 0.5 - f.x, dz = wp.getZ() + 0.5 - f.z;
                f.wpRise = stand - f.y;
                f.wpAway = Math.sqrt(dx * dx + dz * dz);
            }
        } else if (model.isAwaitingPath()) {
            f.flags |= AWAITING_PATH;
        }
        Path path = npc.getNavigation().getPath();
        if (path != null && !path.isDone()) {
            Node next = path.getNextNode();
            f.navNext = BlockPos.asLong(next.x, next.y, next.z);
            f.navIndex = path.getNextNodeIndex();
            f.navCount = path.getNodeCount();
            Node last = path.getEndNode();
            if (last != null) f.navEnd = BlockPos.asLong(last.x, last.y, last.z);
        }
        MoveControl control = npc.getMoveControl();
        f.hasWanted = control.hasWanted();
        f.wantX = control.getWantedX();
        f.wantY = control.getWantedY();
        f.wantZ = control.getWantedZ();
        f.wantSpeed = control.getSpeedModifier();
        f.steerCode = steerCode(f);

        if (move != null && f.tick % RESTORE_EVERY == 0 && npc.level() instanceof ServerLevel level) {
            restorePoints.addLast(new RestorePoint(f.tick, f.x, f.y, f.z, npc.getYRot(), npc.yBodyRot, npc.getYHeadRot(),
                    f.vx, f.vy, f.vz, move.waypoints(), move.currentWaypointIndex(), model.moveGoal(),
                    model.moveSpeed(), model.moveSmoothed(), model.stuckRepaths(), model.stuckRecoveryCount(),
                    doorsAround(level, npc.blockPosition()), f.job, f.follow,
                    Set.copyOf(model.moveExcludedCells())));
            while (restorePoints.size() > RESTORE_POINTS) restorePoints.removeFirst();
        }

        StuckWatch.PendingCatch caught = pendingCatch;
        pendingCatch = null;
        if (caught == null && StuckWatch.earlyOn()) {
            String early = earlyReason(f);
            if (early != null) caught = StuckWatch.pending(model, graph, StuckWatch.Kind.EARLY, early);
        }
        if (caught != null) StuckWatch.finish(model, npc, caught);
    }

    /** A walk event (from the walk-back spy's notes), added to this tick's frame. */
    public void event(String text) {
        if (ring == null || newest < 0 || size == 0) return;
        Frame f = open != null ? open : ring[newest];
        if (f.events == null) f.events = new ArrayList<>(2);
        if (f.events.size() < 12) f.events.add(text.strip());
    }

    /** True while a tick is being recorded, so a catch now has frames to keep. */
    boolean recordingNow() { return open != null; }

    /** Whole seconds of unbroken recording so far (15 when the ring is full). */
    int recordedSeconds() { return size / 20; }

    /** A catch needs the whole 15 s behind it, or the ghost and the rewind have little to show. */
    boolean fullyRecorded() { return size >= FRAMES; }

    /** Hands a catch to the end of this tick, after the walker's turn; the first one in a tick wins. */
    void setPendingCatch(StuckWatch.PendingCatch caught) {
        if (pendingCatch == null) pendingCatch = caught;
    }

    private void clear() {
        newest = -1;
        size = 0;
        routes.clear();
        lastRouteList = null;
        restorePoints.clear();
        rangeCheckIn = 0;
        open = null;
        pendingCatch = null;
    }

    // -------------------------------------------------------------------------
    // Reading back
    // -------------------------------------------------------------------------

    /** The frame {@code ago} ticks before the newest, or null. */
    @Nullable
    Frame frameAgo(int ago) {
        if (ring == null || ago < 0 || ago >= size) return null;
        return ring[Math.floorMod(newest - ago, FRAMES)];
    }

    /** Copies of every kept frame, oldest first. */
    List<Frame> snapshotFrames() {
        List<Frame> out = new ArrayList<>(size);
        for (int ago = size - 1; ago >= 0; ago--) {
            Frame f = frameAgo(ago);
            if (f != null) out.add(f.copy());
        }
        return out;
    }

    List<Route> snapshotRoutes() { return List.copyOf(routes); }

    List<RestorePoint> snapshotRestorePoints() { return List.copyOf(restorePoints); }

    /**
     * Early trigger: the signs of a stuck well before the stuck rules call it.
     * Pressed against a block for a second; or, on one waypoint for two
     * seconds, standing still or getting no closer to it.
     */
    @Nullable
    private String earlyReason(Frame now) {
        if (!now.has(WALKING) || slowOnPurpose(now)) return null;
        boolean pressed = true;
        for (int ago = 0; ago < 20; ago++) {
            Frame f = frameAgo(ago);
            if (f == null || !f.has(WALKING) || !f.has(GROUND) || !f.has(PRESSED) || f.routeSeq != now.routeSeq
                    || Math.hypot(f.mvGotX, f.mvGotZ) > 0.03) {
                pressed = false;
                break;
            }
        }
        if (pressed) {
            return "pressed against a block for 1 s" + (now.blockedBy.isEmpty() ? "" : " (" + now.blockedBy + ")");
        }
        Frame old = frameAgo(40);
        if (old == null) return null;
        for (int ago = 0; ago <= 40; ago++) {
            Frame f = frameAgo(ago);
            if (f == null || !f.has(WALKING) || slowOnPurpose(f) || f.routeSeq != now.routeSeq || f.wp != now.wp) {
                return null;
            }
        }
        double moved = Math.hypot(now.x - old.x, now.z - old.z) + Math.abs(now.y - old.y);
        if (moved < 0.3) return "stood still for 2 s while walking";
        double nowAway = Math.hypot(now.wpAway, now.wpRise);
        double oldAway = Math.hypot(old.wpAway, old.wpRise);
        if (nowAway > oldAway - 0.3) {
            return String.format(Locale.ROOT, "no closer to its waypoint for 2 s (%.1f then, %.1f now)", oldAway, nowAway);
        }
        return null;
    }

    /**
     * Ladders, doors, gates, trapdoors and water: slow or still there by design,
     * so the early trigger leaves them alone. The old walker says so in its
     * steering, the route follower in its step word.
     */
    private static boolean slowOnPurpose(Frame f) {
        if (f.has(WATER)) return true;
        if (f.follow) {
            return switch (f.fStep) {
                case "ladder", "water", "door", "gate", "trapdoor" -> true;
                default -> false;
            };
        }
        return "ladder".equals(f.steer) || "door".equals(f.steer);
    }

    private static int steerCode(Frame f) {
        if (!f.has(WALKING)) return f.has(AWAITING_PATH) ? STEER_WAITING : STEER_IDLE;
        if ((f.steerFlags & (SF_HOP_PUSH | SF_OWN_HOP)) != 0) return STEER_HOP;
        if (f.has(AIR_GAP)) return STEER_AIR;
        if (f.follow) {
            // The follower's own step, in the old walker's colours where there is one.
            return switch (f.fStep) {
                case "ladder" -> STEER_LADDER;
                case "door", "gate", "trapdoor" -> STEER_DOOR;
                case "jump" -> STEER_HOP;
                default -> STEER_FOLLOW;
            };
        }
        return switch (f.steer) {
            case "door" -> STEER_DOOR;
            case "ladder" -> STEER_LADDER;
            case "direct", "nav-refused->direct" -> STEER_STRAIGHT;
            case "follow" -> STEER_FOLLOW;
            default -> STEER_NAV;
        };
    }

    /** Open/shut state of every door, gate and trapdoor near {@code centre}. */
    private static List<DoorState> doorsAround(ServerLevel level, BlockPos centre) {
        List<DoorState> out = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -DOOR_SCAN_XZ; dx <= DOOR_SCAN_XZ; dx++) {
                for (int dz = -DOOR_SCAN_XZ; dz <= DOOR_SCAN_XZ; dz++) {
                    pos.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    BlockState state = level.getBlockState(pos);
                    boolean door = state.getBlock() instanceof DoorBlock
                            && state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER;
                    if ((door || state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof TrapDoorBlock)
                            && state.hasProperty(BlockStateProperties.OPEN)) {
                        out.add(new DoorState(pos.immutable(), state.getValue(BlockStateProperties.OPEN)));
                    }
                }
            }
        }
        return out;
    }

    static String fmt(long pos) {
        if (pos == NONE) return "-";
        BlockPos p = BlockPos.of(pos);
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
