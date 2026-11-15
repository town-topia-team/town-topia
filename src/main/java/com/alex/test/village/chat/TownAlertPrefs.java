package com.alex.test.village.chat;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Each player's own {@link TownAlertLevel}, saved with the world. Kept here
 * rather than on the player, so it survives death without copying.
 */
public final class TownAlertPrefs extends SavedData {

    private static final String DATA_NAME = "dynamicvillages_town_alerts";
    private static final SavedData.Factory<TownAlertPrefs> FACTORY =
            new SavedData.Factory<>(TownAlertPrefs::new, TownAlertPrefs::load, null);

    /** Only players who changed it; everyone else has the default. */
    private final Map<UUID, TownAlertLevel> levels = new HashMap<>();
    /**
     * Players told of every injury not from a monster (hunger, cactus, a wolf,
     * a player's hit), one line a second at most, instead of once a minute for
     * the village or once per 10 s for a player's hits.
     */
    private final Set<UUID> everyInjury = new HashSet<>();

    private static TownAlertPrefs get(ServerPlayer player) {
        return player.server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static TownAlertLevel of(ServerPlayer player) {
        return get(player).levels.getOrDefault(player.getUUID(), TownAlertLevel.DEFAULT);
    }

    public static void set(ServerPlayer player, TownAlertLevel level) {
        TownAlertPrefs prefs = get(player);
        TownAlertLevel old = level == TownAlertLevel.DEFAULT
                ? prefs.levels.remove(player.getUUID())
                : prefs.levels.put(player.getUUID(), level);
        if (old != level) prefs.setDirty();
    }

    public static boolean everyInjury(ServerPlayer player) {
        return get(player).everyInjury.contains(player.getUUID());
    }

    public static void setEveryInjury(ServerPlayer player, boolean on) {
        TownAlertPrefs prefs = get(player);
        UUID id = player.getUUID();
        if (on ? prefs.everyInjury.add(id) : prefs.everyInjury.remove(id)) prefs.setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag players = new CompoundTag();
        levels.forEach((id, level) -> players.putString(id.toString(), level.name()));
        tag.put("Players", players);
        ListTag everyInjuryIds = new ListTag();
        everyInjury.forEach(id -> everyInjuryIds.add(StringTag.valueOf(id.toString())));
        tag.put("EveryInjury", everyInjuryIds);
        return tag;
    }

    private static TownAlertPrefs load(CompoundTag tag, HolderLookup.Provider registries) {
        TownAlertPrefs prefs = new TownAlertPrefs();
        CompoundTag players = tag.getCompound("Players");
        for (String key : players.getAllKeys()) {
            try {
                prefs.levels.put(UUID.fromString(key), TownAlertLevel.valueOf(players.getString(key)));
            } catch (IllegalArgumentException ignored) {
                // A broken id or a level that no longer exists: that player gets the default.
            }
        }
        // Saves from before the switch have no list: off for everyone.
        ListTag everyInjuryIds = tag.getList("EveryInjury", Tag.TAG_STRING);
        for (int i = 0; i < everyInjuryIds.size(); i++) {
            try {
                prefs.everyInjury.add(UUID.fromString(everyInjuryIds.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // A broken id: that player has the switch off.
            }
        }
        return prefs;
    }
}
