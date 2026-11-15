package com.alex.test.testkit;

import com.alex.test.DynamicVillages;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global switches and shared paths for the automated-testing kit.
 *
 * <p>The kit exists so an external process (see {@code tools/mctest}) can drive a
 * headless dedicated server over RCON: build a village from nothing, spawn
 * villagers, fast-forward time, and read the resulting AI state back as JSON.
 *
 * <h3>Headless mode</h3>
 * The village tick loops normally freeze when no player is online, because a
 * village with nobody watching it should not burn CPU. A test server has no
 * players by design, so headless mode overrides that gate. It is off unless
 * explicitly requested, either at launch:
 * <pre>-Dtowntopia.testkit.headless=true</pre>
 * or at runtime via {@code /vtest headless on}.
 */
public final class TestKit {

    /** System property consulted once at class-init to seed {@link #headless}. */
    public static final String HEADLESS_PROPERTY = "towntopia.testkit.headless";

    /** Directory (relative to the game dir) that every JSON/text dump is written to. */
    private static final String OUTPUT_DIR = "vtest-out";

    private static volatile boolean headless =
            Boolean.getBoolean(HEADLESS_PROPERTY);

    private TestKit() {}

    // =========================================================================
    // Headless mode
    // =========================================================================

    /**
     * True when villages should keep ticking even with zero players online.
     *
     * <p>Callers are the player-count gates in {@code VillageManagerRegistry},
     * {@code VillageManager} and {@code MayorAi}.
     */
    public static boolean headless() {
        return headless;
    }

    public static void setHeadless(boolean value) {
        headless = value;
        DynamicVillages.LOGGER.info("[TestKit] Headless village ticking {}.",
                value ? "ENABLED" : "disabled");
    }

    // =========================================================================
    // Simulated player position (headless dormancy-detection test hook)
    // =========================================================================

    /**
     * A headless test server (see {@link #headless}) has zero real {@code
     * ServerPlayer} entities, ever -- which is exactly why {@code
     * VillageDormancy.processDormancyChecks} unconditionally skips real
     * ACTIVE -&gt; DORMANT auto-detection in headless mode: {@code
     * isPlayerNearVillage} would otherwise always be false and every village
     * would go dormant within a few hundred ticks of any sprint, forever,
     * since no real player could ever walk in to reverse it.
     *
     * <p>That skip means the actual detection logic (the proximity check, the
     * grace-miss streak, the per-village stagger) can never run under
     * automated test. This is the narrow escape hatch: a scenario can plant a
     * fake position here via {@code /vtest village simulate_player}, and
     * {@code VillageManagerRegistry} both (a) treats it as a real player
     * position for {@code isPlayerNearVillage}, and (b) stops skipping the
     * miss-streak accounting while one is set -- so the real transition logic
     * genuinely executes, driven by a fake position instead of a fake
     * player-count override. Scenarios that never call the command are
     * completely unaffected: {@link #hasSimulatedPlayer()} stays false and
     * the original headless skip behaves exactly as before.
     */
    private static volatile BlockPos simulatedPlayerPos = null;
    private static volatile ResourceKey<Level> simulatedPlayerDim = null;

    public static boolean hasSimulatedPlayer() {
        return simulatedPlayerPos != null;
    }

    public static BlockPos simulatedPlayerPos() {
        return simulatedPlayerPos;
    }

    public static ResourceKey<Level> simulatedPlayerDim() {
        return simulatedPlayerDim;
    }

    public static void setSimulatedPlayer(BlockPos pos, ResourceKey<Level> dimension) {
        simulatedPlayerPos = pos;
        simulatedPlayerDim = dimension;
        DynamicVillages.LOGGER.info("[TestKit] Simulated player set at {} in {}.", pos, dimension.location());
    }

    public static void clearSimulatedPlayer() {
        simulatedPlayerPos = null;
        simulatedPlayerDim = null;
        DynamicVillages.LOGGER.info("[TestKit] Simulated player cleared.");
    }

    // =========================================================================
    // Forced reconciliation failure (fault-injection test hook)
    // =========================================================================

