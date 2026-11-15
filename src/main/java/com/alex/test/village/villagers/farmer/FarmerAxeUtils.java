package com.alex.test.village.villagers.farmer;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import com.alex.test.village.villagers.tools.ToolSpeed;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;

/** The axe a farmer harvests melons and pumpkins with: selection and wear. */
public final class FarmerAxeUtils {

    private FarmerAxeUtils() {}

    public static int findUsableAxeSlot(InventoryManager inventory, FarmerSettings settings) {
        return ToolSelectionHelper.findUsableToolSlot(
                inventory, ItemTags.AXES, settings.getAllowedAxes(),
                ToolSelectionHelper::isUsableByDefault);
    }

    /** The fastest allowed, usable axe (ToolSpeed): the one the farmer cuts fruit with and keeps. */
    public static int findBestUsableAxeSlot(InventoryManager inventory, FarmerSettings settings) {
        return ToolSelectionHelper.findBestToolSlot(
                inventory, ItemTags.AXES, settings.getAllowedAxes(),
                ToolSelectionHelper::isUsableByDefault, ToolSpeed::quality);
    }

    /** One use of vanilla wear (Unbreaking included); clears a broken axe. */
    public static boolean damageAfterUse(ServerLevel level, InventoryManager inventory, int slot) {
        return ToolSelectionHelper.wearTool(level, inventory, slot);
    }
}
