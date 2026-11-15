package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.ModSounds;
import com.alex.test.network.BandSongPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.FloatSampleSource;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The tavern band's song: its four parts mixed into one stream ({@link BandMixStream}),
 * so they are always exactly together. It sounds from the middle of the bards playing,
 * and a part's gain follows whether a bard is playing it, which the server sends as it
 * changes. One per band; a new play-through replaces the last.
 */
@EventBusSubscriber(modid = DynamicVillages.MODID, value = Dist.CLIENT)
public final class BandSongSound extends AbstractTickableSoundInstance {
    private static final Map<UUID, BandSongSound> PLAYING = new HashMap<>();
    /** The recordings, in {@link BandSongPayload} part order. */
    private static final String[] PART_FILES = {
            "long_road_ahead_violin", "long_road_ahead_guitar", "long_road_ahead_bass", "long_road_ahead_bongos"};
    private static final int SONG_TICKS = 960;
    /** Nobody of the band in sight for this long: the song is over here. */
    private static final int ALONE_TICKS = 40;

    private final UUID bandId;
    private final long songId;
    private final ClientLevel level;
    private final int offsetTicks;
    private final int lastTick;
    private int[] holders;
    /** Read by the sound thread when the mix opens, so always seen fresh. */
    private volatile float[] gains;
    @Nullable private volatile BandMixStream mix;
    private int ticks;
    private int alone;

    private BandSongSound(BandSongPayload payload, ClientLevel level) {
        // Carried by the violin part's event: it is streamed, which routes playback through getStream.
        super(ModSounds.BARD_BAND_VIOLIN.get(), SoundSource.RECORDS, RandomSource.create());
        this.bandId = payload.bandId();
        this.songId = payload.songId();
        this.level = level;
        this.offsetTicks = payload.offsetTicks();
        this.lastTick = SONG_TICKS - offsetTicks + 40;
        this.holders = payload.holders().clone();
        this.gains = payload.gains().clone();
        // Over 1 only widens how far it is heard (32 blocks); the parts' loudness is in the mix.
        this.volume = 2f;
        this.looping = false;
        follow();
    }

    public static void apply(BandSongPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        BandSongSound playing = PLAYING.get(payload.bandId());
        if (payload.action() == BandSongPayload.STOP) {
            if (playing != null) playing.finish();
            return;
        }
        // The same play-through again (a second message on coming into sight): only the parts change.
        if (playing != null && playing.songId == payload.songId()) {
            playing.update(payload);
            return;
        }
        if (payload.action() != BandSongPayload.START || minecraft.level == null) return;
        if (playing != null) playing.finish();
        BandSongSound sound = new BandSongSound(payload, minecraft.level);
        PLAYING.put(payload.bandId(), sound);
        minecraft.getSoundManager().play(sound);
    }

    private void update(BandSongPayload payload) {
        holders = payload.holders().clone();
        gains = payload.gains().clone();
        BandMixStream stream = mix;
        if (stream != null) stream.setGains(gains);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        for (BandSongSound sound : List.copyOf(PLAYING.values())) sound.finish();
        PLAYING.clear();
    }

    /**
     * Opens all four recordings, mixes them, and starts the mix {@code offsetTicks} in.
     * Opening and skipping take a moment, so the time from the server's message to the
     * first sample is skipped as well: the song is where it should be when it is heard.
     */
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        long asked = System.nanoTime();
        @SuppressWarnings("unchecked")
        CompletableFuture<AudioStream>[] opening = new CompletableFuture[PART_FILES.length];
        for (int i = 0; i < PART_FILES.length; i++) {
            opening[i] = soundBuffers.getStream(ResourceLocation.fromNamespaceAndPath(
                    DynamicVillages.MODID, "sounds/bard/" + PART_FILES[i] + ".ogg"), false);
        }
        return CompletableFuture.allOf(opening).thenApply(done -> {
            FloatSampleSource[] parts = new FloatSampleSource[opening.length];
            for (int i = 0; i < opening.length; i++) parts[i] = (FloatSampleSource) opening[i].join();
            BandMixStream stream = new BandMixStream(parts, gains);
            float rate = stream.getFormat().getSampleRate();
            try {
                stream.skip(Math.round(offsetTicks / 20.0 * rate));
                stream.skip(Math.round((System.nanoTime() - asked) / 1e9 * rate));
            } catch (IOException e) {
                DynamicVillages.LOGGER.warn("Could not start the band song partway through: {}", e.toString());
            }
            stream.setGains(gains);
            mix = stream;
            return stream;
        });
    }

    @Override
    public boolean canStartSilent() { return true; }

    @Override
    public void tick() {
        if (Minecraft.getInstance().level != level || ++ticks >= lastTick) {
            finish();
            return;
        }
        if (follow()) {
            alone = 0;
        } else if (++alone >= ALONE_TICKS) {
            finish();
        }
    }

    /** Moves the sound to the middle of the bards playing a part; false if none is in sight. */
    private boolean follow() {
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (int i = 0; i < holders.length; i++) {
            if (holders[i] < 0 || gains[i] <= 0f) continue;
            Entity bard = level.getEntity(holders[i]);
            if (bard == null || !bard.isAlive()) continue;
            sx += bard.getX();
            sy += bard.getY() + 1.2;
            sz += bard.getZ();
            n++;
        }
        if (n == 0) return false;
        x = sx / n;
        y = sy / n;
        z = sz / n;
        return true;
    }

    private void finish() {
        stop();
        Minecraft.getInstance().getSoundManager().stop(this);
        PLAYING.remove(bandId, this);
    }
}
