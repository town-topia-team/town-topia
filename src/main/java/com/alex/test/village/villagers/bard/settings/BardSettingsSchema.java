package com.alex.test.village.villagers.bard.settings;

import com.alex.test.village.villagers.bard.BardInstrument;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.List;

/**
 * Settings for the bard's roaming performances. Instrument selection changes
 * the visible model, playing pose and sound, including during an active set.
 */
public final class BardSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String LISTEN_RADIUS = "listen_radius";
    // "burst_ticks" (Song length) was removed 10-24: a stop plays one whole track. Old saves keep the key, unread.
    public static final String INSTRUMENT = "instrument";
    public static final String MUSIC_VOLUME = "music_volume";
    public static final String ROAM = "roam";
    public static final String PLAY_WHILE_WALKING = "play_while_walking";
    public static final String DANCE_AT_STOPS = "dance_at_stops";
    public static final String CHEER_SADDEST = "cheer_saddest";
    /** One track list per instrument; entry ids are BardActivity's piece track ids, saved by name. */
    public static final String VIOLIN_TRACKS = "violin_tracks";
    public static final String FLUTE_TRACKS = "flute_tracks";
    public static final String BASS_TRACKS = "bass_tracks";
    public static final String GUITAR_TRACKS = "guitar_tracks";
    /** No Village Tune here: the slap clips follow drums, not note blocks (10-24). */
    public static final String BONGO_TRACKS = "bongo_tracks";
    /** Every instrument's note block tune, except the bongos'. */
    public static final String VILLAGE_TUNE = "village_tune";

    /** The track list setting for {@code instrument}. */
    public static String tracksOf(BardInstrument instrument) {
        return switch (instrument) {
            case VIOLIN -> VIOLIN_TRACKS;
            case FLUTE -> FLUTE_TRACKS;
            case BASS -> BASS_TRACKS;
            case GUITAR -> GUITAR_TRACKS;
            case BONGOS -> BONGO_TRACKS;
        };
    }

    /**
     * One play of a track in whole seconds, shown beside it on the Music tab; 0 for an unknown id.
     * Keep in step with the recordings and their exact tick lengths.
     */
    public static int trackSeconds(String tracksSetting, String trackId) {
        // The bongos' Show Off 1 to 4 share the violin's ids but are played once, 10.29 s.
        if (BONGO_TRACKS.equals(tracksSetting)) return trackId.startsWith("show_off_") ? 10 : 0;
        boolean bass = BASS_TRACKS.equals(tracksSetting);
        return switch (trackId) {
            case VILLAGE_TUNE -> 13;
            case "sunny_lane" -> bass ? 18 : 19;
            case "show_off" -> bass ? 10 : 7;
            case "show_off_1", "show_off_2", "show_off_3", "show_off_4" -> 14;
            case "get_up_and_go" -> 18;
            default -> 0;
        };
    }

    private static SettingsSchema.AllowListEntry track(String id, String label, String description) {
        return new SettingsSchema.AllowListEntry(id, label, true, description);
    }

    private static SettingsSchema.Row tracks(String settingId, String label, SettingsSchema.AllowListEntry... entries) {
        return SettingsSchema.Row.allowList(settingId, label, List.of(entries))
                .keepingOne()
                .describedAs("At each stop and visit it plays\none ON track, start to finish.\nKeep at least one on.");
    }

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.BARD,
            "BardSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: plays music that makes\nvillagers happier.\n"
                                            + "OFF: stops playing."),
                            SettingsSchema.Row.behaviorToggle(
                                    DANCE_AT_STOPS, "Dancing", true)
                                    .describedAs("ON: dances while it plays, at stops,\n"
                                            + "on visits and in the tavern.\n"
                                            + "OFF: stands still while it plays."),
                            SettingsSchema.Row.behaviorToggle(
                                    ROAM, "Wandering", true)
                                    .describedAs("ON: wanders from stop to stop.\n"
                                            + "OFF: plays where it stands."),
                            SettingsSchema.Row.behaviorToggle(
                                    CHEER_SADDEST, "Cheer the Saddest", true)
                                    .describedAs("ON: walks to the unhappiest villager\nnearby and plays for them.\n"
                                            + "Only works with Wandering ON.\n"
                                            + "OFF: wanders and plays anywhere."),
                            SettingsSchema.Row.behaviorToggle(
                                    PLAY_WHILE_WALKING, "Playing on the Move", true)
                                    .describedAs("ON: plays the Village Tune as it walks\n"
                                            + "between stops (the bass is only carried).\n"
                                            + "Needs Wandering and that Village Tune ON.\n"
                                            + "OFF: only plays when stopped."))),
                    new SettingsSchema.Section("Music", List.of(
                            // Saved as the index: new choices go on the end (Bongos after Bard's choice,
                            // BardInstrument.BONGOS_CHOICE; the Music tab shows them in its own order).
                            SettingsSchema.Row.choice(
                                    INSTRUMENT, "Instrument", BardInstrument.BARDS_CHOICE,
                                    List.of("Violin", "Flute", "Bass", "Guitar", "Bard's choice", "Bongos"))
                                    .describedAs("Which instrument the bard plays,\nin the village and the tavern band.\nBard's choice picks a new one\nfor every stop and takes a free part."),
                            SettingsSchema.Row.numericStepper(
                                    MUSIC_VOLUME, "Music volume", 70, 0, 100, 10)
                                    .describedAs("How loud you hear the bard.\n100 is full volume, 0 is silent.\n"
                                            + "Villagers enjoy it at any volume."),
                            SettingsSchema.Row.numericStepper(
                                    // Thirty-two blocks covers a compact village.
                                    LISTEN_RADIUS, "Listening range", 32, 4, 64, 4)
                                    // A ceiling, not the reach: past 16 blocks it grows with skill
                                    // (BardBalance.audienceRadius). F5, 10-24.
                                    .describedAs("Villagers up to this many blocks\naway hear the music and get happier.\n"
                                            + "Past 16 blocks, a bard grows into\nthe range as it levels up."),
                            SettingsSchema.Row.header("Violin Tracks"),
                            tracks(VIOLIN_TRACKS, "Violin tracks",
                                    track(VILLAGE_TUNE, "Village Tune",
                                            "The note block tune, 13 s.\nIt gets richer as the bard\nlevels up."),
                                    track("sunny_lane", "Sunny Lane", "Long, smooth notes, 19 s."),
                                    track("show_off", "Show Off", "A quick showpiece, 7 s."),
                                    track("show_off_1", "Show Off 1", "A quick showpiece played\ntwice, 14 s."),
                                    track("show_off_2", "Show Off 2", "A quick showpiece played\ntwice, 14 s."),
                                    track("show_off_3", "Show Off 3", "A quick showpiece played\ntwice, 14 s."),
                                    track("show_off_4", "Show Off 4", "A quick showpiece played\ntwice, 14 s.")),
                            SettingsSchema.Row.header("Flute Tracks"),
                            tracks(FLUTE_TRACKS, "Flute tracks",
                                    track(VILLAGE_TUNE, "Village Tune",
                                            "The note block tune, 13 s.\nThe flute has no recordings yet.")),
                            SettingsSchema.Row.header("Bass Tracks"),
                            tracks(BASS_TRACKS, "Bass tracks",
                                    track(VILLAGE_TUNE, "Village Tune",
                                            "The note block tune as a\nwalking bass line, 13 s."),
                                    track("show_off", "Show Off", "A quick bass showpiece, 10 s."),
                                    track("sunny_lane", "Sunny Lane", "Sunny Lane on the upright\nbass, 18 s.")),
                            SettingsSchema.Row.header("Guitar Tracks"),
                            tracks(GUITAR_TRACKS, "Guitar tracks",
                                    track(VILLAGE_TUNE, "Village Tune", "The note block tune, 13 s."),
                                    track("get_up_and_go", "Get Up and Go", "A lively strummed tune, 18 s.")),
                            // B1/B3, 10-24: the author's recordings, played once on the stool.
                            SettingsSchema.Row.header("Bongos Tracks"),
                            tracks(BONGO_TRACKS, "Bongos tracks",
                                    track("show_off_1", "Show Off 1", "Busy, with quick double hits,\n10 s."),
                                    track("show_off_2", "Show Off 2", "A mixed beat with one short\npause, 10 s."),
                                    track("show_off_3", "Show Off 3", "The calmest: short phrases\nwith pauses, 10 s."),
                                    track("show_off_4", "Show Off 4", "A steady, fast roll, 10 s."))))));

    private BardSettingsSchema() {}
}
