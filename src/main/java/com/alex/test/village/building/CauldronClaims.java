package com.alex.test.village.building;

import com.alex.test.village.brain.VillagerPause;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Who is using which cauldron: one farmer washing, or one farmer refilling,
 * never two at once. The same idea as the evening stand claims, for cauldrons.
 * Also the wash line: every farmer that means to wash in a cauldron, so no more
 * farmers head for one than it has water for.
 *
 * <p>In memory only. The holder refreshes its claim every tick it still wants
 * the cauldron; a claim nobody refreshed for {@value #CLAIM_TIMEOUT_TICKS}
 * ticks is free again, so an interrupted farmer never keeps one.
 */
public final class CauldronClaims {

    public enum Kind { WASH, REFILL }

    public record Claim(UUID owner, Kind kind, long refreshedAt) {}

    private static final int CLAIM_TIMEOUT_TICKS = 40;

    private static final Map<ServerLevel, Map<BlockPos, Claim>> CLAIMS = new WeakHashMap<>();

    private CauldronClaims() {}

    private static Map<BlockPos, Claim> claims(ServerLevel level) {
        Map<BlockPos, Claim> claims = CLAIMS.computeIfAbsent(level, l -> new HashMap<>());
        // Expired claims are otherwise only overwritten; prune now and then.
        if (claims.size() > 32 && level.getGameTime() % 200 == 0) {
            long now = level.getGameTime();
            claims.values().removeIf(c -> VillagerPause.stale(c.owner(), c.refreshedAt(), CLAIM_TIMEOUT_TICKS, now));
        }
        return claims;
    }

    /** The live claim on this cauldron, or null when nobody holds it. */
    @Nullable
    public static Claim holder(ServerLevel level, BlockPos cauldron) {
        Claim claim = claims(level).get(cauldron);
        if (claim == null || VillagerPause.stale(claim.owner(), claim.refreshedAt(), CLAIM_TIMEOUT_TICKS, level.getGameTime())) return null;
        return claim;
    }

    /** Nobody else holds this cauldron. */
    public static boolean isFreeFor(ServerLevel level, BlockPos cauldron, UUID who) {
        Claim claim = holder(level, cauldron);
        return claim == null || claim.owner().equals(who);
    }

    /**
     * Take or refresh the claim when nobody else holds it. Call every tick the
     * cauldron is still wanted.
     *
     * @return whether {@code who} holds it now
     */
    public static boolean tryClaim(ServerLevel level, BlockPos cauldron, UUID who, Kind kind) {
        if (!isFreeFor(level, cauldron, who)) return false;
        claims(level).put(cauldron.immutable(), new Claim(who, kind, level.getGameTime()));
        return true;
    }

    /** Drop {@code who}'s claim on the cauldron, if it holds one. */
    public static void release(ServerLevel level, BlockPos cauldron, UUID who) {
        Map<BlockPos, Claim> claims = claims(level);
        Claim claim = claims.get(cauldron);
        if (claim != null && claim.owner().equals(who)) claims.remove(cauldron);
    }

    // =========================================================================
    // The wash line
    // =========================================================================

    /**
     * One farmer in a cauldron's wash line: when it joined, when it last said
     * it still wants a turn, and whether it is refilling the cauldron first.
     */
    private record InLine(long joinedAt, long refreshedAt, boolean refilling) {}

    /**
     * Every farmer heading for, waiting at or washing in each cauldron, in the
     * order they joined. Refreshed every tick like the claims; a farmer that
     * stopped refreshing for {@value #CLAIM_TIMEOUT_TICKS} ticks has left.
     */
    private static final Map<ServerLevel, Map<BlockPos, Map<UUID, InLine>>> LINES = new WeakHashMap<>();

    private static Map<UUID, InLine> line(ServerLevel level, BlockPos cauldron) {
        Map<BlockPos, Map<UUID, InLine>> lines = LINES.computeIfAbsent(level, l -> new HashMap<>());
        Map<UUID, InLine> line = lines.computeIfAbsent(cauldron.immutable(), c -> new HashMap<>());
        long now = level.getGameTime();
        line.entrySet().removeIf(e -> VillagerPause.stale(e.getKey(), e.getValue().refreshedAt(), CLAIM_TIMEOUT_TICKS, now));
        return line;
    }

    /** Join the cauldron's line, or stay in it. Call every tick the turn is still wanted. */
    public static void joinLine(ServerLevel level, BlockPos cauldron, UUID who, boolean refilling) {
        Map<UUID, InLine> line = line(level, cauldron);
        InLine was = line.get(who);
        long now = level.getGameTime();
        line.put(who, new InLine(was == null ? now : was.joinedAt(), now, refilling));
    }

    /** Leave the cauldron's line. */
    public static void leaveLine(ServerLevel level, BlockPos cauldron, UUID who) {
        line(level, cauldron).remove(who);
    }

    /**
     * How many farmers are ahead of {@code who} in the cauldron's line. One not
     * in the line yet counts everyone already in it.
     */
    public static int ahead(ServerLevel level, BlockPos cauldron, UUID who) {
        Map<UUID, InLine> line = line(level, cauldron);
        InLine mine = line.get(who);
        int ahead = 0;
        for (Map.Entry<UUID, InLine> e : line.entrySet()) {
            if (e.getKey().equals(who)) continue;
            if (mine == null || e.getValue().joinedAt() < mine.joinedAt()
                    || (e.getValue().joinedAt() == mine.joinedAt() && e.getKey().compareTo(who) < 0)) ahead++;
        }
        return ahead;
    }

    /** Someone in the line is refilling the empty cauldron before washing. */
    public static boolean refillingInLine(ServerLevel level, BlockPos cauldron) {
        for (InLine e : line(level, cauldron).values()) {
            if (e.refilling()) return true;
        }
        return false;
    }
}
