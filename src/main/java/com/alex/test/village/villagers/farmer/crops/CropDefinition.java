package com.alex.test.village.villagers.farmer.crops;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

/**
 * A farmer-workable crop family: what item plants it, what block(s) represent
 * it planted, when it is ready, where it may be planted, and how it is
 * harvested. Instances come from reloadable datapack JSON
 * (see {@code CropDefinitionReloadListener}) under {@code data/<namespace>/farmer_crops/*.json},
 * not from Java source.
 */
public record CropDefinition(
        ResourceLocation id,
        ItemMatcher plantingItem,
        BlockMatcher cropBlock,
        CropMaturity maturity,
        CropPlantingStrategy planting,
        CropHarvestStrategy harvest,
        HarvestTool harvestTool) {

    public boolean matchesPlantingItem(Item item) {
        return plantingItem.matches(item);
    }

    public boolean matchesCropBlock(Block block) {
        return cropBlock.matches(block);
    }

    /**
     * A single concrete item that stands in for this definition's planting
     * item, used wherever farmer logic needs to search inventory/storage for
     * a real {@link Item} (inventory and storage APIs work on exact items,
     * not tags). Present for a single-item or any-of-with-a-single-item
     * matcher; absent for a purely tag-based planting item, in which case
     * this crop cannot be auto-selected for supply fetching or inferred
     * replanting (it can still be discovered, harvested and replanted
     * wherever it is already planted, since that path resolves the
     * definition directly from the crop block, not the planting item).
     */
    public Optional<Item> representativePlantingItem() {
        return switch (plantingItem) {
            case ItemMatcher.Single single -> Optional.of(single.item());
            case ItemMatcher.AnyOf anyOf -> anyOf.matchers().stream()
                    .filter(matcher -> matcher instanceof ItemMatcher.Single)
                    .map(matcher -> ((ItemMatcher.Single) matcher).item())
                    .findFirst();
            case ItemMatcher.Tagged ignored -> Optional.empty();
        };
    }

    /**
     * A concrete planted block for inventory-only helpers that cannot query a
     * world position. As with {@link #representativePlantingItem()}, tag-only
     * definitions deliberately have no representative.
     */
    public Optional<Block> representativeCropBlock() {
        return switch (cropBlock) {
            case BlockMatcher.Single single -> Optional.of(single.block());
            case BlockMatcher.AnyOf anyOf -> anyOf.matchers().stream()
                    .filter(matcher -> matcher instanceof BlockMatcher.Single)
                    .map(matcher -> ((BlockMatcher.Single) matcher).block())
                    .findFirst();
            case BlockMatcher.Tagged ignored -> Optional.empty();
        };
    }
}
