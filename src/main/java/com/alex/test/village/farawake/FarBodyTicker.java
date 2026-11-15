package com.alex.test.village.farawake;

import com.alex.test.entity.ChairSeatEntity;
import com.alex.test.entity.NpcEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runs one far villager's body tick where Minecraft does not (outside every player's simulation
 * distance, on ground {@link FarHolds} keeps loaded). The rules:
 * <ol>
 *   <li>Once a tick: never when the body's own stamp ({@link NpcEntity#lastBodyTickTime}) is this
 *       tick, nor on ground Minecraft runs. The village tick runs after Minecraft's creature loop,
 *       so a Minecraft tick this tick is always seen first.</li>
 *   <li>Ready ground: every chunk under the body's box widened by {@link FarAwake#EDGE_MARGIN} is
 *       loaded with its creatures. Unloaded chunks have no walls, and a body moved into one is lost
 *       to the level until the next save; so the body waits instead ({@link Result#EDGE}).</li>
 *   <li>Never crash: our own catch (Minecraft's {@code guardEntityTick} would stop the server). A
 *       throwing villager waits {@link FarAwake#ERROR_COOLDOWN} ticks; {@link FarAwake#BREAKER_ERRORS}
 *       throws within {@link FarAwake#BREAKER_WINDOW} ticks switch the whole system off.</li>
 * </ol>
 * Server thread only.
 */
final class FarBodyTicker {

    private static final Logger LOGGER = LogUtils.getLogger();

    enum Result { TICKED, MINECRAFT, GONE, FROZEN, ASLEEP, COOLDOWN, EDGE, FAILED }

    /** {@link #firstNotReady}: every chunk is ready. No real chunk has this key (z would be 2^31 - 1). */
    static final long READY = Long.MAX_VALUE;
    /** One error line per villager at most this often (5 minutes). */
    private static final int ERROR_LOG_QUIET = 6000;

    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();
    private static final Map<UUID, Long> ERROR_QUIET_UNTIL = new HashMap<>();
    private static final ArrayDeque<Long> RECENT_ERRORS = new ArrayDeque<>();

    /** Set by {@link #tick} on {@link Result#EDGE}: the first chunk not ready under the body. */
    static long lastEdgeChunk = READY;

    private FarBodyTicker() {}

    static Result tick(ServerLevel sl, NpcEntity npc, long now) {
        // A dying body is still ticked, so it finishes dying and is removed as near a player.
        if (npc.isRemoved() || npc.level() != sl) return Result.GONE;
        if (npc.lastBodyTickTime() == now || sl.isPositionEntityTicking(npc.blockPosition())) {
            return Result.MINECRAFT;
        }
        if (sl.tickRateManager().isEntityFrozen(npc)) return Result.FROZEN;
        if (FarAwake.sleepSkip && npc.isSleeping()) return Result.ASLEEP;
        UUID id = npc.getNpcId();
        Long until = COOLDOWN_UNTIL.get(id);
        if (until != null) {
            if (now < until) return Result.COOLDOWN;
            COOLDOWN_UNTIL.remove(id);
        }
        long blocked = firstNotReady(sl, npc);
        if (blocked != READY) {
            lastEdgeChunk = blocked;
            return Result.EDGE;
        }
        try {
            Entity vehicle = npc.getVehicle();
            if (FarAwake.extras && vehicle instanceof ChairSeatEntity seat && !seat.isRemoved()
                    && seat.hasPassenger(npc) && !sl.isPositionEntityTicking(seat.blockPosition())) {
                // Vehicle first, as Minecraft does: the seat's own tick removes it when the chair is gone.
                // Inside view distance the villager is in the level's tick list, so the seat's rider pass
                // ticks it (stamped: done); past it, the rider pass skips it and we tick it below.
                sl.tickNonPassenger(seat);
                if (npc.lastBodyTickTime() == now) return Result.TICKED; // its rider pass ran it after all
                vehicle = npc.getVehicle();
            }
            if (vehicle == null) {
                sl.tickNonPassenger(npc);
            } else if (vehicle.isRemoved() || !vehicle.hasPassenger(npc)) {
                npc.stopRiding();
                sl.tickNonPassenger(npc);
            } else {
                // What ServerLevel.tickPassenger does (private there, read 10-18): Minecraft's own
                // call also wants the passenger in its tick list, which a held chunk never gives.
                npc.setOldPosAndRot();
                npc.tickCount++;
                npc.rideTick();
            }
            return Result.TICKED;
        } catch (Exception | StackOverflowError | LinkageError e) {
            failed(npc, id, now, e);
            return Result.FAILED;
        }
    }

    /** Every chunk under {@code e}'s box widened by the edge margin is loaded with its creatures. */
    static boolean groundReady(ServerLevel sl, Entity e) {
        return firstNotReady(sl, e) == READY;
    }

    /** The first chunk under {@code e}'s box widened by the edge margin that is not ready, or {@link #READY}. */
    static long firstNotReady(ServerLevel sl, Entity e) {
        AABB box = e.getBoundingBox();
        int cx0 = SectionPos.blockToSectionCoord(box.minX - FarAwake.EDGE_MARGIN);
        int cx1 = SectionPos.blockToSectionCoord(box.maxX + FarAwake.EDGE_MARGIN);
        int cz0 = SectionPos.blockToSectionCoord(box.minZ - FarAwake.EDGE_MARGIN);
        int cz1 = SectionPos.blockToSectionCoord(box.maxZ + FarAwake.EDGE_MARGIN);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                long chunk = ChunkPos.asLong(cx, cz);
                if (!FarHolds.ready(sl, chunk)) return chunk;
            }
        }
        return READY;
    }

    private static void failed(NpcEntity npc, UUID id, long now, Throwable e) {
        COOLDOWN_UNTIL.put(id, now + FarAwake.ERROR_COOLDOWN);
        Long quiet = ERROR_QUIET_UNTIL.get(id);
        if (quiet == null || now >= quiet) {
            ERROR_QUIET_UNTIL.put(id, now + ERROR_LOG_QUIET);
            LOGGER.error("[FarAwake] Villager {} ({}) threw while being moved far away at {}; it waits {} ticks.",
                    id.toString().substring(0, 8), npc.getProfession(), npc.blockPosition().toShortString(),
                    FarAwake.ERROR_COOLDOWN, e);
        }
        RECENT_ERRORS.addLast(now);
        while (!RECENT_ERRORS.isEmpty() && now - RECENT_ERRORS.peekFirst() >= FarAwake.BREAKER_WINDOW) {
            RECENT_ERRORS.removeFirst();
        }
        if (RECENT_ERRORS.size() >= FarAwake.BREAKER_ERRORS) {
            RECENT_ERRORS.clear();
            LOGGER.error("[FarAwake] switched off: {} errors in a minute while moving far villagers (last: {} {}).",
                    FarAwake.BREAKER_ERRORS, id.toString().substring(0, 8), e.toString());
            // Logged first: the chat line it sends the server owners says "see the log".
            FarAwake.stopAfterErrors(npc.getServer());
        }
    }

    static void clear() {
        COOLDOWN_UNTIL.clear();
        ERROR_QUIET_UNTIL.clear();
        RECENT_ERRORS.clear();
        lastEdgeChunk = READY;
    }
}
