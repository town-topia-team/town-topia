package com.alex.test.client;

import com.alex.test.entity.visitor.NomadEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** Renderer for recruitable nomads, deliberately separate from merchant visitors. */
public final class NomadGeoRenderer extends GeoEntityRenderer<NomadEntity> {
    private final BentLimb.Dispatcher bend = new BentLimb.Dispatcher(this);

    public NomadGeoRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerGeoModel<>());
        this.renderLayers.addLayer(new VillageAlertOutlineLayer<>(this));
    }

    @Override
    public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer,
                                  int packedLight, int packedOverlay, int colour) {
        if (!bend.handled(poseStack, bone, buffer, packedLight, packedOverlay, colour)) {
            super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, colour);
        }
    }

    @Override
    public void render(NomadEntity entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight) {
        super.render(entity, yaw, partialTick, poseStack, buffers, packedLight);
        ThoughtBubbleRenderer.render(entity, poseStack, buffers, partialTick);
    }
}
