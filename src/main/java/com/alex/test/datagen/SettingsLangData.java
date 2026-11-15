package com.alex.test.datagen;

import com.alex.test.DynamicVillages;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchemaRegistry;
import com.alex.test.village.villagers.settings.SettingsText;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@code ./gradlew runData}: copies the English of every profession settings
 * screen into {@code src/main/resources/.../lang/en_us.json}, so translators
 * see it. See {@link SettingsText} for the keys.
 *
 * <p>It edits the hand-written en_us.json in place rather than writing a
 * generated one: a mod jar can only have one en_us.json per namespace, and the
 * rest of that file (and the translation site's tools) are hand-maintained.
 * Keys it owns ({@code settings.dynamicvillages.*}) are added, updated and,
 * when a setting is gone, removed; every other line is left untouched.
 */
public final class SettingsLangData implements DataProvider {

    private static final String OWNED_PREFIX = "settings.dynamicvillages.";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path langFile;

    private SettingsLangData(Path langFile) {
        this.langFile = langFile;
    }

    public static void gather(GatherDataEvent event) {
        // The data run writes to <project>/src/generated/resources; en_us.json
        // lives next to it in <project>/src/main/resources.
        Path project = event.getGenerator().getPackOutput().getOutputFolder()
                .toAbsolutePath().getParent().getParent().getParent();
        Path lang = project.resolve("src/main/resources/assets/" + DynamicVillages.MODID + "/lang/en_us.json");
        event.getGenerator().addProvider(true, new SettingsLangData(lang));
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, String> lang = GSON.fromJson(
                        Files.readString(langFile, StandardCharsets.UTF_8),
                        new TypeToken<LinkedHashMap<String, String>>() {}.getType());

                Map<String, String> wanted = new LinkedHashMap<>();
                for (SettingsSchema schema : SettingsSchemaRegistry.all()) {
                    wanted.putAll(SettingsText.english(schema));
                }

                lang.keySet().removeIf(key -> key.startsWith(OWNED_PREFIX) && !wanted.containsKey(key));
                lang.putAll(wanted);

                String text = GSON.toJson(lang).replace("\n", System.lineSeparator()) + System.lineSeparator();
                Files.writeString(langFile, text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public String getName() {
        return "TownTopia settings text -> en_us.json";
    }
}
