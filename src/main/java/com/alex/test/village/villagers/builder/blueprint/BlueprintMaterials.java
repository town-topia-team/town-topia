package com.alex.test.village.villagers.builder.blueprint;

import com.alex.test.block.PianoLock;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The items a blueprint takes to build: one item per placed block, with the
 * exceptions a player would expect. The top half of a door or tall flower, the
 * head of a bed and fluids cost nothing (they come with the other half, or not at
 * all); a double slab costs two slabs, a cluster of candles or sea pickles one per
 * piece. Air cells are clearing work, not material.
 *
 * <p>Common code: the server counts the same way when it checks the storage room.
 */
public final class BlueprintMaterials {

    private BlueprintMaterials() {}

    /**
     * @param items        item to how many, most used first
     * @param total        all items together
     * @param noItem       placed blocks that have no item at all (fire, a piston head):
     *                     the Builder will skip them
     */
    public record Result(Map<Item, Integer> items, int total, int noItem) {
        /** The items, most used first. */
        public List<Map.Entry<Item, Integer>> lines() {
            return new ArrayList<>(items.entrySet());
        }
    }

    public static Result count(Blueprint blueprint) {
        int[] perState = new int[blueprint.palette().size()];
        for (int i = 0; i < blueprint.volume(); i++) {
            int p = blueprint.paletteIndex(i);
            if (p != Blueprint.VOID) perState[p]++;
        }
        Map<Item, Integer> counts = new LinkedHashMap<>();
        int total = 0, noItem = 0;
        for (int p = 0; p < perState.length; p++) {
            if (perState[p] == 0) continue;
            BlockState state = blueprint.palette().get(p);
            int each = itemsPerBlock(state);
            if (each == 0) continue;
            Item item = state.getBlock().asItem();
            if (item == Items.AIR) {
                noItem += perState[p];
                continue;
            }
            counts.merge(item, each * perState[p], Integer::sum);
            total += each * perState[p];
        }
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        Map<Item, Integer> ordered = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : sorted) ordered.put(e.getKey(), e.getValue());
        return new Result(Collections.unmodifiableMap(ordered), total, noItem);
    }

    /** How many of its item one block of this state takes; 0 for free cells. */
    public static int itemsPerBlock(BlockState state) {
        if (state.isAir()) return 0;
        Block block = state.getBlock();
        if (block instanceof LiquidBlock) return 0;
        if (PianoLock.isLocked(state)) return 0; // never built (BuildSite.need)
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return 0;
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return 0;
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return 2;
        if (state.hasProperty(BlockStateProperties.CANDLES)) return state.getValue(BlockStateProperties.CANDLES);
        if (state.hasProperty(BlockStateProperties.PICKLES)) return state.getValue(BlockStateProperties.PICKLES);
        if (state.hasProperty(BlockStateProperties.EGGS)) return state.getValue(BlockStateProperties.EGGS);
        if (block instanceof SnowLayerBlock) return state.getValue(SnowLayerBlock.LAYERS);
        return 1;
    }
}
