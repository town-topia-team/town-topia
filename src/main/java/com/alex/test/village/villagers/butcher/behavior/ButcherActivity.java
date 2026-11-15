package com.alex.test.village.villagers.butcher.behavior;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageDays;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.ButcheryTrouble;
import com.alex.test.village.building.ButcheryTrouble.Kind;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.butcher.ButcherBalance;
import com.alex.test.village.villagers.butcher.ButcherDrops;
import com.alex.test.village.villagers.butcher.ButcherStatus;
import com.alex.test.village.villagers.butcher.ButcherSword;
import com.alex.test.village.villagers.butcher.lead.BringBackStrayActivity;
import com.alex.test.village.villagers.butcher.lead.Footsteps;
import com.alex.test.village.villagers.butcher.lead.LeadDoorways;
import com.alex.test.village.villagers.butcher.lead.LeadSupply;
import com.alex.test.village.villagers.butcher.lead.LeadWalk;
import com.alex.test.village.villagers.butcher.lead.LedAnimals;
import com.alex.test.village.villagers.butcher.lead.RanchStrays;
import com.alex.test.village.villagers.butcher.settings.ButcherSettingsSchema;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.guard.GuardSwordUtils;
import com.alex.test.village.villagers.rancher.RanchAnimals;
import com.alex.test.village.villagers.rancher.RanchClaims;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Butcher's working day: lead spare ranch animals to the Butchery and
 * kill them at the chopping block.
 *
 * <p>Always leaves {@link ButcherSettingsSchema#KEEP_ADULTS} adults per
 * species so the rancher can rebuild the herd, and never touches babies,
 * named animals or animals in love. The butcher walks into the pen, puts its
 * lead on the animal, and walks it out of the pen and along its own route to
 * the Butchery ({@link LeadWalk}: the animal treads in the butcher's
 * footsteps). Gates and doors on the way are held open until the animal is
 * through, then shut behind it ({@link LeadDoorways}). At the chopping block
 * the butcher kills it the vanilla way, sword blow by sword blow; the animal
 * drops its own loot table, the butcher's skill adds to it
 * ({@link ButcherDrops}), and the butcher gathers it up (COLLECT).
 *
 * <p>Up to {@link ButcherBalance#cullsPerShift} animals a shift, then the pack is banked.
 * Every way a walk can go wrong ends in {@link #abandonLead}, which lets the
 * animal go without dropping a lead item, frees the doorways it held, and
 * sets the animal aside for a minute.
 */
public final class ButcherActivity extends WorkActivity {

    private enum State { PICK, GO_TO_ANIMAL, LEASH, LEAD_OUT, WALK_BACK, ENTER, KILL, COLLECT, SHARPEN, DEPOSIT, STRAY }

    /**
     * Ticks per sword swing: a level-1 guard's pace and a little slower, a
     * deliberate chop rather than a fight (well past the 10-tick hurt immunity).
     */
    public static final int BLOW_TICKS = GuardBalance.attackTicks(1) + 4;
    /**
     * Ticks into a swing when the blade lands: the overhead chop is blended in
     * first, then comes down at {@link VillagerAnimationState#ATTACK_DOWN_CONTACT}
     * of its clip, both paced to fit the swing (NpcEntity.startChopAnimation).
     */
    private static final int STRIKE_TICKS = Math.max(1, Math.round(BLOW_TICKS
            * (VillagerAnimationState.WORK_BLEND_TICKS
                    + VillagerAnimationState.ATTACK_DOWN_CONTACT * VillagerAnimationState.ATTACK_DOWN_TICKS)
            / (VillagerAnimationState.WORK_BLEND_TICKS + VillagerAnimationState.ATTACK_DOWN_TICKS)));
    /** Blows without a kill before the butcher gives the animal up. */
    private static final int MAX_BLOWS = 12;
    /** Meat the pack must have room for before a kill: a vanilla roll's worth. */
    private static final int MIN_MEAT_ROOM = 3;
    /** Ticks the drops get to land before the butcher gathers them. */
    private static final int DROP_SETTLE_TICKS = 10;
    /** Gathering the drops of one kill takes no longer than this. */
    private static final int COLLECT_LIMIT = 200;
    /** Marked drops this far from where the animal fell are gathered. */
    private static final double COLLECT_RADIUS = 4.0;
    /** Ticks at the grindstone. */
    public static final int SHARPEN_TICKS = 80;
    /** Durability a grindstone visit gives back: 1 or 2, once a day. It slows the wear, it does not undo it. */
    private static final int SHARPEN_MIN = 1;
    private static final int SHARPEN_MAX = 2;
    /** The village day of the last grindstone visit, on the butcher's brain data (survives a reload). */
    private static final String TAG_SHARPEN_DAY = "ButcherSharpenDay";

    /** A Butchery further than this from the ranch gate is no use to it. */
    public static final double MAX_BUTCHERY_DIST = 96.0;

    private static final double REACH_SQ = 6.25;
    /** Walks that found no way to the animal before the butcher leaves it. */
    private static final int MAX_CHASE_REPATHS = 4;
    /** And the longest a chase goes on: 30 s. */
    private static final long CHASE_LIMIT_TICKS = 600L;
    private static final float CHASE_SPEED = 0.6f;
    private static final float LEAD_SPEED = 0.45f;
    /** Animal this many crumbs behind: the butcher waits for it. */
    private static final int WAIT_BEHIND = 4;
    /** And walks on once it is this close again. */
    private static final int RESUME_BEHIND = 2;
    /** Waiting this long (10 s), the butcher walks back to the animal. */
    private static final int WAIT_LIMIT = 200;
    private static final int MAX_WALK_BACKS = 1;
    private static final int MAX_ROUTE_FAILS = 2;
    /** Time allowed to bring the animal onto its spot at the chopping block. */
    private static final int ENTER_LIMIT = 200;
    /** The butcher at the block, the animal still outside this long (5 s): it is put in. */
    private static final int PUT_IN_TICKS = 100;
    /** Put in only while it is this near the butcher: stuck by the door, not lost down the street. */
    private static final double PUT_IN_RANGE = 8.0;

    private State state = State.PICK;

    @Nullable private Animal target;
    @Nullable private RanchBuilding ranch;
    @Nullable private ButcheryBuilding butchery;
    @Nullable private ButcheryBuilding.WorkSpots spots;
    @Nullable private MoveActivity move;
    @Nullable private BlockPos chaseDest;
    @Nullable private Activity sub;
    @Nullable private GetToolActivity fetchTool;
    /** What {@link #fetchTool} is fetching: the lead or the sword. */
    @Nullable private ProfessionToolRequirement fetching;
    @Nullable private BringBackStrayActivity strayJob;
    @Nullable private LeadDoorways doorways;
    /** The butcher's last steps on the walk over, for a lead put on from a distance. */
    private final Footsteps footsteps = new Footsteps();
    /** The lead goes on from where the butcher stands: it could not walk up to the animal. */
    private boolean farLead;

    /** Whether the target is on this butcher's lead right now. */
    private boolean leading;
    private int repaths;
    /** {@link Context#clock()} when the walk to the current animal began. */
    private long chaseSince;
    private int actTimer;
    private int waitTicks;
    private int walkBacks;
    private int routeFails;
    private int enterTicks;
    /** Why the last try to put the animal in said no on this walk, for the give-up's log line. */
    @Nullable private String putInNo;
    private int blows;
    private int collectTicks;
    /** Drops this gathering walked to and could not get within reach of; they are left where they lie. */
    private final Set<UUID> skippedDrops = new HashSet<>();
    /** The drop the current gathering walk is heading for. */
    @Nullable private ItemEntity walkingTo;
    /** Where the last kill fell; its drops are gathered from around here. Null once gathered. */
    @Nullable private BlockPos dropsAt;
    /** What the last kill's drops came to, for its diary line. */
    private final Map<Item, Integer> haul = new HashMap<>();
    @Nullable private String killNote;
    /** Banked once already for this kill's drops: a pack still full leaves the rest. */
    private boolean bankedForDrops;
    private boolean workedThisSession;
    private int culledThisSession;
    /** An animal let go inside the Butchery when the pack filled up; fetched again after banking. */
    @Nullable private UUID strandedInside;
    /**
     * The pen the animal waiting in the Butchery came from: if the wait ends
     * without a kill it is handed back to this pen. Cleared with {@link #strandedInside}.
     */
    @Nullable private RanchBuilding strandedRanch;

    /** The animals this butcher holds {@link RanchClaims} claims on, so ranchers leave them be. */
    private final List<UUID> claimed = new ArrayList<>();

    // =========================================================================
    // WorkActivity contract
    // =========================================================================

    @Override
    protected Status doTick(Context ctx) {
        Status status = tickWork(ctx);
        syncClaims(ctx);
        return status;
    }

    /** Holds claims on the current target and on an animal waiting in the Butchery, refreshed each tick. */
    private void syncClaims(Context ctx) {
        UUID me = ctx.npc().getUUID();
        long now = ctx.level().getGameTime();
        List<UUID> wanted = new ArrayList<>(2);
        if (target != null && target.isAlive()) wanted.add(target.getUUID());
        if (strandedInside != null && !wanted.contains(strandedInside)) wanted.add(strandedInside);
        for (UUID old : claimed) if (!wanted.contains(old)) RanchClaims.release(old, me);
        for (UUID id : wanted) RanchClaims.claim(id, me, now);
        claimed.clear();
        claimed.addAll(wanted);
    }

    private Status tickWork(Context ctx) {
        // Working off: nothing new starts, but an animal on the lead or under
        // the blade is finished first and its drops gathered.
        if (!holdsThroughSettingsChange() && sub == null && move == null && target == null
                && !settings(ctx).getBool(ButcherSettingsSchema.WORK_ENABLED)) {
            return Status.DONE; // duty disabled and nothing in-flight to clean up
        }

        if (fetchTool != null) {
            Status status = fetchTool.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            fetchTool.stop(ctx);
            fetchTool = null;
            boolean sword = fetching == ButcherSword.REQUIREMENT;
            fetching = null;
            if (sword ? ButcherSword.slot(ctx.npc()) < 0 : !LeadSupply.hasLead(ctx.npc())) {
                ButcherStatus.set(ctx, sword ? ButcherStatus.NEEDS_SWORD : ButcherStatus.NEEDS_LEAD);
                return Status.FAILED;
            }
        }

        return switch (state) {
            case PICK -> tickPick(ctx);
            case GO_TO_ANIMAL -> tickGoToAnimal(ctx);
            case LEASH -> tickLeash(ctx);
            case LEAD_OUT -> tickLeadOut(ctx);
            case WALK_BACK -> tickWalkBack(ctx);
            case ENTER -> tickEnter(ctx);
            case KILL -> tickKill(ctx);
            case COLLECT -> tickCollect(ctx);
            case SHARPEN -> tickSharpen(ctx);
            case DEPOSIT -> tickDeposit(ctx);
            case STRAY -> tickStray(ctx);
        };
    }

    @Override
    public void doStop(Context ctx) {
        if (leading && target != null) abandonLead(ctx, Kind.CALLED_AWAY, "the shift was interrupted");
        if (doorways != null && ctx.level() instanceof ServerLevel level) {
            doorways.releaseAll(level, ctx.npc(), target);
            doorways = null;
        }
        if (fetchTool != null) { fetchTool.stop(ctx); fetchTool = null; fetching = null; }
        if (strayJob != null) { strayJob.stop(ctx); strayJob = null; }
        if (sub != null) { sub.stop(ctx); sub = null; }
        if (move != null) { move.stop(ctx); move = null; }
        // An animal left waiting in the Butchery is not forgotten with the shift: its pen takes it home.
        Animal waiting = strandedAnimal(ctx);
        if (waiting != null) giveUpWaiting(ctx, waiting, "the shift ended");
        UUID me = ctx.npc().getUUID();
        for (UUID id : claimed) RanchClaims.release(id, me);
        claimed.clear();
        target = null;
        showNothing(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return move != null && !leading && state != State.KILL;
    }

    /** Not while an animal is on the lead or under the blade; danger, fleeing and the alarm still stop it. */
    @Override
    public boolean allowsRoutineInterruption() {
        return !leading() && state != State.KILL && state != State.COLLECT && actTimer <= 0;
    }

    @Override
    public boolean allowsChatWhileWalking() {
        return !leading();
    }

    /**
     * A settings change, Working off among them, never makes the butcher let
     * an animal go: one on the lead or under the blade is finished, and its
     * drops gathered, before the new settings count.
     */
    @Override
    public boolean holdsThroughSettingsChange() {
        return leading() || state == State.KILL || dropsAt != null;
    }

    /** An animal on this butcher's lead: its own cull, or a stray it is bringing home. */
    private boolean leading() {
        return leading || strayJob != null && strayJob.isLeading();
    }

    // =========================================================================
    // States
    // =========================================================================

    private Status tickPick(Context ctx) {
        if (!LeadSupply.hasLead(ctx.npc())) {
            if (!GetToolActivity.canAcquire(ctx, LeadSupply.REQUIREMENT)) {
                ButcherStatus.set(ctx, ButcherStatus.NEEDS_LEAD);
                return finishSession(ctx);
            }
            fetching = LeadSupply.REQUIREMENT;
            fetchTool = new GetToolActivity(LeadSupply.REQUIREMENT);
            return Status.RUNNING;
        }
        // The kill is real blows: no sword, no work.
        if (ButcherSword.slot(ctx.npc()) < 0) {
            if (!GetToolActivity.canAcquire(ctx, ButcherSword.REQUIREMENT)) {
                ButcherStatus.set(ctx, ButcherStatus.NEEDS_SWORD);
                return finishSession(ctx);
            }
            fetching = ButcherSword.REQUIREMENT;
            fetchTool = new GetToolActivity(ButcherSword.REQUIREMENT);
            return Status.RUNNING;
        }
        // Drops left on the floor when the pack was full come first.
        if (dropsAt != null) {
            beginCollect(ctx, "back for the drops left on the floor");
            return Status.RUNNING;
        }
        // An animal left in the Butchery when the pack was full comes first.
        Animal stranded = strandedAnimal(ctx);
        if (stranded != null && !butchery.canButcher()) {
            // No block to take it to any more: its pen takes it home instead.
            endWait(ctx, stranded);
            diary(ctx, "BUTCHER gave up the " + name(stranded)
                    + " waiting in the Butchery: the Butchery stopped working, its pen takes it back");
            stranded = null;
        }
        if (stranded != null && roomFor(ctx, RanchAnimals.meatFor(stranded.getType())) >= MIN_MEAT_ROOM) {
            target = stranded;
            ranch = strandedRanch; // its own pen, so a failed fetch is handed back to that one
            beginChase(ctx, "fetching the " + name(stranded) + " left in the Butchery");
            return Status.RUNNING;
        }
        if (culledThisSession >= cullsPerShift(ctx)) return goDeposit(ctx);

        boolean[] noRoom = new boolean[1];
        String[] problem = new String[1];
        Pick pick = findCull(ctx, noRoom, problem);
        if (pick == null) {
            if (noRoom[0]) {
                if (DepositActivity.countDepositable(ctx) > 0) return goDeposit(ctx);
                // Nothing can be banked, so the pack stays full: say why.
                ButcherStatus.set(ctx, fullPackStatus(ctx));
                return finishSession(ctx);
            }
            // Nothing to cull: an idle butcher brings home a pen animal that got out.
            RanchStrays.Stray stray = RanchStrays.strayFor(ctx);
            if (stray != null) {
                strayJob = new BringBackStrayActivity(stray.ranch(), stray.animal());
                transition(ctx, State.STRAY, "nothing to cull, bringing back a stray");
                return Status.RUNNING;
            }
            // Every pen with a spare animal had a problem: show the first one.
            if (problem[0] != null) ButcherStatus.set(ctx, problem[0]);
            return finishSession(ctx);
        }
        target = pick.animal();
        ranch = pick.ranch();
        butchery = pick.butchery();
        beginChase(ctx, "picked " + name(target));
        return Status.RUNNING;
    }

    private void beginChase(Context ctx, String why) {
        chaseSince = ctx.clock();
        repaths = 0;
        walkBacks = 0;
        routeFails = 0;
        move = null;
        farLead = false;
        footsteps.clear();
        transition(ctx, State.GO_TO_ANIMAL, why);
    }

    private Status tickGoToAnimal(Context ctx) {
        if (target == null || !target.isAlive()) {
            dropTarget(ctx, "target gone");
            return Status.RUNNING;
        }
        if (ctx.npc().distanceToSqr(target) <= REACH_SQ) {
            if (move != null) { move.stop(ctx); move = null; }
            transition(ctx, State.LEASH, "reached " + name(target));
            return Status.RUNNING;
        }
        footsteps.record(ctx.npc());
        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) {
                if (chaseDest != null && target.blockPosition().distSqr(chaseDest) > 25.0) {
                    move.stop(ctx);
                    move = null; // stale leg: re-path from wherever we are
                }
                return Status.RUNNING;
            }
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED && ++repaths > MAX_CHASE_REPATHS) {
                if (leadFromHere(ctx)) return Status.RUNNING;
                RanchClaims.markUnreachable(ctx.npc().getUUID(), target.getUUID(), ctx.level().getGameTime());
                LeadWalk.log(target, "butcher could not reach it, " + String.format("%.1f", ctx.npc().distanceTo(target)) + " away, in sight " + ctx.npc().hasLineOfSight(target));
                dropTarget(ctx, "could not reach " + name(target));
            }
            return Status.RUNNING;
        }
        // A fresh walk after a wandering animal is no failure; only time is.
        if (ctx.clock() - chaseSince > CHASE_LIMIT_TICKS) {
            if (leadFromHere(ctx)) return Status.RUNNING;
            RanchClaims.markUnreachable(ctx.npc().getUUID(), target.getUUID(), ctx.level().getGameTime());
            LeadWalk.log(target, "butcher gave up chasing it after 30 s, " + String.format("%.1f", ctx.npc().distanceTo(target)) + " away, in sight " + ctx.npc().hasLineOfSight(target));
            dropTarget(ctx, "gave up chasing " + name(target));
            return Status.RUNNING;
        }
        chaseDest = target.blockPosition();
        move = new MoveActivity(chaseDest, CHASE_SPEED);
        return Status.RUNNING;
    }

    /**
     * No way up to the animal (stuck in a hole, behind a fence corner): the
     * lead goes on from where the butcher stands if the animal is in sight
     * and within {@link Footsteps#FAR_LEAD_RANGE}. It then climbs out toward
     * the butcher, or its stuck rules bring it up onto the butcher's last
     * footsteps. False when it is too far or out of sight.
     */
    private boolean leadFromHere(Context ctx) {
        if (farLead || !footsteps.canLeadFromHere(ctx.npc(), target)) return false;
        if (move != null) { move.stop(ctx); move = null; }
        farLead = true;
        transition(ctx, State.LEASH, "could not walk up to " + name(target) + "; leading it from here");
        return true;
    }

    private Status tickLeash(Context ctx) {
        if (target == null || !target.isAlive()) {
            dropTarget(ctx, "target gone before the lead went on");
            return Status.RUNNING;
        }
        if (!farLead && ctx.npc().distanceToSqr(target) > REACH_SQ) {
            transition(ctx, State.GO_TO_ANIMAL, name(target) + " moved off");
            return Status.RUNNING;
        }
        boolean fromStranded = target.getUUID().equals(strandedInside);
        // A player named or saddled it while it waited: a pet or a ride, never culled.
        if (fromStranded && isPlayersAnimal(target)) {
            dropTarget(ctx, name(target) + whyPlayers(target)); // ends the wait: its pen takes it back
            return Status.RUNNING;
        }
        if (!fromStranded && !stillCullable(ctx, target)) {
            dropTarget(ctx, name(target) + " is no longer spare");
            return Status.RUNNING;
        }
        ButcheryBuilding place = butchery != null && butchery.canButcher() ? butchery : null;
        if (place == null) {
            dropTarget(ctx, "the Butchery is gone");
            return Status.RUNNING;
        }
        ctx.npc().getLookControl().setLookAt(target, 30f, 30f);
        if (!LedAnimals.start(target, ctx.npc())) {
            dropTarget(ctx, "could not put the lead on " + name(target));
            return Status.RUNNING;
        }
        leading = true;
        putInNo = null;
        if (farLead) {
            LeadWalk walk = LedAnimals.trailOf(target);
            if (walk != null) walk.seedFootsteps(footsteps.near(ctx.npc()));
        }
        showLead(ctx);
        doorways = new LeadDoorways();
        spots = freeSpots(ctx, place);
        startLeadMove(ctx);
        waitTicks = 0;
        transition(ctx, State.LEAD_OUT, "leading " + name(target) + " to the Butchery");
        return Status.RUNNING;
    }

    private void startLeadMove(Context ctx) {
        if (move != null) move.stop(ctx);
        BlockPos dest = spots != null ? spots.killSpot() : butchery.killSpot();
        // Onto the tile beside the block itself: "within a block of it" stopped the
        // butcher a tile short, by the door, and the animal behind it waited outside.
        move = new MoveActivity(dest, LEAD_SPEED, true, true);
    }

    private Status tickLeadOut(Context ctx) {
        LeadWalk.Result result = tickLeadSafety(ctx);
        if (result == null) return Status.RUNNING; // gave up or lost: already handled
        // In the room on the last steps, and the stuck rules or the door clock ran out: not given up.
        if (result == LeadWalk.Result.STUCK) return killOnTheWay(ctx);

        LeadWalk walk = LedAnimals.trailOf(target);
        int behind = walk == null ? 0 : walk.crumbsBehind();
        boolean waiting = waitTicks > 0;
        if (result == LeadWalk.Result.TOO_FAR || behind >= WAIT_BEHIND || (waiting && behind > RESUME_BEHIND)) {
            ctx.model().holdStillForCare();
            if (++waitTicks >= WAIT_LIMIT) {
                if (walkBacks >= MAX_WALK_BACKS) {
                    abandonLead(ctx, Kind.FELL_BEHIND, name(target) + " could not keep up");
                    return Status.RUNNING;
                }
                walkBacks++;
                waitTicks = 0;
                if (move != null) { move.stop(ctx); move = null; }
                if (walk != null) walk.restartFrom(target.position());
                move = new MoveActivity(target.blockPosition(), LEAD_SPEED);
                transition(ctx, State.WALK_BACK, "walking back to " + name(target));
            }
            return Status.RUNNING;
        }
        waitTicks = 0;

        if (move == null) startLeadMove(ctx);
        Status status = move.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        move.stop(ctx);
        move = null;
        if (status == Status.FAILED) {
            if (++routeFails >= MAX_ROUTE_FAILS) {
                abandonLead(ctx, Kind.NO_WAY, "no way through to the Butchery");
            }
            return Status.RUNNING;
        }
        enterTicks = 0;
        transition(ctx, State.ENTER, "at the chopping block, bringing " + name(target) + " in");
        return Status.RUNNING;
    }

    private Status tickWalkBack(Context ctx) {
        LeadWalk.Result result = tickLeadSafety(ctx);
        if (result == null) return Status.RUNNING;
        if (result == LeadWalk.Result.STUCK) return killOnTheWay(ctx);
        boolean close = ctx.npc().distanceToSqr(target) <= REACH_SQ;
        Status status = close ? Status.DONE : move == null ? Status.FAILED : move.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        if (move != null) { move.stop(ctx); move = null; }
        if (!close && ctx.npc().distanceToSqr(target) > 16.0) {
            abandonLead(ctx, Kind.FELL_BEHIND, "could not get back to " + name(target));
            return Status.RUNNING;
        }
        LeadWalk walk = LedAnimals.trailOf(target);
        if (walk != null) walk.restartFrom(target.position());
        startLeadMove(ctx);
        transition(ctx, State.LEAD_OUT, "back with " + name(target) + ", leading on");
        return Status.RUNNING;
    }

    /** The kill where it stands from the walk in or the walk back: the butcher stops walking first. */
    private Status killOnTheWay(Context ctx) {
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
        waitTicks = 0;
        return beginKill(ctx);
    }

    private Status tickEnter(Context ctx) {
        LeadWalk.Result result = tickLeadSafety(ctx);
        if (result == null) return Status.RUNNING;
        // In the room, and the stuck rules or the door clock ran out: not given up.
        if (result == LeadWalk.Result.STUCK) return beginKill(ctx);
        BlockPos animalSpot = spots != null ? spots.animalSpot() : butchery.animalSpot();
        BlockPos killSpot = spots != null ? spots.killSpot() : butchery.killSpot();
        Vec3 spot = animalSpot == null ? null : animalStand(ctx, animalSpot, killSpot);
        ctx.npc().getLookControl().setLookAt(target, 30f, 30f);
        LeadWalk walk = LedAnimals.trailOf(target);
        // As far as the trail takes it: all of it walked, or waiting at the
        // butcher's heels with the last crumbs too near the butcher to step on.
        // Only the first used to count, and an animal at the heels keeps its
        // last crumbs, so it stood in the doorway until it was put in.
        boolean walkedUp = walk == null || walk.crumbsBehind() == 0 || walk.waitingAtHeels();
        boolean inside = onRoomTile(butchery.getNavMeshBlocks(), target);
        if (!inside && walkedUp && spot != null && target.distanceTo(ctx.npc()) <= 3.0) {
            // The trail ends where the butcher stands and the animal stops short
            // of it: in the doorway, when the block is near the door. It walks
            // on through the door to the first tile inside, then to its spot;
            // straight to its spot when the butcher stands on that first tile.
            BlockPos entry = butchery.entryTile();
            boolean entryFree = butchery.getNavMeshBlocks().contains(entry)
                    && !entry.equals(NavGraph.feetCell(ctx.npc()));
            Vec3 in = entryFree ? Vec3.atBottomCenterOf(entry) : spot;
            lastSteps(ctx, walk, in);
        }
        if (inside) {
            // In the room: the last steps go onto its spot beside the block, not
            // along the trail (which ends where the butcher stands), and the
            // door can shut behind it.
            if (spot != null) lastSteps(ctx, walk, spot);
            if (doorways != null && ctx.level() instanceof ServerLevel level) {
                doorways.closeClearOf(level, ctx.npc(), target);
            }
        }
        boolean onSpot = spot != null && horizontalDist(target.position(), spot) <= 0.7;
        enterTicks++;
        if (inside && (onSpot || (enterTicks >= 60 && target.distanceTo(ctx.npc()) <= 2.0))) {
            return beginKill(ctx);
        }
        // The butcher is at the block and the animal still outside after 5 s:
        // it is put in (tried once a second until it fits somewhere).
        if (!inside && enterTicks >= PUT_IN_TICKS && enterTicks % 20 == 0
                && putInside(ctx, "still outside after " + enterTicks / 20 + " s")) {
            enterTicks = 0;
            return Status.RUNNING;
        }
        if (enterTicks >= ENTER_LIMIT) {
            if (inside && target.distanceTo(ctx.npc()) <= 3.0) {
                return beginKill(ctx); // not on its spot, but in the room: done where it stands
            }
            abandonLead(ctx, Kind.WOULD_NOT_COME_IN, name(target) + " would not come into the Butchery");
        }
        return Status.RUNNING;
    }

    /**
     * The butcher stands in the Butchery and the animal on its lead is stuck
     * outside: a single door is narrower than a cow or pig (an open leaf
     * leaves 13/16 of a block, they are 0.9 wide), so no hop or bring-up can
     * get it through. It is put in: on its spot by the block, else the tile
     * inside the door, else the room tile nearest its spot that it fits on,
     * never on the butcher. False when the butcher is not inside, the animal
     * is in already or more than {@link #PUT_IN_RANGE} away, or nothing fits.
     */
    private boolean putInside(Context ctx, String why) {
        if (target == null || butchery == null) return false;
        Set<BlockPos> room = butchery.getNavMeshBlocks();
        if (!onRoomTile(room, ctx.npc())) return putInNo("the butcher is not in the room");
        if (onRoomTile(room, target)) return putInNo("it is in the room already");
        if (target.distanceTo(ctx.npc()) > PUT_IN_RANGE) {
            return putInNo(String.format("it is %.1f away (over %.0f)", target.distanceTo(ctx.npc()), PUT_IN_RANGE));
        }
        LeadWalk walk = LedAnimals.trailOf(target);
        if (walk == null) return putInNo("it has no trail");
        BlockPos animalSpot = spots != null ? spots.animalSpot() : butchery.animalSpot();
        BlockPos killSpot = spots != null ? spots.killSpot() : butchery.killSpot();
        Vec3 first = animalSpot == null ? null : animalStand(ctx, animalSpot, killSpot);
        List<Vec3> tries = new ArrayList<>();
        if (first != null) tries.add(first);
        BlockPos entry = butchery.entryTile();
        if (entry != null && room.contains(entry)) tries.add(Vec3.atBottomCenterOf(entry));
        Vec3 near = first != null ? first : ctx.npc().position();
        List<BlockPos> rest = new ArrayList<>(room);
        rest.sort(java.util.Comparator.comparingDouble(p -> Vec3.atBottomCenterOf(p).distanceToSqr(near)));
        for (BlockPos tile : rest) tries.add(Vec3.atBottomCenterOf(tile));
        double keepOff = LeadWalk.keepOff(ctx.npc(), target);
        for (Vec3 spot : tries) {
            if (horizontalDist(spot, ctx.npc().position()) < keepOff) continue;
            if (walk.placeAt(target, spot)) {
                LeadWalk.log(target, "put inside the Butchery: " + why);
                diary(ctx, "BUTCHER " + name(target) + " was stuck outside; put it in (" + why + ")");
                putInNo = null;
                return true;
            }
        }
        LeadWalk.log(target, "could not put it inside the Butchery: no room tile fits it (" + why + ")");
        return putInNo("no room tile fits it");
    }

    /** Keeps why {@link #putInside} said no; always false. */
    private boolean putInNo(String why) {
        putInNo = why;
        return false;
    }

    /**
     * Whether {@code body} stands on one of {@code room}'s tiles. A tile is the
     * feet cell over its floor block, a stair counted full height, but a body
     * on a stair's lower step or a bottom slab stands half a block lower: its
     * {@code blockPosition()} is that stair or slab, one under the tile. So the
     * walker's feet cell ({@link NavGraph#feetCell}) counts too.
     */
    private static boolean onRoomTile(Set<BlockPos> room, Entity body) {
        return room.contains(body.blockPosition()) || room.contains(NavGraph.feetCell(body));
    }

    /** The animal's last steps onto {@code to}, with the lead walk's stuck help (hop, slide, put on the spot). */
    private void lastSteps(Context ctx, @Nullable LeadWalk walk, Vec3 to) {
        if (walk == null) {
            target.getMoveControl().setWantedPosition(to.x, to.y, to.z, 1.0);
        } else if (walk.lastSteps(target, to, 1.0)) {
            diary(ctx, "BUTCHER " + name(target) + " was stuck in the doorway; put it through");
        }
    }

    /**
     * Where the animal stands: its spot, moved off the kill spot far enough
     * to leave half a block between it and the butcher. The two tiles are
     * side by side, which put a cow's flank 0.25 from the butcher.
     */
    private Vec3 animalStand(Context ctx, BlockPos animalSpot, @Nullable BlockPos killSpot) {
        Vec3 spot = Vec3.atBottomCenterOf(animalSpot);
        if (killSpot == null || target == null) return spot;
        Vec3 away = spot.subtract(Vec3.atBottomCenterOf(killSpot)).multiply(1, 0, 1);
        double apart = away.length();
        double extra = LeadWalk.keepOff(ctx.npc(), target) - apart;
        if (apart < 1.0e-3 || extra <= 0) return spot;
        return spot.add(away.scale(extra / apart));
    }

    private Status beginKill(Context ctx) {
        Animal animal = target;
        Item meat = RanchAnimals.meatFor(animal.getType());
        if (meat == null || roomFor(ctx, meat) < MIN_MEAT_ROOM) {
            // The pack filled up on the way: let it wait here, bank, come back for it.
            LedAnimals.release(animal, "pack full, waiting in the Butchery");
            leading = false;
            closeDoorways(ctx);
            // Only one waits at a time: one already waiting is handed back to its pen first.
            if (strandedInside != null && !strandedInside.equals(animal.getUUID())) {
                Animal first = strandedAnimal(ctx);
                if (first != null) giveUpWaiting(ctx, first, "another animal has to wait in its place");
            }
            strandedInside = animal.getUUID();
            strandedRanch = ranch;
            target = null;
            diary(ctx, "BUTCHER pack full; left " + name(animal) + " in the Butchery, banking first");
            return goDeposit(ctx);
        }
        blows = 0;
        startSwing(ctx);
        transition(ctx, State.KILL, "killing " + name(animal));
        return Status.RUNNING;
    }

    /** One overhead chop onto the block, the blade landing {@link #STRIKE_TICKS} in. */
    private void startSwing(Context ctx) {
        equipSword(ctx);
        ctx.npc().swing(InteractionHand.MAIN_HAND);
        ctx.npc().startChopAnimation(BLOW_TICKS);
        actTimer = BLOW_TICKS;
    }

    private Status tickKill(Context ctx) {
        // Down between blows (it burned): the kill is still the butcher's.
        if (target != null && blows > 0 && target.isDeadOrDying()) return onKilled(ctx, target);
        if (target == null || !target.isAlive() || !LedAnimals.isLedBy(target, ctx.npc())) {
            leading = false;
            actTimer = 0;
            closeDoorways(ctx);
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            showNothing(ctx);
            // Still alive at the block with no lead on it: its pen takes it back.
            if (target != null) letGoToPen(ctx, ranch, target);
            dropTarget(ctx, "lost the animal at the block");
            return Status.RUNNING;
        }
        LedAnimals.tickWalk(ctx.npc(), target); // keeps the lead alive; the trail is empty, it stands
        tickDoorways(ctx);
        ctx.npc().getLookControl().setLookAt(target, 30f, 30f);
        actTimer--;
        if (actTimer == BLOW_TICKS - STRIKE_TICKS) {
            blows++;
            if (strike(ctx, target)) return onKilled(ctx, target);
            if (blows >= MAX_BLOWS) {
                actTimer = 0;
                abandonLead(ctx, Kind.WOULD_NOT_GO_DOWN, name(target) + " would not go down");
                return Status.RUNNING;
            }
        }
        if (actTimer <= 0) startSwing(ctx);
        return Status.RUNNING;
    }

    /**
     * One blow, the vanilla way: the sword's damage (Sharpness counted) as a
     * mob's attack, a critical from Clean Kill, Fire Aspect setting it alight
     * first so the meat comes off cooked. True when it went down.
     */
    private boolean strike(Context ctx, Animal animal) {
        int slot = ButcherSword.slot(ctx.npc());
        ItemStack sword = slot >= 0 ? ctx.npc().getInventory().getSlot(slot) : ItemStack.EMPTY;
        int level = skillLevel(ctx);
        boolean crit = ButcherBalance.alwaysCrit(level);
        float damage = ButcherBalance.blowDamage(level, GuardSwordUtils.attackDamage(sword));
        double fire = ToolSelectionHelper.effectiveEnchantmentLevel(sword, Enchantments.FIRE_ASPECT);
        if (fire > 0) animal.igniteForSeconds((float) (4.0 * fire));
        // A player's sword: Looting on the kill at its share (PlayerGear).
        boolean landed = com.alex.test.village.tokens.PlayerGear.withWorkingMainHand(ctx.npc(),
                () -> animal.hurt(ctx.npc().damageSources().mobAttack(ctx.npc()), damage));
        if (landed) {
            ctx.level().playSound(null, animal.blockPosition(),
                    crit ? SoundEvents.PLAYER_ATTACK_CRIT : SoundEvents.PLAYER_ATTACK_STRONG,
                    SoundSource.NEUTRAL, 1.0f, 1.0f);
            if (crit && ctx.level() instanceof ServerLevel server) {
                server.sendParticles(ParticleTypes.CRIT, animal.getX(), animal.getY(0.6), animal.getZ(),
                        8, 0.3, 0.3, 0.3, 0.1);
            }
            wearSword(ctx);
        }
        return animal.isDeadOrDying();
    }

    /** It went down: the vanilla death has dropped its loot; gather it. */
    private Status onKilled(Context ctx, Animal animal) {
        if (LedAnimals.isLedBy(animal, ctx.npc())) LedAnimals.release(animal, "killed");
        leading = false;
        actTimer = 0;
        if (animal.getUUID().equals(strandedInside)) {
            strandedInside = null;
            strandedRanch = null;
        }
        closeDoorways(ctx);
        showNothing(ctx);
        target = null;
        workedThisSession = true;
        culledThisSession++;
        killNote = name(animal) + " (lvl " + skillLevel(ctx) + ", " + blows
                + (blows == 1 ? " blow)" : " blows)");
        haul.clear();
        bankedForDrops = false;
        dropsAt = animal.blockPosition();
        ctx.brain().onButcherKill(ctx);
        beginCollect(ctx, "killed " + killNote + ", gathering the drops");
        return Status.RUNNING;
    }

    private void beginCollect(Context ctx, String why) {
        collectTicks = 0;
        // A new gathering tries every drop again, leftovers from before included.
        skippedDrops.clear();
        walkingTo = null;
        if (move != null) { move.stop(ctx); move = null; }
        transition(ctx, State.COLLECT, why);
    }

    /**
     * Picks up what the kill dropped (only drops marked for this butcher,
     * {@link ButcherDrops}). A full pack banks first and comes back for the
     * rest. A drop it walks to and cannot reach is skipped and the others are
     * still gathered; the whole gathering ends after {@link #COLLECT_LIMIT}.
     */
    private Status tickCollect(Context ctx) {
        if (dropsAt == null) return afterKill(ctx);
        if (++collectTicks < DROP_SETTLE_TICKS) {
            ctx.model().holdStillForCare();
            return Status.RUNNING;
        }
        ItemEntity next = collectTicks > COLLECT_LIMIT ? null : nearestDrop(ctx);
        if (next == null) {
            if (move != null) { move.stop(ctx); move = null; }
            boolean leftSome = collectTicks > COLLECT_LIMIT || !skippedDrops.isEmpty();
            logHaul(ctx, leftSome ? " (left some, out of reach)" : "");
            dropsAt = null;
            return afterKill(ctx);
        }
        if (ctx.npc().distanceToSqr(next) <= REACH_SQ) {
            if (move != null) { move.stop(ctx); move = null; }
            walkingTo = null;
            if (!take(ctx, next)) {
                if (bankedForDrops) {
                    // Banked and still no room (storage full): leave the rest.
                    logHaul(ctx, " (pack still full, left the rest)");
                    dropsAt = null;
                    return afterKill(ctx);
                }
                bankedForDrops = true;
                logHaul(ctx, " (pack full, banking first)");
                return goDeposit(ctx);
            }
            return Status.RUNNING;
        }
        if (move == null) {
            move = new MoveActivity(next.blockPosition(), CHASE_SPEED, true);
            walkingTo = next;
        }
        Status status = move.tickOuter(ctx);
        if (status != Status.RUNNING) {
            move.stop(ctx);
            move = null;
            // The walk is over (arrived or no way): a drop still out of reach is left, the others are still gathered.
            ItemEntity tried = walkingTo;
            walkingTo = null;
            if (tried != null && tried.isAlive() && ctx.npc().distanceToSqr(tried) > REACH_SQ) {
                skippedDrops.add(tried.getUUID());
            }
        }
        return Status.RUNNING;
    }

    @Nullable
    private ItemEntity nearestDrop(Context ctx) {
        UUID me = ctx.npc().getUUID();
        AABB box = new AABB(dropsAt).inflate(COLLECT_RADIUS);
        ItemEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (ItemEntity item : ctx.level().getEntitiesOfClass(ItemEntity.class, box,
                e -> e.isAlive() && ButcherDrops.isFor(e, me) && !skippedDrops.contains(e.getUUID()))) {
            double sq = ctx.npc().distanceToSqr(item);
            if (sq < bestSq) {
                bestSq = sq;
                best = item;
            }
        }
        return best;
    }

    /** As much of {@code item} as fits, into the pack as the village's. False when none fits. */
    private boolean take(Context ctx, ItemEntity item) {
        ItemStack stack = item.getItem();
        ItemStack owned = stack.copy();
        // Not mayUse: raw meat is also "a player's food", which villagers may use unbound, so the
        // meat of a kill stayed plain (the Cook would not touch it, the Merchant would not buy it).
        if (!com.alex.test.village.tokens.VillageTokenBinding.isBound(owned)) {
            owned = com.alex.test.village.tokens.VillageItemOwnership.produce(owned, ctx.mgr());
        }
        int fit = owned.isEmpty() ? 0 : Math.min(owned.getCount(), ctx.npc().getInventory().capacityFor(owned));
        if (fit <= 0) return false;
        ctx.npc().getInventory().addItem(owned.copyWithCount(fit));
        haul.merge(stack.getItem(), fit, Integer::sum);
        if (fit >= stack.getCount()) {
            item.discard();
        } else {
            ItemStack rest = stack.copy();
            rest.shrink(fit);
            item.setItem(rest);
        }
        ctx.npc().getLookControl().setLookAt(item, 30f, 30f);
        ctx.level().playSound(null, ctx.npc().blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL,
                0.2f, 1.4f + ctx.npc().getRandom().nextFloat() * 0.4f);
        return true;
    }

    private void logHaul(Context ctx, String suffix) {
        StringBuilder line = new StringBuilder("BUTCHER culled ")
                .append(killNote == null ? "an animal" : killNote).append(" ->");
        if (haul.isEmpty()) line.append(" nothing");
        boolean first = true;
        for (Map.Entry<Item, Integer> entry : haul.entrySet()) {
            line.append(first ? " " : ", ").append(entry.getValue()).append("x ")
                    .append(new ItemStack(entry.getKey()).getHoverName().getString());
            first = false;
        }
        diary(ctx, line.append(suffix).toString());
        haul.clear();
    }

    /** After a kill's drops are in: the grindstone once a day if the sword is worn, else the next animal. */
    private Status afterKill(Context ctx) {
        // Working off: the shift ends at the next tick, and today's grindstone visit is kept for later.
        if (settings(ctx).getBool(ButcherSettingsSchema.WORK_ENABLED)
                && !sharpenedToday(ctx) && wantsSharpening(ctx) && butchery != null && butchery.getGrindstone() != null) {
            ctx.npc().getBrainData().putLong(TAG_SHARPEN_DAY, today(ctx));
            actTimer = 0;
            move = null;
            transition(ctx, State.SHARPEN, "sword is dull, to the grindstone");
            return Status.RUNNING;
        }
        transition(ctx, State.PICK, culledThisSession >= cullsPerShift(ctx) ? "shift's culls done" : "next animal");
        return Status.RUNNING;
    }

    private Status tickSharpen(Context ctx) {
        BlockPos grindstone = butchery == null ? null : butchery.getGrindstone();
        NavGraph graph = ctx.mgr().getGraph();
        if (grindstone == null || graph == null) {
            transition(ctx, State.PICK, "no grindstone after all");
            return Status.RUNNING;
        }
        if (actTimer == 0) {
            if (move == null) {
                BlockPos stand = butchery.getWalkTargetNear(grindstone, graph, ctx.npc().blockPosition(), 2);
                if (stand == null) {
                    transition(ctx, State.PICK, "cannot stand at the grindstone");
                    return Status.RUNNING;
                }
                move = new MoveActivity(stand, CHASE_SPEED, true);
            }
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED) {
                transition(ctx, State.PICK, "could not reach the grindstone");
                return Status.RUNNING;
            }
            equipSword(ctx);
            ctx.npc().setAnimation(VillagerAnimationState.CRAFTING);
            actTimer = SHARPEN_TICKS;
        }
        ctx.npc().getLookControl().setLookAt(Vec3.atCenterOf(grindstone));
        if (actTimer % 10 == 0 && ctx.level() instanceof ServerLevel server) grindstoneEffects(server, grindstone);
        if (--actTimer > 0) return Status.RUNNING;
        sharpen(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        transition(ctx, State.PICK, "sword sharpened");
        return Status.RUNNING;
    }

    private Status tickStray(Context ctx) {
        if (strayJob == null) {
            transition(ctx, State.PICK, "stray job gone");
            return Status.RUNNING;
        }
        Status status = strayJob.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        strayJob.stop(ctx);
        strayJob = null;
        if (status == Status.DONE) {
            workedThisSession = true;
            ctx.brain().onButcherStray(ctx);
        }
        transition(ctx, State.PICK, status == Status.DONE ? "stray home" : "stray not brought back");
        return Status.RUNNING;
    }

    private Status goDeposit(Context ctx) {
        if (DepositActivity.countDepositable(ctx) <= 0) return finishSession(ctx);
        sub = new DepositActivity();
        transition(ctx, State.DEPOSIT, "banking the pack");
        return Status.RUNNING;
    }

    private Status tickDeposit(Context ctx) {
        if (sub == null) return finishSession(ctx);
        Status status = sub.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        sub.stop(ctx);
        sub = null;
        // Banked with drops still on the floor: go back for them.
        if (dropsAt != null) {
            transition(ctx, State.PICK, "back for the drops on the floor");
            return Status.RUNNING;
        }
        // Banked with an animal still waiting in the Butchery: go back for it.
        Animal stranded = strandedAnimal(ctx);
        if (stranded != null && roomFor(ctx, RanchAnimals.meatFor(stranded.getType())) >= MIN_MEAT_ROOM) {
            transition(ctx, State.PICK, "back for the animal in the Butchery");
            return Status.RUNNING;
        }
        return workedThisSession ? Status.DONE : Status.FAILED;
    }

    private Status finishSession(Context ctx) {
        if (DepositActivity.countDepositable(ctx) > 0 && state != State.DEPOSIT) return goDeposit(ctx);
        return workedThisSession ? Status.DONE : Status.FAILED;
    }

    // =========================================================================
    // Leading: the shared per-tick checks and the one way a lead is given up
    // =========================================================================

    /**
     * Steers the animal one tick, holds the doorways, and checks every reason
     * to stop. Null when the walk ended here (given up or lost), so the
     * caller must return. STUCK only when the stuck rules or the door clock
     * ran out with the animal in the room already ({@link #inRoomToKill}):
     * the caller kills it where it stands.
     */
    @Nullable
    private LeadWalk.Result tickLeadSafety(Context ctx) {
        if (target == null || !target.isAlive() || !LedAnimals.isLedBy(target, ctx.npc())) {
            Animal lost = target;
            leading = false;
            closeDoorways(ctx);
            showNothing(ctx);
            if (lost != null) letGoToPen(ctx, ranch, lost);
            dropTarget(ctx, "lost the lead on " + (lost == null ? "the animal" : name(lost)));
            return null;
        }
        // A player named or saddled it on the way: a pet or a ride, never culled.
        if (isPlayersAnimal(target)) {
            abandonLead(ctx, Kind.PLAYERS_ANIMAL, name(target) + whyPlayers(target));
            return null;
        }
        if (butchery == null || !butchery.canButcher()) {
            abandonLead(ctx, Kind.ROOM_BROKE, "the Butchery stopped working");
            return null;
        }
        LeadWalk.Result result = LedAnimals.tickWalk(ctx.npc(), target);
        if (result == LeadWalk.Result.LOST) {
            leading = false;
            closeDoorways(ctx);
            showNothing(ctx);
            letGoToPen(ctx, ranch, target); // the lead is off: its pen takes it back
            dropTarget(ctx, "lost " + name(target));
            return null;
        }
        // Stuck outside with the butcher already in: put in, not given up.
        if (result == LeadWalk.Result.STUCK) {
            if (putInside(ctx, "the stuck rules ran out")) return LeadWalk.Result.FOLLOWING;
            if (inRoomToKill(ctx)) return killWhereItStands(ctx, "the stuck rules ran out");
            abandonLead(ctx, Kind.STUCK, name(target) + " got stuck");
            return null;
        }
        if (tickDoorways(ctx)) {
            if (putInside(ctx, "the door was held too long")) return LeadWalk.Result.FOLLOWING;
            if (inRoomToKill(ctx)) return killWhereItStands(ctx, "the door was held too long");
            abandonLead(ctx, Kind.DOOR_HELD, "held a gate open for " + LeadDoorways.MAX_HOLD_TICKS / 20 + " s");
            return null;
        }
        LeadSupply.keepShown(ctx.npc());
        return result;
    }

    /**
     * The animal on a room tile within 3 blocks: at the block, as at the 10 s
     * limit, or (round 10 of the stairs plan) on the last steps there, walking
     * in or back for it, with the butcher inside too. Putting it in says no then
     * (it is in already), and a give-up handed it back to its pen from inside
     * the Butchery.
     */
    private boolean inRoomToKill(Context ctx) {
        if (target == null || butchery == null || target.distanceTo(ctx.npc()) > 3.0) return false;
        Set<BlockPos> room = butchery.getNavMeshBlocks();
        if (!onRoomTile(room, target)) return false;
        return state == State.ENTER
                || (state == State.LEAD_OUT || state == State.WALK_BACK) && onRoomTile(room, ctx.npc());
    }

    /**
     * Notes why the walk-in is over; tickEnter then kills the animal where it
     * stands. Its trail is emptied so it stands still for the blows, and the
     * stuck rules that ran out do not bring it up mid-kill.
     */
    private LeadWalk.Result killWhereItStands(Context ctx, String why) {
        if (state != State.ENTER) why += " before the butcher reached its tile";
        LeadWalk walk = LedAnimals.trailOf(target);
        if (walk != null) walk.restartFrom(ctx.npc().position());
        LeadWalk.log(target, why + " with it in the Butchery: killed where it stands, not given up");
        diary(ctx, "BUTCHER " + name(target) + " in the Butchery but " + why + "; killing it where it stands");
        return LeadWalk.Result.STUCK;
    }

    /** True when a doorway has been held too long. */
    private boolean tickDoorways(Context ctx) {
        if (doorways == null || !(ctx.level() instanceof ServerLevel level) || target == null) return false;
        LeadWalk walk = LedAnimals.trailOf(target);
        if (walk == null) return false;
        return doorways.tick(level, ctx.npc(), target, walk, ctx.clock());
    }

    private void closeDoorways(Context ctx) {
        if (doorways == null || !(ctx.level() instanceof ServerLevel level)) return;
        doorways.releaseAll(level, ctx.npc(), target != null && target.isAlive() ? target : null);
        doorways = null;
    }

    /**
     * Every give-up ends here: the lead comes off (no item), the doorways are
     * let go and shut once clear, the animal is set aside for a minute, and
     * one that ends up outside its pen is noted as a stray to bring home.
     * The Butchery keeps the give-up ({@code kind}, where the animal stood)
     * for its window; {@code reason} is for the diary and the log.
     */
    private void abandonLead(Context ctx, Kind kind, String reason) {
        Animal animal = target;
        if (animal != null && LedAnimals.isLedBy(animal, ctx.npc())) LedAnimals.release(animal, reason);
        leading = false;
        if (move != null) { move.stop(ctx); move = null; }
        closeDoorways(ctx);
        showNothing(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        if (animal != null) {
            RanchClaims.markUnreachable(ctx.npc().getUUID(), animal.getUUID(), ctx.level().getGameTime());
            letGoToPen(ctx, ranch, animal);
            if (animal.getUUID().equals(strandedInside)) {
                strandedInside = null;
                strandedRanch = null;
            }
        }
        diary(ctx, "BUTCHER gave up leading " + (animal == null ? "an animal" : name(animal)) + ": " + reason);
        if (animal != null) {
            LeadWalk.log(animal, "butcher gave up: " + reason + "; " + cellsNote(ctx, animal)
                    + (putInNo != null ? "; not put in: " + putInNo : ""));
            if (butchery != null && butchery.noteTrouble(ButcheryTrouble.at(kind,
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString(),
                    animal.blockPosition(), NavGraph.feetCell(animal), butchery.getNavMeshBlocks(),
                    butchery.getDoorPos(), ctx.level().getGameTime()), today(ctx))) {
                diary(ctx, "BUTCHER " + ButcheryBuilding.PAUSE_GIVE_UPS + " animals given up at "
                        + butchery.getDisplayName() + " within 10 minutes: no more culls there until tomorrow");
                LeadWalk.log(animal, "the Butchery at " + butchery.getDoorPos().toShortString()
                        + " is paused until tomorrow (" + ButcheryBuilding.PAUSE_GIVE_UPS + " give-ups in 10 minutes)");
            }
        }
        putInNo = null;
        target = null;
        waitTicks = 0;
        state = State.PICK;
    }

    /**
     * Both cells of the animal and of the butcher for the give-up's log line,
     * each marked in or out of the room: a body on a stair's lower step or a
     * bottom slab has its block position one under the tile, its feet cell on it.
     */
    private String cellsNote(Context ctx, Animal animal) {
        if (butchery == null) return "no Butchery; butcher at " + ctx.npc().blockPosition().toShortString();
        Set<BlockPos> room = butchery.getNavMeshBlocks();
        return "animal " + cells(room, animal) + ", butcher " + cells(room, ctx.npc());
    }

    private static String cells(Set<BlockPos> room, Entity body) {
        BlockPos raw = body.blockPosition(), feet = NavGraph.feetCell(body);
        String at = raw.toShortString() + (room.contains(raw) ? " in" : " out");
        if (feet.equals(raw)) return "at " + at;
        return "at " + at + ", feet " + feet.toShortString() + (room.contains(feet) ? " in" : " out");
    }

    /**
     * A live animal that is off this butcher's lead goes back to its pen as a
     * let-go stray, so a rancher (or the butcher, with nothing to cull) walks
     * it home, even from as far as the Butchery. With no pen on record, or one
     * that was removed or stopped working since (nobody serves it), any pen
     * that knows the animal takes it. Safe to call twice for one animal.
     */
    private static void letGoToPen(Context ctx, @Nullable RanchBuilding pen, Animal animal) {
        if (!animal.isAlive()) return;
        if (pen != null && pen.isValid() && ctx.mgr().getBuilding(pen.getItemFrameId()) == pen) {
            RanchStrays.letGo(pen, animal, ctx.level().getGameTime());
        } else {
            RanchStrays.onLetGo(ctx.mgr(), ctx.level(), animal);
        }
    }

    /** The wait for the animal left in the Butchery is over: it goes back to its own pen as a let-go stray. */
    private void endWait(Context ctx, Animal animal) {
        letGoToPen(ctx, strandedRanch != null ? strandedRanch : ranch, animal);
        strandedInside = null;
        strandedRanch = null;
    }

    /** {@link #endWait} for a shift that ends, or a second animal that takes the place, with one still waiting: it is not forgotten. */
    private void giveUpWaiting(Context ctx, Animal animal, String why) {
        endWait(ctx, animal);
        diary(ctx, "BUTCHER gave up the " + name(animal) + " waiting in the Butchery: " + why + ", its pen takes it back");
    }

    private void dropTarget(Context ctx, String why) {
        // Fetching the animal left in the Butchery failed: it is not fetched again and again, its pen takes it home.
        if (target != null && target.getUUID().equals(strandedInside)) {
            endWait(ctx, target);
            why += target.isAlive() ? "; given up waiting, its pen takes it back" : "; no longer waiting";
        }
        if (move != null) { move.stop(ctx); move = null; }
        target = null;
        leading = false;
        waitTicks = 0;
        transition(ctx, State.PICK, why);
    }

    // =========================================================================
    // Choosing
    // =========================================================================

    private record Pick(Animal animal, RanchBuilding ranch, ButcheryBuilding butchery) {}

    /** Where a ranch's animals go: its Butchery, or why there is none. */
    private record Place(@Nullable ButcheryBuilding butchery, @Nullable String problem) {}

    /**
     * A surplus adult to cull now, or null when herds are at target or no pen
     * can be worked. A pen whose Butchery is missing or out of reach is
     * skipped for the next one; the first such reason is left in {@code problem[0]}.
     */
    @Nullable
    private static Pick findCull(Context ctx, @Nullable boolean[] noRoom, @Nullable String[] problem) {
        ProfessionSettings settings = settings(ctx);
        int keepAdults = keepAdults(settings);
        UUID me = ctx.npc().getUUID();
        long now = ctx.level().getGameTime();

        pens:
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid()) continue;
            Place place = null; // worked out once, for the first animal this pen offers
            for (List<Animal> herd : herds(ctx, ranch, settings).values()) {
                if (spare(herd, me, now) <= keepAdults) continue;
                // Bank first rather than walk an animal over to a full pack.
                if (roomFor(ctx, RanchAnimals.meatFor(herd.get(0).getType())) < MIN_MEAT_ROOM) {
                    if (noRoom != null) noRoom[0] = true;
                    continue;
                }
                for (Animal candidate : herd) {
                    // Spare in-love animals: they're mid-breeding for the rancher.
                    if (candidate.isInLove() || candidate.isLeashed() || LedAnimals.isLed(candidate)) continue;
                    // A rancher's claim covers the partner it is walking over to
                    // feed; culling that one wasted the food the first ate. Also
                    // skips animals this butcher gave up on lately.
                    if (!RanchClaims.isFree(candidate.getUUID(), me, now)) continue;
                    if (place == null) place = usablePlace(ctx, ranch);
                    if (place.problem() != null) {
                        // This pen cannot be worked: leave it for the next one.
                        if (problem != null && problem[0] == null) problem[0] = place.problem();
                        continue pens;
                    }
                    return new Pick(candidate, ranch, place.butchery());
                }
            }
        }
        return null;
    }

    /** Adults in the pen this butcher may cull, by species. */
    private static Map<EntityType<?>, List<Animal>> herds(Context ctx, RanchBuilding ranch,
                                                           ProfessionSettings settings) {
        Map<EntityType<?>, List<Animal>> adults = new HashMap<>();
        for (Animal animal : ranch.animalsInside(ctx.level())) {
            if (animal.isBaby()) continue;
            if (isPlayersAnimal(animal)) continue; // a player's pet or ride, not stock
            if (RanchAnimals.meatFor(animal.getType()) == null) continue;
            if (!isCullable(settings, animal.getType())) continue;
            adults.computeIfAbsent(animal.getType(), t -> new ArrayList<>()).add(animal);
        }
        return adults;
    }

    /** A player's animal, never culled: named (a pet) or saddled (a ride, its saddle the player's). */
    public static boolean isPlayersAnimal(Animal animal) {
        return animal.hasCustomName() || animal instanceof Saddleable ride && ride.isSaddled();
    }

    private static String whyPlayers(Animal animal) {
        return animal.hasCustomName() ? " was given a name" : " was saddled";
    }

    /** The cull rules again, at the moment the lead goes on: things change on the walk over. */
    private boolean stillCullable(Context ctx, Animal animal) {
        if (animal.isBaby() || isPlayersAnimal(animal) || animal.isInLove() || animal.isLeashed()) return false;
        if (ranch == null || !ranch.isValid()) return false;
        ProfessionSettings settings = settings(ctx);
        if (!isCullable(settings, animal.getType())) return false;
        List<Animal> herd = herds(ctx, ranch, settings).get(animal.getType());
        return herd != null && herd.contains(animal)
                && spare(herd, ctx.npc().getUUID(), ctx.level().getGameTime()) > keepAdults(settings);
    }

    /**
     * Adults in the herd once the animals another villager is already busy
     * with are left out: one on a lead (on its way out), or claimed by someone
     * else (another butcher's pick, a rancher's feed). Two butchers each saw
     * three cows over a floor of two and took one each, leaving one.
     */
    private static int spare(List<Animal> herd, UUID me, long now) {
        int spare = herd.size();
        for (Animal animal : herd) {
            if (LedAnimals.isLed(animal) || RanchClaims.isClaimedByOther(animal.getUUID(), me, now)) spare--;
        }
        return spare;
    }

    /**
     * The Butchery nearest {@code ranch}'s gate that can be used, or why none can. One paused
     * after too many give-ups is skipped for the next nearest; with none left, it says paused.
     */
    private static Place placeFor(Context ctx, RanchBuilding ranch) {
        ButcheryBuilding best = null;
        boolean anyButchery = false;
        boolean anyChop = false;
        boolean pausedNear = false;
        double bestDist = Double.MAX_VALUE;
        BlockPos gate = ranch.getGatePos();
        long day = today(ctx);
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof ButcheryBuilding candidate) || !candidate.isValid()) continue;
            anyButchery = true;
            if (!candidate.canButcher()) continue;
            anyChop = true;
            double dist = Math.sqrt(candidate.killSpot().distSqr(gate));
            if (candidate.isPaused(day)) {
                pausedNear |= dist <= MAX_BUTCHERY_DIST;
                continue;
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        if (!anyButchery) return new Place(null, ButcherStatus.NEEDS_BUTCHERY);
        if (!anyChop) return new Place(null, ButcherStatus.NEEDS_CHOPPING_BLOCK);
        if (bestDist > MAX_BUTCHERY_DIST) return new Place(null, pausedNear ? ButcherStatus.PAUSED : ButcherStatus.TOO_FAR);
        return new Place(best, null);
    }

    /**
     * The working Butchery whose kill spot is nearest {@code gate}, a paused one too: the one
     * {@link #placeFor} picks when nothing is paused. For the Butchery window's check list.
     */
    @Nullable
    public static ButcheryBuilding nearestWorkingButchery(com.alex.test.village.VillageManager mgr, BlockPos gate) {
        ButcheryBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building building : mgr.getBuildings()) {
            if (!(building instanceof ButcheryBuilding candidate) || !candidate.isValid() || !candidate.canButcher()) continue;
            double dist = candidate.killSpot().distSqr(gate);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    private static final String CANNOT_REACH = "Cannot reach the Ranch or the Butchery";

    /** {@link #placeFor}, and only when the butcher can walk to the pen's gate and the Butchery's kill spot. */
    private static Place usablePlace(Context ctx, RanchBuilding ranch) {
        Place place = placeFor(ctx, ranch);
        if (place.problem() != null) return place;
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || !ctx.model().canReach(graph, ranch.getGatePos())
                || !ctx.model().canReach(graph, place.butchery().killSpot())) {
            return new Place(null, CANNOT_REACH);
        }
        return place;
    }

    /** First work spot pair nobody stands on and nothing lies on; else the Butchery's first. */
    private static ButcheryBuilding.WorkSpots freeSpots(Context ctx, ButcheryBuilding place) {
        for (ButcheryBuilding.WorkSpots pair : place.getWorkSpots()) {
            if (blocked(ctx, pair.killSpot()) || blocked(ctx, pair.animalSpot())) continue;
            return pair;
        }
        return place.getWorkSpots().get(0);
    }

    private static boolean blocked(Context ctx, BlockPos tile) {
        AABB box = new AABB(tile);
        return !ctx.level().getEntitiesOfClass(LivingEntity.class, box,
                        e -> e != ctx.npc() && e.isAlive()).isEmpty()
                || !ctx.level().getEntitiesOfClass(ItemEntity.class, box).isEmpty();
    }

    @Nullable
    private Animal strandedAnimal(Context ctx) {
        if (strandedInside == null || !(ctx.level() instanceof ServerLevel level)) return null;
        if (!(level.getEntity(strandedInside) instanceof Animal animal) || !animal.isAlive() || animal.isLeashed()) {
            strandedInside = null; // gone, or someone else has it on a lead now
            strandedRanch = null;
            return null;
        }
        if (butchery == null || !butchery.isInRoom(animal.blockPosition())
                && !butchery.isInRoom(NavGraph.feetCell(animal))) {
            // It got out of the Butchery (a door was opened for it): free and alive, so its pen takes it back.
            giveUpWaiting(ctx, animal, "it is no longer inside");
            return null;
        }
        return animal;
    }

    /** How many of {@code item}, as the village-owned stack a cull makes, still fit in the pack. */
    private static int roomFor(Context ctx, Item item) {
        ItemStack owned = com.alex.test.village.tokens.VillageItemOwnership.produce(new ItemStack(item), ctx.mgr());
        return owned.isEmpty() ? 0 : ctx.npc().getInventory().capacityFor(owned);
    }

    /** Fewest adults a herd can be cut to and still breed: the rancher feeds a pair. */
    private static final int MIN_BREEDING_ADULTS = 2;

    /**
     * Adults to leave of each species. Never under a breeding pair: a setting
     * of 0 or 1 (allowed before, and still in older saves) culled a species
     * the rancher could then never breed back.
     */
    public static int keepAdults(ProfessionSettings settings) {
        return Math.max(MIN_BREEDING_ADULTS, settings.getInt(ButcherSettingsSchema.KEEP_ADULTS));
    }

    /** Whether {@link ButcherSettingsSchema#ALLOWED_LIVESTOCK} permits culling this species. */
    public static boolean isCullable(ProfessionSettings settings, EntityType<?> type) {
        return settings.isAllowed(ButcherSettingsSchema.ALLOWED_LIVESTOCK, EntityType.getKey(type).toString());
    }

    private static int skillLevel(Context ctx) {
        return ctx.brain().butcherSkillLevel();
    }

    private static int cullsPerShift(Context ctx) {
        return ButcherBalance.cullsPerShift(skillLevel(ctx));
    }

    private static ProfessionSettings settings(Context ctx) {
        return ProfessionSettings.of(ctx.npc(), ButcherSettingsSchema.SCHEMA);
    }

    // =========================================================================
    // The kill and the blade
    // =========================================================================

    /**
     * What one kill gives while nobody is near, rolled as a live kill drops
     * it: the animal's vanilla loot (cow and pig 1 to 3 meat, sheep 1 to 2,
     * chicken 1; a cow's 0 to 2 leather, a chicken's 0 to 2 feathers; a
     * woolly sheep's fleece unless salvage is off) plus what the butcher's
     * skill adds ({@link ButcherDrops}). No sword enchantments: nobody saw it.
     */
    public static Map<Item, Integer> killYield(Animal animal, boolean salvageWool, int level, RandomSource random) {
        Item wool = salvageWool && animal instanceof Sheep sheep && sheep.readyForShearing()
                ? RanchAnimals.woolFor(sheep) : null;
        return killYield(animal.getType(), wool, level, random);
    }

    /**
     * {@link #killYield(Animal, boolean, int, RandomSource)} for an animal of
     * {@code type} that may not be in the world (the away pens' records):
     * {@code wool} is the fleece it drops, null for none (shorn, or salvage off).
     */
    public static Map<Item, Integer> killYield(EntityType<?> type, @Nullable Item wool, int level, RandomSource random) {
        Map<Item, Integer> yield = new LinkedHashMap<>();
        Item meat = RanchAnimals.meatFor(type);
        if (meat == null) return yield;
        int meatCount = type == EntityType.CHICKEN ? 1
                : type == EntityType.SHEEP ? 1 + random.nextInt(2) : 1 + random.nextInt(3);
        yield.put(meat, meatCount + ButcherDrops.extraMeat(level, random));
        if (wool != null) yield.merge(wool, 1, Integer::sum);
        Item hide = ButcherDrops.hideFor(type);
        if (hide != null) {
            int dropped = random.nextInt(3);
            int count = dropped + ButcherDrops.extraHide(level, dropped, random);
            if (count > 0) yield.merge(hide, count, Integer::sum);
        }
        return yield;
    }

    private void equipSword(Context ctx) {
        int slot = ButcherSword.slot(ctx.npc());
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND,
                slot >= 0 ? ctx.npc().getInventory().getSlot(slot).copy() : ItemStack.EMPTY);
    }

    /** One use of wear per blow, the vanilla way. */
    private void wearSword(Context ctx) {
        if (ctx.level() instanceof ServerLevel level) {
            ToolSelectionHelper.wearTool(level, ctx.npc().getInventory(), ButcherSword.slot(ctx.npc()));
        }
    }

    /** One beat of sharpening, every 10 ticks at the wheel: its sound and a few sparks where the blade meets it. */
    public static void grindstoneEffects(ServerLevel level, BlockPos grindstone) {
        level.playSound(null, grindstone, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.6f, 1.0f);
        level.sendParticles(ParticleTypes.CRIT, grindstone.getX() + 0.5, grindstone.getY() + 0.9,
                grindstone.getZ() + 0.5, 4, 0.15, 0.05, 0.15, 0.15);
    }

    /** Any wear at all is worth the daily visit. */
    private boolean wantsSharpening(Context ctx) {
        int slot = ButcherSword.slot(ctx.npc());
        if (slot < 0) return false;
        ItemStack sword = ctx.npc().getInventory().getSlot(slot);
        return sword.isDamageableItem() && sword.getDamageValue() > 0;
    }

    private static long today(Context ctx) {
        return VillageDays.dayIndex(ctx.level().getDayTime());
    }

    private static boolean sharpenedToday(Context ctx) {
        CompoundTag data = ctx.npc().getBrainData();
        return data.contains(TAG_SHARPEN_DAY) && data.getLong(TAG_SHARPEN_DAY) == today(ctx);
    }

    private void sharpen(Context ctx) {
        int repair = sharpenSword(ctx.npc(), ctx.level().random);
        if (repair >= 0) diary(ctx, "BUTCHER sharpened the sword (+" + repair + ")");
    }

    /**
     * One grindstone visit's worth back on the sword the butcher kills with.
     * Also the away butcher's once a day ({@code economy/away/jobs/ButcherAway}).
     *
     * @return the points given back, or -1 with no sword that wears
     */
    public static int sharpenSword(com.alex.test.entity.NpcEntity npc, RandomSource random) {
        int slot = ButcherSword.slot(npc);
        if (slot < 0) return -1;
        ItemStack sword = npc.getInventory().getSlot(slot);
        if (!sword.isDamageableItem()) return -1;
        int repair = SHARPEN_MIN + random.nextInt(SHARPEN_MAX - SHARPEN_MIN + 1);
        // A player's sword takes only its share of the whetstone (PlayerGear).
        repair = com.alex.test.village.tokens.PlayerGear.repair(sword, repair, random);
        sword.setDamageValue(Math.max(0, sword.getDamageValue() - repair));
        return repair;
    }

    /**
     * Blows a kill takes with {@code sword} at skill {@code level}, as
     * {@link #strike} lands them: the animal's health over one blow's damage,
     * at most {@link #MAX_BLOWS}. For the away catch-up, which wears the sword
     * a point a blow.
     */
    public static int blowsToKill(Animal animal, ItemStack sword, int level) {
        return blowsToKill(animal.getHealth(), sword, level);
    }

    /** {@link #blowsToKill(Animal, ItemStack, int)} for an animal with {@code health}. */
    public static int blowsToKill(float health, ItemStack sword, int level) {
        float damage = ButcherBalance.blowDamage(level, GuardSwordUtils.attackDamage(sword));
        if (damage <= 0f) return MAX_BLOWS;
        return Math.clamp((int) Math.ceil(health / damage), 1, MAX_BLOWS);
    }

    /** Whether the butcher with {@code data} went to the grindstone on village day {@code day} (the away catch-up). */
    public static boolean sharpenedOn(CompoundTag data, long day) {
        return data.contains(TAG_SHARPEN_DAY) && data.getLong(TAG_SHARPEN_DAY) == day;
    }

    /**
     * The village day of the butcher's last grindstone visit, or {@link Long#MIN_VALUE} for none yet
     * (the Butchery window). Set when it sets off, so an unreachable grindstone still counts.
     */
    public static long sharpenDay(CompoundTag data) {
        return data.contains(TAG_SHARPEN_DAY) ? data.getLong(TAG_SHARPEN_DAY) : Long.MIN_VALUE;
    }

    /** Marks village day {@code day}'s grindstone visit as done, as {@link #afterKill} does. */
    public static void markSharpened(CompoundTag data, long day) {
        data.putLong(TAG_SHARPEN_DAY, day);
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    /** The lead in hand while leading: a display-only prop, the real one never leaves the pack. */
    private static void showLead(Context ctx) {
        LeadSupply.showInHand(ctx.npc());
    }

    private static void showNothing(Context ctx) {
        LeadSupply.putAway(ctx.npc());
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private void transition(Context ctx, State next, String why) {
        state = next;
        diary(ctx, "BUTCHER " + next.name().toLowerCase(Locale.ROOT) + ": " + why);
    }

    private static String name(Entity entity) {
        return entity.getType().getDescription().getString();
    }

    private static double horizontalDist(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    /**
     * Cheap pre-check for brain scoring: duty on, a lead, a sword, a Ranch with a spare
     * adult, a working Butchery within reach of it, and both on this
     * villager's part of the map.
     */
    public static boolean hasWork(Context ctx) {
        ProfessionSettings settings = settings(ctx);
        if (!settings.getBool(ButcherSettingsSchema.WORK_ENABLED)) return false;
        LeadSupply.giveOnceToOldHire(ctx.npc());
        if (!LeadSupply.hasLead(ctx.npc()) && !GetToolActivity.canAcquire(ctx, LeadSupply.REQUIREMENT)) {
            ButcherStatus.set(ctx, ButcherStatus.NEEDS_LEAD);
            return false;
        }
        if (ButcherSword.slot(ctx.npc()) < 0 && !GetToolActivity.canAcquire(ctx, ButcherSword.REQUIREMENT)) {
            ButcherStatus.set(ctx, ButcherStatus.NEEDS_SWORD);
            return false;
        }
        boolean anyRanch = ctx.mgr().getBuildings().stream()
                .anyMatch(b -> b instanceof RanchBuilding r && r.isValid());
        if (!anyRanch) {
            ButcherStatus.set(ctx, ButcherStatus.NEEDS_RANCH);
            return false;
        }
        // Same pick as the work itself, so the brain never starts a shift
        // that finds nothing it may cull.
        boolean[] noRoom = new boolean[1];
        String[] problem = new String[1];
        Pick pick = findCull(ctx, noRoom, problem);
        if (pick == null) {
            // Surplus waiting only on a full pack: worth a shift that banks it.
            // Or a pen animal out that an idle butcher can bring home.
            boolean work = noRoom[0] && DepositActivity.countDepositable(ctx) > 0
                    || LeadSupply.hasLead(ctx.npc()) && RanchStrays.strayFor(ctx) != null;
            if (!work && noRoom[0]) ButcherStatus.set(ctx, fullPackStatus(ctx));
            else if (!work && problem[0] != null) ButcherStatus.set(ctx, problem[0]);
            else ButcherStatus.clear(ctx);
            return work;
        }
        ButcherStatus.clear(ctx);
        return true;
    }

    /**
     * Why a butcher with a full pack cannot bank it: storage will not take
     * what it could bank, or the pack holds only things it keeps.
     */
    private static String fullPackStatus(Context ctx) {
        return DepositActivity.countPotentiallyDepositable(ctx) > 0
                ? ButcherStatus.STORAGE_FULL : ButcherStatus.PACK_FULL;
    }
}
