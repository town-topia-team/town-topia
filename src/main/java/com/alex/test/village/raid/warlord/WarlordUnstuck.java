package com.alex.test.village.raid.warlord;

import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Keeps a big Warlord from standing stuck in a raid. Checked once a second:
 * <ul>
 * <li>no way to its target or the village centre for 30 seconds: it walks to an
 *     open spot 8 to 12 blocks off the line to the centre, then carries on;</li>
 * <li>under 2 blocks of movement in a minute (and not fighting): it leaps to open
 *     ground nearer the centre, a real jump with the leap and land clips;</li>
 * <li>no fight for 2 minutes: it glows, so it can be found, until it fights again.</li>
 * </ul>
 * Only for a Warlord that came with a raid (it needs the village centre).
 */
public final class WarlordUnstuck {

    private static final int CHECK_TICKS = 20;
    /** Fighting this recently counts as getting somewhere. */
    private static final int FIGHT_GRACE_TICKS = 100;
    /** A detour walk is given up after this long. */
    private static final int DETOUR_TICKS = 300;
    /** At the village centre (or this close) there is nowhere further to get. */
    private static final double CENTRE_REACHED = 6.0;
    /** The leap's push off in the "leap" clip (0.3 s). */
    private static final int PUSH_OFF_TICKS = 6;
    private static final int MAX_FLIGHT_TICKS = 60;
    /** A planned leap that has not taken off this soon is dropped: the ground it was planned for may be gone. */
    private static final int LEAP_STALE_TICKS = 20;
    /** Nor if it has been moved this far from where it planned the leap. */
    private static final double LEAP_MOVED = 2.0;
    private static final int MAX_LEAP_RISE = 4;
    private static final int MAX_LEAP_DROP = 6;
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG = 0.98;
    private static final double AIR_FRICTION = 0.91;
    private static final int[] LEAP_ANGLES = {0, 30, -30, 60, -60, 90, -90};

    private final RaidWarlord warlord;
    private long progressTick = -1;
    private Vec3 progressPos = Vec3.ZERO;
    private long reachTick;
    private long quietSince;
    private boolean glowing;
    @Nullable private BlockPos detour;
    private long detourUntil;
    @Nullable private Vec3 landing;
    private Vec3 leapFrom = Vec3.ZERO;
    private long leapPlannedAt;
    private int leapTicks;
    private boolean airborne;

    WarlordUnstuck(RaidWarlord warlord) {
        this.warlord = warlord;
    }

    /** The once-a-second look: glow, progress, reach; may start a detour or a leap. */
    void check(ServerLevel level) {
        if (warlord.isDeadOrDying()) return;
        VillageManager village = warlord.raidVillage();
        BlockPos centre = village == null ? null : village.raids().warlordCentre();
        if (village == null || centre == null) return;
        long now = level.getGameTime();
        Vec3 pos = warlord.position();
        if (progressTick < 0) {
            progressTick = reachTick = quietSince = now;
            progressPos = pos;
        }
        boolean fighting = now - warlord.lastFightTick() < FIGHT_GRACE_TICKS;
        glow(now, fighting);
        if (landing != null) {
            if (airborne || !stale(now)) return;
            callOffLeap();  // never took off (something else held it): plan afresh later
        }

        if (fighting || pos.distanceTo(progressPos) >= WarlordMath.STUCK_MOVE) {
            progressPos = pos;
            progressTick = now;
        }
        if (fighting || canReach(centre) || horizontalDistance(pos, Vec3.atBottomCenterOf(centre)) < CENTRE_REACHED) {
            reachTick = now;
        }
        if (now - progressTick >= WarlordMath.STUCK_TICKS) {
            if (startLeap(level, village, centre)) {
                progressTick = reachTick = now;
                detour = null;
            }
            return;
        }
        if (detour == null && now - reachTick >= WarlordMath.NO_REACH_TICKS) {
            detour = pickDetour(level, village, centre);
            detourUntil = now + DETOUR_TICKS;
            reachTick = now;
        }
    }

    /** After pounding a door it cannot get through: try another way at once. */
    void forceDetour() {
        reachTick = warlord.level().getGameTime() - WarlordMath.NO_REACH_TICKS;
    }

    private void glow(long now, boolean fighting) {
        if (fighting) quietSince = now;
        if (now - Math.max(quietSince, warlord.lastFightTick()) >= WarlordMath.IDLE_GLOW_TICKS) {
            warlord.addEffect(new MobEffectInstance(MobEffects.GLOWING, CHECK_TICKS * 3, 0, false, false));
            glowing = true;
        } else if (glowing) {
            warlord.removeEffect(MobEffects.GLOWING);
            glowing = false;
        }
    }

