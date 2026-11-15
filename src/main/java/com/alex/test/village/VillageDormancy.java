package com.alex.test.village;

import com.alex.test.testkit.TestKit;
import com.alex.test.village.claims.VillageClaims;
import com.alex.test.village.economy.away.AwaySkip;
import com.alex.test.village.economy.AwayClock;
import com.alex.test.village.economy.AwayReport;
import com.alex.test.village.economy.CatchUp;
import com.alex.test.village.economy.ReconciliationSummary;
import com.alex.test.village.economy.away.AwayRun;
import com.alex.test.village.economy.away.OldCatchUpClosed;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The ACTIVE / DORMANT / RECONCILING lifecycle of every registered village.
 *
 * <p>A village goes dormant when no player has been within its bounds for
 * {@link #DORMANCY_GRACE_MISSES} consecutive checks. The moment a player
 * returns it snaps into the transitional RECONCILING state (no grace needed on
 * that direction): it waits (bounded by {@link #RECONCILE_WAIT_TIMEOUT_TICKS})
 * for its chunks and villager puppets to actually be loaded, then runs an
 * {@link AwayRun} over the time it lives through ({@link AwayClock}: at
 * most three days, the server's empty time -- for an owned village the time
 * its owner and every Officer were offline -- and the time its fields or pens
 * grew live with a player outside the border left out), a few milliseconds a
 * tick, and only then transitions to ACTIVE -- see {@link #processDormancyChecks}.
 *
 * <p>Owned by {@link VillageManagerRegistry}, which persists the state, exposes
 * the public queries, and skips dormant or reconciling villages when ticking.
 * Dormancy is kept apart from the registry's inactive (Town-Hall-missing)
 * lifecycle; a village can be dormant with a perfectly valid Town Hall.
 */
final class VillageDormancy {

    /** Shares the registry's logger so log categories stay unchanged. */
    private static final Logger LOGGER = VillageManagerRegistry.LOGGER;

    /** Ticks between per-village player-proximity checks (10s); staggered per village so they don't all land on one tick. */
    private static final int DORMANCY_CHECK_INTERVAL = 200;
    /** Consecutive misses (no player nearby) required before ACTIVE -> DORMANT. */
    private static final int DORMANCY_GRACE_MISSES = 2;
    /**
     * Bounded wait (20s) for {@link VillageManager#isReadyForReconciliation}
     * to go true before reconciling anyway with whatever chunks/puppets are
     * actually loaded, so one stubborn chunk can never strand a village in
     * RECONCILING forever.
     */
    private static final int RECONCILE_WAIT_TIMEOUT_TICKS = 400;
    /** Time a catch-up may take each tick; it always does at least one step. */
    private static final long CATCH_UP_NANOS_PER_TICK = 3_000_000L;
    /** Empty-server ticks between saves of the counter (one minute). */
    private static final int EMPTY_COUNT_SAVE_INTERVAL = 1_200;

    /** The registry's {@code setDirty}; every persisted state change calls it. */
    private final Runnable markDirty;
    /** The registry's claims switch: with claims off no village rests for its owner. */
    private final BooleanSupplier claimsEnabled;

    /**
     * Ticks the server has run with nobody online, ever (saved by the
     * registry). A village's share of it is the rise since it went dormant.
     */
    private long emptyServerTicks;
    /** {@link #emptyServerTicks} when each village went dormant. */
    private final Map<UUID, Long> dormantSinceEmpty = new HashMap<>();
    /** The village calendar ({@link VillageDays#villageTime}) when each village went dormant, for children. */
    private final Map<UUID, Long> dormantSinceVillageTime = new HashMap<>();
    /**
     * Catch-ups under way, one per reconciling village; saved part done: the away
     * calculator ({@link AwayRun}), or an old calculator's run found in a save, closed
     * as done ({@link OldCatchUpClosed}, round 12).
     */
    private final Map<UUID, CatchUp> catchUps = new HashMap<>();
    /**
     * Uncounted away ticks owed to members whose body was not loaded when a
     * catch-up ended, per village: taken when the body next loads
     * ({@link #takeOwedSkip}; a child does not age through them, a cook's
     * appetite does not fade over them). Saved.
     */
    private final Map<UUID, Map<UUID, Long>> owedSkips = new HashMap<>();
    /**
     * Ticks each sleeping village's fields or pens grew live (a player just
     * outside the border, {@link OutsideWatch}), in steps of one check; left
     * out of its catch-up like the server's empty time. Saved.
     */
    private final Map<UUID, Long> watchedTicks = new HashMap<>();
    /**
     * Ticks each sleeping owned village (claims on) spent with its owner and every Officer off the
     * server, in steps of one check (owner offline, 10-25): left out of its catch-up in place of the
     * server's empty time, which is part of it. Saved.
     */
    private final Map<UUID, Long> ownerAwayTicks = new HashMap<>();
    /** Each sleeping village's field and pen chunks, noted at its first check asleep; not saved (asleep, they do not change). */
    private final Map<UUID, OutsideWatch.Spots> watchSpots = new HashMap<>();

    /**
     * Villages with nobody nearby to observe them. Parallel to, and never
     * merged with, the registry's inactive set — a village can be dormant with a
     * perfectly valid Town Hall, or (in principle) inactive without being
     * dormant.
     */
    private final Set<UUID> dormantVillages = new HashSet<>();
    private final Map<UUID, Long> dormantSinceGameTime = new HashMap<>();
    /**
     * Transitional state entered the instant a player returns to a dormant
     * village. Gates the same tick-driven subsystems as dormant does, through
     * the wait for chunks and the catch-up; see {@link #processDormancyChecks}.
     */
    private final Set<UUID> reconcilingVillages = new HashSet<>();
    /** Transient detection bookkeeping — not persisted; a reload costs at most one extra check cycle. */
    private final Map<UUID, Integer> dormancyMissStreak = new HashMap<>();
    /** Ticks spent so far waiting for a reconciling village's chunks/puppets to be ready; see {@link #RECONCILE_WAIT_TIMEOUT_TICKS}. */
    private final Map<UUID, Integer> reconcilingTicksRemaining = new HashMap<>();

    VillageDormancy(Runnable markDirty, BooleanSupplier claimsEnabled) {
        this.markDirty = markDirty;
        this.claimsEnabled = claimsEnabled;
    }

    // =========================================================================
    // Queries
    // =========================================================================

    boolean isDormant(UUID villageId) {
        return dormantVillages.contains(villageId);
    }

    boolean isReconciling(UUID villageId) {
        return reconcilingVillages.contains(villageId);
    }

    /** True while either dormant or reconciling — the combined gate every tick-driven subsystem should honor. */
    boolean isDormantOrReconciling(UUID villageId) {
        return dormantVillages.contains(villageId) || reconcilingVillages.contains(villageId);
    }

    /** -1 when the village has never gone dormant (or the timestamp was cleared after reconciling). */
    long getDormantSinceGameTime(UUID villageId) {
        return dormantSinceGameTime.getOrDefault(villageId, -1L);
    }

    /** @see VillageManagerRegistry#hasDormantSince */
    boolean hasDormantSince(UUID villageId) {
        return dormantSinceGameTime.containsKey(villageId);
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    private static final String TAG_SINCE_EMPTY = "sinceEmpty";
    private static final String TAG_SINCE_VILLAGE_TIME = "sinceVillageTime";
    /** The old calculator's run (deleted in round 12): closed as done when found ({@link OldCatchUpClosed}). */
    private static final String TAG_CATCH_UP = "catchUp";
    /** An away calculator run ({@link AwayRun}): only its start numbers, it begins again at READ. */
    private static final String TAG_AWAY_RUN = "awayRun";
    private static final String TAG_OWED_SKIPS = "owedSkips";
    private static final String TAG_WATCHED = "watched";
    private static final String TAG_OWNER_AWAY = "ownerAway";

    /** Dormancy is independent of the Town-Hall-frame lifecycle, so it is restored for any persisted village. */
    void restore(UUID villageId, boolean dormant, boolean reconciling, long dormantSince, CompoundTag extra) {
        if (dormant) dormantVillages.add(villageId);
        if (reconciling) reconcilingVillages.add(villageId);
        if (dormantSince >= 0L) {
            dormantSinceGameTime.put(villageId, dormantSince);
        }
        // Older saves have none: their whole time away counts (still at most the cap).
        if (extra.contains(TAG_SINCE_EMPTY)) dormantSinceEmpty.put(villageId, extra.getLong(TAG_SINCE_EMPTY));
        if (extra.contains(TAG_SINCE_VILLAGE_TIME)) {
            dormantSinceVillageTime.put(villageId, extra.getLong(TAG_SINCE_VILLAGE_TIME));
        }
        if (extra.getLong(TAG_WATCHED) > 0L) watchedTicks.put(villageId, extra.getLong(TAG_WATCHED));
        if (extra.getLong(TAG_OWNER_AWAY) > 0L) ownerAwayTicks.put(villageId, extra.getLong(TAG_OWNER_AWAY));
        if (reconciling && extra.contains(TAG_AWAY_RUN)) {
            catchUps.put(villageId, AwayRun.load(villageId, extra.getCompound(TAG_AWAY_RUN)));
        } else if (reconciling && extra.contains(TAG_CATCH_UP)) {
            // It paid as it went: closed, never run again. One that had not begun: none, the new one runs.
            CatchUp old = OldCatchUpClosed.load(villageId, extra.getCompound(TAG_CATCH_UP));
            if (old != null) catchUps.put(villageId, old);
        }
        CompoundTag skips = extra.getCompound(TAG_OWED_SKIPS);
        for (String key : skips.getAllKeys()) {
            try {
                owedSkips.computeIfAbsent(villageId, id -> new HashMap<>())
                        .put(UUID.fromString(key), skips.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // A damaged entry: that child ages through the time, as before.
            }
        }
    }

    /** What {@link #restore} reads back, beside the dormant flags and dormantSince. */
    CompoundTag saveExtra(UUID villageId) {
        CompoundTag tag = new CompoundTag();
        Long sinceEmpty = dormantSinceEmpty.get(villageId);
        if (sinceEmpty != null) tag.putLong(TAG_SINCE_EMPTY, sinceEmpty);
        Long sinceVillageTime = dormantSinceVillageTime.get(villageId);
        if (sinceVillageTime != null) tag.putLong(TAG_SINCE_VILLAGE_TIME, sinceVillageTime);
        Long watched = watchedTicks.get(villageId);
        if (watched != null) tag.putLong(TAG_WATCHED, watched);
        Long ownerAway = ownerAwayTicks.get(villageId);
        if (ownerAway != null) tag.putLong(TAG_OWNER_AWAY, ownerAway);
        CatchUp away = catchUps.get(villageId);
        if (away != null) tag.put(away instanceof AwayRun ? TAG_AWAY_RUN : TAG_CATCH_UP, away.save());
        Map<UUID, Long> skips = owedSkips.get(villageId);
        if (skips != null && !skips.isEmpty()) {
            CompoundTag skipsTag = new CompoundTag();
            skips.forEach((id, ticks) -> skipsTag.putLong(id.toString(), ticks));
            tag.put(TAG_OWED_SKIPS, skipsTag);
        }
        return tag;
    }

    /**
     * After a catch-up: each member whose body was not loaded is owed the
     * uncounted time, added to anything still owed from before. Entries of
     * ids that left the village are dropped.
     */
    private void oweSkips(UUID villageId, VillageManager mgr, CatchUp away) {
        Map<UUID, Long> skips = owedSkips.computeIfAbsent(villageId, id -> new HashMap<>());
        skips.keySet().retainAll(mgr.getVillagerIds());
        long uncounted = away.uncountedTicks();
        if (uncounted > 0L) {
            for (UUID id : away.notLoaded()) skips.merge(id, uncounted, Long::sum);
        }
        if (skips.isEmpty()) owedSkips.remove(villageId);
    }

    /** Away ticks this member is owed (see {@link #owedSkips}), cleared; 0 when none. */
    long takeOwedSkip(UUID villageId, UUID npcId) {
        Map<UUID, Long> skips = owedSkips.get(villageId);
        Long ticks = skips == null ? null : skips.remove(npcId);
        if (ticks == null) return 0L;
        if (skips.isEmpty()) owedSkips.remove(villageId);
        markDirty.run();
        return ticks;
    }

    long emptyServerTicks() {
        return emptyServerTicks;
    }

    void setEmptyServerTicks(long ticks) {
        emptyServerTicks = Math.max(0L, ticks);
    }

    /**
     * Counts one overworld tick with nobody on the server. A dedicated
     * server keeps the world running empty; that time is left out of every
     * village's time away. Not on a headless test server, which never has
     * players.
     */
    void countEmptyServerTick(ServerLevel sl) {
        if (sl.dimension() != Level.OVERWORLD || TestKit.headless()) return;
        if (sl.getServer().getPlayerList().getPlayerCount() > 0) return;
        emptyServerTicks++;
        if (emptyServerTicks % EMPTY_COUNT_SAVE_INTERVAL == 0) markDirty.run();
    }

    /** ACTIVE -> DORMANT: the three clocks the catch-up later measures from, and fresh watched and owner-away clocks. */
    private void goDormant(ServerLevel sl, UUID villageId) {
        dormantVillages.add(villageId);
        dormantSinceGameTime.put(villageId, sl.getGameTime());
        dormantSinceEmpty.put(villageId, emptyServerTicks);
        dormantSinceVillageTime.put(villageId, VillageDays.villageTime(sl.getDayTime()));
        watchedTicks.remove(villageId);
        watchSpots.remove(villageId);
        ownerAwayTicks.remove(villageId);
    }

    // =========================================================================
    // Test hooks
    // =========================================================================

    /** @see VillageManagerRegistry#debugSetDormant */
    void debugSetDormant(ServerLevel level, UUID villageId, boolean dormant) {
        if (dormant) {
            reconcilingVillages.remove(villageId);
            reconcilingTicksRemaining.remove(villageId);
            catchUps.remove(villageId);
            dormancyMissStreak.remove(villageId);
            goDormant(level, villageId);
        } else if (dormantVillages.remove(villageId)) {
            reconcilingVillages.add(villageId);
            reconcilingTicksRemaining.put(villageId, 0);
        }
        markDirty.run();
    }

    /** @see VillageManagerRegistry#debugElapseDormancy */
    boolean debugElapseDormancy(UUID villageId, long ticks) {
        Long since = dormantSinceGameTime.get(villageId);
        if (since == null) return false;
        dormantSinceGameTime.put(villageId, since - ticks);
        dormantSinceVillageTime.computeIfPresent(villageId, (id, time) -> time - ticks);
        markDirty.run();
        return true;
    }

    // =========================================================================
    // Detection
    // =========================================================================

    /**
     * Detects ACTIVE <-> DORMANT <-> RECONCILING transitions for every
     * registered village. Cheap by design: the (comparatively expensive)
     * player-proximity scan for a given village only runs once every
     * {@link #DORMANCY_CHECK_INTERVAL} ticks, staggered per village via a
     * hashCode-derived offset (same idea as {@code GuardPostPatrol.asyncDelay}
     * spreading repath timing across guards) so villages don't all re-check on
     * the same tick.
     *
     * <p>RECONCILING is the exception: since it is meant to be short-lived and
     * the set of reconciling villages is normally empty or tiny, its wait and
     * its catch-up run every tick rather than on the stagger cycle.
     *
     * @param villages         the registry's villages, keyed by village id
     * @param inactiveVillages villages without a valid Town Hall; skipped here
     */
    void processDormancyChecks(ServerLevel sl, Map<UUID, VillageManager> villages,
                               Set<UUID> inactiveVillages) {
        long gameTime = sl.getGameTime();
        for (Map.Entry<UUID, VillageManager> entry : villages.entrySet()) {
            UUID villageId = entry.getKey();
            VillageManager mgr = entry.getValue();
            if (!VillageManagerRegistry.dimMatch(mgr, sl)) continue;
            // Orphaned (Town-Hall-missing) villages are a separate lifecycle;
            // dormancy detection does not apply to them.
            if (inactiveVillages.contains(villageId)) continue;

            if (reconcilingVillages.contains(villageId)) {
                // Asleep at world load and being built a slice per tick: the
                // wait for chunks and villagers starts once it stands, so the
                // timeout below can never catch up a village with no map.
                if (mgr.isAwaitingInitialize()) continue;
                int waited = reconcilingTicksRemaining.merge(villageId, 1, Integer::sum);
                // Far villagers stay awake: the ground this wait needs (far members, bell, doors)
                // is asked for once, on the wait's first tick (its map stands by then).
                if (waited == 1) com.alex.test.village.farawake.FarAwake.onVillageWoke(mgr, sl);
                boolean ready = mgr.isReadyForReconciliation(sl);
                // Puppets can load without their join reaching the village (seen on the beta
                // server with Moonrise: 20 timeouts in 77 wakes, then "Rebound" a second after
                // the catch-up had skipped them). Look for them once a second while waiting.
                if (!ready && waited % 20 == 0) {
                    mgr.rebindMissingPuppets(sl);
                    ready = mgr.isReadyForReconciliation(sl);
                }
                if (!ready && waited < RECONCILE_WAIT_TIMEOUT_TICKS) {
                    continue;
                }
                if (!ready) {
                    LOGGER.warn("[Registry] Village {} reconciling wait timed out after {} tick(s); "
                                    + "proceeding with whatever chunks/puppets are currently loaded.",
                            VillageManagerRegistry.shortId(villageId), waited);
                }

                CatchUp away = catchUps.get(villageId);
                if (away == null) {
                    away = startCatchUp(sl, villageId);
                    catchUps.put(villageId, away);
                    markDirty.run();
                }
                try {
                    if (!away.step(sl, mgr, System.nanoTime() + CATCH_UP_NANOS_PER_TICK)) {
                        // More next tick. Saved as far as it got: a restart
                        // goes on from there instead of paying twice.
                        markDirty.run();
                        continue;
                    }
                    mgr.recordLastReconciliation(away.summary());
                    oweSkips(villageId, mgr, away);
                    AwayReport.send(sl, mgr, away);
                    LOGGER.info("[Registry] Village {} finished reconciling {} of {} tick(s) away; "
                                    + "resuming normal simulation.",
                            VillageManagerRegistry.shortId(villageId), away.countedTicks(), away.awayTicks());
                } catch (Exception e) {
                    // Never leave the village stuck retrying an identical throw
                    // forever (which would also abort this tick's processing
                    // for every OTHER village, since this runs inside the
                    // shared per-village loop). Log it, record a failure
                    // marker, and still fall through to ACTIVE below.
                    LOGGER.error("[Registry] Village {} failed reconciliation after {} counted tick(s); "
                                    + "forcing it back to ACTIVE with the catch-up only part applied.",
                            VillageManagerRegistry.shortId(villageId), away.countedTicks(), e);
                    mgr.recordLastReconciliation(ReconciliationSummary.failed(
                            away.countedTicks(), away.awayTicks(), String.valueOf(e)));
                    // The cap still holds: children, prices, raids skip the
                    // uncounted days even though the end step never ran.
                    try {
                        AwaySkip.skipUncounted(sl, mgr, away);
                        oweSkips(villageId, mgr, away);
                    } catch (Exception skipFailed) {
                        LOGGER.error("[Registry] Village {}: skipping the uncounted days also failed.",
                                VillageManagerRegistry.shortId(villageId), skipFailed);
                    }
                    try {
                        AwayReport.sendBroke(sl, mgr);
                    } catch (Exception reportFailed) {
                        LOGGER.error("[Registry] Village {}: telling the players also failed.",
                                VillageManagerRegistry.shortId(villageId), reportFailed);
                    }
                }
                reconcilingVillages.remove(villageId);
                reconcilingTicksRemaining.remove(villageId);
                dormantSinceGameTime.remove(villageId);
                dormantSinceEmpty.remove(villageId);
                dormantSinceVillageTime.remove(villageId);
                watchedTicks.remove(villageId);
                watchSpots.remove(villageId);
                ownerAwayTicks.remove(villageId);
                catchUps.remove(villageId);
                markDirty.run();
                continue;
            }

            int offset = Math.floorMod(villageId.hashCode(), DORMANCY_CHECK_INTERVAL);
            if (Math.floorMod(gameTime + offset, DORMANCY_CHECK_INTERVAL) != 0) continue;

            boolean playerNearby = isPlayerNearVillage(sl, mgr);

            if (dormantVillages.contains(villageId)) {
                // The last check's worth of time: with its owner and every Officer offline it rests
                // (owner offline, 10-25); else when the game grew its fields or pens live. Never both,
                // so no time is left out twice.
                if (ownerOffline(sl, mgr)) {
                    ownerAwayTicks.merge(villageId, (long) DORMANCY_CHECK_INTERVAL, Long::sum);
                    markDirty.run();
                } else if (OutsideWatch.growsLive(sl, watchSpots.computeIfAbsent(villageId, id -> spotsOf(mgr)))) {
                    watchedTicks.merge(villageId, (long) DORMANCY_CHECK_INTERVAL, Long::sum);
                    markDirty.run();
                }
                if (!playerNearby) continue;
                // DORMANT -> RECONCILING: snap back the moment someone returns,
                // no grace period needed on this direction. dormantSinceGameTime
                // is deliberately left untouched — the reconciliation phase
                // needs it to compute how many ticks were skipped.
                dormantVillages.remove(villageId);
                reconcilingVillages.add(villageId);
                reconcilingTicksRemaining.put(villageId, 0);
                markDirty.run();
                LOGGER.info("[Registry] Village {} is reconciling after a player returned.",
                        VillageManagerRegistry.shortId(villageId));
                continue;
            }

            // Currently active (neither dormant nor reconciling).
            if (playerNearby) {
                dormancyMissStreak.remove(villageId);
                continue;
            }
            if (mgr.raids().inProgress()) {
                // A raid in progress is treated as an implicit "player is
                // effectively still relevant here" condition: guard/archer
                // combat AI only ticks via mgr.tick(), which the DORMANT gate
                // in tickAll skips entirely, but the vanilla raiders keep
                // fighting regardless (they run their own goalSelector/
                // targetSelector independent of VillageManager). Going
                // dormant mid-raid would freeze the defense with zero
                // response for as long as the village stayed dormant.
                dormancyMissStreak.remove(villageId);
                continue;
            }
            if (TestKit.headless() && !TestKit.hasSimulatedPlayer()) {
                // A headless test server has zero real players connected, ever
                // (TestKit exists specifically to keep villages ticking despite
                // that -- see its class doc), so isPlayerNearVillage is always
                // false here and would otherwise mark every village dormant
                // within a few hundred game ticks of any sprint -- a fraction
                // of a real second -- and never bring it back, since no real
                // player will ever walk in to trigger the DORMANT ->
                // RECONCILING transition. That silently freezes brain ticking,
                // production, breeding and raids for the rest of any scenario
                // that outlives DORMANCY_GRACE_MISSES checks, defeating
                // TestKit.headless()'s entire contract. Automatic ACTIVE ->
                // DORMANT detection is therefore skipped outright in headless
                // mode; scenarios that want to exercise dormancy still can, via
                // the explicit /vtest village dormant test hook, which bypasses
                // this detection entirely and is unaffected by this guard.
                // A scenario that instead wants to exercise THIS real
                // detection logic (the miss-streak/stagger below, not a
                // forced state) can opt back in via
                // TestKit.setSimulatedPlayer(...) (/vtest village
                // simulate_player) -- hasSimulatedPlayer() then short-circuits
                // this guard so the real accounting below runs, driven by
                // isPlayerNearVillage's fake-position check above.
                dormancyMissStreak.remove(villageId);
                continue;
            }
            int misses = dormancyMissStreak.merge(villageId, 1, Integer::sum);
            if (misses >= DORMANCY_GRACE_MISSES) {
                dormancyMissStreak.remove(villageId);
                goDormant(sl, villageId);
                markDirty.run();
                LOGGER.info("[Registry] Village {} is now dormant; no player nearby for {} check(s).",
                        VillageManagerRegistry.shortId(villageId), DORMANCY_GRACE_MISSES);
            }
        }
    }

    /**
     * The catch-up for a village a player just came back to: the time since
     * it went dormant, less the server's empty time (an owned village: the
     * time its owner and Officers were offline, {@link #ownerAwayTicks}) and
     * the time its fields or pens grew live ({@link #watchedTicks}), capped by
     * {@link AwayClock}; and how far the village calendar ran, which is
     * what children age by. The away calculator works the days right after
     * everyone left, from that time of day.
     */
    private CatchUp startCatchUp(ServerLevel sl, UUID villageId) {
        long gameTime = sl.getGameTime();
        long awayTicks = Math.max(0L, gameTime - dormantSinceGameTime.getOrDefault(villageId, gameTime));
        long emptyTicks = Math.max(0L, emptyServerTicks - dormantSinceEmpty.getOrDefault(villageId, emptyServerTicks));
        long ownerAway = Math.min(ownerAwayTicks.getOrDefault(villageId, 0L), awayTicks);
        long offline = Math.min(AwayClock.offlineTicks(emptyTicks, ownerAway), awayTicks);
        // Never more than the time away; the two never overlap (watched needs someone in the world,
        // and is not kept while the village rests for its owner).
        long watched = Math.min(watchedTicks.getOrDefault(villageId, 0L), awayTicks - offline);
        long villageNow = VillageDays.villageTime(sl.getDayTime());
        Long sinceVillageTime = dormantSinceVillageTime.get(villageId);
        long villageElapsed = sinceVillageTime != null ? villageNow - sinceVillageTime : awayTicks;
        long counted = AwayClock.countedTicks(awayTicks, offline + watched);
        long start = sinceVillageTime != null ? sinceVillageTime : villageNow - awayTicks;
        return new AwayRun(villageId, awayTicks, counted, villageElapsed, start).watchedFromOutside(watched)
                .ownerOffline(ownerAway);
    }

    /**
     * Owner offline (10-25): claims on, the village has an owner, and neither they nor any
     * Officer is on the server. Never on a headless test server, which has no players at all.
     */
    private boolean ownerOffline(ServerLevel sl, VillageManager mgr) {
        if (TestKit.headless() || !claimsEnabled.getAsBoolean() || !mgr.claim().hasOwner()) return false;
        return !VillageClaims.ownerOrOfficerOnline(sl.getServer(), mgr.claim());
    }

    /** The village's field and pen chunks; none when reading them breaks (the time then all counts, as before). */
    private static OutsideWatch.Spots spotsOf(VillageManager mgr) {
        try {
            return OutsideWatch.of(mgr);
        } catch (Exception e) {
            LOGGER.error("[Registry] Village {}: noting its field and pen chunks failed; time watched from "
                    + "outside will count.", VillageManagerRegistry.shortId(mgr.getVillageId()), e);
            return new OutsideWatch.Spots(new long[0], new long[0]);
        }
    }

    /** Same "any real player standing inside this village's bounds" test as {@code GuardActivity.nearestPlayer}. */
    private static boolean isPlayerNearVillage(ServerLevel sl, VillageManager mgr) {
        for (ServerPlayer player : sl.players()) {
            if (player.isSpectator()) continue;
            if (mgr.contains(player.blockPosition())) return true;
        }
        // Test-only: a headless server has no real players (see TestKit's
        // simulated-player doc), so a scenario exercising the *real*
        // detection logic feeds a fake position in here instead.
        if (TestKit.hasSimulatedPlayer()
                && TestKit.simulatedPlayerDim().equals(sl.dimension())
                && mgr.contains(TestKit.simulatedPlayerPos())) {
            return true;
        }
        return false;
    }
}
