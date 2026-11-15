package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.animation.HurryGait;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EatActivity;
import com.alex.test.village.brain.FleeActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.archer.ArcherBalance;
import com.alex.test.village.villagers.archer.ArcherBowUtils;
import com.alex.test.village.villagers.archer.ArcherEvents;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.villagers.archer.behavior.combat.ArcherAim;
import com.alex.test.village.villagers.archer.behavior.combat.ArcherCombatRules;
import com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import com.alex.test.village.villagers.guard.behavior.DefenderRetreat;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.guard.behavior.GuardPostRegistry;
import com.alex.test.village.villagers.guard.behavior.combat.GuardCombatRules;
import com.alex.test.village.villagers.guard.behavior.patrol.GuardPatrolPlanner;
import com.alex.test.village.villagers.guard.behavior.patrol.GuardPostPatrol;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ToolUpgradeHelper;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.WatchtowerBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;

import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ranged combat and patrol work loop for Archer villagers.
 *
 * <p>Deliberately does not subclass {@link com.alex.test.village.villagers.guard.behavior.GuardActivity}:
 * that class hard-rolls melee-only positioning (attack-range closing,
 * attack-lock animation ticks) with no extension points, and adding them
 * purely so a second subclass could override them would have meant editing
 * guard combat's tested, load-bearing tick loop for the sake of a shape that
 * archer's ranged/retreat logic does not actually share. Instead this reuses
 * every *behaviour-neutral* piece guard combat already owns directly:
 * {@link GuardAlertSystem} for threat detection/assignment,
 * {@link GuardThreatRules} for the hostile-mob policy,
 * {@link GuardCombatRules#face} for aiming, {@link GuardPatrolPlanner} for
 * patrol points, and {@link FleeActivity} for the actual "run away from this
 * entity" pathing — only the ranged-specific pieces (bow/arrow logistics,
 * shoot-band positioning, firing) are archer's own code.
 */
public final class ArcherActivity extends WorkActivity {

    private static final double MELEE_DANGER_RANGE = 5.0D;
    /**
     * How far a hostile may drift from the position a flee was built against
     * before the flee is rebuilt. FleeActivity snapshots the threat position
     * once and then runs for five seconds, so without this an archer sprints
     * away from where the zombie *was* while the zombie -- which chases the
     * moment it is shot -- simply closes again. That is how archers ended up
     * trading blows at 1.5 blocks instead of kiting.
     */
    private static final double FLEE_RESNAP_DIST_SQ = 3.0D * 3.0D;
    private static final double MELEE_DANGER_RANGE_SQ = MELEE_DANGER_RANGE * MELEE_DANGER_RANGE;
    private static final double MIN_SHOOT_RANGE = 6.0D;
    private static final double MIN_SHOOT_RANGE_SQ = MIN_SHOOT_RANGE * MIN_SHOOT_RANGE;
    private static final double GIVE_UP_TARGET_DIST_SQ = 42.0D * 42.0D;

    private static final float ARCHER_KITE_SPEED = VillagerModel.DEFAULT_SPEED * 1.3F;
    private static final int TARGET_REPATH_INTERVAL = 20;
    private static final int COMBAT_END_COOLDOWN_TICKS = 60;
    private static final int RECENT_ATTACKER_MEMORY_TICKS = 200;
    private static final int RETRY_COOLDOWN_TICKS = 600;
    /**
     * Combat ticks without loosing an arrow before the archer gives the
     * target up. Holding a target locks out every hunger override, so a
     * standoff it can never win -- no arrows, or no node with a clear shot at
     * a mob in a pit or on a roof -- used to last until it starved.
     */
    private static final int STANDOFF_GIVE_UP_TICKS = 200;
    /** How long a given-up target is ignored by proactive targeting. */
    private static final int STANDOFF_IGNORE_TICKS = 1200;
    /**
     * Walking to a spot to shoot from does not count as a standoff -- a good
     * spot can be a long walk away -- but this many ticks of walking without
     * a single arrow loosed still gives the mob up.
     */
    private static final int CHASE_GIVE_UP_TICKS = 600;
    /**
     * A mob further than the archer's longest shot plus this is run to first,
     * along the village graph to beside where it stands; the spot to shoot
     * from is picked once it is close (see tickFarApproach).
     */
    private static final double FAR_APPROACH_MARGIN = 10.0D;
    /** The run is planned again once the mob has moved this far from where it stood. */
    private static final double FAR_APPROACH_REPLAN_SQ = 8.0D * 8.0D;
    /** This long without getting a block closer gives the mob up as out of reach. */
    private static final int FAR_APPROACH_STALL_TICKS = 300;
    /** A run that ended or failed short of the mob is planned again after this. */
    private static final int FAR_APPROACH_RETRY_TICKS = 20;
    /** A spot to shoot from that the mob moved away from is replaced this soon. */
    private static final int STALE_SPOT_REPATH_TICKS = 4;
    /**
     * With nothing to shoot, a mob closer than this is run from; one further
     * away is left to the others while the archer goes for arrows.
     */
    private static final double UNARMED_FLEE_RANGE = 8.0D;
    private static final double UNARMED_FLEE_RANGE_SQ = UNARMED_FLEE_RANGE * UNARMED_FLEE_RANGE;
    /** No guard or captain this close: the archer fights alone and keeps more distance. */
    private static final double SOLO_CHECK_RADIUS = 24.0D;
    private static final double SOLO_DANGER_RANGE_SQ = 7.0D * 7.0D;
    private static final double SOLO_MIN_SHOOT_RANGE = 8.0D;
    /** A guard this close, on the far side from the mob, is run to instead of just away. */
    private static final double HAVEN_RADIUS = 16.0D;
    /** How far behind the guard (seen from the mob) the archer stops. */
    private static final double HAVEN_BEHIND_DIST = 3.0D;
    private static final double HAVEN_ARRIVED_SQ = 1.5D * 1.5D;
    private static final double HAVEN_REPLAN_SQ = 3.0D * 3.0D;
    /** Three to five minutes of duty between practice sessions. */
    private static final int PRACTICE_GAP_TICKS = 3600;
    private static final int PRACTICE_GAP_JITTER_TICKS = 2400;
    /** No mob this close: a starving archer may stop to eat mid-fight. */
    private static final double MEAL_BREAK_SAFE_RANGE_SQ = 8.0D * 8.0D;

    /** Health fraction a sheltering archer waits to reach before going back out. */
    private static final float REGROUP_HEALTH_FRACTION = 0.7f;

    private final DefenderRetreat retreat = new DefenderRetreat();
    /** Tick count of the last time this archer was hurt, for healing after fights. */
    private int lastHurtTick = Integer.MIN_VALUE / 2;
    /** The hardest blow a hostile near it could land, read at most every {@link #BLOW_CHECK_TICKS}. */
    private float heaviestBlow;
    private long heaviestBlowAt = Long.MIN_VALUE / 2;
    private static final int BLOW_CHECK_TICKS = 10;

    private GetBowActivity getBowActivity;
    private GetArrowsActivity getArrowsActivity;
    private FletchArrowsActivity fletchActivity;
    /**
     * This archer's missed arrows, picked up once the fight is over. The
     * archer's shared tracker from {@link #start} on, so arrows left by range
     * time or by a session a chat cut short are picked up too.
     */
    private ArrowRecovery recovery = new ArrowRecovery();
    /** Loot and fallen villagers' gear, gathered and banked once the danger is cleared. */
    private final FieldPickup pickup = new FieldPickup();
    private long nextBowAttemptAt;
    /** Next look for a better bow in storage, or a spare to make. */
    private long nextBowCheckAt;
    private long lastSeenBowStorageChange;
    private static final int BOW_CHECK_TICKS = 600;
    /** A Stock tab order being made and taken to storage, if any. */
    @Nullable private FletchArrowsActivity orderWork;
    private long nextOrderCheckAt;
    private static final int ORDER_CHECK_TICKS = 200;
    private long nextArrowAttemptAt;

    private FleeActivity fleeActivity;
    /** Where the hostile stood when the current flee was built. */
    @Nullable private Vec3 fleeSnapshot;
    private MoveActivity repositionMove;
    @Nullable private BlockPos repositionTarget;
    /** The run towards a mob far across the village (see tickFarApproach). */
    @Nullable private MoveActivity approachMove;
    /** Running: to a far mob, or behind a guard. Dropped when either ends. */
    private boolean hurrying;
    /** Where the mob stood when that run was planned. */
    @Nullable private BlockPos approachMobAt;
    /** The closest the run has got so far, in blocks. */
    private double approachBest = Double.MAX_VALUE;
    private int approachStallTicks;
    private int approachRetryTicks;
    /** The mob the diary last noted a run to, so each run is noted once. */
    @Nullable private UUID approachLoggedFor;
    private MoveActivity patrolMove;
    private final GuardPostPatrol postPatrol = new GuardPostPatrol();
    /** The watchtower this archer keeps watch on, if any. */
    @Nullable private LookoutWatch lookout;
    private long nextLookoutCheckAt;
    private static final int LOOKOUT_CHECK_TICKS = 100;
    /** After a failed climb, how long before it looks for a tower again. */
    private static final int LOOKOUT_RETRY_TICKS = 1200;
    /** From a tower even a mob right at its foot is a fair shot. */
    private static final double LOOKOUT_MIN_SHOOT_RANGE = 2.0D;
    /** A mob within this much height of a lookout has climbed up to it. */
    private static final double LOOKOUT_MOB_UP_DY = 2.5D;
    /** A lookout shoots down from its tower's full height, and this much more (a mob in a dip). */
    private static final int LOOKOUT_EXTRA_DROP = 4;
    /** How long an answer to "can the lookout hit it from its platform" is kept. */
    private static final int LOOKOUT_REACH_RECHECK_TICKS = 20;
    /** How often a lookout looks for a mob of its own to shoot, when the one it was offered is out of reach. */
    private static final int LOOKOUT_OWN_PICK_TICKS = 10;
    private record Reach(boolean ok, long at) {}
    /** Mob id -> whether this lookout can hit it from somewhere on its platform. */
    private final Map<UUID, Reach> lookoutReach = new HashMap<>();
    private long nextLookoutOwnPickAt;
    /** A lookout goes down for its misses once at least this many lie near its tower. */
    private static final int LOOKOUT_ARROW_RUN_MIN = 3;
    /** How far from the tower, sideways, it goes for them. */
    private static final double LOOKOUT_ARROW_RUN_RADIUS = 16.0D;
    private static final int LOOKOUT_ARROW_RUN_CHECK_TICKS = 40;
    /** The whole trip down and the pickup, before it gives up and climbs back. */
    private static final int LOOKOUT_ARROW_RUN_MAX_TICKS = 900;
    @Nullable private MoveActivity arrowRunMove;
    private boolean arrowRunPicking;
    private int arrowRunTicks;
    private long nextArrowRunCheckAt;
    /** The practice session under way in quiet duty time, if any. */
    @Nullable private ArcheryPractice practice;
    private int patrolPause;

