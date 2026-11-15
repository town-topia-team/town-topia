package com.alex.test.ui.screen;

import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A rotatable 3D preview of one blueprint on the Builder's desk, drawn with the
 * game's real block models.
 *
 * <p>The camera and the record-once, replay-every-frame drawing are those of
 * {@link RoomSchematicView}, which reads its blocks from the world and so could not
 * take a blueprint without big changes. Only the outer shell is drawn (blocks that
 * touch open air reachable from outside the box), at most {@link #MAX_REAL_BLOCKS}:
 * working that out ({@link #prepare}) runs off the render thread, the models are run
 * through the tesselator once when a blueprint is shown, and each frame only replays
 * the stored vertices through the camera.
 */
final class BlueprintPreviewView {
    /** Real blocks drawn at most; beyond it the view shows the top and outer walls first. */
    static final int MAX_REAL_BLOCKS = 8_000;
    /** Chests, beds, signs and other blocks drawn through their own renderer, live each frame. */
    private static final int MAX_STAND_INS = 256;

    private static final int VIEW = 0xFF111111;
    private static final int PANEL_BORDER = 0xFF454545;
    private static final int PLATE = 0xFF2B2B2B;
    // The world's entity light directions (Lighting.setupLevel), for chests and beds.
    private static final Vector3f WORLD_LIGHT_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f WORLD_LIGHT_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final double DEFAULT_YAW = Math.PI / 4;
    private static final double DEFAULT_PITCH = Math.toRadians(30);
    private static final double MIN_PITCH = Math.toRadians(18);
    private static final double MAX_PITCH = Math.toRadians(75);
    private static final double MIN_ZOOM = 0.5, MAX_ZOOM = 8;
    // GUI z band for the model: above the panel (z 0), below the overlay buttons and tooltips.
    private static final float Z_MID = 200, Z_SPAN = 140;
    private static final float[] NO_TINT = {1f, 1f, 1f};

    /** {@link Prepared#topLayer} of the whole blueprint: nothing cut off. */
    static final int WHOLE = Integer.MAX_VALUE;

    /**
     * What to draw of a blueprint: flat cell indexes, most visible first, how many shell
     * blocks there were before the cap, and the highest layer looked at ({@link #WHOLE}: all).
     */
    record Prepared(Blueprint blueprint, int[] cells, int shellCount, int topLayer) {
        boolean capped() {
            return cells.length < shellCount;
        }
    }

    private double yaw = DEFAULT_YAW;
    private double pitch = DEFAULT_PITCH;
    private double zoom = 1;
    private double panX, panY;
    private int dragButton = -1;
    /** The point a left drag turns around, fixed when the drag starts (null: not found yet). */
    @Nullable private double[] turnPivot;
    private int viewLeft, viewTop, viewRight = 1, viewBottom = 1;

    @Nullable private Prepared shown;
    /** What the recordings show: {@link #shown}, or its cut copy once worked out (Layers). */
    @Nullable private Prepared drawn;
    /** Highest layer shown while the top is cut off, or {@link #WHOLE}. */
    private int cutTop = WHOLE;
    /** The cut being worked out off the render thread, or null. */
    @Nullable private CompletableFuture<Prepared> cutting;
    private double centerX, centerZ, radius = 1, height = 1;

    // Camera, rebuilt only when it moves.
    private boolean viewDirty = true;
    private double cosYaw, sinYaw, sinPitch, cosPitch, viewScale, originX, originY, zScale;
    private final Matrix4f viewRotation = new Matrix4f();

    private record Recording(RenderType type, RoomSchematicView.Recorder vertices) {}
    @Nullable private List<Recording> recordings;
    private final List<BlockEntity> standIns = new ArrayList<>();

    // =========================================================================
    // What is shown
    // =========================================================================

    // The six steps of the outside-air flood, in pairs (d ^ 1 is the opposite side); bit d of a
    // side mask stands for SIDES[d].
    private static final Direction[] SIDES = {Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN,
            Direction.SOUTH, Direction.NORTH};
    private static final int[] DX = {1, -1, 0, 0, 0, 0}, DY = {0, 0, 1, -1, 0, 0}, DZ = {0, 0, 0, 0, 1, -1};
    private static final int UP = 2;
    private static final int[] AROUND = {0, 1, 4, 5};
    private static final byte ALL_SIDES_FULL = 0b111111;

    /**
     * Picks the cells worth drawing. Pure maths over the blueprint plus each block's
     * occlusion shape, so it may (and should) run off the render thread.
     */
    static Prepared prepare(Blueprint blueprint) {
        return prepare(blueprint, MAX_REAL_BLOCKS);
    }

    /** As {@link #prepare(Blueprint)} with its own cap (the world ghost bakes many more). */
    static Prepared prepare(Blueprint blueprint, int cap) {
        return prepare(blueprint, cap, WHOLE);
    }

    /**
     * As {@link #prepare(Blueprint, int)} with every layer above {@code topLayer} taken off.
     * Those layers count as open air, so it pours into the rooms below and their floors,
     * beds and chests are drawn: the Layers cut.
     */
    static Prepared prepare(Blueprint blueprint, int cap, int topLayer) {
        int sx = blueprint.sizeX(), sz = blueprint.sizeZ();
        int sy = Mth.clamp(topLayer, 0, blueprint.sizeY() - 1) + 1;
        // Cells run x, then z, then y: the layers kept are the first indexes.
        int volume = sx * sz * sy;
        List<BlockState> palette = blueprint.palette();
        boolean[] empty = new boolean[palette.size()];
        byte[] full = new byte[palette.size()];
        for (int p = 0; p < palette.size(); p++) {
            BlockState state = palette.get(p);
            empty[p] = state.isAir();
            full[p] = empty[p] ? 0 : fullSides(state);
        }

        // Open air reachable from outside the box: what a camera outside could look through.
        // Air passes any side that is not a full square, so it gets into the open half of a
        // stair or slab and on to the blocks seen through it.
        BitSet outside = new BitSet(volume);
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        for (int i = 0; i < volume; i++) {
            int x = blueprint.xOf(i), y = blueprint.yOf(i), z = blueprint.zOf(i);
            boolean edge = x == 0 || z == 0 || y == 0 || x == sx - 1 || z == sz - 1 || y == sy - 1;
            if (!edge) continue;
            int walls = fullAt(blueprint, full, i);
            for (int d = 0; d < 6; d++) {
                if ((walls & 1 << d) == 0 && !kept(blueprint, sy, x + DX[d], y + DY[d], z + DZ[d])) {
                    outside.set(i);
                    queue.enqueue(i);
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            int i = queue.dequeueInt();
            int x = blueprint.xOf(i), y = blueprint.yOf(i), z = blueprint.zOf(i);
            int walls = fullAt(blueprint, full, i);
            for (int d = 0; d < 6; d++) {
                if ((walls & 1 << d) != 0) continue;
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                if (!kept(blueprint, sy, nx, ny, nz)) continue;
                int j = blueprint.index(nx, ny, nz);
                if (!outside.get(j) && (fullAt(blueprint, full, j) & 1 << (d ^ 1)) == 0) {
                    outside.set(j);
                    queue.enqueue(j);
                }
            }
        }

        // Shell: blocks seen from above first (roofs, floors of open yards), then the
        // rest that touch outside air sideways or hold some themselves. The camera
        // never looks from below, so a block open only underneath is left out.
        IntArrayList top = new IntArrayList(), sides = new IntArrayList();
        for (int i = 0; i < volume; i++) {
            int p = blueprint.paletteIndex(i);
            if (p == Blueprint.VOID || empty[p]) continue;
            int x = blueprint.xOf(i), y = blueprint.yOf(i), z = blueprint.zOf(i);
            if (y + 1 >= sy || seenFrom(blueprint, sy, full, outside, x, y, z, UP)) {
                top.add(i);
                continue;
            }
            boolean side = outside.get(i) || x == 0 || z == 0 || x == sx - 1 || z == sz - 1;
            for (int k = 0; k < AROUND.length && !side; k++) {
                side = seenFrom(blueprint, sy, full, outside, x, y, z, AROUND[k]);
            }
            if (side) sides.add(i);
        }
        int shell = top.size() + sides.size();
        int[] cells = new int[Math.min(shell, cap)];
        int n = 0;
        for (int k = 0; k < top.size() && n < cells.length; k++) cells[n++] = top.getInt(k);
        // Highest first, so a capped tall building keeps its upper walls under the roof.
        for (int k = sides.size() - 1; k >= 0 && n < cells.length; k--) cells[n++] = sides.getInt(k);
        return new Prepared(blueprint, cells, shell, sy == blueprint.sizeY() ? WHOLE : sy - 1);
    }

    /** In the blueprint and not above the cut: {@code sy} layers are kept. */
    private static boolean kept(Blueprint blueprint, int sy, int x, int y, int z) {
        return y < sy && blueprint.contains(x, y, z);
    }

    /**
     * The sides of this block that are a full square, one bit per {@link #SIDES} entry. A block
     * Minecraft never lets hide anything (glass, doors, trapdoors, leaves) has none; stairs,
     * slabs, fences and carpets do hide, but only with their full sides.
     */
    private static byte fullSides(BlockState state) {
        if (!state.canOcclude()) return 0;
        try {
            VoxelShape shape = state.getOcclusionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            int bits = 0;
            for (int d = 0; d < 6; d++) {
                if (Block.isFaceFull(shape, SIDES[d])) bits |= 1 << d;
            }
            return (byte) bits;
        } catch (RuntimeException e) {
            // A modded shape that needs a real world: solid all round, as a full block.
            return ALL_SIDES_FULL;
        }
    }

    private static int fullAt(Blueprint blueprint, byte[] full, int index) {
        int p = blueprint.paletteIndex(index);
        return p == Blueprint.VOID ? 0 : full[p];
    }

    /** Outside air in the cell next to (x, y, z) on side d, with no full square between them. */
    private static boolean seenFrom(Blueprint blueprint, int sy, byte[] full, BitSet outside, int x, int y, int z,
                                    int d) {
        int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
        if (!kept(blueprint, sy, nx, ny, nz)) return false;
        int j = blueprint.index(nx, ny, nz);
        return outside.get(j) && (fullAt(blueprint, full, j) & 1 << (d ^ 1)) == 0;
    }

    /** Shows this blueprint (null: nothing) with a fresh camera; models are recorded on the next frame. */
    void show(@Nullable Prepared prepared) {
        shown = prepared;
        drawn = prepared;
        cutTop = WHOLE;
        cancelCut();
        recordings = null;
        standIns.clear();
        if (prepared != null) {
            Blueprint blueprint = prepared.blueprint();
            centerX = blueprint.sizeX() / 2.0;
            centerZ = blueprint.sizeZ() / 2.0;
            radius = Math.max(1, Math.sqrt(centerX * centerX + centerZ * centerZ));
            height = blueprint.sizeY();
        }
        resetCamera();
    }

    @Nullable Prepared shown() {
        return shown;
    }

    /** Drops the recorded vertices and stand-ins (heap only: nothing is on the GPU). */
    void close() {
        show(null);
    }

    // =========================================================================
    // Camera and input
    // =========================================================================

    void setViewport(int left, int top, int right, int bottom) {
        if (left == viewLeft && top == viewTop && right == viewRight && bottom == viewBottom) return;
        viewLeft = left;
        viewTop = top;
        viewRight = right;
        viewBottom = bottom;
        viewDirty = true;
    }

    boolean contains(double x, double y) {
        return x >= viewLeft && x < viewRight && y >= viewTop && y < viewBottom;
    }

    void resetCamera() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = 1;
        panX = panY = 0;
        turnPivot = null;
        viewDirty = true;
    }

    /** Left drag turns; right or middle drag pans. False when the press is not in the view. */
    boolean beginDrag(double mouseX, double mouseY, int button) {
        if (button > 2 || shown == null || !contains(mouseX, mouseY)) return false;
        dragButton = button;
        turnPivot = null;
        return true;
    }

    boolean drag(int button, double dragX, double dragY) {
        if (button != dragButton) return false;
        if (button == 0) {
            turnAroundViewCentre(dragX * 0.012, dragY * 0.008);
        } else {
            panX += dragX;
            panY += dragY;
        }
        viewDirty = true;
        return true;
    }

    boolean endDrag(int button) {
        if (button != dragButton) return false;
        dragButton = -1;
        return true;
    }

    /**
     * Turns and tilts around the part of the building in the middle of the view, as the
     * village map does: after zooming in on a window, turning keeps that window in view.
     */
    private void turnAroundViewCentre(double dYaw, double dPitch) {
        if (shown == null) return;
        updateView();
        // Found once per drag, as the map's, so the turn never jumps between two points.
        if (turnPivot == null) turnPivot = viewCentrePoint();
        // Height from the building's middle, which originY keeps at the view's centre.
        double px = turnPivot[0], py = turnPivot[1] - height / 2, pz = turnPivot[2];
        yaw += dYaw;
        pitch = Mth.clamp(pitch + dPitch, MIN_PITCH, MAX_PITCH);
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        // Pan so that point lands on the view's centre again (the projection in drawPlate).
        panX = -(px * cy + pz * sy) * viewScale;
        panY = -(-px * sy * sp - py * cp + pz * cy * sp) * viewScale;
        viewDirty = true;
    }

    /**
     * The point to turn around, relative to the footprint's middle and the ground: half way
     * between the first and the last block of the shown building on the line through the
     * view's centre. Zoomed out that is about the building's middle; zoomed in on a corner or
     * a cut-open room it is inside that part. The view's rows are orthonormal, so the line is
     * stepped along the depth row from the camera side, as the map's pick does.
     */
    private double[] viewCentrePoint() {
        double u = -panX / viewScale, v = -panY / viewScale - height / 2 * cosPitch;
        double r1x = -sinYaw * sinPitch, r1y = -cosPitch, r1z = cosYaw * sinPitch;
        double r2x = -sinYaw * cosPitch, r2y = sinPitch, r2z = cosYaw * cosPitch;
        double ax = u * cosYaw + v * r1x, ay = v * r1y, az = u * sinYaw + v * r1z;
        double mid = height / 2 * sinPitch, reach = radius + height / 2 + 1;
        double first = Double.NaN, last = Double.NaN;
        for (double t = mid + reach; t >= mid - reach; t -= 0.25) {
            if (blockAt(ax + t * r2x + centerX, ay + t * r2y, az + t * r2z + centerZ)) {
                if (Double.isNaN(first)) first = t;
                last = t;
            }
        }
        if (!Double.isNaN(first)) {
            double t = (first + last) / 2;
            return new double[] {ax + t * r2x, ay + t * r2y, az + t * r2z};
        }
        // The line misses the building: its point level with the middle, kept over the footprint.
        double t = (height / 2 - ay) / r2y;
        return new double[] {Mth.clamp(ax + t * r2x, -centerX, centerX), height / 2,
                Mth.clamp(az + t * r2z, -centerZ, centerZ)};
    }

    /** A block of the shown building in the cell at this point; above a Layers cut counts as empty. */
    private boolean blockAt(double x, double y, double z) {
        Blueprint blueprint = shown.blueprint();
        int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
        if (by > cutTop || !blueprint.contains(bx, by, bz)) return false;
        int p = blueprint.paletteIndex(blueprint.index(bx, by, bz));
        return p != Blueprint.VOID && !blueprint.palette().get(p).isAir();
    }

    /** Zooms toward the cursor: the point under it stays put. */
    boolean zoomAt(double mouseX, double mouseY, double scrollY) {
        if (shown == null || !contains(mouseX, mouseY)) return false;
        double next = Mth.clamp(zoom * Math.pow(1.15, scrollY), MIN_ZOOM, MAX_ZOOM);
        double ratio = next / zoom;
        double cx = (viewLeft + viewRight) / 2.0, cy = (viewTop + viewBottom) / 2.0;
        panX = mouseX - cx - (mouseX - cx - panX) * ratio;
        panY = mouseY - cy - (mouseY - cy - panY) * ratio;
        zoom = next;
        viewDirty = true;
        return true;
    }

    /** Scale that fits the whole blueprint at any yaw, measured at the default tilt so tilting never rescales. */
    private double fitScale() {
        double halfWidth = (viewRight - viewLeft) / 2.0 - 12;
        double tall = viewBottom - viewTop - 24;
        return Math.max(0.5, Math.min(halfWidth / radius,
                tall / (2 * radius * Math.sin(DEFAULT_PITCH) + height * Math.cos(DEFAULT_PITCH))));
    }

    private void updateView() {
        if (!viewDirty) return;
        viewDirty = false;
        cosYaw = Math.cos(yaw);
        sinYaw = Math.sin(yaw);
        sinPitch = Math.sin(pitch);
        cosPitch = Math.cos(pitch);
        viewScale = fitScale() * zoom;
        double reachX = radius * viewScale;
        double reachY = (radius * sinPitch + height * cosPitch / 2) * viewScale;
        panX = Mth.clamp(panX, -reachX, reachX);
        panY = Mth.clamp(panY, -reachY, reachY);
        originX = (viewLeft + viewRight) / 2.0 + panX;
        originY = (viewTop + viewBottom) / 2.0 + panY + height / 2 * cosPitch * viewScale;
        zScale = Z_SPAN / (radius + height + 1);
        // Columns: where the x, y and z axes land as (screen x, screen y, depth); a mirror,
        // like the scale(16, -16, 16) vanilla uses for GUI items (see RoomSchematicView).
        viewRotation.set(
                (float) cosYaw, (float) (-sinYaw * sinPitch), (float) (-sinYaw * cosPitch), 0,
                0, (float) -cosPitch, (float) sinPitch, 0,
                (float) sinYaw, (float) (cosYaw * sinPitch), (float) (cosYaw * cosPitch), 0,
                0, 0, 0, 1);
    }

    // =========================================================================
    // Layers: cut off the top to look inside
    // =========================================================================

    // A small column on the view's right edge, half way down: up, the label, down (as the village map's).
    // Three buttons of one size, the label centred on the middle and even gaps, so it is symmetrical;
    // odd width, so odd-width text and the arrows sit dead centre.
    private static final int LAYER_W = 35, LAYER_H = 18, LAYER_GAP = 3;
    private int layerX() { return viewRight - 4 - LAYER_W; }
    private int layerLabelY() { return (viewTop + viewBottom) / 2 - LAYER_H / 2; }
    private int layerUpY() { return layerLabelY() - LAYER_GAP - LAYER_H; }
    private int layerDownY() { return layerLabelY() + LAYER_H + LAYER_GAP; }

    /** One layer more (+1) or fewer (-1) shown; past the top the whole building again. */
    void layerStep(int direction) {
        if (shown == null) return;
        int top = shown.blueprint().sizeY() - 1;
        int next = Mth.clamp((cutTop == WHOLE ? top : cutTop) + direction, 0, top);
        setCut(next >= top ? WHOLE : next);
    }

    void layerReset() {
        setCut(WHOLE);
    }

    boolean layerCut() {
        return cutTop != WHOLE;
    }

    /** A status line while a cut is worked out, or null. */
    @Nullable
    String layerNote() {
        return cutting != null ? "Drawing the layer..." : null;
    }

    /** The old picture stays until the new cut is worked out; the camera stays put. */
    private void setCut(int top) {
        if (shown == null || top == cutTop) return;
        cutTop = top;
        cancelCut();
        if (top == WHOLE) {
            drawn = shown;
            recordings = null;
            return;
        }
        Blueprint blueprint = shown.blueprint();
        cutting = CompletableFuture.supplyAsync(() -> prepare(blueprint, MAX_REAL_BLOCKS, top), Util.backgroundExecutor());
    }

    /** Takes a finished cut: recorded on this frame. Fast clicks only ever draw the last one. */
    private void pollCut() {
        if (cutting == null || !cutting.isDone()) return;
        Prepared cut = cutting.isCompletedExceptionally() || cutting.isCancelled() ? null : cutting.join();
        cutting = null;
        if (cut != null && shown != null && cut.blueprint() == shown.blueprint() && cut.topLayer() == cutTop) {
            drawn = cut;
            recordings = null;
        }
    }

    private void cancelCut() {
        if (cutting != null) cutting.cancel(false);
        cutting = null;
    }

    /** The Layers column, drawn over the view; nothing while no building is shown. */
    void renderLayers(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (shown == null) return;
        int x = layerX(), layers = shown.blueprint().sizeY();
        boolean cut = layerCut();
        boolean upOn = cut, downOn = cut ? cutTop > 0 : layers > 1;
        StorageUi.button(graphics, font, x, layerUpY(), LAYER_W, LAYER_H, "",
                StorageUi.inside(mouseX, mouseY, x, layerUpY(), LAYER_W, LAYER_H), upOn);
        arrow(graphics, x, layerUpY(), true, upOn);
        String label = cut ? (cutTop + 1) + "/" + layers : "Top";
        StorageUi.button(graphics, font, x, layerLabelY(), LAYER_W, LAYER_H, label,
                StorageUi.inside(mouseX, mouseY, x, layerLabelY(), LAYER_W, LAYER_H), true);
        if (cut) {
            graphics.fill(x + 1, layerLabelY() + LAYER_H - 2, x + LAYER_W - 1, layerLabelY() + LAYER_H - 1,
                    StorageUi.ACCENT);
        }
        StorageUi.button(graphics, font, x, layerDownY(), LAYER_W, LAYER_H, "",
                StorageUi.inside(mouseX, mouseY, x, layerDownY(), LAYER_W, LAYER_H), downOn);
        arrow(graphics, x, layerDownY(), false, downOn);
    }

    /** A 7 by 4 pixel triangle in the middle of a column button; drawn, so up and down mirror exactly. */
    private static void arrow(GuiGraphics graphics, int x, int y, boolean up, boolean enabled) {
        int color = enabled ? StorageUi.TEXT : StorageUi.MUTED;
        int cx = x + LAYER_W / 2, top = y + (LAYER_H - 4) / 2;
        for (int row = 0; row < 4; row++) {
            int half = up ? row : 3 - row;
            graphics.fill(cx - half, top + row, cx + half + 1, top + row + 1, color);
        }
    }

    boolean overLayers(double mouseX, double mouseY) {
        return shown != null && mouseX >= layerX() && mouseX < layerX() + LAYER_W
                && mouseY >= layerUpY() && mouseY < layerDownY() + LAYER_H;
    }

    /** A left click on the column; taken even on a greyed arrow, so no spin starts there. */
    boolean clickLayers(double mouseX, double mouseY) {
        if (!overLayers(mouseX, mouseY)) return false;
        int x = layerX();
        if (StorageUi.inside(mouseX, mouseY, x, layerUpY(), LAYER_W, LAYER_H)) {
            layerStep(1);
        } else if (StorageUi.inside(mouseX, mouseY, x, layerDownY(), LAYER_W, LAYER_H)) {
            layerStep(-1);
        } else if (StorageUi.inside(mouseX, mouseY, x, layerLabelY(), LAYER_W, LAYER_H)) {
            layerReset();
        }
        return true;
    }

    List<Component> layersTooltip() {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Layers"));
        out.add(Component.literal(layerCut() && shown != null
                ? "Showing layers 1 to " + (cutTop + 1) + " of " + shown.blueprint().sizeY() + "."
                : "Cuts off the top so you can look inside.").withStyle(ChatFormatting.GRAY));
        out.add(Component.literal("Arrows or Shift + scroll: one layer.").withStyle(ChatFormatting.GRAY));
        out.add(Component.literal("Middle: see the whole building.").withStyle(ChatFormatting.GRAY));
        return out;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    /** The framed view with the blueprint in it. */
    void render(GuiGraphics graphics) {
        graphics.fill(viewLeft - 1, viewTop - 1, viewRight + 1, viewBottom + 1, PANEL_BORDER);
        graphics.fill(viewLeft, viewTop, viewRight, viewBottom, VIEW);
        if (shown == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel world = minecraft.level;
        if (world == null) return;
        updateView();
        pollCut();
        if (recordings == null) record(minecraft, world);
        graphics.enableScissor(viewLeft, viewTop, viewRight, viewBottom);
        // No leftover depth from icons drawn before (see VillageModelView.render).
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        drawPlate(graphics);
        renderBlocks(graphics, minecraft);
        graphics.flush();
        Lighting.setupFor3DItems();
        graphics.disableScissor();
    }

    /** A dark ground plate under the footprint, so the model has something to stand on. */
    private void drawPlate(GuiGraphics graphics) {
        double y = -0.02;
        double[] xs = {-centerX, centerX, centerX, -centerX};
        double[] zs = {-centerZ, -centerZ, centerZ, centerZ};
        float[] px = new float[4], py = new float[4], pz = new float[4];
        for (int i = 0; i < 4; i++) {
            double rx = xs[i] * cosYaw + zs[i] * sinYaw;
            double rz = -xs[i] * sinYaw + zs[i] * cosYaw;
            px[i] = (float) (originX + rx * viewScale);
            py[i] = (float) (originY + (rz * sinPitch - y * cosPitch) * viewScale);
            pz[i] = (float) (Z_MID + (y * sinPitch + rz * cosPitch) * zScale);
        }
        double area = 0;
        for (int i = 0; i < 4; i++) area += px[i] * py[(i + 1) % 4] - px[(i + 1) % 4] * py[i];
        // GUI quads are back-face culled; front faces wind with negative screen area.
        int[] order = area > 0 ? new int[] {0, 3, 2, 1} : new int[] {0, 1, 2, 3};
        Matrix4f pose = graphics.pose().last().pose();
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        for (int i : order) buffer.addVertex(pose, px[i], py[i], pz[i]).setColor(PLATE);
    }

    private void renderBlocks(GuiGraphics graphics, Minecraft minecraft) {
        MultiBufferSource.BufferSource buffers = graphics.bufferSource();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(originX, originY, Z_MID);
        pose.scale((float) viewScale, (float) viewScale, (float) zScale);
        pose.mulPose(viewRotation);
        Matrix3f normal = pose.last().normal();
        // Light the stand-ins the way the world lights entities, turning with the model.
        RenderSystem.setShaderLights(new Vector3f(WORLD_LIGHT_0).mul(normal).normalize(),
                new Vector3f(WORLD_LIGHT_1).mul(normal).normalize());
        Matrix4f view = pose.last().pose();
        for (Recording recording : recordings) {
            recording.vertices().replay(buffers.getBuffer(recording.type()), view, normal, NO_TINT);
        }
        MultiBufferSource source = type -> new RoomSchematicView.ViewConsumer(buffers.getBuffer(type), 1f, 1f, 1f);
        Iterator<BlockEntity> it = standIns.iterator();
        while (it.hasNext()) {
            BlockEntity entity = it.next();
            BlockPos pos = entity.getBlockPos();
            pose.pushPose();
            pose.translate(pos.getX() - centerX, pos.getY(), pos.getZ() - centerZ);
            try {
                minecraft.getBlockEntityRenderDispatcher().renderItem(entity, pose, source,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            } catch (RuntimeException e) {
                // A renderer that can't cope with a block entity outside the world: leave it out
                // rather than crash the game, and stop trying.
                it.remove();
            }
            pose.popPose();
        }
        pose.popPose();
    }

    /**
     * Runs every shell block's model through the world's tesselator once, against the
     * blueprint itself, so faces between two blueprint blocks are culled and ambient
     * occlusion comes from the blueprint's own neighbours.
     */
    private void record(Minecraft minecraft, ClientLevel world) {
        recordings = new ArrayList<>();
        standIns.clear();
        Prepared prepared = drawn;
        if (prepared == null) return;
        Blueprint blueprint = prepared.blueprint();
        BlockPos anchor = minecraft.player != null ? minecraft.player.blockPosition() : BlockPos.ZERO;
        PreviewLevel level = new PreviewLevel(blueprint, world, anchor, prepared.topLayer());
        ModelBlockRenderer tesselator = minecraft.getBlockRenderer().getModelRenderer();
        Map<RenderType, RoomSchematicView.Recorder> byType = new LinkedHashMap<>();
        PoseStack pose = new PoseStack();
        for (int i : prepared.cells()) {
            int x = blueprint.xOf(i), y = blueprint.yOf(i), z = blueprint.zOf(i);
            BlockState state = blueprint.palette().get(blueprint.paletteIndex(i));
            BlockPos pos = new BlockPos(x, y, z);
            RenderShape shape = state.getRenderShape();
            if (shape == RenderShape.MODEL) {
                pose.pushPose();
                pose.translate(x - centerX, y, z - centerZ);
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
            } else if (state.getBlock() instanceof EntityBlock entityBlock && standIns.size() < MAX_STAND_INS) {
                try {
                    BlockEntity entity = entityBlock.newBlockEntity(pos, state);
                    if (entity != null) {
                        // In a level, so chests and beds draw by their own state (facing,
                        // halves); without one they fall back to a single south-facing look.
                        entity.setLevel(world);
                        standIns.add(entity);
                    }
                } catch (RuntimeException e) {
                    // Skip a block entity that can't be made outside the world.
                }
            }
        }
        byType.forEach((type, recorder) -> recordings.add(new Recording(type, recorder)));
    }

    /**
     * The blueprint as a block getter: its own blocks, air elsewhere and above {@code topLayer}
     * (so cut walls get their top faces). Shade, light and biome tint come from the client's
     * world, tint taken where the player stands so grass and leaves match the land around.
     * Also used by {@link BlueprintGhost}.
     */
    record PreviewLevel(Blueprint blueprint, ClientLevel world, BlockPos anchor, int topLayer)
            implements BlockAndTintGetter {
        PreviewLevel(Blueprint blueprint, ClientLevel world, BlockPos anchor) {
            this(blueprint, world, anchor, WHOLE);
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            if (pos.getY() > topLayer) return Blocks.AIR.defaultBlockState();
            BlockState state = blueprint.stateAt(pos.getX(), pos.getY(), pos.getZ());
            return state != null ? state : Blocks.AIR.defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }
        @Override public float getShade(Direction direction, boolean shade) {
            return world.getShade(direction, shade);
        }
        @Override public LevelLightEngine getLightEngine() {
            return world.getLightEngine();
        }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return world.getBlockTint(anchor, resolver);
        }
        @Override public int getHeight() {
            return world.getHeight();
        }
        @Override public int getMinBuildHeight() {
            return world.getMinBuildHeight();
        }
    }
}
