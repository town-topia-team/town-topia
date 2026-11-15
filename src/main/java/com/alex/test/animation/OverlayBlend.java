package com.alex.test.animation;

import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Blending out for one villager controller that plays over the standing and
 * walking loop (work, the throw, eating, reading, gestures, crafting).
 *
 * <p>GeckoLib blends a clip in, but a controller that returns STOP simply drops
 * out, and the pose underneath shows on the next frame: "standing, next frame
 * waving", the other way round. So when this controller's clip ends it does
 * not stop at once: for {@link AnimationBlend#outTicks} it plays the clip the
 * pose underneath would show (standing, walking, running, sitting on a chair),
 * which GeckoLib blends to from the pose on screen, and only then stops.
 *
 * <p>That blend starts from the pose the controllers before it wrote this
 * frame (AnimationProcessor updates the bone snapshots controller by
 * controller), not from the pose on screen. So the base controllers ask
 * {@link #ownsBase} first and sit the blend out: on the frame the clip ends
 * and for the whole blend. When they come back they reload their clip, so
 * they blend in from where this one left the pose rather than jumping into
 * the middle of their loop.
 *
 * <p>One per controller per villager, client side.
 */
public final class OverlayBlend {

    private enum Phase { NONE, PLAYING, OUT }

    private final AnimationBlend.Group group;
    private Phase phase = Phase.NONE;
    private int outUntil;

    public OverlayBlend(AnimationBlend.Group group) {
        this.group = group;
    }

    /**
     * Asked by a base controller, which runs earlier in the frame: whether it
     * should sit this frame out. True on the frame this overlay's clip ends
     * ({@code wantedNow} false while still marked playing) and through the
     * blend out.
     */
    public boolean ownsBase(int tick, boolean wantedNow) {
        if (!AnimationBlend.enabled(group)) return false;
        return switch (phase) {
            case PLAYING -> !wantedNow;
            case OUT -> tick < outUntil;
            case NONE -> false;
        };
    }

    /** Whether this overlay is easing out right now (its controller then plays at base speed). */
    public boolean blendingOut() {
        return phase == Phase.OUT;
    }

    /**
     * The overlay controller's handler.
     *
     * @param clip        the clip it wants now, or null when its animation is over
     * @param inTicks     its own blend in, unchanged
     * @param canBlendOut false where easing out makes no sense (asleep in bed, in water)
     * @param base        the clip the pose underneath would show this frame
     */
    public <T extends software.bernie.geckolib.animatable.GeoAnimatable> PlayState handle(
            AnimationController<T> controller, @Nullable RawAnimation clip, int inTicks, int tick,
            boolean canBlendOut, Supplier<RawAnimation> base) {
        if (clip != null) {
            phase = Phase.PLAYING;
            controller.transitionLength(inTicks);
            controller.setAnimation(clip);
            return PlayState.CONTINUE;
        }
        if (phase == Phase.PLAYING) {
            if (canBlendOut && AnimationBlend.enabled(group)) {
                phase = Phase.OUT;
                outUntil = tick + AnimationBlend.outTicks(group);
            } else {
                phase = Phase.NONE;
            }
        }
        if (phase == Phase.OUT && tick < outUntil && canBlendOut && AnimationBlend.enabled(group)) {
            controller.transitionLength(AnimationBlend.outTicks(group));
            controller.setAnimation(base.get());
            return PlayState.CONTINUE;
        }
        phase = Phase.NONE;
        controller.transitionLength(inTicks);
        return PlayState.STOP;
    }
}
