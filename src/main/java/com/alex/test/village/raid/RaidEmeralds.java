package com.alex.test.village.raid;

import com.alex.test.village.VillageManager;
import com.alex.test.village.tokens.VillageItemOwnership;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Emeralds a raid gives: new goods of the village that was raided, so they
 * carry its ID like the Tradesman's and the merchant's. If the stamp cannot
 * be put on, they stay plain rather than being lost.
 */
final class RaidEmeralds {

    private RaidEmeralds() {}

    /** {@code count} emeralds of {@code village}, as full stacks. */
    static List<ItemStack> make(VillageManager village, int count) {
        List<ItemStack> stacks = new ArrayList<>();
        int left = count;
        while (left > 0) {
            int size = Math.min(left, Items.EMERALD.getDefaultMaxStackSize());
            stacks.add(stamp(new ItemStack(Items.EMERALD, size), village));
            left -= size;
        }
        return stacks;
    }

    /** The stack with {@code village}'s ID on it, or the stack itself when that fails. */
    static ItemStack stamp(ItemStack stack, VillageManager village) {
        if (village == null) return stack;
        ItemStack bound = VillageItemOwnership.produce(stack.copy(), village);
        return bound.isEmpty() ? stack : bound;
    }
}
