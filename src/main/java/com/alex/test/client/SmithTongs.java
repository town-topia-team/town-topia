package com.alex.test.client;

import com.alex.test.ModItems;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.util.RenderUtil;

/**
 * The blacksmith's tongs and the hot bar in them, in its left fist while it draws out a bar at the anvil
 * (round 3 of docs/plans/2026-10-24-blacksmith-anvil-redo.md). The sketch's hold (tools/anvil/anvil.js,
 * itemMatrix "L"): a player's hold (fingertips, 2 px forward, PLAYER_HAND) mirrored for the left hand, then
 * the clip's own turns from the boxless bone {@code tongs_hold}: rotation keyed [w, t, spin] (GeckoLib stores
 * x and y negated), position y = how hot the bar is (1 fresh from the fire, 0.55 cooled).
 * The mirror is built into the two models (tools/art/SmithTongsModel.java), so the display turn and the
 * quarter turn here are the sketch's, mirrored. Drawn with ItemDisplayContext.NONE: the models carry no turn.
 */
final class SmithTongs {

    static final String HOLD_BONE = "tongs_hold";

    /** The fist's middle from the hold point (sketch WRIST_PIVOT): 2 px up the arm, 2 back onto its axis. */
    private static final float PIVOT_UP = 2f / 16f, PIVOT_BACK = 2f / 16f;
    /** The tongs in the fist (sketch TONGS.display: rotation 0, -90, 55, translation 0, 4, 0.5, scale 0.85), mirrored. */
    private static final Quaternionf DISPLAY_TURN = new Quaternionf()
            .rotationXYZ(0f, 90f * Mth.DEG_TO_RAD, -55f * Mth.DEG_TO_RAD);
    private static final float DISPLAY_UP = 4f / 16f, DISPLAY_FORWARD = 0.5f / 16f, DISPLAY_SCALE = 0.85f;
    private static final float HALF = (float) Math.sqrt(0.5);
    /**
     * The quarter turn's line, from the model's middle: the bar's middle at the jaws (8.1, 12.6, 8), turned -45
     * about z through (8, 8, 8) as the boxes are, then mirrored; it runs along the bar.
     */
    private static final Vector3f SPIN_AT = new Vector3f(-(0.1f + 4.6f) * HALF, (4.6f - 0.1f) * HALF, 0f).div(16f);
    private static final Vector3f SPIN_AXIS = new Vector3f(-HALF, HALF, 0f);

    /** The bar is tinted in steps of heat, one ready stack per step. */
    private static final int HEAT_STEPS = 10;
    private static final ItemStack[] BARS = new ItemStack[HEAT_STEPS + 1];
    private static ItemStack tongs;   // null until first drawn
    /** The bar's colour fresh from the fire. */
    private static final int HOT = heatColor(1f);

    private SmithTongs() {}

    /**
     * Draws the tongs and the bar in the left fist ({@code arm} = left_arm) if the smith is drawing out a bar;
     * false (nothing drawn) otherwise. {@code hold} is the tongs_hold bone, or null.
     */
    static boolean render(PoseStack poseStack, NpcEntity entity, GeoBone arm, GeoBone hold,
                          MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (hold == null || entity.getProfession() != Profession.BLACKSMITH
                || entity.getAnimation() != VillagerAnimationState.SMITH_ANVIL_DRAW) return false;
        float wrist = -hold.getRotX() * Mth.RAD_TO_DEG, turn = -hold.getRotY() * Mth.RAD_TO_DEG;
        float spin = hold.getRotZ();
        float heat = Mth.clamp(hold.getPosY(), 0f, 1f);

        poseStack.pushPose();
        BentLimb.Grip grip = BentLimb.handGrip(arm, 0f, 0f, HeldItemGeoLayer.PLAYER_HAND_Z);
        if (grip == null) {
            RenderUtil.translateToPivotPoint(poseStack, arm);
        } else {
            poseStack.translate(grip.position().x, grip.position().y, grip.position().z);
            poseStack.mulPose(grip.rotation());
        }
        // The clip's turn of the tongs in the fist, about the fist's middle: tipped in the swing plane, then sideways.
        poseStack.translate(0f, PIVOT_UP, PIVOT_BACK);
        poseStack.mulPose(Axis.XP.rotationDegrees(wrist));
        poseStack.mulPose(Axis.ZP.rotationDegrees(turn));
        poseStack.translate(0f, -PIVOT_UP, -PIVOT_BACK);
        poseStack.mulPose(HeldItemGeoLayer.PLAYER_HAND);
        poseStack.translate(0f, DISPLAY_UP, DISPLAY_FORWARD);
        poseStack.mulPose(DISPLAY_TURN);
        poseStack.scale(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE);
        // The bar's quarter turn about its own length (mirrored, so the other way round).
        poseStack.translate(SPIN_AT.x, SPIN_AT.y, SPIN_AT.z);
        poseStack.mulPose(new Quaternionf().rotationAxis(-spin, SPIN_AXIS.x, SPIN_AXIS.y, SPIN_AXIS.z));
        poseStack.translate(-SPIN_AT.x, -SPIN_AT.y, -SPIN_AT.z);

        if (tongs == null) tongs = new ItemStack(ModItems.SMITH_TONGS.get());
        draw(entity, tongs, poseStack, buffers, packedLight, packedOverlay);
        draw(entity, bar(heat), poseStack, buffers, glow(packedLight, heat), packedOverlay);
        poseStack.popPose();
        return true;
    }

    private static void draw(NpcEntity entity, ItemStack stack, PoseStack poseStack, MultiBufferSource buffers,
                             int light, int overlay) {
        if (ReadyItem.render(entity, stack, ItemDisplayContext.NONE, poseStack, buffers, light, overlay)) return;
        Minecraft.getInstance().getItemRenderer().renderStatic(entity, stack, ItemDisplayContext.NONE, false,
                poseStack, buffers, entity.level(), light, overlay, entity.getId());
    }

    /** The bar glows: full block light fresh from the fire, a little less as it cools, never darker than around it. */
    private static int glow(int packedLight, float heat) {
        int block = Math.max(LightTexture.block(packedLight), Math.round(9f + 6f * heat));
        return LightTexture.pack(block, LightTexture.sky(packedLight));
    }

    /** The bar for this heat, tinted (cached per step from 0.5 to 1). */
    private static ItemStack bar(float heat) {
        int step = Math.round(Mth.clamp((heat - 0.5f) * 2f, 0f, 1f) * HEAT_STEPS);
        ItemStack bar = BARS[step];
        if (bar == null) {
            bar = new ItemStack(ModItems.SMITH_BAR.get());
            bar.set(DataComponents.DYED_COLOR, new DyedItemColor(heatColor(0.5f + 0.5f * step / HEAT_STEPS), false));
            BARS[step] = bar;
        }
        return bar;
    }

    /** The sketch's bar colour: red 1, green 0.25 + 0.55 heat, blue 0.05 + 0.25 of the heat above 0.6. Yellow to orange. */
    private static int heatColor(float heat) {
        int green = Math.round(255f * (0.25f + 0.55f * heat));
        int blue = Math.round(255f * (0.05f + 0.25f * Math.max(0f, heat - 0.6f)));
        return 0xFF0000 | green << 8 | blue;
    }

    /** The item colour for the bar's tinted faces (registered in NpcClientEvents). */
    static int barColor(ItemStack stack, int tintIndex) {
        if (tintIndex != 0) return -1;
        DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
        return 0xFF000000 | (dyed != null ? dyed.rgb() : HOT);
    }
}
