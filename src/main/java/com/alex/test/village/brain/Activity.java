package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Random;

/**
 * One unit of villager behaviour ("eat", "work", "go home", ...).
 *
 * <p>An activity is owned and ticked by a {@link VillagerBrain}. It runs until
 * it returns {@link Status#DONE} or {@link Status#FAILED}, or until the brain
 * interrupts it (override or forced conversation) — in which case {@link #stop}
 * is called so the activity can clean up (release claims, stop navigation).
 */
public abstract class Activity {

    public enum Status { RUNNING, DONE, FAILED }

    /** Everything an activity needs to act on the world. */
    public record Context(
            VillagerBrain  brain,
            VillagerModel  model,
            VillageManager mgr,
            ServerLevel    level,
            NpcEntity      npc
    ) {
        /**
         * This villager's own time: game time less every tick it spent
         * paused (a player's screen open, a cleric tending it), while its
         * activity was not ticked. For an activity's own "how long have I
         * been at this" deadlines, so a pause does not use them up. Anything
         * other villagers read stays on game time.
         */
        public long clock() {
            long now = level.getGameTime();
            return brain == null ? now : now - brain.pausedTicks(now);
        }
    }

    private boolean started = false;

    /** Brain entry point — runs {@link #start} once, then {@link #tick}. */
    public final Status tickOuter(Context ctx) {
        if (!started) { started = true; start(ctx); }
        return tick(ctx);
    }

    protected void start(Context ctx) {}
    protected abstract Status tick(Context ctx);

    /** Called when the activity ends for any reason (done, failed, interrupted). */
    public void stop(Context ctx) {}

    public abstract String name();

    /**
     * Whether a villager already sitting on a chair may stay seated while this
     * activity runs. {@link VillagerBrain} stands it up before ticking any
     * activity that says no, so only activities that sit on purpose, or do
     * nothing that needs legs, return true.
     */
    public boolean keepsChairSeat() { return false; }

    // =========================================================================
    // Shared movement helper
    // =========================================================================

    private boolean moveIssued = false;

    /**
     * Walk to {@code goal} via the village nav graph. Returns {@code true} once
     * movement has finished (arrived, or pathing gave up). Call every tick.
     */
    protected final boolean moveTo(Context ctx, BlockPos goal, float speed) {
        if (!moveIssued) {
            NavGraph g = ctx.mgr().getGraph();
            if (g == null) return true; // nothing we can do
            ctx.model().requestMoveTo(g, goal, speed);
            moveIssued = true;
            return false;
        }
        return !ctx.model().isMoving();
    }

    protected final boolean moveFailed(Context ctx, BlockPos goal) {
        return ctx.model().hasRecentMoveFailure(goal);
    }

    /** Reset the movement helper so {@link #moveTo} can issue a fresh walk. */
    protected final void resetMove() { moveIssued = false; }

    // =========================================================================
    // Shared queries
    // =========================================================================

    /** How far above or below itself an idle villager will consider walking. */
    private static final int MAX_NODE_CHOICE_DY = 6;

    /**
     * Reservoir-sample somewhere a villager could plausibly stroll to.
     *
     * <p>Nodes on furniture, chests and open water are skipped. They exist so a
     * villager that ends up standing on a bed can path off it, not so an idle
     * one can decide to go and stand on somebody's bed — and A* refuses to
     * enter them anyway, so choosing one only produces a goal that gets snapped
     * somewhere else. {@code GuardPatrolPlanner} has always filtered these when
     * choosing patrol points; this is the same rule for everyone else.
     *
     * <p>The vertical window stays at six blocks. On flat ground it never
     * matters, and on real terrain a strolling villager choosing the ledge above
     * or the cave mouth below is the intended behaviour — it walks there the
     * long way round, which is what a person would do.
     */
    protected static BlockPos randomNodeNear(NavGraph graph, BlockPos center, int radius, Random r) {
        return randomNodeNear(graph, center, radius, MAX_NODE_CHOICE_DY, r);
    }

    /**
     * As {@link #randomNodeNear}, for a stroll: never a spot inside a ranch or
     * on its gate. A villager already inside one gets a spot outside,
     * so it walks back out; see {@link OpenGround}. Never a spot down a mine
     * either ({@link MineGround}): only free time strolls come through here
     * (wander, admire, children at play), and none of it belongs in a tunnel.
     */
    protected static BlockPos randomStrollSpot(Context ctx, BlockPos center, int radius, int maxDy, Random r) {
        java.util.function.Predicate<BlockPos> downAMine = MineGround.test(ctx.mgr());
        return OpenGround.randomSpot(ctx.mgr(), ctx.mgr().getGraph(),
                center, radius, maxDy, r, node -> node.isRoutable() && !downAMine.test(node.feetPos()));
    }

    /** As above, with an explicit vertical window. */
    protected static BlockPos randomNodeNear(NavGraph graph, BlockPos center, int radius,
                                             int maxDy, Random r) {
        if (graph == null) return null;
        // Reservoir pick over the box, visited in place (same order and the same
        // draws as collecting the box first, so the same node comes out).
        NavNode[] best = new NavNode[1];
        int[] count = new int[1];
        graph.forEachNodeInBox(center, radius, maxDy, n -> {
            if (!n.isRoutable()) return;
            BlockPos p = n.feetPos();
            if (Math.abs(p.getX() - center.getX()) <= radius
                    && Math.abs(p.getZ() - center.getZ()) <= radius
                    && Math.abs(p.getY() - center.getY()) <= maxDy) {
                count[0]++;
                if (r.nextInt(count[0]) == 0) best[0] = n;
            }
        });
        return best[0] != null ? best[0].feetPos() : null;
    }
}
