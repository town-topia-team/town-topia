package com.alex.test.village.building;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Keeps players' muddy-cauldron tints in step with the server: the full set on
 * login, dimension change and respawn, and the periodic stale-mark sweep.
 * Registered on the game bus by {@link com.alex.test.DynamicVillages}.
 */
public final class CauldronEvents {

    private CauldronEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Frozen, the game time stands still and this would sweep every tick.
        if (com.alex.test.village.stuckwatch.StuckWatch.worldHeld(level)) return;
        if (level.getGameTime() % MuddyCauldrons.SWEEP_TICKS != 0) return;
        MuddyCauldrons.sweep(level);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) MuddyCauldrons.sendAll(player);
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) MuddyCauldrons.sendAll(player);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) MuddyCauldrons.sendAll(player);
    }
}
