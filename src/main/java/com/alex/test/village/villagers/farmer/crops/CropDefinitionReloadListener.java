package com.alex.test.village.villagers.farmer.crops;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads farmer crop definitions from {@code data/<namespace>/farmer_crops/*.json}
 * on every datapack (re)load, letting packs and dependent mods add or override
 * crop definitions without touching Java. One malformed entry is logged and
 * skipped rather than failing the whole reload.
 */
public final class CropDefinitionReloadListener extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIRECTORY = "farmer_crops";

    public CropDefinitionReloadListener() {
        super(new Gson(), DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries,
                          ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, CropDefinition> parsed = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            try {
                CropDefinition definition = CropDefinitionParser.parse(
                        entry.getKey(), entry.getValue().getAsJsonObject());
                parsed.put(entry.getKey(), definition);
            } catch (Exception e) {
                LOGGER.error("Skipping invalid farmer crop definition {}: {}", entry.getKey(), e.getMessage());
            }
        }
        CropDefinitionRegistry.install(parsed);
        LOGGER.info("Loaded {} farmer crop definition(s)", parsed.size());
    }
}
