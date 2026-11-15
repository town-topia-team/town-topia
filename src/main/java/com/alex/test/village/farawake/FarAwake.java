package com.alex.test.village.farawake;

import com.alex.test.Config;
import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.performance.PerformanceSettings;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.MoveToCommand;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.stuckwatch.StuckWatch;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Far villagers stay awake (plan: docs/plans/2026-10-18-far-villagers-awake.md). While a
 * player is in a village, its villagers out past the simulation distance keep living their
 * day: the chunk under each one is held loaded but quiet (a level 33 ticket that runs out by
 * itself unless renewed, {@link FarHolds}), and the mod runs that body's tick itself where
 * Minecraft does not. No caps and no time budget (user, 10-18): every member of an awake village
 * inside its map bounds (plus {@link #BOUNDS_MARGIN_BLOCKS}) is kept.
 *
 * <p>Built so far (10-19): rounds 1-7. The hooks, the switches, the {@code [FarAwake]} minute
 * lines, the holds (far bodies no longer unload) and the body ticks: past the simulation
 * distance the mod moves them itself ({@link FarBodyTicker}), and what they set moving
 * ({@link FarExtras}). The ground ahead loads first; no teleport into ground still loading.
 * Bed lanterns are gone.
 *
 * <p>Everything here runs on the server thread and nothing here is saved: a restart starts clean.
 * The world's choices for {@code awake} and {@code sync} are saved apart ({@link PerformanceSettings}).
 */
public final class FarAwake {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Live switches (/villager farawake ...), not saved. At server start awake comes from the config, the
    // rest go on; then the world's saved choices (Mayor Performance page, PerformanceSettings) win.
    public static volatile boolean awake = true;
    /** The safety cut-out switched {@link #awake} off ({@link #stopAfterErrors}). Not saved; any other change clears it. */
    public static volatile boolean stoppedAfterErrors;
    /** Asleep far villagers are held but not moved. */
    public static volatile boolean sleepSkip = true;
    /** Arrows, led animals and killed creatures of far villagers move too. */
    public static volatile boolean extras = true;
    /** No teleport into a chunk that is not loaded (works with the system off too). */
    public static volatile boolean placeGuard = true;
    /** Far villagers' chunks load when a village wakes or the server starts. */
    public static volatile boolean wake = true;
    /** The [FarAwake] minute lines. */
    public static volatile boolean log = true;
    /** Far bodies the mod moves are sent to the players who see them ({@link FarSync}); off, they jump on screen. Set it with {@link #setSync}. */
    public static volatile boolean sync = true;
    /** {@link FarSync}'s first error switched {@link #sync} off ({@link #stopSyncAfterErrors}). Not saved; any other change clears it. */
    public static volatile boolean syncStoppedAfterErrors;

    /** Ticks a hold lives without renewal. */
    static final int HOLD_LIFESPAN = 300;
    static final int RENEW_EVERY = 100;
    /** A chunk nobody needs any more is let go after this long (keeps border churn down). */
    static final int RELEASE_AFTER = 1200;
    /** New holds asked for each tick: spreads chunk loads, never refuses one. */
    static final int NEW_HOLDS_PER_TICK = 4;
    /** A village that has not ticked for this long has its holds let go. */
    static final int VILLAGE_STALE_TICKS = 40;
    /** Ticks between two [FarAwake] lines of one village (and of one level). */
    static final int LOG_EVERY = 1200;
    /** A far body's chunks: every chunk its box widened by this many blocks touches. */
    static final double EDGE_MARGIN = 2.0;
    /** Members this far outside the village map are left alone (they unload as before). */
    static final int BOUNDS_MARGIN_BLOCKS = 32;
    /**
     * A body Minecraft still runs has its own chunk held too ({@link FarRank#NEAR}), so it stays
     * loaded when the player walks off. Asked for this often: the hold lasts far longer anyway.
     */
    static final int NEAR_EVERY = 20;
    /** A villager whose body tick threw waits this long before it is moved again. */
    static final int ERROR_COOLDOWN = 200;
    /** This many throws within {@link #BREAKER_WINDOW} ticks switch the system off. */
    static final int BREAKER_ERRORS = 20;
    static final int BREAKER_WINDOW = 1200;
    /** A body waiting this long for its ground to load gets a [FarEdge] line (shorter waits are only counted). */
    static final int EDGE_LOG_TICKS = 40;
    /** A far walking body has the chunks of this many route tiles ahead held too, and its goal's. */
    static final int AHEAD_TILES = 12;
    /** Least ticks between two [FarPlace] lines of one level (the others are counted). */
    static final int PLACE_LOG_TICKS = 100;
    /** The "village" that asks for a chunk a refused teleport wanted ({@link #mayPlace}). */
    static final UUID SYSTEM_ID = new UUID(0L, 0L);
    /** A waking village's missing members, bell and doors are held this long at most (30 s). */
    static final int WAKE_TICKS = 600;

    private static final FarRank[] RANKS = FarRank.values();
    private static final Map<ServerLevel, LevelState> LEVELS = new WeakHashMap<>();

    private FarAwake() {}

    /** What one level knows. */
    static final class LevelState {
        final Map<UUID, VillageState> villages = new HashMap<>();
        final FarHoldBook book = new FarHoldBook(
                new FarHoldBook.Settings(NEW_HOLDS_PER_TICK, RENEW_EVERY, RELEASE_AFTER));
        final FarHolds holds;
        long nextLog = Long.MIN_VALUE;
        // Since the last level line.
        int added, renewed, forgotten, placeWaits;
        long lastPlaceLog = Long.MIN_VALUE;
        int placeWaitsUnlogged;
        /**
         * {@link FarExtras}, since the last level line: ticks run, waits for ground, throws, entities sent
         * to players ({@link FarSync}).
         */
        int extraMoves, extraWaits, extraErrors, extraSynced;
        /** Reused for the look-ahead, so a walking body costs no new object a tick. */
        final NeedSink sink = new NeedSink();
        /** Villages that just woke, by village id ({@link #onVillageWoke}). */
        final Map<UUID, Wake> wakes = new HashMap<>();

        LevelState(ServerLevel sl) {
            this.holds = new FarHolds(sl);
        }
    }

    /**
     * A village that just woke, or ticks for the first time since it was last seen (a server start).
     * For {@link #WAKE_TICKS} at most, the chunk where each missing member was last seen is held, so
     * its body loads and the village finds it again; for a wake from sleep also the bell's and every
     * door's chunk, which the away catch-up waits for. A reconciling village does not run
     * {@link #tickVillage}, so {@link #tickLevel} renews these needs each tick.
     */
    static final class Wake {
        final VillageManager mgr;
        final long started;
        /** Members still missing: the chunk of their last-seen spot. */
        final Map<UUID, Long> members;
        final int missingAtStart;
        /** The bell's and the doors' chunks; empty for a first tick after a start. */
        final long[] places;
        int found;

        Wake(VillageManager mgr, long started, Map<UUID, Long> members, long[] places) {
            this.mgr = mgr;
            this.started = started;
            this.members = members;
            this.missingAtStart = members.size();
            this.places = places;
        }
    }

    /** Asks the book for each chunk it is given, for one village at one rank. */
    static final class NeedSink implements java.util.function.LongConsumer {
        FarHoldBook book;
        UUID village;
        FarRank rank;
        long now;

        @Override
        public void accept(long chunk) {
            book.need(chunk, village, rank, now);
        }
    }

    /** What one awake village knows. */
    static final class VillageState {
        final UUID id;
        final VillageManager mgr;
        long lastTick = Long.MIN_VALUE;
        /** Game time of this village's next [FarAwake] line (spread over the minute by village). */
        long nextLog;
        /** Last tick: far bodies held, by {@link FarRank}. */
        final int[] farByRank = new int[RANKS.length];
        int far;
        /** Last tick: far bodies asleep (held, not moved). */
        int asleep;
        /** This tick's far bodies, filled by {@link #needBodies}, moved after (reused). */
        final List<NpcEntity> farBodies = new ArrayList<>();
        /** Bodies waiting for their ground to load: since when. */
        final Map<UUID, Long> edgeSince = new HashMap<>();
        final java.util.Set<UUID> edgeNow = new java.util.HashSet<>();
        /** Waits that got their [FarEdge] line, so their end gets one too. */
        final java.util.Set<UUID> edgeLogged = new java.util.HashSet<>();
        // Since the last minute line; synced = entities sent to players ({@link FarSync}), syncNanos its time.
        long ticks, moved, moveNanos, moveMaxNanos, edgeWaits, errors, synced, syncNanos;

        VillageState(UUID id, VillageManager mgr) {
            this.id = id;
            this.mgr = mgr;
        }
    }

    private static LevelState state(ServerLevel sl) {
        LevelState ls = LEVELS.get(sl);
        if (ls == null) {
            ls = new LevelState(sl);
            LEVELS.put(sl, ls);
        }
        return ls;
    }

    /**
     * Once per level tick, from {@code VillageManagerRegistry.tickAll}, before the empty-server
     * return and before the village ticks. Lets go of villages that stopped ticking (by their
     * last tick, {@link #VILLAGE_STALE_TICKS}), moves the extras, renews the wakes, then asks for,
     * renews and lets go of holds ({@link FarHoldBook#tick}). Its time goes to the cost meter ({@link FarCost}).
     */
    public static void tickLevel(ServerLevel sl) {
        long began = System.nanoTime();
        tickLevelWork(sl);
        FarCost.addAll(sl.getServer().getTickCount(), System.nanoTime() - began);
    }

    private static void tickLevelWork(ServerLevel sl) {
        // /tick freeze or stepping: the village ticks are skipped and tickets do not age either,
        // so nothing may be let go for not ticking.
        if (StuckWatch.worldHeld(sl)) return;
        LevelState ls = LEVELS.get(sl);
        if (ls == null) {
            // No village ticks here, so nothing is moved here: a member's shot or kill in this
            // dimension (it followed a player) would otherwise sit in the lists until the level unloads.
            FarExtras.forgetLevel(sl);
            return;
        }
        long now = sl.getGameTime();

        // A village asleep, without a hall, gone or in another dimension stops ticking: forget it.
        Iterator<VillageState> it = ls.villages.values().iterator();
        while (it.hasNext()) {
            VillageState vs = it.next();
            // A village waking is not ticking yet (it is catching up): its wake needs stay.
            if (now - vs.lastTick > VILLAGE_STALE_TICKS && !ls.wakes.containsKey(vs.id)) {
                ls.book.forgetVillage(vs.id);
                it.remove();
            }
        }

        boolean nobody = sl.getServer().getPlayerList().getPlayerCount() == 0 && !TestKit.headless();
        if (!awake || nobody) {
            // Nothing renewed again: every hold runs out within HOLD_LIFESPAN, as before the system.
            ls.book.forgetAll();
            FarExtras.forgetLevel(sl);
            ls.wakes.clear();
            return;
        }

        // Before the book: the ground an arrow, a led animal or a waking village asks for is added this tick.
        FarExtras.tick(sl, ls, now);
        if (!wake) ls.wakes.clear();
        tickWakes(sl, ls, now);
        ls.book.tick(now, ls.holds);
        ls.added += ls.book.addedLastTick();
        ls.renewed += ls.book.renewedLastTick();
        ls.forgotten += ls.book.forgottenLastTick();
        if (log && now >= ls.nextLog) {
            boolean first = ls.nextLog == Long.MIN_VALUE;
            ls.nextLog = now + LOG_EVERY;
            if (!first && (ls.book.heldCount() > 0 || ls.added + ls.forgotten > 0)) {
                LOGGER.info("[FarAwake] {}", FarStats.describeLevel(sl, ls));
            }
            ls.added = ls.renewed = ls.forgotten = ls.placeWaits = 0;
            ls.extraMoves = ls.extraWaits = ls.extraErrors = ls.extraSynced = 0;
        }
    }

    /**
     * At the top of {@code VillageManager.tick}, before the walkers. Every member body inside the
     * village map (plus {@link #BOUNDS_MARGIN_BLOCKS}) that Minecraft does not run has the chunks
     * under it held, at its {@link FarRank}; every other one has its own chunk held as
     * {@link FarRank#NEAR}. Returns the nanoseconds spent here (the "farBodies" phase).
     */
    public static long tickVillage(VillageManager mgr, ServerLevel sl) {
        long began = System.nanoTime();
        long now = sl.getGameTime();
        LevelState ls = state(sl);
        VillageState vs = ls.villages.get(mgr.getVillageId());
        if (vs == null || vs.mgr != mgr) {
            vs = new VillageState(mgr.getVillageId(), mgr);
            vs.nextLog = now + Math.floorMod(vs.id.hashCode(), LOG_EVERY);
            ls.villages.put(vs.id, vs);
            // First tick since the start (a village awake at shutdown never sleeps and wakes on a
            // restart) or since it stopped ticking: its far members' ground loads.
            if (awake && wake && !ls.wakes.containsKey(vs.id)) startWake(mgr, sl, ls, now, false);
        }
        vs.lastTick = now;
        Arrays.fill(vs.farByRank, 0);
        vs.far = 0;
        vs.asleep = 0;
        vs.ticks++;
        if (awake) {
            needBodies(mgr, sl, ls, vs, now);
            moveBodies(sl, vs, now);
        }
        if (log && now >= vs.nextLog) {
            vs.nextLog = now + LOG_EVERY;
            FarStats.logVillage(mgr, sl, vs, ls.book);
        }
        long took = System.nanoTime() - began;
        FarCost.addAll(sl.getServer().getTickCount(), took);
        return took;
    }

    private static void needBodies(VillageManager mgr, ServerLevel sl, LevelState ls,
                                   VillageState vs, long now) {
        FarHoldBook book = ls.book;
        NavGraph graph = mgr.getGraph();
        if (graph == null) return;
        double minX = graph.getBoundsMinX() - BOUNDS_MARGIN_BLOCKS;
        double maxX = graph.getBoundsMaxX() + 1 + BOUNDS_MARGIN_BLOCKS;
        double minZ = graph.getBoundsMinZ() - BOUNDS_MARGIN_BLOCKS;
        double maxZ = graph.getBoundsMaxZ() + 1 + BOUNDS_MARGIN_BLOCKS;
        boolean nearRound = Math.floorMod(now + vs.id.hashCode(), NEAR_EVERY) == 0;
        vs.farBodies.clear();
        for (VillagerModel model : mgr.getVillagers()) {
            NpcEntity npc = model.puppet();
            // A dying body stays in: it is moved until it has finished dying.
            if (npc == null || npc.isRemoved() || npc.level() != sl) continue;
            double x = npc.getX(), z = npc.getZ();
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
            BlockPos pos = npc.blockPosition();
            if (sl.isPositionEntityTicking(pos)) {
                if (nearRound) book.need(ChunkPos.asLong(pos), vs.id, FarRank.NEAR, now);
                continue;
            }
            FarRank rank = FarRanks.of(model.brain().currentActivity(), npc);
            vs.farByRank[rank.ordinal()]++;
            vs.far++;
            vs.farBodies.add(npc);
            AABB box = npc.getBoundingBox();
            int cx0 = SectionPos.blockToSectionCoord(box.minX - EDGE_MARGIN);
            int cx1 = SectionPos.blockToSectionCoord(box.maxX + EDGE_MARGIN);
            int cz0 = SectionPos.blockToSectionCoord(box.minZ - EDGE_MARGIN);
            int cz1 = SectionPos.blockToSectionCoord(box.maxZ + EDGE_MARGIN);
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) book.need(ChunkPos.asLong(cx, cz), vs.id, rank, now);
            }
            // Look ahead: the route's next tiles and the goal are loaded before the body gets
            // there, so it does not stop at each chunk line, nor at the field or chest it walks to.
            MoveToCommand move = model.activeMove();
            if (move != null) {
                NeedSink sink = ls.sink;
                sink.book = book;
                sink.village = vs.id;
                sink.rank = rank;
                sink.now = now;
                move.chunksAhead(AHEAD_TILES, sink);
                BlockPos goal = model.moveGoal();
                if (goal != null) book.need(ChunkPos.asLong(goal), vs.id, rank, now);
            }
        }
    }

    /**
     * Runs the body tick of every far body found this tick ({@link FarBodyTicker}), after the
     * villager list is walked: a body tick may change the village's members (a death).
     */
    private static void moveBodies(ServerLevel sl, VillageState vs, long now) {
        List<NpcEntity> bodies = vs.farBodies;
        for (int i = 0; i < bodies.size(); i++) {
            if (!awake) break; // the breaker switched the system off during this loop
            NpcEntity npc = bodies.get(i);
            // Its seat before the tick: standing up changes the seat's rider list, which watchers need too.
            Entity rodeBefore = npc.getVehicle();
            long began = System.nanoTime();
            FarBodyTicker.Result result = FarBodyTicker.tick(sl, npc, now);
            switch (result) {
                case TICKED -> {
                    long ended = System.nanoTime();
                    long took = ended - began;
                    vs.moved++;
                    vs.moveNanos += took;
                    if (took > vs.moveMaxNanos) vs.moveMaxNanos = took;
                    // Timed apart, so "us avg" stays comparable with the lines before the send.
                    vs.synced += FarSync.send(sl, npc, rodeBefore);
                    vs.syncNanos += System.nanoTime() - ended;
                }
                case ASLEEP -> vs.asleep++;
                case EDGE -> noteEdge(vs, npc, now);
                case FAILED -> vs.errors++;
                default -> { }
            }
        }
        bodies.clear();
        if (!vs.edgeSince.isEmpty()) {
            Iterator<Map.Entry<UUID, Long>> it = vs.edgeSince.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> wait = it.next();
                if (vs.edgeNow.contains(wait.getKey())) continue;
                long waited = now - wait.getValue();
                if (vs.edgeLogged.remove(wait.getKey())) {
                    LOGGER.info("[FarEdge] {} goes on after {} s", wait.getKey().toString().substring(0, 8),
                            waited / 20);
                }
                it.remove();
            }
        }
        vs.edgeNow.clear();
    }

    private static void noteEdge(VillageState vs, NpcEntity npc, long now) {
        vs.edgeWaits++;
        UUID id = npc.getNpcId();
        vs.edgeNow.add(id);
        Long since = vs.edgeSince.get(id);
        if (since == null) {
            vs.edgeSince.put(id, now);
        } else if (log && now - since >= EDGE_LOG_TICKS && vs.edgeLogged.add(id)) {
            long chunk = FarBodyTicker.lastEdgeChunk;
            LOGGER.info("[FarEdge] {} ({}) has waited {} s at {}: chunk {},{} not loaded yet",
                    id.toString().substring(0, 8), npc.getProfession(), (now - since) / 20,
                    npc.blockPosition().toShortString(), ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
        }
    }

    /**
     * Whether this body moved this tick, for the walk: on frozen ground the walk waits. System on:
     * the body's own stamp (Minecraft's tick or ours). Off: Minecraft's test, as before.
     */
    public static boolean bodyMovesThisTick(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel sl)) return true;
        if (!awake) return sl.isPositionEntityTicking(npc.blockPosition());
        return npc.lastBodyTickTime() == sl.getGameTime();
    }

    /**
     * A sleeping village starts to catch up ({@code VillageDormancy}, the first tick of its wait,
     * so its map is there): the ground where its missing members were last seen loads, and its
     * bell and doors, which the catch-up waits for. No-op when off or with {@code wake} off.
     */
    public static void onVillageWoke(VillageManager mgr, ServerLevel sl) {
        if (!awake || !wake) return;
        startWake(mgr, sl, state(sl), sl.getGameTime(), true);
    }

    private static void startWake(VillageManager mgr, ServerLevel sl, LevelState ls, long now, boolean withPlaces) {
        NavGraph graph = mgr.getGraph();
        if (graph == null) return;
        double minX = graph.getBoundsMinX() - BOUNDS_MARGIN_BLOCKS;
        double maxX = graph.getBoundsMaxX() + 1 + BOUNDS_MARGIN_BLOCKS;
        double minZ = graph.getBoundsMinZ() - BOUNDS_MARGIN_BLOCKS;
        double maxZ = graph.getBoundsMaxZ() + 1 + BOUNDS_MARGIN_BLOCKS;
        Map<UUID, Long> members = new HashMap<>();
        for (VillagerModel model : mgr.getVillagers()) {
            NpcEntity puppet = model.puppet();
            if (puppet != null && puppet.isAlive() && !puppet.isRemoved()) continue;
            // Unknown, in another dimension, or already being searched for in the save files.
            BlockPos spot = model.lastSeenSpot();
            if (spot == null || model.lastSeenDimension() != null || model.lookingForBody()) continue;
            if (spot.getX() < minX || spot.getX() > maxX || spot.getZ() < minZ || spot.getZ() > maxZ) continue;
            members.put(model.npcId, ChunkPos.asLong(spot));
        }
        long[] places = new long[0];
        int placesLoading = 0;
        if (withPlaces) {
            java.util.Set<Long> chunks = new java.util.HashSet<>();
            // getBellPos falls back to 0,0,0 when there is none: that chunk is not the village's.
            BlockPos bell = mgr.getBellPos();
            if (!BlockPos.ZERO.equals(bell)) chunks.add(ChunkPos.asLong(bell));
            for (com.alex.test.village.building.Building building : mgr.getBuildings()) {
                BlockPos door = building.getDoorPos();
                if (door != null) chunks.add(ChunkPos.asLong(door));
            }
            places = chunks.stream().mapToLong(Long::longValue).toArray();
            for (long chunk : places) if (!FarHolds.ready(sl, chunk)) placesLoading++;
        }
        if (members.isEmpty() && placesLoading == 0) return;
        ls.wakes.put(mgr.getVillageId(), new Wake(mgr, now, members, places));
        if (log) {
            LOGGER.info("[FarAwake] Village {} {}: loading the ground of {} missing villager(s){}",
                    mgr.getVillageId().toString().substring(0, 8), withPlaces ? "is waking" : "ticks again",
                    members.size(), withPlaces ? " and " + placesLoading + " of " + places.length
                            + " bell and door chunk(s) not loaded yet" : "");
        }
    }

    /** Renews every live wake's needs; ends a wake when all are found and loaded, or after {@link #WAKE_TICKS}. */
    private static void tickWakes(ServerLevel sl, LevelState ls, long now) {
        if (ls.wakes.isEmpty()) return;
        Iterator<Wake> it = ls.wakes.values().iterator();
        while (it.hasNext()) {
            Wake w = it.next();
            UUID village = w.mgr.getVillageId();
            Iterator<Map.Entry<UUID, Long>> members = w.members.entrySet().iterator();
            while (members.hasNext()) {
                Map.Entry<UUID, Long> member = members.next();
                VillagerModel model = w.mgr.getModel(member.getKey());
                NpcEntity puppet = model == null ? null : model.puppet();
                if (puppet != null && puppet.isAlive() && !puppet.isRemoved()) {
                    w.found++;
                    members.remove();
                } else if (model == null || model.lookingForBody()) {
                    // Forgotten, or its spot loaded with no body there: the save file search takes over.
                    members.remove();
                } else {
                    ls.book.need(member.getValue(), village, FarRank.FOOD, now);
                }
            }
            int placesLoading = 0;
            for (long chunk : w.places) {
                ls.book.need(chunk, village, FarRank.FOOD, now);
                if (!FarHolds.ready(sl, chunk)) placesLoading++;
            }
            boolean done = w.members.isEmpty() && placesLoading == 0;
            if (!done && now - w.started < WAKE_TICKS) continue;
            if (log) {
                LOGGER.info("[FarAwake] Village {} {} after {} s: found {} of {} missing villager(s){}",
                        village.toString().substring(0, 8), done ? "woke" : "stopped waiting",
                        (now - w.started) / 20, w.found, w.missingAtStart,
                        placesLoading > 0 ? ", " + placesLoading + " bell and door chunk(s) still loading" : "");
            }
            it.remove();
        }
    }

    /**
     * Whether a body (villager or animal) may be put at {@code x, z} without walking there: that
     * chunk is loaded with its creatures. An unloaded chunk has no walls, and a body put into one
     * is lost to the level until the next save. Always true with {@code placeguard} off; works with
     * the system off too (it fixes a bug that was there before it).
     */
    public static boolean readyFor(net.minecraft.world.level.Level level, double x, double z) {
        if (!placeGuard || !(level instanceof ServerLevel sl)) return true;
        return FarHolds.ready(sl, ChunkPos.asLong(net.minecraft.util.Mth.floor(x) >> 4,
                net.minecraft.util.Mth.floor(z) >> 4));
    }

    /**
     * {@link #readyFor}, for a teleport that will be tried again: when the ground is not ready the
     * chunk is asked for ({@link #needSoon}), counted ("place waits" in the dimension line) and
     * logged at most every {@link #PLACE_LOG_TICKS} ticks. {@code what}: the move, in a few words.
     */
    public static boolean mayPlace(net.minecraft.world.level.Level level, double x, double z, String what) {
        if (readyFor(level, x, z)) return true;
        ServerLevel sl = (ServerLevel) level;
        needSoon(sl, x, z);
        LevelState ls = state(sl);
        ls.placeWaits++;
        long now = sl.getGameTime();
        if (now - ls.lastPlaceLog >= PLACE_LOG_TICKS) {
            LOGGER.info("[FarPlace] {} not done at ({}, {}): that chunk is not loaded yet; tried again later{}",
                    what, net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(z),
                    ls.placeWaitsUnlogged > 0 ? " (" + ls.placeWaitsUnlogged + " more since the last line)" : "");
            ls.lastPlaceLog = now;
            ls.placeWaitsUnlogged = 0;
        } else {
            ls.placeWaitsUnlogged++;
        }
        return false;
    }

    /**
     * Has the chunk at {@code x, z} loaded soon (held as for a villager walking home). Off: one
     * ticket of its own instead (the book is emptied every tick then), renewed while the move keeps
     * asking and gone {@link #HOLD_LIFESPAN} after; else the place guard would wait for a player.
     */
    public static void needSoon(ServerLevel sl, double x, double z) {
        long chunk = ChunkPos.asLong(net.minecraft.util.Mth.floor(x) >> 4, net.minecraft.util.Mth.floor(z) >> 4);
        if (!awake) {
            new FarHolds(sl).add(chunk);
            return;
        }
        state(sl).book.need(chunk, SYSTEM_ID, FarRank.HOME, sl.getGameTime());
    }

    /** {@code /villager farawake on|off}. Off: no hold is renewed again, all run out within 15 s. */
    public static void setAwake(boolean on) {
        boolean was = awake;
        awake = on;
        stoppedAfterErrors = false; // only the cut-out, as the last change, shows "stopped"
        if (on && !was) restartVillages();
    }

    /**
     * The safety cut-out ({@link FarBodyTicker} after repeated errors): off, not saved, so the next
     * server start tries again. Server owners online get one chat line, so a repeating error is
     * noticed. Never throws.
     */
    static void stopAfterErrors(@Nullable MinecraftServer server) {
        setAwake(false);
        stoppedAfterErrors = true;
        tellOwners(server, Tr.c("chat.dynamicvillages.performance.stopped",
                "TownTopia stopped far villagers after errors; see the log."));
    }

    /** {@code /villager farawake sync on|off} and the Mayor's Performance page. */
    public static void setSync(boolean on) {
        sync = on;
        syncStoppedAfterErrors = false; // only FarSync's error, as the last change, shows "stopped"
    }

    /** {@link FarSync}'s first error: smooth far movement off (not saved), server owners told once. Never throws. */
    static void stopSyncAfterErrors(@Nullable MinecraftServer server) {
        setSync(false);
        syncStoppedAfterErrors = true;
        tellOwners(server, Tr.c("chat.dynamicvillages.performance.sync_stopped",
                "TownTopia stopped smooth far movement after errors; see the log."));
    }

    /** One gold chat line to every server owner online. Never throws. */
    private static void tellOwners(@Nullable MinecraftServer server, MutableComponent line) {
        if (server == null) return;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!PerformanceSettings.mayChange(player)) continue;
                player.sendSystemMessage(line.copy().withStyle(ChatFormatting.GOLD));
            }
        } catch (Exception e) {
            LOGGER.error("[FarAwake] could not tell the server owners: {}", line.getString(), e);
        }
    }

    /** {@code /villager farawake wake on|off}. */
    public static void setWake(boolean on) {
        boolean was = wake;
        wake = on;
        if (on && !was) restartVillages();
    }

    /**
     * Back on (or the breaker reset by hand): far members unloaded while it was off, and only a
     * wake loads them again. Dropping the village states makes the next village tick start one.
     * Commands run between ticks, so no loop is walking these maps now.
     */
    private static void restartVillages() {
        for (LevelState ls : LEVELS.values()) ls.villages.clear();
    }

    /** {@code /villager farawake}: the switches, this level's holds, one line per village ticking here. */
    public static List<String> status(ServerLevel sl) {
        List<String> lines = new ArrayList<>();
        lines.add("Far villagers awake: " + (awake ? "on" : "off") + (stoppedAfterErrors ? " (stopped after errors)" : "")
                + " (sleepskip " + onOff(sleepSkip) + ", extras " + onOff(extras)
                + ", placeguard " + onOff(placeGuard) + ", wake " + onOff(wake) + ", log " + onOff(log)
                + ", sync " + onOff(sync) + (syncStoppedAfterErrors ? " (stopped after errors)" : "") + ")");
        LevelState ls = LEVELS.get(sl);
        if (ls == null || ls.villages.isEmpty()) {
            lines.add("No village ticks in this dimension right now.");
            return lines;
        }
        lines.add(FarStats.describeLevel(sl, ls));
        for (VillageState vs : ls.villages.values()) lines.add(FarStats.describe(vs.mgr, sl, vs, ls.book, false));
        return lines;
    }

    static String rankName(int ordinal) {
        return RANKS[ordinal].name().toLowerCase(Locale.ROOT);
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    /** Server start and stop, level unload: nothing outlives them. */
    public static final class Events {

        private Events() {}

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            LEVELS.clear();
            ChunkStallWatch.clear();
            FarBodyTicker.clear();
            FarExtras.clear();
            FarCost.reset();
            awake = Config.farVillagersAwake();
            // Single player keeps the JVM between worlds: a switch turned off in the last one
            // (placeguard off brings back teleports into unloaded chunks) must not carry over.
            sleepSkip = extras = placeGuard = wake = log = sync = true;
            stoppedAfterErrors = false;
            syncStoppedAfterErrors = false;
            // Last: a saved choice wins over the config; a world that never chose keeps the values above.
            PerformanceSettings.applyAtStart(event.getServer());
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            LEVELS.clear();
            ChunkStallWatch.clear();
            FarBodyTicker.clear();
            FarExtras.clear();
        }

        @SubscribeEvent
        public static void onLevelUnload(LevelEvent.Unload event) {
            // Its tickets go with the level's chunk map.
            if (event.getLevel() instanceof ServerLevel sl) {
                LEVELS.remove(sl);
                FarExtras.forgetLevel(sl);
            }
        }

        /** A creature a villager killed far away is moved until it has finished dying. */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onLivingDeath(LivingDeathEvent event) {
            if (event.getSource().getEntity() instanceof NpcEntity killer) {
                FarExtras.trackKilled(event.getEntity(), killer);
            }
        }
    }
}
