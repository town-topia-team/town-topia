package com.alex.test.village.raid.warlord;

/**
 * What the Warlord is doing that plays a looping clip of its own, synced to
 * clients. Everything else (walking, idling) follows from its movement, and the
 * one-shot clips (swing, slam, roar, pound, leap, land, death) are triggered.
 */
public enum WarlordAction {
    NONE,
    /** Running at its target: the "charge" loop. */
    CHARGE,
    /** Ran into a wall: the "dazed" loop, no attacks. */
    DAZED;

    public static WarlordAction byIndex(int index) {
        WarlordAction[] all = values();
        return index >= 0 && index < all.length ? all[index] : NONE;
    }
}
