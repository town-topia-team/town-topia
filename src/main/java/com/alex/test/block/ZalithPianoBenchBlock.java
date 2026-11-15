package com.alex.test.block;

import com.alex.test.DynamicVillages;
import com.alex.test.block.ZalithPianoBlock.Side;
import com.alex.test.entity.ChairSeatEntity;
import com.alex.test.entity.NpcEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The bench for ZalithYT's Piano, two blocks wide. Placed from the same spot as the piano it lines up with the
 * piano's two columns, and both get the same FACING (toward the placer). Each part draws its own half
 * (tools/art/ZalithPiano.java); only the right part (as seen by the placer) drops the item.
 *
 * <p>Right click either half to sit in the middle, on the edge between the parts, facing away from FACING
 * (toward the keys). It is not a {@link WoodenChairBlock}, so villagers never pick it as a seat.
 */
public class ZalithPianoBenchBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<ZalithPianoBenchBlock> CODEC = simpleCodec(ZalithPianoBenchBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<Side> SIDE = ZalithPianoBlock.SIDE;

    /** Seat top at 9 px like the chairs, so the same mount height lines the sitter up. */
    private static final double SEAT_MOUNT_HEIGHT = 0.5D;

    /** Whole bench facing north, in the right part's pixels (x 0..32). Matches tools/art/ZalithPiano.java. */
    private static final VoxelShape WHOLE_NORTH = Shapes.or(
            Block.box(1, 7, 3, 31, 9, 13),      // seat
            Block.box(2, 5, 4, 30, 7, 12),      // aprons under the seat
            Block.box(2, 0, 4, 4, 7, 6), Block.box(2, 0, 10, 4, 7, 12),
            Block.box(28, 0, 4, 30, 7, 6), Block.box(28, 0, 10, 30, 7, 12));
    /** Fence height over the footprint, for walking villagers only; see {@link #getCollisionShape}. */
    private static final VoxelShape VILLAGER_BLOCKER_NORTH = Block.box(1, 0, 3, 31, 24, 13);
    /** Indexed by side, then facing's 2D value. */
    private static final VoxelShape[][] SHAPES = byPart(WHOLE_NORTH);
    private static final VoxelShape[][] BLOCKERS = byPart(VILLAGER_BLOCKER_NORTH);

    private static VoxelShape[][] byPart(VoxelShape wholeNorth) {
        VoxelShape[][] out = new VoxelShape[2][4];
        for (Side side : Side.values()) {
            int dx = side == Side.LEFT ? 1 : 0;
            VoxelShape north = Shapes.join(wholeNorth, Shapes.box(dx, 0, 0, dx + 1, 2, 1), BooleanOp.AND).move(-dx, 0, 0);
            VoxelShape east = WoodenChairBlock.rotateClockwise(north);
            VoxelShape south = WoodenChairBlock.rotateClockwise(east);
            VoxelShape[] byFacing = out[side.ordinal()];
            byFacing[Direction.NORTH.get2DDataValue()] = north;
            byFacing[Direction.EAST.get2DDataValue()] = east;
            byFacing[Direction.SOUTH.get2DDataValue()] = south;
            byFacing[Direction.WEST.get2DDataValue()] = WoodenChairBlock.rotateClockwise(south);
        }
        return out;
    }

    public ZalithPianoBenchBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(SIDE, Side.RIGHT));
    }

    @Override
    public MapCodec<? extends ZalithPianoBenchBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SIDE);
    }

    /** The clicked spot becomes the right part; the bench reaches to the placer's left, like the piano. Owners only. */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        if (!PianoLock.isOwner(context.getPlayer())) {
            PianoLock.refusePlace(context.getPlayer());
            return null;
        }
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos left = context.getClickedPos().relative(facing.getClockWise());
        Level level = context.getLevel();
        if (!level.getBlockState(left).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(left)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.relative(state.getValue(FACING).getClockWise()), state.setValue(SIDE, Side.LEFT), Block.UPDATE_ALL);
    }

    /** A part whose other half went missing turns to air. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        boolean right = state.getValue(SIDE) == Side.RIGHT;
        Direction facing = state.getValue(FACING);
        Direction across = right ? facing.getClockWise() : facing.getCounterClockWise();
        if (direction == across && neighborState != state.setValue(SIDE, right ? Side.LEFT : Side.RIGHT)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    /** In creative, clear the dropping part quietly first, so breaking the left half drops nothing. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(SIDE) == Side.LEFT) {
            BlockPos main = pos.relative(state.getValue(FACING).getCounterClockWise());
            BlockState mainState = level.getBlockState(main);
            if (mainState.is(this)) {
                level.setBlock(main, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, main, Block.getId(mainState));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** Any mirror puts the left half on the right, so the parts swap names. */
    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = super.mirror(state, mirror);
        return mirror == Mirror.NONE ? mirrored : mirrored.cycle(SIDE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(SIDE).ordinal()][state.getValue(FACING).get2DDataValue()];
    }

    /** Like the chairs: a fence post to walking villagers, so they never step up onto the seat. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext
                && entityContext.getEntity() instanceof NpcEntity npc && !npc.isPassenger()) {
            return BLOCKERS[state.getValue(SIDE).ordinal()][state.getValue(FACING).get2DDataValue()];
        }
        return getShape(state, level, pos, context);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    /** Middle click copies it for owners only; anyone else gets nothing, even in creative. */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return PianoLock.isOwner(player) ? super.getCloneItemStack(state, target, level, pos, player) : ItemStack.EMPTY;
    }

    /** The Wither, the dragon and any other block breaking mob leave it alone. */
    @Override
    public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        return false;
    }

    /** The middle of the bench at the chairs' seat height: the edge between the two parts. */
    public static Vec3 seatPos(BlockPos pos, BlockState state) {
        Direction toLeft = state.getValue(FACING).getClockWise();
        BlockPos right = state.getValue(SIDE) == Side.LEFT ? pos.relative(toLeft.getOpposite()) : pos;
        return new Vec3(right.getX() + 0.5 + toLeft.getStepX() * 0.5, right.getY() + SEAT_MOUNT_HEIGHT,
                right.getZ() + 0.5 + toLeft.getStepZ() * 0.5);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (player.isShiftKeyDown() || player.isPassenger()) return InteractionResult.PASS;
        if (!PianoLock.isOwner(player)) {
            PianoLock.refuseSit(player);
            return InteractionResult.FAIL;
        }
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.SUCCESS;

        Vec3 at = seatPos(pos, state);
        for (ChairSeatEntity seat : serverLevel.getEntitiesOfClass(ChairSeatEntity.class, AABB.ofSize(at, 0.3, 0.3, 0.3))) {
            if (seat.isVehicle()) return InteractionResult.SUCCESS;
            seat.discard();
        }

        ChairSeatEntity seat = DynamicVillages.CHAIR_SEAT.get().create(serverLevel);
        if (seat == null) return InteractionResult.FAIL;
        seat.moveTo(at.x, at.y, at.z, state.getValue(FACING).getOpposite().toYRot(), 0.0F);
        if (!serverLevel.addFreshEntity(seat)) return InteractionResult.FAIL;
        if (!player.startRiding(seat)) {
            seat.discard();
            return InteractionResult.FAIL;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            Vec3 at = seatPos(pos, state);
            for (ChairSeatEntity seat : serverLevel.getEntitiesOfClass(ChairSeatEntity.class, AABB.ofSize(at, 0.3, 0.3, 0.3))) {
                seat.discard();
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("block.towntopia.zalith_piano_bench.tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.towntopia.zalith_piano.tooltip.hidden").withStyle(ChatFormatting.GRAY));
    }
}
