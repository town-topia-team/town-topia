package com.alex.test.village.villagers.enchanter;

import com.alex.test.village.brain.ChestActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageContainers;
import com.alex.test.village.building.StorageInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches one particular enchanted book from storage: this exact book, what it
 * stores and all.
 *
 * <p>The generic fetch matches by item, and every enchanted book is the same
 * item, so it took whichever came first in the chest, a player's ordered book
 * as soon as the one wanted. This one picks chests, checks the lid and takes
 * the book by its components.
 */
final class FetchExactBookActivity extends ChestActivity {

    private final ItemStack book;
    private final String activityName;

    FetchExactBookActivity(ItemStack book, String activityName) {
        this.book = book.copyWithCount(1);
        this.activityName = activityName;
    }

    @Override
    protected StorageBuilding selectStorage(Context ctx) {
        long now = ctx.level().getGameTime();
        return nearestStorage(ctx, storage -> exactChests(ctx, storage).stream()
                .anyMatch(pos -> !ctx.brain().isChestUnreachable(pos, now)));
    }

    @Override
    protected List<BlockPos> candidateChests(Context ctx, StorageBuilding selectedStorage) {
        return exactChests(ctx, selectedStorage);
    }

    @Override
    protected boolean isSelectedChestStillValid(Context ctx) {
        return storage != null && chestPos != null && slotOf(ctx, chestPos) >= 0
                && ctx.npc().getInventory().hasEmptySlot();
    }

    @Override
    protected boolean transact(Context ctx) {
        if (storage == null || chestPos == null) return false;
        StorageInventory chest = StorageContainers.inventory(ctx.level(), chestPos);
        int slot = slotOf(ctx, chestPos);
        if (chest == null || slot < 0) return false;
        ItemStack taken = chest.take(slot, 1);
        if (taken.isEmpty()) return false;
        storage.invalidateContentIndex();
        if (!ctx.npc().getInventory().addItem(taken)) {
            storage.depositItems(ctx.level(), taken);
            return false;
        }
        ctx.brain().diary().log(String.format("%s %s  fetched %s from chest %s",
                VillagerDiary.ts(ctx.level()), name(), EnchantBooks.describe(book), chestPos.toShortString()));
        return true;
    }

    @Override
    protected String targetDebugName(Context ctx) {
        return EnchantBooks.describe(book);
    }

    private List<BlockPos> exactChests(Context ctx, StorageBuilding storage) {
        List<BlockPos> chests = new ArrayList<>();
        if (!storage.isValid()) return chests;
        for (BlockPos pos : storage.getFunctionalBlocks()) {
            if (slotOf(ctx, storage, pos) >= 0) chests.add(pos);
        }
        return chests;
    }

    private int slotOf(Context ctx, BlockPos pos) {
        return storage == null ? -1 : slotOf(ctx, storage, pos);
    }

    private int slotOf(Context ctx, StorageBuilding storage, BlockPos pos) {
        StorageInventory chest = StorageContainers.inventory(ctx.level(), pos);
        if (chest == null) return -1;
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.peek(i);
            if (ItemStack.isSameItemSameComponents(stack, book) && storage.mayUse(ctx.level(), stack)) return i;
        }
        return -1;
    }

    @Override
    public String name() {
        return activityName;
    }
}
