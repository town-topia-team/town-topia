package com.alex.test.village.villagers.lumberjack.behavior;

import com.alex.test.Config;
import com.alex.test.animation.LumberChopSwing;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.animation.WorkAnimationStagger;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.knowledge.KnowledgeBase;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.NavGraph;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.villagers.lumberjack.AxeUtils;
import com.alex.test.village.villagers.lumberjack.LumberjackBalance;
import com.alex.test.village.villagers.lumberjack.LumberjackBigTrees;
import com.alex.test.village.villagers.lumberjack.data.LevelTreeView;
import com.alex.test.village.villagers.lumberjack.data.TreeJudge;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import com.alex.test.village.villagers.tools.ToolSpeed;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies and chops one cached tree structure.
 *
 * <p>When the chop finishes, one refreshed bounded snapshot is converted into
 * a cleanup queue. A configurable number of logs/leaves is removed each tick
 * without spawning drops. Logs are credited directly to the lumberjack and the
 * matching sapling is planted when cleanup completes.
 */
public final class ChopTreeActivity extends Activity {

    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * How close the lumberjack gets before it starts swinging, squared.
     *
     * <p>This used to be the same 36.0 the activity uses to decide a log has got out
     * of reach -- six blocks -- so a villager that happened to arrive anywhere inside
     * six blocks just stopped and chopped from there, swinging at a tree it was
     * nowhere near. Those are two different questions: how far away is too far to
     * keep working, and how close should it stand to start.
     */
    /**
     * Distance from the trunk's centre the lumberjack chops from. The centre of
     * the tile beside the trunk is only 1.0, which puts it almost against the
     * bark; it steps back into the outer half of that tile, or less if
     * something behind it is in the way.
     */
    private static final double STAND_DISTANCE = 1.5;

    private static final double SETTLE_DONE_SQ = 0.2 * 0.2;
    private static final int SETTLE_MAX_TICKS = 40;

    /** How far a log may be from the chopping spot and still be worked from it, squared. */
    private static final double LOG_REACH_SQ = 36.0;

    /** Close enough (XZ, squared) to a cell on the way in to count as on it. */
    private static final double PATH_CELL_REACHED_SQ = 0.3 * 0.3;
    private static final double PATH_SPEED = com.alex.test.village.VillagerModel.WALK_SPEED;
    /** Ticks without getting closer to the next cell before the way in counts as blocked. */
    private static final int PATH_STALL_TICKS = 40;
    /**
     * Failed ways in before the tree is left alone. Each retry plans round
     * the entry that failed, so a villager standing in the way costs one; a
     * tree that really cannot be reached no longer costs one per log.
     */
    private static final int MAX_APPROACH_RETRIES = 2;

    private static final int CHOP_ANIM_PERIOD = 19;
    private static final int CLEANUP_TIMEOUT_TICKS = 1200;

    private enum Phase { CHOPPING, CLEANUP }
    private record CleanupBlock(BlockPos position, boolean log) {}

    private final TreeStructure tree;
    private final BlockPos treeBase;
    private final BlockPos preferredLog;

    private boolean claimed;
    private Phase phase = Phase.CHOPPING;
    private int chopTimer;
    private int requiredChopTicks;
    /** The axe's ToolSpeed: how fast the tree comes down and how fast the swing clip plays. */
    private float chopSpeed = 1f;
    /** Ticks between chop sounds and log checks: CHOP_ANIM_PERIOD at the swing's speed. */
    private int chopSoundPeriod = CHOP_ANIM_PERIOD;
    private int axeSlot = -1;
    private ItemStack activeAxe = ItemStack.EMPTY;
    private double logChance;
    private boolean guaranteedLogAwarded;
    private int animationStartDelay;
    /** The swing this tree is felled with, picked when the first chop starts; kept to the end. */
    private LumberChopSwing swing;
    private int cleanupTimer;
    private int logsCollected;
    private int leavesRemoved;
    /** Cleanup blocks passed by because their chunk is not loaded (treefinish). */
    private int leftUnloaded;
    /** Sticks and apples from every leaf this job cuts; shared with the walk here. */
    private final LeafRewards leafRewards;
    private BlockPos activeLog;
    private BlockPos pendingLog;
    /** Walk over the nav graph to the {@link #plan}'s entry. */
    private MoveActivity retargetMove;
    /** The way in to the trunk; see {@link TreeApproachPlanner}. */
    private TreeApproachPlanner.Plan plan;
    /** Walking the plan's path from its entry to {@link #standSpot}, cutting leaves. */
    private boolean walkingPath;
    private int pathIndex;
    private int pathStallTicks;
    private double pathBestDistSq;
    private int approachRetries;
    private final Set<BlockPos> failedEntries = new HashSet<>();
    /** Where the lumberjack stands to chop: beside the trunk, or a step further out. */
    private BlockPos standSpot;
    /** The exact point on {@link #standSpot} to stand on, {@link #STAND_DISTANCE} out. */
    private Vec3 standPoint;
    /** Walked up; taking the last step onto {@link #standSpot} before swinging. */
    private boolean settling;
    private int settleTicks;
    private List<BlockPos> groundTrunk;
    private final Set<BlockPos> unreachableLogs = new HashSet<>();
    private final Deque<CleanupBlock> cleanupQueue = new ArrayDeque<>();
    private TreeStructure cleanupTree;

