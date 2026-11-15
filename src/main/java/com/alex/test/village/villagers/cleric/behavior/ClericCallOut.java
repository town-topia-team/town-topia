package com.alex.test.village.villagers.cleric.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.villagers.cleric.settings.ClericSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.registry.VillagerRegistry;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The field medic (10-08 rework): when the cleric stays out in a fight
 * instead of running indoors like every other villager.
 *
 * <p>It stays out while all three hold: Healing in Fights is on (and it is
 * working at all), it has more than half its health, and there is an
 * emergency: the bell has rung, or a guard or archer near it is fighting.
 * Hurt below half, it hides as before; once hiding, it comes back out at
 * three quarters, so health bobbing around the line does not send it in and
 * out every few seconds.
 *
 * <p>An emergency is remembered for {@link #QUIET_TICKS} after the last sign
 * of one: a fight moves about, and the guards drop out of "fighting" for a
 * moment between mobs. Without the memory the cleric flipped between its
 * duty and its evening each time.
 */
public final class ClericCallOut {

    /** A guard or archer fighting this far from the cleric calls it out. */
    static final double FIGHT_SEARCH = 48.0;
    /** Stays out above this share of its health... */
    private static final float STAND_SHARE = 0.5f;
    /** ...and, once hiding, comes back out above this share. */
    private static final float RETURN_SHARE = 0.75f;
    /** The quiet after a fight before the cleric stands down: 30 seconds. */
    static final long QUIET_TICKS = 20L * 30L;
    /** How often each cleric looks again for a fight near it. */
    private static final long FIGHT_CHECK_TICKS = 20L;

    private record Seen(long checkedAt, long lastEmergency) {}

    private static final Map<UUID, Seen> SEEN = new HashMap<>();

    private ClericCallOut() {}

    /** A working cleric with Healing in Fights on. */
    public static boolean fieldMedic(NpcEntity cleric) {
        if (cleric.getProfession() != Profession.CLERIC) return false;
        ProfessionSettings settings = ClericCare.settings(cleric);
        return settings.getBool(ClericSettingsSchema.WORK_ENABLED)
                && settings.getBool(ClericSettingsSchema.HEAL_IN_FIGHTS);
    }

    /** Fit to stay out: above half health, or above three quarters to come back out of hiding. */
    public static boolean healthy(LivingEntity cleric, boolean hiding) {
        float share = cleric.getHealth() / Math.max(1f, cleric.getMaxHealth());
        return share > (hiding ? RETURN_SHARE : STAND_SHARE);
    }

    /**
     * Brain hook: this cleric stays out, neither fleeing from monsters it sees
     * nor going indoors for the bell, because it is needed out there.
     *
     * @param hiding it is fleeing or sheltering now
     */
    public static boolean staysOut(Activity.Context ctx, boolean hiding) {
        NpcEntity cleric = ctx.npc();
        return fieldMedic(cleric) && healthy(cleric, hiding)
                && !cornered(cleric, ctx.level().getGameTime()) && emergency(ctx);
    }

    /** The bell has rung, or guards are fighting near, now or in the last half minute. */
    public static boolean emergency(Activity.Context ctx) {
        UUID id = ctx.npc().getUUID();
        long now = ctx.level().getGameTime();
        Seen seen = SEEN.get(id);
        if (seen == null || now - seen.checkedAt() >= FIGHT_CHECK_TICKS || now < seen.checkedAt()) {
            long last = seen == null ? Long.MIN_VALUE : seen.lastEmergency();
            // A fight near counts in its working hours only: night-shift guards
            // fight a zombie or two most nights, and it would never sleep. At
            // night the bell, a raid, or a badly hurt guard (nightCallReason) get it up.
            if (GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                    || ctx.mgr().raids().inProgress()
                    || (VillagerRegistry.shouldWorkNow(Profession.CLERIC, ctx) && nearestFight(ctx) != null)) {
                last = now;
            }
            seen = new Seen(now, last);
            SEEN.put(id, seen);
            if (SEEN.size() > 128) SEEN.values().removeIf(s -> now - s.checkedAt() > QUIET_TICKS * 4);
        }
        return seen.lastEmergency() != Long.MIN_VALUE && now - seen.lastEmergency() <= QUIET_TICKS
                && seen.lastEmergency() <= now;
    }

    // =========================================================================
    // On call at night
    // =========================================================================

    /** A badly hurt guard or archer this close gets a cleric out of bed. */
    static final double NIGHT_CALL_RADIUS = 32.0;
    /** Badly hurt: at or below this share of its health. */
    private static final float NIGHT_CALL_SHARE = 0.5f;
    /** After a night call, the next one waits this long, so it cannot be got up over and over. */
    private static final long NIGHT_CALL_REST_TICKS = 20L * 60L;
    /** After a night call that healed nobody (the guard out of reach, or another cleric on it). */
    private static final long NIGHT_CALL_WASTED_REST_TICKS = 20L * 60L * 5L;
    private static final Map<UUID, Long> NIGHT_CALL_REST = new HashMap<>();

    /**
     * Why this cleric, off duty (evening or in bed), should get up and help
     * now, or null: a guard or archer near it awake and down to half its
     * health or less. Bell and raid are not here: they are an
     * {@link #emergency} and call it out through {@link #staysOut}.
     */
    @Nullable
    public static String nightCallReason(Activity.Context ctx) {
        NpcEntity cleric = ctx.npc();
        ProfessionSettings settings = ClericCare.settings(cleric);
        if (!settings.getBool(ClericSettingsSchema.WORK_ENABLED) || !healthy(cleric, false)) return null;
        long now = ctx.level().getGameTime();
        Long rest = NIGHT_CALL_REST.get(cleric.getUUID());
        if (rest != null && rest > now && rest - now <= NIGHT_CALL_WASTED_REST_TICKS) return null;
        int startAt = ClericCare.startHealing(ctx.npc(), settings);
        int level = ctx.brain().clericSkillLevel();
        // Without Healing in Fights it only gets up for a guard out of the fight.
        boolean intoFights = settings.getBool(ClericSettingsSchema.HEAL_IN_FIGHTS);
        double r2 = NIGHT_CALL_RADIUS * NIGHT_CALL_RADIUS;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isSleeping() || ClericCare.isPreview(npc)
                    || !npc.getProfession().isDefender() || npc.distanceToSqr(cleric) > r2) {
                continue;
            }
            if (npc.getHealth() > npc.getMaxHealth() * NIGHT_CALL_SHARE) continue;
            if (!ClericCare.needsHealing(ctx, npc, startAt, level) || ClericCare.isUnreachable(cleric, npc, now)
                    // Another cleric is already healing it: it was got up for nothing.
                    || ClericCare.claimedByOther(cleric, npc, now)) {
                continue;
            }
            if (!intoFights && ClericCare.dangerNear(ctx.level(), npc)) continue;
            return ClericCare.nameOf(npc) + " is badly hurt";
        }
        return null;
    }

    /**
     * A night call is over: the next waits {@link #NIGHT_CALL_REST_TICKS}, or
     * {@link #NIGHT_CALL_WASTED_REST_TICKS} when it healed nobody. A guard it
     * could not reach is only left alone for a minute or less, so with the
     * short rest it got up for the same guard every minute and a half all night.
     */
    static void nightCallEnded(NpcEntity cleric, long now, boolean healedSomeone) {
        NIGHT_CALL_REST.put(cleric.getUUID(),
                now + (healedSomeone ? NIGHT_CALL_REST_TICKS : NIGHT_CALL_WASTED_REST_TICKS));
        if (NIGHT_CALL_REST.size() > 128) NIGHT_CALL_REST.values().removeIf(until -> until < now);
    }

    // =========================================================================
    // Cornered
    // =========================================================================

    /** Cornered: this long as an ordinary villager, running for shelter, before it is brave again. */
    private static final long CORNERED_TICKS = 20L * 5L;
    private static final Map<UUID, Long> CORNERED_UNTIL = new HashMap<>();

    /**
     * Nowhere to back away to with a monster on it: it stops being the field
     * medic for a few seconds, so the brain's usual flee to shelter takes over.
     * It used to run to the nearest guard, usually the one fighting that monster.
     */
    static void markCornered(NpcEntity cleric, long now) {
        CORNERED_UNTIL.put(cleric.getUUID(), now + CORNERED_TICKS);
        if (CORNERED_UNTIL.size() > 128) CORNERED_UNTIL.values().removeIf(until -> until < now);
    }

    private static boolean cornered(NpcEntity cleric, long now) {
        Long until = CORNERED_UNTIL.get(cleric.getUUID());
        return until != null && until > now && until - now <= CORNERED_TICKS;
    }

    /** Out on a night call: goes into fights for guards if Healing in Fights is on, it is fit, and not cornered. */
    public static boolean braveOnNightCall(NpcEntity cleric, long now) {
        return fieldMedic(cleric) && healthy(cleric, false) && !cornered(cleric, now);
    }

    /** The guard or archer in a fight nearest to the cleric, or null. */
    @Nullable
    static NpcEntity nearestFight(Activity.Context ctx) {
        NpcEntity cleric = ctx.npc();
        NpcEntity best = null;
        double bestDist = FIGHT_SEARCH * FIGHT_SEARCH;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity npc = model.puppet();
            // Not one asleep: a guard in bed with a zombie outside the wall is not fighting.
            if (npc == null || !npc.isAlive() || npc.isSleeping() || ClericCare.isPreview(npc)
                    || !npc.getProfession().isDefender()) {
                continue;
            }
            double dist = npc.distanceToSqr(cleric);
            if (dist > bestDist) continue;
            if (nearestMonster(ctx.level(), npc, ClericCare.DANGER_RADIUS) == null) continue;
            best = npc;
            bestDist = dist;
        }
        return best;
    }

    /** The awake guard or archer nearest the cleric, for standing by during an alarm with no fight in sight. */
    @Nullable
    static NpcEntity nearestDefender(Activity.Context ctx) {
        NpcEntity cleric = ctx.npc();
        NpcEntity best = null;
        double bestDist = FIGHT_SEARCH * FIGHT_SEARCH;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isSleeping() || ClericCare.isPreview(npc)
                    || !npc.getProfession().isDefender()) {
                continue;
            }
            double dist = npc.distanceToSqr(cleric);
            if (dist < bestDist) {
                best = npc;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * The monster nearest to {@code who} within {@code radius} that {@code who}
     * can see, or null. Only seen ones: a zombie in a cave under the guard's
     * feet, or behind a wall, made a "fight" that put the cleric on stand-by
     * all day and kept it backing away from the ground under it.
     */
    @Nullable
    static Mob nearestMonster(ServerLevel level, LivingEntity who, double radius) {
        AABB box = who.getBoundingBox().inflate(radius, 4.0, radius);
        Mob best = null;
        double bestDist = radius * radius;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> m instanceof Enemy && m.isAlive())) {
            double dist = mob.distanceToSqr(who);
            // Sight last: it is a ray through the world, the rest is arithmetic.
            if (dist <= bestDist && who.hasLineOfSight(mob)) {
                best = mob;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * A spot about {@code dist} blocks from {@code anchor} on the side away
     * from {@code threat} (or toward {@code from} when there is no threat),
     * with ground to stand on and room for a villager. Turns up to 90 degrees
     * either way when straight back is a wall or a drop; null when nothing fits.
     */
    @Nullable
    static BlockPos standoffSpot(ServerLevel level, LivingEntity anchor, @Nullable LivingEntity threat,
                                 LivingEntity from, double dist) {
        Vec3 away = threat != null
                ? anchor.position().subtract(threat.position())
                : from.position().subtract(anchor.position());
        away = new Vec3(away.x, 0, away.z);
        if (away.lengthSqr() < 1.0E-4) away = new Vec3(1, 0, 0);
        away = away.normalize();
        double base = Math.atan2(away.z, away.x);
        int[] turns = {0, 30, -30, 60, -60, 90, -90};
        for (int turn : turns) {
            double angle = base + Math.toRadians(turn);
            double x = anchor.getX() + Math.cos(angle) * dist;
            double z = anchor.getZ() + Math.sin(angle) * dist;
            BlockPos ground = standable(level, BlockPos.containing(x, anchor.getY(), z));
            if (ground == null) continue;
            if (threat != null && threat.distanceToSqr(Vec3.atBottomCenterOf(ground))
                    < STANDOFF_KEEP_AWAY_SQ) {
                continue;
            }
            return ground;
        }
        return null;
    }

    /** Monsters kept at least this far (squared) from a standoff spot. */
    private static final double STANDOFF_KEEP_AWAY_SQ = 6.0 * 6.0;

    /** A block a villager can stand in near {@code around}, up to three above or four below; null if none. */
    @Nullable
    private static BlockPos standable(ServerLevel level, BlockPos around) {
        for (int dy = 3; dy >= -4; dy--) {
            BlockPos feet = around.offset(0, dy, 0);
            BlockPos below = feet.below();
            BlockState floor = level.getBlockState(below);
            if (!floor.isFaceSturdy(level, below, net.minecraft.core.Direction.UP)) continue;
            if (!roomFor(level, feet) || !roomFor(level, feet.above())) continue;
            return feet;
        }
        return null;
    }

    private static boolean roomFor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /** Squared horizontal distance helper for the standoff checks. */
    static double flatDistSqr(LivingEntity a, BlockPos b) {
        return Mth.square(a.getX() - (b.getX() + 0.5)) + Mth.square(a.getZ() - (b.getZ() + 0.5));
    }
}
