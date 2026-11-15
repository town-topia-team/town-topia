package com.alex.test.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
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
import software.bernie.geckolib.util.RenderUtil;

/**
 * GeckoLib's cube drawing ({@code GeoRenderer.renderCube}) without new objects (30 FPS plan,
 * round 1a). GeckoLib copies the pose stack for every cube and makes a new matrix per cube,
 * three quaternions, a vector per face and one per corner: with a crowd on screen that was
 * tens of megabytes of garbage a frame, and the clean-ups were the worst frames (84-240 ms,
 * log 10-19 14:06). The same steps in the same order, on scratch objects kept here, so the
 * numbers come out the same to the last bit.
 *
 * <p>{@code /villagerfps writer old} goes back to GeckoLib's own drawing and bone walk
 * ({@link NpcGeoRenderer#renderRecursively}), to compare. Render thread only.
 */
final class CubeWriter {

    /** {@code /villagerfps writer new|old}: ours, or GeckoLib's cube drawing and bone walk. */
    static volatile boolean fast = true;

    static {
        linkCheck();
    }

    /**
     * Plan step 0d (docs/plans/2026-10-22-hundred-villagers-fps.md): the GeckoLib methods this
     * writer calls are looked up once when the class loads. A GeckoLib without one of them turns
     * the writer off (GeckoLib's own drawing and bone walk) instead of stopping the render thread
     * with a link error at the first cube.
     */
    private static void linkCheck() {
        try {
            RenderUtil.class.getMethod("fixInvertedFlatCube", GeoCube.class, Vector3f.class);
            GeoCube.class.getMethod("quads");
            GeoCube.class.getMethod("pivot");
            GeoCube.class.getMethod("rotation");
            GeoCube.class.getMethod("size");
            GeoQuad.class.getMethod("normal");
            GeoQuad.class.getMethod("vertices");
            GeoVertex.class.getMethod("position");
            GeoVertex.class.getMethod("texU");
            GeoVertex.class.getMethod("texV");
        } catch (ReflectiveOperationException | LinkageError e) {
            fast = false;
            com.mojang.logging.LogUtils.getLogger().warn("[CubeWriter] GeckoLib is missing a method our cube writer "
                    + "calls; GeckoLib's own cube drawing and bone walk are used instead", e);
        }
    }

    /**
     * {@code /villagerfps flat on|off} (plan W1, docs/plans/2026-10-22-hundred-villagers-fps.md): faces with
     * no width are left out. GeckoLib bakes a face for every face a cube lists, even on a cube 0 px deep:
     * 4 of each eye sticker's faces (eye background, eyes, eyebrow) are lines, 16 a villager, and each
     * cost as much to draw as a real face. A line shows nothing in any pose, so nothing on screen changes.
     */
    static volatile boolean skipFlat = true;

    /**
     * Faces turned away from the camera are left out (crowd stand-ins, {@link StandIn}):
     * set only while one is drawn, never in the shader shadow pass (its "camera" is the
     * sun). Only cubes with depth in all three sizes: a flat cube shows its back face.
     */
    static boolean cullBack;
    /** Faces left out that way, for {@link StandIn}'s counts. */
    static int culled;

    private static final Matrix4f POSE = new Matrix4f();
    private static final Matrix3f NORMALS = new Matrix3f();
    private static final Quaternionf TURN = new Quaternionf();
    private static final Vector3f NORMAL = new Vector3f();
    private static final Vector4f CORNER = new Vector4f();

    private CubeWriter() {}

    /**
     * One cube in the frame {@code frame} stands in, as GeckoLib's {@code renderCube} draws it
     * after the caller's push: moved to its pivot, turned Z then Y then X, moved back, each
     * face's normal turned with it (flat cubes' normals flipped the same way), each corner
     * written. Nothing is pushed on the stack: the caller's frame is left as it was.
     *
     * @return the faces written (left-out back faces and faces with no width not counted)
     */
    static int cube(PoseStack.Pose frame, GeoCube cube, VertexConsumer buffer, int packedLight,
                    int packedOverlay, int colour) {
        return cube(frame, cube, buffer, packedLight, packedOverlay, colour, SkinFaces.ALL_FACES);
    }

