package com.alex.test.village.villagers.guard;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.village.brain.EveningSchedule;

import java.util.UUID;

/**
 * Shared guard balance. One skill level, 1 to 100, drives every effect. Each
 * stat improves a little every level; four techniques unlock at fixed levels:
 *
 * <pre>
 *   level   technique
 *   1       Shield block    -- raises a shield when pressed; once up it blocks every hit from the front
 *                              (temporarily from level 1; the meter still climbs from 25)
 *   50      Sweeping strike -- hits also cut hostiles next to the target
 *   75      Shield bash     -- a parry (block just as the shield comes up) or every 4th hit knocks back and stuns
 *   100     Second wind     -- once a day, heals instead of falling back
 * </pre>
 *
 * <p>Experience: a day's first {@value #FULL_DAY_EXPERIENCE} raw experience pays in
 * full, the rest a fifth ({@code stats.DailyJobExperience}). A peaceful day
 * of duty with a stand drill fills it, so level 100 takes about 80 in-game
 * days for a guard, captain or archer (range practice pays like a drill)
 * learning at 1x; duty alone about 117, shadow
 * drills about 100. Fights fill the day sooner but cannot rush it. Intelligence ahead of the level speeds that up to 1.25x; a
 * level past it slows ever more ({@code IntelligenceLearning}), so a recruit
 * that never reads levels a long way past its intelligence only very slowly.
 */
public final class GuardBalance {
    public static final int MAX_LEVEL = 100;

    /**
     * Where the guard meter starts its climb to 100; below it a guard has the
     * novice meter.
     */
    public static final int SHIELD_LEVEL = 25;
    /** Level a guard may carry and raise a shield from. Temporarily 1 (was {@link #SHIELD_LEVEL}). */
    public static final int SHIELD_UNLOCK_LEVEL = 1;
    public static final int SWEEP_LEVEL = 50;
    public static final int BASH_LEVEL = 75;
    public static final int SECOND_WIND_LEVEL = 100;

    /** In-game days to level 100 learning at 1x, with a full day every day. */
    public static final int DAYS_TO_MAX = 80;
    /** Raw experience a day that pays in full: level 100's 3,940 over about 80 days. */
    public static final int FULL_DAY_EXPERIENCE = 49;
    /** What experience past the day's full amount still pays: a fifth. */
    public static final float OVER_FULL_DAY_SHARE = 0.2f;
    /** Minutes on duty in a normal shift, meals taken out; an estimate GUARD_DAY checks. */
    private static final float DUTY_MINUTES_PER_DAY = 9.8f;
    /** Minutes at an armor stand in a normal drill window; an estimate GUARD_DAY checks. */
    private static final float DRILL_MINUTES_PER_DAY = 2.25f;
    /**
     * A minute on duty, sized so a normal shift plus a stand drill (worth
     * twice as much a minute) is exactly the full day: about 3.4.
     */
    public static final float EXPERIENCE_PER_DUTY_MINUTE = FULL_DAY_EXPERIENCE
            / (DUTY_MINUTES_PER_DAY + 2f * DRILL_MINUTES_PER_DAY);
    public static final int DUTY_XP_INTERVAL_TICKS = 1200;
    /** A landed hit: as much as a minute on duty. */
    public static final float EXPERIENCE_PER_HIT = EXPERIENCE_PER_DUTY_MINUTE;
    /** A kill, on top of its hit: three minutes on duty. */
    public static final float EXPERIENCE_PER_KILL = 3f * EXPERIENCE_PER_DUTY_MINUTE;
    /** Barracks drill at an armor stand: guard (or captain) experience per minute drilled, twice duty. */
    public static final float DRILL_EXPERIENCE_PER_MINUTE = 2f * EXPERIENCE_PER_DUTY_MINUTE;
    /** A shadow drill, swinging at the air, earns this share of a stand drill's experience. */
    public static final float SHADOW_DRILL_SHARE = 0.25f;
    /** Shadow fighting another guard, swing for swing, earns this share. */
    public static final float PAIRED_SHADOW_SHARE = 0.5f;
    private static final float XP_LINEAR = 20f;
    private static final float XP_SQUARE = 0.2f;

