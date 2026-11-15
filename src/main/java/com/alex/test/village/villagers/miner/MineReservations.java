package com.alex.test.village.villagers.miner;

import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.MineBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server-side partition of registered shafts. Geometry comes from saved mine
 * markers, so ownership is independent of miner tick order and survives reloads.
 */
public final class MineReservations {
    private static final Map<ServerLevel, Snapshot> CACHE = new WeakHashMap<>();

    private MineReservations() {}

    /**
     * Shared safe plan for miners and druids working the same registered mine:
     * shaped by this mine's settings ({@link VillageManager#mineShape}) and cut
     * at {@code village}'s map edge (see {@link Border}).
     */
    public static MineShaftPlan planFor(MineBuilding mine, Snapshot reservations, VillageManager village) {
        return reservations.planFor(mine, village.mineShape(mine.getItemFrameId()), Border.of(village));
    }

    /**
     * The village map's edge across the ground (the border players see). A
     * miner only stands on map tiles, so a shaft past it could never be dug;
     * left in the plan, those cells kept the mine from ever counting as worked
     * out. Null while the map is not built yet: no cut then, or every mine
     * would look worked out right after joining.
     */
    public record Border(int minX, int maxX, int minZ, int maxZ) {
        @Nullable
        public static Border of(VillageManager village) {
            NavGraph graph = village.getGraph();
            if (graph == null || graph.isEmpty()) return null;
            return new Border(graph.getBoundsMinX(), graph.getBoundsMaxX(),
                    graph.getBoundsMinZ(), graph.getBoundsMaxZ());
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }

    public static synchronized Snapshot snapshot(ServerLevel level) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        long revision = registry.mineLayoutRevision();
        Snapshot cached = CACHE.get(level);
        if (cached != null && cached.revision() == revision) return cached;

        List<Shaft> shafts = new ArrayList<>();
        for (VillageManager village : registry.all()) {
            if (village.getLevel() != level) continue;
            collect(shafts, village);
        }
        // The command/test village is not necessarily part of the registry.
        if (VillageManager.INSTANCE.getLevel() == level
                && registry.get(VillageManager.INSTANCE.getVillageId()) == null) {
            collect(shafts, VillageManager.INSTANCE);
        }
        Snapshot result = new Snapshot(revision, List.copyOf(shafts));
        CACHE.put(level, result);
        return result;
    }

    private static void collect(List<Shaft> shafts, VillageManager village) {
        for (VillageManager.SavedBuilding marker : village.getSavedBuildings()) {
            if (!"mine".equals(marker.type())) continue;
            shafts.add(new Shaft(marker.itemFrameId(), marker.doorPos().below(2),
                    marker.interiorDirection()));
        }
    }

    private record Shaft(UUID id, BlockPos mouth, Direction direction) {
        long distanceSquared(BlockPos pos) {
            int along = (pos.getX() - mouth.getX()) * direction.getStepX()
                    + (pos.getZ() - mouth.getZ()) * direction.getStepZ();
            along = Math.max(0, Math.min(MineShaftPlan.MAX_MAIN_LENGTH, along));
            BlockPos closest = mouth.relative(direction, along);
            long dx = (long) pos.getX() - closest.getX();
            long dz = (long) pos.getZ() - closest.getZ();
            long dy = Math.max(0, Math.max(mouth.getY() - pos.getY(),
                    pos.getY() - mouth.getY() - MineShaftPlan.TUNNEL_HEIGHT + 1));
            return dx * dx + dy * dy + dz * dz;
        }
    }

    public static final class Snapshot {
        private final long revision;
        private final List<Shaft> shafts;
        private final Map<PlanKey, MineShaftPlan> plans = new HashMap<>();

        private Snapshot(long revision, List<Shaft> shafts) {
            this.revision = revision;
            this.shafts = shafts;
        }

        public long revision() { return revision; }

        /** Whether this mine's saved marker is in the split yet (just after placing, it may not be). */
        public boolean knows(UUID mineId) {
            for (Shaft shaft : shafts) if (shaft.id().equals(mineId)) return true;
            return false;
        }

        private MineShaftPlan planFor(MineBuilding mine, MineSettings.Shape shape, @Nullable Border border) {
            UUID mineId = mine.getItemFrameId();
            PlanKey key = new PlanKey(mineId, shape.left(), shape.right(), shape.mainLength(), border);
            return plans.computeIfAbsent(key, ignored ->
                    new MineShaftPlan(mine.mouth(), mine.shaftDirection(),
                            shape.left(), shape.right(), shape.mainLength(),
                            pos -> {
                                if (border != null && !border.contains(pos)) return MineShaftPlan.Stop.BORDER;
                                return owns(mineId, pos) && owns(mineId, pos.above())
                                        ? null : MineShaftPlan.Stop.NEXT_MINE;
                            }));
        }

        /** A stable nearest-axis partition; UUID resolves odd-width ties. */
        public boolean owns(UUID mineId, BlockPos pos) {
            Shaft owner = null;
            long best = Long.MAX_VALUE;
            for (Shaft shaft : shafts) {
                long distance = shaft.distanceSquared(pos);
                if (distance < best || (distance == best && owner != null
                        && shaft.id().compareTo(owner.id()) < 0)) {
                    owner = shaft;
                    best = distance;
                }
            }
            return owner != null && owner.id().equals(mineId);
        }
    }

    private record PlanKey(UUID mineId, int left, int right, int mainLength, @Nullable Border border) {}
}
