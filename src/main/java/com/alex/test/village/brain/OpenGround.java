package com.alex.test.village.brain;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where a strolling villager may choose to go: anywhere but a ranch. The pen
 * tiles and the ranch gate are left out, so a stroll never opens the gate and
 * lets the animals out. Other fenced places (the archery range, fields) are
 * fine to stroll through.
 *
 * <p>This only limits the spot a stroll picks. Routes are untouched, so a
 * villager who ended up inside a ranch still walks out through the gate: its
 * next stroll spot is always outside.
 */
public final class OpenGround {

    /** How far a villager inside a ranch looks for a spot outside when its own stroll area has none. */
    private static final int WAY_OUT_RADIUS = 32;

    private OpenGround() {}

    /**
     * A random stroll spot within {@code radius} of {@code center}: a node that
     * passes {@code accept} and is not in a ranch. When every spot in reach is in
     * a ranch (the villager is inside one), the nearest spot outside further
     * out, so it walks back out. Null when there is nothing at all.
     */
    @Nullable
    public static BlockPos randomSpot(VillageManager mgr, NavGraph graph,
                                      BlockPos center, int radius, int maxDy,
                                      Random random, Predicate<NavNode> accept) {
        if (graph == null) return null;
        Predicate<NavNode> isOpen = openTest(mgr);
        NavNode best = null;
        int count = 0;
        boolean anyInRanch = false;
        for (NavNode node : graph.nodesInBox(center, radius, maxDy)) {
            if (!accept.test(node)) continue;
            if (!isOpen.test(node)) { anyInRanch = true; continue; }
            if (random.nextInt(++count) == 0) best = node;
        }
        if (best != null) return best.feetPos();
        if (!anyInRanch) return null;
        NavNode out = nearestOpen(isOpen, graph, center, maxDy, accept);
        return out != null ? out.feetPos() : null;
    }

    /** A test for "a stroll may end here", for checking many nodes in one go. */
    public static Predicate<NavNode> openTest(VillageManager mgr) {
        List<Set<BlockPos>> ranchTiles = new ArrayList<>();
        List<BlockPos> ranchGates = new ArrayList<>();
        for (Building building : mgr.getBuildings()) {
            if (building instanceof RanchBuilding ranch) {
                ranchTiles.add(ranch.getNavMeshBlocks());
                ranchGates.add(ranch.getGatePos());
            }
        }
        if (ranchGates.isEmpty()) return node -> true;
        return node -> {
            BlockPos feet = node.feetPos();
            if (ranchGates.contains(feet) || inRanch(ranchTiles, feet)) return false;
            // A second gate the player added: any doorway that opens onto a pen tile.
            if (node.portal) {
                for (NavNode next : node.neighbours()) {
                    if (inRanch(ranchTiles, next.feetPos())) return false;
                }
            }
            return true;
        };
    }

    private static boolean inRanch(List<Set<BlockPos>> ranchTiles, BlockPos feet) {
        for (Set<BlockPos> tiles : ranchTiles) if (tiles.contains(feet)) return true;
        return false;
    }

    @Nullable
    private static NavNode nearestOpen(Predicate<NavNode> isOpen, NavGraph graph,
                                       BlockPos center, int maxDy, Predicate<NavNode> accept) {
        NavNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (NavNode node : graph.nodesInBox(center, WAY_OUT_RADIUS, maxDy)) {
            if (!accept.test(node) || !isOpen.test(node)) continue;
            double d = node.feetPos().distSqr(center);
            if (d < bestDist) { bestDist = d; best = node; }
        }
        return best;
    }
}
