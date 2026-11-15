package com.alex.test.village.villagers.rancher;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.butcher.lead.LeadSupply;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Handles assignment into the Rancher profession. */
public final class RancherAssignmentHandler {

    private RancherAssignmentHandler() {}

    public static boolean isRancherToken(ItemStack stack) {
        return stack.is(ModItems.RANCHER_TOKEN.get());
    }

    /**
     * Jobless → Rancher. Starter shears for wool duty and a lead to bring
     * strays home. Both or neither: a pack with room for only one fails.
     */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        InventoryManager inv = villager.getInventory();
        ItemStack[] before = new ItemStack[InventoryManager.SIZE];
        for (int i = 0; i < before.length; i++) before[i] = inv.getSlot(i).copy();
        boolean added = inv.addItem(com.alex.test.village.tokens.VillageItemOwnership.issue(
                        new ItemStack(Items.SHEARS), villager))
                && inv.addItem(LeadSupply.issue(villager));
        if (!added) {
            for (int i = 0; i < before.length; i++) inv.setSlot(i, before[i]);
            return false;
        }
        LeadSupply.markGiven(villager);
        villager.setProfession(Profession.RANCHER);
        return villager.getProfession() == Profession.RANCHER;
    }
}
