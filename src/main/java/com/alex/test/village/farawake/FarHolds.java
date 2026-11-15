package com.alex.test.village.farawake;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;

/**
 * The chunk holds themselves: a level 33 region ticket (distance 0). A chunk held that way is
 * fully loaded with its creatures, but nothing in it ticks: no creatures, no crops, no redstone
 * (level 33 is neither block ticking, 32, nor entity ticking, 31). The ticket runs out by itself
 * after {@link FarAwake#HOLD_LIFESPAN} ticks unless added again, which renews it. Server thread only.
 */
final class FarHolds implements FarHoldBook.Tickets {

    static final TicketType<ChunkPos> TICKET = TicketType.create(
            "dynamicvillages_far_villager", Comparator.comparingLong(ChunkPos::toLong), FarAwake.HOLD_LIFESPAN);

    private final ServerLevel sl;

    FarHolds(ServerLevel sl) {
        this.sl = sl;
    }

    @Override
    public void add(long chunk) {
        ChunkPos pos = new ChunkPos(chunk);
        sl.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
    }

    /** Loaded in full with its creatures: safe to tick a body in or move one into. */
    static boolean ready(ServerLevel sl, long chunk) {
        return sl.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null
                && sl.areEntitiesLoaded(chunk);
    }
}
