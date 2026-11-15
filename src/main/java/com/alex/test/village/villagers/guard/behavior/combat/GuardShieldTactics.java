package com.alex.test.village.villagers.guard.behavior.combat;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.villagers.guard.CombatRank;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.guard.GuardSkillEffects;
import com.alex.test.village.villagers.guard.ShieldStance;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import com.alex.test.village.villagers.guard.settings.GuardMobPolicy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * When a guard or captain raises its shield, and when it lowers it to swing.
 *
 * <p>The old rule held the shield up whenever a shooter was 5-24 blocks off or
 * the guard was pressed, and only swung after 24 ticks behind it -- so a guard
 * crept at a walk the whole way to a skeleton, and in a melee spent most of its
 * time not hitting anything. This reads the fight instead:
 *
 * <ul>
 *   <li><b>Arrows.</b> The shield comes up when a shooter aiming at this guard
 *       is about to loose (a bow drawn nearly full, a crossbow charged) or a
 *       projectile is already flying at it, and goes down once the shot is
 *       past, so the guard runs in between shots.</li>
 *   <li><b>Timed blocks.</b> A melee mob swings every 20 ticks once in reach.
 *       Having been hit by it, the guard knows when the next blow comes; when
 *       blocking is worth it (hurt, crowded, or skilled enough to parry) it
 *       raises the shield so it is up just as the blow lands -- in the parry
 *       window -- and lowers it to swing back the moment the hit is taken.</li>
 *   <li><b>Pressed.</b> Mobs in reach whose timing it does not know yet, two
 *       or more of them or while hurt: shield up, then swing after a hold or
 *       straight after a blocked hit.</li>
 *   <li><b>Axes.</b> A shield cannot stop one, so instead of blocking the
 *       guard steps out of reach just before the blow.</li>
 * </ul>
 */
public final class GuardShieldTactics {

    public enum Plan { NONE, ADVANCE, TIMED, PRESSED, DODGE, EVADE }

    /**
     * Distance a guard backs off to from a lit creeper. A creeper's blast
     * (power 3) reaches six blocks, and its fuse only goes out once its target
     * is more than seven away.
     */
    public static final double CREEPER_SAFE_DIST = 7.5D;
    /**
     * A creeper whose fuse is past this share is left alone: started any later,
     * a swing would land just before the blast with the guard still beside it.
     * Earlier, the guard gets one blow in -- then backs off.
     */
    public static final float CREEPER_COMMIT_SWELL = 0.2f;
    /** Shield up towards a creeper this close to blowing, while backing off. */
    private static final float CREEPER_BRACE_SWELL = 0.7f;
    private static final double CREEPER_SCAN_RADIUS = 8.0D;

    /**
     * What the stance should do this tick.
     *
     * @param raise    shield wanted up
     * @param urgent   change it now, without the usual dwell between changes
     * @param faceAt   where to turn so the shield (which covers the front) meets the blow; null to keep facing the target
     * @param maySwing whether a swing may start this tick
     * @param threat   the mob the plan is about (the shooter, the axe), or null
     */
    public record Call(Plan plan, boolean raise, boolean urgent, @Nullable Vec3 faceAt,
                       boolean maySwing, @Nullable LivingEntity threat) {
        static final Call NONE = new Call(Plan.NONE, false, false, null, true, null);
    }

