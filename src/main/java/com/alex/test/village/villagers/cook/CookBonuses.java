package com.alex.test.village.villagers.cook;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What the village's best cook adds to every dish the cook made, when it is
 * eaten (CookBalance milestones): Seasoned taste, Hearty Portions and the
 * Signature Dish. Applied at the table rather than stamped on the dish, so
 * the dishes of a level 24 and a level 25 cook stay the same item and stack.
 */
public final class CookBonuses {

    /** A cook's lifetime batches by dish, in its brain data, for the Signature Dish. */
    private static final String TAG_TOTALS = "CookDishTotals";
    private static final int CACHE_TICKS = 200;

    private record Best(long gameTime, int level, @Nullable Item signature) {}

    /** Each village's best cook, looked up every few seconds at most. */
    private static final Map<Object, Best> BEST = new WeakHashMap<>();

    private CookBonuses() {}

    /** Counts one more batch of {@code dish} in this cook's lifetime totals. */
    public static void recordDish(CompoundTag data, Item dish) {
        CompoundTag totals = data.getCompound(TAG_TOTALS);
        String key = key(dish);
        totals.putInt(key, totals.getInt(key) + 1);
        data.put(TAG_TOTALS, totals);
    }

    /** The dish this cook has made most, or null before it has made any. */
    @Nullable
    public static Item signature(CompoundTag data) {
        CompoundTag totals = data.getCompound(TAG_TOTALS);
        Item best = null;
        int bestCount = 0;
        for (String key : totals.getAllKeys()) {
            int count = totals.getInt(key);
            if (count <= bestCount) continue;
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;
            best = BuiltInRegistries.ITEM.get(id);
            bestCount = count;
        }
        return best;
    }

    /** Extra taste of a cook-made {@code dish} in this village: Seasoned, and the Signature Dish. */
    public static float taste(VillageManager village, long gameTime, Item dish) {
        Best best = best(village, gameTime);
        float bonus = 0f;
        if (best.level() >= CookBalance.SEASONED_LEVEL) bonus += CookBalance.SEASONED_TASTE;
        if (best.level() >= CookBalance.SIGNATURE_LEVEL && dish == best.signature()) {
            bonus += CookBalance.SIGNATURE_TASTE;
        }
        return bonus;
    }

    /** Extra nutrition of a cook-made dish in this village: Hearty Portions. */
    public static int nutrition(VillageManager village, long gameTime) {
        return best(village, gameTime).level() >= CookBalance.HEARTY_LEVEL ? CookBalance.HEARTY_NUTRITION : 0;
    }

    /** Whether {@code dish} is the Signature Dish of this village's best cook. */
    public static boolean isSignature(VillageManager village, long gameTime, Item dish) {
        Best best = best(village, gameTime);
        return best.level() >= CookBalance.SIGNATURE_LEVEL && dish == best.signature();
    }

    private static Best best(VillageManager village, long gameTime) {
        Best cached = BEST.get(village);
        if (cached != null && gameTime - cached.gameTime() < CACHE_TICKS
                && gameTime >= cached.gameTime()) return cached;
        int level = 0;
        Item signature = null;
        boolean someoneUnloaded = false;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null) {
                someoneUnloaded = true;
                continue;
            }
            if (npc.getProfession() != Profession.COOK) continue;
            int cookLevel = model.brain().cookSkillLevel();
            if (cookLevel <= level) continue;
            level = cookLevel;
            signature = signature(npc.getBrainData());
        }
        // A cook out of loaded range still cooks for the village: keep the
        // best seen last time while a villager is unloaded and no loaded cook
        // is better. Everyone loaded and no cook: the village has none now.
        if (cached != null && someoneUnloaded && cached.level() > level) {
            level = cached.level();
            signature = cached.signature();
        }
        Best best = new Best(gameTime, level, signature);
        BEST.put(village, best);
        return best;
    }

    private static String key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
