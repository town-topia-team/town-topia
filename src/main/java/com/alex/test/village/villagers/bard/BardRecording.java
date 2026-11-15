package com.alex.test.village.villagers.bard;

import com.alex.test.ModSounds;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

import javax.annotation.Nullable;

/**
 * The bards' recorded tracks: the sound, one play-through in ticks and how many times it is played in a row.
 * Shared by the bard ({@code BardActivity.Piece}) and the Music tab's preview ({@code client/BardTrackPreview}),
 * so both play the same (10-24, round 7). The Village Tune is note blocks ({@code BardMusic}): no entry here.
 * {@link #track} is the entry id in the instrument's track list ({@code BardSettingsSchema}), saved by id.
 */
public enum BardRecording {
    /** Every violin recording is 6.875 s, 137.5 ticks: a play-through takes 138. */
    SHOW_OFF_VIOLIN(ModSounds.BARD_SHOWOFF_VIOLIN, 138, 1, BardInstrument.VIOLIN, "show_off"),
    SHOW_OFF_1_VIOLIN(ModSounds.BARD_SHOWOFF_VIOLIN_1, 138, 2, BardInstrument.VIOLIN, "show_off_1"),
    SHOW_OFF_2_VIOLIN(ModSounds.BARD_SHOWOFF_VIOLIN_2, 138, 2, BardInstrument.VIOLIN, "show_off_2"),
    SHOW_OFF_3_VIOLIN(ModSounds.BARD_SHOWOFF_VIOLIN_3, 138, 2, BardInstrument.VIOLIN, "show_off_3"),
    SHOW_OFF_4_VIOLIN(ModSounds.BARD_SHOWOFF_VIOLIN_4, 138, 2, BardInstrument.VIOLIN, "show_off_4"),
    /** 18.8 s. */
    SUNNY_LANE_VIOLIN(ModSounds.BARD_SUNNY_LANE, 376, 1, BardInstrument.VIOLIN, "sunny_lane"),
    /** 10.0 s. */
    SHOW_OFF_BASS(ModSounds.BARD_SHOWOFF_BASS, 200, 1, BardInstrument.BASS, "show_off"),
    /** 18.0 s. */
    SUNNY_LANE_BASS(ModSounds.BARD_SUNNY_LANE_BASS, 360, 1, BardInstrument.BASS, "sunny_lane"),
    /** 18.0 s. */
    GET_UP_AND_GO_GUITAR(ModSounds.BARD_GET_UP_AND_GO, 360, 1, BardInstrument.GUITAR, "get_up_and_go"),
    /**
     * Every bongo recording is 10.29 s (453599 frames at 44.1 kHz), 205.7 ticks: a play-through takes 206.
     * Played at stops and visits (BardActivity's BONGOS_1..4, B3) and on the Music tab (10-24).
     */
    SHOW_OFF_1_BONGOS(ModSounds.BARD_SHOWOFF_BONGOS_1, 206, 1, BardInstrument.BONGOS, "show_off_1"),
    SHOW_OFF_2_BONGOS(ModSounds.BARD_SHOWOFF_BONGOS_2, 206, 1, BardInstrument.BONGOS, "show_off_2"),
    SHOW_OFF_3_BONGOS(ModSounds.BARD_SHOWOFF_BONGOS_3, 206, 1, BardInstrument.BONGOS, "show_off_3"),
    SHOW_OFF_4_BONGOS(ModSounds.BARD_SHOWOFF_BONGOS_4, 206, 1, BardInstrument.BONGOS, "show_off_4");

    public final DeferredHolder<SoundEvent, SoundEvent> sound;
    public final int playTicks;
    public final int plays;
    public final BardInstrument instrument;
    public final String track;

    BardRecording(DeferredHolder<SoundEvent, SoundEvent> sound, int playTicks, int plays,
                  BardInstrument instrument, String track) {
        this.sound = sound;
        this.playTicks = playTicks;
        this.plays = plays;
        this.instrument = instrument;
        this.track = track;
    }

    /** The recording behind {@code track} in {@code instrument}'s list, or null (the Village Tune, an unknown id). */
    @Nullable
    public static BardRecording of(BardInstrument instrument, String track) {
        for (BardRecording recording : values()) {
            if (recording.instrument == instrument && recording.track.equals(track)) return recording;
        }
        return null;
    }
}
