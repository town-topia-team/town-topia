package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.animation.HurryGait;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.TickStagger;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardArmorUtils;
import com.alex.test.village.villagers.guard.GuardAssignmentHandler;
import com.alex.test.village.villagers.guard.CaptainBalance;
import com.alex.test.village.villagers.guard.CombatRank;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.guard.GuardSkillEffects;
import com.alex.test.village.villagers.guard.GuardSwordUtils;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import com.alex.test.village.villagers.guard.behavior.combat.GuardCombatRules;
import com.alex.test.village.villagers.guard.behavior.combat.GuardFootwork;
import com.alex.test.village.villagers.guard.behavior.combat.GuardShieldTactics;
import com.alex.test.village.villagers.guard.behavior.combat.SquadTactics;
import com.alex.test.village.villagers.guard.behavior.combat.StrikeSchedule;
import com.alex.test.village.villagers.guard.behavior.patrol.GuardPostPatrol;
import com.alex.test.village.villagers.guard.behavior.patrol.GuardPatrolPlanner;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ToolUpgradeHelper;
import com.alex.test.village.building.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Night patrol and threat-response work loop for Guard villagers.
 *
 * <p>Threat response always wins over weapon crafting and patrol. Patrol stays in
 * the village nav graph. Combat closes in on a mob it can see with vanilla
 * navigation ({@link GuardFootwork}) and falls back on a village-graph route to a
 * spot within reach of it; it gives up when the target pulls too far away from
 * the village or reported villager.
 */
public final class GuardActivity extends WorkActivity {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Timing of start() and of the first tick after it; one INFO line at this long or more.
    private static final long SLOW_START_LOG_NANOS = 10_000_000L;

    private static final int SWORD_RETRY_COOLDOWN_TICKS = 600;
    private static final int ARMOR_RETRY_COOLDOWN_TICKS = 600;
    /** How often a fully dressed guard looks in storage for better armour (once a minute). */
    private static final int ARMOR_UPGRADE_CHECK_TICKS = 1200;
    private static final int TARGET_REPATH_INTERVAL = 30;
    /**
     * A guard runs to a mob farther than this, and slows to a walk for the last
     * few blocks once inside {@link #CHASE_RUN_UNTIL}: closing in and the
     * footwork at swing range are steps, not a sprint. Two distances, so a mob
     * hovering at the edge does not switch it back and forth.
     */
    private static final double CHASE_RUN_FROM = 7.0;
    private static final double CHASE_RUN_UNTIL = 5.0;
    // The time between swings is CombatRank.attackTicks: a guard 24 ticks at
    // level 1 down to 18 at 100, a captain 22 down to 16. The cooldown and the animation lock always match --
    // the old 20/30 pairing let the longer lock, not the cooldown, gate attacks.
    private static final int COMBAT_END_COOLDOWN_TICKS = 60;
    private static final int INVESTIGATE_TIMEOUT_TICKS = 160;
    private static final int RECENT_ATTACKER_MEMORY_TICKS = 200;
    private static final int COMBAT_INITIAL_REPATH_SPREAD_TICKS = 12;
    private static final double GIVE_UP_TARGET_DIST_SQ = 42.0 * 42.0;
    private static final double FOLLOW_PLAYER_COMFORT_DIST_SQ = 10.0 * 10.0;
    private static final double FOLLOW_PLAYER_REJOIN_DIST_SQ = 18.0 * 18.0;
    private static final int FOLLOW_PLAYER_PATROL_PAUSE_MIN_TICKS = 80;
    private static final int FOLLOW_PLAYER_PATROL_PAUSE_JITTER_TICKS = 80;
    private static final int FOLLOW_PLAYER_REPATH_MIN_TICKS = 60;
    private static final int FOLLOW_PLAYER_REPATH_JITTER_TICKS = 50;

    // Health fraction below which a guard breaks off and falls back: see
    // GuardBalance.retreatHealth. A guard used to fight to the death every
    // time, which is why a raid that outnumbered the watch killed all of it --
    // each guard traded itself for a fraction of one pillager instead of living
    // to fight beside the next one. Rolled 12-20% each day, at any level.

    /** Health fraction a retreating guard waits to reach before returning. */
    private static final float REGROUP_HEALTH_FRACTION = 0.7f;

    /**
     * Health fraction above which a guard fights whatever the odds.
     *
     * <p>The odds test alone made guards refuse to engage at all: three guards
     * meeting ten zombies counted the room, decided it was hopeless, and backed
     * off without a single swing -- and two of them died backing off. Being
     * outnumbered is a reason to break off a fight that is going badly, not a
     * reason never to start one, so it only counts once a guard is hurt.
     */
    private static final float ENGAGED_HEALTH_FRACTION = 0.7f;

    /** Hostiles per nearby guard past which a hurt guard stops trading. */
    private static final double RETREAT_ODDS = 2.5;

    /** Radius the odds are counted over. */
    private static final double ODDS_SCAN_RADIUS = 12.0;
    /** The odds are counted again at most this often. */
    private static final int ODDS_CHECK_TICKS = 10;

    /** A blow due within this many ticks lands before the guard breaks off. */
    private static final int BLOW_COMMIT_TICKS = 3;

    /**
     * How far a pair may drift before the follower breaks off and rejoins.
     *
     * <p>Twelve blocks: far enough that the two are not treading on each other
     * across a village square, close enough that both can reach the same fight.
     */
    private static final double PARTNER_REJOIN_DIST_SQ = 12.0 * 12.0;

    /** A graph route that has stopped moving this long without arriving is re-planned. */
    private static final int COMBAT_MOVE_IDLE_LIMIT = 5;
    /** Ticks between tries when no spot to strike from is found. */
    private static final int APPROACH_RETRY_TICKS = 10;
    /** Tries without a spot to strike from before the target is dropped. */
    private static final int APPROACH_FAILURES_TO_DROP = 3;
    /** A counter-swing started from beyond normal reach still lands this much farther out. */
    private static final double COUNTER_LAND_BONUS = 0.5;
    /** Defenders this close to a mob count when choosing a side of it to stand on. */
    private static final double FLANK_SCAN_RADIUS = 4.0;
    private static final int FLANK_SCAN_TICKS = 5;
    /** Vanilla: a hit within this many ticks of the last only deals the excess over it. */
    private static final int HURT_IMMUNITY_TICKS = 10;
    /** A guard whose mob is fully taken waits this long before going for another... */
    private static final int CROWDED_STEP_ASIDE_TICKS = 10;
    /** ...and leaves that mob to the others for this long. */
    private static final int STEP_ASIDE_TICKS = 60;

    private GetSwordActivity getSwordActivity;
    @Nullable private BlockPos claimedSwordUpgradeStorage;
    private GetArmorActivity getArmorActivity;
    private long nextArmorAttemptAt;
    /** Storage door held for an armour swap trip, as for a sword upgrade; null on a fill-a-slot trip. */
    @Nullable private BlockPos claimedArmorUpgradeStorage;
    /** Next look for better armour in storage: a timer, as storage's tool change count leaves armour out. */
    private long nextArmorUpgradeCheckAt;
    @Nullable private GetShieldActivity getShieldActivity;
    private long nextShieldAttemptAt;
    /** Landed hits since the last shield bash. */
    private int hitsSinceBash;
    /** Game time of the last voluntary raise or lower, so the stance does not flicker. */
    private long lastStanceChangeAt = Long.MIN_VALUE / 2;
    private static final int STANCE_DWELL_TICKS = 10;
    private final GuardFootwork footwork = new GuardFootwork();
    private final GuardShieldTactics shieldTactics = new GuardShieldTactics();
    /** Ticks the graph combat route has been standing still. */
    private int combatMoveIdleTicks;
    /** Tries in a row that found no spot to strike from. */
    private int approachFailures;
    /** Ticks this guard's target has had two other guards swinging at it. */
    private int crowdedTicks;
    /** Running at the mob rather than walking; see {@link #CHASE_RUN_FROM}. */
    private boolean chaseRunning;
    /** The swing in progress was started from beyond normal reach, answering a shove. */
    private boolean counterSwing;
    /**
     * The swing in progress is aimed at a mob backing away -- a skeleton keeping
     * its distance -- so the guard follows it in during the wind-up.
     */
    private boolean pursuitSwing;
    /** Whether this guard was on alert last tick, to note when it stands down. */
    private boolean wasOnAlert;
    /** Sword reach at this defender's level ({@link GuardBalance#swingReach}), refreshed every tick. */
    private double swingReach = GuardBalance.swingReach(1);
    @Nullable private List<NpcEntity> flankers;
    private long flankersAt;
    /** Tick count of the last time this guard was hurt, for healing after fights. */
    private int lastHurtTick = Integer.MIN_VALUE / 2;
    private MoveActivity patrolMove;
    private final GuardPostPatrol guardPostPatrol = new GuardPostPatrol();
    private MoveActivity protectMove;
    @Nullable private BlockPos protectMoveTarget;
    private MoveActivity investigateMove;
    private MoveActivity combatMove;
    @Nullable private BlockPos combatMoveTarget;

    @Nullable private LivingEntity target;
    @Nullable private UUID targetId;
    @Nullable private BlockPos investigatePos;
    private int investigateTicks;
    private int repathTimer;
    private int attackCooldown;
    private int attackLockTicks;
    /** Ticks until the swing in progress lands its blow; 0 when none is pending. */
    private int strikeIn;
    private int patrolPause;
    private int protectPause;
    private int protectRepathCooldown;
    private int combatCooldown;
    private long lastSeenToolStorageChangeId;
    private boolean swordAcquireIsUpgrade;
    /** Counts down to the next idle gear check (sword equip, sword/armor trips). */
    private int gearCheckTimer;

