package com.alex.test.village.villagers.mage;

import com.alex.test.animation.VillagerAnimationState;

/**
 * When each spell leaves the mage's hands, how long the mage stands for it,
 * and where its hands are at that moment, so the cast and the visible spell
 * line up the way the cleric's orb and the archer's arrow do. Pure numbers.
 *
 * <p>Every cast is one of the mage's own Mixamo clips, all bare-handed. A clip
 * is paced so its release frame lands on the tick the spell goes: the bolt at
 * {@link MageBalance#castTicks} (the clip's own pace for a novice, half again
 * as fast for a master), and every other cast sped up by the same share.
 */
public final class MageCastTiming {

    public enum Clip {
        BOLT, NOVA, CHAIN, WARD, STARFALL
    }

    /** A hand at the release frame, in blocks from the feet: up, ahead and to the mage's right. */
    public record Hand(double up, double ahead, double right) {}

    private MageCastTiming() {}

    public static VillagerAnimationState state(Clip clip) {
        return switch (clip) {
            case BOLT -> VillagerAnimationState.MAGE_BOLT;
            case NOVA -> VillagerAnimationState.MAGE_NOVA;
            case CHAIN -> VillagerAnimationState.MAGE_CHAIN;
            case WARD -> VillagerAnimationState.MAGE_WARD;
            case STARFALL -> VillagerAnimationState.MAGE_STARFALL;
        };
    }

    /** The clip's release frame in clip ticks after the blend (VillagerAnimationState). */
    private static int clipRelease(Clip clip) {
        return switch (clip) {
            case BOLT -> VillagerAnimationState.BOLT_RELEASE_TICKS;
            case NOVA -> VillagerAnimationState.NOVA_RELEASE_TICKS;
            case CHAIN -> VillagerAnimationState.CHAIN_RELEASE_TICKS;
            case WARD -> VillagerAnimationState.WARD_RELEASE_TICKS;
            case STARFALL -> VillagerAnimationState.STARFALL_RELEASE_TICKS;
        };
    }

    /** Ticks from the start of the cast to the release at the clip's own pace, its blend in included. */
    private static int naturalRelease(Clip clip) {
        return clipRelease(clip) + VillagerAnimationState.WORK_BLEND_TICKS;
    }

    /** Ticks from the start of the cast until the spell leaves the hands. */
    public static int releaseTicks(Clip clip, int level) {
        if (clip == Clip.BOLT) return MageBalance.castTicks(level);
        return Math.max(1, Math.round(naturalRelease(clip) * MageBalance.castPace(level)));
    }

    /** The clip's playback speed for a cast of {@code releaseTicks}: its release frame lands on the release. */
    public static float clipSpeed(Clip clip, int releaseTicks) {
        return naturalRelease(clip) / (float) Math.max(1, releaseTicks);
    }

    /**
     * After the release, how long the mage stands still for the clip's
     * follow-through (the arms coming down) before it may move or cast again.
     * The bolt has none beyond its recovery.
     */
    public static int followThroughTicks(Clip clip) {
        return switch (clip) {
            case BOLT -> 0;
            case NOVA -> 12;
            case CHAIN -> 10;
            case WARD -> 8;
            case STARFALL -> 16;
        };
    }

    /**
     * Where the hand the spell leaves from is at the release frame, read off
     * the shipped clips by forward kinematics (forearm ends; see
     * VillagerAnimationState). The bolt keeps its measured hand.
     */
    public static Hand castingHand(Clip clip) {
        return switch (clip) {
            case BOLT -> new Hand(1.25, 0.6, 0.45);
            case NOVA -> new Hand(0.55, 0.85, 0.0);
            case CHAIN -> new Hand(1.38, 0.89, 0.47);
            case WARD -> new Hand(1.98, 0.38, 0.50);
            case STARFALL -> new Hand(1.88, 0.27, 0.63);
        };
    }

    /** The other hand at the release, for the two-handed casts; null for the one-handed ones. */
    public static Hand otherHand(Clip clip) {
        return switch (clip) {
            case BOLT, WARD -> null;
            case NOVA -> new Hand(0.57, 0.09, -0.88);
            case CHAIN -> new Hand(1.42, 0.51, -0.69);
            case STARFALL -> new Hand(1.96, -0.12, -0.56);
        };
    }
}
