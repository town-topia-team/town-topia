package com.alex.test.village;

import com.alex.test.DynamicVillages;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;

/** Applies a player night skip before villagers wake and the day clock moves. */
public final class VillageSleepEvents {
    private VillageSleepEvents() {}

    @SubscribeEvent
    public static void onSleepFinished(SleepFinishedTimeEvent event) {
        if (DynamicVillages.isServerStopping()
                || !(event.getLevel() instanceof ServerLevel level)) return;
        long skippedTicks = event.getNewTime() - level.getDayTime();
        if (skippedTicks <= 0L) return;

        VillageManager singleton = VillageManager.INSTANCE;
        if (singleton.getLevel() == level) {
            singleton.onPlayerSleepFinished(level, skippedTicks, event.getNewTime());
        }
        for (VillageManager village : VillageManagerRegistry.get(level.getServer()).all()) {
            if (village.getLevel() == level && !village.isDormantOrReconciling()) {
                village.onPlayerSleepFinished(level, skippedTicks, event.getNewTime());
            }
        }
    }

    /**
     * The clock was turned back to morning from the evening or night
     * ({@code /time set day}): the same as a night slept through, for every
     * village, since all dimensions share the clock. Runs at the start of the
     * tick after the change, while sleepers still lie in their beds.
     */
    static void nightSkipped(ServerLevel overworld, long skippedTicks, long wakeDayTime) {
        if (skippedTicks <= 0L) return;
        VillageManager singleton = VillageManager.INSTANCE;
        if (singleton.getLevel() != null) {
            singleton.onPlayerSleepFinished(singleton.getLevel(), skippedTicks, wakeDayTime);
        }
        for (VillageManager village : VillageManagerRegistry.get(overworld.getServer()).all()) {
            ServerLevel level = village.getLevel();
            if (level != null && !village.isDormantOrReconciling()) {
                village.onPlayerSleepFinished(level, skippedTicks, wakeDayTime);
            }
        }
    }
}
