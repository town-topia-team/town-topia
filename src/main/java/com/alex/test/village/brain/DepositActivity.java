package com.alex.test.village.brain;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.supplies.BucketNeeds;
import com.alex.test.village.villagers.supplies.CarryReserve;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import com.alex.test.village.villagers.supplies.StorageSupplyRestocker;
import com.alex.test.village.villagers.tools.ProfessionDepositRules;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import com.alex.test.village.villagers.farmer.FarmerInventory;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.BiPredicate;

/**
 * Carry the inventory's haul to the storage room and drop it into a chest
 * (with the {@link ChestActivity} open-lid theatre).
 *
 * <p>Everything is deposited except a small personal food reserve
 * ({@value #KEEP_FOOD} items) and profession tools the villager needs in the
 * field.
 */
public final class DepositActivity extends ChestActivity {

    /** Food items the villager keeps for itself instead of depositing. */
    private static final int KEEP_FOOD = ProfessionSupplyRules.FOOD_TARGET;

    /** Only these items go in; null for the whole haul. */
    @Nullable private final Predicate<ItemStack> only;

    public DepositActivity() {
        this(null);
    }

    private DepositActivity(@Nullable Predicate<ItemStack> only) {
        this.only = only;
    }

    /**
     * A trip that banks only the items {@code only} accepts, of those the
     * villager would bank anyway: an enchanter taking a finished book in while
     * keeping the lapis it fetched for the next one.
     */
    public static DepositActivity only(Predicate<ItemStack> only) {
        return new DepositActivity(java.util.Objects.requireNonNull(only));
    }

    /** {@link #depositableStacks}, narrowed to {@link #only}. */
    private List<ItemStack> stacksFor(Context ctx) {
        List<ItemStack> candidates = depositableStacks(ctx);
        if (only != null) candidates.removeIf(stack -> !only.test(stack));
        return candidates;
    }

    @Override
    protected StorageBuilding selectStorage(Context ctx) {
        List<ItemStack> candidates = stacksFor(ctx);
        return nearestStorage(ctx, storage -> canAcceptAnyReachable(ctx, storage, candidates));
    }

    @Override
    protected List<BlockPos> candidateChests(Context ctx, StorageBuilding selectedStorage) {
        List<ItemStack> candidates = stacksFor(ctx);
        List<BlockPos> chests = new ArrayList<>();
        for (BlockPos pos : selectedStorage.getFunctionalBlocks()) {
            for (ItemStack candidate : candidates) {
                if (selectedStorage.chestCanAccept(ctx.level(), pos, candidate)) {
                    chests.add(pos);
                    break;
                }
            }
        }
        return chests;
    }

    @Override
    protected boolean isSelectedChestStillValid(Context ctx) {
        if (storage == null || chestPos == null) return false;
        for (ItemStack candidate : stacksFor(ctx)) {
            if (storage.chestCanAccept(ctx.level(), chestPos, candidate)) return true;
        }
        return false;
    }

    @Override
    protected boolean transact(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        int[] kept = keptPerSlot(ctx);
        int itemsDeposited = 0;

        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            int keep = kept[i];
            if (stack.isEmpty() || keep >= stack.getCount()) continue;
            if (only != null && !only.test(stack)) continue;
            Item item = stack.getItem();
            int moved;
            if (keep > 0) {
                ItemStack surplus = stack.copyWithCount(stack.getCount() - keep);
                int offered = surplus.getCount();
                storage.depositItems(ctx.level(), surplus);
                moved = offered - surplus.getCount();
                if (moved > 0) stack.shrink(moved);
                if (stack.isEmpty()) inv.setSlot(i, ItemStack.EMPTY);
            } else {
                // depositItems shrinks the stack to whatever would not fit, and the
                // slot holds that same live stack, so a partial deposit leaves the
                // remainder in place on its own. Only a complete one clears the slot.
                int carried = stack.getCount();
                boolean allFit = storage.depositItems(ctx.level(), stack);
                moved = carried - stack.getCount();
                if (allFit) inv.setSlot(i, ItemStack.EMPTY);
            }
            itemsDeposited += moved;
            // Today's best, for showing off to visitors in the evening.
            if (moved > 0) com.alex.test.village.visitors.MadeToday.record(ctx.npc(), item, moved, ctx.level());
            // A maker banking its work: the Stock tab counts orders as delivered.
            if (moved > 0 && ctx.npc().getProfession() == StockReserves.producerOf(item)) {
                StockReserves.get(ctx.level().getServer()).onDelivered(ctx.mgr(), ctx.level(), item, moved);
            }
            // A farmer banking planting items it fetched and never planted waits before fetching them again.
            if (moved > 0 && ctx.npc().getProfession() == Profession.FARMER
                    && ctx.mgr().farmers().onDeposited(ctx.model().npcId, item, ctx.level().getGameTime())) {
                ctx.brain().diary().log(String.format(
                        "%s deposit  put back %s fetched to plant, none planted: not fetched again for 5 min",
                        VillagerDiary.ts(ctx.level()), item.getDescriptionId()));
            }
        }

