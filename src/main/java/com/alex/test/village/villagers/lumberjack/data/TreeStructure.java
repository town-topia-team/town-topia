package com.alex.test.village.villagers.lumberjack.data;

import com.alex.test.village.WalkFeel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** One bounded, immutable snapshot of a detected natural tree. */
public record TreeStructure(
        BlockPos base,
        Block sourceLog,
        ResourceLocation treeTypeId,
        Set<BlockPos> logs,
        Set<BlockPos> leaves,
        BlockPos replantAnchor,
        boolean twoByTwo) {

    /** A full 31-tall mega jungle is 124 trunk logs before its branches. */
    public static final int MAX_LOGS = TreeJudge.MAX_LOGS;
    public static final int MAX_LEAVES = TreeJudge.MAX_LEAVES;

    /**
     * What the tree judge said about a base, for the callers that need more
     * than "tree or not": whether a refusal is only "cannot see it", the foot
     * a branch belongs to, and the reason for {@code /villager treecheck}.
     *
     * @param where         the block the reason is about, or null
     * @param tree          the tree, only when the verdict is TREE
     * @param suggestedFoot for a base standing on nothing, the foot of its trunk, or null
     * @param crownSides    sides of the best top with its own leaves, -1 when not judged
     * @param logCount      logs gathered (for a refusal, before it stopped)
     * @param groupLogs     logs in the whole joined group; more than the tree's own when
     *                      {@code treesplit} shared it out with other trunks and builds
     */
    public record Inspection(
            TreeJudge.Verdict verdict,
            TreeJudge.Reason reason,
            @Nullable BlockPos where,
            @Nullable TreeStructure tree,
            @Nullable BlockPos suggestedFoot,
            int crownSides,
            @Nullable BlockPos crownTop,
            int logCount,
            int groupLogs) {

        public boolean isTree() {
            return tree != null;
        }

        /** Only "the chunk is not loaded": the tree may well be there. */
        public boolean cantSee() {
            return verdict == TreeJudge.Verdict.CANT_SEE;
        }
    }

    @Nullable
    public static TreeStructure inspect(
            ServerLevel level, BlockPos base, Predicate<BlockPos> protectedPosition) {
        return judge(level, base, protectedPosition, currentRules()).tree();
    }

    /** The tree rules as the {@code /villager walkfeel tree*} switches stand. */
    public static TreeJudge.Rules currentRules() {
        return new TreeJudge.Rules(
                WalkFeel.treePlain, WalkFeel.treePiles, WalkFeel.treeMarks, WalkFeel.treeCrown,
                WalkFeel.treeSplit, WalkFeel.tree2x2, WalkFeel.treeSoil, WalkFeel.treeLeaves, WalkFeel.leafCap);
    }

    /** Asks {@link TreeJudge}, then picks the tree kind by its leaves as before. */
    public static Inspection judge(
            ServerLevel level, BlockPos base, Predicate<BlockPos> protectedPosition, TreeJudge.Rules rules) {
        LevelTreeView view = new LevelTreeView(level, protectedPosition);
        TreeJudge.Judgement judgement = TreeJudge.judge(view, base.asLong(), rules);
        BlockPos crownTop = posOrNull(judgement.crownTop());
        if (!judgement.isTree()) {
            return new Inspection(judgement.verdict(), judgement.reason(), posOrNull(judgement.where()),
                    null, posOrNull(judgement.suggestedFoot()), judgement.crownSides(), crownTop,
                    judgement.logs().size(), judgement.groupLogs());
        }

        BlockState baseState = level.getBlockState(base);
        // treeleaves: a tree whose every leaf near it is nearer another log still
        // gets its kind from those leaves, and none of them falls with it.
        boolean noneOwn = judgement.leaves().isEmpty();
        Map<TreeTypeRegistry.TreeType, Set<BlockPos>> leavesByType = new LinkedHashMap<>();
        TreeTypeRegistry.candidatesForLog(baseState.getBlock())
                .forEach(type -> leavesByType.put(type, new LinkedHashSet<>()));
        for (long packed : noneOwn ? judgement.nearLeaves() : judgement.leaves()) {
            BlockPos leafPos = BlockPos.of(packed);
            Block leaf = level.getBlockState(leafPos).getBlock();
            for (Map.Entry<TreeTypeRegistry.TreeType, Set<BlockPos>> entry : leavesByType.entrySet()) {
                if (entry.getKey().leaves().contains(leaf)) entry.getValue().add(leafPos);
            }
        }

        // With leafcap the judge never hands over more than MAX_LEAVES.
        Map.Entry<TreeTypeRegistry.TreeType, Set<BlockPos>> detected =
                leavesByType.entrySet().stream()
                        .filter(entry -> !entry.getValue().isEmpty())
                        .filter(entry -> noneOwn || entry.getValue().size() <= MAX_LEAVES)
                        .max(Comparator.comparingInt(entry -> entry.getValue().size()))
                        .orElse(null);
        if (detected == null) {
            return new Inspection(TreeJudge.Verdict.NOT_TREE, TreeJudge.Reason.TOO_MANY_LEAVES, base,
                    null, null, judgement.crownSides(), crownTop, judgement.logs().size(), judgement.groupLogs());
        }

        Set<BlockPos> logs = new LinkedHashSet<>();
        for (long packed : judgement.logs()) logs.add(BlockPos.of(packed));
        // The judge finds the square (tree2x2: at the base's own layer); only
        // spruce, jungle and dark oak can grow one.
        BlockPos twoByTwoAnchor = canGrowTwoByTwo(detected.getKey().id())
                ? posOrNull(judgement.twoByTwoAnchor()) : null;
        TreeStructure tree = new TreeStructure(
                base.immutable(),
                baseState.getBlock(),
                detected.getKey().id(),
                Set.copyOf(logs),
                noneOwn ? Set.of() : Set.copyOf(detected.getValue()),
                (twoByTwoAnchor != null ? twoByTwoAnchor : base).immutable(),
                twoByTwoAnchor != null);
        return new Inspection(TreeJudge.Verdict.TREE, TreeJudge.Reason.OK, null, tree, null,
                judgement.crownSides(), crownTop, logs.size(), judgement.groupLogs());
    }

    @Nullable
    private static BlockPos posOrNull(long packed) {
        return packed == TreeJudge.NO_POS ? null : BlockPos.of(packed);
    }

    public boolean matchesLeaf(BlockState state) {
        TreeTypeRegistry.TreeType type = TreeTypeRegistry.byId(treeTypeId);
        return type != null
                && type.leaves().contains(state.getBlock())
                && isNaturalLeaf(state);
    }

    /** Trunks grow straight up; a log on its side is a branch or somebody's build. */
    public static boolean isUpright(BlockState state) {
        return !state.hasProperty(BlockStateProperties.AXIS)
                || state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y;
    }

    /** Leaves a player or a structure placed are persistent; only grown canopy counts. */
    public static boolean isNaturalLeaf(BlockState state) {
        return !state.hasProperty(BlockStateProperties.PERSISTENT)
                || !state.getValue(BlockStateProperties.PERSISTENT);
    }

    /**
     * The tree's own wood. With {@code treeplain} on, never a stripped log or a
     * bark block: no tree grows those, so one in the way is somebody's build.
     */
    public boolean matchesLog(BlockState state) {
        TreeTypeRegistry.TreeType type = TreeTypeRegistry.byId(treeTypeId);
        return type != null
                && state.is(BlockTags.LOGS)
                && type.logs().contains(state.getBlock())
                && !(WalkFeel.treePlain && LevelTreeView.isBarkOrStripped(state.getBlock()));
    }

    public Set<BlockPos> saplingPositions() {
        if (!twoByTwo) return Set.of(replantAnchor);
        return Set.of(
                replantAnchor,
                replantAnchor.east(),
                replantAnchor.south(),
                replantAnchor.east().south());
    }

    @Nullable
    public Block sapling(ServerLevel level) {
        if (treeTypeId.equals(ResourceLocation.withDefaultNamespace("azalea"))) {
            for (BlockPos leaf : leaves) {
                if (level.getBlockState(leaf).is(Blocks.FLOWERING_AZALEA_LEAVES)) {
                    return Blocks.FLOWERING_AZALEA;
                }
            }
        }
        TreeTypeRegistry.TreeType type = TreeTypeRegistry.byId(treeTypeId);
        return type != null ? type.sapling() : null;
    }

    /** Tree kinds that can grow a big 2x2 trunk: big spruce, big jungle and every dark oak. */
    public static boolean canGrowTwoByTwo(ResourceLocation treeType) {
        return treeType.equals(ResourceLocation.withDefaultNamespace("spruce"))
                || treeType.equals(ResourceLocation.withDefaultNamespace("jungle"))
                || alwaysTwoByTwo(treeType);
    }

    /** Dark oak only ever grows as a 2x2 trunk. */
    public static boolean alwaysTwoByTwo(ResourceLocation treeType) {
        return treeType.equals(ResourceLocation.withDefaultNamespace("dark_oak"));
    }
}
