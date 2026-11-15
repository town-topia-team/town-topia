package com.alex.test.village.brain;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.VillageManager.SavedBedAssignment;
import com.alex.test.village.social.MemoryTopic;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Night shelter. Villagers with an assigned bed go to it and sleep; homeless
 * villagers walk to a tile outside a building ({@link WallSleepSpots}), sit
 * down with their back to its wall, sleep, and get up when morning arrives,
 * rain or not; never on the Town Hall floor (10-23). Villagers with neither
 * wander the village, unrested, until a bed or wall spot frees up. The Mayor
 * sits in its own hall instead, and waits there when no spot is free.
 */
public final class GoHomeActivity extends Activity {

    /**
     * Ticks the sitdown / situp one-shot animations play before transitioning.
     *
     * <p>Both must outlast the clip they are timing, or the pose snaps. The
     * authored lengths on the base_villager rig are 1 second for {@code sitdown}
     * and <b>2 seconds for {@code situp}</b> — the stand-up used to be cut off
     * fifteen ticks early, which put the villager back in its idle pose halfway
     * out of the chair.
     */
    private static final int SITDOWN_TICKS      = 25;
    private static final int SITUP_TICKS        = 45;
    /**
     * The same for the back-to-the-wall chain (tools/wallsleep): {@code wall_sitdown}
     * is 44 ticks and {@code wall_situp} 48, each after NpcEntity's 12-tick settle
     * blend, plus 2. Both clips hold their last frame, so waiting longer is harmless;
     * cut short, the eyes would still be closing, or he would stop half way up.
     */
    private static final int WALL_SITDOWN_TICKS = 58;
    private static final int WALL_SITUP_TICKS   = 62;
    /** How often a villager asleep outside checks that the wall it leans on is still there. */
    private static final int WALL_CHECK_TICKS   = 40;
    /** How often to re-send the looping sleep animation so GeckoLib keeps it running. */
    private static final int SLEEP_ANIM_REFRESH = 15;
    /** How often a villager in bed re-checks that the bed is still its own. */
    private static final int CLAIM_CHECK_TICKS  = 40;
    /** A start that takes this long (10 ms) gets a timing line in the log. */
    private static final long SLOW_START_LOG_NANOS = 10_000_000L;
    /**
     * Longest an early bedtime waits for night to begin before daylight counts
     * as morning again. Early bedtimes start well under this before nightfall;
     * the cap only matters if the clock is moved back to day (/time set day).
     */
    private static final long WAIT_FOR_NIGHT_MAX_TICKS = 2400;
    /** How long a villager knocked out of bed stands there before lying back down. */
    private static final int STARTLED_TICKS = 60;
    /** Knocked out more often than this in one night: sleep somewhere else instead. */
    private static final int MAX_BED_RETURNS = 3;
    /**
     * Other tiles beside the same bed tried after a failed walk to it. One
     * failed walk -- a villager in the doorway, a door mid-swing -- used to
     * write the bed off and send its owner to the Town Hall for the night.
     */
    private static final int BED_WALK_RETRIES = 2;
    /**
     * How often a villager bound for, or asleep in, the Town Hall asks for a
     * bed again. Whatever ruled the beds out at bedtime (a failed walk's
     * 30-second blacklist, a room mid-rescan) has usually passed by then.
     */
    private static final int BED_RECHECK_TICKS = 200;
    /**
     * Times a night a villager leaves the hall for a bed. A bed that reads as
     * reachable but whose walk keeps failing would otherwise have it shuttle
     * between the two all night instead of sleeping.
     */
    private static final int MAX_BEDS_FROM_HALL = 3;

    private static final Logger LOGGER = LogUtils.getLogger();

    private enum SleepPhase { SITTING_DOWN, SLEEPING, SITTING_UP }

    private final boolean untilMorning;
    private final boolean untilNight;
    private final TownHallBuilding restrictedTownHall;

    // Shared
    private BlockPos bedPos;
    private SavedBedAssignment observedClaim;
    private BlockPos walkTarget;
    private boolean  nightRecorded = false;
    /**
     * Game time this bed-for-the-night started before nightfall (a tired
     * villager turning in early), or {@link Long#MIN_VALUE}. Until night has
     * begun, "not night" is the evening, not the morning.
     */
    private long     earlyBedtimeAt = Long.MIN_VALUE;

    // Bed path
    private boolean sleeping         = false;
    private int     bedAnimTimer     = 0;
    private int     bedAnimPhase     = 0; // per-villager offset so animations desync
    private int     claimCheckTimer  = 0;
    private int     startledTicks    = 0;
    private int     knockedOutCount  = 0;

    // Homeless path
    private SleepPhase sleepPhase      = null; // null while still walking to spot
    private int        phaseTimer      = 0;
    private int        sleepAnimTimer  = 0;
    private float      sleepYaw        = 0f;   // direction villager faces while sleeping
    private Direction  wallSleepDir    = null;  // solid wall direction at chosen spot
    /**
     * Where a villager sitting with its back to a full block sits (its tile's
     * middle), or null for the legs-out chain. The wall clips slide the body back
     * to the wall from the tile middle, so he is held there.
     */
    private Vec3       wallSeat        = null;
    /** The side the wall is on at the outdoor spot picked tonight; null for a hall spot (the Mayor). */
    private Direction  outdoorBack     = null;
    private int        wallCheckTimer  = 0;
    /** The wall behind this outdoor sleeper went: look for another spot once the sit-up finishes. */
    private boolean    wallGone        = false;
    private int        noSpotRetryTicks = 0;
    private int        bedRecheckTimer  = 0;
    private int        bedWalkRetries   = 0;
    private int        bedsFromHall     = 0;
    /** A bed found while asleep in the hall: walked to once the sit-up finishes. */
    private BlockPos   leaveForBed, leaveForBedTarget;
    /** The hall this villager sits in was removed: look elsewhere once the sit-up finishes. */
    private boolean    hallRemoved = false;
    private boolean    hallFallbackLogged = false;

    // Nowhere-to-sleep path
    private static final int NO_SPOT_RETRY_TICKS   = 100;
    private static final int ROAM_FAIL_PAUSE_TICKS = 40;
    private WanderActivity roam           = null;
    private int            roamPauseTicks = 0;
    /** The Mayor, waiting outside its hall, has walked in once since the last retry. */
    private boolean        hallWalkTried  = false;
    /** Set once {@link #start} has looked for a bed, so the empty fields mean something. */
    private boolean        searched       = false;

