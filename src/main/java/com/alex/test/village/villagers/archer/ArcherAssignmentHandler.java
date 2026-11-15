package com.alex.test.village.villagers.archer;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Handles assignment into the Archer profession. */
public final class ArcherAssignmentHandler {

    /** Arrows handed over with the starter bow, so a new archer can shoot at all. */
    private static final int STARTER_ARROWS = 32;

    private ArcherAssignmentHandler() {}

    public static boolean isArcherToken(ItemStack stack) {
        return stack.is(ModItems.ARCHER_TOKEN.get());
    }

    /**
     * Jobless → Archer, with a bow and a quiver.
     *
     * <p>Mirrors the guard's starter sword. Without it a fresh archer stands
     * unarmed until the village happens to bank a bow, and a village that never
     * does leaves it permanently useless — the profession's whole point is
     * ranged defence, so it starts able to perform it.
     */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        if (!ensureStarterKit(villager)) return false;
        villager.setProfession(Profession.ARCHER);
        ArcherBowUtils.equipBow(villager);
        return villager.getProfession() == Profession.ARCHER;
    }

    private static boolean ensureStarterKit(NpcEntity villager) {
        InventoryManager inventory = villager.getInventory();
        if (!inventory.addItem(com.alex.test.village.tokens.VillageItemOwnership.issue(
                new ItemStack(Items.BOW), villager))) return false;
        // A bow with no arrows is the same as no bow; if the quiver will not fit
        // the assignment fails rather than producing an archer that cannot shoot.
        return inventory.addItem(com.alex.test.village.tokens.VillageItemOwnership.issue(
                new ItemStack(Items.ARROW, STARTER_ARROWS), villager));
    }
}
