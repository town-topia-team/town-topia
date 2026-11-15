package com.alex.test.village.villagers.farmer;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Last-resort seed making for melon and pumpkin: a farmer turns harvested
 * fruit back into seeds only when neither it nor storage has any left, and
 * never beyond one planting reserve.
 */
public final class FarmerSeedCrafting {

    private record Recipe(Item seed, Item fruit, int seedsPerFruit) {}

    private static final List<Recipe> RECIPES = List.of(
            new Recipe(Items.MELON_SEEDS, Items.MELON_SLICE, 1),
            new Recipe(Items.PUMPKIN_SEEDS, Items.PUMPKIN, 4));

    /** {@code storage} is null when the fruit is already carried. */
    public record Plan(Item seed, Item fruit, int fruitNeeded, @Nullable StorageBuilding storage) {}

    private FarmerSeedCrafting() {}

    /** The fruit {@code seed} can be made from, or null. */
    @Nullable
    public static Item fruitFor(Item seed) {
        for (Recipe recipe : RECIPES) if (recipe.seed() == seed) return recipe.fruit();
        return null;
    }

    @Nullable
    public static Plan plan(ServerLevel level, VillageManager village, NpcEntity farmer) {
        FarmerSettings settings = FarmerSettings.of(farmer);
        if (settings.turnOffPlanting()) return null;
        InventoryManager inventory = farmer.getInventory();
        for (Recipe recipe : RECIPES) {
            if (!settings.shouldPlant(recipe.seed()) || count(inventory, recipe.seed()) > 0) continue;
            boolean carriesFruit = count(inventory, recipe.fruit()) > 0;
            StorageBuilding fruitStorage = carriesFruit ? null
                    : nearestStorageWith(level, village, farmer, recipe.fruit());
            if (!carriesFruit && fruitStorage == null) continue;
            if (storageTotal(level, village, recipe.seed()) > 0) continue;
            if (village.farmers().plotsNeeding(level, village, settings, recipe.seed()) <= 0) continue;
            return new Plan(recipe.seed(), recipe.fruit(), fruitNeeded(recipe, 0), fruitStorage);
        }
        return null;
    }

    /** Turns carried fruit into seeds, topping up to the reserve. Returns seeds made. */
    public static int craft(NpcEntity farmer, VillageManager village, Item seed) {
        Recipe recipe = RECIPES.stream().filter(r -> r.seed() == seed).findFirst().orElse(null);
        if (recipe == null) return 0;
        InventoryManager inventory = farmer.getInventory();
        int carriedSeeds = count(inventory, seed);
        int fruitUsed = Math.min(count(inventory, recipe.fruit()), fruitNeeded(recipe, carriedSeeds));
        if (fruitUsed <= 0) return 0;
        int seeds = Math.min(FarmerInventory.PLANTING_RESERVE_PER_CROP - carriedSeeds,
                fruitUsed * recipe.seedsPerFruit());
        // New goods must carry the village binding: an owned inventory
        // refuses unbound non-food items, so plain seeds were rejected, the
        // fruit went back into the chest and the farmer fetched it again.
        ItemStack made = VillageItemOwnership.produce(new ItemStack(seed, seeds), village);
        // Checked before the fruit is taken: addItem is all-or-nothing, so
        // with room confirmed it cannot fail and no fruit is ever lost.
        if (made.isEmpty() || inventory.capacityFor(made) < seeds) return 0;
        remove(inventory, recipe.fruit(), fruitUsed);
        inventory.addItem(made);
        return seeds;
    }

    private static int fruitNeeded(Recipe recipe, int carriedSeeds) {
        int missing = Math.max(0, FarmerInventory.PLANTING_RESERVE_PER_CROP - carriedSeeds);
        return (missing + recipe.seedsPerFruit() - 1) / recipe.seedsPerFruit();
    }

    private static int count(InventoryManager inventory, Item item) {
        int count = 0;
        for (ItemStack stack : inventory.getSlots()) {
            if (inventory.mayUse(stack) && stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static void remove(InventoryManager inventory, Item item, int amount) {
        for (int i = 0; i < InventoryManager.SIZE && amount > 0; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (!inventory.mayUse(stack) || !stack.is(item)) continue;
            int taken = Math.min(amount, stack.getCount());
            inventory.reduceSlot(i, taken);
            amount -= taken;
        }
    }

    private static int storageTotal(ServerLevel level, VillageManager village, Item item) {
        int total = 0;
        for (Building building : village.getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid())
                total += storage.countItem(level, item);
        }
        return total;
    }

    @Nullable
    private static StorageBuilding nearestStorageWith(ServerLevel level, VillageManager village,
                                                      NpcEntity farmer, Item item) {
        StorageBuilding nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()
                    || storage.getFunctionalBlocks().isEmpty()
                    || storage.countItem(level, item) <= 0) continue;
            double distance = storage.getDoorPos().distSqr(farmer.blockPosition());
            if (distance < nearestDistance) {
                nearest = storage;
                nearestDistance = distance;
            }
        }
        return nearest;
    }
}
