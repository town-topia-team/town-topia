package com.alex.test.village.villagers.tradesman.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.List;

/**
 * Tradesman's settings schema. See {@code HOW_TO_ADD_SETTINGS.md} in
 * {@code village.villagers.settings} for the framework contract this
 * follows, and {@code ArcherSettingsSchema} for the reference shape.
 *
 * <p>Every row here corresponds to a real branch in {@code TradesmanActivity}:
 * a master work-enabled gate, a toggle for whether food surplus is eligible
 * for sale at all, and the two numeric knobs ({@code KEEP}/{@code FOOD_KEEP_PER_VILLAGER})
 * that used to be hardcoded constants there. The Sale items tab can override
 * either one per item ({@link TradesmanSalePolicy#keepAmount}).
 */
public final class TradesmanSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String SELL_FOOD_SURPLUS = "sell_food_surplus";
    public static final String RESERVE_KEEP = "reserve_keep";
    /**
     * Food kept back per villager, per kind of food. A new id on purpose: the
     * old "food_sell_floor" was a flat 64, which read as per villager would
     * keep 640 in a village of ten.
     */
    public static final String FOOD_KEEP_PER_VILLAGER = "food_keep_per_villager";
    /** Each villager carries up to 16 food, so less than this per villager is never kept. */
    public static final int MIN_FOOD_PER_VILLAGER = 16;
    public static final int MAX_FOOD_PER_VILLAGER = 64;
    public static final int MAX_ITEM_KEEP = 2048;
    /**
     * Each sale lowers a good's price (MarketMemory). Below this share of its
     * normal price the tradesman stops selling that good and keeps the rest
     * for a later visit. Sell box goods ignore it: the player put them out.
     */
    public static final String PRICE_FLOOR = "price_floor_percent";
    public static final int DEFAULT_PRICE_FLOOR = 50;

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.TRADESMAN,
            "TradesmanSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true),
                            SettingsSchema.Row.behaviorToggle(
                                    SELL_FOOD_SURPLUS, "Selling Spare Food", true))),
                    new SettingsSchema.Section("Trading", List.of(
                            SettingsSchema.Row.numericStepper(
                                    RESERVE_KEEP, "Keep of each item", 32, 0, 128, 8),
                            SettingsSchema.Row.numericStepper(
                                    FOOD_KEEP_PER_VILLAGER, "Food to keep per villager",
                                    MIN_FOOD_PER_VILLAGER, MIN_FOOD_PER_VILLAGER,
                                    MAX_FOOD_PER_VILLAGER, 4),
                            SettingsSchema.Row.numericStepper(
                                    PRICE_FLOOR, "Stop selling below % of price",
                                    DEFAULT_PRICE_FLOOR, 25, 100, 5)))));

    private TradesmanSettingsSchema() {}
}
