package com.alex.test.client;

import com.alex.test.entity.NpcEntity;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.Util;

import java.util.Arrays;

/**
 * Name tags in a crowd (plan P5, 10-19): of the villagers that would show their name,
 * only the nearest {@link #limit} do, plus the one looked at. With 390 guards 69 to 129
 * names were drawn each frame, each its own text and batches.
 *
 * <p>Decided from last frame's distances: the {@code limit}-th nearest one sets this
 * frame's reach. A villager that gets its name keeps it for {@link #HOLD_MS}, so names
 * near the edge do not blink as the crowd moves (a few more than {@code limit} may show).
 * A village with fewer candidates than the limit sees no change. Render thread only.
 */
final class NameTagLimit {

    /** {@code /villagerfps names count <n>}; 0 = no limit. */
    static volatile int limit = 30;
    private static final long HOLD_MS = 1000;

    /** Squared distances of the villagers that wanted a name, this frame so far. */
    private static float[] wanted = new float[256];
    private static int wantedCount;
    /** Reach (squared) worked out from last frame's {@link #wanted}. */
    private static double reachSq = Double.MAX_VALUE;
    private static int frame = Integer.MIN_VALUE;
    /** Entity id -> frame it was counted in: the shadow pass asks twice a frame. */
    private static final Int2IntOpenHashMap COUNTED = new Int2IntOpenHashMap();
    /** Entity id -> time its name may keep showing until (milliseconds). */
    private static final Int2LongOpenHashMap SHOWN_UNTIL = new Int2LongOpenHashMap();

    static {
        COUNTED.defaultReturnValue(Integer.MIN_VALUE);
    }

    private NameTagLimit() {}

    /** Whether {@code npc}, which would show its name, still does in this crowd. */
    static boolean allows(NpcEntity npc, double distanceSq, boolean lookedAt) {
        roll();
        int id = npc.getId();
        if (COUNTED.put(id, frame) != frame) {
            if (wantedCount == wanted.length) wanted = Arrays.copyOf(wanted, wantedCount * 2);
            wanted[wantedCount++] = (float) distanceSq;
        }
        if (lookedAt || limit <= 0) return true;
        long now = Util.getMillis();
        if (distanceSq <= reachSq) {
            SHOWN_UNTIL.put(id, now + HOLD_MS);
            return true;
        }
        return SHOWN_UNTIL.get(id) > now;
    }

    /** First ask of a frame: last frame's distances set this frame's reach. */
    private static void roll() {
        int now = VillagerFrameStats.frame();
        if (now == frame) return;
        frame = now;
        int keep = limit;
        if (keep <= 0 || wantedCount <= keep) {
            reachSq = Double.MAX_VALUE;
        } else {
            Arrays.sort(wanted, 0, wantedCount);
            reachSq = wanted[keep - 1];
        }
        wantedCount = 0;
        // Villagers that left: their entries only age, so a size cap is enough.
        if (COUNTED.size() > 4096) COUNTED.clear();
        if (SHOWN_UNTIL.size() > 4096) SHOWN_UNTIL.clear();
    }
}
