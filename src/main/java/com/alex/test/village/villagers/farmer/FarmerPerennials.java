package com.alex.test.village.villagers.farmer;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import com.alex.test.village.villagers.farmer.crops.CropPlantingStrategy;
import com.alex.test.village.villagers.farmer.crops.HarvestTool;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Cocoa pods, sweet berry bushes and glow berry vines: crops that need
 * neither farmland nor water, so the water-anchored field survey
 * ({@link FarmSiteSurvey}) never sees them. Owned by {@link FarmerManager},
 * which still hands out the jobs, reservations and supply trips; this class
 * only finds the plants, reads them, and says where work is. Every harvest
 * here needs the tool the crop data names (shears).
 *
 * <p><b>Cocoa.</b> Every pod a farmer has seen is remembered with the side of
 * the log it hangs on. A harvested pod is replanted on exactly that spot, and
 * nowhere else. Logs that carry a remembered pod are protected from lumberjacks.
 *
 * <p><b>Sweet berries.</b> Bushes are picked, never broken, so they grow back.
 * Every patch, wild or player planted (there is no telling them apart), keeps
 * growing until the player sets berries to Harvest only or Leave, or closes it
 * in: the patch is the grass/dirt the bushes stand on, flood-filled at their
 * own height, and any other block ends it. New bushes go on every other row
 * with a free ring along the edge (none needed beside a border farmers can
 * walk on, such as trapdoors or carpet), never next to a path or a door, and
 * never past {@link #MAX_BUSHES} in one patch. A bush is picked from the
 * stage it shows fruit, full ones first.
 *
 * <p><b>Glow berries.</b> Only picked, never planted: where vines hang is the
 * player's choice. The chunk scan reads 12 blocks down from the surface, so
 * vines deep in caves are never found; that is accepted.
 */
public final class FarmerPerennials {

    /** The flood that finds a berry patch's ground stops here, for the server's sake. */
    static final int BERRY_REGION_CAP = 4096;
    /** No patch is grown past this many bushes. */
    static final int MAX_BUSHES = 512;
    /** New bushes keep this many tiles away from paths and doors. */
    private static final int ROUTE_CLEARANCE = 2;
    /** Plantable spots kept per survey; the ones nearest the existing bushes. */
    private static final int MAX_PLANTABLE = 64;
    /** A pod or vine can be worked with the feet up to this many blocks below it. */
    private static final int REACH_BELOW = 2;
    /**
     * A berry bush shows fruit from this stage and a player can pick it, so
     * farmers do too. The bush starts over sooner, which gives more berries
     * an hour than waiting for the last stage.
     */
    private static final int FIRST_PICK_AGE = 2;
    /** A bush at its last stage gives one berry more: picked first, up to this many blocks further away. */
    private static final double FULL_BUSH_BONUS = 20.0;
    private static final int SURVEY_COOLDOWN_TICKS = 20;
    private static final int VALIDATION_INTERVAL_TICKS = 100;
    private static final long COCOA_SALT = 0x636F636F61L;
    private static final long BERRY_SALT = 0x6265727279L;
    private static final long GLOW_SALT = 0x676C6F77L;

    enum Kind { COCOA, BERRY, GLOW_BERRY }

    static final class Patch {
        final UUID id;
        final Kind kind;
        final CropDefinition definition;
        /** Cocoa: remembered pod cells. Berries: bushes last seen. Glow berries: vine segments. */
        final Set<BlockPos> plants = new LinkedHashSet<>();
        /** Berries: the open ground of the patch, nearest the first bush first. */
        final Set<BlockPos> region = new LinkedHashSet<>();
        /** Ready to pick: berry bushes from the stage they show fruit, everything else fully grown. */
        final Set<BlockPos> mature = new LinkedHashSet<>();
        /** Berries: the ready bushes at their last stage, which give one berry more. */
        final Set<BlockPos> full = new LinkedHashSet<>();
        final Set<BlockPos> growing = new LinkedHashSet<>();
        /** Empty cells to plant: a harvested pod spot, or a free tile in a berry row. */
        final Set<BlockPos> plantable = new LinkedHashSet<>();
        int minX, minY, minZ, maxX, maxY, maxZ;
        long lastSurvey = Long.MIN_VALUE;

        Patch(UUID id, Kind kind, CropDefinition definition) {
            this.id = id;
            this.kind = kind;
            this.definition = definition;
        }

        boolean near(BlockPos pos, int margin) {
            return pos.getX() >= minX - margin && pos.getX() <= maxX + margin
                    && pos.getY() >= minY - margin && pos.getY() <= maxY + margin
                    && pos.getZ() >= minZ - margin && pos.getZ() <= maxZ + margin;
        }

        void recomputeBounds() {
            minX = minY = minZ = Integer.MAX_VALUE;
            maxX = maxY = maxZ = Integer.MIN_VALUE;
            for (Set<BlockPos> positions : List.of(plants, region)) {
                for (BlockPos pos : positions) {
                    minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
                    minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
                    minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
                }
            }
        }
    }

    /** One piece of perennial work the manager may hand out. */
    record Candidate(Patch patch, FarmerManager.JobType type, BlockPos target, Item item, int priority) {}

    /** Every pod cell a farmer tends, to the side of the log it hangs on (its FACING). */
    private final Map<BlockPos, Direction> podSpots = new LinkedHashMap<>();
    /** The logs those pods hang on; lumberjacks leave these trees alone. */
    private final Set<BlockPos> tendedLogs = new HashSet<>();
    /**
     * Remembered pod spots no farmer can stand to work, as of the last survey.
     * Never worked, and their logs are not protected. A spot not surveyed
     * since loading is not in here, so it stays protected until it is.
     */
    private final Set<BlockPos> unreachablePods = new HashSet<>();
    private final Map<UUID, Patch> patches = new LinkedHashMap<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private Predicate<BlockPos> inBounds = pos -> true;
    private int validationCursor;
    private long nextValidationAt = Long.MIN_VALUE;

    boolean isEmpty() {
        return patches.isEmpty();
    }

    Collection<Patch> patches() {
        return patches.values();
    }

    // =========================================================================
    // Discovery
    // =========================================================================

    /**
     * Regroups the known plants into patches. Cocoa and glow berry patches
     * are rebuilt and surveyed on the spot (cheap). Berry patches persist:
     * only bushes outside every known patch start a new one, and its ground
     * is flooded later through the survey queue, one patch at a time.
     */
    void rebuild(ServerLevel level, VillageManager village, List<BlockPos> podsSeen,
                 List<BlockPos> bushesSeen, List<BlockPos> vinesSeen,
                 Predicate<BlockPos> inBounds, long now) {
        this.inBounds = inBounds;
        podSpots.keySet().removeIf(pos -> !inBounds.test(pos));
        for (BlockPos pod : podsSeen) {
            if (!level.hasChunkAt(pod)) continue;
            BlockState state = level.getBlockState(pod);
            if (state.getBlock() instanceof CocoaBlock) {
                podSpots.put(pod.immutable(), state.getValue(CocoaBlock.FACING));
            }
        }
        rebuildTendedLogs();

        patches.values().removeIf(patch -> patch.kind != Kind.BERRY);
        patches.values().removeIf(patch -> patch.plants.stream().noneMatch(inBounds));

        CropDefinition cocoa = definitionFor(Kind.COCOA);
        if (cocoa != null && !podSpots.isEmpty()) {
            Map<FarmerPlanning.Point, List<BlockPos>> podsByLog = new HashMap<>();
            for (Map.Entry<BlockPos, Direction> spot : podSpots.entrySet()) {
                podsByLog.computeIfAbsent(FarmSiteSurvey.point(spot.getKey().relative(spot.getValue())),
                        ignored -> new ArrayList<>()).add(spot.getKey());
            }
            for (Set<FarmerPlanning.Point> logs : FarmerPlanning.connectedComponents(podsByLog.keySet())) {
                List<BlockPos> pods = new ArrayList<>();
                for (FarmerPlanning.Point log : logs) pods.addAll(podsByLog.get(log));
                pods.sort(FarmSiteSurvey.POSITION_ORDER);
                Patch patch = new Patch(patchId(village, pods.getFirst(), COCOA_SALT), Kind.COCOA, cocoa);
                patch.plants.addAll(pods);
                patches.put(patch.id, patch);
                survey(level, village, patch, now);
            }
        }

        CropDefinition glow = definitionFor(Kind.GLOW_BERRY);
        if (glow != null && !vinesSeen.isEmpty()) {
            for (List<BlockPos> vines : clusters(vinesSeen)) {
                Patch patch = new Patch(patchId(village, vines.getFirst(), GLOW_SALT), Kind.GLOW_BERRY, glow);
                patch.plants.addAll(vines);
                patches.put(patch.id, patch);
                survey(level, village, patch, now);
            }
        }

        CropDefinition berries = definitionFor(Kind.BERRY);
        if (berries == null) {
            patches.values().removeIf(patch -> patch.kind == Kind.BERRY);
        } else if (!bushesSeen.isEmpty()) {
            Set<BlockPos> covered = new HashSet<>();
            for (Patch patch : patches.values()) {
                if (patch.kind != Kind.BERRY) continue;
                covered.addAll(patch.plants);
                covered.addAll(patch.region);
            }
            List<BlockPos> loose = new ArrayList<>();
            for (BlockPos bush : bushesSeen) if (!covered.contains(bush)) loose.add(bush);
            for (List<BlockPos> bushes : clusters(loose)) {
                Patch patch = new Patch(patchId(village, bushes.getFirst(), BERRY_SALT), Kind.BERRY, berries);
                if (patches.containsKey(patch.id)) continue;
                patch.plants.addAll(bushes);
                patch.recomputeBounds();
                patches.put(patch.id, patch);
                dirty.add(patch.id);
            }
        }
        dirty.retainAll(patches.keySet());
    }

    private static List<List<BlockPos>> clusters(List<BlockPos> positions) {
        List<FarmerPlanning.Point> points = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) points.add(FarmSiteSurvey.point(pos));
        List<List<BlockPos>> result = new ArrayList<>();
        for (Set<FarmerPlanning.Point> cluster : FarmerPlanning.connectedComponents(points)) {
            List<BlockPos> members = new ArrayList<>(cluster.size());
            for (FarmerPlanning.Point point : cluster) members.add(new BlockPos(point.x(), point.y(), point.z()));
            members.sort(FarmSiteSurvey.POSITION_ORDER);
            result.add(members);
        }
        return result;
    }

    private static UUID patchId(VillageManager village, BlockPos anchor, long salt) {
        return FarmerPlanning.stableFarmId(village.getVillageId(), anchor.asLong() ^ salt);
    }

    @Nullable
    static CropDefinition definitionFor(Kind kind) {
        for (CropDefinition definition : CropDefinitionRegistry.all()) {
            CropPlantingStrategy planting = definition.planting();
            if (kind == Kind.COCOA && planting instanceof CropPlantingStrategy.OnLog) return definition;
            if (kind == Kind.BERRY && planting instanceof CropPlantingStrategy.OnSoil) return definition;
            if (kind == Kind.GLOW_BERRY && planting instanceof CropPlantingStrategy.Hanging) return definition;
        }
        return null;
    }

    private void rebuildTendedLogs() {
        unreachablePods.retainAll(podSpots.keySet());
        tendedLogs.clear();
        for (Map.Entry<BlockPos, Direction> spot : podSpots.entrySet()) {
            if (unreachablePods.contains(spot.getKey())) continue;
            tendedLogs.add(spot.getKey().relative(spot.getValue()).immutable());
        }
    }

    // =========================================================================
    // Survey
    // =========================================================================

    void tick(ServerLevel level, VillageManager village, long now) {
        Iterator<UUID> iterator = dirty.iterator();
        while (iterator.hasNext()) {
            Patch patch = patches.get(iterator.next());
            if (patch == null) {
                iterator.remove();
                continue;
            }
            if (patch.lastSurvey != Long.MIN_VALUE && now - patch.lastSurvey < SURVEY_COOLDOWN_TICKS) continue;
            iterator.remove();
            survey(level, village, patch, now);
            return;
        }
        if (nextValidationAt == Long.MIN_VALUE) nextValidationAt = now + VALIDATION_INTERVAL_TICKS;
        if (now < nextValidationAt || patches.isEmpty()) return;
        nextValidationAt = now + VALIDATION_INTERVAL_TICKS;
        List<Patch> all = new ArrayList<>(patches.values());
        if (validationCursor >= all.size()) validationCursor = 0;
        survey(level, village, all.get(validationCursor++), now);
    }

    private void survey(ServerLevel level, VillageManager village, Patch patch, long now) {
        patch.mature.clear();
        patch.full.clear();
        patch.growing.clear();
        patch.plantable.clear();
        switch (patch.kind) {
            case COCOA -> surveyCocoa(level, village, patch);
            case BERRY -> surveyBerries(level, village, patch);
            case GLOW_BERRY -> surveyGlowBerries(level, village, patch);
        }
        patch.recomputeBounds();
        patch.lastSurvey = now;
    }

    private void surveyCocoa(ServerLevel level, VillageManager village, Patch patch) {
        NavGraph graph = village.getGraph();
        boolean changed = false;
        Iterator<BlockPos> pods = patch.plants.iterator();
        while (pods.hasNext()) {
            BlockPos pod = pods.next();
            if (!level.hasChunkAt(pod)) continue;
            Direction facing = podSpots.get(pod);
            // The log was cut or replaced: nothing can grow here any more.
            if (facing == null || !isPodLog(level, pod.relative(facing), patch.definition)) {
                podSpots.remove(pod);
                pods.remove();
                changed = true;
                continue;
            }
            // Only pods a farmer can stand to work are tended, and only their
            // trees are kept from the lumberjacks. With no graph yet nothing
            // is known, so the protection stays as it was.
            if (graph != null) {
                boolean reachable = standFor(level, graph, Kind.COCOA, pod, pod) != null;
                changed |= reachable ? unreachablePods.remove(pod) : unreachablePods.add(pod);
                if (!reachable) continue;
            } else if (unreachablePods.contains(pod)) {
                continue;
            }
            BlockState state = level.getBlockState(pod);
            if (patch.definition.matchesCropBlock(state.getBlock())) {
                if (CropDefinitionRegistry.isMature(state, patch.definition)) patch.mature.add(pod);
                else patch.growing.add(pod);
            } else if (state.isAir()) {
                patch.plantable.add(pod);
            }
        }
        if (changed) rebuildTendedLogs();
    }

    private static boolean isPodLog(ServerLevel level, BlockPos log, CropDefinition definition) {
        return level.hasChunkAt(log)
                && definition.planting() instanceof CropPlantingStrategy.OnLog onLog
                && onLog.matchesLog(level.getBlockState(log).getBlock());
    }

    /** Only segments a farmer can reach from the ground are tracked at all. */
    private static void surveyGlowBerries(ServerLevel level, VillageManager village, Patch patch) {
        NavGraph graph = village.getGraph();
        patch.plants.removeIf(vine -> level.hasChunkAt(vine)
                && !patch.definition.matchesCropBlock(level.getBlockState(vine).getBlock()));
        for (BlockPos vine : patch.plants) {
            if (!level.hasChunkAt(vine) || standFor(level, graph, Kind.GLOW_BERRY, vine, vine) == null) continue;
            BlockState state = level.getBlockState(vine);
            if (CropDefinitionRegistry.isMature(state, patch.definition)) patch.mature.add(vine);
            else patch.growing.add(vine);
        }
    }

    private void surveyBerries(ServerLevel level, VillageManager village, Patch patch) {
        BlockPos start = null;
        for (BlockPos bush : patch.plants) {
            if (level.hasChunkAt(bush) && patch.definition.matchesCropBlock(level.getBlockState(bush).getBlock())) {
                start = bush;
                break;
            }
        }
        patch.region.clear();
        if (start == null) {
            // Every bush gone: the patch goes with them.
            patch.plants.removeIf(bush -> level.hasChunkAt(bush));
            if (patch.plants.isEmpty()) patches.remove(patch.id);
            return;
        }
        BerryGround ground = floodGround(level, start, patch.definition, inBounds);
        patch.region.addAll(ground.cells());
        patch.plants.clear();
        for (BlockPos cell : patch.region) {
            if (patch.definition.matchesCropBlock(level.getBlockState(cell).getBlock())) patch.plants.add(cell);
        }
        absorbOverlappingPatches(patch);
        for (BlockPos bush : patch.plants) {
            BlockState state = level.getBlockState(bush);
            if (pickable(patch.kind, state, patch.definition)) {
                patch.mature.add(bush);
                if (fullBush(state)) patch.full.add(bush);
            } else {
                patch.growing.add(bush);
            }
        }
        if (patch.plants.size() >= MAX_BUSHES) return;

        // Where farmers can walk along the border (trapdoors, carpet, slabs),
        // the rows may run right up to it; elsewhere a free strip stays.
        NavGraph graph = village.getGraph();
        Set<BlockPos> walkableEdge = new HashSet<>();
        if (graph != null) {
            for (BlockPos cell : ground.edges()) {
                if (isStand(graph, cell) || isStand(graph, cell.below()) || isStand(graph, cell.above())) {
                    walkableEdge.add(cell);
                }
            }
        }

        Set<BlockPos> nearRoute = new HashSet<>();
        for (BlockPos hazard : ground.routes()) {
            for (int dx = -ROUTE_CLEARANCE; dx <= ROUTE_CLEARANCE; dx++) {
                for (int dz = -ROUTE_CLEARANCE; dz <= ROUTE_CLEARANCE; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos cell = hazard.offset(dx, dy, dz);
                        if (patch.region.contains(cell)) nearRoute.add(cell);
                    }
                }
            }
        }
        RowPattern rows = RowPattern.choose(patch.plants, patch.region);
        // The region is in flood order from the first bush, so new rows grow
        // outward from what is already there.
        for (BlockPos cell : patch.region) {
            if (patch.plantable.size() >= MAX_PLANTABLE) break;
            if (patch.plants.contains(cell) || !rows.isBushRow(cell) || nearRoute.contains(cell)
                    || !isInterior(cell, patch.region, walkableEdge)
                    || !keepsBushesReachable(cell, patch.region, patch.plants)
                    || !canHoldNewBush(level, cell, patch.definition)
                    || isProtected(village, cell)) continue;
            patch.plantable.add(cell.immutable());
        }
    }

    /** Another berry patch whose bushes turned out to share this ground merges in. */
    private void absorbOverlappingPatches(Patch patch) {
        Iterator<Patch> others = patches.values().iterator();
        while (others.hasNext()) {
            Patch other = others.next();
            if (other == patch || other.kind != Kind.BERRY) continue;
            for (BlockPos bush : other.plants) {
                if (patch.region.contains(bush)) {
                    others.remove();
                    dirty.remove(other.id);
                    break;
                }
            }
        }
    }

    /**
     * The open ground of a patch, every cell that ends it along the way
     * (fence, trapdoor, carpet, path...), and which of those are paths and doors.
     */
    private record BerryGround(Set<BlockPos> cells, Set<BlockPos> edges, List<BlockPos> routes) {}

    /**
     * The ground at {@code start}'s height that bushes could stand on,
     * flood-filled through open soil only, nearest first. Any other block
     * ends it, slabs and trapdoors included. Stops at the village border, at
     * unloaded chunks and at {@link #BERRY_REGION_CAP} tiles; past those the
     * edge simply counts as the patch's edge.
     */
    private static BerryGround floodGround(ServerLevel level, BlockPos start, CropDefinition definition,
                                           Predicate<BlockPos> inBounds) {
        Set<BlockPos> region = new LinkedHashSet<>();
        Set<BlockPos> edge = new HashSet<>();
        List<BlockPos> routes = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        region.add(start.immutable());
        queue.add(start.immutable());
        while (!queue.isEmpty() && region.size() < BERRY_REGION_CAP) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = current.relative(direction);
                if (region.contains(next) || edge.contains(next)) continue;
                if (!level.hasChunkAt(next) || !inBounds.test(next)) continue;
                if (!isRegionCell(level, next, definition)) {
                    edge.add(next);
                    if (isRoute(level, next)) routes.add(next);
                    continue;
                }
                region.add(next);
                queue.addLast(next);
                if (region.size() >= BERRY_REGION_CAP) break;
            }
        }
        return new BerryGround(region, edge, routes);
    }

    /** A bush, or open air (or grass to replace) over berry soil. */
    private static boolean isRegionCell(ServerLevel level, BlockPos feet, CropDefinition definition) {
        BlockState state = level.getBlockState(feet);
        if (definition.matchesCropBlock(state.getBlock())) return true;
        if (!state.getFluidState().isEmpty() || !(state.isAir() || state.canBeReplaced())) return false;
        return definition.planting() instanceof CropPlantingStrategy.OnSoil onSoil
                && onSoil.matchesSoil(level.getBlockState(feet.below()).getBlock());
    }

    /** A cell beside the patch that people walk through: a dirt path (a step up or down too), a door or a gate. */
    private static boolean isRoute(ServerLevel level, BlockPos feet) {
        Block here = level.getBlockState(feet).getBlock();
        if (here instanceof DoorBlock || here instanceof FenceGateBlock || here == Blocks.DIRT_PATH) return true;
        return level.getBlockState(feet.below()).is(Blocks.DIRT_PATH)
                || level.getBlockState(feet.below(2)).is(Blocks.DIRT_PATH);
    }

    /**
     * All four sides inside the patch or on a border farmers can walk along.
     * Beside a fence, wall or water the edge stays a free walkway, or the
     * paths between the rows would be dead ends nobody could get into.
     */
    private static boolean isInterior(BlockPos cell, Set<BlockPos> region, Set<BlockPos> walkableEdge) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = cell.relative(direction);
            if (!region.contains(side) && !walkableEdge.contains(side)) return false;
        }
        return true;
    }

    private static boolean canHoldNewBush(ServerLevel level, BlockPos cell, CropDefinition definition) {
        BlockState state = level.getBlockState(cell);
        return state.getFluidState().isEmpty()
                && (state.isAir() || state.canBeReplaced())
                && !definition.matchesCropBlock(state.getBlock())
                && definition.planting() instanceof CropPlantingStrategy.OnSoil onSoil
                && onSoil.matchesSoil(level.getBlockState(cell.below()).getBlock());
    }

    private static boolean isProtected(VillageManager village, BlockPos feet) {
        for (Building building : village.getBuildings()) {
            if (building.isNearRoom(feet) || building.getFunctionalBlocks().contains(feet)
                    || building.getFunctionalBlocks().contains(feet.below())) return true;
        }
        return false;
    }

    /**
     * A bush at {@code cell} must leave itself and every bush beside it a
     * free side to be worked from. Bushes the player planted on a walkway row
     * can otherwise close a gap the row pattern counts on.
     */
    private static boolean keepsBushesReachable(BlockPos cell, Set<BlockPos> region, Set<BlockPos> bushes) {
        if (!hasFreeSide(cell, null, region, bushes)) return false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = cell.relative(direction);
            if (bushes.contains(neighbour) && !hasFreeSide(neighbour, cell, region, bushes)) return false;
        }
        return true;
    }

    private static boolean hasFreeSide(BlockPos bush, @Nullable BlockPos newBush,
                                       Set<BlockPos> region, Set<BlockPos> bushes) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = bush.relative(direction);
            if (side.equals(newBush)) continue;
            if (region.contains(side) && !bushes.contains(side)) return true;
        }
        return false;
    }

    /**
     * Bushes go on every other line, so each row has a walkway beside it.
     * The lines follow whatever the bushes already there line up with best;
     * with no clear lead they run along the patch's longer side.
     */
    private record RowPattern(boolean alongX, int parity) {

        /** {@code alongX}: rows run along X, so every bush shares one Z parity. */
        boolean isBushRow(BlockPos cell) {
            int coordinate = alongX ? cell.getZ() : cell.getX();
            return Math.floorMod(coordinate, 2) == parity;
        }

        static RowPattern choose(Set<BlockPos> bushes, Set<BlockPos> region) {
            int[] alongXCounts = new int[2];
            int[] alongZCounts = new int[2];
            for (BlockPos bush : bushes) {
                alongXCounts[Math.floorMod(bush.getZ(), 2)]++;
                alongZCounts[Math.floorMod(bush.getX(), 2)]++;
            }
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos cell : region) {
                minX = Math.min(minX, cell.getX()); maxX = Math.max(maxX, cell.getX());
                minZ = Math.min(minZ, cell.getZ()); maxZ = Math.max(maxZ, cell.getZ());
            }
            boolean longerAlongX = maxX - minX >= maxZ - minZ;
            int bestX = Math.max(alongXCounts[0], alongXCounts[1]);
            int bestZ = Math.max(alongZCounts[0], alongZCounts[1]);
            boolean alongX = bestX != bestZ ? bestX > bestZ : longerAlongX;
            int[] counts = alongX ? alongXCounts : alongZCounts;
            int parity = counts[1] > counts[0] ? 1 : 0;
            return new RowPattern(alongX, parity);
        }
    }

    // =========================================================================
    // Work
    // =========================================================================

    private static FarmerManager.JobType harvestJob(Kind kind) {
        return switch (kind) {
            case COCOA -> FarmerManager.JobType.HARVEST_POD;
            case BERRY -> FarmerManager.JobType.PICK_BERRIES;
            case GLOW_BERRY -> FarmerManager.JobType.PICK_GLOW_BERRIES;
        };
    }

    /**
     * The best perennial job for a farmer at {@code from}, or null.
     * {@code available}: the spot is not claimed by another farmer nor
     * waiting out a failed try. {@code mayPlantBerries}: the farmer still
     * carries berries it picked itself; berries from storage are food and are
     * never planted. {@code leftToOthers}: crops whose picking this farmer
     * leaves to the farmers set to only such crops. {@code workers}: other
     * farmers per patch id, so a crowded patch loses to a free one.
     */
    @Nullable
    Candidate bestCandidate(BlockPos from, FarmerSettings settings, InventoryManager inventory,
                            Predicate<BlockPos> available, boolean mayPlantBerries, Set<Kind> leftToOthers,
                            Map<UUID, Integer> workers) {
        Candidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Patch patch : patches.values()) {
            Item item = patch.definition.representativePlantingItem().orElse(null);
            if (item == null) continue;
            double crowd = FarmerWorkPolicy.crowdPenalty(workers.getOrDefault(patch.id, 0));
            if (mayHarvest(patch, item, settings, inventory, leftToOthers)) {
                for (BlockPos pos : patch.mature) {
                    if (!available.test(pos)) continue;
                    double score = 600 - crowd - Math.sqrt(pos.distSqr(from))
                            + (patch.full.contains(pos) ? FULL_BUSH_BONUS : 0);
                    if (score > bestScore) {
                        best = new Candidate(patch, harvestJob(patch.kind), pos, item, 600);
                        bestScore = score;
                    }
                }
            }
            if (mayPlant(patch, item, settings, inventory, mayPlantBerries)) {
                FarmerManager.JobType plant = patch.kind == Kind.COCOA
                        ? FarmerManager.JobType.PLANT_POD : FarmerManager.JobType.PLANT_BUSH;
                // Replanting a picked pod restores the farm; a new berry bush grows it.
                int priority = patch.kind == Kind.COCOA ? 400 : 300;
                for (BlockPos pos : patch.plantable) {
                    if (!available.test(pos)) continue;
                    double score = priority - crowd - Math.sqrt(pos.distSqr(from));
                    if (score > bestScore) {
                        best = new Candidate(patch, plant, pos, item, priority);
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Whether {@link #bestCandidate} would find anything, by the same rules.
     * Counting bushes another farmer had claimed sent a farmer to work that
     * found no job and quit, over and over.
     */
    boolean hasWork(FarmerSettings settings, InventoryManager inventory, Predicate<BlockPos> available,
                    boolean mayPlantBerries, Set<Kind> leftToOthers) {
        for (Patch patch : patches.values()) {
            Item item = patch.definition.representativePlantingItem().orElse(null);
            if (item == null) continue;
            if (mayHarvest(patch, item, settings, inventory, leftToOthers)) {
                for (BlockPos pos : patch.mature) if (available.test(pos)) return true;
            }
            if (mayPlant(patch, item, settings, inventory, mayPlantBerries)) {
                for (BlockPos pos : patch.plantable) if (available.test(pos)) return true;
            }
        }
        return false;
    }

    private static boolean mayHarvest(Patch patch, Item item, FarmerSettings settings,
                                      InventoryManager inventory, Set<Kind> leftToOthers) {
        return !settings.turnOffHarvesting() && !leftToOthers.contains(patch.kind)
                && settings.isPlantEnabled(item)
                && FarmerManager.hasHarvestTool(inventory, settings, patch.definition.harvestTool());
    }

    private static boolean mayPlant(Patch patch, Item item, FarmerSettings settings,
                                    InventoryManager inventory, boolean mayPlantBerries) {
        return patch.kind != Kind.GLOW_BERRY && (patch.kind != Kind.BERRY || mayPlantBerries)
                && settings.shouldPlant(item) && inventory.contains(item);
    }

    /**
     * Cocoa spots the farm keeps (a pod on them or not), less the ones no
     * farmer could reach. Saved with the farm, so a village restarted while
     * nobody was near still knows them.
     */
    int awayPodSpots() {
        int count = 0;
        for (BlockPos spot : podSpots.keySet()) if (!unreachablePods.contains(spot)) count++;
        return count;
    }

    /** The same spots with the log face each pod hangs from, for the new away calculator. A copy. */
    Map<BlockPos, Direction> awayPods() {
        Map<BlockPos, Direction> pods = new LinkedHashMap<>();
        podSpots.forEach((spot, facing) -> {
            if (!unreachablePods.contains(spot)) pods.put(spot, facing);
        });
        return pods;
    }

    /** Plants of this kind ready to pick, claimed or not. */
    int readyCount(Kind kind) {
        int count = 0;
        for (Patch patch : patches.values()) {
            if (patch.kind == kind) count += patch.mature.size();
        }
        return count;
    }

    /** Whether some berry patch has a free spot for a new bush. */
    boolean berrySpotsOpen() {
        for (Patch patch : patches.values()) {
            if (patch.kind == Kind.BERRY && !patch.plantable.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Whether a farmer can work anything of this crop in a known patch.
     * Pods and vine segments out of reach are remembered but not counted:
     * they gave every farmer a reason to take shears it could never use.
     */
    boolean grows(CropDefinition definition) {
        for (Patch patch : patches.values()) {
            if (patch.definition == definition && (!patch.mature.isEmpty() || !patch.growing.isEmpty()
                    || !patch.plantable.isEmpty())) return true;
        }
        return false;
    }

    /** Ripe plants waiting for {@code tool}. */
    int ripeNeeding(HarvestTool tool) {
        int count = 0;
        for (Patch patch : patches.values()) {
            if (patch.definition.harvestTool() == tool) count += patch.mature.size();
        }
        return count;
    }

    /**
     * Empty spots per planting item, for the farmer's seed fetching; at most
     * a stack each. Pod spots only: berries are food, so a farmer plants only
     * berries it picked itself and never fetches them from storage.
     */
    Map<Item, Integer> plantingNeed(FarmerSettings settings) {
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (Patch patch : patches.values()) {
            if (patch.kind != Kind.COCOA) continue;
            Item item = patch.definition.representativePlantingItem().orElse(null);
            if (item == null || !settings.shouldPlant(item) || patch.plantable.isEmpty()) continue;
            need.merge(item, patch.plantable.size(), (a, b) -> Math.min(64, a + b));
        }
        return need;
    }

    /** Growing plants bone meal can push along, with the patch kind for picking a stand. */
    Map<BlockPos, Kind> growing(FarmerSettings settings) {
        Map<BlockPos, Kind> result = new LinkedHashMap<>();
        for (Patch patch : patches.values()) {
            Item item = patch.definition.representativePlantingItem().orElse(null);
            if (item == null || !settings.isPlantEnabled(item)) continue;
            for (BlockPos pos : patch.growing) result.put(pos, patch.kind);
        }
        return result;
    }

    /**
     * Where to stand to work {@code target}. A pod or a vine is reached from
     * its own column or the cells around it, feet level with it or up to two
     * blocks lower; a bush only from beside it, never from inside it.
     */
    @Nullable
    static BlockPos standFor(ServerLevel level, @Nullable NavGraph graph, Kind kind,
                             BlockPos target, BlockPos from) {
        return standFor(level, graph, kind, target, from, pos -> true);
    }

    /** As above, using only spots {@code standFree} allows: none another farmer is working from. */
    @Nullable
    static BlockPos standFor(ServerLevel level, @Nullable NavGraph graph, Kind kind,
                             BlockPos target, BlockPos from, Predicate<BlockPos> standFree) {
        if (graph == null) return null;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        if (kind == Kind.BERRY) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = target.relative(direction);
                if (!isStand(graph, candidate) || !standFree.test(candidate)) continue;
                double score = candidate.distSqr(from);
                if (score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
            return best;
        }
        for (int dy = 0; dy >= -REACH_BELOW; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    // A pod has a body: not inside it, nor under it with the head in it.
                    // A vine has none, so its own column is the best place.
                    if (kind == Kind.COCOA && dx == 0 && dz == 0 && dy > -2) continue;
                    BlockPos candidate = target.offset(dx, dy, dz);
                    if (!isStand(graph, candidate) || !standFree.test(candidate)) continue;
                    double score = (dx * dx + dz * dz) * 4.0 - dy + candidate.distSqr(from) * 0.01;
                    if (score < bestScore) {
                        best = candidate;
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isStand(NavGraph graph, BlockPos feet) {
        NavNode node = graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
        return node != null && node.isRoutable() && node.degree() > 0;
    }

    /** Re-checks a perennial job against the live world right before it is worked. */
    boolean validate(ServerLevel level, FarmerManager.Job job) {
        BlockPos target = job.target();
        if (!level.hasChunkAt(target)) return false;
        BlockState state = level.getBlockState(target);
        return switch (job.type()) {
            case HARVEST_POD -> ripe(Kind.COCOA, state);
            case PICK_BERRIES -> ripe(Kind.BERRY, state);
            case PICK_GLOW_BERRIES -> ripe(Kind.GLOW_BERRY, state);
            case PLANT_POD -> {
                CropDefinition cocoa = definitionFor(Kind.COCOA);
                Direction facing = podSpots.get(target);
                yield cocoa != null && facing != null && state.isAir()
                        && isPodLog(level, target.relative(facing), cocoa);
            }
            case PLANT_BUSH -> canPlantBushNow(level, target);
            default -> false;
        };
    }

    private static boolean ripe(Kind kind, BlockState state) {
        CropDefinition definition = definitionFor(kind);
        return definition != null && pickable(kind, state, definition);
    }

    /**
     * Whether a plant of this kind can be picked now: a berry bush from
     * {@link #FIRST_PICK_AGE}, as a player can, anything else fully grown.
     */
    static boolean pickable(Kind kind, BlockState state, CropDefinition definition) {
        if (!definition.matchesCropBlock(state.getBlock())) return false;
        if (kind == Kind.BERRY && state.hasProperty(SweetBerryBushBlock.AGE)) {
            return state.getValue(SweetBerryBushBlock.AGE) >= FIRST_PICK_AGE;
        }
        return CropDefinitionRegistry.isMature(state, definition);
    }

    private static boolean fullBush(BlockState state) {
        return state.hasProperty(SweetBerryBushBlock.AGE)
                && state.getValue(SweetBerryBushBlock.AGE) >= SweetBerryBushBlock.MAX_AGE;
    }

    /**
     * The planting rules again, read live at the moment of planting: the
     * patch may have changed since its survey, and patches can be large.
     */
    private boolean canPlantBushNow(ServerLevel level, BlockPos target) {
        CropDefinition berries = definitionFor(Kind.BERRY);
        if (berries == null || !canHoldNewBush(level, target, berries) || isOccupied(level, target)) return false;
        Patch patch = null;
        for (Patch candidate : patches.values()) {
            if (candidate.kind == Kind.BERRY && candidate.plantable.contains(target)) {
                patch = candidate;
                break;
            }
        }
        if (patch == null || patch.plants.size() >= MAX_BUSHES) return false;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = -ROUTE_CLEARANCE; dx <= ROUTE_CLEARANCE; dx++) {
            for (int dz = -ROUTE_CLEARANCE; dz <= ROUTE_CLEARANCE; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    probe.set(target.getX() + dx, target.getY() + dy, target.getZ() + dz);
                    if (level.hasChunkAt(probe) && isRoute(level, probe)) return false;
                }
            }
        }
        // A free side for the new bush, and one left for every bush beside it.
        if (!liveFreeSide(level, target, null, berries)) return false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = target.relative(direction);
            if (berries.matchesCropBlock(level.getBlockState(neighbour).getBlock())
                    && !liveFreeSide(level, neighbour, target, berries)) return false;
        }
        return true;
    }

    private static boolean liveFreeSide(ServerLevel level, BlockPos bush, @Nullable BlockPos newBush,
                                        CropDefinition berries) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = bush.relative(direction);
            if (side.equals(newBush)) continue;
            if (isRegionCell(level, side, berries)
                    && !berries.matchesCropBlock(level.getBlockState(side).getBlock())) return true;
        }
        return false;
    }

    /** A bush planted on a villager or player would trap them in it. */
    public static boolean isOccupied(ServerLevel level, BlockPos cell) {
        return !level.getEntities((Entity) null, new AABB(cell),
                entity -> entity instanceof LivingEntity && entity.isAlive()).isEmpty();
    }

    /** The side of the log a remembered pod spot hangs on, or null. */
    @Nullable
    Direction podFacing(BlockPos spot) {
        return podSpots.get(spot);
    }

    boolean isTendedLog(BlockPos pos) {
        return tendedLogs.contains(pos);
    }

    // =========================================================================
    // Events
    // =========================================================================

    /** Returns whether a known patch covers {@code pos}. */
    boolean onBlockChanged(BlockPos pos) {
        boolean found = false;
        for (Patch patch : patches.values()) {
            if (!patch.near(pos, ROUTE_CLEARANCE + 1)) continue;
            dirty.add(patch.id);
            found = true;
        }
        return found;
    }

    /**
     * A pod, bush or vine grew a stage. Inside a known patch only its own
     * cell changes; outside every patch it is a new plant to discover.
     */
    boolean onGrow(BlockPos pos, BlockState state) {
        for (Patch patch : patches.values()) {
            if (!patch.near(pos, 0) || !patch.plants.contains(pos)) continue;
            if (!patch.definition.matchesCropBlock(state.getBlock())) {
                dirty.add(patch.id);
                return true;
            }
            // Glow berry segments and cocoa pods out of reach stay untracked.
            if ((patch.kind == Kind.GLOW_BERRY || patch.kind == Kind.COCOA)
                    && !patch.mature.contains(pos) && !patch.growing.contains(pos)) {
                return true;
            }
            patch.full.remove(pos);
            if (pickable(patch.kind, state, patch.definition)) {
                patch.growing.remove(pos);
                patch.mature.add(pos.immutable());
                if (patch.kind == Kind.BERRY && fullBush(state)) patch.full.add(pos.immutable());
            } else {
                patch.mature.remove(pos);
                patch.growing.add(pos.immutable());
            }
            return true;
        }
        return false;
    }

    /**
     * The worked cell is re-read on the spot. Re-surveying the whole patch
     * after every pick or planting meant flooding up to 4096 tiles about once
     * a second while a farmer worked a big patch.
     */
    void onWorked(ServerLevel level, BlockPos pos) {
        for (Patch patch : patches.values()) {
            if (!patch.near(pos, 0) || !patch.plants.contains(pos) && !patch.plantable.contains(pos)) continue;
            refreshCell(level, patch, pos.immutable());
            return;
        }
        // Not a cell of any patch (bone meal on a field crop): the old way.
        onBlockChanged(pos);
    }

    /**
     * A block was placed (it is in the world already). The patch's own crop
     * on one of its spots only updates that spot: a farmer planting a bush
     * fires this, and a full survey per bush was the cost. Anything else,
     * a fence, a path, a bush somewhere new, re-surveys as before.
     */
    boolean onBlockPlaced(ServerLevel level, BlockPos pos) {
        Patch owner = null;
        for (Patch patch : patches.values()) {
            if (patch.near(pos, 0) && (patch.plants.contains(pos) || patch.plantable.contains(pos))) {
                owner = patch;
                break;
            }
        }
        if (owner == null || !level.hasChunkAt(pos)
                || !owner.definition.matchesCropBlock(level.getBlockState(pos).getBlock())) {
            return onBlockChanged(pos);
        }
        refreshCell(level, owner, pos.immutable());
        // A patch close by still re-reads its edge, as before.
        for (Patch patch : patches.values()) {
            if (patch != owner && patch.near(pos, ROUTE_CLEARANCE + 1)) dirty.add(patch.id);
        }
        return true;
    }

    private void refreshCell(ServerLevel level, Patch patch, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        boolean tracked = patch.mature.remove(cell) | patch.growing.remove(cell);
        patch.full.remove(cell);
        if (patch.definition.matchesCropBlock(state.getBlock())) {
            patch.plants.add(cell);
            boolean planted = patch.plantable.remove(cell);
            // Pods and vine pieces out of reach stay untracked, as the survey leaves them.
            boolean workable = switch (patch.kind) {
                case BERRY -> true;
                case COCOA -> !unreachablePods.contains(cell);
                case GLOW_BERRY -> tracked;
            };
            if (workable) {
                if (pickable(patch.kind, state, patch.definition)) {
                    patch.mature.add(cell);
                    if (patch.kind == Kind.BERRY && fullBush(state)) patch.full.add(cell);
                } else {
                    patch.growing.add(cell);
                }
            }
            // Only a new bush changes its neighbours; a pick changes nothing
            // around it (and on a full patch must not set off a survey).
            if (planted && patch.kind == Kind.BERRY) {
                // Full: no spot left to offer, as the survey would leave it.
                if (patch.plants.size() >= MAX_BUSHES) {
                    patch.plantable.clear();
                    return;
                }
                // A new bush can take a neighbouring spot's last free side;
                // those spots wait for the next survey instead of failing.
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    patch.plantable.remove(cell.relative(direction));
                }
                // Every saved spot used up: survey once for the next batch.
                if (patch.plantable.isEmpty()) dirty.add(patch.id);
            }
            return;
        }
        patch.plants.remove(cell);
        // A cut pod leaves air on its remembered spot: replant it there.
        if (patch.kind == Kind.COCOA && state.isAir() && podSpots.containsKey(cell)) {
            patch.plants.add(cell);
            if (!unreachablePods.contains(cell)) patch.plantable.add(cell);
            return;
        }
        // Gone some other way: let the survey sort the patch out.
        dirty.add(patch.id);
    }

    void clear() {
        podSpots.clear();
        tendedLogs.clear();
        unreachablePods.clear();
        patches.clear();
        dirty.clear();
        inBounds = pos -> true;
        validationCursor = 0;
        nextValidationAt = Long.MIN_VALUE;
    }

    String describe() {
        int pods = 0, podsRipe = 0, bushes = 0, bushesRipe = 0, berrySpots = 0, full = 0, vines = 0, vinesRipe = 0;
        for (Patch patch : patches.values()) {
            switch (patch.kind) {
                case COCOA -> {
                    pods += patch.plants.size();
                    podsRipe += patch.mature.size();
                }
                case BERRY -> {
                    bushes += patch.plants.size();
                    bushesRipe += patch.mature.size();
                    berrySpots += patch.plantable.size();
                    if (patch.plants.size() >= MAX_BUSHES) full++;
                }
                case GLOW_BERRY -> {
                    vines += patch.mature.size() + patch.growing.size();
                    vinesRipe += patch.mature.size();
                }
            }
        }
        return "pods=" + pods + " (ripe=" + podsRipe + ") bushes=" + bushes + " (ripe=" + bushesRipe
                + " newSpots=" + berrySpots + " fullPatches=" + full + ") glowVines=" + vines
                + " (ripe=" + vinesRipe + ")";
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    private static final String TAG_POD_POS = "podPos";
    private static final String TAG_POD_FACING = "podFacing";

    /** Only the pod memory is saved; patches are rebuilt from the world. */
    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        long[] positions = new long[podSpots.size()];
        byte[] facings = new byte[podSpots.size()];
        int index = 0;
        for (Map.Entry<BlockPos, Direction> spot : podSpots.entrySet()) {
            positions[index] = spot.getKey().asLong();
            facings[index++] = (byte) spot.getValue().get3DDataValue();
        }
        tag.putLongArray(TAG_POD_POS, positions);
        tag.putByteArray(TAG_POD_FACING, facings);
        return tag;
    }

    void restore(CompoundTag tag) {
        clear();
        long[] positions = tag.getLongArray(TAG_POD_POS);
        byte[] facings = tag.getByteArray(TAG_POD_FACING);
        for (int i = 0; i < Math.min(positions.length, facings.length); i++) {
            Direction facing = Direction.from3DDataValue(facings[i]);
            if (facing.getAxis().isHorizontal()) podSpots.put(BlockPos.of(positions[i]), facing);
        }
        rebuildTendedLogs();
    }
}
