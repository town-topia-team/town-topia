package com.alex.test.client;

import com.alex.test.LegacyModId;
import com.alex.test.DynamicVillages;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Theme packs for the Builder's desk Themes tab: ready-made buildings with credits.
 *
 * <p>Two places hold them, with the same layout (a folder per theme, an optional
 * {@code theme.json}, and the building files):
 * <ul>
 *   <li>shipped in the mod or a resource pack: {@code assets/<namespace>/blueprints/<theme>/};
 *       these need a {@code theme.json};</li>
 *   <li>the test folder {@code <game>/towntopia/themes/<theme>/}, read every time the desk
 *       opens, so a theme can be tried without building the mod. A folder there without a
 *       {@code theme.json} still shows, named after the folder, with every file in it.</li>
 * </ul>
 * {@code theme.json}: {@code name}, {@code author}, {@code description}, {@code icon} (item id) and
 * {@code buildings}: a list of {@code {file, name, author, description}}; only {@code file} is
 * needed. Without {@code buildings} every building file in the folder is listed.
 *
 * <p>Read off the render thread like {@link BlueprintFiles}; the screen calls {@link #poll()}.
 */
public final class BlueprintThemes {
    private static final String ROOT = "blueprints";
    private static final int MAX_THEMES = 100, MAX_BUILDINGS = 200;

    /** One ready-made building: its file, the name players see, who made it, a line about it. */
    public record Building(BlueprintFiles.Entry entry, String name, String author, String description) {}

    /**
     * One theme pack. {@code where} is "Built in" or "Test folder"; {@code problem} is a
     * player-readable note when part of it could not be read (the rest still shows).
     */
    public record Theme(String id, String name, String author, String description, Item icon,
                        List<Building> buildings, String where, @Nullable String problem) {}

    private final Path gameDir;
    @Nullable private CompletableFuture<List<Theme>> loading;
    private List<Theme> themes = List.of();
    private boolean loaded, closed;

    public BlueprintThemes(Path gameDir) {
        this.gameDir = gameDir;
    }

    /** Where players and pack makers put themes to try them: {@code <game>/towntopia/themes}. */
    public static Path testFolder(Path gameDir) {
        return gameDir.resolve(DynamicVillages.MODID).resolve("themes");
    }

    public void refresh() {
        if (closed || loading != null) return;
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        loading = CompletableFuture.supplyAsync(() -> load(gameDir, resources), Util.backgroundExecutor());
    }

    public void poll() {
        if (loading == null || !loading.isDone()) return;
        themes = loading.isCompletedExceptionally() || loading.isCancelled() ? List.of() : loading.join();
        loading = null;
        loaded = true;
    }

    public boolean loaded() {
        return loaded;
    }

    public List<Theme> themes() {
        return themes;
    }

    public void close() {
        closed = true;
        if (loading != null) loading.cancel(false);
        loading = null;
    }

    // =========================================================================
    // Reading (background thread)
    // =========================================================================

    private static List<Theme> load(Path gameDir, ResourceManager resources) {
        List<Theme> out = new ArrayList<>();
        try {
            loadShipped(resources, out);
        } catch (RuntimeException e) {
            // A broken resource pack must not hide the test folder.
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        List<Theme> test = new ArrayList<>();
        loadTestFolder(testFolder(gameDir), test);
        // Themes put there before the TownTopia rename (<game>/dynamicvillages/themes) still load.
        Path oldThemes = LegacyModId.oldGameFolder(gameDir).resolve("themes");
        if (!oldThemes.equals(testFolder(gameDir))) loadTestFolder(oldThemes, test);
        test.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        out.addAll(test);
        return List.copyOf(out.size() > MAX_THEMES ? out.subList(0, MAX_THEMES) : out);
    }

    private static void loadShipped(ResourceManager resources, List<Theme> out) {
        Map<ResourceLocation, Resource> found = resources.listResources(ROOT,
                location -> location.getPath().endsWith("/theme.json"));
        for (Map.Entry<ResourceLocation, Resource> each : found.entrySet()) {
            ResourceLocation json = each.getKey();
            String folder = json.getPath().substring(0, json.getPath().lastIndexOf('/'));
            String folderName = folder.substring(folder.lastIndexOf('/') + 1);
            JsonObject root;
            String problem = null;
            try (Reader reader = each.getValue().openAsReader()) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                root = new JsonObject();
                problem = "Its theme.json can't be read.";
            }
            // Every building file in the folder, by file name, for the list below.
            Map<String, ResourceLocation> files = new LinkedHashMap<>();
            resources.listResources(folder, location -> BlueprintFiles.hasBlueprintExtension(fileName(location.getPath())))
                    .keySet().stream().sorted()
                    .forEach(location -> files.put(fileName(location.getPath()), location));
            Header header = header(root, folderName);
            List<Building> buildings = new ArrayList<>();
            int missing = 0;
            for (Wanted wanted : wanted(root, files.keySet())) {
                ResourceLocation location = files.get(wanted.file());
                if (location == null) {
                    missing++;
                    continue;
                }
                if (buildings.size() >= MAX_BUILDINGS) break;
                BlueprintFiles.Entry entry = new BlueprintFiles.Entry(
                        Path.of("theme", json.getNamespace(), folderName, wanted.file()), wanted.name(), header.name(),
                        0, BlueprintFiles.Source.THEME, location);
                buildings.add(new Building(entry, wanted.name(), wanted.author(header), wanted.description()));
            }
            if (problem == null && missing > 0) problem = missing + " building file(s) in its theme.json are missing.";
            out.add(new Theme(json.getNamespace() + ":" + folder, header.name(), header.author(), header.description(),
                    header.icon(), List.copyOf(buildings), "Built in", problem));
            if (out.size() >= MAX_THEMES) return;
        }
    }

    private static void loadTestFolder(Path root, List<Theme> out) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> folders = Files.list(root)) {
            for (Path folder : folders.filter(Files::isDirectory).sorted().toList()) {
                if (out.size() >= MAX_THEMES) return;
                out.add(readTestTheme(folder));
            }
        } catch (IOException | UncheckedIOException | SecurityException e) {
            // An unreadable folder shows nothing; the shipped themes still do.
        }
    }

    private static Theme readTestTheme(Path folder) {
        String folderName = folder.getFileName().toString();
        JsonObject root = new JsonObject();
        String problem = null;
        Path json = folder.resolve("theme.json");
        if (Files.isRegularFile(json)) {
            try (Reader reader = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                problem = "Its theme.json can't be read. Check the commas and quotes.";
            }
        }
        Map<String, Path> files = new LinkedHashMap<>();
        try (Stream<Path> list = Files.list(folder)) {
            list.filter(Files::isRegularFile).filter(BlueprintFiles::hasBlueprintExtension).sorted()
                    .forEach(path -> files.put(path.getFileName().toString(), path));
        } catch (IOException | UncheckedIOException | SecurityException e) {
            problem = "This folder can't be read.";
        }
        Header header = header(root, folderName);
        List<Building> buildings = new ArrayList<>();
        int missing = 0;
        for (Wanted wanted : wanted(root, files.keySet())) {
            Path path = files.get(wanted.file());
            if (path == null) {
                missing++;
                continue;
            }
            if (buildings.size() >= MAX_BUILDINGS) break;
            BlueprintFiles.Entry entry = new BlueprintFiles.Entry(path, wanted.name(), header.name(),
                    BlueprintFiles.sizeOf(path), BlueprintFiles.Source.THEME);
            buildings.add(new Building(entry, wanted.name(), wanted.author(header), wanted.description()));
        }
        if (problem == null && missing > 0) problem = missing + " building file(s) in its theme.json are missing.";
        if (problem == null && buildings.isEmpty()) problem = "No building files in this folder yet.";
        return new Theme("test:" + folderName, header.name(), header.author(), header.description(), header.icon(),
                List.copyOf(buildings), "Test folder", problem);
    }

    // =========================================================================
    // theme.json
    // =========================================================================

    private record Header(String name, String author, String description, Item icon) {}

    private record Wanted(String file, String name, String author, String description) {
        String author(Header header) {
            return author.isEmpty() ? header.author() : author;
        }
    }

    private static Header header(JsonObject root, String folderName) {
        Item icon = Items.BRICKS;
        ResourceLocation iconId = ResourceLocation.tryParse(string(root, "icon", ""));
        if (iconId != null && BuiltInRegistries.ITEM.containsKey(iconId)) icon = BuiltInRegistries.ITEM.get(iconId);
        return new Header(string(root, "name", pretty(folderName)), string(root, "author", ""),
                string(root, "description", ""), icon);
    }

    /** The buildings the json lists, or every file in the folder when it lists none. */
    private static List<Wanted> wanted(JsonObject root, Iterable<String> files) {
        List<Wanted> out = new ArrayList<>();
        JsonElement list = root.get("buildings");
        if (list instanceof JsonArray array && !array.isEmpty()) {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject building = element.getAsJsonObject();
                String file = string(building, "file", "");
                if (file.isEmpty()) continue;
                out.add(new Wanted(file, string(building, "name", pretty(stripExtension(file))),
                        string(building, "author", ""), string(building, "description", "")));
            }
            return out;
        }
        for (String file : files) out.add(new Wanted(file, pretty(stripExtension(file)), "", ""));
        return out;
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        String text = value.getAsString().trim();
        return text.isEmpty() ? fallback : text;
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String stripExtension(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /** "small_house" -> "Small house"; a name with capitals or spaces stays as it is. */
    static String pretty(String raw) {
        if (raw.contains(" ") || !raw.equals(raw.toLowerCase(Locale.ROOT))) return raw;
        String spaced = raw.replace('_', ' ').replace('-', ' ').trim();
        if (spaced.isEmpty()) return raw;
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
