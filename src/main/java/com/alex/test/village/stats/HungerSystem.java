package com.alex.test.village.stats;

import com.alex.test.village.brain.Personality;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.food.FoodProperties;

/** Rules and thresholds for the villager hunger stat. */
public final class HungerSystem {

    public static final String TAG_HUNGER = "hunger";

    private static final float CRITICAL_HUNGER = 20f;
    private static final float LOW_HUNGER = 45f;
    private static final float ROUTINE_WORK_HUNGER_FLOOR = 10f;
    private static final float MEAL_TARGET_HUNGER = 60f;
    private static final float FETCH_TARGET_HUNGER = 65f;
    private static final float SLEEP_PREPARATION_FLOOR = 55f;
    /** Below this, awake, wellbeing falls (also the away calculator's "hungry" line). */
    public static final float LOW_HUNGER_WELLBEING_THRESHOLD = 25f;

    /**
     * Awake drain before any effort. It was 85 per 12,000 ticks for everyone;
     * now 55, and the rest is charged by effort ({@link #workExertionPerTick},
     * {@link #WALK_HUNGER_PER_BLOCK}), so an average worker still burns about
     * 127 a day while a miner burns more and a child less.
     */
    private static final float HUNGER_PER_TICK = 55f / 12_000f;
    /** Extra drain per working tick for a level-1 villager in a job of intensity 1. */
    private static final float WORK_EXERTION_PER_TICK = 45f / 12_000f;
    /** Hunger per block walked or run, on top of everything else. */
    public static final float WALK_HUNGER_PER_BLOCK = 0.04f;
    /** A level-100 worker spends this much less effort on the same work. */
    private static final float MAX_SKILL_EXERTION_SAVING = 0.4f;
    private static final float SLEEP_HUNGER_PER_TICK = HUNGER_PER_TICK * 0.25f;
    private static final int WAKE_HUNGER_PENALTY_GRACE_TICKS = 1_200;
    private static final int SLEEP_HUNGER_RETRY_COOLDOWN_TICKS = 200;

    private int hungerPenaltyGraceTicks;
    private boolean wasSleeping;

    public void tick(VillagerStats stats, StatActivityStatus status) {
        boolean sleeping = status == StatActivityStatus.SLEEPING;
        if (wasSleeping && !sleeping) {
            hungerPenaltyGraceTicks = WAKE_HUNGER_PENALTY_GRACE_TICKS;
        }
        wasSleeping = sleeping;

        stats.hunger -= sleeping ? SLEEP_HUNGER_PER_TICK : HUNGER_PER_TICK;
        if (hungerPenaltyGraceTicks > 0) hungerPenaltyGraceTicks--;
    }

    public boolean shouldApplyWellbeingPenalty(VillagerStats stats, StatActivityStatus status) {
        return status != StatActivityStatus.SLEEPING
                && hungerPenaltyGraceTicks == 0
                && stats.hunger < LOW_HUNGER_WELLBEING_THRESHOLD;
    }

    public void onFoodEaten(VillagerStats stats, float nutritionValue) {
        stats.hunger = Math.min(100f, stats.hunger + nutritionValue);
    }

    public boolean isCritical(VillagerStats stats) {
        return stats.hunger <= CRITICAL_HUNGER;
    }

    public boolean isLow(VillagerStats stats) {
        return stats.hunger <= LOW_HUNGER;
    }

    public boolean canRoutineWork(VillagerStats stats) {
        return stats.hunger > ROUTINE_WORK_HUNGER_FLOOR;
    }

    public boolean needsSleepPreparation(VillagerStats stats) {
        return stats.hunger < SLEEP_PREPARATION_FLOOR;
    }

    public boolean isBelow(VillagerStats stats, float target) {
        return stats.hunger < target;
    }

    public float sleepPreparationFloor() {
        return SLEEP_PREPARATION_FLOOR;
    }

    public int sleepRetryCooldownTicks() {
        return SLEEP_HUNGER_RETRY_COOLDOWN_TICKS;
    }

    public float mealTargetHunger() {
        return MEAL_TARGET_HUNGER;
    }

    /**
     * Hunger drained per tick while awake. Exposed (rather than duplicated as a
     * copy-pasted literal) for the away calculator ({@code economy/away/AwayHealth}), which needs the
     * exact rate for the hunger of each part of an away day.
     */
    public float hungerPerTickAwake() {
        return HUNGER_PER_TICK;
    }

    /** Hunger drained per tick while asleep. See {@link #hungerPerTickAwake()}. */
    public float hungerPerTickAsleep() {
        return SLEEP_HUNGER_PER_TICK;
    }