    @Nullable private LivingEntity target;
    @Nullable private UUID targetId;
    private int repathTimer;
    private int fireCooldown;
    /** Shots loosed this activity, for Volley (every fourth sends two). */
    private int shotsFired;
    /** Ticks left until full draw; 0 when the bow is not drawn. */
    private int drawTicks;
    /** How long the draw under way takes in all. */
    private int drawTotal;
    /** Ticks of this draw spent without a clean shot (see rideOutHiccup). */
    private int blockedDrawTicks;
    /** Half a second without a clean shot is ridden out; longer lets the draw down. */
    private static final int DRAW_HICCUP_TICKS = 10;
    /** A mob this close sends the archer running even mid-draw. */
    private static final double POINT_BLANK = 3.0D;
    /** Within its shortest range plus this, a drawing archer backs away at a quarter of its pace. */
    private static final double DRAW_STEP_BACK_MARGIN = 4.0D;
    private int combatCooldown;
    private int standoffTicks;
    private int chaseTicks;
    private boolean alone;
    private long aloneCheckedAt = Long.MIN_VALUE / 2;
    @Nullable private MoveActivity havenMove;
    @Nullable private BlockPos havenGoal;
    @Nullable private NpcEntity havenGuard;
    /** Starving, carrying food and nothing close: the brain may send it to eat. */
    private boolean mealBreak;

    @Override
    protected void start(Context ctx) {
        recovery = ArrowRecovery.of(ctx.model().npcId);
        ArcherBowUtils.equipBow(ctx.npc());
        GuardSettings.of(ctx.npc()).clearLegacyOrder();
        postPatrol.start(ctx);
    }

    @Override
    protected Status doTick(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (npc.getProfession() != Profession.ARCHER) return Status.DONE;

        ProfessionSettings archerSettings = ProfessionSettings.of(npc, ArcherSettingsSchema.SCHEMA);
        boolean workEnabled = archerSettings.getBool(ArcherSettingsSchema.WORK_ENABLED);

        ArcherBowUtils.equipBow(npc);
        GuardSettings settings = GuardSettings.of(npc);

        if (fireCooldown > 0) fireCooldown--;
        if (combatCooldown > 0) combatCooldown--;

        tickExperience(ctx);

        // Nothing to shoot with: only a mob right on top of it is any of its
        // business (it runs); anything further is left while it restocks.
        boolean armed = canFire(npc);
        if (!armed && target != null && npc.distanceToSqr(target) > UNARMED_FLEE_RANGE_SQ) {
            releaseTarget(ctx);
        }

        LivingEntity recentAttacker = recentDirectAttacker(ctx);
        LivingEntity helpFor = recentAttacker == null && armed ? helpTarget(ctx) : null;
        if (helpFor != null) {
            // "Defending You": a monster hurting a nearby player comes first.
            acceptTarget(ctx, helpFor);
        } else if (recentAttacker != null) {
            GuardAlertSystem.reportDirectDamageThreat(ctx, npc, recentAttacker);
            if (armed || npc.distanceToSqr(recentAttacker) <= UNARMED_FLEE_RANGE_SQ) {
                acceptTarget(ctx, recentAttacker);
            }
        } else if (armed && archerSettings.getBool(ArcherSettingsSchema.PROACTIVE_TARGETING)) {
            // A disabled PROACTIVE_TARGETING setting suppresses proactive
            // threat-seeking only -- the recentDirectAttacker branch above
            // (self-defense against a direct attacker) still runs unconditionally.
            GuardAlertSystem.GuardTarget alertTarget = GuardAlertSystem.findBestTargetForGuard(ctx);
            LivingEntity offered = alertTarget != null ? alertTarget.entity() : null;
            if (offered != null && isLookoutUp(npc) && !lookoutCanReach(ctx, offered)) {
                // Up its tower, a mob it cannot hit from the platform is left to
                // the others (the scan has reported it already). The lookout
                // keeps watch, and takes a mob it can hit instead if there is one.
                if (offered.getUUID().equals(targetId)) {
                    releaseTarget(ctx);
                }
                // Kept off it for 10 s, so the team plan sends someone on the
                // ground instead of offering it straight back.
                GuardAlertSystem.stepAside(ctx, npc.getNpcId(), offered.getUUID(), 200);
                offered = target == null ? lookoutOwnPick(ctx) : null;
            }
            // A mob given up on (no shot for a while) is taken again the moment
            // it can be hit; it used to be ignored for a full minute regardless.
            if (offered != null && (!isIgnored(ctx, offered) || ArcherCombatRules.canShoot(npc, offered,
                    0.0D, maxShootSq(ctx), maxShotDrop(npc)))) {
                acceptTarget(ctx, offered);
            } else if (isValidTarget(ctx, target) && !GuardAlertSystem.mayKeepTarget(ctx, target)) {
                // Its team has enough without it: back to patrol.
                releaseTarget(ctx);
            } else if (!isValidTarget(ctx, target) && !isLookoutUp(npc) && !GuardAlertSystem.usesTeams(ctx)
                    && (GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level()) || GuardAlertSystem.isOnAlert(npc))) {
                // Called out with no mob given to it (the bell rang for a fight
                // across the village, or a monster is hurting a player there):
                // it takes the nearest danger and runs to it. It used to go and
                // stand at its guard post until the fight came to it.
                LivingEntity danger = GuardAlertSystem.nearestDanger(ctx);
                if (danger != null && isValidTarget(ctx, danger)) acceptTarget(ctx, danger);
            }
        }

        tickHealing(ctx);
        mealBreak = isStarvingWithFood(ctx)
                && (target == null || npc.distanceToSqr(target) > MEAL_BREAK_SAFE_RANGE_SQ);

        // A lookout keeps its tower while it fights from it; anything that takes
        // it down (hurt, restocking, off duty) gives the tower up below.
        if (lookout != null) {
            WatchtowerDuty.renew(ctx.level(), lookout.tower(), npc.getNpcId());
            // Up there it sees farther and stands firm, in a fight as much as on watch.
            lookout.tickFooting(npc, ctx.level().getGameTime());
            // Big danger rings the tower bell, also in the middle of a fight.
            lookout.tickBell(ctx, isValidTarget(ctx, target));
        }

        // Hurt: run for the closest building and wait there (shared with guards).
        if (retreat.isActive() || (isValidTarget(ctx, target) && isLowOnHealth(ctx))) {
            cancelAcquire(ctx);
            cancelPatrol(ctx);
            cancelLookout(ctx);
            if (tickRetreat(ctx) == Status.RUNNING) return Status.RUNNING;
        }

        if (isValidTarget(ctx, target)) {
            cancelAcquire(ctx);
            cancelPatrol(ctx);
            return tickCombat(ctx);
        }

        if (targetId != null) {
            GuardAlertSystem.clearTarget(ctx, npc.getNpcId(), targetId);
            targetId = null;
        }
        // The mob died (or left) mid-draw: the drawn arrow goes to another mob
        // the archer can hit from here, rather than the bow being lowered and
        // drawn again from nothing.
        if (drawTicks > 0 && armed) {
            LivingEntity next = volleyTarget(ctx, target);
            if (next != null) {
                acceptTarget(ctx, next);
                cancelAcquire(ctx);
                cancelPatrol(ctx);
                return tickCombat(ctx);
            }
        }
        if (drawTicks > 0) letDown(ctx, "the mob is dead or gone");
        target = null;
        npc.setTarget(null);
        cancelFlee(ctx);
        cancelReposition(ctx);
        cancelFarApproach(ctx);
        cancelRunToGuard(ctx);

        // Fight over and the danger cleared (no alert, no hostile mob about, not
        // just the one it shot dead): pick up the arrows that missed, then what
        // the kills dropped (skeleton arrows, spider string, bones) and any
        // fallen villager's gear, and bank it (see FieldPickup).
        // (Not mid-practice: those arrows are picked up when the session ends.
        // A lookout goes down the tower for them only when several lie near it.)
        if (!isPractising() && lookout == null && combatCooldown <= 0) {
            if (ArcherBowUtils.setting(npc, ArcherSettingsSchema.PICK_UP) && pickup.dangerCleared(ctx)) {
                if (recovery.tick(ctx) == Status.RUNNING || pickup.tick(ctx) == Status.RUNNING) {
                    cancelPatrol(ctx);
                    return Status.RUNNING;
                }
            } else {
                recovery.cancel(npc);
                pickup.cancel(ctx);
            }
        }
        if (lookout != null && combatCooldown <= 0 && tickLookoutArrowRun(ctx) == Status.RUNNING) {
            cancelPatrol(ctx);
            return Status.RUNNING;
        }

        if (tickAcquire(ctx, workEnabled) == Status.RUNNING) {
            // Down for a bow or arrows: the tower is someone else's meanwhile.
            cancelLookout(ctx);
            return Status.RUNNING;
        }


        // Only the passive night-patrol schedule (and new bow/arrow
        // acquisition, gated above) is suppressed by WORK_ENABLED -- self
        // defense is handled unconditionally above this point.
        if (!workEnabled) return Status.DONE;

