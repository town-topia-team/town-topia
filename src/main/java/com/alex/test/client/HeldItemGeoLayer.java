package com.alex.test.client;

import com.alex.test.animation.AnimationBlend;
import com.alex.test.animation.LumberChopSwing;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;
import software.bernie.geckolib.util.RenderUtil;

/**
 * Renders the NPC's held item on its rig's hand bone. While eating, the food
 * reserved in the offhand is shown on the animated eating hand instead. A
 * farmer's planting item uses that same centered hand placement while planting,
 * a farmer's shears use it at all times, as do display props such as the goods and
 * emerald a tradesman and the merchant hold up while trading.
 *
 * <p>Which bone that is, and how the item is placed on it, comes from
 * {@link HeldItemTransform} — the two rigs attach in different places, and the
 * placement is live-tunable via {@code /helditem}.
 *
 * <p>{@link #renderForBone} is overridden because no bone pivot is the grip. The
 * anchor is a point on the arm's own axis a couple of pixels up from the fingertips,
 * carried through the same deformation {@link BentLimb} drew the arm with, so the
 * tool is welded to the fist rather than to a joint several pixels away from it.
 */
public class HeldItemGeoLayer<T extends NpcEntity> extends BlockAndItemGeoLayer<T> implements NpcGeoRenderer.PerBone {

    /** Shared in-hand placement for eating, planting, and fruit shearing. */
    private static final float CENTERED_GRIP_UP = -1f;

    /** Bring the item in from the outside edge of the raised fist. */
    private static final float CENTERED_GRIP_SIDE = -1f;

    /** Raise the item by one model pixel relative to the raised hand. */
    private static final float CENTERED_VERTICAL_OFFSET = 1f / 16f;

    /** Nudge the item one pixel toward the villager's front in the base rig. */
    private static final float CENTERED_DEPTH_OFFSET = -1f / 16f;

    /** Vanilla generated items use scale 1 in FIXED and 0.55 in third person. */
    private static final float CENTERED_ITEM_SCALE = 0.55f;

    /** Keep each carried tool's grip inside the palm, clear of the fingertips. */
    private static final float GUARD_SWORD_CARRY_UP = 5f;
    private static final float WORK_TOOL_CARRY_UP = 3f;

    /** The farmer's hoe is drawn this much bigger in the walking grip and while tilling; the till clips were baked with it. */
    private static final float FARMER_HOE_SCALE = 1.45f;

    /**
     * The lumberjack's axe is drawn this much bigger, walking and chopping; the sideways chops
     * (LumberChopSwing) were baked with it. Two fists only fit on the handle clear of the arms
     * and chest from about 1.55x (1.45x sinks an arm into the chest).
     */
    private static final float LUMBERJACK_AXE_SCALE = 1.55f;

    /** Cancel each tool's action-pose inward nudge to center the walking grip. */
    private static final float GUARD_SWORD_CARRY_SIDE = 1.5f;
    private static final float WORK_TOOL_CARRY_SIDE = 2f;

    /** Slide each tool toward its head through the walking grip. */
    private static final float GUARD_SWORD_CARRY_SLIDE = 1f / 16f;
    private static final float WORK_TOOL_CARRY_SLIDE = 1.5f / 16f;

    /** Base rotation for carried tools. */
    private static final float TOOL_CARRY_ROT_Z = 90f;


    /** Vanilla's handheld transform leaves these diagonal tools 10 degrees off axis. */
    private static final float TOOL_CARRY_STRAIGHTEN_X = 10f;

    /** Roll around the tool axis; work tool heads face opposite the sword's. */
    private static final Quaternionf SWORD_CARRY_ROLL = new Quaternionf().rotationAxis(
            (float) (Math.PI / 2), 0f,
            (float) Math.cos(Math.toRadians(TOOL_CARRY_STRAIGHTEN_X)),
            -(float) Math.sin(Math.toRadians(TOOL_CARRY_STRAIGHTEN_X)));
    private static final Quaternionf WORK_TOOL_CARRY_ROLL = new Quaternionf().rotationAxis(
            (float) (-Math.PI / 2), 0f,
            (float) Math.cos(Math.toRadians(TOOL_CARRY_STRAIGHTEN_X)),
            -(float) Math.sin(Math.toRadians(TOOL_CARRY_STRAIGHTEN_X)));

    /**
     * The aimed bow's anchor: mid-fist, 2px back from the fingertips. Not the rig's
     * placement point -- that sits 3.4px past the fingertips to cancel vanilla's
     * third-person translation, and the aimed bow is drawn FIXED, which has none, so
     * on an arm pointed at the target the bow hung out in front with the string in
     * the hand instead of the grip.
     */
    private static final float AIMED_BOW_GRIP_UP = 2f;

    /**
     * Where a player's held item starts (ItemInHandLayer): on the arm's axis at the
     * fingertips, 2px toward the front, which on this rig is -z.
     */
    static final float PLAYER_HAND_Z = -2f;   // also SmithTongs' left hand

    /**
     * A player's hand frame, from this rig's forearm: vanilla model space is GeckoLib's
     * turned 180 about Z (LivingEntityRenderer's scale(-1, -1, 1)), then ItemInHandLayer
     * turns X -90 and Y 180 into the hand.
     */
    static final Quaternionf PLAYER_HAND = new Quaternionf()   // never changed: SmithTongs uses it too
            .rotationZ((float) Math.PI).rotateX((float) (-Math.PI / 2)).rotateY((float) Math.PI);

    public HeldItemGeoLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    /**
     * The left arm (the second hand) and whichever bone either rig hangs its held item
     * from; {@code /helditem} moving that bone tells the renderer to ask again
     * ({@link HeldItemTransform#setBone}).
     */
    @Override
    public boolean usesBone(GeoBone bone) {
        String name = bone.getName();
        if (LEFT_ARM_BONE.equals(name)) return true;
        for (HeldItemTransform rig : HeldItemTransform.profiles())
            if (rig.bone().equals(name)) return true;
        return false;
    }

