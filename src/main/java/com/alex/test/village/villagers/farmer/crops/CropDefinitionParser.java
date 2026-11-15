package com.alex.test.village.villagers.farmer.crops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses one {@code data/&lt;namespace&gt;/farmer_crops/*.json} entry into a
 * {@link CropDefinition}. Kept as a hand-written parser (rather than a
 * {@code Codec}) so one malformed or foreign entry can be logged and skipped
 * by the reload listener without a full pack-wide {@code Codec} failure.
 */
public final class CropDefinitionParser {

    private CropDefinitionParser() {}

    public static CropDefinition parse(ResourceLocation id, JsonObject json) {
        ItemMatcher plantingItem = parseItemMatcher(json.get("planting_item"), "planting_item");
        BlockMatcher cropBlock = parseBlockMatcher(json.get("crop_block"), "crop_block");
        CropMaturity maturity = parseMaturity(GsonHelper.getAsJsonObject(json, "maturity"));
        CropPlantingStrategy planting = parsePlanting(GsonHelper.getAsJsonObject(json, "planting"));
        CropHarvestStrategy harvest = parseHarvest(GsonHelper.getAsJsonObject(json, "harvest"));
        HarvestTool harvestTool;
        try {
            harvestTool = HarvestTool.parse(GsonHelper.getAsString(json, "harvest_tool", ""));
        } catch (IllegalArgumentException unknown) {
            throw new JsonParseException(unknown.getMessage());
        }
        return new CropDefinition(id, plantingItem, cropBlock, maturity, planting, harvest, harvestTool);
    }

    private static String type(JsonObject object) {
        return GsonHelper.getAsString(object, "type");
    }

    private static CropMaturity parseMaturity(JsonObject object) {
        String type = type(object);
        return switch (typePath(type)) {
            case "age_property" -> new CropMaturity.AgeProperty();
            case "stack_height" -> new CropMaturity.StackHeight();
            case "attached_fruit" -> new CropMaturity.AttachedFruit();
            case "berries_property" -> new CropMaturity.BerriesProperty();
            default -> throw new JsonParseException("Unknown maturity type: " + type);
        };
    }

    private static CropPlantingStrategy parsePlanting(JsonObject object) {
        String type = type(object);
        return switch (typePath(type)) {
            case "farmland" -> new CropPlantingStrategy.Farmland();
            case "near_water" -> new CropPlantingStrategy.NearWater(blockList(object, "soil_blocks"));
            case "on_log" -> new CropPlantingStrategy.OnLog(blockList(object, "log_blocks"));
            case "on_soil" -> new CropPlantingStrategy.OnSoil(blockList(object, "soil_blocks"));
            case "hanging" -> new CropPlantingStrategy.Hanging();
            default -> throw new JsonParseException("Unknown planting type: " + type);
        };
    }

    private static List<BlockMatcher> blockList(JsonObject object, String field) {
        JsonArray entries = GsonHelper.getAsJsonArray(object, field);
        List<BlockMatcher> matchers = new ArrayList<>();
        for (JsonElement entry : entries) matchers.add(parseBlockMatcherElement(entry, field));
        return List.copyOf(matchers);
    }

    private static CropHarvestStrategy parseHarvest(JsonObject object) {
        String type = type(object);
        return switch (typePath(type)) {
            case "break_and_replant" -> new CropHarvestStrategy.BreakAndReplant();
            case "preserve_base_stack" -> new CropHarvestStrategy.PreserveBaseStack();
            case "adjacent_fruit" -> new CropHarvestStrategy.AdjacentFruit(
                    parseBlockMatcher(object.get("fruit_block"), "fruit_block"));
            case "pick_keep_plant" -> new CropHarvestStrategy.PickKeepPlant();
            default -> throw new JsonParseException("Unknown harvest type: " + type);
        };
    }

    /** Accepts a namespaced or bare discriminator ("towntopia:age_property" or "age_property"; any namespace). */
    private static String typePath(String type) {
        return CropRuleType.path(type);
    }

    private static ItemMatcher parseItemMatcher(JsonElement element, String field) {
        if (element == null) throw new JsonParseException("Missing required field: " + field);
        if (element.isJsonArray()) {
            List<ItemMatcher> matchers = new ArrayList<>();
            for (JsonElement entry : element.getAsJsonArray()) matchers.add(parseItemMatcherElement(entry, field));
            return new ItemMatcher.AnyOf(List.copyOf(matchers));
        }
        return parseItemMatcherElement(element, field);
    }

    private static ItemMatcher parseItemMatcherElement(JsonElement element, String field) {
        String raw = element.getAsString();
        if (raw.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(raw.substring(1)));
            return new ItemMatcher.Tagged(tag);
        }
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(raw))
                .orElseThrow(() -> new JsonParseException("Unknown item in " + field + ": " + raw));
        return new ItemMatcher.Single(item);
    }

    private static BlockMatcher parseBlockMatcher(JsonElement element, String field) {
        if (element == null) throw new JsonParseException("Missing required field: " + field);
        if (element.isJsonArray()) {
            List<BlockMatcher> matchers = new ArrayList<>();
            for (JsonElement entry : element.getAsJsonArray()) matchers.add(parseBlockMatcherElement(entry, field));
            return new BlockMatcher.AnyOf(List.copyOf(matchers));
        }
        return parseBlockMatcherElement(element, field);
    }

    private static BlockMatcher parseBlockMatcherElement(JsonElement element, String field) {
        String raw = element.getAsString();
        if (raw.startsWith("#")) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(raw.substring(1)));
            return new BlockMatcher.Tagged(tag);
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(raw))
                .orElseThrow(() -> new JsonParseException("Unknown block in " + field + ": " + raw));
        return new BlockMatcher.Single(block);
    }
}
