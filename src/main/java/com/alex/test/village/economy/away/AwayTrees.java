package com.alex.test.village.economy.away;

import com.alex.test.village.VillageManager;
import com.alex.test.village.knowledge.KnowledgeBase;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The village's trees for one catch-up (round 7), shared by the lumberjacks:
 * the tree list ({@code KnowledgeBase} TREE) as it stood at READ, bases in
 * loaded chunks only. A woken village has its list from the wake scan (the
 * catch-up starts only after it); one that slept during play kept its own.
 *
 * <p>Each tree is judged the first time a lumberjack looks at it
 * ({@code VillageManager.judgeTree}, the live judge; it never loads a chunk
 * and changes nothing), so a village with no lumberjack judges none. A tree
 * whose job a villager held at READ (a paused chop) goes to that villager
 * only, as live ({@code tryClaimTreeJob}). A felled tree is gone for the next
 * lumberjack, and so is any listed tree that shared one of its logs.
 *
 * <p>WRITE: one change per felled tree, only if its base log is still the one
 * seen: the logs and leaves still as seen go, the saplings are planted where
 * they fit (as live {@code plantSpot}), and the tree leaves the list.
 */
public final class AwayTrees {

    /** A held tree job whose holder is not one of this run's villagers: nobody fells it. */
    private static final UUID SOMEONE_ELSE = new UUID(0L, 0L);

    /** One listed tree base. */
    public static final class Tree {
        private final BlockPos base;
        @Nullable private final UUID holder;
        private boolean judged;
        @Nullable private TreeStructure structure;
        /** Felled, or taken down with a tree that shared its logs. */
        private boolean gone;

        private Tree(BlockPos base, @Nullable UUID holder) {
            this.base = base;
            this.holder = holder;
        }

        public BlockPos base() { return base; }

        /** The villager holding its tree job at READ, or null. */
        @Nullable public UUID holder() { return holder; }
    }

    private final ServerLevel level;
    private final VillageManager mgr;
    private final List<Tree> trees = new ArrayList<>();
    /** Every log of the felled trees. */
    private final Set<Long> takenLogs = new HashSet<>();
    private final List<Fell> fells = new ArrayList<>();
    private int listed;
    private int notLoaded;
    private int held;
    private int judged;
    private int notSeen;
    private int notTree;
    private int takenWith;
    private int logBlocks;
    private int leafBlocks;
    private int spots;
    // WRITE
    private int written;
    private int cleared;
    private int planted;
    private int notPlanted;

    private AwayTrees(ServerLevel level, VillageManager mgr) {
        this.level = level;
        this.mgr = mgr;
    }

    static AwayTrees read(ServerLevel level, VillageManager mgr, List<AwayVillager> villagers) {
        AwayTrees result = new AwayTrees(level, mgr);
        Map<Long, UUID> holders = new HashMap<>();
        for (AwayVillager villager : villagers) {
            TreeStructure job = mgr.getClaimedTreeJob(villager.id);
            if (job != null) holders.put(job.base().asLong(), villager.id);
        }
        for (BlockPos base : new ArrayList<>(mgr.getKnowledge().get(KnowledgeBase.Category.TREE))) {
            result.listed++;
            if (!level.isLoaded(base)) {
                result.notLoaded++;
                continue;
            }
            UUID holder = holders.get(base.asLong());
            if (holder == null && mgr.isResourceClaimed(base)) holder = SOMEONE_ELSE;
            if (holder != null) result.held++;
            result.trees.add(new Tree(base.immutable(), holder));
        }
        return result;
    }

    /** No listed tree in a loaded chunk. */
    public boolean isEmpty() { return trees.isEmpty(); }

    /** Listed trees passed by because their base's chunk was not loaded. */
    public int notLoaded() { return notLoaded; }

    /** The trees not felled yet, in list order. */
    public List<Tree> standing() {
        List<Tree> standing = new ArrayList<>();
        for (Tree tree : trees) {
            if (!tree.gone) standing.add(tree);
        }
        return standing;
    }

    /** Whether {@code villager} may fell it: nobody else held its job at READ. */
    public boolean mayTake(Tree tree, UUID villager) {
        return tree.holder == null || tree.holder.equals(villager);
    }

    /**
     * The tree as the live judge sees it now (judged once a run), or null when it
     * is gone, not a tree, or not wholly in loaded chunks. The real world does
     * not change before WRITE, so one judgement holds for the whole run.
     */
    @Nullable
    public TreeStructure judge(Tree tree) {
        if (tree.gone) return null;
        if (!tree.judged) {
            tree.judged = true;
            judged++;
            TreeStructure.Inspection inspection = mgr.judgeTree(tree.base);
            if (inspection == null || inspection.cantSee()) {
                notSeen++;
            } else if (!inspection.isTree()) {
                // Left on the list: the live search forgets it the next time it looks.
                notTree++;
            } else {
                tree.structure = inspection.tree();
            }
        }
        if (tree.structure == null) return null;
        for (BlockPos log : tree.structure.logs()) {
            if (takenLogs.contains(log.asLong())) {
                tree.gone = true;
                takenWith++;
                return null;
            }
        }
        return tree.structure;
    }

