package com.alex.test.village.raid.warlord;

import com.alex.test.ModSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The whirlwind: with two or more foes around it, the Warlord swings the axe
 * out at arm's length and turns once around (the "spin" clip), standing its
 * ground. Each foe within {@link WarlordMath#SPIN_REACH} is hit once, at the
 * moment the blade passes them on clients ({@link WarlordMath#spinBladeBearing}),
 * and thrown along the sweep. At most once every 8 seconds (5 enraged).
 */
public final class WarlordSpinGoal extends Goal {

    private static final double FLING_SIDEWAYS = 1.3;
    private static final double FLING_UP = 0.45;
    /** The throw leans this much outward from the sweep. */
    private static final double FLING_OUT = 0.5;

    private final RaidWarlord warlord;
    private final Set<UUID> hit = new HashSet<>();
    private long lastSpin = Long.MIN_VALUE / 2;
    /** Ticks since the spin was triggered; -1 while not spinning. */
    private int spinTick = -1;
    private int endsAt;
    private int turnStartsAt;
    private float yaw;

    public WarlordSpinGoal(RaidWarlord warlord) {
        this.warlord = warlord;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (warlord.action() != WarlordAction.NONE || !warlord.onGround()) return false;
        long since = warlord.level().getGameTime() - lastSpin;
        return WarlordMath.wantsSpin(foesAround().size(), since, warlord.isEnraged());
    }

    @Override
    public boolean canContinueToUse() {
        return spinTick >= 0 && warlord.isAlive();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        lastSpin = warlord.level().getGameTime();
        boolean enraged = warlord.isEnraged();
        spinTick = 0;
        endsAt = WarlordMath.clipTicks(WarlordMath.SPIN_CLIP_TICKS, enraged);
        turnStartsAt = WarlordMath.clipTicks((int) Math.round(WarlordMath.SPIN_TURN_FROM * 20), enraged);
        yaw = warlord.yBodyRot;
        hit.clear();
        warlord.getNavigation().stop();
        warlord.playClip("spin");
        warlord.playSound(ModSounds.RAID_WARLORD_ROAR.get(), 1.5F, 1.25F);
    }

    @Override
    public void stop() {
        spinTick = -1;
    }

    @Override
    public void tick() {
        warlord.getNavigation().stop();
        warlord.face(yaw);
        spinTick++;
        if (spinTick >= endsAt) {
            spinTick = -1;
            return;
        }
        if (spinTick == turnStartsAt) warlord.playSound(ModSounds.RAID_WARLORD_SWING.get(), 2.0F, 0.75F);
        boolean enraged = warlord.isEnraged();
        double before = WarlordMath.spinBladeBearing(WarlordMath.clipSeconds(spinTick - 1, enraged));
        double after = WarlordMath.spinBladeBearing(WarlordMath.clipSeconds(spinTick, enraged));
        if (after >= before || !(warlord.level() instanceof ServerLevel level)) return;
        WarlordEffects.spinSweep(level, warlord, yaw, after);
        Vec3 forward = RaidWarlord.forward(yaw);
        Vec3 right = RaidWarlord.right(yaw);
        double damage = WarlordMath.spinDamage(warlord.omenLevel());
        for (LivingEntity foe : foesAround()) {
            if (hit.contains(foe.getUUID())) continue;
            Vec3 at = foe.position().subtract(warlord.position());
            double bearing = bearing(forward, right, at);
            if (!WarlordMath.sweptPast(before, after, bearing)) continue;
            hit.add(foe.getUUID());
            if (warlord.strike(foe, damage)) RaidWarlord.fling(foe, flingWay(forward, right, at, bearing), FLING_SIDEWAYS, FLING_UP);
        }
    }

    /** Degrees to the right of its front of something {@code at} from its middle. */
    static double bearing(Vec3 forward, Vec3 right, Vec3 at) {
        return Math.toDegrees(Math.atan2(at.dot(right), at.dot(forward)));
    }

    /** Where the blade throws a foe at {@code bearing}: the way it was going (toward its front and left), a little outward. */
    static Vec3 flingWay(Vec3 forward, Vec3 right, Vec3 at, double bearing) {
        double b = Math.toRadians(bearing);
        Vec3 along = forward.scale(Math.sin(b)).subtract(right.scale(Math.cos(b)));
        Vec3 out = new Vec3(at.x, 0, at.z);
        return out.lengthSqr() > 1.0e-6 ? along.add(out.normalize().scale(FLING_OUT)) : along;
    }

    /** Foes the spinning axe can reach: near enough, at the blade's height, and in its sight. */
    private List<LivingEntity> foesAround() {
        double reach = WarlordMath.SPIN_REACH + 1.0;
        double feet = warlord.getY();
        Vec3 middle = warlord.position();
        return warlord.level().getEntitiesOfClass(LivingEntity.class, warlord.getBoundingBox().inflate(reach, 1.0, reach),
                foe -> {
                    if (!warlord.isFoe(foe)) return false;
                    double flat = Math.sqrt(WarlordSlamGoal.horizontalDistanceSqr(foe.position(), middle));
                    AABB box = foe.getBoundingBox();
                    return WarlordMath.inSpinZone(flat, foe.getBbWidth() / 2.0)
                            && box.maxY >= feet + WarlordMath.SPIN_LOW && box.minY <= feet + WarlordMath.SPIN_HIGH
                            && warlord.getSensing().hasLineOfSight(foe);
                });
    }
}
