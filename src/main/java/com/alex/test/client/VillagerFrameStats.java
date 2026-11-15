package com.alex.test.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * What the villager renderer did last frame, for {@code /villagerfps}: how many
 * villagers were drawn and how far away they were, how many wore armor or showed
 * a name, and how many quads their bodies and armor came to.
 *
 * <p>Counted on the render thread while drawing and copied aside when the next
 * frame starts, so the report always describes one whole frame. The distance
 * bands are the tiers the limb and armor detail steps are planned around.
 * The mayor and travellers are drawn by renderers of their own and are not
 * counted, except that a bent limb of the mayor adds to the body quads.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VillagerFrameStats {

    /** Distance bands in blocks. */
    static final double NEAR = 12, MID = 28, FAR = 64;

    /** How a limb was drawn; see {@link BentLimb#smoothPx}. */
    enum Limb { SMOOTH, MEDIUM, SIMPLE, BLOCKY, STRAIGHT }

    private static int frame;
    private static int drawn, near, mid, far, armored, named, bodyQuads, armorQuads;
    private static int lastDrawn, lastNear, lastMid, lastFar, lastArmored, lastNamed, lastBodyQuads, lastArmorQuads;
    private static final int[] limbs = new int[Limb.values().length];
    private static final int[] lastLimbs = new int[Limb.values().length];
    private static int detailBones, detailExtras, lastDetailBones, lastDetailExtras;
    private static int armorTrimmed, armorStiff, lastArmorTrimmed, lastArmorStiff;
    /** Villagers drawn inside chunk sections the camera cannot see (counted only while the cost log is on). */
    private static int unseen, lastUnseen;
    /** Faces written for arms and legs (bent or not), apart from {@link #bodyQuads}: real counts (plan W0). */
    private static int limbQuads, lastLimbQuads;
    /** Faces with no width left out, and bones squashed to nothing left out (plan W1). */
    private static int flatFaces, squashedBones, lastFlatFaces, lastSquashedBones;
    /** Faces the skin leaves empty (W4) and closed boxes' back faces (W5) left out ({@link SkinFaces}). */
    private static int emptyFaces, backFaces, lastEmptyFaces, lastBackFaces;

    private VillagerFrameStats() {}

    /** A villager was drawn although every section its body touches is out of the camera's sight. */
    static void unseen() {
        unseen++;
    }

    /** A small bone (eye, nose, hat) was left out because the villager is small on screen. */
    static void detailBone() {
        detailBones++;
    }

    /** A held tool, shield, cape or instrument was left out for the same reason. */
    static void detailExtra() {
        detailExtras++;
    }

    /** A limb (arm or leg, both halves) was drawn {@code how}. */
    static void limb(Limb how) {
        limbs[how.ordinal()]++;
    }

    /** A frame number that changes once per frame; for "once per villager per frame" checks. */
    static int frame() {
        return frame;
    }

    /** A villager is being drawn, {@code distanceSq} blocks squared from the camera. */
    static void villager(double distanceSq) {
        drawn++;
        if (distanceSq < NEAR * NEAR) near++;
        else if (distanceSq < MID * MID) mid++;
        else if (distanceSq < FAR * FAR) far++;
    }

    /** A villager wears armor; {@code trimmed}: its trims were left out, {@code stiff}: its limb pieces drawn stiff. */
    static void armored(boolean trimmed, boolean stiff) {
        armored++;
        if (trimmed) armorTrimmed++;
        if (stiff) armorStiff++;
    }

    static void named() {
        named++;
    }

    /** Faces written for the body's boxes (head, torso, hats; not arms and legs). */
    static void bodyQuads(int quads) {
        bodyQuads += quads;
    }

    /** Faces written for an arm or a leg. */
    static void limbQuads(int quads) {
        limbQuads += quads;
    }

    /** A face with no width was left out ({@link CubeWriter#skipFlat}). */
    static void flatFace() {
        flatFaces++;
    }

    /** A face with nothing painted on it was left out ({@link SkinFaces#skipEmpty}). */
    static void emptyFace() {
        emptyFaces++;
    }

    /** A closed box's face turned away from the camera was left out ({@link SkinFaces#backFaces}). */
    static void backFace() {
        backFaces++;
    }

    /** A bone squashed to nothing was left out ({@link CubeWriter#squashed}). */
    static void squashedBone() {
        squashedBones++;
    }

    static void armorQuads(int quads) {
        armorQuads += quads;
    }

    @SubscribeEvent
    public static void frameStart(RenderFrameEvent.Pre event) {
        frame++;
        lastDrawn = drawn;
        lastNear = near;
        lastMid = mid;
        lastFar = far;
        lastArmored = armored;
        lastNamed = named;
        lastBodyQuads = bodyQuads;
        lastArmorQuads = armorQuads;
        System.arraycopy(limbs, 0, lastLimbs, 0, limbs.length);
        java.util.Arrays.fill(limbs, 0);
        lastDetailBones = detailBones;
        lastDetailExtras = detailExtras;
        lastArmorTrimmed = armorTrimmed;
        lastArmorStiff = armorStiff;
        armorTrimmed = armorStiff = 0;
        lastUnseen = unseen;
        unseen = 0;
        lastLimbQuads = limbQuads;
        lastFlatFaces = flatFaces;
        lastSquashedBones = squashedBones;
        lastEmptyFaces = emptyFaces;
        lastBackFaces = backFaces;
        limbQuads = flatFaces = squashedBones = emptyFaces = backFaces = 0;
        drawn = near = mid = far = armored = named = bodyQuads = armorQuads = detailBones = detailExtras = 0;
    }

    /** The last whole frame on one line, for the {@code [ClientCost]} log. */
    static String compact() {
        return "Last frame: villagers drawn " + lastDrawn + " (within " + (int) NEAR + " blocks " + lastNear
                + ", to " + (int) MID + " " + lastMid + ", to " + (int) FAR + " " + lastFar + "), armored " + lastArmored
                + ", named " + lastNamed + "; quads body " + (lastBodyQuads + lastLimbQuads)
                + " (boxes " + lastBodyQuads + ", arms and legs " + lastLimbQuads + "), armor " + lastArmorQuads
                + "; left out: flat faces " + lastFlatFaces + ", squashed bones " + lastSquashedBones
                + ", empty faces " + lastEmptyFaces + ", back faces " + lastBackFaces
                + "; limbs 12/6/3 strips a half/blocky/straight " + lastLimbs[Limb.SMOOTH.ordinal()]
                + "/" + lastLimbs[Limb.MEDIUM.ordinal()] + "/" + lastLimbs[Limb.SIMPLE.ordinal()]
                + "/" + lastLimbs[Limb.BLOCKY.ordinal()] + "/" + lastLimbs[Limb.STRAIGHT.ordinal()]
                + "; left out for size " + lastDetailBones + " bones, " + lastDetailExtras + " extras"
                + "; armor trims off " + lastArmorTrimmed + ", stiff " + lastArmorStiff
                + "; in unseen sections " + lastUnseen + " (camera sees " + SectionVisibility.sectionsVisible() + ")";
    }

    /** Four lines about the last whole frame. */
    static String[] report() {
        int beyond = lastDrawn - lastNear - lastMid - lastFar;
        return new String[] {
                "Villagers drawn last frame: " + lastDrawn
                        + " (within " + (int) NEAR + " blocks: " + lastNear
                        + ", " + (int) NEAR + " to " + (int) MID + ": " + lastMid
                        + ", " + (int) MID + " to " + (int) FAR + ": " + lastFar
                        + ", beyond: " + beyond + "); in chunk sections the camera cannot see: " + lastUnseen
                        + " (counted while the cost log is on)",
                "Wearing armor: " + lastArmored + " (trims left out on " + lastArmorTrimmed
                        + ", stiff on " + lastArmorStiff + "), showing a name: " + lastNamed
                        + ". Quads written: body " + (lastBodyQuads + lastLimbQuads) + " (boxes " + lastBodyQuads
                        + ", arms and legs " + lastLimbQuads + "), armor " + lastArmorQuads
                        + ". Left out: " + lastFlatFaces + " faces with no width, " + lastSquashedBones
                        + " bones squashed to nothing, " + lastEmptyFaces + " faces with nothing painted on them, "
                        + lastBackFaces + " back faces of closed boxes",
                "Limbs drawn (strips across the bend for each half, twice that a limb): 12 strips "
                        + lastLimbs[Limb.SMOOTH.ordinal()]
                        + ", 6 strips " + lastLimbs[Limb.MEDIUM.ordinal()]
                        + ", 3 strips " + lastLimbs[Limb.SIMPLE.ordinal()]
                        + ", blocky " + lastLimbs[Limb.BLOCKY.ordinal()]
                        + ", straight " + lastLimbs[Limb.STRAIGHT.ordinal()],
                "Left out for size: " + lastDetailBones + " small bones, " + lastDetailExtras
                        + " held items, shields, capes or instruments"
        };
    }
}
