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
import java.util.ArrayList;
import java.util.List;

/**
 * The bard's violin and bow, drawn while it plays them (PLAY_VIOLIN, VIOLIN_PLAY, VIOLIN_DANCE).
 *
 * <p>The model is {@code geo/bard_violin.geo.json} with {@code textures/entity/bard_violin.png}:
 * whole-pixel boxes in the bass's style, bones {@code violin} (authored lying on its back,
 * tail at the origin, scroll toward north) and {@code bow} (frog at the origin, tip toward
 * +x). Both are written by {@code tools/violin/ViolinTool.java}.
 *
 * <p>They are placed by the {@code bard_violin} and {@code bard_bow} bones of
 * {@code geo/base_villager.json}: children of {@code hips} with no boxes, as the arms are.
 * The violin's locator is never keyed, so it stays put on the shoulder whatever moves the
 * torso; the clips key the bow's to follow the right hand. The hands in those clips were
 * solved against exactly these placements, so moving either bone in Blockbench means
 * re-running the tool's compose step.
 */
public class BardViolinGeoLayer<T extends NpcEntity> extends GeoRenderLayer<T> implements NpcGeoRenderer.PerBone {

    public static final String VIOLIN_LOCATOR = "bard_violin";
    public static final String BOW_LOCATOR = "bard_bow";

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "geo/bard_violin.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "textures/entity/bard_violin.png");

    /** The violin and bow bones of the last baked model, rebuilt when a resource reload bakes a new one. */
    private static BakedGeoModel bakedFor;
    private static GeoBone violinBone, bowBone;

    /** Violin and bow noted while this villager's body was drawn ({@link DrawBatching}). */
    private final List<PoseStack.Pose> partPoses = new ArrayList<>();
    private final List<GeoBone> partBones = new ArrayList<>();
    @Nullable private T partsFor;
    private int partsLight;

    public BardViolinGeoLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    /** Only its two locator bones. */
    @Override
    public boolean usesBone(GeoBone bone) {
        return VIOLIN_LOCATOR.equals(bone.getName()) || BOW_LOCATOR.equals(bone.getName());
    }

    @Override
    public void renderForBone(PoseStack poseStack, T entity, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource,
                              VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        boolean violin = VIOLIN_LOCATOR.equals(bone.getName());
        if (!violin && !BOW_LOCATOR.equals(bone.getName())) return;
        if (entity.isInvisible() || entity.getProfession() != Profession.BARD
                || !entity.getAnimation().isViolin()) return;
        if (DetailCull.hidesProps()) {                 // too small on screen for an instrument
            VillagerFrameStats.detailExtra();
            return;
        }
        if (!refresh()) return;
        GeoBone part = violin ? violinBone : bowBone;
        if (part == null) return;

        poseStack.pushPose();
        // GeckoLib has turned the frame about the locator's pivot; both parts are authored
        // about their own origin, so carry them out to the pivot (baked: x already mirrored).
        RenderUtil.translateToPivotPoint(poseStack, bone);
        if (DrawBatching.afterBody) {
            // Drawn once the body is done, violin and bow in one go; see DrawBatching.
            if (partsFor != entity) {
                partPoses.clear();
                partBones.clear();
                partsFor = entity;
            }
            partPoses.add(DrawBatching.keep(poseStack));
            partBones.add(part);
            partsLight = packedLight;
        } else {
            drawCubes(poseStack, part, bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), packedLight);
        }
        poseStack.popPose();
    }

    /** Draws the violin and bow noted while the body was drawn, with one texture request ({@link DrawBatching}). */
    @Override
    public void render(PoseStack poseStack, T entity, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick,
                       int packedLight, int packedOverlay) {
        if (partPoses.isEmpty()) return;
        if (partsFor == entity) {
            VertexConsumer out = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
            for (int i = 0; i < partPoses.size(); i++) {
                drawCubes(DrawBatching.at(partPoses.get(i)), partBones.get(i), out, partsLight);
            }
        }
        partPoses.clear();
        partBones.clear();
        partsFor = null;
    }

    private void drawCubes(PoseStack poseStack, GeoBone part, VertexConsumer out, int packedLight) {
        GeoRenderer<T> renderer = getRenderer();
        for (GeoCube cube : part.getCubes()) {
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
            violinBone = model.getBone("violin").orElse(null);
            bowBone = model.getBone("bow").orElse(null);
        }
        return true;
    }
}