    @Override
    public void renderForBone(PoseStack poseStack, T entity, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource,
                              VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        HeldItemTransform placement = HeldItemTransform.forEntity(entity);
        if (entity.usesBaseVillagerRig() && LEFT_ARM_BONE.equals(bone.getName())) {
            if (!DetailCull.hidesItems()) renderLeftHandItem(poseStack, entity, bone, bufferSource,
                    partialTick, packedLight, packedOverlay, placement);
            return;
        }
        if (!placement.bone().equals(bone.getName())) return;
        if (DetailCull.hidesItems()) {                 // too small on screen for a tool
            VillagerFrameStats.detailExtra();
            return;
        }

        if (entity.isHoldingOrb()) {
            renderHandOrb(poseStack, bone, entity, bufferSource, partialTick, placement);
        }

        ItemStack stack = cookRightHand(entity, smithRightHand(entity, farmerRightHand(entity, displayedStack(entity))));
        if (stack.isEmpty()) return;

        if (isBowAim(entity, stack)) {
            renderAimedBow(poseStack, bone, stack, entity, bufferSource, partialTick,
                    packedLight, packedOverlay, placement);
            return;
        }
        // A player's hold: the archer's bow, the smith's hammer drawing out a bar (SmithHammerHold).
        HeldItemTransform.Adjust playerHold = isBowCarry(entity, stack)
                ? HeldItemTransform.BOW_CARRY : SmithHammerHold.adjustFor(entity, stack);
        if (playerHold != null) {
            renderCarriedBow(poseStack, bone, stack, entity, bufferSource, partialTick,
                    packedLight, packedOverlay, placement, playerHold);
            return;
        }
        if (entity.isEating()) {
            renderEatenFood(poseStack, bone, stack, entity, bufferSource, partialTick,
                    packedLight, packedOverlay, placement);
            return;
        }

        HeldItemTransform.Adjust tool = HeldItemTransform.adjustFor(stack);
        // The farmer's hoe sits in the fist as in the walking grip, standing and walking, at the
        // axe's placement and FARMER_HOE_SCALE. Tilling, the till clip moves it from there into
        // both hands, as the lumberjack's chop does the axe (tools/till/twohand.js).
        boolean farmerTilling = entity.getAnimation() == VillagerAnimationState.TILL
                || entity.getAnimation() == VillagerAnimationState.TILL_SKILLED;
        boolean farmerHoeCarry = entity.getProfession() == Profession.FARMER && tool == HeldItemTransform.HOE
                && entity.getMocapClip() == 0 && (isOrdinaryCarry(entity) || farmerTilling);
        if (farmerHoeCarry) tool = HeldItemTransform.AXE;

        poseStack.pushPose();
        // The matrix already carries this bone's full animated transform: the renderer
        // ran prepMatrixForBone (translate -> pivot -> rotate -> scale -> un-pivot) before
        // handing control to the layers, leaving the origin at model origin. That is the
        // same frame BentLimb drew the limb's cubes in, so a swept point goes straight in.
        // The normal placement compensates for the third-person item's built-in
        // translation. Eating, planting, and shearing use the centred FIXED transform instead,
        // so the item origin can sit directly in the fist during those animations.
        boolean eating = entity.isEating() || entity.isReadingBook();
        boolean centeredHandItem = eating || isFarmerPlanting(entity) || isFarmerHoldingShears(entity, stack)
                || NpcEntity.isDisplayOnly(stack);
        boolean ordinaryCarry = isOrdinaryCarry(entity);
        boolean guardSwordCarry = ordinaryCarry && entity.getProfession().isGuardLike()
                && tool == HeldItemTransform.SWORD;
        // Felling a tree the lumberjack keeps the walking grip on its right fist, and the
        // chop clip turns and shifts the axe from there onto the line both hands hold.
        boolean lumberjackAxe = entity.getProfession() == Profession.LUMBERJACK
                && tool == HeldItemTransform.AXE && entity.getMocapClip() == 0;
        boolean twoHandChop = lumberjackAxe && entity.getAnimation() == VillagerAnimationState.LUMBER_CHOP;
        boolean lumberjackAxeCarry = lumberjackAxe && (ordinaryCarry || twoHandChop);
        boolean minerPickaxeCarry = ordinaryCarry && entity.getProfession() == Profession.MINER
                && tool == HeldItemTransform.PICKAXE;
        boolean workToolCarry = lumberjackAxeCarry || minerPickaxeCarry || farmerHoeCarry;
        boolean toolCarry = guardSwordCarry || workToolCarry;
        BentLimb.Grip grip = BentLimb.handGrip(bone,
                                               centeredHandItem ? CENTERED_GRIP_UP : placement.offsetY() + tool.offsetY()
                                                       + (guardSwordCarry ? GUARD_SWORD_CARRY_UP : 0f)
                                                       + (workToolCarry ? WORK_TOOL_CARRY_UP : 0f),
                                               centeredHandItem ? CENTERED_GRIP_SIDE : placement.offsetX() + tool.offsetX()
                                                       + (guardSwordCarry ? GUARD_SWORD_CARRY_SIDE : 0f)
                                                       + (workToolCarry ? WORK_TOOL_CARRY_SIDE : 0f),
                                               centeredHandItem ? 0f : placement.offsetZ() + tool.offsetZ());
        if (grip == null) {
            // Not a limb -- nothing to hang an item on, but place it rather than drop it.
            RenderUtil.translateToPivotPoint(poseStack, bone);
        } else {
            poseStack.translate(grip.position().x, grip.position().y, grip.position().z);
            if (centeredHandItem) {
                // Undo the upper-arm rotation so the centered item's height and
                // depth offsets stay in the villager's frame as the arm moves.
                Vector3f centeredOffset = new Quaternionf()
                        .rotateZ(bone.getRotZ()).rotateY(bone.getRotY()).rotateX(bone.getRotX())
                        .invert().transform(new Vector3f(0, CENTERED_VERTICAL_OFFSET, CENTERED_DEPTH_OFFSET));
                poseStack.translate(centeredOffset.x(), centeredOffset.y(), centeredOffset.z());
            }
            poseStack.mulPose(grip.rotation());
            if (lumberjackAxeCarry) applyAxeHold(poseStack, entity, twoHandChop, partialTick, entity.lumberChopSwing());
            else if (farmerHoeCarry) applyAxeHold(poseStack, entity, farmerTilling, partialTick, null);
        }

        poseStack.mulPose(Axis.XP.rotationDegrees(placement.rotX() + tool.rotX()));
        poseStack.mulPose(Axis.YP.rotationDegrees(placement.rotY() + tool.rotY()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(placement.rotZ() + tool.rotZ()
                + (toolCarry ? TOOL_CARRY_ROT_Z : 0f)));
        if (toolCarry) {
            poseStack.mulPose(Axis.XP.rotationDegrees(TOOL_CARRY_STRAIGHTEN_X));
            poseStack.mulPose(guardSwordCarry ? SWORD_CARRY_ROLL : WORK_TOOL_CARRY_ROLL);
            // The vanilla handheld sprite runs 10 degrees off local Y before its display
            // transform. Move the item along that axis without moving its hand anchor.
            float slide = guardSwordCarry ? GUARD_SWORD_CARRY_SLIDE : WORK_TOOL_CARRY_SLIDE;
            poseStack.translate(0f,
                    slide * Math.cos(Math.toRadians(TOOL_CARRY_STRAIGHTEN_X)),
                    -slide * Math.sin(Math.toRadians(TOOL_CARRY_STRAIGHTEN_X)));
        }
        float scale = placement.scale() * tool.scale() * (centeredHandItem ? CENTERED_ITEM_SCALE : 1f)
                * (farmerHoeCarry ? FARMER_HOE_SCALE : 1f) * (lumberjackAxeCarry ? LUMBERJACK_AXE_SCALE : 1f);
        poseStack.scale(scale, scale, scale);

        renderStackForBone(poseStack, bone, stack, entity, bufferSource, partialTick, packedLight, packedOverlay);

        poseStack.popPose();
    }

    /**
     * Eating: where the fist closes on the food (+Z is the forearm's back). Tied to the eat
     * clip, which was baked with these numbers (tools/eat sketch GRIP_UP, GRIP_FWD, GRIP_TURN,
     * GRIP_TILT, FOOD_SLIDE, GRIP_SPIN); change them together or the bite misses the mouth.
     */
    private static final float EAT_GRIP_UP = 1.5f, EAT_GRIP_FORWARD = -0.4f;
    /** The food is turned in the hand toward the face, so the forearm itself never twists (10-17c). */
    private static final float EAT_FOOD_TURN = 34.8f;
    /** Then leans out of the fist, so with the fist at the right cheek its end reaches across into the mouth. */
    private static final float EAT_FOOD_TILT = 75.6f;
    /** Slid along its own length toward the far end (px): the fist holds the far half, the near half shows. */
    private static final float EAT_FOOD_SLIDE = -0.84f;
    /** Turned about its own length so its face shows rather than its edge. */
    private static final float EAT_FOOD_SPIN = 0f;
    /** Vanilla food sprites run corner to corner; this lays the long way along the hold. */
    private static final float EAT_FOOD_ROLL = -45f;

    /**
     * The food while eating, placed as in the tools/eat sketch the eat clip was
     * baked from: there the clip brings the food's far end onto the mouth at
     * every bite, so this hold and the clip go together.
     */
    private void renderEatenFood(PoseStack poseStack, GeoBone bone, ItemStack stack, T entity,
                                 MultiBufferSource bufferSource, float partialTick,
                                 int packedLight, int packedOverlay, HeldItemTransform placement) {
        poseStack.pushPose();
        BentLimb.Grip grip = BentLimb.handGrip(bone, EAT_GRIP_UP, placement.offsetX(),
                placement.offsetZ() + EAT_GRIP_FORWARD);
        if (grip == null) {
            RenderUtil.translateToPivotPoint(poseStack, bone);
        } else {
            poseStack.translate(grip.position().x, grip.position().y, grip.position().z);
            poseStack.mulPose(grip.rotation());
        }
        // Forearm frame: +X the villager's right, +Y up the arm, -Z its front.
        poseStack.mulPose(Axis.YP.rotationDegrees(EAT_FOOD_TURN));
        poseStack.mulPose(Axis.XP.rotationDegrees(EAT_FOOD_TILT));
        poseStack.translate(0f, EAT_FOOD_SLIDE / 16f, 0f);
        poseStack.mulPose(Axis.YP.rotationDegrees(EAT_FOOD_SPIN));
        poseStack.mulPose(Axis.ZP.rotationDegrees(EAT_FOOD_ROLL));
        poseStack.scale(CENTERED_ITEM_SCALE, CENTERED_ITEM_SCALE, CENTERED_ITEM_SCALE);
        renderStackForBone(poseStack, bone, stack, entity, bufferSource, partialTick, packedLight, packedOverlay);
        poseStack.popPose();
    }

    /** The boxless bone the lumberjack_chop_* and farmer_till_* clips key the two-handed tool place on (geo/base_villager.json). */
    private static final String AXE_HOLD_BONE = "axe_hold";

    /** The two-handed hold on a lumberjack or farmer, kept to ease out of and to turn in from. */
    private static final class AxeHold {
        /** The clip's own hold, last read off the bone. */
        final Vector3f position = new Vector3f();
        final Quaternionf rotation = new Quaternionf();
        /** When the chop ended (ticks and partial), or -1 while it plays. */
        float endedAt = -1f;
        /** What was drawn last frame (none yet: the plain grip), and where a turn in starts from. */
        final Vector3f shownPosition = new Vector3f(), fromPosition = new Vector3f();
        final Quaternionf shownRotation = new Quaternionf(), fromRotation = new Quaternionf();
        /** The work clip start the current turn in belongs to. */
        int turnClipStart = Integer.MIN_VALUE;
    }

    /** Per lumberjack or farmer, render thread only; dropped with the entity or once eased out. */
    private static final java.util.WeakHashMap<NpcEntity, AxeHold> AXE_HOLDS = new java.util.WeakHashMap<>();

    /**
     * The turn and shift from the walking grip to where both hands hold the axe, as
     * the lumberjack_chop_* clips key them on {@link #AXE_HOLD_BONE}: in the fist's frame, applied
     * the way GeckoLib applies a bone's own keys (move, then Z, Y, X). The clip was
     * baked so that this, on the fist BentLimb draws, puts the handle in both hands
     * (tools/chop, {@code axeHoldNow}). Once the chop stops, the bone drops its keys at
     * once, so the last hold is eased back to the plain grip over the work clip's blend
     * out, as the arms are.
     *
     * <p>{@code turnInto}: the lumberjack's swing. Its hold sits 140 to 180 degrees off the
     * walking grip, and the clip's own blend in eases the three keyed angles one by one, so
     * the axe wobbled round in his fist. Over the blend in it turns instead about one
     * axis, eased, from whatever was drawn (the walking grip, the hold easing out, or the
     * last swing) to the swing's first frame, which the clip then carries on from.
     */
    private void applyAxeHold(PoseStack poseStack, T entity, boolean chopping, float partialTick,
                              LumberChopSwing turnInto) {
        AxeHold hold = AXE_HOLDS.get(entity);
        if (chopping) {
            GeoBone bone = getGeoModel().getAnimationProcessor().getBone(AXE_HOLD_BONE);
            if (bone == null) return;
            if (hold == null) AXE_HOLDS.put(entity, hold = new AxeHold());
            hold.position.set(-bone.getPosX() / 16f, bone.getPosY() / 16f, bone.getPosZ() / 16f);
            hold.rotation.identity().rotateZ(bone.getRotZ()).rotateY(bone.getRotY()).rotateX(bone.getRotX());
            hold.endedAt = -1f;
            float blend = VillagerAnimationState.WORK_BLEND_TICKS;
            float into = turnInto == null ? blend : entity.workClipTicks(partialTick);
            if (into < blend) {
                if (hold.turnClipStart != entity.workClipStartTick()) {
                    hold.turnClipStart = entity.workClipStartTick();
                    hold.fromPosition.set(hold.shownPosition);
                    hold.fromRotation.set(hold.shownRotation);
                }
                float u = Math.max(0f, into) / blend;
                u = u * u * (3f - 2f * u);
                hold.fromPosition.lerp(new Vector3f(-turnInto.firstHoldPosition(0) / 16f,
                        turnInto.firstHoldPosition(1) / 16f, turnInto.firstHoldPosition(2) / 16f), u, hold.shownPosition);
                hold.fromRotation.slerp(new Quaternionf()
                        .rotateZ((float) Math.toRadians(turnInto.firstHoldRotation(2)))
                        .rotateY((float) Math.toRadians(-turnInto.firstHoldRotation(1)))
                        .rotateX((float) Math.toRadians(-turnInto.firstHoldRotation(0))), u, hold.shownRotation);
            } else {
                hold.shownPosition.set(hold.position);
                hold.shownRotation.set(hold.rotation);
            }
            poseStack.translate(hold.shownPosition.x, hold.shownPosition.y, hold.shownPosition.z);
            poseStack.mulPose(hold.shownRotation);
            return;
        }
        if (hold == null) return;
        float now = entity.tickCount + partialTick;
        if (hold.endedAt < 0f) {
            hold.endedAt = now;
            // ease out from what was last drawn, which mid turn in is not the clip's own hold
            hold.position.set(hold.shownPosition);
            hold.rotation.set(hold.shownRotation);
        }
        float left = AnimationBlend.enabled(AnimationBlend.Group.WORK)
                ? 1f - (now - hold.endedAt) / AnimationBlend.outTicks(AnimationBlend.Group.WORK) : 0f;
        if (left <= 0f) {
            AXE_HOLDS.remove(entity);
            return;
        }
        hold.position.mul(left, hold.shownPosition);
        new Quaternionf().slerp(hold.rotation, left, hold.shownRotation);
        poseStack.translate(hold.shownPosition.x, hold.shownPosition.y, hold.shownPosition.z);
        poseStack.mulPose(hold.shownRotation);
    }

    /** The palm orb's anchor: in the fist, a pixel up from the fingertips. */
    private static final float ORB_GRIP_UP = 1f;
    /** How far above the fist the orb floats, straight up in the world, in blocks. */
    private static final float ORB_LIFT = 0.17f;
    /** The palm orb is a little smaller than the thrown one. */
    private static final float ORB_HAND_SIZE = 0.8f;

    /**
     * The cleric's healing orb, floating on top of its right hand from the start
     * of a throw until it leaves. Placed at the fist through the same bend the
     * arm is drawn with, then lifted straight up in the world, so it rests above
     * the palm whatever angle the forearm is at.
     */
    private void renderHandOrb(PoseStack poseStack, GeoBone bone, T entity, MultiBufferSource bufferSource,
                               float partialTick, HeldItemTransform placement) {
        poseStack.pushPose();
        if (entity.hasRawLimbs()) {
            // Rigid boxes: the fist is wherever the forearm box's own transform puts it.
            if (!BentLimb.toRigidHandPoint(poseStack, bone, ORB_GRIP_UP, placement.offsetX(), placement.offsetZ())) {
                RenderUtil.translateToPivotPoint(poseStack, bone);
            }
        } else {
            BentLimb.Grip grip = BentLimb.handGrip(bone, ORB_GRIP_UP, placement.offsetX(), placement.offsetZ());
            if (grip == null) {
                RenderUtil.translateToPivotPoint(poseStack, bone);
            } else {
                poseStack.translate(grip.position().x, grip.position().y, grip.position().z);
            }
        }
        Vector3f fist = poseStack.last().pose().transformPosition(new Vector3f());
        poseStack.last().pose().setTranslation(fist.x, fist.y + ORB_LIFT, fist.z);
        HealingOrbRenderer.drawAt(poseStack, bufferSource, entity.tickCount + partialTick, ORB_HAND_SIZE);
        poseStack.popPose();
        // The pose is camera-relative: add the camera back to tell the entity
        // where in the world its sparkles belong.
        net.minecraft.world.phys.Vec3 camera = net.minecraft.client.Minecraft.getInstance()
                .gameRenderer.getMainCamera().getPosition();
        entity.noteHandOrbDrawn(camera.add(fist.x, fist.y + ORB_LIFT, fist.z));
    }

    @Override
    protected ItemStack getStackForBone(GeoBone bone, T entity) {
        return HeldItemTransform.forEntity(entity).bone().equals(bone.getName())
                ? displayedStack(entity)
                : null;
    }

    /** The library book: drawn only, never in any inventory, so no tool is displaced. */
    private static final ItemStack READING_BOOK = new ItemStack(Items.BOOK);

    private static ItemStack displayedStack(NpcEntity entity) {
        // A reader shows the book in place of whatever it carries; the held
        // tool is untouched and reappears the moment the read pose ends.
        if (entity.isReadingBook()) return READING_BOOK;
        // A guard looking around at its post does it empty handed; the sword
        // is still in its hand and shows again when the look ends.
        if (entity.isLookingAround()) return ItemStack.EMPTY;
        // Asleep in bed, or dozing on a Town Hall seat or floor: hands drawn empty.
        // The tool stays in hand and shows again on waking.
        if (entity.isSleeping()) return ItemStack.EMPTY;
        VillagerAnimationState anim = entity.getAnimation();
        if (anim == VillagerAnimationState.SLEEP_LYING_DOWN
                || anim == VillagerAnimationState.SLEEP_SITTING_DOWN) return ItemStack.EMPTY;
        // Against a wall the hands rest over the knees from the end of the sit-down to the
        // start of the stand-up: a tool there would go through the shins.
        if (anim.isWallSleep()) return ItemStack.EMPTY;
        // Both hands are on the bard's bass or violin: one on the neck, one on the strings or the bow.
        if (anim.isOnTheBeat()) return ItemStack.EMPTY;
        // Working at a crafting table both hands are on the grid; the tool shows again after.
        if (anim == VillagerAnimationState.CRAFT_TABLE) return ItemStack.EMPTY;
        // Washing at a cauldron both hands are in the water or on the farmer; the tool shows again after.
        if (anim == VillagerAnimationState.WASH) return ItemStack.EMPTY;
        // EatActivity reserves the current serving in the offhand. It shows in the
        // eating hand only from when it leaves the pocket until the last piece is
        // eaten; the tool in the main hand stays put away for the whole meal.
        if (entity.isEating()) {
            return entity.showsFoodInHand()
                    ? entity.getOffhandItem() : ItemStack.EMPTY;
        }
        return entity.getMainHandItem();
    }

    /**
     * Planting, pulling up crops or picking (berries, glow berries, cocoa, cane): whatever is
     * in hand sits in the fist the same way, as in the tools/field and tools/pick previews.
     */
    private static boolean isFarmerPlanting(NpcEntity entity) {
        VillagerAnimationState animation = entity.getAnimation();
        return entity.getProfession() == Profession.FARMER
                && (animation == VillagerAnimationState.PLANT
                        || animation == VillagerAnimationState.HARVEST
                        || animation.isFarmerPick());
    }

    /** The left upper arm on the base rig: the farmer's second hand for the crop or fruit. */
    private static final String LEFT_ARM_BONE = "left_arm";
    /**
     * Boxless bones the farmer_plant_*, farmer_harvest_* and farmer_pick_* clips key at
     * position y 1 while something is in that fist, 0 while it is empty (geo/base_villager.json;
     * baked by tools/field and tools/pick). pick_item is the left fist, pick_item_r the right.
     */
    private static final String PICK_ITEM_BONE = "pick_item", PICK_ITEM_R_BONE = "pick_item_r";

    private boolean fistFull(String flagBone) {
        GeoBone flag = getGeoModel().getAnimationProcessor().getBone(flagBone);
        return flag != null && flag.getPosY() > 0.5f;
    }

    /**
     * The farmer's right fist at the new field clips. Planting: the seed (or cane, berry,
     * bone meal) shows from when it comes out of the pouch until it goes into the soil.
     * Pulling up crops: the hand is empty but for the crop it has just plucked.
     */
    private ItemStack farmerRightHand(T entity, ItemStack held) {
        if (entity.getProfession() != Profession.FARMER || entity.getMocapClip() != 0) return held;
        VillagerAnimationState animation = entity.getAnimation();
        if (animation == VillagerAnimationState.PLANT) return fistFull(PICK_ITEM_R_BONE) ? held : ItemStack.EMPTY;
        if (animation == VillagerAnimationState.HARVEST) {
            return fistFull(PICK_ITEM_R_BONE) ? fieldCrop(entity) : ItemStack.EMPTY;
        }
        return held;
    }

    /**
     * The left fist: the farmer's crop being pulled up, or the berries, glow berries, cocoa
     * beans or cane just cut, from the catch to the pocket; the blacksmith's book. Placed as the preview pages do:
     * the right hand's centred grip mirrored (1 px in from the other side, the side turns
     * reversed), FIXED.
     */
    private void renderLeftHandItem(PoseStack poseStack, T entity, GeoBone bone, MultiBufferSource bufferSource,
                                      float partialTick, int packedLight, int packedOverlay,
                                      HeldItemTransform placement) {
        if (entity.getMocapClip() != 0) return;
        // The smith's tongs and hot bar at the anvil, held like a player's tool (round 3, tools/anvil).
        if (SmithTongs.render(poseStack, entity, bone, getGeoModel().getAnimationProcessor().getBone(SmithTongs.HOLD_BONE),
                bufferSource, packedLight, packedOverlay)) return;
        ItemStack stack = entity.getAnimation().isCookWork() ? cookLeftHand(entity)
                : entity.getProfession() == Profession.BLACKSMITH ? smithLeftHand(entity) : farmerLeftHand(entity);
        if (stack.isEmpty()) return;

        poseStack.pushPose();
        BentLimb.Grip grip = BentLimb.handGrip(bone, CENTERED_GRIP_UP, -CENTERED_GRIP_SIDE, 0f);
        if (grip == null) {
            RenderUtil.translateToPivotPoint(poseStack, bone);
        } else {
            poseStack.translate(grip.position().x, grip.position().y, grip.position().z);
            Vector3f centeredOffset = new Quaternionf()
                    .rotateZ(bone.getRotZ()).rotateY(bone.getRotY()).rotateX(bone.getRotX())
                    .invert().transform(new Vector3f(0, CENTERED_VERTICAL_OFFSET, CENTERED_DEPTH_OFFSET));
            poseStack.translate(centeredOffset.x(), centeredOffset.y(), centeredOffset.z());
            poseStack.mulPose(grip.rotation());
        }
        poseStack.mulPose(Axis.XP.rotationDegrees(placement.rotX()));
        poseStack.mulPose(Axis.YP.rotationDegrees(-placement.rotY()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-placement.rotZ()));
        float scale = placement.scale() * CENTERED_ITEM_SCALE;
        poseStack.scale(scale, scale, scale);
        renderStackForBone(poseStack, bone, stack, entity, bufferSource, partialTick, packedLight, packedOverlay);
        poseStack.popPose();
    }

    /** What the farmer's left fist holds: the crop being pulled up or the fruit just cut. */
    private ItemStack farmerLeftHand(T entity) {
        if (entity.getProfession() != Profession.FARMER) return ItemStack.EMPTY;
        VillagerAnimationState animation = entity.getAnimation();
        boolean harvest = animation == VillagerAnimationState.HARVEST;
        if (!harvest && !animation.isFarmerPick()) return ItemStack.EMPTY;
        // Planting cocoa or composting, the main hand holds what goes in; nothing is picked.
        ItemStack held = entity.getMainHandItem();
        if (!harvest && !held.isEmpty() && !held.is(Items.SHEARS)) return ItemStack.EMPTY;
        if (!fistFull(PICK_ITEM_BONE)) return ItemStack.EMPTY;
        return harvest ? fieldCrop(entity) : pickedFruit(entity, animation);
    }

    private static final ItemStack ENCHANTED_BOOK = new ItemStack(Items.ENCHANTED_BOOK), RAW_IRON = new ItemStack(Items.RAW_IRON);
    private static ItemStack smithHammer;   // null until first drawn

    /** The Smith's Hammer prop, made once the item registry is ready. */
    private static ItemStack smithHammer() {
        if (smithHammer == null) smithHammer = new ItemStack(com.alex.test.ModItems.SMITH_HAMMER.get());
        return smithHammer;
    }

    /**
     * The blacksmith's right fist at its work clips (tools/forge): the hammer at the anvil,
     * nothing at the smithing table (both hands on the piece), the raw ore at the furnace until
     * it goes into the mouth. Fitting a book it holds the tool, which BookFitting puts in its hand.
     */
    private ItemStack smithRightHand(T entity, ItemStack held) {
        if (entity.getProfession() != Profession.BLACKSMITH || entity.getMocapClip() != 0) return held;
        VillagerAnimationState animation = entity.getAnimation();
        if (animation.isSmithHammering()) return smithHammer();
        if (animation == VillagerAnimationState.SMITH_SMITHING) return ItemStack.EMPTY;
        if (animation == VillagerAnimationState.SMITH_FURNACE) return fistFull(PICK_ITEM_R_BONE) ? RAW_IRON : ItemStack.EMPTY;
        return held;
    }

    /** The blacksmith's left fist: the enchanted book while it fits one to a tool. */
    private ItemStack smithLeftHand(T entity) {
        VillagerAnimationState animation = entity.getAnimation();
        boolean fitting = animation == VillagerAnimationState.SMITH_ENCHANT || animation == VillagerAnimationState.SMITH_ENCHANT_TAP;
        return fitting && fistFull(PICK_ITEM_BONE) ? ENCHANTED_BOOK : ItemStack.EMPTY;
    }

    private static final ItemStack BOWL = new ItemStack(Items.BOWL);
    private static ItemStack cookSpoon, cookKnife;   // null until first drawn

    /** The Cook's spoon and knife props, made once the item registry is ready. */
    private static ItemStack cookSpoon() {
        if (cookSpoon == null) cookSpoon = new ItemStack(com.alex.test.ModItems.COOK_SPOON.get());
        return cookSpoon;
    }

    private static ItemStack cookKnife() {
        if (cookKnife == null) cookKnife = new ItemStack(com.alex.test.ModItems.COOK_KNIFE.get());
        return cookKnife;
    }

    /**
     * Which thing a fist holds in a cook clip: its flag bone is keyed at position y = 1 or 2
     * while that fist holds the first or second thing of the clip, 0 while it is empty
     * (tools/cook); read to the nearest whole number.
     */
    private int fistSlot(String flagBone) {
        GeoBone flag = getGeoModel().getAnimationProcessor().getBone(flagBone);
        return flag == null ? 0 : Math.round(flag.getPosY());
    }

    /**
     * The right fist in any cook clip (docs/plans/2026-10-24-cook-animations.md, the "hands"
     * column): the spoon, the knife (then the spoon, chopping and stirring), or what the cook
     * is working with ({@link NpcEntity#getCookProp}: fuel, raw food, the dish). Empty
     * between, and in every clip with nothing in that hand; the real tool shows again after.
     */
    private ItemStack cookRightHand(T entity, ItemStack held) {
        VillagerAnimationState animation = entity.getAnimation();
        if (!animation.isCookWork() || entity.getMocapClip() != 0) return held;
        int slot = fistSlot(PICK_ITEM_R_BONE);
        return switch (animation) {
            case COOK_WHISK, COOK_WAIT_SPOON, COOK_CAMP_TURN -> slot == 1 ? cookSpoon() : ItemStack.EMPTY;
            case COOK_WHITTLE -> slot == 1 ? cookKnife() : ItemStack.EMPTY;
            case COOK_CHOP_STIR -> slot == 1 ? cookKnife() : slot == 2 ? cookSpoon() : ItemStack.EMPTY;
            case COOK_COOKIES, COOK_PRESS_CANE, COOK_FIRE_LIGHT, COOK_FIRE_FEED, COOK_LOAD, COOK_TAKE,
                 COOK_CAMP_LAY, COOK_CAMP_TAKE, COOK_PRESENT -> slot == 1 ? entity.getCookProp() : ItemStack.EMPTY;
            default -> ItemStack.EMPTY;
        };
    }

    /** The left fist in any cook clip: the bowl (cake, stew, whittling), or the gilded food or cane. */
    private ItemStack cookLeftHand(T entity) {
        if (fistSlot(PICK_ITEM_BONE) != 1) return ItemStack.EMPTY;
        return switch (entity.getAnimation()) {
            case COOK_WHISK, COOK_CHOP_STIR, COOK_WHITTLE -> BOWL;
            case COOK_GILD, COOK_PRESS_CANE -> entity.getCookProp();
            default -> ItemStack.EMPTY;
        };
    }

    private static final ItemStack WHEAT = new ItemStack(Items.WHEAT), CARROT = new ItemStack(Items.CARROT),
            POTATO = new ItemStack(Items.POTATO), BEETROOT = new ItemStack(Items.BEETROOT),
            SWEET_BERRIES = new ItemStack(Items.SWEET_BERRIES), GLOW_BERRIES = new ItemStack(Items.GLOW_BERRIES),
            COCOA_BEANS = new ItemStack(Items.COCOA_BEANS), SUGAR_CANE = new ItemStack(Items.SUGAR_CANE);
    /** Other (modded) crops: the block's own item, made once per block. */
    private static final java.util.Map<Block, ItemStack> CROP_ITEMS = new java.util.HashMap<>();

    /**
     * What a farmland harvest pulls up: the crop he stands on (field work stands on the
     * tile itself), or else the one just ahead of him.
     */
    private static ItemStack fieldCrop(NpcEntity entity) {
        ItemStack crop = cropItem(entity.level().getBlockState(entity.blockPosition()));
        if (!crop.isEmpty()) return crop;
        return cropItem(entity.level().getBlockState(ahead(entity, 0.8, 0)));
    }

    private static ItemStack cropItem(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.WHEAT) return WHEAT;
        if (block == Blocks.CARROTS) return CARROT;
        if (block == Blocks.POTATOES) return POTATO;
        if (block == Blocks.BEETROOTS) return BEETROOT;
        if (!(block instanceof net.minecraft.world.level.block.CropBlock)) return ItemStack.EMPTY;
        return CROP_ITEMS.computeIfAbsent(block, b -> new ItemStack(b.asItem()));
    }

