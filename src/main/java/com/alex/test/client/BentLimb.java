package com.alex.test.client;

import com.alex.test.ClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.Direction;
import org.joml.AxisAngle4f;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.renderer.GeoRenderer;

import java.util.Map;

/**
 * Draws an arm or a leg as one continuously curving surface instead of two boxes
 * hinged at a point.
 *
 * <h3>Why not just cut the limb up</h3>
 * A chain of rigid boxes never stops looking like a chain of rigid boxes. Slicing
 * a limb and spreading the joint angle over the slices helps the silhouette, but
 * each slice is still flat-shaded from a single normal, so every seam shows as a
 * brightness step — and the boxes only meet along one edge, so the wedge that
 * opens on the outside of every kink has to be papered over with overhang. What
 * you get is a segmented tube, not an arm.
 *
 * <h3>What this does instead</h3>
 * The same thing MoBends does: deform. The limb is subdivided along its length at
 * draw time and a rotation is swept along it, so
 *
 * <ul>
 *   <li>positions are shared between neighbouring bands — the surface is closed,
 *       with no wedges to fill and no caps to hide;</li>
 *   <li>normals rotate with the sweep and are written <em>per vertex</em>, so the
 *       shader interpolates them across each band and the shading is smooth
 *       rather than faceted. This is the part slicing cannot buy at any density.</li>
 * </ul>
 *
 * <h3>How the angle is spread</h3>
 * The whole joint angle nominally happens at one point. {@link #bendFraction}
 * spreads it over a zone either side with a smootherstep, whose
 * derivative vanishes at both ends — so the curvature eases in and out and there
 * is no visible kink where the bend zone begins. The fraction reaches 1 by the
 * bottom of the zone, which is what keeps the far end of the limb exactly where
 * the animation put it; only the shape in between changes.
 *
 * <p>Both halves are drawn here, in the upper bone's space, from one sweep — that
 * is what welds them. The lower bone still carries the animation (its rotation is
 * the joint angle read below) and still positions the held-item layer; it just
 * does not draw its own cubes. A side effect worth knowing about: the sweep
 * attaches the forearm at the elbow geometrically, so {@code bone2}'s pivot
 * sitting at the shoulder rather than the elbow stops mattering, and the right
 * forearm no longer swings away from the upper arm in the older hand-authored
 * clips.
 */
final class BentLimb {

    /** upper bone -> lower bone. The lower bone's cubes are drawn by the upper one. */
    private static final Map<String, String> LIMBS = Map.of(
            "left_arm", "bone",
            "right_arm", "bone2",
            "left_leg", "left_shin",
            "right_leg", "right_shin");

    /** Both halves of every limb, for the cost log's split of limbs from the rest of the body. */
    private static final java.util.Set<String> LIMB_BONES = java.util.Set.of(
            "left_arm", "bone", "right_arm", "bone2", "left_leg", "left_shin", "right_leg", "right_shin");

    /** {@code name} is an upper or lower half of an arm or leg. */
    static boolean isLimbBone(String name) {
        return LIMB_BONES.contains(name);
    }

    /**
     * Narrowest the bend may be, in model pixels. A limb half is 6px, so 1 leaves 5px of
     * straight, hard-edged limb either side and turns the corner over the middle 2. Any
     * wider by default and the limb stops reading as having a skeleton -- spread the
     * angle over the whole limb and you get a rubber hose that bends from hip to ankle.
     */
    private static final float BEND_ZONE_MIN = 1f;

    /**
     * The bend cannot be arbitrarily tight. Turning an angle over an arc length shorter
     * than the limb's half-width drives the radius negative on the inside of the bend,
     * and the inner surface folds through itself -- which is what makes a hard fold read
     * as a smooth hose with no corner in it, exactly where a corner matters most. So the
     * zone grows with the angle to keep the inner radius positive, with a little margin.
     * A 40 degree knee still turns in the minimum 2px; a 127 degree elbow needs 5.5.
     *
     * The margin is not free tuning room. Shaving it to 1.1 tightened the corner but put
     * a crushed concave notch in the front of a 70 degree knee, where the inner surface
     * had begun to fold through itself -- a shape the base model could not make. A softer
     * corner is the better trade: it reads as a bend, not as a defect.
     */
    private static final float BEND_MARGIN = 1.2f;

    /**
     * Bands across the bend zone -- not across the whole limb -- for EACH half: {@link Sweep#cut}
     * cuts each box's own share of the zone (upper above the joint, lower below), so a limb has
     * twice this many strips across the bend (plan BF6). Outside the zone the sweep's rotation is
     * constant, so consecutive bands there are coplanar and cost quads for nothing; a straight limb
     * emits one band a face. Normals are written per vertex, so bands buy silhouette rather than
     * shading; 12 a half leave the strips 0.002-0.014 model px off the true curve (12 px limb,
     * 4 px wide, 30-130 degree bends, measured 10-22: table in the hundred-villagers plan).
     */
    private static final int BANDS = 12;

    /**
     * Detail by size on screen ({@code /villagerfps limbs}). Every band edge costs a
     * quaternion build and four vertices, for every limb of every villager in view,
     * every frame, and a crowd on screen is where that adds up; but a fold a few pixels
     * across is drawn as well by three bands as by twelve. So each half of a limb is cut into
     * {@link #BANDS} when it stands at least {@link #smoothPx} pixels tall on screen,
     * six bands from {@link #mediumPx}, three from {@link #simplePx} (twice that across the
     * whole bend), and below that it
     * is drawn the Blocky way (two rigid halves hinged at the joint,
     * {@link #renderHinged}), which costs the same as an ordinary bone. Each step
     * changes the picture by well under a pixel where it happens. Height on screen is
     * what a spyglass or a zoom mod changes, so zooming in restores the detail by
     * itself. Armor on the limb follows whichever shape the limb got
     * ({@link #renderWorn}). With a 1080p window and the default field of view, 48 px
     * is about 12 blocks away and 10 px about 58.
     */
    static volatile float smoothPx = 48f;
    static volatile float mediumPx = 24f;
    static volatile float simplePx = 10f;

    /** Bands per half at each tier below {@link #BANDS}; the last tier is the hinge. */
    private static final int MEDIUM_BANDS = 6;
    private static final int SIMPLE_BANDS = 3;
    static final int TIER_SMOOTH = 0, TIER_MEDIUM = 1, TIER_SIMPLE = 2, TIER_BLOCKY = 3;

    /**
     * A limb steps to finer detail only once it is this much taller than the tier
     * asks, so one walking right at a threshold does not flicker between two looks.
     */
    private static final float DEAD_BAND = 1.25f;

    /** A villager limb top to bottom in blocks (6 px upper, 6 px lower). */
    private static final float LIMB_HEIGHT = 12f / 16f;

    // A cache of swept limb shapes keyed by the rounded joint angle was tried here
    // (2026-10-17) and taken out again: the animations move every joint by degrees
    // per frame, so a shape almost never came round twice, and building each one as
    // a mesh to keep made tens of kilobytes of garbage per limb per frame. The
    // collector's pauses showed as stutter. Limbs are written straight to the frame.

    /**
     * How many screen pixels one block spans {@code distance} blocks away, under the
     * projection in effect: the window height over twice the tangent of half the field
     * of view, which the projection matrix carries in its second diagonal entry. A
     * spyglass or a zoom mod narrows the field of view, and this grows with it.
     */
    static float pixelsPerBlock(float distance) {
        float m11 = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix().m11();
        int height = net.minecraft.client.Minecraft.getInstance().getWindow().getHeight();
        return height * m11 * 0.5f / distance;
    }

    /** Steps used to integrate the centreline, over the whole limb. */
    private static final int STEPS = 64;

    /** Below this the limb is straight enough to draw as-is (radians). */
    private static final float MIN_ANGLE = 0.02f;

    private static final float PX = 16f;   // model pixels per block

    /** Where a point rigidly attached to a limb ends up once the limb is deformed. */
    record Grip(Vector3f position, Quaternionf rotation) {}

    /** How a limb was drawn this frame: a smooth {@link Sweep} or a blocky {@link Hinge}. */
    private interface Shape {
        /** Where a point on the lower half, given in rest-pose model space (blocks), was drawn. */
        Grip grip(Vector3f rest);
    }

