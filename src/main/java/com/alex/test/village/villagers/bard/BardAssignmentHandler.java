package com.alex.test.village.villagers.bard;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;

/** Handles assignment into the Bard profession. */
public final class BardAssignmentHandler {

    private BardAssignmentHandler() {}

    public static boolean isBardToken(ItemStack stack) {
        return stack.is(ModItems.BARD_TOKEN.get());
    }

    /** Jobless → Bard. No starter tool; the profession works bare-handed. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        villager.setProfession(Profession.BARD);
        return villager.getProfession() == Profession.BARD;
    }
}
