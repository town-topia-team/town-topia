package com.alex.test.village.stats;

import com.alex.test.Tr;
import com.alex.test.village.tokens.VillageTokenBinding;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How much a villager enjoys each food: its taste per serving, its quality
 * tier, and the chance it makes the villager feel sick.
 *
 * <p>Hunger is not in here. It stays the stack's own nutrition times
 * {@link HungerSystem#NUTRITION_TO_HUNGER}, so a cook's bread (stamped with
 * the cook's richer food values) fills more than a merchant's.
 *
 * <p>Food the village made itself (bound to the village) tastes in full;
 * bought or player food gives half of any good taste, bad taste in full. A
 * meal is held to {@value #MEAL_JOY_MIN} to {@value #MEAL_JOY_MAX}, and good
 * food adds at most {@value WellbeingSystem#FOOD_JOY_DAILY_CAP} a day, so
 * eating well never replaces sleep or company. {@link DietMemory} adds the
 * variety bonus or boredom on top.
 */
public final class FoodCatalog {

    public static final float MEAL_JOY_MIN = -10f;
    public static final float MEAL_JOY_MAX = 12f;
    /** A meal without spoiled food never costs more than this, however many servings. */
    public static final float POOR_MEAL_MIN = -3f;
    /** Sitting down to eat, once per meal. */
    public static final float SEATED_MEAL_JOY = 1f;
    /** Dinner at home, or at the tavern with others eating, once per meal. */
    public static final float PLACE_MEAL_JOY = 1f;
    /** Starting a meal already starving, once per meal. */
    public static final float STARVING_MEAL_JOY = -1f;
    /** On top of the food's own taste, when it turns the villager's stomach. */
    public static final float SICK_JOY = -4f;
    /** A player's food (not the village's own): this share of its good taste. Bad taste stays whole. */
    public static final float PLAYER_FOOD_TASTE_SHARE = 0.5f;
    /** A second serving of the same food in one meal. */
    public static final float REPEAT_SERVING_FACTOR = 0.5f;
    /** Boredom never drags a meal below this, unless the food is spoiled. */
    public static final float TASTE_FLOOR = -2f;

    public enum Tier {
        SPOILED("Spoiled", -4f),
        RAW_MEAT("Raw meat", -2f),
        RAW("Raw", -0.5f),
        FRESH("Fresh", 1f),
        PLAIN("Plain", 1f),
        TREAT("Treat", 2f),
        HEARTY("Hearty", 3f),
        FINE("Fine", 6f),
        LUXURY("Luxury", 8f);

        public final String label;

        /** {@link #label} in the player's language. */
        public String shown() {
            return switch (this) {
                case SPOILED -> Tr.s("food.dynamicvillages.tier.spoiled", "Spoiled");
                case RAW_MEAT -> Tr.s("food.dynamicvillages.tier.raw_meat", "Raw meat");
                case RAW -> Tr.s("food.dynamicvillages.tier.raw", "Raw");
                case FRESH -> Tr.s("food.dynamicvillages.tier.fresh", "Fresh");
                case PLAIN -> Tr.s("food.dynamicvillages.tier.plain", "Plain");
                case TREAT -> Tr.s("food.dynamicvillages.tier.treat", "Treat");
                case HEARTY -> Tr.s("food.dynamicvillages.tier.hearty", "Hearty");
                case FINE -> Tr.s("food.dynamicvillages.tier.fine", "Fine");
                case LUXURY -> Tr.s("food.dynamicvillages.tier.luxury", "Luxury");
            };
        }
        /** Taste of a food of this tier that has no value of its own (modded food). */
        public final float defaultTaste;

        Tier(String label, float defaultTaste) {
            this.label = label;
            this.defaultTaste = defaultTaste;
        }

        public boolean atLeast(Tier other) {
            return ordinal() >= other.ordinal();
        }
    }

    private record Entry(Tier tier, float taste, float sickChance) {}

    private static final Map<Item, Entry> ENTRIES = new HashMap<>();

    private static void put(Tier tier, float taste, Item... items) {
        for (Item item : items) ENTRIES.put(item, new Entry(tier, taste, 0f));
    }

    private static void sick(Tier tier, float taste, float sickChance, Item item) {
        ENTRIES.put(item, new Entry(tier, taste, sickChance));
    }

    static {
        sick(Tier.SPOILED, -4f, 0.8f, Items.ROTTEN_FLESH);
        sick(Tier.SPOILED, -4f, 0.6f, Items.POISONOUS_POTATO);
        sick(Tier.SPOILED, -4f, 1.0f, Items.SPIDER_EYE);
        sick(Tier.SPOILED, -4f, 1.0f, Items.PUFFERFISH);
        sick(Tier.RAW_MEAT, -2f, 0.3f, Items.CHICKEN);
        put(Tier.RAW_MEAT, -2f, Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.RABBIT,
                Items.COD, Items.SALMON, Items.TROPICAL_FISH);
        put(Tier.RAW, -0.5f, Items.CARROT);
        put(Tier.RAW, -1f, Items.POTATO, Items.BEETROOT);
        put(Tier.FRESH, 1f, Items.APPLE, Items.MELON_SLICE);
        put(Tier.FRESH, 0.5f, Items.SWEET_BERRIES, Items.GLOW_BERRIES);
        put(Tier.PLAIN, 1.5f, Items.BREAD, Items.COOKED_COD);
        put(Tier.PLAIN, 0.5f, Items.BAKED_POTATO);
        put(Tier.PLAIN, 0f, Items.DRIED_KELP);
        put(Tier.HEARTY, 2f, Items.COOKED_SALMON);
        put(Tier.TREAT, 2f, Items.COOKIE, Items.HONEY_BOTTLE);
        put(Tier.HEARTY, 4f, Items.COOKED_BEEF, Items.COOKED_PORKCHOP);
        put(Tier.HEARTY, 3f, Items.COOKED_CHICKEN, Items.COOKED_RABBIT,
                Items.MUSHROOM_STEW, Items.BEETROOT_SOUP, Items.SUSPICIOUS_STEW);
        put(Tier.HEARTY, 2f, Items.COOKED_MUTTON);
        put(Tier.FINE, 6f, Items.PUMPKIN_PIE, Items.RABBIT_STEW);
        put(Tier.LUXURY, 7f, Items.GOLDEN_CARROT);
        put(Tier.LUXURY, 8f, Items.CAKE, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE);
    }

    /**
     * Foods a villager may pick as its favourite or the one it dislikes: dishes
     * a village can make or grow, not raw meat or spoiled food.
     */
    public static final List<Item> PREFERENCE_CANDIDATES = List.of(
            Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN,
            Items.COOKED_MUTTON, Items.COOKED_RABBIT, Items.COOKED_SALMON, Items.COOKED_COD,
            Items.MUSHROOM_STEW, Items.BEETROOT_SOUP, Items.RABBIT_STEW, Items.BREAD,
            Items.BAKED_POTATO, Items.COOKIE, Items.PUMPKIN_PIE, Items.APPLE, Items.MELON_SLICE,
            Items.CARROT);

    private FoodCatalog() {}

    /** The tier of a food; modded foods are guessed from their food data. */
    public static Tier tier(ItemStack stack, @Nullable FoodProperties food) {
        Entry entry = ENTRIES.get(stack.getItem());
        if (entry != null) return entry.tier();
        return guessTier(food);
    }

    /**
     * Unknown foods: mostly-harmful effects are spoiled, a bowl or a big
     * serving is hearty, a middling one plain, the rest raw.
     */
    private static Tier guessTier(@Nullable FoodProperties food) {
        if (food == null) return Tier.RAW;
        if (harmfulChance(food) >= 0.5f) return Tier.SPOILED;
        if (food.usingConvertsTo().isPresent() || food.nutrition() >= 7) return Tier.HEARTY;
        if (food.nutrition() >= 4) return Tier.PLAIN;
        return Tier.RAW;
    }

    private static float harmfulChance(FoodProperties food) {
        float chance = 0f;
        for (FoodProperties.PossibleEffect effect : food.effects()) {
            if (effect.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                chance = Math.max(chance, effect.probability());
            }
        }
        return chance;
    }

    /** Chance this food makes the villager feel sick. */
    public static float sickChance(ItemStack stack, @Nullable FoodProperties food) {
        Entry entry = ENTRIES.get(stack.getItem());
        if (entry != null) return entry.sickChance();
        return food == null ? 0f : harmfulChance(food);
    }

    /** Taste of home-made food of this kind, ignoring where this stack came from. */
    public static float baseTaste(Item item) {
        Entry entry = ENTRIES.get(item);
        return entry != null ? entry.taste() : Tier.PLAIN.defaultTaste;
    }

    public static float baseTaste(ItemStack stack, @Nullable FoodProperties food) {
        Entry entry = ENTRIES.get(stack.getItem());
        return entry != null ? entry.taste() : guessTier(food).defaultTaste;
    }

    /** Made by the village (bound to it), rather than bought or brought by a player. */
    public static boolean isHomeMade(ItemStack stack) {
        return VillageTokenBinding.isBound(stack);
    }

    /**
     * Share of its hunger a serving fills: the village's own in full, a
     * player's less the better the food is. Plain crops lose a quarter, a
     * player's steak half, the finest food more than half.
     */
    public static float fillShare(ItemStack stack, @Nullable FoodProperties food) {
        if (isHomeMade(stack)) return 1f;
        return switch (tier(stack, food)) {
            case SPOILED, RAW_MEAT, RAW, FRESH -> 0.75f;
            case PLAIN -> 0.65f;
            case TREAT, HEARTY -> 0.5f;
            case FINE -> 0.4f;
            case LUXURY -> 0.3f;
        };
    }

    /**
     * Taste of one serving of this stack: home-made in full, a player's food
     * at half its good taste (bad taste stays whole), and a dish another
     * villager made instead of the cook a point worse (VillageFood).
     */
    public static float taste(ItemStack stack, @Nullable FoodProperties food) {
        float taste = baseTaste(stack, food);
        if (com.alex.test.village.tokens.VillageFood.isVillagerMade(stack)) {
            taste += com.alex.test.village.tokens.VillageFood.VILLAGER_MADE_TASTE;
        }
        return isHomeMade(stack) || taste <= 0f ? taste : taste * PLAYER_FOOD_TASTE_SHARE;
    }

    /**
     * How much a villager wants to eat this next: its taste, plus the variety
     * bonus or boredom and its personal likes. Spoiled food always comes last.
     * A player's food only ever loses here (see {@link #varietyJoy}).
     */
    public static float preference(ItemStack stack, @Nullable FoodProperties food, DietMemory diet) {
        if (tier(stack, food) == Tier.SPOILED) return -100f + taste(stack, food);
        return taste(stack, food) + varietyJoy(stack, diet) + likingJoy(stack, diet);
    }

    /**
     * Novelty or boredom for this stack as the next main dish. A player's
     * food never earns the new-dish bonus and is never the favourite, so it
     * gets dull like any other food; boredom costs the same as the village's own.
     */
    public static float varietyJoy(ItemStack stack, DietMemory diet) {
        return isHomeMade(stack) ? diet.varietyJoy(stack.getItem())
                : Math.min(0f, diet.noveltyJoy(stack.getItem()));
    }

    /** Favourite or disliked: a player's food keeps the dislike, never the favourite bonus. */
    public static float likingJoy(ItemStack stack, DietMemory diet) {
        float joy = diet.likingJoy(stack.getItem());
        return isHomeMade(stack) ? joy : Math.min(0f, joy);
    }

    /** Pie, cake, rabbit stew and golden food: rationed so one villager cannot take it all. */
    public static boolean isRationed(Item item) {
        Entry entry = ENTRIES.get(item);
        return entry != null && entry.tier().atLeast(Tier.FINE);
    }
}
