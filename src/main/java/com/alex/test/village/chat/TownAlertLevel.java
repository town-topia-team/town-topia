package com.alex.test.village.chat;

/**
 * How much a player hears about their village's troubles: each player's own
 * choice, set at the Mayor under Settings > Village rules > Just for you.
 * Saved per player by {@link TownAlertPrefs}.
 */
public enum TownAlertLevel {
    /** Every hit, sighting and cleric on the way, as alerts were before the fight bar. */
    ALL("All"),
    /** The fight bar, one line when a fight starts and one when it ends, and the big events. */
    NORMAL("Normal"),
    /** The fight bar and deaths. */
    DEATHS("Deaths"),
    /** No fight bar, no outlines, no chat. */
    OFF("Off");

    /**
     * A fight with five zombies used to fill the chat box with alerts every
     * ten seconds; players learned to stop reading it.
     */
    public static final TownAlertLevel DEFAULT = NORMAL;

    private final String label;

    TownAlertLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** What it does, in one short line under the setting's title. */
    public String description() {
        return switch (this) {
            case ALL -> "Every hit and sighting in chat";
            case NORMAL -> "Fight bar, start, deaths and end";
            case DEATHS -> "Fight bar and deaths only";
            case OFF -> "No fight bar and no alerts";
        };
    }

    /** Every single hit, sighting and cleric on the way. */
    public boolean hearsEverything() {
        return this == ALL;
    }

    /** Fight start and end, a player hitting a villager, hazards, the tower bell, a saving prayer. */
    public boolean hearsImportant() {
        return this == ALL || this == NORMAL;
    }

    public boolean hearsDeaths() {
        return this != OFF;
    }

    /** The fight bar and the glowing outlines. */
    public boolean seesFight() {
        return this != OFF;
    }

    public TownAlertLevel next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static TownAlertLevel byIndex(int index) {
        TownAlertLevel[] values = values();
        return index >= 0 && index < values.length ? values[index] : DEFAULT;
    }
}
