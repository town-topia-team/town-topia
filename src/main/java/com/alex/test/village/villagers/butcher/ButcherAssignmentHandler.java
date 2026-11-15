package com.alex.test.village.villagers.butcher;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.butcher.lead.LeadSupply;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Handles assignment into the Butcher profession. */
public final class ButcherAssignmentHandler {

    private ButcherAssignmentHandler() {}

    public static boolean isButcherToken(ItemStack stack) {
        return stack.is(ModItems.BUTCHER_TOKEN.get());
    }

    /**
     * Jobless → Butcher. Starter wooden sword for slaughter duty and a lead to
     * walk animals to the Butchery. Both or neither: a pack with room for only
     * one fails the assignment, as a full pack always has.
     */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        InventoryManager inv = villager.getInventory();
        ItemStack[] before = new ItemStack[InventoryManager.SIZE];
        for (int i = 0; i < before.length; i++) before[i] = inv.getSlot(i).copy();
        boolean added = inv.addItem(com.alex.test.village.tokens.VillageItemOwnership.issue(
                        new ItemStack(Items.WOODEN_SWORD), villager))
                && inv.addItem(LeadSupply.issue(villager));
        if (!added) {
            for (int i = 0; i < before.length; i++) inv.setSlot(i, before[i]);
            return false;
        }
        LeadSupply.markGiven(villager);
        villager.setProfession(Profession.BUTCHER);
        return villager.getProfession() == Profession.BUTCHER;
    }
}
