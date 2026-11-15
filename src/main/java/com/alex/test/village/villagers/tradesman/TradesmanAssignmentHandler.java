package com.alex.test.village.villagers.tradesman;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;

/** Handles assignment into the Tradesman profession. */
public final class TradesmanAssignmentHandler {

    private TradesmanAssignmentHandler() {}

    public static boolean isTradesmanToken(ItemStack stack) {
        return stack.is(ModItems.TRADESMAN_TOKEN.get());
    }

    /** Jobless → Tradesman. No starter tool; the profession works bare-handed. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        villager.setProfession(Profession.TRADESMAN);
        return villager.getProfession() == Profession.TRADESMAN;
    }
}
