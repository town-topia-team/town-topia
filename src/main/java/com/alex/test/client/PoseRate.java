package com.alex.test.client;

import com.alex.test.entity.NpcEntity;
import net.minecraft.client.Minecraft;
import software.bernie.geckolib.cache.object.GeoBone;

import java.util.WeakHashMap;

/**
 * Plan W8 (docs/plans/2026-10-22-hundred-villagers-fps.md): on a fast screen a villager is not posed by
 * GeckoLib every frame. From twice {@link #cap} frames a second each villager is posed on 1 frame in 2
 * (1 in 3 from three times, and so on), so it still gets at least about {@link #cap} poses a second,
 * spread by entity id so every frame poses a share of the crowd. The frames between blend its last two
 * poses, the way far stand-ins do ({@link StandIn}, whose kept poses this uses), so its limbs still move
 * every frame; they run one posing behind (at most 1 / cap of a second, a sixtieth by default). Below
 * twice {@link #cap} frames a second nothing changes: every villager is posed every frame.
 *
 * <p>Only in the world's entity pass ({@link CrowdBatching#inWorldEntity}): a portrait (the inventory
 * turns the head towards the mouse) is posed by GeckoLib as before. Not the far stand-ins (they keep
 * their own rate) and not the raw-limb view. {@code /villagerfps poserate off|<per second>}.
 * Render thread only.
 */
final class PoseRate {

    /** {@code /villagerfps poserate}: the fewest poses a second a villager gets; 0 = off (every frame). */
    static volatile int cap = 60;

    /** The most frames one posing serves. */
    private static final int MOST = 8;

    private static final WeakHashMap<NpcEntity, StandIn.Kept> KEPT = new WeakHashMap<>();

    /** Frames one posing serves now; 1 = every frame (W8 idle). */
    private static int every = 1;
    private static int rolledFrame = Integer.MIN_VALUE;
    private static int posed, replayed, lastPosed, lastReplayed;

    private PoseRate() {}

    /** {@code npc} is posed at this rate now (world pass, fast enough screen), not by GeckoLib every frame. */
    static boolean paces(NpcEntity npc) {
        roll();
        return every > 1 && CrowdBatching.inWorldEntity() && !npc.hasRawLimbs();
    }

    /**
     * Writes this frame's pose of {@code entity} into {@code bones} from what was kept, unless GeckoLib
     * should pose it now: its turn, or nothing usable kept. True when written, and GeckoLib is skipped.
     * As {@link StandIn#replay}, with {@link #every} for its rate.
     */
    static boolean replay(NpcEntity entity, GeoBone[] bones) {
        StandIn.Kept kept = KEPT.get(entity);
        if (kept == null || kept.bones != bones) return false;
        int frame = VillagerFrameStats.frame();
        int since = frame - kept.newerFrame;
        // Drawn twice this frame (the shader shadow pass first): the same pose again.
        if (since != 0) {
            if (since < 0 || since >= 2 * every) return false;           // gone a while: start over
            if (since >= every && Math.floorMod(frame + entity.getId(), every) == 0) return false;
        }
        StandIn.write(kept, frame);
        if (!IrisShadowPass.active()) replayed++;
        return true;
    }

    /**
     * GeckoLib just posed {@code entity}: keep that pose, then show the blend from the one before it,
     * which reaches the new pose by its next turn. As {@link StandIn#keep}, with {@link #every}.
     */
    static void keep(NpcEntity entity, GeoBone[] bones) {
        int frame = VillagerFrameStats.frame();
        StandIn.Kept kept = KEPT.get(entity);
        if (kept == null || kept.bones != bones) {
            kept = new StandIn.Kept(bones);
            KEPT.put(entity, kept);
        }
        boolean fresh = kept.newerFrame == Integer.MIN_VALUE || frame - kept.newerFrame >= 2 * every
                || frame <= kept.newerFrame;
        if (!fresh) {
            float[] swap = kept.older;
            kept.older = kept.newer;
            kept.newer = swap;
            kept.olderFrame = kept.newerFrame;
        }
        StandIn.read(bones, kept.newer);
        kept.newerFrame = frame;
        if (fresh) {
            // Nothing recent to blend from: show the new pose as it is.
            System.arraycopy(kept.newer, 0, kept.older, 0, kept.newer.length);
            kept.olderFrame = frame - every;
        }
        StandIn.write(kept, frame);
        if (!IrisShadowPass.active()) posed++;
    }

    /** A new frame: last frame's counts kept for the reports, and the rate set from the frame rate. */
    private static void roll() {
        int frame = VillagerFrameStats.frame();
        if (frame == rolledFrame) return;
        rolledFrame = frame;
        lastPosed = posed;
        lastReplayed = replayed;
        posed = replayed = 0;
        every = everyFor(cap, Minecraft.getInstance().getFps(), every);
    }

    /**
     * Frames one posing serves at {@code fps} frames a second, given {@code now}: as many as keep at least
     * {@code cap} poses a second. It steps up only 5% past the line and down 3% under it, so a frame rate
     * sitting on the line does not flip it every second.
     */
    static int everyFor(int cap, int fps, int now) {
        if (cap <= 0) return 1;
        int n = Math.max(1, Math.min(MOST, now));
        while (n < MOST && fps >= cap * (n + 1) * 1.05f) n++;
        while (n > 1 && fps < cap * n * 0.97f) n--;
        return n;
    }

    /** For {@code /villagerfps}. */
    static String report() {
        int fps = Minecraft.getInstance().getFps();
        if (cap <= 0)
            return "Posing rate: every villager posed every frame (/villagerfps poserate 60 to pose at least 60 times"
                    + " a second instead, on fast screens)";
        return "Posing rate: at least " + cap + " a second. At " + fps + " FPS each villager is posed "
                + (every == 1 ? "every frame (1 frame in 2 from " + (int) Math.ceil(cap * 2 * 1.05f) + " FPS)"
                        : "on 1 frame in " + every + ", blended between")
                + ". Last frame: " + lastPosed + " posed, " + lastReplayed + " blended"
                + " (/villagerfps poserate off|<per second>)";
    }

    /** The same on one line, for the {@code [ClientCost]} log. */
    static String compact() {
        if (cap <= 0) return "pose rate off";
        return "pose 1 frame in " + every + " (posed " + lastPosed + ", blended " + lastReplayed + ")";
    }
}
