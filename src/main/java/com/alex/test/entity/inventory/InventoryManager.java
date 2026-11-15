package com.alex.test.entity.inventory;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.tokens.VillageTokenBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple fixed-size item inventory for villager entities.
 *
 * <p>Used by {@link com.alex.test.entity.NpcEntity} to store carried items.
 * The owning entity is responsible for NBT serialisation — call
 * {@link #save(HolderLookup.Provider)} and
 * {@link #load(List, HolderLookup.Provider)} from the entity's save/load hooks.
 */
public final class InventoryManager {

    public static final int COLUMNS = 9;
    public static final int ROWS = 3;
    public static final int SIZE = COLUMNS * ROWS;

    private final ItemStack[] slots;
    private VillageTokenBinding owner;

    /**
     * Bumped by every change made through this class. A cached scan result is
     * only trusted while it is unchanged. Stacks edited in place from outside
     * (a bow worn down, a count shrunk) do not bump it, so a cache must also
     * re-check the slot it remembers and re-scan on a timer.
     */
    private int changes;

    /** What the last bow / arrow scan found; see ArcherBowUtils. */
    public final SlotMemo bowMemo = new SlotMemo();
    public final SlotMemo arrowMemo = new SlotMemo();

    public static final class SlotMemo {
        public boolean known;
        public int slot = -1;
        public int changes;
        public long scannedAtNanos;
    }

    public int changes() { return changes; }

    public void setOwner(VillageTokenBinding owner) { this.owner = owner; changes++; }
    public boolean mayUse(ItemStack stack) { return VillageItemOwnership.mayUse(stack, owner); }
    /** Cooking, crafting and the like: a player's food does not count. */
    public boolean mayUseAsMaterial(ItemStack stack) { return VillageItemOwnership.mayUseAsMaterial(stack, owner); }

    public InventoryManager() {
        slots = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) slots[i] = ItemStack.EMPTY;
    }

    // -------------------------------------------------------------------------
    // Slot access
    // -------------------------------------------------------------------------

    public ItemStack[] getSlots() { return slots; }

    public ItemStack getSlot(int i) {
        return (i >= 0 && i < SIZE) ? slots[i] : ItemStack.EMPTY;
    }

    public void setSlot(int slot, ItemStack stack) {
        if (slot >= 0 && slot < SIZE) { slots[slot] = stack; changes++; }
    }

    /**
     * Whether any slot is free.
     *
     * <p>Conservative on purpose: a free slot takes any item, so a caller that
     * checks this before producing something can never produce something it
     * cannot carry. A partly-filled stack may well have room too, but knowing
     * that needs the item.
     */
    public boolean hasEmptySlot() {
        for (int i = 0; i < SIZE; i++) if (slots[i].isEmpty()) return true;
        return false;
    }

    /** How many of this exact stack can fit without changing the inventory. */
    public int capacityFor(ItemStack item) {
        if (item.isEmpty() || (owner != null && !mayUse(item))) return 0;
        int capacity = 0;
        for (int i = 0; i < SIZE; i++) {
            ItemStack slot = slots[i];
            if (slot.isEmpty()) {
                capacity += item.getMaxStackSize();
            } else if (slot.isStackable() && ItemStack.isSameItemSameComponents(slot, item)) {
                capacity += Math.max(0, slot.getMaxStackSize() - slot.getCount());
            }
        }
        return capacity;
    }

    public boolean addItem(ItemStack item) {
        if (item.isEmpty()) return false;
        // Keep insertion all-or-nothing for callers, including multi-stack hauls.
        if (capacityFor(item) < item.getCount()) return false;
        changes++;
        int remaining = item.getCount();
        // Try to stack onto an existing slot first
        for (int i = 0; i < SIZE && remaining > 0; i++) {
            if (!slots[i].isEmpty()
                    && slots[i].isStackable()
                    && ItemStack.isSameItemSameComponents(slots[i], item)) {
                int placed = Math.min(remaining, Math.max(0,
                        slots[i].getMaxStackSize() - slots[i].getCount()));
                slots[i].grow(placed);
                remaining -= placed;
            }
        }
        // Then find an empty slot
        for (int i = 0; i < SIZE && remaining > 0; i++) {
            if (slots[i].isEmpty()) {
                int placed = Math.min(remaining, item.getMaxStackSize());
                slots[i] = item.copyWithCount(placed);
                remaining -= placed;
            }
        }
        return true;
    }

    public boolean reduceSlot(int i, int amount) {
        if (i < 0 || i >= SIZE) return false;
        if (amount > slots[i].getCount()) return false;
        changes++;
        if (amount == slots[i].getCount()) { slots[i] = ItemStack.EMPTY; return true; }
        slots[i].shrink(amount);
        return true;
    }

    public boolean contains(Item item) {
        for (ItemStack stack : slots) {
            if (mayUse(stack) && stack.is(item)) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        for (ItemStack s : slots) if (!s.isEmpty()) return false;
        return true;
    }

    // -------------------------------------------------------------------------
    // NBT persistence
    // -------------------------------------------------------------------------

    public List<Tag> save(HolderLookup.Provider registries) {
        List<Tag> out = new ArrayList<>();
        for (ItemStack s : slots) {
            out.add(s.isEmpty() ? new net.minecraft.nbt.CompoundTag()
                               : (Tag) s.save(registries));
        }
        return out;
    }

    public void load(List<Tag> tags, HolderLookup.Provider registries) {
        for (int i = 0; i < SIZE && i < tags.size(); i++) {
            slots[i] = decodeStack(tags.get(i), registries);
        }
        changes++;
    }

    private static ItemStack decodeStack(Tag tag, HolderLookup.Provider registries) {
        return tag instanceof net.minecraft.nbt.CompoundTag compound && compound.isEmpty()
                ? ItemStack.EMPTY
                : ItemStack.parse(registries, tag).orElse(ItemStack.EMPTY);
    }
}
