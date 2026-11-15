package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import javax.annotation.Nullable;

/**
 * A captain's cape: the player's cape, hung from the villager's shoulders.
 *
 * <p>Same box (10 by 16 by 1 pixels) and texture layout (64 by 32) as a vanilla
 * cape, so any player cape image can be dropped in as
 * {@code textures/entity/captain_cape.png}. It swings the vanilla way: a point
 * trailing behind the villager (NpcEntity.moveCloak) tilts it back when
 * walking and lifts it on a fall, and the walk cycle makes it bob.
 *
 * <p>Drawn when the renderer reaches the torso bone, in that bone's animated
 * frame, so it stays on the back through every animation that leans or
 * twists the body.
 */
public class CapeGeoLayer<T extends NpcEntity> extends GeoRenderLayer<T> implements NpcGeoRenderer.PerBone {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "textures/entity/captain_cape.png");

    /** Top of the villager's body (model pixels): where the cape hangs from. */
    private static final float SHOULDER_Y = 24f / 16f;
    /**
     * The body's back face: half its 6-pixel depth. Vanilla pins the cape's
     * inner face exactly on the back the same way (0.125 on a player's 4-deep
     * body), so the top sits on the shoulders and only the swing lifts it off.
     */
    private static final float BACK_Z = 3f / 16f;

    private final ModelPart cape;
    /** Where this villager's cape goes, noted while its body was drawn; null when none is waiting. */
    @Nullable private PoseStack.Pose capePose;
    @Nullable private T capeFor;
    private int capeLight;

    public CapeGeoLayer(GeoRenderer<T> renderer) {
        super(renderer);
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("cape",
                CubeListBuilder.create().texOffs(0, 0).addBox(-5f, 0f, -1f, 10f, 16f, 1f),
                PartPose.ZERO);
        this.cape = LayerDefinition.create(mesh, 64, 32).bakeRoot().getChild("cape");
    }

    /** Only the torso. */
    @Override
    public boolean usesBone(GeoBone bone) {
        return isTorso(bone);
    }

    @Override
    public void renderForBone(PoseStack poseStack, T entity, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource,
                              VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        if (!isTorso(bone)) return;
        if (entity.getProfession() != Profession.CAPTAIN || entity.isInvisible()) return;
        // A set's own captain skin dresses its shoulders itself (pauldrons, plume): no cape over it.
        if (VillagerGeoModel.ownCaptainSkin()) return;
        if (DetailCull.hidesProps()) {                 // too small on screen for a cape
            VillagerFrameStats.detailExtra();
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0f, SHOULDER_Y, BACK_Z);
        // Into vanilla's model frame (y down), where its cape maths is written.
        poseStack.scale(-1f, -1f, 1f);

        // The swing is worked out once a tick (NpcEntity.moveCloak); blend the last two.
        float back = Mth.lerp(partialTick, entity.capeBackO, entity.capeBack);
        float side = Mth.lerp(partialTick, entity.capeSideO, entity.capeSide);
        float lift = Mth.lerp(partialTick, entity.capeLiftO, entity.capeLift);

        poseStack.mulPose(Axis.XP.rotationDegrees(6f + back / 2f + lift));
        poseStack.mulPose(Axis.ZP.rotationDegrees(side / 2f));
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - side / 2f));

        if (DrawBatching.afterBody) {
            // Drawn once the body is done; see DrawBatching.
            capePose = DrawBatching.keep(poseStack);
            capeFor = entity;
            capeLight = packedLight;
        } else {
            VertexConsumer capeBuffer = bufferSource.getBuffer(RenderType.entitySolid(TEXTURE));
            cape.render(poseStack, capeBuffer, packedLight, OverlayTexture.NO_OVERLAY);
        }
        poseStack.popPose();
    }

    /** Draws the cape noted while the body was drawn, now that the body is done ({@link DrawBatching}). */
    @Override
    public void render(PoseStack poseStack, T entity, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick,
                       int packedLight, int packedOverlay) {
        if (capePose == null) return;
        if (capeFor == entity) {
            cape.render(DrawBatching.at(capePose), bufferSource.getBuffer(RenderType.entitySolid(TEXTURE)),
                    capeLight, OverlayTexture.NO_OVERLAY);
        }
        capePose = null;
        capeFor = null;
    }

    /**
     * The bone that carries the torso cube. On the base_villager rig that is
     * {@code hips} -- {@code body} is only an empty root at the feet, which
     * never bobs or sways with the walk. The original rig puts the cube on
     * {@code body} itself.
     */
    private static boolean isTorso(GeoBone bone) {
        return "hips".equals(bone.getName())
                || ("body".equals(bone.getName()) && !bone.getCubes().isEmpty());
    }
}
