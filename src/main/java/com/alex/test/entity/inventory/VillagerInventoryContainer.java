package com.alex.test.entity.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A read-only {@link Container} view of an {@link InventoryManager}, used by the
 * display slots of {@link com.alex.test.ui.VillagerMenu}. Players may look but
 * never take or insert, so every write is a no-op.
 */
public final class VillagerInventoryContainer implements Container {

    private final InventoryManager inventory;

    public VillagerInventoryContainer(InventoryManager inventory) {
        this.inventory = inventory;
    }

    @Override public int     getContainerSize() { return InventoryManager.SIZE; }
    @Override public boolean stillValid(Player p) { return true; }
    @Override public boolean isEmpty() { return inventory.isEmpty(); }
    @Override public ItemStack getItem(int slot) { return inventory.getSlot(slot); }

    @Override public ItemStack removeItem(int slot, int amount) { return ItemStack.EMPTY; }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ItemStack.EMPTY; }
    @Override public void setItem(int slot, ItemStack stack) { }
    @Override public void setChanged() { }
    @Override public void clearContent() { }
}
