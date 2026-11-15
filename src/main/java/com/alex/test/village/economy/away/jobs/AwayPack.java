package com.alex.test.village.economy.away.jobs;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.economy.away.AwayContext;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Pack sums the away jobs share: room for a whole take, and a bank trip's deposit (butcher, lumberjack). */
final class AwayPack {

    private AwayPack() {}

    /** Whether every stack fits in the pack together: {@code addItem}'s own placing, on a copy. */
    static boolean fits(InventoryManager pack, List<ItemStack> stacks) {
        ItemStack[] slots = new ItemStack[InventoryManager.SIZE];
        for (int i = 0; i < slots.length; i++) slots[i] = pack.getSlot(i).copy();
        for (ItemStack stack : stacks) {
            // Alone first: the pack's own answer, its ownership rule included.
            if (pack.capacityFor(stack) < stack.getCount()) return false;
            int left = stack.getCount();
            for (int i = 0; i < slots.length && left > 0; i++) {
                ItemStack slot = slots[i];
                if (slot.isEmpty() || !slot.isStackable() || !ItemStack.isSameItemSameComponents(slot, stack)) continue;
                int placed = Math.min(left, Math.max(0, slot.getMaxStackSize() - slot.getCount()));
                slot.grow(placed);
                left -= placed;
            }
            for (int i = 0; i < slots.length && left > 0; i++) {
                if (!slots[i].isEmpty()) continue;
                int placed = Math.min(left, stack.getMaxStackSize());
                slots[i] = stack.copyWithCount(placed);
                left -= placed;
            }
            if (left > 0) return false;
        }
        return true;
    }

    /** Goods the next bank would take ({@code DepositActivity.keptPerSlot}). */
    static int depositable(AwayContext ctx) {
        InventoryManager pack = ctx.npc().getInventory();
        int[] kept = DepositActivity.keptPerSlot(ctx.activity());
        int count = 0;
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = pack.getSlot(slot);
            if (!stack.isEmpty()) count += Math.max(0, stack.getCount() - kept[slot]);
        }
        return count;
    }

    /**
     * A bank: everything the villager does not keep into storage, as far as
     * storage takes it. How many items went in (0: storage took nothing). The
     * caller counts the trip's time.
     */
    static int bank(AwayContext ctx) {
        InventoryManager pack = ctx.npc().getInventory();
        int[] kept = DepositActivity.keptPerSlot(ctx.activity());
        int moved = 0;
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = pack.getSlot(slot);
            if (stack.isEmpty() || kept[slot] >= stack.getCount()) continue;
            int stored = ctx.store(stack.copyWithCount(stack.getCount() - kept[slot]));
            if (stored > 0) {
                pack.reduceSlot(slot, stored);
                moved += stored;
            }
        }
        return moved;
    }
}
