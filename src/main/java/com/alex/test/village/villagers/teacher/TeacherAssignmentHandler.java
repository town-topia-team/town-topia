package com.alex.test.village.villagers.teacher;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;

/** Handles assignment into the Teacher profession. */
public final class TeacherAssignmentHandler {

    private TeacherAssignmentHandler() {}

    public static boolean isTeacherToken(ItemStack stack) {
        return stack.is(ModItems.TEACHER_TOKEN.get());
    }

    /** Jobless → Teacher. No starter tool; the profession works bare-handed. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        villager.setProfession(Profession.TEACHER);
        return villager.getProfession() == Profession.TEACHER;
    }
}
