package com.alex.test.client;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import software.bernie.geckolib.cache.object.GeoBone;

import javax.annotation.Nullable;
import java.util.WeakHashMap;

/**
 * Crowd stand-ins (30 FPS plan, round 2). A villager in the crowd budget's far ranks
 * ({@link DetailCull#crowdFar}: past the 160 that look biggest on screen) is drawn the
 * cheap way:
 * <ul>
 * <li>GeckoLib poses it only every {@link #POSE_EVERY}rd frame, spread by entity id. The
 *     frames between replay the last two poses kept for it, blended, so its limbs still move
 *     every frame; they run one posing behind (about a tenth of a second).</li>
 * <li>Faces turned away from the camera are left out ({@link CubeWriter#cullBack}; not with
 *     {@code /villagerfps faces off}).</li>
 * <li>Helmet and chest armor are a few plain boxes ({@link GuardArmorGeoLayer}).</li>
 * <li>A held tool is a flat cut-out of its icon ({@link FlatItem}); the shield stays.</li>
 * </ul>
 * Everything else is drawn as before (same bones, skin, render types and crowd batches),
 * so it works with and without shaders, and the alert glow still outlines it. Never for:
 * children, sleeping, sitting, riding or carrying, bards (instruments), raw limbs, and
 * anything not drawn by {@link NpcGeoRenderer} (the Mayor, merchants, nomads).
 *
 * <p>Poses are copied right after GeckoLib posed (the bones are shared by every villager)
 * and written back before this villager's bones are walked; nothing is
 * read back from the bones later. {@code /villagerfps standin on|off|show}. Render thread only.
 */
final class StandIn {

    /** {@code /villagerfps standin on|off}. */
    static volatile boolean enabled = true;
    /** {@code /villagerfps standin show}: the villager looked at gets a stand-in copy beside it. */
    static volatile boolean show;

    /** A stand-in is posed by GeckoLib every this many frames. */
    static final int POSE_EVERY = 3;
    /** The show copy stands this far to the right of the villager, in blocks. */
    static final float SHOW_OFFSET = 1.1f;

    /** Rotation, position and scale: nine numbers per bone. */
    private static final int FLOATS = 9;

    /** One villager's last two poses: every posed bone's nine numbers, and the frames they were made in. Also {@link PoseRate}'s (W8). */
    static final class Kept {
        final GeoBone[] bones;
        float[] older, newer;
        int olderFrame, newerFrame = Integer.MIN_VALUE;

        Kept(GeoBone[] bones) {
            this.bones = bones;
            this.older = new float[bones.length * FLOATS];
            this.newer = new float[bones.length * FLOATS];
        }
    }

    private static final WeakHashMap<NpcEntity, Kept> KEPT = new WeakHashMap<>();

    /** The villager being drawn as a stand-in now, or null. */
    @Nullable
    private static NpcEntity drawing;
    /** It is the show copy: posed as the original, no name. */
    private static boolean copy;
    /** It is drawn in the shader shadow pass (not counted, no back faces left out). */
    private static boolean shadow;

    private static int countFrame = Integer.MIN_VALUE;
    private static int standIns, posed, replayed;
    private static int lastStandIns, lastPosed, lastReplayed, lastCulled, lastFlat;

    private StandIn() {}

    /** {@code entity} is about to be drawn, after {@link DetailCull#begin}: a stand-in or not. */
    static void begin(NpcEntity entity) {
        roll();
        boolean on = enabled && DetailCull.crowdFar() && ordinary(entity);
        drawing = on ? entity : null;
        copy = false;
        shadow = on && IrisShadowPass.active();
        CubeWriter.cullBack = on && !shadow && SkinFaces.backFaces != SkinFaces.BackFaces.OFF;
        if (on && !shadow) standIns++;
    }

    /** The show copy of {@code entity} is about to be drawn: the stand-in look whatever its rank. */
    static void beginCopy(NpcEntity entity) {
        roll();
        drawing = entity;
        copy = true;
        shadow = IrisShadowPass.active();
        CubeWriter.cullBack = !shadow && SkinFaces.backFaces != SkinFaces.BackFaces.OFF;
    }

    static void end() {
        drawing = null;
        copy = false;
        shadow = false;
        CubeWriter.cullBack = false;
    }

    /** The villager being drawn wears the stand-in look (armor boxes, flat tool). */
    static boolean drawing() {
        return drawing != null;
    }

    /** The show copy is being drawn: no name or lead. */
    static boolean drawingCopy() {
        return copy;
    }

    /** Whether the villager looked at gets a stand-in copy beside it this frame. */
    static boolean showsCopyOf(NpcEntity entity) {
        return show && entity == VillagerLabels.lookedAt();
    }

    /** Its poses are kept and replayed: the stand-in being drawn, not the show copy. */
    static boolean keepsPoseOf(Object entity) {
        return entity == drawing && !copy;
    }

    /** Children, sleepers, sitters, riders, carriers, bards and the raw-limb view keep the full path. */
    private static boolean ordinary(NpcEntity npc) {
        Profession job = npc.getProfession();
        return job != Profession.CHILD && job != Profession.BARD
                && !npc.isSleeping() && !npc.isPassenger() && !npc.isVehicle()
                && !npc.isChairSeated() && !npc.hasRawLimbs();
    }

