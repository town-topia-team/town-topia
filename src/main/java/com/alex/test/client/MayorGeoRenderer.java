package com.alex.test.client;

import com.alex.test.entity.mayor.MayorEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** Uses the existing NPC model, texture, and animation set for the Mayor. */
public final class MayorGeoRenderer extends GeoEntityRenderer<MayorEntity> {

    private final BentLimb.Dispatcher bend = new BentLimb.Dispatcher(this);

    public MayorGeoRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerGeoModel<>());
        this.renderLayers.addLayer(new VillageAlertOutlineLayer<>(this));
    }

    @Override
    public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer,
                                  int packedLight, int packedOverlay, int colour) {
        if (bend.handled(poseStack, bone, buffer, packedLight, packedOverlay, colour))
            return;

        super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, colour);
    }

    /** Like the villagers (VillagerLabels): the name shows to a player looking at the Mayor from up to 16 blocks. */
    @Override
    public boolean shouldShowName(MayorEntity animatable) {
        if (animatable.hasCustomName() && animatable == VillagerLabels.lookedAt())
            return VillagerLabels.nameVisibleToPlayer(animatable);
        return super.shouldShowName(animatable);
    }

    /**
     * Thought and conversation bubbles are drawn after the model, from the
     * entity's own origin: GeckoLib has restored the pose stack by the time
     * {@code super.render} returns, so the bubble is positioned in world axes
     * and never picks up the villager's body yaw.
     */
    @Override
    public void render(MayorEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        ThoughtBubbleRenderer.render(entity, poseStack, bufferSource, partialTick);
    }
}
