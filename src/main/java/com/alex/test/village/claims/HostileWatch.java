package com.alex.test.village.claims;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.chat.VillageDamageAlerts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Village claims round 11a: the players an owned village listed as Hostile, while they are around it. Worked out
 * once a second for every owned village (claims on), so a villager's brain only reads a short list:
 * <ul>
 * <li>villagers within {@link #HIDE_RADIUS} of one run indoors and stay while it is near ({@code VillagerBrain},
 *     {@code FleeActivity});</li>
 * <li>a lookout rings its tower bell while one is inside the village ({@code LookoutWatch});</li>
 * <li>the town hears "Bob (hostile) entered Anna's village" when one comes inside, at most every
 *     {@link #ENTERED_AGAIN_TICKS} per player.</li>
 * </ul>
 * Spectators are left out. Creative players count: they are still not welcome.
 */
public final class HostileWatch {
    private HostileWatch() {}

    /** A villager this close (across) to a Hostile player hides... */
    public static final double HIDE_RADIUS = 16.0;
    /** ...when it is also within this much up or down. */
    private static final double HIDE_HEIGHT = 6.0;
    private static final int CHECK_TICKS = 20;
    /** The "entered" line for one player and village at most this often (2 min): walking the edge says it once. */
    private static final long ENTERED_AGAIN_TICKS = 2400L;

    /** Hostile players round one village: everyone within the land and {@link #HIDE_RADIUS} past it, and who is inside. */
    private record Around(List<ServerPlayer> near, Set<UUID> inside) {}

    private static final Map<UUID, Around> AROUND = new HashMap<>();
    /** Village id + player id -> game time of the last "entered" line. */
    private static final Map<String, Long> LAST_ENTERED = new HashMap<>();

    /** The nearest Hostile player within {@link #HIDE_RADIUS} of this spot, for this village's villagers; else null. */
    @Nullable
    public static ServerPlayer nearestTo(VillageManager village, Vec3 at) {
        Around around = AROUND.get(village.getVillageId());
        if (around == null) return null;
        ServerPlayer best = null;
        double bestSq = HIDE_RADIUS * HIDE_RADIUS;
        for (ServerPlayer player : around.near()) {
            if (player.isRemoved() || !player.isAlive() || Math.abs(player.getY() - at.y) > HIDE_HEIGHT) continue;
            double dx = player.getX() - at.x;
            double dz = player.getZ() - at.z;
            double sq = dx * dx + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = player;
            }
        }
        return best;
    }

    /** Whether a Hostile player is inside this village's border right now (as of the last check). */
    public static boolean anyInside(VillageManager village) {
        Around around = AROUND.get(village.getVillageId());
        return around != null && !around.inside().isEmpty();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % CHECK_TICKS != 0) return;
        VillageManagerRegistry registry = VillageManagerRegistry.get(server);
        if (!registry.claimsEnabled()) {
            AROUND.clear();
            return;
        }
        Map<UUID, Around> fresh = new HashMap<>();
        for (VillageManager village : registry.all()) {
            Around around = look(server, village);
            if (around != null) fresh.put(village.getVillageId(), around);
        }
        for (Map.Entry<UUID, Around> entry : fresh.entrySet()) {
            Around before = AROUND.get(entry.getKey());
            VillageManager village = registry.get(entry.getKey());
            if (village == null) continue;
            for (ServerPlayer player : entry.getValue().near()) {
                UUID id = player.getUUID();
                if (!entry.getValue().inside().contains(id) || before != null && before.inside().contains(id)) continue;
                announceEntered(village, player);
            }
        }
        AROUND.clear();
        AROUND.putAll(fresh);
        if (LAST_ENTERED.size() > 256) {
            long now = server.overworld().getGameTime();
            LAST_ENTERED.values().removeIf(at -> now - at > ENTERED_AGAIN_TICKS);
        }
    }

    /** This village's Hostile players near it; null when it has none (the usual case, no players looked up). */
    @Nullable
    private static Around look(MinecraftServer server, VillageManager village) {
        VillageClaim claim = village.claim();
        if (!claim.hasOwner() || !(village.getLevel() instanceof ServerLevel level)) return null;
        int[] land = village.landChunks();
        if (land == null) return null;
        List<ServerPlayer> near = null;
        Set<UUID> inside = null;
        for (Map.Entry<UUID, VillageClaim.Member> member : claim.members().entrySet()) {
            if (member.getValue().rank() != ClaimRank.HOSTILE) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(member.getKey());
            if (player == null || player.isSpectator() || !player.isAlive() || player.serverLevel() != level) continue;
            int x = player.getBlockX();
            int z = player.getBlockZ();
            int margin = (int) HIDE_RADIUS;
            if ((x + margin) >> 4 < land[0] || (x - margin) >> 4 > land[2]
                    || (z + margin) >> 4 < land[1] || (z - margin) >> 4 > land[3]) continue;
            if (near == null) {
                near = new ArrayList<>();
                inside = new HashSet<>();
            }
            near.add(player);
            if (village.contains(player.blockPosition())) inside.add(player.getUUID());
        }
        return near == null ? null : new Around(near, inside);
    }

    private static void announceEntered(VillageManager village, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        String key = village.getVillageId() + "/" + player.getUUID();
        Long last = LAST_ENTERED.get(key);
        if (last != null && now >= last && now - last < ENTERED_AGAIN_TICKS) return;
        LAST_ENTERED.put(key, now);
        VillageDamageAlerts.announceHostileEntered(village, level, player);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        AROUND.clear();
        LAST_ENTERED.clear();
    }
}
