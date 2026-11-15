package com.alex.test.village.villagers.blacksmith;

import com.alex.test.animation.VillagerAnimationState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The hammer's clink at the anvil, timed to the blows in the blacksmith's work clips
 * (tools/forge/ForgeTool.java). The tick lists are the clips' hit keys at 20 ticks a second:
 * change both together. The clip starts once its blend in is over, so the count starts
 * {@link VillagerAnimationState#WORK_BLEND_TICKS} after the state was set.
 * Drawing out a bar (smith_anvil_draw) clinks on each client instead, on the blow it shows (client/SmithBlows).
 */
public final class SmithStrikes {

    private SmithStrikes() {}

    /** smith_anvil_steady: 0.45, 0.88, 1.31, 2.68, 3.08 s. */
    private static final int[] STEADY = {9, 18, 26, 54, 62};
    /** smith_anvil_heavy: 1.10, 1.90 s. */
    private static final int[] HEAVY = {22, 38};
    /** smith_recycle: 0.30, 0.60, 0.90, 1.20 s. */
    private static final int[] RECYCLE = {6, 12, 18, 24};
    /** smith_enchant_tap: the tool tapped on the anvil at 0.50, 0.90, 1.30 s. */
    private static final int[] TAP = {10, 18, 26};

    /**
     * The anvil clip for making something: drawing out the bar (10-24). Steady and heavy are kept
     * but no longer picked; a second way comes back with round 6 of the anvil redo plan.
     */
    public static VillagerAnimationState anvilClip(net.minecraft.util.RandomSource random) {
        return VillagerAnimationState.SMITH_ANVIL_DRAW;
    }

    /** A random clip of the two: fitting a book, pressed on or tapped in. */
    public static VillagerAnimationState enchantClip(net.minecraft.util.RandomSource random) {
        return random.nextBoolean() ? VillagerAnimationState.SMITH_ENCHANT : VillagerAnimationState.SMITH_ENCHANT_TAP;
    }

    /**
     * Plays the clink if a blow lands on this tick.
     *
     * @param ticks ticks since the clip's state was set
     */
    public static void tick(ServerLevel level, BlockPos anvil, VillagerAnimationState clip, int ticks) {
        int[] hits;
        float volume, pitch;
        switch (clip) {
            case SMITH_ANVIL -> { hits = STEADY; volume = 0.3f; pitch = 1.55f; }
            case SMITH_ANVIL_HEAVY -> { hits = HEAVY; volume = 0.45f; pitch = 1.25f; }
            case SMITH_RECYCLE -> { hits = RECYCLE; volume = 0.25f; pitch = 1.7f; }
            case SMITH_ENCHANT_TAP -> { hits = TAP; volume = 0.15f; pitch = 1.9f; }
            default -> { return; }
        }
        int t = ticks - VillagerAnimationState.WORK_BLEND_TICKS;
        if (t < 0) return;
        int inLoop = t % VillagerAnimationState.cycleTicks(clip);
        for (int hit : hits) {
            if (hit != inLoop) continue;
            level.playSound(null, anvil, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS,
                    volume, pitch + (level.random.nextFloat() - 0.5f) * 0.1f);
            return;
        }
    }
}
