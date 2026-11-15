package com.alex.test.village.villagers.core;


/**
 * A villager's job. Determines what {@link com.alex.test.village.brain.WorkActivity}
 * actually does. Add new professions here together with a work-dispatch branch in
 * {@link com.alex.test.village.villagers.registry.VillagerRegistry}.
 */
public enum Profession {
    NONE("Jobless"),
    LUMBERJACK("Lumberjack"),
    GUARD("Guard"),
    // Appended in release order — PROFESSION_DATA syncs by ordinal, so new
    // values must go at the end, never in the middle.
    FARMER("Farmer"),
    RANCHER("Rancher"),
    COOK("Cook"),
    BLACKSMITH("Blacksmith"),
    BUTCHER("Butcher"),
    MINER("Miner"),
    BUILDER("Builder"),
    BARD("Bard"),
    CLERIC("Cleric"),
    DRUID("Druid"),
    ENCHANTER("Enchanter"),
    TEACHER("Teacher"),
    TRADESMAN("Tradesman"),
    ARCHER("Archer"),
    // Not a job: an age. Children attend school instead of working and grow
    // into jobless adults. Lives in this enum because everything that routes
    // behaviour (work dispatch, textures, dumps) already keys off it.
    CHILD("Child"),
    // The barracks' elite guard: one per Barracks. Works the guard's own loop
    // (see isGuardLike) with its own, stronger skill track.
    CAPTAIN("Captain"),
    // A spell-casting defender hired at a Barracks: fights groups from behind
    // the guards (see MageActivity).
    MAGE("Mage");

    private final String displayName;

    Profession(String displayName) { this.displayName = displayName; }

    public String displayName() { return displayName; }

    /**
     * The job's name in the player's language, for screens and tooltips
     * ({@code profession.dynamicvillages.<job>} in the lang files). Keep using
     * {@link #displayName()} anywhere the name is matched or saved.
     */
    public String shownName() {
        return com.alex.test.Tr.sOr("profession.dynamicvillages." + name().toLowerCase(java.util.Locale.ROOT),
                displayName);
    }

    /** Guards and captains: the sword-and-patrol defenders that run GuardActivity. */
    public boolean isGuardLike() {
        return this == GUARD || this == CAPTAIN;
    }

    /** Everyone who defends the village: guards, captains, archers and mages. */
    public boolean isDefender() {
        return this == GUARD || this == CAPTAIN || this == ARCHER || this == MAGE;
    }

    /** Defenders that fight from range and never melee: archers and mages. */
    public boolean isRangedDefender() {
        return this == ARCHER || this == MAGE;
    }

    public String translationKey() {
        return "profession.dynamicvillages." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Profession byName(String name) {
        for (Profession p : values()) if (p.name().equals(name)) return p;
        // Saved as ARCHITECT until the job was renamed on 2026-10-17.
        if ("ARCHITECT".equals(name)) return BUILDER;
        return NONE;
    }

    public static Profession fromNetworkId(int id) {
        Profession[] values = values();
        return id >= 0 && id < values.length ? values[id] : NONE;
    }
}
