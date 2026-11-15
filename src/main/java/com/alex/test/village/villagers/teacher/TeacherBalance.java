package com.alex.test.village.villagers.teacher;

/**
 * Shared teacher and school balance. One skill level, 1 to 100, makes the
 * children learn more per lesson and opens five unlocks:
 *
 * <pre>
 *   level   a full day of lessons   unlocks
 *   1       +6.5 intelligence       head start of 5 levels in a child's favourite job
 *   10                              Patient: tired or sad children daydream half as often
 *   25                              Storyteller: reading hour teaches half as much again
 *   50                              Inspiring: head start of 10 levels
 *   75                              Head Teacher: 12 children before a class gets too big (8 before)
 *   100     +9.75 intelligence      Mentor: head start of 15 levels
 * </pre>
 *
 * <p>Children are born knowing nothing (intelligence 0) and school is where
 * they learn. Childhood lasts {@value #CHILDHOOD_DAYS} village days; a child
 * born at night has five school days, and with a typical teacher and the
 * usual missed bits (arriving late, lunch, a nap) grows up at about 30.
 *
 * <p>Experience is paid per lesson unit with children in class: in full with
 * {@value #FULL_CLASS} or more, half with one, so a teacher with a full
 * class every day reaches level 100 in about {@value #TARGET_DAYS_TO_MAX}
 * days. A day's first {@value #FULL_DAY_UNITS} units pay in full and the
 * rest a fifth, like every other job.
 *
 * <p>Pure numbers, no game classes, so unit tests can use it.
 */
public final class TeacherBalance {

    public static final int MAX_LEVEL = 100;

    public static final int PATIENT_LEVEL = 10;
    public static final int STORYTELLER_LEVEL = 25;
    public static final int INSPIRING_LEVEL = 50;
    public static final int HEAD_TEACHER_LEVEL = 75;
    public static final int MENTOR_LEVEL = 100;

    /** In-game days to level 100 learning at 1x, with a full class every school day. */
    public static final int TARGET_DAYS_TO_MAX = 80;

    /** Ticks in one lesson unit: how often the children in class are paid. */
    public static final int UNIT_TICKS = 100;

    /** Lesson units in a full school day: the timetable's class time ({@link SchoolDay}). */
    public static final int FULL_DAY_UNITS = SchoolDay.CLASS_TICKS / UNIT_TICKS;
    public static final float OVER_FULL_DAY_SHARE = 0.2f;

    /** Children in class for a unit to pay the teacher in full; one child pays half. */
    public static final int FULL_CLASS = 4;

    /** Cumulative experience for level 100: the scale saved teacher experience is measured on. */
    public static final float EXPERIENCE_FOR_MAX_LEVEL = 4000f;

    /** Raw experience per full lesson unit at 1x. */
    public static final float EXPERIENCE_PER_UNIT = EXPERIENCE_FOR_MAX_LEVEL
            / (TARGET_DAYS_TO_MAX * (float) FULL_DAY_UNITS);

    /** Village days from birth to growing up. */
    public static final int CHILDHOOD_DAYS = 5;

    /** Intelligence a child gains from a whole day of lessons at level 1. */
    public static final float DAY_OF_LESSONS = 6.5f;
    /** A child may earn this much more than a whole day of lessons (questions, reading hour). */
    public static final float DAILY_CAP_SHARE = 1.25f;

    /** Children one teacher teaches at full pace; past that each learns a little less. */
    public static final int CLASS_SIZE = 8;
    public static final int HEAD_TEACHER_CLASS_SIZE = 12;

    /** A child at the back (no seat left) learns this share. */
    public static final float BACK_OF_CLASS_SHARE = 0.75f;
    /** A daydreaming child learns this share. */
    public static final float DAYDREAM_SHARE = 0.5f;
    /** Wellbeing below which a child may daydream. */
    public static final float DAYDREAM_WELLBEING = 40f;
    /** Chance per lesson stage that a tired or sad child drifts off. */
    public static final float DAYDREAM_CHANCE = 0.25f;

    /** Each bookshelf in the school makes reading hour this much better, up to {@value #MAX_SHELVES}. */
    public static final float SHELF_BONUS = 0.05f;
    public static final int MAX_SHELVES = 6;
    public static final float STORYTELLER_SHARE = 1.5f;

    /** An answered question is worth this many lesson units to the child who asked. */
    public static final float QUESTION_UNITS = 2f;

    /** Homework: one sitting at home in the evening is worth this many lesson units. */
    public static final float HOMEWORK_UNITS = 4f;

    /** A school day missed while nobody was near the village counts this much. */
    public static final float AWAY_DAY_SHARE = 0.5f;

    /** Wellbeing a child gains from a recess. */
    public static final float RECESS_WELLBEING = 3f;

    /** Share of the total that is linear in the level; the rest is quadratic. */
    private static final double XP_LINEAR_SHARE = 1.0 / 3.0;

    private TeacherBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    /** Cumulative experience needed for {@code level}. */
    public static float experienceForLevel(int level) {
        double p = progress(level);
        return (float) (EXPERIENCE_FOR_MAX_LEVEL
                * (XP_LINEAR_SHARE * p + (1.0 - XP_LINEAR_SHARE) * p * p));
    }

    /** How much one lesson unit counts toward the teacher's day: half with one child, whole with four. */
    public static float unitWeight(int pupils) {
        if (pupils <= 0) return 0f;
        return 0.5f + 0.5f * Math.min(pupils, FULL_CLASS) / (float) FULL_CLASS;
    }

    /** Intelligence a child in class for a whole school day gains: 6.5 at level 1, 9.75 at 100. */
    public static float dayOfLessons(int level) {
        return (float) (DAY_OF_LESSONS * (1.0 + 0.5 * progress(level)));
    }

    /** Intelligence one lesson unit pays a child in a class this teacher teaches. */
    public static float unitGain(int level) {
        return dayOfLessons(level) / FULL_DAY_UNITS;
    }

    /** Most a child can learn in one day under this teacher. */
    public static float dailyCap(int level) {
        return dayOfLessons(level) * DAILY_CAP_SHARE;
    }

    /** Children this teacher can teach before each learns less. */
    public static int classSize(int level) {
        return level >= HEAD_TEACHER_LEVEL ? HEAD_TEACHER_CLASS_SIZE : CLASS_SIZE;
    }

    /** Share each child gets in a class of {@code pupils}: 1 up to the class size, then shared out. */
    public static float crowdShare(int level, int pupils) {
        int size = classSize(level);
        return pupils <= size ? 1f : size / (float) pupils;
    }

    /** Chance a tired or sad child drifts off during one lesson stage. */
    public static float daydreamChance(int level) {
        return level >= PATIENT_LEVEL ? DAYDREAM_CHANCE / 2f : DAYDREAM_CHANCE;
    }

    /** How much reading hour pays compared to a lesson, with {@code shelves} bookshelves in the school. */
    public static float readingShare(int level, int shelves) {
        float storyteller = level >= STORYTELLER_LEVEL ? STORYTELLER_SHARE : 1f;
        return storyteller * (1f + SHELF_BONUS * Math.min(Math.max(shelves, 0), MAX_SHELVES));
    }

    /** Levels a child taught by this teacher starts its favourite job ahead: at least 5. */
    public static int headStartLevels(int level) {
        if (level >= MENTOR_LEVEL) return 15;
        if (level >= INSPIRING_LEVEL) return 10;
        return 5;
    }
}
