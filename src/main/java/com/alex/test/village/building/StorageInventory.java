package com.alex.test.village.building;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * The contents of one storage chest or barrel, however its mod stores them.
 *
 * <p>Vanilla chests and barrels (and Iron Chests) are a vanilla {@link Container}; other
 * mods (Sophisticated Storage) only offer NeoForge's item handler. Their slots can hold
 * more than a normal stack, and the stack a slot shows must never be changed in place, so
 * every change goes through {@link #take} and {@link #insert}. Get one from
 * {@link StorageContainers#inventory}.
 */
public interface StorageInventory {

    int size();

    /** The stack in {@code slot}. Read only: take or insert to change it. */
    ItemStack peek(int slot);

    /** Removes up to {@code amount} from {@code slot} and returns what came out. */
    ItemStack take(int slot, int amount);

    /**
     * Puts what fits of {@code stack} in, topping up matching stacks before using empty
     * slots, and returns what did not fit. {@code stack} itself is left untouched.
     */
    ItemStack insert(ItemStack stack, boolean simulate);

    /**
     * The same object for every position showing these contents. Both halves of a
     * Sophisticated double chest hand out the main half's contents, so they share a key.
     */
    Object key();

    /** How many of {@code stack} would go in right now. */
    default int accepts(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        return stack.getCount() - insert(stack, true).getCount();
    }

    default boolean isEmpty() {
        for (int i = 0; i < size(); i++) {
            if (!peek(i).isEmpty()) return false;
        }
        return true;
    }

    default int emptySlots() {
        int empty = 0;
        for (int i = 0; i < size(); i++) {
            if (peek(i).isEmpty()) empty++;
        }
        return empty;
    }

    /** A vanilla {@link Container}: chests, barrels, Iron Chests. */
    record OfContainer(Container container) implements StorageInventory {
        @Override public int size() { return container.getContainerSize(); }

        @Override public ItemStack peek(int slot) { return container.getItem(slot); }

        @Override
        public ItemStack take(int slot, int amount) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            int taken = Math.min(amount, stack.getCount());
            ItemStack result = stack.copyWithCount(taken);
            stack.shrink(taken);
            if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
            container.setChanged();
            return result;
        }

        @Override
        public ItemStack insert(ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            int left = stack.getCount();
            int limit = container.getMaxStackSize(stack);
            boolean changed = false;
            // Top up matching stacks first.
            for (int i = 0; i < container.getContainerSize() && left > 0; i++) {
                ItemStack slot = container.getItem(i);
                if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) continue;
                int add = Math.min(left, limit - slot.getCount());
                if (add <= 0) continue;
                if (!simulate) slot.grow(add);
                left -= add;
                changed = true;
            }
            // Then empty slots.
            for (int i = 0; i < container.getContainerSize() && left > 0; i++) {
                if (!container.getItem(i).isEmpty() || !container.canPlaceItem(i, stack)) continue;
                int add = Math.min(left, limit);
                if (!simulate) container.setItem(i, stack.copyWithCount(add));
                left -= add;
                changed = true;
            }
            if (changed && !simulate) container.setChanged();
            return left <= 0 ? ItemStack.EMPTY : stack.copyWithCount(left);
        }

        @Override public Object key() { return container; }
    }

    /**
     * A NeoForge item handler (Sophisticated Storage). Its own rules apply: the player's
     * lock, filter and stack upgrades decide what goes in and how much a slot holds.
     */
    record OfHandler(IItemHandler handler, Object key) implements StorageInventory {
        @Override public int size() { return handler.getSlots(); }

        @Override public ItemStack peek(int slot) { return handler.getStackInSlot(slot); }

        @Override
        public ItemStack take(int slot, int amount) {
            ItemStack result = ItemStack.EMPTY;
            int left = amount;
            // A handler hands out at most one normal stack per call; big slots need a few.
            for (int calls = 0; left > 0 && calls < 64; calls++) {
                ItemStack got = handler.extractItem(slot, left, false);
                if (got.isEmpty()) break;
                if (result.isEmpty()) {
                    result = got.copy();
                } else if (ItemStack.isSameItemSameComponents(result, got)) {
                    result.grow(got.getCount());
                } else {
                    // The slot changed under us: give the stranger straight back.
                    handler.insertItem(slot, got, false);
                    break;
                }
                left -= got.getCount();
            }
            return result;
        }

        @Override
        public ItemStack insert(ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            return ItemHandlerHelper.insertItemStacked(handler, stack.copy(), simulate);
        }
    }
}
