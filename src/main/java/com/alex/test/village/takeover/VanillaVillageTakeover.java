package com.alex.test.village.takeover;

import com.alex.test.Config;
import com.alex.test.DynamicVillages;
import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.ChunkHold;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.SlicedMapBuild;
import com.alex.test.village.VillageFounding;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.BuildingAccess;
import com.alex.test.village.building.EnclosureScanner;
import com.alex.test.village.chat.TokenFeedback;
import com.alex.test.village.takeover.VanillaHouses.House;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.behavior.GuardPostRegistry;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turning brand-new generated villages into Dynamic Villages.
 *
 * <p>Only a village generated fresh, whose middle has never been loaded
 * before, is ever changed, and only while the config's
 * {@code generatedVillagesBecomeDynamic} is on. As its chunks load for the
 * first time, the vanilla villagers the generator put there are swapped for
 * waiting settlers of ours before anyone can see them (see
 * {@link VanillaVillagerSwap}). Then, once a player comes near, it wakes up
 * quietly as a small starter village of ours: the Town Hall, a Storage room
 * with food, enough marked houses for everyone, and 4 to 8 villagers. Houses
 * with an open doorway get a door.
 *
 * <p>Everything else is left vanilla: villages loaded before, abandoned
 * (zombie) villages, villages in or beside a Dynamic Village already, and every
 * vanilla villager that is not fresh from the generator, such as a cured zombie
 * villager or a bred one, wherever it goes. A village that turns out not to
 * wake after all gets its vanilla villagers back. A Town Hall hung by hand in
 * an old vanilla village founds an ordinary village.
 *
 * <p>The waking is spread over a few seconds (see {@link WakeUp}) so it is not
 * one long freeze, and a woken village nobody is near goes dormant like any other.
 */
public final class VanillaVillageTakeover {

    /**
     * Chunks a generated village claims each way around its Town Hall door:
     * 13 by 13 chunks, about 208 blocks across, against 19 by 19 for a village
     * founded by hand. A vanilla village is 100 to 160 blocks across; this
     * leaves a ring of land around it, and the land to load and map before it
     * can wake is about half.
     */
    static final int GENERATED_VILLAGE_RADIUS = 6;

    /** Houses marked at most, Town Hall and Storage room not counted. */
    private static final int MAX_HOMES = 4;
    /** Settlers wandering this far outside the village's buildings still count as its own. */
    private static final int VILLAGER_SEARCH_MARGIN = 16;
    /** Bed tokens by the beds a house holds. */
    private static final int TWO_BED_MAX = 2;
    private static final int FOUR_BED_MAX = 4;

    /** Chunks around each player searched for generated villages. */
    private static final int SCAN_CHUNKS = 10;
    /** Ticks between searches; a new village or a finished wake-up also searches at once. */
    private static final int SCAN_INTERVAL = 20;
    /** How long a token hung by the takeover keeps its registration chat to itself. */
    private static final long QUIET_TICKS = 1200L;

    /** Setups that may fail, over every world load, before a village is left vanilla for good. */
    private static final int MAX_FAILURES = 2;

    /** Set while a generated village founds itself, so founding knows to hand over. */
    private static boolean foundingAutomatically;

    /**
     * What loaded since the last tick, per dimension, looked at on the next
     * tick: finding a villager's village can load another chunk, which must not
     * happen while a chunk is being loaded.
     *
     * <p>The villagers themselves are kept, not their ids. A brand-new chunk's
     * villagers are in the world at once but can stay hidden from a lookup by
     * id for a few ticks, until the game finishes opening the chunk; looked up
     * by id they came back missing, were given up on and stayed vanilla.
     */
    private static final Map<ResourceKey<Level>, Arrivals> ARRIVALS = new HashMap<>();

    /** Dimensions to search for villages to wake on the next tick, not at the next interval. */
    private static final Set<ResourceKey<Level>> SCAN_SOON = new HashSet<>();

    private static final class Arrivals {
        /** Chunks that just loaded for the very first time. */
        final LongSet newChunks = new LongOpenHashSet();
        /** Start chunks of the villages whose middle is among them. */
        final LongSet newVillageStarts = new LongOpenHashSet();
        /** Vanilla villagers that just appeared other than from disk, with their chunk. */
        final Map<Villager, Long> villagers = new LinkedHashMap<>();
        /** Waiting settlers that just appeared, or wait for their village to be built. */
        final List<NpcEntity> settlers = new ArrayList<>();

        boolean isEmpty() {
            return newVillageStarts.isEmpty() && villagers.isEmpty() && settlers.isEmpty();
        }
    }

    private VanillaVillageTakeover() {}

    /** The generated village around {@code pos}, or null when there is none. */
    @Nullable
    public static StructureStart findVillage(ServerLevel level, BlockPos pos) {
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (StructureStart start : level.structureManager().startsForStructure(new ChunkPos(pos),
                structure -> structures.wrapAsHolder(structure).is(StructureTags.VILLAGE))) {
            if (start.isValid() && start.getBoundingBox().inflatedBy(4).isInside(pos)) return start;
        }
        return null;
    }

    // =========================================================================
    // Brand-new chunks
    // =========================================================================

