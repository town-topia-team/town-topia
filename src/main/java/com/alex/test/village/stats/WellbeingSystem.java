package com.alex.test.village.stats;

import com.alex.test.village.brain.Personality;
import net.minecraft.nbt.CompoundTag;

/** One short-term reserve for willingness to work and contentment. */
public final class WellbeingSystem {
    public static final String TAG_WELLBEING = "wellbeing";
    private static final String LEGACY_HAPPINESS_TAG = "happiness";
    private static final String LEGACY_MOTIVATION_TAG = "motivation";
    /** Wellbeing at or below this is sad: slower walking and working, never no work. */
    public static final float SAD_THRESHOLD = 25f;
    /** Work pace while sad. */
    public static final float SAD_WORK_PACE = 0.75f;
    /**
     * Walking speed while sad. The Mixamo sad walk covers about 44% of the
     * ground the normal walk does per second (0.15 against 0.34 blocks/s,
     * mocap_root.json), so it plays at {@link #SAD_WALK_CLIP_SPEED} and the
     * villager moves at 0.44 x 1.48 = 0.65. The clip's leg swing was then
     * widened by 0.75 / 0.65 (base_villager and dynamic_villagers, legs only)
     * so the stride covers 75%: the upper body keeps the 1.48x pace the author
     * liked and the feet match the ground. (0.55 with the clip at 1.25x was
     * tried 10-03 and was far too slow.)
     */
    public static final float SAD_WALK_PACE = 0.75f;
    /** Playback speed of the sad walk clip, chosen with SAD_WALK_PACE so the feet do not slide. */
    public static final float SAD_WALK_CLIP_SPEED = 1.48f;
    /**
     * A night's sleep adds a rolled amount on top of the current mood (user
     * rule, 2026-10-08) instead of refilling to a fixed level: 40-55 in a bed,
     * {@link #NO_BED_SLEEP_SHARE} of that roll anywhere else.
     */
    public static final float SLEEP_GAIN_MIN = 40f;
    public static final float SLEEP_GAIN_MAX = 55f;
    /** Share of the roll a villager gets sleeping on a Town Hall floor or any spot without a bed. */
    public static final float NO_BED_SLEEP_SHARE = 0.7f;
    /**
     * The night's gain is handed out over this many sleeping ticks, so a nap
     * gives a little and a villager woken early keeps only what it earned. A
     * normal night (bed about 15,300, up at 23,200) is longer; anything left
     * comes at wake-up ({@link #finishNightSleep}).
     */
    private static final float SLEEP_GAIN_SPREAD_TICKS = 7_000f;

    /** Per awake tick while hungry ({@code HungerSystem.LOW_HUNGER_WELLBEING_THRESHOLD}). */
    public static final float HUNGER_PENALTY = 100f / 12_000f;
    private static final float ADMIRE_GAIN = 20f / 12_000f;
    private static final float SOCIAL_GAIN = 25f / 12_000f;
    public static final float WANDER_GAIN = 20f / 12_000f;
    /** Sitting at the tavern counts three times as much as other socializing. */
    private static final float TAVERN_GAIN = 3f * SOCIAL_GAIN;

    /** Most a good evening can add to tonight's sleep gain. */
    public static final float EVENING_BONUS_MAX = 15f;
    public static final float EVENING_BONUS_TAVERN = 5f;
    public static final float EVENING_BONUS_TAVERN_CHAT = 3f;
    public static final float EVENING_BONUS_BARD = 5f;
    private static final String TAG_EVENING_BONUS = "evening_bonus";
    private static final String TAG_EVENING_BARD = "evening_bonus_bard";
    private static final String TAG_SLEEP_GAIN_LEFT = "sleep_gain_left";
    private static final String TAG_SLEEP_GAIN_TOTAL = "sleep_gain_total";
    private static final String TAG_SLEEP_ROLLED = "sleep_rolled";

    public void tick(VillagerStats stats, Personality personality,
                     StatActivityStatus status, boolean sleptInBed, boolean lowHunger) {
        if (lowHunger) stats.wellbeing -= HUNGER_PENALTY;
        switch (status) {
            // Charge time rather than completed jobs: skill cannot make work more tiring.
            case WORKING -> stats.wellbeing -= WellbeingBalance.workDrain(personality.workFatigue);
            case SLEEPING -> stats.wellbeing += takeSleepGain(stats,
                    stats.sleepGainTotal / SLEEP_GAIN_SPREAD_TICKS);
            case ADMIRING -> stats.wellbeing += ADMIRE_GAIN;
            case SOCIALIZING -> stats.wellbeing += SOCIAL_GAIN;
            case AT_TAVERN -> stats.wellbeing += TAVERN_GAIN;
            case WANDERING -> stats.wellbeing += WANDER_GAIN;
            default -> {}
        }
    }

