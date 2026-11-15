package com.alex.test.village.villagers.farmer.crops;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Live, reload-replaceable table of every {@link CropDefinition} the farmer
 * AI and settings UI may resolve. Installed wholesale by
 * {@code CropDefinitionReloadListener} on every datapack (re)load; installed
 * directly by unit tests with a fixed map so parsing/lookup logic can be
 * exercised without a live {@code ResourceManager}.
 */
public final class CropDefinitionRegistry {

    private static volatile List<CropDefinition> definitions = List.of();

    private CropDefinitionRegistry() {}

    /** Replaces the live table. Definitions are exposed in a stable id-sorted order. */
    public static void install(Map<ResourceLocation, CropDefinition> parsed) {
        List<CropDefinition> ordered = new ArrayList<>(parsed.values());
        ordered.sort(Comparator.comparing(definition -> definition.id().toString()));
        definitions = List.copyOf(ordered);
    }

    /** All currently resolved definitions, stably ordered by id. */
    public static List<CropDefinition> all() {
        return definitions;
    }

    @Nullable
    public static CropDefinition byPlantingItem(Item item) {
        for (CropDefinition definition : definitions) {
            if (definition.matchesPlantingItem(item)) return definition;
        }
        return null;
    }

    @Nullable
    public static CropDefinition byCropBlock(Block block) {
        for (CropDefinition definition : definitions) {
            if (definition.matchesCropBlock(block)) return definition;
        }
        return null;
    }

    /** The deterministic default planting definition: first id-ordered farmland crop. */
    @Nullable
    public static CropDefinition defaultFarmlandCrop() {
        for (CropDefinition definition : definitions) {
            if (definition.planting() instanceof CropPlantingStrategy.Farmland) return definition;
        }
        return null;
    }

    /**
     * Resolves a crop block's maturity, consulting the block above for
     * {@link CropMaturity.StackHeight} rules that a bare {@link BlockState}
     * cannot answer on its own.
     */
    public static boolean isMature(BlockGetter level, BlockPos pos, BlockState state, CropDefinition definition) {
        if (definition.maturity() instanceof CropMaturity.StackHeight) {
            return level.getBlockState(pos.above()).getBlock() == state.getBlock();
        }
        return isMature(state, definition);
    }

    /**
     * Position-independent maturity check for callers that only have a
     * {@link BlockState} in hand. {@link CropMaturity.StackHeight} cannot be
     * answered without the block above, so it always reports not-mature here;
     * callers that may see stacked crops should prefer the position-aware
     * overload above.
     */
    public static boolean isMature(BlockState state, CropDefinition definition) {
        return switch (definition.maturity()) {
            case CropMaturity.AgeProperty ignored -> isAgeMature(state);
            case CropMaturity.StackHeight ignored -> false;
            case CropMaturity.AttachedFruit ignored -> false;
            case CropMaturity.BerriesProperty ignored ->
                    state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BERRIES)
                            && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BERRIES);
        };
    }

    private static boolean isAgeMature(BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) {
            return crop.getAge(state) >= crop.getMaxAge();
        }
        for (var property : state.getProperties()) {
            if (property instanceof net.minecraft.world.level.block.state.properties.IntegerProperty age
                    && "age".equals(age.getName())) {
                int maximum = age.getPossibleValues().stream().mapToInt(Integer::intValue)
                        .max().orElse(Integer.MAX_VALUE);
                return state.getValue(age) >= maximum;
            }
        }
        return false;
    }
}
