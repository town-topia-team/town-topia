package com.alex.test.village.villagers.archer.behavior.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An archer shoots past the railing it stands at: fences, fence gates and
 * trapdoors within {@value #REACH} blocks of it, as on a tower or a wall walk.
 * Vanilla counts a fence as a 1.5 block wall, so an archer up a tower with a
 * fence or trapdoor railing could only hit mobs far enough out for the shot to
 * clear it, and lost most of its range. The arrow flies through that railing
 * too ({@code ArcherEvents}). A railing further off still stops the shot.
 */
public final class RailingSight {

    /** How far (across) from the archer a railing is its own. */
    public static final double REACH = 2.5D;
    /** From this far below the archer's feet to this far above them. */
    private static final int BELOW = 2;
    private static final int ABOVE = 2;

    private RailingSight() {}

    /** Fences, fence gates and trapdoors: what a tower or wall walk is railed with. */
    public static boolean isRailing(BlockState state) {
        return state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.TRAPDOORS);
    }

    /** Whether the block at {@code pos} is close enough to an archer with feet at {@code feet} to be its railing. */
    public static boolean isNear(BlockPos pos, Vec3 feet) {
        double dx = pos.getX() + 0.5D - feet.x;
        double dz = pos.getZ() + 0.5D - feet.z;
        double dy = pos.getY() - Math.floor(feet.y);
        return dx * dx + dz * dz <= REACH * REACH && dy >= -BELOW && dy <= ABOVE;
    }

    /** Nothing but the archer's own railing between {@code from} and {@code to}. */
    public static boolean isClear(Level level, Vec3 from, Vec3 to, Entity archer) {
        return level.clip(new Clip(from, to, archer, archer.position())).getType() == HitResult.Type.MISS;
    }

    /** As {@link #isClear}, for an archer standing with its feet at {@code feet} (a spot it may move to). */
    public static boolean isClearFrom(Level level, Vec3 from, Vec3 to, Entity archer, Vec3 feet) {
        return clip(level, from, to, archer, feet).getType() == HitResult.Type.MISS;
    }

    /** The first block in the way from {@code from} to {@code to}, past the railing of an archer at {@code feet}. */
    public static HitResult clip(Level level, Vec3 from, Vec3 to, Entity entity, Vec3 feet) {
        return level.clip(new Clip(from, to, entity, feet));
    }

    /** Vanilla's collider ray, with the archer's own railing taken out. */
    private static final class Clip extends ClipContext {
        private final Vec3 feet;

        Clip(Vec3 from, Vec3 to, Entity archer, Vec3 feet) {
            super(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, archer);
            this.feet = feet;
        }

        @Override
        public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
            if (isRailing(state) && isNear(pos, feet)) return Shapes.empty();
            return super.getBlockShape(state, level, pos);
        }
    }
}
