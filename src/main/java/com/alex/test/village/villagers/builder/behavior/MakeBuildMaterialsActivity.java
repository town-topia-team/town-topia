package com.alex.test.village.villagers.builder.behavior;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.CraftingStation;
import com.alex.test.village.brain.FetchFromStorageActivity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.builder.craft.MaterialPlanner;
import com.alex.test.village.villagers.builder.craft.MaterialPlanner.Kind;
import com.alex.test.village.villagers.builder.craft.RecipeBook;
import com.alex.test.village.villagers.builder.project.BuildOrders;
import com.alex.test.village.villagers.builder.project.GivenBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Builder making blocks an order is short of (round 5 of
 * docs/plans/2026-10-23-builder-makes-missing-blocks.md), from a {@link MaterialPlanner} plan:
 * fetches what the plan takes from storage, then works each step at a storage room's
 * stonecutter or crafting table (one villager per station, {@link CraftingStation}).
 *
 * <p>A village run uses the village's goods in the pack; what it makes is the village's
 * ({@code produce}). A given run uses one player's given blocks and puts what it makes back
 * in that player's given blocks: nothing is fetched or carried, nothing is mixed.
 *
 * <p>Interrupted, it leaves what it fetched or made in the pack, where the next plan counts it.
 */
final class MakeBuildMaterialsActivity extends Activity {
    private enum Phase { FETCH, WALK, WORK }

    /** Fetch trips before the plan is made again from what storage really holds. */
    private static final int MAX_FETCHES = 8;
    /** Ending a walk further than this from the stand spot means the station can't be reached. */
    private static final double ARRIVE_SQ = 3.0 * 3.0;

    private final String item;
    private final int wanted;
    /** The player whose given blocks this run uses; null for the village's goods. */
    @Nullable private final UUID giver;
    private MaterialPlanner.Plan plan;
    private Phase phase = Phase.FETCH;
    private int step;
    private int fetches;
    private int carriedBefore = -1;
    private boolean replanned;
    @Nullable private FetchFromStorageActivity fetch;
    @Nullable private CraftingStation station;
    @Nullable private BlockPos stand;
    private int timer;
    private int made;
    private String doing = "";
    private String failed = "";

    MakeBuildMaterialsActivity(MaterialPlanner.Plan plan, @Nullable UUID giver) {
        this.plan = plan;
        this.item = plan.item();
        this.wanted = plan.wanted();
        this.giver = giver;
    }

    // =========================================================================
    // Planning (asked by BuildSiteWork)
    // =========================================================================

    /** Half a second a craft, a quarter a cut, as the plan says (smelting comes in round 6). */
    static int ticksPerRun(Kind kind) {
        return switch (kind) {
            case CUT -> 5;
            case CRAFT -> 10;
            case SMELT -> 40;
        };
    }

    /** The stations the Builder uses: the storage rooms' table and stonecutter, no furnace yet. */
    static MaterialPlanner.Stations stations(VillageManager village) {
        MaterialPlanner.Stations all = RecipeBook.stations(village);
        return new MaterialPlanner.Stations(all.table(), all.stonecutter(), false);
    }

    /** Making {@code amount} of {@code item} from the village's goods (storage and the pack). */
    static MaterialPlanner.Plan villagePlan(Activity.Context ctx, Item item, int amount) {
        return villagePlan(ctx, RecipeBook.id(item), amount, emptySlots(ctx));
    }

    private static MaterialPlanner.Plan villagePlan(Activity.Context ctx, String item, int amount, int packSlots) {
        MinecraftServer server = ctx.level().getServer();
        return MaterialPlanner.plan(RecipeBook.withoutSmelting(server),
                RecipeBook.villageLedger(ctx.level(), ctx.mgr(), ctx.npc(), false), stations(ctx.mgr()),
                item, amount, packSlots);
    }

    /** Making it from one player's given blocks; nothing goes in the pack, so the pack is no limit. */
    static MaterialPlanner.Plan givenPlan(Activity.Context ctx, UUID giver, Item item, int amount) {
        MinecraftServer server = ctx.level().getServer();
        return MaterialPlanner.plan(RecipeBook.withoutSmelting(server),
                RecipeBook.givenLedger(server, ctx.mgr(), giver, false), stations(ctx.mgr()),
                RecipeBook.id(item), amount, Integer.MAX_VALUE);
    }

