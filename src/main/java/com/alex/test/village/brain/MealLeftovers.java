package com.alex.test.village.brain;

import com.alex.test.LegacyModId;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.tokens.VillageItemOwnership;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

/**
 * What an eaten meal leaves: the bowl of a stew or soup, the glass bottle of
 * a honey bottle, as vanilla hands them back. A container lasts three meals
 * (user 10-22): the third time it is emptied it breaks and is gone. Bowls
 * stack and look alike, so the count is kept per villager and per kind of
 * container, not on the bowl: two in every three still come back.
 */
public final class MealLeftovers {
    /** Meals one bowl or bottle lasts. */
    public static final int USES = 3;
    // Saved on the villager: the old prefix, like every name written into worlds (LegacyModId).
    private static final String KEY = LegacyModId.SAVE_PREFIX + "_emptied_containers";

    private MealLeftovers() {}

    /**
     * The empty container {@code meal} leaves, now the village's own (a
     * player's stew too: the villager ate from it), or EMPTY when the meal
     * leaves none or this was the container's last use.
     */
    public static ItemStack leftover(NpcEntity npc, ItemStack meal, @Nullable VillageManager village) {
        ItemStack container = containerOf(meal);
        if (container.isEmpty() || village == null) return ItemStack.EMPTY;
        CompoundTag counts = npc.getPersistentData().getCompound(KEY);
        String kind = BuiltInRegistries.ITEM.getKey(container.getItem()).toString();
        int emptied = counts.getInt(kind) + 1;
        if (emptied >= USES) {
            counts.remove(kind);
        } else {
            counts.putInt(kind, emptied);
        }
        npc.getPersistentData().put(KEY, counts);
        return emptied >= USES ? ItemStack.EMPTY : VillageItemOwnership.produce(container, village);
    }

    /** A copy of the villager's emptied-container counts, for the away calculator's villager copy. */
    public static CompoundTag counts(NpcEntity npc) {
        return npc.getPersistentData().getCompound(KEY).copy();
    }

    /** Puts back counts read with {@link #counts}. */
    public static void setCounts(NpcEntity npc, CompoundTag counts) {
        if (counts.isEmpty()) {
            npc.getPersistentData().remove(KEY);
        } else {
            npc.getPersistentData().put(KEY, counts.copy());
        }
    }

    /** The container vanilla hands back after eating {@code meal}, or EMPTY. */
    public static ItemStack containerOf(ItemStack meal) {
        if (meal.getItem() instanceof HoneyBottleItem) return new ItemStack(Items.GLASS_BOTTLE);
        // raw food data: the cook's dishes carry their own food values without the bowl; ask the item.
        FoodProperties vanilla = meal.getItem().components().get(DataComponents.FOOD);
        return vanilla == null ? ItemStack.EMPTY : vanilla.usingConvertsTo().map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }
}
