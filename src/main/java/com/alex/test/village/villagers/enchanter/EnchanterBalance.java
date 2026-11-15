package com.alex.test.village.villagers.enchanter;

/**
 * Shared enchanter balance. One skill level, 1 to 100, sets how strong a book
 * it can write, which enchantments it knows, how many books it finishes a day
 * and how long each takes, with milestones at 25, 50, 75 and 100.
 *
 * <ul>
 *   <li>Lv 1 — Apprentice: level I books of Unbreaking, Efficiency, Sharpness,
 *       Protection and Power; 2 books a day.</li>
 *   <li>Lv 25 — Scholar: level II books, Fortune and Infinity; 3 a day.</li>
 *   <li>Lv 50 — Adept: level III books, and a chance to save the lapis; 4 a day.</li>
 *   <li>Lv 75 — Arcanist: level IV books and Mending; 5 a day.</li>
 *   <li>Lv 100 — Master Enchanter: level V books.</li>
 * </ul>
 *
 * <p>The library sets a second ceiling: a book is never stronger than its
 * bookshelves allow ({@link #shelfCap}), however skilled the enchanter.
 *
 * <p>Pure numbers, no Minecraft types, so unit tests can load it.
 */
public final class EnchanterBalance {

    public static final int MAX_LEVEL = 100;

    public static final int SCHOLAR_LEVEL = 25;
    public static final int ADEPT_LEVEL = 50;
    public static final int ARCANIST_LEVEL = 75;
    public static final int MASTER_LEVEL = 100;

    /** Fewest bookshelves for each book level, I to V; the library itself needs 3. */
    private static final int[] SHELVES_FOR_BOOK_LEVEL = {3, 6, 10, 15, 15};

    /*
     * Experience. Every finished enchanted book pays EXPERIENCE_PER_BOOK. The
     * day's cap (2 to 5 books, by level) is what paces it: an enchanter making
     * its full books every day, learning at 1x, reaches level 100 in about
     * DAYS_TO_MAX days, about 20 days per band (IntelligenceLearning sets the
     * real speed). A village with every tool already covered makes fewer books
     * and learns slower.
     */
    /** In-game days to level 100 learning at 1x, making a full day's books every day. */
    public static final int DAYS_TO_MAX = 80;
    /** Cumulative XP for level 100, the farmer's and lumberjack's scale. */
    public static final float EXPERIENCE_FOR_MAX_LEVEL = 3888f;
    /** The curve's straight part; the rest grows with the square (the lumberjack's shape). */
    private static final double XP_LINEAR_SHARE = 0.38;
    /**
     * Raw experience per finished book, at 1x: about 13.7, so 2 books a day up
     * to 25, 3 to 50, 4 to 75 and 5 to 100 add up to {@link #DAYS_TO_MAX} days.
     */
    public static final float EXPERIENCE_PER_BOOK = (float) (bandDays() / DAYS_TO_MAX);
    /** Raw experience for a batch of paper or a book bound: practice, but small. */
    public static final float EXPERIENCE_PER_BINDING = 0.5f;

    /** Ticks studying at a shelf before each book: 20 s at level 1, 10 s at 100. */
    private static final int STUDY_TICKS_START = 400;
    private static final int STUDY_TICKS_END = 200;
    /** Ticks at the enchanting table per book: 40 s at level 1, 20 s at 100. */
    private static final int TABLE_TICKS_START = 800;
    private static final int TABLE_TICKS_END = 400;
    /** A level IV or V book takes this much longer at the table than a level I-III one. */
    private static final double HIGH_BOOK_TIME = 1.25;

    /** Ticks at the table to press three sugar cane into three paper. */
    public static final int PAPER_TICKS = 160;
    /** Ticks at the table to bind three paper and a leather into a book. */
    public static final int BIND_TICKS = 120;

    /** Lapis saved now and then from level 50: 10% at 50 rising to 30% at 100. */
    private static final double LAPIS_SAVE_START = 0.10;
    private static final double LAPIS_SAVE_END = 0.30;