    public GoHomeActivity(boolean untilMorning) {
        this(untilMorning, null, false);
    }

    public GoHomeActivity(boolean untilMorning, TownHallBuilding restrictedTownHall) {
        this(untilMorning, restrictedTownHall, false);
    }

    private GoHomeActivity(boolean untilMorning, TownHallBuilding restrictedTownHall, boolean untilNight) {
        this.untilMorning = untilMorning;
        this.untilNight = untilNight;
        this.restrictedTownHall = restrictedTownHall;
    }

    /** Daytime rest variant used by Guards: sleep/rest until night begins. */
    public static GoHomeActivity untilNight() {
        return new GoHomeActivity(false, null, true);
    }

    @Override
    protected void start(Context ctx) {
        searched = true;
        ctx.model().setReservedSleepSpot(null);
        if (untilMorning && !VillagerBrain.isNight(ctx)) earlyBedtimeAt = ctx.level().getGameTime();

        // Already lying in a bed (a reload mid-night, before the saved claims
        // are back): keep that bed if it is still free to claim, rather than
        // being handed a different one and leaving this one looking empty.
        BlockPos lyingIn = ctx.npc().isSleeping() ? ctx.npc().getSleepingPos().orElse(null) : null;
        if (restrictedTownHall == null && lyingIn != null && isBedHead(ctx, lyingIn)
                && ctx.mgr().claimBedForSleep(ctx.model(), lyingIn, true)) {
            bedPos = lyingIn;
            resumeBedSleep(ctx);
            observedClaim = ctx.mgr().bedClaimOf(ctx.model().npcId);
            return;
        }

        // Laps for the slow-start line at the end: a start of 36 ms was seen
        // at a guard's daytime rest, and the brain's timing line cannot say
        // whether the bed request or the hall spot took it.
        long startNanos = System.nanoTime();
        long bedNs = 0, walkNs = 0, spotNs = 0, noteNs = 0;
        long lap = startNanos;
        if (restrictedTownHall != null) {
            bedPos = ctx.mgr().requestBedInTownHall(ctx.model(), restrictedTownHall);
        } else {
            bedPos = ctx.mgr().requestBedFor(ctx.model());
        }
        bedNs = System.nanoTime() - lap;

        if (bedPos != null && bedPos.equals(lyingIn)
                && ctx.mgr().claimBedForSleep(ctx.model(), bedPos, true)) {
            resumeBedSleep(ctx);
            observedClaim = ctx.mgr().bedClaimOf(ctx.model().npcId);
            return;
        }
        // Any sleep pose left now is for a bed this villager is not going to
        // use tonight. Walking off in it slides the villager along lying flat.
        getOutOfBed(ctx);

        if (bedPos != null) {
            lap = System.nanoTime();
            walkTarget = ctx.mgr().reachableBedWalkTarget(ctx.model(), bedPos);
            walkNs = System.nanoTime() - lap;
            if (walkTarget == null) {
                ctx.mgr().releaseTemporaryBedFor(ctx.model().npcId);
                bedPos = null;
                lap = System.nanoTime();
                walkTarget = pickSleepSpot(ctx);
                spotNs = System.nanoTime() - lap;
                lap = System.nanoTime();
                noteHallFallback(ctx, "its bed has no reachable tile beside it");
                noteNs = System.nanoTime() - lap;
            } else if (isAlreadyAtBed(ctx, bedPos, walkTarget)) {
                startSleepingInAssignedBed(ctx);
            }
        } else {
            lap = System.nanoTime();
            walkTarget = pickSleepSpot(ctx);
            spotNs = System.nanoTime() - lap;
            lap = System.nanoTime();
            noteHallFallback(ctx, "no bed could be claimed");
            noteNs = System.nanoTime() - lap;
        }
        observedClaim = ctx.mgr().bedClaimOf(ctx.model().npcId);
        long totalNs = System.nanoTime() - startNanos;
        if (totalNs >= SLOW_START_LOG_NANOS) {
            LOGGER.info("[GoHome {}] slow start {} ms: bedRequest={} bedWalkTarget={} pickSleepSpot={} noteHallFallback={}",
                    villagerLabel(ctx), totalNs / 1_000_000,
                    bedNs / 1_000_000, walkNs / 1_000_000, spotNs / 1_000_000, noteNs / 1_000_000);
        }
    }

    @Override
    protected Status tick(Context ctx) {
        if (ctx.model().isEscapingWater()) {
            resetMove();
            return Status.RUNNING;
        }

        // A housing transfer may happen while approaching an old bed. Refresh only
        // this sleep activity; working/combat activities and actual sleepers are left alone.
        if (restrictedTownHall == null && !sleeping && sleepPhase == null) {
            SavedBedAssignment claim = ctx.mgr().bedClaimOf(ctx.model().npcId);
            if (!Objects.equals(claim, observedClaim)) {
                stopRoam(ctx);
                ctx.model().stopMoving();
                ctx.model().setReservedSleepSpot(null);
                resetMove();
                bedPos = null;
                walkTarget = null;
                bedWalkRetries = 0;
                findPlaceToSleep(ctx, "its bed claim changed and no new bed was usable");
                observedClaim = ctx.mgr().bedClaimOf(ctx.model().npcId);
            }
        }

        if (bedPos == null) {

            return tickHomeless(ctx);
        } else {
            return tickWithBed(ctx);
        }
    }

    // -------------------------------------------------------------------------
    // Homeless path
    // -------------------------------------------------------------------------

