package com.alex.test.testkit;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Supplements block random ticks for village farms while the server is
 * headless.
 *
 * <p>Vanilla only random-ticks chunks near a player, so on a playerless test
 * server crops never grow, farmland never hydrates, and every farming test
 * silently deadlocks at "planted, waiting". This handler applies the same
 * per-block probability vanilla uses ({@code randomTickSpeed}/4096 per tick) to
 * exactly the tiles a discovered field owns — plus the soil beneath them, which is what
 * moisturises farmland.
 *
 * <p>It also services any box a scenario declared through
 * {@code /vtest ticker}, which is how anything growing outside a registered
 * farm - a wild crop patch, a hedge, a cane bank - gets ticked at all.
 *
 * <p>Active only when {@link TestKit#headless()} is on AND nobody is online;
 * the moment a player joins, vanilla ticking takes over and this stands down.
 */
public final class HeadlessRandomTicker {

    private HeadlessRandomTicker() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!TestKit.headless()) return;
        if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
        if (sl.getServer().getPlayerList().getPlayerCount() > 0) return;

        int speed = sl.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        if (speed <= 0) return;
        float perBlockChance = Math.min(1.0f, speed / 4096.0f);

        for (VillageManager mgr : VillageManagerRegistry.get(sl.getServer()).all()) {
            if (mgr.getLevel() == null
                    || !sl.dimension().equals(mgr.getLevel().dimension())) {
                continue;
            }
            for (var farm : mgr.farmers().snapshots()) {
                for (BlockPos tile : farm.hydrationCoverage()) {
                    maybeTick(sl, tile, perBlockChance);          // the crop
                    maybeTick(sl, tile.below(), perBlockChance);  // the farmland
                }
            }
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int[] box : TestKit.randomTickBoxes()) {
            for (int x = box[0]; x <= box[3]; x++) {
                for (int y = box[1]; y <= box[4]; y++) {
                    for (int z = box[2]; z <= box[5]; z++) {
                        maybeTick(sl, cursor.set(x, y, z), perBlockChance);
                    }
                }
            }
        }
    }

    private static void maybeTick(ServerLevel sl, BlockPos pos, float chance) {
        if (sl.random.nextFloat() >= chance) return;
        if (!sl.hasChunkAt(pos)) return;
        BlockState state = sl.getBlockState(pos);
        // immutable(): callers may pass a shared cursor, and randomTick hands
        // the position on to block code that is entitled to keep it.
        if (state.isRandomlyTicking()) state.randomTick(sl, pos.immutable(), sl.random);
    }
}
