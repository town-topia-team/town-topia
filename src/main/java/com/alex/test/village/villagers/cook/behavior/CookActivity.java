package com.alex.test.village.villagers.cook.behavior;

import com.alex.test.village.ClaimsRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.CraftingStation;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.FetchFromStorageActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.KitchenBuilding.HeatSource;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.cook.CookBalance;
import com.alex.test.village.villagers.cook.CookDemand;
import com.alex.test.village.villagers.cook.CookMoves;
import com.alex.test.village.villagers.cook.CookPlanner;
import com.alex.test.village.villagers.cook.CookPlanner.Pantry;
import com.alex.test.village.villagers.cook.CookPlanner.Plan;
import com.alex.test.village.villagers.cook.VillageKitchens;
import com.alex.test.village.villagers.cook.settings.CookSettings;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The Cook's working day: turn the village's raw food into meals, until the
 * shelves are stocked.
 *
 * <p>Every dish the cook can make is one row in {@link #RECIPES}, ordered
 * highest-nutrition first. What to make next is {@link CookPlanner}'s call:
 * only dishes storage holds fewer of than their stock target, chosen by the
 * cook's production mode. Two kinds of station exist: the crafting table, and
 * heat (smoker, furnace or campfire); a recipe declares which one it needs via
 * {@link Recipe#needsFurnace()}. Each station is worked by one cook at a time.
 */
public final class CookActivity extends WorkActivity {

    /** Wheat per loaf, as the vanilla recipe. */
    public static final int WHEAT_PER_BREAD = 3;

    /** Cook-made stews stack this high, where vanilla's stack to one. */
    private static final int STEW_STACK = 16;

    /**
     * One recipe batch. {@code needsFurnace} means "needs heat": a smoker,
     * furnace or campfire, whichever {@link CookPlanner#pickHeat} allows.
     */
    public record Recipe(Item result, int outputPerBatch,
                           Map<Item, Integer> inputsPerBatch, boolean needsFurnace,
                           int nutrition) {
    }

    private static Map<Item, Integer> ingredients(Object... pairs) {
        Map<Item, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Item) pairs[i], (Integer) pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    /**
     * The recipe table, best first.
     *
     * <p>Three tiers, in the order a cook should reach for them: rich dishes,
     * then roast meat, fish and stews, then plain staples. The old table put
     * bread above steak, so a village with a butcher ate loaves while the beef
     * sat in the chest. Within the top tier the gold-free dishes come first, so
     * gold is only ever touched when nothing else will do.
     *
     * <p>Each row carries the nutrition its output should have, which is
     * applied to the stack as a food component when it is made. That is what
     * makes cooking worth doing over eating the ingredients raw — and it is the
     * only way a cake is edible at all, since vanilla cake has no food data and
     * is eaten a slice at a time off a placed block. It is also why stews give
     * no bowl back: the replaced food component has no bowl to return.
     */
    private static final List<Recipe> RECIPES = List.of(
            // --- rich: worth a detour, and the reason to build an economy ---
            // Vanilla cake: 3 wheat + 2 sugar + 1 egg + 3 milk buckets. Milk
            // buckets are consumed outright — nothing in this codebase models
            // bucket return, the same simplification furnace fuel already makes.
            new Recipe(Items.CAKE, 1,
                    ingredients(Items.WHEAT, 3, Items.SUGAR, 2,
                            Items.EGG, 1, Items.MILK_BUCKET, 3), false, 13),
            // Vanilla rabbit stew wants cooked rabbit and a baked potato. Those
            // are the cook's own dishes, which it banks rather than keeps, so
            // here the pot goes on the heat with the raw ones instead.
            new Recipe(Items.RABBIT_STEW, 1,
                    ingredients(Items.RABBIT, 1, Items.CARROT, 1, Items.POTATO, 1,
                            Items.BROWN_MUSHROOM, 1, Items.BOWL, 1), true, 13),
            // Vanilla pumpkin pie: 1 pumpkin + 1 sugar + 1 egg.
            new Recipe(Items.PUMPKIN_PIE, 1,
                    ingredients(Items.PUMPKIN, 1, Items.SUGAR, 1, Items.EGG, 1), false, 12),
            // Vanilla: 1 carrot + 8 gold nuggets. Nuggets aren't a resource
            // this economy mines or stores (the miner/blacksmith chain produces
            // ingots), so 1 gold ingot stands in for the 8-nugget cost.
            new Recipe(Items.GOLDEN_CARROT, 1,
                    ingredients(Items.CARROT, 1, Items.GOLD_INGOT, 1), false, 12),
            new Recipe(Items.GOLDEN_APPLE, 1,
                    ingredients(Items.APPLE, 1, Items.GOLD_INGOT, 8), false, 13),
            // --- roast, fish and stews: what a working village lives on -----
            new Recipe(Items.COOKED_BEEF, 1, ingredients(Items.BEEF, 1), true, 10),
            new Recipe(Items.COOKED_PORKCHOP, 1, ingredients(Items.PORKCHOP, 1), true, 10),
            new Recipe(Items.COOKED_CHICKEN, 1, ingredients(Items.CHICKEN, 1), true, 9),
            new Recipe(Items.COOKED_MUTTON, 1, ingredients(Items.MUTTON, 1), true, 9),
            new Recipe(Items.COOKED_RABBIT, 1, ingredients(Items.RABBIT, 1), true, 9),
            // Vanilla: one of each mushroom + a bowl; 6 beetroot + a bowl.
            new Recipe(Items.MUSHROOM_STEW, 1,
                    ingredients(Items.RED_MUSHROOM, 1, Items.BROWN_MUSHROOM, 1,
                            Items.BOWL, 1), false, 9),
            new Recipe(Items.BEETROOT_SOUP, 1,
                    ingredients(Items.BEETROOT, 6, Items.BOWL, 1), false, 9),
            new Recipe(Items.COOKED_SALMON, 1, ingredients(Items.SALMON, 1), true, 8),
            new Recipe(Items.COOKED_COD, 1, ingredients(Items.COD, 1), true, 7),
            // --- staples: always available, always last --------------------
            new Recipe(Items.BREAD, 1, ingredients(Items.WHEAT, WHEAT_PER_BREAD), false, 7),
            // Vanilla baked potato: 1 potato, smelted.
            new Recipe(Items.BAKED_POTATO, 1, ingredients(Items.POTATO, 1), true, 7),
            // Vanilla cookie: 2 wheat + 1 cocoa bean -> 8 cookies.
            new Recipe(Items.COOKIE, 8,
                    ingredients(Items.WHEAT, 2, Items.COCOA_BEANS, 1), false, 4),
            new Recipe(Items.DRIED_KELP, 1, ingredients(Items.KELP, 1), true, 2));

    /** Ordered cooking rules shared by live work and offline reconciliation. */
    public static List<Recipe> recipes() {
        return RECIPES;
    }

    /**
     * Every item that is an ingredient of something the cook can make.
     *
     * <p>Exposed so the deposit rules can let a cook hold onto what it just
     * fetched. Without it the cook walks to the chest, takes one wheat, and the
     * next decision sees a depositable item and walks it straight back --
     * forever. That is the same trap the rancher hit with breeding wheat and
     * the lumberjack with its axe.
     */
    public static final Set<Item> INGREDIENTS = RECIPES.stream()
            .flatMap(recipe -> recipe.inputsPerBatch().keySet().stream())
            .collect(Collectors.toUnmodifiableSet());

    /** Every dish the cook makes: what it banks as its product. */
    private static final Set<Item> DISHES = RECIPES.stream()
            .map(Recipe::result)
            .collect(Collectors.toUnmodifiableSet());

    public static boolean isDish(ItemStack stack) {
        return DISHES.contains(stack.getItem());
    }

    @Nullable private Recipe recipe;
    /** The heat {@link #recipe} cooks on; null at the crafting table. */
    @Nullable private HeatSource heat;
    /** Batches the shelf wanted when this dish was picked. */
    private int plannedBatches;
    /** The storage room's Stock tab row {@link #recipe} is cooked for, or null. */
    @Nullable private StockReserves.Want order;
    /**
     * Items the fire in front of the cook can still smelt. A piece of fuel goes
     * in only when this drops below one, as a vanilla furnace would take it,
     * and whatever is left goes cold when the cook walks away.
     */
    private double fuelCredit;
    @Nullable private MoveActivity move;
    @Nullable private Activity sub;
    private boolean finishAfterSub;
    private int workTimer;
    private int itemsToProcess;
    private boolean blockedAtStation;
    @Nullable private BlockPos station;
    /** Where to stand and face for {@link #station}; holds the claim on it. */
    @Nullable private CraftingStation workStation;
    @Nullable private BlockPos stand;
    /** Walked up; taking the last step onto {@link #stand} before starting. */
    private boolean settling;
    /** Set while another villager is at the station and this one is waiting. */
    private boolean waiting;
    private boolean furnaceLit;
    private boolean workedThisSession;
    /** A mid-shift deposit ran and no batch has finished since. */
    private boolean depositedSinceLastBatch;
    /** The heat station a dish just finished on, so the next dish in hand for it goes on straight away. */
    @Nullable private HeatSource lastHeat;

    // The cook's moves (docs/plans/2026-10-24-cook-animations.md): only the
    // looks. A batch takes exactly as long as before; CookMoves lays its
    // moves inside it and tickProcessing starts each one on its tick.

    /** The batch under way's moves, in time order. May be read-only: walked by index, never changed. */
    private List<CookMoves.Piece> pieces = List.of();
    /** The next of {@link #pieces} to start. */
    private int nextPiece;
    /** Ticks the batch under way lasts, so its ticks can be counted from its start. */
    private int batchTicks;
    /** Batch tick the furnace block lights on (the fuel goes in during cook_fire_light); -1 for none. */
    private int lightTick = -1;
    /** The fuel the batch under way burned, shown in the fist while it goes in; null when none was. */
    @Nullable private Item burnedFuel;
    /** The batch under way ends with the dish held up (the last of a showpiece). */
    private boolean presenting;
    /** A batch has started this shift: only the first rolls its sleeves up. */
    private boolean shiftStarted;
    /** The last batch came out one extra: the next one opens with the chef's kiss. */
    private boolean kissNext;
    /** cook_wait_station is playing while this cook waits for a busy station. */
    private boolean waitClip;
    /** A waiting cook turns to the station only when it is this close, as it looks over at it ({@link CraftingStation}). */
    private static final double WAIT_FACE_DIST_SQ = 12.0 * 12.0;

    // =========================================================================
    // WorkActivity contract
    // =========================================================================

    @Override
    protected Status doTick(Context ctx) {
        if (!CookSettings.of(ctx.npc()).works() && workTimer <= 0 && sub == null) {
            return depositOrFinish(ctx);
        }

        if (sub != null) {
            Status status = sub.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            boolean wasDeposit = sub instanceof DepositActivity;
            sub.stop(ctx);
            sub = null;
            // The trip moved things in or out of storage.
            STORAGE_COUNTS.remove(ctx.mgr());
            if (finishAfterSub) return Status.DONE;
            if (status == Status.FAILED) {
                // chooseNext() would pick the same trip again next tick: storage
                // still lists the item, so a cook with a full inventory walked
                // to the chest, found nowhere to put the fetch, and repeated
                // that forever without ever reaching its deposit.
                diary(ctx, "COOK " + (wasDeposit ? "deposit" : "fetch") + " failed, ending shift");
                if (wasDeposit) return workedThisSession ? Status.DONE : Status.FAILED;
                return depositOrFinish(ctx);
            }
            return Status.RUNNING;
        }

        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED) {
                diary(ctx, "COOK could not reach station " + station);
                abandonRecipe(ctx);
                return depositOrFinish(ctx);
            }
            settling = true;
            return Status.RUNNING;
        }

        if (waiting) {
            // The kitchens read afresh: one may have gone up or come down.
            VillageKitchens kitchens = findKitchen(ctx);
            if (kitchens == null) {
                abandonRecipe(ctx);
                return depositOrFinish(ctx);
            }
            return startWalkToStation(ctx, kitchens);
        }

        if (settling) {
            if (workStation == null || stand == null || workStation.settle(ctx, stand)) {
                settling = false;
                beginProcessing(ctx);
            }
            return Status.RUNNING;
        }

        if (blockedAtStation) {
            blockedAtStation = false;
            return depositOrFinish(ctx);
        }

        if (workTimer > 0) return tickProcessing(ctx);

        return chooseNext(ctx);
    }

    @Override
    public void doStop(Context ctx) {
        if (sub != null) { sub.stop(ctx); sub = null; }
        if (move != null) { move.stop(ctx); move = null; }
        blockedAtStation = false;
        // Clears the prop too.
        abandonRecipe(ctx);
        extinguishFurnace(ctx);
        // A shrug (gave up waiting, out of fuel) ends itself: the shift often
        // ends on the same tick, and IDLE here would cut it off unseen.
        if (ctx.npc().getAnimation() != VillagerAnimationState.SHRUG) {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return move != null;
    }

    public boolean isProcessingBatch() {
        return workTimer > 0;
    }

    @Override
    public boolean allowsRoutineInterruption() {
        return workTimer <= 0;
    }

    // =========================================================================
    // Selection
    // =========================================================================

    private Status chooseNext(Context ctx) {
        VillageKitchens kitchen = findKitchen(ctx);
        if (kitchen == null) return depositOrFinish(ctx);

        // Bank the dishes before starting the next one. The cook used to
        // deposit only once storage ran dry, so a well-stocked kitchen filled
        // all nine slots with food and then had no room for the next fetch.
        // Once per batch at most: if the trip leaves the inventory full of
        // things that must be kept, the next fetch fails and ends the shift
        // instead of sending the cook back to the chest again.
        if (!depositedSinceLastBatch
                && (productsCarried(ctx) >= CookBalance.carryItems(ctx.brain().cookSkillLevel())
                    || !hasFreeSlot(ctx))
                && DepositActivity.countDepositable(ctx) > 0) {
            depositedSinceLastBatch = true;
            // Off to storage: the station it may still hold is free meanwhile.
            releaseStation(ctx);
            sub = new DepositActivity();
            return Status.RUNNING;
        }

        // The dish the shelves need most, by the cook's mode. This stays the
        // SAME dish across ticks while it is still short and obtainable
        // (fetching moves ingredients from storage to the cook, which leaves
        // the pantry's totals alone), so gathering a four-ingredient cake over
        // several storage trips is not abandoned partway for whatever
        // lower-priority recipe happens to already sit fully in hand.
        CookSettings settings = CookSettings.of(ctx.npc());
        Pantry pantry = pantry(ctx);
        // Still at a station with another dish for it in hand (brought along
        // by fetchCompanion): cook that before walking anywhere.
        if (lastHeat != null) {
            HeatSource here = lastHeat;
            lastHeat = null;
            Plan next = firstCraftableFromCarried(ctx, kitchen, pantry, settings, here);
            if (next != null) return startCooking(ctx, kitchen, next);
        }
        // Nothing more for the station it stood at: the next cook may have it.
        releaseStation(ctx);
        Plan plan = CookPlanner.choose(kitchen, pantry, settings, ctx.brain().cookSkillLevel());
        // Nothing worth cooking. Before giving up, see whether the kitchen
        // should be laying in fuel instead -- a village that burns its logs
        // straight gets a fraction of the heat charcoal would have given it.
        if (plan == null) plan = CookPlanner.charcoal(kitchen, pantry, settings);
        if (plan != null) {
            Status status = pursue(ctx, kitchen, pantry, settings, plan);
            if (status != null) return status;
        }

        // Nothing left to work toward via storage. Only now fall back to
        // whatever leftover carried items happen to already complete a
        // (lower-priority) recipe the shelves still want.
        Plan carried = firstCraftableFromCarried(ctx, kitchen, pantry, settings);
        if (carried != null) return startCooking(ctx, kitchen, carried);

        return depositOrFinish(ctx);
    }

    /**
     * Cook {@code plan} if it is in hand, else fetch the next thing it lacks:
     * bowls (made from planks, or from a log cut into planks, when storage has
     * none), sugar (pressed from spare sugar cane), an ingredient, or fuel.
     * Null when it can neither start nor fetch anything.
     */
    @Nullable
    private Status pursue(Context ctx, VillageKitchens kitchen, Pantry pantry,
                          CookSettings settings, Plan plan) {
        Recipe target = plan.recipe();
        Integer bowlsPerBatch = target.inputsPerBatch().get(Items.BOWL);
        if (bowlsPerBatch != null && pantry.count(Items.BOWL) < bowlsPerBatch) {
            Plan bowls = CookPlanner.bowls(pantry, plan.batches());
            return bowls == null ? null : pursue(ctx, kitchen, pantry, settings, bowls);
        }
        Integer sugarPerBatch = target.inputsPerBatch().get(Items.SUGAR);
        if (sugarPerBatch != null) {
            Plan sugar = CookPlanner.sugar(pantry, plan);
            if (sugar != null) return pursue(ctx, kitchen, pantry, settings, sugar);
            if (pantry.count(Items.SUGAR) < sugarPerBatch) return null;
        }

        if (craftableBatches(ctx, target, plan.batches(), plan.order() != null) <= 0) {
            // Fetch one missing ingredient for it. Only one
            // FetchFromStorageActivity runs at a time; the cook goes from
            // chest to chest without walking back in between.
            for (Map.Entry<Item, Integer> entry : target.inputsPerBatch().entrySet()) {
                Item item = entry.getKey();
                if (countItem(ctx, item) >= entry.getValue()) continue;
                if (storageWithItem(ctx, item) == null) continue;
                int amount = Math.min(entry.getValue() * plan.batches(), item.getDefaultMaxStackSize());
                sub = FetchFromStorageActivity.forItem(
                        ingredientSample(ctx, item), amount, "fetch_" + itemName(item));
                return Status.RUNNING;
            }
            return null;
        }

        // A fuller visit: other dishes the same station can cook come along.
        if (plan.heat() != null && plan.order() == null) {
            Status companion = fetchCompanion(ctx, kitchen, pantry, settings, plan);
            if (companion != null) return companion;
        }

        // Fuel for the whole visit in one trip, counting the heat the station
        // kept from last time.
        if (CookPlanner.burnsFuel(plan.heat())) {
            double have = CookPlanner.fuelSmelts(carriedPantry(ctx), settings, target, 1)
                    + warmthAt(ctx, stationFor(ctx, kitchen, target, plan.heat(), null));
            int needed = visitHeatBatches(ctx, kitchen, pantry, settings, plan);
            if (have < needed) {
                Item fuel = CookPlanner.bestFuel(new Pantry(storageCounts(ctx)), settings, target,
                        CookPlanner.coalScarce(pantry, settings));
                if (fuel != null) {
                    int amount = (int) Math.ceil((needed - have) / CookPlanner.smeltsPerFuel(fuel));
                    sub = FetchFromStorageActivity.forItem(new ItemStack(fuel),
                            Math.clamp(amount, 1, fuel.getDefaultMaxStackSize()), "fetch_fuel");
                    return Status.RUNNING;
                }
                if (have < 1) return null;
            }
        }
        return startCooking(ctx, kitchen, plan);
    }

    /** Most other dishes brought along to one station visit. */
    private static final int MAX_COMPANIONS = 2;
    /** Inventory slots a companion fetch leaves free, for what the visit cooks. */
    private static final int COMPANION_FREE_SLOTS = 2;

    /**
     * Fetches the next missing ingredient of a dish that can share
     * {@code plan}'s station visit: heat dishes the shelves want, on the same
     * station, whose ingredients are all in storage, up to
     * {@value #MAX_COMPANIONS} of them. Null when there is nothing to fetch.
     * Bowl dishes stay out: their bowls are a crafting trip of their own.
     */
    @Nullable
    private Status fetchCompanion(Context ctx, VillageKitchens kitchen, Pantry pantry,
                                  CookSettings settings, Plan plan) {
        if (freeSlots(ctx) < COMPANION_FREE_SLOTS) return null;
        int level = ctx.brain().cookSkillLevel();
        Pantry free = pantry.withoutHeld();
        int inHand = 0;
        for (Recipe candidate : RECIPES) {
            if (candidate == plan.recipe() || !candidate.needsFurnace()) continue;
            if (candidate.inputsPerBatch().containsKey(Items.BOWL)) continue;
            if (!CookPlanner.isCandidate(kitchen, free, settings, candidate, level)) continue;
            if (CookPlanner.pickHeat(kitchen, candidate, true) != plan.heat()) continue;
            if (craftableBatches(ctx, candidate, 1, false) > 0) {
                if (++inHand >= MAX_COMPANIONS) return null;
                continue;
            }
            int batches = CookPlanner.wantedBatches(free, settings, candidate, level);
            if (batches <= 0) continue;
            boolean allInStorage = true;
            for (Item item : candidate.inputsPerBatch().keySet()) {
                if (countItem(ctx, item) < candidate.inputsPerBatch().get(item)
                        && storageWithItem(ctx, item) == null) allInStorage = false;
            }
            if (!allInStorage) continue;
            for (Map.Entry<Item, Integer> entry : candidate.inputsPerBatch().entrySet()) {
                Item item = entry.getKey();
                if (countItem(ctx, item) >= entry.getValue()) continue;
                int amount = Math.min(entry.getValue() * batches, item.getDefaultMaxStackSize());
                sub = FetchFromStorageActivity.forItem(
                        ingredientSample(ctx, item), amount, "fetch_" + itemName(item));
                return Status.RUNNING;
            }
        }
        return null;
    }

    /**
     * Batches the coming station visit will cook: the plan's own, and every
     * other wanted dish for the same station already in hand.
     */
    private static int visitHeatBatches(Context ctx, VillageKitchens kitchen, Pantry pantry,
                                        CookSettings settings, Plan plan) {
        int total = Math.max(1, craftableBatches(ctx, plan.recipe(), plan.batches(), plan.order() != null));
        if (plan.order() != null) return total;
        int level = ctx.brain().cookSkillLevel();
        Pantry free = pantry.withoutHeld();
        for (Recipe candidate : RECIPES) {
            if (candidate == plan.recipe() || !candidate.needsFurnace()) continue;
            if (!settings.allowsRecipe(candidate.result())
                    || CookPlanner.deficit(free, settings, candidate) <= 0) continue;
            if (CookPlanner.pickHeat(kitchen, candidate, true) != plan.heat()) continue;
            total += craftableBatches(ctx, candidate,
                    CookPlanner.wantedBatches(free, settings, candidate, level), false);
        }
        return total;
    }

    private static int freeSlots(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        int free = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            if (inv.getSlot(i).isEmpty()) free++;
        }
        return free;
    }

    private Status startCooking(Context ctx, VillageKitchens kitchen, Plan plan) {
        recipe = plan.recipe();
        heat = plan.heat();
        plannedBatches = plan.batches();
        order = plan.order();
        // A row this cook is making is not planned again by another cook.
        if (order != null && order.once()) {
            StockReserves.get(ctx.level().getServer()).claim(ctx.mgr().getVillageId(), order.row(),
                    ctx.npc().getNpcId(), plannedBatches * recipe.outputPerBatch(), ctx.level().getGameTime());
        }
        station = stationFor(ctx, kitchen, recipe, heat, null);
        return startWalkToStation(ctx, kitchen);
    }

    /**
     * Head for {@link #station}. A station (the crafting table, a smoker,
     * furnace or campfire) is worked by one villager at a time: while somebody
     * else is at it the cook waits where it is, and this is called again each
     * tick until the station frees up.
     *
     * <p>Only the table used to be claimed, so every cook with something to
     * cook on heat went to the same smoker and they all cooked on it at once.
     */
    private Status startWalkToStation(Context ctx, VillageKitchens kitchen) {
        if (recipe == null || station == null) {
            abandonRecipe(ctx);
            return Status.RUNNING;
        }
        // Waiting at a busy table or smoker: another of its kind may have come free.
        BlockPos pick = stationFor(ctx, kitchen, recipe, heat, station);
        if (pick != null) station = pick;
        if (workStation == null || !workStation.table().equals(station)) {
            if (workStation != null) workStation.release(ctx);
            workStation = new CraftingStation(station);
        }
        waiting = false;
        switch (workStation.acquire(ctx)) {
            case WAIT -> {
                waiting = true;
                // Arms folded, foot tapping, a finger wagged at the station.
                if (!waitClip) {
                    waitClip = true;
                    ctx.npc().startWorkAnimation(VillagerAnimationState.COOK_WAIT_STATION, 1f);
                }
                if (ctx.npc().blockPosition().distSqr(station) <= WAIT_FACE_DIST_SQ) {
                    CraftingStation.face(ctx.npc(), station);
                }
                return Status.RUNNING;
            }
            case GIVE_UP -> {
                waitClip = false;
                ctx.npc().setAnimation(VillagerAnimationState.SHRUG);
                diary(ctx, "COOK gave up waiting for station " + station);
                abandonRecipe(ctx);
                return depositOrFinish(ctx);
            }
            case MINE -> endWaitClip(ctx);
        }

        // Worked from inside the room it stands in.
        KitchenBuilding room = kitchen.kitchenOf(station);
        stand = room == null ? null : recipe.needsFurnace()
                ? room.getWalkTargetBeside(station, ctx.mgr().getGraph(), ctx.npc().blockPosition())
                : workStation.standFor(ctx, room);
        if (stand == null) {
            diary(ctx, "COOK no stand beside station " + station);
            abandonRecipe(ctx);
            return Status.RUNNING;
        }
        if (ctx.npc().blockPosition().distSqr(stand) <= 2.25) {
            settling = true;
        } else {
            move = new MoveActivity(stand, VillagerModel.WALK_SPEED, true);
        }
        return Status.RUNNING;
    }

    /** Drops the current dish and lets go of the station for the next villager. */
    private void abandonRecipe(Context ctx) {
        abandonRecipe(ctx, false);
    }

    /** As {@link #abandonRecipe(Context)}; {@code keepStation} holds on to the station's claim. */
    private void abandonRecipe(Context ctx, boolean keepStation) {
        if (order != null) {
            StockReserves.get(ctx.level().getServer()).release(ctx.mgr().getVillageId(), order.row(),
                    ctx.npc().getNpcId());
            order = null;
        }
        // The heat stays in the station for the next visit (see WARM_STATIONS).
        if (station != null && CookPlanner.burnsFuel(heat)) keepWarmth(ctx, station, fuelCredit);
        recipe = null;
        heat = null;
        plannedBatches = 0;
        fuelCredit = 0;
        stand = null;
        settling = false;
        waiting = false;
        endWaitClip(ctx);
        workTimer = 0;
        itemsToProcess = 0;
        // Nothing left in the fist (stopAtStation and doStop come through here too).
        pieces = List.of();
        nextPiece = 0;
        lightTick = -1;
        burnedFuel = null;
        presenting = false;
        ctx.npc().setCookProp(ItemStack.EMPTY);
        if (!keepStation) releaseStation(ctx);
    }

    /** Stops cook_wait_station, if it plays: the cook got the station, or the wait ended another way. */
    private void endWaitClip(Context ctx) {
        if (!waitClip) return;
        waitClip = false;
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
    }

    private void releaseStation(Context ctx) {
        if (workStation != null) {
            workStation.release(ctx);
            workStation = null;
        }
    }

    // =========================================================================
    // Processing
    // =========================================================================

    private void beginProcessing(Context ctx) {
        if (recipe == null || station == null) return;
        if (!canContinueRecipe(ctx, recipe, order)) {
            abandonRecipe(ctx);
            return;
        }
        itemsToProcess = craftableBatches(ctx, recipe, plannedBatches, order != null);
        if (itemsToProcess <= 0) {
            abandonRecipe(ctx);
            return;
        }
        ItemStack first = producedStack(ctx, recipe, recipe.outputPerBatch());
        if (!fitsAfterBatch(ctx, recipe, first)) {
            abandonRecipe(ctx);
            blockedAtStation = true;
            return;
        }
        CraftingStation.face(ctx.npc(), station);
        // Cook on what the last visit left in the fire first.
        if (CookPlanner.burnsFuel(heat)) fuelCredit += takeWarmth(ctx, station);
        if (!startBatch(ctx, true)) {
            abandonRecipe(ctx);
            ctx.npc().setAnimation(VillagerAnimationState.SHRUG);
            return;
        }
        // A cold furnace or smoker lights when cook_fire_light puts the fuel in
        // (tickProcessing); a warm one, or a campfire, lights now as before.
        if (heat != null && lightTick < 0) lightFurnace(ctx, true);
        diary(ctx, "COOK " + itemName(recipe.result()) + " x" + itemsToProcess
                + " at " + (heat == null ? "table" : heat.name().toLowerCase(Locale.ROOT))
                + " " + station.toShortString()
                + " mode=" + CookSettings.of(ctx.npc()).mode().name().toLowerCase(Locale.ROOT));
    }

    /**
     * Feeds the fire if the batch needs it, then lays out the batch's moves and
     * starts the first. False only when the fire needs fuel and none is left.
     * {@code firstOfVisit}: the first batch since the cook stepped up to the station.
     */
    private boolean startBatch(Context ctx, boolean firstOfVisit) {
        burnedFuel = null;
        if (CookPlanner.burnsFuel(heat)) {
            if (!ensureHeat(ctx)) return false;
            fuelCredit -= 1;
        }
        int level = ctx.brain().cookSkillLevel();
        int stationTicks = CookPlanner.stationTicks(heat);
        workTimer = CookBalance.batchTicks(level, stationTicks);
        planMoves(ctx, firstOfVisit);
        return true;
    }

    /**
     * The batch's moves ({@link CookMoves#plan}), laid inside {@link #workTimer}
     * without changing it: sleeves up on the shift's first batch, the chef's kiss
     * after a batch that came out one extra, the fuel lit (first batch of a visit)
     * or fed (later) when one is burned, and the dish held up on the last batch
     * of a showpiece. The moves due at tick 0 start now.
     */
    private void planMoves(Context ctx, boolean firstOfVisit) {
        String result = itemName(recipe.result());
        CookMoves.Opener opener = !shiftStarted ? CookMoves.Opener.SLEEVES
                : kissNext ? CookMoves.Opener.KISS : CookMoves.Opener.NONE;
        shiftStarted = true;
        kissNext = false;
        presenting = CookMoves.isShowpiece(result) && itemsToProcess == 1;
        batchTicks = workTimer;
        // RandomGenerator needs only nextLong(): the villager's own random serves.
        pieces = CookMoves.plan(moveStation(heat), CookMoves.tableClip(result), batchTicks,
                burnedFuel != null && firstOfVisit, burnedFuel != null && !firstOfVisit,
                opener, presenting, ctx.npc().getRandom()::nextLong);
        nextPiece = 0;
        lightTick = -1;
        for (CookMoves.Piece piece : pieces) {
            if (piece.clip() != VillagerAnimationState.COOK_FIRE_LIGHT) continue;
            // The block lights when the fuel goes into its mouth: the clip's moment,
            // after the blend in, both played at the piece's speed.
            lightTick = piece.start() + (int) Math.ceil((VillagerAnimationState.WORK_BLEND_TICKS
                    + VillagerAnimationState.COOK_FIRE_LIGHT_IN_TICKS) / piece.speed());
            break;
        }
        startDueMoves(ctx, 0);
    }

    /** Starts every move of {@link #pieces} due by batch tick {@code elapsed}, with what it holds. */
    private void startDueMoves(Context ctx, int elapsed) {
        while (nextPiece < pieces.size() && pieces.get(nextPiece).start() <= elapsed) {
            CookMoves.Piece piece = pieces.get(nextPiece++);
            // Every move through startWorkAnimation, a repeat of the same clip
            // too: its new sequence is what restarts the clip on the client.
            ctx.npc().startWorkAnimation(piece.clip(), piece.speed());
            ctx.npc().setCookProp(propFor(piece.clip()));
        }
    }

    /**
     * What a move shows in the fist (drawn only, never taken from the pack): the
     * fuel going in, the raw food going on, the dish coming out or held up, the
     * cane being pressed; nothing for the rest (spoon, knife and bowl are drawn
     * by the clip itself).
     */
    private ItemStack propFor(VillagerAnimationState clip) {
        if (recipe == null) return ItemStack.EMPTY;
        return switch (clip) {
            case COOK_FIRE_LIGHT, COOK_FIRE_FEED -> burnedFuel == null ? ItemStack.EMPTY : new ItemStack(burnedFuel);
            case COOK_LOAD, COOK_CAMP_LAY -> mainInput(recipe);
            case COOK_TAKE, COOK_CAMP_TAKE, COOK_PRESENT, COOK_COOKIES, COOK_GILD -> new ItemStack(recipe.result());
            case COOK_PRESS_CANE -> new ItemStack(Items.SUGAR_CANE);
            default -> ItemStack.EMPTY;
        };
    }

    /** The recipe's main raw input: its first one that is not a bowl. */
    private static ItemStack mainInput(Recipe recipe) {
        for (Item item : recipe.inputsPerBatch().keySet()) {
            if (item != Items.BOWL) return new ItemStack(item);
        }
        return ItemStack.EMPTY;
    }

    /** The kind of station the moves are planned for; a blast furnace plays the furnace's. */
    private static CookMoves.Station moveStation(@Nullable HeatSource heat) {
        if (heat == null) return CookMoves.Station.TABLE;
        return switch (heat) {
            case SMOKER -> CookMoves.Station.SMOKER;
            case FURNACE, BLAST_FURNACE -> CookMoves.Station.FURNACE;
            case CAMPFIRE -> CookMoves.Station.CAMPFIRE;
        };
    }

    /** At least one item's worth of heat, burning a carried fuel if needed ({@link #burnedFuel}). */
    private boolean ensureHeat(Context ctx) {
        if (fuelCredit >= 1) return true;
        if (recipe == null) return false;
        CookSettings settings = CookSettings.of(ctx.npc());
        Item fuel = CookPlanner.bestFuel(carriedPantry(ctx), settings, recipe,
                CookPlanner.coalScarce(pantry(ctx), settings));
        if (fuel == null || !consumeItems(ctx, fuel, 1)) {
            diary(ctx, "COOK out of fuel for " + itemName(recipe.result()));
            return false;
        }
        fuelCredit += CookPlanner.smeltsPerFuel(fuel);
        burnedFuel = fuel;
        return true;
    }

    private Status tickProcessing(Context ctx) {
        if (station != null) CraftingStation.face(ctx.npc(), station);
        workTimer--;
        int elapsed = batchTicks - workTimer;
        if (lightTick >= 0 && elapsed >= lightTick) {
            lightTick = -1;
            lightFurnace(ctx, true);
        }
        if (workTimer > 0) {
            startDueMoves(ctx, elapsed);
            return Status.RUNNING;
        }

        if (recipe == null || craftableBatches(ctx, recipe, 1, order != null) <= 0) {
            stopAtStation(ctx);
            return Status.RUNNING;
        }
        int amount = recipe.outputPerBatch();
        int cookLevel = ctx.brain().cookSkillLevel();
        if (isBonusStaple(recipe) && ctx.npc().getRandom().nextDouble()
                < CookBalance.extraStapleChance(cookLevel)) {
            amount++;
        } else if (!isBonusStaple(recipe) && cookLevel >= CookBalance.BAKERS_HANDS_LEVEL
                && com.alex.test.village.tokens.VillageFood.isDish(recipe.result())
                && recipe.result() != Items.CAKE // a cake does not stack
                && ctx.npc().getRandom().nextDouble() < CookBalance.BAKERS_HANDS_CHANCE) {
            // Baker's Hands: any other dish may come out one extra too.
            amount++;
        }
        ItemStack cooked = producedStack(ctx, recipe, amount);
        if (!fitsAfterBatch(ctx, recipe, cooked)) {
            diary(ctx, "COOK no room for " + itemName(recipe.result()) + ", stopping");
            stopAtStation(ctx);
            return depositOrFinish(ctx);
        }
        if (!consumeInputs(ctx, recipe)) {
            stopAtStation(ctx);
            return Status.RUNNING;
        }
        ctx.npc().getInventory().addItem(cooked);
        if (order != null && order.once()) {
            StockReserves.get(ctx.level().getServer()).onMade(ctx.mgr().getVillageId(), order.row(),
                    ctx.npc().getNpcId(), recipe.result(), cooked.getCount());
        }
        depositedSinceLastBatch = false;
        ctx.level().playSound(null, station,
                recipe.needsFurnace() ? SoundEvents.FURNACE_FIRE_CRACKLE
                        : SoundEvents.VILLAGER_WORK_FARMER,
                SoundSource.NEUTRAL, 0.6f, 1.0f);
        workedThisSession = true;
        ctx.brain().onCookBatchCompleted(ctx, recipe.result());
        // One extra: the next batch opens with the chef's kiss. A plain dish
        // gets a small nod (a held-up showpiece already ends on its bow).
        if (amount > recipe.outputPerBatch()) kissNext = true;
        else if (!presenting) ctx.npc().nod();
        itemsToProcess--;
        boolean more = itemsToProcess > 0 && canContinueRecipe(ctx, recipe, order)
                && craftableBatches(ctx, recipe, 1, order != null) > 0
                && productsCarried(ctx) < CookBalance.carryItems(ctx.brain().cookSkillLevel());
        if (more && startBatch(ctx, false)) {
            return Status.RUNNING;
        }
        stopAtStation(ctx);
        // Another batch was wanted and the fire had no fuel for it.
        if (more) ctx.npc().setAnimation(VillagerAnimationState.SHRUG);
        return Status.RUNNING;
    }

    private void stopAtStation(Context ctx) {
        // The next dish in hand for this station is cooked before leaving it,
        // so the station stays this cook's until chooseNext has looked.
        lastHeat = heat;
        extinguishFurnace(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        // Clears the prop as well.
        abandonRecipe(ctx, lastHeat != null);
    }

    public static boolean isBonusStaple(Recipe recipe) {
        Item result = recipe.result();
        return result == Items.BREAD || result == Items.BAKED_POTATO
                || result == Items.COOKED_BEEF || result == Items.COOKED_PORKCHOP
                || result == Items.COOKED_CHICKEN || result == Items.COOKED_MUTTON
                || result == Items.COOKED_RABBIT || result == Items.COOKED_COD
                || result == Items.COOKED_SALMON || result == Items.DRIED_KELP;
    }

    private static ItemStack producedStack(Context ctx, Recipe recipe, int amount) {
        return com.alex.test.village.tokens.VillageFood.chef(new ItemStack(recipe.result(), amount), ctx.mgr());
    }

    /** Shared with offline production so its food components match live dishes. */
    public static void applyProducedFoodProperties(ItemStack stack) {
        for (Recipe recipe : RECIPES) {
            if (recipe.result() != stack.getItem()) continue;
            if (recipe.nutrition() > 0) stack.set(DataComponents.FOOD,
                    new FoodProperties.Builder()
                            .nutrition(recipe.nutrition())
                            .saturationModifier(0.6f)
                            .build());
            // A villager's nine slots cannot hold a food reserve of stews one
            // per slot, which is all vanilla allows.
            if (recipe.inputsPerBatch().containsKey(Items.BOWL)) {
                stack.set(DataComponents.MAX_STACK_SIZE, STEW_STACK);
            }
            return;
        }
    }

    private Status depositOrFinish(Context ctx) {
        // The shift is ending: no station is kept for a next dish, and no
        // wait at one goes on playing on the walk to storage.
        releaseStation(ctx);
        endWaitClip(ctx);
        if (DepositActivity.countDepositable(ctx) > 0) {
            sub = new DepositActivity();
            finishAfterSub = true;
            return Status.RUNNING;
        }
        return workedThisSession ? Status.DONE : Status.FAILED;
    }

    // =========================================================================
    // Furnace theatre
    // =========================================================================

    // The station remains visual only. Putting synthetic ingredients in a real
    // furnace or campfire lets vanilla produce an unbound second output and can
    // delete a player's contents when the villager clears the station.

    private void lightFurnace(Context ctx, boolean lit) {
        if (station == null) return;
        BlockState state = ctx.level().getBlockState(station);
        if (state.getBlock() instanceof AbstractFurnaceBlock
                && state.hasProperty(AbstractFurnaceBlock.LIT)) {
            ctx.level().setBlock(station, state.setValue(AbstractFurnaceBlock.LIT, lit), 3);
            furnaceLit = lit;
        } else if (state.getBlock() instanceof CampfireBlock
                && state.hasProperty(CampfireBlock.LIT)) {
            // A campfire the player put out stays out; this only ever lights
            // one, which is what a cook arriving to work would do.
            if (lit) {
                ctx.level().setBlock(station, state.setValue(CampfireBlock.LIT, true), 3);
                furnaceLit = true;
            }
        }
    }

    private void extinguishFurnace(Context ctx) {
        if (furnaceLit) lightFurnace(ctx, false);
    }

    // =========================================================================
    // Recipe helpers
    // =========================================================================

    /**
     * The block to cook {@code recipe} on, of every kitchen's crafting tables or
     * stations of {@code heat}: the one this cook already holds, else the
     * nearest one nobody holds, else {@code current} when it is one of them (a
     * cook waiting at a busy station keeps its place and its wait), else the nearest.
     *
     * <p>Only the first table and the first station of each kind used to be
     * handed out, so a second smoker or table stood unused while every cook
     * went to the first.
     */
    @Nullable
    private static BlockPos stationFor(Context ctx, VillageKitchens kitchen, Recipe recipe,
                                       @Nullable HeatSource heat, @Nullable BlockPos current) {
        if (recipe.needsFurnace() && heat == null) return null;
        List<BlockPos> stations = recipe.needsFurnace()
                ? kitchen.getHeatPositions(heat) : kitchen.getCraftingTablePositions();
        if (stations.isEmpty()) return null;
        ClaimsRegistry claims = ctx.mgr().getClaims();
        UUID me = ctx.model().npcId;
        BlockPos from = ctx.npc().blockPosition();
        BlockPos nearestFree = null;
        BlockPos nearest = null;
        for (BlockPos pos : stations) {
            UUID holder = claims.holder(pos);
            if (me.equals(holder)) return pos;
            if (holder == null && (nearestFree == null || pos.distSqr(from) < nearestFree.distSqr(from))) {
                nearestFree = pos;
            }
            if (nearest == null || pos.distSqr(from) < nearest.distSqr(from)) nearest = pos;
        }
        if (nearestFree != null) return nearestFree;
        return current != null && stations.contains(current) ? current : nearest;
    }

    /** How many whole batches of {@code recipe} the carried inventory covers, up to {@code cap}. */
    private static int craftableBatches(Context ctx, Recipe recipe, int cap, boolean ignoreReserve) {
        int batches = cap;
        for (Map.Entry<Item, Integer> entry : recipe.inputsPerBatch().entrySet()) {
            int have = countItem(ctx, entry.getKey());
            if (entry.getKey() == Items.GOLD_INGOT && !ignoreReserve) {
                have = Math.min(have, have + storageCounts(ctx).getOrDefault(Items.GOLD_INGOT, 0)
                        - CookSettings.of(ctx.npc()).goldReserve());
            }
            batches = Math.min(batches, have / entry.getValue());
        }
        return Math.max(0, batches);
    }

    private static boolean consumeInputs(Context ctx, Recipe recipe) {
        for (Map.Entry<Item, Integer> entry : recipe.inputsPerBatch().entrySet()) {
            if (countItem(ctx, entry.getKey()) < entry.getValue()) return false;
        }
        for (Map.Entry<Item, Integer> entry : recipe.inputsPerBatch().entrySet()) {
            if (!consumeItems(ctx, entry.getKey(), entry.getValue())) return false;
        }
        return true;
    }

    /**
     * Highest-priority dish the shelves still want that the cook can start
     * with what it already carries, fuel included.
     */
    @Nullable
    private static Plan firstCraftableFromCarried(Context ctx, VillageKitchens kitchen,
                                                  Pantry pantry, CookSettings settings) {
        return firstCraftableFromCarried(ctx, kitchen, pantry, settings, null);
    }

    /** The same, only dishes for heat station {@code only} when it is not null. */
    @Nullable
    private static Plan firstCraftableFromCarried(Context ctx, VillageKitchens kitchen,
                                                  Pantry pantry, CookSettings settings,
                                                  @Nullable HeatSource only) {
        pantry = pantry.withoutHeld();
        // Carried fuel, and the heat the stations kept from earlier visits.
        Pantry carried = carriedPantry(ctx).withWarmth(kitchenWarmth(ctx));
        for (Recipe candidate : RECIPES) {
            if (only != null && !candidate.needsFurnace()) continue;
            if (!settings.allowsRecipe(candidate.result())) continue;
            if (!CookPlanner.isUnlocked(candidate, ctx.brain().cookSkillLevel())) continue;
            if (!canAffordGold(ctx, candidate)) continue;
            if (CookPlanner.deficit(pantry, settings, candidate) <= 0) continue;
            HeatSource candidateHeat = null;
            if (candidate.needsFurnace()) {
                candidateHeat = CookPlanner.pickHeat(kitchen, candidate,
                        CookPlanner.fuelSmelts(carried, settings, candidate, 1) >= 1);
                if (candidateHeat == null || (only != null && candidateHeat != only)) continue;
            } else if (kitchen.getCraftingTablePos() == null) {
                continue;
            }
            int batches = CookPlanner.wantedBatches(pantry, settings, candidate,
                    ctx.brain().cookSkillLevel());
            if (craftableBatches(ctx, candidate, batches, false) <= 0) continue;
            return new Plan(candidate, candidateHeat, batches);
        }
        return null;
    }

    /**
     * What the cook holds onto through a deposit: inputs of a dish the
     * shelves still want, and fuel, planks or logs while there is a use for
     * them.
     *
     * <p>{@link #INGREDIENTS} alone kept every input of every recipe forever,
     * so potatoes with no furnace, carrots fetched for a golden carrot the
     * gold reserve then vetoed, or the eggs of a cake whose milk ran out sat
     * in the cook's nine slots for good. Built once per deposit pass, since
     * it looks through storage.
     */
    public static Predicate<ItemStack> depositKeepRule(Context ctx) {
        CookSettings settings = CookSettings.of(ctx.npc());
        VillageKitchens kitchen = findKitchen(ctx);
        if (!settings.works() || kitchen == null) return stack -> false;
        Pantry pantry = pantry(ctx);
        int level = ctx.brain().cookSkillLevel();
        Set<Item> keep = CookPlanner.worthKeeping(kitchen, pantry, settings, level);
        boolean fuelWanted = false;
        for (Recipe candidate : CookPlanner.orderCandidates(kitchen, pantry, settings, level)) {
            if (candidate.needsFurnace()) fuelWanted = true;
        }
        Pantry free = pantry.withoutHeld();
        for (Recipe candidate : RECIPES) {
            if (candidate.needsFurnace()
                    && CookPlanner.isCandidate(kitchen, free, settings, candidate, level)) {
                fuelWanted = true;
                break;
            }
        }
        boolean charcoalWanted = CookPlanner.charcoal(kitchen, pantry, settings) != null;
        boolean planksWanted = keep.contains(Items.BOWL);
        // No three planks of one wood: the log it brought to cut into planks.
        Item plank = pantry.mostOf(ItemTags.PLANKS);
        boolean logsForPlanks = planksWanted && (plank == null || pantry.count(plank) < 3);
        // Cane it brought to press into sugar for a pie or cake.
        boolean caneWanted = keep.contains(Items.SUGAR);
        boolean burnFuel = fuelWanted || charcoalWanted;
        return stack -> {
            Item item = stack.getItem();
            if (keep.contains(item)) return true;
            if ((charcoalWanted || logsForPlanks) && stack.is(ItemTags.LOGS)) return true;
            if (planksWanted && stack.is(ItemTags.PLANKS)) return true;
            if (caneWanted && stack.is(Items.SUGAR_CANE)) return true;
            return burnFuel && CookPlanner.isFuel(item);
        };
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    /**
     * Whether the cook should keep making {@code recipe}: still allowed and
     * affordable, and still short of its stock target. Charcoal is kept up
     * while the village is short of fuel; bowls, sugar and planks (made for a
     * dish, not for the shelf) are always finished. A Stock
     * tab row is kept up while the row still wants more, whatever the Recipes
     * tab and the gold reserve say.
     */
    private static boolean canContinueRecipe(Context ctx, Recipe recipe, @Nullable StockReserves.Want order) {
        CookSettings settings = CookSettings.of(ctx.npc());
        if (!settings.works()) return false;
        if (order != null) {
            if (!CookPlanner.isUnlocked(recipe, ctx.brain().cookSkillLevel())) return false;
            for (StockReserves.Want want : pantry(ctx).orders()) {
                if (want.item() == recipe.result() && java.util.Objects.equals(want.row(), order.row())) return true;
            }
            return false;
        }
        if (recipe.result() == Items.CHARCOAL) return CookPlanner.wantsCharcoal(pantry(ctx), settings);
        if (CookPlanner.isSideJob(recipe.result())) return true;
        return settings.allowsRecipe(recipe.result())
                && CookPlanner.isUnlocked(recipe, ctx.brain().cookSkillLevel())
                && canAffordGold(ctx, recipe)
                && CookPlanner.deficit(pantry(ctx).withoutHeld(), settings, recipe) > 0;
    }

    /**
     * Whether the kitchen may spend the gold this recipe wants.
     *
     * The cook is allowed the surplus above its configured reserve.
     */
    private static boolean canAffordGold(Context ctx, Recipe recipe) {
        Integer needed = recipe.inputsPerBatch().get(Items.GOLD_INGOT);
        if (needed == null) return true;
        int available = countItem(ctx, Items.GOLD_INGOT)
                + storageCounts(ctx).getOrDefault(Items.GOLD_INGOT, 0);
        return available - needed >= CookSettings.of(ctx.npc()).goldReserve();
    }

    // =========================================================================
    // Pantry: storage and carried counts
    // =========================================================================

    private record StorageCounts(long gameTime, Map<Item, Integer> counts) {}

    /**
     * Each village's storage, counted once per tick. Every choice the cook
     * makes looks at a dozen or more items, and each used to be its own walk
     * over every chest slot.
     */
    private static final Map<Object, StorageCounts> STORAGE_COUNTS = new WeakHashMap<>();

    private static Map<Item, Integer> storageCounts(Context ctx) {
        long now = ctx.level().getGameTime();
        StorageCounts cached = STORAGE_COUNTS.get(ctx.mgr());
        if (cached != null && cached.gameTime() == now) return cached.counts();
        Map<Item, Integer> counts = new HashMap<>();
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (var chest : storage.inventories(ctx.level())) {
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack stack = chest.peek(i);
                    if (!stack.isEmpty() && storage.mayUseAsMaterial(ctx.level(), stack)) {
                        counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
            }
        }
        STORAGE_COUNTS.put(ctx.mgr(), new StorageCounts(now, counts));
        return counts;
    }

    // =========================================================================
    // Warm stations
    // =========================================================================

    private record Warmth(double credit, long until) {}

    /**
     * How long a smoker or furnace keeps the heat a cook left in it. A coal
     * cooks eight items; a cook leaving after two used to throw the other six
     * away, which is most of the coal a kitchen burned.
     */
    private static final long WARM_TICKS = 24_000L;

    /** Each village's stations and the heat left in them. Not saved: a reload cools them. */
    private static final Map<Object, Map<BlockPos, Warmth>> WARM_STATIONS = new WeakHashMap<>();

    /** Items {@code station} can still cook on the heat left in it. */
    private static double warmthAt(Context ctx, @Nullable BlockPos station) {
        if (station == null) return 0.0;
        Map<BlockPos, Warmth> stations = WARM_STATIONS.get(ctx.mgr());
        Warmth warmth = stations == null ? null : stations.get(station);
        return warmth == null || warmth.until() < ctx.level().getGameTime() ? 0.0 : warmth.credit();
    }

    /** The heat left in {@code station}, taken out to cook on. */
    private static double takeWarmth(Context ctx, BlockPos station) {
        double credit = warmthAt(ctx, station);
        Map<BlockPos, Warmth> stations = WARM_STATIONS.get(ctx.mgr());
        if (stations != null) stations.remove(station);
        return credit;
    }

    /** Leaves {@code credit} items of heat in {@code station} for the next visit. */
    private static void keepWarmth(Context ctx, BlockPos station, double credit) {
        if (credit <= 0.0) return;
        double total = credit + warmthAt(ctx, station);
        WARM_STATIONS.computeIfAbsent(ctx.mgr(), mgr -> new HashMap<>())
                .put(station.immutable(), new Warmth(total, ctx.level().getGameTime() + WARM_TICKS));
    }

    /** Heat left in all of the village's stations. */
    private static double kitchenWarmth(Context ctx) {
        Map<BlockPos, Warmth> stations = WARM_STATIONS.get(ctx.mgr());
        if (stations == null) return 0.0;
        long now = ctx.level().getGameTime();
        stations.values().removeIf(warmth -> warmth.until() < now);
        double total = 0.0;
        for (Warmth warmth : stations.values()) total += warmth.credit();
        return total;
    }

    /**
     * Storage plus everything the cook carries, with what the village eats a
     * day (watched here, see {@link CookDemand}) and the heat its stations
     * still hold.
     */
    private static Pantry pantry(Context ctx) {
        Map<Item, Integer> storage = storageCounts(ctx);
        CookDemand.observe(ctx.npc().getBrainData(), ctx.level().getGameTime(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()), storage);
        Pantry pantry = new Pantry(storage).forVillage(ctx.mgr())
                .withDemand(CookDemand.perDay(ctx.npc().getBrainData()))
                .withWarmth(kitchenWarmth(ctx))
                .withOrders(StockReserves.get(ctx.level().getServer()).wants(ctx.mgr(), ctx.level(),
                        Profession.COOK, ctx.npc().getNpcId()), held(ctx));
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!stack.isEmpty() && inv.mayUseAsMaterial(stack)) pantry.add(stack.getItem(), stack.getCount());
        }
        return pantry;
    }

    /**
     * What the Stock tab holds back from the cook's own targets: the ingredients its
     * rows still need, and the player's dishes, which are not on the village's shelf.
     */
    private static Map<Item, Integer> held(Context ctx) {
        StockReserves reserves = StockReserves.get(ctx.level().getServer());
        Map<Item, Integer> held = new HashMap<>(reserves.heldFor(ctx.mgr(), ctx.level(), Profession.COOK));
        for (Item dish : DISHES) {
            int locked = reserves.lockedAmount(ctx.mgr().getVillageId(), dish);
            if (locked > 0) held.merge(dish, locked, Integer::sum);
        }
        return held;
    }

    private static Pantry carriedPantry(Context ctx) {
        Pantry pantry = new Pantry(Map.of());
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!stack.isEmpty() && inv.mayUseAsMaterial(stack)) pantry.add(stack.getItem(), stack.getCount());
        }
        return pantry;
    }

    // =========================================================================
    // Inventory helpers
    // =========================================================================

    private static int countItem(Context ctx, @Nullable Item item) {
        if (item == null) return 0;
        int total = 0;
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (inv.mayUseAsMaterial(stack) && stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    /** Finished dishes carried, counted the way DepositActivity will bank them. */
    private static int productsCarried(Context ctx) {
        int total = 0;
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (inv.mayUseAsMaterial(stack) && ProfessionSupplyRules.isProfessionProduct(ctx.npc(), stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Whether an inventory slot is free.
     */
    private static boolean hasFreeSlot(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            if (inv.getSlot(i).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Whether {@code result} fits once one batch of {@code recipe}'s inputs is
     * gone. Replays consumeItems and then InventoryManager.addItem on copies,
     * so the slot a batch empties counts as room for what it makes.
     */
    private static boolean fitsAfterBatch(Context ctx, Recipe recipe, ItemStack result) {
        InventoryManager inv = ctx.npc().getInventory();
        ItemStack[] after = new ItemStack[InventoryManager.SIZE];
        for (int i = 0; i < InventoryManager.SIZE; i++) after[i] = inv.getSlot(i).copy();
        for (Map.Entry<Item, Integer> entry : recipe.inputsPerBatch().entrySet()) {
            int remaining = entry.getValue();
            for (int i = 0; i < InventoryManager.SIZE && remaining > 0; i++) {
                if (!inv.mayUseAsMaterial(inv.getSlot(i)) || after[i].getItem() != entry.getKey()) continue;
                int take = Math.min(remaining, after[i].getCount());
                after[i].shrink(take);
                remaining -= take;
            }
        }
        for (ItemStack stack : after) {
            if (!stack.isEmpty() && stack.isStackable()
                    && ItemStack.isSameItemSameComponents(stack, result)
                    && stack.getCount() + result.getCount() <= stack.getMaxStackSize()) {
                return true;
            }
        }
        for (ItemStack stack : after) if (stack.isEmpty()) return true;
        return false;
    }

    private static boolean consumeItems(Context ctx, Item item, int amount) {
        InventoryManager inv = ctx.npc().getInventory();
        int remaining = amount;
        for (int i = 0; i < InventoryManager.SIZE && remaining > 0; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!inv.mayUseAsMaterial(stack) || stack.getItem() != item) continue;
            int take = Math.min(remaining, stack.getCount());
            inv.reduceSlot(i, take);
            remaining -= take;
        }
        return remaining == 0;
    }

    // =========================================================================
    // Village queries
    // =========================================================================

    /** Every standing kitchen of the village, worked as one; null when it has none. */
    @Nullable
    private static VillageKitchens findKitchen(Context ctx) {
        return VillageKitchens.of(ctx.mgr());
    }

    /** What the cook fetches by: food only the village's own, never a player's. */
    private static ItemStack ingredientSample(Context ctx, Item item) {
        return com.alex.test.village.tokens.VillageItemOwnership.materialSample(item, ctx.mgr());
    }

    @Nullable
    private static StorageBuilding storageWithItem(Context ctx, Item item) {
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()
                    && storage.hasItem(ctx.level(), ingredientSample(ctx, item))) {
                return storage;
            }
        }
        return null;
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    /** Raw meat and fish: what villagers leave in storage for a working cook. */
    private static final Set<Item> RAW_FOR_COOK = Set.of(Items.BEEF, Items.PORKCHOP, Items.CHICKEN,
            Items.MUTTON, Items.RABBIT, Items.COD, Items.SALMON);

    public static boolean isRawForCook(Item item) {
        return RAW_FOR_COOK.contains(item);
    }

    private record CookWaiting(long gameTime, boolean waiting) {}
    /** Each village's answer to {@link #cookWaitsForRawFood}, kept for a few seconds. */
    private static final Map<Object, CookWaiting> COOK_WAITING = new WeakHashMap<>();
    private static final int COOK_WAITING_TICKS = 100;

    /**
     * Whether the village has a cook at work and a smoker, furnace or
     * campfire for it: then raw meat and fish in storage are the cook's to
     * cook, not a villager's lunch (StorageSupplyRestocker). A hungry
     * villager with nothing else to take still takes them.
     */
    public static boolean cookWaitsForRawFood(com.alex.test.village.VillageManager mgr, long gameTime) {
        CookWaiting cached = COOK_WAITING.get(mgr);
        if (cached != null && gameTime - cached.gameTime() < COOK_WAITING_TICKS
                && gameTime >= cached.gameTime()) return cached.waiting();
        boolean heat = false;
        for (Building building : mgr.getBuildings()) {
            if (building instanceof KitchenBuilding kitchen && kitchen.isValid()
                    && (kitchen.getHeatPos(HeatSource.SMOKER) != null
                        || kitchen.getHeatPos(HeatSource.FURNACE) != null
                        || kitchen.getHeatPos(HeatSource.CAMPFIRE) != null)) {
                heat = true;
                break;
            }
        }
        boolean waiting = false;
        if (heat) {
            for (com.alex.test.village.VillagerModel model : mgr.getVillagers()) {
                var npc = model.puppet();
                if (npc != null && npc.getProfession() == Profession.COOK && CookSettings.of(npc).works()) {
                    waiting = true;
                    break;
                }
            }
        }
        COOK_WAITING.put(mgr, new CookWaiting(gameTime, waiting));
        return waiting;
    }

    /** Cheap pre-check for brain scoring. */
    public static boolean hasWork(Context ctx) {
        CookSettings settings = CookSettings.of(ctx.npc());
        if (!settings.works()) {
            return false;
        }
        VillageKitchens kitchen = findKitchen(ctx);
        if (kitchen == null) return false;
        Pantry pantry = pantry(ctx);
        return CookPlanner.choose(kitchen, pantry, settings, ctx.brain().cookSkillLevel()) != null
                // Laying in fuel is work too. Without this a village with logs
                // and no coal scored the cook at zero and it stood in the
                // square all day next to the charcoal it was meant to make.
                || CookPlanner.charcoal(kitchen, pantry, settings) != null
                || firstCraftableFromCarried(ctx, kitchen, pantry, settings) != null;
    }
}
