package com.alex.test.testkit;

import com.alex.test.DynamicVillages;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Samples every villager's state on a fixed tick interval into a JSONL file.
 *
 * <p>A single snapshot answers "what is the villager doing now"; a lot of the
 * interesting questions about this mod are about change over time — did it ever
 * start working, how long did it stay hungry, did it get stuck in one activity.
 * The timeline makes those answerable: one JSON object per sample, one sample
 * per line, so an external harness can scan for transitions without replaying
 * the world.
 *
 * <p>Only one watch runs at a time; starting a new one replaces the old.
 */
public final class BehaviorWatcher {

    private static volatile boolean active;
    private static int intervalTicks = 20;
    private static String fileName = "timeline.jsonl";
    private static int samplesTaken;
    private static int sampleLimit = -1;
    private static long tickCounter;

    private BehaviorWatcher() {}

    // =========================================================================
    // Control
    // =========================================================================

    /**
     * Begins sampling into {@code vtest-out/<name>}, truncating any previous
     * contents so a run never mixes with the one before it.
     *
     * @param limit stop automatically after this many samples, or -1 for open-ended
     */
    public static void start(MinecraftServer server, String name, int interval, int limit) {
        fileName = name;
        intervalTicks = Math.max(1, interval);
        sampleLimit = limit;
        samplesTaken = 0;
        tickCounter = 0;
        try {
            java.nio.file.Files.deleteIfExists(TestKit.outputFile(server, fileName));
        } catch (Exception e) {
            DynamicVillages.LOGGER.warn("[TestKit] Could not reset {}: {}", fileName, e.getMessage());
        }
        active = true;
        DynamicVillages.LOGGER.info("[TestKit] Watch started -> {} every {} tick(s), limit {}.",
                fileName, intervalTicks, limit < 0 ? "none" : limit);
    }

    public static void stop() {
        active = false;
        DynamicVillages.LOGGER.info("[TestKit] Watch stopped after {} sample(s).", samplesTaken);
    }

    public static boolean isActive()  { return active; }
    public static int samples()       { return samplesTaken; }
    public static String currentFile(){ return fileName; }

    // =========================================================================
    // Sampling
    // =========================================================================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!active) return;
        if (++tickCounter % intervalTicks != 0) return;
        sample(event.getServer());
        if (sampleLimit >= 0 && samplesTaken >= sampleLimit) stop();
    }

    /**
     * Writes one line describing the current tick.
     *
     * <p>Kept deliberately narrow — clock plus per-villager activity, position
     * and needs. A full state dump per sample would make the timeline unwieldy
     * for the long runs it exists to support.
     */
    public static void sample(MinecraftServer server) {
        JsonObject line = new JsonObject();
        line.add("server", StateDump.server(server));

        JsonArray villagers = new JsonArray();
        for (VillageManager mgr : VillageManagerRegistry.get(server).all()) {
            for (VillagerModel model : mgr.getVillagers()) {
                JsonObject v = StateDump.villager(model);
                v.remove("personality");
                v.remove("inventory");
                villagers.add(v);
            }
        }
        line.add("villagers", villagers);

        TestKit.appendLine(server, fileName, StateDump.toCompactJson(line));
        samplesTaken++;
    }
}
