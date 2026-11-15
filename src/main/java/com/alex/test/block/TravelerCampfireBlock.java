package com.alex.test.block;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.visitors.TravelerCamp;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

import javax.annotation.Nullable;

/**
 * The campfire travelers light when they camp in a village overnight
 * ({@link TravelerCamp}). It looks and burns like a vanilla campfire, but it
 * drops nothing, cooks nothing, cannot be put out with water or a shovel,
 * does not burn out, and shrugs off explosions and pistons. Breaking it while
 * travelers camp there upsets them (see {@link TravelerCamp#onPlayerBreaksFire}).
 */
public class TravelerCampfireBlock extends CampfireBlock {

    public TravelerCampfireBlock(Properties properties) {
        super(true, 1, properties);
    }

    /** Fence height over the fire, for villagers and visitors only; see {@link #getCollisionShape}. */
    private static final VoxelShape NPC_BLOCKER = Block.box(0, 0, 0, 16, 24, 16);

    /**
     * Villagers walk around the fire on their walking map, but the map only
     * learns about a new fire after a short quiet spell, and one already on its
     * way keeps its old route. To them the fire is a fence: they bump into it
     * and find another way instead of stepping up into the flames. Players
     * keep the vanilla campfire shape.
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext
                && entityContext.getEntity() instanceof NpcEntity) {
            return NPC_BLOCKER;
        }
        return super.getCollisionShape(state, level, pos, context);
    }

    /**
     * Burning, for Minecraft's own pathfinding, which villagers use for short
     * walks between the stops on their route. It only knows vanilla campfires
     * burn; to it this one was a step to stand on, so it planned paths up over
     * the fire, and a villager walked into the fence-high box above (see
     * {@link #getCollisionShape}) and stood stuck against it. As fire it plans
     * around it, the way it does a vanilla campfire.
     */
    @Nullable
    @Override
    public PathType getBlockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob) {
        return PathType.DAMAGE_FIRE;
    }

    /** No block entity: nothing to cook, nothing to drop. */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return null;
    }

    /** The vanilla smoke column comes from the block entity; puff some here instead. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        super.animateTick(state, level, pos, random);
        if (!state.getValue(LIT)) return;
        for (int i = 0; i < 3; i++) {
            if (random.nextFloat() < 0.8F) makeParticles(level, pos, false, false);
        }
    }

    // Water does not put it out, and flowing water stops at it.
    @Override
    public boolean canPlaceLiquid(@Nullable Player player, BlockGetter level, BlockPos pos,
                                  BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        return false;
    }

    /** Dousing it with a shovel counts as breaking it: warned once, then it upsets the campers. */
    @Nullable
    @Override
    public BlockState getToolModifiedState(BlockState state, UseOnContext context, ItemAbility itemAbility,
                                           boolean simulate) {
        if (itemAbility != ItemAbilities.SHOVEL_DOUSE) {
            return super.getToolModifiedState(state, context, itemAbility, simulate);
        }
        if (!simulate && context.getLevel() instanceof ServerLevel level && context.getPlayer() != null
                && TravelerCamp.onPlayerBreaksFire(level, context.getPlayer(), context.getClickedPos())) {
            level.removeBlock(context.getClickedPos(), false);
        }
        return null;
    }

    /** Anything else that puts it out (a splash of water) has it light again. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !state.getValue(LIT)) level.scheduleTick(pos, this, 20);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(LIT)) {
            level.setBlock(pos, state.setValue(LIT, true).setValue(WATERLOGGED, false), 3);
        }
    }
}