    /**
     * A lumberjack felled {@code tree}: its blocks as they stood (loaded and still
     * the tree's), and the sapling spots to plant at WRITE (empty: none).
     */
    public void fell(Tree tree, Map<BlockPos, BlockState> logs, Map<BlockPos, BlockState> leaves,
                     @Nullable Block sapling, List<List<BlockPos>> saplingSpots) {
        tree.gone = true;
        TreeStructure structure = tree.structure;
        if (structure != null) {
            for (BlockPos log : structure.logs()) takenLogs.add(log.asLong());
        }
        logBlocks += logs.size();
        leafBlocks += leaves.size();
        if (sapling != null) spots += saplingSpots.size();
        BlockState baseState = logs.containsKey(tree.base) ? logs.get(tree.base) : level.getBlockState(tree.base);
        fells.add(new Fell(tree.base, baseState, logs, leaves,
                sapling != null ? sapling.defaultBlockState() : null, saplingSpots));
    }

    /** WRITE: one change per felled tree, after the fields' and pens' changes. */
    void writeTo(AwayWorld world) {
        for (Fell fell : fells) world.add(fell);
    }

    /** For the {@code [AwayRun]} lines and the preview. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(listed).append(" listed");
        if (notLoaded > 0 || held > 0) {
            text.append(" (").append(notLoaded).append(" not loaded, ").append(held).append(" held)");
        }
        text.append(", judged ").append(judged);
        if (notSeen > 0 || notTree > 0) {
            text.append(" (").append(notSeen).append(" not wholly loaded, ").append(notTree).append(" not a tree)");
        }
        text.append(", felled ").append(fells.size()).append(" (").append(logBlocks).append(" log block(s), ")
                .append(leafBlocks).append(" leaves, ").append(spots).append(" sapling spot(s))");
        if (takenWith > 0) text.append(", ").append(takenWith).append(" taken down with a felled one");
        if (written > 0) {
            text.append("; written ").append(written).append(": ").append(cleared).append(" block(s) cleared, ")
                    .append(planted).append(" sapling(s) planted");
            if (notPlanted > 0) text.append(", ").append(notPlanted).append(" spot(s) no longer fit");
        }
        return text.toString();
    }

    /** One felled tree at WRITE, if its base log is still the one seen. */
    private final class Fell implements AwayWorld.Change {
        private final BlockPos base;
        private final BlockState baseState;
        private final Map<BlockPos, BlockState> logs;
        private final Map<BlockPos, BlockState> leaves;
        @Nullable private final BlockState sapling;
        private final List<List<BlockPos>> saplingSpots;

        Fell(BlockPos base, BlockState baseState, Map<BlockPos, BlockState> logs, Map<BlockPos, BlockState> leaves,
             @Nullable BlockState sapling, List<List<BlockPos>> saplingSpots) {
            this.base = base;
            this.baseState = baseState;
            this.logs = logs;
            this.leaves = leaves;
            this.sapling = sapling;
            this.saplingSpots = saplingSpots;
        }

        @Override
        public boolean stillHolds(ServerLevel level) {
            return level.isLoaded(base) && level.getBlockState(base) == baseState;
        }

        @Override
        public void apply(ServerLevel level) {
            written++;
            logs.forEach((pos, state) -> clear(level, pos, state));
            leaves.forEach((pos, state) -> clear(level, pos, state));
            if (sapling != null) {
                for (List<BlockPos> spot : saplingSpots) plant(level, spot);
            }
            mgr.forgetTree(base);
        }

        private void clear(ServerLevel level, BlockPos pos, BlockState was) {
            if (!level.isLoaded(pos) || level.getBlockState(pos) != was) return;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            mgr.markNavigationDirtyAt(pos);
            cleared++;
        }

        /** One trunk's sapling(s), all or none, as live {@code plantSpot}. */
        private void plant(ServerLevel level, List<BlockPos> spot) {
            if (spot.isEmpty()) return;
            for (BlockPos pos : spot) {
                if (!level.isLoaded(pos) || !level.getBlockState(pos).canBeReplaced()
                        || !sapling.canSurvive(level, pos)) {
                    notPlanted++;
                    return;
                }
            }
            for (BlockPos pos : spot) {
                level.setBlock(pos, sapling, Block.UPDATE_ALL);
                mgr.markNavigationDirtyAt(pos);
                planted++;
            }
        }

        @Override
        public String describe() {
            return "felled tree at " + base.toShortString() + " (" + logs.size() + " log block(s)); its base is"
                    + " no longer the log seen";
        }
    }
}
