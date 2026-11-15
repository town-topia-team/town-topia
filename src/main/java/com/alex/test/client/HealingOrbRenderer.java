package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.HealingOrbEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the cleric's healing orb the way vanilla draws a thrown snowball or an
 * experience orb: one 16x16 pixel-art sprite that always faces the camera,
 * crisp (cut out, not blended) and fully lit. The same drawing is used for the
 * orb resting on the cleric's palm ({@link #drawAt}), so the orb in the hand
 * and the one in the air are the same thing.
 */
public class HealingOrbRenderer extends EntityRenderer<HealingOrbEntity> {

    public static final ResourceLocation CORE = ResourceLocation.fromNamespaceAndPath(
            DynamicVillages.MODID, "textures/entity/healing_orb.png");

    /**
     * Half the width of the sprite, in blocks. The orb fills 10 of its 16
     * pixels, so it shows about 0.31 blocks across, a thrown snowball's size.
     */
    private static final float SPRITE_RADIUS = 0.25f;

    public HealingOrbRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(HealingOrbEntity orb, float yaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        poseStack.pushPose();
        // Centred on the orb's own position, not its hitbox's middle: it leaves
        // from exactly where the palm orb was, and lands where it is aimed.
        poseStack.mulPose(entityRenderDispatcher.cameraOrientation());
        draw(poseStack, buffers, orb.tickCount + partialTick, 1f);
        poseStack.popPose();
        super.render(orb, yaw, partialTick, poseStack, buffers, packedLight);
    }

    /**
     * Draws the orb with its centre where the current pose puts the origin,
     * turned to face the camera. For drawing inside another renderer, whose
     * pose carries rotations and scales of its own: only the position is kept.
     */
    public static void drawAt(PoseStack poseStack, MultiBufferSource buffers, float time, float size) {
        Vector3f centre = poseStack.last().pose().transformPosition(new Vector3f());
        poseStack.pushPose();
        PoseStack.Pose pose = poseStack.last();
        pose.pose().identity().translate(centre)
                .rotate(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        pose.normal().identity();
        draw(poseStack, buffers, time, size);
        poseStack.popPose();
    }

    private static void draw(PoseStack poseStack, MultiBufferSource buffers, float time, float size) {
        quad(poseStack.last(), buffers.getBuffer(RenderType.entityCutoutNoCull(CORE)), SPRITE_RADIUS * size, 255);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, float r, int alpha) {
        Matrix4f m = pose.pose();
        vertex(buffer, pose, m, -r, -r, 0f, 1f, alpha);
        vertex(buffer, pose, m, r, -r, 1f, 1f, alpha);
        vertex(buffer, pose, m, r, r, 1f, 0f, alpha);
        vertex(buffer, pose, m, -r, r, 0f, 0f, alpha);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, Matrix4f m,
                               float x, float y, float u, float v, int alpha) {
        buffer.addVertex(m, x, y, 0f)
                .setColor(255, 255, 255, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, 0f, 1f, 0f);
    }

    @Override
    public ResourceLocation getTextureLocation(HealingOrbEntity orb) {
        return CORE;
    }
}
