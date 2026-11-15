package com.alex.test.village.stats;

import com.alex.test.village.brain.Personality;
import net.minecraft.nbt.CompoundTag;

/**
 * Mutable stat state for one villager.
 *
 * <p>The fields remain public for the existing lightweight scoring/read paths.
 * Stat changes should go through this class so hunger and wellbeing rules
 * stay in one organized package.
 */
public class VillagerStats {
    public float hunger = 100f;
    public float wellbeing = 75f;
    /** Happiness good food has given on {@link #foodJoyDay}; see {@link WellbeingSystem#onFoodJoy}. */
    float foodJoyToday;
    long foodJoyDay = -1;
    /** How much higher than usual tonight's sleep refills, from a good evening (0-15). */
    public float eveningBonus;
    /** The bard's share of {@link #eveningBonus} is already counted tonight. */
    boolean eveningBardCounted;
    /** Tonight's sleep gain still to come, out of {@link #sleepGainTotal}; see {@link WellbeingSystem#startNightSleep}. */
    float sleepGainLeft;
    float sleepGainTotal;
    /** Tonight's sleep gain has been rolled; cleared by the new day. */
    boolean sleepRolledTonight;
    /** Recent meals, favourite and disliked food. */
    public final DietMemory diet = new DietMemory();
    /** Cleric heals a starving villager may get before it starves to death (user 10-19). */
    public static final int STARVING_HEALS = 3;
    private static final String TAG_STARVING_HEALS = "starving_heals_left";
    /**
     * Heals still allowed while hunger is 0: a cleric's orb or splash, a
     * battle blessing or Guardian Angel each use one. Back to
     * {@link #STARVING_HEALS} whenever hunger is above 0 ({@link HealthSystem#tick}).
     */
    public int starvingHealsLeft = STARVING_HEALS;

    private final HungerSystem hungerSystem = new HungerSystem();
    private final WellbeingSystem wellbeingSystem = new WellbeingSystem();

    public HungerSystem hungerRules() {
        return hungerSystem;
    }

    public WellbeingSystem wellbeingRules() {
        return wellbeingSystem;
    }

    /**
     * Passive per-tick drift. Call once per brain tick with the villager's
     * current activity status so the correct drains/gains are applied.
     */
    public void tick(Personality personality, StatActivityStatus status, boolean sleptInBed) {
        hungerSystem.tick(this, status);
        boolean lowHungerPenalty = hungerSystem.shouldApplyWellbeingPenalty(this, status);
        wellbeingSystem.tick(this, personality, status, sleptInBed, lowHungerPenalty);
        clamp();
    }

    public void onConversationComplete(boolean noLimits) {
        wellbeingSystem.onConversationComplete(this, noLimits);
        clamp();
    }

    public void onMayorConversationComplete(float wellbeingBoost) {
        wellbeingSystem.onMayorConversationComplete(this, wellbeingBoost);
        clamp();
    }

    public void onFoodEaten(float nutritionValue, float wellbeingBoost) {
        hungerSystem.onFoodEaten(this, nutritionValue);
        wellbeingSystem.onFoodEaten(this, wellbeingBoost);
        clamp();
    }

    /** Hunger spent on work or walking, on top of the passive drain. */
    public void onExertion(float amount) {
        hungerSystem.onExertion(this, amount);
        clamp();
    }

    /** Happiness from a meal; good food is capped per day. Returns what was applied. */
    public float onFoodJoy(float joy, long day) {
        float applied = wellbeingSystem.onFoodJoy(this, joy, day);
        clamp();
        return applied;
    }

    public void onFleeStarted() {
        wellbeingSystem.onFleeStarted(this);
        clamp();
    }

    public boolean sleepRolledTonight() {
        return sleepRolledTonight;
    }

    /** First lie-down tonight; {@code roll} is between the sleep gain min and max. */
    public void startNightSleep(float roll, boolean inBed) {
        wellbeingSystem.startNightSleep(this, roll, inBed);
    }

    /** {@code ticks} of tonight's sleep at once, for the away calculator. */
    public void sleepFor(long ticks) {
        wellbeingSystem.sleepFor(this, ticks);
        clamp();
    }

    /** The night ended properly (morning, or a player skipped it): add what is still owed. */
    public void finishNightSleep() {
        wellbeingSystem.finishNightSleep(this);
        clamp();
    }

    public void clearNightSleep() {
        wellbeingSystem.clearNightSleep(this);
    }

    public void addEveningBonus(float amount) {
        wellbeingSystem.addEveningBonus(this, amount);
    }

    public void onEveningBard() {
        wellbeingSystem.onEveningBard(this);
    }

    public void clearEveningBonus() {
        wellbeingSystem.clearEveningBonus(this);
    }

    /**
     * A wellbeing delta with no named event behind it — a raid grinding morale
     * down for as long as it lasts, for instance. Anything with a real cause
     * should use that cause's own method, so this stays the exception.
     */
    public void addWellbeing(float delta) {
        wellbeing += delta;
        clamp();
    }

    public void clamp() {
        hunger = StatMath.clampPercent(hunger);
        wellbeing = StatMath.clampPercent(wellbeing);
    }

    public void save(CompoundTag tag) {
        hungerSystem.save(this, tag);
        wellbeingSystem.save(this, tag);
        diet.save(tag);
        tag.putInt(TAG_STARVING_HEALS, starvingHealsLeft);
    }

    public void load(CompoundTag tag) {
        hungerSystem.load(this, tag);
        wellbeingSystem.load(this, tag);
        diet.load(tag);
        if (tag.contains(TAG_STARVING_HEALS)) {
            starvingHealsLeft = Math.max(0, Math.min(STARVING_HEALS, tag.getInt(TAG_STARVING_HEALS)));
        }
        clamp();
    }
}
