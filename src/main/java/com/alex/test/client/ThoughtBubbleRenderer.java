package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws the icon a villager is thinking or saying as a camera-facing billboard
 * above its head.
 *
 * <p>Two quads: a bubble backdrop — a cloud for a private thought, a speech
 * balloon for a line in a conversation — and the icon inset into its body. The
 * backdrop matters more than it sounds: these icons are warm yellows and ochres
 * and read as mud against grass, sand or a birch wall without a plate behind
 * them.
 *
 * <p>Lit at full brightness on purpose. A thought is interface, not scenery: a
 * villager complaining about a missing bed at midnight is precisely when the
 * player needs to be able to read it.
 *
 * <p>Drawn with the <em>text</em> render type rather than an entity one, which
 * is the difference between a white plate and a grey one. Entity shaders run
 * {@code minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color)}
 * over the vertex normal, and a billboard's normal turns with the camera — so
 * the bubble was dimmed by a diffuse term that changed as the player walked
 * round the villager, and read as grey from most angles. {@code rendertype_text}
 * has no normal and no diffuse term: {@code vertexColor = Color * lightmap},
 * which with full-bright light is the texture exactly as authored. It is what
 * vanilla draws name tags with, for the same reason.
 *
 * <p>The fade is driven off {@link NpcEntity#bubbleChangedAtTick()} rather than
 * frame time, so it is identical whatever the framerate, and the entity keeps
 * its last icon after the server clears the field so there is something to fade
 * out.
 */
public final class ThoughtBubbleRenderer {

    /** Texture layout: a 128x128 bubble body with the tail in the rows below. */
    private static final float BUBBLE_ASPECT = 160f / 128f;

    /** Bubble width in blocks. */
    private static final float BUBBLE_WIDTH = 0.62f;

    /** Icon width as a fraction of the bubble's, and where its centre sits. */
    private static final float ICON_SCALE  = 0.656f;
    private static final float ICON_CENTRE = 64f / 160f;

    /**
     * How far in front of the backdrop the icon sits.
     *
     * <p>The two quads are otherwise coplanar, and each texture is its own
     * render type, so which one wins the depth test would come down to the
     * order the buffer source happens to flush them in. A hair of clearance
     * settles it: after the billboard rotation, +Z is towards the camera.
     */
    private static final float ICON_Z_LIFT = 0.005f;

    /**
     * Gap between the top of the head and the bubble's tail.
     *
     * <p>Sized to clear the name tag rather than the head: these villagers
     * always have one (the brain writes the current activity into it), it is
     * drawn from {@code bbHeight + 0.5} downwards, and at the obvious 0.28 the
     * tail came out straight through the middle of the text.
     */
    private static final float HEAD_CLEARANCE = 0.62f;

    /** Ticks the bubble takes to pop in or fade out. */
    private static final float FADE_TICKS = 4f;

    /** Bob amplitude in blocks, and its period in ticks. */
    private static final float BOB_AMPLITUDE = 0.035f;
    private static final float BOB_PERIOD    = 50f;

    /** Children are rendered at 0.6 scale, so their heads are lower. */
    private static final float CHILD_SCALE = 0.6f;

    /** Past this distance the bubble is too small to read and is skipped. */
    private static final double MAX_RENDER_DIST_SQ = 40.0 * 40.0;

    private static final ResourceLocation BUBBLE_THOUGHT =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "textures/thought/bubble_thought.png");
    private static final ResourceLocation BUBBLE_SPEECH =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "textures/thought/bubble_speech.png");

    private ThoughtBubbleRenderer() {}

    /** The backdrop behind the icon: a speech balloon, or a thought cloud. */
    public static ResourceLocation bubbleTexture(boolean speech) {
        return speech ? BUBBLE_SPEECH : BUBBLE_THOUGHT;
    }

    /**
     * Called from each villager renderer after the model has been drawn, with
     * the pose stack back at the entity's origin and world-aligned.
     */
    public static void render(NpcEntity entity, PoseStack poseStack,
                              MultiBufferSource bufferSource, float partialTick) {
        ThoughtIcon icon = entity.renderedBubbleIcon();
        if (icon == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getEntityRenderDispatcher().distanceToSqr(entity) > MAX_RENDER_DIST_SQ) return;

        float age   = (entity.tickCount + partialTick) - entity.bubbleChangedAtTick();
        float phase = Mth.clamp(age / FADE_TICKS, 0f, 1f);
        float alpha = entity.bubbleShowing() ? phase : 1f - phase;
        if (alpha <= 0.02f) return;

        // Ease the pop-in so a new bubble arrives with a little weight rather
        // than simply materialising at full size.
        float scale = entity.bubbleShowing() ? 0.75f + 0.25f * (alpha * (2f - alpha)) : 1f;
        int   packedAlpha = (int) (alpha * 255f);

        float headroom = entity.getBbHeight()
                * (entity.getProfession() == Profession.CHILD ? CHILD_SCALE : 1f);
        float bob = BOB_AMPLITUDE * Mth.sin(
                (entity.tickCount + partialTick) / BOB_PERIOD * Mth.TWO_PI);

        poseStack.pushPose();
        poseStack.translate(0.0f, headroom + HEAD_CLEARANCE + bob, 0.0f);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(scale, scale, scale);

        float width  = BUBBLE_WIDTH;
        float height = width * BUBBLE_ASPECT;
        // The tail hangs below the origin so the bubble points at the head; the
        // body then floats above it.
        float bottom = 0f;
        float top    = height;

        ResourceLocation backdrop = bubbleTexture(entity.renderedBubbleIsSpeech());
        quad(poseStack, bufferSource, backdrop,
                -width / 2f, bottom, width / 2f, top, 0f, packedAlpha);

        float iconSize   = width * ICON_SCALE;
        float iconCentre = top - height * ICON_CENTRE;
        net.minecraft.world.item.ItemStack shown = entity.bubbleItem();
        if (!shown.isEmpty()) {
            // Something shown off, asked about or priced: the item itself, as
            // it looks in an item frame, in the bubble's body. Items cannot
            // fade, so it pops in and out with the bubble's middle.
            if (alpha > 0.5f) {
                poseStack.pushPose();
                poseStack.translate(0f, iconCentre, ICON_Z_LIFT * 4f);
                poseStack.scale(iconSize * 1.1f, iconSize * 1.1f, iconSize * 1.1f);
                mc.getItemRenderer().renderStatic(shown, net.minecraft.world.item.ItemDisplayContext.FIXED,
                        net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                        poseStack, bufferSource, entity.level(), entity.getId());
                poseStack.popPose();
            }
        } else {
            quad(poseStack, bufferSource, icon.texture(),
                    -iconSize / 2f, iconCentre - iconSize / 2f,
                     iconSize / 2f, iconCentre + iconSize / 2f, ICON_Z_LIFT, packedAlpha);
        }

        poseStack.popPose();
    }

    /**
     * One flatly-lit, camera-facing quad.
     *
     * <p>{@code POSITION_COLOR_TEX_LIGHTMAP} is the text format: position,
     * colour, UV and light, and no normal or overlay to set — writing either
     * would not match the format.
     */
    private static void quad(PoseStack poseStack, MultiBufferSource bufferSource,
                             ResourceLocation texture,
                             float x0, float y0, float x1, float y1, float z, int alpha) {
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.text(texture));
        PoseStack.Pose pose = poseStack.last();
        vertex(buffer, pose, x0, y0, z, 0f, 1f, alpha);
        vertex(buffer, pose, x1, y0, z, 1f, 1f, alpha);
        vertex(buffer, pose, x1, y1, z, 1f, 0f, alpha);
        vertex(buffer, pose, x0, y1, z, 0f, 0f, alpha);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose,
                               float x, float y, float z, float u, float v, int alpha) {
        buffer.addVertex(pose, x, y, z)
              .setColor(255, 255, 255, alpha)
              .setUv(u, v)
              .setLight(LightTexture.FULL_BRIGHT);
    }
}
