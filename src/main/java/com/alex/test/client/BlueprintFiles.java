package com.alex.test.client;

import com.alex.test.LegacyModId;
import com.alex.test.DynamicVillages;
import com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.alex.test.village.villagers.builder.blueprint.BlueprintException;
import com.alex.test.village.villagers.builder.blueprint.BlueprintLimits;
import com.alex.test.village.villagers.builder.blueprint.BlueprintReader;
import com.alex.test.village.villagers.builder.blueprint.BlueprintRoomGuess;
import net.minecraft.Util;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * The schematic files on this computer that the Builder's desk lists: the usual
 * folders of WorldEdit, Litematica and structure blocks, plus the mod's own.
 *
 * <p>Nothing here touches the disk on the render thread. The folder scan and every
 * file read run on {@link Util#backgroundExecutor()}; the screen calls {@link #poll()}
 * each tick to pick results up, and at most one file is read for its list line at a
 * time, so a folder of hundreds of schematics never stalls a frame or hogs the pool.
 * One instance per open screen: {@link #close()} stops the reading.
 */
public final class BlueprintFiles {
    private static final String[] EXTENSIONS = {".nbt", ".schem", ".schematic", ".litematic"};
    /** Folder levels searched under each root (the root's own files are level 1). */
    private static final int MAX_DEPTH = 3;
    private static final int MAX_FILES = 500;
    /** Files read for their list line ("Kitchen · 9×7"); the rest show only their format. */
    private static final int MAX_SUMMARIES = 300;

    /** Which tab of the desk a file belongs to; THEME files come from {@link BlueprintThemes}. */
    public enum Source { FILES, COPIED, THEME }

    /**
     * One file: where it is, its name without extension, the folder it came from, its size.
     * A theme building shipped in the mod or a resource pack has no file: {@code resource}
     * says where it is, and {@code path} is only a key.
     */
    public record Entry(Path path, String name, String folder, long bytes, Source source,
                        @Nullable net.minecraft.resources.ResourceLocation resource) {
        public Entry(Path path, String name, String folder, long bytes, Source source) {
            this(path, name, folder, bytes, source, null);
        }

        public boolean tooBig() {
            return bytes > BlueprintLimits.MAX_FILE_BYTES;
        }
    }

    /**
     * What a file holds, for its list line. {@code error} is the player-readable reason
     * when it cannot be read; then the sizes are 0.
     */
    public record Summary(@Nullable TokenType guess, int sizeX, int sizeY, int sizeZ, @Nullable String error) {}

    private final Path gameDir;
    @Nullable private CompletableFuture<List<Entry>> scan;
    private List<Entry> entries = List.of();
    private boolean scanned;
    /** A refresh came in while a scan ran: look once more when it ends (a file saved meanwhile). */
    private boolean scanAgain;
    // Render thread only: written by poll, read by the screen.
    private final Map<Path, Summary> summaries = new HashMap<>();
    @Nullable private CompletableFuture<Summary> summarising;
    @Nullable private Entry summarisingEntry;
    private int started;
    private boolean closed;

    public BlueprintFiles(Path gameDir) {
        this.gameDir = gameDir;
    }

    /** Where copied buildings are saved; the Copied tab lists this folder. */
    public static Path copiedFolder(Path gameDir) {
        return gameDir.resolve(DynamicVillages.MODID).resolve("blueprints").resolve("copied");
    }

    /** Where files dragged onto the desk from elsewhere on the computer are kept; listed under Files. */
    public static Path droppedFolder(Path gameDir) {
        return gameDir.resolve(DynamicVillages.MODID).resolve("blueprints").resolve("dropped");
    }

    /** The scan lists this file already: it lies in one of the searched folders, not too deep. */
    static boolean inListedFolder(Path gameDir, Path file) {
        Path path = file.toAbsolutePath().normalize();
        for (Root root : roots(gameDir)) {
            Path base = root.path().toAbsolutePath().normalize();
            if (path.startsWith(base) && base.relativize(path).getNameCount() <= MAX_DEPTH) return true;
        }
        return false;
    }

    /** Starts (again) looking through the folders; the old list stays until the new one is in. */
    public void refresh() {
        if (closed) return;
        if (scan != null) {
            scanAgain = true;
            return;
        }
        scan = CompletableFuture.supplyAsync(() -> scan(gameDir), Util.backgroundExecutor());
    }

    /** Picks up finished work and starts the next file read. Call once per tick. */
    public void poll() {
        if (closed) return;
        if (scan != null && scan.isDone()) {
            entries = scan.isCompletedExceptionally() ? List.of() : scan.join();
            scan = null;
            scanned = true;
            started = 0;
            if (scanAgain) {
                scanAgain = false;
                refresh();
            }
        }
        if (summarising != null && summarising.isDone()) {
            if (!summarising.isCompletedExceptionally() && summarisingEntry != null) {
                summaries.put(summarisingEntry.path(), summarising.join());
            }
            summarising = null;
            summarisingEntry = null;
        }
        if (summarising != null) return;
        for (Entry entry : entries) {
            if (started >= MAX_SUMMARIES) return;
            if (summaries.containsKey(entry.path())) continue;
            if (entry.tooBig()) {
                summaries.put(entry.path(), new Summary(null, 0, 0, 0, "Too big"));
                continue;
            }
            started++;
            summarisingEntry = entry;
            summarising = CompletableFuture.supplyAsync(() -> summarise(entry), Util.backgroundExecutor());
            return;
        }
    }

    public boolean scanned() {
        return scanned;
    }

    /** A look through the folders is running or waiting to run. */
    public boolean scanning() {
        return scan != null || scanAgain;
    }

    /** The files of one tab, sorted by name. */
    public List<Entry> entries(Source source) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.source() == source) out.add(entry);
        }
        return out;
    }

    /** The file's list line, or null while it has not been read yet. */
    @Nullable
    public Summary summary(Entry entry) {
        return summaries.get(entry.path());
    }

    /** False once the cap of files read for their list line is used up and this one has none. */
    public boolean willRead(Entry entry) {
        return summaries.containsKey(entry.path()) || started < MAX_SUMMARIES;
    }

    /** Records what a full read (the selected file) found, so its list line need not read it again. */
    public void putSummary(Entry entry, Summary summary) {
        summaries.put(entry.path(), summary);
    }

    /** Stops reading; results still on their way are dropped. */
    public void close() {
        closed = true;
        if (scan != null) scan.cancel(false);
        if (summarising != null) summarising.cancel(false);
        scan = null;
        summarising = null;
    }

    /**
     * Reads and parses a whole file. Slow (disk, unpacking, the DataFixer for old files):
     * call it off the render thread.
     */
    public static Blueprint read(Entry entry) throws BlueprintException {
        byte[] bytes;
        if (entry.resource() != null) {
            var resource = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(entry.resource());
            if (resource.isEmpty()) throw new BlueprintException("This building is missing from its theme pack.");
            try (java.io.InputStream in = resource.get().open()) {
                bytes = in.readNBytes(BlueprintLimits.MAX_FILE_BYTES + 1);
            } catch (IOException e) {
                throw new BlueprintException("This building can't be opened.", e);
            }
            BlueprintLimits.checkFileSize(bytes.length);
        } else {
            try {
                BlueprintLimits.checkFileSize(Files.size(entry.path()));
                bytes = Files.readAllBytes(entry.path());
            } catch (IOException e) {
                throw new BlueprintException("This file can't be opened. It may have been moved or deleted.", e);
            }
        }
        // A theme building is named as its theme says, not after its file.
        String hint = entry.source() == Source.THEME ? entry.name().replace('/', ' ').replace('\\', ' ') + ".nbt"
                : entry.path().getFileName().toString();
        return BlueprintReader.read(bytes, hint);
    }

    /** Reads a file for its list line; slow, call it off the render thread. */
    public static Summary summarise(Entry entry) {
        try {
            Blueprint blueprint = read(entry);
            return new Summary(BlueprintRoomGuess.guess(blueprint), blueprint.sizeX(), blueprint.sizeY(),
                    blueprint.sizeZ(), null);
        } catch (BlueprintException e) {
            return new Summary(null, 0, 0, 0, e.getMessage());
        } catch (RuntimeException e) {
            return new Summary(null, 0, 0, 0, "This file can't be read.");
        }
    }

    // =========================================================================
    // Folder scan (background thread)
    // =========================================================================

    /** A searched folder and the name the list shows for it. */
    private record Root(Path path, String folder) {}

    private static List<Root> roots(Path gameDir) {
        List<Root> roots = new ArrayList<>();
        roots.add(new Root(gameDir.resolve("schematics"), "schematics"));
        roots.add(new Root(gameDir.resolve("config").resolve("worldedit").resolve("schematics"), "WorldEdit"));
        Path ownFolder = gameDir.resolve(DynamicVillages.MODID).resolve("blueprints");
        roots.add(new Root(ownFolder, "TownTopia"));
        // Files saved before the TownTopia rename stay in <game>/dynamicvillages and still list.
        Path oldFolder = LegacyModId.oldGameFolder(gameDir).resolve("blueprints");
        if (!oldFolder.equals(ownFolder)) roots.add(new Root(oldFolder, "TownTopia (old folder)"));
        return roots;
    }

    private static List<Entry> scan(Path gameDir) {
        List<Entry> found = new ArrayList<>();
        Path copied = copiedFolder(gameDir);
        Path oldCopied = LegacyModId.oldGameFolder(gameDir).resolve("blueprints").resolve("copied");
        for (Root root : roots(gameDir)) scanRoot(found, root.path(), root.folder(), copied, oldCopied);
        found.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(found);
    }

    private static void scanRoot(List<Entry> found, Path root, String folder, Path copied, Path oldCopied) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(BlueprintFiles::hasBlueprintExtension)
                    .limit(MAX_FILES)
                    .forEach(path -> {
                        if (found.size() >= MAX_FILES) return;
                        boolean isCopy = path.startsWith(copied) || path.startsWith(oldCopied);
                        found.add(new Entry(path, baseName(path), isCopy ? "Copied" : folder, sizeOf(path),
                                isCopy ? Source.COPIED : Source.FILES));
                    });
        } catch (IOException | UncheckedIOException | SecurityException e) {
            // A folder that can't be read is skipped; the others still list.
        }
    }

    static boolean hasBlueprintExtension(Path path) {
        return hasBlueprintExtension(path.getFileName().toString());
    }

    static boolean hasBlueprintExtension(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (name.endsWith(extension)) return true;
        }
        return false;
    }

    static String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }
}
