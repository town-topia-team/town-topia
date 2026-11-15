package com.alex.test.village.villagers.guard;

import com.alex.test.village.villagers.core.Profession;

/**
 * Which numbers a sword-and-patrol defender fights with. Guards and captains
 * share {@code GuardActivity}; this hands each its own balance so the shared
 * code never asks which one it is running.
 */
public enum CombatRank {
    GUARD,
    CAPTAIN;

    public static CombatRank of(Profession profession) {
        return profession == Profession.CAPTAIN ? CAPTAIN : GUARD;
    }

    public Profession profession() {
        return this == CAPTAIN ? Profession.CAPTAIN : Profession.GUARD;
    }

    public double bonusHealth(int level) {
        return this == CAPTAIN ? CaptainBalance.bonusHealth(level) : GuardBalance.bonusHealth(level);
    }

    /** Sword reach at this level; guards and captains share the curve, each by its own level. */
    public double swingReach(int level) {
        return GuardBalance.swingReach(level);
    }

    public int attackTicks(int level) {
        return this == CAPTAIN ? CaptainBalance.attackTicks(level) : GuardBalance.attackTicks(level);
    }

    public double responseSpeed(int level) {
        return this == CAPTAIN ? CaptainBalance.responseSpeed(level) : GuardBalance.responseSpeed(level);
    }

    public int regenTicks(int level) {
        return this == CAPTAIN ? CaptainBalance.regenTicks(level) : GuardBalance.regenTicks(level);
    }

    public boolean hasShieldBlock(int level) {
        return this == CAPTAIN || GuardBalance.hasShieldBlock(level);
    }

    public float guardMeter(int level) {
        return this == CAPTAIN ? CaptainBalance.guardMeter(level) : GuardBalance.guardMeter(level);
    }

    /** A parry (block just as the shield comes up) needs the guard's bash level; a captain's shockwave level. */
    public boolean canParry(int level) {
        return hasBash(level);
    }

    public boolean hasSweep(int level) {
        return this == CAPTAIN || GuardBalance.hasSweep(level);
    }

    public float sweepFraction() {
        return this == CAPTAIN ? CaptainBalance.SWEEP_DAMAGE_FRACTION : GuardBalance.SWEEP_DAMAGE_FRACTION;
    }

    public boolean hasBash(int level) {
        return this == CAPTAIN ? CaptainBalance.hasShockwave(level) : GuardBalance.hasBash(level);
    }

    public int bashEveryHits() {
        return this == CAPTAIN ? CaptainBalance.BASH_EVERY_HITS : GuardBalance.BASH_EVERY_HITS;
    }

    /** A captain's bash staggers everything around the target, not just the target. */
    public boolean bashIsShockwave() {
        return this == CAPTAIN;
    }

    /** Second winds a day: 0 until the capstone, then 1 for a guard and 2 for a captain. */
    public int secondWindsPerDay(int level) {
        if (this == CAPTAIN) return CaptainBalance.isUnbreakable(level) ? CaptainBalance.SECOND_WINDS_PER_DAY : 0;
        return GuardBalance.hasSecondWind(level) ? 1 : 0;
    }
}