    /** A melee mob that has reach swings this often (vanilla MeleeAttackGoal). */
    private static final int MELEE_CADENCE_TICKS = 20;
    private static final double MELEE_SCAN_RADIUS = 4.0D;
    private static final double SHOOTER_SCAN_RADIUS = 24.0D;
    private static final int SCAN_TICKS = 5;
    private static final double PROJECTILE_SCAN_RADIUS = 12.0D;
    /** A projectile passing within this of the guard's middle counts as coming at it. */
    private static final double PROJECTILE_HIT_RADIUS = 1.1D;
    /** Raise for a projectile arriving within this many ticks. */
    private static final int PROJECTILE_WARN_EXTRA_TICKS = 6;
    /**
     * Raise for a drawn bow when its arrow is due within this many ticks, so it
     * lands while the shield clip is between 0.25 and 0.6 s
     * ({@link GuardBalance#SHOT_HIT_EARLIEST_TICKS} to
     * {@link GuardBalance#SHOT_HIT_LATEST_TICKS}: 7 to 14) -- the shield settled
     * in front and taking hits. Aimed at the early end: a real arrow is slowed by
     * gravity and drag and tends to arrive a tick after the straight distance /
     * speed estimate, which still lands inside the window, and a tick early
     * still meets a shield that is up (from tick 4).
     */
    private static final int SHOT_RAISE_LEAD_TICKS = GuardBalance.SHOT_HIT_EARLIEST_TICKS;
    /** A skeleton's arrow speed (Skeleton#performRangedAttack: 1.6). */
    private static final double ARROW_BLOCKS_PER_TICK = 1.6;
    private static final int BOW_FULL_DRAW_TICKS = 20;
    /** Shooters closer than this (to their hitbox) are fought, not blocked. */
    private static final double SHOOTER_MIN_DIST = 3.0D;
    /** Old fallback for shooters that give no warning (witches): shield up between these. */
    private static final double UNTELEGRAPHED_MIN = GuardBalance.SHIELD_ADVANCE_MIN;
    private static final double UNTELEGRAPHED_MAX = GuardBalance.SHIELD_ADVANCE_MAX;
    /** A timed block raised this long past the arm coming up, without a hit, was mistimed: drop it. */
    private static final int TIMED_GIVE_UP_TICKS = 8;
    private static final int TIMED_SLACK_TICKS = 2;
    /** Blocking is worth a swing below this share of health. */
    private static final float BLOCK_WORTH_HEALTH = 0.85f;
    /** Step away from an axe this many ticks before its blow. */
    private static final int DODGE_WARN_TICKS = 5;

    private final Map<Integer, Integer> lastHitByAttacker = new HashMap<>();
    private int lastSeenHurtStamp = Integer.MIN_VALUE;
    private final List<Mob> meleeNear = new ArrayList<>();
    private final List<Mob> shootersNear = new ArrayList<>();
    private final List<Creeper> creepersNear = new ArrayList<>();
    private long nextScanAt;
    private Plan raisedFor = Plan.NONE;

    /** Forgets the fight; call when the guard stops fighting. */
    public void reset() {
        lastHitByAttacker.clear();
        meleeNear.clear();
        shootersNear.clear();
        creepersNear.clear();
        nextScanAt = 0L;
        raisedFor = Plan.NONE;
    }