    private boolean canReach(BlockPos centre) {
        LivingEntity target = warlord.getTarget();
        // Round 5 (switch raidbig): the village map answers when it can, on a worker thread,
        // instead of a whole Minecraft path once a second.
        Boolean onMap = warlord.wideWalk().mapReach(target != null && target.isAlive() ? target.blockPosition() : centre);
        if (onMap != null) return onMap;
        Path path = target != null && target.isAlive()
                ? warlord.getNavigation().createPath(target, 1)
                : warlord.getNavigation().createPath(centre, 4);
        boolean reaches = path != null && path.canReach();
        com.alex.test.village.raid.RaidNavCount.note(com.alex.test.village.raid.RaidNavCount.Site.WARLORD_CHECK, warlord, reaches);
        return reaches;
    }

    // -------------------------------------------------------------------------
    // Detour
    // -------------------------------------------------------------------------

    @Nullable
    private BlockPos pickDetour(ServerLevel level, VillageManager village, BlockPos centre) {
        Vec3 pos = warlord.position();
        Vec3 toCentre = flatDirection(pos, Vec3.atBottomCenterOf(centre));
        Vec3 across = new Vec3(-toCentre.z, 0, toCentre.x);
        for (int attempt = 0; attempt < 8; attempt++) {
            double side = (attempt % 2 == 0) ? 1 : -1;
            double off = 8 + level.random.nextDouble() * 4;
            Vec3 p = pos.add(across.scale(side * off)).add(toCentre.scale(4));
            BlockPos feet = surface(level, p);
            if (feet != null && openGround(level, village, feet)) return feet;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Leap
    // -------------------------------------------------------------------------

    private boolean startLeap(ServerLevel level, VillageManager village, BlockPos centre) {
        Vec3 spot = findLanding(level, village, centre);
        if (spot == null) return false;
        landing = spot;
        leapFrom = warlord.position();
        leapPlannedAt = level.getGameTime();
        leapTicks = 0;
        airborne = false;
        return true;
    }

    /** Too long since it was planned, or moved off the spot it was planned from. */
    private boolean stale(long now) {
        return now - leapPlannedAt > LEAP_STALE_TICKS || warlord.position().distanceTo(leapFrom) > LEAP_MOVED;
    }

    /**
     * Open ground within {@link WarlordMath#LEAP_RANGE}, nearer the centre than it
     * stands now: sky above its head, outside and off every building (roofs
     * included), a sturdy block underfoot, no water, room for its body.
     */
    @Nullable
    private Vec3 findLanding(ServerLevel level, VillageManager village, BlockPos centre) {
        Vec3 pos = warlord.position();
        Vec3 centreAt = Vec3.atBottomCenterOf(centre);
        double nowFromCentre = horizontalDistance(pos, centreAt);
        Vec3 toCentre = flatDirection(pos, centreAt);
        for (int dist = WarlordMath.LEAP_RANGE; dist >= 4; dist -= 2) {
            for (int angle : LEAP_ANGLES) {
                Vec3 dir = toCentre.yRot(angle * Mth.DEG_TO_RAD);
                BlockPos feet = surface(level, pos.add(dir.scale(dist)));
                if (feet == null) continue;
                int rise = feet.getY() - Mth.floor(pos.y);
                if (rise > MAX_LEAP_RISE || rise < -MAX_LEAP_DROP) continue;
                Vec3 at = Vec3.atBottomCenterOf(feet);
                if (horizontalDistance(at, centreAt) >= nowFromCentre) continue;
                if (!openGround(level, village, feet)) continue;
                return at;
            }
        }
        return null;
    }

    private boolean openGround(ServerLevel level, VillageManager village, BlockPos feet) {
        if (!level.canSeeSky(feet.above(Mth.ceil(warlord.getBbHeight())))) return false;
        BlockPos below = feet.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(below).isEmpty()) return false;
        for (Building building : village.getBuildings()) {
            if (building.mayAffectRoom(feet)) return false;
        }
        AABB box = warlord.getDimensions(warlord.getPose()).makeBoundingBox(Vec3.atBottomCenterOf(feet));
        return level.noCollision(warlord, box);
    }

    /** One tick of a leap: crouch, push off, fly, land. */
    private void tickLeap(ServerLevel level) {
        Vec3 to = landing;
        if (to == null) return;
        if (!airborne && leapTicks == 0 && stale(level.getGameTime())) {
            landing = null;
            return;
        }
        leapTicks++;
        warlord.getNavigation().stop();
        face(to);
        if (!airborne) {
            if (leapTicks == 1) warlord.playClip("leap");  // only once the leap is really under way
            if (leapTicks >= PUSH_OFF_TICKS) {
                if (warlord.position().distanceTo(leapFrom) > LEAP_MOVED) {
                    callOffLeap();  // shoved off its spot while crouching: the solved jump no longer fits
                    return;
                }
                launch(to);
                airborne = true;
            }
            return;
        }
        if ((warlord.onGround() && leapTicks > PUSH_OFF_TICKS + 3) || leapTicks > MAX_FLIGHT_TICKS) {
            landing = null;
            warlord.playClip("land");
            WarlordEffects.landing(level, warlord);
        }
    }

    /**
     * Drops the planned leap. If it was already crouching, its leap clip (held in
     * the air until the landing) lets go too, or it would stay crouched.
     */
    private void callOffLeap() {
        if (leapTicks > 0 && !airborne) warlord.stopClip("leap");
        landing = null;
    }

    /**
     * Sets off toward {@code to} so it comes down there: the flight time from
     * the distance, the push from how air slows a mob each tick.
     */
    private void launch(Vec3 to) {
        Vec3 pos = warlord.position();
        double distance = horizontalDistance(pos, to);
        int flight = Mth.clamp((int) Math.round(distance * 1.3), 10, 22);
        double sideways = distance * (1 - AIR_FRICTION) / (1 - Math.pow(AIR_FRICTION, flight));
        double up = upwardSpeedToLand(to.y - pos.y, flight);
        Vec3 dir = flatDirection(pos, to);
        warlord.setDeltaMovement(dir.x * sideways, up, dir.z * sideways);
        warlord.hasImpulse = true;
    }

    /** The upward push that ends {@code rise} higher after {@code ticks} of gravity and drag. */
    private static double upwardSpeedToLand(double rise, int ticks) {
        double low = 0.0;
        double high = 3.0;
        for (int i = 0; i < 40; i++) {
            double mid = (low + high) / 2;
            if (heightAfter(mid, ticks) < rise) low = mid;
            else high = mid;
        }
        return high;
    }

    private static double heightAfter(double up, int ticks) {
        double y = 0;
        double v = up;
        for (int t = 0; t < ticks; t++) {
            y += v;
            v = (v - GRAVITY) * AIR_DRAG;
        }
        return y;
    }

    // -------------------------------------------------------------------------
    // Small helpers
    // -------------------------------------------------------------------------

    private void face(Vec3 to) {
        Vec3 dir = flatDirection(warlord.position(), to);
        float yaw = (float) (Mth.atan2(dir.z, dir.x) * Mth.RAD_TO_DEG) - 90.0F;
        warlord.setYRot(yaw);
        warlord.setYBodyRot(yaw);
        warlord.setYHeadRot(yaw);
    }

    @Nullable
    private static BlockPos surface(ServerLevel level, Vec3 at) {
        int x = Mth.floor(at.x);
        int z = Mth.floor(at.z);
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    private static Vec3 flatDirection(Vec3 from, Vec3 to) {
        Vec3 flat = new Vec3(to.x - from.x, 0, to.z - from.z);
        return flat.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.sqrt(WarlordSlamGoal.horizontalDistanceSqr(a, b));
    }

    // -------------------------------------------------------------------------
    // The goal that walks the detour and flies the leap
    // -------------------------------------------------------------------------

    /**
     * Runs a detour or a leap {@link WarlordUnstuck} has started, ahead of the
     * raid march. A detour gives way to a foe in reach; a leap, once begun, does not.
     */
    public static final class DetourGoal extends Goal {

        private static final int REPATH_TICKS = 20;
        private static final double ARRIVED = 2.5;

        private final RaidWarlord warlord;
        private int repathIn;

        public DetourGoal(RaidWarlord warlord) {
            this.warlord = warlord;
            setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            WarlordUnstuck unstuck = warlord.unstuck();
            if (unstuck.landing != null) return true;
            if (unstuck.detour == null || warlord.action() != WarlordAction.NONE) return false;
            LivingEntity target = warlord.getTarget();
            return target == null || !warlord.isWithinMeleeAttackRange(target);
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
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
            warlord.wideWalk().drop();
        }

        @Override
        public void tick() {
            WarlordUnstuck unstuck = warlord.unstuck();
            if (!(warlord.level() instanceof ServerLevel level)) return;
            if (unstuck.landing != null) {
                unstuck.tickLeap(level);
                return;
            }
            BlockPos to = unstuck.detour;
            if (to == null) return;
            Vec3 at = Vec3.atBottomCenterOf(to);
            if (level.getGameTime() > unstuck.detourUntil || horizontalDistance(warlord.position(), at) < ARRIVED) {
                unstuck.detour = null;
                warlord.wideWalk().drop();
                warlord.getNavigation().stop();
                return;
            }
            // Round 5 (switch raidbig): by village map route while the spot is on the map.
            if (warlord.wideWalk().tick(to, 1.0, com.alex.test.village.raid.WideMapWalk.Goal.PLACE)) {
                repathIn = 0;
                return;
            }
            if (--repathIn <= 0) {
                repathIn = REPATH_TICKS;
                boolean found = warlord.getNavigation().moveTo(at.x, at.y, at.z, 1.0);
                com.alex.test.village.raid.RaidNavCount.note(com.alex.test.village.raid.RaidNavCount.Site.WARLORD_DETOUR, warlord, found);
            }
        }
    }
}
