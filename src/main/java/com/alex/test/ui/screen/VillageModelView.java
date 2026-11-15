package com.alex.test.ui.screen;

import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.alex.test.village.villagers.builder.blueprint.BlueprintPlacement;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The whole village in 3D on the Builder's desk, with the picked blueprint as a ghost
 * the player turns, mirrors and clicks into place.
 *
 * <p>The orbit camera is {@link RoomSchematicView}'s (left drag turns, right drag pans,
 * scroll zooms toward the cursor), but the land is far too big to go through the GUI
 * buffers every frame: {@link VillageTerrainMesh} writes it once into a static GPU
 * buffer, and building shells get a second small one. A frame then only issues a few
 * draws with the camera as the model-view matrix. Every buffer is freed in
 * {@link #close}, which the desk calls when the tab changes or the screen closes.
 *
 * <p>The placement check (border, building shells, paths) runs when the ghost moves,
 * never per frame, and leaves an immutable {@link BlueprintPlacement} for ordering.
 *
 * <p>Copying a building uses the same view without a ghost: in pick mode a click only
 * records the column ({@link #takePick}), and {@link #setCopyBox} outlines what the copy
 * holds, with the side the player is moving tinted.
 */
final class VillageModelView {
    private static final double DEFAULT_YAW = Math.PI / 4;
    private static final double DEFAULT_PITCH = Math.toRadians(50);
    private static final double MIN_PITCH = Math.toRadians(18);
    private static final double MAX_PITCH = Math.toRadians(85);
    private static final double MIN_ZOOM = 0.75, MAX_ZOOM = 12;
    // GUI z band for the model, as the other 3D views: above the panel, below the overlay.
    private static final float Z_MID = 200, Z_SPAN = 140;
    /** Room in the depth band for a ghost standing above the land. */
    private static final int GHOST_HEADROOM = 130;
    /** A press that moves less than this many pixels is a click (place), not a drag. */
    private static final double CLICK_SLOP = 4;
    private static final int VIEW = 0xFF111111;
    private static final int PANEL_BORDER = 0xFF454545;
    private static final int SHELL_FILL = 0x30E6D49A, SHELL_LINE = 0xC0E6D49A;
    private static final int CLASH_FILL = 0x80E07A6E;
    private static final int GHOST_LINE = 0xFF6BAF72, BAD_LINE = 0xFFE07A6E;
    private static final int LABEL_BACK = 0xA0000000;
    private static final int COPY_LINE = 0xFF6BAF72, COPY_SIDE = 0x606BAF72;

    /** What the last placement check found. */
    record Check(boolean insideBorder, @Nullable String clashName, boolean coversPath) {}

    private final int[] border;
    private final List<BuilderDeskMenu.BuildingRow> buildings;
    private final BuilderDeskMenu.UndergroundFloors floors;
    private VillageTerrainMesh terrain;
    private final BlueprintGhost ghost = new BlueprintGhost();

    // Camera.
    private double yaw = DEFAULT_YAW;
    private double pitch = DEFAULT_PITCH;
    private double zoom = 1;
    private double panX, panY;
    private int dragButton = -1;
    /** The land point a left drag turns around, fixed when the drag starts (null: not found yet). */
    @Nullable private double[] turnPivot;
    private double pressX, pressY, dragged;
    private int viewLeft, viewTop, viewRight = 1, viewBottom = 1;
    private boolean viewDirty = true;
    private double cosYaw, sinYaw, sinPitch, cosPitch, viewScale, originX, originY, zScale, radius = 1, height = 1;
    private final Matrix4f viewRotation = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Matrix4f ghostModelView = new Matrix4f();

    // Static GPU buffers: building shells once, the placement's red boxes when it moves.
    private boolean shellsBuilt;
    @Nullable private VertexBuffer shellFaces, shellLines, placeFaces, placeLines;

    // Placement: the column the footprint is centred on, and the height of its lowest layer.
    @Nullable private Blueprint source;
    @Nullable private String blueprintName;
    private boolean placed, freshSource;
    /** An exact spot to put the ghost at once its shape is ready (see {@link #setPlacement}). */
    @Nullable private BlockPos wantOrigin;
    /** A column (x, z) to centre the ghost on once its shape is ready: a file dropped on the map. */
    @Nullable private int[] wantColumn;
    private int ghostX, ghostZ, baseY;
    private boolean placementDirty;
    @Nullable private Check check;
    @Nullable private BlueprintPlacement placement;

    // Copying: clicks pick a column instead of placing; the copy's box and its tinted side.
    private boolean pickMode;
    @Nullable private int[] pendingPick;
    @Nullable private BlockPos copyMin, copyMax;
    private int copySide = -1;
    private boolean copyDirty;
    @Nullable private VertexBuffer copyFaces, copyLines;
    // Real blocks, built after the coloured land and drawn in its place once done.
    @Nullable private VillageBlockMesh blocks;
    private boolean blocksChecked;

    // Layers: everything above the cut is hidden (NO_CUT: the plain surface). A change waits
    // CUT_DELAY_MS for more clicks, then both maps are built again; the coloured one is
    // swapped in when ready. The camera keeps the first land's height, so it never jumps.
    static final int NO_CUT = VillageBlockMesh.NO_CUT;
    private static final long CUT_DELAY_MS = 300;
    private int cut = NO_CUT, appliedCut = NO_CUT;
    private long cutChangedAt;
    @Nullable private VillageTerrainMesh pendingTerrain;
    private int surfaceTop = Integer.MIN_VALUE, fitHeight = -1;
    /** Opened under rock (a cave village): the cut was set by the desk, not the player. */
    private boolean caveOpened;

    VillageModelView(int[] border, List<BuilderDeskMenu.BuildingRow> buildings,
                     BuilderDeskMenu.UndergroundFloors floors) {
        this.border = border.clone();
        this.buildings = buildings;
        this.floors = floors;
        this.terrain = new VillageTerrainMesh(this.border);
    }

    // =========================================================================
    // The ghost
    // =========================================================================

    /** The picked blueprint (null: none). Keeps the ghost's spot when another file is picked. */
    void setBlueprint(@Nullable Blueprint blueprint, @Nullable BlueprintPreviewView.Prepared prepared) {
        if (blueprint == source) return;
        source = blueprint;
        blueprintName = blueprint == null ? null : blueprint.name();
        freshSource = true;
        ghost.setSource(blueprint, prepared);
        if (blueprint == null) {
            placement = null;
            check = null;
            placementDirty = true;
        }
    }

    /** Picks up a finished Turn or Mirror and puts the first ghost down once the land is read. */
    void tick() {
        boolean changed = ghost.poll();
        if (wantColumn != null) {
            if (ghost.shape() == null || ghost.busy() || !terrain.ready()) return;
            placeAt(wantColumn[0], wantColumn[1]);
            wantColumn = null;
            freshSource = false;
            return;
        }
        if (wantOrigin != null) {
            BlueprintGhost.Shape shape = ghost.shape();
            if (shape == null || ghost.busy() || !terrain.ready()) return;
            ghostX = wantOrigin.getX() + shape.sizeX() / 2;
            ghostZ = wantOrigin.getZ() + shape.sizeZ() / 2;
            baseY = wantOrigin.getY();
            wantOrigin = null;
            placed = true;
            freshSource = false;
            placementDirty = true;
            return;
        }
        if (!placed) {
            if (terrain.ready() && ghost.shape() != null) {
                placeAt(defaultColumnX(), defaultColumnZ());
                freshSource = false;
            }
            return;
        }
        if (changed && ghost.shape() != null) {
            // A Turn keeps centre and height; a new blueprint gets a fresh height for its footprint.
            if (freshSource) {
                placeAt(ghostX, ghostZ);
            } else {
                placementDirty = true;
            }
            freshSource = false;
        }
    }

    void turn() {
        ghost.turn();
    }

    void cycleMirror() {
        ghost.cycleMirror();
    }

    void raise(int dy) {
        if (!placed) return;
        baseY += dy;
        placementDirty = true;
    }

    boolean ready() {
        return terrain.ready();
    }

    float progress() {
        return terrain.progress();
    }

    /** How far the real-block map is, 0..1; -1 when it is done or not being built (big areas). */
    float detailProgress() {
        // A new layer has its own line (layerNote); this is the first build only.
        return blocks == null || blocks.ready() || blocks.shown() ? -1 : blocks.progress();
    }

    // =========================================================================
    // Layers
    // =========================================================================

    /**
     * A cave village: most registered buildings have rock (or anything) well above their roof.
     * Then the map opens cut just above the highest such roof; else NO_CUT.
     */
    private int caveCut() {
        int loaded = 0, covered = 0, roof = Integer.MIN_VALUE;
        for (BuilderDeskMenu.BuildingRow row : buildings) {
            int x = (row.shellMin().getX() + row.shellMax().getX()) / 2;
            int z = (row.shellMin().getZ() + row.shellMax().getZ()) / 2;
            if (!terrain.loadedAt(x, z)) continue;
            loaded++;
            if (terrain.topAt(x, z) > row.shellMax().getY() + 2) {
                covered++;
                roof = Math.max(roof, row.shellMax().getY());
            }
        }
        return covered > 0 && covered * 2 > loaded ? roof : NO_CUT;
    }

    private void applyCut() {
        appliedCut = cut;
        updateView();
        double[] middle = viewCentrePoint();
        if (blocks != null) blocks.setCut(cut, middle[0] + terrain.centerX(), middle[2] + terrain.centerZ());
        if (pendingTerrain != null) pendingTerrain.close();
        pendingTerrain = new VillageTerrainMesh(border, cut, terrain.centerY());
    }

    private void setCut(int value) {
        if (value == cut) return;
        cut = value;
        cutChangedAt = System.currentTimeMillis();
        caveOpened = false;
    }

    /**
     * One step of the Layers: {@code fine} moves one block; otherwise the next floor where
     * villagers walk under a roof or rock (cut at head height there: walls open, floor and
     * furniture show), or 4 blocks when there is none that way. Above the land: the surface.
     */
    void layerStep(int direction, boolean fine) {
        if (!terrain.ready() || surfaceTop == Integer.MIN_VALUE) return;
        int current = cut == NO_CUT ? surfaceTop : cut;
        int next;
        if (fine) {
            next = current + direction;
        } else {
            next = direction < 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            for (int feet : floors.floors()) {
                int at = feet + 1;
                if (direction < 0 && at < current) next = Math.max(next, at);
                if (direction > 0 && at > current) next = Math.min(next, at);
            }
            if (next == Integer.MIN_VALUE || next == Integer.MAX_VALUE) next = current + 4 * direction;
        }
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null) next = Math.max(next, world.getMinBuildHeight() + 1);
        setCut(next >= surfaceTop ? NO_CUT : next);
    }

    void layerReset() {
        setCut(NO_CUT);
    }

    /** "Top", or the highest height shown. */
    String layerLabel() {
        return cut == NO_CUT ? "Top" : "Y" + cut;
    }

    /** The highest Y level shown while the top is cut off. */
    int cutY() {
        return cut;
    }

    boolean layerCut() {
        return cut != NO_CUT;
    }

    /** A status line about the Layers, or null. */
    @Nullable
    String layerNote() {
        if (cut != appliedCut) return "Drawing the layer...";
        if (blocks != null && blocks.shown() && !blocks.ready()) {
            return String.format(java.util.Locale.ROOT, "Drawing the layer... %d%%", (int) (blocks.progress() * 100));
        }
        if (pendingTerrain != null) return "Drawing the layer...";
        return null;
    }

    /** Map pieces left blank because the client does not hold their chunks (0 when none). */
    int waitingPieces() {
        return blocks == null || !blocks.ready() ? 0 : blocks.waitingPieces();
    }

    /** A Turn or Mirror is being worked out (big files take a moment). */
    boolean busy() {
        return ghost.busy();
    }

    @Nullable BlueprintGhost.Shape shape() {
        return ghost.shape();
    }

    @Nullable Check check() {
        return check;
    }

    /** Where the ghost is now, for the details strip and (round 4) the Build button. */
    @Nullable BlueprintPlacement placement() {
        return placement;
    }

    /** The direction the blueprint's north side faces after Turn and Mirror. */
    Direction facing() {
        return ghost.rotation().rotate(ghost.mirror().mirror(Direction.NORTH));
    }

    Rotation rotation() {
        return ghost.rotation();
    }

    Mirror mirror() {
        return ghost.mirror();
    }

    /**
     * Puts the ghost at an exact spot (its lowest corner), turned and mirrored as given, once
     * the turned shape and the land are ready: the spot a Builder's plan confirmed in the
     * world. {@code origin} null keeps the turn and places it as usual.
     */
    void setPlacement(@Nullable BlockPos origin, Rotation rotation, Mirror mirror) {
        ghost.setTransform(rotation, mirror);
        wantOrigin = origin;
        wantColumn = null;
    }

    /** The land column (x, z) under a point of the screen, or null (off the view, the sky, land not read yet). */
    @Nullable
    int[] columnAt(double mouseX, double mouseY) {
        if (!terrain.ready() || !contains(mouseX, mouseY)) return null;
        return pick(mouseX, mouseY);
    }

    /**
     * Centres the ghost on this column, standing on the land, as soon as its shape is ready
     * (a file dragged onto the map is still being read). A click on the map does the same at once.
     */
    void placeOnColumn(int x, int z) {
        wantColumn = new int[] {x, z};
        wantOrigin = null;
    }

    /** The file meant for {@link #placeOnColumn} could not be read: nothing waits for it any more. */
    void forgetColumn() {
        wantColumn = null;
    }

    boolean mirrored() {
        return ghost.mirror() != Mirror.NONE;
    }

    /** In pick mode a click on the land is kept for {@link #takePick} instead of moving the ghost. */
    void setPickMode(boolean on) {
        pickMode = on;
        pendingPick = null;
    }

    /** The last column clicked in pick mode, as x, the top block's y, z; handed out once. */
    @Nullable
    int[] takePick() {
        int[] pick = pendingPick;
        pendingPick = null;
        return pick;
    }

    /**
     * Outlines a copy's box (inclusive corners; null hides it) and tints one side, in
     * {@code BuildingCopier.SIDES} order (-1: none).
     */
    void setCopyBox(@Nullable BlockPos min, @Nullable BlockPos max, int side) {
        if (java.util.Objects.equals(min, copyMin) && java.util.Objects.equals(max, copyMax) && side == copySide) {
            return;
        }
        copyMin = min;
        copyMax = max;
        copySide = side;
        copyDirty = true;
    }

    private void rebuildCopyBox() {
        copyDirty = false;
        if (copyMin == null || copyMax == null) {
            closeCopyBuffers();
            return;
        }
        float cx = (float) terrain.centerX(), cy = (float) terrain.centerY(), cz = (float) terrain.centerZ();
        float x0 = copyMin.getX() - cx, y0 = copyMin.getY() - cy, z0 = copyMin.getZ() - cz;
        float x1 = copyMax.getX() + 1 - cx, y1 = copyMax.getY() + 1 - cy, z1 = copyMax.getZ() + 1 - cz;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(256)) {
            BufferBuilder faces = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            switch (copySide) {
                case 0 -> quad(faces, x0, z0, x0, z1, y0, y1, COPY_SIDE);
                case 1 -> quad(faces, x1, z0, x1, z1, y0, y1, COPY_SIDE);
                case 2, 3 -> {
                    float y = copySide == 2 ? y0 : y1;
                    faces.addVertex(x0, y, z0).setColor(COPY_SIDE);
                    faces.addVertex(x0, y, z1).setColor(COPY_SIDE);
                    faces.addVertex(x1, y, z1).setColor(COPY_SIDE);
                    faces.addVertex(x1, y, z0).setColor(COPY_SIDE);
                }
                case 4 -> quad(faces, x0, z0, x1, z0, y0, y1, COPY_SIDE);
                case 5 -> quad(faces, x0, z1, x1, z1, y0, y1, COPY_SIDE);
                default -> { }
            }
            copyFaces = upload(copyFaces, faces, VertexBuffer.Usage.DYNAMIC);
        }
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(1024)) {
            BufferBuilder lines = new BufferBuilder(bytes, VertexFormat.Mode.DEBUG_LINES,
                    DefaultVertexFormat.POSITION_COLOR);
            outline(lines, x0, y0, z0, x1, y1, z1, COPY_LINE);
            copyLines = upload(copyLines, lines, VertexBuffer.Usage.DYNAMIC);
        }
    }

    private void closeCopyBuffers() {
        if (copyFaces != null) copyFaces.close();
        if (copyLines != null) copyLines.close();
        copyFaces = copyLines = null;
    }

    private int defaultColumnX() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && insideBorder(minecraft.player.getBlockX(), minecraft.player.getBlockZ())) {
            return minecraft.player.getBlockX();
        }
        return (border[0] + border[2]) / 2;
    }

    private int defaultColumnZ() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && insideBorder(minecraft.player.getBlockX(), minecraft.player.getBlockZ())) {
            return minecraft.player.getBlockZ();
        }
        return (border[1] + border[3]) / 2;
    }

    private boolean insideBorder(int x, int z) {
        return x >= border[0] && x <= border[2] && z >= border[1] && z <= border[3];
    }

    /**
     * Centres the footprint on this column, standing on the middle height of the land under it
     * (a copied building with its floor level with that land).
     */
    private void placeAt(int x, int z) {
        BlueprintGhost.Shape shape = ghost.shape();
        if (shape == null || !terrain.ready()) return;
        ghostX = x;
        ghostZ = z;
        int ox = x - shape.sizeX() / 2, oz = z - shape.sizeZ() / 2;
        int[] tops = new int[shape.sizeX() * shape.sizeZ()];
        int n = 0;
        for (int dz = 0; dz < shape.sizeZ(); dz++) {
            for (int dx = 0; dx < shape.sizeX(); dx++) {
                if (terrain.loadedAt(ox + dx, oz + dz)) tops[n++] = terrain.topAt(ox + dx, oz + dz);
            }
        }
        if (n == 0) {
            baseY = terrain.topAt(x, z);
        } else {
            Arrays.sort(tops, 0, n);
            // topAt is already one above the top block, so this is "median height + 1".
            baseY = tops[n / 2];
        }
        // A copied building: its floor takes the top ground block's place, the door at walking height.
        baseY -= shape.floorSink();
        placed = true;
        placementDirty = true;
    }

    private BlockPos origin(BlueprintGhost.Shape shape) {
        return new BlockPos(ghostX - shape.sizeX() / 2, baseY, ghostZ - shape.sizeZ() / 2);
    }

    /**
     * Checks the spot and rebuilds the red boxes and the outline. Only when the ghost
     * moved, turned or changed; render thread (it uploads).
     */
    private void rebuildPlacement() {
        placementDirty = false;
        BlueprintGhost.Shape shape = ghost.shape();
        if (!placed || shape == null || blueprintName == null) {
            check = null;
            placement = null;
            closePlacementBuffers();
            return;
        }
        BlockPos origin = origin(shape);
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int sx = shape.sizeX(), sz = shape.sizeZ();
        // Only shells whose ground plan meets the footprint can clash.
        List<BuilderDeskMenu.BuildingRow> near = new ArrayList<>();
        for (BuilderDeskMenu.BuildingRow row : buildings) {
            if (row.shellMax().getX() >= ox && row.shellMin().getX() < ox + sx
                    && row.shellMax().getZ() >= oz && row.shellMin().getZ() < oz + sz) near.add(row);
        }
        boolean inside = true, path = false;
        String clash = null;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(4096)) {
            BufferBuilder faces = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            float cx = (float) terrain.centerX(), cy = (float) terrain.centerY(), cz = (float) terrain.centerZ();
            for (int z = 0; z < sz; z++) {
                int runStart = -1, runLo = 0, runHi = 0;
                for (int x = 0; x <= sx; x++) {
                    int redLo = Integer.MAX_VALUE, redHi = Integer.MIN_VALUE;
                    if (x < sx && shape.low()[z * sx + x] >= 0) {
                        int wx = ox + x, wz = oz + z;
                        int y0 = oy + shape.low()[z * sx + x], y1 = oy + shape.high()[z * sx + x];
                        if (!insideBorder(wx, wz)) {
                            inside = false;
                            redLo = y0;
                            redHi = y1;
                        } else {
                            for (BuilderDeskMenu.BuildingRow row : near) {
                                if (wx < row.shellMin().getX() || wx > row.shellMax().getX()
                                        || wz < row.shellMin().getZ() || wz > row.shellMax().getZ()) continue;
                                int lo = Math.max(y0, row.shellMin().getY()), hi = Math.min(y1, row.shellMax().getY());
                                if (lo > hi) continue;
                                if (clash == null) clash = row.name();
                                redLo = Math.min(redLo, lo);
                                redHi = Math.max(redHi, hi);
                            }
                        }
                        if (!path && terrain.pathAt(wx, wz)) path = true;
                    }
                    boolean red = redLo <= redHi;
                    if (runStart >= 0 && (!red || redLo != runLo || redHi != runHi)) {
                        box(faces, ox + runStart - cx, runLo - cy, oz + z - cz, ox + x - cx, runHi + 1 - cy,
                                oz + z + 1 - cz, CLASH_FILL);
                        runStart = -1;
                    }
                    if (red && runStart < 0) {
                        runStart = x;
                        runLo = redLo;
                        runHi = redHi;
                    }
                }
            }
            placeFaces = upload(placeFaces, faces, VertexBuffer.Usage.DYNAMIC);
        }
        boolean valid = inside && clash == null;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(1024)) {
            BufferBuilder lines = new BufferBuilder(bytes, VertexFormat.Mode.DEBUG_LINES,
                    DefaultVertexFormat.POSITION_COLOR);
            float x0 = (float) (ox - terrain.centerX()), y0 = (float) (oy - terrain.centerY()),
                    z0 = (float) (oz - terrain.centerZ());
            outline(lines, x0, y0, z0, x0 + sx, y0 + shape.sizeY(), z0 + sz, valid ? GHOST_LINE : BAD_LINE);
            placeLines = upload(placeLines, lines, VertexBuffer.Usage.DYNAMIC);
        }
        check = new Check(inside, clash, path);
        String reason = !inside ? "Part of it is outside the village border"
                : clash != null ? "It clashes with " + clash : null;
        placement = new BlueprintPlacement(blueprintName, origin, shape.rotation(), shape.mirror(), valid, reason);
    }

    private void closePlacementBuffers() {
        if (placeFaces != null) placeFaces.close();
        if (placeLines != null) placeLines.close();
        placeFaces = placeLines = null;
    }

    private void buildShells() {
        shellsBuilt = true;
        if (buildings.isEmpty()) return;
        float cx = (float) terrain.centerX(), cy = (float) terrain.centerY(), cz = (float) terrain.centerZ();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(buildings.size() * 24 * 16 + 64)) {
            BufferBuilder faces = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (BuilderDeskMenu.BuildingRow row : buildings) {
                box(faces, row.shellMin().getX() - cx, row.shellMin().getY() - cy, row.shellMin().getZ() - cz,
                        row.shellMax().getX() + 1 - cx, row.shellMax().getY() + 1 - cy, row.shellMax().getZ() + 1 - cz,
                        SHELL_FILL);
            }
            shellFaces = upload(null, faces, VertexBuffer.Usage.STATIC);
        }
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(buildings.size() * 24 * 16 + 64)) {
            BufferBuilder lines = new BufferBuilder(bytes, VertexFormat.Mode.DEBUG_LINES,
                    DefaultVertexFormat.POSITION_COLOR);
            for (BuilderDeskMenu.BuildingRow row : buildings) {
                outline(lines, row.shellMin().getX() - cx, row.shellMin().getY() - cy, row.shellMin().getZ() - cz,
                        row.shellMax().getX() + 1 - cx, row.shellMax().getY() + 1 - cy,
                        row.shellMax().getZ() + 1 - cz, SHELL_LINE);
            }
            shellLines = upload(null, lines, VertexBuffer.Usage.STATIC);
        }
    }

    // =========================================================================
    // GPU helpers (shared with the terrain and the ghost)
    // =========================================================================

    /** Uploads what {@code builder} holds into {@code reuse} (or a new buffer); null when it was empty. */
    @Nullable
    static VertexBuffer upload(@Nullable VertexBuffer reuse, BufferBuilder builder, VertexBuffer.Usage usage) {
        MeshData mesh = builder.build();
        if (mesh == null) {
            if (reuse != null) reuse.close();
            return null;
        }
        VertexBuffer buffer = reuse != null && !reuse.isInvalid() ? reuse : new VertexBuffer(usage);
        buffer.bind();
        buffer.upload(mesh);
        VertexBuffer.unbind();
        return buffer;
    }

    static void drawBuffer(@Nullable VertexBuffer buffer, Matrix4f modelView, Matrix4f projection) {
        if (buffer == null || buffer.isInvalid() || buffer.getFormat() == null) return;
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) return;
        buffer.bind();
        buffer.drawWithShader(modelView, projection, shader);
        VertexBuffer.unbind();
    }

    /** A box without its bottom (the camera never looks from below). Culling is off when drawn. */
    static void box(VertexConsumer out, float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        out.addVertex(x0, y1, z0).setColor(color);
        out.addVertex(x0, y1, z1).setColor(color);
        out.addVertex(x1, y1, z1).setColor(color);
        out.addVertex(x1, y1, z0).setColor(color);
        int side = VillageTerrainMesh.shade(color, 0.8);
        quad(out, x0, z0, x1, z0, y0, y1, side);
        quad(out, x0, z1, x1, z1, y0, y1, side);
        int end = VillageTerrainMesh.shade(color, 0.65);
        quad(out, x0, z0, x0, z1, y0, y1, end);
        quad(out, x1, z0, x1, z1, y0, y1, end);
    }

    private static void quad(VertexConsumer out, float ax, float az, float bx, float bz, float y0, float y1,
                             int color) {
        out.addVertex(ax, y0, az).setColor(color);
        out.addVertex(ax, y1, az).setColor(color);
        out.addVertex(bx, y1, bz).setColor(color);
        out.addVertex(bx, y0, bz).setColor(color);
    }

    /** The twelve edges of a box, as line pairs. */
    static void outline(VertexConsumer out, float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        float[][] corners = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        for (int i = 0; i < 4; i++) {
            float[] a = corners[i], b = corners[(i + 1) % 4];
            line(out, a[0], y0, a[1], b[0], y0, b[1], color);
            line(out, a[0], y1, a[1], b[0], y1, b[1], color);
            line(out, a[0], y0, a[1], a[0], y1, a[1], color);
        }
    }

    private static void line(VertexConsumer out, float ax, float ay, float az, float bx, float by, float bz,
                             int color) {
        out.addVertex(ax, ay, az).setColor(color);
        out.addVertex(bx, by, bz).setColor(color);
    }

    // =========================================================================
    // Camera and input
    // =========================================================================

    void setViewport(int left, int top, int right, int bottom) {
        if (left == viewLeft && top == viewTop && right == viewRight && bottom == viewBottom) return;
        // Same spot in the middle after a resize (the full view): the pan is in screen
        // pixels, so it grows with the scale.
        boolean hadView = viewRight > viewLeft && viewBottom > viewTop && terrain.ready();
        double oldFit = hadView ? fitScale() : 0;
        viewLeft = left;
        viewTop = top;
        viewRight = right;
        viewBottom = bottom;
        if (oldFit > 0) {
            double ratio = fitScale() / oldFit;
            panX *= ratio;
            panY *= ratio;
        }
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
        viewDirty = true;
    }

    /** Left drag turns (a still left click places); right or middle drag pans. */
    boolean beginDrag(double mouseX, double mouseY, int button) {
        if (button > 2 || !terrain.ready() || !contains(mouseX, mouseY)) return false;
        dragButton = button;
        turnPivot = null;
        pressX = mouseX;
        pressY = mouseY;
        dragged = 0;
        return true;
    }

    boolean drag(int button, double dragX, double dragY) {
        if (button != dragButton) return false;
        dragged += Math.abs(dragX) + Math.abs(dragY);
        if (button == 0) {
            turnAroundViewCentre(dragX * 0.012, dragY * 0.008);
        } else {
            panX += dragX;
            panY += dragY;
        }
        viewDirty = true;
        return true;
    }

    boolean endDrag(int button, double mouseX, double mouseY) {
        if (button != dragButton) return false;
        dragButton = -1;
        if (button == 0 && dragged < CLICK_SLOP && Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) < CLICK_SLOP) {
            int[] column = pick(mouseX, mouseY);
            if (column != null && pickMode) {
                pendingPick = new int[] {column[0], terrain.topAt(column[0], column[1]) - 1, column[1]};
            } else if (column != null && wantColumn != null) {
                // A dropped file still being read goes where this click was.
                wantColumn = column;
            } else if (column != null) {
                placeAt(column[0], column[1]);
            }
        }
        return true;
    }

    /**
     * Turns and tilts around the land in the middle of the view, not the middle of the
     * village: after zooming in on one house, turning keeps that house in view.
     */
    private void turnAroundViewCentre(double dYaw, double dPitch) {
        updateView();
        if (turnPivot == null) turnPivot = viewCentrePoint();
        // Found once per drag: worked out again on every move, a point on a roof edge
        // jumped between roof and ground height and the camera flickered.
        double px = turnPivot[0], py = turnPivot[1], pz = turnPivot[2];
        yaw += dYaw;
        pitch = Mth.clamp(pitch + dPitch, MIN_PITCH, MAX_PITCH);
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        // Pan so that point lands on the view's centre again (the same projection as renderLabels).
        panX = -(px * cy + pz * sy) * viewScale;
        panY = -(-px * sy * sp - py * cp + pz * cy * sp) * viewScale;
        viewDirty = true;
    }

    /**
     * The land point under the view's centre, relative to the land's middle: the
     * projection undone for a point at the land's height there (two steps, starting from
     * the middle height).
     */
    private double[] viewCentrePoint() {
        double u = -panX / viewScale, v = -panY / viewScale;
        double px = 0, pz = 0, py = 0;
        for (int step = 0; step < 2; step++) {
            double w = (v + py * cosPitch) / sinPitch;
            px = u * cosYaw - w * sinYaw;
            pz = u * sinYaw + w * cosYaw;
            if (step == 1) break;
            int bx = Mth.floor(px + terrain.centerX()), bz = Mth.floor(pz + terrain.centerZ());
            py = terrain.inArea(bx, bz) ? terrain.topAt(bx, bz) - terrain.centerY() : 0;
        }
        return new double[] {px, py, pz};
    }

    /** Zooms toward the cursor: the point under it stays put. */
    boolean zoomAt(double mouseX, double mouseY, double scrollY) {
        if (!terrain.ready() || !contains(mouseX, mouseY)) return false;
        double next = Mth.clamp(zoom * Math.pow(1.15, scrollY), MIN_ZOOM, MAX_ZOOM);
        double ratio = next / zoom;
        double cx = (viewLeft + viewRight) / 2.0, cy = (viewTop + viewBottom) / 2.0;
        panX = mouseX - cx - (mouseX - cx - panX) * ratio;
        panY = mouseY - cy - (mouseY - cy - panY) * ratio;
        zoom = next;
        viewDirty = true;
        return true;
    }

    /** Scale that fits the whole village at any yaw, measured at the default tilt so tilting never rescales. */
    private double fitScale() {
        double halfWidth = (viewRight - viewLeft) / 2.0 - 12;
        double tall = viewBottom - viewTop - 24;
        return Math.max(0.05, Math.min(halfWidth / radius,
                tall / (2 * radius * Math.sin(DEFAULT_PITCH) + height * Math.cos(DEFAULT_PITCH))));
    }

    private void updateView() {
        if (!viewDirty) return;
        viewDirty = false;
        double halfX = (terrain.maxX - terrain.minX + 1) / 2.0, halfZ = (terrain.maxZ - terrain.minZ + 1) / 2.0;
        radius = Math.max(1, Math.sqrt(halfX * halfX + halfZ * halfZ));
        // The first land's height: a Layers cut makes the land lower, and the view must not jump.
        if (fitHeight < 0 && terrain.ready()) fitHeight = Math.max(1, terrain.maxHeight() - terrain.minHeight() + 1);
        height = fitHeight > 0 ? fitHeight : Math.max(1, terrain.maxHeight() - terrain.minHeight() + 1);
        cosYaw = Math.cos(yaw);
        sinYaw = Math.sin(yaw);
        sinPitch = Math.sin(pitch);
        cosPitch = Math.cos(pitch);
        viewScale = fitScale() * zoom;
        double reachX = radius * viewScale;
        double reachY = (radius * sinPitch + height * cosPitch / 2) * viewScale;
        panX = Mth.clamp(panX, -reachX, reachX);
        panY = Mth.clamp(panY, -reachY, reachY);
        // The land's middle height sits at the view's centre (the mesh is stored around it).
        originX = (viewLeft + viewRight) / 2.0 + panX;
        originY = (viewTop + viewBottom) / 2.0 + panY;
        zScale = Z_SPAN / (radius + height / 2 + GHOST_HEADROOM);
        // Columns: where the x, y and z axes land as (screen x, screen y, depth); see BlueprintPreviewView.
        viewRotation.set(
                (float) cosYaw, (float) (-sinYaw * sinPitch), (float) (-sinYaw * cosPitch), 0,
                0, (float) -cosPitch, (float) sinPitch, 0,
                (float) sinYaw, (float) (cosYaw * sinPitch), (float) (cosYaw * cosPitch), 0,
                0, 0, 0, 1);
    }

    /**
     * The land column under the cursor, or null. The view's rows are orthonormal, so the
     * cursor is a line through the model along the depth row; it is stepped from the
     * camera side until it first dips below the height grid.
     */
    @Nullable
    private int[] pick(double mouseX, double mouseY) {
        updateView();
        double u = (mouseX - originX) / viewScale, v = (mouseY - originY) / viewScale;
        double r1x = -sinYaw * sinPitch, r1y = -cosPitch, r1z = cosYaw * sinPitch;
        double r2x = -sinYaw * cosPitch, r2y = sinPitch, r2z = cosYaw * cosPitch;
        double reach = radius + height + 8;
        for (double t = reach; t >= -reach; t -= 0.25) {
            double x = u * cosYaw + v * r1x + t * r2x + terrain.centerX();
            double y = v * r1y + t * r2y + terrain.centerY();
            double z = u * sinYaw + v * r1z + t * r2z + terrain.centerZ();
            int bx = Mth.floor(x), bz = Mth.floor(z);
            if (!terrain.inArea(bx, bz)) continue;
            if (y <= terrain.topAt(bx, bz)) return new int[] {bx, bz};
        }
        return null;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    /** The framed view; while the land is still being read it shows only the frame. */
    void render(GuiGraphics graphics) {
        graphics.fill(viewLeft - 1, viewTop - 1, viewRight + 1, viewBottom + 1, PANEL_BORDER);
        graphics.fill(viewLeft, viewTop, viewRight, viewBottom, VIEW);
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel world = minecraft.level;
        if (world == null) return;
        if (!terrain.ready()) {
            terrain.step(world);
            if (terrain.ready()) viewDirty = true;
            return;
        }
        if (!shellsBuilt) buildShells();
        if (!blocksChecked) {
            blocksChecked = true;
            surfaceTop = terrain.maxHeight() + 1;
            int cave = caveCut();
            if (cave != NO_CUT) {
                cut = cave;
                caveOpened = true;
            }
            if (VillageBlockMesh.fits(terrain)) blocks = new VillageBlockMesh(terrain, buildings, cut);
        }
        if (cut != appliedCut && System.currentTimeMillis() - cutChangedAt >= CUT_DELAY_MS) applyCut();
        if (pendingTerrain != null) {
            pendingTerrain.step(world);
            if (pendingTerrain.ready()) {
                terrain.close();
                terrain = pendingTerrain;
                pendingTerrain = null;
                placementDirty = true;
                viewDirty = true;
            }
        }
        // Keeps going once shown: pieces waiting for chunks fill in when they arrive.
        if (blocks != null) blocks.step(world);
        ghost.prepareGpu(minecraft, world);
        if (placementDirty) rebuildPlacement();
        if (copyDirty) rebuildCopyBox();
        updateView();

        // The frame above must be drawn before the model, which goes straight to the GPU.
        graphics.flush();
        graphics.enableScissor(viewLeft, viewTop, viewRight, viewBottom);
        // Item icons drawn earlier (tab icons, list icons) leave depth that cut holes in the
        // model as it turned past them. The scissor limits the clear to the view.
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(originX, originY, Z_MID);
        pose.scale((float) viewScale, (float) viewScale, (float) zScale);
        pose.mulPose(viewRotation);
        modelView.set(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
        Matrix4f projection = RenderSystem.getProjectionMatrix();

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        // The view matrix is a mirror (see updateView), so winding flips; the boxes are simple
        // enough to draw both sides.
        RenderSystem.disableCull();
        if (blocks != null && blocks.shown()) {
            blocks.draw(modelView, projection);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        } else {
            terrain.draw(modelView, projection);
        }

        BlueprintGhost.Shape shape = ghost.shape();
        BlockPos origin = placed && shape != null ? origin(shape) : null;
        if (origin != null && ghost.hasRecording()) {
            pose.pushPose();
            pose.translate(origin.getX() - terrain.centerX(), origin.getY() - terrain.centerY(),
                    origin.getZ() - terrain.centerZ());
            ghost.replay(graphics.bufferSource(), pose);
            pose.popPose();
            graphics.flush();
            RenderSystem.disableCull();
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        // Under a cut the shells would float over the opened rooms; their names stay.
        if (appliedCut == NO_CUT) drawBuffer(shellFaces, modelView, projection);
        if (origin != null) {
            ghostModelView.set(modelView).translate((float) (origin.getX() - terrain.centerX()),
                    (float) (origin.getY() - terrain.centerY()), (float) (origin.getZ() - terrain.centerZ()));
            ghost.drawBoxes(ghostModelView, projection);
            drawBuffer(placeFaces, modelView, projection);
            drawBuffer(placeLines, modelView, projection);
        }
        if (appliedCut == NO_CUT) drawBuffer(shellLines, modelView, projection);
        drawBuffer(copyFaces, modelView, projection);
        drawBuffer(copyLines, modelView, projection);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        pose.popPose();
        graphics.disableScissor();
    }

    /** Building names over their shells, when they are big enough on screen to read. Overlay pass. */
    void renderLabels(GuiGraphics graphics, Font font) {
        if (!terrain.ready()) return;
        updateView();
        for (BuilderDeskMenu.BuildingRow row : buildings) {
            int wide = Math.max(row.shellMax().getX() - row.shellMin().getX(), row.shellMax().getZ() - row.shellMin().getZ()) + 1;
            if (wide * viewScale < 28) continue;
            double x = (row.shellMin().getX() + row.shellMax().getX() + 1) / 2.0 - terrain.centerX();
            double y = row.shellMax().getY() + 1 - terrain.centerY();
            double z = (row.shellMin().getZ() + row.shellMax().getZ() + 1) / 2.0 - terrain.centerZ();
            int sx = (int) Math.round(originX + (x * cosYaw + z * sinYaw) * viewScale);
            int sy = (int) Math.round(originY + (-x * sinYaw * sinPitch - y * cosPitch + z * cosYaw * sinPitch)
                    * viewScale);
            String name = StorageUi.trim(font, row.name(), 100);
            int w = font.width(name);
            int lx = sx - w / 2, ly = sy - 12;
            if (lx < viewLeft + 2 || lx + w > viewRight - 2 || ly < viewTop + 2 || ly + 9 > viewBottom - 2) continue;
            graphics.fill(lx - 2, ly - 1, lx + w + 2, ly + 9, LABEL_BACK);
            graphics.drawString(font, name, lx, ly, StorageUi.TEXT, false);
        }
    }

    /** Frees every GPU buffer and stops any Turn in progress. Safe to call twice. */
    void close() {
        terrain.close();
        if (pendingTerrain != null) pendingTerrain.close();
        pendingTerrain = null;
        if (blocks != null) blocks.close();
        ghost.close();
        if (shellFaces != null) shellFaces.close();
        if (shellLines != null) shellLines.close();
        shellFaces = shellLines = null;
        closePlacementBuffers();
        closeCopyBuffers();
        // A later render (the view shown again) uploads the copy box anew.
        copyDirty = copyMin != null;
    }
}