    /**
     * Lying down for the first time tonight: set tonight's gain from
     * {@code roll} (between {@link #SLEEP_GAIN_MIN} and {@link #SLEEP_GAIN_MAX}),
     * cut to {@link #NO_BED_SLEEP_SHARE} without a bed, plus the evening bonus.
     */
    public void startNightSleep(VillagerStats stats, float roll, boolean inBed) {
        stats.sleepGainTotal = (inBed ? roll : roll * NO_BED_SLEEP_SHARE) + stats.eveningBonus;
        stats.sleepGainLeft = stats.sleepGainTotal;
        stats.sleepRolledTonight = true;
    }

    /** {@code ticks} of tonight's sleep at once (the away calculator): what they are worth of the gain. */
    public void sleepFor(VillagerStats stats, long ticks) {
        stats.wellbeing += takeSleepGain(stats, stats.sleepGainTotal / SLEEP_GAIN_SPREAD_TICKS * ticks);
    }

    /** The night ended properly: whatever of tonight's gain is still owed is added now. */
    public void finishNightSleep(VillagerStats stats) {
        stats.wellbeing += takeSleepGain(stats, stats.sleepGainLeft);
    }

    /** A new day: anything not slept tonight is lost and the next sleep rolls again. */
    public void clearNightSleep(VillagerStats stats) {
        stats.sleepGainLeft = 0f;
        stats.sleepGainTotal = 0f;
        stats.sleepRolledTonight = false;
    }

    private static float takeSleepGain(VillagerStats stats, float wanted) {
        float given = Math.clamp(wanted, 0f, stats.sleepGainLeft);
        stats.sleepGainLeft -= given;
        return given;
    }

    /**
     * At or below {@link #SAD_THRESHOLD} a villager is sad. Sadness never stops
     * work (user rule, 2026-10-02): the villager keeps working at
     * {@link #SAD_WORK_PACE} and walks with the sad walk at {@link #SAD_WALK_PACE}.
     */
    public static boolean isSad(float wellbeing) {
        return wellbeing <= SAD_THRESHOLD;
    }

    public boolean isSad(VillagerStats stats) {
        return isSad(stats.wellbeing);
    }

    public void onConversationComplete(VillagerStats stats, boolean noLimits) {
        float cap = noLimits ? 80f : 60f;
        if (stats.wellbeing < cap) stats.wellbeing = Math.min(cap, stats.wellbeing + 10f);
    }

    public void onMayorConversationComplete(VillagerStats stats, float boost) {
        stats.wellbeing += boost;
    }

    public void onFoodEaten(VillagerStats stats, float boost) {
        stats.wellbeing += boost;
    }

    /** Walked into a crowded building; {@code amount} is already capped for the day. */
    public void onCrowded(VillagerStats stats, float amount) {
        stats.wellbeing -= amount;
    }

    public void onFleeStarted(VillagerStats stats) {
        stats.wellbeing -= 5f;
    }

    public void onThreatKilled(VillagerStats stats) {
        stats.wellbeing += 8f;
    }

    /**
     * Happiness from a meal, after {@link FoodCatalog}'s per-meal cap. Good
     * food adds at most {@value #FOOD_JOY_DAILY_CAP} a day; bad food is never
     * capped. {@code day} is the world's day number, so the allowance starts
     * over each morning. Returns what was actually applied.
     */
    public float onFoodJoy(VillagerStats stats, float joy, long day) {
        if (stats.foodJoyDay != day) {
            stats.foodJoyDay = day;
            stats.foodJoyToday = 0f;
        }
        if (joy > 0f) joy = Math.min(joy, Math.max(0f, FOOD_JOY_DAILY_CAP - stats.foodJoyToday));
        if (joy > 0f) stats.foodJoyToday += joy;
        stats.wellbeing += joy;
        return joy;
    }

