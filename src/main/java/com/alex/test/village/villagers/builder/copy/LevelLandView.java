package com.alex.test.village.villagers.builder.copy;

import com.alex.test.DynamicVillages;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The real world behind {@link LandView}. Blocks come through a {@link BuildingShapeScanner}
 * (never loads a chunk; {@code builtToday} is its today's rule), the bare land from the world
 * generator's {@code getBaseColumn}: the terrain noise alone for one column, without blending,
 * terrain fitting round structures, carvers, surface layers or features. One answer covers a
 * whole column and is kept for the life of this view (one copy or one check).
 */
final class LevelLandView implements LandView {

    /** How far down from a column's top block a trust sample looks for the natural surface. */
    private static final int SURFACE_DEPTH = 48;
    /** Trust samples: one column in this many each way. */
    static final int SAMPLE_STEP = 4;
    /** Trust samples: first out to this far past the building's box, then wider by {@link #SAMPLE_WIDEN}. */
    static final int SAMPLE_FIRST = BuildingCopier.OWN_REACH + 8;
    static final int SAMPLE_WIDEN = 8;
    static final int SAMPLE_WIDENINGS = 2;
    /** Trust samples: stop once this many are usable. */
    static final int MAX_USABLE = 40;

    private final ServerLevel level;
    private final BuildingShapeScanner scanner;
    private final BuildingShapeScanner.Area area;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final Long2ObjectOpenHashMap<NoiseColumn> columns = new Long2ObjectOpenHashMap<>();
    private final PlacedLand memory;
    private boolean generatorFailed;
    /** Time spent in the generator, and how many columns it answered. */
    long bareNanos;
    int bareColumns;

    LevelLandView(ServerLevel level, BuildingShapeScanner scanner, BuildingShapeScanner.Area area) {
        this.level = level;
        this.scanner = scanner;
        this.area = area;
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
        this.memory = PlacedLand.get(level);
    }

    static Group group(BlockState state) {
        return switch (NaturalBlocks.kind(state)) {
            case BUILT -> NaturalBlocks.isWorldIceLookalike(state) ? Group.HARD_ICE
                    : NaturalBlocks.isWorldRockLookalike(state) ? Group.LOOKALIKE : Group.BUILT;
            case LOG -> Group.LOG;
            case SANDSTONE -> Group.SANDSTONE;
            case NATURAL -> switch (NaturalBlocks.land(state)) {
                case ROCK -> Group.ROCK;
                case SOIL -> Group.SOIL;
                case FROZEN -> Group.FROZEN;
                case NONE -> Group.NEVER;
            };
        };
    }

    @Override
    public Group group(int x, int y, int z) {
        BlockState state = scanner.state(x, y, z);
        return state == null ? Group.UNLOADED : group(state);
    }

    @Override
    public boolean solid(int x, int y, int z) {
        BlockState state = scanner.state(x, y, z);
        return state != null && NaturalBlocks.isSolidGround(state);
    }

    @Override
    public Bare bare(int x, int y, int z) {
        NoiseColumn column = column(x, z);
        if (column == null) return Bare.UNKNOWN;
        BlockState state = column.getBlock(y);
        if (state.isAir()) return Bare.AIR;
        return state.getFluidState().isEmpty() ? Bare.SOLID : Bare.FLUID;
    }

    @Override
    public boolean builtToday(int x, int y, int z) {
        return scanner.isBuiltToday(x, y, z);
    }

    @Override
    public boolean insideFootprint(int x, int z) {
        return !area.outsideFootprint(x, z);
    }

    @Override
    public int topY() {
        return area.maxY();
    }

    @Override
    public boolean remembered(int x, int y, int z) {
        return memory.contains(BlockPos.asLong(x, y, z));
    }

    /** The highest row where the bare land is solid in this column, or {@link LandTrust#NONE}. */
    int bareTop(int x, int z) {
        NoiseColumn column = column(x, z);
        if (column == null) return LandTrust.NONE;
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            BlockState state = column.getBlock(y);
            if (!state.isAir() && state.getFluidState().isEmpty()) return y;
        }
        return LandTrust.NONE;
    }

    @Nullable
    private NoiseColumn column(int x, int z) {
        if (generatorFailed) return null;
        long key = BlockPos.asLong(x, 0, z);
        NoiseColumn column = columns.get(key);
        if (column != null) return column;
        long started = System.nanoTime();
        try {
            column = generator.getBaseColumn(x, z, level, randomState);
        } catch (RuntimeException e) {
            // A modded generator that can't answer: today's rule from here on, never a failed copy.
            DynamicVillages.LOGGER.warn("BUILDER copy: the world generator gave no bare land at {} {}: {}",
                    x, z, e.toString());
            generatorFailed = true;
            return null;
        }
        bareNanos += System.nanoTime() - started;
        bareColumns++;
        if (column == null) {
            generatorFailed = true;
            return null;
        }
        columns.put(key, column);
        return column;
    }

    // =========================================================================
    // Trust samples
    // =========================================================================

    /**
     * The natural surface of a column: walking down from its top block through air, plants, snow
     * layers, tree leaves and tree logs, the first block, when it is soil (grass, dirt, sand...).
     * Otherwise {@link LandTrust#NONE}: water, a roof or wall, rock, snow, an unloaded chunk.
     */
    int naturalSurface(int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return LandTrust.NONE;
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        for (int y = top; y > top - SURFACE_DEPTH && y >= level.getMinBuildHeight(); y--) {
            BlockState state = scanner.state(x, y, z);
            if (state == null || !state.getFluidState().isEmpty()) return LandTrust.NONE;
            Group group = group(state);
            if (group == Group.SOIL) return y;
            if (group == Group.NEVER && !NaturalBlocks.isSolidGround(state)) continue;
            if (group == Group.LOG && !scanner.isBuiltToday(x, y, z)) continue;
            return LandTrust.NONE;
        }
        return LandTrust.NONE;
    }

    /**
     * Trust samples round a box (x and z, inclusive): one column in {@link #SAMPLE_STEP} each way,
     * nearest the box first, outside it and outside every box in {@code skip} (other buildings).
     * Out to {@link #SAMPLE_FIRST} past the box; while fewer than {@code minUsable} are usable, wider
     * by {@link #SAMPLE_WIDEN} up to {@link #SAMPLE_WIDENINGS} times. At most {@link #MAX_USABLE} usable.
     */
    List<LandTrust.Sample> samples(int minX, int minZ, int maxX, int maxZ, List<int[]> skip, int minUsable) {
        int reach = SAMPLE_FIRST + SAMPLE_WIDEN * SAMPLE_WIDENINGS;
        List<int[]> candidates = new ArrayList<>();
        for (int x = minX - reach; x <= maxX + reach; x += SAMPLE_STEP) {
            for (int z = minZ - reach; z <= maxZ + reach; z += SAMPLE_STEP) {
                int distance = Math.max(Math.max(minX - x, x - maxX), Math.max(minZ - z, z - maxZ));
                if (distance <= 0 || inAny(skip, x, z)) continue;
                candidates.add(new int[] {distance, x, z});
            }
        }
        candidates.sort(Comparator.comparingInt(c -> c[0]));
        List<LandTrust.Sample> samples = new ArrayList<>();
        int usable = 0;
        int limit = SAMPLE_FIRST;
        columns:
        for (int[] candidate : candidates) {
            while (candidate[0] > limit) {
                if (usable >= minUsable || limit >= reach) break columns;
                limit += SAMPLE_WIDEN;
            }
            int surface = naturalSurface(candidate[1], candidate[2]);
            int bareTop = surface == LandTrust.NONE ? LandTrust.NONE : bareTop(candidate[1], candidate[2]);
            LandTrust.Sample sample = new LandTrust.Sample(candidate[1], candidate[2], surface, bareTop);
            samples.add(sample);
            if (sample.usable() && ++usable >= MAX_USABLE) break;
        }
        return samples;
    }

    private static boolean inAny(List<int[]> boxes, int x, int z) {
        for (int[] box : boxes) {
            if (x >= box[0] && x <= box[3] && z >= box[2] && z <= box[5]) return true;
        }
        return false;
    }

    /** Round 0: the cost of asking only the bare land's top height, for these columns (fresh answers). */
    double msPerTopHeight(List<LandTrust.Sample> samples, int most) {
        long nanos = 0;
        int asked = 0;
        for (LandTrust.Sample sample : samples) {
            if (asked >= most || generatorFailed) break;
            if (!sample.usable()) continue;
            long started = System.nanoTime();
            try {
                generator.getBaseHeight(sample.x(), sample.z(), Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
            } catch (RuntimeException e) {
                return -1;
            }
            nanos += System.nanoTime() - started;
            asked++;
        }
        return asked == 0 ? -1 : nanos / 1e6 / asked;
    }
}
