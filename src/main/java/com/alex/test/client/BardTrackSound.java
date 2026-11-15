package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.network.BardTrackPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A recording played from a bard: it moves with the bard, never loops, and ends on
 * the bard's STOP, when the bard is gone, or after its length. The server sends every
 * part of the tavern song on the same tick, so they are all started in the same frame.
 */
@EventBusSubscriber(modid = DynamicVillages.MODID, value = Dist.CLIENT)
public final class BardTrackSound extends AbstractTickableSoundInstance {
    private static final Map<UUID, List<BardTrackSound>> PLAYING = new HashMap<>();
    private final UUID bardId;
    private final int entityId;
    private final ClientLevel level;
    private final int durationTicks;
    /** How far into the recording it starts, in game ticks (a part joining a song already on). */
    private final int offsetTicks;
    private int ticks;

    private BardTrackSound(BardTrackPayload payload, ClientLevel level, Entity bard) {
        super(SoundEvent.createVariableRangeEvent(payload.sound()), SoundSource.RECORDS, RandomSource.create());
        this.bardId = payload.bardId();
        this.entityId = payload.entityId();
        this.level = level;
        this.offsetTicks = payload.offsetTicks();
        this.durationTicks = payload.durationTicks() - offsetTicks;
        this.volume = Math.max(0f, payload.volume());
        this.looping = false;
        follow(bard);
    }

    /**
     * A recording started partway through: the stream is opened as usual and the
     * first {@code offsetTicks} of it are read and thrown away. Reading them takes a
     * moment, so that time is skipped as well, which keeps the part in time with the
     * ones already playing. Only streamed sounds come through here (the band's song).
     */
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        CompletableFuture<AudioStream> stream = super.getStream(soundBuffers, sound, looping);
        if (offsetTicks <= 0) return stream;
        return stream.thenApply(audio -> {
            try {
                long began = System.nanoTime();
                skip(audio, offsetTicks / 20.0);
                skip(audio, (System.nanoTime() - began) / 1e9);
            } catch (IOException e) {
                DynamicVillages.LOGGER.warn("Could not start {} partway through: {}", sound.getLocation(), e.toString());
            }
            return audio;
        });
    }

    private static void skip(AudioStream audio, double seconds) throws IOException {
        AudioFormat format = audio.getFormat();
        int frame = Math.max(1, format.getFrameSize());
        long left = Math.round(seconds * format.getSampleRate()) * frame;
        while (left > 0) {
            ByteBuffer read = audio.read((int) Math.min(left, 65536));
            if (read == null || !read.hasRemaining()) return;
            left -= read.remaining();
        }
    }

    public static void apply(BardTrackPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        List<BardTrackSound> mine = PLAYING.get(payload.bardId());
        if (payload.action() == BardTrackPayload.STOP) {
            if (mine != null) for (BardTrackSound sound : List.copyOf(mine)) sound.finish();
            return;
        }
        if (minecraft.level == null) return;
        Entity bard = minecraft.level.getEntity(payload.entityId());
        if (bard == null) return;
        // The same recording started again replaces the old one.
        if (mine != null) {
            for (BardTrackSound sound : List.copyOf(mine)) {
                if (sound.getLocation().equals(payload.sound())) sound.finish();
            }
        }
        BardTrackSound sound = new BardTrackSound(payload, minecraft.level, bard);
        PLAYING.computeIfAbsent(payload.bardId(), id -> new ArrayList<>()).add(sound);
        minecraft.getSoundManager().play(sound);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        for (List<BardTrackSound> sounds : List.copyOf(PLAYING.values())) {
            for (BardTrackSound sound : List.copyOf(sounds)) sound.finish();
        }
        PLAYING.clear();
    }

    @Override
    public boolean canStartSilent() { return true; }

    @Override
    public void tick() {
        Entity bard = level.getEntity(entityId);
        if (Minecraft.getInstance().level != level || bard == null || !bard.isAlive()
                || !bard.getUUID().equals(bardId) || ++ticks >= durationTicks) {
            finish();
            return;
        }
        follow(bard);
    }

    private void follow(Entity bard) {
        x = bard.getX();
        y = bard.getY() + 1.2;
        z = bard.getZ();
    }

    private void finish() {
        stop();
        Minecraft.getInstance().getSoundManager().stop(this);
        List<BardTrackSound> mine = PLAYING.get(bardId);
        if (mine != null) {
            mine.remove(this);
            if (mine.isEmpty()) PLAYING.remove(bardId);
        }
    }
}
