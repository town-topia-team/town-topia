package com.alex.test.client;

import com.alex.test.animation.FastController;
import com.alex.test.animation.FastPose;
import com.alex.test.entity.NpcEntity;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationProcessor;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.EasingType;
import software.bernie.geckolib.animation.keyframe.AnimationPoint;
import software.bernie.geckolib.animation.keyframe.BoneAnimationQueue;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

import java.util.Map;

/**
 * The posing step for villagers ({@link VillagerGeoModel#getAnimationProcessor}), plain
 * villager FPS plan V1. {@link #tickAnimation} is GeckoLib 4.8.4's own, copied, with two changes
 * that leave every bone where GeckoLib would put it:
 * <ul>
 *   <li>Easing back to rest: GeckoLib works the ease out for every bone of the model that no
 *       track moved this frame (74, three channels each), even the ones already at rest with
 *       nothing to ease. Here a channel already at rest, in its saved state too, and not just
 *       let go by a clip, is left as it is: GeckoLib would set it to the same rest values.</li>
 *   <li>The saved states are found once per villager by bone order, not by name three times
 *       per bone per frame.</li>
 * </ul>
 * Only for villagers whose tracks are all {@link FastController}s and only after their first
 * posed frame (GeckoLib's own handles that one); anything else, or {@link FastPose} off, is
 * GeckoLib's own processor. Bones are registered in GeckoLib's map too, so either way works.
 */
final class FastAnimationProcessor<T extends GeoAnimatable> extends AnimationProcessor<T> {

    /** The registered bones by name, as GeckoLib keeps them (its map is private). */
    private final Map<String, GeoBone> boneMap = new Object2ObjectOpenHashMap<>();
    private GeoBone[] boneList = new GeoBone[0];
    private boolean boneListStale = true;

    FastAnimationProcessor(GeoModel<T> model) {
        super(model);
    }

    @Override
    public void setActiveModel(BakedGeoModel model) {
        this.boneMap.clear();
        this.boneListStale = true;
        super.setActiveModel(model);
    }

    @Override
    public void registerGeoBone(GeoBone bone) {
        // GeckoLib's own registers the children through this same method.
        super.registerGeoBone(bone);
        this.boneMap.put(bone.getName(), bone);
        this.boneListStale = true;
    }

    private GeoBone[] bones() {
        if (this.boneListStale) {
            this.boneList = this.boneMap.values().toArray(new GeoBone[0]);
            this.boneListStale = false;
        }
        return this.boneList;
    }

    @Override
    public void tickAnimation(T animatable, GeoModel<T> model, AnimatableManager<T> animatableManager, double animTime,
                              AnimationState<T> state, boolean crashWhenCantFindBone) {
        // Safe and GeckoLib posing both run GeckoLib's own step; FastController filters the bones in safe.
        if (FastPose.mode() != FastPose.Mode.FULL || animatableManager.isFirstTick() || !(animatable instanceof NpcEntity npc)
                || !allFast(animatableManager)) {
            super.tickAnimation(animatable, model, animatableManager, animTime, state, crashWhenCantFindBone);
            return;
        }
        try {
            fastTick(npc, animatable, model, animatableManager, animTime, state, crashWhenCantFindBone);
        } catch (LinkageError e) {
            FastPose.broken(e);
        }
    }

    private static boolean allFast(AnimatableManager<?> manager) {
        for (AnimationController<?> controller : manager.getAnimationControllers().values())
            if (!(controller instanceof FastController<?>))
                return false;
        return true;
    }

