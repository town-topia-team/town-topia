package com.alex.test.village;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads the world's entity save files, every dimension, looking for villagers
 * by their NpcId: where each one is saved and its name. Read only, and off the
 * server thread so a big world never freezes the game.
 *
 * <p>A villager sitting on a chair is saved inside the seat's "Passengers", so
 * those are searched too. Any file or chunk that cannot be read makes the
 * result not clean: a villager could be in it, so nobody may be called gone.
 */
final class SaveFileBodyScan {

    /** Where a villager's body is saved. */
    record Body(ResourceKey<Level> dimension, BlockPos pos, @Nullable String name) {}

    /** What one search found; {@code problem} names the first thing that could not be read. */
    record Result(Map<UUID, Body> found, boolean clean, @Nullable String problem, int files, long millis) {
        static Result failed(String problem) {
            return new Result(Map.of(), false, problem, 0, 0L);
        }
    }

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = 2 * SECTOR_BYTES;
    /** Flag on a chunk's compression byte: its data is in a separate .mcc file. */
    private static final int EXTERNAL_FLAG = 128;
    private static final java.util.regex.Pattern REGION_NAME =
            java.util.regex.Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TownTopia body search");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private SaveFileBodyScan() {}

    /** Starts a search for {@code wanted}; the folders are looked up here, on the server thread. */
    static CompletableFuture<Result> start(MinecraftServer server, Set<UUID> wanted) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        Map<ResourceKey<Level>, Path> folders = new LinkedHashMap<>();
        for (ResourceKey<Level> dimension : server.levelKeys()) {
            folders.put(dimension, DimensionType.getStorageFolder(dimension, root).resolve("entities"));
        }
        Set<UUID> ids = Set.copyOf(wanted);
        return CompletableFuture.supplyAsync(() -> search(folders, ids), EXECUTOR);
    }

    private static Result search(Map<ResourceKey<Level>, Path> folders, Set<UUID> ids) {
        long start = System.nanoTime();
        Map<UUID, Body> found = new HashMap<>();
        String problem = null;
        int files = 0;
        for (Map.Entry<ResourceKey<Level>, Path> folder : folders.entrySet()) {
            if (!Files.isDirectory(folder.getValue())) continue;
            try (DirectoryStream<Path> regions = Files.newDirectoryStream(folder.getValue(), "r.*.mca")) {
                for (Path region : regions) {
                    files++;
                    String fileProblem = readRegion(folder.getKey(), region, ids, found);
                    if (problem == null) problem = fileProblem;
                }
            } catch (IOException | DirectoryIteratorException e) {
                if (problem == null) problem = folder.getValue() + ": " + e;
            }
        }
        return new Result(found, problem == null, problem, files, (System.nanoTime() - start) / 1_000_000L);
    }

    /** Searches one region file; returns what could not be read, or null when all of it was. */
    @Nullable
    private static String readRegion(ResourceKey<Level> dimension, Path file, Set<UUID> ids, Map<UUID, Body> found) {
        // A file named otherwise ("r.0.0 - Copy.mca") is not one the game reads either.
        java.util.regex.Matcher name = REGION_NAME.matcher(file.getFileName().toString());
        if (!name.matches()) return null;
        int regionX;
        int regionZ;
        try {
            regionX = Integer.parseInt(name.group(1));
            regionZ = Integer.parseInt(name.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            return file + ": " + e;
        }
        if (data.length == 0) return null;
        if (data.length < HEADER_BYTES) return file + ": header cut short";
        String problem = null;
        for (int i = 0; i < 1024; i++) {
            int entry = readInt(data, i * 4);
            if (entry == 0) continue;
            int chunkX = regionX * 32 + (i & 31);
            int chunkZ = regionZ * 32 + (i >> 5);
            try {
                CompoundTag chunk = readChunk(file, data, (long) (entry >>> 8) * SECTOR_BYTES,
                        (entry & 0xFF) * SECTOR_BYTES, chunkX, chunkZ);
                if (chunk != null) search(dimension, chunk.getList("Entities", Tag.TAG_COMPOUND), ids, found);
            } catch (IOException | RuntimeException e) {
                if (problem == null) problem = file + " chunk " + chunkX + "," + chunkZ + ": " + e;
            }
        }
        return problem;
    }

    /** One chunk's saved entities, the way the game's own RegionFile reads them. */
    @Nullable
    private static CompoundTag readChunk(Path file, byte[] data, long offset, int space, int chunkX, int chunkZ)
            throws IOException {
        // Only the sectors the header gives this chunk, as the game reads it.
        if (offset < HEADER_BYTES) throw new IOException("chunk inside the file header");
        // A last chunk may run past an unpadded file's end; the game reads what is there.
        if (offset < data.length) space = (int) Math.min(space, data.length - offset);
        if (space < 5 || offset + 5 > data.length) throw new IOException("chunk cut short");
        int start = (int) offset;
        int length = readInt(data, start);
        int version = data[start + 4] & 0xFF;
        // The game skips a chunk like this; here it counts as unreadable, to be safe.
        if (length == 0) throw new IOException("chunk has no data");
        InputStream raw;
        if ((version & EXTERNAL_FLAG) != 0) {
            version &= ~EXTERNAL_FLAG;
            raw = new ByteArrayInputStream(Files.readAllBytes(
                    file.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc")));
        } else {
            int size = length - 1;
            if (size < 0 || size > space - 5) throw new IOException("chunk cut short");
            raw = new ByteArrayInputStream(data, start + 5, size);
        }
        RegionFileVersion compression = RegionFileVersion.fromId(version);
        if (compression == null) throw new IOException("unknown compression " + version);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(compression.wrap(raw)))) {
            return NbtIo.read(in, NbtAccounter.unlimitedHeap());
        }
    }

    private static void search(ResourceKey<Level> dimension, ListTag entities, Set<UUID> ids, Map<UUID, Body> found) {
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entity = entities.getCompound(i);
            if (entity.hasUUID("NpcId")) {
                UUID id = entity.getUUID("NpcId");
                ListTag pos = entity.getList("Pos", Tag.TAG_DOUBLE);
                if (ids.contains(id) && pos.size() == 3) {
                    String name = entity.getCompound("BrainData").getString("GivenName");
                    found.put(id, new Body(dimension,
                            BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)),
                            name.isEmpty() ? null : name));
                }
            }
            // Riders (a villager on a chair seat) are saved inside what they ride.
            if (entity.contains("Passengers", Tag.TAG_LIST)) {
                search(dimension, entity.getList("Passengers", Tag.TAG_COMPOUND), ids, found);
            }
        }
    }

    private static int readInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }
}