    /**
     * What arrived since the last tick.
     *
     * <p>A villager counts as freshly generated only when it appeared together
     * with its chunk's very first load: the generator's villagers are added in
     * the same step that announces the new chunk. A cured or bred villager
     * appears later, in a chunk that was loaded already, and is left alone.
     */
    private static void processArrivals(ServerLevel level) {
        Arrivals arrived = ARRIVALS.get(level.dimension());
        if (arrived == null || arrived.isEmpty()) {
            if (arrived != null) arrived.newChunks.clear();
            return;
        }
        ARRIVALS.remove(level.dimension());
        TakenVillages taken = TakenVillages.get(level);
        boolean on = Config.generatedVillagesBecomeDynamic();
        if (on) {
            for (long packed : arrived.newVillageStarts) {
                ChunkPos start = new ChunkPos(packed);
                if (!taken.isOld(start)) taken.markFresh(start);
            }
            // A village that just appeared near a player starts waking without waiting for the next search.
            if (!arrived.newVillageStarts.isEmpty()) SCAN_SOON.add(level.dimension());
        }

        Map<ChunkPos, Boolean> swapping = new HashMap<>();
        Map<ChunkPos, int[]> outcomes = new LinkedHashMap<>();
        if (on) {
            for (Map.Entry<Villager, Long> entry : arrived.villagers.entrySet()) {
                if (!arrived.newChunks.contains(entry.getValue().longValue())) continue;
                Villager villager = entry.getKey();
                if (villager.isRemoved() || villager.getTags().contains(VanillaVillagerSwap.RESTORED_TAG)) continue;
                StructureStart start = findVillage(level, villager.blockPosition());
                if (start == null) continue;
                ChunkPos village = start.getChunkPos();
                if (!swapping.computeIfAbsent(village, key -> judge(level, taken, start))) continue;
                int[] counts = outcomes.computeIfAbsent(village, key -> new int[2]);
                if (taken.settlers(village) >= taken.villageSize(village, level.random)) {
                    VanillaVillagerSwap.dropQuietly(villager);
                    counts[1]++;
                } else if (VanillaVillagerSwap.makeWaitingSettler(level, villager, village)) {
                    // On a village already woken, the new settler joins it next tick.
                    taken.addSettler(village);
                    counts[0]++;
                }
            }
        }
        for (Map.Entry<ChunkPos, int[]> outcome : outcomes.entrySet()) {
            ChunkPos village = outcome.getKey();
            DynamicVillages.LOGGER.info("[Takeover] Generated village {}: {} new villager(s) became settlers, "
                            + "{} past its size removed; settlers {}/{}.", village, outcome.getValue()[0],
                    outcome.getValue()[1], taken.settlers(village), taken.villageSize(village, level.random));
        }

        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        for (NpcEntity settler : arrived.settlers) {
            if (settler.isRemoved() || !settler.isWaitingSettler()) continue;
            TakenVillages.Area area = taken.areaAt(settler.blockPosition());
            if (area != null) {
                VillageManager village = registry.get(area.villageId());
                if (village != null && village.isAwaitingInitialize()) {
                    // Its village was asleep at world load and is not built yet: ask again next tick.
                    arrivals(level).settlers.add(settler);
                } else if (village != null && village.getGraph() != null) {
                    VanillaVillagerSwap.adopt(level, village, settler, area.jobs());
                } else {
                    // The village that took this ground is gone.
                    VanillaVillagerSwap.restoreVanilla(level, settler);
                }
                continue;
            }
            // Off taken ground it keeps waiting for its village to wake, unless that will never happen.
            ChunkPos village = VanillaVillagerSwap.settlerVillage(settler);
            if (!on || (village != null && taken.isLeftAlone(village))) {
                VanillaVillagerSwap.restoreVanilla(level, settler);
            }
        }
    }

    /**
     * Whether the generator's villagers of this village become waiting
     * settlers: only when it is brand new, not an abandoned village, and not
     * in or beside a Dynamic Village already. Decided here, when its first
     * villagers appear, where possible, so a village that would only be left
     * alone later never has its villagers swapped at all.
     */
    private static boolean judge(ServerLevel level, TakenVillages taken, StructureStart start) {
        ChunkPos village = start.getChunkPos();
        // Taken: anyone more is past its size and removed. Left alone: vanilla.
        if (taken.isDone(village)) return !taken.isLeftAlone(village);
        if (taken.isOld(village)) return false;
        if (!taken.isFresh(village)) {
            // The villager's chunk is new, but the village's middle is not: a
            // village a player saw part of before, or that was there before
            // the mod. Its far side loading now must not change it.
            if (!neverFullyLoaded(level, village)) {
                taken.markOld(village);
                DynamicVillages.LOGGER.info("[Takeover] Generated village {}: its middle was loaded before, "
                        + "so it stays vanilla.", village);
                return false;
            }
            taken.markFresh(village);
        }
        if (VanillaHouses.isAbandoned(start)) {
            leaveAloneForGood(level, start, "it is an abandoned (zombie) village");
            return false;
        }
        String reason = tooCloseReason(level, middleOf(start));
        if (reason != null) {
            leaveAloneForGood(level, start, reason);
            return false;
        }
        return true;
    }

