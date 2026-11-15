package com.alex.test.village.villagers.bard.behavior;

import com.alex.test.ModSounds;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.network.BardPianoPayload;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.FleeActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.OpenGround;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.TavernBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.villagers.bard.BardBalance;
import com.alex.test.village.villagers.bard.BardInstrument;
import com.alex.test.village.villagers.bard.BardRecording;
import com.alex.test.village.villagers.bard.settings.BardSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Daytime instrument strolls and an evening set: piano once, then instruments until closing. */
public final class BardActivity extends WorkActivity {

    private static final int NOTE_INTERVAL = BardMusic.BEAT_TICKS;
    private static final int WANDER_RADIUS = 32;
    private static final int MIN_STOP_DISTANCE_SQ = 36;
    @Nullable private MoveActivity move;
    /** The track played at this stop and the ticks left of it (0 between stops). */
    private Piece stopPiece = Piece.VIOLIN_TUNE;
    private int stopTimer;
    /** After a stop the instrument is put down this long, so the next track starts its clip afresh. */
    private int restTimer;
    private static final int REST_TICKS = 20;
    /** Ticks to the next happiness award while it plays: every AWARD_TICKS (the old 5 s Song length). */
    private int awardTimer;
    private static final int AWARD_TICKS = 100;
    /** Beats of the Village Tune played on the move. */
    private int noteTimer;
    private int noteIndex;
    private int burstsCompleted;
    /** On Bard's choice, the instrument picked for this stop or visit; null on a set instrument. */
    @Nullable private BardInstrument chosen;
    private boolean workedThisSession;
    private final Map<UUID, Float> grantedThisSession = new HashMap<>();
    /** The tavern this set is played in, or null for the daytime village stroll. */
    @Nullable private final Building eveningVenue;
    private final BardPianoPerformance piano = new BardPianoPerformance();
    @Nullable private BlockPos pianoStage;
    private float pianoVolume;
    private long lastPianoAward;
    private boolean pianoStopped;
    private boolean eveningInstruments;
    /** The tavern's band this bard plays in tonight, and its own place in the line. */
    @Nullable private TavernBand band;
    @Nullable private BlockPos bandSpot;
    /** The part it last played in the band, for the diary. */
    @Nullable private BardInstrument bandPart;
    @Nullable private UUID bardId;
    private long lastTick;
    /** The play-through (its start time) the bongo clip was started in step with, and which clip. */
    private long bongoSong = Long.MIN_VALUE;
    @Nullable private VillagerAnimationState bongoClip;

    // Daytime visits: walk to the unhappiest villager nearby and play a piece for them,
    // one of the pieces below, picked at random each visit.
    private static final int SERENADE_RADIUS = 32;

    /**
     * The serenade pieces: a recording, how long it lasts, how many times it is played
     * through, how long the bard holds the instrument for it, its id in the instrument's
     * track list (BardSettingsSchema) and how many lots it has in the draw. Every violin
     * recording is 6.875 s, 137.5 ticks: a play-through takes 138 ticks, so the bow stops
     * within half a tick (25 ms) of the music, the closest a 20-tick game can get.
     */
    private enum Piece {
        // Sound, play-through, plays, instrument and track id come from BardRecording (shared with the
        // Music tab's preview, 10-24); here only how long the instrument is held and the lots.
        /** showoff_violin.ogg, 6.875 s. */
        VIOLIN(BardRecording.SHOW_OFF_VIOLIN, 138, 1),
        /** showoff_violin_2.ogg, 6.875 s, played through twice back to back. */
        VIOLIN_TWICE(BardRecording.SHOW_OFF_2_VIOLIN, 276, 1),
        /** showoff_violin_1.ogg, 6.875 s, played through twice back to back. */
        VIOLIN_1_TWICE(BardRecording.SHOW_OFF_1_VIOLIN, 276, 1),
        /** showoff_violin_3.ogg, 6.875 s, played through twice back to back. */
        VIOLIN_3_TWICE(BardRecording.SHOW_OFF_3_VIOLIN, 276, 1),
        /** showoff_violin_4.ogg, 6.875 s, played through twice back to back. */
        VIOLIN_4_TWICE(BardRecording.SHOW_OFF_4_VIOLIN, 276, 1),
        /**
         * sunny_lane_violin.ogg, "Sunny Lane", 18.8 s of long joined notes. Its last note has died
         * away by 17.8 s and the rest is room echo, so the bow stops at 357 ticks and the echo plays out.
         * Two lots, so it comes up more often than the others.
         */
        SUNNY_LANE(BardRecording.SUNNY_LANE_VIOLIN, 357, 2),
        /**
         * showoff_bass.ogg, 10.0 s, on the upright bass. Its last note is played by 8.0 s
         * and the rest is that note dying away, so the bass is put down at 8.1 s: on
         * a pluck (the clip's plucks fall 10 ticks after the pose starts, then every 8).
         */
        BASS(BardRecording.SHOW_OFF_BASS, 162, 1),
        /**
         * sunny_lane_bass.ogg, "Sunny Lane" on the upright bass, 18.0 s. Every note sits on
         * one of the clip's plucks (10 ticks in, then every 8); the last, a low G, at 314
         * ticks. The bass is put down on the pluck after it, at 322, and the note rings out.
         */
        SUNNY_LANE_BASS(BardRecording.SUNNY_LANE_BASS, 322, 2),
        /**
         * get_up_and_go_guitar.ogg, "Get Up and Go", 18.0 s, strummed. Its strums sit on the
         * guitar clips' beats (10 ticks in, then every 4 an eighth note); the last big chord
         * at 330 ticks. The guitar is put down at 334 and the chord rings out.
         */
        GET_UP_AND_GO(BardRecording.GET_UP_AND_GO_GUITAR, 334, 1),
        /**
         * showoff_bongos_1..4.ogg, the author's bongo showpieces, 10.29 s each, played once on the stool to the
         * end of the sound. The slap clips are the band's (no new ones were wanted, B3 10-24): they keep their
         * own beat, not these recordings' hits.
         */
        BONGOS_1(BardRecording.SHOW_OFF_1_BONGOS, 206, 1),
        BONGOS_2(BardRecording.SHOW_OFF_2_BONGOS, 206, 1),
        BONGOS_3(BardRecording.SHOW_OFF_3_BONGOS, 206, 1),
        BONGOS_4(BardRecording.SHOW_OFF_4_BONGOS, 206, 1),
        /**
         * The Village Tune: no recording, one play-through of the note block tune on that
         * instrument (the bass walks it), its first note on the clip's first beat, held a
         * breath past the last note. The flute's only piece.
         */
        VIOLIN_TUNE(BardInstrument.VIOLIN),
        FLUTE_TUNE(BardInstrument.FLUTE),
        BASS_TUNE(BardInstrument.BASS),
        GUITAR_TUNE(BardInstrument.GUITAR);

