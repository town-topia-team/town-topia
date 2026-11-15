package com.alex.test.village.raid;

import com.alex.test.Tr;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.chat.TownAlertLevel;
import com.alex.test.village.chat.TownAlertPrefs;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What players in a village hear about its raid: one short chat line per
 * moment, each as much as their own Town alerts setting asks for, and the
 * raid horn.
 */
final class RaidMessages {

    static final String MARK_WARNING = "⚠";
    static final String MARK_ATTACK = "⚔";
    static final String MARK_WIN = "✔";
    static final String MARK_DEATH = "☠";
    /** Blocks around the village border that still count as "near the village". */
    static final double NEAR_MARGIN = 32.0;

    private RaidMessages() {}

    /** A line for players who hear important village news (Normal and All). */
    static void important(VillageManager village, ServerLevel level, String mark, ChatFormatting color,
                          Component text, @Nullable BlockPos at) {
        send(village, level, TownAlertLevel::hearsImportant, line(mark, color, text, at));
    }

    /** A line for players who hear everything (All only). */
    static void detail(VillageManager village, ServerLevel level, String mark, ChatFormatting color,
                       Component text, @Nullable BlockPos at) {
        send(village, level, TownAlertLevel::hearsEverything, line(mark, color, text, at));
    }

    /** The end of a raid: everyone but those with alerts Off. */
    static void result(VillageManager village, ServerLevel level, String mark, ChatFormatting color, Component text) {
        send(village, level, TownAlertLevel::hearsDeaths, line(mark, color, text, null));
    }

    /** A line for one player, whatever their Town alerts setting: it answers something they did. */
    static void tell(ServerPlayer player, String mark, ChatFormatting color, Component text) {
        player.sendSystemMessage(line(mark, color, text, null));
    }

    private static void send(VillageManager village, ServerLevel level, Predicate<TownAlertLevel> hears,
                             Component text) {
        for (ServerPlayer player : audience(village, level)) {
            if (hears.test(TownAlertPrefs.of(player))) player.sendSystemMessage(text);
        }
    }

