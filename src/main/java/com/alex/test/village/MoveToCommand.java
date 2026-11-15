package com.alex.test.village;

import net.minecraft.world.entity.PathfinderMob;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * A walk along a village route, as {@link VillagerModel} and the travellers run one: ticked once
 * a game tick until it is done, stuck, or wants the rest of its route planned again. The walker
 * is {@link FollowWalk} (the route follower); it hands each run of ladder rungs to a
 * {@link LadderLeg}. This class holds what every walk shares: the route's feet cells, the doors
 * it opened and who shuts them, and what the stuck catcher and walk-back spy read.
 *
 * <p>Until 2026-10-18 this class was also the old walker, which handed each stretch between two
 * waypoints to Minecraft's own pathfinder. That walker and its patches are gone.
 */
public abstract class MoveToCommand {

    public enum Status {
        RUNNING,
        DONE,
        STUCK,
        /**
         * The walk got as far as its route was read (a very long route is read in parts) and
         * the rest should be planned again from here. Not a stuck: nothing went wrong.
         */
        REPLAN
    }

    private final List<BlockPos> waypoints;

    /** The doors, gates and trapdoors this walk opened, to shut behind it. */
    final RouteDoorHandler doorHandler = new RouteDoorHandler();
    /**
     * Takes the doors this walk could not shut when it ended because someone
     * stood in them; see {@link #closeAllOpenedDoors}. Null: shut them all at once.
     */
    @Nullable private RouteDoorHandler occupiedDoorKeeper;

    protected MoveToCommand(List<BlockPos> waypoints) {
        this.waypoints = waypoints;
    }

    /** The route's feet cells, in order. */
    public List<BlockPos> waypoints() { return waypoints; }

    public int waypointCount() { return waypoints.size(); }

    /** One game tick of walking. */
    public abstract Status tick(PathfinderMob npc);

    /** The route cell the walk is heading for next, or null when it is done. */
    @Nullable
    public abstract BlockPos currentWaypoint();

    public abstract int currentWaypointIndex();

    /**
     * True when the last STUCK was not the step ahead's fault (off the route, a fall, a block
     * found and already reported), so {@code VillagerModel} does not mark that step failed.
     */
    public abstract boolean stuckOffRoute();

    /** True when the walk itself marked the step it stuck at failed in the map. */
    public boolean stepMarkedFailed() { return false; }

    /** Stuck catcher: which stuck rule fired, in words; empty until one does. */
    public abstract String stuckRule();

    /** For the spies: jumps tried at the current step and how the last one went. */
    public abstract String spyHops();

    /** How the last tick was steered, in a word: follow, ladder, straight. */
    abstract String spyMode();

    /** True while the last tick lined the villager up for a door, gate or trapdoor. */
    abstract boolean spyThreadingDoor();

    /** True while the last ladder tick kept the villager on the rung's column, not stepping on or off. */
    abstract boolean spyLadderOnRung();

    /** Stuck catcher (BlackBox): writes this walk's state into the tick's frame. Reads only. */
    public abstract void describeTo(com.alex.test.village.stuckwatch.BlackBox.Frame f);

    /**
     * Stuck catcher rewind: a fresh walk of a recorded route starts at waypoint {@code index}
     * instead of the first, as the recorded one was then.
     */
    public abstract void startAt(int index);

    /** One line for the debug log and the stuck catcher. */
    public abstract String debugState(PathfinderMob npc);

    /**
     * The chunks of the next {@code tiles} route tiles from where the body is, for far villagers
     * (FarAwake loads them before the body gets there). None by default: a ladder leg is short
     * and the edge margin round the body covers it.
     */
    public void chunksAhead(int tiles, java.util.function.LongConsumer out) {}

    // -------------------------------------------------------------------------
    // Doors
    // -------------------------------------------------------------------------

    /** Carries the doors a replaced route opened over to this one; see {@link RouteDoorHandler#adopt}. */
    public void inheritOpenedDoors(MoveToCommand previous) {
        previous.gatherDoors();
        doorHandler.adopt(previous.doorHandler);
    }

    /** Collects into {@link #doorHandler} any doors a walk inside this one opened (a ladder leg). */
    void gatherDoors() {}

    /** See {@link #occupiedDoorKeeper}. */
    public void keepOccupiedDoorsWith(RouteDoorHandler keeper) {
        this.occupiedDoorKeeper = keeper;
    }

    /**
     * Close all doors used during this walk (called on arrival or cancellation). With a keeper,
     * a door a villager or player is still standing in is handed to it instead, to shut once the
     * doorway is clear.
     */
    public void closeAllOpenedDoors(PathfinderMob npc) {
        WalkBody.ladderTarget(npc, null);
        if (occupiedDoorKeeper == null) {
            doorHandler.closeAllOpenedDoors(npc);
            return;
        }
        doorHandler.closeVacatedDoors(npc);
        occupiedDoorKeeper.adopt(doorHandler);
    }

    /**
     * Where across the door's cell the middle of the open gap lies, 0 to 1, from the door's live
     * collision shape: the open panel hugs one side. A closed door or a gate has no gap to find,
     * so aim down the middle and let it open.
     *
     * <p>An open door is a panel 3/16 of a block thick standing along one side of its cell, so the
     * clear gap is 13/16 wide and a 0.6-wide villager has about a tenth of a block to spare on
     * each side of the gap's centre. Aimed at the cell's centre instead, villagers passed a
     * sixteenth of a block from the panel and any angle put them into its edge.
     */
    static double gapCentre(ServerLevel level, BlockPos pos, BlockState state,
                            Direction.Axis passage) {
        Direction.Axis across = passage == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return 0.5;
        double min = shape.min(across);
        double max = shape.max(across);
        if (min <= 0.01 && max >= 0.99) return 0.5;
        if (min <= 0.01) return (max + 1.0) / 2.0;
        if (max >= 0.99) return min / 2.0;
        return 0.5;
    }
}
