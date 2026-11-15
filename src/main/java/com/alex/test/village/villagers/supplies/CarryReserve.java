package com.alex.test.village.villagers.supplies;

/**
 * How much of one inventory stack a villager keeps when it empties its
 * pockets into storage, given how much of the same reserve earlier stacks
 * already kept.
 *
 * <p>Counted in items, not stacks: a reserve of 16 with 15 already kept keeps
 * one more item from the next stack and deposits the rest of it. Keeping
 * whole stacks let a villager walk off with a full stack of bread on top of
 * its reserve.
 */
public final class CarryReserve {

    private CarryReserve() {}

    /** Items of a {@code stackCount} stack to keep toward a {@code target} reserve. */
    public static int toKeep(int stackCount, int alreadyKept, int target) {
        if (stackCount <= 0) return 0;
        return Math.min(stackCount, Math.max(0, target - Math.max(0, alreadyKept)));
    }
}
