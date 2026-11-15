package com.alex.test.village.raid.warlord;

import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animation.keyframe.BoneAnimation;
import software.bernie.geckolib.animation.keyframe.Keyframe;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.loading.math.MathValue;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Warlord's one animation controller, run on the client: its loops
 * (standing, walking, charging, dazed) and the moves the server triggers
 * (swing, slam, spin, roar, pound, leap, land, death).
 *
 * <p>One controller, not loops underneath and moves on top: GeckoLib drops a
 * controller that stops with no blend at all, so every move on top ended with
 * the whole body jumping to the walk in one frame. Here a move that ends
 * blends from the pose on screen into the loop over {@link #SETTLE_TICKS},
 * and the loop starts again from its first frame.
 */
final class WarlordAnimator extends AnimationController<RaidWarlord> {

    /** Its name, for triggering clips from the server. */
    static final String NAME = "body";

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation CHARGE = RawAnimation.begin().thenLoop("charge");
    private static final RawAnimation DAZED = RawAnimation.begin().thenLoop("dazed");
    private static final String[] MOVES = {"swing", "slam", "spin", "roar", "pound", "land"};

    /** Between standing, walking, charging and dazed. */
    private static final int LOOP_BLEND_TICKS = 4;
    /** From the end of a move back into standing or walking. */
    private static final int SETTLE_TICKS = 5;

    /** Vanilla's smoothed leg swing above this counts as walking. */
    private static final float WALK_SWING_THRESHOLD = 0.03F;
    /** Ticks the walk keeps playing after it stops moving. */
    private static final int WALK_GRACE_TICKS = 3;

    /** An arm and what it holds: brought round to the idle clip's angles together. */
    private static final String[][] CHAINS = {{"right_arm", "grip", "axe"}, {"left_arm"}};
    /** An angle this far from the idle clip's own, at the end of a move, is the same stance stored another way. */
    private static final float OTHER_WAY = (float) Math.toRadians(45.0);
    private static final float TURN = (float) (2.0 * Math.PI);

    /** Client tick it last visibly moved; see {@link #walking}. */
    private int lastWalkMotionTick = Integer.MIN_VALUE / 2;

    WarlordAnimator(RaidWarlord warlord) {
        super(warlord, NAME, LOOP_BLEND_TICKS, state -> ((WarlordAnimator) state.getController()).animate(state));
        for (String move : MOVES) triggerableAnim(move, RawAnimation.begin().thenPlay(move));
        // Held in the air until the landing plays; WarlordUnstuck stops it if the leap is called off.
        triggerableAnim("leap", RawAnimation.begin().thenPlayAndHold("leap"));
        triggerableAnim("death", RawAnimation.begin().thenPlayAndHold("death"));
        setAnimationSpeedHandler(w -> w.isEnraged() && !w.isDeadOrDying() ? WarlordMath.ENRAGED_CLIP_SPEED : 1.0);
    }

    /** Every move blends in over the same short time: the server times its hits by it ({@link WarlordMath#clipTicks}). */
    @Override
    public boolean tryTriggerAnimation(String animName) {
        if (!super.tryTriggerAnimation(animName)) return false;
        transitionLength(WarlordMath.ACT_BLEND_TICKS);
        return true;
    }

    /** The loop to play; GeckoLib only asks while no move is playing. */
    private PlayState animate(AnimationState<RaidWarlord> state) {
        RaidWarlord warlord = state.getAnimatable();
        if (warlord.isDeadOrDying()) return PlayState.STOP;
        RawAnimation loop = switch (warlord.action()) {
            case CHARGE -> CHARGE;
            case DAZED -> DAZED;
            case NONE -> walking(state) ? WALK : IDLE;
        };
        RawAnimation playing = getCurrentRawAnimation();
        if (loop != playing) {
            boolean afterMove = playing != IDLE && playing != WALK && playing != CHARGE && playing != DAZED;
            if (afterMove) settleFrom(warlord);
            transitionLength(afterMove ? SETTLE_TICKS : LOOP_BLEND_TICKS);
            setAnimation(loop);
        }
        return PlayState.CONTINUE;
    }

    /**
     * Whether the walk should play. GeckoLib's own {@code isMoving()} reads the
     * speed the server last sent, which drops to nothing for a moment whenever
     * it bumps a block, steps up or picks a new path: the walk then flipped to
     * standing and, coming back, started over from its first frame. As the
     * villagers do ({@code NpcEntity.isWalkingForAnimation}), this also takes
     * the leg swing and how far it moved this tick, both as the client sees
     * them, and keeps walking {@value #WALK_GRACE_TICKS} ticks after the last
     * movement.
     */
    private boolean walking(AnimationState<RaidWarlord> state) {
        RaidWarlord warlord = state.getAnimatable();
        double dx = warlord.getX() - warlord.xo;
        double dz = warlord.getZ() - warlord.zo;
        if (state.isMoving() || warlord.walkAnimation.speed() > WALK_SWING_THRESHOLD || dx * dx + dz * dz > 1.0E-4) {
            lastWalkMotionTick = warlord.tickCount;
        }
        return warlord.tickCount - lastWalkMotionTick <= WALK_GRACE_TICKS;
    }

    /**
     * Readies the pose on screen, where the blend out of a move starts from.
     * GeckoLib blends each angle on its own, in a straight line, so it matters
     * how an angle is stored, not only how it looks:
     * <ul>
     * <li>The spin ends a whole turn round (360 degrees); left like that, the
     *     blend would turn it all the way back. Any angle a whole turn off is
     *     taken back by that turn, which looks the same.</li>
     * <li>Every move ends standing ready, as the idle clip starts, but some
     *     clips store an arm and the axe in it another way (the arm turned
     *     round, the axe turned back). Blending from those angles would whirl
     *     the axe round, so such an arm, with what it holds, starts the blend
     *     from the idle clip's own angles, which look the same.</li>
     * </ul>
     */
    private void settleFrom(RaidWarlord warlord) {
        Animation idle = lastModel == null ? null : lastModel.getAnimation(warlord, "idle");
        var manager = warlord.getAnimatableInstanceCache().getManagerForId(warlord.getId());
        if (idle == null || manager == null) return;
        Map<String, BoneSnapshot> snapshots = manager.getBoneSnapshotCollection();
        Map<String, float[]> ready = new HashMap<>();
        for (BoneAnimation bone : idle.boneAnimations()) {
            var rotation = bone.rotationKeyFrames();
            if (rotation.xKeyframes().isEmpty()) continue;
            ready.put(bone.boneName(), new float[] {
                    first(rotation.xKeyframes()), first(rotation.yKeyframes()), first(rotation.zKeyframes())});
        }
        Map<String, float[]> offsets = new HashMap<>();
        for (Map.Entry<String, float[]> entry : ready.entrySet()) {
            float[] offset = offsetFrom(snapshots.get(entry.getKey()), entry.getValue());
            if (offset != null) offsets.put(entry.getKey(), offset);
        }
        for (String[] chain : CHAINS) {
            boolean otherWay = false;
            for (String bone : chain) {
                float[] offset = offsets.get(bone);
                if (offset == null) continue;
                for (float angle : offset) otherWay |= Math.abs(angle) > OTHER_WAY;
            }
            if (otherWay) {
                for (String bone : chain) {
                    if (offsets.containsKey(bone)) offsets.put(bone, new float[3]);
                }
            }
        }
        for (Map.Entry<String, float[]> entry : offsets.entrySet()) {
            BoneSnapshot snapshot = snapshots.get(entry.getKey());
            BoneSnapshot initial = snapshot.getBone().getInitialSnapshot();
            float[] to = ready.get(entry.getKey());
            float[] offset = entry.getValue();
            snapshot.updateRotation(initial.getRotX() + to[0] + offset[0],
                    initial.getRotY() + to[1] + offset[1], initial.getRotZ() + to[2] + offset[2]);
        }
    }

    /** How far the bone's angles on screen are from {@code ready}, each taken to within half a turn. */
    @Nullable
    private static float[] offsetFrom(@Nullable BoneSnapshot snapshot, float[] ready) {
        if (snapshot == null) return null;
        GeoBone bone = snapshot.getBone();
        BoneSnapshot initial = bone.getInitialSnapshot();
        float[] now = {snapshot.getRotX() - initial.getRotX(), snapshot.getRotY() - initial.getRotY(),
                snapshot.getRotZ() - initial.getRotZ()};
        float[] offset = new float[3];
        for (int i = 0; i < 3; i++) {
            float d = now[i] - ready[i];
            offset[i] = d - TURN * Math.round(d / TURN);
        }
        return offset;
    }

    /** A rotation channel's first value, in the radians GeckoLib bakes it to. */
    private static float first(List<Keyframe<MathValue>> frames) {
        return (float) frames.get(0).startValue().get();
    }
}
