package com.alex.test.village.golem;

import com.alex.test.village.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.IronGolem;

import java.util.EnumSet;

/**
 * A golem outside its home village's border walks back to the last spot it
 * stood inside. Never while it is fighting, on a lead or riding something.
 */
final class GolemReturnHomeGoal extends Goal {

    /** Same pace as the golem's own wandering. */
    private static final double SPEED = 0.6;
    /** Ticks to wait before trying again when no path back was found (5 seconds). */
    private static final int RETRY_TICKS = 100;

    private final IronGolem golem;
    private final GolemHome home;
    private int retryAt;

    GolemReturnHomeGoal(IronGolem golem, GolemHome home) {
        this.golem = golem;
        this.home = home;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        VillageManager village = home.current();
        if (village == null || golem.tickCount < retryAt || golem.getTarget() != null
                || golem.isLeashed() || golem.isPassenger()) return false;
        BlockPos back = home.lastInside();
        // The border is being redrawn (its old inside spot no longer counts): wait for it.
        return back != null && !village.contains(golem.blockPosition()) && village.contains(back);
    }

    @Override
    public void start() {
        BlockPos back = home.lastInside();
        if (back == null || !golem.getNavigation().moveTo(back.getX() + 0.5, back.getY(), back.getZ() + 0.5, SPEED)) {
            retryAt = golem.tickCount + RETRY_TICKS;
        }
    }

    @Override
    public boolean canContinueToUse() {
        return !golem.getNavigation().isDone() && golem.getTarget() == null && !golem.isLeashed();
    }

    @Override
    public void stop() {
        golem.getNavigation().stop();
    }
}
