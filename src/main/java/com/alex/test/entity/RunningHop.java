package com.alex.test.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Round 2 of the running hop: a villager in a hurry jumps a one-block step
 * before reaching it, so it arcs over the edge at speed instead of running into
 * the face, stopping dead and rising straight up.
 *
 * <p>Every jump used to wait for contact: vanilla's MoveControl jumps within a
 * block of the step's centre, MoveToCommand's step-up assist only once a face
 * has stopped the walk. At a walk that is right (MoveToCommand notes that an
 * earlier jump-before-contact was a bug: at 2.7 blocks a second the arc lands
 * short of the step and bumps it mid-air). At a run it costs half a second.
 *
 * <p>The arc is vanilla's: 0.42 up, gravity 0.08, drag 0.98. The feet are above
 * the step's top (1 block) from the 3rd tick to the 8th, so the jump starts when
 * the step's face is {@link #LEAD_TICKS} ticks of travel ahead and the body
 * crosses the edge early in that window. With round 3 (NpcEntity.getFlyingSpeed)
 * the pace holds through the arc; without it the crossing comes a little later,
 * still inside the window.
 *
 * <p>Only when all of this holds, else the ordinary jump on contact happens as
 * before: hurrying and going at least {@link #MIN_SPEED}; the step straight
 * ahead along the way it is moving, a full-topped block between a step and 1.25
 * high (not a door, a fence, a slab); nothing at head height on the way and two
 * clear blocks on top of the step; and the route really climbs onto it.
 */
final class RunningHop {

    /** Ticks of travel between the front of the villager and the step's face when it jumps. */
    static final double LEAD_TICKS = 4.0;
    /** Slower than this (blocks a tick: 3 a second, above a walk's 2.7) the contact jump is left alone. */
    static final double MIN_SPEED = 0.15;
    /** Moving this far off the way it faces its target (cos 25 degrees) is a turn, not a run at a step. */
    private static final double MIN_ALIGN = 0.9;
    /** Look-ahead sampling step, in blocks. */
    private static final double SAMPLE = 0.1;
    /** A step is at most this high: vanilla's jump peaks at 1.25. */
    private static final double MAX_RISE = 1.0 + 1e-3;
    /** Ticks after an early hop before another may start. */
    static final int COOLDOWN_TICKS = 6;

    private RunningHop() {}

    /**
     * Whether {@code npc}, steering for ({@code wantedX}, {@code wantedY},
     * {@code wantedZ}), should jump now. Server side, from the move control, after
     * vanilla's own decision not to.
     */
    static boolean shouldJumpEarly(NpcEntity npc, double wantedX, double wantedY, double wantedZ) {
        if (!npc.isRunning() || !npc.onGround() || npc.isInWaterOrBubble()
                || npc.onClimbable() || npc.isPassenger()) {
            return false;
        }
        Level level = npc.level();

        // How fast it is going: the motion carried into this tick is what is left
        // after last tick's ground friction, so divide that back out.
        Vec3 motion = npc.getDeltaMovement();
        double carried = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        BlockPos under = npc.getBlockPosBelowThatAffectsMyMovement();
        float friction = level.getBlockState(under).getFriction(level, under, npc) * 0.91F;
        double speed = friction > 0 ? carried / friction : 0;
        if (speed < MIN_SPEED) return false;
        double dirX = motion.x / carried;
        double dirZ = motion.z / carried;

        // Heading at its target, not turning.
        double tx = wantedX - npc.getX();
        double tz = wantedZ - npc.getZ();
        double toTarget = Math.sqrt(tx * tx + tz * tz);
        if (toTarget < 1e-3 || (dirX * tx + dirZ * tz) / toTarget < MIN_ALIGN) return false;

        double feetY = npc.getY();
        double half = npc.getBbWidth() / 2.0;
        double lead = LEAD_TICKS * speed;
        BlockPos here = BlockPos.containing(npc.getX(), feetY + 0.5, npc.getZ());
        if (!isClear(level, here.above(2))) return false;

        for (double d = half; d <= half + lead + SAMPLE; d += SAMPLE) {
            BlockPos column = BlockPos.containing(npc.getX() + dirX * d, feetY + 0.5, npc.getZ() + dirZ * d);
            if (column.getX() == here.getX() && column.getZ() == here.getZ()) continue;
            BlockState state = level.getBlockState(column);
            VoxelShape shape = state.getCollisionShape(level, column);
            if (shape.isEmpty()) {
                // Open ground before the step: the arc passes over it at head height.
                if (!isClear(level, column.above(2))) return false;
                continue;
            }
            double rise = column.getY() + shape.max(Direction.Axis.Y) - feetY;
            if (rise <= 1e-3) continue;
            // A slab or a stair is walked up; a fence or a wall is not a step.
            if (rise <= npc.maxUpStep() || rise > MAX_RISE) return false;
            if (state.is(BlockTags.DOORS) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.TRAPDOORS)
                    || !Block.isFaceFull(shape, Direction.UP)) {
                return false;
            }
            // The face is at about d: jump once it is no more than LEAD_TICKS of travel off.
            if (d - half > lead) return false;
            if (!isClear(level, column.above()) || !isClear(level, column.above(2))) return false;
            return routeClimbs(npc, column, wantedX, wantedY, wantedZ, feetY);
        }
        return false;
    }

    /**
     * Whether the way it is going climbs onto {@code step}: the target is up there
     * past it (a straight walk to a waypoint), or the vanilla route's next few
     * nodes include the step's top. Running at some block that the route goes
     * round must not set off a jump.
     */
    private static boolean routeClimbs(NpcEntity npc, BlockPos step, double wantedX, double wantedY, double wantedZ,
                                       double feetY) {
        if (wantedY - feetY > npc.maxUpStep()) {
            double toStep = horizontalDistSq(npc.getX(), npc.getZ(), step.getX() + 0.5, step.getZ() + 0.5);
            double toTarget = horizontalDistSq(npc.getX(), npc.getZ(), wantedX, wantedZ);
            if (toTarget + 0.5 >= toStep) return true;
        }
        Path path = npc.getNavigation().getPath();
        if (path == null || path.isDone()) return false;
        int stepTop = step.getY() + 1;
        int last = Math.min(path.getNodeCount(), path.getNextNodeIndex() + 3);
        for (int i = path.getNextNodeIndex(); i < last; i++) {
            Node node = path.getNode(i);
            if (node.y >= stepTop && Math.abs(node.x - step.getX()) <= 1 && Math.abs(node.z - step.getZ()) <= 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClear(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static double horizontalDistSq(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }
}
