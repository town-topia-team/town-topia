package com.alex.test.village.villagers.cook;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;

/** Handles assignment into the Cook profession. */
public final class CookAssignmentHandler {

    private CookAssignmentHandler() {}

    public static boolean isCookToken(ItemStack stack) {
        return stack.is(ModItems.COOK_TOKEN.get());
    }

    /** Jobless → Cook. No starter tool — the kitchen provides the stations. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        villager.setProfession(Profession.COOK);
        return villager.getProfession() == Profession.COOK;
    }
}