    private Status tickHomeless(Context ctx) {
        if (!nightRecorded) {
            nightRecorded = true;
            ctx.brain().markStayedInTownHallTonight();
        }

        if (sleepPhase == null) {
            // If morning arrived before we reached the sleep spot, just wake and exit.
            if (morningCame(ctx)
                    || (untilNight && com.alex.test.village.villagers.guard.GuardShift.isShiftNight(ctx))) {
                ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                return Status.DONE;
            }
            // Still walking to the chosen sleep spot.
            if (walkTarget == null) {
                // No bed and no free wall spot: nowhere to sleep tonight. Stroll the
                // village instead of standing frozen in place, and keep
                // looking for a place to lie down. Roaming is not resting, so
                // no energy comes back, and ThoughtState shows the no-bed icon.
                // The Mayor waits in its hall instead: strolling, it was seen
                // walking around outside at night whenever the hall was full.
                if (restrictedTownHall != null) {
                    tickWaitInHall(ctx);
                } else {
                    tickRoam(ctx);
                }
                if (++noSpotRetryTicks >= NO_SPOT_RETRY_TICKS) {
                    noSpotRetryTicks = 0;
                    hallWalkTried = false;
                    if (findPlaceToSleep(ctx, "no bed could be claimed")) {
                        stopRoam(ctx);
                        resetMove();
                    }
                }
                return Status.RUNNING;
            }
            // On the way to the hall: a bed that has become usable since wins.
            if (++bedRecheckTimer >= BED_RECHECK_TICKS) {
                bedRecheckTimer = 0;
                BlockPos[] found = findBedFromHall(ctx);
                if (found != null) {
                    ctx.model().stopMoving();
                    ctx.model().setReservedSleepSpot(null);
                    goToBed(found);
                    return Status.RUNNING;
                }
            }
            if (moveTo(ctx, walkTarget, VillagerModel.WALK_SPEED)) {
                if (walkTarget != null && moveFailed(ctx, walkTarget)) {
                    walkTarget = pickSleepSpot(ctx);
                    resetMove();
                    return Status.RUNNING;
                }
                if (isAtSleepSpot(ctx, walkTarget)) {
                    // One tile: the same inputs pickSleepSpot builds for the
                    // whole hall, computed for just this tile's box. Outside, the
                    // wall must still be there too (the spot list is a minute old).
                    if (isSpotFree(ctx, walkTarget, reservedSleepKeys(ctx),
                            npcsIn(ctx, sleepingBox(ctx, walkTarget)))
                            && (outdoorBack == null
                                || WallSleepSpots.isLeanWall(ctx.level(), walkTarget.relative(outdoorBack)))) {
                        startSittingDown(ctx);
                    } else {
                        walkTarget = pickSleepSpot(ctx);
                        resetMove();
                    }
                } else if (Math.abs(ctx.npc().getY() - standHeight(ctx, walkTarget)) <= 0.5
                        && standPoint(ctx, walkTarget).distanceToSqr(ctx.npc().position()) <= 2.25) {
                    // A* may finish over a block from its final waypoint.
                    // Complete the short approach before claiming this tile.
                    ctx.npc().getNavigation().stop();
                    ctx.npc().getMoveControl().setWantedPosition(
                            walkTarget.getX() + 0.5, ctx.npc().getY(),
                            walkTarget.getZ() + 0.5, 0.45);
                } else {
                    resetMove();
                }
            }
            return Status.RUNNING;
        }

        switch (sleepPhase) {
            case SITTING_DOWN -> {
                holdFacing(ctx);
                if (++phaseTimer >= (wallSeat != null ? WALL_SITDOWN_TICKS : SITDOWN_TICKS)) {
                    sleepPhase = SleepPhase.SLEEPING;
                    ctx.npc().setAnimation(sleepState());
                    sleepAnimTimer = 0;
                }
            }
            case SLEEPING -> {
                holdFacing(ctx);
                if (++sleepAnimTimer >= SLEEP_ANIM_REFRESH) {
                    sleepAnimTimer = 0;
                    ctx.npc().setAnimation(sleepState());
                }
                // Asleep outside and the wall behind was broken: up, then another spot.
                if (outdoorBack != null && wallSeat != null && ++wallCheckTimer >= WALL_CHECK_TICKS) {
                    wallCheckTimer = 0;
                    if (!WallSleepSpots.isLeanWall(ctx.level(), walkTarget.relative(outdoorBack))) {
                        wallGone = true;
                        sleepPhase = SleepPhase.SITTING_UP;
                        phaseTimer = 0;
                        ctx.npc().setAnimation(sitUpState());
                        return Status.RUNNING;
                    }
                }
                if (morningCame(ctx)
                        || (untilNight && com.alex.test.village.villagers.guard.GuardShift.isShiftNight(ctx))) {
                    ctx.brain().onWokeAfterFullSleep();
                    sleepPhase = SleepPhase.SITTING_UP;
                    phaseTimer = 0;
                    ctx.npc().setAnimation(sitUpState());
                } else if (++bedRecheckTimer >= BED_RECHECK_TICKS) {
                    // A bed has come free, or become reachable: get up and go
                    // to it rather than sit out the night against the wall.
                    bedRecheckTimer = 0;
                    BlockPos[] found = findBedFromHall(ctx);
                    if (found != null) {
                        leaveForBed = found[0];
                        leaveForBedTarget = found[1];
                        sleepPhase = SleepPhase.SITTING_UP;
                        phaseTimer = 0;
                        ctx.npc().setAnimation(sitUpState());
                    }
                }
            }
            case SITTING_UP -> {
                holdFacing(ctx);
                if (++phaseTimer >= (wallSeat != null ? WALL_SITUP_TICKS : SITUP_TICKS)) {
                    ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                    if (leaveForBed != null) {
                        sleepPhase = null;
                        ctx.model().setReservedSleepSpot(null);
                        goToBed(new BlockPos[]{leaveForBed, leaveForBedTarget});
                        leaveForBed = leaveForBedTarget = null;
                        return Status.RUNNING;
                    }
                    if (hallRemoved) {
                        hallRemoved = false;
                        sleepPhase = null;
                        walkTarget = null;
                        ctx.model().setReservedSleepSpot(null);
                        findPlaceToSleep(ctx, "the Town Hall was removed");
                        return Status.RUNNING;
                    }
                    if (wallGone) {
                        wallGone = false;
                        sleepPhase = null;
                        walkTarget = null;
                        ctx.model().setReservedSleepSpot(null);
                        findPlaceToSleep(ctx, "the wall it slept against was broken");
                        return Status.RUNNING;
                    }
                    return Status.DONE;
                }
            }
        }

        return Status.RUNNING;
    }

    // -------------------------------------------------------------------------
    // Bed path
    // -------------------------------------------------------------------------

