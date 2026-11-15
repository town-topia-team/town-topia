package com.alex.test.command;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who {@code /villager spawn} made, saved with the world, so {@code /villager spawn clear}
 * also gets the ones in unloaded chunks: they leave their village at once, and their body
 * is turned away the next time its chunk loads (before the village takes it back in).
 *
 * <p>Each spawned body carries the clear count it was made in ({@link #GEN_KEY}); a clear
 * raises the count, so any tagged body from an earlier count is a cleared one. Bodies from
 * before this file (no count saved) read as 0 and go on the first clear.
 */
public final class SpawnedVillagers extends SavedData {

    /** On every villager made by {@code /villager spawn}. */
    public static final String TAG = "dv_spawn_cmd";
    private static final String GEN_KEY = "dv_spawn_gen";

    private static final String DATA_NAME = "dynamicvillages_spawned_villagers";
    private static final SavedData.Factory<SpawnedVillagers> FACTORY =
            new SavedData.Factory<>(SpawnedVillagers::new, SpawnedVillagers::load, null);

    /** How many clears so far. */
    private int generation;
    /** Villager ids made since the last clear. */
    private final Set<UUID> spawned = new HashSet<>();

    private static SpawnedVillagers get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Marks a villager the command just made. */
    public static void remember(MinecraftServer server, NpcEntity npc) {
        SpawnedVillagers data = get(server);
        npc.addTag(TAG);
        npc.getPersistentData().putInt(GEN_KEY, data.generation);
        data.spawned.add(npc.getNpcId());
        data.setDirty();
    }

    /**
     * Removes every villager the command made: loaded ones now, unloaded ones leave
     * their village now and are turned away when their chunk loads.
     *
     * @return {loaded bodies removed, unloaded villagers let go}
     */
    public static int[] clear(MinecraftServer server) {
        SpawnedVillagers data = get(server);
        VillageManagerRegistry registry = VillageManagerRegistry.get(server);
        Set<UUID> notLoaded = new HashSet<>(data.spawned);
        java.util.List<NpcEntity> bodies = new java.util.ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof NpcEntity npc && npc.getTags().contains(TAG)) bodies.add(npc);
            }
        }
        for (NpcEntity npc : bodies) {
            notLoaded.remove(npc.getNpcId());
            forget(registry, npc.getNpcId());
            npc.discard();
        }
        int letGo = 0;
        for (UUID id : notLoaded) {
            if (forget(registry, id)) letGo++;
        }
        data.spawned.clear();
        data.generation++;
        data.setDirty();
        return new int[]{bodies.size(), letGo};
    }

    /** Takes {@code npcId} off every village; true if one still had a record of it. */
    private static boolean forget(VillageManagerRegistry registry, UUID npcId) {
        boolean found = false;
        for (VillageManager village : java.util.List.copyOf(registry.all())) {
            if (village.getModel(npcId) != null) found = true;
            // Also when no record is loaded: the village may still remember the id.
            registry.forgetVillager(village, npcId);
        }
        return found;
    }

    /**
     * A cleared villager's chunk loaded: it is not added to the world (so it is gone from
     * the save when the chunk is next saved). Highest priority, so the village's own join
     * handler, which would take it back in, never sees it.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof NpcEntity npc) || !npc.getTags().contains(TAG)) return;
        if (!(event.getLevel() instanceof ServerLevel sl) || sl.getServer() == null) return;
        SpawnedVillagers data = get(sl.getServer());
        if (npc.getPersistentData().getInt(GEN_KEY) >= data.generation) return;
        forget(VillageManagerRegistry.get(sl.getServer()), npc.getNpcId());
        event.setCanceled(true);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Generation", generation);
        ListTag ids = new ListTag();
        spawned.forEach(id -> ids.add(StringTag.valueOf(id.toString())));
        tag.put("Spawned", ids);
        return tag;
    }

    private static SpawnedVillagers load(CompoundTag tag, HolderLookup.Provider registries) {
        SpawnedVillagers data = new SpawnedVillagers();
        data.generation = tag.getInt("Generation");
        ListTag ids = tag.getList("Spawned", Tag.TAG_STRING);
        for (int i = 0; i < ids.size(); i++) {
            try {
                data.spawned.add(UUID.fromString(ids.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // A broken id: nothing to clear for it.
            }
        }
        return data;
    }
}
