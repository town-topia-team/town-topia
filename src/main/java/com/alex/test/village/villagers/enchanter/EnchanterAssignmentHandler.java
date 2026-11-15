package com.alex.test.village.villagers.enchanter;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;

/** Handles assignment into the Enchanter profession. */
public final class EnchanterAssignmentHandler {

    private EnchanterAssignmentHandler() {}

    public static boolean isEnchanterToken(ItemStack stack) {
        return stack.is(ModItems.ENCHANTER_TOKEN.get());
    }

    /** Jobless → Enchanter. No starter tool; the profession works bare-handed. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        villager.setProfession(Profession.ENCHANTER);
        return villager.getProfession() == Profession.ENCHANTER;
    }
}