    /**
     * The shape each limb was drawn with this frame, keyed by its upper bone.
     *
     * <p>A held item hangs off the forearm, but the forearm's *bone* is not where the
     * forearm is drawn any more -- the sweep or the hinge is. Without this the axe keeps
     * the pose the bone would have had, and drifts out of the hand by up to 2.7px at the
     * top of a chop, which on a 4px arm reads as the villager having dropped it.
     */
    private static final Map<String, Shape> LAST_SHAPE = new java.util.HashMap<>();

    /**
     * {@code /villagerfps limbmath new|old} (plan W3, docs/plans/2026-10-22-hundred-villagers-fps.md): the
     * same limb shape with less work. Each limb's boxes are measured once per baked bone instead of every
     * frame; walking the curve down the limb takes a sine and cosine only inside the bend zone (above it
     * nothing has turned, below it everything has turned by the whole angle, worked out once); the four sides
     * of a box share one turn per band height instead of building their own; the sweep, the writer and the
     * joint's scratch are kept instead of made per limb. Far blocky limbs (plan BF4) keep one hinge per limb
     * name, measure each joint cap once per baked box, and turn the lower half (and worn pieces) on the stack's
     * own frame, set back after, instead of a pushed copy. Every vertex comes out of the same sums as before,
     * so nothing on screen moves. {@code old} runs the earlier maths, to compare.
     */
    static volatile boolean fastMaths = true;

    /**
     * Plan W3: each limb's measured rest shape, per upper bone. By identity: GeckoLib's {@code GeoBone.equals}
     * compares names, and the villager and nomad rigs share bone names. Render thread only.
     */
    private static final java.util.IdentityHashMap<GeoBone, Extent> EXTENTS = new java.util.IdentityHashMap<>();
    /** "Measured, and not a limb" in {@link #EXTENTS}. */
    private static final Extent NO_EXTENT = new Extent(0f, 0f, 0f, 0f, 0f, 0f);
    /**
     * Plan W3: one sweep per limb name, set again for every limb drawn. So the {@link Shape} in
     * {@link #LAST_SHAPE} changes when the next villager draws that limb: read it while the villager is drawn
     * (its layers run right after the bone), never keep it.
     */
    private static final Map<String, Sweep> SWEEPS = new java.util.HashMap<>();
    private static final LimbWriter WRITER = new LimbWriter(true);
    private static final Quaternionf JOINT = new Quaternionf();
    private static final AxisAngle4f JOINT_AXIS = new AxisAngle4f();
    /** Plan BF4: one hinge per limb name, set again for every blocky limb drawn (the rule of {@link #SWEEPS}). */
    private static final Map<String, Hinge> HINGES = new java.util.HashMap<>();
    /**
     * Plan BF4: each blocky limb box's joint cap, measured once per baked box. By identity, like {@link #EXTENTS};
     * a reload's old boxes go once there are 256. Render thread only.
     */
    private static final java.util.IdentityHashMap<GeoCube, Cap> CAPS = new java.util.IdentityHashMap<>();
    /** Plan BF4 scratch: the frame kept under a hinge's turn (limb, then worn pieces) and a cap's normal and corner. */
    private static final Matrix4f HINGE_POSE = new Matrix4f(), WORN_POSE = new Matrix4f();
    private static final Matrix3f HINGE_NORMAL = new Matrix3f(), WORN_NORMAL = new Matrix3f();
    private static final Vector3f CAP_NORMAL = new Vector3f();
    private static final Vector4f CAP_CORNER = new Vector4f();

    private BentLimb() {}

    /** For the {@code [ClientCost]} log. */
    static String state() {
        return fastMaths ? "limb maths new" : "limb maths old";
    }

    /**
     * Decides, per bone, whether {@link BentLimb} draws it. One per renderer, since it
     * remembers across the two calls that a lower half was already drawn by its upper
     * one — a straight joint falls back to the plain path, and then the lower bone has
     * to draw itself as usual.
     */
    static final class Dispatcher {
        private final java.util.Set<String> drawnByUpper = new java.util.HashSet<>();
        /** Draws the cubes of a blocky limb, the way the renderer draws any other cube. */
        private final GeoRenderer<?> renderer;
        /**
         * The detail tier each limb of each villager was drawn at last, for the dead
         * band. Keyed by the entity itself (identity); forgotten with it. Render thread only.
         */
        private final java.util.WeakHashMap<Object, byte[]> lastTier = new java.util.WeakHashMap<>();
        private static final java.util.List<String> LIMB_ORDER =
                java.util.List.of("left_arm", "right_arm", "left_leg", "right_leg");
        private static final Vector3f SCALE = new Vector3f();

        Dispatcher(GeoRenderer<?> renderer) {
            this.renderer = renderer;
        }

        /** The finest tier {@code limbPx} earns outright, {@link #TIER_SMOOTH} to {@link #TIER_BLOCKY}. */
        private static int tierEarned(float limbPx) {
            if (limbPx >= smoothPx) return TIER_SMOOTH;
            if (limbPx >= mediumPx) return TIER_MEDIUM;
            if (limbPx >= simplePx) return TIER_SIMPLE;
            return TIER_BLOCKY;
        }

        /** Pixels of limb height a tier asks for. */
        private static float askedPx(int tier) {
            return tier == TIER_SMOOTH ? smoothPx : tier == TIER_MEDIUM ? mediumPx : simplePx;
        }

        /**
         * The detail tier for this limb this frame, from how tall it is on screen, with
         * the dead band: it gets coarser the moment the height says so, and finer only
         * once the height is {@link #DEAD_BAND} times what the finer tier asks. In a
         * crowd the villager's rank on screen may cap it coarser still
         * ({@link DetailCull#limbTierCap}); the dead band remembers the height's tier only.
         */
        private int tierFor(String limb, float distance, float scale) {
            if (distance <= 0f)
                return TIER_SMOOTH;                 // not in the world (a portrait): full detail
            float limbPx = pixelsPerBlock(distance) * LIMB_HEIGHT * scale;
            int earned = tierEarned(limbPx);
            int cap = DetailCull.limbTierCap();
            Object who = renderer.getAnimatable();
            int index = LIMB_ORDER.indexOf(limb);
            if (who == null || index < 0)
                return Math.max(earned, cap);
            byte[] last = lastTier.computeIfAbsent(who, k -> new byte[LIMB_ORDER.size()]);
            int tier = earned;
            if (earned < last[index]) {
                tier = last[index];
                while (tier > earned && limbPx >= askedPx(tier - 1) * DEAD_BAND)
                    tier--;
            }
            last[index] = (byte) tier;
            return Math.max(tier, cap);
        }

        /** @return true if the bone has been drawn and the caller should do nothing more */
        boolean handled(PoseStack poseStack, GeoBone bone, VertexConsumer buffer,
                        int packedLight, int packedOverlay, int colour) {
            // No rig check: both rigs are run through tools/bendify_model.py, so both
            // carry an elbow and a knee under the names below. A rig that does not
            // falls out at the childNamed() lookup and draws the ordinary way.
            if (drawnByUpper.remove(bone.getName()))
                return true;

            String lower = LIMBS.get(bone.getName());
            if (lower == null)
                return false;
            // A new limb: forget what the last one drawn left behind (plan BF2, BF3). Its lower half
            // may never have come here (squashed to nothing, or cut for size) and would skip the next
            // villager's straight forearm; a hidden upper must not lend its held item the last
            // villager's arm shape.
            drawnByUpper.remove(lower);
            LAST_SHAPE.remove(bone.getName());
            if (bone.isHidden())
                return false;

            Matrix4f pose = poseStack.last().pose();
            float scale = pose.getScale(SCALE).y();
            float distance = distanceOf(pose, scale);
            int tier = tierFor(bone.getName(), distance, scale);
            boolean blocky = ClientConfig.blockyLimbs() || tier == TIER_BLOCKY;
            int bands = tier == TIER_SMOOTH ? BANDS : tier == TIER_MEDIUM ? MEDIUM_BANDS : SIMPLE_BANDS;
            boolean drawn = blocky
                    ? renderHinged(renderer, poseStack, bone, buffer, packedLight, packedOverlay, colour)
                    : render(poseStack, bone, buffer, packedLight, packedOverlay, colour, bands);
            if (!drawn) {
                VillagerFrameStats.limb(VillagerFrameStats.Limb.STRAIGHT);
                return false;                       // straight enough; both halves draw normally
            }
            VillagerFrameStats.limb(blocky ? VillagerFrameStats.Limb.BLOCKY
                    : tier == TIER_SMOOTH ? VillagerFrameStats.Limb.SMOOTH
                    : tier == TIER_MEDIUM ? VillagerFrameStats.Limb.MEDIUM : VillagerFrameStats.Limb.SIMPLE);

            drawnByUpper.add(lower);
            return true;
        }
    }