    /**
     * Decides the stance for this tick. Only called while the guard is free --
     * not mid-swing -- and fighting {@code foe}.
     *
     * @param strikeLandTicks ticks from starting a swing to its blow landing
     */
    public Call decide(Activity.Context ctx, CombatRank rank, int skill, LivingEntity foe, int strikeLandTicks) {
        NpcEntity npc = ctx.npc();
        long now = ctx.level().getGameTime();
        ShieldStance stance = npc.shieldStance();
        observeHits(npc);
        if (now >= nextScanAt) {
            nextScanAt = now + SCAN_TICKS;
            scan(ctx, foe);
        }

        boolean shieldable = rank.hasShieldBlock(skill) && !stance.isBroken(now)
                && GuardSkillEffects.findShieldSlot(npc.getInventory()) >= 0;

        // A creeper about to blow: back off out of the blast, bracing behind the
        // shield at the end if there is one.
        Creeper fuse = litCreeper(npc);
        if (fuse != null) {
            float max = rank.guardMeter(skill);
            boolean brace = shieldable && fuse.getSwelling(1.0F) >= CREEPER_BRACE_SWELL
                    && npc.distanceToSqr(fuse) < CREEPER_SAFE_DIST * CREEPER_SAFE_DIST
                    && (stance.isRaised() || stance.meter(max) >= max * GuardBalance.METER_MIN_TO_RAISE);
            raisedFor = brace ? Plan.EVADE : Plan.NONE;
            return new Call(Plan.EVADE, brace, true, fuse.getEyePosition(), false, fuse);
        }

        // An axe goes through a shield: step out of its reach just before it falls.
        Mob axe = axeAboutToFall(npc, strikeLandTicks);
        if (axe != null) {
            raisedFor = Plan.NONE;
            return new Call(Plan.DODGE, false, true, null, false, axe);
        }
        if (!shieldable) {
            raisedFor = Plan.NONE;
            return Call.NONE;
        }
        float max = rank.guardMeter(skill);
        boolean meterToRaise = stance.meter(max) >= max * GuardBalance.METER_MIN_TO_RAISE;
        // No raise takes hits before the arm is up in the animation.
        int armUpTicks = GuardBalance.SHIELD_UP_TICKS;

        // A shot blocked stops whole without touching the meter (GuardSkillEffects),
        // so a low meter never holds the shield back for one. A projectile seen
        // only in flight -- from anyone, not only a shooter that has picked this
        // guard -- is raised for at once; it is usually too close to be met by a
        // shield that is up, but the guard is seen trying.
        Projectile shot = incomingProjectile(ctx);
        if (shot != null) {
            Entity owner = shot.getOwner();
            Vec3 from = owner != null ? owner.getEyePosition() : shot.position();
            return raise(Plan.ADVANCE, true, from, owner instanceof LivingEntity living ? living : null);
        }

        // A shooter about to loose at this guard.
        Mob shooter = shooterAboutToLoose(npc);
        if (shooter != null) {
            return raise(Plan.ADVANCE, true, shooter.getEyePosition(), shooter);
        }

        // A timed block in progress: hold until the blow lands, then swing back.
        if (raisedFor == Plan.TIMED && stance.isRaised()) {
            if (stance.blockedSinceRaised()
                    || now - stance.raisedAt() > armUpTicks + TIMED_GIVE_UP_TICKS) {
                raisedFor = Plan.NONE;
                return new Call(Plan.NONE, false, true, null, true, null);
            }
            return new Call(Plan.TIMED, true, true, null, false, null);
        }

        float health = npc.getHealth();
        float maxHealth = npc.getMaxHealth();
        int inReach = 0;
        Mob soonest = null;
        int soonestHitIn = Integer.MAX_VALUE;
        for (Mob mob : meleeNear) {
            if (!mob.isAlive() || !mob.isWithinMeleeAttackRange(npc)) continue;
            inReach++;
            int hitIn = knownHitIn(npc, mob);
            if (hitIn >= 0 && hitIn < soonestHitIn) {
                soonest = mob;
                soonestHitIn = hitIn;
            }
        }

        boolean blockWorth = rank.canParry(skill) || health < maxHealth * BLOCK_WORTH_HEALTH || inReach >= 2;
        if (soonest != null && blockWorth && meterToRaise) {
            if (soonestHitIn >= armUpTicks - 1 && soonestHitIn <= armUpTicks + TIMED_SLACK_TICKS) {
                return raise(Plan.TIMED, true, soonest.getEyePosition(), soonest);
            }
            if (soonestHitIn > armUpTicks + TIMED_SLACK_TICKS && soonestHitIn < strikeLandTicks) {
                // Our blow would land after theirs: wait the few ticks and block it instead.
                lowerPlan();
                return new Call(Plan.NONE, false, false, soonest.getEyePosition(), false, soonest);
            }
        }

        boolean pressed = inReach >= GuardBalance.GUARD_UP_HOSTILES
                || (inReach >= 1 && health < maxHealth * GuardBalance.GUARD_UP_HEALTH_FRACTION);
        if (pressed && (raisedFor == Plan.PRESSED || meterToRaise)) {
            boolean swing = stance.heldUpFor(now) >= GuardBalance.RAISED_HOLD_TICKS
                    || stance.blockedSinceRaised();
            if (swing) {
                raisedFor = Plan.NONE;
                return new Call(Plan.PRESSED, false, true, null, true, null);
            }
            raisedFor = Plan.PRESSED;
            return new Call(Plan.PRESSED, true, false, null, false, null);
        }

        lowerPlan();
        return Call.NONE;
    }

