package com.alex.test.village.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Who is on the way to, or standing on, which evening standing spot: beside
 * a host, in a host's view for a wave, by the door, at the tavern. Chairs
 * have {@link com.alex.test.village.VillageChairs} claims; these are the
 * same idea for standing, so two guests coming in together do not pick the
 * same tile and end up inside each other.
 *
 * <p>In memory only. The holder refreshes its claim every tick it still
 * wants the spot; a claim nobody refreshed for {@value #CLAIM_TIMEOUT_TICKS}
 * ticks is free again, so a villager that is interrupted never keeps a spot.
 */
public final class StandClaims {

    private static final int CLAIM_TIMEOUT_TICKS = 40;

    private record Claim(UUID owner, long refreshedAt) {}

    private static final Map<ServerLevel, Map<BlockPos, Claim>> CLAIMS = new WeakHashMap<>();

    private StandClaims() {}

    private static Map<BlockPos, Claim> claims(ServerLevel level) {
        Map<BlockPos, Claim> claims = CLAIMS.computeIfAbsent(level, l -> new HashMap<>());
        // Expired claims are otherwise only overwritten; prune now and then.
        if (claims.size() > 64 && level.getGameTime() % 200 == 0) {
            long now = level.getGameTime();
            claims.values().removeIf(c -> VillagerPause.stale(c.owner(), c.refreshedAt(), CLAIM_TIMEOUT_TICKS, now));
        }
        return claims;
    }

    /** Nobody else holds this spot. */
    public static boolean isFreeFor(ServerLevel level, BlockPos spot, UUID who) {
        Claim claim = claims(level).get(spot);
        return claim == null || claim.owner().equals(who)
                || VillagerPause.stale(claim.owner(), claim.refreshedAt(), CLAIM_TIMEOUT_TICKS, level.getGameTime());
    }

    /** Take or refresh a claim. Call every tick the spot is still wanted. */
    public static void claim(ServerLevel level, BlockPos spot, UUID who) {
        claims(level).put(spot.immutable(), new Claim(who, level.getGameTime()));
    }

    /** Drop {@code who}'s claim on the spot, if it holds one. */
    public static void release(ServerLevel level, BlockPos spot, UUID who) {
        Map<BlockPos, Claim> claims = claims(level);
        Claim claim = claims.get(spot);
        if (claim != null && claim.owner().equals(who)) claims.remove(spot);
    }
}
