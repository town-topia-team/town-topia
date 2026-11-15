package com.alex.test.village.villagers.farmer;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inventory helpers backed by the live crop-definition registry. */
public final class FarmerInventory {

    /**
     * Personal reserve retained for each planting item during deposits. A
     * local plot sample is 4x4, so one reserve can restore a completely empty
     * sampled plot after a harvest or storage outage.
     */
    public static final int PLANTING_RESERVE_PER_CROP = 16;

    private FarmerInventory() {}

    /** Keep up to one plot's worth of each planting item across all inventory slots. */
    public static int plantingReserveToKeep(Item item, int stackCount,
                                            Map<Item, Integer> alreadyKeptByItem) {
        int kept = alreadyKeptByItem.getOrDefault(item, 0);
        int reserve = com.alex.test.village.villagers.supplies.CarryReserve.toKeep(
                stackCount, kept, PLANTING_RESERVE_PER_CROP);
        alreadyKeptByItem.put(item, kept + reserve);
        return reserve;
    }

    public record PlantingSeed(int slot, BlockState cropState) {}

    public static boolean hasSupportedSeed(InventoryManager inventory) {
        return findPlantingSeed(inventory, null, null) != null;
    }

    public static int countSupportedSeeds(InventoryManager inventory) {
        int count = 0;
        for (ItemStack stack : inventory.getSlots()) {
            if (inventory.mayUse(stack) && isSupportedSeed(stack)) count += stack.getCount();
        }
        return count;
    }

    public static boolean isSupportedSeed(ItemStack stack) {
        return !stack.isEmpty() && CropDefinitionRegistry.byPlantingItem(stack.getItem()) != null;
    }

    /**
     * What a farmer holds back from a deposit, up to one reserve each: the
     * planting items it replants, and its bone meal while it uses bone meal.
     */
    public static boolean isKeptSupply(com.alex.test.entity.NpcEntity farmer, ItemStack stack) {
        com.alex.test.village.villagers.farmer.settings.FarmerSettings settings =
                com.alex.test.village.villagers.farmer.settings.FarmerSettings.of(farmer);
        if (isSupportedSeed(stack)) {
            Item item = stack.getItem();
            return settings.shouldPlant(item) && keepsPlantingReserve(item)
                    && (!isBushSeed(item) || bushSpotsOpen(farmer));
        }
        return stack.is(net.minecraft.world.item.Items.BONE_MEAL) && settings.useBoneMeal();
    }

    /** Sweet berries: planted as new bushes, but food first. */
    private static boolean isBushSeed(Item item) {
        CropDefinition definition = CropDefinitionRegistry.byPlantingItem(item);
        return definition != null && definition.planting()
                instanceof com.alex.test.village.villagers.farmer.crops.CropPlantingStrategy.OnSoil;
    }

    /**
     * Whether the farmer's village has a free spot for a new bush. With every
     * patch full, berries kept as seeds were berries no one ever planted, a
     * stack in each farmer's pocket that never reached storage.
     */
    private static boolean bushSpotsOpen(com.alex.test.entity.NpcEntity farmer) {
        if (!(farmer.level() instanceof net.minecraft.server.level.ServerLevel level)
                || farmer.getVillageLink() == null) return true;
        java.util.UUID villageId = farmer.getVillageLink().villageId();
        com.alex.test.village.VillageManager village =
                com.alex.test.village.VillageManagerRegistry.get(level.getServer()).get(villageId);
        if (village == null && com.alex.test.village.VillageManager.INSTANCE.getVillageId().equals(villageId)) {
            village = com.alex.test.village.VillageManager.INSTANCE;
        }
        return village == null || village.farmers().berrySpotsOpen();
    }

    /**
     * Whether a farmer holds back a reserve of this planting item. Sugar cane
     * is cut above its base and the base grows back, so cane is almost never
     * replanted: what the farmer carries is haul and goes to storage whole.
     * A lost base is planted from storage, fetched when it is needed.
     */
    public static boolean keepsPlantingReserve(Item item) {
        CropDefinition definition = CropDefinitionRegistry.byPlantingItem(item);
        return definition != null
                && !(definition.harvest() instanceof
                        com.alex.test.village.villagers.farmer.crops.CropHarvestStrategy.PreserveBaseStack);
    }

    /**
     * Finds a seed that can be placed at {@code cropPos}. Passing a null level
     * is a cheap inventory-only check used before a storage trip.
     */
    @Nullable
    public static PlantingSeed findPlantingSeed(
            InventoryManager inventory, @Nullable Level level, @Nullable BlockPos cropPos) {
        List<PlantingSeed> seeds = findPlantingSeeds(inventory, level, cropPos);
        return seeds.isEmpty() ? null : seeds.getFirst();
    }

    /**
     * Returns one usable inventory slot per crop type, preserving inventory
     * order for callers that need the legacy fallback behaviour.
     */
    public static List<PlantingSeed> findPlantingSeeds(
            InventoryManager inventory, @Nullable Level level, @Nullable BlockPos cropPos) {
        Map<Block, PlantingSeed> seedsByCrop = new LinkedHashMap<>();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (!inventory.mayUse(stack)) continue;
            Block crop = cropFor(stack.getItem());
            if (crop == null || seedsByCrop.containsKey(crop)) continue;

            BlockState state = crop.defaultBlockState();
            if (level != null && cropPos != null && !state.canSurvive(level, cropPos)) continue;
            seedsByCrop.put(crop, new PlantingSeed(i, state));
        }
        return new ArrayList<>(seedsByCrop.values());
    }

    /** Counts every carried planting item that produces {@code crop}. */
    public static int countPlantingSeeds(InventoryManager inventory, Block crop) {
        int count = 0;
        for (ItemStack stack : inventory.getSlots()) {
            if (inventory.mayUse(stack) && crop == cropFor(stack.getItem())) count += stack.getCount();
        }
        return count;
    }

    /** Returns the exact inventory item required to plant a supported crop. */
    @Nullable
    public static Item plantingItemFor(Block crop) {
        CropDefinition definition = CropDefinitionRegistry.byCropBlock(crop);
        return definition == null ? null : definition.representativePlantingItem().orElse(null);
    }

    public static void consumeSeed(InventoryManager inventory, int slot) {
        if (inventory.mayUse(inventory.getSlot(slot))) inventory.reduceSlot(slot, 1);
    }

    @Nullable
    private static Block cropFor(Item item) {
        CropDefinition definition = CropDefinitionRegistry.byPlantingItem(item);
        return definition == null ? null : definition.representativeCropBlock().orElse(null);
    }
}