    private final DefenderRetreat retreat = new DefenderRetreat();
    /** Running to ring the village bell for a Bad Omen raid nobody has warned of. */
    private final RaidBellRunner bellRunner = new RaidBellRunner();
    /** The last odds count ({@link #isOutnumbered}) and when it was made. */
    private boolean outnumbered;
    private long oddsCheckedAt = Long.MIN_VALUE / 2;
    /** The hardest blow a hostile near it could land ({@link GuardCombatRules#heaviestBlowAgainst}), and when it was read. */
    private float heaviestBlow;
    private long heaviestBlowAt = Long.MIN_VALUE / 2;
    /** True from start() until the first doTick after it has run; that tick is timed. */
    private boolean firstTickPending;
    /** Laps of the timed first tick: duty and healing, target choice, guard post patrol. */
    private long firstTickDutyNanos;
    private long firstTickTargetNanos;
    private long firstTickPatrolNanos;

    @Override
    protected void start(Context ctx) {
        long startedAt = System.nanoTime();
        GuardAssignmentHandler.ensureStarterSword(ctx.npc());
        GuardSettings settings = GuardSettings.of(ctx.npc());
        settings.clearLegacyOrder();
        GuardSwordUtils.equipBestAllowedSword(ctx.npc(), settings.getAllowedSwords());
        guardPostPatrol.start(ctx);
        // Staggered by entity id so guards do not all check their gear on one tick.
        gearCheckTimer = 1 + Math.floorMod(ctx.npc().getId(), TickStagger.CHECK_PERIOD_TICKS);
        firstTickPending = true;
        long took = System.nanoTime() - startedAt;
        if (took >= SLOW_START_LOG_NANOS) {
            LOGGER.info("[GuardActivity {}] slow start {} ms", ctx.npc().getName().getString(), millis(took));
        }
    }

    @Override
    protected Status doTick(Context ctx) {
        if (!firstTickPending) return tickGuard(ctx);
        // The first tick after start() is timed; the laps are taken inside tickGuard.
        long startedAt = System.nanoTime();
        firstTickDutyNanos = 0L;
        firstTickTargetNanos = 0L;
        firstTickPatrolNanos = 0L;
        try {
            return tickGuard(ctx);
        } finally {
            firstTickPending = false;
            long took = System.nanoTime() - startedAt;
            if (took >= SLOW_START_LOG_NANOS) {
                LOGGER.info("[GuardActivity {}] slow first tick {} ms: duty={} target={} patrol={}",
                        ctx.npc().getName().getString(), millis(took), millis(firstTickDutyNanos),
                        millis(firstTickTargetNanos), millis(firstTickPatrolNanos));
            }
        }
    }

    /** Nanos as milliseconds with one decimal, for the timing lines. */
    private static double millis(long nanos) {
        return nanos / 100_000L / 10.0D;
    }

    private Status tickGuard(Context ctx) {
        if (!ctx.npc().getProfession().isGuardLike()) return Status.DONE;

        GuardSettings settings = GuardSettings.of(ctx.npc());
        Set<Item> allowedSwords = settings.getAllowedSwords();
        int skill = ctx.brain().defenderSkillLevel();
        CombatRank rank = rank(ctx);
        swingReach = rank.swingReach(skill);
        long lap = System.nanoTime();
        tickDutyAndHealing(ctx, rank, skill);
        tickShieldHousekeeping(ctx, rank, skill);
        tickCombatReady(ctx);
        firstTickDutyNanos = System.nanoTime() - lap;
        // Gear only changes now and then; combat re-equips on its own.
        boolean gearCheckDue = --gearCheckTimer <= 0;
        if (gearCheckDue) {
            gearCheckTimer = TickStagger.CHECK_PERIOD_TICKS;
            GuardSwordUtils.equipBestAllowedSword(ctx.npc(), allowedSwords);
        }

        if (attackCooldown > 0) attackCooldown--;
        if (combatCooldown > 0) combatCooldown--;
        // An arrow on its way, or a bow at full draw, beats finishing a swing:
        // drop it, and the shield comes up below in this same tick.
        if (attackLockTicks > 0 && isValidTarget(ctx, target)
                && shieldTactics.shotImminent(ctx, rank, skill)) {
            LOGGER.debug("[GuardCombat] {} dropped a swing to block a shot", ctx.npc().getName().getString());
            attackLockTicks = 0;
            strikeIn = 0;
            attackCooldown = 0;
            counterSwing = false;
            pursuitSwing = false;
        }
        // A guard that has fallen low mid-swing breaks off at once rather than
        // plant its feet for the rest of it -- unless the blow is about to land.
        if (attackLockTicks > 0 && !retreat.isActive()
                && (strikeIn == 0 || strikeIn > BLOW_COMMIT_TICKS)
                && isValidTarget(ctx, target) && shouldRetreat(ctx)) {
            attackLockTicks = 0;
            strikeIn = 0;
            attackCooldown = 0;
            counterSwing = false;
            pursuitSwing = false;
        }
        if (attackLockTicks > 0) {
            attackLockTicks--;
            boolean windingUp = strikeIn > 0;
            if (strikeIn > 0 && --strikeIn == 0) resolveStrike(ctx, allowedSwords);
            // Blow landed on a creeper whose fuse is burning: skip the follow-through
            // and get clear now rather than finish the swing beside it.
            if (strikeIn == 0 && target instanceof net.minecraft.world.entity.monster.Creeper creeper
                    && creeper.getSwellDir() > 0) {
                attackLockTicks = 0;
                attackCooldown = 0;
            }
            cancelCombatMove(ctx);
            footwork.stop(ctx);
            // A counter-swing, answered from where a shove left the guard, steps in
            // behind its blade during the wind-up, and so does a swing at a mob
            // backing off (a skeleton keeps its distance, and stepped out of reach
            // before the blow fell); an ordinary swing at a mob holding its ground
            // plants its feet.
            if (windingUp && strikeIn > 0 && (counterSwing || pursuitSwing) && isValidTarget(ctx, target)) {
                GuardFootwork.lungeStep(ctx, target, swingReach);
            } else {
                ctx.npc().holdStillForAnimation();
            }
            if (isValidTarget(ctx, target)) {
                GuardCombatRules.face(ctx.npc(), target.getEyePosition());
            }
            return Status.RUNNING;
        }

        lap = System.nanoTime();
        LivingEntity recentAttacker = recentDirectAttacker(ctx);
        // A Bad Omen raid nobody has rung the bell for: one guard who sees it runs to
        // ring it instead of fighting (hit on the way, it fights back first). Every
        // other fight is fought as always.
        if (recentAttacker == null && !retreat.isActive() && bellRunner.tick(ctx)) {
            cancelCombatMove(ctx);
            if (footwork.isActive()) footwork.stop(ctx);
            cancelPatrol(ctx);
            cancelGuardPostPatrol(ctx);
            cancelProtect(ctx);
            cancelInvestigation(ctx);
            ctx.npc().setTarget(null);
            return Status.RUNNING;
        }
        if (recentAttacker != null) bellRunner.interrupt(ctx);
        if (recentAttacker != null) {
            GuardAlertSystem.reportDirectDamageThreat(ctx, ctx.npc(), recentAttacker);
            // A guard already trading blows with its own mob finishes it first;
            // otherwise it turns on whatever hit it -- unless it has just found
            // no way to reach that mob (a skeleton shooting from a roof): turning
            // on it again would only freeze the guard where it is being shot.
            if (shouldAnswerAttacker(ctx, recentAttacker)
                    && !GuardAlertSystem.isGivenUp(ctx.npc(), recentAttacker, ctx.level().getGameTime())) {
                acceptAlertTarget(ctx, new GuardAlertSystem.GuardTarget(
                        recentAttacker,
                        recentAttacker.blockPosition(),
                        ctx.npc().blockPosition(),
                        true));
            }
        } else {
            GuardAlertSystem.GuardTarget alertTarget = GuardAlertSystem.findBestTargetForGuard(ctx);
            if (alertTarget != null) {
                acceptAlertTarget(ctx, alertTarget);
            } else if (isValidTarget(ctx, target) && !GuardAlertSystem.mayKeepTarget(ctx, target)) {
                // Its team has enough without it: back to patrol.
                diary(ctx, "GUARD leaving " + target.getName().getString() + " to its team");
                dropTarget(ctx);
            }
        }
        firstTickTargetNanos = System.nanoTime() - lap;

        // A guard already falling back keeps falling back: re-deciding every
        // tick while a pillager chases it would just walk it in circles.
        if (retreat.isActive()) {
            NpcEntity self = ctx.npc();
            boolean fit = self.getHealth() >= self.getMaxHealth() * REGROUP_HEALTH_FRACTION
                    && !shouldRetreat(ctx);
            DefenderRetreat.Phase phase = retreat.tick(ctx, fit);
            if (phase == DefenderRetreat.Phase.RUNNING) return Status.RUNNING;
            if (phase == DefenderRetreat.Phase.HOLDING) {
                // Inside and still hurt: stay put, but strike whatever comes within reach.
                if (isValidTarget(ctx, target)) return tickCombat(ctx, settings, allowedSwords, true);
                return Status.RUNNING;
            }
            endRetreat(ctx);
        }

        if (isValidTarget(ctx, target) && shouldRetreat(ctx)) {
            if (isLowOnHealth(ctx)
                    && GuardSkillEffects.trySecondWind(ctx.level(), ctx.npc(), rank, skill)) {
                diary(ctx, String.format("GUARD second wind (health=%.1f/%.1f)",
                        ctx.npc().getHealth(), ctx.npc().getMaxHealth()));
            } else {
                beginRetreat(ctx);
                return Status.RUNNING;
            }
        }

        if (isValidTarget(ctx, target)) {
            cancelSwordAcquire(ctx);
            cancelArmorAcquire(ctx);
            cancelShieldAcquire(ctx);
            cancelPatrol(ctx);
            cancelGuardPostPatrol(ctx);
            cancelProtect(ctx);
            cancelInvestigation(ctx);
            return tickCombat(ctx, settings, allowedSwords);
        }

        clearMonsterTargetingGuard(ctx, target);
        if (targetId != null) {
            GuardAlertSystem.clearTarget(ctx, ctx.npc().getNpcId(), targetId);
            targetId = null;
            investigatePos = null;
        }
        target = null;
        ctx.npc().setTarget(null);
        cancelCombatMove(ctx);
        if (footwork.isActive()) footwork.stop(ctx);
        shieldTactics.reset();
        approachFailures = 0;

        if (investigatePos != null) {
            Status investigation = tickInvestigate(ctx);
            if (investigation == Status.RUNNING) return Status.RUNNING;
            investigatePos = null;
            investigateMove = null;
            investigateTicks = 0;
            combatCooldown = COMBAT_END_COOLDOWN_TICKS;
        }

        // Threats interrupt equipment work. Only acquire a sword while the area is quiet.
        if (tickSwordAcquire(ctx, settings, allowedSwords, gearCheckDue) == Status.RUNNING) {
            return Status.RUNNING;
        }

        if (gearCheckDue && tryStartSwordUpgrade(ctx, allowedSwords)) {
            return Status.RUNNING;
        }

        if (tickArmorAcquire(ctx, gearCheckDue) == Status.RUNNING) {
            return Status.RUNNING;
        }

        if (tickShieldAcquire(ctx, skill, gearCheckDue) == Status.RUNNING) {
            return Status.RUNNING;
        }


        if (shouldFollowPlayer(ctx, settings)) {
            Status protect = tickProtectPlayer(ctx);
            if (protect == Status.RUNNING) return Status.RUNNING;
        } else {
            cancelProtect(ctx);
        }

        boolean scheduled = com.alex.test.village.villagers.guard.GuardShift.scheduled(ctx, settings);
        if (!scheduled && !GuardAlertSystem.hasEmergencyForGuard(ctx)) {
            return Status.DONE;
        }

        if (settings.useGuardPosts()) {
            lap = System.nanoTime();
            Status status = guardPostPatrol.tick(ctx, settings, combatCooldown);
            firstTickPatrolNanos = System.nanoTime() - lap;
            if (status == Status.RUNNING) return Status.RUNNING;
        } else {
            cancelGuardPostPatrol(ctx);
        }

        return tickPatrol(ctx);
    }


