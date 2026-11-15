package com.alex.test.village.villagers.mage;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.village.villagers.guard.GuardBalance;

/**
 * The mage's numbers, and its 1..100 skill track. Pure numbers, like
 * {@link com.alex.test.village.villagers.archer.ArcherBalance}.
 *
 * <p>The mage is the village's crowd fighter: the archer is best against one
 * dangerous mob, the mage against a group. A bolt hits a little softer than an
 * arrow but never misses: it curves after its mob like the cleric's orb. The
 * big spells hit many mobs at once, and slow or pin them for the guards.
 *
 * <p>Practice is the guard's, at the guard's pay and full day, so a peaceful
 * day of duty reaches level 100 in about 80 in-game days at 1x. Spells open at
 * milestones: Arcane Bolt from the start, Frost Nova at 25, Blink at 40, Ward
 * at 50, Chain Spark at 75 (five jumps from 90) and Starfall at 100. Between
 * milestones the mage casts faster, holds more mana and gets it back sooner,
 * and its bolts hit harder.
 */
public final class MageBalance {

    public static final int MAX_LEVEL = 100;

    public static final int FROST_NOVA_LEVEL = 25;
    public static final int BLINK_LEVEL = 40;
    public static final int WARD_LEVEL = 50;
    public static final int CHAIN_SPARK_LEVEL = 75;
    /** Chain Spark jumps {@link #CHAIN_FULL_JUMPS} times from here, not {@link #CHAIN_FIRST_JUMPS}. */
    public static final int CHAIN_FULL_LEVEL = 90;
    public static final int STARFALL_LEVEL = 100;

    // -------------------------------------------------------------------------
    // Casting and range
    // -------------------------------------------------------------------------

    /** How far a mage casts from, in blocks: an archer's range plus a little. */
    public static final double CAST_RANGE = 16.0D;
    /** Closer than this it does not start a cast (it backs off first). */
    public static final double MIN_CAST_RANGE = 6.0D;

    /** Bolt damage: 4 at level 1 to 7 at level 100 (a zombie takes 3 to 5 bolts). */
    private static final float BOLT_DAMAGE_NOVICE = 4.0f;
    private static final float BOLT_DAMAGE_MASTER = 7.0f;

    /**
     * Ticks from the start of a bolt cast to the bolt leaving the hand. At 35 the
     * clip plays at its own pace (its release frame is 27 ticks in, after the
     * 8-tick blend); a master casts in 24, the clip played half again as fast.
     * Every other cast is sped up by the same share (see {@link #castPace}).
     */
    private static final int CAST_TICKS_NOVICE =
            VillagerAnimationState.BOLT_RELEASE_TICKS + VillagerAnimationState.WORK_BLEND_TICKS;
    private static final int CAST_TICKS_MASTER = 24;

    /** Rest after a bolt before the next cast starts: the follow-through of the clip, and a breath. */
    private static final int RECOVERY_TICKS_NOVICE = 24;
    private static final int RECOVERY_TICKS_MASTER = 10;

    // -------------------------------------------------------------------------
    // Mana
    // -------------------------------------------------------------------------

    /** Mana: 100 at level 1, 150 at 100. */
    private static final float NOVICE_MAX_MANA = 100f;
    private static final float MASTER_MAX_MANA = 150f;
    /** Mana back each second, fighting or not: 2 at level 1, 4 at 100. */
    private static final float NOVICE_MANA_PER_SECOND = 2f;
    private static final float MASTER_MANA_PER_SECOND = 4f;

    // -------------------------------------------------------------------------
    // Frost Nova
    // -------------------------------------------------------------------------

