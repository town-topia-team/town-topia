package com.alex.test.village.raid.warlord;

import com.alex.test.ModSounds;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * The Warlord's heavy overhead swing: it walks up to its target, stops, raises
 * the axe (the "swing" clip) and brings it down in front. It turns with its
 * target while the axe goes up, then the blow is set: a quick step aside dodges
 * it. Everyone under the falling blade is hit, not only the one it aimed at
 * ({@link WarlordMath#inSwingZone}), at the moment the blade lands on clients.
 * It stands its ground until the axe is back up. One swing every two seconds,
 * every one and a half when enraged.
 */
public final class WarlordSwingGoal extends Goal {

    private static final int REPATH_TICKS = 10;
    /** It keeps turning with its target this long into the swing (the raise). */
    private static final int TRACK_TICKS = 6;
    private static final float TURN_PER_TICK = 25.0F;
    private static final double FLING_SIDEWAYS = 0.9;
    private static final double FLING_UP = 0.35;
    /** A foe standing a little below it (a step down) is still under the blade. */
    private static final double BELOW = 0.5;
    /** Further than this (squared), or out of sight: chased by village map route (switch raidbig). */
    private static final double MAP_CHASE_SQ = 8.0 * 8.0;

    private final RaidWarlord warlord;
    /** Ticks since the swing was triggered; -1 while not swinging. */
    private int swingTick = -1;
    private int landsAt;
    private int endsAt;
    private float swingYaw;
    private long lastSwing = Long.MIN_VALUE / 2;
    private int repathIn;

    public WarlordSwingGoal(RaidWarlord warlord) {
        this.warlord = warlord;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = warlord.getTarget();
        return target != null && target.isAlive() && warlord.action() == WarlordAction.NONE;
    }

    @Override
    public boolean canContinueToUse() {
        return swingTick >= 0 || canUse();
    }

    /** Not while the axe is coming down: the blow always lands. */
    @Override
    public boolean isInterruptable() {
        return swingTick < 0 || swingTick >= landsAt;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        repathIn = 0;
    }

    @Override
    public void stop() {
        swingTick = -1;
        warlord.wideWalk().drop();
        warlord.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = warlord.getTarget();
        if (swingTick >= 0) {
            swinging(target);
            return;
        }
        if (target == null) return;
        warlord.getLookControl().setLookAt(target, 30.0F, 30.0F);
        long now = warlord.level().getGameTime();
        if (warlord.isWithinMeleeAttackRange(target) && warlord.getSensing().hasLineOfSight(target)) {
            if (now - lastSwing >= WarlordMath.swingIntervalTicks(warlord.isEnraged())) {
                warlord.getNavigation().stop();
                lastSwing = now;
                boolean enraged = warlord.isEnraged();
                swingTick = 0;
                landsAt = WarlordMath.clipTicks(WarlordMath.SWING_WINDUP_TICKS, enraged);
                endsAt = WarlordMath.clipTicks(WarlordMath.SWING_CLIP_TICKS, enraged);
                swingYaw = warlord.yBodyRot;
                warlord.playClip("swing");
                warlord.playSound(ModSounds.RAID_WARLORD_SWING.get(), 1.5F, 1.0F);
            }
        } else {
            // Round 5 (switch raidbig): far off or out of sight, a village map route; close and in
            // sight, Minecraft's path straight at it as before.
            com.alex.test.village.raid.WideMapWalk walk = warlord.wideWalk();
            if (warlord.distanceToSqr(target) > MAP_CHASE_SQ || !warlord.getSensing().hasLineOfSight(target)) {
                if (walk.tick(target.blockPosition(), 1.0, com.alex.test.village.raid.WideMapWalk.Goal.MOVING)) {
                    repathIn = 0;
                    return;
                }
            } else {
                walk.drop();
            }
            if (--repathIn <= 0) {
                repathIn = REPATH_TICKS;
                boolean found = warlord.getNavigation().moveTo(target, 1.0);
                com.alex.test.village.raid.RaidNavCount.note(com.alex.test.village.raid.RaidNavCount.Site.WARLORD_CHASE, warlord, found);
            }
        }
    }

    private void swinging(LivingEntity target) {
        warlord.getNavigation().stop();
        swingTick++;
        if (target != null && target.isAlive() && swingTick <= TRACK_TICKS) {
            float wanted = RaidWarlord.yawToward(warlord.position(), target.position());
            swingYaw = Mth.approachDegrees(swingYaw, wanted, TURN_PER_TICK);
        }
        warlord.face(swingYaw);
        if (swingTick == landsAt) land(target);
        if (swingTick >= endsAt) swingTick = -1;
    }

    /** The blade lands: everyone under it is hit and knocked back. */
    private void land(LivingEntity target) {
        Vec3 forward = RaidWarlord.forward(swingYaw);
        Vec3 right = RaidWarlord.right(swingYaw);
        double reach = WarlordMath.SWING_REACH + 1.0;
        double feet = warlord.getY();
        AABB area = warlord.getBoundingBox().inflate(reach, 1.0, reach);
        for (LivingEntity foe : warlord.level().getEntitiesOfClass(LivingEntity.class, area,
                e -> e != warlord && (e == target || warlord.isFoe(e)))) {
            Vec3 at = foe.position().subtract(warlord.position());
            if (!WarlordMath.inSwingZone(at.dot(forward), at.dot(right), foe.getBbWidth() / 2.0)) continue;
            AABB box = foe.getBoundingBox();
            if (box.minY > feet + WarlordMath.SWING_TOP || box.maxY < feet - BELOW) continue;
            // Not through a wall or a closed door, as vanilla's melee checks too.
            if (!warlord.getSensing().hasLineOfSight(foe)) continue;
            if (warlord.doHurtTarget(foe)) RaidWarlord.fling(foe, forward, FLING_SIDEWAYS, FLING_UP);
        }
    }
}