    /** A villager's base health; matches NpcAttributes. */
    public static final double BASE_HEALTH = 30.0;
    /** Extra health at level 100 over that base. */
    public static final double MAX_BONUS_HEALTH = 15.0;
    private static final int NOVICE_ATTACK_TICKS = 24;
    private static final int MASTER_ATTACK_TICKS = 18;
    // --- Drill at an armor stand: practice, so slower than a fight. The slash
    // clip is 41 ticks with its blend; a recruit plays it about as made, a
    // master half again as fast. Then a breath before the next swing.
    private static final int NOVICE_DRILL_SLASH_TICKS = 37;
    private static final int MASTER_DRILL_SLASH_TICKS = 26;
    private static final int NOVICE_DRILL_PAUSE_TICKS = 10;
    private static final int MASTER_DRILL_PAUSE_TICKS = 4;
    private static final double NOVICE_RESPONSE_SPEED = 1.45;
    private static final double MASTER_RESPONSE_SPEED = 1.60;
    /** Retreat health is rolled between these every day, whatever the level. */
    private static final double MIN_RETREAT_HEALTH = 0.12;
    private static final double MAX_RETREAT_HEALTH = 0.20;
    private static final int RETREAT_HEALTH_STEPS = 800;
    private static final long SALT_RETREAT = 0x3E7A_EA71L;
    /** Out of a fight: one health every 80 ticks at level 1, twice as often at 100. */
    private static final int NOVICE_REGEN_TICKS = 80;
    /** How long since the last hit taken before healing starts. */
    public static final int REGEN_DELAY_TICKS = 200;

    // --- Shield stance: once up (SHIELD_UP_TICKS after raising) it blocks every
    // hit from the front, slows the guard to a walk and keeps it from swinging;
    // the guard meter is what the blocked damage is drawn from. The meter runs
    // from level 25 to 100.
    public static final float GUARD_METER_NOVICE = 12f;
    public static final float GUARD_METER_MASTER = 30f;
    /** A block this soon after the shield comes up is a parry (level 75). */
    public static final int PARRY_WINDOW_TICKS = 5;
    /** Share of a blocked blow that gets through past what was left in the meter when it runs dry. */
    public static final float METER_OVERFLOW_SHARE = 0.25f;
    /** Shield lowered and the guard staggered for this long after the meter runs dry. */
    public static final int GUARD_BROKEN_TICKS = 40;
    /** A hit from an axe disables a raised shield for this long, as in vanilla. */
    public static final int SHIELD_DISABLED_TICKS = 100;
    /** Meter regained per tick while the shield is lowered (12 empty to full in ~5 s). */
    public static final float METER_REGEN_PER_TICK = 0.125f;
    /** Walking pace behind a raised shield, as a share of the usual response speed. */
    public static final double RAISED_SPEED_FACTOR = 0.6;
    /** A raised shield is lowered to swing after being held this long (ticks). */
    public static final int RAISED_HOLD_TICKS = 24;
    /**
     * The client's blend into the shield clips (shield_guard_*, shield_cover_*,
     * baked by tools/shield/ShieldClips.java): short, so the shield snaps up.
     * The work clips blend for {@link VillagerAnimationState#WORK_BLEND_TICKS}.
     */
    public static final int SHIELD_BLEND_TICKS = 2;
    /** Length of the raise clip (0.2 s) before its hold loop takes over. */
    public static final int SHIELD_RAISE_CLIP_TICKS = 4;
    /** Client: after this long up, a stance or walk change goes straight to the hold, without raising again. */
    public static final int SHIELD_RAISE_SHOW_TICKS = SHIELD_BLEND_TICKS + SHIELD_RAISE_CLIP_TICKS;
    /** Clip time at which the shield is up in front (most of the way, overshoot at 0.13 s): from here on it blocks. */
    public static final double SHIELD_UP_CLIP_SECONDS = 0.1;
    /**
     * Window of clip time a blocked arrow should land in: the shield settled in
     * front. The clips hold it up from 0.2 s on, so the window is wide; its start
     * is when the guard raises for a drawn bow.
     */
    public static final double SHOT_HIT_EARLIEST_CLIP_SECONDS = 0.25;
    public static final double SHOT_HIT_LATEST_CLIP_SECONDS = 0.6;
    /**
     * Ticks from raising the shield until it takes hits: the client's blend into
     * the pose and the clip up to {@link #SHIELD_UP_CLIP_SECONDS} -- 4. A shield
     * that blocked sooner did so while the animation still showed it on its way
     * up. Every raise waits this long. (The old mocap clip took 13.)
     */
    public static final int SHIELD_UP_TICKS = (int) Math.ceil(SHIELD_BLEND_TICKS + SHIELD_UP_CLIP_SECONDS * 20.0);
    /** First whole tick after raising at which the clip has passed {@link #SHOT_HIT_EARLIEST_CLIP_SECONDS} -- 7. */
    public static final int SHOT_HIT_EARLIEST_TICKS = (int) Math.ceil(SHIELD_BLEND_TICKS + SHOT_HIT_EARLIEST_CLIP_SECONDS * 20.0);
    /** Last whole tick after raising still before {@link #SHOT_HIT_LATEST_CLIP_SECONDS} -- 14. */
    public static final int SHOT_HIT_LATEST_TICKS = (int) Math.floor(SHIELD_BLEND_TICKS + SHOT_HIT_LATEST_CLIP_SECONDS * 20.0);
    /**
     * Shortest time a raised shield stays up: the blend and the raise (6 ticks)
     * plus 4 for the held pose to read -- 10. Lowered sooner -- a timed block
     * answered at once, a passing arrow -- the arm flicked up and straight back
     * down and the pose never read.
     */
    public static final int SHIELD_MIN_RAISE_TICKS = SHIELD_RAISE_SHOW_TICKS + 4;
    /** The guard puts its shield up for ranged attackers farther than this and closer than SHIELD_ADVANCE_MAX. */
    public static final double SHIELD_ADVANCE_MIN = 5.0;
    public static final double SHIELD_ADVANCE_MAX = 24.0;
    /** Melee: a guard hurt below this share of its health, or facing two, guards up. */
    public static final double GUARD_UP_HEALTH_FRACTION = 0.70;
    public static final int GUARD_UP_HOSTILES = 2;
    public static final double HOSTILE_PRESSURE_RADIUS = 4.0;
    /** Below this share of the meter the guard stops raising until it has recovered. */
    public static final float METER_MIN_TO_RAISE = 0.25f;