    public static final float NOVA_MANA = 30f;
    public static final int NOVA_REAGENTS = 1;
    public static final double NOVA_RADIUS = 4.0;
    /** Slowness III: a mob in the nova barely walks. */
    public static final int NOVA_SLOW_AMPLIFIER = 2;
    private static final float NOVICE_NOVA_DAMAGE = 2.0f;
    private static final float MASTER_NOVA_DAMAGE = 4.0f;
    /** How long the slowness lasts: 3 s at level 25, 6 s at 100. */
    private static final int NOVICE_NOVA_SLOW_TICKS = 60;
    private static final int MASTER_NOVA_SLOW_TICKS = 120;
    public static final int NOVA_COOLDOWN_TICKS = 100;
    /** A nova is worth its reagent on two mobs or more. */
    public static final int NOVA_MIN_MOBS = 2;
    /** At this many it comes before a Chain Spark: pinning a pack beats picking at it. */
    public static final int NOVA_PACK_MOBS = 3;

    // -------------------------------------------------------------------------
    // Ward
    // -------------------------------------------------------------------------

    public static final float WARD_MANA = 25f;
    /** A guard or captain below this share of its health, still fighting, is warded. */
    public static final float WARD_HEALTH_FRACTION = 0.6f;
    public static final double WARD_RANGE = 16.0;
    /** How long the bubble lasts if nothing breaks it. */
    public static final int WARD_TICKS = 20 * 15;
    /** One ward from one mage at most this often. */
    public static final int WARD_COOLDOWN_TICKS = 160;
    /** Absorption I (4 health) until here, Absorption II (8) from here. */
    public static final int STRONG_WARD_LEVEL = 80;

    // -------------------------------------------------------------------------
    // Chain Spark: particle lightning, never a real lightning bolt
    // -------------------------------------------------------------------------

    public static final float CHAIN_MANA = 35f;
    public static final int CHAIN_REAGENTS = 1;
    public static final int CHAIN_FIRST_JUMPS = 3;
    public static final int CHAIN_FULL_JUMPS = 5;
    /** How far the spark jumps from one mob to the next. */
    public static final double CHAIN_JUMP_RANGE = 5.0;
    private static final float NOVICE_CHAIN_DAMAGE = 4.0f;
    private static final float MASTER_CHAIN_DAMAGE = 6.0f;
    /** Each jump hits for this share of the last. */
    public static final float CHAIN_FALLOFF = 0.85f;
    public static final int CHAIN_COOLDOWN_TICKS = 80;
    public static final int CHAIN_MIN_MOBS = 2;

    // -------------------------------------------------------------------------
    // Starfall: once per fight
    // -------------------------------------------------------------------------

    public static final float STARFALL_MANA = 60f;
    public static final int STARFALL_REAGENTS = 2;
    public static final int STARFALL_MIN_MOBS = 3;
    public static final int STARFALL_MIN_STARS = 3;
    public static final int STARFALL_MAX_STARS = 5;
    public static final float STAR_DAMAGE = 8.0f;
    public static final double STAR_RADIUS = 2.5;
    /** From the top of the sky to the ground. */
    public static final int STAR_FALL_TICKS = 20;
    /** Between one star and the next. */
    public static final int STAR_SPACING_TICKS = 6;
    /** How high above the target a star appears. */
    public static final double STAR_HEIGHT = 18.0;

    // -------------------------------------------------------------------------
    // Blink
    // -------------------------------------------------------------------------

    public static final float BLINK_MANA = 20f;
    public static final int BLINK_COOLDOWN_TICKS = 20 * 30;
    public static final double BLINK_MIN_DISTANCE = 6.0;
    public static final double BLINK_MAX_DISTANCE = 8.0;
    /** A mob this close, with no guard to run to: the mage blinks away. */
    public static final double BLINK_DANGER_RANGE = 3.0;
    /** No guard or captain this close counts as cornered. */
    public static final double BLINK_NO_GUARD_RADIUS = 16.0;

    private MageBalance() {}

    /** Same curve as the guard's: 20·(L−1) + 0.2·(L−1)². */
    public static float experienceForLevel(int level) {
        return GuardBalance.experienceForLevel(Math.clamp(level, 1, MAX_LEVEL));
    }

    private static float progress(int level) {
        int clamped = Math.max(1, Math.min(MAX_LEVEL, level));
        return (clamped - 1) / (float) (MAX_LEVEL - 1);
    }

    private static float lerp(float from, float to, int level) {
        return from + (to - from) * progress(level);
    }

