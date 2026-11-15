package com.alex.test.village.villagers.lumberjack;

import com.alex.test.village.knowledge.KnowledgeBase;
import com.alex.test.village.NavGraph;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.lumberjack.behavior.GetAxeActivity;
import com.alex.test.village.villagers.lumberjack.behavior.LumberjackActivity;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.Set;

/** Wires the Lumberjack profession to its behavior implementation. */
public final class LumberjackProfession {

    public static final Profession PROFESSION = Profession.LUMBERJACK;

    private LumberjackProfession() {}

    public static WorkActivity createWorkActivity() {
        return new LumberjackActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        boolean work = findImmediateWork(ctx);
        // A tree reserved across a stop (the walk there, a paused chop) is let
        // go only by the work loop; with no work it never runs, so the tree
        // would stay out of every other lumberjack's reach. Not while working:
        // the reach test here can say no where the walk planner found a way in.
        if (!work && WalkFeel.treeResume && ctx.brain() != null
                && !(ctx.brain().currentActivity() instanceof LumberjackActivity)) {
            TreeStructure held = ctx.mgr().getClaimedTreeJob(ctx.model().npcId);
            if (held != null) {
                ctx.mgr().releaseTreeJob(held.base(), ctx.model().npcId);
                if (ctx.mgr().isTreeStructureStillSafe(held)) {
                    ctx.mgr().getKnowledge().add(KnowledgeBase.Category.TREE, held.base());
                }
            }
        }
        return work;
    }

    private static boolean findImmediateWork(Activity.Context ctx) {
        LumberjackSettings settings = LumberjackSettings.of(ctx.npc());
        // Every "no" below that is not about trees forgets any big tree passed
        // over, so the thought bubble shows the real reason.
        if (!settings.works()) {
            LumberjackBigTrees.clearSkipped(ctx.npc());
            return false;
        }

        // Tool acquisition is work in its own right.  Without this early
        // check, a lumberjack who has lost their axe can only enter
        // LumberjackActivity after a reachable tree has already been found.
        // That leaves them in leisure when the tree cache/path graph is still
        // catching up, so GetAxeActivity never gets a chance to craft the
        // fallback wooden axe.
        Set<Item> allowedAxes = settings.getAllowedAxes();
        // Same lookup GetAxeActivity runs, so logs in the pack count as
        // crafting wood here too -- otherwise a lumberjack carrying a full
        // load is told "no axe" by this gate and never gets to craft one.
        if (AxeUtils.findUsableAxeSlot(ctx.npc().getInventory(), allowedAxes) < 0) {
            LumberjackBigTrees.clearSkipped(ctx.npc());
            return GetToolActivity.canAcquire(ctx, GetAxeActivity.AXE_REQUIREMENT);
        }

        // Full pack: the next job is banking it, and with no storage able to
        // take it there is nothing useful to do until that changes.
        if (LumberjackActivity.isPackFullWithNowhereToBank(ctx)) {
            LumberjackBigTrees.clearSkipped(ctx.npc());
            return false;
        }

        boolean bigTreeAxe = AxeUtils.hasBigTreeAxe(ctx.npc());
        TreeStructure claimed = ctx.mgr().getClaimedTreeJob(ctx.model().npcId);
        if (claimed != null && !LumberjackBigTrees.mayFell(claimed, bigTreeAxe)) {
            // A big tree it can no longer fell (its stronger axes switched off,
            // say). Let it go: a claim lasts until released, and with no work
            // it never enters the work loop that would release it, so the tree
            // would stay out of reach of every other lumberjack.
            ctx.mgr().releaseTreeJob(claimed.base(), ctx.model().npcId);
            claimed = null;
        }
        if (claimed != null
                && settings.isTreeEnabled(claimed.treeTypeId())
                && ctx.mgr().isTreeStructureStillSafe(claimed)
                && hasReachableLog(ctx, claimed)) {
            return true;
        }

        long gameTime = ctx.level().getGameTime();
        boolean skippedBigTree = false;
        for (BlockPos base : ctx.mgr().getKnowledge().get(KnowledgeBase.Category.TREE)) {
            if (ctx.mgr().isResourceClaimed(base)
                    || ctx.mgr().isTreeTemporarilyBlacklisted(base, gameTime)) {
                continue;
            }
            TreeStructure tree = ctx.mgr().inspectTreeCached(base);
            if (tree == null || !ctx.mgr().isTreeStructureStillSafe(tree)) continue;
            if (!settings.isTreeEnabled(tree.treeTypeId())) continue;
            // Same skip as the search: part of a tree another lumberjack holds.
            if (WalkFeel.treeOneClaim && ctx.mgr().overlapsHeldTree(tree, ctx.model().npcId)) continue;
            if (!LumberjackBigTrees.mayFell(tree, bigTreeAxe)) {
                // Only counted if it could walk up to it: one behind a wall
                // would be left standing whatever the axe.
                if (!skippedBigTree && hasReachableLog(ctx, tree)) skippedBigTree = true;
                continue;
            }
            if (hasReachableLog(ctx, tree)) {
                if (skippedBigTree) LumberjackBigTrees.noteSkipped(ctx.npc(), gameTime);
                return true;
            }
        }
        if (!skippedBigTree) {
            LumberjackBigTrees.clearSkipped(ctx.npc());
            return false;
        }
        LumberjackBigTrees.noteSkipped(ctx.npc(), gameTime);
        // Only big trees left and its axe is too weak for them. Fetching an
        // iron axe or better from storage is work; otherwise it waits, and
        // its thought bubble says why (ThoughtState.NEEDS_BIG_AXE).
        return LumberjackBigTrees.storageHasBigTreeAxe(ctx);
    }

    /** Some log of {@code tree} is within reach of a walkable spot on the village map. */
    public static boolean hasReachableLog(Activity.Context ctx, TreeStructure tree) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return false;
        for (BlockPos log : tree.logs()) {
            var node = graph.nearest(log, 4, 3);
            if (node != null && node.feetPos().distSqr(log) <= 25.0) return true;
        }
        return false;
    }
}
