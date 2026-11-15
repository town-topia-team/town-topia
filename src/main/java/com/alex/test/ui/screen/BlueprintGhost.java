package com.alex.test.ui.screen;

import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The picked blueprint as a green ghost on the village model, turned and mirrored as
 * the player chose.
 *
 * <p>Turning and mirroring a blueprint and picking its outer shell is slow for big
 * files, so each Turn or Mirror works it out on a background thread ({@link Shape});
 * the old ghost stays on screen until the new one is ready. Small ghosts are then run
 * through the block tesselator once (real blocks, replayed each frame with a green
 * tint and only moved by the camera matrix when the ghost moves); ghosts with more
 * than {@link #MAX_REAL_BLOCKS} shell blocks are drawn as green column boxes from a
 * static GPU buffer instead, so a huge file never costs a frame more than one draw.
 */
final class BlueprintGhost {
    /**
     * Shell blocks replayed at most. Lower than the lone preview's cap: here the
     * village is drawn too, and replaying costs CPU every frame.
     */
    static final int MAX_REAL_BLOCKS = 4_000;
    private static final float[] TINT = {0.62f, 1f, 0.66f};
    private static final int BOX_COLOR = 0x7086D38F;

    /**
     * One turned and mirrored blueprint and what the desk needs of it. Per column
     * ({@code z * sizeX + x}): lowest and highest cell the build would touch (air too,
     * since air clears what is there; -1 when nothing), and lowest and highest real block.
     */
    record Shape(Blueprint blueprint, Rotation rotation, Mirror mirror, BlueprintPreviewView.Prepared prepared,
                 int[] low, int[] high, int[] solidLow, int[] solidHigh) {
        int sizeX() { return blueprint.sizeX(); }
        int sizeY() { return blueprint.sizeY(); }
        int sizeZ() { return blueprint.sizeZ(); }

        boolean realBlocks() {
            return prepared.shellCount() <= MAX_REAL_BLOCKS;
        }

        /**
         * How far below the first open cell over the ground the box starts, so a copied
         * building's floor sits level with the ground (its floor row plus one). 0 for every
         * other file: its bottom row stands on the ground as before.
         */
        int floorSink() {
            int row = blueprint.floorRow();
            return row < 0 ? 0 : row + 1;
        }
    }

    record Recording(RenderType type, RoomSchematicView.Recorder vertices) {}

    @Nullable private Blueprint source;
    @Nullable private BlueprintPreviewView.Prepared sourcePrepared;
    private Rotation rotation = Rotation.NONE;
    private Mirror mirror = Mirror.NONE;
    @Nullable private CompletableFuture<Shape> pending;
    @Nullable private Shape shape;

    // Made on the render thread from the shape, once.
    private boolean gpuDone;
    @Nullable private List<Recording> recordings;
    @Nullable private VertexBuffer boxes;

    // =========================================================================
    // What is shown
    // =========================================================================

    /** A new blueprint (or none): straight, not mirrored, worked out off-thread. */
    /** Most shell cells a shape keeps; the world ghost raises it before {@link #setSource}. */
    private int cellCap = BlueprintPreviewView.MAX_REAL_BLOCKS;

    void setCellCap(int cap) {
        cellCap = cap;
    }

    void setSource(@Nullable Blueprint blueprint, @Nullable BlueprintPreviewView.Prepared prepared) {
        if (blueprint == source) return;
        source = blueprint;
        sourcePrepared = prepared;
        rotation = Rotation.NONE;
        mirror = Mirror.NONE;
        shape = null;
        dropGpu();
        start();
    }

    void turn() {
        rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
        start();
    }

    void cycleMirror() {
        mirror = switch (mirror) {
            case NONE -> Mirror.LEFT_RIGHT;
            case LEFT_RIGHT -> Mirror.FRONT_BACK;
            case FRONT_BACK -> Mirror.NONE;
        };
        start();
    }

    /** Turned the other way (the Builder's plan scrolls both ways). */
    void turnBack() {
        rotation = rotation.getRotated(Rotation.COUNTERCLOCKWISE_90);
        start();
    }

    /** Sets turn and mirror outright, as the desk or the Builder's plan had them. */
    void setTransform(Rotation newRotation, Mirror newMirror) {
        if (newRotation == rotation && newMirror == mirror) return;
        rotation = newRotation;
        mirror = newMirror;
        start();
    }

    Rotation rotation() { return rotation; }
    Mirror mirror() { return mirror; }

    @Nullable Shape shape() {
        return shape;
    }

    /** A Turn or Mirror is still being worked out. */
    boolean busy() {
        return pending != null;
    }

    private void start() {
        if (pending != null) pending.cancel(false);
        pending = null;
        Blueprint from = source;
        if (from == null) return;
        BlueprintPreviewView.Prepared prepared = sourcePrepared;
        Rotation r = rotation;
        Mirror m = mirror;
        int cap = cellCap;
        pending = CompletableFuture.supplyAsync(() -> compute(from, prepared, r, m, cap), Util.backgroundExecutor());
    }

    /** Background thread: turn, pick the shell, measure the columns. */
    private static Shape compute(Blueprint from, @Nullable BlueprintPreviewView.Prepared fromPrepared,
                                 Rotation rotation, Mirror mirror, int cap) {
        Blueprint turned = from.transformed(rotation, mirror);
        boolean reuse = turned == from && fromPrepared != null
                && fromPrepared.cells().length >= Math.min(cap, fromPrepared.shellCount());
        BlueprintPreviewView.Prepared prepared = reuse ? fromPrepared : BlueprintPreviewView.prepare(turned, cap);
        int sx = turned.sizeX(), sz = turned.sizeZ();
        int[] low = new int[sx * sz], high = new int[sx * sz], solidLow = new int[sx * sz], solidHigh = new int[sx * sz];
        Arrays.fill(low, -1);
        Arrays.fill(high, -1);
        Arrays.fill(solidLow, -1);
        Arrays.fill(solidHigh, -1);
        List<BlockState> palette = turned.palette();
        boolean[] air = new boolean[palette.size()];
        for (int p = 0; p < air.length; p++) air[p] = palette.get(p).isAir();
        // Cells run Y outermost, so the first hit in a column is its lowest.
        for (int i = 0, volume = turned.volume(); i < volume; i++) {
            int p = turned.paletteIndex(i);
            if (p == Blueprint.VOID) continue;
            int column = turned.zOf(i) * sx + turned.xOf(i), y = turned.yOf(i);
            if (low[column] < 0) low[column] = y;
            high[column] = y;
            if (air[p]) continue;
            if (solidLow[column] < 0) solidLow[column] = y;
            solidHigh[column] = y;
        }
        return new Shape(turned, rotation, mirror, prepared, low, high, solidLow, solidHigh);
    }

    /** Picks up a finished Turn or Mirror. True when the ghost changed. Render thread. */
    boolean poll() {
        CompletableFuture<Shape> job = pending;
        if (job == null || !job.isDone()) return false;
        pending = null;
        if (job.isCancelled() || job.isCompletedExceptionally()) return false;
        shape = job.join();
        dropGpu();
        return true;
    }

    // =========================================================================
    // Drawing
    // =========================================================================

    /** Records the real blocks or builds the box buffer, once per shape. Render thread. */
    void prepareGpu(Minecraft minecraft, ClientLevel world) {
        Shape current = shape;
        if (current == null || gpuDone) return;
        gpuDone = true;
        if (current.realBlocks()) {
            recordings = record(minecraft, world, current);
        } else {
            boxes = buildBoxes(current);
        }
    }

    /** Real blocks into the GUI buffers, through {@code pose} (camera plus the ghost's corner). */
    void replay(MultiBufferSource.BufferSource buffers, PoseStack pose) {
        if (recordings == null) return;
        Matrix4f view = pose.last().pose();
        Matrix3f normal = pose.last().normal();
        for (Recording recording : recordings) {
            recording.vertices().replay(buffers.getBuffer(recording.type()), view, normal, TINT);
        }
    }

    boolean hasRecording() {
        return recordings != null;
    }

    /** The column boxes of a big ghost; {@code modelView} already moved to the ghost's corner. */
    void drawBoxes(Matrix4f modelView, Matrix4f projection) {
        VillageModelView.drawBuffer(boxes, modelView, projection);
    }

    static List<Recording> record(Minecraft minecraft, ClientLevel world, Shape shape) {
        Map<RenderType, RoomSchematicView.Recorder> byType = new LinkedHashMap<>();
        int[] cells = shape.prepared().cells();
        recordRange(minecraft, previewLevel(minecraft, world, shape), shape, cells, 0, cells.length, byType);
        return recordings(byType);
    }

    /** The stand-in world the block models are tesselated against. */
    static BlueprintPreviewView.PreviewLevel previewLevel(Minecraft minecraft, ClientLevel world, Shape shape) {
        BlockPos anchor = minecraft.player != null ? minecraft.player.blockPosition() : BlockPos.ZERO;
        return new BlueprintPreviewView.PreviewLevel(shape.blueprint(), world, anchor);
    }

    static List<Recording> recordings(Map<RenderType, RoomSchematicView.Recorder> byType) {
        List<Recording> out = new ArrayList<>();
        byType.forEach((type, recorder) -> out.add(new Recording(type, recorder)));
        return out;
    }

    /** Tesselates {@code cells[from..to)} into {@code byType}; a big ghost does this over several frames. */
    static void recordRange(Minecraft minecraft, BlueprintPreviewView.PreviewLevel level, Shape shape, int[] cells,
                            int from, int to, Map<RenderType, RoomSchematicView.Recorder> byType) {
        Blueprint blueprint = shape.blueprint();
        ModelBlockRenderer tesselator = minecraft.getBlockRenderer().getModelRenderer();
        PoseStack pose = new PoseStack();
        for (int k = from; k < to; k++) {
            int i = cells[k];
            int x = blueprint.xOf(i), y = blueprint.yOf(i), z = blueprint.zOf(i);
            BlockState state = blueprint.palette().get(blueprint.paletteIndex(i));
            // Chests, beds and signs draw through their own renderer: the ghost leaves them out.
            if (state.getRenderShape() != RenderShape.MODEL) continue;
            BlockPos pos = new BlockPos(x, y, z);
            pose.pushPose();
            pose.translate(x, y, z);
            try {
                BakedModel model = minecraft.getBlockRenderer().getBlockModel(state);
                long seed = state.getSeed(pos);
                for (RenderType type : model.getRenderTypes(state, RandomSource.create(seed), ModelData.EMPTY)) {
                    RoomSchematicView.Recorder recorder = byType.computeIfAbsent(
                            RoomSchematicView.viewRenderType(type), key -> new RoomSchematicView.Recorder());
                    tesselator.tesselateBlock(level, model, state, pos, pose, recorder, true,
                            RandomSource.create(), seed, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, type);
                }
            } catch (RuntimeException e) {
                // A modded model that needs a real world: skip that one block.
            }
            pose.popPose();
        }
    }

    /** One box per run of equal columns along X, in the blueprint's own coordinates. */
    @Nullable
    static VertexBuffer buildBoxes(Shape shape) {
        int sx = shape.sizeX(), sz = shape.sizeZ();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(4096)) {
            BufferBuilder out = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int z = 0; z < sz; z++) {
                int x = 0;
                while (x < sx) {
                    int lo = shape.solidLow()[z * sx + x], hi = shape.solidHigh()[z * sx + x];
                    int end = x + 1;
                    while (end < sx && shape.solidLow()[z * sx + end] == lo && shape.solidHigh()[z * sx + end] == hi) {
                        end++;
                    }
                    if (lo >= 0) VillageModelView.box(out, x, lo, z, end, hi + 1, z + 1, BOX_COLOR);
                    x = end;
                }
            }
            return VillageModelView.upload(null, out, VertexBuffer.Usage.STATIC);
        }
    }

    private void dropGpu() {
        gpuDone = false;
        recordings = null;
        if (boxes != null) boxes.close();
        boxes = null;
    }

    /** Stops any Turn in progress and frees the box buffer. */
    void close() {
        if (pending != null) pending.cancel(false);
        pending = null;
        source = null;
        sourcePrepared = null;
        shape = null;
        dropGpu();
    }
}
