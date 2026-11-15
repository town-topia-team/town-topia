package com.alex.test.village.villagers.rancher;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/** Species knowledge shared by rancher and butcher behaviour. */
public final class RanchAnimals {

    private RanchAnimals() {}

    private static final List<Item> GRAZER_FOODS = List.of(Items.WHEAT);
    private static final List<Item> PIG_FOODS = List.of(Items.CARROT, Items.POTATO, Items.BEETROOT);
    /**
     * Wheat seeds first: the farms' leftover. Melon, pumpkin and beetroot seeds
     * are what the farmers plant those crops from, and torchflower seeds and
     * pitcher pods are rare, so they go only when no wheat seeds can be had.
     */
    private static final List<Item> CHICKEN_FOODS = List.of(Items.WHEAT_SEEDS, Items.MELON_SEEDS,
            Items.PUMPKIN_SEEDS, Items.BEETROOT_SEEDS, Items.TORCHFLOWER_SEEDS, Items.PITCHER_POD);

    /**
     * Every food this species breeds on in vanilla, the one a rancher feeds and
     * fetches first at the front; empty if unmanaged.
     */
    public static List<Item> breedFoodsFor(EntityType<?> type) {
        if (type == EntityType.COW || type == EntityType.SHEEP) return GRAZER_FOODS;
        if (type == EntityType.PIG) return PIG_FOODS;
        if (type == EntityType.CHICKEN) return CHICKEN_FOODS;
        return List.of();
    }

    /** The first of {@link #breedFoodsFor}, or null if unmanaged: also the test for a ranch animal. */
    @Nullable
    public static Item breedFoodFor(EntityType<?> type) {
        List<Item> foods = breedFoodsFor(type);
        return foods.isEmpty() ? null : foods.get(0);
    }

    /** Whether {@code item} breeds any kind a rancher tends. */
    public static boolean isBreedFood(Item item) {
        return GRAZER_FOODS.contains(item) || PIG_FOODS.contains(item) || CHICKEN_FOODS.contains(item);
    }

    /** The raw meat a butchered animal yields, or null if it isn't livestock. */
    @Nullable
    public static Item meatFor(EntityType<?> type) {
        if (type == EntityType.COW) return Items.BEEF;
        if (type == EntityType.SHEEP) return Items.MUTTON;
        if (type == EntityType.PIG) return Items.PORKCHOP;
        if (type == EntityType.CHICKEN) return Items.CHICKEN;
        return null;
    }

    /** Wool item matching the sheep's colour (white_wool, black_wool, ...). */
    public static Item woolFor(Sheep sheep) {
        return woolFor(sheep.getColor());
    }

    /** Wool item of {@code color}; also for a lamb born while nobody was near (economy/away). */
    public static Item woolFor(DyeColor color) {
        Item wool = BuiltInRegistries.ITEM.getOptional(ResourceLocation.withDefaultNamespace(
                color.getName() + "_wool")).orElse(Items.WHITE_WOOL);
        return wool == Items.AIR ? Items.WHITE_WOOL : wool;
    }
}
