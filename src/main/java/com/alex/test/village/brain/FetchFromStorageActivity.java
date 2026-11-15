package com.alex.test.village.brain;

import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.supplies.StorageSupplyRestocker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;

/**
 * Generic storage-withdraw activity.
 *
 * <p>This replaces hardcoded per-job chest lookup. A job only needs to describe
 * what it wants: either a {@link StorageBuilding.ResourceType} category such as
 * FOOD/WOOD/SEEDS, or one exact {@link ItemStack}. This activity then:
 * <ol>
 *   <li>finds a valid storage building that contains that target,</li>
 *   <li>uses the storage content index to find only matching chests,</li>
 *   <li>walks to the closest reachable matching chest,</li>
 *   <li>directly rechecks that same chest before opening it,</li>
 *   <li>withdraws only from the visually opened chest.</li>
 * </ol>
 *
 * <p>So future jobs do not need to duplicate chest/path/cache logic. They can
 * create this activity with {@link #forResource(StorageBuilding.ResourceType, int, String)}
 * or {@link #forItem(ItemStack, int, String)}.
 */
public class FetchFromStorageActivity extends ChestActivity {

    private final StorageBuilding.ResourceType resourceType;
    private final ItemStack wantedItem;
    private final int amount;
    private final String activityName;
    private final String debugTarget;
    private final BlockPos requiredStorageDoor;

    /**
     * Create a fetch activity for a broad storage resource category.
     * Example: FOOD, WOOD, SEEDS, ANY.
     */
    public static FetchFromStorageActivity forResource(StorageBuilding.ResourceType type, int amount, String activityName) {
        return new FetchFromStorageActivity(type, ItemStack.EMPTY, amount, activityName, null);
    }

    /**
     * Create a fetch activity for one exact item stack, including components.
     * The count on {@code wanted} is ignored; use {@code amount} for the amount.
     */
    public static FetchFromStorageActivity forItem(ItemStack wanted, int amount, String activityName) {
        return new FetchFromStorageActivity(null, wanted, amount, activityName, null);
    }

    /** Fetches an exact item only from the storage selected by the planner. */
    public static FetchFromStorageActivity forItemAtStorage(
            ItemStack wanted, int amount, String activityName, BlockPos storageDoor) {
        return new FetchFromStorageActivity(null, wanted, amount, activityName,
                Objects.requireNonNull(storageDoor, "storageDoor").immutable());
    }

    /** Constructor for subclasses such as {@link FetchFoodActivity}. */
    protected FetchFromStorageActivity(StorageBuilding.ResourceType type, int amount, String activityName) {
        this(type, ItemStack.EMPTY, amount, activityName, null);
    }

    /** Constructor for exact-item subclasses. */
    protected FetchFromStorageActivity(ItemStack wanted, int amount, String activityName) {
        this(null, wanted, amount, activityName, null);
    }

    private FetchFromStorageActivity(StorageBuilding.ResourceType type, ItemStack wanted, int amount,
                                     String activityName, BlockPos requiredStorageDoor) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");
        if (type == null && (wanted == null || wanted.isEmpty())) {
            throw new IllegalArgumentException("fetch target must be a resource type or a non-empty item stack");
        }

