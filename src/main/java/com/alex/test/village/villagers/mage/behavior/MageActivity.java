package com.alex.test.village.villagers.mage.behavior;

import com.alex.test.animation.HurryGait;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.ArcaneBoltEntity;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.EatActivity;
import com.alex.test.village.brain.FleeActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.villagers.archer.behavior.combat.ArcherCombatRules;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.guard.GuardShift;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import com.alex.test.village.villagers.guard.behavior.DefenderRetreat;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.guard.behavior.combat.GuardCombatRules;
import com.alex.test.village.villagers.guard.behavior.patrol.GuardPatrolPlanner;
import com.alex.test.village.villagers.guard.behavior.patrol.GuardPostPatrol;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.mage.MageBalance;
import com.alex.test.village.villagers.mage.MageCastTiming;
import com.alex.test.village.villagers.mage.MageMana;
import com.alex.test.village.villagers.mage.MageReagents;
import com.alex.test.village.villagers.mage.MageSettingsBridge;
import com.alex.test.village.villagers.mage.settings.MageSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.mage.MageSpellPlanner;
import com.alex.test.village.villagers.mage.MageSpellPlanner.Spell;
import com.alex.test.village.villagers.mage.MageTargeting;
import com.alex.test.village.villagers.tools.GetToolActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The mage's work: night patrol and guard posts like an archer, and in a
 * fight, spells from behind the guards (mage plan rounds 1-4).
 *
 * <p>Built the way {@code ArcherActivity} is, from the same shared pieces:
 * {@link GuardAlertSystem} hands it mobs (it counts as a ranged defender),
 * {@link ArcherCombatRules} finds it a spot with a clear view, and it runs
 * from a mob that comes close, behind a guard if one is on the far side.
 * What it does not have is everything about arrows: no misses to pick up, no
 * towers or range practice yet. Its one supply is reagents (amethyst, then
 * lapis), fetched from storage between fights, never in one.
 *
 * <p>It casts bare-handed: nothing is ever in its hands. Each spell gathers in
 * them as light before the release ({@link MageSpells#chargeGlow}) and leaves
 * as particles. Which spell is {@link MageSpellPlanner}'s call: a Ward for a
 * guard about to fall, Starfall once a fight on a big pack, Frost Nova on a
 * pack, Chain Spark when it can jump, else the free Arcane Bolt. Cornered with
 * no guard near, a mage that knows Blink vanishes and reappears a few blocks
 * away. Out of reagents it still fights, with bolts and wards.
 *
 * <p>A bolt can only ever hit the hostile mob it was cast at (see
 * {@link ArcaneBoltEntity}), and the other spells only hostile mobs
 * ({@link MageSpells#isFoe}), so the mage never hurts a villager, a player, a
 * pet or a golem, whoever steps into its line.
 */
public final class MageActivity extends WorkActivity {

    /** A mob this close is run from (a guard between them holds it off instead). */
    private static final double DANGER_RANGE = 5.0D;
    /** No guard or captain near: the mage fights alone and keeps a wider gap. */
    private static final double SOLO_DANGER_RANGE = 7.0D;
    private static final double SOLO_MIN_CAST_RANGE = 8.0D;
    private static final double SOLO_CHECK_RADIUS = 24.0D;
    /** A mob this close sends the mage running even mid-cast. */
    private static final double POINT_BLANK = 3.0D;
    private static final double FLEE_RESNAP_DIST_SQ = 3.0D * 3.0D;
    /**
     * Once it runs, it runs on until the mob is this much past its shortest
     * cast range, so it has room for a whole cast when it stops. It used to
     * stop at the edge of the danger range, inside its shortest cast range,
     * where it could neither cast nor stay: a step of the mob's sent it running
     * again, every few ticks, and it never cast at all.
     */
    private static final double FLEE_SAFE_MARGIN = 2.5D;
    /**
     * The run went nowhere (a wall, a corner, out of time) with the mob still
     * on it: it turns and casts at whatever range for this long, a cast and
     * its follow-through, before it tries running again.
     */
    private static final int STAND_GROUND_TICKS = 70;
    private static final double GIVE_UP_TARGET_DIST_SQ = 42.0D * 42.0D;
    /** Past the cast range plus this, the mage runs over to the mob first. */
    private static final double FAR_APPROACH_MARGIN = 10.0D;
    private static final double FAR_APPROACH_REPLAN_SQ = 8.0D * 8.0D;
    private static final int FAR_APPROACH_STALL_TICKS = 300;
    private static final int FAR_APPROACH_RETRY_TICKS = 20;
    private static final float KITE_SPEED = VillagerModel.DEFAULT_SPEED * 1.3F;
    private static final int TARGET_REPATH_INTERVAL = 20;
    private static final int STALE_SPOT_REPATH_TICKS = 4;
    private static final int COMBAT_END_COOLDOWN_TICKS = 60;
    /** Moved more than this (squared, blocks) since last tick: walking, not standing. */
    private static final double MOVING_SQ = 0.05 * 0.05;
    private static final int RECENT_ATTACKER_MEMORY_TICKS = 200;
    /** Combat ticks without a bolt cast before the mob is given up. */
    private static final int STANDOFF_GIVE_UP_TICKS = 200;
    private static final int STANDOFF_IGNORE_TICKS = 1200;
    private static final int CHASE_GIVE_UP_TICKS = 600;
    /** A guard this close, on the far side from the mob, is run to instead of just away. */
    private static final double HAVEN_RADIUS = 16.0D;
    private static final double HAVEN_BEHIND_DIST = 3.0D;
    private static final double HAVEN_ARRIVED_SQ = 1.5D * 1.5D;
    private static final double HAVEN_REPLAN_SQ = 3.0D * 3.0D;
    private static final float REGROUP_HEALTH_FRACTION = 0.7f;
    private static final int BLOW_CHECK_TICKS = 10;

    /** Starving, carrying food and no mob this close: the brain may send it to eat. */
    private static final double MEAL_BREAK_SAFE_RANGE_SQ = 8.0D * 8.0D;
    private static final int RETRY_COOLDOWN_TICKS = 600;
    /** Mana is saved to the villager at most this often, and after every spell. */
    private static final int MANA_SAVE_TICKS = 200;
    /** A mob in sight this far past the cast range still counts for the area spells. */
    private static final double AREA_LOOK_MARGIN = MageBalance.NOVA_RADIUS;
    /** The light gathers in the hands over this many ticks before the release, at most. */
    private static final int GLOW_TICKS = 10;
    /** How often a mage on night duty looks for a dark spot to light. */
    private static final int LIGHT_CHECK_TICKS = 100;
    /** How often a mage on alert shows a faint glow about its hands. */
    private static final int ALERT_GLOW_EVERY = 6;

    private final DefenderRetreat retreat = new DefenderRetreat();
    private final GuardPostPatrol postPatrol = new GuardPostPatrol();
    private int lastHurtTick = Integer.MIN_VALUE / 2;
    private float heaviestBlow;
    private long heaviestBlowAt = Long.MIN_VALUE / 2;

    @Nullable private LivingEntity target;
    @Nullable private UUID targetId;
    /** Ticks left until the spell leaves; 0 when no cast is under way. */
    private int castTicks;
    /** How long the cast under way takes to its release, for {@link #finishesCast}. */
    private int castTotal;
    /** The spell under way, its mob (or the ward's guard), and where an area spell lands. */
    private Spell castSpell = Spell.ARCANE_BOLT;
    @Nullable private LivingEntity castAim;
    @Nullable private Vec3 castSpot;
    /** The mobs a Chain Spark jumps through, in order. */
    private List<LivingEntity> castChain = List.of();
    /** Mobs in the pack an area spell was aimed at, for the number of stars. */
    private int castPack = 1;
    @Nullable private SpellTimers timers;
    @Nullable private MageMana mana;
    private long manaSavedAt;
    @Nullable private GetReagentsActivity getReagents;
    private long nextReagentAttemptAt;
    /** Starving with food and no mob close: the brain may send it to eat. */
    private boolean mealBreak;
    private int recoverTicks;
    private int combatCooldown;
    private int standoffTicks;
    private int chaseTicks;
    private int repathTimer;
    private boolean alone;
    private long aloneCheckedAt = Long.MIN_VALUE / 2;

    @Nullable private FleeActivity fleeActivity;
    /** Game time until which it stands its ground and casts (see {@link #STAND_GROUND_TICKS}). */
    private long standGroundUntil;
    @Nullable private Vec3 fleeSnapshot;
    @Nullable private MoveActivity repositionMove;
    @Nullable private BlockPos repositionTarget;
    @Nullable private MoveActivity approachMove;
    @Nullable private BlockPos approachMobAt;
    private double approachBest = Double.MAX_VALUE;
    private int approachStallTicks;
    private int approachRetryTicks;
    @Nullable private MoveActivity havenMove;
    @Nullable private BlockPos havenGoal;
    @Nullable private NpcEntity havenGuard;
    private boolean hurrying;
    @Nullable private MoveActivity patrolMove;
    private int patrolPause;

    /**
     * Spell cooldowns, and whether Starfall has fallen this fight, kept per
     * mage rather than per activity: a mage that steps away to eat mid-fight
     * comes back to a new activity, and must not find them all ready again.
     * Game times, so forgotten when the server stops.
     */
    private static final class SpellTimers {
        long novaReadyAt;
        long chainReadyAt;
        long wardReadyAt;
        long blinkReadyAt;
        /** Starfall falls once per fight; a fight ends when the mage has had nothing to fight for a while. */
        boolean starfallUsed;
        long lastFoughtAt = Long.MIN_VALUE / 2;
    }

    private static final java.util.Map<UUID, SpellTimers> TIMERS = new java.util.HashMap<>();

    private SpellTimers timers(Context ctx) {
        if (timers == null) {
            if (TIMERS.size() > 256) TIMERS.clear();
            timers = TIMERS.computeIfAbsent(ctx.model().npcId, id -> new SpellTimers());
        }
        return timers;
    }

    /** Called when the server stops (see {@link MageSpells#onServerStopping}). */
    static void forgetTimers() {
        TIMERS.clear();
    }

    @Override
    protected void start(Context ctx) {
        emptyHands(ctx.npc());
        GuardSettings.of(ctx.npc()).clearLegacyOrder();
        // The duty reads the guard settings: they follow the mage screen.
        MageSettingsBridge.applyToGuardSettings(ctx.npc());
        postPatrol.start(ctx);
    }

    @Override
    protected Status doTick(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (npc.getProfession() != Profession.MAGE) return Status.DONE;
        emptyHands(npc);
        GuardSettings settings = GuardSettings.of(npc);
        ProfessionSettings mageSettings = MageSettingsBridge.of(npc);
        boolean workEnabled = mageSettings.getBool(MageSettingsSchema.WORK_ENABLED);

        if (recoverTicks > 0) recoverTicks--;
        if (combatCooldown > 0) combatCooldown--;
        // The follow-through of a cast holds the legs still: once it walks
        // (flees, repositions, gives chase), the pose gives way.
        if (castTicks <= 0 && npc.getAnimation().isMageCast()
                && npc.position().subtract(npc.xo, npc.yo, npc.zo).horizontalDistanceSqr() > MOVING_SQ) {
            npc.setAnimation(VillagerAnimationState.IDLE);
        }
        tickExperience(ctx);
        tickManaSave(ctx);

        LivingEntity recentAttacker = recentDirectAttacker(ctx);
        if (recentAttacker != null) {
            GuardAlertSystem.reportDirectDamageThreat(ctx, npc, recentAttacker);
            acceptTarget(ctx, recentAttacker);
        } else if (workEnabled && mageSettings.getBool(MageSettingsSchema.PROACTIVE_TARGETING)) {
            // Hunting off: only a mob that hits it is fought (the branch above).
            GuardAlertSystem.GuardTarget alertTarget = GuardAlertSystem.findBestTargetForGuard(ctx);
            LivingEntity offered = alertTarget != null ? alertTarget.entity() : null;
            // A monster switched off in its settings is left to the others.
            if (offered != null && !isValidTarget(ctx, offered)) {
                // Kept off it for half a minute, so the team plan sends someone else
                // instead of offering it straight back.
                GuardAlertSystem.stepAside(ctx, npc.getNpcId(), offered.getUUID(), 600);
                offered = null;
            }
            if (offered != null && (!isIgnored(ctx, offered) || ArcherCombatRules.canShoot(npc, offered,
                    0.0D, MageBalance.CAST_RANGE * MageBalance.CAST_RANGE))) {
                acceptTarget(ctx, offered);
            } else if (isValidTarget(ctx, target) && !GuardAlertSystem.mayKeepTarget(ctx, target)) {
                // Its team has enough without it: back to patrol.
                releaseTarget(ctx);
            } else if (!isValidTarget(ctx, target) && !GuardAlertSystem.usesTeams(ctx)
                    && (GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level()) || GuardAlertSystem.isOnAlert(npc))) {
                // Called out with no mob given to it: the nearest danger, run to.
                LivingEntity danger = GuardAlertSystem.nearestDanger(ctx);
                if (danger != null && isValidTarget(ctx, danger)) acceptTarget(ctx, danger);
            }
        }

        tickHealing(ctx);
        mealBreak = isStarvingWithFood(ctx)
                && (target == null || npc.distanceToSqr(target) > MEAL_BREAK_SAFE_RANGE_SQ);

        // Hurt: run for the closest building and wait there (shared with guards and archers).
        if (retreat.isActive() || (isValidTarget(ctx, target) && isLowOnHealth(ctx))) {
            cancelAcquire(ctx);
            cancelPatrol(ctx);
            if (tickRetreat(ctx) == Status.RUNNING) return Status.RUNNING;
        }

        if (isValidTarget(ctx, target)) {
            cancelAcquire(ctx);
            cancelPatrol(ctx);
            timers(ctx).lastFoughtAt = ctx.level().getGameTime();
            return tickCombat(ctx);
        }

        if (targetId != null) {
            GuardAlertSystem.clearTarget(ctx, npc.getNpcId(), targetId);
            targetId = null;
        }
        stopCast(ctx, "the mob is dead or gone");
        target = null;
        npc.setTarget(null);
        cancelFlee(ctx);
        cancelReposition(ctx);
        cancelFarApproach(ctx);
        cancelRunToGuard(ctx);
        // The fight is over once it has been quiet a while: Starfall is ready for the next.
        if (combatCooldown <= 0
                && ctx.level().getGameTime() - timers(ctx).lastFoughtAt > COMBAT_END_COOLDOWN_TICKS) {
            timers(ctx).starfallUsed = false;
        }

        if (GuardAlertSystem.isOnAlert(npc) && Math.floorMod(npc.tickCount, ALERT_GLOW_EVERY) == 0) {
            MageSpells.alertGlow(ctx.level(), at(npc, MageCastTiming.castingHand(MageCastTiming.Clip.BOLT)));
        }

        // A trip under way is called off, not left hanging, while danger is about.
        if (getReagents != null && (combatCooldown > 0 || GuardAlertSystem.hasEmergencyForGuard(ctx))) {
            cancelAcquire(ctx);
        }
        if (combatCooldown > 0) return Status.RUNNING;
        // Working off: it still defends itself (above), but does no duty.
        if (!workEnabled) {
            cancelAcquire(ctx);
            return Status.DONE;
        }

        // Reagents once the pouch runs low: after the fight and the danger are over.
        if (!GuardAlertSystem.hasEmergencyForGuard(ctx) && tickAcquire(ctx) == Status.RUNNING) {
            return Status.RUNNING;
        }

        boolean scheduled = GuardShift.scheduled(ctx, settings);
        if (!scheduled && !GuardAlertSystem.hasEmergencyForGuard(ctx)) return Status.DONE;
        if (scheduled) tickNightLights(ctx);

        // Guard posts are shared with the guards and archers.
        if (settings.useGuardPosts()) {
            Status status = postPatrol.tick(ctx, settings, combatCooldown);
            if (status == Status.RUNNING) return Status.RUNNING;
        } else {
            postPatrol.cancel(ctx);
        }
        return tickPatrol(ctx);
    }

    /** Practice paid to the mage's own track: time on duty, every spell that hit, every kill. */
    private static void tickExperience(Context ctx) {
        NpcEntity npc = ctx.npc();
        int[] combat = MageSpells.takeCombat(npc.getNpcId());
        ctx.brain().onDefenderCombat(ctx, combat[0], combat[1]);
        if (Math.floorMod(ctx.level().getGameTime() + npc.getId(), GuardBalance.DUTY_XP_INTERVAL_TICKS) == 0) {
            ctx.brain().onDefenderDuty(ctx);
        }
    }

    private static boolean isStarvingWithFood(Context ctx) {
        VillagerStats needs = ctx.brain().needs();
        return needs.hungerRules().isCritical(needs) && EatActivity.findFoodSlot(ctx) >= 0;
    }

    /**
     * Nothing in its hands: the mage casts bare-handed. A display item (a gift
     * being handed over) is left for its moment; anything else -- a blaze rod
     * from before mages lost their staffs -- is put away into the pack.
     */
    private static void emptyHands(NpcEntity npc) {
        ItemStack held = npc.getMainHandItem();
        if (!held.isEmpty() && !NpcEntity.isDisplayOnly(held)) npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (!npc.getOffhandItem().isEmpty()) npc.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    private static int level(Context ctx) {
        return ctx.brain().defenderSkillLevel();
    }

    private MageMana mana(Context ctx) {
        if (mana == null) mana = MageReagents.loadMana(ctx.npc(), level(ctx), ctx.level().getGameTime());
        return mana;
    }

    private void tickManaSave(Context ctx) {
        long now = ctx.level().getGameTime();
        if (mana != null && (now - manaSavedAt >= MANA_SAVE_TICKS || now < manaSavedAt)) saveMana(ctx);
    }

    private void saveMana(Context ctx) {
        if (mana == null) return;
        MageReagents.saveMana(ctx.npc(), mana);
        manaSavedAt = ctx.level().getGameTime();
    }

    // =========================================================================
    // Falling back and healing
    // =========================================================================

    /** Below the day's retreat health, or one blow from the hardest hitter near it away from death. */
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

    /** RUNNING on the way and while sheltering (casting at what comes into view), DONE once fit again. */
    private Status tickRetreat(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (!retreat.isActive()) {
            cancelFlee(ctx);
            cancelReposition(ctx);
            cancelFarApproach(ctx);
            cancelRunToGuard(ctx);
            stopCast(ctx, "running for shelter");
            npc.getNavigation().stop();
            retreat.begin(ctx, target);
        }
        boolean fit = npc.getHealth() >= npc.getMaxHealth() * REGROUP_HEALTH_FRACTION;
        DefenderRetreat.Phase phase = retreat.tick(ctx, fit);
        if (phase == DefenderRetreat.Phase.RUNNING) return Status.RUNNING;
        if (phase == DefenderRetreat.Phase.HOLDING) {
            npc.getNavigation().stop();
            LivingEntity foe = target;
            if (isValidTarget(ctx, foe) && ArcherCombatRules.canShoot(npc, foe, 0.0D,
                    MageBalance.CAST_RANGE * MageBalance.CAST_RANGE)) {
                npc.setTarget(foe);
                tickCast(ctx, npc, foe);
            } else {
                stopCast(ctx, "no clear view (sheltering)");
            }
            return Status.RUNNING;
        }
        combatCooldown = COMBAT_END_COOLDOWN_TICKS;
        return Status.DONE;
    }

    /** Out of a fight, or sheltering, a mage heals like a guard. */
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
        LivingEntity foe = target;
        if (!isValidTarget(ctx, foe)) return Status.DONE;
        // A fight is on: it is over only once nothing has been fought for a while.
        combatCooldown = Math.max(combatCooldown, COMBAT_END_COOLDOWN_TICKS);

        if (GuardThreatRules.isTooFarFromVillage(ctx, foe.blockPosition())
                && npc.distanceToSqr(foe) > GIVE_UP_TARGET_DIST_SQ) {
            releaseTarget(ctx);
            combatCooldown = COMBAT_END_COOLDOWN_TICKS;
            return Status.RUNNING;
        }

        double distSq = npc.distanceToSqr(foe);
        boolean solo = isAlone(ctx);
        double danger = solo ? SOLO_DANGER_RANGE : DANGER_RANGE;
        double castFrom = solo ? SOLO_MIN_CAST_RANGE : MageBalance.MIN_CAST_RANGE;
        double runTo = castFrom + FLEE_SAFE_MARGIN;
        // Cornered: it casts at whatever range instead of running nowhere.
        boolean standing = ctx.level().getGameTime() < standGroundUntil;
        double minCast = standing ? 0.0D : castFrom;
        // A guard between the mage and the mob holds it off: stay and cast.
        boolean covered = distSq <= danger * danger && distSq > POINT_BLANK * POINT_BLANK
                && ArcherCombatRules.isCoveredByGuard(npc, foe);
        // Running already, it keeps running until it has room to cast.
        boolean tooClose = distSq <= danger * danger || (fleeActivity != null && distSq < runTo * runTo);
        boolean fleeing = !standing && tooClose
                && !ArcherCombatRules.isOutOfReachAbove(npc, foe) && !covered;
        boolean farOff = distSq > farApproachSq();
        if (!farOff) cancelFarApproach(ctx);
        if (!fleeing && !farOff) {
            if (repositionMove != null) chaseTicks++;
            else standoffTicks++;
        }
        if (standoffTicks > STANDOFF_GIVE_UP_TICKS || chaseTicks > CHASE_GIVE_UP_TICKS) {
            GuardAlertSystem.giveUpUnreachable(ctx, npc.getNpcId(), foe, STANDOFF_IGNORE_TICKS);
            releaseTarget(ctx);
            combatCooldown = COMBAT_END_COOLDOWN_TICKS;
            return Status.RUNNING;
        }

        npc.setTarget(foe);

        // Cornered with a mob on top of it: a Blink still gets it out.
        if (standing && castTicks <= 0 && tickBlink(ctx, foe)) {
            standGroundUntil = 0L;
            return Status.RUNNING;
        }

        // Past halfway into a cast, a mob that has closed in does not cost the
        // bolt: finishing is quicker than turning to run.
        if (fleeing && finishesCast(npc, foe)) {
            cancelReposition(ctx);
            npc.getNavigation().stop();
            tickCast(ctx, npc, foe);
            return Status.RUNNING;
        }

        if (fleeing) {
            stopCast(ctx, "the mob came too close, backing off");
            cancelReposition(ctx);
            // Cornered and nobody to run to: it blinks away, if it knows how.
            if (tickBlink(ctx, foe)) return Status.RUNNING;
            if (tickRunToGuard(ctx, foe)) return Status.RUNNING;
            Vec3 threatNow = foe.position();
            if (fleeActivity != null && fleeSnapshot != null
                    && threatNow.distanceToSqr(fleeSnapshot) > FLEE_RESNAP_DIST_SQ) {
                fleeActivity.retarget(foe);
                fleeSnapshot = threatNow;
            }
            if (fleeActivity == null) {
                // Keeping its distance, not afraid: no happiness lost for it.
                fleeActivity = FleeActivity.kite(foe);
                fleeSnapshot = threatNow;
            }
            Status status = fleeActivity.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            cancelFlee(ctx);
            // Nowhere left to run, or out of time, and the mob still close: fight.
            if (npc.distanceToSqr(foe) < runTo * runTo) {
                standGroundUntil = ctx.level().getGameTime() + STAND_GROUND_TICKS;
                ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " MAGE cornered by "
                        + foe.getName().getString() + ", standing to cast");
            }
            return Status.RUNNING;
        }
        cancelFlee(ctx);
        cancelRunToGuard(ctx);

        ArcherCombatRules.Blocker blocker = ArcherCombatRules.shotBlocker(npc, foe,
                minCast * minCast, MageBalance.CAST_RANGE * MageBalance.CAST_RANGE,
                ArcherCombatRules.MAX_SHOT_DROP);
        // The shortest range is where a cast starts, not where one under way is given up.
        if (blocker == null
                || blocker == ArcherCombatRules.Blocker.TOO_CLOSE && finishesCast(npc, foe)) {
            cancelReposition(ctx);
            npc.getNavigation().stop();
            tickCast(ctx, npc, foe);
            return Status.RUNNING;
        }
        stopCast(ctx, blocker.why());

        if (farOff) {
            cancelReposition(ctx);
            return tickFarApproach(ctx, foe);
        }

        if (repositionMove != null && repositionTarget != null) {
            if (ArcherCombatRules.isPositionStillUseful(
                    ctx, repositionTarget, foe, minCast, MageBalance.CAST_RANGE)) {
                Status status = repositionMove.tickOuter(ctx);
                if (status == Status.RUNNING) return Status.RUNNING;
            } else {
                repathTimer = Math.min(repathTimer, STALE_SPOT_REPATH_TICKS);
            }
            cancelReposition(ctx);
        }
        if (repositionMove == null && --repathTimer <= 0) {
            repathTimer = TARGET_REPATH_INTERVAL;
            BlockPos spot = ArcherCombatRules.bestRangedPosition(ctx, foe, minCast, MageBalance.CAST_RANGE);
            if (spot != null) {
                repositionTarget = spot;
                repositionMove = new MoveActivity(spot, KITE_SPEED, true);
            }
        }
        if (repositionMove != null) {
            Status status = repositionMove.tickOuter(ctx);
            if (status != Status.RUNNING) cancelReposition(ctx);
        }
        return Status.RUNNING;
    }

    /**
     * One tick of casting at {@code foe}: start a cast (the spell's clip, paced
     * so its release frame lands on the spell), let the light gather in the
     * hands, count down, let the spell go, then rest before the next.
     */
    private void tickCast(Context ctx, NpcEntity npc, LivingEntity foe) {
        npc.holdStillForAnimation();
        emptyHands(npc);
        if (castSpot != null) GuardCombatRules.face(npc, castSpot);
        else if (castTicks > 0 && castAim != null && castAim.isAlive()) GuardCombatRules.face(npc, castAim.getEyePosition());
        else GuardCombatRules.face(npc, foe.getEyePosition());
        int level = level(ctx);
        if (castTicks > 0) {
            glow(ctx, npc);
            if (--castTicks > 0) return;
            release(ctx, npc, foe, level);
            recoverTicks = Math.max(MageBalance.recoveryTicks(level),
                    MageCastTiming.followThroughTicks(castSpell.clip()));
            castSpot = null;
            standoffTicks = 0;
            chaseTicks = 0;
            return;
        }
        if (recoverTicks <= 0) startCast(ctx, npc, foe, level);
    }

    /** Reads the fight, picks the spell (see {@link MageSpellPlanner}) and starts its clip. */
    private void startCast(Context ctx, NpcEntity npc, LivingEntity foe, int level) {
        long now = ctx.level().getGameTime();

        // Every mob in sight within reach of the area spells, as points.
        List<LivingEntity> mobs = foesInSight(ctx, MageBalance.CAST_RANGE + AREA_LOOK_MARGIN);
        if (!mobs.contains(foe)) mobs.add(0, foe);
        List<MageTargeting.Point> points = new ArrayList<>(mobs.size());
        for (LivingEntity mob : mobs) points.add(new MageTargeting.Point(mob.getX(), mob.getY(), mob.getZ()));

        MageTargeting.Cluster pack = MageTargeting.densest(points, MageBalance.NOVA_RADIUS);
        LivingEntity packCentre = pack.index() >= 0 ? mobs.get(pack.index()) : foe;
        // The pack's centre has to be somewhere the mage can see and reach.
        int packSize = npc.distanceTo(packCentre) <= MageBalance.CAST_RANGE && ArcherCombatRules.hasSight(npc, packCentre)
                ? pack.count() : 1;
        int first = mobs.indexOf(foe);
        int chainSize = MageTargeting.chainLength(points, first, MageBalance.CHAIN_JUMP_RANGE,
                Math.max(1, MageBalance.chainHits(level)));
        LivingEntity wardFor = MageBalance.hasWard(level) ? wardCandidate(ctx) : null;

        SpellTimers t = timers(ctx);
        Spell spell = MageSpellPlanner.choose(new MageSpellPlanner.Situation(
                level, mana(ctx).current(now, level), MageReagents.count(npc.getInventory()),
                packSize, chainSize, wardFor != null, t.starfallUsed,
                now >= t.novaReadyAt, now >= t.chainReadyAt, now >= t.wardReadyAt, switchedOff(npc)));

        castSpell = spell;
        castAim = foe;
        castSpot = null;
        castChain = List.of();
        castPack = packSize;
        switch (spell) {
            case WARD -> castAim = wardFor;
            case FROST_NOVA, STARFALL -> {
                castAim = packCentre;
                castSpot = packCentre.position();
            }
            case CHAIN_SPARK -> castChain = chainPath(mobs, points, first, level);
            default -> {}
        }
        MageCastTiming.Clip clip = spell.clip();
        castTicks = MageCastTiming.releaseTicks(clip, level);
        castTotal = castTicks;
        npc.startWorkAnimation(MageCastTiming.state(clip), MageCastTiming.clipSpeed(clip, castTicks));
    }

    /** The spells the player has switched off in this mage's settings. */
    private static java.util.Set<Spell> switchedOff(NpcEntity npc) {
        ProfessionSettings settings = MageSettingsBridge.of(npc);
        java.util.EnumSet<Spell> off = java.util.EnumSet.noneOf(Spell.class);
        if (!settings.getBool(MageSettingsSchema.FROST_NOVA)) off.add(Spell.FROST_NOVA);
        if (!settings.getBool(MageSettingsSchema.WARD)) off.add(Spell.WARD);
        if (!settings.getBool(MageSettingsSchema.CHAIN_SPARK)) off.add(Spell.CHAIN_SPARK);
        if (!settings.getBool(MageSettingsSchema.STARFALL)) off.add(Spell.STARFALL);
        return off;
    }

    private static List<LivingEntity> chainPath(List<LivingEntity> mobs, List<MageTargeting.Point> points,
                                                int first, int level) {
        List<LivingEntity> path = new ArrayList<>();
        for (int i : MageTargeting.chain(points, first, MageBalance.CHAIN_JUMP_RANGE, MageBalance.chainHits(level))) {
            path.add(mobs.get(i));
        }
        return path;
    }

    /** Foes within {@code radius} of the mage that it can see. */
    private static List<LivingEntity> foesInSight(Context ctx, double radius) {
        NpcEntity npc = ctx.npc();
        List<LivingEntity> seen = new ArrayList<>();
        for (LivingEntity mob : MageSpells.foesNear(ctx.level(), npc, npc.position(), radius)) {
            if (ArcherCombatRules.hasSight(npc, mob)) seen.add(mob);
        }
        return seen;
    }

    /**
     * The guard or captain in the most trouble: fighting (or hurt in the last
     * five seconds), below {@link MageBalance#WARD_HEALTH_FRACTION} of its
     * health, in range and in sight, and not under a ward already.
     */
    @Nullable
    private static LivingEntity wardCandidate(Context ctx) {
        NpcEntity npc = ctx.npc();
        double r = MageBalance.WARD_RANGE;
        LivingEntity best = null;
        float bestShare = MageBalance.WARD_HEALTH_FRACTION;
        for (NpcEntity guard : npc.level().getEntitiesOfClass(NpcEntity.class, npc.getBoundingBox().inflate(r),
                e -> e != npc && e.isAlive() && e.getProfession().isGuardLike())) {
            if (guard.distanceToSqr(npc) > r * r) continue;
            boolean fighting = guard.isCombatReady() || guard.getTarget() != null
                    || guard.tickCount - guard.getLastHurtByMobTimestamp() < 100;
            if (!fighting || guard.getAbsorptionAmount() > 0f || MageSpells.isWarded(guard)) continue;
            float share = guard.getHealth() / guard.getMaxHealth();
            if (share >= bestShare || !npc.hasLineOfSight(guard)) continue;
            best = guard;
            bestShare = share;
        }
        return best;
    }

    /** The light gathering in the hands over the last ticks before the release. */
    private void glow(Context ctx, NpcEntity npc) {
        int window = Math.min(GLOW_TICKS, castTotal);
        if (castTicks > window) return;
        float charge = 1f - (castTicks - 1) / (float) Math.max(1, window);
        MageCastTiming.Clip clip = castSpell.clip();
        var colour = MageSpells.colourFor(castSpell);
        MageSpells.chargeGlow(ctx.level(), at(npc, MageCastTiming.castingHand(clip)), colour, charge);
        MageCastTiming.Hand other = MageCastTiming.otherHand(clip);
        if (other != null) MageSpells.chargeGlow(ctx.level(), at(npc, other), colour, charge);
    }

    /**
     * The spell leaves the hands. Paid for here, not at the start: a cast
     * broken off costs nothing. If the mana or reagents went meanwhile, a bolt
     * goes instead.
     */
    private void release(Context ctx, NpcEntity npc, LivingEntity foe, int level) {
        ServerLevel world = ctx.level();
        long now = world.getGameTime();
        Spell spell = castSpell;
        if (spell != Spell.ARCANE_BOLT && (!mana(ctx).canAfford(spell.mana(), now, level)
                || MageReagents.count(npc.getInventory()) < spell.reagents())) {
            spell = Spell.ARCANE_BOLT;
        }
        // The pack died or scattered during the cast: a bolt, not a reagent burnt on nothing.
        if (spell == Spell.FROST_NOVA && MageSpells.foesNear(world, npc,
                castSpot != null ? castSpot : foe.position(), MageBalance.NOVA_RADIUS).isEmpty()) {
            spell = Spell.ARCANE_BOLT;
        }
        if (spell == Spell.CHAIN_SPARK && castChain.stream().noneMatch(mob -> isValidTarget(ctx, mob))) {
            spell = Spell.ARCANE_BOLT;
        }
        Vec3 hand = at(npc, MageCastTiming.castingHand(castSpell.clip()));
        String what;
        switch (spell) {
            case FROST_NOVA -> {
                Vec3 spot = castSpot != null ? castSpot : foe.position();
                pay(ctx, spell, now, level);
                int caught = MageSpells.frostNova(world, npc, spot, level);
                timers(ctx).novaReadyAt = now + MageBalance.NOVA_COOLDOWN_TICKS;
                what = "Frost Nova caught " + caught;
            }
            case WARD -> {
                LivingEntity ally = castAim;
                if (ally == null || !ally.isAlive()) {
                    what = "Ward fizzled: the guard is gone";
                    break;
                }
                pay(ctx, spell, now, level);
                MageSpells.ward(world, npc, ally, hand, level);
                timers(ctx).wardReadyAt = now + MageBalance.WARD_COOLDOWN_TICKS;
                what = "Ward on " + ally.getName().getString();
            }
            case CHAIN_SPARK -> {
                pay(ctx, spell, now, level);
                int hits = MageSpells.chainSpark(world, npc, hand, castChain, level);
                timers(ctx).chainReadyAt = now + MageBalance.CHAIN_COOLDOWN_TICKS;
                what = "Chain Spark hit " + hits;
            }
            case STARFALL -> {
                Vec3 spot = castSpot != null ? castSpot : foe.position();
                pay(ctx, spell, now, level);
                int stars = MageBalance.starCount(castPack);
                MageSpells.starfall(world, npc, spot, stars);
                timers(ctx).starfallUsed = true;
                what = "Starfall, " + stars + " stars";
            }
            default -> {
                LivingEntity aim = castAim != null && castAim.isAlive() && isValidTarget(ctx, castAim)
                        && castSpell == Spell.ARCANE_BOLT ? castAim : foe;
                castBolt(ctx, npc, aim, level, hand);
                what = "Arcane Bolt at " + aim.getName().getString();
            }
        }
        saveMana(ctx);
        ctx.brain().diary().log(String.format("%s MAGE %s | mana %.0f, reagents %d",
                VillagerDiary.ts(world), what, mana(ctx).current(now, level),
                MageReagents.count(npc.getInventory())));
    }

    private void pay(Context ctx, Spell spell, long now, int level) {
        mana(ctx).trySpend(spell.mana(), now, level);
        MageReagents.spend(ctx.npc().getInventory(), spell.reagents());
    }

    private void castBolt(Context ctx, NpcEntity npc, LivingEntity foe, int level, Vec3 hand) {
        String name = foe.getName().getString();
        UUID mageId = npc.getNpcId();
        ArcaneBoltEntity bolt = ArcaneBoltEntity.castAt(ctx.level(), npc, hand, foe, MageBalance.boltDamage(level), landing -> {
            MageSpells.recordBoltLanding(mageId, landing.killed());
            if (landing.killed()) {
                ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " MAGE bolt killed " + name);
            }
        });
        com.alex.test.village.farawake.FarExtras.trackShot(bolt, npc); // flies on past the simulation distance
        ctx.level().sendParticles(ParticleTypes.WITCH, hand.x, hand.y, hand.z, 6, 0.1, 0.1, 0.1, 0.0);
    }

    private static Vec3 at(NpcEntity npc, MageCastTiming.Hand hand) {
        return MageSpells.handAt(npc, hand);
    }

    /** A cast past halfway, a clear view, and the mob not yet right on top of the mage. */
    private boolean finishesCast(NpcEntity npc, LivingEntity foe) {
        return castTicks > 0 && castTicks * 2 <= castTotal
                && npc.distanceToSqr(foe) > POINT_BLANK * POINT_BLANK
                && ArcherCombatRules.hasSight(npc, foe);
    }

    /** The cast is dropped: no spell, nothing paid, and the pose ends now. */
    private void stopCast(Context ctx, String why) {
        NpcEntity npc = ctx.npc();
        if (castTicks > 0) {
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " MAGE dropped " + castSpell.label() + ": " + why);
        }
        castTicks = 0;
        castSpot = null;
        if (npc.getAnimation().isMageCast() && recoverTicks <= 0) {
            npc.setAnimation(VillagerAnimationState.IDLE);
        }
    }

    // =========================================================================
    // Blink
    // =========================================================================

    /**
     * A mob on top of it and no guard or captain to run to: the mage vanishes
     * and reappears {@value MageBalance#BLINK_MIN_DISTANCE}-{@value MageBalance#BLINK_MAX_DISTANCE}
     * blocks away from the mob, on a walkable tile of the village. Long cooldown.
     */
    private boolean tickBlink(Context ctx, LivingEntity foe) {
        NpcEntity npc = ctx.npc();
        int level = level(ctx);
        long now = ctx.level().getGameTime();
        if (!MageBalance.hasBlink(level) || now < timers(ctx).blinkReadyAt) return false;
        if (!MageSettingsBridge.setting(npc, MageSettingsSchema.BLINK)) return false;
        if (npc.distanceToSqr(foe) > MageBalance.BLINK_DANGER_RANGE * MageBalance.BLINK_DANGER_RANGE) return false;
        if (!ArcherCombatRules.meleeDefendersNear(npc, npc.position(), MageBalance.BLINK_NO_GUARD_RADIUS).isEmpty()) {
            return false;
        }
        if (!mana(ctx).canAfford(MageBalance.BLINK_MANA, now, level)) return false;
        Vec3 dest = blinkSpot(ctx, foe);
        if (dest == null || !com.alex.test.village.farawake.FarAwake.mayPlace(ctx.level(), dest.x, dest.z, "mage blink")) {
            // Nowhere safe, or the spot's chunk still loading (FarAwake): not looked for again
            // for a second, and no mana spent.
            timers(ctx).blinkReadyAt = now + 20;
            return false;
        }
        mana(ctx).trySpend(MageBalance.BLINK_MANA, now, level);
        timers(ctx).blinkReadyAt = now + MageBalance.BLINK_COOLDOWN_TICKS;
        cancelFlee(ctx);
        cancelRunToGuard(ctx);
        Vec3 from = npc.position();
        ctx.model().stopMoving();
        npc.getNavigation().stop();
        if (npc.getAnimation().isMageCast()) npc.setAnimation(VillagerAnimationState.IDLE);
        npc.teleportTo(dest.x, dest.y, dest.z);
        MageSpells.blink(ctx.level(), from, dest);
        saveMana(ctx);
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + String.format(
                " MAGE blinked %.0f blocks away from %s", from.distanceTo(dest), foe.getName().getString()));
        return true;
    }

    /**
     * A walkable tile 6-8 blocks from the mage, on the far side from the mob,
     * in plain sight of it, with no other mob next to it: never a doorway,
     * ladder or trapdoor. Straight back first, then fanning out. Where its feet
     * land, on top of a carpet or slab.
     */
    @Nullable
    private static Vec3 blinkSpot(Context ctx, LivingEntity foe) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        NpcEntity npc = ctx.npc();
        Vec3 away = npc.position().subtract(foe.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1.0E-4) away = new Vec3(-npc.getLookAngle().x, 0, -npc.getLookAngle().z);
        if (away.lengthSqr() < 1.0E-4) return null;
        away = away.normalize();
        double mid = (MageBalance.BLINK_MIN_DISTANCE + MageBalance.BLINK_MAX_DISTANCE) / 2.0;
        for (double a : new double[] {0, 0.5, -0.5, 1.0, -1.0, 1.5, -1.5}) {
            double cos = Math.cos(a), sin = Math.sin(a);
            Vec3 dir = new Vec3(away.x * cos - away.z * sin, 0, away.x * sin + away.z * cos);
            NavNode node = graph.nearestRoutable(BlockPos.containing(npc.position().add(dir.scale(mid))), 2, 3,
                    n -> !n.portal && !n.climb && !n.openTrapdoor && !n.waterDrop);
            if (node == null) continue;
            BlockPos feet = node.feetPos();
            double d = Math.sqrt(feet.distToCenterSqr(npc.position()));
            if (d < MageBalance.BLINK_MIN_DISTANCE - 1.0 || d > MageBalance.BLINK_MAX_DISTANCE + 1.5) continue;
            if (feet.distToCenterSqr(foe.position()) <= npc.distanceToSqr(foe)) continue;
            AABB around = new AABB(feet).inflate(DANGER_RANGE);
            if (!ctx.level().getEntitiesOfClass(LivingEntity.class, around, e -> MageSpells.isFoe(npc, e)).isEmpty()) {
                continue;
            }
            Vec3 dest = new Vec3(node.x + 0.5, node.standY, node.z + 0.5);
            // Not through a wall: it sees where it goes.
            Vec3 eye = npc.getEyePosition();
            Vec3 destEye = dest.add(0, npc.getEyeHeight(), 0);
            if (ctx.level().clip(new ClipContext(eye, destEye, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, npc)).getType() != HitResult.Type.MISS) {
                continue;
            }
            return dest;
        }
        return null;
    }

    // =========================================================================
    // Night lights
    // =========================================================================

    /**
     * On night duty, every few seconds, a wisp of light is left just over the
     * mage's head if the spot is dark and inside the village (see
     * {@link MageLights}: plain air only, spaced out, all gone at dawn).
     */
    private void tickNightLights(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (Math.floorMod(ctx.level().getGameTime() + npc.getId(), LIGHT_CHECK_TICKS) != 0) return;
        // Its own clock says night, and the sky agrees: the wisps fade by the sky.
        if (!com.alex.test.village.brain.VillagerBrain.isNight(ctx) || ctx.level().isDay()) return;
        if (!MageSettingsBridge.setting(npc, MageSettingsSchema.NIGHT_LIGHTS)) return;
        BlockPos spot = npc.blockPosition().above(2);
        // Inside the village's own bounds: the village-distance rule passes
        // anywhere a villager stands, and so would let a wisp out into the wild.
        if (!ctx.mgr().contains(spot)) return;
        if (MageLights.get(ctx.level()).tryPlace(ctx.level(), spot)) {
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " MAGE left a night light at " + spot.toShortString());
            // With a Mage Tower near, the light drifts out of its crystal to the spot.
            Vec3 crystal = nearestTowerCrystal(ctx, spot);
            if (crystal != null) MageSpells.wispFlight(ctx.level(), crystal, Vec3.atCenterOf(spot));
        }
    }

    /** A wisp's own Mage Tower crystal sends it no farther than this. */
    private static final double WISP_TOWER_RANGE = 64.0;

    /** The centre of the nearest valid Mage Tower's crystal within {@link #WISP_TOWER_RANGE} of {@code spot}; null if none. */
    @Nullable
    private static Vec3 nearestTowerCrystal(Context ctx, BlockPos spot) {
        Vec3 best = null;
        double bestSq = WISP_TOWER_RANGE * WISP_TOWER_RANGE;
        for (com.alex.test.village.building.Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof com.alex.test.village.building.MageTowerBuilding tower) || !tower.isValid()) continue;
            for (BlockPos crystal : tower.getCrystals()) {
                double d = crystal.distSqr(spot);
                if (d < bestSq) {
                    bestSq = d;
                    best = Vec3.atCenterOf(crystal).add(0, 0.6, 0);
                }
            }
        }
        return best;
    }

    // =========================================================================
    // Reagents
    // =========================================================================

    /** A trip to storage once the pouch runs low; never in a fight. */
    private Status tickAcquire(Context ctx) {
        if (getReagents != null) {
            Status status = getReagents.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            getReagents.stop(ctx);
            getReagents = null;
            if (status == Status.FAILED) nextReagentAttemptAt = ctx.level().getGameTime() + RETRY_COOLDOWN_TICKS;
            return Status.DONE;
        }
        // A novice has no spell that burns them yet, nor one whose spells are all switched off.
        if (!burnsReagents(ctx)) return Status.DONE;
        long now = ctx.level().getGameTime();
        if (now < nextReagentAttemptAt) return Status.DONE;
        if (MageReagents.count(ctx.npc().getInventory()) >= MageReagents.REFILL_LINE) return Status.DONE;
        if (GetToolActivity.canAcquire(ctx, GetReagentsActivity.REAGENT_REQUIREMENT)) {
            cancelPatrol(ctx);
            getReagents = new GetReagentsActivity();
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " MAGE going to storage for reagents");
            return Status.RUNNING;
        }
        nextReagentAttemptAt = now + RETRY_COOLDOWN_TICKS;
        return Status.DONE;
    }

    /** Whether it knows, and may cast, a spell that burns reagents. */
    private static boolean burnsReagents(Context ctx) {
        int level = level(ctx);
        java.util.Set<Spell> off = switchedOff(ctx.npc());
        for (Spell spell : Spell.values()) {
            if (spell.reagents() > 0 && spell.learnt(level) && !off.contains(spell)) return true;
        }
        return false;
    }

    private void cancelAcquire(Context ctx) {
        if (getReagents == null) return;
        getReagents.stop(ctx);
        getReagents = null;
    }

    private static double farApproachSq() {
        double range = MageBalance.CAST_RANGE + FAR_APPROACH_MARGIN;
        return range * range;
    }

    /** Runs to a mob far across the village along the graph, as an archer does. */
    private Status tickFarApproach(Context ctx, LivingEntity foe) {
        NpcEntity npc = ctx.npc();
        double dist = npc.distanceTo(foe);
        npc.hurry(HurryGait.RUN);
        hurrying = true;
        if (dist < approachBest - 1.0D) {
            approachBest = dist;
            approachStallTicks = 0;
        } else if (++approachStallTicks > FAR_APPROACH_STALL_TICKS) {
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + String.format(
                    " MAGE can't get closer to %s (%.0f blocks), leaving it for now",
                    foe.getName().getString(), dist));
            GuardAlertSystem.giveUpUnreachable(ctx, npc.getNpcId(), foe, STANDOFF_IGNORE_TICKS);
            releaseTarget(ctx);
            combatCooldown = COMBAT_END_COOLDOWN_TICKS;
            return Status.RUNNING;
        }
        BlockPos at = foe.blockPosition();
        if (approachMove != null) {
            if (approachMobAt != null && approachMobAt.distSqr(at) <= FAR_APPROACH_REPLAN_SQ) {
                Status status = approachMove.tickOuter(ctx);
                if (status == Status.RUNNING) return Status.RUNNING;
                approachRetryTicks = FAR_APPROACH_RETRY_TICKS;
            }
            approachMove.stop(ctx);
            approachMove = null;
        }
        if (approachRetryTicks > 0) {
            approachRetryTicks--;
            return Status.RUNNING;
        }
        NavGraph graph = ctx.mgr().getGraph();
        NavNode node = graph != null ? graph.nearest(at, 10, 4) : null;
        if (node == null) {
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

    private static float runSpeed(Context ctx) {
        return (float) (VillagerModel.DEFAULT_SPEED
                * GuardBalance.responseSpeed(ctx.brain().defenderSkillLevel()));
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

    /** Runs behind the nearest guard on the far side from {@code foe}; false when there is none. */
    private boolean tickRunToGuard(Context ctx, LivingEntity foe) {
        NpcEntity npc = ctx.npc();
        NpcEntity guard = havenGuard != null && havenGuard.isAlive() ? havenGuard : findHavenGuard(npc, foe);
        if (guard == null) {
            cancelRunToGuard(ctx);
            return false;
        }
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
            fleeActivity = null;
            fleeSnapshot = null;
            havenGoal = goal;
            havenMove = new MoveActivity(goal, KITE_SPEED, true);
        }
        Status status = havenMove.tickOuter(ctx);
        if (status == Status.RUNNING) {
            npc.hurry(HurryGait.RUN);
            hurrying = true;
            return true;
        }
        cancelRunToGuard(ctx);
        return false;
    }

    @Nullable
    private static NpcEntity findHavenGuard(NpcEntity npc, LivingEntity foe) {
        Vec3 toFoe = foe.position().subtract(npc.position());
        NpcEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (NpcEntity guard : ArcherCombatRules.meleeDefendersNear(npc, npc.position(), HAVEN_RADIUS)) {
            Vec3 toGuard = guard.position().subtract(npc.position());
            if (toGuard.dot(toFoe) >= 0) continue;
            double d = toGuard.lengthSqr();
            if (d < bestSq) {
                bestSq = d;
                best = guard;
            }
        }
        return best;
    }

    // =========================================================================
    // Targets
    // =========================================================================

    private void acceptTarget(Context ctx, LivingEntity entity) {
        UUID previous = targetId;
        target = entity;
        targetId = entity.getUUID();
        GuardAlertSystem.claim(ctx, ctx.npc(), entity);
        if (!targetId.equals(previous)) {
            cancelReposition(ctx);
            cancelFarApproach(ctx);
            repathTimer = 0;
            standoffTicks = 0;
            chaseTicks = 0;
        }
    }

    private void releaseTarget(Context ctx) {
        if (targetId != null) GuardAlertSystem.clearTarget(ctx, ctx.npc().getNpcId(), targetId);
        target = null;
        targetId = null;
        stopCast(ctx, "gave the mob up");
        ctx.npc().getNavigation().stop();
        ctx.npc().setTarget(null);
        cancelFlee(ctx);
        cancelReposition(ctx);
        cancelFarApproach(ctx);
        cancelRunToGuard(ctx);
    }

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
        // Any hostile mob that hits it is fought back, whatever its list says.
        return GuardThreatRules.isSelfDefenseTarget(ctx.npc(), attacker, RECENT_ATTACKER_MEMORY_TICKS)
                ? attacker : null;
    }

    /**
     * Only hostile monsters on its settings' list, or one that hit it lately
     * ({@link MageSpells#isFoe}): never a villager, player, pet or golem.
     */
    private static boolean isValidTarget(Context ctx, @Nullable LivingEntity entity) {
        return MageSpells.isFoe(ctx.npc(), entity);
    }

    // =========================================================================
    // Patrol
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

    private void cancelFarApproach(Context ctx) {
        stopHurrying(ctx);
        if (approachMove != null) approachMove.stop(ctx);
        approachMove = null;
        approachMobAt = null;
        approachBest = Double.MAX_VALUE;
        approachStallTicks = 0;
        approachRetryTicks = 0;
    }

    private void cancelRunToGuard(Context ctx) {
        stopHurrying(ctx);
        if (havenMove != null) havenMove.stop(ctx);
        havenMove = null;
        havenGoal = null;
        havenGuard = null;
    }

    private void stopHurrying(Context ctx) {
        if (!hurrying) return;
        hurrying = false;
        ctx.npc().stopHurry();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public boolean isMovingPhase() {
        return target == null && castTicks <= 0;
    }

    /** Fighting right now: a mob to cast at or run from, a spell under way, or running for shelter. */
    public boolean isThreatResponseActive() {
        return target != null && target.isAlive() || castTicks > 0 || retreat.isActive() || fleeActivity != null;
    }

    @Override
    public boolean allowsRoutineInterruption() {
        // A held target locks out the brain's food overrides; a starving mage
        // with food and no mob close may still stop to eat.
        return castTicks <= 0 && !retreat.isRunning() && (target == null || mealBreak);
    }

    /** The guard post this mage stands at, for the shift handover; null while it patrols. */
    @Nullable
    public BlockPos activeWatchPost() {
        return postPatrol.activePost();
    }

    @Override
    protected void doStop(Context ctx) {
        retreat.cancel(ctx);
        cancelAcquire(ctx);
        cancelPatrol(ctx);
        cancelFlee(ctx);
        cancelReposition(ctx);
        cancelFarApproach(ctx);
        cancelRunToGuard(ctx);
        stopCast(ctx, "work stopped");
        if (targetId != null) GuardAlertSystem.clearTarget(ctx, ctx.npc().getNpcId(), targetId);
        target = null;
        targetId = null;
        saveMana(ctx);
        ctx.model().stopMoving();
        ctx.npc().getNavigation().stop();
        ctx.npc().setTarget(null);
    }

    @Override
    public String name() { return "mage"; }
}
