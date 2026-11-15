package com.alex.test.village.villagers.miner.behavior;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.NavGraph;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.CraftingStation;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.miner.MinerBalance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * The miner makes its own torches from the village's logs, at a storage room
 * that has a furnace and a crafting table.
 *
 * <p>Vanilla recipes throughout. A full batch takes six logs: four are burned
 * into four charcoal, and two are cut into eight planks -- three burn as the
 * furnace's fuel (a plank smelts one and a half items), two become four
 * sticks, and three are left over. Four charcoal and four sticks make sixteen
 * torches. A miner already carrying coal skips the furnace and only cuts one
 * log into sticks; the torches are then crafted in the mine as before.
 *
 * <p>Smelting and crafting both get faster with skill, from vanilla speed at
 * level 1 to twice that at level 100 ({@link MinerBalance#torchMakingSpeed}).
 *
 * <p>This replaced fetching charcoal from storage. That fetch could never
 * succeed when the only charcoal sat in a chest the miner had written off as
 * unreachable, and the miner asked again on every decision -- the "no valid
 * storage for charcoal" loop.
 */
public final class MakeTorchesActivity extends Activity {

    private enum Phase { WALK_TO_CHEST, AT_CHEST, WALK_TO_FURNACE, SMELTING, WALK_TO_TABLE, CRAFTING }

    /** Logs burned into charcoal per batch. */
    public static final int CHARCOAL_PER_BATCH = 4;
    /** Logs cut into planks for fuel and sticks per full batch. */
    private static final int PLANK_LOGS_FULL = 2;
    /** Logs cut when only sticks are needed. */
    private static final int PLANK_LOGS_STICKS_ONLY = 1;
    private static final int PLANKS_PER_LOG = 4;
    /** Vanilla: a plank burns for 300 ticks, one and a half smelts. */
    private static final double SMELTS_PER_PLANK = 1.5;
    private static final int STICKS_PER_TWO_PLANKS = 4;
    private static final int TORCHES_PER_CRAFT = 4;
    private static final int CHEST_BUSY_TICKS = 20;
    private static final double CHEST_REACH_SQ = 36.0;
    private static final int PARTICLE_INTERVAL = 10;

    /** True when the miner has coal already and only needs sticks. */
    private final boolean sticksOnly;

    private StorageBuilding storage;
    private Phase phase;
    private BlockPos chestPos;
    private BlockPos walkTarget;
    private CraftingStation furnace;
    private CraftingStation table;
    private int timer;
    private boolean furnaceLit;

    public MakeTorchesActivity(boolean sticksOnly) {
        this.sticksOnly = sticksOnly;
    }

    // =========================================================================
    // Planning
    // =========================================================================

    private static int logsNeeded(boolean sticksOnly) {
        return sticksOnly ? PLANK_LOGS_STICKS_ONLY : CHARCOAL_PER_BATCH + PLANK_LOGS_FULL;
    }

    /** Whether some storage room can host a batch: table, furnace if needed, and the logs. */
    public static boolean canRun(Activity.Context ctx, boolean sticksOnly) {
        return findStorage(ctx, sticksOnly) != null;
    }

    @Nullable
    private static StorageBuilding findStorage(Activity.Context ctx, boolean sticksOnly) {
        int missing = Math.max(0, logsNeeded(sticksOnly) - carriedLogs(ctx));
        BlockPos npcPos = ctx.npc().blockPosition();
        StorageBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            if (!storage.hasCraftingTable()) continue;
            if (!sticksOnly && !storage.hasFurnace()) continue;
            if (missing > 0 && storage.countMatching(ctx.level(),
                    s -> s.is(ItemTags.LOGS_THAT_BURN)) < missing) continue;
            double d = storage.getDoorPos().distSqr(npcPos);
            if (d < bestDist) {
                bestDist = d;
                best = storage;
            }
        }
        return best;
    }

    @Override
    protected void start(Context ctx) {
        storage = findStorage(ctx, sticksOnly);
        if (storage == null) return;
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return;

        if (carriedLogs(ctx) >= logsNeeded(sticksOnly)) {
            beginFurnaceOrTable(ctx);
            return;
        }
        BlockPos npcPos = ctx.npc().blockPosition();
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : storage.getFunctionalBlocks()) {
            BlockPos target = storage.getWalkTargetForContainer(pos, graph, npcPos);
            if (target == null) continue;
            double d = target.distSqr(npcPos);
            if (d < bestDist) {
                bestDist = d;
                chestPos = pos;
                walkTarget = target;
            }
        }
        if (walkTarget != null) phase = Phase.WALK_TO_CHEST;
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    @Override
    protected Status tick(Context ctx) {
        if (storage == null || phase == null) return Status.FAILED;
        switch (phase) {
            case WALK_TO_CHEST -> {
                if (moveTo(ctx, walkTarget, VillagerModel.WALK_SPEED)) {
                    timer = CHEST_BUSY_TICKS;
                    openChest(ctx, true);
                    phase = Phase.AT_CHEST;
                }
            }
            case AT_CHEST -> {
                CraftingStation.face(ctx.npc(), chestPos);
                if (--timer > 0) return Status.RUNNING;
                openChest(ctx, false);
                takeLogs(ctx);
                if (carriedLogs(ctx) < logsNeeded(sticksOnly)) {
                    diary(ctx, "TORCH not enough logs in storage for a batch");
                    return Status.FAILED;
                }
                if (!beginFurnaceOrTable(ctx)) return Status.FAILED;
            }
            case WALK_TO_FURNACE -> {
                if (!moveTo(ctx, walkTarget, VillagerModel.WALK_SPEED)) return Status.RUNNING;
                switch (furnace.acquire(ctx)) {
                    case WAIT -> { return Status.RUNNING; }
                    case GIVE_UP -> { return Status.FAILED; }
                    case MINE -> { }
                }
                if (!furnace.settle(ctx, walkTarget)) return Status.RUNNING;
                timer = MinerBalance.smeltTicks(CHARCOAL_PER_BATCH, ctx.brain().minerSkillLevel());
                setFurnaceLit(ctx, true);
                ctx.npc().setAnimation(VillagerAnimationState.CRAFTING);
                diary(ctx, "TORCH burning " + CHARCOAL_PER_BATCH + " logs into charcoal ("
                        + timer + " ticks)");
                phase = Phase.SMELTING;
            }
            case SMELTING -> {
                CraftingStation.face(ctx.npc(), furnace.table());
                ctx.npc().setAnimation(VillagerAnimationState.CRAFTING);
                if (timer % PARTICLE_INTERVAL == 0) furnaceEffects(ctx);
                if (--timer > 0) return Status.RUNNING;
                setFurnaceLit(ctx, false);
                furnace.release(ctx);
                if (!smeltCharcoal(ctx)) return Status.FAILED;
                if (!walkToTable(ctx)) return Status.FAILED;
            }
            case WALK_TO_TABLE -> {
                if (!moveTo(ctx, walkTarget, VillagerModel.WALK_SPEED)) return Status.RUNNING;
                switch (table.acquire(ctx)) {
                    case WAIT -> { return Status.RUNNING; }
                    case GIVE_UP -> { return Status.FAILED; }
                    case MINE -> { }
                }
                if (!table.settle(ctx, walkTarget)) return Status.RUNNING;
                // Planks, sticks, and (with charcoal) torches: one job each.
                int jobs = sticksOnly ? 2 : 3;
                timer = jobs * MinerBalance.craftTicks(ctx.brain().minerSkillLevel());
                ctx.npc().setAnimation(VillagerAnimationState.CRAFT_TABLE);
                phase = Phase.CRAFTING;
            }
            case CRAFTING -> {
                CraftingStation.face(ctx.npc(), table.table());
                ctx.npc().setAnimation(VillagerAnimationState.CRAFT_TABLE);
                if (--timer > 0) return Status.RUNNING;
                ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                table.release(ctx);
                return craftAtTable(ctx) ? Status.DONE : Status.FAILED;
            }
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) {
        openChest(ctx, false);
        setFurnaceLit(ctx, false);
        if (furnace != null) furnace.release(ctx);
        if (table != null) table.release(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public String name() {
        return "make_torches";
    }

    // =========================================================================
    // Steps
    // =========================================================================

    /** Next stop after the logs are in hand: the furnace, or straight to the table. */
    private boolean beginFurnaceOrTable(Context ctx) {
        if (sticksOnly) return walkToTable(ctx);
        // A free one of the room's furnaces, when it has several.
        BlockPos furnacePos = CraftingStation.pick(ctx, storage.getFurnacePositions(), null);
        if (furnacePos == null) return false;
        furnace = new CraftingStation(furnacePos);
        walkTarget = furnace.standFor(ctx, storage);
        if (walkTarget == null) return false;
        resetMove();
        phase = Phase.WALK_TO_FURNACE;
        return true;
    }

    private boolean walkToTable(Context ctx) {
        // A free one of the room's tables, when it has several.
        BlockPos tablePos = CraftingStation.pick(ctx, storage.getCraftingTablePositions(), null);
        if (tablePos == null) return false;
        table = new CraftingStation(tablePos);
        walkTarget = table.standFor(ctx, storage);
        if (walkTarget == null) return false;
        resetMove();
        phase = Phase.WALK_TO_TABLE;
        return true;
    }

    private void takeLogs(Context ctx) {
        int missing = logsNeeded(sticksOnly) - carriedLogs(ctx);
        for (int i = 0; i < missing; i++) {
            ItemStack log = storage.withdrawMatchingNear(ctx.level(), walkTarget, CHEST_REACH_SQ,
                    s -> s.is(ItemTags.LOGS_THAT_BURN));
            if (log.isEmpty()) return;
            if (!ctx.npc().getInventory().addItem(log)) {
                storage.depositItems(ctx.level(), log);
                ItemLossLog.destroyed(ctx, log, "torch-making log put-back found no room");
                return;
            }
        }
    }

    /**
     * Four logs become four charcoal. The furnace's fuel is planks cut from
     * the batch's other logs, so it is accounted for at the table.
     */
    private boolean smeltCharcoal(Context ctx) {
        if (!consumeLogs(ctx, CHARCOAL_PER_BATCH)) return false;
        give(ctx, new ItemStack(Items.CHARCOAL, CHARCOAL_PER_BATCH));
        ctx.level().playSound(null, furnace.table(), SoundEvents.FURNACE_FIRE_CRACKLE,
                SoundSource.BLOCKS, 0.8f, 1.0f);
        diary(ctx, "TORCH made " + CHARCOAL_PER_BATCH + " charcoal");
        return true;
    }

    /** Planks from logs; fuel planks spent; sticks; and torches if there is charcoal. */
    private boolean craftAtTable(Context ctx) {
        int plankLogs = sticksOnly ? PLANK_LOGS_STICKS_ONLY : PLANK_LOGS_FULL;
        if (!consumeLogs(ctx, plankLogs)) return false;
        int planks = plankLogs * PLANKS_PER_LOG;
        if (!sticksOnly) {
            planks -= (int) Math.ceil(CHARCOAL_PER_BATCH / SMELTS_PER_PLANK);   // furnace fuel
        }
        int sticks = STICKS_PER_TWO_PLANKS;
        planks -= 2;
        if (planks > 0) give(ctx, new ItemStack(Items.OAK_PLANKS, planks));

        if (sticksOnly) {
            give(ctx, new ItemStack(Items.STICK, sticks));
            diary(ctx, "TORCH made " + sticks + " sticks");
            return true;
        }
        int charcoal = countCarried(ctx, Items.CHARCOAL);
        int crafts = Math.min(Math.min(charcoal, sticks), CHARCOAL_PER_BATCH);
        consumeCarried(ctx, Items.CHARCOAL, crafts);
        if (sticks > crafts) give(ctx, new ItemStack(Items.STICK, sticks - crafts));
        give(ctx, new ItemStack(Items.TORCH, crafts * TORCHES_PER_CRAFT));
        diary(ctx, "TORCH made " + crafts * TORCHES_PER_CRAFT + " torches");
        return crafts > 0;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void give(Context ctx, ItemStack stack) {
        ItemStack owned = VillageItemOwnership.produce(stack, ctx.mgr());
        if (owned.isEmpty()) return;
        if (!ctx.npc().getInventory().addItem(owned)) {
            storage.depositItems(ctx.level(), owned);
            ItemLossLog.destroyed(ctx, owned, "torch-making product: pack and storage both full");
        }
    }

    private static int carriedLogs(Activity.Context ctx) {
        int total = 0;
        InventoryManager inventory = ctx.npc().getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (inventory.mayUse(stack) && stack.is(ItemTags.LOGS_THAT_BURN)) total += stack.getCount();
        }
        return total;
    }

    private static boolean consumeLogs(Context ctx, int amount) {
        if (carriedLogs(ctx) < amount) return false;
        InventoryManager inventory = ctx.npc().getInventory();
        int remaining = amount;
        for (int slot = 0; slot < InventoryManager.SIZE && remaining > 0; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (!inventory.mayUse(stack) || !stack.is(ItemTags.LOGS_THAT_BURN)) continue;
            int take = Math.min(remaining, stack.getCount());
            inventory.reduceSlot(slot, take);
            remaining -= take;
        }
        return remaining == 0;
    }

    private static int countCarried(Context ctx, net.minecraft.world.item.Item item) {
        int total = 0;
        InventoryManager inventory = ctx.npc().getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (inventory.mayUse(stack) && stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static void consumeCarried(Context ctx, net.minecraft.world.item.Item item, int amount) {
        InventoryManager inventory = ctx.npc().getInventory();
        int remaining = amount;
        for (int slot = 0; slot < InventoryManager.SIZE && remaining > 0; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (!inventory.mayUse(stack) || !stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            inventory.reduceSlot(slot, take);
            remaining -= take;
        }
    }

    /** Shows the furnace burning while the miner works it; put back afterwards. */
    private void setFurnaceLit(Context ctx, boolean lit) {
        if (furnace == null || furnaceLit == lit) return;
        ServerLevel level = ctx.level();
        BlockState state = level.getBlockState(furnace.table());
        if (!state.hasProperty(AbstractFurnaceBlock.LIT)) return;
        // Never switch off a furnace a player has burning.
        if (!lit && !furnaceLit) return;
        if (lit && state.getValue(AbstractFurnaceBlock.LIT)) return;
        level.setBlock(furnace.table(), state.setValue(AbstractFurnaceBlock.LIT, lit), 3);
        furnaceLit = lit;
    }

    private void furnaceEffects(Context ctx) {
        BlockPos pos = furnace.table();
        ctx.level().sendParticles(ParticleTypes.SMOKE,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.0);
        if (ctx.level().random.nextInt(3) == 0) {
            ctx.level().playSound(null, pos, SoundEvents.FURNACE_FIRE_CRACKLE,
                    SoundSource.BLOCKS, 0.5f, 1.0f);
        }
    }

    private boolean chestOpened;

    private void openChest(Context ctx, boolean open) {
        if (chestPos == null || chestOpened == open) return;
        chestOpened = open;
        com.alex.test.village.building.StorageContainers.setOpen(ctx.level(), chestPos, open);
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }
}
