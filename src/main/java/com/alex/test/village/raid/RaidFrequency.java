package com.alex.test.village.raid;

/**
 * How often raids come by themselves, set at the Mayor (Settings > Village
 * rules > Raids). Off stops raids from coming on their own; the gaps are in
 * village days between one raid's end and the next raid's warning.
 */
public enum RaidFrequency {
    OFF("Off", 0, 0),
    RARE("Rare", 9, 14),
    NORMAL("Normal", 5, 8),
    OFTEN("Often", 3, 5);

    public static final RaidFrequency DEFAULT = NORMAL;

    private final String label;
    private final int minGap;
    private final int maxGap;

    RaidFrequency(String label, int minGap, int maxGap) {
        this.label = label;
        this.minGap = minGap;
        this.maxGap = maxGap;
    }

    public String label() { return label; }

    /** Shortest gap in village days; Off borrows Normal's, for a raid scheduled while off. */
    public int minGap() { return this == OFF ? NORMAL.minGap : minGap; }

    public int maxGap() { return this == OFF ? NORMAL.maxGap : maxGap; }

    public RaidFrequency next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public RaidFrequency previous() {
        return values()[(ordinal() + values().length - 1) % values().length];
    }

    public static RaidFrequency byIndex(int index) {
        RaidFrequency[] values = values();
        return index >= 0 && index < values.length ? values[index] : DEFAULT;
    }

    /** Reads a saved name; anything unknown (an older save) is the default. */
    public static RaidFrequency byName(String name) {
        for (RaidFrequency value : values()) {
            if (value.name().equals(name)) return value;
        }
        return DEFAULT;
    }
}
