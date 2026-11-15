package com.alex.test.village;

import com.alex.test.testkit.TestKit;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.farmer.FarmerManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.ForcedChunkManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Fields and pens that grow live while their village sleeps (away leftovers
 * round 8): a player just outside the border keeps those chunks running, so
 * the game grows the crops and the pen babies while the villagers stand
 * frozen. The catch-up would grow them again, so that time is left out of it
 * ({@link VillageDormancy}, the "watched" clock).
 *
 * <p>The game's own rules: crops, cane, berries and cocoa grow by random
 * ticks, only in a chunk that runs blocks and entities with a player (not
 * spectating) within 128 blocks, flat, of the chunk's middle, or in a chunk a
 * mod's chunk loader holds with ticking on (NeoForge's forced ticks), player or
 * not. Animals grow up in a chunk that runs entities while the dimension runs
 * them: anyone in it, or any force-loaded chunk in it (`/forceload` or a mod's
 * chunk loader; both hold their chunk at level 31, entity ticking). Time with
 * nobody on the server is left out already ({@link VillageDormancy}'s empty
 * count), so it is never counted here too. Trees are left out: the catch-up
 * never grows a sapling.
 */
final class OutsideWatch {

    /** The game's random-tick reach from a player to a chunk's middle. */
    private static final double RANDOM_TICK_REACH_SQR = 128.0 * 128.0;

    private OutsideWatch() {}

    /** Chunks holding a field tile, cane, bush, vine or cocoa (crop rule), and chunks under a pen (animal rule). */
    record Spots(long[] fields, long[] pens) {}

    /** The village's spots, from what its farms and pens saved: nothing is read from the world. */
    static Spots of(VillageManager mgr) {
        LongOpenHashSet fields = new LongOpenHashSet();
        FarmerManager.AwayField field = mgr.farmers().awayField();
        for (BlockPos pos : field.tiles()) fields.add(ChunkPos.asLong(pos));
        for (BlockPos pos : field.fruitSpaces().values()) fields.add(ChunkPos.asLong(pos));
        for (BlockPos pos : field.cane()) fields.add(ChunkPos.asLong(pos));
        for (BlockPos pos : field.berries()) fields.add(ChunkPos.asLong(pos));
        for (BlockPos pos : field.glowBerries()) fields.add(ChunkPos.asLong(pos));
        for (BlockPos pos : field.pods().keySet()) fields.add(ChunkPos.asLong(pos));
        LongOpenHashSet pens = new LongOpenHashSet();
        for (Building building : mgr.getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid()) continue;
            BlockPos min = ranch.shellMin();
            BlockPos max = ranch.shellMax();
            for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
                for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) pens.add(ChunkPos.asLong(cx, cz));
            }
        }
        return new Spots(fields.toLongArray(), pens.toLongArray());
    }

    /** Whether the game grows any of the spots right now (TestKit's simulated player counts as a player). */
    static boolean growsLive(ServerLevel sl, Spots spots) {
        List<BlockPos> watchers = new ArrayList<>();
        for (ServerPlayer player : sl.players()) {
            if (!player.isSpectator()) watchers.add(player.blockPosition());
        }
        boolean simulated = TestKit.hasSimulatedPlayer() && TestKit.simulatedPlayerDim().equals(sl.dimension());
        if (simulated) watchers.add(TestKit.simulatedPlayerPos());
        // The same test as VillageDormancy.countEmptyServerTick: that time is not counted at all.
        boolean serverEmpty = !TestKit.headless() && sl.getServer().getPlayerList().getPlayerCount() == 0;
        DistanceManager tickets = sl.getChunkSource().chunkMap.getDistanceManager();
        for (long chunk : spots.fields()) {
            if (!sl.shouldTickBlocksAt(chunk)) continue;
            // A chunk loader with ticking on grows the crops with nobody near (ServerChunkCache.tickChunks).
            if (!serverEmpty && tickets.shouldForceTicks(chunk)) return true;
            if (watchers.isEmpty() || !sl.isNaturalSpawningAllowed(new ChunkPos(chunk))) continue;
            double midX = (ChunkPos.getX(chunk) << 4) + 8.0;
            double midZ = (ChunkPos.getZ(chunk) << 4) + 8.0;
            for (BlockPos watcher : watchers) {
                double dx = midX - (watcher.getX() + 0.5);
                double dz = midZ - (watcher.getZ() + 0.5);
                if (dx * dx + dz * dz < RANDOM_TICK_REACH_SQR) return true;
            }
        }
        // The dimension runs entities with anyone in it or any chunk force-loaded (ServerLevel.tick).
        boolean runsEntities = !sl.players().isEmpty() || simulated
                || !serverEmpty && ForcedChunkManager.hasForcedChunks(sl);
        if (!runsEntities) return false;
        for (long chunk : spots.pens()) {
            if (sl.isNaturalSpawningAllowed(new ChunkPos(chunk))) return true;
        }
        return false;
    }
}
