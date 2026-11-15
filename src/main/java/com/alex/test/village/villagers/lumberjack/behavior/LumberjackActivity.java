package com.alex.test.village.villagers.lumberjack.behavior;

import com.alex.test.village.knowledge.KnowledgeBase;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.PreTaskHungerActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.lumberjack.AxeUtils;
import com.alex.test.village.villagers.lumberjack.LumberjackBalance;
import com.alex.test.village.villagers.lumberjack.LumberjackBigTrees;
import com.alex.test.village.villagers.lumberjack.LumberjackProfession;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import com.alex.test.village.villagers.tools.ToolUpgradeHelper;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Work session for a lumberjack.
 *
 * <p>Runs one tree-sized work slice before yielding back to the brain:
 * <ol>
 *   <li>Claim the nearest available tree and start a {@link MoveActivity} to it.</li>
 *   <li>On arrival, hand off to {@link ChopTreeActivity} (which integrates the
 *       old {@code CheckTreeTask} verify-step and the full chop sequence).</li>
 *   <li>After a successful chop, deposit carried items if needed.</li>
 *   <li>Return {@link Status#DONE} so {@link com.alex.test.village.brain.VillagerBrain}
 *       scores hunger, work, and leisure again before the next tree.</li>
 * </ol>
 */
public final class LumberjackActivity extends WorkActivity {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float CHOP_HUNGER_FLOOR  = 40f;

    private enum Phase { GET_AXE, MOVE, PREPARE_HUNGER, CHOP, DEPOSIT }
    private record TreeApproach(BlockPos log, NavNode node) {}

    /** Within this of the claimed tree (squared), leaves the walk is blocked by are cut. Ten blocks. */
    private static final double NEAR_TREE_SQ = 100.0;

    private int     treesChopped;
    private BlockPos claimedTree;
    private BlockPos claimedAxeUpgradeStorage;
    private TreeStructure claimedStructure;
    private BlockPos chopFocus;
    private Phase   phase;
    private long lastSeenAxeStorageChangeId;
    private boolean hungerCheckedForCurrentTree;
    /** Trees whose approach failed this work session; retried next session. */
    private final Set<BlockPos> failedTrees = new HashSet<>();
    /**
     * Server time one tree search may use per tick. Each candidate costs a shape
     * inspection and an approach plan; in a dense forest with no way in to most
     * trees, one search walked hundreds of them in a single tick and froze the
     * beta server for 7.4 s (2026-10-18). Past the budget the search stops and
     * carries on next tick, so the same tree is chosen, a few ticks later.
     */
    private static final long TREE_SEARCH_BUDGET_NANOS = 3_000_000L;
    /** The last tree search ran out of budget before finishing; resume it, don't give up. */
    private boolean treeSearchUnfinished;
    /** Trees of a disabled type already passed over by the search in progress. */
    private final Set<BlockPos> skippedThisSearch = new HashSet<>();
    /** Entry cells of the claimed tree that a walk failed to reach; planned round next time. */
    private final Set<BlockPos> failedEntries = new HashSet<>();
    /** Entry cell of the walk in progress, so a failure can be pinned on it. */
    private BlockPos approachEntry;
    /** Sticks and apples from leaves cut for the claimed tree, on the walk there and at it. */
    private LeafRewards leafRewards = new LeafRewards();

    private GetAxeActivity   getAxeActivity;
    private MoveActivity     moveActivity;
    private PreTaskHungerActivity preTaskHungerActivity;
    private ChopTreeActivity chopActivity;
    private DepositActivity  depositActivity;
    private boolean finishAfterDeposit;

    // =========================================================================

    @Override
    protected void start(Context ctx) {
        LumberjackSettings settings = LumberjackSettings.of(ctx.npc());
        if (!settings.works()) return;

        // Require an axe before doing any work. This comes before the
        // full-pack deposit so the axe is crafted from the logs being carried;
        // the deposit follows once the axe is in hand (or fails to be).
        if (AxeUtils.findUsableAxeSlot(ctx.npc().getInventory(), settings.getAllowedAxes()) < 0) {
            getAxeActivity = new GetAxeActivity();
            phase = Phase.GET_AXE;
            return;
        }

        // A full pack is banked before any tree, including a resumed one.
        // With nowhere to bank it, leave the tree for later: doTick ends the
        // session rather than chopping more onto the pile.
        if (carriedLogs(ctx) >= carryLimit(ctx)) {
            if (DepositActivity.countDepositable(ctx) > 0) startDeposit(ctx);
            return;
        }

        resumeClaimedTree(ctx, settings);
    }

    /**
     * Heads back to the tree this lumberjack still holds (interrupted on the
     * walk there, or a paused chop), or lets go of it when it can no longer be
     * worked. True when the walk back has started.
     */
    private boolean resumeClaimedTree(Context ctx, LumberjackSettings settings) {
        TreeStructure resumed = ctx.mgr().getClaimedTreeJob(ctx.model().npcId);
        if (resumed == null) return false;
        if (!settings.isTreeEnabled(resumed.treeTypeId())
                || !LumberjackBigTrees.mayFell(resumed, AxeUtils.hasBigTreeAxe(ctx.npc()))) {
            ctx.mgr().releaseTreeJob(resumed.base(), ctx.model().npcId);
        } else if (!ctx.level().hasChunkAt(resumed.base())) {
            // Out of sight, not gone. Set it aside for this session only.
            failedTrees.add(resumed.base());
            ctx.mgr().releaseTreeJob(resumed.base(), ctx.model().npcId);
        } else if (!ctx.mgr().isTreeStructureStillSafe(resumed)) {
            ctx.mgr().forgetTree(resumed.base());
            failedTrees.add(resumed.base());
        } else {
            failedEntries.clear();
            TreeApproach approach = findApproach(ctx, resumed, failedEntries);
            if (approach != null) {
                claimedTree = resumed.base();
                claimedStructure = resumed;
                leafRewards = new LeafRewards();
                chopFocus = approach.log();
                hungerCheckedForCurrentTree = false;
                startMoveToClaimedTree(ctx, approach.node(), true);
                return true;
            }
            failedTrees.add(resumed.base());
            ctx.mgr().blacklistTreeTemporarily(
                    resumed.base(), ctx.level().getGameTime());
            ctx.mgr().releaseTreeJob(resumed.base(), ctx.model().npcId);
        }
        return false;
    }

    @Override
    protected Status doTick(Context ctx) {

        // ── GET_AXE sub-phase ───────────────────────────────────────────────
        if (phase == Phase.GET_AXE && getAxeActivity != null) {
            boolean wasUpgrade = claimedAxeUpgradeStorage != null;
            Status s = getAxeActivity.tickOuter(ctx);
            if (s == Status.RUNNING) return Status.RUNNING;
            getAxeActivity.stop(ctx);
            getAxeActivity = null;
            releaseAxeUpgradeClaim(ctx);
            if (s == Status.FAILED && !wasUpgrade) {
                // No axe, and none to be had. Bank whatever is in the pack
                // before giving up: a lumberjack in this state is usually
                // carrying the very logs the village needs to make it another
                // axe, and returning FAILED here strands both. This used to be
                // a silent exit, which is why it took an eighteen-day
                // playthrough to notice a lumberjack idling with eighty logs.
                int dep = DepositActivity.countDepositable(ctx);
                ctx.brain().diary().log(VillagerDiary.ts(ctx.level())
                        + " work  no axe available — " + (dep > 0
                        ? "banking " + dep + " carried item(s) first"
                        : "nothing to bank, giving up"));
                if (dep > 0) return startDeposit(ctx, true);
                return Status.FAILED;
            }
            phase = null;
            // Fall through to tree selection below
        }

        // ── DEPOSIT sub-phase ───────────────────────────────────────────────
        if (phase == Phase.DEPOSIT && depositActivity != null) {
            Status s = depositActivity.tickOuter(ctx);
            if (s != Status.RUNNING) {
                depositActivity.stop(ctx);
                depositActivity = null;
                phase = null;
                ctx.npc().setActivityLabel("work");
                boolean shouldFinish = finishAfterDeposit || sessionDone(ctx);
                finishAfterDeposit = false;
                if (shouldFinish) return Status.DONE;
                // else fall through to pick next tree
            } else {
                return Status.RUNNING;
            }
        }

        // ── CHOP sub-phase ──────────────────────────────────────────────────
        if (phase == Phase.PREPARE_HUNGER && preTaskHungerActivity != null) {
            Status s = preTaskHungerActivity.tickOuter(ctx);
            if (s == Status.RUNNING) return Status.RUNNING;

            preTaskHungerActivity.stop(ctx);
            preTaskHungerActivity = null;
            hungerCheckedForCurrentTree = true;

            if (!returnToClaimedTreeAfterHungerCheck(ctx)) {
                phase = null;
            }
            return Status.RUNNING;
        }

        if (phase == Phase.CHOP && chopActivity != null) {
            Status s = chopActivity.tickOuter(ctx);
            if (s == Status.RUNNING) return Status.RUNNING;

            int logsFromTree = chopActivity.logsCollected();
            chopActivity.stop(ctx);
            chopActivity = null;

            boolean choppedTree = s == Status.DONE;
            if (choppedTree) {
                treesChopped++;
                ctx.brain().onLumberjackTreeFelled(ctx, logsFromTree);
            }
            else if (claimedTree != null) {
                failedTrees.add(claimedTree);
                ctx.mgr().blacklistTreeTemporarily(
                        claimedTree, ctx.level().getGameTime());
            }
            // Release the resource claim regardless of DONE/FAILED
            releaseTree(ctx);
            phase = null;

            int dep = DepositActivity.countDepositable(ctx);
            if (choppedTree) {
                if (dep > 0 && (carriedLogs(ctx) >= carryLimit(ctx) || sessionDone(ctx))) {
                    return startDeposit(ctx, true);
                }
                return Status.DONE;
            }
            if (dep > 0 && sessionDone(ctx)) {
                return startDeposit(ctx);
            }
            if (sessionDone(ctx)) return Status.DONE;
            // else fall through to next tree
        }

        // ── MOVE sub-phase ──────────────────────────────────────────────────
        if (phase == Phase.MOVE && moveActivity != null) {
            // Near its tree, the canopy's edge is what the route clips; cut
            // what blocks it rather than wait to be called stuck. Only near
            // it: out in the village a leaf block is not its business.
            if (claimedTree != null
                    && ctx.npc().blockPosition().distSqr(claimedTree) <= NEAR_TREE_SQ) {
                TreeApproachPlanner.cutLeavesPressedAgainst(ctx, leafRewards);
            }
            Status s = moveActivity.tickOuter(ctx);
            if (s == Status.RUNNING) return Status.RUNNING;

            moveActivity.stop(ctx);
            moveActivity = null;

            if (s == Status.FAILED) {
                // Count the failure where it will survive: this activity is
                // rebuilt from scratch whenever anything preempts work, so a
                // tally kept here alone can never reach the point of giving up.
                boolean setAside = claimedTree != null
                        && ctx.mgr().recordTreeApproachFailure(
                                claimedTree, ctx.level().getGameTime());

                if (!setAside && claimedStructure != null && approachEntry != null) {
                    // The walk to that entry failed; plan round it rather
                    // than blaming a log -- every log is chopped from the
                    // same spot, so a different log is the same walk.
                    failedEntries.add(approachEntry);
                    TreeApproach alternative = findApproach(
                            ctx, claimedStructure, failedEntries);
                    if (alternative != null) {
                        chopFocus = alternative.log();
                        startMoveToClaimedTree(ctx, alternative.node(), true);
                        return Status.RUNNING;
                    }
                }

                // No approach to any remaining log in this cached tree.
                BlockPos failedPos = claimedTree;
                if (failedPos != null) failedTrees.add(failedPos);
                if (failedPos != null) {
                    ctx.mgr().blacklistTreeTemporarily(
                            failedPos, ctx.level().getGameTime());
                }
                releaseTree(ctx);
                phase = null;
                ctx.brain().diary().log(VillagerDiary.ts(ctx.level())
                        + " work  move failed — skipping tree " + failedPos);
                // fall through to try the next tree
            } else {
                // Arrived — start chopping
                LumberjackSettings settings = LumberjackSettings.of(ctx.npc());
                if (!settings.works()
                        || claimedStructure == null
                        || !settings.isTreeEnabled(claimedStructure.treeTypeId())
                        || !LumberjackBigTrees.mayFell(claimedStructure, AxeUtils.hasBigTreeAxe(ctx.npc()))) {
                    releaseTree(ctx);
                    phase = null;
                    return Status.DONE;
                }
                if (needsHungerPreparation(ctx)) {
                    phase = Phase.PREPARE_HUNGER;
                    preTaskHungerActivity = new PreTaskHungerActivity(CHOP_HUNGER_FLOOR);
                    ctx.npc().setActivityLabel("eat before chop");
                    ctx.brain().diary().log(String.format(
                            "%s work  preparing hunger before chopping (hunger=%.1f, target=%.1f)",
                            VillagerDiary.ts(ctx.level()), ctx.brain().needs().hunger,
                            CHOP_HUNGER_FLOOR));
                    return Status.RUNNING;
                }

                startChoppingClaimedTree(ctx);
                return Status.RUNNING;
            }
        }

        // ── No active sub-phase: select next action ─────────────────────────
        if (sessionDone(ctx)) {
            int dep = DepositActivity.countDepositable(ctx);
            if (dep > 0) return startDeposit(ctx);
            return Status.DONE;
        }

        if (carriedLogs(ctx) >= carryLimit(ctx)) {
            int dep = DepositActivity.countDepositable(ctx);
            if (dep > 0) return startDeposit(ctx);
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level())
                    + " work  pack full (" + carriedLogs(ctx) + "/" + carryLimit(ctx)
                    + " logs) and no storage will take them — not chopping");
            return Status.DONE;
        }

        // Axe may have broken after the last tree; get a new one before continuing
        Set<Item> allowedAxes = LumberjackSettings.of(ctx.npc()).getAllowedAxes();
        if (AxeUtils.findUsableAxeSlot(ctx.npc().getInventory(), allowedAxes) < 0) {
            getAxeActivity = new GetAxeActivity();
            phase = Phase.GET_AXE;
            return Status.RUNNING;
        }

        if (tryStartAxeUpgrade(ctx, allowedAxes)) {
            return Status.RUNNING;
        }

        // A tree still held (it banked a full pack or fetched an axe first) comes
        // before a new one: the search skips held trees, its own too, and it may
        // not reserve a second, so it ended work with "no trees available".
        if (WalkFeel.treeResume && !treeSearchUnfinished
                && resumeClaimedTree(ctx, LumberjackSettings.of(ctx.npc()))) {
            return Status.RUNNING;
        }

        if (!startNextTree(ctx)) {
            if (treeSearchUnfinished) return Status.RUNNING;   // out of this tick's budget; continue next tick
            if (treesChopped > 0) {
                ctx.brain().diary().log(VillagerDiary.ts(ctx.level())
                        + " work  no more trees — session done (" + treesChopped + " chopped)");
                int dep = DepositActivity.countDepositable(ctx);
                if (dep > 0) return startDeposit(ctx);
                return Status.DONE;
            }
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " work  no trees available — FAILED");
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    @Override
    protected void doStop(Context ctx) {
        if (getAxeActivity  != null) { getAxeActivity.stop(ctx);  getAxeActivity  = null; }
        if (depositActivity != null) { depositActivity.stop(ctx); depositActivity = null; }
        finishAfterDeposit = false;
        if (preTaskHungerActivity != null) {
            preTaskHungerActivity.stop(ctx);
            preTaskHungerActivity = null;
        }
        if (chopActivity    != null) { chopActivity.stop(ctx);    chopActivity    = null; }
        if (moveActivity    != null) { moveActivity.stop(ctx);    moveActivity    = null; }
        // Keep the owner-aware tree job across temporary activity switches.
        // VillageManager releases it explicitly on completion/failure/unload/death.
        claimedTree = null;
        claimedStructure = null;
        chopFocus = null;
        hungerCheckedForCurrentTree = false;
        failedEntries.clear();
        approachEntry = null;
        releaseAxeUpgradeClaim(ctx);
        ctx.mgr().getClaims().releaseAll(ctx.model().npcId);
        ctx.model().stopMoving();
    }

    /** True while the villager is walking toward a tree (open to conversation). */
    @Override
    public boolean isMovingPhase() { return phase == Phase.MOVE; }

    @Override
    public boolean allowsRoutineInterruption() {
        return phase != Phase.CHOP;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean sessionDone(Context ctx) {
        return !LumberjackSettings.of(ctx.npc()).works();
    }

    /** At or over the carry limit, and no storage will take the load. */
    public static boolean isPackFullWithNowhereToBank(Context ctx) {
        return carriedLogs(ctx) >= carryLimit(ctx)
                && DepositActivity.countDepositable(ctx) == 0;
    }

    private static int carryLimit(Context ctx) {
        return LumberjackBalance.carryLogs(ctx.brain().lumberjackSkillLevel());
    }

    private static int carriedLogs(Context ctx) {
        int count = 0;
        for (int i = 0; i < com.alex.test.entity.inventory.InventoryManager.SIZE; i++) {
            ItemStack stack = ctx.npc().getInventory().getSlot(i);
            if (stack.is(ItemTags.LOGS)) count += stack.getCount();
        }
        return count;
    }

    private Status startDeposit(Context ctx) {
        return startDeposit(ctx, false);
    }

    private Status startDeposit(Context ctx, boolean finishAfterDeposit) {
        this.finishAfterDeposit = finishAfterDeposit;
        phase           = Phase.DEPOSIT;
        depositActivity = new DepositActivity();
        ctx.npc().setActivityLabel("deposit");
        return Status.RUNNING;
    }

    private void releaseTree(Context ctx) {
        if (claimedTree != null) {
            ctx.mgr().releaseTreeJob(claimedTree, ctx.model().npcId);
            claimedTree = null;
        }
        claimedStructure = null;
        chopFocus = null;
        hungerCheckedForCurrentTree = false;
    }

    private boolean tryStartAxeUpgrade(Context ctx, Set<Item> allowedAxes) {
        if (allowedAxes.isEmpty()) return false;

        long newestChange = ToolUpgradeHelper.newestToolStorageChangeId(ctx);
        if (newestChange <= lastSeenAxeStorageChangeId) return false;

        StorageBuilding upgradeStorage = ToolUpgradeHelper.nearestStorageWithBetterTool(
                ctx, GetAxeActivity.AXE_REQUIREMENT);
        if (upgradeStorage == null) {
            lastSeenAxeStorageChangeId = newestChange;
            return false;
        }

        // A busy door is retried after the next tree, not forgotten until storage changes again.
        BlockPos claimPos = upgradeStorage.getDoorPos();
        if (!ctx.mgr().getClaims().claim(claimPos, ctx.model().npcId)) return false;
        lastSeenAxeStorageChangeId = newestChange;

        claimedAxeUpgradeStorage = claimPos;
        getAxeActivity = GetAxeActivity.forUpgrade(upgradeStorage);
        phase = Phase.GET_AXE;
        ctx.npc().setActivityLabel("upgrade axe");
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level())
                + " work  heading to storage for a better axe");
        return true;
    }

    private void releaseAxeUpgradeClaim(Context ctx) {
        if (claimedAxeUpgradeStorage == null) return;
        ctx.mgr().getClaims().release(claimedAxeUpgradeStorage, ctx.model().npcId);
        claimedAxeUpgradeStorage = null;
        ctx.npc().setActivityLabel("work");
    }

    private boolean needsHungerPreparation(Context ctx) {
        var needs = ctx.brain().needs();
        return !hungerCheckedForCurrentTree
                && needs.hungerRules().isBelow(needs, CHOP_HUNGER_FLOOR);
    }

    private void startChoppingClaimedTree(Context ctx) {
        phase = Phase.CHOP;
        chopActivity = new ChopTreeActivity(claimedStructure, chopFocus, leafRewards);
        LOGGER.debug(
                "[LumberjackActivity] Lumberjack {} started chop job for tree {}.",
                ctx.model().npcId, claimedTree);
    }

    private boolean returnToClaimedTreeAfterHungerCheck(Context ctx) {
        if (claimedStructure == null || claimedTree == null) return false;

        LumberjackSettings settings = LumberjackSettings.of(ctx.npc());
        if (!settings.works() || !settings.isTreeEnabled(claimedStructure.treeTypeId())
                || !LumberjackBigTrees.mayFell(claimedStructure, AxeUtils.hasBigTreeAxe(ctx.npc()))) {
            releaseTree(ctx);
            return false;
        }

        TreeApproach approach = findApproach(ctx, claimedStructure, failedEntries);
        if (approach == null) {
            failedTrees.add(claimedTree);
            ctx.mgr().blacklistTreeTemporarily(
                    claimedTree, ctx.level().getGameTime());
            releaseTree(ctx);
            return false;
        }

        chopFocus = approach.log();
        startMoveToClaimedTree(ctx, approach.node(), true);
        return true;
    }

    private boolean startNextTree(Context ctx) {
        boolean resuming = treeSearchUnfinished;
        treeSearchUnfinished = false;
        if (!resuming) skippedThisSearch.clear();
        LumberjackSettings settings = LumberjackSettings.of(ctx.npc());
        if (!settings.works()) return false;
        long searchStarted = System.nanoTime();
        int examined = 0;
        BlockPos npcPos  = ctx.npc().blockPosition();
        NavGraph graph   = ctx.mgr().getGraph();
        boolean bigTreeAxe = AxeUtils.hasBigTreeAxe(ctx.npc());

        // Pick the nearest tree that: isn't claimed, hasn't failed this session,
        // and has a way in the lumberjack can actually walk (TreeApproachPlanner).
        // A tree behind a fence with no gate, or walled in by a build, has
        // none and is passed over here -- without walking up to it first.
        BlockPos bestTree = null;
        NavNode  bestNode = null;

        List<BlockPos> candidates = new ArrayList<>(
                ctx.mgr().getKnowledge().get(KnowledgeBase.Category.TREE));
        candidates.removeIf(pos ->
                ctx.mgr().isResourceClaimed(pos)
                        || failedTrees.contains(pos)
                        || skippedThisSearch.contains(pos)
                        || ctx.mgr().isTreeTemporarilyBlacklisted(
                                pos, ctx.level().getGameTime()));
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(npcPos)));

        // Tree bases are cheap cached knowledge. Expand/validate shapes in
        // nearest-first order and stop at the first usable job, so a lumberjack
        // does not flood-fill every tree in a large village.
        for (BlockPos pos : candidates) {
            // Out of this tick's budget: stop here, exactly as a failed search
            // leaves things, and resume next tick. Every tree examined so far is
            // in failedTrees or skippedThisSearch, so the next tick starts where
            // this one stopped.
            if (examined++ > 0 && System.nanoTime() - searchStarted > TREE_SEARCH_BUDGET_NANOS) {
                treeSearchUnfinished = true;
                claimedStructure = null;
                chopFocus = null;
                hungerCheckedForCurrentTree = false;
                return false;
            }
            // "I cannot see it" is not "it is not there". TreeStructure.inspect
            // returns null for an unloaded chunk exactly as it does for a tree
            // somebody felled, and forgetting on the first is permanent: over a
            // fortnight of play the knowledge base quietly loses every tree the
            // lumberjack happened to consider while its chunk was out.
            if (!ctx.level().hasChunkAt(pos)) {
                failedTrees.add(pos);
                continue;
            }
            // Validate and cache the tree once before attempting to claim it.
            TreeStructure.Inspection inspection = ctx.mgr().judgeTree(pos);
            TreeStructure structure = inspection != null ? inspection.tree() : null;
            if (structure == null) {
                ctx.mgr().forgetTree(pos);
                failedTrees.add(pos);
                // A branch standing on its own leaves was taken for a base
                // (fancy oak, cherry): the judge hands over its trunk's foot.
                if (inspection != null && inspection.suggestedFoot() != null) {
                    ctx.mgr().getKnowledge().add(KnowledgeBase.Category.TREE, inspection.suggestedFoot());
                }
                continue;
            }
            if (!settings.isTreeEnabled(structure.treeTypeId())) {
                skippedThisSearch.add(pos);
                continue;
            }
            // Another base of a group another lumberjack is felling: the claim
            // would refuse it, and a refused claim ends this search.
            if (WalkFeel.treeOneClaim && ctx.mgr().overlapsHeldTree(structure, ctx.model().npcId)) {
                skippedThisSearch.add(pos);
                continue;
            }
            if (!LumberjackBigTrees.mayFell(structure, bigTreeAxe)) {
                // A big 2x2 tree and an axe too weak for it. Remembered when it
                // could walk up to it, for its thought bubble and villager screen.
                skippedThisSearch.add(pos);
                if (LumberjackProfession.hasReachableLog(ctx, structure)) {
                    long now = ctx.level().getGameTime();
                    if (!LumberjackBigTrees.waitingForAxe(ctx.npc(), now)) {
                        ctx.brain().diary().log(VillagerDiary.ts(ctx.level())
                                + " work  passing over big tree at " + pos.toShortString()
                                + ": axe too weak, needs iron or better");
                    }
                    LumberjackBigTrees.noteSkipped(ctx.npc(), now);
                }
                continue;
            }

            failedEntries.clear();
            TreeApproach approach = findApproach(ctx, structure, failedEntries);
            if (graph != null && approach == null) {
                LOGGER.debug("[LumberjackActivity] Lumberjack {} has no way in to tree {}; skipping it.",
                        ctx.model().npcId, pos);
                failedTrees.add(pos);
                continue;
            }

            bestTree = pos;
            bestNode = approach != null ? approach.node() : null;
            claimedStructure = structure;
            chopFocus = approach != null ? approach.log() : pos;
            break;
        }

        if (bestTree == null || claimedStructure == null
                || !ctx.mgr().tryClaimTreeJob(claimedStructure, ctx.model().npcId)) {
            claimedStructure = null;
            chopFocus = null;
            hungerCheckedForCurrentTree = false;
            return false;
        }
        claimedTree = bestTree;
        leafRewards = new LeafRewards();
        hungerCheckedForCurrentTree = false;
        LOGGER.debug(
                "[LumberjackActivity] Lumberjack {} selected tree {}.",
                ctx.model().npcId, claimedTree);
        startMoveToClaimedTree(ctx, bestNode, false);
        return true;
    }

    /**
     * Where to walk to reach this tree, and which log to cut first; null when
     * there is no way in from here.
     *
     * <p>This used to be the graph node nearest any log, with no check that the
     * lumberjack could walk to it. Beside a house that was often a node on the
     * far side of a wall, and the villager walked into the wall until it was
     * called stuck. The walk now goes to the entry {@link TreeApproachPlanner}
     * found, which is reachable by construction. The log is the lowest one
     * standing, nearest the chopping spot -- the stump on an ordinary tree.
     */
    private TreeApproach findApproach(
            Context ctx, TreeStructure structure, Set<BlockPos> excludedEntries) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return new TreeApproach(structure.base(), null);

        TreeApproachPlanner.Plan plan = TreeApproachPlanner.plan(ctx, structure, excludedEntries);
        if (plan == null) return null;
        BlockPos standSpot = plan.standSpot();
        return structure.logs().stream()
                .filter(log -> ctx.level().getBlockState(log)
                        .is(net.minecraft.tags.BlockTags.LOGS))
                .min(Comparator
                        .comparingInt((BlockPos log) -> log.getY())
                        .thenComparingDouble(log -> log.distSqr(standSpot)))
                .map(log -> new TreeApproach(log, plan.entry()))
                .orElse(null);
    }

    private void startMoveToClaimedTree(Context ctx, NavNode approach, boolean resumed) {
        BlockPos dest = approach != null ? approach.feetPos() : claimedTree;
        approachEntry = approach != null ? approach.feetPos() : null;
        ctx.brain().diary().log(String.format(
                "%s work  %s tree at %d,%d,%d via log %d,%d,%d (%d chopped this session)",
                VillagerDiary.ts(ctx.level()), resumed ? "resuming" : "heading to",
                claimedTree.getX(), claimedTree.getY(), claimedTree.getZ(),
                chopFocus.getX(), chopFocus.getY(), chopFocus.getZ(),
                treesChopped));
        phase = Phase.MOVE;
        moveActivity = new MoveActivity(dest, VillagerModel.WALK_SPEED);
    }
}
