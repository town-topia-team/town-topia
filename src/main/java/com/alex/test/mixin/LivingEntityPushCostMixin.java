package com.alex.test.mixin;

import com.alex.test.performance.ServerCostLog;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times mobs pushing each other for the {@code [ServerCost]} log; one flag check while it is off. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPushCostMixin {

    @Inject(method = "pushEntities()V", at = @At("HEAD"), require = 0)
    private void dynamicvillages$pushStart(CallbackInfo ci) {
        if (ServerCostLog.measuring) ServerCostLog.pushStart((LivingEntity) (Object) this);
    }

    @Inject(method = "pushEntities()V", at = @At("RETURN"), require = 0)
    private void dynamicvillages$pushEnd(CallbackInfo ci) {
        if (ServerCostLog.measuring) ServerCostLog.pushEnd((LivingEntity) (Object) this);
    }
}
