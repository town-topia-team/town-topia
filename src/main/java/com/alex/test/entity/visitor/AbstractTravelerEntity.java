package com.alex.test.entity.visitor;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VanillaNavCount;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.visitors.TravelerGraphWalk;
import com.alex.test.village.visitors.TravelerLifecycleRules;
import com.alex.test.village.visitors.TravelerThreatResponse;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/** Shared, persistent return-home lifecycle for non-member travellers. */
public abstract class AbstractTravelerEntity extends NpcEntity {
    private static final String TAG_DEPARTURE = "TravelerDepartureAt";
    private static final String TAG_DEPARTURE_CLOCK = "TravelerDepartureClock";
    private static final String TAG_LAST_DAY_TIME = "TravelerLastDayTime";
    private static final String LEGACY_TAG_DESPAWN = "VisitorDespawnAt";
    private static final String TAG_RETURNING = "TravelerReturning";
    private static final String TAG_RETURN_STARTED = "TravelerReturnStartedAt";
    private static final String TAG_ORIGIN_X = "TravelerOriginX";
    private static final String TAG_ORIGIN_Y = "TravelerOriginY";
    private static final String TAG_ORIGIN_Z = "TravelerOriginZ";
    private static final String TAG_ORIGIN_DIMENSION = "TravelerOriginDimension";
    private static final String TAG_GATHERING_SPOT = "TravelerGatheringSpot";

    private long departureAtGameTime = -1L;
    private int departureDayClock = -1;
    private long lastObservedDayTime = -1L;
    private boolean returningHome;
    private long returnStartedAt = -1L;
    private long nextReturnPathAt;
    private Vec3 spawnOrigin;
    private ResourceKey<Level> spawnDimension;
    private BlockPos gatheringSpot;
    private boolean lifecycleEnded;
    private boolean loggedReturnPathFailure;
    private final TravelerThreatResponse threatResponse = new TravelerThreatResponse(this);
    private boolean fleeing;
    /** The walk home on the village map; off the map, vanilla navigation walks. */
    private static final TravelerGraphWalk HOME_WALK = new TravelerGraphWalk("Return");
    /**
     * Transient: the map walk home gave up, so the traveller waits on the map
     * until this game time, then the map walk tries again. It used to be given
     * up for the whole trip, and vanilla walked a nomad into a village pond.
     */
    private long homeWalkRetryAt = -1L;
    /** A villager's failed walk is retried after a wait like this one. */
    private static final long HOME_WALK_RETRY_TICKS = 640L;
    /** Transient: when the villager model's swim-out took this traveller (see tickWaterRescue), or -1. */
    private long rescueStartedAt = -1L;
    /** Transient: the swim-out got nowhere; its own walks run until it is out of the water. */
    private boolean rescueGaveUp;
    /** A swim-out still in the water after this long (30 s) is given up. */
    private static final long WATER_RESCUE_GIVE_UP_TICKS = 600L;
    /** Vanilla walk home: hops this far toward home, which its path search can plan. */
    private static final int RETURN_HOP_BLOCKS = 24;
    /** Vanilla walk home: no block of progress in this long means a sidestep. */
    private static final long RETURN_NO_PROGRESS_TICKS = 600L;
    private Vec3 returnProgressPos;
    private long returnProgressAt;
    /** A walk with no headway for this long (60 s) hops to an open map tile nearby. */
    public static final long STUCK_HOP_TICKS = 1_200L;
    /** A walk home with no headway for this long (2 min), even after the hop, just ends the visit. */
    private static final long STUCK_LEAVE_TICKS = 2_400L;
    /** Blocks closer to the goal that count as headway. */
    private static final double STUCK_HEADWAY_BLOCKS = 2.0D;
    /** How far the stuck hop may look for an open tile. */
    private static final int STUCK_HOP_RADIUS = 6;
    /** Transient stuck watch: the goal, the closest the traveller got to it, and since when. */
    private Vec3 stuckGoal;
    private double stuckBestDist;
    private long stuckSince = -1L;
    private long stuckLastTick = -1L;
    private boolean stuckHopped;
    /** A trade screen open at leave time holds the departure this long at most (1 in-game hour). */
    private static final long SCREEN_HOLD_TICKS = 1_000L;
    /** When the leave time came due (-1: not due), and for which leave time. Transient. */
    private long departureDueSince = -1L;
    private long departureDueFor = -1L;
    private String departureDueReason = "";

