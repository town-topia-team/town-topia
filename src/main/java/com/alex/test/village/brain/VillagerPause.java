package com.alex.test.village.brain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Villagers paused right now: a player has their screen open, or a cleric is
 * tending them. Their activity is not ticked, so nothing they hold is
 * refreshed, but they are still there and still holding it.
 *
 * <p>Every registry that lets a claim, lease or hold lapse when its owner
 * goes quiet (a led animal, a door held open, a seat, a reserved tile) asks
 * here first: a paused owner's claim does not lapse. It keeps that cover for
 * {@link #GRACE_TICKS} after the pause ends, so a claim the activity only
 * refreshes every few seconds is refreshed before it is judged again.
 *
 * <p>The brain takes a villager off this list when it unloads or dies, and
 * {@link VillagerBrain} gives up a pause after {@link #MAX_PAUSE_TICKS}, so
 * nothing is held for good.
 */
public final class VillagerPause {

    /** A screen left open this long (5 minutes): the villager gives up what it was doing. */
    public static final long MAX_PAUSE_TICKS = 6000L;

    /** Cover kept after a pause ends, until the resumed activity has refreshed what it holds. */
    public static final long GRACE_TICKS = 200L;

    private static final long PAUSED = Long.MAX_VALUE;

    /** Villager, and {@link #PAUSED} or the game time its pause ended. */
    private static final Map<UUID, Long> PAUSES = new HashMap<>();

    private VillagerPause() {}

    /** {@code villager} is paused from now on. */
    public static void start(UUID villager) {
        PAUSES.put(villager, PAUSED);
    }

    /** {@code villager}'s pause ended at {@code now}; its claims keep {@link #GRACE_TICKS} of cover. */
    public static void end(UUID villager, long now) {
        if (PAUSES.containsKey(villager)) PAUSES.put(villager, now);
    }

    /** No cover at all: the villager gave up, unloaded or died. */
    public static void forget(UUID villager) {
        PAUSES.remove(villager);
    }

    /** Whether {@code villager} is paused, or resumed less than {@link #GRACE_TICKS} ago. */
    public static boolean covers(UUID villager, long now) {
        Long ended = PAUSES.get(villager);
        if (ended == null) return false;
        if (ended == PAUSED || now - ended <= GRACE_TICKS) return true;
        PAUSES.remove(villager);
        return false;
    }

    /** A claim that runs {@code until} a game time: lapsed only when that time is past and no pause covers its owner. */
    public static boolean lapsed(UUID owner, long until, long now) {
        return until <= now && !covers(owner, now);
    }

    /** A hold refreshed at {@code last}: stale only when older than {@code ttl} and no pause covers its owner. */
    public static boolean stale(UUID owner, long last, long ttl, long now) {
        return now - last > ttl && !covers(owner, now);
    }

    /** Server stopping. */
    public static void clear() {
        PAUSES.clear();
    }
}
