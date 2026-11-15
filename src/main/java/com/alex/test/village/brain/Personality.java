package com.alex.test.village.brain;

import java.util.Random;
import java.util.UUID;

/**
 * Per-villager decision weights. First rolled from the NPC's UUID, then saved
 * in its brain data ({@code NpcEntity.personality()}), so changing the roll
 * below only changes villagers that have never been loaded.
 */
public final class Personality {

    /** Multiplier on the work score (0.80-1.00). */
    public final float workDrive;
    /** Multiplier on the socialize score (0.70-1.00). */
    public final float sociability;
    /** Multiplier on hunger-driven scores (0.90-1.00). */
    public final float appetite;
    /** Divides job step times, with skill speed (0.80-1.20). */
    public final float workSpeed;
    /** Small variation in wellbeing spent per work tick (0.80-0.90). */
    public final float workFatigue;
    /** Unused; kept so the roll order, and with it every older villager, stays the same (0.50-0.60). */
    public final float workFraction;

    public Personality(UUID npcId) {
        Random r = new Random(npcId.getMostSignificantBits() ^ npcId.getLeastSignificantBits());
        workDrive    = 0.80f + r.nextFloat() * 0.20f;
        sociability  = 0.70f + r.nextFloat() * 0.30f;
        appetite     = 0.90f + r.nextFloat() * 0.10f;
        workFatigue  = 0.80f + r.nextFloat() * 0.1f;
        workFraction = 0.50f + r.nextFloat() * 0.1f;
        workSpeed = 0.8f + r.nextFloat() * 0.4f;
    }

    /** A saved personality, read back as it was rolled. */
    public Personality(float workDrive, float sociability, float appetite,
                       float workSpeed, float workFatigue, float workFraction) {
        this.workDrive = workDrive;
        this.sociability = sociability;
        this.appetite = appetite;
        this.workSpeed = workSpeed;
        this.workFatigue = workFatigue;
        this.workFraction = workFraction;
    }

    /** Historical personality preference retained for diagnostics. */
    public float leisureThreshold() { return (1.0f - workFraction) * 100f; }
}
