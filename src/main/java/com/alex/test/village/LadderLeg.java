package com.alex.test.village;

import net.minecraft.world.entity.PathfinderMob;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Climbs one ladder section of a {@link FollowWalk} route: the floor tile before a run of rungs,
 * the rungs, and the floor tile after. Every tile is one step from the last, so it steers
 * straight from tile to tile; it never asks Minecraft's pathfinder for anything.
 *
 * <p>This is the old walker's ladder code (2026-10-17 ladder fixes, {@code /villager walkfeel
 * ladder*}), kept as it was tested when the old walker was removed (2026-10-18):
 * <ul>
 *   <li>only a vertical edge between rungs, or a rung with no floor under it
 *   ({@link WalkFeel#ladderCross}), is driven as a climb; a floor-supported ladder cell walked
 *   across is steered like ground;</li>
 *   <li>the villager is held at the rung's column and moved up or down at ladder speed, commits to
 *   an exit once its feet reach the landing's height, and lets gravity finish the landing;</li>
 *   <li>a sideways step from rung to rung keeps it at the rung's height ({@link WalkFeel#ladderSide});
 *   standing on the top rung's plate it is aimed off the plate ({@link WalkFeel#ladderPerch});
 *   a landing counts against the floor it really stands on ({@link WalkFeel#ladderLanding}).</li>
 *   <li>a ladder's top rung onto a floor-standing ladder one block up beside it is a landing,
 *   not a rung: climbed out of first, then stepped onto ({@link WalkFeel#ladderChain}).</li>
 * </ul>
 * Stuck after five seconds of under half a block a second, as before.
 */
final class LadderLeg extends MoveToCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A floor tile counts as reached this close to its middle... */
    private static final double ARRIVE_DIST_SQ = 0.85 * 0.85;
    /** ...about level with its floor... */
    private static final double ARRIVE_Y_TOLERANCE = 0.85;
    /** ...or, once climbed onto (or as the leg's last tile), this far off it. */
    private static final double CLIMBED_Y_TOLERANCE = 1.25;

    private static final double LADDER_ARRIVE_DIST_SQ = 0.4 * 0.4;
    private static final double LADDER_Y_TOLERANCE = 0.25;
    private static final double LADDER_EXIT_DIST_SQ = 0.65 * 0.65;
    private static final double LADDER_CLIMB_SPEED = 0.2;
    private static final double LADDER_DESCEND_SPEED = -0.12;
    /**
     * How far off the rung's middle, toward the open side, a villager stood on the ladder's top
     * plate is aimed. Its 0.6-wide body clears the 3/16 plate once its middle is 0.0625 off; the
     * nudge settles within 0.03 of the aim, so 0.15 leaves a clear margin.
     */
    private static final double LADDER_PERCH_NUDGE = 0.15;

    /** [LadderLeg] lines: a ladder leg with no up or down movement this many ticks is a stall. */
    private static final int    LADDER_STALL_TICKS = 10;
    /** [LadderLeg] lines: below the rung it was walking to by this much, it fell. */
    private static final double LADDER_FALL_LOG_DEPTH = 1.5;
    /** [LadderLeg] lines: at most this many for the whole server per LADDER_LOG_WINDOW ticks. */
    private static final int    LADDER_LOG_MAX = 40;
    private static final long   LADDER_LOG_WINDOW = 1200L;
    private static long ladderLogWindowStart = Long.MIN_VALUE / 2;
    private static int  ladderLogLines;

    private static final int    STAIR_STEP_ASSIST_TICKS = 12;
    private static final double STEP_UP_MIN_Y = 0.45;
    private static final double STEP_UP_MAX_Y = 1.25;
    /** Slack beyond the body's half-width and the half-block to the target's face: one tick of approach. */
    private static final double STEP_UP_FORWARD_SLACK = 0.15;
    private static final int    STEP_UP_JUMP_COOLDOWN_TICKS = 8;

    private static final int    CHECK_INTERVAL  = 20;   // 1 s position sample
    private static final double MIN_PROGRESS_SQ = 0.25; // 0.5 blocks min per sample
    private static final int    STUCK_PERIODS   = 5;    // 5 s total before STUCK
    /** Off a rung: samples without getting this much closer to the tile before it counts as stuck... */
    private static final double MIN_CLOSER_BLOCKS = 0.3;
    private static final int    NO_CLOSER_PERIODS = 8;
    /** ...or on the ground beside a tile this far above, with no climb to it, this many ticks. */
    private static final double UNREACHABLE_RISE = 1.3;
    private static final int    UNREACHABLE_TICKS = 30;

    private final float speed;

    private int currentWp;
    /** The tile this leg last reached by its own arrival test (not by {@link #startAt}); -1 none. */
    private int arrivedWp = -1;
    private int stepUpJumpCooldown;
    /** Once an exit starts, do not steer back to the ladder when Y dips. */
    private int ladderExitCommittedWp = -1;
    /** [LadderLeg] lines: the villager's height last tick, and ticks in a row without up or down movement. */
    private double ladderLastY = Double.NaN;
    private int    ladderStillTicks;
    /** [LadderLeg] lines: the waypoint each kind of line was last written for, so once per waypoint. */
    private int ladderCrossLoggedWp = -1;
    private int ladderStallLoggedWp = -1;
    private int ladderFallLoggedWp = -1;
    private int ladderPerchLoggedWp = -1;
    private int ladderChainLoggedWp = -1;
    private int ladderLedgeLoggedWp = -1;

    private int    checkTimer;
    private int    stuckPeriods;
    private double lastCheckX = Double.NaN;
    private double lastCheckY = Double.NaN;
    private double lastCheckZ = Double.NaN;
    private double bestTileDistSq = Double.NaN;
    private int    noCloserPeriods;
    private int    unreachableTicks;

    /** How this tick was steered: "ladder" (held at a rung) or "straight" (at a tile's middle). */
    private String branch = "straight";
    /** For the spy: the last ladder tick aimed at the rung's own column (climbing), not at a landing. */
    private boolean ladderAimedAtRung;
    private String stuckRule = "";

    LadderLeg(List<BlockPos> tiles, float speed) {
        super(tiles);
        this.speed = speed;
    }

    @Override
    public Status tick(PathfinderMob npc) {
        List<BlockPos> waypoints = waypoints();
        if (currentWp >= waypoints.size()) {
            WalkBody.ladderTarget(npc, null);
            return Status.DONE;
        }
        if (!(npc.level() instanceof ServerLevel level)) return Status.RUNNING;
        if (stepUpJumpCooldown > 0) stepUpJumpCooldown--;

        // Opens only doors on the stretch being walked (a trapdoor at the top of a shaft, a gate
        // beside the foot of a ladder).
        doorHandler.tick(npc, this::isDoorOnActiveSegment);

        BlockPos target = waypoints.get(currentWp);
        if (hasArrivedAt(npc, level, target)) {
            arrivedWp = currentWp;
            currentWp++;
            stepUpJumpCooldown = 0;
            ladderExitCommittedWp = -1;
            resetStuckState();
            if (currentWp >= waypoints.size()) {
                WalkBody.ladderTarget(npc, null);
                closeAllOpenedDoors(npc);
                BlockPos last = waypoints.get(waypoints.size() - 1);
                if (WalkFeel.ladderLog && isLadderAt(level, last) && hasNoFloorUnder(level, last)) {
                    ladderLog(npc, level, "walk ended on a ladder space with nothing under it at "
                            + last.toShortString() + "; it will slide down");
                }
                return Status.DONE;
            }
            target = waypoints.get(currentWp);
        }

        applyStairStepAssist(npc, level, target);

        // A ladder on a raised block is stepped onto or walked off, not climbed (ladderLedge).
        boolean ledgeStep = isLedgeStep(level, currentWp);
        boolean ladderSegment = !ledgeStep
                && (isPlannedClimbWaypoint(level, currentWp) || isLeavingLadder(npc, level));
        if (ladderSegment) {
            WalkBody.ladderTarget(npc, isRungTarget(level, currentWp) ? target : waypoints.get(currentWp - 1));
            branch = "ladder";
            driveLadderSegment(npc, level, target);
        } else {
            WalkBody.ladderTarget(npc, null);
            ladderAimedAtRung = false;
            branch = "straight";
            steerStraight(npc, level, target);
        }
        if (WalkFeel.ladderLog) logLadderLeg(npc, level, target, ladderSegment, ledgeStep);

        if (++checkTimer >= CHECK_INTERVAL) {
            checkTimer = 0;
            double nx = npc.getX(), ny = npc.getY(), nz = npc.getZ();
            if (!Double.isNaN(lastCheckX)) {
                double dx = nx - lastCheckX, dy = ny - lastCheckY, dz = nz - lastCheckZ;
                stuckPeriods = dx * dx + dy * dy + dz * dz < MIN_PROGRESS_SQ ? stuckPeriods + 1 : 0;
            }
            lastCheckX = nx;
            lastCheckY = ny;
            lastCheckZ = nz;
            // Moving is not progress: pacing back and forth by a tile it cannot reach moves half
            // a block every second. Count seconds that end no nearer than the nearest yet.
            double wx = target.getX() + 0.5 - nx;
            double wz = target.getZ() + 0.5 - nz;
            double wy = standHeight(npc, target) - ny;
            double distSq = wx * wx + wy * wy + wz * wz;
            if (Double.isNaN(bestTileDistSq)) {
                bestTileDistSq = distSq;
            } else if (Math.sqrt(bestTileDistSq) - Math.sqrt(distSq) >= MIN_CLOSER_BLOCKS) {
                bestTileDistSq = distSq;
                noCloserPeriods = 0;
            } else {
                noCloserPeriods++;
            }
        }
        // A tile well above the head, on the ground beside it, with no climb to it: fell off.
        if (!ladderSegment && npc.onGround() && !npc.isInWater()
                && standHeight(npc, target) - npc.getY() > UNREACHABLE_RISE
                && horizontalDistSq(npc, target) <= 1.5 * 1.5) {
            unreachableTicks++;
        } else {
            unreachableTicks = 0;
        }
        if (stuckPeriods >= STUCK_PERIODS) {
            stuckRule = "stood still for " + STUCK_PERIODS + " s (under half a block a second)";
            return Status.STUCK;
        }
        if (!ladderSegment && (unreachableTicks >= UNREACHABLE_TICKS || noCloserPeriods >= NO_CLOSER_PERIODS)) {
            stuckRule = unreachableTicks >= UNREACHABLE_TICKS
                    ? "under a tile more than " + UNREACHABLE_RISE + " blocks up for " + UNREACHABLE_TICKS + " ticks"
                    : "no closer to its tile for " + NO_CLOSER_PERIODS + " s";
            return Status.STUCK;
        }
        return Status.RUNNING;
    }

    private void resetStuckState() {
        checkTimer = 0;
        stuckPeriods = 0;
        lastCheckX = lastCheckY = lastCheckZ = Double.NaN;
        bestTileDistSq = Double.NaN;
        noCloserPeriods = 0;
        unreachableTicks = 0;
    }

    private static double horizontalDistSq(PathfinderMob npc, BlockPos target) {
        double dx = npc.getX() - (target.getX() + 0.5);
        double dz = npc.getZ() - (target.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    private boolean hasArrivedAt(PathfinderMob npc, ServerLevel level, BlockPos target) {
        double dx = npc.getX() - (target.getX() + 0.5);
        double dz = npc.getZ() - (target.getZ() + 0.5);
        double xzDistSq = dx * dx + dz * dz;
        double dy = npc.getY() - target.getY();
        if (isLadderAt(level, target)) {
            if (xzDistSq > LADDER_ARRIVE_DIST_SQ) return false;
            if (Math.abs(dy) <= LADDER_Y_TOLERANCE) return true;
            // A rung standing on a low floor (a bottom slab) is reached stood on that floor, half
            // a block under the rung's own height (ladderSlab). On a full block the two are equal.
            // Never more than half a block under: the same reach as isUnderRungOnLowFloor, so a
            // villager counted as there is always one the leg can still push up the ladder.
            if (!WalkFeel.ladderSlab || !npc.onGround() || hasNoFloorUnder(level, target)) return false;
            double floorY = standHeight(npc, target);
            return target.getY() - floorY <= 0.5 + 1.0E-3
                    && Math.abs(npc.getY() - floorY) <= LADDER_Y_TOLERANCE;
        }
        if (previousWaypointIsLadder(level)) {
            // Against the height the villager really stands at on the landing (ladderLanding).
            // The waypoint's own Y is half a block above a bottom slab, so a villager stood on a
            // slab landing never counted as off the ladder. On a full block the two are the same.
            double landDy = WalkFeel.ladderLanding ? npc.getY() - standHeight(npc, target) : dy;
            return xzDistSq <= LADDER_EXIT_DIST_SQ
                    && Math.abs(landDy) <= LADDER_Y_TOLERANCE
                    && npc.onGround();
        }
        if (xzDistSq >= ARRIVE_DIST_SQ) return false;
        double standDy = npc.getY() - standHeight(npc, target);
        if (Math.abs(standDy) <= ARRIVE_Y_TOLERANCE) return true;
        // A tile still a block above is reached only once climbed, unless the leg ends there.
        return Math.abs(standDy) <= CLIMBED_Y_TOLERANCE
                && (standDy > 0 || currentWp + 1 >= waypoints().size());
    }

    private boolean previousWaypointIsLadder(ServerLevel level) {
        return currentWp > 0 && isPlannedClimbWaypoint(level, currentWp - 1);
    }

    /**
     * A rung climbed rather than walked: a floor-supported ladder can be part of a level
     * corridor or a one-block step, so only a vertical edge between rungs is climbed, or a rung
     * with nothing under it ({@link WalkFeel#ladderCross}), crossed sideways when the ladder is
     * the only way over a shaft. Walked as ground there, nothing held the villager up.
     */
    private boolean isPlannedClimbWaypoint(ServerLevel level, int index) {
        List<BlockPos> waypoints = waypoints();
        BlockPos rung = waypoints.get(index);
        if (!isLadderAt(level, rung)) return false;
        return hasVerticalRungEdge(level, index)
                || (WalkFeel.ladderCross && hasNoFloorUnder(level, rung));
    }

    private boolean hasVerticalRungEdge(ServerLevel level, int index) {
        List<BlockPos> waypoints = waypoints();
        BlockPos rung = waypoints.get(index);
        return (index > 0 && isVerticalRungEdge(level, waypoints.get(index - 1), rung))
                || (index + 1 < waypoints.size()
                && isVerticalRungEdge(level, rung, waypoints.get(index + 1)));
    }

    /**
     * The step to {@code index} leaves the top of one ladder for another ladder one block up in
     * the column beside it, standing on a floor ({@link WalkFeel#ladderChain}). The graph links
     * the two ({@code NavGraph.scanLadderNeighbour}, from the upper rung's side). The floor under
     * the upper rung blocks a step across at the lower rung's height, so that rung is a landing:
     * climbed out of first, like any floor. With nothing under it, stepping across low and
     * climbing the new ladder already works, so it stays a rung.
     */
    private boolean isClimbOutOntoRung(ServerLevel level, int index) {
        if (!WalkFeel.ladderChain || index <= 0) return false;
        List<BlockPos> waypoints = waypoints();
        BlockPos from = waypoints.get(index - 1);
        BlockPos to = waypoints.get(index);
        return to.getY() == from.getY() + 1
                && Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ()) == 1
                && isLadderAt(level, from) && isLadderAt(level, to)
                && !hasNoFloorUnder(level, to);
    }

    /**
     * The step to {@code index} is a plain one-block step between a ladder that stands on a
     * raised block and the floor beside it, one block lower ({@link WalkFeel#ladderLedge}): up
     * from that floor onto the rung, or down off the rung onto that floor. The block under the
     * rung is in the way of a climb in the ladder's column, so the step is walked: jumped up
     * like any one-block rise, or walked off and dropped. Up only within a jump's reach.
     */
    private boolean isLedgeStep(ServerLevel level, int index) {
        if (!WalkFeel.ladderLedge || index <= 0) return false;
        List<BlockPos> waypoints = waypoints();
        BlockPos from = waypoints.get(index - 1);
        BlockPos to = waypoints.get(index);
        if (Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ()) != 1) return false;
        boolean fromRung = isLadderAt(level, from);
        boolean toRung = isLadderAt(level, to);
        if (!fromRung && toRung && to.getY() == from.getY() + 1) {
            return !hasNoFloorUnder(level, to)
                    && NavGraph.standY(level, to) - NavGraph.standY(level, from) <= STEP_UP_MAX_Y;
        }
        if (fromRung && !toRung && to.getY() == from.getY() - 1) {
            return !hasNoFloorUnder(level, from);
        }
        return false;
    }

    /** A rung to climb to or hold at; a rung climbed out onto ({@link #isClimbOutOntoRung}) is a landing. */
    private boolean isRungTarget(ServerLevel level, int index) {
        return isLadderAt(level, waypoints().get(index)) && !isClimbOutOntoRung(level, index);
    }

    /**
     * On the ladder, for the leg's up and down push: feet in a rung or just above one, or
     * ({@link WalkFeel#ladderSlab}) stood on a low floor right under one.
     */
    private static boolean isTouchingLadder(PathfinderMob npc, ServerLevel level) {
        BlockPos feet = BlockPos.containing(npc.getX(), npc.getY(), npc.getZ());
        if (isLadderAt(level, feet) || isLadderAt(level, feet.below())) return true;
        return WalkFeel.ladderSlab && isUnderRungOnLowFloor(npc, level, feet);
    }

    /**
     * Feet inside the block under a rung that stands on that block, at most half a block under
     * the rung: the block is a low floor (a bottom slab), the villager stands on it, and its feet
     * cell is the floor's, not the ladder's. On a full block the feet are never in that cell.
     */
    private static boolean isUnderRungOnLowFloor(PathfinderMob npc, ServerLevel level, BlockPos feet) {
        BlockPos rung = feet.above();
        return isLadderAt(level, rung) && !hasNoFloorUnder(level, rung)
                && rung.getY() - npc.getY() <= 0.5 + 1.0E-3;
    }

    /** The graph's own test (see NavNode.ladderFloor), so both agree on which rungs stand on a floor. */
    private static boolean hasNoFloorUnder(ServerLevel level, BlockPos rung) {
        return !NavGraph.isSolidSurface(level, rung.getX(), rung.getY() - 1, rung.getZ());
    }

    private static boolean isVerticalRungEdge(ServerLevel level, BlockPos from, BlockPos to) {
        return from.getX() == to.getX() && from.getZ() == to.getZ()
                && from.getY() != to.getY()
                && isLadderAt(level, from) && isLadderAt(level, to);
    }

    private boolean isLeavingLadder(PathfinderMob npc, ServerLevel level) {
        if (!previousWaypointIsLadder(level)) return false;
        BlockPos feet = BlockPos.containing(npc.getX(), npc.getY(), npc.getZ());
        return !npc.onGround()
                || isLadderAt(level, feet)
                || isLadderAt(level, feet.below());
    }

    private void driveLadderSegment(PathfinderMob npc, ServerLevel level, BlockPos target) {
        // A rung climbed out onto is driven as a landing: every !targetRung exit below applies.
        boolean targetRung = isRungTarget(level, currentWp);
        BlockPos previous = currentWp > 0 ? waypoints().get(currentWp - 1) : target;
        // Stay centred until the feet clear the upper landing, or descend to the lower landing.
        // Commit to the exit once that height is reached: recentering after a small gravity dip
        // makes the mob climb up and down the same last rung indefinitely.
        if (!targetRung && ladderExitCommittedWp != currentWp) {
            double exitY = target.getY() > previous.getY()
                    ? target.getY() + 0.02
                    : target.getY() == previous.getY()
                    ? target.getY() - 0.02 : target.getY() + 0.1;
            boolean exitHeightReached = target.getY() >= previous.getY()
                    ? npc.getY() >= exitY : npc.getY() <= exitY;
            if (exitHeightReached) ladderExitCommittedWp = currentWp;
        }
        BlockPos horizontalGoal = !targetRung && ladderExitCommittedWp != currentWp
                ? previous : target;
        // On the rung for the spy: aimed at a rung's column and already inside it. Stepping onto
        // the ladder from the floor beside it is "on or off". A rung climbed out onto (ladderchain)
        // is a landing, so stood in it counts as off the rung.
        ladderAimedAtRung = (horizontalGoal.equals(target) ? targetRung : isLadderAt(level, horizontalGoal))
                && Math.abs(npc.getX() - (horizontalGoal.getX() + 0.5)) < 0.5
                && Math.abs(npc.getZ() - (horizontalGoal.getZ() + 0.5)) < 0.5;
        double aimX = horizontalGoal.getX() + 0.5;
        double aimZ = horizontalGoal.getZ() + 0.5;

        // Coming down a ladder through a hole in the floor, the top rung's plate reaches the
        // floor's height, and the move control stops pushing 0.1 from its aim. A villager that
        // coasted toward the wall came to rest with its body over the plate, standing on it, and
        // the push down could not move it. Aim a little toward the ladder's open side instead,
        // until it is off the plate (ladderPerch).
        if (WalkFeel.ladderPerch && targetRung) {
            BlockState perch = ladderTopUnderFeet(npc, level, target);
            if (perch != null) {
                Direction open = perch.getValue(LadderBlock.FACING);
                aimX += open.getStepX() * LADDER_PERCH_NUDGE;
                aimZ += open.getStepZ() * LADDER_PERCH_NUDGE;
                if (WalkFeel.ladderLog && ladderPerchLoggedWp != currentWp) {
                    ladderPerchLoggedWp = currentWp;
                    ladderLog(npc, level, String.format(Locale.ROOT,
                            "stood on the top of the ladder at %s, nudged toward its open side (%s)",
                            npc.blockPosition().below().toShortString(), open.getSerializedName()));
                }
            }
        }

        npc.getNavigation().stop();
        npc.getMoveControl().setWantedPosition(aimX, npc.getY(), aimZ, speed);

        boolean touchingLadder = isTouchingLadder(npc, level);
        double dx = npc.getX() - (previous.getX() + 0.5);
        double dz = npc.getZ() - (previous.getZ() + 0.5);
        if (targetRung) {
            dx = npc.getX() - (target.getX() + 0.5);
            dz = npc.getZ() - (target.getZ() + 0.5);
        }
        // The final sideways step can leave the ladder cell before the feet reach the upper
        // floor. Allow a short correction during that crossing.
        double landingDx = npc.getX() - (target.getX() + 0.5);
        double landingDz = npc.getZ() - (target.getZ() + 0.5);
        boolean crossingUpperExit = !targetRung && ladderExitCommittedWp == currentWp
                && target.getY() >= previous.getY() && !npc.onGround()
                && Math.abs(npc.getY() - target.getY()) <= 1.0
                && landingDx * landingDx + landingDz * landingDz <= 1.5 * 1.5;
        // Stepping sideways from one rung to the rung beside it, level with it, the villager is a
        // whole block from the new rung's centre when the step starts. Its feet are on one ladder
        // or the other all the way across, so its height is held (ladderSide).
        boolean sidewaysRungStep = WalkFeel.ladderSide && targetRung && currentWp > 0
                && isLadderAt(level, previous) && previous.getY() == target.getY()
                && Math.abs(previous.getX() - target.getX())
                + Math.abs(previous.getZ() - target.getZ()) == 1;
        if ((!touchingLadder || dx * dx + dz * dz > LADDER_EXIT_DIST_SQ)
                && !crossingUpperExit
                && !(sidewaysRungStep && touchingLadder)) return;

        double remainingY = target.getY() - npc.getY();
        if (!targetRung && ladderExitCommittedWp == currentWp) {
            if (target.getY() >= previous.getY()) {
                boolean overLanding = Math.abs(landingDx) < 0.4
                        && Math.abs(landingDz) < 0.4;
                // Let gravity finish the landing. Writing zero here every tick holds the villager
                // in midair; a positive exit velocity also makes it continue climbing past the
                // chosen floor.
                var motion = npc.getDeltaMovement();
                if (npc.onGround() || overLanding || remainingY <= -0.02) {
                    if (motion.y > 0.0) npc.setDeltaMovement(motion.x, 0.0, motion.z);
                    return;
                }
                npc.setDeltaMovement(motion.x,
                        Math.min(LADDER_CLIMB_SPEED, remainingY + 0.02), motion.z);
            }
            // A lower exit is already at landing height when committed.
            return;
        }
        double vertical;
        if (!targetRung) {
            vertical = target.getY() >= previous.getY()
                    ? Math.min(LADDER_CLIMB_SPEED, Math.max(0.0, remainingY + 0.02))
                    : Math.max(LADDER_DESCEND_SPEED, Math.min(0.0, remainingY - 0.1));
        } else if (remainingY > LADDER_Y_TOLERANCE) {
            vertical = Math.min(LADDER_CLIMB_SPEED, remainingY);
        } else if (remainingY < -LADDER_Y_TOLERANCE) {
            vertical = Math.max(LADDER_DESCEND_SPEED, remainingY);
        } else {
            vertical = 0.0;
        }
        var motion = npc.getDeltaMovement();
        npc.setDeltaMovement(motion.x, vertical, motion.z);
    }

    /**
     * The ladder whose plate top the villager stands on while it should be going down to
     * {@code rung} below it, or null. Only that: standing on the ground, its feet cell no ladder,
     * the ladder straight under its feet in the rung's column, and its body over that plate.
     */
    @Nullable
    private static BlockState ladderTopUnderFeet(PathfinderMob npc, ServerLevel level, BlockPos rung) {
        if (!npc.onGround() || rung.getY() >= npc.getY() - LADDER_Y_TOLERANCE) return null;
        BlockPos feet = npc.blockPosition();
        if (isLadderAt(level, feet)) return null;
        BlockPos under = feet.below();
        if (under.getX() != rung.getX() || under.getZ() != rung.getZ()) return null;
        BlockState state = level.getBlockState(under);
        if (!state.is(Blocks.LADDER)) return null;
        VoxelShape shape = state.getCollisionShape(level, under);
        if (shape.isEmpty()) return null;
        AABB plate = shape.bounds().move(under);
        return npc.getBoundingBox().move(0.0, -0.05, 0.0).intersects(plate) ? state : null;
    }

    /** At a floor tile: straight at its middle, at its floor's height (so the move control steps or jumps up). */
    private void steerStraight(PathfinderMob npc, ServerLevel level, BlockPos target) {
        npc.getNavigation().stop();
        npc.getMoveControl().setWantedPosition(
                target.getX() + 0.5, standHeight(npc, target), target.getZ() + 0.5, speed);
        stepUpJumpAssist(npc, target);
    }

    /**
     * Jumps a villager pressed against a one-block rise onto {@code target}: the move control
     * jumps by itself once close enough to the wanted height, this only tops it up. It fires only
     * on genuine contact (Minecraft's own {@code horizontalCollision}) within a body's reach of
     * the target's face, never on the approach.
     */
    private void stepUpJumpAssist(PathfinderMob npc, BlockPos target) {
        if (stepUpJumpCooldown > 0 || !npc.onGround() || npc.isInWaterOrBubble()) return;
        double dy = standHeight(npc, target) - npc.getY();
        if (dy < STEP_UP_MIN_Y || dy > STEP_UP_MAX_Y) return;
        if (!npc.horizontalCollision) return;
        double dx = npc.getX() - (target.getX() + 0.5D);
        double dz = npc.getZ() - (target.getZ() + 0.5D);
        double reach = 0.5D + npc.getBbWidth() * 0.5D + STEP_UP_FORWARD_SLACK;
        if (dx * dx + dz * dz > reach * reach) return;
        npc.jumpFromGround();
        stepUpJumpCooldown = STEP_UP_JUMP_COOLDOWN_TICKS;
    }

    /**
     * Stairs are walked up their half steps, so the stair assist (no hops on stair shapes) is on
     * near one; except for the jump off the top of a staircase onto a floor a block up.
     */
    private void applyStairStepAssist(PathfinderMob npc, ServerLevel level, BlockPos target) {
        if (!isStairAt(level, target.below()) && !isStairAt(level, target)) {
            double rise = standHeight(npc, target) - npc.getY();
            if (rise > npc.maxUpStep() && rise <= STEP_UP_MAX_Y) {
                WalkBody.cancelStairAssist(npc);
                return;
            }
        }
        BlockPos npcFeet = BlockPos.containing(npc.getX(), npc.getY() - 0.05D, npc.getZ());
        if (isStairAt(level, target.below())
                || isStairAt(level, target)
                || isStairAt(level, npcFeet)
                || isStairAt(level, npcFeet.below())) {
            WalkBody.stairAssist(npc, STAIR_STEP_ASSIST_TICKS);
        }
    }

    /**
     * The [LadderLeg] lines (WalkFeel.ladderLog). Reads only: nothing here steers. Each kind is
     * written once per waypoint.
     */
    private void logLadderLeg(PathfinderMob npc, ServerLevel level, BlockPos target, boolean ladderSegment,
                              boolean ledgeStep) {
        List<BlockPos> waypoints = waypoints();
        boolean targetRung = isRungTarget(level, currentWp);

        if (ledgeStep && ladderLedgeLoggedWp != currentWp) {
            ladderLedgeLoggedWp = currentWp;
            ladderLog(npc, level, String.format(Locale.ROOT, targetRung
                            ? "stepping up from %s onto the ladder on a raised block at %s (wp %d/%d)"
                            : "stepping down off the ladder on a raised block at %s onto %s (wp %d/%d)",
                    waypoints.get(currentWp - 1).toShortString(), target.toShortString(),
                    currentWp + 1, waypoints.size()));
        }

        if (ladderSegment && !targetRung && isLadderAt(level, target) && ladderChainLoggedWp != currentWp) {
            ladderChainLoggedWp = currentWp;
            ladderLog(npc, level, String.format(Locale.ROOT,
                    "climbing out of the ladder at %s onto the ladder beside it at %s, a block up (wp %d/%d)",
                    waypoints.get(currentWp - 1).toShortString(), target.toShortString(),
                    currentWp + 1, waypoints.size()));
        }

        if (ladderSegment && targetRung && ladderCrossLoggedWp != currentWp
                && !hasVerticalRungEdge(level, currentWp) && hasNoFloorUnder(level, target)) {
            ladderCrossLoggedWp = currentWp;
            ladderLog(npc, level, String.format(Locale.ROOT,
                    "crossing a ladder sideways at %s (wp %d/%d), held at the rung's height",
                    target.toShortString(), currentWp + 1, waypoints.size()));
        }

        // Fell: walking to a rung that is not driven as a climb, and well below it.
        if (targetRung && !ladderSegment && ladderFallLoggedWp != currentWp
                && npc.getY() < target.getY() - LADDER_FALL_LOG_DEPTH) {
            ladderFallLoggedWp = currentWp;
            ladderLog(npc, level, String.format(Locale.ROOT,
                    "fell %.1f blocks below the rung %s (wp %d/%d) it was walking to, which is not driven as a climb",
                    target.getY() - npc.getY(), target.toShortString(), currentWp + 1, waypoints.size()));
        }

        // Stall: on a ladder, still needing to go up or down, and not moving.
        BlockPos feet = BlockPos.containing(npc.getX(), npc.getY(), npc.getZ());
        double y = npc.getY();
        boolean needsVertical = ladderSegment
                && (targetRung ? Math.abs(target.getY() - y) > LADDER_Y_TOLERANCE
                        : ladderExitCommittedWp != currentWp)
                && isTouchingLadder(npc, level);
        if (needsVertical && !Double.isNaN(ladderLastY) && Math.abs(y - ladderLastY) < 0.01) {
            ladderStillTicks++;
        } else {
            ladderStillTicks = 0;
        }
        ladderLastY = y;
        if (ladderStillTicks >= LADDER_STALL_TICKS && ladderStallLoggedWp != currentWp) {
            ladderStallLoggedWp = currentWp;
            BlockPos rung = targetRung ? target : waypoints.get(currentWp - 1);
            ladderLog(npc, level, String.format(Locale.ROOT,
                    "no up or down movement for %.1f s at (%.2f, %.2f, %.2f), wp %d/%d %s, on ground %s, "
                            + "at feet %s, under feet %s, off the rung's centre (%.2f, %.2f), "
                            + "stood on the ladder top %s, steered %s",
                    ladderStillTicks / 20.0, npc.getX(), y, npc.getZ(),
                    currentWp + 1, waypoints.size(), target.toShortString(), npc.onGround(),
                    blockName(level, feet), blockName(level, feet.below()),
                    npc.getX() - (rung.getX() + 0.5), npc.getZ() - (rung.getZ() + 0.5),
                    targetRung && ladderTopUnderFeet(npc, level, target) != null ? "yes" : "no",
                    branch));
        }
    }

    private static String blockName(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
    }

    /** One [LadderLeg] line, at most LADDER_LOG_MAX for the whole server per LADDER_LOG_WINDOW ticks. */
    private static void ladderLog(PathfinderMob npc, ServerLevel level, String what) {
        long now = level.getGameTime();
        if (now < ladderLogWindowStart || now - ladderLogWindowStart >= LADDER_LOG_WINDOW) {
            ladderLogWindowStart = now;
            ladderLogLines = 0;
        }
        if (++ladderLogLines > LADDER_LOG_MAX) return;
        // The villager's id, as the other lines print it, not the body's own UUID.
        LOGGER.info("[LadderLeg] {} ({}) {}",
                WalkBody.logId(npc),
                WalkBody.logRole(npc), what);
    }

    /** A door on the stretch to the current tile, or on the one after it. */
    private boolean isDoorOnActiveSegment(BlockPos lowerDoorPos) {
        List<BlockPos> waypoints = waypoints();
        if (currentWp >= waypoints.size()) return false;
        BlockPos from = currentWp > 0 ? waypoints.get(currentWp - 1) : waypoints.get(currentWp);
        BlockPos target = waypoints.get(currentWp);
        if (passesDoor(from, target, lowerDoorPos)) return true;
        return currentWp + 1 < waypoints.size()
                && passesDoor(target, waypoints.get(currentWp + 1), lowerDoorPos);
    }

    /** Each step of a leg is one tile, so a door is on it when it stands in either tile's column. */
    private static boolean passesDoor(BlockPos from, BlockPos to, BlockPos door) {
        for (BlockPos tile : new BlockPos[] { from, to }) {
            if (tile.getX() == door.getX() && tile.getZ() == door.getZ()
                    && Math.abs(door.getY() - tile.getY()) <= 1) {
                return true;
            }
        }
        return false;
    }

    private static double standHeight(PathfinderMob npc, BlockPos feet) {
        return NavGraph.standY(npc.level(), feet);
    }

    private static boolean isStairAt(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof StairBlock;
    }

    private static boolean isLadderAt(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.LADDER);
    }

    // -------------------------------------------------------------------------
    // What the rest of the mod reads from a walk

    @Override
    @Nullable
    public BlockPos currentWaypoint() {
        List<BlockPos> waypoints = waypoints();
        return currentWp >= 0 && currentWp < waypoints.size() ? waypoints.get(currentWp) : null;
    }

    @Override
    public int currentWaypointIndex() { return currentWp; }

    /**
     * The step the leg was on when it stopped: the tile it last reached and the tile it was going
     * to. Null when it never reached the tile before that one (joined part-way, or stuck before
     * its first tile), so the step was not really tried. For {@link WalkFeel#ladderBlame}.
     */
    @Nullable
    BlockPos[] stuckStep() {
        List<BlockPos> waypoints = waypoints();
        if (currentWp <= 0 || currentWp >= waypoints.size() || arrivedWp != currentWp - 1) return null;
        return new BlockPos[] { waypoints.get(currentWp - 1), waypoints.get(currentWp) };
    }

    /** The route follower decides what a ladder stuck means; the leg never blames its step itself. */
    @Override
    public boolean stuckOffRoute() { return false; }

    @Override
    public String stuckRule() { return stuckRule; }

    @Override
    public String spyHops() { return "0 (none)"; }

    @Override
    String spyMode() { return branch; }

    @Override
    boolean spyThreadingDoor() { return false; }

    @Override
    boolean spyLadderOnRung() { return ladderAimedAtRung; }

    @Override
    public void describeTo(com.alex.test.village.stuckwatch.BlackBox.Frame f) {
        f.route = waypoints();
        f.wp = currentWp;
        f.wpCount = waypoints().size();
        BlockPos target = currentWaypoint();
        f.wpPos = target == null ? com.alex.test.village.stuckwatch.BlackBox.NONE : target.asLong();
        f.steer = branch;
        f.still = stuckPeriods;
        f.stillOf = STUCK_PERIODS;
    }

    @Override
    public void startAt(int index) {
        currentWp = Mth.clamp(index, 0, Math.max(0, waypoints().size() - 1));
    }

    @Override
    public String debugState(PathfinderMob npc) {
        BlockPos target = currentWaypoint();
        if (target == null) {
            return String.format(Locale.ROOT, "wp=done/%d npc=(%.2f,%.2f,%.2f)",
                    waypoints().size(), npc.getX(), npc.getY(), npc.getZ());
        }
        return String.format(Locale.ROOT,
                "wp=%d/%d mode=%s target=(%d,%d,%d) npc=(%.2f,%.2f,%.2f) stuckPeriods=%d",
                currentWp + 1, waypoints().size(), branch,
                target.getX(), target.getY(), target.getZ(),
                npc.getX(), npc.getY(), npc.getZ(), stuckPeriods);
    }
}
