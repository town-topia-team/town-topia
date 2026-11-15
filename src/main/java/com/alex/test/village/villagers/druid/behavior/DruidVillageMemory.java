package com.alex.test.village.villagers.druid.behavior;

import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.Activity;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * What every druid in one village shares: the last sapling scan, saplings
 * that would not grow, mines just handed over to a miner, and which druid is
 * working which field.
 *
 * <p>None of it matters for more than a few minutes, so it lives in memory
 * beside the village rather than in its save, and a restart simply forgets
 * it.
 */
final class DruidVillageMemory {

    private static final Map<VillageManager, DruidVillageMemory> BY_VILLAGE = new WeakHashMap<>();

    /**
     * How long one sapling scan is reused.
     *
     * <p>The scan looks through every loaded chunk of the village, and the
     * brain asks whether a druid has work many times a minute. Ten seconds
     * late to notice a freshly planted sapling costs nothing.
     */
    private static final int SAPLING_SCAN_TICKS = 200;

    private final Map<Long, Long> blockedSaplingsUntil = new HashMap<>();
    private final Map<Long, Long> yieldedMinesUntil = new HashMap<>();
    private final Map<UUID, FieldHold> fieldHolds = new HashMap<>();

    private record FieldHold(UUID druid, long until) {}
    private List<BlockPos> saplings = List.of();
    private long saplingScanAt;
    private boolean scanned;

    static DruidVillageMemory of(VillageManager mgr) {
        return BY_VILLAGE.computeIfAbsent(mgr, m -> new DruidVillageMemory());
    }

    /** Every sapling in the village, from a scan at most {@link #SAPLING_SCAN_TICKS} old. */
    List<BlockPos> saplings(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        if (!scanned || now - saplingScanAt >= SAPLING_SCAN_TICKS) {
            saplings = DruidSaplings.scan(ctx);
            saplingScanAt = now;
            scanned = true;
        }
        return saplings;
    }

    boolean isSaplingBlocked(BlockPos base, long now) {
        return isHeld(blockedSaplingsUntil, base, now);
    }

    void blockSapling(BlockPos base, long until) {
        blockedSaplingsUntil.put(base.asLong(), until);
    }

    /**
     * The druid working a field, or null when nobody is. A soft hold: it runs
     * out on its own, so a druid that died, unloaded or changed activity
     * never blocks a field for good.
     */
    @Nullable
    UUID fieldHolder(UUID farmId, long now) {
        FieldHold hold = fieldHolds.get(farmId);
        if (hold == null) return null;
        if (hold.until() <= now) {
            fieldHolds.remove(farmId);
            return null;
        }
        return hold.druid();
    }

    void holdField(UUID farmId, UUID druid, long until) {
        fieldHolds.put(farmId, new FieldHold(druid, until));
    }

    void releaseFields(UUID druid) {
        if (fieldHolds.isEmpty()) return;
        fieldHolds.values().removeIf(hold -> hold.druid().equals(druid));
    }

    boolean isMineYielded(BlockPos mouth, long now) {
        return isHeld(yieldedMinesUntil, mouth, now);
    }

    void yieldMine(BlockPos mouth, long until) {
        yieldedMinesUntil.put(mouth.asLong(), until);
    }

    private static boolean isHeld(Map<Long, Long> until, BlockPos pos, long now) {
        if (until.isEmpty()) return false;
        until.values().removeIf(t -> t <= now);
        return until.containsKey(pos.asLong());
    }
}
