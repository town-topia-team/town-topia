package com.alex.test.client;

import com.alex.test.ModItems;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;

/**
 * When the blacksmith's hammer is held exactly like a player holds a tool (10-24, grip A of
 * docs/plans/2026-10-24-blacksmith-anvil-redo.md): while it draws out a bar at the anvil. The
 * clip smith_anvil_draw was solved in tools/anvil for that hold (the archer's bow chain in
 * {@link HeldItemGeoLayer}: fingertips, 2 px forward, PLAYER_HAND, the hammer's own
 * thirdperson_righthand), so the hammer never turns in the fist and its face lands edge first.
 * Melting down and fitting books keep the old hold until their own rounds.
 */
final class SmithHammerHold {

    private SmithHammerHold() {}

    /** The player hold's nudge for this hand, or null when the old hold applies. */
    static HeldItemTransform.Adjust adjustFor(NpcEntity entity, ItemStack stack) {
        if (entity.getProfession() != Profession.BLACKSMITH || entity.getMocapClip() != 0) return null;
        if (entity.getAnimation() != VillagerAnimationState.SMITH_ANVIL_DRAW) return null;
        return stack.is(ModItems.SMITH_HAMMER.get()) ? HeldItemTransform.SMITH_HAMMER_HOLD : null;
    }
}
