package com.alex.test.village.raid;

/**
 * How big a raid is: what the village is worth to raiders (threat), how well
 * it is defended (defense), and the raid budget between the two. Raiders cost
 * budget points ({@link RaiderKind#cost()}).
 *
 * <p>No Minecraft types: unit-tested on a plain JVM.
 */
public final class RaidMath {

    /** Storage emeralds per threat point. */
    public static final double EMERALDS_PER_WEALTH_POINT = 40.0;
    /** Fame points per threat point. */
    public static final int FAME_PER_POINT = 5;
    public static final int MAX_FAME = 100;
    /** A raid is never bigger than this many times the village's defense. */
    public static final double DEFENSE_CAP = 1.4;
    /** The smallest raid. */
    public static final int MIN_BUDGET = 4;
    /** The biggest raid a village with no (or almost no) defense gets. */
    public static final int UNDEFENDED_CAP = 6;

    private RaidMath() {}

    /**
     * Grown villagers, plus storage wealth (never counting for more than the
     * villagers do), plus fame.
     */
    public static int threat(int villagers, double wealthEmeralds, int fame) {
        int people = Math.max(0, villagers);
        int wealth = (int) Math.min(people, Math.floor(Math.max(0, wealthEmeralds) / EMERALDS_PER_WEALTH_POINT));
        int famePoints = Math.max(0, Math.min(MAX_FAME, fame)) / FAME_PER_POINT;
        return people + wealth + famePoints;
    }

    /**
     * Guards and archers 3 each, captains 6, plus their levels (sums over
     * that job: level/25 for guards and archers, level/20 for captains),
     * plus 2 per watchtower with a lookout. The player does not count.
     */
    public static double defense(int guards, int guardLevels, int captains, int captainLevels,
                                 int archers, int archerLevels, int towers) {
        return guards * 3.0 + guardLevels / 25.0
                + captains * 6.0 + captainLevels / 20.0
                + archers * 3.0 + archerLevels / 25.0
                + towers * 2.0;
    }

    /**
     * Threat scaled by difficulty, never more than {@link #DEFENSE_CAP} times
     * the defense (or {@link #UNDEFENDED_CAP} for a village without guards)
     * and never less than {@link #MIN_BUDGET}.
     */
    public static int budget(int threat, double defense, double difficulty) {
        int raw = (int) Math.round(Math.max(0, threat) * Math.max(0.0, difficulty));
        int cap = Math.max(UNDEFENDED_CAP, (int) Math.floor(Math.max(0.0, defense) * DEFENSE_CAP));
        return Math.max(MIN_BUDGET, Math.min(raw, cap));
    }

    /** 1 under 15 threat, 2 up to 35, 3 above: which raiders can come. */
    public static int tier(int threat) {
        if (threat < 15) return 1;
        if (threat <= 35) return 2;
        return 3;
    }

    /** How the warning describes the raid's size next to the village's defense. */
    public static String sizeWord(int budget, double defense) {
        if (defense <= 0) return "a small band, and no guards to stop it";
        double ratio = budget / defense;
        if (ratio < 0.6) return "a small band";
        if (ratio <= 1.1) return "a band your guards can match";
        return "a dangerous band";
    }

    /** Emeralds a beaten raider drops per point of its cost, on average. */
    public static final double DROP_PER_COST = 0.25;

    /**
     * Emeralds a beaten raider is worth on average: a quarter of its cost,
     * with elite gear one cost point more, doubled for a Minecraft raid's
     * raider (that raid leaves no war chest).
     */
    public static double dropWorth(int cost, boolean eliteGear, boolean doubled) {
        double worth = (Math.max(0, cost) + (eliteGear ? 1 : 0)) * DROP_PER_COST;
        return doubled ? worth * 2 : worth;
    }

    /** The most emeralds a war chest holds. */
    public static final int WAR_CHEST_MAX = 20;

    /**
     * Emeralds the war chest holds after a win: a quarter of the raid's size
     * (at most {@link #WAR_CHEST_MAX}) times the result's share, at least 1.
     */
    public static int warChestEmeralds(int budget, RaidResult result) {
        if (!result.isWin()) return 0;
        int full = Math.min(WAR_CHEST_MAX, Math.max(0, budget) / 4);
        return Math.max(1, (int) Math.round(full * result.spoilsShare()));
    }

    /** Whole emeralds for an average worth: the whole part always, the rest as a chance ({@code roll} in [0, 1)). */
    public static int rollDrop(double worth, double roll) {
        double whole = Math.floor(Math.max(0.0, worth));
        return (int) whole + (roll < worth - whole ? 1 : 0);
    }
}
