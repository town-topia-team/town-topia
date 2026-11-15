package com.alex.test.village.farawake;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The {@code [FarAwake]} minute lines. One per village: how many of its villagers Minecraft is
 * moving, how many stand loaded but still past the simulation distance, how many have no body
 * loaded, the far bodies held by what they are doing, and how often the village tick waited for a
 * chunk to load ({@link ChunkStallWatch}). One per level: its holds.
 *
 * <pre>
 * [FarAwake] a71a85c5 members 96: running 61, standing still 22, unloaded 13; bodies in 41 chunks; far 22 (home 3, asleep 9, food 1, work 8, free 1), holds 30 chunks; moved 12.6 a tick, 13 us avg, 41 us max, edge waits 2, asleep 9, errors 0; sent 4.0 a tick, sync 3.5 us a tick; stalls 3 (40 ms in all, worst 18 ms at FarmerActivity.scan:412)
 * [FarAwake] minecraft:overworld holds 44 chunks (1 still loading, 0 waiting); last minute: new 3, renewed 26, let go 1, place waits 0
 * </pre>
 */
final class FarStats {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FarStats() {}

    static void logVillage(VillageManager mgr, ServerLevel sl, FarAwake.VillageState vs, FarHoldBook book) {
        LOGGER.info("[FarAwake] {}", describe(mgr, sl, vs, book, true));
        vs.ticks = vs.moved = vs.moveNanos = vs.moveMaxNanos = vs.edgeWaits = vs.errors = vs.synced = vs.syncNanos = 0;
    }

    /**
     * One village's numbers. {@code takeStalls}: the minute line takes the stall count and
     * starts it again; the status command only reads it.
     */
    static String describe(VillageManager mgr, ServerLevel sl, FarAwake.VillageState vs, FarHoldBook book,
                           boolean takeStalls) {
        int members = 0, running = 0, still = 0, unloaded = 0;
        Set<Long> chunks = new HashSet<>();
        for (VillagerModel model : mgr.getVillagers()) {
            members++;
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved() || npc.level() != sl) {
                unloaded++;
                continue;
            }
            chunks.add(ChunkPos.asLong(npc.blockPosition()));
            if (sl.isPositionEntityTicking(npc.blockPosition())) running++;
            else still++;
        }
        StringBuilder line = new StringBuilder();
        line.append(mgr.getVillageId().toString(), 0, 8)
                .append(" members ").append(members)
                .append(": running ").append(running)
                .append(", standing still ").append(still)
                .append(", unloaded ").append(unloaded)
                .append("; bodies in ").append(chunks.size()).append(" chunks; far ").append(vs.far);
        if (vs.far > 0) {
            line.append(" (");
            boolean first = true;
            for (int r = 0; r < vs.farByRank.length; r++) {
                if (vs.farByRank[r] == 0) continue;
                if (!first) line.append(", ");
                line.append(FarAwake.rankName(r)).append(' ').append(vs.farByRank[r]);
                first = false;
            }
            line.append(')');
        }
        line.append(", holds ").append(book.heldCount(vs.id)).append(" chunks; ");
        long ticks = Math.max(1, vs.ticks);
        line.append(String.format(Locale.ROOT, "moved %.1f a tick, %.0f us avg, %.0f us max, edge waits %d, asleep %d, errors %d; ",
                vs.moved / (double) ticks, vs.moved == 0 ? 0.0 : vs.moveNanos / 1e3 / vs.moved,
                vs.moveMaxNanos / 1e3, vs.edgeWaits, vs.asleep, vs.errors));
        // The whole cost of the send, lookups for bodies nobody sees included.
        line.append(String.format(Locale.ROOT, "sent %.1f a tick, sync %.1f us a tick; ",
                vs.synced / (double) ticks, vs.syncNanos / 1e3 / ticks));
        line.append(ChunkStallWatch.describe(mgr.getVillageId(), takeStalls));
        return line.toString();
    }

    /** One level's holds; "still loading" is checked here only (once a minute, or on the command). */
    static String describeLevel(ServerLevel sl, FarAwake.LevelState ls) {
        int[] loading = {0};
        ls.book.forEachHeld(chunk -> {
            if (!FarHolds.ready(sl, chunk)) loading[0]++;
        });
        return sl.dimension().location() + " holds " + ls.book.heldCount() + " chunks ("
                + loading[0] + " still loading, " + ls.book.waitingCount() + " waiting); last minute: new "
                + ls.added + ", renewed " + ls.renewed + ", let go " + ls.forgotten + ", place waits " + ls.placeWaits
                + ", extras moved " + ls.extraMoves + " (waited " + ls.extraWaits + ", sent " + ls.extraSynced
                + (ls.extraErrors > 0 ? ", errors " + ls.extraErrors : "") + ")";
    }
}
