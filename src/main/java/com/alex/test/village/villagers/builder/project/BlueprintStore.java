package com.alex.test.village.villagers.builder.project;

import com.alex.test.DynamicVillages;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/**
 * Ordered blueprints, saved with the world in {@code dynamicvillages/blueprints/<sha1>.nbt}
 * (gzipped structure NBT, already turned and mirrored). The same file ordered twice is
 * stored once. Written off the server thread.
 */
public final class BlueprintStore {
    private BlueprintStore() {}

    public static Path folder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("dynamicvillages").resolve("blueprints");
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes the file if it is not there yet; completes with its hash. */
    public static CompletableFuture<String> save(MinecraftServer server, byte[] bytes) {
        String hash = hash(bytes);
        Path file = folder(server).resolve(hash + ".nbt");
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Path temp = file.resolveSibling(hash + ".tmp");
                    Files.write(temp, bytes);
                    Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return hash;
            } catch (IOException e) {
                DynamicVillages.LOGGER.warn("[Builder] Could not save an ordered blueprint: {}", e.toString());
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool());
    }
}
