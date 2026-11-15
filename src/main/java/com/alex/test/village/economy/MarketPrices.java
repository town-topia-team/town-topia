package com.alex.test.village.economy;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The merchant's base prices, loaded from {@code data/<namespace>/economy/*.json}
 * on every datapack (re)load by {@link MarketPriceReloadListener}.
 *
 * <p>A price is emeralds for one full stack of the good, whatever its stack
 * size (64 for most goods, 16 for eggs), and never above {@link #maxPrice()}
 * unless the file names the good itself ({@link #HARD_MAX_ITEM_PRICE}).
 * An explicit item entry wins over a tag entry, and a tag entry over the
 * fallback, so a pack can price one crop without listing every crop.
 *
 * <p>Enchanted books have two prices in the {@code books} section: a
 * top-level level V book ({@code level_5}) and any other top-level book
 * ({@code other}). All books share one price drop: every book sold lowers
 * the next one's price, whatever it holds.
 *
 * <p>Recent sales move the live price up or down from these bases; see
 * {@link MarketMemory}. This class only knows the table, including the five
 * {@link MarketTuning} numbers of the {@code market} block that say how far.
 */
public final class MarketPrices {
    private MarketPrices() {}

    /** The ceiling nothing may ever be worth more than, however the file is written. */
    public static final int HARD_MAX_PRICE = 8;
    /**
     * A good named in the file's {@code items} may be priced above
     * {@code max_price}, up to this (a stack of diamonds is 24). Tags, the
     * fallback and books never go above {@code max_price}.
     */
    public static final int HARD_MAX_ITEM_PRICE = 64;
    private static final int DEFAULT_MAX_PRICE = 8;
    private static final int DEFAULT_FALLBACK_PRICE = 4;
    private static final int DEFAULT_OTHER_BOOK_PRICE = 4;

    /**
     * One loaded table; replaced whole on reload. {@code tuning} is null on a
     * part whose file has no {@code market} block, so it leaves the numbers an
     * earlier file set alone; a merged table always has one.
     */
    public record Table(int maxPrice, int fallbackPrice, Map<ResourceLocation, Integer> items,
                        List<TagPrice> tags, Map<String, Integer> books, @Nullable MarketTuning tuning) {
        public static final Table EMPTY = new Table(DEFAULT_MAX_PRICE, DEFAULT_FALLBACK_PRICE,
                Map.of(), List.of(), Map.of(), MarketTuning.DEFAULT);
    }

    public record TagPrice(TagKey<Item> tag, int price) {}

    private static volatile Table table = Table.EMPTY;
    private static final Map<Item, Integer> CACHE = new ConcurrentHashMap<>();

    /** Installs a freshly loaded table and forgets every cached answer. */
    public static void install(Table loaded) {
        table = loaded;
        CACHE.clear();
    }

    /** Tags may have changed what an item is; prices are looked up again. */
    public static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        CACHE.clear();
    }

    public static Table table() { return table; }

    /** How sales move prices: the {@code market} block of the price file. */
    public static MarketTuning tuning() {
        MarketTuning tuning = table.tuning();
        return tuning != null ? tuning : MarketTuning.DEFAULT;
    }

    /** The most any good or book pays for a full stack or a single book. */
    public static int maxPrice() {
        return Math.clamp(table.maxPrice(), 1, HARD_MAX_PRICE);
    }

    /**
     * Emeralds for a full stack of {@code item}, 1 to {@link #maxPrice()}, or
     * up to {@link #HARD_MAX_ITEM_PRICE} for a good the file names itself.
     */
    public static int basePrice(Item item) {
        Integer cached = CACHE.get(item);
        if (cached != null) return cached;
        Table current = table;
        Integer own = current.items().get(BuiltInRegistries.ITEM.getKey(item));
        int price = own != null ? Math.clamp(own, 1, HARD_MAX_ITEM_PRICE) : clamp(resolve(current, item));
        CACHE.put(item, price);
        return price;
    }

    /**
     * The most a good whose base is {@code basePrice} may pay for a stack:
     * {@link #maxPrice()}, or its own base when that is higher, so a diamond
     * stack never rises above 24 and welcome prices do not double it.
     */
    public static int ceiling(int basePrice) {
        return Math.max(maxPrice(), basePrice);
    }

    private static int resolve(Table current, Item item) {
        ItemStack sample = new ItemStack(item);
        for (TagPrice entry : current.tags()) {
            if (sample.is(entry.tag())) return entry.price();
        }
        return current.fallbackPrice();
    }

    /** A top-level level V book: 6 by default. */
    private static final int DEFAULT_LEVEL_FIVE_BOOK_PRICE = 6;

    /**
     * Base emeralds for one top-level enchanted book: {@code level_5} for a
     * level V book (Efficiency V, Sharpness V, Power V), {@code other} for any
     * other top-level book. Recent book sales lower it (MarketMemory).
     */
    public static int bookPrice(boolean levelFive) {
        Map<String, Integer> books = table.books();
        return clamp(levelFive ? books.getOrDefault("level_5", DEFAULT_LEVEL_FIVE_BOOK_PRICE)
                : books.getOrDefault("other", DEFAULT_OTHER_BOOK_PRICE));
    }

    public static int clamp(int price) {
        return Math.clamp(price, 1, maxPrice());
    }

    /** Builds a table from the parsed sections of one or more files, later files winning. */
    public static Table merge(List<Table> parts) {
        int maxPrice = DEFAULT_MAX_PRICE;
        int fallback = DEFAULT_FALLBACK_PRICE;
        Map<ResourceLocation, Integer> items = new LinkedHashMap<>();
        List<TagPrice> tags = new ArrayList<>();
        Map<String, Integer> books = new LinkedHashMap<>();
        MarketTuning tuning = MarketTuning.DEFAULT;
        for (Table part : parts) {
            maxPrice = part.maxPrice();
            fallback = part.fallbackPrice();
            items.putAll(part.items());
            tags.addAll(part.tags());
            books.putAll(part.books());
            if (part.tuning() != null) tuning = part.tuning();
        }
        return new Table(maxPrice, fallback, Map.copyOf(items), List.copyOf(tags), Map.copyOf(books), tuning);
    }

    public static TagKey<Item> itemTag(ResourceLocation id) {
        return TagKey.create(Registries.ITEM, id);
    }
}
