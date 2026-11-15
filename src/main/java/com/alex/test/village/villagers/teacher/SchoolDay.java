package com.alex.test.village.villagers.teacher;

/**
 * The school timetable, by the world's time of day (0 = 6:00, 1000 ticks an
 * hour, about 50 real seconds).
 *
 * <pre>
 *   7:30   1500  first bell, the children walk over and sit down
 *   8:00   2000  lesson 1
 *   10:00  4000  recess in the yard
 *   10:45  4750  lesson 2
 *   12:00  6000  lunch, everyone off
 *   12:45  6750  lesson 3 (once a week a field trip instead)
 *   14:15  8250  reading hour
 *   15:00  9000  home bell
 * </pre>
 *
 * The teacher rings a hand bell at the start of each part. With recess
 * switched off, lessons 1 and 2 run straight on. Every day is a school day.
 *
 * <p>Pure logic, no game classes, so unit tests can use it.
 */
public final class SchoolDay {

    public enum Phase {
        /** Before the first bell, and after school until the next morning. */
        OFF,
        GATHER,
        LESSON_1,
        RECESS,
        LESSON_2,
        LUNCH,
        LESSON_3,
        READING,
        /** After the home bell. */
        AFTER;

        /** Class is on: the children belong in their seats (or on the field trip). */
        public boolean inClass() {
            return this == GATHER || this == LESSON_1 || this == LESSON_2
                    || this == LESSON_3 || this == READING;
        }

        /** Lesson units pay the children. */
        public boolean pays() {
            return this == LESSON_1 || this == LESSON_2 || this == LESSON_3 || this == READING;
        }

        /** The teacher has a job at school: class or watching recess. */
        public boolean atSchool() {
            return inClass() || this == RECESS;
        }

        public boolean isLesson() {
            return this == LESSON_1 || this == LESSON_2 || this == LESSON_3;
        }
    }

    public static final int FIRST_BELL = 1500;
    public static final int LESSON_1_START = 2000;
    public static final int RECESS_START = 4000;
    public static final int LESSON_2_START = 4750;
    public static final int LUNCH_START = 6000;
    public static final int LESSON_3_START = 6750;
    public static final int READING_START = 8250;
    public static final int HOME_BELL = 9000;

    /** Ticks of paid class time in a whole school day (recess and lunch not counted). */
    public static final int CLASS_TICKS = (RECESS_START - LESSON_1_START)
            + (LUNCH_START - LESSON_2_START) + (HOME_BELL - LESSON_3_START);

    private SchoolDay() {}

    /** The part of the school day at {@code dayTime}; with {@code recess} off, recess is lesson 2. */
    public static Phase phase(long dayTime, boolean recess) {
        int t = (int) Math.floorMod(dayTime, 24000L);
        if (t < FIRST_BELL) return Phase.OFF;
        if (t < LESSON_1_START) return Phase.GATHER;
        if (t < RECESS_START) return Phase.LESSON_1;
        if (t < LESSON_2_START) return recess ? Phase.RECESS : Phase.LESSON_2;
        if (t < LUNCH_START) return Phase.LESSON_2;
        if (t < LESSON_3_START) return Phase.LUNCH;
        if (t < READING_START) return Phase.LESSON_3;
        if (t < HOME_BELL) return Phase.READING;
        if (t < 12000) return Phase.AFTER;
        return Phase.OFF;
    }

    /** Ticks until the current part of the day ends; 0 outside school. */
    public static int ticksLeft(long dayTime, boolean recess) {
        int t = (int) Math.floorMod(dayTime, 24000L);
        return switch (phase(dayTime, recess)) {
            case GATHER -> LESSON_1_START - t;
            case LESSON_1 -> (recess ? RECESS_START : LUNCH_START) - t;
            case RECESS -> LESSON_2_START - t;
            case LESSON_2 -> LUNCH_START - t;
            case LUNCH -> LESSON_3_START - t;
            case LESSON_3 -> READING_START - t;
            case READING -> HOME_BELL - t;
            default -> 0;
        };
    }

    /** Ticks since the current part of the day began; 0 outside school. */
    public static int ticksInto(long dayTime, boolean recess) {
        int t = (int) Math.floorMod(dayTime, 24000L);
        return switch (phase(dayTime, recess)) {
            case GATHER -> t - FIRST_BELL;
            case LESSON_1 -> t - LESSON_1_START;
            case RECESS -> t - RECESS_START;
            case LESSON_2 -> t - (recess ? LESSON_2_START : RECESS_START);
            case LUNCH -> t - LUNCH_START;
            case LESSON_3 -> t - LESSON_3_START;
            case READING -> t - READING_START;
            case AFTER -> t - HOME_BELL;
            default -> 0;
        };
    }

    /** "10:45" for a time of day, for chat and the Mayor's screen. */
    public static String clock(int timeOfDay) {
        int minutes = Math.floorMod(timeOfDay * 60 / 1000 + 6 * 60, 24 * 60);
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60);
    }

    /** The next bell after {@code dayTime} and what it is for, for the Mayor's School card. */
    public static String nextBell(long dayTime, boolean recess) {
        int t = (int) Math.floorMod(dayTime, 24000L);
        if (t < FIRST_BELL) return clock(FIRST_BELL) + " first bell";
        if (t < RECESS_START && recess) return clock(RECESS_START) + " recess";
        if (t < LESSON_2_START && recess) return clock(LESSON_2_START) + " lesson 2";
        if (t < LUNCH_START) return clock(LUNCH_START) + " lunch";
        if (t < LESSON_3_START) return clock(LESSON_3_START) + " lesson 3";
        if (t < READING_START) return clock(READING_START) + " reading hour";
        if (t < HOME_BELL) return clock(HOME_BELL) + " home time";
        return "tomorrow " + clock(FIRST_BELL);
    }
}
