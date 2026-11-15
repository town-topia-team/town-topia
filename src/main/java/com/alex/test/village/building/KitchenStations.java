package com.alex.test.village.building;

import com.alex.test.village.building.KitchenBuilding.HeatSource;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

/**
 * What a cook can cook on: one {@link KitchenBuilding}, or every kitchen of a
 * village together ({@code VillageKitchens}).
 */
public interface KitchenStations {

    /** The first heat source of {@code kind}, or null when there is none. */
    @Nullable
    BlockPos getHeatPos(HeatSource kind);

    /** The first crafting table, or null when there is none. */
    @Nullable
    BlockPos getCraftingTablePos();

    /** Every heat source of {@code kind}; empty when there is none. */
    List<BlockPos> getHeatPositions(HeatSource kind);

    /** Every crafting table; empty when there is none. */
    List<BlockPos> getCraftingTablePositions();
}
