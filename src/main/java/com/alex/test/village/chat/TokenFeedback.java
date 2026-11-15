package com.alex.test.village.chat;

import com.alex.test.Tr;
import com.alex.test.village.VillageManager;
import com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType;
import com.alex.test.village.building.BuildingRequirements;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Central home for player-facing village token and founding chat messages. */
public final class TokenFeedback {

    private static final double MESSAGE_RADIUS = 48.0;
    /** Village-wide news reaches players inside the village or this close to its Town Hall. */
    private static final double VILLAGE_MESSAGE_RADIUS = 128.0;
    private static final long CHAT_DEDUPE_TICKS = 20L;
    private static final Map<PlacementKey, Long> LAST_PLACEMENT_MESSAGE = new HashMap<>();
    private static final Map<ChatKey, Long> LAST_CHAT_MESSAGE = new HashMap<>();

    /** Places where token chat is held back for a while; see {@link #quiet}. */
    private static final List<QuietArea> QUIET_AREAS = new ArrayList<>();

    private TokenFeedback() {}

    /**
     * Holds back token chat about anything inside {@code box} for {@code ticks}.
     *
     * <p>Used while a generated village turns itself into one of ours. Nobody
     * hung those tokens, so their founding and registration chat would only
     * puzzle a player passing by; the takeover sends its own short line instead.
     */
    public static void quiet(ServerLevel level, BoundingBox box, long ticks) {
        QUIET_AREAS.add(new QuietArea(level.dimension().location().toString(), box,
                level.getGameTime() + ticks));
    }

    /**
     * Forgets every quiet area of {@code level}'s dimension, for a world
     * closing. They are timed by game time, which starts over in the next
     * world opened, so one left behind could mute token chat there for hours.
     */
    public static void clearQuiet(ServerLevel level) {
        String dimension = level.dimension().location().toString();
        QUIET_AREAS.removeIf(area -> area.dimension().equals(dimension));
    }

    private static boolean isQuiet(ServerLevel level, BlockPos pos) {
        if (QUIET_AREAS.isEmpty()) return false;
        long now = level.getGameTime();
        QUIET_AREAS.removeIf(area -> area.until() <= now);
        String dimension = level.dimension().location().toString();
        for (QuietArea area : QUIET_AREAS) {
            if (area.dimension().equals(dimension) && area.box().isInside(pos)) return true;
        }
        return false;
    }

    /** Announces a token outcome once per frame, outcome and game tick. */
    public static void announcePlacement(ServerLevel level, BlockPos framePos, TokenType type,
                                         boolean valid, @Nullable String problem) {
        List<ServerPlayer> audience = placementAudience(level, framePos, type, valid);
        if (audience.isEmpty()) return;
        if (valid) {
            for (Component message : BuildingRegistrationMessages.forType(type)) {
                send(audience, message);
            }
            return;
        }
        send(audience, (problem != null
                ? Tr.c("chat.dynamicvillages.token.not_registered", "[Village] %1$s not registered: %2$s.",
                        BuildingRequirements.name(type), problem)
                : Tr.c("chat.dynamicvillages.token.not_registered.unknown",
                        "[Village] %s not registered: its room or enclosure could not be validated.",
                        BuildingRequirements.name(type)))
                .withStyle(ChatFormatting.RED));
        if (type == TokenType.STALL) send(audience, BuildingRegistrationMessages.stallPlacementHelp());
    }

    /** A diagnosed placement failure, with wording owned by {@link RegistrationFailure}. */
    public static void announceFailure(ServerLevel level, BlockPos framePos, TokenType type,
                                       RegistrationFailure reason, BlockPos problemPos) {
        announceFailure(level, framePos, type, reason, problemPos, 0, 0);
    }

    public static void announceFailure(ServerLevel level, BlockPos framePos, TokenType type,
                                       RegistrationFailure reason, BlockPos problemPos,
                                       int count, int required) {
        List<ServerPlayer> audience = placementAudience(level, framePos, type, false);
        send(audience, Component.literal("[Village] ")
                .append(reason.component(type, problemPos, count, required)).withStyle(ChatFormatting.RED));
    }