    // --- casting ---------------------------------------------------------------

    public static float boltDamage(int level) {
        return lerp(BOLT_DAMAGE_NOVICE, BOLT_DAMAGE_MASTER, level);
    }

    /** Ticks from the start of a bolt cast to the bolt leaving the hand. */
    public static int castTicks(int level) {
        return Math.round(lerp(CAST_TICKS_NOVICE, CAST_TICKS_MASTER, level));
    }

    /**
     * How much of a clip's own time to its release a cast takes at this level:
     * 1 for a novice, 24/35 for a master. The bolt's pace, shared by every cast.
     */
    public static float castPace(int level) {
        return castTicks(level) / (float) CAST_TICKS_NOVICE;
    }

    public static int recoveryTicks(int level) {
        return Math.round(lerp(RECOVERY_TICKS_NOVICE, RECOVERY_TICKS_MASTER, level));
    }

    /**
     * Pace for the bolt clip so its release frame lands on the tick the bolt
     * leaves: the wind-up (and the blend in) squeezed into {@code castTicks}.
     */
    public static float castAnimationSpeed(int castTicks) {
        return (VillagerAnimationState.BOLT_RELEASE_TICKS + VillagerAnimationState.WORK_BLEND_TICKS)
                / (float) Math.max(1, castTicks);
    }

    /** Seconds per bolt, cast plus pause, for the skill panel. */
    public static double secondsPerBolt(int level) {
        return (castTicks(level) + recoveryTicks(level)) / 20.0;
    }

    // --- mana ------------------------------------------------------------------

    public static float maxMana(int level) {
        return lerp(NOVICE_MAX_MANA, MASTER_MAX_MANA, level);
    }

    public static float manaPerTick(int level) {
        return lerp(NOVICE_MANA_PER_SECOND, MASTER_MANA_PER_SECOND, level) / 20f;
    }

    // --- spells ----------------------------------------------------------------

    public static boolean hasFrostNova(int level) { return level >= FROST_NOVA_LEVEL; }

    public static boolean hasBlink(int level) { return level >= BLINK_LEVEL; }

    public static boolean hasWard(int level) { return level >= WARD_LEVEL; }

    public static boolean hasChainSpark(int level) { return level >= CHAIN_SPARK_LEVEL; }

    public static boolean hasStarfall(int level) { return level >= STARFALL_LEVEL; }

    public static float novaDamage(int level) {
        return lerp(NOVICE_NOVA_DAMAGE, MASTER_NOVA_DAMAGE, level);
    }

    public static int novaSlowTicks(int level) {
        return Math.round(lerp(NOVICE_NOVA_SLOW_TICKS, MASTER_NOVA_SLOW_TICKS, level));
    }

    /** The Absorption amplifier a ward gives: 0 (4 health) or 1 (8 health). */
    public static int wardAmplifier(int level) {
        return level >= STRONG_WARD_LEVEL ? 1 : 0;
    }

    /** Health a ward soaks up before it breaks. */
    public static float wardHealth(int level) {
        return 4f * (wardAmplifier(level) + 1);
    }

    /** Mobs one Chain Spark hits, the first included: 0 before it is learnt. */
    public static int chainHits(int level) {
        if (!hasChainSpark(level)) return 0;
        return level >= CHAIN_FULL_LEVEL ? CHAIN_FULL_JUMPS : CHAIN_FIRST_JUMPS;
    }

    public static float chainDamage(int level) {
        return lerp(NOVICE_CHAIN_DAMAGE, MASTER_CHAIN_DAMAGE, level);
    }

    /** Damage the spark does to the {@code hit}th mob it reaches, counting from 0. */
    public static float chainDamageAt(int level, int hit) {
        return chainDamage(level) * (float) Math.pow(CHAIN_FALLOFF, Math.max(0, hit));
    }

    /** Stars in one Starfall: one per mob in the pack, from three to five. */
    public static int starCount(int mobsInPack) {
        return Math.clamp(mobsInPack, STARFALL_MIN_STARS, STARFALL_MAX_STARS);
    }
}