    private Block sapling;
    private Set<BlockPos> saplingPositions = Set.of();
    /** The felled group's other trunks (replantall): one spot or a 2x2 each, low corner first. */
    private List<List<BlockPos>> otherSaplings = List.of();
    /** The blocks under the trunk's feet when it was last judged (chopcheck). */
    private final Map<BlockPos, BlockState> groundSeen = new LinkedHashMap<>();

    public ChopTreeActivity(TreeStructure tree, BlockPos focusLog, LeafRewards leafRewards) {
        this.tree = tree;
        this.treeBase = tree.base();
        this.preferredLog = focusLog != null ? focusLog : tree.base();
        this.leafRewards = leafRewards;
    }

    @Override
    protected void start(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (npc == null) return;

        if (!ctx.mgr().isTreeStructureStillSafe(tree)) {
            ctx.mgr().forgetTree(treeBase);
            return;
        }
        if (!ctx.mgr().ownsTreeJob(treeBase, ctx.model().npcId)) return;
        if (!ctx.mgr().getClaims().claim(treeBase, ctx.model().npcId)) return;

        claimed = true;
        // Judged whole before the first swing (chopcheck): a block put under it
        // since it was picked leaves it standing.
        if (WalkFeel.chopCheck && !judgedOnArrival(ctx)) return;
        // Pick up where the last attempt left off, and forget that this tree
        // was ever hard to walk to -- we are standing at it.
        chopTimer = ctx.mgr().getChopProgress(ctx.model().npcId, treeBase);
        ctx.mgr().clearTreeApproachFailures(treeBase);

        // Snapshot the actual allowed axe, its speed and the skill for this tree.
        InventoryManager inventory = ctx.npc().getInventory();
        axeSlot = AxeUtils.findChoppingAxeSlot(inventory,
                LumberjackSettings.of(ctx.npc()).getAllowedAxes());
        if (axeSlot < 0) {
            releaseClaim(ctx);
            return;
        }
        activeAxe = inventory.getSlot(axeSlot);
        // Big 2x2 trees need an iron axe or better. The work loop never walks a
        // lumberjack to one without, so this only catches an axe that changed
        // on the way.
        if (!LumberjackBigTrees.mayFell(tree, AxeUtils.canFellBigTrees(activeAxe))) {
            releaseClaim(ctx);
            return;
        }
        // Off the village's tree list only once it can really be chopped: the
        // axe checks above leave it on, so it is not forgotten until the next scan.
        // With treekeep it stays on until it falls (cleanup begins in tick): a
        // chop stopped mid-swing then leaves it listed, and the held job keeps
        // other lumberjacks off it meanwhile.
        if (!WalkFeel.treeKeep) ctx.mgr().getKnowledge().remove(KnowledgeBase.Category.TREE, treeBase);
        int skill = ctx.brain().lumberjackSkillLevel();
        logChance = LumberjackBalance.logChance(skill);
        // The axe alone sets the pace; the swing clip and its sound keep up with it.
        chopSpeed = (float) ToolSpeed.of(activeAxe);
        chopSoundPeriod = Math.max(1, Math.round(CHOP_ANIM_PERIOD / chopSpeed));
        requiredChopTicks = LumberjackBalance.chopTicks(tree.logs().size(), tree.twoByTwo(), chopSpeed);
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, activeAxe.copy());

        plan = TreeApproachPlanner.plan(ctx, tree, failedEntries);
        if (plan == null) {
            failUnreachable(ctx);
            return;
        }
        standSpot = plan.standSpot();

