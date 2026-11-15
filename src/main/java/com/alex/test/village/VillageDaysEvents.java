package com.alex.test.village;

import com.alex.test.DynamicVillages;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Runs the {@link VillageDays} calendar: loads it with the overworld, shows it
 * the clock at the start of every overworld tick (before villagers and
 * villages read the day), saves it with the world, and treats a night skipped
 * by turning the clock back like a night slept through.
 */
public final class VillageDaysEvents {
    private VillageDaysEvents() {}

    /** First, so villages loading with the overworld already read village days. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        Data data = Data.get(level);
        VillageDays.install(data.days);
        data.days.observe(level.getDayTime(), level.getGameTime());
        if (data.days.takeDirty()) data.setDirty();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            VillageDays.reset();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        VillageDays.reset();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLevelTick(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        Data data = Data.get(level);
        if (VillageDays.current() != data.days) VillageDays.install(data.days);
        long skipped = data.days.observe(level.getDayTime(), level.getGameTime());
        if (data.days.takeDirty()) data.setDirty();
        if (skipped == VillageDays.NO_SKIP || DynamicVillages.isServerStopping()) return;
        DynamicVillages.LOGGER.info("[VillageDays] Clock turned back to morning: village day {} begins "
                        + "(night of {} ticks skipped, treated like sleeping).",
                data.days.day(level.getDayTime()), skipped);
        VillageSleepEvents.nightSkipped(level, skipped, level.getDayTime());
    }

    /** The calendar's save, kept with the overworld. */
    static final class Data extends SavedData {
        private static final String DATA_NAME = "dynamicvillages_village_days";
        private static final SavedData.Factory<Data> FACTORY =
                new SavedData.Factory<>(Data::new, Data::load, null);

        final VillageDays days = new VillageDays();

        static Data get(ServerLevel overworld) {
            return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        }

        private static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data data = new Data();
            if (tag.contains("Offset")) {
                data.days.restore(tag.getLong("Offset"), tag.getLong("DayStart"),
                        tag.getLong("StillSince"), tag.getLong("StillSinceTime"),
                        tag.getLong("Furthest"), tag.getLong("Run"));
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLong("Offset", days.offset());
            tag.putLong("DayStart", days.dayStartGameTime());
            tag.putLong("StillSince", days.stillSinceGameTime());
            tag.putLong("StillSinceTime", days.stillSinceVillageTime());
            tag.putLong("Furthest", days.furthestVillageTime());
            tag.putLong("Run", days.run());
            return tag;
        }
    }
}
