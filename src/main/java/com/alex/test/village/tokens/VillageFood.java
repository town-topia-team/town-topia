package com.alex.test.village.tokens;

import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;

/**
 * Who made a village dish, and the one way village food is made.
 *
 * <p>The cook's dishes ({@link Maker#CHEF}) carry the cook's food values
 * (bread worth 7, a stew stacking to 16) and taste as the food catalogue
 * says. Any other villager's dish ({@link Maker#VILLAGER}: bread a hungry
 * villager bakes, the founding gift, a taken-over village's stores) keeps
 * vanilla's values, tastes a point worse, and is called "Poor Man's ..." so
 * the two are told apart at a glance.
 *
 * <p>Every village dish goes through {@link #chef} or {@link #villagerMade},
 * so two loaves from the same kind of maker are always the same item and
 * stack. Before this, the founding bread kept vanilla's values while the
 * cook's had his, and the two never stacked though both said "Bread".
 */
public final class VillageFood {

    public enum Maker { CHEF, VILLAGER }

    private static final String KEY = "dynamicvillages:food_maker";
    /** What a villager-made dish is called in front of its own name. */
    public static final String POOR_PREFIX = "Poor Man's ";
    /** Taste a villager-made dish has against the same dish from the cook. */
    public static final float VILLAGER_MADE_TASTE = -1f;

    private VillageFood() {}

    /** Whether {@code item} is one of the cook's dishes. */
    public static boolean isDish(Item item) {
        for (CookActivity.Recipe recipe : CookActivity.recipes()) {
            if (recipe.result() == item) return true;
        }
        return false;
    }

    /** A dish the cook made: his food values, marked as his, bound to the village. */
    public static ItemStack chef(ItemStack stack, VillageManager village) {
        CookActivity.applyProducedFoodProperties(stack);
        // Charcoal and bowls come off the same stations but are no dish.
        if (isDish(stack.getItem())) mark(stack, Maker.CHEF);
        return VillageItemOwnership.produce(stack, village);
    }

    /** A dish another villager made: vanilla food values, "Poor Man's", bound to the village. */
    public static ItemStack villagerMade(Item item, int count, VillageManager village) {
        ItemStack stack = new ItemStack(item, count);
        markVillagerMade(stack);
        return VillageItemOwnership.produce(stack, village);
    }

    /**
     * Turns {@code stack} into a villager-made dish before it is bound: back
     * to vanilla's food values, and marked. Not a dish: left alone.
     */
    public static void markVillagerMade(ItemStack stack) {
        if (stack.isEmpty() || !isDish(stack.getItem())) return;
        restoreVanillaFood(stack);
        mark(stack, Maker.VILLAGER);
    }

    /** Who made this stack, or null when it is no marked village dish. */
    @Nullable
    public static Maker maker(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (!tag.contains(KEY)) return null;
        return "villager".equals(tag.getString(KEY)) ? Maker.VILLAGER : Maker.CHEF;
    }

    public static boolean isChefMade(ItemStack stack) {
        return maker(stack) == Maker.CHEF;
    }

    public static boolean isVillagerMade(ItemStack stack) {
        return maker(stack) == Maker.VILLAGER;
    }

    /**
     * A village dish made before makers were marked, sorted out so it stacks
     * with today's: the cook's food values mean the cook made it, anything
     * else a villager. Only bound dishes are touched.
     */
    public static void normalize(ItemStack stack) {
        if (stack.isEmpty() || !VillageTokenBinding.isBound(stack) || maker(stack) != null) return;
        if (!isDish(stack.getItem())) return;
        ItemStack chef = stack.copyWithCount(1);
        CookActivity.applyProducedFoodProperties(chef);
        // raw food data: compares the cook's food values, not whether a villager eats it.
        if (java.util.Objects.equals(chef.get(DataComponents.FOOD), stack.get(DataComponents.FOOD))) {
            mark(stack, Maker.CHEF);
        } else {
            restoreVanillaFood(stack);
            mark(stack, Maker.VILLAGER);
            VillageTokenBinding.refreshName(stack);
        }
    }

    private static void mark(ItemStack stack, Maker maker) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putString(KEY, maker == Maker.VILLAGER ? "villager" : "chef"));
    }

    /**
     * Vanilla's food values and stack size, as the item comes. The stack size
     * only when the stack fits it: sixteen old cook stews must not become one
     * stack of a dish that holds one.
     */
    private static void restoreVanillaFood(ItemStack stack) {
        // raw food data: puts back vanilla's own food values.
        stack.set(DataComponents.FOOD, stack.getItem().components().get(DataComponents.FOOD));
        Integer vanillaMax = stack.getItem().components().get(DataComponents.MAX_STACK_SIZE);
        if (vanillaMax != null && stack.getCount() <= vanillaMax) {
            stack.set(DataComponents.MAX_STACK_SIZE, vanillaMax);
        }
    }
}
