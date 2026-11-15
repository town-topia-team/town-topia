package com.alex.test.village.villagers.farmer;

import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One discovered farm: the connected agricultural tiles that anchor it and the
 * latest classification of every tile around them.
 *
 * <p>{@link FarmerManager} creates sites during discovery and owns their
 * active jobs; {@link FarmSiteSurvey#refreshSite} re-reads the world and
 * refills the tile sets in place.
 */
final class FarmSite {
    final UUID id;
    /** True only for a recognisable non-cane crop field. */
    boolean cropField;
    final Set<BlockPos> anchors = new LinkedHashSet<>();
    final Set<BlockPos> water = new LinkedHashSet<>();
    final Set<BlockPos> hydration = new LinkedHashSet<>();
    final Set<BlockPos> tillable = new LinkedHashSet<>();
    final Set<BlockPos> tilled = new LinkedHashSet<>();
    final Set<BlockPos> planted = new LinkedHashSet<>();
    final Set<BlockPos> mature = new LinkedHashSet<>();
    final Set<BlockPos> blocked = new LinkedHashSet<>();
    final Set<BlockPos> fruits = new LinkedHashSet<>();
    /** Base tile of a stacked (near-water-planted) crop, e.g. sugar cane. */
    final Map<BlockPos, CropDefinition> stackBases = new LinkedHashMap<>();
    /** Empty tiles where a stacked crop could be planted, and which definition applies. */
    final Map<BlockPos, CropDefinition> stackPlantable = new LinkedHashMap<>();
    final Set<BlockPos> everFarmland = new LinkedHashSet<>();
    /** Stem tile (live or planned, tilled or not) to the empty tile its fruit grows into. */
    final Map<BlockPos, BlockPos> fruitSpaces = new LinkedHashMap<>();
    /** Untilled soil beside pumpkin/melon stems that has no stem partner and must stay soil. */
    final Set<BlockPos> heldSoil = new LinkedHashSet<>();
    final Map<BlockPos, CropDefinition> inferredCrop = new HashMap<>();
    final Map<UUID, FarmerManager.Job> activeJobs = new LinkedHashMap<>();
    long lastScan;
    long lastValidation;
    long lastActivity;

    FarmSite(UUID id) {
        this.id = id;
    }

    /** Whether {@code tile} is being kept empty for pumpkin/melon fruit and must not be tilled or planted. */
    boolean isKeptForFruit(BlockPos tile) {
        return heldSoil.contains(tile) || fruitSpaces.containsValue(tile);
    }

    Set<BlockPos> allWorkPositions() {
        Set<BlockPos> all = new LinkedHashSet<>(hydration);
        all.addAll(anchors);
        all.addAll(fruits);
        all.addAll(stackBases.keySet());
        all.addAll(stackPlantable.keySet());
        return all;
    }
}
