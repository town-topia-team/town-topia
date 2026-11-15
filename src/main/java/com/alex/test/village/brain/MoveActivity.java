package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.villagers.lumberjack.behavior.LumberjackActivity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import java.util.Set;

/**
 * Moves the villager to a destination via the village nav graph.
 *
 * <p>Issues an async A* request on {@link #start}, then polls arrival each
 * tick. If the villager stops moving without arriving for {@value #STUCK_TIMEOUT}
 * ticks the activity fails so the caller can try another target.
 *
 * <p>Used as a composable sub-activity inside {@link LumberjackActivity} and
 * as the movement engine delegated to by leisure activities.
 */
public final class MoveActivity extends Activity {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double ARRIVE_DIST_SQ = 4.0; // ~2 blocks
    private static final double DETAILED_ARRIVE_XZ_DIST_SQ = 1.0;
    private static final double DETAILED_ARRIVE_Y_TOLERANCE = 1.25;
    private static final int    STUCK_TIMEOUT  = 200; // 10 s
    /** Times a walk dropped by a water escape is asked for again before the timeout decides. */
    private static final int    MAX_WATER_RESUMES = 3;
    /** A swim-out still going after this long gets the walk asked for again without waiting for its end. */
    private static final int    WATER_WAIT_TICKS = 60; // 3 s

    private final BlockPos destination;
    private final float    speed;
    private final boolean  detailedPath;
    private final boolean  preferExactCell;
    private final Set<Long> excludedCells;

    private int stuckTimer = 0;
    private boolean sawWaterEscape = false;
    private int waterWaitTicks = 0;
    private int waterResumes = 0;

    public MoveActivity(BlockPos destination, float speed) {
        this(destination, speed, false);
    }

    public MoveActivity(BlockPos destination, float speed, boolean detailedPath) {
        this(destination, speed, detailedPath, false);
    }

    /** Try to enter the destination cell before accepting the route's normal arrival radius. */
    public MoveActivity(BlockPos destination, float speed, boolean detailedPath,
                        boolean preferExactCell) {
        this(destination, speed, detailedPath, preferExactCell, Set.of());
    }

    public MoveActivity(BlockPos destination, float speed, boolean detailedPath,
                        boolean preferExactCell, Set<Long> excludedCells) {
        this.destination  = destination;
        this.speed        = speed;
        this.detailedPath = detailedPath;
        this.preferExactCell = preferExactCell;
        this.excludedCells = Set.copyOf(excludedCells);
    }

    @Override
    protected void start(Context ctx) {
        NavGraph g = ctx.mgr().getGraph();
        if (g != null) {
            if (!excludedCells.isEmpty()) {
                ctx.model().requestMoveToDetailedAvoiding(g, destination, speed, excludedCells);
            } else if (detailedPath) {
                ctx.model().requestMoveToDetailed(g, destination, speed);
            } else {
                ctx.model().requestMoveTo(g, destination, speed);
            }
        }
    }

    @Override
    protected Status tick(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (npc == null) return Status.FAILED;

        // Not arrived while still on the ladder part of this same walk. The
        // landing beside a ladder's top counts as "near" by distance while the
        // villager is still on the rungs or stepping off them; ending the walk
        // there dropped the ladder push and the villager slid back down
        // (guards climbing to a raised post fell and climbed again).
        boolean onLadder = npc.isOnLadderLeg() && ctx.model().isMoving();
        if (!onLadder && hasArrived(npc)) {
            ctx.model().stopMoving();
            return Status.DONE;
        }

        // Detailed navigation can finish just outside a plot's cell. It has
        // still made the requested attempt, so the farmer may use its nearby
        // fallback stance and face the crop rather than waiting until stuck.
        if (preferExactCell && ctx.model().hasRecentMoveSuccess(destination)) {
            ctx.model().stopMoving();
            return Status.DONE;
        }

        if (ctx.model().hasRecentMoveFailure(destination)) return Status.FAILED;

        // Falling into water hands the villager's movement to the swim-out,
        // which drops this walk for a route to the nearest dry tile, and
        // nothing gave the walk back afterwards. A miner dropped through a
        // stair trap stood there until the timeout below failed the walk,
        // then set its dig spot aside as unreachable. Even a landing on a
        // waterlogged block, shallow enough to wade, starts the swim-out
        // for the moment the villager falls through the water. So once the
        // swim-out is over, ask for the same walk again. The timeout still
        // runs meanwhile: in open water with no dry tile in reach the
        // swim-out stands still, and the walk should fail as before.
        //
        // Nor does it wait for the swim-out's end past WATER_WAIT_TICKS. At
        // the foot of a one-block ledge the swim-out stops pushing whenever
        // the villager's feet bob above the surface, so it hovers against
        // the ledge and the swim-out never ends. An ordinary walk hops out
        // of water onto a ledge: a farmer whose stroll picked a new spot was
        // out in two seconds while the miner beside it waited on.
        if (ctx.model().isEscapingWater()) {
            sawWaterEscape = true;
            if (++waterWaitTicks >= WATER_WAIT_TICKS) {
                waterWaitTicks = 0;
                if (!walkingToDestination(ctx)
                        && askAgainAfterWater(ctx, npc, "the swim-out has run 3 s")) {
                    return Status.RUNNING;
                }
            }
        } else if (sawWaterEscape) {
            sawWaterEscape = false;
            waterWaitTicks = 0;
            if (!walkingToDestination(ctx)
                    && askAgainAfterWater(ctx, npc, "the swim-out is over")) {
                return Status.RUNNING;
            }
        }

        if (!ctx.model().isMoving()) {
            if (++stuckTimer >= STUCK_TIMEOUT) return Status.FAILED;
        } else {
            stuckTimer = 0;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) {
        ctx.model().stopMoving();
    }

    private boolean walkingToDestination(Context ctx) {
        return ctx.model().isMoving() && destination.equals(ctx.model().moveGoal());
    }

    /** Asks for this walk again after a water escape dropped it; false once the allowance is used up. */
    private boolean askAgainAfterWater(Context ctx, NpcEntity npc, String why) {
        if (waterResumes >= MAX_WATER_RESUMES) return false;
        waterResumes++;
        LOGGER.info("[move] {} walk to {} was dropped by a water escape; {}, asking again from {} ({}/{}).",
                ctx.model().npcId.toString().substring(0, 8), destination.toShortString(), why,
                npc.blockPosition().toShortString(), waterResumes, MAX_WATER_RESUMES);
        start(ctx);
        stuckTimer = 0;
        return true;
    }

    private boolean hasArrived(NpcEntity npc) {
        if (excludedCells.contains(NavGraph.feetCell(npc).asLong())) return false;
        if (preferExactCell) return NavGraph.feetCell(npc).equals(destination);
        if (!detailedPath) return NavGraph.feetCell(npc).distSqr(destination) <= ARRIVE_DIST_SQ;

        double dx = npc.getX() - (destination.getX() + 0.5D);
        double dz = npc.getZ() - (destination.getZ() + 0.5D);
        return dx * dx + dz * dz <= DETAILED_ARRIVE_XZ_DIST_SQ
                && Math.abs(npc.getY() - destination.getY()) <= DETAILED_ARRIVE_Y_TOLERANCE;
    }

    @Override
    public String name() { return "move"; }
}
