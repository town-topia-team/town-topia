package com.alex.test.village.villagers.builder;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;

/** Handles assignment into the Builder profession. */
public final class BuilderAssignmentHandler {

    private BuilderAssignmentHandler() {}

    public static boolean isBuilderToken(ItemStack stack) {
        return stack.is(ModItems.BUILDER_TOKEN.get());
    }

    /** Jobless → Builder. No starter tool; the profession works bare-handed. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        villager.setProfession(Profession.BUILDER);
        return villager.getProfession() == Profession.BUILDER;
    }
}
