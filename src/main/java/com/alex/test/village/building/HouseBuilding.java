package com.alex.test.village.building;

import com.alex.test.village.NavGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A house: created by placing a 2-, 4-, or 6-Bed House token in an item frame
 * near a door.
 *
 * <p>Beds are optional for registration. The token's capacity limits how many
 * discovered beds may be assigned to villagers; extra beds are ignored.
 * The building tracks whether each bed is occupied (a player sleeping in it).
 */
public final class HouseBuilding extends Building {

    private final int capacity;

    public HouseBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                         Direction interiorDirection,
                         Set<BlockPos> initialNavMeshBlocks,
                         int capacity) {
        super(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks);
        if (capacity != 2 && capacity != 4 && capacity != 6) {
            throw new IllegalArgumentException("House capacity must be 2, 4, or 6");
        }
        this.capacity = capacity;
    }

    // =========================================================================
    // Functional block scan
    // =========================================================================

    /**
     * Scans the room's expanded AABB for bed blocks.
     * Only the <em>head</em> half of each bed is recorded (to avoid double-counting).
     * Returns {@code true} when at least {@link #capacity} bed heads are found.
     */
    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        // Search 1 block beyond the nav-mesh bounds to catch beds
        // that sit against the wall rather than on a walkable tile
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 2; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof BedBlock
                            && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
                            && isBedAccessibleFromRoom(level, pos)) {
                        functionalBlocks.add(pos);
                    }
                }
            }
        }
        return functionalBlocks.size() >= capacity;
    }


    private boolean isBedAccessibleFromRoom(ServerLevel level, BlockPos headPos) {
        if (isFunctionalBlockAccessibleFromRoom(headPos)) return true;

        // The walkable tile may be beside the foot half rather than the head half.
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos other = headPos.relative(dir);
            BlockState otherState = level.getBlockState(other);
            if (otherState.getBlock() instanceof BedBlock
                    && otherState.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                    && isFunctionalBlockAccessibleFromRoom(other)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Bed access
    // =========================================================================


    @Nullable
    public BlockPos getWalkTargetBesideBed(ServerLevel level, BlockPos headPos, NavGraph graph, BlockPos from,
                                           java.util.function.Predicate<BlockPos> usable) {
        LinkedHashSet<BlockPos> bedBlocks = new LinkedHashSet<>();
        bedBlocks.add(headPos);

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos other = headPos.relative(dir);
            BlockState otherState = level.getBlockState(other);
            if (otherState.getBlock() instanceof BedBlock
                    && otherState.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
                bedBlocks.add(other);
                break;
            }
        }

        return getWalkTargetBesideAny(bedBlocks, graph, from, usable);
    }

    /**
     * Returns the positions of all bed heads found in the room.
     * The list is in insertion order from the most recent scan.
     */
    public List<BlockPos> getBeds() {
        List<BlockPos> beds = new ArrayList<>(functionalBlocks);
        return beds.size() <= capacity ? beds : new ArrayList<>(beds.subList(0, capacity));
    }

    /**
     * Every bed head in the room, past the token's capacity too. Claims are
     * permanent, so which beds count is decided by who holds them, not by
     * scan order: once {@link #getCapacity} beds are claimed the rest stay free.
     */
    public List<BlockPos> getAllBeds() {
        return new ArrayList<>(functionalBlocks);
    }

    /** Maximum number of villagers this token allows the house to accommodate. */
    public int getCapacity() {
        return capacity;
    }

    /** Stable saved-data and item id suffix for this house tier. */
    public String getSavedBuildingType() {
        return capacity + "_bed_house";
    }

    @Override
    public String getDisplayName() {
        return capacity + " Bed House";
    }

    /**
     * Returns whether the bed at {@code index} is currently occupied.
     */
    public boolean isBedOccupied(ServerLevel level, int index) {
        List<BlockPos> beds = getBeds();
        if (index < 0 || index >= beds.size()) return false;
        BlockState state = level.getBlockState(beds.get(index));
        if (!(state.getBlock() instanceof BedBlock)) return false;
        return state.getValue(BlockStateProperties.OCCUPIED);
    }

    /** Villagers inside can feel crowded; see {@link BuildingSpace}. */
    @Override
    public boolean countsCrowding() {
        return true;
    }

    @Override
    public int livingBeds() {
        return getBeds().size();
    }
}