    /** GeckoLib 4.8.4's {@code tickAnimation}, line for line except where marked. */
    @SuppressWarnings("unchecked")
    private void fastTick(NpcEntity npc, T animatable, GeoModel<T> model, AnimatableManager<T> animatableManager,
                          double animTime, AnimationState<T> state, boolean crashWhenCantFindBone) {
        GeoBone[] bones = bones();
        Map<String, BoneSnapshot> boneSnapshots = animatableManager.getBoneSnapshotCollection();
        // Changed: GeckoLib's updateBoneSnapshots, plus each bone's saved state in bone order.
        BoneSnapshot[] saved = savedSnapshots(npc, bones, boneSnapshots);

        long tracks = ClientCostLog.partStart();
        // Plan W0: on a sampled villager the tracks' "what should play" checks are timed apart.
        FastController.timeChecks = tracks != 0L;
        long checksBefore = FastController.checkNanos;
        for (AnimationController<T> controller : animatableManager.getAnimationControllers().values()) {
            FastController<T> fast = (FastController<T>) controller;

            if (this.reloadAnimations) {
                controller.forceAnimationReset();
                controller.getBoneAnimationQueues().clear();
            }

            fast.justStarting(animatableManager.isFirstTick());

            state.withController(controller);
            controller.process(model, state, this.boneMap, boneSnapshots, animTime, crashWhenCantFindBone);

            Map<String, BoneAnimationQueue> queues = controller.getBoneAnimationQueues();
            if (queues.isEmpty())
                continue;                                   // a stopped track: nothing to apply

            for (BoneAnimationQueue boneAnimation : queues.values()) {
                GeoBone bone = boneAnimation.bone();
                BoneSnapshot snapshot = boneSnapshots.get(bone.getName());
                BoneSnapshot initialSnapshot = bone.getInitialSnapshot();

                AnimationPoint rotXPoint = boneAnimation.rotationXQueue().poll();
                AnimationPoint rotYPoint = boneAnimation.rotationYQueue().poll();
                AnimationPoint rotZPoint = boneAnimation.rotationZQueue().poll();
                AnimationPoint posXPoint = boneAnimation.positionXQueue().poll();
                AnimationPoint posYPoint = boneAnimation.positionYQueue().poll();
                AnimationPoint posZPoint = boneAnimation.positionZQueue().poll();
                AnimationPoint scaleXPoint = boneAnimation.scaleXQueue().poll();
                AnimationPoint scaleYPoint = boneAnimation.scaleYQueue().poll();
                AnimationPoint scaleZPoint = boneAnimation.scaleZQueue().poll();
                EasingType easingType = fast.easingOverride();

                if (rotXPoint != null && rotYPoint != null && rotZPoint != null) {
                    bone.setRotX((float)EasingType.lerpWithOverride(rotXPoint, easingType) + initialSnapshot.getRotX());
                    bone.setRotY((float)EasingType.lerpWithOverride(rotYPoint, easingType) + initialSnapshot.getRotY());
                    bone.setRotZ((float)EasingType.lerpWithOverride(rotZPoint, easingType) + initialSnapshot.getRotZ());
                    snapshot.updateRotation(bone.getRotX(), bone.getRotY(), bone.getRotZ());
                    snapshot.startRotAnim();
                    bone.markRotationAsChanged();
                }

                if (posXPoint != null && posYPoint != null && posZPoint != null) {
                    bone.setPosX((float)EasingType.lerpWithOverride(posXPoint, easingType));
                    bone.setPosY((float)EasingType.lerpWithOverride(posYPoint, easingType));
                    bone.setPosZ((float)EasingType.lerpWithOverride(posZPoint, easingType));
                    snapshot.updateOffset(bone.getPosX(), bone.getPosY(), bone.getPosZ());
                    snapshot.startPosAnim();
                    bone.markPositionAsChanged();
                }

                if (scaleXPoint != null && scaleYPoint != null && scaleZPoint != null) {
                    bone.setScaleX((float)EasingType.lerpWithOverride(scaleXPoint, easingType));
                    bone.setScaleY((float)EasingType.lerpWithOverride(scaleYPoint, easingType));
                    bone.setScaleZ((float)EasingType.lerpWithOverride(scaleZPoint, easingType));
                    snapshot.updateScale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
                    snapshot.startScaleAnim();
                    bone.markScaleAsChanged();
                }
            }
        }
        ClientCostLog.partEnd(ClientCostLog.Part.ANIM_TRACKS, tracks);
        if (FastController.timeChecks) {
            ClientCostLog.addPart(ClientCostLog.Part.ANIM_CHECKS, FastController.checkNanos - checksBefore);
            FastController.timeChecks = false;
        }

        this.reloadAnimations = false;
        double resetTickLength = animatable.getBoneResetTime();
        boolean checking = FastPose.checking();

        long rest = ClientCostLog.partStart();
        for (int i = 0; i < bones.length; i++) {
            GeoBone bone = bones[i];
            BoneSnapshot saveSnapshot = saved[i];
            BoneSnapshot initialSnapshot = bone.getInitialSnapshot();

            if (!bone.hasRotationChanged()) {
                // Changed: a channel already at rest, saved at rest and not let go just now is left be.
                if (!saveSnapshot.isRotAnimInProgress() && rotationAtRest(bone, saveSnapshot, initialSnapshot)) {
                    if (checking) checkRotation(bone, saveSnapshot, initialSnapshot, animTime, resetTickLength);
                } else {
                    easeRotation(bone, saveSnapshot, initialSnapshot, animTime, resetTickLength);
                }
            }

            if (!bone.hasPositionChanged()) {
                if (!saveSnapshot.isPosAnimInProgress() && positionAtRest(bone, saveSnapshot, initialSnapshot)) {
                    if (checking) FastPose.checkedRest(0);
                } else {
                    easePosition(bone, saveSnapshot, initialSnapshot, animTime, resetTickLength);
                }
            }

            if (!bone.hasScaleChanged()) {
                if (!saveSnapshot.isScaleAnimInProgress() && scaleAtRest(bone, saveSnapshot, initialSnapshot)) {
                    if (checking) FastPose.checkedRest(0);
                } else {
                    easeScale(bone, saveSnapshot, initialSnapshot, animTime, resetTickLength);
                }
            }
        }

        // GeckoLib's resetBoneTransformationMarkers.
        for (GeoBone bone : bones)
            bone.resetStateChanges();
        ClientCostLog.partEnd(ClientCostLog.Part.ANIM_REST, rest);
        // GeckoLib's finishFirstTick is not reachable from here; the first frame goes GeckoLib's way.
    }

