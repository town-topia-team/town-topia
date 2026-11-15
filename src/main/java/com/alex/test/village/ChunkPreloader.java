package com.alex.test.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * Loads the chunks a full nav build is about to read all at once, instead of
 * one at a time as the build reaches them.
 *
 * <p>The build reads blocks one by one, and on the server thread a read in a
 * chunk that isn't loaded stops everything to load that chunk then and there.
 * At world load nothing around a village is loaded yet, so a 162k-node village
 * loaded its ~360 chunks one after another, each waiting on the disk: most of
 * a 3.35 s build, all of it before the player could spawn. Asking for every
 * chunk first lets the disk reads, decompression and lighting run on their own
 * threads, overlapped, while this thread does the main-thread steps as they come
 * in. That is what vanilla's blocking chunk read does, for many chunks at once.
 *
 * <p>Each chunk gets a full-chunk ticket that lives one tick, like the ticket a
 * blocking read gives it, so the chunks stay loaded no longer than before. The
 * build's own reads still load anything that isn't in by the timeout.
 */
final class ChunkPreloader {

    /** Level 33 (full chunk, no ticking), one tick: the same as vanilla's blocking-read ticket. */
    private static final TicketType<ChunkPos> TICKET = TicketType.create(
            "dynamicvillages_nav_preload", Comparator.comparingLong(ChunkPos::toLong), 1);

    /** Past this, give up waiting; the build loads whatever is missing as it reads. */
    private static final long TIMEOUT_NANOS = 20_000_000_000L;

    /** How long to sleep when there is no main-thread chunk work yet: vanilla's own wait. */
    private static final long IDLE_PARK_NANOS = 100_000L;

    private ChunkPreloader() {}

    /** @param missing chunks still not loaded when the wait gave up (0 normally) */
    record Result(int requested, int missing, long nanos) {
        String describe() {
            if (requested == 0) return "all loaded already";
            return String.format(Locale.ROOT, "%d preloaded in %.1f ms%s", requested, nanos / 1_000_000.0,
                    missing > 0 ? ", " + missing + " still missing (timed out)" : "");
        }
    }

    /**
     * Loads every chunk a full build of this claim reads: the claim, and the
     * one-chunk ring around it that the build's ledge checks look into at the
     * edge. Blocks until they are in (or the timeout).
     */
    static Result preloadClaim(ServerLevel level, BlockPos origin, int chunkRadius) {
        int cx = origin.getX() >> 4;
        int cz = origin.getZ() >> 4;
        int reach = chunkRadius + 1;
        return preload(level, cx - reach, cx + reach, cz - reach, cz + reach);
    }

    static Result preload(ServerLevel level, int minCX, int maxCX, int minCZ, int maxCZ) {
        // getChunkNow and the chunk task queue belong to the server thread.
        if (!level.getServer().isSameThread()) return new Result(0, 0, 0L);
        long began = System.nanoTime();
        ServerChunkCache chunks = level.getChunkSource();
        List<ChunkPos> wanted = new ArrayList<>();
        for (int x = minCX; x <= maxCX; x++) {
            for (int z = minCZ; z <= maxCZ; z++) {
                if (chunks.getChunkNow(x, z) != null) continue;
                ChunkPos pos = new ChunkPos(x, z);
                chunks.addRegionTicket(TICKET, pos, 0, pos);
                wanted.add(pos);
            }
        }
        if (wanted.isEmpty()) return new Result(0, 0, System.nanoTime() - began);

        // Nothing unloads while this waits: the tickets only expire on a server tick.
        long deadline = began + TIMEOUT_NANOS;
        int next = 0;
        while (next < wanted.size() && System.nanoTime() < deadline) {
            // A chunk worker that crashed: fail the way vanilla's wait does.
            MinecraftServer.throwIfFatalException();
            ChunkPos pos = wanted.get(next);
            if (chunks.getChunkNow(pos.x, pos.z) != null) {
                next++;
                continue;
            }
            if (!chunks.pollTask()) LockSupport.parkNanos("waiting for village chunks", IDLE_PARK_NANOS);
        }
        int missing = 0;
        for (int i = next; i < wanted.size(); i++) {
            ChunkPos pos = wanted.get(i);
            if (chunks.getChunkNow(pos.x, pos.z) == null) missing++;
        }
        return new Result(wanted.size(), missing, System.nanoTime() - began);
    }
}
