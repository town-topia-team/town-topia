package com.alex.test.village.villagers.bard.behavior;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * A player who comes within sight of a bard while the tavern band is playing (walking
 * up, or joining the world) is sent that bard's part from where the song is now, so it
 * is heard in time with the rest instead of only from the next play-through.
 */
@EventBusSubscriber(modid = DynamicVillages.MODID)
public final class BardTrackSync {
    private BardTrackSync() {}

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof NpcEntity bard && event.getEntity() instanceof ServerPlayer player) {
            TavernBand.sendNowPlaying(player, bard);
        }
    }
}
