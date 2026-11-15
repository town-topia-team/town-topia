package com.alex.test.village.villagers.farmer;

import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import com.alex.test.village.villagers.farmer.crops.CropHarvestStrategy;
import com.alex.test.village.villagers.farmer.crops.CropMaturity;
import com.alex.test.village.villagers.farmer.crops.CropPlantingStrategy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.common.FarmlandWaterManager;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the world around one {@link FarmSite} and classifies its tiles:
 * hydrated, tillable, tilled, planted, mature and blocked, plus stacked-crop
 * bases, the fruit slot reserved beside each stem, and the crop each empty
 * plot should grow.
 *
 * <p>Server-thread world reads only. Reservations, assignments and job
 * choice stay in {@link FarmerManager}, which also shares the crop and block
 * lookups at the top of this class for job validation.
 */
final class FarmSiteSurvey {

    static final Comparator<BlockPos> POSITION_ORDER = Comparator
            .comparingInt((BlockPos pos) -> pos.getX())
            .thenComparingInt(pos -> pos.getY())
            .thenComparingInt(pos -> pos.getZ());

    private FarmSiteSurvey() {}

    // =========================================================================
    // Crop and block lookups (shared with FarmerManager)
    // =========================================================================

    static FarmerPlanning.Point point(BlockPos pos) {
        return new FarmerPlanning.Point(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Whether {@code block} is the harvestable fruit of any registered attached-fruit crop. */
    static boolean isAnyFruitBlock(Block block) {
        return fruitDefinitionFor(block) != null;
    }

    /** The definition whose adjacent-fruit block matches, if any. */
    @Nullable
    static CropDefinition fruitDefinitionFor(Block block) {
        for (CropDefinition definition : CropDefinitionRegistry.all()) {
            if (definition.harvest() instanceof CropHarvestStrategy.AdjacentFruit adjacentFruit
                    && adjacentFruit.fruitBlock().matches(block)) return definition;
        }
        return null;
    }

    /**
     * A stem beside {@code fruit} is attached to it: a grown crop, not a
     * melon or pumpkin someone placed, or one a village generated. A
     * neighbour in an unloaded chunk cannot be read, so the fruit is kept
     * until it can; the job re-checks right before cutting.
     */
    static boolean isAttachedFruit(ServerLevel level, BlockPos fruit) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stem = fruit.relative(direction);
            if (!level.hasChunkAt(stem)) return true;
            BlockState state = level.getBlockState(stem);
            if (state.getBlock() instanceof AttachedStemBlock
                    && state.getValue(AttachedStemBlock.FACING) == direction.getOpposite()) return true;
        }
        return false;
    }

    /** The stacked-crop definition (near-water planting) that {@code block} represents, if any. */
    @Nullable
    static CropDefinition stackDefinitionForBlock(Block block) {
        CropDefinition definition = CropDefinitionRegistry.byCropBlock(block);
        return definition != null && definition.planting() instanceof CropPlantingStrategy.NearWater
                ? definition : null;
    }

