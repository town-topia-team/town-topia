package com.alex.test.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * ZalithYT's upright piano, two blocks wide and two tall. Each of the four parts draws its own
 * quarter of the model (tools/art/ZalithPiano.java); drawn whole from one part, the game's soft
 * shadows went black on everything past that block (10-24). The parts carry their slice of the
 * shape and break together. Only the bottom right part (as seen from the keys) drops the item,
 * like the lower half of a door.
 */
public class ZalithPianoBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<ZalithPianoBlock> CODEC = simpleCodec(ZalithPianoBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final EnumProperty<Side> SIDE = EnumProperty.create("side", Side.class);

    /** Which column a part is in, as seen by someone facing the keys. */
    public enum Side implements StringRepresentable {
        LEFT("left"), RIGHT("right");

        private final String name;

        Side(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * Whole piano facing north, in the bottom right part's pixels (x 0..32, y 0..32).
     * Matches the box list in tools/art/ZalithPiano.java.
     */
    private static final VoxelShape WHOLE_NORTH = Shapes.or(
            Block.box(0, 0, 6, 32, 24, 16),     // sides, case and lid
            Block.box(0, 12, 0, 32, 16, 6),     // key shelf, keys and their end blocks
            Block.box(0, 0, 1, 2, 12, 6),       // right toe block and leg
            Block.box(30, 0, 1, 32, 12, 6),     // left toe block and leg
            Block.box(11, 17, 5, 21, 22, 6),    // sheet music
            Block.box(4, 24, 10, 6, 30, 12),    // candles
            Block.box(26, 24, 10, 28, 30, 12));
    /** Indexed by side, half, then facing's 2D value. */
    private static final VoxelShape[][][] SHAPES = new VoxelShape[2][2][4];

    static {
        for (Side side : Side.values()) {
            for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
                int dx = side == Side.LEFT ? 1 : 0;
                int dy = half == DoubleBlockHalf.UPPER ? 1 : 0;
                VoxelShape north = Shapes.join(WHOLE_NORTH, Shapes.box(dx, dy, 0, dx + 1, dy + 1, 1), BooleanOp.AND)
                        .move(-dx, -dy, 0);
                VoxelShape east = WoodenChairBlock.rotateClockwise(north);
                VoxelShape south = WoodenChairBlock.rotateClockwise(east);
                VoxelShape[] byFacing = SHAPES[side.ordinal()][half.ordinal()];
                byFacing[Direction.NORTH.get2DDataValue()] = north;
                byFacing[Direction.EAST.get2DDataValue()] = east;
                byFacing[Direction.SOUTH.get2DDataValue()] = south;
                byFacing[Direction.WEST.get2DDataValue()] = WoodenChairBlock.rotateClockwise(south);
            }
        }
    }

    public ZalithPianoBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(SIDE, Side.RIGHT));
    }

    @Override
    public MapCodec<? extends ZalithPianoBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, SIDE);
    }

    /**
     * The clicked spot becomes the bottom right part; the piano reaches up and to the placer's left.
     * Only an owner places it ({@link PianoLock}); for anyone else, or no player, nothing appears.
     */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        if (!PianoLock.isOwner(context.getPlayer())) {
            PianoLock.refusePlace(context.getPlayer());
            return null;
        }
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() >= level.getMaxBuildHeight() - 1) return null;
        BlockPos left = pos.relative(facing.getClockWise());
        for (BlockPos part : List.of(pos.above(), left, left.above())) {
            if (!level.getBlockState(part).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(part)) {
                return null;
            }
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    /** Order matters: each new part must land next to parts that are already there. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        BlockPos left = pos.relative(state.getValue(FACING).getClockWise());
        BlockState upper = state.setValue(HALF, DoubleBlockHalf.UPPER);
        level.setBlock(pos.above(), upper, Block.UPDATE_ALL);
        level.setBlock(left, state.setValue(SIDE, Side.LEFT), Block.UPDATE_ALL);
        level.setBlock(left.above(), upper.setValue(SIDE, Side.LEFT), Block.UPDATE_ALL);
    }

    /** A part whose neighbour piece went missing turns to air, which then takes the rest with it. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        boolean lower = state.getValue(HALF) == DoubleBlockHalf.LOWER;
        boolean right = state.getValue(SIDE) == Side.RIGHT;
        Direction facing = state.getValue(FACING);
        Direction vertical = lower ? Direction.UP : Direction.DOWN;
        Direction across = right ? facing.getClockWise() : facing.getCounterClockWise();
        BlockState expected = null;
        if (direction == vertical) {
            expected = state.setValue(HALF, lower ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        } else if (direction == across) {
            expected = state.setValue(SIDE, right ? Side.LEFT : Side.RIGHT);
        }
        if (expected != null && neighborState != expected) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    /**
     * In creative, clear the dropping part quietly first, as tall flowers do;
     * otherwise breaking any other part would still drop a piano.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()) {
            BlockPos main = mainPos(pos, state);
            BlockState mainState = level.getBlockState(main);
            if (!main.equals(pos) && mainState.is(this)) {
                level.setBlock(main, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, main, Block.getId(mainState));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    private static BlockPos mainPos(BlockPos pos, BlockState state) {
        BlockPos bottom = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        return state.getValue(SIDE) == Side.LEFT
                ? bottom.relative(state.getValue(FACING).getCounterClockWise())
                : bottom;
    }

    /** Any mirror puts the left column on the right, so the parts swap names. */
    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = super.mirror(state, mirror);
        return mirror == Mirror.NONE ? mirrored : mirrored.cycle(SIDE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(SIDE).ordinal()][state.getValue(HALF).ordinal()]
                [state.getValue(FACING).get2DDataValue()];
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

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("block.towntopia.zalith_piano.tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.towntopia.zalith_piano.tooltip.hidden").withStyle(ChatFormatting.GRAY));
    }
}
