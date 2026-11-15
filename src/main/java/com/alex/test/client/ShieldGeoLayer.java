package com.alex.test.client;

import com.alex.test.entity.NpcEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;
import software.bernie.geckolib.util.RenderUtil;

/**
 * Draws a guard's or captain's shield while it is raised.
 *
 * <p>The shield is placed by the {@code shield} bone of the model
 * ({@code geo/base_villager.json}, under the left forearm), so it can be moved
 * and turned in Blockbench like any other part: the bone's box -- 12 wide, 22
 * tall, 1 thick, centred on the bone's pivot -- is where the shield's face plate
 * is drawn, front on the box's south face. The box itself is hidden in game
 * ({@code VillagerGeoModel}).
 *
 * <p>Blockbench shows an arm as two rigid boxes hinged at the elbow, but the
 * game draws it as one smooth bend ({@link BentLimb}), and the drawn forearm is
 * not where the rigid forearm box would be. Hung on the forearm bone, the shield
 * came out somewhere else than in Blockbench as soon as the arm bent. So the
 * shield is drawn from the arm's upper bone instead: the {@code shield} bone's
 * rest-pose pivot is carried through the same bend the arm is drawn with, then
 * turned by the bone's own rotation -- exactly how the sword follows the hand.
 *
 * <p>{@link HeldItemTransform#SHIELD} is applied on top for live fine-tuning with
 * {@code /helditem shield}; it is zero by default.
 */
public class ShieldGeoLayer<T extends NpcEntity> extends BlockAndItemGeoLayer<T> implements NpcGeoRenderer.PerBone {

    public static final String SHIELD_BONE = "shield";
    private static final ItemStack SHIELD = new ItemStack(Items.SHIELD);

    /**
     * The vanilla shield, drawn with no display transform, has its face plate
     * centred at (-0.5, -0.5, -0.5 + 1.5/16) of its own frame (the item frame's
     * half-block shift, then the plate's 1-pixel slab two pixels back, flipped).
     * Shifting by this puts the plate's centre on the bone's pivot.
     */
    private static final float PLATE_CENTRE_X = 0.5f;
    private static final float PLATE_CENTRE_Y = 0.5f;
    private static final float PLATE_CENTRE_Z = 0.5f - 1.5f / 16f;

    public ShieldGeoLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    /** Only an arm whose lower half carries the shield bone. */
    @Override
    public boolean usesBone(GeoBone bone) {
        GeoBone lower = BentLimb.lowerOf(bone);
        return lower != null && childNamed(lower, SHIELD_BONE) != null;
    }

    @Override
    public void renderForBone(PoseStack poseStack, T entity, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource,
                              VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        // Drawn from the arm's upper bone, the frame its bend is computed in.
        GeoBone lower = BentLimb.lowerOf(bone);
        GeoBone shieldBone = lower != null ? childNamed(lower, SHIELD_BONE) : null;
        if (shieldBone == null) return;
        if (!entity.showsShieldUp()) return;
        // Counted on the shield's own arm only (it was counted on every bone before).
        if (DetailCull.hidesItems()) {                 // too small on screen for a shield
            VillagerFrameStats.detailExtra();
            return;
        }

        Vector3f rest = new Vector3f(shieldBone.getPivotX() / 16f, shieldBone.getPivotY() / 16f,
                shieldBone.getPivotZ() / 16f);
        BentLimb.Grip grip = BentLimb.gripAt(bone, rest);
        if (grip == null) return;

        HeldItemTransform.Adjust tune = HeldItemTransform.SHIELD;
        poseStack.pushPose();
        poseStack.translate(grip.position().x, grip.position().y, grip.position().z);
        poseStack.mulPose(grip.rotation());
        // The bone's own move, in the forearm's frame (GeckoLib keys x negated). Clips whose
        // elbow spin was taken out (tools/ElbowUntwist.java) key it to keep the shield in place.
        poseStack.translate(-shieldBone.getPosX() / 16f, shieldBone.getPosY() / 16f, shieldBone.getPosZ() / 16f);
        // The bone's own turn, as set in Blockbench (GeckoLib's order: Z, Y, X).
        RenderUtil.rotateMatrixAroundBone(poseStack, shieldBone);
        poseStack.translate(tune.offsetX() / 16f, tune.offsetY() / 16f, tune.offsetZ() / 16f);
        poseStack.mulPose(Axis.XP.rotationDegrees(tune.rotX()));
        poseStack.mulPose(Axis.YP.rotationDegrees(tune.rotY()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(tune.rotZ()));
        float scale = tune.scale();
        poseStack.scale(scale, scale, scale);
        poseStack.translate(PLATE_CENTRE_X, PLATE_CENTRE_Y, PLATE_CENTRE_Z);

        if (!ReadyItem.render(entity, SHIELD, ItemDisplayContext.NONE, poseStack, bufferSource, packedLight,
                OverlayTexture.NO_OVERLAY))
            Minecraft.getInstance().getItemRenderer().renderStatic(entity, SHIELD, ItemDisplayContext.NONE,
                    false, poseStack, bufferSource, entity.level(), packedLight, OverlayTexture.NO_OVERLAY,
                    entity.getId());

        poseStack.popPose();
    }

    @Override
    protected ItemStack getStackForBone(GeoBone bone, T entity) {
        return null; // drawn by renderForBone above
    }

    private static GeoBone childNamed(GeoBone bone, String name) {
        for (GeoBone child : bone.getChildBones()) {
            if (child.getName().equals(name)) return child;
        }
        return null;
    }
}