    private void cancelSwordAcquire(Context ctx) {
        if (getSwordActivity == null) return;
        getSwordActivity.stop(ctx);
        getSwordActivity = null;
        releaseSwordUpgradeClaim(ctx);
        swordAcquireIsUpgrade = false;
    }

    private void cancelPatrol(Context ctx) {
        if (patrolMove == null) return;
        patrolMove.stop(ctx);
        patrolMove = null;
    }

    private void cancelGuardPostPatrol(Context ctx) {
        guardPostPatrol.cancel(ctx);
    }

    private void cancelProtect(Context ctx) {
        if (protectMove != null) protectMove.stop(ctx);
        protectMove = null;
        protectMoveTarget = null;
        protectPause = 0;
        protectRepathCooldown = 0;
    }


    private void cancelInvestigation(Context ctx) {
        if (investigateMove == null) return;
        ctx.npc().stopHurry();
        investigateMove.stop(ctx);
        investigateMove = null;
        investigateTicks = 0;
    }

    private void cancelCombatMove(Context ctx) {
        if (combatMove != null) combatMove.stop(ctx);
        dropCombatMove();
    }

    /**
     * Lets go of the combat route without stopping the walk: the next route,
     * asked for at once, takes over from it when planned
     * ({@link WalkFeel#chaseHandover}).
     */
    private void dropCombatMove() {
        combatMove = null;
        combatMoveTarget = null;
        combatMoveIdleTicks = 0;
    }

    private void acceptAlertTarget(Context ctx, GuardAlertSystem.GuardTarget alertTarget) {
        cancelSwordAcquire(ctx);
        cancelArmorAcquire(ctx);
        cancelShieldAcquire(ctx);
        cancelPatrol(ctx);
        cancelGuardPostPatrol(ctx);
        cancelProtect(ctx);
        UUID previousTargetId = targetId;
        target = alertTarget.entity();
        targetId = target != null ? target.getUUID() : null;
        investigatePos = alertTarget.lastKnownMonsterPos();
        // However the mob was chosen, it counts against the guards on it.
        if (target != null) GuardAlertSystem.claim(ctx, ctx.npc(), target);
        if (targetId != null && !targetId.equals(previousTargetId)) {
            cancelCombatMove(ctx);
            repathTimer = asyncCombatRepathDelay(ctx);
            footwork.repathNow();
            approachFailures = 0;
        }
    }

    /**
     * Whether to turn on the mob that just hit this guard. Not while the guard
     * is already in reach of its own target -- it finishes that one -- and not
     * for a far-off shooter while its own target is nearer.
     */
    private boolean shouldAnswerAttacker(Context ctx, LivingEntity attacker) {
        if (!isValidTarget(ctx, target) || target == attacker) return true;
        NpcEntity npc = ctx.npc();
        if (canStrike(npc, target)) return false;
        if (canStrike(npc, attacker)) return true;
        return npc.distanceToSqr(attacker) + 1.0 < npc.distanceToSqr(target);
    }

    @Nullable
    private static LivingEntity recentDirectAttacker(Context ctx) {
        LivingEntity attacker = ctx.npc().getLastHurtByMob();
        if (attacker == null || !attacker.isAlive()) return null;
        return GuardThreatRules.isSelfDefenseTarget(ctx.npc(), attacker, RECENT_ATTACKER_MEMORY_TICKS)
                ? attacker : null;
    }

    private Status tickSwordAcquire(Context ctx, GuardSettings settings, Set<Item> allowedSwords,
                                    boolean mayStart) {
        if (getSwordActivity != null) {
            Status status = getSwordActivity.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            getSwordActivity.stop(ctx);
            getSwordActivity = null;
            releaseSwordUpgradeClaim(ctx);
            boolean wasUpgrade = swordAcquireIsUpgrade;
            swordAcquireIsUpgrade = false;
            if (status == Status.FAILED && !wasUpgrade) {
                settings.markSwordAcquireAttempt(ctx.level().getGameTime(), SWORD_RETRY_COOLDOWN_TICKS);
            } else {
                settings.clearSwordAcquireCooldown();
                GuardSwordUtils.equipBestAllowedSword(ctx.npc(), allowedSwords);
            }
            return Status.DONE;
        }

        if (mayStart && !allowedSwords.isEmpty()
                && !GuardSwordUtils.hasUsableAllowedSword(ctx.npc().getInventory(), allowedSwords)
                && settings.canAttemptSwordAcquire(ctx.level().getGameTime())) {
            cancelPatrol(ctx);
            cancelGuardPostPatrol(ctx);
            getSwordActivity = new GetSwordActivity();
            swordAcquireIsUpgrade = false;
            return Status.RUNNING;
        }
        return Status.DONE;
    }

    /**
     * Proactive armor pickup, mirroring {@link #tickSwordAcquire}: one storage
     * trip per missing slot (head/chest/legs/feet, in that order, skipping one
     * storage has nothing for), so a guard
     * gears up even on a run where it never needs food or a sword and so
     * never triggers {@link com.alex.test.village.villagers.supplies.StorageSupplyRestocker}'s
     * incidental restock. With no slot to fill (or its trip waiting out the
     * retry), a worn piece is swapped for a better one in storage; see
     * {@link #tryStartArmorUpgrade}.
     */
    private Status tickArmorAcquire(Context ctx, boolean mayStart) {
        if (getArmorActivity != null) {
            Status status = getArmorActivity.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            getArmorActivity.stop(ctx);
            getArmorActivity = null;
            boolean wasUpgrade = claimedArmorUpgradeStorage != null;
            releaseArmorUpgradeClaim(ctx);
            long now = ctx.level().getGameTime();
            if (wasUpgrade) {
                // After a swap the next slot may have a better piece waiting too.
                nextArmorUpgradeCheckAt = status == Status.FAILED ? now + ARMOR_UPGRADE_CHECK_TICKS : 0L;
            } else if (status == Status.FAILED) {
                nextArmorAttemptAt = now + ARMOR_RETRY_COOLDOWN_TICKS;
            }
            return Status.DONE;
        }

        if (!mayStart) return Status.DONE;

        // Armor already in the pack goes on before any trip for it.
        EquipmentSlot fromPack = GuardArmorUtils.wearFromPack(ctx.npc());
        if (fromPack != null) {
            diary(ctx, "GUARD put on " + ctx.npc().getItemBySlot(fromPack).getHoverName().getString()
                    + " from its pack");
        }

        long now = ctx.level().getGameTime();
        if (now >= nextArmorAttemptAt && GuardArmorUtils.firstMissingSlot(ctx.npc()) != null) {
            // The first empty slot storage can fill: always trying the first empty
            // one left boots in storage while the guard kept asking for a helmet.
            EquipmentSlot fillable = GetArmorActivity.firstFillableSlot(ctx);
            if (fillable != null) {
                cancelPatrol(ctx);
                cancelGuardPostPatrol(ctx);
                getArmorActivity = GetArmorActivity.forSlot(fillable);
                return Status.RUNNING;
            }
            // Nothing stocked for any of them: look again in 30 s, as after a failed
            // trip, which also left this "no tools" thought.
            nextArmorAttemptAt = now + ARMOR_RETRY_COOLDOWN_TICKS;
            if (ctx.npc().getInventory().hasEmptySlot()) ctx.brain().remember(MemoryTopic.NO_TOOLS, ctx.level());
        }
        return tryStartArmorUpgrade(ctx) ? Status.RUNNING : Status.DONE;
    }

