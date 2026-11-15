package com.alex.test.village.stats;

/** High-level activity state used to apply passive stat drift. */
public enum StatActivityStatus {
    IDLE,
    WORKING,
    WANDERING,
    SOCIALIZING,
    /** Settled at the tavern for the evening: worth three times socializing. */
    AT_TAVERN,
    ADMIRING,
    SLEEPING
}