    /** Sugar-cane-style spot check, generalized to any {@code NearWater} crop definition. */
    @Nullable
    static CropDefinition matchingStackDefinition(ServerLevel level, BlockPos feet) {
        if (!level.getBlockState(feet).isAir()) return null;
        BlockPos ground = feet.below();
        Block groundBlock = level.getBlockState(ground).getBlock();
        for (CropDefinition definition : CropDefinitionRegistry.all()) {
            if (!(definition.planting() instanceof CropPlantingStrategy.NearWater nearWater)) continue;
            if (!nearWater.matchesSoil(groundBlock)) continue;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (level.getFluidState(ground.relative(direction)).is(FluidTags.WATER)) return definition;
            }
        }
        return null;
    }

    static boolean isHorizontalNeighbour(BlockPos first, BlockPos second) {
        return first.getY() == second.getY()
                && Math.abs(first.getX() - second.getX()) + Math.abs(first.getZ() - second.getZ()) == 1;
    }

    /** Uses the exact mapped FarmBlock search volume and NeoForge hydration hook. */
    static boolean isActuallyHydrated(ServerLevel level, BlockPos farmlandPos) {
        return isActuallyHydrated(level, farmlandPos, null);
    }

    private static boolean isActuallyHydrated(ServerLevel level, BlockPos farmlandPos,
                                              @Nullable FluidGrid fluids) {
        BlockState farmland = Blocks.FARMLAND.defaultBlockState();
        BlockPos.MutableBlockPos water = new BlockPos.MutableBlockPos();
        for (int y = farmlandPos.getY(); y <= farmlandPos.getY() + 1; y++) {
            for (int z = farmlandPos.getZ() - 4; z <= farmlandPos.getZ() + 4; z++) {
                for (int x = farmlandPos.getX() - 4; x <= farmlandPos.getX() + 4; x++) {
                    water.set(x, y, z);
                    FluidState fluid = fluids == null ? level.getFluidState(water) : fluids.get(level, water);
                    // An empty fluid can never hydrate; skip the hook's call chain for it.
                    if (fluid.isEmpty()) continue;
                    if (farmland.canBeHydrated(level, farmlandPos, fluid, water)) return true;
                }
            }
        }
        return FarmlandWaterManager.hasBlockWaterTicket(level, farmlandPos);
    }

    /**
     * Fluid states of one survey's box, read lazily on first use so a survey
     * touches exactly the blocks it did with a map, without allocating a
     * {@code BlockPos} and hashing it for each of the 162 probes per tile.
     * Positions outside the box are read straight from the level.
     */
    private static final class FluidGrid {
        /** Beyond this a merged giant site falls back to direct reads. */
        private static final int MAX_CELLS = 1 << 18;

        private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
        @Nullable private final FluidState[] states;

        FluidGrid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = maxX - minX + 1;
            this.sizeY = maxY - minY + 1;
            this.sizeZ = maxZ - minZ + 1;
            long cells = (long) sizeX * sizeY * sizeZ;
            this.states = cells > 0 && cells <= MAX_CELLS ? new FluidState[(int) cells] : null;
        }

        FluidState get(ServerLevel level, BlockPos pos) {
            int x = pos.getX() - minX;
            int y = pos.getY() - minY;
            int z = pos.getZ() - minZ;
            if (states == null || x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
                return level.getFluidState(pos);
            }
            int index = (y * sizeZ + z) * sizeX + x;
            FluidState state = states[index];
            if (state == null) {
                state = level.getFluidState(pos);
                states[index] = state;
            }
            return state;
        }
    }

    // =========================================================================
    // Survey
    // =========================================================================

    /** Re-reads every tile around {@code site}'s anchors and refills its tile sets. */
    static void refreshSite(ServerLevel level, VillageManager village, FarmSite site, long now) {
        Map<BlockPos, CropDefinition> previousInferredCrop = new HashMap<>(site.inferredCrop);
        Map<BlockPos, BlockPos> previousFruitSpaces = new HashMap<>(site.fruitSpaces);
        site.hydration.clear();
        site.tillable.clear();
        site.tilled.clear();
        site.planted.clear();
        site.mature.clear();
        site.blocked.clear();
        site.fruitSpaces.clear();
        site.heldSoil.clear();
        site.inferredCrop.clear();
        // Any melon or pumpkin within two blocks of the field used to count,
        // decorations included; only fruit a stem grew is the farm's.
        site.fruits.removeIf(pos -> level.hasChunkAt(pos)
                && (!isAnyFruitBlock(level.getBlockState(pos).getBlock()) || !isAttachedFruit(level, pos)));
        site.stackBases.clear();
        site.stackPlantable.clear();

        int minX = site.anchors.stream().mapToInt(BlockPos::getX).min().orElse(0) - 1;
        int maxX = site.anchors.stream().mapToInt(BlockPos::getX).max().orElse(0) + 1;
        int minZ = site.anchors.stream().mapToInt(BlockPos::getZ).min().orElse(0) - 1;
        int maxZ = site.anchors.stream().mapToInt(BlockPos::getZ).max().orElse(0) + 1;
        int minSoilY = site.anchors.stream().mapToInt(pos -> pos.getY() - 1).min().orElse(0) - 1;
        int maxSoilY = site.anchors.stream().mapToInt(pos -> pos.getY() - 1).max().orElse(0) + 1;

        Set<BlockPos> layout = layoutCandidates(site);
        FluidGrid fluidStates = new FluidGrid(minX - 4, minSoilY, minZ - 4, maxX + 4, maxSoilY + 1, maxZ + 4);
        Set<BlockPos> candidateTillable = new LinkedHashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int soilY = minSoilY; soilY <= maxSoilY; soilY++) {
                    BlockPos soil = new BlockPos(x, soilY, z);
                    BlockPos feet = soil.above();
                    if (!layout.contains(feet)) continue;
                    if (!level.hasChunkAt(soil) || !isActuallyHydrated(level, soil, fluidStates)) continue;
                    site.hydration.add(feet);
                    classifyTile(level, village, site, feet, candidateTillable);
                }
            }
        }
        for (BlockPos anchor : site.anchors) {
            if (!level.hasChunkAt(anchor)) continue;
            // Hydrated anchors were classified in the bounded scan above.
            if (site.hydration.contains(anchor)) continue;
            classifyTile(level, village, site, anchor, candidateTillable);
        }
        site.everFarmland.removeIf(pos -> horizontalDistanceToSet(pos, site.anchors) > 2);
        // A dirt block only becomes tillable when an unbroken chain of valid
        // dirt/farmland tiles connects it back to a water source. Anything in
        // between that is not part of that walkable set - fence, path,
        // crop-less obstruction, whatever it is - blocks that particular
        // route without ruling out an alternate one around it.
        // A nearby crop field can share a discovered site with cane. Keep the
        // cane's water and immediate surroundings out of the ordinary field.
        // Water only claims soil on its own side of anything built between
        // them - fence, wall, slab, trapdoor, full block - so a field divided
        // from the cane is still tilled right up to the divider.
        Set<BlockPos> stackWater = new HashSet<>();
        for (BlockPos water : site.water) {
            for (BlockPos base : site.stackBases.keySet()) {
                if (isHorizontalNeighbour(water, base.below())) {
                    stackWater.add(water);
                    break;
                }
            }
        }
        Map<BlockPos, Set<BlockPos>> openFootprints = new HashMap<>();
        candidateTillable.removeIf(tile -> nearStackBase(tile, site.stackBases.keySet())
                || nearestWaterIsForStack(level, tile, site.water, stackWater, openFootprints));
        Set<BlockPos> fieldWater = new HashSet<>(site.water);
        fieldWater.removeAll(stackWater);
        site.tillable.addAll(reachableTillableTiles(level, site, candidateTillable, fieldWater));
        if (!site.stackBases.isEmpty()) {
            for (BlockPos feet : site.hydration) {
                CropDefinition match = matchingStackDefinition(level, feet);
                if (match != null) site.stackPlantable.put(feet.immutable(), match);
            }
            site.tillable.removeAll(site.stackPlantable.keySet());
        }
        Map<BlockPos, Integer> plotOf = plotAssignments(site.everFarmland);
        for (BlockPos tile : site.tilled) {
            CropDefinition previous = previousInferredCrop.get(tile);
            CropDefinition inferred = inferCrop(level, site, tile, previous, plotOf.get(tile), plotOf);
            // A just-harvested isolated crop leaves no blocks to vote. Keep its
            // last known intent until new planted evidence establishes a
            // different majority, rather than changing crop because the tile
            // happened to be observed between harvest and replanting.
            if (inferred != null) site.inferredCrop.put(tile, inferred);
            else if (previous != null) site.inferredCrop.put(tile, previous);
        }
        // Untilled soil bordering a plot is also given an intended crop, so
        // a pumpkin/melon plot can be laid out as stem/fruit pairs before
        // any of that soil is tilled. Only current evidence counts here: a
        // dirt tile never holds on to a stale intent.
        for (BlockPos tile : site.tillable) {
            Integer plot = plotOf.containsKey(tile) ? plotOf.get(tile) : adjacentPlot(tile, plotOf);
            if (plot == null) continue;
            CropDefinition inferred = inferCrop(level, site, tile,
                    previousInferredCrop.get(tile), plot, plotOf);
            if (inferred != null) site.inferredCrop.put(tile, inferred);
        }
        // A tile in an unloaded chunk was not re-read, so it gives no new
        // evidence. Keep its intent rather than forgetting the plot plan while
        // part of a farm is still loading (e.g. just after a reload).
        for (Map.Entry<BlockPos, CropDefinition> entry : previousInferredCrop.entrySet()) {
            if (!level.hasChunkAt(entry.getKey())) site.inferredCrop.putIfAbsent(entry.getKey(), entry.getValue());
        }
        // Fruit space is part of the plot plan, not just a transient planting
        // job. Plan pairs for empty stem tiles as well as already-planted
        // stems, and retain valid pairs across validation passes so another
        // farmer cannot fill a pumpkin/melon fruit slot in the meantime.
        assignFruitSpaces(level, site, previousFruitSpaces, plotOf);
        site.lastValidation = now;
    }

    /**
     * Re-reads the one tile a farmer just worked and moves it to the set it
     * now belongs in (harvested out of mature, tilled into tilled, planted out
     * of tilled, ...). Everything derived from the whole field - reachable
     * soil, plots, crop intent, fruit slots - is left for the next full
     * {@link #refreshSite}, which the caller schedules.
     */
    static void refreshTile(ServerLevel level, VillageManager village, FarmSite site, BlockPos feet) {
        if (!level.hasChunkAt(feet)) return;
        BlockPos tile = feet.immutable();
        boolean wasTillable = site.tillable.remove(tile);
        boolean wasStackPlantable = site.stackPlantable.remove(tile) != null;
        site.tilled.remove(tile);
        site.planted.remove(tile);
        site.mature.remove(tile);
        site.blocked.remove(tile);
        site.stackBases.remove(tile);
        if (!isAnyFruitBlock(level.getBlockState(tile).getBlock()) || !isAttachedFruit(level, tile)) {
            site.fruits.remove(tile);
        }
        // The full survey only classifies hydrated tiles and anchors; match it.
        if (!site.hydration.contains(tile) && !site.anchors.contains(tile)) return;

        Set<BlockPos> candidateTillable = new HashSet<>();
        classifyTile(level, village, site, tile, candidateTillable);
        // Reachability from water is a whole-field question; a tile keeps
        // its tillable status only if it had it and is still plain soil.
        if (wasTillable && candidateTillable.contains(tile)) site.tillable.add(tile);
        if (wasStackPlantable) {
            CropDefinition match = matchingStackDefinition(level, tile);
            if (match != null) site.stackPlantable.put(tile, match);
        }
    }

    /**
     * Groups every farmland tile into physically touching plots so crop
     * inference for one field never gets diluted or overridden by a
     * different, merely-nearby field that happens to share the same
     * hydrated farm site (e.g. two rows split by a path or a canal).
     */
    private static Map<BlockPos, Integer> plotAssignments(Set<BlockPos> farmland) {
        Map<FarmerPlanning.Point, BlockPos> byPoint = new HashMap<>();
        List<FarmerPlanning.Point> points = new ArrayList<>(farmland.size());
        for (BlockPos pos : farmland) {
            FarmerPlanning.Point converted = point(pos);
            byPoint.put(converted, pos);
            points.add(converted);
        }
        List<Set<FarmerPlanning.Point>> components = FarmerPlanning.tightlyConnectedComponents(points);
        Map<BlockPos, Integer> plotOf = new HashMap<>();
        for (int index = 0; index < components.size(); index++) {
            for (FarmerPlanning.Point point : components.get(index)) plotOf.put(byPoint.get(point), index);
        }
        return plotOf;
    }

    /** Reduces {@code candidateTillable} to the tiles reachable from a water source via walkable dirt/farmland. */
    private static Set<BlockPos> reachableTillableTiles(ServerLevel level, FarmSite site,
                                                         Set<BlockPos> candidateTillable,
                                                         Set<BlockPos> fieldWater) {
        if (candidateTillable.isEmpty() || fieldWater.isEmpty()) return Set.of();
        Map<FarmerPlanning.Point, BlockPos> byPoint = new HashMap<>();
        Set<FarmerPlanning.Point> walkable = new HashSet<>();
        for (BlockPos pos : candidateTillable) {
            FarmerPlanning.Point converted = point(pos);
            byPoint.put(converted, pos);
            walkable.add(converted);
        }
        // Old farmland is field ground only while it lies open. Carpet or a
        // trapdoor laid on it, or its soil swapped for another block, makes it
        // a border like any other: it used to let the field grow straight past
        // a border built on its own edge. Unloaded tiles were not re-read: kept.
        for (BlockPos pos : site.everFarmland) {
            if (!level.hasChunkAt(pos) || isOpenFieldGround(level, pos)) walkable.add(point(pos));
        }
        Set<FarmerPlanning.Point> waterPoints = new HashSet<>();
        for (BlockPos water : fieldWater) waterPoints.add(point(water));

        Set<FarmerPlanning.Point> reachable = FarmerPlanning.reachableFromWater(walkable, waterPoints);
        Set<BlockPos> result = new LinkedHashSet<>();
        for (Map.Entry<FarmerPlanning.Point, BlockPos> entry : byPoint.entrySet()) {
            if (reachable.contains(entry.getKey())) result.add(entry.getValue());
        }
        return result;
    }

    /** Farmland, dirt or grass with nothing on it but a crop, its fruit, or grass to clear. */
    private static boolean isOpenFieldGround(ServerLevel level, BlockPos feet) {
        BlockState soil = level.getBlockState(feet.below());
        if (!soil.is(Blocks.FARMLAND) && !soil.is(Blocks.DIRT) && !soil.is(Blocks.GRASS_BLOCK)) return false;
        BlockState cover = level.getBlockState(feet);
        Block block = cover.getBlock();
        return cover.isAir() || isClearableCover(cover)
                || CropDefinitionRegistry.byCropBlock(block) != null || isAnyFruitBlock(block);
    }

    /**
     * Short grass, ferns, tall grass, dead bushes and snow: what a block
     * placed there would simply replace. A hoe needs open air above the soil,
     * so a farmer clears these first, as a player would. They used to leave
     * grassy soil beside a field untilled for good, and snowy fields never
     * grew. Flowers stay: they count as a border like any other block.
     */
    static boolean isClearableCover(BlockState state) {
        if (state.isAir() || !state.canBeReplaced() || !state.getFluidState().isEmpty()) return false;
        return state.getBlock() instanceof BushBlock || state.is(Blocks.SNOW);
    }

    private static boolean nearStackBase(BlockPos tile, Set<BlockPos> bases) {
        for (BlockPos base : bases) {
            if (tile.getY() == base.getY()
                    && Math.abs(tile.getX() - base.getX()) <= 1
                    && Math.abs(tile.getZ() - base.getZ()) <= 1) return true;
        }
        return false;
    }

    /**
     * Assign overlapping hydration footprints to their nearest water source.
     * A source walled off from the tile by any block does not compete for it.
     */
    private static boolean nearestWaterIsForStack(ServerLevel level, BlockPos tile, Set<BlockPos> water,
                                                   Set<BlockPos> stackWater,
                                                   Map<BlockPos, Set<BlockPos>> openFootprints) {
        if (stackWater.isEmpty()) return false;
        int nearest = Integer.MAX_VALUE;
        boolean nearestIsForStack = false;
        for (BlockPos source : water) {
            if (!FarmerPlanning.withinVanillaHydration(point(tile.below()), point(source))) continue;
            int dx = tile.getX() - source.getX();
            int dz = tile.getZ() - source.getZ();
            int distance = dx * dx + dz * dz;
            if ((distance < nearest || distance == nearest && stackWater.contains(source))
                    && openFootprints.computeIfAbsent(source, key -> openFootprint(level, key))
                            .contains(new BlockPos(tile.getX(), source.getY(), tile.getZ()))) {
                nearest = distance;
                nearestIsForStack = stackWater.contains(source);
            }
        }
        return nearestIsForStack;
    }

    /**
     * Columns of {@code water}'s 9x9 hydration footprint reachable from it
     * without crossing a divider, keyed at the water's own Y. Straight steps
     * only, so two diagonal blocks still close a corner. An unloaded column
     * is not read and never blocks.
     */
    private static Set<BlockPos> openFootprint(ServerLevel level, BlockPos water) {
        Set<BlockPos> reached = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        reached.add(water.immutable());
        queue.add(water.immutable());
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = current.relative(direction);
                if (Math.abs(next.getX() - water.getX()) > 4 || Math.abs(next.getZ() - water.getZ()) > 4
                        || reached.contains(next)) continue;
                if (level.hasChunkAt(next) && isDividerColumn(level, next)) continue;
                reached.add(next);
                queue.addLast(next);
            }
        }
        return reached;
    }

    /**
     * Anything solid standing on the water's ground level divides the
     * footprint: fence, wall, slab, trapdoor, full block, raised ground.
     * Crops, cane and fruit are the farm itself, and a gate counts even
     * while open. At the water's own level (the soil of a flat field) only
     * a fence, wall or gate can be told apart from ground.
     */
    private static boolean isDividerColumn(ServerLevel level, BlockPos column) {
        if (isFenceLike(level.getBlockState(column).getBlock())) return true;
        BlockPos above = column.above();
        BlockState state = level.getBlockState(above);
        Block block = state.getBlock();
        if (isFenceLike(block)) return true;
        if (CropDefinitionRegistry.byCropBlock(block) != null || isAnyFruitBlock(block)) return false;
        return !state.getCollisionShape(level, above).isEmpty();
    }

    private static boolean isFenceLike(Block block) {
        return block instanceof FenceBlock || block instanceof WallBlock || block instanceof FenceGateBlock;
    }

    /** Exact membership of the old anchor-distance rule, built once per survey. */
    private static Set<BlockPos> layoutCandidates(FarmSite site) {
        Set<BlockPos> candidates = new HashSet<>(site.everFarmland);
        for (BlockPos anchor : site.anchors) {
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        candidates.add(anchor.offset(x, y, z));
                    }
                }
            }
        }
        return candidates;
    }

    private static int horizontalDistanceToSet(BlockPos pos, Collection<BlockPos> others) {
        int best = Integer.MAX_VALUE;
        for (BlockPos other : others) {
            if (Math.abs(pos.getY() - other.getY()) > 1) continue;
            best = Math.min(best, Math.max(Math.abs(pos.getX() - other.getX()),
                    Math.abs(pos.getZ() - other.getZ())));
        }
        return best;
    }

    private static void classifyTile(ServerLevel level, VillageManager village, FarmSite site, BlockPos feet,
                                     Set<BlockPos> candidateTillable) {
        BlockPos soil = feet.below();
        BlockState ground = level.getBlockState(soil);
        BlockState cropState = level.getBlockState(feet);
        CropDefinition stackDefinition = stackDefinitionForBlock(cropState.getBlock());
        if (stackDefinition != null) {
            site.planted.add(feet.immutable());
            if (level.getBlockState(feet.below()).getBlock() != cropState.getBlock()) {
                site.stackBases.put(feet.immutable(), stackDefinition);
                if (CropDefinitionRegistry.isMature(level, feet, cropState, stackDefinition)) {
                    site.mature.add(feet.immutable());
                }
            }
            return;
        }
        if (ground.is(Blocks.FARMLAND)) {
            if (!site.cropField) {
                site.blocked.add(feet.immutable());
                return;
            }
            site.everFarmland.add(feet.immutable());
            CropDefinition planted = CropDefinitionRegistry.byCropBlock(cropState.getBlock());
            if (cropState.isAir()) site.tilled.add(feet.immutable());
            // A berry bush on farmland is the berry patch's, never a field crop.
            else if (planted != null && !planted.planting().perennial()) {
                site.planted.add(feet.immutable());
                if (CropDefinitionRegistry.isMature(level, feet, cropState, planted)) {
                    site.mature.add(feet.immutable());
                }
            } else site.blocked.add(feet.immutable());
            return;
        }
        if (site.cropField && site.hydration.contains(feet)
                && (ground.is(Blocks.DIRT) || ground.is(Blocks.GRASS_BLOCK))
                && (cropState.isAir() || isClearableCover(cropState)) && !isProtected(village, feet, site)) {
            candidateTillable.add(feet.immutable());
        } else if (site.anchors.contains(feet)) {
            site.blocked.add(feet.immutable());
        }
    }

    private static boolean isProtected(VillageManager village, BlockPos feet, FarmSite site) {
        // Existing/reverted farmland is allowed to recover. New dirt inside any
        // registered room, road-adjacent workspace or functional block remains untouched.
        if (site.everFarmland.contains(feet)) return false;
        for (Building building : village.getBuildings()) {
            // Generic migration bridge: an old agricultural enclosure exposes
            // its soil through the shared functional-block API. Farmers do not
            // require, select, navigate to, or otherwise depend on that marker,
            // but existing save fields remain safe to maintain.
            if (building.getFunctionalBlocks().contains(feet)) return false;
            if (building.isNearRoom(feet) || building.getFunctionalBlocks().contains(feet)
                    || building.getFunctionalBlocks().contains(feet.below())) return true;
        }
        return false;
    }

    // =========================================================================
    // Crop inference and fruit slots
    // =========================================================================

    /**
     * Weighs valid planted crops in the local 4x4 sample to infer what belongs
     * on an empty tile. Only crops sharing {@code empty}'s
     * own plot (its physically touching patch of farmland, from
     * {@code plotOf}) are ever counted - a merely nearby field that happens
     * to share the same hydrated farm site, but is split off by so much as a
     * path or canal, never bleeds its crop into this one. Returns
     * {@code null} when no neighbour gives any signal, rather than silently
     * defaulting - callers decide how to handle a genuinely blank plot
     * themselves. On a tie, {@code previous} (this tile's own last inferred
     * crop, if any) wins so the choice does not flap between equally-weighted
     * crops on every validation pass.
     */
    @Nullable
    private static CropDefinition inferCrop(ServerLevel level, FarmSite site, BlockPos empty,
                                            @Nullable CropDefinition previous,
                                            @Nullable Integer plot,
                                            Map<BlockPos, Integer> plotOf) {
        List<FarmerPlanning.CropSample> samples = new ArrayList<>();
        for (BlockPos planted : site.planted) {
            if (plot != null && !plot.equals(plotOf.get(planted))) continue;
            int dx = planted.getX() - empty.getX();
            int dz = planted.getZ() - empty.getZ();
            if (dx < -2 || dx > 1 || dz < -2 || dz > 1) continue;
            BlockState state = level.getBlockState(planted);
            CropDefinition definition = CropDefinitionRegistry.byCropBlock(state.getBlock());
            if (definition == null || !(definition.planting() instanceof CropPlantingStrategy.Farmland)
                    || !level.getBlockState(planted.below()).is(Blocks.FARMLAND)) continue;
            samples.add(new FarmerPlanning.CropSample(definition.id().toString(), dx, dz));
        }
        String previousId = previous == null ? null : previous.id().toString();
        String selectedId = FarmerPlanning.weightedCropChoice(samples, previousId).orElse(null);
        if (selectedId == null) return null;
        for (CropDefinition definition : CropDefinitionRegistry.all()) {
            if (definition.id().toString().equals(selectedId)) return definition;
        }
        return null;
    }

    /** The plot a soil tile would join once tilled: that of a horizontally touching farmland tile. */
    @Nullable
    private static Integer adjacentPlot(BlockPos tile, Map<BlockPos, Integer> plotOf) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Integer plot = plotOf.get(tile.relative(direction));
            if (plot != null) return plot;
        }
        return null;
    }

    private static List<BlockPos> plannedStemTiles(FarmSite site, Collection<BlockPos> tiles) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos tile : tiles) {
            CropDefinition definition = site.inferredCrop.get(tile);
            if (definition != null && definition.maturity() instanceof CropMaturity.AttachedFruit) {
                result.add(tile);
            }
        }
        result.sort(POSITION_ORDER);
        return result;
    }

    private static void assignFruitSpaces(ServerLevel level, FarmSite site,
                                          Map<BlockPos, BlockPos> previous,
                                          Map<BlockPos, Integer> plotOf) {
        Set<BlockPos> claimed = new HashSet<>();
        Set<BlockPos> reservedStems = new HashSet<>();
        List<BlockPos> stems = new ArrayList<>();
        for (BlockPos pos : site.planted) {
            CropDefinition definition = CropDefinitionRegistry.byCropBlock(level.getBlockState(pos).getBlock());
            if (definition != null && definition.maturity() instanceof CropMaturity.AttachedFruit) stems.add(pos);
        }
        stems.sort(POSITION_ORDER);

        // A pair reaching into an unloaded chunk cannot be re-checked yet;
        // keep it as planned so no loaded stem claims its slot meanwhile.
        for (Map.Entry<BlockPos, BlockPos> pair : previous.entrySet()) {
            if (!level.hasChunkAt(pair.getKey()) || !level.hasChunkAt(pair.getValue())) {
                reserveFruitPair(site, pair.getKey(), pair.getValue(), claimed, reservedStems);
            }
        }

        // Bind live fruit first. An existing pumpkin/melon is authoritative
        // and must not lose its stem association merely because a previously
        // planned empty slot sorts earlier.
        for (BlockPos stem : stems) {
            CropDefinition definition = CropDefinitionRegistry.byCropBlock(level.getBlockState(stem).getBlock());
            if (definition == null
                    || !(definition.harvest() instanceof CropHarvestStrategy.AdjacentFruit adjacentFruit)) continue;
            BlockPos chosen = matchingFruit(level, stem, adjacentFruit, claimed);
            if (chosen != null) {
                site.fruits.add(chosen.immutable());
                reserveFruitPair(site, stem, chosen, claimed, reservedStems);
            }
        }

        // Every live stem without fruit gets a stable empty fruit slot.
        for (BlockPos stem : stems) {
            if (site.fruitSpaces.containsKey(stem)) continue;
            BlockPos chosen = chooseFruitSpace(level, site, stem, previous.get(stem),
                    claimed, reservedStems, plotOf);
            if (chosen != null) reserveFruitPair(site, stem, chosen, claimed, reservedStems);
        }

        // Then lay out future stems in inferred pumpkin/melon plots. Because
        // fruit slots are stored in site.fruitSpaces immediately, the normal
        // plant and till planners treat them as protected before any stem is
        // actually placed.
        for (BlockPos stem : plannedStemTiles(site, site.tilled)) {
            if (claimed.contains(stem) || reservedStems.contains(stem)) continue;
            BlockPos chosen = chooseFruitSpace(level, site, stem, previous.get(stem),
                    claimed, reservedStems, plotOf);
            if (chosen != null) reserveFruitPair(site, stem, chosen, claimed, reservedStems);
        }

        // Finally pair up the untilled soil bordering the plot, before the
        // till planner reaches it. Tilling soil one tile at a time and only
        // then looking for a fruit slot is what used to leave bare farmland
        // in front of stems: by the time a freshly tilled tile looked, its
        // soil neighbours were already farmland. A soil tile that cannot get
        // a partner of its own is held as soil too - tilled, it could only
        // ever become an unplantable stem tile.
        for (BlockPos stem : plannedStemTiles(site, site.tillable)) {
            if (claimed.contains(stem) || reservedStems.contains(stem)) continue;
            BlockPos chosen = chooseFruitSpace(level, site, stem, previous.get(stem),
                    claimed, reservedStems, plotOf);
            if (chosen != null) reserveFruitPair(site, stem, chosen, claimed, reservedStems);
            else site.heldSoil.add(stem.immutable());
        }
    }

    /**
     * The fruit {@code stem} actually grew: only an attached stem owns one,
     * the one it faces. A plain stem that merely touches a neighbour's fruit
     * used to take it as its own, so it never reserved an empty slot and a
     * new stem got planted into the space it still needed.
     */
    @Nullable
    private static BlockPos matchingFruit(ServerLevel level, BlockPos stem,
                                          CropHarvestStrategy.AdjacentFruit adjacentFruit,
                                          Set<BlockPos> claimed) {
        BlockState state = level.getBlockState(stem);
        if (!(state.getBlock() instanceof AttachedStemBlock)) return null;
        BlockPos fruit = stem.relative(state.getValue(AttachedStemBlock.FACING));
        if (claimed.contains(fruit)
                || !adjacentFruit.fruitBlock().matches(level.getBlockState(fruit).getBlock())) return null;
        return fruit.immutable();
    }

    /**
     * Picks the empty neighbour of {@code stem} its fruit should grow into.
     * {@code preferred} (the slot from the previous pass) is kept unless a
     * strictly better-ranked slot is now free, so a reservation does not
     * wander between equally good tiles as the field changes around it.
     */
    @Nullable
    static BlockPos chooseFruitSpace(ServerLevel level, FarmSite site, BlockPos stem,
                                     @Nullable BlockPos preferred, Set<BlockPos> claimed,
                                     Set<BlockPos> reservedStems,
                                     Map<BlockPos, Integer> plotOf) {
        BlockPos chosen = null;
        int bestRank = Integer.MAX_VALUE;
        // Only a stem still to be planted steps aside; a live one keeps the
        // best side it has.
        boolean planning = level.getBlockState(stem).isAir();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = stem.relative(direction);
            if (!isUsableFruitSpace(level, site, stem, candidate, claimed, reservedStems, plotOf, planning)) continue;
            int rank = fruitSpaceRank(level, site, candidate);
            if (rank < bestRank || rank == bestRank
                    && (chosen == null || POSITION_ORDER.compare(candidate, chosen) < 0)) {
                chosen = candidate;
                bestRank = rank;
            }
        }
        if (isUsableFruitSpace(level, site, stem, preferred, claimed, reservedStems, plotOf, planning)
                && fruitSpaceRank(level, site, preferred) <= bestRank) {
            return preferred.immutable();
        }
        return chosen == null ? null : chosen.immutable();
    }

    /**
     * Lower is better. Untilled soil inside the field is the ideal fruit
     * slot: reserving it is exactly what stops the till planner from turning
     * the spot a pumpkin/melon will grow into into farmland. Already-tilled
     * farmland in the plot is the fallback for a solid field that has no
     * soil left, and loose soil outside the field is the last resort so
     * fruit is not pushed onto surrounding paths while the plot has room.
     */
    private static int fruitSpaceRank(ServerLevel level, FarmSite site, BlockPos candidate) {
        boolean farmland = level.getBlockState(candidate.below()).is(Blocks.FARMLAND);
        if (!farmland && (site.hydration.contains(candidate) || site.everFarmland.contains(candidate))) {
            return 0;
        }
        return farmland ? 1 : 2;
    }

    private static boolean isUsableFruitSpace(ServerLevel level, FarmSite site, BlockPos stem,
                                               @Nullable BlockPos candidate,
                                               Set<BlockPos> claimed, Set<BlockPos> reservedStems,
                                               Map<BlockPos, Integer> plotOf, boolean planning) {
        if (candidate == null || !isHorizontalNeighbour(stem, candidate)
                || claimed.contains(candidate) || reservedStems.contains(candidate)
                || !level.getBlockState(candidate).isAir()) return false;
        Integer stemPlot = plotOf.get(stem);
        Integer candidatePlot = plotOf.get(candidate);
        if (stemPlot != null && candidatePlot != null && !stemPlot.equals(candidatePlot)) return false;
        BlockState below = level.getBlockState(candidate.below());
        if (!below.is(Blocks.DIRT) && !below.is(Blocks.GRASS_BLOCK) && !below.is(Blocks.FARMLAND)) return false;
        return !planning || !bordersUntrackedStem(level, site, stem, candidate);
    }

    /**
     * True when a stem this site does not track - on dry farmland outside
     * the hydrated survey, another farm's, a player's - also borders
     * {@code candidate}. Vanilla grows fruit into any free side, so that
     * older stem would take the slot and leave the new one without room.
     */
    private static boolean bordersUntrackedStem(ServerLevel level, FarmSite site, BlockPos stem,
                                                BlockPos candidate) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = candidate.relative(direction);
            if (neighbour.equals(stem) || site.planted.contains(neighbour)
                    || !level.hasChunkAt(neighbour)) continue;
            CropDefinition definition = CropDefinitionRegistry.byCropBlock(level.getBlockState(neighbour).getBlock());
            if (definition != null && definition.maturity() instanceof CropMaturity.AttachedFruit) return true;
        }
        return false;
    }

    private static void reserveFruitPair(FarmSite site, BlockPos stem, BlockPos fruit,
                                         Set<BlockPos> claimed, Set<BlockPos> reservedStems) {
        site.fruitSpaces.put(stem.immutable(), fruit.immutable());
        claimed.add(fruit.immutable());
        reservedStems.add(stem.immutable());
    }
}