    /** How hard a job works the body, relative to farming. */
    public static float workIntensity(Profession profession) {
        return switch (profession) {
            case MINER -> 1.4f;
            case LUMBERJACK -> 1.3f;
            case BUTCHER, BLACKSMITH -> 1.2f;
            case GUARD, CAPTAIN, BUILDER -> 1.1f;
            case FARMER, RANCHER, DRUID -> 1.0f;
            case ARCHER, MAGE -> 0.9f;
            case COOK, BARD -> 0.8f;
            case CLERIC -> 0.7f;
            case TRADESMAN, ENCHANTER, TEACHER -> 0.6f;
            case CHILD -> 0.5f;
            default -> 1.0f;
        };
    }

    /** Extra hunger per working tick: the job's intensity, eased by skill. */
    public static float workExertionPerTick(Profession profession, int skillLevel) {
        float progress = (Math.clamp(skillLevel, 1, 100) - 1) / 99f;
        return WORK_EXERTION_PER_TICK * workIntensity(profession)
                * (1f - MAX_SKILL_EXERTION_SAVING * progress);
    }

    /** Hunger spent on effort: work or walking. */
    public void onExertion(VillagerStats stats, float amount) {
        stats.hunger -= amount;
    }

    /**
     * Villagers keep meal times: breakfast after waking, lunch around noon,
     * and dinner as a step of the evening routine. At a meal they eat their
     * fill; between meals they only snack once hunger falls to
     * {@value #SNACK_HUNGER}, and only back up to the ordinary meal target.
     */
    public static final float SNACK_HUNGER = 30f;
    /** A proper meal is worth sitting down to below this. */
    public static final float MEAL_TIME_HUNGER = 75f;
    /** What a proper meal (breakfast, lunch, dinner) fills up to. */
    public static final float PROPER_MEAL_TARGET = 90f;
    private static final int BREAKFAST_END = 1_500;
    public static final int LUNCH_START = 5_500;
    private static final int LUNCH_END = 7_000;

    /** Breakfast (waking until mid-morning) or lunch, by time of day. */
    public static boolean isMealTime(long dayTime) {
        long t = Math.floorMod(dayTime, 24_000L);
        boolean breakfast = t >= 23_200 || t < BREAKFAST_END;
        boolean lunch = t >= LUNCH_START && t < LUNCH_END;
        return breakfast || lunch;
    }

    public float eatScore(VillagerStats stats, boolean hasFood, Personality personality,
                          boolean mealTime) {
        float threshold = mealTime ? MEAL_TIME_HUNGER : SNACK_HUNGER;
        return hasFood && stats.hunger < threshold
                ? (100f - stats.hunger) * personality.appetite
                : 0f;
    }

    public float fetchScore(
            VillagerStats stats,
            boolean hasFood,
            Personality personality,
            boolean fetchAvailable) {
        return !hasFood && stats.hunger < FETCH_TARGET_HUNGER && fetchAvailable
                ? (100f - stats.hunger) * personality.appetite * 0.9f
                : 0f;
    }

    public float nearbyStorageReserveBonus(int carriedFoodCount, double storageDistanceSqr) {
        return isFoodReserveLow(carriedFoodCount) && storageDistanceSqr <= 16.0 ? 30f : 0f;
    }

    public boolean isFoodReserveLow(int carriedFoodCount) {
        return carriedFoodCount <= ProfessionSupplyRules.FOOD_RESERVE_LOW;
    }

    /**
     * Fetch score for keeping a food reserve, whatever the hunger. Empty pockets
     * outrank ordinary work (a guard on shift still wins at 120); a reserve of
     * one to four competes with work and grows more urgent as it shrinks.
     */
    public float foodReserveScore(int carriedFoodCount) {
        if (carriedFoodCount <= 0) return 110f;
        if (!isFoodReserveLow(carriedFoodCount)) return 0f;
        return 25f + 10f * (ProfessionSupplyRules.FOOD_RESERVE_LOW + 1 - carriedFoodCount);
    }

    public void save(VillagerStats stats, CompoundTag tag) {
        tag.putFloat(TAG_HUNGER, stats.hunger);
    }

    public void load(VillagerStats stats, CompoundTag tag) {
        if (tag.contains(TAG_HUNGER)) stats.hunger = tag.getFloat(TAG_HUNGER);
    }

    /**
     * How much hunger one item restores: its vanilla nutrition, scaled.
     *
     * <p>A villager burns about 127 hunger a day, so at the old multiplier of
     * five a loaf of bread was worth 25 and every villager needed five loaves a
     * day. Fourteen villagers meant seventy loaves and two hundred and ten
     * wheat, which three farmers cannot grow — two eighteen-day playthroughs
     * with a farm, a kitchen and a cook never held more than ten food at once
     * and spent most of a fortnight at zero.
     *
     * <p>At eight, bread is worth 40 and the same village needs about
     * forty-five loaves a day. That is inside what three farmers produce, which
     * is the bar the mod is aiming at: three working farmers and a cook feeding
     * themselves and ten more.
     */
    public static final float NUTRITION_TO_HUNGER = 8f;

    public static float hungerRestoredByFood(FoodProperties food) {
        return food.nutrition() * NUTRITION_TO_HUNGER;
    }
}