    /** Several lines for one failed placement, sent like the others: once per frame, outcome and tick. */
    public static void announceLines(ServerLevel level, BlockPos framePos, TokenType type, List<Component> lines) {
        List<ServerPlayer> audience = placementAudience(level, framePos, type, false);
        for (Component line : lines) send(audience, line);
    }

    /** An intact saved building can temporarily lose its route without losing its marker. */
    public static void announceAccessChange(ServerLevel level, BlockPos framePos, TokenType type,
                                            boolean reachable, BlockPos entrance) {
        announceAccessChange(level, framePos, type, reachable, entrance, false);
    }

    public static void announceAccessChange(ServerLevel level, BlockPos framePos, TokenType type,
                                            boolean reachable, BlockPos entrance,
                                            boolean graphUnavailable) {
        Component name = BuildingRequirements.name(type);
        String at = entrance.toShortString();
        Component message = reachable
                ? Tr.c("chat.dynamicvillages.token.reachable", "[Village] %1$s at %2$s is reachable again.", name, at)
                : graphUnavailable
                ? Tr.c("chat.dynamicvillages.token.paths_unavailable",
                        "[Village] %1$s at %2$s is inactive because village paths are unavailable.", name, at)
                : Tr.c("chat.dynamicvillages.token.unreachable",
                        "[Village] %1$s at %2$s is unreachable. Its token is inactive until villagers can reach it.", name, at);
        announce(level, framePos, message.copy().withStyle(
                reachable ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    /** A successful registration told in the building's own words, deduplicated like any other. */
    public static void announceRegistered(ServerLevel level, BlockPos framePos, TokenType type,
                                          List<Component> messages) {
        List<ServerPlayer> audience = placementAudience(level, framePos, type, true);
        for (Component message : messages) send(audience, message);
    }

    /** Nearby players, or nobody when this frame already reported this outcome this tick. */
    private static List<ServerPlayer> placementAudience(ServerLevel level, BlockPos framePos,
                                                        TokenType type, boolean valid) {
        PlacementKey key = new PlacementKey(level.dimension().location().toString(),
                framePos.immutable(), type, valid);
        long gameTime = level.getGameTime();
        if (LAST_PLACEMENT_MESSAGE.getOrDefault(key, Long.MIN_VALUE) == gameTime) return List.of();
        LAST_PLACEMENT_MESSAGE.put(key, gameTime);
        LAST_PLACEMENT_MESSAGE.entrySet().removeIf(entry -> gameTime - entry.getValue() > 1);
        return nearbyPlayers(level, framePos);
    }

    /** Explains which persistent Town Hall identity owns the village at this location. */
    public static void announceTownHallOwner(ServerLevel level, BlockPos pos, UUID townHallId) {
        String id = townHallId.toString().substring(0, 8);
        announce(level, pos, Tr.c("chat.dynamicvillages.token.owner",
                "[Village] This village belongs to the Town Hall token with ID %s.", id)
                .withStyle(ChatFormatting.RED));
    }

    /** One plain message to everyone near {@code pos}. */
    public static void announce(ServerLevel level, BlockPos pos, Component message) {
        long gameTime = level.getGameTime();
        ChatKey key = new ChatKey(level.dimension().location().toString(),
                pos.immutable(), message.getString());
        Long lastMessageTick = LAST_CHAT_MESSAGE.get(key);
        if (lastMessageTick != null && gameTime - lastMessageTick <= CHAT_DEDUPE_TICKS) return;
        LAST_CHAT_MESSAGE.put(key, gameTime);
        LAST_CHAT_MESSAGE.entrySet().removeIf(
                entry -> gameTime - entry.getValue() > CHAT_DEDUPE_TICKS);
        send(nearbyPlayers(level, pos), message);
    }

    /** Single-line rejection for a token owned by another village. */
    public static void announceForeignVillageToken(ServerLevel level, BlockPos pos) {
        announce(level, pos, Tr.c("chat.dynamicvillages.token.foreign", "[Village] This token belongs to another village.")
                .withStyle(ChatFormatting.RED));
    }

    /** The founding messages are kept beside all other token-facing chat text. */
    public static void announceFounded(ServerLevel level, BlockPos pos) {
        announce(level, pos, Tr.c("chat.dynamicvillages.village.founded", "[Village] Your village has been founded!")
                .withStyle(ChatFormatting.GREEN));
        announce(level, pos, Tr.c("chat.dynamicvillages.village.founded.mayor",
                "Talk to the Mayor to buy building and job tokens for emeralds.")
                .withStyle(ChatFormatting.GRAY));
        for (Component message : BuildingRegistrationMessages.featureLines(TokenType.TOWN_HALL)) {
            announce(level, pos, message);
        }
        announce(level, pos, Tr.c("chat.dynamicvillages.village.founded.settlers", "Settlers are on their way to the village.")
                .withStyle(ChatFormatting.GRAY));
    }

    /** A Town Hall was just hung by hand: its village is founded once its map is drawn. */
    public static void announceTownHallSettingUp(ServerLevel level, BlockPos framePos) {
        announce(level, framePos, Tr.c("chat.dynamicvillages.village.setting_up",
                "[Village] Setting up your village. This can take a few seconds.")
                .withStyle(ChatFormatting.YELLOW));
    }

    /** The token left its frame before the village it was setting up was founded. */
    public static void announceTownHallSetupStopped(ServerLevel level, BlockPos framePos) {
        announce(level, framePos, Tr.c("chat.dynamicvillages.village.setup_stopped",
                "[Village] Village setup stopped: the Town Hall token left its frame.")
                .withStyle(ChatFormatting.RED));
    }

    /** The Town Hall token has left its frame; the village pauses unless it is back in time. */
    public static void announceTownHallTokenMissing(ServerLevel level, VillageManager village,
                                                    BlockPos framePos, int secondsLeft) {
        List<ServerPlayer> audience = villagePlayers(level, village);
        send(audience, Tr.c("chat.dynamicvillages.village.token_out",
                "[Village] Your Town Hall token is out of its frame at %s!", framePos.toShortString())
                .withStyle(ChatFormatting.GOLD));
        send(audience, Tr.c("chat.dynamicvillages.village.token_out.desc",
                "Hang it back within about %s seconds, or the village will pause.", secondsLeft)
                .withStyle(ChatFormatting.GRAY));
    }

    /** The token stayed out too long: villagers stop and wait for it. */
    public static void announceVillagePaused(ServerLevel level, VillageManager village, BlockPos framePos) {
        List<ServerPlayer> audience = villagePlayers(level, village);
        send(audience, Tr.c("chat.dynamicvillages.village.paused",
                "[Village] Your village has paused. The villagers will wait for their Town Hall token.")
                .withStyle(ChatFormatting.RED));
        send(audience, Tr.c("chat.dynamicvillages.village.paused.desc",
                "Hang the token in a frame at %s to wake the village up.", framePos.toShortString())
                .withStyle(ChatFormatting.GRAY));
    }

    /** The token is back after players were told it was out. */
    public static void announceTownHallTokenBack(ServerLevel level, VillageManager village) {
        send(villagePlayers(level, village), Tr.c("chat.dynamicvillages.village.token_back",
                "[Village] The Town Hall token is back. Your village carries on.")
                .withStyle(ChatFormatting.GREEN));
    }

    private static void send(List<ServerPlayer> audience, Component message) {
        for (ServerPlayer player : audience) player.sendSystemMessage(message);
    }

    private static List<ServerPlayer> villagePlayers(ServerLevel level, VillageManager village) {
        BlockPos bell = village.getBellPos();
        if (isQuiet(level, bell)) return List.of();
        double radiusSq = VILLAGE_MESSAGE_RADIUS * VILLAGE_MESSAGE_RADIUS;
        return level.players().stream()
                .filter(player -> village.contains(player.blockPosition())
                        || player.distanceToSqr(bell.getX() + 0.5, bell.getY() + 0.5,
                                bell.getZ() + 0.5) <= radiusSq)
                .toList();
    }

    private static List<ServerPlayer> nearbyPlayers(ServerLevel level, BlockPos pos) {
        if (isQuiet(level, pos)) return List.of();
        double radiusSq = MESSAGE_RADIUS * MESSAGE_RADIUS;
        return level.players().stream()
                .filter(player -> player.distanceToSqr(
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= radiusSq)
                .toList();
    }

    private record QuietArea(String dimension, BoundingBox box, long until) {}
    private record PlacementKey(String dimension, BlockPos pos, TokenType type, boolean valid) {}
    private record ChatKey(String dimension, BlockPos pos, String message) {}
}
