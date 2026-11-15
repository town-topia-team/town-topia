package com.alex.test.ui.screen;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import com.alex.test.block.WoodenChairBlock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A rotatable cutaway schematic of one room, used by house management and barracks inspection. It is the storage
 * room screen's view lifted out as-is; that screen still has its own copy until it moves over.
 *
 * <p>The room is built once as a list of boxes. Their projected faces are cached until the
 * camera moves and drawn as one depth-tested quad batch, so shapes stay solid at every angle
 * and a frame costs a single draw call. Real blocks from the client's world are drawn through
 * the same projection. Anything clickable carries a {@code target} position; the owning screen
 * colours targets through its {@link Styler}.
 */
final class RoomSchematicView {
    /** How the owning screen colours clickable things: selection, hover, status. */
    interface Styler {
        /** Colour multiplier for a real block drawn for {@code target}. */
        float[] tint(BlockPos target, boolean hovered);
        /** Colour of a flat box standing for {@code target}; {@code base} is its own colour. */
        int boxColor(BlockPos target, int base, boolean hovered);
    }

    static final float[] NO_TINT = {1f, 1f, 1f};

    // Schematic colours.
    static final int FLOOR = 0xFF4A4A4A;
    static final int WALL = 0xFF6E6E6E;
    static final int DOOR = 0xFF4E6F53;
    private static final int VIEW = 0xFF111111;
    private static final int PANEL_BORDER = 0xFF454545;

    private static final int NORTH = 1, SOUTH = 2, WEST = 4, EAST = 8;
    private static final double WALL_THICKNESS = 0.1;
    private static final double WALL_HEIGHT = 0.4;
    private static final double SHADOW_SPREAD = 0.22;
    private static final float SHADOW_ALPHA = 0.38f;
    // The world's entity light directions (Lighting.setupLevel), for chests.
    private static final Vector3f WORLD_LIGHT_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f WORLD_LIGHT_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final double DEFAULT_YAW = Math.PI / 4;
    private static final double DEFAULT_PITCH = Math.toRadians(30);
    private static final double MIN_PITCH = Math.toRadians(18);
    private static final double MAX_PITCH = Math.toRadians(75);
    private static final double MIN_ZOOM = 0.5, MAX_ZOOM = 6;
    // GUI z band for the schematic: above the panel (z 0), below tooltips (z 400).
    private static final float Z_MID = 200, Z_SPAN = 140, LABEL_Z = 360;

    private final Styler styler;

    private double yaw = DEFAULT_YAW;
    private double pitch = DEFAULT_PITCH;
    private double zoom = 1;
    private double panX, panY;
    private int dragButton = -1;
    private double dragDistance;
    @Nullable private BlockPos hovered;
    private int viewLeft, viewTop, viewRight = 1, viewBottom = 1;

    /**
     * Axis-aligned box in room space: blocks, relative to the middle of the room. Invisible
     * boxes stand behind real blocks for bounds, label occlusion and clicking.
     */
    private record Box(double x0, double y0, double z0, double x1, double y1, double z1, int color,
                       @Nullable BlockPos target, @Nullable String label, int hiddenSides, boolean visible) {}
    private record Label(String text, int x, int y) {}
    /**
     * A world block drawn as itself; {@code target} tints it with its container or the door.
     * {@code stand} replaces the world's block entity, to show it differently (a bed in another colour).
     */
    private record BlockDraw(BlockPos pos, BlockState state, double dy, @Nullable BlockPos target,
                             boolean entity, float cropTop, @Nullable BlockEntity stand) {}
    /** The client's own entity, drawn where it is each frame; {@code mark} a flat colour under it, 0 none. */
    private record EntityDraw(int entityId, @Nullable BlockPos target, int mark) {}

    private final List<Box> boxes = new ArrayList<>();
    private final List<BlockDraw> blockDraws = new ArrayList<>();
    private final List<EntityDraw> entityDraws = new ArrayList<>();
    private double roomX, roomY, roomZ;
    private double radius = 1, minY, maxY;
    private double ledgeDepth = 0.18;
    /** Footprints (room space) of chests: not full blocks, so they get a drawn contact shadow. */
    private final List<double[]> shadowFootprints = new ArrayList<>();

    // Projected view, rebuilt only when the camera or the layout changes.
    private boolean viewDirty = true;
    private double cosYaw, sinYaw, sinPitch, cosPitch, viewScale, originX, originY, zScale;
    private final Matrix4f viewRotation = new Matrix4f();
    private float[] shadowVertices = new float[0];
    private float[] shadowAlphas = new float[0];
    private int shadowCount;
    private float[] faceVertices = new float[0];
    private int[] faceBoxes = new int[0];
    private float[] faceShades = new float[0];
    private int faceCount;
    private final List<Label> labels = new ArrayList<>();

    RoomSchematicView(Styler styler) {
        this.styler = styler;
    }

    // =========================================================================
    // Room model
    // =========================================================================

    /** Forgets the room; the camera stays where it is. */
    void clear() {
        boxes.clear();
        blockDraws.clear();
        entityDraws.clear();
        shadowFootprints.clear();
        recordings = null;
        hovered = null;
        viewDirty = true;
    }

    /**
     * As {@link #clear()}, but keeps the recorded block shapes: for a rebuild that queues exactly
     * the same blocks again, where only boxes (villagers) moved. Recording every block is the slow part.
     */
    void clearKeepingBlocks() {
        List<Recording> kept = recordings;
        clear();
        recordings = kept;
    }