    // GeckoLib's ease back to rest, one channel at a time (unchanged; split out of tickAnimation).

    private void easeRotation(GeoBone bone, BoneSnapshot saveSnapshot, BoneSnapshot initialSnapshot,
                              double animTime, double resetTickLength) {
        if (saveSnapshot.isRotAnimInProgress())
            saveSnapshot.stopRotAnim(animTime);

        double percentageReset = resetTickLength == 0 ? 1 : Math.min((animTime - saveSnapshot.getLastResetRotationTick()) / resetTickLength, 1);
        float initialRotX = initialSnapshot.getRotX();
        float initialRotY = initialSnapshot.getRotY();
        float initialRotZ = initialSnapshot.getRotZ();
        float lastXRot = saveSnapshot.getRotX();
        float lastYRot = saveSnapshot.getRotY();
        float lastZRot = saveSnapshot.getRotZ();

        // Let's capture suspected full-rotations and prevent them from back-lerping
        if (percentageReset == 0) {
            if (lastXRot != initialRotX && isSuspectedCompletedRotation(lastXRot)) {
                lastXRot = initialRotX;
                percentageReset = 1;
            }

            if (lastYRot != initialRotY && isSuspectedCompletedRotation(lastYRot)) {
                lastYRot = initialRotY;
                percentageReset = 1;
            }

            if (lastZRot != initialRotZ && isSuspectedCompletedRotation(lastZRot)) {
                lastZRot = initialRotZ;
                percentageReset = 1;
            }
        }

        bone.setRotX((float)Mth.lerp(percentageReset, lastXRot, initialRotX));
        bone.setRotY((float)Mth.lerp(percentageReset, lastYRot, initialRotY));
        bone.setRotZ((float)Mth.lerp(percentageReset, lastZRot, initialRotZ));

        if (percentageReset >= 1)
            saveSnapshot.updateRotation(bone.getRotX(), bone.getRotY(), bone.getRotZ());
    }

    private void easePosition(GeoBone bone, BoneSnapshot saveSnapshot, BoneSnapshot initialSnapshot,
                              double animTime, double resetTickLength) {
        if (saveSnapshot.isPosAnimInProgress())
            saveSnapshot.stopPosAnim(animTime);

        double percentageReset = resetTickLength == 0 ? 1 : Math.min((animTime - saveSnapshot.getLastResetPositionTick()) / resetTickLength, 1);

        bone.setPosX((float)Mth.lerp(percentageReset, saveSnapshot.getOffsetX(), initialSnapshot.getOffsetX()));
        bone.setPosY((float)Mth.lerp(percentageReset, saveSnapshot.getOffsetY(), initialSnapshot.getOffsetY()));
        bone.setPosZ((float)Mth.lerp(percentageReset, saveSnapshot.getOffsetZ(), initialSnapshot.getOffsetZ()));

        if (percentageReset >= 1)
            saveSnapshot.updateOffset(bone.getPosX(), bone.getPosY(), bone.getPosZ());
    }

    private void easeScale(GeoBone bone, BoneSnapshot saveSnapshot, BoneSnapshot initialSnapshot,
                           double animTime, double resetTickLength) {
        if (saveSnapshot.isScaleAnimInProgress())
            saveSnapshot.stopScaleAnim(animTime);

        double percentageReset = resetTickLength == 0 ? 1 : Math.min((animTime - saveSnapshot.getLastResetScaleTick()) / resetTickLength, 1);

        bone.setScaleX((float)Mth.lerp(percentageReset, saveSnapshot.getScaleX(), initialSnapshot.getScaleX()));
        bone.setScaleY((float)Mth.lerp(percentageReset, saveSnapshot.getScaleY(), initialSnapshot.getScaleY()));
        bone.setScaleZ((float)Mth.lerp(percentageReset, saveSnapshot.getScaleZ(), initialSnapshot.getScaleZ()));

        if (percentageReset >= 1)
            saveSnapshot.updateScale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
    }

