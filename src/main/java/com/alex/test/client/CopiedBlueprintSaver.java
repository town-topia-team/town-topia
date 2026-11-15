package com.alex.test.client;

import net.minecraft.Util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Saves a copied building on the player's computer, in
 * {@code towntopia/blueprints/copied/<name>.nbt}, where the desk's Copied tab
 * lists it. Never overwrites: a taken name gets " (2)", " (3)" and so on. The write
 * runs off the render thread.
 */
public final class CopiedBlueprintSaver {
    private CopiedBlueprintSaver() {}

    private static final int MAX_NAME = 48;
    private static final String FALLBACK = "Copied building";
    /** Names Windows will not use for a file, whatever the extension. */
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4",
            "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8",
            "lpt9");

    /** A file name from what the player typed: letters, digits, spaces and - _ ( ) ' only. */
    public static String cleanName(String typed) {
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < typed.length() && out.length() < MAX_NAME; i++) {
            char c = typed.charAt(i);
            if (Character.isLetterOrDigit(c) || "-_()'".indexOf(c) >= 0) {
                if (space && !out.isEmpty()) out.append(' ');
                out.append(c);
                space = false;
            } else {
                space = true;
            }
        }
        String name = out.toString().trim();
        if (name.isEmpty()) return FALLBACK;
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) return name + " building";
        return name;
    }

    /** Writes the file; the future gives where it went, or fails with the reason. */
    public static CompletableFuture<Path> save(Path gameDir, String name, byte[] bytes) {
        String clean = cleanName(name);
        return CompletableFuture.supplyAsync(() -> {
            Path folder = BlueprintFiles.copiedFolder(gameDir);
            try {
                Files.createDirectories(folder);
                for (int n = 1; n < 1000; n++) {
                    Path file = folder.resolve(n == 1 ? clean + ".nbt" : clean + " (" + n + ").nbt");
                    try {
                        Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                        return file;
                    } catch (FileAlreadyExistsException taken) {
                        // Try the next number.
                    }
                }
                throw new IOException("Every name like this one is taken.");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.backgroundExecutor());
    }
}
