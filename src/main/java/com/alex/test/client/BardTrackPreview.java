package com.alex.test.client;

import com.alex.test.village.villagers.bard.BardInstrument;
import com.alex.test.village.villagers.bard.BardRecording;
import com.alex.test.village.villagers.bard.behavior.BardMusic;
import com.alex.test.village.villagers.bard.settings.BardSettingsSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Bard Music tab's track preview (round 7, 10-24): one track at a time, played whole to this
 * player only, from the screen (no position, nothing sent to the server). A recording plays as many
 * times in a row as the bard plays it ({@link BardRecording}); the Village Tune plays BardMusic's
 * beats as a new bard plays them. Loudness is full, on the player's Jukebox/Note Blocks slider.
 *
 * The settings screen ticks it while its row is on screen and stops it otherwise (closed, other
 * tab, other instrument, scrolled away): nothing else ever stops it, so every way out must call
 * {@link #stop}.
 */
public final class BardTrackPreview {

    /** The Village Tune's last note rings one beat before the preview ends, like the bard holding it. */
    private static final int TUNE_TICKS = BardMusic.TUNE_BEATS * BardMusic.BEAT_TICKS + BardMusic.BEAT_TICKS;
    /** The plain arrangement, as a new bard plays it. */
    private static final int TUNE_SKILL = 1;

    @Nullable private static BardInstrument instrument;
    @Nullable private static String track;
    @Nullable private static BardRecording recording;
    /** Everything started for this preview, so stop can cut off ringing notes too. */
    private static final List<SoundInstance> STARTED = new ArrayList<>();
    private static int ticks;
    private static int length;

    private BardTrackPreview() {}

    /** Starts {@code track} of {@code instrument}'s list from the top, stopping any other preview. */
    public static void play(BardInstrument instrument, String track) {
        stop();
        BardRecording found = BardRecording.of(instrument, track);
        if (found == null && !BardSettingsSchema.VILLAGE_TUNE.equals(track)) return;
        BardTrackPreview.instrument = instrument;
        BardTrackPreview.track = track;
        recording = found;
        ticks = 0;
        length = found != null ? found.playTicks * found.plays : TUNE_TICKS;
        if (found != null) start(found.sound.get(), 1f, 1f);
        else tuneBeat(0);
    }

    public static void stop() {
        if (track == null && STARTED.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        for (SoundInstance sound : STARTED) minecraft.getSoundManager().stop(sound);
        STARTED.clear();
        instrument = null;
        track = null;
        recording = null;
    }

    public static boolean isPlaying() {
        return track != null;
    }

    public static boolean isPlaying(BardInstrument instrument, @Nullable String track) {
        return BardTrackPreview.track != null && BardTrackPreview.instrument == instrument
                && BardTrackPreview.track.equals(track);
    }

    /** How far through the preview is, 0 to 1. */
    public static float progress() {
        return length <= 0 ? 0f : Math.min(1f, ticks / (float) length);
    }

    /** One client tick: the next play-through or beat, and the end. Called by the settings screen only. */
    public static void tick() {
        if (track == null) return;
        ticks++;
        if (ticks >= length) {
            stop();
            return;
        }
        if (recording != null) {
            if (ticks % recording.playTicks == 0 && ticks < recording.playTicks * recording.plays) {
                start(recording.sound.get(), 1f, 1f);
            }
        } else if (ticks % BardMusic.BEAT_TICKS == 0) {
            tuneBeat(ticks / BardMusic.BEAT_TICKS);
        }
    }

    private static void tuneBeat(int beat) {
        if (beat >= BardMusic.TUNE_BEATS || instrument == null) return;
        BardMusic.beatNotes(beat, TUNE_SKILL, instrument, 1f,
                (event, loudness, semitone) -> start(event, loudness, BardMusic.pitch(semitone)));
    }

    private static void start(SoundEvent event, float volume, float pitch) {
        // No position and relative: heard the same wherever the player stands, by this player only.
        SoundInstance sound = new SimpleSoundInstance(event.getLocation(), SoundSource.RECORDS,
                Math.min(1f, volume), pitch, SoundInstance.createUnseededRandom(), false, 0,
                SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true);
        STARTED.add(sound);
        Minecraft.getInstance().getSoundManager().play(sound);
    }
}
