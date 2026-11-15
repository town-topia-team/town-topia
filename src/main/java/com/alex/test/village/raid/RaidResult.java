package com.alex.test.village.raid;

/**
 * How a raid went, and what it does to the village's mood and fame.
 *
 * <p>No Minecraft types: unit-tested on a plain JVM.
 */
public enum RaidResult {
    FLAWLESS("Flawless", 15, 8),
    VICTORY("Victory", 10, 5),
    COSTLY("Costly win", 5, 2),
    DEFEAT("Defeat", -10, -5);

    private final String label;
    private final int moodChange;
    private final int fameChange;

    RaidResult(String label, int moodChange, int fameChange) {
        this.label = label;
        this.moodChange = moodChange;
        this.fameChange = fameChange;
    }

    /**
     * @param beaten     the raiders were all beaten or broke and ran, rather than giving up at the time limit
     * @param deaths     villagers killed during the raid
     * @param stacksLost stacks carried off for good
     * @param taken      villagers carried off and not rescued
     * @param villagers  grown villagers when the raid began
     */
    public static RaidResult grade(boolean beaten, int deaths, int stacksLost, int taken, int villagers) {
        if (taken > 0 || deaths >= 3) return DEFEAT;
        if (deaths > 0 && villagers > 0 && deaths * 4 >= villagers) return DEFEAT;
        if (!beaten && stacksLost > 0) return DEFEAT;
        if (beaten && deaths == 0 && stacksLost == 0) return FLAWLESS;
        if (deaths <= 1 && stacksLost <= 1) return VICTORY;
        return COSTLY;
    }

    public String label() { return label; }

    public int moodChange() { return moodChange; }

    public int fameChange() { return fameChange; }

    public boolean isWin() { return this != DEFEAT; }

    /** Share of the spoils (war chest, Bad Omen spoils) this result gets. */
    public double spoilsShare() {
        return switch (this) {
            case FLAWLESS -> 1.0;
            case VICTORY -> 0.75;
            case COSTLY -> 0.5;
            case DEFEAT -> 0.0;
        };
    }

    public static RaidResult byName(String name) {
        for (RaidResult value : values()) {
            if (value.name().equals(name)) return value;
        }
        return null;
    }
}
