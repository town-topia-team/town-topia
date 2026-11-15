package com.alex.test.village.villagers.miner;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Handles assignment into the Miner profession. */
public final class MinerAssignmentHandler {

    private MinerAssignmentHandler() {}

    public static boolean isMinerToken(ItemStack stack) {
        return stack.is(ModItems.MINER_TOKEN.get());
    }

    /** Jobless → Miner. Starter wooden pickaxe. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        if (!villager.getInventory().addItem(com.alex.test.village.tokens.VillageItemOwnership.issue(
                new ItemStack(Items.WOODEN_PICKAXE), villager))) return false;
        villager.setProfession(Profession.MINER);
        return villager.getProfession() == Profession.MINER;
    }
}