    /**
     * Who hears this raid and sees its bar: everyone near enough to keep it
     * going ({@link #playersNear}), except those standing in another village.
     * Inside the town only, a player just past the border kept the raid going
     * and heard the horn but got no warning, bar or result.
     */
    static List<ServerPlayer> audience(VillageManager village, ServerLevel level) {
        List<ServerPlayer> near = playersNear(village, level);
        if (near.isEmpty()) return near;
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : near) {
            VillageManager town = registry.findVillageAt(level, player.blockPosition());
            if (town == null || town == village) out.add(player);
        }
        return out;
    }

    /** "⚔ The warband is attacking from the north (12 64 -200)". */
    private static Component line(String mark, ChatFormatting color, Component text, @Nullable BlockPos at) {
        MutableComponent message = Component.literal(mark + " ").withStyle(color)
                .append(text.copy().withStyle(ChatFormatting.GRAY));
        if (at != null) {
            message.append(Component.literal(" (" + at.getX() + " " + at.getY() + " " + at.getZ() + ")")
                    .withStyle(ChatFormatting.AQUA));
        }
        return message;
    }

    /**
     * The raid horn, from the camp's side, to every player near the village,
     * as vanilla sounds it: 13 blocks from the player towards the raid, loud
     * enough to carry.
     */
    static void horn(VillageManager village, ServerLevel level, BlockPos from,
                     net.minecraft.sounds.SoundEvent sound, float pitch) {
        net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> holder =
                net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound);
        for (ServerPlayer player : playersNear(village, level)) {
            Vec3 toward = Vec3.atCenterOf(from).subtract(player.position());
            Vec3 dir = toward.lengthSqr() < 1.0e-4 ? Vec3.ZERO : toward.normalize();
            Vec3 at = player.position().add(dir.scale(13.0));
            player.connection.send(new ClientboundSoundPacket(holder, SoundSource.NEUTRAL,
                    at.x, player.getY(), at.z, 64.0F, pitch, level.random.nextLong()));
        }
    }

    /**
     * Where something lies from the village. Chat never glues a bare
     * direction into a sentence: {@link #toward} and {@link #from} give the
     * whole phrase ("to the north", "from the north"), so a translation can
     * use its own preposition and case.
     */
    enum Compass {
        NEARBY, NORTH, NORTH_EAST, EAST, SOUTH_EAST, SOUTH, SOUTH_WEST, WEST, NORTH_WEST,
        /** The village has no centre to measure from. */
        EDGE
    }

    /** Where {@code to} lies from {@code from}. */
    static Compass compass(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz < 1) return Compass.NEARBY;
        // Minecraft: north is -z, east is +x.
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        int sector = (int) Math.floorMod(Math.round(degrees / 45.0), 8);
        return switch (sector) {
            case 0 -> Compass.NORTH;
            case 1 -> Compass.NORTH_EAST;
            case 2 -> Compass.EAST;
            case 3 -> Compass.SOUTH_EAST;
            case 4 -> Compass.SOUTH;
            case 5 -> Compass.SOUTH_WEST;
            case 6 -> Compass.WEST;
            default -> Compass.NORTH_WEST;
        };
    }

    /** "to the north", "nearby", "at the edge of the village": where something is. */
    static MutableComponent toward(Compass compass) {
        return switch (compass) {
            case NEARBY -> Tr.c("chat.dynamicvillages.raid.to.nearby", "nearby");
            case NORTH -> Tr.c("chat.dynamicvillages.raid.to.north", "to the north");
            case NORTH_EAST -> Tr.c("chat.dynamicvillages.raid.to.north_east", "to the north east");
            case EAST -> Tr.c("chat.dynamicvillages.raid.to.east", "to the east");
            case SOUTH_EAST -> Tr.c("chat.dynamicvillages.raid.to.south_east", "to the south east");
            case SOUTH -> Tr.c("chat.dynamicvillages.raid.to.south", "to the south");
            case SOUTH_WEST -> Tr.c("chat.dynamicvillages.raid.to.south_west", "to the south west");
            case WEST -> Tr.c("chat.dynamicvillages.raid.to.west", "to the west");
            case NORTH_WEST -> Tr.c("chat.dynamicvillages.raid.to.north_west", "to the north west");
            case EDGE -> Tr.c("chat.dynamicvillages.raid.to.edge", "at the edge of the village");
        };
    }

    /** "from the north", "from nearby", "from the edge of the village": where something comes from. */
    static MutableComponent from(Compass compass) {
        return switch (compass) {
            case NEARBY -> Tr.c("chat.dynamicvillages.raid.from.nearby", "from nearby");
            case NORTH -> Tr.c("chat.dynamicvillages.raid.from.north", "from the north");
            case NORTH_EAST -> Tr.c("chat.dynamicvillages.raid.from.north_east", "from the north east");
            case EAST -> Tr.c("chat.dynamicvillages.raid.from.east", "from the east");
            case SOUTH_EAST -> Tr.c("chat.dynamicvillages.raid.from.south_east", "from the south east");
            case SOUTH -> Tr.c("chat.dynamicvillages.raid.from.south", "from the south");
            case SOUTH_WEST -> Tr.c("chat.dynamicvillages.raid.from.south_west", "from the south west");
            case WEST -> Tr.c("chat.dynamicvillages.raid.from.west", "from the west");
            case NORTH_WEST -> Tr.c("chat.dynamicvillages.raid.from.north_west", "from the north west");
            case EDGE -> Tr.c("chat.dynamicvillages.raid.from.edge", "from the edge of the village");
        };
    }

    /** Facts side by side, each a whole phrase of its own: "12 raiders beaten · nobody died". */
    static MutableComponent joined(List<? extends Component> facts, String separator) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < facts.size(); i++) {
            if (i > 0) out.append(separator);
            out.append(facts.get(i));
        }
        return out;
    }

    /** Players standing inside this village (not a neighbouring one), in its level. */
    static List<ServerPlayer> playersInsideTown(VillageManager village, ServerLevel level) {
        if (village.getLevel() != level) return List.of();
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        List<ServerPlayer> inside = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            BlockPos pos = player.blockPosition();
            if (!village.contains(pos)) continue;
            VillageManager town = registry.findVillageAt(level, pos);
            if (town == village || (town == null && village == VillageManager.INSTANCE)) inside.add(player);
        }
        return inside;
    }

    /**
     * Players inside the village border or within {@link #NEAR_MARGIN} of it,
     * spectators left out: someone is here, so the raid may go on.
     */
    static List<ServerPlayer> playersNear(VillageManager village, ServerLevel level) {
        if (village.getLevel() != level) return List.of();
        AABB border = village.borderBounds();
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            boolean close = border != null
                    ? border.inflate(NEAR_MARGIN, 0, NEAR_MARGIN).contains(player.getX(), border.minY + 1, player.getZ())
                    : village.contains(player.blockPosition());
            if (close) near.add(player);
        }
        return near;
    }
}
