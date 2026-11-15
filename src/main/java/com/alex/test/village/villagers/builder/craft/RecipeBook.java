package com.alex.test.village.villagers.builder.craft;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.builder.craft.MaterialPlanner.Floor;
import com.alex.test.village.villagers.builder.craft.MaterialPlanner.Kind;
import com.alex.test.village.villagers.builder.project.BuildOrders;
import com.alex.test.village.villagers.builder.project.GivenBlocks;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.neoforged.neoforge.common.Tags;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The game's side of {@link MaterialPlanner}: Minecraft's crafting, stonecutting and smelting
 * recipes (vanilla and modded) as the planner's plain records, and the rules on what the
 * Builder may make blocks from. Built once, again after /reload (a new recipe manager).
 *
 * <p>Kept: shaped and shapeless crafting (no "special" ones like dyeing armour), every
 * stonecutting and smelting recipe, with a plain result. An ingredient that leaves something
 * behind (a bucket, a bottle), is damageable, or carries data is dropped from its slot; a slot
 * with nothing left drops the recipe.
 */
public final class RecipeBook {
    private RecipeBook() {}

    @Nullable private static RecipeManager builtFor;
    @Nullable private static MaterialPlanner.Book book, noSmelting;

    public static synchronized MaterialPlanner.Book get(MinecraftServer server) {
        RecipeManager manager = server.getRecipeManager();
        if (book == null || builtFor != manager) {
            long started = System.nanoTime();
            book = build(manager, server.registryAccess());
            noSmelting = null;
            builtFor = manager;
            DynamicVillages.LOGGER.debug("[BuilderCraft] recipe book: {} results, {} fuels in {} ms",
                    book.recipes().size(), book.fuel().size(), (System.nanoTime() - started) / 1_000_000);
        }
        return book;
    }

    /** The book without furnace recipes: what the Builder really makes until it smelts (round 6). */
    public static synchronized MaterialPlanner.Book withoutSmelting(MinecraftServer server) {
        MaterialPlanner.Book full = get(server);
        if (noSmelting == null) noSmelting = full.without(Kind.SMELT);
        return noSmelting;
    }