        if (combatCooldown > 0) return Status.RUNNING;

        // Same night-patrol-or-emergency schedule as GuardActivity, reusing
        // GuardSettings directly (it is plain per-villager NBT, not
        // guard-exclusive) rather than inventing a parallel archer setting.
        boolean scheduled = com.alex.test.village.villagers.guard.GuardShift.scheduled(ctx, settings);
        if (!scheduled && !GuardAlertSystem.hasEmergencyForGuard(ctx)) {
            return Status.DONE;
        }

        // A watchtower with no lookout comes first: it keeps watch all shift.
        boolean towerWatch = ArcherBowUtils.setting(npc, ArcherSettingsSchema.TOWER_WATCH);
        if (scheduled && towerWatch && tickLookout(ctx) == Status.RUNNING) {
            cancelPatrol(ctx);
            return Status.RUNNING;
        }
        if (!scheduled || !towerWatch) cancelLookout(ctx);

        // Stock tab orders for arrows or bows come before practice (see ArcherOrders).
        if (scheduled && tickOrders(ctx) == Status.RUNNING) {
            cancelPatrol(ctx);
            return Status.RUNNING;
        }

        // Quiet duty: every few minutes, a minute at the practice target.
        if (tickTargetPractice(ctx) == Status.RUNNING) {
            cancelPatrol(ctx);
            return Status.RUNNING;
        }

