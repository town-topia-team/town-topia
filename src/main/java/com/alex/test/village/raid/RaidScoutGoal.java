package com.alex.test.village.raid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * The warband's scout during the warning day: it keeps to its post outside
 * the village and watches it. Once it has someone to fight (it was attacked,
 * or a villager came close) it fights like any pillager, then goes back.
 */
final class RaidScoutGoal extends Goal {

    static final int PRIORITY = 4;
    /** Within this of its post it stands and watches. */
    private static final double AT_POST_SQ = 3.0 * 3.0;
    private static final int REPATH_TICKS = 20;

    private final PathfinderMob scout;
    private final BlockPos post;
    private final BlockPos watch;
    private int repath;
    /** The last ask found no path: wait the second out instead of asking every tick. */
    private boolean lastAskFailed;

    RaidScoutGoal(PathfinderMob scout, BlockPos post, BlockPos watch) {
        this.scout = scout;
        this.post = post;
        this.watch = watch;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = scout.getTarget();
        return target == null || !target.isAlive();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Vec3 at = Vec3.atBottomCenterOf(post);
        if (scout.distanceToSqr(at) > AT_POST_SQ) {
            if (--repath <= 0 || (scout.getNavigation().isDone() && !lastAskFailed)) {
                repath = REPATH_TICKS;
                lastAskFailed = !scout.getNavigation().moveTo(at.x, at.y, at.z, 0.8);
                RaidNavCount.note(RaidNavCount.Site.SCOUT, scout, !lastAskFailed);
            }
        } else {
            scout.getNavigation().stop();
        }
        scout.getLookControl().setLookAt(Vec3.atCenterOf(watch));
    }
}
