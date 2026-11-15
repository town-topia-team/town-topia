package com.alex.test.village;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * The start of a path Minecraft planned, measured from where the villager
 * stands. Reads only.
 *
 * <p>Village routes are walked by the route follower; Minecraft's own
 * navigation only walks the few walks left on it (travellers off the village
 * map, a guard's close chase, see docs/plans/2026-10-18-vanilla-pathfinder-leftovers.md).
 * Such a path always starts at the block the villager is in.
 */
public final class LegShape {

    private LegShape() {}

    /**
     * Round 2: true when the path's first point is the block the mob already
     * stands in and the step on to the second point is level, so the mob may
     * walk straight for the second point instead of first to its own block's
     * middle.
     *
     * <p>A new path's first point is always that block. Minecraft counts it as
     * reached when the mob is within 0.45 of its middle on both axes, or when
     * the second point lies on the far side of the mob from the first
     * ({@code PathNavigation.followThePath} and
     * {@code shouldTargetNextNodeInDirection}, 1.21.1). This widens that to the
     * whole block. Doors, fire and steps up or down keep Minecraft's own rule,
     * and so does a path that starts from a corner of the mob's body rather
     * than the block it is in.
     */
    public static boolean startSkippable(Mob mob, Path path) {
        if (path.getNextNodeIndex() != 0 || path.getNodeCount() < 2) return false;
        if (!mob.onGround() || mob.isInWater()) return false;
        Node start = path.getNode(0);
        Node next = path.getNode(1);
        if (next.y != start.y) return false;
        if (!cutsCorners(start.type) || !cutsCorners(next.type)) return false;
        return mob.getBlockX() == start.x && mob.getBlockZ() == start.z
                && Math.abs(mob.getY() - start.y) < 1.0;
    }

    /** {@code PathNavigation#canCutCorner}: doors and fire are walked to their middle. */
    public static boolean cutsCorners(PathType type) {
        return type != PathType.DANGER_FIRE && type != PathType.DANGER_OTHER
                && type != PathType.WALKABLE_DOOR;
    }
}
