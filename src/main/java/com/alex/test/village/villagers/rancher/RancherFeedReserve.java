package com.alex.test.village.villagers.rancher;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.behavior.RancherActivity;
import com.alex.test.village.villagers.rancher.settings.RancherSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.supplies.CarryReserve;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The animals' share of a rancher's pack: up to {@link #KEEP} of each breeding
 * food it feeds. Of those only the pigs' (carrots, potatoes, beetroot) are also
 * food, and carrots were lunch: the
 * restock took them like bread, so pigs were the one kind bred without a trip
 * to storage, and the rancher ate the pigs' food. Held back, they are not
 * counted toward the food reserve, not eaten while the pack holds other food,
 * and not taken as lunch at the chest while storage has other food.
 */
public final class RancherFeedReserve {

    private RancherFeedReserve() {}

    /** One breeding-food trip's worth ({@code RancherActivity.FOOD_PER_FETCH}). */
    public static final int KEEP = 8;

    private static final List<EntityType<?>> KINDS =
            List.of(EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN);

    /** The breeding foods this villager feeds: none unless it is a rancher at work with breeding on. */
    public static Set<Item> feeds(NpcEntity npc) {
        if (npc == null || npc.getProfession() != Profession.RANCHER) return Set.of();
        ProfessionSettings settings = ProfessionSettings.of(npc, RancherSettingsSchema.SCHEMA);
        if (!settings.getBool(RancherSettingsSchema.WORK_ENABLED)
                || !settings.getBool(RancherSettingsSchema.BREEDING_ENABLED)) return Set.of();
        Set<Item> foods = new HashSet<>();
        for (EntityType<?> kind : KINDS) {
            if (RancherActivity.isAllowedSpecies(settings, kind)) foods.addAll(RanchAnimals.breedFoodsFor(kind));
        }
        return foods;
    }

    /** Units of each pack slot held back for the animals, slot by slot in pack order. */
    public static int[] heldBack(NpcEntity npc) {
        InventoryManager inv = npc.getInventory();
        int[] aside = new int[inv.getSlots().length];
        Set<Item> foods = feeds(npc);
        if (foods.isEmpty()) return aside;
        Map<Item, Integer> kept = new HashMap<>();
        for (int slot = 0; slot < aside.length; slot++) {
            ItemStack stack = inv.getSlot(slot);
            if (stack.isEmpty() || !foods.contains(stack.getItem()) || !inv.mayUse(stack)) continue;
            int already = kept.getOrDefault(stack.getItem(), 0);
            aside[slot] = CarryReserve.toKeep(stack.getCount(), already, KEEP);
            kept.put(stack.getItem(), already + aside[slot]);
        }
        return aside;
    }

    /**
     * {@link #heldBack} for a meal: while the pack holds other food the
     * villager may eat. With nothing else to hand it eats the carrots after
     * all; going hungry with food in the pocket helps no animal.
     */
    public static int[] heldBackAtMeals(NpcEntity npc) {
        int[] aside = heldBack(npc);
        InventoryManager inv = npc.getInventory();
        for (int slot = 0; slot < aside.length; slot++) {
            ItemStack stack = inv.getSlot(slot);
            if (stack.getCount() > aside[slot] && inv.mayUse(stack)
                    && VillageItemOwnership.villagerFood(stack, npc) != null) return aside;
        }
        return new int[aside.length];
    }
}
