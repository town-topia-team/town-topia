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
 * The bard's upright bass, drawn while it plays (BASS_PLAY, BASS_JAM).
 *
 * <p>The model is {@code geo/bard_bass.geo.json} with {@code textures/entity/bard_bass.png}:
 * ten whole-pixel boxes, authored upright with the endpin tip at the origin, so it
 * opens in Blockbench on its own. Both are written by {@code tools/bass/BassTool.java}.
 *
 * <p>It is placed by the {@code bard_bass} bone of {@code geo/base_villager.json}: a root
 * bone with no boxes, its pivot on the floor where the endpin stands and its rotation the
 * bass's lean. The bass clips key that bone, tipping the bass with the hips' sway about
 * its planted endpin, and the hands in those clips were solved against exactly this
 * placement, so moving the bone in Blockbench means re-running the tool's compose step.
 */
public class BardBassGeoLayer<T extends NpcEntity> extends GeoRenderLayer<T> implements NpcGeoRenderer.PerBone {

    public static final String LOCATOR = "bard_bass";

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "geo/bard_bass.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "textures/entity/bard_bass.png");

    /** The bass bone of the last baked model, rebuilt when a resource reload bakes a new one. */
    private static BakedGeoModel bakedFor;
    private static GeoBone bassBone;

    /** Where this villager's bass goes, noted while its body was drawn; null when none is waiting. */
    @Nullable private PoseStack.Pose bassPose;
    @Nullable private T bassFor;
    private int bassLight;

    public BardBassGeoLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    /** Only its locator bone. */
    @Override
    public boolean usesBone(GeoBone bone) {
        return LOCATOR.equals(bone.getName());
    }

    @Override
    public void renderForBone(PoseStack poseStack, T entity, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource,
                              VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        if (!LOCATOR.equals(bone.getName())) return;
        if (entity.isInvisible() || entity.getProfession() != Profession.BARD
                || !entity.getAnimation().isBass()) return;
        if (DetailCull.hidesProps()) {                 // too small on screen for an instrument
            VillagerFrameStats.detailExtra();
            return;
        }
        GeoBone bass = bassBone();
        if (bass == null) return;

        poseStack.pushPose();
        // GeckoLib has turned the frame about the locator's pivot; the bass is authored
        // with its endpin at the origin, so carry it out to the pivot (baked: x already mirrored).
        RenderUtil.translateToPivotPoint(poseStack, bone);
        if (DrawBatching.afterBody) {
            // Drawn once the body is done; see DrawBatching.
            bassPose = DrawBatching.keep(poseStack);
            bassFor = entity;
            bassLight = packedLight;
        } else {
            drawCubes(poseStack, bass, bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), packedLight);
        }
        poseStack.popPose();
    }

    /** Draws the bass noted while the body was drawn, now that the body is done ({@link DrawBatching}). */
    @Override
    public void render(PoseStack poseStack, T entity, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick,
                       int packedLight, int packedOverlay) {
        if (bassPose == null) return;
        GeoBone bass = bassBone();
        if (bassFor == entity && bass != null) {
            drawCubes(DrawBatching.at(bassPose), bass,
                    bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), bassLight);
        }
        bassPose = null;
        bassFor = null;
    }

    private void drawCubes(PoseStack poseStack, GeoBone bass, VertexConsumer out, int packedLight) {
        GeoRenderer<T> renderer = getRenderer();
        for (GeoCube cube : bass.getCubes()) {
            poseStack.pushPose();
            renderer.renderCube(poseStack, cube, out, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            poseStack.popPose();
        }
    }

    private static GeoBone bassBone() {
        BakedGeoModel model = GeckoLibCache.getBakedModels().get(MODEL);
        if (model == null) return null;
        if (model != bakedFor) {
            bakedFor = model;
            bassBone = model.getBone("bass").orElse(null);
        }
        return bassBone;
    }
}
