package com.alex.test.village.raid;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The numbers of the Bad Omen raid: how many waves, how strong, what a win is
 * worth. The omen level (1 to 5) is the Bad Omen amplifier plus one; two
 * players' omens add up to a higher level.
 *
 * <p>No Minecraft types: unit-tested on a plain JVM.
 */
public final class RaidOmenMath {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 5;
    /** A defeat costs this much fame at every level. */
    public static final int DEFEAT_FAME = -10;
    /** The smallest raid strength, and the smallest wave. */
    public static final int MIN_STRENGTH = 6;
    /** Strength the Bad Omen raid gets in a village with no defense at all. */
    public static final int UNDEFENDED_STRENGTH = 10;
    /** A Bad Omen raid is at most this many times the village's defense (normal raids: {@link RaidMath#DEFENSE_CAP}). */
    public static final double DEFENSE_CAP = 2.0;

    private RaidOmenMath() {}

    public static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    /** "I" to "V": how the omen level reads in chat and on the bar. */
    public static String roman(int level) {
        return switch (clampLevel(level)) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "V";
        };
    }

    /** Waves by difficulty (0 easy, 1 normal, 2 hard): 3, 5 or 7, and one more from level 2. */
    public static int waveCount(int difficultyIndex, int level) {
        int base = switch (difficultyIndex) {
            case 0 -> 3;
            case 2 -> 7;
            default -> 5;
        };
        return base + (level >= 2 ? 1 : 0);
    }

    /** 1.0 at level 1, then 0.15 more with every level. */
    public static double sizeMultiplier(int level) {
        return 1 + 0.15 * (level - 1);
    }

    /**
     * Threat scaled by difficulty, never more than {@link #DEFENSE_CAP} times
     * the defense ({@link #UNDEFENDED_STRENGTH} without any), grown by the omen
     * level, and never under {@link #MIN_STRENGTH}.
     */
    public static int strength(int threat, double defense, double difficultyScale, int level) {
        double base = Math.max(0, threat) * Math.max(0.0, difficultyScale);
        double cap = defense <= 0 ? UNDEFENDED_STRENGTH : DEFENSE_CAP * defense;
        int result = (int) Math.round(Math.min(base, cap) * sizeMultiplier(level));
        return Math.max(MIN_STRENGTH, result);
    }

    /** Points to spend on one wave; each wave is bigger than the one before. */
    public static int waveBudget(int strength, int waveIndex) {
        return Math.max(MIN_STRENGTH, (int) Math.round(strength * (0.4 + 0.15 * waveIndex)));
    }

    /** The share of its budget the last wave spends on regular raiders, as the Warlord comes with it. */
    public static final double WARLORD_WAVE_SHARE = 0.5;

    /**
     * One list of raiders per wave. A wave never holds more than {@code maxAlive}
     * regular raiders (budget left over is dropped), and is never empty. Witches
     * come from the second wave, evokers from the third, a ravager from the
     * fourth. The last wave is led by the Warlord, first in its list, on top of
     * {@code maxAlive}; its regulars get {@link #WARLORD_WAVE_SHARE} of its budget.
     * {@code level} does not change the mix: it already grew {@code strength}.
     */
    public static List<List<RaiderKind>> plan(int strength, int waves, int level, int maxAlive, RandomGenerator rng) {
        int cap = Math.max(1, maxAlive);
        List<List<RaiderKind>> plan = new ArrayList<>();
        for (int i = 0; i < waves; i++) {
            boolean last = i == waves - 1;
            int budget = waveBudget(strength, i);
            List<RaiderKind> wave = wave(last ? (int) Math.round(budget * WARLORD_WAVE_SHARE) : budget, i, cap, rng);
            if (last) wave.add(0, RaiderKind.WARLORD);
            plan.add(wave);
        }
        return plan;
    }

    private static List<RaiderKind> wave(int budget, int index, int cap, RandomGenerator rng) {
        List<RaiderKind> wave = new ArrayList<>();
        int left = budget;
        if (index >= 3 && left >= 12 && wave.size() < cap) {
            wave.add(RaiderKind.RAVAGER);
            left -= RaiderKind.RAVAGER.cost();
        }
        if (index >= 2 && left >= 10 && wave.size() < cap) {
            wave.add(RaiderKind.EVOKER);
            left -= RaiderKind.EVOKER.cost();
        }
        if (index >= 1 && left >= 7 && wave.size() < cap && rng.nextBoolean()) {
            wave.add(RaiderKind.WITCH);
            left -= RaiderKind.WITCH.cost();
        }
        while (left >= RaiderKind.PILLAGER.cost() && wave.size() < cap) {
            RaiderKind kind = left >= RaiderKind.VINDICATOR.cost() && rng.nextDouble() < RaidPlanner.VINDICATOR_CHANCE
                    ? RaiderKind.VINDICATOR : RaiderKind.PILLAGER;
            wave.add(kind);
            left -= kind.cost();
        }
        if (wave.isEmpty()) wave.add(RaiderKind.PILLAGER);
        return wave;
    }

    // -------------------------------------------------------------------------
    // Losing
    // -------------------------------------------------------------------------

    /** Villager deaths the Bad Omen raid costs at the very least before it is lost. */
    public static final int MIN_DEATH_LIMIT = 3;
    /** The share of the grown villagers whose death loses the raid (once the defenders are spent). */
    public static final double DEATH_SHARE = 0.30;
    /** From this many grown villagers the village keeps two defenders standing before it can fall. */
    public static final int BIG_VILLAGE = 16;

    /** Villager deaths that lose the raid: 30% of the grown villagers, never under {@link #MIN_DEATH_LIMIT}. */
    public static int deathLimit(int villagers) {
        int people = Math.max(0, villagers);
        int limit = Math.max(MIN_DEATH_LIMIT, (int) Math.round(DEATH_SHARE * people + 1e-9));
        return people == 0 ? limit : Math.min(limit, Math.max(people, MIN_DEATH_LIMIT));
    }

    /**
     * Defenders (guards, archers, captains) still standing when the village can
     * fall: none for a village that had none, one in a small village, two from
     * {@link #BIG_VILLAGE} villagers.
     */
    public static int defenderFloor(int villagers, int defenders) {
        if (defenders <= 0) return 0;
        return villagers >= BIG_VILLAGE ? 2 : 1;
    }

    /**
     * The raid is lost when the defenders are down to {@link #defenderFloor} (or
     * were never there) and {@link #deathLimit} villagers are dead. Until both
     * are true the fight goes on.
     */
    public static boolean lost(int deaths, int villagersAtStart, int defendersAtStart, int defendersAlive) {
        return deaths >= deathLimit(villagersAtStart)
                && defendersAlive <= defenderFloor(villagersAtStart, defendersAtStart);
    }

    /**
     * One more death (or one more defender down) and {@link #lost} would be
     * true: the moment for a warning.
     */
    public static boolean onTheBrink(int deaths, int villagersAtStart, int defendersAtStart, int defendersAlive) {
        if (lost(deaths, villagersAtStart, defendersAtStart, defendersAlive)) return false;
        int limit = deathLimit(villagersAtStart);
        int floor = defenderFloor(villagersAtStart, defendersAtStart);
        boolean deathsDone = deaths >= limit;
        boolean defendersDone = defendersAlive <= floor;
        return (deathsDone && defendersAlive == floor + 1) || (defendersDone && deaths == limit - 1);
    }

    /**
     * How a Bad Omen raid went: a defeat when it was {@code lost} (or ended
     * unbeaten with goods stolen), else flawless, a victory or a costly win as
     * for any raid. Nobody is carried off in this raid.
     */
    public static RaidResult grade(boolean beaten, int deaths, int stacksLost, boolean lost) {
        if (lost) return RaidResult.DEFEAT;
        if (!beaten && stacksLost > 0) return RaidResult.DEFEAT;
        if (beaten && deaths == 0 && stacksLost == 0) return RaidResult.FLAWLESS;
        if (deaths <= 1 && stacksLost <= 1) return RaidResult.VICTORY;
        return RaidResult.COSTLY;
    }

    // -------------------------------------------------------------------------
    // Rewards
    // -------------------------------------------------------------------------

    /** A defeat is -10 fame whatever the level; a win grows the normal fame by half again for every level. */
    public static int fameChange(RaidResult result, int level) {
        if (result == RaidResult.DEFEAT) return DEFEAT_FAME;
        return (int) Math.round(result.fameChange() * (1 + level / 2.0));
    }

    /** Practice days for defenders: more with the level. */
    public static float practiceDays(float baseDays, int level) {
        return baseDays * (1 + level / 4f);
    }

    /** Emeralds in the spoils: (5 + 3 per level) times the result's share. */
    public static int spoilsEmeralds(RaidResult result, int level) {
        return (int) Math.round((5 + 3 * level) * result.spoilsShare());
    }

    /** Village days after one Bad Omen raid's spoils before another raid brings spoils again. */
    public static final int SPOILS_WAIT_DAYS = 3;

    /**
     * A Bad Omen raid starting on {@code day} brings spoils: none were delivered
     * yet ({@code lastSpoilsDay} MIN_VALUE), or {@link #SPOILS_WAIT_DAYS} have passed.
     */
    public static boolean spoilsDue(long lastSpoilsDay, long day) {
        return lastSpoilsDay == Long.MIN_VALUE || day - lastSpoilsDay >= SPOILS_WAIT_DAYS;
    }

    public static int ironIngots(int level) {
        return level >= 2 ? 4 + 2 * level : 0;
    }

    public static int goldIngots(int level) {
        return level >= 2 ? 2 + level : 0;
    }

    public static boolean enchantedBook(int level) {
        return level >= 3;
    }

    public static boolean totem(int level) {
        return level >= 4;
    }
}
