package com.alex.test.village.brain;

import java.util.UUID;

/**
 * How many villagers one library holds tonight, and what a visitor does there.
 *
 * <p>Who goes to the library is part of each villager's nightly plan
 * ({@link EveningPlanner#rollPlan}); this class only says when the library is
 * full and picks a visitor's action. Every rule is a pure function of ids, the
 * day and a few numbers, so it survives reloads, changes from night to night,
 * and can be unit tested without Minecraft.
 *
 * <p>Three caps keep a library quiet, whichever is lowest wins: never more
 * than {@value #MAX_VILLAGE_SHARE} of the village; no more than the room holds
 * before it feels crowded ({@link com.alex.test.village.stats.CrowdingBalance},
 * {@value com.alex.test.village.stats.CrowdingBalance#TILES_PER_VILLAGER} tiles
 * each, less anyone else inside); and no more than its usable seats and
 * shelf-side spots. A guest who finds it full goes on a neighbour visit or
 * home instead, like a full tavern.
 */
public final class LibraryPlan {

    /** Never more of the village than this at the library at once. */
    public static final double MAX_VILLAGE_SHARE = 0.20;

    private static final long SALT_ACTION = 0x11B4_A4E3L;

    /** What a visitor mostly does tonight. Readers and browsers who reach a chair hold a book. */
    public enum Action { READ, BROWSE, SIT_OBSERVE }

    private LibraryPlan() {}

    /**
     * What limits attendance: the whole village's size (defenders included),
     * how many the room holds before it feels crowded (less anyone inside
     * for another reason, like the enchanter at work), and its usable spots
     * (chairs with a shelf in view plus standing places beside a shelf).
     */
    public record Limits(int villageSize, int roomFits, int spots) {}

    /** The most visitors the village and the room allow: the library is full at this many. */
    public static int cap(Limits limits) {
        int village = villageCap(limits.villageSize());
        return Math.max(0, Math.min(Math.min(village, limits.roomFits()), limits.spots()));
    }

    /**
     * At most {@value #MAX_VILLAGE_SHARE} of the village, rounded down, but a
     * tiny village may still send one villager: with three people, one at the
     * library is not everyone.
     */
    public static int villageCap(int villageSize) {
        if (villageSize <= 0) return 0;
        return Math.max(1, (int) Math.floor(villageSize * MAX_VILLAGE_SHARE + 1e-9));
    }

    /** What this villager does at the library tonight: mostly reading, sometimes not. */
    public static Action action(UUID villager, long day) {
        int roll = EveningSchedule.sharedRoll(villager, day, SALT_ACTION, 100);
        if (roll < 70) return Action.READ;
        if (roll < 85) return Action.BROWSE;
        return Action.SIT_OBSERVE;
    }
}
