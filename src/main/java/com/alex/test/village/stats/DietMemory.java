package com.alex.test.village.stats;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * What a villager has eaten lately, and the one food it loves and the one it
 * would rather not eat.
 *
 * <p>Only the main dish of each meal is remembered, the last
 * {@value #MEALS_REMEMBERED} of them. Something not among them is a treat
 * (+2); the same food over and over gets dull, down to -3. A favourite never
 * gets dull and adds +2; the disliked food costs 2.
 */
public final class DietMemory {

    public static final int MEALS_REMEMBERED = 6;
    public static final float NEW_FOOD_JOY = 2f;
    public static final float FAVOURITE_JOY = 2f;
    public static final float DISLIKED_JOY = -2f;

    private static final String TAG = "diet";
    private static final String TAG_RECENT = "recent";
    private static final String TAG_FAVOURITE = "favourite";
    private static final String TAG_DISLIKED = "disliked";

    private final Deque<Item> recent = new ArrayDeque<>();
    @Nullable private Item favourite;
    @Nullable private Item disliked;

    /** Rolls the favourite and the disliked food the first time they are needed. */
    public void ensureRolled(RandomSource random) {
        if (favourite != null) return;
        List<Item> pool = FoodCatalog.PREFERENCE_CANDIDATES;
        favourite = pool.get(random.nextInt(pool.size()));
        do {
            disliked = pool.get(random.nextInt(pool.size()));
        } while (disliked == favourite);
    }

    @Nullable public Item favourite() { return favourite; }
    @Nullable public Item disliked() { return disliked; }

    /** Main dishes of the remembered meals, oldest first. */
    public List<Item> recent() {
        return List.copyOf(recent);
    }

    public int timesEaten(Item item) {
        int count = 0;
        for (Item eaten : recent) if (eaten == item) count++;
        return count;
    }

    /** Novelty or boredom for {@code item} as the next main dish. */
    public float varietyJoy(Item item) {
        if (item == favourite) return 0f;
        return noveltyJoy(item);
    }

    /**
     * Novelty or boredom by meals alone, with no favourite exception: what a
     * player's food gets, which is never the favourite (FoodCatalog.varietyJoy).
     */
    public float noveltyJoy(Item item) {
        return switch (timesEaten(item)) {
            case 0 -> NEW_FOOD_JOY;
            case 1 -> 0f;
            case 2 -> -1f;
            case 3 -> -2f;
            default -> -3f;
        };
    }

    public float likingJoy(Item item) {
        if (item == favourite) return FAVOURITE_JOY;
        if (item == disliked) return DISLIKED_JOY;
        return 0f;
    }

    /** Everything this villager's history and tastes add to {@code item} as a main dish. */
    public float adjustment(Item item) {
        return varietyJoy(item) + likingJoy(item);
    }

    public void record(Item item) {
        recent.addLast(item);
        while (recent.size() > MEALS_REMEMBERED) recent.removeFirst();
    }

    public void save(CompoundTag parent) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Item item : recent) list.add(StringTag.valueOf(id(item)));
        tag.put(TAG_RECENT, list);
        if (favourite != null) tag.putString(TAG_FAVOURITE, id(favourite));
        if (disliked != null) tag.putString(TAG_DISLIKED, id(disliked));
        parent.put(TAG, tag);
    }

    public void load(CompoundTag parent) {
        recent.clear();
        favourite = null;
        disliked = null;
        if (!parent.contains(TAG, Tag.TAG_COMPOUND)) return;
        CompoundTag tag = parent.getCompound(TAG);
        ListTag list = tag.getList(TAG_RECENT, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            Item item = item(list.getString(i));
            if (item != null) record(item);
        }
        favourite = item(tag.getString(TAG_FAVOURITE));
        disliked = item(tag.getString(TAG_DISLIKED));
        if (favourite == null || disliked == null) {
            favourite = null;
            disliked = null;
        }
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    @Nullable
    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return null;
        Item item = BuiltInRegistries.ITEM.get(key);
        return item == Items.AIR ? null : item;
    }
}
