package com.alex.test.village.brain;

import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.villagers.miner.MineShaftPlan;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Down a mine: the Miner's place, and nowhere free time ends. A villager at
 * leisure used to pick a stroll spot deep in a tunnel, walk the length of the
 * shaft in the dark, and turn round and walk back out.
 *
 * <p>The tunnel area is the box the miner itself calls inside its mine
 * ({@code MinerActivity.insideMine}): past the mouth along the shaft, the
 * longest side tunnel any mine may have plus two either side (so a side tunnel
 * dug before the Mayor shortened them still counts), from 32 below the corridor
 * floor (vein holes, pits) to one above it. A spot in that box under open sky
 * is not down the mine: the hill over the shaft, or ground past a short outcrop
 * the shaft came out of.
 */
public final class MineGround {

    /** How far below the corridor floor vein holes and pits reach, as the miner counts it. */
    private static final int BELOW_FLOOR = 32;
    /** Sideways reach either side of the corridor. */
    private static final int HALF_WIDTH = MinerSettingsSchema.MAX_SIDE_BRANCH_DISTANCE + 2;

    private MineGround() {}

    /** A test for "this feet block is down one of the village's mines", for checking many spots in one go. */
    public static Predicate<BlockPos> test(VillageManager mgr) {
        List<MineBuilding> mines = new ArrayList<>();
        for (Building b : mgr.getBuildings()) {
            if (b instanceof MineBuilding mine && mine.isValid()) mines.add(mine);
        }
        if (mines.isEmpty()) return pos -> false;
        ServerLevel level = mgr.getLevel();
        return pos -> {
            for (MineBuilding mine : mines) {
                if (inTunnelBox(mine, pos)) return level == null || !level.canSeeSky(pos);
            }
            return false;
        };
    }

    /** Whether {@code feet} is down one of the village's mines. */
    public static boolean isDownAMine(VillageManager mgr, BlockPos feet) {
        return test(mgr).test(feet);
    }

    private static boolean inTunnelBox(MineBuilding mine, BlockPos pos) {
        Direction along = mine.shaftDirection();
        Direction side = along.getClockWise();
        BlockPos mouth = mine.mouth();
        int dx = pos.getX() - mouth.getX();
        int dz = pos.getZ() - mouth.getZ();
        int forward = dx * along.getStepX() + dz * along.getStepZ();
        int sideways = dx * side.getStepX() + dz * side.getStepZ();
        int dy = pos.getY() - mouth.getY();
        return forward >= 1 && forward <= MineShaftPlan.MAX_MAIN_LENGTH + 1
                && Math.abs(sideways) <= HALF_WIDTH
                && dy >= -BELOW_FLOOR && dy <= 1;
    }
}
