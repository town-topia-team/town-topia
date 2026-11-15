package com.alex.test.village.villagers.farmer.settings;

import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the vanilla planting tag plus every live {@link CropDefinition}'s
 * planting item, filtered to the ones the farmer can actually work.
 */
public final class FarmerPlantingResources {

    private FarmerPlantingResources() {}

    public static List<Item> allSupported() {
        Set<Item> candidates = new LinkedHashSet<>();
        BuiltInRegistries.ITEM.getTag(ItemTags.VILLAGER_PLANTABLE_SEEDS)
                .ifPresent(tag -> tag.forEach(holder -> candidates.add(holder.value())));
        for (Item item : BuiltInRegistries.ITEM) {
            if (isSupported(item)) candidates.add(item);
        }
        candidates.removeIf(item -> !isSupported(item));

        List<Item> result = new ArrayList<>(candidates);
        result.sort((a, b) -> {
            String aNamespace = BuiltInRegistries.ITEM.getKey(a).getNamespace();
            String bNamespace = BuiltInRegistries.ITEM.getKey(b).getNamespace();
            boolean aVanilla = "minecraft".equals(aNamespace);
            boolean bVanilla = "minecraft".equals(bNamespace);
            if (aVanilla != bVanilla) return aVanilla ? -1 : 1;
            return a.getDescription().getString().compareToIgnoreCase(b.getDescription().getString());
        });
        return List.copyOf(result);
    }

    public static boolean isSupported(Item item) {
        return CropDefinitionRegistry.byPlantingItem(item) != null;
    }
}
