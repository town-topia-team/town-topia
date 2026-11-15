package com.alex.test.village.villagers.archer.behavior;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which archer is shooting at which target, so two never share a lane: one
 * would stand in the other's spot, and each waits forever for the other to
 * leave its line of fire. A claim lapses unless its archer renews it, so a
 * villager that unloads or dies mid-session frees its target by itself.
 * Mages practising at the range claim lanes here too, so an archer and a mage
 * never share one either.
 */
public final class ArcheryLanes {

    /** A claim not renewed for this long is free again. */
    private static final int HOLD_TICKS = 60;
    private static final int PRUNE_SIZE = 128;

    private record Key(ResourceKey<Level> dimension, BlockPos target) {}

    private record Claim(UUID archer, BlockPos stand, long renewedAt) {}

    private static final Map<Key, Claim> CLAIMS = new HashMap<>();

    private ArcheryLanes() {}

    /** Free, or already this archer's. */
    public static boolean isFree(ServerLevel level, BlockPos target, UUID archer) {
        Claim claim = CLAIMS.get(new Key(level.dimension(), target));
        return claim == null || claim.archer().equals(archer) || lapsed(claim, level.getGameTime());
    }

    /** Takes the target for this archer, standing at {@code stand}; false when another archer holds it. */
    public static boolean claim(ServerLevel level, BlockPos target, BlockPos stand, UUID archer) {
        if (!isFree(level, target, archer)) return false;
        long now = level.getGameTime();
        CLAIMS.put(new Key(level.dimension(), target.immutable()), new Claim(archer, stand.immutable(), now));
        if (CLAIMS.size() > PRUNE_SIZE) CLAIMS.values().removeIf(c -> lapsed(c, now));
        return true;
    }

    /**
     * Keeps this archer's claim alive. False when another archer holds the
     * target now: the claim lapsed while this one was not ticking (its screen
     * open, say) and the target was taken. The caller must give the lane up.
     */
    public static boolean renew(ServerLevel level, BlockPos target, BlockPos stand, UUID archer) {
        return claim(level, target, stand, archer);
    }

    public static void release(ServerLevel level, BlockPos target, UUID archer) {
        Key key = new Key(level.dimension(), target);
        Claim claim = CLAIMS.get(key);
        if (claim != null && claim.archer().equals(archer)) CLAIMS.remove(key);
    }

    /** Whether another archer's live claim stands within a block of {@code pos}. */
    public static boolean standTaken(ServerLevel level, BlockPos pos, UUID archer) {
        long now = level.getGameTime();
        for (Map.Entry<Key, Claim> entry : CLAIMS.entrySet()) {
            Claim claim = entry.getValue();
            if (claim.archer().equals(archer) || lapsed(claim, now)
                    || !entry.getKey().dimension().equals(level.dimension())) continue;
            if (claim.stand().distManhattan(pos) <= 1) return true;
        }
        return false;
    }

    /** Lapsed, or stamped on another world's clock (a later game time than now). */
    private static boolean lapsed(Claim claim, long now) {
        return now - claim.renewedAt() > HOLD_TICKS || now < claim.renewedAt();
    }
}
