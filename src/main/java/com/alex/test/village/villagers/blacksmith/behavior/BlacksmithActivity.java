package com.alex.test.village.villagers.blacksmith.behavior;

import com.alex.test.village.NavGraph;
import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.CraftingStation;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.FetchFromStorageActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.blacksmith.BlacksmithBalance;
import com.alex.test.village.villagers.blacksmith.ForgeOrderBook;
import com.alex.test.village.villagers.blacksmith.ForgeOrderBook.ToolKind;
import com.alex.test.village.villagers.blacksmith.ForgeRecycling;
import com.alex.test.village.villagers.blacksmith.SmithStrikes;
import com.alex.test.village.villagers.blacksmith.settings.BlacksmithSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.enchanter.BookFitting;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.supplies.BucketNeeds;
import com.alex.test.village.villagers.supplies.GoldNeeds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Blacksmith's working day: keep the village equipped, and melt down what
 * it no longer uses.
 *
 * <p>Each pass builds a short queue of jobs from what the village actually
 * needs, most urgent first (see {@link Planner}):
 * <ol>
 *   <li>someone's tool is missing or down to its last quarter -- the
 *       replacement goes to storage before the old one breaks;</li>
 *   <li>old tools that waited a day unclaimed are melted down
 *       ({@link ForgeRecycling});</li>
 *   <li>shears, a bucket for the wash cauldrons, shields and a first set of
 *       armour;</li>
 *   <li>upgrades, the miner's pickaxe first because it brings back the
 *       materials for everything else;</li>
 *   <li>buckets for the rancher's milk while the cook wants a cake;</li>
 *   <li>one spare of each tool for the whole village.</li>
 * </ol>
 * With nothing in the queue it smelts the miner's raw gold, as much as the
 * cook wants for golden food ({@link GoldNeeds}); raw gold already in hand
 * goes into the furnace after the iron. After that, with "Smelting" on,
 * it smelts the rest of the raw iron and gold, and all raw copper, into ingots,
 * which the forge can still use (not copper) and the tradesman can sell (raw
 * ore never sells). While nobody is near ({@link #workOffline}) both are done once
 * nothing is left to forge; old tools are never melted down there.
 * Every job is made at the best material the settings, the smith's skill and
 * the storage room allow, so a broken iron axe is never answered with a stone
 * one while there is iron. Materials for the whole queue are fetched together,
 * raw iron is smelted in batches against a fuel credit, and finished work is
 * banked together. The smith never hands anything to anyone directly; the
 * workers' own upgrade logic finds new tools in storage.
 */
public final class BlacksmithActivity extends WorkActivity {

    /** Jobs planned ahead, so their materials come in one trip. */
    private static final int QUEUE_SIZE = 4;

    /** Raw iron (and raw gold with it) smelted per furnace visit at most. */
    private static final int MAX_SMELT_BATCH = 16;

    private static final String FETCH_RAW_GOLD = "fetch_raw_gold";
    /** After a raw gold fetch fails, gold trips wait this long (5 minutes). */
    private static final long GOLD_PAUSE_TICKS = 6000;
    private static final String FETCH_SPARE_ORE = "fetch_spare_ore";
    /** Spare raw iron and gold worth a furnace trip of their own. */
    private static final int SPARE_MIN_LOAD = 8;
    /**
     * Smiths whose spare ore fetch failed, and the game time spare smelting may
     * start again. Kept across work sessions, not saved.
     */
    private static final Map<java.util.UUID, Long> SPARE_PAUSED_UNTIL = new java.util.HashMap<>();

    /** Fuel worth of one log or plank, as a share of a coal: 1.5 smelts of 8. */
    private static final float WOOD_FUEL_SHARE = 1.5f / 8f;

    /** Ticks of station work that earn one minute's experience. */
    private static final int WORK_XP_TICKS = 1200;

    // =========================================================================
    // Materials and recipes
    // =========================================================================

    /** Everything a recipe can cost, by index into {@link Recipe#cost}. */
    enum Mat {
        /**
         * Any stone a stone tool takes, as vanilla's stone_tool_materials: a
         * miner digging below Y 0 brings home cobbled deepslate, not cobblestone.
         */
        COBBLE(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE),
        IRON(Items.IRON_INGOT),
        LEATHER(Items.LEATHER),
        DIAMOND(Items.DIAMOND),
        NETHERITE(Items.NETHERITE_INGOT),
        TEMPLATE(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
        NETHERRACK(Items.NETHERRACK),
        /** One log or two planks: the handle. */
        WOOD(null);

        /** The item an order card shows for it. */
        @Nullable final Item item;
        /** Every item that pays for it, {@link #item} first; empty for WOOD. */
        final List<Item> items;

        Mat(@Nullable Item item, Item... alternatives) {
            this.item = item;
            List<Item> all = new java.util.ArrayList<>();
            if (item != null) all.add(item);
            all.addAll(List.of(alternatives));
            this.items = List.copyOf(all);
        }
    }

    private static final Mat[] MATS = Mat.values();

    enum Station { ANVIL, SMITHING }

    /**
     * One thing the forge can make.
     *
     * @param tier      {@code ForgeOrderBook} tier of the result, or -1
     * @param kind      the tool kind this equips, or null
     * @param armorSlot index into {@link ForgeOrderBook#ARMOR_SLOTS}, or -1
     */
    record Recipe(Item result, int count, int tier, @Nullable ToolKind kind, int armorSlot,
                  int[] cost, Station station) {
        int cost(Mat mat) {
            return cost[mat.ordinal()];
        }
    }

    /**
     * A queued job: make a recipe, or melt down one old tool. {@code order} is the Stock
     * tab row it is made for, or null for the village's own needs.
     */
    record Job(@Nullable Recipe recipe, @Nullable ForgeOrderBook.Recyclable recycle, String reason,
               @Nullable StockReserves.Want order) {
        Job(@Nullable Recipe recipe, @Nullable ForgeOrderBook.Recyclable recycle, String reason) {
            this(recipe, recycle, reason, null);
        }

        String label() {
            return recipe != null
                    ? recipe.result().getDescription().getString()
                    : "recycle " + recycle.stack().getHoverName().getString();
        }

        int cost(Mat mat) {
            return recipe == null ? 0 : recipe.cost(mat);
        }
    }

    private static int[] cost(Object... pairs) {
        int[] cost = new int[MATS.length];
        for (int i = 0; i < pairs.length; i += 2) {
            cost[((Mat) pairs[i]).ordinal()] = (Integer) pairs[i + 1];
        }
        return cost;
    }

    /** Head material per tool kind: axe and pickaxe 3, sword and hoe 2. */
    private static int headCost(ToolKind kind) {
        return kind == ToolKind.AXE || kind == ToolKind.PICKAXE ? 3 : 2;
    }

    /** Material per {@link ForgeOrderBook#ARMOR_SLOTS} index: chest 8, head 5, legs 7, feet 4. */
    private static final int[] ARMOR_COST = {8, 5, 7, 4};

    private static final Map<ToolKind, Item[]> TOOL_ITEMS = new EnumMap<>(Map.of(
            // indexed by tier: wood, stone, iron, diamond, netherite
            ToolKind.AXE, new Item[]{Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
                    Items.DIAMOND_AXE, Items.NETHERITE_AXE},
            ToolKind.PICKAXE, new Item[]{Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
                    Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE},
            ToolKind.HOE, new Item[]{Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE,
                    Items.DIAMOND_HOE, Items.NETHERITE_HOE},
            ToolKind.SWORD, new Item[]{Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
                    Items.DIAMOND_SWORD, Items.NETHERITE_SWORD}));

    /** Indexed [slot][tier]; tier 0 is leather, 1 (stone) has no armour. */
    private static final Item[][] ARMOR_ITEMS = {
            {Items.LEATHER_CHESTPLATE, null, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE},
            {Items.LEATHER_HELMET, null, Items.IRON_HELMET, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET},
            {Items.LEATHER_LEGGINGS, null, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS},
            {Items.LEATHER_BOOTS, null, Items.IRON_BOOTS, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS}};

    @Nullable
    static Recipe toolRecipe(ToolKind kind, int tier) {
        Item item = TOOL_ITEMS.get(kind)[tier];
        int head = headCost(kind);
        return switch (tier) {
            case ForgeOrderBook.TIER_STONE -> new Recipe(item, 1, tier, kind, -1,
                    cost(Mat.COBBLE, head, Mat.WOOD, 1), Station.ANVIL);
            case ForgeOrderBook.TIER_IRON -> new Recipe(item, 1, tier, kind, -1,
                    cost(Mat.IRON, head, Mat.WOOD, 1), Station.ANVIL);
            case ForgeOrderBook.TIER_DIAMOND -> new Recipe(item, 1, tier, kind, -1,
                    cost(Mat.DIAMOND, head, Mat.WOOD, 1), Station.ANVIL);
            // Forged as diamond and upgraded at once: the diamond cost plus
            // the ingot and the template the smithing table eats.
            case ForgeOrderBook.TIER_NETHERITE -> new Recipe(item, 1, tier, kind, -1,
                    cost(Mat.DIAMOND, head, Mat.WOOD, 1, Mat.NETHERITE, 1, Mat.TEMPLATE, 1),
                    Station.SMITHING);
            default -> null;
        };
    }

    @Nullable
    static Recipe armorRecipe(int slot, int tier) {
        Item item = ARMOR_ITEMS[slot][tier];
        if (item == null) return null;
        int n = ARMOR_COST[slot];
        return switch (tier) {
            case ForgeOrderBook.TIER_WOOD -> new Recipe(item, 1, tier, null, slot,
                    cost(Mat.LEATHER, n), Station.ANVIL);
            case ForgeOrderBook.TIER_IRON -> new Recipe(item, 1, tier, null, slot,
                    cost(Mat.IRON, n), Station.ANVIL);
            case ForgeOrderBook.TIER_DIAMOND -> new Recipe(item, 1, tier, null, slot,
                    cost(Mat.DIAMOND, n), Station.ANVIL);
            case ForgeOrderBook.TIER_NETHERITE -> new Recipe(item, 1, tier, null, slot,
                    cost(Mat.DIAMOND, n, Mat.NETHERITE, 1, Mat.TEMPLATE, 1), Station.SMITHING);
            default -> null;
        };
    }

    /** The recipe that makes exactly {@code item}, or null. */
    @Nullable
    static Recipe recipeFor(Item item) {
        if (item == SHIELD.result()) return SHIELD;
        if (item == SHEARS.result()) return SHEARS;
        if (item == BUCKET.result()) return BUCKET;
        if (item == TEMPLATE_COPY.result()) return TEMPLATE_COPY;
        for (ToolKind kind : ToolKind.values()) {
            for (int tier = ForgeOrderBook.TIER_STONE; tier <= ForgeOrderBook.TIER_NETHERITE; tier++) {
                if (TOOL_ITEMS.get(kind)[tier] == item) return toolRecipe(kind, tier);
            }
        }
        for (int slot = 0; slot < ARMOR_ITEMS.length; slot++) {
            for (int tier = 0; tier < ARMOR_ITEMS[slot].length; tier++) {
                if (ARMOR_ITEMS[slot][tier] == item) return armorRecipe(slot, tier);
            }
        }
        return null;
    }

    /** Stands for "one log or two planks" in {@link OrderInfo#materials}. */
    public static final Item WOOD_STAND_IN = Items.OAK_LOG;

    /**
     * What one forge batch of an item gives and costs, for the storage room's Stock tab.
     *
     * @param count         items one batch makes (two for a template copy)
     * @param materials     cost by item; wood is {@link #WOOD_STAND_IN}
     * @param level         smith level that can make it
     * @param smithingTable netherite: needs a smithing table at the forge
     */
    public record OrderInfo(int count, Map<Item, Integer> materials, int level, boolean smithingTable) {}

    /** {@link OrderInfo} for {@code item}, or null when the forge does not make it. */
    @Nullable
    public static OrderInfo orderInfo(Item item) {
        Recipe recipe = recipeFor(item);
        if (recipe == null) return null;
        Map<Item, Integer> materials = new java.util.LinkedHashMap<>();
        for (Mat mat : MATS) {
            int n = recipe.cost(mat);
            if (n > 0) materials.put(mat == Mat.WOOD ? WOOD_STAND_IN : mat.item, n);
        }
        return new OrderInfo(recipe.count(), materials, orderLevel(recipe), recipe.station() == Station.SMITHING);
    }

    /** Smith level a recipe needs: diamond 25, netherite 75, a template copy 100. */
    private static int orderLevel(Recipe recipe) {
        if (recipe == TEMPLATE_COPY) return BlacksmithBalance.TEMPLATE_LEVEL;
        for (int level = 1; level <= BlacksmithBalance.MAX_LEVEL; level++) {
            if (BlacksmithBalance.maxTier(level) >= recipe.tier()) return level;
        }
        return BlacksmithBalance.MAX_LEVEL;
    }

    /** A single ingot and the handle wood (vanilla's six planks would be dearer). */
    static final Recipe SHIELD = new Recipe(Items.SHIELD, 1, ForgeOrderBook.TIER_IRON, null, -1,
            cost(Mat.IRON, 1, Mat.WOOD, 1), Station.ANVIL);
    static final Recipe SHEARS = new Recipe(Items.SHEARS, 1, ForgeOrderBook.TIER_IRON, null, -1,
            cost(Mat.IRON, 2), Station.ANVIL);
    /** Level 100: vanilla's copy recipe, one template in, two out. */
    static final Recipe TEMPLATE_COPY = new Recipe(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2, -1,
            null, -1, cost(Mat.DIAMOND, 7, Mat.NETHERRACK, 1, Mat.TEMPLATE, 1), Station.ANVIL);
    /** Vanilla's bucket: three ingots. For the farmers' cauldrons and the rancher's milk (BucketNeeds). */
    static final Recipe BUCKET = new Recipe(Items.BUCKET, 1, ForgeOrderBook.TIER_IRON, null, -1,
            cost(Mat.IRON, 3), Station.ANVIL);
    /**
     * Stands for a furnace visit that smelts raw gold for the kitchen
     * (GoldNeeds); never crafted, so it costs nothing at the anvil.
     */
    private static final Recipe SMELT_GOLD = new Recipe(Items.GOLD_INGOT, 1, -1, null, -1,
            cost(), Station.ANVIL);
    /**
     * Stands for a furnace visit that smelts spare raw iron and gold into
     * ingots to sell ("Smelting"); never crafted, so it costs nothing.
     */
    private static final Recipe SMELT_SPARE = new Recipe(Items.IRON_INGOT, 1, -1, null, -1,
            cost(), Station.ANVIL);

    // =========================================================================
    // State
    // =========================================================================

    private enum Phase { SMELT, CRAFT, RECYCLE }

    @Nullable private Job job;
    @Nullable private Phase phase;
    @Nullable private MoveActivity move;
    @Nullable private Activity sub;
    private boolean finishAfterSub;
    @Nullable private BlockPos station;
    /** The room tile he works {@link #station} from. */
    @Nullable private BlockPos stand;
    /** The last step onto {@link #stand}'s middle, square to the station, before the work starts; null otherwise. */
    @Nullable private CraftingStation settling;
    private int workTimer;
    private int smeltTicksPerItem;
    private int smeltCount;
    /** Smelts the fuel already burned still pays for; goes cold when the session ends. */
    private float smeltCredit;
    /** Raw gold in this furnace load, smelted after the iron. */
    private int goldThisLoad;
    /** Raw copper in this furnace load (spare ore visits only), smelted last. */
    private int copperThisLoad;
    /**
     * Smiths whose raw gold fetch failed (chest out of reach), and the game time
     * gold trips may start again. Kept across work sessions, not saved.
     */
    private static final Map<java.util.UUID, Long> GOLD_PAUSED_UNTIL = new java.util.HashMap<>();
    private int stationTicks;
    /** The work clip playing at the station, and ticks since it was set (for the hammer's clink). */
    private VillagerAnimationState workClip = VillagerAnimationState.IDLE;
    private int clipTicks;
    private boolean furnaceLit;
    private boolean workedThisSession;

    // =========================================================================
    // WorkActivity contract
    // =========================================================================

    @Override
    protected Status doTick(Context ctx) {
        if (!settings(ctx).getBool(BlacksmithSettingsSchema.WORK_ENABLED)) {
            return Status.DONE;
        }

        // A worker waiting with an enchanted book is served between two steps
        // of the smith's own work; a walk under way is dropped and re-planned.
        if (sub == null && workTimer == 0 && BookFitting.hasCustomer(ctx)) {
            Activity serve = BookFitting.serve(ctx);
            if (serve != null) {
                if (move != null || settling != null) {
                    if (move != null) move.stop(ctx);
                    move = null;
                    settling = null;
                    job = null;
                    phase = null;
                }
                sub = serve;
                finishAfterSub = false;
                workedThisSession = true;
                return Status.RUNNING;
            }
        }

        if (sub != null) {
            Status status = sub.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            sub.stop(ctx);
            String subName = sub.name();
            sub = null;
            if (finishAfterSub) return Status.DONE;
            // A book fitting that fell through is no reason to stop the forge.
            if ("fit_book".equals(subName)) return Status.RUNNING;
            // Nor is raw gold it could not reach: the kitchen's gold waits a while.
            if (FETCH_RAW_GOLD.equals(subName) && status == Status.FAILED) {
                GOLD_PAUSED_UNTIL.put(ctx.npc().getNpcId(), ctx.level().getGameTime() + GOLD_PAUSE_TICKS);
                diary(ctx, "SMITH could not fetch raw gold; leaving it for now");
                return Status.RUNNING;
            }
            // Spare ore it could not reach waits too: it was only ever extra work.
            if (FETCH_SPARE_ORE.equals(subName) && status == Status.FAILED) {
                SPARE_PAUSED_UNTIL.put(ctx.npc().getNpcId(), ctx.level().getGameTime() + GOLD_PAUSE_TICKS);
                diary(ctx, "SMITH could not fetch spare ore; leaving it for now");
                return Status.RUNNING;
            }
            if (status == Status.FAILED) {
                // The planner only knows storage holds the input, not that this
                // smith can reach it. Planning again straight away asked for the
                // same fetch every other tick until the write-off expired; stop,
                // and let the brain's work backoff pace the retry.
                diary(ctx, "SMITH " + subName + " failed; stopping for now");
                job = null;
                return Status.FAILED;
            }
            return Status.RUNNING;
        }

        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED) {
                diary(ctx, "SMITH could not reach station " + station);
                job = null;
                phase = null;
                return depositOrFinish(ctx);
            }
            settling = new CraftingStation(station);
            return Status.RUNNING;
        }

        if (settling != null) {
            if (settling.settle(ctx, stand)) {
                settling = null;
                beginStationWork(ctx);
            }
            return Status.RUNNING;
        }

        if (workTimer > 0) {
            tickStationWork(ctx);
            return Status.RUNNING;
        }

        return chooseNext(ctx);
    }

    @Override
    public void doStop(Context ctx) {
        if (sub != null) { sub.stop(ctx); sub = null; }
        if (move != null) { move.stop(ctx); move = null; }
        settling = null;
        extinguishFurnace(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return move != null;
    }

    // =========================================================================
    // Choosing the next step
    // =========================================================================

    /**
     * Free slots kept clear before the smith starts another job. A villager
     * carries nine stacks; a smith that kept its finished work filled up and
     * then could not pick up anything, not even bread.
     */
    private static final int FREE_SLOTS_BEFORE_NEXT_JOB = 2;

    private Status chooseNext(Context ctx) {
        ForgeBuilding forge = findForge(ctx);
        if (forge == null) return depositOrFinish(ctx);

        // An old tool already in hand is melted down before anything else, so
        // an interrupted trip never leaves it riding around in the pack.
        int carriedOld = carriedRecyclableSlot(ctx);
        if (carriedOld >= 0) {
            job = new Job(null, new ForgeOrderBook.Recyclable(
                    ctx.npc().getInventory().getSlot(carriedOld).copyWithCount(1), BlockPos.ZERO),
                    "old tool in hand");
            phase = Phase.RECYCLE;
            station = forge.getAnvilPos();
            return startWalk(ctx, forge) ? Status.RUNNING : Status.FAILED;
        }

        List<Job> queue = plan(ctx, smeltCredit, forge);
        if (queue.isEmpty()) {
            job = null;
            // Nothing to forge: smelt the raw gold the kitchen wants.
            Status gold = smeltGold(ctx, forge);
            if (gold != null) return gold;
            // Then the miner's spare ore, into ingots the tradesman can sell.
            Status spare = smeltSpare(ctx, forge);
            if (spare != null) return spare;
            return depositOrFinish(ctx);
        }
        job = queue.get(0);
        // An order being made by this smith is not planned again by another.
        if (job.order() != null && job.order().once() && job.recipe() != null) {
            StockReserves.get(ctx.level().getServer()).claim(ctx.mgr().getVillageId(), job.order().row(),
                    ctx.npc().getNpcId(), job.recipe().count(), ctx.level().getGameTime());
        }

        if (freeSlots(ctx) < FREE_SLOTS_BEFORE_NEXT_JOB
                && DepositActivity.countDepositable(ctx) > 0) {
            diary(ctx, "SMITH banking finished work before the next job");
            job = null;
            sub = new DepositActivity();
            finishAfterSub = false;
            return Status.RUNNING;
        }

        if (job.recycle() != null) {
            diary(ctx, "SMITH fetching " + job.label() + " (" + job.reason() + ")");
            sub = FetchFromStorageActivity.forItemAtStorage(
                    job.recycle().stack(), 1, "fetch_old_tool", job.recycle().storageDoor());
            finishAfterSub = false;
            return Status.RUNNING;
        }

        Recipe recipe = job.recipe();
        // Plain materials, the whole queue's worth in one trip.
        for (Mat mat : MATS) {
            if (mat == Mat.IRON || mat == Mat.WOOD || recipe.cost(mat) == 0) continue;
            int carried = countMat(ctx, mat);
            if (carried >= recipe.cost(mat)) continue;
            int stored = storedAvailable(ctx, mat, job.order() != null);
            if (carried + stored < recipe.cost(mat)) {
                job = null;
                return depositOrFinish(ctx);
            }
            // Stone comes as whichever kind storage holds most of; when that
            // is not enough, the next pass fetches another kind.
            Map<Item, Integer> totals = storedTotals(ctx);
            Item fetch = mostStored(totals, mat);
            int wanted = Math.min(Math.min(stored, totals.getOrDefault(fetch, 0)),
                    Math.max(recipe.cost(mat), queueCost(queue, mat)) - carried);
            sub = FetchFromStorageActivity.forItem(new ItemStack(fetch), wanted,
                    "fetch_" + BuiltInRegistries.ITEM.getKey(fetch).getPath());
            finishAfterSub = false;
            return Status.RUNNING;
        }
        if (recipe.cost(Mat.WOOD) > 0 && woodUnits(ctx) < recipe.cost(Mat.WOOD)) {
            if (storageWith(ctx, StorageBuilding.ResourceType.WOOD) == null) {
                job = null;
                return depositOrFinish(ctx);
            }
            sub = FetchFromStorageActivity.forResource(StorageBuilding.ResourceType.WOOD,
                    Math.max(1, queueCost(queue, Mat.WOOD)), "fetch_wood");
            finishAfterSub = false;
            return Status.RUNNING;
        }
        if (recipe.cost(Mat.IRON) > 0 && countItem(ctx, Items.IRON_INGOT) < recipe.cost(Mat.IRON)) {
            return gatherIron(ctx, forge, recipe, queue);
        }

        // Everything in hand — to the anvil, or the smithing table for netherite.
        phase = Phase.CRAFT;
        station = recipe.station() == Station.SMITHING ? forge.getSmithingTablePos() : forge.getAnvilPos();
        diary(ctx, "SMITH next: " + job.label() + " (" + job.reason() + ")"
                + (queue.size() > 1 ? ", then " + queue.size() + " queued" : ""));
        return startWalk(ctx, forge) ? Status.RUNNING : Status.FAILED;
    }

    /**
     * Ingots short: smelt what is carried, else fetch ingots, else fetch raw
     * iron in a furnace-sized batch. Leftover ingots are banked with the work.
     */
    private Status gatherIron(Context ctx, ForgeBuilding forge, Recipe recipe, List<Job> queue) {
        int ingots = countItem(ctx, Items.IRON_INGOT);
        int raw = countItem(ctx, Items.RAW_IRON);
        int shortForJob = recipe.cost(Mat.IRON) - ingots;
        int shortForQueue = Math.max(shortForJob, queueCost(queue, Mat.IRON) - ingots);

        if (raw >= shortForJob) {
            // Off to the furnace anyway: the kitchen's raw gold rides along.
            int ironLoad = Math.min(raw, MAX_SMELT_BATCH);
            Status gold = fetchRawGold(ctx, MAX_SMELT_BATCH - ironLoad);
            if (gold != null) return gold;
            if (smeltCredit < 1f && fuelCarried(ctx) == null) {
                if (storageWith(ctx, StorageBuilding.ResourceType.FUEL) == null) {
                    job = null;
                    return depositOrFinish(ctx);
                }
                int perCoal = Math.max(1, (int) BlacksmithBalance.smeltsPerCoal(level(ctx)));
                int load = ironLoad + goldForLoad(ctx, MAX_SMELT_BATCH - ironLoad);
                sub = FetchFromStorageActivity.forResource(StorageBuilding.ResourceType.FUEL,
                        Math.max(1, (load + perCoal - 1) / perCoal), "fetch_fuel");
                finishAfterSub = false;
                return Status.RUNNING;
            }
            phase = Phase.SMELT;
            station = forge.getBestFurnacePos();
            return startWalk(ctx, forge) ? Status.RUNNING : Status.FAILED;
        }

        int[] stored = storedIron(ctx, job != null && job.order() != null);
        if (stored[0] > 0) {
            sub = FetchFromStorageActivity.forItem(new ItemStack(Items.IRON_INGOT),
                    Math.min(stored[0], shortForQueue), "fetch_ingots");
            finishAfterSub = false;
            return Status.RUNNING;
        }
        if (stored[1] > 0) {
            // A full furnace load when there is that much: one coal smelts eight.
            int want = Math.min(stored[1], Math.min(MAX_SMELT_BATCH, Math.max(shortForQueue - raw, 8)));
            sub = FetchFromStorageActivity.forItem(new ItemStack(Items.RAW_IRON), want, "fetch_raw_iron");
            finishAfterSub = false;
            return Status.RUNNING;
        }
        job = null;
        return depositOrFinish(ctx);
    }

    private static int queueCost(List<Job> queue, Mat mat) {
        int total = 0;
        for (Job queued : queue) total += queued.cost(mat);
        return total;
    }

    // =========================================================================
    // Gold for the kitchen
    // =========================================================================

    /** Raw gold this load may take: what the kitchen still wants, carried first, at most {@code room}. */
    private static int goldForLoad(Context ctx, int room) {
        if (room <= 0) return 0;
        int wanted = GoldNeeds.ingotsWanted(ctx.mgr(), ctx.level());
        return Math.min(countItem(ctx, Items.RAW_GOLD), Math.min(room, wanted));
    }

    /**
     * Fetches the raw gold a furnace load has room for, when the kitchen wants
     * gold and storage has some. Null when there is nothing to fetch.
     */
    @Nullable
    private Status fetchRawGold(Context ctx, int room) {
        if (room <= 0 || goldPaused(ctx)) return null;
        int wanted = Math.min(room, GoldNeeds.ingotsWanted(ctx.mgr(), ctx.level()));
        int toFetch = wanted - countItem(ctx, Items.RAW_GOLD);
        if (toFetch <= 0) return null;
        int stored = storedRawGold(ctx);
        if (stored <= 0) return null;
        sub = FetchFromStorageActivity.forItem(new ItemStack(Items.RAW_GOLD),
                Math.min(stored, toFetch), FETCH_RAW_GOLD);
        finishAfterSub = false;
        return Status.RUNNING;
    }

    /**
     * With no forge job queued: fetch the raw gold the kitchen wants and the
     * fuel for it, then smelt it. Null when no gold is wanted, or there is
     * none to smelt or nothing to burn.
     */
    @Nullable
    private Status smeltGold(Context ctx, ForgeBuilding forge) {
        if (forge.getBestFurnacePos() == null) return null;
        Status fetch = fetchRawGold(ctx, MAX_SMELT_BATCH);
        if (fetch != null) return fetch;
        int load = goldForLoad(ctx, MAX_SMELT_BATCH);
        if (load <= 0) return null;
        if (smeltCredit < 1f && fuelCarried(ctx) == null) {
            if (storageWith(ctx, StorageBuilding.ResourceType.FUEL) == null) return null;
            int perCoal = Math.max(1, (int) BlacksmithBalance.smeltsPerCoal(level(ctx)));
            sub = FetchFromStorageActivity.forResource(StorageBuilding.ResourceType.FUEL,
                    Math.max(1, (load + perCoal - 1) / perCoal), "fetch_fuel");
            finishAfterSub = false;
            return Status.RUNNING;
        }
        job = new Job(SMELT_GOLD, null, "gold for the kitchen");
        phase = Phase.SMELT;
        station = forge.getBestFurnacePos();
        diary(ctx, "SMITH next: smelting " + load + " raw gold for the kitchen");
        return startWalk(ctx, forge) ? Status.RUNNING : Status.FAILED;
    }

    // =========================================================================
    // Spare ore to sell
    // =========================================================================

    /**
     * With nothing to forge and no gold for the kitchen: smelts the miner's
     * spare raw iron, raw gold and raw copper into ingots. The forge uses iron
     * and gold later as readily as raw ore, and meanwhile the tradesman can sell
     * them (iron 6, gold 7, copper 3 a stack), which raw ore never sells for.
     * Copper only ever sells, so it is fetched and smelted after the other two.
     * Only a furnace load of {@link #SPARE_MIN_LOAD} or more is worth a trip;
     * what is already carried always goes in. Null when there is nothing to do.
     */
    @Nullable
    private Status smeltSpare(Context ctx, ForgeBuilding forge) {
        if (!settings(ctx).getBool(BlacksmithSettingsSchema.SMELT_SPARE_ORE)) return null;
        if (forge.getBestFurnacePos() == null) return null;
        // Bank finished ingots first rather than stuffing the pack.
        if (freeSlots(ctx) < FREE_SLOTS_BEFORE_NEXT_JOB && DepositActivity.countDepositable(ctx) > 0) {
            sub = new DepositActivity();
            finishAfterSub = false;
            return Status.RUNNING;
        }
        int carried = carriedSpareOre(ctx);
        if (carried < MAX_SMELT_BATCH && !sparePaused(ctx)) {
            int storedIron = storedRaw(ctx, Items.RAW_IRON);
            int storedGold = storedRaw(ctx, Items.RAW_GOLD);
            int storedCopper = storedRaw(ctx, Items.RAW_COPPER);
            int stored = storedIron + storedGold + storedCopper;
            if (carried + stored >= SPARE_MIN_LOAD && stored > 0) {
                Item fetch = storedIron > 0 ? Items.RAW_IRON : storedGold > 0 ? Items.RAW_GOLD : Items.RAW_COPPER;
                int amount = Math.min(MAX_SMELT_BATCH - carried, fetch == Items.RAW_IRON ? storedIron
                        : fetch == Items.RAW_GOLD ? storedGold : storedCopper);
                sub = FetchFromStorageActivity.forItem(new ItemStack(fetch), amount, FETCH_SPARE_ORE);
                finishAfterSub = false;
                return Status.RUNNING;
            }
        }
        int load = Math.min(MAX_SMELT_BATCH, carried);
        if (load <= 0) return null;
        if (smeltCredit < 1f && fuelCarried(ctx) == null) {
            if (storageWith(ctx, StorageBuilding.ResourceType.FUEL) == null) return null;
            int perCoal = Math.max(1, (int) BlacksmithBalance.smeltsPerCoal(level(ctx)));
            sub = FetchFromStorageActivity.forResource(StorageBuilding.ResourceType.FUEL,
                    Math.max(1, (load + perCoal - 1) / perCoal), "fetch_fuel");
            finishAfterSub = false;
            return Status.RUNNING;
        }
        job = new Job(SMELT_SPARE, null, "spare ore to sell");
        phase = Phase.SMELT;
        station = forge.getBestFurnacePos();
        diary(ctx, "SMITH next: smelting " + load + " spare raw ore into ingots to sell");
        return startWalk(ctx, forge) ? Status.RUNNING : Status.FAILED;
    }

    private static boolean sparePaused(Context ctx) {
        Long until = SPARE_PAUSED_UNTIL.get(ctx.npc().getNpcId());
        if (until == null) return false;
        long now = ctx.level().getGameTime();
        if (now < until && until - now <= GOLD_PAUSE_TICKS) return true;
        SPARE_PAUSED_UNTIL.remove(ctx.npc().getNpcId());
        return false;
    }

    /** Raw iron, gold and copper in the smith's pack: what a spare ore visit smelts. */
    private static int carriedSpareOre(Context ctx) {
        return countItem(ctx, Items.RAW_IRON) + countItem(ctx, Items.RAW_GOLD) + countItem(ctx, Items.RAW_COPPER);
    }

    /** {@code item} in the village's storage. */
    private static int storedRaw(Context ctx, Item item) {
        int total = 0;
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()) {
                total += storage.countItem(ctx.level(), item);
            }
        }
        return total;
    }

    /** Whether there is spare ore to smelt and fuel for it: work for a smith with nothing to forge. */
    private static boolean hasSpareToSmelt(Context ctx, ForgeBuilding forge) {
        if (forge.getBestFurnacePos() == null
                || !settings(ctx).getBool(BlacksmithSettingsSchema.SMELT_SPARE_ORE)) return false;
        int carried = carriedSpareOre(ctx);
        if (carried <= 0 && (sparePaused(ctx) || storedRaw(ctx, Items.RAW_IRON) + storedRaw(ctx, Items.RAW_GOLD)
                + storedRaw(ctx, Items.RAW_COPPER) < SPARE_MIN_LOAD)) return false;
        return fuelCarried(ctx) != null || storageWith(ctx, StorageBuilding.ResourceType.FUEL) != null;
    }

    /** Whether this smith's last raw gold fetch failed within the last {@link #GOLD_PAUSE_TICKS}. */
    private static boolean goldPaused(Context ctx) {
        Long until = GOLD_PAUSED_UNTIL.get(ctx.npc().getNpcId());
        if (until == null) return false;
        long now = ctx.level().getGameTime();
        if (now < until && until - now <= GOLD_PAUSE_TICKS) return true;
        GOLD_PAUSED_UNTIL.remove(ctx.npc().getNpcId());
        return false;
    }

    /** Raw gold in the village's storage. */
    private static int storedRawGold(Context ctx) {
        int total = 0;
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()) {
                total += storage.countItem(ctx.level(), Items.RAW_GOLD);
            }
        }
        return total;
    }

    /** Whether there is raw gold the kitchen wants and fuel to smelt it: work for a smith with nothing to forge. */
    private static boolean hasGoldToSmelt(Context ctx, ForgeBuilding forge) {
        if (forge.getBestFurnacePos() == null) return false;
        if (GoldNeeds.ingotsWanted(ctx.mgr(), ctx.level()) <= 0) return false;
        if (countItem(ctx, Items.RAW_GOLD) <= 0 && (goldPaused(ctx) || storedRawGold(ctx) <= 0)) return false;
        return fuelCarried(ctx) != null || storageWith(ctx, StorageBuilding.ResourceType.FUEL) != null;
    }

    // =========================================================================
    // Planning
    // =========================================================================

    private static ProfessionSettings settings(Context ctx) {
        return ProfessionSettings.of(ctx.npc(), BlacksmithSettingsSchema.SCHEMA);
    }

    private static int level(Context ctx) {
        return Math.max(1, ctx.brain().blacksmithSkillLevel());
    }

    /** The next few jobs, most urgent first. Empty when the village wants nothing. */
    static List<Job> plan(Context ctx, float smeltCredit, ForgeBuilding forge) {
        return new Planner(ctx, smeltCredit, forge, true, null).run();
    }

    /**
     * Turns the village's stock into an ordered list of jobs.
     *
     * <p>Every job it queues is charged against a budget of what storage and the
     * smith's pack hold (minus what "Save materials for me" keeps back), and
     * counted as already made, so the queue never asks for the same axe twice
     * or for materials that are not there. A job that cannot be afforded at the
     * best material is tried one material lower; a job that cannot be afforded
     * at all is skipped, never half-fetched.
     */
    private static final class Planner {
        private final Context ctx;
        private final ForgeOrderBook.Stock stock;
        private final int[] budget = new int[MATS.length];
        private final int maxTier;
        private final int level;
        private final int focus;
        private final boolean recycle;
        private final ProfessionSettings settings;
        private final List<Job> jobs = new ArrayList<>();

        // What the queue will add, on top of stock.
        private final int[][] plannedTools = new int[ToolKind.values().length][ForgeOrderBook.TIER_NETHERITE + 1];
        private final int[][] plannedArmor = new int[ForgeOrderBook.ARMOR_SLOTS.length][ForgeOrderBook.TIER_NETHERITE + 1];
        private int plannedShields;
        private int plannedShears;
        private int plannedBuckets;
        /** Buckets the wash cauldrons and the rancher's milk lack (BucketNeeds), counted once per pass. */
        private final int farmBucketsShort;
        private final int milkBucketsShort;
        // Budget for the Stock tab rows: everything, "Save materials" included.
        private final int[] orderBudget = new int[MATS.length];
        // Highest tier the smith can make at all; rows ignore "Best material".
        private final int orderTier;

        /** Once orders already forged away, told to the Stock tab only at the away WRITE; null live. */
        @Nullable private final AwayOrderBook awayOrders;

        /**
         * @param meltDown   whether old tools may be queued for melting down: false away, where
         *                   they are never melted and only kept the queue's places (old bug S1)
         * @param awayOrders the new away calculator's orders made so far, taken off what the rows ask
         */
        Planner(Context ctx, float smeltCredit, ForgeBuilding forge, boolean meltDown,
                @Nullable AwayOrderBook awayOrders) {
            this.ctx = ctx;
            this.awayOrders = awayOrders;
            ProfessionSettings settings = settings(ctx);
            this.settings = settings;
            this.level = level(ctx);
            this.focus = settings.getInt(BlacksmithSettingsSchema.FOCUS);
            this.recycle = meltDown && settings.getBool(BlacksmithSettingsSchema.RECYCLE);
            int cap = switch (settings.getInt(BlacksmithSettingsSchema.BEST_MATERIAL)) {
                case BlacksmithSettingsSchema.MATERIAL_STONE -> ForgeOrderBook.TIER_STONE;
                case BlacksmithSettingsSchema.MATERIAL_IRON -> ForgeOrderBook.TIER_IRON;
                case BlacksmithSettingsSchema.MATERIAL_DIAMOND -> ForgeOrderBook.TIER_DIAMOND;
                default -> ForgeOrderBook.TIER_NETHERITE;
            };
            int tier = Math.min(cap, BlacksmithBalance.maxTier(level));
            // Netherite needs somewhere to do the upgrade.
            if (tier == ForgeOrderBook.TIER_NETHERITE && forge.getSmithingTablePos() == null) {
                tier = ForgeOrderBook.TIER_DIAMOND;
            }
            this.maxTier = tier;
            int skillTier = BlacksmithBalance.maxTier(level);
            if (skillTier == ForgeOrderBook.TIER_NETHERITE && forge.getSmithingTablePos() == null) {
                skillTier = ForgeOrderBook.TIER_DIAMOND;
            }
            this.orderTier = skillTier;
            this.stock = ForgeOrderBook.stock(ctx);
            this.farmBucketsShort = BucketNeeds.farmBucketsToMake(ctx.mgr(), ctx.level());
            this.milkBucketsShort = BucketNeeds.milkBucketsToMake(ctx.mgr(), ctx.level());
            fillBudget(settings.getInt(BlacksmithSettingsSchema.SAVE_MATERIALS), smeltCredit);
        }

        private void fillBudget(int save, float smeltCredit) {
            Map<Item, Integer> stored = storedTotals(ctx);
            for (Mat mat : MATS) {
                if (mat.item == null || mat == Mat.IRON) continue;
                budget[mat.ordinal()] = countMat(ctx, mat) + storedMat(stored, mat);
            }
            budget[Mat.DIAMOND.ordinal()] = countItem(ctx, Items.DIAMOND) + Math.max(0,
                    stored.getOrDefault(Items.DIAMOND, 0)
                            - BlacksmithSettingsSchema.diamondReserve(save));
            // Raw iron only counts when something can smelt it.
            boolean fuel = smeltCredit >= 1f || fuelCarried(ctx) != null
                    || storageWith(ctx, StorageBuilding.ResourceType.FUEL) != null;
            int storedIron = stored.getOrDefault(Items.IRON_INGOT, 0)
                    + (fuel ? stored.getOrDefault(Items.RAW_IRON, 0) : 0);
            budget[Mat.IRON.ordinal()] = countItem(ctx, Items.IRON_INGOT)
                    + (fuel ? countItem(ctx, Items.RAW_IRON) : 0)
                    + Math.max(0, storedIron - BlacksmithSettingsSchema.ironReserve(save));
            // Handles, as payment counts them: a log is one, two planks are one (a stored plank
            // counted as a whole handle planned twice the tools the wood pays for, live and away).
            budget[Mat.WOOD.ordinal()] = woodUnits(ctx) + storedWoodUnits(ctx);
            // Templates the player keeps in the Stock tab are not the smith's to spend.
            budget[Mat.TEMPLATE.ordinal()] = Math.max(0, budget[Mat.TEMPLATE.ordinal()]
                    - reserves().lockedAmount(ctx.mgr().getVillageId(),
                            Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
            // The player's own rows may spend what "Save materials" keeps back for them.
            System.arraycopy(budget, 0, orderBudget, 0, MATS.length);
            orderBudget[Mat.DIAMOND.ordinal()] = countItem(ctx, Items.DIAMOND)
                    + stored.getOrDefault(Items.DIAMOND, 0);
            orderBudget[Mat.IRON.ordinal()] = countItem(ctx, Items.IRON_INGOT)
                    + (fuel ? countItem(ctx, Items.RAW_IRON) : 0) + storedIron;
        }

        List<Job> run() {
            // The player's Stock tab rows come before anything the village needs.
            playerRows();
            switch (focus) {
                case BlacksmithSettingsSchema.FOCUS_WORKERS -> {
                    urgentTools(ToolKind.PICKAXE, ToolKind.AXE, ToolKind.HOE, ToolKind.SWORD);
                    recycling();
                    shears();
                    farmBuckets();
                    templateCopy();
                    upgrades(ToolKind.PICKAXE, ToolKind.AXE, ToolKind.HOE);
                    defenderBasics();
                    upgrades(ToolKind.SWORD);
                    armorUpgrades();
                }
                case BlacksmithSettingsSchema.FOCUS_GUARDS -> {
                    urgentTools(ToolKind.SWORD);
                    defenderBasics();
                    templateCopy();
                    upgrades(ToolKind.SWORD);
                    armorUpgrades();
                    urgentTools(ToolKind.PICKAXE, ToolKind.AXE, ToolKind.HOE);
                    recycling();
                    shears();
                    farmBuckets();
                    upgrades(ToolKind.PICKAXE, ToolKind.AXE, ToolKind.HOE);
                }
                default -> {
                    urgentTools(ToolKind.PICKAXE, ToolKind.AXE, ToolKind.HOE, ToolKind.SWORD);
                    recycling();
                    shears();
                    farmBuckets();
                    defenderBasics();
                    templateCopy();
                    upgrades(ToolKind.PICKAXE, ToolKind.SWORD);
                    armorUpgrades();
                    upgrades(ToolKind.AXE, ToolKind.HOE);
                }
            }
            // Milk is for cake, a treat: only once the village's own gear is seen to.
            milkBuckets();
            spares();
            return jobs;
        }

        private boolean full() {
            return jobs.size() >= QUEUE_SIZE;
        }

        private boolean affordable(@Nullable Recipe recipe) {
            if (recipe == null) return false;
            // The Products tab: an item switched off is never made.
            if (!settings.isAllowed(BlacksmithSettingsSchema.PRODUCTS,
                    BuiltInRegistries.ITEM.getKey(recipe.result()).getPath())) return false;
            for (Mat mat : MATS) {
                if (budget[mat.ordinal()] < recipe.cost(mat)) return false;
            }
            return true;
        }

        /** Queues a job and charges its materials; it counts towards what the workers still need. */
        private void take(Recipe recipe, String reason) {
            for (Mat mat : MATS) {
                budget[mat.ordinal()] -= recipe.cost(mat);
                orderBudget[mat.ordinal()] -= recipe.cost(mat);
            }
            if (recipe.kind() != null) plannedTools[recipe.kind().ordinal()][recipe.tier()]++;
            if (recipe.armorSlot() >= 0) plannedArmor[recipe.armorSlot()][recipe.tier()]++;
            if (recipe == SHIELD) plannedShields++;
            if (recipe == SHEARS) plannedShears++;
            if (recipe == BUCKET) plannedBuckets++;
            jobs.add(new Job(recipe, null, reason));
        }

        private int freshTools(ToolKind kind) {
            int planned = 0;
            for (int n : plannedTools[kind.ordinal()]) planned += n;
            return stock.fresh(kind) + planned;
        }

        private int toolsAtLeast(ToolKind kind, int tier) {
            int planned = 0;
            for (int t = tier; t <= ForgeOrderBook.TIER_NETHERITE; t++) planned += plannedTools[kind.ordinal()][t];
            return stock.ownedAtLeast(kind, tier) + planned;
        }

        private int armorAtLeast(int slot, int tier) {
            int planned = 0;
            for (int t = tier; t <= ForgeOrderBook.TIER_NETHERITE; t++) planned += plannedArmor[slot][t];
            return stock.armorAtLeast(slot, tier) + planned;
        }

        /** The best tool of this kind from {@code top} down to {@code bottom} that the budget covers. */
        private boolean bestTool(ToolKind kind, int top, int bottom, String reason) {
            for (int tier = top; tier >= bottom; tier--) {
                Recipe recipe = toolRecipe(kind, tier);
                if (affordable(recipe)) {
                    take(recipe, reason);
                    return true;
                }
            }
            return false;
        }

        private boolean bestArmor(int slot, int top, String reason) {
            for (int tier = top; tier >= ForgeOrderBook.TIER_WOOD; tier--) {
                Recipe recipe = armorRecipe(slot, tier);
                if (affordable(recipe)) {
                    take(recipe, reason);
                    return true;
                }
            }
            return false;
        }

        /** Someone has no tool of this kind, or theirs is in its last quarter. */
        private void urgentTools(ToolKind... kinds) {
            for (ToolKind kind : kinds) {
                int users = stock.users(kind);
                while (!full() && users > 0 && freshTools(kind) < users) {
                    if (!bestTool(kind, maxTier, ForgeOrderBook.TIER_STONE,
                            "a " + name(kind) + " is missing or wearing out")) break;
                }
            }
        }

        private void recycling() {
            if (!recycle) return;
            for (ForgeOrderBook.Recyclable old : stock.recyclables()) {
                if (full()) return;
                jobs.add(new Job(null, old, "unclaimed for a day"));
            }
        }

        private void shears() {
            while (!full() && stock.freshShears() + plannedShears < stock.shearsUsers()
                    && maxTier >= ForgeOrderBook.TIER_IRON && affordable(SHEARS)) {
                take(SHEARS, "shears for the flock or the berries");
            }
        }

        /**
         * A bucket or two for the wash cauldrons. Cheap, and the farmers bring
         * them back, so it comes early.
         */
        private void farmBuckets() {
            buckets(farmBucketsShort, "a bucket for the wash cauldrons");
        }

        /**
         * Buckets for the rancher to milk while the cook wants a cake: the cake
         * uses the bucket up, so only one cake's worth is kept going round.
         */
        private void milkBuckets() {
            buckets(farmBucketsShort + milkBucketsShort, "a bucket for the rancher's milk");
        }

        /** Buckets until {@code wanted} are planned in this pass. */
        private void buckets(int wanted, String reason) {
            while (!full() && plannedBuckets < wanted
                    && maxTier >= ForgeOrderBook.TIER_IRON && affordable(BUCKET)) {
                take(BUCKET, reason);
            }
        }

        /** A shield per guard and captain, and any armour at all in every defender's slots. */
        private void defenderBasics() {
            while (!full() && stock.freshShields() + plannedShields < stock.shieldUsers()
                    && maxTier >= ForgeOrderBook.TIER_IRON && affordable(SHIELD)) {
                take(SHIELD, "a shield for the watch");
            }
            int defenders = stock.defenders();
            for (int slot = 0; slot < ForgeOrderBook.ARMOR_SLOTS.length; slot++) {
                while (!full() && defenders > 0 && armorAtLeast(slot, ForgeOrderBook.TIER_WOOD) < defenders) {
                    if (!bestArmor(slot, maxTier, "armour for the watch")) break;
                }
            }
        }

        /**
         * Brings every user of a kind up to the best tier. The highest unmet
         * tier the budget covers is made; if the top one is too dear, the next
         * one down still moves somebody up.
         */
        private void upgrades(ToolKind... kinds) {
            for (ToolKind kind : kinds) {
                int users = stock.users(kind);
                if (users == 0) continue;
                tiers:
                while (!full()) {
                    // Down to stone, so wooden tools a worker made itself get replaced too.
                    for (int tier = maxTier; tier >= ForgeOrderBook.TIER_STONE; tier--) {
                        if (toolsAtLeast(kind, tier) >= users) break tiers;
                        Recipe recipe = toolRecipe(kind, tier);
                        if (affordable(recipe)) {
                            take(recipe, "upgrading a " + name(kind));
                            continue tiers;
                        }
                    }
                    break;
                }
            }
        }

        private void armorUpgrades() {
            int defenders = stock.defenders();
            if (defenders == 0) return;
            for (int slot = 0; slot < ForgeOrderBook.ARMOR_SLOTS.length; slot++) {
                slots:
                while (!full()) {
                    for (int tier = maxTier; tier >= ForgeOrderBook.TIER_IRON; tier--) {
                        if (armorAtLeast(slot, tier) >= defenders) break slots;
                        Recipe recipe = armorRecipe(slot, tier);
                        if (affordable(recipe)) {
                            take(recipe, "better armour for the watch");
                            continue slots;
                        }
                    }
                    break;
                }
            }
        }

        /**
         * Level 100: keep a second netherite template, so the last one is never
         * spent on an upgrade with no way to get another.
         */
        private void templateCopy() {
            if (full() || level < BlacksmithBalance.TEMPLATE_LEVEL) return;
            int templates = budget[Mat.TEMPLATE.ordinal()];
            if (templates >= 1 && templates < 2 && affordable(TEMPLATE_COPY)) {
                take(TEMPLATE_COPY, "copying the netherite template");
            }
        }

        /**
         * One spare per kind for the whole village, at iron at most -- diamonds
         * go into hands, not onto shelves. With nobody using a kind yet, one
         * cheap stone tool so the next hire is not empty-handed.
         */
        private StockReserves reserves() {
            return StockReserves.get(ctx.level().getServer());
        }

        /**
         * The storage room's Stock tab rows: once orders oldest first, then what the
         * always rows are short. Made as exactly that item, within the smith's skill and
         * stations only: the Products tab, "Best material" and "Save materials" are the
         * village's limits, not the player's. Whatever cannot be queued now keeps its
         * materials out of the village's own jobs below.
         */
        private void playerRows() {
            int[] holdBack = new int[MATS.length];
            for (StockReserves.Want want : reserves().wants(ctx.mgr(), ctx.level(),
                    Profession.BLACKSMITH, ctx.npc().getNpcId())) {
                Recipe recipe = recipeFor(want.item());
                if (recipe == null) continue;
                boolean able = recipe.tier() <= orderTier
                        && (recipe != TEMPLATE_COPY || level >= BlacksmithBalance.TEMPLATE_LEVEL);
                int units = want.units();
                if (awayOrders != null && want.once() && want.row() != null) units -= awayOrders.madeAway(want.row());
                while (able && units > 0 && !full() && orderAffordable(recipe)) {
                    takeOrder(recipe, want);
                    units -= recipe.count();
                }
                if (units <= 0) continue;
                int batches = (units + recipe.count() - 1) / recipe.count();
                for (Mat mat : MATS) holdBack[mat.ordinal()] += recipe.cost(mat) * batches;
            }
            for (Mat mat : MATS) {
                if (mat != Mat.WOOD) budget[mat.ordinal()] -= holdBack[mat.ordinal()];
            }
        }

        private boolean orderAffordable(Recipe recipe) {
            for (Mat mat : MATS) {
                if (orderBudget[mat.ordinal()] < recipe.cost(mat)) return false;
            }
            return true;
        }

        /** Queues a job for a row. It is the player's work, not the workers', so no worker need counts it. */
        private void takeOrder(Recipe recipe, StockReserves.Want want) {
            for (Mat mat : MATS) {
                budget[mat.ordinal()] -= recipe.cost(mat);
                orderBudget[mat.ordinal()] -= recipe.cost(mat);
            }
            jobs.add(new Job(recipe, null, want.once() ? "ordered in the storage room" : "kept in stock", want));
        }

        private void spares() {
            for (ToolKind kind : ToolKind.values()) {
                if (full()) return;
                int users = stock.users(kind);
                if (users > 0) {
                    if (freshTools(kind) < users + 1) {
                        bestTool(kind, Math.min(maxTier, ForgeOrderBook.TIER_IRON),
                                ForgeOrderBook.TIER_STONE, "a spare " + name(kind));
                    }
                } else if (toolsAtLeast(kind, ForgeOrderBook.TIER_STONE) == 0) {
                    bestTool(kind, ForgeOrderBook.TIER_STONE, ForgeOrderBook.TIER_STONE,
                            "a " + name(kind) + " for the next hire");
                }
            }
        }
    }

    private static String name(ToolKind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Usable totals of every plan material across valid storage rooms, in one pass per item. */
    private static Map<Item, Integer> storedTotals(Context ctx) {
        Map<Item, Integer> totals = new java.util.HashMap<>();
        Item[] items = {Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE,
                Items.IRON_INGOT, Items.RAW_IRON, Items.LEATHER,
                Items.DIAMOND, Items.NETHERITE_INGOT, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                Items.NETHERRACK};
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (Item item : items) {
                int n = storage.countItem(ctx.level(), item);
                if (n > 0) totals.merge(item, n, Integer::sum);
            }
        }
        return totals;
    }

    /**
     * What storage holds of a plain material, minus the player's diamond reserve. A Stock
     * tab row may use the reserve: the player asked for it.
     */
    private static int storedAvailable(Context ctx, Mat mat, boolean forOrder) {
        int stored = storedMat(storedTotals(ctx), mat);
        if (mat == Mat.DIAMOND && !forOrder) {
            stored -= BlacksmithSettingsSchema.diamondReserve(
                    settings(ctx).getInt(BlacksmithSettingsSchema.SAVE_MATERIALS));
        }
        return Math.max(0, stored);
    }

    /** {ingots, raw iron} the smith may take from storage, ingots first, after the reserve. */
    private static int[] storedIron(Context ctx, boolean forOrder) {
        Map<Item, Integer> stored = storedTotals(ctx);
        int ingots = stored.getOrDefault(Items.IRON_INGOT, 0);
        int raw = stored.getOrDefault(Items.RAW_IRON, 0);
        int free = forOrder ? ingots + raw : Math.max(0, ingots + raw - BlacksmithSettingsSchema.ironReserve(
                settings(ctx).getInt(BlacksmithSettingsSchema.SAVE_MATERIALS)));
        int takeIngots = Math.min(ingots, free);
        int takeRaw = Math.min(raw, free - takeIngots);
        return new int[]{takeIngots, takeRaw};
    }

    // =========================================================================
    // Station work
    // =========================================================================

    /**
     * Heads for the station, or starts work if already beside it.
     *
     * @return false when there is nowhere to stand; the caller ends the
     *         session, since planning again next tick finds the same station
     */
    private boolean startWalk(Context ctx, ForgeBuilding forge) {
        if (station == null) {
            job = null;
            phase = null;
            return false;
        }
        // At the anvil, one of its two long sides when he can (ForgeBuilding.getStandBeside).
        stand = forge.getStandBeside(ctx.level(), station, ctx.mgr().getGraph(), ctx.npc().blockPosition());
        if (stand == null) {
            diary(ctx, "SMITH no stand beside " + station);
            job = null;
            phase = null;
            return false;
        }
        // All the way onto the tile, then the last step onto its middle (settling). A walk that counted as
        // arrived within 2 blocks, and a start from up to a tile off, measured through walls: he worked
        // outside the forge, against the wall behind the anvil (players saw it, 10-25).
        if (NavGraph.feetCell(ctx.npc()).equals(stand)) {
            settling = new CraftingStation(station);
        } else {
            move = new MoveActivity(stand, VillagerModel.WALK_SPEED, true);
        }
        return true;
    }

    private void beginStationWork(Context ctx) {
        if (job == null || phase == null || station == null) return;
        int level = level(ctx);
        switch (phase) {
            case SMELT -> {
                int raw = Math.min(countItem(ctx, Items.RAW_IRON), MAX_SMELT_BATCH);
                // Raw gold goes in after the iron: what the kitchen wants, or on
                // a spare ore visit, all that is carried.
                goldThisLoad = job.recipe() == SMELT_SPARE
                        ? Math.min(countItem(ctx, Items.RAW_GOLD), MAX_SMELT_BATCH - raw)
                        : goldForLoad(ctx, MAX_SMELT_BATCH - raw);
                // Copper is never forged, so it only goes in on a spare ore visit, last.
                copperThisLoad = job.recipe() == SMELT_SPARE
                        ? Math.min(countItem(ctx, Items.RAW_COPPER), MAX_SMELT_BATCH - raw - goldThisLoad)
                        : 0;
                int load = raw + goldThisLoad + copperThisLoad;
                float perCoal = BlacksmithBalance.smeltsPerCoal(level);
                while (smeltCredit < load) {
                    Item fuel = fuelCarried(ctx);
                    if (fuel == null || !consumeItems(ctx, fuel, 1)) break;
                    smeltCredit += fuel == Items.COAL || fuel == Items.CHARCOAL
                            ? perCoal : perCoal * WOOD_FUEL_SHARE;
                }
                smeltCount = Math.min(load, (int) smeltCredit);
                if (smeltCount <= 0) {
                    phase = null;
                    job = null;
                    return;
                }
                smeltCredit -= smeltCount;
                boolean blast = ctx.level().getBlockState(station).getBlock() instanceof BlastFurnaceBlock;
                smeltTicksPerItem = Math.max(1, BlacksmithBalance.smeltTicksPerItem(level) / (blast ? 2 : 1));
                workTimer = smeltCount * smeltTicksPerItem;
                lightFurnace(ctx, true);
            }
            case CRAFT, RECYCLE -> workTimer = BlacksmithBalance.craftTicks(level);
        }
        // Hammer at the anvil (one of two ways, per job), both hands at the smithing table,
        // ore into the furnace: tools/forge/ForgeTool.java.
        workClip = switch (phase) {
            case SMELT -> VillagerAnimationState.SMITH_FURNACE;
            case RECYCLE -> VillagerAnimationState.SMITH_RECYCLE;
            case CRAFT -> job.recipe() != null && job.recipe().station() == Station.SMITHING
                    ? VillagerAnimationState.SMITH_SMITHING : SmithStrikes.anvilClip(ctx.level().random);
        };
        clipTicks = 0;
        // Drawing out a bar starts afresh every job (a new work sequence): each client times its clinks from it (SmithBlows).
        if (workClip == VillagerAnimationState.SMITH_ANVIL_DRAW) ctx.npc().startWorkAnimation(workClip, 1f);
        else ctx.npc().setAnimation(workClip);
        ctx.npc().getLookControl().setLookAt(
                station.getX() + 0.5, station.getY() + 0.5, station.getZ() + 0.5, 30f, 30f);
        diary(ctx, "SMITH " + phase + (phase == Phase.SMELT ? " x" + smeltCount : " " + job.label()));
    }

    private void tickStationWork(Context ctx) {
        // Square to the station the whole time (a player's window turns him to face them).
        CraftingStation.face(ctx.npc(), station);
        workTimer--;
        SmithStrikes.tick(ctx.level(), station, workClip, clipTicks++);
        if (++stationTicks >= WORK_XP_TICKS) {
            stationTicks = 0;
            ctx.brain().onBlacksmithExperience(ctx, BlacksmithBalance.WORK_POINTS_PER_MINUTE);
        }

        if (phase == Phase.SMELT && workTimer % smeltTicksPerItem == 0) {
            Item ingot = null;
            if (consumeItems(ctx, Items.RAW_IRON, 1)) {
                ingot = Items.IRON_INGOT;
            } else if (goldThisLoad > 0 && consumeItems(ctx, Items.RAW_GOLD, 1)) {
                goldThisLoad--;
                ingot = Items.GOLD_INGOT;
                GoldNeeds.forget(ctx.mgr());
            } else if (copperThisLoad > 0 && consumeItems(ctx, Items.RAW_COPPER, 1)) {
                copperThisLoad--;
                ingot = Items.COPPER_INGOT;
            }
            if (ingot != null) {
                ctx.npc().getInventory().addItem(VillageItemOwnership.produce(
                        new ItemStack(ingot), ctx.mgr()));
                ctx.level().playSound(null, station, SoundEvents.FURNACE_FIRE_CRACKLE,
                        SoundSource.BLOCKS, 0.5f, 1.0f);
                ctx.brain().onBlacksmithExperience(ctx, BlacksmithBalance.SMELT_POINTS_PER_INGOT);
            }
        }

        if (workTimer > 0) return;

        if (phase == Phase.CRAFT && job != null && job.recipe() != null) {
            finishCraft(ctx, job.recipe());
        } else if (phase == Phase.RECYCLE && job != null) {
            finishRecycle(ctx);
        }
        extinguishFurnace(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        if (phase == Phase.SMELT) workedThisSession = true;
        phase = null;
        job = null;
    }

    private void finishCraft(Context ctx, Recipe recipe) {
        boolean paid = true;
        for (Mat mat : MATS) {
            int n = recipe.cost(mat);
            if (n == 0) continue;
            paid &= mat == Mat.WOOD ? consumeWood(ctx) : consumeMat(ctx, mat, n);
        }
        if (!paid) return;
        int level = level(ctx);
        ItemStack made = VillageItemOwnership.produce(new ItemStack(recipe.result(), recipe.count()), ctx.mgr());
        boolean tempered = made.isDamageableItem()
                && ctx.level().random.nextFloat() < BlacksmithBalance.temperedChance(level);
        if (tempered) {
            Holder<Enchantment> unbreaking = ctx.level().registryAccess()
                    .registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.UNBREAKING);
            made.enchant(unbreaking, 1);
        }
        ctx.npc().getInventory().addItem(made);
        if (recipe == BUCKET) BucketNeeds.forget(ctx.mgr());
        ctx.level().playSound(null, station, recipe.station() == Station.SMITHING
                        ? SoundEvents.SMITHING_TABLE_USE : SoundEvents.ANVIL_USE,
                SoundSource.BLOCKS, 0.7f, 1.0f);
        diary(ctx, "SMITH crafted " + job.label() + (tempered ? " (tempered)" : ""));
        if (job.order() != null && job.order().once()) {
            StockReserves.get(ctx.level().getServer()).onMade(ctx.mgr().getVillageId(), job.order().row(),
                    ctx.npc().getNpcId(), recipe.result(), recipe.count());
        }
        float points = BlacksmithBalance.craftPoints(Math.max(0, recipe.tier()));
        if (recipe.armorSlot() >= 0) points *= ARMOR_COST[recipe.armorSlot()] / 4f;
        ctx.brain().onBlacksmithCraft(ctx, points);
        workedThisSession = true;
    }

    /**
     * Melts the old tool down. Material comes back in proportion to the
     * durability left and the smith's recovery rate; fractions go into a running
     * total kept on the smith, and only whole items come out.
     */
    private void finishRecycle(Context ctx) {
        int slot = carriedRecyclableSlot(ctx);
        if (slot < 0) return;
        InventoryManager inv = ctx.npc().getInventory();
        ItemStack old = inv.getSlot(slot).copyWithCount(1);
        ForgeRecycling.Salvage salvage = ForgeRecycling.salvageOf(old.getItem());
        if (salvage == null || !inv.reduceSlot(slot, 1)) return;
        float share = ForgeRecycling.durabilityLeft(old) * BlacksmithBalance.recoveryRate(level(ctx));
        int back = credit(ctx, salvage.material(), salvage.count() * share);
        if (salvage.netheriteScrap() > 0) {
            credit(ctx, Items.NETHERITE_SCRAP, salvage.netheriteScrap() * share);
        }
        ctx.level().playSound(null, station, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.7f, 1.0f);
        diary(ctx, String.format("SMITH recycled %s at %d%% -> %d %s",
                old.getHoverName().getString(), Math.round(ForgeRecycling.durabilityLeft(old) * 100),
                back, salvage.material().getDescription().getString()));
        ctx.brain().onBlacksmithExperience(ctx, BlacksmithBalance.RECYCLE_POINTS);
        workedThisSession = true;
    }

    private static final String TAG_SCRAP = "SmithScrap";

    /** Adds to the smith's running total of {@code material} and hands out the whole items. */
    private static int credit(Context ctx, Item material, float amount) {
        CompoundTag data = ctx.npc().getBrainData();
        CompoundTag scrap = data.getCompound(TAG_SCRAP);
        String key = BuiltInRegistries.ITEM.getKey(material).toString();
        float total = scrap.getFloat(key) + amount;
        int whole = (int) Math.floor(total + 1e-4f);
        scrap.putFloat(key, Math.max(0f, total - whole));
        data.put(TAG_SCRAP, scrap);
        if (whole > 0) {
            ItemStack out = VillageItemOwnership.produce(new ItemStack(material, whole), ctx.mgr());
            ctx.npc().getInventory().addItem(out);
        }
        return whole;
    }

    /** Slot of a carried tool that is stamped and due for melting down, or -1. */
    private static int carriedRecyclableSlot(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        long now = ctx.level().getGameTime();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            if (inv.mayUseAsMaterial(inv.getSlot(i)) && ForgeRecycling.isRecyclable(inv.getSlot(i), now)) return i;
        }
        return -1;
    }

    /** Empty inventory slots the villager still has. */
    private static int freeSlots(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        int free = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            if (inv.getSlot(i).isEmpty()) free++;
        }
        return free;
    }

    private Status depositOrFinish(Context ctx) {
        if (DepositActivity.countDepositable(ctx) > 0) {
            sub = new DepositActivity();
            finishAfterSub = true;
            return Status.RUNNING;
        }
        return workedThisSession ? Status.DONE : Status.FAILED;
    }

    // =========================================================================
    // Furnace theatre
    // =========================================================================

    private void lightFurnace(Context ctx, boolean lit) {
        if (station == null) return;
        BlockState state = ctx.level().getBlockState(station);
        if (state.getBlock() instanceof AbstractFurnaceBlock
                && state.hasProperty(AbstractFurnaceBlock.LIT)) {
            ctx.level().setBlock(station, state.setValue(AbstractFurnaceBlock.LIT, lit), 3);
            furnaceLit = lit;
        }
    }

    private void extinguishFurnace(Context ctx) {
        if (furnaceLit) lightFurnace(ctx, false);
    }

    // =========================================================================
    // Inventory helpers
    // =========================================================================

    private static int countItem(Context ctx, Item item) {
        int total = 0;
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (stack.getItem() == item && inv.mayUseAsMaterial(stack)) total += stack.getCount();
        }
        return total;
    }

    /** Handles the pack pays for: one per log, one per two planks. */
    private static int woodUnits(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        int logs = 0;
        int planks = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!inv.mayUseAsMaterial(stack)) continue;
            if (stack.is(ItemTags.LOGS)) logs += stack.getCount();
            else if (stack.is(ItemTags.PLANKS)) planks += stack.getCount();
        }
        return logs + planks / 2;
    }

    /** One log or two planks pays the stick cost of any recipe. */
    private static boolean consumeWood(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (inv.mayUseAsMaterial(stack) && stack.is(ItemTags.LOGS)) {
                return inv.reduceSlot(i, 1);
            }
        }
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (inv.mayUseAsMaterial(stack) && stack.is(ItemTags.PLANKS)) {
                return inv.reduceSlot(i, Math.min(2, stack.getCount()));
            }
        }
        return false;
    }

    @Nullable
    private static Item fuelCarried(Context ctx) {
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!inv.mayUseAsMaterial(stack)) continue;
            // Must match StorageBuilding's FUEL resource type exactly: fetch_fuel
            // can hand back a log or plank, and a narrower check here would never
            // recognize it as fuel, sending the smith right back for another one.
            if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)
                    || stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS)) {
                return stack.getItem();
            }
        }
        return null;
    }

    /** What the pack holds of everything that pays for {@code mat}. */
    private static int countMat(Context ctx, Mat mat) {
        int total = 0;
        for (Item item : mat.items) total += countItem(ctx, item);
        return total;
    }

    /** {@code stored}'s total of everything that pays for {@code mat}. */
    private static int storedMat(Map<Item, Integer> stored, Mat mat) {
        int total = 0;
        for (Item item : mat.items) total += stored.getOrDefault(item, 0);
        return total;
    }

    /** The item of {@code mat} storage holds most of; {@link Mat#item} on a tie or when there is none. */
    private static Item mostStored(Map<Item, Integer> stored, Mat mat) {
        Item best = mat.item;
        for (Item item : mat.items) {
            if (stored.getOrDefault(item, 0) > stored.getOrDefault(best, 0)) best = item;
        }
        return best;
    }

    /** Uses {@code amount} of {@code mat} from the pack, {@link Mat#item} first. */
    private static boolean consumeMat(Context ctx, Mat mat, int amount) {
        int remaining = amount;
        for (Item item : mat.items) {
            int take = Math.min(remaining, countItem(ctx, item));
            if (take > 0 && consumeItems(ctx, item, take)) remaining -= take;
        }
        return remaining == 0;
    }

    private static boolean consumeItems(Context ctx, Item item, int amount) {
        InventoryManager inv = ctx.npc().getInventory();
        int remaining = amount;
        for (int i = 0; i < InventoryManager.SIZE && remaining > 0; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!inv.mayUseAsMaterial(stack) || stack.getItem() != item) continue;
            int take = Math.min(remaining, stack.getCount());
            inv.reduceSlot(i, take);
            remaining -= take;
        }
        return remaining == 0;
    }

    // =========================================================================
    // Village queries
    // =========================================================================

    @Nullable
    private static ForgeBuilding findForge(Context ctx) {
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof ForgeBuilding forge && forge.isValid()) return forge;
        }
        return null;
    }

    @Nullable
    private static StorageBuilding storageWith(Context ctx, StorageBuilding.ResourceType type) {
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()
                    && storage.hasResource(ctx.level(), type)) {
                return storage;
            }
        }
        return null;
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    /** Cheap pre-check for brain scoring: is there anything to make or melt down? */
    public static boolean hasWork(Context ctx) {
        if (!settings(ctx).getBool(BlacksmithSettingsSchema.WORK_ENABLED)) return false;
        ForgeBuilding forge = findForge(ctx);
        if (forge == null) return false;
        return carriedRecyclableSlot(ctx) >= 0 || !plan(ctx, 0f, forge).isEmpty()
                || hasGoldToSmelt(ctx, forge) || hasSpareToSmelt(ctx, forge);
    }


    // =========================================================================
    // Offline catch-up
    // =========================================================================

    /** Planning passes the catch-up runs at most; each queues up to {@link #QUEUE_SIZE} jobs. */
    private static final int OFFLINE_MAX_PASSES = 250;

    /** Spare ore a smelting visit takes, in the live order: iron, gold, then copper (only ever sold). */
    private static final Item[][] SPARE_ORE = {
            {Items.RAW_IRON, Items.IRON_INGOT}, {Items.RAW_GOLD, Items.GOLD_INGOT}, {Items.RAW_COPPER, Items.COPPER_INGOT}};

    /**
     * What the smith made while nobody was near.
     *
     * @param made     items made (into storage; away, a Stock tab order's into the smith's pack)
     * @param used     materials taken, raw ore and fuel included
     * @param points   forge points earned, before the daily rule (away: already paid)
     * @param jobs     items forged
     * @param smelted  ingots smelted on their own: gold for the kitchen, spare ore to sell
     * @param sessions queues worked through (live fetches a queue's materials together and banks together)
     * @param ticks    work time used
     * @param stop     why the work ended while there was more to do ("time", "storage full",
     *                 "no fuel to smelt"), else null
     */
    public record OfflineWork(Map<Item, Integer> made, Map<Item, Integer> used, float points, int jobs,
                              int smelted, int sessions, long ticks, @Nullable String stop) {}

    /**
     * Where the new away calculator keeps the Stock tab once orders forged away: told to
     * the Stock tab at its WRITE, so a preview, or a catch-up begun again after a restart,
     * changes no order.
     */
    public interface AwayOrderBook {
        /** Units already made away for once row {@code row}. */
        int madeAway(UUID row);

        /** {@code count} of {@code item} made for {@code row}; {@code stored}: put in storage, else kept in the pack. */
        void made(UUID row, Item item, UUID maker, int count, boolean stored);
    }

    /**
     * What the away calculator ({@code economy/away/jobs/BlacksmithAway}) gives {@link #workOffline}.
     *
     * @param payNow    forge points paid at once, job by job, so a level-up counts for the next job (S7)
     * @param tripTicks a storage trip per queue and per furnace load
     * @param orders    the run's once orders, told at WRITE
     */
    public record AwayHooks(java.util.function.Consumer<Float> payNow, long tripTicks, AwayOrderBook orders) {}

    /**
     * The smith's work while the village was unloaded, planned exactly as live:
     * only what the village is short of (missing and worn tools, a pair of shears
     * per rancher and per farmer growing berries or cocoa, a shield per guard, armour, upgrades, one spare per
     * kind, the Stock tab rows). Paid from the smith's pack and the storage chests
     * it can reach, above what the Stock tab holds; stops when the plan is empty,
     * the time is used up or storage is full. With nothing left to forge it smelts
     * the kitchen's gold and, with "Smelting" on, the spare ore, as live.
     *
     * <p>Fixed in round 9 of the away calculator rebuild:
     * old tools are never melted down away and no longer fill the queue (S1); wood
     * burns as fuel as live, and raw iron with no fuel at all stays raw (S2); a job
     * is counted before anything is taken and every part checked, short ones put
     * back (S3); chests the smith wrote off as out of reach are skipped, as the
     * enchanter (S4); gold and spare ore (S5); the skill is read again for every
     * job, so a level-up counts at once (S7); a once order put in
     * storage is told as delivered too, or the Stock tab took it for lost and had
     * it made again (S10).
     */
    public static OfflineWork workOffline(Context ctx, long workTicks, AwayHooks hooks) {
        Offline work = new Offline(ctx, workTicks, hooks);
        work.run();
        return work.result();
    }

    /** One run of {@link #workOffline}. */
    private static final class Offline {
        private final Context ctx;
        private final long workTicks;
        private final AwayHooks hooks;
        private final long tripTicks;
        private final Map<Item, Integer> made = new java.util.LinkedHashMap<>();
        private final Map<Item, Integer> used = new java.util.LinkedHashMap<>();
        @Nullable private ForgeBuilding forge;
        private long ticksLeft;
        private float points;
        /** Smelts the part-used fuel still pays for. */
        private float fuelCredit;
        private int jobs;
        private int smelted;
        private int sessions;
        @Nullable private String stop;

        Offline(Context ctx, long workTicks, AwayHooks hooks) {
            this.ctx = ctx;
            this.workTicks = workTicks;
            this.hooks = hooks;
            this.tripTicks = hooks.tripTicks();
            this.ticksLeft = workTicks;
        }

        OfflineWork result() {
            return new OfflineWork(made, used, points, jobs, smelted, sessions, workTicks - ticksLeft, stop);
        }

        void run() {
            forge = findForge(ctx);
            if (forge == null) return;
            // Counts kept for 100 ticks would be another away day's: many steps run in one tick.
            BucketNeeds.forget(ctx.mgr());
            GoldNeeds.forget(ctx.mgr());
            boolean planEmpty = false;
            for (int pass = 0; pass < OFFLINE_MAX_PASSES && stop == null; pass++) {
                List<Job> queue = new Planner(ctx, fuelCredit, forge, false,
                        hooks.orders()).run();
                if (queue.isEmpty()) {
                    planEmpty = true;
                    break;
                }
                boolean madeAny = false;
                for (Job job : queue) {
                    // Melt-down jobs are never planned away (S1); the check stays for safety.
                    if (job.recipe() == null) continue;
                    // The queue's materials come in one trip and its work is banked in one.
                    if (forgeOne(job, madeAny ? 0L : tripTicks)) madeAny = true;
                    if (stop != null) break;
                }
                if (!madeAny) break;
                sessions++;
            }
            // As live: the kitchen's gold and the spare ore only once there is nothing to forge.
            if (planEmpty && stop == null) smeltLeftovers();
        }

        /** One job, paid in full or not made. True when it was made. */
        private boolean forgeOne(Job job, long trip) {
            Recipe recipe = job.recipe();
            int level = level(ctx);
            int ingots = countItem(ctx, Items.IRON_INGOT) + storedFree(ctx, Items.IRON_INGOT);
            int raw = Math.max(0, recipe.cost(Mat.IRON) - ingots);
            long work = BlacksmithBalance.craftTicks(level) + (long) raw * smeltTicks(level);
            if (trip + work > ticksLeft) {
                stop = "time";
                return false;
            }
            ItemStack product = VillageItemOwnership.produce(
                    new ItemStack(recipe.result(), recipe.count()), ctx.mgr());
            // A full storage room stalls the live smith too.
            StorageBuilding room = roomFor(ctx, product);
            if (room == null) {
                stop = "storage full";
                return false;
            }
            Payment pay = new Payment();
            int smeltedForJob = pay.forRecipe(recipe, raw, level);
            if (smeltedForJob < 0) {
                pay.refund();
                return false;
            }
            pay.count();

            if (product.isDamageableItem()
                    && ctx.level().random.nextFloat() < BlacksmithBalance.temperedChance(level)) {
                Holder<Enchantment> unbreaking = ctx.level().registryAccess()
                        .registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.UNBREAKING);
                product.enchant(unbreaking, 1);
            }
            made.merge(recipe.result(), recipe.count(), Integer::sum);
            boolean once = job.order() != null && job.order().once() && job.order().row() != null;
            // Away, a once order stays in the pack as live after the anvil: on its way, kept from
            // the workers' later away days, banked by the smith after you are back.
            boolean kept = once && ctx.npc().getInventory().addItem(product.copy());
            if (!kept) {
                room.depositItems(ctx.level(), product);
                if (!product.isEmpty()) ctx.npc().getInventory().addItem(product);
            }
            if (recipe == BUCKET) BucketNeeds.forget(ctx.mgr());
            // Told at WRITE; one put in storage is told as delivered too (S10).
            if (once) {
                hooks.orders().made(job.order().row(), recipe.result(), ctx.npc().getNpcId(),
                        recipe.count(), !kept);
            }
            StockReserves.get(ctx.level().getServer()).forgetCounts();

            float craft = BlacksmithBalance.craftPoints(Math.max(0, recipe.tier()));
            if (recipe.armorSlot() >= 0) craft *= ARMOR_COST[recipe.armorSlot()] / 4f;
            earn(craft + smeltedForJob * BlacksmithBalance.SMELT_POINTS_PER_INGOT
                    + work / (float) WORK_XP_TICKS * BlacksmithBalance.WORK_POINTS_PER_MINUTE);
            ticksLeft -= trip + work;
            jobs++;
            return true;
        }

        /**
         * With nothing left to forge (S5): the raw gold the kitchen wants ({@link GoldNeeds}),
         * then with "Smelting" on the spare raw iron, gold and copper into ingots to sell, as
         * live {@code smeltGold} and {@code smeltSpare}: loads of {@link #MAX_SMELT_BATCH} at
         * most, a trip to storage only for {@link #SPARE_MIN_LOAD} or more, what is carried always.
         */
        private void smeltLeftovers() {
            if (forge.getBestFurnacePos() == null) return;
            GoldNeeds.forget(ctx.mgr());
            int gold = Math.min(GoldNeeds.ingotsWanted(ctx.mgr(), ctx.level()), have(Items.RAW_GOLD));
            while (gold > 0 && stop == null) {
                int[] wants = new int[SPARE_ORE.length];
                wants[1] = Math.min(gold, MAX_SMELT_BATCH);
                int load = smeltLoad(wants, countItem(ctx, Items.RAW_GOLD) >= wants[1] ? 0L : tripTicks);
                if (load <= 0) break;
                gold -= load;
                GoldNeeds.forget(ctx.mgr());
            }
            if (stop != null || !settings(ctx).getBool(BlacksmithSettingsSchema.SMELT_SPARE_ORE)) return;
            while (stop == null) {
                int carried = carriedSpareOre(ctx);
                int stored = 0;
                for (Item[] ore : SPARE_ORE) stored += storedFree(ctx, ore[0]);
                if (carried <= 0 && stored < SPARE_MIN_LOAD) return;
                int[] wants = new int[SPARE_ORE.length];
                int room = MAX_SMELT_BATCH;
                for (int i = 0; i < SPARE_ORE.length; i++) {
                    // Storage is only worth the walk for a full enough load.
                    int from = countItem(ctx, SPARE_ORE[i][0])
                            + (carried + stored >= SPARE_MIN_LOAD ? storedFree(ctx, SPARE_ORE[i][0]) : 0);
                    wants[i] = Math.min(room, from);
                    room -= wants[i];
                }
                boolean fetch = false;
                for (int i = 0; i < SPARE_ORE.length; i++) fetch |= wants[i] > countItem(ctx, SPARE_ORE[i][0]);
                if (smeltLoad(wants, fetch ? tripTicks : 0L) <= 0) return;
            }
        }

        /**
         * One furnace load, {@code wants[i]} of {@link #SPARE_ORE}'s ore {@code i}: time, room for
         * the ingots and fuel first, then the ore taken and the ingots stored. How many smelted.
         */
        private int smeltLoad(int[] wants, long trip) {
            int level = level(ctx);
            int load = 0;
            for (int want : wants) load += want;
            if (load <= 0) return 0;
            long work = (long) load * smeltTicks(level);
            if (trip + work > ticksLeft) {
                stop = "time";
                return 0;
            }
            List<ItemStack> ingots = new ArrayList<>();
            for (int i = 0; i < wants.length; i++) {
                if (wants[i] <= 0) continue;
                ItemStack out = VillageItemOwnership.produce(new ItemStack(SPARE_ORE[i][1], wants[i]), ctx.mgr());
                if (roomFor(ctx, out) == null) {
                    stop = "storage full";
                    return 0;
                }
                ingots.add(out);
            }
            Payment pay = new Payment();
            for (int i = 0; i < wants.length; i++) {
                if (wants[i] > 0 && pay.take(SPARE_ORE[i][0], wants[i]) < wants[i]) {
                    pay.refund();
                    return 0;
                }
            }
            if (!pay.burn(load, level)) {
                pay.refund();
                stop = "no fuel to smelt";
                return 0;
            }
            pay.count();
            for (ItemStack out : ingots) {
                made.merge(out.getItem(), out.getCount(), Integer::sum);
                StorageBuilding room = roomFor(ctx, out);
                if (room != null) room.depositItems(ctx.level(), out);
                if (!out.isEmpty()) ctx.npc().getInventory().addItem(out);
            }
            StockReserves.get(ctx.level().getServer()).forgetCounts();
            earn(load * BlacksmithBalance.SMELT_POINTS_PER_INGOT
                    + work / (float) WORK_XP_TICKS * BlacksmithBalance.WORK_POINTS_PER_MINUTE);
            ticksLeft -= trip + work;
            smelted += load;
            return load;
        }

        /** Ticks an ingot takes at the forge's best furnace: a blast furnace halves it, as live. */
        private int smeltTicks(int level) {
            BlockPos furnace = forge.getBestFurnacePos();
            // Never loads a chunk: an unloaded furnace counts as a plain one.
            boolean blast = furnace != null && ctx.level().isLoaded(furnace)
                    && ctx.level().getBlockState(furnace).getBlock() instanceof BlastFurnaceBlock;
            return Math.max(1, BlacksmithBalance.smeltTicksPerItem(level) / (blast ? 2 : 1));
        }

        private void earn(float earned) {
            if (earned <= 0f) return;
            points += earned;
            hooks.payNow().accept(earned);
        }

        /** {@code item} the smith may take: its pack, then the storage chests it can reach. */
        private int have(Item item) {
            return countItem(ctx, item) + storedFree(ctx, item);
        }

        /** One job's payment: what came out, so a short one can be put back (S3). */
        private final class Payment {
            private final List<ItemStack> taken = new ArrayList<>();
            private final float creditBefore = fuelCredit;

            /**
             * A recipe's whole price, counted before anything is taken, then taken and every part
             * checked. The ingots smelted for it, or -1 when it could not be paid ({@link #refund}).
             */
            int forRecipe(Recipe recipe, int raw, int level) {
                if (raw > 0 && forge.getBestFurnacePos() == null) return -1;
                int woodNeed = recipe.cost(Mat.WOOD);
                if (raw > 0) {
                    float perCoal = BlacksmithBalance.smeltsPerCoal(level);
                    float smelts = fuelCredit + (have(Items.COAL) + have(Items.CHARCOAL)) * perCoal;
                    // Wood for the fire counts a whole handle's worth each: never short at the furnace.
                    if (smelts < raw) woodNeed += (int) Math.ceil((raw - smelts) / (perCoal * WOOD_FUEL_SHARE));
                }
                if (woodNeed > 0 && woodUnits(ctx) + storedWoodUnits(ctx) < woodNeed) return -1;
                for (Mat mat : MATS) {
                    int n = recipe.cost(mat);
                    if (n == 0 || mat == Mat.WOOD) continue;
                    int there = 0;
                    for (Item item : mat.items) there += have(item);
                    if (mat == Mat.IRON) there += have(Items.RAW_IRON);
                    if (there < n) return -1;
                }

                for (int i = 0; i < recipe.cost(Mat.WOOD); i++) {
                    if (!woodUnit()) return -1;
                }
                int smelts = 0;
                for (Mat mat : MATS) {
                    int n = recipe.cost(mat);
                    if (n == 0 || mat == Mat.WOOD) continue;
                    int left = n;
                    for (Item item : mat.items) left -= take(item, left);
                    if (mat == Mat.IRON && left > 0) {
                        int rawTaken = take(Items.RAW_IRON, left);
                        left -= rawTaken;
                        smelts = rawTaken;
                    }
                    if (left > 0) return -1;
                }
                if (smelts > 0 && !burn(smelts, level)) return -1;
                return smelts;
            }

            /**
             * Fuel for {@code smelts} ingots: the part-used load first, then coal, charcoal,
             * planks, logs (planks first: they pay half a handle). Wood burns as live (S2).
             */
            boolean burn(int smelts, int level) {
                float perCoal = BlacksmithBalance.smeltsPerCoal(level);
                fuelCredit -= smelts;
                while (fuelCredit < 0f) {
                    if (take(Items.COAL, 1) == 1 || take(Items.CHARCOAL, 1) == 1) {
                        fuelCredit += perCoal;
                    } else if (takeWood(ItemTags.PLANKS, 1) == 1 || takeWood(ItemTags.LOGS, 1) == 1) {
                        fuelCredit += perCoal * WOOD_FUEL_SHARE;
                    } else {
                        return false;
                    }
                }
                return true;
            }

            /** Up to {@code amount} of {@code item}, the pack first, then reachable storage. How many came. */
            int take(Item item, int amount) {
                if (amount <= 0) return 0;
                InventoryManager inv = ctx.npc().getInventory();
                int left = amount;
                for (int i = 0; i < InventoryManager.SIZE && left > 0; i++) {
                    ItemStack stack = inv.getSlot(i);
                    if (!inv.mayUseAsMaterial(stack) || stack.getItem() != item) continue;
                    int n = Math.min(left, stack.getCount());
                    taken.add(stack.copyWithCount(n));
                    inv.reduceSlot(i, n);
                    left -= n;
                }
                if (left > 0) left -= takeStored(ctx, item, left, taken);
                return amount - left;
            }

            /** One handle: a log, else two planks; the pack first, as {@link #consumeWood}, then storage. */
            boolean woodUnit() {
                InventoryManager inv = ctx.npc().getInventory();
                for (int i = 0; i < InventoryManager.SIZE; i++) {
                    ItemStack stack = inv.getSlot(i);
                    if (inv.mayUseAsMaterial(stack) && stack.is(ItemTags.LOGS)) {
                        taken.add(stack.copyWithCount(1));
                        return inv.reduceSlot(i, 1);
                    }
                }
                if (packPlanks() >= 2) return takeWood(ItemTags.PLANKS, 2) == 2;
                if (storedWood(ItemTags.PLANKS) >= 2) return takeWood(ItemTags.PLANKS, 2) == 2;
                return takeWood(ItemTags.LOGS, 1) == 1;
            }

            /** Up to {@code amount} logs or planks, any wood, the pack first. How many came. */
            int takeWood(net.minecraft.tags.TagKey<Item> kind, int amount) {
                InventoryManager inv = ctx.npc().getInventory();
                int left = amount;
                for (int i = 0; i < InventoryManager.SIZE && left > 0; i++) {
                    ItemStack stack = inv.getSlot(i);
                    if (!inv.mayUseAsMaterial(stack) || !stack.is(kind)) continue;
                    int n = Math.min(left, stack.getCount());
                    taken.add(stack.copyWithCount(n));
                    inv.reduceSlot(i, n);
                    left -= n;
                }
                if (left > 0) left -= takeStoredWood(ctx, kind, left, taken);
                return amount - left;
            }

            private int packPlanks() {
                InventoryManager inv = ctx.npc().getInventory();
                int planks = 0;
                for (int i = 0; i < InventoryManager.SIZE; i++) {
                    ItemStack stack = inv.getSlot(i);
                    if (inv.mayUseAsMaterial(stack) && stack.is(ItemTags.PLANKS)) planks += stack.getCount();
                }
                return planks;
            }

            private int storedWood(net.minecraft.tags.TagKey<Item> kind) {
                return storedWoodCounts(ctx, kind);
            }

            /** Puts everything taken back: the pack first, then storage; the part-used fuel as it was. */
            void refund() {
                fuelCredit = creditBefore;
                InventoryManager inv = ctx.npc().getInventory();
                for (ItemStack stack : taken) {
                    if (inv.addItem(stack.copy())) continue;
                    ItemStack rest = stack.copy();
                    for (Building building : ctx.mgr().getBuildings()) {
                        if (rest.isEmpty()) break;
                        if (building instanceof StorageBuilding storage && storage.isValid()) {
                            storage.depositItems(ctx.level(), rest);
                        }
                    }
                }
                taken.clear();
                StockReserves.get(ctx.level().getServer()).forgetCounts();
            }

            /** The payment went through: counted as used. */
            void count() {
                for (ItemStack stack : taken) used.merge(stack.getItem(), stack.getCount(), Integer::sum);
                taken.clear();
            }
        }
    }

    /** First storage room with space for the whole stack, or null. */
    @Nullable
    private static StorageBuilding roomFor(Context ctx, ItemStack stack) {
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()
                    && storage.canAcceptAll(ctx.level(), stack)) {
                return storage;
            }
        }
        return null;
    }

    /** Chests the smith wrote off as out of reach for now, as the enchanter's {@code storageAvailable} (S4). */
    private static List<BlockPos> reachable(Context ctx, List<BlockPos> chests) {
        long now = ctx.level().getGameTime();
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : chests) {
            if (!ctx.brain().isChestUnreachable(pos, now)) result.add(pos);
        }
        return result;
    }

    /** What the Stock tab leaves the smith of {@code item}: MAX_VALUE when it holds none back. */
    private static int stockFree(Context ctx, Item item) {
        return StockReserves.get(ctx.level().getServer()).free(ctx.mgr(), ctx.level(), item, Profession.BLACKSMITH);
    }

    /** {@code item} in the storage chests the smith can reach, above what the Stock tab holds back. */
    private static int storedFree(Context ctx, Item item) {
        ItemStack wanted = new ItemStack(item);
        int found = 0;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            List<BlockPos> chests = reachable(ctx, storage.findChestsWithItem(ctx.level(), wanted));
            for (var chest : StorageBuilding.inventories(ctx.level(), chests)) {
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack stack = chest.peek(i);
                    if (stack.getItem() == item && storage.mayUse(ctx.level(), stack)) found += stack.getCount();
                }
            }
        }
        return found <= 0 ? 0 : Math.min(found, stockFree(ctx, item));
    }

    /**
     * Takes up to {@code amount} of {@code item} from the reachable chests into {@code into}. One
     * withdrawal hands over one kind of stack (the village's own or unbound), so a chest is asked
     * until it has none left (S3). How many came.
     */
    private static int takeStored(Context ctx, Item item, int amount, List<ItemStack> into) {
        int left = Math.min(amount, stockFree(ctx, item));
        int wanted = left;
        ItemStack sample = new ItemStack(item);
        for (Building building : ctx.mgr().getBuildings()) {
            if (left <= 0) break;
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (BlockPos pos : reachable(ctx, storage.findChestsWithItem(ctx.level(), sample))) {
                while (left > 0) {
                    ItemStack got = storage.withdrawItemFromChest(ctx.level(), pos, sample, left);
                    if (got.isEmpty()) break;
                    left -= got.getCount();
                    into.add(got);
                }
                if (left <= 0) break;
            }
        }
        if (left < wanted) StockReserves.get(ctx.level().getServer()).forgetCounts();
        return wanted - left;
    }

    /** Logs or planks ({@code kind}) in the reachable chests, each wood above what the Stock tab holds. */
    private static int storedWoodCounts(Context ctx, net.minecraft.tags.TagKey<Item> kind) {
        Map<Item, Integer> counts = new java.util.HashMap<>();
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            List<BlockPos> chests = reachable(ctx,
                    storage.findChestsWithResource(ctx.level(), StorageBuilding.ResourceType.WOOD));
            for (var chest : StorageBuilding.inventories(ctx.level(), chests)) {
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack stack = chest.peek(i);
                    if (stack.is(kind) && storage.mayUse(ctx.level(), stack)) {
                        counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
            }
        }
        int total = 0;
        for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
            total += Math.min(entry.getValue(), stockFree(ctx, entry.getKey()));
        }
        return total;
    }

    /** Handles storage's wood pays for, as the pack's: one per log, one per two planks. */
    private static int storedWoodUnits(Context ctx) {
        return storedWoodCounts(ctx, ItemTags.LOGS) + storedWoodCounts(ctx, ItemTags.PLANKS) / 2;
    }

    /** Takes up to {@code amount} logs or planks ({@code kind}), any wood, from the reachable chests. */
    private static int takeStoredWood(Context ctx, net.minecraft.tags.TagKey<Item> kind, int amount,
                                      List<ItemStack> into) {
        int left = amount;
        Map<Item, Integer> freeLeft = new java.util.HashMap<>();
        for (Building building : ctx.mgr().getBuildings()) {
            if (left <= 0) break;
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            List<BlockPos> chests = reachable(ctx,
                    storage.findChestsWithResource(ctx.level(), StorageBuilding.ResourceType.WOOD));
            boolean changed = false;
            for (var chest : StorageBuilding.inventories(ctx.level(), chests)) {
                for (int i = 0; i < chest.size() && left > 0; i++) {
                    ItemStack stack = chest.peek(i);
                    if (stack.isEmpty() || !stack.is(kind) || !storage.mayUse(ctx.level(), stack)) continue;
                    // Read before the take: an emptied stack says air.
                    Item wood = stack.getItem();
                    int free = freeLeft.computeIfAbsent(wood, item -> stockFree(ctx, item));
                    int n = Math.min(left, Math.min(free, stack.getCount()));
                    if (n <= 0) continue;
                    ItemStack got = chest.take(i, n);
                    if (got.isEmpty()) continue;
                    into.add(got);
                    left -= got.getCount();
                    freeLeft.put(wood, free - got.getCount());
                    changed = true;
                }
                if (left <= 0) break;
            }
            if (changed) storage.invalidateContentIndex();
        }
        if (left < amount) StockReserves.get(ctx.level().getServer()).forgetCounts();
        return amount - left;
    }
}
