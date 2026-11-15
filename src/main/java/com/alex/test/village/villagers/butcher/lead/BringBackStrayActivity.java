package com.alex.test.village.villagers.butcher.lead;

import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.rancher.RanchClaims;
import com.alex.test.village.villagers.rancher.RanchHappenings;
import com.alex.test.village.villagers.rancher.RanchHappenings.GaveUp;
import com.alex.test.village.villagers.rancher.RanchRoundUp;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Leads an animal into a pen: walk to it, put the lead on, walk in through
 * the gate to a tile well inside, and let it go once it is in and clear of
 * the gate. The same footstep-following walk and doorway holding the butcher
 * uses. Two kinds: a stray (a pen animal that got out) brought home, or a
 * loose animal rounded up ({@link #roundUp}).
 *
 * <p>The villager stops at least {@link #MIN_RELEASE_DIST} from the gate. A
 * led animal always stops short of its villager; with the villager only two
 * tiles in, that left a cow standing in the gateway with the last crumbs
 * unwalked, the gate held open until the walk was given up.
 *
 * <p>Given up (lead lost, stuck, can't keep up, gate held too long, no
 * route): the animal is let go where it is and this villager leaves it be
 * for a minute.
 */
public final class BringBackStrayActivity extends Activity {

    private enum Phase { GO, LEAD, WALK_BACK, SETTLE }

    private static final double REACH_SQ = 6.25;
    private static final int MAX_REPATHS = 4;
    private static final float CHASE_SPEED = 0.6f;
    private static final float LEAD_SPEED = 0.45f;
    private static final int WAIT_BEHIND = 4;
    private static final int RESUME_BEHIND = 2;
    private static final int WAIT_LIMIT = 200;
    /** Waited {@link #WAIT_LIMIT} for it: the villager walks back to it once, as the butcher does, then gives up. */
    private static final int MAX_WALK_BACKS = 1;
    private static final int MAX_ROUTE_FAILS = 2;
    private static final int SETTLE_LIMIT = 200;
    /** The villager in the pen, the animal still outside this long (5 s): it is put in. */
    private static final int PUT_IN_TICKS = 100;
    /** Put in only while it is this near the villager: stuck at the gate, not lost down the street. */
    private static final double PUT_IN_RANGE = 8.0;
    /** Settling this long without the animal coming in: the villager walks further in, once. */
    private static final int STEP_IN_TICKS = 80;
    /** Where the villager lets go: about this far in from the gate, straight in. */
    private static final double RELEASE_DEPTH = 4.0;
    /**
     * The villager stops at least this far from the gate's centre. The animal
     * stops up to keep-off plus a crumb (2 blocks) behind it, which leaves a
     * cow's body clear of the gate block (0.95 from its centre).
     */
    private static final double MIN_RELEASE_DIST = 3.5;
    /** Within this of the gate's centre the animal is in or at the gateway, and steered straight in. */
    private static final double GATEWAY_DIST = 1.6;

    private final RanchBuilding ranch;
    private final Animal animal;
    /** A loose animal rounded up, rather than a stray of this pen. */
    private final boolean roundUp;
    /** Round-up: how far from the gate it may be and still be fetched. */
    private final double range;

    private Phase phase = Phase.GO;
    @Nullable private MoveActivity move;
    @Nullable private BlockPos chaseDest;
    @Nullable private BlockPos releaseTile;
    @Nullable private LeadDoorways doorways;
    private boolean leading;
    private boolean steppedIn;
    /** The villager's last steps on the walk over, for a lead put on from a distance. */
    private final Footsteps footsteps = new Footsteps();
    private boolean farLead;
    private int repaths;
    private int waitTicks;
    private int walkBacks;
    private int routeFails;
    private int settleTicks;

    public BringBackStrayActivity(RanchBuilding ranch, Animal animal) {
        this(ranch, animal, false, RanchStrays.RANGE);
    }

    private BringBackStrayActivity(RanchBuilding ranch, Animal animal, boolean roundUp, double range) {
        this.ranch = ranch;
        this.animal = animal;
        this.roundUp = roundUp;
        this.range = range;
    }

    /** Leads a loose animal, no pen's yet, into {@code ranch}. */
    public static BringBackStrayActivity roundUp(RanchBuilding ranch, Animal animal, double range) {
        return new BringBackStrayActivity(ranch, animal, true, range);
    }

    @Override
    protected void start(Context ctx) {
        diary(ctx, roundUp
                ? "RANCH rounding up a loose " + animalName()
                : "RANCH bringing back a stray " + animalName());
    }

    @Override
    protected Status tick(Context ctx) {
        if (!ranch.isValid()) return giveUp(ctx, null, "the ranch is gone");
        RanchClaims.claim(animal.getUUID(), ctx.npc().getUUID(), ctx.level().getGameTime());
        return switch (phase) {
            case GO -> tickGo(ctx);
            case LEAD -> tickLead(ctx);
            case WALK_BACK -> tickWalkBack(ctx);
            case SETTLE -> tickSettle(ctx);
        };
    }

    private Status tickGo(Context ctx) {
        boolean fetchable = roundUp
                ? RanchRoundUp.fetchable(ranch, animal, range)
                : RanchStrays.fetchable(ranch, animal);
        if (!fetchable) {
            if (animal.isAlive() && ranch.isPenTile((ServerLevel) ctx.level(), animal.blockPosition())) {
                markInside(ctx);
                return Status.DONE; // it walked in by itself
            }
            return Status.FAILED;
        }
        if (ctx.npc().distanceToSqr(animal) <= REACH_SQ) return startLead(ctx);
        footsteps.record(ctx.npc());
        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) {
                if (chaseDest != null && animal.blockPosition().distSqr(chaseDest) > 25.0) {
                    move.stop(ctx);
                    move = null;
                }
                return Status.RUNNING;
            }
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED && ++repaths > MAX_REPATHS) {
                // No way up to it (stuck in a hole, behind a fence corner):
                // the lead goes on from here if it is in sight and near enough.
                if (!farLead && footsteps.canLeadFromHere(ctx.npc(), animal)) {
                    farLead = true;
                    diary(ctx, "RANCH could not walk up to the " + animalName() + "; leading it from here");
                    return startLead(ctx);
                }
                return giveUp(ctx, GaveUp.REACH, "could not reach it");
            }
            return Status.RUNNING;
        }
        chaseDest = animal.blockPosition();
        move = new MoveActivity(chaseDest, CHASE_SPEED);
        return Status.RUNNING;
    }

    private Status startLead(Context ctx) {
        if (move != null) { move.stop(ctx); move = null; }
        if (!LedAnimals.start(animal, ctx.npc())) return giveUp(ctx, GaveUp.LEAD_ON, "could not put the lead on");
        leading = true;
        if (farLead) {
            LeadWalk walk = LedAnimals.trailOf(animal);
            if (walk != null) walk.seedFootsteps(footsteps.near(ctx.npc()));
        }
        LeadSupply.showInHand(ctx.npc());
        doorways = new LeadDoorways();
        releaseTile = releaseTile(false);
        move = new MoveActivity(releaseTile, LEAD_SPEED, true);
        phase = Phase.LEAD;
        return Status.RUNNING;
    }

    private Status tickLead(Context ctx) {
        // In already (a short pen, a bring-up past the gate): let go now
        // rather than hold the gate while the villager walks on.
        if (isInside(ctx)) return arrived(ctx);
        Status safety = leadSafety(ctx);
        if (safety != null) return safety;
        LeadWalk.Result result = LedAnimals.tickWalk(ctx.npc(), animal);
        if (result == LeadWalk.Result.LOST) return lost(ctx);
        if (result == LeadWalk.Result.STUCK) {
            if (putInPen(ctx, "the stuck rules ran out")) return Status.RUNNING;
            return giveUp(ctx, GaveUp.STUCK, "it got stuck");
        }

        LeadWalk walk = LedAnimals.trailOf(animal);
        int behind = walk == null ? 0 : walk.crumbsBehind();
        if (result == LeadWalk.Result.TOO_FAR || behind >= WAIT_BEHIND || (waitTicks > 0 && behind > RESUME_BEHIND)) {
            ctx.model().holdStillForCare();
            if (++waitTicks >= WAIT_LIMIT) {
                if (walkBacks >= MAX_WALK_BACKS) return giveUp(ctx, GaveUp.KEEP_UP, "it could not keep up");
                // Walk back to it once and lead it on from there.
                walkBacks++;
                waitTicks = 0;
                if (move != null) { move.stop(ctx); move = null; }
                if (walk != null) walk.restartFrom(animal.position());
                move = new MoveActivity(animal.blockPosition(), LEAD_SPEED);
                phase = Phase.WALK_BACK;
                diary(ctx, "RANCH walking back to the " + animalName());
            }
            return Status.RUNNING;
        }
        waitTicks = 0;
        if (releaseTile == null) releaseTile = releaseTile(false);
        if (move == null) move = new MoveActivity(releaseTile, LEAD_SPEED, true);
        Status status = move.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        move.stop(ctx);
        move = null;
        if (status == Status.FAILED) {
            if (++routeFails >= MAX_ROUTE_FAILS) return giveUp(ctx, GaveUp.NO_WAY_IN, "no way into the pen");
            return Status.RUNNING;
        }
        phase = Phase.SETTLE;
        settleTicks = 0;
        return Status.RUNNING;
    }

    /** Back to an animal that could not keep up, then on to the pen again; as the butcher's walk back. */
    private Status tickWalkBack(Context ctx) {
        Status safety = leadSafety(ctx);
        if (safety != null) return safety;
        LeadWalk.Result result = LedAnimals.tickWalk(ctx.npc(), animal);
        if (result == LeadWalk.Result.LOST) return lost(ctx);
        if (result == LeadWalk.Result.STUCK) return giveUp(ctx, GaveUp.STUCK, "it got stuck");
        boolean close = ctx.npc().distanceToSqr(animal) <= REACH_SQ;
        Status status = close ? Status.DONE : move == null ? Status.FAILED : move.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        if (move != null) { move.stop(ctx); move = null; }
        if (!close && ctx.npc().distanceToSqr(animal) > 16.0) return giveUp(ctx, GaveUp.BACK_TO_IT, "could not get back to it");
        LeadWalk walk = LedAnimals.trailOf(animal);
        if (walk != null) walk.restartFrom(animal.position());
        phase = Phase.LEAD; // tickLead starts the walk to the pen again
        return Status.RUNNING;
    }

    /** At the tile inside: the animal comes in, and is let go once it is in and clear of the gate. */
    private Status tickSettle(Context ctx) {
        // Checked before the doorway hold: the villager stands still now, so
        // the animal gets no further along the trail and the gate's hold
        // would run out with the animal already in.
        if (isInside(ctx)) return arrived(ctx);
        Status safety = leadSafety(ctx);
        if (safety != null) return safety;
        LeadWalk.Result result = LedAnimals.tickWalk(ctx.npc(), animal);
        if (result == LeadWalk.Result.LOST) return lost(ctx);
        if (result == LeadWalk.Result.STUCK) {
            if (putInPen(ctx, "the stuck rules ran out")) return Status.RUNNING;
            return giveUp(ctx, GaveUp.STUCK, "it got stuck");
        }

        LeadWalk walk = LedAnimals.trailOf(animal);
        boolean trailDone = walk == null || walk.crumbsBehind() == 0;
        Vec3 gateCentre = Vec3.atBottomCenterOf(ranch.getGatePos());
        Direction in = ranch.getInteriorDirection();
        // How far in past the gate's centre: negative is still outside, where
        // a straight line in could catch a fence post.
        double depth = (animal.getX() - gateCentre.x) * in.getStepX() + (animal.getZ() - gateCentre.z) * in.getStepZ();
        boolean atGate = horizontalDist(animal.position(), gateCentre) <= GATEWAY_DIST;
        if (depth >= -0.3 && (trailDone || atGate)) {
            // The animal stops short of the villager, and may do so still half
            // in the gateway with crumbs it will not walk (they are at the
            // villager's heels): it walks on, straight in, clear of the gate.
            Vec3 to = clearSpot(ctx);
            if (to != null && walk == null) {
                animal.getMoveControl().setWantedPosition(to.x, to.y, to.z, 1.0);
            } else if (to != null && walk.lastSteps(animal, to, 1.0)) {
                diary(ctx, "RANCH the " + animalName() + " was stuck in the gateway; put it in");
            }
        }

        settleTicks++;
        // The villager is in the pen and the animal still outside after 5 s:
        // it is put in (tried once a second until it fits somewhere).
        if (settleTicks >= PUT_IN_TICKS && settleTicks % 20 == 0
                && putInPen(ctx, "still outside after " + settleTicks / 20 + " s")) {
            return Status.RUNNING;
        }
        if (settleTicks == STEP_IN_TICKS && !steppedIn) {
            // Still not in: the villager is in the way. It walks further in
            // and the animal follows it the rest of the way.
            steppedIn = true;
            BlockPos deeper = releaseTile(true);
            if (!deeper.equals(releaseTile)) {
                releaseTile = deeper;
                move = new MoveActivity(deeper, LEAD_SPEED, true);
                phase = Phase.LEAD;
                return Status.RUNNING;
            }
        }
        if (settleTicks >= SETTLE_LIMIT) return giveUp(ctx, GaveUp.WOULD_NOT_COME_IN, "it would not come in");
        return Status.RUNNING;
    }

    /**
     * The villager stands in the pen and the animal on its lead is stuck
     * outside: it is put in, two tiles straight in from the gate, else the
     * pen tile nearest there that it fits on, clear of the gate and never on
     * the villager. False when the villager is not in the pen, the animal is
     * in already or more than {@link #PUT_IN_RANGE} away, or nothing fits.
     */
    private boolean putInPen(Context ctx, String why) {
        ServerLevel level = (ServerLevel) ctx.level();
        if (!ranch.isPenTile(level, ctx.npc().blockPosition()) || isInside(ctx)) return false;
        if (animal.distanceTo(ctx.npc()) > PUT_IN_RANGE) return false;
        LeadWalk walk = LedAnimals.trailOf(animal);
        if (walk == null) return false;
        BlockPos gate = ranch.getGatePos();
        Vec3 near = Vec3.atBottomCenterOf(gate.relative(ranch.getInteriorDirection(), 2));
        List<Vec3> tries = new ArrayList<>();
        Vec3 clear = clearSpot(ctx);
        if (clear != null) tries.add(clear);
        List<BlockPos> rest = new ArrayList<>(ranch.getNavMeshBlocks());
        rest.sort(Comparator.comparingDouble(p -> Vec3.atBottomCenterOf(p).distanceToSqr(near)));
        for (BlockPos tile : rest) tries.add(Vec3.atBottomCenterOf(tile));
        double keepOff = LeadWalk.keepOff(ctx.npc(), animal);
        AABB gateBox = new AABB(gate);
        for (Vec3 spot : tries) {
            BlockPos tile = BlockPos.containing(spot);
            if (!ranch.isPenTile(level, tile) || horizontalDist(spot, ctx.npc().position()) < keepOff) continue;
            if (animal.getBoundingBox().move(spot.subtract(animal.position())).intersects(gateBox)) continue;
            if (walk.placeAt(animal, spot)) {
                LeadWalk.log(animal, "put inside the pen: " + why);
                diary(ctx, "RANCH the " + animalName() + " was stuck outside; put it in (" + why + ")");
                return true;
            }
        }
        LeadWalk.log(animal, "could not put it inside the pen: no pen tile fits it (" + why + ")");
        return false;
    }

    /** In the pen and clear of the gate, so the gate can shut behind it. */
    private boolean isInside(Context ctx) {
        return animal.isAlive()
                && ranch.isPenTile((ServerLevel) ctx.level(), animal.blockPosition())
                && !animal.getBoundingBox().intersects(new AABB(ranch.getGatePos()));
    }

    private Status arrived(Context ctx) {
        LedAnimals.release(animal, roundUp ? "rounded up into the pen" : "home in the pen");
        leading = false;
        if (move != null) { move.stop(ctx); move = null; }
        closeDoorways(ctx);
        markInside(ctx);
        showNothing(ctx);
        diary(ctx, roundUp
                ? "RANCH brought a new " + animalName() + " into the pen"
                : "RANCH brought the " + animalName() + " home");
        note(ctx, roundUp ? RanchHappenings.Kind.ROUNDED_UP : RanchHappenings.Kind.BROUGHT_BACK, GaveUp.NONE);
        return Status.DONE;
    }

    /** A line in the pen's recent happenings, for the Ranch window. */
    private void note(Context ctx, RanchHappenings.Kind kind, GaveUp why) {
        RanchHappenings.note(ranch, kind, VillageManager.displayName(ctx.model()), animal.getType(), 0, why,
                ctx.level().getGameTime());
    }

    /** It belongs to the pen from now on: if it gets out again, it is a stray and fetched back. */
    private void markInside(Context ctx) {
        RanchStrays.markHome(ranch, animal);
        ranch.markMember(animal.getUUID(), ctx.level().getGameTime());
    }

    /**
     * Where the animal walks the last steps to: straight in from the gate,
     * two tiles in, else one, clear of the villager by its keep-off distance.
     * Null when neither fits (a tiny pen with the villager in the way).
     */
    @Nullable
    private Vec3 clearSpot(Context ctx) {
        BlockPos gate = ranch.getGatePos();
        Direction in = ranch.getInteriorDirection();
        double keepOff = LeadWalk.keepOff(ctx.npc(), animal);
        for (int depth = 2; depth >= 1; depth--) {
            BlockPos tile = gate.relative(in, depth);
            if (!ranch.getNavMeshBlocks().contains(tile)) continue;
            Vec3 spot = Vec3.atBottomCenterOf(tile);
            if (horizontalDist(spot, ctx.npc().position()) >= keepOff) return spot;
        }
        return null;
    }

    /**
     * The pen tile the villager walks the animal to: the one nearest
     * {@link #RELEASE_DEPTH} straight in from the gate that is at least
     * {@link #MIN_RELEASE_DIST} from it, so the animal behind it is clear of
     * the gate. A pen too small for that, or {@code deepest}: the tile
     * furthest from the gate.
     */
    private BlockPos releaseTile(boolean deepest) {
        BlockPos gate = ranch.getGatePos();
        Direction in = ranch.getInteriorDirection();
        Vec3 gateCentre = Vec3.atBottomCenterOf(gate);
        Vec3 ideal = gateCentre.add(in.getStepX() * RELEASE_DEPTH, 0, in.getStepZ() * RELEASE_DEPTH);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos furthest = null;
        double furthestDist = -1;
        for (BlockPos tile : ranch.getNavMeshBlocks()) {
            Vec3 centre = Vec3.atBottomCenterOf(tile);
            double fromGate = horizontalDist(centre, gateCentre);
            if (fromGate > furthestDist) {
                furthestDist = fromGate;
                furthest = tile;
            }
            if (fromGate < MIN_RELEASE_DIST) continue;
            double score = horizontalDist(centre, ideal);
            if (score < bestScore) {
                bestScore = score;
                best = tile;
            }
        }
        if (!deepest && best != null) return best;
        return furthest != null ? furthest : gate.relative(in);
    }

    /** Lost, saddled, or a doorway held too long: the walk is over. Null while all is well. */
    @Nullable
    private Status leadSafety(Context ctx) {
        if (!animal.isAlive() || !LedAnimals.isLedBy(animal, ctx.npc())) return lost(ctx);
        if (RanchStrays.isSaddled(animal)) return giveUp(ctx, GaveUp.SADDLED, "it was saddled: a player's ride");
        if (doorways != null && LedAnimals.trailOf(animal) != null
                && doorways.tick((ServerLevel) ctx.level(), ctx.npc(), animal, LedAnimals.trailOf(animal), ctx.clock())) {
            if (putInPen(ctx, "the gate was held too long")) return null;
            return giveUp(ctx, GaveUp.GATE_HELD, "held a gate open for " + LeadDoorways.MAX_HOLD_TICKS / 20 + " s");
        }
        LeadSupply.keepShown(ctx.npc());
        return null;
    }

    private Status lost(Context ctx) {
        leading = false;
        closeDoorways(ctx);
        showNothing(ctx);
        diary(ctx, "RANCH lost the lead on the " + animalName());
        note(ctx, RanchHappenings.Kind.GAVE_UP, animal.isAlive() ? GaveUp.LEAD_OFF : GaveUp.DIED);
        return Status.FAILED;
    }

    /**
     * The one way this walk is given up: lead off with no item, doorways let go, left be a minute.
     * {@code why} is the pen's recent happenings line; null notes nothing (the pen is gone).
     */
    private Status giveUp(Context ctx, @Nullable GaveUp why, String reason) {
        if (why != null) note(ctx, RanchHappenings.Kind.GAVE_UP, why);
        if (leading && LedAnimals.isLedBy(animal, ctx.npc())) LedAnimals.release(animal, reason);
        leading = false;
        if (move != null) { move.stop(ctx); move = null; }
        closeDoorways(ctx);
        showNothing(ctx);
        RanchClaims.markUnreachable(ctx.npc().getUUID(), animal.getUUID(), ctx.level().getGameTime());
        diary(ctx, (roundUp ? "RANCH gave up rounding up the " : "RANCH gave up bringing back the ")
                + animalName() + ": " + reason);
        LeadWalk.log(animal, (roundUp ? "round-up" : "stray walk") + " given up: " + reason
                + " (villager at " + ctx.npc().blockPosition().toShortString() + ")");
        return Status.FAILED;
    }

    @Override
    public void stop(Context ctx) {
        if (leading) giveUp(ctx, GaveUp.CALLED_AWAY, "interrupted");
        if (move != null) { move.stop(ctx); move = null; }
        closeDoorways(ctx);
        RanchClaims.release(animal.getUUID(), ctx.npc().getUUID());
        ctx.model().stopMoving();
    }

    /** Whether an animal is on this villager's lead right now; the work may not be interrupted then. */
    public boolean isLeading() {
        return leading;
    }

    private void closeDoorways(Context ctx) {
        if (doorways == null) return;
        doorways.releaseAll((ServerLevel) ctx.level(), ctx.npc(), animal.isAlive() ? animal : null);
        doorways = null;
    }

    private static void showNothing(Context ctx) {
        LeadSupply.putAway(ctx.npc());
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private static double horizontalDist(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private String animalName() {
        return animal.getType().getDescription().getString();
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    @Override
    public String name() {
        return roundUp ? "round_up" : "bring_back_stray";
    }
}
