package com.alex.test.village.brain;

import com.alex.test.block.TravelerCampfireBlock;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.KitchenBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The lit campfires a village's people gather round in their free time
 * ({@link CampfireActivity}): any campfire or soul campfire a player lit
 * inside the border.
 *
 * <p>Not the cooks' (a campfire in a Kitchen is a stove) and not the one
 * travelers light for the night, which is theirs. Found by reading the block
 * entities of the village's loaded chunks, never by scanning blocks, and kept
 * for {@link #REFRESH_TICKS}: a fire lit or put out is noticed within half a
 * minute, and a villager checks its own fire again before walking to it.
 */
public final class VillageCampfires {

    private static final long REFRESH_TICKS = 600L;
    /** No village needs more gathering places than this; the nearest ones to the search start win. */
    private static final int MAX_FIRES = 16;

    private record Known(long readAt, List<BlockPos> fires) {}

    private static final Map<UUID, Known> CACHE = new HashMap<>();

    private VillageCampfires() {}

    /** The village's gathering fires, as of the last read. */
    public static List<BlockPos> of(VillageManager village, ServerLevel level) {
        long now = level.getGameTime();
        Known known = CACHE.get(village.getVillageId());
        if (known != null && now - known.readAt() < REFRESH_TICKS && now >= known.readAt()) return known.fires();
        List<BlockPos> fires = read(village, level);
        CACHE.put(village.getVillageId(), new Known(now, fires));
        return fires;
    }

    /** The nearest gathering fire within {@code reach} blocks of {@code from}, still lit; null when none. */
    @Nullable
    public static BlockPos nearest(VillageManager village, ServerLevel level, BlockPos from, int reach) {
        BlockPos best = null;
        double bestSq = (double) reach * reach;
        for (BlockPos fire : of(village, level)) {
            double sq = fire.distSqr(from);
            if (sq <= bestSq && isLit(level, fire)) {
                bestSq = sq;
                best = fire;
            }
        }
        return best;
    }

    /** A lit campfire that is not the travelers' own. */
    public static boolean isLit(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof CampfireBlock
                && !(state.getBlock() instanceof TravelerCampfireBlock)
                && state.getValue(CampfireBlock.LIT);
    }

    /** Forgets a village's list (the village is gone, or a test wants a fresh read). */
    public static void forget(UUID villageId) {
        CACHE.remove(villageId);
    }

    private static List<BlockPos> read(VillageManager village, ServerLevel level) {
        NavGraph graph = village.getGraph();
        if (graph == null) return List.of();
        List<BlockPos> stoves = new ArrayList<>();
        for (Building building : village.getBuildings()) {
            if (building instanceof KitchenBuilding) stoves.addAll(building.getFunctionalBlocks());
        }
        List<BlockPos> fires = new ArrayList<>();
        int minCx = graph.getBoundsMinX() >> 4, maxCx = graph.getBoundsMaxX() >> 4;
        int minCz = graph.getBoundsMinZ() >> 4, maxCz = graph.getBoundsMaxZ() >> 4;
        for (int cx = minCx; cx <= maxCx && fires.size() < MAX_FIRES; cx++) {
            for (int cz = minCz; cz <= maxCz && fires.size() < MAX_FIRES; cz++) {
                // Loaded chunks only: never load (let alone generate) one to look for a fire.
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof CampfireBlockEntity)) continue;
                    BlockPos pos = blockEntity.getBlockPos();
                    if (!village.contains(pos) || stoves.contains(pos) || !isLit(level, pos)) continue;
                    fires.add(pos.immutable());
                    if (fires.size() >= MAX_FIRES) break;
                }
            }
        }
        return List.copyOf(fires);
    }
}
