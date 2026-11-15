package com.alex.test.village.villagers.cook;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.KitchenBuilding.HeatSource;
import com.alex.test.village.building.KitchenStations;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.cook.behavior.CookActivity.Recipe;
import com.alex.test.village.stats.DietMemory;
import com.alex.test.village.stats.FoodCatalog;
import com.alex.test.village.villagers.cook.settings.CookSettings;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.enchanter.settings.EnchanterSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.supplies.WoodCutting;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the cook should make next, shared by live cooking ({@link CookActivity})
 * and the away cook ({@code economy/away/jobs/CookAway}) so both follow the
 * same rules.
 *
 * <ul>
 *   <li><b>Demand.</b> Each dish has a stock target. A dish is only cooked
 *       while storage (plus what the cook carries) holds fewer than that, so
 *       the cook no longer turns every ingredient in the village into food
 *       nobody needs yet. Once the cook has watched a day of eating
 *       ({@link CookDemand}), the target is about two days of what the
 *       village really eats, the Stock tab number being the most.</li>
 *   <li><b>Mode.</b> Among dishes below target: best food first, the
 *       emptiest shelf first, the most hunger per ingredient, or a varied
 *       menu. With under a day of cooked food in storage, the most hunger
 *       per ingredient whatever the mode.</li>
 *   <li><b>Stations.</b> Smoker 10 s a batch, furnace 20 s, campfire 60 s
 *       (twice vanilla; no fuel), crafting table 5 s; a blast furnace cooks no food
 *       (vanilla rules). Charcoal is furnace-only. A station is not lit for
 *       fewer than {@link #MIN_HEAT_BATCHES} batches while the dish is still
 *       on the shelf.</li>
 *   <li><b>Fuel.</b> Vanilla burn values: coal and charcoal smelt 8 items,
 *       a log or a plank 1.5. Charcoal is the way to get more out of the
 *       village's wood. Heat left in a station is kept for the next visit,
 *       and wood burns first while coal is below "Fuel to keep".</li>
 * </ul>
 */
public final class CookPlanner {

    /** {@code CookSettingsSchema.MODES}, same order. */
    public enum Mode { BEST_FOOD, BALANCED, STRETCH, VARIED }

    /** Village size the Stock tab targets are meant for. */
    public static final int TARGET_POPULATION = 10;

    /** Days of what the village really eats the cook keeps on the shelf. */
    public static final double DEMAND_DAYS = 2.0;
    /** The fewest of a dish kept once eating is tracked, so an untried dish still gets made. */
    public static final int MIN_SHELF = 8;
    /** Nutrition a villager eats a day (10-04 diaries: 24.5 on average). */
    public static final int DAILY_NUTRITION = 24;
    /**
     * Below this many days of cooked food in storage the cook feeds the
     * village first: the most food per ingredient, whatever its mode.
     */
    public static final double HUNGRY_FOOD_DAYS = 1.0;
    /**
     * The fewest batches worth lighting a station for while the dish is
     * still on the shelf; an empty shelf is cooked for at once.
     */
    public static final int MIN_HEAT_BATCHES = 3;

    /**
     * A dish to cook, where, and how many batches the shelf still wants. {@code order} is
     * the storage room's Stock tab row it is cooked for, or null for the cook's own targets.
     */
    public record Plan(Recipe recipe, @Nullable HeatSource heat, int batches, @Nullable StockReserves.Want order) {
        public Plan(Recipe recipe, @Nullable HeatSource heat, int batches) {
            this(recipe, heat, batches, null);
        }
    }

    /** Charcoal made in one visit to the furnace. */
    public static final int CHARCOAL_BATCH = 4;

    /** Vanilla: three planks make four bowls. */
    private static final int PLANKS_PER_BOWL_BATCH = 3;
    private static final int BOWLS_PER_BATCH = 4;

    /**
     * Sugar cane the cook leaves in storage for each enchanter that presses its
     * own paper: three books' worth. Sugar is a pinch per pie; the enchanter's
     * books are a whole job's material.
     */
    public static final int CANE_KEPT_PER_ENCHANTER = 9;

    /** Heat sources in the order the cook reaches for them: fastest first. */
    private static final List<HeatSource> HEAT_PREFERENCE =
            List.of(HeatSource.SMOKER, HeatSource.FURNACE, HeatSource.CAMPFIRE);

    private CookPlanner() {}

    // =========================================================================
    // Pantry
    // =========================================================================

    /** Items the cook can draw on, by count: storage, plus whatever it carries. */
    public static final class Pantry {
        private final Map<Item, Integer> counts;
        /** Stock targets are set for {@value #TARGET_POPULATION} villagers; bigger villages scale them up. */
        private double stockScale = 1.0;
        /** Share of villagers bored of each dish, for {@link Mode#VARIED}. */
        private Map<Item, Float> boredom = Map.of();
        /** The Stock tab rows the cook works on first, most urgent first. */
        private List<StockReserves.Want> orders = List.of();
        /** What those rows hold back: their ingredients, and the player's own dishes. */
        private Map<Item, Integer> held = Map.of();
        /** Villagers the cook feeds, for {@link #foodDays}; 0 when unknown. */
        private int villagers;
        /** Items the kitchen's warm stations can still cook without new fuel. */
        private double warmth;
        /** Dishes eaten from storage a day ({@link CookDemand}), or null before a day has been seen. */
        @Nullable private Map<Item, Double> demand;
        /** Sugar cane left for the village's enchanters' paper ({@link #CANE_KEPT_PER_ENCHANTER} each). */
        private int caneKept;

        public Pantry(Map<Item, Integer> counts) {
            this.counts = new HashMap<>(counts);
        }

        /** Heat left in the kitchen's stations from earlier visits. */
        public Pantry withWarmth(double warmth) {
            this.warmth = Math.max(0.0, warmth);
            return this;
        }

        /** What the village eats a day, by dish; null keeps the Stock tab targets as they are. */
        public Pantry withDemand(@Nullable Map<Item, Double> demand) {
            this.demand = demand;
            return this;
        }

        public int villagers() {
            return villagers;
        }

        /** The storage room's Stock tab: rows to cook for, and what they hold back. */
        public Pantry withOrders(List<StockReserves.Want> orders, Map<Item, Integer> held) {
            this.orders = List.copyOf(orders);
            this.held = Map.copyOf(held);
            return this;
        }

        public List<StockReserves.Want> orders() {
            return orders;
        }

        /**
         * This pantry less what the Stock tab holds back: what the cook's own stock
         * targets may use, and how full its shelves really are.
         */
        public Pantry withoutHeld() {
            if (held.isEmpty()) return this;
            Pantry free = new Pantry(counts);
            held.forEach((item, n) -> free.counts.merge(item, -n, Integer::sum));
            free.counts.replaceAll((item, n) -> Math.max(0, n));
            free.stockScale = stockScale;
            free.boredom = boredom;
            free.villagers = villagers;
            free.warmth = warmth;
            free.demand = demand;
            free.caneKept = caneKept;
            return free;
        }

        /** Sugar cane the cook may turn into sugar: what is above the enchanters' share. */
        public int spareCane() {
            return Math.max(0, count(Items.SUGAR_CANE) - caneKept);
        }

        /** Who the cook is cooking for: how many villagers, and what they are tired of. */
        public Pantry forVillage(@Nullable VillageManager village) {
            if (village == null) return this;
            Collection<VillagerModel> villagers = village.getVillagers();
            this.villagers = villagers.size();
            stockScale = Math.max(1.0, villagers.size() / (double) TARGET_POPULATION);
            if (villagers.isEmpty()) return this;
            caneKept = caneKept(village);
            Map<Item, Float> bored = new HashMap<>();
            for (VillagerModel villager : villagers) {
                DietMemory diet = villager.brain().needs().diet;
                for (Recipe recipe : CookActivity.recipes()) {
                    if (diet.timesEaten(recipe.result()) >= 2) {
                        bored.merge(recipe.result(), 1f / villagers.size(), Float::sum);
                    }
                }
            }
            boredom = bored;
            return this;
        }

        /**
         * The shelf target for {@code item}: the Stock tab's number scaled to
         * the village's size, which stays the most the cook keeps. Once a day
         * of eating has been seen, about DEMAND_DAYS days of what
         * the village really eats of it, never under MIN_SHELF, so
         * a dish nobody has had yet still gets a try.
         */
        public int target(CookSettings settings, Item item) {
            int target = settings.stockTarget(item);
            if (target <= 0) return 0;
            int scaled = (int) Math.round(target * stockScale);
            if (demand == null) return scaled;
            int eaten = (int) Math.ceil(DEMAND_DAYS * demand.getOrDefault(item, 0.0));
            return Math.min(scaled, Math.max(MIN_SHELF, eaten));
        }

        /**
         * Days the cooked food in this pantry would feed the village at
         * DAILY_NUTRITION nutrition a villager; huge when the
         * village size is unknown.
         */
        public double foodDays() {
            if (villagers <= 0) return Double.MAX_VALUE;
            double nutrition = 0;
            for (Recipe recipe : CookActivity.recipes()) {
                nutrition += count(recipe.result()) * (double) recipe.nutrition();
            }
            return nutrition / (villagers * (double) DAILY_NUTRITION);
        }

        public float boredom(Item item) {
            return boredom.getOrDefault(item, 0f);
        }

        public int count(Item item) {
            return counts.getOrDefault(item, 0);
        }

        public void add(Item item, int delta) {
            counts.merge(item, delta, Integer::sum);
        }

        public int count(TagKey<Item> tag) {
            int total = 0;
            for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
                if (is(entry.getKey(), tag)) total += Math.max(0, entry.getValue());
            }
            return total;
        }

        /** The most plentiful item in {@code tag}, or null when there is none. */
        @Nullable
        public Item mostOf(TagKey<Item> tag) {
            Item best = null;
            int bestCount = 0;
            for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > bestCount && is(entry.getKey(), tag)) {
                    best = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            return best;
        }

        Map<Item, Integer> entries() {
            return counts;
        }
    }

    private static boolean is(Item item, TagKey<Item> tag) {
        return item.builtInRegistryHolder().is(tag);
    }

    /** Sugar cane the cook leaves in {@code village}'s storage for its enchanters' paper. */
    public static int caneKept(VillageManager village) {
        int kept = 0;
        for (VillagerModel villager : village.getVillagers()) {
            if (pressesPaper(villager.puppet())) kept += CANE_KEPT_PER_ENCHANTER;
        }
        return kept;
    }

    /** An enchanter at work that makes its own paper from sugar cane ("Paper and Book Crafting"). */
    private static boolean pressesPaper(@Nullable NpcEntity npc) {
        if (npc == null || npc.getProfession() != Profession.ENCHANTER) return false;
        ProfessionSettings settings = ProfessionSettings.of(npc, EnchanterSettingsSchema.SCHEMA);
        return settings.getBool(EnchanterSettingsSchema.WORK_ENABLED)
                && settings.getBool(EnchanterSettingsSchema.MAKE_BOOKS);
    }

    // =========================================================================
    // Stations
    // =========================================================================

    /**
     * Ticks a batch takes at level 1 on {@code heat}; null is the crafting
     * table. Twice vanilla: smoker 10 s, furnace 20 s, campfire 60 s; table 5 s.
     */
    public static int stationTicks(@Nullable HeatSource heat) {
        if (heat == null) return CookBalance.TABLE_TICKS;
        return switch (heat) {
            case SMOKER -> CookBalance.SMOKER_TICKS;
            case FURNACE -> CookBalance.FURNACE_TICKS;
            case CAMPFIRE -> CookBalance.CAMPFIRE_TICKS;
            // Cooks no food (canCook); only here so the switch is whole.
            case BLAST_FURNACE -> CookBalance.FURNACE_TICKS;
        };
    }

    public static boolean burnsFuel(@Nullable HeatSource heat) {
        return heat == HeatSource.SMOKER || heat == HeatSource.FURNACE;
    }

    /** Vanilla's rules: smokers and campfires cook food only, blast furnaces no food. */
    public static boolean canCook(HeatSource heat, Recipe recipe) {
        return switch (heat) {
            case FURNACE -> true;
            case SMOKER, CAMPFIRE -> recipe.result() != Items.CHARCOAL;
            case BLAST_FURNACE -> false;
        };
    }

    /** The fastest heat source that can cook {@code recipe}; one that burns fuel only when there is fuel. */
    @Nullable
    public static HeatSource pickHeat(KitchenStations kitchen, Recipe recipe, boolean hasFuel) {
        for (HeatSource heat : HEAT_PREFERENCE) {
            if (kitchen.getHeatPos(heat) == null || !canCook(heat, recipe)) continue;
            if (burnsFuel(heat) && !hasFuel) continue;
            return heat;
        }
        return null;
    }

    // =========================================================================
    // Fuel
    // =========================================================================

    /** Items one piece of fuel smelts, as in vanilla; 0 when it is not fuel to a cook. */
    public static double smeltsPerFuel(Item item) {
        if (item == Items.COAL || item == Items.CHARCOAL) return 8.0;
        if (isWood(item)) return 1.5;
        return 0.0;
    }

    private static boolean isWood(Item item) {
        return is(item, ItemTags.LOGS) || is(item, ItemTags.PLANKS);
    }

    public static boolean isFuel(Item item) {
        return smeltsPerFuel(item) > 0;
    }

    /**
     * Whether {@code item} may go on the fire for {@code recipe}. Wood is always
     * allowed when making charcoal, or a village with no coal could never make
     * its first piece.
     */
    public static boolean mayBurn(Item item, CookSettings settings, Recipe recipe) {
        if (!isFuel(item)) return false;
        return !isWood(item) || settings.burnsWood() || recipe.result() == Items.CHARCOAL;
    }

    /**
     * Items the fuel in {@code pantry} could smelt, after {@code batches} worth of the
     * recipe's own inputs, plus the heat its warm stations still hold.
     */
    public static double fuelSmelts(Pantry pantry, CookSettings settings, Recipe recipe, int batches) {
        double smelts = pantry.warmth;
        for (Map.Entry<Item, Integer> entry : pantry.entries().entrySet()) {
            Item item = entry.getKey();
            if (!mayBurn(item, settings, recipe)) continue;
            int spare = entry.getValue() - recipe.inputsPerBatch().getOrDefault(item, 0) * batches;
            if (spare > 0) smelts += spare * smeltsPerFuel(item);
        }
        return smelts;
    }

    /**
     * Whether coal and charcoal are below the "Fuel to keep" setting: the
     * village is short of them, so wood should burn first.
     */
    public static boolean coalScarce(Pantry pantry, CookSettings settings) {
        return pantry.count(Items.COAL) + pantry.count(Items.CHARCOAL) < settings.fuelComfort();
    }

    /** {@link #bestFuel(Pantry, CookSettings, Recipe, boolean)}, judging coal from this pantry. */
    @Nullable
    public static Item bestFuel(Pantry pantry, CookSettings settings, Recipe recipe) {
        return bestFuel(pantry, settings, recipe, coalScarce(pantry, settings));
    }

    /**
     * The fuel to take: coal and charcoal, then planks (a log makes four), then
     * logs, never eating into one batch of the recipe's own inputs. While coal
     * is scarce, wood the cook may burn goes first, so the village's coal is
     * kept for torches and for charcoal-less days.
     */
    @Nullable
    public static Item bestFuel(Pantry pantry, CookSettings settings, Recipe recipe, boolean coalScarce) {
        Item best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Map.Entry<Item, Integer> entry : pantry.entries().entrySet()) {
            Item item = entry.getKey();
            if (!mayBurn(item, settings, recipe)) continue;
            if (entry.getValue() - recipe.inputsPerBatch().getOrDefault(item, 0) <= 0) continue;
            int rank = !isWood(item) ? (coalScarce ? 3 : 0) : is(item, ItemTags.PLANKS) ? 1 : 2;
            if (rank < bestRank) {
                best = item;
                bestRank = rank;
            }
        }
        return best;
    }

    // =========================================================================
    // Demand
    // =========================================================================

    /** Dishes short of the stock target, counting everything in the pantry. */
    public static int deficit(Pantry pantry, CookSettings settings, Recipe recipe) {
        return Math.max(0, pantry.target(settings, recipe.result()) - pantry.count(recipe.result()));
    }

    /** Batches worth making this visit: the shelf's shortfall, capped by skill. */
    public static int wantedBatches(Pantry pantry, CookSettings settings, Recipe recipe, int level) {
        int deficit = deficit(pantry, settings, recipe);
        int batches = (deficit + recipe.outputPerBatch() - 1) / recipe.outputPerBatch();
        batches = Math.min(CookBalance.batchesPerVisit(level), batches);
        // Fetching gold for batches the reserve would then veto pulled it out
        // of storage for the whole trip, below the amount the player set aside.
        Integer goldPerBatch = recipe.inputsPerBatch().get(Items.GOLD_INGOT);
        if (goldPerBatch != null && goldPerBatch > 0) {
            int spare = pantry.count(Items.GOLD_INGOT) - settings.goldReserve();
            batches = Math.min(batches, Math.max(0, spare / goldPerBatch));
        }
        return batches;
    }

    // =========================================================================
    // Choosing
    // =========================================================================

    /** The dish to cook next, or null when every allowed dish is stocked or out of reach. */
    @Nullable
    public static Plan choose(KitchenStations kitchen, Pantry pantry, CookSettings settings, int level) {
        return choose(kitchen, pantry, settings, level, Set.of());
    }

    /**
     * The same, leaving out the dishes in {@code skip}: the away cook's dishes that hit a
     * snag today (no bowls, no fuel, no room), so it goes on with the next one.
     */
    @Nullable
    public static Plan choose(KitchenStations kitchen, Pantry pantry, CookSettings settings, int level,
                              Set<Item> skip) {
        // The player's Stock tab rows first, in their order.
        Plan ordered = chooseOrder(kitchen, pantry, settings, level, skip);
        if (ordered != null) return ordered;
        pantry = pantry.withoutHeld();
        // Hunger first: with less than a day of cooked food in storage, the
        // most food per ingredient, whatever the mode. One cake (nine items)
        // while the village eats raw carrots helps nobody.
        Mode mode = pantry.foodDays() < HUNGRY_FOOD_DAYS ? Mode.STRETCH : settings.mode();
        Recipe best = null;
        double bestScore = 0;
        for (Recipe recipe : CookActivity.recipes()) {
            if (skip.contains(recipe.result()) || !isCandidate(kitchen, pantry, settings, recipe, level)) continue;
            double score = switch (mode) {
                // Tastiest first; gold only when nothing as good is free. Ties
                // keep table order.
                case BEST_FOOD -> FoodCatalog.baseTaste(recipe.result())
                        - (recipe.inputsPerBatch().containsKey(Items.GOLD_INGOT) ? 3 : 0);
                // Emptiest shelf first.
                case BALANCED -> shelfFill(pantry, settings, recipe);
                case STRETCH -> stretch(recipe);
                // Emptiest shelf, but away from what the village is tired of
                // and a little toward the tastier dish.
                case VARIED -> shelfFill(pantry, settings, recipe)
                        - 2.0 * pantry.boredom(recipe.result())
                        + 0.05 * FoodCatalog.baseTaste(recipe.result());
            };
            if (best == null || score > bestScore) {
                best = recipe;
                bestScore = score;
            }
        }
        return best == null ? null : plan(kitchen, pantry, settings, best, level);
    }

    /**
     * The first Stock tab row the cook can start on: the dish known at its level, a
     * station for it, and one batch of inputs. Rows ignore the Recipes tab and the gold
     * reserve, which are the village's limits, not the player's.
     */
    @Nullable
    public static Plan chooseOrder(KitchenStations kitchen, Pantry pantry, CookSettings settings, int level) {
        return chooseOrder(kitchen, pantry, settings, level, Set.of());
    }

    /** The same, leaving out the dishes in {@code skip}. */
    @Nullable
    public static Plan chooseOrder(KitchenStations kitchen, Pantry pantry, CookSettings settings, int level,
                                   Set<Item> skip) {
        for (StockReserves.Want want : pantry.orders()) {
            if (skip.contains(want.item())) continue;
            Recipe recipe = StockReserves.recipeFor(want.item());
            if (recipe == null || !isOrderCandidate(kitchen, pantry, settings, recipe, level)) continue;
            HeatSource heat = recipe.needsFurnace()
                    ? pickHeat(kitchen, recipe, fuelSmelts(pantry, settings, recipe, 1) >= 1)
                    : null;
            int batches = Math.min(CookBalance.batchesPerVisit(level), StockReserves.batches(recipe, want.units()));
            return new Plan(recipe, heat, Math.max(1, batches), want);
        }
        return null;
    }

    /** Whether a Stock tab row's dish can be started: known, a station, one batch of inputs. */
    public static boolean isOrderCandidate(KitchenStations kitchen, Pantry pantry,
                                           CookSettings settings, Recipe recipe, int level) {
        if (!isUnlocked(recipe, level)) return false;
        if (!hasInputs(pantry, settings, recipe, true)) return false;
        if (!recipe.needsFurnace()) return kitchen.getCraftingTablePos() != null;
        return pickHeat(kitchen, recipe, fuelSmelts(pantry, settings, recipe, 1) >= 1) != null;
    }

    /** Minus how full the shelf is against its target: the emptiest scores highest. */
    private static double shelfFill(Pantry pantry, CookSettings settings, Recipe recipe) {
        return -pantry.count(recipe.result())
                / (double) Math.max(1, pantry.target(settings, recipe.result()));
    }

    /** Allowed, unlocked, short of its target, has a station, and one batch's inputs are in the pantry. */
    public static boolean isCandidate(KitchenStations kitchen, Pantry pantry,
                                      CookSettings settings, Recipe recipe, int level) {
        if (!settings.allowsRecipe(recipe.result())) return false;
        if (!isUnlocked(recipe, level)) return false;
        if (deficit(pantry, settings, recipe) <= 0) return false;
        if (!hasInputs(pantry, settings, recipe)) return false;
        if (!recipe.needsFurnace()) return kitchen.getCraftingTablePos() != null;
        if (tooSmallForHeat(pantry, settings, recipe, level)) return false;
        return pickHeat(kitchen, recipe, fuelSmelts(pantry, settings, recipe, 1) >= 1) != null;
    }

    /**
     * A heat dish not worth lighting a station for yet: fewer than
     * {@link #MIN_HEAT_BATCHES} batches to make while the shelf still has
     * some and the village is not short of food. The raw food waits in
     * storage for a fuller visit (villagers leave it for the cook).
     */
    private static boolean tooSmallForHeat(Pantry pantry, CookSettings settings, Recipe recipe, int level) {
        if (pantry.count(recipe.result()) <= 0 || pantry.foodDays() < HUNGRY_FOOD_DAYS) return false;
        int batches = wantedBatches(pantry, settings, recipe, level);
        for (Map.Entry<Item, Integer> entry : recipe.inputsPerBatch().entrySet()) {
            batches = Math.min(batches, pantry.count(entry.getKey()) / Math.max(1, entry.getValue()));
        }
        return batches < MIN_HEAT_BATCHES;
    }

    public static Plan plan(KitchenStations kitchen, Pantry pantry, CookSettings settings,
                            Recipe recipe, int level) {
        HeatSource heat = recipe.needsFurnace()
                ? pickHeat(kitchen, recipe, fuelSmelts(pantry, settings, recipe, 1) >= 1)
                : null;
        return new Plan(recipe, heat, Math.max(1, wantedBatches(pantry, settings, recipe, level)));
    }

    /**
     * Hunger per ingredient item: what Stretch mode ranks by. Cookies (32 hunger
     * from three items) and roast meat (one item each) lead; cake, nine items
     * for one dish, comes last.
     */
    private static double stretch(Recipe recipe) {
        int items = 0;
        for (int amount : recipe.inputsPerBatch().values()) items += amount;
        return recipe.nutrition() * recipe.outputPerBatch() / (double) Math.max(1, items);
    }

    /**
     * What one batch of {@code result} teaches, in lessons (a roast is one):
     * staples half, stews and soups one and a half, rich dishes two, and side
     * jobs (charcoal, bowls, sugar, planks) a quarter. Harder cooking teaches more.
     */
    public static double lessons(Item result) {
        if (result == Items.BREAD || result == Items.BAKED_POTATO
                || result == Items.COOKIE || result == Items.DRIED_KELP) return 0.5;
        if (result == Items.MUSHROOM_STEW || result == Items.BEETROOT_SOUP) return 1.5;
        if (result == Items.CAKE || result == Items.PUMPKIN_PIE || result == Items.RABBIT_STEW
                || result == Items.GOLDEN_CARROT || result == Items.GOLDEN_APPLE) return 2.0;
        if (isSideJob(result)) return 0.25;
        return 1.0;
    }

    /** Whether a cook of {@code level} knows {@code recipe}: always. No food is locked behind a level. */
    public static boolean isUnlocked(Recipe recipe, int level) {
        return true;
    }

    private static boolean hasInputs(Pantry pantry, CookSettings settings, Recipe recipe) {
        return hasInputs(pantry, settings, recipe, false);
    }

    private static boolean hasInputs(Pantry pantry, CookSettings settings, Recipe recipe, boolean ignoreReserve) {
        for (Map.Entry<Item, Integer> entry : recipe.inputsPerBatch().entrySet()) {
            Item item = entry.getKey();
            int have = pantry.count(item);
            if (item == Items.GOLD_INGOT && !ignoreReserve) have -= settings.goldReserve();
            if (item == Items.BOWL) have += bowlsFromPlanks(pantry);
            // The cook presses its own sugar from the cane the enchanters can spare.
            if (item == Items.SUGAR) have += pantry.spareCane();
            if (have < entry.getValue()) return false;
        }
        return true;
    }

    /** Bowls the pantry's planks would make, and its logs once cut into planks. */
    private static int bowlsFromPlanks(Pantry pantry) {
        int planks = pantry.count(ItemTags.PLANKS) + pantry.count(ItemTags.LOGS) * WoodCutting.PLANKS_PER_LOG;
        return planks / PLANKS_PER_BOWL_BATCH * BOWLS_PER_BATCH;
    }

    /** Dishes of Stock tab rows the cook could start on now. */
    public static List<Recipe> orderCandidates(KitchenStations kitchen, Pantry pantry,
                                               CookSettings settings, int level) {
        List<Recipe> recipes = new java.util.ArrayList<>();
        for (StockReserves.Want want : pantry.orders()) {
            Recipe recipe = StockReserves.recipeFor(want.item());
            if (recipe != null && !recipes.contains(recipe)
                    && isOrderCandidate(kitchen, pantry, settings, recipe, level)) recipes.add(recipe);
        }
        return recipes;
    }

    /** Inputs of every dish the cook could still make: what it should not bank yet. */
    public static Set<Item> worthKeeping(KitchenStations kitchen, Pantry pantry,
                                         CookSettings settings, int level) {
        Set<Item> keep = new HashSet<>();
        for (Recipe recipe : orderCandidates(kitchen, pantry, settings, level)) {
            keep.addAll(recipe.inputsPerBatch().keySet());
        }
        Pantry free = pantry.withoutHeld();
        for (Recipe recipe : CookActivity.recipes()) {
            if (isCandidate(kitchen, free, settings, recipe, level)) {
                keep.addAll(recipe.inputsPerBatch().keySet());
            }
        }
        return keep;
    }

    // =========================================================================
    // Side jobs: sugar, bowls, planks and charcoal
    // =========================================================================

    /** Whether {@code item} is one of the cook's side jobs: made for its own dishes, not eaten. */
    public static boolean isSideJob(Item item) {
        return item == Items.BOWL || item == Items.SUGAR || item == Items.CHARCOAL || is(item, ItemTags.PLANKS);
    }

    /**
     * Sugar for {@code plan}, one cane a sugar as in vanilla: as much as its
     * batches still lack, and only for batches the other ingredients cover
     * too, so no cane becomes sugar that then sits unused. Never the cane the
     * enchanters' paper needs ({@link Pantry#spareCane}). Null when no sugar
     * is to be made.
     */
    @Nullable
    public static Plan sugar(Pantry pantry, Plan plan) {
        Integer perBatch = plan.recipe().inputsPerBatch().get(Items.SUGAR);
        if (perBatch == null || perBatch <= 0) return null;
        int batches = plan.batches();
        for (Map.Entry<Item, Integer> entry : plan.recipe().inputsPerBatch().entrySet()) {
            Item item = entry.getKey();
            if (item == Items.SUGAR) continue;
            int have = pantry.count(item) + (item == Items.BOWL ? bowlsFromPlanks(pantry) : 0);
            batches = Math.min(batches, have / Math.max(1, entry.getValue()));
        }
        int wanted = batches * perBatch - pantry.count(Items.SUGAR);
        int pressed = Math.min(wanted, pantry.spareCane());
        if (pressed <= 0) return null;
        Recipe recipe = new Recipe(Items.SUGAR, 1, Map.of(Items.SUGAR_CANE, 1), false, 0);
        return new Plan(recipe, null, pressed);
    }

    /**
     * Bowls for {@code stewBatches} stews, from the most plentiful planks. With
     * no three planks of one wood, a log is cut into four planks first (the
     * plan returned is then the planks); null when there is neither.
     */
    @Nullable
    public static Plan bowls(Pantry pantry, int stewBatches) {
        int wanted = (stewBatches + BOWLS_PER_BATCH - 1) / BOWLS_PER_BATCH;
        Item plank = pantry.mostOf(ItemTags.PLANKS);
        int affordable = plank == null ? 0 : pantry.count(plank) / PLANKS_PER_BOWL_BATCH;
        if (affordable > 0) {
            Recipe recipe = new Recipe(Items.BOWL, BOWLS_PER_BATCH,
                    Map.of(plank, PLANKS_PER_BOWL_BATCH), false, 0);
            return new Plan(recipe, null, Math.max(1, Math.min(wanted, affordable)));
        }
        // No three of one wood: three of any, as vanilla's bowl takes mixed planks. Before
        // this, two oak and one spruce counted as bowls (hasInputs) but made none, and the
        // cook stood stuck on the stew it had picked.
        Map<Item, Integer> mixed = mixedPlanks(pantry);
        if (mixed != null) {
            return new Plan(new Recipe(Items.BOWL, BOWLS_PER_BATCH, mixed, false, 0), null, 1);
        }
        Item log = pantry.mostOf(ItemTags.LOGS);
        if (log == null) return null;
        int planksShort = wanted * PLANKS_PER_BOWL_BATCH - (plank == null ? 0 : pantry.count(plank));
        int logs = (planksShort + WoodCutting.PLANKS_PER_LOG - 1) / WoodCutting.PLANKS_PER_LOG;
        Recipe recipe = new Recipe(WoodCutting.planksFor(log), WoodCutting.PLANKS_PER_LOG,
                Map.of(log, 1), false, 0);
        return new Plan(recipe, null, Math.clamp(logs, 1, pantry.count(log)));
    }

    /** Three planks of the most plentiful woods, for one batch of bowls; null with fewer than three. */
    @Nullable
    private static Map<Item, Integer> mixedPlanks(Pantry pantry) {
        if (pantry.count(ItemTags.PLANKS) < PLANKS_PER_BOWL_BATCH) return null;
        List<Map.Entry<Item, Integer>> planks = new java.util.ArrayList<>();
        for (Map.Entry<Item, Integer> entry : pantry.entries().entrySet()) {
            if (entry.getValue() > 0 && is(entry.getKey(), ItemTags.PLANKS)) planks.add(entry);
        }
        planks.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        Map<Item, Integer> inputs = new java.util.LinkedHashMap<>();
        int left = PLANKS_PER_BOWL_BATCH;
        for (Map.Entry<Item, Integer> entry : planks) {
            int take = Math.min(left, entry.getValue());
            inputs.put(entry.getKey(), take);
            left -= take;
            if (left == 0) break;
        }
        return left == 0 ? java.util.Collections.unmodifiableMap(inputs) : null;
    }

    /**
     * Whether the village would be better off with charcoal than with the pile
     * of logs it is feeding into furnaces: coal and charcoal below the comfort
     * level, and logs to spare.
     */
    public static boolean wantsCharcoal(Pantry pantry, CookSettings settings) {
        if (!settings.makesCharcoal()) return false;
        if (pantry.count(Items.COAL) + pantry.count(Items.CHARCOAL) >= settings.fuelComfort()) {
            return false;
        }
        int logs = pantry.count(ItemTags.LOGS);
        return logs > 0 && logs >= settings.logsToSpare();
    }

    /** A furnace visit turning the most plentiful logs into charcoal, or null. */
    @Nullable
    public static Plan charcoal(KitchenStations kitchen, Pantry pantry, CookSettings settings) {
        if (kitchen.getHeatPos(HeatSource.FURNACE) == null) return null;
        if (!wantsCharcoal(pantry, settings)) return null;
        Item log = pantry.mostOf(ItemTags.LOGS);
        if (log == null) return null;
        // Nutrition 0: charcoal is fuel, and the table is otherwise ordered by
        // what a dish is worth to eat.
        Recipe recipe = new Recipe(Items.CHARCOAL, 1, Map.of(log, 1), true, 0);
        if (fuelSmelts(pantry, settings, recipe, 1) < 1) return null;
        return new Plan(recipe, HeatSource.FURNACE, CHARCOAL_BATCH);
    }
}
