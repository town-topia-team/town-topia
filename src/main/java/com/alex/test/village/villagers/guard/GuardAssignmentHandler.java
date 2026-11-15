package com.alex.test.village.villagers.guard;

import com.alex.test.ModItems;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Handles first-time assignment into the Guard profession. */
public final class GuardAssignmentHandler {

    private static final String STARTER_SWORD_GIVEN = "GuardStarterSwordGiven";

    private GuardAssignmentHandler() {}

    public static boolean isGuardToken(ItemStack stack) {
        return stack.is(ModItems.GUARD_TOKEN.get());
    }

    /** Assigns the Guard job and stores the starter wooden sword in the custom inventory. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        if (!ensureStarterSword(villager)) return false;
        villager.setProfession(Profession.GUARD);
        wakeAssignedGuard(villager);
        GuardSwordUtils.equipBestAllowedSword(villager, GuardSettings.of(villager).getAllowedSwords());
        return villager.getProfession() == Profession.GUARD;
    }

    static void wakeAssignedGuard(NpcEntity guard) {
        if (guard.isSleeping()) guard.stopSleeping();
        guard.setPose(Pose.STANDING);
        guard.setAnimation(VillagerAnimationState.IDLE);
        guard.getNavigation().stop();
    }

    /**
     * Gives a Guard its one-time starter wooden sword. This also covers guards
     * created by future commands/NBT rather than only by the token flow.
     */
    public static boolean ensureStarterSword(NpcEntity villager) {
        if (villager.getBrainData().getBoolean(STARTER_SWORD_GIVEN)) return true;
        if (hasWoodenSword(villager)) {
            villager.getBrainData().putBoolean(STARTER_SWORD_GIVEN, true);
            return true;
        }
        if (!GuardSettings.of(villager).isSwordTierAllowed(GuardSettings.SwordTier.WOODEN)) {
            return true;
        }
        if (!villager.getInventory().addItem(com.alex.test.village.tokens.VillageItemOwnership.issue(
                new ItemStack(Items.WOODEN_SWORD), villager))) return false;
        villager.getBrainData().putBoolean(STARTER_SWORD_GIVEN, true);
        return true;
    }

    private static boolean hasWoodenSword(NpcEntity villager) {
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = villager.getInventory().getSlot(i);
            if (!stack.isEmpty() && stack.is(Items.WOODEN_SWORD)) return true;
        }
        return false;
    }
}
