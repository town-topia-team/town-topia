package com.alex.test.village.villagers.guard.behavior.combat;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.AStarPathfinder;
import com.alex.test.village.FollowWalk;
import com.alex.test.village.MoveToCommand;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VanillaNavCount;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.brain.Activity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Close-range footwork for a fighting guard or captain: walking straight at a
 * mob it can see instead of planning a village-graph route to it, standing at
 * sword's length on a side no other defender has taken, zig-zagging in on a
 * shooter, lunging into a counter-swing, and stepping out of an axe's reach.
 *
 * <p>The village graph is the right tool for crossing a village but far too
 * slow for a fight: every step was "pick a graph spot, wait for an async A*,
 * walk, arrive, pick again", while a zombie re-aims at its target every tick.
 * Within {@link #DIRECT_RANGE} blocks, level and in sight, this steers vanilla
 * navigation at the mob's predicted position every few ticks, the way vanilla
 * melee mobs chase. It gives up -- and the caller falls back on the graph --
 * when vanilla finds no path or the guard stops gaining ground, and stays
 * inside the village graph's bounds, which the villager model enforces.
 *
 * <p>The step back from an axe and the run clear of a lit creeper are walked on the
 * village map instead, on routes planned on the spot ({@link WalkFeel#stepBackMap},
 * docs/plans/2026-10-18-vanilla-pathfinder-leftovers.md step 7). With
 * {@link WalkFeel#chaseMap} (step 9, off by default) the close chase is too: the same
 * spot, gates and give-ups, but a short map route planned on the spot instead of
 * Minecraft's path, and a new one only when the spot has moved.
 */
public final class GuardFootwork {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double DIRECT_RANGE = 10.0D;
    /** Shooters are chased directly from farther out: they back off and keep shooting. */
    private static final double DIRECT_RANGE_RANGED = 18.0D;
    private static final double MAX_DIRECT_Y_DIFF = 1.5D;
    /** With {@link WalkFeel#chaseMargin}, a direct chase already running holds this much farther out... */
    private static final double HOLD_EXTRA_RANGE = 2.0D;
    /** ...this much more height apart... */
    private static final double HOLD_EXTRA_Y_DIFF = 0.75D;
    /** ...and this long out of sight. */
    private static final int HOLD_SIGHT_GRACE_TICKS = 10;
    private static final int REPATH_TICKS = 5;
    /** The mob's position is led by this many ticks of its current motion... */
    private static final int LEAD_TICKS = 6;
    /** ...but never by more than this many blocks. */
    private static final double MAX_LEAD = 1.5D;
    /** Ticks without getting closer before the direct chase is given up. */
    private static final int NO_PROGRESS_TICKS = 30;
    /** After giving up, the graph route is used for this long. */
    private static final int BLOCKED_TICKS = 60;
    private static final double PROGRESS_STEP = 0.3D;
    /** Spacing of the doorway checks along the line to the mob, in blocks. */
    private static final double DOORWAY_SAMPLE_STEP = 0.25D;

    /** A shooter farther than this is closed on in a zig-zag. */
    private static final double ZIGZAG_MIN_DIST = 6.0D;
    private static final double ZIGZAG_OFFSET = 1.6D;
    private static final double ZIGZAG_STEP = 4.0D;
    private static final int ZIGZAG_MIN_TICKS = 14;
    private static final int ZIGZAG_JITTER_TICKS = 10;

    /** Stand spots this close to another defender are taken. */
    private static final double TAKEN_SPOT_DIST_SQ = 1.3D * 1.3D;
    /** Sides tried around the mob, in degrees off the guard's own line of approach. */
    private static final double[] FLANK_ANGLES = {0.0D, 45.0D, -45.0D, 90.0D, -90.0D};

    /** Speed of a lunge step during a swing's wind-up (blocks per tick). */
    private static final double LUNGE_SPEED = 0.18D;
    private static final double DODGE_DIST = 1.6D;

    /** Map steps ({@link WalkFeel#stepBackMap}): a route longer than this many tiles per block of step is a detour, not a step. */
    private static final double MAP_STEP_TILES_PER_BLOCK = 2.0D;
    private static final int MAP_STEP_EXTRA_TILES = 3;
    /** Routes planned at most per look (each one a search of the map, on the spot). */
    private static final int MAP_STEP_MAX_PLANS = 3;
    /** After a look found no map step, or a step got stuck, the map is not searched again for this long. */
    private static final int MAP_STEP_LOOK_AGAIN_TICKS = 10;
    /** Sides tried for a creeper when no step back on {@link #FLANK_ANGLES} is free; the farthest tile from it wins. */
    private static final double[] WIDE_ANGLES = {0.0D, 35.0D, -35.0D, 70.0D, -70.0D, 105.0D, -105.0D, 140.0D, -140.0D};

    /** Map chase ({@link WalkFeel#chaseMap}): the route under way is kept while its end tile is this near the new spot. */
    private static final double CHASE_KEEP_DIST_SQ = 1.5D * 1.5D;
    /** A chase route longer than this many tiles per block to the mob, plus the extra, goes round: left to the village route. */
    private static final double CHASE_TILES_PER_BLOCK = 2.0D;
    private static final int CHASE_EXTRA_TILES = 6;
    /** The {@code [ChaseMap]} line comes at most this often. */
    private static final long CHASE_STATS_TICKS = 1200L;

    private boolean active;
    /** Game time the guard last saw its mob from here; see {@link #HOLD_SIGHT_GRACE_TICKS}. */
    private long lastSightAt = Long.MIN_VALUE / 2;
    private int repathTimer;
    private long blockedUntil;
    private int noProgressTicks;
    private double bestDist = Double.MAX_VALUE;
    private int zigzagSide = 1;
    private int zigzagTimer;
    private int dodgeTicks;
    /** A step back walked on the village map, planned on the spot; see {@link #backOffOnMap}. */
    @Nullable private MoveToCommand mapStep;
    private long mapStepLookAt = Long.MIN_VALUE;
    /** The close chase walked on the village map, and the tile it ends on; see {@link #chaseOnMap}. */
    @Nullable private MoveToCommand chaseWalk;
    @Nullable private BlockPos chaseTile;

    // Map chase numbers for the [ChaseMap] line, across every guard.
    private static long chaseStatsFrom = Long.MIN_VALUE;
    private static int chaseRoutes;
    private static int chaseKept;
    private static int chaseNoRoute;
    private static int chaseDoorway;
    private static int chaseStuck;
    private static long chasePlanNanos;

    public boolean isActive() {
        return active;
    }

    /** Whether the direct chase may run against {@code foe} now. */
    public boolean canChase(Activity.Context ctx, LivingEntity foe, boolean ranged) {
        if (ctx.level().getGameTime() < blockedUntil) return false;
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return false;
        NpcEntity npc = ctx.npc();
        if (npc.isInWaterOrBubble() || npc.onClimbable()) return false;
        // Already running straight at it, a little more room before giving that
        // up for the village route (WalkFeel.chaseMargin). With one line for both,
        // a mob hopping across the height limit, or stepping back and forth
        // across the range, flipped the guard between the two every few ticks,
        // and every flip braked it and planned again.
        boolean holding = WalkFeel.chaseMargin && active;
        double maxY = MAX_DIRECT_Y_DIFF + (holding ? HOLD_EXTRA_Y_DIFF : 0.0D);
        if (Math.abs(npc.getY() - foe.getY()) > maxY) return false;
        double range = (ranged ? DIRECT_RANGE_RANGED : DIRECT_RANGE) + (holding ? HOLD_EXTRA_RANGE : 0.0D);
        if (npc.distanceToSqr(foe) > range * range) return false;
        if (!graph.isInsideBounds(npc.blockPosition()) || !graph.isInsideBounds(foe.blockPosition())) {
            return false;
        }
        // A door or gate between the guard and its mob is walked through on
        // the village route, which opens it and shuts it behind the guard.
        // Switching to footwork there shut the door the route had just opened,
        // took away the line of sight, and the guard flicked the door open and
        // shut.
        if (doorwayInTheWay(ctx.level(), npc, foe)) return false;
        long now = ctx.level().getGameTime();
        if (npc.hasLineOfSight(foe)) {
            lastSightAt = now;
            return true;
        }
        // A fence post or a leaf between them for a moment is no reason to
        // drop the chase for a route.
        return holding && now - lastSightAt <= HOLD_SIGHT_GRACE_TICKS;
    }

    private static boolean isDoorway(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock;
    }

    /**
     * True when the guard stands in a doorway, or a door or fence gate lies on
     * the straight line from the guard to {@code foe}.
     */
    private static boolean doorwayInTheWay(Level level, NpcEntity npc, LivingEntity foe) {
        AABB box = npc.getBoundingBox();
        int feetY = Mth.floor(npc.getY() + 0.01D);
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1.0E-7D); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1.0E-7D); z++) {
                if (isDoorway(level.getBlockState(new BlockPos(x, feetY, z)))
                        || isDoorway(level.getBlockState(new BlockPos(x, feetY + 1, z)))) {
                    return true;
                }
            }
        }
        Vec3 from = npc.position();
        Vec3 to = foe.position();
        int samples = Mth.ceil(from.distanceTo(to) / DOORWAY_SAMPLE_STEP);
        for (int i = 1; i <= samples; i++) {
            Vec3 p = from.lerp(to, (double) i / samples);
            BlockPos cell = BlockPos.containing(p.x, p.y + 0.1D, p.z);
            if (isDoorway(level.getBlockState(cell)) || isDoorway(level.getBlockState(cell.above()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the vanilla path goes through a door or fence gate. Nothing
     * opens one while footwork steers, so the guard would walk into it.
     */
    private static boolean pathUsesDoorway(Level level, Path path) {
        if (path == null) return false;
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            BlockPos cell = new BlockPos(node.x, node.y, node.z);
            if (isDoorway(level.getBlockState(cell)) || isDoorway(level.getBlockState(cell.above()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Steers at {@code foe}: to a free spot at sword's length on the guard's
     * side of it, or, for a shooter still far off, in a zig-zag towards it.
     *
     * @return false when the chase has failed and the graph route should take over
     */
    public boolean chase(Activity.Context ctx, LivingEntity foe, float speed, boolean ranged,
                         List<NpcEntity> otherDefenders, double swingReach) {
        NpcEntity npc = ctx.npc();
        dropMapStep(npc);
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return fail(ctx);
        if (!active) {
            active = true;
            repathTimer = 0;
            noProgressTicks = 0;
            bestDist = Double.MAX_VALUE;
        }

        double dist = GuardCombatRules.edgeDistance(npc, foe);
        if (dist < bestDist - PROGRESS_STEP) {
            bestDist = dist;
            noProgressTicks = 0;
        } else if (++noProgressTicks > NO_PROGRESS_TICKS) {
            return fail(ctx);
        }

        if (WalkFeel.chaseMap) return chaseOnMap(ctx, npc, graph, foe, speed, ranged, otherDefenders, swingReach, dist);
        dropChaseWalk(npc);
        if (--repathTimer > 0 && !npc.getNavigation().isDone()) return true;
        repathTimer = REPATH_TICKS;

        Vec3 lead = leadPosition(foe);
        Vec3 goal = ranged && dist > ZIGZAG_MIN_DIST
                ? zigzagPoint(ctx, npc, lead, dist)
                : standPoint(ctx, npc, foe, lead, otherDefenders, swingReach);
        if (goal == null || !graph.isInsideBounds(BlockPos.containing(goal))) goal = lead;
        VanillaNavCount.note(VanillaNavCount.Site.CHASE, npc);
        // A path through a door or gate is left to the village route, which
        // opens it; footwork has nothing that would.
        if (npc.getNavigation().moveTo(goal.x, foe.getY(), goal.z, speed)) {
            return pathUsesDoorway(ctx.level(), npc.getNavigation().getPath()) ? fail(ctx) : true;
        }
        // The spot may be in a wall or over a gap; walking at the mob itself still closes in.
        if (goal != lead && npc.getNavigation().moveTo(lead.x, foe.getY(), lead.z, speed)) {
            return pathUsesDoorway(ctx.level(), npc.getNavigation().getPath()) ? fail(ctx) : true;
        }
        return fail(ctx);
    }

    /**
     * Leftovers step 9 ({@link WalkFeel#chaseMap}): the close chase on the village map. The spot
     * is chosen as for Minecraft's path (sword's length on a free side, or the zig-zag at a
     * shooter) every {@link #REPATH_TICKS}; a short route to its map tile is planned on the spot
     * and walked by the route follower, and a new one only when the spot has moved more than 1.5
     * blocks from where the route under way ends. No short route, one through a door or gate, or
     * a stuck walk fails the chase and the village route takes over, as when Minecraft's path
     * failed.
     */
    private boolean chaseOnMap(Activity.Context ctx, NpcEntity npc, NavGraph graph, LivingEntity foe, float speed,
                               boolean ranged, List<NpcEntity> otherDefenders, double swingReach, double dist) {
        flushChaseStats(ctx.level().getGameTime());
        if (chaseWalk != null && !tickChaseWalk(ctx, npc)) return false;
        if (--repathTimer > 0 && chaseWalk != null) return true;
        repathTimer = REPATH_TICKS;

        Vec3 lead = leadPosition(foe);
        Vec3 goal = ranged && dist > ZIGZAG_MIN_DIST
                ? zigzagPoint(ctx, npc, lead, dist)
                : standPoint(ctx, npc, foe, lead, otherDefenders, swingReach);
        if (goal == null || !graph.isInsideBounds(BlockPos.containing(goal))) goal = lead;
        BlockPos tile = chaseTileNear(graph, goal, foe);
        // The spot may be in a wall or over a gap; the mob's own tile still closes in.
        if (tile == null && goal != lead) tile = chaseTileNear(graph, lead, foe);
        if (tile == null) {
            chaseNoRoute++;
            return fail(ctx);
        }
        // Still heading for about the same spot: keep the route under way.
        if (chaseWalk != null && chaseTile != null && chaseTile.distSqr(tile) <= CHASE_KEEP_DIST_SQ) {
            chaseKept++;
            return true;
        }
        long started = System.nanoTime();
        AStarPathfinder.Route route = AStarPathfinder.findRoute(
                graph, npc.blockPosition(), tile, true, Set.of(), Set.of());
        chasePlanNanos += System.nanoTime() - started;
        chaseRoutes++;
        int maxTiles = (int) Math.ceil(dist * CHASE_TILES_PER_BLOCK) + CHASE_EXTRA_TILES;
        if (route == null || route.tiles().size() > maxTiles) {
            chaseNoRoute++;
            return fail(ctx);
        }
        // A door or gate on the way is left to the village route, as with Minecraft's path.
        if (routeUsesDoorway(ctx.level(), route)) {
            chaseDoorway++;
            return fail(ctx);
        }
        MoveToCommand walk = FollowWalk.forRoute(graph, npc, route, speed, true, 0, Set.of());
        if (walk == null) {
            chaseNoRoute++;
            return fail(ctx);
        }
        dropChaseWalk(npc);
        npc.getNavigation().stop();
        chaseWalk = walk;
        chaseTile = tile;
        return tickChaseWalk(ctx, npc);
    }

    /** Ticks the map chase walk; false when it got stuck, which fails the chase. */
    private boolean tickChaseWalk(Activity.Context ctx, NpcEntity npc) {
        MoveToCommand.Status status = chaseWalk.tick(npc);
        if (status == MoveToCommand.Status.RUNNING) {
            // Closing in on a mob: no slowing into the route's end.
            npc.setWalkEndEase(1.0F);
            return true;
        }
        if (status == MoveToCommand.Status.STUCK) {
            chaseStuck++;
            fail(ctx);
            return false;
        }
        // Arrived (the mob has moved on) or the route was read only in part: plan afresh now.
        dropChaseWalk(npc);
        repathTimer = 0;
        return true;
    }

    /** The routable map tile at {@code spot} (at the mob's height), inside the border; null if none. */
    @Nullable
    private static BlockPos chaseTileNear(NavGraph graph, Vec3 spot, LivingEntity foe) {
        NavNode node = graph.nearestRoutable(BlockPos.containing(spot.x, foe.getY(), spot.z), 1, 2);
        if (node == null || !graph.isInsideBounds(node.feetPos())) return null;
        return node.feetPos();
    }

    /** True when a tile of {@code route} holds a door or fence gate, as {@link #pathUsesDoorway} for Minecraft's path. */
    private static boolean routeUsesDoorway(Level level, AStarPathfinder.Route route) {
        for (NavNode node : route.tiles()) {
            BlockPos cell = node.feetPos();
            if (isDoorway(level.getBlockState(cell)) || isDoorway(level.getBlockState(cell.above()))) {
                return true;
            }
        }
        return false;
    }

    /** Lets go of the map chase walk. */
    private void dropChaseWalk(NpcEntity npc) {
        if (chaseWalk == null) return;
        chaseWalk.closeAllOpenedDoors(npc);
        chaseWalk = null;
        chaseTile = null;
        npc.setLadderClimbTarget(null);
    }

    /** About once a minute while map chases run: how many routes, their planning time, and why chases were handed on. */
    private static void flushChaseStats(long now) {
        if (chaseStatsFrom == Long.MIN_VALUE || now < chaseStatsFrom) {
            chaseStatsFrom = now;
            return;
        }
        if (now - chaseStatsFrom < CHASE_STATS_TICKS) return;
        if (chaseRoutes + chaseKept + chaseNoRoute + chaseDoorway + chaseStuck > 0) {
            double ms = chasePlanNanos / 1.0e6;
            LOGGER.info("[ChaseMap] last {} s: {} routes planned ({} ms in all, {} ms each), {} new routes not needed "
                            + "(spot barely moved); handed to the village route: {} no short route, {} door or gate, {} stuck",
                    (now - chaseStatsFrom) / 20, chaseRoutes, String.format(Locale.ROOT, "%.1f", ms),
                    String.format(Locale.ROOT, "%.3f", chaseRoutes > 0 ? ms / chaseRoutes : 0.0),
                    chaseKept, chaseNoRoute, chaseDoorway, chaseStuck);
        }
        // A quiet spell starts the next minute afresh rather than counting into it.
        chaseStatsFrom = now;
        chaseRoutes = 0;
        chaseKept = 0;
        chaseNoRoute = 0;
        chaseDoorway = 0;
        chaseStuck = 0;
        chasePlanNanos = 0L;
    }

    /** Re-plans on the next tick: after being shoved, or when the stance changes pace. */
    public void repathNow() {
        repathTimer = 0;
    }

    /** Stops steering; the caller holds the guard still or hands over to another move. */
    public void stop(Activity.Context ctx) {
        if (active) ctx.npc().getNavigation().stop();
        dropMapStep(ctx.npc());
        dropChaseWalk(ctx.npc());
        active = false;
        repathTimer = 0;
        noProgressTicks = 0;
        bestDist = Double.MAX_VALUE;
        dodgeTicks = 0;
    }

    private boolean fail(Activity.Context ctx) {
        stop(ctx);
        blockedUntil = ctx.level().getGameTime() + BLOCKED_TICKS;
        return false;
    }

    /** Where the mob will be in a few ticks, going by its motion now. */
    private static Vec3 leadPosition(LivingEntity foe) {
        Vec3 motion = foe.getDeltaMovement();
        Vec3 flat = new Vec3(motion.x, 0.0D, motion.z).scale(LEAD_TICKS);
        if (flat.lengthSqr() > MAX_LEAD * MAX_LEAD) flat = flat.normalize().scale(MAX_LEAD);
        return foe.position().add(flat);
    }

    /**
     * A spot {@link GuardCombatRules#standReach} of the swing reach from the mob's hitbox, on the
     * guard's side of it if that is free, otherwise swung round to a side no
     * other defender stands on, so two guards do not shoulder each other out of
     * reach of the same zombie.
     */
    private static Vec3 standPoint(Activity.Context ctx, NpcEntity npc, LivingEntity foe, Vec3 lead,
                                   List<NpcEntity> otherDefenders, double swingReach) {
        Vec3 away = new Vec3(npc.getX() - lead.x, 0.0D, npc.getZ() - lead.z);
        if (away.lengthSqr() < 1.0e-4) {
            Vec3 look = npc.getViewVector(1.0F);
            away = new Vec3(-look.x, 0.0D, -look.z);
        }
        away = away.normalize();
        double radius = GuardCombatRules.standReach(swingReach) + foe.getBbWidth() * 0.5D;
        Vec3 fallback = null;
        for (double angle : FLANK_ANGLES) {
            Vec3 dir = away.yRot((float) Math.toRadians(angle));
            Vec3 spot = new Vec3(lead.x + dir.x * radius, foe.getY(), lead.z + dir.z * radius);
            if (!hasRoom(ctx, npc, spot)) continue;
            if (fallback == null) fallback = spot;
            if (!isTaken(spot, otherDefenders)) return spot;
        }
        return fallback;
    }

    /** A point a few blocks towards the shooter and off to one side, the side flipping now and then. */
    private Vec3 zigzagPoint(Activity.Context ctx, NpcEntity npc, Vec3 lead, double dist) {
        if (--zigzagTimer <= 0) {
            zigzagSide = -zigzagSide;
            zigzagTimer = ZIGZAG_MIN_TICKS + npc.getRandom().nextInt(ZIGZAG_JITTER_TICKS + 1);
        }
        Vec3 toward = new Vec3(lead.x - npc.getX(), 0.0D, lead.z - npc.getZ());
        if (toward.lengthSqr() < 1.0e-4) return null;
        toward = toward.normalize();
        Vec3 side = new Vec3(-toward.z, 0.0D, toward.x).scale(zigzagSide * ZIGZAG_OFFSET);
        double step = Math.min(ZIGZAG_STEP, Math.max(1.0D, dist - 2.0D));
        Vec3 spot = new Vec3(npc.getX() + toward.x * step + side.x, npc.getY(),
                npc.getZ() + toward.z * step + side.z);
        if (hasRoom(ctx, npc, spot)) return spot;
        // Blocked on this side: try the other one before going straight in.
        zigzagSide = -zigzagSide;
        spot = new Vec3(npc.getX() + toward.x * step - side.x, npc.getY(),
                npc.getZ() + toward.z * step - side.z);
        return hasRoom(ctx, npc, spot) ? spot : null;
    }

    private static boolean isTaken(Vec3 spot, List<NpcEntity> otherDefenders) {
        for (NpcEntity other : otherDefenders) {
            double dx = other.getX() - spot.x;
            double dz = other.getZ() - spot.z;
            if (dx * dx + dz * dz <= TAKEN_SPOT_DIST_SQ) return true;
        }
        return false;
    }

    /** Room for the guard's body at {@code spot}, with ground under it. */
    private static boolean hasRoom(Activity.Context ctx, NpcEntity npc, Vec3 spot) {
        if (!ctx.level().noCollision(npc, npc.getBoundingBox().move(spot.subtract(npc.position())))) {
            return false;
        }
        BlockPos below = BlockPos.containing(spot.x, spot.y - 0.5D, spot.z);
        return !ctx.level().getBlockState(below).getCollisionShape(ctx.level(), below).isEmpty();
    }

    // =========================================================================
    // Lunge and dodge
    // =========================================================================

    /**
     * One step of a lunge during a counter-swing's wind-up: started from where
     * a shove left the guard, the swing closes the gap so the blade still meets
     * the mob. Stops once the mob is within ordinary swing reach, and short of
     * any drop. Ordinary swings plant their feet and never lunge.
     */
    public static void lungeStep(Activity.Context ctx, LivingEntity foe, double swingReach) {
        NpcEntity npc = ctx.npc();
        npc.getNavigation().stop();
        npc.setSpeed(0f);
        npc.xxa = 0f;
        npc.zza = 0f;
        Vec3 toward = new Vec3(foe.getX() - npc.getX(), 0.0D, foe.getZ() - npc.getZ());
        Vec3 motion = npc.getDeltaMovement();
        if (toward.lengthSqr() < 1.0e-4
                || GuardCombatRules.edgeDistance(npc, foe) <= swingReach
                || !npc.onGround()) {
            npc.setDeltaMovement(0.0D, motion.y, 0.0D);
            return;
        }
        toward = toward.normalize();
        BlockPos ahead = BlockPos.containing(npc.getX() + toward.x * 0.6D, npc.getY() - 0.5D,
                npc.getZ() + toward.z * 0.6D);
        if (ctx.level().getBlockState(ahead).getCollisionShape(ctx.level(), ahead).isEmpty()) {
            npc.setDeltaMovement(0.0D, motion.y, 0.0D);
            return;
        }
        npc.setDeltaMovement(toward.x * LUNGE_SPEED, motion.y, toward.z * LUNGE_SPEED);
    }

    /**
     * Steps back out of {@code foe}'s reach -- used against an axe, which a
     * shield cannot stop. Returns true while the step is still going.
     */
    public boolean dodge(Activity.Context ctx, LivingEntity foe, float speed) {
        return backOff(ctx, foe, DODGE_DIST, speed);
    }

    /**
     * Gets well clear of {@code foe} -- a creeper whose fuse is burning -- to at
     * least {@code dist} blocks from it. Returns true while still backing off.
     */
    public boolean evade(Activity.Context ctx, LivingEntity foe, double dist, float speed) {
        NpcEntity npc = ctx.npc();
        double gap = Math.sqrt(npc.distanceToSqr(foe));
        if (gap >= dist) {
            dropMapStep(npc);
            npc.getNavigation().stop();
            return true;
        }
        if (WalkFeel.stepBackMap) return backOffOnMap(ctx, foe, dist - gap + 1.0D, speed, true);
        if (backOff(ctx, foe, dist - gap + 1.0D, speed)) return true;
        // No clear spot straight back: let vanilla find any spot away from it.
        NavGraph graph = ctx.mgr().getGraph();
        Vec3 away = net.minecraft.world.entity.ai.util.DefaultRandomPos.getPosAway(
                npc, (int) Math.ceil(dist), 3, foe.position());
        if (away == null || graph == null || !graph.isInsideBounds(BlockPos.containing(away))) return false;
        if (!npc.getNavigation().moveTo(away.x, away.y, away.z, speed)) return false;
        VanillaNavCount.note(VanillaNavCount.Site.CREEPER_AWAY, npc);
        active = true;
        dodgeTicks = 8;
        return true;
    }

    private boolean backOff(Activity.Context ctx, LivingEntity foe, double stepDist, float speed) {
        if (WalkFeel.stepBackMap) return backOffOnMap(ctx, foe, stepDist, speed, false);
        NpcEntity npc = ctx.npc();
        dropMapStep(npc);
        dropChaseWalk(npc);
        NavGraph graph = ctx.mgr().getGraph();
        if (dodgeTicks > 0) {
            dodgeTicks--;
            if (!npc.getNavigation().isDone()) return true;
        }
        Vec3 away = new Vec3(npc.getX() - foe.getX(), 0.0D, npc.getZ() - foe.getZ());
        if (away.lengthSqr() < 1.0e-4 || graph == null) return false;
        away = away.normalize();
        for (double angle : FLANK_ANGLES) {
            Vec3 dir = away.yRot((float) Math.toRadians(angle));
            Vec3 spot = npc.position().add(dir.scale(stepDist));
            if (!graph.isInsideBounds(BlockPos.containing(spot)) || !hasRoom(ctx, npc, spot)) continue;
            if (npc.getNavigation().moveTo(spot.x, spot.y, spot.z, speed)) {
                VanillaNavCount.note(VanillaNavCount.Site.STEP_BACK, npc);
                // The chase that follows starts fresh: a progress mark left from
                // before the step back would count it as getting nowhere.
                active = true;
                repathTimer = 0;
                noProgressTicks = 0;
                bestDist = Double.MAX_VALUE;
                dodgeTicks = 8;
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Stepping back on the village map
    // =========================================================================

    /**
     * Leftovers step 7 ({@link WalkFeel#stepBackMap}): the step back is a map tile on
     * the same sides as before ({@link #FLANK_ANGLES}), walked by the route follower on
     * a route planned on the spot, so it starts this tick, opens no gap it cannot cross
     * and never steps off a ledge the map does not link. A tile the map only reaches the
     * long way round is no step back and is passed over. Against a creeper with no free
     * step on those sides, the farthest map tile from it on any side ({@link #WIDE_ANGLES}).
     * Each new step is chosen when the last one ends; true while one is under way.
     */
    private boolean backOffOnMap(Activity.Context ctx, LivingEntity foe, double stepDist, float speed,
                                 boolean creeper) {
        NpcEntity npc = ctx.npc();
        dropChaseWalk(npc);
        if (tickMapStep(ctx, npc)) return true;
        long now = ctx.level().getGameTime();
        if (now < mapStepLookAt) return false;
        if (pickMapStep(ctx, npc, foe, stepDist, speed, FLANK_ANGLES, 1, false)) return true;
        if (creeper && pickMapStep(ctx, npc, foe, stepDist, speed, WIDE_ANGLES, 2, true)) return true;
        mapStepLookAt = now + MAP_STEP_LOOK_AGAIN_TICKS;
        return false;
    }

    /** Ticks the map step under way; true while it goes on. */
    private boolean tickMapStep(Activity.Context ctx, NpcEntity npc) {
        if (mapStep == null) return false;
        MoveToCommand.Status status = mapStep.tick(npc);
        if (status == MoveToCommand.Status.RUNNING) {
            // A quick step: no slowing into its end, the way a Minecraft path step was walked.
            npc.setWalkEndEase(1.0F);
            return true;
        }
        if (status == MoveToCommand.Status.STUCK) {
            mapStepLookAt = ctx.level().getGameTime() + MAP_STEP_LOOK_AGAIN_TICKS;
        }
        dropMapStep(npc);
        return false;
    }

    private boolean pickMapStep(Activity.Context ctx, NpcEntity npc, LivingEntity foe, double stepDist,
                                float speed, double[] angles, int xzRadius, boolean farthestFirst) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || graph.isEmpty()) return false;
        Vec3 away = new Vec3(npc.getX() - foe.getX(), 0.0D, npc.getZ() - foe.getZ());
        if (away.lengthSqr() < 1.0e-4) return false;
        away = away.normalize();
        BlockPos feet = NavGraph.feetCell(npc);
        double gapSq = flatDistSq(npc.getX(), npc.getZ(), foe);
        List<BlockPos> tiles = new ArrayList<>();
        for (double angle : angles) {
            Vec3 dir = away.yRot((float) Math.toRadians(angle));
            BlockPos aim = BlockPos.containing(npc.getX() + dir.x * stepDist, feet.getY(),
                    npc.getZ() + dir.z * stepDist);
            NavNode node = graph.nearestRoutable(aim, xzRadius, 2);
            if (node == null) continue;
            BlockPos tile = node.feetPos();
            if (tile.equals(feet) || tiles.contains(tile) || !graph.isInsideBounds(tile)) continue;
            // Never nearer to it than where the guard stands now.
            if (flatDistSq(tile.getX() + 0.5D, tile.getZ() + 0.5D, foe) <= gapSq) continue;
            if (!ctx.model().canReach(graph, tile)) continue;
            tiles.add(tile);
        }
        if (farthestFirst) {
            tiles.sort(Comparator.comparingDouble(
                    (BlockPos t) -> flatDistSq(t.getX() + 0.5D, t.getZ() + 0.5D, foe)).reversed());
        }
        int maxTiles = (int) Math.ceil(stepDist * MAP_STEP_TILES_PER_BLOCK) + MAP_STEP_EXTRA_TILES;
        int plans = 0;
        for (BlockPos tile : tiles) {
            if (++plans > MAP_STEP_MAX_PLANS) break;
            AStarPathfinder.Route route = AStarPathfinder.findRoute(
                    graph, npc.blockPosition(), tile, true, Set.of(), Set.of());
            if (route == null || route.tiles().size() > maxTiles) continue;
            MoveToCommand walk = FollowWalk.forRoute(graph, npc, route, speed, true, 0, Set.of());
            if (walk == null) continue;
            npc.getNavigation().stop();
            mapStep = walk;
            // The chase that follows starts fresh, as after a step back on Minecraft's path.
            active = true;
            repathTimer = 0;
            noProgressTicks = 0;
            bestDist = Double.MAX_VALUE;
            tickMapStep(ctx, npc);
            return true;
        }
        return false;
    }

    /** Lets go of the map step, closing any door or gate it opened. */
    private void dropMapStep(NpcEntity npc) {
        if (mapStep == null) return;
        mapStep.closeAllOpenedDoors(npc);
        mapStep = null;
        npc.setLadderClimbTarget(null);
    }

    private static double flatDistSq(double x, double z, LivingEntity foe) {
        double dx = x - foe.getX();
        double dz = z - foe.getZ();
        return dx * dx + dz * dz;
    }
}