    private static MaterialPlanner.Book build(RecipeManager manager, RegistryAccess access) {
        Map<String, List<MaterialPlanner.Recipe>> recipes = new HashMap<>();
        for (RecipeHolder<CraftingRecipe> holder : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (recipe.isSpecial() || !(recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)) continue;
            add(recipes, Kind.CRAFT, recipe.getIngredients(), recipe.getResultItem(access));
        }
        for (RecipeHolder<StonecutterRecipe> holder : manager.getAllRecipesFor(RecipeType.STONECUTTING)) {
            add(recipes, Kind.CUT, holder.value().getIngredients(), holder.value().getResultItem(access));
        }
        for (RecipeHolder<SmeltingRecipe> holder : manager.getAllRecipesFor(RecipeType.SMELTING)) {
            add(recipes, Kind.SMELT, holder.value().getIngredients(), holder.value().getResultItem(access));
        }
        Map<String, List<MaterialPlanner.Recipe>> frozen = new HashMap<>();
        recipes.forEach((item, list) -> frozen.put(item, List.copyOf(list)));
        Map<String, Double> fuel = new HashMap<>();
        Map<String, Integer> stacks = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) continue;
            if (stack.getMaxStackSize() != MaterialPlanner.STACK) stacks.put(id(item), stack.getMaxStackSize());
            // Burn time in ticks; a furnace smelts one item in 200.
            int burn = stack.getBurnTime(RecipeType.SMELTING);
            if (burn > 0 && !stack.isDamageableItem() && !item.hasCraftingRemainingItem()) fuel.put(id(item), burn / 200.0);
        }
        return new MaterialPlanner.Book(Map.copyOf(frozen), Map.copyOf(fuel), Map.copyOf(stacks));
    }

    private static void add(Map<String, List<MaterialPlanner.Recipe>> recipes, Kind kind, List<Ingredient> ingredients,
                            ItemStack result) {
        if (result.isEmpty() || !result.getComponentsPatch().isEmpty()) return;
        List<List<String>> slots = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            List<String> fits = new ArrayList<>();
            for (ItemStack option : ingredient.getItems()) {
                if (option.isEmpty() || !option.getComponentsPatch().isEmpty() || option.isDamageableItem()
                        || option.getItem().hasCraftingRemainingItem()) continue;
                String key = id(option.getItem());
                if (!fits.contains(key)) fits.add(key);
            }
            if (fits.isEmpty()) return;
            slots.add(List.copyOf(fits));
        }
        if (slots.isEmpty()) return;
        String made = id(result.getItem());
        recipes.computeIfAbsent(made, key -> new ArrayList<>())
                .add(new MaterialPlanner.Recipe(kind, List.copyOf(slots), made, result.getCount()));
    }

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    // =========================================================================
    // What may be used
    // =========================================================================

    /**
     * Never made into blocks: food, seeds, bone meal, the cook's ingredients, damageable things,
     * emeralds and the mod's own items (tokens).
     */
    public static boolean never(Item item) {
        ItemStack stack = new ItemStack(item);
        return VillageItemOwnership.isVillagerFood(stack) || stack.is(Tags.Items.SEEDS)
                || VillageItemOwnership.isFarmSupply(stack) || CookActivity.INGREDIENTS.contains(item)
                || stack.isDamageableItem() || item == Items.EMERALD || item == Items.EMERALD_BLOCK
                || BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(DynamicVillages.MODID);
    }

    /** Diamond, gold, netherite and lapis, in any form: only with "Use Valuables" (round 7). */
    public static boolean valuable(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        return path.contains("diamond") || path.contains("gold") || path.contains("netherite") || path.contains("lapis");
    }

    /** The last fuel, wood and iron stay: 16 coal or charcoal, 32 logs, 16 iron ingots. */
    public static List<Floor> floors() {
        Set<String> logs = new HashSet<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.LOGS)) logs.add(id(holder.value()));
        return List.of(new Floor(Set.of(id(Items.COAL), id(Items.CHARCOAL)), 16), new Floor(Set.copyOf(logs), 32),
                new Floor(Set.of(id(Items.IRON_INGOT)), 16));
    }

    /** A village good with no data of its own: only the village's mark (named or enchanted ones are left). */
    private static boolean plainGood(ItemStack stack) {
        DataComponentPatch patch = stack.getComponentsPatch();
        return patch.isEmpty() || patch.size() == 1 && patch.get(DataComponents.CUSTOM_DATA) != null;
    }

    /**
     * What the Builder may make blocks from out of the village's goods: storage (as much as
     * the Stock tab reserves leave it) plus its own pack, less what the village's orders still
     * need as that item, less the floors.
     */
    public static Map<String, Integer> villageLedger(ServerLevel level, VillageManager village,
                                                     @Nullable NpcEntity builder, boolean useValuables) {
        Map<Item, Integer> found = new HashMap<>();
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (StorageInventory chest : storage.inventories(level)) {
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack stack = chest.peek(i);
                    if (stack.isEmpty() || !plainGood(stack) || !storage.mayUseAsMaterial(level, stack)) continue;
                    found.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }
        StockReserves reserves = StockReserves.get(level.getServer());
        Map<String, Integer> stored = new HashMap<>();
        for (Map.Entry<Item, Integer> each : found.entrySet()) {
            int free = Math.min(each.getValue(), reserves.free(village, level, each.getKey(), Profession.BUILDER));
            if (free > 0) stored.put(id(each.getKey()), free);
        }
        Map<String, Integer> carried = new HashMap<>();
        if (builder != null) {
            for (ItemStack stack : builder.getInventory().getSlots()) {
                if (!stack.isEmpty() && plainGood(stack) && builder.getInventory().mayUseAsMaterial(stack)) {
                    carried.merge(id(stack.getItem()), stack.getCount(), Integer::sum);
                }
            }
        }
        return MaterialPlanner.spare(stored, carried, ordersNeed(level.getServer(), village), floors(),
                id -> never(item(id)), id -> valuable(item(id)), useValuables);
    }

    /** Everyone's given blocks for the village, less what its orders still need as they are. No floors. */
    public static Map<String, Integer> givenLedger(MinecraftServer server, VillageManager village, boolean useValuables) {
        Map<String, Integer> given = new HashMap<>();
        GivenBlocks.get(server).byVillage(village.getVillageId()).forEach((item, n) -> given.put(id(item), n));
        return MaterialPlanner.spare(given, Map.of(), ordersNeed(server, village), List.of(),
                id -> never(item(id)), id -> valuable(item(id)), useValuables);
    }

    /**
     * One player's given blocks for the village, as much as the orders' needs leave once everyone
     * else's piles have gone to them. Runs made from it stay that player's ({@code givenLedger}
     * is everyone's, for the check command). Planned again after every run, so two givers are
     * never both counted as spare for the same need.
     */
    public static Map<String, Integer> givenLedger(MinecraftServer server, VillageManager village, UUID giver,
                                                   boolean useValuables) {
        GivenBlocks given = GivenBlocks.get(server);
        Map<Item, Integer> all = given.byVillage(village.getVillageId());
        Map<String, Integer> mine = new HashMap<>();
        for (GivenBlocks.Pile pile : given.byGiver(village.getVillageId(), giver)) {
            if (pile.count > 0) mine.merge(id(pile.item), pile.count, Integer::sum);
        }
        if (mine.isEmpty()) return Map.of();
        Map<String, Integer> needsLeft = new HashMap<>();
        BuildOrders.ordersStillNeed(server, village.getVillageId(), null).forEach((item, need) -> {
            int others = all.getOrDefault(item, 0) - mine.getOrDefault(id(item), 0);
            if (need - others > 0) needsLeft.put(id(item), need - others);
        });
        return MaterialPlanner.spare(mine, Map.of(), needsLeft, List.of(),
                id -> never(item(id)), id -> valuable(item(id)), useValuables);
    }

    private static Map<String, Integer> ordersNeed(MinecraftServer server, VillageManager village) {
        Map<String, Integer> needs = new HashMap<>();
        BuildOrders.ordersStillNeed(server, village.getVillageId(), null).forEach((item, n) -> needs.put(id(item), n));
        return needs;
    }

    /** The stations in the village's storage rooms (any room counts here). */
    public static MaterialPlanner.Stations stations(VillageManager village) {
        boolean table = false, stonecutter = false, furnace = false;
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            table |= storage.hasCraftingTable();
            stonecutter |= storage.hasStonecutter();
            furnace |= storage.hasFurnace();
        }
        return new MaterialPlanner.Stations(table, stonecutter, furnace);
    }
}
