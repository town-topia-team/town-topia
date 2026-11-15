package com.alex.test.village.economy.away;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.MealLeftovers;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.stats.DietMemory;
import com.alex.test.village.stats.FoodCatalog;
import com.alex.test.village.stats.HungerSystem;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.tokens.VillageFood;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.cook.CookBonuses;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.tradesman.behavior.TradesmanActivity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One meal time while away, for the whole village at once: who wants to eat,
 * what each may eat, shared out in turns ({@link MealShare}), and what eating
 * does to the copies. Runs with the book open and every eater's copy swapped
 * into its body ({@link AwayHealth}).
 *
 * <p>The live rules ({@code EatActivity}, {@code FetchFromStorageActivity},
 * {@code StorageSupplyRestocker}, {@code BakeBreadActivity}): a proper meal
 * (breakfast, lunch, dinner) below 75 hunger fills to 90, a snack below 30
 * and the bite before bed below 55 fill to 60; its own pack first, a tradesman's sell box goods are not its to
 * eat; from storage only what the village may use and the Stock tab does not
 * hold back; raw meat and fish are the cook's while one works with a stove,
 * unless storage has nothing else; with no food left anywhere, a villager
 * bakes up to 3 loaves from wheat if a storage room has a crafting table. The
 * meal's happiness, the diet memory and the bowl back are as live; a chair,
 * the place and a dessert give nothing away (my call, 10-22).
 */
final class AwayMeals {

    /** Loaves one baking makes at most, as {@code BakeBreadActivity}. */
    private static final int LOAVES_PER_BAKE = 3;

    /** Which meal: who eats, and up to what. */
    enum Kind {
        /** Breakfast, lunch, dinner: below 75, up to 90. */
        PROPER,
        /** Between meals: below 30, up to 60. */
        SNACK,
        /** Before lying down ({@code PreTaskHungerActivity}): below 55, up to 60. */
        BEDTIME
    }

    /** What the meals of a day did, for the day line and the summary. */
    static final class Tally {
        int fromPacks;
        int fromStorage;
        int baked;
        float hungerRestored;
        /** Items eaten, and wheat baked into bread. */
        final Map<Item, Integer> used = new LinkedHashMap<>();

        int servings() { return fromPacks + fromStorage; }
    }

    /** Where one {@link MealShare.Food} is, to take a serving of it. */
    private record Source(ItemStack like, FoodProperties props, int packSlot,
                          @Nullable StorageInventory chest, int chestSlot) {}

    /** One eater's meal so far: what {@code EatActivity} keeps per meal. */
    private static final class Plate {
        final boolean starving;
        final Set<Item> served = new HashSet<>();
        @Nullable Item mainDish;
        boolean mainCounted;
        boolean hadSpoiled;
        boolean starvingCounted;
        float joy;

        Plate(boolean starving) {
            this.starving = starving;
        }
    }

    private AwayMeals() {}

    /**
     * Serves one meal time. {@code day} is the village day number the meal's
     * happiness counts on (the daily allowance).
     */
    static void serve(ServerLevel level, VillageManager mgr, AwayBook book, List<AwayEater> eaters,
                      Kind kind, long day, Tally tally) {
        boolean properMeal = kind == Kind.PROPER;
        int n = eaters.size();
        float[] hunger = new float[n];
        float[] target = new float[n];
        int[] rationedLeft = new int[n];
        int[] plate = new int[n];
        Plate[] plates = new Plate[n];
        boolean anyone = false;
        for (int i = 0; i < n; i++) {
            AwayEater eater = eaters.get(i);
            VillagerStats needs = eater.needs;
            hunger[i] = needs.hunger;
            rationedLeft[i] = eater.rationedLeft;
            boolean wants = switch (kind) {
                case PROPER -> needs.hunger < HungerSystem.MEAL_TIME_HUNGER;
                case SNACK -> needs.hunger < HungerSystem.SNACK_HUNGER;
                case BEDTIME -> needs.hungerRules().needsSleepPreparation(needs);
            };
            target[i] = !wants ? -1f
                    : properMeal ? HungerSystem.PROPER_MEAL_TARGET : needs.hungerRules().mealTargetHunger();
            if (!wants) continue;
            anyone = true;
            needs.diet.ensureRolled(eater.npc.getRandom());
            plates[i] = new Plate(needs.hungerRules().isCritical(needs));
        }
        if (!anyone) return;

        long gameTime = level.getGameTime();
        boolean cookWaits = CookActivity.cookWaitsForRawFood(mgr, gameTime);
        boolean rawForCook = !cookWaits;
        boolean baked = false;
        MealShare.Taste taste = (i, food, h) -> {
            Source source = (Source) food.source;
            // A starving villager eats the most filling food first; spoiled food always last.
            if (plates[i].starving) return food.fill - (food.spoiled ? 1000f : 0f);
            return FoodCatalog.preference(source.like(), source.props(), eaters.get(i).needs.diet);
        };
        while (true) {
            List<MealShare.Food> foods = foods(level, mgr, book, eaters, target, rawForCook, gameTime);
            for (MealShare.Serving serving : MealShare.serve(hunger, target, rationedLeft, plate, foods, taste)) {
                eat(level, mgr, book, eaters.get(serving.eater()), plates[serving.eater()], serving.food(),
                        properMeal, day, gameTime, tally);
            }
            book.changed();
            if (sharedLeft(foods) || !unfed(hunger, target)) break;
            // Storage has nothing else: the cook's raw meat after all.
            if (!rawForCook) {
                rawForCook = true;
                continue;
            }
            // No food anywhere: bread from the village's wheat, once a meal.
            if (baked || !bakeForUnfed(level, mgr, book, eaters, hunger, target, foods, tally)) break;
            baked = true;
        }
        for (int i = 0; i < n; i++) eaters.get(i).rationedLeft = rationedLeft[i];
    }

