package com.alex.test.mixin;

import com.alex.test.client.CrowdBatching;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the world's entity pass, one entity at a time, for {@code client.CrowdBatching}:
 * only a villager drawn from here may go into the crowd's buffers. Inventory portraits
 * and screens use the same main buffer but are not drawn from here.
 * With {@code CrowdBatching.allMobs}, Minecraft's own mobs (and their shadows and names)
 * are handed the crowd's buffers here too.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererEntityMixin {

    @Inject(method = "renderEntity", at = @At("HEAD"), require = 0)
    private void dv$entityStart(CallbackInfo ci) {
        CrowdBatching.worldEntity(true);
    }

    @Inject(method = "renderEntity", at = @At("RETURN"), require = 0)
    private void dv$entityEnd(CallbackInfo ci) {
        CrowdBatching.worldEntity(false);
    }

    /** The buffer the mob is drawn into ({@code EntityRenderDispatcher.render}'s 8th argument). */
    @ModifyArg(method = "renderEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
            index = 7, require = 0)
    private MultiBufferSource dv$crowdSource(Entity entity, double x, double y, double z, float yaw, float partialTick,
                                             PoseStack poseStack, MultiBufferSource source, int light) {
        return CrowdBatching.wrapWorld(entity, source);
    }
}