    /** Where in the mixamo_slash clip the blade passes the target: 0.87 s of 1.67 s (the arm winds up to 0.73 s). */
    public static final float SLASH_CONTACT = 0.52f;

    /**
     * How far through a swing the blade reaches the target. The client does not
     * start the slash at once: it blends in from the ready pose over
     * {@link VillagerAnimationState#WORK_BLEND_TICKS} animation ticks first,
     * which GeckoLib plays at the clip's speed. So a swing is that blend plus the
     * clip, sped up to fit the swing ({@code NpcEntity.startAttackAnimation}), and
     * the blade arrives at (blend + 52% of the clip) / (blend + clip) -- about
     * 61%, 10 ticks of a 16-tick swing, 7 of 12. Timing the blow at 52% of the
     * swing ignored the blend, so the mob flinched about 0.2 s before the blade
     * got there.
     */
    public static final float STRIKE_POINT = (VillagerAnimationState.WORK_BLEND_TICKS
            + SLASH_CONTACT * VillagerAnimationState.cycleTicks(VillagerAnimationState.ATTACK))
            / (float) (VillagerAnimationState.WORK_BLEND_TICKS
            + VillagerAnimationState.cycleTicks(VillagerAnimationState.ATTACK));

    /** Share of the usual knockback a guard, captain or archer takes from a hit. */
    public static final float KNOCKBACK_TAKEN_FACTOR = 0.5f;
    /** For this long after being hurt a guard's melee reach is longer, so a shove does not cost it its swing. */
    public static final int RECOVERY_REACH_TICKS = 30;
    /** A counter-swing starts this much farther out than an ordinary one; it lunges in to land. */
    public static final double RECOVERY_REACH_BONUS = 1.5;
    /** A guard holds its ready pose between swings while a live enemy is this close (blocks). */
    public static final double READY_POSE_RADIUS = 3.0;
    /** How far a guard or captain notices a hostile on its own (blocks); a captain's Veteran's eye widens it. */
    public static final double SPOT_RADIUS = 10.0;
    /** On alert a guard or captain notices hostiles this much farther out. */
    public static final double ALERT_SPOT_BONUS = 4.0;

    public static final float SWEEP_DAMAGE_FRACTION = 0.4f;
    public static final double SWEEP_RADIUS = 1.5;
    public static final int BASH_EVERY_HITS = 4;
    public static final double BASH_KNOCKBACK = 1.2;
    public static final int BASH_STUN_TICKS = 30;
    public static final float SECOND_WIND_HEAL_FRACTION = 0.4f;