    private static final int[] LOW_HEIGHTS = {0, -1, 1}, MID_HEIGHTS = {1, 0, 2}, HIGH_HEIGHTS = {2, 3, 1};

    /**
     * What a pick takes: the fruit in front of him (the pick spot is 0.5 to 0.8 blocks from
     * it), at the height the clip reaches to (low: at his feet or below, middle: a block up,
     * high: two up), or at the height next to it.
     */
    private static ItemStack pickedFruit(NpcEntity entity, VillagerAnimationState animation) {
        int[] heights = animation == VillagerAnimationState.PICK ? MID_HEIGHTS
                : animation == VillagerAnimationState.PICK_HIGH ? HIGH_HEIGHTS : LOW_HEIGHTS;
        for (double distance : new double[] {0.8, 0.4}) {
            for (int up : heights) {
                ItemStack fruit = fruitItem(entity.level().getBlockState(ahead(entity, distance, up)));
                if (!fruit.isEmpty()) return fruit;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack fruitItem(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.SWEET_BERRY_BUSH) return SWEET_BERRIES;
        if (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT) return GLOW_BERRIES;
        if (block == Blocks.COCOA) return COCOA_BEANS;
        if (block == Blocks.SUGAR_CANE) return SUGAR_CANE;
        return ItemStack.EMPTY;
    }

    /** The block {@code distance} blocks in front of his body and {@code up} above his feet block. */
    private static BlockPos ahead(NpcEntity entity, double distance, int up) {
        float yaw = entity.yBodyRot * Mth.DEG_TO_RAD;
        return BlockPos.containing(entity.getX() - Mth.sin(yaw) * distance,
                entity.getY() + 0.01 + up, entity.getZ() + Mth.cos(yaw) * distance);
    }

    /**
     * Shears in a farmer's hand always sit in the fist like a planted seed, while
     * walking and idling as well as harvesting. Hoes and axes keep the normal grip.
     */
    private static boolean isFarmerHoldingShears(NpcEntity entity, ItemStack stack) {
        return entity.getProfession() == Profession.FARMER && stack.is(Items.SHEARS);
    }

    /** An archer drawing its bow (the AIM clip). */
    private static boolean isBowAim(NpcEntity entity, ItemStack stack) {
        return entity.getAnimation() == VillagerAnimationState.AIM && stack.is(Items.BOW);
    }

    /** Standing or walking, not previewing a mocap clip: tools get their walking grip. */
    private static boolean isOrdinaryCarry(NpcEntity entity) {
        VillagerAnimationState animation = entity.getAnimation();
        return (animation == VillagerAnimationState.IDLE || animation == VillagerAnimationState.WALK)
                && entity.getMocapClip() == 0;
    }

    /** An archer standing or walking with its bow. */
    private static boolean isBowCarry(NpcEntity entity, ItemStack stack) {
        return isOrdinaryCarry(entity) && entity.getProfession() == Profession.ARCHER && stack.is(Items.BOW);
    }

    /**
     * The bow while an archer stands or walks, held exactly the way a player holds one:
     * vanilla's hand placement (ItemInHandLayer) and the bow's own third-person
     * transform, so the fist closes on the bow's metal band and the string runs
     * through the forearm. The forearm's bend is carried through, so it follows the
     * arm. {@link HeldItemTransform#BOW_CARRY} nudges it from there. The blacksmith's hammer at
     * the anvil is held the same way, nudged by {@code carry} = SMITH_HAMMER_HOLD (SmithHammerHold).
     */
    private void renderCarriedBow(PoseStack poseStack, GeoBone bone, ItemStack stack, T entity,
                                  MultiBufferSource bufferSource, float partialTick,
                                  int packedLight, int packedOverlay, HeldItemTransform placement,
                                  HeldItemTransform.Adjust carry) {
        poseStack.pushPose();
        BentLimb.Grip grip = BentLimb.handGrip(bone, 0f, placement.offsetX(), placement.offsetZ() + PLAYER_HAND_Z);
        if (grip == null) {
            RenderUtil.translateToPivotPoint(poseStack, bone);
        } else {
            poseStack.translate(grip.position().x, grip.position().y, grip.position().z);
            poseStack.mulPose(grip.rotation());
        }
        // Forearm frame: +X the villager's right, +Y up the arm, -Z its front.
        poseStack.translate(carry.offsetX() / 16f, carry.offsetY() / 16f, -carry.offsetZ() / 16f);
        poseStack.mulPose(PLAYER_HAND);
        poseStack.mulPose(Axis.YP.rotationDegrees(carry.rotY()));
        poseStack.mulPose(Axis.XP.rotationDegrees(carry.rotX()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(carry.rotZ()));
        poseStack.scale(carry.scale(), carry.scale(), carry.scale());
        renderStackForBone(poseStack, bone, stack, entity, bufferSource, partialTick, packedLight, packedOverlay);
        poseStack.popPose();
    }

    /**
     * The bow while aiming: upright in front of the bow hand, string toward the
     * archer's face. The normal grip points an item along the forearm like a
     * sword, which with the arm raised laid the bow flat, pointing at the target.
     * So the bow is placed at the fist but turned in the villager's body frame
     * (the arm's own rotation undone), with the flat FIXED sprite, by
     * {@link HeldItemTransform#BOW_AIM}.
     */
    private void renderAimedBow(PoseStack poseStack, GeoBone bone, ItemStack stack, T entity,
                                MultiBufferSource bufferSource, float partialTick,
                                int packedLight, int packedOverlay, HeldItemTransform placement) {
        poseStack.pushPose();
        BentLimb.Grip grip = BentLimb.handGrip(bone, AIMED_BOW_GRIP_UP, placement.offsetX(), placement.offsetZ());
        if (grip == null) {
            RenderUtil.translateToPivotPoint(poseStack, bone);
        } else {
            poseStack.translate(grip.position().x, grip.position().y, grip.position().z);
        }
        // prepMatrixForBone turned the frame by this arm's Z, Y, X rotation, and by every
        // bone above it; undo them all, arm first. The bow is then aligned to where the
        // villager faces (the target), not to its torso: the shot clip turns the upper
        // body side-on, as an archer stands, and the bow has to stay on the arrow's line.
        for (GeoBone b = bone; b != null; b = b.getParent()) {
            poseStack.mulPose(new Quaternionf()
                    .rotateZ(b.getRotZ()).rotateY(b.getRotY()).rotateX(b.getRotX()).invert());
        }
        HeldItemTransform.Adjust aim = HeldItemTransform.BOW_AIM;
        // Body frame: +Y up, -Z the villager's front.
        poseStack.translate(aim.offsetX() / 16f, aim.offsetY() / 16f, -aim.offsetZ() / 16f);
        poseStack.mulPose(Axis.YP.rotationDegrees(aim.rotY()));
        poseStack.mulPose(Axis.XP.rotationDegrees(aim.rotX()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(aim.rotZ()));
        poseStack.scale(aim.scale(), aim.scale(), aim.scale());
        renderStackForBone(poseStack, bone, stack, entity, bufferSource, partialTick, packedLight, packedOverlay);
        poseStack.popPose();
    }

    /**
     * A crowd stand-in ({@link StandIn}) holds a flat cut-out of its tool: 2 faces instead of ~70.
     * Everyone else's item is written from a ready copy of Minecraft's drawing ({@link ReadyItem}).
     */
    @Override
    protected void renderStackForBone(PoseStack poseStack, GeoBone bone, ItemStack stack, T entity,
                                      MultiBufferSource bufferSource, float partialTick,
                                      int packedLight, int packedOverlay) {
        ItemDisplayContext context = getTransformTypeForStack(bone, stack, entity);
        if (StandIn.drawing() && FlatItem.render(entity, stack, context, poseStack, bufferSource,
                packedLight, packedOverlay))
            return;
        if (ReadyItem.render(entity, stack, context, poseStack, bufferSource, packedLight, packedOverlay)) return;
        super.renderStackForBone(poseStack, bone, stack, entity, bufferSource, partialTick, packedLight, packedOverlay);
    }

    @Override
    protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, T entity) {
        if (isBowAim(entity, stack)) return ItemDisplayContext.FIXED;
        if (entity.usesBaseVillagerRig() && LEFT_ARM_BONE.equals(bone.getName())) return ItemDisplayContext.FIXED;
        if (entity.isEating() || entity.isReadingBook()
                || isFarmerPlanting(entity) || isFarmerHoldingShears(entity, stack)
                || NpcEntity.isDisplayOnly(stack))
            return ItemDisplayContext.FIXED;

        // The two rigs hang the item off opposite arms, so match the context to the arm:
        // dynamic_villagers' "bone" is the left forearm, base_villager's "bone2" the right.
        //
        // This is a constant per (item model, context) and can never be a source of drift:
        // BlockAndItemGeoLayer always passes leftHand=false to ItemRenderer.renderStatic,
        // so no mirroring happens either. For vanilla handheld items the two contexts are
        // in fact identical — ItemTransforms falls back to the right-hand entry when a
        // model omits thirdperson_lefthand, which every vanilla tool does — so this only
        // bites for a modded item that defines a genuinely different left-hand transform.
        return entity.usesBaseVillagerRig()
                ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                : ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
    }
}
