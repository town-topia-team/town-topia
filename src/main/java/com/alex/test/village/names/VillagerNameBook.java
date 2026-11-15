package com.alex.test.village.names;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntUnaryOperator;

/**
 * Every villager name in use in the world, with whose it is (the villager's npcId),
 * saved with the world. A name is held from the day it is given until its villager
 * dies or is removed, loaded or not, so no two villagers anywhere share one.
 *
 * <p>Names given before this book existed are written in as their villagers load
 * (NpcEntity.ensureGivenName); a villager that loads with a name someone else
 * already holds is renamed then.
 */
public final class VillagerNameBook extends SavedData {

    private static final String DATA_NAME = "dynamicvillages_names";
    private static final SavedData.Factory<VillagerNameBook> FACTORY =
            new SavedData.Factory<>(VillagerNameBook::new, VillagerNameBook::load, null);

    /** {@link VillagerNames#key} of a name to the npcId holding it. */
    private final Map<String, UUID> owners = new HashMap<>();
    /** How many held names use each first name ({@link VillagerNames#key} of the first name). */
    private final Map<String, Integer> firstNames = new HashMap<>();

    public static VillagerNameBook get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public boolean isTaken(String name) {
        return owners.containsKey(VillagerNames.key(name));
    }

    /** Whether {@code name} is free, or already {@code id}'s own. */
    public boolean isFreeFor(String name, UUID id) {
        UUID owner = owners.get(VillagerNames.key(name));
        return owner == null || owner.equals(id);
    }

    @Nullable
    public UUID owner(String name) {
        return owners.get(VillagerNames.key(name));
    }

    /** Holds {@code name} for {@code id}; false (and nothing changes) if someone else holds it. */
    public boolean claim(String name, UUID id) {
        String key = VillagerNames.key(name);
        UUID owner = owners.get(key);
        if (owner != null) return owner.equals(id);
        owners.put(key, id);
        firstNames.merge(VillagerNames.key(VillagerNames.firstName(name)), 1, Integer::sum);
        setDirty();
        return true;
    }

    /** Frees {@code name} if {@code id} holds it. */
    public void release(@Nullable String name, UUID id) {
        if (name == null) return;
        String key = VillagerNames.key(name);
        if (!id.equals(owners.get(key))) return;
        owners.remove(key);
        firstNames.computeIfPresent(VillagerNames.key(VillagerNames.firstName(name)), (k, n) -> n > 1 ? n - 1 : null);
        setDirty();
    }

    /** A new full name nobody holds yet (not claimed: call {@link #claim}). */
    public String newName(IntUnaryOperator random) {
        return VillagerNames.pick(this::isTaken,
                first -> firstNames.containsKey(VillagerNames.key(first)), random);
    }

    /** {@code first} with a last name nobody holds yet (not claimed). */
    public String withLastName(String first, IntUnaryOperator random) {
        return VillagerNames.withLastName(first, this::isTaken, random);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // The names as given (with capitals) are not kept here, only the key; the
        // villager keeps its own spelling, and only the key is ever compared.
        ListTag list = new ListTag();
        owners.forEach((key, id) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", key);
            entry.putUUID("Id", id);
            list.add(entry);
        });
        tag.put("Names", list);
        return tag;
    }

    private static VillagerNameBook load(CompoundTag tag, HolderLookup.Provider registries) {
        VillagerNameBook book = new VillagerNameBook();
        ListTag list = tag.getList("Names", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("Id") || entry.getString("Name").isEmpty()) continue;
            String key = entry.getString("Name");
            book.owners.put(key, entry.getUUID("Id"));
            book.firstNames.merge(VillagerNames.key(VillagerNames.firstName(key)), 1, Integer::sum);
        }
        return book;
    }
}