    /**
     * {@link #cube} drawing only what {@code draw} asks ({@link SkinFaces#mask}): a face whose bit is
     * clear shows nothing on this skin (W4); with {@link SkinFaces#CULL} the cube is closed and its faces
     * turned away from the camera are left out (W5, {@link #away}).
     */
    static int cube(PoseStack.Pose frame, GeoCube cube, VertexConsumer buffer, int packedLight,
                    int packedOverlay, int colour, int draw) {
        Matrix4f pose = POSE.set(frame.pose());
        Matrix3f normals = NORMALS.set(frame.normal());
        Vec3 pivot = cube.pivot();
        float px = (float) (pivot.x() / 16f), py = (float) (pivot.y() / 16f), pz = (float) (pivot.z() / 16f);
        pose.translate(px, py, pz);
        // GeckoLib turns by all three every time; a turn by 0 leaves every number as it was.
        Vec3 rotation = cube.rotation();
        if (rotation.z() != 0) turn(pose, normals, TURN.rotationXYZ(0, 0, (float) rotation.z()));
        if (rotation.y() != 0) turn(pose, normals, TURN.rotationXYZ(0, (float) rotation.y(), 0));
        if (rotation.x() != 0) turn(pose, normals, TURN.rotationXYZ((float) rotation.x(), 0, 0));
        pose.translate(-px, -py, -pz);

        boolean cull = cullBack && solid(cube);
        boolean closed = !cull && (draw & SkinFaces.CULL) != 0;
        // Only a cube with a size of 0 can have faces with no width; the others are not looked at.
        boolean flat = skipFlat && !solid(cube);
        int written = 0;
        GeoQuad[] quads = cube.quads();
        for (int q = 0; q < quads.length; q++) {
            GeoQuad quad = quads[q];
            if (quad == null)
                continue;
            if (flat && noWidth(cube, quad.direction())) {
                VillagerFrameStats.flatFace();
                continue;
            }
            if (q < 6 && (draw & (1 << q)) == 0) {
                VillagerFrameStats.emptyFace();
                continue;
            }
            Vector3f normal = normals.transform(NORMAL.set(quad.normal()));
            RenderUtil.fixInvertedFlatCube(cube, normal);
            GeoVertex[] corners = quad.vertices();
            boolean drawn = true;
            for (int i = 0; i < corners.length; i++) {
                GeoVertex vertex = corners[i];
                Vector3f position = vertex.position();
                Vector4f at = pose.transform(CORNER.set(position.x(), position.y(), position.z(), 1.0f));
                // The camera sits at the frame's origin (entities are drawn camera-relative):
                // a face whose normal points the same way as the camera-to-face line is a back face.
                // A face is flat, so its first corner decides for all of it.
                if (i == 0 && (cull ? at.x() * normal.x() + at.y() * normal.y() + at.z() * normal.z() >= 0f
                        : closed && away(at.x(), at.y(), at.z(), normal.x(), normal.y(), normal.z()))) {
                    if (cull) culled++;
                    else VillagerFrameStats.backFace();
                    drawn = false;
                    break;
                }
                buffer.addVertex(at.x(), at.y(), at.z(), colour, vertex.texU(), vertex.texV(),
                        packedOverlay, packedLight, normal.x(), normal.y(), normal.z());
            }
            if (drawn) written++;
        }
        return written;
    }

    /**
     * How far, in blocks, the eye may sit from the frame's origin: view bobbing moves the picture by up
     * to about 0.11 of a block after the entities are placed. A face of a closed box is left out only when
     * the eye is at least this far behind its plane, so no bob can turn it to the camera.
     */
    private static final float EYE_SLACK = 0.25f;