    /** The order's line: "Making 64 Oak Stairs: crafting". */
    String line() {
        String making = "Making " + plan.makes() + " " + name(item) + (giver == null ? "" : " from given blocks");
        return doing.isEmpty() ? making : making + ": " + doing;
    }

    /** Why it stopped, for the order's waiting line; empty when it didn't fail. */
    String failed() {
        return failed;
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    @Override
    protected void start(Context ctx) {
        diary(ctx, "BUILDER making " + plan.makes() + " " + name(item) + (giver == null ? "" : " from given blocks")
                + ": takes " + list(plan.takes()));
        DynamicVillages.LOGGER.debug("[BuilderCraft] {} making {} x{} (wanted {}), {} steps, takes {}, given={}",
                ctx.npc().getName().getString(), item, plan.makes(), wanted, plan.steps().size(), plan.takes(), giver);
        if (giver != null) phase = null;
    }

    @Override
    protected Status tick(Context ctx) {
        if (phase == null) return beginStep(ctx, 0);
        return switch (phase) {
            case FETCH -> fetchTick(ctx);
            case WALK -> walkTick(ctx);
            case WORK -> workTick(ctx);
        };
    }

    @Override
    public void stop(Context ctx) {
        if (fetch != null) fetch.stop(ctx);
        fetch = null;
        if (station != null) station.release(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public String name() {
        return "build_make";
    }

    // =========================================================================
    // Fetching what the plan takes
    // =========================================================================

    private Status fetchTick(Context ctx) {
        if (fetch != null) {
            if (fetch.tickOuter(ctx) == Status.RUNNING) return Status.RUNNING;
            fetch.stop(ctx);
            fetch = null;
        }
        Map.Entry<String, Integer> missing = missing(ctx);
        if (missing == null) return beginStep(ctx, 0);
        int carried = carriedTakes(ctx);
        if (fetches >= MAX_FETCHES || fetches > 0 && carried <= carriedBefore) {
            // Storage held less than planned (someone took some, or it was spread thin): plan
            // again from what is really there, once.
            if (replanned) return fail(ctx, "not enough in storage to make it from");
            replanned = true;
            MaterialPlanner.Plan again = villagePlan(ctx, item, wanted, emptySlots(ctx) + slotsHolding(ctx));
            if (!again.any()) {
                return fail(ctx, again.why() == MaterialPlanner.Why.PACK_FULL ? "no room in its pack"
                        : "not enough in storage to make it from");
            }
            plan = again;
            fetches = 0;
            diary(ctx, "BUILDER storage had less: now making " + plan.makes() + " " + name(item)
                    + ", takes " + list(plan.takes()));
            missing = missing(ctx);
            if (missing == null) return beginStep(ctx, 0);
        }
        Item wantedItem = RecipeBook.item(missing.getKey());
        int stored = BuildOrders.stored(ctx.mgr(), ctx.level(), wantedItem);
        if (stored <= 0) return fail(ctx, "no " + name(missing.getKey()) + " left in storage");
        int amount = Math.min(Math.min(missing.getValue(), stored), new ItemStack(wantedItem).getMaxStackSize());
        carriedBefore = carried;
        fetches++;
        fetch = FetchFromStorageActivity.forItem(new ItemStack(wantedItem), amount, "fetch_build");
        doing = "fetching " + name(missing.getKey());
        return Status.RUNNING;
    }

    /** The first input the pack still lacks, and how many. */
    @Nullable
    private Map.Entry<String, Integer> missing(Context ctx) {
        for (Map.Entry<String, Integer> take : plan.takes().entrySet()) {
            int lacking = take.getValue() - carried(ctx, RecipeBook.item(take.getKey()));
            if (lacking > 0) return Map.entry(take.getKey(), lacking);
        }
        return null;
    }

    private int carriedTakes(Context ctx) {
        int total = 0;
        for (String input : plan.takes().keySet()) total += carried(ctx, RecipeBook.item(input));
        return total;
    }

    /** Pack slots holding what the plan takes: room the plan made again may count on. */
    private int slotsHolding(Context ctx) {
        InventoryManager pack = ctx.npc().getInventory();
        int slots = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = pack.getSlot(i);
            if (!stack.isEmpty() && plan.takes().containsKey(RecipeBook.id(stack.getItem()))) slots++;
        }
        return slots;
    }

    // =========================================================================
    // Working the steps
    // =========================================================================

    /** Walks to step {@code index}'s station, or works on at the same one; DONE after the last. */
    private Status beginStep(Context ctx, int index) {
        step = index;
        if (index >= plan.steps().size()) return done(ctx);
        Kind kind = plan.steps().get(index).recipe().kind();
        Station found = nearestStation(ctx, kind, station != null ? station.table() : null);
        if (found == null) return fail(ctx, "no " + kind.station + " in the storage room");
        if (station != null && station.table().equals(found.pos())) {
            startWork(ctx);
            return Status.RUNNING;
        }
        if (station != null) station.release(ctx);
        station = new CraftingStation(found.pos());
        stand = station.standFor(ctx, found.storage());
        if (stand == null) return fail(ctx, "can't reach the " + kind.station);
        resetMove();
        phase = Phase.WALK;
        doing = "walking to the " + kind.station;
        return Status.RUNNING;
    }

    private Status walkTick(Context ctx) {
        Kind kind = plan.steps().get(step).recipe().kind();
        if (!moveTo(ctx, stand, VillagerModel.WALK_SPEED)) return Status.RUNNING;
        if (ctx.npc().blockPosition().distSqr(stand) > ARRIVE_SQ) return fail(ctx, "can't reach the " + kind.station);
        switch (station.acquire(ctx)) {
            case WAIT -> {
                doing = "waiting for the " + kind.station;
                return Status.RUNNING;
            }
            case GIVE_UP -> {
                return fail(ctx, "the " + kind.station + " was busy");
            }
            case MINE -> { }
        }
        if (!station.settle(ctx, stand)) return Status.RUNNING;
        startWork(ctx);
        return Status.RUNNING;
    }

    private void startWork(Context ctx) {
        MaterialPlanner.Step now = plan.steps().get(step);
        timer = Math.max(1, now.runs() * ticksPerRun(now.recipe().kind()));
        phase = Phase.WORK;
        doing = now.recipe().kind() == Kind.CUT ? "cutting" : "crafting";
        ctx.npc().setAnimation(VillagerAnimationState.CRAFT_TABLE);
    }

    private Status workTick(Context ctx) {
        MaterialPlanner.Step now = plan.steps().get(step);
        CraftingStation.face(ctx.npc(), station.table());
        ctx.npc().setAnimation(VillagerAnimationState.CRAFT_TABLE);
        if (now.recipe().kind() == Kind.CUT && timer % 10 == 0) {
            ctx.level().playSound(null, station.table(), SoundEvents.UI_STONECUTTER_TAKE_RESULT, SoundSource.BLOCKS,
                    0.6f, 1.0f);
        }
        if (--timer > 0) return Status.RUNNING;
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        if (!(giver == null ? swapInPack(ctx, now) : swapGiven(ctx, now))) {
            return fail(ctx, "what it fetched went missing");
        }
        if (now.recipe().result().equals(item)) made += now.gives();
        // The same station next is worked on without letting go of it (beginStep).
        return beginStep(ctx, step + 1);
    }

    /** The step's inputs out of the pack, its result in, tied to the village. */
    private boolean swapInPack(Context ctx, MaterialPlanner.Step now) {
        for (Map.Entry<String, Integer> input : now.used().entrySet()) {
            if (carried(ctx, RecipeBook.item(input.getKey())) < input.getValue()) return false;
        }
        for (Map.Entry<String, Integer> input : now.used().entrySet()) {
            takeFromPack(ctx, RecipeBook.item(input.getKey()), input.getValue());
        }
        Item result = RecipeBook.item(now.recipe().result());
        int left = now.gives();
        int stack = Math.max(1, new ItemStack(result).getMaxStackSize());
        while (left > 0) {
            ItemStack made = VillageItemOwnership.produce(new ItemStack(result, Math.min(left, stack)), ctx.mgr());
            left -= Math.min(left, stack);
            if (made.isEmpty()) continue;
            if (!ctx.npc().getInventory().addItem(made)) ItemLossLog.pop(ctx, ctx.npc().blockPosition(), made, "build_make");
        }
        return true;
    }

    /** The step's inputs out of the giver's given blocks, its result back into them. */
    private boolean swapGiven(Context ctx, MaterialPlanner.Step now) {
        GivenBlocks given = GivenBlocks.get(ctx.level().getServer());
        UUID village = ctx.mgr().getVillageId();
        for (Map.Entry<String, Integer> input : now.used().entrySet()) {
            // The player may have taken some back while the Builder walked.
            if (given.count(village, giver, RecipeBook.item(input.getKey())) < input.getValue()) return false;
        }
        for (Map.Entry<String, Integer> input : now.used().entrySet()) {
            given.takeBack(village, giver, RecipeBook.item(input.getKey()), input.getValue());
        }
        given.add(village, giver, RecipeBook.item(now.recipe().result()), now.gives());
        BuildOrders.changed(ctx.level().getServer(), village);
        return true;
    }

    private Status done(Context ctx) {
        if (station != null) station.release(ctx);
        station = null;
        if (made <= 0) return fail(ctx, "made none");
        String from = giver == null ? list(plan.takes()) : list(plan.takes()) + " given";
        diary(ctx, "BUILDER made " + made + " " + name(item) + " from " + from);
        DynamicVillages.LOGGER.debug("[BuilderCraft] {} made {} x{} from {}{}", ctx.npc().getName().getString(),
                item, made, plan.takes(), giver == null ? "" : " (given)");
        return Status.DONE;
    }

    private Status fail(Context ctx, String why) {
        failed = why;
        diary(ctx, "BUILDER couldn't make " + name(item) + ": " + why);
        DynamicVillages.LOGGER.debug("[BuilderCraft] {} gave up making {}: {}", ctx.npc().getName().getString(), item, why);
        return Status.FAILED;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private record Station(StorageBuilding storage, BlockPos pos) {}

    /**
     * The storage rooms' stonecutter, crafting table or furnace to work, of
     * every one in every room ({@link CraftingStation#pick}): the one this
     * builder holds, else the nearest free one, else the nearest. Only each
     * room's first used to count, so a second one stood unused.
     */
    @Nullable
    private static Station nearestStation(Context ctx, Kind kind, @Nullable BlockPos current) {
        List<BlockPos> stations = new ArrayList<>();
        Map<BlockPos, StorageBuilding> roomOf = new HashMap<>();
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            List<BlockPos> positions = switch (kind) {
                case CUT -> storage.getStonecutterPositions();
                case CRAFT -> storage.getCraftingTablePositions();
                case SMELT -> storage.getFurnacePositions();
            };
            for (BlockPos pos : positions) {
                if (roomOf.putIfAbsent(pos, storage) == null) stations.add(pos);
            }
        }
        BlockPos pos = CraftingStation.pick(ctx, stations, current);
        return pos == null ? null : new Station(roomOf.get(pos), pos);
    }

    /** A village good with nothing on it but the village's mark, as the ledger counts it. */
    private static boolean plain(ItemStack stack) {
        DataComponentPatch patch = stack.getComponentsPatch();
        return patch.isEmpty() || patch.size() == 1 && patch.get(DataComponents.CUSTOM_DATA) != null;
    }

    private static int carried(Context ctx, Item item) {
        InventoryManager pack = ctx.npc().getInventory();
        int count = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = pack.getSlot(i);
            if (stack.is(item) && plain(stack) && pack.mayUseAsMaterial(stack)) count += stack.getCount();
        }
        return count;
    }

    private static void takeFromPack(Context ctx, Item item, int amount) {
        InventoryManager pack = ctx.npc().getInventory();
        int left = amount;
        for (int i = 0; i < InventoryManager.SIZE && left > 0; i++) {
            ItemStack stack = pack.getSlot(i);
            if (!stack.is(item) || !plain(stack) || !pack.mayUseAsMaterial(stack)) continue;
            int used = Math.min(left, stack.getCount());
            pack.reduceSlot(i, used);
            left -= used;
        }
    }

    private static int emptySlots(Context ctx) {
        int empty = 0;
        for (ItemStack stack : ctx.npc().getInventory().getSlots()) if (stack.isEmpty()) empty++;
        return empty;
    }

    /** "16 Oak Log, 4 Cobblestone". */
    private static String list(Map<String, Integer> items) {
        if (items.isEmpty()) return "nothing";
        List<String> parts = new ArrayList<>();
        items.forEach((id, n) -> parts.add(String.format(Locale.ROOT, "%d %s", n, name(id))));
        return String.join(", ", parts);
    }

    static String name(String id) {
        return new ItemStack(RecipeBook.item(id)).getHoverName().getString();
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }
}
