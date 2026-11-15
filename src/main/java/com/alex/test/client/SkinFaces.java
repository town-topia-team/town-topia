package com.alex.test.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.renderer.GeoRenderer;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a villager's skin paints on each face of the model, read once per skin picture, for two
 * savings that change nothing on screen (plans W4 and W5, docs/plans/2026-10-22-hundred-villagers-fps.md):
 * <ul>
 *   <li>W4, {@code /villagerfps empty on|off}: a face whose patch of the skin has no visible pixel
 *       at all draws nothing (the shader throws every one of its pixels away), so it is not written.
 *       The plain skin (children, the jobless) paints its eyes on the face, so its eye stickers,
 *       torso box and second nose show nothing: about 19 faces a villager.</li>
 *   <li>W5, {@code /villagerfps faces all|standin|off}: a box with all six faces painted solid is
 *       closed, and from outside it its faces turned away from the camera are always behind its own
 *       front, so they are not written ({@link CubeWriter#away}). A bent limb counts when both halves
 *       make one closed tube ({@link #tube}). Only in the world's entity pass (the camera at the
 *       frame's origin), never in the shader shadow pass, never on a see-through villager, never
 *       within {@link #NEAR} blocks (the camera could be inside it).</li>
 * </ul>
 * Only the villager's own body in its own pass ({@link NpcGeoRenderer}): armor, held items, props and
 * the alert outline's redraw are drawn as before. A skin that cannot be read, or one that moves
 * (an animated texture), leaves every face drawn. Off while Entity Texture Features is loaded: it may
 * swap the picture while drawing. Forgotten at every resource reload ({@link FarmerDirtTextures}
 * calls {@link #forget}). Render thread only.
 */
final class SkinFaces {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code /villagerfps empty on|off} (W4). */
    static volatile boolean skipEmpty = true;

    /** {@code /villagerfps faces}: back faces left out on every villager (W5), only on far stand-ins (as before), or never. */
    enum BackFaces { ALL, STANDIN, OFF }

    static volatile BackFaces backFaces = BackFaces.ALL;

    /** Another mod draws villagers with pictures of its own (Entity Texture Features): both savings stay off. */
    private static final boolean SWAPPED_SKINS = ModList.get() != null && ModList.get().isLoaded("entity_texture_features");

    /** What to draw of a cube: bits 0-5 its face at that place in {@code GeoCube.quads()}; CULL its back faces left out. */
    static final int ALL_FACES = 0x3F;
    static final int CULL = 1 << 6;

    // Kept per cube of a skin: bits 0-5 face i has a visible pixel; OPAQUE every face there is painted
    // solid and the cube has depth; CLOSED that, with all six faces there.
    private static final int OPAQUE = 1 << 6, CLOSED = 1 << 7;
    private static final int SEEN = 1, SOLID = 2;

    /** No back faces left out on a villager nearer the camera than this, in blocks (feet to camera). */
    private static final float NEAR = 3f;

    private static final Map<ResourceLocation, Skin> SKINS = new HashMap<>();
    private static final Skin UNREADABLE = new Skin(new byte[0], 0, 0);
    private static final Vector3f SCALE = new Vector3f();
    private static final int[] SPAN = new int[4];

    /** The skin of the villager being drawn, or null: everything drawn. */
    @Nullable
    private static Skin current;
    private static boolean emptyNow, cullNow;
    private static boolean warned;

    private SkinFaces() {}

    /** One skin picture: each pixel's alpha, and per bone of the model what that does to its cubes. */
    private static final class Skin {
        final byte[] alpha;
        final int width, height;
        final Map<GeoBone, Bone> bones = new IdentityHashMap<>();

        Skin(byte[] alpha, int width, int height) {
            this.alpha = alpha;
            this.width = width;
            this.height = height;
        }
    }

    /** One bone on one skin: a kept value per cube; for an upper limb bone, whether the limb is a closed tube. */
    private static final class Bone {
        final int[] cubes;
        /** 0 not worked out yet, 1 a closed tube, 2 not. */
        byte tube;

        Bone(int[] cubes) {
            this.cubes = cubes;
        }
    }

    /**
     * {@code entity}'s own body is about to be drawn by {@code renderer} with {@code renderType} and
     * {@code colour}, {@code poseStack} standing at its feet. {@link #end} after.
     */
    static <T extends Entity & GeoAnimatable> void begin(GeoRenderer<T> renderer, T entity,
                                                       @Nullable RenderType renderType, PoseStack poseStack, int colour) {
        end();
        // The sun's pass (shaders) and an unseen body are drawn as before.
        if (SWAPPED_SKINS || renderType == null || IrisShadowPass.active()) return;
        boolean empty = skipEmpty;
        boolean cull = backFaces == BackFaces.ALL && CrowdBatching.inWorldEntity() && (colour >>> 24) == 0xFF;
        if (!empty && !cull) return;
        ResourceLocation texture = renderer.getTextureLocation(entity);
        if (texture == null) return;
        // Only the picture GeckoLib draws with, in a way that throws see-through pixels away. Back faces
        // only in the plain solid one (not see-through, not the glowing outline).
        if (renderType != RenderType.entityCutoutNoCull(texture)) {
            cull = false;
            empty = empty && (renderType == RenderType.itemEntityTranslucentCull(texture)
                    || renderType == RenderType.outline(texture));
        }
        if (cull && !farEnough(poseStack, entity.getBbHeight())) cull = false;
        if (!empty && !cull) return;
        Skin skin = skin(texture);
        if (skin == null) return;
        current = skin;
        emptyNow = empty;
        cullNow = cull;
    }

    static void end() {
        current = null;
        emptyNow = cullNow = false;
    }

    /**
     * Far enough from the camera that it cannot be inside the villager, {@code height} blocks tall (a big
     * one needs more room); a portrait (no distance) is not.
     */
    private static boolean farEnough(PoseStack poseStack, float height) {
        Matrix4f pose = poseStack.last().pose();
        return BentLimb.distanceOf(pose, pose.getScale(SCALE).y()) >= Math.max(NEAR, 1.5f * height);
    }

    /** The kept values of {@code bone}'s cubes on the skin being drawn, or null: draw everything. */
    @Nullable
    static int[] masks(GeoBone bone) {
        Skin skin = current;
        if (skin == null) return null;
        Bone kept = skin.bones.get(bone);
        if (kept == null) {
            kept = read(skin, bone);
            skin.bones.put(bone, kept);
        }
        return kept.cubes;
    }

    /** What to draw of cube {@code i} of a bone whose {@link #masks} are {@code masks}. */
    static int mask(@Nullable int[] masks, int i) {
        if (masks == null) return ALL_FACES;
        int kept = masks[i];
        int draw = emptyNow ? kept & ALL_FACES : ALL_FACES;
        return cullNow && (kept & CLOSED) != 0 ? draw | CULL : draw;
    }

    /** {@link #mask} for a half of a bent limb: its back faces left out when the whole limb is a closed tube. */
    static int limbMask(@Nullable int[] masks, int i, boolean tube) {
        if (masks == null) return ALL_FACES;
        int draw = emptyNow ? masks[i] & ALL_FACES : ALL_FACES;
        return tube ? draw | CULL : draw;
    }

    /**
     * The bent limb {@code upper} + {@code lower} is one closed tube on the skin being drawn, so its back
     * faces may go: one upright box each, every face painted solid, the upper missing only its bottom and
     * the lower only its top (the joint, where the sweep welds them), the same cross-section, meeting at
     * one height. A limb with sleeves, cuffs or any other extra box is not judged.
     */
    static boolean tube(GeoBone upper, GeoBone lower) {
        Skin skin = current;
        if (skin == null || !cullNow) return false;
        masks(upper);
        masks(lower);
        Bone top = skin.bones.get(upper);
        if (top.tube == 0) top.tube = closedTube(skin, upper, lower) ? (byte) 1 : (byte) 2;
        return top.tube == 1;
    }

    private static boolean closedTube(Skin skin, GeoBone upper, GeoBone lower) {
        List<GeoCube> above = upper.getCubes(), below = lower.getCubes();
        if (above.size() != 1 || below.size() != 1) return false;
        if ((skin.bones.get(upper).cubes[0] & OPAQUE) == 0 || (skin.bones.get(lower).cubes[0] & OPAQUE) == 0)
            return false;
        GeoCube a = above.get(0), b = below.get(0);
        if (a.rotation().lengthSqr() > 1e-8 || b.rotation().lengthSqr() > 1e-8) return false;
        if (!allBut(a, Direction.DOWN) || !allBut(b, Direction.UP)) return false;
        float[] p = bounds(a), q = bounds(b);
        return same(p[0], q[0]) && same(p[1], q[1]) && same(p[4], q[4]) && same(p[5], q[5]) && same(p[2], q[3]);
    }

    /** Every face of {@code cube} is there but the one facing {@code missing}. */
    private static boolean allBut(GeoCube cube, Direction missing) {
        int there = 0;
        for (GeoQuad quad : cube.quads()) {
            if (quad == null) continue;
            if (quad.direction() == missing) return false;
            there |= 1 << quad.direction().ordinal();
        }
        return there == (0x3F & ~(1 << missing.ordinal()));
    }

    /** The cube's corners' reach: min x, max x, min y, max y, min z, max z (blocks, model space). */
    private static float[] bounds(GeoCube cube) {
        float[] b = {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (GeoQuad quad : cube.quads()) {
            if (quad == null) continue;
            for (GeoVertex v : quad.vertices()) {
                Vector3f p = v.position();
                b[0] = Math.min(b[0], p.x()); b[1] = Math.max(b[1], p.x());
                b[2] = Math.min(b[2], p.y()); b[3] = Math.max(b[3], p.y());
                b[4] = Math.min(b[4], p.z()); b[5] = Math.max(b[5], p.z());
            }
        }
        return b;
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < 1e-5f;
    }

    private static Bone read(Skin skin, GeoBone bone) {
        List<GeoCube> cubes = bone.getCubes();
        int[] kept = new int[cubes.size()];
        for (int i = 0; i < kept.length; i++)
            kept[i] = read(skin, cubes.get(i));
        return new Bone(kept);
    }

    /** One cube on one skin: which faces show anything, and whether it is painted solid all round. */
    private static int read(Skin skin, GeoCube cube) {
        GeoQuad[] quads = cube.quads();
        if (quads.length > 6) return ALL_FACES;              // never baked so; then nothing is judged
        int kept = 0, there = 0;
        boolean solid = CubeWriter.solid(cube) && thick(cube);
        for (int q = 0; q < quads.length; q++) {
            if (quads[q] == null) continue;
            there++;
            int paint = paint(skin, quads[q]);
            if ((paint & SEEN) != 0) kept |= 1 << q;
            if ((paint & SOLID) == 0) solid = false;
        }
        if (solid) kept |= OPAQUE;
        if (solid && there == 6) kept |= CLOSED;
        return kept;
    }

    /**
     * At least a tenth of a model pixel every way, inflate counted (sizes are pixels, the inflate blocks):
     * a thinner box's front and back faces lie so close that the depth test may let the back one win
     * here and there, so its back faces stay.
     */
    private static boolean thick(GeoCube cube) {
        net.minecraft.world.phys.Vec3 size = cube.size();
        double grow = 2 * cube.inflate() * 16;
        return size.x() + grow >= 0.1 && size.y() + grow >= 0.1 && size.z() + grow >= 0.1;
    }

    /**
     * What the skin paints on {@code quad}'s patch: SEEN if any pixel there is not fully see-through,
     * SOLID if every one is fully opaque. A patch reaching off the picture (it would wrap) is SEEN only.
     */
    private static int paint(Skin skin, GeoQuad quad) {
        float u0 = Float.POSITIVE_INFINITY, u1 = Float.NEGATIVE_INFINITY;
        float v0 = Float.POSITIVE_INFINITY, v1 = Float.NEGATIVE_INFINITY;
        for (GeoVertex v : quad.vertices()) {
            u0 = Math.min(u0, v.texU()); u1 = Math.max(u1, v.texU());
            v0 = Math.min(v0, v.texV()); v1 = Math.max(v1, v.texV());
        }
        if (!(u0 >= 0f && u1 <= 1f && v0 >= 0f && v1 <= 1f)) return SEEN;
        span(u0, u1, skin.width, 0);
        span(v0, v1, skin.height, 2);
        if (SPAN[0] > SPAN[1] || SPAN[2] > SPAN[3]) return SEEN;
        boolean seen = false, solid = true;
        for (int y = SPAN[2]; y <= SPAN[3]; y++) {
            for (int x = SPAN[0]; x <= SPAN[1]; x++) {
                int a = skin.alpha[y * skin.width + x] & 0xFF;
                if (a != 0) seen = true;
                if (a != 0xFF) solid = false;
            }
        }
        return (seen ? SEEN : 0) | (solid ? SOLID : 0);
    }

    /**
     * The pixels (first, last) a patch from {@code a} to {@code b} (0..1) covers across {@code size}, into
     * {@link #SPAN} at {@code at}. A patch with no width on the picture takes the pixels either side of its line.
     */
    private static void span(float a, float b, int size, int at) {
        float lo = a * size, hi = b * size;
        int first = (int) Math.floor(lo + 1e-3f), last = (int) Math.ceil(hi - 1e-3f) - 1;
        if (last < first) {
            first = (int) Math.floor(lo - 1e-3f);
            last = (int) Math.floor(hi + 1e-3f);
        }
        SPAN[at] = Math.max(0, first);
        SPAN[at + 1] = Math.min(size - 1, last);
    }

    @Nullable
    private static Skin skin(ResourceLocation texture) {
        Skin skin = SKINS.get(texture);
        if (skin == null) {
            if (SKINS.size() >= 512) SKINS.clear();
            skin = load(texture);
            SKINS.put(texture, skin);
        }
        return skin == UNREADABLE ? null : skin;
    }

    /**
     * The picture drawn for {@code texture}: a built one (a farmer's dirt stage) from its pixels, a file
     * from the resource packs as Minecraft loads it. Anything else, or an animated one, is UNREADABLE.
     */
    private static Skin load(ResourceLocation texture) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            AbstractTexture loaded = minecraft.getTextureManager().getTexture(texture, null);
            if (loaded instanceof DynamicTexture built)
                return built.getPixels() == null ? UNREADABLE : copy(built.getPixels());
            if (loaded != null && loaded.getClass() != SimpleTexture.class)
                return UNREADABLE;                           // drawn by a texture class of someone else's
            Optional<Resource> file = minecraft.getResourceManager().getResource(texture);
            if (file.isEmpty()) return UNREADABLE;
            ResourceMetadata about = file.get().metadata();
            // Blurred: pixels bleed into their neighbours, so a see-through patch may still show at its
            // edges. Animated: a mod may play its frames. Either way nothing is judged.
            if (about.getSection(TextureMetadataSection.SERIALIZER).map(TextureMetadataSection::isBlur).orElse(false)
                    || about.getSection(AnimationMetadataSection.SERIALIZER).isPresent())
                return UNREADABLE;
            try (InputStream in = file.get().open(); NativeImage image = NativeImage.read(in)) {
                return copy(image);
            }
        } catch (Exception | LinkageError e) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[SkinFaces] could not read {}; its faces are all drawn", texture, e);
            }
            return UNREADABLE;
        }
    }

    private static Skin copy(NativeImage image) {
        int w = image.getWidth(), h = image.getHeight();
        byte[] alpha = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                alpha[y * w + x] = (byte) (image.getPixelRGBA(x, y) >>> 24);
        return new Skin(alpha, w, h);
    }

    /** A resource reload: pictures and baked bones may have changed. */
    static void forget() {
        SKINS.clear();
        end();
    }

    /** For {@code /villagerfps} and the {@code [ClientCost]} log. */
    static String state() {
        if (SWAPPED_SKINS) return "skin faces off (Entity Texture Features loaded)";
        int unreadable = 0;
        for (Skin skin : SKINS.values())
            if (skin == UNREADABLE) unreadable++;
        return "empty faces " + (skipEmpty ? "left out" : "drawn") + ", back faces "
                + backFaces.name().toLowerCase(java.util.Locale.ROOT) + ", skins read " + (SKINS.size() - unreadable)
                + (unreadable > 0 ? " (" + unreadable + " could not be read: all their faces drawn)" : "");
    }
}
