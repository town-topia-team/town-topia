package com.alex.test.village.economy.away.jobs;

import com.alex.test.Config;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.economy.AwayClock;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayJob;
import com.alex.test.village.economy.away.AwayRun;
import com.alex.test.village.economy.away.AwayTrees;
import com.alex.test.village.economy.away.AwayXp;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.lumberjack.AxeUtils;
import com.alex.test.village.villagers.lumberjack.LumberjackBalance;
import com.alex.test.village.villagers.lumberjack.LumberjackBigTrees;
import com.alex.test.village.villagers.lumberjack.behavior.ChopTreeActivity;
import com.alex.test.village.villagers.lumberjack.behavior.GetAxeActivity;
import com.alex.test.village.villagers.lumberjack.behavior.LeafRewards;
import com.alex.test.village.villagers.lumberjack.behavior.TreeApproachPlanner;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import com.alex.test.village.villagers.lumberjack.data.TreeTypeRegistry;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import com.alex.test.village.villagers.tools.ToolSpeed;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A lumberjack's away day (round 7 of the plan), on the village's trees
 * ({@link AwayTrees}): the lumberjacks take their turns, each over its own
 * work hours, so a tree one fells is gone for the next, and no wood comes
 * without a tree (old bugs L1, L2, L3).
 *
 * <p>Live rules it copies ({@code LumberjackActivity}, {@code ChopTreeActivity}), and where they live:
 * <ul>
 *   <li>Checks: work on; trees listed in loaded chunks; per tree its kind switched on
 *       ({@code isTreeEnabled}), a big 2x2 tree only with an iron axe or better ({@code mayFell},
 *       {@code hasBigTreeAxe}; old bug L4), a way in from this lumberjack's part of the map
 *       ({@code TreeApproachPlanner.plan}, only with the chunks round the tree loaded). A tree job held
 *       at READ (a paused chop) is its holder's, first ({@code resumeClaimedTree}); the others pass it
 *       by ({@code tryClaimTreeJob}).</li>
 *   <li>Order, as {@code doTick}: carried logs at the carry limit ({@code carryLogs}) bank first; no usable
 *       axe: one from storage or made from storage wood ({@code GetAxeActivity.AXE_REQUIREMENT}), and
 *       with none, a bank and a second try (live banks and tries again on its next start; old bug L9);
 *       then the nearest tree from where it stands.</li>
 *   <li>Time: one clock over the work hours at {@code AwayClock.AWAY_PACE}. A tree is the walk there
 *       (it must fit the hours), settling in, the chop ({@code chopTicks} at the axe's {@code ToolSpeed})
 *       and the cleanup (its blocks at the config's blocks a tick). Once chopping starts the tree is
 *       finished past the hours ({@code allowsRoutineInterruption} false in CHOP).</li>
 *   <li>Logs ({@code takeLog}): the tree's own kinds (old bug L5), the base log sure, the rest at
 *       {@code logChance} as an average, the fraction kept for the next tree (old bug L10). Sticks at
 *       {@code STICK_LEAF_CHANCE} a leaf while storage holds under {@code STICK_STOCK_CAP} (read once a
 *       tree; old bug L6), apples at {@code APPLE_LEAF_CHANCE} from oak and dark oak leaves only, from
 *       every leaf the tree really has (old bug L7).</li>
 *   <li>Axe: a point a log received ({@code damageAxeForLog}); once it breaks the rest of that tree is
 *       free, the next axe comes before the next tree (old bug L8).</li>
 *   <li>Room: the tree's whole take must fit in the pack, a bank first if needed; with no room at all
 *       the day ends (live drops what does not fit on the ground).</li>
 *   <li>World, at WRITE ({@link AwayTrees}): the logs and leaves go, saplings are planted in "cut and
 *       plant" mode ({@code shouldReplant}; every trunk of a joined group with {@code replantall}), and
 *       the tree leaves the list.</li>
 *   <li>XP: each tree ({@link AwayXp#lumberjackTree}).</li>
 * </ul>
 * Not done away: axe upgrades from storage, eating before a chop, leaves cut on the walk, saplings
 * growing into trees.
 */
public final class LumberjackAway implements AwayJob {

    public static final LumberjackAway INSTANCE = new LumberjackAway();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The walk in through the leaves to the trunk and settling there (my call). */
    static final long SETTLE_TICKS = 40L;

    /** Fractions carried on between trees and catch-ups, in the brain data. */
    private static final String TAG_LOG_SHARE = "AwayLumberjackLogShare";
    private static final String TAG_STICK_SHARE = "AwayLumberjackStickShare";
    private static final String TAG_APPLE_SHARE = "AwayLumberjackAppleShare";

    private static final ResourceLocation AZALEA = ResourceLocation.withDefaultNamespace("azalea");

    private LumberjackAway() {}

    @Override
    @Nullable
    public String cannotWork(AwayContext ctx) {
        if (!LumberjackSettings.of(ctx.npc()).works()) return "work off";
        AwayTrees trees = ctx.trees();
        if (trees.isEmpty()) return trees.notLoaded() > 0 ? "trees not loaded" : "no trees known";
        return null;
    }

    @Override
    public void workDay(AwayContext ctx) {
        new Shift(ctx).run();
    }

    private record Pick(AwayTrees.Tree tree, TreeStructure structure) {}

    /** What felling a tree would give, worked out before anything changes. */
    private record Take(Map<BlockPos, BlockState> logs, Map<BlockPos, BlockState> leaves, List<ItemStack> stacks,
                        int logsGot, int sticks, int apples, double logShare, double stickShare, double appleShare) {}

    /** One lumberjack's day. */
    private static final class Shift {
        private final AwayContext ctx;
        private final NpcEntity npc;
        private final UUID id;
        private final InventoryManager pack;
        private final LumberjackSettings settings;
        private final AwayTrees trees;
        private final Activity.Context live;
        /** The lumberjack's clock, in ticks from the start of its work hours. */
        private long t;
        private final long end;
        /** Where it stands: its body at first, then the tree it last felled. */
        private BlockPos pos;
        private boolean storageFull;
        /** Why the day ended before its hours did; null when the hours ran out. */
        @Nullable private String stop;
        private final Map<Item, Integer> gross = new LinkedHashMap<>();
        private final Map<String, Integer> felledByKind = new TreeMap<>();
        // Trees passed by today, for the log.
        private final Set<BlockPos> kindOff = new HashSet<>();
        private final Set<BlockPos> tooBig = new HashSet<>();
        private final Set<BlockPos> noWay = new HashSet<>();
        private final Set<BlockPos> heldByOther = new HashSet<>();
        private int felled;
        private int logBlocks;
        private int logsGot;
        private int sticks;
        private int apples;
        private int broken;
        private int axes;
        private int banks;
        private int trips;

        Shift(AwayContext ctx) {
            this.ctx = ctx;
            this.npc = ctx.npc();
            this.id = npc.getNpcId();
            this.pack = npc.getInventory();
            this.settings = LumberjackSettings.of(npc);
            this.trees = ctx.trees();
            this.live = ctx.activity();
            this.end = Math.round(ctx.day.workTicks() * AwayClock.AWAY_PACE);
            this.pos = npc.blockPosition();
        }

        void run() {
            while (t < end) {
                if (carriedLogs() >= carryLimit() && !bank()) {
                    stop = "pack full, storage takes nothing";
                    break;
                }
                if (AxeUtils.findUsableAxeSlot(pack, settings.getAllowedAxes()) < 0 && !getAxe()) break;
                if (t >= end) break;
                Pick pick = pick();
                if (pick == null) {
                    stop = "no tree it can fell";
                    break;
                }
                Take take = take(pick);
                if (!AwayPack.fits(pack, take.stacks())) {
                    if (!storageFull && AwayPack.depositable(ctx) > 0) bank();
                    if (!AwayPack.fits(pack, take.stacks())) {
                        stop = storageFull ? "storage full" : "pack full";
                        break;
                    }
                }
                if (!fell(pick, take)) break;
                // As live: a full load is banked before the next tree.
                if (carriedLogs() >= carryLimit() && !storageFull && AwayPack.depositable(ctx) > 0) bank();
            }
            finish();
        }

        // ---------------------------------------------------------------------
        // Choosing
        // ---------------------------------------------------------------------

        /** The tree live would walk to now ({@code startNextTree}), or null when none will do. */
        @Nullable
        private Pick pick() {
            List<AwayTrees.Tree> order = new ArrayList<>(trees.standing());
            // Its own held tree first, then the nearest.
            order.sort(Comparator.comparing((AwayTrees.Tree tree) -> !id.equals(tree.holder()))
                    .thenComparingDouble(tree -> tree.base().distSqr(pos)));
            boolean bigTreeAxe = AxeUtils.hasBigTreeAxe(npc);
            for (AwayTrees.Tree tree : order) {
                BlockPos base = tree.base();
                if (noWay.contains(base)) continue;
                if (!trees.mayTake(tree, id)) {
                    heldByOther.add(base);
                    continue;
                }
                TreeStructure structure = trees.judge(tree);
                if (structure == null) continue;
                if (!settings.isTreeEnabled(structure.treeTypeId())) {
                    kindOff.add(base);
                    continue;
                }
                if (!LumberjackBigTrees.mayFell(structure, bigTreeAxe)) {
                    tooBig.add(base);
                    continue;
                }
                if (!wayIn(structure)) {
                    noWay.add(base);
                    continue;
                }
                return new Pick(tree, structure);
            }
            return null;
        }

        /** A way in from this lumberjack's part of the map; with no map at all, as live, yes. */
        private boolean wayIn(TreeStructure structure) {
            if (ctx.mgr.getGraph() == null) return true;
            BlockPos base = structure.base();
            int reach = TreeApproachPlanner.REACH;
            // The planner reads blocks round the tree: never from a chunk that is not loaded.
            for (int cx = (base.getX() - reach) >> 4; cx <= (base.getX() + reach) >> 4; cx++) {
                for (int cz = (base.getZ() - reach) >> 4; cz <= (base.getZ() + reach) >> 4; cz++) {
                    if (!ctx.level.hasChunk(cx, cz)) return false;
                }
            }
            return TreeApproachPlanner.canApproach(live, structure);
        }

        // ---------------------------------------------------------------------
        // The tree
        // ---------------------------------------------------------------------

        /** The tree's blocks as they stand and what felling it gives; nothing changes yet. */
        private Take take(Pick pick) {
            TreeStructure structure = pick.structure();
            BlockPos base = structure.base();
            // Live's cleanup order: the base first, then up the trunk.
            List<BlockPos> order = new ArrayList<>(structure.logs());
            order.sort(Comparator.comparing((BlockPos log) -> !log.equals(base)).thenComparingInt(BlockPos::getY));
            Map<BlockPos, BlockState> logs = new LinkedHashMap<>();
            for (BlockPos log : order) {
                if (!ctx.level.isLoaded(log)) continue;
                BlockState state = ctx.level.getBlockState(log);
                if (structure.matchesLog(state)) logs.put(log, state);
            }
            Map<BlockPos, BlockState> leaves = new LinkedHashMap<>();
            int appleLeaves = 0;
            for (BlockPos leaf : structure.leaves()) {
                if (!ctx.level.isLoaded(leaf)) continue;
                BlockState state = ctx.level.getBlockState(leaf);
                if (!structure.matchesLeaf(state)) continue;
                leaves.put(leaf, state);
                if (LeafRewards.dropsApples(state)) appleLeaves++;
            }
            CompoundTag data = npc.getBrainData();
            // Logs: the first sure, each next one at the skill's chance, as an average.
            double chance = LumberjackBalance.logChance(level());
            double logShare = data.getDouble(TAG_LOG_SHARE);
            Map<Item, Integer> logItems = new LinkedHashMap<>();
            boolean first = true;
            for (BlockState state : logs.values()) {
                boolean got = first;
                if (!first) {
                    logShare += chance;
                    got = logShare >= 1.0;
                    if (got) logShare -= 1.0;
                }
                first = false;
                Item item = state.getBlock().asItem();
                if (got && item != Items.AIR) logItems.merge(item, 1, Integer::sum);
            }
            List<ItemStack> stacks = new ArrayList<>();
            int received = 0;
            for (Map.Entry<Item, Integer> entry : logItems.entrySet()) {
                received += addStacks(stacks, entry.getKey(), entry.getValue());
            }
            // Sticks while the village has few; the stock read once a tree, as live.
            double stickShare = data.getDouble(TAG_STICK_SHARE) + leaves.size() * LeafRewards.STICK_LEAF_CHANCE;
            int stickCount = (int) Math.floor(stickShare);
            stickShare -= stickCount;
            stickCount = Math.min(stickCount, Math.max(0, LeafRewards.STICK_STOCK_CAP - ctx.storageHolds(Items.STICK)));
            double appleShare = data.getDouble(TAG_APPLE_SHARE) + appleLeaves * LeafRewards.APPLE_LEAF_CHANCE;
            int appleCount = (int) Math.floor(appleShare);
            appleShare -= appleCount;
            int stickGot = addStacks(stacks, Items.STICK, stickCount);
            int appleGot = addStacks(stacks, Items.APPLE, appleCount);
            return new Take(logs, leaves, stacks, received, stickGot, appleGot, logShare, stickShare, appleShare);
        }

        /** Fells the picked tree; false when the work hours ended on the walk there. */
        private boolean fell(Pick pick, Take take) {
            TreeStructure structure = pick.structure();
            long walk = AwayWalk.walk(Math.sqrt(pos.distSqr(structure.base()))) + SETTLE_TICKS;
            if (t + walk > end) {
                t = end;
                return false;
            }
            int slot = AxeUtils.findChoppingAxeSlot(pack, settings.getAllowedAxes());
            if (slot < 0) {
                stop = "no axe";
                return false;
            }
            ItemStack axe = pack.getSlot(slot);
            int chop = LumberjackBalance.chopTicks(structure.logs().size(), structure.twoByTwo(), ToolSpeed.of(axe));
            int perTick = Math.max(1, Config.lumberjackTreeBlocksRemovedPerTick());
            long cleanup = Math.ceilDiv(take.logs().size() + take.leaves().size(), perTick);
            // Chopping has begun: the tree is finished even past the work hours, as live.
            t += walk + chop + cleanup;
            for (int log = 0; log < take.logsGot(); log++) {
                // Broken: the rest of the felled tree is cleared for free.
                if (!AxeUtils.damageAxeForLog(ctx.level, pack, slot)) {
                    broken++;
                    break;
                }
            }
            for (ItemStack stack : take.stacks()) {
                int placed = stack.getCount();
                if (!pack.addItem(stack)) placed = ctx.store(stack.copy());
                if (placed > 0) gross.merge(stack.getItem(), placed, Integer::sum);
            }
            CompoundTag data = npc.getBrainData();
            data.putDouble(TAG_LOG_SHARE, take.logShare());
            data.putDouble(TAG_STICK_SHARE, take.stickShare());
            data.putDouble(TAG_APPLE_SHARE, take.appleShare());
            Block sapling = saplingFor(structure, take.leaves());
            trees.fell(pick.tree(), take.logs(), take.leaves(), sapling,
                    sapling != null ? saplingSpots(structure) : List.of());
            AwayXp.lumberjackTree(ctx);
            felledByKind.merge(structure.treeTypeId().getPath(), 1, Integer::sum);
            felled++;
            logBlocks += take.logs().size();
            logsGot += take.logsGot();
            sticks += take.sticks();
            apples += take.apples();
            pos = structure.base();
            return true;
        }

        /** The sapling to plant in "cut and plant" mode, or null ({@code replantSaplings}, {@code TreeStructure.sapling}). */
        @Nullable
        private Block saplingFor(TreeStructure structure, Map<BlockPos, BlockState> leaves) {
            if (!settings.shouldReplant(structure.treeTypeId())) return null;
            // As TreeStructure.sapling, from the leaves already read: never a block in an unloaded chunk.
            if (structure.treeTypeId().equals(AZALEA)) {
                for (BlockState leaf : leaves.values()) {
                    if (leaf.is(Blocks.FLOWERING_AZALEA_LEAVES)) return Blocks.FLOWERING_AZALEA;
                }
            }
            TreeTypeRegistry.TreeType type = TreeTypeRegistry.byId(structure.treeTypeId());
            return type != null ? type.sapling() : null;
        }

        /** Its own trunk's spot first, then every other trunk of a joined group ({@code replantall}). */
        private List<List<BlockPos>> saplingSpots(TreeStructure structure) {
            List<List<BlockPos>> spots = new ArrayList<>();
            spots.add(List.copyOf(structure.saplingPositions()));
            if (WalkFeel.replantAll) spots.addAll(ChopTreeActivity.otherTrunkSpots(ctx.level, structure));
            return spots;
        }

        /** {@code count} of {@code item} as the village's stacks into {@code out}; how many could be the village's. */
        private int addStacks(List<ItemStack> out, Item item, int count) {
            int max = Math.max(1, new ItemStack(item).getMaxStackSize());
            int added = 0;
            while (count > 0) {
                int size = Math.min(count, max);
                count -= size;
                // Live loses an item that cannot be the village's the same way.
                ItemStack stack = VillageItemOwnership.produce(new ItemStack(item, size), ctx.mgr);
                if (stack.isEmpty()) continue;
                out.add(stack);
                added += stack.getCount();
            }
            return added;
        }

        // ---------------------------------------------------------------------
        // Axe, pack and storage
        // ---------------------------------------------------------------------

        /** An axe from storage or made from storage wood; with none, a bank and a second try. */
        private boolean getAxe() {
            if (fetchAxe()) return true;
            if (!storageFull && AwayPack.depositable(ctx) > 0) {
                bank();
                if (fetchAxe()) return true;
            }
            stop = "no axe";
            return false;
        }

        private boolean fetchAxe() {
            if (!ctx.restock(GetAxeActivity.AXE_REQUIREMENT)) return false;
            t += AwayWalk.TRIP_TICKS;
            trips++;
            axes++;
            return true;
        }

        /** A storage trip with everything the lumberjack does not keep; false when storage took nothing. */
        private boolean bank() {
            if (storageFull || AwayPack.depositable(ctx) == 0) return false;
            t += AwayWalk.TRIP_TICKS;
            trips++;
            if (AwayPack.bank(ctx) == 0) {
                storageFull = true;
                return false;
            }
            banks++;
            return true;
        }

        /** Logs in the pack ({@code carriedLogs}). */
        private int carriedLogs() {
            int count = 0;
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (stack.is(ItemTags.LOGS)) count += stack.getCount();
            }
            return count;
        }

        private int carryLimit() {
            return LumberjackBalance.carryLogs(level());
        }

        private int level() {
            return ctx.model().brain().lumberjackSkillLevel();
        }

        private void finish() {
            gross.forEach(ctx::made);
            ctx.stoppedPartway(stop);
            LOGGER.info("[AwayLumberjack] {} day {}: felled {} {} ({} log block(s): {} log(s), {} stick(s), {} apple(s)), "
                            + "{} tick(s) left of {}; {} axe(s) broke, {} fetched, banked {}, trips {}; passed by: "
                            + "{} kind off, {} big without an iron axe, {} no way in, {} held by another; made {}{}",
                    npc.getNpcId().toString().substring(0, 8), ctx.day.index() + 1, felled, felledByKind, logBlocks,
                    logsGot, sticks, apples, Math.max(0L, end - t), end, broken, axes, banks, trips, kindOff.size(),
                    tooBig.size(), noWay.size(), heldByOther.size(), AwayRun.counts(gross),
                    stop == null ? "" : "; stopped: " + stop);
        }
    }
}
