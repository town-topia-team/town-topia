package com.alex.test.village.raid.warlord;

import com.alex.test.village.raid.RaidOmenMath;

/**
 * The numbers of the Illager Warlord, the leader of the Bad Omen raid's last
 * wave: how tough it is at each omen level (1 to 5), how often it swings,
 * slams and charges, and how long it waits before it tries to get unstuck.
 * Times are in ticks (20 a second), distances in blocks.
 *
 * <p>No Minecraft types: unit-tested on a plain JVM.
 */
public final class WarlordMath {

    /** Enemies within this many blocks are hit by the slam. */
    public static final double SLAM_RADIUS = 4.0;
    /** It slams only when at least this many enemies crowd it. */
    public static final int SLAM_CROWD = 3;
    /** Into the swing clip when the blade lands (0.6 s); see {@link #clipTicks}. */
    public static final int SWING_WINDUP_TICKS = 12;
    /** The swing clip is 1.2 s: it stands its ground until the axe is back up. */
    public static final int SWING_CLIP_TICKS = 24;
    /** Into the slam clip when the axe hits the ground (1.0 s). */
    public static final int SLAM_WINDUP_TICKS = 20;
    /** The slam clip is 1.6 s. */
    public static final int SLAM_CLIP_TICKS = 32;
    /**
     * The swing's blade comes down in front of it, from overhead to the ground: it hits
     * whatever stands from its middle out to {@link #SWING_REACH} ahead (the axe tip
     * lands 3.6 ahead) and within {@link #SWING_HALF_WIDTH} of its middle line.
     */
    public static final double SWING_REACH = 3.6;
    public static final double SWING_HALF_WIDTH = 0.7;
    /** The falling blade reaches foes up to this high above its feet. */
    public static final double SWING_TOP = 3.0;
    /** It starts a swing at a target this far past the edge of its own box (its attack box). */
    public static final double SWING_START_REACH = 2.0;
    /** Two or more foes this close (to their near side): it spins with the axe held out. */
    public static final double SPIN_REACH = 4.1;
    /** The spinning axe passes between these heights above its feet. */
    public static final double SPIN_LOW = 0.9;
    public static final double SPIN_HIGH = 2.0;
    public static final int SPIN_CROWD = 2;
    /** The spin clip is 1.8 s. */
    public static final int SPIN_CLIP_TICKS = 36;
    /** The spin clip holds the blade out to its right (degrees right of its front) ... */
    public static final double SPIN_START_BEARING = 69;
    /** ... and turns it once around, through its front and left, between these clip times. */
    public static final double SPIN_TURN_FROM = 0.45;
    public static final double SPIN_TURN_TO = 1.05;
    public static final int CHARGE_COOLDOWN_TICKS = 240;
    /** The roar before the run: the whole roar clip (1.6 s), so it sets off as the roar ends. */
    public static final int CHARGE_WINDUP_TICKS = 32;
    /** Its clips play this much faster when enraged. */
    public static final double ENRAGED_CLIP_SPEED = 1.15;
    /** A triggered clip starts after a blend of this many ticks ({@link WarlordAnimator}). */
    public static final int ACT_BLEND_TICKS = 2;
    public static final int CHARGE_MAX_TICKS = 40;
    /** The charge runs this many times its walking speed. */
    public static final double CHARGE_SPEED_FACTOR = 2.5;
    /**
     * A ground mob's real walking pace, in blocks a tick, per point of its movement
     * speed attribute (friction and the move input taken into account).
     */
    public static final double WALK_PACE_PER_SPEED = 0.55;
    /** The charge runs on this far past where its target stood when it set off. */
    public static final double CHARGE_OVERRUN = 3.0;
    /** It never charges along a line that drops deeper than this. */
    public static final int CHARGE_MAX_DROP = 2;
    /** Dazed after running into a wall: no attacks meanwhile. */
    public static final int DAZE_TICKS = 40;
    /** Villagers outside within this many blocks hear its roar and run for shelter. */
    public static final double ROAR_RADIUS = 24.0;
    /** The body stays this long for the death clip (2.5 s). */
    public static final int DEATH_HOLD_TICKS = 50;
    /** No way to anyone for this long (30 s): it tries another route. */
    public static final int NO_REACH_TICKS = 600;
    /** Moved less than {@link #STUCK_MOVE} blocks in this long (60 s): it leaps. */
    public static final int STUCK_TICKS = 1200;
    public static final double STUCK_MOVE = 2.0;
    /** No fight for this long (2 min): it glows, so it can be found. */
    public static final int IDLE_GLOW_TICKS = 2400;
    /** How far a leap may take it. */
    public static final int LEAP_RANGE = 12;
    /** It pounds the same door at most this often (20 s). */
    public static final int POUND_COOLDOWN_TICKS = 400;

    private static final double BASE_HEALTH = 150;
    private static final double HEALTH_PER_LEVEL = 50;
    private static final double BASE_SWING = 12;
    private static final double SWING_PER_LEVEL = 1.5;
    private static final double BASE_SLAM = 8;
    private static final double BASE_CHARGE = 6;
    private static final double WALK_SPEED = 0.25;
    private static final double ENRAGED_SPEED = 1.15;
    private static final double ENRAGE_SHARE = 0.5;
    private static final double CHARGE_MIN = 8;
    private static final double CHARGE_MAX = 16;

    private WarlordMath() {}

