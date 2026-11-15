package com.alex.test.village.brain;

import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.tokens.VillageItemOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The tavern's cake: once a night, the first villager to sit down to dinner
 * in the tavern cuts a cake from storage, and it feeds seven diners a slice
 * each, as a placed vanilla cake would. One cake, seven happy villagers.
 *
 * <p>Nothing is placed in the world; the table is kept per village in memory.
 * A slice is a one-off cake stack with a slice's food value, marked so an
 * interrupted meal hands it back to the table instead of the villager's pack.
 */
public final class FeastTable {

    public static final int SLICES_PER_CAKE = 7;
    /** A vanilla cake slice: 2 nutrition. */
    private static final int SLICE_NUTRITION = 2;
    private static final String SLICE_TAG = "dynamicvillages_cake_slice";

    private static final class Table {
        long day = -1;
        int slicesLeft;
        boolean cut;
        /** Victory feast: cakes on the house tonight, from the raid's spoils (not from storage). */
        long feastDay = -1;
        int feastCakes;
    }

    private static final Map<VillageManager, Table> TABLES = new WeakHashMap<>();

    private FeastTable() {}

    /**
     * A raid was won: the next tavern dinner has {@code cakes} extra cakes
     * that cost the village nothing, after (or instead of) the usual one.
     * Tonight's when villagers have not headed out for the evening yet, else
     * tomorrow's (a raid won at night comes after tonight's dinner). Returns
     * whether the feast is tonight.
     */
    public static boolean addVictoryCakes(VillageManager village, ServerLevel level, int cakes) {
        Table table = TABLES.computeIfAbsent(village, v -> new Table());
        long day = EveningSchedule.dayIndex(level.getDayTime());
        long clock = Math.floorMod(com.alex.test.village.VillageDays.villageTime(level.getDayTime()),
                EveningSchedule.DAY_TICKS);
        boolean tonight = clock < EveningSchedule.HEAD_OUT_MIN;
        if (!tonight) day++;
        if (table.feastDay != day) {
            table.feastDay = day;
            table.feastCakes = 0;
        }
        table.feastCakes += Math.max(0, cakes);
        return tonight;
    }

    /**
     * A slice for a diner at the tavern tonight, cutting tonight's cake from
     * storage if it has not been cut yet; empty when there is none.
     */
    public static ItemStack takeSlice(VillageManager village, ServerLevel level) {
        Table table = TABLES.computeIfAbsent(village, v -> new Table());
        long day = EveningSchedule.dayIndex(level.getDayTime());
        if (table.day != day) {
            table.day = day;
            table.slicesLeft = 0;
            table.cut = false;
        }
        if (table.slicesLeft <= 0) {
            if (table.feastDay == day && table.feastCakes > 0) {
                table.feastCakes--;
            } else if (table.cut || !cutCake(village, level)) {
                return ItemStack.EMPTY;
            } else {
                table.cut = true;
            }
            table.slicesLeft = SLICES_PER_CAKE;
        }
        table.slicesLeft--;
        return slice(village);
    }

    /** An uneaten slice goes back on the table. */
    public static void returnSlice(VillageManager village) {
        Table table = TABLES.get(village);
        if (table != null) table.slicesLeft++;
    }

    public static boolean isSlice(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(SLICE_TAG);
    }

    private static ItemStack slice(VillageManager village) {
        ItemStack slice = new ItemStack(Items.CAKE);
        slice.set(DataComponents.FOOD, new FoodProperties.Builder()
                .nutrition(SLICE_NUTRITION).saturationModifier(0.1f).build());
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(SLICE_TAG, true);
        slice.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return VillageItemOwnership.produce(slice, village);
    }

    /** Takes one cake the village may eat out of storage. */
    private static boolean cutCake(VillageManager village, ServerLevel level) {
        if (StockReserves.get(level.getServer()).free(village, level, Items.CAKE) <= 0) return false;
        ItemStack wanted = new ItemStack(Items.CAKE);
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (BlockPos pos : storage.findChestsWithItem(level, wanted)) {
                ItemStack taken = storage.withdrawItemFromChest(level, pos, wanted, 1);
                if (!taken.isEmpty()) return true;
            }
        }
        return false;
    }
}
