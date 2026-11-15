package com.alex.test.village.building;

import com.alex.test.village.NavGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.Set;
import java.util.UUID;

/**
 * Base for open-air, fence-bounded buildings (farms, ranches).
 *
 * <p>Reuses the whole {@link Building} contract with two substitutions: the
 * "door" is a fence gate, and the interior scan is the fence-bounded
 * {@link EnclosureScanner} instead of the walled-room BFS. Everything else —
 * AABB bounds, dirty-rebuild triggering, gate-break removal — falls out of the
 * base class unchanged, because it only ever reasons about {@code doorPos} and
 * the tile set.
 */
public abstract class EnclosureBuilding extends Building {

    protected EnclosureBuilding(UUID itemFrameId, BlockPos gatePos, BlockPos itemFramePos,
                                Direction interiorDirection,
                                Set<BlockPos> initialTiles) {
        super(itemFrameId, gatePos, itemFramePos, interiorDirection, initialTiles);
    }

    /** The fence gate villagers enter through — same position {@code getDoorPos} returns. */
    public final BlockPos getGatePos() {
        return getDoorPos();
    }

    @Override
    protected Set<BlockPos> scanRoomBlocks(ServerLevel level, NavGraph navGraph,
                                           java.util.Collection<BlockPos> rivalEntries) {
        // A pen is bounded by its own fence, so it cannot bleed into a
        // neighbour the way two roofed rooms sharing a doorway can. The rival
        // list is accepted for the shared signature and ignored.
        return EnclosureScanner.scan(level, getDoorPos(), getInteriorDirection());
    }
}
