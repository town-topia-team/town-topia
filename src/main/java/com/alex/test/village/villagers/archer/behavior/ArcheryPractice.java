package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity.Context;
import com.alex.test.village.brain.Activity.Status;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.archer.ArcherBalance;
import com.alex.test.village.villagers.archer.ArcherBowUtils;
import com.alex.test.village.villagers.archer.ArcherEvents;
import com.alex.test.village.villagers.archer.behavior.combat.ArcherAim;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.guard.behavior.combat.GuardCombatRules;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One practice session at a target, the way archers really practise: from
 * the lane's standing spot the archer shoots an end of {@value #END_ARROWS}
 * arrows, walks up to the target, pulls its arrows out, fetches any that
 * missed, and walks back, {@value #ENDS} ends in all.
 *
 * <p>Each arrow is scored where it struck the target (see
 * {@link ArcherEvents#targetScore(AbstractArrow)}): a bullseye shows the proud
 * icon, an end with half its arrows off the target the doubtful one. Practice
 * spends plain arrows only and never takes the quiver below the restock line.
 * The time from the first arrival at the lane is paid like a guard's drill,
 * {@link GuardBalance#DRILL_EXPERIENCE_PER_MINUTE}, however the session ends.
 *
 * <p>Lanes come from the village's Archery Ranges. Only a village with no
 * range lane at all falls back to the target blocks around its barracks
 * ({@link ArcherPracticeRange}). Practice is daylight only.
 *
 * <p>Shared by on-duty quiet time ({@link ArcherActivity}) and off-duty range
 * time ({@link RangePracticeActivity}); the owner ticks it and must call
 * {@link #stop} when it ends, however it ends.
 */
final class ArcheryPractice {

    private enum Phase { WALK_TO_STAND, SHOOT, LANDING, WALK_TO_TARGET, PULL, STRAYS, RETURN }

    static final int END_ARROWS = 6;
    static final int ENDS = 3;
    private static final float WALK_SPEED = VillagerModel.WALK_SPEED;
    /** Arrows are in flight well under a second at lane distance. */
    private static final int LANDING_TICKS = 30;
    private static final int PULL_EVERY_TICKS = 8;
    /** Waiting this long in all for a clear line of fire ends the session. */
    private static final int BLOCKED_GIVE_UP_TICKS = 200;
    private static final int BLOCKED_RECHECK_TICKS = 5;
    /** Close enough to the lane's spot once a walk ends. */
    private static final double ARRIVED_SQ = 2.0D * 2.0D;
    /** Misses lying further than this from the target are left to the after-fight pickup. */
    private static final double STRAY_RADIUS_SQ = 14.0D * 14.0D;
    private static final int BULLSEYE_THOUGHT_TICKS = 40;
    private static final int END_THOUGHT_TICKS = 60;

    /** Archer npc id -> game time before which no on-duty session starts. */
    private static final Map<UUID, Long> NEXT_ON_DUTY = new HashMap<>();
    /** Longer than any gap: a time further off than this is from another world's clock. */
    private static final long MAX_ON_DUTY_WAIT_TICKS = 24000L;

    private final ArrowRecovery recovery;
    private final BlockPos target;
    private final BlockPos stand;
    @Nullable private final BlockPos pullSpot;
    /** The range the lane belongs to; null for a barracks target. */
    @Nullable private final ArcheryRangeBuilding range;
    /** Off-duty range time ends each session back at the lane's spot, to rest there. */
    private final boolean returnToStand;

    private Phase phase = Phase.WALK_TO_STAND;
    @Nullable private MoveActivity move;
    private int endsDone;
    private int shotsThisEnd;
    /** A player's arrows alternate at practice: this one is spent, the next comes back. */
    private boolean spendNextPlayerArrow = true;
    private int drawTicks;
    private int cooldown;
    private int blockedTicks;
    private int phaseTicks;
    /** From the first arrival at the lane, every tick is practice time. */
    private boolean arrived;
    private int unpaidTicks;
    private boolean stopped;
    private final List<UUID> endArrows = new ArrayList<>();
    private final Map<UUID, Integer> endScores = new HashMap<>();

    private ArcheryPractice(ArrowRecovery recovery, BlockPos target, BlockPos stand,
                            @Nullable BlockPos pullSpot, @Nullable ArcheryRangeBuilding range,
                            boolean returnToStand) {
        this.recovery = recovery;
        this.target = target;
        this.stand = stand;
        this.pullSpot = pullSpot;
        this.range = range;
        this.returnToStand = returnToStand;
    }

    // =========================================================================
    // Starting
    // =========================================================================

    /**
     * Whether this archer's next on-duty session is due. Kept per archer, not
     * in its work activity: that is made anew after every chat, meal or shift
     * start, and each new one used to send the archer straight back to practise.
     */
    static boolean onDutySessionDue(Context ctx) {
        Long at = NEXT_ON_DUTY.get(ctx.model().npcId);
        long now = ctx.level().getGameTime();
        return at == null || now >= at || at - now > MAX_ON_DUTY_WAIT_TICKS;
    }

    /** No on-duty session for this archer for the next {@code ticks}. */
    static void delayOnDutySession(Context ctx, int ticks) {
        long now = ctx.level().getGameTime();
        NEXT_ON_DUTY.put(ctx.model().npcId, now + ticks);
        if (NEXT_ON_DUTY.size() > 256) NEXT_ON_DUTY.values().removeIf(at -> at < now);
    }

    /**
     * Bow, plain arrows, a full end above the restock line, daylight and no
     * alert; and a bow that is not worn, unless a spare waits in storage: a
     * worn bow's last shots are kept for real fights (see {@link BowWear}).
     */
    static boolean canPractiseNow(Context ctx) {
        NpcEntity npc = ctx.npc();
        return ArcherBowUtils.hasBow(npc.getInventory())
                && ArcherBowUtils.findPlainArrowSlot(npc.getInventory()) >= 0
                && ArcherBowUtils.countArrows(npc.getInventory())
                        >= ArcherBowUtils.refillLine(ctx.npc()) + END_ARROWS
                && !VillagerBrain.isNight(ctx)
                && !GuardAlertSystem.isOnAlert(npc)
                && ArcherBowUtils.setting(npc,
                        com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema.RANGE_PRACTICE)
                && BowWear.mayPractise(ctx);
    }

    /**
     * Claims a lane and starts a session there, or returns null: no lane free,
     * or not a time to practise. The nearest free range lane is taken; the
     * barracks targets only when no range in the village has a lane at all.
     */
    @Nullable
    static ArcheryPractice begin(Context ctx, ArrowRecovery recovery, boolean returnToStand) {
        if (!canPractiseNow(ctx)) return null;
        UUID self = ctx.model().npcId;
        BlockPos from = ctx.npc().blockPosition();

        boolean rangeLanes = false;
        ArcheryRangeBuilding bestRange = null;
        ArcheryRangeBuilding.Lane best = null;
        double bestSq = Double.MAX_VALUE;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof ArcheryRangeBuilding range) || !range.isValid()) continue;
            for (ArcheryRangeBuilding.Lane lane : range.getLanes()) {
                rangeLanes = true;
                if (!ArcheryLanes.isFree(ctx.level(), lane.target(), self)) continue;
                if (ctx.model().hasRecentMoveFailure(lane.stand())) continue;
                if (!ArcherPracticeRange.isTarget(ctx, lane.target())) continue;
                double d = lane.stand().distSqr(from);
                if (d < bestSq) {
                    bestSq = d;
                    best = lane;
                    bestRange = range;
                }
            }
        }
        if (best != null) {
            if (!ArcheryLanes.claim(ctx.level(), best.target(), best.stand(), self)) return null;
            return new ArcheryPractice(recovery, best.target(), best.stand(), best.pull(), bestRange,
                    returnToStand);
        }
        // Every lane busy: wait for one rather than wander off to the barracks.
        if (rangeLanes) return null;

        BlockPos target = ArcherPracticeRange.nearestFreeTarget(ctx);
        if (target == null) return null;
        BlockPos stand = ArcherPracticeRange.standFor(ctx, target);
        if (stand == null) return null;
        if (!ArcheryLanes.claim(ctx.level(), target, stand, self)) return null;
        return new ArcheryPractice(recovery, target, stand, ArcherPracticeRange.pullSpotFor(ctx, target),
                null, returnToStand);
    }

    BlockPos target() {
        return target;
    }

    BlockPos stand() {
        return stand;
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    /**
     * RUNNING while the session lasts; DONE when it is over (finished, or cut
     * short by an alert, nightfall, a broken target or an empty quiver);
     * FAILED when the lane could not be reached.
     */
    Status tick(Context ctx) {
        NpcEntity npc = ctx.npc();
        // Off duty the hand may hold something else (it ate); the bow is what it practises with.
        ArcherBowUtils.equipBow(npc);
        // The lane went to another archer while this one stood frozen (its
        // screen open, say): two at one target only block each other's line.
        if (!ArcheryLanes.renew(ctx.level(), target, stand, ctx.model().npcId)) return Status.DONE;
        if (arrived) unpaidTicks++;
        if (cooldown > 0) cooldown--;
        if (GuardAlertSystem.isOnAlert(npc) || VillagerBrain.isNight(ctx)
                || !ArcherBowUtils.hasBow(npc.getInventory())
                || !ArcherPracticeRange.isTarget(ctx, target)
                || range != null && (!range.isValid() || range.laneFor(target) == null)) {
            return Status.DONE;
        }
        return switch (phase) {
            case WALK_TO_STAND -> tickWalkToStand(ctx);
            case SHOOT -> tickShoot(ctx);
            case LANDING -> tickLanding(ctx);
            case WALK_TO_TARGET -> tickWalkToTarget(ctx);
            case PULL -> tickPull(ctx);
            case STRAYS -> tickStrays(ctx);
            case RETURN -> walk(ctx, stand) == Status.RUNNING ? Status.RUNNING : Status.DONE;
        };
    }

    private Status tickWalkToStand(Context ctx) {
        Status walked = walk(ctx, stand);
        if (walked == Status.RUNNING) return Status.RUNNING;
        if (walked == Status.FAILED) return Status.FAILED;
        arrived = true;
        startEnd();
        return Status.RUNNING;
    }

    private void startEnd() {
        phase = Phase.SHOOT;
        shotsThisEnd = 0;
        blockedTicks = 0;
        drawTicks = 0;
        endArrows.clear();
        endScores.clear();
    }

    private Status tickShoot(Context ctx) {
        NpcEntity npc = ctx.npc();
        Vec3 aim = ArcheryRangeBuilding.aimPoint(target, npc.getEyePosition());
        npc.holdStillForAnimation();
        GuardCombatRules.face(npc, aim);
        scoreLanded(ctx);
        int level = ctx.brain().defenderSkillLevel();

        if (drawTicks > 0) {
            if (--drawTicks > 0) return Status.RUNNING;
            // Somebody stepped into the line during the draw: let it down, no arrow.
            if (!ArcherPracticeRange.isLineClear(ctx, npc.getEyePosition(), target)) {
                letDown(npc);
                cooldown = BLOCKED_RECHECK_TICKS;
                return Status.RUNNING;
            }
            int slot = ArcherBowUtils.findPlainArrowSlot(npc.getInventory());
            if (slot >= 0 && ArcherBowUtils.countArrows(npc.getInventory())
                    > ArcherBowUtils.refillLine(ctx.npc())) {
                loose(ctx, npc, slot, aim);
            }
            cooldown = ArcherBalance.recoveryTicks(level);
            return Status.RUNNING;
        }

        boolean quiverLow = ArcherBowUtils.findPlainArrowSlot(npc.getInventory()) < 0
                || ArcherBowUtils.countArrows(npc.getInventory()) <= ArcherBowUtils.refillLine(ctx.npc());
        if (shotsThisEnd >= END_ARROWS || quiverLow) {
            // Nothing loosed this end: nothing to fetch, and nothing left to shoot.
            if (shotsThisEnd == 0) return Status.DONE;
            phase = Phase.LANDING;
            phaseTicks = 0;
            return Status.RUNNING;
        }
        if (cooldown > 0) return Status.RUNNING;
        if (!ArcherPracticeRange.isLineClear(ctx, npc.getEyePosition(), target)) {
            blockedTicks += BLOCKED_RECHECK_TICKS;
            if (blockedTicks > BLOCKED_GIVE_UP_TICKS) return Status.DONE;
            cooldown = BLOCKED_RECHECK_TICKS;
            return Status.RUNNING;
        }
        drawTicks = ArcherBalance.drawTicks(level);
        npc.startWorkAnimation(VillagerAnimationState.AIM, ArcherActivity.drawAnimationSpeed(drawTicks));
        return Status.RUNNING;
    }

    private Status tickLanding(Context ctx) {
        ctx.npc().holdStillForAnimation();
        scoreLanded(ctx);
        if (++phaseTicks < LANDING_TICKS) return Status.RUNNING;
        finishEnd(ctx);
        phase = Phase.WALK_TO_TARGET;
        return Status.RUNNING;
    }

    private Status tickWalkToTarget(Context ctx) {
        if (pullSpot == null) {
            phase = Phase.STRAYS;
            return Status.RUNNING;
        }
        // Pulled from wherever the walk ended: what is out of reach is fetched next.
        if (walk(ctx, pullSpot) == Status.RUNNING) return Status.RUNNING;
        phase = Phase.PULL;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private Status tickPull(Context ctx) {
        NpcEntity npc = ctx.npc();
        npc.holdStillForAnimation();
        GuardCombatRules.face(npc, Vec3.atCenterOf(target));
        if (++phaseTicks % PULL_EVERY_TICKS != 0) return Status.RUNNING;
        if (!recovery.pullOneInReach(ctx)) phase = Phase.STRAYS;
        return Status.RUNNING;
    }

    private Status tickStrays(Context ctx) {
        // At a range only what fell inside the fence (or stuck in it): one over
        // it would mean a walk round through the gate for every arrow.
        Status status = recovery.tickPractice(ctx, pos -> pos.distSqr(target) <= STRAY_RADIUS_SQ
                && (range == null || range.isWithinReachOfFloor(pos)));
        if (status == Status.RUNNING) return Status.RUNNING;
        recovery.cancel(ctx.npc());
        endsDone++;
        boolean quiverLow = ArcherBowUtils.countArrows(ctx.npc().getInventory())
                < ArcherBowUtils.refillLine(ctx.npc()) + 1
                || ArcherBowUtils.findPlainArrowSlot(ctx.npc().getInventory()) < 0;
        // The bow wore down to its last quarter this session, with no spare waiting.
        boolean bowSpent = !BowWear.mayPractise(ctx);
        if (endsDone >= ENDS || quiverLow || bowSpent) {
            if (!returnToStand) return Status.DONE;
            phase = Phase.RETURN;
            return Status.RUNNING;
        }
        phase = Phase.WALK_TO_STAND;
        return Status.RUNNING;
    }

    /** Walks to {@code goal}: RUNNING on the way, DONE there, FAILED if it never got close. */
    private Status walk(Context ctx, BlockPos goal) {
        if (move == null) move = new MoveActivity(goal, WALK_SPEED, true, true);
        Status status = move.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        move.stop(ctx);
        move = null;
        return status == Status.FAILED || ctx.npc().blockPosition().distSqr(goal) > ARRIVED_SQ
                ? Status.FAILED : Status.DONE;
    }

    // =========================================================================
    // Shooting and scoring
    // =========================================================================

    /** A plain practice arrow at the target's face: no rally, no piercing, just the archer's own aim. */
    private void loose(Context ctx, NpcEntity npc, int slot, Vec3 aim) {
        int level = ctx.brain().defenderSkillLevel();
        ItemStack arrowStack = npc.getInventory().getSlot(slot).copyWithCount(1);
        // As in combat: never fire "from" an empty hand (vanilla throws on it).
        ItemStack bow = npc.getMainHandItem();
        if (bow.isEmpty()) {
            int bowSlot = ArcherBowUtils.findBowSlot(npc.getInventory());
            if (bowSlot >= 0) bow = npc.getInventory().getSlot(bowSlot);
        }
        // As in a fight: faster and flatter with skill.
        float speed = ArcherBalance.arrowSpeed(level);
        AbstractArrow arrow = ArcherBowUtils.fullDrawArrow(npc, arrowStack, bow, speed);
        Vec3 dir = ArcherAim.direction(arrow.position(), aim, Vec3.ZERO, speed);
        arrow.shoot(dir.x, dir.y, dir.z, speed, ArcherBalance.inaccuracy(level));
        // Infinity: a plain arrow is not used up, and cannot be picked up again
        // (it is still pulled out of the target, for nothing).
        boolean infinite = ArcherBowUtils.isInfiniteShot(ctx.level(), bow, arrowStack);
        // A player's arrows wear out at practice: every other one is spent,
        // never pulled back out of the target. The village's own all come back.
        boolean spent = false;
        if (!infinite && com.alex.test.village.tokens.PlayerGear.isArrow(arrowStack)
                && !com.alex.test.village.tokens.VillageTokenBinding.isBound(arrowStack)) {
            spent = spendNextPlayerArrow;
            spendNextPlayerArrow = !spendNextPlayerArrow;
        }
        if (infinite || spent) arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        ctx.level().playSound(null, npc.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        ctx.level().addFreshEntity(arrow);
        com.alex.test.village.farawake.FarExtras.trackShot(arrow, npc); // flies on past the simulation distance
        recovery.remember(arrow, infinite || spent ? ItemStack.EMPTY : arrowStack,
                ctx.level().getGameTime(), true);
        endArrows.add(arrow.getUUID());
        shotsThisEnd++;
        if (!infinite) npc.getInventory().reduceSlot(slot, 1);
        // Practice wears the bow like any shot.
        BowWear.afterShot(ctx);
        npc.swing(InteractionHand.MAIN_HAND);
    }

    /** Scores the arrows of this end that have landed; a bullseye shows the proud icon at once. */
    private void scoreLanded(Context ctx) {
        for (UUID id : endArrows) {
            if (endScores.containsKey(id)) continue;
            Entity entity = ctx.level().getEntity(id);
            if (!(entity instanceof AbstractArrow arrow)) continue;
            int score = ArcherEvents.targetScore(arrow);
            if (score > 0) {
                endScores.put(id, score);
                if (score >= ArcherEvents.BULLSEYE_SCORE) {
                    ctx.npc().showBubble(ThoughtIcon.EMOTE_PRIDE, false, BULLSEYE_THOUGHT_TICKS);
                }
            } else if (ArrowRecovery.isLying(arrow)) {
                endScores.put(id, 0);
            }
        }
    }

    /** The end's scores in the diary; half the arrows off the target shows the doubtful icon. */
    private void finishEnd(Context ctx) {
        StringBuilder scores = new StringBuilder();
        int total = 0;
        int misses = 0;
        for (UUID id : endArrows) {
            int score = endScores.getOrDefault(id, 0);
            if (score <= 0) misses++;
            total += Math.max(0, score);
            scores.append(' ').append(score);
        }
        if (misses * 2 >= endArrows.size() && !endArrows.isEmpty()) {
            ctx.npc().showBubble(ThoughtIcon.EMOTE_DOUBT, false, END_THOUGHT_TICKS);
        }
        ctx.brain().diary().log(String.format("%s PRACTICE end %d/%d at %s scores:%s (total %d of %d)",
                VillagerDiary.ts(ctx.level()), endsDone + 1, ENDS, target.toShortString(), scores,
                total, endArrows.size() * 15));
    }

    private void letDown(NpcEntity npc) {
        drawTicks = 0;
        if (npc.getAnimation() == VillagerAnimationState.AIM) npc.setAnimation(VillagerAnimationState.IDLE);
    }

    // =========================================================================
    // Ending
    // =========================================================================

    /** Ends the session however it ended: the lane is freed and the time spent is paid. */
    void stop(Context ctx) {
        if (stopped) return;
        stopped = true;
        NpcEntity npc = ctx.npc();
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
        recovery.cancel(npc);
        letDown(npc);
        ArcheryLanes.release(ctx.level(), target, ctx.model().npcId);
        if (unpaidTicks > 0 && npc.getProfession() == Profession.ARCHER) {
            ctx.brain().onDefenderDrill(ctx, unpaidTicks / 1200f,
                    GuardBalance.DRILL_EXPERIENCE_PER_MINUTE * unpaidTicks / 1200f);
        }
        unpaidTicks = 0;
    }
}
