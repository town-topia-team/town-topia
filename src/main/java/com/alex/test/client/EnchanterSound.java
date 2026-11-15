package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.ModSounds;
import com.alex.test.entity.NpcEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The soft spell-weaving loop an enchanter makes while it writes a book.
 * Started on this client for each enchanter near the player whose
 * {@link NpcEntity#isEnchanting()} is on; follows it, and fades out when the
 * book is done, the enchanter is gone, or the player walks away. Loud inside
 * the library it writes in, softer outside where the walls muffle it.
 */
@EventBusSubscriber(modid = DynamicVillages.MODID, value = Dist.CLIENT)
public final class EnchanterSound extends AbstractTickableSoundInstance {
    /** Heard from this far; matches the sound's own 16-block range. */
    private static final double START_RANGE = 16.0;
    /** Dropped past this, so a player leaving does not keep a silent stream open. */
    private static final double STOP_RANGE = 20.0;
    /** Standing in the library with it. */
    private static final float FULL_VOLUME = 1.0f;
    /**
     * Share of the volume left outside the library: the walls muffle it. The
     * trance music uses the same share through {@link #roomShare}.
     */
    public static final float OUTSIDE_SHARE = 0.3f;
    /** How fast the room share follows the player through a door: about 0.6 s end to end. */
    public static final float ROOM_STEP = 1.0f / 12;
    private static final int FADE_IN_TICKS = 20;
    private static final int FADE_OUT_TICKS = 10;
    private static final int SCAN_INTERVAL = 10;

    private static final Map<UUID, EnchanterSound> PLAYING = new HashMap<>();

    private final UUID enchanterId;
    private final int entityId;
    private final ClientLevel level;
    /** 0..1 of {@link #FULL_VOLUME}; climbs while writing, falls once it stops. */
    private float fade;
    private boolean fadingOut;
    /** {@link #OUTSIDE_SHARE}..1, eased toward where the player stands now. */
    private float room = -1.0f;

    private EnchanterSound(NpcEntity enchanter, ClientLevel level) {
        super(ModSounds.ENCHANTER_ENCHANTING.get(), SoundSource.NEUTRAL, RandomSource.create());
        this.enchanterId = enchanter.getUUID();
        this.entityId = enchanter.getId();
        this.level = level;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0f;
        follow(enchanter);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || minecraft.isPaused()) return;
        if (level.getGameTime() % SCAN_INTERVAL != 0) return;
        for (NpcEntity npc : level.getEntitiesOfClass(NpcEntity.class,
                player.getBoundingBox().inflate(START_RANGE), NpcEntity::isEnchanting)) {
            if (npc.distanceToSqr(player) > START_RANGE * START_RANGE) continue;
            EnchanterSound current = PLAYING.get(npc.getUUID());
            if (current != null && !current.isStopped() && !current.fadingOut) continue;
            if (current != null) current.finish();
            EnchanterSound sound = new EnchanterSound(npc, level);
            PLAYING.put(npc.getUUID(), sound);
            minecraft.getSoundManager().play(sound);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        for (EnchanterSound sound : List.copyOf(PLAYING.values())) sound.finish();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        Entity entity = level.getEntity(entityId);
        if (minecraft.level != level || minecraft.player == null || !(entity instanceof NpcEntity enchanter)
                || !enchanter.isAlive() || !enchanter.getUUID().equals(enchanterId)) {
            finish();
            return;
        }
        follow(enchanter);
        if (!enchanter.isEnchanting() || enchanter.distanceToSqr(minecraft.player) > STOP_RANGE * STOP_RANGE) {
            fadingOut = true;
        }
        if (fadingOut) {
            fade -= 1.0f / FADE_OUT_TICKS;
            if (fade <= 0.0f) {
                finish();
                return;
            }
        } else {
            fade = Math.min(1.0f, fade + 1.0f / FADE_IN_TICKS);
        }
        room = easeRoom(room, roomShare(enchanter, minecraft.player));
        // Ducked under the trance music while it plays, back up as it fades out.
        volume = FULL_VOLUME * fade * room * (1.0f - EnchanterTranceClient.musicMix());
    }

    /**
     * 1 when the player stands in the library the enchanter writes in (or the
     * room is not known), {@link #OUTSIDE_SHARE} outside it.
     */
    public static float roomShare(NpcEntity enchanter, Entity listener) {
        Boolean inside = enchanter.isInEnchantingRoom(listener.position());
        return inside == null || inside ? 1.0f : OUTSIDE_SHARE;
    }

    /** One tick of {@code current} moving toward {@code target}; a first call (negative) jumps straight there. */
    public static float easeRoom(float current, float target) {
        if (current < 0.0f) return target;
        if (current < target) return Math.min(target, current + ROOM_STEP);
        return Math.max(target, current - ROOM_STEP);
    }

    private void follow(NpcEntity enchanter) {
        x = enchanter.getX();
        y = enchanter.getY() + 1.2;
        z = enchanter.getZ();
    }

    private void finish() {
        fadingOut = true;
        stop();
        Minecraft.getInstance().getSoundManager().stop(this);
        PLAYING.remove(enchanterId, this);
    }
}
