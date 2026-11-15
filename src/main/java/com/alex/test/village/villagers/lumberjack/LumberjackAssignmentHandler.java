package com.alex.test.village.villagers.lumberjack;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Handles assignment into the Lumberjack profession. */
public final class LumberjackAssignmentHandler {

    private LumberjackAssignmentHandler() {}

    public static boolean isLumberjackToken(ItemStack stack) {
        return stack.is(ModItems.LUMBERJACK_TOKEN.get());
    }

    /**
     * Perform the one-time jobless/nitwit to Lumberjack transition.
     * The starter axe is stored in the same custom inventory used by
     * LumberjackActivity and GetAxeActivity.
     */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        if (!villager.getInventory().addItem(com.alex.test.village.tokens.VillageItemOwnership.issue(
                new ItemStack(Items.WOODEN_AXE), villager))) return false;
        villager.setProfession(Profession.LUMBERJACK);
        return villager.getProfession() == Profession.LUMBERJACK;
    }
}
