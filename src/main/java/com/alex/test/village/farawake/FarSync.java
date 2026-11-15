package com.alex.test.village.farawake;

import com.alex.test.mixin.ChunkMapAccessor;
import com.alex.test.mixin.TrackedEntityAccessor;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Sends a far body's changes (place, turn, pose, riders) to the players who see it, right after the mod
 * ran its tick ({@link FarBodyTicker}, {@link FarExtras}). Minecraft's {@code ChunkMap.tick} sends them only
 * for entities in entity-ticking range or that changed section, so far bodies jumped on screen at
 * section lines (far villagers plan, bug 29).
 * <p>Skipped while nobody sees the entity: a player who starts seeing it gets the last place sent (the
 * add packet is built from the tracking base, {@code ServerEntity.getPositionBase}), and the next send
 * moves it on from there, so nothing drifts. Never throws: the first error switches {@link FarAwake#sync}
 * off. Server thread only.
 */
final class FarSync {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FarSync() {}

    /**
     * Sends {@code e}, the vehicle it rode before its tick ({@code rodeBefore}, its seat: a rider getting off
     * changes the seat's rider list) and the one it rides now. Returns how many entities were sent.
     */
    static int send(ServerLevel sl, Entity e, @Nullable Entity rodeBefore) {
        if (!FarAwake.sync) return 0;
        long began = System.nanoTime();
        try {
            Int2ObjectMap<?> tracked = ((ChunkMapAccessor) sl.getChunkSource().chunkMap).dv$entityMap();
            int sent = one(tracked, e);
            if (rodeBefore != null && rodeBefore != e) sent += one(tracked, rodeBefore);
            Entity vehicle = e.getVehicle();
            if (vehicle != null && vehicle != rodeBefore) sent += one(tracked, vehicle);
            return sent;
        } catch (Exception | LinkageError ex) {
            LOGGER.error("[FarSync] switched off: sending a far {} to the players who see it threw; far bodies"
                    + " jump on screen again until it is turned on (Mayor > Performance, or /villager farawake sync on).", EntityType.getKey(e.getType()), ex);
            // Logged first: the chat line it sends the server owners says "see the log".
            FarAwake.stopSyncAfterErrors(sl.getServer());
            return 0;
        } finally {
            // The Performance page's "smooth movement" time; every caller runs inside a FarAwake tick, which counts it too.
            FarCost.addSend(sl.getServer().getTickCount(), System.nanoTime() - began);
        }
    }

    private static int one(Int2ObjectMap<?> tracked, Entity e) {
        // A removed entity has left the tracking map already (its removal was sent).
        if (e.isRemoved()) return 0;
        Object entry = tracked.get(e.getId());
        if (entry == null) return 0;
        TrackedEntityAccessor t = (TrackedEntityAccessor) entry;
        if (t.dv$seenBy().isEmpty()) return 0;
        t.dv$serverEntity().sendChanges();
        return 1;
    }
}
