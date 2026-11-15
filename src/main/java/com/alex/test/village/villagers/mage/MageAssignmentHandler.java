package com.alex.test.village.villagers.mage;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.CaptainAssignmentHandler;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * Handles assignment into the Mage profession. A mage is hired at a Mage Tower
 * or a Barracks: the token only works in a village with one of them. It sleeps
 * in a Mage Tower bed when one is free, else with the other defenders.
 */
public final class MageAssignmentHandler {

    private MageAssignmentHandler() {}

    public static boolean isMageToken(ItemStack stack) {
        return stack.is(ModItems.MAGE_TOKEN.get());
    }

    /** Whether {@code village} has a Mage Tower or a Barracks a mage can be hired at. */
    public static boolean hasHiringPlace(@Nullable VillageManager village) {
        return CaptainAssignmentHandler.barracksCount(village) > 0 || towerCount(village) > 0;
    }

    /** Valid Mage Towers in {@code village}. */
    public static int towerCount(@Nullable VillageManager village) {
        if (village == null) return 0;
        int count = 0;
        for (com.alex.test.village.building.Building building : village.getBuildings()) {
            if (building instanceof com.alex.test.village.building.MageTowerBuilding && building.isValid()) count++;
        }
        return count;
    }

    /**
     * Jobless → Mage. No starter kit: a mage casts bare-handed, its bolt needs
     * nothing, and it fetches reagents from storage once it learns spells that
     * burn them. Whatever was in its hand is put away.
     */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        villager.setProfession(Profession.MAGE);
        if (!NpcEntity.isDisplayOnly(villager.getMainHandItem())) {
            villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        return villager.getProfession() == Profession.MAGE;
    }
}
