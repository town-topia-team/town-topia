package com.alex.test.village.economy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads the merchant's price table from {@code data/<namespace>/economy/*.json}
 * on every datapack (re)load. Files are applied in id order, later ones
 * overriding earlier ones entry by entry, so a pack can ship its own file
 * that only names the goods it changes. A malformed file is logged and
 * skipped; the mod's own file still loads.
 */
public final class MarketPriceReloadListener extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIRECTORY = "economy";

    public MarketPriceReloadListener() {
        super(new Gson(), DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries,
                          ResourceManager resourceManager, ProfilerFiller profiler) {
        List<MarketPrices.Table> parts = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : new TreeMap<>(entries).entrySet()) {
            try {
                parts.add(parse(entry.getValue().getAsJsonObject()));
            } catch (Exception e) {
                LOGGER.error("Skipping invalid market price file {}: {}", entry.getKey(), e.getMessage());
            }
        }
        MarketPrices.Table table = MarketPrices.merge(parts);
        MarketPrices.install(table);
        LOGGER.info("Loaded market prices: {} item(s), {} tag(s), {} book enchantment(s), max {}, {}",
                table.items().size(), table.tags().size(), table.books().size(), table.maxPrice(),
                table.tuning());
    }

    static MarketPrices.Table parse(JsonObject json) {
        int maxPrice = json.has("max_price") ? json.get("max_price").getAsInt() : MarketPrices.HARD_MAX_PRICE;
        int fallback = json.has("fallback_price") ? json.get("fallback_price").getAsInt() : 4;
        Map<ResourceLocation, Integer> items = new LinkedHashMap<>();
        if (json.has("items")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("items").entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
                if (id == null) {
                    LOGGER.warn("Market price for unknown item id '{}' ignored", entry.getKey());
                    continue;
                }
                items.put(id, entry.getValue().getAsInt());
            }
        }
        List<MarketPrices.TagPrice> tags = new ArrayList<>();
        if (json.has("tags")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("tags").entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
                if (id == null) {
                    LOGGER.warn("Market price for unknown tag '{}' ignored", entry.getKey());
                    continue;
                }
                tags.add(new MarketPrices.TagPrice(MarketPrices.itemTag(id), entry.getValue().getAsInt()));
            }
        }
        Map<String, Integer> books = new LinkedHashMap<>();
        if (json.has("books")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("books").entrySet()) {
                books.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        // Only a file with a "market" block sets the numbers; a missing key
        // inside it takes its default, and the values are pulled into range.
        MarketTuning tuning = null;
        if (json.has("market")) {
            JsonObject market = json.getAsJsonObject("market");
            tuning = MarketTuning.clamped(
                    number(market, "drop_per_stack", MarketTuning.DEFAULT_DROP_PER_STACK),
                    number(market, "rise_per_emerald", MarketTuning.DEFAULT_RISE_PER_EMERALD),
                    number(market, "max_rise", MarketTuning.DEFAULT_MAX_RISE),
                    number(market, "lowest_share", MarketTuning.DEFAULT_LOWEST_SHARE),
                    number(market, "keep_each_morning", MarketTuning.DEFAULT_KEEP_EACH_MORNING));
        }
        return new MarketPrices.Table(maxPrice, fallback, items, tags, books, tuning);
    }

    private static double number(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }
}