    /** Centres room space on these cells; call before adding anything. */
    void centerOn(Collection<BlockPos> inside) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE, baseY = Integer.MAX_VALUE;
        for (BlockPos pos : inside) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
            baseY = Math.min(baseY, pos.getY());
        }
        if (inside.isEmpty()) {
            minX = maxX = minZ = maxZ = baseY = 0;
        }
        roomX = (minX + maxX + 1) / 2.0;
        roomY = baseY;
        roomZ = (minZ + maxZ + 1) / 2.0;
    }

    /** Measures the room for fitting it in the view; call after adding everything. */
    void finish() {
        radius = 1;
        minY = Double.MAX_VALUE;
        maxY = -Double.MAX_VALUE;
        for (Box box : boxes) {
            double dx = Math.max(Math.abs(box.x0()), Math.abs(box.x1()));
            double dz = Math.max(Math.abs(box.z0()), Math.abs(box.z1()));
            radius = Math.max(radius, Math.sqrt(dx * dx + dz * dz));
            minY = Math.min(minY, box.y0());
            maxY = Math.max(maxY, box.y1());
        }
        if (boxes.isEmpty()) minY = maxY = 0;
        viewDirty = true;
    }

    /**
     * Floor: walkable tiles minus spots standing on furniture, plus the cells furniture
     * occupies at floor level, so the floor runs under the furniture instead of around it.
     */
    static Set<BlockPos> fillFloor(Collection<BlockPos> tiles, Set<BlockPos> objects) {
        Set<BlockPos> floor = new LinkedHashSet<>();
        for (BlockPos tile : tiles) {
            if (!objects.contains(tile.below())) floor.add(tile);
        }
        Deque<BlockPos> open = new ArrayDeque<>(floor);
        while (!open.isEmpty()) {
            BlockPos cell = open.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(dir);
                if (objects.contains(next) && !objects.contains(next.below()) && floor.add(next)) open.add(next);
            }
        }
        return floor;
    }

    /** The registered door plus, for a double door, the leaf beside it (lower halves). */
    static List<BlockPos> doorCells(@Nullable ClientLevel world, BlockPos door) {
        List<BlockPos> cells = new ArrayList<>(List.of(door));
        if (world == null) return cells;
        BlockState state = world.getBlockState(door);
        if (!(state.getBlock() instanceof DoorBlock)) return cells;
        Direction facing = state.getValue(DoorBlock.FACING);
        BlockPos lower = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? door.below() : door;
        for (Direction side : new Direction[] {facing.getClockWise(), facing.getCounterClockWise()}) {
            BlockPos next = lower.relative(side);
            BlockState other = world.getBlockState(next);
            if (other.getBlock() instanceof DoorBlock && other.getValue(DoorBlock.FACING) == facing
                    && other.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                cells.add(next);
                break;
            }
        }
        return cells;
    }

    /**
     * Flat schematic floor and low wall ledges; the doors are real blocks when the world has
     * them ({@code world} non-null), else green pads targeting {@code doorTarget}.
     */
    void addShell(@Nullable ClientLevel world, Set<BlockPos> floor, Set<BlockPos> inside,
                  List<BlockPos> doors, BlockPos doorTarget, @Nullable String doorLabel) {
        addShell(world, floor, inside, doors, doorTarget, doorLabel, true);
    }

    /** As above; with {@code withWalls} false only the floor and doors (the mine: a tunnel's walls are all rock). */
    void addShell(@Nullable ClientLevel world, Set<BlockPos> floor, Set<BlockPos> inside,
                  List<BlockPos> doors, BlockPos doorTarget, @Nullable String doorLabel, boolean withWalls) {
        List<BlockPos> realDoors = new ArrayList<>();
        for (BlockPos door : doors) {
            if (world != null && addDraw(world, door, 0, doorTarget)) {
                addDraw(world, door.above(), 0, doorTarget);
                // The whole doorway picks, not just the thin door panel.
                addBlockBox(door, 0, 2, doorTarget, null);
                realDoors.add(door);
            } else {
                addBox(door.getX(), door.getY(), door.getZ(), door.getX() + 1, door.getY() + 0.16,
                        door.getZ() + 1, DOOR, doorTarget, door.equals(doorTarget) ? doorLabel : null, 0, true);
            }
        }
        // A real door stands on floor; a pad is its own floor. The floor is the actual block
        // under each tile where the world has it, the flat slab where it does not.
        Set<BlockPos> ground = new LinkedHashSet<>(floor);
        ground.addAll(realDoors);
        Set<BlockPos> flat = new LinkedHashSet<>();
        for (BlockPos cell : ground) {
            if (world != null && addDraw(world, cell.below(), 0, null)) addBlockBox(cell.below(), 0, 1, null, null);
            else flat.add(cell);
        }
        addFlatFloor(flat, inside);
        // Real floor blocks are a full block deep, so the wall ledges reach down with them.
        ledgeDepth = flat.size() < ground.size() ? 1.0 : 0.18;
        if (!withWalls) return;
        if (world != null && flat.size() < ground.size()) {
            addRealWalls(world, floor, inside);
            return;
        }

        // Walls sit just outside the room so they never cut into floor or furniture, and
        // straight runs are merged into one box each.
        Map<Direction, Set<BlockPos>> walls = new EnumMap<>(Direction.class);
        for (Direction dir : Direction.Plane.HORIZONTAL) walls.put(dir, new HashSet<>());
        for (BlockPos cell : floor) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (!isInside(inside, cell.relative(dir))) walls.get(dir).add(cell);
            }
        }
        for (Map.Entry<Direction, Set<BlockPos>> entry : walls.entrySet()) {
            Set<BlockPos> cells = entry.getValue();
            Direction along = entry.getKey().getAxis() == Direction.Axis.Z ? Direction.EAST : Direction.SOUTH;
            for (BlockPos start : cells) {
                if (cells.contains(start.relative(along.getOpposite()))) continue;
                BlockPos end = start;
                while (cells.contains(end.relative(along))) end = end.relative(along);
                addWall(entry.getKey(), start, end, inside);
            }
        }
    }

    /**
     * The room's own walls as a low cutaway rim: each wall cell's foundation block (level with
     * the floor) and the wall block above it cropped to {@link #WALL_HEIGHT}. Diagonal cells
     * close the corners.
     */
    private void addRealWalls(ClientLevel world, Set<BlockPos> floor, Set<BlockPos> inside) {
        Set<BlockPos> walls = new LinkedHashSet<>();
        for (BlockPos cell : floor) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos next = cell.offset(dx, 0, dz);
                    if (!isInside(inside, next)) walls.add(next);
                }
            }
        }
        for (BlockPos wall : walls) {
            BlockState state = world.getBlockState(wall);
            // An opening in the wall stays open; block entities are not cropped.
            if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
            boolean base = addDraw(world, wall.below(), 0, null);
            // Other doorways and chairs are not wall: they stand whole, a door with its top half.
            if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof WoodenChairBlock) {
                addDraw(world, wall, 0, null);
                boolean tall = state.getBlock() instanceof DoorBlock
                        && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                        && addDraw(world, wall.above(), 0, null);
                addBlockBox(wall, base ? -1 : 0, tall ? 2 : 1, null, null);
                continue;
            }
            blockDraws.add(new BlockDraw(wall.immutable(), state, 0, null, false, (float) WALL_HEIGHT, null));
            addBlockBox(wall, base ? -1 : 0, WALL_HEIGHT, null, null);
        }
    }

    private void addFlatFloor(Set<BlockPos> floor, Set<BlockPos> inside) {
        for (BlockPos cell : floor) {
            int hidden = 0;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(dir);
                // Edges against another slab or a wall can never be seen.
                if (floor.contains(next) || !isInside(inside, next)) hidden |= sideBit(dir);
            }
            addBox(cell.getX(), cell.getY() - 0.18, cell.getZ(), cell.getX() + 1, cell.getY() - 0.03,
                    cell.getZ() + 1, FLOOR, null, null, hidden, true);
        }
    }

    private void addWall(Direction dir, BlockPos start, BlockPos end, Set<BlockPos> inside) {
        double y0 = start.getY() - ledgeDepth, y1 = start.getY() + WALL_HEIGHT, t = WALL_THICKNESS;
        if (dir.getAxis() == Direction.Axis.Z) {
            // Reach into the corner square where the outline turns outward.
            double x0 = start.getX() - (isInside(inside, start.west()) ? 0 : t);
            double x1 = end.getX() + 1 + (isInside(inside, end.east()) ? 0 : t);
            double z = dir == Direction.NORTH ? start.getZ() - t : start.getZ() + 1;
            addBox(x0, y0, z, x1, y1, z + t, WALL, null, null, 0, true);
        } else {
            double x = dir == Direction.WEST ? start.getX() - t : start.getX() + 1;
            addBox(x, y0, start.getZ(), x + t, y1, end.getZ() + 1, WALL, null, null, 0, true);
        }
    }

    /** The real block when the world has it, else a labelled coloured box. */
    void addFurniture(@Nullable ClientLevel world, @Nullable BlockPos pos, int color, String label) {
        if (pos == null) return;
        if (world != null && addDraw(world, pos, 0, null)) {
            addBlockBox(pos, 0, 1, null, null);
            return;
        }
        addBox(pos.getX() + 0.05, pos.getY(), pos.getZ() + 0.05, pos.getX() + 0.95, pos.getY() + 0.9,
                pos.getZ() + 0.95, color, null, label, 0, true);
    }

    /** Queues the world's block at {@code pos} for drawing; false when there is nothing to draw. */
    boolean addDraw(ClientLevel world, BlockPos pos, double dy, @Nullable BlockPos target) {
        BlockState state = world.getBlockState(pos);
        RenderShape shape = state.getRenderShape();
        if (state.isAir() || shape == RenderShape.INVISIBLE) return false;
        blockDraws.add(new BlockDraw(pos.immutable(), state, dy, target,
                shape == RenderShape.ENTITYBLOCK_ANIMATED, 1f, null));
        return true;
    }

    /**
     * Draws {@code stand}, a block entity made up for the view, at {@code pos} through its own
     * renderer: the world's block there, recoloured, or one that is no longer there at all.
     */
    void addStandIn(BlockPos pos, BlockEntity stand, @Nullable BlockPos target) {
        blockDraws.add(new BlockDraw(pos.immutable(), stand.getBlockState(), 0, target, true, 1f, stand));
    }

    /** An invisible full-cell box behind a drawn block: bounds, label occlusion and clicking. */
    void addBlockBox(BlockPos pos, double y0, double y1, @Nullable BlockPos target, @Nullable String label) {
        addBox(pos.getX(), pos.getY() + y0, pos.getZ(), pos.getX() + 1, pos.getY() + y1, pos.getZ() + 1,
                0, target, label, 0, false);
    }

    /** A box in world coordinates. */
    void addBox(double x0, double y0, double z0, double x1, double y1, double z1, int color,
                @Nullable BlockPos target, @Nullable String label, int hiddenSides, boolean visible) {
        boxes.add(new Box(x0 - roomX, y0 - roomY, z0 - roomZ, x1 - roomX, y1 - roomY, z1 - roomZ,
                color, target, label, hiddenSides, visible));
    }

    /**
     * The client's own entity {@code entityId}, drawn where it is each frame, so it walks and turns as in
     * the world, with a soft shadow and, when {@code mark} is not 0, a flat mark of that colour under it.
     * Picked by its live box. False when the client has no such entity of that type: draw something else.
     * It adds nothing to the view's fit; add an invisible box for that.
     */
    boolean addEntity(int entityId, EntityType<?> type, @Nullable BlockPos target, int mark) {
        Entity entity = liveEntity(entityId);
        if (entity == null || entity.getType() != type) return false;
        entityDraws.add(new EntityDraw(entityId, target, mark));
        return true;
    }

    @Nullable private static Entity liveEntity(int entityId) {
        ClientLevel world = Minecraft.getInstance().level;
        Entity entity = world == null ? null : world.getEntity(entityId);
        return entity == null || entity.isRemoved() || entity.isInvisible() ? null : entity;
    }

    /** A soft contact shadow round a footprint on the floor at {@code y}, in world coordinates. */
    void addShadowFootprint(double x0, double y, double z0, double x1, double z1) {
        shadowFootprints.add(new double[] {x0 - roomX, y - roomY, z0 - roomZ, x1 - roomX, z1 - roomZ});
    }

    /** Level changes (a step up or down) still count as the room continuing. */
    static boolean isInside(Set<BlockPos> inside, BlockPos pos) {
        return inside.contains(pos) || inside.contains(pos.above()) || inside.contains(pos.below());
    }

    private static int sideBit(Direction dir) {
        return switch (dir) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> EAST;
        };
    }

    // =========================================================================
    // Camera and input
    // =========================================================================

    /** Where the view sits on screen; cheap to call every frame. */
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

    void setHovered(@Nullable BlockPos target) {
        hovered = target;
    }

    @Nullable BlockPos hovered() {
        return hovered;
    }

    boolean isDragging() {
        return dragButton != -1;
    }

    void resetCamera() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = 1;
        panX = panY = 0;
        viewDirty = true;
    }

    /** Left drag rotates; right or middle drag pans. False when the press is not in the view. */
    boolean beginDrag(double mouseX, double mouseY, int button) {
        if (button > 2 || !contains(mouseX, mouseY)) return false;
        dragButton = button;
        dragDistance = 0;
        return true;
    }

    boolean drag(int button, double dragX, double dragY) {
        if (button != dragButton) return false;
        dragDistance += Math.abs(dragX) + Math.abs(dragY);
        if (button == 0) {
            yaw += dragX * 0.012;
            pitch = Mth.clamp(pitch + dragY * 0.008, MIN_PITCH, MAX_PITCH);
        } else {
            panX += dragX;
            panY += dragY;
        }
        viewDirty = true;
        return true;
    }

    /** Ends a drag; true when it was a left click rather than a drag, so it selects. */
    boolean endDrag(int button) {
        if (button != dragButton) return false;
        dragButton = -1;
        return button == 0 && dragDistance < 4;
    }

    /** Zooms toward the cursor: the point under it stays put. */
    boolean zoomAt(double mouseX, double mouseY, double scrollY) {
        if (!contains(mouseX, mouseY)) return false;
        double next = Mth.clamp(zoom * Math.pow(1.15, scrollY), MIN_ZOOM, MAX_ZOOM);
        double ratio = next / zoom;
        double centerX = (viewLeft + viewRight) / 2.0, centerY = (viewTop + viewBottom) / 2.0;
        panX = mouseX - centerX - (mouseX - centerX - panX) * ratio;
        panY = mouseY - centerY - (mouseY - centerY - panY) * ratio;
        zoom = next;
        viewDirty = true;
        return true;
    }

    // =========================================================================
    // Projection
    // =========================================================================

    /** Scale that fits the whole room at any yaw. Measured at the default tilt so tilting never rescales. */
    private double fitScale() {
        double halfWidth = (viewRight - viewLeft) / 2.0 - 12;
        double height = viewBottom - viewTop - 24;
        return Math.max(1, Math.min(halfWidth / radius,
                height / (2 * radius * Math.sin(DEFAULT_PITCH) + (maxY - minY) * Math.cos(DEFAULT_PITCH))));
    }

    private void updateView() {
        if (!viewDirty) return;
        viewDirty = false;
        cosYaw = Math.cos(yaw);
        sinYaw = Math.sin(yaw);
        sinPitch = Math.sin(pitch);
        cosPitch = Math.cos(pitch);
        viewScale = fitScale() * zoom;
        // Keep at least part of the room on screen however far it is panned.
        double reachX = radius * viewScale;
        double reachY = (radius * sinPitch + (maxY - minY) * cosPitch / 2) * viewScale;
        panX = Mth.clamp(panX, -reachX, reachX);
        panY = Mth.clamp(panY, -reachY, reachY);
        originX = (viewLeft + viewRight) / 2.0 + panX;
        originY = (viewTop + viewBottom) / 2.0 + panY + (minY + maxY) / 2 * cosPitch * viewScale;
        zScale = Z_SPAN / (radius + Math.max(Math.abs(minY), Math.abs(maxY)) + 1);

        int maxFaces = boxes.size() * 3;
        if (faceBoxes.length < maxFaces) {
            faceVertices = new float[maxFaces * 12];
            faceBoxes = new int[maxFaces];
            faceShades = new float[maxFaces];
        }
        // The same projection as a matrix, for the real blocks: columns are where the world
        // x, y and z axes land as (screen x, screen y, depth). GUI y points down, so this is a
        // mirror (det < 0) just like the scale(16, -16, 16) vanilla uses for GUI items.
        viewRotation.set(
                (float) cosYaw, (float) (-sinYaw * sinPitch), (float) (-sinYaw * cosPitch), 0,
                0, (float) -cosPitch, (float) sinPitch, 0,
                (float) sinYaw, (float) (cosYaw * sinPitch), (float) (cosYaw * cosPitch), 0,
                0, 0, 0, 1);

        faceCount = 0;
        float[] px = new float[8], py = new float[8], pz = new float[8];
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            if (!box.visible()) continue;
            for (int corner = 0; corner < 8; corner++) {
                double x = (corner & 1) == 0 ? box.x0() : box.x1();
                double y = (corner & 2) == 0 ? box.y0() : box.y1();
                double z = (corner & 4) == 0 ? box.z0() : box.z1();
                double rx = x * cosYaw + z * sinYaw;
                double rz = -x * sinYaw + z * cosYaw;
                px[corner] = (float) (originX + rx * viewScale);
                py[corner] = (float) (originY + (rz * sinPitch - y * cosPitch) * viewScale);
                // Nearer the camera = higher GUI z, so the depth test hides what is behind.
                pz[corner] = (float) (Z_MID + (y * sinPitch + rz * cosPitch) * zScale);
            }
            addFace(i, 2, 3, 7, 6, 1f, px, py, pz);
            int hidden = box.hiddenSides();
            if (cosYaw < 0 && (hidden & NORTH) == 0) addFace(i, 0, 1, 3, 2, sideShade(0, -1), px, py, pz);
            if (cosYaw > 0 && (hidden & SOUTH) == 0) addFace(i, 4, 5, 7, 6, sideShade(0, 1), px, py, pz);
            if (sinYaw > 0 && (hidden & WEST) == 0) addFace(i, 0, 4, 6, 2, sideShade(-1, 0), px, py, pz);
            if (sinYaw < 0 && (hidden & EAST) == 0) addFace(i, 1, 5, 7, 3, sideShade(1, 0), px, py, pz);
        }

        // Soft contact shadow round each footprint: a ring on the floor fading outward.
        int maxShadowQuads = shadowFootprints.size() * 8;
        if (shadowAlphas.length < maxShadowQuads * 4) {
            shadowVertices = new float[maxShadowQuads * 12];
            shadowAlphas = new float[maxShadowQuads * 4];
        }
        shadowCount = 0;
        for (double[] f : shadowFootprints) {
            double x0 = f[0], y = f[1] + 0.01, z0 = f[2], x1 = f[3], z1 = f[4], e = SHADOW_SPREAD;
            float a = SHADOW_ALPHA;
            addShadowQuad(y, new double[] {x0, x1, x1, x0}, new double[] {z0, z0, z0 - e, z0 - e}, a, a, 0, 0);
            addShadowQuad(y, new double[] {x0, x1, x1, x0}, new double[] {z1, z1, z1 + e, z1 + e}, a, a, 0, 0);
            addShadowQuad(y, new double[] {x0, x0, x0 - e, x0 - e}, new double[] {z0, z1, z1, z0}, a, a, 0, 0);
            addShadowQuad(y, new double[] {x1, x1, x1 + e, x1 + e}, new double[] {z0, z1, z1, z0}, a, a, 0, 0);
            addShadowQuad(y, new double[] {x0, x0, x0 - e, x0 - e}, new double[] {z0, z0 - e, z0 - e, z0}, a, 0, 0, 0);
            addShadowQuad(y, new double[] {x1, x1, x1 + e, x1 + e}, new double[] {z0, z0 - e, z0 - e, z0}, a, 0, 0, 0);
            addShadowQuad(y, new double[] {x0, x0, x0 - e, x0 - e}, new double[] {z1, z1 + e, z1 + e, z1}, a, 0, 0, 0);
            addShadowQuad(y, new double[] {x1, x1, x1 + e, x1 + e}, new double[] {z1, z1 + e, z1 + e, z1}, a, 0, 0, 0);
        }

        labels.clear();
        double dirX = -cosPitch * sinYaw, dirY = sinPitch, dirZ = cosPitch * cosYaw;
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            if (box.label() == null) continue;
            double cx = (box.x0() + box.x1()) / 2, cy = (box.y0() + box.y1()) / 2;
            double cz = (box.z0() + box.z1()) / 2;
            // Pin the label to the face the camera sees; skip it when something is in front.
            double exit = rayExit(box, cx, cy, cz, dirX, dirY, dirZ);
            double sx = cx + dirX * exit, sy = cy + dirY * exit, sz = cz + dirZ * exit;
            boolean covered = false;
            for (int j = 0; j < boxes.size() && !covered; j++) {
                Box blocker = boxes.get(j);
                // The ray climbs, so anything wholly below the anchor (most floor) can't cover it.
                if (j == i || blocker.y1() <= sy) continue;
                double other = rayExit(blocker, sx, sy, sz, dirX, dirY, dirZ);
                covered = !Double.isNaN(other) && other > 1e-3;
            }
            if (covered) continue;
            double rx = cx * cosYaw + cz * sinYaw;
            double rz = -cx * sinYaw + cz * cosYaw;
            labels.add(new Label(box.label(), (int) Math.round(originX + rx * viewScale),
                    (int) Math.round(originY + (rz * sinPitch - cy * cosPitch) * viewScale)));
        }
    }

    private float sideShade(int normalX, int normalZ) {
        // Fixed light from screen-left, so shading turns smoothly with the room.
        return (float) (0.66 - 0.16 * (normalX * cosYaw + normalZ * sinYaw));
    }

    /** One floor-level shadow quad; alphas per corner, in the order of {@code xs}/{@code zs}. */
    private void addShadowQuad(double y, double[] xs, double[] zs, float... alphas) {
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
        if (Math.abs(area) < 0.02) return;
        // Same culling rule as the faces: front faces wind with negative screen area.
        int[] order = area > 0 ? new int[] {0, 3, 2, 1} : new int[] {0, 1, 2, 3};
        for (int v = 0; v < 4; v++) {
            int i = shadowCount * 4 + v;
            shadowVertices[i * 3] = px[order[v]];
            shadowVertices[i * 3 + 1] = py[order[v]];
            shadowVertices[i * 3 + 2] = pz[order[v]];
            shadowAlphas[i] = alphas[order[v]];
        }
        shadowCount++;
    }

    private void addFace(int box, int a, int b, int c, int d, float shade, float[] px, float[] py, float[] pz) {
        double area = px[a] * py[b] - px[b] * py[a] + px[b] * py[c] - px[c] * py[b]
                + px[c] * py[d] - px[d] * py[c] + px[d] * py[a] - px[a] * py[d];
        if (Math.abs(area) < 0.02) return;
        // GUI quads are back-face culled; front faces wind with negative screen area.
        if (area > 0) { int swap = b; b = d; d = swap; }
        int o = faceCount * 12;
        int[] corners = {a, b, c, d};
        for (int corner : corners) {
            faceVertices[o++] = px[corner];
            faceVertices[o++] = py[corner];
            faceVertices[o++] = pz[corner];
        }
        faceBoxes[faceCount] = box;
        faceShades[faceCount] = shade;
        faceCount++;
    }

    /** Ray parameter where the line meets the box nearest the camera, or NaN when it misses. */
    private static double rayExit(Box box, double ox, double oy, double oz, double dx, double dy, double dz) {
        double enter = -Double.MAX_VALUE, exit = Double.MAX_VALUE;
        if (Math.abs(dx) < 1e-9) {
            if (ox < box.x0() || ox > box.x1()) return Double.NaN;
        } else {
            double a = (box.x0() - ox) / dx, b = (box.x1() - ox) / dx;
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
        }
        if (Math.abs(dy) < 1e-9) {
            if (oy < box.y0() || oy > box.y1()) return Double.NaN;
        } else {
            double a = (box.y0() - oy) / dy, b = (box.y1() - oy) / dy;
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
        }
        if (Math.abs(dz) < 1e-9) {
            if (oz < box.z0() || oz > box.z1()) return Double.NaN;
        } else {
            double a = (box.z0() - oz) / dz, b = (box.z1() - oz) / dz;
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
        }
        return enter <= exit ? exit : Double.NaN;
    }

    /** The target whose visible surface is under the cursor. */
    @Nullable BlockPos pick(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) return null;
        updateView();
        double rx = (mouseX - originX) / viewScale;
        double rz = (mouseY - originY) / viewScale / sinPitch;
        double ox = rx * cosYaw - rz * sinYaw, oz = rx * sinYaw + rz * cosYaw;
        double dirX = -cosPitch * sinYaw, dirY = sinPitch, dirZ = cosPitch * cosYaw;
        BlockPos hit = null;
        double nearest = -Double.MAX_VALUE;
        for (Box box : boxes) {
            if (box.target() == null) continue;
            double exit = rayExit(box, ox, 0, oz, dirX, dirY, dirZ);
            if (!Double.isNaN(exit) && exit > nearest) {
                nearest = exit;
                hit = box.target();
            }
        }
        // Live entities by where they are now, not where the room was built.
        for (EntityDraw draw : entityDraws) {
            Entity entity = draw.target() == null ? null : liveEntity(draw.entityId());
            if (entity == null) continue;
            AABB live = entity.getBoundingBox();
            Box box = new Box(live.minX - roomX, live.minY - roomY, live.minZ - roomZ, live.maxX - roomX,
                    live.maxY - roomY, live.maxZ - roomZ, 0, draw.target(), null, 0, false);
            double exit = rayExit(box, ox, 0, oz, dirX, dirY, dirZ);
            if (!Double.isNaN(exit) && exit > nearest) {
                nearest = exit;
                hit = draw.target();
            }
        }
        return hit;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    /** The framed view with the room in it. */
    void render(GuiGraphics graphics, Font font) {
        graphics.fill(viewLeft - 1, viewTop - 1, viewRight + 1, viewBottom + 1, PANEL_BORDER);
        graphics.fill(viewLeft, viewTop, viewRight, viewBottom, VIEW);
        updateView();
        graphics.enableScissor(viewLeft, viewTop, viewRight, viewBottom);
        Matrix4f pose = graphics.pose().last().pose();
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        for (int face = 0; face < faceCount; face++) {
            int color = shade(boxColor(boxes.get(faceBoxes[face])), faceShades[face]);
            int end = face * 12 + 12;
            for (int v = face * 12; v < end; v += 3) {
                buffer.addVertex(pose, faceVertices[v], faceVertices[v + 1], faceVertices[v + 2]).setColor(color);
            }
        }
        renderBlocks(graphics);
        renderEntities(graphics);
        graphics.flush();
        Lighting.setupFor3DItems();
        // Contact shadows go on after the floor is down, so they blend over it.
        if (shadowCount > 0) {
            VertexConsumer shadow = graphics.bufferSource().getBuffer(RenderType.gui());
            for (int i = 0; i < shadowCount * 4; i++) {
                shadow.addVertex(pose, shadowVertices[i * 3], shadowVertices[i * 3 + 1], shadowVertices[i * 3 + 2])
                        .setColor(0f, 0f, 0f, shadowAlphas[i]);
            }
            graphics.flush();
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, LABEL_Z);
        for (Label label : labels) {
            graphics.drawCenteredString(font, label.text(), label.x(), label.y() - 4, 0xFFFFFFFF);
        }
        graphics.pose().popPose();
        graphics.disableScissor();
    }

    /**
     * Draws the room's real blocks through the same projection as the boxes, so the two share
     * one depth range. Block entities (chests, beds) go through their own renderer, which also
     * joins the halves of a double chest; everything else through its block model.
     */
    private void renderBlocks(GuiGraphics graphics) {
        ClientLevel world = Minecraft.getInstance().level;
        if (blockDraws.isEmpty() || world == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource buffers = graphics.bufferSource();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(originX, originY, Z_MID);
        pose.scale((float) viewScale, (float) viewScale, (float) zScale);
        pose.mulPose(viewRotation);
        // Block entities are lit like entities. Aim that light the way the world's is, rather
        // than the GUI's fixed item light, so they shade like the blocks around them as the room
        // turns. Restored by render once the batch is drawn.
        org.joml.Matrix3f normal = pose.last().normal();
        RenderSystem.setShaderLights(new Vector3f(WORLD_LIGHT_0).mul(normal).normalize(),
                new Vector3f(WORLD_LIGHT_1).mul(normal).normalize());
        // Block models: shaded once, replayed through the current view.
        if (recordings == null) recordBlocks(world);
        Matrix4f view = pose.last().pose();
        org.joml.Matrix3f viewNormal = pose.last().normal();
        for (Recording recording : recordings) {
            recording.vertices().replay(buffers.getBuffer(recording.type()), view, viewNormal,
                    tint(recording.target()));
        }
        // Block entities: live, so lids animate and a double chest joins its halves.
        for (BlockDraw draw : blockDraws) {
            if (!draw.entity()) continue;
            float[] tint = tint(draw.target());
            MultiBufferSource source = type -> new ViewConsumer(buffers.getBuffer(type), tint[0], tint[1], tint[2]);
            pose.pushPose();
            pose.translate(draw.pos().getX() - roomX, draw.pos().getY() - roomY + draw.dy(),
                    draw.pos().getZ() - roomZ);
            BlockEntity entity = draw.stand() != null ? draw.stand() : world.getBlockEntity(draw.pos());
            if (entity == null || minecraft.getBlockEntityRenderDispatcher().renderItem(entity, pose, source,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY)) {
                minecraft.getBlockRenderer().renderSingleBlock(draw.state(), pose, source,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            }
            pose.popPose();
        }
        pose.popPose();
    }

    private static final ResourceLocation ENTITY_SHADOW = ResourceLocation.withDefaultNamespace("textures/misc/shadow.png");
    /** True only while {@link #renderEntities} draws: their name tags stay off. */
    private static boolean drawingEntities;

    static {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderNameTagEvent.class, event -> {
            if (drawingEntities) event.setCanRender(TriState.FALSE);
        });
    }

    /**
     * The live entities through the same projection as the blocks, each where it is this frame, lit and
     * tinted as the block entities. Vanilla's shadow, hit boxes (F3+B) and name tags stay off; a soft
     * round shadow and the mark are drawn under each here instead, so they move with it.
     */
    private void renderEntities(GuiGraphics graphics) {
        if (entityDraws.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        MultiBufferSource buffers = graphics.bufferSource();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(originX, originY, Z_MID);
        pose.scale((float) viewScale, (float) viewScale, (float) zScale);
        pose.mulPose(viewRotation);
        org.joml.Matrix3f normal = pose.last().normal();
        RenderSystem.setShaderLights(new Vector3f(WORLD_LIGHT_0).mul(normal).normalize(),
                new Vector3f(WORLD_LIGHT_1).mul(normal).normalize());
        Matrix4f matrix = pose.last().pose();
        List<Entity> live = new ArrayList<>(entityDraws.size());
        List<double[]> spots = new ArrayList<>(entityDraws.size());
        for (EntityDraw draw : entityDraws) {
            Entity entity = liveEntity(draw.entityId());
            live.add(entity);
            spots.add(entity == null ? null : new double[] {
                    Mth.lerp(partialTick, entity.xOld, entity.getX()) - roomX,
                    Mth.lerp(partialTick, entity.yOld, entity.getY()) - roomY,
                    Mth.lerp(partialTick, entity.zOld, entity.getZ()) - roomZ});
        }
        // Floor first, one batch each: the shadows, then the marks.
        VertexConsumer shadows = buffers.getBuffer(RenderType.entityShadow(ENTITY_SHADOW));
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i) == null) continue;
            double[] at = spots.get(i);
            double reach = live.get(i).getBbWidth() * 0.75;
            floorQuad(shadows, matrix, at[0] - reach, at[1] + 0.015, at[2] - reach, at[0] + reach, at[2] + reach,
                    0x99FFFFFF, true);
        }
        VertexConsumer marks = buffers.getBuffer(RenderType.gui());
        for (int i = 0; i < live.size(); i++) {
            EntityDraw draw = entityDraws.get(i);
            if (live.get(i) == null || draw.mark() == 0) continue;
            double[] at = spots.get(i);
            double reach = live.get(i).getBbWidth() / 2 + 0.12;
            floorQuad(marks, matrix, at[0] - reach, at[1] + 0.03, at[2] - reach, at[0] + reach, at[2] + reach,
                    boxColor(draw.target(), draw.mark()), false);
        }
        boolean hitBoxes = dispatcher.shouldRenderHitBoxes();
        dispatcher.setRenderShadow(false);
        dispatcher.setRenderHitBoxes(false);
        drawingEntities = true;
        try {
            for (int i = 0; i < live.size(); i++) {
                Entity entity = live.get(i);
                if (entity == null) continue;
                double[] at = spots.get(i);
                float[] tint = tint(entityDraws.get(i).target());
                MultiBufferSource source = type -> new ViewConsumer(buffers.getBuffer(type), tint[0], tint[1], tint[2]);
                dispatcher.render(entity, at[0], at[1], at[2], Mth.lerp(partialTick, entity.yRotO, entity.getYRot()),
                        partialTick, pose, source, LightTexture.FULL_BRIGHT);
            }
        } finally {
            drawingEntities = false;
            dispatcher.setRenderShadow(true);
            dispatcher.setRenderHitBoxes(hitBoxes);
            pose.popPose();
        }
    }

    /**
     * A flat square on the floor in room space, wound both ways so the culled side never hides it.
     * {@code textured}: an entity-format vertex (the shadow), else position and colour only.
     */
    private static void floorQuad(VertexConsumer out, Matrix4f matrix, double x0, double y, double z0,
                                  double x1, double z1, int color, boolean textured) {
        float[] xs = {(float) x0, (float) x1, (float) x1, (float) x0};
        float[] zs = {(float) z0, (float) z0, (float) z1, (float) z1};
        float[] us = {0, 1, 1, 0}, vs = {0, 0, 1, 1};
        int[][] windings = {{0, 1, 2, 3}, {3, 2, 1, 0}};
        for (int[] winding : windings) {
            for (int i : winding) {
                VertexConsumer vertex = out.addVertex(matrix, xs[i], (float) y, zs[i]).setColor(color);
                if (textured) {
                    vertex.setUv(us[i], vs[i]).setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(LightTexture.FULL_BRIGHT).setNormal(0, 1, 0);
                }
            }
        }
    }

    // The world's block render types without mipmaps, as inventory icons draw. Mip levels
    // switch with the viewing angle, which made patterned textures shimmer as the room turned.
    private static final RenderType VIEW_SOLID = viewType("solid", RenderStateShard.RENDERTYPE_SOLID_SHADER,
            RenderStateShard.NO_TRANSPARENCY);
    private static final RenderType VIEW_CUTOUT = viewType("cutout", RenderStateShard.RENDERTYPE_CUTOUT_SHADER,
            RenderStateShard.NO_TRANSPARENCY);
    private static final RenderType VIEW_TRANSLUCENT = viewType("translucent",
            RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER, RenderStateShard.TRANSLUCENT_TRANSPARENCY);

    private static RenderType viewType(String name, RenderStateShard.ShaderStateShard shader,
                                       RenderStateShard.TransparencyStateShard transparency) {
        return RenderType.create("dynamicvillages_room_view_" + name, DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS, 262144, false, transparency != RenderStateShard.NO_TRANSPARENCY,
                RenderType.CompositeState.builder()
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setShaderState(shader)
                        .setTextureState(RenderStateShard.BLOCK_SHEET)
                        .setTransparencyState(transparency)
                        .createCompositeState(true));
    }

    /** The un-mipmapped view type for a chunk layer; anything unusual keeps its moving-block type. */
    static RenderType viewRenderType(RenderType chunkLayer) {
        if (chunkLayer == RenderType.solid()) return VIEW_SOLID;
        if (chunkLayer == RenderType.cutout() || chunkLayer == RenderType.cutoutMipped()) return VIEW_CUTOUT;
        if (chunkLayer == RenderType.translucent()) return VIEW_TRANSLUCENT;
        return RenderTypeHelper.getMovingBlockRenderType(chunkLayer);
    }

    /** One render type's worth of shaded block vertices for one tint target. */
    private record Recording(RenderType type, @Nullable BlockPos target, Recorder vertices) {}
    private record RecordingKey(RenderType type, @Nullable BlockPos target) {}
    @Nullable private List<Recording> recordings;

    /**
     * Runs every block model through the world's own tesselator once, in room space: per-face
     * shading and ambient occlusion from the real neighbours, the soft contact shadows blocks
     * have in game. Faces against another drawn block are culled as in the world -- buried
     * faces flickered through the seams as the view turned -- while faces against anything
     * the cutaway leaves out stay.
     */
    private void recordBlocks(ClientLevel world) {
        Minecraft minecraft = Minecraft.getInstance();
        ModelBlockRenderer tesselator = minecraft.getBlockRenderer().getModelRenderer();
        Map<RecordingKey, Recorder> byKey = new java.util.LinkedHashMap<>();
        Set<BlockPos> shown = new HashSet<>();
        Set<BlockPos> fullHeight = new HashSet<>();
        for (BlockDraw draw : blockDraws) {
            shown.add(draw.pos());
            if (draw.cropTop() >= 1f) fullHeight.add(draw.pos());
        }
        // A cut-down wall hides its sides against anything, as they are always covered. A full
        // block must keep its side next to a cut-down wall, whose top half is open air -- so
        // full blocks are shaded against a world where the cut walls are not there.
        CutawayLevel withWalls = new CutawayLevel(world, shown);
        CutawayLevel withoutWalls = new CutawayLevel(world, fullHeight);
        PoseStack pose = new PoseStack();
        for (BlockDraw draw : blockDraws) {
            if (draw.entity()) continue;
            pose.pushPose();
            pose.translate(draw.pos().getX() - roomX, draw.pos().getY() - roomY + draw.dy(),
                    draw.pos().getZ() - roomZ);
            BakedModel model = minecraft.getBlockRenderer().getBlockModel(draw.state());
            long seed = draw.state().getSeed(draw.pos());
            for (RenderType type : model.getRenderTypes(draw.state(), RandomSource.create(seed), ModelData.EMPTY)) {
                Recorder recorder = byKey.computeIfAbsent(new RecordingKey(viewRenderType(type), draw.target()),
                        key -> new Recorder());
                if (draw.cropTop() >= 1f) {
                    tesselator.tesselateBlock(withoutWalls, model, draw.state(), draw.pos(), pose, recorder, true,
                            RandomSource.create(), seed, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, type);
                } else {
                    Recorder whole = new Recorder();
                    tesselator.tesselateBlock(withWalls, model, draw.state(), draw.pos(), pose, whole, true,
                            RandomSource.create(), seed, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, type);
                    recorder.appendCropped(whole,
                            (float) (draw.pos().getY() - roomY + draw.dy()) + draw.cropTop());
                }
            }
            pose.popPose();
        }
        recordings = new ArrayList<>();
        byKey.forEach((key, recorder) -> recordings.add(new Recording(key.type(), key.target(), recorder)));
    }

    /**
     * The world as the cutaway shows it: only the drawn blocks exist. Ambient occlusion against
     * the real world darkened cut-down wall tops and the floor beside them as if the hidden upper
     * walls and ceiling were still there; against this it shades only where visible blocks meet.
     */
    private record CutawayLevel(ClientLevel world, Set<BlockPos> shown)
            implements net.minecraft.world.level.BlockAndTintGetter {
        @Override public BlockState getBlockState(BlockPos pos) {
            return shown.contains(pos) ? world.getBlockState(pos)
                    : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return shown.contains(pos) ? world.getFluidState(pos)
                    : net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }
        @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return shown.contains(pos) ? world.getBlockEntity(pos) : null;
        }
        @Override public float getShade(Direction direction, boolean shade) {
            return world.getShade(direction, shade);
        }
        @Override public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
            return world.getLightEngine();
        }
        @Override public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) {
            return world.getBlockTint(pos, resolver);
        }
        @Override public int getHeight() {
            return world.getHeight();
        }
        @Override public int getMinBuildHeight() {
            return world.getMinBuildHeight();
        }
    }

    /**
     * Stores vertices as the tesselator emits them, and replays them through a view.
     * Also used by {@link BlueprintPreviewView} (as are viewRenderType and ViewConsumer).
     */
    static final class Recorder implements VertexConsumer {
        // Per vertex: x y z u v nx ny nz, and colour + overlay.
        private float[] floats = new float[256 * 8];
        private int[] ints = new int[256 * 2];
        private int size;

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if ((size + 1) * 8 > floats.length) {
                floats = java.util.Arrays.copyOf(floats, floats.length * 2);
                ints = java.util.Arrays.copyOf(ints, ints.length * 2);
            }
            int f = size * 8;
            floats[f] = x;
            floats[f + 1] = y;
            floats[f + 2] = z;
            ints[size * 2] = 0xFFFFFFFF;
            ints[size * 2 + 1] = OverlayTexture.NO_OVERLAY;
            size++;
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            ints[(size - 1) * 2] = a << 24 | r << 16 | g << 8 | b;
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) {
            floats[(size - 1) * 8 + 3] = u;
            floats[(size - 1) * 8 + 4] = v;
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) {
            ints[(size - 1) * 2 + 1] = u | v << 16;
            return this;
        }
        @Override public VertexConsumer setUv2(int u, int v) {
            return this; // replayed full-bright
        }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            int f = (size - 1) * 8;
            floats[f + 5] = x;
            floats[f + 6] = y;
            floats[f + 7] = z;
            return this;
        }

        /**
         * Appends {@code source} cut off at height {@code cutY}: vertices above it come down to
         * the cut, and on a side face the texture is cropped with them rather than squashed.
         */
        void appendCropped(Recorder source, float cutY) {
            for (int quad = 0; quad + 3 < source.size; quad += 4) {
                for (int i = quad; i < quad + 4; i++) {
                    int f = i * 8;
                    addVertex(source.floats[f], source.floats[f + 1], source.floats[f + 2]);
                    int to = (size - 1) * 8;
                    System.arraycopy(source.floats, f + 3, floats, to + 3, 5);
                    ints[(size - 1) * 2] = source.ints[i * 2];
                    ints[(size - 1) * 2 + 1] = source.ints[i * 2 + 1];
                    if (source.floats[f + 1] <= cutY) continue;
                    // The vertex below this one on the same vertical edge sets the crop.
                    for (int j = quad; j < quad + 4; j++) {
                        int g = j * 8;
                        if (j == i || source.floats[g + 1] > cutY
                                || Math.abs(source.floats[g] - source.floats[f]) > 1e-4f
                                || Math.abs(source.floats[g + 2] - source.floats[f + 2]) > 1e-4f) continue;
                        float t = (cutY - source.floats[g + 1]) / (source.floats[f + 1] - source.floats[g + 1]);
                        floats[to + 3] = source.floats[g + 3] + (source.floats[f + 3] - source.floats[g + 3]) * t;
                        floats[to + 4] = source.floats[g + 4] + (source.floats[f + 4] - source.floats[g + 4]) * t;
                        break;
                    }
                    floats[to + 1] = cutY;
                }
            }
        }

        void replay(VertexConsumer out, Matrix4f pose, org.joml.Matrix3f normal, float[] tint) {
            Vector3f position = new Vector3f(), direction = new Vector3f();
            for (int i = 0; i < size; i++) {
                int f = i * 8, color = ints[i * 2];
                pose.transformPosition(floats[f], floats[f + 1], floats[f + 2], position);
                normal.transform(floats[f + 5], floats[f + 6], floats[f + 7], direction);
                if (direction.lengthSquared() > 0) direction.normalize();
                out.addVertex(position.x, position.y, position.z)
                        .setColor((int) (((color >> 16) & 255) * tint[0]), (int) (((color >> 8) & 255) * tint[1]),
                                (int) ((color & 255) * tint[2]), color >>> 24)
                        .setUv(floats[f + 3], floats[f + 4])
                        .setOverlay(ints[i * 2 + 1])
                        .setLight(LightTexture.FULL_BRIGHT)
                        .setNormal(direction.x, direction.y, direction.z);
            }
        }
    }

    private float[] tint(@Nullable BlockPos target) {
        return target == null ? NO_TINT : styler.tint(target, target.equals(hovered));
    }

    private int boxColor(Box box) {
        return boxColor(box.target(), box.color());
    }

    private int boxColor(@Nullable BlockPos target, int color) {
        return target == null ? color : styler.boxColor(target, color, target.equals(hovered));
    }

    /**
     * Tints a real block for selection and forces full brightness: a double chest otherwise
     * picks up the world's light and turns dark in an unlit room.
     */
    record ViewConsumer(VertexConsumer inner, float red, float green, float blue) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            inner.addVertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            inner.setColor((int) (r * red), (int) (g * green), (int) (b * blue), a);
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) {
            inner.setUv(u, v);
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) {
            inner.setUv1(u, v);
            return this;
        }
        @Override public VertexConsumer setUv2(int u, int v) {
            inner.setLight(LightTexture.FULL_BRIGHT);
            return this;
        }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            inner.setNormal(x, y, z);
            return this;
        }
    }

    private static int shade(int color, double factor) {
        int r = (int)(((color >> 16) & 255) * factor);
        int g = (int)(((color >> 8) & 255) * factor);
        int b = (int)((color & 255) * factor);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    static int lighten(int color) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        return 0xFF000000 | (r + (255 - r) / 4) << 16 | (g + (255 - g) / 4) << 8 | (b + (255 - b) / 4);
    }
}
