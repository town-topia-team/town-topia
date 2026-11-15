package com.alex.test.village.villagers.bard.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.bard.BardBalance;
import com.alex.test.village.villagers.bard.BardInstrument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

/** Three instrument voices for the same brisk tune and skill-based arrangement. */
public final class BardMusic {
    /** Thirty-two quarter beats at 150 BPM. -1 is a deliberate breath. */
    private static final int[] LEAD = {
            12, -1, 15, 17, 19, -1, 17, 15,
            12, 10, 12, -1, 15, 12, 10, -1,
            17, -1, 19, 22, 19, 17, 15, -1,
            12, 15, 17, 15, 12, 10, 12, -1
    };
    /** Beats in one play-through of the tune. */
    public static final int TUNE_BEATS = LEAD.length;
    private static final int[] BASS = {0, 0, 5, 7, 0, 5, 7, 0};
    /** The upright bass walks each bar: root, fifth, octave, fifth of that bar's root. */
    private static final int[] WALK = {0, 7, 12, 7};
    /** Ticks from one beat to the next as a bard plays the tune (BardActivity's NOTE_INTERVAL). */
    public static final int BEAT_TICKS = 8;

    /**
     * Where one beat's notes go: into the world from a bard or a note block, or the Music tab's
     * preview (client/BardTrackPreview, 10-24), which plays the same notes to one player only.
     */
    @FunctionalInterface
    public interface NoteSink {
        void note(SoundEvent event, float volume, int semitone);
    }

    private BardMusic() {}

    public static void playBeat(ServerLevel level, NpcEntity bard, int beat, int skillLevel,
                                BardInstrument instrument, float volume) {
        float gain = Math.clamp(volume, 0f, 1f);
        if (gain == 0f) return;
        BlockPos here = bard.blockPosition();
        int note = beatNotes(beat, skillLevel, instrument, gain,
                (event, loudness, semitone) -> play(level, here, event, loudness, semitone));
        if (note < 0) return;
        double y = bard.getY() + (instrument == BardInstrument.BASS ? 2.2 : 1.65);
        level.sendParticles(ParticleTypes.NOTE, bard.getX(), y, bard.getZ(), 0, note / 24.0, 0.0, 0.0, 1.0);
    }

    /**
     * One beat of the tune on {@code instrument}, as a bard of {@code skillLevel} plays it, into {@code sink}.
     * Returns the beat's melody note (for the note particle), or -1 on a breath.
     */
    public static int beatNotes(int beat, int skillLevel, BardInstrument instrument, float gain, NoteSink sink) {
        if (instrument == BardInstrument.BASS) return bassNotes(beat, skillLevel, gain, sink);
        SoundEvent lead = switch (instrument) {
            case FLUTE -> SoundEvents.NOTE_BLOCK_FLUTE.value();
            case GUITAR -> SoundEvents.NOTE_BLOCK_GUITAR.value();
            default -> SoundEvents.NOTE_BLOCK_HARP.value();
        };
        // The guitar's accents are the harp's plucks, under it rather than bells over it.
        SoundEvent accent = switch (instrument) {
            case FLUTE -> SoundEvents.NOTE_BLOCK_BELL.value();
            case GUITAR -> SoundEvents.NOTE_BLOCK_HARP.value();
            default -> SoundEvents.NOTE_BLOCK_CHIME.value();
        };
        return arrangement(beat, skillLevel, lead, accent, true, gain, sink);
    }

    /** A note block's pitch for {@code semitone} (12 = the block's own pitch, an octave either side). */
    public static float pitch(int semitone) {
        return (float) Math.pow(2.0, (semitone - 12) / 12.0);
    }