    /**
     * A face through the point ({@code x, y, z}) (camera-relative) with the outward normal ({@code nx, ny,
     * nz}) is turned away from every eye within {@link #EYE_SLACK} of the origin. The normal need not be
     * one long (a scaled bone's is not).
     */
    static boolean away(float x, float y, float z, float nx, float ny, float nz) {
        float d = x * nx + y * ny + z * nz;
        return d > 0f && d * d >= EYE_SLACK * EYE_SLACK * (nx * nx + ny * ny + nz * nz);
    }

    /** Depth in all three sizes: its back faces can only be seen from inside it. */
    static boolean solid(GeoCube cube) {
        Vec3 size = cube.size();
        return size.x() > 1e-4 && size.y() > 1e-4 && size.z() > 1e-4;
    }

    /**
     * The face of {@code cube} facing {@code side} has no width: one of the two sizes it spans is 0,
     * inflate counted (a 0 px sticker inflated by any amount has width). Sizes are model pixels,
     * GeckoLib keeps the inflate in blocks.
     */
    static boolean noWidth(GeoCube cube, Direction side) {
        Vec3 size = cube.size();
        double grow = 2 * Math.abs(cube.inflate()) * 16;
        Direction.Axis axis = side.getAxis();
        double across = axis == Direction.Axis.X ? size.z() : size.x();
        double up = axis == Direction.Axis.Y ? size.z() : size.y();
        return Math.abs(across) + grow < 1e-4 || Math.abs(up) + grow < 1e-4;
    }

    /**
     * The faces of {@code cube} that would be drawn (none left out as back faces), for the face
     * counts when GeckoLib's own drawing runs ({@code writer old}): every face it baked.
     */
    static int faces(GeoCube cube) {
        int n = 0;
        for (GeoQuad quad : cube.quads())
            if (quad != null) n++;
        return n;
    }

    /**
     * A bone squashed to nothing (scale 0 on all three sizes) draws no face anyone can see: every
     * corner lands on one point. The nose a villager does not have is drawn that way (clip
     * {@code nose_variants} scales it to 0). Same switch as {@link #skipFlat}; only with our writer.
     */
    static boolean squashed(GeoBone bone) {
        return fast && skipFlat && bone.getScaleX() == 0f && bone.getScaleY() == 0f && bone.getScaleZ() == 0f;
    }

    /** What {@code PoseStack.mulPose} does to the top frame. */
    private static void turn(Matrix4f pose, Matrix3f normals, Quaternionf by) {
        pose.rotate(by);
        normals.rotate(by);
    }

    /**
     * {@code renderer.renderCube} between a push and a pop, as the armor and limb code
     * draws its cubes; with {@link #fast}, {@link #cube} with no push at all.
     *
     * @return the faces written
     */
    static int draw(GeoRenderer<?> renderer, PoseStack poseStack, GeoCube cube, VertexConsumer buffer,
                    int packedLight, int packedOverlay, int colour) {
        return draw(renderer, poseStack, cube, buffer, packedLight, packedOverlay, colour, SkinFaces.ALL_FACES);
    }

    /** {@link #draw} of only what {@code draw} asks ({@link SkinFaces#mask}); GeckoLib's own drawing draws it all. */
    static int draw(GeoRenderer<?> renderer, PoseStack poseStack, GeoCube cube, VertexConsumer buffer,
                    int packedLight, int packedOverlay, int colour, int draw) {
        if (fast)
            return cube(poseStack.last(), cube, buffer, packedLight, packedOverlay, colour, draw);
        poseStack.pushPose();
        renderer.renderCube(poseStack, cube, buffer, packedLight, packedOverlay, colour);
        poseStack.popPose();
        return faces(cube);
    }

    /** For {@code /villagerfps} and the {@code [ClientCost]} log. */
    static String state() {
        return (fast ? "writer new" : "writer old (GeckoLib's)") + (fast && skipFlat ? ", flat faces skipped" : ", flat faces drawn");
    }
}