    /**
     * The limb's rest shape: how far it runs, where the two halves meet, and where its
     * axis lies. Cube vertices are in model space (over 16) and both halves share that
     * one frame, so the joint is simply where the upper cubes stop.
     *
     * <p>{@code top} is the shoulder or hip end and {@code bottom} the hand or foot end
     * -- measured, not assumed, which is what lets a grip be placed "so far up from the
     * hand" without knowing which way the baked axes happen to run.
     */
    private record Extent(float top, float bottom, float joinY,
                          float centreX, float centreZ, float halfWidth) {}

    private static Extent extentOf(GeoBone upper, GeoBone lower) {
        float top = Float.NEGATIVE_INFINITY, bottom = Float.POSITIVE_INFINITY, joinY = Float.POSITIVE_INFINITY;
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (GeoCube cube : upper.getCubes()) {
            for (GeoQuad quad : cube.quads()) {
                if (quad == null)
                    continue;
                for (GeoVertex v : quad.vertices()) {
                    top = Math.max(top, v.position().y());
                    joinY = Math.min(joinY, v.position().y());
                    minX = Math.min(minX, v.position().x());
                    maxX = Math.max(maxX, v.position().x());
                    minZ = Math.min(minZ, v.position().z());
                    maxZ = Math.max(maxZ, v.position().z());
                }
            }
        }
        for (GeoCube cube : lower.getCubes())
            for (GeoQuad quad : cube.quads())
                if (quad != null)
                    for (GeoVertex v : quad.vertices())
                        bottom = Math.min(bottom, v.position().y());

        if (top <= joinY || joinY <= bottom)
            return null;

        return new Extent(top, bottom, joinY, (minX + maxX) * 0.5f, (minZ + maxZ) * 0.5f,
                          Math.max(maxX - minX, maxZ - minZ) * 0.5f);
    }

    /**
     * {@link #extentOf}, measured once per baked bone (plan W3): a bone's boxes never change. A resource
     * reload bakes new bones, which are measured afresh; the old bones' entries go once there are 32.
     */
    private static Extent measured(GeoBone upper, GeoBone lower) {
        Extent extent = EXTENTS.get(upper);
        if (extent == null) {
            if (EXTENTS.size() >= 32)
                EXTENTS.clear();
            extent = extentOf(upper, lower);
            EXTENTS.put(upper, extent == null ? NO_EXTENT : extent);
        }
        return extent == NO_EXTENT ? null : extent;
    }

    /** {@link #measured} with the new limb maths, {@link #extentOf} with the old. */
    private static Extent extent(GeoBone upper, GeoBone lower, boolean fast) {
        return fast ? measured(upper, lower) : extentOf(upper, lower);
    }

    /**
     * Where a point in the fist ends up this frame, given the limb's upper bone.
     *
     * <p>This exists because the obvious anchor is the wrong one. A held item used to
     * hang off the forearm *bone*, and on {@code base_villager} that bone's pivot is at
     * the shoulder, not the elbow -- so the item hinged 6.4px away from where the drawn
     * arm hinges and wandered up to 7.4px across a chop, nearly two arm-widths. Here the
     * anchor is a point on the limb's own axis near the hand end, carried through the
     * exact deformation the geometry was drawn with, so it cannot come apart from the
     * fist however the arm moves.
     *
     * @param up      pixels from the hand end back toward the elbow
     * @param side    pixels off the axis across the limb, and {@code forward} along z
     * @return where that point lands (blocks, model space) and how it is turned, or null
     *         if this bone is not a limb
     */
    static Grip handGrip(GeoBone upper, float up, float side, float forward) {
        GeoBone lower = childNamed(upper, LIMBS.get(upper.getName()));
        if (lower == null)
            return null;

        Extent extent = extent(upper, lower, fastMaths);
        if (extent == null)
            return null;

        Vector3f rest = new Vector3f(extent.centreX() + side / PX,
                                     extent.bottom() + up / PX,
                                     extent.centreZ() + forward / PX);

        // No shape means the joint was straight enough this frame that both halves drew
        // as plain boxes, and then the rest point is already where the hand is.
        Shape shape = LAST_SHAPE.get(upper.getName());
        return shape == null ? new Grip(rest, new Quaternionf()) : shape.grip(rest);
    }

    /**
     * The same point in the fist for a limb drawn as two rigid boxes (the
     * raw-limbs debug view): {@code poseStack} must hold {@code upper}'s frame;
     * it is moved on into the lower bone's frame and onto the point. Returns
     * false, leaving the stack untouched, if this bone is not a limb.
     */
    static boolean toRigidHandPoint(PoseStack poseStack, GeoBone upper, float up, float side, float forward) {
        GeoBone lower = childNamed(upper, LIMBS.get(upper.getName()));
        if (lower == null)
            return false;
        Extent extent = extent(upper, lower, fastMaths);
        if (extent == null)
            return false;
        software.bernie.geckolib.util.RenderUtil.prepMatrixForBone(poseStack, lower);
        poseStack.translate(extent.centreX() + side / PX, extent.bottom() + up / PX,
                extent.centreZ() + forward / PX);
        return true;
    }

    /**
     * Draws {@code upper} and its lower half as one bent surface, cut into
     * {@code bands} bands across the bend.
     *
     * @return false if this bone is not a limb, or the joint is straight enough that
     *         the caller should just draw it normally
     */
    static boolean render(PoseStack poseStack, GeoBone upper, VertexConsumer buffer,
                          int packedLight, int packedOverlay, int colour, int bands) {
        GeoBone lower = childNamed(upper, LIMBS.get(upper.getName()));
        if (lower == null || lower.isHidden())
            return false;

        // The limb's set-up (measuring its boxes, the curve down it), timed apart (plan W0).
        boolean fast = fastMaths;
        long setUp = ClientCostLog.partStart();
        AxisAngle4f axisAngle = fast ? jointOf(lower, JOINT).get(JOINT_AXIS)
                                     : jointOf(lower, new Quaternionf()).get(new AxisAngle4f());
        if (Math.abs(axisAngle.angle) < MIN_ANGLE) {
            ClientCostLog.partEnd(ClientCostLog.Part.LIMB_SETUP, setUp);
            return false;
        }

        Extent extent = extent(upper, lower, fast);
        if (extent == null) {
            ClientCostLog.partEnd(ClientCostLog.Part.LIMB_SETUP, setUp);
            return false;
        }

        Sweep sweep = (fast ? SWEEPS.computeIfAbsent(upper.getName(), name -> new Sweep(BANDS)) : new Sweep(bands))
                .set(axisAngle, extent.top(), extent.bottom(), extent.joinY(),
                     extent.centreX(), extent.centreZ(), extent.halfWidth(), bands, fast);
        LAST_SHAPE.put(upper.getName(), sweep);
        ClientCostLog.partEnd(ClientCostLog.Part.LIMB_SETUP, setUp);

        // Plans W4 + W5 (SkinFaces): faces the skin leaves empty are not written; back faces go only
        // when both halves make one closed tube on this skin and this bend cannot fold it through itself.
        int[] above = SkinFaces.masks(upper), below = SkinFaces.masks(lower);
        boolean closed = SkinFaces.tube(upper, lower) && sweep.foldFree(extent.halfWidth());
        LimbWriter writer = writer(sweep, poseStack.last(), buffer, packedLight, packedOverlay, colour, fast);
        java.util.List<GeoCube> cubes = upper.getCubes();
        for (int i = 0; i < cubes.size(); i++)
            writer.cube(cubes.get(i), SkinFaces.limbMask(above, i, closed));
        cubes = lower.getCubes();
        for (int i = 0; i < cubes.size(); i++)
            writer.cube(cubes.get(i), SkinFaces.limbMask(below, i, closed));
        VillagerFrameStats.limbQuads(writer.quads);

        return true;
    }

