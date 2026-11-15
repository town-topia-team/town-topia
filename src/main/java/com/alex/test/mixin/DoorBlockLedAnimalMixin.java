package com.alex.test.mixin;

import com.alex.test.village.villagers.butcher.lead.LedAnimals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

/**
 * An open door does not stop an animal one of our villagers is leading.
 *
 * <p>An open leaf lies along one side of its block and leaves 13/16 of a
 * block clear, narrower than a cow, pig or sheep (0.9): through a single
 * door the animal could only be stuck until the lead walk's stuck rules
 * moved it past. Now it walks through, brushing the open panel. Only for
 * led animals ({@link LedAnimals}), only on the server (where they move),
 * only while the door is open: players, villagers, loose animals and every
 * closed door collide as before. Path searches ask with an empty context and
 * are not affected either.
 */
@Mixin(DoorBlock.class)
public abstract class DoorBlockLedAnimalMixin extends Block {

    private DoorBlockLedAnimalMixin(Properties properties) {
        super(properties);
    }

    // Public on purpose: other mods (Sol Lib, NexusLib) widen BlockBehaviour.getCollisionShape to
    // public with an access transformer, and a protected override then fails to apply at startup.
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(DoorBlock.OPEN)
                && context instanceof EntityCollisionContext entityContext
                && entityContext.getEntity() instanceof Animal animal
                && !animal.level().isClientSide
                && LedAnimals.isLed(animal)) {
            return Shapes.empty();
        }
        return super.getCollisionShape(state, level, pos, context);
    }
}
