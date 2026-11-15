package com.alex.test.village.villagers.lumberjack.registry;

import com.alex.test.village.WalkFeel;
import com.alex.test.village.knowledge.KnowledgeBase;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Per-village runtime state for Lumberjack tree knowledge and assignments. */
public final class LumberjackTreeRegistry {

    private static final long TREE_RETRY_DELAY_TICKS = 600L;

    private final KnowledgeBase knowledgeBase;
    private final Supplier<ServerLevel> levelSupplier;
    private final Predicate<BlockPos> protectedPosition;

    private final Map<Long, UUID> claimedTrees = new HashMap<>();
    private final Map<UUID, TreeStructure> claimedTreesByVillager = new HashMap<>();
    private final Map<Long, Long> treeRetryAfter = new HashMap<>();

    private static final long INSPECTION_CACHE_TICKS = 200L;
    private static final int MAX_CACHED_INSPECTIONS = 1024;
    private record CachedInspection(@Nullable TreeStructure tree, long expiresAt) {}
    private final Map<Long, CachedInspection> inspectionCache = new HashMap<>();

    /**
     * How many times a lumberjack has failed to walk up to each tree.
     *
     * <p>This used to live on the activity, which is thrown away and rebuilt
     * every time anything preempts work — so the count reset before it could
     * ever reach the point of giving up. A lumberjack needed about 1200 ticks
     * to try every log on a tree it could not reach, and something interrupted
     * it every 600, so it went back to the same tree for eighteen days with
     * seven hundred others standing.
     */
    private final Map<Long, Integer> approachFailures = new HashMap<>();

    /**
     * How far each lumberjack has got into the tree it is felling.
     *
     * <p>Chop progress used to live on the activity, and the activity is
     * rebuilt whenever anything preempts work — so every interruption threw the
     * axe-work away and started the tree again. The claim on the tree already
     * survives an interruption; the effort spent on it should too.
     *
     * <p>Kept with the tree it was for: a tree let go of some other way (a
     * player cut it, it was forgotten) leaves its count here, and the next
     * tree must not start with it.
     */
    private final Map<UUID, ChopCount> chopProgress = new HashMap<>();
    private record ChopCount(long base, int ticks) {}

    /**
     * Failed approaches before a tree is set aside. Four covers a normal oak's
     * trunk with one to spare, so a genuine one-off (a villager in the way, a
     * door that happened to be shut) never costs a tree.
     */
    private static final int MAX_APPROACH_FAILURES = 4;

    public LumberjackTreeRegistry(
            KnowledgeBase knowledgeBase,
            Supplier<ServerLevel> levelSupplier,
            Predicate<BlockPos> protectedPosition) {
        this.knowledgeBase = knowledgeBase;
        this.levelSupplier = levelSupplier;
        this.protectedPosition = protectedPosition;
    }

    public boolean registerKnownTree(TreeStructure tree) {
        return knowledgeBase.add(KnowledgeBase.Category.TREE, tree.base());
    }

    @Nullable
    public TreeStructure inspectTree(BlockPos base) {
        ServerLevel level = levelSupplier.get();
        if (level == null) return null;
        return TreeStructure.inspect(level, base, protectedPosition);
    }

    /**
     * {@link #inspectTree} with the judge's reason: whether a refusal is only
     * an unloaded chunk, and the trunk foot of a branch that was taken for a
     * base. Null when there is no level.
     */
    @Nullable
    public TreeStructure.Inspection judgeTree(BlockPos base) {
        ServerLevel level = levelSupplier.get();
        if (level == null) return null;
        return TreeStructure.judge(level, base, protectedPosition, TreeStructure.currentRules());
    }

    /**
     * {@link #inspectTree} remembered for {@link #INSPECTION_CACHE_TICKS}, for
     * the cheap "is there any tree to cut?" probe only. An idle lumberjack
     * used to flood-fill every known tree every time it was asked. Callers
     * still run {@link #isTreeStructureStillSafe}, which re-reads the logs, and
     * claiming a tree inspects it fresh.
     */
    @Nullable
    public TreeStructure inspectTreeCached(BlockPos base) {
        ServerLevel level = levelSupplier.get();
        if (level == null) return null;
        long now = level.getGameTime();
        long key = base.asLong();
        CachedInspection cached = inspectionCache.get(key);
        if (cached != null && cached.expiresAt() > now) return cached.tree();
        if (inspectionCache.size() > MAX_CACHED_INSPECTIONS) {
            inspectionCache.values().removeIf(entry -> entry.expiresAt() <= now);
        }
        TreeStructure tree = TreeStructure.inspect(level, base, protectedPosition);
        inspectionCache.put(key, new CachedInspection(tree, now + INSPECTION_CACHE_TICKS));
        return tree;
    }

    public boolean isTreeStructureStillSafe(TreeStructure tree) {
        ServerLevel level = levelSupplier.get();
        if (level == null) return false;
        int existingLogs = 0;
        for (BlockPos log : tree.logs()) {
            if (protectedPosition.test(log)) return false;
            if (level.getBlockState(log).is(BlockTags.LOGS)) existingLogs++;
        }
        return existingLogs >= 2;
    }

    public void forgetTree(BlockPos base) {
        knowledgeBase.remove(KnowledgeBase.Category.TREE, base);
        inspectionCache.remove(base.asLong());
        abandonTree(base);
    }

