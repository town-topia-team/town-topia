package com.alex.test.village.villagers.farmer;

/** Cheap job ordering applied only when a farmer asks the manager for work. */
final class FarmerWorkPolicy {
    private static final double PREFERRED_FIELD_BONUS = 16.0;
    // A free field with any viable job wins over a second worker on another
    // field within ordinary village walking distance. More workers can still
    // share a field when the other fields have no work for them.
    private static final double WORKER_PENALTY = 500.0;

    enum Choice { RESTORE, PLANT, RETILL, HARVEST, EXPAND, NONE }

    private FarmerWorkPolicy() {}

    static Choice choose(boolean restore, boolean harvest, boolean plant,
                         boolean retill, boolean expand, int waitingPlots) {
        if (restore) return Choice.RESTORE;
        if (waitingPlots >= 2 && plant) return Choice.PLANT;
        if (waitingPlots >= 2 && retill) return Choice.RETILL;
        if (harvest) return Choice.HARVEST;
        if (plant) return Choice.PLANT;
        if (retill) return Choice.RETILL;
        return waitingPlots == 0 && expand ? Choice.EXPAND : Choice.NONE;
    }

    static double fieldScore(int jobPriority, double distance, boolean preferred, int otherWorkers,
                             boolean urgentPlanting, boolean urgentRetilling) {
        int urgencyBonus = urgentPlanting ? 250 : urgentRetilling ? 275 : 0;
        return jobPriority + urgencyBonus - distance + (preferred ? PREFERRED_FIELD_BONUS : 0)
                - crowdPenalty(otherWorkers);
    }

    /** Points a field or patch loses for the other farmers already working it. */
    static double crowdPenalty(int otherWorkers) {
        return WORKER_PENALTY * otherWorkers;
    }
}
