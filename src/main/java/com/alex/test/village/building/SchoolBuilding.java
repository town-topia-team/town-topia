package com.alex.test.village.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * School: a walled room with a lectern the teacher teaches from. Created like
 * every room building -- School token in a frame beside a door.
 *
 * <p>Each lectern is one class with one teacher. Children sit at chairs that
 * face the lectern, or stand in rows ({@code SchoolSeats}). Intelligence gains
 * for pupils are handled by the teacher's activity; the building just says
 * where lessons happen.
 */
public final class SchoolBuilding extends Building {

    public static final int REQUIRED_LECTERNS = 1;

    public SchoolBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                          Direction interiorDirection, Set<BlockPos> initialNavMeshBlocks) {
        super(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks);
    }

    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 2; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isFunctionalBlockAccessibleFromRoom(pos)) continue;
                    if (level.getBlockState(pos).is(Blocks.LECTERN)) {
                        functionalBlocks.add(pos);
                    }
                }
            }
        }
        return functionalBlocks.size() >= REQUIRED_LECTERNS;
    }

    /** Where the teacher stands to hold a lesson. */
    @Nullable
    public BlockPos getLecternPos() {
        return functionalBlocks.isEmpty() ? null : functionalBlocks.iterator().next();
    }

    /** Every lectern in the room: each one is a class with a teacher of its own. */
    public List<BlockPos> getLecterns() {
        return List.copyOf(functionalBlocks);
    }

    /** Villagers inside can feel crowded; see {@link BuildingSpace}. */
    @Override
    public boolean countsCrowding() {
        return true;
    }
}
