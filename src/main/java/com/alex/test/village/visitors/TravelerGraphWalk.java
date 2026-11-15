package com.alex.test.village.visitors;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.visitor.AbstractTravelerEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import net.minecraft.core.BlockPos;

import java.util.WeakHashMap;

/**
 * Walks a traveller to a spot inside a village with the villagers' own
 * {@link VillagerModel}: the same route planning, door handling, stuck
 * re-paths, nudges off furniture and out of walls, and giving up on a spot
 * that really cannot be reached.
 *
 * <p>{@link TravelerApproach} walks the same graph, but with none of that
 * recovery; a merchant whose route clipped a wall post kept walking into it.
 *
 * <p>The model is the traveller's own ({@link AbstractTravelerEntity#travelMovement}),
 * shared with its flee. It is only ticked while the traveller stands inside
 * the village bounds: outside, the model would pull it back in, so the caller
 * walks on vanilla navigation there.
 */
public final class TravelerGraphWalk {

    public enum Result {
        /** On the way; the caller should do nothing else this tick. */
        WALKING,
        /** The model reached the goal, or the nearest tile to it the map has. */
        ARRIVED,
        /** The model gave up on the goal after its own stuck recovery. */
        FAILED,
        /** The traveller or the goal is off the village graph: walk on vanilla. */
        OUTSIDE
    }

    /** A walk not ticked for this long (a flee, the night, a trade screen) starts over. */
    private static final long RESUME_GAP_TICKS = 20L;

    private static final class Walk {
        final BlockPos goal;
        boolean started;
        long lastTick;

        Walk(BlockPos goal) { this.goal = goal; }
    }

    private final String label;
    private final WeakHashMap<AbstractTravelerEntity, Walk> walks = new WeakHashMap<>();

    public TravelerGraphWalk(String label) { this.label = label; }

    public Result tick(AbstractTravelerEntity traveler, VillageManager village, BlockPos goal, float speed) {
        NavGraph graph = village.getGraph();
        if (graph == null || graph.isEmpty()
                || !graph.isInsideBounds(traveler.blockPosition()) || !graph.isInsideBounds(goal)) {
            clear(traveler);
            return Result.OUTSIDE;
        }

        VillagerModel model = traveler.travelMovement();
        long now = traveler.level().getGameTime();
        Walk walk = walks.get(traveler);
        if (walk == null || !walk.goal.equals(goal) || now - walk.lastTick > RESUME_GAP_TICKS) {
            // Whatever the model was doing (another walk, a flee that ended,
            // a route from before the night) is not this walk.
            model.stopMoving();
            walk = new Walk(goal.immutable());
            walks.put(traveler, walk);
        }
        walk.lastTick = now;

        if (walk.started && !model.isMoving()) {
            if (model.hasRecentMoveSuccess(goal)) return Result.ARRIVED;
            if (model.hasRecentMoveFailure(goal)) return Result.FAILED;
        }
        if (!model.isMoving()) {
            walk.started = true;
            if (!model.requestMoveTo(graph, goal, speed)) {
                DynamicVillages.LOGGER.info("[{} {}] The village map refused a walk to {}.",
                        label, TravelerApproach.shortId(traveler), goal.toShortString());
                return Result.FAILED;
            }
        }

        model.tick(graph);
        if (model.hasRecentMoveSuccess(goal)) return Result.ARRIVED;
        if (model.hasRecentMoveFailure(goal)) return Result.FAILED;
        return Result.WALKING;
    }

    /** Ends this traveller's walk, if it has one, and stops the model. */
    public void clear(AbstractTravelerEntity traveler) {
        if (walks.remove(traveler) != null) traveler.travelMovement().stopMoving();
    }
}
