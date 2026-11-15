package com.alex.test.village.stats;

/** Pure arithmetic shared by live wellbeing rules and balance tests. */
public final class WellbeingBalance {
    private WellbeingBalance() {}

    public static float workDrain(float workFatigue) {
        return (50f / 12_000f) * workFatigue / 0.85f;
    }

    public static float recover(float current, boolean inBed) {
        return recover(current, inBed, 0f);
    }

    /**
     * The old sleep rule: refill up to 75 in a bed (65 elsewhere), plus tonight's
     * {@code eveningBonus}. Live sleep now adds a rolled amount instead
     * ({@link WellbeingSystem#startNightSleep}); only balance tests still use this.
     */
    public static float recover(float current, boolean inBed, float eveningBonus) {
        float cap = (inBed ? 75f : 65f) + eveningBonus;
        float gain = inBed ? 100f / 12_000f : 75f / 12_000f;
        return current < cap ? Math.min(cap, current + gain) : current;
    }

    public static float migrate(float happiness, float motivation) {
        float happy = Math.clamp(happiness, 0f, 100f);
        float energy = Math.clamp(motivation, 0f, 100f);
        return 0.75f * Math.min(happy, energy) + 0.25f * Math.max(happy, energy);
    }
}
