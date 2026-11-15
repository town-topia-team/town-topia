package com.alex.test.village.takeover;

import com.alex.test.ModItems;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * What makes one generated village different from the next: how well off it
 * is, which second job it has, whether it comes with a rare extra building,
 * what its starter chest holds, and what a player may find in its own vanilla
 * chests. Every roll here is made once per village and kept.
 *
 * <p>Fixed by the author: a farmer and a lumberjack always, a guard by chance;
 * never a miner and never a Mine token; a Ranch (with no rancher) or a Guard
 * Post only rarely.
 */
final class VillageCharacter {

    private VillageCharacter() {}

    // =========================================================================
    // Wealth
    // =========================================================================

    /** How well off a village came out of generation. */
    enum Wealth {
        POOR, ORDINARY, WELL_OFF;

        static Wealth byId(int id) {
            Wealth[] values = values();
            return id >= 0 && id < values.length ? values[id] : ORDINARY;
        }
    }

    /** Chance weights for poor, ordinary and well-off. */
    private static final int[] WEALTH_WEIGHTS = {30, 55, 15};

    static Wealth rollWealth(RandomSource random) {
        return Wealth.values()[weighted(random, WEALTH_WEIGHTS)];
    }

    // =========================================================================
    // Jobs
    // =========================================================================

    /** The jobs every generated village has, in the order they are handed out. */
    static final List<Profession> ALWAYS_JOBS = List.of(Profession.FARMER, Profession.LUMBERJACK);

    /** Chance a village has a guard as well, on top of its farmer and lumberjack. */
    static final float GUARD_CHANCE = 0.25f;

    static boolean rollGuard(RandomSource random) {
        return random.nextFloat() < GUARD_CHANCE;
    }

    /**
     * Chance a village with an animal pen that qualifies gets a Ranch. Only the
     * building: no rancher comes with it, the player picks one.
     */
    static final float RANCH_CHANCE = 0.10f;
    /** Chance a village with a guard gets a Guard Post by its bell. */
    static final float GUARD_POST_CHANCE = 0.10f;

    /** Starting levels added to a villager's job, by wealth: none, up to 2, 2 to 5. */
    static int levelBonus(Wealth wealth, RandomSource random) {
        return switch (wealth) {
            case POOR -> 0;
            case ORDINARY -> random.nextInt(3);
            case WELL_OFF -> 2 + random.nextInt(4);
        };
    }

    /** Chance a well-off village has one veteran: much better at their job than the rest. */
    static final float VETERAN_CHANCE = 0.5f;

    static int veteranBonus(RandomSource random) {
        return 8 + random.nextInt(5);
    }

    /** Chance an adult arrives carrying a little food of their own, by wealth. */
    static float personalFoodChance(Wealth wealth) {
        return switch (wealth) {
            case POOR -> 0.25f;
            case ORDINARY -> 0.5f;
            case WELL_OFF -> 0.75f;
        };
    }

    // =========================================================================
    // The land
    // =========================================================================

    /** Which kind of village, from its structure: decides its wood and foods. */
    enum Kind {
        PLAINS(Items.OAK_LOG, null, Items.CARROT, Items.POTATO, Items.APPLE),
        DESERT(Items.OAK_LOG, null, Items.BAKED_POTATO, Items.COOKED_RABBIT, Items.CARROT),
        SAVANNA(Items.ACACIA_LOG, Items.MELON_SEEDS, Items.MELON_SLICE, Items.CARROT, Items.COOKED_MUTTON),
        TAIGA(Items.SPRUCE_LOG, Items.PUMPKIN_SEEDS, Items.SWEET_BERRIES, Items.PUMPKIN_PIE, Items.BAKED_POTATO),
        SNOWY(Items.SPRUCE_LOG, Items.BEETROOT_SEEDS, Items.BAKED_POTATO, Items.COOKED_SALMON, Items.BEETROOT);

        final Item log;
        final Item extraSeeds;
        final Item[] foods;

        Kind(Item log, Item extraSeeds, Item... foods) {
            this.log = log;
            this.extraSeeds = extraSeeds;
            this.foods = foods;
        }
    }

    static Kind kindOf(ServerLevel level, StructureStart start) {
        ResourceLocation id = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getKey(start.getStructure());
        String path = id != null ? id.getPath() : "";
        if (path.contains("desert")) return Kind.DESERT;
        if (path.contains("savanna")) return Kind.SAVANNA;
        if (path.contains("snowy")) return Kind.SNOWY;
        if (path.contains("taiga")) return Kind.TAIGA;
        return Kind.PLAINS;
    }

    /** A handful of the village's own food, for a villager's pocket. */
    static ItemStack pocketFood(Kind kind, RandomSource random) {
        Item food = random.nextBoolean() ? Items.BREAD : kind.foods[random.nextInt(kind.foods.length)];
        return new ItemStack(food, 2 + random.nextInt(4));
    }

