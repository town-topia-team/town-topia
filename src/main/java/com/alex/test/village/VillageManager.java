package com.alex.test.village;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.entity.mayor.MayorSpawnManager;
import com.alex.test.DynamicVillages;
import com.alex.test.network.VillageBoundsPayload;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.knowledge.KnowledgeBase;
import com.alex.test.village.knowledge.KnowledgeBase.Category;
import com.alex.test.village.knowledge.KnowledgeScanner;
import com.alex.test.village.NavNode;
import java.util.ArrayList;
import java.util.List;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.visitors.VisitorSpawnManager;
import com.alex.test.village.visitors.NomadSpawnManager;
import com.alex.test.village.brain.GoHomeActivity;
import com.alex.test.village.breeding.BreedingManager;
import com.alex.test.village.raid.RaidManager;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.villagers.lumberjack.data.TreeScanner;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import com.alex.test.village.villagers.lumberjack.registry.LumberjackTreeRegistry;
import com.alex.test.village.villagers.farmer.FarmerManager;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Per-village authority: owns one {@link NavGraph}, a {@link KnowledgeBase},
 * a pool of {@link VillagerModel}s, and a priority task queue.
 *
 * <h3>Two strictly separated update channels</h3>
 * <dl>
 *   <dt>NavGraph</dt>
 *   <dd>Rebuilt when blocks are broken, placed, or grown into collision-changing
 *       features such as trees, after a 2-second debounce.</dd>
 *   <dt>KnowledgeBase</dt>
 *   <dd>Populated once at NavGraph build time via {@link KnowledgeScanner}, then
 *       kept current purely through world events:
 *       <ul>
 *         <li>{@code BlockGrowFeatureEvent}     sapling growing into a tree.</li>
 *         <li>{@code BlockEvent.CropGrowEvent.Post}     crop reaches max age.</li>
 *         <li>{@code BlockEvent.BreakEvent}     player / villager removes a resource.</li>
 *         <li>{@code EntityJoinLevelEvent}     item frame placed (room marker).</li>
 *       </ul>
 *   </dd>
 * </dl>
 *
 * <h3>Work model</h3>
 * Villager brains ({@link com.alex.test.village.brain.VillagerBrain}) decide what
 * to do and <em>pull</em> work: a brain claims a resource through the
 * owner-aware tree-job methods below.
 * and manages the job entirely through its activity system.  This manager only
 * maintains shared state (claims, knowledge, buildings) and ticks each brain.
 *
 * <h3>Components</h3>
 * This class is the village's public entry point and owns its tick order and
 * event routing. Larger areas of state live in package-private components:
 * <ul>
 *   <li>{@link VillageBuildings}: token frames, building discovery,
 *       restoration, validation and removal.</li>
 *   <li>{@link VillageBeds}: persistent bed assignments.</li>
 *   <li>{@link NavDirtyTracker}: changed columns and the NavGraph rebuild
 *       debounce.</li>
 * </ul>
 * Profession-specific state lives in {@link FarmerManager} and
 * {@link LumberjackTreeRegistry}.
 */
public final class VillageManager {

    /** Package-private so the village's components log under this same category. */
    static final Logger LOGGER = LogUtils.getLogger();

    /** How often to attempt to rebind missing puppet entities (3 s). */
    private static final int REBIND_INTERVAL = 60;
    /** Ticks between rolling building re-checks; see {@link VillageBuildings#revalidateNextBuilding}. */
    private static final int REVALIDATE_INTERVAL = 100;
    /** Limit full graph work to a small part of each server tick. */
    private static final int FULL_BUILD_NODES_PER_TICK = 512;
    /**
     * Most nodes one scheduled local update re-reads in a tick; changes past
     * that wait for the next tick (see {@link NavDirtyTracker#nextBatch}).
     */
    private static final int LOCAL_UPDATE_MAX_NODES = 300;
    /**
     * Most tiles one scheduled patch may fill. Ordinary patches create 100-300;
     * past this the fill is spreading over never-built ground and the sliced
     * full build takes over (about 60 ms of fill at the cap, against 1.3 s seen).
     */
    private static final int LOCAL_FILL_MAX_NODES =
            Integer.getInteger("dynamicvillages.nav.patchFillLimit", 8000);   // lower it to test the fallback
    /**
     * A village tick that has already taken this long does not start a nav
     * update on top: the update has waited ten seconds and can wait a tick
     * more, for at most {@link #NAV_BUSY_MAX_DEFERRALS} ticks.
     */
    private static final long NAV_BUSY_REBUILD_NANOS = 8_000_000L;
    private static final int NAV_BUSY_MAX_DEFERRALS = 5;
    /** As {@link #NAV_BUSY_REBUILD_NANOS} for the nav slices, which then skip that one tick. */
    private static final long NAV_BUSY_SLICES_NANOS = 12_000_000L;

    // =========================================================================
    // Singleton for the /villager graph scan command village
    // =========================================================================

    // Must stay above INSTANCE: the constructor's field initializers read it during class init.
    private static final String[] TICK_PHASE_NAMES = {
            "grows", "frames+restore", "existingScan", "dirtyBuildings", "farms", "mayor",
            "movement", "social", "brains", "navRebuild", "rebind", "spawners", "revalidate",
            "navBuild", "farBodies"};

    public static final VillageManager INSTANCE = new VillageManager();

    // =========================================================================
    // Identity
    // =========================================================================

    private final UUID villageId;
    private BlockPos bellPos;
    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private int graphChunkRadius = 9;

    // =========================================================================
    // Core state
    // =========================================================================

    private final Map<UUID, VillagerModel> allVillagers = new LinkedHashMap<>();
    /** Members that were captains when last seen; see {@link #captainIds}. */
    private final Set<UUID> knownCaptains = new HashSet<>();

    /** Maintains one persistent Mayor for every valid Town Hall. */
    private final MayorSpawnManager mayorSpawnManager;

    /** Pairs loitering villagers into conversations (visible-theatre only). */
    private final com.alex.test.village.brain.SocialCoordinator socialCoordinator =
            new com.alex.test.village.brain.SocialCoordinator();

    private NavGraph navGraph;
    private long graphVersion = 0L;
    private NavGraph.BuildTask pendingFullBuild;
    private long pendingFullBuildWorkNanos;
    private int pendingFullBuildTicks;

    /**
     * A map built over many ticks before this village was founded, for the
     * next {@link #initialize} from the same origin to use instead of building
     * its own in one go. Set by a generated village waking up near a player.
     */
    @Nullable private NavGraph prebuiltGraph;
    @Nullable private BlockPos prebuiltOrigin;
    /**
     * The prebuilt map is for a generated village founding itself: its only
     * building is the Town Hall just hung and it has no villagers yet, so the
     * whole-claim searches for both are skipped.
     */
    private boolean prebuiltForNewVillage;

    /**
     * Set while a saved village waits to be built: it was asleep when the
     * world loaded, so its map is drawn only once a player comes near, a slice
     * per tick (see {@link #deferInitialize}).
     */
    @Nullable private DeferredInit deferredInit;

    /** Chunks a slice-built map held loaded, let go a few per tick once its knowledge scan is done. */
    @Nullable private ChunkHold wakeHold;
    private long wakeHoldSince;
    /** Longest the chunks are kept once the village stands, whatever its scan is doing. */
    private static final long WAKE_HOLD_MAX_TICKS = 1200L;
    /** Chunks let go per tick, so they are not all saved and unloaded in one tick. */
    private static final int WAKE_HOLD_RELEASE_PER_TICK = 8;

    /** The knowledge scan still running in slices; see {@link #wakeUpWith}. */
    @Nullable private KnowledgeScanner.Scan pendingKnowledgeScan;
    private long pendingKnowledgeScanNanos;
    /** Time one tick may give the knowledge scan. */
    private static final long KNOWLEDGE_SLICE_NANOS = 4_000_000L;

    private final KnowledgeBase knowledgeBase = new KnowledgeBase();

    private final ClaimsRegistry claims = new ClaimsRegistry();

    /** Lumberjack-specific tree knowledge, claims, and retry state. */
    private final LumberjackTreeRegistry lumberjackTrees =
            new LumberjackTreeRegistry(knowledgeBase, () -> level, this::isProtectedTreePosition);

    /** Farmer-specific discovery, planning, reservations, and recovery. */
    private final FarmerManager farmerManager = new FarmerManager();

    /** Work tiles nobody in this village could reach, and when to retry them. */
    private final UnreachableWorkTiles unreachableWorkTiles = new UnreachableWorkTiles();

    // =========================================================================
    // Nav dirty tracking (break / place / growth events)
    // =========================================================================

    private final NavDirtyTracker navDirty = new NavDirtyTracker();

    /** Wall-clock cost of the most recent nav rebuild, for telemetry. */
    private long lastRebuildMillis = -1L;

    /** Whether that rebuild only re-read the columns that changed. */
    private boolean lastRebuildWasLocal = false;

    // =========================================================================
    // Deferred grow checks
    // =========================================================================

    /**
     * Sapling positions collected from {@code BlockGrowFeatureEvent} (which fires
     * <em>before</em> the tree is placed).  Processed on the next server tick,
     * by which time the tree blocks are in the world.
     */
    private final List<BlockPos> pendingGrowChecks = new ArrayList<>();

    /**
     * Saplings whose grown tree reached into an unloaded chunk, with the game
     * time first seen ({@code growretry}); see {@link #retryGrownTrees}.
     */
    private final Map<BlockPos, Long> growRetries = new LinkedHashMap<>();
    private long nextGrowRetry;
    private static final long GROW_RETRY_EVERY = 200L;
    private static final long GROW_RETRY_FOR = 6000L;
    /** More than this at once is a whole unloaded edge; the village scan finds those. */
    private static final int GROW_RETRY_MAX = 64;

    /**
     * Crop positions that were just bonemealed.  Vanilla's bonemeal path calls
     * {@code CropBlock.growCrops} directly and fires <em>no</em>
     * {@code CropGrowEvent.Post}, so a crop a player forces to maturity never
     * reaches the KnowledgeBase and no farmer can see it -- the crop knowledge
     * is seeded once at startup and maintained by that event alone.  Checked on
     * the next tick, because BonemealEvent fires before the growth is applied.
     */
    private final List<BlockPos> pendingBonemealChecks = new ArrayList<>();
    /**
     * Blocks a tool right-click was used on, with the state each had before: a
     * shovel making a path, a hoe tilling, an axe stripping a log. The tool
     * changes the block with a plain setBlock, which fires neither the break
     * nor the place event, so the nav graph kept grass where a path now lies.
     * The event fires before the change and also for clicks that change
     * nothing, so the next tick compares the block before refreshing anything.
     */
    private final Map<BlockPos, BlockState> pendingToolChecks = new LinkedHashMap<>();
    /**
     * Armor stands placed, broken, loaded or unloaded since the last tick. Entity
     * events fire while Minecraft walks a chunk's entity list, so the barracks
     * post rescan waits for the next village tick.
     */
    private final Set<BlockPos> pendingArmorStandChecks = new LinkedHashSet<>();

    // =========================================================================
    // Building system
    // =========================================================================

    /** Registered buildings, their saved markers, and queued frame/rebuild checks. */
    private final VillageBuildings buildings = new VillageBuildings(this);
    private int minerSideBranchDistance =
            com.alex.test.village.villagers.miner.settings.MinerSettingsSchema.DEFAULT_SIDE_BRANCH_DISTANCE;

    public int minerSideBranchDistance() { return minerSideBranchDistance; }

    public void setMinerSideBranchDistance(int value) {
        int clamped = Math.max(0, Math.min(
                com.alex.test.village.villagers.miner.settings.MinerSettingsSchema.MAX_SIDE_BRANCH_DISTANCE,
                value));
        if (minerSideBranchDistance == clamped) return;
        minerSideBranchDistance = clamped;
        markPersistentDataDirty();
        // Mines on "Village setting" change shape: everyone holding a plan replans.
        onMineLayoutChanged();
    }

    /** Each mine's own tunnel lengths and pause switch, by token frame id; saved with the village. */
    private final com.alex.test.village.villagers.miner.MineSettings.Book mineSettings =
            new com.alex.test.village.villagers.miner.MineSettings.Book();

    public com.alex.test.village.villagers.miner.MineSettings.Book mineSettings() { return mineSettings; }

    /** The shape this mine is dug to: its own settings, with the village's side length where it follows it. */
    public com.alex.test.village.villagers.miner.MineSettings.Shape mineShape(UUID mineId) {
        return mineSettings.get(mineId).resolve(minerSideBranchDistance);
    }

    /** The mine settings to save: only mines that still have a saved marker. */
    public CompoundTag saveMineSettings() {
        Set<UUID> mines = new HashSet<>();
        for (SavedBuilding marker : getSavedBuildings()) {
            if ("mine".equals(marker.type())) mines.add(marker.itemFrameId());
        }
        return mineSettings.save(mines);
    }

    /** Rules the player sets at the Mayor (Settings > Village rules); saved with the village. */
    private final VillageRules rules = new VillageRules();

    public VillageRules rules() { return rules; }

    /** Who owns the village (village claims); saved with the village, kept when it pauses or its token moves. */
    private final com.alex.test.village.claims.VillageClaim claim = new com.alex.test.village.claims.VillageClaim();

    public com.alex.test.village.claims.VillageClaim claim() { return claim; }
    /** Persistent bed assignments: one bed per villager, one villager per bed. */
    private final VillageBeds beds = new VillageBeds(this, buildings);

    /** Who stands in which building, for overcrowding; counted once a second. */
    private final VillageCrowding crowding = new VillageCrowding();
    /** The cauldrons farmers wash in, and what refills remember about them. */
    private final com.alex.test.village.building.CauldronIndex cauldrons =
            new com.alex.test.village.building.CauldronIndex();
    private Runnable persistentStateDirtyCallback = () -> {};
    /** Set by {@link VillageManagerRegistry}; defaults to "never dormant" for the singleton {@link #INSTANCE}. */
    private java.util.function.BooleanSupplier dormancyQuery = () -> false;

    /** {@code workSpot}: a Butchery's butcher's spot the player picked (10-24), null when automatic or not a Butchery. */
    public record SavedBuilding(
            UUID itemFrameId,
            String type,
            BlockPos doorPos,
            BlockPos itemFramePos,
            Direction interiorDirection,
            int housingCapacity,
            @javax.annotation.Nullable BlockPos workSpot) {
        public SavedBuilding(UUID itemFrameId, String type, BlockPos doorPos, BlockPos itemFramePos,
                             Direction interiorDirection, int housingCapacity) {
            this(itemFrameId, type, doorPos, itemFramePos, interiorDirection, housingCapacity, null);
        }
    }

    public record SavedBedAssignment(UUID villagerId, UUID buildingId, BlockPos bedPos, boolean barracksCaptain) {
        public SavedBedAssignment(UUID villagerId, UUID buildingId, BlockPos bedPos) {
            this(villagerId, buildingId, bedPos, false);
        }
    }

    // =========================================================================
    // Misc tick counters
    // =========================================================================

    private int rebindCounter = 0;
    private final NomadSpawnManager nomadSpawnManager = new NomadSpawnManager(this);
    /** Where staying visitors camp overnight, and what upsets them. */
    private final com.alex.test.village.visitors.TravelerCamp travelerCamp =
            new com.alex.test.village.visitors.TravelerCamp(this);
    private final VisitorSpawnManager visitorSpawnManager = new VisitorSpawnManager(this);
    private final BreedingManager breedingManager = new BreedingManager(this);
    private final RaidManager raidManager = new RaidManager(this);
    private int revalidateCounter = 0;
    /**
     * Result of the most recent offline-economy catch-up, or {@code null} if
     * this village has never gone dormant and woken back up. Not persisted --
     * it is only ever reported for test/debug inspection via {@code StateDump}.
     */
    private com.alex.test.village.economy.ReconciliationSummary lastReconciliation;
    // =========================================================================
    // Constructors
    // =========================================================================

    public VillageManager(UUID villageId, BlockPos bellPos, ServerLevel level) {
        this.villageId = villageId;
        this.bellPos   = bellPos;
        this.level     = level;
        this.dimension = level != null ? level.dimension() : null;
        this.mayorSpawnManager = new MayorSpawnManager(this);
    }

