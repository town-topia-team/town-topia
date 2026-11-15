package com.alex.test.village.villagers.supplies;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Logs into planks, as a crafting table does it: four planks of the log's own
 * wood. Shared by the cook (planks for bowls) and the archer (planks for
 * sticks), who used to wait for planks to turn up as somebody's leftovers.
 */
public final class WoodCutting {

    /** Vanilla: one log makes four planks. */
    public static final int PLANKS_PER_LOG = 4;

    private static final List<String> LOG_SUFFIXES = List.of("_log", "_wood", "_stem", "_hyphae");

    private WoodCutting() {}

    /**
     * The planks {@code log} is cut into: a spruce log gives spruce planks,
     * stripped logs and bark blocks too. Oak planks when the wood has no
     * planks of its own name (a modded log).
     */
    public static Item planksFor(Item log) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(log);
        String path = id.getPath();
        if (path.startsWith("stripped_")) path = path.substring("stripped_".length());
        for (String suffix : LOG_SUFFIXES) {
            if (!path.endsWith(suffix)) continue;
            String wood = path.substring(0, path.length() - suffix.length());
            Item planks = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath(
                    id.getNamespace(), wood + "_planks")).orElse(Items.AIR);
            if (planks != Items.AIR && planks.builtInRegistryHolder().is(ItemTags.PLANKS)) return planks;
        }
        return Items.OAK_PLANKS;
    }
}
