package com.alex.test.village;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks which villager has claimed each block position,
 * preventing two villagers from being assigned to the same tree.
 */
public final class ClaimsRegistry {

    /** pos.asLong() → npcId of the claiming villager. */
    private final Map<Long, UUID> claims = new HashMap<>();

    /**
     * Attempt to claim {@code pos} for {@code npcId}.
     *
     * @return {@code true} if the claim was acquired; {@code false} if already
     *         claimed by a different villager.
     */
    public boolean claim(BlockPos pos, UUID npcId) {
        long key = pos.asLong();
        UUID existing = claims.get(key);
        if (existing != null && !existing.equals(npcId)) return false;
        claims.put(key, npcId);
        return true;
    }

    /**
     * True when somebody other than {@code npcId} holds {@code pos}.
     *
     * <p>The read-only half of {@link #claim}, for the cheap "is there work
     * here for me?" checks the brain runs while scoring. Those must not take a
     * claim as a side effect of being asked.
     */
    public boolean isClaimedByOther(BlockPos pos, UUID npcId) {
        UUID existing = claims.get(pos.asLong());
        return existing != null && !existing.equals(npcId);
    }

    /** Who holds {@code pos}, or null when nobody does. */
    public UUID holder(BlockPos pos) {
        return claims.get(pos.asLong());
    }

    /** Release the claim on {@code pos} if currently held by {@code npcId}. */
    public void release(BlockPos pos, UUID npcId) {
        long key = pos.asLong();
        if (npcId.equals(claims.get(key))) claims.remove(key);
    }

    /** Release all claims held by {@code npcId} (task failure / villager death). */
    public void releaseAll(UUID npcId) {
        claims.values().removeIf(id -> id.equals(npcId));
    }

    public void clear() { claims.clear(); }
}
