package com.alex.test.animation;

import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationController;

/**
 * An {@link AnimationController} whose first looped clip after the entity loads in
 * starts part-way through, at a phase taken from the entity's UUID.
 *
 * <p>Every villager in a chunk is created on the client in the same tick, so every
 * idle (or walk, or sleep) loop used to start at frame zero together and the whole
 * village breathed in lockstep until something split them up. Offsetting only the
 * very first start is enough: after that each villager's loops restart on their own
 * schedule.
 *
 * <p>That first start also skips the blend-in. GeckoLib blends towards a clip's
 * frame zero, so blending and then jumping to the phase would pop; and a villager
 * that has only just appeared has no previous pose worth blending from. Once the
 * first clip is running -- or {@link #ARM_TICKS} have passed without one -- the
 * normal transition length is restored.
 *
 * <p>It also lets the playback speed change smoothly. GeckoLib reads a clip's
 * time as speed x (time since the clip started), so a new speed moves the
 * playhead to wherever that product lands: half a loop away, for a speed that
 * changes by a tenth ten seconds in. The run's speed follows the villager's pace
 * every tick (RunPace), so this moves the start instead, and the clip carries on
 * from where it is.
 */
public class PhasedLoopController<T extends Entity & GeoAnimatable> extends FastController<T> {

    /** Only a clip that starts this soon after the entity appears counts as "loading in". */
    private static final int ARM_TICKS = 20;

    private final double normalTransition;
    private final double phaseFraction;
    private boolean armed = true;
    /** The speed the clock was last read at; NaN until the first read. */
    private double lastSpeed = Double.NaN;

    public PhasedLoopController(T animatable, String name, int transitionTicks, AnimationStateHandler<T> handler) {
        super(animatable, name, 0, handler);
        this.normalTransition = transitionTicks;
        // Stable per villager and identical on every client watching it.
        this.phaseFraction = Math.floorMod(animatable.getUUID().hashCode(), 1000) / 1000d;
    }

    @Override
    protected double adjustTick(double tick) {
        if (!armed)
            return continuousTick(tick);

        if (this.animatable.tickCount >= ARM_TICKS) {
            disarm();
            return continuousTick(tick);
        }

        // The reset that begins the first clip's first loop, right after its (zero-length) transition.
        if (!this.shouldResetTick || getAnimationState() != State.RUNNING || this.currentAnimation == null)
            return continuousTick(tick);

        disarm();
        double length = this.currentAnimation.animation().length();
        double speed = this.animationSpeedModifier.apply(this.animatable);
        if (this.currentAnimation.loopType() != Animation.LoopType.LOOP || length <= 0 || speed <= 0)
            return continuousTick(tick);

        double phase = length * this.phaseFraction;
        this.shouldResetTick = false;
        this.tickOffset = tick - phase / speed;
        this.lastSpeed = speed;
        this.lastClipTicks = phase;
        return phase;
    }

    /** GeckoLib's clock, with the start moved when the speed changes so the playhead does not jump. */
    private double continuousTick(double tick) {
        double speed = this.animationSpeedModifier.apply(this.animatable);
        if (!this.shouldResetTick && speed > 0 && this.lastSpeed > 0 && speed != this.lastSpeed) {
            double elapsed = Math.max(tick - this.tickOffset, 0);
            this.tickOffset = tick - elapsed * this.lastSpeed / speed;
        }
        this.lastSpeed = speed;
        this.lastClipTicks = super.adjustTick(tick);
        return this.lastClipTicks;
    }

    /** The clip time this controller last read, in ticks. */
    private double lastClipTicks;

    /**
     * Where in its loop the clip {@code clip} is, 0 to 1, as of the last frame
     * drawn; NaN when that clip is not the one playing, or it is still blending in.
     */
    public double loopPhase(String clip) {
        double length = loopTicks(clip);
        if (Double.isNaN(length)) return Double.NaN;
        double t = this.lastClipTicks % length;
        return (t < 0 ? t + length : t) / length;
    }

    /** The playing clip's length in ticks if it is {@code clip} and running; NaN otherwise. */
    public double loopTicks(String clip) {
        if (getAnimationState() != State.RUNNING || this.currentAnimation == null) return Double.NaN;
        Animation animation = this.currentAnimation.animation();
        if (!animation.name().equals(clip) || animation.length() <= 0) return Double.NaN;
        return animation.length();
    }

    private void disarm() {
        this.armed = false;
        this.transitionLength = this.normalTransition;
    }
}
