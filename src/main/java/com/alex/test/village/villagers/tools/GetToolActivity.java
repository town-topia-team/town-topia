package com.alex.test.village.villagers.tools;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.NavGraph;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.brain.CraftingStation;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.supplies.StorageSupplyRestocker;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Shared activity for acquiring or upgrading a profession tool.
 *
 * <p>The profession supplies only its tool tag, allowed item set, craft fallback,
 * and success callback. Walking to storage, chest interaction, crafting-table
 * animation, and upgrade swapping are shared here.
 */
public class GetToolActivity extends Activity {

    private enum Phase {
        WALK_TO_STORAGE, WAIT_FOR_TABLE, AT_CHEST, WALK_TO_TABLE, SETTLE_AT_TABLE, CRAFTING
    }
    private enum Mode { ACQUIRE, UPGRADE }
    private record AcquisitionPlan(
            StorageBuilding storage, boolean craftMode, boolean carriedWood) {}

    private final ProfessionToolRequirement requirement;
    private final Mode mode;
    private final StorageBuilding preferredStorage;

    private StorageBuilding storage;
    private BlockPos walkTarget;
    private BlockPos chestPos;
    private BlockPos craftingTablePos;
    /** Held from before the wood is taken until the tool is made. */
    private CraftingStation station;
    private Phase phase;
    private boolean craftMode;
    /** Crafting from wood the villager is already carrying, not from the chest. */
    private boolean carriedWood;
    /**
     * Wood taken from storage for a craft that has not finished yet. It only
     * becomes the tool when crafting completes; any other ending puts it back.
     */
    private final List<StorageBuilding.WithdrawnStack> heldWood = new ArrayList<>();
    private Set<Item> allowedTools;

    private boolean chestOpened;
    private boolean transacted;
    private boolean transactSuccess;
    private int busyTimer;
    private int craftTimer;

    public GetToolActivity(ProfessionToolRequirement requirement) {
        this(requirement, Mode.ACQUIRE, null);
    }

    /** Trade the working tool for a better one in {@code storage}; see {@link ToolUpgradeHelper}. */
    public static GetToolActivity forUpgrade(
            ProfessionToolRequirement requirement,
            StorageBuilding storage) {
        return new GetToolActivity(requirement, Mode.UPGRADE, storage);
    }

    protected GetToolActivity(
            ProfessionToolRequirement requirement,
            StorageBuilding preferredStorage) {
        this(requirement, Mode.UPGRADE, preferredStorage);
    }

    private GetToolActivity(
            ProfessionToolRequirement requirement,
            Mode mode,
            StorageBuilding preferredStorage) {
        this.requirement = requirement;
        this.mode = mode;
        this.preferredStorage = preferredStorage;
    }

    @Override
    protected void start(Context ctx) {
        allowedTools = requirement.allowedTools(ctx);
        if (allowedTools.isEmpty()) return;

        if (mode == Mode.UPGRADE) {
            if (preferredStorage == null || !hasBetterToolAt(ctx, preferredStorage)) {
                return;
            }
            storage = preferredStorage;
            craftMode = false;
        } else {
            AcquisitionPlan plan = findAcquisitionPlan(ctx, requirement, allowedTools);
            if (plan == null) return;
            storage = plan.storage();
            craftMode = plan.craftMode();
            carriedWood = plan.carriedWood();
        }

        NavGraph g = ctx.mgr().getGraph();
        if (g == null) return;

        BlockPos npcPos = ctx.npc().blockPosition();
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : storage.getFunctionalBlocks()) {
            BlockPos target = storage.getWalkTargetForContainer(pos, g, npcPos);
            if (target == null) continue;
            double d = target.distSqr(npcPos);
            if (d < bestDist) {
                bestDist = d;
                chestPos = pos;
                walkTarget = target;
            }
        }
        if (chestPos == null || walkTarget == null) return;

