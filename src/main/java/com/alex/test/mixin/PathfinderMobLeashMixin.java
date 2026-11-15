package com.alex.test.mixin;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.butcher.lead.LedAnimals;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leashes held by our villagers skip vanilla's leash physics: the straight
 * line follow, the elastic pull and the 10-block snap that drops a lead item.
 * The villager's {@code LeadWalk} keeps the animal close instead.
 *
 * <p>A leash to a villager that is not in {@link LedAnimals} is stale (a
 * world saved mid-walk, a lead left after a crash) and is taken off without
 * an item: the lead never left the villager's pack. Leashes held by players
 * and fence knots are untouched.
 */
@Mixin(PathfinderMob.class)
public abstract class PathfinderMobLeashMixin {

    @Inject(method = "handleLeashAtDistance(Lnet/minecraft/world/entity/Entity;F)Z",
            at = @At("HEAD"), cancellable = true)
    private void dynamicvillages$villagerLeash(Entity holder, float distance, CallbackInfoReturnable<Boolean> cir) {
        if (!(holder instanceof NpcEntity)) return;
        PathfinderMob self = (PathfinderMob) (Object) this;
        if (self.level().isClientSide) return;
        if (!LedAnimals.isLed(self)) self.dropLeash(true, false);
        cir.setReturnValue(false);
    }
}