    /**
     * The lower bone's turn relative to the upper one: the joint angle (GeckoLib's order: Z, Y, X), written
     * into {@code dest}, which is set to no turn first.
     */
    private static Quaternionf jointOf(GeoBone lower, Quaternionf dest) {
        return dest.identity().rotateZ(lower.getRotZ()).rotateY(lower.getRotY()).rotateX(lower.getRotX());
    }

    /** The kept writer (plan W3) or, with the old maths, a new one per limb as before. */
    private static LimbWriter writer(Sweep sweep, PoseStack.Pose frame, VertexConsumer buffer,
                                     int packedLight, int packedOverlay, int colour, boolean fast) {
        return (fast ? WRITER : new LimbWriter(false)).reset(sweep, frame, buffer, packedLight, packedOverlay, colour);
    }

    /**
     * The blocky limb style ({@link ClientConfig.LimbStyle#BLOCKY}): both halves drawn as
     * their own straight boxes, the lower half turned by the joint angle about the elbow
     * or knee.
     *
     * <p>Not simply the bones' own transforms: on {@code base_villager} and the nomad rig
     * the right forearm's pivot sits at shoulder height, so a forearm turned about its
     * own pivot swings clean off the arm. Hinged here at the point where the halves meet,
     * it bends at the same spot the smooth style bends.
     *
     * @return false if this bone is not a limb, or the joint is straight enough that
     *         the caller should just draw it normally
     */
    private static boolean renderHinged(GeoRenderer<?> renderer, PoseStack poseStack, GeoBone upper,
                                        VertexConsumer buffer, int packedLight, int packedOverlay, int colour) {
        GeoBone lower = childNamed(upper, LIMBS.get(upper.getName()));
        if (lower == null || lower.isHidden())
            return false;

        long setUp = ClientCostLog.partStart();
        // New limb maths (plan BF4): the joint is worked out in scratch and copied into this limb's kept hinge;
        // the old maths make a new quaternion, angle and hinge per limb.
        boolean fast = fastMaths;
        Quaternionf joint = jointOf(lower, fast ? JOINT : new Quaternionf());
        if (Math.abs(joint.get(fast ? JOINT_AXIS : new AxisAngle4f()).angle) < MIN_ANGLE) {
            ClientCostLog.partEnd(ClientCostLog.Part.LIMB_SETUP, setUp);
            return false;
        }

        Extent extent = extent(upper, lower, fast);
        if (extent == null) {
            ClientCostLog.partEnd(ClientCostLog.Part.LIMB_SETUP, setUp);
            return false;
        }

        Hinge hinge = (fast ? HINGES.computeIfAbsent(upper.getName(), name -> new Hinge()) : new Hinge())
                .set(joint, extent.centreX(), extent.joinY(), extent.centreZ());
        LAST_SHAPE.put(upper.getName(), hinge);
        ClientCostLog.partEnd(ClientCostLog.Part.LIMB_SETUP, setUp);

        // Plan W4 (SkinFaces): faces the skin leaves empty are not written. No back faces left out
        // here: each half is open at the joint, where only a cap of one skin row closes it.
        int[] above = SkinFaces.masks(upper), below = SkinFaces.masks(lower);
        int faces = 0;
        java.util.List<GeoCube> cubes = upper.getCubes();
        for (int i = 0; i < cubes.size(); i++) {
            GeoCube cube = cubes.get(i);
            faces += CubeWriter.draw(renderer, poseStack, cube, buffer, packedLight, packedOverlay, colour,
                    SkinFaces.limbMask(above, i, false));
            if (jointCap(poseStack, cube, extent.joinY(), false, fast, buffer, packedLight, packedOverlay, colour))
                faces++;
        }
        // The lower half under the hinge's turn. New maths (plan BF4): turned on the stack's top frame and set
        // back after, as the bone walk does, instead of a pushed copy (three new objects a limb).
        PoseStack.Pose frame = poseStack.last();
        if (fast) {
            HINGE_POSE.set(frame.pose());
            HINGE_NORMAL.set(frame.normal());
        } else
            poseStack.pushPose();
        poseStack.rotateAround(hinge.joint(), hinge.x(), hinge.y(), hinge.z());
        cubes = lower.getCubes();
        for (int i = 0; i < cubes.size(); i++) {
            GeoCube cube = cubes.get(i);
            faces += CubeWriter.draw(renderer, poseStack, cube, buffer, packedLight, packedOverlay, colour,
                    SkinFaces.limbMask(below, i, false));
            if (jointCap(poseStack, cube, extent.joinY(), true, fast, buffer, packedLight, packedOverlay, colour))
                faces++;
        }
        if (fast) {
            frame.pose().set(HINGE_POSE);
            frame.normal().set(HINGE_NORMAL);
        } else
            poseStack.popPose();
        VillagerFrameStats.limbQuads(faces);
        return true;
    }

    /**
     * Closes one end of a blocky limb half at the joint. bendify_model.py drops these
     * caps because the smooth sweep never shows them, but once the halves turn apart as
     * rigid boxes the hole lets you look straight through the limb. The cap is coloured
     * from the row of skin right beside the joint, so a sleeve stays sleeve-coloured.
     *
     * @param top true for the lower half's top end, false for the upper half's bottom end
     * @param fast the new limb maths: the cap is measured once per baked box ({@link #CAPS}, plan BF4)
     *             instead of every time, and drawn with kept scratch
     * @return whether a cap was drawn (for the face count)
     */
    private static boolean jointCap(PoseStack poseStack, GeoCube cube, float joinY, boolean top, boolean fast,
                                    VertexConsumer buffer, int packedLight, int packedOverlay, int colour) {
        Cap cap = fast ? capMeasured(cube, joinY, top) : capOf(cube, joinY, top);
        if (cap.x() == null)
            return false;
        Matrix4f pose = poseStack.last().pose();
        Vector3f normal = poseStack.last().normal()
                .transform(fast ? CAP_NORMAL.set(0f, top ? 1f : -1f, 0f) : new Vector3f(0, top ? 1 : -1, 0));
        Vector4f p = fast ? CAP_CORNER : new Vector4f();
        for (int i = 0; i < 4; i++) {
            pose.transform(p.set(cap.x()[i], cap.y(), cap.z()[i], 1f));
            buffer.addVertex(p.x, p.y, p.z, colour, cap.u()[i], cap.v(),
                             packedOverlay, packedLight, normal.x, normal.y, normal.z);
        }
        return true;
    }

    /**
     * A blocky limb box's cap at the joint, ready to draw: its height {@code y}, the four corners in drawing order
     * ({@code x}, {@code z}) and their skin coordinates ({@code u}, all at {@code v}). Null arrays: this box has no cap
     * to draw. {@code joinY} and {@code top} are what it was measured for.
     */
    private record Cap(float joinY, boolean top, float y, float v, float[] x, float[] z, float[] u) {}

    /** {@link #capOf}, measured once per baked box (plan BF4): a box's corners and skin never change. */
    private static Cap capMeasured(GeoCube cube, float joinY, boolean top) {
        Cap cap = CAPS.get(cube);
        if (cap == null || cap.joinY() != joinY || cap.top() != top) {
            if (CAPS.size() >= 256)
                CAPS.clear();
            cap = capOf(cube, joinY, top);
            CAPS.put(cube, cap);
        }
        return cap;
    }

    private static Cap noCap(float joinY, boolean top) {
        return new Cap(joinY, top, 0f, 0f, null, null, null);
    }

