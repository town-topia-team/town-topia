package com.alex.test.village.villagers.supplies;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.stats.DietMemory;
import com.alex.test.village.stats.FoodCatalog;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.RancherFeedReserve;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import com.alex.test.village.villagers.tools.ToolUpgradeHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared restocking for food, profession tools, and future supplies during storage visits. */
public final class StorageSupplyRestocker {

    private StorageSupplyRestocker() {}

    public static int restockAfterStorageVisit(Activity.Context ctx, StorageBuilding storage) {
        return restockAfterStorageVisit(ctx, storage, null);
    }

    public static int restockAfterStorageVisit(
            Activity.Context ctx,
            StorageBuilding storage,
            @Nullable ProfessionToolRequirement skippedToolRequirement) {
        if (storage == null) return 0;
        returnUnusableFood(ctx, storage);
        int moved = restockTools(ctx, storage, skippedToolRequirement);
        moved += restockFood(ctx, storage);
        return moved;
    }

    /**
     * Food in the pack the villager may not eat (a player's, picked up before
     * villagers only took plain crops from players, or another village's)
     * goes into the storage room, or on the floor when it is full: kept, it
     * held slots for good and the villager could not fetch real food.
     */
    private static void returnUnusableFood(Activity.Context ctx, StorageBuilding storage) {
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (stack.isEmpty() || inv.mayUse(stack)) continue;
            // raw food data: food a villager may not eat (a player's, the ominous bottle) goes back.
            if (stack.get(net.minecraft.core.component.DataComponents.FOOD) == null
                    && !com.alex.test.village.tokens.VillageFood.isDish(stack.getItem())) continue;
            ItemStack out = stack.copy();
            String name = out.getCount() + "x " + out.getItem().getDescriptionId();
            inv.reduceSlot(i, stack.getCount());
            String where = "in storage";
            if (!storage.returnUnusable(ctx.level(), out)) {
                where = "on the floor (storage full)";
                net.minecraft.world.Containers.dropItemStack(ctx.level(),
                        ctx.npc().getX(), ctx.npc().getY() + 0.5, ctx.npc().getZ(), out);
            }
            ctx.brain().diary().log(String.format("%s supplies  put back %s it may not eat, %s",
                    VillagerDiary.ts(ctx.level()), name, where));
        }
    }

    /**
     * Fill the carried food reserve up to {@link ProfessionSupplyRules#FOOD_TARGET}
     * from anywhere in {@code storage}.
     *
     * <p>One food type is preferred: first more of what the villager already
     * carries, then whatever the storage holds most of. Only when that type
     * runs out does it switch to the next, and never so far that the villager
     * carries more than {@link #MAX_FOOD_TYPES} different foods.
     */
    public static int restockFood(Activity.Context ctx, StorageBuilding storage) {
        int currentFood = countCarriedFood(ctx);
        int needed = ProfessionSupplyRules.FOOD_TARGET - currentFood;
        if (needed <= 0) return 0;

        // Same rule as FetchFromStorageActivity: what the Stock tab holds back
        // (the player's dishes, ingredients for its rows) is not a meal.
        StockReserves reserves = StockReserves.get(ctx.level().getServer());
        Map<Item, Integer> onShelves = storage.countResourceByItem(
                ctx.level(), StorageBuilding.ResourceType.FOOD,
                item -> reserves.free(ctx.mgr(), ctx.level(), item, ctx.npc().getProfession()) <= 0);
        if (onShelves.isEmpty()) return 0;
        // Raw meat and fish are the cook's to cook while one works with a
        // station for them. Only when storage has nothing else does a
        // villager take them anyway.
        if (ctx.mgr() != null && com.alex.test.village.villagers.cook.behavior.CookActivity
                .cookWaitsForRawFood(ctx.mgr(), ctx.level().getGameTime())) {
            Map<Item, Integer> cooked = new HashMap<>(onShelves);
            cooked.keySet().removeIf(com.alex.test.village.villagers.cook.behavior.CookActivity::isRawForCook);
            if (!cooked.isEmpty()) onShelves = cooked;
        }
        // A rancher's carrots are the pigs' (RancherFeedReserve), fetched on
        // purpose like the others' wheat. Only with nothing else on the
        // shelves does it take some as lunch.
        Set<Item> feeds = RancherFeedReserve.feeds(ctx.npc());
        if (!feeds.isEmpty()) {
            Map<Item, Integer> lunch = new HashMap<>(onShelves);
            lunch.keySet().removeAll(feeds);
            if (!lunch.isEmpty()) onShelves = lunch;
        }
        Map<Item, Integer> stocked = onShelves;

        Map<Item, Integer> carried = carriedFoodByType(ctx);
        List<Item> order = new ArrayList<>();
        carried.entrySet().stream()
                .filter(e -> stocked.containsKey(e.getKey()))
                .sorted(Map.Entry.<Item, Integer>comparingByValue().reversed())
                .forEach(e -> order.add(e.getKey()));
        // Poor food last: a chest of zombie drops outnumbers the bread but
        // should not become the reserve while anything better is on offer.
        // Then what the villager would most like to eat next (taste, variety,
        // its likes), and only then whatever there is most of.
        DietMemory diet = ctx.brain().needs().diet;
        diet.ensureRolled(ctx.npc().getRandom());
        stocked.entrySet().stream()
                .filter(e -> !carried.containsKey(e.getKey()))
                .sorted(Comparator.<Map.Entry<Item, Integer>>comparingInt(
                                e -> POOR_FOOD.contains(e.getKey()) ? 1 : 0)
                        .thenComparing(e -> -(FoodCatalog.baseTaste(e.getKey())
                                + diet.adjustment(e.getKey())))
                        .thenComparing(Map.Entry.<Item, Integer>comparingByValue().reversed()))
                .forEach(e -> order.add(e.getKey()));
        int eaters = Math.max(1, ctx.mgr() != null ? ctx.mgr().getVillagers().size() : 1);

        // Spread the reserve over up to three foods so meals can vary: first
        // an even share of each (16 over three types is six each), then the
        // rest from whichever of those still has stock.
        int typesHeld = Math.min(MAX_FOOD_TYPES, Math.max(1, (int) order.stream()
                .filter(item -> carried.containsKey(item) || stocked.getOrDefault(item, 0) > 0)
                .count()));
        typesHeld = Math.max(typesHeld, Math.min(MAX_FOOD_TYPES, carried.size()));
        int perType = (ProfessionSupplyRules.FOOD_TARGET + typesHeld - 1) / typesHeld;
        Map<Item, Integer> held = new HashMap<>(carried);
        int newTypesAllowed = Math.max(0, MAX_FOOD_TYPES - carried.size());
        int moved = 0;
        for (int pass = 0; pass < 2 && needed > 0; pass++) {
            for (Item item : order) {
                if (needed <= 0) break;
                boolean newType = !held.containsKey(item);
                // The second pass only tops up foods already chosen.
                if (newType && (pass == 1 || newTypesAllowed <= 0)) continue;
                int limit = Math.min(needed, reserves.free(ctx.mgr(), ctx.level(), item, ctx.npc().getProfession()));
                if (pass == 0) limit = Math.min(limit, Math.max(0, perType - held.getOrDefault(item, 0)));
                // Pie, cake, rabbit stew and golden food: a fair share each, so the
                // first villager at the chest does not walk off with all of it.
                if (FoodCatalog.isRationed(item)) {
                    int share = Math.max(1, (stocked.get(item) + eaters - 1) / eaters);
                    limit = Math.min(limit, Math.max(0, share - held.getOrDefault(item, 0)));
                }
                if (limit <= 0) continue;
                int took = withdrawFood(ctx, storage, item, limit);
                if (took <= 0) continue;
                moved += took;
                needed -= took;
                held.merge(item, took, Integer::sum);
                if (newType) newTypesAllowed--;
            }
        }
        if (moved == 0) return 0;

        ctx.brain().diary().log(String.format(
                "%s supplies  restocked %dx food from storage (now carrying %d)",
                VillagerDiary.ts(ctx.level()), moved, currentFood + moved));
        return moved;
    }

    /**
     * A farmer keeps its own crops as lunch between storage visits (see
     * {@code DepositActivity}), so it never walks to storage only to bank
     * carrots and take carrots back. At the chest anyway, it would rather eat
     * something better: each own crop that storage has tastier food than
     * (to this villager's taste, poor food never counts) goes in, and
     * {@link #restockFood} then fills the lunch from storage as usual. Other
     * own crops are not counted as better: their taste shifts with what was
     * eaten lately, and the farmer would swap them back and forth.
     *
     * @return items handed in
     */
    public static int handInOwnCropsForBetterFood(Activity.Context ctx, StorageBuilding storage) {
        NpcEntity npc = ctx.npc();
        if (npc.getProfession() != Profession.FARMER || storage == null) return 0;
        InventoryManager inventory = npc.getInventory();
        Set<Item> ownCrops = new LinkedHashSet<>();
        for (ItemStack stack : inventory.getSlots()) {
            if (isOwnCropLunch(npc, inventory, stack)) ownCrops.add(stack.getItem());
        }
        if (ownCrops.isEmpty()) return 0;

        StockReserves reserves = StockReserves.get(ctx.level().getServer());
        Map<Item, Integer> stocked = storage.countResourceByItem(
                ctx.level(), StorageBuilding.ResourceType.FOOD,
                item -> reserves.free(ctx.mgr(), ctx.level(), item, npc.getProfession()) <= 0);
        DietMemory diet = ctx.brain().needs().diet;
        diet.ensureRolled(npc.getRandom());
        float bestInStorage = Float.NEGATIVE_INFINITY;
        for (Item item : stocked.keySet()) {
            if (POOR_FOOD.contains(item)
                    || ProfessionSupplyRules.isProfessionProduct(npc, new ItemStack(item))) continue;
            bestInStorage = Math.max(bestInStorage, FoodCatalog.baseTaste(item) + diet.adjustment(item));
        }

        int moved = 0;
        for (Item crop : ownCrops) {
            if (FoodCatalog.baseTaste(crop) + diet.adjustment(crop) >= bestInStorage) continue;
            int handedIn = 0;
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = inventory.getSlot(slot);
                if (!stack.is(crop) || !isOwnCropLunch(npc, inventory, stack)) continue;
                int carried = stack.getCount();
                boolean allFit = storage.depositItems(ctx.level(), stack);
                handedIn += carried - stack.getCount();
                if (allFit) inventory.setSlot(slot, ItemStack.EMPTY);
            }
            if (handedIn <= 0) continue;
            moved += handedIn;
            ctx.brain().diary().log(String.format(
                    "%s supplies  handed in %dx %s for tastier food",
                    VillagerDiary.ts(ctx.level()), handedIn, crop.getDescriptionId()));
        }
        return moved;
    }

    /** One of the farmer's own crops, carried as food and not kept as seed. */
    private static boolean isOwnCropLunch(NpcEntity npc, InventoryManager inventory, ItemStack stack) {
        return !stack.isEmpty() && inventory.mayUse(stack) && VillageItemOwnership.villagerFood(stack, npc) != null
                && ProfessionSupplyRules.isProfessionProduct(npc, stack)
                && !com.alex.test.village.villagers.farmer.FarmerInventory.isKeptSupply(npc, stack);
    }

    /** Most different foods a villager's reserve is spread over. */
    private static final int MAX_FOOD_TYPES = 3;

    /** Edible, but only worth carrying when nothing better is stocked. */
    private static final Set<Item> POOR_FOOD = Set.of(
            Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH);

    /** Up to {@code max} of {@code item} from any chest in the storage, straight into the inventory. */
    private static int withdrawFood(Activity.Context ctx, StorageBuilding storage, Item item, int max) {
        InventoryManager inventory = ctx.npc().getInventory();
        ItemStack wanted = new ItemStack(item);
        int took = 0;
        for (BlockPos pos : storage.findChestsWithItem(ctx.level(), wanted)) {
            if (took >= max) break;
            // Size the withdrawal by the stack this chest will actually hand
            // out, not the plain sample: a chest can hold village-bound food,
            // which never stacks onto an unbound stack in the pack, so room
            // measured for the sample overstated it and addItem refused the lot.
            ItemStack first = storage.peekItemFromChest(ctx.level(), pos, wanted);
            if (first.isEmpty()) continue;
            int room = Math.min(max - took, inventory.capacityFor(first));
            // Another chest may hold a kind of this food that does fit.
            if (room <= 0) continue;
            ItemStack taken = storage.withdrawItemFromChest(ctx.level(), pos, wanted, room);
            if (taken.isEmpty()) continue;
            if (!inventory.addItem(taken)) {
                // Back into the chest it came from first: its deposit filter
                // may refuse the ordinary route even though it handed these out.
                storage.returnToChest(ctx.level(), pos, taken);
                if (!taken.isEmpty()) storage.depositItems(ctx.level(), taken);
                ItemLossLog.drop(ctx, taken, "food restock could not carry or return it (chest "
                        + pos.toShortString() + ")");
                break;
            }
            took += taken.getCount();
        }
        return took;
    }

    private static Map<Item, Integer> carriedFoodByType(Activity.Context ctx) {
        Map<Item, Integer> counts = new HashMap<>();
        InventoryManager inventory = ctx.npc().getInventory();
        int[] forAnimals = RancherFeedReserve.heldBack(ctx.npc());
        for (int slot = 0; slot < forAnimals.length; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            int lunch = stack.getCount() - forAnimals[slot];
            if (lunch > 0 && inventory.mayUse(stack) && VillageItemOwnership.villagerFood(stack, ctx.npc()) != null) {
                counts.merge(stack.getItem(), lunch, Integer::sum);
            }
        }
        return counts;
    }

    private static int restockTools(
            Activity.Context ctx,
            StorageBuilding storage,
            @Nullable ProfessionToolRequirement skippedToolRequirement) {
        int moved = 0;
        for (ProfessionToolRequirement requirement :
                ProfessionSupplyRules.toolRequirements(ctx.npc().getProfession())) {
            if (requirement == skippedToolRequirement) continue;
            moved += restockTool(ctx, storage, requirement);
        }
        return moved;
    }

    private static int restockTool(
            Activity.Context ctx,
            StorageBuilding storage,
            ProfessionToolRequirement requirement) {
        InventoryManager inventory = ctx.npc().getInventory();
        var allowedTools = requirement.allowedTools(ctx);
        if (allowedTools.isEmpty()) return 0;

        int currentSlot = ToolUpgradeHelper.workingToolSlot(ctx, requirement);

        if (currentSlot < 0) {
            // Tools promised to other villagers on their way here are left for them.
            ItemStack available = storage.peekToolOfTypes(
                    ctx.level(), requirement.toolTag(), allowedTools,
                    stack -> requirement.canPlaceAcquiredTool(ctx, stack),
                    requirement.quality(ctx), ctx.model().npcId);
            if (available.isEmpty()) return 0;
            ItemStack tool = storage.withdrawToolOfTypes(
                    ctx.level(), requirement.toolTag(), allowedTools,
                    stack -> requirement.canPlaceAcquiredTool(ctx, stack),
                    requirement.quality(ctx), ctx.model().npcId);
            if (tool.isEmpty()) return 0;
            if (!requirement.placeAcquiredTool(ctx, tool)) {
                storage.depositItems(ctx.level(), tool);
                ItemLossLog.drop(ctx, tool, "tool restock could not place or return it");
                return 0;
            }
            requirement.onAcquireSuccess(ctx);
            logTool(ctx, "took", tool);
            return 1;
        }

        // The old tool goes into this storage; the villager never keeps it.
        if (!ToolUpgradeHelper.upgradeAt(ctx, storage, requirement)) return 0;
        requirement.onAcquireSuccess(ctx);
        int working = ToolUpgradeHelper.workingToolSlot(ctx, requirement);
        if (working >= 0) logTool(ctx, "upgraded to", inventory.getSlot(working));
        return 1;
    }

    /**
     * Food items this villager carries and may eat: its personal reserve. A
     * rancher's feed for its animals (RancherFeedReserve) is not part of it.
     */
    public static int countCarriedFood(Activity.Context ctx) {
        InventoryManager inventory = ctx.npc().getInventory();
        int[] forAnimals = RancherFeedReserve.heldBack(ctx.npc());
        int count = 0;
        for (int slot = 0; slot < forAnimals.length; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (inventory.mayUse(stack)
                    && VillageItemOwnership.villagerFood(stack, ctx.npc()) != null) {
                count += stack.getCount() - forAnimals[slot];
            }
        }
        return count;
    }

    private static void logTool(Activity.Context ctx, String action, ItemStack tool) {
        ctx.brain().diary().log(String.format(
                "%s supplies  %s %s from storage",
                VillagerDiary.ts(ctx.level()), action, tool.getItem().getDescriptionId()));
    }
}
