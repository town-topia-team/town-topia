package com.alex.test.village.villagers.bard.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.network.BardTrackPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Recordings played from a bard, sent to everyone who can see it. Unlike a plain
 * playSound, these can be stopped again, so the music never outlasts the playing pose,
 * and started partway through, so a late part stays in time with the rest.
 */
public final class BardTracks {
    private BardTracks() {}

    /** Plays {@code sound} from the bard; {@code durationTicks} is the recording's length. */
    public static void play(NpcEntity bard, SoundEvent sound, float volume, int durationTicks) {
        play(bard, sound, volume, durationTicks, 0);
    }

    /** As {@link #play(NpcEntity, SoundEvent, float, int)}, starting {@code offsetTicks} into the recording. */
    public static void play(NpcEntity bard, SoundEvent sound, float volume, int durationTicks, int offsetTicks) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(bard, start(bard, sound, volume, durationTicks, offsetTicks));
    }

    /** The same, to one player only: one who has just come within sight of the bard. */
    public static void playTo(ServerPlayer player, Entity bard, SoundEvent sound, float volume,
                              int durationTicks, int offsetTicks) {
        PacketDistributor.sendToPlayer(player, start(bard, sound, volume, durationTicks, offsetTicks));
    }

    private static BardTrackPayload start(Entity bard, SoundEvent sound, float volume, int durationTicks, int offsetTicks) {
        return new BardTrackPayload(bard.getUUID(), bard.getId(), sound.getLocation(),
                BardTrackPayload.START, volume, durationTicks, Math.max(0, offsetTicks));
    }

    /** Ends every recording this bard is playing. */
    public static void stop(NpcEntity bard) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(bard, new BardTrackPayload(bard.getUUID(), bard.getId(),
                ResourceLocation.withDefaultNamespace("empty"), BardTrackPayload.STOP, 0f, 0, 0));
    }
}