        BlockPos target = chooseReachableLog(ctx, preferredLog);
        if (target == null) {
            failUnreachable(ctx);
            return;
        }
        beginTarget(ctx, target);
    }

    @Override
    protected Status tick(Context ctx) {
        if (!claimed) return Status.FAILED;

        NpcEntity npc = ctx.npc();
        if (npc == null) {
            stop(ctx);
            return Status.FAILED;
        }

        if (phase == Phase.CLEANUP) return tickCleanup(ctx);

        if (retargetMove != null) {
            // Leaves of this tree the lumberjack brushes against on the way,
            // and any other natural leaves it is actually blocked by.
            clearLeavesAround(ctx, npc.blockPosition(), 1);
            TreeApproachPlanner.cutLeavesPressedAgainst(ctx, leafRewards);
            Status moveStatus = retargetMove.tickOuter(ctx);
            if (moveStatus == Status.RUNNING) return Status.RUNNING;
            retargetMove.stop(ctx);
            retargetMove = null;
            // A failed walk is a failed way in, not a bad log: every log is
            // chopped from the same spot, so trying the next one only walked
            // the same route again -- once per log, on a big spruce dozens.
            if (moveStatus == Status.FAILED) return approachFailed(ctx);
            beginPath(ctx, 0);
        }

        if (walkingPath) {
            Status pathStatus = tickPath(ctx);
            if (pathStatus != null) return pathStatus;
            walkingPath = false;
            beginSettle(ctx);
        }

        if (settling) {
            clearLeavesAround(ctx, npc.blockPosition(), 1);
            if (!stepOntoStandSpot(ctx)) return Status.RUNNING;
            settling = false;
            // A last look before swinging: the ground may have changed on the way in.
            if (WalkFeel.chopCheck && !groundStillHolds(ctx)) return failNotATree(ctx);
            activeLog = pendingLog;
            pendingLog = null;
            startChoppingTarget(ctx);
        }

        faceTree(npc);

        if (animationStartDelay > 0) {
            animationStartDelay--;
            if (animationStartDelay == 0) playChopAnimation(ctx);
            return Status.RUNNING;
        }

        chopTimer++;
        // Kept where it survives this activity being rebuilt: an interruption
        // should cost the walk back, not the whole tree.
        ctx.mgr().setChopProgress(ctx.model().npcId, treeBase, chopTimer);
        if (chopTimer % chopSoundPeriod == 0) {
            if (WalkFeel.chopCheck && !groundStillHolds(ctx)) return failNotATree(ctx);
            if (activeLog == null
                    || !ctx.level().getBlockState(activeLog).is(BlockTags.LOGS)
                    || npc.distanceToSqr(Vec3.atCenterOf(activeLog)) > LOG_REACH_SQ) {
                if (activeLog != null) unreachableLogs.add(activeLog);
                BlockPos replacement = chooseReachableLog(ctx, null);
                if (replacement == null) return failUnreachable(ctx);
                beginTarget(ctx, replacement);
                return Status.RUNNING;
            }
            keepChopping(npc);
        }

        if (chopTimer < requiredChopTicks) return Status.RUNNING;

        if (ctx.npc().getInventory().getSlot(axeSlot) != activeAxe) {
            releaseClaim(ctx);
            return Status.FAILED;
        }

        if (!ctx.mgr().isTreeStructureStillSafe(tree)) {
            ctx.mgr().forgetTree(treeBase);
            releaseClaim(ctx);
            npc.setAnimation(VillagerAnimationState.IDLE);
            return Status.FAILED;
        }

        ctx.level().playSound(
                null, activeLog, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS,
                0.9f, 0.9f + ctx.level().random.nextFloat() * 0.15f);
        if (!prepareCleanup(ctx)) return failInvalidCleanup(ctx);
        // Felled: off the village's tree list now (treekeep kept it on while it stood).
        if (WalkFeel.treeKeep) ctx.mgr().getKnowledge().remove(KnowledgeBase.Category.TREE, treeBase);

        // Durability is charged per log received during cleanup, not here.
        npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);

        npc.setAnimation(VillagerAnimationState.IDLE);
        phase = Phase.CLEANUP;
        LOGGER.debug(
                "[ChopTreeActivity] Lumberjack {} completed chopping tree {}; cleanup started with {} blocks.",
                ctx.model().npcId, treeBase, cleanupQueue.size());
        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) {
        if (retargetMove != null) {
            retargetMove.stop(ctx);
            retargetMove = null;
        }
        if ((walkingPath || settling) && ctx.npc() != null) haltDirectWalk(ctx.npc());
        walkingPath = false;
        settling = false;
        // Every normal end of a chop has let go already, so still holding the
        // tree here means something stopped it. Before the tree falls that is
        // a pause: the job and the swing count wait for the walk back.
        if (claimed && WalkFeel.treeResume && phase == Phase.CHOPPING) pauseClaim(ctx);
        else if (claimed) {
            // After the fall, nothing may be left hanging in the air.
            if (WalkFeel.treeFinish && phase == Phase.CLEANUP) finishFelledTree(ctx);
            releaseClaim(ctx);
        }
        NpcEntity npc = ctx.npc();
        if (npc != null) {
            npc.setAnimation(VillagerAnimationState.IDLE);
            npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    @Override
    public String name() {
        return "chop";
    }

    /** Logs credited to the lumberjack from this tree so far. */
    public int logsCollected() {
        return logsCollected;
    }

    private boolean prepareCleanup(Context ctx) {
        cleanupTree = ctx.mgr().inspectTree(treeBase);
        if (cleanupTree == null
                || cleanupTree.logs().size() > TreeStructure.MAX_LOGS
                || cleanupTree.leaves().size() > TreeStructure.MAX_LEAVES) {
            return false;
        }

        sapling = cleanupTree.sapling(ctx.level());
        saplingPositions = cleanupTree.saplingPositions();
        // Read while the trunks still stand: cleanup takes their logs away.
        otherSaplings = WalkFeel.replantAll ? otherTrunkSpots(ctx) : List.of();
        List<BlockPos> logs = new ArrayList<>(cleanupTree.logs());
        logs.sort(Comparator
                .comparing((BlockPos pos) -> !pos.equals(treeBase))
                .thenComparingInt(BlockPos::getY));
        for (BlockPos pos : logs) {
            cleanupQueue.addLast(new CleanupBlock(pos, true));
        }

        for (BlockPos pos : cleanupTree.leaves()) {
            cleanupQueue.addLast(new CleanupBlock(pos, false));
        }
        return !cleanupQueue.isEmpty();
    }

    private Status tickCleanup(Context ctx) {
        if (++cleanupTimer > CLEANUP_TIMEOUT_TICKS) {
            LOGGER.warn(
                    "[ChopTreeActivity] Timed out cleaning tree at {} with {} blocks remaining.",
                    treeBase, cleanupQueue.size());
            if (!WalkFeel.treeFinish) {
                releaseClaim(ctx);
                return Status.FAILED;
            }
            // treefinish: the rest of the logs come down now, and the tree ends as felled.
            dropRestOfTree(ctx, true);
        }

        int budget = Config.lumberjackTreeBlocksRemovedPerTick();
        for (int processed = 0; processed < budget && !cleanupQueue.isEmpty(); processed++) {
            CleanupBlock cleanup = cleanupQueue.removeFirst();
            BlockPos pos = cleanup.position();
            if (!ctx.level().hasChunkAt(pos)) {
                // treefinish: pass it by (reading it would load the chunk) and
                // take the rest down; off, the rest was left hanging.
                if (WalkFeel.treeFinish) {
                    leftUnloaded++;
                    continue;
                }
                LOGGER.warn(
                        "[ChopTreeActivity] Stopped cleanup at {} because chunk {} is unloaded.",
                        treeBase, pos);
                releaseClaim(ctx);
                return Status.FAILED;
            }

            BlockState state = ctx.level().getBlockState(pos);
            if (cleanup.log()) {
                if (!cleanupTree.matchesLog(state)) continue;
                takeLog(ctx, pos, state, null);
            } else if (cleanupTree.matchesLeaf(state)
                    && ctx.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) {
                leavesRemoved++;
                leafRewards.award(ctx, state);
            }
        }

        if (!cleanupQueue.isEmpty()) return Status.RUNNING;

        int replantedSaplings = replantSaplings(ctx);

        ctx.mgr().getKnowledge().remove(KnowledgeBase.Category.TREE, treeBase);
        releaseClaim(ctx);
        ctx.brain().diary().log(String.format(
                "%s chop  felled tree at %d,%d,%d — collected %d logs",
                VillagerDiary.ts(ctx.level()),
                treeBase.getX(), treeBase.getY(), treeBase.getZ(), logsCollected));
        LOGGER.info(
                "[ChopTreeActivity] Cleaned tree at {}: {} logs, {} leaves, {} sticks, {} apples; replanted {} sapling(s).{}",
                treeBase, logsCollected, leavesRemoved, leafRewards.sticks(), leafRewards.apples(),
                replantedSaplings, leftUnloaded > 0 ? " " + leftUnloaded + " block(s) left in unloaded chunks." : "");
        return Status.DONE;
    }

    /**
     * One felled log: the usual chance at a log item, then gone. With
     * {@code footPile} (the lumberjack died) the item goes on that pile
     * instead of into the pack.
     */
    private void takeLog(Context ctx, BlockPos pos, BlockState state, @Nullable List<ItemStack> footPile) {
        if (!guaranteedLogAwarded || ctx.level().random.nextDouble() < logChance) {
            guaranteedLogAwarded = true;
            ItemStack logItem = new ItemStack(state.getBlock().asItem());
            if (!logItem.isEmpty()) logItem = com.alex.test.village.tokens.VillageItemOwnership.produce(logItem, ctx.mgr());
            if (!logItem.isEmpty()) {
                if (footPile != null) {
                    addToPile(footPile, logItem);
                } else if (!ctx.npc().getInventory().addItem(logItem)) {
                    ItemLossLog.pop(ctx, pos, logItem, "felled log, pack full");
                }
                logsCollected++;
                if (footPile == null) wearAxeForLog(ctx);
            }
        }
        ctx.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    private static void addToPile(List<ItemStack> pile, ItemStack stack) {
        for (ItemStack held : pile) {
            if (!ItemStack.isSameItemSameComponents(held, stack)) continue;
            int move = Math.min(stack.getCount(), held.getMaxStackSize() - held.getCount());
            held.grow(move);
            stack.shrink(move);
            if (stack.isEmpty()) return;
        }
        pile.add(stack);
    }

    /**
     * Takes every log still queued down at once (treefinish), so a cleanup cut
     * short leaves no part of the tree hanging. Leaves stay and decay on their
     * own; blocks in unloaded chunks are passed by. {@code intoPack} false (the
     * lumberjack died): the logs drop in stacks at the tree's foot.
     */
    private void dropRestOfTree(Context ctx, boolean intoPack) {
        List<ItemStack> footPile = intoPack ? null : new ArrayList<>();
        for (CleanupBlock cleanup : cleanupQueue) {
            BlockPos pos = cleanup.position();
            if (!ctx.level().hasChunkAt(pos)) {
                leftUnloaded++;
                continue;
            }
            if (!cleanup.log()) continue;
            BlockState state = ctx.level().getBlockState(pos);
            if (cleanupTree.matchesLog(state)) takeLog(ctx, pos, state, footPile);
        }
        cleanupQueue.clear();
        if (footPile == null) return;
        for (ItemStack stack : footPile) ItemLossLog.pop(ctx, treeBase, stack, "felled log, lumberjack gone");
    }

    /**
     * Stopped after the fall (an emergency, a job change, its death): the rest
     * of the logs come down now and the spot is replanted, as at the normal end.
     * Not while its body unloads: the pack was saved already and the tree's
     * chunk is going too, so the tree is left as it is.
     */
    private void finishFelledTree(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (cleanupQueue.isEmpty()) return;
        if (npc == null || (npc.isRemoved() && npc.getRemovalReason() != null && npc.getRemovalReason().shouldSave())) {
            LOGGER.info("[ChopTreeActivity] Lumberjack unloaded during cleanup of {}; {} block(s) left standing.",
                    treeBase, cleanupQueue.size());
            cleanupQueue.clear();
            return;
        }
        boolean alive = npc.isAlive() && !npc.isRemoved();
        int logsBefore = logsCollected;
        dropRestOfTree(ctx, alive);
        int replanted = alive ? replantSaplings(ctx) : 0;
        ctx.mgr().getKnowledge().remove(KnowledgeBase.Category.TREE, treeBase);
        LOGGER.info("[ChopTreeActivity] Chop stopped during cleanup of {}: {} more log item(s) at once, "
                        + "{} block(s) left in unloaded chunks, replanted {} sapling(s).",
                treeBase, logsCollected - logsBefore, leftUnloaded, replanted);
    }

    /**
     * One vanilla durability point per log actually received. Only the axe
     * this tree was started with is worn, and once it breaks the rest of the
     * already-felled tree is still cleared for free.
     */
    private void wearAxeForLog(Context ctx) {
        if (axeSlot < 0 || activeAxe.isEmpty()) return;
        InventoryManager inventory = ctx.npc().getInventory();
        if (inventory.getSlot(axeSlot) != activeAxe) return;
        if (!AxeUtils.damageAxeForLog(ctx.level(), inventory, axeSlot)) {
            activeAxe = ItemStack.EMPTY;
            ctx.level().playSound(null, ctx.npc().blockPosition(),
                    SoundEvents.ITEM_BREAK, SoundSource.NEUTRAL, 0.8f,
                    0.8f + ctx.level().random.nextFloat() * 0.4f);
        }
    }

    private int replantSaplings(Context ctx) {
        if (!LumberjackSettings.of(ctx.npc()).shouldReplant(tree.treeTypeId())) return 0;
        if (sapling == null) return 0;

        BlockState saplingState = sapling.defaultBlockState();
        int planted = plantSpot(ctx, saplingState, saplingPositions, cleanupTree.replantAnchor());
        // Each other trunk on its own: one that does not fit leaves the rest planted.
        for (List<BlockPos> spot : otherSaplings) {
            planted += plantSpot(ctx, saplingState, spot, spot.get(0));
        }
        return planted;
    }

    /** One trunk's sapling(s), all or none. */
    private int plantSpot(Context ctx, BlockState saplingState, Collection<BlockPos> spot, BlockPos anchor) {
        if (spot.isEmpty()) return 0;
        for (BlockPos position : spot) {
            if (!ctx.level().hasChunkAt(position)
                    || !ctx.level().getBlockState(position).canBeReplaced()
                    || !saplingState.canSurvive(ctx.level(), position)) {
                LOGGER.warn(
                        "[ChopTreeActivity] Could not safely replant {} sapling pattern at {}.",
                        spot.size(), anchor);
                return 0;
            }
        }

        for (BlockPos position : spot) {
            ctx.level().setBlock(position, saplingState, 3);
        }
        return spot.size();
    }

    /** Where the felled group's other trunks stand (replantall); see {@link TreeJudge#otherTrunks}. */
    private List<List<BlockPos>> otherTrunkSpots(Context ctx) {
        return otherTrunkSpots(ctx.level(), cleanupTree);
    }

    /**
     * {@link #otherTrunkSpots(Context)} for any standing tree, read while its logs
     * stand; the away lumberjack's copy uses it too.
     */
    public static List<List<BlockPos>> otherTrunkSpots(ServerLevel level, TreeStructure tree) {
        Set<Long> logs = new HashSet<>();
        for (BlockPos log : tree.logs()) logs.add(log.asLong());
        Set<Long> skip = new HashSet<>();
        for (BlockPos spot : tree.saplingPositions()) skip.add(spot.asLong());
        boolean squares = TreeStructure.canGrowTwoByTwo(tree.treeTypeId());
        // A lone dark oak sapling never grows.
        boolean singles = !TreeStructure.alwaysTwoByTwo(tree.treeTypeId());
        List<List<BlockPos>> spots = new ArrayList<>();
        for (List<Long> trunk : TreeJudge.otherTrunks(
                new LevelTreeView(level, pos -> false), logs, skip, squares)) {
            if (trunk.size() == 1 && !singles) continue;
            spots.add(trunk.stream().map(BlockPos::of).toList());
        }
        return spots;
    }

    /**
     * The tree judged fresh on arrival (chopcheck). Not a tree: off the list,
     * the job let go. Only an unloaded chunk in the way: the job let go, the
     * tree stays listed.
     */
    private boolean judgedOnArrival(Context ctx) {
        TreeStructure.Inspection now = ctx.mgr().judgeTree(treeBase);
        if (now == null || now.isTree()) {
            rememberGround(ctx);
            return true;
        }
        if (!now.cantSee()) ctx.mgr().forgetTree(treeBase);
        LOGGER.info("[ChopTreeActivity] Tree at {} refused on arrival: {} at {}.",
                treeBase, now.reason(), now.where());
        releaseClaim(ctx);
        return false;
    }

    /**
     * Remembers the blocks under the trunk's feet as just judged (chopcheck).
     * Only the judge decides what holds a tree; these only show that one of
     * them changed.
     */
    private void rememberGround(Context ctx) {
        groundSeen.clear();
        Set<BlockPos> feet = new LinkedHashSet<>(tree.saplingPositions());
        feet.add(treeBase);
        for (BlockPos foot : feet) groundSeen.put(foot.below(), ctx.level().getBlockState(foot.below()));
    }

    /**
     * On reaching the chopping spot and on each swing beat (chopcheck): when
     * a block under a trunk foot changed since the last look, the tree is
     * judged again. False when it is no longer a tree; an unloaded chunk in
     * the way keeps it.
     */
    private boolean groundStillHolds(Context ctx) {
        boolean changed = false;
        for (Map.Entry<BlockPos, BlockState> seen : groundSeen.entrySet()) {
            changed |= ctx.level().getBlockState(seen.getKey()) != seen.getValue();
        }
        if (!changed) return true;
        TreeStructure.Inspection now = ctx.mgr().judgeTree(treeBase);
        rememberGround(ctx);
        if (now == null || now.isTree() || now.cantSee()) return true;
        LOGGER.info("[ChopTreeActivity] Tree at {} is no longer a tree mid-chop: {} at {}; left standing.",
                treeBase, now.reason(), now.where());
        return false;
    }

    /** No longer a tree (chopcheck): off the list for good, the job and the swing count let go. */
    private Status failNotATree(Context ctx) {
        ctx.mgr().forgetTree(treeBase);
        releaseClaim(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        return Status.FAILED;
    }

    private Status failInvalidCleanup(Context ctx) {
        ctx.mgr().forgetTree(treeBase);
        releaseClaim(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        LOGGER.warn("[ChopTreeActivity] Refused invalid cleanup snapshot at {}.", treeBase);
        return Status.FAILED;
    }

    /**
     * Square up to the nearest trunk column, body and head, looking at it at
     * chest height. Every tick: look control and body turning otherwise drift
     * the villager off the tree between swings.
     */
    private void faceTree(NpcEntity npc) {
        BlockPos trunk = nearestTrunk(npc);
        double tx = trunk.getX() + 0.5;
        double tz = trunk.getZ() + 0.5;
        float yaw = (float) Math.toDegrees(Math.atan2(-(tx - npc.getX()), tz - npc.getZ()));
        npc.setYRot(yaw);
        npc.setYBodyRot(yaw);
        npc.setYHeadRot(yaw);
        npc.getLookControl().setLookAt(tx, npc.getY() + 1.2, tz, 30f, 30f);
    }

    private BlockPos nearestTrunk(NpcEntity npc) {
        BlockPos best = treeBase;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos log : groundTrunk()) {
            double d = horizontalDistSq(npc, log);
            if (d < bestDist) {
                bestDist = d;
                best = log;
            }
        }
        return best;
    }

    /** The trunk's bottom layer: one log, or four for a 2x2 tree. */
    private List<BlockPos> groundTrunk() {
        if (groundTrunk == null) groundTrunk = TreeApproachPlanner.groundTrunk(tree);
        return groundTrunk;
    }

    private static double horizontalDistSq(NpcEntity npc, BlockPos pos) {
        double dx = pos.getX() + 0.5 - npc.getX();
        double dz = pos.getZ() + 0.5 - npc.getZ();
        return dx * dx + dz * dz;
    }

    // -------------------------------------------------------------------------
    // Leaves in the way
    // -------------------------------------------------------------------------

    /**
     * Removes this tree's leaves in the body space (feet and head height)
     * within {@code radius} blocks of {@code centre}. Only leaves of the tree
     * being felled -- the whole canopy goes at cleanup anyway, so this only
     * takes some of it a little early, rewards included.
     */
    private void clearLeavesAround(Context ctx, BlockPos centre, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = centre.offset(dx, dy, dz);
                    if (isTreeLeaf(ctx, pos)) removeLeaf(ctx, pos);
                }
            }
        }
    }

    private boolean isTreeLeaf(Context ctx, BlockPos pos) {
        return tree.leaves().contains(pos)
                && tree.matchesLeaf(ctx.level().getBlockState(pos))
                && !ctx.mgr().isProtectedTreePosition(pos);
    }

    /**
     * Cuts a leaf block. Every leaf rolls for a stick and an apple, a
     * neighbour's boughs cut on the way in as much as this tree's own; the
     * village stick cap and the low apple chance keep that from paying.
     */
    private void removeLeaf(Context ctx, BlockPos pos) {
        boolean ours = isTreeLeaf(ctx, pos);
        if (leafRewards.cut(ctx, pos) && ours) leavesRemoved++;
    }

    /** Cuts {@code pos} if it holds natural leaves the lumberjack may cut through. */
    private void cutIfClearable(Context ctx, BlockPos pos) {
        if (TreeApproachPlanner.isClearableLeaf(ctx, pos)) removeLeaf(ctx, pos);
    }

    // -------------------------------------------------------------------------
    // Way in
    // -------------------------------------------------------------------------

    /**
     * Heads for the chopping spot along {@link #plan}: straight along its path
     * when already standing on it, otherwise over the nav graph to its entry
     * first. Only standing on it counts -- a cell a block away can be on the
     * far side of a fence, and steering straight at it is walking into the
     * fence.
     */
    private void beginApproach(Context ctx) {
        settling = false;
        walkingPath = false;
        if (retargetMove != null) {
            retargetMove.stop(ctx);
            retargetMove = null;
        }
        int join = plan.path().indexOf(NavGraph.feetCell(ctx.npc()));
        if (join >= 0) {
            beginPath(ctx, join);
            return;
        }
        retargetMove = new MoveActivity(plan.entryPos(),
                com.alex.test.village.VillagerModel.WALK_SPEED, true);
    }

    private void beginPath(Context ctx, int fromIndex) {
        ctx.model().stopMoving();
        ctx.npc().getNavigation().stop();
        walkingPath = true;
        pathIndex = fromIndex;
        pathStallTicks = 0;
        pathBestDistSq = Double.MAX_VALUE;
    }

    /**
     * Walks the plan's path a cell at a time, cutting the leaves in the way
     * just ahead. Vanilla navigation cannot plan through leaves, so this
     * steers directly: every step is one block sideways and at most one up
     * or down, and the planner has already checked there is nothing but
     * leaves in it.
     *
     * @return null once on the chopping spot, otherwise the status to return
     */
    private Status tickPath(Context ctx) {
        NpcEntity npc = ctx.npc();
        List<BlockPos> path = plan.path();

        // Whatever the body is already brushing, so a corner can't snag it.
        clearClearableLeavesIn(ctx, npc.getBoundingBox().inflate(0.1, 0.0, 0.1));

        while (pathIndex < path.size() && isOnCell(npc, path.get(pathIndex))) {
            pathIndex++;
            pathStallTicks = 0;
            pathBestDistSq = Double.MAX_VALUE;
        }
        if (pathIndex >= path.size()) {
            npc.getNavigation().stop();
            return null;
        }

        BlockPos next = path.get(pathIndex);
        BlockPos from = pathIndex > 0 ? path.get(pathIndex - 1) : NavGraph.feetCell(npc);
        cutIfClearable(ctx, next);
        cutIfClearable(ctx, next.above());
        if (from.getY() != next.getY()) {
            // Room over the lower cell for the head, going up or coming down.
            cutIfClearable(ctx, (from.getY() < next.getY() ? from : next).above(2));
        }
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || !TreeApproachPlanner.isWalkable(ctx, graph, next)) {
            // Something other than leaves has moved in since the plan was made.
            return approachFailed(ctx);
        }

        npc.getMoveControl().setWantedPosition(
                next.getX() + 0.5, next.getY(), next.getZ() + 0.5, PATH_SPEED);

        double dy = npc.getY() - next.getY();
        double dist = horizontalDistSq(npc, next) + dy * dy;
        if (dist < pathBestDistSq - 0.01) {
            pathBestDistSq = dist;
            pathStallTicks = 0;
        } else if (++pathStallTicks > PATH_STALL_TICKS) {
            return approachFailed(ctx);
        }
        return Status.RUNNING;
    }

    private static boolean isOnCell(NpcEntity npc, BlockPos cell) {
        return horizontalDistSq(npc, cell) <= PATH_CELL_REACHED_SQ
                && Math.abs(npc.getY() - cell.getY()) <= 0.6;
    }

    /**
     * The way in did not work out. Plan round the entry that failed and try
     * again, a limited number of times; then leave the tree for later rather
     * than walking into the same wall.
     */
    private Status approachFailed(Context ctx) {
        haltDirectWalk(ctx.npc());
        ctx.model().stopMoving();
        walkingPath = false;
        if (plan != null) failedEntries.add(plan.entryPos());
        if (++approachRetries > MAX_APPROACH_RETRIES) {
            LOGGER.debug("[ChopTreeActivity] Lumberjack {} gave up on the way in to tree {}.",
                    ctx.model().npcId, treeBase);
            return failUnreachable(ctx);
        }
        plan = TreeApproachPlanner.plan(ctx, tree, failedEntries);
        if (plan == null) return failUnreachable(ctx);
        if (!plan.standSpot().equals(standSpot)) standPoint = null;
        standSpot = plan.standSpot();
        beginApproach(ctx);
        return Status.RUNNING;
    }

    /** Ends a direct walk: the move control otherwise keeps pushing towards its last target. */
    private static void haltDirectWalk(NpcEntity npc) {
        npc.getNavigation().stop();
        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0);
    }

    private void clearClearableLeavesIn(Context ctx, AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(
                (int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
                (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            cutIfClearable(ctx, pos.immutable());
        }
    }

    // -------------------------------------------------------------------------
    // Standing spot
    // -------------------------------------------------------------------------

    private void beginSettle(Context ctx) {
        clearLeavesAround(ctx, standSpot, 0);
        standPoint = chooseStandPoint(ctx);
        settling = true;
        settleTicks = 0;
        ctx.model().stopMoving();
    }

    /**
     * The point on {@link #standSpot} to chop from: {@link #STAND_DISTANCE}
     * from the trunk's centre, straight out from it. Backs off in quarter
     * blocks towards the tile centre when the villager would not fit there
     * (a wall, a fence, rising ground). Spots already further out than that
     * -- the fallback ring -- are used at their centre.
     */
    private Vec3 chooseStandPoint(Context ctx) {
        NpcEntity npc = ctx.npc();
        Vec3 spotCentre = Vec3.atBottomCenterOf(standSpot);
        BlockPos trunk = nearestTrunkTo(standSpot);
        Vec3 trunkCentre = new Vec3(trunk.getX() + 0.5, standSpot.getY(), trunk.getZ() + 0.5);
        Vec3 out = spotCentre.subtract(trunkCentre);
        double spotDist = out.horizontalDistance();
        if (spotDist <= 0 || spotDist >= STAND_DISTANCE) return spotCentre;

        Vec3 dir = out.scale(1.0 / spotDist);
        for (double dist = STAND_DISTANCE; dist > spotDist; dist -= 0.25) {
            Vec3 point = trunkCentre.add(dir.scale(dist));
            AABB box = npc.getBoundingBox()
                    .move(point.x - npc.getX(), point.y - npc.getY(), point.z - npc.getZ())
                    .deflate(1.0E-3);
            clearLeavesIn(ctx, box);
            if (ctx.level().noCollision(npc, box)) return point;
        }
        return spotCentre;
    }

    private BlockPos nearestTrunkTo(BlockPos spot) {
        BlockPos best = treeBase;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos log : groundTrunk()) {
            double dx = log.getX() - spot.getX();
            double dz = log.getZ() - spot.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = log;
            }
        }
        return best;
    }

    private void clearLeavesIn(Context ctx, AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(
                (int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
                (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            if (isTreeLeaf(ctx, pos)) removeLeaf(ctx, pos.immutable());
        }
    }

    private boolean isAtStandPoint(NpcEntity npc) {
        if (standPoint == null) return false;
        double dx = standPoint.x - npc.getX();
        double dz = standPoint.z - npc.getZ();
        return dx * dx + dz * dz <= SETTLE_DONE_SQ
                && Math.abs(npc.getY() - standPoint.y) <= 0.5;
    }

    /** Walks the last stretch onto {@link #standPoint}; true once there or out of tries. */
    private boolean stepOntoStandSpot(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (standPoint == null || isAtStandPoint(npc) || ++settleTicks > SETTLE_MAX_TICKS) {
            settleTicks = 0;
            npc.getNavigation().stop();
            return true;
        }
        npc.getMoveControl().setWantedPosition(standPoint.x, standPoint.y, standPoint.z, 0.5);
        return false;
    }

    private BlockPos chooseReachableLog(Context ctx, BlockPos preferred) {
        if (preferred != null && !unreachableLogs.contains(preferred)
                && isReachableLog(ctx, preferred)) {
            return preferred;
        }
        BlockPos npcPos = ctx.npc().blockPosition();
        return tree.logs().stream()
                .filter(pos -> !unreachableLogs.contains(pos))
                .filter(pos -> isReachableLog(ctx, pos))
                .min(Comparator.comparingInt((BlockPos pos) -> pos.getY())
                        .thenComparingDouble(pos -> pos.distSqr(npcPos)))
                .orElse(null);
    }

    /**
     * A log worth swinging at: still there, not part of anything protected,
     * and within the reach the chop loop allows of the chopping spot. The
     * whole tree is felled from the ground, so the log only has to be close,
     * not have a graph node of its own nearby -- a trunk wrapped in low
     * boughs has none, and every log used to fail that test.
     */
    private boolean isReachableLog(Context ctx, BlockPos pos) {
        if (!ctx.level().getBlockState(pos).is(BlockTags.LOGS)) return false;
        if (ctx.mgr().isProtectedTreePosition(pos)) return false;
        return standSpot == null
                || Vec3.atBottomCenterOf(standSpot).distanceToSqr(Vec3.atCenterOf(pos)) <= LOG_REACH_SQ;
    }

    /**
     * Sets {@code target} as the log to cut. Chops at once when already on the
     * chopping spot; otherwise walks in first. Every log is cut from the same
     * spot, so a new log never means a new way in.
     */
    private void beginTarget(Context ctx, BlockPos target) {
        if (isAtStandPoint(ctx.npc())) {
            activeLog = target;
            pendingLog = null;
            startChoppingTarget(ctx);
            return;
        }
        activeLog = null;
        pendingLog = target;
        beginApproach(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
    }

    private void startChoppingTarget(Context ctx) {
        faceTree(ctx.npc());
        animationStartDelay = WorkAnimationStagger.delayTicks(ctx.npc().getId());
        if (animationStartDelay == 0) {
            playChopAnimation(ctx);
        } else {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
    }

    private void playChopAnimation(Context ctx) {
        // The thunks come with the blows, on each client (NpcEntity.tickChopBites).
        keepChopping(ctx.npc());
    }

    /**
     * The swing at the axe's speed: the lumberjack's own two-handed chop
     * (LUMBER_CHOP; the rancher keeps CHOP). A clip already playing carries on
     * looping (every way out of it goes through IDLE, which resets the speed, so
     * the state here is always this tree's); anything else starts it fresh at the
     * axe's speed, as this tree's swing, picked at random the first time.
     */
    private void keepChopping(NpcEntity npc) {
        if (npc.getAnimation() == VillagerAnimationState.LUMBER_CHOP) {
            npc.setAnimation(VillagerAnimationState.LUMBER_CHOP);
        } else {
            if (swing == null) {
                LumberChopSwing[] all = LumberChopSwing.values();
                swing = all[npc.getRandom().nextInt(all.length)];
            }
            npc.startLumberChop(swing, chopSpeed);
        }
    }

    private Status failUnreachable(Context ctx) {
        if (ctx.mgr().isTreeStructureStillSafe(tree)) {
            ctx.mgr().getKnowledge().add(KnowledgeBase.Category.TREE, treeBase);
        }
        releaseClaim(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        return Status.FAILED;
    }

    private void releaseClaim(Context ctx) {
        ctx.mgr().getClaims().release(treeBase, ctx.model().npcId);
        ctx.mgr().releaseTreeJob(treeBase, ctx.model().npcId);
        claimed = false;
    }

    /**
     * Stopped mid-chop: only the work spot goes. The tree job (other
     * lumberjacks keep off it) and the swing count stay, as when it is
     * interrupted on the walk there; {@code LumberjackActivity} resumes it.
     */
    private void pauseClaim(Context ctx) {
        ctx.mgr().getClaims().release(treeBase, ctx.model().npcId);
        claimed = false;
    }
}
