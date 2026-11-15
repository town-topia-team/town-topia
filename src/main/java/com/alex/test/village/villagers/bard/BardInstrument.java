package com.alex.test.village.villagers.bard;

import com.alex.test.animation.VillagerAnimationState;

/** Instrument option saved as a stable index in the bard's settings (the enum order is the index). */
public enum BardInstrument {
    VIOLIN(VillagerAnimationState.PLAY_VIOLIN, VillagerAnimationState.VIOLIN_DANCE,
            VillagerAnimationState.VIOLIN_PLAY, true),
    FLUTE(VillagerAnimationState.PLAY_FLUTE, VillagerAnimationState.FLUTE_DANCE,
            VillagerAnimationState.FLUTE_PLAY, true),
    /** The upright bass stands on the floor: walked with empty hands, played standing. */
    BASS(VillagerAnimationState.WALK, VillagerAnimationState.BASS_JAM,
            VillagerAnimationState.BASS_PLAY, false),
    /** The acoustic guitar, slung across the front: strummed on the move, at stops and in a livelier jam. */
    GUITAR(VillagerAnimationState.PLAY_GUITAR, VillagerAnimationState.GUITAR_JAM,
            VillagerAnimationState.GUITAR_PLAY, true),
    /**
     * The bongos, played sitting on a low stool. In the tavern band TavernBand hands them out
     * after violin, bass and guitar. A settings choice since 10-24 ({@link #BONGOS_CHOICE}, after
     * Bard's choice); by day it drums its own pieces on the stool at stops and visits (B3).
     */
    BONGOS(VillagerAnimationState.WALK, VillagerAnimationState.BONGOS_JAM,
            VillagerAnimationState.BONGOS_PLAY, false);

    /** How many of the first values are saved as their own index (0..3: violin, flute, bass, guitar). */
    public static final int SETTING_CHOICES = 4;
    /**
     * The menu's choice after the four instruments, "Bard's choice": the bard picks one itself
     * for every stop and visit (BardActivity). {@link #fromSetting} gives the violin for it.
     */
    public static final int BARDS_CHOICE = SETTING_CHOICES;
    /** The bongos' saved index, after Bard's choice so older saves keep their pick (10-24). */
    public static final int BONGOS_CHOICE = BARDS_CHOICE + 1;

    private final VillagerAnimationState walk;
    private final VillagerAnimationState dance;
    private final VillagerAnimationState stand;
    private final boolean playsOnTheMove;

    BardInstrument(VillagerAnimationState walk, VillagerAnimationState dance,
                   VillagerAnimationState stand, boolean playsOnTheMove) {
        this.walk = walk;
        this.dance = dance;
        this.stand = stand;
        this.playsOnTheMove = playsOnTheMove;
    }

    public static BardInstrument fromSetting(int index) {
        if (index == BONGOS_CHOICE) return BONGOS;
        return index >= 0 && index < SETTING_CHOICES ? values()[index] : VIOLIN;
    }

    /** The pose walking between stops: the violin and flute play on, the bass is carried silent. */
    public VillagerAnimationState walkingAnimation() { return walk; }

    /** The pose at a stop with "dance at stops" on. */
    public VillagerAnimationState dancingAnimation() { return dance; }

    /** The pose at a stop with "dance at stops" off. */
    public VillagerAnimationState standingAnimation() { return stand; }

    /** Whether it can be played while walking ("play while walking"). */
    public boolean playsOnTheMove() { return playsOnTheMove; }
}