        // At the chest anyway: own crops kept as lunch go in where storage has
        // something tastier, which the top-up below then takes.
        itemsDeposited += StorageSupplyRestocker.handInOwnCropsForBetterFood(ctx, storage);
        int itemsRestocked = StorageSupplyRestocker.restockAfterStorageVisit(ctx, storage);

        if (itemsDeposited > 0) {
            ctx.brain().diary().log(String.format("%s deposit  deposited %d item(s) to storage",
                    VillagerDiary.ts(ctx.level()), itemsDeposited));
        }
        return itemsDeposited > 0 || itemsRestocked > 0;
    }

    @Override
    public String name() { return "deposit"; }

    // -------------------------------------------------------------------------

    /** Items this villager would deposit right now (used by brain scoring). */
    public static int countDepositable(Context ctx) {
        List<ItemStack> candidates = depositableStacks(ctx);
        if (candidates.isEmpty()
                || nearestStorage(ctx, storage -> canAcceptAnyReachable(ctx, storage, candidates)) == null) {
            return 0;
        }

        int count = 0;
        for (ItemStack candidate : candidates) count += candidate.getCount();
        return count;
    }

    /** Inventory surplus even when storage is unavailable or full. */
    public static int countPotentiallyDepositable(Context ctx) {
        int count = 0;
        for (ItemStack candidate : depositableStacks(ctx)) count += candidate.getCount();
        return count;
    }

    /** Exact stack portions allowed to leave the villager's inventory. */
    private static List<ItemStack> depositableStacks(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        int[] kept = keptPerSlot(ctx);
        List<ItemStack> candidates = new ArrayList<>();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inv.getSlot(slot);
            if (stack.isEmpty() || kept[slot] >= stack.getCount()) continue;
            candidates.add(stack.copyWithCount(stack.getCount() - kept[slot]));
        }
        return candidates;
    }

    /**
     * How many items of each slot stay in the pack; the first rule that fits
     * decides. Items the villager may not use stay. An archer keeps its
     * quiver, a mage its reagents. A farmer keeps one reserve of each
     * planting item it replants (a bounded reserve per crop, so the first
     * stack cannot crowd out carrots or potatoes). Working tools and
     * profession supplies stay.
     * Food tops up a personal reserve of {@value #KEEP_FOOD}, counted in
     * items, so only the part of a stack that fills it stays.
     *
     * <p>Never out of the villager's own product, though: a butcher
     * "reserving" the raw meat it just harvested starves the cook's supply
     * chain. A farmer is the exception. Its own crops fill whatever its lunch
     * still lacks, since banking every carrot and then taking carrots back as
     * lunch sent it to storage again and again to swap the same food. At the
     * chest it still trades them for tastier food when storage has some
     * ({@link StorageSupplyRestocker#handInOwnCropsForBetterFood}).
     *
     * <p>Public for the new away calculator's deposits ({@code economy/away}), so they keep the same.
     */
    public static int[] keptPerSlot(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        Profession profession = ctx.npc().getProfession();
        int[] kept = new int[InventoryManager.SIZE];
        int foodKept = 0;
        int seedFoodKept = 0;
        int arrowsKept = 0;
        int reagentsKept = 0;
        int leadsKept = 0;
        int rancherBucketsLeft = -1;
        Map<Item, Integer> farmerPlantingItemsKept = new HashMap<>();
        List<Integer> ownCropSlots = new ArrayList<>();
        BiPredicate<Integer, ItemStack> professionKeeps = keepRule(ctx);
        // A tradesman's sell box goods and the emeralds owed to the barrel stay
        // for the walk back to the barrel; banked, they would be lost to it.
        Map<Item, Integer> sellBoxLeft = profession == Profession.TRADESMAN
                ? new HashMap<>(com.alex.test.village.villagers.tradesman.behavior.TradesmanActivity
                        .sellBoxHoldings(ctx.npc(), ctx.level().getGameTime()))
                : Map.of();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inv.getSlot(slot);
            if (stack.isEmpty()) continue;
            if (!VillageItemOwnership.mayUse(stack, ctx.mgr())) {
                kept[slot] = stack.getCount();
                continue;
            }
            int forBox = sellBoxLeft.getOrDefault(stack.getItem(), 0);
            if (forBox > 0 && VillageItemOwnership.mayTrade(stack, ctx.mgr())) {
                kept[slot] = Math.min(forBox, stack.getCount());
                sellBoxLeft.put(stack.getItem(), forBox - kept[slot]);
                continue;
            }
            if (profession == Profession.ARCHER && stack.is(ItemTags.ARROWS)) {
                kept[slot] = CarryReserve.toKeep(stack.getCount(), arrowsKept,
                        com.alex.test.village.villagers.archer.ArcherBowUtils.quiverSize(ctx.npc()));
                arrowsKept += kept[slot];
                continue;
            }
            // A mage keeps its pouch of spell reagents, as an archer keeps its quiver.
            if (profession == Profession.MAGE
                    && com.alex.test.village.villagers.mage.MageReagents.isReagent(stack)) {
                kept[slot] = CarryReserve.toKeep(stack.getCount(), reagentsKept,
                        com.alex.test.village.villagers.mage.MageReagents.CARRY);
                reagentsKept += kept[slot];
                continue;
            }
            if (profession == Profession.FARMER && FarmerInventory.isKeptSupply(ctx.npc(), stack)) {
                kept[slot] = FarmerInventory.plantingReserveToKeep(
                        stack.getItem(), stack.getCount(), farmerPlantingItemsKept);
                // Carrots and potatoes kept as seed are eaten too: part of the lunch.
                if (VillageItemOwnership.villagerFood(stack, ctx.npc()) != null) seedFoodKept += kept[slot];
                continue;
            }
            // One lead to walk animals on; spares go back for the next villager.
            if (stack.is(Items.LEAD) && com.alex.test.village.villagers.butcher.lead.LeadSupply.usesLead(profession)) {
                kept[slot] = CarryReserve.toKeep(stack.getCount(), leadsKept,
                        com.alex.test.village.villagers.butcher.lead.LeadSupply.KEEP);
                leadsKept += kept[slot];
                continue;
            }
            // A rancher keeps only the empty buckets still wanted for milk; the
            // rest go back for the wash cauldrons and the next cake.
            if (profession == Profession.RANCHER && stack.is(Items.BUCKET)) {
                if (rancherBucketsLeft < 0) rancherBucketsLeft = BucketNeeds.rancherBucketsToKeep(ctx.mgr(), ctx.level());
                kept[slot] = Math.min(stack.getCount(), rancherBucketsLeft);
                rancherBucketsLeft -= kept[slot];
                continue;
            }
            if (professionKeeps.test(slot, stack)) {
                kept[slot] = stack.getCount();
                continue;
            }
            if (VillageItemOwnership.villagerFood(stack, ctx.npc()) != null) {
                if (!ProfessionSupplyRules.isProfessionProduct(ctx.npc(), stack)) {
                    kept[slot] = CarryReserve.toKeep(stack.getCount(), foodKept, KEEP_FOOD);
                    foodKept += kept[slot];
                } else if (profession == Profession.FARMER) {
                    ownCropSlots.add(slot);
                }
            }
        }
        // Own crops last: other food the farmer chose as lunch stays first.
        int lunchRoom = KEEP_FOOD - foodKept - seedFoodKept;
        int ownCropsKept = 0;
        for (int slot : ownCropSlots) {
            kept[slot] = CarryReserve.toKeep(inv.getSlot(slot).getCount(), ownCropsKept, lunchRoom);
            ownCropsKept += kept[slot];
        }
        return kept;
    }

    /**
     * {@link ProfessionDepositRules#shouldKeep}, except that a cook keeps only
     * the ingredients of a dish the shelves still want, and fuel only while it
     * has something to burn it for (see {@link CookActivity#depositKeepRule}).
     * That needs the village, which the per-stack rule has no access to, and
     * it looks through storage, so it is built once per pass rather than once
     * per slot.
     */
    private static BiPredicate<Integer, ItemStack> keepRule(Context ctx) {
        Set<Integer> workingTools = ProfessionDepositRules.preferredToolSlots(ctx);
        Predicate<ItemStack> otherItems = ctx.npc().getProfession() == Profession.COOK
                ? CookActivity.depositKeepRule(ctx)
                : stack -> ProfessionDepositRules.shouldKeep(ctx.npc(), stack);
        return (slot, stack) -> ToolSelectionHelper.isProfessionTool(stack)
                ? workingTools.contains(slot) : otherItems.test(stack);
    }

    private static boolean canAcceptAnyReachable(Context ctx, StorageBuilding storage,
                                                 List<ItemStack> candidates) {
        long now = ctx.level().getGameTime();
        for (BlockPos pos : storage.getFunctionalBlocks()) {
            if (ctx.brain().isChestUnreachable(pos, now)) continue;
            for (ItemStack candidate : candidates) {
                if (storage.chestCanAccept(ctx.level(), pos, candidate)) return true;
            }
        }
        return false;
    }

}