    VillageManager() {
        this(UUID.randomUUID(), null, null);
    }

    // =========================================================================
    // Initialisation
    // =========================================================================

    public void initialize() {
        if (level == null) return;
        doInitialize(level, bellPos != null ? bellPos : BlockPos.ZERO, graphChunkRadius);
    }

    public void initialize(ServerLevel sl, BlockPos center, int chunkRadius) {
        this.level            = sl;
        this.bellPos          = center;
        this.graphChunkRadius = chunkRadius;
        this.dimension        = sl.dimension();
        doInitialize(sl, center, chunkRadius);
    }

    /**
     * Hands the next {@link #initialize} a map already built from
     * {@code origin} over many ticks, and lets its knowledge scan run in slices
     * afterwards, so founding a village while a player is near is no long stall.
     * Built with version 1: only a village that has never had a map takes one.
     */
    public void wakeUpWith(NavGraph graph, BlockPos origin) {
        wakeUpWith(graph, origin, true);
    }

    /**
     * @param newVillage true for a generated village founding itself (no
     *        buildings but its Town Hall, no villagers yet); false for a saved
     *        village being woken, which needs its full building and villager scans
     */
    public void wakeUpWith(NavGraph graph, BlockPos origin, boolean newVillage) {
        if (graphVersion != 0L) return;
        prebuiltGraph = graph;
        prebuiltOrigin = origin.immutable();
        prebuiltForNewVillage = newVillage;
    }

    /**
     * Looks for token frames hung before the village was founded, a slice per
     * tick. A village founded on a map drawn ahead registers only its Town
     * Hall at founding; a player's village may have other tokens up already.
     */
    void findEarlierFramesSoon() {
        buildings.scheduleExistingBuildingsScan();
    }

    /** Building rows of the village log off (true) while the village is set up from its save, then on again. */
    void setBuildingRowsOff(boolean off) {
        buildings.setBuildingRowsOff(off);
    }

    /** Hands this village the chunks its map was drawn on, to let go once it stands. */
    public void takeWakeHold(ChunkHold hold) {
        if (hold == null || hold.isEmpty() || level == null) return;
        if (wakeHold != null) wakeHold.releaseAll(level);
        wakeHold = hold;
        wakeHoldSince = level.getGameTime();
    }

    /**
     * Brings the map up to date with the blocks just changed, now, instead of
     * after the usual quiet spell (ten seconds with no other change). For a
     * setup that knows it has finished placing blocks, such as a generated
     * village putting in its chest and doors: the tokens it hangs next then
     * register within a few ticks. Only a small local update; anything bigger
     * is left to the usual wait.
     */
    public void updateMapNow() {
        if (level == null || navGraph == null || !navDirty.hasDirtyBounds()) return;
        if (!navGraph.canUpdateRegion(effectiveOrigin(), graphChunkRadius, navDirty.boxes())) return;
        rebuildNow(level, false);
    }

    /** True while the trees and crops of a village that woke near a player are still being learned. */
    public boolean isKnowledgeScanPending() {
        return pendingKnowledgeScan != null;
    }

    /**
     * Lets go of the chunks a slice-built map held, a few per tick, once the
     * village has learned its trees (or has gone to sleep, or waited too long).
     * Runs whether or not the village is awake.
     */
    void tickChunkRelease(ServerLevel sl, boolean asleep) {
        if (wakeHold == null) return;
        // A village asleep stops its scan, so its land is let go; but one
        // built late learns its trees while still counted as asleep.
        boolean due = (asleep && deferredInit == null) || pendingKnowledgeScan == null
                || sl.getGameTime() - wakeHoldSince >= WAKE_HOLD_MAX_TICKS;
        if (due && wakeHold.releaseSome(sl, WAKE_HOLD_RELEASE_PER_TICK)) wakeHold = null;
    }

    // =========================================================================
    // Built later: villages asleep at world load
    // =========================================================================

    /**
     * A saved village that was asleep when the world loaded. Drawing every
     * village's map at load made the loading screen grow with every village
     * found, and most of them stay asleep for the whole session. This one
     * draws its map only when a player comes near: its land loads in the
     * background, the map is drawn a slice per tick, its trees and crops are
     * learned in slices, and only then does it count as ready to catch up and
     * wake (see {@link #tickDeferredInitialize}).
     */
    private static final class DeferredInit {
        enum Stage { WAITING, LOAD, MAP, SCAN }
        Stage stage = Stage.WAITING;
        final ChunkHold hold = new ChunkHold();
        @Nullable SlicedMapBuild build;
        @Nullable CompoundTag farmState;
        long startedAt;
    }

    /** Longest the land of a waking saved village is waited for before its map is drawn anyway. */
    private static final long DEFERRED_LOAD_TIMEOUT_TICKS = 1200L;

    /**
     * Keeps everything {@link #initialize} would set except the map, the
     * building restore and the knowledge scan, which wait for
     * {@link #tickDeferredInitialize}. Saved buildings, beds and villager ids
     * are remembered as usual, so saving the world in the meantime loses
     * nothing; the farm state is kept here for the same reason.
     */
    public void deferInitialize(ServerLevel sl, BlockPos center, int chunkRadius, @Nullable CompoundTag farmState) {
        this.level            = sl;
        this.bellPos          = center;
        this.graphChunkRadius = chunkRadius;
        this.dimension        = sl.dimension();
        DeferredInit deferred = new DeferredInit();
        deferred.farmState = farmState;
        this.deferredInit = deferred;
        LOGGER.info("[VillageManager {}] Asleep at world load: its map is drawn when a player comes near.",
                shortId());
    }

    /** True while this village waits to be built; see {@link #deferInitialize}. */
    public boolean isAwaitingInitialize() {
        return deferredInit != null;
    }

    /** True once the building of a village asleep at world load has begun; it then runs to the end. */
    public boolean isDeferredBuildStarted() {
        return deferredInit != null && deferredInit.stage != DeferredInit.Stage.WAITING;
    }

    /** The farm state a village waiting to be built will restore, for saving in the meantime. */
    @Nullable
    public CompoundTag deferredFarmState() {
        return deferredInit != null ? deferredInit.farmState : null;
    }

    /**
     * One step of building a village that was asleep at world load.
     *
     * @return true once it is built (or was never waiting)
     */
    public boolean tickDeferredInitialize(ServerLevel sl) {
        DeferredInit deferred = deferredInit;
        if (deferred == null) return true;
        try {
            switch (deferred.stage) {
                case WAITING -> {
                    int cx = effectiveOrigin().getX() >> 4;
                    int cz = effectiveOrigin().getZ() >> 4;
                    // The claim and the ring its edge checks look into.
                    deferred.hold.hold(sl, ChunkHold.square(cx, cz, cx, cz, graphChunkRadius + 1));
                    deferred.startedAt = sl.getGameTime();
                    deferred.stage = DeferredInit.Stage.LOAD;
                    LOGGER.info("[VillageManager {}] A player came near: loading its land to draw its map.", shortId());
                }
                case LOAD -> {
                    if (!deferred.hold.loaded(sl, DEFERRED_LOAD_TIMEOUT_TICKS)) return false;
                    int missing = deferred.hold.missing(sl);
                    if (missing > 0) {
                        LOGGER.warn("[VillageManager {}] {} chunk(s) of its land still not loaded after {} ticks; "
                                + "drawing the map anyway.", shortId(), missing, deferred.hold.waited(sl));
                    }
                    deferred.build = new SlicedMapBuild(sl, effectiveOrigin(), graphChunkRadius, 1L);
                    deferred.stage = DeferredInit.Stage.MAP;
                }
                case MAP -> {
                    NavGraph graph = deferred.build.advance(sl);
                    if (graph == null) return false;
                    wakeUpWith(graph, effectiveOrigin(), false);
                    doInitialize(sl, effectiveOrigin(), graphChunkRadius);
                    // After initialize, which starts the farmer manager empty.
                    if (deferred.farmState != null) farmerManager.restoreState(deferred.farmState);
                    LOGGER.info("[VillageManager {}] Map drawn in {} ms over {} ticks ({} ticks after a player came near).",
                            shortId(), deferred.build.workMillis(), deferred.build.ticks(),
                            sl.getGameTime() - deferred.startedAt);
                    // The knowledge scan runs in slices from here; it must be
                    // done before the village catches up on the time it slept.
                    deferred.stage = DeferredInit.Stage.SCAN;
                    takeWakeHold(deferred.hold);
                }
                case SCAN -> {
                    if (pendingKnowledgeScan != null) {
                        advanceKnowledgeScan(sl);
                        if (pendingKnowledgeScan != null) return false;
                    }
                    deferredInit = null;
                    return true;
                }
            }
            return false;
        } catch (RuntimeException failure) {
            // Never leave a village stuck unbuilt: build it the old way, now.
            LOGGER.error("[VillageManager {}] Building it a slice at a time failed; building it in one go.",
                    shortId(), failure);
            CompoundTag farmState = deferred.farmState;
            deferred.hold.releaseAll(sl);
            deferredInit = null;
            prebuiltGraph = null;
            prebuiltOrigin = null;
            if (navGraph == null) {
                doInitialize(sl, effectiveOrigin(), graphChunkRadius);
                if (farmState != null) farmerManager.restoreState(farmState);
            }
            pendingKnowledgeScan = null;
            return true;
        }
    }

    private void doInitialize(ServerLevel sl, BlockPos origin, int chunkRadius) {
        pendingFullBuild = null;
        fullBuildRequested = false;
        pendingKnowledgeScan = null;
        farmerManager.clear();
        clearTaskState();

        NavGraph prebuilt = origin.equals(prebuiltOrigin) ? prebuiltGraph : null;
        boolean newVillage = prebuilt != null && prebuiltForNewVillage;
        prebuiltGraph = null;
        prebuiltOrigin = null;
        prebuiltForNewVillage = false;

        //        NavGraph (the only place NavGraph and KnowledgeBase interact)
        // At world load none of the claim is loaded yet; load it all at once
        // rather than one chunk at a time as the flood fill reaches each. A
        // map drawn ahead needs none of it: its chunks were held loaded while
        // it was drawn, and waiting here for any that were not would be a freeze.
        String chunks;
        if (prebuilt == null) {
            chunks = ChunkPreloader.preloadClaim(sl, origin, chunkRadius).describe();
        } else {
            chunks = "drawn ahead";
        }
        long buildBegan = System.nanoTime();
        graphVersion++;
        navGraph = prebuilt != null ? prebuilt : NavGraph.build(sl, origin, chunkRadius, graphVersion);
        navDirty.clear();
        LOGGER.info("[VillageManager {}] NavGraph {} in {} ms: {} | chunks: {}",
                shortId(), prebuilt != null ? "taken from the wake-up" : "built",
                millis(System.nanoTime() - buildBegan), navGraph.summary(), chunks);
        // The first patch of a session used to be the slowest, 17 to 47 ms,
        // because the patch code ran cold on the player's first block. One
        // practice patch around the bell, while the world is still loading,
        // runs it once for nothing: same tiles, one version bump before
        // anything has cached one. A map drawn ahead is already in play.
        if (prebuilt == null) {
            long practiceBegan = System.nanoTime();
            // Three times, not one: the compiler builds a version of the patch
            // code on the first run and a better one after it has seen a few.
            for (int round = 0; round < PRACTICE_PATCHES; round++) {
                navGraph.updateRegion(sl, origin, chunkRadius, List.of(new NavGraph.DirtyBox(
                        origin.getX() - 4, origin.getX() + 4, origin.getZ() - 4, origin.getZ() + 4,
                        origin.getY(), origin.getY(), false)));
            }
            LOGGER.info("[VillageManager {}] {} practice patches {} ms", shortId(), PRACTICE_PATCHES,
                    millis(System.nanoTime() - practiceBegan));
        }

        //        Auto-discover pre-existing buildings
        if (newVillage) {
            // A generated village founding itself has one building, the Town
            // Hall just hung by this door; walking the whole claim for doors
            // and frames was ~30 ms of a one-tick stall to find it.
            buildings.registerTownHallAt(sl, origin);
        } else if (prebuilt != null) {
            // A saved village waking near a player: its saved buildings are
            // restored just below; frames hung while it slept are found by
            // the same search in slices, from its first ticks.
            buildings.scheduleExistingBuildingsScan();
        } else {
            buildings.scanExistingBuildings(sl);
        }
        buildings.restoreSavedBuildings(sl, Long.MAX_VALUE);

        //        Knowledge (populated from the world once, then maintained by events)
        long scanBegan = System.nanoTime();
        knowledgeBase.clearAll();
        if (prebuilt != null) {
            // Waking up: the scan runs in slices from the next ticks on.
            pendingKnowledgeScan = KnowledgeScanner.start(
                    sl, navGraph, knowledgeBase, this::isProtectedTreePosition);
            pendingKnowledgeScanNanos = 0L;
        } else {
            KnowledgeScanner.populate(
                    sl, navGraph, knowledgeBase, this::isProtectedTreePosition);
            LOGGER.info("[VillageManager {}] {} scanned in {} ms", shortId(), knowledgeBase.dumpStats(),
                    millis(System.nanoTime() - scanBegan));
        }

        // A map drawn ahead skips the search of the whole claim for villagers
        // (~30-40 ms in one tick): a generated village founding itself has
        // none yet, its settlers are taken in by the takeover right after; a
        // saved village woken late has been here since the world loaded, and
        // bound each of its villagers as they loaded in.
        if (prebuilt == null) discoverVillagers(sl);
        broadcastBounds();

        LOGGER.info("[VillageManager {}] Ready: {} villager(s), {} known tree(s).",
                shortId(), allVillagers.size(), knowledgeBase.count(Category.TREE));
    }

    // =========================================================================
    // Teardown
    // =========================================================================

    public void teardown() {
        pendingFullBuild = null;
        pendingKnowledgeScan = null;
        if (level != null) {
            if (wakeHold != null) wakeHold.releaseAll(level);
            if (deferredInit != null) deferredInit.hold.releaseAll(level);
        }
        wakeHold = null;
        deferredInit = null;
        for (VillagerModel m : allVillagers.values()) m.stopMoving();
        mayorSpawnManager.teardown();
        clearTaskState();
        farmerManager.clear();
        knowledgeBase.clearAll();
        allVillagers.clear();
        knownCaptains.clear();
        buildings.clear();
        beds.clear();
        LOGGER.info("[VillageManager {}] Torn down.", shortId());
    }

    public void pauseRuntimeMovement() {
        for (VillagerModel m : allVillagers.values()) {
            m.stopMoving();
        }
    }

    public void pauseRuntimeMovementForShutdown() {
        for (VillagerModel m : allVillagers.values()) {
            m.stopMovingForShutdown();
        }
        mayorSpawnManager.teardown();
        claims.clear();
        lumberjackTrees.clear();
        // The world is saved after this event; keep a snapshot of the farm
        // memory and in-progress farmer jobs so that save can still write them.
        farmerManager.clearForShutdown();
        pendingGrowChecks.clear();
        growRetries.clear();
        pendingBonemealChecks.clear();
        pendingToolChecks.clear();
        buildings.clearPendingWork();
    }

    // =========================================================================
    // Nav rebuild     called only from the dirty/debounce path
    // =========================================================================

    /**
     * Rebuild the NavGraph from the live world (pathfinding only).
     * Does <em>not</em> touch the KnowledgeBase     knowledge is event-driven.
     */
    public void rebuildNow(ServerLevel sl) {
        rebuildNow(sl, true);
    }