    // =========================================================================
    // The starter chest
    // =========================================================================

    /** Chance the starter chest holds a token the village was saving for. */
    static final float SAVED_TOKEN_CHANCE = 0.10f;

    /** Tokens a chest may hold. Never the Mine, nor the miner's token. */
    private static final List<Supplier<? extends ItemLike>> FINDABLE_TOKENS = List.of(
            ModItems.TWO_BED_HOUSE, ModItems.KITCHEN, ModItems.RANCH, ModItems.GUARD_POST,
            ModItems.FARMER_TOKEN, ModItems.LUMBERJACK_TOKEN, ModItems.GUARD_TOKEN,
            ModItems.RANCHER_TOKEN, ModItems.COOK_TOKEN);

    static ItemStack findableToken(RandomSource random) {
        return new ItemStack(FINDABLE_TOKENS.get(random.nextInt(FINDABLE_TOKENS.size())).get());
    }

    /**
     * The starter chest: food for the first days, seeds, wood, and for the
     * better off coal, iron, stone tools and a few emeralds. Poor villages get
     * a thin chest, well-off ones a full one; one in ten has a token too.
     * Stacks larger than an item allows are split by the caller's chest.
     */
    static List<ItemStack> starterChest(Kind kind, Wealth wealth, RandomSource random) {
        List<ItemStack> goods = new ArrayList<>();
        int foodKinds;
        switch (wealth) {
            case POOR -> {
                add(goods, Items.BREAD, between(random, 8, 12));
                foodKinds = 1;
                add(goods, Items.WHEAT_SEEDS, between(random, 4, 8));
                add(goods, kind.log, between(random, 4, 8));
            }
            case ORDINARY -> {
                add(goods, Items.BREAD, between(random, 16, 24));
                foodKinds = 2;
                add(goods, Items.WHEAT_SEEDS, between(random, 8, 16));
                if (kind.extraSeeds != null) add(goods, kind.extraSeeds, between(random, 4, 8));
                add(goods, kind.log, between(random, 12, 20));
                add(goods, Items.COAL, between(random, 2, 6));
                if (random.nextBoolean()) {
                    goods.add(worn(random.nextBoolean() ? Items.STONE_HOE : Items.STONE_AXE, random, 0.3f, 0.6f));
                }
                add(goods, Items.EMERALD, random.nextInt(3));
            }
            default -> {
                add(goods, Items.BREAD, between(random, 28, 36));
                foodKinds = 3;
                add(goods, Items.WHEAT_SEEDS, 16);
                if (kind.extraSeeds != null) add(goods, kind.extraSeeds, 8);
                add(goods, kind.log, between(random, 24, 40));
                add(goods, Items.COAL, between(random, 6, 12));
                add(goods, Items.IRON_INGOT, between(random, 2, 5));
                goods.add(worn(Items.STONE_HOE, random, 0.1f, 0.3f));
                goods.add(worn(Items.STONE_AXE, random, 0.1f, 0.3f));
                add(goods, Items.EMERALD, between(random, 3, 8));
            }
        }
        List<Item> foods = new ArrayList<>(List.of(kind.foods));
        for (int i = 0; i < foodKinds && !foods.isEmpty(); i++) {
            Item food = foods.remove(random.nextInt(foods.size()));
            add(goods, food, wealth == Wealth.POOR ? between(random, 3, 6)
                    : wealth == Wealth.ORDINARY ? between(random, 6, 10) : between(random, 8, 14));
        }
        if (random.nextFloat() < SAVED_TOKEN_CHANCE) goods.add(findableToken(random));
        return goods;
    }

    // =========================================================================
    // The village's own vanilla chests
    // =========================================================================

    /** Chance each of the village's vanilla loot chests holds one of our tokens too. */
    static final float VANILLA_CHEST_FIND_CHANCE = 0.25f;

    // =========================================================================

    private static ItemStack worn(Item tool, RandomSource random, float minWear, float maxWear) {
        ItemStack stack = new ItemStack(tool);
        int max = stack.getMaxDamage();
        if (max > 0) stack.setDamageValue((int) (max * (minWear + random.nextFloat() * (maxWear - minWear))));
        return stack;
    }

    private static void add(List<ItemStack> goods, Item item, int count) {
        if (count <= 0) return;
        int max = new ItemStack(item).getMaxStackSize();
        while (count > 0) {
            int part = Math.min(count, max);
            goods.add(new ItemStack(item, part));
            count -= part;
        }
    }

    private static int between(RandomSource random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private static int weighted(RandomSource random, int[] weights) {
        int total = 0;
        for (int weight : weights) total += weight;
        int roll = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) return i;
        }
        return 0;
    }
}
