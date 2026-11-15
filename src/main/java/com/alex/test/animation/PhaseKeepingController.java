package com.alex.test.animation;

import java.util.Map;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * An AnimationController whose looping clip keeps its beat. GeckoLib 4.8 starts a loop again
 * from zero on the frame it sees the clip has ended, and starts the clip proper the same way
 * when its blend in ends, dropping the part of a frame it ran past each time. On a clip that
 * loops for a whole tree that adds up (the lumberjack's thunks, timed from the clip's start,
 * drifted ahead of the blows), and a villager off screen for a while came back at any point
 * of its loop. Here both restarts carry the overshoot on, so the clip shown is always the
 * one that started at its start time: speed x (time since start) - blend in.
 */
public class PhaseKeepingController<T extends GeoAnimatable> extends FastController<T> {
    /** process() began in the blend in: the reset that ends it carries the overshoot. */
    private boolean endingTransition;
    /** Ticks the next clip start is late by (0: none); see {@link #startLate}. */
    private double startLate;

    public PhaseKeepingController(T animatable, String name, int transitionTicks, AnimationStateHandler<T> handler) {
        super(animatable, name, transitionTicks, handler);
    }

    /**
     * Call with forceAnimationReset: the clip should have started {@code ticks} ago (it was
     * started while the mob was not drawn), so it is shown that far on, its blend in cut short
     * or skipped. Keep it under blend in + the clip's length. Only the bongo clips use it.
     */
    public void startLate(double ticks) {
        startLate = Math.max(0, ticks);
    }

    @Override
    public void process(GeoModel<T> model, AnimationState<T> state, Map<String, GeoBone> bones,
                        Map<String, BoneSnapshot> snapshots, double seekTime, boolean crashWhenCantFindBone) {
        endingTransition = animationState == State.TRANSITIONING && !shouldResetTick;
        try {
            super.process(model, state, bones, snapshots, seekTime, crashWhenCantFindBone);
        } finally {
            endingTransition = false;
        }
    }

    @Override
    protected double adjustTick(double tick) {
        // A new clip starting late: its start time is moved back, but this first call still
        // returns 0 so GeckoLib takes the clip and its blend-from pose as usual.
        if (shouldResetTick && startLate > 0 && animationState == State.TRANSITIONING) {
            tickOffset = tick - startLate;
            startLate = 0;
            shouldResetTick = false;
            return 0;
        }
        // A reset while running is the blend in ending or the loop going round; a new clip
        // resets while transitioning and starts from zero as usual.
        if (shouldResetTick && animationState == State.RUNNING && currentAnimation != null) {
            double speed = animationSpeedModifier.apply(animatable);
            double length = currentAnimation.animation().length();
            if (speed > 0 && length > 0) {
                double elapsed = speed * Math.max(tick - tickOffset, 0);
                double carry = -1;
                if (endingTransition) {
                    endingTransition = false;
                    if (elapsed >= transitionLength) carry = elapsed - transitionLength;
                } else if (elapsed >= length) {
                    carry = elapsed % length;
                }
                if (carry >= 0 && carry < length) {
                    tickOffset = tick - carry / speed;
                    shouldResetTick = false;
                    return carry;
                }
            }
        }
        return super.adjustTick(tick);
    }
}
