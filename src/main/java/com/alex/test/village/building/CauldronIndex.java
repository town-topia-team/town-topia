package com.alex.test.village.building;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.StandClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The cauldrons a village's farmers wash in: plain and water cauldrons standing
 * inside a house or a storage room, beside one of that room's own floor tiles
 * (the same test beds and chests pass). A cauldron in the open, or behind a
 * wall, is never one of them.
 *
 * <p>One per {@link VillageManager}, in memory only. Each building's cauldrons
 * are found by looking beside its room tiles, and that answer is kept for
 * {@link #RESCAN_TICKS} (spread a little by building) or until a block changes
 * near the room. The water level is never cached: it is read from the block
 * whenever it is asked for.
 *
 * <p>It also keeps what the refills need to remember: which day each cauldron
 * was last refilled, the water source found for it, and which farmer gave up
 * on which cauldron for a while.
 */
public final class CauldronIndex {

    public enum Kind { HOUSE, STORAGE }

    /** One cauldron that counts, and the room it stands in. */
    public record Spot(BlockPos pos, Building building, Kind kind) {}

    /** How a work tile sits to its cauldron: straight beside it, a step or two off, or on top of it. */
    public enum Fit { BESIDE, NEAR, TOP }

    /** The exact tile a farmer stands on to wash in, or refill, one cauldron. */
    public record StandSpot(BlockPos pos, Fit fit) {}

    /** How far from the cauldron a {@link Fit#NEAR} tile may be, across. */
    private static final int NEAR_RADIUS = 2;

    /** A water source block and the floor tile it is filled from. */
    public record WaterSpot(BlockPos water, BlockPos stand) {}

    /** How long a building's cauldron list is trusted. */
    public static final int RESCAN_TICKS = 600;
    /** Extra rescan delay spread over buildings, so they do not all rescan on one tick. */
    private static final int RESCAN_SPREAD_TICKS = 200;

    private record Scan(List<BlockPos> cauldrons, long at) {}

    private final Map<UUID, Scan> scans = new HashMap<>();
    /** Cauldron (packed position) to the day index it was last refilled. */
    private final Map<Long, Long> refilledDay = new HashMap<>();
    /** Cauldron (packed position) to the water source last found for it. */
    private final Map<Long, WaterSpot> waterSources = new HashMap<>();
    /** Cauldron (packed position) to the game time before which no water search is tried again. */
    private final Map<Long, Long> noWaterUntil = new HashMap<>();
    /** Cauldron (packed position) to the farmers that gave up on it, and until when. */
    private final Map<Long, Map<UUID, Long>> refillCooldowns = new HashMap<>();

    /**
     * Every cauldron that counts in the village right now. Buildings whose
     * list is stale are rescanned first.
     */
    public List<Spot> spots(VillageManager mgr, ServerLevel level) {
        long now = level.getGameTime();
        List<Spot> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Building building : mgr.getBuildings()) {
            Kind kind = kindOf(building);
            if (kind == null || !building.isValid() || building.getNavMeshBlocks().isEmpty()) continue;
            UUID id = building.getItemFrameId();
            seen.add(id);
            Scan scan = scans.get(id);
            long maxAge = RESCAN_TICKS + Math.floorMod(id.hashCode(), RESCAN_SPREAD_TICKS);
            if (scan == null || now < scan.at() || now - scan.at() >= maxAge) {
                scan = new Scan(scan(level, building), now);
                scans.put(id, scan);
            }
            for (BlockPos pos : scan.cauldrons()) out.add(new Spot(pos, building, kind));
        }
        scans.keySet().retainAll(seen);
        return out;
    }

    /** The cauldron at {@code pos}, if it is one that counts. */
    @Nullable
    public Spot spotAt(VillageManager mgr, ServerLevel level, BlockPos pos) {
        for (Spot spot : spots(mgr, level)) {
            if (spot.pos().equals(pos)) return spot;
        }
        return null;
    }

    /**
     * A block changed at {@code pos}: any room it could belong to gets its
     * cauldrons looked for again on the next ask.
     */
    public void onBlockChanged(VillageManager mgr, BlockPos pos) {
        if (scans.isEmpty()) return;
        for (Building building : mgr.getBuildings()) {
            if (scans.containsKey(building.getItemFrameId()) && building.mayAffectRoom(pos)) {
                scans.remove(building.getItemFrameId());
            }
        }
    }

    /** Cauldrons beside the room's floor tiles, at foot height. */
    private static List<BlockPos> scan(ServerLevel level, Building building) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (BlockPos tile : building.getNavMeshBlocks()) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos side = tile.relative(dir);
                if (found.contains(side) || !level.isLoaded(side)) continue;
                if (waterLevel(level, side) >= 0) found.add(side.immutable());
            }
        }
        return new ArrayList<>(found);
    }

    @Nullable
    private static Kind kindOf(Building building) {
        if (building instanceof HouseBuilding) return Kind.HOUSE;
        if (building instanceof StorageBuilding) return Kind.STORAGE;
        return null;
    }

    // =========================================================================
    // Block helpers
    // =========================================================================

    /**
     * 0 for an empty cauldron, 1 to 3 for a water cauldron, -1 for anything
     * else (lava, powder snow, or no cauldron at all any more).
     */
    public static int waterLevel(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.CAULDRON)) return 0;
        if (state.is(Blocks.WATER_CAULDRON)) return state.getValue(LayeredCauldronBlock.LEVEL);
        return -1;
    }

    /** The room tile beside the cauldron to stand on, or null when there is none. */
    @Nullable
    public static BlockPos standTile(Building building, BlockPos cauldron, @Nullable NavGraph graph, BlockPos from) {
        return building.getWalkTargetBeside(cauldron, graph, from);
    }

    /**
     * The tile to stand on to work this cauldron, best first: one of the four
     * tiles straight beside it (the nearest to {@code from}); else the room
     * tile nearest to it within {@value #NEAR_RADIUS} blocks on the same floor
     * (never one standing on a cauldron); else, last of all, the top of the
     * cauldron itself. A tile counts only when it has a walkable graph node
     * and nobody else has claimed it; with {@code checkBodies}, also only when
     * nobody else is standing on it. The first pick leaves bodies out: the
     * farmer before it is often still on the tile when the turn passes, and
     * steps off a moment later. Null when there is none.
     *
     * <p>{@link #standTile} stays the plain "can the farmer get to this
     * cauldron at all" question; this is where it actually stands.
     */
    @Nullable
    public static StandSpot workTile(ServerLevel level, Building building, BlockPos cauldron,
                                     @Nullable NavGraph graph, @Nullable BlockPos from, UUID me,
                                     boolean checkBodies) {
        if (graph == null) return null;
        BlockPos origin = from != null ? from : cauldron;
        Set<BlockPos> room = building.getNavMeshBlocks();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos tile = cauldron.relative(dir);
            if (!room.contains(tile) || !usableTile(level, graph, tile, me, checkBodies)) continue;
            double d = tile.distSqr(origin);
            if (d < bestDist) {
                bestDist = d;
                best = tile;
            }
        }
        if (best != null) return new StandSpot(best.immutable(), Fit.BESIDE);

        double bestAcross = Double.MAX_VALUE;
        bestDist = Double.MAX_VALUE;
        for (BlockPos tile : room) {
            if (tile.getY() != cauldron.getY()) continue;
            int dx = tile.getX() - cauldron.getX();
            int dz = tile.getZ() - cauldron.getZ();
            if (Math.abs(dx) > NEAR_RADIUS || Math.abs(dz) > NEAR_RADIUS || (dx == 0 && dz == 0)) continue;
            if (level.getBlockState(tile.below()).getBlock() instanceof AbstractCauldronBlock) continue;
            if (!usableTile(level, graph, tile, me, checkBodies)) continue;
            double across = dx * dx + dz * dz;
            double d = tile.distSqr(origin);
            if (across < bestAcross || (across == bestAcross && d < bestDist)) {
                bestAcross = across;
                bestDist = d;
                best = tile;
            }
        }
        if (best != null) return new StandSpot(best.immutable(), Fit.NEAR);

        BlockPos top = cauldron.above();
        if (graph.nodeAt(top.getX(), top.getY(), top.getZ()) != null
                && StandClaims.isFreeFor(level, top, me) && !npcOn(level, top, me)) {
            return new StandSpot(top.immutable(), Fit.TOP);
        }
        return null;
    }

    /** Walkable, unclaimed by anyone else, and (with {@code checkBodies}) nobody else standing on it. */
    private static boolean usableTile(ServerLevel level, NavGraph graph, BlockPos tile, UUID me,
                                      boolean checkBodies) {
        NavNode node = graph.nodeAt(tile.getX(), tile.getY(), tile.getZ());
        if (node == null || !node.isRoutable() || !StandClaims.isFreeFor(level, tile, me)) return false;
        return !checkBodies || !npcOn(level, tile, me);
    }

    /** Someone else has claimed this tile, or another villager is standing on it. */
    public static boolean tileTaken(ServerLevel level, BlockPos tile, UUID me) {
        return !StandClaims.isFreeFor(level, tile, me) || npcOn(level, tile, me);
    }

    /** Another living villager (not {@code me}) is inside this block. */
    private static boolean npcOn(ServerLevel level, BlockPos tile, UUID me) {
        return !level.getEntitiesOfClass(NpcEntity.class, new AABB(tile),
                other -> other.isAlive() && !me.equals(other.getNpcId())).isEmpty();
    }

    // =========================================================================
    // Refill memory
    // =========================================================================

    public boolean refilledOn(BlockPos cauldron, long day) {
        Long refilled = refilledDay.get(cauldron.asLong());
        return refilled != null && refilled == day;
    }

    public void markRefilled(BlockPos cauldron, long day) {
        refilledDay.put(cauldron.asLong(), day);
    }

    @Nullable
    public WaterSpot waterSource(BlockPos cauldron) {
        return waterSources.get(cauldron.asLong());
    }

    public void setWaterSource(BlockPos cauldron, @Nullable WaterSpot spot) {
        if (spot == null) waterSources.remove(cauldron.asLong());
        else waterSources.put(cauldron.asLong(), spot);
    }

    /** No water was found for this cauldron recently; the search is not worth repeating yet. */
    public boolean waterSearchResting(BlockPos cauldron, long now) {
        Long until = noWaterUntil.get(cauldron.asLong());
        return until != null && now < until;
    }

    public void restWaterSearch(BlockPos cauldron, long until) {
        noWaterUntil.put(cauldron.asLong(), until);
    }

    /** This farmer gave up on this cauldron a short while ago. */
    public boolean onRefillCooldown(BlockPos cauldron, UUID farmer, long now) {
        Map<UUID, Long> byFarmer = refillCooldowns.get(cauldron.asLong());
        if (byFarmer == null) return false;
        Long until = byFarmer.get(farmer);
        if (until == null) return false;
        if (now >= until) {
            byFarmer.remove(farmer);
            if (byFarmer.isEmpty()) refillCooldowns.remove(cauldron.asLong());
            return false;
        }
        return true;
    }

    public void setRefillCooldown(BlockPos cauldron, UUID farmer, long until) {
        refillCooldowns.computeIfAbsent(cauldron.asLong(), k -> new HashMap<>()).put(farmer, until);
    }

    @Nullable
    public Long refilledDay(BlockPos cauldron) {
        return refilledDay.get(cauldron.asLong());
    }
}
