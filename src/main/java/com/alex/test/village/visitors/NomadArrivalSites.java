package com.alex.test.village.visitors;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.NavReachability;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Selects safe nav nodes well inside the village bounds.
 *
 * <p>Sites are also kept inside the simulation distance of the nearest player,
 * with {@link #SIMULATION_MARGIN_CHUNKS} to spare, so a nomad that has just
 * spawned keeps walking in even if that player wanders off a little. Sites
 * near the edge of that reach are tried first, so the walk in is still a walk.
 */
public final class NomadArrivalSites {
    public static final int PERIMETER_BAND = 3;
    public static final int PREFERRED_BORDER_CLEARANCE = 30;
    /** Chunks kept between a spawn site and the edge of the nearest player's simulation distance. */
    public static final int SIMULATION_MARGIN_CHUNKS = 2;
    private static final int PROBE_ATTEMPTS = 128;
    private static final int WANTED = 16;
    private static final int SNAP_XZ = 2;
    private static final int SNAP_Y = 8;

    private NomadArrivalSites() {}

    public static List<BlockPos> candidates(ServerLevel level, VillageManager village,
                                            RandomSource random, Set<BlockPos> excluded) {
        NavGraph graph = village.getGraph();
        if (graph == null || graph.isEmpty()) return List.of();
        int minX = graph.getBoundsMinX(), maxX = graph.getBoundsMaxX();
        int minZ = graph.getBoundsMinZ(), maxZ = graph.getBoundsMaxZ();
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        if (width <= 0 || depth <= 0) return List.of();

        // Keep a 30 block buffer when the village is large enough. Smaller
        // footprints use their available center instead of producing no sites.
        int inset = Math.min(PREFERRED_BORDER_CLEARANCE,
                Math.max(0, (Math.min(width, depth) - 1) / 2));
        int innerMinX = minX + inset, innerMaxX = maxX - inset;
        int innerMinZ = minZ + inset, innerMaxZ = maxZ - inset;

        List<BlockPos> result = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>(excluded);
        BlockPos gate = village.getVisitorGatheringSpot();
        for (int attempt = 0; attempt < PROBE_ATTEMPTS && result.size() < WANTED; attempt++) {
            BlockPos perimeterProbe = new BlockPos(
                    innerMinX + random.nextInt(innerMaxX - innerMinX + 1), gate.getY(),
                    innerMinZ + random.nextInt(innerMaxZ - innerMinZ + 1));
            NavNode node = graph.nearestRoutable(
                    perimeterProbe, Math.max(SNAP_XZ, inset == 0 ? 8 : SNAP_XZ), SNAP_Y);
            if (!usable(level, village, graph, node, gate)) continue;
            BlockPos feet = node.feetPos();
            if (!seen.add(feet)) continue;
            result.add(feet);
        }
        // Farthest from the player first: sites in the outermost allowed chunk
        // ring, then the rest. The sort is stable, so each group stays random.
        int preferredChunks = maxSpawnChunks(level) - 1;
        result.sort((a, b) -> Boolean.compare(
                nearestPlayerChunkDistance(level, b) >= preferredChunks,
                nearestPlayerChunkDistance(level, a) >= preferredChunks));
        return result;
    }

    /** Furthest a site may be from its nearest player, in chunks (simulation distance minus the margin). */
    public static int maxSpawnChunks(ServerLevel level) {
        int simulation = level.getServer().getPlayerList().getSimulationDistance();
        return Math.max(1, simulation - SIMULATION_MARGIN_CHUNKS);
    }

    /**
     * Chebyshev chunk distance from {@code pos} to the nearest non-spectator
     * player in {@code level}, the same square shape vanilla's ticking area
     * has. {@link Integer#MAX_VALUE} when nobody is there.
     */
    public static int nearestPlayerChunkDistance(ServerLevel level, BlockPos pos) {
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        int best = Integer.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            int dx = Math.abs(player.chunkPosition().x - chunkX);
            int dz = Math.abs(player.chunkPosition().z - chunkZ);
            best = Math.min(best, Math.max(dx, dz));
        }
        return best;
    }

    /** Pure length-weighted side/position picker used by production and tests. */
    public static BlockPos randomPerimeterProbe(int minX, int maxX, int minZ, int maxZ,
                                                int y, RandomSource random) {
        NomadSpawnRules.Probe probe = NomadSpawnRules.perimeterProbe(
                minX, maxX, minZ, maxZ, PERIMETER_BAND, random::nextInt);
        return new BlockPos(probe.x(), y, probe.z());
    }

    public static boolean isInPerimeterBand(VillageManager village, BlockPos pos) {
        NavGraph graph = village.getGraph();
        if (graph == null || !village.contains(pos)) return false;
        int edgeDistance = Math.min(
                Math.min(pos.getX() - graph.getBoundsMinX(), graph.getBoundsMaxX() - pos.getX()),
                Math.min(pos.getZ() - graph.getBoundsMinZ(), graph.getBoundsMaxZ() - pos.getZ()));
        return edgeDistance >= 0 && edgeDistance <= PERIMETER_BAND;
    }

    public static boolean usable(ServerLevel level, VillageManager village, NavGraph graph,
                                  @Nullable NavNode node, BlockPos gate) {
        if (node == null || node.degree() == 0 || !node.isRoutable()
                || node.portal || node.climb) return false;
        BlockPos feet = node.feetPos();
        if (!village.contains(feet)) return false;
        if (!level.hasChunkAt(feet)) return false;
        // Somewhere a player is simulating, with room to spare, or the nomad
        // would stand frozen at the spawn site until someone came by.
        if (!level.isPositionEntityTicking(feet)
                || nearestPlayerChunkDistance(level, feet) > maxSpawnChunks(level)) return false;
        if (!level.getFluidState(feet).isEmpty()
                || !level.getFluidState(feet.below()).isEmpty()) return false;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
            return false;
        }
        for (Building building : village.getBuildings()) {
            if (building.getNavMeshBlocks().contains(feet) || building.isNearRoom(feet)) return false;
        }
        if (!level.getEntities(null, new AABB(feet).inflate(0.35, 1.0, 0.35)).isEmpty()) return false;
        return NavReachability.canReach(graph, feet, gate);
    }
}
