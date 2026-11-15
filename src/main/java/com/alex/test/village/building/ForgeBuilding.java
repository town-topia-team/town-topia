package com.alex.test.village.building;

import com.alex.test.village.NavGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Forge: a walled room with an anvil and a furnace. Created by hanging a Forge
 * token in an item frame beside the room's door.
 *
 * <p>The blacksmith works here: smelting raw ore at the furnace and crafting
 * tools at the anvil. {@code functionalBlocks} holds the anvils (the primary
 * work station); furnaces are tracked separately.
 */
public final class ForgeBuilding extends Building {

    public static final int REQUIRED_ANVILS = 1;
    public static final int REQUIRED_FURNACES = 1;

    private final Set<BlockPos> furnaceBlocks = new LinkedHashSet<>();
    /** Optional: where the smith does netherite upgrades. */
    private final Set<BlockPos> smithingTables = new LinkedHashSet<>();
    private final Set<BlockPos> blastFurnaces = new LinkedHashSet<>();

    public ForgeBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                         Direction interiorDirection, Set<BlockPos> initialNavMeshBlocks) {
        super(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks);
    }

    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        furnaceBlocks.clear();
        smithingTables.clear();
        blastFurnaces.clear();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 2; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isFunctionalBlockAccessibleFromRoom(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof AnvilBlock) {
                        functionalBlocks.add(pos);
                    } else if (state.getBlock() instanceof AbstractFurnaceBlock) {
                        furnaceBlocks.add(pos);
                        if (state.getBlock() instanceof BlastFurnaceBlock) blastFurnaces.add(pos);
                    } else if (state.getBlock() instanceof SmithingTableBlock) {
                        smithingTables.add(pos);
                    }
                }
            }
        }
        return functionalBlocks.size() >= REQUIRED_ANVILS
                && furnaceBlocks.size() >= REQUIRED_FURNACES;
    }

    @Nullable
    public BlockPos getAnvilPos() {
        return functionalBlocks.isEmpty() ? null : functionalBlocks.iterator().next();
    }

    @Nullable
    public BlockPos getFurnacePos() {
        return furnaceBlocks.isEmpty() ? null : furnaceBlocks.iterator().next();
    }

    /** A blast furnace when the forge has one (it smelts twice as fast), else any furnace. */
    @Nullable
    public BlockPos getBestFurnacePos() {
        return blastFurnaces.isEmpty() ? getFurnacePos() : blastFurnaces.iterator().next();
    }

    @Nullable
    public BlockPos getSmithingTablePos() {
        return smithingTables.isEmpty() ? null : smithingTables.iterator().next();
    }

    /**
     * The room tile to work {@code station} from, nearest to {@code from} first. At an anvil one of the two
     * tiles along its long top when either can be stood on (the drawing-out clip lays the bar along it; vanilla's
     * top runs along FACING's axis, so those tiles are on the other axis), else any side, as for other stations.
     */
    @Nullable
    public BlockPos getStandBeside(ServerLevel level, BlockPos station, NavGraph graph, BlockPos from) {
        BlockState state = level.getBlockState(station);
        if (state.getBlock() instanceof AnvilBlock) {
            Direction.Axis along = state.getValue(AnvilBlock.FACING).getAxis();
            BlockPos longSide = getWalkTargetBesideAny(Set.of(station), graph, from,
                    tile -> tile.getX() == station.getX() ? along == Direction.Axis.X : along == Direction.Axis.Z);
            if (longSide != null) return longSide;
        }
        return getWalkTargetBeside(station, graph, from);
    }

    /** Villagers inside can feel crowded; see {@link BuildingSpace}. */
    @Override
    public boolean countsCrowding() {
        return true;
    }
}
