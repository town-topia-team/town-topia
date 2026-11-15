package com.alex.test.block;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.ChairSeatEntity;
import com.alex.test.entity.NpcEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Wooden chair that mounts one player on an invisible, temporary seat. */
public class WoodenChairBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<WoodenChairBlock> CODEC = simpleCodec(WoodenChairBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private static final VoxelShape SEAT = Block.box(2, 7, 2, 14, 9, 14);
    private static final VoxelShape LEGS = Shapes.or(
            Block.box(2, 0, 2, 4, 8, 4), Block.box(12, 0, 2, 14, 8, 4),
            Block.box(2, 0, 12, 4, 18.75, 14), Block.box(12, 0, 12, 14, 18.75, 14));
    private static final VoxelShape NORTH_SHAPE = Shapes.or(SEAT, LEGS,
            Block.box(4, 11.25, 12, 12, 13.5, 14),
            Block.box(4, 15.125, 12, 12, 17.375, 14));
    private static final VoxelShape EAST_SHAPE = rotateClockwise(NORTH_SHAPE);
    private static final VoxelShape SOUTH_SHAPE = rotateClockwise(EAST_SHAPE);
    private static final VoxelShape WEST_SHAPE = rotateClockwise(SOUTH_SHAPE);
    /** Fence-height block over the chair's footprint; see {@link #getCollisionShape}. */
    private static final VoxelShape VILLAGER_BLOCKER = Block.box(2, 0, 2, 14, 24, 14);
    /** Planks' fire odds ({@code FireBlock.bootStrap}): catching from a nearby fire, and burning away. */
    private static final int PLANK_FIRE_SPREAD = 5;
    private static final int PLANK_BURN = 20;

    protected static VoxelShape rotateClockwise(VoxelShape shape) {
        VoxelShape rotated = Shapes.empty();
        for (AABB box : shape.toAabbs()) {
            rotated = Shapes.or(rotated, Shapes.box(
                    1 - box.maxZ, box.minY, box.minX,
                    1 - box.minZ, box.maxY, box.maxX));
        }
        return rotated;
    }

    public WoodenChairBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public MapCodec<? extends WoodenChairBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
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

    /**
     * To a walking villager a chair is a fence post. The seat (9/16) sits
     * under the 0.6 step height, so a villager whose 0.6-wide body brushed a
     * chair while passing — routes run cell centre to cell centre — was
     * stepped up onto the seat and walked into the back. Players keep the
     * real shape, and a seated villager rides its seat entity, so neither
     * sitting down nor getting up touches this.
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        VoxelShape shape = getShape(state, level, pos, context);
        if (context instanceof EntityCollisionContext entityContext
                && entityContext.getEntity() instanceof NpcEntity npc && !npc.isPassenger()) {
            return Shapes.or(shape, VILLAGER_BLOCKER);
        }
        return shape;
    }

    /**
     * Vanilla navigation treats any block short of a full cube as walkable
     * unless told otherwise, so it planned legs straight through chairs and
     * villagers stepped onto the seat and wedged against the back. Chests and
     * stairs opt out the same way.
     */
    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    /**
     * Chairs burn like the planks they are made of. Lava-lit (set per wood in
     * ModBlocks) is the switch, the same pairing vanilla planks have: crimson
     * and warped are neither. A burnt chair's seat goes in {@link #onRemove}.
     */
    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.ignitedByLava(level, pos, direction) ? PLANK_BURN : 0;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.ignitedByLava(level, pos, direction) ? PLANK_FIRE_SPREAD : 0;
    }

    /** Height of the invisible seat within the chair block. */
    public double seatMountHeight() {
        return 0.5D;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (player.isShiftKeyDown() || player.isPassenger()) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.SUCCESS;

        for (ChairSeatEntity seat : serverLevel.getEntitiesOfClass(
                ChairSeatEntity.class, new AABB(pos).inflate(0.15))) {
            if (seat.isVehicle()) return InteractionResult.SUCCESS;
            seat.discard();
        }

        ChairSeatEntity seat = DynamicVillages.CHAIR_SEAT.get().create(serverLevel);
        if (seat == null) return InteractionResult.FAIL;
        seat.moveTo(pos.getX() + 0.5, pos.getY() + seatMountHeight(), pos.getZ() + 0.5,
                state.getValue(FACING).toYRot(), 0.0F);
        if (!serverLevel.addFreshEntity(seat)) return InteractionResult.FAIL;
        if (!player.startRiding(seat)) {
            seat.discard();
            return InteractionResult.FAIL;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            for (ChairSeatEntity seat : serverLevel.getEntitiesOfClass(
                    ChairSeatEntity.class, new AABB(pos).inflate(0.15))) {
                seat.discard();
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

}