    protected AbstractTravelerEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    /** Kept for command/test compatibility; this is now a departure deadline, not an instant despawn. */
    public long getDespawnAtGameTime() { return departureAtGameTime; }
    public void setDespawnAtGameTime(long gameTime) { this.departureAtGameTime = gameTime; }
    public long getDepartureAtGameTime() { return departureAtGameTime; }
    public void setDepartureAtGameTime(long gameTime) {
        // A new leave time (kept overnight by a storm, sent off early) gets its
        // clock time worked out again; the old one would still fire at dusk.
        if (gameTime != departureAtGameTime) departureDayClock = -1;
        this.departureAtGameTime = gameTime;
    }
    public void setDepartureSchedule(long gameTime, int dayClock, long currentDayTime) {
        departureAtGameTime = gameTime;
        departureDayClock = dayClock;
        lastObservedDayTime = currentDayTime;
    }
    public boolean isReturningHome() { return returningHome; }

    /** This traveller's own villager movement model, shared by its walks and its flee. */
    public VillagerModel travelMovement() { return threatResponse.movement(); }

    public Vec3 getExactSpawnOrigin() { return spawnOrigin != null ? spawnOrigin : position(); }
    public BlockPos getSpawnOrigin() { return BlockPos.containing(getExactSpawnOrigin()); }
    public ResourceKey<Level> getSpawnDimension() {
        return spawnDimension != null ? spawnDimension : level().dimension();
    }

    public BlockPos getGatheringSpot() { return gatheringSpot; }
    public void setGatheringSpot(BlockPos spot) {
        gatheringSpot = spot != null ? spot.immutable() : null;
    }

    public void setSpawnOrigin(ServerLevel level, Vec3 origin) {
        spawnOrigin = origin;
        spawnDimension = level.dimension();
    }

    public void setSpawnOrigin(ServerLevel level, BlockPos origin) {
        setSpawnOrigin(level, Vec3.atBottomCenterOf(origin));
    }

    /**
     * Travellers swim up in deep water like vanilla mobs. Only the villager
     * model's swim-out lifted them before, and a walk home on vanilla
     * navigation (the map walk gave up, or off the map) sank and drowned.
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override public final boolean isVillageMember() { return false; }

    /** The village this traveller is visiting, if it has been assigned one. */
    @Nullable public abstract UUID getVillageId();

