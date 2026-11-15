package com.alex.test.village.villagers.cook;

import com.alex.test.village.villagers.cook.behavior.CookActivity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * What the village really eats of each dish, learned by the cook watching its
 * shelves: every drop in a dish's storage count between two looks is food
 * taken (a villager's meal or lunch box, the merchant's cart, the player).
 * Rises are the cook's own deposits and are ignored.
 *
 * <p>Each day's takings fold into a running daily figure that leans on the
 * last few days, kept in the cook's brain data so it survives a reload. Until
 * one whole day has been folded in there is no figure, and the Stock tab
 * targets apply as they are ({@link CookPlanner.Pantry#target}).
 */
public final class CookDemand {

    private static final String TAG_SEEN = "CookDemandSeen";
    private static final String TAG_TODAY = "CookDemandToday";
    private static final String TAG_DAILY = "CookDemandDaily";
    private static final String TAG_DAY = "CookDemandDay";
    private static final String TAG_LAST_LOOK = "CookDemandLook";
    private static final String TAG_DAYS = "CookDemandDays";

    /** Ticks between two looks at the shelves. */
    private static final int LOOK_TICKS = 200;
    /** Weight of the newest day in the running figure: about the last three days. */
    private static final double NEW_DAY_WEIGHT = 1.0 / 3.0;
    /**
     * The most of one dish counted as eaten in a single look. A player
     * emptying a chest of bread into their pockets is not the village's
     * appetite.
     */
    private static final int MAX_TAKEN_PER_LOOK = 16;

    private CookDemand() {}

    /**
     * Looks at the shelves, at most every {@value #LOOK_TICKS} ticks: what
     * went missing since the last look counts as eaten today, and a new day
     * folds yesterday into the running figure.
     */
    public static void observe(CompoundTag data, long gameTime, long day, Map<Item, Integer> storage) {
        if (data.contains(TAG_LAST_LOOK) && gameTime - data.getLong(TAG_LAST_LOOK) < LOOK_TICKS
                && gameTime >= data.getLong(TAG_LAST_LOOK)) return;
        data.putLong(TAG_LAST_LOOK, gameTime);

        CompoundTag today = data.getCompound(TAG_TODAY);
        if (data.contains(TAG_DAY) && data.getLong(TAG_DAY) != day) {
            foldDay(data, today, Math.max(1, day - data.getLong(TAG_DAY)));
            today = new CompoundTag();
        }
        data.putLong(TAG_DAY, day);

        CompoundTag seen = data.getCompound(TAG_SEEN);
        CompoundTag now = new CompoundTag();
        for (CookActivity.Recipe recipe : CookActivity.recipes()) {
            Item dish = recipe.result();
            String key = key(dish);
            int count = storage.getOrDefault(dish, 0);
            now.putInt(key, count);
            if (!seen.contains(key)) continue;
            int taken = Math.min(MAX_TAKEN_PER_LOOK, seen.getInt(key) - count);
            if (taken > 0) today.putInt(key, today.getInt(key) + taken);
        }
        data.put(TAG_SEEN, now);
        data.put(TAG_TODAY, today);
    }

    /**
     * One away day of the new calculator ({@code economy/away}): the dishes the
     * village ate that day count as taken, as if the cook had watched, so a trip
     * leaves no "nothing eaten" days behind. The last look is forgotten: storage
     * changed while away, and the first look back would count that as eaten.
     */
    public static void recordAwayDay(CompoundTag data, long day, Map<Item, Integer> eaten) {
        CompoundTag today = data.getCompound(TAG_TODAY);
        if (data.contains(TAG_DAY) && data.getLong(TAG_DAY) != day) {
            foldDay(data, today, Math.max(1, day - data.getLong(TAG_DAY)));
            today = new CompoundTag();
        }
        data.putLong(TAG_DAY, day);
        for (CookActivity.Recipe recipe : CookActivity.recipes()) {
            int count = eaten.getOrDefault(recipe.result(), 0);
            if (count <= 0) continue;
            String key = key(recipe.result());
            today.putInt(key, today.getInt(key) + count);
        }
        data.put(TAG_TODAY, today);
        data.remove(TAG_SEEN);
    }

    /**
     * Away days the village did not live through (past the 3-day cap, or with
     * nobody on the server) are not days with nothing eaten: the last-look
     * day moves forward by them, never past {@code today}.
     */
    public static void skipUncountedDays(CompoundTag data, long days, long today) {
        if (days <= 0L || !data.contains(TAG_DAY)) return;
        data.putLong(TAG_DAY, Math.max(data.getLong(TAG_DAY), Math.min(today, data.getLong(TAG_DAY) + days)));
    }

    /**
     * Folds a finished day into the running figure. Days the cook missed
     * (asleep elsewhere, unloaded) count as days with nothing seen, so a
     * long absence does not leave an old appetite standing.
     */
    private static void foldDay(CompoundTag data, CompoundTag today, long days) {
        CompoundTag daily = data.getCompound(TAG_DAILY);
        for (CookActivity.Recipe recipe : CookActivity.recipes()) {
            String key = key(recipe.result());
            double value = daily.getDouble(key);
            value = value * (1.0 - NEW_DAY_WEIGHT) + today.getInt(key) * NEW_DAY_WEIGHT;
            for (long missed = 1; missed < Math.min(days, 7); missed++) value *= 1.0 - NEW_DAY_WEIGHT;
            daily.putDouble(key, value);
        }
        data.put(TAG_DAILY, daily);
        data.putInt(TAG_DAYS, data.getInt(TAG_DAYS) + 1);
    }

    /** Dishes eaten a day, or null until a whole day has been seen. */
    @Nullable
    public static Map<Item, Double> perDay(CompoundTag data) {
        if (data.getInt(TAG_DAYS) <= 0) return null;
        CompoundTag daily = data.getCompound(TAG_DAILY);
        Map<Item, Double> perDay = new HashMap<>();
        for (CookActivity.Recipe recipe : CookActivity.recipes()) {
            double value = daily.getDouble(key(recipe.result()));
            if (value > 0) perDay.put(recipe.result(), value);
        }
        return perDay;
    }

    private static String key(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id.toString();
    }
}
