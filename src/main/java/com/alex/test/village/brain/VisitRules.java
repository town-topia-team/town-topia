package com.alex.test.village.brain;

import java.util.List;

/**
 * The house-visit rules that are pure numbers: whether a host has time for a
 * guest, which of the nearby houses a guest picks, and how tonight's visit
 * begins and ends. Minecraft-free so it can be unit tested.
 */
public final class VisitRules {

    private VisitRules() {}

    /** Walking estimate, the same one the bard uses: winding routes plus a margin. */
    public static final int TICKS_PER_BLOCK = 7;
    public static final int WALK_MARGIN_TICKS = 100;
    /** Spare time a host must have beyond the walk and the visit. */
    public static final int HOST_MARGIN_TICKS = 200;
    /** A host must have at least this long left when the guest reaches the house. */
    public static final int HOST_MIN_ON_ARRIVAL = 300;
    /**
     * A visit chat's longest length: 18 icons at 40 ticks, a few short
     * pauses between turns and the tail (see VisitTalk).
     */
    public static final int VISIT_CHAT_TICKS = 830;
    /** A guest stays open to a chat until this little of its visit is left. */
    public static final int GUEST_LAST_CHAT_TICKS = 100;

    /** How a guest is welcomed in; rolled per visit. */
    public enum Welcome {
        /** Knock, wait for the host to come and open the door, wave, go in. */
        KNOCK,
        /** The host saw the guest coming and waits at the open door. */
        AT_DOOR,
        /** The guest walks in; the host waves from where it is. */
        WAVE_FROM_INSIDE,
        /** Close neighbours: the guest just walks in. */
        WALK_IN
    }

    public static long walkTicks(double blocks) {
        return WALK_MARGIN_TICKS + Math.round(TICKS_PER_BLOCK * Math.max(0.0, blocks));
    }

    /**
     * Whether a host with {@code hostTicksLeft} before its own bedtime can
     * take a guest {@code blocks} away: the walk, then the visit or a visit
     * chat (whichever is longer), plus a margin.
     */
    public static boolean hostHasTime(long hostTicksLeft, double blocks, int visitLength) {
        return hostTicksLeft >= walkTicks(blocks) + Math.max(visitLength, VISIT_CHAT_TICKS) + HOST_MARGIN_TICKS;
    }

    /**
     * Which of the reachable candidate houses to visit: among those with the
     * fewest guests, one picked by {@code roll} (any non-negative number), so
     * guests spread over the houses rather than queueing at the nearest.
     * Returns the index into {@code guests}, or -1 when empty.
     */
    public static int pickHost(List<Integer> guests, int roll) {
        if (guests.isEmpty()) return -1;
        int fewest = Integer.MAX_VALUE;
        for (int g : guests) fewest = Math.min(fewest, g);
        int count = 0;
        for (int g : guests) if (g == fewest) count++;
        int pick = Math.floorMod(roll, count);
        for (int i = 0; i < guests.size(); i++) {
            if (guests.get(i) != fewest) continue;
            if (pick-- == 0) return i;
        }
        return -1;
    }

    /** Welcome for a visit from a 0-99 roll: knock 35, at the door 25, wave from inside 25, walk in 15. */
    public static Welcome welcome(int roll100) {
        if (roll100 < 35) return Welcome.KNOCK;
        if (roll100 < 60) return Welcome.AT_DOOR;
        if (roll100 < 85) return Welcome.WAVE_FROM_INSIDE;
        return Welcome.WALK_IN;
    }

    /** The host sees the guest out on about a third of visits that end on time. */
    public static boolean seeOut(int roll100) {
        return roll100 < 30;
    }

    /** A snack is shared on about a quarter of visits, and only when the host has food. */
    public static boolean snack(int roll100, boolean hostHasFood) {
        return hostHasFood && roll100 < 25;
    }
}
