package com.alex.test.village.villagers.druid;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;

/** Handles assignment into the Druid profession. */
public final class DruidAssignmentHandler {

    private DruidAssignmentHandler() {}

    public static boolean isDruidToken(ItemStack stack) {
        return stack.is(ModItems.DRUID_TOKEN.get());
    }

    /** Jobless → Druid. No starter tool; the profession works bare-handed. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        villager.setProfession(Profession.DRUID);
        return villager.getProfession() == Profession.DRUID;
    }
}
