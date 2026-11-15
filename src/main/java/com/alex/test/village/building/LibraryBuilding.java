package com.alex.test.village.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Library: a walled room lined with bookshelves, optionally holding an
 * enchanting table. Created like every room building -- Library token in a
 * frame beside a door.
 *
 * <p>The enchanter works here. Validity needs only the bookshelves so a young
 * village can raise the building before it can afford an enchanting table; the
 * enchanter simply has no station until one appears, and reports that rather
 * than invalidating the room.
 */
public final class LibraryBuilding extends Building {

    public static final int REQUIRED_BOOKSHELVES = 3;

    private final Set<BlockPos> enchantingTables = new LinkedHashSet<>();

    public LibraryBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                           Direction interiorDirection, Set<BlockPos> initialNavMeshBlocks) {
        super(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks);
    }

    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        enchantingTables.clear();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 2; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isFunctionalBlockAccessibleFromRoom(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.BOOKSHELF)) {
                        functionalBlocks.add(pos);
                    } else if (state.is(Blocks.ENCHANTING_TABLE)) {
                        enchantingTables.add(pos);
                    }
                }
            }
        }
        return functionalBlocks.size() >= REQUIRED_BOOKSHELVES;
    }

    /** The enchanter's station, or null while the village cannot afford one. */
    @Nullable
    public BlockPos getEnchantingTablePos() {
        return enchantingTables.isEmpty() ? null : enchantingTables.iterator().next();
    }

    /** Every enchanting table in the room, so two enchanters can each work one. */
    public List<BlockPos> getEnchantingTables() {
        return List.copyOf(enchantingTables);
    }

    /** Bookshelves around the room: more of them let the enchanter write stronger books. */
    public int bookshelfCount() {
        return functionalBlocks.size();
    }

    /** The room's bookshelves, as of the last scan. */
    public List<BlockPos> getBookshelves() {
        return List.copyOf(functionalBlocks);
    }

    /** Villagers inside can feel crowded; see {@link BuildingSpace}. */
    @Override
    public boolean countsCrowding() {
        return true;
    }
}
