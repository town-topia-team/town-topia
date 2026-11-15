package com.alex.test.village;

import com.alex.test.entity.NpcEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.alex.test.village.VillageManagerRegistry.LOGGER;

/**
 * Looks for members the villages lost track of (see VillageManager's "Missing
 * members"), across every village and dimension, without ever going by time.
 *
 * <p>A search looks at the loaded world first, then reads the save files
 * ({@link SaveFileBodyScan}). Found either way: the villager gets that spot
 * and stays. Missing from two clean searches at least five minutes apart, and
 * not loaded either time: it is gone. A search that could not read every file
 * decides nothing.
 */
final class MissingMemberSearch {

    /** Five minutes between searches, so the second one sees the game's latest saves. */
    private static final long SEARCH_GAP_TICKS = 5L * 60L * 20L;
    /**
     * Thirty minutes after searches that could not read every file twice in a
     * row; the first time is likely a file caught mid-save, so five minutes.
     */
    private static final long UNREADABLE_GAP_TICKS = 30L * 60L * 20L;
    /** Clean searches in a row with no body before a villager is gone. */
    private static final int MISSES_TO_FORGET = 2;

    private CompletableFuture<SaveFileBodyScan.Result> running;
    /** The villagers the running search looks for, each with its village. */
    private Map<UUID, UUID> runningFor = Map.of();
    private long nextSearchTime;
    /** Searches in a row that could not read every file. */
    private int unreadableSearches;

    /** Called every overworld tick. */
    void tick(VillageManagerRegistry registry, ServerLevel overworld) {
        long now = overworld.getGameTime();
        if (running != null) {
            if (!running.isDone()) return;
            SaveFileBodyScan.Result result;
            try {
                result = running.join();
            } catch (RuntimeException e) {
                result = SaveFileBodyScan.Result.failed(String.valueOf(e));
            }
            running = null;
            apply(registry, overworld.getServer(), result);
            runningFor = Map.of();
            unreadableSearches = result.clean() ? 0 : unreadableSearches + 1;
            nextSearchTime = now + (unreadableSearches >= 2 ? UNREADABLE_GAP_TICKS : SEARCH_GAP_TICKS);
            return;
        }
        if (now < nextSearchTime) return;

        Map<UUID, UUID> wanted = new LinkedHashMap<>();
        for (VillageManager manager : registry.all()) {
            for (UUID id : manager.membersToLookFor()) wanted.put(id, manager.getVillageId());
        }
        if (wanted.isEmpty()) return;
        LOGGER.info("[Registry] Searching the save files for {} missing villager(s).", wanted.size());
        runningFor = wanted;
        running = SaveFileBodyScan.start(overworld.getServer(), wanted.keySet());
    }

    private void apply(VillageManagerRegistry registry, MinecraftServer server, SaveFileBodyScan.Result result) {
        Map<UUID, NpcEntity> loaded = loadedBodies(server);
        Map<VillageManager, List<VillagerModel>> gone = new LinkedHashMap<>();
        for (Map.Entry<UUID, UUID> entry : runningFor.entrySet()) {
            VillageManager manager = registry.get(entry.getValue());
            VillagerModel model = manager != null ? manager.getModel(entry.getKey()) : null;
            // Taken off already, loaded since, or seen since (loading clears the look):
            // a miss from a search that started before it was seen must not count.
            if (model == null || model.puppet() != null || !model.lookingForBody()) continue;
            String shortId = entry.getKey().toString().substring(0, 8);

            NpcEntity live = loaded.get(entry.getKey());
            if (live != null) {
                manager.noteBodyFound(model, live.level().dimension(), live.blockPosition(), live.givenName());
                LOGGER.info("[Registry] Missing villager {} is loaded at {} in {}; kept.",
                        shortId, live.blockPosition().toShortString(), live.level().dimension().location());
                continue;
            }
            // A saved body in a chunk that is loaded now is an old copy: the loaded world wins.
            SaveFileBodyScan.Body body = result.found().get(entry.getKey());
            if (body != null && !entitiesLoaded(server, body)) {
                manager.noteBodyFound(model, body.dimension(), body.pos(), body.name());
                LOGGER.info("[Registry] Missing villager {} ({}) is saved at {} in {}; kept.",
                        shortId, body.name() != null ? body.name() : "no name",
                        body.pos().toShortString(), body.dimension().location());
                continue;
            }
            if (!result.clean()) continue;
            if (model.addBodyMiss() >= MISSES_TO_FORGET) {
                gone.computeIfAbsent(manager, m -> new ArrayList<>()).add(model);
            } else {
                LOGGER.info("[Registry] Missing villager {} was not found anywhere; searching once more"
                        + " in five minutes before deciding.", shortId);
            }
        }
        if (!result.clean()) {
            LOGGER.warn("[Registry] Missing villager search could not read every save file, so nobody is taken"
                    + " off; trying again in {} minutes. First problem: {}",
                    unreadableSearches + 1 >= 2 ? 30 : 5, result.problem());
        }
        LOGGER.info("[Registry] Missing villager search read {} file(s) in {} ms.", result.files(), result.millis());
        gone.forEach(VillageManager::forgetGone);
    }

    /** Every loaded villager in every dimension, by NpcId. */
    private static Map<UUID, NpcEntity> loadedBodies(MinecraftServer server) {
        Map<UUID, NpcEntity> bodies = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (NpcEntity npc : level.getEntities(EntityTypeTest.forClass(NpcEntity.class), NpcEntity::isAlive)) {
                bodies.put(npc.getNpcId(), npc);
            }
        }
        return bodies;
    }

    private static boolean entitiesLoaded(MinecraftServer server, SaveFileBodyScan.Body body) {
        ServerLevel level = server.getLevel(body.dimension());
        return level != null && level.areEntitiesLoaded(ChunkPos.asLong(body.pos()));
    }
}
