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
 * Town Hall: created by placing a Town Hall item in an item frame near a door.
 *
 * <p>No functional-block requirements beyond having a valid walled room.
 * Validity is therefore determined solely by the room scan succeeding.
 */
public final class TownHallBuilding extends Building {

    public TownHallBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                             Direction interiorDirection,
                             Set<BlockPos> initialNavMeshBlocks) {
        super(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks);
    }

    /**
     * No extra requirements — any valid room with a door qualifies as a town hall.
     * Always returns {@code true}; {@code functionalBlocks} stays empty.
     */
    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
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
        return true; // Beds are cached for the Mayor but are not required.
    }


    private boolean isBedAccessibleFromRoom(ServerLevel level, BlockPos headPos) {
        if (isFunctionalBlockAccessibleFromRoom(headPos)) return true;

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

    public List<BlockPos> getBeds() {
        return new ArrayList<>(functionalBlocks);
    }

    /** Villagers inside can feel crowded; see {@link BuildingSpace}. */
    @Override
    public boolean countsCrowding() {
        return true;
    }
}
