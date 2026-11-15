package com.alex.test.village;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Work tiles the village has learned it cannot get to, and when to try again.
 *
 * <p>Professions already give up on a tile they failed to walk to — but they do
 * it on the activity instance, and a work activity is rebuilt from scratch every
 * time anything preempts work: sleep, a meal, a conversation. The give-up is
 * therefore forgotten several times a day, and a farmer whose potato row runs
 * down to an ocean shore spends the first half-minute of every work session
 * walking back to the same unreachable tile and standing there until the stuck
 * timer fires. Twice a day, forever.
 *
 * <p>Living on the village makes the lesson last as long as it is true, and
 * shares it: one farmer's wasted trip saves the next one's. Entries expire, so
 * a tile that becomes reachable when the player bridges the gap is tried again
 * rather than written off for the life of the world.
 *
 * <p>Modelled on the lumberjack's tree blacklist, which solved the same problem
 * for trees before farming existed.
 */
public final class UnreachableWorkTiles {

    /** How long a tile stays written off. Roughly a quarter of a day. */
    private static final long RETRY_DELAY_TICKS = 6_000L;

    /** Entries kept before the oldest are dropped, so a bad shoreline cannot grow without bound. */
    private static final int MAX_ENTRIES = 512;

    private final Map<Long, Long> retryAfter = new HashMap<>();

    /** Notes that nobody could reach {@code tile}, and stops it being offered for a while. */
    public void markUnreachable(BlockPos tile, long gameTime) {
        if (retryAfter.size() >= MAX_ENTRIES) prune(gameTime);
        if (retryAfter.size() >= MAX_ENTRIES) return;
        retryAfter.put(tile.asLong(), gameTime + RETRY_DELAY_TICKS);
    }

    /** True while {@code tile} is still written off. */
    public boolean isUnreachable(BlockPos tile, long gameTime) {
        Long until = retryAfter.get(tile.asLong());
        if (until == null) return false;
        if (gameTime >= until) {
            retryAfter.remove(tile.asLong());
            return false;
        }
        return true;
    }

    /** Forgets a tile somebody has just worked successfully. */
    public void clear(BlockPos tile) {
        retryAfter.remove(tile.asLong());
    }

    public int size() {
        return retryAfter.size();
    }

    public void clearAll() {
        retryAfter.clear();
    }

    private void prune(long gameTime) {
        retryAfter.entrySet().removeIf(entry -> gameTime >= entry.getValue());
    }
}
