package com.alex.test.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BuiltInModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.FastColor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Held items (sword, tools, the raised shield) written from a ready copy of what Minecraft writes.
 * Minecraft builds a held item afresh every frame, a face at a time with new vectors for every
 * corner (a sword is about 70 faces, 280 corners). Here Minecraft's own item drawing runs ONCE per
 * item model and way of holding it (display context, shimmer on or off) into a recorder, in the
 * item's own frame; every frame after that only moves the recorded corners into place and writes
 * them: same render types in the same order, same colours, shimmer, light and hurt tint.
 *
 * <p>Only what records exactly: Minecraft's plain item models (overrides already picked, so a drawn
 * bow is its own model), with no tinted faces and no light baked in, and the plain shield. Banner
 * shields, dyed or potion-tinted items, compass, clock, trident, spyglass and other mods' models go
 * Minecraft's way, and so does everything under a shader pack (Iris tags each held item itself).
 * Render thread only.
 */
final class ReadyItem {

    /** {@code /villagerfps items ready|minecraft}. */
    static volatile boolean enabled = true;

    private static int drawn, missed, lastDrawn, lastMissed, countFrame = -1;

    /** Per item model, its ready copies by way of holding ({@link #slot}). Cleared on a resource reload. */
    private static final Map<BakedModel, Ready[]> READY = new IdentityHashMap<>();
    /** Stands for "this one goes Minecraft's way". */
    private static final Ready REFUSED = new Ready(null, new Group[0]);
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Direction[] LISTS = {Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST, null};
    private static final Vector3f POS = new Vector3f();
    private static final Vector3f NORMAL = new Vector3f();
    private static BakedModel missingModel;
    private static boolean fabulous;

    private ReadyItem() {}