    private EnchanterBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    /** Cumulative XP: level one starts at zero, level 100 needs {@link #EXPERIENCE_FOR_MAX_LEVEL}. */
    public static float experienceForLevel(int level) {
        double progress = progress(level);
        return (float) (EXPERIENCE_FOR_MAX_LEVEL
                * (XP_LINEAR_SHARE * progress + (1.0 - XP_LINEAR_SHARE) * progress * progress));
    }

    /** Book-days the whole track takes: each band's XP over the books a day it allows. */
    private static double bandDays() {
        int[] starts = {1, SCHOLAR_LEVEL, ADEPT_LEVEL, ARCANIST_LEVEL, MAX_LEVEL};
        double total = 0.0;
        for (int i = 0; i + 1 < starts.length; i++) {
            total += (experienceForLevel(starts[i + 1]) - experienceForLevel(starts[i]))
                    / (double) booksPerDay(starts[i]);
        }
        return total;
    }

    /** Enchanted books a day at most: 2 below 25, 3 to 50, 4 to 75, 5 from 75. */
    public static int booksPerDay(int level) {
        if (level >= ARCANIST_LEVEL) return 5;
        if (level >= ADEPT_LEVEL) return 4;
        if (level >= SCHOLAR_LEVEL) return 3;
        return 2;
    }

    /** The strongest book this skill writes: I below 25, II, III, IV, V at 100. */
    public static int skillCap(int level) {
        if (level >= MASTER_LEVEL) return 5;
        if (level >= ARCANIST_LEVEL) return 4;
        if (level >= ADEPT_LEVEL) return 3;
        if (level >= SCHOLAR_LEVEL) return 2;
        return 1;
    }

    /** The strongest book a library with {@code shelves} bookshelves allows: I with 3, up to V with 15. */
    public static int shelfCap(int shelves) {
        int cap = 0;
        for (int i = 0; i < SHELVES_FOR_BOOK_LEVEL.length; i++) {
            if (shelves >= SHELVES_FOR_BOOK_LEVEL[i]) cap = i + 1;
        }
        return Math.max(1, cap);
    }

    /** Bookshelves a library needs for {@code bookLevel} books. */
    public static int shelvesFor(int bookLevel) {
        return SHELVES_FOR_BOOK_LEVEL[Math.clamp(bookLevel, 1, SHELVES_FOR_BOOK_LEVEL.length) - 1];
    }

    /** The strongest book this enchanter can write in this library. */
    public static int bookCap(int level, int shelves) {
        return Math.min(skillCap(level), shelfCap(shelves));
    }

    /** Lapis one book of {@code bookLevel} uses: 1, 2, then 3 from level III, like a vanilla table. */
    public static int lapisFor(int bookLevel) {
        return Math.clamp(bookLevel, 1, 3);
    }

    /** Ticks reading at a shelf before a book. */
    public static int studyTicks(int level) {
        return (int) Math.round(STUDY_TICKS_START - (STUDY_TICKS_START - STUDY_TICKS_END) * progress(level));
    }

    /** Ticks at the table for one book of {@code bookLevel}. */
    public static int tableTicks(int level, int bookLevel) {
        double ticks = TABLE_TICKS_START - (TABLE_TICKS_START - TABLE_TICKS_END) * progress(level);
        if (bookLevel >= 4) ticks *= HIGH_BOOK_TIME;
        return (int) Math.round(ticks);
    }

    /** Chance a finished book leaves its lapis unused: none below 50, 10% to 30% from there. */
    public static double lapisSaveChance(int level) {
        if (level < ADEPT_LEVEL) return 0.0;
        return LAPIS_SAVE_START + (LAPIS_SAVE_END - LAPIS_SAVE_START)
                * (Math.clamp(level, ADEPT_LEVEL, MAX_LEVEL) - ADEPT_LEVEL)
                / (double) (MAX_LEVEL - ADEPT_LEVEL);
    }
}
