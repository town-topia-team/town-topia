package com.alex.test.client;

import com.alex.test.entity.NpcEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/** Renders an alerting villager's animated GeckoLib model into the outline buffer. */
public final class VillageAlertOutlineLayer<T extends NpcEntity> extends GeoRenderLayer<T> implements NpcGeoRenderer.PerBone {

    public VillageAlertOutlineLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    /** None: the outline redraws the whole villager from {@link #render}. */
    @Override
    public boolean usesBone(software.bernie.geckolib.cache.object.GeoBone bone) {
        return false;
    }

    @Override
    public void render(PoseStack poseStack, T villager, BakedGeoModel model,
                       @Nullable RenderType renderType, MultiBufferSource bufferSource,
                       @Nullable VertexConsumer buffer, float partialTick,
                       int packedLight, int packedOverlay) {
        if (!VillageAlertOutline.isActive(villager)) return;
        // Iris shaders draw each mob once from the sun too; that copy would glow off to one side.
        if (IrisShadowPass.active()) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.levelRenderer.shouldShowEntityOutlines()) return;

        OutlineBufferSource outlineBuffers = minecraft.renderBuffers().outlineBufferSource();
        if (VillageAlertOutline.isRedAlertActive(villager)) {
            double phase = (minecraft.level.getGameTime() + partialTick) * Math.PI / 10.0;
            double brightness = (Math.sin(phase) + 1.0) / 2.0;
            outlineBuffers.setColor(
                    (int) (120 + 135 * brightness),
                    (int) (12 + 33 * brightness),
                    (int) (8 + 17 * brightness), 255);
        } else if (VillageAlertOutline.isGuardAlertActive(villager)) {
            // A guard in a fight: steady blue, flashing twice a second when low on health.
            if (VillageAlertOutline.isLowOnHealth(villager)) {
                double phase = (minecraft.level.getGameTime() + partialTick) * Math.PI / 5.0;
                double brightness = (Math.sin(phase) + 1.0) / 2.0;
                outlineBuffers.setColor(
                        (int) (15 + 55 * brightness),
                        (int) (40 + 110 * brightness),
                        (int) (90 + 165 * brightness), 255);
            } else {
                outlineBuffers.setColor(70, 150, 255, 255);
            }
        } else if (VillageAlertOutline.isYellowAlertActive(villager)) {
            double phase = (minecraft.level.getGameTime() + partialTick) * Math.PI / 10.0;
            double brightness = (Math.sin(phase) + 1.0) / 2.0;
            outlineBuffers.setColor(
                    (int) (120 + 135 * brightness),
                    (int) (95 + 160 * brightness),
                    (int) (8 + 32 * brightness), 255);
        } else {
            outlineBuffers.setColor(45, 255, 80, 255);
        }
        RenderType outlineType = RenderType.outline(getTextureResource(villager));
        VertexConsumer outlineConsumer = outlineBuffers.getBuffer(outlineType);
        getRenderer().reRender(
                model,
                poseStack,
                outlineBuffers,
                villager,
                outlineType,
                outlineConsumer,
                partialTick,
                LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY,
                0xFFFFFFFF);
        minecraft.levelRenderer.requestOutlineEffect();
    }
}
