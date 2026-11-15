package com.alex.test.village.raid.warlord;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The charge: a target 8 to 16 blocks away across open, level ground makes the
 * Warlord roar at it, then (as the roar ends) lower its shoulder and run straight
 * at where it stands, about 2.5 times its walking speed, for up to 2 seconds. Everyone in the way is hit
 * once and thrown aside. Running into a wall leaves it dazed for 2 seconds (no
 * attacks): the moment to hit back. It never runs off a drop deeper than 2
 * blocks, and breaks nothing. At most once every 12 seconds.
 */
public final class WarlordChargeGoal extends Goal {

    private enum Phase { WINDUP, RUN, DAZED, DONE }

    /** How finely the way ahead is checked before a charge, in blocks. */
    private static final double LINE_STEP = 0.5;
    /** Half its width: the way is checked down its middle and along both shoulders. */
    private static final double SHOULDER = 0.6;
    /** The highest a target may stand above or below it and still be charged. */
    private static final double MAX_RISE = 1.5;
    private static final double FLING_SIDEWAYS = 1.2;
    private static final double FLING_UP = 0.4;
    private static final float TURN_PER_TICK = 20.0F;

    private final RaidWarlord warlord;
    private final Set<UUID> hit = new HashSet<>();
    private long lastCharge = Long.MIN_VALUE / 2;
    private Phase phase = Phase.DONE;
    private int timer;
    private Vec3 direction = Vec3.ZERO;
    /** Blocks still to run: to where the target stood, and a little on. */
    private double runLeft;

    public WarlordChargeGoal(RaidWarlord warlord) {
        this.warlord = warlord;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = warlord.getTarget();
        if (target == null || !target.isAlive() || warlord.action() != WarlordAction.NONE || !warlord.onGround()) return false;
        if (warlord.level().getGameTime() - lastCharge < WarlordMath.CHARGE_COOLDOWN_TICKS) return false;
        double distance = Math.sqrt(WarlordSlamGoal.horizontalDistanceSqr(target.position(), warlord.position()));
        if (!WarlordMath.inChargeRange(distance) || Math.abs(target.getY() - warlord.getY()) > MAX_RISE) return false;
        return clearWay(target.position(), WarlordMath.chargeRunLength(distance));
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.DONE && warlord.isAlive();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        LivingEntity target = warlord.getTarget();
        if (target == null) return;
        lastCharge = warlord.level().getGameTime();
        Vec3 flat = new Vec3(target.getX() - warlord.getX(), 0, target.getZ() - warlord.getZ());
        direction = flat.normalize();
        runLeft = WarlordMath.chargeRunLength(flat.length());
        hit.clear();
        phase = Phase.WINDUP;
        timer = WarlordMath.clipTicks(WarlordMath.CHARGE_WINDUP_TICKS, warlord.isEnraged());
        warlord.getNavigation().stop();
        face();
        warlord.roar();
    }

    @Override
    public void stop() {
        phase = Phase.DONE;
        if (warlord.isAlive() && warlord.action() != WarlordAction.NONE) warlord.setAction(WarlordAction.NONE);
    }

    @Override
    public void tick() {
        warlord.getNavigation().stop();
        switch (phase) {
            case WINDUP -> {
                // It roars at its target, turning with it.
                LivingEntity target = warlord.getTarget();
                if (target != null && target.isAlive()) {
                    float wanted = RaidWarlord.yawToward(warlord.position(), target.position());
                    warlord.face(Mth.approachDegrees(warlord.yBodyRot, wanted, TURN_PER_TICK));
                } else {
                    face();
                }
                if (--timer <= 0) {
                    aimAgain();
                    phase = Phase.RUN;
                    timer = WarlordMath.CHARGE_MAX_TICKS;
                    warlord.setAction(WarlordAction.CHARGE);
                }
            }
            case RUN -> run();
            case DAZED -> {
                if (--timer <= 0) end();
            }
            case DONE -> {}
        }
    }

    private void run() {
        face();
        // It hit something last tick (the move happens after the AI): a wall.
        if (warlord.horizontalCollision) {
            daze();
            return;
        }
        if (dropAhead()) {
            end();
            return;
        }
        double speed = WarlordMath.chargeBlocksPerTick(warlord.getAttributeValue(Attributes.MOVEMENT_SPEED));
        warlord.setDeltaMovement(direction.x * speed, warlord.getDeltaMovement().y, direction.z * speed);
        trample();
        runLeft -= speed;
        if (--timer <= 0 || runLeft <= 0) end();
    }