    /**
     * One-shot fault injection for the away calculator's first step ({@code AwayRun} READ): a
     * scenario cannot otherwise corrupt village/storage state in a way that
     * reliably throws mid-reconciliation, so this lets a test arm a village
     * id to have its very next catch-up throw on its first step instead,
     * exercising {@code VillageDormancy.processDormancyChecks}'s
     * catch-and-recover path (RECONCILING -&gt; ACTIVE with a failed-summary
     * marker, other villages unaffected that same tick) on demand. Armed via
     * {@code /vtest village force_throw <villageId>}; consumed (removed)
     * the moment it fires so a village is never permanently stuck failing.
     */
    // =========================================================================
    // Extra random-tick regions
    // =========================================================================

    /**
     * Boxes the headless random ticker services on top of the farm plots.
     *
     * <p>Vanilla only random-ticks chunks near a player, and
     * {@link HeadlessRandomTicker} stands in for that by ticking the tiles a
     * farm owns. Anything growing outside a registered farm - a wild patch, a
     * crop a scenario planted before any token was hung - therefore never grew
     * at all, which made a farmer that works crops without a building
     * impossible to test. A scenario declares the patch it planted and the
     * ticker services it too.
     *
     * <p>Volume is capped per box: this runs every tick, per block.
     */
    private static final List<int[]> randomTickBoxes = new CopyOnWriteArrayList<>();

    /** Blocks in one declared box, past which the box is rejected. */
    public static final int MAX_TICK_BOX_VOLUME = 32 * 32 * 32;

    /**
     * @return the box's volume, or {@code -1} if it was too big to accept
     */
    public static int addRandomTickBox(int minX, int minY, int minZ,
                                       int maxX, int maxY, int maxZ) {
        int lox = Math.min(minX, maxX), hix = Math.max(minX, maxX);
        int loy = Math.min(minY, maxY), hiy = Math.max(minY, maxY);
        int loz = Math.min(minZ, maxZ), hiz = Math.max(minZ, maxZ);
        long volume = (long) (hix - lox + 1) * (hiy - loy + 1) * (hiz - loz + 1);
        if (volume > MAX_TICK_BOX_VOLUME) return -1;
        randomTickBoxes.add(new int[] {lox, loy, loz, hix, hiy, hiz});
        return (int) volume;
    }

    public static List<int[]> randomTickBoxes() {
        return randomTickBoxes;
    }

    public static void clearRandomTickBoxes() {
        randomTickBoxes.clear();
    }

    private static final Set<UUID> forcedReconciliationThrow =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void armForcedReconciliationThrow(UUID villageId) {
        forcedReconciliationThrow.add(villageId);
        DynamicVillages.LOGGER.info("[TestKit] Armed forced reconciliation failure for village {}.", villageId);
    }

    /** Returns true (and disarms) exactly once per {@link #armForcedReconciliationThrow} call. */
    public static boolean consumeForcedReconciliationThrow(UUID villageId) {
        return forcedReconciliationThrow.remove(villageId);
    }

    // =========================================================================
    // Output files
    // =========================================================================

    /**
     * Resolves {@code vtest-out/<name>} inside the server's game directory,
     * creating the directory if needed.
     *
     * <p>Dumps go to files rather than straight back over RCON because RCON
     * truncates long responses; the command returns only the path and size.
     */
    public static Path outputFile(MinecraftServer server, String name) {
        Path dir = server.getServerDirectory().resolve(OUTPUT_DIR);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            DynamicVillages.LOGGER.warn("[TestKit] Could not create {}: {}", dir, e.getMessage());
        }
        return dir.resolve(name);
    }

    /**
     * Writes {@code content} to {@code vtest-out/<name>} and returns a one-line
     * summary suitable for an RCON reply, or an error string on failure.
     */
    public static String write(MinecraftServer server, String name, String content) {
        Path file = outputFile(server, name);
        try {
            Files.writeString(file, content);
            return "OK " + OUTPUT_DIR + "/" + name + " " + content.length();
        } catch (Exception e) {
            DynamicVillages.LOGGER.warn("[TestKit] Write to {} failed: {}", file, e.getMessage());
            return "ERR write " + name + ": " + e.getMessage();
        }
    }

    /** Appends one line to {@code vtest-out/<name>} (used for JSONL timelines). */
    public static void appendLine(MinecraftServer server, String name, String line) {
        Path file = outputFile(server, name);
        try {
            Files.writeString(file, line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            DynamicVillages.LOGGER.warn("[TestKit] Append to {} failed: {}", file, e.getMessage());
        }
    }
}
