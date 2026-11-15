package com.alex.test.village.brain;

import com.alex.test.animation.HurryGait;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.building.Building;
import com.alex.test.village.shelter.ShelterWatch;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.guard.behavior.DefenderRetreat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Hard-override activity: get away from the attacker.
 *
 * <p>Two kinds:
 * <ul>
 *   <li><b>To shelter</b> ({@link #toShelter}): a scared villager runs into
 *       the safest nearby building, gets a few steps in from the door and waits
 *       there until no monster (nor the player who hit it) has been near for {@link #ALL_CLEAR_TICKS}. The
 *       run is a civilian {@link DefenderRetreat}: the closest building whose
 *       door the monster is not nearer to, the next one if the way is blocked,
 *       straight across open ground when off the village paths, and away from
 *       the monster when there is no building at all.</li>
 *   <li><b>Away</b> ({@code new FleeActivity}): sprint for five seconds away
 *       from the attacker, staying on the village nav graph. An archer keeping
 *       its distance and a guard backing off a monster in the water use it.</li>
 * </ul>
 */
public final class FleeActivity extends Activity {

    private static final int FLEE_TICKS = 100; // 5 s
    private static final double FLEE_DIST = 12.0;
    private static final double MIN_TARGET_DIST_SQ = 4.0 * 4.0;
    private static final double MAX_TARGET_DIST_SQ = 24.0 * 24.0;
    private static final int MAX_TARGET_Y_DELTA = 6;

    /** Running for shelter: the old flee's pace, about 4.7 blocks a second, with the running clip. */
    private static final float SHELTER_RUN_SPEED = 0.7f;
    /** How often a hiding villager looks around for monsters. */
    private static final int CHECK_TICKS = 10;
    /** How often a villager hiding in or running to a building looks for a monster inside it (once a second). */
    private static final int BREACH_CHECK_TICKS = 20;
    /** No monster near for this long and the hiding is over (10 s). */
    private static final int ALL_CLEAR_TICKS = 200;
    /** The longest a villager hides before going back to its day anyway (2 min). */
    private static final int MAX_HOLD_TICKS = 2400;
    /** The longest a whole flee to shelter lasts, runs and all (3 min). */
    private static final int MAX_SHELTER_FLEE_TICKS = 3600;
    /** A monster inside this close to the door, and nearer it than the villager, blocks the way out. */
    private static final double DOOR_BLOCKED_DIST = 3.0;
    /** The longest a villager waits in a corner for the doorway to clear before running anyway (15 s). */
    private static final int MAX_CORNER_TICKS = 300;
    /** How long the afraid and relief bubbles show (3 s). */
    private static final int BUBBLE_TICKS = 60;
    /** The player who hit this villager keeps it hiding for at most this long after the last hit (1 min). */
    private static final int PLAYER_DANGER_TICKS = 1200;
    /** ... while within this many blocks across, */
    private static final double PLAYER_DANGER_RADIUS = ShelterWatch.DANGER_RADIUS;
    /** ... and this many up or down, as for monsters. */
    private static final double PLAYER_DANGER_HEIGHT = 6.0;

    private Vec3 threatPos;
    private int ticks = 0;
    private int repathTimer = 0;
    private BlockPos currentTarget;

    /** The run to a building; null for the plain run away. */
    @Nullable private DefenderRetreat retreat;
    @Nullable private LivingEntity threat;
    /** Ticks spent hiding so far, over every stay. */
    private int holdTicks;
    /** Ticks in a row with no monster near. */
    private int clearTicks;
    /** The hurt timestamp of the last hit handled, so one hit is handled once. */
    private int lastHitStamp = Integer.MIN_VALUE;
    /** The building a monster got into while this villager was in it, waiting in its far corner; else null. */
    @Nullable private Building cornerIn;
    @Nullable private LivingEntity cornerMob;
    private int cornerTicks;
    /** The building the brain found this villager in when a monster got in; null when it was not in one. */
    @Nullable private Building startedIn;
    /** This villager's own beat for its checks, so a crowd that fled together does not check together. */
    private int phase;

    /** False for a fighter keeping its distance (see {@link #kite}): no fright, no bubble. */
    private boolean scared = true;

    /** The player who last hit this villager on this flee to shelter; null when none, or long enough ago. */
    @Nullable private Player hitBy;
    /** Game time of {@link #hitBy}'s last hit. */
    private long hitByAt;
    /** The "still near, staying hidden" diary line is written once per hit. */
    private boolean hitByLogged;

    public FleeActivity(LivingEntity attacker) {
        this.threatPos = attacker.position();
        this.threat = attacker;
    }

    /**
     * The plain run away, for a fighter backing off to make room for its next
     * cast or shot. It is not afraid: no happiness lost, no "fleeing" bubble
     * and no danger memory. A mage kiting a zombie starts dozens of these in
     * one fight, and lost 5 happiness each time.
     */
    public static FleeActivity kite(LivingEntity attacker) {
        FleeActivity flee = new FleeActivity(attacker);
        flee.scared = false;
        return flee;
    }

    /** A scared villager's flee: into the safest nearby building, and stay until it is clear. */
    public static FleeActivity toShelter(LivingEntity attacker) {
        FleeActivity flee = new FleeActivity(attacker);
        flee.retreat = DefenderRetreat.forCivilian(SHELTER_RUN_SPEED);
        return flee;
    }

    /**
     * A flee to shelter for a villager who is in {@code building} (asleep in
     * its bed, or hiding there from the bell) when {@code mob} got in.
     */
    public static FleeActivity toShelterFrom(LivingEntity mob, Building building) {
        FleeActivity flee = toShelter(mob);
        flee.startedIn = building;
        return flee;
    }

    /** Whether this is a flee to shelter (not the plain run away). */
    public boolean isToShelter() {
        return retreat != null;
    }

    /** Whether this flee to shelter has arrived and is waiting it out. */
    public boolean isHiding() {
        return retreat != null && retreat.isHolding();
    }

    /** Whether this flee is on its way to (not yet in) {@code building}. */
    public boolean isRunningTo(Building building) {
        return retreat != null && retreat.isRunning() && retreat.shelter() == building;
    }

    /** The building this flee is running to or hiding in; null when none. */
    @Nullable
    public Building shelter() {
        return retreat != null ? retreat.shelter() : null;
    }

    /**
     * Runs on away from where {@code attacker} is now, without stopping: the
     * walk under way goes on until the new route is ready, and the five
     * seconds start again. A new flee stops the villager first and leaves it
     * standing while its route is worked out.
     */
    public void retarget(LivingEntity attacker) {
        threatPos = attacker.position();
        threat = attacker;
        repathTimer = 0;
        ticks = 0;
    }

    /**
     * Hit again while fleeing to shelter: the run re-aims from the attacker,
     * or a villager hiding moves away from it (see {@link DefenderRetreat#retarget}).
     * Each hit is handled once, however many ticks it is reported. The plain
     * run away ignores it, as it always has.
     */
    public void onAttacked(Context ctx, LivingEntity attacker) {
        if (retreat == null || !retreat.isActive()) return;
        int stamp = ctx.npc().getLastHurtByMobTimestamp();
        if (stamp == lastHitStamp) return;
        lastHitStamp = stamp;
        threat = attacker;
        threatPos = attacker.position();
        clearTicks = 0;
        if (attacker instanceof Player player) noteHitBy(player, ctx.level().getGameTime());
        if (cornerIn != null) {
            // Caught in the corner: run for it.
            endCorner(ctx, true);
            return;
        }
        retreat.retarget(ctx, attacker);
    }

    @Override
    protected void start(Context ctx) {
        ctx.model().stopMoving();
        if (scared) {
            ctx.brain().needs().onFleeStarted();
            // Being chased is the single most conversation-worthy thing that
            // happens to a villager, and the only place it is observable: by the
            // time the periodic state check runs the monster is usually gone.
            ctx.brain().rememberAndShow(MemoryTopic.DANGER,
                    ThoughtIcon.STATUS_FLEEING, FLEE_TICKS, ctx.level());
        }
        if (retreat != null) {
            lastHitStamp = ctx.npc().getLastHurtByMobTimestamp();
            phase = Math.floorMod(ctx.npc().getNpcId().hashCode(), BREACH_CHECK_TICKS);
            // A hit starts a flee from a player, or a Hostile one near (claims 11a; monsters are spotted, raiders heard).
            if (threat instanceof Player player) noteHitBy(player, ctx.level().getGameTime());
            // Woken (or found hiding) in a building a monster has got into,
            // with the monster between the villager and the door: the corner first.
            Building in = startedIn != null && startedIn.isValid() ? startedIn
                    : ShelterWatch.shelterAt(ctx.mgr(), ctx.npc().blockPosition(), false);
            LivingEntity inside = in != null ? ShelterWatch.look(ctx.mgr(), ctx.level(), in).inside() : null;
            if (inside != null) onBreach(ctx, inside);
            if (inside != null && doorBlocked(ctx, in, inside)) {
                retreat.beginHoldingIn(ctx, in, inside);
                startCorner(ctx, in, inside);
            } else {
                retreat.begin(ctx, threat != null && threat.isAlive() ? threat : null);
            }
        }
    }

    @Override
    protected Status tick(Context ctx) {
        if (retreat != null) return tickShelter(ctx);
        if (++ticks > FLEE_TICKS) return Status.DONE;
        ctx.npc().hurry(HurryGait.RUN);

        if (--repathTimer <= 0
                || currentTarget == null
                || ctx.model().hasRecentMoveFailure(currentTarget)
                || !ctx.model().isMoving()) {
            repathTimer = 20;
            currentTarget = pickFleeTarget(ctx);
            if (currentTarget == null) return Status.FAILED;
            if (!ctx.model().requestMoveTo(ctx.mgr().getGraph(), currentTarget, 0.7f)) {
                return Status.FAILED;
            }
        }
        return Status.RUNNING;
    }

    private Status tickShelter(Context ctx) {
        if (++ticks > MAX_SHELTER_FLEE_TICKS) return Status.DONE;
        if (cornerIn != null) {
            if ((++cornerTicks + phase) % CHECK_TICKS == 0 && cornerOver()) endCorner(ctx, cornerMustRun());
            if (cornerIn != null) {
                return retreat.tick(ctx, false) == DefenderRetreat.Phase.DONE ? Status.DONE : Status.RUNNING;
            }
        } else if ((ticks + phase) % BREACH_CHECK_TICKS == 0) {
            checkBreach(ctx);
        }
        boolean ready = false;
        if (retreat.isHolding()) {
            holdTicks++;
            if ((holdTicks + phase) % CHECK_TICKS == 0) {
                if (dangerNear(ctx)) clearTicks = 0;
                else clearTicks += CHECK_TICKS;
                Building shelter = retreat.shelter();
                if (shelter != null && shelter.isValid()) ShelterDoor.keepShut(ctx, shelter);
            }
            ready = clearTicks >= ALL_CLEAR_TICKS || holdTicks >= MAX_HOLD_TICKS;
        } else {
            // On the move again: the quiet has to start over once it is hiding.
            clearTicks = 0;
        }
        if (retreat.tick(ctx, ready) != DefenderRetreat.Phase.DONE) return Status.RUNNING;
        // It is over because nothing has come near for a while: relief.
        if (clearTicks >= ALL_CLEAR_TICKS) ctx.brain().showBubble(ThoughtIcon.EMOTE_RELIEF, BUBBLE_TICKS, ctx.level());
        return Status.DONE;
    }

    /**
     * A monster in the building hidden in or run to. The building is already
     * marked overrun for the whole village (the look does it) and the guards
     * are told. A villager inside with the monster between it and the door
     * waits in the far corner; otherwise it runs for the next building now.
     * One running to a building someone else saw a monster get into lately
     * picks another.
     */
    private void checkBreach(Context ctx) {
        Building shelter = retreat.shelter();
        if (shelter == null || !shelter.isValid()) return;
        BlockPos at = ctx.npc().blockPosition();
        boolean inThere = retreat.isHolding() || ShelterWatch.isOnFloor(shelter, at);
        LivingEntity mob = ShelterWatch.look(ctx.mgr(), ctx.level(), shelter).inside();
        if (mob != null) {
            onBreach(ctx, mob);
            if (inThere && doorBlocked(ctx, shelter, mob)) {
                startCorner(ctx, shelter, mob);
            } else {
                retreat.abandonShelter(ctx, mob);
            }
            return;
        }
        if (!inThere && ShelterWatch.isOverrun(ctx.mgr(), shelter, ctx.level().getGameTime())) {
            retreat.abandonShelter(ctx, null);
        }
    }

    /** A monster got into the building: an afraid bubble, and the guards hear of it. */
    private void onBreach(Context ctx, LivingEntity mob) {
        clearTicks = 0;
        ctx.brain().rememberAndShow(MemoryTopic.DANGER, ThoughtIcon.STATUS_AFRAID, BUBBLE_TICKS, ctx.level());
        com.alex.test.village.villagers.guard.behavior.GuardAlertSystem.reportThreat(ctx, ctx.npc(), mob);
    }

    /**
     * Whether {@code mob}, inside {@code building}, stands within
     * {@link #DOOR_BLOCKED_DIST} of its door and nearer to it than the villager:
     * running out would mean running into it.
     */
    private static boolean doorBlocked(Context ctx, Building building, LivingEntity mob) {
        if (building.getDoorPos() == null) return false;
        Vec3 door = Vec3.atCenterOf(building.getDoorPos());
        double mobToDoor = mob.position().distanceTo(door);
        return mobToDoor <= DOOR_BLOCKED_DIST && mobToDoor < ctx.npc().position().distanceTo(door);
    }

    /** Off to the tile farthest from the monster, to wait for the doorway to clear. */
    private void startCorner(Context ctx, Building building, LivingEntity mob) {
        cornerIn = building;
        cornerMob = mob;
        cornerTicks = 0;
        clearTicks = 0;
        BlockPos far = DefenderRetreat.farthestTileFrom(building, mob.position());
        if (far != null && !far.equals(ctx.npc().blockPosition())) retreat.moveWithin(ctx, far);
    }

    /** Whether the wait in the corner is over: the monster gone, out of the building, off the door, or too long. */
    private boolean cornerOver() {
        return cornerMustRun() || cornerMob == null || !cornerMob.isAlive() || !cornerIn.isValid()
                || !ShelterWatch.isOnFloor(cornerIn, cornerMob.blockPosition());
    }

    /**
     * Whether the corner ends in a run for the next building: the monster still
     * inside but off the doorway, or the wait ran too long. A monster that died
     * or walked back out leaves the villager hiding where it is.
     */
    private boolean cornerMustRun() {
        if (cornerTicks >= MAX_CORNER_TICKS) return true;
        if (cornerMob == null || !cornerMob.isAlive() || !cornerIn.isValid()) return false;
        if (!ShelterWatch.isOnFloor(cornerIn, cornerMob.blockPosition())) return false;
        Vec3 door = Vec3.atCenterOf(cornerIn.getDoorPos());
        return cornerMob.position().distanceTo(door) > DOOR_BLOCKED_DIST;
    }

    private void endCorner(Context ctx, boolean run) {
        LivingEntity mob = cornerMob;
        cornerIn = null;
        cornerMob = null;
        cornerTicks = 0;
        if (run) retreat.abandonShelter(ctx, mob);
    }

    /**
     * Whether a monster is still close: around the building hidden in, or around
     * the villager in the open. The player who hit it counts too, see {@link #hitByNear}.
     */
    private boolean dangerNear(Context ctx) {
        if (hitByNear(ctx)) return true;
        // Village claims 11a: a Hostile player near keeps it hidden, hit or not, for as long as one is.
        if (com.alex.test.village.claims.HostileWatch.nearestTo(ctx.mgr(), ctx.npc().position()) != null) return true;
        Building shelter = retreat.shelter();
        if (shelter != null && shelter.isValid()) {
            return ShelterWatch.look(ctx.mgr(), ctx.level(), shelter).dangerNear();
        }
        return ShelterWatch.dangerNear(ctx.mgr(), ctx.level(), ctx.npc().position());
    }

    private void noteHitBy(Player player, long now) {
        hitBy = player;
        hitByAt = now;
        hitByLogged = false;
    }

    /**
     * Player hit, step 3 ({@link WalkFeel#playerFlee}): the player who hit this
     * villager is danger while within {@link #PLAYER_DANGER_RADIUS} blocks of it,
     * for up to {@link #PLAYER_DANGER_TICKS} after the last hit. Players are not
     * monsters, so the hide used to end ten seconds in with the player beside it.
     */
    private boolean hitByNear(Context ctx) {
        if (!WalkFeel.playerFlee || hitBy == null) return false;
        if (!hitBy.isAlive() || hitBy.isRemoved() || hitBy.isSpectator() || hitBy.level() != ctx.level()
                || ctx.level().getGameTime() - hitByAt > PLAYER_DANGER_TICKS) {
            hitBy = null;
            return false;
        }
        Vec3 at = ctx.npc().position();
        double dx = hitBy.getX() - at.x;
        double dz = hitBy.getZ() - at.z;
        boolean near = dx * dx + dz * dz <= PLAYER_DANGER_RADIUS * PLAYER_DANGER_RADIUS
                && Math.abs(hitBy.getY() - at.y) <= PLAYER_DANGER_HEIGHT;
        if (near && !hitByLogged) {
            hitByLogged = true;
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " [PlayerFlee] "
                    + hitBy.getName().getString() + " still near: staying hidden");
        }
        return near;
    }

    @Override
    public void stop(Context ctx) {
        if (retreat != null) retreat.cancel(ctx);
        ctx.npc().stopHurry();
        ctx.model().stopMoving();
    }

    @Override
    public String name() { return "flee"; }

    private BlockPos pickFleeTarget(Context ctx) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || graph.isEmpty()) return null;

        Vec3 npcPos = ctx.npc().position();
        Vec3 away = npcPos.subtract(threatPos);
        if (away.lengthSqr() < 0.01D) away = new Vec3(1, 0, 0);
        away = away.normalize();

        NavNode best = null;
        double bestScore = -Double.MAX_VALUE;
        BlockPos current = ctx.npc().blockPosition();
        for (NavNode node : graph.nodesInBox(current, 24, MAX_TARGET_Y_DELTA)) {
            if (node.isAvoidedSurface()) continue;
            BlockPos pos = node.feetPos();
            if (Math.abs(pos.getY() - current.getY()) > MAX_TARGET_Y_DELTA) continue;

            Vec3 target = Vec3.atCenterOf(pos);
            double distFromNpc = target.distanceToSqr(npcPos);
            if (distFromNpc < MIN_TARGET_DIST_SQ || distFromNpc > MAX_TARGET_DIST_SQ) continue;

            Vec3 offset = target.subtract(npcPos);
            double alignment = offset.normalize().dot(away);
            double threatDistance = target.distanceToSqr(threatPos);
            double score = threatDistance + alignment * FLEE_DIST * FLEE_DIST - distFromNpc * 0.05D;
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }

        if (best != null) return best.feetPos();

        NavNode nearest = graph.nearestNonAvoided(current, 8, MAX_TARGET_Y_DELTA);
        return nearest != null ? nearest.feetPos() : null;
    }
}
