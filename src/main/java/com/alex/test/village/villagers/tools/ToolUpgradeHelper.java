package com.alex.test.village.villagers.tools;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.function.ToLongFunction;

/**
 * Shared tool upgrades. A storage tool is an upgrade when it beats the working
 * tool on {@link ProfessionToolRequirement#quality}; the old tool always goes
 * back to storage, and wear never counts, so a nearly broken tool is used until
 * it breaks rather than traded for a fresh copy.
 */
public final class ToolUpgradeHelper {

    private ToolUpgradeHelper() {}

    public static long newestToolStorageChangeId(Activity.Context ctx) {
        long newest = 0L;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding s) || !s.isValid()) continue;
            newest = Math.max(newest, s.getToolInventoryChangeId(ctx.level()));
        }
        return newest;
    }

    /** The slot of the tool the villager works with: the best by quality, most uses left on a tie. */
    public static int workingToolSlot(Activity.Context ctx, ProfessionToolRequirement requirement) {
        return ToolSelectionHelper.findBestToolSlot(ctx.npc().getInventory(), requirement.toolTag(),
                requirement.allowedTools(ctx), requirement.usablePredicate(ctx), requirement.quality(ctx));
    }

    /**
     * Whether {@code storage} holds a better tool than the one the villager works
     * with, not counting tools already promised to other villagers.
     */
    public static boolean hasBetterTool(
            Activity.Context ctx, StorageBuilding storage, ProfessionToolRequirement requirement) {
        int slot = workingToolSlot(ctx, requirement);
        if (slot < 0 || !storage.isValid() || storage.getFunctionalBlocks().isEmpty()) return false;
        ToLongFunction<ItemStack> quality = requirement.quality(ctx);
        return storage.hasToolBetterThan(ctx.level(), requirement.toolTag(),
                requirement.allowedTools(ctx), quality,
                quality.applyAsLong(ctx.npc().getInventory().getSlot(slot)), ctx.model().npcId);
    }

    /** Claims the better tool in {@code storage} for this villager's trip; false when none is free. */
    public static boolean reserveBetterTool(
            Activity.Context ctx, StorageBuilding storage, ProfessionToolRequirement requirement) {
        int slot = workingToolSlot(ctx, requirement);
        if (slot < 0 || !storage.isValid()) return false;
        ToLongFunction<ItemStack> quality = requirement.quality(ctx);
        return storage.reserveBetterTool(ctx.level(), requirement.toolTag(),
                requirement.allowedTools(ctx), quality,
                quality.applyAsLong(ctx.npc().getInventory().getSlot(slot)), ctx.model().npcId);
    }

    public static StorageBuilding nearestStorageWithBetterTool(
            Activity.Context ctx, ProfessionToolRequirement requirement) {
        BlockPos npcPos = ctx.npc().blockPosition();
        StorageBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding s) || !hasBetterTool(ctx, s, requirement)) continue;
            double d = s.getDoorPos().distSqr(npcPos);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    /**
     * Trades the working tool for the best better one in {@code storage}. The old
     * tool goes into storage, never into the pack, and any other tool of the same
     * kind the villager carries goes back with it.
     *
     * @return true when the villager now works with a better tool
     */
    public static boolean upgradeAt(
            Activity.Context ctx, StorageBuilding storage, ProfessionToolRequirement requirement) {
        InventoryManager inventory = ctx.npc().getInventory();
        int slot = workingToolSlot(ctx, requirement);
        if (slot < 0) return false;
        ItemStack oldTool = inventory.getSlot(slot).copy();
        ItemStack better = storage.swapForBetterTool(ctx.level(), requirement.toolTag(),
                requirement.allowedTools(ctx), requirement.quality(ctx), oldTool, ctx.model().npcId);
        if (better.isEmpty()) return false;
        if (oldTool.isEmpty()) {
            inventory.setSlot(slot, better);
        } else if (!inventory.addItem(better)) {
            // Storage took neither the old tool nor the new one back.
            ItemLossLog.drop(ctx, better, "upgraded tool could not be stored or carried");
            return false;
        }
        bankSpareTools(ctx, storage, requirement);
        return true;
    }

    /** Every tool of this kind except the working one goes into {@code storage} if it fits. */
    private static void bankSpareTools(
            Activity.Context ctx, StorageBuilding storage, ProfessionToolRequirement requirement) {
        InventoryManager inventory = ctx.npc().getInventory();
        int keep = workingToolSlot(ctx, requirement);
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (i == keep || stack.isEmpty() || !stack.is(requirement.toolTag())
                    || !inventory.mayUse(stack)) continue;
            ItemStack spare = stack.copy();
            storage.depositItems(ctx.level(), spare);
            inventory.setSlot(i, spare.isEmpty() ? ItemStack.EMPTY : spare);
        }
    }
}
