package com.alex.test.village.villagers.cook.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Cook's settings schema, following {@code ArcherSettingsSchema}'s shape
 * (the reference implementation for the generic settings framework -- see
 * {@code HOW_TO_ADD_SETTINGS.md}).
 *
 * <p>{@code CookPlanner} cooks only dishes below their stock target, picking
 * among those by {@link #PRODUCTION_MODE}. {@link #ALLOWED_RECIPES} lets a
 * player veto specific dishes (e.g. to stop burning through a scarce
 * ingredient like gold ingots on golden carrots) -- a disallowed recipe is
 * simply skipped over, same as if it were permanently unobtainable. Gold,
 * stock, charcoal and fuel controls apply to both live cooking and offline
 * production.
 */
public final class CookSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String PRODUCTION_MODE = "production_mode";
    public static final String ALLOWED_RECIPES = "allowed_recipes";
    public static final String GOLD_RESERVE = "gold_reserve";
    public static final String MAKE_CHARCOAL = "make_charcoal";
    public static final String FUEL_COMFORT = "fuel_comfort";
    public static final String LOGS_TO_SPARE = "logs_to_spare";
    public static final String BURN_WOOD = "burn_wood";

    /** Prefix of each dish's stock target, followed by the result item's path. */
    public static final String STOCK_PREFIX = "stock_";

    /** {@link #PRODUCTION_MODE} choices, in {@code CookPlanner.Mode} order. */
    public static final List<String> MODES = List.of("Best food", "Balanced", "Stretch", "Varied menu");

    /**
     * One row per dish, in {@code CookActivity.RECIPES} priority order:
     * registry id, label, default stock target. Rich dishes keep fewer on
     * hand, staples the most.
     */
    private record Dish(String id, String label, int stock, String needs) {}

    private static final String HEAT = " Cooked on a smoker (fastest), furnace or campfire.";
    private static final String TABLE = " Made at a crafting table.";
    private static final String BOWL = " The bowl is used up; the cook crafts bowls from planks"
            + " (or cuts a log into planks) if storage has none.";
    private static final String SUGAR = " The cook makes the sugar from sugar cane, and leaves"
            + " some cane for the enchanter's paper.";

    private static final List<Dish> DISHES = List.of(
            new Dish("minecraft:cake", "Cake", 8,
                    "Needs 3 wheat, 2 sugar, 1 egg and 3 milk buckets (the buckets are used up)."
                            + SUGAR + TABLE),
            new Dish("minecraft:rabbit_stew", "Rabbit Stew", 16,
                    "Needs raw rabbit, a carrot, a potato, a brown mushroom and a bowl." + BOWL + HEAT),
            new Dish("minecraft:pumpkin_pie", "Pumpkin Pie", 16,
                    "Needs 1 pumpkin, 1 sugar and 1 egg." + SUGAR + TABLE),
            new Dish("minecraft:golden_carrot", "Golden Carrot", 16,
                    "Needs 1 carrot and 1 gold ingot. Never dips into the gold set aside under"
                            + " \"Gold to leave for me\"." + TABLE),
            new Dish("minecraft:golden_apple", "Golden Apple", 8,
                    "Needs 1 apple and 8 gold ingots. Never dips into the gold set aside under"
                            + " \"Gold to leave for me\"." + TABLE),
            new Dish("minecraft:cooked_beef", "Cooked Beef", 32,
                    "Needs 1 raw beef." + HEAT),
            new Dish("minecraft:cooked_porkchop", "Cooked Porkchop", 32,
                    "Needs 1 raw porkchop." + HEAT),
            new Dish("minecraft:cooked_chicken", "Cooked Chicken", 32,
                    "Needs 1 raw chicken." + HEAT),
            new Dish("minecraft:cooked_mutton", "Cooked Mutton", 32,
                    "Needs 1 raw mutton." + HEAT),
            new Dish("minecraft:cooked_rabbit", "Cooked Rabbit", 32,
                    "Needs 1 raw rabbit." + HEAT),
            new Dish("minecraft:mushroom_stew", "Mushroom Stew", 16,
                    "Needs 1 red mushroom, 1 brown mushroom and a bowl." + BOWL + TABLE),
            new Dish("minecraft:beetroot_soup", "Beetroot Soup", 16,
                    "Needs 6 beetroot and a bowl." + BOWL + TABLE),
            new Dish("minecraft:cooked_salmon", "Cooked Salmon", 32,
                    "Needs 1 raw salmon." + HEAT),
            new Dish("minecraft:cooked_cod", "Cooked Cod", 32,
                    "Needs 1 raw cod." + HEAT),
            new Dish("minecraft:bread", "Bread", 64,
                    "Needs 3 wheat." + TABLE),
            new Dish("minecraft:baked_potato", "Baked Potato", 64,
                    "Needs 1 potato." + HEAT),
            new Dish("minecraft:cookie", "Cookie", 64,
                    "Needs 2 wheat and 1 cocoa bean, and makes 8 cookies at once." + TABLE),
            new Dish("minecraft:dried_kelp", "Dried Kelp", 32,
                    "Needs 1 kelp. Low in food, so it ranks last." + HEAT));

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.COOK,
            "CookSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: cooks meals for the village.\n"
                                            + "OFF: stops cooking. Food already\nin storage stays."),
                            SettingsSchema.Row.choice(
                                    PRODUCTION_MODE, "Cook for", 0, MODES)
                                    .describedAs("Which dish it makes next.\n"
                                            + "Best food: the tastiest first.\n"
                                            + "Balanced: the emptiest shelf.\n"
                                            + "Stretch: the most food per\ningredient.\n"
                                            + "Varied menu: dishes eaten least\nlately."),
                            SettingsSchema.Row.behaviorToggle(
                                    BURN_WOOD, "Burning Wood", true)
                                    .describedAs("ON: also burns logs and planks.\n"
                                            + "OFF: only coal and charcoal.\n"
                                            + "Coal cooks 8 items, a log 1.5."),
                            SettingsSchema.Row.header("Additional Behaviour"),
                            SettingsSchema.Row.behaviorToggle(
                                    MAKE_CHARCOAL, "Charcoal Making", true)
                                    .describedAs("ON: when coal runs low, turns spare\nlogs into charcoal.\n"
                                            + "Needs a furnace in the kitchen."))),
                    new SettingsSchema.Section("Recipes", List.of(
                            SettingsSchema.Row.allowList(ALLOWED_RECIPES, "Recipes", recipes())
                                    .describedAs("ON: the cook makes this dish.\nOFF: never makes it."))),
                    new SettingsSchema.Section("Stock", stockRows())));

    private CookSettingsSchema() {}

    /** The stock setting id for a dish, from its registry id. */
    public static String stockId(String registryId) {
        return STOCK_PREFIX + registryId.substring(registryId.indexOf(':') + 1);
    }

    /** Identified by the result item's registry id (what {@code CookActivity} filters by). */
    private static List<SettingsSchema.AllowListEntry> recipes() {
        List<SettingsSchema.AllowListEntry> entries = new ArrayList<>();
        for (Dish dish : DISHES) {
            entries.add(new SettingsSchema.AllowListEntry(dish.id(), dish.label(), true,
                    dish.needs().trim() + "\nOFF: never makes it."));
        }
        return entries;
    }

    /**
     * The Stock tab: what the cook leaves in storage (gold, fuel, logs), then how many
     * of each dish to keep. Storage holds this many and the cook stops making the dish;
     * 0 means never make it. Rows are found by id, never by position.
     */
    private static List<SettingsSchema.Row> stockRows() {
        List<SettingsSchema.Row> rows = new ArrayList<>();
        rows.add(SettingsSchema.Row.header("Supplies"));
        rows.add(SettingsSchema.Row.numericStepper(
                GOLD_RESERVE, "Gold to leave for me", 8, 0, 64, 1)
                .describedAs("Gold it leaves in storage for you.\n"
                        + "Golden carrots and apples only\nuse gold above this."));
        rows.add(SettingsSchema.Row.numericStepper(
                FUEL_COMFORT, "Fuel to keep", 4, 0, 64, 1)
                .describedAs("Coal and charcoal it tries to keep\nin storage. Below this it makes\n"
                        + "charcoal. 0 never makes charcoal."));
        rows.add(SettingsSchema.Row.numericStepper(
                LOGS_TO_SPARE, "Logs to keep", 16, 0, 64, 1)
                .describedAs("Logs that stay in storage. Only\nlogs above this become charcoal."));
        rows.add(SettingsSchema.Row.header("Dishes"));
        for (Dish dish : DISHES) {
            rows.add(SettingsSchema.Row.numericStepper(
                    stockId(dish.id()), dish.label(), dish.stock(), 0, 256, 8)
                    .describedAs("How many of this dish to keep.\nSet for 10 villagers, and it\n"
                            + "grows with the village. 0 never\nmakes it."));
        }
        return rows;
    }
}