    private Status tickWithBed(Context ctx) {
        if (sleeping && !ctx.npc().isSleeping()) knockedOutOfBed(ctx);
        if (bedPos == null) return Status.RUNNING;
        if (startledTicks > 0) {
            if (--startledTicks == 0) returnToBed(ctx);
        } else if (!sleeping) {
            if (walkTarget != null && moveTo(ctx, walkTarget, VillagerModel.WALK_SPEED)) {
                if (moveFailed(ctx, walkTarget)) {
                    resetMove();
                    // The failed tile is blacklisted now, so this is another
                    // tile beside the same bed, or none.
                    BlockPos retry = bedWalkRetries < BED_WALK_RETRIES && isBedHead(ctx, bedPos)
                            ? ctx.mgr().reachableBedWalkTarget(ctx.model(), bedPos) : null;
                    if (retry != null) {
                        bedWalkRetries++;
                        walkTarget = retry;
                        return Status.RUNNING;
                    }
                    ctx.mgr().releaseTemporaryBedFor(ctx.model().npcId);
                    bedPos = null;
                    walkTarget = pickSleepSpot(ctx);
                    noteHallFallback(ctx, "the walk to its bed failed");
                    return Status.RUNNING;
                }
                if (isAlreadyAtBed(ctx, bedPos, walkTarget)) {
                    startSleepingInAssignedBed(ctx);
                } else if (isBedHead(ctx, bedPos)) {
                    resetMove();
                } else {
                    // Bed was broken on the way: take a free bed if there is one,
                    // else sit-sleep tonight.
                    ctx.mgr().releaseTemporaryBedFor(ctx.model().npcId);
                    bedPos = null;
                    walkTarget = null;
                    findPlaceToSleep(ctx, "its bed was broken on the way");
                    resetMove();
                }
            }
        } else {
            // A villager lying in a bed another one holds the claim to gets up
            // and finds somewhere else; the owner keeps it. Otherwise re-take a
            // claim that a room rescan dropped while this one slept, so the bed
            // is not handed out again under it.
            if (++claimCheckTimer >= CLAIM_CHECK_TICKS) {
                claimCheckTimer = 0;
                if (!isBedHead(ctx, bedPos)) {
                    giveUpBed(ctx, "its bed was broken");
                    return Status.RUNNING;
                }
                if (!ctx.mgr().claimBedForSleep(ctx.model(), bedPos, true)) {
                    giveUpBed(ctx, "its bed is someone else's or in no building");
                    return Status.RUNNING;
                }
            }
            // Refresh the looping animation periodically.
            if (++bedAnimTimer >= SLEEP_ANIM_REFRESH) {
                bedAnimTimer = 0;
                ctx.npc().setAnimation(VillagerAnimationState.SLEEP_LYING_DOWN);
            }
        }

        if (morningCame(ctx)
                || (untilNight && com.alex.test.village.villagers.guard.GuardShift.isShiftNight(ctx))) {
            if (sleeping) ctx.brain().onWokeAfterFullSleep();
            wakeUp(ctx);
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    // -------------------------------------------------------------------------

    /**
     * True once a night's sleep is over. A villager that turned in before
     * nightfall is still in its evening, not its morning: reading "not night"
     * as morning ended its bedtime on the first tick, and the brain started a
     * new one every tick, leaving it frozen where it stood until dark.
     */
    private boolean morningCame(Context ctx) {
        if (!untilMorning) return false;
        boolean night = VillagerBrain.isNight(ctx);
        if (earlyBedtimeAt != Long.MIN_VALUE) {
            if (!night && ctx.level().getGameTime() - earlyBedtimeAt < WAIT_FOR_NIGHT_MAX_TICKS) return false;
            earlyBedtimeAt = Long.MIN_VALUE;
        }
        return !night;
    }

    @Override
    public void stop(Context ctx) {
        stopRoam(ctx);
        wakeUp(ctx);
        ctx.model().stopMoving();
    }

    @Override
    public String name() { return (untilMorning || untilNight) ? "sleep" : "go_home"; }

    /** The daytime rest of a night-shift worker ({@link #untilNight()}), not a night's sleep. */
    public boolean isDaytimeRest() {
        return untilNight;
    }

    /** A claimed destination alone is not rest; the villager must be in its sleep pose. */
    public boolean isResting() {
        return sleeping || sleepPhase == SleepPhase.SLEEPING;
    }

    /**
     * Asleep outside against a wall: resting, but not indoors, so the bell
     * and raid shouts still send it in (VillagerBrain's override checks).
     */
    public boolean isRestingOutside() {
        return outdoorBack != null && sleepPhase == SleepPhase.SLEEPING;
    }

    /** Housing changes wait for a seated rest and its transition animations to finish. */
    public boolean canTransferHome() {
        return !sleeping && sleepPhase == null;
    }

    /** Capacity migration is the exception to deferring a sleeping resident's transfer. */
    public void onBedClaimRevoked(Context ctx) {
        stopRoam(ctx);
        wakeUp(ctx);
        ctx.model().stopMoving();
        resetMove();
        bedPos = null;
        walkTarget = null;
        observedClaim = null;
        noSpotRetryTicks = NO_SPOT_RETRY_TICKS;
    }

    /**
     * {@code gone} was just removed (VillageManager.onBuildingRemoved; once per
     * removal, never polled). A bed in it: up and look elsewhere now. A hall
     * spot in it: sit up first, then look; on the way there, pick again.
     */
    public void onBuildingRemoved(Context ctx, Building gone) {
        if (restrictedTownHall != null) return;
        if (bedPos != null) {
            if (ctx.mgr().isBedIn(gone, bedPos)) giveUpBed(ctx, "its home was removed");
            return;
        }
        if (!(gone instanceof TownHallBuilding) || walkTarget == null
                || !gone.getNavMeshBlocks().contains(walkTarget)) return;
        if (sleepPhase == SleepPhase.SITTING_DOWN || sleepPhase == SleepPhase.SLEEPING) {
            hallRemoved = true;
            sleepPhase = SleepPhase.SITTING_UP;
            phaseTimer = 0;
            ctx.npc().setAnimation(sitUpState());
        } else if (sleepPhase == null) {
            ctx.model().stopMoving();
            ctx.model().setReservedSleepSpot(null);
            resetMove();
            walkTarget = null;
            findPlaceToSleep(ctx, "the Town Hall was removed");
        }
    }

    /** True while the villager has neither a bed nor a town-hall spot to sleep in. */
    public boolean hasNowhereToSleep() {
        return searched && bedPos == null && walkTarget == null && sleepPhase == null;
    }

    // -------------------------------------------------------------------------
    // Nowhere to sleep
    // -------------------------------------------------------------------------

    private void tickRoam(Context ctx) {
        if (roamPauseTicks > 0) {
            roamPauseTicks--;
            ctx.model().stopMoving();
            return;
        }
        if (roam == null) roam = new WanderActivity();
        Status s = roam.tickOuter(ctx);
        if (s == Status.RUNNING) return;
        roam.stop(ctx);
        roam = null;
        // A failed wander usually means no goal could be picked; back off
        // instead of rescanning the nav graph every tick.
        if (s == Status.FAILED) roamPauseTicks = ROAM_FAIL_PAUSE_TICKS;
    }

    private void stopRoam(Context ctx) {
        if (roam == null) return;
        roam.stop(ctx);
        roam = null;
    }

    /**
     * The Mayor's night with no bed and no free hall spot: inside its hall it
     * stands still until the retry finds one; outside, it walks to the nearest
     * hall tile, once per retry so a failed walk is not re-planned every tick.
     */
    private void tickWaitInHall(Context ctx) {
        if (hallWalkTried || ctx.model().isMoving()) return;
        Building hall = liveRestrictedHall(ctx);
        NavGraph graph = ctx.mgr().getGraph();
        if (hall == null || graph == null || hall.isInRoom(ctx.npc().blockPosition())) return;
        hallWalkTried = true;
        BlockPos current = ctx.npc().blockPosition();
        BlockPos nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (BlockPos tile : hall.getNavMeshBlocks()) {
            if (!isSleepableTile(ctx, graph, tile)) continue;
            long dx = tile.getX() - current.getX();
            long dy = tile.getY() - current.getY();
            long dz = tile.getZ() - current.getZ();
            long distance = dx * dx + dz * dz + 4 * dy * dy;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = tile;
            }
        }
        if (nearest != null) ctx.model().requestMoveTo(graph, nearest, VillagerModel.WALK_SPEED);
    }

    /** The registered, valid hall {@link #restrictedTownHall} stands for now (a rescan may replace it), or null. */
    private Building liveRestrictedHall(Context ctx) {
        for (Building b : ctx.mgr().getBuildings()) {
            if (b instanceof TownHallBuilding && b.isValid()
                    && b.getItemFrameId().equals(restrictedTownHall.getItemFrameId())) return b;
        }
        return null;
    }

    /**
     * Retry what {@link #start} did: a bed that has come free, then a wall spot
     * outside (the Mayor: a hall spot). Returns true once there is somewhere to walk to.
     */
    private boolean findPlaceToSleep(Context ctx, String whyNoBed) {
        BlockPos bed = restrictedTownHall != null
                ? ctx.mgr().requestBedInTownHall(ctx.model(), restrictedTownHall)
                : ctx.mgr().requestBedFor(ctx.model());
        observedClaim = ctx.mgr().bedClaimOf(ctx.model().npcId);
        if (bed != null) {
            BlockPos target = ctx.mgr().reachableBedWalkTarget(ctx.model(), bed);
            if (target != null) {
                bedPos = bed;
                walkTarget = target;
                bedWalkRetries = 0;
                return true;
            }
            ctx.mgr().releaseTemporaryBedFor(ctx.model().npcId);
        }
        walkTarget = pickSleepSpot(ctx);
        noteHallFallback(ctx, whyNoBed);
        return walkTarget != null;
    }

    /**
     * A bed this villager, bound for or asleep in the Town Hall, can now
     * claim and walk to: {bed, tile beside it}, or null. Not for the Mayor,
     * whose hall spot is the fallback for its own Town Hall bed.
     */
    private BlockPos[] findBedFromHall(Context ctx) {
        if (restrictedTownHall != null || bedsFromHall >= MAX_BEDS_FROM_HALL) return null;
        BlockPos bed = ctx.mgr().requestBedFor(ctx.model());
        observedClaim = ctx.mgr().bedClaimOf(ctx.model().npcId);
        if (bed == null) return null;
        BlockPos target = ctx.mgr().reachableBedWalkTarget(ctx.model(), bed);
        if (target == null) return null;
        bedsFromHall++;
        LOGGER.info("[Beds] {} found bed {} after all; leaving its wall spot for it.",
                villagerLabel(ctx), bed.toShortString());
        return new BlockPos[]{bed, target};
    }

    /** Switches from the hall to {bed, tile beside it}; tickWithBed takes over next tick. */
    private void goToBed(BlockPos[] found) {
        bedPos = found[0];
        walkTarget = found[1];
        wallSleepDir = null;
        bedWalkRetries = 0;
        resetMove();
    }

    /**
     * Logs, once a night, why this villager is not sleeping in a bed, with
     * every house and bed and what ruled it out. Filter the log for "[Beds]",
     * e.g. {@code /villager debug console on info [Beds]}.
     */
    private void noteHallFallback(Context ctx, String why) {
        if (restrictedTownHall != null || hallFallbackLogged || bedPos != null) return;
        hallFallbackLogged = true;
        StringBuilder msg = new StringBuilder("[Beds] ").append(villagerLabel(ctx))
                .append(" has no bed tonight: ").append(why).append("; ")
                .append(walkTarget != null ? "sleeping outside against a wall at " + walkTarget.toShortString()
                        : "no free wall spot, roaming")
                .append(". Village has a free bed: ").append(ctx.mgr().hasFreeBed()).append('.');
        for (String line : ctx.mgr().explainNoBed(ctx.model())) {
            msg.append("\n[Beds]   ").append(line);
        }
        LOGGER.info(msg.toString());
    }

    private static String villagerLabel(Context ctx) {
        return ctx.npc().getName().getString() + " (" + ctx.model().npcId.toString().substring(0, 8) + ")";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void startSittingDown(Context ctx) {
        ctx.model().stopMoving();
        ctx.npc().holdStillForAnimation();
        sleepPhase = SleepPhase.SITTING_DOWN;
        phaseTimer = 0;

        // A full block behind a flat tile: sit with the back to it, knees up
        // (read now, not at the pick: the walk here takes a while). Outside,
        // the spot's own wall, checked on arrival; the ground there need not be
        // level all round, only the tile itself (WallSleepSpots).
        Direction backDir = outdoorBack != null && walkTarget != null
                && WallSleepSpots.isLeanWall(ctx.level(), walkTarget.relative(outdoorBack)) ? outdoorBack
                : walkTarget != null && isFlatFloor(ctx.mgr().getGraph(), walkTarget)
                ? findWallBackDir(ctx, walkTarget) : null;
        wallSeat = backDir != null
                ? new Vec3(walkTarget.getX() + 0.5, ctx.npc().getY(), walkTarget.getZ() + 0.5) : null;

        // Re-check wall direction at arrival position (villager may not be exactly
        // on the chosen nav tile) and compute facing-away yaw.
        Direction wallDir = backDir != null ? backDir
                : (wallSleepDir != null)
                ? wallSleepDir
                : findWallDir(ctx, ctx.npc().blockPosition());

        if (wallDir != null) {
            Vec3 away = Vec3.atLowerCornerOf(wallDir.getOpposite().getNormal());
            sleepYaw = (float) Math.toDegrees(Math.atan2(-away.x, away.z));
        } else {
            sleepYaw = ctx.npc().getYRot();
        }
        wallCheckTimer = 0;
        wallGone = false;
        holdFacing(ctx);
        ctx.npc().setAnimation(wallSeat != null ? VillagerAnimationState.WALL_SITDOWN : VillagerAnimationState.SITDOWN);
    }

    /**
     * Re-applies the sleep yaw each tick to prevent look-control from overriding it,
     * and keeps a wall sleeper on its tile middle (arrival allows 0.4 off it, and a
     * push moves it): the clip puts the back on the wall only from there.
     */
    private void holdFacing(Context ctx) {
        ctx.npc().setYRot(sleepYaw);
        ctx.npc().setYHeadRot(sleepYaw);
        if (wallSeat != null) {
            double dx = ctx.npc().getX() - wallSeat.x, dz = ctx.npc().getZ() - wallSeat.z;
            if (dx * dx + dz * dz > 1.0E-4) ctx.npc().setPos(wallSeat.x, ctx.npc().getY(), wallSeat.z);
        }
    }

    /** The sleeping loop for the chain this villager sat down with. */
    private VillagerAnimationState sleepState() {
        return wallSeat != null ? VillagerAnimationState.WALL_SLEEP : VillagerAnimationState.SLEEP_SITTING_DOWN;
    }

    /** The stand-up for the chain this villager sat down with. */
    private VillagerAnimationState sitUpState() {
        return wallSeat != null ? VillagerAnimationState.WALL_SITUP : VillagerAnimationState.SITUP;
    }

    private void wakeUp(Context ctx) {
        ctx.model().setReservedSleepSpot(null);
        if (ctx.npc().isSleeping()) ctx.npc().stopSleeping();
        // Always reset — GeckoLib applies a horizontal rotation for Pose.SLEEPING
        // regardless of the active animation, so any path that skips this would
        // leave the villager gliding along the ground after waking.
        ctx.npc().setPose(Pose.STANDING);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        sleeping   = false;
        sleepPhase = null;
        wallSeat   = null;
    }

    private void startSleepingInAssignedBed(Context ctx) {
        // Vanilla lets any number of sleepers into one bed; the claim is the
        // only thing keeping a second villager out of it.
        if (!ctx.mgr().claimBedForSleep(ctx.model(), bedPos, false)) {
            giveUpBed(ctx, "its bed is someone else's or in no building");
            return;
        }
        ctx.model().stopMoving();
        resetMove();
        ctx.npc().startBedSleep(bedPos);
        resumeBedSleep(ctx);
        ctx.brain().markSleptInBedTonight();
        ctx.brain().remember(MemoryTopic.GOOD_REST, ctx.level());
    }

    /** This bed cannot be used tonight ({@code why}): get up and look for another bed or a hall spot. */
    private void giveUpBed(Context ctx, String why) {
        getOutOfBed(ctx);
        sleeping = false;
        claimCheckTimer = 0;
        startledTicks = 0;
        bedPos = null;
        walkTarget = null;
        ctx.model().stopMoving();
        resetMove();
        findPlaceToSleep(ctx, why);
    }

    /**
     * Something stood this villager up out of its bed. Vanilla wakes any
     * sleeper that takes damage and puts it on its feet beside the bed; this
     * activity still thought it was lying down, so it stood there "asleep"
     * until morning. It now waits a moment and lies back down, or, knocked
     * out too often tonight, sleeps somewhere else. A monster's hit needs
     * nothing here: the brain's fight or flee reaction takes over.
     */
    private void knockedOutOfBed(Context ctx) {
        sleeping = false;
        claimCheckTimer = 0;
        ctx.model().stopMoving();
        resetMove();
        ctx.npc().setPose(Pose.STANDING);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        DamageSource hit = ctx.npc().getLastDamageSource();
        String why = hit == null ? "not hurt"
                : "hurt: " + hit.getMsgId()
                  + (hit.getEntity() != null ? " by " + hit.getEntity().getName().getString() : "");
        boolean tryAgain = ++knockedOutCount <= MAX_BED_RETURNS;
        ctx.brain().diary().log(String.format("%s SLEEP knocked out of bed %s (%s), %s",
                VillagerDiary.ts(ctx.level()), bedPos.toShortString(), why,
                tryAgain ? "lying back down soon" : "sleeping elsewhere tonight"));
        if (tryAgain) {
            startledTicks = STARTLED_TICKS;
        } else {
            sleepElsewhere(ctx);
        }
    }

    /** The startled pause is over: back into the same bed, walking to it first if need be. */
    private void returnToBed(Context ctx) {
        if (!isBedHead(ctx, bedPos)) {
            giveUpBed(ctx, "its bed was broken");
            return;
        }
        // A bed resumed after a reload never had a walk target.
        if (walkTarget == null) walkTarget = ctx.mgr().reachableBedWalkTarget(ctx.model(), bedPos);
        if (walkTarget == null) {
            sleepElsewhere(ctx);
        } else if (isAlreadyAtBed(ctx, bedPos, walkTarget)) {
            startSleepingInAssignedBed(ctx);
        } else {
            resetMove();
        }
    }

    /** Tonight at a wall spot outside instead; the bed claim itself is kept. */
    private void sleepElsewhere(Context ctx) {
        bedPos = null;
        walkTarget = pickSleepSpot(ctx);
        resetMove();
    }

    /** Drops a vanilla sleep pose, if any, without touching the activity's own state. */
    private static void getOutOfBed(Context ctx) {
        if (!ctx.npc().isSleeping()) return;
        ctx.npc().stopSleeping();
        ctx.npc().setPose(Pose.STANDING);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
    }

    private void resumeBedSleep(Context ctx) {
        sleeping = true;
        // Stagger animation refresh across villagers using UUID hash so they
        // don't all breathe in sync.
        bedAnimPhase = Math.abs(ctx.npc().getNpcId().hashCode()) % SLEEP_ANIM_REFRESH;
        bedAnimTimer = bedAnimPhase;
        ctx.npc().setAnimation(VillagerAnimationState.SLEEP_LYING_DOWN);
    }

    private boolean isAlreadyAtBed(Context ctx, BlockPos bedHead, BlockPos target) {
        if (!isBedHead(ctx, bedHead)) return false;
        if (!isStandingOffLadder(ctx)) return false;
        BlockPos npcPos = ctx.npc().blockPosition();
        if (sameOrAdjacentSameFloor(npcPos, target)) return true;
        for (BlockPos bedBlock : bedBlocks(ctx, bedHead)) {
            if (sameOrAdjacentSameFloor(npcPos, bedBlock)) return true;
        }
        return false;
    }

    private static boolean sameOrAdjacentSameFloor(BlockPos a, BlockPos b) {
        return a != null && b != null
                && Math.abs(a.getY() - b.getY()) <= 1
                && Math.abs(a.getX() - b.getX()) <= 1
                && Math.abs(a.getZ() - b.getZ()) <= 1;
    }

    private static List<BlockPos> bedBlocks(Context ctx, BlockPos headPos) {
        List<BlockPos> blocks = new ArrayList<>();
        blocks.add(headPos);
        Direction facing = ctx.level().getBlockState(headPos).getValue(BlockStateProperties.HORIZONTAL_FACING);
        blocks.add(headPos.relative(facing.getOpposite()));
        return blocks;
    }

    private static boolean isBedHead(Context ctx, BlockPos pos) {
        if (pos == null) return false;
        BlockState state = ctx.level().getBlockState(pos);
        return state.getBlock() instanceof BedBlock
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
    }

    // -------------------------------------------------------------------------
    // Wall-preference spot picking
    // -------------------------------------------------------------------------

    /**
     * Where this villager sleeps tonight without a bed (user, 10-23): outside,
     * sitting against a building's outer wall ({@link WallSleepSpots}), never
     * on the Town Hall floor, rain or not. Null when no wall spot is free and
     * reachable: it strolls and asks again. The Mayor keeps its hall spot.
     */
    private BlockPos pickSleepSpot(Context ctx) {
        outdoorBack = null;
        if (restrictedTownHall != null) return pickHallSpot(ctx);
        ctx.model().setReservedSleepSpot(null);
        wallSleepDir = null;
        WallSleepSpots.Spot spot = WallSleepSpots.pick(ctx);
        if (spot == null) return null;
        outdoorBack = spot.back();
        ctx.model().setReservedSleepSpot(spot.tile());
        return spot.tile();
    }

    /**
     * Selects a sleep spot from the town-hall nav mesh (the Mayor only, since 10-23). Tiles with at least one
     * adjacent solid block (i.e. a wall to put their back against) are preferred
     * over open tiles; if none are available, falls back to any interior tile.
     * Tiles within 2 blocks of the door are always excluded so villagers don't
     * bunch up at the entrance.
     *
     * <p>Tiles are sorted into pools on cheap geometry alone, and the costly
     * checks -- is anyone sleeping there, can this villager walk there -- run
     * only on tiles drawn at random from the best pool, stopping at the first
     * that passes. Checking every tile first and then drawing one threw away
     * all but one answer, and each answer used to be an A* search: at dusk
     * that was every homeless villager times every tile in the hall, all in
     * one tick. The first passing tile of a random draw order is still a
     * uniform pick among the passing tiles, so where villagers sleep is
     * unchanged.
     */
    private BlockPos pickHallSpot(Context ctx) {
        ctx.model().setReservedSleepSpot(null);
        List<BlockPos> wallSpots  = new ArrayList<>();
        List<BlockPos> openSpots  = new ArrayList<>();
        List<BlockPos> stepSpots  = new ArrayList<>();
        NavGraph graph = ctx.mgr().getGraph();
        // The hall's nav-mesh bounding box, for one entity query over the
        // whole hall instead of one per tile drawn (see isSpotFree).
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof TownHallBuilding) || !b.isValid() || b.getNavMeshBlocks().isEmpty()) continue;
            if (restrictedTownHall != null
                    && !b.getItemFrameId().equals(restrictedTownHall.getItemFrameId())) continue;
            BlockPos door = b.getDoorPos();
            for (BlockPos p : b.getNavMeshBlocks()) {
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
                // Skip tiles right at the entrance — villagers cluster there otherwise.
                if (door != null && Math.abs(p.getX() - door.getX()) <= 2
                        && Math.abs(p.getZ() - door.getZ()) <= 2) continue;
                // The room mesh also contains ladder rungs. They are transit
                // nodes, not places where a villager can settle to sleep.
                if (!isSleepableTile(ctx, graph, p)) continue;
                // A small hall's room mesh is mostly its staircase. Sleeping on
                // a step, or at the foot of one, blocks the stairs, and a spot
                // lost to someone else sent the villager back up them for the
                // next step along. Only if the hall has nothing flatter.
                if (!isFlatFloor(graph, p)) {
                    stepSpots.add(p);
                } else if (findWallDir(ctx, p) != null) {
                    wallSpots.add(p);
                } else {
                    openSpots.add(p);
                }
            }
        }

