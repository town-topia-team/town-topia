package com.alex.test.village.villagers.lumberjack;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import com.alex.test.village.villagers.tools.ToolSpeed;
import net.minecraft.tags.ItemTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

public final class AxeUtils {

    /** The weakest axe that fells a big 2x2 tree is iron. */
    private static final int BIG_TREE_RANK = ToolSelectionHelper.rank(Items.IRON_AXE);
    /**
     * Added to {@link #quality} for an axe that fells big trees, past anything
     * speed and enchantments can add (ToolSpeed.quality stays under 15 million).
     */
    public static final long BIG_TREE_QUALITY = 1_000_000_000L;

    private AxeUtils() {}

    /** Higher value means a preferred axe for lumberjack work. */
    public static int axeRank(Item axe) {
        return ToolSelectionHelper.rank(axe);
    }

    /**
     * Returns the slot index of a usable axe in the inventory (i.e. an axe
     * that is not yet broken), or -1 if none.
     */
    public static int findUsableAxeSlot(InventoryManager inventory) {
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (inventory.mayUse(stack) && isUsableAxe(stack)) return i;
        }
        return -1;
    }

    /** Like {@link #findUsableAxeSlot(InventoryManager)} but restricted to axe types in {@code allowed}. */
    public static int findUsableAxeSlot(InventoryManager inventory, Set<Item> allowed) {
        return ToolSelectionHelper.findUsableToolSlot(
                inventory, ItemTags.AXES, allowed, AxeUtils::isUsableAxe);
    }

    /** Vanilla durability: an axe is usable until its last point is spent. */
    public static boolean isUsableAxe(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ItemTags.AXES)) return false;
        return !stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage();
    }

    /**
     * The best allowed, usable axe by {@link #quality}: one that fells big 2x2
     * trees first, then the fastest; on a tie the one with the most uses left.
     * The axe the lumberjack chops with, wears down and keeps when banking.
     */
    public static int findChoppingAxeSlot(InventoryManager inventory, Set<Item> allowed) {
        return ToolSelectionHelper.findBestToolSlot(
                inventory, ItemTags.AXES, allowed, AxeUtils::isUsableAxe, AxeUtils::quality);
    }

    /** Iron, diamond and netherite axes fell big 2x2 trees; wooden, stone and golden ones are too weak. */
    public static boolean canFellBigTrees(Item axe) {
        return axeRank(axe) >= BIG_TREE_RANK;
    }

    public static boolean canFellBigTrees(ItemStack axe) {
        return !axe.isEmpty() && canFellBigTrees(axe.getItem());
    }

    /**
     * How the lumberjack ranks its axes: one that fells big 2x2 trees first,
     * then chop speed (ToolSpeed, Efficiency included). A fast stone axe still
     * beats a slow wooden one, but never an iron one, so an iron axe in storage
     * is always worth the swap.
     */
    public static long quality(ItemStack axe) {
        return (canFellBigTrees(axe) ? BIG_TREE_QUALITY : 0L) + ToolSpeed.quality(axe);
    }

    /** Whether the axe this lumberjack chops with fells big 2x2 trees. */
    public static boolean hasBigTreeAxe(NpcEntity npc) {
        InventoryManager inventory = npc.getInventory();
        int slot = findChoppingAxeSlot(inventory, LumberjackSettings.of(npc).getAllowedAxes());
        return slot >= 0 && canFellBigTrees(inventory.getSlot(slot));
    }

    /**
     * Wears the axe in {@code slot} by one point for one log the lumberjack
     * actually received -- the vanilla cost of breaking a log, with Unbreaking
     * applied the vanilla way. Leaves, and logs lost to the drop chance, cost
     * nothing. Clears the slot if the axe breaks.
     *
     * @return true if the axe survived, false if it broke or was not there
     */
    public static boolean damageAxeForLog(ServerLevel level, InventoryManager inventory, int slot) {
        ItemStack stack = inventory.getSlot(slot);
        if (!inventory.mayUse(stack) || !stack.is(ItemTags.AXES)) return false;
        if (!stack.isDamageableItem()) return true;
        int damage = com.alex.test.village.tokens.PlayerGear.wear(level, stack, 1);
        if (damage <= 0) return true;
        int newDamage = stack.getDamageValue() + damage;
        if (newDamage >= stack.getMaxDamage()) {
            inventory.setSlot(slot, ItemStack.EMPTY);
            return false;
        }
        stack.setDamageValue(newDamage);
        return true;
    }
}