    private GuardBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    /** Total experience for level L = 20·(L−1) + 0.2·(L−1)²; the last level costs ~3× the first. */
    public static float experienceForLevel(int level) {
        int steps = Math.clamp(level, 1, MAX_LEVEL) - 1;
        return XP_LINEAR * steps + XP_SQUARE * steps * steps;
    }

    public static double bonusHealth(int level) {
        return MAX_BONUS_HEALTH * progress(level);
    }

    /** Sword reach, from the defender's centre to the mob's hitbox edge: 2.25 blocks at level 1 to 2.75 at 100. */
    private static final double NOVICE_SWING_REACH = 2.25;
    private static final double MASTER_SWING_REACH = 2.75;

    /** How far away a swing starts (blocks to the mob's hitbox edge); grows a little every level. */
    public static double swingReach(int level) {
        return NOVICE_SWING_REACH + (MASTER_SWING_REACH - NOVICE_SWING_REACH) * progress(level);
    }

    /** Ticks from one sword swing to the next: 24 (1.2 s) at level 1 down to 18 (0.9 s) at 100. */
    public static int attackTicks(int level) {
        return (int) Math.round(NOVICE_ATTACK_TICKS
                - (NOVICE_ATTACK_TICKS - MASTER_ATTACK_TICKS) * progress(level));
    }

    /**
     * How far along the drill pace is: most of it comes in the first levels,
     * so a level 10 already swings visibly quicker than a fresh recruit.
     */
    private static double drillProgress(int level) {
        return Math.sqrt(progress(level));
    }

    /** A drill slash at the stand: 37 ticks for a recruit, 34 at level 10, 29 at 50, 26 at 100. */
    public static int drillSlashTicks(int level) {
        return (int) Math.round(NOVICE_DRILL_SLASH_TICKS
                - (NOVICE_DRILL_SLASH_TICKS - MASTER_DRILL_SLASH_TICKS) * drillProgress(level));
    }

    /** The breath after a drill slash before the next: 10 ticks for a recruit down to 4 at level 100. */
    public static int drillPauseTicks(int level) {
        return (int) Math.round(NOVICE_DRILL_PAUSE_TICKS
                - (NOVICE_DRILL_PAUSE_TICKS - MASTER_DRILL_PAUSE_TICKS) * drillProgress(level));
    }

    /** Running speed when answering a threat, as a multiple of the walking speed. */
    public static double responseSpeed(int level) {
        return NOVICE_RESPONSE_SPEED
                + (MASTER_RESPONSE_SPEED - NOVICE_RESPONSE_SPEED) * progress(level);
    }

    /**
     * Health fraction below which a defender -- guard, captain or archer --
     * breaks off a fight: 12% to 20%, rolled for each defender once a day.
     * Level plays no part. Fixed for the whole day so it never shifts in the
     * middle of a fight.
     */
    public static double retreatHealth(UUID npcId, long dayTime) {
        int roll = EveningSchedule.sharedRoll(npcId, EveningSchedule.dayIndex(dayTime), SALT_RETREAT,
                RETREAT_HEALTH_STEPS + 1);
        return MIN_RETREAT_HEALTH + (MAX_RETREAT_HEALTH - MIN_RETREAT_HEALTH) * roll / RETREAT_HEALTH_STEPS;
    }

    /** Ticks per health point healed out of a fight: 80 at level 1, 40 at 100. */
    public static int regenTicks(int level) {
        return (int) Math.round(NOVICE_REGEN_TICKS / (1.0 + progress(level)));
    }

    public static boolean hasShieldBlock(int level) {
        return level >= SHIELD_UNLOCK_LEVEL;
    }

    /** Progress from shield block (25) to 100, 0..1. */
    private static double shieldProgress(int level) {
        return (Math.clamp(level, SHIELD_LEVEL, MAX_LEVEL) - SHIELD_LEVEL)
                / (double) (MAX_LEVEL - SHIELD_LEVEL);
    }

    /** What the guard meter holds: 12 at 25, 30 at 100. */
    public static float guardMeter(int level) {
        return (float) (GUARD_METER_NOVICE
                + (GUARD_METER_MASTER - GUARD_METER_NOVICE) * shieldProgress(level));
    }

    public static boolean hasSweep(int level) {
        return level >= SWEEP_LEVEL;
    }

    public static boolean hasBash(int level) {
        return level >= BASH_LEVEL;
    }

    public static boolean hasSecondWind(int level) {
        return level >= SECOND_WIND_LEVEL;
    }
}
