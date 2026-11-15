package com.alex.test.village.economy;

import com.alex.test.village.VillageManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One village's catch-up after nobody was near, stepped a few milliseconds a
 * tick by {@code VillageDormancy}: the away calculator ({@code economy/away/AwayRun}),
 * or an old calculator's run found in a save, closed ({@code away/OldCatchUpClosed}).
 * Holds what the end of a catch-up needs: the welcome-back lines
 * ({@link AwayReport}), the skip of the days not counted
 * ({@code away/AwaySkip.skipUncounted}) and the registry's summary.
 */
public interface CatchUp {

    /**
     * Runs steps until {@code deadlineNanos}, always at least one.
     *
     * @return true once the catch-up is finished
     */
    boolean step(ServerLevel level, VillageManager mgr, long deadlineNanos);

    /** Saved with the village while it runs. */
    CompoundTag save();

    /** Ticks nobody was near, the server's empty time included. */
    long awayTicks();

    /** Ticks the village lives through ({@link AwayClock#countedTicks}). */
    long countedTicks();

    /** Village time away the village does not live through: children do not age in it. */
    long uncountedTicks();

    /**
     * Members with no body loaded at the skip: the registry owes them the
     * uncounted time for when they load. Filled and read in the same tick.
     */
    Set<UUID> notLoaded();

    /**
     * For the welcome-back lines: what the village has more of than when
     * everyone left, item by item (made less used and eaten; wheat baked into
     * bread shows as bread). Only rises.
     */
    Map<Item, Integer> made();

    /** For the welcome-back lines: villagers alive and at hunger 0 at the end. */
    int starvingNow();

    /**
     * For the welcome-back lines: some part broke on an error of ours (a job,
     * a day's meals, a write-back part), so some work away was not counted.
     */
    default boolean somethingBroke() { return false; }

    /** True the first time only: the uncounted days are skipped once per catch-up. */
    boolean claimUncountedSkip();

    /** What the catch-up did, as the registry keeps it and StateDump shows it. */
    ReconciliationSummary summary();
}
