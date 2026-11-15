package com.alex.test.client;

import com.alex.test.entity.ChairSeatEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** The mount is invisible; only its player passenger and the chair block render. */
public class ChairSeatRenderer extends EntityRenderer<ChairSeatEntity> {
    public ChairSeatRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ChairSeatEntity seat, float yaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
    }

    @Override
    public ResourceLocation getTextureLocation(ChairSeatEntity seat) {
        return ResourceLocation.withDefaultNamespace("textures/block/oak_planks.png");
    }
}