    /** Measures {@link #jointCap}'s cap for {@code cube}: the same sums it always did, kept instead of drawn. */
    private static Cap capOf(GeoCube cube, float joinY, boolean top) {
        if (!isUpright(cube))
            return noCap(joinY, top);

        GeoQuad side = null;
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (GeoQuad quad : cube.quads()) {
            if (quad == null)
                continue;
            Direction dir = quad.direction();
            if (dir == (top ? Direction.UP : Direction.DOWN))
                return noCap(joinY, top);           // the model kept this cap; nothing to close
            // Front or back face preferred (it runs across x), any side face otherwise.
            if (dir.getAxis().isHorizontal()
                    && (side == null || dir == Direction.NORTH
                        || (dir == Direction.SOUTH && side.direction() != Direction.NORTH)))
                side = quad;
            for (GeoVertex v : quad.vertices()) {
                Vector3f p = v.position();
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
            }
        }
        float y = top ? maxY : minY;
        if (side == null || Math.abs(y - joinY) > 1e-4f)
            return noCap(joinY, top);               // not the piece that meets the joint

        // The side face's edge at the joint: 0,1 run along its top, 3,2 along its bottom.
        // Nudged a little inward so only that one row of texels is sampled.
        GeoVertex[] sv = side.vertices();
        GeoVertex a = top ? sv[0] : sv[3], b = top ? sv[1] : sv[2], across = top ? sv[3] : sv[0];
        float du = (b.texU() - a.texU()) * 0.05f;
        float u0 = a.texU() + du, u1 = b.texU() - du;
        float v = a.texV() + (across.texV() - a.texV()) * 0.05f;
        // Spread u along whichever way that edge runs, x or z.
        boolean alongX = Math.abs(b.position().x() - a.position().x()) >= Math.abs(b.position().z() - a.position().z());
        float from = alongX ? a.position().x() : a.position().z();
        float to = alongX ? b.position().x() : b.position().z();

        // Counter-clockwise seen from outside: this order faces down, reversed it faces up.
        float[][] corners = {{minX, minZ}, {maxX, minZ}, {maxX, maxZ}, {minX, maxZ}};
        float[] xs = new float[4], zs = new float[4], us = new float[4];
        for (int i = 0; i < 4; i++) {
            float[] c = corners[top ? 3 - i : i];
            float t = Math.abs(to - from) < 1e-6f ? 0f : ((alongX ? c[0] : c[1]) - from) / (to - from);
            xs[i] = c[0];
            zs[i] = c[1];
            us[i] = u0 + (u1 - u0) * t;
        }
        return new Cap(joinY, top, y, v, xs, zs, us);
    }

    /**
     * A blocky limb: the lower half turned rigidly by {@code joint} about the point (x, y, z). With the new limb
     * maths one is kept per limb name ({@link #HINGES}, plan BF4) and set again for every blocky limb drawn, so the
     * one in {@link #LAST_SHAPE} is valid only while that villager is drawn, as a {@link Sweep} is.
     */
    private static final class Hinge implements Shape {
        private final Quaternionf joint = new Quaternionf();
        private float x, y, z;

        Hinge set(Quaternionf joint, float x, float y, float z) {
            this.joint.set(joint);
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        Quaternionf joint() { return joint; }
        float x() { return x; }
        float y() { return y; }
        float z() { return z; }

        @Override
        public Grip grip(Vector3f rest) {
            Vector3f position = joint.transform(rest.x() - x, rest.y() - y, rest.z() - z, new Vector3f())
                    .add(x, y, z);
            return new Grip(position, new Quaternionf(joint));
        }
    }

    /**
     * How far the bone is from the camera, in blocks, or -1 for a drawing that is not
     * in the world at all, which keeps full detail. {@code scale} is the pose's scale.
     *
     * <p>In the world the pose stack carries no camera rotation -- that is in the
     * model-view matrix -- so its translation is the bone's offset from the camera, in
     * blocks. The inventory portrait draws at about 30x in screen space, where that
     * offset is not a distance at all, so anything scaled up like that keeps full detail.
     */
    static float distanceOf(Matrix4f pose, float scale) {
        if (scale > 2f)
            return -1f;
        return (float) Math.sqrt(pose.m30() * pose.m30() + pose.m31() * pose.m31()
                + pose.m32() * pose.m32());
    }

    private static GeoBone childNamed(GeoBone bone, String name) {
        if (name == null)
            return null;
        for (GeoBone child : bone.getChildBones()) {
            if (child.getName().equals(name))
                return child;
        }
        return null;
    }

    /**
     * Writes one limb's vertices through its sweep.
     *
     * <p>It owns every scratch vector the per-vertex maths needs, so drawing a limb
     * allocates this and nothing more. Building fresh vectors, quaternions and vertices
     * for every corner, as this used to, threw away thousands of small objects per
     * villager per frame, and with a crowd in view the garbage collector's pauses showed
     * up as dropped frames. With the new limb maths (plan W3) one writer, {@link #WRITER},
     * is kept and {@link #reset} for every limb, so not even this is made.
     */
    private static final class LimbWriter {
        private Sweep sweep;
        private Matrix4f pose;
        private Matrix3f normals;
        private VertexConsumer buffer;
        private int packedLight, packedOverlay, colour;

        private final Quaternionf rotation = new Quaternionf();
        private final Vector3f position = new Vector3f();
        private final Vector3f normal = new Vector3f();
        private final Vector4f world = new Vector4f();
        private final Vector3f worldNormal = new Vector3f();
        /** Height {@link #rotation} was built for; NaN until the first vertex. */
        private float rotationY = Float.NaN;
        /** Quads written so far, for {@link VillagerFrameStats}. */
        int quads;

        /**
         * Plan W3, kept writer only (null with the old maths): the height, the turn and the centreline
         * point at each cut of the side face being written. The four sides of a box share their heights,
         * so the first side builds them and the other three reuse them; each used to build its own.
         */
        private final float[] bandY;
        private final Quaternionf[] bandTurn;
        private final float[] bandCentre;
        /** The edge heights and cut count {@link #bandY} was built for; NaN: none yet. */
        private float tableTop = Float.NaN, tableBottom = Float.NaN;
        private int tableCount;

        /** @param kept the writer kept for the new limb maths, with its table of band heights */
        LimbWriter(boolean kept) {
            if (kept) {
                bandY = new float[BANDS + 3];
                bandTurn = new Quaternionf[BANDS + 3];
                for (int i = 0; i < bandTurn.length; i++)
                    bandTurn[i] = new Quaternionf();
                bandCentre = new float[(BANDS + 3) * 3];
            } else {
                bandY = null;
                bandTurn = null;
                bandCentre = null;
            }
        }

        /** Points this writer at one limb (or one worn piece) and forgets the last one's heights. */
        LimbWriter reset(Sweep sweep, PoseStack.Pose frame, VertexConsumer buffer,
                         int packedLight, int packedOverlay, int colour) {
            this.sweep = sweep;
            this.pose = frame.pose();
            this.normals = frame.normal();
            this.buffer = buffer;
            this.packedLight = packedLight;
            this.packedOverlay = packedOverlay;
            this.colour = colour;
            this.rotationY = Float.NaN;
            this.tableTop = this.tableBottom = Float.NaN;
            this.quads = 0;
            this.heldCount = 0;
            this.closed = false;
            this.mirrored = pose.determinant3x3() < 0f;
            return this;
        }

        void cube(GeoCube cube) {
            cube(cube, SkinFaces.ALL_FACES);
        }

        /**
         * {@link #cube} writing only what {@code draw} asks ({@link SkinFaces#limbMask}): a face whose bit is
         * clear shows nothing on this skin (W4); with {@link SkinFaces#CULL} the limb is a closed tube and
         * its bands and caps turned away from the camera are left out (W5, {@link #triangleAway}).
         */
        void cube(GeoCube cube, int draw) {
            cull = CubeWriter.cullBack && CubeWriter.solid(cube);
            boolean closedCube = !cull && (draw & SkinFaces.CULL) != 0;
            boolean flat = CubeWriter.skipFlat && !CubeWriter.solid(cube);
            GeoQuad[] faces = cube.quads();
            for (int q = 0; q < faces.length; q++) {
                GeoQuad quad = faces[q];
                if (quad == null)
                    continue;
                if (flat && CubeWriter.noWidth(cube, quad.direction())) {
                    VillagerFrameStats.flatFace();          // no width: shows nothing (plan W1)
                    continue;
                }
                if (q < 6 && (draw & (1 << q)) == 0) {
                    VillagerFrameStats.emptyFace();         // nothing painted on it (plan W4)
                    continue;
                }

                GeoVertex[] v = quad.vertices();
                Direction dir = quad.direction();
                winding = closedCube ? windingOf(v, quad.normal()) : 0f;
                closed = winding != 0f;
                if (dir == Direction.UP || dir == Direction.DOWN) {
                    // A cap is flat and lies at one height, so it just goes where that
                    // height goes -- nothing to subdivide.
                    for (GeoVertex corner : v) {
                        Vector3f p = corner.position();
                        vertex(p.x(), p.y(), p.z(), corner.texU(), corner.texV(), quad.normal(), -1);
                    }
                    quads++;
                    continue;
                }

                // Side faces are baked with vertices 0,1 along the top edge and 2,3 along
                // the bottom, so 0-3 and 1-2 are the two edges running down the limb.
                float yHi = Math.max(v[0].position().y(), v[3].position().y());
                float yLo = Math.min(v[0].position().y(), v[3].position().y());
                int count = bandY != null ? sweep.cutKept(yHi, yLo) : sweep.cut(yHi, yLo);
                quads += count - 1;
                if (bandY != null)
                    table(v[0].position().y(), v[3].position().y(), count);
                for (int i = 0; i + 1 < count; i++) {
                    float t0 = sweep.cuts[i], t1 = sweep.cuts[i + 1];
                    along(v[0], v[3], t0, quad.normal(), i);
                    along(v[1], v[2], t0, quad.normal(), i);
                    along(v[1], v[2], t1, quad.normal(), i + 1);
                    along(v[0], v[3], t1, quad.normal(), i + 1);
                }
            }
        }

