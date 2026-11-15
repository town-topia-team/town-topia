package com.alex.test.village.villagers.cleric;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;

/** Handles assignment into the Cleric profession. */
public final class ClericAssignmentHandler {

    private ClericAssignmentHandler() {}

    public static boolean isClericToken(ItemStack stack) {
        return stack.is(ModItems.CLERIC_TOKEN.get());
    }

    /** Jobless → Cleric. No starter tool; the profession works bare-handed. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        villager.setProfession(Profession.CLERIC);
        return villager.getProfession() == Profession.CLERIC;
    }
}
