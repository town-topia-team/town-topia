package com.alex.test.village.villagers.builder.project;

import com.alex.test.DynamicVillages;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.alex.test.village.villagers.builder.blueprint.BlueprintReader;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Ordered blueprints read back from {@link BlueprintStore} for building, read off the
 * server thread once and kept while a project uses them. Server thread only.
 */
public final class ProjectBlueprints {
    private ProjectBlueprints() {}

    private static final Map<String, CompletableFuture<Blueprint>> CACHE = new HashMap<>();

    /** The stored blueprint (already turned), or null while it is being read or when it failed. */
    @Nullable
    public static Blueprint get(MinecraftServer server, String fileHash) {
        CompletableFuture<Blueprint> future = CACHE.computeIfAbsent(fileHash, hash -> CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = Files.readAllBytes(BlueprintStore.folder(server).resolve(hash + ".nbt"));
                return BlueprintReader.read(bytes, hash);
            } catch (Exception e) {
                DynamicVillages.LOGGER.warn("[Builder] Could not read ordered blueprint {}: {}", hash, e.toString());
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Util.backgroundExecutor()));
        return future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
    }

    /** The file could not be read (missing or broken). */
    public static boolean failed(String fileHash) {
        CompletableFuture<Blueprint> future = CACHE.get(fileHash);
        return future != null && future.isCompletedExceptionally();
    }

    public static void forget(String fileHash) {
        CACHE.remove(fileHash);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CACHE.clear();
    }
}