    private void rebuildNow(ServerLevel sl, boolean scanImmediately) {
        if (sl == null) sl = level;
        if (sl == null) return;
        pendingFullBuild = null;
        graphVersion++;
        long startedAt = System.nanoTime();

        // A block change can only alter the tiles around it, so re-read only
        // those columns and keep the rest of the graph. Falls back to a full
        // build when there is no graph yet, when the claim has moved, or when
        // the change is too widespread for the bookkeeping to pay. The
        // scheduled path takes the changes a batch per tick, so that many of
        // them at once do not all land in this one.
        List<NavGraph.DirtyBox> batch = null;
        NavGraph.UpdateResult result = null;
        boolean local = false;
        // The scheduled path caps each patch's fill (see NavGraph#updateRegion);
        // an immediate rebuild (commands, tests) keeps the old unbounded patch.
        int fillLimit = scanImmediately ? Integer.MAX_VALUE : LOCAL_FILL_MAX_NODES;
        if (navGraph != null && navDirty.hasDirtyBounds()) {
            batch = scanImmediately ? navDirty.boxes()
                    : navDirty.nextBatch(navGraph::nodesAround, LOCAL_UPDATE_MAX_NODES);
            result = navGraph.updateRegion(sl, effectiveOrigin(), graphChunkRadius, batch, fillLimit);
            local = result.done();
            if (!local && !scanImmediately) {
                // The batch on its own could not be patched; let all the
                // changes together decide, as they did before batches.
                batch = navDirty.boxes();
                result = navGraph.updateRegion(sl, effectiveOrigin(), graphChunkRadius, batch, fillLimit);
                local = result.done();
            }
        }
        if (!local) {
            if (!scanImmediately && navGraph != null) {
                // Scheduled: build the whole claim a slice per tick (tickNavRebuild,
                // then tickNavSlices) and keep the current graph until it lands,
                // instead of the whole claim in this tick -- up to 1.9 s on the
                // beta server.
                fullBuildRequested = true;
                navDirty.clear();
                return;
            }
            navGraph = NavGraph.build(sl, effectiveOrigin(), graphChunkRadius, graphVersion);
        }
        // The patch found islands too large to be right, or its fill hit the
        // limit; a full build settles it. Scheduled: the sliced one, and the
        // patched graph stays in use meanwhile.
        if (local && navGraph.takeFullBuildRequest()) {
            if (scanImmediately) {
                navGraph = NavGraph.build(sl, effectiveOrigin(), graphChunkRadius, graphVersion);
                local = false;
            } else {
                fullBuildRequested = true;
            }
        }
        long graphDoneAt = System.nanoTime();
        lastRebuildMillis = (graphDoneAt - startedAt) / 1_000_000L;
        lastRebuildWasLocal = local;
        summaryEdits++;
        if (graphDoneAt - startedAt > summaryWorstUpdateNanos) summaryWorstUpdateNanos = graphDoneAt - startedAt;
        if (navGraph.cells().lastMapNanos() > summaryWorstMapNanos) summaryWorstMapNanos = navGraph.cells().lastMapNanos();
        int columnsLeft = 0;
        Set<Long> touchedCells = Set.of();
        if (local) {
            columnsLeft = navDirty.finish(result.ran());
            // Columns the fill could not read because their chunk is not
            // loaded stay dirty; a box skipped for the same reason never left
            // the tracker. Either way the patch tries again in a few seconds
            // rather than every tick.
            for (NavGraph.DirtyBox box : result.keep()) navDirty.keep(box);
            if (result.ran().isEmpty() && result.skippedBoxes() > 0) navDirty.retryIn(CHUNK_WAIT_TICKS);
            touchedCells = result.touchedCells();
        } else {
            navDirty.clear();
        }
        // Rooms rescanned while this update was pending saw the old graph --
        // a ladder just placed had no rungs in it yet. Look at them again, and
        // at the route of every building near a cell the patch changed or
        // whose Town Hall access flipped.
        if (local) buildings.onNavGraphRebuilt(sl, touchedCells);
        else buildings.onNavGraphRebuilt(sl);
        long buildingsDoneAt = System.nanoTime();
        com.alex.test.village.villagers.guard.behavior.GuardPostRegistry.get(sl)
                .recheckAccess(sl, this);
        long guardsDoneAt = System.nanoTime();
        // Knowledge stays as-is: the events that caused the patch will
        // already have updated the KnowledgeBase. Look for doors and gates
        // with tokens only where the patch changed something.
        if (scanImmediately) buildings.scanExistingBuildings(sl);
        else if (local) buildings.scheduleExistingBuildingsScan(touchedCells);
        else buildings.scheduleExistingBuildingsScan();
        long scanDoneAt = System.nanoTime();
        buildings.restoreSavedBuildings(sl, VillageBuildings.RESTORE_BUDGET_NANOS);
        long restoreDoneAt = System.nanoTime();
        // The duration is in the line on purpose. This runs on the server
        // thread, from a debounce that any block placement can trigger, and on
        // generated terrain the graph is fifty times the size it is on a
        // superflat test world -- so "how long did that take" is the number that
        // decides whether a player notices. The "after" part is the work the
        // patch sets off in the same tick.
        LOGGER.info("[VillageManager {}] NavGraph rebuilt ({}) in {} ms: {}{} | after {} ms: "
                        + "building access {} ms ({}), guard posts {} ms, building scan {} ms ({}), "
                        + "restore saved {} ms",
                shortId(), local ? "local" : "full", lastRebuildMillis, navGraph.summary(),
                columnsLeft > 0 ? " | " + columnsLeft + " changed column(s) left for the next tick" : "",
                millis(restoreDoneAt - graphDoneAt),
                millis(buildingsDoneAt - graphDoneAt), buildings.lastAccessRecheckTiming(),
                millis(guardsDoneAt - buildingsDoneAt),
                millis(scanDoneAt - guardsDoneAt),
                scanImmediately ? "now" : local ? "scheduled, " + touchedCells.size() + " cell(s)" : "scheduled",
                millis(restoreDoneAt - scanDoneAt));
    }

    /** Ticks a patch waits before trying again when every box had a chunk that was not loaded. */
    private static final int CHUNK_WAIT_TICKS = 100;

    /** Patches run for nothing around the bell at join, so the player's first edit runs warm code. */
    private static final int PRACTICE_PATCHES = 3;

    /** Nanoseconds as "12.3", for the rebuild timing lines. */
    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    /**
     * Starts the rebuild the debounce asks for. Only one village on the server
     * may start one per tick ({@link NavWorkBudget}); a village that finds the
     * slot taken keeps its rebuild due and tries again next tick.
     */
    private void tickNavRebuild(ServerLevel sl, int serverTick, long tickNanosSoFar) {
        navRebuiltThisTick = false;
        // A failed jump that has lapsed counts again: one cell regrouped, the
        // piece map redone, well under a millisecond.
        if (navGraph != null) navGraph.tickFailedSteps(System.currentTimeMillis());
        // Bug 5 (irondoors): which iron doors redstone has shut, for the villagers' routes.
        if (navGraph != null && serverTick % 20 == 0) navGraph.refreshShutIronDoors(sl);
        if (pendingFullBuild != null) return; // advanced by tickNavSlices
        if (!fullBuildRequested && !navDirty.tickRebuildDue()) return;
        // Not on top of a tick that is already slow for another reason (a
        // farm rebuild, a brain spike); the rebuild stays due.
        if (tickNanosSoFar >= NAV_BUSY_REBUILD_NANOS && navBusyDeferrals < NAV_BUSY_MAX_DEFERRALS) {
            navBusyDeferrals++;
            return;
        }
        navBusyDeferrals = 0;
        if (!NavWorkBudget.tryTakeRebuild(serverTick)) return;
        navRebuiltThisTick = true;
        BlockPos origin = effectiveOrigin();
        if (!fullBuildRequested
                && navGraph.canUpdateRegion(origin, graphChunkRadius, navDirty.boxes())) {
            rebuildNow(sl, false);
            return;
        }
        fullBuildRequested = false;
        graphVersion++;
        long startedAt = System.nanoTime();
        pendingFullBuild = NavGraph.startBuild(sl, origin, graphChunkRadius, graphVersion);
        pendingFullBuildWorkNanos = System.nanoTime() - startedAt;
        pendingFullBuildTicks = 0;
        navDirty.clear();
    }

    /** Whether this village wanted a nav slice last tick and the shared budget had none left. */
    private boolean navSlicesStarved = false;

    /** This tick ran a local update or started a full build; its slices wait for the next. */
    private boolean navRebuiltThisTick = false;

    /** Ticks in a row a due rebuild has stood back for a busy tick. */
    private int navBusyDeferrals = 0;

    /** The slices skipped last tick because it was busy; they never skip two in a row. */
    private boolean navSlicesSkippedBusy = false;

    /** A prune sweep was refused (NavGraph#takeFullBuildRequest); the next rebuild is a full one. */
    private boolean fullBuildRequested = false;

    /**
     * The nav work done a slice per tick: only a full build in progress now.
     * The island sweep, the Town Hall access flood and the reachability
     * labels that used to follow every local patch here are gone; the patch
     * regroups its cells and redoes the piece map in its own tick. The budget
     * is still shared with every other village on the server
     * ({@link NavWorkBudget}).
     *
     * <p>None of it runs in a tick that ran a patch ({@link #tickNavRebuild}),
     * and it skips one tick that is already slow for another reason.
     */
    private void tickNavSlices(ServerLevel sl, int serverTick, long tickNanosSoFar) {
        if (navRebuiltThisTick) return;
        if (pendingFullBuild == null) {
            navSlicesStarved = false;
            return;
        }
        if (tickNanosSoFar >= NAV_BUSY_SLICES_NANOS && !navSlicesSkippedBusy) {
            navSlicesSkippedBusy = true;
            return;
        }
        navSlicesSkippedBusy = false;
        long budget = NavWorkBudget.sliceBudget(serverTick, navSlicesStarved);
        if (budget <= 0L) {
            navSlicesStarved = true;
            return;
        }
        navSlicesStarved = false;
        long startedAt = System.nanoTime();
        advanceFullBuild(sl);
        NavWorkBudget.spent(serverTick, System.nanoTime() - startedAt);
    }

    /** One slice of a waking village's knowledge scan. */
    private void advanceKnowledgeScan(ServerLevel sl) {
        long startedAt = System.nanoTime();
        boolean done = pendingKnowledgeScan.advance(KNOWLEDGE_SLICE_NANOS);
        pendingKnowledgeScanNanos += System.nanoTime() - startedAt;
        if (!done) return;
        pendingKnowledgeScan = null;
        LOGGER.info("[VillageManager {}] {} scanned in {} ms over several ticks", shortId(),
                knowledgeBase.dumpStats(), millis(pendingKnowledgeScanNanos));
    }

    /** One slice of the full build in progress; installs the graph when it completes. */
    private void advanceFullBuild(ServerLevel sl) {
        long startedAt = System.nanoTime();
        NavGraph completed = pendingFullBuild.advance(sl, FULL_BUILD_NODES_PER_TICK);
        pendingFullBuildWorkNanos += System.nanoTime() - startedAt;
        pendingFullBuildTicks++;
        if (completed == null) return;
        navGraph = completed;
        pendingFullBuild = null;
        fullBuildRequested = false;
        lastRebuildMillis = pendingFullBuildWorkNanos / 1_000_000L;
        lastRebuildWasLocal = false;
        long graphDoneAt = System.nanoTime();
        buildings.onNavGraphRebuilt(sl);
        long buildingsDoneAt = System.nanoTime();
        com.alex.test.village.villagers.guard.behavior.GuardPostRegistry.get(sl)
                .recheckAccess(sl, this);
        long guardsDoneAt = System.nanoTime();
        buildings.scheduleExistingBuildingsScan();
        buildings.restoreSavedBuildings(sl, VillageBuildings.RESTORE_BUDGET_NANOS);
        long restoreDoneAt = System.nanoTime();
        LOGGER.info("[VillageManager {}] NavGraph rebuilt (full) in {} ms over {} ticks: {} | after {} ms: "
                        + "building access {} ms ({}), guard posts {} ms, restore saved {} ms",
                shortId(), lastRebuildMillis, pendingFullBuildTicks, navGraph.summary(),
                millis(restoreDoneAt - graphDoneAt),
                millis(buildingsDoneAt - graphDoneAt), buildings.lastAccessRecheckTiming(),
                millis(guardsDoneAt - buildingsDoneAt),
                millis(restoreDoneAt - guardsDoneAt));
    }

    // =========================================================================
    // Per-tick
    // =========================================================================

    public void tick(ServerLevel sl) {
        if (navGraph == null) return;
        // Minecraft's tick freeze (/tick freeze, the stuck catcher) stops entities
        // but still fires level ticks: brains and walkers wait with their bodies.
        if (com.alex.test.village.stuckwatch.StuckWatch.worldHeld(sl)) return;

        long tickStart = System.nanoTime();
        long mark = tickStart;

        // Far villagers stay awake (FarAwake): their holds, and their bodies moved where
        // Minecraft does not move them, before the walkers below read whether they moved.
        com.alex.test.village.farawake.FarAwake.tickVillage(this, sl);
        mark = lap(TP_FAR_BODIES, mark);

        // 0. A village founded or woken on a map drawn ahead finishes learning its trees and crops.
        if (pendingKnowledgeScan != null) advanceKnowledgeScan(sl);

        // 1. Process deferred grow checks from the previous tick's grow events.
        processPendingGrows(sl);
        processPendingBonemeal(sl);
        processPendingToolChecks(sl);
        mark = lap(TP_GROWS, mark);

        // 2. Process deferred item-frame interaction checks (building creation).
        buildings.processPendingFrameChecks(sl);

        // 2b. Retry persisted building markers whose chunks/entities were not
        // available when the village first initialized.
        buildings.restoreSavedBuildings(sl, VillageBuildings.RESTORE_BUDGET_NANOS);
        mark = lap(TP_FRAMES, mark);

        // Discover token frames in bounded slices after a scheduled graph rebuild.
        buildings.tickExistingBuildingsScan(sl);
        mark = lap(TP_EXISTING_SCAN, mark);

        // 3. Rebuild buildings whose room AABB contained a recent block change.
        buildings.rebuildDirtyBuildings(sl);
        // Lost routes that stayed lost past their grace period.
        buildings.tickAccessGrace(sl);
        // Village log rows a building held back while it flipped (one a minute).
        buildings.tickBuildingRows(sl);
        beds.tickBarracksAssignments();
        processPendingArmorStands(sl);
        mark = lap(TP_DIRTY_BUILDINGS, mark);

        // Farm discovery/planning is server-thread-only and precedes workers so
        // every farmer sees one coherent reservation/state snapshot this tick.
        farmerManager.tick(sl, this);
        tickMissingMembers(sl);
        mark = lap(TP_FARMS, mark);

        // 4. Slowly reconcile the one-Mayor-per-Town-Hall invariant.
        mayorSpawnManager.tick(sl);
        mark = lap(TP_MAYOR, mark);

        // 5. Advance all villager movement.
        List<VillagerModel> movementSnapshot = new ArrayList<>(allVillagers.values());
        long worstMove = 0L;
        String worstMoveWho = "";

        for (VillagerModel m : movementSnapshot) {
            NpcEntity npc = m.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved()) {
                // Once when it goes missing, then every 10 s: two of these walk whole claim lists.
                if (m.dueUnloadedRelease(sl.getGameTime())) {
                    releaseTreeJobs(m.npcId);
                    farmerManager.releaseUnloaded(m.npcId); // a job back from the save waits for its body
                    claims.releaseAll(m.npcId);
                    m.stopMoving();
                }
                continue;
            }
            long began = System.nanoTime();
            m.tick(navGraph);
            long took = System.nanoTime() - began;
            if (took > worstMove) {
                worstMove = took;
                worstMoveWho = m.npcId.toString().substring(0, 8);
            }
        }
        mark = lap(TP_MOVEMENT, mark);

        // 6. Villager brains decide and execute their own activities.
        socialCoordinator.tick(this, sl);
        crowding.tick(sl, this);
        mark = lap(TP_SOCIAL, mark);

        List<VillagerModel> brainSnapshot = new ArrayList<>(allVillagers.values());
        long worstBrain = 0L;
        String worstBrainWho = "";

        for (VillagerModel m : brainSnapshot) {
            NpcEntity npc = m.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved() || m.isBrainSuspended()) {
                continue;
            }

