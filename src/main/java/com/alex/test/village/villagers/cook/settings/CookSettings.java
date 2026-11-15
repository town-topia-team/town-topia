package com.alex.test.village.villagers.cook.settings;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.cook.CookPlanner;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/** Typed access to the cook's saved work and production choices. */
public final class CookSettings {
    private final ProfessionSettings values;

    private CookSettings(NpcEntity npc) {
        values = ProfessionSettings.of(npc, CookSettingsSchema.SCHEMA);
    }

    public static CookSettings of(NpcEntity npc) {
        return new CookSettings(npc);
    }

    public boolean works() {
        return values.getBool(CookSettingsSchema.WORK_ENABLED);
    }

    public CookPlanner.Mode mode() {
        CookPlanner.Mode[] modes = CookPlanner.Mode.values();
        return modes[Math.clamp(values.getInt(CookSettingsSchema.PRODUCTION_MODE),
                0, modes.length - 1)];
    }

    public boolean allowsRecipe(Item result) {
        return values.isAllowed(CookSettingsSchema.ALLOWED_RECIPES,
                BuiltInRegistries.ITEM.getKey(result).toString());
    }

    /** How many of {@code dish} storage should hold; the cook stops there. */
    public int stockTarget(Item dish) {
        return values.getInt(CookSettingsSchema.stockId(
                BuiltInRegistries.ITEM.getKey(dish).toString()));
    }

    public int goldReserve() {
        return values.getInt(CookSettingsSchema.GOLD_RESERVE);
    }

    public boolean makesCharcoal() {
        return values.getBool(CookSettingsSchema.MAKE_CHARCOAL);
    }

    public boolean burnsWood() {
        return values.getBool(CookSettingsSchema.BURN_WOOD);
    }

    public int fuelComfort() {
        return values.getInt(CookSettingsSchema.FUEL_COMFORT);
    }

    public int logsToSpare() {
        return values.getInt(CookSettingsSchema.LOGS_TO_SPARE);
    }
}
