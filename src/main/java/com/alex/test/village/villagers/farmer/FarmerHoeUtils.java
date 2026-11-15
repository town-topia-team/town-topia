package com.alex.test.village.villagers.farmer;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import com.alex.test.village.villagers.tools.ToolSpeed;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

/** Shared hoe selection and durability rules for Farmer work and resupply. */
public final class FarmerHoeUtils {

    private FarmerHoeUtils() {}

    public static int findUsableHoeSlot(InventoryManager inventory, FarmerSettings settings) {
        return ToolSelectionHelper.findUsableToolSlot(
                inventory, ItemTags.HOES, settings.getAllowedHoes(),
                ToolSelectionHelper::isUsableByDefault);
    }

    /** The fastest allowed, usable hoe (ToolSpeed): the one the farmer works, wears and keeps. */
    public static int findBestUsableHoeSlot(InventoryManager inventory, FarmerSettings settings) {
        return ToolSelectionHelper.findBestToolSlot(
                inventory, ItemTags.HOES, settings.getAllowedHoes(),
                ToolSelectionHelper::isUsableByDefault, ToolSpeed::quality);
    }

    /** Applies one normal farming action of vanilla wear (Unbreaking included) and clears a broken hoe. */
    public static boolean damageHoeAfterUse(ServerLevel level, InventoryManager inventory, int slot) {
        return ToolSelectionHelper.wearTool(level, inventory, slot);
    }
}
