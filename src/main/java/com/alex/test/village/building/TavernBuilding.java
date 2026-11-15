package com.alex.test.village.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tavern: a walled room with at least one note block (a jukebox also counts).
 * Created like every room building -- Tavern token in a frame beside a door.
 *
 * <p>The bard may pass through while playing, and villagers spending leisure
 * here can hear its music. {@code functionalBlocks} holds the room's instruments.
 */
public final class TavernBuilding extends Building {

    public static final int REQUIRED_INSTRUMENTS = 1;

    public TavernBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
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
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.NOTE_BLOCK) || state.is(Blocks.JUKEBOX)) {
                        functionalBlocks.add(pos);
                    }
                }
            }
        }
        return functionalBlocks.size() >= REQUIRED_INSTRUMENTS;
    }

    /** Where the bard stands to perform. */
    @Nullable
    public BlockPos getStagePos() {
        return functionalBlocks.isEmpty() ? null : functionalBlocks.iterator().next();
    }

    /**
     * The beds in this tavern room, by head position. A tavern built into a
     * house can reach the house's bedrooms too; {@code TravelerCamp} leaves
     * those to the villagers and offers guests the rest.
     */
    public List<BlockPos> guestBeds(ServerLevel level) {
        List<BlockPos> beds = new ArrayList<>();
        if (!isValid()) return beds;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD
                            && holdsGuestBed(pos)) {
                        beds.add(pos);
                    }
                }
            }
        }
        return beds;
    }

    /** {@code pos} (either half of a bed) lies inside this tavern room. */
    public boolean holdsGuestBed(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ && isFunctionalBlockAccessibleFromRoom(pos);
    }

    /** A chest or barrel inside the room, for what guests leave in the morning; null if none. */
    @Nullable
    public BlockPos containerInside(ServerLevel level) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    // A chest or barrel only: a furnace or brewing stand is a
                    // container too, and would take emeralds into its slots.
                    var blockEntity = level.getBlockEntity(pos);
                    if (blockEntity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                            || blockEntity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }
}