        // Guard posts (item-frame tokens) are shared with the guards: an archer
        // stands watch at one too, so a post on a wall or tower gets an archer.
        if (settings.useGuardPosts()) {
            Status status = postPatrol.tick(ctx, settings, combatCooldown);
            if (status == Status.RUNNING) return Status.RUNNING;
        } else {
            postPatrol.cancel(ctx);
        }
        return tickPatrol(ctx);
    }

    /** A usable bow and at least one arrow. */
    private static boolean canFire(NpcEntity npc) {
        return ArcherBowUtils.hasBow(npc.getInventory()) && ArcherBowUtils.hasArrow(npc.getInventory());
    }

    /**
     * Practice, paid to the defender skill like a guard's: time on duty, and
     * every arrow that hit or killed (counted by {@link ArcherEvents} when the
     * arrow lands). Archers used to earn nothing and never levelled.
     */
    private static void tickExperience(Context ctx) {
        NpcEntity npc = ctx.npc();
        int[] combat = ArcherEvents.takeCombat(npc.getNpcId());
        ctx.brain().onDefenderCombat(ctx, combat[0], combat[1]);
        if (Math.floorMod(ctx.level().getGameTime() + npc.getId(),
                GuardBalance.DUTY_XP_INTERVAL_TICKS) == 0) {
            ctx.brain().onDefenderDuty(ctx);
        }
    }

    private static boolean isStarvingWithFood(Context ctx) {
        VillagerStats needs = ctx.brain().needs();
        return needs.hungerRules().isCritical(needs) && EatActivity.findFoodSlot(ctx) >= 0;
    }

    // =========================================================================
    // Falling back
    // =========================================================================

    /**
     * Below the day's retreat health (12-20%, the guards' roll), or low enough
     * that the hardest hitter going for it could finish it in one blow (see
     * {@code GuardActivity.isLowOnHealth}).
     */
    private boolean isLowOnHealth(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (npc.getHealth() < npc.getMaxHealth()
                * GuardBalance.retreatHealth(ctx.model().npcId, ctx.level().getDayTime())) return true;
        long now = ctx.level().getGameTime();
        if (now < heaviestBlowAt || now - heaviestBlowAt >= BLOW_CHECK_TICKS) {
            heaviestBlowAt = now;
            heaviestBlow = GuardCombatRules.heaviestBlowAgainst(ctx.level(), npc);
        }
        return heaviestBlow > 0f && npc.getHealth() <= heaviestBlow;
    }

    /**
     * Runs the fall-back: RUNNING while on the way and while sheltering (shooting
     * whatever comes into sight from inside), DONE once the archer is fit again.
     */
    private Status tickRetreat(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (!retreat.isActive()) {
            cancelFlee(ctx);
            cancelReposition(ctx);
            cancelFarApproach(ctx);
            cancelRunToGuard(ctx);
            npc.getNavigation().stop();
            retreat.begin(ctx, target);
        }
        boolean fit = npc.getHealth() >= npc.getMaxHealth() * REGROUP_HEALTH_FRACTION;
        DefenderRetreat.Phase phase = retreat.tick(ctx, fit);
        if (phase == DefenderRetreat.Phase.RUNNING) return Status.RUNNING;
        if (phase == DefenderRetreat.Phase.HOLDING) {
            npc.getNavigation().stop();
            LivingEntity foe = target;
            if (!isValidTarget(ctx, foe)) {
                letDown(ctx, "the mob is dead or gone (sheltering)");
                return Status.RUNNING;
            }
            ArcherCombatRules.Blocker blocker = ArcherCombatRules.shotBlocker(npc, foe, 0.0D, maxShootSq(ctx),
                    ArcherCombatRules.MAX_SHOT_DROP);
            if (blocker == null) {
                blockedDrawTicks = 0;
                npc.setTarget(foe);
                tickShot(ctx, npc, foe);
            } else if (!blocker.passing() || !rideOutHiccup(npc, foe)) {
                letDown(ctx, blocker.why() + " (sheltering)");
            }
            return Status.RUNNING;
        }
        combatCooldown = COMBAT_END_COOLDOWN_TICKS;
        return Status.DONE;
    }

    /**
     * Out of a fight -- or sheltering from one -- an archer heals like a guard:
     * one health every {@link GuardBalance#regenTicks} ticks once nothing has hurt
     * it for {@link GuardBalance#REGEN_DELAY_TICKS}.
     */
    private void tickHealing(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (npc.hurtTime > 0) lastHurtTick = npc.tickCount;
        // Starving (hunger 0): no healing, or it would outlast the starving hits (10-19).
        if (ctx.brain().isStarving()) return;
        if (npc.getHealth() >= npc.getMaxHealth()
                || (isValidTarget(ctx, target) && !retreat.isActive())) return;
        if (npc.tickCount - lastHurtTick < GuardBalance.REGEN_DELAY_TICKS) return;
        if (Math.floorMod(npc.tickCount + npc.getId(),
                GuardBalance.regenTicks(ctx.brain().defenderSkillLevel())) == 0) {
            npc.heal(1.0F);
        }
    }

    // =========================================================================
    // Combat
    // =========================================================================

    private Status tickCombat(Context ctx) {
        NpcEntity npc = ctx.npc();
        LivingEntity currentTarget = target;
        if (!isValidTarget(ctx, currentTarget)) return Status.DONE;

        if (GuardThreatRules.isTooFarFromVillage(ctx, currentTarget.blockPosition())
                && npc.distanceToSqr(currentTarget) > GIVE_UP_TARGET_DIST_SQ) {
            releaseTarget(ctx);
            combatCooldown = COMBAT_END_COOLDOWN_TICKS;
            return Status.RUNNING;
        }

        // Fleeing is not a standoff: it is what the archer is meant to do
        // while a melee mob closes, so only non-flee ticks count below.
        double distSq = npc.distanceToSqr(currentTarget);
        boolean kiteFromMelee = ProfessionSettings.of(npc, ArcherSettingsSchema.SCHEMA)
                .getBool(ArcherSettingsSchema.KITE_FROM_MELEE);
        // With no guard around to catch the mob it keeps a wider gap.
        boolean solo = isAlone(ctx);
        double dangerSq = solo ? SOLO_DANGER_RANGE_SQ : MELEE_DANGER_RANGE_SQ;
        double minShoot = solo ? SOLO_MIN_SHOOT_RANGE : MIN_SHOOT_RANGE;
        // A lookout up its tower stays there: it shoots down from the tower's
        // whole height, even at a mob at the foot of it, and only runs from one
        // that has climbed up to it. A mob it cannot hit from the platform is
        // left to the others, unless that mob is the one hurting it: then the
        // lookout comes down and fights it like any archer.
        boolean lookoutUp = isLookoutUp(npc);
        boolean onTower = lookoutUp && lookoutCanReach(ctx, currentTarget);
        if (lookoutUp && !onTower && currentTarget != recentDirectAttacker(ctx)) {
            releaseTarget(ctx);
            return Status.RUNNING;
        }
        if (onTower) minShoot = LOOKOUT_MIN_SHOOT_RANGE;
        double maxDrop = onTower ? maxShotDrop(npc) : ArcherCombatRules.MAX_SHOT_DROP;
        boolean mobUpHere = !onTower || lookout.tower().isOnPlatform(currentTarget.blockPosition())
                || Math.abs(currentTarget.getY() - npc.getY()) < LOOKOUT_MOB_UP_DY;
        // A guard or captain between it and the mob holds the mob off: the
        // archer keeps its distance and shoots instead of running. It used to
        // run behind the guard, off again (still too close), and back,
        // stopping each time. A mob right on top of it is run from regardless.
        boolean covered = kiteFromMelee && distSq <= dangerSq && distSq > POINT_BLANK * POINT_BLANK
                && ArcherCombatRules.isCoveredByGuard(npc, currentTarget);
        // Unarmed it can only run (doTick keeps only a mob this close as target).
        boolean fleeing = mobUpHere && (!canFire(npc) || (kiteFromMelee && distSq <= dangerSq
                && !ArcherCombatRules.isOutOfReachAbove(npc, currentTarget) && !covered));
        // Far across the village it runs over first, watched by its own
        // give-up timer (see tickFarApproach); the standoff and chase timers
        // start once it is close.
        boolean farOff = !onTower && distSq > farApproachSq(ctx);
        if (!farOff) cancelFarApproach(ctx);
        if (!fleeing && !farOff) {
            if (repositionMove != null) chaseTicks++;
            else standoffTicks++;
        }
        if (standoffTicks > STANDOFF_GIVE_UP_TICKS || chaseTicks > CHASE_GIVE_UP_TICKS) {
            // Remembered with every other mob this archer has given up on: it
            // used to remember only the last one, so with two mobs out of its
            // shot it stood ten seconds on each in turn all night.
            GuardAlertSystem.giveUpUnreachable(ctx, npc.getNpcId(), currentTarget, STANDOFF_IGNORE_TICKS);
            releaseTarget(ctx);
            combatCooldown = COMBAT_END_COOLDOWN_TICKS;
            return Status.RUNNING;
        }

        npc.setTarget(currentTarget);

        // Past halfway into a draw, a mob that has closed in does not cost the
        // shot: loosing is quicker than turning to run. Only one right on top
        // of the archer sends it running at once (see finishesShot). Only
        // while it can back away as it draws, though: with a wall or a drop
        // behind it, it used to stand still and let the mob walk up to it.
        if (fleeing && finishesShot(npc, currentTarget) && canStepBack(ctx, npc, currentTarget)) {
            cancelReposition(ctx);
            npc.getNavigation().stop();
            tickShot(ctx, npc, currentTarget);
            stepBackWhileDrawing(ctx, npc, currentTarget);
            return Status.RUNNING;
        }

        // A closing hostile always wins over shooting from where we stand --
        // kiting away is the default point of the profession, but
        // KITE_FROM_MELEE lets a player opt an archer out of the guaranteed
        // retreat reflex (e.g. to have it stand its ground instead).
        if (fleeing) {
            letDown(ctx, canFire(npc) ? "the mob came too close, backing off" : "no bow or arrows");
            cancelReposition(ctx);
            // Run behind a guard if one is on the far side: it catches the mob.
            if (tickRunToGuard(ctx, currentTarget)) return Status.RUNNING;
            Vec3 threatNow = currentTarget.position();
            if (fleeActivity != null && fleeSnapshot != null
                    && threatNow.distanceToSqr(fleeSnapshot) > FLEE_RESNAP_DIST_SQ) {
                // Away from where the mob is now, without stopping: a new flee
                // stood the archer still for its route every time a chasing
                // mob took three steps.
                fleeActivity.retarget(currentTarget);
                fleeSnapshot = threatNow;
            }
            if (fleeActivity == null) {
                fleeActivity = FleeActivity.kite(currentTarget);
                fleeSnapshot = threatNow;
            }
            Status status = fleeActivity.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            fleeActivity.stop(ctx);
            fleeActivity = null;
            fleeSnapshot = null;
            return Status.RUNNING;
        }
        cancelFlee(ctx);
        cancelRunToGuard(ctx);

        ArcherCombatRules.Blocker blocker = ArcherCombatRules.shotBlocker(npc, currentTarget,
                minShoot * minShoot, maxShootSq(ctx), maxDrop);
        // The shortest range is where a draw starts, not where one well under
        // way is given up: a mob that walked a little closer still gets the arrow.
        if (blocker == null
                || blocker == ArcherCombatRules.Blocker.TOO_CLOSE && finishesShot(npc, currentTarget)) {
            blockedDrawTicks = 0;
            cancelReposition(ctx);
            npc.getNavigation().stop();
            tickShot(ctx, npc, currentTarget);
            stepBackWhileDrawing(ctx, npc, currentTarget);
            return Status.RUNNING;
        }
        // A short hiccup does not end the draw either (see rideOutHiccup).
        if (blocker.passing() && rideOutHiccup(npc, currentTarget)) return Status.RUNNING;
        // Lost the shot: the draw is let down.
        letDown(ctx, blocker.why());

        if (farOff) {
            cancelReposition(ctx);
            return tickFarApproach(ctx, currentTarget);
        }

        if (repositionMove != null && repositionTarget != null) {
            if (ArcherCombatRules.isPositionStillUseful(
                    ctx, repositionTarget, currentTarget, minShoot, maxShoot(ctx), maxDrop)) {
                Status status = repositionMove.tickOuter(ctx);
                if (status == Status.RUNNING) return Status.RUNNING;
            } else {
                // The mob moved off: a new spot almost at once. It used to stand
                // a full second first, every time, so a mob on the move kept a
                // following archer stopping and starting.
                repathTimer = Math.min(repathTimer, STALE_SPOT_REPATH_TICKS);
            }
            cancelReposition(ctx);
        }

        if (repositionMove == null && --repathTimer <= 0) {
            repathTimer = TARGET_REPATH_INTERVAL;
            // A lookout only steps across its own platform for a better angle.
            BlockPos spot = onTower
                    ? ArcherCombatRules.bestSpotAmong(ctx, lookout.usableSpots(ctx), currentTarget,
                            minShoot, maxShoot(ctx), maxDrop)
                    : ArcherCombatRules.bestRangedPosition(ctx, currentTarget, minShoot, maxShoot(ctx));
            if (spot != null) {
                repositionTarget = spot;
                repositionMove = new MoveActivity(spot, ARCHER_KITE_SPEED, true);
            }
        }
        if (repositionMove != null) {
            Status status = repositionMove.tickOuter(ctx);
            if (status != Status.RUNNING) cancelReposition(ctx);
        }
        return Status.RUNNING;
    }

    /** Further than this the archer runs to the mob first (see tickFarApproach). */
    private static double farApproachSq(Context ctx) {
        double range = maxShoot(ctx) + FAR_APPROACH_MARGIN;
        return range * range;
    }

    /** A guard's pace when answering danger, by the archer's own level. Also its pace fetching arrows. */
    static float runSpeed(Context ctx) {
        return (float) (VillagerModel.DEFAULT_SPEED
                * GuardBalance.responseSpeed(ctx.brain().defenderSkillLevel()));
    }

    /**
     * Runs to a mob far across the village at a guard's response pace: along
     * the graph to the walkable spot nearest it, planned again only once the
     * mob has moved well off. It used to pick an exact spot to shoot from
     * straight away; with the mob still walking about, that spot went stale
     * every few steps, and the archer stopped, stood a second and planned
     * again, so from 80 blocks off it hardly moved and soon gave the mob up.
     * {@value #FAR_APPROACH_STALL_TICKS} ticks without getting a block closer
     * still gives the mob up as out of reach.
     */
    private Status tickFarApproach(Context ctx, LivingEntity foe) {
        NpcEntity npc = ctx.npc();
        double dist = npc.distanceTo(foe);
        npc.hurry(HurryGait.RUN);
        hurrying = true;
        if (!foe.getUUID().equals(approachLoggedFor)) {
            approachLoggedFor = foe.getUUID();
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + String.format(
                    " ARCHER running to %s, %.0f blocks away", foe.getName().getString(), dist));
        }
        if (dist < approachBest - 1.0D) {
            approachBest = dist;
            approachStallTicks = 0;
        } else if (++approachStallTicks > FAR_APPROACH_STALL_TICKS) {
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + String.format(
                    " ARCHER can't get closer to %s (%.0f blocks), leaving it for now",
                    foe.getName().getString(), dist));
            GuardAlertSystem.giveUpUnreachable(ctx, npc.getNpcId(), foe, STANDOFF_IGNORE_TICKS);
            releaseTarget(ctx);
            combatCooldown = COMBAT_END_COOLDOWN_TICKS;
            return Status.RUNNING;
        }

        BlockPos at = foe.blockPosition();
        boolean handedOver = false;
        if (approachMove != null) {
            boolean mobMoved = approachMobAt == null || approachMobAt.distSqr(at) > FAR_APPROACH_REPLAN_SQ;
            if (!mobMoved) {
                Status status = approachMove.tickOuter(ctx);
                if (status == Status.RUNNING) return Status.RUNNING;
                // Ended or failed short of the mob: a short wait, then a new run.
                approachRetryTicks = FAR_APPROACH_RETRY_TICKS;
            }
            // The mob moved on mid-run: the old route runs on until the new one
            // takes over, rather than braking the archer and starting the new
            // one on a tile it had already crossed (WalkFeel.chaseHandover).
            handedOver = WalkFeel.chaseHandover && mobMoved && ctx.model().isMoving();
            if (!handedOver) approachMove.stop(ctx);
            approachMove = null;
        }
        if (approachRetryTicks > 0) {
            if (handedOver) ctx.model().stopMoving();
            approachRetryTicks--;
            return Status.RUNNING;
        }
        NavGraph graph = ctx.mgr().getGraph();
        NavNode node = graph != null ? graph.nearest(at, 10, 4) : null;
        if (node == null) {
            if (handedOver) ctx.model().stopMoving();
            approachRetryTicks = FAR_APPROACH_RETRY_TICKS;
            return Status.RUNNING;
        }
        approachMobAt = at;
        approachMove = new MoveActivity(node.feetPos(), runSpeed(ctx));
        if (approachMove.tickOuter(ctx) != Status.RUNNING) {
            approachMove.stop(ctx);
            approachMove = null;
            approachRetryTicks = FAR_APPROACH_RETRY_TICKS;
        }
        return Status.RUNNING;
    }

    private void cancelFarApproach(Context ctx) {
        stopHurrying(ctx);
        if (approachMove != null) approachMove.stop(ctx);
        approachMove = null;
        approachMobAt = null;
        approachBest = Double.MAX_VALUE;
        approachStallTicks = 0;
        approachRetryTicks = 0;
    }

    /** No guard or captain within {@link #SOLO_CHECK_RADIUS}; read once a second. */
    private boolean isAlone(Context ctx) {
        long now = ctx.level().getGameTime();
        if (now - aloneCheckedAt >= 20 || now < aloneCheckedAt) {
            aloneCheckedAt = now;
            NpcEntity npc = ctx.npc();
            alone = ArcherCombatRules.meleeDefendersNear(npc, npc.position(), SOLO_CHECK_RADIUS).isEmpty();
        }
        return alone;
    }

    /**
     * Runs to the nearest guard on the far side from {@code foe} and stands
     * behind it. False when there is none, or the archer is already beside it
     * (then it simply runs on, away from the mob).
     */
    private boolean tickRunToGuard(Context ctx, LivingEntity foe) {
        NpcEntity npc = ctx.npc();
        NpcEntity guard = havenGuard != null && havenGuard.isAlive() ? havenGuard : findHavenGuard(npc, foe);
        if (guard == null) {
            cancelRunToGuard(ctx);
            return false;
        }
        // Behind the guard, seen from the mob.
        Vec3 away = guard.position().subtract(foe.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1.0E-4) {
            cancelRunToGuard(ctx);
            return false;
        }
        Vec3 spot = guard.position().add(away.normalize().scale(HAVEN_BEHIND_DIST));
        if (npc.position().distanceToSqr(spot) <= HAVEN_ARRIVED_SQ) {
            cancelRunToGuard(ctx);
            return false;
        }
        havenGuard = guard;
        BlockPos goal = BlockPos.containing(spot);
        if (havenMove == null || havenGoal == null || havenGoal.distSqr(goal) > HAVEN_REPLAN_SQ) {
            // The walk under way (a flee, or the last run to this guard) is not
            // stopped: it goes on until the new route is ready, rather than the
            // archer standing still with a mob on its heels while it is worked out.
            fleeActivity = null;
            fleeSnapshot = null;
            havenGoal = goal;
            havenMove = new MoveActivity(goal, ARCHER_KITE_SPEED, true);
        }
        Status status = havenMove.tickOuter(ctx);
        if (status == Status.RUNNING) {
            // A mob close behind: running to the guard's back.
            npc.hurry(HurryGait.RUN);
            hurrying = true;
            return true;
        }
        cancelRunToGuard(ctx);
        return false;
    }

    /** Back to walking, if this activity had the archer running. */
    private void stopHurrying(Context ctx) {
        if (!hurrying) return;
        hurrying = false;
        ctx.npc().stopHurry();
    }

    @Nullable
    private static NpcEntity findHavenGuard(NpcEntity npc, LivingEntity foe) {
        Vec3 toFoe = foe.position().subtract(npc.position());
        NpcEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (NpcEntity guard : ArcherCombatRules.meleeDefendersNear(npc, npc.position(), HAVEN_RADIUS)) {
            Vec3 toGuard = guard.position().subtract(npc.position());
            // Only one the archer runs away from the mob to reach.
            if (toGuard.dot(toFoe) >= 0) continue;
            double d = toGuard.lengthSqr();
            if (d < bestSq) {
                bestSq = d;
                best = guard;
            }
        }
        return best;
    }

    private void cancelRunToGuard(Context ctx) {
        stopHurrying(ctx);
        if (havenMove != null) havenMove.stop(ctx);
        havenMove = null;
        havenGoal = null;
        havenGuard = null;
    }

    /**
     * Looses one arrow at {@code target}. {@code free} is a Volley's second
     * arrow: it costs nothing from the quiver and is not picked up again.
     */
    private void fireArrow(Context ctx, NpcEntity npc, LivingEntity target, boolean free) {
        int arrowSlot = ArcherBowUtils.pickArrowSlot(npc.getInventory(), target);
        if (arrowSlot < 0) return;
        loose(ctx, npc, arrowSlot, new Vec3(target.getX(), target.getY(0.5D), target.getZ()),
                target.getDeltaMovement(), free);
    }

    /** Looses the arrow in {@code arrowSlot} at {@code aimPoint}, which moves by {@code walk} a tick. */
    private void loose(Context ctx, NpcEntity npc, int arrowSlot, Vec3 aimPoint, Vec3 walk, boolean free) {
        int level = ctx.brain().defenderSkillLevel();

        ItemStack arrowStack = npc.getInventory().getSlot(arrowSlot).copyWithCount(1);
        // Vanilla refuses an arrow "fired from" an empty stack ("Invalid weapon firing
        // an arrow"), which threw out of the whole village tick every time an archer
        // shot with its hand briefly empty (eating, a bow swap). Fall back to the bow
        // in the pack, and to no weapon at all rather than crash.
        ItemStack bow = npc.getMainHandItem();
        if (bow.isEmpty()) {
            int bowSlot = ArcherBowUtils.findBowSlot(npc.getInventory());
            if (bowSlot >= 0) bow = npc.getInventory().getSlot(bowSlot);
        }
        // Faster and flatter with skill, up to a player's full draw; each hit as hard as before.
        float speed = ArcherBalance.arrowSpeed(level);
        AbstractArrow arrow = ArcherBowUtils.fullDrawArrow(npc, arrowStack, bow, speed);
        // A captain's rallying cry puts weight behind the archers' arrows too.
        arrow.setBaseDamage(arrow.getBaseDamage() * com.alex.test.village.villagers.guard.GuardSkillEffects
                .rallyDamageMultiplier(npc, ctx.level().getGameTime()));
        int pierce = ArcherBalance.pierceLevel(level);
        if (pierce > arrow.getPierceLevel()) setPierce(arrow, pierce);

        // Leads a walking mob and lifts for the arrow's drop (see ArcherAim).
        Vec3 aim = ArcherAim.direction(arrow.position(), aimPoint, walk, speed);
        arrow.shoot(aim.x, aim.y, aim.z, speed, ArcherBalance.inaccuracy(level));

        // Infinity: a plain arrow is not used up, and cannot be picked up again (as for a player).
        boolean infinite = !free && ArcherBowUtils.isInfiniteShot(ctx.level(), bow, arrowStack);
        if (infinite) arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        ctx.level().playSound(null, npc.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        ctx.level().addFreshEntity(arrow);
        com.alex.test.village.farawake.FarExtras.trackShot(arrow, npc); // flies on past the simulation distance
        if (free) return;
        if (!infinite) {
            recovery.remember(arrow, arrowStack, ctx.level().getGameTime(), false);
            npc.getInventory().reduceSlot(arrowSlot, 1);
        }
        // Every shot wears the bow, as in a player's hands (Volley's free arrow does not).
        BowWear.afterShot(ctx);
        npc.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Piercing Shot. Vanilla only sets an arrow's pierce from the bow's
     * enchantment and keeps the setter private, so it goes in through the
     * arrow's own saved data, which it reads back whole.
     */
    private static void setPierce(AbstractArrow arrow, int pierce) {
        net.minecraft.nbt.CompoundTag tag = arrow.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        tag.putByte("PierceLevel", (byte) pierce);
        arrow.load(tag);
    }

    /**
     * Pace for the AIM clip so its release frame lands on the shot: the draw and
     * hold before it (and the blend in) are squeezed or stretched into {@code drawTicks}.
     * The follow-through after the release plays out at the same pace.
     */
    static float drawAnimationSpeed(int drawTicks) {
        return (VillagerAnimationState.AIM_RELEASE_TICKS + VillagerAnimationState.WORK_BLEND_TICKS)
                / (float) drawTicks;
    }

    /**
     * The draw is let down: no arrow, and the bow pose ends now rather than when
     * it times out. A draw under way is noted in the diary with {@code why}, so a
     * test shows what keeps the archer from shooting.
     */
    private void letDown(Context ctx, String why) {
        NpcEntity npc = ctx.npc();
        if (drawTicks > 0) {
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " ARCHER let the draw down ("
                    + (drawTotal - drawTicks) + "/" + drawTotal + " ticks drawn): " + why);
        }
        drawTicks = 0;
        blockedDrawTicks = 0;
        if (npc.getAnimation() == VillagerAnimationState.AIM) npc.setAnimation(VillagerAnimationState.IDLE);
    }

    /**
     * A draw under way rides out a short hiccup (someone steps past, the mob
     * ducks behind a post) for up to {@value #DRAW_HICCUP_TICKS} ticks: it keeps
     * drawing, and looses on time if the shot is clear again by then. It used
     * to be let down at the first blocked tick and started again from nothing.
     * The release is never held back: the clip lets go on that tick, so a shot
     * still blocked then is let down.
     */
    private boolean rideOutHiccup(NpcEntity npc, LivingEntity foe) {
        if (drawTicks <= 1 || ++blockedDrawTicks > DRAW_HICCUP_TICKS) return false;
        npc.holdStillForAnimation();
        GuardCombatRules.face(npc, foe.getEyePosition());
        drawTicks--;
        return true;
    }

    /** A monster that hurt a player within {@value #HELP_RADIUS} blocks lately; null if none or the setting is off. */
    @Nullable
    private LivingEntity helpTarget(Context ctx) {
        if (!ArcherBowUtils.setting(ctx.npc(), ArcherSettingsSchema.HELP_ME)) return null;
        for (ServerPlayer player : ctx.level().players()) {
            if (player.isSpectator() || player.distanceToSqr(ctx.npc()) > HELP_RADIUS * HELP_RADIUS) continue;
            LivingEntity attacker = player.getLastHurtByMob();
            if (attacker == null
                    || player.tickCount - player.getLastHurtByMobTimestamp() > RECENT_ATTACKER_MEMORY_TICKS) continue;
            if (isValidTarget(ctx, attacker)) return attacker;
        }
        return null;
    }

    private static final double HELP_RADIUS = 24.0D;

    /**
     * While the bow is drawn, a mob coming on is backed away from at a quarter
     * of walking speed (vanilla's strafe, the way a skeleton does it), still
     * facing it and drawing. Further off, up a tower, above a mob that cannot
     * climb, or with "Back away from close mobs" off, the archer stands to shoot.
     * Never backwards into a wall or off more than a one-block drop (see canStepBack).
     */
    private void stepBackWhileDrawing(Context ctx, NpcEntity npc, LivingEntity foe) {
        if (drawTicks <= 0 || isLookoutUp(npc) || ArcherCombatRules.isOutOfReachAbove(npc, foe)) return;
        if (!ProfessionSettings.of(npc, ArcherSettingsSchema.SCHEMA).getBool(ArcherSettingsSchema.KITE_FROM_MELEE)) {
            return;
        }
        double backOff = (isAlone(ctx) ? SOLO_MIN_SHOOT_RANGE : MIN_SHOOT_RANGE) + DRAW_STEP_BACK_MARGIN;
        if (npc.distanceToSqr(foe) > backOff * backOff) return;
        if (!canStepBack(ctx, npc, foe)) return;
        npc.getMoveControl().strafe(-1.0F, 0.0F);
    }

    /**
     * The block behind the archer, seen from {@code foe}, can be backed into:
     * room for its head, no water or lava, and level floor, a step up it walks
     * onto anyway (a slab, a stair) or a drop of one block. It used to want
     * plain level floor, so on stairs or a slope it stood still to draw while
     * the mob walked up to it.
     */
    private static boolean canStepBack(Context ctx, NpcEntity npc, LivingEntity foe) {
        Vec3 away = npc.position().subtract(foe.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1.0E-4) return false;
        away = away.normalize();
        var level = ctx.level();
        BlockPos behind = BlockPos.containing(npc.getX() + away.x, npc.getY() + 0.1D, npc.getZ() + away.z);
        if (!level.getFluidState(behind).isEmpty()) return false;
        double top = collisionTop(level, behind);
        // A wall, or a step up higher than it can walk onto.
        if (top > npc.getY() + npc.maxUpStep()) return false;
        if (collisionTop(level, behind.above()) != NO_COLLISION) return false;
        // Stepping up lifts its head into the block above that.
        if (top > npc.getY() && collisionTop(level, behind.above(2)) != NO_COLLISION) return false;
        if (top != NO_COLLISION) return true;
        // Nothing in the block itself: floor under it, or one block lower.
        return collisionTop(level, behind.below()) != NO_COLLISION
                || collisionTop(level, behind.below(2)) != NO_COLLISION;
    }

    private static final double NO_COLLISION = Double.NEGATIVE_INFINITY;

    /** The top of {@code pos}'s collision box, or {@link #NO_COLLISION} when it has none. */
    private static double collisionTop(net.minecraft.world.level.Level level, BlockPos pos) {
        var shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return shape.isEmpty() ? NO_COLLISION : pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
    }

    /**
     * Whether a draw is far enough along to be finished against a mob that has
     * come close: past halfway, a clear line, and the mob not yet within
     * {@value #POINT_BLANK} blocks. Loosing then is quicker than turning to run.
     */
    private boolean finishesShot(NpcEntity npc, LivingEntity foe) {
        return drawTicks > 0 && drawTicks * 2 <= drawTotal && canFire(npc)
                && npc.distanceToSqr(foe) > POINT_BLANK * POINT_BLANK
                && ArcherCombatRules.hasSight(npc, foe)
                && !ArcherCombatRules.isFriendInLine(npc.getEyePosition(), foe,
                        ArcherCombatRules.friendsNear(npc, foe));
    }

    /**
     * Each tick the archer has a clean shot: draws the bow (the AIM clip, paced
     * to the draw), looses at full draw, then rests before the next draw. Draw
     * and rest get shorter with skill; at Volley every fourth shot sends a
     * second arrow, at another mob in range if there is one.
     */
    private void tickShot(Context ctx, NpcEntity npc, LivingEntity foe) {
        npc.holdStillForAnimation();
        GuardCombatRules.face(npc, foe.getEyePosition());
        int level = ctx.brain().defenderSkillLevel();
        if (drawTicks > 0) {
            if (--drawTicks > 0) return;
            fireArrow(ctx, npc, foe, false);
            if (ArcherBalance.hasVolley(level) && ++shotsFired % ArcherBalance.VOLLEY_EVERY_SHOTS == 0) {
                LivingEntity second = volleyTarget(ctx, foe);
                fireArrow(ctx, npc, second != null ? second : foe, true);
            }
            fireCooldown = ArcherBalance.recoveryTicks(level);
            standoffTicks = 0;
            chaseTicks = 0;
            return;
        }
        if (fireCooldown <= 0 && ArcherBowUtils.hasArrow(npc.getInventory())) {
            drawTicks = ArcherBalance.drawTicks(level);
            drawTotal = drawTicks;
            blockedDrawTicks = 0;
            npc.startWorkAnimation(VillagerAnimationState.AIM, drawAnimationSpeed(drawTicks));
        }
    }

    /** Another hostile this archer may fight and has a clean shot at, nearest first. */
    @Nullable
    private LivingEntity volleyTarget(Context ctx, LivingEntity besides) {
        NpcEntity npc = ctx.npc();
        double range = maxShoot(ctx);
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (LivingEntity other : ctx.level().getEntitiesOfClass(LivingEntity.class,
                npc.getBoundingBox().inflate(range), e -> e != besides && e.isAlive())) {
            if (!isValidTarget(ctx, other)
                    || !ArcherCombatRules.canShoot(npc, other, 0.0D, range * range, maxShotDrop(npc))) continue;
            double d = npc.distanceToSqr(other);
            if (d < bestSq) {
                bestSq = d;
                best = other;
            }
        }
        return best;
    }

    /** How far this archer shoots: 14 blocks, 18 with Eagle Eye. */
    private static double maxShoot(Context ctx) {
        return ArcherBalance.maxShootRange(ctx.brain().defenderSkillLevel());
    }

    private static double maxShootSq(Context ctx) {
        double range = maxShoot(ctx);
        return range * range;
    }

    private void releaseTarget(Context ctx) {
        if (targetId != null) GuardAlertSystem.clearTarget(ctx, ctx.npc().getNpcId(), targetId);
        target = null;
        targetId = null;
        letDown(ctx, "gave the mob up");
        ctx.npc().getNavigation().stop();
        ctx.npc().setTarget(null);
        cancelFlee(ctx);
        cancelReposition(ctx);
        cancelFarApproach(ctx);
        cancelRunToGuard(ctx);
    }

    private void acceptTarget(Context ctx, LivingEntity entity) {
        UUID previous = targetId;
        target = entity;
        targetId = entity.getUUID();
        // However the mob was chosen, it counts against the defenders on it.
        GuardAlertSystem.claim(ctx, ctx.npc(), entity);
        if (!targetId.equals(previous)) {
            cancelReposition(ctx);
            cancelFarApproach(ctx);
            repathTimer = 0;
            // A draw under way is kept: the archer turns its drawn bow on the
            // new mob and looses on time if it has a shot (tickCombat lets the
            // draw down if not). It used to lower the bow and draw from nothing.
            blockedDrawTicks = 0;
            standoffTicks = 0;
            chaseTicks = 0;
        }
    }

    /**
     * A target this archer recently gave up on. Only proactive targeting
     * checks this -- a mob that is actually hitting the archer is always
     * fought back.
     */
    private boolean isIgnored(Context ctx, LivingEntity entity) {
        return GuardAlertSystem.isGivenUp(ctx.npc(), entity, ctx.level().getGameTime());
    }

    @Nullable
    private static LivingEntity recentDirectAttacker(Context ctx) {
        LivingEntity attacker = ctx.npc().getLastHurtByMob();
        if (attacker == null || !attacker.isAlive()) return null;
        if (ctx.npc().tickCount - ctx.npc().getLastHurtByMobTimestamp() > RECENT_ATTACKER_MEMORY_TICKS) {
            return null;
        }
        return GuardThreatRules.isHostileMonsterForGuard(ctx.npc(), attacker) ? attacker : null;
    }

    private static boolean isValidTarget(Context ctx, @Nullable LivingEntity entity) {
        if (!GuardThreatRules.isHostileMonsterForGuard(ctx.npc(), entity)) return false;
        // Layered on top of Guard's shared hostile-mob gate above: an
        // Archer-only allow-list (ArcherSettingsSchema.ALLOWED_MOB_TARGETS,
        // reusing GuardMobPolicy's own mob list) lets this archer's own
        // settings screen narrow which of those mobs it actually engages.
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), ArcherSettingsSchema.SCHEMA);
        return settings.isAllowed(
                ArcherSettingsSchema.ALLOWED_MOB_TARGETS, EntityType.getKey(entity.getType()).toString());
    }

    // =========================================================================
    // Bow/arrow acquisition
    // =========================================================================

    private Status tickAcquire(Context ctx, boolean workEnabled) {
        if (getBowActivity != null) {
            Status status = getBowActivity.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            getBowActivity.stop(ctx);
            getBowActivity = null;
            if (status == Status.FAILED) {
                nextBowAttemptAt = ctx.level().getGameTime() + RETRY_COOLDOWN_TICKS;
            } else {
                ArcherBowUtils.equipBow(ctx.npc());
            }
            return Status.DONE;
        }
        if (getArrowsActivity != null) {
            Status status = getArrowsActivity.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            getArrowsActivity.stop(ctx);
            getArrowsActivity = null;
            if (status == Status.FAILED) {
                nextArrowAttemptAt = ctx.level().getGameTime() + RETRY_COOLDOWN_TICKS;
            }
            return Status.DONE;
        }
        if (fletchActivity != null) {
            Status status = fletchActivity.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            fletchActivity.stop(ctx);
            fletchActivity = null;
            if (status == Status.FAILED) {
                // Arrows or a bow: neither trip is tried again at once.
                nextArrowAttemptAt = ctx.level().getGameTime() + RETRY_COOLDOWN_TICKS;
                nextBowAttemptAt = ctx.level().getGameTime() + RETRY_COOLDOWN_TICKS;
            } else {
                ArcherBowUtils.equipBow(ctx.npc());
            }
            return Status.DONE;
        }

        // WORK_ENABLED only suppresses starting a *new* gearing-up trip --
        // an in-flight one (handled above) always runs to completion.
        if (!workEnabled) return Status.DONE;

        // No bow (it broke: bows wear out, see BowWear): the best one in
        // storage, else one made from string and sticks, else the players are told.
        if (!ArcherBowUtils.hasBow(ctx.npc().getInventory())
                && ctx.level().getGameTime() >= nextBowAttemptAt) {
            cancelPatrol(ctx);
            if (GetToolActivity.canAcquire(ctx, GetBowActivity.BOW_REQUIREMENT)) {
                getBowActivity = new GetBowActivity();
                return Status.RUNNING;
            }
            if (FletchArrowsActivity.canMakeBow(ctx)) {
                fletchActivity = FletchArrowsActivity.bowForSelf();
                return Status.RUNNING;
            }
            nextBowAttemptAt = ctx.level().getGameTime() + RETRY_COOLDOWN_TICKS;
            BowWear.warnNoBow(ctx);
        }
        long now = ctx.level().getGameTime();
        if (ArcherBowUtils.hasBow(ctx.npc().getInventory()) && now >= nextBowCheckAt) {
            nextBowCheckAt = now + BOW_CHECK_TICKS;
            // A better (more enchanted) bow in storage: swapped in, the old one put back.
            long storageChange = ToolUpgradeHelper.newestToolStorageChangeId(ctx);
            if (storageChange > lastSeenBowStorageChange) {
                lastSeenBowStorageChange = storageChange;
                StorageBuilding better = ToolUpgradeHelper.nearestStorageWithBetterTool(
                        ctx, GetBowActivity.BOW_REQUIREMENT);
                if (better != null) {
                    cancelPatrol(ctx);
                    getBowActivity = GetBowActivity.forUpgrade(better);
                    return Status.RUNNING;
                }
            }
            // A worn bow and none waiting in storage: a spare is made for storage
            // (one archer at a time), so a bow is there when this one breaks.
            if (BowWear.bowWorn(ctx.npc()) && !BowWear.spareInStorage(ctx)
                    && FletchArrowsActivity.canMakeBow(ctx) && BowWear.claimSpare(ctx)) {
                cancelPatrol(ctx);
                fletchActivity = FletchArrowsActivity.spareBow();
                return Status.RUNNING;
            }
        }
        // Not after every shot: a trip once the quiver is half spent, for a full one.
        if (ArcherBowUtils.countArrows(ctx.npc().getInventory()) < ArcherBowUtils.refillLine(ctx.npc())
                && ctx.level().getGameTime() >= nextArrowAttemptAt) {
            // Arrows from storage if it has any, else made from flint, sticks and feathers.
            if (GetToolActivity.canAcquire(ctx, GetArrowsActivity.ARROW_REQUIREMENT)) {
                cancelPatrol(ctx);
                getArrowsActivity = new GetArrowsActivity();
                return Status.RUNNING;
            }
            if (mayFletch(ctx) && FletchArrowsActivity.canRun(ctx)) {
                cancelPatrol(ctx);
                fletchActivity = new FletchArrowsActivity();
                return Status.RUNNING;
            }
            nextArrowAttemptAt = ctx.level().getGameTime() + RETRY_COOLDOWN_TICKS;
        }
        return Status.DONE;
    }

    /** Plain arrows are what fletching makes; not when the settings forbid them. */
    private static boolean mayFletch(Context ctx) {
        return ProfessionSettings.of(ctx.npc(), ArcherSettingsSchema.SCHEMA)
                .isAllowed(ArcherSettingsSchema.ALLOWED_ARROW_TYPES, "minecraft:arrow");
    }

    private void cancelAcquire(Context ctx) {
        if (getBowActivity != null) {
            getBowActivity.stop(ctx);
            getBowActivity = null;
        }
        if (getArrowsActivity != null) {
            getArrowsActivity.stop(ctx);
            getArrowsActivity = null;
        }
        if (fletchActivity != null) {
            fletchActivity.stop(ctx);
            fletchActivity = null;
        }
        recovery.cancel(ctx.npc());
        pickup.cancel(ctx);
        cancelArrowRun(ctx);
        cancelTargetPractice(ctx);
        cancelOrder(ctx);
    }

    // =========================================================================
    // Stock tab orders
    // =========================================================================

    /**
     * Quiet duty time: arrows or a bow the storage room's Stock tab asks for,
     * made and put into storage. Looked for every ten seconds; like practice,
     * only when every guard post is watched by someone else.
     */
    private Status tickOrders(Context ctx) {
        long now = ctx.level().getGameTime();
        if (orderWork != null) {
            Status status = orderWork.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            orderWork.stop(ctx);
            orderWork = null;
            if (status == Status.FAILED) nextOrderCheckAt = now + RETRY_COOLDOWN_TICKS;
            return Status.DONE;
        }
        if (isPractising() || now < nextOrderCheckAt) return Status.DONE;
        nextOrderCheckAt = now + ORDER_CHECK_TICKS;
        if (GuardSettings.of(ctx.npc()).useGuardPosts()
                && GuardPostRegistry.get(ctx.level())
                        .hasPostNeedingWatch(ctx.level(), ctx.mgr(), ctx.npc().getNpcId())) {
            return Status.DONE;
        }
        orderWork = ArcherOrders.next(ctx);
        return orderWork != null ? Status.RUNNING : Status.DONE;
    }

    private void cancelOrder(Context ctx) {
        if (orderWork == null) return;
        orderWork.stop(ctx);
        orderWork = null;
    }

    // =========================================================================
    // Target practice
    // =========================================================================

    /**
     * Quiet duty time, by day: every few minutes a practice session at the
     * village's Archery Range, or at the targets around a barracks when there
     * is no range (see {@link ArcheryPractice}). Never on alert, never at
     * night, never short of arrows.
     *
     * <p>A guard post comes first: an archer goes only when every post in the
     * village is watched by someone else. It used to leave its post on the
     * wall every few minutes, however far away the range was.
     */
    private Status tickTargetPractice(Context ctx) {
        if (practice == null) {
            if (!ArcheryPractice.onDutySessionDue(ctx) || !ArcheryPractice.canPractiseNow(ctx)) return Status.DONE;
            ArcheryPractice.delayOnDutySession(ctx,
                    PRACTICE_GAP_TICKS + ctx.npc().getRandom().nextInt(PRACTICE_GAP_JITTER_TICKS));
            if (GuardSettings.of(ctx.npc()).useGuardPosts()
                    && GuardPostRegistry.get(ctx.level())
                            .hasPostNeedingWatch(ctx.level(), ctx.mgr(), ctx.npc().getNpcId())) {
                return Status.DONE;
            }
            practice = ArcheryPractice.begin(ctx, recovery, false);
            if (practice == null) return Status.DONE;
        }
        Status status = practice.tick(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        cancelTargetPractice(ctx);
        return Status.DONE;
    }

    private boolean isPractising() {
        return practice != null;
    }

    /**
     * Ends the session, finished or cut short (an alarm, a lost path): the
     * time already spent at the target is paid either way.
     */
    private void cancelTargetPractice(Context ctx) {
        if (practice == null) return;
        practice.stop(ctx);
        practice = null;
    }

    // =========================================================================
    // Patrol / idle
    // =========================================================================

    private Status tickPatrol(Context ctx) {
        if (patrolPause > 0) {
            patrolPause--;
            return Status.RUNNING;
        }
        if (patrolMove == null) {
            BlockPos point = GuardPatrolPlanner.villagePatrolPoint(ctx);
            if (point == null) return Status.FAILED;
            patrolMove = new MoveActivity(point, VillagerModel.WALK_SPEED);
        }
        Status status = patrolMove.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        patrolMove.stop(ctx);
        patrolMove = null;
        patrolPause = 30 + ctx.npc().getRandom().nextInt(60);
        return Status.RUNNING;
    }

    private void cancelPatrol(Context ctx) {
        postPatrol.cancel(ctx);
        if (patrolMove == null) return;
        patrolMove.stop(ctx);
        patrolMove = null;
    }

    private void cancelFlee(Context ctx) {
        fleeSnapshot = null;
        if (fleeActivity == null) return;
        fleeActivity.stop(ctx);
        fleeActivity = null;
    }

    private void cancelReposition(Context ctx) {
        if (repositionMove != null) repositionMove.stop(ctx);
        repositionMove = null;
        repositionTarget = null;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public boolean isMovingPhase() {
        return target == null;
    }

    /** Fighting right now: a mob to shoot or run from, or running for shelter. */
    public boolean isThreatResponseActive() {
        return target != null && target.isAlive() || retreat.isActive() || fleeActivity != null;
    }

    @Override
    public boolean allowsRoutineInterruption() {
        // A held target locks out the brain's food overrides; a starving archer
        // with food and no mob close may still stop to eat (it used to starve).
        return !retreat.isRunning() && (target == null || mealBreak);
    }

    // =========================================================================
    // Watchtower lookout
    // =========================================================================

    /**
     * On duty, a free Watchtower is manned first: the nearest one nobody keeps
     * watch on (a night-shift archer also comes to relieve a day lookout).
     * Looked for every few seconds, not every tick.
     */
    private Status tickLookout(Context ctx) {
        long now = ctx.level().getGameTime();
        if (lookout == null) {
            if (now < nextLookoutCheckAt && now + LOOKOUT_RETRY_TICKS >= nextLookoutCheckAt) return Status.DONE;
            nextLookoutCheckAt = now + LOOKOUT_CHECK_TICKS + ctx.npc().getRandom().nextInt(LOOKOUT_CHECK_TICKS);
            WatchtowerBuilding tower = WatchtowerDuty.claimNearest(ctx);
            if (tower == null) return Status.DONE;
            lookout = new LookoutWatch(tower);
            cancelTargetPractice(ctx);
            ctx.brain().diary().log(com.alex.test.village.brain.VillagerDiary.ts(ctx.level())
                    + " LOOKOUT up the watchtower at " + tower.getItemFramePos().toShortString());
        }
        Status status = lookout.tick(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        // No way up: not tried again for a while, by this archer or any other.
        if (status == Status.FAILED) {
            nextLookoutCheckAt = now + LOOKOUT_RETRY_TICKS;
            WatchtowerDuty.markUnreachable(ctx.level(), lookout.tower());
        }
        cancelLookout(ctx);
        return Status.DONE;
    }

    /**
     * After a fight, a lookout goes down for the arrows it missed once
     * {@value #LOOKOUT_ARROW_RUN_MIN} or more lie near its tower: it walks the
     * village paths down to the nearest of them, picks up what lies near the
     * tower, and climbs back (its claim on the tower holds meanwhile). Arrows
     * vanish a minute after they land, and a lookout used to stay up until
     * they had; the ground pickup on its own cannot get down a ladder.
     */
    private Status tickLookoutArrowRun(Context ctx) {
        NpcEntity npc = ctx.npc();
        WatchtowerBuilding tower = lookout.tower();
        BlockPos base = tower.getItemFramePos();
        double radiusSq = LOOKOUT_ARROW_RUN_RADIUS * LOOKOUT_ARROW_RUN_RADIUS;
        java.util.function.Predicate<BlockPos> nearTower = pos -> {
            double dx = pos.getX() - base.getX();
            double dz = pos.getZ() - base.getZ();
            return dx * dx + dz * dz <= radiusSq;
        };
        // Only what lies at its own level is picked up: up top, arrows stuck in
        // the railing; once down, those on the ground. It used to try to walk
        // straight to arrows far below while still up, and pushed against the
        // railing for the best part of a minute.
        java.util.function.Predicate<BlockPos> atMyLevel = pos -> nearTower.test(pos)
                && Math.abs(pos.getY() - npc.getBlockY()) <= 2;
        long now = ctx.level().getGameTime();
        if (arrowRunMove == null && !arrowRunPicking) {
            if (now < nextArrowRunCheckAt && nextArrowRunCheckAt - now <= LOOKOUT_RETRY_TICKS) {
                return Status.DONE;
            }
            nextArrowRunCheckAt = now + LOOKOUT_ARROW_RUN_CHECK_TICKS;
            if (isLookoutUp(npc) && recovery.lyingIn(ctx, pos -> atMyLevel.test(pos)
                    && pos.distSqr(npc.blockPosition()) <= ARROW_PLATFORM_REACH_SQ, 1) != null) {
                // Within reach up here: picked up without going down.
                arrowRunPicking = true;
                arrowRunTicks = 0;
            } else {
                int platformY = tower.platformFeetY();
                BlockPos nearest = recovery.lyingIn(ctx,
                        pos -> nearTower.test(pos) && pos.getY() <= platformY - 3, LOOKOUT_ARROW_RUN_MIN);
                if (nearest == null) return Status.DONE;
                // Not with monsters about: it would walk down among them, at night as like as not.
                if (hostileNearTower(ctx, base)) return Status.DONE;
                NavGraph graph = ctx.mgr().getGraph();
                NavNode node = graph != null ? graph.nearest(nearest, 3, 3) : null;
                if (node == null) return Status.DONE;
                lookout.pauseMove(ctx);
                arrowRunMove = new MoveActivity(node.feetPos(), VillagerModel.WALK_SPEED, true);
                arrowRunTicks = 0;
                ctx.brain().diary().log(com.alex.test.village.brain.VillagerDiary.ts(ctx.level())
                        + " LOOKOUT down the tower for its arrows");
            }
        }
        if (++arrowRunTicks > LOOKOUT_ARROW_RUN_MAX_TICKS) {
            cancelArrowRun(ctx);
            return Status.DONE;
        }
        if (arrowRunMove != null) {
            // A monster turned up on the way down: back up the tower.
            if (arrowRunTicks % 20 == 0 && hostileNearTower(ctx, base)) {
                cancelArrowRun(ctx);
                return Status.DONE;
            }
            Status status = arrowRunMove.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            arrowRunMove.stop(ctx);
            arrowRunMove = null;
            if (status == Status.FAILED) {
                // No way down to them: not tried again for a while.
                cancelArrowRun(ctx);
                nextArrowRunCheckAt = now + LOOKOUT_RETRY_TICKS;
                return Status.DONE;
            }
            arrowRunPicking = true;
        }
        if (recovery.tickIn(ctx, atMyLevel) == Status.RUNNING) return Status.RUNNING;
        cancelArrowRun(ctx);
        return Status.DONE;
    }

    /** No hostile mob this close to the tower before a lookout goes down for its arrows. */
    private static final double ARROW_RUN_SAFE_RADIUS = 16.0D;
    /** Arrows this close on the platform (stuck in the railing) are picked up without going down. */
    private static final double ARROW_PLATFORM_REACH_SQ = 5.0D * 5.0D;

    private static boolean hostileNearTower(Context ctx, BlockPos base) {
        AABB box = new AABB(base).inflate(ARROW_RUN_SAFE_RADIUS, 24.0D, ARROW_RUN_SAFE_RADIUS);
        return !ctx.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box,
                mob -> mob.isAlive() && GuardThreatRules.isHostileMonsterForGuard(ctx.npc(), mob)).isEmpty();
    }

    private void cancelArrowRun(Context ctx) {
        if (arrowRunMove != null) arrowRunMove.stop(ctx);
        arrowRunMove = null;
        if (arrowRunPicking) recovery.cancel(ctx.npc());
        arrowRunPicking = false;
        arrowRunTicks = 0;
    }

    private void cancelLookout(Context ctx) {
        lookoutReach.clear();
        cancelArrowRun(ctx);
        if (lookout == null) return;
        lookout.stop(ctx);
        lookout = null;
    }

    /** Keeping watch and standing on its platform now (not still climbing, not down for a fight). */
    private boolean isLookoutUp(NpcEntity npc) {
        return lookout != null && lookout.isUp(npc);
    }

    /**
     * How far above a mob this archer may shoot from: 12 blocks, or for a
     * lookout up its tower the tower's whole height and a little more. Archers
     * were held to 12 everywhere, so a lookout on a taller tower could never
     * shoot at anything on the ground.
     */
    private double maxShotDrop(NpcEntity npc) {
        if (!isLookoutUp(npc)) return ArcherCombatRules.MAX_SHOT_DROP;
        return Math.max(ArcherCombatRules.MAX_SHOT_DROP,
                lookout.tower().getHeightAboveGround() + LOOKOUT_EXTRA_DROP);
    }

    /**
     * Whether the lookout could hit {@code mob} from somewhere on its platform:
     * in range, not too far below, with nothing solid in the way. Kept for a
     * second per mob. A lookout sees much farther than it shoots, and it used
     * to take mobs it could not reach, stand frozen until it gave up, and stop
     * keeping watch meanwhile.
     */
    private boolean lookoutCanReach(Context ctx, LivingEntity mob) {
        long now = ctx.level().getGameTime();
        Reach cached = lookoutReach.get(mob.getUUID());
        if (cached != null && now >= cached.at() && now - cached.at() < LOOKOUT_REACH_RECHECK_TICKS) {
            return cached.ok();
        }
        WatchtowerBuilding tower = lookout.tower();
        double range = maxShoot(ctx);
        BlockPos frame = tower.getItemFramePos();
        double dx = mob.getX() - (frame.getX() + 0.5D);
        double dz = mob.getZ() - (frame.getZ() + 0.5D);
        // Cheap first: further out than any platform spot could shoot.
        double reach = range + WatchtowerBuilding.PLATFORM_RADIUS + 1.0D;
        boolean ok = dx * dx + dz * dz <= reach * reach
                && ArcherCombatRules.bestSpotAmong(ctx, lookout.usableSpots(ctx), mob, LOOKOUT_MIN_SHOOT_RANGE,
                        range, maxShotDrop(ctx.npc())) != null;
        if (lookoutReach.size() > 16) lookoutReach.clear();
        lookoutReach.put(mob.getUUID(), new Reach(ok, now));
        return ok;
    }

    /**
     * The nearest hostile a lookout can hit from its platform, looked for at
     * most twice a second. The village's threat list may put a mob far out of
     * the lookout's reach first, and the lookout would then shoot at nothing
     * while a zombie walked past the foot of its tower.
     */
    @Nullable
    private LivingEntity lookoutOwnPick(Context ctx) {
        long now = ctx.level().getGameTime();
        if (now < nextLookoutOwnPickAt && nextLookoutOwnPickAt - now <= LOOKOUT_OWN_PICK_TICKS) return null;
        nextLookoutOwnPickAt = now + LOOKOUT_OWN_PICK_TICKS;
        NpcEntity npc = ctx.npc();
        double range = maxShoot(ctx) + WatchtowerBuilding.PLATFORM_RADIUS;
        AABB box = new AABB(npc.getX() - range, npc.getY() - maxShotDrop(npc) - 1.0D, npc.getZ() - range,
                npc.getX() + range, npc.getY() + 4.0D, npc.getZ() + range);
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (LivingEntity mob : ctx.level().getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (!isValidTarget(ctx, mob) || isIgnored(ctx, mob)) continue;
            // Only inside the village: one out in the fields is bothering nobody,
            // and an arrow from the tower would only start a fight.
            if (!ctx.mgr().contains(mob.blockPosition())) continue;
            double d = npc.distanceToSqr(mob);
            if (d >= bestSq || !lookoutCanReach(ctx, mob)) continue;
            bestSq = d;
            best = mob;
        }
        return best;
    }

    /**
     * Where this archer keeps watch, for the shift handover: its tower, or the
     * guard post it is standing at. Null while it patrols.
     */
    @Nullable
    public BlockPos activeWatchPost() {
        if (lookout != null) return lookout.tower().getItemFramePos();
        return postPatrol.activePost();
    }

    /** The watchtower this archer keeps watch on, or null; see {@link #activeWatchPost}. */
    @Nullable
    public WatchtowerBuilding activeTower() {
        return lookout != null ? lookout.tower() : null;
    }

    @Override
    protected void doStop(Context ctx) {
        retreat.cancel(ctx);
        cancelAcquire(ctx);
        cancelPatrol(ctx);
        cancelLookout(ctx);
        cancelFlee(ctx);
        cancelReposition(ctx);
        cancelFarApproach(ctx);
        cancelRunToGuard(ctx);
        if (targetId != null) GuardAlertSystem.clearTarget(ctx, ctx.npc().getNpcId(), targetId);
        target = null;
        targetId = null;
        ctx.model().stopMoving();
        ctx.npc().getNavigation().stop();
        ctx.npc().setTarget(null);
    }

    @Override
    public String name() { return "archer"; }
}
