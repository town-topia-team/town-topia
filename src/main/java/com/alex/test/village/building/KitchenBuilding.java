package com.alex.test.village.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Kitchen: a walled room with something to cook on and a crafting table.
 * Created like every room building — Kitchen token in a frame beside a door.
 *
 * <p>The cook works here: baking bread at the crafting table and cooking raw
 * meat over the heat. {@code functionalBlocks} holds the heat sources; the
 * crafting tables are tracked separately, mirroring {@link StorageBuilding}.
 *
 * <p>Heat means a furnace, a blast furnace, a smoker <em>or</em> a campfire,
 * and any of them still validates the room. What each one is good for is the
 * cook's business ({@code CookPlanner}): a smoker cooks food twice as fast, a
 * campfire half as fast but without fuel, and a blast furnace, as in vanilla,
 * cooks no food at all.
 */
public final class KitchenBuilding extends Building implements KitchenStations {

    /** Heat sources needed: any mix of furnace, blast furnace, smoker, campfire. */
    public static final int REQUIRED_FURNACES = 1;
    public static final int REQUIRED_CRAFTING_TABLES = 1;

    /** The kinds of heat a kitchen can have. */
    public enum HeatSource { SMOKER, FURNACE, CAMPFIRE, BLAST_FURNACE }

    private final Set<BlockPos> craftingTableBlocks = new LinkedHashSet<>();

    /** {@link #functionalBlocks} split by kind, in scan order. */
    private final Map<HeatSource, Set<BlockPos>> heatBlocks = new EnumMap<>(HeatSource.class);

    public KitchenBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                           Direction interiorDirection, Set<BlockPos> initialNavMeshBlocks) {
        super(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks);
    }

    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        craftingTableBlocks.clear();
        heatBlocks.clear();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 2; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isFunctionalBlockAccessibleFromRoom(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    HeatSource heat = heatSourceOf(state);
                    if (heat != null) {
                        functionalBlocks.add(pos);
                        heatBlocks.computeIfAbsent(heat, k -> new LinkedHashSet<>()).add(pos);
                    } else if (state.is(Blocks.CRAFTING_TABLE)) {
                        craftingTableBlocks.add(pos);
                    }
                }
            }
        }
        return functionalBlocks.size() >= REQUIRED_FURNACES
                && craftingTableBlocks.size() >= REQUIRED_CRAFTING_TABLES;
    }

    @Nullable
    private static HeatSource heatSourceOf(BlockState state) {
        if (state.getBlock() instanceof SmokerBlock) return HeatSource.SMOKER;
        if (state.getBlock() instanceof BlastFurnaceBlock) return HeatSource.BLAST_FURNACE;
        if (state.getBlock() instanceof AbstractFurnaceBlock) return HeatSource.FURNACE;
        if (state.getBlock() instanceof CampfireBlock) return HeatSource.CAMPFIRE;
        return null;
    }

    /** The first heat source of {@code kind} in the room, or null when it has none. */
    @Nullable
    public BlockPos getHeatPos(HeatSource kind) {
        Set<BlockPos> blocks = heatBlocks.get(kind);
        return blocks == null || blocks.isEmpty() ? null : blocks.iterator().next();
    }

    /** Every heat source of {@code kind} in the room, in scan order; empty when it has none. */
    public List<BlockPos> getHeatPositions(HeatSource kind) {
        Set<BlockPos> blocks = heatBlocks.get(kind);
        return blocks == null ? List.of() : List.copyOf(blocks);
    }

    @Nullable
    public BlockPos getCraftingTablePos() {
        return craftingTableBlocks.isEmpty() ? null : craftingTableBlocks.iterator().next();
    }

    /** Every crafting table of the room, in scan order; empty when it has none. */
    public List<BlockPos> getCraftingTablePositions() {
        return List.copyOf(craftingTableBlocks);
    }

    /** Whether {@code pos} is one of this room's heat sources or crafting tables. */
    public boolean hasStation(BlockPos pos) {
        return functionalBlocks.contains(pos) || craftingTableBlocks.contains(pos);
    }

    /** Villagers inside can feel crowded; see {@link BuildingSpace}. */
    @Override
    public boolean countsCrowding() {
        return true;
    }
}