    /** Everyone in its path, once each: hurt, and thrown to the side they stood on. */
    private void trample() {
        AABB path = warlord.getBoundingBox().expandTowards(direction.scale(0.8)).inflate(0.2, 0.0, 0.2);
        Vec3 across = new Vec3(-direction.z, 0, direction.x);
        double damage = WarlordMath.chargeDamage(warlord.omenLevel());
        for (LivingEntity foe : warlord.level().getEntitiesOfClass(LivingEntity.class, path,
                foe -> warlord.isFoe(foe) && !hit.contains(foe.getUUID()))) {
            hit.add(foe.getUUID());
            warlord.strike(foe, damage);
            Vec3 side = foe.position().subtract(warlord.position()).dot(across) >= 0 ? across : across.reverse();
            RaidWarlord.fling(foe, side.add(direction.scale(0.5)), FLING_SIDEWAYS, FLING_UP);
        }
    }

    /** The roar is over: it sets off at where its target stands now, if that way is clear too. */
    private void aimAgain() {
        LivingEntity target = warlord.getTarget();
        if (target == null || !target.isAlive()) return;
        Vec3 flat = new Vec3(target.getX() - warlord.getX(), 0, target.getZ() - warlord.getZ());
        double distance = flat.length();
        if (distance < 1.0e-3 || Math.abs(target.getY() - warlord.getY()) > MAX_RISE) return;
        double runLength = WarlordMath.chargeRunLength(distance);
        if (!clearWay(target.position(), runLength)) return;
        direction = flat.normalize();
        runLeft = runLength;
    }

    private void daze() {
        phase = Phase.DAZED;
        timer = WarlordMath.DAZE_TICKS;
        warlord.setAction(WarlordAction.DAZED);
        warlord.setDeltaMovement(0, warlord.getDeltaMovement().y, 0);
        if (warlord.level() instanceof ServerLevel level) {
            BlockPos wall = BlockPos.containing(warlord.position().add(direction.scale(1.2)).add(0, 1, 0));
            WarlordEffects.wallBump(level, warlord, wall);
        }
    }

    private void end() {
        phase = Phase.DONE;
        warlord.setAction(WarlordAction.NONE);
    }

    private void face() {
        float yaw = (float) (Mth.atan2(direction.z, direction.x) * Mth.RAD_TO_DEG) - 90.0F;
        warlord.setYRot(yaw);
        warlord.setYBodyRot(yaw);
        warlord.setYHeadRot(yaw);
    }

    /**
     * Nothing in the way down its middle or along either shoulder, at its feet or
     * up its body, and ground all the way no more than {@link WarlordMath#CHARGE_MAX_DROP} down.
     */
    private boolean clearWay(Vec3 to, double runLength) {
        Level level = warlord.level();
        Vec3 from = warlord.position();
        Vec3 flat = new Vec3(to.x - from.x, 0, to.z - from.z);
        if (flat.length() < 1.0e-3) return false;
        Vec3 dir = flat.normalize();
        double length = runLength;  // the whole run, past the target too
        Vec3 across = new Vec3(-dir.z, 0, dir.x).scale(SHOULDER);
        int feet = Mth.floor(from.y + 0.01);
        int height = Mth.ceil(warlord.getBbHeight());
        for (double d = LINE_STEP; d <= length; d += LINE_STEP) {
            Vec3 middle = from.add(dir.scale(d));
            for (Vec3 p : new Vec3[]{middle, middle.add(across), middle.subtract(across)}) {
                for (int dy = 0; dy < height; dy++) {
                    BlockPos pos = BlockPos.containing(p.x, feet + dy, p.z);
                    if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                            || !level.getFluidState(pos).isEmpty()) return false;
                }
            }
            if (!groundWithinDrop(level, middle, feet)) return false;
        }
        return true;
    }

    /** Running on would take it over a drop deeper than it may go, or into water or lava. */
    private boolean dropAhead() {
        Level level = warlord.level();
        Vec3 ahead = warlord.position().add(direction.scale(1.2));
        int feet = Mth.floor(warlord.getY() + 0.01);
        for (int dy = -1; dy <= 0; dy++) {
            if (!level.getFluidState(BlockPos.containing(ahead.x, feet + dy, ahead.z)).isEmpty()) return true;
        }
        return !groundWithinDrop(level, ahead, feet);
    }

    private static boolean groundWithinDrop(Level level, Vec3 at, int feet) {
        for (int down = 1; down <= WarlordMath.CHARGE_MAX_DROP + 1; down++) {
            BlockPos pos = BlockPos.containing(at.x, feet - down, at.z);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return true;
        }
        return false;
    }
}
