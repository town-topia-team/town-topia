package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.ModSounds;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.enchanter.EnchanterTrance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Decides, for this client's own player, which writing enchanters are in a
 * trance: NORMAL → SEIZE (once) → TRANCE (loop) on walking within
 * {@link EnchanterTrance#IN_RANGE}; RELEASE (once) → NORMAL on stepping past
 * {@link EnchanterTrance#OUT_RANGE} or when the book is done. The phase is
 * written onto the villager, whose work controller then plays the trance
 * clips. Also runs the one ominous loop (the nearest entranced enchanter's)
 * and ducks the calm enchanting loop under it.
 */
@EventBusSubscriber(modid = DynamicVillages.MODID, value = Dist.CLIENT)
public final class EnchanterTranceClient {
    /** mixamo_enchant_seize / _release lengths, in ticks. */
    private static final int SEIZE_TICKS = 33;
    private static final int RELEASE_TICKS = 29;
    private static final int SCAN_INTERVAL = 5;

    private static final Map<Integer, Tracked> TRACKED = new HashMap<>();
    private static ClientLevel trackedLevel;
    private static TranceMusic music;

    private EnchanterTranceClient() {}

    private static final class Tracked {
        final NpcEntity npc;
        int phase = EnchanterTrance.NORMAL;
        int ticks;
        boolean near;

        Tracked(NpcEntity npc) {
            this.npc = npc;
        }
    }

    /** 0..1: how far the ominous loop has faded in; the calm loop plays at 1 minus this. */
    public static float musicMix() {
        return music == null ? 0.0f : music.fade;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level != trackedLevel || level == null || player == null) {
            clear();
            trackedLevel = level;
            if (level == null || player == null) return;
        }
        if (minecraft.isPaused()) return;

        if (level.getGameTime() % SCAN_INTERVAL == 0) {
            double scan = EnchanterTrance.IN_RANGE + 2.0;
            for (NpcEntity npc : level.getEntitiesOfClass(NpcEntity.class,
                    player.getBoundingBox().inflate(scan), NpcEntity::isEnchanting)) {
                TRACKED.computeIfAbsent(npc.getId(), id -> new Tracked(npc));
            }
        }

        boolean alive = player.isAlive();
        NpcEntity loudest = null;
        double loudestDist = Double.MAX_VALUE;
        for (Iterator<Tracked> it = TRACKED.values().iterator(); it.hasNext(); ) {
            Tracked t = it.next();
            NpcEntity npc = t.npc;
            if (npc.isRemoved() || npc.level() != level) {
                npc.setTrancePhase(EnchanterTrance.NORMAL);
                it.remove();
                continue;
            }
            double dist = npc.distanceTo(player);
            t.near = alive && npc.isEnchanting()
                    && dist <= (t.near ? EnchanterTrance.OUT_RANGE : EnchanterTrance.IN_RANGE);
            step(t, level);
            npc.setTrancePhase(t.phase);
            if (t.phase == EnchanterTrance.NORMAL && !npc.isEnchanting()) {
                it.remove();
                continue;
            }
            boolean ominous = t.phase == EnchanterTrance.SEIZE || t.phase == EnchanterTrance.TRANCE;
            if (ominous && dist < loudestDist) {
                loudest = npc;
                loudestDist = dist;
            }
        }

        if (loudest != null && (music == null || music.isStopped())) {
            music = new TranceMusic();
            minecraft.getSoundManager().play(music);
        }
        if (music != null) music.follow(loudest);
    }

    private static void step(Tracked t, ClientLevel level) {
        t.ticks++;
        switch (t.phase) {
            case EnchanterTrance.NORMAL -> {
                if (t.near) {
                    enter(t, EnchanterTrance.SEIZE);
                    NpcEntity npc = t.npc;
                    level.playLocalSound(npc.getX(), npc.getY() + 1.0, npc.getZ(), SoundEvents.WARDEN_HEARTBEAT,
                            SoundSource.HOSTILE, 0.7f, 0.8f, false);
                }
            }
            case EnchanterTrance.SEIZE -> {
                if (!t.near) enter(t, EnchanterTrance.RELEASE);
                else if (t.ticks >= SEIZE_TICKS) enter(t, EnchanterTrance.TRANCE);
            }
            case EnchanterTrance.TRANCE -> {
                if (!t.near) enter(t, EnchanterTrance.RELEASE);
            }
            default -> {
                // Let the release finish; back in range, it seizes again.
                if (t.ticks >= RELEASE_TICKS) enter(t, EnchanterTrance.NORMAL);
            }
        }
    }

    private static void enter(Tracked t, int phase) {
        t.phase = phase;
        t.ticks = 0;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void clear() {
        for (Tracked t : TRACKED.values()) t.npc.setTrancePhase(EnchanterTrance.NORMAL);
        TRACKED.clear();
        if (music != null) music.finish();
        music = null;
    }

    /**
     * The ominous loop. Stereo, so not placed in the world: it plays "in the
     * head", faded in over 0.7 s while an enchanter is in its trance and out
     * over 0.5 s after, then stopped. Muffled outside the library like the
     * calm loop ({@link EnchanterSound#roomShare}).
     */
    private static final class TranceMusic extends AbstractTickableSoundInstance {
        private static final float FADE_IN = 1.0f / 14;
        private static final float FADE_OUT = 1.0f / 10;
        float fade;
        private float room = -1.0f;
        private NpcEntity source;

        TranceMusic() {
            super(ModSounds.ENCHANTER_TRANCE.get(), SoundSource.NEUTRAL, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.x = 0;
            this.y = 0;
            this.z = 0;
            this.volume = 0.0f;
        }

        void follow(NpcEntity entranced) {
            source = entranced;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != trackedLevel || minecraft.player == null) {
                finish();
                return;
            }
            if (source != null) {
                fade = Math.min(1.0f, fade + FADE_IN);
                room = EnchanterSound.easeRoom(room, EnchanterSound.roomShare(source, minecraft.player));
            } else {
                fade -= FADE_OUT;
                if (fade <= 0.0f) {
                    finish();
                    return;
                }
            }
            volume = fade * Math.max(room, 0.0f);
        }

        void finish() {
            fade = 0.0f;
            stop();
            Minecraft.getInstance().getSoundManager().stop(this);
        }
    }

    /** Whether {@code entity} is an enchanter this client currently shows in a trance (any phase). */
    public static boolean inTrance(Entity entity) {
        return entity instanceof NpcEntity npc && npc.getTrancePhase() != EnchanterTrance.NORMAL;
    }
}