        /**
         * Plan W3: the height, turn and centreline point at each of the {@code count} cuts of a side
         * face whose edges run down from {@code yTop} to {@code yBottom}, unless already built for
         * those (the box's previous side). The height is {@link #along}'s own sum, so a vertex finds
         * its cut's entry only when it sits at exactly that height.
         */
        private void table(float yTop, float yBottom, int count) {
            if (yTop == tableTop && yBottom == tableBottom && count == tableCount)
                return;
            tableTop = yTop;
            tableBottom = yBottom;
            tableCount = count;
            for (int j = 0; j < count; j++) {
                float y = yTop + (yBottom - yTop) * sweep.cuts[j];
                bandY[j] = y;
                sweep.rotationInto(y, bandTurn[j]);
                sweep.centreInto(y, bandCentre, 3 * j);
            }
        }

        /** The vertex {@code t} of the way down the edge from {@code a} to {@code b}, at cut {@code cut}. */
        private void along(GeoVertex a, GeoVertex b, float t, Vector3f restNormal, int cut) {
            Vector3f pa = a.position(), pb = b.position();
            vertex(pa.x() + (pb.x() - pa.x()) * t,
                   pa.y() + (pb.y() - pa.y()) * t,
                   pa.z() + (pb.z() - pa.z()) * t,
                   a.texU() + (b.texU() - a.texU()) * t,
                   a.texV() + (b.texV() - a.texV()) * t,
                   restNormal, cut);
        }

        /** One vertex, given where it sits on the straight limb; {@code cut} its band height's index, -1 for a cap. */
        private void vertex(float x, float y, float z, float u, float v, Vector3f restNormal, int cut) {
            Quaternionf turn;
            if (cut >= 0 && bandY != null && bandY[cut] == y) {
                // Plan W3: this height's turn and centreline point were built once for the box's sides.
                turn = bandTurn[cut];
                sweep.positionAt(x, z, turn, bandCentre, 3 * cut, position);
            } else {
                // The rotation depends on height alone, and a band's vertices come in pairs at
                // one height (a cap's four share one), so build it once per height and use it
                // for both the position and the normal.
                if (y != rotationY) {
                    sweep.rotationInto(y, rotation);
                    rotationY = y;
                }
                turn = rotation;
                sweep.positionInto(x, y, z, rotation, position);
            }
            turn.transform(restNormal, normal);

            pose.transform(world.set(position.x, position.y, position.z, 1f));
            normals.transform(worldNormal.set(normal));
            if (!cull && !closed) {
                buffer.addVertex(world.x, world.y, world.z, colour, u, v,
                                 packedOverlay, packedLight, worldNormal.x, worldNormal.y, worldNormal.z);
                return;
            }
            // Crowd stand-in (CubeWriter.cullBack) or closed tube (W5): the quad's four corners
            // are held back until it is known whether it faces the camera.
            float[] s = HELD;
            int at = heldCount * 8;
            s[at] = world.x; s[at + 1] = world.y; s[at + 2] = world.z;
            s[at + 3] = u; s[at + 4] = v;
            s[at + 5] = worldNormal.x; s[at + 6] = worldNormal.y; s[at + 7] = worldNormal.z;
            if (++heldCount < 4) return;
            heldCount = 0;
            // Stand-in: corners 0 and 2 sit at the band's two heights, where the bend may face it apart.
            // Closed tube: both triangles the quad is drawn as, each by its own corners.
            if (cull ? facesAway(s, 0) && facesAway(s, 16)
                     : triangleAway(s, 0, 8, 16) && triangleAway(s, 0, 16, 24)) {
                quads--;
                if (cull) CubeWriter.culled++;
                else VillagerFrameStats.backFace();
                return;
            }
            for (int i = 0; i < 32; i += 8)
                buffer.addVertex(s[i], s[i + 1], s[i + 2], colour, s[i + 3], s[i + 4],
                                 packedOverlay, packedLight, s[i + 5], s[i + 6], s[i + 7]);
        }

        /** The camera is at the frame's origin: the corner's normal points away from it. */
        private static boolean facesAway(float[] s, int at) {
            return s[at] * s[at + 5] + s[at + 1] * s[at + 6] + s[at + 2] * s[at + 7] >= 0f;
        }

        /**
         * W5: the triangle of held corners {@code a, b, c} (offsets into {@link #HELD}) is turned away from
         * every eye near the camera ({@link CubeWriter#away}). Its facing comes from its corners' order (a
         * bent band is not flat, so its corner normals are not its facing), made outward by the face's
         * {@link #winding}. A triangle turned inside out (its facing against its first corner's normal) or
         * with no area is always drawn.
         */
        private boolean triangleAway(float[] s, int a, int b, int c) {
            float ex = s[b] - s[a], ey = s[b + 1] - s[a + 1], ez = s[b + 2] - s[a + 2];
            float fx = s[c] - s[a], fy = s[c + 1] - s[a + 1], fz = s[c + 2] - s[a + 2];
            float nx = (ey * fz - ez * fy) * winding;
            float ny = (ez * fx - ex * fz) * winding;
            float nz = (ex * fy - ey * fx) * winding;
            if (nx * s[a + 5] + ny * s[a + 6] + nz * s[a + 7] <= 0f)
                return false;
            return CubeWriter.away(s[a], s[a + 1], s[a + 2], nx, ny, nz);
        }

        /**
         * W5: +1 if the corners 0, 1, 2 of the face {@code v} run so that their cross product points out of
         * the box (along its rest normal {@code n}), -1 if inward, 0 if they cannot tell (then the face is
         * drawn); flipped when the frame mirrors (a negative scale turns the corners' order around). A band
         * keeps its face's order, so this holds for every band of the face.
         */
        private float windingOf(GeoVertex[] v, Vector3f n) {
            Vector3f p0 = v[0].position(), p1 = v[1].position(), p2 = v[2].position();
            float ex = p1.x() - p0.x(), ey = p1.y() - p0.y(), ez = p1.z() - p0.z();
            float fx = p2.x() - p0.x(), fy = p2.y() - p0.y(), fz = p2.z() - p0.z();
            float d = (ey * fz - ez * fy) * n.x() + (ez * fx - ex * fz) * n.y() + (ex * fy - ey * fx) * n.z();
            if (d == 0f || Float.isNaN(d))
                return 0f;
            return (d > 0f) != mirrored ? 1f : -1f;
        }

        /** Back faces left out ({@link CubeWriter#cullBack}) for the cube being written. */
        private boolean cull;
        /** W5: the face being written belongs to a closed tube, and {@link #winding} is known. */
        private boolean closed;
        /** W5: {@link #windingOf} the face being written. */
        private float winding;
        /** The frame turns the limb inside out (a negative scale), for {@link #windingOf}. */
        private boolean mirrored;
        /** Corners of the quad being written while {@link #cull}: x, y, z, u, v, normal. Render thread only. */
        private static final float[] HELD = new float[32];
        private int heldCount;
    }

    /**
     * The bend itself: a rotation that grows along the limb, and the centreline that
     * follows from it.
     *
     * <p>The rotation at a height is exact. The centreline has to be integrated --
     * it is where the limb's axis ends up once everything above has turned -- so it
     * is walked down the limb once and sampled per vertex.
     */
    /**
     * Where a point given in rest-pose model space (blocks) ends up once the limb
     * whose upper bone is {@code upper} is bent as drawn this frame -- for something
     * placed on the forearm in Blockbench, where the arm is two rigid boxes, that has
     * to follow the drawn bend instead. Null if this bone is not a limb.
     */
    /** The lower half (forearm, shin) of the limb whose upper bone is {@code upper}, or null. */
    static GeoBone lowerOf(GeoBone upper) {
        return childNamed(upper, LIMBS.get(upper.getName()));
    }

