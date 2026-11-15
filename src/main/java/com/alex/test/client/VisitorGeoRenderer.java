package com.alex.test.client;

import com.alex.test.entity.visitor.VisitorEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * GeckoLib renderer for {@link VisitorEntity}.
 *
 * <p>Shares {@link VillagerGeoModel} with the villagers — a visitor is the same
 * mesh in different clothes, and the model picks the texture from the visitor
 * kind. It shares the villagers' held-item layer too: a merchant takes the
 * goods it buys from the tradesman into its hand for a moment.
 *
 * <p>Limbs go through {@link BentLimb} like every other villager's: a visitor
 * walks on the same rig and would otherwise be the one crease in the crowd.
 */
public class VisitorGeoRenderer extends GeoEntityRenderer<VisitorEntity> {

    private final BentLimb.Dispatcher bend = new BentLimb.Dispatcher(this);

    public VisitorGeoRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerGeoModel<>());
        this.renderLayers.addLayer(new VillageAlertOutlineLayer<>(this));
        this.renderLayers.addLayer(new HeldItemGeoLayer<>(this));
    }

    @Override
    public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer,
                                  int packedLight, int packedOverlay, int colour) {
        if (bend.handled(poseStack, bone, buffer, packedLight, packedOverlay, colour))
            return;

        super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, colour);
    }

    /**
     * Thought and conversation bubbles are drawn after the model, from the
     * entity's own origin: GeckoLib has restored the pose stack by the time
     * {@code super.render} returns, so the bubble is positioned in world axes
     * and never picks up the villager's body yaw.
     */
    @Override
    public void render(VisitorEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        ThoughtBubbleRenderer.render(entity, poseStack, bufferSource, partialTick);
    }
}
