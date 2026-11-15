package com.alex.test.ui.screen;

import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Builder's plan's blueprint, drawn see-through in the real world.
 *
 * <p>While loose it follows the block the player looks at (up to {@link #REACH} away) and
 * stands on the ground there; pinned, it stays put. Turning and mirroring are worked out
 * off-thread by the desk's own {@link BlueprintGhost}; the blocks are then baked once
 * into a static GPU buffer, so a frame costs two draws (depth, then colour: only the
 * nearest faces show, at half strength). Blueprints with more than
 * {@link BlueprintGhost#MAX_REAL_BLOCKS} outside blocks show as green column boxes.
 *
 * <p>Checks, like the desk: every column inside the village border, no registered
 * building in the way (both red, and the spot can't be confirmed). Blocks already
 * standing where the blueprint puts a block are found a slice per tick and shown as
 * red boxes; they don't stop a confirm.
 */
final class PlanWorldGhost {
    /** How far away the loose ghost follows the look, in blocks. */
    static final int REACH = 64;
    /** How far down from the looked-at spot the ghost looks for ground to stand on. */
    private static final int GROUND_SEARCH = 24;
    /** Blueprint cells checked against the world per tick. */
    private static final int SCAN_PER_TICK = 24_000;
    /** Red boxes drawn at most. */
    private static final int MAX_RED = 4_096;
    private static final int RED_BLOCK = 0x70E05040, RED_COLUMN = 0x50E07A6E;
    private static final int GOOD_LINE = 0xFF7FE08A, BAD_LINE = 0xFFFF6A5E;

    private static final RenderType GHOST = RenderType.create("dynamicvillages_plan_ghost",
            DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 256, false, true,
            RenderType.CompositeState.builder()
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .createCompositeState(false));

    private final BuilderPlanClient.Carried carried;
    private final BlueprintGhost ghost = new BlueprintGhost();

    // Where it stands: the footprint's middle column and the height of its lowest layer.
    private boolean placed, pinned;
    private int centreX, centreZ, baseY;
    /** Up/Down while loose: added to the ground height under the look. */
    private int lift;
    /** The desk's spot, used once the turned shape is ready. */
    @Nullable private BlockPos wantOrigin;
    @Nullable private BlockPos origin;
    private boolean dirty;

    // What the checks found.
    private boolean inside = true;
    @Nullable private String clashName;
    private final List<int[]> redColumns = new ArrayList<>();
    private final List<int[]> inTheWay = new ArrayList<>();
    private int inTheWayCount, scanIndex = -1;

    // GPU, made on the render thread.
    private boolean blocksBaked;
    /** Shell blocks the world ghost draws as real blocks; more than this shows as column boxes. */
    private static final int WORLD_MAX_REAL_BLOCKS = 60_000;
    /** Blocks tesselated per frame while a big ghost is being baked. */
    private static final int RECORD_PER_FRAME = 1_500;
    @Nullable private java.util.Map<RenderType, RoomSchematicView.Recorder> recording;
    @Nullable private BlueprintPreviewView.PreviewLevel recordLevel;
    @Nullable private int[] recordCells;
    private int recordIndex;
    /** One layer at a time: the highest layer shown, or -1 for the whole blueprint. */
    private int layer = -1;
    @Nullable private VertexBuffer blocks, boxes, red, frame;
    private boolean redDirty, frameDirty;

    PlanWorldGhost(BuilderPlanClient.Carried carried) {
        this.carried = carried;
        ghost.setCellCap(WORLD_MAX_REAL_BLOCKS);
        ghost.setSource(carried.blueprint(), carried.prepared());
        ghost.setTransform(carried.rotation(), carried.mirror());
        if (carried.origin() != null) {
            wantOrigin = carried.origin();
            pinned = true;
        }
    }

    // =========================================================================
    // State
    // =========================================================================

    BuilderPlanClient.Carried carried() {
        return carried;
    }

    String name() {
        return carried.blueprint().name();
    }

    @Nullable BlueprintGhost.Shape shape() {
        return ghost.shape();
    }

    boolean busy() {
        return ghost.busy();
    }

    boolean pinned() {
        return pinned;
    }

    boolean placed() {
        return placed && origin != null;
    }

    @Nullable BlockPos origin() {
        return origin;
    }

    boolean valid() {
        return placed() && inside && clashName == null;
    }

    /** Why it can't go here, or null when it can. */
    @Nullable String reason() {
        if (!placed()) return "Look at the ground where it should go";
        if (!inside) return "Part of it is outside the village border";
        if (clashName != null) return "It clashes with " + clashName;
        return null;
    }

    /** Blocks already standing where the blueprint puts one; -1 while still looking. */
    int inTheWay() {
        return scanIndex >= 0 ? -1 : inTheWayCount;
    }

    Rotation rotation() {
        return ghost.rotation();
    }

    Mirror mirror() {
        return ghost.mirror();
    }

    Direction facing() {
        return ghost.rotation().rotate(ghost.mirror().mirror(Direction.NORTH));
    }

    // =========================================================================
    // Controls
    // =========================================================================

    void togglePin() {
        if (!placed()) return;
        pinned = !pinned;
        if (!pinned) lift = 0;
    }

    void turn(boolean back) {
        if (back) {
            ghost.turnBack();
        } else {
            ghost.turn();
        }
    }

    void cycleMirror() {
        ghost.cycleMirror();
    }

    /** Arrow keys: one block across the ground. Pins it first, so it stops following the look. */
    void nudge(int dx, int dz) {
        if (!placed()) return;
        pinned = true;
        centreX += dx;
        centreZ += dz;
        dirty = true;
    }

    void raise(int dy) {
        if (pinned) {
            baseY += dy;
            dirty = true;
        } else {
            lift += dy;
        }
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    void tick(Minecraft minecraft) {
        ClientLevel world = minecraft.level;
        if (world == null || minecraft.player == null) return;
        if (ghost.poll()) {
            dropBlocks();
            dirty = true;
        }
        BlueprintGhost.Shape shape = ghost.shape();
        if (shape == null) return;
        if (wantOrigin != null) {
            if (ghost.busy()) return;
            centreX = wantOrigin.getX() + shape.sizeX() / 2;
            centreZ = wantOrigin.getZ() + shape.sizeZ() / 2;
            baseY = wantOrigin.getY();
            wantOrigin = null;
            placed = true;
            dirty = true;
        }
        if (!pinned) follow(minecraft, world, shape);
        if (placed) {
            BlockPos now = new BlockPos(centreX - shape.sizeX() / 2, baseY, centreZ - shape.sizeZ() / 2);
            if (!now.equals(origin)) {
                origin = now;
                dirty = true;
            }
        }
        if (dirty && origin != null) {
            dirty = false;
            check(shape);
            inTheWay.clear();
            inTheWayCount = 0;
            scanIndex = 0;
            redDirty = true;
            frameDirty = true;
        }
        if (scanIndex >= 0 && origin != null) scan(world, shape);
    }

    /**
     * Loose: the column in front of the face looked at, standing on the first ground below it
     * (a copied building with its floor level with that ground).
     */
    private void follow(Minecraft minecraft, ClientLevel world, BlueprintGhost.Shape shape) {
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 end = eye.add(minecraft.player.getViewVector(1.0F).scale(REACH));
        BlockHitResult hit = world.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                minecraft.player));
        if (hit.getType() == HitResult.Type.MISS) return;
        BlockPos spot = hit.getBlockPos().relative(hit.getDirection());
        int y = spot.getY();
        for (int i = 0; i < GROUND_SEARCH && y > world.getMinBuildHeight(); i++) {
            BlockPos below = new BlockPos(spot.getX(), y - 1, spot.getZ());
            if (world.getBlockState(below).blocksMotion()) break;
            y--;
        }
        centreX = spot.getX();
        centreZ = spot.getZ();
        baseY = y - shape.floorSink() + lift;
        placed = true;
    }

    /** Border and registered buildings, per column, like the desk's check. */
    private void check(BlueprintGhost.Shape shape) {
        int[] border = carried.border();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int sx = shape.sizeX(), sz = shape.sizeZ();
        List<BuilderDeskMenu.BuildingRow> near = new ArrayList<>();
        for (BuilderDeskMenu.BuildingRow row : carried.buildings()) {
            if (row.shellMax().getX() >= ox && row.shellMin().getX() < ox + sx
                    && row.shellMax().getZ() >= oz && row.shellMin().getZ() < oz + sz) near.add(row);
        }
        inside = true;
        clashName = null;
        redColumns.clear();
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                int column = z * sx + x;
                if (shape.low()[column] < 0) continue;
                int wx = ox + x, wz = oz + z;
                int y0 = oy + shape.low()[column], y1 = oy + shape.high()[column];
                if (wx < border[0] || wx > border[2] || wz < border[1] || wz > border[3]) {
                    inside = false;
                    if (redColumns.size() < MAX_RED) redColumns.add(new int[] {x, y0 - oy, z, y1 - oy});
                    continue;
                }
                for (BuilderDeskMenu.BuildingRow row : near) {
                    if (wx < row.shellMin().getX() || wx > row.shellMax().getX()
                            || wz < row.shellMin().getZ() || wz > row.shellMax().getZ()) continue;
                    int lo = Math.max(y0, row.shellMin().getY()), hi = Math.min(y1, row.shellMax().getY());
                    if (lo > hi) continue;
                    if (clashName == null) clashName = row.name();
                    if (redColumns.size() < MAX_RED) redColumns.add(new int[] {x, lo - oy, z, hi - oy});
                }
            }
        }
    }

    /** A slice of the blueprint's blocks against the world: what already stands where one goes. */
    private void scan(ClientLevel world, BlueprintGhost.Shape shape) {
        Blueprint blueprint = shape.blueprint();
        List<BlockState> palette = blueprint.palette();
        int volume = blueprint.volume();
        int end = Math.min(volume, scanIndex + SCAN_PER_TICK);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = scanIndex; i < end; i++) {
            int p = blueprint.paletteIndex(i);
            if (p == Blueprint.VOID) continue;
            BlockState wanted = palette.get(p);
            if (wanted.isAir()) continue;
            int x = blueprint.xOf(i), y = blueprint.yOf(i), z = blueprint.zOf(i);
            at.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            if (!world.hasChunkAt(at)) continue;
            BlockState there = world.getBlockState(at);
            if (there.isAir() || there.canBeReplaced() || there == wanted) continue;
            inTheWayCount++;
            if (inTheWay.size() < MAX_RED) inTheWay.add(new int[] {x, y, z});
        }
        scanIndex = end >= volume ? -1 : end;
        if (scanIndex < 0) redDirty = true;
    }

    // =========================================================================
    // Drawing
    // =========================================================================

    /** Draws the ghost; {@code cameraPos} is where the camera stands. Render thread. */
    void render(Minecraft minecraft, Vec3 cameraPos) {
        BlueprintGhost.Shape shape = ghost.shape();
        if (shape == null || origin == null || minecraft.level == null) return;
        if (!blocksBaked) bake(minecraft, minecraft.level, shape);
        if (redDirty) buildRed();
        if (frameDirty) buildFrame(shape);

        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).translate(
                (float) (origin.getX() - cameraPos.x), (float) (origin.getY() - cameraPos.y),
                (float) (origin.getZ() - cameraPos.z));
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        boolean good = valid();

        if (blocks != null) {
            GHOST.setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            if (shader != null) {
                if (good) {
                    RenderSystem.setShaderColor(0.75F, 1.0F, 0.8F, 0.55F);
                } else {
                    RenderSystem.setShaderColor(1.0F, 0.6F, 0.55F, 0.55F);
                }
                blocks.bind();
                // Depth first, then colour where it matches: only the nearest faces show.
                RenderSystem.depthMask(true);
                RenderSystem.colorMask(false, false, false, false);
                blocks.drawWithShader(modelView, projection, shader);
                RenderSystem.colorMask(true, true, true, true);
                RenderSystem.depthMask(false);
                RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
                blocks.drawWithShader(modelView, projection, shader);
                VertexBuffer.unbind();
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                RenderSystem.depthMask(true);
            }
            GHOST.clearRenderState();
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        if (boxes != null) VillageModelView.drawBuffer(boxes, modelView, projection);
        VillageModelView.drawBuffer(red, modelView, projection);
        // The frame shows through everything, so the spot can be found behind hills and walls.
        RenderSystem.disableDepthTest();
        VillageModelView.drawBuffer(frame, modelView, projection);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** The blocks (or column boxes for a big one) into a static buffer, once per turned shape. */
    private void bake(Minecraft minecraft, ClientLevel world, BlueprintGhost.Shape shape) {
        if (shape.prepared().capped()) {
            // Too big even here: column boxes (and no layers).
            blocksBaked = true;
            boxes = BlueprintGhost.buildBoxes(shape);
            return;
        }
        if (recording == null) {
            recording = new java.util.LinkedHashMap<>();
            recordLevel = BlueprintGhost.previewLevel(minecraft, world, shape);
            recordCells = layer < 0 ? shape.prepared().cells() : layerCells(shape);
            recordIndex = 0;
        }
        int end = Math.min(recordCells.length, recordIndex + RECORD_PER_FRAME);
        BlueprintGhost.recordRange(minecraft, recordLevel, shape, recordCells, recordIndex, end, recording);
        recordIndex = end;
        if (end < recordCells.length) return;
        blocksBaked = true;
        List<BlueprintGhost.Recording> recordings = BlueprintGhost.recordings(recording);
        recording = null;
        recordLevel = null;
        recordCells = null;
        float[] plain = {1.0F, 1.0F, 1.0F};
        Matrix4f identity = new Matrix4f();
        Matrix3f normal = new Matrix3f();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 20)) {
            BufferBuilder out = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            for (BlueprintGhost.Recording recording : recordings) {
                recording.vertices().replay(out, identity, normal, plain);
            }
            blocks = VillageModelView.upload(blocks, out, VertexBuffer.Usage.STATIC);
        }
    }

    private void buildRed() {
        redDirty = false;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(4096)) {
            BufferBuilder out = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int[] c : redColumns) {
                VillageModelView.box(out, c[0] - 0.01F, c[1] - 0.01F, c[2] - 0.01F, c[0] + 1.01F, c[3] + 1.01F,
                        c[2] + 1.01F, RED_COLUMN);
            }
            for (int[] b : inTheWay) {
                VillageModelView.box(out, b[0] - 0.02F, b[1] - 0.02F, b[2] - 0.02F, b[0] + 1.02F, b[1] + 1.02F,
                        b[2] + 1.02F, RED_BLOCK);
            }
            red = VillageModelView.upload(red, out, VertexBuffer.Usage.DYNAMIC);
        }
    }

    private void buildFrame(BlueprintGhost.Shape shape) {
        frameDirty = false;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(1024)) {
            BufferBuilder out = new BufferBuilder(bytes, VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            VillageModelView.outline(out, 0, 0, 0, shape.sizeX(), shape.sizeY(), shape.sizeZ(),
                    valid() ? GOOD_LINE : BAD_LINE);
            frame = VillageModelView.upload(frame, out, VertexBuffer.Usage.DYNAMIC);
        }
    }

    /** Layer view: the outside blocks below the layer, and every block of the layer itself. */
    private int[] layerCells(BlueprintGhost.Shape shape) {
        Blueprint blueprint = shape.blueprint();
        it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int i : shape.prepared().cells()) {
            if (blueprint.yOf(i) < layer) out.add(i);
        }
        if (layer < blueprint.sizeY()) {
            for (int z = 0; z < blueprint.sizeZ(); z++) {
                for (int x = 0; x < blueprint.sizeX(); x++) {
                    int i = blueprint.index(x, layer, z);
                    int p = blueprint.paletteIndex(i);
                    if (p != Blueprint.VOID && !blueprint.palette().get(p).isAir()) out.add(i);
                }
            }
        }
        return out.toIntArray();
    }

    /** L: one layer at a time (starting at the bottom), or the whole blueprint again. */
    void toggleLayers() {
        layer = layer < 0 ? 0 : -1;
        dropBlocks();
    }

    /** Sneak + scroll in layer view: the next layer up or down. */
    void stepLayer(int dir) {
        BlueprintGhost.Shape shape = ghost.shape();
        if (layer < 0 || shape == null) return;
        int next = Math.max(0, Math.min(shape.sizeY() - 1, layer + dir));
        if (next == layer) return;
        layer = next;
        dropBlocks();
    }

    /** The layer shown (0 = bottom), or -1 when the whole blueprint shows. */
    int layer() {
        return layer;
    }

    private void dropBlocks() {
        recording = null;
        recordLevel = null;
        recordCells = null;
        blocksBaked = false;
        if (blocks != null) blocks.close();
        if (boxes != null) boxes.close();
        blocks = boxes = null;
    }

    /** Frees every buffer and stops any turn being worked out. */
    void close() {
        dropBlocks();
        if (red != null) red.close();
        if (frame != null) frame.close();
        red = frame = null;
        ghost.close();
    }
}
