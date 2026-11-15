package com.alex.test.village.farawake;

/**
 * Why a far villager's chunk is wanted, best first. With no caps every needed chunk is held;
 * the rank only decides which of them is loaded first when many are new at once (a village
 * waking, a player arriving). No Minecraft classes: unit tested.
 */
public enum FarRank {
    /** Walking home to bed (the old bed lantern's job). */
    HOME,
    /** Asleep in bed: held so the bed stays real, the body is not moved. */
    ASLEEP,
    /** Fetching or eating food. */
    FOOD,
    /** At work or walking to it. */
    WORK,
    /** Free time: strolls, visits, the tavern. */
    FREE,
    /** The chunk just ahead on a walk, not the one the body stands in. */
    NEAR
}