    /**
     * Draws {@code stack} as {@code ItemRenderer.renderStatic(entity, stack, context, false, ...)} would;
     * false when it has to go Minecraft's way.
     */
    static boolean render(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                          MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!enabled || stack.isEmpty()) return false;
        roll();
        if (stack.is(Items.TRIDENT) || stack.is(Items.SPYGLASS) || stack.is(ItemTags.COMPASSES)
                || stack.is(Items.CLOCK) || IrisShadowPass.packInUse()) {
            missed++;
            return false;
        }
        ItemRenderer items = Minecraft.getInstance().getItemRenderer();
        BakedModel model = items.getModel(stack, entity.level(), entity, entity.getId());
        Class<?> kind = model.getClass();
        if (kind != SimpleBakedModel.class && !(kind == BuiltInModel.class && plainShield(stack))) {
            missed++;
            return false;
        }
        Ready ready = ready(items, model, stack, context);
        if (ready == REFUSED || ready.item != stack.getItem()) {
            missed++;
            return false;
        }
        ready.write(poseStack.last(), buffers, packedLight, packedOverlay);
        drawn++;
        return true;
    }

    /** A shield with no banner on it, drawn by Minecraft's own item renderer: always the same picture. */
    private static boolean plainShield(ItemStack stack) {
        return stack.is(Items.SHIELD)
                && stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers().isEmpty()
                && stack.get(DataComponents.BASE_COLOR) == null
                && IClientItemExtensions.of(stack) == IClientItemExtensions.DEFAULT;
    }

    private static Ready ready(ItemRenderer items, BakedModel model, ItemStack stack, ItemDisplayContext context) {
        // New models after a resource reload; Fabulous graphics picks another shimmer for see-through items.
        BakedModel missing = Minecraft.getInstance().getModelManager().getMissingModel();
        boolean fab = Minecraft.useShaderTransparency();
        if (missing != missingModel || fab != fabulous || READY.size() > 512) {
            READY.clear();
            missingModel = missing;
            fabulous = fab;
        }
        int slot = context.ordinal() * 2 + (stack.hasFoil() ? 1 : 0);
        Ready[] ways = READY.get(model);
        if (ways == null || ways.length <= slot) {
            ways = ways == null ? new Ready[slot + 2] : Arrays.copyOf(ways, slot + 2);
            READY.put(model, ways);
        }
        Ready ready = ways[slot];
        if (ready == null) ways[slot] = ready = record(items, model, stack, context);
        return ready;
    }

    /** Runs Minecraft's item drawing once into a recorder, unlit, in the item's own frame. */
    private static Ready record(ItemRenderer items, BakedModel model, ItemStack stack, ItemDisplayContext context) {
        if (model instanceof SimpleBakedModel && tinted(model)) return REFUSED;   // colour from the stack
        Recording recording = new Recording();
        try {
            items.render(stack, context, false, new PoseStack(), recording, 0, OverlayTexture.NO_OVERLAY, model);
        } catch (RuntimeException e) {
            return REFUSED;
        }
        return recording.finish(stack.getItem());
    }

    private static boolean tinted(BakedModel model) {
        for (Direction list : LISTS) {
            RANDOM.setSeed(42L);
            for (BakedQuad quad : model.getQuads(null, list, RANDOM))
                if (quad.isTinted()) return true;
        }
        return false;
    }

    private static boolean isGlint(RenderType type) {
        return type == RenderType.glint() || type == RenderType.glintTranslucent() || type == RenderType.entityGlint()
                || type == RenderType.entityGlintDirect() || type == RenderType.armorEntityGlint();
    }

    /** A new frame: last frame's counts are kept for the reports. */
    private static void roll() {
        int frame = VillagerFrameStats.frame();
        if (frame == countFrame) return;
        countFrame = frame;
        lastDrawn = drawn;
        lastMissed = missed;
        drawn = missed = 0;
    }

    /** For {@code /villagerfps}. */
    static String report() {
        roll();
        if (!enabled) return "Held items: Minecraft's own item drawing every frame (/villagerfps items ready)";
        return "Held items: ready copies (Minecraft's drawing recorded once per item and way of holding, then only "
                + "moved into place). Last frame: " + lastDrawn + " ready, " + lastMissed + " Minecraft's way"
                + (IrisShadowPass.packInUse() ? " (shader pack on: all Minecraft's way)" : "")
                + "; " + READY.size() + " item models kept (/villagerfps items minecraft to compare)";
    }

    /** The same on one line, for the {@code [ClientCost]} log. */
    static String compact() {
        roll();
        return enabled ? "items ready " + lastDrawn + ", Minecraft's " + lastMissed : "items Minecraft's";
    }

    /** One item model held one way: its render types in Minecraft's order, each with its corners. */
    private record Ready(Item item, Group[] groups) {
        void write(PoseStack.Pose pose, MultiBufferSource buffers, int light, int overlay) {
            for (Group group : groups) {
                // ItemRenderer.getFoilBuffer(Direct): the shimmer's buffer first, then the item's.
                VertexConsumer out = group.glint == null ? buffers.getBuffer(group.type)
                        : VertexMultiConsumer.create(buffers.getBuffer(group.glint), buffers.getBuffer(group.type));
                group.write(pose.pose(), pose.normal(), out, light, overlay);
            }
        }
    }

    /** Corners for one render type: x y z u v and normal per corner, in the item's frame, plus colours. */
    private record Group(RenderType glint, RenderType type, float[] corners, int[] colours) {
        void write(Matrix4f pose, Matrix3f normal, VertexConsumer out, int light, int overlay) {
            float lx = Float.NaN, ly = 0f, lz = 0f;
            for (int i = 0, f = 0; i < colours.length; i++, f += 8) {
                pose.transformPosition(corners[f], corners[f + 1], corners[f + 2], POS);
                float nx = corners[f + 5], ny = corners[f + 6], nz = corners[f + 7];
                if (nx != lx || ny != ly || nz != lz) {          // a face's corners share it
                    lx = nx;
                    ly = ny;
                    lz = nz;
                    normal.transform(nx, ny, nz, NORMAL);
                    if (NORMAL.lengthSquared() > 0f) NORMAL.normalize();
                }
                out.addVertex(POS.x, POS.y, POS.z, colours[i], corners[f + 3], corners[f + 4], overlay, light,
                        NORMAL.x, NORMAL.y, NORMAL.z);
            }
        }
    }

    /** Hands out one {@link Recorder} per buffer asked for, in order. */
    private static final class Recording implements MultiBufferSource {
        final List<RenderType> types = new ArrayList<>();
        final List<Recorder> recorders = new ArrayList<>();

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            Recorder recorder = new Recorder();
            types.add(type);
            recorders.add(recorder);
            return recorder;
        }

        /** Pairs each shimmer buffer with the item buffer asked for after it; anything else is refused. */
        Ready finish(Item item) {
            List<Group> groups = new ArrayList<>();
            int corners = 0;
            for (int i = 0; i < types.size(); i++) {
                RenderType glint = null;
                if (isGlint(types.get(i))) {
                    if (i + 1 >= types.size() || isGlint(types.get(i + 1))
                            || recorders.get(i).count != recorders.get(i + 1).count)
                        return REFUSED;
                    glint = types.get(i++);
                }
                Recorder recorder = recorders.get(i);
                if (recorder.lit) return REFUSED;                // light baked into the model
                corners += recorder.count;
                groups.add(new Group(glint, types.get(i), Arrays.copyOf(recorder.corners, recorder.count * 8),
                        Arrays.copyOf(recorder.colours, recorder.count)));
            }
            return corners == 0 ? REFUSED : new Ready(item, groups.toArray(new Group[0]));
        }
    }

    /** Keeps what is written to it. Light is recorded as 0 so only light baked into a model shows up. */
    private static final class Recorder implements VertexConsumer {
        float[] corners = new float[8 * 64];
        int[] colours = new int[64];
        int count;
        boolean lit;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (count == colours.length) {
                colours = Arrays.copyOf(colours, count * 2);
                corners = Arrays.copyOf(corners, count * 16);
            }
            int f = count * 8;
            corners[f] = x;
            corners[f + 1] = y;
            corners[f + 2] = z;
            corners[f + 3] = corners[f + 4] = corners[f + 5] = corners[f + 6] = corners[f + 7] = 0f;
            colours[count] = -1;
            count++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            if (count > 0) colours[count - 1] = FastColor.ARGB32.color(alpha, red, green, blue);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (count > 0) {
                corners[count * 8 - 5] = u;
                corners[count * 8 - 4] = v;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;                                         // hurt tint: the frame's is used
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            if (u != 0 || v != 0) lit = true;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            if (count > 0) {
                corners[count * 8 - 3] = x;
                corners[count * 8 - 2] = y;
                corners[count * 8 - 1] = z;
            }
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int colour, float u, float v, int overlay, int light,
                              float normalX, float normalY, float normalZ) {
            addVertex(x, y, z);
            colours[count - 1] = colour;
            setUv(u, v);
            if (light != 0) lit = true;
            setNormal(normalX, normalY, normalZ);
        }
    }
}