    /**
     * The same tune played on a note block: the lead in the block's own
     * instrument (what the block under it makes it), sounding from the block,
     * with the note particles on top of it. Mob-head instruments cannot be
     * tuned, so they fall back to the harp.
     */
    public static void playBeatOnBlock(ServerLevel level, BlockPos block, int beat, int skillLevel,
                                       float volume) {
        BlockState state = level.getBlockState(block);
        SoundEvent lead = SoundEvents.NOTE_BLOCK_HARP.value();
        if (state.hasProperty(NoteBlock.INSTRUMENT)) {
            NoteBlockInstrument instrument = state.getValue(NoteBlock.INSTRUMENT);
            if (instrument.isTunable()) lead = instrument.getSoundEvent().value();
        }
        float gain = Math.clamp(volume, 0f, 1f);
        if (gain == 0f) return;
        int note = arrangement(beat, skillLevel, lead, lead, false, gain,
                (event, loudness, semitone) -> play(level, block, event, loudness, semitone));
        if (note >= 0) {
            level.sendParticles(ParticleTypes.NOTE, block.getX() + 0.5, block.getY() + 1.2, block.getZ() + 0.5,
                    0, note / 24.0, 0.0, 0.0, 1.0);
        }
    }

    /** One beat of the lead, its accents and the pulse; returns the melody note, or -1 on a breath. */
    private static int arrangement(int beat, int skillLevel, SoundEvent lead, SoundEvent accent,
                                   boolean harmony, float gain, NoteSink sink) {
        int step = Math.floorMod(beat, LEAD.length);
        int tier = BardBalance.arrangement(skillLevel);
        int note = LEAD[step];

        if (note >= 0) {
            sink.note(lead, 1.8f * gain, note);
            if (step % 4 == 0) sink.note(accent, 0.65f * gain, note);
            if (harmony && tier >= 2 && step % 2 == 0)
                sink.note(lead, 0.55f * gain, note - 5);
        }
        // Even a novice has a pulse; higher tiers add a bass line and hats.
        if (step % 4 == 0)
            sink.note(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), (tier >= 3 ? 0.75f : 0.42f) * gain, 12);
        if (step % 4 == 2)
            sink.note(SoundEvents.NOTE_BLOCK_SNARE.value(), (tier >= 3 ? 0.55f : 0.28f) * gain, 12);
        if (tier >= 3) {
            if (step % 4 == 0) {
                int bass = BASS[(step / 4) % BASS.length];
                sink.note(SoundEvents.NOTE_BLOCK_BASS.value(), 0.85f * gain, bass);
            }
            if (step % 2 == 0)
                sink.note(SoundEvents.NOTE_BLOCK_HAT.value(), 0.35f * gain, 12);
        }
        if (tier >= 4 && step % 8 == 7) {
            sink.note(SoundEvents.NOTE_BLOCK_BELL.value(), 0.55f * gain, 24);
        }
        return note;
    }

    /**
     * The tune's chords as a walking bass line on the note block bass, one note per
     * beat (each a pluck in the bass clip), over the same pulse the other voices have.
     * Higher tiers add the hats, a guitar on the bar's root and the bell turnaround.
     */
    private static int bassNotes(int beat, int skillLevel, float gain, NoteSink sink) {
        int step = Math.floorMod(beat, LEAD.length);
        int tier = BardBalance.arrangement(skillLevel);
        int root = BASS[(step / 4) % BASS.length];
        int note = root + WALK[step % 4];

        sink.note(SoundEvents.NOTE_BLOCK_BASS.value(), (step % 4 == 0 ? 1.8f : 1.5f) * gain, note);
        if (step % 4 == 0)
            sink.note(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), (tier >= 3 ? 0.75f : 0.42f) * gain, 12);
        if (step % 4 == 2)
            sink.note(SoundEvents.NOTE_BLOCK_SNARE.value(), (tier >= 3 ? 0.55f : 0.28f) * gain, 12);
        if (tier >= 2 && step % 4 == 0)
            sink.note(SoundEvents.NOTE_BLOCK_GUITAR.value(), 0.6f * gain, root + 12);
        if (tier >= 3 && step % 2 == 0)
            sink.note(SoundEvents.NOTE_BLOCK_HAT.value(), 0.35f * gain, 12);
        if (tier >= 4 && step % 8 == 7)
            sink.note(SoundEvents.NOTE_BLOCK_BELL.value(), 0.55f * gain, 24);
        return note;
    }

    private static void play(ServerLevel level, BlockPos pos, SoundEvent event,
                             float volume, int semitone) {
        level.playSound(null, pos, event, SoundSource.RECORDS, volume, pitch(semitone));
    }
}
