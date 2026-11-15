package com.alex.test.client;

import com.alex.test.ModSounds;
import com.alex.test.DynamicVillages;
import com.alex.test.network.BardPianoPayload;
import com.alex.test.village.villagers.bard.behavior.BardPianoPerformance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;

/** A single streamed, stationary stage performance; never loops. */
@EventBusSubscriber(modid = DynamicVillages.MODID, value = Dist.CLIENT)
public final class BardPianoSound extends AbstractTickableSoundInstance {
    private static final Map<UUID, BardPianoSound> PLAYING = new HashMap<>();
    private final UUID bardId;
    private final int entityId;
    private final ClientLevel level;
    private int ticks;

    private BardPianoSound(BardPianoPayload payload, ClientLevel level) {
        super(ModSounds.BARD_PIANO.get(), SoundSource.RECORDS, RandomSource.create());
        this.bardId = payload.bardId();
        this.entityId = payload.entityId();
        this.level = level;
        this.x = payload.stage().getX() + 0.5;
        this.y = payload.stage().getY() + 1.0;
        this.z = payload.stage().getZ() + 0.5;
        this.volume = Math.clamp(payload.volume(), 0f, 1f) * 2f;
        this.looping = false;
    }

    public static void apply(BardPianoPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        BardPianoSound previous = PLAYING.get(payload.bardId());
        if (payload.action() == BardPianoPayload.VOLUME) {
            if (previous != null) previous.volume = Math.clamp(payload.volume(), 0f, 1f) * 2f;
            return;
        }
        if (previous != null) previous.finish();
        if (payload.action() != BardPianoPayload.START || minecraft.level == null) return;
        BardPianoSound sound = new BardPianoSound(payload, minecraft.level);
        PLAYING.put(payload.bardId(), sound);
        minecraft.getSoundManager().play(sound);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        for (BardPianoSound sound : List.copyOf(PLAYING.values())) sound.finish();
    }

    @Override
    public boolean canStartSilent() { return true; }

    @Override
    public void tick() {
        Entity bard = level.getEntity(entityId);
        if (Minecraft.getInstance().level != level || bard == null || !bard.isAlive()
                || !bard.getUUID().equals(bardId) || ++ticks >= BardPianoPerformance.DURATION_TICKS) {
            finish();
        }
    }

    private void finish() {
        stop();
        Minecraft.getInstance().getSoundManager().stop(this);
        PLAYING.remove(bardId, this);
    }
}