    /** Which part of a limb a worn piece covers, for {@link #renderWorn}. */
    enum WornOn { UPPER, LOWER, JOINT }

    /** Scratch for a stiff worn piece's move in {@link #renderWorn}; render thread only. */
    private static final Quaternionf STIFF_TURN = new Quaternionf();
    private static final Vector3f STIFF_AT = new Vector3f();

    /**
     * Draws cubes worn on a limb (armor) so they stay on the limb as it was drawn this
     * frame. {@code poseStack} must hold {@code upper}'s frame, and the limb itself must
     * already have been drawn -- render layers run right after their bone, so it has.
     *
     * <ul>
     *   <li>Smooth: plain cubes are swept exactly like the limb's own, so a greave bends
     *       with the knee. Joint caps and tilted cubes stay rigid and ride the bend at
     *       their centre, which turns them by about half the joint angle.</li>
     *   <li>Smooth but {@code stiff} (the villager is small on screen, {@link DetailCull#stiffArmor}):
     *       each piece is one rigid box. Upper pieces stay put; lower pieces take the
     *       sweep's transform below the bend, which is rigid there, so they sit exactly
     *       on the drawn forearm or shin; joint caps ride the middle of the bend at half
     *       the turn. Six quads a cube and no per-vertex sweep; only the few pixels
     *       around the bend itself no longer curve.</li>
     *   <li>Blocky: upper pieces stay put, lower pieces turn with the hinge, joint caps
     *       turn half way.</li>
     *   <li>Straight, or not a limb: everything is drawn as authored.</li>
     * </ul>
     */
    static void renderWorn(GeoRenderer<?> renderer, PoseStack poseStack, GeoBone upper, java.util.List<GeoCube> cubes,
                           WornOn part, boolean stiff, VertexConsumer buffer, int packedLight, int packedOverlay,
                           int colour) {
        Shape shape = childNamed(upper, LIMBS.get(upper.getName())) == null ? null : LAST_SHAPE.get(upper.getName());

        if (stiff && shape instanceof Sweep sweep) {
            VillagerFrameStats.armorQuads(ArmorFarModel.quads(cubes));
            poseStack.pushPose();
            if (part != WornOn.UPPER) {
                // Below the bend zone every point turns by the whole joint angle and is
                // carried down the bent centreline: one rigid move, read off at its top.
                // The joint cap takes the middle of the bend, where half the turn is done.
                float y = part == WornOn.LOWER ? sweep.joinY - sweep.zone : sweep.joinY;
                Quaternionf turn = sweep.rotationInto(y, STIFF_TURN);
                Vector3f at = sweep.positionInto(sweep.centreX, y, sweep.centreZ, turn, STIFF_AT);
                poseStack.translate(at.x, at.y, at.z);
                poseStack.mulPose(turn);
                poseStack.translate(-sweep.centreX, -y, -sweep.centreZ);
            }
            for (GeoCube cube : cubes) {
                CubeWriter.draw(renderer, poseStack, cube, buffer, packedLight, packedOverlay, colour);
            }
            poseStack.popPose();
            return;
        }

        if (shape instanceof Sweep sweep) {
            // Upright pieces are swept along the limb like its own cubes; the rest
            // (joint caps, tilted pieces) ride rigidly on the point they sit at.
            LimbWriter writer = writer(sweep, poseStack.last(), buffer, packedLight, packedOverlay, colour, fastMaths);
            for (GeoCube cube : cubes)
                if (part != WornOn.JOINT && isUpright(cube))
                    writer.cube(cube);
            int quads = writer.quads;
            for (GeoCube cube : cubes) {
                if (part != WornOn.JOINT && isUpright(cube))
                    continue;
                Vector3f centre = centreOf(cube);
                Grip grip = sweep.grip(centre);
                poseStack.pushPose();
                poseStack.translate(grip.position().x, grip.position().y, grip.position().z);
                poseStack.mulPose(grip.rotation());
                poseStack.translate(-centre.x, -centre.y, -centre.z);
                renderer.renderCube(poseStack, cube, buffer, packedLight, packedOverlay, colour);
                poseStack.popPose();
                quads += 6;
            }
            VillagerFrameStats.armorQuads(quads);
            return;
        }

        VillagerFrameStats.armorQuads(6 * cubes.size());
        // New limb maths (plan BF4): the half turn on scratch, and the stack's top frame turned and set back
        // after (nothing to set back when nothing turns) instead of a pushed copy.
        boolean fast = fastMaths;
        Hinge turned = shape instanceof Hinge hinge && part != WornOn.UPPER ? hinge : null;
        PoseStack.Pose frame = poseStack.last();
        if (!fast)
            poseStack.pushPose();
        else if (turned != null) {
            WORN_POSE.set(frame.pose());
            WORN_NORMAL.set(frame.normal());
        }
        if (turned != null) {
            Quaternionf turn = part == WornOn.LOWER ? turned.joint()
                    : (fast ? STIFF_TURN.identity() : new Quaternionf()).slerp(turned.joint(), 0.5f);
            poseStack.rotateAround(turn, turned.x(), turned.y(), turned.z());
        }
        for (GeoCube cube : cubes) {
            CubeWriter.draw(renderer, poseStack, cube, buffer, packedLight, packedOverlay, colour);
        }
        if (!fast)
            poseStack.popPose();
        else if (turned != null) {
            frame.pose().set(WORN_POSE);
            frame.normal().set(WORN_NORMAL);
        }
    }

    /** No tilt of its own: its side faces run straight down the limb and can be swept. */
    private static boolean isUpright(GeoCube cube) {
        return cube.rotation().lengthSqr() < 1e-8;
    }

    /** Middle of a cube's baked corners (blocks, model space). */
    private static Vector3f centreOf(GeoCube cube) {
        Vector3f sum = new Vector3f();
        int n = 0;
        for (GeoQuad quad : cube.quads()) {
            if (quad == null)
                continue;
            for (GeoVertex v : quad.vertices()) {
                sum.add(v.position());
                n++;
            }
        }
        return n == 0 ? sum : sum.div(n);
    }

    static Grip gripAt(GeoBone upper, Vector3f rest) {
        if (childNamed(upper, LIMBS.get(upper.getName())) == null)
            return null;
        Shape shape = LAST_SHAPE.get(upper.getName());
        return shape == null ? new Grip(new Vector3f(rest), new Quaternionf()) : shape.grip(rest);
    }

    private static final class Sweep implements Shape {
        private final Vector3f axis = new Vector3f();
        private float angle, top, step, centreX, centreZ, joinY, zone;
        private int bandCount;
        /** The centreline samples as x, y, z runs: sample k starts at index 3k. */
        private final float[] centreline = new float[(STEPS + 1) * 3];
        /** Written by {@link #cut}, and reused face after face of the one limb. */
        private final float[] cuts;
        /** Plan W3: the heights {@link #cuts} holds the cuts of, for {@link #cutKept}; NaN: not known. */
        private float cutHi = Float.NaN, cutLo = Float.NaN;
        private int cutCount;

        // Scratch for set's walk down the limb, and positionInto's centreline point.
        private final Quaternionf turn = new Quaternionf();
        private final Vector3f prev = new Vector3f(), next = new Vector3f();
        /** Plan W3: the limb's direction below the bend zone, once built for this shape. */
        private final Vector3f below = new Vector3f();
        private boolean belowBuilt;
        private final float[] centre = new float[3];

        /** @param maxBands the most bands {@link #set} will be asked for */
        Sweep(int maxBands) {
            this.cuts = new float[maxBands + 3];
        }

