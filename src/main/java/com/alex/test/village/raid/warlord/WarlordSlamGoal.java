package com.alex.test.village.raid.warlord;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * The ground slam: when three or more foes crowd the Warlord it stops, raises
 * the axe (the "slam" clip) and drives it into the ground as the clip does
 * ({@link WarlordMath#SLAM_WINDUP_TICKS} into it). Everyone within
 * {@link WarlordMath#SLAM_RADIUS} is hurt and thrown back and up. It stands its
 * ground until the axe is back up. At most once every 10 seconds (6 enraged).
 * Dust only: no block changes.
 */
public final class WarlordSlamGoal extends Goal {

    private static final double FLING_SIDEWAYS = 1.5;
    private static final double FLING_UP = 0.6;

    private final RaidWarlord warlord;
    private long lastSlam = Long.MIN_VALUE / 2;
    /** Ticks since the slam was triggered; -1 while not slamming. */
    private int slamTick = -1;
    private int landsAt;
    private int endsAt;

    public WarlordSlamGoal(RaidWarlord warlord) {
        this.warlord = warlord;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (warlord.action() != WarlordAction.NONE || !warlord.onGround()) return false;
        long since = warlord.level().getGameTime() - lastSlam;
        return WarlordMath.wantsSlam(foesInReach().size(), since, warlord.isEnraged());
    }

    @Override
    public boolean canContinueToUse() {
        return slamTick >= 0 && warlord.isAlive();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        lastSlam = warlord.level().getGameTime();
        boolean enraged = warlord.isEnraged();
        slamTick = 0;
        landsAt = WarlordMath.clipTicks(WarlordMath.SLAM_WINDUP_TICKS, enraged);
        endsAt = WarlordMath.clipTicks(WarlordMath.SLAM_CLIP_TICKS, enraged);
        warlord.getNavigation().stop();
        warlord.playClip("slam");
    }

    @Override
    public void stop() {
        slamTick = -1;
    }

    @Override
    public void tick() {
        warlord.getNavigation().stop();
        slamTick++;
        if (slamTick >= endsAt) slamTick = -1;
        if (slamTick != landsAt || !(warlord.level() instanceof ServerLevel level)) return;
        WarlordEffects.slam(level, warlord);
        double damage = WarlordMath.slamDamage(warlord.omenLevel());
        for (LivingEntity foe : foesInReach()) {
            warlord.strike(foe, damage);
            RaidWarlord.fling(foe, foe.position().subtract(warlord.position()), FLING_SIDEWAYS, FLING_UP);
        }
    }

    /**
     * Foes standing within the slam's reach, counted flat (a foe up on a step still
     * counts), and only ones it can see: villagers hiding behind a wall or a door
     * are neither counted nor hit.
     */
    private List<LivingEntity> foesInReach() {
        double reach = WarlordMath.SLAM_RADIUS;
        Vec3 at = warlord.position();
        return warlord.level().getEntitiesOfClass(LivingEntity.class, warlord.getBoundingBox().inflate(reach, 1.0, reach),
                foe -> warlord.isFoe(foe) && horizontalDistanceSqr(foe.position(), at) <= reach * reach
                        && warlord.getSensing().hasLineOfSight(foe));
    }

    static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }
}
