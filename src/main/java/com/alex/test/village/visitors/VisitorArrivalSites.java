package com.alex.test.village.visitors;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.NavReachability;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a visitor may walk in from.
 *
 * <p>A visitor has no {@code VillagerModel}, so it navigates with vanilla
 * ground pathing and nothing in the mod will rescue it if it lands somewhere it
 * cannot walk out of. Dropping one on the nearest surface block is therefore
 * not good enough: a nomad spawned on a tree canopy, on a one-block island, in
 * a ravine, or across a river simply never reaches the Town Hall and expires
 * where it stood.
 *
 * <p>So a candidate has to earn its place. It must be a real nav-graph node —
 * which already means solid ground, two blocks of headroom and a safe ledge —
 * it must not be water, furniture or the inside of somebody's house, and the
 * graph must contain an actual route from it to the Town Hall door, which the
 * visitor then walks on the graph.
 */
public final class VisitorArrivalSites {

    /** Close enough that the walk in is short; far enough that they arrive from "outside". */
    private static final int MIN_DISTANCE = 12;
    /**
     * Kept under the 32-block FOLLOW_RANGE a visitor's navigator uses as its
     * hard path-length limit — a site further out than that cannot be planned
     * to at all, however walkable the ground between is.
     */
    private static final int MAX_DISTANCE = 26;
    /** Random ring probes before giving up. Each is a cheap nearest-node lookup. */
    private static final int PROBE_ATTEMPTS = 64;
    /**
     * How close a visitor may appear when the ordinary ring found nowhere.
     *
     * <p>On generated terrain the twelve-to-twenty-six-block band around a
     * village can be almost all cliff, water or tree canopy, and a trader that
     * never turns up because the ground outside town is awkward is a worse
     * outcome than one that walks in from the edge of the square.
     */
    private static final int FALLBACK_MIN_DISTANCE = 5;
    /** How many verified sites to collect before stopping early. */
    private static final int WANTED_CANDIDATES = 6;
    /** Vertical slack when snapping a probe to a node — visitors may arrive uphill. */
    private static final int SNAP_Y_RADIUS = 6;
    private static final int SNAP_XZ_RADIUS = 4;

    private VisitorArrivalSites() {}

    /**
     * Safe, reachable arrival spots around {@code village}, best first.
     *
     * <p>Open-sky sites sort ahead of enclosed ones, so a visitor to a village
     * with a cellar walks in across the fields rather than materialising in the
     * cellar — but an entirely underground village still gets sites rather than
     * nothing.
     */
    public static List<BlockPos> candidates(ServerLevel level, VillageManager village,
                                            RandomSource random) {
        NavGraph graph = village.getGraph();
        BlockPos gate = village.getVisitorGatheringSpot();
        if (graph == null || graph.isEmpty()) return List.of();

        List<BlockPos> ordered = sweep(level, village, random, graph, gate, MIN_DISTANCE);
        if (ordered.isEmpty()) {
            ordered = sweep(level, village, random, graph, gate, FALLBACK_MIN_DISTANCE);
        }
        return ordered;
    }

    /** One ring sweep, from {@code minDistance} out to {@link #MAX_DISTANCE}. */
    private static List<BlockPos> sweep(ServerLevel level, VillageManager village,
                                        RandomSource random, NavGraph graph, BlockPos gate,
                                        int minDistance) {
        List<BlockPos> openSky = new ArrayList<>();
        List<BlockPos> sheltered = new ArrayList<>();

        for (int attempt = 0; attempt < PROBE_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int distance = minDistance + random.nextInt(MAX_DISTANCE - minDistance + 1);
            BlockPos probe = gate.offset(
                    Mth.floor(Math.cos(angle) * distance), 0,
                    Mth.floor(Math.sin(angle) * distance));

            NavNode node = graph.nearestRoutable(probe, SNAP_XZ_RADIUS, SNAP_Y_RADIUS);
            if (!isUsable(node, village, gate, minDistance)) continue;

            BlockPos feet = node.feetPos();
            if (openSky.contains(feet) || sheltered.contains(feet)) continue;
            // A visitor outside every player's simulation distance would stand frozen.
            if (!level.isPositionEntityTicking(feet)) continue;
            if (!NavReachability.canReach(graph, feet, gate)) continue;

            (level.canSeeSky(feet) ? openSky : sheltered).add(feet);
            if (openSky.size() >= WANTED_CANDIDATES) break;
        }

        List<BlockPos> ordered = new ArrayList<>(openSky);
        ordered.addAll(sheltered);
        return ordered;
    }

    /** The first candidate, or null when the village has nowhere safe to arrive. */
    @Nullable
    public static BlockPos any(ServerLevel level, VillageManager village, RandomSource random) {
        List<BlockPos> candidates = candidates(level, village, random);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean isUsable(@Nullable NavNode node, VillageManager village,
                                    BlockPos gate, int minDistance) {
        if (node == null || node.degree() == 0) return false;
        // Nobody walks in out of the sea, off a ladder, or standing in a doorway.
        if (!node.isRoutable() || node.portal || node.climb) return false;

        BlockPos feet = node.feetPos();
        int dx = feet.getX() - gate.getX();
        int dz = feet.getZ() - gate.getZ();
        if (dx * dx + dz * dz < minDistance * minDistance) return false;

        for (Building building : village.getBuildings()) {
            if (building.getNavMeshBlocks().contains(feet)) return false;
        }
        return true;
    }
}