    /**
     * A trip to trade one worn piece for a better one in storage, like a sword
     * upgrade: the old piece goes back to storage, stamped for the smith to melt
     * down. Without it the smith's "better armour for the watch" waited in storage
     * for good. Looked for once a minute, sooner after a swap.
     */
    private boolean tryStartArmorUpgrade(Context ctx) {
        long now = ctx.level().getGameTime();
        if (now < nextArmorUpgradeCheckAt) return false;
        nextArmorUpgradeCheckAt = now + ARMOR_UPGRADE_CHECK_TICKS;

        GetArmorActivity.Upgrade upgrade = GetArmorActivity.findUpgrade(ctx);
        if (upgrade == null) return false;

        // A busy door is retried on the next gear check, not a minute later.
        BlockPos claimPos = upgrade.storage().getDoorPos();
        if (!ctx.mgr().getClaims().claim(claimPos, ctx.model().npcId)) {
            nextArmorUpgradeCheckAt = 0L;
            return false;
        }

        cancelPatrol(ctx);
        cancelGuardPostPatrol(ctx);
        cancelProtect(ctx);
        claimedArmorUpgradeStorage = claimPos;
        getArmorActivity = GetArmorActivity.forUpgrade(upgrade.slot(), upgrade.storage());
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level())
                + " guard  heading to storage for better armour (" + upgrade.slot().getName() + ")");
        return true;
    }

    private void releaseArmorUpgradeClaim(Context ctx) {
        if (claimedArmorUpgradeStorage == null) return;
        ctx.mgr().getClaims().release(claimedArmorUpgradeStorage, ctx.model().npcId);
        claimedArmorUpgradeStorage = null;
    }

    private void cancelArmorAcquire(Context ctx) {
        if (getArmorActivity != null) {
            getArmorActivity.stop(ctx);
            getArmorActivity = null;
        }
        releaseArmorUpgradeClaim(ctx);
    }

    /**
     * Once the guard has learned shield block, one storage trip for a shield
     * when it carries none. The storage lookup is rationed like the armor
     * retry, so a village with no shield stocked is not searched every gear
     * check.
     */
    private Status tickShieldAcquire(Context ctx, int skill, boolean mayStart) {
        if (getShieldActivity != null) {
            Status status = getShieldActivity.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            getShieldActivity.stop(ctx);
            getShieldActivity = null;
            if (status == Status.FAILED) {
                nextShieldAttemptAt = ctx.level().getGameTime() + ARMOR_RETRY_COOLDOWN_TICKS;
            }
            return Status.DONE;
        }

        if (!mayStart || !rank(ctx).hasShieldBlock(skill)
                || ctx.level().getGameTime() < nextShieldAttemptAt) {
            return Status.DONE;
        }
        if (GuardSkillEffects.findShieldSlot(ctx.npc().getInventory()) >= 0) return Status.DONE;
        nextShieldAttemptAt = ctx.level().getGameTime() + ARMOR_RETRY_COOLDOWN_TICKS;
        if (!GetToolActivity.canAcquire(ctx, GetShieldActivity.SHIELD_REQUIREMENT)) return Status.DONE;

        cancelPatrol(ctx);
        cancelGuardPostPatrol(ctx);
        getShieldActivity = new GetShieldActivity();
        return Status.RUNNING;
    }

    private void cancelShieldAcquire(Context ctx) {
        if (getShieldActivity == null) return;
        getShieldActivity.stop(ctx);
        getShieldActivity = null;
    }

    /**
     * Practice for time on duty, and healing after fights: once nothing has
     * hurt the defender for {@link GuardBalance#REGEN_DELAY_TICKS} and it has no
     * target, it heals one point every {@link CombatRank#regenTicks} ticks.
     */
    private void tickDutyAndHealing(Context ctx, CombatRank rank, int skill) {
        NpcEntity npc = ctx.npc();
        if (Math.floorMod(ctx.level().getGameTime() + npc.getId(),
                GuardBalance.DUTY_XP_INTERVAL_TICKS) == 0) {
            ctx.brain().onDefenderDuty(ctx);
        }

        if (npc.hurtTime > 0) lastHurtTick = npc.tickCount;
        // Starving (hunger 0): no healing, or it would outlast the starving hits (10-19).
        if (ctx.brain().isStarving()) return;
        // Sheltering is not fighting: a retreating guard heals even while a mob is still about.
        if (npc.getHealth() >= npc.getMaxHealth()
                || (isValidTarget(ctx, target) && !retreat.isActive())) return;
        if (npc.tickCount - lastHurtTick < GuardBalance.REGEN_DELAY_TICKS) return;
        if (Math.floorMod(npc.tickCount + npc.getId(), rank.regenTicks(skill)) == 0) {
            npc.heal(1.0F);
        }
    }

    private float threatResponseSpeed(Context ctx) {
        double pace = ctx.npc().shieldStance().isRaised() ? GuardBalance.RAISED_SPEED_FACTOR : 1.0;
        return (float) (VillagerModel.DEFAULT_SPEED
                * rank(ctx).responseSpeed(ctx.brain().defenderSkillLevel()) * pace);
    }

    // =========================================================================
    // Shield stance
    // =========================================================================

    /**
     * Keeps the ready pose on while a live enemy is within
     * {@link GuardBalance#READY_POSE_RADIUS} blocks (a little more once it is on, so
     * the pose does not flicker at the edge). The client shows it only between swings
     * and only while the guard stands still, so it never fights the walk to the enemy.
     */
    private void tickCombatReady(Context ctx) {
        NpcEntity npc = ctx.npc();
        boolean alert = GuardAlertSystem.isOnAlert(npc);
        if (alert != wasOnAlert) {
            wasOnAlert = alert;
            if (!alert) diary(ctx, "GUARD standing down from alert");
        }
        // Only with an enemy close. (Holding it on alert with nothing to fight was
        // tried: mixamo_block is a looping parry, not a still stance, so an alert
        // guard stood bringing its sword up to its face and down for 30 s.)
        boolean on = false;
        if (!retreat.isRunning() && isValidTarget(ctx, target)) {
            // At least the swing reach plus the mob's half width, so the pose is on when it swings.
            double radius = Math.max(GuardBalance.READY_POSE_RADIUS, swingReach + 0.6)
                    + (npc.isCombatReady() ? 0.5 : 0.0);
            on = npc.distanceTo(target) <= radius;
        }
        npc.setCombatReady(on);
    }

    /**
     * Whether a swing at {@code foe} may start. Just after being hit the reach is
     * longer, so a guard that was shoved back can answer the mob that did it
     * instead of walking back in to be hit again: the swing then lunges in during
     * its wind-up ({@link GuardFootwork#lungeStep}) so the blade still meets it.
     */
    private boolean canStrike(NpcEntity npc, LivingEntity foe) {
        return GuardCombatRules.canMeleeAttack(npc, foe,
                swingReach + (isRecovering(npc) ? GuardBalance.RECOVERY_REACH_BONUS : 0.0));
    }

    /** Whether the blow of the swing in progress reaches {@code foe}. */
    private boolean canLand(NpcEntity npc, LivingEntity foe) {
        boolean longer = counterSwing || pursuitSwing || isRecovering(npc);
        return GuardCombatRules.canMeleeAttack(npc, foe,
                GuardCombatRules.landReach(swingReach) + (longer ? COUNTER_LAND_BONUS : 0.0));
    }

    private boolean isRecovering(NpcEntity npc) {
        return npc.tickCount - lastHurtTick < GuardBalance.RECOVERY_REACH_TICKS;
    }

    /**
     * Whether {@code foe} is moving away from the guard fast enough that a blow
     * started now would land on empty air without following it in.
     */
    private static boolean isBackingAway(NpcEntity npc, LivingEntity foe) {
        double ax = foe.getX() - npc.getX();
        double az = foe.getZ() - npc.getZ();
        double len = Math.sqrt(ax * ax + az * az);
        if (len < 1.0e-3) return false;
        net.minecraft.world.phys.Vec3 v = foe.getDeltaMovement();
        return (v.x * ax + v.z * az) / len > 0.03;
    }

    /** Whether {@code foe} is going for this guard -- hitting it or targeting it. */
    private static boolean isAttackingMe(NpcEntity npc, LivingEntity foe) {
        return (foe instanceof Mob mob && mob.getTarget() == npc)
                || GuardThreatRules.isSelfDefenseTarget(npc, foe, RECENT_ATTACKER_MEMORY_TICKS);
    }

    /** Ticks from the start of a swing to its blow. */
    private static int strikeTicks(int swingTicks) {
        return Math.max(1, Math.round(swingTicks * GuardBalance.STRIKE_POINT));
    }

    /**
     * Whether a blow started now would land while {@code foe} is still shrugging
     * off another defender's hit: vanilla only counts the damage above that hit
     * then, so with two guards on one mob the second one's swing was often
     * wasted. Waiting the tick or two it takes is cheaper.
     */
    private static boolean wouldHitImmune(LivingEntity foe, int strikeTicks) {
        return foe.invulnerableTime - strikeTicks > HURT_IMMUNITY_TICKS;
    }

    /** Lowers the shield. Safe to call whenever; does nothing when it is already down. */
    private static void lowerShield(Context ctx) {
        NpcEntity npc = ctx.npc();
        npc.shieldStance().lower();
        npc.setShieldRaised(false);
    }

    /**
     * Every tick: refill the guard meter while the shield is down, and keep the
     * shield down whenever the guard is not actually fighting -- no target,
     * mid-swing, or falling back. Also drops it when the shield is gone or the
     * skill no longer allows it.
     */
    private void tickShieldHousekeeping(Context ctx, CombatRank rank, int skill) {
        NpcEntity npc = ctx.npc();
        var stance = npc.shieldStance();
        boolean has = rank.hasShieldBlock(skill);
        if (has) stance.regen(rank.guardMeter(skill));
        if (!stance.isRaised() && !npc.isShieldRaised()) return;
        if (!has) {
            lowerShield(ctx);
        } else if ((attackLockTicks > 0 || retreat.isRunning() || !isValidTarget(ctx, target))
                && stance.mayLower(ctx.level().getGameTime())) {
            // Not before the raise animation has played -- see SHIELD_MIN_RAISE_TICKS.
            lowerShield(ctx);
        }
    }

    /**
     * Moves the shield to the wanted position ({@link GuardShieldTactics} decides
     * which). Voluntary changes wait out a short dwell so the stance does not
     * flicker; an urgent one -- a timed block, an arrow on its way, lowering to
     * swing back after a block -- does not. A changed stance re-plans the chase
     * at the new pace.
     */
    private void applyShieldStance(Context ctx, CombatRank rank, int skill, boolean wantUp, boolean urgent,
                                   boolean againstShots) {
        NpcEntity npc = ctx.npc();
        var stance = npc.shieldStance();
        long now = ctx.level().getGameTime();
        boolean raised = stance.isRaised();
        if (wantUp && againstShots) {
            // A shot is coming: raise now, with no dwell -- every time. It takes
            // the hit once the arm is up (GuardBalance.SHIELD_UP_TICKS).
            stance.raiseAgainstShots(now);
            if (!raised) {
                npc.setShieldRaised(true);
                lastStanceChangeAt = now;
                if (combatMove != null) {
                    cancelCombatMove(ctx);
                    repathTimer = 0;
                }
                footwork.repathNow();
            }
            return;
        }
        if (wantUp == raised) return;
        if (!urgent && now - lastStanceChangeAt < STANCE_DWELL_TICKS) return;
        // Even an urgent lowering waits for the raise animation to play.
        if (!wantUp && !stance.mayLower(now)) return;
        lastStanceChangeAt = now;
        if (wantUp) {
            stance.raise(now);
            npc.setShieldRaised(true);
        } else {
            lowerShield(ctx);
        }
        if (combatMove != null) {
            cancelCombatMove(ctx);
            repathTimer = 0;
        }
        footwork.repathNow();
    }

    private static CombatRank rank(Context ctx) {
        return CombatRank.of(ctx.npc().getProfession());
    }

    private boolean tryStartSwordUpgrade(Context ctx, Set<Item> allowedSwords) {
        if (allowedSwords.isEmpty()) return false;

        long newestChange = ToolUpgradeHelper.newestToolStorageChangeId(ctx);
        if (newestChange <= lastSeenToolStorageChangeId) return false;

        StorageBuilding upgradeStorage = ToolUpgradeHelper.nearestStorageWithBetterTool(
                ctx, GetSwordActivity.SWORD_REQUIREMENT);
        if (upgradeStorage == null) {
            lastSeenToolStorageChangeId = newestChange;
            return false;
        }

        // A busy door is retried on the next gear check, not forgotten until storage changes again.
        BlockPos claimPos = upgradeStorage.getDoorPos();
        if (!ctx.mgr().getClaims().claim(claimPos, ctx.model().npcId)) return false;
        lastSeenToolStorageChangeId = newestChange;

        cancelPatrol(ctx);
        cancelGuardPostPatrol(ctx);
        cancelProtect(ctx);
        claimedSwordUpgradeStorage = claimPos;
        getSwordActivity = GetSwordActivity.forUpgrade(upgradeStorage);
        swordAcquireIsUpgrade = true;
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level())
                + " guard  heading to storage for a better sword");
        return true;
    }

    private void releaseSwordUpgradeClaim(Context ctx) {
        if (claimedSwordUpgradeStorage == null) return;
        ctx.mgr().getClaims().release(claimedSwordUpgradeStorage, ctx.model().npcId);
        claimedSwordUpgradeStorage = null;
    }

    private Status tickCombat(Context ctx, GuardSettings settings, Set<Item> allowedSwords) {
        return tickCombat(ctx, settings, allowedSwords, false);
    }

    /**
     * @param standGround hold the spot -- a guard sheltering in a building swings
     *                    at what comes within reach but never goes after it
     */
    private Status tickCombat(Context ctx, GuardSettings settings, Set<Item> allowedSwords,
                              boolean standGround) {
        NpcEntity npc = ctx.npc();
        LivingEntity currentTarget = target;
        if (!isValidTarget(ctx, currentTarget)) return Status.DONE;

        if (GuardThreatRules.isTooFarFromVillage(ctx, currentTarget.blockPosition())
                && npc.distanceToSqr(currentTarget) > GIVE_UP_TARGET_DIST_SQ) {
            GuardAlertSystem.clearTarget(ctx, npc.getNpcId(), currentTarget.getUUID());
            target = null;
            targetId = null;
            investigatePos = null;
            npc.getNavigation().stop();
            npc.setTarget(null);
            combatCooldown = COMBAT_END_COOLDOWN_TICKS;
            return Status.RUNNING;
        }

        npc.setTarget(currentTarget);
        GuardAlertSystem.keepAlert(npc, ctx.level().getGameTime());
        // The tick it is hit -- and shoved -- re-plan the approach at once instead of
        // waiting out the repath interval, or a knocked-back guard stands there for up
        // to a second and a half while the mob that hit it keeps hitting.
        if (npc.hurtDuration > 0 && npc.hurtTime == npc.hurtDuration) {
            repathTimer = 0;
            footwork.repathNow();
        }
        // A fighting captain keeps its rallying cry up, renewed every second.
        if (rank(ctx) == CombatRank.CAPTAIN
                && CaptainBalance.hasRallyingCry(ctx.brain().defenderSkillLevel())
                && Math.floorMod(ctx.level().getGameTime() + npc.getId(), 20) == 0) {
            GuardSkillEffects.rally(ctx.level(), npc);
        }

        GuardSwordUtils.equipBestAllowedSword(npc, allowedSwords);

        int skill = ctx.brain().defenderSkillLevel();
        CombatRank rank = rank(ctx);
        int swingTicks = rank.attackTicks(skill);
        int strikeTicks = strikeTicks(swingTicks);
        GuardShieldTactics.Call shield = shieldTactics.decide(ctx, rank, skill, currentTarget, strikeTicks);
        // The high cover against shots and a creeper's blast, the close guard against blows.
        if (shield.raise()) {
            ctx.npc().setShieldHigh(shield.plan() == GuardShieldTactics.Plan.ADVANCE
                    || shield.plan() == GuardShieldTactics.Plan.EVADE);
        }
        applyShieldStance(ctx, rank, skill, shield.raise(), shield.urgent(),
                shield.plan() == GuardShieldTactics.Plan.ADVANCE);

        // An axe goes through a shield: step out of its reach, then back in to swing.
        if (shield.plan() == GuardShieldTactics.Plan.DODGE && shield.threat() != null) {
            cancelCombatMove(ctx);
            // A quick step back, not a run.
            stopChaseRun(npc);
            if (footwork.dodge(ctx, shield.threat(), threatResponseSpeed(ctx))) {
                Vec3 eye = shield.threat().getEyePosition();
                npc.getLookControl().setLookAt(eye.x, eye.y, eye.z, 30f, 30f);
                return Status.RUNNING;
            }
        }
        // A creeper with its fuse well lit: get clear of the blast, shield turned to it.
        if (shield.plan() == GuardShieldTactics.Plan.EVADE && shield.threat() != null) {
            cancelCombatMove(ctx);
            // Running clear of the blast.
            npc.hurry(runGait(npc));
            footwork.evade(ctx, shield.threat(), GuardShieldTactics.CREEPER_SAFE_DIST, threatResponseSpeed(ctx));
            Vec3 eye = shield.threat().getEyePosition();
            npc.getLookControl().setLookAt(eye.x, eye.y, eye.z, 30f, 30f);
            return Status.RUNNING;
        }

        // As many guards are already swinging at this mob as it can take and it is
        // not after this one: go to a mob with fewer guards on it if there is one.
        // The limit is soft -- with every mob full the guard stays and queues.
        if (!isAttackingMe(npc, currentTarget)
                && !StrikeSchedule.hasRoomFor(currentTarget, npc.getNpcId(), ctx.level().getGameTime())) {
            if (++crowdedTicks >= CROWDED_STEP_ASIDE_TICKS) {
                crowdedTicks = 0;
                if (GuardAlertSystem.hasLessLoadedThreat(ctx, currentTarget)) {
                    diary(ctx, "GUARD leaving " + currentTarget.getName().getString()
                            + " to the guards already on it");
                    GuardAlertSystem.stepAside(ctx, npc.getNpcId(), currentTarget.getUUID(), STEP_ASIDE_TICKS);
                    dropTarget(ctx);
                    combatCooldown = 0;
                    return Status.RUNNING;
                }
            }
        } else {
            crowdedTicks = 0;
        }

        if (!canStrike(npc, currentTarget)) {
            if (standGround) {
                footwork.stop(ctx);
                cancelCombatMove(ctx);
                stopChaseRun(npc);
                npc.getNavigation().stop();
                GuardCombatRules.face(npc, currentTarget.getEyePosition());
                return Status.RUNNING;
            }
            Status status = approach(ctx, currentTarget);
            // Advancing behind a raised shield: keep it turned to the blow.
            if (shield.faceAt() != null && npc.shieldStance().isRaised()) {
                npc.getLookControl().setLookAt(shield.faceAt().x, shield.faceAt().y, shield.faceAt().z, 30f, 30f);
            }
            return status;
        }

        footwork.stop(ctx);
        cancelCombatMove(ctx);
        stopChaseRun(npc);
        approachFailures = 0;
        npc.getNavigation().stop();
        npc.holdStillForAnimation();
        GuardCombatRules.face(npc, shield.faceAt() != null && npc.shieldStance().isRaised()
                ? shield.faceAt() : currentTarget.getEyePosition());
        long gameTime = ctx.level().getGameTime();
        if (attackCooldown <= 0 && shield.maySwing() && !wouldHitImmune(currentTarget, strikeTicks)
                && npc.shieldStance().mayLower(gameTime)
                && StrikeSchedule.canLandAt(currentTarget, npc.getNpcId(), gameTime + strikeTicks, gameTime,
                        isAttackingMe(npc, currentTarget))) {
            StrikeSchedule.book(currentTarget, npc.getNpcId(), gameTime + strikeTicks, gameTime);
            if (npc.shieldStance().isRaised()) {
                lowerShield(ctx);
                lastStanceChangeAt = gameTime;
            }
            GuardCombatRules.face(npc, currentTarget.getEyePosition());
            double edge = GuardCombatRules.edgeDistance(npc, currentTarget);
            counterSwing = edge > swingReach;
            pursuitSwing = GuardShieldTactics.isShooter(currentTarget) || isBackingAway(npc, currentTarget);
            npc.swing(InteractionHand.MAIN_HAND);
            npc.startAttackAnimation(swingTicks);
            attackCooldown = swingTicks;
            attackLockTicks = swingTicks;
            // The hit, the sweep and the bash land when the blade does, not when the
            // swing starts: the slash winds up first.
            strikeIn = strikeTicks;
            // Whatever happens during the swing, chase again the moment it ends --
            // the old repath timer was left at its full 30 ticks here, so a guard
            // whose mob stepped back stood still for a second and a half.
            repathTimer = 0;
            footwork.repathNow();
            LOGGER.debug("[GuardCombat] {} swings at {} edge={} {}", npc.getName().getString(),
                    currentTarget.getName().getString(), String.format("%.2f", edge),
                    counterSwing ? "counter" : pursuitSwing ? "pursuit" : "normal");
        }
        return Status.RUNNING;
    }

    /**
     * Closes on {@code foe}: straight at it with vanilla navigation when it is
     * near, level and in sight, else along the village graph to a spot within
     * reach of it. The target is dropped only after several tries find no way.
     */
    private Status approach(Context ctx, LivingEntity foe) {
        NpcEntity npc = ctx.npc();
        boolean ranged = GuardShieldTactics.isShooter(foe);
        float speed = threatResponseSpeed(ctx);
        tickChaseGait(npc, foe);

        if (footwork.canChase(ctx, foe, ranged)) {
            cancelCombatMove(ctx);
            if (footwork.chase(ctx, foe, speed, ranged, flankers(ctx, foe), swingReach)) {
                approachFailures = 0;
                return Status.RUNNING;
            }
            LOGGER.debug("[GuardCombat] {} direct chase of {} failed; using village routes",
                    npc.getName().getString(), foe.getName().getString());
            repathTimer = 0;
        } else if (footwork.isActive()) {
            footwork.stop(ctx);
        }

        // The spot being run to, offered to bestApproach to keep unless another is clearly better.
        BlockPos previousSpot = combatMoveTarget;
        boolean handedOver = false;
        if (combatMove != null) {
            boolean stalled = combatMoveStalled(ctx);
            boolean keep = combatMoveTarget != null
                    && GuardCombatRules.isApproachStillUseful(ctx, combatMoveTarget, foe)
                    && !stalled;
            if (keep) {
                Status status = combatMove.tickOuter(ctx);
                if (status == Status.RUNNING) return Status.RUNNING;
            }
            // The mob moved on while the guard was still running to its spot.
            // Stopping here braked the guard while the new route was planned,
            // and the new one then started on a tile it had already crossed.
            // The old route runs on until the new one takes over from it.
            handedOver = WalkFeel.chaseHandover && !keep && !stalled && ctx.model().isMoving();
            if (handedOver) {
                dropCombatMove();
            } else {
                cancelCombatMove(ctx);
            }
            repathTimer = 0;
        }

        if (--repathTimer > 0) {
            if (handedOver) ctx.model().stopMoving();
            return Status.RUNNING;
        }
        repathTimer = TARGET_REPATH_INTERVAL;
        BlockPos chaseTarget = GuardCombatRules.bestApproach(ctx, foe, flankers(ctx, foe), swingReach,
                previousSpot);
        if (chaseTarget == null) {
            if (handedOver) ctx.model().stopMoving();
            if (++approachFailures < APPROACH_FAILURES_TO_DROP) {
                repathTimer = APPROACH_RETRY_TICKS;
                return Status.RUNNING;
            }
            diary(ctx, String.format("GUARD can't reach %s (dist=%.1f), leaving it for now",
                    foe.getName().getString(), npc.distanceTo(foe)));
            GuardAlertSystem.giveUpUnreachable(ctx, npc.getNpcId(), foe,
                    GuardAlertSystem.GUARD_UNREACHABLE_RETRY_TICKS);
            dropTarget(ctx);
            return Status.RUNNING;
        }
        approachFailures = 0;
        combatMoveTarget = chaseTarget;
        combatMove = new MoveActivity(chaseTarget, speed, true);
        combatMoveIdleTicks = 0;
        Status status = combatMove.tickOuter(ctx);
        if (status != Status.RUNNING) {
            cancelCombatMove(ctx);
            repathTimer = 0;
        }
        return Status.RUNNING;
    }

    /**
     * Runs while {@code foe} is still a way off ({@link #CHASE_RUN_FROM}), walks
     * the last few blocks, and walks behind a raised shield, which slows the guard.
     */
    private void tickChaseGait(NpcEntity npc, LivingEntity foe) {
        double dist = npc.distanceTo(foe);
        if (npc.shieldStance().isRaised() || dist < CHASE_RUN_UNTIL) {
            chaseRunning = false;
        } else if (dist > CHASE_RUN_FROM) {
            chaseRunning = true;
        }
        if (chaseRunning) npc.hurry(runGait(npc));
        else npc.stopHurry();
    }

    private void stopChaseRun(NpcEntity npc) {
        chaseRunning = false;
        npc.stopHurry();
    }

    /**
     * A defender's run into a fight: the one-handed sword run with a blade in
     * hand, the plain run without. (The sword-and-shield run held a shield up in
     * front that a guard carries on its back; the great sword run carried the
     * blade into its own head. Both went.)
     */
    public static HurryGait runGait(NpcEntity npc) {
        ItemStack held = npc.getMainHandItem();
        return held.is(ItemTags.SWORDS) || held.getItem() instanceof AxeItem
                ? HurryGait.SWORD_RUN : HurryGait.RUN;
    }

    /**
     * True once the graph route has stood still for a few ticks without
     * arriving. The walker counts a route done within a block and a half of
     * its end while the move itself wants one block, so the guard could stop
     * just short and wait out the move's ten-second stuck timer within sight
     * of the mob.
     */
    private boolean combatMoveStalled(Context ctx) {
        if (ctx.model().isMoving()) {
            combatMoveIdleTicks = 0;
            return false;
        }
        return ++combatMoveIdleTicks >= COMBAT_MOVE_IDLE_LIMIT;
    }

    /** Other defenders around {@code foe}, refreshed every few ticks, for picking a free side of it. */
    private List<NpcEntity> flankers(Context ctx, LivingEntity foe) {
        long now = ctx.level().getGameTime();
        if (flankers == null || now >= flankersAt) {
            flankersAt = now + FLANK_SCAN_TICKS;
            flankers = SquadTactics.defendersAround(ctx.level(), ctx.npc(), foe, FLANK_SCAN_RADIUS);
        }
        return flankers;
    }

    /** Gives up the current target and pauses before the next. */
    private void dropTarget(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (targetId != null) GuardAlertSystem.clearTarget(ctx, npc.getNpcId(), targetId);
        target = null;
        targetId = null;
        investigatePos = null;
        cancelCombatMove(ctx);
        footwork.stop(ctx);
        npc.getNavigation().stop();
        npc.setTarget(null);
        approachFailures = 0;
        combatCooldown = COMBAT_END_COOLDOWN_TICKS;
    }

    /**
     * The blow itself, {@link GuardBalance#STRIKE_POINT} of the way through the
     * swing. Damage, sweep, bash, sword wear, experience and the kill all
     * happen here so they line up with the animation; a target that has moved
     * out of reach or died since the swing began is simply missed.
     */
    private void resolveStrike(Context ctx, Set<Item> allowedSwords) {
        NpcEntity npc = ctx.npc();
        LivingEntity currentTarget = target;
        if (!isValidTarget(ctx, currentTarget)) return;
        if (!canLand(npc, currentTarget)) {
            LOGGER.debug("[GuardCombat] {} missed {}: out of reach (edge={})", npc.getName().getString(),
                    currentTarget.getName().getString(),
                    String.format("%.2f", GuardCombatRules.edgeDistance(npc, currentTarget)));
            return;
        }

        int skill = ctx.brain().defenderSkillLevel();
        CombatRank rank = rank(ctx);
        int swordSlot = GuardSwordUtils.equipBestAllowedSword(npc, allowedSwords);
        ItemStack sword = swordSlot >= 0 ? npc.getInventory().getSlot(swordSlot) : ItemStack.EMPTY;
        // The sword's own damage at every level; only a captain's rallying cry adds to it.
        float damage = (float) (GuardSwordUtils.attackDamage(sword)
                * GuardSkillEffects.rallyDamageMultiplier(npc, ctx.level().getGameTime()));
        // Vanilla knockback on the target, as from a player's unsprinted hit.
        // A player's sword: Looting on the kill at its share (PlayerGear).
        boolean landed = com.alex.test.village.tokens.PlayerGear.withWorkingMainHand(npc,
                () -> currentTarget.hurt(npc.damageSources().mobAttack(npc), damage));
        // Only a blow that landed sweeps on into the mobs beside it.
        int sweepKills = landed && rank.hasSweep(skill)
                ? GuardSkillEffects.sweep(ctx.level(), npc, currentTarget, damage, rank.sweepFraction()) : 0;
        LOGGER.debug("[GuardCombat] {} {} {} for {}", npc.getName().getString(),
                landed ? "hit" : "was shrugged off by", currentTarget.getName().getString(),
                String.format("%.1f", damage));
        drawAggro(npc, currentTarget, landed);
        if (landed && currentTarget.isAlive() && rank.hasBash(skill)) {
            int shieldSlot = GuardSkillEffects.findShieldSlot(npc.getInventory());
            if (shieldSlot >= 0 && ++hitsSinceBash >= rank.bashEveryHits()) {
                hitsSinceBash = 0;
                GuardSkillEffects.bash(ctx.level(), npc, currentTarget, shieldSlot,
                        rank.bashIsShockwave());
            }
        }
        // The stress test crowd keeps its swords whole (NpcEntity.keepsGearWhole).
        if (!npc.keepsGearWhole())
            GuardSwordUtils.damageBestSwordAfterAttack(ctx.level(), npc.getInventory(), allowedSwords);
        GuardSwordUtils.equipBestAllowedSword(npc, allowedSwords);
        ctx.brain().onDefenderCombat(ctx, landed ? 1 : 0,
                (landed && !currentTarget.isAlive() ? 1 : 0) + sweepKills);
        if (!currentTarget.isAlive()) {
            // The one thing that makes a guard's day better -- see
            // WellbeingSystem.onThreatKilled for why it needs to.
            ctx.brain().needs().wellbeingRules().onThreatKilled(ctx.brain().needs());
            diary(ctx, "GUARD killed " + currentTarget.getName().getString());
            GuardAlertSystem.clearTarget(ctx, npc.getNpcId(), currentTarget.getUUID());
            target = null;
            targetId = null;
            investigatePos = null;
            npc.setTarget(null);
            combatCooldown = COMBAT_END_COOLDOWN_TICKS;
        }
    }

    /**
     * A blow from a guard turns the mob on the guard. Vanilla's hurt-by target
     * goal should do this by itself, but a zombie was seen staying on the
     * villager it was chasing through several of a guard's hits; a guard that
     * cannot pull a mob off a villager is not protecting anyone. Only a mob
     * going after someone other than a defender is turned -- one already on a
     * guard, captain or archer is left on it.
     */
    private static void drawAggro(NpcEntity guard, LivingEntity foe, boolean landed) {
        if (!landed || !foe.isAlive() || !(foe instanceof Mob mob)) return;
        LivingEntity chasing = mob.getTarget();
        if (chasing == guard) return;
        if (chasing instanceof NpcEntity other && other.getProfession().isDefender()) return;
        mob.setTarget(guard);
        LOGGER.debug("[GuardCombat] {} pulled {} off {} (vanilla had not switched it)",
                guard.getName().getString(), foe.getName().getString(),
                chasing == null ? "nothing" : chasing.getName().getString());
    }

    // =========================================================================
    // Falling back and regrouping
    // =========================================================================

    /**
     * Whether this fight is currently worth having.
     *
     * <p>Two ways to lose one: be nearly dead, or be badly outnumbered. Both
     * are counted locally -- what is within sight of this guard -- rather than
     * across the village, because a guard on the far side of the walls neither
     * knows nor can help.
     */
    private boolean shouldRetreat(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (isLowOnHealth(ctx)) return true;
        // A captain breaking off takes its hurt guards with it.
        if (rank(ctx) != CombatRank.CAPTAIN
                && npc.getHealth() < npc.getMaxHealth() * SquadTactics.FALL_BACK_HEALTH
                && SquadTactics.fallBackCalledNear(ctx.level(), npc, ctx.level().getGameTime())) {
            return true;
        }
        // A guard in good shape fights, however many of them there are.
        if (npc.getHealth() >= npc.getMaxHealth() * ENGAGED_HEALTH_FRACTION) return false;
        // The head count is an entity scan, and this runs every tick of a fight:
        // the last count holds for a few ticks.
        long now = ctx.level().getGameTime();
        if (now >= oddsCheckedAt && now - oddsCheckedAt < ODDS_CHECK_TICKS) return outnumbered;
        oddsCheckedAt = now;
        outnumbered = isOutnumbered(ctx);
        return outnumbered;
    }

    /** Whether the hostiles around this guard outnumber the defenders by more than {@link #RETREAT_ODDS}. */
    private static boolean isOutnumbered(Context ctx) {
        NpcEntity npc = ctx.npc();
        int hostiles = 0;
        int defenders = 1; // this guard
        net.minecraft.world.phys.AABB box =
                npc.getBoundingBox().inflate(ODDS_SCAN_RADIUS);
        for (LivingEntity entity :
                ctx.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (entity == npc) continue;
            if (GuardThreatRules.isHostileMonsterForGuard(npc, entity)) {
                hostiles++;
            } else if (entity instanceof NpcEntity other && other.isAlive()
                    && isDefender(other)) {
                defenders++;
            }
        }
        return hostiles > defenders * RETREAT_ODDS;
    }

    /**
     * Below the day's retreat health (GuardBalance.retreatHealth) -- lower
     * still while a captain's rallying cry is on it -- or low enough that the
     * hardest hitter going for it could finish it with one more blow. The roll
     * alone (12-20% of 30) let a vindicator's 13 kill a guard that was still
     * "fine": against small hitters nothing changes, against big ones a guard
     * turns while it still can.
     */
    private boolean isLowOnHealth(Context ctx) {
        NpcEntity npc = ctx.npc();
        double threshold = GuardBalance.retreatHealth(ctx.model().npcId, ctx.level().getDayTime());
        long now = ctx.level().getGameTime();
        if (GuardSkillEffects.isRallied(npc, now)) {
            threshold *= CaptainBalance.RALLY_RETREAT_FACTOR;
        }
        if (npc.getHealth() < npc.getMaxHealth() * threshold) return true;
        // An entity scan: the last answer holds for a few ticks.
        if (now < heaviestBlowAt || now - heaviestBlowAt >= ODDS_CHECK_TICKS) {
            heaviestBlowAt = now;
            heaviestBlow = GuardCombatRules.heaviestBlowAgainst(ctx.level(), npc);
        }
        return heaviestBlow > 0f && npc.getHealth() <= heaviestBlow;
    }

    private static boolean isDefender(NpcEntity npc) {
        return npc.getProfession().isDefender();
    }

    /**
     * Breaks off and runs for the closest building ({@link DefenderRetreat}):
     * the plan is made here, once, and the swing in progress is dropped so the
     * guard does not plant its feet for the rest of it with a mob on top of it.
     */
    private void beginRetreat(Context ctx) {
        NpcEntity npc = ctx.npc();
        LivingEntity threat = target;
        cancelCombatMove(ctx);
        footwork.stop(ctx);
        npc.getNavigation().stop();
        npc.setTarget(null);
        if (targetId != null) {
            GuardAlertSystem.clearTarget(ctx, npc.getNpcId(), targetId);
        }
        target = null;
        targetId = null;
        investigatePos = null;
        attackLockTicks = 0;
        strikeIn = 0;
        attackCooldown = 0;
        counterSwing = false;
        pursuitSwing = false;
        retreat.begin(ctx, threat);
        if (rank(ctx) == CombatRank.CAPTAIN) {
            SquadTactics.callFallBack(npc, ctx.level().getGameTime());
        }
        BlockPos door = retreat.shelterDoor();
        diary(ctx, String.format("GUARD falling back to %s (health=%.1f/%.1f)",
                door == null ? "nowhere" : door.toShortString(),
                npc.getHealth(), npc.getMaxHealth()));
    }

    private void endRetreat(Context ctx) {
        retreat.cancel(ctx);
        combatCooldown = COMBAT_END_COOLDOWN_TICKS;
        diary(ctx, "GUARD regrouped");
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    private void clearMonsterTargetingGuard(Context ctx, @Nullable LivingEntity rejectedTarget) {
        if (!(rejectedTarget instanceof Mob mob)) return;
        if (mob.getTarget() == ctx.npc()) {
            mob.setTarget(null);
        }
    }

    private Status tickInvestigate(Context ctx) {
        if (investigatePos == null) return Status.DONE;
        if (++investigateTicks > INVESTIGATE_TIMEOUT_TICKS) return Status.DONE;

        if (investigateMove == null) {
            BlockPos destination = investigatePos;
            NavGraph graph = ctx.mgr().getGraph();
            NavNode node = graph != null ? graph.nearest(destination, 10, 4) : null;
            investigateMove = new MoveActivity(node != null ? node.feetPos() : destination, threatResponseSpeed(ctx));
        }

        Status status = investigateMove.tickOuter(ctx);
        if (status == Status.RUNNING) {
            // Answering a call-out: running there.
            ctx.npc().hurry(runGait(ctx.npc()));
            return Status.RUNNING;
        }
        ctx.npc().stopHurry();
        investigateMove.stop(ctx);
        return Status.DONE;
    }

    /**
     * The "Follow player" setting: walks toward / holds near the nearest player
     * in the village using the comfort/rejoin band logic, and yields to normal
     * patrol once nobody is nearby.
     */
    private Status tickProtectPlayer(Context ctx) {
        ServerPlayer player = nearestPlayer(ctx);
        if (player == null) {
            cancelProtect(ctx);
            return Status.DONE;
        }
        cancelGuardPostPatrol(ctx);
        if (protectRepathCooldown > 0) protectRepathCooldown--;

        double dist = ctx.npc().distanceToSqr(player);

        if (protectMove != null) {
            Status status = protectMove.tickOuter(ctx);
            if (status == Status.RUNNING
                    && (dist <= FOLLOW_PLAYER_REJOIN_DIST_SQ || protectRepathCooldown > 0)) {
                return Status.RUNNING;
            }
            protectMove.stop(ctx);
            protectMove = null;
            protectMoveTarget = null;
            protectPause = FOLLOW_PLAYER_PATROL_PAUSE_MIN_TICKS
                    + ctx.npc().getRandom().nextInt(FOLLOW_PLAYER_PATROL_PAUSE_JITTER_TICKS + 1);
        }

        if (protectPause > 0 && dist <= FOLLOW_PLAYER_COMFORT_DIST_SQ) {
            protectPause--;
            ctx.npc().getNavigation().stop();
            return Status.RUNNING;
        }

        if (protectRepathCooldown > 0 && dist > FOLLOW_PLAYER_COMFORT_DIST_SQ) {
            return Status.RUNNING;
        }

        cancelPatrol(ctx);
        BlockPos target = dist > FOLLOW_PLAYER_REJOIN_DIST_SQ
                ? GuardPatrolPlanner.nearestVillageNode(ctx, player.blockPosition(), 12, 5)
                : GuardPatrolPlanner.playerPatrolPoint(ctx, player.blockPosition());
        if (target == null) {
            target = GuardPatrolPlanner.nearestVillageNode(ctx, player.blockPosition(), 12, 5);
        }
        if (target == null) {
            cancelProtect(ctx);
            ctx.npc().getNavigation().stop();
            return Status.DONE;
        }

        protectMoveTarget = target;
        protectMove = new MoveActivity(target, dist > FOLLOW_PLAYER_REJOIN_DIST_SQ ? 0.72f : VillagerModel.WALK_SPEED);
        protectRepathCooldown = FOLLOW_PLAYER_REPATH_MIN_TICKS
                + ctx.npc().getRandom().nextInt(FOLLOW_PLAYER_REPATH_JITTER_TICKS + 1);
        Status status = protectMove.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        protectMove.stop(ctx);
        protectMove = null;
        protectMoveTarget = null;
        return Status.RUNNING;
    }

    @Nullable
    private ServerPlayer nearestPlayer(Context ctx) {
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer player : ctx.level().players()) {
            if (player.isSpectator()) continue;
            if (!ctx.mgr().contains(player.blockPosition())) continue;
            double dist = ctx.npc().distanceToSqr(player);
            if (dist < bestDist) {
                bestDist = dist;
                best = player;
            }
        }
        return best;
    }

    private Status tickPatrol(Context ctx) {
        if (combatCooldown > 0) return Status.RUNNING;

        // A guard that has drifted away from its partner rejoins it, whatever
        // it was walking towards. Without this the pairing was only ever "set
        // off towards where my partner was standing when I chose": by the time
        // the follower arrived the leader had picked its own next anchor
        // thirty blocks away, and the two spent the night orbiting each other.
        if (patrolMove != null && hasDriftedFromPartner(ctx)) {
            patrolMove.stop(ctx);
            patrolMove = null;
            patrolPause = 0;
        }

        if (patrolPause > 0) {
            patrolPause--;
            return Status.RUNNING;
        }

        if (patrolMove == null) {
            BlockPos point = GuardPatrolPlanner.villagePatrolPoint(ctx);
            if (point == null) return Status.FAILED;
            // On alert the round is walked briskly.
            patrolMove = new MoveActivity(point, GuardAlertSystem.isOnAlert(ctx.npc()) ? 0.72f : VillagerModel.WALK_SPEED);
        }

        Status status = patrolMove.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        patrolMove.stop(ctx);
        patrolMove = null;
        // The follower of a pair takes a shorter breather, so it re-aims at
        // wherever its partner has got to rather than standing still while the
        // partner walks out of sight.
        patrolPause = GuardPatrolPlanner.isPatrolFollower(ctx)
                ? 5 + ctx.npc().getRandom().nextInt(15)
                : 25 + ctx.npc().getRandom().nextInt(55);
        return Status.RUNNING;
    }

    /** True when this guard is a follower and its partner has got too far. */
    private static boolean hasDriftedFromPartner(Context ctx) {
        if (!GuardPatrolPlanner.isPatrolFollower(ctx)) return false;
        NpcEntity partner = GuardPatrolPlanner.patrolPartner(ctx);
        if (partner == null || !partner.isAlive()) return false;
        return partner.distanceToSqr(ctx.npc()) > PARTNER_REJOIN_DIST_SQ;
    }

    private static int asyncCombatRepathDelay(Context ctx) {
        if (COMBAT_INITIAL_REPATH_SPREAD_TICKS <= 1) return 0;
        int slot = Math.floorMod(ctx.npc().getNpcId().hashCode(), COMBAT_INITIAL_REPATH_SPREAD_TICKS);
        int current = Math.floorMod((int) ctx.level().getGameTime(), COMBAT_INITIAL_REPATH_SPREAD_TICKS);
        return Math.floorMod(slot - current, COMBAT_INITIAL_REPATH_SPREAD_TICKS);
    }

    private boolean shouldFollowPlayer(Context ctx, GuardSettings settings) {
        if (!settings.followPlayer()) return false;
        return com.alex.test.village.villagers.guard.GuardShift.scheduled(ctx, settings);
    }

    /** The guard post this guard is watching at right now, if any. */
    @javax.annotation.Nullable
    public BlockPos activeGuardPost() { return guardPostPatrol.activePost(); }

    @Override
    public boolean isMovingPhase() {
        return target == null;
    }

    public boolean isThreatResponseActive() {
        return target != null
                || targetId != null
                || investigatePos != null
                || investigateMove != null
                || combatMove != null
                || footwork.isActive()
                || attackLockTicks > 0
                || bellRunner.isRunning()
                // Falling back hurt is still the fight: a cleric's heal must
                // not stop it on the way.
                || retreat.isRunning();
    }

    @Override
    public boolean allowsRoutineInterruption() {
        return target == null && !retreat.isRunning() && !bellRunner.isRunning();
    }

    @Override
    protected void doStop(Context ctx) {
        retreat.cancel(ctx);
        bellRunner.stop(ctx);
        lowerShield(ctx);
        ctx.npc().setCombatReady(false);
        chaseRunning = false;
        ctx.npc().stopHurry();
        if (getSwordActivity != null) {
            getSwordActivity.stop(ctx);
            getSwordActivity = null;
        }
        releaseSwordUpgradeClaim(ctx);
        swordAcquireIsUpgrade = false;
        cancelArmorAcquire(ctx);
        // A shield trip cut off by the end of the shift gives back the shield it reserved.
        cancelShieldAcquire(ctx);
        if (patrolMove != null) {
            patrolMove.stop(ctx);
            patrolMove = null;
        }
        cancelGuardPostPatrol(ctx);
        if (protectMove != null) {
            protectMove.stop(ctx);
            protectMove = null;
        }
        if (investigateMove != null) {
            investigateMove.stop(ctx);
            investigateMove = null;
        }
        if (combatMove != null) {
            combatMove.stop(ctx);
            combatMove = null;
            combatMoveTarget = null;
        }
        footwork.stop(ctx);
        shieldTactics.reset();
        attackLockTicks = 0;
        strikeIn = 0;
        counterSwing = false;
        pursuitSwing = false;
        if (targetId != null) GuardAlertSystem.clearTarget(ctx, ctx.npc().getNpcId(), targetId);
        ctx.model().stopMoving();
        ctx.npc().getNavigation().stop();
        ctx.npc().setTarget(null);
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    @Override
    public String name() { return "guard"; }

    /**
     * A mob this guard may fight: one its settings let it go after, or one that
     * has just hurt it -- a guard always hits back, whatever its mob list or
     * "attack hostile mobs" setting says.
     */
    private static boolean isValidTarget(Context ctx, @Nullable LivingEntity entity) {
        return GuardThreatRules.isHostileMonsterForGuard(ctx.npc(), entity)
                || GuardThreatRules.isSelfDefenseTarget(ctx.npc(), entity, RECENT_ATTACKER_MEMORY_TICKS);
    }

}