    public boolean isTreeClaimed(BlockPos pos) {
        return claimedTrees.containsKey(pos.asLong());
    }

    @Nullable
    public TreeStructure getClaimedTreeJob(UUID villagerId) {
        TreeStructure tree = claimedTreesByVillager.get(villagerId);
        if (tree == null) return null;
        return villagerId.equals(claimedTrees.get(tree.base().asLong())) ? tree : null;
    }

    public boolean tryClaimTreeJob(TreeStructure tree, UUID villagerId) {
        TreeStructure existingTree = getClaimedTreeJob(villagerId);
        if (existingTree != null && !existingTree.base().equals(tree.base())) return false;

        long key = tree.base().asLong();
        UUID existingOwner = claimedTrees.get(key);
        if (existingOwner != null && !existingOwner.equals(villagerId)) return false;
        if (existingOwner == null && WalkFeel.treeOneClaim && overlapsHeldTree(tree, villagerId)) return false;

        claimedTrees.put(key, villagerId);
        claimedTreesByVillager.put(villagerId, tree);
        return true;
    }

    /**
     * True when some log of {@code tree} belongs to a tree another lumberjack
     * holds. With {@code treesplit} off touching trees are one job, yet the
     * list can hold two bases of one group (a grow listed during a sliced
     * scan, say); each base is reserved on its own, so two lumberjacks walked
     * to the same logs. With the split on, trees never share logs.
     */
    public boolean overlapsHeldTree(TreeStructure tree, UUID villagerId) {
        for (Map.Entry<UUID, TreeStructure> held : claimedTreesByVillager.entrySet()) {
            UUID owner = held.getKey();
            TreeStructure other = held.getValue();
            if (owner.equals(villagerId) || !owner.equals(claimedTrees.get(other.base().asLong()))) continue;
            Set<BlockPos> otherLogs = other.logs();
            for (BlockPos log : tree.logs()) {
                if (otherLogs.contains(log)) return true;
            }
        }
        return false;
    }

    public boolean ownsTreeJob(BlockPos base, UUID villagerId) {
        return villagerId.equals(claimedTrees.get(base.asLong()));
    }

    public boolean isTreeTemporarilyBlacklisted(BlockPos base, long gameTime) {
        Long retryAfter = treeRetryAfter.get(base.asLong());
        if (retryAfter == null) return false;
        if (gameTime >= retryAfter) {
            treeRetryAfter.remove(base.asLong());
            return false;
        }
        return true;
    }

    public void blacklistTreeTemporarily(BlockPos base, long gameTime) {
        treeRetryAfter.put(base.asLong(), gameTime + TREE_RETRY_DELAY_TICKS);
    }

    /**
     * Records that somebody could not get to this tree, and says whether it has
     * now failed often enough to be set aside for a while.
     */
    public boolean recordApproachFailure(BlockPos base, long gameTime) {
        long key = base.asLong();
        int failures = approachFailures.merge(key, 1, Integer::sum);
        if (failures < MAX_APPROACH_FAILURES) return false;
        approachFailures.remove(key);
        blacklistTreeTemporarily(base, gameTime);
        return true;
    }

    /** Forgets the failure count for a tree somebody has just got to. */
    public void clearApproachFailures(BlockPos base) {
        approachFailures.remove(base.asLong());
    }

    /** Ticks already chopped into the tree at {@code base}; 0 for any other tree. */
    public int getChopProgress(UUID villagerId, BlockPos base) {
        ChopCount count = chopProgress.get(villagerId);
        return count != null && count.base() == base.asLong() ? count.ticks() : 0;
    }

    public void setChopProgress(UUID villagerId, BlockPos base, int ticks) {
        chopProgress.put(villagerId, new ChopCount(base.asLong(), ticks));
    }

    public void clearChopProgress(UUID villagerId) {
        chopProgress.remove(villagerId);
    }

    public void clearTreeTemporaryBlacklist(BlockPos base) {
        treeRetryAfter.remove(base.asLong());
    }

    public void releaseTreeJob(BlockPos base, UUID villagerId) {
        chopProgress.remove(villagerId);
        long key = base.asLong();
        if (!villagerId.equals(claimedTrees.get(key))) return;
        claimedTrees.remove(key);
        TreeStructure tree = claimedTreesByVillager.get(villagerId);
        if (tree != null && tree.base().equals(base)) claimedTreesByVillager.remove(villagerId);
    }

    public void releaseTreeJobs(UUID villagerId) {
        chopProgress.remove(villagerId);
        TreeStructure tree = claimedTreesByVillager.remove(villagerId);
        if (tree != null && villagerId.equals(claimedTrees.get(tree.base().asLong()))) {
            claimedTrees.remove(tree.base().asLong());
        }
        claimedTrees.values().removeIf(villagerId::equals);
    }

    public void abandonTree(BlockPos pos) {
        treeRetryAfter.remove(pos.asLong());
        UUID owner = claimedTrees.remove(pos.asLong());
        if (owner == null) return;
        TreeStructure tree = claimedTreesByVillager.get(owner);
        if (tree != null && tree.base().equals(pos)) claimedTreesByVillager.remove(owner);
    }

    public void clear() {
        approachFailures.clear();
        chopProgress.clear();
        claimedTrees.clear();
        claimedTreesByVillager.clear();
        treeRetryAfter.clear();
        inspectionCache.clear();
    }
}
