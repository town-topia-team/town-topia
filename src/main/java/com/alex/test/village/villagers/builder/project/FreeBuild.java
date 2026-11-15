package com.alex.test.village.villagers.builder.project;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Builder's "Free Building" switch (10-25): only a player in Creative may turn it on, and it
 * lasts while that player stays in Creative and online. While on, the Builder places blocks
 * without taking any, still one at a time ({@code BuildSiteWork}).
 *
 * <p>Saved on the Builder (brain data): who turned it on and the game time they did. A player
 * who left Creative or joined again after that time (logging off counts as leaving) no longer
 * keeps it on; the Builder finds out before its next block ({@link #on}), switches it off and
 * tells them in chat.
 */
public final class FreeBuild {
    private static final String BY = "BuilderFreeBy", AT = "BuilderFreeAt";
    /** Online players: game time they last joined or left Creative. Not saved: every join writes it again. */
    private static final Map<UUID, Long> LEFT = new HashMap<>();

    private FreeBuild() {}

    /** The player keeping this Builder's switch on, or null when it is off (or no longer counts). */
    @Nullable
    public static ServerPlayer owner(NpcEntity builder) {
        CompoundTag data = builder.getBrainData();
        MinecraftServer server = builder.getServer();
        if (server == null || !data.hasUUID(BY)) return null;
        UUID id = data.getUUID(BY);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null || !player.isCreative()) return null;
        return LEFT.getOrDefault(id, Long.MIN_VALUE) < data.getLong(AT) ? player : null;
    }

    /**
     * Server, before each block: true while the switch is on. One whose player left Creative is
     * switched off here: that player is told, and the village's open desks are refreshed.
     */
    public static boolean on(NpcEntity builder, UUID villageId) {
        CompoundTag data = builder.getBrainData();
        if (!data.hasUUID(BY)) return false;
        if (owner(builder) != null) return true;
        UUID id = data.getUUID(BY);
        set(builder, null);
        MinecraftServer server = builder.getServer();
        if (server == null) return false;
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            player.displayClientMessage(Component.literal("The Builder's free building stopped: you left Creative."),
                    false);
        }
        DynamicVillages.LOGGER.info("[Builder] {}: free building stopped, its player left Creative",
                builder.getDisplayName().getString());
        BuildOrders.changed(server, villageId);
        return false;
    }

    /** Turns the switch on for {@code player} (who must be in Creative), or off with null. */
    public static void set(NpcEntity builder, @Nullable ServerPlayer player) {
        CompoundTag data = builder.getBrainData();
        if (player == null) {
            data.remove(BY);
            data.remove(AT);
            return;
        }
        data.putUUID(BY, player.getUUID());
        data.putLong(AT, now(player.server));
    }

    private static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    // =========================================================================
    // Events
    // =========================================================================

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) LEFT.put(player.getUUID(), now(player.server));
    }

    @SubscribeEvent
    public static void onGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (event.getNewGameMode() != GameType.CREATIVE && event.getEntity() instanceof ServerPlayer player) {
            LEFT.put(player.getUUID(), now(player.server));
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // Offline already counts as left; the next join writes a fresh time.
        LEFT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LEFT.clear();
    }
}