    /**
     * Whether a shooter's arrow is due at this guard soon enough that the shield
     * must go up now to meet it (see {@link #SHOT_RAISE_LEAD_TICKS}). Checked
     * during a swing, which otherwise runs to its end, so the guard can drop the
     * swing and block.
     */
    public boolean shotImminent(Activity.Context ctx, CombatRank rank, int skill) {
        NpcEntity npc = ctx.npc();
        long now = ctx.level().getGameTime();
        if (!rank.hasShieldBlock(skill) || npc.shieldStance().isBroken(now)
                || GuardSkillEffects.findShieldSlot(npc.getInventory()) < 0) {
            return false;
        }
        // Only a shot still to come is worth dropping a swing for: one already in
        // flight lands before a shield raised now is up.
        return shooterAboutToLoose(npc) != null;
    }

    private Call raise(Plan plan, boolean urgent, @Nullable Vec3 faceAt, @Nullable LivingEntity threat) {
        raisedFor = plan;
        return new Call(plan, true, urgent, faceAt, false, threat);
    }

    private void lowerPlan() {
        raisedFor = Plan.NONE;
    }

    // =========================================================================
    // Reading the fight
    // =========================================================================

    /** Notes who hit this guard and when, for predicting their next blow. */
    private void observeHits(NpcEntity npc) {
        LivingEntity by = npc.getLastHurtByMob();
        int stamp = npc.getLastHurtByMobTimestamp();
        if (by != null && stamp != lastSeenHurtStamp) {
            lastSeenHurtStamp = stamp;
            lastHitByAttacker.put(by.getId(), stamp);
        }
        if (lastHitByAttacker.size() > 16) {
            lastHitByAttacker.values().removeIf(t -> npc.tickCount - t > MELEE_CADENCE_TICKS * 5);
        }
    }

    /** Ticks until {@code mob}'s next blow, when it hit this guard less than a cadence ago; else -1. */
    private int knownHitIn(NpcEntity npc, Mob mob) {
        Integer last = lastHitByAttacker.get(mob.getId());
        if (last == null) return -1;
        int since = npc.tickCount - last;
        return since < MELEE_CADENCE_TICKS ? MELEE_CADENCE_TICKS - since : -1;
    }

    private void scan(Activity.Context ctx, LivingEntity foe) {
        NpcEntity npc = ctx.npc();
        meleeNear.clear();
        shootersNear.clear();
        creepersNear.clear();
        for (Mob mob : ctx.level().getEntitiesOfClass(Mob.class,
                npc.getBoundingBox().inflate(SHOOTER_SCAN_RADIUS, 4.0D, SHOOTER_SCAN_RADIUS))) {
            if (!mob.isAlive()) continue;
            // Any creeper close by matters, whoever lit it: the blast does not pick.
            if (mob instanceof Creeper creeper) {
                if (npc.distanceToSqr(creeper) <= CREEPER_SCAN_RADIUS * CREEPER_SCAN_RADIUS) {
                    creepersNear.add(creeper);
                }
                continue;
            }
            if (mob.getTarget() != npc && mob != foe) continue;
            // Mobs off the guard's list still count once they are after it.
            if (!GuardThreatRules.isHostileMonsterForGuard(npc, mob)
                    && !(mob.getTarget() == npc && GuardMobPolicy.isHostileCreature(mob))) {
                continue;
            }
            if (isShooter(mob)) {
                if (mob.getTarget() == npc) shootersNear.add(mob);
            } else if (mob.getTarget() == npc && npc.distanceToSqr(mob) <= MELEE_SCAN_RADIUS * MELEE_SCAN_RADIUS) {
                meleeNear.add(mob);
            }
        }
    }

    /** A mob that fights from range right now: a bow, crossbow or trident in hand, or a witch-like thrower. */
    public static boolean isShooter(LivingEntity entity) {
        if (!(entity instanceof RangedAttackMob) || !(entity instanceof Mob mob)) return false;
        if (holdsRangedWeapon(mob.getMainHandItem()) || holdsRangedWeapon(mob.getOffhandItem())) return true;
        // Skeletons, illagers and drowned without their ranged weapon fight hand to hand.
        return !(mob instanceof AbstractSkeleton || mob instanceof AbstractIllager || mob instanceof Drowned);
    }

    private static boolean holdsRangedWeapon(ItemStack stack) {
        return stack.getItem() instanceof ProjectileWeaponItem || stack.getItem() instanceof TridentItem;
    }

