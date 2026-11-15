package com.alex.test.village;

import com.alex.test.village.brain.VillagerPause;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Doors and gates that must stay open for now: a villager is leading an
 * animal through and it has not passed yet.
 *
 * <p>Several villagers can hold one doorway; it may close only once every
 * hold is gone. {@link RouteDoorHandler} will not close a held door, its own
 * or anyone else's, and keeps trying until the holds are gone. A hold not
 * refreshed for {@link #EXPIRE_TICKS} lapses by itself, so a villager that
 * died or unloaded mid-walk never leaves a gate open forever. A villager
 * paused for a player's screen ({@link VillagerPause}) keeps its holds.
 */
public final class DoorHolds {

    /** A hold must be refreshed at least this often to stay. */
    public static final long EXPIRE_TICKS = 100;

    /** Dimension, anchor (a door's lower half or the gate), owner, last refresh. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Map<UUID, Long>>> HOLDS = new HashMap<>();

    private DoorHolds() {}

    /** Takes or refreshes {@code owner}'s hold on the doorway at {@code anchor}. */
    public static void hold(ServerLevel level, BlockPos anchor, UUID owner) {
        HOLDS.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .computeIfAbsent(anchor.immutable(), k -> new HashMap<>())
                .put(owner, level.getGameTime());
    }

    /** Drops {@code owner}'s hold; the doorway may close once nobody else holds it. */
    public static void release(ServerLevel level, BlockPos anchor, UUID owner) {
        Map<BlockPos, Map<UUID, Long>> doors = HOLDS.get(level.dimension());
        if (doors == null) return;
        Map<UUID, Long> owners = doors.get(anchor);
        if (owners == null) return;
        owners.remove(owner);
        if (owners.isEmpty()) doors.remove(anchor);
    }

    /** Whether anyone still holds the doorway at {@code anchor} open. Lapsed holds are dropped here. */
    public static boolean isHeld(ServerLevel level, BlockPos anchor) {
        Map<BlockPos, Map<UUID, Long>> doors = HOLDS.get(level.dimension());
        if (doors == null || doors.isEmpty()) return false;
        Map<UUID, Long> owners = doors.get(anchor);
        if (owners == null) return false;
        long now = level.getGameTime();
        // A holder paused for a player's screen still holds the door.
        owners.entrySet().removeIf(e -> VillagerPause.stale(e.getKey(), e.getValue(), EXPIRE_TICKS, now));
        if (owners.isEmpty()) {
            doors.remove(anchor);
            return false;
        }
        return true;
    }

    /** Server stopping: nothing is held in the next world. */
    public static void clear() {
        HOLDS.clear();
    }
}