        /** The recording, or null for the note block tune. */
        @Nullable final DeferredHolder<SoundEvent, SoundEvent> sound;
        final int playTicks, plays, holdTicks;
        final BardInstrument instrument;
        final boolean bass;
        final String track;
        final int lots;

        Piece(@Nullable DeferredHolder<SoundEvent, SoundEvent> sound, int playTicks, int plays, int holdTicks,
              BardInstrument instrument, String track, int lots) {
            this.sound = sound;
            this.playTicks = playTicks;
            this.plays = plays;
            this.holdTicks = holdTicks;
            this.instrument = instrument;
            this.bass = instrument == BardInstrument.BASS;
            this.track = track;
            this.lots = lots;
        }

        /** A recording, held for {@code holdTicks} (at most its plays) with {@code lots} in the draw. */
        Piece(BardRecording recording, int holdTicks, int lots) {
            this(recording.sound, recording.playTicks, recording.plays, holdTicks, recording.instrument,
                    recording.track, lots);
        }

        /** The Village Tune on {@code instrument}. */
        Piece(BardInstrument instrument) {
            this(null, BardMusic.TUNE_BEATS * NOTE_INTERVAL, 1,
                    FIRST_NOTE_TICKS + BardMusic.TUNE_BEATS * NOTE_INTERVAL, instrument,
                    BardSettingsSchema.VILLAGE_TUNE, 1);
        }

        /** Whether this piece is switched on in its instrument's track list. */
        boolean on(ProfessionSettings settings) {
            return settings.isAllowed(BardSettingsSchema.tracksOf(instrument), track);
        }

        /** Played where it is, never following a listener: the bass stands on the floor, the bongos on the stool. */
        boolean staysPut() {
            return bass || instrument == BardInstrument.BONGOS;
        }
    }
    private static final int CHASE_LIMIT_TICKS = 400;
    private static final int REPATH_INTERVAL = 40;
    private static final double REPATH_DRIFT_SQ = 16;
    private static final double PLAY_DISTANCE_SQ = 9;
    /** One in-game hour before the same villager is picked again. */
    private static final long CHEERED_COOLDOWN = 1000;
    /** A villager this much sadder than everyone else is picked even while cooling down. */
    private static final float FAR_SADDER = 25f;
    /** Shared by all bards: once one has played for a villager, the others move on too. */
    private static final Map<UUID, Long> LAST_CHEERED = new HashMap<>();
    /**
     * Shared by all bards: who is on their way to or playing for whom, right now. A
     * villager in here is left to that bard, and so is everyone near that bard, so two
     * bards never walk up to the same villager or play over each other.
     */
    private static final Map<UUID, Visit> VISITS = new HashMap<>();
    private record Visit(UUID bard, BlockPos bardPos, long seenAt) {}
    /** A visit not refreshed for this long was dropped without a stop (unloaded, removed). */
    private static final int VISIT_STALE_TICKS = 40;
    /** Nobody within this many blocks of another bard on a visit is picked. */
    private static final int OTHER_BARD_GAP = 12;
    @Nullable private VillagerModel serenadeTarget;
    @Nullable private MoveActivity serenadeWalk;
    @Nullable private BlockPos serenadeGoal;
    private int chaseTicks;
    private int repathTimer;
    private int serenadeTimer;
    private boolean listenerHeld;
    /** Walking between stops with its instrument out (the "play while walking" setting). */
    private boolean playingOnTheMove;
    /** This visit's piece. The bass one is played standing on the upright bass, in front of them. */
    private Piece serenadePiece = Piece.VIOLIN;
    private static final float STOP_TO_LISTEN_CHANCE = 0.2f;
    /**
     * Slow follow while playing to a listener: a quarter of a job walk's pace. A mob's pace
     * goes with the square of this number, so half the walk setting gives a quarter of the
     * pace (the old 0.1075, a fifth of the setting, came out near 4%, and under 2% once
     * the walk's slow finish was added).
     */
    private static final float FOLLOW_SPEED = VillagerModel.WALK_SPEED * 0.5f;
    /** Mid-song, he only sets off after a listener more than 3.5 blocks away. */
    private static final double FOLLOW_START_SQ = 12.25;

    public BardActivity() {
        this(null);
    }

    private BardActivity(@Nullable Building eveningVenue) {
        this.eveningVenue = eveningVenue;
    }

    /** The evening's performance, in and around {@code tavern}. */
    public static BardActivity eveningSet(Building tavern) {
        return new BardActivity(tavern);
    }

    public boolean isEveningSet() { return eveningVenue != null; }

    @Override
    public String name() { return eveningVenue != null ? "bard_evening" : super.name(); }

