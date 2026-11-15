package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.NavGraph;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.CraftingStation;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.archer.ArcherBowUtils;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import com.alex.test.village.villagers.supplies.WoodCutting;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * The archer makes its own arrows, and bows, from what is in storage.
 *
 * <p>Arrows: vanilla's recipe, one flint, one stick and one feather. Flint
 * short in storage is sifted out of gravel at the table:
 * {@value #GRAVEL_PER_FLINT} gravel make one flint. Gravel comes from the
 * miner, feathers from the butcher's chickens. Done at an Archery Range's
 * fletching table when the village has one, which makes
 * {@value ArcheryRangeBuilding#ARROWS_PER_FLETCH} arrows a craft; otherwise at
 * a storage room's crafting table, vanilla's four.
 *
 * <p>Bows: vanilla's three string and three sticks, one bow a craft, at either
 * table. String comes from the spiders the defenders kill ({@link FieldPickup}).
 *
 * <p>Sticks come from storage, or are cut from planks (two planks, four
 * sticks), and with no planks either, from a log cut into four planks first.
 * Made for the archer itself (its quiver, a bow to replace a broken
 * one) or for storage (a spare bow, a Stock tab order), which it then walks
 * back to the storage room to put away. Modelled on the miner's torch-making.
 */
public final class FletchArrowsActivity extends Activity {

    /** What is made. */
    public enum Product { ARROWS, BOW }

    private enum Phase { WALK_TO_CHEST, AT_CHEST, WALK_TO_TABLE, CRAFTING, DELIVER }

    /** Vanilla's recipe at a crafting table. */
    public static final int ARROWS_PER_CRAFT = 4;
    private static final int STICKS_PER_TWO_PLANKS = 4;
    /** Vanilla's bow: three string and three sticks. */
    private static final int STRING_PER_BOW = 3;
    private static final int STICKS_PER_BOW = 3;
    /** Ticks at the table per craft. */
    private static final int TICKS_PER_CRAFT = 20;
    private static final int TICKS_PER_BOW = 60;
    private static final int CHEST_BUSY_TICKS = 20;
    /** Takes ingredients from any chest of the room, not just the one opened. */
    private static final double ROOM_REACH_SQ = 32.0 * 32.0;
    /** Gravel sifted for one flint. */
    public static final int GRAVEL_PER_FLINT = 4;
    /** Ticks at the table per gravel sifted. */
    private static final int TICKS_PER_SIFT = 10;

    private static final Predicate<ItemStack> FLINT = s -> s.is(Items.FLINT);
    private static final Predicate<ItemStack> GRAVEL = s -> s.is(Items.GRAVEL);
    private static final Predicate<ItemStack> FEATHER = s -> s.is(Items.FEATHER);
    private static final Predicate<ItemStack> STRING = s -> s.is(Items.STRING);
    private static final Predicate<ItemStack> STICK = s -> s.is(Items.STICK);
    private static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);
    private static final Predicate<ItemStack> LOGS = s -> s.is(ItemTags.LOGS);
    /** A log cut into four planks, cut into sticks two planks at a time. */
    private static final int STICKS_PER_LOG = WoodCutting.PLANKS_PER_LOG / 2 * STICKS_PER_TWO_PLANKS;

    private final Product product;
    /** Items asked for; 0 for arrows means "fill the archer's own quiver". */
    private final int askedItems;
    /** Made for storage (a spare bow, an order), not for the archer's own use. */
    private final boolean forStorage;
    /**
     * An order's bookkeeping: told how many items went into storage, once they
     * are there (an archer counts an order made and delivered together; what
     * it carries is its own quiver, not an order on its way).
     */
    @Nullable private final IntConsumer onDelivered;
    /** An order given up before delivery: its claim is let go. */
    @Nullable private final Runnable onAbandoned;
    private boolean delivered;

    private StorageBuilding storage;
    /** The range whose fletching table is used; null to use a crafting table. */
    @Nullable private ArcheryRangeBuilding range;
    private int perCraft = ARROWS_PER_CRAFT;
    private Phase phase;
    private BlockPos chestPos;
    private BlockPos chestStand;
    private BlockPos walkTarget;
    private CraftingStation table;
    private int crafts;
    private int timer;
    private boolean chestOpened;
    /** Made for storage and still to be put away. */
    private final List<ItemStack> delivery = new ArrayList<>();

    /** Arrows for the archer's own quiver. */
    public FletchArrowsActivity() {
        this(Product.ARROWS, 0, false, null, null);
    }

    private FletchArrowsActivity(Product product, int askedItems, boolean forStorage,
                                 @Nullable IntConsumer onDelivered, @Nullable Runnable onAbandoned) {
        this.product = product;
        this.askedItems = askedItems;
        this.forStorage = forStorage;
        this.onDelivered = onDelivered;
        this.onAbandoned = onAbandoned;
    }

    /** A bow for this archer, whose own has broken and storage has none. */
    public static FletchArrowsActivity bowForSelf() {
        return new FletchArrowsActivity(Product.BOW, 1, false, null, null);
    }

    /** A spare bow for storage, so one is waiting when a worn bow breaks. */
    public static FletchArrowsActivity spareBow() {
        return new FletchArrowsActivity(Product.BOW, 1, true, null, null);
    }

    /** {@code items} arrows or bows for storage, against a Stock tab order. */
    public static FletchArrowsActivity forOrder(Product product, int items, IntConsumer onDelivered,
                                                Runnable onAbandoned) {
        return new FletchArrowsActivity(product, Math.max(1, items), true, onDelivered, onAbandoned);
    }

    // =========================================================================
    // Planning
    // =========================================================================

    private static int perCraft(Product product, boolean fletchingTable) {
        if (product == Product.BOW) return 1;
        return fletchingTable ? ArcheryRangeBuilding.ARROWS_PER_FLETCH : ARROWS_PER_CRAFT;
    }

    /** Crafts wanted: enough for what was asked, or to fill the quiver (rounded up), at least one. */
    private static int craftsWanted(Activity.Context ctx, int askedItems, int perCraft) {
        int missing = askedItems > 0 ? askedItems
                : ArcherBowUtils.quiverSize(ctx.npc()) - ArcherBowUtils.countArrows(ctx.npc().getInventory());
        return Math.max(1, (missing + perCraft - 1) / perCraft);
    }

    /** Whether there is somewhere to work and a storage room with the makings of one craft of arrows. */
    public static boolean canRun(Activity.Context ctx) {
        return canMake(ctx, Product.ARROWS, 0);
    }

    /** Whether storage holds three string and three sticks (or planks) and there is a table to work at. */
    public static boolean canMakeBow(Activity.Context ctx) {
        return canMake(ctx, Product.BOW, 1);
    }

    /** Whether one craft of {@code product} can be made now. */
    public static boolean canMake(Activity.Context ctx, Product product, int askedItems) {
        // "Bow and Arrow Crafting" off: only ready ones from storage.
        if (!ArcherBowUtils.setting(ctx.npc(),
                com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema.MAKE_ARROWS)) return false;
        ArcheryRangeBuilding range = usableFletchingRange(ctx);
        return findStorage(ctx, product, craftsWanted(ctx, askedItems, perCraft(product, range != null)),
                range == null) != null;
    }

    /**
     * The nearest valid Archery Range whose fletching table is still there
     * with floor beside it to work from, or null. Checked before anything is
     * taken from storage: a table that could not be used sent the archer to
     * the chest for nothing when the storage room had no crafting table
     * either, and it tried again every half minute.
     */
    @Nullable
    private static ArcheryRangeBuilding usableFletchingRange(Activity.Context ctx) {
        BlockPos npcPos = ctx.npc().blockPosition();
        ArcheryRangeBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof ArcheryRangeBuilding range) || !range.isValid()
                    || !range.hasFletchingTable()) continue;
            double d = range.getDoorPos().distSqr(npcPos);
            if (d >= bestDist) continue;
            BlockPos table = nearestFletchingTable(ctx, range);
            if (table == null || new CraftingStation(table).standFor(ctx, range) == null) continue;
            bestDist = d;
            best = range;
        }
        return best;
    }

    /** The nearest valid storage room with a crafting table, or null. */
    @Nullable
    private static StorageBuilding nearestCraftingRoom(Activity.Context ctx) {
        BlockPos npcPos = ctx.npc().blockPosition();
        StorageBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding room) || !room.isValid()
                    || !room.hasCraftingTable()) continue;
            double d = room.getDoorPos().distSqr(npcPos);
            if (d < bestDist) {
                bestDist = d;
                best = room;
            }
        }
        return best;
    }

    /** Crafts {@code storage} plus the pack can make, up to {@code wanted}. */
    private static int craftsPossible(Activity.Context ctx, StorageBuilding storage, Product product, int wanted) {
        int sticks = storage.countMatching(ctx.level(), STICK) + carried(ctx, Items.STICK)
                + (storage.countMatching(ctx.level(), PLANKS) + carriedPlanks(ctx)) / 2 * STICKS_PER_TWO_PLANKS
                + (storage.countMatching(ctx.level(), LOGS) + carriedLogs(ctx)) * STICKS_PER_LOG;
        if (product == Product.BOW) {
            int string = storage.countMatching(ctx.level(), STRING) + carried(ctx, Items.STRING);
            return Math.min(wanted, Math.min(string / STRING_PER_BOW, sticks / STICKS_PER_BOW));
        }
        int flint = storage.countMatching(ctx.level(), FLINT) + carried(ctx, Items.FLINT)
                + (storage.countMatching(ctx.level(), GRAVEL) + carried(ctx, Items.GRAVEL)) / GRAVEL_PER_FLINT;
        int feathers = storage.countMatching(ctx.level(), FEATHER) + carried(ctx, Items.FEATHER);
        return Math.min(wanted, Math.min(flint, Math.min(feathers, sticks)));
    }

    /**
     * The nearest storage room with the makings of a craft; with a crafting
     * table too when {@code needsCraftingTable} (no fletching table to work
     * at instead).
     */
    @Nullable
    private static StorageBuilding findStorage(Activity.Context ctx, Product product, int wanted,
                                               boolean needsCraftingTable) {
        BlockPos npcPos = ctx.npc().blockPosition();
        StorageBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            if (needsCraftingTable && !storage.hasCraftingTable()) continue;
            if (craftsPossible(ctx, storage, product, wanted) <= 0) continue;
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
        range = usableFletchingRange(ctx);
        perCraft = perCraft(product, range != null);
        storage = findStorage(ctx, product, craftsWanted(ctx, askedItems, perCraft), range == null);
        if (storage == null) return;
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return;
        crafts = craftsPossible(ctx, storage, product, craftsWanted(ctx, askedItems, perCraft));
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
        chestStand = walkTarget;
        if (walkTarget != null) phase = Phase.WALK_TO_CHEST;
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    @Override
    protected Status tick(Context ctx) {
        if (storage == null || phase == null || crafts <= 0) return Status.FAILED;
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
                takeIngredients(ctx);
                crafts = Math.min(crafts, craftsCarried(ctx));
                if (crafts <= 0) {
                    diary(ctx, product == Product.BOW
                            ? "ARCHER not enough string or sticks to make a bow"
                            : "ARCHER not enough flint (or gravel), feathers or sticks to make arrows");
                    return Status.FAILED;
                }
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
                timer = product == Product.BOW ? crafts * TICKS_PER_BOW
                        : crafts * TICKS_PER_CRAFT + flintToSift(ctx) * GRAVEL_PER_FLINT * TICKS_PER_SIFT;
                // The bow in hand is a display copy: put down, or the craft swing hammers with it.
                ctx.npc().setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                ctx.npc().setAnimation(workAnimation(ctx));
                phase = Phase.CRAFTING;
            }
            case CRAFTING -> {
                CraftingStation.face(ctx.npc(), table.table());
                ctx.npc().setAnimation(workAnimation(ctx));
                if (--timer > 0) return Status.RUNNING;
                ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                table.release(ctx);
                if (!craft(ctx)) return Status.FAILED;
                if (delivery.isEmpty()) return Status.DONE;
                walkTarget = chestStand;
                resetMove();
                phase = Phase.DELIVER;
            }
            case DELIVER -> {
                if (!moveTo(ctx, walkTarget, VillagerModel.WALK_SPEED)) return Status.RUNNING;
                putAway(ctx);
                return Status.DONE;
            }
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) {
        openChest(ctx, false);
        if (table != null) table.release(ctx);
        // Cut short on the way back: what was made stays with the archer, or
        // goes into the room if the pack cannot take it.
        for (ItemStack stack : delivery) {
            if (!ctx.npc().getInventory().addItem(stack) && storage != null) storage.depositItems(ctx.level(), stack);
        }
        delivery.clear();
        if (!delivered && onAbandoned != null) onAbandoned.run();
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public String name() {
        return product == Product.BOW ? "make_bow" : "fletch_arrows";
    }

    // =========================================================================
    // Steps
    // =========================================================================

    /** The crafting-table clip at a storage room's crafting table; the fletching table keeps the old one. */
    private VillagerAnimationState workAnimation(Context ctx) {
        return ctx.level().getBlockState(table.table()).is(Blocks.CRAFTING_TABLE)
                ? VillagerAnimationState.CRAFT_TABLE : VillagerAnimationState.CRAFTING;
    }

    /**
     * Heads for the range's fletching table, or a crafting table: also when
     * the range or its table has gone since the plan was made. The crafting
     * table is this storage room's, or the nearest other room's when this one
     * has none (four arrows a craft then). A bow for storage is made at this
     * room's crafting table when it has one: it is going back there anyway.
     */
    private boolean walkToTable(Context ctx) {
        boolean stayInRoom = product == Product.BOW && forStorage && storage.hasCraftingTable();
        if (!stayInRoom && range != null && range.isValid()) {
            // A free one of the range's fletching tables, when it has several.
            BlockPos fletchingTable = CraftingStation.pick(ctx, fletchingTables(ctx, range), null);
            if (fletchingTable != null) {
                CraftingStation station = new CraftingStation(fletchingTable);
                BlockPos stand = station.standFor(ctx, range);
                if (stand != null) {
                    table = station;
                    walkTarget = stand;
                    perCraft = perCraft(product, true);
                    resetMove();
                    phase = Phase.WALK_TO_TABLE;
                    return true;
                }
            }
        }
        perCraft = perCraft(product, false);
        StorageBuilding room = storage.hasCraftingTable() ? storage : nearestCraftingRoom(ctx);
        // A free one of the room's tables, when it has several.
        BlockPos tablePos = room != null ? CraftingStation.pick(ctx, room.getCraftingTablePositions(), null) : null;
        if (tablePos == null) {
            diary(ctx, "ARCHER has the makings but no fletching or crafting table to use");
            return false;
        }
        table = new CraftingStation(tablePos);
        walkTarget = table.standFor(ctx, room);
        if (walkTarget == null) return false;
        resetMove();
        phase = Phase.WALK_TO_TABLE;
        return true;
    }

    /** The range's fletching tables that are loaded and still fletching tables. */
    private static List<BlockPos> fletchingTables(Context ctx, ArcheryRangeBuilding range) {
        List<BlockPos> tables = new ArrayList<>();
        for (BlockPos pos : range.getFletchingTables()) {
            if (ctx.level().isLoaded(pos) && ctx.level().getBlockState(pos).is(Blocks.FLETCHING_TABLE)) tables.add(pos);
        }
        return tables;
    }

    @Nullable
    private static BlockPos nearestFletchingTable(Context ctx, ArcheryRangeBuilding range) {
        BlockPos npcPos = ctx.npc().blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : fletchingTables(ctx, range)) {
            double d = pos.distSqr(npcPos);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /** Sticks needed for the crafts planned. */
    private int sticksNeeded() {
        return product == Product.BOW ? crafts * STICKS_PER_BOW : crafts;
    }

    /** Crafts what the pack now holds can make. */
    private int craftsCarried(Context ctx) {
        int sticks = carried(ctx, Items.STICK) + carriedPlanks(ctx) / 2 * STICKS_PER_TWO_PLANKS
                + carriedLogs(ctx) * STICKS_PER_LOG;
        if (product == Product.BOW) {
            return Math.min(carried(ctx, Items.STRING) / STRING_PER_BOW, sticks / STICKS_PER_BOW);
        }
        return Math.min(carriedFlintWithGravel(ctx), Math.min(carried(ctx, Items.FEATHER), sticks));
    }

    /** The recipe's makings for every craft; sticks, or planks to cut them from, or logs for those. */
    private void takeIngredients(Context ctx) {
        if (product == Product.BOW) {
            take(ctx, STRING, crafts * STRING_PER_BOW - carried(ctx, Items.STRING));
        } else {
            // Flint, or gravel to sift it from.
            take(ctx, FLINT, crafts - carried(ctx, Items.FLINT));
            int flintShort = crafts - carried(ctx, Items.FLINT);
            if (flintShort > 0) take(ctx, GRAVEL, flintShort * GRAVEL_PER_FLINT - carried(ctx, Items.GRAVEL));
            take(ctx, FEATHER, crafts - carried(ctx, Items.FEATHER));
        }
        int sticksShort = sticksNeeded() - carried(ctx, Items.STICK);
        if (sticksShort > 0) take(ctx, STICK, sticksShort);
        sticksShort = sticksNeeded() - carried(ctx, Items.STICK);
        if (sticksShort > 0) {
            int planksNeeded = (sticksShort + STICKS_PER_TWO_PLANKS - 1) / STICKS_PER_TWO_PLANKS * 2;
            take(ctx, PLANKS, planksNeeded - carriedPlanks(ctx));
            // Still short of planks: logs, four planks each.
            int planksShort = planksNeeded - carriedPlanks(ctx);
            if (planksShort > 0) {
                int logsNeeded = (planksShort + WoodCutting.PLANKS_PER_LOG - 1) / WoodCutting.PLANKS_PER_LOG;
                take(ctx, LOGS, logsNeeded - carriedLogs(ctx));
            }
        }
    }

    private void take(Context ctx, Predicate<ItemStack> what, int amount) {
        for (int i = 0; i < amount; i++) {
            ItemStack piece = storage.withdrawMatchingNear(ctx.level(), walkTarget, ROOM_REACH_SQ, what);
            if (piece.isEmpty()) return;
            if (!ctx.npc().getInventory().addItem(piece)) {
                storage.depositItems(ctx.level(), piece);
                ItemLossLog.destroyed(ctx, piece, "fletching ingredient put-back found no room");
                return;
            }
        }
    }

    /** Gravel the crafts still need sifted into flint: one flint per {@value #GRAVEL_PER_FLINT} gravel. */
    private int flintToSift(Context ctx) {
        if (product != Product.ARROWS) return 0;
        int missing = crafts - carried(ctx, Items.FLINT);
        return missing <= 0 ? 0 : Math.min(missing, carried(ctx, Items.GRAVEL) / GRAVEL_PER_FLINT);
    }

    private static int carriedFlintWithGravel(Context ctx) {
        return carried(ctx, Items.FLINT) + carried(ctx, Items.GRAVEL) / GRAVEL_PER_FLINT;
    }

    /** Gravel into flint and planks into sticks as needed, then the recipe. */
    private boolean craft(Context ctx) {
        int sifted = flintToSift(ctx);
        if (sifted > 0) {
            consume(ctx, Items.GRAVEL, sifted * GRAVEL_PER_FLINT);
            give(ctx, new ItemStack(Items.FLINT, sifted));
            ctx.level().playSound(null, table.table(), SoundEvents.GRAVEL_BREAK, SoundSource.NEUTRAL, 0.6f, 1.0f);
            diary(ctx, "ARCHER sifted " + sifted * GRAVEL_PER_FLINT + " gravel into " + sifted + " flint");
        }
        int sticksShort = sticksNeeded() - carried(ctx, Items.STICK);
        // Logs into planks first, only as many as the sticks still want.
        int planksShort = (sticksShort + STICKS_PER_TWO_PLANKS - 1) / STICKS_PER_TWO_PLANKS * 2 - carriedPlanks(ctx);
        while (planksShort > 0) {
            Item log = consumeLog(ctx);
            if (log == null) break;
            give(ctx, new ItemStack(WoodCutting.planksFor(log), WoodCutting.PLANKS_PER_LOG));
            planksShort -= WoodCutting.PLANKS_PER_LOG;
            diary(ctx, "ARCHER cut a log into " + WoodCutting.PLANKS_PER_LOG + " planks");
        }
        while (sticksShort > 0 && carriedPlanks(ctx) >= 2) {
            consumePlanks(ctx, 2);
            give(ctx, new ItemStack(Items.STICK, STICKS_PER_TWO_PLANKS));
            sticksShort -= STICKS_PER_TWO_PLANKS;
        }
        String where = table.table() != null
                && ctx.level().getBlockState(table.table()).is(Blocks.FLETCHING_TABLE)
                ? "a fletching table" : "a crafting table";
        int items;
        if (product == Product.BOW) {
            int made = Math.min(crafts, Math.min(carried(ctx, Items.STRING) / STRING_PER_BOW,
                    carried(ctx, Items.STICK) / STICKS_PER_BOW));
            if (made <= 0) return false;
            consume(ctx, Items.STRING, made * STRING_PER_BOW);
            consume(ctx, Items.STICK, made * STICKS_PER_BOW);
            for (int i = 0; i < made; i++) produce(ctx, new ItemStack(Items.BOW));
            items = made;
            diary(ctx, "ARCHER made " + (made == 1 ? "a bow" : made + " bows") + " at " + where
                    + (forStorage ? " for storage" : ""));
        } else {
            int made = Math.min(crafts, Math.min(carried(ctx, Items.FLINT),
                    Math.min(carried(ctx, Items.FEATHER), carried(ctx, Items.STICK))));
            if (made <= 0) return false;
            consume(ctx, Items.FLINT, made);
            consume(ctx, Items.FEATHER, made);
            consume(ctx, Items.STICK, made);
            items = made * perCraft;
            produce(ctx, new ItemStack(Items.ARROW, items));
            diary(ctx, "ARCHER made " + items + " arrows at " + where + (forStorage ? " for storage" : ""));
        }
        ctx.level().playSound(null, table.table(), SoundEvents.VILLAGER_WORK_FLETCHER,
                SoundSource.NEUTRAL, 0.8f, 1.0f);
        return true;
    }

    /** The product, for the archer's own use or to be carried back to storage. */
    private void produce(Context ctx, ItemStack stack) {
        if (!forStorage) {
            give(ctx, stack);
            return;
        }
        ItemStack owned = VillageItemOwnership.produce(stack, ctx.mgr());
        if (!owned.isEmpty()) delivery.add(owned);
    }

    /** Back at the storage room: what was made for it goes into the chests. */
    private void putAway(Context ctx) {
        int stored = 0;
        for (ItemStack stack : delivery) {
            int before = stack.getCount();
            boolean all = storage.depositItems(ctx.level(), stack);
            stored += before - (all ? 0 : stack.getCount());
            if (all) continue;
            // Storage full: the rest stays with the archer, its own now.
            if (!ctx.npc().getInventory().addItem(stack)) {
                ItemLossLog.destroyed(ctx, stack, "fletching product: storage and pack both full");
            }
        }
        delivery.clear();
        delivered = true;
        if (onDelivered != null && stored > 0) onDelivered.accept(stored);
        diary(ctx, "ARCHER put what it made into storage (" + stored + ")");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void give(Context ctx, ItemStack stack) {
        ItemStack owned = VillageItemOwnership.produce(stack, ctx.mgr());
        if (owned.isEmpty()) return;
        if (!ctx.npc().getInventory().addItem(owned)) {
            storage.depositItems(ctx.level(), owned);
            ItemLossLog.destroyed(ctx, owned, "fletching product: pack and storage both full");
        }
    }

    private static int carried(Activity.Context ctx, Item item) {
        int total = 0;
        InventoryManager inventory = ctx.npc().getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (inventory.mayUse(stack) && stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int carriedPlanks(Activity.Context ctx) {
        int total = 0;
        InventoryManager inventory = ctx.npc().getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (inventory.mayUse(stack) && stack.is(ItemTags.PLANKS)) total += stack.getCount();
        }
        return total;
    }

    private static void consume(Context ctx, Item item, int amount) {
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

    private static int carriedLogs(Activity.Context ctx) {
        int total = 0;
        InventoryManager inventory = ctx.npc().getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (inventory.mayUse(stack) && stack.is(ItemTags.LOGS)) total += stack.getCount();
        }
        return total;
    }

    /** Uses up one carried log and says which; null when there is none. */
    @Nullable
    private static Item consumeLog(Context ctx) {
        InventoryManager inventory = ctx.npc().getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (!inventory.mayUse(stack) || !stack.is(ItemTags.LOGS)) continue;
            Item log = stack.getItem();
            inventory.reduceSlot(slot, 1);
            return log;
        }
        return null;
    }

    private static void consumePlanks(Context ctx, int amount) {
        InventoryManager inventory = ctx.npc().getInventory();
        int remaining = amount;
        for (int slot = 0; slot < InventoryManager.SIZE && remaining > 0; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (!inventory.mayUse(stack) || !stack.is(ItemTags.PLANKS)) continue;
            int take = Math.min(remaining, stack.getCount());
            inventory.reduceSlot(slot, take);
            remaining -= take;
        }
    }

    private void openChest(Context ctx, boolean open) {
        if (chestPos == null || chestOpened == open) return;
        chestOpened = open;
        com.alex.test.village.building.StorageContainers.setOpen(ctx.level(), chestPos, open);
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }
}
