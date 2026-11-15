package com.alex.test.village.villagers.farmer;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.VillagerPause;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import com.alex.test.village.villagers.farmer.crops.CropHarvestStrategy;
import com.alex.test.village.villagers.farmer.crops.CropMaturity;
import com.alex.test.village.villagers.farmer.crops.CropPlantingStrategy;
import com.alex.test.village.villagers.farmer.crops.HarvestTool;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Per-village authority for farm discovery, planning and target ownership.
 *
 * <p>Discovered farms, their memory and every in-progress job are saved with
 * the village ({@link #saveState}/{@link #restoreState}), so a reload continues
 * exactly where the farmers left off. Tile classifications are not saved; they
 * are re-read from the world. No chunk is force-loaded and no worker scans the world.
 *
 * <p>This class finds farms (chunk scans and clustering into {@link FarmSite}s),
 * plans jobs, and owns reservations and assignments. Reading and classifying
 * the tiles of one site is {@link FarmSiteSurvey}'s job; the pure geometry
 * rules live in {@link FarmerPlanning}.
 */
public final class FarmerManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int SCAN_INTERVAL_TICKS = 50;
    public static final int VALIDATION_INTERVAL_TICKS = 100;
    /** Minimum gap between two dirty-triggered surveys of the same site. */
    private static final int DIRTY_REVALIDATION_COOLDOWN_TICKS = 20;
    public static final int RESERVATION_TICKS = 20 * 30;
    public static final int UNREACHABLE_RETRY_TICKS = 20 * 60;
    public static final int CROP_SAMPLE_SIZE = 4;
    private static final int SUPPLY_RECHECK_TICKS = 100;
    /** How long a farmer that banked fetched planting items unplanted waits before fetching them again. */
    private static final int REFETCH_BLOCK_TICKS = 20 * 60 * 5;
    /**
     * Blocks read down from one above the top motion-blocking block. 12 still
     * reaches farmland under a roof sitting 10 blocks above it (greenhouses).
     */
    private static final int SURFACE_SCAN_DEPTH = 12;

    /** Water sources kept per scanned column, from the top down; see advanceChunkScan. */
    private static final int MAX_WATER_LAYERS = 2;
    private static final int SCAN_COLUMNS_PER_TICK = 2;
    private static final boolean DEBUG = Boolean.getBoolean("dynamicvillages.debug.farms");

    /** Saved by name: safe to add to. */
    public enum JobType {
        HARVEST,
        HARVEST_FRUIT,
        HARVEST_CANE,
        RETILL,
        PLANT,
        PLANT_CANE,
        TILL,
        FETCH_SUPPLIES,
        /** One bone meal on a growing crop, pod or bush. */
        BONEMEAL,
        /** Fill a composter with the carried item, and empty it once ready. */
        COMPOST,
        /** Bone meal (or bones to make it from) from storage. */
        FETCH_BONE_MEAL,
        /** Surplus compostables from storage, for the composter in {@code fruitSpace}. */
        FETCH_COMPOST,
        HARVEST_POD,
        PLANT_POD,
        PICK_BERRIES,
        PLANT_BUSH,
        /** Pick a glow berry vine segment; vines are never planted. */
        PICK_GLOW_BERRIES;

        public boolean isFetch() {
            return this == FETCH_SUPPLIES || this == FETCH_BONE_MEAL || this == FETCH_COMPOST;
        }

        /** Work on cocoa or berries, planned by {@link FarmerPerennials}. */
        public boolean isPerennial() {
            return this == HARVEST_POD || this == PLANT_POD || this == PICK_BERRIES || this == PLANT_BUSH
                    || this == PICK_GLOW_BERRIES;
        }

        /** Jobs that take a crop: everything the Harvesting switch turns off. */
        public boolean isHarvest() {
            return this == HARVEST || this == HARVEST_FRUIT || this == HARVEST_CANE
                    || this == HARVEST_POD || this == PICK_BERRIES || this == PICK_GLOW_BERRIES;
        }

        /** Jobs a farmer with no field work (Planting, Harvesting and Tilling all off) still does. */
        public boolean isBoneMealWork() {
            return this == BONEMEAL || this == COMPOST || this == FETCH_BONE_MEAL || this == FETCH_COMPOST;
        }
    }

    /**
     * {@code fruitSpace} is a second reserved cell: the melon/pumpkin slot of
     * a stem planting, or the composter a {@code FETCH_COMPOST} trip is for.
     */
    public record Job(UUID farmId, JobType type, BlockPos target,
                      @Nullable BlockPos interactionPos,
                      @Nullable BlockPos fruitSpace,
                      @Nullable Item plantingItem,
                      int supplyAmount, boolean ordinaryFieldJob,
                      long expiresAt) {}

    /** Saved by ordinal: add new types at the end. */
    private enum ObservationType {
        FARMLAND, CROP, STEM, FRUIT, CANE, WATER, PLAUSIBLE_SOIL, OPEN_WATER, COCOA, BERRY, GLOW_BERRY
    }

    private record Observation(BlockPos pos, ObservationType type, long seenAt) {}
    private record Reservation(UUID farmerId, long expiresAt) {}
    private record ChunkKey(int x, int z) {}
    private record AgriculturalComponents(
            List<Set<BlockPos>> blocks, List<Set<FarmerPlanning.Point>> points) {}

    public record FarmSnapshot(
            UUID id,
            Set<BlockPos> waterSources,
            Set<BlockPos> hydrationCoverage,
            Set<BlockPos> tillable,
            Set<BlockPos> tilled,
            Set<BlockPos> planted,
            Set<BlockPos> mature,
            Set<BlockPos> blocked,
            Map<BlockPos, BlockPos> fruitSpaces,
            @Nullable UUID assignedFarmer,
            @Nullable Job activeJob,
            long lastScan,
            long lastValidation,
            long lastActivity) {}

    private final Map<Long, Observation> observations = new LinkedHashMap<>();
    private final Map<UUID, FarmSite> farms = new LinkedHashMap<>();
    private final Set<UUID> dirtySites = new LinkedHashSet<>();
    private final Map<Long, Reservation> reservations = new HashMap<>();
    private final Map<UUID, Job> assignments = new HashMap<>();
    private final Map<UUID, UUID> preferredFarm = new HashMap<>();
    /** The cocoa or berry patch each farmer last worked; kept apart from its field. Not saved. */
    private final Map<UUID, UUID> preferredPatch = new HashMap<>();
    /**
     * Farmers whose latest work was a patch, not their field. Between jobs
     * they count as working the patch: counted on the field they would go
     * back to, they made it look crowded and sent the rest to the bushes too.
     */
    private final Set<UUID> lastOnPatch = new HashSet<>();
    private final Map<UUID, Restoration> recentHarvests = new HashMap<>();
    private final Map<UUID, Long> nextSupplyCheck = new HashMap<>();
    /**
     * Planting items each farmer fetched and has planted none of since.
     * Banking one starts a wait in {@link #refetchBlockedUntil}. Not saved.
     */
    private final Map<UUID, Set<Item>> fetchedUnplanted = new HashMap<>();
    /** Per farmer, planting items it may not fetch again until this game time. Not saved. */
    private final Map<UUID, Map<Item, Long>> refetchBlockedUntil = new HashMap<>();
    private final Map<Long, Integer> failures = new HashMap<>();
    private final Map<Long, Long> retryAfter = new HashMap<>();
    private final Map<Item, Integer> supplyClaims = new HashMap<>();
    private final List<ChunkKey> scanChunks = new ArrayList<>();
    private ChunkScan chunkScan;
    private int scanCursor;
    /**
     * Farmable soil around each water source, keyed by the water's
     * {@link BlockPos#asLong()}: {@link #plausibleFarmSoil}'s answer for that
     * water, empty answers included. A site rebuild used to ask the world
     * about every water observation again (22k of them, up to 80 block reads
     * and a buildings walk each) although almost none had changed since the
     * last rebuild. Entries are dropped when a chunk scan sees a change in
     * or next to their chunk, when a block change is reported within the
     * 9x9 footprint, when the buildings change, and all at once every
     * {@link #MEMO_FULL_RESET_TICKS} as a safety net for changes nobody
     * reports (leaf decay, endermen, pistons). Stored lists are immutable.
     */
    private final Map<Long, List<BlockPos>> soilMemo = new HashMap<>();
    /**
     * Memo keys per chunk of the water block, so a scanned chunk's entries
     * can be dropped without walking the memo. A key may be listed twice
     * when it was dropped by a block change and memoised again; the full
     * clear below bounds that.
     */
    private final Map<ChunkKey, LongArrayList> soilMemoByChunk = new HashMap<>();
    /** {@link #buildingsSignature} at the last rebuild; a different one empties the memo. */
    private long soilMemoBuildingsSignature = Long.MIN_VALUE;
    /** When the memo was last emptied; {@code Long.MIN_VALUE} until the first rebuild. */
    private long lastMemoFullClearAt = Long.MIN_VALUE;
    /** Ten game minutes: the longest an unreported block change can hide behind a memo entry. */
    private static final int MEMO_FULL_RESET_TICKS = 12000;
    /**
     * Next tick each farmer may try seed crafting. Lives here, not on the
     * activity: a new work session is a new activity, and a per-activity
     * timer reset every session, letting a failed craft loop on the chest.
     */
    private final Map<UUID, Long> nextSeedCraft = new HashMap<>();
    /** Chunks where crops grew outside every known farm; scanned before the round-robin. */
    private final Set<ChunkKey> priorityChunks = new LinkedHashSet<>();
    /** Cocoa and sweet berry patches, which need no water and live outside the field sites. */
    private final FarmerPerennials perennials = new FarmerPerennials();

    /** One farmer's bone meal timer: uses left in this burst, and when the next burst may start. */
    private static final class BoneMealGate {
        int uses;
        long nextBurstAt = Long.MIN_VALUE;
        /** {@link #boneMealWorkDue}'s last answer while a burst waits, so polling stays cheap. */
        long workCheckedAt = Long.MIN_VALUE;
        boolean workFound;
    }

    /** How long a "bone meal is due" answer is reused before the crops are looked at again. */
    private static final int BONE_MEAL_WORK_RECHECK_TICKS = 100;

    private final Map<UUID, BoneMealGate> boneMealGates = new HashMap<>();
    /**
     * Berries each farmer picked and may still plant. Berries are food: a
     * farmer plants only what it picked itself, never berries from storage
     * or its meal reserve. Not saved; after a reload it waits for its next pick.
     */
    private final Map<UUID, Integer> berryPlantAllowance = new HashMap<>();
    /** Next tick each farmer may look for composting work. */
    private final Map<UUID, Long> nextCompostCheck = new HashMap<>();
    /** When a farmer with no hoe goes for one. */
    private final FarmerHoeTrips hoeTrips = new FarmerHoeTrips();
    /**
     * Diary lines from a farmer's last job request: targets it found no spot
     * to stand by, and why composting found nothing. The manager keeps no
     * diary of its own; the farmer takes these right after it asks.
     */
    private final Map<UUID, List<String>> requestNotes = new HashMap<>();
    private static final int COMPOST_CHECK_TICKS = 20 * 30;
    private static final int FOOD_URGENCY_REFRESH_TICKS = 20 * 30;
    private static final int COMPOSTER_REFRESH_TICKS = 20 * 10;
    private double foodUrgency;
    private long foodUrgencyAt = Long.MIN_VALUE;
    private Set<BlockPos> composters = Set.of();
    private long compostersAt = Long.MIN_VALUE;
    private int validationCursor;
    private long nextScanAt = Long.MIN_VALUE;
    private long nextValidationAt = Long.MIN_VALUE;
    private long boundsSignature = Long.MIN_VALUE;
    /** Restored sites still waiting for their first survey; true once surveyed with part of the site unloaded. */
    private final Map<UUID, Boolean> restoredSites = new LinkedHashMap<>();
    /** Restored jobs whose farmer entity has not joined the level yet. */
    private final Set<UUID> restoredAwaitingFarmer = new HashSet<>();
    private int restoredScanCursor = -1;
    private static final class ChunkScan {
        final ChunkKey chunk;
        final long startedAt;
        final Map<Long, Observation> found = new HashMap<>();
        int nextColumn;

        ChunkScan(ChunkKey chunk, long startedAt) {
            this.chunk = chunk;
            this.startedAt = startedAt;
        }
    }
    /** Taken at server shutdown, before this state is cleared and the world is saved. */
    @Nullable private CompoundTag shutdownSnapshot;

    /** Called once per logical-server tick by the owning village. */
    public void tick(ServerLevel level, VillageManager village) {
        if (level == null || level.isClientSide()) return;
        long now = level.getGameTime();
        long startedAt = System.nanoTime();
        stats.reset();
        expireAssignments(village, now);
        long mark = System.nanoTime();
        stats.expireNanos = mark - startedAt;

        rebuildChunkListIfNeeded(village);
        surveyRestoredSites(level, village, now);
        stats.restoreNanos = System.nanoTime() - mark;
        mark = System.nanoTime();

        if (chunkScan != null) advanceChunkScan(level, village);

        if (nextScanAt == Long.MIN_VALUE) nextScanAt = now + SCAN_INTERVAL_TICKS;
        if (chunkScan == null && (now >= nextScanAt || !priorityChunks.isEmpty())) {
            nextScanAt = now + SCAN_INTERVAL_TICKS;
            beginChunkScan(level, now);
            if (chunkScan != null) advanceChunkScan(level, village);
        }
        stats.scanNanos = System.nanoTime() - mark;
        mark = System.nanoTime();

        validateDirtyFarm(level, village, now);
        if (perennialRebuildPending) {
            perennialRebuildPending = false;
            rebuildPerennials(level, village, now);
        }
        perennials.tick(level, village, now);
        stats.dirtyNanos = System.nanoTime() - mark;
        mark = System.nanoTime();

        if (nextValidationAt == Long.MIN_VALUE) nextValidationAt = now + VALIDATION_INTERVAL_TICKS;
        if (now >= nextValidationAt) {
            nextValidationAt = now + VALIDATION_INTERVAL_TICKS;
            validateOneFarm(level, village, now);
        }
        stats.validateNanos = System.nanoTime() - mark;

        long total = System.nanoTime() - startedAt;
        if (total >= SLOW_TICK_NANOS) logSlowTick(total);
    }

    // -------------------------------------------------------------------------
    // Slow-tick report: which part of one tick of farm bookkeeping ate the time.
    // -------------------------------------------------------------------------

    /** A farm tick at or over this is logged with its breakdown; the village's own report uses the same. */
    private static final long SLOW_TICK_NANOS = 15_000_000L;

    /** Where the current tick's time went. Reset at the start of every tick. */
    private static final class TickStats {
        long expireNanos, restoreNanos, scanNanos, dirtyNanos, validateNanos;
        /** Time in {@link #rebuildFarmSites}, surveys included; part of {@code scanNanos}. */
        long rebuildNanos;
        int rebuildObservations, rebuildWater, rebuildSites;
        /** Water blocks whose soil answer came from {@link #soilMemo}, and those computed afresh. */
        int soilMemoHits, soilMemoMisses;
        long surveyNanos;
        int surveys;
        long worstSurveyNanos;
        @Nullable FarmSite worstSurveySite;

        void reset() {
            expireNanos = restoreNanos = scanNanos = dirtyNanos = validateNanos = 0L;
            rebuildNanos = 0L;
            rebuildObservations = rebuildWater = rebuildSites = 0;
            soilMemoHits = soilMemoMisses = 0;
            surveyNanos = 0L;
            surveys = 0;
            worstSurveyNanos = 0L;
            worstSurveySite = null;
        }
    }

    private final TickStats stats = new TickStats();

    /** {@link FarmSiteSurvey#refreshSite}, timed for the slow-tick report. */
    private void surveySite(ServerLevel level, VillageManager village, FarmSite site, long now) {
        long began = System.nanoTime();
        FarmSiteSurvey.refreshSite(level, village, site, now);
        long took = System.nanoTime() - began;
        stats.surveyNanos += took;
        stats.surveys++;
        if (took > stats.worstSurveyNanos) {
            stats.worstSurveyNanos = took;
            stats.worstSurveySite = site;
        }
    }

    private void logSlowTick(long total) {
        StringBuilder line = new StringBuilder();
        appendPhase(line, "expire", stats.expireNanos);
        appendPhase(line, "restoredSurveys", stats.restoreNanos);
        appendPhase(line, "chunkScan", stats.scanNanos);
        if (stats.rebuildNanos > 0L) {
            line.append(" (rebuildAllSites=").append(stats.rebuildNanos / 1_000_000L)
                    .append("ms: ").append(stats.rebuildObservations).append(" observations, ")
                    .append(stats.rebuildWater).append(" water, ")
                    .append(stats.rebuildSites).append(" sites, soilMemo=")
                    .append(stats.soilMemoHits).append('/').append(stats.soilMemoMisses).append(')');
        }
        appendPhase(line, "dirtySurvey", stats.dirtyNanos);
        appendPhase(line, "validateOne", stats.validateNanos);
        line.append(" | ").append(stats.surveys).append(" site survey(s) ")
                .append(stats.surveyNanos / 1_000_000L).append(" ms");
        FarmSite worst = stats.worstSurveySite;
        if (worst != null) {
            line.append(", slowest ").append(stats.worstSurveyNanos / 1_000_000L).append(" ms: ")
                    .append(describeSurveyBox(worst));
        }
        LOGGER.info("[FarmerManager] Slow farm tick {} ms:{} | {} site(s) tracked, {} observations",
                total / 1_000_000L, line, farms.size(), observations.size());
    }

    private static void appendPhase(StringBuilder line, String name, long nanos) {
        if (nanos < 1_000_000L) return;
        line.append(' ').append(name).append('=').append(nanos / 1_000_000L).append("ms");
    }

    /** The site's anchor count and the box {@link FarmSiteSurvey#refreshSite} walks for it. */
    private static String describeSurveyBox(FarmSite site) {
        if (site.anchors.isEmpty()) return "0 anchors";
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : site.anchors) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }
        // refreshSite pads the box by one on every side.
        int sizeX = maxX - minX + 3, sizeY = maxY - minY + 3, sizeZ = maxZ - minZ + 3;
        return site.anchors.size() + " anchors, box " + sizeX + "x" + sizeY + "x" + sizeZ
                + " (" + ((long) sizeX * sizeY * sizeZ) + " cells) at "
                + minX + "," + minY + "," + minZ;
    }

    private void rebuildChunkListIfNeeded(VillageManager village) {
        AABB bounds = village.borderBounds();
        if (bounds == null) return;
        long signature = Double.doubleToLongBits(bounds.minX);
        signature = 31L * signature + Double.doubleToLongBits(bounds.maxX);
        signature = 31L * signature + Double.doubleToLongBits(bounds.minZ);
        signature = 31L * signature + Double.doubleToLongBits(bounds.maxZ);
        signature = 31L * signature + village.getBellPos().asLong();
        signature = 31L * signature + village.getGraphChunkRadius();
        if (signature == boundsSignature) return;

        boundsSignature = signature;
        chunkScan = null;
        scanChunks.clear();
        int minChunkX = ((int) Math.floor(bounds.minX)) >> 4;
        int maxChunkX = ((int) Math.floor(bounds.maxX)) >> 4;
        int minChunkZ = ((int) Math.floor(bounds.minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(bounds.maxZ)) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) scanChunks.add(new ChunkKey(x, z));
        }
        int originChunkX = village.getBellPos().getX() >> 4;
        int originChunkZ = village.getBellPos().getZ() >> 4;
        scanChunks.sort(Comparator
                .comparingInt((ChunkKey chunk) -> {
                    int dx = chunk.x() - originChunkX;
                    int dz = chunk.z() - originChunkZ;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(ChunkKey::x)
                .thenComparingInt(ChunkKey::z));
        scanCursor = restoredScanCursor >= 0 && restoredScanCursor < scanChunks.size()
                ? restoredScanCursor : 0;
        restoredScanCursor = -1;
        observations.entrySet().removeIf(entry -> !inside(bounds, entry.getValue().pos()));
        farms.entrySet().removeIf(entry -> entry.getValue().anchors.stream().noneMatch(pos -> inside(bounds, pos)));
        debug("recalculated {} farm-scan chunks", scanChunks.size());
    }

    private void beginChunkScan(ServerLevel level, long now) {
        if (scanChunks.isEmpty()) return;
        ChunkKey chosen = null;
        Iterator<ChunkKey> priority = priorityChunks.iterator();
        while (chosen == null && priority.hasNext()) {
            ChunkKey candidate = priority.next();
            priority.remove();
            if (level.hasChunkAt(new BlockPos((candidate.x() << 4) + 8, level.getMinBuildHeight(),
                    (candidate.z() << 4) + 8))) chosen = candidate;
        }
        for (int checked = 0; chosen == null && checked < scanChunks.size(); checked++) {
            ChunkKey candidate = scanChunks.get(scanCursor++);
            if (scanCursor >= scanChunks.size()) scanCursor = 0;
            BlockPos probe = new BlockPos((candidate.x() << 4) + 8, level.getMinBuildHeight(),
                    (candidate.z() << 4) + 8);
            if (level.hasChunkAt(probe)) {
                chosen = candidate;
                break;
            }
        }
        if (chosen == null) return;

        chunkScan = new ChunkScan(chosen, now);
    }

    private void advanceChunkScan(ServerLevel level, VillageManager village) {
        ChunkScan scan = chunkScan;
        int minX = scan.chunk.x() << 4;
        int minZ = scan.chunk.z() << 4;
        BlockPos probe = new BlockPos(minX + 8, level.getMinBuildHeight(), minZ + 8);
        if (!level.hasChunkAt(probe)) {
            chunkScan = null;
            return;
        }
        AABB bounds = village.borderBounds();
        int end = Math.min(256, scan.nextColumn + SCAN_COLUMNS_PER_TICK * 16);
        for (; scan.nextColumn < end; scan.nextColumn++) {
            int x = minX + scan.nextColumn / 16;
            int z = minZ + scan.nextColumn % 16;
                if (bounds == null || !insideXZ(bounds, x, z)) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
                int bottom = Math.max(level.getMinBuildHeight(), top - SURFACE_SCAN_DEPTH);
                int waterInColumn = 0;
                for (int y = top; y >= bottom; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    ObservationType type = observationType(level, pos);
                    // Only the top water layers can sit beside a field:
                    // hydration pairs water with soil at the tile's own level
                    // or one below. A lake or sea scanned 12 deep was 146k
                    // water observations, and every site rebuild walked them
                    // all (85 ms a rebuild beside an ocean).
                    if (type == ObservationType.WATER && ++waterInColumn > MAX_WATER_LAYERS) continue;
                    if (type != null) scan.found.put(pos.asLong(),
                            new Observation(pos, type, scan.startedAt));
                }
        }
        if (scan.nextColumn < 256) return;
        chunkScan = null;
        Map<Long, Observation> previous = new HashMap<>();
        observations.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getValue().pos();
            boolean inChunk = pos.getX() >= minX && pos.getX() < minX + 16
                    && pos.getZ() >= minZ && pos.getZ() < minZ + 16;
            if (inChunk) previous.put(entry.getKey(), entry.getValue());
            return inChunk;
        });
        classifyOpenWater(scan.found, observations);
        observations.putAll(scan.found);
        boolean changed = previous.size() != scan.found.size();
        if (!changed) {
            for (Map.Entry<Long, Observation> entry : scan.found.entrySet()) {
                Observation old = previous.get(entry.getKey());
                if (old == null || old.type() != entry.getValue().type()) {
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            // A water block's 9x9 footprint reaches 4 blocks into the next
            // chunk, so answers memoised for the neighbours may have read
            // blocks of this chunk too.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    forgetSoilMemoInChunk(scan.chunk.x() + dx, scan.chunk.z() + dz);
                }
            }
            long began = System.nanoTime();
            rebuildFarmSites(level, village, level.getGameTime());
            stats.rebuildNanos += System.nanoTime() - began;
        }
    }

    @Nullable
    private static ObservationType observationType(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.FARMLAND)) return ObservationType.FARMLAND;
        CropDefinition definition = CropDefinitionRegistry.byCropBlock(state.getBlock());
        if (definition != null) {
            if (definition.planting() instanceof CropPlantingStrategy.OnLog) return ObservationType.COCOA;
            if (definition.planting() instanceof CropPlantingStrategy.OnSoil) return ObservationType.BERRY;
            if (definition.planting() instanceof CropPlantingStrategy.Hanging) return ObservationType.GLOW_BERRY;
            if (definition.maturity() instanceof CropMaturity.AttachedFruit) return ObservationType.STEM;
            if (definition.planting() instanceof CropPlantingStrategy.NearWater) return ObservationType.CANE;
            return ObservationType.CROP;
        }
        if (FarmSiteSurvey.isAnyFruitBlock(state.getBlock())) return ObservationType.FRUIT;
        if (state.getFluidState().is(FluidTags.WATER) && state.getFluidState().isSource()) {
            return ObservationType.WATER;
        }
        return null;
    }

    private void rebuildFarmSites(ServerLevel level, VillageManager village, long now) {
        // The soil memo only knows about changes it was told of. A building
        // change (a new room, a rescan moving functional blocks) is not
        // reported per block, so the buildings are fingerprinted here; and
        // every ten minutes everything is thrown away regardless.
        long buildings = buildingsSignature(village);
        if (buildings != soilMemoBuildingsSignature) {
            soilMemoBuildingsSignature = buildings;
            clearSoilMemo(now);
        } else if (lastMemoFullClearAt == Long.MIN_VALUE
                || now - lastMemoFullClearAt >= MEMO_FULL_RESET_TICKS) {
            clearSoilMemo(now);
        }
        Map<Long, Observation> agricultural = new HashMap<>();
        List<BlockPos> water = new ArrayList<>();
        List<BlockPos> fruits = new ArrayList<>();
        for (Observation observation : observations.values()) {
            if (observation.type() == ObservationType.WATER) water.add(observation.pos());
            else if (observation.type() == ObservationType.OPEN_WATER) continue; // see classifyOpenWater
            else if (observation.type() == ObservationType.FRUIT) fruits.add(observation.pos());
            // Cocoa and berries need no water: they never anchor a field.
            else if (observation.type() == ObservationType.COCOA
                    || observation.type() == ObservationType.BERRY
                    || observation.type() == ObservationType.GLOW_BERRY) continue;
            else agricultural.put(observation.pos().asLong(), observation);
        }
        rebuildPerennials(level, village, now);
        for (BlockPos waterPos : water) {
            for (BlockPos soil : plausibleFarmSoilCached(level, village, waterPos)) {
                BlockPos feet = soil.above();
                agricultural.putIfAbsent(feet.asLong(),
                        new Observation(feet, ObservationType.PLAUSIBLE_SOIL, now));
            }
        }

        List<BlockPos> agriculturalPositions = new ArrayList<>(agricultural.size());
        for (Observation observation : agricultural.values()) agriculturalPositions.add(observation.pos());
        AgriculturalComponents agriculturalComponents = connectedAgriculturalComponents(agriculturalPositions);
        List<Set<BlockPos>> components = agriculturalComponents.blocks();
        
        // Build these reverse lookups once.  The former code asked every farm
        // component whether it contained a tile near each water/fruit block.
        // On a large field that made one chunk scan proportional to every
        // component multiplied by every water source and fruit.  Looking up
        // only the small vanilla-local neighbourhood keeps the same rules
        // without repeatedly walking a whole component.
        List<FarmerPlanning.Point> waterPoints = new ArrayList<>(water.size());
        for (BlockPos waterPos : water) waterPoints.add(FarmSiteSurvey.point(waterPos));
        List<FarmerPlanning.Point> fruitPoints = new ArrayList<>(fruits.size());
        for (BlockPos fruit : fruits) fruitPoints.add(FarmSiteSurvey.point(fruit));
        List<Set<Integer>> waterByComponent = FarmerPlanning.waterComponentIndices(
                agriculturalComponents.points(), waterPoints);
        List<Set<Integer>> fruitsByComponent = FarmerPlanning.fruitComponentIndices(
                agriculturalComponents.points(), fruitPoints);

        Map<UUID, FarmSite> rebuilt = new LinkedHashMap<>();
        Set<UUID> reusedIds = new HashSet<>();
        for (int componentIndex = 0; componentIndex < components.size(); componentIndex++) {
            Set<BlockPos> component = components.get(componentIndex);
            if (component.isEmpty()) continue;
            FarmSite prior = bestOverlappingSite(component);
            BlockPos anchor = component.stream().min(FarmSiteSurvey.POSITION_ORDER).orElseThrow();
            UUID id = prior != null && reusedIds.add(prior.id)
                    ? prior.id : stableFarmId(village.getVillageId(), anchor);
            FarmSite site = new FarmSite(id);
            site.anchors.addAll(component);
            int farmlandAnchors = 0;
            boolean plantedCropAnchor = false;
            for (BlockPos pos : component) {
                Observation observation = agricultural.get(pos.asLong());
                if (observation == null) continue;
                if (observation.type() == ObservationType.FARMLAND) farmlandAnchors++;
                else if (observation.type() == ObservationType.CROP
                        || observation.type() == ObservationType.STEM) {
                    plantedCropAnchor = true;
                }
            }
            site.cropField = FarmerPlanning.isRecognizableCropField(
                    plantedCropAnchor, farmlandAnchors);
            if (prior != null) {
                site.everFarmland.addAll(prior.everFarmland);
                // Carry the plot plan across rediscovery. Without it every
                // chunk scan re-planned fruit slots from scratch, so a slot
                // reserved a moment ago could be released and tilled over.
                site.fruitSpaces.putAll(prior.fruitSpaces);
                site.inferredCrop.putAll(prior.inferredCrop);
                site.lastActivity = prior.lastActivity;
            }
            for (int waterIndex : waterByComponent.get(componentIndex)) site.water.add(water.get(waterIndex));
            for (int fruitIndex : fruitsByComponent.get(componentIndex)) site.fruits.add(fruits.get(fruitIndex));
            site.lastScan = now;
            surveySite(level, village, site, now);
            rebuilt.put(id, site);
        }

        // Preserve active ownership only when the rebuilt farm still contains the target.
        for (Map.Entry<UUID, Job> entry : new ArrayList<>(assignments.entrySet())) {
            // Perennial, compost and bone meal fetch work belongs to no field;
            // its own validation re-checks it against the world.
            if (!farms.containsKey(entry.getValue().farmId())
                    && !rebuilt.containsKey(entry.getValue().farmId())) continue;
            FarmSite site = rebuilt.get(entry.getValue().farmId());
            if (site == null || !site.allWorkPositions().contains(entry.getValue().target())) {
                release(entry.getKey());
            } else {
                site.activeJobs.put(entry.getKey(), entry.getValue());
            }
        }
        farms.clear();
        farms.putAll(rebuilt);
        dirtySites.retainAll(rebuilt.keySet());
        stats.rebuildObservations = observations.size();
        stats.rebuildWater = water.size();
        stats.rebuildSites = rebuilt.size();
        debug("farm discovery now tracks {} site(s) from {} observations", farms.size(), observations.size());
    }

    private void rebuildPerennials(ServerLevel level, VillageManager village, long now) {
        List<BlockPos> pods = new ArrayList<>();
        List<BlockPos> bushes = new ArrayList<>();
        List<BlockPos> vines = new ArrayList<>();
        for (Observation observation : observations.values()) {
            if (observation.type() == ObservationType.COCOA) pods.add(observation.pos());
            else if (observation.type() == ObservationType.BERRY) bushes.add(observation.pos());
            else if (observation.type() == ObservationType.GLOW_BERRY) vines.add(observation.pos());
        }
        AABB bounds = village.borderBounds();
        perennials.rebuild(level, village, pods, bushes, vines,
                pos -> bounds == null || inside(bounds, pos), now);
    }

    /** Pure clustering rule shared with focused unit tests. */
    private static AgriculturalComponents connectedAgriculturalComponents(Collection<BlockPos> positions) {
        List<FarmerPlanning.Point> points = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) points.add(FarmSiteSurvey.point(pos));
        List<Set<FarmerPlanning.Point>> pointComponents = FarmerPlanning.connectedComponents(points);
        List<Set<BlockPos>> blockComponents = new ArrayList<>(pointComponents.size());
        for (Set<FarmerPlanning.Point> source : pointComponents) {
            Set<BlockPos> component = new LinkedHashSet<>();
            for (FarmerPlanning.Point point : source) {
                component.add(new BlockPos(point.x(), point.y(), point.z()));
            }
            blockComponents.add(component);
        }
        return new AgriculturalComponents(blockComponents, pointComponents);
    }

    @Nullable
    private FarmSite bestOverlappingSite(Set<BlockPos> component) {
        FarmSite best = null;
        int bestOverlap = 0;
        for (FarmSite existing : farms.values()) {
            int overlap = 0;
            for (BlockPos pos : component) if (existing.anchors.contains(pos)) overlap++;
            if (overlap > bestOverlap) {
                best = existing;
                bestOverlap = overlap;
            }
        }
        return best;
    }

    /**
     * {@link #plausibleFarmSoil} through {@link #soilMemo}. An answer cut
     * short by an unloaded chunk is handed back empty, as before, but not
     * remembered: the chunk may load before the next rebuild.
     */
    private List<BlockPos> plausibleFarmSoilCached(
            ServerLevel level, VillageManager village, BlockPos water) {
        long key = water.asLong();
        List<BlockPos> known = soilMemo.get(key);
        if (known != null) {
            stats.soilMemoHits++;
            return known;
        }
        stats.soilMemoMisses++;
        List<BlockPos> computed = plausibleFarmSoil(level, village, water);
        if (computed == null) return List.of();
        List<BlockPos> stored = List.copyOf(computed);
        soilMemo.put(key, stored);
        soilMemoByChunk.computeIfAbsent(new ChunkKey(water.getX() >> 4, water.getZ() >> 4),
                chunk -> new LongArrayList()).add(key);
        return stored;
    }

    /** Drops every memoised answer whose water block lies in the chunk. */
    private void forgetSoilMemoInChunk(int chunkX, int chunkZ) {
        LongArrayList keys = soilMemoByChunk.remove(new ChunkKey(chunkX, chunkZ));
        if (keys == null) return;
        for (int i = 0; i < keys.size(); i++) soilMemo.remove(keys.getLong(i));
    }

    /**
     * Drops the memoised answers a block change at {@code pos} can affect:
     * water within the 9x9 footprint sideways, at the soil's level or one
     * below (the soil is read at the water's y, the cover above it at y+1).
     * 243 map lookups, no scan. The chunk index keeps the key; a stale
     * index entry only costs one failed remove later.
     */
    private void forgetSoilMemoAround(BlockPos pos) {
        if (soilMemo.isEmpty()) return;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    soilMemo.remove(BlockPos.asLong(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz));
                }
            }
        }
    }

    private void clearSoilMemo(long now) {
        soilMemo.clear();
        soilMemoByChunk.clear();
        lastMemoFullClearAt = now;
    }

    /**
     * Everything {@link #plausibleFarmSoil} reads from the buildings: which
     * rooms exist, their box (what {@link Building#isNearRoom} tests) and
     * how many functional blocks they have. Per-building values are added,
     * not chained, so the collection's order does not matter.
     */
    private static long buildingsSignature(VillageManager village) {
        long signature = 17L;
        for (Building building : village.getBuildings()) {
            UUID frame = building.getItemFrameId();
            AABB box = building.roomBox();
            long one = frame == null ? 0L : frame.hashCode();
            one = 31L * one + building.getNavMeshBlocks().size();
            one = 31L * one + building.getFunctionalBlocks().size();
            one = 31L * one + (long) box.minX;
            one = 31L * one + (long) box.minY;
            one = 31L * one + (long) box.minZ;
            one = 31L * one + (long) box.maxX;
            one = 31L * one + (long) box.maxY;
            one = 31L * one + (long) box.maxZ;
            signature += one * 0x9E3779B97F4A7C15L;
        }
        return signature;
    }

    /**
     * Farmable soil in the water's 9x9 hydration footprint, or {@code null}
     * when part of that footprint is not loaded and the answer must not be
     * remembered. Callers go through {@link #plausibleFarmSoilCached}.
     */
    @Nullable
    private static List<BlockPos> plausibleFarmSoil(
            ServerLevel level, VillageManager village, BlockPos water) {
        if (!level.hasChunkAt(water)) return null;
        if (!level.getFluidState(water).isSource()) return List.of();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getFluidState(water.relative(direction)).isSource()) return List.of();
        }
        List<BlockPos> soil = new ArrayList<>();
        int farmlandAnchors = 0;
        boolean plantedCropAnchor = false;
        // One source hydrates the full vanilla 9x9 footprint. Include that
        // whole footprint so an idle farmer can expand a field outward from
        // its water instead of stopping after the immediately adjacent ring.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos candidate = water.offset(dx, 0, dz);
                if (!level.hasChunkAt(candidate)) return null;
                BlockState state = level.getBlockState(candidate);
                BlockState above = level.getBlockState(candidate.above());
                boolean clear = above.isAir() || above.getBlock() instanceof CropBlock
                        || CropDefinitionRegistry.byCropBlock(above.getBlock()) != null
                        || FarmSiteSurvey.isClearableCover(above);
                if (clear && (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                        || state.is(Blocks.FARMLAND))) {
                    boolean protectedPosition = village.getBuildings().stream()
                            .anyMatch(building -> building.isNearRoom(candidate.above())
                                    && !building.getFunctionalBlocks().contains(candidate.above()));
                    if (!protectedPosition) {
                        soil.add(candidate.immutable());
                        if (state.is(Blocks.FARMLAND)) {
                            farmlandAnchors++;
                            plantedCropAnchor |= CropDefinitionRegistry.byCropBlock(above.getBlock()) != null;
                        }
                    }
                }
            }
        }
        // Water plus open dirt is common around rivers, ponds and sugar cane.
        // Expansion is unlocked only by a planted crop or a recognisable
        // tilled patch within the source's vanilla hydration footprint.
        return FarmerPlanning.canExpandHydratedField(
                plantedCropAnchor, farmlandAnchors, soil.size())
                ? soil : List.of();
    }

    /** Grass, ferns or snow on soil to be tilled: cleared first (see {@link FarmSiteSurvey#isClearableCover}). */
    public static boolean isClearableCover(BlockState state) {
        return FarmSiteSurvey.isClearableCover(state);
    }

    /** Uses the exact mapped FarmBlock search volume and NeoForge hydration hook. */
    public static boolean isActuallyHydrated(ServerLevel level, BlockPos farmlandPos) {
        return FarmSiteSurvey.isActuallyHydrated(level, farmlandPos);
    }

    /**
     * Called by a farmer; claims and returns at most one manager-planned job.
     * {@code supplyRoom}: how many more spare items the farmer can carry
     * before its carry limit sends it to deposit. A seed fetch never takes
     * more than its kept reserve plus this room.
     */
    @Nullable
    public Job requestJob(ServerLevel level, VillageManager village, NpcEntity farmer, int supplyRoom) {
        if (level.isClientSide() || farmer.getProfession() != Profession.FARMER) return null;
        FarmerSettings settings = FarmerSettings.of(farmer);
        if (!settings.works()) {
            release(farmer.getNpcId());
            return null;
        }
        long now = level.getGameTime();
        requestNotes.remove(farmer.getNpcId());
        Job assigned = assignments.get(farmer.getNpcId());
        if (assigned != null) {
            if (!VillagerPause.lapsed(farmer.getNpcId(), assigned.expiresAt(), now) && validateJob(level, assigned)
                    && jobAllowed(assigned, settings, farmer.getInventory())) return assigned;
            release(farmer.getNpcId());
        }

        InventoryManager inventory = farmer.getInventory();
        if (settings.boneMealOnly()) {
            Job job = boneMealJob(level, village, farmer, settings, now, true);
            return job != null ? job : compostJob(level, village, farmer, settings, now, true);
        }
        UUID preferred = preferredFarm.get(farmer.getNpcId());
        Map<UUID, Integer> farmWorkers = farmWorkerCounts(village, farmer.getNpcId(), settings);
        // A planter leaves dirt plots to the village's tillers while they keep up.
        boolean mayTill = tillsNow(level, village, farmer, settings, now);
        noteTillerHandOff(level, village, farmer, settings, now);
        Map<CropDefinition, Boolean> plantableCrops = new HashMap<>();
        CropDefinition blankCrop = null;
        CropDefinition defaultCrop = CropDefinitionRegistry.defaultFarmlandCrop();
        for (CropDefinition definition : CropDefinitionRegistry.all()) {
            boolean canPlant = plantable(definition, settings, inventory);
            plantableCrops.put(definition, canPlant);
            if (canPlant && definition.planting() instanceof CropPlantingStrategy.Farmland
                    && (blankCrop == null || definition == defaultCrop)) blankCrop = definition;
        }
        Restoration restoration = recentHarvests.get(farmer.getNpcId());
        if (restoration != null && !restorationNeeded(level, settings, restoration, mayTill)) {
            recentHarvests.remove(farmer.getNpcId());
            restoration = null;
        }
        if (restoration != null && !inventory.contains(restoration.seed())
                && now >= nextSupplyCheck.getOrDefault(farmer.getNpcId(), 0L)) {
            nextSupplyCheck.put(farmer.getNpcId(), now + SUPPLY_RECHECK_TICKS);
            Job supplies = supplyJob(level, village, farmer, settings, now, restoration.seed(), supplyRoom);
            if (supplies != null) return supplies;
        }
        if (restoration == null) {
            Job boneMeal = boneMealJob(level, village, farmer, settings, now, false);
            if (boneMeal != null) return boneMeal;
        }

        Set<FarmerPerennials.Kind> leftToOthers = leftToSpecialists(village, settings, now);
        Predicate<BlockPos> standFree = standFreeFor(farmer.getNpcId(), now);
        // A tiller tills for what the village's planters can sow, not for its own seeds.
        VillagePlanting forVillage = settings.tillsForVillage() && mayTill
                ? villagePlanting(level, village, now) : null;
        Map<CropDefinition, Boolean> tillCrops = forVillage != null ? forVillage.crops() : plantableCrops;
        CropDefinition tillBlank = forVillage != null ? forVillage.blankCrop() : blankCrop;

        // A blocked stand is cooled down and another candidate is tried. Each
        // pass uses cached survey sets; only the selected tile gets a nav lookup.
        boolean triedGeneralSupplies = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            Candidate best = null;
            int waitingPlots = 0;
            if (forVillage != null) {
                waitingPlots = plotsAwaitingPlanters(forVillage);
            } else {
                for (FarmSite site : farms.values()) {
                    waitingPlots += waitingPlots(level, village.getGraph(), site, settings, now);
                }
            }
            boolean plantAvailable = false;
            boolean retillAvailable = false;
            for (FarmSite site : farms.values()) {
                Candidate harvest = harvestCandidate(level, village.getGraph(), farmer, settings, site, now);
                Candidate plant = plantCandidate(level, village.getGraph(), farmer, site, now,
                        plantableCrops, blankCrop);
                TillCandidates soil = tillCandidates(farmer, settings, mayTill, site, now,
                        tillCrops, tillBlank);
                plantAvailable |= plant != null;
                retillAvailable |= soil.retill() != null;
                Candidate siteBest = chooseCandidate(null, harvest, plant,
                        soil.retill(), soil.expand(), waitingPlots);
                best = betterCandidate(best, siteBest, farmer, preferred,
                        farmWorkers.getOrDefault(site.id, 0), waitingPlots >= 2);
            }
            Candidate restore = restoration == null ? null
                    : restorationCandidate(inventory, restoration, mayTill, now);
            if (!triedGeneralSupplies && restore == null && waitingPlots >= 2
                    && !plantAvailable && !retillAvailable
                    && now >= nextSupplyCheck.getOrDefault(farmer.getNpcId(), 0L)) {
                triedGeneralSupplies = true;
                nextSupplyCheck.put(farmer.getNpcId(), now + SUPPLY_RECHECK_TICKS);
                Job supplies = supplyJob(level, village, farmer, settings, now, null, supplyRoom);
                if (supplies != null) return supplies;
            }
            if (restore != null) best = restore;
            FarmerPerennials.Candidate perennial = restore != null ? null
                    : perennials.bestCandidate(farmer.blockPosition(), settings, inventory,
                            target -> available(target, now), mayPlantBerries(farmer), leftToOthers,
                            farmWorkers);
            if (best == null && perennial == null) {
                Job supplies = triedGeneralSupplies ? null
                        : supplyJob(level, village, farmer, settings, now, null, supplyRoom);
                return supplies != null ? supplies
                        : compostJob(level, village, farmer, settings, now, false);
            }
            if (perennial != null && (best == null || perennialScore(perennial, farmer, farmWorkers)
                    > best.score)) {
                BlockPos target = perennial.target();
                BlockPos stand = FarmerPerennials.standFor(level, village.getGraph(),
                        perennial.patch().kind, target, farmer.blockPosition(), standFree);
                if (stand == null) {
                    boolean busy = FarmerPerennials.standFor(level, village.getGraph(),
                            perennial.patch().kind, target, farmer.blockPosition()) != null;
                    skipTarget(target, busy, now);
                    noteSkipped(farmer, perennial.type(), target, busy, "");
                    continue;
                }
                Job job = new Job(perennial.patch().id, perennial.type(), target.immutable(), stand,
                        null, perennial.item(), 0, false, now + RESERVATION_TICKS);
                claimLoose(farmer.getNpcId(), job);
                // Its own memory: in preferredFarm a patch replaced the farmer's
                // field, so one berry pick cost it the field it works.
                preferredPatch.put(farmer.getNpcId(), perennial.patch().id);
                lastOnPatch.add(farmer.getNpcId());
                return job;
            }

            boolean ordinaryFieldJob = isOrdinaryFieldJob(best);
            BlockPos stand = interactionPosition(level, village.getGraph(), best.type,
                    best.target, farmer.blockPosition(), ordinaryFieldJob, standFree);
            if (stand == null) {
                boolean busy = interactionPosition(level, village.getGraph(), best.type,
                        best.target, farmer.blockPosition(), ordinaryFieldJob) != null;
                skipTarget(best.target, busy, now);
                noteSkipped(farmer, best.type, best.target, busy,
                        best.site.cropField ? "" : ", notField site");
                continue;
            }
            Job job = new Job(best.site.id, best.type, best.target.immutable(), stand,
                    best.fruitSpace, best.plantingItem, 0, ordinaryFieldJob,
                    now + RESERVATION_TICKS);
            if (best.fruitSpace != null) {
                best.site.fruitSpaces.put(best.target.immutable(), best.fruitSpace.immutable());
            }
            claim(farmer.getNpcId(), best.site, job);
            return job;
        }
        // Every try had nowhere to stand. Ending empty here kept composting
        // out of reach for good: each skipped spot is back a minute later, so
        // a farmer with a few it cannot reach never ran out of tries to spend.
        return compostJob(level, village, farmer, settings, now, false);
    }

    /** Diary note for a target given up this request, with how far off it was. */
    private void noteSkipped(NpcEntity farmer, JobType type, BlockPos target, boolean busy, String where) {
        int distance = (int) Math.sqrt(target.distSqr(farmer.blockPosition()));
        note(farmer.getNpcId(), "FARM skipped " + type + " at " + target.toShortString()
                + " (" + distance + " blocks away" + where + "): "
                + (busy ? "every spot by it taken, retry in 2 s" : "no spot to stand by it, retry in 60 s"));
    }

    private void note(UUID farmerId, String line) {
        requestNotes.computeIfAbsent(farmerId, ignored -> new ArrayList<>(4)).add(line);
    }

    /** The diary lines the last {@link #requestJob} or {@link #compostAfterFetch} left for this farmer, once. */
    public List<String> takeNotes(UUID farmerId) {
        List<String> notes = requestNotes.remove(farmerId);
        return notes == null ? List.of() : notes;
    }

    /** A plant whose every working spot is taken waits this long, not the unreachable minute. */
    private static final int STAND_BUSY_RETRY_TICKS = 40;

    /**
     * Spots no other farmer's job stands on. Two farmers picking the bushes
     * on both sides of one walkway chose the same cell and stood in each
     * other, as if both were working one bush.
     */
    private Predicate<BlockPos> standFreeFor(UUID farmerId, long now) {
        Set<BlockPos> taken = new HashSet<>();
        for (Map.Entry<UUID, Job> entry : assignments.entrySet()) {
            Job other = entry.getValue();
            if (entry.getKey().equals(farmerId) || other.interactionPos() == null
                    || other.type().isFetch() || VillagerPause.lapsed(entry.getKey(), other.expiresAt(), now)) continue;
            taken.add(other.interactionPos());
        }
        return taken.isEmpty() ? pos -> true : pos -> !taken.contains(pos);
    }

    /**
     * No stand for {@code target} this time. {@code busy}: only because other
     * farmers stand at every spot, so it is tried again in a moment; else it
     * cannot be reached at all and waits the usual minute.
     */
    private void skipTarget(BlockPos target, boolean busy, long now) {
        if (busy) {
            retryAfter.put(target.asLong(), now + STAND_BUSY_RETRY_TICKS);
            return;
        }
        recordFailure(target, now);
        retryAfter.put(target.asLong(), now + UNREACHABLE_RETRY_TICKS);
    }

    /**
     * Per crop kind, the farmers working only perennial crops (berries,
     * cocoa, glow berries) that can pick it now: switched on and carrying the
     * tool. Read once per tick for every farmer that asks.
     */
    private final Map<FarmerPerennials.Kind, Integer> specialistsOnDuty =
            new java.util.EnumMap<>(FarmerPerennials.Kind.class);
    private long specialistsCountedAt = Long.MIN_VALUE;

    /**
     * Crops this farmer leaves to the farmers set to only such crops. A
     * farmer that also grows field crops lets them pick first, and helps only
     * once more than {@link #SPECIALIST_SHARE} per such farmer are ready:
     * every farmer on a shared field preferred a ripe bush to its own crops,
     * so the berry farmers were left with nothing.
     */
    private Set<FarmerPerennials.Kind> leftToSpecialists(VillageManager village, FarmerSettings settings,
                                                         long now) {
        if (!tendsFieldCrops(settings)) return Set.of();
        if (specialistsCountedAt != now) {
            specialistsCountedAt = now;
            specialistsOnDuty.clear();
            for (var model : village.getVillagers()) {
                NpcEntity npc = model.puppet();
                if (npc == null || !npc.isAlive() || npc.isRemoved()
                        || npc.getProfession() != Profession.FARMER) continue;
                FarmerSettings other = FarmerSettings.of(npc);
                // One that does not harvest (also: no field work at all) covers no crop.
                if (!other.works() || other.turnOffHarvesting() || tendsFieldCrops(other)) continue;
                for (FarmerPerennials.Kind kind : FarmerPerennials.Kind.values()) {
                    CropDefinition definition = FarmerPerennials.definitionFor(kind);
                    if (definition != null && cropAllowed(other, definition)
                            && hasHarvestTool(npc.getInventory(), other, definition.harvestTool())) {
                        specialistsOnDuty.merge(kind, 1, Integer::sum);
                    }
                }
            }
        }
        if (specialistsOnDuty.isEmpty()) return Set.of();
        Set<FarmerPerennials.Kind> left = java.util.EnumSet.noneOf(FarmerPerennials.Kind.class);
        specialistsOnDuty.forEach((kind, count) -> {
            if (perennials.readyCount(kind) <= SPECIALIST_SHARE * count) left.add(kind);
        });
        return left;
    }

    /** Ready plants per specialist before the other farmers help pick. */
    private static final int SPECIALIST_SHARE = 4;

    /**
     * What the village's planting farmers could sow now, for a farmer that
     * tills for them ({@link FarmerSettings#tillsForVillage}): farmland crops a
     * working farmer with Planting on has on Replant, with the seed in its pack
     * or in storage. {@code blankCrop} is picked the way requestJob picks one.
     */
    private record VillagePlanting(Map<CropDefinition, Boolean> crops, @Nullable CropDefinition blankCrop,
                                   int planters) {
        boolean any() {
            return crops.containsValue(true);
        }
    }

    @Nullable private VillagePlanting villagePlanting;
    private long villagePlantingAt = Long.MIN_VALUE;

    /** Read once per tick for every tiller that asks. */
    private VillagePlanting villagePlanting(ServerLevel level, VillageManager village, long now) {
        if (villagePlanting != null && villagePlantingAt == now) return villagePlanting;
        Map<CropDefinition, Boolean> crops = new HashMap<>();
        int planters = 0;
        for (var model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved()
                    || npc.getProfession() != Profession.FARMER) continue;
            FarmerSettings other = FarmerSettings.of(npc);
            if (!other.works() || other.turnOffPlanting()) continue;
            planters++;
            for (CropDefinition definition : CropDefinitionRegistry.all()) {
                if (!(definition.planting() instanceof CropPlantingStrategy.Farmland)
                        || crops.getOrDefault(definition, false)) continue;
                if (plantAllowed(other, definition) && hasPlantingSupply(level, village, npc, definition, now)) {
                    crops.put(definition, true);
                }
            }
        }
        CropDefinition blankCrop = null;
        CropDefinition defaultCrop = CropDefinitionRegistry.defaultFarmlandCrop();
        for (CropDefinition definition : CropDefinitionRegistry.all()) {
            if (crops.getOrDefault(definition, false)
                    && (blankCrop == null || definition == defaultCrop)) blankCrop = definition;
        }
        villagePlanting = new VillagePlanting(crops, blankCrop, planters);
        villagePlantingAt = now;
        return villagePlanting;
    }

    /**
     * Empty farmland the village's planters could sow now, counting up to 2,
     * the same tiles {@link #waitingPlots} counts for a planter. A tiller
     * breaks no new soil while any waits, so it never runs ahead of them.
     */
    private int plotsAwaitingPlanters(VillagePlanting planting) {
        int count = 0;
        for (FarmSite site : farms.values()) {
            for (BlockPos tile : site.tilled) {
                if (site.hydration.contains(tile) && !isFruitSlot(site, tile)
                        && desiredCrop(site, tile, planting.crops(), planting.blankCrop()) != null) count++;
                if (count >= 2) return count;
            }
        }
        return count;
    }

    /**
     * Soil this tiller could work now, by the same rules requestJob hands it
     * out: a plot to re-till, or new soil while no empty farmland waits.
     */
    private boolean villageTillingWaiting(ServerLevel level, VillageManager village, NpcEntity farmer,
                                          FarmerSettings settings, long now) {
        VillagePlanting planting = villagePlanting(level, village, now);
        if (!planting.any()) return false;
        int waiting = plotsAwaitingPlanters(planting);
        boolean mayTill = canTill(settings, farmer.getInventory());
        for (FarmSite site : farms.values()) {
            TillCandidates soil = tillCandidates(farmer, settings, mayTill, site, now,
                    planting.crops(), planting.blankCrop());
            if (soil.retill() != null || waiting == 0 && soil.expand() != null) return true;
        }
        return false;
    }

    /**
     * Dirt plots each tiller on duty keeps to itself before the other farmers
     * help till again: the berry pickers' {@link #SPECIALIST_SHARE} rule. One
     * beginner tiller keeps up with about four beginner harvesters, so this
     * is rarely reached. {@code /villager tillshare} changes it until restart;
     * 0 is the old way (every farmer tills its own plots).
     */
    public static final int DEFAULT_TILL_SHARE = 4;
    private static int tillShare = DEFAULT_TILL_SHARE;

    public static int tillShare() {
        return tillShare;
    }

    public static void setTillShare(int plots) {
        tillShare = Math.max(0, plots);
    }

    /** Tillers on duty (switched on, carrying a hoe) and the dirt plots waiting for them. */
    private record TillerDuty(int tillers, int toTill) {
        boolean keepsUp() {
            return tillShare > 0 && tillers > 0 && toTill <= tillShare * tillers;
        }
    }

    @Nullable private TillerDuty tillerDuty;
    private long tillerDutyAt = Long.MIN_VALUE;
    /** Planters tilling again because the tillers fell behind; only for the diary lines. */
    private final Set<UUID> helpingTillers = new HashSet<>();

    /** Read once per tick for every farmer that asks. */
    private TillerDuty tillerDuty(ServerLevel level, VillageManager village, long now) {
        if (tillerDuty != null && tillerDutyAt == now) return tillerDuty;
        int tillers = 0;
        for (var model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved()
                    || npc.getProfession() != Profession.FARMER) continue;
            FarmerSettings other = FarmerSettings.of(npc);
            if (other.works() && other.tillsForVillage() && canTill(other, npc.getInventory())) tillers++;
        }
        int toTill = tillers == 0 ? 0
                : plotsToTill(villagePlanting(level, village, now), tillShare * tillers + 1, now);
        tillerDuty = new TillerDuty(tillers, toTill);
        tillerDutyAt = now;
        return tillerDuty;
    }

    /**
     * Plots gone back to dirt that a tiller could work now (the RETILL soil of
     * {@link #tillCandidates}, for what the planters can sow), counting up to {@code limit}.
     */
    private int plotsToTill(VillagePlanting planting, int limit, long now) {
        int count = 0;
        for (FarmSite site : farms.values()) {
            for (BlockPos tile : site.tillable) {
                if (site.everFarmland.contains(tile) && !isFruitSlot(site, tile) && available(tile, now)
                        && !site.isKeptForFruit(tile) && desiredCrop(site, tile, planting.crops(), planting.blankCrop()) != null) count++;
                if (count >= limit) return count;
            }
        }
        return count;
    }

    /**
     * Whether this farmer may till now: {@link #canTill}, less what a planter
     * leaves to the village's tillers while they keep up.
     */
    private boolean tillsNow(ServerLevel level, VillageManager village, NpcEntity farmer,
                             FarmerSettings settings, long now) {
        if (!canTill(settings, farmer.getInventory())) return false;
        return settings.tillsForVillage() || !tillerDuty(level, village, now).keepsUp();
    }

    /** A diary line when a planter starts helping the tillers, or leaves the tilling to them again. */
    private void noteTillerHandOff(ServerLevel level, VillageManager village, NpcEntity farmer,
                                   FarmerSettings settings, long now) {
        UUID id = farmer.getNpcId();
        TillerDuty duty = tillerDuty(level, village, now);
        if (tillShare == 0 || settings.tillsForVillage() || duty.tillers() == 0
                || !canTill(settings, farmer.getInventory())) {
            helpingTillers.remove(id);
            return;
        }
        int share = tillShare * duty.tillers();
        if (!duty.keepsUp() && helpingTillers.add(id)) {
            note(id, "FARM helping the tiller: " + duty.toTill() + " plots wait to be tilled, more than the "
                    + duty.tillers() + " tiller(s) keep (" + share + ")");
        } else if (duty.keepsUp() && helpingTillers.remove(id)) {
            note(id, "FARM leaving the tilling to the tiller again: " + duty.toTill() + " plots wait");
        }
    }

    /** Diary text: how many planters, what they can sow, and the empty farmland waiting for them. */
    private String describeVillagePlanting(ServerLevel level, VillageManager village, long now) {
        VillagePlanting planting = villagePlanting(level, village, now);
        StringBuilder crops = new StringBuilder();
        planting.crops().forEach((definition, sowable) -> {
            if (!sowable) return;
            if (!crops.isEmpty()) crops.append(',');
            crops.append(definition.representativePlantingItem()
                    .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath()).orElse("?"));
        });
        TillerDuty duty = tillerDuty(level, village, now);
        return planting.planters() + " planters [" + crops + "] waiting=" + plotsAwaitingPlanters(planting)
                + " toTill=" + duty.toTill() + " tillers=" + duty.tillers() + " share=" + tillShare;
    }

    /** Whether the farmer grows anything outside the perennial patches: farmland crops or cane. */
    private static boolean tendsFieldCrops(FarmerSettings settings) {
        for (CropDefinition definition : CropDefinitionRegistry.all()) {
            if (!definition.planting().perennial() && cropAllowed(settings, definition)) return true;
        }
        return false;
    }

    /**
     * Whether this farmer has any use for a hoe: a farmland crop it does not
     * leave alone (the hoe tills and paces its work, and a tiller tills for
     * those crops). Berries, cocoa, glow berries and cane need none, so a
     * farmer set to only those neither fetches a hoe nor waits for one.
     */
    public static boolean needsHoe(FarmerSettings settings) {
        if (settings.boneMealOnly()) return false;
        for (CropDefinition definition : CropDefinitionRegistry.all()) {
            if (definition.planting() instanceof CropPlantingStrategy.Farmland
                    && cropAllowed(settings, definition)) return true;
        }
        return false;
    }

    /**
     * Whether this farmer may till now: Tilling on and a usable hoe. Every
     * "is there tilling for it" answer asks this, so a farmer with Tilling off
     * counts bare soil the same as one without a hoe.
     */
    public static boolean canTill(FarmerSettings settings, InventoryManager inventory) {
        return settings.tilling() && FarmerHoeUtils.findUsableHoeSlot(inventory, settings) >= 0;
    }

    /** Whether some berry patch has a free spot for a new bush: berries are kept as seeds only then. */
    public boolean berrySpotsOpen() {
        return perennials.berrySpotsOpen();
    }

    /**
     * Scored like a field job, crowding included: with no penalty here every
     * farmer sharing a field went to the bushes together.
     */
    private double perennialScore(FarmerPerennials.Candidate candidate, NpcEntity farmer,
                                  Map<UUID, Integer> workers) {
        UUID preferred = preferredPatch.get(farmer.getNpcId());
        return FarmerWorkPolicy.fieldScore(candidate.priority(),
                Math.sqrt(candidate.target().distSqr(farmer.blockPosition())),
                candidate.patch().id.equals(preferred),
                workers.getOrDefault(candidate.patch().id, 0), false, false);
    }

    /**
     * Claims a job that belongs to no field site: perennial, compost and
     * bone meal work. A fetch reserves no cell (the storage door is shared),
     * only the composter it is for.
     */
    private void claimLoose(UUID farmerId, Job job) {
        if (!job.type().isFetch()) {
            reservations.put(job.target().asLong(), new Reservation(farmerId, job.expiresAt()));
        }
        if (job.fruitSpace() != null) {
            reservations.put(job.fruitSpace().asLong(), new Reservation(farmerId, job.expiresAt()));
        }
        assignments.put(farmerId, job);
    }

    // -------------------------------------------------------------------------
    // Bone meal
    // -------------------------------------------------------------------------

    private double foodUrgency(ServerLevel level, VillageManager village, long now) {
        if (foodUrgencyAt == Long.MIN_VALUE || now - foodUrgencyAt >= FOOD_URGENCY_REFRESH_TICKS) {
            foodUrgency = FarmerBoneMeal.foodUrgency(level, village);
            foodUrgencyAt = now;
        }
        return foodUrgency;
    }

    /**
     * Bone meal work, if this farmer's timer allows it: one use on the best
     * target, or first a trip for bone meal (or bones to make it).
     * {@code only}: a farmer with no field work, which has no timer.
     */
    @Nullable
    private Job boneMealJob(ServerLevel level, VillageManager village, NpcEntity farmer,
                            FarmerSettings settings, long now, boolean only) {
        if (!settings.useBoneMeal()) return null;
        UUID farmerId = farmer.getNpcId();
        BoneMealGate gate = null;
        if (!only) {
            gate = boneMealGates.computeIfAbsent(farmerId, ignored -> new BoneMealGate());
            if (gate.uses <= 0) {
                double urgency = foodUrgency(level, village, now);
                if (gate.nextBurstAt == Long.MIN_VALUE) {
                    // A farmer just loaded or hired waits a while first.
                    gate.nextBurstAt = now + FarmerBoneMeal.ticksUntilNextBurst(urgency, level.random);
                    return null;
                }
                if (now < gate.nextBurstAt) return null;
                gate.uses = FarmerBoneMeal.burstUses(urgency);
                gate.nextBurstAt = now + FarmerBoneMeal.ticksUntilNextBurst(urgency, level.random);
            }
        }
        BoneMealTarget target = boneMealTarget(level, village, farmer, settings, now);
        if (target == null) {
            if (gate != null) gate.uses = 0;
            return null;
        }
        InventoryManager inventory = farmer.getInventory();
        if (FarmerBoneMeal.carried(inventory) <= 0) FarmerBoneMeal.craftFromBones(farmer, village);
        if (FarmerBoneMeal.carried(inventory) <= 0) {
            Job fetch = boneMealFetch(level, village, farmer, now);
            // Nothing to use anywhere: let this burst go rather than ask every job.
            if (fetch == null && gate != null) gate.uses = 0;
            return fetch;
        }
        Predicate<BlockPos> standFree = standFreeFor(farmerId, now);
        BlockPos stand = target.kind() == null
                ? interactionPosition(level, village.getGraph(), JobType.BONEMEAL, target.pos(),
                        farmer.blockPosition(), target.onFarmland(), standFree)
                : FarmerPerennials.standFor(level, village.getGraph(), target.kind(),
                        target.pos(), farmer.blockPosition(), standFree);
        if (stand == null) {
            boolean busy = (target.kind() == null
                    ? interactionPosition(level, village.getGraph(), JobType.BONEMEAL, target.pos(),
                            farmer.blockPosition(), target.onFarmland())
                    : FarmerPerennials.standFor(level, village.getGraph(), target.kind(),
                            target.pos(), farmer.blockPosition())) != null;
            skipTarget(target.pos(), busy, now);
            return null;
        }
        Job job = new Job(target.farmId(), JobType.BONEMEAL, target.pos().immutable(), stand, null,
                net.minecraft.world.item.Items.BONE_MEAL, 0, target.onFarmland(), now + RESERVATION_TICKS);
        claimLoose(farmerId, job);
        if (gate != null) gate.uses--;
        return job;
    }

    /**
     * Whether this farmer's bone meal timer is up and there is something to
     * do with it: a growing crop and bone meal (or bones) carried or in
     * storage. Counts as work, so a farmer whose crops are all still growing
     * comes back from its break to bone meal them instead of idling until
     * something ripens. Same timer as {@link #boneMealJob}: once per burst.
     */
    private boolean boneMealWorkDue(ServerLevel level, VillageManager village, NpcEntity farmer,
                                    FarmerSettings settings, long now) {
        if (!settings.useBoneMeal()) return false;
        // requestJob skips bone meal while a harvested plot waits to be
        // replanted; counting it then would start sessions that do nothing.
        Restoration restoration = recentHarvests.get(farmer.getNpcId());
        if (restoration != null && restorationNeeded(level, settings, restoration,
                tillsNow(level, village, farmer, settings, now))) return false;
        BoneMealGate gate = boneMealGates.computeIfAbsent(farmer.getNpcId(), ignored -> new BoneMealGate());
        if (gate.uses <= 0) {
            if (gate.nextBurstAt == Long.MIN_VALUE) {
                // First look at a farmer just loaded or hired: it waits a while, as in boneMealJob.
                gate.nextBurstAt = now + FarmerBoneMeal.ticksUntilNextBurst(
                        foodUrgency(level, village, now), level.random);
                return false;
            }
            if (now < gate.nextBurstAt) return false;
        }
        if (gate.workCheckedAt != Long.MIN_VALUE && now - gate.workCheckedAt >= 0
                && now - gate.workCheckedAt < BONE_MEAL_WORK_RECHECK_TICKS) return gate.workFound;
        InventoryManager inventory = farmer.getInventory();
        boolean supplied = FarmerBoneMeal.carried(inventory) > 0
                || countItem(inventory, net.minecraft.world.item.Items.BONE) > 0
                || storageHas(level, village, net.minecraft.world.item.Items.BONE_MEAL, now)
                || storageHas(level, village, net.minecraft.world.item.Items.BONE, now);
        boolean found = supplied && boneMealTarget(level, village, farmer, settings, now) != null;
        if (!found) {
            // Nothing to do this burst: let it go, as boneMealJob would, so
            // the crops are not searched again on every poll.
            if (gate.uses <= 0) {
                gate.nextBurstAt = now + FarmerBoneMeal.ticksUntilNextBurst(
                        foodUrgency(level, village, now), level.random);
            }
            gate.uses = 0;
        }
        gate.workCheckedAt = now;
        gate.workFound = found;
        return found;
    }

    private record BoneMealTarget(UUID farmId, BlockPos pos, @Nullable FarmerPerennials.Kind kind,
                                  boolean onFarmland) {}

    /**
     * The growing crop with the most stages left, nearest first among equals.
     * Crops set to "Leave" and crops a druid is working are skipped.
     */
    @Nullable
    private BoneMealTarget boneMealTarget(ServerLevel level, VillageManager village, NpcEntity farmer,
                                          FarmerSettings settings, long now) {
        BoneMealTarget best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        BlockPos from = farmer.blockPosition();
        for (FarmSite site : farms.values()) {
            for (BlockPos tile : site.planted) {
                if (site.mature.contains(tile)) continue;
                double score = boneMealScore(level, village, farmer, settings, tile, from, now);
                if (score > bestScore) {
                    best = new BoneMealTarget(site.id, tile, null,
                            level.getBlockState(tile.below()).is(Blocks.FARMLAND));
                    bestScore = score;
                }
            }
        }
        for (Map.Entry<BlockPos, FarmerPerennials.Kind> entry : perennials.growing(settings).entrySet()) {
            double score = boneMealScore(level, village, farmer, settings, entry.getKey(), from, now);
            if (score > bestScore) {
                best = new BoneMealTarget(village.getVillageId(), entry.getKey(), entry.getValue(), false);
                bestScore = score;
            }
        }
        return best;
    }

    private double boneMealScore(ServerLevel level, VillageManager village, NpcEntity farmer,
                                 FarmerSettings settings, BlockPos tile, BlockPos from, long now) {
        if (!level.hasChunkAt(tile) || !available(tile, now)
                || village.getClaims().isClaimedByOther(tile, farmer.getNpcId())) {
            return Double.NEGATIVE_INFINITY;
        }
        BlockState state = level.getBlockState(tile);
        CropDefinition definition = CropDefinitionRegistry.byCropBlock(state.getBlock());
        if (definition == null || !cropAllowed(settings, definition)
                || !FarmerBoneMeal.isTarget(level, tile, state)) return Double.NEGATIVE_INFINITY;
        return FarmerBoneMeal.stagesLeft(state) * 16.0 - Math.sqrt(tile.distSqr(from));
    }

    /** Bone meal from storage, or else bones to make it from. */
    @Nullable
    private Job boneMealFetch(ServerLevel level, VillageManager village, NpcEntity farmer, long now) {
        int wanted = FarmerBoneMeal.CARRY_TARGET - FarmerBoneMeal.carried(farmer.getInventory());
        if (wanted <= 0) return null;
        Item item = net.minecraft.world.item.Items.BONE_MEAL;
        SupplySource source = supplySource(level, village, farmer, item, now);
        int amount = wanted;
        if (source == null) {
            item = net.minecraft.world.item.Items.BONE;
            source = supplySource(level, village, farmer, item, now);
            amount = FarmerBoneMeal.bonesFor(wanted);
        }
        if (source == null) return null;
        amount = Math.min(amount, source.available());
        if (amount <= 0) return null;
        return fetchJob(farmer, village, JobType.FETCH_BONE_MEAL, source, item, amount, null, now);
    }

    private Job fetchJob(NpcEntity farmer, VillageManager village, JobType type, SupplySource source,
                         Item item, int amount, @Nullable BlockPos forBlock, long now) {
        supplyClaims.merge(item, amount, Integer::sum);
        Job job = new Job(village.getVillageId(), type, source.storage().getDoorPos(), null, forBlock,
                item, amount, false, now + RESERVATION_TICKS);
        claimLoose(farmer.getNpcId(), job);
        return job;
    }

    // -------------------------------------------------------------------------
    // Compost
    // -------------------------------------------------------------------------

    /** Composters by the fields and berry/cocoa patches, plus any inside a storage room. */
    private Set<BlockPos> composters(ServerLevel level, VillageManager village, long now) {
        if (compostersAt != Long.MIN_VALUE && now - compostersAt < COMPOSTER_REFRESH_TICKS) return composters;
        compostersAt = now;
        List<Collection<BlockPos>> areas = new ArrayList<>();
        for (FarmSite site : farms.values()) areas.add(site.anchors);
        for (FarmerPerennials.Patch patch : perennials.patches()) {
            areas.add(patch.region.isEmpty() ? patch.plants : patch.region);
        }
        Set<BlockPos> found = new LinkedHashSet<>(FarmerCompost.findNear(level, areas));
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (BlockPos composter : storage.getComposterPositions()) {
                if (level.hasChunkAt(composter) && level.getBlockState(composter).is(Blocks.COMPOSTER)) {
                    found.add(composter.immutable());
                }
            }
        }
        composters = found;
        return composters;
    }

    /**
     * Composting, checked at most every half minute per farmer: empty a
     * ready composter, or fetch surplus scraps for one that has room.
     */
    @Nullable
    private Job compostJob(ServerLevel level, VillageManager village, NpcEntity farmer,
                           FarmerSettings settings, long now, boolean only) {
        if (!settings.compost()) return null;
        UUID farmerId = farmer.getNpcId();
        if (now < nextCompostCheck.getOrDefault(farmerId, Long.MIN_VALUE)) return null;
        nextCompostCheck.put(farmerId, now + (only ? COMPOST_CHECK_TICKS / 3 : COMPOST_CHECK_TICKS));
        Set<BlockPos> known = composters(level, village, now);
        if (known.isEmpty()) {
            note(farmerId, "COMPOST none: no composter within 8 blocks of a field or patch, or inside a storage room");
            return null;
        }
        List<BlockPos> nearest = FarmerCompost.nearestFirst(known, farmer.blockPosition());
        // A ready composter is emptied first: that bone meal is already made.
        for (BlockPos composter : nearest) {
            if (!available(composter, now) || !level.hasChunkAt(composter)
                    || FarmerCompost.fill(level.getBlockState(composter)) != ComposterBlock.READY
                    || FarmerCompost.hopperBelow(level, composter)) continue;
            Job job = compostAt(level, village, farmer, composter, null, 0, now);
            if (job != null) return job;
        }
        for (BlockPos composter : nearest) {
            if (!available(composter, now) || !level.hasChunkAt(composter)) continue;
            int fill = FarmerCompost.fill(level.getBlockState(composter));
            if (fill < 0 || fill >= ComposterBlock.MAX_LEVEL) continue;
            FarmerCompost.Choice choice = FarmerCompost.pickSurplus(level, village, supplyClaims);
            if (choice == null) {
                note(farmerId, "COMPOST none: nothing spare in storage (keeps 64 of each seed, 16 of anything else)");
                return null;
            }
            SupplySource source = supplySource(level, village, farmer, choice.item(), now);
            int amount = source == null ? 0 : Math.min(Math.min(choice.surplus(), source.available()),
                    FarmerCompost.itemsToFill(fill, choice.item()));
            if (amount <= 0) {
                note(farmerId, "COMPOST none: " + choice.surplus() + " spare "
                        + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(choice.item())
                        + " but none to fetch right now (storage door on retry, or all claimed)");
                return null;
            }
            return fetchJob(farmer, village, JobType.FETCH_COMPOST, source, choice.item(), amount,
                    composter.immutable(), now);
        }
        note(farmerId, "COMPOST none: all " + known.size() + " composters full, taken or unreachable");
        return null;
    }

    @Nullable
    private Job compostAt(ServerLevel level, VillageManager village, NpcEntity farmer, BlockPos composter,
                          @Nullable Item item, int amount, long now) {
        BlockPos stand = interactionPosition(level, village.getGraph(), JobType.COMPOST, composter,
                farmer.blockPosition(), false);
        if (stand == null) {
            recordFailure(composter, now);
            retryAfter.put(composter.asLong(), now + UNREACHABLE_RETRY_TICKS);
            note(farmer.getNpcId(), "COMPOST no spot to stand by the composter at "
                    + composter.toShortString() + ", retry in 60 s");
            return null;
        }
        // The claim covers the walk and every item dropped in, plus the waits
        // for a full composter to turn ready; with only the walk's 30 s a
        // long job lost its claim halfway and a second farmer could join it.
        long workTicks = (long) Math.max(0, amount) * FarmerCompost.TICKS_PER_ITEM
                + 4L * FarmerCompost.READY_WAIT_TICKS;
        Job job = new Job(village.getVillageId(), JobType.COMPOST, composter.immutable(), stand, null,
                item, amount, false, now + RESERVATION_TICKS + workTicks);
        claimLoose(farmer.getNpcId(), job);
        return job;
    }

    /**
     * Straight after a compost fetch: the composting job for what was
     * fetched, before a deposit pass can carry it back to the chest.
     */
    @Nullable
    public Job compostAfterFetch(ServerLevel level, VillageManager village, NpcEntity farmer, Job fetch) {
        BlockPos composter = fetch.fruitSpace();
        Item item = fetch.plantingItem();
        if (composter == null || item == null || !level.hasChunkAt(composter)
                || !available(composter, level.getGameTime())) return null;
        int carried = countItem(farmer.getInventory(), item);
        if (carried <= 0 || FarmerCompost.fill(level.getBlockState(composter)) < 0) return null;
        return compostAt(level, village, farmer, composter, item,
                Math.min(carried, fetch.supplyAmount()), level.getGameTime());
    }

    /** The tool the crop planted by {@code plantingItem} is harvested with, from its crop data. */
    public static HarvestTool harvestToolFor(@Nullable Item plantingItem) {
        CropDefinition definition = plantingItem == null ? null : CropDefinitionRegistry.byPlantingItem(plantingItem);
        return definition == null ? HarvestTool.NONE : definition.harvestTool();
    }

    /** Whether the farmer carries a usable {@code tool} it is allowed to use. */
    public static boolean hasHarvestTool(InventoryManager inventory, FarmerSettings settings, HarvestTool tool) {
        return switch (tool) {
            case NONE -> true;
            case SHEARS -> settings.allowShears() && FarmerShearsUtils.findUsableShearsSlot(inventory) >= 0;
            case AXE -> FarmerAxeUtils.findUsableAxeSlot(inventory, settings) >= 0;
        };
    }

    /**
     * Whether this farmer has crops to harvest that need {@code tool}: what
     * sends it for shears or an axe, and what the blacksmith counts. Based on
     * what grows (plots and patches), not on what is ripe this minute, so
     * the farmer does not fetch and bank the tool over and over.
     */
    public boolean wantsHarvestTool(NpcEntity farmer, HarvestTool tool) {
        if (tool == HarvestTool.NONE) return false;
        FarmerSettings settings = FarmerSettings.of(farmer);
        // Harvesting off (which includes no field work at all): no shears or axe trips.
        if (!settings.works() || settings.turnOffHarvesting()) return false;
        if (tool == HarvestTool.SHEARS && !settings.allowShears()) return false;
        for (CropDefinition definition : CropDefinitionRegistry.all()) {
            if (definition.harvestTool() != tool || !cropAllowed(settings, definition)) continue;
            if (definition.planting().perennial()) {
                if (perennials.grows(definition)) return true;
                // Just loaded, patches not rebuilt yet (a dormant village never
                // ticks, so all through its catch-up): what was saved counts.
                if (perennialRebuildPending && savedPerennial(definition)) return true;
                continue;
            }
            for (FarmSite site : farms.values()) {
                if (site.inferredCrop.containsValue(definition) || !site.fruits.isEmpty()) return true;
            }
        }
        return false;
    }

    /** Whether the saved scans or pod memory hold plants of this perennial crop. */
    private boolean savedPerennial(CropDefinition definition) {
        if (definition == FarmerPerennials.definitionFor(FarmerPerennials.Kind.COCOA)) {
            return perennials.awayPodSpots() > 0;
        }
        if (definition == FarmerPerennials.definitionFor(FarmerPerennials.Kind.BERRY)) {
            return awayBerryBushes() > 0;
        }
        if (definition == FarmerPerennials.definitionFor(FarmerPerennials.Kind.GLOW_BERRY)) {
            return observations.values().stream().anyMatch(o -> o.type() == ObservationType.GLOW_BERRY);
        }
        return false;
    }

    /**
     * Sweet berry bushes the scans found inside the border. The scans are
     * saved, so a village restarted while nobody was near knows them.
     */
    public int awayBerryBushes() {
        int count = 0;
        for (Observation observation : observations.values()) {
            if (observation.type() == ObservationType.BERRY) count++;
        }
        return count;
    }

    /** Whether something ripe is waiting that only {@code tool} can harvest. */
    public boolean hasRipeNeeding(HarvestTool tool) {
        if (tool == HarvestTool.NONE) return false;
        if (perennials.ripeNeeding(tool) > 0) return true;
        if (tool != HarvestTool.AXE) return false;
        for (FarmSite site : farms.values()) if (!site.fruits.isEmpty()) return true;
        return false;
    }

    /** Whether this farmer, or any job type, may do {@code job} right now. */
    public static boolean jobAllowed(Job job, FarmerSettings settings,
                                     InventoryManager inventory) {
        if (settings.boneMealOnly() && !job.type().isBoneMealWork()) return false;
        if (settings.turnOffHarvesting() && job.type().isHarvest()) return false;
        switch (job.type()) {
            case BONEMEAL -> {
                return settings.useBoneMeal() && FarmerBoneMeal.carried(inventory) > 0;
            }
            case FETCH_BONE_MEAL -> {
                return settings.useBoneMeal();
            }
            case COMPOST, FETCH_COMPOST -> {
                return settings.compost();
            }
            case HARVEST_POD, PICK_BERRIES, PICK_GLOW_BERRIES, HARVEST_FRUIT -> {
                return job.plantingItem() != null && settings.isPlantEnabled(job.plantingItem())
                        && hasHarvestTool(inventory, settings, harvestToolFor(job.plantingItem()));
            }
            case PLANT_POD, PLANT_BUSH -> {
                return job.plantingItem() != null && settings.shouldPlant(job.plantingItem());
            }
            default -> {
            }
        }
        if ((job.type() == JobType.TILL || job.type() == JobType.RETILL)
                && !canTill(settings, inventory)) return false;
        if (job.plantingItem() == null) return true;
        return switch (job.type()) {
            case PLANT, PLANT_CANE, FETCH_SUPPLIES -> settings.shouldPlant(job.plantingItem());
            default -> settings.isPlantEnabled(job.plantingItem());
        };
    }

    @Nullable
    private static Candidate betterCandidate(@Nullable Candidate current,
                                             @Nullable Candidate candidate,
                                             NpcEntity farmer, @Nullable UUID preferred,
                                             int otherWorkers, boolean plantingBacklog) {
        if (candidate == null) return current;
        double score = FarmerWorkPolicy.fieldScore(candidate.priority,
                Math.sqrt(candidate.target.distSqr(farmer.blockPosition())),
                candidate.site.id.equals(preferred), otherWorkers,
                plantingBacklog && (candidate.type == JobType.PLANT
                        || candidate.type == JobType.PLANT_CANE),
                plantingBacklog && candidate.type == JobType.RETILL);
        return current == null || score > current.score ? candidate.withScore(score) : current;
    }

    /**
     * Count live farmers committed to each field and each perennial patch
     * (keyed by its id), including those between jobs. Only farmers doing
     * some of the same work count: a tiller and a planter share a field.
     */
    private Map<UUID, Integer> farmWorkerCounts(VillageManager village, UUID requestingFarmer,
                                                FarmerSettings requesting) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (var model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved()
                    || npc.getProfession() != Profession.FARMER
                    || npc.getNpcId().equals(requestingFarmer)) continue;
            FarmerSettings other = FarmerSettings.of(npc);
            if (!other.works() || !shareFieldWork(requesting, other)) continue;
            Job active = assignments.get(npc.getNpcId());
            UUID farmId = active != null && active.type() != JobType.FETCH_SUPPLIES
                    ? active.farmId()
                    : lastOnPatch.contains(npc.getNpcId())
                            ? preferredPatch.get(npc.getNpcId()) : preferredFarm.get(npc.getNpcId());
            if (farmId == null) continue;
            counts.merge(farmId, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Whether two farmers do some of the same field work. A tiller that only
     * tills (Harvesting off too) shares nothing with a farmer that does not
     * till for the village, so neither avoids the field the other is on.
     */
    private static boolean shareFieldWork(FarmerSettings a, FarmerSettings b) {
        boolean aOnlyTills = a.tillsForVillage() && a.turnOffHarvesting();
        boolean bOnlyTills = b.tillsForVillage() && b.turnOffHarvesting();
        return !aOnlyTills && !bOnlyTills || a.tillsForVillage() && b.tillsForVillage();
    }

    /** Work existing plots before expanding; a short planting backlog preempts harvesting. */
    @Nullable
    private static Candidate chooseCandidate(@Nullable Candidate restore, @Nullable Candidate harvest,
                                             @Nullable Candidate plant, @Nullable Candidate retill,
                                             @Nullable Candidate expand, int waitingPlots) {
        return switch (FarmerWorkPolicy.choose(restore != null, harvest != null,
                plant != null, retill != null, expand != null, waitingPlots)) {
            case RESTORE -> restore;
            case HARVEST -> harvest;
            case PLANT -> plant;
            case RETILL -> retill;
            case EXPAND -> expand;
            case NONE -> null;
        };
    }

    private record Restoration(UUID farmId, BlockPos target, Item seed) {}

    /**
     * Whether the plot this farmer just harvested still waits for it. Soil the
     * harvest turned to dirt waits only for a farmer that may till now
     * ({@link #tillsNow}: a hoe, Tilling on, no tiller keeping up): kept for one
     * that cannot, the plot could never be redone and held off its bone meal for good.
     */
    private boolean restorationNeeded(ServerLevel level, FarmerSettings settings,
                                      Restoration restoration, boolean mayTill) {
        FarmSite site = farms.get(restoration.farmId());
        BlockPos tile = restoration.target();
        return site != null && level.hasChunkAt(tile) && settings.shouldPlant(restoration.seed())
                && site.hydration.contains(tile) && !isFruitSlot(site, tile)
                && (site.inferredCrop.get(tile) == null
                    || site.inferredCrop.get(tile).representativePlantingItem()
                            .filter(restoration.seed()::equals).isPresent())
                && (site.tilled.contains(tile)
                    || site.tillable.contains(tile) && site.everFarmland.contains(tile) && mayTill);
    }

    @Nullable
    private Candidate restorationCandidate(InventoryManager inventory, Restoration restoration,
                                           boolean mayTill, long now) {
        FarmSite site = farms.get(restoration.farmId());
        if (site == null || !available(restoration.target(), now)
                || !inventory.contains(restoration.seed())) return null;
        BlockPos tile = restoration.target();
        if (site.tilled.contains(tile)) {
            return new Candidate(site, JobType.PLANT, tile, null, restoration.seed(), 500, 0);
        }
        if (site.tillable.contains(tile) && site.everFarmland.contains(tile) && mayTill) {
            return new Candidate(site, JobType.RETILL, tile, null, null, 450, 0);
        }
        return null;
    }

    /**
     * Empty plots of this site waiting to be planted, counting up to 2. A cane
     * spot no farmer can work is left out (step 1's check): two such spots
     * anywhere in the village kept the count at 2 for good, so no field was
     * ever extended and planting always went ahead of harvesting.
     */
    private int waitingPlots(ServerLevel level, @Nullable NavGraph graph, FarmSite site,
                             FarmerSettings settings, long now) {
        if (settings.turnOffPlanting()) return 0;
        int count = 0;
        for (Map.Entry<BlockPos, CropDefinition> entry : site.stackPlantable.entrySet()) {
            if (plantAllowed(settings, entry.getValue())
                    && caneSpotWorkable(level, graph, entry.getKey(), now)) count++;
            if (count >= 2) return count;
        }
        for (BlockPos tile : site.tilled) {
            if (site.hydration.contains(tile) && !isFruitSlot(site, tile)
                    && cropMayBePlanted(site, settings, tile)) count++;
            if (count >= 2) return count;
        }
        for (BlockPos tile : site.tillable) {
            if (site.everFarmland.contains(tile) && !isFruitSlot(site, tile)
                    && cropMayBePlanted(site, settings, tile)) count++;
            if (count >= 2) return count;
        }
        return count;
    }

    private static boolean cropMayBePlanted(FarmSite site, FarmerSettings settings, BlockPos tile) {
        CropDefinition crop = site.inferredCrop.get(tile);
        return crop == null || plantAllowed(settings, crop);
    }

    private record Candidate(FarmSite site, JobType type, BlockPos target,
                             @Nullable BlockPos fruitSpace, @Nullable Item plantingItem,
                             int priority, double score) {
        private Candidate withScore(double value) {
            return new Candidate(site, type, target, fruitSpace, plantingItem, priority, value);
        }
    }

    private record SupplySource(StorageBuilding storage, int available) {}

    @Nullable
    private Candidate harvestCandidate(ServerLevel level, @Nullable NavGraph graph, NpcEntity farmer,
                                       FarmerSettings settings, FarmSite site, long now) {
        if (settings.turnOffHarvesting()) return null;
        Candidate best = null;
        {
            // Melons and pumpkins come off with the tool their crop data names (an axe).
            for (BlockPos fruit : site.fruits) {
                if (!available(fruit, now) || !FarmSiteSurvey.isAttachedFruit(level, fruit)) continue;
                CropDefinition definition = FarmSiteSurvey.fruitDefinitionFor(level.getBlockState(fruit).getBlock());
                Item representative = definition == null
                        ? null : definition.representativePlantingItem().orElse(null);
                if (representative == null || !cropAllowed(settings, definition)
                        || !hasHarvestTool(farmer.getInventory(), settings, definition.harvestTool())) continue;
                best = nearer(best, new Candidate(site, JobType.HARVEST_FRUIT, fruit,
                        null, representative, 600, 0), farmer.blockPosition());
            }
        }
        for (Map.Entry<BlockPos, CropDefinition> entry : site.stackBases.entrySet()) {
            BlockPos base = entry.getKey();
            CropDefinition definition = entry.getValue();
            Item representative = definition.representativePlantingItem().orElse(null);
            if (representative == null || !available(base, now) || !cropAllowed(settings, definition)
                    || !isCaneGround(level.getBlockState(base.below()))) continue;
            Block cropBlock = level.getBlockState(base).getBlock();
            if (cropBlock == level.getBlockState(base.above()).getBlock()
                    && caneBaseReachable(level, graph, base)) {
                best = nearer(best, new Candidate(site, JobType.HARVEST_CANE, base,
                        null, representative, 600, 0), farmer.blockPosition());
            }
        }
        for (BlockPos tile : site.mature) {
            CropDefinition definition = CropDefinitionRegistry.byCropBlock(level.getBlockState(tile).getBlock());
            if (definition == null || !(definition.harvest() instanceof CropHarvestStrategy.BreakAndReplant)) {
                continue;
            }
            Item representative = definition.representativePlantingItem().orElse(null);
            if (representative == null || !cropAllowed(settings, definition)) continue;
            if (available(tile, now)) best = nearer(best,
                    new Candidate(site, JobType.HARVEST, tile, null, representative, 600, 0),
                    farmer.blockPosition());
        }
        return best;
    }

    @Nullable
    private Candidate plantCandidate(ServerLevel level, @Nullable NavGraph graph, NpcEntity farmer,
                                     FarmSite site, long now,
                                     Map<CropDefinition, Boolean> plantableCrops,
                                     @Nullable CropDefinition blankCrop) {
        Candidate best = null;
        Set<BlockPos> protectedFruit = new HashSet<>(site.fruitSpaces.values());
        for (Map.Entry<BlockPos, CropDefinition> entry : site.stackPlantable.entrySet()) {
            BlockPos tile = entry.getKey();
            CropDefinition definition = entry.getValue();
            Item representative = definition.representativePlantingItem().orElse(null);
            if (representative == null || !plantableCrops.getOrDefault(definition, false)
                    || !caneSpotWorkable(level, graph, tile, now)) continue;
            best = nearer(best, new Candidate(site, JobType.PLANT_CANE, tile,
                    null, representative, 400, 0), farmer.blockPosition());
        }
        for (BlockPos tile : site.tilled) {
            if (!site.hydration.contains(tile) || protectedFruit.contains(tile)
                    || !available(tile, now)) continue;
            CropDefinition definition = desiredCrop(site, tile, plantableCrops, blankCrop);
            if (definition == null) continue;
            Item seed = definition.representativePlantingItem().orElse(null);
            if (seed == null) continue;
            BlockPos fruitSpace = fruitSpaceForCrop(level, site, tile, definition);
            if (definition.maturity() instanceof CropMaturity.AttachedFruit && fruitSpace == null) continue;
            best = nearer(best, new Candidate(site, JobType.PLANT, tile,
                    fruitSpace, seed, 400, 0), farmer.blockPosition());
        }
        return best;
    }

    private record TillCandidates(@Nullable Candidate retill, @Nullable Candidate expand) {}

    /** {@code mayTill}: {@link #tillsNow} for a planter, {@link #canTill} for a tiller. */
    private TillCandidates tillCandidates(NpcEntity farmer,
                                         FarmerSettings settings, boolean mayTill,
                                         FarmSite site, long now,
                                         Map<CropDefinition, Boolean> plantableCrops,
                                         @Nullable CropDefinition blankCrop) {
        if (!mayTill) return new TillCandidates(null, null);
        Candidate retill = null;
        Candidate expand = null;
        Set<BlockPos> protectedFruit = new HashSet<>(site.fruitSpaces.values());
        protectedFruit.addAll(site.heldSoil);
        for (BlockPos tile : site.tillable) {
            if (protectedFruit.contains(tile) || !available(tile, now)) continue;
            // Never make more bare farmland than can be planted (by this worker,
            // or by the village's planters for a tiller), and none for a crop
            // this farmer is set to leave alone.
            CropDefinition desired = desiredCrop(site, tile, plantableCrops, blankCrop);
            if (desired == null || !cropAllowed(settings, desired)) continue;
            JobType type = site.everFarmland.contains(tile) ? JobType.RETILL : JobType.TILL;
            int priority = type == JobType.RETILL ? 350 : 300;
            Candidate candidate = new Candidate(site, type, tile, null, null, priority, 0);
            if (type == JobType.RETILL) retill = nearer(retill, candidate, farmer.blockPosition());
            else expand = nearer(expand, candidate, farmer.blockPosition());
        }
        return new TillCandidates(retill, expand);
    }

    private record SeedNeed(Map<Item, Integer> established, Map<BlockPos, FarmSite> blankTiles) {}

    /**
     * Plots {@code seed} could be planted on right now: its established
     * plots plus the blank ones its crop fits. Zero when not set to replant.
     */
    public int plotsNeeding(ServerLevel level, VillageManager village, FarmerSettings settings, Item seed) {
        CropDefinition definition = CropDefinitionRegistry.byPlantingItem(seed);
        if (definition == null || !plantAllowed(settings, definition)) return 0;
        SeedNeed need = seedNeed(level, village.getGraph(), settings, true, level.getGameTime());
        int count = need.established().getOrDefault(seed, 0);
        if (definition.planting() instanceof CropPlantingStrategy.Farmland)
            count += blankNeed(level, need.blankTiles(), definition);
        return count;
    }

    /** {@code canTill} false leaves out soil that needs tilling first: no seeds are fetched for it without a hoe or with Tilling off. */
    private SeedNeed seedNeed(ServerLevel level, @Nullable NavGraph graph, FarmerSettings settings,
                              boolean canTill, long now) {
        Map<Item, Integer> establishedNeed = new LinkedHashMap<>();
        Map<BlockPos, FarmSite> blankTiles = new LinkedHashMap<>();
        for (FarmSite site : farms.values()) {
            for (BlockPos tile : site.tilled) {
                if (!site.hydration.contains(tile) || isFruitSlot(site, tile)) continue;
                CropDefinition desired = site.inferredCrop.get(tile);
                if (desired == null) {
                    blankTiles.put(tile, site);
                    continue;
                }
                // Only count tiles the plant pass can actually use: a stem
                // with no free fruit space is never planted, and counting it
                // kept farmers fetching seeds they then deposited again.
                if (!plantAllowed(settings, desired)
                        || !fruitSpaceAvailable(level, site, tile, desired)) continue;
                desired.representativePlantingItem()
                        .ifPresent(item -> establishedNeed.merge(item, 1, Integer::sum));
            }
            for (Map.Entry<BlockPos, CropDefinition> entry : site.stackPlantable.entrySet()) {
                CropDefinition definition = entry.getValue();
                if (!plantAllowed(settings, definition)
                        || !caneSpotWorkable(level, graph, entry.getKey(), now)) continue;
                definition.representativePlantingItem()
                        .ifPresent(item -> establishedNeed.merge(item, 1, Integer::sum));
            }
            for (BlockPos tile : canTill ? site.tillable : Set.<BlockPos>of()) {
                if (!site.everFarmland.contains(tile) || isFruitSlot(site, tile)) continue;
                CropDefinition desired = site.inferredCrop.get(tile);
                if (desired == null) {
                    blankTiles.put(tile, site);
                } else if (plantAllowed(settings, desired)
                        && fruitSpaceAvailable(level, site, tile, desired)) {
                    desired.representativePlantingItem()
                            .ifPresent(item -> establishedNeed.merge(item, 1, Integer::sum));
                }
            }
        }
        // Empty pod spots and berry rows want beans and berries the same way.
        perennials.plantingNeed(settings).forEach((item, count) -> establishedNeed.merge(item, count, Integer::sum));
        return new SeedNeed(establishedNeed, blankTiles);
    }

    /**
     * Whether an empty cane spot can be planted now: not waiting out a skip,
     * and with a place to stand by the plant job's own check. Cane keeps no
     * pack reserve, so cane fetched for a spot no farmer could reach went
     * back to storage at the end of the shift and was fetched again, forever.
     */
    private boolean caneSpotWorkable(ServerLevel level, @Nullable NavGraph graph, BlockPos tile, long now) {
        return available(tile, now)
                && interactionPosition(level, graph, JobType.PLANT_CANE, tile, tile, false) != null;
    }

    /**
     * Whether a farmer has a place to stand beside this cane base, by the
     * harvest job's own check. Ripe wild cane nobody could reach was offered
     * as work every minute, and made {@link #hasWork} say yes, so a farmer
     * with nothing else to do started session after empty session instead of
     * refilling cauldrons. The cane stays a farm: tool trips and notes see it.
     */
    /**
     * Ground a cane base is cut on: whatever vanilla cane grows on (the dirt
     * and sand tags). Only {@code minecraft:sand} counted until 0.1.3, so a
     * row planted on a grassy bank was a known farm that nobody ever cut
     * (bug #130: no cane, no paper, no books for the Enchanter). Planting new
     * cane still wants the sand of the crop file: wet dirt is a field to till.
     */
    public static boolean isCaneGround(BlockState ground) {
        return ground.is(net.minecraft.tags.BlockTags.SAND) || ground.is(net.minecraft.tags.BlockTags.DIRT);
    }

    private static boolean caneBaseReachable(ServerLevel level, @Nullable NavGraph graph, BlockPos base) {
        return interactionPosition(level, graph, JobType.HARVEST_CANE, base, base, false) != null;
    }

    @Nullable
    private Job supplyJob(ServerLevel level, VillageManager village, NpcEntity farmer,
                          FarmerSettings settings, long now, @Nullable Item preferredItem,
                          int supplyRoom) {
        SeedNeed need = seedNeed(level, village.getGraph(), settings,
                tillsNow(level, village, farmer, settings, now), now);
        Map<Item, Integer> establishedNeed = need.established();
        Map<BlockPos, FarmSite> blankTiles = need.blankTiles();
        if (preferredItem != null && settings.shouldPlant(preferredItem))
            establishedNeed.merge(preferredItem, 1, Integer::sum);

        Item selected = null;
        int selectedNeed = -1;
        SupplySource selectedSource = null;
        for (Map.Entry<Item, Integer> entry : establishedNeed.entrySet()) {
            if (preferredItem != null && entry.getKey() != preferredItem) continue;
            if (refetchBlocked(farmer.getNpcId(), entry.getKey(), now)) continue;
            int carried = countItem(farmer.getInventory(), entry.getKey());
            int shortage = Math.max(0, entry.getValue() - carried);
            if (shortage <= 0) continue;
            SupplySource source = supplySource(level, village, farmer, entry.getKey(), now);
            if (source != null && shortage > selectedNeed) {
                selected = entry.getKey();
                selectedNeed = shortage;
                selectedSource = source;
            }
        }
        // A different crop is only eligible for a genuinely blank plot. If an
        // established plot's item is absent from both inventory and storage,
        // leave that plot empty rather than mixing in whatever happens to be
        // available.
        if (preferredItem == null && selected == null && !blankTiles.isEmpty()) {
            for (CropDefinition definition : CropDefinitionRegistry.all()) {
                if (!(definition.planting() instanceof CropPlantingStrategy.Farmland)) continue;
                Item candidate = definition.representativePlantingItem().orElse(null);
                if (candidate == null || !plantAllowed(settings, definition)
                        || refetchBlocked(farmer.getNpcId(), candidate, now)) continue;
                // Same rule as established plots: what this crop can really
                // use, minus what is already carried.
                int shortage = blankNeed(level, blankTiles, definition)
                        - countItem(farmer.getInventory(), candidate);
                if (shortage <= 0) continue;
                SupplySource source = supplySource(level, village, farmer, candidate, now);
                if (source != null && (selectedSource == null
                        || source.available() > selectedSource.available())) {
                    selected = candidate;
                    selectedNeed = shortage;
                    selectedSource = source;
                }
            }
        }
        if (selected == null || selectedSource == null) return null;
        int carried = countItem(farmer.getInventory(), selected);
        int stackLimit = new net.minecraft.world.item.ItemStack(selected).getMaxStackSize();
        // Sugar cane keeps no reserve: only the spots waiting for it are fetched.
        int reserve = FarmerInventory.keepsPlantingReserve(selected)
                ? FarmerInventory.PLANTING_RESERVE_PER_CROP : 0;
        int desired = Math.min(stackLimit, Math.max(reserve, carried + selectedNeed));
        // A deposit pass keeps one reserve of a planting item; anything past
        // that counts toward the carry limit. Fetching more than both left
        // the farmer at the limit, and the very next check walked it back to
        // the chest it had just emptied with the rest.
        int keepable = reserve + Math.max(0, supplyRoom) + Math.max(0, carried - reserve);
        desired = Math.min(desired, keepable);
        int amount = Math.min(desired - carried, selectedSource.available());
        if (amount <= 0) return null;
        supplyClaims.merge(selected, amount, Integer::sum);
        UUID farmId = farms.keySet().stream().findFirst().orElse(village.getVillageId());
        Job job = new Job(farmId, JobType.FETCH_SUPPLIES, selectedSource.storage().getDoorPos(),
                null, null, selected, amount, false, now + RESERVATION_TICKS);
        assignments.put(farmer.getNpcId(), job);
        FarmSite site = farms.get(farmId);
        if (site != null) site.activeJobs.put(farmer.getNpcId(), job);
        return job;
    }

    @Nullable
    private SupplySource supplySource(ServerLevel level, VillageManager village, NpcEntity farmer,
                                      Item item, long now) {
        int claimed = supplyClaims.getOrDefault(item, 0);
        StorageBuilding nearest = null;
        int nearestCount = 0;
        double nearestDistance = Double.MAX_VALUE;
        int total = 0;
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()
                    || storage.getFunctionalBlocks().isEmpty()
                    || !available(storage.getDoorPos(), now)) continue;
            int count = storage.countItem(level, item);
            if (count <= 0) continue;
            total += count;
            double distance = storage.getDoorPos().distSqr(farmer.blockPosition());
            if (distance < nearestDistance) {
                nearest = storage;
                nearestCount = count;
                nearestDistance = distance;
            }
        }
        int available = Math.min(nearestCount, total - claimed);
        return nearest == null || available <= 0 ? null : new SupplySource(nearest, available);
    }

    private static int countItem(InventoryManager inventory, Item item) {
        int count = 0;
        for (var stack : inventory.getSlots()) {
            if (inventory.mayUse(stack) && stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    @Nullable
    private static Candidate nearer(@Nullable Candidate current, Candidate candidate, BlockPos from) {
        if (current == null) return candidate;
        return candidate.target.distSqr(from) < current.target.distSqr(from) ? candidate : current;
    }

    private static boolean cropAllowed(FarmerSettings settings, CropDefinition definition) {
        return definition.representativePlantingItem().map(settings::isPlantEnabled).orElse(false);
    }

    private static boolean plantAllowed(FarmerSettings settings, CropDefinition definition) {
        return definition.representativePlantingItem().map(settings::shouldPlant).orElse(false);
    }

    /**
     * Picks the crop for one empty tilled tile. A tile with a clear
     * neighbourhood pattern (rows, an adjoining crop, a cohesive plot) only
     * ever plants that pattern's crop - even if the farmer happens to be
     * carrying a different seed - so a coherent plot never gets a stray
     * mismatched plant. A tile with no such signal is a genuinely blank
     * plot, where guessing the deterministic default (falling back to
     * whatever seed is actually in stock) is reasonable.
     */
    @Nullable
    private static CropDefinition desiredCrop(FarmSite site, BlockPos tile,
                                              Map<CropDefinition, Boolean> plantableCrops,
                                              @Nullable CropDefinition blankCrop) {
        CropDefinition established = site.inferredCrop.get(tile);
        return established != null
                ? (plantableCrops.getOrDefault(established, false) ? established : null)
                : blankCrop;
    }

    private static boolean plantable(CropDefinition definition, FarmerSettings settings, InventoryManager inventory) {
        return plantAllowed(settings, definition)
                && definition.representativePlantingItem().map(inventory::contains).orElse(false);
    }

    @Nullable
    private static BlockPos fruitSpaceForCrop(ServerLevel level, FarmSite site, BlockPos stem,
                                              CropDefinition definition) {
        if (!(definition.maturity() instanceof CropMaturity.AttachedFruit)) return null;
        BlockPos planned = site.fruitSpaces.get(stem);
        if (canGrowFruitAt(level, stem, planned)) return planned.immutable();

        Set<BlockPos> claimed = new HashSet<>(site.fruitSpaces.values());
        for (Job active : site.activeJobs.values()) {
            if (active.fruitSpace() != null) claimed.add(active.fruitSpace());
        }
        // Same ranking as the survey, so an unplanned stem still keeps its
        // fruit on untilled soil and never claims another pair's stem tile.
        Set<BlockPos> otherStems = new HashSet<>(site.fruitSpaces.keySet());
        otherStems.remove(stem);
        return FarmSiteSurvey.chooseFruitSpace(level, site, stem, null, claimed, otherStems, Map.of());
    }

    /** Non-fruit crops always pass; a stem needs somewhere for its fruit to grow. */
    private static boolean fruitSpaceAvailable(ServerLevel level, FarmSite site, BlockPos tile,
                                               CropDefinition definition) {
        return !(definition.maturity() instanceof CropMaturity.AttachedFruit)
                || fruitSpaceForCrop(level, site, tile, definition) != null;
    }

    /** Blank tiles {@code definition} could be planted on, capped at one stack. */
    private static int blankNeed(ServerLevel level, Map<BlockPos, FarmSite> blankTiles,
                                 CropDefinition definition) {
        if (!(definition.maturity() instanceof CropMaturity.AttachedFruit)) return blankTiles.size();
        int count = 0;
        for (Map.Entry<BlockPos, FarmSite> entry : blankTiles.entrySet()) {
            if (fruitSpaceAvailable(level, entry.getValue(), entry.getKey(), definition)
                    && ++count >= 64) break;
        }
        return count;
    }

    /** Tiles held empty for pumpkin/melon fruit; never tilled or planted over. */
    private static boolean isFruitSlot(FarmSite site, BlockPos tile) {
        return site.isKeptForFruit(tile);
    }

    private static boolean canGrowFruitAt(ServerLevel level, BlockPos stem,
                                          @Nullable BlockPos candidate) {
        if (candidate == null || !FarmSiteSurvey.isHorizontalNeighbour(stem, candidate)
                || !level.getBlockState(candidate).isAir()) return false;
        BlockState below = level.getBlockState(candidate.below());
        return below.is(Blocks.DIRT) || below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.FARMLAND);
    }

    private static boolean isOrdinaryFieldJob(Candidate candidate) {
        return switch (candidate.type()) {
            case HARVEST -> true;
            case PLANT -> candidate.fruitSpace() == null;
            case TILL, RETILL -> {
                CropDefinition inferred = candidate.site().inferredCrop.get(candidate.target());
                yield inferred == null || !(inferred.maturity() instanceof CropMaturity.AttachedFruit);
            }
            case HARVEST_FRUIT, HARVEST_CANE, PLANT_CANE, FETCH_SUPPLIES,
                 BONEMEAL, COMPOST, FETCH_BONE_MEAL, FETCH_COMPOST,
                 HARVEST_POD, PLANT_POD, PICK_BERRIES, PLANT_BUSH, PICK_GLOW_BERRIES -> false;
        };
    }

    @Nullable
    private static BlockPos interactionPosition(ServerLevel level, @Nullable NavGraph graph,
                                                JobType type, BlockPos target, BlockPos from,
                                                boolean ordinaryFieldJob) {
        return interactionPosition(level, graph, type, target, from, ordinaryFieldJob, pos -> true);
    }

    /**
     * As above; a spot beside the target is used only if {@code standFree}
     * allows it (no other farmer works from it). Standing on the target tile
     * itself needs no check: the target is claimed.
     */
    @Nullable
    private static BlockPos interactionPosition(ServerLevel level, @Nullable NavGraph graph,
                                                JobType type, BlockPos target, BlockPos from,
                                                boolean ordinaryFieldJob, Predicate<BlockPos> standFree) {
        if (graph == null) return null;
        boolean targetIsTilled = level.getBlockState(target.below()).is(Blocks.FARMLAND);
        // Tilling is done from the tile beside the target (the clip drags the blade from the far
        // end of the next block back to the farmer's feet), on tilled land when there is some;
        // the target itself is only the last resort, when no tile beside it can be stood on.
        boolean tilling = type == JobType.TILL || type == JobType.RETILL;
        boolean preferTarget = tilling ? false : ordinaryFieldJob
                ? targetIsTilled && (type == JobType.PLANT || type == JobType.HARVEST
                    || type == JobType.BONEMEAL)
                : type == JobType.PLANT || type == JobType.PLANT_CANE;
        boolean fieldScoring = ordinaryFieldJob || tilling;
        if (preferTarget) {
            NavNode targetNode = graph.nodeAt(target.getX(), target.getY(), target.getZ());
            if (targetNode != null && targetNode.isRoutable() && targetNode.degree() > 0) {
                return target.immutable();
            }
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = target.relative(direction);
            if (!standFree.test(candidate)) continue;
            NavNode node = graph.nodeAt(candidate.getX(), candidate.getY(), candidate.getZ());
            if (node == null || !node.isRoutable() || node.degree() == 0) continue;
            BlockState standing = level.getBlockState(candidate);
            BlockState ground = level.getBlockState(candidate.below());
            boolean crossesCrops = standing.getBlock() instanceof CropBlock
                    || CropDefinitionRegistry.byCropBlock(standing.getBlock()) != null;
            // Ordinary crop work stays on the tilled field when it can. Prefer
            // an empty farmland tile over a planted one, then a nearby path if
            // no farmland node is reachable. Fruit and cane keep their safer
            // path-first positioning.
            double score = candidate.distSqr(from)
                    + (fieldScoring
                        ? (ground.is(Blocks.FARMLAND) ? 0.0 : 100_000.0)
                            + (crossesCrops ? 1_000.0 : 0.0)
                        : (crossesCrops ? 100_000.0 : 0.0)
                            + (ground.is(Blocks.FARMLAND) ? 10_000.0 : 0.0));
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null && tilling) {
            NavNode targetNode = graph.nodeAt(target.getX(), target.getY(), target.getZ());
            if (targetNode != null && targetNode.isRoutable() && targetNode.degree() > 0) return target.immutable();
        }
        return best;
    }

    public boolean validateJob(ServerLevel level, Job job) {
        if (!level.hasChunkAt(job.target())) return false;
        BlockState state = level.getBlockState(job.target());
        switch (job.type()) {
            case BONEMEAL -> {
                return FarmerBoneMeal.isTarget(level, job.target(), state);
            }
            case COMPOST -> {
                return state.is(Blocks.COMPOSTER);
            }
            case FETCH_BONE_MEAL, FETCH_COMPOST -> {
                return job.plantingItem() != null;
            }
            case HARVEST_POD, PLANT_POD, PICK_BERRIES, PLANT_BUSH, PICK_GLOW_BERRIES -> {
                return perennials.validate(level, job);
            }
            default -> {
            }
        }
        FarmSite site = farms.get(job.farmId());
        if (site == null) return false;
        return switch (job.type()) {
            case HARVEST -> isMatureCrop(state);
            case HARVEST_FRUIT -> FarmSiteSurvey.fruitDefinitionFor(state.getBlock()) != null
                    && FarmSiteSurvey.isAttachedFruit(level, job.target());
            case HARVEST_CANE -> {
                CropDefinition definition = FarmSiteSurvey.stackDefinitionForBlock(state.getBlock());
                yield definition != null
                        && isCaneGround(level.getBlockState(job.target().below()))
                        && level.getBlockState(job.target().above()).getBlock() == state.getBlock();
            }
            // A tile can become a fruit slot after its job was handed out
            // (the plan is refreshed while the farmer walks over), so both
            // soil jobs re-check the reservation right before acting.
            case PLANT -> site.hydration.contains(job.target()) && !isFruitSlot(site, job.target())
                    && level.getBlockState(job.target().below()).is(Blocks.FARMLAND) && state.isAir()
                    && validFruitSpaceForPlant(level, site, job);
            case PLANT_CANE -> FarmSiteSurvey.matchingStackDefinition(level, job.target()) != null;
            case TILL, RETILL -> site.tillable.contains(job.target()) && !isFruitSlot(site, job.target())
                    && (level.getBlockState(job.target().below()).is(Blocks.DIRT)
                    || level.getBlockState(job.target().below()).is(Blocks.GRASS_BLOCK))
                    && (state.isAir() || FarmSiteSurvey.isClearableCover(state));
            case FETCH_SUPPLIES -> job.plantingItem() != null;
            // Answered above.
            case BONEMEAL, COMPOST, FETCH_BONE_MEAL, FETCH_COMPOST,
                 HARVEST_POD, PLANT_POD, PICK_BERRIES, PLANT_BUSH, PICK_GLOW_BERRIES -> false;
        };
    }

    private static boolean validFruitSpaceForPlant(ServerLevel level, FarmSite site, Job job) {
        CropDefinition definition = job.plantingItem() == null
                ? null : CropDefinitionRegistry.byPlantingItem(job.plantingItem());
        if (definition == null || !(definition.maturity() instanceof CropMaturity.AttachedFruit)) return true;
        return job.fruitSpace() != null
                && job.fruitSpace().equals(site.fruitSpaces.get(job.target()))
                && canGrowFruitAt(level, job.target(), job.fruitSpace());
    }

    /**
     * The worked tile is re-read at once, so it is never offered again as
     * stale work (a failed retry would blacklist it for a minute). The rest of
     * the field is re-surveyed through the dirty-site cooldown, so a busy field
     * costs one full survey a second instead of one per finished job. A supply
     * fetch changes no field block and triggers neither.
     */
    public void reportSuccess(ServerLevel level, VillageManager village, UUID farmerId, Job job) {
        FarmSite site = farms.get(job.farmId());
        if (site != null && job.type() != JobType.FETCH_SUPPLIES) {
            site.lastActivity = level.getGameTime();
            if (job.type() == JobType.HARVEST && job.plantingItem() != null) {
                CropDefinition harvested = CropDefinitionRegistry.byPlantingItem(job.plantingItem());
                if (harvested != null) site.inferredCrop.put(job.target().immutable(), harvested);
            }
            FarmSiteSurvey.refreshTile(level, village, site, job.target());
            // A harvest can turn farmland back to dirt. The single-tile refresh
            // removes it from planted but waits for the next survey to discover
            // tillable soil, so expose this known former plot immediately.
            if (job.type() == JobType.HARVEST && site.everFarmland.contains(job.target())
                    && site.hydration.contains(job.target())
                    && level.getBlockState(job.target()).isAir()
                    && (level.getBlockState(job.target().below()).is(Blocks.DIRT)
                        || level.getBlockState(job.target().below()).is(Blocks.GRASS_BLOCK))) {
                site.tillable.add(job.target().immutable());
            }
            dirtySites.add(site.id);
        }
        if (job.type().isPerennial() || job.type() == JobType.BONEMEAL) perennials.onWorked(level, job.target());
        if (job.type() == JobType.PLANT_BUSH) {
            berryPlantAllowance.computeIfPresent(farmerId, (id, left) -> left <= 1 ? null : left - 1);
        }
        failures.remove(job.target().asLong());
        retryAfter.remove(job.target().asLong());
        if (!job.type().isFetch() && job.type() != JobType.COMPOST
                && !job.farmId().equals(village.getVillageId())) {
            if (farms.containsKey(job.farmId())) {
                preferredFarm.put(farmerId, job.farmId());
                lastOnPatch.remove(farmerId);
            } else if (job.type().isPerennial()) {
                preferredPatch.put(farmerId, job.farmId());
                lastOnPatch.add(farmerId);
            }
        }
        if (job.type() == JobType.HARVEST && job.plantingItem() != null) {
            recentHarvests.put(farmerId,
                    new Restoration(job.farmId(), job.target().immutable(), job.plantingItem()));
        } else if (job.type() == JobType.PLANT) {
            Restoration pending = recentHarvests.get(farmerId);
            if (pending != null && pending.target().equals(job.target())) recentHarvests.remove(farmerId);
        }
        if (job.type() == JobType.FETCH_SUPPLIES && job.plantingItem() != null) {
            fetchedUnplanted.computeIfAbsent(farmerId, id -> new HashSet<>()).add(job.plantingItem());
        } else if (isPlanting(job.type()) && job.plantingItem() != null) {
            Set<Item> unplanted = fetchedUnplanted.get(farmerId);
            if (unplanted != null && unplanted.remove(job.plantingItem()) && unplanted.isEmpty()) {
                fetchedUnplanted.remove(farmerId);
            }
        }
        release(farmerId);
    }

    private static boolean isPlanting(JobType type) {
        return type == JobType.PLANT || type == JobType.PLANT_CANE
                || type == JobType.PLANT_POD || type == JobType.PLANT_BUSH;
    }

    /**
     * A farmer banked {@code item}. When it fetched that item to plant and has
     * planted none since, it does not fetch it again for five minutes: cane
     * (no farmer keeps any) fetched for a spot it never got to went back to
     * the chest at the end of each shift and was fetched again, over and over.
     * Returns true when that wait starts.
     */
    public boolean onDeposited(UUID farmerId, Item item, long now) {
        Set<Item> unplanted = fetchedUnplanted.get(farmerId);
        if (unplanted == null || !unplanted.remove(item)) return false;
        if (unplanted.isEmpty()) fetchedUnplanted.remove(farmerId);
        refetchBlockedUntil.computeIfAbsent(farmerId, id -> new HashMap<>()).put(item, now + REFETCH_BLOCK_TICKS);
        return true;
    }

    private boolean refetchBlocked(UUID farmerId, Item item, long now) {
        Map<Item, Long> blocked = refetchBlockedUntil.get(farmerId);
        Long until = blocked == null ? null : blocked.get(item);
        if (until == null) return false;
        if (until > now) return true;
        blocked.remove(item);
        if (blocked.isEmpty()) refetchBlockedUntil.remove(farmerId);
        return false;
    }

    public void reportFailure(UUID farmerId, Job job, long now) {
        recordFailure(job.target(), now);
        release(farmerId);
    }

    private void recordFailure(BlockPos target, long now) {
        long key = target.asLong();
        int count = failures.merge(key, 1, Integer::sum);
        if (count >= 2) retryAfter.put(key, now + UNREACHABLE_RETRY_TICKS);
    }

    private boolean available(BlockPos target, long now) {
        Reservation reservation = reservations.get(target.asLong());
        return (reservation == null || VillagerPause.lapsed(reservation.farmerId(), reservation.expiresAt(), now))
                && retryAfter.getOrDefault(target.asLong(), 0L) <= now;
    }

    private void claim(UUID farmerId, FarmSite site, Job job) {
        reservations.put(job.target().asLong(), new Reservation(farmerId, job.expiresAt()));
        if (job.fruitSpace() != null) {
            reservations.put(job.fruitSpace().asLong(), new Reservation(farmerId, job.expiresAt()));
        }
        assignments.put(farmerId, job);
        site.activeJobs.put(farmerId, job);
        preferredFarm.put(farmerId, site.id);
        lastOnPatch.remove(farmerId);
    }

    /**
     * For a farmer whose body is not loaded: a job restored from the save is left to
     * {@code expireAssignments}, which keeps it until the body loads or the job runs out.
     */
    public void releaseUnloaded(UUID farmerId) {
        if (restoredAwaitingFarmer.contains(farmerId)) return;
        release(farmerId);
    }

    public void release(UUID farmerId) {
        restoredAwaitingFarmer.remove(farmerId);
        Job job = assignments.remove(farmerId);
        if (job == null) return;
        Reservation reservation = reservations.get(job.target().asLong());
        if (reservation != null && reservation.farmerId().equals(farmerId)) {
            reservations.remove(job.target().asLong());
        }
        if (job.fruitSpace() != null) {
            Reservation fruitReservation = reservations.get(job.fruitSpace().asLong());
            if (fruitReservation != null && fruitReservation.farmerId().equals(farmerId)) {
                reservations.remove(job.fruitSpace().asLong());
            }
        }
        FarmSite site = farms.get(job.farmId());
        if (site != null) site.activeJobs.remove(farmerId);
        if (job.type().isFetch() && job.plantingItem() != null) {
            supplyClaims.computeIfPresent(job.plantingItem(),
                    (item, count) -> count <= job.supplyAmount() ? null : count - job.supplyAmount());
        }
    }

    /**
     * Keeps a farmer's claim on the job it is still walking to or working.
     * A claim used to last {@link #RESERVATION_TICKS} from the moment it was
     * handed out and then lapse while the farmer was still on its way (or be
     * dropped by a block change there, unknown to the farmer), so a second
     * farmer took the same plant and both worked it. A lapsed claim is taken
     * back while the spot is free. Returns false when another farmer holds
     * it now: the caller gives the job up.
     */
    public boolean renewClaim(UUID farmerId, Job job, long now) {
        if (job.type().isFetch()) return true;
        if (heldByOther(job.target(), farmerId, now)
                || job.fruitSpace() != null && heldByOther(job.fruitSpace(), farmerId, now)) return false;
        Job current = assignments.get(farmerId);
        if (current != null && (current.type() != job.type() || !current.target().equals(job.target()))) {
            release(farmerId);
        }
        Job renewed = new Job(job.farmId(), job.type(), job.target(), job.interactionPos(), job.fruitSpace(),
                job.plantingItem(), job.supplyAmount(), job.ordinaryFieldJob(), now + RESERVATION_TICKS);
        FarmSite site = farms.get(job.farmId());
        // Field jobs were claimed on their site; perennial, bone meal and compost work loose.
        boolean fieldJob = !job.type().isPerennial() && !job.type().isBoneMealWork();
        if (fieldJob && site != null) claim(farmerId, site, renewed);
        else claimLoose(farmerId, renewed);
        return true;
    }

    private boolean heldByOther(BlockPos pos, UUID farmerId, long now) {
        Reservation holder = reservations.get(pos.asLong());
        return holder != null && !holder.farmerId().equals(farmerId)
                && !VillagerPause.lapsed(holder.farmerId(), holder.expiresAt(), now);
    }

    /** Immediately invalidates ownership touched by a live block-change event. */
    public void onBlockChanged(BlockPos pos) {
        onBlockChanged(null, pos);
    }

    /**
     * A block-place event; the new block is already in the world. A bush or
     * pod placed on its own patch's spot only updates that spot. A farmer's
     * own planting fires this too, and re-surveyed the whole patch each time.
     * Break events stay on {@link #onBlockChanged(BlockPos)}: they fire
     * before the block is gone, so the spot cannot be read yet.
     */
    public void onBlockPlaced(@Nullable ServerLevel level, BlockPos pos) {
        onBlockChanged(level, pos);
    }

    private void onBlockChanged(@Nullable ServerLevel placedIn, BlockPos pos) {
        for (Map.Entry<UUID, Job> entry : new ArrayList<>(assignments.entrySet())) {
            Job job = entry.getValue();
            if (job.type().isFetch()) continue; // its target is the storage door
            if (job.target().equals(pos) || job.target().below().equals(pos)
                    || pos.equals(job.fruitSpace())) release(entry.getKey());
        }
        if (placedIn != null) perennials.onBlockPlaced(placedIn, pos);
        else perennials.onBlockChanged(pos);
        // A broken composter leaves the cached list at once; a new one is
        // found on the next refresh.
        if (composters.contains(pos)) compostersAt = Long.MIN_VALUE;
        forgetSoilMemoAround(pos);
        markNearbySitesDirty(pos, true);
    }

    /** A farmer picked {@code count} sweet berries: it may plant that many. */
    public void onBerriesPicked(UUID farmerId, int count) {
        if (count > 0) berryPlantAllowance.merge(farmerId, count, Integer::sum);
    }

    /**
     * Whether the farmer still carries berries it picked itself. The
     * allowance never exceeds what it carries: picked berries it ate or
     * deposited cannot be made up from food it fetched later.
     */
    private boolean mayPlantBerries(NpcEntity farmer) {
        Integer allowance = berryPlantAllowance.get(farmer.getNpcId());
        if (allowance == null) return false;
        int carried = countItem(farmer.getInventory(), net.minecraft.world.item.Items.SWEET_BERRIES);
        int left = Math.min(allowance, carried);
        if (left <= 0) berryPlantAllowance.remove(farmer.getNpcId());
        else if (left < allowance) berryPlantAllowance.put(farmer.getNpcId(), left);
        return left > 0;
    }

    /** True for cocoa and sweet berries: crops {@link FarmerPerennials} looks after. */
    public static boolean isPerennialCrop(BlockState state) {
        CropDefinition definition = CropDefinitionRegistry.byCropBlock(state.getBlock());
        return definition != null && definition.planting().perennial();
    }

    /**
     * A pod or bush grew; one outside every patch is a new patch to discover.
     * Only where the chunk scan can see it: glow berry vines deep in a cave
     * grow all the time, and each growth queued a priority scan that found
     * nothing and held back the normal scan.
     */
    public void onPerennialGrow(ServerLevel level, BlockPos pos, BlockState state) {
        if (perennials.onGrow(pos, state)) return;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) + 1;
        if (pos.getY() <= top && pos.getY() >= top - SURFACE_SCAN_DEPTH) discoverAt(pos);
    }

    /** A log a farmer's cocoa hangs on: lumberjacks must leave its tree standing. */
    public boolean isTendedCocoaLog(BlockPos pos) {
        return perennials.isTendedLog(pos);
    }

    @Nullable
    public net.minecraft.core.Direction podFacing(BlockPos pod) {
        return perennials.podFacing(pod);
    }

    /** Growth changes harvest work without invalidating a farmer's reservation. */
    public void onCropMatured(BlockPos pos) {
        if (!markNearbySitesDirty(pos, false)) discoverAt(pos);
    }

    /**
     * Any melon/pumpkin stem growth. A stem inside a known farm only matters
     * once it attaches (fruit placed); one outside every farm means a field
     * the round-robin scan has not reached yet, so its chunk jumps the queue.
     */
    public void onStemGrow(BlockPos pos, boolean attached) {
        if (attached) onCropMatured(pos);
        else if (farms.values().stream().noneMatch(site -> mayAffectSite(pos, site))) discoverAt(pos);
    }

    private void discoverAt(BlockPos pos) {
        priorityChunks.add(new ChunkKey(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /**
     * An age crop reaching full growth changes exactly one known tile from
     * planted to mature, so that tile is updated in place rather than paying
     * for a survey of the whole field. Anything the site did not already know
     * as planted falls back to the survey.
     */
    public void onCropMatured(BlockPos pos, BlockState state) {
        CropDefinition definition = CropDefinitionRegistry.byCropBlock(state.getBlock());
        if (definition == null || !CropDefinitionRegistry.isMature(state, definition)) {
            onCropMatured(pos);
            return;
        }
        boolean updated = false;
        for (FarmSite site : farms.values()) {
            if (!site.cropField || !site.planted.contains(pos)) continue;
            site.mature.add(pos.immutable());
            updated = true;
        }
        if (!updated) onCropMatured(pos);
    }

    public boolean seedCraftDue(UUID farmerId, long now) {
        return now >= nextSeedCraft.getOrDefault(farmerId, 0L);
    }

    public void delaySeedCraft(UUID farmerId, long until) {
        nextSeedCraft.put(farmerId, until);
    }

    /** Returns whether any known site covers {@code pos}. */
    private boolean markNearbySitesDirty(BlockPos pos, boolean validateUnknownChange) {
        boolean found = false;
        for (FarmSite site : farms.values()) {
            if (!mayAffectSite(pos, site)) continue;
            dirtySites.add(site.id);
            found = true;
        }
        // Keep the old fallback for changes before the relevant site is discovered.
        if (!found && validateUnknownChange) nextValidationAt = 0L;
        return found;
    }

    /** Includes the hydration footprint and divider columns around a site's water. */
    private static boolean mayAffectSite(BlockPos pos, FarmSite site) {
        for (BlockPos anchor : site.anchors) {
            if (Math.abs(pos.getX() - anchor.getX()) <= 8
                    && Math.abs(pos.getZ() - anchor.getZ()) <= 8
                    && Math.abs(pos.getY() - anchor.getY()) <= 3) return true;
        }
        for (BlockPos water : site.water) {
            if (Math.abs(pos.getX() - water.getX()) <= 4
                    && Math.abs(pos.getZ() - water.getZ()) <= 4
                    && Math.abs(pos.getY() - water.getY()) <= 2) return true;
        }
        return false;
    }

    public void clearAssignments() {
        reservations.clear();
        assignments.clear();
        supplyClaims.clear();
        recentHarvests.clear();
        nextSupplyCheck.clear();
        fetchedUnplanted.clear();
        refetchBlockedUntil.clear();
        restoredAwaitingFarmer.clear();
        for (FarmSite site : farms.values()) site.activeJobs.clear();
    }

    public void clear() {
        clearAssignments();
        observations.clear();
        farms.clear();
        dirtySites.clear();
        scanChunks.clear();
        chunkScan = null;
        soilMemo.clear();
        soilMemoByChunk.clear();
        soilMemoBuildingsSignature = Long.MIN_VALUE;
        lastMemoFullClearAt = Long.MIN_VALUE;
        failures.clear();
        retryAfter.clear();
        preferredFarm.clear();
        preferredPatch.clear();
        lastOnPatch.clear();
        restoredSites.clear();
        perennials.clear();
        perennialRebuildPending = false;
        boneMealGates.clear();
        berryPlantAllowance.clear();
        storagePresence.clear();
        nextCompostCheck.clear();
        hoeTrips.clear();
        requestNotes.clear();
        helpingTillers.clear();
        tillerDuty = null;
        tillerDutyAt = Long.MIN_VALUE;
        composters = Set.of();
        compostersAt = Long.MIN_VALUE;
        foodUrgencyAt = Long.MIN_VALUE;
        scanCursor = 0;
        validationCursor = 0;
        restoredScanCursor = -1;
        nextScanAt = Long.MIN_VALUE;
        nextValidationAt = Long.MIN_VALUE;
        boundsSignature = Long.MIN_VALUE;
        shutdownSnapshot = null;
    }

    /**
     * Server shutdown clears runtime state before the world is saved. Keep
     * what {@link #saveState} would have written, including jobs that farmer
     * entities release while unloading, so the save still carries it.
     */
    public void clearForShutdown() {
        CompoundTag snapshot = saveState();
        clear();
        shutdownSnapshot = snapshot;
    }

    private void expireAssignments(VillageManager village, long now) {
        for (Map.Entry<UUID, Job> entry : new ArrayList<>(assignments.entrySet())) {
            NpcEntity npc = findFarmer(village, entry.getKey());
            Job job = entry.getValue();
            // A job restored from the save waits for its farmer to load back
            // in, instead of being dropped on the first tick after a reload.
            if (npc == null && restoredAwaitingFarmer.contains(entry.getKey())
                    && job.expiresAt() > now) continue;
            if (npc != null) restoredAwaitingFarmer.remove(entry.getKey());
            if (VillagerPause.lapsed(entry.getKey(), job.expiresAt(), now) || npc == null || !npc.isAlive() || npc.isRemoved()
                    || npc.getProfession() != Profession.FARMER
                    || !village.getLevel().hasChunkAt(job.target())) release(entry.getKey());
        }
        reservations.entrySet().removeIf(entry ->
                VillagerPause.lapsed(entry.getValue().farmerId(), entry.getValue().expiresAt(), now));
        retryAfter.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    @Nullable
    private static NpcEntity findFarmer(VillageManager village, UUID id) {
        // One map lookup, not a walk of every villager for each job each tick (same answer: ids are keys).
        VillagerModel model = village.getModel(id);
        return model != null ? model.puppet() : null;
    }

    private void validateOneFarm(ServerLevel level, VillageManager village, long now) {
        if (farms.isEmpty()) return;
        List<FarmSite> values = new ArrayList<>(farms.values());
        if (validationCursor >= values.size()) validationCursor = 0;
        FarmSite site = values.get(validationCursor++);
        refreshAndValidate(level, village, site, now);
    }

    /**
     * Re-surveys the first dirty site that has not been surveyed recently. A
     * site touched again inside the cooldown stays dirty and is picked up once
     * it expires, so a busy field costs one survey a second rather than one a
     * tick. Jobs re-check the live block before acting, so the short lag is safe.
     */
    private void validateDirtyFarm(ServerLevel level, VillageManager village, long now) {
        if (dirtySites.isEmpty()) return;
        Iterator<UUID> iterator = dirtySites.iterator();
        while (iterator.hasNext()) {
            FarmSite site = farms.get(iterator.next());
            if (site == null) {
                iterator.remove();
                continue;
            }
            if (now - site.lastValidation < DIRTY_REVALIDATION_COOLDOWN_TICKS) continue;
            iterator.remove();
            refreshAndValidate(level, village, site, now);
            return;
        }
    }

    private void refreshAndValidate(ServerLevel level, VillageManager village, FarmSite site, long now) {
        if (site.anchors.stream().noneMatch(level::hasChunkAt)) return;
        if (site.lastValidation != now) surveySite(level, village, site, now);
        for (Map.Entry<UUID, Job> entry : new ArrayList<>(site.activeJobs.entrySet())) {
            if (!validateJob(level, entry.getValue())) release(entry.getKey());
        }
    }

    /** Whether {@code farmerId} holds a job, e.g. one restored from the save. */
    public boolean hasAssignment(UUID farmerId) {
        return assignments.containsKey(farmerId);
    }

    public FarmerHoeTrips hoeTrips() {
        return hoeTrips;
    }

    /**
     * Diary line for a request that came back empty: what the manager could
     * see, so a farmer that keeps dropping out of work leaves a reason.
     */
    public String describeNoJob(ServerLevel level, VillageManager village, NpcEntity farmer) {
        FarmerSettings settings = FarmerSettings.of(farmer);
        long now = level.getGameTime();
        boolean shears = hasHarvestTool(farmer.getInventory(), settings, HarvestTool.SHEARS);
        boolean axe = hasHarvestTool(farmer.getInventory(), settings, HarvestTool.AXE);
        int fruits = 0, fruitsFree = 0, fruitsAllowed = 0, stems = 0, mature = 0, tilled = 0;
        for (FarmSite site : farms.values()) {
            for (BlockPos pos : site.planted) {
                CropDefinition d = CropDefinitionRegistry.byCropBlock(level.getBlockState(pos).getBlock());
                if (d != null && d.maturity() instanceof CropMaturity.AttachedFruit) stems++;
            }
            for (BlockPos fruit : site.fruits) {
                fruits++;
                if (available(fruit, now)) fruitsFree++;
                CropDefinition d = FarmSiteSurvey.fruitDefinitionFor(level.getBlockState(fruit).getBlock());
                if (d != null && cropAllowed(settings, d)) fruitsAllowed++;
            }
            mature += site.mature.size();
            tilled += site.tilled.size();
        }
        long stemObservations = observations.values().stream()
                .filter(o -> o.type() == ObservationType.STEM).count();
        StringBuilder sites = new StringBuilder();
        for (FarmSite site : farms.values()) {
            BlockPos anchor = site.anchors.stream().min(FarmSiteSurvey.POSITION_ORDER).orElse(null);
            sites.append(' ').append(anchor == null ? "?" : anchor.toShortString())
                    .append(site.cropField ? "" : "(notField)");
        }
        return "FARM no job: farms=" + farms.size() + " stems=" + stems + " fruits=" + fruits
                + " (free=" + fruitsFree + " allowed=" + fruitsAllowed + ") axe=" + axe + " shears=" + shears
                + (!axe && fruits > 0 ? " [ripe melons/pumpkins wait: no axe]" : "")
                + (!shears && perennials.ripeNeeding(HarvestTool.SHEARS) > 0
                    ? " [" + perennials.ripeNeeding(HarvestTool.SHEARS) + " ripe berries/pods/glow berries wait: no shears]" : "")
                + " mature=" + mature + " emptyFarmland=" + tilled
                + " | stemsSeenByScan=" + stemObservations + " scan=" + scanCursor + "/" + scanChunks.size()
                + " queued=" + priorityChunks.size() + " | sites:" + sites
                + " | " + perennials.describe()
                + " leftToSpecialists=" + leftToSpecialists(village, settings, now)
                + " | planting=" + (settings.turnOffPlanting() ? "off" : "on")
                + " harvesting=" + (settings.turnOffHarvesting() ? "off" : "on")
                + " tilling=" + (settings.tilling() ? "on" : "off")
                + (settings.tillsForVillage() ? " tillsFor=" + describeVillagePlanting(level, village, now)
                    : tillerDuty(level, village, now).tillers() > 0
                        ? " leftToTiller=" + !tillsNow(level, village, farmer, settings, now)
                            + " toTill=" + tillerDuty(level, village, now).toTill() : "")
                + " boneMeal=" + (settings.boneMealOnly() ? "only " : "") + (settings.useBoneMeal() ? "on" : "off")
                + " carried=" + FarmerBoneMeal.carried(farmer.getInventory())
                + " compost=" + (settings.compost() ? "on" : "off") + " composters=" + composters.size();
    }

    /**
     * The irrigation water of every known farm, as last surveyed: where a
     * farmer fills a bucket for a cauldron first.
     */
    public List<BlockPos> waterPositions() {
        List<BlockPos> out = new ArrayList<>();
        for (FarmSite site : farms.values()) out.addAll(site.water);
        return out;
    }

    public boolean hasWork(ServerLevel level, VillageManager village, NpcEntity farmer) {
        if (farmer.getProfession() != Profession.FARMER) return false;
        FarmerSettings settings = FarmerSettings.of(farmer);
        if (!settings.works()) return false;
        Job assignment = assignments.get(farmer.getNpcId());
        if (assignment != null && assignment.expiresAt() > level.getGameTime()) return true;
        if (settings.boneMealOnly()) return boneMealOnlyHasWork(level, village, farmer, settings);
        if (farms.isEmpty() && perennials.isEmpty()) return false;
        // Only work requestJob can really hand out. A ripe crop set to Leave,
        // or empty farmland with no seed to plant, started sessions that
        // found nothing and quit at once, over and over, and kept the farmer
        // from refilling cauldrons (its fallback work when it has none).
        // The same for bare soil without a hoe, with Tilling off, or left to a
        // tiller that keeps up: requestJob never tills then.
        long now = level.getGameTime();
        boolean canCutFruit = hasHarvestTool(farmer.getInventory(), settings, HarvestTool.AXE);
        boolean planting = !settings.turnOffPlanting();
        boolean canTill = tillsNow(level, village, farmer, settings, now);
        Set<CropDefinition> suppliesChecked = new HashSet<>();
        boolean blankChecked = false;
        // A tiller: the soil requestJob would hand it, judged by the village's planters.
        if (canTill && settings.tillsForVillage()
                && villageTillingWaiting(level, village, farmer, settings, now)) return true;
        for (FarmSite site : farms.values()) {
            if (canCutFruit && fruitWaiting(level, settings, site)) return true;
            if (harvestWaiting(level, village.getGraph(), settings, site)) return true;
            if (!planting) continue;
            for (Set<BlockPos> plots : canTill ? List.of(site.tilled, site.tillable) : List.of(site.tilled)) {
                for (BlockPos tile : plots) {
                    if (isFruitSlot(site, tile)) continue;
                    CropDefinition crop = site.inferredCrop.get(tile);
                    if (crop == null) {
                        if (blankChecked) continue;
                        blankChecked = true;
                        for (CropDefinition blank : CropDefinitionRegistry.all()) {
                            if (blank.planting() instanceof CropPlantingStrategy.Farmland
                                    && plantAllowed(settings, blank)
                                    && hasPlantingSupply(level, village, farmer, blank, now)) return true;
                        }
                    } else if (suppliesChecked.add(crop) && plantAllowed(settings, crop)
                            && hasPlantingSupply(level, village, farmer, crop, now)) {
                        return true;
                    }
                }
            }
        }
        if (perennials.hasWork(settings, farmer.getInventory(), target -> available(target, now),
                mayPlantBerries(farmer), leftToSpecialists(village, settings, now))) return true;
        if (boneMealWorkDue(level, village, farmer, settings, now)) return true;
        return settings.compost() && level.getGameTime() >= nextCompostCheck.getOrDefault(
                farmer.getNpcId(), Long.MIN_VALUE) && compostWorkAvailable(level, village);
    }

    /** A ripe crop this farmer may harvest, by the same rules as {@link #harvestCandidate}. */
    private static boolean harvestWaiting(ServerLevel level, @Nullable NavGraph graph,
                                          FarmerSettings settings, FarmSite site) {
        if (settings.turnOffHarvesting()) return false;
        for (BlockPos tile : site.mature) {
            if (!level.hasChunkAt(tile)) continue;
            BlockState state = level.getBlockState(tile);
            CropDefinition crop = CropDefinitionRegistry.byCropBlock(state.getBlock());
            if (crop == null || !cropAllowed(settings, crop)) continue;
            if (crop.harvest() instanceof CropHarvestStrategy.BreakAndReplant) return true;
            if (site.stackBases.containsKey(tile) && isCaneGround(level.getBlockState(tile.below()))
                    && level.getBlockState(tile.above()).getBlock() == state.getBlock()
                    && caneBaseReachable(level, graph, tile)) return true;
        }
        return false;
    }

    /** A grown melon or pumpkin this farmer may cut (it has the axe). */
    private static boolean fruitWaiting(ServerLevel level, FarmerSettings settings, FarmSite site) {
        if (settings.turnOffHarvesting()) return false;
        for (BlockPos fruit : site.fruits) {
            if (!level.hasChunkAt(fruit)) continue;
            CropDefinition definition = FarmSiteSurvey.fruitDefinitionFor(level.getBlockState(fruit).getBlock());
            if (definition != null && cropAllowed(settings, definition)) return true;
        }
        return false;
    }

    /**
     * Something to plant {@code crop} with: its seed carried or in storage,
     * or, for melon and pumpkin, fruit to make seeds from when that is due.
     */
    private boolean hasPlantingSupply(ServerLevel level, VillageManager village, NpcEntity farmer,
                                      CropDefinition crop, long now) {
        Item seed = crop.representativePlantingItem().orElse(null);
        if (seed == null) return false;
        InventoryManager inventory = farmer.getInventory();
        if (inventory.contains(seed) || storageHas(level, village, seed, now)) return true;
        Item fruit = FarmerSeedCrafting.fruitFor(seed);
        return fruit != null && seedCraftDue(farmer.getNpcId(), now)
                && (inventory.contains(fruit) || storageHas(level, village, fruit, now));
    }

    private record StoragePresence(boolean present, long at) {}

    /** Whether any storage holds {@code item}, re-read at most every 10 s per item for all farmers. */
    private final Map<Item, StoragePresence> storagePresence = new HashMap<>();
    private static final int STORAGE_PRESENCE_TICKS = 200;

    private boolean storageHas(ServerLevel level, VillageManager village, Item item, long now) {
        StoragePresence cached = storagePresence.get(item);
        if (cached != null && now - cached.at() >= 0 && now - cached.at() < STORAGE_PRESENCE_TICKS) {
            return cached.present();
        }
        boolean present = false;
        for (Building building : village.getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()
                    && !storage.getFunctionalBlocks().isEmpty() && storage.countItem(level, item) > 0) {
                present = true;
                break;
            }
        }
        storagePresence.put(item, new StoragePresence(present, now));
        return present;
    }

    private boolean compostAvailable;
    private long compostAvailableAt = Long.MIN_VALUE;

    /**
     * Whether any composter could use a visit: one ready to empty, or one
     * with room while storage holds scraps to spare. Shared by every farmer
     * and re-read at most every half minute, since it reads all the chests.
     */
    private boolean compostWorkAvailable(ServerLevel level, VillageManager village) {
        long now = level.getGameTime();
        if (compostAvailableAt != Long.MIN_VALUE && now - compostAvailableAt < COMPOST_CHECK_TICKS) {
            return compostAvailable;
        }
        compostAvailableAt = now;
        compostAvailable = false;
        boolean room = false;
        for (BlockPos composter : composters(level, village, now)) {
            if (!level.hasChunkAt(composter)) continue;
            int fill = FarmerCompost.fill(level.getBlockState(composter));
            if (fill == ComposterBlock.READY && !FarmerCompost.hopperBelow(level, composter)) {
                compostAvailable = true;
                return true;
            }
            room |= fill >= 0 && fill < ComposterBlock.MAX_LEVEL;
        }
        compostAvailable = room && FarmerCompost.pickSurplus(level, village, supplyClaims) != null;
        return compostAvailable;
    }

    /**
     * Something to bone meal, and bone meal to do it with: carried, in
     * storage, as bones, or a composter to make it in.
     */
    private boolean boneMealOnlyHasWork(ServerLevel level, VillageManager village, NpcEntity farmer,
                                        FarmerSettings settings) {
        long now = level.getGameTime();
        if (!settings.useBoneMeal()) {
            // Fertilizing is off too: composting is all that is left, and when that is
            // off as well there is no work, so no empty sessions start.
            return settings.compost()
                    && now >= nextCompostCheck.getOrDefault(farmer.getNpcId(), Long.MIN_VALUE)
                    && compostWorkAvailable(level, village);
        }
        if (boneMealTarget(level, village, farmer, settings, now) == null) return false;
        InventoryManager inventory = farmer.getInventory();
        if (FarmerBoneMeal.carried(inventory) > 0 || countItem(inventory, net.minecraft.world.item.Items.BONE) > 0) {
            return true;
        }
        if (supplySource(level, village, farmer, net.minecraft.world.item.Items.BONE_MEAL, now) != null
                || supplySource(level, village, farmer, net.minecraft.world.item.Items.BONE, now) != null) {
            return true;
        }
        // Only real composting work: any composter at all started a session
        // that found nothing to do, every time the 10 s compost check came round.
        return settings.compost()
                && now >= nextCompostCheck.getOrDefault(farmer.getNpcId(), Long.MIN_VALUE)
                && compostWorkAvailable(level, village);
    }

    /**
     * What the away calculator ({@code economy/away/AwayFarm}) reads of
     * the farms: copies, from what is saved and what the latest scans saw.
     *
     * @param tiles       crop tiles (the crop's own cell, soil below): farmland ever tilled, crops and stems
     *                    seen; the cells kept empty for fruit are left out
     * @param crops       the crop last grown on a tile, for tiles empty now
     * @param fruitSpaces melon and pumpkin stem to the cell its fruit grows in
     * @param cane        every sugar cane block the scans saw (bases have no cane below)
     * @param berries     sweet berry bushes the scans saw
     * @param glowBerries glow berry vines the scans saw
     * @param pods        cocoa spots a farmer can reach, with the face of the log each hangs from
     */
    public record AwayField(Set<BlockPos> tiles, Map<BlockPos, CropDefinition> crops,
                            Map<BlockPos, BlockPos> fruitSpaces, Set<BlockPos> cane, Set<BlockPos> berries,
                            Set<BlockPos> glowBerries, Map<BlockPos, Direction> pods) {}

    public AwayField awayField() {
        Set<BlockPos> tiles = new LinkedHashSet<>();
        Map<BlockPos, CropDefinition> crops = new HashMap<>();
        Map<BlockPos, BlockPos> fruitSpaces = new LinkedHashMap<>();
        Set<BlockPos> keptForFruit = new HashSet<>();
        for (FarmSite site : farms.values()) {
            tiles.addAll(site.everFarmland);
            tiles.addAll(site.tilled);
            tiles.addAll(site.planted);
            tiles.addAll(site.mature);
            tiles.addAll(site.inferredCrop.keySet());
            tiles.addAll(site.fruitSpaces.keySet());
            crops.putAll(site.inferredCrop);
            fruitSpaces.putAll(site.fruitSpaces);
            keptForFruit.addAll(site.heldSoil);
            keptForFruit.addAll(site.fruitSpaces.values());
        }
        tiles.removeAll(keptForFruit);
        Set<BlockPos> cane = new LinkedHashSet<>();
        Set<BlockPos> berries = new LinkedHashSet<>();
        Set<BlockPos> glowBerries = new LinkedHashSet<>();
        for (Observation observation : observations.values()) {
            switch (observation.type()) {
                case CANE -> cane.add(observation.pos());
                case BERRY -> berries.add(observation.pos());
                case GLOW_BERRY -> glowBerries.add(observation.pos());
                default -> { }
            }
        }
        return new AwayField(tiles, crops, fruitSpaces, cane, berries, glowBerries, perennials.awayPods());
    }

    public Collection<FarmSnapshot> snapshots() {
        List<FarmSnapshot> result = new ArrayList<>();
        for (FarmSite site : farms.values()) {
            Map.Entry<UUID, Job> active = site.activeJobs.entrySet().stream().findFirst().orElse(null);
            result.add(new FarmSnapshot(site.id, Set.copyOf(site.water), Set.copyOf(site.hydration),
                    Set.copyOf(site.tillable), Set.copyOf(site.tilled), Set.copyOf(site.planted),
                    Set.copyOf(site.mature), Set.copyOf(site.blocked), Map.copyOf(site.fruitSpaces),
                    active != null ? active.getKey() : null, active != null ? active.getValue() : null,
                    site.lastScan, site.lastValidation, site.lastActivity));
        }
        return List.copyOf(result);
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    private static final String TAG_OBSERVED_POS = "observedPos";
    private static final String TAG_OBSERVED_TYPE = "observedType";
    private static final String TAG_OBSERVED_AT = "observedAt";
    private static final String TAG_FARMS = "farms";
    private static final String TAG_ID = "id";
    private static final String TAG_CROP_FIELD = "cropField";
    private static final String TAG_ANCHORS = "anchors";
    private static final String TAG_WATER = "water";
    private static final String TAG_FRUITS = "fruits";
    private static final String TAG_EVER_FARMLAND = "everFarmland";
    private static final String TAG_FRUIT_STEMS = "fruitStems";
    private static final String TAG_FRUIT_SPACES = "fruitSpaces";
    private static final String TAG_INFERRED_CROPS = "inferredCrops";
    private static final String TAG_CROP = "crop";
    private static final String TAG_TILES = "tiles";
    private static final String TAG_LAST_SCAN = "lastScan";
    private static final String TAG_LAST_VALIDATION = "lastValidation";
    private static final String TAG_LAST_ACTIVITY = "lastActivity";
    private static final String TAG_ASSIGNMENTS = "assignments";
    private static final String TAG_FARMER = "farmer";
    private static final String TAG_FARM = "farm";
    private static final String TAG_TYPE = "type";
    private static final String TAG_TARGET = "target";
    private static final String TAG_STAND = "stand";
    private static final String TAG_FRUIT_SPACE = "fruitSpace";
    private static final String TAG_ITEM = "item";
    private static final String TAG_SUPPLY_AMOUNT = "supplyAmount";
    private static final String TAG_ORDINARY = "ordinaryFieldJob";
    private static final String TAG_EXPIRES_AT = "expiresAt";
    private static final String TAG_PREFERRED = "preferredFarms";
    private static final String TAG_RESTORATIONS = "restorations";
    private static final String TAG_FAILURE_POS = "failurePos";
    private static final String TAG_FAILURE_COUNT = "failureCount";
    private static final String TAG_RETRY_POS = "retryPos";
    private static final String TAG_RETRY_AT = "retryAt";
    private static final String TAG_SCAN_CURSOR = "scanCursor";
    private static final String TAG_VALIDATION_CURSOR = "validationCursor";
    private static final String TAG_PERENNIALS = "perennials";

    /**
     * Everything needed to continue after a reload exactly where the farms
     * left off: discovered tiles, each farm's memory (reverted farmland,
     * intended crops, fruit slots) and each farmer's in-progress job.
     */
    public CompoundTag saveState() {
        if (shutdownSnapshot != null) return shutdownSnapshot.copy();
        CompoundTag tag = new CompoundTag();
        long[] observedPos = new long[observations.size()];
        byte[] observedType = new byte[observations.size()];
        long[] observedAt = new long[observations.size()];
        int index = 0;
        for (Observation observation : observations.values()) {
            observedPos[index] = observation.pos().asLong();
            observedType[index] = (byte) observation.type().ordinal();
            observedAt[index] = observation.seenAt();
            index++;
        }
        tag.putLongArray(TAG_OBSERVED_POS, observedPos);
        tag.putByteArray(TAG_OBSERVED_TYPE, observedType);
        tag.putLongArray(TAG_OBSERVED_AT, observedAt);

        ListTag farmsTag = new ListTag();
        for (FarmSite site : farms.values()) farmsTag.add(writeSite(site));
        tag.put(TAG_FARMS, farmsTag);

        ListTag jobsTag = new ListTag();
        for (Map.Entry<UUID, Job> entry : assignments.entrySet()) {
            CompoundTag jobTag = writeJob(entry.getValue());
            jobTag.putUUID(TAG_FARMER, entry.getKey());
            jobsTag.add(jobTag);
        }
        tag.put(TAG_ASSIGNMENTS, jobsTag);

        ListTag preferredTag = new ListTag();
        for (Map.Entry<UUID, UUID> entry : preferredFarm.entrySet()) {
            CompoundTag preferred = new CompoundTag();
            preferred.putUUID(TAG_FARMER, entry.getKey());
            preferred.putUUID(TAG_FARM, entry.getValue());
            preferredTag.add(preferred);
        }
        tag.put(TAG_PREFERRED, preferredTag);

        ListTag restorationTag = new ListTag();
        for (Map.Entry<UUID, Restoration> entry : recentHarvests.entrySet()) {
            Restoration restoration = entry.getValue();
            CompoundTag pending = new CompoundTag();
            pending.putUUID(TAG_FARMER, entry.getKey());
            pending.putUUID(TAG_FARM, restoration.farmId());
            pending.putLong(TAG_TARGET, restoration.target().asLong());
            pending.putString(TAG_ITEM, BuiltInRegistries.ITEM.getKey(restoration.seed()).toString());
            restorationTag.add(pending);
        }
        tag.put(TAG_RESTORATIONS, restorationTag);

        long[] failurePos = new long[failures.size()];
        int[] failureCount = new int[failures.size()];
        index = 0;
        for (Map.Entry<Long, Integer> entry : failures.entrySet()) {
            failurePos[index] = entry.getKey();
            failureCount[index++] = entry.getValue();
        }
        tag.putLongArray(TAG_FAILURE_POS, failurePos);
        tag.putIntArray(TAG_FAILURE_COUNT, failureCount);
        long[] retryPos = new long[retryAfter.size()];
        long[] retryAt = new long[retryAfter.size()];
        index = 0;
        for (Map.Entry<Long, Long> entry : retryAfter.entrySet()) {
            retryPos[index] = entry.getKey();
            retryAt[index++] = entry.getValue();
        }
        tag.putLongArray(TAG_RETRY_POS, retryPos);
        tag.putLongArray(TAG_RETRY_AT, retryAt);
        tag.putInt(TAG_SCAN_CURSOR, scanCursor);
        tag.putInt(TAG_VALIDATION_CURSOR, validationCursor);
        tag.put(TAG_PERENNIALS, perennials.save());
        return tag;
    }

    /**
     * Drops saved water observations below the top {@link #MAX_WATER_LAYERS}
     * of their column. Saves from before that limit hold the whole 12-deep
     * scan; without this, the first rescan of every such chunk would look
     * like a change and rebuild every site, chunk after chunk.
     */
    private void trimDeepWater() {
        Map<Long, List<Integer>> waterYByColumn = new HashMap<>();
        for (Observation observation : observations.values()) {
            if (!isWater(observation)) continue;
            BlockPos pos = observation.pos();
            waterYByColumn.computeIfAbsent(ChunkPos.asLong(pos.getX(), pos.getZ()),
                    ignored -> new ArrayList<>()).add(pos.getY());
        }
        for (Map.Entry<Long, List<Integer>> entry : waterYByColumn.entrySet()) {
            List<Integer> ys = entry.getValue();
            if (ys.size() <= MAX_WATER_LAYERS) continue;
            ys.sort(Comparator.reverseOrder());
            int x = ChunkPos.getX(entry.getKey());
            int z = ChunkPos.getZ(entry.getKey());
            for (int i = MAX_WATER_LAYERS; i < ys.size(); i++) {
                observations.remove(BlockPos.asLong(x, ys.get(i), z));
            }
        }
    }

    private static boolean isWater(@Nullable Observation observation) {
        return observation != null && (observation.type() == ObservationType.WATER
                || observation.type() == ObservationType.OPEN_WATER);
    }

    /**
     * Re-types each {@code WATER} observation in {@code scanned} as
     * {@code OPEN_WATER} when every other position in its 9x9 at its own level
     * is a known water source too, and back when not.
     *
     * <p>Such water can neither border a field nor hydrate one: hydration
     * pairs water with soil within 4 blocks at the water's level or the one
     * below, and both the soil and the tile above it would be non-water
     * positions inside that 9x9. {@link #plausibleFarmSoil} rejects it as
     * well, since it has source neighbours. So site rebuilds skip it. Beside
     * an ocean that is nearly all the water; only the shore band is left. A
     * position nobody has scanned counts as not water, so unknown ground
     * never hides real shore.
     *
     * @param others observations outside {@code scanned}, or null if
     *               {@code scanned} is all of them
     */
    private static void classifyOpenWater(Map<Long, Observation> scanned,
                                          @Nullable Map<Long, Observation> others) {
        List<Observation> retyped = new ArrayList<>();
        for (Observation observation : scanned.values()) {
            if (!isWater(observation)) continue;
            BlockPos pos = observation.pos();
            boolean open = true;
            for (int dx = -4; dx <= 4 && open; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long key = BlockPos.asLong(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                    Observation near = scanned.get(key);
                    if (near == null && others != null) near = others.get(key);
                    if (!isWater(near)) {
                        open = false;
                        break;
                    }
                }
            }
            ObservationType type = open ? ObservationType.OPEN_WATER : ObservationType.WATER;
            if (observation.type() != type) {
                retyped.add(new Observation(pos, type, observation.seenAt()));
            }
        }
        for (Observation observation : retyped) scanned.put(observation.pos().asLong(), observation);
    }

    /**
     * Replaces all state with a {@link #saveState} snapshot. Restored jobs
     * are reserved again, so each farmer's next {@link #requestJob} hands back
     * the job it was busy with. An empty tag (older save) leaves the manager empty.
     */
    public void restoreState(@Nullable CompoundTag tag) {
        clear();
        if (tag == null || tag.isEmpty()) return;
        long[] observedPos = tag.getLongArray(TAG_OBSERVED_POS);
        byte[] observedType = tag.getByteArray(TAG_OBSERVED_TYPE);
        long[] observedAt = tag.getLongArray(TAG_OBSERVED_AT);
        ObservationType[] types = ObservationType.values();
        int observed = Math.min(observedPos.length, Math.min(observedType.length, observedAt.length));
        for (int i = 0; i < observed; i++) {
            int ordinal = observedType[i];
            if (ordinal < 0 || ordinal >= types.length) continue;
            observations.put(observedPos[i],
                    new Observation(BlockPos.of(observedPos[i]), types[ordinal], observedAt[i]));
        }
        trimDeepWater();
        classifyOpenWater(observations, null);

        ListTag farmsTag = tag.getList(TAG_FARMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < farmsTag.size(); i++) {
            FarmSite site = readSite(farmsTag.getCompound(i));
            if (site == null || site.anchors.isEmpty()) continue;
            farms.put(site.id, site);
            restoredSites.put(site.id, false);
        }

        ListTag jobsTag = tag.getList(TAG_ASSIGNMENTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < jobsTag.size(); i++) {
            CompoundTag jobTag = jobsTag.getCompound(i);
            if (!jobTag.hasUUID(TAG_FARMER)) continue;
            Job job = readJob(jobTag);
            if (job == null) continue;
            UUID farmerId = jobTag.getUUID(TAG_FARMER);
            FarmSite site = farms.get(job.farmId());
            if (job.type() == JobType.FETCH_SUPPLIES) {
                assignments.put(farmerId, job);
                if (site != null) site.activeJobs.put(farmerId, job);
                if (job.plantingItem() != null) {
                    supplyClaims.merge(job.plantingItem(), job.supplyAmount(), Integer::sum);
                }
            } else if (site != null) {
                claim(farmerId, site, job);
            } else {
                continue;
            }
            restoredAwaitingFarmer.add(farmerId);
        }

        ListTag preferredTag = tag.getList(TAG_PREFERRED, Tag.TAG_COMPOUND);
        for (int i = 0; i < preferredTag.size(); i++) {
            CompoundTag preferred = preferredTag.getCompound(i);
            if (preferred.hasUUID(TAG_FARMER) && preferred.hasUUID(TAG_FARM)) {
                preferredFarm.put(preferred.getUUID(TAG_FARMER), preferred.getUUID(TAG_FARM));
            }
        }
        ListTag restorationTag = tag.getList(TAG_RESTORATIONS, Tag.TAG_COMPOUND);
        for (int i = 0; i < restorationTag.size(); i++) {
            CompoundTag pending = restorationTag.getCompound(i);
            if (!pending.hasUUID(TAG_FARMER) || !pending.hasUUID(TAG_FARM)
                    || !pending.contains(TAG_TARGET) || !pending.contains(TAG_ITEM)) continue;
            UUID farmId = pending.getUUID(TAG_FARM);
            ResourceLocation itemId = ResourceLocation.tryParse(pending.getString(TAG_ITEM));
            Item seed = itemId == null ? null : BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
            if (farms.containsKey(farmId) && seed != null) {
                recentHarvests.put(pending.getUUID(TAG_FARMER),
                        new Restoration(farmId, BlockPos.of(pending.getLong(TAG_TARGET)), seed));
            }
        }
        long[] failurePos = tag.getLongArray(TAG_FAILURE_POS);
        int[] failureCount = tag.getIntArray(TAG_FAILURE_COUNT);
        for (int i = 0; i < Math.min(failurePos.length, failureCount.length); i++) {
            failures.put(failurePos[i], failureCount[i]);
        }
        long[] retryPos = tag.getLongArray(TAG_RETRY_POS);
        long[] retryAt = tag.getLongArray(TAG_RETRY_AT);
        for (int i = 0; i < Math.min(retryPos.length, retryAt.length); i++) {
            retryAfter.put(retryPos[i], retryAt[i]);
        }
        restoredScanCursor = tag.getInt(TAG_SCAN_CURSOR);
        validationCursor = tag.getInt(TAG_VALIDATION_CURSOR);
        perennials.restore(tag.getCompound(TAG_PERENNIALS));
        // Patches are rebuilt from the pod memory and the saved observations
        // once the first chunk scan reports a change; ask for one now.
        if (observations.values().stream().anyMatch(o -> o.type() == ObservationType.COCOA
                || o.type() == ObservationType.BERRY || o.type() == ObservationType.GLOW_BERRY) || !tag.getCompound(TAG_PERENNIALS).isEmpty()) {
            perennialRebuildPending = true;
        }
    }

    /** Set on load: rebuild the perennial patches on the first tick the village can survey. */
    private boolean perennialRebuildPending;

    /**
     * A restored site has its memory but none of its tile sets until the
     * world around it is read again. Survey it as soon as part of it is
     * loaded, and once more when all of it is, so restored jobs validate.
     *
     * <p>One site per tick. After a join every farm's chunks are loaded at
     * once, and surveying all of them in that one tick was 37 ms for five
     * farms; a farm that comes back a few ticks later costs nobody anything.
     */
    private void surveyRestoredSites(ServerLevel level, VillageManager village, long now) {
        if (restoredSites.isEmpty()) return;
        Iterator<Map.Entry<UUID, Boolean>> iterator = restoredSites.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Boolean> entry = iterator.next();
            FarmSite site = farms.get(entry.getKey());
            if (site == null) {
                iterator.remove();
                continue;
            }
            boolean anyLoaded = false;
            boolean allLoaded = true;
            for (BlockPos anchor : site.anchors) {
                if (level.hasChunkAt(anchor)) anyLoaded = true;
                else allLoaded = false;
            }
            if (allLoaded) {
                surveySite(level, village, site, now);
                iterator.remove();
                return;
            }
            if (anyLoaded && !entry.getValue()) {
                surveySite(level, village, site, now);
                entry.setValue(true);
                return;
            }
        }
    }

    private static CompoundTag writeSite(FarmSite site) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, site.id);
        tag.putBoolean(TAG_CROP_FIELD, site.cropField);
        tag.putLongArray(TAG_ANCHORS, positions(site.anchors));
        tag.putLongArray(TAG_WATER, positions(site.water));
        tag.putLongArray(TAG_FRUITS, positions(site.fruits));
        tag.putLongArray(TAG_EVER_FARMLAND, positions(site.everFarmland));
        tag.putLongArray(TAG_FRUIT_STEMS, positions(site.fruitSpaces.keySet()));
        tag.putLongArray(TAG_FRUIT_SPACES, positions(site.fruitSpaces.values()));
        Map<ResourceLocation, List<BlockPos>> tilesByCrop = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, CropDefinition> entry : site.inferredCrop.entrySet()) {
            tilesByCrop.computeIfAbsent(entry.getValue().id(), id -> new ArrayList<>()).add(entry.getKey());
        }
        ListTag cropsTag = new ListTag();
        for (Map.Entry<ResourceLocation, List<BlockPos>> entry : tilesByCrop.entrySet()) {
            CompoundTag cropTag = new CompoundTag();
            cropTag.putString(TAG_CROP, entry.getKey().toString());
            cropTag.putLongArray(TAG_TILES, positions(entry.getValue()));
            cropsTag.add(cropTag);
        }
        tag.put(TAG_INFERRED_CROPS, cropsTag);
        tag.putLong(TAG_LAST_SCAN, site.lastScan);
        tag.putLong(TAG_LAST_VALIDATION, site.lastValidation);
        tag.putLong(TAG_LAST_ACTIVITY, site.lastActivity);
        return tag;
    }

    @Nullable
    private static FarmSite readSite(CompoundTag tag) {
        if (!tag.hasUUID(TAG_ID)) return null;
        FarmSite site = new FarmSite(tag.getUUID(TAG_ID));
        site.cropField = tag.getBoolean(TAG_CROP_FIELD);
        for (long pos : tag.getLongArray(TAG_ANCHORS)) site.anchors.add(BlockPos.of(pos));
        for (long pos : tag.getLongArray(TAG_WATER)) site.water.add(BlockPos.of(pos));
        for (long pos : tag.getLongArray(TAG_FRUITS)) site.fruits.add(BlockPos.of(pos));
        for (long pos : tag.getLongArray(TAG_EVER_FARMLAND)) site.everFarmland.add(BlockPos.of(pos));
        long[] stems = tag.getLongArray(TAG_FRUIT_STEMS);
        long[] spaces = tag.getLongArray(TAG_FRUIT_SPACES);
        for (int i = 0; i < Math.min(stems.length, spaces.length); i++) {
            site.fruitSpaces.put(BlockPos.of(stems[i]), BlockPos.of(spaces[i]));
        }
        ListTag cropsTag = tag.getList(TAG_INFERRED_CROPS, Tag.TAG_COMPOUND);
        for (int i = 0; i < cropsTag.size(); i++) {
            CompoundTag cropTag = cropsTag.getCompound(i);
            CropDefinition definition = cropById(cropTag.getString(TAG_CROP));
            if (definition == null) continue;
            for (long pos : cropTag.getLongArray(TAG_TILES)) site.inferredCrop.put(BlockPos.of(pos), definition);
        }
        site.lastScan = tag.getLong(TAG_LAST_SCAN);
        site.lastValidation = tag.getLong(TAG_LAST_VALIDATION);
        site.lastActivity = tag.getLong(TAG_LAST_ACTIVITY);
        return site;
    }

    private static CompoundTag writeJob(Job job) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_FARM, job.farmId());
        tag.putString(TAG_TYPE, job.type().name());
        tag.putLong(TAG_TARGET, job.target().asLong());
        if (job.interactionPos() != null) tag.putLong(TAG_STAND, job.interactionPos().asLong());
        if (job.fruitSpace() != null) tag.putLong(TAG_FRUIT_SPACE, job.fruitSpace().asLong());
        if (job.plantingItem() != null) {
            tag.putString(TAG_ITEM, BuiltInRegistries.ITEM.getKey(job.plantingItem()).toString());
        }
        tag.putInt(TAG_SUPPLY_AMOUNT, job.supplyAmount());
        tag.putBoolean(TAG_ORDINARY, job.ordinaryFieldJob());
        tag.putLong(TAG_EXPIRES_AT, job.expiresAt());
        return tag;
    }

    @Nullable
    private static Job readJob(CompoundTag tag) {
        if (!tag.hasUUID(TAG_FARM) || !tag.contains(TAG_TARGET)) return null;
        JobType type;
        try {
            type = JobType.valueOf(tag.getString(TAG_TYPE));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        Item item = null;
        if (tag.contains(TAG_ITEM)) {
            ResourceLocation itemId = ResourceLocation.tryParse(tag.getString(TAG_ITEM));
            item = itemId == null ? null : BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
            if (item == null) return null;
        }
        return new Job(tag.getUUID(TAG_FARM), type, BlockPos.of(tag.getLong(TAG_TARGET)),
                tag.contains(TAG_STAND) ? BlockPos.of(tag.getLong(TAG_STAND)) : null,
                tag.contains(TAG_FRUIT_SPACE) ? BlockPos.of(tag.getLong(TAG_FRUIT_SPACE)) : null,
                item, tag.getInt(TAG_SUPPLY_AMOUNT), tag.getBoolean(TAG_ORDINARY),
                tag.getLong(TAG_EXPIRES_AT));
    }

    @Nullable
    private static CropDefinition cropById(String id) {
        for (CropDefinition definition : CropDefinitionRegistry.all()) {
            if (definition.id().toString().equals(id)) return definition;
        }
        return null;
    }

    private static long[] positions(Collection<BlockPos> positions) {
        long[] result = new long[positions.size()];
        int index = 0;
        for (BlockPos pos : positions) result[index++] = pos.asLong();
        return result;
    }

    static UUID stableFarmId(UUID villageId, BlockPos anchor) {
        return FarmerPlanning.stableFarmId(villageId, anchor.asLong());
    }

    private static boolean inside(AABB bounds, BlockPos pos) {
        return insideXZ(bounds, pos.getX(), pos.getZ());
    }

    private static boolean insideXZ(AABB bounds, int x, int z) {
        return x >= Math.floor(bounds.minX) && x <= Math.floor(bounds.maxX)
                && z >= Math.floor(bounds.minZ) && z <= Math.floor(bounds.maxZ);
    }

    /**
     * Position-independent maturity check for ordinary (non-stacked) crops,
     * resolved generically through the live {@link CropDefinitionRegistry}
     * instead of a hard-coded {@code CropBlock}/pitcher-plant special case.
     */
    public static boolean isMatureCrop(BlockState state) {
        CropDefinition definition = CropDefinitionRegistry.byCropBlock(state.getBlock());
        return definition != null && CropDefinitionRegistry.isMature(state, definition);
    }

    private static void debug(String message, Object... args) {
        if (DEBUG) LOGGER.info("[FarmerManager] " + message, args);
    }
}
