package com.alex.test.village.economy.away.jobs;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.building.KitchenBuilding.HeatSource;
import com.alex.test.village.building.KitchenStations;
import com.alex.test.village.economy.AwayClock;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayJob;
import com.alex.test.village.economy.away.AwayKitchen;
import com.alex.test.village.economy.away.AwayRun;
import com.alex.test.village.economy.away.AwayXp;
import com.alex.test.village.tokens.VillageFood;
import com.alex.test.village.villagers.cook.CookBalance;
import com.alex.test.village.villagers.cook.CookDemand;
import com.alex.test.village.villagers.cook.CookPlanner;
import com.alex.test.village.villagers.cook.CookPlanner.Pantry;
import com.alex.test.village.villagers.cook.CookPlanner.Plan;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.cook.behavior.CookActivity.Recipe;
import com.alex.test.village.villagers.cook.settings.CookSettings;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A cook's away day (round 4 of the plan), in the village's kitchens, worked
 * as one ({@link AwayKitchen}), on the storage copies. The cooks take their turns
 * after the farmers, so today's harvest is in the pantry.
 *
 * <p>Live rules it copies, and where they live:
 * <ul>
 *   <li>What to cook: {@code CookPlanner.choose} (the Stock tab's rows first, then dishes under their
 *       shelf target by the cook's mode; the most food per ingredient with under a day of cooked food),
 *       the shelf targets from what the village eats ({@code CookDemand}), charcoal only when no dish is
 *       wanted ({@code CookActivity.chooseNext}).</li>
 *   <li>First what a dish needs: bowls ({@code CookPlanner.bowls}: planks, mixed planks or a cut log) and
 *       sugar from the cane the enchanters can spare ({@code CookPlanner.sugar}), as {@code pursue}.</li>
 *   <li>Time: the day's work hours at {@code AwayClock.AWAY_PACE}; a batch {@code CookBalance.batchTicks}
 *       at the station's own speed ({@code CookPlanner.stationTicks}: table, smoker, furnace, campfire); a
 *       walk ({@link #WALK_TICKS}) per station visit and per deposit on the way. One cook at a time at the
 *       each crafting table, smoker, furnace and campfire ({@link AwayKitchen}).</li>
 *   <li>Fuel: a piece only when the fire holds less than an item's heat ({@code ensureHeat}), the one
 *       {@code CookPlanner.bestFuel} picks; the heat left stays in the station ({@code keepWarmth}).</li>
 *   <li>Ingredients: the village's own only ({@code mayUseAsMaterial}), the pack first; gold above
 *       "Gold to leave" except for the Stock tab's rows; what the Stock tab holds is left for its rows.</li>
 *   <li>Yield: the staple's extra dish ({@code extraStapleChance}) or Baker's Hands, as an average.</li>
 *   <li>Banking: dishes into the pack, to storage once {@code carryItems} are carried or the pack is full
 *       ({@code chooseNext}); the dishes it carried before the day first; all of them, and charcoal, at
 *       the end of the day.</li>
 *   <li>Keep going while the dish is still wanted ({@code canContinueRecipe}) and the visit's batches
 *       last ({@code batchesPerVisit}).</li>
 *   <li>XP: each batch as live ({@link AwayXp#cookBatch}).</li>
 * </ul>
 * A snag (no bowls, no sugar, no fuel, no room, the table or the heat taken) skips that
 * dish for the day; the cook goes on with the next. Not done away: the second
 * dish brought along to a station visit ({@code fetchCompanion}), the station's
 * flames and sounds.
 */
public final class CookAway implements AwayJob {

    public static final CookAway INSTANCE = new CookAway();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A walk to storage and on to the station (as the farmer's deposit trip). */
    static final double WALK_TICKS = 200.0;
    /** Toward the next extra dish (a staple's extra, Baker's Hands), per dish id. */
    static final String TAG_EXTRA = "CookAwayExtraCarry";
    /** Most visits a day: a guard, should a plan ever make nothing and miss the skip list. */
    private static final int MAX_VISITS = 500;

    private CookAway() {}

    @Override
    @Nullable
    public String cannotWork(AwayContext ctx) {
        if (!CookSettings.of(ctx.npc()).works()) return "work off";
        if (ctx.kitchen().building() == null) return "no kitchen";
        if (!ctx.hasStorage()) return "no storage";
        return null;
    }

    @Override
    public void workDay(AwayContext ctx) {
        new Shift(ctx).run();
    }

    /** One cook's day. */
    private static final class Shift {
        private final AwayContext ctx;
        private final NpcEntity npc;
        private final InventoryManager pack;
        private final CookSettings settings;
        private final AwayKitchen kitchen;
        private final KitchenStations building;
        private final StockReserves reserves;
        private final double dayTicks;
        private double ticksLeft;
        private boolean outOfTime;
        private final CompoundTag extra;
        /** Dishes (and side jobs) that hit a snag today. */
        private final Set<Item> skip = new HashSet<>();
        private final Map<String, Integer> snags = new TreeMap<>();
        /** Whole items made and used today, for the net made / used. */
        private final Map<Item, Integer> made = new LinkedHashMap<>();
        private final Map<Item, Integer> used = new LinkedHashMap<>();
        private int batches;
        private int visits;
        private int deposits;

        Shift(AwayContext ctx) {
            this.ctx = ctx;
            this.npc = ctx.npc();
            this.pack = npc.getInventory();
            this.settings = CookSettings.of(npc);
            this.kitchen = ctx.kitchen();
            this.building = kitchen.building();
            this.reserves = StockReserves.get(ctx.level.getServer());
            this.dayTicks = ctx.day.workTicks() * AwayClock.AWAY_PACE;
            this.ticksLeft = dayTicks;
            this.extra = npc.getBrainData().getCompound(TAG_EXTRA).copy();
        }

        void run() {
            // The dishes it carried from before go to storage first.
            bank(false);
            for (int guard = 0; guard < MAX_VISITS && !outOfTime; guard++) {
                if (!visit()) break;
            }
            bank(false);
            finish();
        }

        // ---------------------------------------------------------------------
        // Choosing
        // ---------------------------------------------------------------------

        /** The next station visit; false when nothing is left to cook today. */
        private boolean visit() {
            // A full load goes to storage before the next dish, as chooseNext.
            if (carriedDishes() >= CookBalance.carryItems(level()) || !hasFreeSlot()) bank(true);
            Pantry pantry = pantry();
            Plan plan = CookPlanner.choose(building, pantry, settings, level(), skip);
            if (plan == null && !skip.contains(Items.CHARCOAL)) plan = CookPlanner.charcoal(building, pantry, settings);
            if (plan == null) return false;
            Plan step = prepare(pantry, plan);
            if (step == null) {
                skip.add(plan.recipe().result());
                return true;
            }
            if (cook(step, pantry) == 0 && !outOfTime) skip.add(step.recipe().result());
            return true;
        }

        /** What {@code plan} needs made first (bowls, planks, sugar), the plan itself, or null on a snag. */
        @Nullable
        private Plan prepare(Pantry pantry, Plan plan) {
            Recipe target = plan.recipe();
            Integer bowlsPerBatch = target.inputsPerBatch().get(Items.BOWL);
            if (bowlsPerBatch != null && pantry.count(Items.BOWL) < bowlsPerBatch) {
                Plan bowls = CookPlanner.bowls(pantry, plan.batches());
                if (bowls == null || skip.contains(bowls.recipe().result())) {
                    snag("no bowls");
                    return null;
                }
                return bowls;
            }
            Integer sugarPerBatch = target.inputsPerBatch().get(Items.SUGAR);
            if (sugarPerBatch != null) {
                Plan sugar = CookPlanner.sugar(pantry, plan);
                if (sugar != null && !skip.contains(Items.SUGAR)) return sugar;
                if (pantry.count(Items.SUGAR) < sugarPerBatch) {
                    snag("no sugar");
                    return null;
                }
            }
            return plan;
        }

        // ---------------------------------------------------------------------
        // Cooking
        // ---------------------------------------------------------------------

        /** One station visit of {@code plan}: the batches cooked. */
        private int cook(Plan plan, Pantry pantry) {
            Recipe recipe = plan.recipe();
            HeatSource heat = plan.heat();
            boolean onTable = !recipe.needsFurnace();
            if (!onTable && heat == null) {
                snag("no station");
                return 0;
            }
            if (onTable && building.getCraftingTablePos() == null) {
                snag("no table");
                return 0;
            }
            boolean burns = CookPlanner.burnsFuel(heat);
            int batchTicks = CookBalance.batchTicks(level(), CookPlanner.stationTicks(heat));
            double credit = burns ? kitchen.takeWarmth(heat) : 0.0;
            StockReserves.Want order = plan.order();
            boolean forOrder = order != null;
            UUID row = forOrder && order.once() ? order.row() : null;
            int orderLeft = row != null ? order.units() : 0;
            boolean forPlayer = row != null && forPlayer(row);
            boolean walked = false;
            int done = 0;
            while (done < plan.batches()) {
                String shortOf = shortOf(recipe, forOrder, pantry);
                if (shortOf != null) {
                    if (done == 0) snag(shortOf);
                    break;
                }
                boolean extraDish = extraDue(recipe);
                int amount = recipe.outputPerBatch() + (extraDish ? 1 : 0);
                ItemStack out = VillageFood.chef(new ItemStack(recipe.result(), amount), ctx.mgr);
                if (out.isEmpty() || (pack.capacityFor(out) < amount && !ctx.storageTakes(out))) {
                    if (done == 0) snag("no room");
                    break;
                }
                double walk = walked ? 0.0 : WALK_TICKS;
                if (ticksLeft < walk + batchTicks) {
                    outOfTime = true;
                    break;
                }
                if (onTable && kitchen.tableTicksLeft() < batchTicks) {
                    if (done == 0) snag("table busy");
                    break;
                }
                if (!onTable && kitchen.heatTicksLeft(heat) < batchTicks) {
                    if (done == 0) snag(heat.name().toLowerCase(Locale.ROOT) + " busy");
                    break;
                }
                if (burns && credit < 1.0) {
                    Item fuel = CookPlanner.bestFuel(pantry, settings, recipe, CookPlanner.coalScarce(pantry, settings));
                    if (fuel == null || !takeAll(Map.of(fuel, 1), pantry)) {
                        if (done == 0) snag("no fuel");
                        break;
                    }
                    credit += CookPlanner.smeltsPerFuel(fuel);
                }
                if (!takeAll(recipe.inputsPerBatch(), pantry)) {
                    if (done == 0) snag("ingredients held back");
                    break;
                }
                ticksLeft -= walk + batchTicks;
                walked = true;
                if (onTable) kitchen.useTable(batchTicks);
                else kitchen.useHeat(heat, batchTicks);
                if (burns) credit -= 1.0;
                useExtra(recipe, extraDish);
                give(out, pantry);
                done++;
                batches++;
                kitchen.cooked(recipe.result(), amount);
                AwayXp.cookBatch(ctx, recipe.result());
                if (row != null) {
                    // Past the order's units, the rest is the village's (StockReserves.onMade).
                    int forRow = Math.min(amount, orderLeft);
                    orderLeft -= forRow;
                    kitchen.onOrderMade(row, recipe.result(), npc.getNpcId(), forRow);
                    if (forPlayer) ctx.holdForPlayer(recipe.result(), forRow);
                }
                if (!goOn(plan, pantry, orderLeft)) break;
                if (carriedDishes() >= CookBalance.carryItems(level())) break;
            }
            if (burns) kitchen.keepWarmth(heat, credit);
            if (done > 0) visits++;
            return done;
        }

        /** Why one more batch cannot start ("short of sugar"), or null. Gold above the reserve, cane above the enchanters' share. */
        @Nullable
        private String shortOf(Recipe recipe, boolean forOrder, Pantry pantry) {
            Pantry free = forOrder ? pantry : pantry.withoutHeld();
            for (Map.Entry<Item, Integer> input : recipe.inputsPerBatch().entrySet()) {
                Item item = input.getKey();
                int have = item == Items.SUGAR_CANE && recipe.result() == Items.SUGAR ? free.spareCane() : free.count(item);
                if (item == Items.GOLD_INGOT && !forOrder) have -= settings.goldReserve();
                if (have < input.getValue()) return "short of " + BuiltInRegistries.ITEM.getKey(item).getPath();
            }
            return null;
        }

        /** Whether the visit goes on to another batch, as {@code canContinueRecipe}. */
        private boolean goOn(Plan plan, Pantry pantry, int orderLeft) {
            Recipe recipe = plan.recipe();
            Item result = recipe.result();
            if (plan.order() != null) {
                if (plan.order().once()) return orderLeft > 0;
                reserves.forgetCounts();
                return reserves.shortfall(ctx.mgr, ctx.level, result) > 0;
            }
            if (result == Items.CHARCOAL) return CookPlanner.wantsCharcoal(pantry, settings);
            if (CookPlanner.isSideJob(result)) return true;
            Integer gold = recipe.inputsPerBatch().get(Items.GOLD_INGOT);
            if (gold != null && pantry.count(Items.GOLD_INGOT) - gold < settings.goldReserve()) return false;
            return settings.allowsRecipe(result) && CookPlanner.deficit(pantry.withoutHeld(), settings, recipe) > 0;
        }

        private boolean forPlayer(UUID row) {
            for (StockReserves.Row candidate : reserves.rows(ctx.mgr.getVillageId())) {
                if (candidate.id.equals(row)) return candidate.forPlayer();
            }
            return false;
        }

        // ---------------------------------------------------------------------
        // The extra dish (no dice)
        // ---------------------------------------------------------------------

        /** Chance of one extra dish a batch: the staple's, else Baker's Hands (not a cake), as tickProcessing. */
        private double extraChance(Recipe recipe) {
            int level = level();
            if (CookActivity.isBonusStaple(recipe)) return CookBalance.extraStapleChance(level);
            if (level >= CookBalance.BAKERS_HANDS_LEVEL && VillageFood.isDish(recipe.result())
                    && recipe.result() != Items.CAKE) return CookBalance.BAKERS_HANDS_CHANCE;
            return 0.0;
        }

        private boolean extraDue(Recipe recipe) {
            double chance = extraChance(recipe);
            return chance > 0.0 && extra.getDouble(key(recipe.result())) + chance >= 1.0 - 1e-9;
        }

        private void useExtra(Recipe recipe, boolean gotOne) {
            double chance = extraChance(recipe);
            if (chance <= 0.0) return;
            String key = key(recipe.result());
            extra.putDouble(key, extra.getDouble(key) + chance - (gotOne ? 1.0 : 0.0));
        }

        // ---------------------------------------------------------------------
        // The pantry and the items
        // ---------------------------------------------------------------------

        /**
         * Storage plus what the cook carries, as {@code CookActivity.pantry}: the village's
         * appetite, the heat left in the stations, the Stock tab's rows (less what was cooked
         * for them while away) and what they hold back (the player's dishes too).
         */
        private Pantry pantry() {
            reserves.forgetCounts();
            List<StockReserves.Want> wants = new ArrayList<>();
            for (StockReserves.Want want : reserves.wants(ctx.mgr, ctx.level, Profession.COOK, npc.getNpcId())) {
                if (!want.once() || want.row() == null) {
                    wants.add(want);
                    continue;
                }
                int units = want.units() - kitchen.madeFor(want.row());
                if (units > 0) wants.add(new StockReserves.Want(want.row(), want.item(), units, true));
            }
            Map<Item, Integer> held = new HashMap<>(reserves.heldFor(ctx.mgr, ctx.level, Profession.COOK));
            // Orders cooked while away have spent their materials.
            kitchen.madeByDish().forEach((dish, units) -> StockReserves.materialsFor(dish, units)
                    .forEach((material, n) -> held.merge(material, -n, Integer::sum)));
            held.replaceAll((item, n) -> Math.max(0, n));
            for (Recipe recipe : CookActivity.recipes()) {
                Item dish = recipe.result();
                int locked = reserves.lockedAmount(ctx.mgr.getVillageId(), dish) + ctx.heldForPlayer(dish);
                if (locked > 0) held.merge(dish, locked, Integer::sum);
            }
            Pantry pantry = new Pantry(ctx.materialCounts()).forVillage(ctx.mgr)
                    .withDemand(CookDemand.perDay(npc.getBrainData()))
                    .withWarmth(kitchen.warmth())
                    .withOrders(wants, held);
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (!stack.isEmpty() && pack.mayUseAsMaterial(stack)) pantry.add(stack.getItem(), stack.getCount());
            }
            return pantry;
        }

        /**
         * Takes every {@code wanted} item, the pack first, then storage (the village's own only),
         * or nothing: what was taken goes back when one runs short.
         */
        private boolean takeAll(Map<Item, Integer> wanted, Pantry pantry) {
            List<ItemStack> fromPack = new ArrayList<>();
            List<ItemStack> fromStorage = new ArrayList<>();
            for (Map.Entry<Item, Integer> entry : wanted.entrySet()) {
                Item item = entry.getKey();
                int need = entry.getValue();
                int got = takeFromPack(item, need, fromPack);
                if (got < need) {
                    for (ItemStack stack : ctx.takeMaterial(item, need - got, Profession.COOK)) {
                        got += stack.getCount();
                        fromStorage.add(stack);
                    }
                }
                if (got < need) {
                    for (ItemStack stack : fromPack) {
                        if (!pack.addItem(stack)) ctx.store(stack);
                    }
                    for (ItemStack stack : fromStorage) ctx.store(stack);
                    return false;
                }
            }
            for (Map.Entry<Item, Integer> entry : wanted.entrySet()) {
                pantry.add(entry.getKey(), -entry.getValue());
                used.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            return true;
        }

        private int takeFromPack(Item item, int amount, List<ItemStack> taken) {
            int got = 0;
            for (int slot = 0; slot < InventoryManager.SIZE && got < amount; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (stack.isEmpty() || !stack.is(item) || !pack.mayUseAsMaterial(stack)) continue;
                int take = Math.min(amount - got, stack.getCount());
                taken.add(stack.copyWithCount(take));
                pack.reduceSlot(slot, take);
                got += take;
            }
            return got;
        }

        /** What a batch made: into the pack, a deposit walk when it is full, else straight to storage. */
        private void give(ItemStack out, Pantry pantry) {
            int count = out.getCount();
            pantry.add(out.getItem(), count);
            made.merge(out.getItem(), count, Integer::sum);
            if (pack.addItem(out)) return;
            if (bankable(out) && bank(true) > 0 && pack.addItem(out)) return;
            int stored = ctx.store(out);
            if (stored < count) {
                LOGGER.warn("[AwayCook] {} {} cooked with no room anywhere, lost", count - stored,
                        BuiltInRegistries.ITEM.getKey(out.getItem()));
            }
        }

        /** Dishes and charcoal from the pack to storage; with {@code walk}, a deposit walk. How many went. */
        private int bank(boolean walk) {
            int moved = 0;
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (stack.isEmpty() || !bankable(stack)) continue;
                int in = ctx.store(stack.copy());
                if (in <= 0) continue;
                pack.reduceSlot(slot, in);
                moved += in;
            }
            if (walk && moved > 0) {
                ticksLeft = Math.max(0.0, ticksLeft - WALK_TICKS);
                deposits++;
            }
            return moved;
        }

        private static boolean bankable(ItemStack stack) {
            return CookActivity.isDish(stack) || stack.is(Items.CHARCOAL);
        }

        private int carriedDishes() {
            int total = 0;
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (CookActivity.isDish(stack)) total += stack.getCount();
            }
            return total;
        }

        private boolean hasFreeSlot() {
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                if (pack.getSlot(slot).isEmpty()) return true;
            }
            return false;
        }

        // ---------------------------------------------------------------------
        // The day's books
        // ---------------------------------------------------------------------

        private int level() {
            return ctx.model().brain().cookSkillLevel();
        }

        private void snag(String reason) {
            snags.merge(reason, 1, Integer::sum);
            kitchen.snag(reason);
        }

        private void finish() {
            extra.getAllKeys().removeIf(key -> extra.getDouble(key) < 1e-6);
            npc.getBrainData().put(TAG_EXTRA, extra);
            if (batches > 0) AwayXp.workSession(ctx);
            Set<Item> items = new HashSet<>(made.keySet());
            items.addAll(used.keySet());
            for (Item item : items) {
                int net = made.getOrDefault(item, 0) - used.getOrDefault(item, 0);
                if (net > 0) ctx.made(item, net);
                else ctx.used(item, -net);
            }
            LOGGER.info("[AwayCook] {} day {}: {} batch(es) in {} visit(s), {} deposit walk(s), {} tick(s) left of {};"
                            + " made {}; used {}; snags {}",
                    npc.getNpcId().toString().substring(0, 8), ctx.day.index() + 1, batches, visits, deposits,
                    Math.round(ticksLeft), Math.round(dayTicks), AwayRun.counts(made), AwayRun.counts(used),
                    snags.isEmpty() ? "none" : snags);
        }

        private static String key(Item item) {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }
    }
}