    @Override
    protected Status doTick(Context ctx) {
        if (eveningVenue != null) return tickPianoSet(ctx);
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), BardSettingsSchema.SCHEMA);
        if (!settings.getBool(BardSettingsSchema.WORK_ENABLED)) {
            endSet(ctx);
            return finishOrFail();
        }

        if (serenadeTarget != null) return tickSerenade(ctx, settings);
        BardInstrument instrument = instrument(ctx, settings);
        if (move != null) {
            if (!settings.getBool(BardSettingsSchema.ROAM) && eveningVenue == null) {
                move.stop(ctx);
                move = null;
                beginPerforming(ctx, settings, instrument);
                return Status.RUNNING;
            }
            // Only its own instrument travels with the bard, playing the Village Tune; with
            // that track switched off it just walks. The bongos have no Village Tune (an id
            // missing from a list reads as allowed): it walks with empty hands.
            boolean playWhileWalking = settings.getBool(BardSettingsSchema.PLAY_WHILE_WALKING)
                    && instrument != BardInstrument.BONGOS
                    && settings.isAllowed(BardSettingsSchema.tracksOf(instrument), BardSettingsSchema.VILLAGE_TUNE);
            playingOnTheMove = playWhileWalking;
            playPose(ctx, playWhileWalking
                    ? instrument.walkingAnimation() : VillagerAnimationState.WALK);
            // The bass is carried between stops, silent; it is only played standing.
            if (playWhileWalking && instrument.playsOnTheMove()) tickMelody(ctx, settings, instrument);
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            playingOnTheMove = false;
            beginPerforming(ctx, settings, instrument);
            return Status.RUNNING;
        }

        if (stopTimer > 0) {
            tickStop(ctx, settings);
            return Status.RUNNING;
        }
        if (restTimer > 0) {
            restTimer--;
            return Status.RUNNING;
        }
        return chooseNext(ctx, settings, instrument);
    }

    @Override
    public void doStop(Context ctx) {
        endSet(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return (move != null || serenadeWalk != null) && eveningVenue == null;
    }

    /** Not while on the way to a listener, following one mid-song, or playing between stops. */
    @Override
    public boolean allowsChatWhileWalking() {
        return serenadeTarget == null && !playingOnTheMove;
    }

    private void endSet(Context ctx) {
        if (move != null) { move.stop(ctx); move = null; }
        playingOnTheMove = false;
        endStop(ctx);
        clearSerenade(ctx);
        stopPiano(ctx);
        if (band != null) {
            band.leave(ctx.npc());
            band = null;
        }
    }

    private void stopPiano(Context ctx) {
        if (piano.started() && !pianoStopped) {
            pianoStopped = true;
            sendPiano(ctx, BardPianoPayload.STOP, 0f);
            if (band != null) band.pianoFinished(ctx.npc().getUUID());
        }
    }

    /**
     * At the tavern stage and not mid-recording: the brain may step the bard
     * off to eat here (dinner before the set, or a break when starving) and
     * start the set again afterwards. Not on the walk over, not during the
     * piano recording, and not during its part of the band's song.
     */
    public boolean canStepOffToEat() {
        return eveningVenue != null && pianoStage != null && move == null
                && !(piano.started() && !pianoStopped)
                && !(band != null && bardId != null && band.inSong(bardId, lastTick));
    }

    /**
     * The tavern set reads its settings itself every tick (instrument, volume, tracks; Working off ends it
     * there), so a settings change must not stop it. The brain's check asks for work hours, which the
     * evening never is: any change used to end the set, the piano for the whole night (10-24, F1).
     */
    @Override
    public boolean holdsThroughSettingsChange() {
        return eveningVenue != null;
    }

    /** A serenade piece or a stop's track, once started, is played to its end: no chat or routine trip cuts in. */
    @Override
    public boolean allowsRoutineInterruption() {
        if (eveningVenue == null) return serenadeTimer <= 0 && stopTimer <= 0;
        return !piano.started() && !eveningInstruments;
    }

    /**
     * The tavern set, played as a band with every other bard here: the piano recording
     * once a night (by the first bard to start it; the others dance along), then the
     * band's song over and over until closing. Each bard has its own place in a line
     * through the stage and faces the guests.
     */
    private Status tickPianoSet(Context ctx) {
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), BardSettingsSchema.SCHEMA);
        EveningSchedule.Phase phase = ctx.brain().eveningPhase(ctx);
        long now = ctx.level().getGameTime();
        lastTick = now;
        bardId = ctx.npc().getUUID();
        BardInstrument instrument = instrument(ctx, settings);
        if (band == null) band = TavernBand.of(eveningVenue, now);
        // On Bard's choice the band hands it whichever part is free.
        band.touch(ctx.npc(), bardsChoice(settings) ? null : instrument, bandSpot, now,
                phase == EveningSchedule.Phase.GATHER);
        boolean myPiano = piano.started() && !pianoStopped;
        // Closing time: the piano or the song in progress is played to its end first.
        if (phase == EveningSchedule.Phase.DAY
                || (phase != EveningSchedule.Phase.GATHER && !myPiano && !band.inSong(bardId, now))) {
            endSet(ctx);
            return Status.DONE;
        }
        if (!eveningVenue.isValid() || eveningVenue.getNavMeshBlocks().isEmpty()
                || !settings.getBool(BardSettingsSchema.WORK_ENABLED)) {
            endSet(ctx);
            return Status.FAILED;
        }
        // The music is on: guests may get up and dance to it.
        if (myPiano || eveningInstruments) {
            com.alex.test.village.brain.TavernDances.duringSet(ctx, eveningVenue);
        }
        if (myPiano) {
            if (piano.finished(now)) {
                workedThisSession = true;
                stopPiano(ctx);
                eveningInstruments = true;
                diary(ctx, "BARD piano finished; the band plays on");
                return Status.RUNNING;
            }
            float volume = settings.getInt(BardSettingsSchema.MUSIC_VOLUME) / 100f;
            if (volume != pianoVolume) {
                pianoVolume = volume;
                sendPiano(ctx, BardPianoPayload.VOLUME, volume);
            }
            ctx.npc().setAnimation(danceOrStand(settings));
            faceGuests(ctx, now);
            // Music volume is only how loud players hear it: villagers enjoy the piano at any volume (F2).
            if (now - lastPianoAward >= 160) {
                lastPianoAward = now;
                boostNearbyHappiness(ctx, null);
                ctx.brain().onBardBurstCompleted(ctx);
            }
            return Status.RUNNING;
        }
        if (pianoStage == null) {
            BlockPos stage = eveningVenue instanceof TavernBuilding tavern ? tavern.getStagePos() : null;
            pianoStage = tileNearest(stage != null ? stage : eveningVenue.getDoorPos());
            if (pianoStage == null) return Status.FAILED;
            bandSpot = band.claimSpot(bardId, eveningVenue, pianoStage, band.audience(ctx, eveningVenue, now));
            if (bandSpot == null) bandSpot = pianoStage;
            if (bandSpot.distSqr(ctx.npc().blockPosition()) > 2) {
                move = new MoveActivity(bandSpot, VillagerModel.WALK_SPEED);
            }
        }
        if (move != null) {
            ctx.npc().setAnimation(VillagerAnimationState.WALK);
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return status;
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED) {
                // Its place in the line could not be reached: it stands at the stage instead.
                // Giving up here used to end the bard's set for the whole night.
                if (bandSpot == null || bandSpot.equals(pianoStage)) return status;
                diary(ctx, "BARD could not reach its place in the band at " + bandSpot.toShortString()
                        + "; playing from the stage");
                bandSpot = pianoStage;
                if (bandSpot.distSqr(ctx.npc().blockPosition()) > 2) {
                    move = new MoveActivity(bandSpot, VillagerModel.WALK_SPEED);
                    return Status.RUNNING;
                }
            }
        }
        BardInstrument given = band.ready(bardId);
        if (given != null) {
            bandPart = given;
            diary(ctx, "BARD takes the " + given.name().toLowerCase(java.util.Locale.ROOT) + " in the band"
                    + (bardsChoice(settings) ? " (bard's choice)" : ""));
        }
        // Another bard has the piano tonight: this one never plays it, and dances (stands with Dancing OFF) while it plays.
        if (!ctx.brain().bardPianoPlayed() && band.pianoTaken(now) && !band.claimPiano(bardId, now)) {
            ctx.brain().tryStartBardPiano(ctx);
        }
        if (band.pianoPlaying(now)) {
            ctx.npc().setAnimation(danceOrStand(settings));
            faceGuests(ctx, now);
            return Status.RUNNING;
        }
        // Saved or interrupted recordings are not replayed; on to the band's song.
        if (ctx.brain().bardPianoPlayed()) {
            eveningInstruments = true;
            playInBand(ctx, settings, instrument, phase == EveningSchedule.Phase.GATHER, now);
            return Status.RUNNING;
        }
        if (waitingForGuests(ctx)) {
            waitAtStage(ctx);
            return Status.RUNNING;
        }
        if (!band.claimPiano(bardId, now) || !ctx.brain().tryStartBardPiano(ctx)) return Status.RUNNING;
        piano.start(now);
        lastPianoAward = now;
        pianoVolume = settings.getInt(BardSettingsSchema.MUSIC_VOLUME) / 100f;
        ctx.model().stopMoving();
        ctx.npc().setAnimation(danceOrStand(settings));
        sendPiano(ctx, BardPianoPayload.START, pianoVolume);
        diary(ctx, "BARD piano recording started (132.05 seconds)");
        return Status.RUNNING;
    }

    /**
     * This bard's part of the band's song. Up to {@link TavernBand#MAX_PLAYERS} bards play
     * it, each on its own instrument with that instrument's playing animation, from the
     * first beat to the last; the recording of each part comes from the first of them on
     * that instrument. A bard who gets here mid-song joins in straight away, its part
     * coming in in time with the rest. With only flute players there is no song, and the
     * old note-block tune is played instead; bards set to the bongos with no violin, bass or
     * guitar beside them play their own pieces the same way (B3, 10-24). A bard beyond the
     * fourth dances to the music (stands with Dancing OFF).
     */
    private void playInBand(Context ctx, ProfessionSettings settings, BardInstrument instrument,
                            boolean newSongs, long now) {
        band.tick(now, newSongs);
        faceGuests(ctx, now);
        if (band.inSong(bardId, now)) {
            // A note-block tune it was playing before the song (only flute players) stops.
            endStop(ctx);
            BardInstrument part = band.partOf(bardId);
            if (part != null && part != bandPart) {
                diary(ctx, "BARD switches to the " + part.name().toLowerCase(java.util.Locale.ROOT) + " in the band");
                bandPart = part;
            }
            if (part == BardInstrument.BONGOS) {
                playBongos(ctx, settings, newSongs, now);
            } else {
                bongoSong = Long.MIN_VALUE;
                setPerformanceAnimation(ctx, settings, part != null ? part : instrument);
            }
            if (awardTimer <= 0) awardTimer = AWARD_TICKS;
            if (--awardTimer <= 0) finishBurst(ctx, settings);
            return;
        }
        bongoSong = Long.MIN_VALUE;
        if (!band.hasPartPlayer()) {
            if (stopTimer > 0) tickStop(ctx, settings);
            else if (restTimer > 0) restTimer--;
            else beginPerforming(ctx, settings, instrument);
            return;
        }
        ctx.npc().setAnimation(danceOrStand(settings));
    }

    /**
     * The bongo clips are played in step with the drum part: clip time 0 is song time 0,
     * and the client shows clip time = ticks since the clip was started - the blend in. So
     * a clip is only started on a tick that lines it up (song start - blend + a whole
     * number of clip lengths, to the nearest tick, 25 ms at most), and again right before
     * the next play-through starts, since 48 s is not a whole number of bars (the drums are
     * quiet then). A drummer who comes in mid-song, or switches between calm and joyful,
     * keeps the clip it has or stands ready until such a tick: at most 2.6 s (calm) or
     * 5.2 s (joyful). The start's game time goes to the client with it
     * ({@code NpcEntity.startBongoClip}), so a player who was looking away, or comes in
     * mid-song, still sees the slaps on the beat.
     */
    private void playBongos(Context ctx, ProfessionSettings settings, boolean newSongs, long now) {
        VillagerAnimationState want = settings.getBool(BardSettingsSchema.DANCE_AT_STOPS)
                ? BardInstrument.BONGOS.dancingAnimation() : BardInstrument.BONGOS.standingAnimation();
        NpcEntity npc = ctx.npc();
        long start = band.songStartedAt();
        long next = start + TavernBand.SONG_TICKS;
        int blend = VillagerAnimationState.WORK_BLEND_TICKS;
        if (newSongs && now == next - blend) {
            // Its first frame lands on the next play-through's first tick.
            npc.startBongoClip(want);
            bongoSong = next;
            bongoClip = want;
            return;
        }
        if (bongoSong == next && npc.getAnimation() == bongoClip) return;
        if (bongoSong == start && bongoClip == want && npc.getAnimation() == want) return;
        float length = want == VillagerAnimationState.BONGOS_JAM
                ? VillagerAnimationState.BONGOS_JAM_TICKS : VillagerAnimationState.BONGOS_PLAY_TICKS;
        long first = start - blend;
        long m = Math.max(0, (long) Math.floor((now - first) / length));
        while (first + Math.round(m * length) < now) m++;
        if (first + Math.round(m * length) == now) {
            npc.startBongoClip(want);
            bongoSong = start;
            bongoClip = want;
        } else if (bongoSong != start || npc.getAnimation() != bongoClip) {
            bongoSong = Long.MIN_VALUE;
            npc.setAnimation(VillagerAnimationState.IDLE);
        }
    }

    /**
     * While the piano recording plays, or the band plays without it: dancing, or standing (facing the guests)
     * with Dancing OFF. It always danced before (10-24, F4).
     */
    private static VillagerAnimationState danceOrStand(ProfessionSettings settings) {
        return settings.getBool(BardSettingsSchema.DANCE_AT_STOPS)
                ? VillagerAnimationState.DANCE : VillagerAnimationState.IDLE;
    }

    /** Turns the bard, body and head, towards the middle of the guests. */
    private void faceGuests(Context ctx, long now) {
        if (band == null) return;
        Vec3 guests = band.audience(ctx, eveningVenue, now);
        NpcEntity npc = ctx.npc();
        double dx = guests.x - npc.getX(), dz = guests.z - npc.getZ();
        if (dx * dx + dz * dz < 0.25) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float turned = Mth.approachDegrees(npc.getYRot(), yaw, FACE_TURN_PER_TICK);
        npc.setYRot(turned);
        npc.setYBodyRot(turned);
        npc.getLookControl().setLookAt(guests.x, guests.y + 1.5, guests.z, 30f, 30f);
    }

    /** Degrees a tick the bard turns to face the guests: a quarter turn in about half a second. */
    private static final float FACE_TURN_PER_TICK = 9f;

    private void sendPiano(Context ctx, int action, float volume) {
        if (pianoStage == null) return;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(ctx.npc(),
                new BardPianoPayload(ctx.npc().getUUID(), ctx.npc().getId(), pianoStage, action, volume));
    }

    private Status chooseNext(Context ctx, ProfessionSettings settings,
                              BardInstrument instrument) {
        // Bard's choice: a new instrument for every stop and visit.
        if (chosen != null) {
            chosen = null;
            instrument = instrument(ctx, settings);
        }
        if (!settings.getBool(BardSettingsSchema.ROAM)) {
            beginPerforming(ctx, settings, instrument);
            return Status.RUNNING;
        }
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || graph.allNodes().isEmpty()) return finishOrFail();

        if (settings.getBool(BardSettingsSchema.CHEER_SADDEST) && startSerenade(ctx, settings, instrument)) {
            return Status.RUNNING;
        }

        BlockPos here = ctx.npc().blockPosition();
        BlockPos center = villageCenter(ctx);
        java.util.function.Predicate<NavNode> open = OpenGround.openTest(ctx.mgr());
        NavNode choice = null;
        int candidates = 0;
        for (NavNode node : graph.nodesInBox(center, WANDER_RADIUS, 6)) {
            if (!node.isRoutable() || node.feetPos().distSqr(here) < MIN_STOP_DISTANCE_SQ) continue;
            // Stops in a ranch (pen or gate) are left out.
            if (!open.test(node)) continue;
            if (ctx.npc().getRandom().nextInt(++candidates) == 0) choice = node;
        }
        if (choice != null) {
            move = new MoveActivity(choice.feetPos(), VillagerModel.WALK_SPEED);
            if (burstsCompleted == 0) diary(ctx, "BARD strolling and playing around village");
        } else {
            // A tiny or temporarily disconnected graph still permits a local set.
            beginPerforming(ctx, settings, instrument);
        }
        return Status.RUNNING;
    }

    private boolean waitLogged;

    /** Start only once a guest has settled at this tavern. */
    private boolean waitingForGuests(Context ctx) {
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            if (m.brain().currentActivity() instanceof com.alex.test.village.brain.EveningActivity e
                    && e.mode() == com.alex.test.village.brain.EveningActivity.Mode.TAVERN
                    && e.venue() == eveningVenue && e.isHangingAround()) return false;
        }
        return true;
    }

    /** Waits at the stage until guests come. */
    private void waitAtStage(Context ctx) {
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        BlockPos door = eveningVenue.getDoorPos();
        ctx.npc().getLookControl().setLookAt(door.getX() + 0.5, door.getY() + 1.0, door.getZ() + 0.5, 20f, 20f);
        if (!waitLogged) {
            waitLogged = true;
            diary(ctx, "BARD at the tavern, waiting for the first guests");
        }
    }

    @Nullable
    private BlockPos tileNearest(@Nullable BlockPos anchor) {
        if (anchor == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos tile : eveningVenue.getNavMeshBlocks()) {
            double d = tile.distSqr(anchor);
            if (d < bestDist) { bestDist = d; best = tile; }
        }
        return best;
    }

    private static BlockPos villageCenter(Context ctx) {
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof TownHallBuilding && building.isValid())
                return building.getDoorPos();
        }
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof TavernBuilding && building.isValid())
                return building.getDoorPos();
        }
        return ctx.npc().blockPosition();
    }

    private Status finishOrFail() {
        return workedThisSession ? Status.DONE : Status.FAILED;
    }

    /**
     * The instrument set in the bard's settings. On Bard's choice, the one it picked for
     * this stop or visit (picked again in {@link #chooseNext}); the evening set keeps one all night.
     */
    private BardInstrument instrument(Context ctx, ProfessionSettings settings) {
        if (!bardsChoice(settings)) {
            chosen = null;
            return BardInstrument.fromSetting(settings.getInt(BardSettingsSchema.INSTRUMENT));
        }
        if (chosen == null) chosen = pickInstrument(ctx);
        return chosen;
    }

    private static boolean bardsChoice(ProfessionSettings settings) {
        return settings.getInt(BardSettingsSchema.INSTRUMENT) == BardInstrument.BARDS_CHOICE;
    }

    /** Bard's choice: the violin two times in six, the bass, guitar, bongos (B3, 10-24) and flute one in six each. */
    private static BardInstrument pickInstrument(Context ctx) {
        return switch (ctx.npc().getRandom().nextInt(6)) {
            case 0, 1 -> BardInstrument.VIOLIN;
            case 2 -> BardInstrument.BASS;
            case 3 -> BardInstrument.GUITAR;
            case 4 -> BardInstrument.BONGOS;
            default -> BardInstrument.FLUTE;
        };
    }

    /**
     * A stop: one track that is switched on for its instrument, played start to finish
     * (a recording, or one play-through of the Village Tune), its first tick now.
     */
    private void beginPerforming(Context ctx, ProfessionSettings settings,
                                 BardInstrument instrument) {
        stopPiece = pickPiece(ctx, settings, instrument);
        stopTimer = stopPiece.holdTicks;
        restTimer = 0;
        awardTimer = AWARD_TICKS;
        diary(ctx, "BARD stop at " + ctx.npc().blockPosition().toShortString() + ": " + stopPiece.track
                + " on the " + stopPiece.instrument.name().toLowerCase(java.util.Locale.ROOT));
        tickStop(ctx, settings);
    }

    /**
     * One tick of the stop's track, from the same clock as a serenade piece: the recording
     * (again on the tick it ends, for one played twice) or the tune's next beat. Everyone in
     * earshot gets happier every AWARD_TICKS. At the end the instrument is put down for a
     * moment, so the next track's clip starts afresh and its notes land on the beats.
     */
    private void tickStop(Context ctx, ProfessionSettings settings) {
        showPose(ctx, stopPose(settings));
        int played = stopPiece.holdTicks - stopTimer;
        if (stopPiece.sound == null) {
            playTuneBeat(ctx, settings, stopPiece, played);
        } else if (played % stopPiece.playTicks == 0 && played < stopPiece.playTicks * stopPiece.plays) {
            playPiece(ctx, settings, stopPiece);
        }
        if (--awardTimer <= 0) {
            awardTimer = AWARD_TICKS;
            finishBurst(ctx, settings);
        }
        if (--stopTimer > 0) return;
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        restTimer = REST_TICKS;
    }

    /**
     * The stop's pose: dancing or standing; a bass recording is always played standing (its plucks follow that clip).
     * The bongos' dancing and standing are the band's joyful and calm seated clips.
     */
    private VillagerAnimationState stopPose(ProfessionSettings settings) {
        if (stopPiece.bass && stopPiece.sound != null) return VillagerAnimationState.BASS_PLAY;
        return settings.getBool(BardSettingsSchema.DANCE_AT_STOPS)
                ? stopPiece.instrument.dancingAnimation() : stopPiece.instrument.standingAnimation();
    }

    /**
     * Shows a day pose. A bongo clip is started with its time stamp ({@code NpcEntity.startBongoClip}) on the
     * first tick it is wanted, then held; by day nothing lines it up, since the recordings do not follow the
     * clips' beat (B3, 10-24). A drummer in the band song never comes here: {@link #playBongos} starts its
     * clip in step with the song.
     */
    private static void showPose(Context ctx, VillagerAnimationState pose) {
        NpcEntity npc = ctx.npc();
        if (!pose.isBongos()) {
            npc.setAnimation(pose);
        } else if (npc.getAnimation() != pose) {
            npc.startBongoClip(pose);
        }
    }

    /** A stop cut short (a fight, bedtime, work switched off): its recording stops with the pose. */
    private void endStop(Context ctx) {
        if (stopTimer > 0 && stopPiece.sound != null) BardTracks.stop(ctx.npc());
        stopTimer = 0;
        restTimer = 0;
    }

    /** Another AWARD_TICKS of playing: everyone in earshot gets happier. */
    private void finishBurst(Context ctx, ProfessionSettings settings) {
        burstsCompleted++;

        workedThisSession = true;
        // Music volume is only how loud players hear it, day and tavern alike (F2: the tavern used to skip volume 0).
        int boosted = boostNearbyHappiness(ctx, null);
        ctx.brain().onBardBurstCompleted(ctx);
        diary(ctx, "BARD burst " + burstsCompleted + " complete, "
                + boosted + " listener(s) happier");
    }

    private void setPerformanceAnimation(Context ctx, ProfessionSettings settings,
                                         BardInstrument instrument) {
        VillagerAnimationState pose = settings.getBool(BardSettingsSchema.DANCE_AT_STOPS)
                ? instrument.dancingAnimation() : instrument.standingAnimation();
        playPose(ctx, pose);
    }

    /**
     * Sets a playing pose. The bass and violin clips pluck or turn the bow every
     * NOTE_INTERVAL ticks, starting once a new clip has blended in and reached the client:
     * entering one puts the next note on that first pluck or stroke.
     */
    private void playPose(Context ctx, VillagerAnimationState pose) {
        if (pose.isOnTheBeat() && ctx.npc().getAnimation() != pose) noteTimer = FIRST_NOTE_TICKS;
        ctx.npc().setAnimation(pose);
    }

    /** Ticks from setting a bass or violin pose to its first pluck or stroke: the work blend, a tick to reach the client. */
    private static final int FIRST_NOTE_TICKS = VillagerAnimationState.WORK_BLEND_TICKS + 2;

    private void tickMelody(Context ctx, ProfessionSettings settings,
                            BardInstrument instrument) {
        if (--noteTimer > 0) return;
        noteTimer = NOTE_INTERVAL;
        BardMusic.playBeat(ctx.level(), ctx.npc(), noteIndex++, ctx.brain().bardSkillLevel(),
                instrument, settings.getInt(BardSettingsSchema.MUSIC_VOLUME) / 100f);
    }

    /** Everyone in earshot gets a normal burst; {@code favored}, if set, gets the bigger serenade boost. */
    private int boostNearbyHappiness(Context ctx, @Nullable VillagerModel favored) {
        BlockPos from = ctx.npc().blockPosition();
        int skill = ctx.brain().bardSkillLevel();
        double listenRadius = BardBalance.audienceRadius(skill,
                ProfessionSettings.of(ctx.npc(), BardSettingsSchema.SCHEMA)
                        .getInt(BardSettingsSchema.LISTEN_RADIUS));
        double listenRadiusSq = listenRadius * listenRadius;
        float happiness = BardBalance.happinessPerBurst(skill);
        float sessionCap = BardBalance.sessionCap(skill);
        int boosted = 0;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            if (model == ctx.model()) continue;
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive()) continue;
            if (model != favored && npc.blockPosition().distSqr(from) > listenRadiusSq) continue;
            boosted++;

            float already = grantedThisSession.getOrDefault(model.npcId, 0f);
            if (already >= sessionCap) continue;
            float wanted = model == favored ? BardBalance.serenadeBoost(skill) : happiness;
            float grant = Math.min(wanted, sessionCap - already);
            model.brain().needs().onMayorConversationComplete(grant);
            grantedThisSession.put(model.npcId, already + grant);
            if (eveningVenue != null) model.brain().onHeardBard();
        }
        return boosted;
    }

    /** Picks the unhappiest villager within reach and sets off towards them; false if there is nobody. */
    private boolean startSerenade(Context ctx, ProfessionSettings settings, BardInstrument instrument) {
        long now = ctx.level().getGameTime();
        LAST_CHEERED.values().removeIf(at -> now - at >= CHEERED_COOLDOWN);
        VISITS.values().removeIf(v -> now - v.seenAt() > VISIT_STALE_TICKS);
        UUID me = ctx.npc().getUUID();
        float sessionCap = BardBalance.sessionCap(ctx.brain().bardSkillLevel());
        BlockPos here = ctx.npc().blockPosition();
        VillagerModel fresh = null;
        VillagerModel cooling = null;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            if (model == ctx.model() || !canListen(model)) continue;
            NpcEntity npc = model.puppet();
            if (npc.blockPosition().distSqr(here) > SERENADE_RADIUS * SERENADE_RADIUS) continue;
            if (grantedThisSession.getOrDefault(model.npcId, 0f) >= sessionCap) continue;
            if (leftToAnotherBard(me, model, npc.blockPosition())) continue;
            if (LAST_CHEERED.containsKey(model.npcId)) {
                if (cooling == null || sadder(model, cooling)) cooling = model;
            } else if (fresh == null || sadder(model, fresh)) {
                fresh = model;
            }
        }
        VillagerModel pick = fresh;
        if (cooling != null && (fresh == null
                || wellbeing(cooling) + FAR_SADDER < wellbeing(fresh))) pick = cooling;
        if (pick == null) return false;

        serenadeTarget = pick;
        VISITS.put(pick.npcId, new Visit(me, here, now));
        chaseTicks = 0;
        repathTimer = 0;
        serenadeTimer = 0;
        serenadePiece = pickPiece(ctx, settings, instrument);
        diary(ctx, "BARD heading over to play " + serenadePiece.track + " on the "
                + serenadePiece.instrument.name().toLowerCase(java.util.Locale.ROOT) + " for "
                + pick.puppet().getName().getString()
                + String.format(" (wellbeing %.0f)", wellbeing(pick)));
        return true;
    }

    /**
     * A piece on the bard's own instrument (see {@link #instrument}) that is switched on in
     * its track list, at random by lots. With none on (the list keeps one on, but an old
     * save or a command can switch the last off), the Village Tune; the bongos have none: their first piece.
     */
    private static Piece pickPiece(Context ctx, ProfessionSettings settings, BardInstrument instrument) {
        java.util.List<Piece> lots = new java.util.ArrayList<>();
        Piece fallback = null;
        for (Piece piece : Piece.values()) {
            if (piece.instrument != instrument) continue;
            if (piece.sound == null || fallback == null) fallback = piece;
            if (!piece.on(settings)) continue;
            for (int i = 0; i < piece.lots; i++) lots.add(piece);
        }
        if (lots.isEmpty()) return fallback != null ? fallback : Piece.VIOLIN_TUNE;
        return lots.get(ctx.npc().getRandom().nextInt(lots.size()));
    }

    /** Someone another bard is already visiting, or standing near a bard who is on a visit. */
    private static boolean leftToAnotherBard(UUID me, VillagerModel model, BlockPos at) {
        Visit theirs = VISITS.get(model.npcId);
        if (theirs != null && !theirs.bard().equals(me)) return true;
        for (Visit visit : VISITS.values()) {
            if (!visit.bard().equals(me) && visit.bardPos().distSqr(at) < OTHER_BARD_GAP * OTHER_BARD_GAP) return true;
        }
        return false;
    }

    /** Keeps this bard's visit marked as taken while it walks over and plays. */
    private void refreshVisit(Context ctx) {
        if (serenadeTarget == null) return;
        VISITS.put(serenadeTarget.npcId, new Visit(ctx.npc().getUUID(), ctx.npc().blockPosition(),
                ctx.level().getGameTime()));
    }

    /** Whether a serenade piece or a stop's track is playing now: it is played to its end, even with the bard's menu open. */
    public boolean isPlayingPiece() {
        return eveningVenue == null && (serenadeTimer > 0 || stopTimer > 0);
    }

    /**
     * The serenade's pose. The violin plays on the move over slowed walking legs and
     * standing or dancing once stopped; the bass stands on the floor and is played
     * where it was set down until the piece ends.
     */
    private VillagerAnimationState serenadePose(boolean following, ProfessionSettings settings) {
        if (serenadePiece.bass) return VillagerAnimationState.BASS_PLAY;
        BardInstrument instrument = serenadePiece.instrument;
        if (following) return instrument.walkingAnimation();
        return settings.getBool(BardSettingsSchema.DANCE_AT_STOPS)
                ? instrument.dancingAnimation() : instrument.standingAnimation();
    }

    /** One play-through of {@code piece}'s recording, from the bard at its music volume. */
    private void playPiece(Context ctx, ProfessionSettings settings, Piece piece) {
        float volume = settings.getInt(BardSettingsSchema.MUSIC_VOLUME) / 100f;
        if (volume <= 0f || piece.sound == null) return;
        // Tied to the bard, so the music goes with him if he follows the listener, and
        // stoppable, so it ends with the playing pose if the visit or stop is cut short.
        BardTracks.play(ctx.npc(), piece.sound.get(), volume * 2f, piece.playTicks);
    }

    /**
     * The Village Tune, once through: a note every NOTE_INTERVAL ticks from the clip's
     * first beat, FIRST_NOTE_TICKS after the playing pose was set ({@code played} = 0).
     */
    private void playTuneBeat(Context ctx, ProfessionSettings settings, Piece piece, int played) {
        int sinceFirst = played - FIRST_NOTE_TICKS;
        if (sinceFirst < 0 || sinceFirst % NOTE_INTERVAL != 0) return;
        int beat = sinceFirst / NOTE_INTERVAL;
        if (beat >= BardMusic.TUNE_BEATS) return;
        BardMusic.playBeat(ctx.level(), ctx.npc(), beat, ctx.brain().bardSkillLevel(),
                piece.instrument, settings.getInt(BardSettingsSchema.MUSIC_VOLUME) / 100f);
    }

    /** Walks to the listener, then plays this visit's piece on its own instrument in front of them. */
    private Status tickSerenade(Context ctx, ProfessionSettings settings) {
        VillagerModel target = serenadeTarget;
        NpcEntity listener = target.puppet();
        refreshVisit(ctx);
        if (serenadeTimer > 0) {
            boolean following = false;
            if (listener != null && listener.isAlive()) {
                // The bass stands on the floor and the bongos sit on the stool: played to the end where they are.
                if (!serenadePiece.staysPut()) following = followWhilePlaying(ctx, listener);
                ctx.npc().getLookControl().setLookAt(listener, 30f, 30f);
            }
            // The dance keys the legs, so on the move the arms play over ordinary, slowed walking legs.
            showPose(ctx, serenadePose(walkingPoseFor(ctx, following), settings));
            // The note block tune sends its own note particles, one per note.
            if (serenadeTimer % 8 == 0 && serenadePiece.sound != null) {
                ctx.level().sendParticles(ParticleTypes.NOTE, ctx.npc().getX(), ctx.npc().getY() + 1.65,
                        ctx.npc().getZ(), 0, ctx.npc().getRandom().nextInt(25) / 24.0, 0.0, 0.0, 1.0);
            }
            if (--serenadeTimer > 0) {
                int played = serenadePiece.holdTicks - serenadeTimer;
                if (serenadePiece.sound == null) {
                    playTuneBeat(ctx, settings, serenadePiece, played);
                } else if (played % serenadePiece.playTicks == 0
                        && played < serenadePiece.playTicks * serenadePiece.plays) {
                    // A piece played more than once starts again on the tick it ends.
                    playPiece(ctx, settings, serenadePiece);
                }
                return Status.RUNNING;
            }
            finishSerenade(ctx, target);
            return Status.RUNNING;
        }

        // Wandering or Cheer the Saddest switched off on the way over: the walk is dropped (a piece already
        // playing, above, is finished). No cooldown for the listener, unlike a give-up. Next tick chooseNext
        // plays where it stands (Wandering off) or picks a normal stop (Cheer off). F3, 10-24.
        if (!settings.getBool(BardSettingsSchema.ROAM) || !settings.getBool(BardSettingsSchema.CHEER_SADDEST)) {
            diary(ctx, "BARD dropped the walk over to " + target.npcId.toString().substring(0, 8) + ": "
                    + (settings.getBool(BardSettingsSchema.ROAM) ? "Cheer the Saddest" : "Wandering") + " switched off");
            clearSerenade(ctx);
            return Status.RUNNING;
        }

        if (!canListen(target)) {
            giveUpSerenade(ctx, "they are busy or gone");
            return Status.RUNNING;
        }
        if (listener.distanceToSqr(ctx.npc()) <= PLAY_DISTANCE_SQ
                && Math.abs(listener.getY() - ctx.npc().getY()) <= 2) {
            if (serenadeWalk != null) { serenadeWalk.stop(ctx); serenadeWalk = null; }
            ctx.model().stopMoving();
            // The instrument or its tracks were changed on the way over: a piece on the new ones.
            BardInstrument instrument = instrument(ctx, settings);
            if (serenadePiece.instrument != instrument || !serenadePiece.on(settings)) {
                serenadePiece = pickPiece(ctx, settings, instrument);
            }
            int pieceTicks = serenadePiece.holdTicks;
            serenadeTimer = pieceTicks;
            walkingPoseShown = false;
            poseSwitchTicks = 0;
            lastPoseX = ctx.npc().getX();
            lastPoseZ = ctx.npc().getZ();
            playPiece(ctx, settings, serenadePiece);
            // Most villagers carry on with their work; now and then one stops to listen.
            if (ctx.npc().getRandom().nextFloat() < STOP_TO_LISTEN_CHANCE
                    && target.brain().isFreeToChat()
                    && target.brain().requestCareHold(ctx.npc(), pieceTicks + 10)) {
                listenerHeld = true;
                listener.setAnimation(VillagerAnimationState.IDLE);
            }
            showPose(ctx, serenadePose(false, settings));
            return Status.RUNNING;
        }
        if (++chaseTicks > CHASE_LIMIT_TICKS) {
            giveUpSerenade(ctx, "could not catch up");
            return Status.RUNNING;
        }

        // A listener who walks off gets a fresh route; small shuffles do not.
        BlockPos goal = NavGraph.feetCell(listener);
        if (serenadeWalk == null || (++repathTimer >= REPATH_INTERVAL
                && goal.distSqr(serenadeGoal) > REPATH_DRIFT_SQ)) {
            if (serenadeWalk != null) serenadeWalk.stop(ctx);
            repathTimer = 0;
            serenadeGoal = goal;
            serenadeWalk = new MoveActivity(goal, VillagerModel.WALK_SPEED);
        }
        ctx.npc().setAnimation(VillagerAnimationState.WALK);
        Status status = serenadeWalk.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        serenadeWalk.stop(ctx);
        serenadeWalk = null;
        if (status == Status.FAILED) giveUpSerenade(ctx, "could not reach them");
        return Status.RUNNING;
    }

    private void finishSerenade(Context ctx, VillagerModel target) {
        float before = wellbeing(target);
        int boosted = boostNearbyHappiness(ctx, target);
        burstsCompleted++;
        workedThisSession = true;
        ctx.brain().onBardBurstCompleted(ctx);
        LAST_CHEERED.put(target.npcId, ctx.level().getGameTime());
        NpcEntity listener = target.puppet();
        String name = listener != null ? listener.getName().getString() : target.npcId.toString().substring(0, 8);
        diary(ctx, String.format("BARD played for %s (wellbeing %.0f -> %.0f), %d listener(s) happier",
                name, before, wellbeing(target), boosted));
        clearSerenade(ctx);
    }

    /** Sets the visit aside; the villager waits out the cooldown so the bard does not try them again at once. */
    private void giveUpSerenade(Context ctx, String why) {
        VillagerModel target = serenadeTarget;
        if (target != null) {
            LAST_CHEERED.put(target.npcId, ctx.level().getGameTime());
            diary(ctx, "BARD gave up on playing for " + target.npcId.toString().substring(0, 8) + ": " + why);
        }
        clearSerenade(ctx);
    }

    /**
     * During a violin piece: a listener who walked off is followed at a quarter of the
     * walking pace, and the bard stops again within playing distance. True while walking.
     */
    private boolean followWhilePlaying(Context ctx, NpcEntity listener) {
        if (serenadeWalk == null) {
            if (listener.distanceToSqr(ctx.npc()) <= FOLLOW_START_SQ) return false;
            serenadeGoal = NavGraph.feetCell(listener);
            serenadeWalk = new MoveActivity(serenadeGoal, FOLLOW_SPEED);
            repathTimer = 0;
        } else if (++repathTimer >= REPATH_INTERVAL
                && NavGraph.feetCell(listener).distSqr(serenadeGoal) > REPATH_DRIFT_SQ) {
            // Not stopped first: the old route carries him on until the new one is
            // planned, so he does not halt and ease back up to pace at every re-plan.
            serenadeGoal = NavGraph.feetCell(listener);
            serenadeWalk = new MoveActivity(serenadeGoal, FOLLOW_SPEED);
            repathTimer = 0;
        }
        Status status = serenadeWalk.tickOuter(ctx);
        if (status == Status.RUNNING && listener.distanceToSqr(ctx.npc()) > PLAY_DISTANCE_SQ) return true;
        // Close again, arrived or stuck: either way, keep playing from here.
        serenadeWalk.stop(ctx);
        serenadeWalk = null;
        ctx.model().stopMoving();
        return false;
    }

    /**
     * Whether the serenade shows the walking pose this tick. It follows the bard's
     * real movement, not the follow walk: a walk that is still being planned, or a
     * re-plan, stands him still for a moment, and flipping the pose with it made
     * the legs pop between standing and walking. He has to be moving for
     * {@link #POSE_START_TICKS} ticks before the walk shows, and still for
     * {@link #POSE_STOP_TICKS} before the standing or dancing pose comes back.
     */
    private boolean walkingPoseFor(Context ctx, boolean following) {
        NpcEntity npc = ctx.npc();
        double dx = npc.getX() - lastPoseX, dz = npc.getZ() - lastPoseZ;
        lastPoseX = npc.getX();
        lastPoseZ = npc.getZ();
        boolean moving = following && dx * dx + dz * dz > MOVING_SQ;
        if (moving == walkingPoseShown) {
            poseSwitchTicks = 0;
        } else if (++poseSwitchTicks >= (moving ? POSE_START_TICKS : POSE_STOP_TICKS)) {
            walkingPoseShown = moving;
            poseSwitchTicks = 0;
        }
        return walkingPoseShown;
    }

    /** Moving means at least 0.2 blocks a second; a quarter of the walk is about 0.7. */
    private static final double MOVING_SQ = 0.01 * 0.01;
    private static final int POSE_START_TICKS = 2;
    private static final int POSE_STOP_TICKS = 5;
    private boolean walkingPoseShown;
    private int poseSwitchTicks;
    private double lastPoseX, lastPoseZ;

    private void clearSerenade(Context ctx) {
        if (serenadeWalk != null) { serenadeWalk.stop(ctx); serenadeWalk = null; }
        walkingPoseShown = false;
        poseSwitchTicks = 0;
        if (listenerHeld && serenadeTarget != null) serenadeTarget.brain().releaseCareHold(ctx.npc());
        listenerHeld = false;
        // Cut off mid-piece (a fight, bedtime, work switched off): the music stops with the pose.
        if (serenadeTimer > 0) BardTracks.stop(ctx.npc());
        if (serenadeTarget != null) {
            Visit visit = VISITS.get(serenadeTarget.npcId);
            if (visit != null && visit.bard().equals(ctx.npc().getUUID())) VISITS.remove(serenadeTarget.npcId);
        }
        serenadeTarget = null;
        serenadeGoal = null;
        serenadeTimer = 0;
        // The bass and the standing violin are held until something else is played; put them down.
        if (ctx.npc().getAnimation().isOnTheBeat()) ctx.npc().setAnimation(VillagerAnimationState.IDLE);
    }

    /** Awake, nearby in the world, and not hiding or fighting. Visitors are not in the village list. */
    private static boolean canListen(VillagerModel model) {
        NpcEntity npc = model.puppet();
        return npc != null && npc.isAlive() && !npc.isSleeping() && !npc.isCombatReady()
                && !model.brain().isTakingShelter()
                && !(model.brain().currentActivity() instanceof FleeActivity);
    }

    private static float wellbeing(VillagerModel model) {
        return model.brain().needs().wellbeing;
    }

    private static boolean sadder(VillagerModel a, VillagerModel b) {
        return wellbeing(a) < wellbeing(b);
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    /** A bard needs a village route, but no tavern or separate instrument block. */
    public static boolean hasWork(Context ctx) {
        if (!ProfessionSettings.of(ctx.npc(), BardSettingsSchema.SCHEMA)
                .getBool(BardSettingsSchema.WORK_ENABLED)) return false;
        if (!ProfessionSettings.of(ctx.npc(), BardSettingsSchema.SCHEMA)
                .getBool(BardSettingsSchema.ROAM)) return true;
        NavGraph graph = ctx.mgr().getGraph();
        return graph != null && !graph.allNodes().isEmpty();
    }
}