        this.resourceType = type;
        this.wantedItem = wanted == null || wanted.isEmpty() ? ItemStack.EMPTY : wanted.copyWithCount(1);
        this.amount = amount;
        this.activityName = Objects.requireNonNull(activityName, "activityName");
        this.requiredStorageDoor = requiredStorageDoor;
        this.debugTarget = type != null
                ? type.name()
                : this.wantedItem.getItem().getDescriptionId();
    }

    @Override
    protected StorageBuilding selectStorage(Context ctx) {
        return nearestStorage(ctx, s -> {
            if (requiredStorageDoor != null && !requiredStorageDoor.equals(s.getDoorPos())) return false;
            List<BlockPos> chests = resourceType != null
                    ? s.findChestsWithResource(ctx.level(), resourceType)
                    : s.findChestsWithItem(ctx.level(), wantedItem);
            long now = ctx.level().getGameTime();
            return chests.stream().anyMatch(pos -> !ctx.brain().isChestUnreachable(pos, now)
                    && spaceForChest(ctx, s, pos) > 0);
        });
    }

    @Override
    protected List<BlockPos> candidateChests(Context ctx, StorageBuilding selectedStorage) {
        List<BlockPos> chests = resourceType != null
                ? selectedStorage.findChestsWithResource(ctx.level(), resourceType)
                : selectedStorage.findChestsWithItem(ctx.level(), wantedItem);
        return chests.stream().filter(pos -> spaceForChest(ctx, selectedStorage, pos) > 0).toList();
    }

    @Override
    protected boolean isSelectedChestStillValid(Context ctx) {
        if (storage == null || chestPos == null) return false;
        return spaceForChest(ctx, storage, chestPos) > 0;
    }

    @Override
    protected String targetDebugName(Context ctx) {
        return debugTarget;
    }

    @Override
    protected boolean transact(Context ctx) {
        int toTake = Math.min(amountToTake(ctx), spaceForChest(ctx, storage, chestPos));
        if (toTake <= 0) return false;
        ItemStack taken;
        if (resourceType != null) {
            taken = storage.withdrawItemsFromChest(ctx.level(), chestPos, resourceType, toTake,
                    item -> unreserved(ctx, item) <= 0);
        } else {
            taken = storage.withdrawItemFromChest(ctx.level(), chestPos, wantedItem, toTake);
        }

        if (taken.isEmpty()) return false;
        if (!VillageItemOwnership.mayUse(taken, ctx.mgr())) {
            storage.depositItems(ctx.level(), taken);
            ItemLossLog.destroyed(ctx, taken, name() + " put-back of an item it may not use found no room (chest "
                    + chestPos.toShortString() + ")");
            return false;
        }

        if (!ctx.npc().getInventory().addItem(taken)) {
            // Inventory full — put it back rather than deleting it.
            storage.depositItems(ctx.level(), taken);
            ItemLossLog.destroyed(ctx, taken, name() + " put-back after a full pack found no room (chest "
                    + chestPos.toShortString() + ")");
            return false;
        }

        ctx.brain().diary().log(String.format("%s %s  fetched %dx %s from chest %s",
                VillagerDiary.ts(ctx.level()), name(), taken.getCount(),
                taken.getItem().getDescriptionId(), chestPos));
        StorageSupplyRestocker.restockAfterStorageVisit(ctx, storage);
        return true;
    }

    /** How many to withdraw at the chest; subclasses may size it from what is already carried. */
    protected int amountToTake(Context ctx) {
        return amount;
    }

    /** Capacity for the stack this chest would actually withdraw first, within the stock reserve. */
    private int spaceForChest(Context ctx, StorageBuilding selectedStorage, BlockPos pos) {
        ItemStack first = resourceType != null
                ? selectedStorage.peekResourceFromChest(ctx.level(), pos, resourceType,
                        item -> unreserved(ctx, item) <= 0)
                : selectedStorage.peekItemFromChest(ctx.level(), pos, wantedItem);
        if (first.isEmpty()) return 0;
        return Math.min(ctx.npc().getInventory().capacityFor(first), unreserved(ctx, first.getItem()));
    }

    /**
     * How many of {@code item} this villager may take: what the village's stock reserve leaves
     * free. The cook and the blacksmith may spend what is held for their own Stock tab rows,
     * never what the player keeps.
     */
    private static int unreserved(Context ctx, Item item) {
        return StockReserves.get(ctx.level().getServer()).free(ctx.mgr(), ctx.level(), item,
                ctx.npc().getProfession());
    }

    /**
     * Whether {@code storage} holds food this villager may take: food by the content
     * index and, as a fetch would judge it, not all of it held back by the Stock tab.
     * Asking only the index sent a starving sleeper out of bed every few ticks all
     * night when the only food was what the player keeps there (10-19).
     */
    static boolean hasFoodItMayTake(Context ctx, StorageBuilding storage) {
        for (BlockPos pos : storage.findChestsWithResource(ctx.level(), StorageBuilding.ResourceType.FOOD)) {
            if (!storage.peekResourceFromChest(ctx.level(), pos, StorageBuilding.ResourceType.FOOD,
                    item -> unreserved(ctx, item) <= 0).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String name() {
        return activityName;
    }
}
