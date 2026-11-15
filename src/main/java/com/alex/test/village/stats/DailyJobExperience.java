package com.alex.test.village.stats;

import net.minecraft.nbt.CompoundTag;

/**
 * Work experience per job with a full day: a day's first {@code fullDayJobs}
 * jobs pay {@code perJob} each, every job after that pays
 * {@code overFullDayShare} of it. Shared by a profession's live work and the
 * away calculator (per away day, {@code economy/away/AwayXp}) so both keep
 * one pace. The day's full-pay tally lives in
 * the villager's brain data under the profession's own keys, so a reload
 * does not refill it. Amounts are raw, before intelligence.
 */
public final class DailyJobExperience {

    private final String tagDay;
    private final String tagEarned;
    private final float perJob;
    private final float fullDay;
    private final float overFullDayShare;

    /**
     * @param tagDay    brain-data key for the day the tally belongs to
     * @param tagEarned brain-data key for the full pay used that day
     */
    public DailyJobExperience(String tagDay, String tagEarned, float perJob, int fullDayJobs,
                              float overFullDayShare) {
        this.tagDay = tagDay;
        this.tagEarned = tagEarned;
        this.perJob = perJob;
        this.fullDay = fullDayJobs * perJob;
        this.overFullDayShare = overFullDayShare;
    }

    /** Full-pay experience already used on {@code day}. */
    public float earned(CompoundTag data, long day) {
        return data.contains(tagDay) && data.getLong(tagDay) == day ? data.getFloat(tagEarned) : 0f;
    }

    /**
     * What {@code jobs} jobs pay on {@code day}: in full while the day's
     * full pay lasts, the reduced share after it. Recorded as used.
     */
    public float claim(CompoundTag data, long day, double jobs) {
        float earned = earned(data, day);
        double wanted = Math.max(0.0, jobs * perJob);
        double full = Math.min(wanted, Math.max(0.0, fullDay - earned));
        data.putLong(tagDay, day);
        data.putFloat(tagEarned, (float) (earned + full));
        return (float) (full + (wanted - full) * overFullDayShare);
    }
}
