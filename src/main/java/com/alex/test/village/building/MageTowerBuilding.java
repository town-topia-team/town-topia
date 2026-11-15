package com.alex.test.village.building;

import com.alex.test.village.NavGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mage Tower: the mages' own room, where they are hired, study off duty and
 * sleep. Created like every room building -- the Mage Tower token in a frame
 * beside a door.
 *
 * <p>Like every room, it registers on its room alone; the furniture is what
 * it is for. Mages study at its study stations (enchanting tables and
 * lecterns, one mage to each), with motes drifting from its bookshelves, and
 * learn faster than in a Library: the more bookshelves, the faster
 * ({@link #studyPay}). The scan reports it complete with a station, at least
 * {@value #REQUIRED_BOOKSHELVES} bookshelves and a focus crystal (an amethyst
 * block, cluster or bud), which glows while a mage studies.
 *
 * <p>Beds are optional, like a Barracks': up to {@value #CAPACITY} mages sleep
 * here, and only mages. Kept apart from the furniture, like the Barracks'
 * beds, so a bed never counts toward the tower being complete.
 */
public final class MageTowerBuilding extends Building {

    public static final String SAVED_TYPE = "mage_tower";
    public static final int REQUIRED_BOOKSHELVES = 3;
    /** Mages that may sleep here; extra beds stay free. */
    public static final int CAPACITY = 8;
    /** Bookshelves past which more add nothing to study speed (an enchanting table's 15). */
    public static final int MAX_USEFUL_BOOKSHELVES = 15;
    /** Study pay with {@value #MAX_USEFUL_BOOKSHELVES} bookshelves; the fewest allowed pay 1. */
    public static final float MAX_STUDY_PAY = 1.25f;

    private final Set<BlockPos> stations = new LinkedHashSet<>();
    private final Set<BlockPos> bookshelves = new LinkedHashSet<>();
    private final Set<BlockPos> crystals = new LinkedHashSet<>();
    private final Set<BlockPos> beds = new LinkedHashSet<>();

    public MageTowerBuilding(java.util.UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                             Direction interiorDirection, Set<BlockPos> initialNavMeshBlocks) {
        super(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks);
    }

    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        stations.clear();
        bookshelves.clear();
        crystals.clear();
        beds.clear();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 2; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof BedBlock) {
                        scanBed(level, pos, state);
                        continue;
                    }
                    if (!isFunctionalBlockAccessibleFromRoom(pos)) continue;
                    if (state.is(Blocks.ENCHANTING_TABLE) || state.is(Blocks.LECTERN)) {
                        stations.add(pos);
                        functionalBlocks.add(pos);
                    } else if (state.is(Blocks.BOOKSHELF)) {
                        bookshelves.add(pos);
                        functionalBlocks.add(pos);
                    } else if (isCrystal(state)) {
                        crystals.add(pos);
                        functionalBlocks.add(pos);
                    }
                }
            }
        }
        return !stations.isEmpty() && bookshelves.size() >= REQUIRED_BOOKSHELVES && !crystals.isEmpty();
    }

    /** A whole bed, either half reachable from the room: recorded by its head, as the Barracks does. */
    private void scanBed(ServerLevel level, BlockPos head, BlockState state) {
        if (state.getValue(BlockStateProperties.BED_PART) != BedPart.HEAD) return;
        BlockPos foot = head.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        BlockState footState = level.getBlockState(foot);
        if (!(footState.getBlock() instanceof BedBlock)
                || footState.getValue(BlockStateProperties.BED_PART) != BedPart.FOOT) return;
        if (isFunctionalBlockAccessibleFromRoom(head) || isFunctionalBlockAccessibleFromRoom(foot)) {
            beds.add(head.immutable());
        }
    }

    private static boolean isCrystal(BlockState state) {
        return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST)
                || state.is(Blocks.AMETHYST_CLUSTER) || state.is(Blocks.LARGE_AMETHYST_BUD)
                || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.SMALL_AMETHYST_BUD);
    }

    /** Enchanting tables and lecterns: where a mage stands to study. */
    public List<BlockPos> getStudyStations() {
        return List.copyOf(stations);
    }

    public List<BlockPos> getBookshelves() {
        return List.copyOf(bookshelves);
    }

    /** The focus crystals: amethyst blocks, clusters and buds. */
    public List<BlockPos> getCrystals() {
        return List.copyOf(crystals);
    }

    /** All beds found, in scan order; past {@link #CAPACITY} they stay free. */
    public List<BlockPos> getBeds() {
        return List.copyOf(beds);
    }

    /**
     * Study pay for {@code shelves} bookshelves: 1 with the fewest a tower needs,
     * rising evenly to {@value #MAX_STUDY_PAY} at {@value #MAX_USEFUL_BOOKSHELVES}.
     */
    public static float studyPay(int shelves) {
        int useful = Math.clamp(shelves, REQUIRED_BOOKSHELVES, MAX_USEFUL_BOOKSHELVES);
        return 1f + (MAX_STUDY_PAY - 1f) * (useful - REQUIRED_BOOKSHELVES)
                / (float) (MAX_USEFUL_BOOKSHELVES - REQUIRED_BOOKSHELVES);
    }

    /** This tower's study pay, from its bookshelves. */
    public float studyPay() {
        return studyPay(bookshelves.size());
    }

    @Nullable
    public BlockPos getWalkTargetBesideBed(ServerLevel level, BlockPos head, NavGraph graph, BlockPos from,
                                           java.util.function.Predicate<BlockPos> usable) {
        if (!beds.contains(head) || !level.isLoaded(head)) return null;
        BlockState state = level.getBlockState(head);
        if (!(state.getBlock() instanceof BedBlock)) return null;
        BlockPos foot = head.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        return getWalkTargetBesideAny(Set.of(head, foot), graph, from, usable);
    }

    /** Villagers inside can feel crowded; see {@link BuildingSpace}. */
    @Override
    public boolean countsCrowding() {
        return true;
    }
}
