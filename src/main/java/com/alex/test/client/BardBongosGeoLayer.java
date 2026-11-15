package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtil;

import javax.annotation.Nullable;

/**
 * The bongo bard's drums and the low stool it sits on, drawn while it plays (BONGOS_PLAY,
 * BONGOS_JAM).
 *
 * <p>The models are {@code geo/bard_bongos.geo.json} (bone "bongos") and
 * {@code geo/bard_stool.geo.json} (bone "stool"), each with its own texture, written by
 * {@code tools/art/BongosModel.java}. They are placed by two root bones of
 * {@code geo/base_villager.json} with no boxes: {@code bard_bongos} (the drums held in
 * front of the shins) and {@code bard_stool} (on the floor under the seat). Neither is
 * keyed by the clips, so both stay still while the body sways. The hands and legs in the
 * clips were solved against exactly these pivots (tools/bongos/BongosTool.java), so moving
 * either bone means re-running that tool's compose step.
 */
public class BardBongosGeoLayer<T extends NpcEntity> extends GeoRenderLayer<T> implements NpcGeoRenderer.PerBone {

    public static final String BONGOS_LOCATOR = "bard_bongos";
    public static final String STOOL_LOCATOR = "bard_stool";

    private static final ResourceLocation BONGOS_MODEL =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "geo/bard_bongos.geo.json");
    private static final ResourceLocation BONGOS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "textures/entity/bard_bongos.png");
    private static final ResourceLocation STOOL_MODEL =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "geo/bard_stool.geo.json");
    private static final ResourceLocation STOOL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "textures/entity/bard_stool.png");

    /** One prop: its model bone (cached per bake) and where this villager's goes this frame. */
    private final class Prop {
        final String locator, boneName;
        final ResourceLocation model, texture;
        @Nullable BakedGeoModel bakedFor;
        @Nullable GeoBone bone;
        /** Noted while the body was drawn; null when none is waiting. */
        @Nullable PoseStack.Pose pose;
        @Nullable T poseFor;
        int light;

        Prop(String locator, String boneName, ResourceLocation model, ResourceLocation texture) {
            this.locator = locator;
            this.boneName = boneName;
            this.model = model;
            this.texture = texture;
        }

        @Nullable GeoBone bone() {
            BakedGeoModel baked = GeckoLibCache.getBakedModels().get(model);
            if (baked == null) return null;
            if (baked != bakedFor) {
                bakedFor = baked;
                bone = baked.getBone(boneName).orElse(null);
            }
            return bone;
        }
    }

    private final Prop bongos = new Prop(BONGOS_LOCATOR, "bongos", BONGOS_MODEL, BONGOS_TEXTURE);
    private final Prop stool = new Prop(STOOL_LOCATOR, "stool", STOOL_MODEL, STOOL_TEXTURE);

    public BardBongosGeoLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    /** Only its two locator bones. */
    @Override
    public boolean usesBone(GeoBone bone) {
        return BONGOS_LOCATOR.equals(bone.getName()) || STOOL_LOCATOR.equals(bone.getName());
    }

    @Override
    public void renderForBone(PoseStack poseStack, T entity, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource,
                              VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        Prop prop = BONGOS_LOCATOR.equals(bone.getName()) ? bongos
                : STOOL_LOCATOR.equals(bone.getName()) ? stool : null;
        if (prop == null) return;
        if (entity.isInvisible() || entity.getProfession() != Profession.BARD
                || !entity.getAnimation().isBongos()) return;
        if (DetailCull.hidesProps()) {                 // too small on screen for a prop
            VillagerFrameStats.detailExtra();
            return;
        }
        GeoBone model = prop.bone();
        if (model == null) return;

        poseStack.pushPose();
        // Both props are authored with the middle of their bottom at the origin; carry
        // them out to the locator's pivot (baked: x already mirrored).
        RenderUtil.translateToPivotPoint(poseStack, bone);
        if (DrawBatching.afterBody) {
            // Drawn once the body is done; see DrawBatching.
            prop.pose = DrawBatching.keep(poseStack);
            prop.poseFor = entity;
            prop.light = packedLight;
        } else {
            drawCubes(poseStack, model, bufferSource.getBuffer(RenderType.entityCutoutNoCull(prop.texture)), packedLight);
        }
        poseStack.popPose();
    }

    /** Draws the props noted while the body was drawn, now that the body is done ({@link DrawBatching}). */
    @Override
    public void render(PoseStack poseStack, T entity, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick,
                       int packedLight, int packedOverlay) {
        drawKept(bongos, entity, bufferSource);
        drawKept(stool, entity, bufferSource);
    }

    private void drawKept(Prop prop, T entity, MultiBufferSource bufferSource) {
        if (prop.pose == null) return;
        GeoBone model = prop.bone();
        if (prop.poseFor == entity && model != null) {
            drawCubes(DrawBatching.at(prop.pose), model,
                    bufferSource.getBuffer(RenderType.entityCutoutNoCull(prop.texture)), prop.light);
        }
        prop.pose = null;
        prop.poseFor = null;
    }

    private void drawCubes(PoseStack poseStack, GeoBone model, VertexConsumer out, int packedLight) {
        GeoRenderer<T> renderer = getRenderer();
        for (GeoCube cube : model.getCubes()) {
            poseStack.pushPose();
            renderer.renderCube(poseStack, cube, out, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            poseStack.popPose();
        }
    }
}
