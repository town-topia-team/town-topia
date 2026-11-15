package com.alex.test.village.villagers.tradesman;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The merchant stall's barrels, used as a sell box: goods with the village's
 * ID that a player puts in them are sold to the visiting merchant by the
 * tradesman, on top of the surplus it sells from storage on its own, and the
 * emeralds come back into the barrel. While the merchant visits, the village
 * goods in there it does not want that day, and the odd units short of a half
 * stack, go on to storage.
 *
 * <p>Only goods marked with the village's ID count, the same rule the merchant
 * applies when a player sells to it directly, so the box cannot turn items
 * brought in from outside into village emeralds.
 */
public final class SellBox {
    private SellBox() {}

    /** The village's first valid stall, the one the merchant visits. */
    @Nullable
    public static MerchantStallBuilding stall(VillageManager village) {
        for (Building building : village.getBuildings()) {
            if (building instanceof MerchantStallBuilding stall && stall.isValid()) return stall;
        }
        return null;
    }

    /** The stall's barrels that are loaded and still barrels. */
    public static List<BlockPos> barrels(ServerLevel level, MerchantStallBuilding stall) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : stall.getFunctionalBlocks()) {
            if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.BARREL)
                    && level.getBlockEntity(pos) instanceof Container) result.add(pos.immutable());
        }
        return result;
    }

    @Nullable
    public static Container container(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof Container container ? container : null;
    }

    /** Whether {@code pos} is a barrel of {@code village}'s merchant stall. */
    public static boolean isSellBox(ServerLevel level, VillageManager village, BlockPos pos) {
        MerchantStallBuilding stall = stall(village);
        return stall != null && stall.getFunctionalBlocks().contains(pos)
                && level.getBlockState(pos).is(Blocks.BARREL);
    }

    /** Village goods waiting in all of the stall's barrels, per item. */
    public static Map<Item, Integer> goods(ServerLevel level, VillageManager village, MerchantStallBuilding stall) {
        Map<Item, Integer> counts = new HashMap<>();
        for (BlockPos pos : barrels(level, stall)) {
            Container box = container(level, pos);
            if (box == null) continue;
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack stack = box.getItem(slot);
                if (VillageItemOwnership.mayTrade(stack, village))
                    counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Items in the stall's barrels without the village's ID (brought in from
     * outside): the tradesman never sells or moves them. Emeralds are left
     * out; they are the box's takings either way.
     */
    public static int foreignCount(ServerLevel level, VillageManager village, MerchantStallBuilding stall) {
        int total = 0;
        for (BlockPos pos : barrels(level, stall)) {
            Container box = container(level, pos);
            if (box == null) continue;
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack stack = box.getItem(slot);
                if (!stack.isEmpty() && !stack.is(Items.EMERALD) && !VillageItemOwnership.mayTrade(stack, village))
                    total += stack.getCount();
            }
        }
        return total;
    }

    /** All village goods waiting in the stall's barrels. */
    public static int goodsCount(ServerLevel level, VillageManager village, MerchantStallBuilding stall) {
        int total = 0;
        for (int count : goods(level, village, stall).values()) total += count;
        return total;
    }

    /**
     * Moves up to {@code max} of {@code item} (village goods only) from the box
     * into {@code pack}. Never takes more than the pack can hold.
     *
     * @return how many moved
     */
    public static int take(Container box, Item item, int max, VillageManager village, InventoryManager pack) {
        int moved = 0;
        for (int slot = 0; slot < box.getContainerSize() && moved < max; slot++) {
            ItemStack stack = box.getItem(slot);
            if (!stack.is(item) || !VillageItemOwnership.mayTrade(stack, village)) continue;
            int amount = Math.min(max - moved, stack.getCount());
            amount = Math.min(amount, pack.capacityFor(stack));
            if (amount <= 0) break;
            ItemStack taken = stack.copyWithCount(amount);
            if (!pack.addItem(taken)) break;
            stack.shrink(amount);
            if (stack.isEmpty()) box.setItem(slot, ItemStack.EMPTY);
            moved += amount;
        }
        if (moved > 0) box.setChanged();
        return moved;
    }

    /**
     * Puts as much of {@code stack} into the box as fits, topping up matching
     * stacks first. Shrinks {@code stack} by what went in.
     */
    public static void insert(Container box, ItemStack stack) {
        boolean changed = false;
        for (int slot = 0; slot < box.getContainerSize() && !stack.isEmpty(); slot++) {
            ItemStack existing = box.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) continue;
            int room = Math.min(existing.getMaxStackSize(), box.getMaxStackSize()) - existing.getCount();
            if (room <= 0) continue;
            int moved = Math.min(room, stack.getCount());
            existing.grow(moved);
            stack.shrink(moved);
            changed = true;
        }
        for (int slot = 0; slot < box.getContainerSize() && !stack.isEmpty(); slot++) {
            if (!box.getItem(slot).isEmpty() || !box.canPlaceItem(slot, stack)) continue;
            int moved = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), box.getMaxStackSize()));
            box.setItem(slot, stack.copyWithCount(moved));
            stack.shrink(moved);
            changed = true;
        }
        if (changed) box.setChanged();
    }
}
