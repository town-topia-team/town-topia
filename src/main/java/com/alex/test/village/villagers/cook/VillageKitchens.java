package com.alex.test.village.villagers.cook;

import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.KitchenBuilding.HeatSource;
import com.alex.test.village.building.KitchenStations;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every standing kitchen of a village, worked by its cooks as one: what the
 * village has to cook on, and every station in any of them. A station in a
 * wall two kitchens share is listed once.
 *
 * <p>The cooks used to know only the village's first kitchen, so a second
 * kitchen's smoker, furnace and tables were never used, and what the first one
 * lacked was never cooked on the second's.
 */
public final class VillageKitchens implements KitchenStations {

    private final List<KitchenBuilding> kitchens;

    private VillageKitchens(List<KitchenBuilding> kitchens) {
        this.kitchens = kitchens;
    }

    /** The village's standing kitchens, or null when it has none. */
    @Nullable
    public static VillageKitchens of(VillageManager mgr) {
        List<KitchenBuilding> kitchens = new ArrayList<>();
        for (Building building : mgr.getBuildings()) {
            if (building instanceof KitchenBuilding kitchen && kitchen.isValid()) kitchens.add(kitchen);
        }
        return kitchens.isEmpty() ? null : new VillageKitchens(List.copyOf(kitchens));
    }

    /** The kitchen {@code station} stands in, to work it from; null when none lists it. */
    @Nullable
    public KitchenBuilding kitchenOf(BlockPos station) {
        for (KitchenBuilding kitchen : kitchens) {
            if (kitchen.hasStation(station)) return kitchen;
        }
        return null;
    }

    public int size() {
        return kitchens.size();
    }

    @Override
    @Nullable
    public BlockPos getHeatPos(HeatSource kind) {
        for (KitchenBuilding kitchen : kitchens) {
            BlockPos pos = kitchen.getHeatPos(kind);
            if (pos != null) return pos;
        }
        return null;
    }

    @Override
    @Nullable
    public BlockPos getCraftingTablePos() {
        for (KitchenBuilding kitchen : kitchens) {
            BlockPos pos = kitchen.getCraftingTablePos();
            if (pos != null) return pos;
        }
        return null;
    }

    @Override
    public List<BlockPos> getHeatPositions(HeatSource kind) {
        Set<BlockPos> all = new LinkedHashSet<>();
        for (KitchenBuilding kitchen : kitchens) all.addAll(kitchen.getHeatPositions(kind));
        return List.copyOf(all);
    }

    @Override
    public List<BlockPos> getCraftingTablePositions() {
        Set<BlockPos> all = new LinkedHashSet<>();
        for (KitchenBuilding kitchen : kitchens) all.addAll(kitchen.getCraftingTablePositions());
        return List.copyOf(all);
    }
}