    /** A good part of the evening raises tonight's sleep refill, up to {@link #EVENING_BONUS_MAX}. */
    public void addEveningBonus(VillagerStats stats, float amount) {
        stats.eveningBonus = Math.min(EVENING_BONUS_MAX, stats.eveningBonus + amount);
    }

    /** Heard the bard tonight: counts once per evening. */
    public void onEveningBard(VillagerStats stats) {
        if (stats.eveningBardCounted) return;
        stats.eveningBardCounted = true;
        addEveningBonus(stats, EVENING_BONUS_BARD);
    }

    /** A new day: last night's evening bonus is used up. */
    public void clearEveningBonus(VillagerStats stats) {
        stats.eveningBonus = 0f;
        stats.eveningBardCounted = false;
    }

    public static final float FOOD_JOY_DAILY_CAP = 15f;
    private static final String TAG_FOOD_JOY_TODAY = "food_joy_today";
    private static final String TAG_FOOD_JOY_DAY = "food_joy_day";

    public float workDriveFactor(VillagerStats stats) {
        return 0.7f + 0.3f * stats.wellbeing / 100f;
    }

    public float socialScore(VillagerStats stats, Personality personality, boolean available) {
        if (!available) return 0f;
        return Math.max(0f, (80f - stats.wellbeing) * 0.8f * personality.sociability);
    }

    public float admireScore(VillagerStats stats) {
        return stats.wellbeing >= 45f ? 10f + (stats.wellbeing - 45f) * 0.25f : 0f;
    }

    public float leisureNeed(VillagerStats stats) {
        return 1f - stats.wellbeing / 100f;
    }

    public void save(VillagerStats stats, CompoundTag tag) {
        tag.putFloat(TAG_WELLBEING, stats.wellbeing);
        tag.putFloat(TAG_FOOD_JOY_TODAY, stats.foodJoyToday);
        tag.putLong(TAG_FOOD_JOY_DAY, stats.foodJoyDay);
        tag.putFloat(TAG_EVENING_BONUS, stats.eveningBonus);
        tag.putBoolean(TAG_EVENING_BARD, stats.eveningBardCounted);
        tag.putFloat(TAG_SLEEP_GAIN_LEFT, stats.sleepGainLeft);
        tag.putFloat(TAG_SLEEP_GAIN_TOTAL, stats.sleepGainTotal);
        tag.putBoolean(TAG_SLEEP_ROLLED, stats.sleepRolledTonight);
        tag.remove(LEGACY_HAPPINESS_TAG);
        tag.remove(LEGACY_MOTIVATION_TAG);
    }

    public void load(VillagerStats stats, CompoundTag tag) {
        stats.eveningBonus = Math.clamp(tag.getFloat(TAG_EVENING_BONUS), 0f, EVENING_BONUS_MAX);
        stats.eveningBardCounted = tag.getBoolean(TAG_EVENING_BARD);
        stats.sleepGainTotal = Math.max(0f, tag.getFloat(TAG_SLEEP_GAIN_TOTAL));
        stats.sleepGainLeft = Math.clamp(tag.getFloat(TAG_SLEEP_GAIN_LEFT), 0f, stats.sleepGainTotal);
        stats.sleepRolledTonight = tag.getBoolean(TAG_SLEEP_ROLLED);
        if (tag.contains(TAG_FOOD_JOY_DAY)) {
            stats.foodJoyDay = tag.getLong(TAG_FOOD_JOY_DAY);
            stats.foodJoyToday = tag.getFloat(TAG_FOOD_JOY_TODAY);
        }
        if (tag.contains(TAG_WELLBEING)) {
            stats.wellbeing = tag.getFloat(TAG_WELLBEING);
        } else if (tag.contains(LEGACY_HAPPINESS_TAG)
                && tag.contains(LEGACY_MOTIVATION_TAG)) {
            stats.wellbeing = WellbeingBalance.migrate(
                    tag.getFloat(LEGACY_HAPPINESS_TAG), tag.getFloat(LEGACY_MOTIVATION_TAG));
        } else if (tag.contains(LEGACY_HAPPINESS_TAG)) {
            stats.wellbeing = tag.getFloat(LEGACY_HAPPINESS_TAG);
        } else if (tag.contains(LEGACY_MOTIVATION_TAG)) {
            stats.wellbeing = tag.getFloat(LEGACY_MOTIVATION_TAG);
        }
    }
}
