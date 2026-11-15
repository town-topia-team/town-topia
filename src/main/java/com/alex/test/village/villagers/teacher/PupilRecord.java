package com.alex.test.village.villagers.teacher;

import com.alex.test.village.stats.VillagerSkills;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * A child's school record, kept in its brain data: what it learned today
 * (against the daily limit), in total, which subjects it was taught most,
 * and the best teacher it had. Read when it grows up to say how it did and
 * which job it would be good at.
 */
public final class PupilRecord {

    private static final String TAG_DAY = "SchoolLearnDay";
    private static final String TAG_TODAY = "SchoolLearnedToday";
    private static final String TAG_TOTAL = "SchoolLearnedTotal";
    private static final String TAG_SUBJECTS = "SchoolSubjects";
    private static final String TAG_BEST_TEACHER = "SchoolBestTeacher";
    /**
     * Saved on the grown-up for good: its favourite job (shown with a heart on
     * that job in its screen) and how many levels ahead school put it.
     */
    public static final String TAG_FAVOURITE_JOB = "FavouriteJob";
    public static final String TAG_HEAD_START = "FavouriteJobHeadStart";
    /** The head start has been added to the favourite job's skill (once). */
    public static final String TAG_HEAD_START_APPLIED = "FavouriteJobHeadStartApplied";

    /** A child's own leaning makes its favourite subject count this much more. */
    private static final float LEANING_SHARE = 1.5f;

    private PupilRecord() {}

    /**
     * Pays {@code amount} intelligence on village day {@code day}, as far as
     * today's limit {@code dailyCap} allows. Returns what was paid.
     */
    public static float learn(CompoundTag data, VillagerSkills skills, long day, float amount, float dailyCap) {
        if (amount <= 0f) return 0f;
        float today = data.contains(TAG_DAY) && data.getLong(TAG_DAY) == day ? data.getFloat(TAG_TODAY) : 0f;
        float paid = Math.min(amount, Math.max(0f, dailyCap - today));
        if (paid <= 0f) return 0f;
        skills.addIntelligence(paid);
        data.putLong(TAG_DAY, day);
        data.putFloat(TAG_TODAY, today + paid);
        data.putFloat(TAG_TOTAL, data.getFloat(TAG_TOTAL) + paid);
        return paid;
    }

    /** School days missed while nobody was near: paid straight, outside the daily limit. */
    public static void credit(CompoundTag data, VillagerSkills skills, float amount) {
        if (amount <= 0f) return;
        skills.addIntelligence(amount);
        data.putFloat(TAG_TOTAL, data.getFloat(TAG_TOTAL) + amount);
    }

    /** Learned so far today. */
    public static float learnedToday(CompoundTag data, long day) {
        return data.contains(TAG_DAY) && data.getLong(TAG_DAY) == day ? data.getFloat(TAG_TODAY) : 0f;
    }

    /** Learned at school (and from homework) in all. */
    public static float learnedTotal(CompoundTag data) {
        return data.getFloat(TAG_TOTAL);
    }

    /** A unit of {@code subject} taught; the child's own leaning counts half as much again. */
    public static void taught(CompoundTag data, UUID pupil, Profession subject, float units) {
        if (subject == null || subject == Profession.NONE || units <= 0f) return;
        CompoundTag subjects = data.getCompound(TAG_SUBJECTS);
        float weight = subject == leaning(pupil) ? LEANING_SHARE : 1f;
        subjects.putFloat(subject.name(), subjects.getFloat(subject.name()) + units * weight);
        data.put(TAG_SUBJECTS, subjects);
    }

    /** The subject taught most, leaning included; null before any. */
    @Nullable
    public static Profession favourite(CompoundTag data) {
        CompoundTag subjects = data.getCompound(TAG_SUBJECTS);
        Profession best = null;
        float bestPoints = 0f;
        for (String key : subjects.getAllKeys()) {
            Profession p = Profession.byName(key);
            if (p == Profession.NONE) continue;
            float points = subjects.getFloat(key);
            if (points > bestPoints) {
                bestPoints = points;
                best = p;
            }
        }
        return best;
    }

    /** A grown-up's favourite job from school; null when it has none. */
    @Nullable
    public static Profession favouriteJob(CompoundTag data) {
        String saved = data.getString(TAG_FAVOURITE_JOB);
        if (saved.isEmpty()) return null;
        Profession job = Profession.byName(saved);
        return job == Profession.NONE || job == Profession.CHILD ? null : job;
    }

    /** The best teacher this child has had, by level. */
    public static void taughtBy(CompoundTag data, int teacherLevel) {
        if (teacherLevel > data.getInt(TAG_BEST_TEACHER)) data.putInt(TAG_BEST_TEACHER, teacherLevel);
    }

    public static int bestTeacher(CompoundTag data) {
        return data.getInt(TAG_BEST_TEACHER);
    }

    /** Whether this child ever went to school. */
    public static boolean wentToSchool(CompoundTag data) {
        return data.getFloat(TAG_TOTAL) > 0f;
    }

    /** Forgets the school record (kept: the favourite job, saved separately). */
    public static void clear(CompoundTag data) {
        data.remove(TAG_DAY);
        data.remove(TAG_TODAY);
        data.remove(TAG_TOTAL);
        data.remove(TAG_SUBJECTS);
        data.remove(TAG_BEST_TEACHER);
        data.remove(SchoolClasses.TAG_PUPIL_SCHOOL);
        data.remove(SchoolClasses.TAG_PUPIL_LECTERN);
        data.remove(SchoolClasses.TAG_PUPIL_SEAT);
    }

    /** The job a child takes to on its own, the same every time: a nudge toward one subject. */
    public static Profession leaning(UUID pupil) {
        List<Profession> subjects = SchoolSubjects.TEACHABLE;
        long h = pupil.getMostSignificantBits() ^ (pupil.getLeastSignificantBits() * 31L);
        return subjects.get((int) Math.floorMod(h, (long) subjects.size()));
    }
}