        // Built once for every draw below: the other villagers' reserved
        // spots as a set, and every other villager in the hall from one
        // entity query. isSpotFree used to walk all villagers and query the
        // world per tile, and a crowded hall fails many draws per pick.
        // The box is the mesh blocks grown by a block each way, which holds
        // every tile's sleeping box (NPC_HEIGHT 1.9 plus 0.05 of inflate).
        Set<Long> reserved = reservedSleepKeys(ctx);
        List<NpcEntity> inHall = minX == Integer.MAX_VALUE ? List.of()
                : npcsIn(ctx, new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1).inflate(1.0));

        // Wall tiles first, then any flat tile, then steps. A flat tile only
        // gets its turn once every wall tile has failed, which is what
        // preferring wall tiles among the flat ones amounts to.
        for (List<BlockPos> pool : List.of(wallSpots, openSpots, stepSpots)) {
            BlockPos chosen = drawAvailableSpot(ctx, graph, pool, reserved, inHall);
            if (chosen != null) {
                wallSleepDir = findWallDir(ctx, chosen);
                ctx.model().setReservedSleepSpot(chosen);
                return chosen;
            }
        }

        wallSleepDir = null;
        return null;
    }

    /**
     * Draws tiles from {@code pool} in random order and returns the first that
     * is free and reachable, or null if none is. Consumes the pool.
     */
    private BlockPos drawAvailableSpot(Context ctx, NavGraph graph, List<BlockPos> pool,
                                      Set<Long> reserved, List<NpcEntity> inHall) {
        while (!pool.isEmpty()) {
            int i = ctx.npc().getRandom().nextInt(pool.size());
            BlockPos p = pool.get(i);
            pool.set(i, pool.get(pool.size() - 1));
            pool.remove(pool.size() - 1);
            if (isSpotFree(ctx, p, reserved, inHall) && ctx.model().canReach(graph, p)) return p;
        }
        return null;
    }

    private boolean isAtSleepSpot(Context ctx, BlockPos target) {
        return isStandingOffLadder(ctx)
                && Math.abs(ctx.npc().getY() - standHeight(ctx, target)) <= 0.25
                && standPoint(ctx, target).distanceToSqr(ctx.npc().position()) <= 0.4 * 0.4;
    }

    /**
     * Where a villager's feet really are on this tile. The tile is a graph
     * feet cell, but on a bottom-slab floor the villager stands half a block
     * below it, which the old quarter-block check never accepted: it walked
     * onto a slab-floored sleep spot and nudged at it all night.
     */
    private static double standHeight(Context ctx, BlockPos tile) {
        NavGraph graph = ctx.mgr().getGraph();
        NavNode node = graph != null ? graph.nodeAt(tile.getX(), tile.getY(), tile.getZ()) : null;
        return node != null ? node.standY : tile.getY();
    }

    private static Vec3 standPoint(Context ctx, BlockPos tile) {
        return new Vec3(tile.getX() + 0.5, standHeight(ctx, tile), tile.getZ() + 0.5);
    }

    /** The cheap, per-tile half of choosing a sleep spot; see {@link #pickSleepSpot}. */
    private boolean isSleepableTile(Context ctx, NavGraph graph, BlockPos p) {
        if (graph == null || ctx.model().hasRecentMoveFailure(p)
                || ctx.level().getBlockState(p).is(Blocks.LADDER)) return false;
        NavNode node = graph.nodeAt(p.getX(), p.getY(), p.getZ());
        return node != null && node.isRoutable() && !node.climb;
    }

    /** Not a stair or slab step, and not the tile at the foot of one. */
    private static boolean isFlatFloor(NavGraph graph, BlockPos p) {
        NavNode node = graph != null ? graph.nodeAt(p.getX(), p.getY(), p.getZ()) : null;
        if (node == null) return true;
        for (NavNode nb : node.neighbours()) {
            if (Math.abs(nb.standY - node.standY) > 0.25) return false;
        }
        return true;
    }

    /**
     * The tile is not another villager's reserved spot and no other villager
     * stands on it. {@code reserved} and {@code inHall} come from
     * {@link #reservedSleepKeys} and {@link #npcsIn}, built once per pick.
     */
    private boolean isSpotFree(Context ctx, BlockPos p, Set<Long> reserved, List<NpcEntity> inHall) {
        if (reserved.contains(p.asLong())) return false;
        AABB sleepingBox = sleepingBox(ctx, p);
        for (NpcEntity other : inHall) {
            if (other.getBoundingBox().intersects(sleepingBox)) return false;
        }
        return true;
    }

    /**
     * Every other villager's reserved sleep spot, keyed at its own height and
     * one block above and below, so the "same column within a block of
     * height" rule is one set lookup per tile.
     */
    private static Set<Long> reservedSleepKeys(Context ctx) {
        Set<Long> keys = new HashSet<>();
        for (VillagerModel other : ctx.mgr().getVillagers()) {
            if (other == ctx.model()) continue;
            BlockPos r = other.reservedSleepSpot();
            if (r == null) continue;
            keys.add(BlockPos.asLong(r.getX(), r.getY() - 1, r.getZ()));
            keys.add(r.asLong());
            keys.add(BlockPos.asLong(r.getX(), r.getY() + 1, r.getZ()));
        }
        return keys;
    }

    /** This villager's bounding box, were it standing on the centre of {@code p}, grown a little. */
    private static AABB sleepingBox(Context ctx, BlockPos p) {
        return ctx.npc().getBoundingBox().move(
                p.getX() + 0.5 - ctx.npc().getX(), p.getY() - ctx.npc().getY(),
                p.getZ() + 0.5 - ctx.npc().getZ()).inflate(0.05);
    }

    /** Other living villagers whose bounding boxes touch {@code box}. */
    private static List<NpcEntity> npcsIn(Context ctx, AABB box) {
        return ctx.level().getEntitiesOfClass(NpcEntity.class, box,
                other -> other != ctx.npc() && other.isAlive());
    }

    private static boolean isStandingOffLadder(Context ctx) {
        return ctx.npc().onGround()
                && !ctx.level().getBlockState(ctx.npc().blockPosition()).is(Blocks.LADDER);
    }

    /**
     * Returns the first cardinal direction from {@code pos} that has a solid
     * (impassable) block, or {@code null} if all four sides are open.
     */
    private static Direction findWallDir(Context ctx, BlockPos pos) {
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos wall = pos.relative(dir);
            if (!NavGraph.isPassable(ctx.level(), wall.getX(), wall.getY(), wall.getZ())) return dir;
        }
        return null;
    }

    /**
     * The first cardinal direction from {@code pos} with a full block at feet
     * height ({@link WallSleepSpots#isLeanWall}), which a wall sleeper can lean
     * on; {@code null} if none. The head reaches into the block above, but a low
     * wall with nothing over it still reads as leaning back.
     */
    private static Direction findWallBackDir(Context ctx, BlockPos pos) {
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (WallSleepSpots.isLeanWall(ctx.level(), pos.relative(dir))) return dir;
        }
        return null;
    }
}