        /**
         * Shapes this sweep for one limb drawn this frame and walks its centreline down.
         *
         * @param fast plan W3: the walk takes a sine and cosine only inside the bend zone. Above
         *             it the turn is none at all, below it the whole angle, the same at every
         *             step; the directions come out as the same numbers either way.
         */
        Sweep set(AxisAngle4f axisAngle, float top, float bottom, float joinY, float centreX,
                  float centreZ, float halfWidth, int bandCount, boolean fast) {
            this.bandCount = bandCount;
            this.axis.set(axisAngle.x, axisAngle.y, axisAngle.z);
            this.angle = axisAngle.angle;
            this.top = top;
            this.joinY = joinY;
            // Arc length over the zone is 2 * zone, and the inner radius stays positive
            // while that is at least halfWidth * angle -- hence zone >= halfWidth * angle / 2.
            this.zone = Math.max(BEND_ZONE_MIN / PX,
                                 halfWidth * Math.abs(this.angle) * 0.5f * BEND_MARGIN);
            this.centreX = centreX;
            this.centreZ = centreZ;
            this.step = (top - bottom) / STEPS;
            this.cutHi = this.cutLo = Float.NaN;
            this.belowBuilt = false;

            centreline[0] = centreX;
            centreline[1] = top;
            centreline[2] = centreZ;
            Vector3f prev = direction(top, fast, this.prev);
            Vector3f next = this.next;
            float half = 0.5f * this.step;                         // trapezoid
            for (int k = 1; k <= STEPS; k++) {
                direction(top - k * this.step, fast, next);
                int c = 3 * k;
                centreline[c]     = centreline[c - 3] + (prev.x + next.x) * half;
                centreline[c + 1] = centreline[c - 2] + (prev.y + next.y) * half;
                centreline[c + 2] = centreline[c - 1] + (prev.z + next.z) * half;
                prev.set(next);
            }
            return this;
        }

        /** The limb's way down at rest height {@code y}, (0, -1, 0) turned by the bend there, into {@code dest}. */
        private Vector3f direction(float y, boolean fast, Vector3f dest) {
            float fraction = bendFraction(y);
            if (fast) {
                // Turned by 0: straight down (the turn by 0 gave the same, up to the sign of a zero).
                if (fraction == 0f)
                    return dest.set(0f, -1f, 0f);
                // Turned by the whole angle (1 x angle is angle exactly): built at the first such step.
                if (fraction == 1f) {
                    if (!belowBuilt) {
                        turn.setAngleAxis(angle, axis.x, axis.y, axis.z).transform(0f, -1f, 0f, below);
                        belowBuilt = true;
                    }
                    return dest.set(below);
                }
            }
            // rotationInto(y) without working the fraction out twice.
            return turn.setAngleAxis(fraction * angle, axis.x, axis.y, axis.z).transform(0f, -1f, 0f, dest);
        }

        /**
         * How much of the joint angle has been turned by this height: 0 above the bend
         * zone, 1 below it, smootherstep between. Its derivative is zero at both ends,
         * so the limb eases into and out of the curve.
         */
        private float bendFraction(float y) {
            float u = (joinY + zone - y) / (2f * zone);
            if (u <= 0f)
                return 0f;
            if (u >= 1f)
                return 1f;
            return u * u * u * (u * (6f * u - 15f) + 10f);
        }

        /**
         * Where to cut a face between two heights, as edge parameters in 0..1, written
         * into {@link #cuts} in ascending order; the bands are the gaps between
         * neighbours. Only the stretch inside the bend zone curves, so that is the only
         * stretch worth subdividing -- the rest of the limb comes out as a single band.
         *
         * @return how many cuts were written
         */
        int cut(float yHi, float yLo) {
            cutHi = cutLo = Float.NaN;             // cutKept's memory no longer matches cuts
            float lo = joinY - zone, hi = joinY + zone;
            float a = Math.min(yHi, hi), b = Math.max(yLo, lo);
            float span = yHi - yLo;
            cuts[0] = 0f;
            cuts[1] = 1f;
            if (span <= 0)
                return 2;
            if (a <= b)
                return 2;                          // this face misses the bend entirely

            // y falls from a to b as k rises, so t only ever rises: the cuts come out
            // sorted, and a repeat can only be the value just written.
            int n = 1;
            for (int k = 0; k <= bandCount; k++) {
                float y = a + (b - a) * k / bandCount;
                float t = Math.min(1f, Math.max(0f, (yHi - y) / span));
                if (t != cuts[n - 1])
                    cuts[n++] = t;
            }
            if (cuts[n - 1] != 1f)
                cuts[n++] = 1f;
            return n;
        }

        /**
         * W5 ({@link SkinFaces}): this bend cannot fold the limb's skin through itself, for a limb
         * {@code halfWidth} across either way from its axis. A cross-section turns by the bend's slope
         * times its distance from the axis (across the turn) for each step down; once that reaches one the
         * inside of the bend runs backwards and folds, and inside a fold a face turned away from the camera
         * can still show. The slope is steepest halfway through the zone, {@link #STEEPEST} times the
         * average. On a 4 px limb bends of up to about 27 degrees pass (20 when it bends towards a corner);
         * the tighter ones, which {@link #BEND_MARGIN} lets fold a little, are drawn whole.
         */
        boolean foldFree(float halfWidth) {
            float reach = (Math.abs(axis.x) + Math.abs(axis.z)) * halfWidth;
            return Math.abs(angle) * STEEPEST / (2f * zone) * reach < FOLD_LIMIT;
        }

        /** Smootherstep's steepest slope (halfway), over its average: 30 / 16. */
        private static final float STEEPEST = 1.875f;
        /** {@link #foldFree}'s limit, short of one by a margin for the bands' straight edges. */
        private static final float FOLD_LIMIT = 0.9f;

        /**
         * {@link #cut}, skipped when {@link #cuts} already holds the cuts for these heights: a box's
         * four sides share them (plan W3).
         */
        int cutKept(float yHi, float yLo) {
            if (yHi == cutHi && yLo == cutLo)
                return cutCount;
            int n = cut(yHi, yLo);
            cutHi = yHi;
            cutLo = yLo;
            cutCount = n;
            return n;
        }

        @Override
        public Grip grip(Vector3f rest) {
            return new Grip(position(rest), rotation(rest.y()));
        }

        Quaternionf rotation(float y) {
            return rotationInto(y, new Quaternionf());
        }

        /** {@link #rotation}, written into {@code dest}. */
        Quaternionf rotationInto(float y, Quaternionf dest) {
            return dest.setAngleAxis(bendFraction(y) * angle, axis.x, axis.y, axis.z);
        }

        Vector3f position(Vector3f rest) {
            return positionInto(rest.x(), rest.y(), rest.z(), rotation(rest.y()), new Vector3f());
        }

        /**
         * {@link #position} for the rest point (x, y, z), with the rotation at that height
         * already built, written into {@code dest}.
         */
        Vector3f positionInto(float x, float y, float z, Quaternionf rotation, Vector3f dest) {
            centreInto(y, centre, 0);
            return positionAt(x, z, rotation, centre, 0, dest);
        }

        /**
         * Where the rest point (x, z) at a height goes, given that height's turn and bent centreline
         * point ({@code centre} from {@code at}, as {@link #centreInto} wrote it), into {@code dest}.
         */
        Vector3f positionAt(float x, float z, Quaternionf rotation, float[] centre, int at, Vector3f dest) {
            return rotation.transform(x - centreX, 0f, z - centreZ, dest)
                    .add(centre[at], centre[at + 1], centre[at + 2]);
        }

        /** The bent centreline's point at rest height {@code y}, written into {@code out} from {@code at}. */
        void centreInto(float y, float[] out, int at) {
            float k = (top - y) / step;
            float cx, cy, cz;
            if (k > STEPS) {
                // Past the hand end of the limb. Clamping to the last sample -- which is
                // what this did -- silently throws the overshoot away, and the overshoot
                // is exactly where a held item lives: a grip anchored 3.4px below the
                // fingertips moved the item 0.95px instead of 3.4, and no amount of
                // turning the knob got the rest of it. Carry on along the limb's own
                // final direction instead, which is what the fingers would do.
                int last = 3 * STEPS;
                float over = k - STEPS;
                cx = centreline[last]     + over * (centreline[last]     - centreline[last - 3]);
                cy = centreline[last + 1] + over * (centreline[last + 1] - centreline[last - 2]);
                cz = centreline[last + 2] + over * (centreline[last + 2] - centreline[last - 1]);
            } else {
                int i = Math.max(0, Math.min(STEPS - 1, (int) k));
                float f = Math.max(0f, Math.min(1f, k - i));
                int c = 3 * i;
                cx = centreline[c]     + (centreline[c + 3] - centreline[c])     * f;
                cy = centreline[c + 1] + (centreline[c + 4] - centreline[c + 1]) * f;
                cz = centreline[c + 2] + (centreline[c + 5] - centreline[c + 2]) * f;
            }
            out[at] = cx;
            out[at + 1] = cy;
            out[at + 2] = cz;
        }
    }
}
