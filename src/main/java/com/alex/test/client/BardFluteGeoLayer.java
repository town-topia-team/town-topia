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
 * The bard's flute, drawn while it plays it (PLAY_FLUTE, FLUTE_PLAY, FLUTE_DANCE).
 *
 * <p>The model is {@code geo/bard_flute.geo.json} with {@code textures/entity/bard_flute.png}:
 * one whole-pixel silver tube with its keys painted on, bone {@code flute} (authored along
 * x, the embouchure hole at the origin, the foot toward the villager's right). It is
 * written by {@code tools/flute/FluteTool.java}.
 *
 * <p>It is placed by the {@code bard_flute} bone of {@code geo/base_villager.json}: a child of
 * the head with no boxes, at the mouth under the nose. It is never keyed, so the flute
 * stays at the lips whatever the head does; the clips solved both hands onto exactly this
 * placement, so moving the bone in Blockbench means re-running the tool's compose step.
 */
public class BardFluteGeoLayer<T extends NpcEntity> extends GeoRenderLayer<T> implements NpcGeoRenderer.PerBone {

    public static final String FLUTE_LOCATOR = "bard_flute";

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "geo/bard_flute.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "textures/entity/bard_flute.png");

    /** The flute bone of the last baked model, rebuilt when a resource reload bakes a new one. */
    private static BakedGeoModel bakedFor;
    private static GeoBone fluteBone;

    /** The flute noted while this villager's body was drawn ({@link DrawBatching}). */
    @Nullable private PoseStack.Pose partPose;
    @Nullable private T partFor;
    private int partLight;

    public BardFluteGeoLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    /** Only its locator bone. */
    @Override
    public boolean usesBone(GeoBone bone) {
        return FLUTE_LOCATOR.equals(bone.getName());
    }

    @Override
    public void renderForBone(PoseStack poseStack, T entity, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource,
                              VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        if (!FLUTE_LOCATOR.equals(bone.getName())) return;
        if (entity.isInvisible() || entity.getProfession() != Profession.BARD
                || !entity.getAnimation().isFlute()) return;
        if (DetailCull.hidesProps()) {                 // too small on screen for an instrument
            VillagerFrameStats.detailExtra();
            return;
        }
        if (!refresh() || fluteBone == null) return;

        poseStack.pushPose();
        // GeckoLib has turned the frame about the locator's pivot; the flute is authored
        // about its own origin, so carry it out to the pivot (baked: x already mirrored).
        RenderUtil.translateToPivotPoint(poseStack, bone);
        if (DrawBatching.afterBody) {
            // Drawn once the body is done; see DrawBatching.
            partPose = DrawBatching.keep(poseStack);
            partFor = entity;
            partLight = packedLight;
        } else {
            drawCubes(poseStack, bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), packedLight);
        }
        poseStack.popPose();
    }

    /** Draws the flute noted while the body was drawn ({@link DrawBatching}). */
    @Override
    public void render(PoseStack poseStack, T entity, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick,
                       int packedLight, int packedOverlay) {
        if (partPose == null) return;
        if (partFor == entity) {
            drawCubes(DrawBatching.at(partPose), bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), partLight);
        }
        partPose = null;
        partFor = null;
    }

    private void drawCubes(PoseStack poseStack, VertexConsumer out, int packedLight) {
        GeoRenderer<T> renderer = getRenderer();
        for (GeoCube cube : fluteBone.getCubes()) {
            poseStack.pushPose();
            renderer.renderCube(poseStack, cube, out, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            poseStack.popPose();
        }
    }

    private static boolean refresh() {
        BakedGeoModel model = GeckoLibCache.getBakedModels().get(MODEL);
        if (model == null) return false;
        if (model != bakedFor) {
            bakedFor = model;
            fluteBone = model.getBone("flute").orElse(null);
        }
        return true;
    }
}
