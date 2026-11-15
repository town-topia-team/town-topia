package com.alex.test.village.golem;

import com.alex.test.village.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Takes the place of the golem's own wandering. With a home village it
 * wanders to spots inside that border, now and then toward the Town Hall;
 * without one it wanders exactly as before (the vanilla goal it wraps).
 */
final class GolemHomeStrollGoal extends Goal {

    /** Same pace and pause between walks as the vanilla golem stroll. */
    private static final double SPEED = 0.6;
    private static final int INTERVAL = 240;
    /** Chance a walk heads toward the Town Hall instead of anywhere nearby. */
    private static final float TOWARD_CENTRE_CHANCE = 0.3F;
    private static final int TRIES = 4;

    private final IronGolem golem;
    private final GolemHome home;
    private final Goal vanilla;
    /** Whether the running walk is the vanilla one. */
    private boolean usingVanilla;
    @Nullable private Vec3 target;

    GolemHomeStrollGoal(IronGolem golem, GolemHome home, Goal vanilla) {
        this.golem = golem;
        this.home = home;
        this.vanilla = vanilla;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        VillageManager village = home.current();
        if (village == null) {
            usingVanilla = true;
            return vanilla.canUse();
        }
        usingVanilla = false;
        if (golem.isVehicle() || golem.getRandom().nextInt(reducedTickDelay(INTERVAL)) != 0) return false;
        target = spotInside(village);
        return target != null;
    }

    @Nullable
    private Vec3 spotInside(VillageManager village) {
        for (int i = 0; i < TRIES; i++) {
            Vec3 spot = golem.getRandom().nextFloat() < TOWARD_CENTRE_CHANCE
                    ? LandRandomPos.getPosTowards(golem, 12, 7, Vec3.atBottomCenterOf(village.getBellPos()))
                    : LandRandomPos.getPos(golem, 10, 7);
            if (spot != null && village.contains(BlockPos.containing(spot))) return spot;
        }
        return null;
    }

    @Override
    public void start() {
        if (usingVanilla) vanilla.start();
        else golem.getNavigation().moveTo(target.x, target.y, target.z, SPEED);
    }

    @Override
    public boolean canContinueToUse() {
        return usingVanilla ? vanilla.canContinueToUse()
                : !golem.getNavigation().isDone() && !golem.isVehicle();
    }

    @Override
    public void tick() {
        if (usingVanilla) vanilla.tick();
    }

    @Override
    public void stop() {
        if (usingVanilla) vanilla.stop();
        else golem.getNavigation().stop();
    }
}
