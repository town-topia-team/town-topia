package com.alex.test.village.villagers.farmer;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import com.alex.test.village.villagers.tools.ToolSpeed;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

/** Shears farmers pick sweet berries and glow berries and cut cocoa pods with. */
public final class FarmerShearsUtils {

    public static final TagKey<Item> FARMER_SHEARS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "farmer_shears"));
    public static final Set<Item> ALLOWED_SHEARS = Set.of(Items.SHEARS);

    private FarmerShearsUtils() {}

    public static int findUsableShearsSlot(InventoryManager inventory) {
        return ToolSelectionHelper.findUsableToolSlot(inventory, FARMER_SHEARS,
                ALLOWED_SHEARS, ToolSelectionHelper::isUsableByDefault);
    }

    /** The fastest usable shears (ToolSpeed: Efficiency only): the pair the farmer picks with, wears and keeps. */
    public static int findBestUsableShearsSlot(InventoryManager inventory) {
        return ToolSelectionHelper.findBestToolSlot(inventory, FARMER_SHEARS,
                ALLOWED_SHEARS, ToolSelectionHelper::isUsableByDefault, ToolSpeed::quality);
    }

    /** One use of vanilla wear (Unbreaking included); clears broken shears. */
    public static boolean damageAfterUse(ServerLevel level, InventoryManager inventory, int slot) {
        return ToolSelectionHelper.wearTool(level, inventory, slot);
    }
}