        // Claim the tool before walking, so other villagers skip it instead of
        // racing over for it. A craft from this room's wood claims the wood the
        // same way; carried wood needs no claim, and the table is held later.
        if (!craftMode && !reserveTool(ctx)) return;
        if (craftMode && !carriedWood
                && !storage.claimWood(ctx.level(), requirement.woodCost(), ctx.model().npcId)) return;

        busyTimer = requirement.chestBusyTicks();
        phase = Phase.WALK_TO_STORAGE;
    }

    @Override
    protected Status tick(Context ctx) {
        if (storage == null || chestPos == null || phase == null) return Status.FAILED;

        switch (phase) {
            case WALK_TO_STORAGE -> {
                if (moveTo(ctx, walkTarget, requirement.storageWalkSpeed())) {
                    if (!isCloseToWalkTarget(ctx.npc().blockPosition(), walkTarget)) {
                        return Status.FAILED;
                    }
                    if (craftMode) {
                        // Take the table before touching the wood, so a villager
                        // that gives up waiting has not spent anything.
                        craftingTablePos = CraftingStation.pick(ctx, storage.getCraftingTablePositions(), null);
                        if (craftingTablePos == null) return Status.FAILED;
                        station = new CraftingStation(craftingTablePos);
                        phase = Phase.WAIT_FOR_TABLE;
                    } else {
                        openChest(ctx);
                        phase = Phase.AT_CHEST;
                    }
                }
            }
            case WAIT_FOR_TABLE -> {
                // Not walked over yet: another of the room's tables that came free will do.
                BlockPos free = CraftingStation.pick(ctx, storage.getCraftingTablePositions(), craftingTablePos);
                if (free != null && !free.equals(craftingTablePos)) {
                    craftingTablePos = free;
                    station = new CraftingStation(free);
                }
                switch (station.acquire(ctx)) {
                    case MINE -> {
                        openChest(ctx);
                        phase = Phase.AT_CHEST;
                    }
                    case GIVE_UP -> { return Status.FAILED; }
                    case WAIT -> { }
                }
            }
            case AT_CHEST -> {
                ctx.npc().getLookControl().setLookAt(
                        chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5,
                        30f, 30f);
                if (!transacted && busyTimer <= 10) {
                    transacted = true;
                    transactSuccess = doTransaction(ctx);
                    if (transactSuccess && !craftMode) {
                        StorageSupplyRestocker.restockAfterStorageVisit(
                                ctx, storage, requirement);
                    }
                }
                if (--busyTimer <= 0) {
                    closeChest(ctx);
                    if (!transactSuccess) return Status.FAILED;
                    if (!craftMode) {
                        requirement.onAcquireSuccess(ctx);
                        return Status.DONE;
                    }
                    walkTarget = station.standFor(ctx, storage);
                    if (walkTarget == null) return Status.FAILED;
                    resetMove();
                    phase = Phase.WALK_TO_TABLE;
                }
            }
            case WALK_TO_TABLE -> {
                if (moveTo(ctx, walkTarget, VillagerModel.WALK_SPEED)) {
                    if (!isCloseToWalkTarget(ctx.npc().blockPosition(), walkTarget)) {
                        return Status.FAILED;
                    }
                    phase = Phase.SETTLE_AT_TABLE;
                }
            }
            case SETTLE_AT_TABLE -> {
                if (station.settle(ctx, walkTarget)) {
                    craftTimer = requirement.craftTicks();
                    ctx.npc().setAnimation(VillagerAnimationState.CRAFT_TABLE);
                    phase = Phase.CRAFTING;
                }
            }
            case CRAFTING -> {
                CraftingStation.face(ctx.npc(), craftingTablePos);
                if (--craftTimer <= 0) {
                    ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                    station.release(ctx);
                    // Any failure here leaves the wood unspent: stop() puts
                    // storage wood back, and carried wood was never taken.
                    if (!requirement.canCraft(ctx)) return Status.FAILED;
                    ItemStack crafted = VillageItemOwnership.produce(
                            requirement.craftedTool(ctx), ctx.mgr());
                    if (crafted.isEmpty()) return Status.FAILED;
                    if (carriedWood) {
                        if (!spendCarriedWood(ctx, requirement.woodCost())) return Status.FAILED;
                    } else {
                        if (heldWoodCount() < requirement.woodCost()) return Status.FAILED;
                        heldWood.clear(); // the wood became the tool
                    }
                    if (!ctx.npc().getInventory().addItem(crafted)) {
                        // The pack may have changed while the villager was crafting.
                        // Keep the finished tool in the village instead of losing it.
                        storage.depositItems(ctx.level(), crafted);
                        ItemLossLog.drop(ctx, crafted, "crafted tool could not be carried or stored");
                        return Status.FAILED;
                    }
                    requirement.onAcquireSuccess(ctx);
                    return Status.DONE;
                }
            }
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) {
        if (storage != null) {
            storage.releaseToolReservations(ctx.model().npcId);
            storage.releaseWoodClaim(ctx.model().npcId);
        }
        refundHeldWood(ctx);
        closeChest(ctx);
        if (station != null) station.release(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
        // A full pack can also prevent acquisition; do not report that as a
        // village tool shortage.
        if (phase == null && mode != Mode.UPGRADE
                && ctx.npc().getInventory().hasEmptySlot()) {
            ctx.brain().remember(MemoryTopic.NO_TOOLS, ctx.level());
        }
    }

    @Override
    public String name() {
        return requirement.activityName();
    }

    /**
     * Whether this requirement can currently be supplied from storage or
     * crafted. Work-entry checks use this exact lookup so that a missing tool
     * cannot prevent the activity responsible for obtaining it from starting.
     */
    public static boolean canAcquire(Context ctx, ProfessionToolRequirement requirement) {
        Set<Item> allowed = requirement.allowedTools(ctx);
        return !allowed.isEmpty() && findAcquisitionPlan(ctx, requirement, allowed) != null;
    }

    /** Whether the villager already carries a usable tool for this requirement. */
    public static boolean hasUsableTool(Context ctx, ProfessionToolRequirement requirement) {
        return ToolSelectionHelper.findUsableToolSlot(ctx.npc().getInventory(),
                requirement.toolTag(), requirement.allowedTools(ctx),
                requirement.usablePredicate(ctx)) >= 0;
    }

    private static AcquisitionPlan findAcquisitionPlan(
            Context ctx,
            ProfessionToolRequirement requirement,
            Set<Item> allowedTools) {
        StorageBuilding toolStorage = nearestStorageWith(ctx, s -> {
            ItemStack tool = s.peekToolOfTypes(ctx.level(), requirement.toolTag(), allowedTools,
                    stack -> requirement.canPlaceAcquiredTool(ctx, stack),
                    requirement.quality(ctx), ctx.model().npcId);
            return !tool.isEmpty();
        });
        if (toolStorage != null) return new AcquisitionPlan(toolStorage, false, false);
        // A stocked tool that cannot fit should prompt a deposit trip, not
        // make the villager craft a weaker replacement over and over.
        if (nearestStorageWith(ctx, s -> !s.peekToolOfTypes(ctx.level(), requirement.toolTag(),
                allowedTools, ctx.model().npcId).isEmpty()) != null) return null;
        if (!requirement.canCraft(ctx)) return null;

        // Wood in hand counts. Requiring a lumberjack to bank its carried logs
        // before replacing a broken axe deadlocks it before the deposit step.
        boolean inHand = carriedWoodCount(ctx) >= requirement.woodCost();
        if (!canCraftIntoPack(ctx, requirement, inHand)) return null;
        // The full cost, all wood types together: "any wood at all" sent the
        // villager to a room holding one plank, where the craft failed every time.
        // Wood another crafter has claimed does not count.
        StorageBuilding craftStorage = nearestStorageWith(ctx,
                s -> s.hasCraftingTable()
                        && (inHand || s.freeWood(ctx.level(), ctx.model().npcId) >= requirement.woodCost()));
        return craftStorage == null
                ? null
                : new AcquisitionPlan(craftStorage, true, inHand);
    }

    private boolean doTransaction(Context ctx) {
        if (mode == Mode.UPGRADE) return swapToBetterTool(ctx, storage);

        if (!craftMode) {
            ItemStack available = storage.peekToolOfTypes(
                    ctx.level(), requirement.toolTag(), allowedTools,
                    stack -> requirement.canPlaceAcquiredTool(ctx, stack),
                    requirement.quality(ctx), ctx.model().npcId);
            if (available.isEmpty()) return false;
            ItemStack tool = storage.withdrawToolOfTypes(
                    ctx.level(), requirement.toolTag(), allowedTools,
                    stack -> requirement.canPlaceAcquiredTool(ctx, stack),
                    requirement.quality(ctx), ctx.model().npcId);
            if (tool.isEmpty()) return false;
            if (!requirement.placeAcquiredTool(ctx, tool)) {
                storage.depositItems(ctx.level(), tool);
                ItemLossLog.drop(ctx, tool, "withdrawn tool could not be placed or returned");
                return false;
            }
            // Ammunition: keep taking until the visit's share is in the pack.
            int more = requirement.piecesPerVisit(ctx) - 1;
            for (int i = 0; i < more; i++) {
                ItemStack extra = storage.withdrawToolOfTypes(
                        ctx.level(), requirement.toolTag(), allowedTools,
                        stack -> requirement.canPlaceAcquiredTool(ctx, stack),
                        requirement.quality(ctx), ctx.model().npcId);
                if (extra.isEmpty()) break;
                if (!requirement.placeAcquiredTool(ctx, extra)) {
                    storage.depositItems(ctx.level(), extra);
                    break;
                }
            }
            return true;
        }

        if (!canCraftIntoPack(ctx, requirement, carriedWood)) return false;
        // Carried wood is spent only when the tool is finished.
        if (carriedWood) return carriedWoodCount(ctx) >= requirement.woodCost();

        heldWood.addAll(storage.withdrawWood(ctx.level(), requirement.woodCost(), ctx.model().npcId));
        if (heldWoodCount() >= requirement.woodCost()) return true;
        refundHeldWood(ctx);
        return false;
    }

    private int heldWoodCount() {
        int total = 0;
        for (StorageBuilding.WithdrawnStack held : heldWood) total += held.stack().getCount();
        return total;
    }

    /** Puts unspent craft wood back: its own chest, then the room, then the pack. */
    private void refundHeldWood(Context ctx) {
        for (StorageBuilding.WithdrawnStack held : heldWood) {
            ItemStack wood = held.stack();
            if (storage != null && !storage.returnToChest(ctx.level(), held.chestPos(), wood)) {
                storage.depositItems(ctx.level(), wood);
            }
            if (!wood.isEmpty() && !ctx.npc().getInventory().addItem(wood)) {
                ItemLossLog.drop(ctx, wood, "unspent craft wood could not be returned");
            }
        }
        heldWood.clear();
    }

    private static boolean canCraftIntoPack(
            Context ctx, ProfessionToolRequirement requirement, boolean useCarriedWood) {
        ItemStack crafted = VillageItemOwnership.produce(requirement.craftedTool(ctx), ctx.mgr());
        if (crafted.isEmpty()) return false;
        if (requirement.canPlaceAcquiredTool(ctx, crafted)) return true;
        return useCarriedWood && carriedWoodWillFreeSlot(ctx, requirement.woodCost());
    }

    private static boolean carriedWoodWillFreeSlot(Context ctx, int amount) {
        if (amount <= 0) return false;
        InventoryManager inventory = ctx.npc().getInventory();
        int remaining = amount;
        boolean freesSlot = false;
        for (int slot = 0; slot < InventoryManager.SIZE && remaining > 0; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (!inventory.mayUse(stack) || !isWood(stack)) continue;
            int taken = Math.min(remaining, stack.getCount());
            freesSlot |= taken == stack.getCount();
            remaining -= taken;
        }
        return remaining == 0 && freesSlot;
    }

    /** Logs and planks the villager has on it. */
    private static int carriedWoodCount(Context ctx) {
        int total = 0;
        InventoryManager inventory = ctx.npc().getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            if (inventory.mayUse(inventory.getSlot(slot)) && isWood(inventory.getSlot(slot)))
                total += inventory.getSlot(slot).getCount();
        }
        return total;
    }

    /** Takes {@code amount} wood out of the villager's own pack, or nothing at all. */
    private static boolean spendCarriedWood(Context ctx, int amount) {
        if (carriedWoodCount(ctx) < amount) return false;
        InventoryManager inventory = ctx.npc().getInventory();
        int remaining = amount;
        for (int slot = 0; slot < InventoryManager.SIZE && remaining > 0; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (!inventory.mayUse(stack) || !isWood(stack)) continue;
            int taken = Math.min(remaining, stack.getCount());
            inventory.reduceSlot(slot, taken);
            remaining -= taken;
        }
        return remaining == 0;
    }

    private static boolean isWood(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS));
    }

    // Upgrade trips: a pack tool by default. A tool worn elsewhere (guard armour,
    // GetArmorActivity) overrides all three together.

    /** Whether {@code storage} holds something better than what the villager works with. */
    protected boolean hasBetterToolAt(Context ctx, StorageBuilding storage) {
        return ToolUpgradeHelper.hasBetterTool(ctx, storage, requirement);
    }

    /** Claims the better tool in {@code storage} for this trip; false when none is free. */
    protected boolean reserveBetterToolAt(Context ctx, StorageBuilding storage) {
        return ToolUpgradeHelper.reserveBetterTool(ctx, storage, requirement);
    }

    /** Trades the working tool for the better one at the chest; true when the villager now has it. */
    protected boolean swapToBetterTool(Context ctx, StorageBuilding storage) {
        return ToolUpgradeHelper.upgradeAt(ctx, storage, requirement);
    }

    /** Promises this trip's tool to the villager; see {@link StorageBuilding#reserveToolOfTypes}. */
    private boolean reserveTool(Context ctx) {
        if (mode == Mode.UPGRADE) return reserveBetterToolAt(ctx, storage);
        return storage.reserveToolOfTypes(ctx.level(), requirement.toolTag(), allowedTools,
                stack -> requirement.canPlaceAcquiredTool(ctx, stack),
                requirement.quality(ctx), ctx.model().npcId);
    }

    private void openChest(Context ctx) {
        if (!chestOpened && chestPos != null) {
            chestOpened = true;
            com.alex.test.village.building.StorageContainers.setOpen(ctx.level(), chestPos, true);
        }
    }

    private void closeChest(Context ctx) {
        if (chestOpened && chestPos != null) {
            chestOpened = false;
            com.alex.test.village.building.StorageContainers.setOpen(ctx.level(), chestPos, false);
        }
    }

    private static boolean isCloseToWalkTarget(BlockPos npcPos, BlockPos target) {
        if (npcPos == null || target == null) return false;
        if (Math.abs(npcPos.getY() - target.getY()) > 1) return false;
        double dx = npcPos.getX() - target.getX();
        double dz = npcPos.getZ() - target.getZ();
        return dx * dx + dz * dz <= 4.0;
    }

    private static StorageBuilding nearestStorageWith(Context ctx, Predicate<StorageBuilding> filter) {
        BlockPos npcPos = ctx.npc().blockPosition();
        StorageBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding s) || !s.isValid()) continue;
            if (s.getFunctionalBlocks().isEmpty() || !filter.test(s)) continue;
            double d = s.getDoorPos().distSqr(npcPos);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }
}
