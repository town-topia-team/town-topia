package com.alex.test.client;

import com.alex.test.village.villagers.builder.blueprint.BlueprintLimits;
import net.minecraft.Util;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Building files dragged onto the Builder's desk from anywhere on the computer. Each one
 * is copied into {@code towntopia/blueprints/dropped/}, which the desk lists under Files,
 * so it is still there next time. A file already in a listed folder is used where it is.
 * Never overwrites: a taken name holding another file gets " (2)", " (3)" and so on; the
 * same file dropped again is found and used. The copying runs off the render thread.
 */
public final class DroppedBlueprintFiles {
    private DroppedBlueprintFiles() {}

    /**
     * What came of one drop: the files the desk lists now (in drop order), the plain reason for
     * each building file left out, and how many dropped things were not building files at all.
     */
    public record Result(List<Path> files, List<String> problems, int notBuildings) {}

    public static CompletableFuture<Result> copyIn(Path gameDir, List<Path> dropped) {
        List<Path> paths = List.copyOf(dropped);
        return CompletableFuture.supplyAsync(() -> {
            List<Path> files = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            int notBuildings = 0;
            for (Path path : paths) {
                if (!BlueprintFiles.hasBlueprintExtension(path) || !Files.isRegularFile(path)) {
                    notBuildings++;
                    continue;
                }
                String name = path.getFileName().toString();
                try {
                    long bytes = Files.size(path);
                    if (bytes > BlueprintLimits.MAX_FILE_BYTES) {
                        problems.add(String.format(Locale.ROOT, "%s is too big: %.1f MB, the limit is 2 MB.", name,
                                bytes / (1024.0 * 1024.0)));
                        continue;
                    }
                    files.add(BlueprintFiles.inListedFolder(gameDir, path) ? path : copy(gameDir, path));
                } catch (IOException | SecurityException e) {
                    problems.add(name + " could not be copied. Check that the game folder can be written to.");
                }
            }
            return new Result(List.copyOf(files), List.copyOf(problems), notBuildings);
        }, Util.backgroundExecutor());
    }

    private static Path copy(Path gameDir, Path source) throws IOException {
        Path folder = BlueprintFiles.droppedFolder(gameDir);
        Files.createDirectories(folder);
        String file = source.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String base = CopiedBlueprintSaver.cleanName(file.substring(0, Math.max(0, dot)));
        String extension = file.substring(dot).toLowerCase(Locale.ROOT);
        for (int n = 1; n < 1000; n++) {
            Path target = folder.resolve(n == 1 ? base + extension : base + " (" + n + ")" + extension);
            if (Files.exists(target)) {
                // Dropped before: the same file is used, not a second copy.
                if (Files.mismatch(target, source) == -1) return target;
                continue;
            }
            try {
                Files.copy(source, target);
                return target;
            } catch (FileAlreadyExistsException taken) {
                // Try the next number.
            }
        }
        throw new IOException("Every name like this one is taken.");
    }
}