    /**
     * True when the chunk has never been a full chunk: not loaded now (a
     * village marked fresh would already be known) and not saved to disk as a
     * full chunk. A chunk the generator only got partway through counts as never loaded.
     */
    private static boolean neverFullyLoaded(ServerLevel level, ChunkPos chunk) {
        if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null) return false;
        try {
            Optional<CompoundTag> saved = level.getChunkSource().chunkMap.read(chunk).join();
            return saved.isEmpty() || ChunkSerializer.getChunkTypeFromTag(saved.get()) != ChunkType.LEVELCHUNK;
        } catch (RuntimeException failure) {
            // Unknown: treat it as loaded before, so the village stays vanilla.
            DynamicVillages.LOGGER.warn("[Takeover] Could not read chunk {} to see whether it was loaded before; "
                    + "its village stays vanilla.", chunk, failure);
            return false;
        }
    }

    // =========================================================================
    // Finding villages to wake
    // =========================================================================

    /**
     * Looks around every player for a brand-new generated village not tried
     * yet, and starts waking the nearest. One at a time per dimension. With the
     * takeover switched off, the villages found are left vanilla instead.
     */
    private static void scanForVillages(ServerLevel level) {
        // Test scenarios build their own villages; a generated one joining in would spoil them.
        if (level.players().isEmpty() || TestKit.headless()) return;
        boolean on = Config.generatedVillagesBecomeDynamic();
        if (on && WAKING.containsKey(level.dimension())) return;
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        TakenVillages taken = TakenVillages.get(level);
        StructureStart nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            ChunkPos at = player.chunkPosition();
            for (int dx = -SCAN_CHUNKS; dx <= SCAN_CHUNKS; dx++) {
                for (int dz = -SCAN_CHUNKS; dz <= SCAN_CHUNKS; dz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(at.x + dx, at.z + dz);
                    // A village's start is stored in the chunk at its middle only.
                    if (chunk == null || chunk.getAllStarts().isEmpty()) continue;
                    for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
                        StructureStart start = entry.getValue();
                        if (!start.isValid() || !taken.isFresh(start.getChunkPos())
                                || taken.isHandled(start.getChunkPos())) continue;
                        if (!structures.wrapAsHolder(entry.getKey()).is(StructureTags.VILLAGE)) continue;
                        if (!on) {
                            leaveAloneForGood(level, start, "generated villages are switched off in the config");
                            continue;
                        }
                        int distance = dx * dx + dz * dz;
                        if (distance < nearestDistance) {
                            nearest = start;
                            nearestDistance = distance;
                        }
                    }
                }
            }
        }
        if (nearest != null) beginWakeUp(level, nearest);
    }

    /** The village's first piece is its meeting point, with the bell. */
    private static BlockPos middleOf(StructureStart start) {
        return start.getPieces().isEmpty()
                ? start.getBoundingBox().getCenter()
                : start.getPieces().get(0).getBoundingBox().getCenter();
    }

    // =========================================================================
    // Waking up in stages
    // =========================================================================

    /** Longest a wake-up waits for land before going on with whatever has loaded. */
    private static final long LOAD_TIMEOUT_TICKS = 1200L;
    /** Houses nearest the middle tried as the Town Hall. */
    private static final int MAX_HALL_CANDIDATES = 4;
    /** Chunks each way of the quick map drawn from each Town Hall candidate's door. */
    private static final int HALL_CHECK_RADIUS = 2;
    /** Time one tick gives a candidate's quick map. */
    private static final long HALL_CHECK_SLICE_NANOS = 3_000_000L;
    /** Map steps between clock checks. */
    private static final int HALL_CHECK_STEPS = 128;
    /** Block changes remembered while the map is drawn, to be looked at again once it stands. */
    private static final int MAX_CHANGES = 512;
    /** Chunks let go per tick by wake-ups that did not found a village. */
    private static final int RELEASE_PER_TICK = 8;

    /**
     * LOAD_VILLAGE: the village's own chunks and a ring past them, so every
     * house is seen and every Town Hall candidate can be checked. PICK_HALL:
     * a quick map from each candidate's door, one per few ticks, to find the
     * hall that leads to the rest of the village. LOAD_LAND: the square the
     * village will claim. MAP: its map, a slice per tick.
     */
    private enum Stage { LOAD_VILLAGE, PICK_HALL, LOAD_LAND, MAP }

    /**
     * A generated village waking up, one per dimension at a time. Founding a
     * village in one go is a freeze of seconds while a player is right there:
     * loading its land, mapping it and learning its trees all at once. Here the
     * land loads in the background while the game runs, the map is drawn a
     * slice per tick, and the village is founded on that finished map,
     * learning its trees and crops in slices afterwards.
     */
    private static final class WakeUp {
        final StructureStart start;
        final BlockPos middle;
        final long startedAt;
        /** Every chunk this wake-up holds loaded; handed to the village, or let go. */
        final ChunkHold hold = new ChunkHold();
        Stage stage = Stage.LOAD_VILLAGE;
        long villageLoadedAt;
        long hallPickedAt;
        long landLoadedAt;
        List<House> houses = List.of();
        List<House> candidates = List.of();
        int nextCandidate;
        @Nullable NavGraph.BuildTask check;
        @Nullable House bestHall;
        int bestReached = -1;
        int bestNearby;
        @Nullable House hall;
        @Nullable SlicedMapBuild build;
        /** Blocks broken or placed in the land while its map was drawn. */
        final Set<BlockPos> changed = new LinkedHashSet<>();

        WakeUp(StructureStart start, BlockPos middle, long startedAt) {
            this.start = start;
            this.middle = middle;
            this.startedAt = startedAt;
        }
    }

    private static final Map<ResourceKey<Level>, WakeUp> WAKING = new HashMap<>();

    /** Chunks of wake-ups that founded nothing, let go a few per tick. */
    private static final Map<ResourceKey<Level>, List<ChunkHold>> RELEASING = new HashMap<>();

    /**
     * Starts waking one generated village: asks for all of its chunks to load,
     * so its Town Hall is chosen from every house, never from the few that
     * happened to be loaded when a player arrived.
     */
    private static void beginWakeUp(ServerLevel level, StructureStart start) {
        BlockPos middle = middleOf(start);
        if (VanillaHouses.isAbandoned(start)) {
            leaveAloneForGood(level, start, "it is an abandoned (zombie) village");
            return;
        }
        String reason = tooCloseReason(level, middle);
        if (reason != null) {
            leaveAloneForGood(level, start, reason);
            return;
        }
        WakeUp wake = new WakeUp(start, middle, level.getGameTime());
        BoundingBox box = start.getBoundingBox();
        // The ring past the village: a house's floor may reach a little past
        // its piece, and a Town Hall candidate's quick map (and its edge
        // checks) reaches this far from its door.
        wake.hold.hold(level, ChunkHold.square(box.minX() >> 4, box.minZ() >> 4, box.maxX() >> 4, box.maxZ() >> 4,
                HALL_CHECK_RADIUS + 1));
        WAKING.put(level.dimension(), wake);
        DynamicVillages.LOGGER.info("[Takeover] Generated village at {}: waking up, loading its {} chunk(s).",
                middle, wake.hold.size());
    }

    /** One step of the wake-up in progress, if any. */
    private static void tickWakeUp(ServerLevel level) {
        WakeUp wake = WAKING.get(level.dimension());
        if (wake == null) return;
        if (!Config.generatedVillagesBecomeDynamic()) {
            WAKING.remove(level.dimension());
            releaseLater(level, wake.hold);
            leaveAloneForGood(level, wake.start, "generated villages were switched off in the config");
            return;
        }
        switch (wake.stage) {
            case LOAD_VILLAGE -> loadVillage(level, wake);
            case PICK_HALL -> pickHall(level, wake);
            case LOAD_LAND -> loadLand(level, wake);
            case MAP -> {
                NavGraph graph = wake.build.advance(level);
                if (graph != null) finishWakeUp(level, wake, graph);
            }
        }
    }

    private static void loadVillage(ServerLevel level, WakeUp wake) {
        if (!wake.hold.loaded(level, LOAD_TIMEOUT_TICKS)) return;
        wake.villageLoadedAt = level.getGameTime();
        wake.houses = VanillaHouses.survey(level, wake.start);
        List<House> candidates = new ArrayList<>();
        for (House house : wake.houses) {
            if (house.beds() > 0) candidates.add(house);
        }
        if (candidates.isEmpty()) {
            WAKING.remove(level.dimension());
            releaseLater(level, wake.hold);
            failWakeUp(level, wake, "no house can be the Town Hall");
            return;
        }
        candidates.sort(Comparator.comparingDouble(house -> house.door().distSqr(wake.middle)));
        wake.candidates = List.copyOf(candidates.subList(0, Math.min(MAX_HALL_CANDIDATES, candidates.size())));
        wake.stage = Stage.PICK_HALL;
    }

    /**
     * Picks the Town Hall: the bed house nearest the middle whose door leads to
     * the rest of the village. On a hillside the nearest house can sit on a
     * terrace cut off by a drop no villager climbs; founded there, the whole
     * village's map was the hundred tiles around that house, every other
     * house failed and every villager stood still. So a quick map is drawn from
     * each candidate's door in turn, a slice per tick, and the first that
     * reaches at least half the houses around it wins; failing that, the one
     * that reached most.
     */
    private static void pickHall(ServerLevel level, WakeUp wake) {
        House candidate = wake.candidates.get(wake.nextCandidate);
        if (wake.check == null) wake.check = NavGraph.startBuild(level, candidate.door(), HALL_CHECK_RADIUS, 0L);
        long began = System.nanoTime();
        NavGraph quick = null;
        while (quick == null && System.nanoTime() - began < HALL_CHECK_SLICE_NANOS) {
            quick = wake.check.advance(level, HALL_CHECK_STEPS);
        }
        if (quick == null) return;
        wake.check = null;
        wake.nextCandidate++;

        int nearby = 0;
        int reached = 0;
        for (House other : wake.houses) {
            if (other == candidate) continue;
            BlockPos door = other.door();
            if (door.getX() < quick.getBoundsMinX() || door.getX() > quick.getBoundsMaxX()
                    || door.getZ() < quick.getBoundsMinZ() || door.getZ() > quick.getBoundsMaxZ()) continue;
            nearby++;
            if (VanillaHouses.onMap(quick, other)) reached++;
        }
        if (reached > wake.bestReached) {
            wake.bestHall = candidate;
            wake.bestReached = reached;
            wake.bestNearby = nearby;
        }
        boolean goodEnough = nearby > 0 && reached * 2 >= nearby;
        if (!goodEnough && wake.nextCandidate < wake.candidates.size()) return;

        House hall = wake.bestHall != null ? wake.bestHall : wake.candidates.get(0);
        wake.hall = hall;
        wake.hallPickedAt = level.getGameTime();
        VanillaHouses.placeDoors(level, hall, null);
        // The same square the new village will claim, and the ring its map looks into.
        int cx = hall.door().getX() >> 4;
        int cz = hall.door().getZ() >> 4;
        wake.hold.hold(level, ChunkHold.square(cx, cz, cx, cz, GENERATED_VILLAGE_RADIUS + 1));
        wake.stage = Stage.LOAD_LAND;
        DynamicVillages.LOGGER.info("[Takeover] Generated village at {}: Town Hall door at {} (its door reaches {} of "
                        + "the {} house(s) around it; {} candidate(s) tried), loading the land around it.",
                wake.middle, hall.door(), Math.max(0, wake.bestReached), wake.bestNearby, wake.nextCandidate);
    }

    private static void loadLand(ServerLevel level, WakeUp wake) {
        if (!wake.hold.loaded(level, LOAD_TIMEOUT_TICKS)) return;
        int missing = wake.hold.missing(level);
        if (missing > 0) {
            DynamicVillages.LOGGER.warn("[Takeover] Generated village at {}: {} chunk(s) of its land still not loaded "
                    + "after {} ticks; mapping it anyway.", wake.middle, missing, wake.hold.waited(level));
        }
        wake.landLoadedAt = level.getGameTime();
        wake.build = new SlicedMapBuild(level, wake.hall.door(), GENERATED_VILLAGE_RADIUS, 1L);
        wake.stage = Stage.MAP;
    }

    /** Founds the village on its finished map, then hands it the land to let go of later. */
    private static void finishWakeUp(ServerLevel level, WakeUp wake, NavGraph graph) {
        WAKING.remove(level.dimension());
        SCAN_SOON.add(level.dimension());
        TakenVillages taken = TakenVillages.get(level);
        ChunkPos startChunk = wake.start.getChunkPos();
        boolean handedOver = false;
        try {
            // The last word on the Town Hall: its door must lead to at least
            // one other house, or the village would be the hall alone.
            int others = 0;
            int reached = 0;
            for (House house : wake.houses) {
                if (house == wake.hall) continue;
                others++;
                if (VanillaHouses.onMap(graph, house)) reached++;
            }
            if (others > 0 && reached == 0) {
                leaveAloneForGood(level, wake.start, "the Town Hall's door does not lead to any other house");
                return;
            }
            // A player may have founded a village nearby while the map was drawn.
            String reason = tooCloseReason(level, wake.hall.door());
            if (reason != null) {
                leaveAloneForGood(level, wake.start, reason);
                return;
            }

            long foundBegan = System.nanoTime();
            ItemFrame frame;
            VillageManager founded;
            foundingAutomatically = true;
            try {
                frame = VanillaHouses.hangToken(level, wake.hall.door(), wake.hall.out(),
                        new ItemStack(ModItems.TOWN_HALL.get()));
                // Nobody hung this token; its founding and registration chat stays quiet.
                if (frame != null) quietFrame(level, frame);
                founded = frame == null ? null : VillageManagerRegistry.get(level.getServer())
                        .createFromTownHall(level, frame, graph, GENERATED_VILLAGE_RADIUS);
            } finally {
                foundingAutomatically = false;
            }
            boolean ok = founded != null && founded.hasValidTownHall(frame.getUUID());
            if (!ok && frame != null) frame.discard();
            if (ok) {
                taken.markHandled(startChunk);
                // Kept loaded until the village has learned its trees, then let go a few per tick.
                founded.takeWakeHold(wake.hold);
                handedOver = true;
                // Blocks changed while the map was drawn may or may not be on it: look again.
                for (BlockPos pos : wake.changed) founded.noteBlockChanged(pos);
            } else {
                failWakeUp(level, wake, "the Town Hall was not accepted");
            }
            DynamicVillages.LOGGER.info("[Takeover] Generated village at {}: {}. Village loaded in {} ticks, "
                            + "Town Hall picked in {} more, land in {} more, map drawn in {} ms over {} ticks, "
                            + "founded in {} ms; {} of {} other house(s) on its map.",
                    wake.middle, ok ? "now a TownTopia village" : "not woken this time",
                    wake.villageLoadedAt - wake.startedAt, wake.hallPickedAt - wake.villageLoadedAt,
                    wake.landLoadedAt - wake.hallPickedAt, wake.build.workMillis(), wake.build.ticks(),
                    (System.nanoTime() - foundBegan) / 1_000_000L, reached, others);
        } finally {
            if (!handedOver) releaseLater(level, wake.hold);
        }
    }

    /**
     * A setup that did not work. Tried again after the next world load, up to
     * {@link #MAX_FAILURES} setups in all; then the village stays vanilla.
     * The caller has taken the wake-up off and let go of its chunks.
     */
    private static void failWakeUp(ServerLevel level, WakeUp wake, String reason) {
        TakenVillages taken = TakenVillages.get(level);
        ChunkPos start = wake.start.getChunkPos();
        int failures = taken.addFailure(start);
        if (failures >= MAX_FAILURES) {
            leaveAloneForGood(level, wake.start, reason + " (" + failures + " tries)");
            return;
        }
        taken.markFailed(start);
        DynamicVillages.LOGGER.info("[Takeover] Generated village at {}: not woken for now, tried again after the "
                + "next world load ({} of {} tries): {}.", wake.middle, failures, MAX_FAILURES, reason);
    }

    /** Why a village at {@code pos} cannot be one of ours: in or beside a Dynamic Village already. */
    @Nullable
    private static String tooCloseReason(ServerLevel level, BlockPos pos) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        if (registry.findVillageAt(level, pos) != null) return "it is inside a village already";
        if (registry.overlapsActiveVillage(level, pos, GENERATED_VILLAGE_RADIUS)) {
            return "it is too close to another village";
        }
        return null;
    }

    /**
     * A generated village that will never be one of ours: it stays vanilla,
     * and its waiting settlers turn back into the vanilla villagers they were.
     * Those not loaded now turn back as they load.
     */
    private static void leaveAloneForGood(ServerLevel level, StructureStart start, String reason) {
        TakenVillages.get(level).markLeftAlone(start.getChunkPos());
        int restored = VanillaVillagerSwap.restoreAll(level,
                AABB.of(start.getBoundingBox().inflatedBy(VILLAGER_SEARCH_MARGIN)), start.getChunkPos());
        DynamicVillages.LOGGER.info("[Takeover] Generated village at {}: stays vanilla, {}; {} waiting villager(s) "
                + "turned back into vanilla villagers.", middleOf(start), reason, restored);
    }

    private static void releaseLater(ServerLevel level, ChunkHold hold) {
        if (!hold.isEmpty()) RELEASING.computeIfAbsent(level.dimension(), key -> new ArrayList<>()).add(hold);
    }

    private static void tickReleases(ServerLevel level) {
        List<ChunkHold> holds = RELEASING.get(level.dimension());
        if (holds == null) return;
        Iterator<ChunkHold> it = holds.iterator();
        if (it.hasNext() && it.next().releaseSome(level, RELEASE_PER_TICK)) it.remove();
        if (holds.isEmpty()) RELEASING.remove(level.dimension());
    }

    /** Keeps the registration chat of a token the takeover hung to itself for a while. */
    private static void quietFrame(ServerLevel level, ItemFrame frame) {
        TokenFeedback.quiet(level, new BoundingBox(frame.blockPosition()).inflatedBy(2), QUIET_TICKS);
    }

    // =========================================================================
    // Setting up the village
    // =========================================================================

    /**
     * Called by founding. When a waking generated village founds itself,
     * schedules the rest of its setup and returns true; that setup sends its own
     * chat, gift and settlers in place of the usual founding ones. Any other
     * founding, including a Town Hall hung by hand in an old vanilla village, is
     * an ordinary one.
     */
    public static boolean begin(ServerLevel level, VillageManager village,
                                BlockPos hallDoor, Direction hallInterior) {
        if (!foundingAutomatically) return false;
        StructureStart start = findVillage(level, hallDoor);
        if (start == null) return false;
        MinecraftServer server = level.getServer();
        // Founding runs inside the Town Hall frame's registration. Hanging more
        // frames and taking the settlers in waits until that has finished.
        server.tell(new TickTask(server.getTickCount(),
                () -> takeOver(level, village, start, hallDoor, hallInterior)));
        return true;
    }

    private static void takeOver(ServerLevel level, VillageManager village, StructureStart start,
                                 BlockPos hallDoor, Direction hallInterior) {
        NavGraph graph = village.getGraph();
        if (graph == null) return;   // the village was taken down in the meantime

        BoundingBox ground = start.getBoundingBox().inflatedBy(VILLAGER_SEARCH_MARGIN);
        TakenVillages taken = TakenVillages.get(level);
        taken.markHandled(start.getChunkPos());

        // What this village is like: its land, how well off it is, its jobs.
        VillageCharacter.Kind kind = VillageCharacter.kindOf(level, start);
        VillageCharacter.Wealth wealth = taken.wealth(start.getChunkPos(), level.random);
        // A farmer and a lumberjack always, a guard sometimes. A Ranch, if the
        // village gets one, comes without a rancher: the player picks one.
        boolean guard = VillageCharacter.rollGuard(level.random);
        Bonus bonus = pickBonus(level, village, start, graph, guard);
        List<Profession> jobs = new ArrayList<>(VillageCharacter.ALWAYS_JOBS);
        if (guard) jobs.add(Profession.GUARD);
        // Recorded before anyone is taken in, so a settler whose chunk loads from now on joins too.
        taken.addArea(ground, village.getVillageId(), jobs);

        List<House> houses = new ArrayList<>(VanillaHouses.survey(level, start));
        // Only houses the village's map reaches: a house up a drop no villager
        // climbs would be a home nobody could get to.
        houses.removeIf(house -> house.touches(hallDoor) || !village.contains(house.door())
                || !VanillaHouses.onMap(graph, house));
        houses.sort(Comparator.comparingDouble(house -> house.door().distSqr(hallDoor)));

        AABB area = AABB.of(ground);
        int size = taken.villageSize(start.getChunkPos(), level.random);

        House storage = pickStorage(houses);
        if (storage != null) houses.remove(storage);
        // Short villages are made up to their size, so beds for all of it.
        List<House> homes = pickHomes(houses, size);

        int doors = 0;
        if (storage != null) doors += VanillaHouses.placeDoors(level, storage, village);
        for (House home : homes) doors += VanillaHouses.placeDoors(level, home, village);

        VanillaVillagerSwap.Setup setup = new VanillaVillagerSwap.Setup(List.copyOf(jobs), kind, wealth);
        VanillaVillagerSwap.Result swap = VanillaVillagerSwap.swap(level, village, area, hallDoor, size, setup);
        int newcomers = makeUpNumbers(level, village, start, size, swap, homes,
                hallDoor.relative(hallInterior), setup);

        // The chest goes in before the Storage token, so the room's first scan finds it.
        List<ItemStack> goods = forVillage(village, VillageCharacter.starterChest(kind, wealth, level.random));
        BlockPos chest = storage != null ? VanillaHouses.chestSpot(level, storage) : null;
        if (chest != null) {
            VillageFounding.stockChest(level, village, chest, VanillaHouses.chestState(storage, chest), goods);
        } else {
            VillageFounding.placeGiftChest(level, village, hallDoor, hallInterior, goods);
        }

        // The chest and any new doors on the map now, not after the usual ten
        // seconds' wait for more changes: the tokens below register as soon as
        // the map is current, and the village is complete when a player arrives.
        village.updateMapNow();

        boolean storageMarked = storage != null && hang(level, village, storage, ModItems.STORAGE_ROOM.get());
        int homesMarked = 0;
        for (House home : homes) {
            if (hang(level, village, home, houseToken(home.beds()))) homesMarked++;
        }
        boolean bonusHung = bonus != null && hangBonus(level, village, bonus);
        int finds = addVanillaChestFinds(level, village, start);

        // Set up quietly: a player walking in simply finds a village.
        DynamicVillages.LOGGER.info("[Takeover] Village {} set up in a generated village ({}, {}): size {}, "
                        + "jobs {}, settlers joined={}, newcomers={}, moved away={}, storage={}, homes hung={}/{}, "
                        + "doors added={}, starter chest {} stack(s), bonus {}, tokens put in its own chests={}.",
                village.getVillageId().toString().substring(0, 8), kind, wealth, size, jobs, swap.joined(),
                newcomers, swap.left(), storageMarked, homesMarked, homes.size(), doors, goods.size(),
                bonus == null ? "none" : bonus.type() + (bonusHung ? "" : " (could not be hung)"), finds);
    }

    // =========================================================================
    // The rare extra: a Ranch or a Guard Post
    // =========================================================================

    /** A rare extra building the village comes with: its token, and where it hangs. */
    private record Bonus(ItemFrameTokenRegistry.TokenType type, BlockPos support, Direction facing) {}

    /**
     * Rolls the village's rare extra: a Ranch on an animal pen it already has,
     * or, for a village with a guard, a Guard Post by its bell. Each is checked
     * the way the village will check it, so no token is hung that would not
     * register; a village with no pen that passes simply gets no Ranch.
     */
    @Nullable
    private static Bonus pickBonus(ServerLevel level, VillageManager village, StructureStart start, NavGraph graph,
                                   boolean hasGuard) {
        if (level.random.nextFloat() < VillageCharacter.RANCH_CHANCE) {
            Bonus ranch = findRanch(level, village, start, graph);
            if (ranch != null) return ranch;
        }
        if (hasGuard && level.random.nextFloat() < VillageCharacter.GUARD_POST_CHANCE) {
            return findGuardPost(level, village, start, graph);
        }
        return null;
    }

    /** A gate of one of the village's animal pens that makes a working Ranch, and the fence beside it. */
    @Nullable
    private static Bonus findRanch(ServerLevel level, VillageManager village, StructureStart start, NavGraph graph) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece pool)
                    || !pool.getElement().toString().contains("animal_pen")) continue;
            BoundingBox box = piece.getBoundingBox().inflatedBy(1);
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        BlockState state = level.getBlockState(at.set(x, y, z));
                        if (!(state.getBlock() instanceof FenceGateBlock)) continue;
                        BlockPos gate = at.immutable();
                        if (!village.contains(gate)) continue;
                        Direction through = state.getValue(FenceGateBlock.FACING);
                        Direction outside = null;
                        for (Direction inside : new Direction[] {through, through.getOpposite()}) {
                            EnclosureScanner.ScanResult scan = EnclosureScanner.scanDetailed(level, gate, inside);
                            if (scan.found() && BuildingAccess.ranch(graph, village.getBellPos(), gate, inside,
                                    scan.tiles()) == null) {
                                outside = inside.getOpposite();
                                break;
                            }
                        }
                        if (outside == null) continue;
                        // The token hangs on the fence beside the gate, on the outside.
                        for (Direction side : new Direction[] {through.getClockWise(), through.getCounterClockWise()}) {
                            BlockPos fence = gate.relative(side);
                            Block fenceBlock = level.getBlockState(fence).getBlock();
                            if (!(fenceBlock instanceof FenceBlock) && !(fenceBlock instanceof WallBlock)) continue;
                            if (!level.getBlockState(fence.relative(outside)).isAir()) continue;
                            return new Bonus(ItemFrameTokenRegistry.TokenType.RANCH, fence, outside);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Most blocks from the bell a Guard Post is looked for. */
    private static final int GUARD_POST_SEARCH = 4;

    /** A free, reachable spot on the ground near the bell, for a Guard Post lying flat. */
    @Nullable
    private static Bonus findGuardPost(ServerLevel level, VillageManager village, StructureStart start,
                                       NavGraph graph) {
        NavNode near = graph.nearestOnFloor(middleOf(start), 8, 8);
        if (near == null) return null;
        BlockPos base = near.feetPos();
        for (int ring = 1; ring <= GUARD_POST_SEARCH; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    NavNode node = graph.nearest(base.offset(dx, 0, dz), 0, 2);
                    if (node == null) continue;
                    BlockPos feet = node.feetPos();
                    if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) continue;
                    if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) continue;
                    if (!level.getEntitiesOfClass(ItemFrame.class, new AABB(feet)).isEmpty()) continue;
                    if (!BuildingAccess.guardPost(graph, village.getBellPos(), feet)) continue;
                    return new Bonus(ItemFrameTokenRegistry.TokenType.GUARD_POST, feet.below(), Direction.UP);
                }
            }
        }
        return null;
    }

    /** Hangs the extra's token where it was found and asks for it to be registered. */
    private static boolean hangBonus(ServerLevel level, VillageManager village, Bonus bonus) {
        ItemStack token = new ItemStack(bonus.type() == ItemFrameTokenRegistry.TokenType.RANCH
                ? ModItems.RANCH.get() : ModItems.GUARD_POST.get());
        if (!VillageTokenBinding.bind(token, village)) return false;
        ItemFrame frame = new ItemFrame(level, bonus.support().relative(bonus.facing()), bonus.facing());
        if (!frame.survives() || !level.addFreshEntity(frame)) return false;
        // Empty first, then the token, as a player would: see VanillaHouses.hangToken.
        frame.setItem(token, false);
        quietFrame(level, frame);
        if (bonus.type() == ItemFrameTokenRegistry.TokenType.GUARD_POST) {
            GuardPostRegistry.get(level).queueFrameCheck(level, frame.blockPosition());
        } else {
            village.routeItemFrameInteract(frame.blockPosition());
        }
        return true;
    }

    // =========================================================================
    // Things to find
    // =========================================================================

    /**
     * Ties to the village what its villagers could not use otherwise (tools,
     * wood, coal, iron, tokens), so they do use it; food and seeds stay plain,
     * as do the emeralds, which are for the player.
     */
    private static List<ItemStack> forVillage(VillageManager village, List<ItemStack> goods) {
        List<ItemStack> kept = new ArrayList<>();
        for (ItemStack stack : goods) {
            // The village's own stores: food is village food, dishes Poor Man's
            // (no cook made them), so villagers may eat them (VillageFood).
            if (VillageItemOwnership.isVillagerFood(stack)
                    || com.alex.test.village.tokens.VillageFood.isDish(stack.getItem())) {
                com.alex.test.village.tokens.VillageFood.markVillagerMade(stack);
                if (VillageTokenBinding.bind(stack, village)) kept.add(stack);
                continue;
            }
            // All gear in the chests is the village's own, wooden tools too, so it carries
            // no player penalty (PlayerGear); only bone meal and bones stay plain.
            boolean usable = VillageItemOwnership.isFarmSupply(stack);
            if (stack.is(Items.EMERALD) || (usable && !ItemFrameTokenRegistry.isToken(stack))) {
                kept.add(stack);
            } else if (VillageTokenBinding.bind(stack, village)) {
                kept.add(stack);
            }
        }
        return kept;
    }

    /**
     * Now and then one of the village's own vanilla loot chests (the smith's,
     * the temple's, a house's) holds one of our tokens as well, tied to this
     * village. The chest's vanilla loot is rolled now, as it would have been
     * when first opened, and the token goes in a free slot. Never the Mine.
     *
     * @return how many tokens went in
     */
    private static int addVanillaChestFinds(ServerLevel level, VillageManager village, StructureStart start) {
        BoundingBox box = start.getBoundingBox();
        int finds = 0;
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity blockEntity : List.copyOf(chunk.getBlockEntities().values())) {
                    if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) continue;
                    if (!box.isInside(blockEntity.getBlockPos())) continue;
                    ResourceKey<LootTable> table = container.getLootTable();
                    if (table == null || !table.location().getPath().startsWith("chests/village/")) continue;
                    if (level.random.nextFloat() >= VillageCharacter.VANILLA_CHEST_FIND_CHANCE) continue;
                    ItemStack find = VillageCharacter.findableToken(level.random);
                    if (!VillageTokenBinding.bind(find, village)) continue;
                    container.unpackLootTable(null);
                    List<Integer> free = new ArrayList<>();
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        if (container.getItem(slot).isEmpty()) free.add(slot);
                    }
                    if (free.isEmpty()) continue;
                    container.setItem(free.get(level.random.nextInt(free.size())), find);
                    container.setChanged();
                    finds++;
                }
            }
        }
        return finds;
    }

    /**
     * Brings a village that came out of generation short up to its size, with
     * newcomers standing in its houses, and closes it to further settlers.
     * Settlers made but not loaded yet still come, so they are counted as here;
     * ones that died while waiting are not.
     *
     * @return how many newcomers were added
     */
    private static int makeUpNumbers(ServerLevel level, VillageManager village, StructureStart start, int size,
                                     VanillaVillagerSwap.Result swap, List<House> homes, BlockPos hallInside,
                                     VanillaVillagerSwap.Setup setup) {
        TakenVillages taken = TakenVillages.get(level);
        int stillComing = Math.max(0, taken.settlers(start.getChunkPos()) - swap.joined() - swap.left());
        int missing = size - swap.joined() - stillComing;
        taken.markFull(start.getChunkPos(), size);

        List<BlockPos> spots = new ArrayList<>();
        for (House home : homes) spots.addAll(home.tiles());
        int added = 0;
        for (int i = 0; i < missing; i++) {
            BlockPos at = spots.isEmpty() ? hallInside : spots.get(level.random.nextInt(spots.size()));
            if (VanillaVillagerSwap.addNewcomer(level, village, at, setup)) added++;
        }
        return added;
    }

    /** A room without beds if there is one, so no beds go to waste; else the nearest house. */
    @Nullable
    private static House pickStorage(List<House> houses) {
        for (House house : houses) {
            if (house.beds() == 0) return house;
        }
        // Keep at least one house for sleeping.
        return houses.size() > 1 ? houses.get(0) : null;
    }

    /** Nearest houses first, until every villager has a bed and there is one to spare. */
    private static List<House> pickHomes(List<House> houses, int sleepers) {
        List<House> homes = new ArrayList<>();
        int beds = 0;
        for (House house : houses) {
            if (house.beds() == 0) continue;
            if (homes.size() >= MAX_HOMES || beds > sleepers) break;
            homes.add(house);
            beds += house.beds();
        }
        return homes;
    }

    private static Item houseToken(int beds) {
        if (beds <= TWO_BED_MAX) return ModItems.TWO_BED_HOUSE.get();
        if (beds <= FOUR_BED_MAX) return ModItems.FOUR_BED_HOUSE.get();
        return ModItems.SIX_BED_HOUSE.get();
    }

    /** Hangs a village token by the house's door and asks the village to register it. */
    private static boolean hang(ServerLevel level, VillageManager village, House house, Item item) {
        ItemStack token = new ItemStack(item);
        // Tied to this village: taken out of the frame, every other village refuses it.
        if (!VillageTokenBinding.bind(token, village)) return false;
        ItemFrame frame = VanillaHouses.hangToken(level, house.door(), house.out(), token);
        if (frame == null) return false;
        quietFrame(level, frame);
        // Checked once the new doors are on the village's paths.
        village.routeItemFrameInteract(frame.blockPosition());
        return true;
    }

    // =========================================================================
    // Events
    // =========================================================================

    public static final class Events {

        private Events() {}

        /** Arrivals first, so a village marked brand new this tick can be found by the search. */
        @SubscribeEvent
        public static void onLevelTick(LevelTickEvent.Post event) {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            if (com.alex.test.village.stuckwatch.StuckWatch.worldHeld(level)) return;
            processArrivals(level);
            tickReleases(level);
            tickWakeUp(level);
            if (level.getGameTime() % SCAN_INTERVAL == 0 || SCAN_SOON.remove(level.dimension())) {
                scanForVillages(level);
            }
        }

        /** A chunk loading for the very first time, and a village middle in it, are noted. */
        @SubscribeEvent
        public static void onChunkLoad(ChunkEvent.Load event) {
            if (!event.isNewChunk() || !(event.getLevel() instanceof ServerLevel level)) return;
            Arrivals arrivals = arrivals(level);
            arrivals.newChunks.add(event.getChunk().getPos().toLong());
            // Read off the chunk itself; nothing in the level is touched while it loads.
            Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Map.Entry<Structure, StructureStart> entry : event.getChunk().getAllStarts().entrySet()) {
                if (entry.getValue().isValid()
                        && structures.wrapAsHolder(entry.getKey()).is(StructureTags.VILLAGE)) {
                    arrivals.newVillageStarts.add(entry.getValue().getChunkPos().toLong());
                }
            }
        }

        /** Vanilla villagers not loaded from disk, and waiting settlers, are noted. */
        @SubscribeEvent
        public static void onEntityJoin(EntityJoinLevelEvent event) {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            Entity entity = event.getEntity();
            if (entity.getType() == EntityType.VILLAGER && entity instanceof Villager villager) {
                if (event.loadedFromDisk() || villager.getTags().contains(VanillaVillagerSwap.RESTORED_TAG)) return;
                arrivals(level).villagers.put(villager, villager.chunkPosition().toLong());
            } else if (entity instanceof NpcEntity npc && npc.isWaitingSettler()) {
                arrivals(level).settlers.add(npc);
            }
        }

        /** A waiting settler that died will not be coming: its village may be made up to size with a newcomer. */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onLivingDeath(LivingDeathEvent event) {
            if (event.isCanceled()) return;
            if (!(event.getEntity() instanceof NpcEntity npc) || !npc.isWaitingSettler()) return;
            if (!(npc.level() instanceof ServerLevel level)) return;
            ChunkPos village = VanillaVillagerSwap.settlerVillage(npc);
            if (village != null) TakenVillages.get(level).removeSettler(village);
        }

        @SubscribeEvent
        public static void onBlockBreak(BlockEvent.BreakEvent event) {
            noteChange(event.getLevel(), event.getPos());
        }

        @SubscribeEvent
        public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
            noteChange(event.getLevel(), event.getPos());
        }

        /**
         * A block broken or placed in land whose map is being drawn: the map
         * may already have passed that spot, so the village looks at it again
         * once it stands. No village exists yet to hear about it itself.
         */
        private static void noteChange(LevelAccessor levelAccessor, BlockPos pos) {
            if (!(levelAccessor instanceof ServerLevel level)) return;
            WakeUp wake = WAKING.get(level.dimension());
            if (wake == null || wake.stage != Stage.MAP || wake.hall == null
                    || wake.changed.size() >= MAX_CHANGES) return;
            int cx = wake.hall.door().getX() >> 4;
            int cz = wake.hall.door().getZ() >> 4;
            if (Math.abs((pos.getX() >> 4) - cx) > GENERATED_VILLAGE_RADIUS
                    || Math.abs((pos.getZ() >> 4) - cz) > GENERATED_VILLAGE_RADIUS) return;
            wake.changed.add(pos.immutable());
        }

        /** A world closing drops its unfinished wake-up and arrivals; they start over next load. */
        @SubscribeEvent
        public static void onLevelUnload(LevelEvent.Unload event) {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            // The world is closing; its chunk tickets go with it.
            WAKING.remove(level.dimension());
            ARRIVALS.remove(level.dimension());
            RELEASING.remove(level.dimension());
            SCAN_SOON.remove(level.dimension());
            TokenFeedback.clearQuiet(level);
        }

        private static Arrivals arrivals(ServerLevel level) {
            return VanillaVillageTakeover.arrivals(level);
        }
    }

    private static Arrivals arrivals(ServerLevel level) {
        return ARRIVALS.computeIfAbsent(level.dimension(), key -> new Arrivals());
    }
}
