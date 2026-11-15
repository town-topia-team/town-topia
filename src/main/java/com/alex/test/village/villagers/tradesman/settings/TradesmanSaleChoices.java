package com.alex.test.village.villagers.tradesman.settings;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Sale items tab's choices, per village rather than per tradesman: every
 * tradesman of a village follows the same SELL/KEEP list and keep numbers,
 * and they survive a tradesman dying or changing job.
 *
 * <p>Each village holds a {@code sale_items} compound (item id to allowed)
 * and a {@code sale_keep} compound (item id to keep number), the same shape a
 * tradesman used to keep in its own brain data, so moving them over is a copy.
 */
public final class TradesmanSaleChoices extends SavedData {
    private static final String DATA_NAME = "dynamicvillages_tradesman_sale_choices";
    private static final SavedData.Factory<TradesmanSaleChoices> FACTORY =
            new SavedData.Factory<>(TradesmanSaleChoices::new, TradesmanSaleChoices::load, null);

    static final String SALE_ITEMS = "sale_items";
    static final String SALE_KEEP = "sale_keep";

    private final Map<UUID, CompoundTag> byVillage = new HashMap<>();

    public static TradesmanSaleChoices get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** The village's SELL/KEEP choices; read only, never null. */
    CompoundTag allowed(UUID village) {
        return byVillage.getOrDefault(village, new CompoundTag()).getCompound(SALE_ITEMS);
    }

    /** The village's own keep numbers; read only, never null. */
    CompoundTag keeps(UUID village) {
        return byVillage.getOrDefault(village, new CompoundTag()).getCompound(SALE_KEEP);
    }

    void setAllowed(UUID village, String itemId, boolean allowed) {
        section(village, SALE_ITEMS).putBoolean(itemId, allowed);
        setDirty();
    }

    void setKeep(UUID village, String itemId, int keep) {
        section(village, SALE_KEEP).putInt(itemId, keep);
        setDirty();
    }

    void clearKeep(UUID village, String itemId) {
        CompoundTag entry = byVillage.get(village);
        if (entry == null || !entry.getCompound(SALE_KEEP).contains(itemId)) return;
        entry.getCompound(SALE_KEEP).remove(itemId);
        setDirty();
    }

    /**
     * Folds one tradesman's old per-tradesman choices into the village's.
     * Where they disagree the safer choice wins: KEEP over SELL, and the
     * higher keep number, so moving never makes a village sell more.
     */
    void merge(UUID village, CompoundTag oldAllowed, CompoundTag oldKeeps) {
        if (oldAllowed.isEmpty() && oldKeeps.isEmpty()) return;
        CompoundTag allowed = section(village, SALE_ITEMS);
        for (String id : oldAllowed.getAllKeys()) {
            boolean value = oldAllowed.getBoolean(id);
            allowed.putBoolean(id, allowed.contains(id) ? allowed.getBoolean(id) && value : value);
        }
        CompoundTag keeps = section(village, SALE_KEEP);
        for (String id : oldKeeps.getAllKeys()) {
            int value = oldKeeps.getInt(id);
            keeps.putInt(id, keeps.contains(id) ? Math.max(keeps.getInt(id), value) : value);
        }
        setDirty();
    }

    private CompoundTag section(UUID village, String key) {
        CompoundTag entry = byVillage.computeIfAbsent(village, ignored -> new CompoundTag());
        if (!entry.contains(key, Tag.TAG_COMPOUND)) entry.put(key, new CompoundTag());
        return entry.getCompound(key);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag villages = new ListTag();
        for (Map.Entry<UUID, CompoundTag> entry : byVillage.entrySet()) {
            CompoundTag saved = entry.getValue().copy();
            saved.putUUID("village", entry.getKey());
            villages.add(saved);
        }
        tag.put("villages", villages);
        return tag;
    }

    private static TradesmanSaleChoices load(CompoundTag tag, HolderLookup.Provider registries) {
        TradesmanSaleChoices choices = new TradesmanSaleChoices();
        ListTag villages = tag.getList("villages", Tag.TAG_COMPOUND);
        for (int i = 0; i < villages.size(); i++) {
            CompoundTag saved = villages.getCompound(i).copy();
            if (!saved.hasUUID("village")) continue;
            UUID village = saved.getUUID("village");
            saved.remove("village");
            choices.byVillage.put(village, saved);
        }
        return choices;
    }
}