    // =========================================================================
    // What each may eat
    // =========================================================================

    /** Every serving the eaters who want to eat may take: their packs, then the storage copies. */
    private static List<MealShare.Food> foods(ServerLevel level, VillageManager mgr, AwayBook book,
                                              List<AwayEater> eaters, float[] target, boolean rawForCook,
                                              long gameTime) {
        List<MealShare.Food> foods = new ArrayList<>();
        VillageTokenBinding owner = VillageTokenBinding.of(mgr);
        for (int i = 0; i < eaters.size(); i++) {
            if (target[i] < 0f) continue;
            NpcEntity npc = eaters.get(i).npc;
            InventoryManager pack = npc.getInventory();
            int[] aside = setAside(npc, mgr, gameTime);
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                int free = stack.getCount() - aside[slot];
                if (free <= 0) continue;
                FoodProperties props = VillageItemOwnership.villagerFood(stack, npc);
                if (props == null || !VillageItemOwnership.mayUse(stack, owner)) continue;
                foods.add(food(i, stack, props, free, new Source(stack.copyWithCount(1), props, slot, null, -1),
                        mgr, gameTime));
            }
        }
        // The Stock tab's hold, for any taker (my call: food held for the cook's work is not a meal),
        // and the player's orders cooked while away (AwayBook.holdAway).
        Map<Item, Integer> mayTake = new HashMap<>();
        for (AwayBook.BookChest chest : book.chests()) {
            for (int slot = 0; slot < chest.size(); slot++) {
                ItemStack stack = chest.peek(slot);
                if (stack.isEmpty() || !VillageItemOwnership.isVillagerFood(stack)) continue;
                if (!rawForCook && CookActivity.isRawForCook(stack.getItem())) continue;
                FoodProperties props = VillageItemOwnership.villagerFood(stack, null);
                if (props == null || !VillageItemOwnership.mayUse(stack, owner)) continue;
                int left = mayTake.computeIfAbsent(stack.getItem(), item -> book.free(level, mgr, item, null));
                int count = Math.min(left, stack.getCount());
                if (count <= 0) continue;
                mayTake.put(stack.getItem(), left - count);
                foods.add(food(MealShare.SHARED, stack, props, count,
                        new Source(stack.copyWithCount(1), props, -1, chest, slot), mgr, gameTime));
            }
        }
        return foods;
    }

    private static MealShare.Food food(int owner, ItemStack stack, FoodProperties props, int count, Source source,
                                       VillageManager mgr, long gameTime) {
        boolean spoiled = FoodCatalog.tier(stack, props) == FoodCatalog.Tier.SPOILED;
        return new MealShare.Food(owner, fill(stack, props, mgr, gameTime),
                FoodCatalog.isRationed(stack.getItem()), spoiled, count, source);
    }

    /** Hunger one serving restores, as {@code EatActivity.servingFill}. */
    private static float fill(ItemStack stack, FoodProperties props, VillageManager mgr, long gameTime) {
        float fill = HungerSystem.hungerRestoredByFood(props) * FoodCatalog.fillShare(stack, props);
        if (VillageFood.isChefMade(stack)) {
            fill += CookBonuses.nutrition(mgr, gameTime) * HungerSystem.NUTRITION_TO_HUNGER;
        }
        return fill;
    }

    /**
     * A tradesman's sell box goods in its pack, slot by slot: not its to eat (as {@code EatActivity.setAside}).
     * A rancher's carrots for the pigs, while it carries other food.
     */
    private static int[] setAside(NpcEntity npc, VillageManager mgr, long gameTime) {
        if (npc.getProfession() == Profession.RANCHER) {
            return com.alex.test.village.villagers.rancher.RancherFeedReserve.heldBackAtMeals(npc);
        }
        InventoryManager pack = npc.getInventory();
        int[] aside = new int[InventoryManager.SIZE];
        if (npc.getProfession() != Profession.TRADESMAN) return aside;
        Map<Item, Integer> left = new HashMap<>(TradesmanActivity.sellBoxHoldings(npc, gameTime));
        if (left.isEmpty()) return aside;
        for (int slot = 0; slot < aside.length; slot++) {
            ItemStack stack = pack.getSlot(slot);
            int forBox = left.getOrDefault(stack.getItem(), 0);
            if (forBox <= 0 || stack.isEmpty() || !VillageItemOwnership.mayTrade(stack, mgr)) continue;
            aside[slot] = Math.min(forBox, stack.getCount());
            left.put(stack.getItem(), forBox - aside[slot]);
        }
        return aside;
    }

    private static boolean sharedLeft(List<MealShare.Food> foods) {
        for (MealShare.Food food : foods) {
            if (food.owner == MealShare.SHARED && food.count > 0) return true;
        }
        return false;
    }

    private static boolean unfed(float[] hunger, float[] target) {
        for (int i = 0; i < hunger.length; i++) {
            if (target[i] >= 0f && hunger[i] < target[i]) return true;
        }
        return false;
    }

    // =========================================================================
    // Eating one serving
    // =========================================================================

    private static void eat(ServerLevel level, VillageManager mgr, AwayBook book, AwayEater eater, Plate plate,
                            MealShare.Food food, boolean properMeal, long day, long gameTime, Tally tally) {
        Source source = (Source) food.source;
        ItemStack meal;
        if (source.chest() == null) {
            InventoryManager pack = eater.npc.getInventory();
            ItemStack slot = pack.getSlot(source.packSlot());
            if (!ItemStack.isSameItemSameComponents(slot, source.like())) return;
            meal = slot.copyWithCount(1);
            pack.reduceSlot(source.packSlot(), 1);
            tally.fromPacks++;
        } else {
            ItemStack slot = source.chest().peek(source.chestSlot());
            if (!ItemStack.isSameItemSameComponents(slot, source.like())) return;
            meal = source.chest().take(source.chestSlot(), 1);
            if (meal.isEmpty()) return;
            tally.fromStorage++;
        }
        VillagerStats needs = eater.needs;
        float before = needs.hunger;
        needs.onFoodEaten(food.fill, 0f);
        tally.hungerRestored += needs.hunger - before;
        tally.used.merge(meal.getItem(), 1, Integer::sum);
        if (plate.mainDish == null) plate.mainDish = meal.getItem();
        joy(eater, plate, meal, source.props(), properMeal, day, mgr, gameTime);
        bowlBack(level, mgr, book, eater.npc, meal);
    }

    /**
     * One serving's happiness, as {@code EatActivity.applyMealJoy} without the
     * chair, the place and the memories: taste (the cook's bonus on its dishes,
     * a second serving of the same food halved), variety and likes on the main
     * dish, feeling sick, starting the meal starving, the meal's range and the
     * daily allowance.
     */
    private static void joy(AwayEater eater, Plate plate, ItemStack meal, FoodProperties props, boolean properMeal,
                            long day, VillageManager mgr, long gameTime) {
        DietMemory diet = eater.needs.diet;
        Item item = meal.getItem();
        boolean spoiled = FoodCatalog.tier(meal, props) == FoodCatalog.Tier.SPOILED;
        float joy = FoodCatalog.taste(meal, props);
        if (VillageFood.isChefMade(meal)) joy += CookBonuses.taste(mgr, gameTime, item);
        if (!plate.served.add(item) && joy > 0f) joy *= FoodCatalog.REPEAT_SERVING_FACTOR;
        if (item == plate.mainDish && !plate.mainCounted) {
            plate.mainCounted = true;
            float variety = properMeal ? FoodCatalog.varietyJoy(meal, diet) : 0f;
            joy += variety + FoodCatalog.likingJoy(meal, diet);
            if (!spoiled) joy = Math.max(joy, FoodCatalog.TASTE_FLOOR);
            if (properMeal) diet.record(item);
        }
        if (eater.npc.getRandom().nextFloat() < FoodCatalog.sickChance(meal, props)) joy += FoodCatalog.SICK_JOY;
        if (plate.starving && !plate.starvingCounted) {
            plate.starvingCounted = true;
            joy += FoodCatalog.STARVING_MEAL_JOY;
        }
        if (spoiled) plate.hadSpoiled = true;
        float floor = plate.hadSpoiled ? FoodCatalog.MEAL_JOY_MIN : FoodCatalog.POOR_MEAL_MIN;
        float allowed = Math.clamp(plate.joy + joy, Math.min(floor, plate.joy), FoodCatalog.MEAL_JOY_MAX) - plate.joy;
        plate.joy += allowed;
        eater.needs.onFoodJoy(allowed, day);
    }

    /** The bowl or bottle back into the pack (every third breaks); no room: into storage. */
    private static void bowlBack(ServerLevel level, VillageManager mgr, AwayBook book, NpcEntity npc, ItemStack meal) {
        ItemStack back = MealLeftovers.leftover(npc, meal, mgr);
        if (back.isEmpty() || npc.getInventory().addItem(back)) return;
        for (StorageBuilding room : book.storageRooms()) {
            if (back.isEmpty()) break;
            room.depositItems(level, back);
        }
    }

    // =========================================================================
    // Bread when there is no food
    // =========================================================================

    /**
     * Each eater still hungry with nothing in its pack bakes up to
     * {@link #LOAVES_PER_BAKE} loaves into its pack, from wheat it carries,
     * then from storage (any room; live takes it from the room with the
     * table). Needs a crafting table in a storage room. True when any bread
     * was baked.
     */
    private static boolean bakeForUnfed(ServerLevel level, VillageManager mgr, AwayBook book, List<AwayEater> eaters,
                                        float[] hunger, float[] target, List<MealShare.Food> foods, Tally tally) {
        boolean table = false;
        for (StorageBuilding room : book.storageRooms()) {
            if (room.hasCraftingTable()) {
                table = true;
                break;
            }
        }
        if (!table) return false;
        VillageTokenBinding owner = VillageTokenBinding.of(mgr);
        int storageWheat = Math.min(book.free(level, mgr, Items.WHEAT, null), bookWheat(book, owner));
        boolean any = false;
        for (int i = 0; i < eaters.size(); i++) {
            if (target[i] < 0f || hunger[i] >= target[i] || hasOwnFood(foods, i)) continue;
            InventoryManager pack = eaters.get(i).npc.getInventory();
            int carried = packWheat(pack);
            ItemStack loaf = VillageFood.villagerMade(Items.BREAD, 1, mgr);
            int loaves = Math.min(LOAVES_PER_BAKE, (carried + storageWheat) / CookActivity.WHEAT_PER_BREAD);
            loaves = Math.min(loaves, pack.capacityFor(loaf));
            if (loaves <= 0) continue;
            int wheat = loaves * CookActivity.WHEAT_PER_BREAD;
            int fromPack = Math.min(carried, wheat);
            takePackWheat(pack, fromPack);
            takeBookWheat(book, owner, wheat - fromPack);
            storageWheat -= wheat - fromPack;
            pack.addItem(loaf.copyWithCount(loaves));
            tally.baked += loaves;
            tally.used.merge(Items.WHEAT, wheat, Integer::sum);
            any = true;
        }
        return any;
    }

    private static boolean hasOwnFood(List<MealShare.Food> foods, int eater) {
        for (MealShare.Food food : foods) {
            if (food.owner == eater && food.count > 0) return true;
        }
        return false;
    }

    private static int packWheat(InventoryManager pack) {
        int wheat = 0;
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = pack.getSlot(slot);
            if (stack.is(Items.WHEAT) && pack.mayUse(stack)) wheat += stack.getCount();
        }
        return wheat;
    }

    private static void takePackWheat(InventoryManager pack, int amount) {
        for (int slot = 0; slot < InventoryManager.SIZE && amount > 0; slot++) {
            ItemStack stack = pack.getSlot(slot);
            if (!stack.is(Items.WHEAT) || !pack.mayUse(stack)) continue;
            int take = Math.min(amount, stack.getCount());
            pack.reduceSlot(slot, take);
            amount -= take;
        }
    }

    private static int bookWheat(AwayBook book, VillageTokenBinding owner) {
        int wheat = 0;
        for (AwayBook.BookChest chest : book.chests()) {
            for (int slot = 0; slot < chest.size(); slot++) {
                ItemStack stack = chest.peek(slot);
                if (stack.is(Items.WHEAT) && VillageItemOwnership.mayUse(stack, owner)) wheat += stack.getCount();
            }
        }
        return wheat;
    }

    private static void takeBookWheat(AwayBook book, VillageTokenBinding owner, int amount) {
        for (AwayBook.BookChest chest : book.chests()) {
            for (int slot = 0; slot < chest.size() && amount > 0; slot++) {
                ItemStack stack = chest.peek(slot);
                if (!stack.is(Items.WHEAT) || !VillageItemOwnership.mayUse(stack, owner)) continue;
                amount -= chest.take(slot, Math.min(amount, stack.getCount())).getCount();
            }
        }
    }
}
