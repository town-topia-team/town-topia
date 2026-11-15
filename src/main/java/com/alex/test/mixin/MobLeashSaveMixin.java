package com.alex.test.mixin;

import com.alex.test.village.villagers.butcher.lead.LedAnimals;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An animal a villager is leading is saved without its leash. Saved, the
 * leash would come back pointing at the villager's id, and if the villager
 * loaded more than 5 seconds after the animal, vanilla would drop a lead item
 * for it: a second lead, while the villager still has its own.
 */
@Mixin(Mob.class)
public abstract class MobLeashSaveMixin {

    @Inject(method = "addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    private void dynamicvillages$dropVillagerLeash(CompoundTag tag, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (!self.level().isClientSide && LedAnimals.isLed(self)) tag.remove(Leashable.LEASH_TAG);
    }
}
