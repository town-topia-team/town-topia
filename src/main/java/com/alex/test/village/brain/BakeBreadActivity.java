package com.alex.test.village.brain;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Bake the storage room's wheat into bread when the larder has nothing ready
 * to eat.
 *
 * <p>Any villager can do this, not only the cook. A village whose farmer fills
 * the chest with wheat but has no cook (or a cook who is busy) used to starve
 * next to a full chest. The villager takes wheat from the storage room, bakes
 * at that room's crafting table, and keeps the bread. The eat flow then eats
 * it like any other carried food.
 *
 * <p>The wheat may be unbound, for example a player's harvest dropped into the
 * chest. The bread is always made through {@link VillageItemOwnership#produce},
 * so it comes off the table with the village's green ID, the same as the
 * cook's bread, and stacks with it.
 */
public final class BakeBreadActivity extends Activity {

    /** Loaves baked in one visit, enough to see a villager through a day or so. */
    private static final int MAX_LOAVES = 3;

    /** Same pace as the cook at its table. */
    private static final int TICKS_PER_LOAF = 40;

    private static final int WHEAT_PER_BREAD = CookActivity.WHEAT_PER_BREAD;

    /**
     * How long a hungry villager waits, where it stands, for a table another
     * villager holds. It has not taken any wheat yet; after this it gives up
     * and tries again after the usual fetch cooldown.
     */
    private static final int HUNGRY_WAIT_TICKS = 20 * 30;

    /** QUEUE: the table was taken at the start of the trip; wait where we are, before the wheat. */
    private enum Phase { QUEUE, FETCH, WAIT_FOR_TABLE, WALK_TO_TABLE, SETTLE, CRAFTING }

    /**
     * Filling the pocket (bedtime, chores, bard set) rather than eating now.
     * A top-up never queues for a table someone else holds: it gives up at
     * once, before walking anywhere or taking wheat. Twenty villagers used to
     * take wheat at bedtime and stand in the storage room waiting their turn.
     */
    private final boolean topUp;

    @Nullable private StorageBuilding storage;
    @Nullable private Phase phase;
    @Nullable private FetchFromStorageActivity fetch;
    @Nullable private CraftingStation station;
    @Nullable private BlockPos stand;
    private int craftTimer;
    private int loavesBaked;
    private ChestActivity.Failure failure = ChestActivity.Failure.NONE;

    public BakeBreadActivity() {
        this(false);
    }

    /** {@code topUp}: see {@link #topUp}. */
    public BakeBreadActivity(boolean topUp) {
        this.topUp = topUp;
    }

    /** Why baking failed, in the same terms as a food fetch. */
    public ChestActivity.Failure failure() { return failure; }

    // =========================================================================
    // Queries
    // =========================================================================

    /** Whether this villager could bake bread right now. */
    public static boolean canBake(Context ctx) {
        return bakery(ctx) != null;
    }

    /** Whether {@code storage} alone holds enough to bake a loaf: a table and wheat. */
    public static boolean canBakeIn(StorageBuilding storage, ServerLevel level) {
        return storage.isValid() && storage.hasCraftingTable()
                && storage.hasItem(level, new ItemStack(Items.WHEAT))
                && storage.countItem(level, Items.WHEAT) >= WHEAT_PER_BREAD;
    }

    /**
     * Nearest storage room with a crafting table and enough wheat, counting
     * what the villager already carries.
     */
    @Nullable
    private static StorageBuilding bakery(Context ctx) {
        boolean carriesEnough = carriedWheat(ctx) >= WHEAT_PER_BREAD;
        BlockPos npcPos = ctx.npc().blockPosition();
        StorageBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding s) || !s.isValid() || !s.hasCraftingTable()) continue;
            if (!carriesEnough && !canBakeIn(s, ctx.level())) continue;
            double d = s.getDoorPos().distSqr(npcPos);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    // =========================================================================
    // Activity
    // =========================================================================

    @Override
    protected void start(Context ctx) {
        storage = bakery(ctx);
        if (storage == null) {
            failure = ChestActivity.Failure.NO_STORAGE;
            return;
        }
        String where = "bake_bread  at storage " + storage.getDoorPos().toShortString()
                + " carrying " + carriedWheat(ctx) + " wheat";
        // The table is held from the start of the trip, so the next villager
        // sees it taken before anyone walks over or takes wheat. A free one of
        // the room's tables, when it has several.
        BlockPos table = CraftingStation.pick(ctx, storage.getCraftingTablePositions(), null);
        if (table != null) {
            station = new CraftingStation(table, HUNGRY_WAIT_TICKS);
            if (!station.tryClaim(ctx)) {
                if (topUp) {
                    diary(ctx, where + ": table busy, top-up skipped");
                    failure = ChestActivity.Failure.TABLE_BUSY;
                    phase = null;
                    return;
                }
                phase = Phase.QUEUE;
                diary(ctx, where + ": table busy, waiting here");
                return;
            }
        }
        startFetch(ctx);
        diary(ctx, where);
    }

    /** Wheat first if the pocket is short and the room has some; otherwise on to the table. */
    private void startFetch(Context ctx) {
        int missing = MAX_LOAVES * WHEAT_PER_BREAD - carriedWheat(ctx);
        if (missing > 0 && storage.hasItem(ctx.level(), new ItemStack(Items.WHEAT))) {
            fetch = FetchFromStorageActivity.forItemAtStorage(
                    new ItemStack(Items.WHEAT), missing, "fetch_wheat", storage.getDoorPos());
            phase = Phase.FETCH;
        } else {
            phase = Phase.WAIT_FOR_TABLE;
        }
    }

    @Override
    protected Status tick(Context ctx) {
        if (storage == null || phase == null) return Status.FAILED;

        switch (phase) {
            case QUEUE -> {
                // Another of the room's tables may have come free meanwhile.
                BlockPos free = CraftingStation.pick(ctx, storage.getCraftingTablePositions(), station.table());
                if (free != null && !free.equals(station.table())) station = new CraftingStation(free, HUNGRY_WAIT_TICKS);
                switch (station.acquire(ctx)) {
                    case MINE -> {
                        if (carriedWheat(ctx) < WHEAT_PER_BREAD
                                && !storage.hasItem(ctx.level(), new ItemStack(Items.WHEAT))) {
                            return fail(ctx, "wheat gone while waiting for table");
                        }
                        diary(ctx, "bake_bread  table free, going");
                        startFetch(ctx);
                    }
                    case GIVE_UP -> { return fail(ctx, "gave up waiting for table"); }
                    case WAIT -> { }
                }
            }
            case FETCH -> {
                Status status = fetch.tickOuter(ctx);
                if (status == Status.RUNNING) return Status.RUNNING;
                fetch.stop(ctx);
                ChestActivity.Failure why = fetch.failure();
                fetch = null;
                // A fetch that came back short still bakes whatever it covers.
                if (carriedWheat(ctx) < WHEAT_PER_BREAD) {
                    failure = why != ChestActivity.Failure.NONE ? why : ChestActivity.Failure.TRANSACTION;
                    return Status.FAILED;
                }
                phase = Phase.WAIT_FOR_TABLE;
            }
            case WAIT_FOR_TABLE -> {
                if (station == null) {
                    BlockPos table = CraftingStation.pick(ctx, storage.getCraftingTablePositions(), null);
                    if (table == null) return fail(ctx, "no crafting table");
                    station = new CraftingStation(table);
                } else {
                    // Not walked over yet: another table that came free will do.
                    BlockPos free = CraftingStation.pick(ctx, storage.getCraftingTablePositions(), station.table());
                    if (free != null && !free.equals(station.table())) station = new CraftingStation(free);
                }
                switch (station.acquire(ctx)) {
                    case MINE -> {
                        stand = station.standFor(ctx, storage);
                        if (stand == null) return fail(ctx, "no stand beside table");
                        resetMove();
                        phase = Phase.WALK_TO_TABLE;
                    }
                    case GIVE_UP -> { return fail(ctx, "gave up waiting for table"); }
                    case WAIT -> { }
                }
            }
            case WALK_TO_TABLE -> {
                if (moveTo(ctx, stand, VillagerModel.WALK_SPEED)) {
                    if (!isCloseTo(ctx.npc().blockPosition(), stand)) {
                        return fail(ctx, "could not reach table");
                    }
                    phase = Phase.SETTLE;
                }
            }
            case SETTLE -> {
                if (station.settle(ctx, stand)) {
                    int loaves = Math.min(MAX_LOAVES, carriedWheat(ctx) / WHEAT_PER_BREAD);
                    if (loaves <= 0) return fail(ctx, "wheat gone before baking");
                    craftTimer = loaves * TICKS_PER_LOAF;
                    ctx.npc().setAnimation(VillagerAnimationState.CRAFT_TABLE);
                    phase = Phase.CRAFTING;
                }
            }
            case CRAFTING -> {
                CraftingStation.face(ctx.npc(), station.table());
                craftTimer--;
                boolean more = craftTimer % TICKS_PER_LOAF != 0 || bakeOneLoaf(ctx);
                if (craftTimer <= 0 || !more) {
                    ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                    station.release(ctx);
                    diary(ctx, "bake_bread  baked " + loavesBaked + " bread");
                    if (loavesBaked > 0) return Status.DONE;
                    failure = ChestActivity.Failure.TRANSACTION;
                    return Status.FAILED;
                }
            }
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) {
        if (fetch != null) {
            fetch.stop(ctx);
            fetch = null;
        }
        if (station != null) station.release(ctx);
        if (phase == Phase.CRAFTING) ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public String name() {
        return "bake_bread";
    }

    // =========================================================================
    // Baking
    // =========================================================================

    /**
     * Turns three carried wheat into one village-bound loaf. Returns
     * {@code false}, leaving the wheat where it was, if that cannot be done.
     */
    private boolean bakeOneLoaf(Context ctx) {
        // Not the cook: vanilla bread, "Poor Man's Bread" (VillageFood).
        ItemStack bread = com.alex.test.village.tokens.VillageFood.villagerMade(Items.BREAD, 1, ctx.mgr());
        if (bread.isEmpty()) return false;

        InventoryManager inv = ctx.npc().getInventory();
        List<ItemStack> spent = takeWheat(inv, WHEAT_PER_BREAD);
        if (spent == null) return false;
        if (!inv.addItem(bread)) {
            for (ItemStack wheat : spent) {
                if (!inv.addItem(wheat)) ItemLossLog.drop(ctx, wheat, "bread baking could not return spent wheat");
            }
            return false;
        }

        loavesBaked++;
        ctx.level().playSound(null, station.table(), SoundEvents.VILLAGER_WORK_FARMER,
                SoundSource.NEUTRAL, 0.6f, 1.0f);
        return true;
    }

    /** Removes {@code amount} usable wheat, returning exactly what was taken, or nothing. */
    @Nullable
    private static List<ItemStack> takeWheat(InventoryManager inv, int amount) {
        int have = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (isUsableWheat(inv, stack)) have += stack.getCount();
        }
        if (have < amount) return null;

        List<ItemStack> taken = new ArrayList<>();
        int remaining = amount;
        for (int i = 0; i < InventoryManager.SIZE && remaining > 0; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!isUsableWheat(inv, stack)) continue;
            int take = Math.min(remaining, stack.getCount());
            taken.add(stack.copyWithCount(take));
            inv.reduceSlot(i, take);
            remaining -= take;
        }
        return taken;
    }

    private static int carriedWheat(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        int total = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (isUsableWheat(inv, stack)) total += stack.getCount();
        }
        return total;
    }

    private static boolean isUsableWheat(InventoryManager inv, ItemStack stack) {
        return stack.is(Items.WHEAT) && inv.mayUse(stack);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Status fail(Context ctx, String why) {
        diary(ctx, "bake_bread  failed: " + why);
        failure = ChestActivity.Failure.TRANSACTION;
        return Status.FAILED;
    }

    private static boolean isCloseTo(BlockPos npcPos, BlockPos target) {
        if (npcPos == null || target == null) return false;
        if (Math.abs(npcPos.getY() - target.getY()) > 1) return false;
        double dx = npcPos.getX() - target.getX();
        double dz = npcPos.getZ() - target.getZ();
        return dx * dx + dz * dz <= 4.0;
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }
}