            long began = System.nanoTime();
            m.brain().tick(this, sl);
            long took = System.nanoTime() - began;
            Object activity = m.brain().currentActivity();
            recordActivity(activity, took);
            if (com.alex.test.performance.ServerCostLog.measuring) {
                com.alex.test.performance.ServerCostLog.brain(activity, took);
            }
            if (took > worstBrain) {
                worstBrain = took;
                worstBrainWho = m.npcId.toString().substring(0, 8) + " "
                        + (activity == null ? "none" : activity.getClass().getSimpleName());
            }
        }
        mark = lap(TP_BRAINS, mark);

        // 6b. Stock tab orders: collected, lost and finished ones (throttled inside).
        com.alex.test.village.economy.StockReserves.get(sl.getServer()).tick(this, sl);
        com.alex.test.village.villagers.enchanter.BookOrders.get(sl.getServer()).tick(this, sl);

        // 7. Nav dirty debounce (break / place / growth events; see event
        //    handlers), with a ceiling so steady digging cannot starve it.
        //    Both 7 and 7b share out their time with every other village on
        //    the server (NavWorkBudget).
        int serverTick = sl.getServer().getTickCount();
        tickNavRebuild(sl, serverTick, mark - tickStart);
        mark = lap(TP_NAV_REBUILD, mark);

        // 7b. Slices: a full build in progress, its tiles, index and pieces
        //     a slice per tick instead of all at once.
        tickNavSlices(sl, serverTick, mark - tickStart);
        mark = lap(TP_NAV_BUILD, mark);

        // 8. Periodic puppet rebind (handles chunk-reload edge cases).
        if (++rebindCounter >= REBIND_INTERVAL) {
            rebindCounter = 0;
            if (hasMissingPuppets()) rediscoverVillagers(sl);
        }
        mark = lap(TP_REBIND, mark);

        // 8b. Recruitable nomads and temporary visitors have independent schedules.
        nomadSpawnManager.tick(sl);
        visitorSpawnManager.tick(sl);
        travelerCamp.tick(sl);
        breedingManager.tick(sl);
        raidManager.tick(sl);
        mark = lap(TP_SPAWNERS, mark);

        // 9. Rolling re-check of one building, for losses no event reported.
        if (++revalidateCounter >= REVALIDATE_INTERVAL) {
            revalidateCounter = 0;
            buildings.revalidateNextBuilding(sl);
        }
        mark = lap(TP_REVALIDATE, mark);

        long total = mark - tickStart;
        if (total >= SLOW_TICK_NANOS) {
            logSlowTick(total, worstMove, worstMoveWho, worstBrain, worstBrainWho);
        }
        recordTickSummary(total);
    }

    // -------------------------------------------------------------------------
    // Tick summary: what this village costs on average, not only its spikes.
    // -------------------------------------------------------------------------

    /**
     * Ticks per summary line (30 s). The slow-tick line only shows ticks of
     * 15 ms and up; what a village costs the server all the time -- sixty
     * brains every tick, each well under that -- never shows there.
     */
    private static final int SUMMARY_TICKS = 600;

    /** Activities listed in the summary, most expensive first. */
    private static final int SUMMARY_TOP_ACTIVITIES = 6;

    private final long[] summaryPhaseSum = new long[TICK_PHASE_NAMES.length];
    private final long[] summaryPhaseMax = new long[TICK_PHASE_NAMES.length];
    private long summaryTotalSum, summaryTotalMax;
    private int summaryTicks, summarySlowTicks;
    /** Patches this period, the slowest patch, and the slowest piece map build, so a log answers "did it work". */
    private int summaryEdits;
    private long summaryWorstUpdateNanos, summaryWorstMapNanos;
    /** Per brain activity class: {ticks run, total ns, worst ns}. */
    private final Map<Class<?>, long[]> summaryActivities = new HashMap<>();

    private void recordActivity(Object activity, long nanos) {
        Class<?> key = activity == null ? Void.class : activity.getClass();
        long[] stats = summaryActivities.computeIfAbsent(key, ignored -> new long[3]);
        stats[0]++;
        stats[1] += nanos;
        if (nanos > stats[2]) stats[2] = nanos;
    }

    private void recordTickSummary(long total) {
        for (int i = 0; i < tickPhaseNanos.length; i++) {
            summaryPhaseSum[i] += tickPhaseNanos[i];
            if (tickPhaseNanos[i] > summaryPhaseMax[i]) summaryPhaseMax[i] = tickPhaseNanos[i];
        }
        summaryTotalSum += total;
        if (total > summaryTotalMax) summaryTotalMax = total;
        if (total >= SLOW_TICK_NANOS) summarySlowTicks++;
        if (++summaryTicks < SUMMARY_TICKS) return;

        StringBuilder phases = new StringBuilder();
        for (int i = 0; i < summaryPhaseSum.length; i++) {
            double avg = summaryPhaseSum[i] / 1_000_000.0 / summaryTicks;
            if (avg < 0.01 && summaryPhaseMax[i] < 1_000_000L) continue;
            phases.append(' ').append(TICK_PHASE_NAMES[i]).append('=')
                    .append(String.format(Locale.ROOT, "%.2f/%.1f", avg, summaryPhaseMax[i] / 1_000_000.0));
        }
        List<Map.Entry<Class<?>, long[]>> activities = new ArrayList<>(summaryActivities.entrySet());
        activities.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < Math.min(SUMMARY_TOP_ACTIVITIES, activities.size()); i++) {
            long[] stats = activities.get(i).getValue();
            Class<?> type = activities.get(i).getKey();
            top.append(' ').append(type == Void.class ? "none" : type.getSimpleName()).append('=')
                    .append(String.format(Locale.ROOT, "%.2f/%.1f",
                            stats[1] / 1_000_000.0 / summaryTicks, stats[2] / 1_000_000.0))
                    .append(" (").append(stats[0] / summaryTicks).append(')');
        }
        LOGGER.info("[VillageManager {}] Tick summary {} ticks, {} villagers: avg {} ms, max {} ms, {} slow"
                        + " | phases avg/max ms:{} | brain activities avg/max ms (villagers):{}"
                        + " | edits {}, worst update {} ms, worst piece map {} ms",
                shortId(), summaryTicks, allVillagers.size(),
                String.format(Locale.ROOT, "%.2f", summaryTotalSum / 1_000_000.0 / summaryTicks),
                String.format(Locale.ROOT, "%.1f", summaryTotalMax / 1_000_000.0),
                summarySlowTicks, phases, top,
                summaryEdits, millis(summaryWorstUpdateNanos), millis(summaryWorstMapNanos));

        Arrays.fill(summaryPhaseSum, 0L);
        Arrays.fill(summaryPhaseMax, 0L);
        summaryTotalSum = summaryTotalMax = 0L;
        summaryTicks = summarySlowTicks = 0;
        summaryEdits = 0;
        summaryWorstUpdateNanos = summaryWorstMapNanos = 0L;
        summaryActivities.clear();
    }

    // -------------------------------------------------------------------------
    // Slow-tick report: which part of this village's tick ate the time.
    // -------------------------------------------------------------------------

    /** A village tick at or over this is logged with its per-phase breakdown. */
    private static final long SLOW_TICK_NANOS = 15_000_000L;

    private static final int TP_GROWS = 0, TP_FRAMES = 1, TP_EXISTING_SCAN = 2,
            TP_DIRTY_BUILDINGS = 3, TP_FARMS = 4, TP_MAYOR = 5, TP_MOVEMENT = 6,
            TP_SOCIAL = 7, TP_BRAINS = 8, TP_NAV_REBUILD = 9, TP_REBIND = 10,
            TP_SPAWNERS = 11, TP_REVALIDATE = 12, TP_NAV_BUILD = 13, TP_FAR_BODIES = 14;
    private final long[] tickPhaseNanos = new long[TP_FAR_BODIES + 1];

    private long lap(int phase, long since) {
        long now = System.nanoTime();
        tickPhaseNanos[phase] = now - since;
        return now;
    }

    private void logSlowTick(long total, long worstMove, String worstMoveWho,
                             long worstBrain, String worstBrainWho) {
        StringBuilder phases = new StringBuilder();
        for (int i = 0; i < tickPhaseNanos.length; i++) {
            if (tickPhaseNanos[i] < 1_000_000L) continue;
            phases.append(' ').append(TICK_PHASE_NAMES[i]).append('=')
                    .append(tickPhaseNanos[i] / 1_000_000L).append("ms");
        }
        LOGGER.info("[VillageManager {}] Slow village tick {} ms:{} | worst movement {} ({} ms), "
                        + "worst brain {} ({} ms)",
                shortId(), total / 1_000_000L, phases,
                worstMoveWho, worstMove / 1_000_000L,
                worstBrainWho, worstBrain / 1_000_000L);
    }

    /**
     * Complete the skipped portion of a night while bed sleep poses still exist:
     * players slept, or the clock was turned back to morning ({@link VillageDays}).
     *
     * @param wakeDayTime the world clock the village wakes up to
     */
    public void onPlayerSleepFinished(ServerLevel sl, long skippedTicks, long wakeDayTime) {
        if (navGraph == null || level != sl || skippedTicks <= 0L) return;

        // Births need the parents' actual sleep poses, which disappear as soon
        // as the world advances to morning.
        breedingManager.onPlayerSleepFinished(sl, skippedTicks);
        for (VillagerModel model : allVillagers.values()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved()) continue;
            boolean resting = npc.isSleeping() || model.brain().currentActivity() instanceof GoHomeActivity home
                    && home.isResting();
            if (resting) model.brain().staggerWakeAfterPlayerSleep(sl);
            model.brain().onNightSkipped(npc, resting, getAssignedBed(model.npcId) != null);
        }
        mayorSpawnManager.onPlayerSleepFinished(sl);
        nomadSpawnManager.onPlayerSleepFinished(sl, skippedTicks, wakeDayTime);
        visitorSpawnManager.onPlayerSleepFinished(sl, skippedTicks, wakeDayTime);
    }

    /** A live member is lying in the head of their assigned, still present bed. */
    public boolean isSleepingInAssignedBed(VillagerModel model) {
        NpcEntity npc = model.puppet();
        if (npc == null || !npc.isAlive() || npc.isRemoved() || !npc.isSleeping()
                || npc.level() != level) return false;
        BlockPos bed = getAssignedBed(model.npcId);
        if (bed == null || npc.getSleepingPos().filter(bed::equals).isEmpty()) return false;
        BlockState state = level.getBlockState(bed);
        return state.getBlock() instanceof BedBlock
                && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    public VisitorSpawnManager visitors() {
        return visitorSpawnManager;
    }

    public com.alex.test.village.visitors.TravelerCamp travelerCamp() {
        return travelerCamp;
    }

    public NomadSpawnManager nomads() {
        return nomadSpawnManager;
    }

    public BreedingManager breeding() {
        return breedingManager;
    }

    public RaidManager raids() {
        return raidManager;
    }

    /**
     * The rules as saved with the village, with the raid manager's state put
     * in first. A raid manager that has not read its saved state yet (the
     * village has not ticked since loading) leaves the loaded state as it is.
     */
    public net.minecraft.nbt.CompoundTag saveRules() {
        if (raidManager.isLoaded()) rules.setRaidState(raidManager.save());
        return rules.save();
    }

    /**
     * Whether {@code pos} belongs to a registered building and must not be
     * mined, broken or built over.
     *
     * <p>Uses {@link Building#isNearRoom}, which covers the interior tiles plus
     * one block of shell around them and three of headroom — the walls, floor,
     * ceiling and functional blocks that make a room validate. A miner that
     * chews through any of these destroys the very room it works in, which is
     * how a mine could vanish from the save on the next restart.
     */
    public boolean isProtectedRoomBlock(BlockPos pos) {
        return buildings.isNearAnyRoom(pos);
    }

    /**
     * As {@link #isProtectedRoomBlock}, ignoring one building's own box: a
     * mine's box reaches over its own entrance, which its miner has to cut.
     */
    public boolean isProtectedRoomBlockExcept(BlockPos pos, java.util.UUID skipFrameId) {
        return buildings.isNearAnyRoomExcept(pos, skipFrameId);
    }

    // =========================================================================
    // Deferred grow processing
    // =========================================================================

    /**
     * Called at the start of each tick.  Checks every sapling position that was
     * flagged by a {@code BlockGrowFeatureEvent} in the previous tick     by now the
     * tree blocks are guaranteed to be in the world.
     */
    private void processPendingBonemeal(ServerLevel sl) {
        if (pendingBonemealChecks.isEmpty()) return;
        List<BlockPos> toCheck = new ArrayList<>(pendingBonemealChecks);
        pendingBonemealChecks.clear();

        for (BlockPos pos : toCheck) {
            if (!sl.hasChunkAt(pos)) continue;
            routeCropGrow(pos, sl.getBlockState(pos));
        }
    }

    /**
     * Refreshes the nav graph, and the rooms around it, where a tool really
     * changed a block's shape; see {@link #pendingToolChecks}. Stripping a log
     * leaves it the same shape but counts as a change, which only costs a
     * small local update.
     */
    private void processPendingToolChecks(ServerLevel sl) {
        if (pendingToolChecks.isEmpty()) return;
        List<Map.Entry<BlockPos, BlockState>> toCheck = new ArrayList<>(pendingToolChecks.entrySet());
        pendingToolChecks.clear();

        for (Map.Entry<BlockPos, BlockState> entry : toCheck) {
            BlockPos pos = entry.getKey();
            if (!sl.hasChunkAt(pos)) continue;
            if (!NavGraph.blockChangeAffectsNavigation(sl, pos, entry.getValue(), sl.getBlockState(pos))) continue;
            markDirtyAt(pos.getX(), pos.getY(), pos.getZ(), involvesWater(entry.getValue(), sl.getBlockState(pos)));
            buildings.markBuildingsDirtyAt(pos);
        }
    }

    private void processPendingGrows(ServerLevel sl) {
        retryGrownTrees(sl);
        if (pendingGrowChecks.isEmpty()) return;
        List<BlockPos> toCheck = new ArrayList<>(pendingGrowChecks);
        pendingGrowChecks.clear();

        for (BlockPos saplingPos : toCheck) {
            if (listGrownTree(sl, saplingPos) && WalkFeel.growRetry
                    && growRetries.size() < GROW_RETRY_MAX
                    && growRetries.putIfAbsent(saplingPos.immutable(), sl.getGameTime()) == null) {
                LOGGER.info("[TreeGrow] Sapling at {} grew partly into an unloaded chunk; looking again for 5 minutes.",
                        saplingPos.toShortString());
            }
        }
    }

    /**
     * Finds and lists the tree a sapling grew into. True when the tree judge
     * could not see all of it (part in an unloaded chunk): worth another look.
     */
    private boolean listGrownTree(ServerLevel sl, BlockPos saplingPos) {
        BlockPos baseLog = TreeScanner.findBaseLogNear(sl, saplingPos);
        if (baseLog == null) return false; // wasn't a tree feature, or it missed the window

        TreeStructure.Inspection inspection = WalkFeel.growFoot ? judgeGrownTree(baseLog) : null;
        TreeStructure tree = WalkFeel.growFoot
                ? (inspection != null ? inspection.tree() : null)
                : inspectTree(baseLog);
        if (inspection != null && !inspection.isTree() && !inspection.cantSee()) {
            // So a grown tree that is never cut says why.
            LOGGER.info("[TreeGrow] Sapling at {} grew, tree not listed: {} at {} (foot {}).",
                    saplingPos.toShortString(), inspection.reason(), inspection.where(), baseLog.toShortString());
        }
        if (tree != null) markTreeNavigationDirty(tree);
        if (tree != null && !(WalkFeel.growFoot && joinsListedTree(tree))
                && lumberjackTrees.registerKnownTree(tree)) {
            LOGGER.info("[VillageManager {}] New tree known at {}.", shortId(), tree.base());
        }
        return inspection != null && inspection.cantSee();
    }

    /**
     * The tree judge on a grown sapling's foot. A refusal that names its
     * trunk's foot (the lookup landed on a branch) is judged again there.
     */
    @Nullable
    private TreeStructure.Inspection judgeGrownTree(BlockPos foot) {
        TreeStructure.Inspection inspection = judgeTree(foot);
        if (inspection != null && !inspection.isTree() && inspection.suggestedFoot() != null) {
            inspection = judgeTree(inspection.suggestedFoot());
        }
        return inspection;
    }

    /**
     * Grown trees the judge could not see whole, looked at again every
     * {@link #GROW_RETRY_EVERY} ticks until listed, refused for a real reason,
     * gone, or {@link #GROW_RETRY_FOR} ticks old. A sapling whose own chunk has
     * gone too only waits: nothing here may load a chunk.
     */
    private void retryGrownTrees(ServerLevel sl) {
        if (growRetries.isEmpty()) return;
        long now = sl.getGameTime();
        if (now < nextGrowRetry) return;
        nextGrowRetry = now + GROW_RETRY_EVERY;
        Iterator<Map.Entry<BlockPos, Long>> retries = growRetries.entrySet().iterator();
        while (retries.hasNext()) {
            Map.Entry<BlockPos, Long> retry = retries.next();
            BlockPos sapling = retry.getKey();
            boolean stillUnseen = !sl.hasChunkAt(sapling) || listGrownTree(sl, sapling);
            if (!stillUnseen) {
                retries.remove();
            } else if (now - retry.getValue() >= GROW_RETRY_FOR) {
                LOGGER.info("[TreeGrow] Gave up on the tree grown at {}: still partly in an unloaded chunk.",
                        sapling.toShortString());
                retries.remove();
            }
        }
    }

    /**
     * A grown tree joined to one already listed. With {@code treesplit} off
     * the judge takes the whole joined group, so the other tree's base is
     * among its logs and that listing fells both; listing this one too sent
     * two lumberjacks to the same logs. With the split on each trunk is its
     * own tree.
     */
    private boolean joinsListedTree(TreeStructure tree) {
        if (WalkFeel.treeSplit) return false;
        for (BlockPos log : tree.logs()) {
            if (!log.equals(tree.base()) && knowledgeBase.contains(Category.TREE, log)) return true;
        }
        return false;
    }

    // =========================================================================
    // Building system
    // =========================================================================

    /**
     * Validates a diamond-sword town-hall marker before a village exists.
     * The registry uses this bootstrap scan so an invalid room never creates
     * a VillageManager.
     */
    @Nullable
    public static TownHallScan scanTownHallMarker(ServerLevel sl, ItemFrame frame, int chunkRadius) {
        return VillageBuildings.scanTownHallMarker(sl, frame, chunkRadius);
    }

    public record TownHallScan(BlockPos doorPos, Direction interiorDirection,
                               Set<BlockPos> roomBlocks) {}

    public boolean hasSavedTownHall(UUID frameId) {
        return buildings.hasSavedTownHall(frameId);
    }

    // =========================================================================
    // Routing     called by VillageManagerRegistry for item-frame interactions
    // =========================================================================

    /**
     * Queue a frame-position check for the next tick.
     * Called by {@link VillageManagerRegistry} when a player interacts with an
     * item frame that lies within this village's bounds.
     */
    public void routeItemFrameInteract(BlockPos framePos) {
        if (!isInGraphBounds(framePos.getX(), framePos.getZ())) return;
        buildings.queueFrameCheck(framePos);
    }

    // =========================================================================
    // Public building queries
    // =========================================================================

    /** Returns all currently registered buildings (valid or not). */
    public java.util.Collection<Building> getBuildings() {
        return buildings.all();
    }

    /**
     * Re-checks one building the way the rolling re-check does (the Builder at its door).
     * Returns true when it was queued for the full rescan.
     */
    public boolean recheckBuilding(ServerLevel sl, Building building) {
        return buildings.recheck(sl, building);
    }

    public List<SavedBuilding> getSavedBuildings() {
        return buildings.getSavedBuildings();
    }

    public List<SavedBedAssignment> getSavedBedAssignments() {
        return beds.getSavedBedAssignments();
    }

    public void rememberBedAssignments(Collection<SavedBedAssignment> assignments) {
        beds.rememberBedAssignments(assignments);
    }

    public void rememberSavedBuildings(Collection<SavedBuilding> savedBuildings) {
        buildings.rememberSavedBuildings(savedBuildings);
        onMineLayoutChanged();
    }

    /** Mine reservations are derived from persistent building markers. */
    public void onMineLayoutChanged() {
        if (level != null) {
            VillageManagerRegistry.get(level.getServer()).mineLayoutChanged();
        }
    }

    public void setPersistentStateDirtyCallback(Runnable callback) {
        persistentStateDirtyCallback = callback != null ? callback : () -> {};
    }

    /**
     * Wired by {@link VillageManagerRegistry} to {@code isVillageDormantOrReconciling}
     * for this village's id, so subsystems that hold a {@code VillageManager}
     * reference (e.g. {@link com.alex.test.village.raid.RaidManager}) can check
     * dormancy without reaching into the registry's private sets directly.
     */
    public void setDormancyQuery(java.util.function.BooleanSupplier query) {
        dormancyQuery = query != null ? query : () -> false;
    }

    /** True while this village is dormant or reconciling — no player has been nearby recently. */
    public boolean isDormantOrReconciling() {
        return dormancyQuery.getAsBoolean();
    }

    private void markPersistentStateDirty() {
        persistentStateDirtyCallback.run();
    }

    /** Persistence hook for owned subsystem state such as nomad deadlines. */
    public void markPersistentDataDirty() {
        markPersistentStateDirty();
    }

    /** Conservative protection used by tree detection/chopping. */
    public boolean isProtectedTreePosition(BlockPos position) {
        if (!contains(position)) return true;
        // A jungle tree carrying a farmer's cocoa is a farm, not timber.
        if (farmerManager.isTendedCocoaLog(position)) return true;
        return buildings.isNearAnyRoom(position);
    }

    @Nullable
    public TreeStructure inspectTree(BlockPos base) {
        return lumberjackTrees.inspectTree(base);
    }

    /** {@link #inspectTree} with the tree judge's reason; null without a level. */
    @Nullable
    public TreeStructure.Inspection judgeTree(BlockPos base) {
        return lumberjackTrees.judgeTree(base);
    }

    /** Short-lived cached {@link #inspectTree}, for cheap work-availability probes only. */
    public TreeStructure inspectTreeCached(BlockPos base) {
        return lumberjackTrees.inspectTreeCached(base);
    }

    public boolean isTreeStructureStillSafe(TreeStructure tree) {
        return lumberjackTrees.isTreeStructureStillSafe(tree);
    }

    public void forgetTree(BlockPos base) {
        lumberjackTrees.forgetTree(base);
    }

    /** Returns the building whose item frame is at {@code frameId}, or {@code null}. */
    @Nullable
    public Building getBuilding(UUID frameId) { return buildings.get(frameId); }

    /** True only when this exact marker frame owns a currently valid town hall. */
    public boolean hasValidTownHall(UUID frameId) {
        return buildings.hasValidTownHall(frameId);
    }

    /**
     * True when the position lies inside this village's horizontal boundary.
     *
     * <p>A village owns its complete X/Z footprint at every build height.  The
     * navigation graph's Y range describes reachable floors, not a limit on
     * village membership; keeping Y out of this check lets a village founded at
     * the bottom of the world continue to own villagers and other village
     * activity high above it.</p>
     */
    public boolean contains(BlockPos pos) {
        return isInGraphBounds(pos.getX(), pos.getZ());
    }

    /**
     * True when a prospective village's chunk-square would overlap this
     * village's current navigation bounds.
     */
    public boolean overlapsCandidateBounds(BlockPos center, int chunkRadius) {
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int candidateMinX = (centerChunkX - chunkRadius) << 4;
        int candidateMaxX = ((centerChunkX + chunkRadius + 1) << 4) - 1;
        int candidateMinZ = (centerChunkZ - chunkRadius) << 4;
        int candidateMaxZ = ((centerChunkZ + chunkRadius + 1) << 4) - 1;
        if (navGraph == null) {
            if (deferredInit == null) return contains(center);
            // Not built yet: the square its map will cover.
            int cx = effectiveOrigin().getX() >> 4;
            int cz = effectiveOrigin().getZ() >> 4;
            return candidateMaxX >= (cx - graphChunkRadius) << 4
                    && candidateMinX <= ((cx + graphChunkRadius + 1) << 4) - 1
                    && candidateMaxZ >= (cz - graphChunkRadius) << 4
                    && candidateMinZ <= ((cz + graphChunkRadius + 1) << 4) - 1;
        }
        return candidateMaxX >= navGraph.getBoundsMinX()
                && candidateMinX <= navGraph.getBoundsMaxX()
                && candidateMaxZ >= navGraph.getBoundsMinZ()
                && candidateMinZ <= navGraph.getBoundsMaxZ();
    }

    /** Spawn position just inside the town hall belonging to this marker frame. */
    @Nullable
    public BlockPos getTownHallSpawnPos(UUID frameId) {
        Building building = buildings.get(frameId);
        if (!(building instanceof TownHallBuilding) || !building.isValid()) return null;
        BlockPos inside = building.getDoorPos().relative(building.getInteriorDirection());
        if (navGraph != null && navGraph.nearest(inside, 0, 1) != null) return inside;
        return building.getDoorPos();
    }

    // =========================================================================

    // =========================================================================
    // Villager discovery & spawning
    // =========================================================================

    private void discoverVillagers(ServerLevel sl) {
        AABB box = graphBounds(sl);
        List<NpcEntity> npcs = sl.getEntitiesOfClass(NpcEntity.class, box);
        for (NpcEntity npc : npcs) {
            if (!npc.isVillageMember()) continue;
            if (belongsToThisVillage(npc)) bindVillager(npc);
        }
        LOGGER.info("[VillageManager {}] Discovered {} NPC(s).", shortId(), npcs.size());
    }

    /** True while at least one logical member's puppet is unbound (chunk not yet loaded). */
    public boolean hasMissingPuppets() {
        for (VillagerModel m : allVillagers.values())
            if (m.puppet() == null || !m.puppet().isAlive() || m.puppet().isRemoved()) return true;
        return false;
    }

    /**
     * Gate for the reconciliation phase ({@link VillageManagerRegistry}'s
     * RECONCILING -> ACTIVE transition): every logical member must have a live
     * puppet rebound, and every building's door chunk (plus the Town Hall/bell
     * chunk) must be loaded, before the offline-economy projection runs against
     * real world state. The caller applies its own bounded timeout on top of
     * this so a village missing one stubborn chunk cannot stay stuck reconciling
     * forever.
     */
    public boolean isReadyForReconciliation(ServerLevel sl) {
        if (deferredInit != null) return false;
        if (hasMissingPuppets()) return false;
        // No bell: getBellPos() falls back to 0,0,0, which is not the village's ground.
        if (bellPos != null && !sl.hasChunkAt(bellPos)) return false;
        for (Building building : buildings.all()) {
            BlockPos door = building.getDoorPos();
            if (door != null && !sl.hasChunkAt(door)) return false;
        }
        return true;
    }

    /** The reconcile wait's rebind: members whose puppets are loaded but not bound yet. */
    public void rebindMissingPuppets(ServerLevel sl) {
        if (hasMissingPuppets()) rediscoverVillagers(sl);
    }

    private void rediscoverVillagers(ServerLevel sl) {
        AABB box = graphBounds(sl);
        List<NpcEntity> npcs = sl.getEntitiesOfClass(NpcEntity.class, box);
        int rebound = 0;
        for (NpcEntity npc : npcs) {
            if (!npc.isVillageMember()) continue;
            if (!belongsToThisVillage(npc)) continue;
            VillagerModel model = getOrCreateModel(npc.getNpcId());
            boolean wasMissing = model.puppet() == null || !model.puppet().isAlive()
                    || model.puppet().isRemoved();
            bindVillager(npc);
            if (wasMissing) rebound++;
        }
        if (rebound > 0)
            LOGGER.info("[VillageManager {}] Rebound {} NPC(s) after reload.", shortId(), rebound);
    }

    /** A new jobless member at {@code spawnPos}; {@code null} when it could not be made. */
    @Nullable
    public NpcEntity spawnVillager(BlockPos spawnPos) {
        if (level == null) return null;
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) return null;
        npc.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);
        npc.setVillageLink(new VillagerVillageLink(villageId, dimension, getBellPos()));
        level.addFreshEntity(npc);
        bindVillager(npc);
        markPersistentStateDirty();
        LOGGER.info("[VillageManager {}] Spawned villager {} at {}.",
                shortId(), npc.getNpcId().toString().substring(0, 8), spawnPos);
        return npc;
    }

    private boolean belongsToThisVillage(NpcEntity npc) {
        VillagerVillageLink link = npc.getVillageLink();
        if (link != null) return villageId.equals(link.villageId());
        return contains(npc.blockPosition());
    }

    /** Bind a loaded NPC and refresh its persistent repair metadata. */
    public void bindVillager(NpcEntity npc) {
        if (!npc.isVillageMember() || dimension == null) return;
        // With vanilla's NoAI on, a mob never runs its navigation or move
        // control, though a path it was given still reads as in progress. A
        // preview villager taken in before previews were kept out stood where
        // it was for good, moved only by the stuck rescue. No resident is ever
        // frozen on purpose.
        if (npc.isNoAi()) {
            npc.setNoAi(false);
            LOGGER.warn("[VillageManager {}] Villager {} was frozen (NoAI), likely a leftover preview; unfrozen.",
                    shortId(), npc.getNpcId().toString().substring(0, 8));
        }
        npc.setVillageLink(new VillagerVillageLink(villageId, dimension, getBellPos()));
        VillagerModel model = getOrCreateModel(npc.getNpcId());
        model.bind(npc);
        // Not loaded when the away catch-up ended: skips the uncounted days now (a child's age, a cook's appetite).
        if (npc.level() instanceof ServerLevel sl && sl.getServer() != null) {
            long skip = VillageManagerRegistry.get(sl.getServer()).takeOwedSkip(villageId, npc.getNpcId());
            if (skip > 0L) com.alex.test.village.economy.away.AwaySkip.skipUncountedAway(npc, skip);
        }
        model.noteSeen();
        if (model.noteName(npc)) markPersistentStateDirty();
        beds.queueBarracksAssignments();
    }

    /** Registers a newly created resident and persists its logical membership. */
    public void registerVillager(NpcEntity npc) {
        bindVillager(npc);
        markPersistentStateDirty();
    }

    /** Refresh backup Town Hall metadata on every currently loaded member. */
    public void refreshLoadedVillagerLinks() {
        if (dimension == null) return;
        VillagerVillageLink link = new VillagerVillageLink(villageId, dimension, getBellPos());
        for (VillagerModel model : allVillagers.values()) {
            NpcEntity npc = model.puppet();
            if (npc != null && npc.isAlive() && !npc.isRemoved()) npc.setVillageLink(link);
        }
    }

    /** Stable logical villager IDs persisted as an orphan-recovery index. */
    public Set<UUID> getVillagerIds() {
        return Collections.unmodifiableSet(allVillagers.keySet());
    }

    /** Recreate logical membership from SavedData before entities load. */
    public void rememberVillagerIds(Collection<UUID> villagerIds) {
        for (UUID villagerId : villagerIds) getOrCreateModel(villagerId);
    }

    /** Pause runtime tasks without deleting logical villager membership. */
    public void pauseForMissingTownHall() {
        if (level == null) return;
        // A raid would stand frozen while the village does not tick.
        raidManager.onVillageInactive(level);
        for (VillagerModel model : allVillagers.values()) {
            NpcEntity npc = model.puppet();
            if (npc != null && npc.isAlive() && !npc.isRemoved()) {
                model.brain().pauseForVillageInactive(this, level, npc);
            }
            model.stopMoving();
            claims.releaseAll(model.npcId);
            releaseTreeJobs(model.npcId);
            farmerManager.release(model.npcId);
        }
        claims.clear();
        lumberjackTrees.clear();
    }

    /**
     * Captains among the members: the live job of a loaded villager, else the
     * job it had when it was last seen. Older saves only knew an unloaded
     * captain by its barracks captain's bed, so that still counts too.
     */
    public Set<UUID> captainIds() {
        Set<UUID> captains = new LinkedHashSet<>();
        for (VillagerModel model : allVillagers.values()) {
            NpcEntity npc = model.puppet();
            boolean captain;
            if (npc != null && !npc.isRemoved()) {
                captain = npc.isAlive()
                        && npc.getProfession() == com.alex.test.village.villagers.core.Profession.CAPTAIN;
            } else {
                SavedBedAssignment claim = beds.claimOf(model.npcId);
                captain = knownCaptains.contains(model.npcId) || claim != null && claim.barracksCaptain();
            }
            if (captain) captains.add(model.npcId);
        }
        return captains;
    }

    /** Restores who was a captain from SavedData, before their entities load. */
    public void rememberCaptains(Collection<UUID> captainIds) {
        knownCaptains.addAll(captainIds);
    }

    /** Members' names as last seen, saved so screens can name villagers that are not loaded. */
    public Map<UUID, String> knownNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (VillagerModel model : allVillagers.values()) {
            String name = model.name();
            if (name != null) names.put(model.npcId, name);
        }
        return names;
    }

    /** Restores members' names from SavedData, before their entities load. */
    public void rememberNames(Map<UUID, String> names) {
        names.forEach((id, name) -> {
            VillagerModel model = allVillagers.get(id);
            if (model != null) model.rememberName(name);
        });
    }

    /**
     * The name screens show for a member: its own name, loaded or not, else a
     * plain stand-in for one not seen since names started being saved.
     */
    public static String displayName(VillagerModel model) {
        String name = model.name();
        if (name != null) return name;
        return model.puppet() != null ? "Unnamed villager" : "Unnamed villager (far away)";
    }

    // -------------------------------------------------------------------------
    // Missing members
    //
    // A villager can vanish without the village hearing of a death, and then it
    // held a bed and counted as a resident for good. Nothing here is decided by
    // time passing (players stand AFK for hours). Minecraft loads a villager with
    // the chunk it is saved in, so a member whose last spot is loaded and empty,
    // or whose spot is unknown (older saves), has the world's save files searched
    // for its body (MissingMemberSearch). Found: its spot is updated. Missing from
    // two clean searches minutes apart: it is gone.
    // -------------------------------------------------------------------------

    private static final int MISSING_CHECK_TICKS = 100;
    /** Checks in a row (15 s) the last-seen spot must stay loaded and empty. */
    private static final int GONE_CHECKS = 3;
    /** Village ticks to wait after loading, so villagers still joining are not looked for. */
    private static final int MISSING_WARMUP_TICKS = 60 * 20;
    /** How far from its last spot a loaded villager is looked for. */
    private static final int GONE_SEARCH_RADIUS = 48;
    private int missingWarmup = MISSING_WARMUP_TICKS;

    /** Members' last-seen spots, saved with the village. */
    public Map<UUID, BlockPos> lastSeenSpots() {
        Map<UUID, BlockPos> spots = new LinkedHashMap<>();
        for (VillagerModel model : allVillagers.values()) {
            // A loaded villager is saved where it stands: a quit never unbinds it.
            NpcEntity npc = model.puppet();
            if (npc != null && npc.isAlive() && !npc.isRemoved() && npc.level() == level) {
                spots.put(model.npcId, npc.blockPosition().immutable());
            } else if (model.lastSeenSpot() != null) {
                spots.put(model.npcId, model.lastSeenSpot());
            }
        }
        return spots;
    }

    /** Dimensions of last-seen spots outside the village's own (a trip through a portal), saved with the village. */
    public Map<UUID, ResourceKey<Level>> lastSeenDimensions() {
        Map<UUID, ResourceKey<Level>> dimensions = new LinkedHashMap<>();
        for (VillagerModel model : allVillagers.values()) {
            NpcEntity npc = model.puppet();
            boolean loadedHere = npc != null && npc.isAlive() && !npc.isRemoved() && npc.level() == level;
            if (!loadedHere && model.lastSeenSpot() != null && model.lastSeenDimension() != null) {
                dimensions.put(model.npcId, model.lastSeenDimension());
            }
        }
        return dimensions;
    }

    /** Restores last-seen spots from SavedData, before entities load. */
    public void rememberSightings(Map<UUID, BlockPos> spots, Map<UUID, ResourceKey<Level>> dimensions) {
        for (VillagerModel model : allVillagers.values()) {
            model.rememberSighting(spots.get(model.npcId), dimensions.get(model.npcId));
        }
    }

    /** {@code dimension} as a spot's dimension: null for the village's own. */
    @Nullable
    ResourceKey<Level> spotDimension(ResourceKey<Level> dimension) {
        return level != null && dimension == level.dimension() ? null : dimension;
    }

    private void tickMissingMembers(ServerLevel sl) {
        if (missingWarmup > 0) {
            missingWarmup--;
            return;
        }
        if (Math.floorMod(sl.getGameTime() + villageId.hashCode(), MISSING_CHECK_TICKS) != 0) return;
        boolean dirty = false;
        for (VillagerModel model : allVillagers.values()) {
            NpcEntity puppet = model.puppet();
            if (puppet != null) {
                // Keeps the saved spot near it, so a save is fresh even without an unload.
                if (puppet.isAlive() && !puppet.isRemoved() && puppet.level() == sl
                        && model.noteLoadedAt(puppet.blockPosition())) dirty = true;
                continue;
            }
            if (model.lookingForBody()) continue;
            BlockPos spot = model.lastSeenSpot();
            ServerLevel spotLevel = spot == null ? null : model.lastSeenDimension() == null
                    ? sl : sl.getServer().getLevel(model.lastSeenDimension());
            if (spotLevel == null) {
                // Spot unknown (older saves) or in a dimension no longer there.
                model.lookForBody();
                continue;
            }
            if (!spotLevel.areEntitiesLoaded(ChunkPos.asLong(spot))) {
                model.clearGoneChecks();
            } else if (model.addGoneCheck() >= GONE_CHECKS) {
                model.clearGoneChecks();
                // Last look around the spot: one that is there but was never bound is not gone.
                NpcEntity found = findMember(spotLevel, model.npcId, spot);
                if (found == null) {
                    model.lookForBody();
                    LOGGER.info("[VillageManager {}] Villager {} is not at its last spot {} though that is loaded;"
                            + " searching the save files for it.", shortId(),
                            model.npcId.toString().substring(0, 8), spot.toShortString());
                } else if (spotLevel == sl && belongsToThisVillage(found)) {
                    bindVillager(found);
                } else if (model.noteBodyAt(spotDimension(spotLevel.dimension()), found.blockPosition())) {
                    dirty = true;
                }
            }
        }
        if (dirty) markPersistentStateDirty();
    }

    /** Members not loaded that wait for the save files to be searched for their bodies. */
    List<UUID> membersToLookFor() {
        List<UUID> ids = new ArrayList<>();
        for (VillagerModel model : allVillagers.values()) {
            if (model.puppet() == null && model.lookingForBody()) ids.add(model.npcId);
        }
        return ids;
    }

    /** A search found {@code model}'s body: it is not gone, and is looked for there from now on. */
    void noteBodyFound(VillagerModel model, ResourceKey<Level> bodyDimension, BlockPos pos, @Nullable String name) {
        boolean changed = model.noteBodyAt(spotDimension(bodyDimension), pos);
        if (name != null && model.name() == null) {
            model.rememberName(name);
            changed = true;
        }
        if (changed) markPersistentStateDirty();
    }

    /**
     * Takes members no search could find off the village list. Quietly: they
     * no longer existed, so this is cleanup the player never needs to hear of;
     * the log says who and why.
     */
    void forgetGone(List<VillagerModel> gone) {
        if (gone.isEmpty() || level == null) return;
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        for (VillagerModel model : gone) {
            String name = model.name();
            LOGGER.info("[VillageManager {}] Villager {} ({}) is gone: no body in the world, loaded or saved,"
                            + " in two searches. Taken off the village list, bed freed.",
                    shortId(), model.npcId.toString().substring(0, 8), name != null ? name : "no name");
            registry.forgetVillager(this, model.npcId);
        }
    }

    @Nullable
    private static NpcEntity findMember(ServerLevel sl, UUID npcId, BlockPos spot) {
        AABB area = new AABB(spot.getX() - GONE_SEARCH_RADIUS, sl.getMinBuildHeight(), spot.getZ() - GONE_SEARCH_RADIUS,
                spot.getX() + GONE_SEARCH_RADIUS, sl.getMaxBuildHeight(), spot.getZ() + GONE_SEARCH_RADIUS);
        List<NpcEntity> found = sl.getEntitiesOfClass(NpcEntity.class, area,
                npc -> npc.isAlive() && npcId.equals(npc.getNpcId()));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Remembers whether {@code npc} is a captain, for while it is not loaded. */
    private void noteCaptain(NpcEntity npc) {
        boolean changed = npc.getProfession() == com.alex.test.village.villagers.core.Profession.CAPTAIN
                ? knownCaptains.add(npc.getNpcId())
                : knownCaptains.remove(npc.getNpcId());
        if (changed) markPersistentStateDirty();
    }

    /** Drop only the runtime puppet reference when its chunk unloads. */
    public void unbindVillager(NpcEntity npc) {
        VillagerModel model = allVillagers.get(npc.getNpcId());
        if (model != null) {
            noteCaptain(npc);
            // Where it is saved: when that spot is loaded again and it is not, it is gone.
            boolean spotChanged = model.noteUnloadedAt(spotDimension(npc.level().dimension()), npc.blockPosition());
            if (model.noteName(npc) | spotChanged) markPersistentStateDirty();
            if (level != null) model.brain().onPuppetUnloaded(this, level, npc);
            releaseTreeJobs(model.npcId);
            farmerManager.release(model.npcId);
            claims.releaseAll(model.npcId);
            model.unbind(npc);
        }
    }

    /**
     * Permanently unregister a villager that died. Unlike {@link #unbindVillager},
     * this removes logical membership so saved data and Mayor stats stop listing it.
     */
    public boolean removeVillager(UUID villagerId) {
        VillagerModel model = allVillagers.remove(villagerId);
        if (model == null) return false;

        NpcEntity npc = model.puppet();
        boolean wasCaptain = npc != null
                && npc.getProfession() == com.alex.test.village.villagers.core.Profession.CAPTAIN;
        if (npc != null && level != null) {
            model.brain().onPuppetUnloaded(this, level, npc);
            model.unbind(npc);
        } else {
            model.stopMoving();
        }
        if (wasCaptain && level != null) {
            com.alex.test.village.chat.CaptainNotices.captainLost(level, this);
        }

        claims.releaseAll(villagerId);
        releaseTreeJobs(villagerId);
        farmerManager.release(villagerId);
        releaseBedFor(villagerId);
        knownCaptains.remove(villagerId);

        markPersistentStateDirty();

        LOGGER.info("[VillageManager {}] Removed villager {} (died or gone).",
                shortId(), villagerId.toString().substring(0, 8));
        return true;
    }

    /**
     * Re-register a Town Hall whose item-frame entity loaded after the manager.
     * This is intentionally local and does not rebuild the village.
     */
    public boolean registerLoadedTownHall(ServerLevel sl, ItemFrame frame) {
        // Not built yet: its building restore registers the hall from its saved marker.
        if (deferredInit != null) return false;
        return buildings.registerLoadedTownHall(sl, frame);
    }

    /** Where this Town Hall's marker frame hangs, or null when neither the hall nor its saved marker is known. */
    @Nullable
    public BlockPos getTownHallFramePos(UUID frameId) {
        return buildings.townHallFramePos(frameId);
    }

    /** The Town Hall's own frame still hangs in place with a Town Hall token that is not another village's. */
    public boolean isTownHallFramePresent(ServerLevel sl, UUID frameId) {
        return buildings.isTownHallFramePresent(sl, frameId);
    }

    /** Quietly checks a present-but-invalid Town Hall again; true when it is valid now. */
    public boolean retryTownHall(ServerLevel sl, UUID frameId) {
        return buildings.retryTownHall(sl, frameId);
    }

    /** Remove stale Town Hall building objects before orphan restoration. */
    public void clearTownHallsForRecovery() {
        buildings.clearTownHallsForRecovery();
    }

    // =========================================================================
    // NeoForge event handlers
    // (For the INSTANCE/command village these are registered directly.
    //  For registry villages, VillageManagerRegistry.Events routes to onXxx().)
    // =========================================================================

    //        Nav events

    /** Block broken: update nav only when walkability may change; update farms and buildings regardless. */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (!isDimensionMatch(sl)) return;

        BlockPos pos = event.getPos();
        farmerManager.onBlockChanged(pos);
        if (NavGraph.blockChangeAffectsNavigation(sl, pos, event.getState(),
                Blocks.AIR.defaultBlockState())) {
            markDirtyAt(pos.getX(), pos.getY(), pos.getZ(), involvesWater(event.getState(), null));
        }
        buildings.checkDoorBreak(pos);           // remove building if its door was just broken
        buildings.markBuildingsDirtyAt(pos);

        // Knowledge: remove resource entries whose base block was just broken.
        onBreakKnowledgeUpdate(pos, sl);
    }

    /** Block placed: update nav only when walkability may change; update farms and buildings regardless. */
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (!isDimensionMatch(sl)) return;
        BlockPos pos = event.getPos();
        farmerManager.onBlockPlaced(sl, pos);
        if (NavGraph.blockChangeAffectsNavigation(sl, pos,
                event.getBlockSnapshot().getState(), event.getPlacedBlock())) {
            markDirtyAt(pos.getX(), pos.getY(), pos.getZ(),
                    involvesWater(event.getBlockSnapshot().getState(), event.getPlacedBlock()));
        }
        buildings.markBuildingsDirtyAt(pos);
    }

    /** Tool right-click (path, till, strip): checked next tick; see {@link #pendingToolChecks}. */
    @SubscribeEvent
    public void onToolModify(BlockEvent.BlockToolModificationEvent event) {
        if (event.isSimulated()) return;
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (!isDimensionMatch(sl)) return;
        routeToolModification(event.getPos(), event.getState());
    }

    /**
     * Player interacts with an entity (general INTERACT packet).
     * If the target is an ItemFrame, queue its position for building creation.
     */
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        queueFrameIfPresent(event.getLevel(), event.getTarget());
    }

    /**
     * Player interacts with an entity at a specific point (INTERACT_AT packet).
     * Item frames sometimes take this path instead of the general interact event.
     */
    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        queueFrameIfPresent(event.getLevel(), event.getTarget());
    }

    /** Shared logic: queue the ItemFrame entity for next-tick building creation. */
    private void queueFrameIfPresent(net.minecraft.world.level.LevelAccessor level,
                                     net.minecraft.world.entity.Entity target) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!isDimensionMatch(sl)) return;
        if (!(target instanceof ItemFrame frame)) return;
        BlockPos framePos = frame.blockPosition();
        if (navGraph == null) {
            LOGGER.info("[VillageManager {}] Item-frame interaction at {} ignored     no navgraph yet.", shortId(), framePos);
            return;
        }
        if (!isInGraphBounds(framePos.getX(), framePos.getZ())) {
            LOGGER.info("[VillageManager {}] Item-frame at {} is outside navgraph bounds     ignoring.", shortId(), framePos);
            return;
        }
        if (buildings.queueFrameCheck(framePos)) {
            LOGGER.info("[VillageManager {}] Queued item-frame at {} for building check.", shortId(), framePos);
        }
    }

    //        Knowledge events

    /**
     * Sapling growing into a tree.  The tree is not yet in the world at this point
     * (this event fires before feature generation), so we defer the block check to
     * the next tick via {@link #pendingGrowChecks}.
     */
    @SubscribeEvent
    public void onTreeGrow(BlockGrowFeatureEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        if (!isDimensionMatch((ServerLevel) event.getLevel())) return;
        if (!isInGraphBounds(event.getPos().getX(), event.getPos().getZ())) return;
        markTreeDirtyAt(event.getPos().getX(), event.getPos().getY(), event.getPos().getZ());
        pendingGrowChecks.add(event.getPos());
    }

    /**
     * Something was bonemealed.  Deferred to the next tick like a sapling: the
     * event fires with the state <em>before</em> the growth is applied, so
     * reading the block here would always see the immature crop.
     *
     * @see #pendingBonemealChecks
     */
    @SubscribeEvent
    public void onBonemeal(BonemealEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        if (!isDimensionMatch((ServerLevel) event.getLevel())) return;
        routeBonemeal(event.getPos());
    }

    /**
     * Crop finished growing one stage.  If it is now fully mature, add it to the
     * knowledge base and plan a harvest task.
     */
    @SubscribeEvent
    public void onCropGrow(CropGrowEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        if (!isDimensionMatch((ServerLevel) event.getLevel())) return;
        routeCropGrow(event.getPos(), event.getState());
    }

    /**
     * Entity entered the world.  If it is an {@link ItemFrame} inside our graph
     * area, register it as a room marker.
     */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        if (!isDimensionMatch((ServerLevel) event.getLevel())) return;
        if (!(event.getEntity() instanceof ItemFrame frame)) return;

        BlockPos pos = frame.blockPosition();
        if (!isInGraphBounds(pos.getX(), pos.getZ())) return;

        if (knowledgeBase.add(Category.ROOM, pos)) {
            LOGGER.debug("[VillageManager {}] Room marker at {}.", shortId(), pos);
        }
    }

    /**
     * Entity left the world.  If it is an {@link ItemFrame} that belongs to a
     * registered building, remove that building.
     *
     * <p><b>Important:</b> {@link EntityLeaveLevelEvent} also fires when a chunk is
     * unloaded ({@link net.minecraft.world.entity.Entity.RemovalReason#UNLOADED_TO_CHUNK}
     * / {@link net.minecraft.world.entity.Entity.RemovalReason#UNLOADED_WITH_PLAYER}).
     * We must NOT delete the building in those cases     the frame is coming back.
     * Only remove the building when the frame was truly destroyed (KILLED / DISCARDED).
     */
    @SubscribeEvent
    public void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        if (!isDimensionMatch((ServerLevel) event.getLevel())) return;
        if (!(event.getEntity() instanceof ItemFrame frame)) return;
        // Keep buildings across chunk unloads.
        net.minecraft.world.entity.Entity.RemovalReason reason = frame.getRemovalReason();
        if (reason == null
                || reason == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            return;
        }
        routeEntityLeave(frame);
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (navGraph == null || dimension == null) return;
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (!sl.dimension().equals(dimension)) return;
        if (DynamicVillages.isServerStopping()
                || (sl.getServer().getPlayerList().getPlayerCount() == 0
                && !TestKit.headless())) {
            pauseRuntimeMovement();
            return;
        }
        tick(sl);
    }

    // =========================================================================
    // Routable methods (called by VillageManagerRegistry.Events for bell villages)
    // =========================================================================

    /** @see #onBlockBreak */
    public void routeBlockBreak(ServerLevel sl, BlockPos pos) {
        routeBlockBreak(sl, pos, true);
    }

    void routeBlockBreak(ServerLevel sl, BlockPos pos, boolean navigationChanged) {
        farmerManager.onBlockChanged(pos);
        if (navigationChanged) markDirtyAt(pos);
        buildings.checkDoorBreak(pos);           // remove building if its door was just broken
        buildings.markBuildingsDirtyAt(pos);
        cauldrons.onBlockChanged(this, pos);
        onBreakKnowledgeUpdate(pos, sl);
    }

    /** @see #onBlockPlace */
    public void routeBlockPlace(BlockPos pos) {
        routeBlockPlace(pos, true);
    }

    void routeBlockPlace(BlockPos pos, boolean navigationChanged) {
        // Placed already: the farm can read the new block (see onBlockPlaced).
        farmerManager.onBlockPlaced(level, pos);
        if (navigationChanged) markDirtyAt(pos);
        buildings.markBuildingsDirtyAt(pos);
        cauldrons.onBlockChanged(this, pos);
    }

    /** @see #onToolModify */
    public void routeToolModification(BlockPos pos, BlockState before) {
        // A graph not built yet will read the block as it is when it is.
        if (navGraph == null || !isInGraphBounds(pos.getX(), pos.getZ())) return;
        pendingToolChecks.putIfAbsent(pos.immutable(), before);
    }

    /** @see #onTreeGrow */
    public void routeTreeGrow(BlockPos saplingPos) {
        if (!isInGraphBounds(saplingPos.getX(), saplingPos.getZ())) return;
        markTreeDirtyAt(saplingPos.getX(), saplingPos.getY(), saplingPos.getZ());
        pendingGrowChecks.add(saplingPos);
    }

    /** @see #onBonemeal */
    public void routeBonemeal(BlockPos pos) {
        if (!isInGraphBounds(pos.getX(), pos.getZ())) return;
        pendingBonemealChecks.add(pos);
    }

    /** @see #onCropGrow */
    public void routeCropGrow(BlockPos pos, BlockState newState) {
        if (!isInGraphBounds(pos.getX(), pos.getZ())) return;
        if (newState.is(Blocks.SUGAR_CANE)) {
            registerCane(pos);
            farmerManager.onCropMatured(pos);
            return;
        }
        // A stem drops its melon or pumpkin into a neighbouring cell with a
        // plain setBlock, and this event names the stem, not the fruit. No
        // break/place event fires, so the graph kept flat ground where a full
        // block now stands and villagers walked into it without jumping.
        if (newState.getBlock() instanceof StemBlock
                || newState.getBlock() instanceof AttachedStemBlock) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                markDirtyAt(pos.getX() + dir.getStepX(), pos.getY(), pos.getZ() + dir.getStepZ(), false);
            }
            // The farm survey only learns about new fruit when its site is
            // re-surveyed. Without this the fruit stayed invisible until the
            // slow round-robin validation reached the field, so a farmer
            // standing next to a melon found no work and went to leisure.
            // Only an attached stem means fruit was just placed; plain stem
            // aging only matters for a field no farm covers yet (discovery).
            farmerManager.onStemGrow(pos, newState.getBlock() instanceof AttachedStemBlock);
            return;
        }
        // Cocoa and sweet berries are no CropBlock; their patches track each stage.
        if (FarmerManager.isPerennialCrop(newState)) {
            if (level != null) farmerManager.onPerennialGrow(level, pos, newState);
            return;
        }
        if (!(newState.getBlock() instanceof CropBlock crop)) return;
        if (crop.getAge(newState) < crop.getMaxAge()) return;
        farmerManager.onCropMatured(pos, newState);
        if (knowledgeBase.add(Category.CROP, pos)) {
            LOGGER.debug("[VillageManager {}] Crop matured at {}.", shortId(), pos);
        }
    }

    /**
     * Learns a cane plant from a segment that just grew.
     *
     * <p>`SugarCaneBlock.randomTick` fires the same growth event a crop does,
     * for the new segment. Cane knowledge holds the *base* of a plant -- the
     * farmer cuts above it and leaves the base to regrow -- so this walks down
     * to the bottom of the stack.
     *
     * <p>Without this, cane was registered only by the one-shot scan at village
     * creation, and a player who founded a village and then planted a cane farm
     * had planted something no farmer could ever see. Growth is the right
     * moment to learn it: a single base block with nothing above it is not
     * harvestable anyway.
     */
    private void registerCane(BlockPos grownAt) {
        if (level == null) return;
        BlockPos base = grownAt;
        while (level.getBlockState(base.below()).is(Blocks.SUGAR_CANE)) {
            base = base.below();
            if (base.getY() <= level.getMinBuildHeight()) return;
        }
        if (knowledgeBase.add(Category.SUGARCANE, base)) {
            LOGGER.debug("[VillageManager {}] Cane learned at {}.", shortId(), base);
        }
    }

    /** @see #onEntityJoin */
    /** An armor stand joined or left the world here: a barracks may have gained or lost a post. */
    public void routeArmorStandChange(BlockPos pos) {
        if (!isInGraphBounds(pos.getX(), pos.getZ())) return;
        pendingArmorStandChecks.add(pos.immutable());
    }

    private void processPendingArmorStands(ServerLevel sl) {
        if (pendingArmorStandChecks.isEmpty()) return;
        for (Building building : buildings.all()) {
            if (!(building instanceof com.alex.test.village.building.BarracksBuilding barracks)
                    || !barracks.isValid()) continue;
            for (BlockPos pos : pendingArmorStandChecks) {
                if (barracks.isNearRoom(pos)) {
                    barracks.refreshPosts(sl);
                    break;
                }
            }
        }
        pendingArmorStandChecks.clear();
    }

    public void routeEntityJoin(ItemFrame frame) {
        BlockPos pos = frame.blockPosition();
        if (!isInGraphBounds(pos.getX(), pos.getZ())) return;
        knowledgeBase.add(Category.ROOM, pos);
    }

    /**
     * ItemFrame entity was removed from the world (broken by a player or explosion).
     * Removes any building whose marker frame is this entity.
     */
    public void routeEntityLeave(ItemFrame frame) {
        UUID frameId = frame.getUUID();
        if (level != null && buildings.isRegistered(frameId)) {
            buildings.removeBuilding(frameId, level, "item frame at " + frame.blockPosition() + " was destroyed",
                    com.alex.test.Tr.c("log.dynamicvillages.building.removed.frame", "Its item frame was broken."), frame);
            LOGGER.info("[VillageManager {}] Building removed     item frame at {} was destroyed.",
                    shortId(), frame.blockPosition());
        }
    }

    /**
     * A building just left the village ({@link VillageBuildings#removeBuilding}):
     * whoever is going to bed or asleep in it gets up and looks elsewhere now.
     * Runs once per removal; nothing checks for this on a timer.
     */
    void onBuildingRemoved(Building gone) {
        if (level == null) return;
        for (VillagerModel model : allVillagers.values()) {
            if (!(model.brain().currentActivity() instanceof GoHomeActivity home)) continue;
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved()) continue;
            home.onBuildingRemoved(new com.alex.test.village.brain.Activity.Context(
                    model.brain(), model, this, level, npc), gone);
        }
    }

    /** Whether {@code building} lists {@code bed} among its beds (house, Town Hall, Barracks, Mage Tower). */
    public boolean isBedIn(Building building, BlockPos bed) {
        return VillageBeds.buildingContainsBed(building, bed);
    }

    // =========================================================================
    // Knowledge update helpers
    // =========================================================================

    /**
     * Called when a block is broken (both from {@link #onBlockBreak} for the INSTANCE
     * and from {@link #routeBlockBreak} for bell villages).
     *
     * <p>If the broken block was the base of a known tree or the position of a known
     * crop, the entry is removed from the KB (and any claim on it released).
     */
    private void onBreakKnowledgeUpdate(BlockPos pos, ServerLevel sl) {
        // Check if a known tree base was removed
        if (knowledgeBase.remove(Category.TREE, pos)) {
            lumberjackTrees.abandonTree(pos);
            LOGGER.debug("[VillageManager {}] Tree base at {} was broken     KB updated.", shortId(), pos);
        }
        // Check if a known crop was removed / harvested
        if (knowledgeBase.remove(Category.CROP, pos)) {
            LOGGER.debug("[VillageManager {}] Crop at {} was broken     KB updated.", shortId(), pos);
        }
        // Sugarcane: removing the base of a cane plant
        if (knowledgeBase.remove(Category.SUGARCANE, pos)) {
            LOGGER.debug("[VillageManager {}] Sugarcane base at {} was broken     KB updated.", shortId(), pos);
        }
    }

    // =========================================================================
    // Nav dirty helpers
    // =========================================================================

    /**
     * A villager changed a block itself, outside any NeoForge event.
     *
     * <p>The nav graph and the building shells are kept up to date by the
     * player-driven break and place events. A villager writing blocks straight
     * into the world -- the miner digging its shaft -- fires neither, so the
     * corridor it just opened would stay unwalkable and it could never follow
     * its own tunnel in. This is the same bookkeeping {@link #onBlockBreak}
     * does, minus the door and knowledge handling that only a player break
     * needs.
     */
    public void noteBlockChanged(BlockPos pos) {
        markDirtyAt(pos);
        buildings.markBuildingsDirtyAt(pos);
    }

    private void markDirtyAt(int x, int y, int z, boolean water) {
        if (!isInGraphBounds(x, z)) return;
        navDirty.mark(x, y, z, water);
    }

    /** As {@link #markDirtyAt(int, int, int, boolean)}, judging water from the block as it is now. */
    private void markDirtyAt(BlockPos pos) {
        if (!isInGraphBounds(pos.getX(), pos.getZ())) return;
        boolean water = level != null && level.hasChunkAt(pos) && involvesWater(level.getBlockState(pos), null);
        navDirty.mark(pos.getX(), pos.getY(), pos.getZ(), water);
    }

    /**
     * Whether either block state is water or waterlogged. A change that
     * involves water makes the patch take the whole column: a ledge far
     * above can hold a drop into a pool that just changed.
     */
    private static boolean involvesWater(@Nullable BlockState before, @Nullable BlockState after) {
        return isWatery(before) || isWatery(after);
    }

    private static boolean isWatery(@Nullable BlockState state) {
        if (state == null) return false;
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return true;
        return state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED);
    }

    /** True while a patch or a full build is pending; used by tests to wait one out. */
    public boolean hasPendingNavRebuild() {
        return pendingFullBuild != null || navDirty.hasPendingRebuild();
    }

    /** A villager changed the block at {@code pos}; re-scan the nav graph there. */
    public void markNavigationDirtyAt(BlockPos pos) {
        markDirtyAt(pos);
    }

    private void markTreeNavigationDirty(TreeStructure tree) {
        markTreeDirtyAt(tree.base().getX(), tree.base().getY(), tree.base().getZ());
        for (BlockPos log : tree.logs()) markTreeDirtyAt(log.getX(), log.getY(), log.getZ());
        for (BlockPos leaf : tree.leaves()) markTreeDirtyAt(leaf.getX(), leaf.getY(), leaf.getZ());
    }

    /**
     * A block of a tree that just grew. The tree lands whole in one tick, so
     * the map re-reads it about a second later ({@link NavDirtyTracker#markSoon})
     * instead of after the ten-second quiet wait: until then it offered the
     * sapling's tile as open grass, and the druid that grew the tree planned
     * its next walk straight through the new trunk (10-18).
     */
    private void markTreeDirtyAt(int x, int y, int z) {
        if (!isInGraphBounds(x, z)) return;
        if (WalkFeel.newTree) navDirty.markSoon(x, y, z, false);
        else navDirty.mark(x, y, z, false);
    }

    /**
     * Whether the map round {@code pos} has changes it has not re-read yet, or
     * a full build is under way: a walk planned now could still go through a
     * block that was placed or grown there.
     */
    public boolean isNavCatchingUpNear(BlockPos pos, int radius) {
        return pendingFullBuild != null || navDirty.isDirtyNear(pos.getX(), pos.getZ(), radius);
    }

    private boolean isInGraphBounds(int x, int z) {
        if (navGraph == null) {
            // Not built yet: the square its map will cover, so a player
            // walking in is seen and wakes it.
            if (deferredInit == null) return false;
            int cx = effectiveOrigin().getX() >> 4;
            int cz = effectiveOrigin().getZ() >> 4;
            return x >= (cx - graphChunkRadius) << 4 && x <= ((cx + graphChunkRadius + 1) << 4) - 1
                    && z >= (cz - graphChunkRadius) << 4 && z <= ((cz + graphChunkRadius + 1) << 4) - 1;
        }
        return x >= navGraph.getBoundsMinX() && x <= navGraph.getBoundsMaxX()
                && z >= navGraph.getBoundsMinZ() && z <= navGraph.getBoundsMaxZ();
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    private void clearTaskState() {
        for (VillagerModel m : allVillagers.values()) {
            claims.releaseAll(m.npcId);
            releaseTreeJobs(m.npcId);
            farmerManager.release(m.npcId);
            m.stopMoving();
        }
        claims.clear();
        lumberjackTrees.clear();
        farmerManager.clearAssignments();
        pendingGrowChecks.clear();
        growRetries.clear();
        pendingBonemealChecks.clear();
        pendingToolChecks.clear();
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private boolean isDimensionMatch(ServerLevel sl) {
        return dimension == null || sl.dimension().equals(dimension);
    }

    private BlockPos effectiveOrigin() {
        return bellPos != null ? bellPos : BlockPos.ZERO;
    }

    private AABB graphBounds(ServerLevel sl) {
        if (navGraph != null) {
            return new AABB(
                    navGraph.getBoundsMinX(), sl.getMinBuildHeight(), navGraph.getBoundsMinZ(),
                    navGraph.getBoundsMaxX(), sl.getMaxBuildHeight(), navGraph.getBoundsMaxZ());
        }
        int r = (graphChunkRadius * 2 + 1) * 16;
        BlockPos o = effectiveOrigin();
        return new AABB(o.getX() - r, sl.getMinBuildHeight(), o.getZ() - r,
                o.getX() + r, sl.getMaxBuildHeight(), o.getZ() + r);
    }

    /**
     * Sends every player in this village's dimension the borders of all its
     * villages. The client keeps exactly the list it was last sent, so sending
     * this village's border alone (as this used to) wiped every other border
     * a player could see whenever any village drew a new map.
     */
    public void broadcastBounds() {
        if (level == null) return;
        if (this == INSTANCE) {
            AABB box = borderBounds();
            if (box != null) PacketDistributor.sendToAllPlayers(new VillageBoundsPayload(List.of(box)));
            return;
        }
        VillageManagerRegistry.get(level.getServer()).broadcastBounds(level, this);
    }

    @Nullable
    public AABB borderBounds() {
        if (navGraph == null || navGraph.isEmpty() || level == null) return null;
        return new AABB(
                navGraph.getBoundsMinX(), level.getMinBuildHeight(), navGraph.getBoundsMinZ(),
                navGraph.getBoundsMaxX(), level.getMaxBuildHeight(), navGraph.getBoundsMaxZ());
    }

    /**
     * The village's land in chunks, {minX, minZ, maxX, maxZ} inclusive (village claims, Land tab): the square
     * {@link #contains} uses, or the one around the Town Hall its map will cover (not built yet, or paused).
     * Null with no Town Hall.
     */
    @Nullable
    public int[] landChunks() {
        if (navGraph != null) {
            return new int[] {navGraph.getBoundsMinX() >> 4, navGraph.getBoundsMinZ() >> 4,
                    navGraph.getBoundsMaxX() >> 4, navGraph.getBoundsMaxZ() >> 4};
        }
        if (bellPos == null) return null;
        int cx = bellPos.getX() >> 4;
        int cz = bellPos.getZ() >> 4;
        return new int[] {cx - graphChunkRadius, cz - graphChunkRadius, cx + graphChunkRadius, cz + graphChunkRadius};
    }

    String shortId() { return villageId.toString().substring(0, 8); }

    // =========================================================================
    // Public API
    // =========================================================================

    public NavGraph       getGraph()        { return navGraph; }
    public NavGraph       graph()           { return navGraph; }

    //        Brain-facing API

    /**
     * All villager models in this village (read-only iteration). During a step
     * of an away catch-up, not the ones that starved to death earlier in it
     * ({@link #setAwayGone}): they live in the world until its write-back.
     */
    public java.util.Collection<VillagerModel> getVillagers() {
        if (awayGone.isEmpty()) return allVillagers.values();
        List<VillagerModel> alive = new ArrayList<>(allVillagers.size());
        for (VillagerModel model : allVillagers.values()) {
            if (!awayGone.contains(model.npcId)) alive.add(model);
        }
        return alive;
    }

    /**
     * Villagers gone in the away world (starved to death on an earlier away
     * day) but still alive in this one until the write-back: left out of
     * {@link #getVillagers} while {@code economy/away/AwayRun} runs a step, so
     * the smith's, enchanter's and gold plans skip them (leftovers round 5).
     * Empty outside a step; {@link #getVillagerIds} and {@link #getModel} keep them.
     */
    private Set<UUID> awayGone = Set.of();

    public void setAwayGone(Set<UUID> gone) {
        awayGone = gone;
    }

    /**
     * Whether any bed in a registered, valid house is not spoken for.
     *
     * <p>Beds are claimed the first time a villager goes to bed and kept for
     * good after that, so a villager new to the village has no bed until its
     * first night. A house's free beds are only free while it has fewer claims
     * than its token's capacity.
     */
    public boolean hasFreeBed() {
        return beds.hasFreeBed();
    }

    /**
     * A bed some villager owns or may claim: inside a house or barracks, or
     * held by anyone. Never a guest bed, even when it also lies in a tavern room.
     */
    public boolean isVillagerBed(BlockPos bed) {
        return beds.isVillagerBed(bed);
    }

    /** Return the assigned bed for a villager, or null if none is currently valid. */
    @Nullable
    public BlockPos getAssignedBed(UUID villagerId) {
        return beds.getAssignedBed(villagerId);
    }

    /** Return the registered building containing a villager's claimed bed, if any. */
    @Nullable
    public Building getAssignedBedBuilding(UUID villagerId) {
        return beds.getAssignedBedBuilding(villagerId);
    }

    /** Assign a free bed to this villager: checks registered houses first, then world scan. */
    @Nullable
    public BlockPos requestBedFor(VillagerModel model) {
        return beds.requestBedFor(model);
    }

    /**
     * Why {@link #requestBedFor} has no bed for this villager: each house and
     * bed with the check it fails. Read-only; for the log and debug command.
     */
    public List<String> explainNoBed(VillagerModel model) {
        return beds.explainNoBed(model);
    }

    /**
     * The Mayor's own bed in one specific Town Hall, kept for good since 10-24 (null on a night it
     * cannot use it); with none, or its bed gone, reserves a free cached bed there.
     * The Mayor uses this path so it never chooses a bed elsewhere.
     */
    @Nullable
    public BlockPos requestBedInTownHall(VillagerModel model, TownHallBuilding townHall) {
        return beds.requestBedInTownHall(model, townHall);
    }

    @Nullable
    public BlockPos reachableBedWalkTarget(VillagerModel model, BlockPos bed) {
        return beds.reachableBedWalkTarget(model, bed);
    }

    public void releaseBedFor(UUID villagerId) {
        beds.releaseBedFor(villagerId);
    }

    /** Frees every bed claim in this Town Hall not held by {@code keeperId}: left by an earlier Mayor. */
    public int releaseHallClaimsExcept(UUID hallId, UUID keeperId) {
        return beds.releaseHallClaimsExcept(hallId, keeperId);
    }

    /** Drops a temporary bed claim; a house bed is permanent and stays, so does the Mayor's hall bed unless it is gone. */
    public void releaseTemporaryBedFor(UUID villagerId) {
        beds.releaseTemporaryBedFor(villagerId);
    }

    /** Villager id to claimed bed for every claim in this house, broken beds included. */
    public Map<UUID, BlockPos> houseBedClaims(UUID houseId) {
        return beds.houseClaims(houseId);
    }

    /** Persistent residents of a house or barracks, including unloaded owners. */
    public Map<UUID, BlockPos> buildingBedClaims(UUID buildingId) {
        return beds.houseClaims(buildingId);
    }

    @Nullable
    public BlockPos reservedCaptainBed(com.alex.test.village.building.BarracksBuilding barracks) {
        return beds.reservedCaptainBed(barracks);
    }

    public boolean barracksNeedsCaptain(com.alex.test.village.building.BarracksBuilding barracks) {
        return beds.barracksNeedsCaptain(barracks);
    }

    /** The villager's claimed bed and building, whether or not the bed can be used now. */
    @Nullable
    public SavedBedAssignment bedClaimOf(UUID villagerId) {
        return beds.claimOf(villagerId);
    }

    /**
     * A player gives {@code bed} to this villager from the house screen. The
     * bed's previous owner swaps into the villager's old bed when that was in
     * the same house. False when the house is already full.
     */
    public boolean assignBedByPlayer(UUID villagerId, HouseBuilding house, BlockPos bed) {
        return beds.assignByPlayer(villagerId, house, bed);
    }

    /** The Mage Tower screen's version of {@link #assignBedByPlayer}: mages only; the refusal, or "". */
    public String assignTowerBedByPlayer(UUID villagerId, com.alex.test.village.building.MageTowerBuilding tower,
                                         BlockPos bed) {
        return beds.assignTowerBedByPlayer(villagerId, tower, bed);
    }

    /** A player gives a Town Hall bed to the Mayor ({@code mayorId} = its npc id); empty when it went through. */
    public String assignHallBedByPlayer(UUID mayorId, TownHallBuilding hall, BlockPos bed) {
        return beds.assignHallBedByPlayer(mayorId, hall, bed);
    }

    /**
     * False when {@code bed} is someone else's tonight: another villager holds
     * the claim or, unless this one is {@code alreadyLyingInIt}, someone else
     * is lying in it, or the villager has a house bed of its own elsewhere.
     * Otherwise an unowned bed becomes the villager's, and true.
     */
    public boolean claimBedForSleep(VillagerModel model, BlockPos bed, boolean alreadyLyingInIt) {
        return beds.claimBedForSleep(model, bed, alreadyLyingInIt);
    }

    /** Random walkable tile inside the town hall; if none, a random nav node in the village. */
    public BlockPos getTownHallSpot(BlockPos from) {
        List<BlockPos> tiles = new ArrayList<>();
        for (Building b : buildings.all()) {
            if (!(b instanceof TownHallBuilding) || !b.isValid() || b.getNavMeshBlocks().isEmpty()) continue;
            tiles.addAll(b.getNavMeshBlocks());
        }
        if (!tiles.isEmpty()) return tiles.get(new java.util.Random().nextInt(tiles.size()));
        // No town hall registered — pick a random nav node anywhere in the village so
        // villagers scatter rather than cluster.  Returns null if the nav graph isn't
        // ready yet; callers must handle null by waiting in place.
        if (navGraph != null && !navGraph.isEmpty()) {
            java.util.Random rng = new java.util.Random();
            NavNode picked = null; int count = 0;
            for (NavNode n : navGraph.allNodes()) {
                if (rng.nextInt(++count) == 0) picked = n;
            }
            if (picked != null) return picked.feetPos();
        }
        return null;
    }
    /** True if another villager has already claimed this resource position. */
    public boolean isResourceClaimed(BlockPos pos) {
        return lumberjackTrees.isTreeClaimed(pos);
    }

    /** Return this lumberjack's still-active cached tree assignment, if any. */
    @Nullable
    public TreeStructure getClaimedTreeJob(UUID villagerId) {
        return lumberjackTrees.getClaimedTreeJob(villagerId);
    }

    /**
     * Atomically lock a cached tree snapshot to one lumberjack. A villager may
     * resume its own assignment, but cannot silently replace it with another.
     */
    public boolean tryClaimTreeJob(TreeStructure tree, UUID villagerId) {
        return lumberjackTrees.tryClaimTreeJob(tree, villagerId);
    }

    public boolean ownsTreeJob(BlockPos base, UUID villagerId) {
        return lumberjackTrees.ownsTreeJob(base, villagerId);
    }

    /** Some log of {@code tree} belongs to a tree another lumberjack holds. */
    public boolean overlapsHeldTree(TreeStructure tree, UUID villagerId) {
        return lumberjackTrees.overlapsHeldTree(tree, villagerId);
    }

    public boolean isTreeTemporarilyBlacklisted(BlockPos base, long gameTime) {
        return lumberjackTrees.isTreeTemporarilyBlacklisted(base, gameTime);
    }

    public boolean recordTreeApproachFailure(BlockPos base, long gameTime) {
        return lumberjackTrees.recordApproachFailure(base, gameTime);
    }

    public void clearTreeApproachFailures(BlockPos base) {
        lumberjackTrees.clearApproachFailures(base);
    }

    /**
     * Where a visitor walks to, and what an arrival site must be able to reach.
     *
     * <p>Not the bell — which for a frame-founded village is the Town Hall's
     * door block, and a door is a thing vanilla ground navigation refuses to
     * path onto. Aimed at a door, a nomad's path stops several blocks short,
     * {@code canReach()} is false, and {@code NomadLogic}'s three-block arrival
     * test never passes: the settler stands in the square for three days and
     * leaves. The tile immediately outside the door is somewhere it can
     * genuinely stand.
     */
    public BlockPos getVisitorGatheringSpot() {
        for (Building building : buildings.all()) {
            if (building instanceof TownHallBuilding hall && hall.isValid()) {
                BlockPos outside = hall.getDoorPos()
                        .relative(hall.getInteriorDirection().getOpposite());
                if (navGraph == null) return outside;
                // A doorstep can be blocked — by the founding supply chest, by
                // something the player built there. Take the nearest tile that
                // is genuinely walkable instead of insisting on that one.
                NavNode landing = navGraph.nearestRoutable(outside, 2, 2);
                return landing != null ? landing.feetPos() : outside;
            }
        }
        return getBellPos();
    }

    /**
     * Work tiles nobody could get to. Shared across villagers and across work
     * sessions, unlike the per-activity sets that preceded it.
     */
    public UnreachableWorkTiles unreachableWorkTiles() {
        return unreachableWorkTiles;
    }

    /** Village-owned centralized farm authority. */
    public FarmerManager farmers() {
        return farmerManager;
    }

    public int getChopProgress(java.util.UUID villagerId, BlockPos base) {
        return lumberjackTrees.getChopProgress(villagerId, base);
    }

    public void setChopProgress(java.util.UUID villagerId, BlockPos base, int ticks) {
        lumberjackTrees.setChopProgress(villagerId, base, ticks);
    }

    public void blacklistTreeTemporarily(BlockPos base, long gameTime) {
        lumberjackTrees.blacklistTreeTemporarily(base, gameTime);
    }

    /**
     * Cancel this lumberjack's exact tree assignment after an external settings
     * change. Both claim registries are cleared and a still-standing tree is
     * restored to shared knowledge so another lumberjack may claim it.
     */
    public void cancelLumberjackTreeJob(UUID villagerId) {
        TreeStructure tree = lumberjackTrees.getClaimedTreeJob(villagerId);
        if (tree != null) {
            BlockPos base = tree.base();
            claims.release(base, villagerId);
            lumberjackTrees.releaseTreeJob(base, villagerId);
            lumberjackTrees.clearTreeTemporaryBlacklist(base);
            if (lumberjackTrees.isTreeStructureStillSafe(tree)) {
                knowledgeBase.add(KnowledgeBase.Category.TREE, base);
            }
        }

        // Defensive cleanup for stale/inconsistent runtime state. Both methods
        // are owner-aware and cannot release another lumberjack's assignment.
        claims.releaseAll(villagerId);
        lumberjackTrees.releaseTreeJobs(villagerId);
    }

    /** Release a tree only when the expected lumberjack still owns it. */
    public void releaseTreeJob(BlockPos base, UUID villagerId) {
        lumberjackTrees.releaseTreeJob(base, villagerId);
    }

    /** Release all runtime tree assignments for an unloading/dead villager. */
    public void releaseTreeJobs(UUID villagerId) {
        lumberjackTrees.releaseTreeJobs(villagerId);
    }

    public ServerLevel    getLevel()        { return level; }
    public BlockPos       getBellPos()      { return bellPos != null ? bellPos : BlockPos.ZERO; }
    void setSuppressTownHallRegistrationFeedback(boolean suppress) {
        buildings.setSuppressTownHallRegistrationFeedback(suppress);
    }

    /** Bed bookkeeping, for {@link VillageBuildings} to reconcile when rooms change. */
    VillageBeds beds() { return beds; }

    /** Mayor upkeep, for {@link VillageBuildings} to report Town Hall changes. */
    MayorSpawnManager mayors() { return mayorSpawnManager; }

    public UUID           getVillageId()    { return villageId; }
    public long           getLastRebuildMillis() { return lastRebuildMillis; }
    public boolean        wasLastRebuildLocal()  { return lastRebuildWasLocal; }
    public int            getGraphChunkRadius() { return graphChunkRadius; }
    public ClaimsRegistry getClaims()       { return claims; }
    public KnowledgeBase  getKnowledge()    { return knowledgeBase; }

    public VillagerModel getOrCreateModel(UUID npcId) {
        return allVillagers.computeIfAbsent(npcId, VillagerModel::new);
    }

    public VillagerModel getModel(UUID npcId) {
        return allVillagers.get(npcId);
    }

    /** Headcounts per building for overcrowding. */
    public VillageCrowding crowding() {
        return crowding;
    }

    /** Cauldrons inside houses and storage rooms; see {@link com.alex.test.village.building.CauldronIndex}. */
    public com.alex.test.village.building.CauldronIndex cauldrons() {
        return cauldrons;
    }

    /** Recorded by {@link VillageManagerRegistry} right after a reconciliation run. */
    public void recordLastReconciliation(com.alex.test.village.economy.ReconciliationSummary summary) {
        this.lastReconciliation = summary;
    }

    /** The last offline-economy catch-up applied to this village, or {@code null} if none yet. */
    @Nullable
    public com.alex.test.village.economy.ReconciliationSummary getLastReconciliation() {
        return lastReconciliation;
    }
}
