package com.alex.test.village.farawake;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Counts the times a village tick made the server thread wait for a chunk to load (a block
 * read in a chunk nobody held: Minecraft loads it on the spot and the tick waits), and names
 * the mod code that asked. Fed by {@code ServerChunkCacheStallMixin} around the wait in
 * {@code ServerChunkCache.getChunk}; only while a village tick runs ({@link #enter}).
 *
 * <p>Far villagers kept awake read blocks where no player is, so this is the number that
 * says whether their holds cover what they read. Server thread only.
 */
public final class ChunkStallWatch {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A single wait this long gets its own log line. */
    private static final long LOG_ONE_NANOS = 50_000_000L;
    /**
     * Trap: every block read past the 4 chunk cache goes through the same wait call, and for a
     * chunk already loaded it returns at once (well under 1 µs). Shorter than this = no wait.
     */
    private static final long REAL_WAIT_NANOS = 100_000L;

    private static UUID current;
    /** Nested waits (the wait runs other tasks, which may read chunks too): only the outer one counts. */
    private static int depth;
    private static long waitStart;

    private static final Map<UUID, Tally> TALLIES = new HashMap<>();

    private static final class Tally {
        int stalls;
        long totalNanos;
        long worstNanos;
        String worstWho = "?";
    }

    private ChunkStallWatch() {}

    /** A village tick starts. */
    public static void enter(UUID village) {
        current = village;
        depth = 0;
    }

    /** It ended (also when it threw). */
    public static void exit() {
        current = null;
        depth = 0;
    }

    /** The server thread may wait for a chunk (often it does not: see {@link #REAL_WAIT_NANOS}). */
    public static void beforeWait() {
        if (current == null) return;
        if (depth++ == 0) waitStart = System.nanoTime();
    }

    /** The wait is over; still inside the chunk read, so the stack still names who asked. */
    public static void afterWait() {
        if (current == null || depth == 0 || --depth > 0) return;
        long took = System.nanoTime() - waitStart;
        if (took < REAL_WAIT_NANOS) return;
        String waitWho = firstModFrame();
        Tally tally = TALLIES.computeIfAbsent(current, ignored -> new Tally());
        tally.stalls++;
        tally.totalNanos += took;
        if (took > tally.worstNanos) {
            tally.worstNanos = took;
            tally.worstWho = waitWho;
        }
        if (took >= LOG_ONE_NANOS) {
            LOGGER.info("[ChunkStall] village {} waited {} ms for a chunk to load, asked by {}",
                    current.toString().substring(0, 8), took / 1_000_000L, waitWho);
        }
    }

    /** "stalls N (...)" for the minute line; {@code take} starts the count again. */
    static String describe(UUID village, boolean take) {
        Tally tally = take ? TALLIES.remove(village) : TALLIES.get(village);
        if (tally == null) return "stalls 0";
        return String.format(Locale.ROOT, "stalls %d (%.0f ms in all, worst %.0f ms at %s)",
                tally.stalls, tally.totalNanos / 1e6, tally.worstNanos / 1e6, tally.worstWho);
    }

    static void clear() {
        TALLIES.clear();
        current = null;
        depth = 0;
    }

    /** The innermost mod method on the stack (not this class, not a mixin): who read the chunk. */
    private static String firstModFrame() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> f.getClassName().startsWith("com.alex.test.")
                        && !f.getClassName().startsWith("com.alex.test.mixin.")
                        && !f.getClassName().equals(ChunkStallWatch.class.getName()))
                .findFirst()
                .map(f -> {
                    String cls = f.getClassName();
                    return cls.substring(cls.lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber();
                })
                .orElse("?"));
    }
}
