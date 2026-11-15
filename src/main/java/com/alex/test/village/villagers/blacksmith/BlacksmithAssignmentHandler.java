package com.alex.test.village.villagers.blacksmith;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;

/** Handles assignment into the Blacksmith profession. */
public final class BlacksmithAssignmentHandler {

    private BlacksmithAssignmentHandler() {}

    public static boolean isBlacksmithToken(ItemStack stack) {
        return stack.is(ModItems.BLACKSMITH_TOKEN.get());
    }

    /** Jobless → Blacksmith. No starter tool — the forge provides the stations. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        villager.setProfession(Profession.BLACKSMITH);
        return villager.getProfession() == Profession.BLACKSMITH;
    }
}
