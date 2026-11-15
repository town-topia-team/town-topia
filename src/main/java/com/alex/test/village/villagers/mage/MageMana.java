package com.alex.test.village.villagers.mage;

/**
 * A mage's mana: what it held when it last cast, and when. What it holds now
 * is worked out from the time since, so mana comes back while the mage sleeps,
 * eats or walks home just as it does in a fight, with nothing ticking it.
 * Pure: the brain data holds the two numbers (see {@link MageReagents#loadMana}).
 */
public final class MageMana {

    private float stored;
    private long storedAt;

    public MageMana(float stored, long storedAt) {
        this.stored = Math.max(0f, stored);
        this.storedAt = storedAt;
    }

    /** A new mage starts full. */
    public static MageMana full(int level, long now) {
        return new MageMana(MageBalance.maxMana(level), now);
    }

    /** Mana held at {@code now}: what was stored plus what came back since, up to the most it can hold. */
    public float current(long now, int level) {
        float max = MageBalance.maxMana(level);
        // A clock that went backwards (a world restored from a backup) gives nothing back.
        long elapsed = Math.max(0L, now - storedAt);
        return Math.min(max, stored + elapsed * MageBalance.manaPerTick(level));
    }

    public boolean canAfford(float cost, long now, int level) {
        return current(now, level) >= cost;
    }

    /** Takes {@code cost} if the mage has it; false (and nothing taken) if not. */
    public boolean trySpend(float cost, long now, int level) {
        float have = current(now, level);
        if (have < cost) return false;
        stored = have - Math.max(0f, cost);
        storedAt = now;
        return true;
    }

    /** Share of the bar filled, 0..1, for the info screen. */
    public float fraction(long now, int level) {
        return current(now, level) / MageBalance.maxMana(level);
    }

    public float stored() {
        return stored;
    }

    public long storedAt() {
        return storedAt;
    }
}
