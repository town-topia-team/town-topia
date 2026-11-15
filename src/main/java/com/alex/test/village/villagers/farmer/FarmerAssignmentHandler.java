package com.alex.test.village.villagers.farmer;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Handles assignment into the Farmer profession. */
public final class FarmerAssignmentHandler {

    private FarmerAssignmentHandler() {}

    public static boolean isFarmerToken(ItemStack stack) {
        return stack.is(ModItems.FARMER_TOKEN.get());
    }

    /**
     * Jobless → Farmer. The starter kit is a wooden hoe plus a handful of
     * carrots, so a fresh farmer can work an empty farm before the village has
     * any seed stock to fetch from.
     */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        InventoryManager inventory = villager.getInventory();
        ItemStack hoe = com.alex.test.village.tokens.VillageItemOwnership.issue(
                new ItemStack(Items.WOODEN_HOE), villager);
        ItemStack carrots = com.alex.test.village.tokens.VillageItemOwnership.issue(
                new ItemStack(Items.CARROT, 8), villager);
        if (hoe.isEmpty() || carrots.isEmpty()) return false;
        int hoeSlot = -1;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            if (inventory.getSlot(i).isEmpty()) {
                hoeSlot = i;
                break;
            }
        }
        if (hoeSlot < 0 || !inventory.addItem(hoe)) return false;
        if (!inventory.addItem(carrots)) {
            inventory.setSlot(hoeSlot, ItemStack.EMPTY);
            return false;
        }
        villager.setProfession(Profession.FARMER);
        return villager.getProfession() == Profession.FARMER;
    }
}