    /** 150 at omen level I, then 50 more with every level: 350 at V. */
    public static double maxHealth(int level) {
        return BASE_HEALTH + HEALTH_PER_LEVEL * (RaidOmenMath.clampLevel(level) - 1);
    }

    /** 12 at level I, 1.5 more with every level: 18 at V. */
    public static double swingDamage(int level) {
        return BASE_SWING + SWING_PER_LEVEL * (RaidOmenMath.clampLevel(level) - 1);
    }

    /** 8 at level I, one more with every level: 12 at V. */
    public static double slamDamage(int level) {
        return BASE_SLAM + (RaidOmenMath.clampLevel(level) - 1);
    }

    /** 6 at level I, one more with every level: 10 at V. */
    public static double chargeDamage(int level) {
        return BASE_CHARGE + (RaidOmenMath.clampLevel(level) - 1);
    }

    /** Down to half its health or less. */
    public static boolean enraged(float health, float maxHealth) {
        return health <= maxHealth * ENRAGE_SHARE;
    }

    /** One swing every 2 seconds, every 1.5 when enraged. */
    public static int swingIntervalTicks(boolean enraged) {
        return enraged ? 30 : 40;
    }

    /** One slam every 10 seconds at most, every 6 when enraged. */
    public static int slamCooldownTicks(boolean enraged) {
        return enraged ? 120 : 200;
    }

    /** Slams when {@link #SLAM_CROWD} or more enemies are near and its last slam was long enough ago. */
    public static boolean wantsSlam(int enemiesNear, long ticksSinceLastSlam, boolean enraged) {
        return enemiesNear >= SLAM_CROWD && ticksSinceLastSlam >= slamCooldownTicks(enraged);
    }

    /** Far enough to run at, near enough to reach: 8 to 16 blocks. */
    public static boolean inChargeRange(double distance) {
        return distance >= CHARGE_MIN && distance <= CHARGE_MAX;
    }

    /** The charge's pace in blocks a tick: {@link #CHARGE_SPEED_FACTOR} times its real walking pace. */
    public static double chargeBlocksPerTick(double movementSpeed) {
        return CHARGE_SPEED_FACTOR * WALK_PACE_PER_SPEED * movementSpeed;
    }

    /** How far one charge runs: to where its target stood, and {@link #CHARGE_OVERRUN} on. */
    public static double chargeRunLength(double distanceToTarget) {
        return distanceToTarget + CHARGE_OVERRUN;
    }

    /**
     * Server ticks from triggering a clip to the moment {@code clipTick} ticks into it,
     * as clients see it: after the controller's blend, quicker when enraged.
     */
    public static int clipTicks(int clipTick, boolean enraged) {
        return (int) Math.round((ACT_BLEND_TICKS + clipTick) / clipSpeed(enraged));
    }

    /** How far into a clip (seconds) clients are {@code ticks} after it was triggered. */
    public static double clipSeconds(int ticks, boolean enraged) {
        return Math.max(0.0, ticks * clipSpeed(enraged) - ACT_BLEND_TICKS) / 20.0;
    }

    private static double clipSpeed(boolean enraged) {
        return enraged ? ENRAGED_CLIP_SPEED : 1.0;
    }

    /**
     * A foe whose middle is {@code ahead} blocks in front of its middle and {@code side}
     * to one side, {@code halfWidth} wide: is any of it under the falling blade?
     */
    public static boolean inSwingZone(double ahead, double side, double halfWidth) {
        return ahead + halfWidth >= 0.0 && ahead - halfWidth <= SWING_REACH
                && Math.abs(side) - halfWidth <= SWING_HALF_WIDTH;
    }

    /** Three quarters of its swing: 9 at level I, 13.5 at V. */
    public static double spinDamage(int level) {
        return swingDamage(level) * 0.75;
    }

    /** One spin every 8 seconds at most, every 5 when enraged. */
    public static int spinCooldownTicks(boolean enraged) {
        return enraged ? 100 : 160;
    }

    /** Spins when {@link #SPIN_CROWD} or more foes are around it and its last spin was long enough ago. */
    public static boolean wantsSpin(int foesAround, long ticksSinceLastSpin, boolean enraged) {
        return foesAround >= SPIN_CROWD && ticksSinceLastSpin >= spinCooldownTicks(enraged);
    }

    /** A foe {@code distance} blocks away (flat, middle to middle), {@code halfWidth} wide: in the spinning axe's reach? */
    public static boolean inSpinZone(double distance, double halfWidth) {
        return distance - halfWidth <= SPIN_REACH;
    }

    /**
     * Where the spinning blade is, {@code seconds} into the spin clip: degrees to the
     * right of its front, falling as it turns (once around, quickest in the middle).
     */
    public static double spinBladeBearing(double seconds) {
        double u = Math.min(1.0, Math.max(0.0, (seconds - SPIN_TURN_FROM) / (SPIN_TURN_TO - SPIN_TURN_FROM)));
        return SPIN_START_BEARING - 360.0 * u * u * (3 - 2 * u);
    }

    /** The blade went from bearing {@code before} down to {@code after}: did it pass {@code bearing} (any turn of it)? */
    public static boolean sweptPast(double before, double after, double bearing) {
        double swept = before - after;
        if (swept <= 0) return false;
        double behind = ((before - bearing) % 360.0 + 360.0) % 360.0;
        return behind < swept;
    }

    /** About iron golem pace, 15% faster when enraged. */
    public static double walkSpeed(boolean enraged) {
        return enraged ? WALK_SPEED * ENRAGED_SPEED : WALK_SPEED;
    }
}
