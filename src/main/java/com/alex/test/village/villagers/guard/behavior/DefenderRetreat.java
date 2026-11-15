package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.animation.HurryGait;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.RouteDoorHandler;
import com.alex.test.village.VanillaNavCount;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.shelter.ShelterWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A hurt defender's fall-back, shared by guards, captains and archers: run to
 * the closest building, get a few blocks in from its door and wait there.
 * Scared villagers who are not defenders run the same way
 * ({@link #forCivilian}, used by the brain's flee).
 *
 * <p>The plan is made once, when the retreat begins. Every valid walled
 * building within {@link #MAX_RUN_DIST} is scored by how far its door is, with a penalty
 * for a door the mob is nearer to than the defender is (it would meet the mob
 * there). The best few are tried in order, and the defender never stands still
 * for want of a plan:
 * <ol>
 *   <li><b>Village route</b> to the first building the nav graph can reach.</li>
 *   <li><b>Straight run</b> with vanilla navigation to the closest building
 *       when the graph cannot be used, opening the doors on its way -- a
 *       defender chasing a skeleton across open ground is often off the
 *       graph altogether. With {@link WalkFeel#retreatMap} (leftovers step 8)
 *       only when the village has no map: a villager too far from every map
 *       tile for a route to start steps straight onto the nearest one first
 *       (no drop over 3 blocks on the way) and plans again from there, and a
 *       building the map cannot reach is passed over like a failed route.</li>
 *   <li><b>Away</b> from the mob, re-aimed every second, when there is no
 *       building to run to.</li>
 * </ol>
 * A building whose route fails or stalls is struck off for this retreat and the
 * next is tried at once.
 *
 * <p>Once inside, the retreat is {@link Phase#HOLDING}: the owner decides what
 * the defender does there (a guard swings at what comes within reach, an archer
 * shoots it) but it does not walk out until the owner reports it fit again.
 */
public final class DefenderRetreat {

    /** What the retreat is doing this tick. */
    public enum Phase {
        /** On the way to a building (or running from the mob). */
        RUNNING,
        /** Inside a building, waiting to recover. */
        HOLDING,
        /** Over: the owner goes back to its normal behaviour. */
        DONE
    }

    private enum Mode { NONE, GRAPH, DIRECT, AWAY, JOIN }

    /**
     * Speed of a defender falling back hurt: about 4.2 blocks a second, the
     * pace the injured run can show with its feet on the ground (RunPace at its
     * fastest playback). It was 1.6x a walk, about 6.9 blocks a second, faster
     * than a sprinting player: a limp at that speed slid over the ground. Still
     * ahead of zombies, skeletons, creepers and spiders; not of pillagers.
     */
    public static final float SPEED = VillagerModel.DEFAULT_SPEED * 1.24F;

    /** Buildings farther than this are not worth the run. */
    private static final double MAX_RUN_DIST = 56.0;
    /** Candidates considered per planning pass. */
    private static final int MAX_CANDIDATES = 4;
    /** Score added per block a mob is nearer to a door than the defender. */
    private static final double BEATEN_TO_DOOR_PER_BLOCK = 1.5;
    /** Flat score added to a door the mob would reach first. */
    private static final double BEATEN_TO_DOOR_FLAT = 6.0;
    /** Waiting spot: at least this far from the door, so the door lies between defender and mob... */
    private static final double SPOT_MIN_DOOR_DIST_SQ = 2.0 * 2.0;
    /** ...but not so deep that the run takes long. */
    private static final double SPOT_MAX_DOOR_DIST_SQ = 6.0 * 6.0;
    /** A run that has not ended by now is over, hurt or not. */
    private static final int RUN_TIMEOUT_TICKS = 600;
    /** Longest stay in a building before the owner is handed back. */
    private static final int HOLD_TIMEOUT_TICKS = 3600;
    /** Ticks between planning tries when nothing could be planned. */
    private static final int REPLAN_TICKS = 40;
    /** How often a holding defender checks it has not been knocked out of the room. */
    private static final int HOLD_CHECK_TICKS = 10;
    /** A straight run counts as arrived this close to its goal. */
    private static final double DIRECT_ARRIVE_DIST_SQ = 2.0 * 2.0;
    /** Ticks between checks that a straight run is still getting somewhere. */
    private static final int PROGRESS_CHECK_TICKS = 20;
    /** Less than this far moved in a check counts as no progress. */
    private static final double PROGRESS_MIN_DIST_SQ = 0.6 * 0.6;
    /** Checks without progress before a straight run gives its building up. */
    private static final int PROGRESS_CHECKS_TO_GIVE_UP = 2;
    /** How far each leg of running away from the mob goes. */
    private static final int AWAY_LEG = 12;
    /** Ticks between re-aiming a run away from the mob. */
    private static final int AWAY_REAIM_TICKS = 20;
    /** Running away on the village map: legs tried, longest first, in blocks. */
    private static final int[] AWAY_MAP_LEGS = {12, 7};
    /** Directions tried, in degrees off straight away from the mob. */
    private static final double[] AWAY_MAP_ANGLES = {0.0, 35.0, -35.0, 70.0, -70.0, 105.0, -105.0};
    /** A map tile picked to run to is this many blocks farther from the mob than the villager, at least. */
    private static final double AWAY_MAP_MIN_GAIN = 3.0;
    /** Every this often on the way, a tile the mob is now nearer to than the villager is given up. */
    private static final int AWAY_MAP_CHECK_TICKS = 40;
    /** No tile to run to (cornered), or the walk failed: looked for again after this long. */
    private static final int AWAY_MAP_LOOK_AGAIN_TICKS = 20;
    /** Stepping onto the map ({@link WalkFeel#retreatMap}): map tiles looked for this far round, across... */
    private static final int JOIN_SEARCH_XZ = 8;
    /** ...and this far up or down... */
    private static final int JOIN_SEARCH_Y = 4;
    /** ...but none more than this far below the feet, and no deeper drop on the way (Minecraft's own fall limit). */
    private static final int JOIN_MAX_DROP = 3;
    /** Ground under the straight line onto the map is checked this often, in blocks. */
    private static final double JOIN_GROUND_STEP = 0.5;
    /** Map routes start from a tile within this of the feet (as {@code VillagerModel.canReach}). */
    private static final int ON_MAP_RADIUS = 3;
    /** Spots tried per building before it counts as one the map cannot reach. */
    private static final int MAP_SPOT_TRIES = 3;
    /** Far enough from the mob that running away is over and resting can begin. */
    private static final double AWAY_SAFE_DIST_SQ = 20.0 * 20.0;
    /** A mob this close to a resting defender that is not in a building starts the run again. */
    private static final double AWAY_RESUME_DIST_SQ = 12.0 * 12.0;
    /** A civilian hit while holding moves away from the shooter at most this often. */
    private static final int RELOCATE_COOLDOWN_TICKS = 40;
    /** A monster this close to a door, and nearer it than the civilian, makes the building not worth the run. */
    private static final double CONTESTED_DOOR_DIST = 4.0;
    /** How often a civilian on its way checks its building's door for monsters. */
    private static final int CONTESTED_CHECK_TICKS = 10;
    /** An attacker farther than this from a hiding civilian is shooting, not hitting. */
    private static final double RANGED_HIT_DIST = 4.0;
    /** Hits from afar within {@link #RANGED_HIT_WINDOW_TICKS} that make a hiding civilian leave its building. */
    private static final int RANGED_HITS_TO_LEAVE = 3;
    /** Ten seconds. */
    private static final int RANGED_HIT_WINDOW_TICKS = 200;
    /** A player this close to the door, and nearer it than the hiding civilian, stands in the way out. */
    private static final double PLAYER_DOORWAY_DIST = 3.0;
    /** A player who hit a civilian and stands this close to the door of the building it is in makes it leave. */
    private static final double PLAYER_NEAR_DOOR_DIST = 6.0;
    /** Buildings a player chases a civilian out of before it gives up on buildings and runs into the open. */
    private static final int PLAYER_CHASES_TO_OPEN = 2;

    /** How fast this retreat runs: {@link #SPEED} for a hurt defender. */
    private final float speed;
    /** The running clip on the way. */
    private final HurryGait gait;
    /**
     * A villager who is not a defender: one already inside a building stays
     * in it, and a building whose door the monster reaches first is given up
     * on the way (see {@link #forCivilian}).
     */
    private final boolean civilian;

    private boolean active;
    private boolean holding;
    private Mode mode = Mode.NONE;
    @Nullable private LivingEntity threat;
    @Nullable private Vec3 threatAt;
    @Nullable private Building shelter;
    @Nullable private MoveActivity move;
    @Nullable private Vec3 goal;
    @Nullable private Vec3 lastProgressPos;
    private int progressMisses;
    private final List<Building> failed = new ArrayList<>();
    private int runTicks;
    private int holdTicks;
    private int replanIn;
    private int legTicks;
    /** Running away on the map with no walk under way: ticks until a tile is looked for again. */
    private int awayLookIn;
    /** Game time a hit last moved a holding civilian to another spot in its room. */
    private long lastRelocateAt = Long.MIN_VALUE;
    /** The walk under way is {@link #moveWithin}: one that fails just holds again. */
    private boolean movingWithin;
    /**
     * Doors on a straight run or a run away (vanilla navigation, off the
     * village route, which opens its own): opened on the way, shut behind.
     */
    private final RouteDoorHandler straightDoors = new RouteDoorHandler();
    private static final java.util.function.Predicate<BlockPos> NO_ROUTE_DOORS = pos -> false;
    /** Game times of recent hits from afar while hiding in the current building. */
    private final java.util.ArrayDeque<Long> rangedHits = new java.util.ArrayDeque<>();
    /**
     * A player in the way out hit this civilian in the current building and it backed into
     * the far corner: the next player hit there sends it running past.
     */
    private boolean backedFromPlayer;
    /** Buildings this civilian left this retreat because {@link #chasedBy} hit it there or stood at them. */
    private int playerChases;
    /** The player those chases were by; a different player starts the count again. */
    @Nullable private java.util.UUID chasedBy;

    /** A hurt defender's retreat: the injured run at {@link #SPEED}. */
    public DefenderRetreat() {
        this(SPEED, HurryGait.INJURED_RUN, false);
    }

    private DefenderRetreat(float speed, HurryGait gait, boolean civilian) {
        this.speed = speed;
        this.gait = gait;
        this.civilian = civilian;
    }

    /**
     * The same run for a villager who is not a defender, scared by a monster:
     * a full run at {@code speed}. One already inside a building stays there
     * (a few steps in from the door), and on the way a building whose door the
     * monster is about to reach first is given up for the next.
     */
    public static DefenderRetreat forCivilian(float speed) {
        return new DefenderRetreat(speed, HurryGait.RUN, true);
    }

    public boolean isActive() {
        return active;
    }

    /** On the way: active and not yet resting. */
    public boolean isRunning() {
        return active && !holding;
    }

    public boolean isHolding() {
        return active && holding;
    }

    /** The building being fled to or held in; null while there is none. */
    @Nullable
    public Building shelter() {
        return shelter;
    }

    /** The door of the building being fled to, for the log; null while there is none. */
    @Nullable
    public BlockPos shelterDoor() {
        return shelter != null ? shelter.getDoorPos() : null;
    }

    /** Starts a retreat from {@code from} (the mob being fled, if known). */
    public void begin(Activity.Context ctx, @Nullable LivingEntity from) {
        cancel(ctx);
        // Its team gets one more while it is gone (GuardAlertSystem teams).
        GuardAlertSystem.noteRetreat(ctx, from);
        active = true;
        holding = false;
        threat = from;
        threatAt = from != null ? from.position() : null;
        runTicks = 0;
        holdTicks = 0;
        replanIn = 0;
        failed.clear();
        replan(ctx);
    }

    /**
     * Advances the retreat one tick. {@code readyToReturn} is the owner's say
     * that the defender is fit to go back to work; it ends a stay (a run is
     * never cut short by it).
     */
    public Phase tick(Activity.Context ctx, boolean readyToReturn) {
        if (!active) return Phase.DONE;
        NpcEntity npc = ctx.npc();
        if (threat != null && !threat.isAlive()) threat = null;
        if (threat != null) threatAt = threat.position();

        if (holding) {
            if (readyToReturn || ++holdTicks > HOLD_TIMEOUT_TICKS) {
                cancel(ctx);
                return Phase.DONE;
            }
            if (shelter != null) {
                // Knocked back out through the door (or the building went): start over.
                if (!shelter.isValid()
                        || (Math.floorMod(holdTicks, HOLD_CHECK_TICKS) == 0
                        && !shelter.isInRoom(npc.blockPosition()))) {
                    resumeRun(ctx);
                }
            } else if (threat != null && Math.floorMod(holdTicks, HOLD_CHECK_TICKS) == 0
                    && npc.distanceToSqr(threat) < AWAY_RESUME_DIST_SQ) {
                // Rested in the open and the mob has caught up: run again.
                resumeRun(ctx);
            }
            if (holding) npc.stopHurry();
            return holding ? Phase.HOLDING : Phase.RUNNING;
        }

        if (++runTicks > RUN_TIMEOUT_TICKS) {
            cancel(ctx);
            return Phase.DONE;
        }
        // A civilian on its way gives up a door a monster is about to reach first.
        if (civilian && shelter != null && !movingWithin && runTicks % CONTESTED_CHECK_TICKS == 0
                && doorContested(ctx, npc, shelter)) {
            giveUpShelter(ctx);
        }

        switch (mode) {
            case GRAPH -> tickGraph(ctx);
            case DIRECT -> tickDirect(ctx);
            case AWAY -> tickAway(ctx);
            case JOIN -> tickJoin(ctx);
            case NONE -> {
                if (--replanIn <= 0) replan(ctx);
            }
        }
        // The run on the way (the injured run for a hurt defender), standing once inside.
        if (holding) npc.stopHurry();
        else npc.hurry(gait);
        return holding ? Phase.HOLDING : Phase.RUNNING;
    }

    /**
     * Hit again, or a new monster to flee. On the way, a civilian whose
     * building's door {@code from} is about to reach first gives that building
     * up for the next. Holding in a building, a civilian hit by a monster that
     * is not inside (a skeleton shooting through the door) moves to the floor
     * tile farthest from it, at most once every two seconds; shot from more
     * than {@link #RANGED_HIT_DIST} blocks {@link #RANGED_HITS_TO_LEAVE} times
     * in ten seconds, it gives the building up and runs for another. Hit by a
     * player in there, see {@link #leavePlayer}.
     */
    public void retarget(Activity.Context ctx, LivingEntity from) {
        if (!active) return;
        threat = from;
        threatAt = from.position();
        if (!civilian) return;
        if (!holding && !movingWithin) {
            if (shelter != null && mobReachesDoorFirst(ctx.npc(), shelter)) giveUpShelter(ctx);
            return;
        }
        // The monster watch never counts a player as inside, so a player's hit in
        // here was skipped below and the villager stood taking them.
        if (WalkFeel.playerFlee && from instanceof Player player
                && shelter != null && shelter.isValid()
                && (ShelterWatch.isOnFloor(shelter, from.blockPosition())
                        || from.distanceTo(ctx.npc()) <= RANGED_HIT_DIST)) {
            leavePlayer(ctx, player);
            return;
        }
        if (shelter == null || !shelter.isValid()
                || ShelterWatch.isOnFloor(shelter, from.blockPosition())) return;
        long now = ctx.level().getGameTime();
        // Shot at from outside (a skeleton through a window or the open door):
        // a few hits in a short while and this building is no good, try another.
        // A monster hitting from the doorway is close, not ranged: moving away
        // from it is better than running out past it.
        if (from.distanceTo(ctx.npc()) > RANGED_HIT_DIST && countRangedHit(now) >= RANGED_HITS_TO_LEAVE) {
            giveUpShelter(ctx);
            return;
        }
        if (!holding) return;
        if (now - lastRelocateAt < RELOCATE_COOLDOWN_TICKS) return;
        lastRelocateAt = now;
        BlockPos far = farthestTileFrom(shelter, from.position());
        if (far != null && !far.equals(ctx.npc().blockPosition())) moveWithin(ctx, far);
    }

    /** Notes a hit from afar at {@code now}; returns how many fell within {@link #RANGED_HIT_WINDOW_TICKS}. */
    private int countRangedHit(long now) {
        rangedHits.addLast(now);
        while (!rangedHits.isEmpty() && now - rangedHits.peekFirst() > RANGED_HIT_WINDOW_TICKS) {
            rangedHits.removeFirst();
        }
        return rangedHits.size();
    }

    /**
     * Hit by {@code player} in the building held (on its floor, or close from
     * the doorway): it is no hiding place from a player, so it is given up for
     * another building, or a run away when there is none. A player standing in
     * the way out sends it to the far corner first, once; the next hit there
     * runs past. The second building left this way ends the buildings (see
     * {@link #countPlayerChase}).
     */
    private void leavePlayer(Activity.Context ctx, Player player) {
        NpcEntity npc = ctx.npc();
        String door = shelter.getDoorPos() != null ? shelter.getDoorPos().toShortString() : "?";
        if (!backedFromPlayer && playerInDoorway(npc, shelter, player)) {
            backedFromPlayer = true;
            BlockPos far = farthestTileFrom(shelter, player.position());
            if (far != null && !far.equals(npc.blockPosition())) {
                lastRelocateAt = ctx.level().getGameTime();
                noteDiary(ctx, "[PlayerFlee] hit by " + player.getName().getString() + " in the doorway of the "
                        + shelter.getDisplayName() + " at " + door + ": far corner " + far.toShortString());
                moveWithin(ctx, far);
                return;
            }
        }
        noteDiary(ctx, "[PlayerFlee] hit by " + player.getName().getString() + " in the "
                + shelter.getDisplayName() + " at " + door + ": leaving it");
        countPlayerChase(ctx, player);
        giveUpShelter(ctx);
    }

    /**
     * Player hit, step 4: one more building left because of {@code player}. At
     * {@link #PLAYER_CHASES_TO_OPEN} (the player followed it into the next one)
     * the rest of this retreat runs over open ground, away from that player.
     */
    private void countPlayerChase(Activity.Context ctx, Player player) {
        if (!player.getUUID().equals(chasedBy)) {
            chasedBy = player.getUUID();
            playerChases = 0;
        }
        if (++playerChases == PLAYER_CHASES_TO_OPEN) {
            noteDiary(ctx, "[PlayerFlee] chased out of a second building by " + player.getName().getString()
                    + ": running into the open");
        }
    }

    /** Whether this retreat runs over open ground only: chased out of buildings by the player it is running from. */
    private boolean openGroundFromPlayer() {
        return WalkFeel.playerFlee && playerChases >= PLAYER_CHASES_TO_OPEN
                && threat instanceof Player player && player.isAlive() && player.getUUID().equals(chasedBy);
    }

    /** Whether {@code player} stands within {@link #PLAYER_DOORWAY_DIST} of the building's door and nearer it than the villager. */
    private static boolean playerInDoorway(NpcEntity npc, Building building, LivingEntity player) {
        if (building.getDoorPos() == null) return false;
        Vec3 door = Vec3.atCenterOf(building.getDoorPos());
        double playerToDoor = player.position().distanceTo(door);
        return playerToDoor <= PLAYER_DOORWAY_DIST && playerToDoor < npc.position().distanceTo(door);
    }

    private static void noteDiary(Activity.Context ctx, String line) {
        ctx.brain().diary().log(com.alex.test.village.brain.VillagerDiary.ts(ctx.level()) + " " + line);
    }

    /**
     * A monster got into the building held or run to: strike it off for this
     * retreat and run for the next at once, away from {@code from} when known.
     */
    public void abandonShelter(Activity.Context ctx, @Nullable LivingEntity from) {
        if (!active) return;
        if (from != null && from.isAlive()) {
            threat = from;
            threatAt = from.position();
        }
        if (shelter != null) failed.add(shelter);
        shelter = null;
        rangedHits.clear();
        backedFromPlayer = false;
        resumeRun(ctx);
    }

    /**
     * Starts a retreat already inside {@code building}, holding there: a
     * villager waiting in a corner for a monster to clear the doorway before it
     * runs (it then calls {@link #abandonShelter}).
     */
    public void beginHoldingIn(Activity.Context ctx, Building building, @Nullable LivingEntity from) {
        cancel(ctx);
        active = true;
        holding = true;
        shelter = building;
        threat = from;
        threatAt = from != null ? from.position() : null;
        runTicks = 0;
        holdTicks = 0;
        replanIn = 0;
        ctx.npc().getNavigation().stop();
    }

    /**
     * Walks to {@code spot} in the building already held, at the run, and holds
     * there again on arrival. Used to keep away from a monster in the doorway.
     */
    public void moveWithin(Activity.Context ctx, BlockPos spot) {
        if (!active || shelter == null) return;
        stopMovers(ctx);
        holding = false;
        runTicks = 0;
        move = new MoveActivity(spot, speed);
        mode = Mode.GRAPH;
        movingWithin = true;
    }

    /** The building's floor tile farthest from {@code from}, kept off the door tile; null with no floor. */
    @Nullable
    public static BlockPos farthestTileFrom(Building building, Vec3 from) {
        BlockPos door = building.getDoorPos();
        BlockPos best = null;
        double bestDist = -1.0;
        for (BlockPos tile : building.getNavMeshBlocks()) {
            if (door != null && tile.distSqr(door) < 1.5) continue;
            double d = Vec3.atBottomCenterOf(tile).distanceToSqr(from);
            if (d > bestDist) {
                bestDist = d;
                best = tile;
            }
        }
        return best;
    }

    /**
     * Whether the monster is within {@link #CONTESTED_DOOR_DIST} of the
     * building's door and nearer to it than the villager: running on would
     * meet it there.
     */
    private boolean mobReachesDoorFirst(NpcEntity npc, Building building) {
        if (threatAt == null || building.getDoorPos() == null) return false;
        // Already through the door: the monster outside is behind it.
        if (ShelterWatch.isOnFloor(building, npc.blockPosition())) return false;
        Vec3 door = Vec3.atCenterOf(building.getDoorPos());
        double mobToDoor = threatAt.distanceTo(door);
        return mobToDoor <= CONTESTED_DOOR_DIST && mobToDoor < npc.position().distanceTo(door);
    }

    /**
     * Whether any monster, not only the one fled, is within
     * {@link #CONTESTED_DOOR_DIST} of the building's door and nearer to it than
     * the civilian, who is not yet inside. Checked on the way every half second.
     */
    private static boolean doorContested(Activity.Context ctx, NpcEntity npc, Building building) {
        BlockPos doorPos = building.getDoorPos();
        if (doorPos == null || ShelterWatch.isOnFloor(building, npc.blockPosition())) return false;
        Vec3 door = Vec3.atCenterOf(doorPos);
        double npcToDoor = npc.position().distanceTo(door);
        for (net.minecraft.world.entity.Mob mob : ctx.level().getEntitiesOfClass(
                net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(doorPos).inflate(CONTESTED_DOOR_DIST),
                mob -> mob.isAlive()
                        && com.alex.test.village.villagers.guard.alert.GuardThreatRules.isHostileMonster(ctx.mgr(), mob))) {
            double mobToDoor = mob.position().distanceTo(door);
            if (mobToDoor <= CONTESTED_DOOR_DIST && mobToDoor < npcToDoor) return true;
        }
        return false;
    }

    /** Drops the retreat and whatever movement it started. */
    public void cancel(Activity.Context ctx) {
        if (active) ctx.npc().stopHurry();
        stopMovers(ctx);
        shelter = null;
        threat = null;
        threatAt = null;
        active = false;
        holding = false;
        mode = Mode.NONE;
        failed.clear();
        rangedHits.clear();
        backedFromPlayer = false;
        playerChases = 0;
        chasedBy = null;
    }

    // -------------------------------------------------------------------------
    // Running
    // -------------------------------------------------------------------------

    private void resumeRun(Activity.Context ctx) {
        holding = false;
        runTicks = 0;
        replanIn = 0;
        replan(ctx);
    }

    private void tickGraph(Activity.Context ctx) {
        if (move == null) {
            giveUpShelter(ctx);
            return;
        }
        Activity.Status status = move.tickOuter(ctx);
        if (status == Activity.Status.RUNNING) return;
        move.stop(ctx);
        move = null;
        boolean within = movingWithin;
        movingWithin = false;
        if (status == Activity.Status.DONE) {
            holding = true;
            holdTicks = 0;
            return;
        }
        if (within) {
            // Could not get to the far side of the room: hold where it stands.
            holding = true;
            return;
        }
        // The route failed: this building is out for this retreat, try the next.
        giveUpShelter(ctx);
    }

    private void tickDirect(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        if (goal == null) {
            giveUpShelter(ctx);
            return;
        }
        // Vanilla navigation walks into a closed door; open the ones on its path.
        straightDoors.tick(npc, NO_ROUTE_DOORS);
        if (npc.position().distanceToSqr(goal) <= DIRECT_ARRIVE_DIST_SQ
                || (shelter != null && shelter.isInRoom(npc.blockPosition())
                && npc.position().distanceToSqr(goal) <= SPOT_MAX_DOOR_DIST_SQ)) {
            npc.getNavigation().stop();
            straightDoors.closeAllOpenedDoors(npc);
            holding = true;
            holdTicks = 0;
            return;
        }
        if (npc.getNavigation().isDone()) {
            VanillaNavCount.note(VanillaNavCount.Site.RETREAT_STRAIGHT, npc);
            npc.getNavigation().moveTo(goal.x, goal.y, goal.z, speed);
        }
        if (++legTicks % PROGRESS_CHECK_TICKS != 0) return;
        if (lastProgressPos != null
                && npc.position().distanceToSqr(lastProgressPos) < PROGRESS_MIN_DIST_SQ) {
            if (++progressMisses >= PROGRESS_CHECKS_TO_GIVE_UP) {
                giveUpShelter(ctx);
                return;
            }
        } else {
            progressMisses = 0;
        }
        lastProgressPos = npc.position();
    }

    private void tickAway(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        if (threat == null || npc.distanceToSqr(threat) > AWAY_SAFE_DIST_SQ) {
            // Clear of it (or it is gone): rest where we are.
            if (move != null) {
                move.stop(ctx);
                move = null;
            }
            npc.getNavigation().stop();
            straightDoors.closeAllOpenedDoors(npc);
            holding = true;
            holdTicks = 0;
            return;
        }
        NavGraph graph = ctx.mgr().getGraph();
        if (WalkFeel.fleeMap && graph != null && !graph.isEmpty()) {
            tickAwayOnMap(ctx, npc, graph, threat);
            return;
        }
        straightDoors.tick(npc, NO_ROUTE_DOORS);
        if (--legTicks > 0 && !npc.getNavigation().isDone()) return;
        legTicks = AWAY_REAIM_TICKS;
        Vec3 away = awayPoint(ctx, npc, threat);
        if (away != null) {
            VanillaNavCount.note(VanillaNavCount.Site.RETREAT_AWAY, npc);
            npc.getNavigation().moveTo(away.x, away.y, away.z, speed);
        }
    }

    /**
     * Leftovers step 6 ({@link WalkFeel#fleeMap}): running away is a village-map walk
     * to a tile well away from the mob, through gates and doors the route opens, and
     * never past the border (where the model would put the villager back on the map).
     * On arrival the next tile is picked at once; on the way, a tile the mob has got
     * nearer to than the villager is given up for another, if there is one. With no
     * tile to run to (cornered) the villager stands and looks again every second.
     */
    private void tickAwayOnMap(Activity.Context ctx, NpcEntity npc, NavGraph graph, LivingEntity from) {
        if (move != null) {
            Activity.Status status = move.tickOuter(ctx);
            if (status == Activity.Status.RUNNING) {
                if (++legTicks % AWAY_MAP_CHECK_TICKS != 0 || goal == null) return;
                if (from.position().distanceToSqr(goal) >= npc.position().distanceToSqr(goal)) return;
                // The mob is nearer the tile than the villager now: another tile, if there is one.
            } else {
                // Arrived (the next tile at once) or no way there (looked for again in a second).
                move = null;
                goal = null;
                if (status == Activity.Status.FAILED) awayLookIn = AWAY_MAP_LOOK_AGAIN_TICKS;
            }
        }
        if (move == null && --awayLookIn > 0) return;
        BlockPos tile = awayTile(ctx, npc, graph, from);
        if (move != null && (tile == null || tile.equals(BlockPos.containing(goal)))) {
            return; // nothing better: keep running (checked again in 2 s)
        }
        if (tile == null) {
            awayLookIn = AWAY_MAP_LOOK_AGAIN_TICKS;
            return;
        }
        // A new walk replaces the old one (the model drops the old route for it), asked
        // for at once so the run does not stand still for a tick in between.
        move = new MoveActivity(tile, speed);
        goal = Vec3.atBottomCenterOf(tile);
        legTicks = 0;
        move.tickOuter(ctx);
    }

    /**
     * A map tile to run to: about {@link #AWAY_MAP_LEGS} blocks off, straight away
     * from {@code from} first, then more and more to the side; at least
     * {@link #AWAY_MAP_MIN_GAIN} blocks farther from it than the villager is, and one
     * the villager's part of the map reaches. Null when there is none (cornered).
     */
    @Nullable
    private static BlockPos awayTile(Activity.Context ctx, NpcEntity npc, NavGraph graph, LivingEntity from) {
        Vec3 dir = new Vec3(npc.getX() - from.getX(), 0.0D, npc.getZ() - from.getZ());
        if (dir.lengthSqr() < 1.0e-4) {
            Vec3 look = npc.getViewVector(1.0F);
            dir = new Vec3(look.x, 0.0D, look.z);
            if (dir.lengthSqr() < 1.0e-4) dir = new Vec3(1.0D, 0.0D, 0.0D);
        }
        dir = dir.normalize();
        double here = Math.sqrt(horizontalDistSq(npc.getX(), npc.getZ(), from.getX(), from.getZ()));
        double minDist = here + AWAY_MAP_MIN_GAIN;
        int feetY = NavGraph.feetCell(npc).getY();
        for (int leg : AWAY_MAP_LEGS) {
            for (double angle : AWAY_MAP_ANGLES) {
                Vec3 d = dir.yRot((float) Math.toRadians(angle));
                BlockPos aim = BlockPos.containing(npc.getX() + d.x * leg, feetY, npc.getZ() + d.z * leg);
                NavNode node = graph.nearestRoutable(aim, 3, 4);
                if (node == null) continue;
                BlockPos tile = node.feetPos();
                if (!graph.isInsideBounds(tile)) continue;
                if (horizontalDistSq(tile.getX() + 0.5D, tile.getZ() + 0.5D, from.getX(), from.getZ())
                        < minDist * minDist) continue;
                if (!ctx.model().canReach(graph, tile)) continue;
                return tile;
            }
        }
        return null;
    }

    private static double horizontalDistSq(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    /** A point {@link #AWAY_LEG} blocks off, away from {@code from}, kept inside the village when it can be. */
    @Nullable
    private static Vec3 awayPoint(Activity.Context ctx, NpcEntity npc, LivingEntity from) {
        NavGraph graph = ctx.mgr().getGraph();
        Vec3 spot = DefaultRandomPos.getPosAway(npc, AWAY_LEG, 4, from.position());
        if (spot != null && (graph == null || graph.isInsideBounds(BlockPos.containing(spot)))) return spot;
        Vec3 dir = new Vec3(npc.getX() - from.getX(), 0.0D, npc.getZ() - from.getZ());
        if (dir.lengthSqr() < 1.0e-4) dir = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 straight = npc.position().add(dir.normalize().scale(AWAY_LEG));
        return straight;
    }

    /** The current building can't be reached: strike it off and plan again at once. */
    private void giveUpShelter(Activity.Context ctx) {
        if (shelter != null) failed.add(shelter);
        rangedHits.clear();
        backedFromPlayer = false;
        stopMovers(ctx);
        shelter = null;
        replanIn = 0;
        replan(ctx);
    }

    private void stopMovers(Activity.Context ctx) {
        if (move != null) move.stop(ctx);
        straightDoors.closeAllOpenedDoors(ctx.npc());
        move = null;
        goal = null;
        lastProgressPos = null;
        movingWithin = false;
        progressMisses = 0;
        legTicks = 0;
        awayLookIn = 0;
        if (mode == Mode.DIRECT || mode == Mode.AWAY) ctx.npc().getNavigation().stop();
        mode = Mode.NONE;
    }

    // -------------------------------------------------------------------------
    // Planning
    // -------------------------------------------------------------------------

    private record Scored(Building building, double score) {}

    private void replan(Activity.Context ctx) {
        replanIn = REPLAN_TICKS;
        stopMovers(ctx);
        if (planShelter(ctx)) return;
        // No building to run to: at least get away from the mob.
        if (threat != null) {
            mode = Mode.AWAY;
            legTicks = 0;
        }
    }

    private boolean planShelter(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        NavGraph graph = ctx.mgr().getGraph();
        long now = ctx.level().getGameTime();
        if (civilian) strikeOffPlayerBuilding(ctx, npc);
        // Chased out of a second building by the same player: no more buildings, the run goes away from it.
        if (civilian && openGroundFromPlayer()) return false;
        if (civilian && planStayInside(ctx, npc, graph, now)) return true;
        // Leftovers step 8: with a map, every run is a map route, never a straight Minecraft one.
        boolean mapOnly = WalkFeel.retreatMap && graph != null && !graph.isEmpty();
        if (mapOnly && graph.nearestOnFloor(NavGraph.feetCell(npc), ON_MAP_RADIUS, ON_MAP_RADIUS) == null) {
            // Too far from every map tile for a route to start: onto the map, then plan again.
            return startJoin(ctx, npc, graph);
        }

        List<Scored> ranked = new ArrayList<>();
        // Villagers hiding spread out over the buildings instead of all piling into the nearest.
        java.util.Map<Building, Integer> sheltering = civilian
                ? com.alex.test.village.brain.ShelterCrowd.sheltering(ctx.mgr(), ctx.model())
                : java.util.Map.of();
        for (Building building : ctx.mgr().getBuildings()) {
            if (building == null || !building.isValid() || failed.contains(building)) continue;
            if (!isShelter(building)) continue;
            // A monster got into it lately: the whole village keeps out.
            if (ShelterWatch.isOverrun(ctx.mgr(), building, now)) continue;
            BlockPos door = building.getDoorPos();
            if (door == null || building.getNavMeshBlocks().isEmpty()) continue;
            Vec3 doorCentre = Vec3.atCenterOf(door);
            double dist = npc.position().distanceTo(doorCentre);
            if (dist > MAX_RUN_DIST) continue;
            double score = dist;
            if (threatAt != null) {
                double mobToDoor = threatAt.distanceTo(doorCentre);
                if (mobToDoor < dist) {
                    score += (dist - mobToDoor) * BEATEN_TO_DOOR_PER_BLOCK + BEATEN_TO_DOOR_FLAT;
                }
            }
            if (civilian) score += com.alex.test.village.brain.ShelterCrowd.penalty(ctx.mgr(), building, sheltering);
            ranked.add(new Scored(building, score));
        }
        ranked.sort(Comparator.comparingDouble(Scored::score));

        Building straight = null;
        BlockPos straightSpot = null;
        int checked = 0;
        for (Scored candidate : ranked) {
            if (checked++ >= MAX_CANDIDATES) break;
            // One with a monster inside right now is marked overrun by the look, and skipped.
            if (ShelterWatch.look(ctx.mgr(), ctx.level(), candidate.building()).inside() != null) continue;
            // With mapOnly a building no spot of which the map reaches (another map island,
            // or every spot tried ruled out by a failed walk) is passed over.
            BlockPos inside = mapOnly ? reachableSpot(ctx, npc, graph, candidate.building())
                    : pickSpot(npc, candidate.building());
            if (inside == null) continue;
            if (graph != null && ctx.model().canReach(graph, inside)) {
                shelter = candidate.building();
                move = new MoveActivity(inside, speed);
                mode = Mode.GRAPH;
                return true;
            }
            if (straight == null) {
                straight = candidate.building();
                straightSpot = inside;
            }
        }
        if (straight != null && !mapOnly) {
            // The graph has no route (the defender may be off it): head straight there.
            shelter = straight;
            goal = Vec3.atBottomCenterOf(straightSpot);
            mode = Mode.DIRECT;
            legTicks = 0;
            lastProgressPos = null;
            progressMisses = 0;
            VanillaNavCount.note(VanillaNavCount.Site.RETREAT_STRAIGHT, npc);
            npc.getNavigation().moveTo(goal.x, goal.y, goal.z, speed);
            return true;
        }
        return false;
    }

    /**
     * Only a walled room is somewhere to recover. A fenced pen, a mine face, a tower top or
     * an open-air stall is open ground: the mob just follows the defender in.
     */
    private static boolean isShelter(Building building) {
        return ShelterWatch.isShelter(building);
    }

    /**
     * Player hit, step 2: fleeing a player who is in the building this civilian
     * stands in (on its floor, or within {@link #PLAYER_NEAR_DOOR_DIST} of its
     * door), that building is struck off for this retreat, so it neither stays
     * put there nor runs back into it. Found by the loose room box too: a
     * villager woken in bed is not on a floor tile.
     */
    private void strikeOffPlayerBuilding(Activity.Context ctx, NpcEntity npc) {
        if (!WalkFeel.playerFlee || !(threat instanceof Player player) || !player.isAlive()) return;
        Building here = ShelterWatch.shelterAt(ctx.mgr(), npc.blockPosition(), true);
        if (here == null || failed.contains(here)) return;
        BlockPos doorPos = here.getDoorPos();
        boolean playerThere = ShelterWatch.isOnFloor(here, player.blockPosition())
                || doorPos != null && player.position().distanceTo(Vec3.atCenterOf(doorPos)) <= PLAYER_NEAR_DOOR_DIST;
        if (!playerThere) return;
        failed.add(here);
        noteDiary(ctx, "[PlayerFlee] " + player.getName().getString() + " is at the " + here.getDisplayName()
                + " at " + (doorPos != null ? doorPos.toShortString() : "?") + ": not hiding in it");
        countPlayerChase(ctx, player);
    }

    /**
     * A civilian already standing on the floor of a building with no monster
     * in it stays there: where it stands if that is away from the door, else a
     * few steps further in. Running out to another house would only take it
     * past the monster.
     */
    private boolean planStayInside(Activity.Context ctx, NpcEntity npc, @Nullable NavGraph graph, long now) {
        Building here = ShelterWatch.shelterAt(ctx.mgr(), npc.blockPosition(), false);
        if (here == null || failed.contains(here) || here.getDoorPos() == null
                || ShelterWatch.isOverrun(ctx.mgr(), here, now)
                || ShelterWatch.look(ctx.mgr(), ctx.level(), here).inside() != null) return false;
        if (npc.blockPosition().distSqr(here.getDoorPos()) >= SPOT_MIN_DOOR_DIST_SQ) {
            shelter = here;
            holding = true;
            holdTicks = 0;
            npc.getNavigation().stop();
            return true;
        }
        BlockPos inside = pickSpot(npc, here);
        if (inside == null || graph == null || !ctx.model().canReach(graph, inside)) return false;
        shelter = here;
        move = new MoveActivity(inside, speed);
        mode = Mode.GRAPH;
        return true;
    }

    /** A spot in {@code building} the map reaches from here, up to {@link #MAP_SPOT_TRIES} picks; null if none. */
    @Nullable
    private static BlockPos reachableSpot(Activity.Context ctx, NpcEntity npc, NavGraph graph, Building building) {
        for (int i = 0; i < MAP_SPOT_TRIES; i++) {
            BlockPos spot = pickSpot(npc, building);
            if (spot == null) return null;
            if (ctx.model().canReach(graph, spot)) return spot;
        }
        return null;
    }

    /**
     * Steps straight at the nearest map tile within {@link #JOIN_SEARCH_XZ} blocks, none more
     * than {@link #JOIN_MAX_DROP} below and with ground under the whole line; false when there is
     * none (stranded: on a roof, say), and the run plans as if there were no building.
     */
    private boolean startJoin(Activity.Context ctx, NpcEntity npc, NavGraph graph) {
        BlockPos feet = NavGraph.feetCell(npc);
        NavNode node = graph.nearestRoutable(feet, JOIN_SEARCH_XZ, JOIN_SEARCH_Y,
                n -> n.feetY >= feet.getY() - JOIN_MAX_DROP && graph.isInsideBounds(n.feetPos()));
        if (node == null) return false;
        Vec3 target = Vec3.atBottomCenterOf(node.feetPos());
        if (!groundAllTheWay(ctx.level(), npc, target)) return false;
        goal = target;
        mode = Mode.JOIN;
        legTicks = 0;
        lastProgressPos = null;
        progressMisses = 0;
        return true;
    }

    /**
     * On the way onto the map: plans the run as soon as a map route can start (within
     * {@link #ON_MAP_RADIUS} of a tile), short of the tile itself. Getting nowhere for two
     * checks in a row ends it; the run is planned again in {@link #REPLAN_TICKS}.
     */
    private void tickJoin(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        NavGraph graph = ctx.mgr().getGraph();
        if (goal == null || graph == null || graph.isEmpty()) {
            stopMovers(ctx);
            return;
        }
        if (graph.nearestOnFloor(NavGraph.feetCell(npc), ON_MAP_RADIUS, ON_MAP_RADIUS) != null) {
            replan(ctx);
            return;
        }
        // Own height as the wanted height, as the route follower steers.
        npc.getMoveControl().setWantedPosition(goal.x, npc.getY(), goal.z, speed);
        if (++legTicks % PROGRESS_CHECK_TICKS != 0) return;
        if (lastProgressPos != null
                && npc.position().distanceToSqr(lastProgressPos) < PROGRESS_MIN_DIST_SQ) {
            if (++progressMisses >= PROGRESS_CHECKS_TO_GIVE_UP) {
                stopMovers(ctx);
                replanIn = REPLAN_TICKS;
                return;
            }
        } else {
            progressMisses = 0;
        }
        lastProgressPos = npc.position();
    }

    /** Something to stand on within {@link #JOIN_MAX_DROP} blocks below the feet all along the straight line to {@code to}. */
    private static boolean groundAllTheWay(Level level, NpcEntity npc, Vec3 to) {
        double dx = to.x - npc.getX();
        double dz = to.z - npc.getZ();
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / JOIN_GROUND_STEP));
        int under = Mth.floor(npc.getY() + 0.01) - 1;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= steps; i++) {
            int x = Mth.floor(npc.getX() + dx * i / steps);
            int z = Mth.floor(npc.getZ() + dz * i / steps);
            boolean ground = false;
            for (int y = under; y >= under - JOIN_MAX_DROP && !ground; y--) {
                probe.set(x, y, z);
                ground = !level.getBlockState(probe).getCollisionShape(level, probe).isEmpty();
            }
            if (!ground) return false;
        }
        return true;
    }

    /** A floor tile a few blocks in from the building's door, or null. */
    @Nullable
    private static BlockPos pickSpot(NpcEntity npc, Building building) {
        BlockPos door = building.getDoorPos();
        List<BlockPos> near = new ArrayList<>();
        List<BlockPos> beyond = new ArrayList<>();
        for (BlockPos tile : building.getNavMeshBlocks()) {
            double d = tile.distSqr(door);
            if (d < SPOT_MIN_DOOR_DIST_SQ) continue;
            (d <= SPOT_MAX_DOOR_DIST_SQ ? near : beyond).add(tile);
        }
        List<BlockPos> pool = !near.isEmpty() ? near : beyond;
        if (pool.isEmpty()) {
            // A one-tile room: the door's own tile is all there is.
            return building.getNavMeshBlocks().iterator().next();
        }
        return pool.get(npc.getRandom().nextInt(pool.size()));
    }
}