    /** Travellers collide and gently push apart instead of occupying one hitbox. */
    @Override public boolean isPushable() { return true; }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel serverLevel) || lifecycleEnded) return;
        reconcileLifecycle(serverLevel);
        if (lifecycleEnded || isRemoved()) return;

        long now = serverLevel.getGameTime();
        long dayTime = serverLevel.getDayTime();
        if (departureDayClock < 0 && departureAtGameTime >= now) {
            departureDayClock = (int) Math.floorMod(
                    dayTime + (departureAtGameTime - now), TravelerLifecycleRules.DAY_TICKS);
        }
        boolean crossedBedtime = departureDayClock >= 0
                && TravelerLifecycleRules.crossedDayClock(
                lastObservedDayTime, dayTime, departureDayClock);
        lastObservedDayTime = dayTime;
        if (!returningHome && departureAtGameTime >= 0
                && (now >= departureAtGameTime || crossedBedtime) && departureDueSince < 0) {
            departureDueSince = now;
            departureDueFor = departureAtGameTime;
            departureDueReason = crossedBedtime ? "bedtime crossed" : "game-time deadline";
        }
        // A new leave time (a storm keeps it overnight) cancels the one that came due.
        if (departureDueSince >= 0 && departureDueFor != departureAtGameTime) departureDueSince = -1L;
        // Someone mid-trade: wait for the screen to close, up to an in-game hour.
        if (!returningHome && departureDueSince >= 0
                && (!uiInteraction().isMenuOpen(this, serverLevel)
                        || now - departureDueSince >= SCREEN_HOLD_TICKS)) {
            returningHome = true;
            returnStartedAt = now;
            nextReturnPathAt = 0L;
            departureDueSince = -1L;
            getNavigation().stop();
            com.alex.test.village.visitors.TravelerNight.clear(this);
            DynamicVillages.LOGGER.info("{} {} began returning home to {} in {} ({}).",
                    lifecycleName(), getUUID(), getExactSpawnOrigin(), getSpawnDimension().location(),
                    departureDueReason);
        }

        // Checked before anything that can hold the traveller in place (a
        // flee, a swim-out going nowhere), so the timeout always runs.
        if (returningHome && tickReturnTimeout(serverLevel, now)) return;
        if (tickFlee(serverLevel)) {
            com.alex.test.village.visitors.TravelerNight.clear(this);
            return;
        }
        if (tickWaterRescue(serverLevel)) return;
        if (returningHome) tickReturnHome(serverLevel, now);
        else if (isAlive() && !isRemoved()
                && !uiInteraction().tickTraveler(this, serverLevel)) tickTraveler(serverLevel);
    }

    /** Returns true while fleeing; the walk home re-paths as soon as it ends. */
    private boolean tickFlee(ServerLevel level) {
        if (threatResponse.tick(level)) {
            fleeing = true;
            return true;
        }
        if (fleeing) {
            fleeing = false;
            nextReturnPathAt = 0L;
        }
        return false;
    }

    /**
     * In water inside the village, the traveller's villager model gets it out
     * the way it gets a villager out: swim up, head for the nearest exit, climb
     * onto dry ground. A villager's model runs every tick, but a traveller's
     * only ran during a map walk, so one in water while trading, camping, or
     * walking home on vanilla navigation had no swim-out at all. True while
     * the swim-out has it; everything else waits.
     */
    private boolean tickWaterRescue(ServerLevel level) {
        VillagerModel model = travelMovement();
        NavGraph graph = model.isEscapingWater() ? villageGraph(level) : null;
        if (graph == null || !graph.isInsideBounds(blockPosition())) {
            rescueStartedAt = -1L;
            rescueGaveUp = false;
            return false;
        }
        if (rescueGaveUp) return false;
        long now = level.getGameTime();
        if (rescueStartedAt < 0) {
            rescueStartedAt = now;
            getNavigation().stop();
            DynamicVillages.LOGGER.info("{} {} is in water at {}; the villager swim-out takes over.",
                    lifecycleName(), getUUID(), blockPosition().toShortString());
        } else if (now - rescueStartedAt >= WATER_RESCUE_GIVE_UP_TICKS) {
            // A pond with no exit on the map, or an exit it cannot climb: the
            // swim-out would hold it there for good. Back to its own walks,
            // afloat (FloatGoal), until it is out of the water.
            rescueGaveUp = true;
            model.stopMoving();
            nextReturnPathAt = 0L;
            DynamicVillages.LOGGER.info("{} {} got nowhere swimming out at {} in {} s; "
                    + "walking on vanilla navigation until out of the water.",
                    lifecycleName(), getUUID(), blockPosition().toShortString(),
                    WATER_RESCUE_GIVE_UP_TICKS / 20);
            return false;
        }
        model.tick(graph);
        return true;
    }

    @Nullable
    private NavGraph villageGraph(ServerLevel level) {
        if (getVillageId() == null) return null;
        VillageManager village = VillageManagerRegistry.get(level.getServer()).get(getVillageId());
        NavGraph graph = village != null ? village.getGraph() : null;
        return graph != null && !graph.isEmpty() ? graph : null;
    }

    /** Walking home too long with no player near: the visit ends. True when it did. */
    private boolean tickReturnTimeout(ServerLevel level, long now) {
        if (returnStartedAt < 0
                || now - returnStartedAt < TravelerLifecycleRules.RETURN_TIMEOUT_TICKS
                || level.hasNearbyAlivePlayer(getX(), getY(), getZ(),
                TravelerLifecycleRules.SAFE_REMOVAL_PLAYER_RADIUS)) return false;
        finishVisit(level, level.dimension().equals(getSpawnDimension())
                ? "return timeout" : "wrong-dimension fallback");
        return true;
    }

    private void tickReturnHome(ServerLevel level, long now) {
        Vec3 origin = getExactSpawnOrigin();
        boolean correctDimension = level.dimension().equals(getSpawnDimension());
        if (correctDimension && position().distanceToSqr(origin)
                <= TravelerLifecycleRules.HOME_RADIUS_SQ) {
            finishVisit(level, "reached spawn");
            return;
        }
        if (correctDimension && tickStuckHome(level, now, origin)) return;
        if (correctDimension && tickMapWalkHome(level)) {
            // Arrival sites are tiles on the village map, so the villagers'
            // own pathfinder can usually walk the whole way home.
            if (lifecycleEnded) return;
        } else if (correctDimension && waitsForMapWalk(level, now)) {
            // On the map, but its walk home just gave up: stand and wait for the
            // retry. Vanilla walking here was what pushed travellers into cliffs.
            getNavigation().stop();
        } else if (correctDimension && now >= nextReturnPathAt) {
            tickVanillaWalkHome(level, now, origin);
            nextReturnPathAt = now + TravelerLifecycleRules.REPATH_INTERVAL_TICKS;
        }
    }

    /**
     * The stuck watch on the walk home. No headway for 60 s: hop to an open
     * map tile and walk again. Still none at 2 min: the visit ends where it
     * stands, even with a player watching, rather than a traveller frozen
     * on a ledge for good. True when it acted this tick.
     */
    private boolean tickStuckHome(ServerLevel level, long now, Vec3 origin) {
        long stuck = walkStuckTicks(now, origin);
        if (stuck >= STUCK_LEAVE_TICKS) {
            DynamicVillages.LOGGER.info("{} {} made no headway home from {} in {} s; the visit ends here.",
                    lifecycleName(), getUUID(), blockPosition().toShortString(), stuck / 20);
            finishVisit(level, "stuck walking home");
            return true;
        }
        if (!hopIfStuck(level, stuck)) return false;
        HOME_WALK.clear(this);
        homeWalkRetryAt = -1L;
        nextReturnPathAt = 0L;
        return true;
    }

    /**
     * True while the map walk home is waiting out its retry and the traveller
     * stands on the map. Off the map, or afloat after a failed swim-out,
     * vanilla navigation still walks.
     */
    private boolean waitsForMapWalk(ServerLevel level, long now) {
        if (rescueGaveUp || homeWalkRetryAt < 0 || now >= homeWalkRetryAt) return false;
        NavGraph graph = villageGraph(level);
        return graph != null && graph.isInsideBounds(blockPosition());
    }

    /**
     * Ticks this traveller has gone without getting {@value #STUCK_HEADWAY_BLOCKS}
     * blocks closer to {@code goal}. Called every tick of a walk; a new goal, or
     * a gap in the calls (a flee, the night, a trade screen), starts the count over.
     */
    public final long walkStuckTicks(long now, Vec3 goal) {
        double dist = position().distanceTo(goal);
        if (stuckGoal == null || stuckGoal.distanceToSqr(goal) > 1.0D || now - stuckLastTick > 20L) {
            stuckGoal = goal;
            stuckBestDist = dist;
            stuckSince = now;
            stuckHopped = false;
        } else if (dist <= stuckBestDist - STUCK_HEADWAY_BLOCKS) {
            stuckBestDist = dist;
            stuckSince = now;
            stuckHopped = false;
        }
        stuckLastTick = now;
        return now - stuckSince;
    }

    /**
     * Stuck for {@link #STUCK_HOP_TICKS}: hops once to an open map tile
     * nearby, off whatever ledge or corner holds it. True when it hopped; the
     * caller starts its walk over.
     */
    public final boolean hopIfStuck(ServerLevel level, long stuckTicks) {
        if (stuckHopped || stuckTicks < STUCK_HOP_TICKS) return false;
        stuckHopped = true;
        BlockPos from = blockPosition();
        if (!hopToOpenMapTile(level, STUCK_HOP_RADIUS, 2)) return false;
        DynamicVillages.LOGGER.info("{} {} made no headway in {} s at {}; hopped to {}.",
                lifecycleName(), getUUID(), stuckTicks / 20, from.toShortString(),
                blockPosition().toShortString());
        return true;
    }

    /**
     * Teleports onto the nearest open tile of the village map within
     * {@code radius}, at least {@code minStep} tiles away sideways: room to
     * stand, nothing to collide with. False when there is no map or no tile.
     */
    public final boolean hopToOpenMapTile(ServerLevel level, int radius, int minStep) {
        NavGraph graph = villageGraph(level);
        if (graph == null || !graph.isInsideBounds(blockPosition())) return false;
        net.minecraft.world.phys.AABB box = getBoundingBox();
        BlockPos feet = NavGraph.feetCell(this);
        var open = graph.nearestRoutable(feet, radius, 3, node -> {
            BlockPos to = node.feetPos();
            if (Math.max(Math.abs(to.getX() - feet.getX()), Math.abs(to.getZ() - feet.getZ())) < minStep
                    && to.getY() == feet.getY()) return false;
            if (to.equals(feet) || !NavGraph.hasStandingRoom(level, node)) return false;
            if (!level.getFluidState(to).isEmpty()) return false;
            var there = box.move(to.getX() + 0.5D - getX(), to.getY() - getY(), to.getZ() + 0.5D - getZ());
            return level.noCollision(this, there.inflate(0.2D, -0.05D, 0.2D));
        });
        if (open == null) return false;
        BlockPos to = open.feetPos();
        travelMovement().stopMoving();
        getNavigation().stop();
        teleportTo(to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D);
        return true;
    }

    /**
     * The walk home on the village map. True while it is in charge this tick;
     * false when the traveller is off the map, or the map walk gave up, and
     * vanilla navigation should walk instead.
     */
    private boolean tickMapWalkHome(ServerLevel level) {
        if (getVillageId() == null) return false;
        long now = level.getGameTime();
        if (homeWalkRetryAt >= 0 && now < homeWalkRetryAt) return false;
        // The map walk ticks the same model, whose swim-out just got nowhere.
        if (rescueGaveUp) return false;
        VillageManager village = VillageManagerRegistry.get(level.getServer()).get(getVillageId());
        if (village == null) return false;
        switch (HOME_WALK.tick(this, village, getSpawnOrigin(),
                (float) TravelerLifecycleRules.RETURN_WALK_SPEED)) {
            case WALKING -> { return true; }
            case ARRIVED -> {
                HOME_WALK.clear(this);
                finishVisit(level, "reached spawn");
                return true;
            }
            case FAILED -> {
                homeWalkRetryAt = now + HOME_WALK_RETRY_TICKS;
                HOME_WALK.clear(this);
                DynamicVillages.LOGGER.info("{} {} could not walk home to {} on the village map; "
                        + "waiting {} s to try again.", lifecycleName(), getUUID(),
                        getSpawnOrigin(), HOME_WALK_RETRY_TICKS / 20);
                return false;
            }
            default -> { return false; }
        }
    }

    /**
     * The walk home off the village map. Vanilla only plans about 32 blocks
     * ahead, so a far-away home is walked in hops it can plan. With no
     * progress for a while, a sidestep gets the traveller off whatever it is
     * pressed against.
     */
    private void tickVanillaWalkHome(ServerLevel level, long now, Vec3 origin) {
        double speed = TravelerLifecycleRules.RETURN_WALK_SPEED;
        if (returnProgressPos == null || position().distanceToSqr(returnProgressPos) > 1.0D) {
            returnProgressPos = position();
            returnProgressAt = now;
        } else if (now - returnProgressAt >= RETURN_NO_PROGRESS_TICKS) {
            returnProgressPos = position();
            returnProgressAt = now;
            Vec3 side = DefaultRandomPos.getPos(this, 8, 4);
            if (side != null && getNavigation().moveTo(side.x, side.y, side.z, speed)) {
                VanillaNavCount.note(VanillaNavCount.Site.HOME_SIDESTEP, this);
                DynamicVillages.LOGGER.info("{} {} made no progress home from {}; sidestepping.",
                        lifecycleName(), getUUID(), blockPosition().toShortString());
                return;
            }
        }

        Vec3 target = origin;
        double dx = origin.x - getX();
        double dz = origin.z - getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat > RETURN_HOP_BLOCKS) {
            double x = getX() + dx / flat * RETURN_HOP_BLOCKS;
            double z = getZ() + dz / flat * RETURN_HOP_BLOCKS;
            BlockPos column = BlockPos.containing(x, getY(), z);
            // Never load a chunk just to find the ground.
            double y = level.hasChunkAt(column)
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ())
                    : getY();
            target = new Vec3(x, y, z);
        }
        VanillaNavCount.note(VanillaNavCount.Site.HOME, this);
        if (!getNavigation().moveTo(target.x, target.y, target.z, speed)) {
            // No straight-line push when there is no path: it walked travellers
            // into cliffs and lakes. It waits for the next try, and the stuck
            // watch (tickStuckHome) hops it or ends the visit.
            if (!loggedReturnPathFailure) {
                loggedReturnPathFailure = true;
                DynamicVillages.LOGGER.warn("{} {} could not build a return path toward {}; "
                        + "waiting to try again.", lifecycleName(), getUUID(), origin);
            }
        }
    }

    public final void finishVisit(ServerLevel level, String reason) {
        if (lifecycleEnded) return;
        lifecycleEnded = true;
        onVisitEnded(level, reason);
        DynamicVillages.LOGGER.info("{} {} visit ended: {}.", lifecycleName(), getUUID(), reason);
        // Removed while lying in a guest bed: free the bed, or it reads as
        // occupied for good and nobody can sleep in it again.
        if (isSleeping()) stopSleeping();
        discard();
    }

    public final void confirmExternalVisitEnd(ServerLevel level, String reason) {
        if (lifecycleEnded) return;
        lifecycleEnded = true;
        onVisitEnded(level, reason);
    }

    protected abstract void reconcileLifecycle(ServerLevel level);
    protected abstract void onVisitEnded(ServerLevel level, String reason);
    protected abstract String lifecycleName();
    protected abstract void tickTraveler(ServerLevel level);

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putLong(TAG_DEPARTURE, departureAtGameTime);
        tag.putInt(TAG_DEPARTURE_CLOCK, departureDayClock);
        tag.putLong(TAG_LAST_DAY_TIME, lastObservedDayTime);
        tag.putBoolean(TAG_RETURNING, returningHome);
        tag.putLong(TAG_RETURN_STARTED, returnStartedAt);
        Vec3 origin = getExactSpawnOrigin();
        tag.putDouble(TAG_ORIGIN_X, origin.x);
        tag.putDouble(TAG_ORIGIN_Y, origin.y);
        tag.putDouble(TAG_ORIGIN_Z, origin.z);
        tag.putString(TAG_ORIGIN_DIMENSION, getSpawnDimension().location().toString());
        if (gatheringSpot != null) tag.putLong(TAG_GATHERING_SPOT, gatheringSpot.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(TAG_DEPARTURE)) departureAtGameTime = tag.getLong(TAG_DEPARTURE);
        else if (tag.contains(LEGACY_TAG_DESPAWN)) departureAtGameTime = tag.getLong(LEGACY_TAG_DESPAWN);
        departureDayClock = tag.contains(TAG_DEPARTURE_CLOCK)
                ? tag.getInt(TAG_DEPARTURE_CLOCK) : -1;
        lastObservedDayTime = tag.contains(TAG_LAST_DAY_TIME)
                ? tag.getLong(TAG_LAST_DAY_TIME) : -1L;
        returningHome = tag.getBoolean(TAG_RETURNING);
        returnStartedAt = tag.contains(TAG_RETURN_STARTED) ? tag.getLong(TAG_RETURN_STARTED) : -1L;
        if (tag.contains(TAG_ORIGIN_X) && tag.contains(TAG_ORIGIN_Y) && tag.contains(TAG_ORIGIN_Z)) {
            spawnOrigin = new Vec3(tag.getDouble(TAG_ORIGIN_X), tag.getDouble(TAG_ORIGIN_Y),
                    tag.getDouble(TAG_ORIGIN_Z));
        }
        ResourceLocation dimensionId = tag.contains(TAG_ORIGIN_DIMENSION)
                ? ResourceLocation.tryParse(tag.getString(TAG_ORIGIN_DIMENSION)) : null;
        if (dimensionId != null) spawnDimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        gatheringSpot = tag.contains(TAG_GATHERING_SPOT)
                ? BlockPos.of(tag.getLong(TAG_GATHERING_SPOT)) : null;
    }
}
