package com.alex.test.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Compact chair variant with a taller back, sharing the original chair's seating behavior. */
public class TallWoodenChairBlock extends WoodenChairBlock {
    public static final MapCodec<TallWoodenChairBlock> CODEC = simpleCodec(TallWoodenChairBlock::new);

    private static final VoxelShape NORTH_SHAPE = Shapes.or(
            Block.box(3, 5.5, 3, 13, 7.5, 13),
            Block.box(3, 0, 3, 5, 5.5, 5), Block.box(11, 0, 3, 13, 5.5, 5),
            Block.box(3, 0, 11, 5, 16, 13), Block.box(11, 0, 11, 13, 16, 13),
            Block.box(5, 9.625, 11, 11, 11.875, 13),
            Block.box(5, 12.625, 11, 11, 14.875, 13));
    private static final VoxelShape EAST_SHAPE = rotateClockwise(NORTH_SHAPE);
    private static final VoxelShape SOUTH_SHAPE = rotateClockwise(EAST_SHAPE);
    private static final VoxelShape WEST_SHAPE = rotateClockwise(SOUTH_SHAPE);

    public TallWoodenChairBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<TallWoodenChairBlock> codec() {
        return CODEC;
    }

    @Override
    public double seatMountHeight() {
        return 0.40625D;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case WEST -> WEST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }
}