    /** GeckoLib's private {@code isSuspectedCompletedRotation}, unchanged. */
    private static boolean isSuspectedCompletedRotation(float lastRotation) {
        float rotations = Mth.abs(lastRotation / (360f * Mth.DEG_TO_RAD));
        float partialRotation = 1 - (rotations - (int)rotations);

        return partialRotation == 1 || partialRotation < 0.026 * rotations;
    }

    // At rest: the ease would set the bone to lerp(t, rest, rest) = rest, and the saved state to the
    // same rest values, so nothing changes. (Only the "moved this frame" marks would, and those
    // are all cleared at the end of the step anyway.)

    private static boolean rotationAtRest(GeoBone bone, BoneSnapshot saved, BoneSnapshot rest) {
        return bone.getRotX() == rest.getRotX() && bone.getRotY() == rest.getRotY() && bone.getRotZ() == rest.getRotZ()
                && saved.getRotX() == rest.getRotX() && saved.getRotY() == rest.getRotY() && saved.getRotZ() == rest.getRotZ();
    }

    private static boolean positionAtRest(GeoBone bone, BoneSnapshot saved, BoneSnapshot rest) {
        return bone.getPosX() == rest.getOffsetX() && bone.getPosY() == rest.getOffsetY() && bone.getPosZ() == rest.getOffsetZ()
                && saved.getOffsetX() == rest.getOffsetX() && saved.getOffsetY() == rest.getOffsetY()
                && saved.getOffsetZ() == rest.getOffsetZ();
    }

    private static boolean scaleAtRest(GeoBone bone, BoneSnapshot saved, BoneSnapshot rest) {
        return bone.getScaleX() == rest.getScaleX() && bone.getScaleY() == rest.getScaleY() && bone.getScaleZ() == rest.getScaleZ()
                && saved.getScaleX() == rest.getScaleX() && saved.getScaleY() == rest.getScaleY()
                && saved.getScaleZ() == rest.getScaleZ();
    }

    /** {@code /villagerfps anim check}: what GeckoLib's ease would have set a rotation left at rest to. */
    private static void checkRotation(GeoBone bone, BoneSnapshot saved, BoneSnapshot rest,
                                      double animTime, double resetTickLength) {
        double percentageReset = resetTickLength == 0 ? 1
                : Math.min((animTime - saved.getLastResetRotationTick()) / resetTickLength, 1);
        double dx = (float) Mth.lerp(percentageReset, saved.getRotX(), rest.getRotX()) - bone.getRotX();
        double dy = (float) Mth.lerp(percentageReset, saved.getRotY(), rest.getRotY()) - bone.getRotY();
        double dz = (float) Mth.lerp(percentageReset, saved.getRotZ(), rest.getRotZ()) - bone.getRotZ();
        FastPose.checkedRest(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
    }

    /**
     * GeckoLib's {@code updateBoneSnapshots} (a saved state for every bone, copied from its rest
     * state the first time), plus the saved states in bone order, kept on the villager until the
     * bones or the villager's saved states change.
     */
    private static BoneSnapshot[] savedSnapshots(NpcEntity npc, GeoBone[] bones, Map<String, BoneSnapshot> snapshots) {
        if (npc.fastPoseCache instanceof SavedStates kept && kept.bones == bones && kept.map == snapshots
                && snapshots.size() == kept.size
                && (bones.length == 0 || snapshots.get(bones[0].getName()) == kept.states[0]))
            return kept.states;

        BoneSnapshot[] states = new BoneSnapshot[bones.length];
        for (int i = 0; i < bones.length; i++) {
            BoneSnapshot state = snapshots.get(bones[i].getName());
            if (state == null) {
                state = BoneSnapshot.copy(bones[i].getInitialSnapshot());
                snapshots.put(bones[i].getName(), state);
            }
            states[i] = state;
        }
        npc.fastPoseCache = new SavedStates(bones, snapshots, snapshots.size(), states);
        return states;
    }

    /** One villager's saved bone states in the order of {@link #bones}. */
    private record SavedStates(GeoBone[] bones, Map<String, BoneSnapshot> map, int size, BoneSnapshot[] states) {}
}
