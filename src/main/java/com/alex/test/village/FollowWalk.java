package com.alex.test.village;

import net.minecraft.world.entity.PathfinderMob;
import com.alex.test.village.follow.LinePursuit;
import com.alex.test.village.follow.OpenArea;
import com.alex.test.village.follow.RouteLine;
import com.alex.test.village.follow.RouteTile;
import com.alex.test.village.follow.StepKind;
import com.alex.test.village.follow.StepRules;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * The route follower (2026-10-18): one route, one walker. Walks the map's own tile route itself;
 * Minecraft's pathfinder is never asked. It is the only walker for villagers and travellers: the
 * old one, which handed each stretch to Minecraft, was removed on 2026-10-18.
 *
 * <p>The tile route is straightened once with the body's real width ({@link RouteLine}), and each
 * tick the villager aims a block ahead along it ({@link LinePursuit}); progress along the line
 * never goes back, so there is no point to walk past and turn round for. Minecraft's move control
 * only steers and pushes: the wanted height is the villager's own, so it never jumps by itself,
 * and jumps happen only at the steps {@link FollowRoute} judged to need one, timed by how fast the
 * body is going, with a push in the air.
 *
 * <p>The route is cut into sections:
 * <ul>
 *   <li>ground, walked along the line. Doors, fence gates and open trapdoors on it are lined up
 *   for and walked through the middle of their gap, and doors are opened ahead and shut behind by
 *   the walk's own door handler;</li>
 *   <li>ladders: each run of rungs, with the floor tile either side, is climbed by a
 *   {@link LadderLeg} (the tested ladder code: climbs, crossings, getting on and off); the line
 *   takes over again on the floor after it.</li>
 * </ul>
 * A villager standing on none of the route's first tiles, with no clear way onto one, first
 * walks straight to the first tile. A route cut short ({@link FollowRoute.Result#cut}) is walked
 * as far as it goes; there a step no body can take is marked failed in the map and reported as a
 * stuck, so the re-plan goes round, and a very long route asks for the rest to be planned
 * ({@link Status#REPLAN}).
 *
 * <p>A {@code [Follow]} line each minute counts walks, cuts, stucks, hops, ladders, doors and how
 * long routes took to read. A {@code [FollowPace]} line times walking on level ground, half-steps,
 * full-block climbs, drops and ladders (climb-cost step 0).
 */
public final class FollowWalk extends MoveToCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Jumps

    /** Walking, front of the body this close to a jump's riser (middle 0.45 from it): jump. */
    private static final double JUMP_AT = 0.45;
    /** Pressed against something with a jump's riser this close: jump as well. */
    private static final double PRESSED_JUMP_AT = 0.8;
    /** Blocks a tick: faster than any walk (2.7 blocks a second), the pace of a run. Matches RunningHop. */
    private static final double RUN_SPEED = 0.15;
    /**
     * At a run the jump starts when the riser is this many ticks of travel ahead of the body's
     * front, as {@code RunningHop} does: the feet are above a block from the 3rd tick of a jump
     * to the 8th, so the body crosses the edge early in that window instead of hitting its face.
     */
    private static final double JUMP_LEAD_TICKS = 4.0;
    /** The earliest a jump is ever started, body middle to riser. */
    private static final double JUMP_AT_MOST = 1.5;
    /** Rising faster than this the tick after leaving the ground is a jump, not a walk off an edge. */
    private static final double TAKEOFF_RISE = 0.2;
    /** A jump nobody here asked for still counts as the step's when it starts this much further back. */
    private static final double TAKEOFF_SLACK = 0.5;
    /** Upward speed under this after asking for a jump: the jump was refused (stair assist). */
    private static final double JUMP_REFUSED_RISE = 0.1;
    /** Horizontal speed toward the tile above, each airborne tick of a planned jump... */
    private static final double HOP_PUSH = 0.12;
    /** ...or the body's own pace when faster, up to this, so a running jump keeps its speed. */
    private static final double HOP_PUSH_MOST = 0.3;
    private static final int    HOP_PUSH_TICKS = 14;
    private static final int    HOP_COOLDOWN_TICKS = 6;
    /** Planned jumps at one step before the stuck rule may fire. */
    private static final int    MAX_HOPS = 3;
    /** Landed this close under the tile's floor counts as up. */
    private static final double HOP_CLEARED_SLACK = 0.1;
    /**
     * Climb-cost step 1 ({@link WalkFeel#vanillaClimb}): a walking jump starts where Minecraft's
     * move control starts one, the body's middle within a block of the next tile's middle,
     * which for a full block is half a block before the riser.
     */
    private static final double VANILLA_JUMP_AT = 0.5;
    /** Ticks the stair assist is asked for at a time (it stops vanilla's hop on stair shapes). */
    private static final int    STAIR_ASSIST_TICKS = 2;

    // Step timing (climb-cost step 0, 10-18): the [FollowPace] minute line, walking villagers only

    /** A jump or drop is timed over this much line either side of its riser: two blocks in all. */
    private static final double PACE_WINDOW = 1.0;
    /** Only a step first seen this close past its window's start is timed (a walk may start inside one). */
    private static final double PACE_ENTRY = 0.35;
    /** Steps closer together than this are not timed: one would slow the other's stretch. */
    private static final double PACE_APART = 2.5;
    /** Level ground this close after a riser is not flat walking yet: the body is still picking up pace. */
    private static final double PACE_AFTER_STEP = 1.5;
    /** A walk's first ticks are left out: it is still easing up to pace. */
    private static final int    PACE_SETTLE_TICKS = 20;
    /** A timed stretch taking longer than this (a wait, a crowd) is dropped. */
    private static final int    PACE_MOST_TICKS = 100;

    // Stuck rules

    /** Under this much progress along the line... */
    private static final double MIN_GAIN = 0.3;
    /**
     * ...in this many ticks is stuck. Three seconds: every walk comes here now, crowds push,
     * and a re-plan is cheap compared with standing pressed against a neighbour for five.
     */
    private static final int    NO_GAIN_TICKS = 60;
    /** ...or, while still trying a jump, in this many. */
    private static final int    NO_GAIN_TICKS_MOST = 100;
    /** Stuck within this of the end, on the end tile's floor: the walk is done (someone stands there)... */
    private static final double DONE_NEAR = 1.0;
    /** ...or within this on a step-by-step route, the old walker's arrival distance for those. */
    private static final double DONE_NEAR_DETAILED = 0.85;
    /** ...after this many ticks without gaining {@link #MIN_GAIN}. */
    private static final int    DONE_NEAR_TICKS = 10;
    /** Further than this from the line... */
    private static final double OFF_LINE = 1.5;
    /** ...or this far below the floor it should be on... */
    private static final double FELL_BELOW = 1.2;
    /** ...for this many ticks is off the route. */
    private static final int    OFF_LINE_TICKS = 10;
    /** Pressed against something with the body's box this far ahead blocked... */
    private static final double PROBE_AHEAD = 0.4;
    /** ...this many ticks in a row is blocked ahead. */
    private static final int    BLOCKED_TICKS = 10;
    /** A jump's riser closer than this: pressing against it is the step, not a block in the way. */
    private static final double STEP_NEAR = 1.0;
    /** How long routes avoid a step a block was found in (step 3), as long as a failed jump. */
    private static final long BUMP_STEP_MILLIS = 10L * 60L * 1000L;
    /** The village re-reads one bumped block at most this often. */
    private static final long RESCAN_EVERY_TICKS = 200L;
    private static final double ARRIVE_Y = 0.85;

    /** Room kept beside the body on a corner's curve (step 5); the straightened line keeps 0.1. */
    private static final double ROUND_MARGIN = 0.05;

    /** The ladder leg takes over this close to the end of the ground before it. */
    private static final double LADDER_HAND_OVER = 0.3;

    // Walking onto the route from off it

    /** The line takes over this close to the first tile's middle. */
    private static final double JOIN_DONE = 0.35;
    /** A rise onto the first tile above a walking step and up to a jump is jumped... */
    private static final double JOIN_JUMP_MOST = StepRules.JUMP_UP + 0.1;
    /** ...when pressed against it this close: half the body, half the tile and a little slack. */
    private static final double JOIN_JUMP_SLACK = 0.15;

    // Doors, gates and open trapdoors: the old walker's door threading numbers.

    /** Lining up starts this far before the gap's middle... */
    private static final double DOOR_APPROACH = 1.8;
    /** ...for a body no further than this to the side of the gap's line. */
    private static final double DOOR_ACROSS = 1.5;
    /** Straight through, the tile is passed with the body's middle this far beyond the gap's middle. */
    private static final double DOOR_PASSED = 0.8;
    /** Turning inside the tile (an open trapdoor's plate ahead), the turn starts this short of its middle. */
    private static final double TURN_PASSED = -0.15;
    /** Closer than this to the gap's middle the body must already be square to the gap... */
    private static final double DOOR_ENTRY = -0.8;
    /** ...within this sideways; otherwise it is held this far in front until it is. */
    private static final double DOOR_ALIGN = 0.15;
    private static final double DOOR_HOLD = -1.0;
    /** The aim runs this far ahead along the gap's line, so the body converges on it. */
    private static final double DOOR_LOOKAHEAD = 0.4;
    /** Lined up for only within this many route tiles ahead of the body, as the door opener... */
    private static final int DOOR_ROUTE_AHEAD = 3;
    /** ...and only with its floor this close to the body's height: this floor, not the one above or below. */
    private static final double DOOR_SAME_FLOOR = 1.5;

    /** Within this of a drop's edge the body is "dropping" for the stuck catcher. */
    private static final double DROP_NEAR = 0.5;
    /** Falling at least this fast (blocks a tick) off the ground is a drop too. */
    private static final double FALLING = -0.1;

    /** Same slow-down over the last blocks as the old walker's round 6. */
    private static final double END_EASE_FROM = 4.0;
    private static final double END_EASE_TO = 1.0;
    private static final float  END_EASE_SLOWEST = 0.55F;

    /** Speed asked for above the shared walk speed (0.55): a hurried walk, for the counts. */
    private static final float  HURRIED_SPEED = 0.6F;

    private static final Predicate<BlockPos> NO_DOORS = pos -> false;

    /** A stretch of the route walked one way: along the line, or by a ladder leg. */
    private record Section(boolean ladder, int from, int to) {}

    /** Where this walk is counted: the villagers' {@code [Follow]} line or the raiders' (set by the factories). */
    private Stats stats = Stats.VILLAGERS;
    /** The village map the route came from: a block found in the way is reported to it. */
    private final NavGraph graph;
    private final List<BlockPos> feet;
    private final List<RouteTile> tiles;
    private final boolean[] stair;
    private final byte[] kind;
    private final OpenArea area;
    private final float speed;
    private final boolean detailed;
    /** Route indices of door, gate and open trapdoor tiles, in order. */
    private final int[] narrow;
    /** Route indices of door and gate tiles, in order: the ones the door handler opens. */
    private final int[] doorTiles;
    private final Section[] sections;
    /** Distance from the first tile's middle to each tile's, along the tiles (for the stuck catcher). */
    private final double[] tileArc;
    private final Predicate<BlockPos> routeDoorTest = this::isRouteDoorNear;
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    private int sectionIndex;
    /** The route tile the current line starts at. */
    private int lineFirst;
    @Nullable private RouteLine line;
    @Nullable private LinePursuit pursuit;
    /** Corners the current line's pursuit had curved through when last counted. */
    private int roundedSeen;
    /** The leg climbing the current ladder section, or null on the ground. */
    @Nullable private LadderLeg ladder;
    private int ladderFrom;
    /** The next route tile ahead of the body: the walk's "waypoint" for everything outside. */
    private int ahead;
    /** The door ({@code narrowCursor}) already counted in {@link Stats#doorLaneWaits}, so it counts once. */
    private int laneWaitCounted = -1;

    /** Index into {@link #narrow} of the next door, gate or trapdoor to pass. */
    private int narrowCursor;
    /** Turning inside that tile: past the turn, lined up with the way out. */
    private boolean narrowExit;
    private boolean threading;
    private double threadAimX;
    private double threadAimZ;
    private String threadWord = "door";
    /** Route index of the door, gate or trapdoor being lined up for, while {@link #threading}. */
    private int threadTile = -1;

    private double lastX;
    private double lastZ;
    private boolean haveLast;
    /** Blocks moved sideways last tick. */
    private double speedNow;
    private boolean wasOnGround = true;
    private double aimX;
    private double aimZ;
    private String stepNow = "walk";

    @Nullable private RouteLine.StepMark hopMark;
    private int    hopsAtMark;
    private int    fellBackAtMark;
    private int    hopPushTicks;
    private int    hopAirTicks;
    private int    hopCooldown;
    private double hopPush = HOP_PUSH;
    /** This hop is a Minecraft climb: no carry through the air (see {@link #walkingClimb}). */
    private boolean hopVanilla;
    @Nullable private RouteTile hopFar;
    private String lastHopResult = "none";

    private double gainBase;
    private int    ticksSinceGain;
    private int    offLineTicks;
    private int    blockedTicks;
    private boolean stuckOffRoute;
    /** The last stuck marked a step failed in the map itself (a block in the route's own column). */
    private boolean stepMarked;
    private String stuckRule = "";
    private String note = "following the line";
    private boolean finished;

    /** Why the route ends before the planner's last tile (FollowRoute.CUT_*), and the step there. */
    private final byte cutKind;
    @Nullable private final String cut;
    @Nullable private final NavNode cutFrom;
    @Nullable private final NavNode cutTo;

    /** Walking straight to the first tile before the line takes over. */
    private boolean joining;
    private double joinBest = Double.POSITIVE_INFINITY;
    private int joinStillTicks;

    // Step timing (see Stats): ticks this walk has run, last tick's progress, the step being timed
    private int walkTicks;
    private double paceProgress = Double.NaN;
    @Nullable private RouteLine.StepMark paceSeen;
    private double paceLastRiser = Double.NEGATIVE_INFINITY;
    @Nullable private RouteLine.StepMark paceMark;
    private int paceTicks;
    private int ladderTicks;

    private FollowWalk(NavGraph graph, FollowRoute.Result route, OpenArea area, float speed, boolean detailed) {
        super(route.feet());
        this.graph = graph;
        this.cutKind = route.cutKind();
        this.cut = route.cut();
        this.cutFrom = route.cutFrom();
        this.cutTo = route.cutTo();
        this.feet = route.feet();
        this.tiles = route.tiles();
        this.stair = route.stair();
        this.kind = route.kind();
        this.area = area;
        this.speed = speed;
        this.detailed = detailed;
        int n = tiles.size();
        int narrowCount = 0, doorCount = 0;
        for (byte k : kind) {
            if (FollowRoute.isNarrow(k)) narrowCount++;
            if (k == FollowRoute.DOOR || k == FollowRoute.GATE) doorCount++;
        }
        this.narrow = new int[narrowCount];
        this.doorTiles = new int[doorCount];
        for (int i = 0, a = 0, b = 0; i < n; i++) {
            if (FollowRoute.isNarrow(kind[i])) narrow[a++] = i;
            if (kind[i] == FollowRoute.DOOR || kind[i] == FollowRoute.GATE) doorTiles[b++] = i;
        }
        this.sections = sectionsOf(kind);
        this.tileArc = new double[n];
        for (int i = 1; i < n; i++) {
            RouteTile p = tiles.get(i - 1), t = tiles.get(i);
            double dx = t.x() - p.x(), dz = t.z() - p.z(), dy = t.stand() - p.stand();
            tileArc[i] = tileArc[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        startFrom(route.start());
        // On a ladder section the leg already steers straight to its first tile.
        joining = route.joinFar() && !sections[sectionIndex].ladder();
        if (joining) note = "walking onto its route";
    }

    /**
     * Cuts the route into ground and ladder sections. A ladder section is a run of rungs with
     * the floor tile before and after it; the ground sections between share those floor tiles.
     */
    private static Section[] sectionsOf(byte[] kind) {
        int n = kind.length;
        List<Section> out = new ArrayList<>();
        int lineStart = 0;
        int i = 0;
        while (i < n) {
            if (kind[i] != FollowRoute.LADDER) {
                i++;
                continue;
            }
            int a = i;
            while (i < n && kind[i] == FollowRoute.LADDER) i++;
            int from = Math.max(0, a - 1), to = Math.min(n - 1, i);
            if (from > lineStart) out.add(new Section(false, lineStart, from));
            out.add(new Section(true, from, to));
            lineStart = to;
            if (to == i) i++;
        }
        if (lineStart < n - 1 || out.isEmpty()) out.add(new Section(false, lineStart, n - 1));
        return out.toArray(new Section[0]);
    }

    /**
     * The walk for a villager's route just planned; null only when there is nothing to walk (no
     * map or body, or a route with no tiles, counted by reason in the {@code [Follow]} line).
     *
     * @param smoothed false for a step-by-step route; after a stuck it is kept to its own tiles
     * @param excluded feet cells the walk must keep out of, as planned with
     */
    @Nullable
    static FollowWalk forRoute(VillagerModel model, @Nullable NavGraph graph, @Nullable PathfinderMob npc,
                               AStarPathfinder.Route route, float speed, boolean smoothed, Set<Long> excluded) {
        return forRoute(graph, npc, route, speed, smoothed, model.stuckRepaths(), excluded);
    }

    /**
     * The walk for a route with no {@link VillagerModel} of its own (a traveller's approach,
     * {@code TravelerApproach}), by the same rules as the villagers' walks.
     *
     * @param stuckRepaths stuck re-plans so far; with {@code smoothed} false, more than none keeps
     *                     the line to the route's own tiles
     */
    @Nullable
    public static FollowWalk forRoute(@Nullable NavGraph graph, @Nullable PathfinderMob npc, AStarPathfinder.Route route,
                                      float speed, boolean smoothed, int stuckRepaths, Set<Long> excluded) {
        if (graph == null || npc == null) return null;
        if (!(npc.level() instanceof ServerLevel level)) return null;
        Stats.flush(level);
        Stats stats = Stats.of(npc);
        if (route.tiles().isEmpty()) return stats.refuse("no tile list");
        long started = System.nanoTime();
        OpenArea area = FollowRoute.area(graph, excluded, !smoothed && stuckRepaths > 0);
        FollowRoute.Result built = FollowRoute.build(level, npc, route.tiles(), true, area);
        if (built.refused() != null) {
            stats.timed(System.nanoTime() - started);
            return stats.refuse(built.refused());
        }
        FollowWalk walk = new FollowWalk(graph, built, area, speed, !smoothed);
        walk.stats = stats;
        stats.timed(System.nanoTime() - started);
        stats.started++;
        if (WalkBody.isRunning(npc) || speed >= HURRIED_SPEED) stats.running++;
        if (built.cut() != null) stats.cuts.merge(built.cut(), 1, Integer::sum);
        if (walk.joining) stats.joinedFar++;
        return walk;
    }

    /**
     * Stuck catcher rewind: the route follower again for a recorded walk's tiles (its
     * {@link #waypoints()}), as a plain walk; null when the map no longer has them all. Call
     * {@link #startAt} next.
     */
    @Nullable
    public static FollowWalk replay(NavGraph graph, PathfinderMob npc, List<BlockPos> tiles, float speed) {
        return replay(graph, npc, tiles, speed, true, 0, Set.of());
    }

    /**
     * Stuck catcher rewind of a job walk or a re-plan, rebuilt as it was: {@code smoothed} as
     * {@code VillagerModel.moveSmoothed()}, {@code stuckRepaths} as {@code stuckRepaths()} (a
     * step-by-step re-plan after a stuck keeps to its own tiles) and {@code excluded} as
     * {@code moveExcludedCells()} (cells the line must not cut across).
     */
    @Nullable
    public static FollowWalk replay(NavGraph graph, PathfinderMob npc, List<BlockPos> tiles, float speed,
                                    boolean smoothed, int stuckRepaths, Set<Long> excluded) {
        if (!(npc.level() instanceof ServerLevel level) || tiles.isEmpty()) return null;
        List<NavNode> nodes = new ArrayList<>(tiles.size());
        for (BlockPos p : tiles) {
            NavNode node = graph.nodeAt(p.getX(), p.getY(), p.getZ());
            if (node == null) return null;
            nodes.add(node);
        }
        OpenArea area = FollowRoute.area(graph, excluded, !smoothed && stuckRepaths > 0);
        FollowRoute.Result built = FollowRoute.build(level, npc, nodes, false, area);
        if (built.refused() != null) return null;
        FollowWalk walk = new FollowWalk(graph, built, area, speed, !smoothed);
        walk.stats = Stats.of(npc);
        return walk;
    }

    /** Starts walking from route tile {@code tile}: the last section holding it, so a shared floor tile goes on. */
    private void startFrom(int tile) {
        int t = Mth.clamp(tile, 0, tiles.size() - 1);
        int s = sections.length - 1;
        while (s > 0 && sections[s].from() > t) s--;
        narrowCursor = 0;
        while (narrowCursor < narrow.length && narrow[narrowCursor] < t) narrowCursor++;
        narrowExit = false;
        startSection(s, t);
    }

    private void startSection(int s, int tile) {
        sectionIndex = s;
        Section sec = sections[s];
        hopMark = null;
        hopsAtMark = 0;
        fellBackAtMark = 0;
        hopPushTicks = 0;
        hopCooldown = 0;
        hopFar = null;
        hopVanilla = false;
        gainBase = 0.0;
        ticksSinceGain = 0;
        offLineTicks = 0;
        blockedTicks = 0;
        threading = false;
        paceProgress = Double.NaN;
        paceSeen = null;
        paceLastRiser = Double.NEGATIVE_INFINITY;
        paceMark = null;
        ladderTicks = 0;
        if (sec.ladder()) {
            line = null;
            pursuit = null;
            ladderFrom = sec.from();
            ladder = new LadderLeg(List.copyOf(feet.subList(sec.from(), sec.to() + 1)), speed);
            ladder.keepOccupiedDoorsWith(doorHandler);
            ladder.startAt(tile - sec.from());
            ahead = tile;
            note = "ladder leg, tiles " + (sec.from() + 1) + "-" + (sec.to() + 1);
        } else {
            ladder = null;
            lineFirst = tile;
            line = RouteLine.build(tiles.subList(tile, sec.to() + 1), area);
            pursuit = new LinePursuit(line, this::roundClear);
            roundedSeen = 0;
            ahead = Math.min(tile + 1, sec.to());
            note = "following the line";
        }
    }

    @Override
    public Status tick(PathfinderMob npc) {
        // A finished walk stays finished if ticked again.
        if (finished) return Status.DONE;
        if (!(npc.level() instanceof ServerLevel level)) return Status.RUNNING;
        Stats.flush(level);
        double x = npc.getX(), z = npc.getZ();
        speedNow = haveLast ? Math.hypot(x - lastX, z - lastZ) : 0.0;
        lastX = x;
        lastZ = z;
        haveLast = true;
        walkTicks++;
        Status status;
        if (joining) {
            status = tickJoin(npc, level);
        } else {
            status = ladder != null ? tickLadder(npc, level) : tickLine(npc, level);
        }
        wasOnGround = npc.onGround();
        return status;
    }

    /**
     * Off the route with no clear way onto its first tiles (fell, pushed, or the map's nearest
     * tile a few blocks off): straight at the first tile's middle, at the body's own height, the
     * door handler and stair assist on as on the line. A rise onto it is jumped when pressed
     * against it, and swum up in water. No closer by {@link #MIN_GAIN} in {@link #NO_GAIN_TICKS}
     * is stuck, off the route, so the re-plan starts from where the villager now stands.
     */
    private Status tickJoin(PathfinderMob npc, ServerLevel level) {
        WalkBody.ladderTarget(npc, null);
        if (npc.getNavigation().isInProgress()) npc.getNavigation().stop();
        doorHandler.tick(npc, doorTiles.length > 0 ? routeDoorTest : NO_DOORS);
        if (hopCooldown > 0) hopCooldown--;
        RouteTile first = tiles.get(0);
        double x = npc.getX(), z = npc.getZ();
        double dist = Math.hypot(first.centreX() - x, first.centreZ() - z);
        if (dist <= JOIN_DONE) {
            joining = false;
            note = "following the line";
            return tickLine(npc, level);
        }
        boolean inWater = npc.isInWaterOrBubble();
        double rise = first.stand() - npc.getY();
        if (inWater) {
            if (rise > -0.1) npc.getJumpControl().jump();
        } else if (hopCooldown == 0 && npc.onGround() && npc.horizontalCollision
                && rise > StepRules.STEP_UP && rise <= JOIN_JUMP_MOST
                && dist <= 0.5 + npc.getBbWidth() * 0.5 + JOIN_JUMP_SLACK) {
            WalkBody.cancelStairAssist(npc);
            npc.jumpFromGround();
            hopCooldown = HOP_COOLDOWN_TICKS;
        } else if (nearStairs(level, npc)) {
            WalkBody.stairAssist(npc, STAIR_ASSIST_TICKS);
        }
        aimX = first.centreX();
        aimZ = first.centreZ();
        npc.getMoveControl().setWantedPosition(aimX, npc.getY(), aimZ, speed);
        stepNow = inWater ? "water" : !npc.onGround() && npc.getDeltaMovement().y < FALLING ? "drop" : "walk";
        note = String.format(Locale.ROOT, "walking onto its route, %.1f blocks from its first tile", dist);

        if (dist <= joinBest - MIN_GAIN) {
            joinBest = dist;
            joinStillTicks = 0;
        } else if (++joinStillTicks >= NO_GAIN_TICKS) {
            return stuck(String.format(Locale.ROOT, "could not get onto its route (%.1f blocks from its first tile %d,%d,%d)",
                    dist, first.x(), feet.get(0).getY(), first.z()), "could not reach its route", false);
        }
        return Status.RUNNING;
    }

    private Status tickLadder(PathfinderMob npc, ServerLevel level) {
        // Doors opened before the ladder still shut behind; the leg opens its own.
        doorHandler.tick(npc, NO_DOORS);
        LadderLeg leg = ladder;
        Status status = leg.tick(npc);
        ahead = Mth.clamp(ladderFrom + leg.currentWaypointIndex(), 0, tiles.size() - 1);
        stepNow = "ladder";
        threading = false;
        ladderTicks++;
        if (status == Status.RUNNING) return status;
        gatherDoors();
        if (status == Status.STUCK) {
            Status stuckStatus = stuck("ladder: " + leg.stuckRule(), "stuck on a ladder leg", false);
            if (WalkFeel.ladderBlame) blameLadderStep(npc, level, leg);
            return stuckStatus;
        }
        // A ladder leg walked to its end: any earlier ladder stuck no longer counts as "in a row".
        LADDER_MISSES.remove(npc);
        stats.ladders++;
        Section sec = sections[sectionIndex];
        if (!WalkBody.isRunning(npc) && speed < HURRIED_SPEED) {
            int rungs = 0;
            for (int i = sec.from(); i <= sec.to(); i++) if (kind[i] == FollowRoute.LADDER) rungs++;
            stats.ladderRungs += rungs;
            stats.ladderTicks += ladderTicks;
        }
        if (sectionIndex == sections.length - 1) {
            ladder = null;
            ahead = tiles.size() - 1;
            return endOfRoute(npc, level, "reached the end (ladder leg)");
        }
        startSection(sectionIndex + 1, sec.to());
        // Two ladders with one floor tile between: the next leg starts next tick.
        return ladder != null ? Status.RUNNING : tickLine(npc, level);
    }

    private Status tickLine(PathfinderMob npc, ServerLevel level) {
        WalkBody.ladderTarget(npc, null);
        if (npc.getNavigation().isInProgress()) npc.getNavigation().stop();
        doorHandler.tick(npc, doorTiles.length > 0 ? routeDoorTest : NO_DOORS);
        if (hopCooldown > 0) hopCooldown--;
        boolean onGround = npc.onGround();
        boolean inWater = npc.isInWaterOrBubble();
        double x = npc.getX(), z = npc.getZ();

        pursuit.update(x, z);
        if (pursuit.rounded() != roundedSeen) {
            stats.rounded += pursuit.rounded() - roundedSeen;
            roundedSeen = pursuit.rounded();
        }
        ahead = tileAhead(x, z);
        boolean lastSection = sectionIndex == sections.length - 1;
        if (!lastSection && hopPushTicks == 0 && (onGround || inWater)
                && (pursuit.done() || pursuit.remaining() <= LADDER_HAND_OVER)) {
            // Only a ladder section follows a ground one.
            startSection(sectionIndex + 1, sections[sectionIndex].to());
            return tickLadder(npc, level);
        }
        if (lastSection && pursuit.done() && onGround && levelWithEnd(npc)) return endOfRoute(npc, level, "reached the end");

        RouteLine.StepMark mark = pursuit.nextStep();
        boolean jumpWanted = false;
        if (hopPushTicks > 0) {
            hopTick(npc);
        } else {
            noticeTakeoff(npc, mark, onGround, inWater);
            if (hopPushTicks == 0 && mark != null && mark.step().kind() == StepKind.JUMP) {
                jumpWanted = jumpIfDue(npc, mark, onGround, inWater);
            }
        }

        boolean hopping = hopPushTicks > 0;
        threading = threadNarrow(level, npc);
        aimX = threading ? threadAimX : pursuit.aimX();
        aimZ = threading ? threadAimZ : pursuit.aimZ();
        if (!hopping && nearStairs(level, npc)) WalkBody.stairAssist(npc, STAIR_ASSIST_TICKS);
        // Own height as the wanted height: Minecraft's move control never jumps by itself.
        npc.getMoveControl().setWantedPosition(aimX, npc.getY(), aimZ, speed);
        if (WalkFeel.ease && !hopping && !threading && lastSection) easeTowardEnd(npc);
        stepNow = stepWord(npc, mark, onGround, inWater, hopping || jumpWanted);
        timePace(npc, mark, onGround, inWater, lastSection);

        return checkStuck(npc, level, mark, onGround, lastSection);
    }

    /**
     * Climb-cost step 0: how long each kind of step takes a walking villager, for the
     * {@code [FollowPace]} line. A jump or drop with no other step near it is timed over the
     * two blocks of line round its riser; level ground and stair or slab half-steps add up the
     * line gained per tick. Starts of walks, ends (the slow-down), doors, water, bumps and the
     * block and a half after a step are left out, so flat is plain walking pace.
     */
    private void timePace(PathfinderMob npc, @Nullable RouteLine.StepMark mark, boolean onGround, boolean inWater,
                          boolean lastSection) {
        double p = pursuit.progress();
        double gain = Double.isNaN(paceProgress) ? 0.0 : p - paceProgress;
        paceProgress = p;
        if (mark != paceSeen) {
            if (paceSeen != null) paceLastRiser = paceSeen.riserArc();
            paceSeen = mark;
        }
        if (WalkBody.isRunning(npc) || speed >= HURRIED_SPEED || inWater || walkTicks < PACE_SETTLE_TICKS) {
            paceMark = null;
            return;
        }
        if (paceMark != null) {
            paceTicks++;
            if (p >= paceMark.riserArc() + PACE_WINDOW) {
                stats.timedStep(paceMark.step().kind(), paceTicks);
                paceMark = null;
            } else if (paceTicks > PACE_MOST_TICKS || threading) {
                paceMark = null;
            }
            return;
        }
        if (mark != null && (mark.step().kind() == StepKind.JUMP || mark.step().kind() == StepKind.DROP)) {
            double start = mark.riserArc() - PACE_WINDOW;
            double after = line.length() - (mark.riserArc() + PACE_WINDOW);
            if (p >= start && p < start + PACE_ENTRY && !threading && stepAlone(mark)
                    && after >= (lastSection ? END_EASE_FROM : RouteLine.HALF_WIDTH)) {
                paceMark = mark;
                paceTicks = 0;
                return;
            }
            if (p >= start - RouteLine.HALF_WIDTH) return;
        }
        if (!onGround || hopPushTicks > 0 || threading || npc.horizontalCollision) return;
        if (lastSection && pursuit.remaining() < END_EASE_FROM) return;
        if (p - paceLastRiser < PACE_AFTER_STEP) return;
        double dy = tiles.get(ahead).stand() - tiles.get(Math.max(lineFirst, ahead - 1)).stand();
        stats.timedGround(dy > RouteLine.FLAT ? 1 : dy < -RouteLine.FLAT ? -1 : 0, gain);
    }

    /** No other jump or drop within {@link #PACE_APART} of this one's riser on the line. */
    private boolean stepAlone(RouteLine.StepMark mark) {
        for (RouteLine.StepMark other : line.steps()) {
            if (other != mark && Math.abs(other.riserArc() - mark.riserArc()) < PACE_APART) return false;
        }
        return true;
    }

    /**
     * Climb-cost step 1 (10-18, {@link WalkFeel#vanillaClimb}): a walking villager climbs a full
     * block the way a Minecraft mob on its own path does. It jumps half a block before the riser,
     * its front meets the block's face while still rising and its pace stops there, it creeps
     * over the edge on Minecraft's small push in the air and lands slowly near the edge, then
     * picks its pace back up: about 0.45 s more than two blocks of level ground at a walk, where
     * the old hop cost nothing. Before, a walk (3.3 blocks a second) counted as a run here
     * ({@link #RUN_SPEED} 3), so walking villagers took the running leap over every block.
     * Runs keep their leap. After a climb that fell back, the next try at that step is carried.
     */
    private boolean walkingClimb(PathfinderMob npc) {
        return WalkFeel.vanillaClimb && !WalkBody.isRunning(npc) && speed < HURRIED_SPEED;
    }

    /** The time to jump, body middle to riser: at a walk close to it, at a run a few ticks of travel ahead. */
    private double jumpAt(PathfinderMob npc) {
        if (walkingClimb(npc)) return VANILLA_JUMP_AT;
        if (speedNow < RUN_SPEED) return JUMP_AT;
        return Mth.clamp(RouteLine.HALF_WIDTH + JUMP_LEAD_TICKS * speedNow, JUMP_AT, JUMP_AT_MOST);
    }

    /**
     * A planned jump: on the ground with the riser {@link #jumpAt} ahead, or pressed against it.
     * In water the jump is held instead (Minecraft swims up, or lifts a body pressed against a
     * ledge out), and not counted as a hop. True when a jump was wanted this tick.
     */
    private boolean jumpIfDue(PathfinderMob npc, RouteLine.StepMark mark, boolean onGround, boolean inWater) {
        if (hopCooldown > 0) return false;
        RouteTile far = tiles.get(lineFirst + mark.tile() + 1);
        if (npc.getY() >= far.stand() - HOP_CLEARED_SLACK) return false;
        double toRiser = pursuit.toRiser(mark);
        double at = jumpAt(npc);
        boolean due = toRiser <= at && toRiser > -RouteLine.HALF_WIDTH;
        boolean pressed = npc.horizontalCollision && toRiser <= Math.max(PRESSED_JUMP_AT, at);
        if (!due && !pressed) return false;
        if (inWater) {
            npc.getJumpControl().jump();
            return true;
        }
        if (!onGround) return false;
        if (mark != hopMark) {
            hopMark = mark;
            hopsAtMark = 0;
            fellBackAtMark = 0;
        }
        if (hopsAtMark >= MAX_HOPS) return false;
        WalkBody.cancelStairAssist(npc);
        npc.jumpFromGround();
        if (npc.getDeltaMovement().y <= JUMP_REFUSED_RISE) {
            lastHopResult = "jump refused";
            hopCooldown = HOP_COOLDOWN_TICKS;
            return false;
        }
        startHop(npc, mark, far, "jumped");
        return true;
    }

    /**
     * A take-off nobody here asked for, close before a jump's riser: Minecraft's own hop on a
     * stair shape, or a jump already under way when this walk took over from the last one. It
     * is carried over the riser like a planned jump, instead of landing back short of it.
     */
    private void noticeTakeoff(PathfinderMob npc, @Nullable RouteLine.StepMark mark, boolean onGround, boolean inWater) {
        if (!wasOnGround || onGround || inWater || mark == null || mark.step().kind() != StepKind.JUMP) return;
        if (npc.getDeltaMovement().y <= TAKEOFF_RISE) return;
        RouteTile far = tiles.get(lineFirst + mark.tile() + 1);
        if (npc.getY() >= far.stand() - HOP_CLEARED_SLACK) return;
        double toRiser = pursuit.toRiser(mark);
        if (toRiser > Math.max(jumpAt(npc), PRESSED_JUMP_AT) + TAKEOFF_SLACK || toRiser <= -RouteLine.HALF_WIDTH) return;
        if (mark != hopMark) {
            hopMark = mark;
            hopsAtMark = 0;
            fellBackAtMark = 0;
        }
        if (hopsAtMark >= MAX_HOPS) return;
        stats.ownTakeoffs++;
        startHop(npc, mark, far, "took off");
    }

    private void startHop(PathfinderMob npc, RouteLine.StepMark mark, RouteTile far, String how) {
        hopsAtMark++;
        stats.hops++;
        hopFar = far;
        hopAirTicks = 0;
        hopPushTicks = HOP_PUSH_TICKS;
        // A Minecraft climb keeps only the move control's own push; a retry after falling back is carried.
        hopVanilla = walkingClimb(npc) && fellBackAtMark == 0;
        hopPush = Mth.clamp(speedNow, HOP_PUSH, HOP_PUSH_MOST);
        note = String.format(Locale.ROOT, "%s %.2f up to %d,%d (hop %d%s)",
                how, far.stand() - npc.getY(), far.x(), far.z(), hopsAtMark, hopVanilla ? ", Minecraft climb" : "");
        if (!hopVanilla) pushToward(npc, far, hopPush);
    }

    /** One airborne tick of a planned jump: carry the body over the riser, then judge the landing. */
    private void hopTick(PathfinderMob npc) {
        RouteTile far = hopFar;
        if (far == null) {
            hopPushTicks = 0;
            return;
        }
        hopAirTicks++;
        if (npc.onGround() && hopAirTicks > 1) {
            boolean cleared = npc.getY() >= far.stand() - HOP_CLEARED_SLACK;
            lastHopResult = cleared ? "cleared" : "fell back";
            if (cleared) {
                stats.cleared++;
            } else {
                stats.fellBack++;
                fellBackAtMark++;
                if (hopVanilla) stats.climbsFellBack++;
            }
            hopPushTicks = 0;
            hopCooldown = cleared ? 0 : HOP_COOLDOWN_TICKS;
            return;
        }
        if (--hopPushTicks == 0) {
            lastHopResult = "no landing";
            return;
        }
        if (!hopVanilla && !npc.onGround() && !npc.isInWaterOrBubble()) pushToward(npc, far, hopPush);
    }

    private static void pushToward(PathfinderMob npc, RouteTile far, double push) {
        double dx = far.centreX() - npc.getX();
        double dz = far.centreZ() - npc.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-3) return;
        double step = Math.min(push, dist);
        Vec3 motion = npc.getDeltaMovement();
        npc.setDeltaMovement(dx / dist * step, motion.y, dz / dist * step);
    }

    /**
     * The old walker's door threading, for every door, fence gate and open trapdoor tile on the
     * line: from {@link #DOOR_APPROACH} before the gap's middle the aim runs along the middle of
     * the gap the open door or trapdoor plate leaves (a gate's is the tile's middle), and a body
     * not yet square to it is held in front until it is: an angled entry is what put villagers
     * into the panel. An open trapdoor whose plate stands across the way is turned at inside the
     * tile, then the way out is lined up the same way. Sets the aim; false when none is near.
     */
    private boolean threadNarrow(ServerLevel level, PathfinderMob npc) {
        int end = sections[sectionIndex].to();
        // Behind the body already (pushed past it to one side, say): not waited for.
        while (narrowCursor < narrow.length
                && (narrow[narrowCursor] < lineFirst || narrow[narrowCursor] < ahead - 1)) {
            narrowCursor++;
            narrowExit = false;
        }
        if (narrowCursor >= narrow.length || narrow[narrowCursor] > end) return false;
        int k = narrow[narrowCursor];
        int n = tiles.size();
        RouteTile t = tiles.get(k);
        // Only one on the body's own floor, a few tiles ahead on the route (the door opener's
        // window). The flat test below alone took a door straight above or below, many tiles
        // later on the route, for the one in front: in a two-storey house it turned a villager
        // back through the door it had just used, and pressed one into a closed door that was
        // not on its route at all (10-18). Checked before "passed", so a door on another floor
        // is never passed from there either.
        if (WalkFeel.doorFloor && (k > ahead + DOOR_ROUTE_AHEAD
                || Math.abs(npc.getY() - t.stand()) > DOOR_SAME_FLOOR)) {
            return false;
        }
        int ux, uz, vx, vz;
        if (k > 0) {
            ux = Integer.signum(t.x() - tiles.get(k - 1).x());
            uz = Integer.signum(t.z() - tiles.get(k - 1).z());
        } else {
            ux = uz = 0;
        }
        if (k + 1 < n) {
            vx = Integer.signum(tiles.get(k + 1).x() - t.x());
            vz = Integer.signum(tiles.get(k + 1).z() - t.z());
        } else {
            vx = ux;
            vz = uz;
        }
        if (ux == 0 && uz == 0) {
            ux = vx;
            uz = vz;
        }
        if (vx == 0 && vz == 0) {
            vx = ux;
            vz = uz;
        }
        if (ux == 0 && uz == 0) return false;
        boolean last = k == n - 1;
        boolean straight = ux == vx && uz == vz;
        double x = npc.getX(), z = npc.getZ();
        threadWord = kind[k] == FollowRoute.GATE ? "gate" : kind[k] == FollowRoute.TRAPDOOR ? "trapdoor" : "door";

        if (!narrowExit) {
            double gap = gapAt(level, k, ux != 0 ? Direction.Axis.X : Direction.Axis.Z);
            double gx = ux != 0 ? t.centreX() : t.x() + gap;
            double gz = ux != 0 ? t.z() + gap : t.centreZ();
            double along = (x - gx) * ux + (z - gz) * uz;
            double across = (z - gz) * ux - (x - gx) * uz;
            double passedAt = last ? Double.POSITIVE_INFINITY : straight ? DOOR_PASSED : TURN_PASSED;
            // D1 (10-18): past the gap's middle counts only in the gap's lane. Along alone took a
            // door as passed for a body still beside the house, south of the door's middle, on a
            // route round to it: a guard then ran into the doorway without lining up and caught
            // the open panel. Out of the lane nothing is aimed; the route-progress skip above still
            // drops a door the body really left behind, and the tile before a door is an anchor
            // the line passes through, so the lane is always met on the way in.
            boolean inLane = !WalkFeel.doorLane || Math.abs(across) <= DOOR_ACROSS;
            if (along >= passedAt && !inLane && laneWaitCounted != narrowCursor) {
                laneWaitCounted = narrowCursor;
                stats.doorLaneWaits++;
            }
            if (along >= passedAt && inLane) {
                if (straight) {
                    passNarrow();
                    return false;
                }
                narrowExit = true;
            } else {
                if (along < -DOOR_APPROACH || Math.abs(across) > DOOR_ACROSS) return false;
                double aim = along + DOOR_LOOKAHEAD;
                if (along < DOOR_ENTRY && Math.abs(across) > DOOR_ALIGN) aim = Math.min(aim, DOOR_HOLD);
                aim = Math.min(aim, straight && !last ? 1.0 : 0.0);
                threadAimX = gx + ux * aim;
                threadAimZ = gz + uz * aim;
                threadTile = k;
                return true;
            }
        }
        double gap = gapAt(level, k, vx != 0 ? Direction.Axis.X : Direction.Axis.Z);
        double gx = vx != 0 ? t.centreX() : t.x() + gap;
        double gz = vx != 0 ? t.z() + gap : t.centreZ();
        double along = (x - gx) * vx + (z - gz) * vz;
        if (along >= DOOR_PASSED) {
            passNarrow();
            return false;
        }
        double aim = Math.min(along + DOOR_LOOKAHEAD, 1.0);
        threadAimX = gx + vx * aim;
        threadAimZ = gz + vz * aim;
        threadTile = k;
        return true;
    }

    private void passNarrow() {
        narrowCursor++;
        narrowExit = false;
        stats.doors++;
    }

    /**
     * Where across tile {@code k} the middle of the open gap lies, 0 to 1, walking along
     * {@code passage}: from the door's live shape, or the open trapdoor's at feet or head height.
     */
    private double gapAt(ServerLevel level, int k, Direction.Axis passage) {
        BlockPos at = feet.get(k);
        BlockState state = level.getBlockState(at);
        if (kind[k] == FollowRoute.TRAPDOOR && !FollowRoute.isOpenTrapdoor(state)) {
            probe.set(at.getX(), at.getY() + 1, at.getZ());
            BlockState head = level.getBlockState(probe);
            if (!FollowRoute.isOpenTrapdoor(head)) return 0.5;
            return gapCentre(level, probe, head, passage);
        }
        return gapCentre(level, at, state, passage);
    }

    /** What the body is doing on this step, in one word, for the stuck catcher. */
    private String stepWord(PathfinderMob npc, @Nullable RouteLine.StepMark mark, boolean onGround, boolean inWater,
                            boolean jumping) {
        if (inWater) return "water";
        if (threading) return threadWord;
        if (jumping) return "jump";
        if (mark != null && mark.step().kind() == StepKind.DROP && pursuit.toRiser(mark) <= DROP_NEAR) return "drop";
        if (!onGround && npc.getDeltaMovement().y < FALLING) return "drop";
        if (speedNow >= RUN_SPEED || WalkBody.isRunning(npc)) return "run";
        return "walk";
    }

    private Status checkStuck(PathfinderMob npc, ServerLevel level, @Nullable RouteLine.StepMark mark,
                              boolean onGround, boolean lastSection) {
        double progress = pursuit.progress();
        if (progress >= gainBase + MIN_GAIN) {
            gainBase = progress;
            ticksSinceGain = 0;
        } else {
            ticksSinceGain++;
        }

        boolean fell = onGround && npc.getY() < floorHere() - FELL_BELOW;
        offLineTicks = pursuit.offLine() > OFF_LINE || fell ? offLineTicks + 1 : 0;
        if (offLineTicks >= OFF_LINE_TICKS) {
            return stuck(fell ? "off the line (fell below its floor)"
                    : String.format(Locale.ROOT, "off the line (%.1f blocks)", pursuit.offLine()), "left the line", false);
        }

        // Every hop at this step left the ground and landed back below it: the step itself is
        // the trouble, so the map is told (VillagerModel writes the step ahead off for a while).
        if (hopMark != null && mark == hopMark && hopPushTicks == 0 && onGround
                && hopsAtMark >= MAX_HOPS && fellBackAtMark >= hopsAtMark) {
            ahead = lineFirst + hopMark.tile() + 1;
            stats.blamed++;
            return stuck("jump failed (" + hopsAtMark + " hops, all fell back)", "could not jump the step", true);
        }

        boolean jumpNear = mark != null && mark.step().kind() == StepKind.JUMP && pursuit.toRiser(mark) < STEP_NEAR;
        if (onGround && npc.horizontalCollision && hopPushTicks == 0 && !jumpNear && blockedAhead(level, npc)) {
            blockedTicks++;
        } else {
            blockedTicks = 0;
        }
        if (blockedTicks >= BLOCKED_TICKS) return blockedStuck(level, npc);

        if (lastSection && ticksSinceGain >= DONE_NEAR_TICKS
                && pursuit.remaining() + pursuit.offLine() <= (detailed ? DONE_NEAR_DETAILED : DONE_NEAR)
                && onGround && levelWithEnd(npc)) {
            return endOfRoute(npc, level, "near the end, could get no closer");
        }
        if (ticksSinceGain >= NO_GAIN_TICKS) {
            if (jumpNear && hopsAtMark < MAX_HOPS && ticksSinceGain < NO_GAIN_TICKS_MOST) return Status.RUNNING;
            return stuck(jumpNear ? "jump failed (" + hopsAtMark + " hops, " + lastHopResult + ")"
                    : String.format(Locale.ROOT, "no progress (under %.1f blocks in %d s)", MIN_GAIN, NO_GAIN_TICKS / 20),
                    "stopped on the line", false);
        }
        return Status.RUNNING;
    }

    /**
     * Step 5 ({@link WalkFeel#cornerRound}): whether a body-wide box slides straight from
     * {@code a} to {@code b} across line point {@code corner}, over route tiles near the corner
     * level with it or open map ground at its height (the same test the line was straightened
     * with, a little tighter at the sides since the body walks this chord at once).
     */
    private boolean roundClear(double ax, double az, double bx, double bz, int corner) {
        if (!WalkFeel.cornerRound || line == null) return false;
        int k = lineFirst + line.tileIndex(corner);
        double stand = tiles.get(k).stand();
        int lo = Math.max(lineFirst, k - 3);
        int hi = Math.min(sections[sectionIndex].to(), k + 3);
        return RouteLine.sweepClear(ax, az, bx, bz, RouteLine.HALF_WIDTH + ROUND_MARGIN, (x, z) -> {
            for (int i = lo; i <= hi; i++) {
                RouteTile t = tiles.get(i);
                if (t.x() == x && t.z() == z && Math.abs(t.stand() - stand) <= RouteLine.FLAT) return true;
            }
            return area.open(x, z, stand);
        });
    }

    /** The body's box, lifted a step and moved {@link #PROBE_AHEAD} toward the aim; null at the aim. */
    @Nullable
    private AABB probeBox(PathfinderMob npc) {
        double ax = aimX - npc.getX();
        double az = aimZ - npc.getZ();
        double dist = Math.sqrt(ax * ax + az * az);
        if (dist < 1.0E-3) return null;
        double reach = Math.min(PROBE_AHEAD, dist);
        AABB box = npc.getBoundingBox();
        return new AABB(box.minX, box.minY + StepRules.STEP_UP, box.minZ, box.maxX, box.maxY, box.maxZ)
                .move(ax / dist * reach, 0.0, az / dist * reach);
    }

    /** The body's box, lifted a step and moved a little toward the aim, runs into a block. */
    private boolean blockedAhead(ServerLevel level, PathfinderMob npc) {
        AABB ahead = probeBox(npc);
        return ahead != null && !level.noCollision(npc, ahead);
    }

    /** What the blocked-ahead probe ran into: the block, its cell, and whether at head height. */
    private record Bump(BlockPos pos, BlockState state, boolean head) {
        /** A door, gate or trapdoor: the walker's own to open, never the map's fault. */
        boolean doorway() {
            Block block = state.getBlock();
            return block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock;
        }

        String describe() {
            String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            if (state.hasProperty(BlockStateProperties.OPEN)) {
                name += state.getValue(BlockStateProperties.OPEN) ? " (open)" : " (closed)";
            }
            return String.format(Locale.ROOT, "%s at %d,%d,%d, %s height",
                    name, pos.getX(), pos.getY(), pos.getZ(), head ? "head" : "waist");
        }
    }

    /**
     * The block whose collision shape is in the probe box, the one nearest the body's middle
     * when there are several; null when only an entity (a boat, a minecart) or the world border
     * is in the way. Run once per stuck, not every tick.
     */
    @Nullable
    private Bump findBump(ServerLevel level, PathfinderMob npc) {
        AABB box = probeBox(npc);
        if (box == null) return null;
        CollisionContext ctx = CollisionContext.of(npc);
        int feetY = Mth.floor(npc.getY() + 1.0E-3);
        Bump best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY - 1.0E-7); y++) {
            for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1.0E-7); x++) {
                for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1.0E-7); z++) {
                    probe.set(x, y, z);
                    BlockState state = level.getBlockState(probe);
                    if (state.isAir()) continue;
                    VoxelShape shape = state.getCollisionShape(level, probe, ctx);
                    if (shape.isEmpty()) continue;
                    boolean hit = false;
                    for (AABB part : shape.toAabbs()) {
                        if (part.move(x, y, z).intersects(box)) {
                            hit = true;
                            break;
                        }
                    }
                    if (!hit) continue;
                    double dx = x + 0.5 - npc.getX(), dz = z + 0.5 - npc.getZ();
                    double distance = dx * dx + dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new Bump(probe.immutable(), state, y > feetY);
                    }
                }
            }
        }
        return best;
    }

    /**
     * Stuck pressed against something (route follower stucks, steps 2 and 3, 10-18). The rule
     * names what the body ran into. A solid block the map calls open is then reported
     * ({@link WalkFeel#bumpLearn}): in a route tile's own column, feet or head, every step into
     * that tile is marked failed in the map, so the re-plan goes round instead of walking into it
     * again; beside the route, the line is kept off that column for a while and the village
     * re-reads the block, in case the map missed it being placed. (Not for a tile on the route:
     * a re-read rebuilds its node and would forget the failed steps.) Doors, gates and trapdoors
     * are the walker's own to open: named, never reported.
     */
    private Status blockedStuck(ServerLevel level, PathfinderMob npc) {
        Bump bump = findBump(level, npc);
        if (bump == null) {
            return stuck("blocked ahead (no block in the way: an entity or the world border)", "stopped on the line", false);
        }
        String what = bump.describe();
        if (bump.doorway() || !WalkFeel.bumpLearn) return stuck("blocked ahead (" + what + ")", "stopped on the line", false);
        // A raider teaches the map nothing (raider plan round 2): the block is only re-read.
        if (!WalkBody.teachesMap(npc)) {
            askRescan(level, bump.pos());
            return stuck("blocked ahead (" + what + "; block re-read)", "stopped on the line", false);
        }
        int k = routeTileHolding(bump.pos());
        NavNode tile = k >= 0 ? nodeOf(k) : null;
        if (tile != null) {
            // The block stands in the tile itself, so every way into it is closed, not just this one.
            int marked = 0;
            for (NavNode nb : tile.neighbours()) {
                if (nb == null || !nb.canStepTo(tile)) continue;
                graph.markStepFailed(nb, tile, BUMP_STEP_MILLIS);
                marked++;
            }
            if (marked > 0) {
                ahead = k;
                stats.bumpSteps++;
                Status status = stuck(String.format(Locale.ROOT,
                        "blocked ahead (%s; %d steps into %d,%d,%d marked failed)",
                        what, marked, tile.x, tile.feetY, tile.z), "stopped on the line", false);
                stepMarked = true;
                return status;
            }
        }
        FollowRoute.noteBump(graph, bump.pos().getX(), bump.pos().getZ());
        askRescan(level, bump.pos());
        stats.bumpBeside++;
        return stuck("blocked ahead (" + what + "; kept off the line, block re-read)", "stopped on the line", false);
    }

    /**
     * The route tile ahead on the body's stretch (or the two after it) whose own column, feet or
     * head cell, holds {@code pos}; -1 when none does. Never the first tile of the line: the body
     * came from there.
     */
    private int routeTileHolding(BlockPos pos) {
        int end = sections[sectionIndex].to();
        int seg = pursuit.segment();
        int from = lineFirst + line.tileIndex(seg) + 1;
        int to = Math.min(end, lineFirst + line.tileIndex(Math.min(seg + 1, line.points() - 1)) + 2);
        for (int k = Math.max(lineFirst + 1, from); k <= to; k++) {
            BlockPos f = feet.get(k);
            if (f.getX() == pos.getX() && f.getZ() == pos.getZ()
                    && (pos.getY() == f.getY() || pos.getY() == f.getY() + 1)) {
                return k;
            }
        }
        return -1;
    }

    @Nullable
    private NavNode nodeOf(int k) {
        BlockPos f = feet.get(k);
        return graph.nodeAt(f.getX(), f.getY(), f.getZ());
    }

    /** Bumped blocks (packed position) and when the village was last asked to re-read each. */
    private static final Map<Long, Long> RESCANNED = new HashMap<>();

    /** Asks the village to re-read the map at {@code pos}, at most every {@link #RESCAN_EVERY_TICKS}. */
    private static void askRescan(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        Long last = RESCANNED.get(pos.asLong());
        if (last != null && now >= last && now - last < RESCAN_EVERY_TICKS) return;
        if (RESCANNED.size() > 512) RESCANNED.clear();
        RESCANNED.put(pos.asLong(), now);
        VillageManager village = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, pos);
        if (village != null) village.markNavigationDirtyAt(pos);
    }

    /** Lowest floor of the tiles on the body's stretch of line. */
    private double floorHere() {
        int seg = pursuit.segment();
        int from = line.tileIndex(seg);
        int to = line.tileIndex(Math.min(seg + 1, line.points() - 1));
        double low = Double.POSITIVE_INFINITY;
        for (int k = from; k <= to; k++) low = Math.min(low, tiles.get(lineFirst + k).stand());
        return low;
    }

    /** The route tile after the one under the body on its stretch, or the stretch's end tile. */
    private int tileAhead(double x, double z) {
        int seg = pursuit.segment();
        int from = line.tileIndex(seg);
        int to = line.tileIndex(Math.min(seg + 1, line.points() - 1));
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int end = sections[sectionIndex].to();
        for (int k = from; k <= to; k++) {
            RouteTile t = tiles.get(lineFirst + k);
            if (t.x() == bx && t.z() == bz) return Math.min(lineFirst + k + 1, end);
        }
        return lineFirst + to;
    }

    private boolean levelWithEnd(PathfinderMob npc) {
        return Math.abs(npc.getY() - tiles.get(tiles.size() - 1).stand()) <= ARRIVE_Y;
    }

    private boolean nearStairs(ServerLevel level, PathfinderMob npc) {
        if (stair[ahead] || stair[Math.max(lineFirst, ahead - 1)]) return true;
        probe.set(npc.getX(), npc.getY() - 0.05, npc.getZ());
        if (level.getBlockState(probe).getBlock() instanceof StairBlock) return true;
        probe.move(0, -1, 0);
        return level.getBlockState(probe).getBlock() instanceof StairBlock;
    }

    private void easeTowardEnd(PathfinderMob npc) {
        double left = pursuit.remaining();
        if (left >= END_EASE_FROM) return;
        double t = Mth.clamp((left - END_EASE_TO) / (END_EASE_FROM - END_EASE_TO), 0.0, 1.0);
        WalkBody.walkEndEase(npc, (float) (END_EASE_SLOWEST + (1.0 - END_EASE_SLOWEST) * t));
    }

    /**
     * A door or gate tile near the body on the ground ahead: the door handler opens only these.
     * Its anchor (a door's lower half, the bottom gate of a stack) is in the tile's feet cell, or
     * its head cell for one hung at head height, which the old walker opened too.
     */
    private boolean isRouteDoorNear(BlockPos anchor) {
        if (ladder != null) return false;
        for (int k : doorTiles) {
            if (k < ahead - 2) continue;
            if (k > ahead + 3) break;
            BlockPos f = feet.get(k);
            if (f.getX() == anchor.getX() && f.getZ() == anchor.getZ()
                    && (anchor.getY() == f.getY() || anchor.getY() == f.getY() + 1)) {
                return true;
            }
        }
        return false;
    }

    private Status finish(PathfinderMob npc, String why) {
        finished = true;
        note = why;
        stats.done++;
        closeAllOpenedDoors(npc);
        return Status.DONE;
    }

    /**
     * At the last tile of the route as read. A whole route is done. A route cut short
     * ({@link FollowRoute.Result#cut}) is not: too long to read at once, the rest is planned
     * from here ({@link Status#REPLAN}); stopped by a step no body can take, that step is marked
     * failed in the map, its block re-read, and it is a stuck, so the re-plan goes round; stopped
     * by anything else (an unloaded chunk) it is a plain stuck. Never blamed on the step ahead by
     * {@code VillagerModel}: the step is marked here or not at all.
     */
    private Status endOfRoute(PathfinderMob npc, ServerLevel level, String why) {
        if (cutKind == FollowRoute.CUT_NONE) return finish(npc, why);
        stats.cutReached++;
        if (cutKind == FollowRoute.CUT_LENGTH) {
            finished = true;
            note = "walked as far as the route was read; planning the rest";
            closeAllOpenedDoors(npc);
            return Status.REPLAN;
        }
        String at = cutTo == null ? "?" : cutTo.x + "," + cutTo.feetY + "," + cutTo.z;
        if (cutKind == FollowRoute.CUT_STEP && cutFrom != null && cutTo != null) {
            // The map's nodes now, in case it was rebuilt since the route was planned.
            NavNode from = graph.nodeAt(cutFrom.x, cutFrom.feetY, cutFrom.z);
            NavNode to = graph.nodeAt(cutTo.x, cutTo.feetY, cutTo.z);
            // A raider teaches the map nothing (raider plan round 2): the block is only re-read.
            boolean marked = from != null && to != null && from.canStepTo(to) && WalkBody.teachesMap(npc);
            if (marked) graph.markStepFailed(from, to, BUMP_STEP_MILLIS);
            BlockPos toFeet = cutTo.feetPos();
            askRescan(level, toFeet);
            // The villager's id, as every other line and the stuck reports print it; the body's
            // own UUID is a different number and read as a second villager.
            LOGGER.info("[FollowCut] {} ({}) {} at {} -> {}: feet {}, head {}, floor {}; {}",
                    WalkBody.logId(npc),
                    WalkBody.logRole(npc), cut,
                    cutFrom.x + "," + cutFrom.feetY + "," + cutFrom.z, at,
                    blockName(level, toFeet), blockName(level, toFeet.above()), blockName(level, toFeet.below()),
                    marked ? "step marked failed, block re-read" : "no map step to mark, block re-read");
            Status status = stuck("route cut short (" + cut + " at " + at + ")",
                    "reached where its route was cut short", false);
            stepMarked = marked;
            return status;
        }
        return stuck("route cut short (" + cut + " at " + at + ")", "reached where its route was cut short", false);
    }

    private static String blockName(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
    }

    /**
     * A stuck here reports itself off the route, so {@code VillagerModel} does not write the step
     * ahead off as failed: a fault in this walker must not teach the map to avoid a good step.
     * The one exception ({@code blameStep}) is a jump tried {@link #MAX_HOPS} times that left the
     * ground each time and landed back below the step: that is the step's fault. The real reason
     * is in {@link #stuckRule}.
     */
    private Status stuck(String rule, String wording, boolean blameStep) {
        stuckOffRoute = !blameStep;
        stepMarked = false;
        stuckRule = rule;
        note = wording;
        stats.stuck(rule);
        return Status.STUCK;
    }

    /**
     * Ladder stucks ({@link WalkFeel#ladderBlame}, 10-19). A ladder stuck stays "off the route",
     * so {@code VillagerModel} blames nothing, and the stuck loop's own blame looks from the
     * villager's tile (often the shaft's bottom) to the leg's target (often blocks up), which are
     * not neighbours, so a ladder step the walker cannot do was chosen again and again. Here the
     * leg's own step (the tile it last reached -> the tile it was going to) is marked failed in the
     * map for {@link #BUMP_STEP_MILLIS}, like a bumped ground step, on the second stuck in a row
     * there by this villager within {@link #LADDER_MISS_TICKS}. Never while anyone else (a mob, a
     * player) is at the step: it may have been in the way, and that stuck breaks the row. Never for
     * a raider, never for a step the leg did not really try. Not counted toward
     * {@code stickysteps}, so a ladder blame never keeps a step failed for the session.
     */
    private void blameLadderStep(PathfinderMob npc, ServerLevel level, LadderLeg leg) {
        BlockPos[] step = leg.stuckStep();
        if (step == null || !WalkBody.teachesMap(npc)) return;
        NavNode from = graph.nodeAt(step[0].getX(), step[0].getY(), step[0].getZ());
        NavNode to = graph.nodeAt(step[1].getX(), step[1].getY(), step[1].getZ());
        if (from == null || to == null || !from.hasNeighbour(to)) return;
        String where = step[0].toShortString() + " -> " + step[1].toShortString();
        AABB around = new AABB(Vec3.atLowerCornerOf(step[0]), Vec3.atLowerCornerOf(step[1]))
                .expandTowards(1.0, 2.0, 1.0)
                .minmax(npc.getBoundingBox())
                .inflate(0.5);
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, around,
                other -> other != npc && other.isAlive() && !other.isSpectator()).isEmpty()) {
            // Someone may have been in the way: this stuck proves nothing, and breaks the row.
            LADDER_MISSES.remove(npc);
            stuckRule += " (someone else at ladder step " + where + ": not blamed)";
            return;
        }
        long now = level.getGameTime();
        LadderMiss last = LADDER_MISSES.get(npc);
        if (last == null || !last.from().equals(step[0]) || !last.to().equals(step[1])
                || now - last.at() > LADDER_MISS_TICKS || now < last.at()) {
            LADDER_MISSES.put(npc, new LadderMiss(step[0], step[1], now));
            stuckRule += " (first stuck at ladder step " + where + ")";
            return;
        }
        LADDER_MISSES.remove(npc);
        // Not counted toward stickysteps: a ladder blame always lapses, so a walker fault can
        // never lose a good ladder (the only way up a tower) for the session.
        graph.markStepFailed(from, to, BUMP_STEP_MILLIS, false);
        stepMarked = true;
        stuckRule += " (second stuck in a row at ladder step " + where + ": marked failed)";
        LOGGER.info("[LadderBlame] {} ({}) stuck twice in a row at ladder step {}; step marked failed for {} min",
                WalkBody.logId(npc), WalkBody.logRole(npc), where, BUMP_STEP_MILLIS / 60_000L);
    }

    /** {@link #blameLadderStep}: a villager's last ladder stuck, the step it was on and the game tick. */
    private record LadderMiss(BlockPos from, BlockPos to, long at) {}

    /** Last ladder stuck per body, server thread only; weak keys, so a gone body is forgotten. */
    private static final Map<PathfinderMob, LadderMiss> LADDER_MISSES = new java.util.WeakHashMap<>();
    /** The second ladder stuck counts as "in a row" within this many ticks of the first. */
    private static final long LADDER_MISS_TICKS = 20L * 120L;

    /** Takes the doors the ladder leg opened, so they are shut behind the villager like any other. */
    @Override
    void gatherDoors() {
        if (ladder != null) doorHandler.adopt(ladder.doorHandler);
    }

    @Override
    public void closeAllOpenedDoors(PathfinderMob npc) {
        gatherDoors();
        super.closeAllOpenedDoors(npc);
    }

    // ---------------------------------------------------------------------------------------
    // What the rest of the mod reads from a walk

    @Override
    public BlockPos currentWaypoint() {
        return feet.get(ahead);
    }

    @Override
    public int currentWaypointIndex() {
        return ahead;
    }

    /** From the tile behind the body to {@code count} tiles ahead; each chunk once per run of tiles in it. */
    @Override
    public void chunksAhead(int count, java.util.function.LongConsumer out) {
        if (tiles.isEmpty()) return;
        int from = Math.max(0, ahead - 1);
        int to = Math.min(tiles.size() - 1, ahead + count);
        long last = Long.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            RouteTile tile = tiles.get(i);
            long chunk = net.minecraft.world.level.ChunkPos.asLong(tile.x() >> 4, tile.z() >> 4);
            if (chunk != last) {
                out.accept(chunk);
                last = chunk;
            }
        }
    }

    @Override
    public boolean stuckOffRoute() {
        return stuckOffRoute;
    }

    /** True when the last stuck already marked the step it was blocked at failed in the map. */
    @Override
    public boolean stepMarkedFailed() {
        return stepMarked;
    }

    /**
     * "no progress", "off the line", "blocked ahead", "jump failed", "could not get onto its route",
     * "route cut short" or "ladder: ...", with detail after.
     */
    @Override
    public String stuckRule() {
        return stuckRule;
    }

    @Override
    public String spyHops() {
        return ladder != null ? ladder.spyHops() : hopsAtMark + " (" + lastHopResult + ")";
    }

    @Override
    String spyMode() {
        return "follow";
    }

    @Override
    boolean spyThreadingDoor() {
        return ladder != null ? ladder.spyThreadingDoor() : threading;
    }

    @Override
    boolean spyLadderOnRung() {
        return ladder != null && ladder.spyLadderOnRung();
    }

    /** Stuck catcher rewind: the walk picks up from the tile before {@code index}, the one it stood on. */
    @Override
    public void startAt(int index) {
        startFrom(Mth.clamp(index - 1, 0, tiles.size() - 1));
    }

    /** Stuck catcher: the frame fields this walk has a meaning for. */
    @Override
    public void describeTo(com.alex.test.village.stuckwatch.BlackBox.Frame f) {
        f.route = waypoints();
        f.wp = ahead;
        f.wpCount = feet.size();
        f.wpPos = feet.get(ahead).asLong();
        f.steer = joining ? "join" : ladder != null ? "ladder" : "follow";
        f.lastSteer = note;
        f.hops = hopsAtMark;
        f.hopResult = lastHopResult;
    }

    @Override
    public String debugState(PathfinderMob npc) {
        BlockPos wp = feet.get(ahead);
        Section sec = sections[sectionIndex];
        String where = String.format(Locale.ROOT,
                "wp=%d/%d mode=follow section=%d/%d %s tiles %d-%d step=%s tile=(%d,%d,%d) npc=(%.2f,%.2f,%.2f)",
                ahead + 1, feet.size(), sectionIndex + 1, sections.length, sec.ladder() ? "ladder" : "ground",
                sec.from() + 1, sec.to() + 1, stepNow, wp.getX(), wp.getY(), wp.getZ(),
                npc.getX(), npc.getY(), npc.getZ());
        if (cut != null) where += " cut=" + cut;
        // The door, gate or trapdoor being lined up for, else the next one on the route.
        if (threading && threadTile >= 0) where += " thread=" + narrowName(threadTile);
        else if (narrowCursor < narrow.length) where += " nextnarrow=" + narrowName(narrow[narrowCursor]);
        if (joining) return where + " joining: " + note;
        if (ladder != null) return where + " leg: " + ladder.debugState(npc);
        if (pursuit == null) return where + " last=" + note;
        RouteLine.StepMark mark = pursuit.nextStep();
        return where + String.format(Locale.ROOT,
                " progress=%.2f/%.2f offLine=%.2f next=%s toRiser=%s noGainTicks=%d offTicks=%d blockedTicks=%d hops=%s last=%s",
                pursuit.progress(), line.length(), pursuit.offLine(),
                mark == null ? "none" : mark.step().kind().name().toLowerCase(Locale.ROOT),
                mark == null ? "-" : String.format(Locale.ROOT, "%.2f", pursuit.toRiser(mark)),
                ticksSinceGain, offLineTicks, blockedTicks, spyHops(), note);
    }

    /** A door, gate or trapdoor route tile for the walker line: kind, its number on the route, its feet cell. */
    private String narrowName(int k) {
        BlockPos f = feet.get(k);
        String word = kind[k] == FollowRoute.GATE ? "gate" : kind[k] == FollowRoute.TRAPDOOR ? "trapdoor" : "door";
        return String.format(Locale.ROOT, "%s#%d(%d,%d,%d)", word, k + 1, f.getX(), f.getY(), f.getZ());
    }

    // Stuck catcher getters (BlackBox reads these for a FollowWalk).

    /** Distance walked along the route so far, in blocks. */
    public double followProgress() {
        return pursuit != null ? tileArc[lineFirst] + pursuit.progress() : tileArc[Math.max(0, ahead - 1)];
    }

    /** Distance left to the walk's end, in blocks. */
    public double followRemaining() {
        double end = tileArc[tileArc.length - 1];
        if (pursuit != null) return pursuit.remaining() + (end - tileArc[sections[sectionIndex].to()]);
        return end - tileArc[Math.max(0, ahead - 1)];
    }

    public double followOffLine() {
        return pursuit != null ? pursuit.offLine() : 0.0;
    }

    /** The next jump or drop ahead on the ground: "jump", "drop", or "none" (also on a ladder leg). */
    public String followNextStepKind() {
        RouteLine.StepMark mark = pursuit != null ? pursuit.nextStep() : null;
        return mark == null ? "none" : mark.step().kind() == StepKind.JUMP ? "jump" : "drop";
    }

    /** Body middle to the next step's riser, in blocks; NaN when there is none. */
    public double followToRiser() {
        RouteLine.StepMark mark = pursuit != null ? pursuit.nextStep() : null;
        return mark == null ? Double.NaN : pursuit.toRiser(mark);
    }

    public int followHops() { return hopsAtMark; }

    public int followBlockedTicks() { return blockedTicks; }

    public double followNoProgressSeconds() { return ticksSinceGain / 20.0; }

    /**
     * What the body is doing on the current step, as of the last tick: "walk", "run", "jump",
     * "drop", "door", "gate", "trapdoor", "ladder" or "water". One word, never combined.
     */
    public String followStepNow() { return stepNow; }

    // ---------------------------------------------------------------------------------------

    /**
     * The {@code [Follow]} minute line: how much the follower walked and how it went. Server thread only.
     * Since 10-19 raiders' walks (raider plan round 3) count apart, in a {@code [FollowRaid]} line, and
     * never in {@code [FollowPace]}: until then they were mixed into the villagers' numbers.
     */
    private static final class Stats {
        static final Stats VILLAGERS = new Stats("[Follow]");
        static final Stats RAIDERS = new Stats("[FollowRaid]");
        private static final long WINDOW = 1200L;
        private static long windowStart = Long.MIN_VALUE;
        private final String tag;
        int started, running, done, hops, cleared, fellBack, ownTakeoffs, ladders, doors, blamed;
        /** Blocks found in the way (step 3): in the route's column (step marked failed), or beside it. */
        int bumpSteps, bumpBeside;
        /** Level corners the body curved through (step 5). */
        int rounded;
        /** D1: doors whose pass waited because the body was past their middle but not in their lane. */
        int doorLaneWaits;
        /** Walks that began off the route (walked straight to its first tile), and cut routes walked to the cut. */
        int joinedFar, cutReached;
        private int builds;
        private long buildNanos;
        private long worstBuildNanos;
        private final Map<String, Integer> refused = new TreeMap<>();
        /** Routes cut short when read, by why. */
        final Map<String, Integer> cuts = new TreeMap<>();
        private final Map<String, Integer> stuck = new TreeMap<>();
        /**
         * Climb-cost step 0, walking villagers only: line gained and ticks on level ground and
         * on half-steps up and down; timed jumps and drops (ticks over the two blocks round each
         * riser); ladder rungs and ticks, floor to floor; walking climbs that fell back.
         */
        private double flatArc, upArc, downArc;
        private int flatTicks, upTicks, downTicks, climbs, climbTicks, drops, dropTicks;
        int ladderRungs, ladderTicks, climbsFellBack;

        private Stats(String tag) {
            this.tag = tag;
        }

        /** The counts a walk of {@code body} goes to: villagers' (and travellers'), or raiders'. */
        static Stats of(PathfinderMob body) {
            return WalkBody.teachesMap(body) ? VILLAGERS : RAIDERS;
        }

        void timedGround(int rise, double gain) {
            if (rise > 0) {
                upArc += gain;
                upTicks++;
            } else if (rise < 0) {
                downArc += gain;
                downTicks++;
            } else {
                flatArc += gain;
                flatTicks++;
            }
        }

        void timedStep(StepKind kind, int ticks) {
            if (kind == StepKind.JUMP) {
                climbs++;
                climbTicks += ticks;
            } else {
                drops++;
                dropTicks += ticks;
            }
        }

        @Nullable
        FollowWalk refuse(String why) {
            refused.merge(why, 1, Integer::sum);
            return null;
        }

        void stuck(String rule) {
            int cut = rule.indexOf(" (");
            stuck.merge(cut > 0 ? rule.substring(0, cut) : rule, 1, Integer::sum);
        }

        /** One route read and its first line built, taken or not. */
        void timed(long nanos) {
            builds++;
            buildNanos += nanos;
            worstBuildNanos = Math.max(worstBuildNanos, nanos);
        }

        /** About once a minute: the villagers' and the raiders' lines, the pace line, the route search lines. */
        static void flush(ServerLevel level) {
            long now = level.getGameTime();
            if (windowStart == Long.MIN_VALUE || now < windowStart) windowStart = now;
            if (now - windowStart < WINDOW) return;
            windowStart = now;
            VILLAGERS.flushPace();
            RAIDERS.clearPace();
            AStarPathfinder.SearchStats.flush();
            RoutePriceCheck.flush();
            VILLAGERS.flushLine();
            RAIDERS.flushLine();
        }

        private void flushLine() {
            if (started == 0 && refused.isEmpty()) return;
            int stuckCount = stuck.values().stream().mapToInt(Integer::intValue).sum();
            double avgMs = builds == 0 ? 0.0 : buildNanos / 1.0E6 / builds;
            LOGGER.info("{} last 60 s: {} walks ({} running), {} done, {} stuck {}, refused {}, cut short {}"
                            + " ({} reached), joined from off the route {}, hops {} ({} up,"
                            + " {} fell back, {} own take-offs), ladders {}, doors {} ({} waited for their lane),"
                            + " steps blamed {}, blocks in the way {} on the route / {} beside, corners rounded {},"
                            + " build avg {} ms worst {} ms",
                    tag, started, running, done, stuckCount, stuck, refused, cuts, cutReached, joinedFar,
                    hops, cleared, fellBack, ownTakeoffs,
                    ladders, doors, doorLaneWaits, blamed, bumpSteps, bumpBeside, rounded,
                    String.format(Locale.ROOT, "%.2f", avgMs),
                    String.format(Locale.ROOT, "%.2f", worstBuildNanos / 1.0E6));
            started = running = done = hops = cleared = fellBack = ownTakeoffs = ladders = doors = blamed = 0;
            bumpSteps = bumpBeside = rounded = joinedFar = cutReached = doorLaneWaits = 0;
            builds = 0;
            buildNanos = worstBuildNanos = 0L;
            refused.clear();
            cuts.clear();
            stuck.clear();
        }

        /**
         * The {@code [FollowPace]} line: walking pace in blocks a second on level ground and on
         * half-steps, and seconds per two blocks round a full-block climb or a drop, with how
         * much longer that is than two blocks of level ground. "climb Minecraft" or "climb quick"
         * says how walking climbs were done ({@link WalkFeel#vanillaClimb}).
         */
        private void flushPace() {
            if (flatTicks + upTicks + downTicks + climbs + drops + ladderRungs == 0) {
                climbsFellBack = 0;
                return;
            }
            double flatPace = flatArc <= 0.0 ? Double.NaN : flatArc / flatTicks;
            double flatTwo = 2.0 * PACE_WINDOW / flatPace;
            LOGGER.info("[FollowPace] last 60 s, walking, climb {}: flat {} b/s ({} s timed), half-steps up {} b/s ({} s)"
                            + " / down {} b/s ({} s), full-block climbs {} at {} s per 2 blocks ({} s over flat,"
                            + " {} fell back), drops {} at {} s ({} s over flat), ladders {} rungs at {} s each",
                    WalkFeel.vanillaClimb ? "Minecraft" : "quick",
                    pace(flatArc, flatTicks), secs(flatTicks), pace(upArc, upTicks), secs(upTicks),
                    pace(downArc, downTicks), secs(downTicks),
                    climbs, perStep(climbTicks, climbs), overFlat(climbTicks, climbs, flatTwo), climbsFellBack,
                    drops, perStep(dropTicks, drops), overFlat(dropTicks, drops, flatTwo),
                    ladderRungs, perStep(ladderTicks, ladderRungs));
            clearPace();
        }

        private void clearPace() {
            flatArc = upArc = downArc = 0.0;
            flatTicks = upTicks = downTicks = climbs = climbTicks = drops = dropTicks = 0;
            ladderRungs = ladderTicks = climbsFellBack = 0;
        }

        private static String pace(double arc, int ticks) {
            return ticks == 0 ? "-" : String.format(Locale.ROOT, "%.2f", arc / ticks * 20.0);
        }

        private static String secs(int ticks) {
            return String.format(Locale.ROOT, "%.1f", ticks / 20.0);
        }

        private static String perStep(int ticks, int count) {
            return count == 0 ? "-" : String.format(Locale.ROOT, "%.2f", ticks / 20.0 / count);
        }

        private static String overFlat(int ticks, int count, double flatTwo) {
            if (count == 0 || Double.isNaN(flatTwo)) return "-";
            return String.format(Locale.ROOT, "%+.2f", ((double) ticks / count - flatTwo) / 20.0);
        }
    }
}
