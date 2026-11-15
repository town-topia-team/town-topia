package com.alex.test.village.villagers.farmer;

import com.alex.test.village.stats.DailyJobExperience;
import net.minecraft.nbt.CompoundTag;

/**
 * Farmer experience per job (see {@link FarmerBalance}), shared by the live
 * farmer and the away calculator so both keep one pace. A day's first
 * {@link FarmerBalance#FULL_DAY_JOBS} jobs pay in full; every job after that
 * pays {@link FarmerBalance#OVER_FULL_DAY_SHARE} of it. The rule itself is
 * {@link DailyJobExperience}; this keeps the farmer's numbers and keys.
 */
public final class FarmerExperience {

    private static final DailyJobExperience RULE = new DailyJobExperience(
            "FarmerXpDay", "FarmerXpEarnedToday", FarmerBalance.EXPERIENCE_PER_JOB,
            FarmerBalance.FULL_DAY_JOBS, FarmerBalance.OVER_FULL_DAY_SHARE);

    private FarmerExperience() {}

    /** Full-pay experience already used on {@code day}. */
    public static float earned(CompoundTag data, long day) {
        return RULE.earned(data, day);
    }

    /** What {@code jobs} jobs pay on {@code day}; see {@link DailyJobExperience#claim}. */
    public static float claim(CompoundTag data, long day, double jobs) {
        return RULE.claim(data, day, jobs);
    }
}
