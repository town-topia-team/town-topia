package com.alex.test.village.villagers.supplies;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.cook.settings.CookSettings;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Gold ingots the village wants and does not have: what the blacksmith
 * smelts the miner's raw gold for.
 *
 * <p>Only the cook uses gold (golden carrots and apples). So the smith
 * smelts raw gold only while a cook at work has them allowed and its shelf is
 * short, plus the gold the cook leaves alone ("Gold to leave for me"), since
 * golden food only uses the gold above that. With no cook wanting golden
 * food the raw gold stays raw and no fuel is burned for it.
 */
public final class GoldNeeds {

    /** Golden carrots a shelf is smelted for at a time: one ingot each. */
    private static final int MAX_CARROTS = 8;
    /** Golden apples a shelf is smelted for at a time: eight ingots each. */
    private static final int MAX_APPLES = 2;
    private static final long CACHE_TICKS = 100;

    private record Count(long gameTime, int wanted) {}

    private static final Map<VillageManager, Count> CACHE = new WeakHashMap<>();

    private GoldNeeds() {}

    /** Gold ingots still wanted: the cooks' reserve and golden dishes, less what the village holds. */
    public static int ingotsWanted(VillageManager village, ServerLevel level) {
        long now = level.getGameTime();
        Count cached = CACHE.get(village);
        if (cached != null && now >= cached.gameTime() && now - cached.gameTime() < CACHE_TICKS) {
            return cached.wanted();
        }
        int wanted = count(village, level);
        CACHE.put(village, new Count(now, wanted));
        return wanted;
    }

    /** Counts again on the next question: an ingot was just smelted. */
    public static void forget(VillageManager village) {
        CACHE.remove(village);
    }

    private static int count(VillageManager village, ServerLevel level) {
        int ingots = 0;
        int carrots = 0;
        int apples = 0;
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            ingots += storage.countItem(level, Items.GOLD_INGOT);
            carrots += storage.countItem(level, Items.GOLDEN_CARROT);
            apples += storage.countItem(level, Items.GOLDEN_APPLE);
        }
        int target = 0;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive()) continue;
            InventoryManager inv = npc.getInventory();
            if (inv != null) {
                for (int i = 0; i < InventoryManager.SIZE; i++) {
                    ItemStack stack = inv.getSlot(i);
                    if (stack.is(Items.GOLD_INGOT) && inv.mayUse(stack)) ingots += stack.getCount();
                }
            }
            if (npc.getProfession() != Profession.COOK) continue;
            CookSettings settings = CookSettings.of(npc);
            if (!settings.works()) continue;
            int dishes = shortOf(settings, Items.GOLDEN_CARROT, carrots, MAX_CARROTS)
                    + shortOf(settings, Items.GOLDEN_APPLE, apples, MAX_APPLES) * 8;
            // Golden food only uses gold above the reserve; no dish wanted, no reserve to fill.
            if (dishes > 0) target = Math.max(target, settings.goldReserve() + dishes);
        }
        // Stock tab orders for golden food hold their gold too, reserve or not.
        target += StockReserves.get(level.getServer()).heldFor(village, level, Profession.COOK)
                .getOrDefault(Items.GOLD_INGOT, 0);
        return Math.max(0, target - ingots);
    }

    /** How many of {@code dish} the cook's shelf lacks, at most {@code cap}; 0 when it may not make it. */
    private static int shortOf(CookSettings settings, Item dish, int stored, int cap) {
        if (!settings.allowsRecipe(dish)) return 0;
        int target = settings.stockTarget(dish);
        if (target <= 0) return 0;
        return Math.min(cap, Math.max(0, target - stored));
    }
}
