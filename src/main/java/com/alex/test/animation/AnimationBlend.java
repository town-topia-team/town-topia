package com.alex.test.animation;

import java.util.EnumMap;
import java.util.Map;

/**
 * Client-side switches and lengths for the smoothing between villager clips
 * (10-05, after the cleric's throw was smoothed as a preview). Tuned live with
 * {@code /blend}; not saved, so a value that looks right is copied into the
 * defaults below.
 *
 * <p>Two kinds of smoothing, both GeckoLib's own transitions:
 * <ul>
 *   <li><b>Blending out</b> ({@link OverlayBlend}): an animation that ends
 *       eases into standing, walking or sitting over {@link #outTicks} instead
 *       of snapping back on the next frame. One switch per group of
 *       animations.</li>
 *   <li><b>Resuming</b> ({@link Group#RESUME}): the standing and walking loop,
 *       and the chair sit, come back after sitting something out (a chair, a
 *       sit, water, eating) by blending in from the pose on screen rather than
 *       jumping into the middle of their loop.</li>
 * </ul>
 * Blending in is unchanged everywhere: the work, shot and drill timings are
 * measured with those lengths.
 */
public final class AnimationBlend {

    public enum Group {
        /** Standing and walking (and the chair sit) blend back in after pausing. */
        RESUME(0),
        /** Wave, think, talk, shrug, salute, power-up, kick; standing and seated. */
        GESTURES(8),
        /** Eating, reading, crafting. */
        HANDS(8),
        /** Every work clip (farming, chopping, mining, smithing, dances, music, swings, shots) and the guard's stance. */
        WORK(6),
        /** The cleric's healing throw. */
        CAST(10);

        final int defaultOutTicks;

        Group(int defaultOutTicks) {
            this.defaultOutTicks = defaultOutTicks;
        }
    }

    private static final Map<Group, Boolean> ENABLED = new EnumMap<>(Group.class);
    private static final Map<Group, Integer> OUT_TICKS = new EnumMap<>(Group.class);

    static {
        reset();
    }

    private AnimationBlend() {}

    public static boolean enabled(Group group) {
        return ENABLED.get(group);
    }

    public static void setEnabled(Group group, boolean on) {
        ENABLED.put(group, on);
    }

    /** Ticks an animation of this group takes to ease out into standing, walking or sitting. */
    public static int outTicks(Group group) {
        return OUT_TICKS.get(group);
    }

    public static void setOutTicks(Group group, int ticks) {
        OUT_TICKS.put(group, Math.max(1, ticks));
    }

    public static void reset() {
        for (Group group : Group.values()) {
            ENABLED.put(group, true);
            OUT_TICKS.put(group, Math.max(1, group.defaultOutTicks));
        }
    }
}
