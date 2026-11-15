package com.alex.test.village.villagers.farmer.behavior;

import com.alex.test.village.brain.ChestActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.farmer.FarmerCauldrons;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts the empty bucket a refill borrowed back into a storage chest, and
 * nothing else. The chest theatre is {@link ChestActivity}'s.
 */
final class ReturnBucketActivity extends ChestActivity {

    @Override
    protected StorageBuilding selectStorage(Context ctx) {
        ItemStack bucket = carried(ctx);
        if (bucket.isEmpty()) return null;
        long now = ctx.level().getGameTime();
        return nearestStorage(ctx, storage -> storage.getFunctionalBlocks().stream()
                .anyMatch(pos -> !ctx.brain().isChestUnreachable(pos, now)
                        && storage.chestCanAccept(ctx.level(), pos, bucket)));
    }

    @Override
    protected List<BlockPos> candidateChests(Context ctx, StorageBuilding selectedStorage) {
        ItemStack bucket = carried(ctx);
        List<BlockPos> chests = new ArrayList<>();
        if (bucket.isEmpty()) return chests;
        for (BlockPos pos : selectedStorage.getFunctionalBlocks()) {
            if (selectedStorage.chestCanAccept(ctx.level(), pos, bucket)) chests.add(pos);
        }
        return chests;
    }

    @Override
    protected boolean isSelectedChestStillValid(Context ctx) {
        ItemStack bucket = carried(ctx);
        return storage != null && chestPos != null && !bucket.isEmpty()
                && storage.chestCanAccept(ctx.level(), chestPos, bucket);
    }

    @Override
    protected boolean transact(Context ctx) {
        int slot = FarmerCauldrons.slotOf(ctx.npc().getInventory(), Items.BUCKET);
        if (slot < 0) return false;
        ItemStack stack = ctx.npc().getInventory().getSlot(slot);
        ItemStack one = stack.split(1);
        if (!storage.returnToChest(ctx.level(), chestPos, one)) {
            // The chest took none of it after all: back into the pack.
            stack.grow(one.getCount());
            return false;
        }
        if (stack.isEmpty()) ctx.npc().getInventory().setSlot(slot, ItemStack.EMPTY);
        ctx.brain().diary().log(String.format("%s REFILL returned bucket to chest %s",
                VillagerDiary.ts(ctx.level()), chestPos.toShortString()));
        return true;
    }

    @Override
    protected String targetDebugName(Context ctx) {
        return "a chest for the bucket";
    }

    private static ItemStack carried(Context ctx) {
        int slot = FarmerCauldrons.slotOf(ctx.npc().getInventory(), Items.BUCKET);
        return slot < 0 ? ItemStack.EMPTY : ctx.npc().getInventory().getSlot(slot).copyWithCount(1);
    }

    @Override
    public String name() {
        return "return_bucket";
    }
}