    @Nullable
    private Mob shooterAboutToLoose(NpcEntity npc) {
        Mob best = null;
        double bestDist = Double.MAX_VALUE;
        for (Mob shooter : shootersNear) {
            if (!shooter.isAlive()) continue;
            double dist = GuardCombatRules.edgeDistance(npc, shooter);
            if (dist < SHOOTER_MIN_DIST || dist > SHOOTER_SCAN_RADIUS) continue;
            if (!isAboutToLoose(shooter, dist)) continue;
            if (!shooter.hasLineOfSight(npc)) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = shooter;
            }
        }
        return best;
    }

    private boolean isAboutToLoose(Mob shooter, double dist) {
        ItemStack using = shooter.getUseItem();
        if (shooter.isUsingItem() && using.getItem() instanceof BowItem) {
            // When the arrow will land: the rest of the draw, then its flight.
            int impactIn = Math.max(0, BOW_FULL_DRAW_TICKS - shooter.getTicksUsingItem())
                    + (int) Math.ceil(dist / ARROW_BLOCKS_PER_TICK);
            return impactIn <= SHOT_RAISE_LEAD_TICKS;
        }
        ItemStack main = shooter.getMainHandItem();
        if (main.getItem() instanceof CrossbowItem) return CrossbowItem.isCharged(main);
        if (main.getItem() instanceof BowItem) return false; // bow lowered between shots
        // No tell to read (witches, tridents): the old rule, shield up while closing in.
        return dist >= UNTELEGRAPHED_MIN && dist <= UNTELEGRAPHED_MAX;
    }

    /** A hostile projectile that will reach this guard within {@link #PROJECTILE_WARN_EXTRA_TICKS}. */
    @Nullable
    private static Projectile incomingProjectile(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        Vec3 centre = npc.getBoundingBox().getCenter();
        int warn = PROJECTILE_WARN_EXTRA_TICKS;
        for (Projectile shot : ctx.level().getEntitiesOfClass(Projectile.class,
                npc.getBoundingBox().inflate(PROJECTILE_SCAN_RADIUS))) {
            if (!shot.isAlive()) continue;
            Entity owner = shot.getOwner();
            // Any hostile's shot, on the guard's mob list or not: it is still coming at the guard.
            if (owner == null || !GuardMobPolicy.isHostileCreature(owner)) continue;
            Vec3 velocity = shot.getDeltaMovement();
            double speed = velocity.length();
            if (speed < 0.2D) continue; // stuck in the ground or spent
            Vec3 dir = velocity.scale(1.0D / speed);
            Vec3 to = centre.subtract(shot.position());
            double along = to.dot(dir);
            if (along <= 0.0D) continue;
            if (to.subtract(dir.scale(along)).lengthSqr() > PROJECTILE_HIT_RADIUS * PROJECTILE_HIT_RADIUS) continue;
            if (along / speed <= warn) return shot;
        }
        return null;
    }

    /** The nearest creeper with its fuse past the point of trading a blow, or null. */
    @Nullable
    private Creeper litCreeper(NpcEntity npc) {
        Creeper best = null;
        double bestDist = Double.MAX_VALUE;
        for (Creeper creeper : creepersNear) {
            if (!creeper.isAlive() || creeper.getSwellDir() <= 0) continue;
            if (creeper.getSwelling(1.0F) < CREEPER_COMMIT_SWELL) continue;
            double dist = npc.distanceToSqr(creeper);
            if (dist < bestDist) {
                bestDist = dist;
                best = creeper;
            }
        }
        return best;
    }

    /** An axe-wielder in reach whose blow is due before this guard's own would land. */
    @Nullable
    private Mob axeAboutToFall(NpcEntity npc, int strikeLandTicks) {
        for (Mob mob : meleeNear) {
            if (!mob.isAlive() || !(mob.getMainHandItem().getItem() instanceof AxeItem)) continue;
            if (!mob.isWithinMeleeAttackRange(npc)) continue;
            int hitIn = knownHitIn(npc, mob);
            if (hitIn >= 0 && hitIn <= DODGE_WARN_TICKS && hitIn < strikeLandTicks) return mob;
        }
        return null;
    }
}