    /**
     * Writes this frame's pose of {@code entity} into {@code bones} from what was kept,
     * unless GeckoLib should pose it now: its turn, or nothing usable kept. True when
     * written, and GeckoLib is skipped.
     */
    static boolean replay(NpcEntity entity, GeoBone[] bones) {
        Kept kept = KEPT.get(entity);
        if (kept == null || kept.bones != bones) return false;
        int frame = VillagerFrameStats.frame();
        int since = frame - kept.newerFrame;
        // Drawn twice this frame (the shader shadow pass first): the same pose again.
        if (since != 0) {
            if (since < 0 || since >= 2 * POSE_EVERY) return false;      // gone a while: start over
            if (since >= POSE_EVERY && Math.floorMod(frame + entity.getId(), POSE_EVERY) == 0) return false;
        }
        write(kept, frame);
        if (!shadow) replayed++;
        return true;
    }

    /**
     * GeckoLib just posed {@code entity}: keep that pose, then show the blend from the one
     * before it, which reaches the new pose by its next turn.
     */
    static void keep(NpcEntity entity, GeoBone[] bones) {
        int frame = VillagerFrameStats.frame();
        Kept kept = KEPT.get(entity);
        if (kept == null || kept.bones != bones) {
            kept = new Kept(bones);
            KEPT.put(entity, kept);
        }
        boolean fresh = kept.newerFrame == Integer.MIN_VALUE || frame - kept.newerFrame >= 2 * POSE_EVERY
                || frame <= kept.newerFrame;
        if (!fresh) {
            float[] swap = kept.older;
            kept.older = kept.newer;
            kept.newer = swap;
            kept.olderFrame = kept.newerFrame;
        }
        read(bones, kept.newer);
        kept.newerFrame = frame;
        if (fresh) {
            // Nothing recent to blend from: show the new pose as it is.
            System.arraycopy(kept.newer, 0, kept.older, 0, kept.newer.length);
            kept.olderFrame = frame - POSE_EVERY;
        }
        write(kept, frame);
        if (!shadow) posed++;
    }

    static void read(GeoBone[] bones, float[] into) {
        for (int i = 0, k = 0; i < bones.length; i++, k += FLOATS) {
            GeoBone bone = bones[i];
            into[k] = bone.getRotX();
            into[k + 1] = bone.getRotY();
            into[k + 2] = bone.getRotZ();
            into[k + 3] = bone.getPosX();
            into[k + 4] = bone.getPosY();
            into[k + 5] = bone.getPosZ();
            into[k + 6] = bone.getScaleX();
            into[k + 7] = bone.getScaleY();
            into[k + 8] = bone.getScaleZ();
        }
    }

    /** The pose {@code frame} shows: from the older kept pose at the newer one's frame to the newer one a turn later. */
    static void write(Kept kept, int frame) {
        int span = kept.newerFrame - kept.olderFrame;
        float t = span <= 0 ? 1f : Math.min(1f, (frame - kept.newerFrame) / (float) span);
        float[] a = kept.older, b = kept.newer;
        GeoBone[] bones = kept.bones;
        for (int i = 0, k = 0; i < bones.length; i++, k += FLOATS) {
            GeoBone bone = bones[i];
            bone.setRotX(turn(a[k], b[k], t));
            bone.setRotY(turn(a[k + 1], b[k + 1], t));
            bone.setRotZ(turn(a[k + 2], b[k + 2], t));
            bone.setPosX(a[k + 3] + (b[k + 3] - a[k + 3]) * t);
            bone.setPosY(a[k + 4] + (b[k + 4] - a[k + 4]) * t);
            bone.setPosZ(a[k + 5] + (b[k + 5] - a[k + 5]) * t);
            bone.setScaleX(a[k + 6] + (b[k + 6] - a[k + 6]) * t);
            bone.setScaleY(a[k + 7] + (b[k + 7] - a[k + 7]) * t);
            bone.setScaleZ(a[k + 8] + (b[k + 8] - a[k + 8]) * t);
            // The setters mark the bone "animated this frame". GeckoLib clears that at the end of
            // its posing; left on, the next villager it poses would skip easing those bones back
            // and wear this one's pose on every bone its own clips leave alone.
            bone.resetStateChanges();
        }
    }

    /** A turn that jumped by more than half a circle (a spin wrapping round) switches halfway instead of spinning back. */
    private static float turn(float a, float b, float t) {
        return Math.abs(b - a) > Math.PI ? (t < 0.5f ? a : b) : a + (b - a) * t;
    }

    /** A new frame: last frame's counts are kept for the reports. */
    private static void roll() {
        int frame = VillagerFrameStats.frame();
        if (frame == countFrame) return;
        countFrame = frame;
        lastStandIns = standIns;
        lastPosed = posed;
        lastReplayed = replayed;
        lastCulled = CubeWriter.culled;
        lastFlat = FlatItem.drawn;
        standIns = posed = replayed = 0;
        CubeWriter.culled = 0;
        FlatItem.drawn = 0;
    }

    /** For {@code /villagerfps}. */
    static String report() {
        if (!enabled)
            return "Crowd stand-ins: off, far crowd villagers are drawn like the rest (/villagerfps standin on)"
                    + (show ? "; show copy on" : "");
        return "Crowd stand-ins: on. Villagers past the crowd budget's middle ranks are posed every "
                + POSE_EVERY + "rd frame (blended in between), skip faces turned away, wear plain helmet and chest "
                + "boxes and hold flat tools. Last frame: " + lastStandIns + " stand-ins, " + lastPosed + " posed, "
                + lastReplayed + " replayed, " + lastCulled + " faces skipped, " + lastFlat + " flat tools"
                + (show ? "; show copy on (look at a villager)" : "") + " (/villagerfps standin off|show)";
    }

    /** The same on one line, for the {@code [ClientCost]} log. */
    static String compact() {
        if (!enabled) return "stand-ins off";
        return "stand-ins " + lastStandIns + " (posed " + lastPosed + ", replayed " + lastReplayed
                + ", back faces " + lastCulled + ", flat tools " + lastFlat + ")";
    }
}
