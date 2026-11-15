package com.alex.test.village;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.entity.visitor.AbstractTravelerEntity;
import com.alex.test.network.VillageBoundsPayload;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.chat.TokenFeedback;
import com.alex.test.village.log.VillageLog;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.building.StorageRoomAccess;
import com.alex.test.village.VillageRegistryCodec.SavedVillage;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Server-side registry that owns all town-hall-frame {@link VillageManager} instances.
 *
 * <h3>Event routing</h3>
 * {@link Events} is a static inner class registered on the NeoForge event bus.
 * It routes every game event to all registered managers that match the event's
 * dimension and spatial bounds.  The singleton {@link VillageManager#INSTANCE}
 * (the command/debug village) handles its own events through its own
 * {@code @SubscribeEvent} methods — there is no double-registration.
 *
 * <h3>Event split</h3>
 * Matching {@link VillageManager}'s own design, the registry routes:
 * <ul>
 *   <li><b>Nav</b>: {@code BlockEvent.BreakEvent},
 *       {@code BlockEvent.EntityPlaceEvent} and
 *       {@code BlockEvent.BlockToolModificationEvent} only.</li>
 *   <li><b>Knowledge</b>: {@code BlockGrowFeatureEvent},
 *       {@code BlockEvent.CropGrowEvent.Post}, and
 *       {@code EntityJoinLevelEvent}.</li>
 * </ul>
 * No piston events, no grow-dirty-all sweeps, no periodic polling.
 *
 * <h3>Components</h3>
 * The NBT layout of this saved data lives in {@link VillageRegistryCodec}, and
 * the ACTIVE / DORMANT / RECONCILING lifecycle in {@link VillageDormancy}.
 * This class keeps the village lifecycle (founding, orphaning, recovery),
 * villager relinking, ticking and event routing.
 */
public final class VillageManagerRegistry extends SavedData {

    static final Logger LOGGER = LogUtils.getLogger();

    /** Invalidates the derived mine partition only when registered geometry changes. */
    private long mineLayoutRevision;

    public long mineLayoutRevision() { return mineLayoutRevision; }

    public void mineLayoutChanged() { mineLayoutRevision++; }

    private static final String DATA_NAME   = "dynamicvillages_registry";
    private static final int TOWN_HALL_LOAD_GRACE_TICKS = 200;
    /** How often a Town Hall whose frame is in place but which is not valid is checked again. */
    private static final int TOWN_HALL_RETRY_INTERVAL = 100;
    /** One in-game hour: how long the Town Hall token may be out of its frame before the village pauses. */
    private static final int TOWN_HALL_TOKEN_MISSING_TICKS = 1000;
    /** Out this long, the token is reported, so taking it out and straight back in stays quiet. */
    private static final int TOWN_HALL_TOKEN_WARN_TICKS = 20;
    /** How often a valid Town Hall's frame is looked at for its token. */
    private static final int TOWN_HALL_TOKEN_CHECK_INTERVAL = 10;
    private static final int MAX_RELINKS_PER_TICK = 8;
    /**
     * A new village's claim radius in chunks. Also the radius {@link VillageRegistryCodec}
     * gives entries saved without one, and the area a waking generated village loads first.
     */
    public static final int DEFAULT_VILLAGE_RADIUS = 9;
    private static final int ORPHAN_RECOVERY_RADIUS = 48;

    // =========================================================================
    // Factory
    // =========================================================================

    private static final SavedData.Factory<VillageManagerRegistry> FACTORY =
            new SavedData.Factory<>(
                    VillageManagerRegistry::createEmpty,
                    VillageManagerRegistry::load,
                    null);

    public static VillageManagerRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    // =========================================================================
    // State
    // =========================================================================

    private final Map<UUID, VillageManager> villages = new LinkedHashMap<>();
    /** Finds members the villages lost track of, by searching the world and its save files. */
    private final MissingMemberSearch missingMemberSearch = new MissingMemberSearch();
    /** Villages loaded from disk whose dimension has not been initialized yet. */
    private final List<SavedVillage>        pending  = new ArrayList<>();
    private final Set<UUID> townHallFrameVillages = new HashSet<>();
    /** Current Town Hall item-frame UUID for each logical village UUID. */
    private final Map<UUID, UUID> townHallFrameByVillage = new HashMap<>();
    /** Villages retained without a currently valid Town Hall. */
    private final Set<UUID> inactiveVillages = new HashSet<>();
    private final Map<UUID, Long> missingSince = new HashMap<>();
    /**
     * Dormant / reconciling lifecycle of every village. Parallel to, and never
     * merged with, {@link #inactiveVillages}; see {@link VillageDormancy}.
     */
    private final VillageDormancy dormancy = new VillageDormancy(this::setDirty, this::claimsEnabled);
    private final Map<UUID, UUID> rememberedMayorByVillage = new HashMap<>();
    private final Map<UUID, Set<UUID>> rememberedVillagersByVillage = new HashMap<>();
    private final Map<FrameCheckKey, PendingFrameCheck> pendingTownHallChecks = new LinkedHashMap<>();
    /** Town Halls hung by players whose villages are being set up a slice per tick. */
    private final TownHallSetups townHallSetups = new TownHallSetups(this);
    /** Who hung each fresh Town Hall token, so the village it founds is theirs. Not saved. */
    private final com.alex.test.village.claims.TownHallHangers townHallHangers =
            new com.alex.test.village.claims.TownHallHangers();
    /**
     * Village claims for this world (operators: {@code /villager claim on|off}). Saved; on in new and older worlds.
     * Owners are remembered either way; later rounds' locks ask this.
     */
    private boolean claimsEnabled = true;
    private final Map<UUID, Integer> townHallLoadGrace = new HashMap<>();
    /**
     * Ticks each village's Town Hall token has been out of its frame, counted
     * only while the village runs with that frame's chunk loaded: a village
     * nobody is near does not run its wait out unseen. Not saved: after a
     * reload the wait starts over.
     */
    private final Map<UUID, Integer> townHallTokenMissingTicks = new HashMap<>();
    /** Villages told their Town Hall token is out, so they also hear when it is back. */
    private final Set<UUID> townHallTokenWarned = new HashSet<>();
    /** Each village's latest Town Hall token issue; see {@link #issueTownHallToken}. Saved. */
    private final Map<UUID, Integer> townHallTokenGeneration = new HashMap<>();
    private final LinkedHashMap<UUID, PendingVillagerRelink> pendingVillagerRelinks =
            new LinkedHashMap<>();
    /*
     * EntityLeaveLevelEvent fires while Minecraft is iterating a chunk's entity
     * list. Activity cleanup must therefore wait until that iteration finishes.
     */
    private final LinkedHashMap<UUID, PendingVillagerUnbind> pendingVillagerUnbinds =
            new LinkedHashMap<>();

    private record FrameCheckKey(ResourceKey<Level> dimension, BlockPos pos) {}
    private record PendingFrameCheck(ResourceKey<Level> dimension, BlockPos pos) {}
    private record PendingVillagerUnbind(UUID villageId, NpcEntity npc) {}
    private static final class PendingVillagerRelink {
        private final ResourceKey<Level> dimension;
        private final UUID entityId;
        private int delayTicks = 2;
        private int attempts = 0;

        private PendingVillagerRelink(ResourceKey<Level> dimension, UUID entityId) {
            this.dimension = dimension;
            this.entityId = entityId;
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    private VillageManagerRegistry() {}

    private static VillageManagerRegistry createEmpty() {
        return new VillageManagerRegistry();
    }

    public VillageManager create(ServerLevel level, UUID villageId, BlockPos bellPos) {
        return create(level, villageId, bellPos, 3);
    }

    public VillageManager create(ServerLevel level, UUID villageId, BlockPos bellPos, int radius) {
        initializePendingVillages(level);
        VillageManager existing = villages.get(villageId);
        if (existing != null) return existing;
        VillageManager mgr = new VillageManager(villageId, bellPos, level);
        mgr.setPersistentStateDirtyCallback(this::setDirty);
        mgr.setDormancyQuery(() -> isVillageDormantOrReconciling(villageId));
        mgr.initialize(level, bellPos, radius);
        villages.put(villageId, mgr);
        setDirty();
        LOGGER.info("[Registry] Created village {} at {}.", shortId(villageId), bellPos);
        return mgr;
    }

    /** The token retains the logical village identity when its item frame is replaced. */
    public VillageManager createFromTownHall(ServerLevel level, ItemFrame frame) {
        return createFromTownHall(level, frame, null);
    }

    /**
     * @param wakeUpGraph a map of the new village built ahead over many ticks
     *        from its Town Hall door (see {@link VillageManager#wakeUpWith}), or
     *        null to build it during founding as usual
     */
    public VillageManager createFromTownHall(ServerLevel level, ItemFrame frame,
                                             @Nullable NavGraph wakeUpGraph) {
        return createFromTownHall(level, frame, wakeUpGraph, DEFAULT_VILLAGE_RADIUS);
    }

    /**
     * @param radius chunks the new village claims around its Town Hall door
     *        (each way); a generated village claims less than a founded one
     */
    public VillageManager createFromTownHall(ServerLevel level, ItemFrame frame,
                                             @Nullable NavGraph wakeUpGraph, int radius) {
        return createFromTownHall(level, frame, wakeUpGraph, radius, false);
    }

    /**
     * A Town Hall token a player hung, or one loading in with its frame. A
     * fresh token for a new village is set up a slice per tick and founded
     * from there (see {@link TownHallSetups}); everything else is handled at
     * once, as before.
     */
    private void townHallHung(ServerLevel level, ItemFrame frame) {
        createFromTownHall(level, frame, null, DEFAULT_VILLAGE_RADIUS, true);
    }

    /** Founds a set-up Town Hall's village on the map drawn for it; see {@link TownHallSetups}. */
    VillageManager foundOnDrawnMap(ServerLevel level, ItemFrame frame, NavGraph graph, int radius) {
        return createFromTownHall(level, frame, graph, radius, true);
    }

    /**
     * @param byHand true for a token a player hung (or its frame loading in):
     *        with no map drawn yet, a new village is set up in slices rather
     *        than founded in one go; on its drawn map, it also looks for
     *        tokens hung before it was founded
     */
    private VillageManager createFromTownHall(ServerLevel level, ItemFrame frame,
                                              @Nullable NavGraph wakeUpGraph, int radius, boolean byHand) {
        if (ItemFrameTokenRegistry.tokenType(frame.getItem())
                != ItemFrameTokenRegistry.TokenType.TOWN_HALL) return null;
        UUID frameId = frame.getUUID();
        initializePendingVillages(level);

        VillageTokenBinding binding = VillageTokenBinding.read(frame.getItem());
        if (VillageTokenBinding.isBound(frame.getItem()) && (binding == null
                || !binding.dimension().equals(level.dimension().location().toString()))) {
            return rejectTownHall(level, frame, "this Town Hall token belongs to another dimension");
        }
        if (isReplacedTownHallToken(frame.getItem())) {
            return rejectTownHall(level, frame, "this token was replaced by a newer one");
        }
        if (binding != null) {
            VillageManager placedIn = findVillageAt(level, frame.blockPosition());
            if (placedIn != null && !VillageTokenBinding.matches(frame.getItem(), placedIn)) {
                ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
                TokenFeedback.announceFailure(level, frame.blockPosition(),
                        ItemFrameTokenRegistry.TokenType.TOWN_HALL,
                        com.alex.test.village.chat.RegistrationFailure.WRONG_TOWN_HALL,
                        frame.blockPosition());
                return null;
            }
        }
        UUID existingVillageId = findVillageIdByTownHallFrame(frameId);
        if (existingVillageId != null && binding != null
                && !existingVillageId.equals(binding.townHallId())) {
            // The hall's own token is out of its frame now: the village gets the
            // usual warning and wait before it pauses, not a silent pause.
            return rejectTownHall(level, frame, "this frame belongs to a different Town Hall");
        }
        VillageManager existing = existingVillageId != null
                ? villages.get(existingVillageId)
                : villages.get(binding != null ? binding.townHallId() : frameId);
        if (existing != null) {
            if (!dimMatch(existing, level)) {
                return rejectTownHall(level, frame, "this Town Hall belongs to another dimension");
            }
            UUID currentFrame = townHallFrameByVillage.getOrDefault(existing.getVillageId(),
                    existing.getVillageId());
            if (!currentFrame.equals(frameId)) {
                // Do not adopt a nearby orphan or create a second village from a copied token.
                // The old marker must be gone, and the new one must stay in its own village.
                // A village still waiting for its token counts as having lost it.
                UUID existingId = existing.getVillageId();
                boolean waitingForToken = townHallTokenMissingTicks.containsKey(existingId);
                if (binding == null
                        || (!inactiveVillages.contains(existingId) && !waitingForToken)
                        || !level.hasChunkAt(existing.getBellPos())
                        || (level.getEntity(currentFrame) instanceof ItemFrame oldFrame
                        && VillageTokenBinding.matches(oldFrame.getItem(), existing))) {
                    return rejectTownHall(level, frame,
                            com.alex.test.village.chat.RegistrationFailure.DUPLICATE_TOWN_HALL);
                }
                VillageManager.TownHallScan scan = VillageManager.scanTownHallMarker(
                        level, frame, existing.getGraphChunkRadius());
                if (scan == null || !existing.contains(scan.doorPos())) {
                    return rejectTownHall(level, frame, "restore this Town Hall inside its own village in a valid room");
                }
                // Its own token hung in a new frame while the old frame lost it:
                // pause the village now so it moves into the new frame like any orphan.
                boolean wasPaused = inactiveVillages.contains(existingId);
                if (waitingForToken && !inactiveVillages.contains(existingId)) {
                    townHallTokenMissingTicks.remove(existingId);
                    // No log line: the village wakes again in a moment (restoreOrphan).
                    markVillageInactive(level, existingId, null);
                }
                if (findOverlappingActiveVillage(level, scan.doorPos(),
                        existing.getGraphChunkRadius()) != null) {
                    if (!wasPaused && inactiveVillages.contains(existingId)) logPaused(level, existing, movedTokenFailed());
                    return rejectTownHall(level, frame, "restore this Town Hall inside its own village in a valid room");
                }
                return restoreOrphan(level, existing, frame, scan, wasPaused);
            }
            boolean freshToken = !VillageTokenBinding.isBound(frame.getItem());
            if (!VillageTokenBinding.bindTownHallFrame(frame, existing)
                    && !VillageTokenBinding.bindSavedTownHallFrame(frame, existing)) {
                return rejectTownHallForVillage(level, frame, existing.getVillageId());
            }
            // A fresh token in the old frame is now the village's newest token:
            // a lost older one stops working.
            if (freshToken) stampNewTownHallToken(frame, existing);
            if (existing.registerLoadedTownHall(level, frame)) {
                UUID villageId = existing.getVillageId();
                townHallFrameByVillage.put(villageId, frameId);
                boolean woke = inactiveVillages.remove(villageId);
                missingSince.remove(villageId);
                townHallLoadGrace.remove(villageId);
                existing.refreshLoadedVillagerLinks();
                setDirty();
                if (woke) logBack(level, existing);
                // registerLoadedTownHall() routes the registration through
                // VillageManager.tryCreateBuilding(), which owns the outcome
                // message. Emitting another success here duplicates it during
                // Town Hall recovery.
            }
            return existing;
        }
        if (binding != null) {
            return rejectTownHall(level, frame, "the village belonging to this Town Hall is unavailable");
        }
        // A fresh token hung inside a village that lost its own wakes that
        // village up, rather than founding a second one on top of it.
        VillageManager awaiting = findVillageAwaitingTownHall(level, frame.blockPosition());
        if (awaiting != null) return reviveWithFreshToken(level, awaiting, frame);
        if (byHand && wakeUpGraph == null && !TestKit.headless()) {
            // Founding in one go is a freeze of seconds on a slow machine, so
            // the village is set up a slice per tick and founded from there.
            // With no door, the scan below says so at once without any map.
            if (townHallSetups.isSettingUp(frameId)) return null;
            BlockPos door = VillageBuildings.townHallDoor(level, frame);
            if (door != null) {
                VillageManager overlapping = findOverlappingActiveVillage(level, door, radius);
                if (overlapping != null) return rejectOverlap(level, frame, door, overlapping);
                townHallSetups.begin(level, frame, door, radius);
                return null;
            }
        }
        VillageBuildings.TownHallScanResult scanResult =
                VillageBuildings.scanTownHallMarkerDetailed(level, frame, radius, wakeUpGraph);
        VillageManager.TownHallScan scan = scanResult.scan();
        if (scan == null) {
            reportTownHallScanFailure(level, frame, scanResult);
            return null;
        }

        VillageManager overlapping = findOverlappingActiveVillage(
                level, scan.doorPos(), radius);
        if (overlapping != null) return rejectOverlap(level, frame, scan.doorPos(), overlapping);

        UUID villageId = frameId;
        VillageManager mgr = new VillageManager(villageId, scan.doorPos(), level);
        mgr.setPersistentStateDirtyCallback(this::setDirty);
        mgr.setDormancyQuery(() -> isVillageDormantOrReconciling(villageId));
        // Set ownership before initialize() discovers the Town Hall and its other markers.
        var originalToken = frame.getItem().copy();
        var boundToken = originalToken.copy();
        VillageTokenBinding.bind(boundToken, mgr);
        frame.setItem(boundToken);
        townHallFrameByVillage.put(villageId, frameId);
        if (wakeUpGraph != null) mgr.wakeUpWith(wakeUpGraph, scan.doorPos(), true);
        VillageFounding.logFounded(level, mgr, scan.doorPos());
        mgr.setSuppressTownHallRegistrationFeedback(true);
        mgr.initialize(level, scan.doorPos(), radius);
        mgr.setSuppressTownHallRegistrationFeedback(false);
        if (!mgr.hasValidTownHall(frameId)) {
            mgr.teardown();
            townHallFrameByVillage.remove(villageId);
            VillageTokenBinding.clearFailedFounding(frame);
            frame.setItem(originalToken);
            LOGGER.info("[Registry] Town hall marker at {} failed manager registration; village not created.",
                    frame.blockPosition());
            return null;
        }

        villages.put(villageId, mgr);
        townHallFrameVillages.add(villageId);
        townHallFrameByVillage.put(villageId, frameId);
        inactiveVillages.remove(villageId);
        townHallLoadGrace.remove(villageId);
        // The player who hung this token owns the village; none for one that founded itself.
        var founder = townHallHangers.take(frameId, level.getServer().getTickCount());
        if (founder != null) {
            mgr.claim().setOwner(founder.playerId(), founder.name());
            LOGGER.info("[Claims] Village {} is owned by its founder {}.", shortId(villageId), founder.name());
        }
        // Founded on a drawn map, only the Town Hall was registered; a player
        // may have hung other tokens before founding.
        if (byHand && wakeUpGraph != null) mgr.findEarlierFramesSoon();
        setDirty();
        LOGGER.info("[Registry] Created frame village {} at town hall door {}.",
                shortId(villageId), scan.doorPos());
        VillageFounding.onFounded(level, mgr, scan.doorPos(), scan.interiorDirection());
        return mgr;
    }

    /** A Town Hall whose room check failed: its token loses its shine and nearby players hear why. */
    void reportTownHallScanFailure(ServerLevel level, ItemFrame frame,
                                   VillageBuildings.TownHallScanResult scanResult) {
        LOGGER.info("[Registry] Town hall marker at {} is invalid; village not created.",
                frame.blockPosition());
        ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
        TokenFeedback.announceFailure(level, frame.blockPosition(),
                ItemFrameTokenRegistry.TokenType.TOWN_HALL,
                scanResult.failure(), scanResult.problemPos());
    }

    private VillageManager rejectOverlap(ServerLevel level, ItemFrame frame, BlockPos door,
                                         VillageManager overlapping) {
        LOGGER.info("[Registry] Rejected Town Hall at {}: location overlaps village {}.",
                door, shortId(overlapping.getVillageId()));
        ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
        TokenFeedback.announcePlacement(level, frame.blockPosition(),
                ItemFrameTokenRegistry.TokenType.TOWN_HALL, false,
                "this spot overlaps an existing village — build further away");
        return null;
    }

    private VillageManager rejectTownHall(ServerLevel level, ItemFrame frame, String reason) {
        ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
        TokenFeedback.announcePlacement(level, frame.blockPosition(),
                ItemFrameTokenRegistry.TokenType.TOWN_HALL, false, reason);
        return null;
    }

    private VillageManager rejectTownHall(ServerLevel level, ItemFrame frame,
                                          com.alex.test.village.chat.RegistrationFailure reason) {
        ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
        TokenFeedback.announceFailure(level, frame.blockPosition(),
                ItemFrameTokenRegistry.TokenType.TOWN_HALL, reason, frame.blockPosition());
        return null;
    }

    private VillageManager rejectTownHallForVillage(ServerLevel level, ItemFrame frame,
                                                    UUID villageId) {
        ItemFrameTokenRegistry.setRegisteredGlint(frame, false);
        TokenFeedback.announceFailure(level, frame.blockPosition(),
                ItemFrameTokenRegistry.TokenType.TOWN_HALL,
                com.alex.test.village.chat.RegistrationFailure.WRONG_TOWN_HALL,
                frame.blockPosition());
        TokenFeedback.announceTownHallOwner(level, frame.blockPosition(), villageId);
        return null;
    }

    @Nullable
    public UUID getTownHallFrameId(UUID villageId) {
        return townHallFrameByVillage.get(villageId);
    }

    /** True when a village founded at {@code pos} would overlap an active one. */
    public boolean overlapsActiveVillage(ServerLevel level, BlockPos pos) {
        return overlapsActiveVillage(level, pos, DEFAULT_VILLAGE_RADIUS);
    }

    /** True when a village claiming {@code radius} chunks each way around {@code pos} would overlap an active one. */
    public boolean overlapsActiveVillage(ServerLevel level, BlockPos pos, int radius) {
        return findOverlappingActiveVillage(level, pos, radius) != null;
    }

    /**
     * Central duplicate-village rule. A candidate is rejected when its
     * prospective chunk-square overlaps any active village in the dimension.
     */
    private VillageManager findOverlappingActiveVillage(
            ServerLevel level, BlockPos townHallPos, int chunkRadius) {
        for (VillageManager manager : villages.values()) {
            if (inactiveVillages.contains(manager.getVillageId())) continue;
            if (dimMatch(manager, level)
                    && manager.overlapsCandidateBounds(townHallPos, chunkRadius)) {
                return manager;
            }
        }
        return null;
    }

    /**
     * @param wasPaused whether the village was paused before its caller paused it for the move:
     *        a paused village that comes back gets a "back" log line, a running one moving its token none.
     */
    private VillageManager restoreOrphan(
            ServerLevel level,
            VillageManager manager,
            ItemFrame frame,
            VillageManager.TownHallScan scan,
            boolean wasPaused) {
        UUID villageId = manager.getVillageId();
        UUID frameId = frame.getUUID();
        townHallFrameByVillage.put(villageId, frameId);
        inactiveVillages.remove(villageId);
        missingSince.remove(villageId);
        townHallLoadGrace.remove(villageId);

        manager.clearTownHallsForRecovery();
        manager.initialize(level, scan.doorPos(), manager.getGraphChunkRadius());
        if (!manager.hasValidTownHall(frameId)) {
            inactiveVillages.add(villageId);
            missingSince.put(villageId, level.getGameTime());
            LOGGER.warn("[Registry] Failed to restore orphaned village {} at {}.",
                    shortId(villageId), scan.doorPos());
            if (!wasPaused) logPaused(level, manager, movedTokenFailed());
            return null;
        }

        manager.refreshLoadedVillagerLinks();
        setDirty();
        if (wasPaused) logBack(level, manager);
        LOGGER.info("[Registry] Restored orphaned village {} with replacement Town Hall {} at {}.",
                shortId(villageId), shortId(frameId), scan.doorPos());
        return manager;
    }

    /**
     * A village around {@code pos} that has lost its Town Hall token: paused,
     * or still counting down to the pause. One overlapping another running
     * village is skipped; it could not be woken there anyway.
     */
    @Nullable
    private VillageManager findVillageAwaitingTownHall(ServerLevel level, BlockPos pos) {
        VillageManager best = null;
        double bestDistance = Double.MAX_VALUE;
        for (VillageManager manager : villages.values()) {
            UUID id = manager.getVillageId();
            if (!dimMatch(manager, level) || !townHallFrameVillages.contains(id)) continue;
            if (!inactiveVillages.contains(id) && !townHallTokenMissingTicks.containsKey(id)) continue;
            if (!manager.contains(pos) || overlapsOtherActiveVillage(level, manager)) continue;
            double distance = manager.getBellPos().distSqr(pos);
            if (distance < bestDistance) {
                best = manager;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** False when another running village now covers this paused one, so no Town Hall can wake it. */
    public boolean canWakeUp(VillageManager village) {
        return village.getLevel() != null && !overlapsOtherActiveVillage(village.getLevel(), village);
    }

    private boolean overlapsOtherActiveVillage(ServerLevel level, VillageManager village) {
        for (VillageManager other : villages.values()) {
            if (other == village || inactiveVillages.contains(other.getVillageId())) continue;
            if (dimMatch(other, level) && other.overlapsCandidateBounds(
                    village.getBellPos(), village.getGraphChunkRadius())) return true;
        }
        return false;
    }

    /**
     * Wakes {@code village} with the fresh Town Hall token in {@code frame}.
     * Any player may do this. The token becomes the village's newest, so an
     * older one turning up later no longer works.
     */
    @Nullable
    private VillageManager reviveWithFreshToken(ServerLevel level, VillageManager village, ItemFrame frame) {
        UUID villageId = village.getVillageId();
        VillageManager.TownHallScan scan = VillageManager.scanTownHallMarker(
                level, frame, village.getGraphChunkRadius());
        if (scan == null || !village.contains(scan.doorPos())) {
            return rejectTownHall(level, frame, "restore this Town Hall inside its own village in a valid room");
        }
        ItemStack original = frame.getItem().copy();
        ItemStack token = original.copy();
        int previousGeneration = townHallTokenGeneration(villageId);
        if (!issueTownHallToken(token, village)) {
            return rejectTownHallForVillage(level, frame, villageId);
        }
        frame.setItem(token);
        townHallTokenMissingTicks.remove(villageId);
        boolean wasPaused = inactiveVillages.contains(villageId);
        markVillageInactive(level, villageId, null);
        VillageManager restored = restoreOrphan(level, village, frame, scan, wasPaused);
        if (restored == null) {
            frame.setItem(original);
            townHallTokenGeneration.put(villageId, previousGeneration);
            setDirty();
            return rejectTownHall(level, frame, "restore this Town Hall inside its own village in a valid room");
        }
        LOGGER.info("[Registry] Woke village {} with a new Town Hall token at {}; older tokens no longer work.",
                shortId(villageId), scan.doorPos());
        return restored;
    }

    // =========================================================================
    // Town Hall token issues
    // =========================================================================

    /** How many times this village's Town Hall token was reissued; 0 for the first token. */
    public int townHallTokenGeneration(UUID villageId) {
        return townHallTokenGeneration.getOrDefault(villageId, 0);
    }

    /**
     * Binds {@code stack} as {@code village}'s newest Town Hall token. Every
     * token issued before it stops working.
     */
    public boolean issueTownHallToken(ItemStack stack, VillageManager village) {
        if (!VillageTokenBinding.bind(stack, village)) return false;
        UUID villageId = village.getVillageId();
        int next = townHallTokenGeneration(villageId) + 1;
        townHallTokenGeneration.put(villageId, next);
        VillageTokenBinding.setTownHallGeneration(stack, next);
        setDirty();
        return true;
    }

    /** The token now in {@code frame} was fresh until just now: make it the newest. */
    private void stampNewTownHallToken(ItemFrame frame, VillageManager village) {
        ItemStack token = frame.getItem().copy();
        if (issueTownHallToken(token, village)) frame.setItem(token);
    }

    /** A Town Hall token from before its village's latest one; it no longer works. */
    public boolean isReplacedTownHallToken(ItemStack stack) {
        if (!stack.is(com.alex.test.ModItems.TOWN_HALL.get())) return false;
        VillageTokenBinding binding = VillageTokenBinding.read(stack);
        return binding != null && VillageTokenBinding.townHallGeneration(stack)
                < townHallTokenGeneration(binding.townHallId());
    }

    /**
     * Minecraft is saving this body (chunk save, unload, world save): its brain writes into the
     * body's save data first, so the save holds this tick's needs, not the last 5 s copy.
     * Far villagers kept awake (FarAwake) are saved while they live on; the same for all others.
     */
    public static void flushBrainForSave(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel sl)) return;
        VillagerVillageLink link = npc.getVillageLink();
        if (link == null) return;
        VillageManager mgr = get(sl.getServer()).get(link.villageId());
        if (mgr == null) return;
        VillagerModel model = mgr.getModel(npc.getNpcId());
        if (model != null) model.brain().saveNow(npc);
    }

    public VillageManager get(UUID villageId)      { return villages.get(villageId); }
    public Collection<VillageManager> all()        { return Collections.unmodifiableCollection(villages.values()); }

    public VillageManager findVillageAt(ServerLevel level, BlockPos pos) {
        for (VillageManager mgr : villages.values()) {
            if (!inactiveVillages.contains(mgr.getVillageId())
                    && dimMatch(mgr, level) && mgr.contains(pos)) return mgr;
        }
        return null;
    }

    /**
     * The village containing {@code pos}, otherwise the active village in the same
     * dimension whose bell is closest, if that bell lies within {@code maxDistance} blocks.
     */
    @Nullable
    public VillageManager findNearestVillage(ServerLevel level, BlockPos pos, int maxDistance) {
        VillageManager containing = findVillageAt(level, pos);
        if (containing != null) return containing;
        VillageManager nearest = null;
        double best = (double) maxDistance * maxDistance;
        for (VillageManager mgr : villages.values()) {
            if (inactiveVillages.contains(mgr.getVillageId()) || !dimMatch(mgr, level)) continue;
            double dist = mgr.getBellPos().distSqr(pos);
            if (dist <= best) {
                best = dist;
                nearest = mgr;
            }
        }
        return nearest;
    }

    public boolean isVillageInactive(UUID villageId) {
        return inactiveVillages.contains(villageId);
    }

    // =========================================================================
    // Dormancy: queries and test hooks
    // =========================================================================

    public boolean isVillageDormant(UUID villageId) {
        return dormancy.isDormant(villageId);
    }

    public boolean isVillageReconciling(UUID villageId) {
        return dormancy.isReconciling(villageId);
    }

    /** True while either dormant or reconciling — the combined gate every tick-driven subsystem should honor. */
    public boolean isVillageDormantOrReconciling(UUID villageId) {
        return dormancy.isDormantOrReconciling(villageId);
    }

    /** -1 when the village has never gone dormant (or the timestamp was cleared after reconciling). */
    public long getDormantSinceGameTime(UUID villageId) {
        return dormancy.getDormantSinceGameTime(villageId);
    }

    /**
     * Whether a dormant-since timestamp is actually recorded for this
     * village, as opposed to {@link #getDormantSinceGameTime} returning its
     * -1 "never dormant" sentinel. The two are not interchangeable: the
     * {@code /vtest village elapse} test hook can legitimately rewind a
     * young test world's timestamp below zero (elapsing more ticks than the
     * world has existed), which a plain {@code >= 0} check on the returned
     * value would misreport as "never dormant". Callers that need to tell
     * "absent" from "present but negative" (e.g. {@code StateDump}'s
     * {@code dormantElapsedTicks}) must use this instead of sign-checking
     * the timestamp.
     */
    public boolean hasDormantSince(UUID villageId) {
        return dormancy.hasDormantSince(villageId);
    }

    /** Away ticks a member was owed for not being loaded when the catch-up ended, cleared; 0 when none. */
    long takeOwedSkip(UUID villageId, UUID npcId) {
        return dormancy.takeOwedSkip(villageId, npcId);
    }

    /**
     * Test hook ({@code /vtest village dormant}): force a village's dormant
     * state directly, bypassing player-proximity detection, so a scenario can
     * flip a village dormant without teleporting a fake player away and
     * waiting out {@code VillageDormancy.DORMANCY_GRACE_MISSES} real check cycles.
     *
     * <p>{@code dormant=true} forces DORMANT immediately, same as a real
     * "no player nearby" detection firing. {@code dormant=false} mirrors the
     * real "player returned" edge exactly: DORMANT -> RECONCILING, never
     * straight back to ACTIVE -- {@code dormantSinceGameTime} is deliberately
     * left in place so {@link VillageDormancy#processDormancyChecks} still computes the
     * correct elapsed window and lets the away calculator ({@code economy/away/AwayRun}) run
     * once chunks/puppets are confirmed ready, exactly like the real flow.
     * Calling {@code false} on a village that is not currently dormant
     * (already active, or already reconciling) is a no-op -- there is
     * nothing to wake up.
     */
    public void debugSetDormant(ServerLevel level, UUID villageId, boolean dormant) {
        dormancy.debugSetDormant(level, villageId, dormant);
    }

    /**
     * Test hook ({@code /vtest village elapse}): rewinds {@code dormantSinceGameTime}
     * by {@code ticks}, simulating elapsed offline time without a real-time wait.
     *
     * @return false if the village has no dormant timestamp to rewind (never went dormant).
     */
    public boolean debugElapseDormancy(UUID villageId, long ticks) {
        return dormancy.debugElapseDormancy(villageId, ticks);
    }

    public void sendCurrentBoundsTo(ServerPlayer player) {
        ServerLevel playerLevel = player.serverLevel();
        initializePendingVillages(playerLevel);
        PacketDistributor.sendToPlayer(player, new VillageBoundsPayload(currentBounds(playerLevel, null)));
    }

    /**
     * Sends every player in {@code level} the borders of all its villages,
     * {@code extra} included even when it is not registered yet (a village is
     * built before it is added). The client replaces its whole list with
     * what it is sent, so a list of one would hide every other border.
     */
    void broadcastBounds(ServerLevel level, @Nullable VillageManager extra) {
        List<AABB> bounds = currentBounds(level, extra);
        VillageBoundsPayload payload = new VillageBoundsPayload(bounds);
        for (ServerPlayer player : level.players()) PacketDistributor.sendToPlayer(player, payload);
        // A village's land moved, came or went: the locked squares too (village claims round 5).
        com.alex.test.village.claims.ClaimLock.markDirty();
    }

    private List<AABB> currentBounds(ServerLevel level, @Nullable VillageManager extra) {
        List<AABB> bounds = new ArrayList<>();
        for (Map.Entry<UUID, VillageManager> entry : villages.entrySet()) {
            if (inactiveVillages.contains(entry.getKey())) continue;
            VillageManager manager = entry.getValue();
            if (!dimMatch(manager, level)) continue;
            AABB box = manager.borderBounds();
            if (box != null) bounds.add(box);
        }
        if (extra != null && villages.get(extra.getVillageId()) != extra && dimMatch(extra, level)) {
            AABB box = extra.borderBounds();
            if (box != null) bounds.add(box);
        }
        return bounds;
    }

    public void rememberMayor(UUID villageId, UUID mayorId) {
        if (mayorId == null) return;
        if (!mayorId.equals(rememberedMayorByVillage.put(villageId, mayorId))) setDirty();
    }

    public UUID getRememberedMayor(UUID villageId) {
        return rememberedMayorByVillage.get(villageId);
    }

    /**
     * Forget a persisted Mayor only when the entity with that exact identity is
     * confirmed gone.  A missing/unloaded entity must retain this record so a
     * world reload cannot manufacture a replacement before its chunk loads.
     */
    public void forgetMayor(UUID villageId, UUID mayorId) {
        if (mayorId != null && mayorId.equals(rememberedMayorByVillage.get(villageId))) {
            rememberedMayorByVillage.remove(villageId);
            setDirty();
        }
    }

    /** Frees the Town Hall bed of a Mayor gone for good (killed or removed); a new Mayor can then take it. */
    public void releaseMayorBed(MayorEntity mayor) {
        var link = mayor.getTownHallLink();
        if (link == null) return;
        VillageManager manager = villages.get(link.villageId());
        if (manager == null && VillageManager.INSTANCE.getVillageId().equals(link.villageId())) {
            manager = VillageManager.INSTANCE;
        }
        if (manager == null) return;
        VillageManager.SavedBedAssignment claim = manager.bedClaimOf(mayor.getNpcId());
        if (claim == null) return;
        manager.releaseBedFor(mayor.getNpcId());
        LOGGER.info("[Registry] Village {}: Town Hall bed {} no longer claimed by {}: the Mayor died or was removed.",
                shortId(manager.getVillageId()), claim.bedPos(), mayor.getNpcId().toString().substring(0, 8));
    }

    public void handleVillagerDeath(ServerLevel level, NpcEntity npc) {
        VillagerVillageLink link = npc.getVillageLink();
        VillageManager manager = null;

        if (link != null && link.dimension().equals(level.dimension())) {
            manager = villages.get(link.villageId());
            if (manager == null && VillageManager.INSTANCE.getVillageId().equals(link.villageId())) {
                manager = VillageManager.INSTANCE;
            }
        }

        if (manager == null) return;

        UUID villageId = manager.getVillageId();
        UUID villagerId = npc.getNpcId();
        boolean removed = manager.removeVillager(villagerId);
        Set<UUID> remembered = rememberedVillagersByVillage.get(villageId);
        if (remembered != null && remembered.remove(villagerId)) {
            removed = true;
            if (remembered.isEmpty()) rememberedVillagersByVillage.remove(villageId);
        }
        pendingVillagerRelinks.remove(npc.getUUID());
        if (removed && manager != VillageManager.INSTANCE) setDirty();
    }

    /**
     * A villager left its village for good without dying (kidnappers took it):
     * forgotten the way a death forgets it, so no empty record comes back on
     * the next load.
     */
    public void forgetVillager(VillageManager manager, UUID villagerId) {
        boolean removed = manager.removeVillager(villagerId);
        UUID villageId = manager.getVillageId();
        Set<UUID> remembered = rememberedVillagersByVillage.get(villageId);
        if (remembered != null && remembered.remove(villagerId)) {
            removed = true;
            if (remembered.isEmpty()) rememberedVillagersByVillage.remove(villageId);
        }
        if (removed && manager != VillageManager.INSTANCE) setDirty();
    }

    @Nullable
    private UUID findVillageIdByTownHallFrame(UUID frameId) {
        for (Map.Entry<UUID, UUID> entry : townHallFrameByVillage.entrySet()) {
            if (entry.getValue().equals(frameId)) return entry.getKey();
        }
        return null;
    }

    // =========================================================================
    // Tick
    // =========================================================================

    void tickAll(ServerLevel sl) {
        processPendingVillagerUnbinds(sl);

        if (DynamicVillages.isServerStopping()) {
            for (VillageManager mgr : villages.values()) {
                if (dimMatch(mgr, sl)) mgr.pauseRuntimeMovement();
            }
            return;
        }

        // Flush deferred inits once the ServerLevel is available.
        initializePendingVillages(sl);

        processPendingTownHallChecks(sl);

        townHallSetups.tick(sl);

        processPendingVillagerRelinks(sl);
        if (sl.dimension() == Level.OVERWORLD) missingMemberSearch.tick(this, sl);

        // Must run even with zero players connected (and even for villages
        // currently dormant/reconciling) — this is exactly what detects
        // ACTIVE -> DORMANT, DORMANT -> RECONCILING and RECONCILING -> ACTIVE.
        // On a real dedicated/singleplayer-hosted server, "everyone
        // disconnected" is the primary scenario dormancy exists to handle, so
        // this must not be skipped just because the player count is zero.
        dormancy.countEmptyServerTick(sl);
        dormancy.processDormancyChecks(sl, villages, inactiveVillages);

        tickVillagesBuiltLate(sl);

        // Far villagers stay awake: chunk holds renewed for the villages that ticked lately; here,
        // before the empty-server return, so an empty server lets them go.
        com.alex.test.village.farawake.FarAwake.tickLevel(sl);

        if (sl.getServer().getPlayerList().getPlayerCount() == 0 && !TestKit.headless()) {
            for (VillageManager mgr : villages.values()) {
                if (dimMatch(mgr, sl)) mgr.pauseRuntimeMovement();
            }
            return;
        }

        Iterator<Map.Entry<UUID, VillageManager>> iterator = villages.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, VillageManager> entry = iterator.next();
            VillageManager mgr = entry.getValue();
            if (!dimMatch(mgr, sl)) continue;
            if (inactiveVillages.contains(entry.getKey())) {
                // An orphaned village does not tick, so without this nothing
                // would look at its Town Hall again until the frame reloaded.
                retryOrphanedTownHall(sl, entry.getKey(), mgr);
                continue;
            }
            if (dormancy.isDormantOrReconciling(entry.getKey())) continue;
            long costStart = com.alex.test.performance.ServerCostLog.measuring ? System.nanoTime() : 0L;
            try {
                // Chunk loads the village tick waits for are counted and named ([FarAwake] line).
                com.alex.test.village.farawake.ChunkStallWatch.enter(entry.getKey());
                mgr.tick(sl);
            } catch (Exception e) {
                // One village manager throwing must not abort ticking for
                // every other village later in this same loop iteration.
                LOGGER.error("[Registry] Village {} threw during tick(); skipping it this tick.",
                        shortId(entry.getKey()), e);
                continue;
            } finally {
                com.alex.test.village.farawake.ChunkStallWatch.exit();
                if (costStart != 0L) com.alex.test.performance.ServerCostLog.village(System.nanoTime() - costStart);
            }
            UUID currentTownHall = townHallFrameByVillage.getOrDefault(
                    entry.getKey(), entry.getKey());
            if (townHallFrameVillages.contains(entry.getKey())
                    && (!mgr.hasValidTownHall(currentTownHall)
                        || tokenLeftValidHall(sl, entry.getKey(), mgr, currentTownHall))) {
                if (!isTownHallFrameLoaded(sl, mgr, currentTownHall)) continue;
                // A hall whose frame is still up with its token has only failed
                // its checks: keep the village running and keep checking the hall.
                if (mgr.isTownHallFramePresent(sl, currentTownHall)) {
                    townHallTokenMissingTicks.remove(entry.getKey());
                    int grace = townHallLoadGrace.getOrDefault(
                            entry.getKey(), TOWN_HALL_LOAD_GRACE_TICKS);
                    if (grace > 0) {
                        townHallLoadGrace.put(entry.getKey(), grace - 1);
                        continue;
                    }
                    if (isTownHallRetryTick(sl, entry.getKey())) {
                        retryTownHall(sl, entry.getKey(), mgr);
                    }
                    continue;
                }
                // The frame is gone or no longer holds this village's token.
                tickMissingTownHallToken(sl, entry.getKey(), mgr, currentTownHall);
            } else {
                townHallLoadGrace.remove(entry.getKey());
                townHallTokenMissingTicks.remove(entry.getKey());
                if (townHallTokenWarned.remove(entry.getKey())) {
                    TokenFeedback.announceTownHallTokenBack(sl, mgr);
                }
            }
        }
    }

    /**
     * A Town Hall the village still counts as valid whose token is out of its
     * frame. The hall building itself only notices on its next re-check, which
     * can take a minute or more, so the frame is looked at directly: twice a
     * second, and every tick once the token is known to be out.
     */
    private boolean tokenLeftValidHall(ServerLevel sl, UUID villageId, VillageManager mgr, UUID frameId) {
        if (!townHallTokenMissingTicks.containsKey(villageId)
                && Math.floorMod(sl.getGameTime() + villageId.hashCode(), TOWN_HALL_TOKEN_CHECK_INTERVAL) != 0) {
            return false;
        }
        return isTownHallFrameLoaded(sl, mgr, frameId) && !mgr.isTownHallFramePresent(sl, frameId);
    }

    /** True while the village's Town Hall token is out and the village has not paused yet. */
    public boolean isAwaitingTownHallToken(UUID villageId) {
        return townHallTokenMissingTicks.containsKey(villageId);
    }

    /**
     * The Town Hall token is out of its frame: nearby players hear about it
     * at once, and the village pauses once it has been out an in-game hour.
     */
    private void tickMissingTownHallToken(ServerLevel sl, UUID villageId, VillageManager mgr,
                                          UUID frameId) {
        int missingFor = townHallTokenMissingTicks.merge(villageId, 1, Integer::sum);
        BlockPos framePos = mgr.getTownHallFramePos(frameId);
        BlockPos where = framePos != null ? framePos : mgr.getBellPos();
        if (missingFor >= TOWN_HALL_TOKEN_WARN_TICKS && townHallTokenWarned.add(villageId)) {
            int secondsLeft = (TOWN_HALL_TOKEN_MISSING_TICKS - missingFor) / 20 / 5 * 5;
            LOGGER.info("[Registry] Town Hall token of village {} is out of its frame at {}; "
                    + "pausing in {} ticks unless it is back.", shortId(villageId), where,
                    TOWN_HALL_TOKEN_MISSING_TICKS - missingFor);
            TokenFeedback.announceTownHallTokenMissing(sl, mgr, where, secondsLeft);
        }
        if (missingFor < TOWN_HALL_TOKEN_MISSING_TICKS) return;
        townHallTokenMissingTicks.remove(villageId);
        markVillageInactive(sl, villageId, Tr.c("log.dynamicvillages.town_hall.paused.token_gone",
                "Its Town Hall token was gone from its frame for too long."));
        TokenFeedback.announceVillagePaused(sl, mgr, where);
    }

    /**
     * Whether the Town Hall's frame could have loaded yet. A frame is an
     * entity, and Minecraft loads a chunk's entities after its blocks, on a
     * separate queue. Before that the frame reads as missing, so the load grace
     * must not count down on the block chunk alone. With no known frame
     * position, the frame hangs beside the door, which is the bell position.
     */
    private static boolean isTownHallFrameLoaded(ServerLevel sl, VillageManager mgr, UUID frameId) {
        if (!sl.hasChunkAt(mgr.getBellPos())) return false;
        BlockPos framePos = mgr.getTownHallFramePos(frameId);
        BlockPos watched = framePos != null ? framePos : mgr.getBellPos();
        return sl.hasChunkAt(watched) && sl.areEntitiesLoaded(ChunkPos.asLong(watched));
    }

    /** One Town Hall retry per village every {@link #TOWN_HALL_RETRY_INTERVAL} ticks, staggered. */
    private static boolean isTownHallRetryTick(ServerLevel sl, UUID villageId) {
        return Math.floorMod(sl.getGameTime() + villageId.hashCode(), TOWN_HALL_RETRY_INTERVAL) == 0;
    }

    /** Recovers an orphaned village whose Town Hall frame is still in place, once its hall checks out. */
    private void retryOrphanedTownHall(ServerLevel sl, UUID villageId, VillageManager mgr) {
        if (!townHallFrameVillages.contains(villageId) || !isTownHallRetryTick(sl, villageId)) return;
        UUID frameId = townHallFrameByVillage.getOrDefault(villageId, villageId);
        if (!isTownHallFrameLoaded(sl, mgr, frameId)
                || !mgr.isTownHallFramePresent(sl, frameId)) return;
        retryTownHall(sl, villageId, mgr);
    }

    /**
     * Checks the Town Hall again, without chat noise while it keeps failing.
     * On success the village leaves the orphaned state, the same way it does
     * when the frame reloads (createFromTownHall), and nearby players hear
     * that the Town Hall is registered.
     */
    private boolean retryTownHall(ServerLevel sl, UUID villageId, VillageManager mgr) {
        UUID frameId = townHallFrameByVillage.getOrDefault(villageId, villageId);
        if (!mgr.retryTownHall(sl, frameId)) return false;
        boolean wasOrphaned = inactiveVillages.remove(villageId);
        missingSince.remove(villageId);
        townHallLoadGrace.remove(villageId);
        if (wasOrphaned) {
            mgr.refreshLoadedVillagerLinks();
            logBack(sl, mgr);
        }
        setDirty();
        LOGGER.info("[Registry] Town Hall of village {} checked out on retry{}.",
                shortId(villageId), wasOrphaned ? "; village is no longer orphaned" : "");
        BlockPos framePos = mgr.getTownHallFramePos(frameId);
        if (framePos != null) {
            TokenFeedback.announcePlacement(sl, framePos,
                    ItemFrameTokenRegistry.TokenType.TOWN_HALL, true, null);
        }
        return true;
    }

    private VillageManager initializePendingVillage(ServerLevel level, SavedVillage pendingVillage) {
        UUID villageId = pendingVillage.id();
        if (pendingVillage.townHallFrameVillage()) {
            townHallFrameVillages.add(villageId);
            townHallFrameByVillage.put(villageId, pendingVillage.townHallFrameId());
            if (pendingVillage.inactive()) inactiveVillages.add(villageId);
            if (pendingVillage.missingSince() >= 0L) {
                missingSince.put(villageId, pendingVillage.missingSince());
            }
            if (pendingVillage.townHallTokenGeneration() > 0) {
                townHallTokenGeneration.put(villageId, pendingVillage.townHallTokenGeneration());
            }
            if (pendingVillage.mayorId() != null) {
                rememberedMayorByVillage.put(villageId, pendingVillage.mayorId());
            }
            if (!pendingVillage.villagerIds().isEmpty()) {
                rememberedVillagersByVillage.put(
                        villageId, new LinkedHashSet<>(pendingVillage.villagerIds()));
            }
        }
        // Dormancy is independent of the Town-Hall-frame lifecycle above, so it
        // is restored unconditionally for any persisted village.
        dormancy.restore(villageId, pendingVillage.dormant(), pendingVillage.reconciling(),
                pendingVillage.dormantSince(), pendingVillage.dormancyExtra());
        VillageManager manager = villages.get(villageId);
        if (manager == null) {
            manager = new VillageManager(
                    villageId, pendingVillage.bell(), level);
            manager.setPersistentStateDirtyCallback(this::setDirty);
            if (buildLater(pendingVillage)) {
                // Asleep when the world was saved: its map waits until a
                // player comes near, so the loading screen does not grow
                // with every village in the world.
                manager.deferInitialize(level, pendingVillage.bell(), pendingVillage.radius(),
                        pendingVillage.farmState());
            } else {
                // What it finds registered now was there before the save: no village log rows (they would
                // also be lost to the saved log loading below, while still holding back real ones).
                manager.setBuildingRowsOff(true);
                try {
                    manager.initialize(
                            level, pendingVillage.bell(), pendingVillage.radius());
                } finally {
                    manager.setBuildingRowsOff(false);
                }
                // After initialize, which starts the farmer manager empty. A
                // manager that already existed keeps its live, newer farm state.
                manager.farmers().restoreState(pendingVillage.farmState());
            }
            villages.put(villageId, manager);
            LOGGER.info("[Registry] Restored village {} at {} in {} from saved data.",
                    shortId(villageId), pendingVillage.bell(),
                    level.dimension().location());
        }
        manager.setPersistentStateDirtyCallback(this::setDirty);
        manager.setDormancyQuery(() -> isVillageDormantOrReconciling(villageId));
        manager.rememberVillagerIds(pendingVillage.villagerIds());
        manager.rememberCaptains(pendingVillage.captainIds());
        manager.rememberNames(pendingVillage.knownNames());
        manager.rememberSightings(pendingVillage.lastSeenSpots(), pendingVillage.lastSeenDimensions());
        manager.rememberSavedBuildings(pendingVillage.buildings());
        manager.setMinerSideBranchDistance(pendingVillage.minerSideBranchDistance());
        manager.rules().load(pendingVillage.villageRules());
        manager.claim().load(pendingVillage.claim());
        manager.mineSettings().load(pendingVillage.mineSettings());
        manager.rememberBedAssignments(memberBedClaims(pendingVillage));
        manager.nomads().restore(pendingVillage.nomadSchedule(),
                pendingVillage.legacyNomadWelcome(), pendingVillage.activeNomads());
        manager.visitors().restore(pendingVillage.merchantState());
        if (pendingVillage.townHallFrameVillage() && !pendingVillage.inactive()) {
            townHallLoadGrace.put(villageId, TOWN_HALL_LOAD_GRACE_TICKS);
        }
        return manager;
    }

    /**
     * A village saved while asleep is built when a player comes near rather
     * than at world load. Test servers build everything at once, as before:
     * scenarios expect a village to be ready the moment it loads.
     */
    private static boolean buildLater(SavedVillage saved) {
        return saved.dormant() && !saved.reconciling() && !saved.inactive() && !TestKit.headless();
    }

    /** Ticks between looks for a player on the land of a village waiting to be built. */
    private static final int BUILD_LATE_CHECK_INTERVAL = 20;

    /**
     * Builds, a step per tick, the villages that were asleep at world load and
     * that a player has come onto (one at a time per dimension), and lets go
     * of the chunks held for maps drawn ahead. A player on its land starts the
     * building at once, not at the village's next sleep check up to ten
     * seconds later, so it is more often ready by the time they reach it.
     */
    private void tickVillagesBuiltLate(ServerLevel sl) {
        boolean building = false;
        boolean look = sl.getGameTime() % BUILD_LATE_CHECK_INTERVAL == 0;
        for (Map.Entry<UUID, VillageManager> entry : villages.entrySet()) {
            VillageManager mgr = entry.getValue();
            if (!dimMatch(mgr, sl)) continue;
            UUID villageId = entry.getKey();
            mgr.tickChunkRelease(sl, dormancy.isDormant(villageId));
            if (building || !mgr.isAwaitingInitialize()) continue;
            if (!mgr.isDeferredBuildStarted() && !dormancy.isReconciling(villageId)
                    && !(look && isPlayerOnLand(sl, mgr))) continue;
            building = true;
            try {
                if (mgr.tickDeferredInitialize(sl)) setDirty();
            } catch (Exception e) {
                LOGGER.error("[Registry] Village {} threw while being built; trying again next tick.",
                        shortId(villageId), e);
            }
        }
    }

    private static boolean isPlayerOnLand(ServerLevel sl, VillageManager mgr) {
        for (ServerPlayer player : sl.players()) {
            if (!player.isSpectator() && mgr.contains(player.blockPosition())) return true;
        }
        return false;
    }

    private void initializePendingVillages(ServerLevel level) {
        if (pending.isEmpty()) return;
        int restored = 0;
        Iterator<SavedVillage> iterator = pending.iterator();
        while (iterator.hasNext()) {
            SavedVillage entry = iterator.next();
            if (!entry.dimension().equals(level.dimension())) continue;
            iterator.remove();
            initializePendingVillage(level, entry);
            restored++;
        }
    }

    /**
     * @param why the village log's hover line, or null for no log line (a token moving to a new frame:
     *        the village wakes again at once).
     */
    private void markVillageInactive(ServerLevel level, UUID villageId, @Nullable Component why) {
        if (!townHallFrameVillages.contains(villageId)
                || !inactiveVillages.add(villageId)) return;
        VillageManager manager = villages.get(villageId);
        if (manager != null) {
            manager.pauseForMissingTownHall();
            rememberedVillagersByVillage.put(
                    villageId, new LinkedHashSet<>(manager.getVillagerIds()));
            if (why != null) logPaused(level, manager, why);
        }
        missingSince.put(villageId, level.getGameTime());
        townHallLoadGrace.remove(villageId);
        setDirty();
        LOGGER.info("[Registry] Village {} is now orphaned; assignments are retained for recovery.",
                shortId(villageId));
    }

    /** Village log: "The village paused", hover {@code why}. */
    private static void logPaused(ServerLevel level, VillageManager manager, Component why) {
        VillageLog.record(manager, level, VillageLog.Kind.TOWN_HALL,
                Tr.c("log.dynamicvillages.town_hall.paused", "The village paused"), why, manager.getBellPos());
    }

    /** Village log: "The village is back" after a pause. */
    private static void logBack(ServerLevel level, VillageManager manager) {
        VillageLog.record(manager, level, VillageLog.Kind.TOWN_HALL,
                Tr.c("log.dynamicvillages.town_hall.back", "The village is back"),
                Tr.c("log.dynamicvillages.town_hall.back.detail", "Its Town Hall works again."), manager.getBellPos());
    }

    /** The village's token (moved, or a fresh one) hung in a new frame where the hall could not be set up. */
    private static Component movedTokenFailed() {
        return Tr.c("log.dynamicvillages.town_hall.paused.moved",
                "Its Town Hall token was hung in a frame where the Town Hall can't work.");
    }

    private void processPendingVillagerRelinks(ServerLevel sl) {
        if (pendingVillagerRelinks.isEmpty()) return;
        int processed = 0;
        Iterator<Map.Entry<UUID, PendingVillagerRelink>> iterator =
                pendingVillagerRelinks.entrySet().iterator();
        while (iterator.hasNext() && processed < MAX_RELINKS_PER_TICK) {
            PendingVillagerRelink pendingRelink = iterator.next().getValue();
            if (!pendingRelink.dimension.equals(sl.dimension())) continue;
            if (pendingRelink.delayTicks-- > 0) continue;
            processed++;

            net.minecraft.world.entity.Entity entity = sl.getEntity(pendingRelink.entityId);
            if (!(entity instanceof NpcEntity npc) || npc instanceof MayorEntity) {
                if (++pendingRelink.attempts >= 5) iterator.remove();
                else pendingRelink.delayTicks = 10;
                continue;
            }
            if (tryRelinkVillager(sl, npc, pendingRelink.attempts >= 10)) {
                iterator.remove();
            } else if (++pendingRelink.attempts >= 20) {
                iterator.remove();
                LOGGER.warn("[Registry] Could not relink villager {} after {} attempts.",
                        shortId(npc.getNpcId()), pendingRelink.attempts);
            } else {
                pendingRelink.delayTicks = 10;
            }
        }
    }

    /**
     * Complete NPC unload cleanup outside {@link EntityLeaveLevelEvent}.
     * That event is dispatched from Minecraft's chunk-entity iteration, where
     * re-entrant entity work can cause a ConcurrentModificationException.
     */
    private void processPendingVillagerUnbinds(ServerLevel sl) {
        if (pendingVillagerUnbinds.isEmpty()) return;
        Iterator<Map.Entry<UUID, PendingVillagerUnbind>> iterator =
                pendingVillagerUnbinds.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingVillagerUnbind pending = iterator.next().getValue();
            if (pending.npc().level() != sl) continue;
            iterator.remove();

            VillageManager manager = villages.get(pending.villageId());
            if (manager != null && dimMatch(manager, sl)) {
                manager.unbindVillager(pending.npc());
            }
        }
    }

    private void queueVillagerUnbind(UUID villageId, NpcEntity npc) {
        pendingVillagerUnbinds.put(npc.getUUID(), new PendingVillagerUnbind(villageId, npc));
    }

    private void queueVillagerRelink(ServerLevel sl, NpcEntity npc) {
        pendingVillagerRelinks.put(
                npc.getUUID(), new PendingVillagerRelink(sl.dimension(), npc.getUUID()));
    }

    private boolean tryRelinkVillager(ServerLevel sl, NpcEntity npc) {
        return tryRelinkVillager(sl, npc, false);
    }

    private boolean tryRelinkVillager(
            ServerLevel sl, NpcEntity npc, boolean allowWideFallback) {
        // Travellers share the NPC rendering/movement shell but are not logical
        // residents. In particular, never let the legacy spatial-repair path
        // turn a nearby Nomad into a member on spawn or world reload.
        if (!npc.isVillageMember()) return true;

        VillagerVillageLink link = npc.getVillageLink();
        VillageManager manager = null;

        if (link != null && link.dimension().equals(sl.dimension())) {
            manager = villages.get(link.villageId());
            if (manager != null && !dimMatch(manager, sl)) manager = null;
            if (manager == null) manager = findVillageByAnchor(sl, link.townHallPos());
        }
        // Legacy migration and final repair fallback: one bounded registry lookup,
        // never an entity/world scan.
        if (manager == null && (link == null || allowWideFallback)) {
            manager = findVillageAt(sl, npc.blockPosition());
            if (manager == null) manager = findNearestVillageForRepair(sl, npc.blockPosition());
        }
        if (manager == null) return false;

        boolean newLogicalMember = !manager.getVillagerIds().contains(npc.getNpcId());
        manager.bindVillager(npc);
        if (newLogicalMember && manager != VillageManager.INSTANCE) setDirty();
        LOGGER.debug("[Registry] Linked villager {} to village {}.",
                shortId(npc.getNpcId()), shortId(manager.getVillageId()));
        return true;
    }

    private VillageManager findVillageByAnchor(ServerLevel sl, BlockPos anchor) {
        for (VillageManager manager : villages.values()) {
            if (dimMatch(manager, sl) && manager.getBellPos().equals(anchor)) return manager;
        }
        return null;
    }

    private VillageManager findNearestVillageForRepair(ServerLevel sl, BlockPos pos) {
        VillageManager best = null;
        double bestDistance = ORPHAN_RECOVERY_RADIUS * ORPHAN_RECOVERY_RADIUS;
        for (VillageManager manager : villages.values()) {
            if (!dimMatch(manager, sl)) continue;
            double distance = manager.getBellPos().distSqr(pos);
            if (distance <= bestDistance) {
                best = manager;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void processPendingTownHallChecks(ServerLevel sl) {
        if (pendingTownHallChecks.isEmpty()) return;

        Iterator<Map.Entry<FrameCheckKey, PendingFrameCheck>> iterator =
                pendingTownHallChecks.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingFrameCheck check = iterator.next().getValue();
            if (!check.dimension().equals(sl.dimension())) continue;
            iterator.remove();

            AABB box = new AABB(check.pos()).inflate(1.0);
            ItemFrame frame = sl.getEntitiesOfClass(ItemFrame.class, box).stream()
                    .filter(candidate -> candidate.blockPosition().equals(check.pos()))
                    .findFirst()
                    .orElse(null);
            if (frame == null) continue;

            UUID frameId = frame.getUUID();
            if (ItemFrameTokenRegistry.tokenType(frame.getItem())
                    != ItemFrameTokenRegistry.TokenType.TOWN_HALL) {
                UUID villageId = findVillageIdByTownHallFrame(frameId);
                if (villageId != null) {
                    markVillageInactive(sl, villageId, frame.getItem().isEmpty()
                            ? Tr.c("log.dynamicvillages.town_hall.paused.token_out", "Its Town Hall token was taken out.")
                            : Tr.c("log.dynamicvillages.town_hall.paused.swapped", "Something else was put in the Town Hall's frame."));
                }
                continue;
            }
            townHallHung(sl, frame);
        }
    }

    // =========================================================================
    // Routing helpers (called from Events)
    // =========================================================================

    private void routeBreak(ServerLevel sl, BlockPos pos, BlockState brokenState) {
        boolean navigationChanged = NavGraph.blockChangeAffectsNavigation(sl, pos,
                brokenState, Blocks.AIR.defaultBlockState());
        // Only a break that takes a Town Hall from valid to invalid orphans its
        // village (breaking its door). A hall that is not registered yet, such
        // as one whose frame is still loading after a world load, must not be
        // orphaned because a player broke some unrelated block.
        List<UUID> validTownHalls = new ArrayList<>();
        for (UUID villageId : townHallFrameVillages) {
            if (inactiveVillages.contains(villageId)) continue;
            VillageManager manager = villages.get(villageId);
            UUID frameId = townHallFrameByVillage.getOrDefault(villageId, villageId);
            if (manager != null && dimMatch(manager, sl) && manager.hasValidTownHall(frameId)) {
                validTownHalls.add(villageId);
            }
        }
        for (VillageManager mgr : villages.values())
            if (dimMatch(mgr, sl)) mgr.routeBlockBreak(sl, pos, navigationChanged);
        for (UUID villageId : validTownHalls) {
            VillageManager manager = villages.get(villageId);
            UUID frameId = townHallFrameByVillage.getOrDefault(villageId, villageId);
            if (manager != null && !manager.hasValidTownHall(frameId)) {
                markVillageInactive(sl, villageId, Tr.c("log.dynamicvillages.town_hall.paused.broken",
                        "A broken block made the Town Hall stop working."));
            }
        }
        townHallSetups.noteChange(sl, pos);
    }

    private void routePlace(ServerLevel sl, BlockPos pos, BlockState previous, BlockState placed) {
        boolean navigationChanged = NavGraph.blockChangeAffectsNavigation(sl, pos, previous, placed);
        for (VillageManager mgr : villages.values())
            if (dimMatch(mgr, sl)) mgr.routeBlockPlace(pos, navigationChanged);
        townHallSetups.noteChange(sl, pos);
    }

    private void routeToolModification(ServerLevel sl, BlockPos pos, BlockState before) {
        for (VillageManager mgr : villages.values())
            if (dimMatch(mgr, sl)) mgr.routeToolModification(pos, before);
    }

    private void routeTreeGrow(ServerLevel sl, BlockPos pos) {
        for (VillageManager mgr : villages.values())
            if (dimMatch(mgr, sl)) mgr.routeTreeGrow(pos);
    }

    private void routeBonemeal(ServerLevel sl, BlockPos pos) {
        for (VillageManager mgr : villages.values())
            if (dimMatch(mgr, sl)) mgr.routeBonemeal(pos);
    }

    private void routeCropGrow(ServerLevel sl, BlockPos pos,
                               net.minecraft.world.level.block.state.BlockState newState) {
        for (VillageManager mgr : villages.values())
            if (dimMatch(mgr, sl)) mgr.routeCropGrow(pos, newState);
    }

    private void routeEntityJoin(ServerLevel sl, ItemFrame frame) {
        for (VillageManager mgr : villages.values())
            if (dimMatch(mgr, sl)) mgr.routeEntityJoin(frame);
        if (ItemFrameTokenRegistry.tokenType(frame.getItem())
                == ItemFrameTokenRegistry.TokenType.TOWN_HALL) {
            townHallHung(sl, frame);
        }
    }

    /**
     * A building token is leaving the frame at {@code framePos} (punched or
     * shot out, swapped, emptied by a command): check it as a right-click
     * would, so the building goes next tick instead of at the rolling re-check.
     */
    public void onFrameTokenLeaving(ServerLevel sl, net.minecraft.core.BlockPos framePos) {
        routeItemFrameInteract(sl, framePos);
    }

    private void routeItemFrameInteract(ServerLevel sl, net.minecraft.core.BlockPos framePos) {
        for (VillageManager mgr : villages.values())
            if (dimMatch(mgr, sl)) mgr.routeItemFrameInteract(framePos);
        FrameCheckKey key = new FrameCheckKey(sl.dimension(), framePos.immutable());
        pendingTownHallChecks.put(key, new PendingFrameCheck(sl.dimension(), framePos.immutable()));
    }

    private void routeArmorStandChange(ServerLevel sl, BlockPos pos) {
        for (VillageManager mgr : villages.values())
            if (dimMatch(mgr, sl)) mgr.routeArmorStandChange(pos);
    }

    private void routeEntityLeave(ServerLevel sl, ItemFrame frame) {
        for (VillageManager mgr : villages.values())
            if (dimMatch(mgr, sl)) mgr.routeEntityLeave(frame);
        UUID villageId = findVillageIdByTownHallFrame(frame.getUUID());
        if (villageId != null) {
            markVillageInactive(sl, villageId, Tr.c("log.dynamicvillages.town_hall.paused.frame",
                    "The Town Hall's item frame was broken."));
        }
    }

    static boolean dimMatch(VillageManager mgr, ServerLevel sl) {
        return mgr.getLevel() != null && sl.dimension().equals(mgr.getLevel().dimension());
    }

    static String shortId(UUID id) { return id.toString().substring(0, 8); }

    // =========================================================================
    // Persistence
    // =========================================================================

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        List<SavedVillage> entries = new ArrayList<>();
        Set<UUID> writtenVillageIds = new HashSet<>();
        for (VillageManager mgr : villages.values()) {
            entries.add(snapshotRuntimeVillage(mgr));
            writtenVillageIds.add(mgr.getVillageId());
        }
        // A dimension may not have reached LevelEvent.Load/tick yet. Preserve
        // those loaded-but-not-initialized entries instead of overwriting the
        // previous SavedData file with an incomplete registry.
        for (SavedVillage pendingVillage : pending) {
            if (writtenVillageIds.add(pendingVillage.id())) {
                entries.add(pendingVillage);
            }
        }
        tag.putLong(TAG_EMPTY_SERVER_TICKS, dormancy.emptyServerTicks());
        tag.putBoolean(TAG_CLAIMS_ENABLED, claimsEnabled);
        return VillageRegistryCodec.write(tag, entries);
    }

    /** Ticks the server has run with nobody online, ever; see {@link VillageDormancy#countEmptyServerTick}. */
    private static final String TAG_EMPTY_SERVER_TICKS = "emptyServerTicks";
    /** The world's claims switch; absent in older saves, which read as on. */
    private static final String TAG_CLAIMS_ENABLED = "claimsEnabled";

    /** Whether village claims are on in this world. */
    public boolean claimsEnabled() {
        return claimsEnabled;
    }

    public void setClaimsEnabled(boolean enabled) {
        if (claimsEnabled == enabled) return;
        claimsEnabled = enabled;
        setDirty();
        com.alex.test.village.claims.ClaimLock.markDirty();
    }

    /** A player who owns or is listed in villages logged in under a new name: the villages say the new one. */
    private void refreshOwnerName(ServerPlayer player) {
        for (VillageManager mgr : villages.values()) {
            if (mgr.claim().renamePlayer(player.getUUID(), player.getGameProfile().getName())) setDirty();
        }
    }

    /** The persisted form of a live village, combining its manager's state with the registry's. */
    private SavedVillage snapshotRuntimeVillage(VillageManager manager) {
        UUID villageId = manager.getVillageId();
        ResourceKey<Level> savedDimension = manager.getLevel() != null
                ? manager.getLevel().dimension()
                : Level.OVERWORLD;
        Set<UUID> villagerIds = new LinkedHashSet<>(manager.getVillagerIds());
        villagerIds.addAll(rememberedVillagersByVillage.getOrDefault(
                villageId, Collections.emptySet()));
        return new SavedVillage(
                villageId,
                savedDimension,
                manager.getBellPos(),
                manager.getGraphChunkRadius(),
                townHallFrameVillages.contains(villageId),
                townHallFrameByVillage.get(villageId),
                inactiveVillages.contains(villageId),
                missingSince.getOrDefault(villageId, -1L),
                dormancy.isDormant(villageId),
                dormancy.isReconciling(villageId),
                dormancy.getDormantSinceGameTime(villageId),
                rememberedMayorByVillage.get(villageId),
                villagerIds,
                manager.getSavedBuildings(),
                manager.getSavedBedAssignments(),
                manager.nomads().saveSchedule(),
                List.of(),
                manager.nomads().savedActiveNomads(),
                manager.visitors().savedState(),
                // Not built yet: its farmer manager is empty, the saved state waits in it.
                manager.isAwaitingInitialize() ? manager.deferredFarmState() : manager.farmers().saveState(),
                manager.minerSideBranchDistance(),
                manager.saveRules(),
                manager.saveMineSettings(),
                townHallTokenGeneration(villageId),
                manager.captainIds(),
                manager.knownNames(),
                manager.lastSeenSpots(),
                manager.lastSeenDimensions(),
                dormancy.saveExtra(villageId),
                manager.claim().save());
    }

    /**
     * The saved bed claims, less those held by someone no longer in the
     * village: such a bed stayed taken for good. The Mayor and Town Hall beds,
     * which are not residents' claims, are kept. The Mayor's claim is filed
     * under its own saved id, not {@code mayorId} (its body's id), so it is
     * kept by its building: only Mayors claim Town Hall beds, and one left by
     * a Mayor that is gone is freed at the next Mayor's bedtime.
     */
    private List<VillageManager.SavedBedAssignment> memberBedClaims(SavedVillage village) {
        List<VillageManager.SavedBedAssignment> kept = new ArrayList<>();
        Set<UUID> townHalls = new HashSet<>();
        for (VillageManager.SavedBuilding building : village.buildings()) {
            if (building != null && "town_hall".equals(building.type())) townHalls.add(building.itemFrameId());
        }
        int dropped = 0;
        for (VillageManager.SavedBedAssignment claim : village.bedAssignments()) {
            if (claim == null || claim.villagerId() == null
                    || village.villagerIds().contains(claim.villagerId())
                    || claim.villagerId().equals(village.mayorId())
                    || village.id().equals(claim.buildingId())
                    || townHalls.contains(claim.buildingId())) {
                kept.add(claim);
                continue;
            }
            dropped++;
            LOGGER.info("[Registry] Village {}: freed bed {} held by {}, who is not in the village.",
                    shortId(village.id()), claim.bedPos(), claim.villagerId().toString().substring(0, 8));
        }
        if (dropped > 0) setDirty();
        return kept;
    }

    public static VillageManagerRegistry load(CompoundTag tag,
                                               net.minecraft.core.HolderLookup.Provider registries) {
        VillageManagerRegistry reg = new VillageManagerRegistry();
        VillageRegistryCodec.Loaded loaded = VillageRegistryCodec.read(tag);
        reg.pending.addAll(loaded.villages());
        reg.dormancy.setEmptyServerTicks(tag.getLong(TAG_EMPTY_SERVER_TICKS));
        reg.claimsEnabled = !tag.contains(TAG_CLAIMS_ENABLED) || tag.getBoolean(TAG_CLAIMS_ENABLED);
        if (loaded.migrated()) reg.setDirty();
        LOGGER.info("[Registry] Loaded persistent registry: {} village(s), {} villager id(s), "
                        + "{} pending runtime initialization.",
                reg.pending.size(), loaded.villagerIds(), reg.pending.size());
        return reg;
    }

    // =========================================================================
    // Static event handlers — route to all bell-placed villages
    // =========================================================================

    /**
     * NeoForge event listener for all registered villages.
     *
     * <h3>What goes here</h3>
     * Only events that need to be forwarded to every registered village manager.
     * The INSTANCE (command/debug village) handles its own events independently.
     *
     * <h3>Nav events</h3>
     * Block break/place events and collision-changing growth events dirty the nav graph.
     *
     * <h3>Knowledge events</h3>
     * {@code BlockGrowFeatureEvent}, {@code CropGrowEvent.Post}, and
     * {@code EntityJoinLevelEvent} update the KnowledgeBase.
     */
    public static final class Events {

        private Events() {}

        // ── Tick ──────────────────────────────────────────────────────────────

        @SubscribeEvent
        public static void onLevelLoad(LevelEvent.Load event) {
            if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
            VillageManagerRegistry registry = VillageManagerRegistry.get(sl.getServer());
            registry.initializePendingVillages(sl);
        }

        @SubscribeEvent
        public static void onLevelTick(LevelTickEvent.Post event) {
            if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
            // Frozen world (/tick freeze, the stuck catcher): villages wait too.
            if (com.alex.test.village.stuckwatch.StuckWatch.worldHeld(sl)) return;
            VillageManagerRegistry.get(sl.getServer()).tickAll(sl);
        }

        @SubscribeEvent
        public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            VillageManagerRegistry registry = VillageManagerRegistry.get(player.server);
            registry.sendCurrentBoundsTo(player);
            registry.refreshOwnerName(player);
        }

        @SubscribeEvent
        public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            VillageManagerRegistry.get(player.server).sendCurrentBoundsTo(player);
        }

        @SubscribeEvent
        public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            VillageManagerRegistry.get(player.server).sendCurrentBoundsTo(player);
        }

        // ── Nav: break, place and tool use ────────────────────────────────────

        @SubscribeEvent
        public static void onBlockBreak(BlockEvent.BreakEvent event) {
            if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
            VillageManagerRegistry.get(sl.getServer()).routeBreak(sl, event.getPos(), event.getState());
        }

        @SubscribeEvent
        public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
            if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
            VillageManagerRegistry.get(sl.getServer()).routePlace(sl, event.getPos(),
                    event.getBlockSnapshot().getState(), event.getPlacedBlock());
        }

        /**
         * A shovel making a path, a hoe tilling, an axe stripping a log. None
         * of these is a break or a place, so without this the nav graph never
         * learned of them. Fires before the change, with the old state; each
         * manager compares the block on its next tick.
         */
        @SubscribeEvent
        public static void onToolModify(BlockEvent.BlockToolModificationEvent event) {
            if (event.isSimulated()) return;
            if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
            VillageManagerRegistry.get(sl.getServer()).routeToolModification(sl, event.getPos(), event.getState());
        }

        // ── Knowledge: grow events ─────────────────────────────────────────────

        /**
         * Sapling growing into a tree feature.  Deferred to next tick by each
         * manager because the event fires before the tree blocks are placed.
         */
        @SubscribeEvent
        public static void onTreeGrow(BlockGrowFeatureEvent event) {
            if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
            VillageManagerRegistry.get(sl.getServer()).routeTreeGrow(sl, event.getPos());
        }

        /**
         * Bonemeal forces a crop to maturity without firing
         * {@code CropGrowEvent.Post} -- vanilla's {@code performBonemeal} calls
         * {@code growCrops} directly -- so without this a player who bonemeals
         * a field creates crops no farmer in the village can ever see.
         */
        @SubscribeEvent
        public static void onBonemeal(BonemealEvent event) {
            if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
            VillageManagerRegistry.get(sl.getServer()).routeBonemeal(sl, event.getPos());
        }

        /** Crop finishes growing one stage; update KB if now fully mature. */
        @SubscribeEvent
        public static void onCropGrow(CropGrowEvent.Post event) {
            if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
            VillageManagerRegistry.get(sl.getServer())
                    .routeCropGrow(sl, event.getPos(), event.getState());
        }

        // ── Knowledge: entity events ───────────────────────────────────────────

        /**
         * Entity entered the world.  If it is an {@link ItemFrame} we treat its
         * position as a room marker in the KnowledgeBase.
         */
        @SubscribeEvent
        public static void onEntityJoin(EntityJoinLevelEvent event) {
            if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
            VillageManagerRegistry registry = VillageManagerRegistry.get(sl.getServer());
            if (event.getEntity() instanceof ItemFrame frame) {
                registry.routeEntityJoin(sl, frame);
            } else if (event.getEntity() instanceof net.minecraft.world.entity.decoration.ArmorStand stand) {
                registry.routeArmorStandChange(sl, stand.blockPosition());
            } else if (event.getEntity() instanceof NpcEntity npc
                    && !(npc instanceof AbstractTravelerEntity)
                    && !(npc instanceof MayorEntity)
                    && !registry.tryRelinkVillager(sl, npc)) {
                registry.queueVillagerRelink(sl, npc);
            }
        }

        @SubscribeEvent
        public static void onVillagerDeath(LivingDeathEvent event) {
            if (!(event.getEntity() instanceof NpcEntity npc)) return;
            if (!(npc.level() instanceof ServerLevel sl) || sl.getServer() == null) return;
            if (npc instanceof MayorEntity mayor) {
                var link = mayor.getTownHallLink();
                if (link != null) {
                    VillageManagerRegistry.get(sl.getServer())
                            .forgetMayor(link.villageId(), mayor.getUUID());
                }
                VillageManagerRegistry.get(sl.getServer()).releaseMayorBed(mayor);
                return;
            }
            if (npc instanceof AbstractTravelerEntity traveler) {
                traveler.confirmExternalVisitEnd(sl, "death");
                return;
            }
            VillageManagerRegistry.get(sl.getServer()).handleVillagerDeath(sl, npc);
        }

        /**
         * Entity left the world.  If it is an {@link ItemFrame} that belongs to a
         * registered building, the building is removed.
         *
         * <p>Chunk unloads ({@code UNLOADED_TO_CHUNK} / {@code UNLOADED_WITH_PLAYER})
         * are ignored — the frame will come back and the building should persist.
         */
        @SubscribeEvent
        public static void onEntityLeave(EntityLeaveLevelEvent event) {
            if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
            // Barracks posts: placed, broken or unloaded, the stand is gone from the room.
            if (event.getEntity() instanceof net.minecraft.world.entity.decoration.ArmorStand stand) {
                VillageManagerRegistry.get(sl.getServer()).routeArmorStandChange(sl, stand.blockPosition());
                return;
            }
            if (event.getEntity() instanceof MayorEntity mayor) {
                net.minecraft.world.entity.Entity.RemovalReason reason = mayor.getRemovalReason();
                if (reason != null
                        && reason != net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK
                        && reason != net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER
                        && reason != net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION) {
                    var link = mayor.getTownHallLink();
                    if (link != null) {
                        VillageManagerRegistry.get(sl.getServer())
                                .forgetMayor(link.villageId(), mayor.getUUID());
                    }
                    VillageManagerRegistry.get(sl.getServer()).releaseMayorBed(mayor);
                }
                return;
            }
            if (event.getEntity() instanceof AbstractTravelerEntity traveler) {
                net.minecraft.world.entity.Entity.RemovalReason reason = traveler.getRemovalReason();
                if (reason != null
                        && reason != net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK
                        && reason != net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER
                        && reason != net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION) {
                    traveler.confirmExternalVisitEnd(sl, "removed: " + reason);
                }
                return;
            }
            if (event.getEntity() instanceof NpcEntity npc && !(npc instanceof MayorEntity)) {
                VillagerVillageLink link = npc.getVillageLink();
                if (link != null) {
                    VillageManagerRegistry.get(sl.getServer())
                            .queueVillagerUnbind(link.villageId(), npc);
                }
                return;
            }
            if (!(event.getEntity() instanceof ItemFrame frame)) return;
            net.minecraft.world.entity.Entity.RemovalReason reason = frame.getRemovalReason();
            if (reason == null
                    || reason == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK
                    || reason == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
                return;
            }
            VillageManagerRegistry.get(sl.getServer()).routeEntityLeave(sl, frame);
        }

        /**
         * Player right-clicks an item frame (general interact packet).
         * Queue the frame position for building creation on the next tick.
         */
        @SubscribeEvent
        public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
            noteTownHallHanger(event, event.getTarget());
            if (tryOpenStorage(event, event.getTarget())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
            routeFrameInteract(event.getLevel(), event.getTarget());
        }

        /**
         * Player right-clicks an item frame at a specific point (INTERACT_AT packet).
         * Item frames sometimes arrive via this event instead of the general one.
         */
        @SubscribeEvent
        public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
            noteTownHallHanger(event, event.getTarget());
            if (tryOpenStorage(event, event.getTarget())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
            routeFrameInteract(event.getLevel(), event.getTarget());
        }

        /** A player hanging a fresh Town Hall token: remembered, so the village it founds is theirs. */
        private static void noteTownHallHanger(PlayerInteractEvent event, net.minecraft.world.entity.Entity target) {
            if (!(event.getEntity() instanceof ServerPlayer player) || !(target instanceof ItemFrame frame)) return;
            VillageManagerRegistry.get(player.server).townHallHangers.note(
                    player, frame, player.getItemInHand(event.getHand()), player.server.getTickCount());
        }

        private static boolean tryOpenStorage(PlayerInteractEvent event,
                                               net.minecraft.world.entity.Entity target) {
            if (!(event.getEntity() instanceof ServerPlayer player)
                    || !(target instanceof ItemFrame frame)) return false;
            if (StorageRoomAccess.openFromFrame(player, frame)) return true;
            if (com.alex.test.village.building.HouseRoomAccess.openFromFrame(player, frame)) {
                // A Town Hall click also has the hall looked over again (token out or swapped); it still does.
                if (ItemFrameTokenRegistry.tokenType(frame.getItem()) == ItemFrameTokenRegistry.TokenType.TOWN_HALL) {
                    routeFrameInteract(event.getLevel(), target);
                }
                return true;
            }
            if (com.alex.test.village.building.MineRoomAccess.openFromFrame(player, frame)) {
                // Before the frame opened a screen the click was ignored and
                // asked for the mine to be looked over again; it still does.
                routeFrameInteract(event.getLevel(), target);
                return true;
            }
            if (com.alex.test.village.building.ButcheryRoomAccess.openFromFrame(player, frame)) {
                // Like the mine: the click still has the room looked over again.
                routeFrameInteract(event.getLevel(), target);
                return true;
            }
            if (com.alex.test.village.building.RanchRoomAccess.openFromFrame(player, frame)) {
                // Like the mine: the click still has the pen looked over again.
                routeFrameInteract(event.getLevel(), target);
                return true;
            }
            return false;
        }

        /**
         * Village claims round 7b: the land lock refused this click (a Visitor on someone's land), but a token's
         * window may still be looked at. Opens it, if the frame holds one: the storage room still needs Friend
         * (it says so in chat), the others open look only. The building is not looked over again here.
         */
        public static boolean openWindowToLook(ServerPlayer player, ItemFrame frame) {
            return StorageRoomAccess.openFromFrame(player, frame)
                    || com.alex.test.village.building.HouseRoomAccess.openFromFrame(player, frame)
                    || com.alex.test.village.building.MineRoomAccess.openFromFrame(player, frame)
                    || com.alex.test.village.building.ButcheryRoomAccess.openFromFrame(player, frame)
                    || com.alex.test.village.building.RanchRoomAccess.openFromFrame(player, frame);
        }

        private static void routeFrameInteract(
                net.minecraft.world.level.LevelAccessor level,
                net.minecraft.world.entity.Entity target) {
            if (!(level instanceof ServerLevel sl) || sl.getServer() == null) return;
            if (!(target instanceof ItemFrame)) return;
            net.minecraft.core.BlockPos framePos = target.blockPosition();
            VillageManagerRegistry.get(sl.getServer()).routeItemFrameInteract(sl, framePos);
        }
    }
}
