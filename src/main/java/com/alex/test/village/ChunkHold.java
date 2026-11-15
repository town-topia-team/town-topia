package com.alex.test.village;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps a set of chunks loaded in the background while a village is set up or
 * woken a slice per tick, then lets them go a few at a time.
 *
 * <p>Unlike {@link ChunkPreloader}, nothing here waits: the chunks are asked
 * for, the game loads (or generates) them on its own threads while it keeps
 * running, and the caller checks each tick whether they are in. Each chunk is
 * held at full-chunk level with nothing ticking in it, so a held chunk costs
 * memory, not tick time.
 *
 * <p>Letting hundreds of chunks go in one tick has them all saved and unloaded
 * together, a hiccup of its own; {@link #releaseSome} spreads that out.
 */
public final class ChunkHold {

    /** Level 33 (full chunk, no ticking) until removed by hand. */
    private static final TicketType<ChunkPos> TICKET = TicketType.create(
            "dynamicvillages_wake_up", Comparator.comparingLong(ChunkPos::toLong));

    private final Set<ChunkPos> held = new LinkedHashSet<>();
    private List<ChunkPos> waitingFor = List.of();
    private long waitStartedAt;

    /** Every chunk from (minX, minZ) to (maxX, maxZ), widened by {@code ring} on each side. */
    public static List<ChunkPos> square(int minX, int minZ, int maxX, int maxZ, int ring) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = minX - ring; x <= maxX + ring; x++) {
            for (int z = minZ - ring; z <= maxZ + ring; z++) chunks.add(new ChunkPos(x, z));
        }
        return chunks;
    }

    /** Holds the chunks loaded, and makes them the ones {@link #loaded} waits for. */
    public void hold(ServerLevel level, List<ChunkPos> chunks) {
        for (ChunkPos pos : chunks) {
            if (held.add(pos)) level.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
        }
        waitingFor = List.copyOf(chunks);
        waitStartedAt = level.getGameTime();
    }

    /** True once every chunk waited for is loaded, or {@code timeoutTicks} have passed. */
    public boolean loaded(ServerLevel level, long timeoutTicks) {
        if (level.getGameTime() - waitStartedAt >= timeoutTicks) return true;
        return missing(level) == 0;
    }

    /** How many of the chunks waited for are not loaded yet. */
    public int missing(ServerLevel level) {
        int missing = 0;
        for (ChunkPos pos : waitingFor) {
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null) missing++;
        }
        return missing;
    }

    /** Ticks since the current wait began. */
    public long waited(ServerLevel level) {
        return level.getGameTime() - waitStartedAt;
    }

    /**
     * Lets go of up to {@code max} chunks.
     *
     * @return true when none are held any more
     */
    public boolean releaseSome(ServerLevel level, int max) {
        Iterator<ChunkPos> it = held.iterator();
        for (int i = 0; i < max && it.hasNext(); i++) {
            ChunkPos pos = it.next();
            level.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
            it.remove();
        }
        if (held.isEmpty()) waitingFor = List.of();
        return held.isEmpty();
    }

    /** Lets go of every chunk at once; for a world closing, where nothing is left to spread out. */
    public void releaseAll(ServerLevel level) {
        releaseSome(level, Integer.MAX_VALUE);
    }

    public boolean isEmpty() {
        return held.isEmpty();
    }

    public int size() {
        return held.size();
    }
}
