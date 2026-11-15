package com.alex.test.village.villagers.rancher;

import com.alex.test.village.brain.VillagerPause;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Which animal each rancher is busy with, and which ones it could not reach.
 *
 * <p>Ranchers pick the first animal in the pen that needs something, so two
 * ranchers sharing a pen used to pick the same one, walk into each other and
 * stand beside it. A claim keeps the others off that animal while one works
 * it. A rancher that gives up on an animal leaves it alone for a minute
 * instead of picking it again straight away.
 *
 * <p>Claims expire on their own unless refreshed, so a rancher that unloads
 * or dies mid-job never locks an animal for good. One paused for a player's
 * screen ({@link VillagerPause}) keeps its claims.
 */
public final class RanchClaims {

    /** A claim lapses this long after its rancher last refreshed it. */
    private static final long CLAIM_TICKS = 200L;
    /** How long a rancher leaves an animal it gave up on. */
    public static final long UNREACHABLE_TICKS = 1200L;

    private record Claim(UUID rancher, long until) {}

    private static final Map<UUID, Claim> CLAIMS = new HashMap<>();
    private static final Map<UUID, Map<UUID, Long>> UNREACHABLE = new HashMap<>();

    private RanchClaims() {}

    /** Claims {@code animal} for {@code rancher}, or refreshes its claim. */
    public static void claim(UUID animal, UUID rancher, long now) {
        CLAIMS.put(animal, new Claim(rancher, now + CLAIM_TICKS));
    }

    /** Lets go of {@code animal} if {@code rancher} holds it. */
    public static void release(UUID animal, UUID rancher) {
        Claim claim = CLAIMS.get(animal);
        if (claim != null && claim.rancher().equals(rancher)) CLAIMS.remove(animal);
    }

    /** {@code rancher} leaves {@code animal} alone for {@link #UNREACHABLE_TICKS}. */
    public static void markUnreachable(UUID rancher, UUID animal, long now) {
        Map<UUID, Long> skipped = UNREACHABLE.computeIfAbsent(rancher, k -> new HashMap<>());
        skipped.values().removeIf(until -> until <= now);
        skipped.put(animal, now + UNREACHABLE_TICKS);
    }

    /** Whether {@code rancher} may pick {@code animal}: nobody else on it, and not given up on lately. */
    public static boolean isFree(UUID animal, UUID rancher, long now) {
        Claim claim = CLAIMS.get(animal);
        if (claim != null) {
            if (VillagerPause.lapsed(claim.rancher(), claim.until(), now)) CLAIMS.remove(animal);
            else if (!claim.rancher().equals(rancher)) return false;
        }
        Map<UUID, Long> skipped = UNREACHABLE.get(rancher);
        if (skipped == null) return true;
        Long until = skipped.get(animal);
        if (until == null) return true;
        if (until > now) return false;
        skipped.remove(animal);
        if (skipped.isEmpty()) UNREACHABLE.remove(rancher);
        return true;
    }

    /** Whether someone other than {@code rancher} is working {@code animal} right now. */
    public static boolean isClaimedByOther(UUID animal, UUID rancher, long now) {
        Claim claim = CLAIMS.get(animal);
        return claim != null && !VillagerPause.lapsed(claim.rancher(), claim.until(), now)
                && !claim.rancher().equals(rancher);
    }

    /** The rancher working {@code animal} right now, or null. For the Ranch window. */
    @Nullable
    public static UUID claimant(UUID animal, long now) {
        Claim claim = CLAIMS.get(animal);
        return claim == null || VillagerPause.lapsed(claim.rancher(), claim.until(), now) ? null : claim.rancher();
    }

    /** Drops lapsed claims; called now and then so the table stays small. */
    public static void prune(long now) {
        for (Iterator<Claim> it = CLAIMS.values().iterator(); it.hasNext(); ) {
            Claim claim = it.next();
            if (VillagerPause.lapsed(claim.rancher(), claim.until(), now)) it.remove();
        }
        UNREACHABLE.values().removeIf(skipped -> {
            skipped.values().removeIf(until -> until <= now);
            return skipped.isEmpty();
        });
    }
}
