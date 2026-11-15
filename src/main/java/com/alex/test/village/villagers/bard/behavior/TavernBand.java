package com.alex.test.village.villagers.bard.behavior;

import com.alex.test.ModSounds;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.bard.BardInstrument;
import com.alex.test.village.villagers.bard.settings.BardSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.network.BandSongPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every bard playing one tavern tonight, as one band: the piano recording once a night
 * (by whichever bard gets there first), then "Long Road Ahead" over and over, each
 * part played by a bard holding that instrument.
 *
 * <p>The song is not four sounds: players get one sound ({@code BandSongSound}) with the
 * four recordings mixed sample by sample, and this class only tells them when a
 * play-through starts and who holds each part (a part nobody holds is silent in the
 * mix). So the parts are always exactly together, whatever the lag or reloads.
 *
 * <p>At most {@link #MAX_PLAYERS} bards play. A bard set to an instrument always plays it,
 * even one another bard has too (both hold it; the recording comes from the first). A bard
 * on "Bard's choice" takes the first part nobody holds: violin, bass, guitar, then the
 * bongos (a fourth bard sits at them); while nobody holds the bongos their recording comes
 * from the first bard with a part. A flute bard (the flute has no recorded part) plays
 * along without a recording. With only flute bards there is no song and the old note-block
 * tune is played; with only bongo bards, their own pieces (10-24). Parts are handed out again at the start of every play-through, so a
 * changed setting never changes a part mid-song. A bard who arrives mid-song joins in at
 * once (its part turned up in the mix); a player who comes within sight mid-song hears the
 * song from where it is.
 */
final class TavernBand {
    /** One play-through: 2,116,800 samples at 44,100 Hz, exactly 48.0 s. */
    static final int SONG_TICKS = 960;
    /** The most bards in the band; any more dance to the music. */
    static final int MAX_PLAYERS = 4;
    /** A member not heard from for this long has left (its activity ended without a stop). */
    private static final int GONE_TICKS = 40;
    /** The piano is played once a night; a claim older than this was a past night. */
    private static final long NIGHT_TICKS = 12000;
    private static final int AUDIENCE_REFRESH_TICKS = 40;
    /** Blocks between neighbours in the band's line. */
    private static final int SPACING = 2;

    private static final Map<UUID, TavernBand> BANDS = new HashMap<>();

    private static final class Member {
        final UUID id;
        NpcEntity npc;
        /** The bard's own instrument setting, or null on Bard's choice (any free part). */
        @Nullable BardInstrument wanted;
        /** What it plays in the band tonight (null until it has its place, or with the band full). */
        @Nullable BardInstrument part;
        @Nullable BlockPos spot;
        boolean ready;
        long seenAt;
        /** Its own evening still has new play-throughs in it (false once only the song in progress is left). */
        boolean playsOn = true;

        Member(NpcEntity npc) { this.id = npc.getUUID(); this.npc = npc; }
    }

    private final LinkedHashMap<UUID, Member> members = new LinkedHashMap<>();
    @Nullable private UUID pianist;
    private long pianoClaimedAt = Long.MIN_VALUE;
    private boolean pianoDone;
    private long songStartedAt = Long.MIN_VALUE;
    /** Who is in the current play-through, and on what. */
    private final Map<UUID, BardInstrument> roster = new HashMap<>();
    /** This band's tavern (its item frame id): the song's id on the players' side. */
    private final UUID id;
    /** The play-through players were last told about, and who held each part then. */
    private long sentSong = Long.MIN_VALUE;
    @Nullable private int[] sentHolders;
    @Nullable private float[] sentGains;
    /** A player arriving this close to the end of a play-through waits for the next one. */
    private static final int LATE_START_LIMIT = 20;
    @Nullable private Vec3 audience;
    private long audienceAt = Long.MIN_VALUE;

    private TavernBand(UUID id) { this.id = id; }

    static TavernBand of(Building tavern, long now) {
        // Old, empty bands from past nights are dropped.
        BANDS.values().removeIf(b -> b.members.isEmpty() && !b.pianoTaken(now));
        return BANDS.computeIfAbsent(tavern.getItemFrameId(), TavernBand::new);
    }

    /** The recording for {@code instrument}'s part, or null if it has none (the flute). */
    @Nullable
    static SoundEvent partFor(@Nullable BardInstrument instrument) {
        if (instrument == null) return null;
        return switch (instrument) {
            case VIOLIN -> ModSounds.BARD_BAND_VIOLIN.get();
            case GUITAR -> ModSounds.BARD_BAND_GUITAR.get();
            case BASS -> ModSounds.BARD_BAND_BASS.get();
            case BONGOS -> ModSounds.BARD_BAND_BONGOS.get();
            case FLUTE -> null;
        };
    }

    /**
     * Called by each member every tick: keeps it in the band (with its place in the line,
     * once it has one) and drops members that left. {@code wanted} is its instrument setting,
     * null on Bard's choice. {@code playsOn} is false once its own evening is over: it
     * finishes the song in progress and is not brought into the next.
     */
    void touch(NpcEntity npc, @Nullable BardInstrument wanted, @Nullable BlockPos spot, long now, boolean playsOn) {
        Member me = members.computeIfAbsent(npc.getUUID(), id -> new Member(npc));
        me.npc = npc;
        me.wanted = wanted;
        me.seenAt = now;
        me.playsOn = playsOn;
        if (spot != null) me.spot = spot;
        for (Iterator<Member> it = members.values().iterator(); it.hasNext(); ) {
            Member m = it.next();
            if (m != me && (now - m.seenAt > GONE_TICKS || !m.npc.isAlive() || m.npc.isRemoved())) {
                it.remove();
                dropped(m);
            }
        }
    }

    /** The bard stopped playing here: its part goes quiet, the others play on. */
    void leave(NpcEntity npc) {
        Member m = members.remove(npc.getUUID());
        if (m != null) dropped(m);
    }

    private void dropped(Member m) {
        boolean wasPlaying = roster.remove(m.id) != null;
        if (m.id.equals(pianist)) pianoDone = true;
        if (!wasPlaying || sentSong != songStartedAt) return;
        long now = m.npc.level().getGameTime();
        if (roster.isEmpty()) {
            // The last bard has gone: the song stops for everyone.
            sendStop(m.npc);
            sentHolders = null;
            sentGains = null;
            return;
        }
        // Its part goes quiet at once; another bard picks up the bongos (and a Bard's
        // choice bard on the flute any free part) inside the same song, so it stays in time.
        fill(now);
    }

    private void sendStop(NpcEntity near) {
        if (!(near.level() instanceof ServerLevel level)) return;
        PacketDistributor.sendToPlayersNear(level, null, near.getX(), near.getY(), near.getZ(), SEND_RADIUS,
                new BandSongPayload(id, sentSong, BandSongPayload.STOP, 0,
                        new int[] {-1, -1, -1, -1}, new float[BandSongPayload.PARTS]));
    }

    /**
     * At its place in the line, ready to play. The first time, it is given its part (once
     * fewer than {@link #MAX_PLAYERS} bards have one): its own instrument, whoever else has
     * it, or on Bard's choice the first free one of violin, bass, guitar and bongos (the
     * parts with a recording). So four bards on Bard's choice make a violin, a bass, a
     * guitar and the bongos. Returns the part when it is newly given, else null.
     */
    @Nullable
    BardInstrument ready(UUID id) {
        Member m = members.get(id);
        if (m == null) return null;
        m.ready = true;
        if (m.part != null) return null;
        int players = 0;
        for (Member other : members.values()) if (other.part != null) players++;
        if (players >= MAX_PLAYERS) return null;
        m.part = m.wanted != null ? m.wanted : freePart(takenParts(m));
        return m.part;
    }

    private Set<BardInstrument> takenParts(Member except) {
        Set<BardInstrument> taken = new HashSet<>();
        for (Member other : members.values()) {
            if (other != except && other.part != null) taken.add(other.part);
        }
        return taken;
    }

    /** A Bard's choice part: the first recorded part not in {@code taken}, or the flute with all four taken. */
    private static BardInstrument freePart(Set<BardInstrument> taken) {
        BardInstrument free = freeRecordedPart(taken);
        return free != null ? free : BardInstrument.FLUTE;
    }

    /**
     * At the start of a play-through: every bard set to an instrument plays it (a setting
     * changed during the last song takes effect here), and every Bard's choice bard keeps
     * its part unless a set bard or an earlier Bard's choice bard holds it, or it has no
     * recording; then it takes the first free one. Bards with no part yet are left to
     * {@link #ready}.
     */
    private void handParts() {
        Set<BardInstrument> held = new HashSet<>();
        for (Member m : members.values()) {
            if (m.part == null || m.wanted == null) continue;
            m.part = m.wanted;
            held.add(m.part);
        }
        for (Member m : members.values()) {
            if (m.part == null || m.wanted != null) continue;
            if (held.contains(m.part) || partFor(m.part) == null) m.part = freePart(held);
            held.add(m.part);
        }
    }

    /** Parts with a recording, in the order they are handed out; the first three carry the tune. */
    private static final BardInstrument[] RECORDED_PARTS = {
            BardInstrument.VIOLIN, BardInstrument.BASS, BardInstrument.GUITAR, BardInstrument.BONGOS};

    @Nullable
    private static BardInstrument freeRecordedPart(Set<BardInstrument> taken) {
        for (BardInstrument part : RECORDED_PARTS) if (!taken.contains(part)) return part;
        return null;
    }

    // ---- The piano, once a night per tavern -------------------------------------------

    boolean pianoTaken(long now) {
        return pianist != null && now - pianoClaimedAt < NIGHT_TICKS;
    }

    boolean pianoPlaying(long now) {
        return pianoTaken(now) && !pianoDone;
    }

    /** False if another bard has the piano tonight. */
    boolean claimPiano(UUID id, long now) {
        if (pianoTaken(now) && !id.equals(pianist)) return false;
        pianist = id;
        pianoClaimedAt = now;
        pianoDone = false;
        return true;
    }

    void pianoFinished(UUID id) {
        if (id.equals(pianist)) pianoDone = true;
    }

    // ---- The song ----------------------------------------------------------------------

    boolean songOn(long now) {
        return !roster.isEmpty() && now - songStartedAt < SONG_TICKS;
    }

    boolean inSong(UUID id, long now) {
        return songOn(now) && roster.containsKey(id);
    }

    /** What this bard plays in the current play-through. */
    @Nullable
    BardInstrument partOf(UUID id) {
        return roster.get(id);
    }

    /** Game time the current play-through started (the next one starts exactly SONG_TICKS later). */
    long songStartedAt() {
        return songStartedAt;
    }

    /**
     * Whether a ready member has the violin, bass or guitar, so a song can be played. The drums alone are no song:
     * bards set to the bongos (a Bard's choice drummer would move to a free part) then play their own pieces, as
     * flute players do (B3, 10-24). A song already going plays to its end if only the drums are left.
     */
    boolean hasPartPlayer() {
        for (Member m : members.values()) {
            if (m.ready && m.part != BardInstrument.BONGOS && partFor(m.part) != null) return true;
        }
        return false;
    }

    /**
     * Called by every member each tick before it looks at the song; the first call on
     * the tick a play-through ends starts the next one, for the whole band at once, so
     * nobody shows a tick of the wrong pose between two play-throughs.
     *
     * <p>Members whose own evening is over leave the band here rather than being brought
     * into the next play-through by a bard who plays on: one bard kept the whole band
     * playing until its own evening ended. Their parts are free for the next song.
     */
    void tick(long now, boolean newSongs) {
        if (songOn(now)) {
            fill(now);
            return;
        }
        roster.clear();
        sentHolders = null;
        sentGains = null;
        if (!newSongs) return;
        for (Iterator<Member> it = members.values().iterator(); it.hasNext(); ) {
            Member m = it.next();
            if (!m.playsOn) {
                it.remove();
                dropped(m);
            }
        }
        // Parts are handed out again for the new play-through; parts they held go to
        // a Bard's choice bard now, so it can carry the next song.
        handParts();
        takeFreeParts();
        if (!hasPartPlayer()) return;
        songStartedAt = now;
        fill(now);
    }

    /**
     * Brings everyone ready into the song and tells nearby players who plays which part.
     * The song is one sound on the player's side, its four parts mixed together, so a
     * part coming in mid-song (a bard who has just reached its place, a flute player
     * taking over a part someone left, a new drummer) is turned up inside that sound and
     * is exactly in time with the rest, whatever the lag.
     */
    private void fill(long now) {
        takeFreeParts();
        for (Member m : members.values()) {
            if (m.ready && m.part != null) roster.put(m.id, m.part);
        }
        refreshSong(now);
    }

    /**
     * A part with a recording left free (its bard went home) goes to a Bard's choice bard
     * on the flute. A Bard's choice drummer gets up and takes the violin, bass or guitar once
     * nobody holds any of them, so the song is never the drums alone. Bards set to an
     * instrument are never moved.
     */
    private void takeFreeParts() {
        for (Member m : members.values()) {
            if (m.part != BardInstrument.FLUTE || m.wanted != null) continue;
            BardInstrument free = freeRecordedPart(takenParts(m));
            if (free != null) m.part = free;
        }
        for (Member m : members.values()) {
            if (m.part == BardInstrument.VIOLIN || m.part == BardInstrument.BASS || m.part == BardInstrument.GUITAR) return;
        }
        for (Member m : members.values()) {
            if (m.part == BardInstrument.BONGOS && m.wanted == null) {
                m.part = freeRecordedPart(takenParts(m));
                return;
            }
        }
    }

    /** Index of {@code part} in {@link BandSongPayload} order, or -1 for the flute. */
    private static int partIndex(@Nullable BardInstrument part) {
        if (part == null) return -1;
        return switch (part) {
            case VIOLIN -> 0;
            case GUITAR -> 1;
            case BASS -> 2;
            case BONGOS -> BONGOS_INDEX;
            case FLUTE -> -1;
        };
    }
    private static final int BONGOS_INDEX = 3;

    /** How loud a bard's part is in the mix: its music volume, where 50 or more is full. */
    private static float gainOf(Member m) {
        float volume = ProfessionSettings.of(m.npc, BardSettingsSchema.SCHEMA)
                .getInt(BardSettingsSchema.MUSIC_VOLUME) / 100f;
        return Math.min(1f, volume * 2f);
    }

    /**
     * Works out who plays each part now and sends it when it changed: START on the first
     * tick of a play-through, UPDATE after that. Each part comes from the one bard holding
     * it; the bongos, while nobody holds them, from the first bard with a part.
     */
    private void refreshSong(long now) {
        if (!songOn(now)) return;
        int[] holders = {-1, -1, -1, -1};
        float[] gains = new float[BandSongPayload.PARTS];
        Member drummer = null;
        for (Member m : members.values()) {
            int i = partIndex(roster.get(m.id));
            if (i < 0 || holders[i] >= 0) continue;
            holders[i] = m.npc.getId();
            gains[i] = gainOf(m);
            if (drummer == null) drummer = m;
        }
        if (drummer != null && holders[BONGOS_INDEX] < 0) {
            holders[BONGOS_INDEX] = drummer.npc.getId();
            gains[BONGOS_INDEX] = gainOf(drummer);
        }
        if (sentSong != songStartedAt) {
            sentSong = songStartedAt;
            send(BandSongPayload.START, (int) (now - songStartedAt), holders, gains);
        } else if (!Arrays.equals(holders, sentHolders) || !Arrays.equals(gains, sentGains)) {
            send(BandSongPayload.UPDATE, (int) (now - songStartedAt), holders, gains);
        }
        sentHolders = holders;
        sentGains = gains;
    }

    /** Everyone close enough to see the band gets it. */
    private void send(int action, int into, int[] holders, float[] gains) {
        Member any = members.isEmpty() ? null : members.values().iterator().next();
        if (any == null || !(any.npc.level() instanceof ServerLevel level)) return;
        PacketDistributor.sendToPlayersNear(level, null, any.npc.getX(), any.npc.getY(), any.npc.getZ(),
                SEND_RADIUS, new BandSongPayload(id, sentSong, action, into, holders, gains));
    }

    /** Further than any player can see a villager, so everyone who might hear the band is told. */
    private static final double SEND_RADIUS = 128;

    /**
     * A player who has just come within sight of a bard in a band (walked into range,
     * or joined the world) hears the song from where it is now. A player who already
     * has this play-through only gets the parts again.
     */
    static void sendNowPlaying(ServerPlayer player, NpcEntity bard) {
        long now = bard.level().getGameTime();
        for (TavernBand band : BANDS.values()) {
            if (!band.songOn(now) || !band.members.containsKey(bard.getUUID()) || band.sentHolders == null) continue;
            int into = (int) (now - band.songStartedAt);
            if (into > SONG_TICKS - LATE_START_LIMIT) continue;
            PacketDistributor.sendToPlayer(player, new BandSongPayload(band.id, band.sentSong,
                    BandSongPayload.START, into, band.sentHolders, band.sentGains));
        }
    }

    // ---- Where the band stands and looks -----------------------------------------------

    /**
     * Where the guests are: the middle of everyone settled at this tavern, or the middle
     * of the room before anyone has come. Shared, so the whole band faces the same way.
     */
    Vec3 audience(Activity.Context ctx, Building tavern, long now) {
        if (audience != null && now - audienceAt < AUDIENCE_REFRESH_TICKS) return audience;
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity guest = m.puppet();
            if (guest == null || !guest.isAlive()) continue;
            if (m.brain().currentActivity() instanceof EveningActivity e
                    && e.mode() == EveningActivity.Mode.TAVERN && e.venue() == tavern && e.isHangingAround()) {
                x += guest.getX();
                y += guest.getY();
                z += guest.getZ();
                n++;
            }
        }
        if (n == 0) {
            for (BlockPos tile : tavern.getNavMeshBlocks()) {
                x += tile.getX() + 0.5;
                y += tile.getY();
                z += tile.getZ() + 0.5;
                n++;
            }
        }
        audience = n == 0 ? Vec3.atCenterOf(tavern.getDoorPos()) : new Vec3(x / n, y / n, z / n);
        audienceAt = now;
        return audience;
    }

    /**
     * This bard's place: a line through the stage, across the way the guests are, two
     * blocks apart. The first bard stands at the stage, the next to one side, the next
     * to the other side, and so on outwards. Kept for the rest of the set.
     */
    @Nullable
    BlockPos claimSpot(UUID id, Building tavern, BlockPos stage, Vec3 audience) {
        Member me = members.get(id);
        if (me == null) return stage;
        if (me.spot != null) return me.spot;
        Set<BlockPos> taken = new HashSet<>();
        for (Member m : members.values()) if (m != me && m.spot != null) taken.add(m.spot);
        int slot = taken.size();
        int offset = slot == 0 ? 0 : ((slot + 1) / 2) * SPACING * (slot % 2 == 1 ? 1 : -1);

        double ax = stage.getX() + 0.5, az = stage.getZ() + 0.5;
        double dx = audience.x - ax, dz = audience.z - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) { dx = 1; dz = 0; len = 1; }
        double ix = ax - dz / len * offset, iz = az + dx / len * offset;

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos tile : tavern.getNavMeshBlocks()) {
            if (Math.abs(tile.getY() - stage.getY()) > 1 || taken.contains(tile)) continue;
            boolean crowded = false;
            for (BlockPos other : taken) {
                if (other.distSqr(tile) < 2) { crowded = true; break; }
            }
            double tx = tile.getX() + 0.5 - ix, tz = tile.getZ() + 0.5 - iz;
            double score = tx * tx + tz * tz + (crowded ? 16 : 0);
            if (score < bestScore) { bestScore = score; best = tile; }
        }
        me.spot = best != null ? best : stage;
        return me.spot;
    }
}
