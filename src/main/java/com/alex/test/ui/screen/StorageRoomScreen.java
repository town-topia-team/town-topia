package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.block.WoodenChairBlock;
import com.alex.test.network.MayorBuildingPayloads;
import com.alex.test.network.StorageRoomPayloads;
import com.alex.test.ui.StorageRoomMenu;
import com.alex.test.ui.screen.mayor.MayorStatsScreen;
import com.alex.test.village.building.StorageBuilding;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Storage room management: a rotatable cutaway schematic of the room next to the deposit
 * filter of the selected container. Selecting the door edits every container at once.
 *
 * <p>The room is built once as a list of boxes. Their projected faces are cached until the
 * camera moves and drawn as one depth-tested quad batch, so shapes stay solid at every angle
 * and a frame costs a single draw call.
 */
public final class StorageRoomScreen extends AbstractContainerScreen<StorageRoomMenu> {
    // Palette shared with the other management screens (see mayor/MayorLook).
    private static final int OUTER = 0xFF080808;
    private static final int PANEL_BORDER = 0xFF454545;
    private static final int PANEL = 0xFF202020;
    private static final int PANEL_DARK = 0xFF141414;
    private static final int VIEW = 0xFF111111;
    private static final int ROW = 0xFF292929;
    private static final int ROW_HOVER = 0xFF383838;
    private static final int ROW_SELECTED = 0xFF3C5641;
    private static final int BUTTON_HOVER_BORDER = 0xFF666666;
    private static final int TEXT = 0xFFF3F3F3;
    private static final int MUTED = 0xFFAAAAAA;
    private static final int ACCENT = 0xFF6BAF72;

    // Schematic colours.
    private static final int FLOOR = 0xFF4A4A4A;
    private static final int WALL = 0xFF6E6E6E;
    private static final int DOOR = 0xFF4E6F53;
    private static final int TABLE = 0xFF9A784B;
    private static final int FURNACE = 0xFF8A8A8A;
    private static final int CHEST = 0xFFB28450;
    private static final int BARREL = 0xFF987049;

    private static final int PAD = 8, HEADER_H = 28, FOOTER_H = 16, SIDE_W = 164;
    private static final int CARD_H = 40, CELL_H = 18, CELL_GAP = 2, GRID_Y = 66;
    // Container panel rows under the card: lock switch, Category/Items switch, then content.
    private static final int LOCK_Y = 52, SEGMENT_Y = 72, CONTENT_Y = 94, PICK_CELL = 19, PICK_COLUMNS = 8;
    private static final int TAB_GAP = 8, GRID_SCROLL_STEP = 12;
    private static String resetLabel() { return Tr.s("screen.dynamicvillages.storage.reset_view", "Reset view"); }
    private static String mayorLabel() { return Tr.s("screen.dynamicvillages.storage.back_to_mayor", "Back to Mayor"); }

    private static final int NORTH = 1, SOUTH = 2, WEST = 4, EAST = 8;
    private static final double WALL_THICKNESS = 0.1;
    private static final double WALL_HEIGHT = 0.4;
    private static final float[] SELECTED_TINT = {0.55f, 1f, 0.6f};
    private static final float[] SELECTED_HOVER_TINT = {0.72f, 1f, 0.76f};
    private static final float[] HOVER_TINT = {1f, 0.88f, 0.55f};
    private static final float[] NO_TINT = {1f, 1f, 1f};
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

    private double yaw = DEFAULT_YAW;
    private double pitch = DEFAULT_PITCH;
    private double zoom = 1;
    private double panX, panY;
    private int dragButton = -1;
    private double dragDistance;
    /** A container's primary position, or the door position for the whole room. */
    @Nullable private BlockPos selected;
    @Nullable private BlockPos hovered;
    /** Every selected container; shift-click adds and removes. Empty for the door. */
    private final Set<BlockPos> selection = new LinkedHashSet<>();

    private enum Page { CONTAINERS, STOCK }
    private enum PanelTab { CATEGORY, ITEMS }
    private Page page = Page.CONTAINERS;
    private PanelTab panelTab = PanelTab.CATEGORY;
    private EditBox itemSearch;
    private EditBox dishSearch;
    private String itemQuery = "", dishQuery = "";
    private StorageStockPage stockPage;
    // Item picker: the village's items filtered by the search, picked ones first.
    private final List<Item> pickerItems = new ArrayList<>();
    private final Map<Item, String> itemNames = new HashMap<>();
    private boolean pickerDirty = true;
    private int itemScroll;
    // The category grid and its note scroll down to the panel's bottom on a short window (GUI scale Auto).
    private int gridScroll;
    private int gridContentHeight;
    private boolean draggingGridBar;

    /**
     * Axis-aligned box in room space: blocks, relative to the middle of the room. Invisible
     * boxes stand behind real blocks for bounds, label occlusion and clicking.
     */
    private record Box(double x0, double y0, double z0, double x1, double y1, double z1, int color,
                       @Nullable BlockPos target, @Nullable String label, int hiddenSides, boolean visible) {}
    private record Label(String text, int x, int y) {}
    /** A world block drawn as itself; {@code target} tints it with its container or the door. */
    private record BlockDraw(BlockPos pos, BlockState state, double dy, @Nullable BlockPos target,
                             boolean entity, float cropTop) {}

    private final List<Box> boxes = new ArrayList<>();
    private final List<BlockDraw> blockDraws = new ArrayList<>();
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

    public StorageRoomScreen(StorageRoomMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 600;
        imageHeight = 350;
        titleLabelY = -1000;
        inventoryLabelY = -1000;
        // Opened from the room's frame rather than a chest: start on the whole room.
        selected = menu.initialSelection() != null ? menu.initialSelection() : menu.doorPos();
        if (!roomMode() && marker(selected) != null) selection.add(selected);
        buildRoom();
    }

    @Override protected void init() {
        imageWidth = Math.min(600, width - 16);
        imageHeight = Math.min(350, height - 16);
        super.init();
        viewDirty = true;
        itemSearch = addRenderableWidget(new EditBox(font, 0, 0, SIDE_W - 12, 16,
                Tr.c("screen.dynamicvillages.storage.search_items", "Search items")));
        itemSearch.setHint(Tr.c("screen.dynamicvillages.storage.search_items.hint", "Search items..."));
        itemSearch.setMaxLength(64);
        itemSearch.setValue(itemQuery);
        itemSearch.setResponder(value -> {
            itemQuery = value;
            itemScroll = 0;
            pickerDirty = true;
        });
        dishSearch = addRenderableWidget(new EditBox(font, 0, 0, SIDE_W - 12, 16,
                Tr.c("screen.dynamicvillages.orders.search_dishes", "Search dishes...")));
        dishSearch.setMaxLength(64);
        dishSearch.setValue(dishQuery);
        stockPage = new StorageStockPage(menu, font, dishSearch);
        dishSearch.setResponder(value -> {
            dishQuery = value;
            stockPage.resetDishScroll();
        });
        pickerDirty = true;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // Orders move on while the tab is open: ask for fresh progress now and then.
        if (orderPage() && stockPage != null) stockPage.tick();
    }

    private boolean roomMode() {
        return menu.doorPos().equals(selected);
    }

    /**
     * Whether the door's room-wide filter reaches this container. Item frames decide for their
     * own container and locked ones keep their settings.
     */
    private boolean overriddenByRoom(BlockPos target) {
        StorageRoomMenu.ContainerMarker marker = marker(target);
        return marker != null && marker.framed().isEmpty() && !marker.locked();
    }

    /** The shift-selected containers, in room order. */
    private List<StorageRoomMenu.ContainerMarker> selectedMarkers() {
        List<StorageRoomMenu.ContainerMarker> markers = new ArrayList<>();
        if (roomMode()) return markers;
        for (StorageRoomMenu.ContainerMarker marker : menu.containers()) {
            if (selection.contains(marker.pos())) markers.add(marker);
        }
        return markers;
    }

    private static List<BlockPos> unlockedPositions(List<StorageRoomMenu.ContainerMarker> markers) {
        List<BlockPos> positions = new ArrayList<>();
        for (StorageRoomMenu.ContainerMarker marker : markers) if (!marker.locked()) positions.add(marker.pos());
        return positions;
    }

    private void select(BlockPos hit, boolean shift) {
        if (hit.equals(menu.doorPos())) {
            selected = hit;
            selection.clear();
        } else if (shift && !roomMode()) {
            if (!selection.remove(hit)) {
                selection.add(hit);
                selected = hit;
            } else if (hit.equals(selected)) {
                selected = selection.isEmpty() ? null : selection.iterator().next();
            }
        } else {
            selection.clear();
            selection.add(hit);
            selected = hit;
        }
        itemScroll = 0;
        gridScroll = 0;
        pickerDirty = true;
    }

    /** Village claims round 7b: below Officer the chests' categories, picked items and locks are look only. */
    private boolean mayChange() {
        return menu.rights().mayChange();
    }

    private String lookOnlyNote() {
        return Tr.s("screen.dynamicvillages.storage.claim_look_only", "Look only: %s has not let you change these chests.",
                menu.rights().ownerName());
    }

    private void toggleLock() {
        List<StorageRoomMenu.ContainerMarker> markers = selectedMarkers();
        if (markers.isEmpty() || !mayChange()) return;
        boolean lock = !markers.stream().allMatch(StorageRoomMenu.ContainerMarker::locked);
        List<BlockPos> positions = new ArrayList<>();
        for (StorageRoomMenu.ContainerMarker marker : markers) {
            positions.add(marker.pos());
            menu.setLockedLocally(marker.pos(), lock);
        }
        PacketDistributor.sendToServer(new StorageRoomPayloads.SetLocked(menu.frameId(), positions, lock));
    }

    /** Picks or drops {@code item} for every unlocked selected container. */
    private void togglePicked(Item item) {
        if (!mayChange()) return;
        List<StorageRoomMenu.ContainerMarker> markers = selectedMarkers();
        boolean remove = !markers.isEmpty() && markers.stream()
                .filter(marker -> !marker.locked()).allMatch(marker -> marker.chosen().contains(item));
        for (StorageRoomMenu.ContainerMarker marker : markers) {
            if (marker.locked()) continue;
            List<Item> chosen = new ArrayList<>(marker.chosen());
            if (remove) chosen.remove(item);
            else if (!chosen.contains(item) && chosen.size() < StorageBuilding.MAX_CHOSEN_ITEMS) chosen.add(item);
            sendPicked(marker.pos(), chosen);
        }
        pickerDirty = true;
    }

    private void clearPicked() {
        if (!mayChange()) return;
        for (StorageRoomMenu.ContainerMarker marker : selectedMarkers()) {
            if (!marker.locked() && !marker.chosen().isEmpty()) sendPicked(marker.pos(), List.of());
        }
        pickerDirty = true;
    }

    private void sendPicked(BlockPos pos, List<Item> chosen) {
        menu.setChosenLocally(pos, chosen);
        List<net.minecraft.resources.ResourceLocation> ids = new ArrayList<>();
        for (Item item : chosen) ids.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
        PacketDistributor.sendToServer(new StorageRoomPayloads.SetItems(menu.frameId(), List.of(pos), ids));
    }

    @Nullable private StorageRoomMenu.ContainerMarker marker(@Nullable BlockPos pos) {
        if (pos == null) return null;
        for (StorageRoomMenu.ContainerMarker marker : menu.containers()) {
            if (marker.pos().equals(pos)) return marker;
        }
        return null;
    }

    private String containerName(StorageRoomMenu.ContainerMarker marker) {
        int number = menu.containers().indexOf(marker) + 1;
        return marker.barrel() ? Tr.s("screen.dynamicvillages.storage.barrel", "Barrel %s", number) : Tr.s("screen.dynamicvillages.storage.chest", "Chest %s", number);
    }

    /** The categories shared by every container the door reaches, or null when they differ. */
    @Nullable private Set<StorageBuilding.DepositFilter> roomFilter() {
        Set<StorageBuilding.DepositFilter> shared = null;
        for (StorageRoomMenu.ContainerMarker marker : roomTargets()) {
            if (!marker.chosen().isEmpty()) return null;
            if (shared == null) shared = marker.filters();
            else if (!shared.equals(marker.filters())) return null;
        }
        return shared;
    }

    /** The containers the door's override reaches. */
    private List<StorageRoomMenu.ContainerMarker> roomTargets() {
        List<StorageRoomMenu.ContainerMarker> targets = new ArrayList<>();
        for (StorageRoomMenu.ContainerMarker marker : menu.containers()) {
            if (overriddenByRoom(marker.pos())) targets.add(marker);
        }
        return targets;
    }

    /** Containers the door's override leaves alone: item frames and locks. */
    private int keptCount() {
        int count = 0;
        for (StorageRoomMenu.ContainerMarker marker : menu.containers()) {
            if (!overriddenByRoom(marker.pos())) count++;
        }
        return count;
    }

    /**
     * How many of {@code markers} take {@code filter} as one of their categories. In room mode a
     * container with picked items does not count: those decide over its categories.
     */
    private int filterUsers(List<StorageRoomMenu.ContainerMarker> markers, StorageBuilding.DepositFilter filter) {
        int users = 0;
        for (StorageRoomMenu.ContainerMarker marker : markers) {
            if (roomMode() && !marker.chosen().isEmpty()) continue;
            if (marker.filters().contains(filter)) users++;
        }
        return users;
    }

    private static String framedNames(StorageRoomMenu.ContainerMarker marker) {
        String first = marker.framed().getFirst().getDescription().getString();
        int more = marker.framed().size() - 1;
        return more > 0 ? Tr.s("screen.dynamicvillages.storage.and_more", "%1$s +%2$s more", first, more) : first;
    }

    /**
     * A click on a category cell switches it on, or off when every container it applies to
     * already has it. The door sets the room's shared categories on every container (a mixed
     * room starts over from this one); a selection keeps each container's other categories.
     */
    private void applyFilter(StorageBuilding.DepositFilter filter) {
        if (!mayChange()) return;
        if (roomMode()) {
            Set<StorageBuilding.DepositFilter> shared = roomFilter();
            Set<StorageBuilding.DepositFilter> filters = shared == null
                    ? EnumSet.of(filter)
                    : StorageBuilding.DepositFilter.toggled(shared, filter, !shared.contains(filter));
            for (StorageRoomMenu.ContainerMarker marker : menu.containers()) {
                if (marker.locked()) continue;
                menu.setFiltersLocally(marker.pos(), filters);
                menu.setChosenLocally(marker.pos(), List.of());
            }
            PacketDistributor.sendToServer(new StorageRoomPayloads.SetRoomFilter(
                    menu.frameId(), StorageBuilding.DepositFilter.mask(filters)));
            pickerDirty = true;
            return;
        }
        List<StorageRoomMenu.ContainerMarker> unlocked = selectedMarkers().stream()
                .filter(marker -> !marker.locked()).toList();
        if (unlocked.isEmpty()) return;
        boolean on = filterUsers(unlocked, filter) < unlocked.size();
        List<BlockPos> positions = unlockedPositions(unlocked);
        for (BlockPos pos : positions) menu.toggleFilterLocally(pos, filter, on);
        PacketDistributor.sendToServer(new StorageRoomPayloads.SetFilter(menu.frameId(), positions, filter.ordinal(), on));
    }

    private static Item filterIcon(StorageBuilding.DepositFilter filter) {
        return switch (filter) {
            case ANY -> Items.HOPPER;
            case FOOD -> Items.BREAD;
            case CROP -> Items.WHEAT;
            case SEEDS -> Items.WHEAT_SEEDS;
            case RAW_MEAT -> Items.BEEF;
            case WOOD -> Items.OAK_LOG;
            case STONE -> Items.COBBLESTONE;
            case ORES -> Items.RAW_IRON;
            case TOOLS -> Items.IRON_PICKAXE;
            case ARMOR -> Items.IRON_CHESTPLATE;
            case ENCHANTS -> Items.ENCHANTED_BOOK;
            case NONE -> Items.BARRIER;
        };
    }

    // =========================================================================
    // Room model
    // =========================================================================

    private void buildRoom() {
        // Real blocks come from the client's copy of the world; without the chunk, the
        // flat-coloured schematic stands in.
        ClientLevel world = Minecraft.getInstance().level;
        boolean real = world != null && world.isLoaded(menu.doorPos());
        List<StorageRoomMenu.ContainerMarker> markers = menu.containers();
        Set<BlockPos> objects = new HashSet<>();
        // A double chest is drawn whole even if the room only registered one half.
        Map<BlockPos, BlockPos> partners = new HashMap<>();
        for (StorageRoomMenu.ContainerMarker marker : markers) {
            BlockPos partner = marker.partner() != null ? marker.partner()
                    : real ? chestPartner(world, marker.pos()) : null;
            objects.add(marker.pos());
            if (partner != null) {
                objects.add(partner);
                partners.put(marker.pos(), partner);
            }
        }
        if (menu.table() != null) objects.add(menu.table());
        if (menu.furnace() != null) objects.add(menu.furnace());
        List<BlockPos> doors = doorCells(real ? world : null);

        // Floor: walkable tiles minus spots standing on furniture, plus the cells furniture
        // occupies at floor level, so the floor runs under the chests instead of around them.
        Set<BlockPos> floor = new LinkedHashSet<>();
        for (BlockPos tile : menu.floor()) {
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
        Set<BlockPos> inside = new HashSet<>(floor);
        inside.addAll(objects);
        inside.addAll(doors);

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE, baseY = Integer.MAX_VALUE;
        for (BlockPos pos : inside) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
            baseY = Math.min(baseY, pos.getY());
        }
        roomX = (minX + maxX + 1) / 2.0;
        roomY = baseY;
        roomZ = (minZ + maxZ + 1) / 2.0;

        addShell(real ? world : null, floor, inside, doors);
        addFurniture(real ? world : null, menu.table(), TABLE, "T");
        addFurniture(real ? world : null, menu.furnace(), FURNACE, "F");
        int number = 0;
        for (StorageRoomMenu.ContainerMarker marker : markers) {
            number++;
            BlockPos pos = marker.pos();
            BlockPos partner = partners.get(pos);
            BlockPos other = partner == null ? pos : partner;
            // Both halves of a double chest draw from their own block entity.
            boolean drawn = real && addDraw(world, pos, 0, pos);
            if (drawn && partner != null) addDraw(world, partner, 0, pos);
            double inset = marker.barrel() ? 0.03 : 0.0625;
            double height = marker.barrel() ? 0.94 : 0.875;
            addBox(Math.min(pos.getX(), other.getX()) + inset, pos.getY(),
                    Math.min(pos.getZ(), other.getZ()) + inset,
                    Math.max(pos.getX(), other.getX()) + 1 - inset, pos.getY() + height,
                    Math.max(pos.getZ(), other.getZ()) + 1 - inset,
                    marker.barrel() ? BARREL : CHEST, pos, Integer.toString(number), 0, !drawn);
            // Only a chest standing on the floor casts one; a stacked chest would shade the lid below.
            if (drawn && !marker.barrel() && !objects.contains(pos.below())) {
                shadowFootprints.add(new double[] {
                        Math.min(pos.getX(), other.getX()) + inset - roomX, pos.getY() - roomY,
                        Math.min(pos.getZ(), other.getZ()) + inset - roomZ,
                        Math.max(pos.getX(), other.getX()) + 1 - inset - roomX,
                        Math.max(pos.getZ(), other.getZ()) + 1 - inset - roomZ});
            }
        }

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
    }

    /** The other half of a double chest in the client's world, or null for a single container. */
    @Nullable private static BlockPos chestPartner(ClientLevel world, BlockPos pos) {
        return com.alex.test.village.building.StorageContainers.pairedChestPos(world, pos);
    }

    /** The registered door plus, for a double door, the leaf beside it (lower halves). */
    private List<BlockPos> doorCells(@Nullable ClientLevel world) {
        BlockPos door = menu.doorPos();
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

    /** Floor, cut-down walls and doors as the actual blocks of the room. */
    /**
     * Flat schematic floor and low wall ledges; the doors are real blocks when the world has
     * them ({@code world} non-null), else green pads.
     */
    private void addShell(@Nullable ClientLevel world, Set<BlockPos> floor, Set<BlockPos> inside,
                          List<BlockPos> doors) {
        BlockPos target = menu.doorPos();
        List<BlockPos> realDoors = new ArrayList<>();
        for (BlockPos door : doors) {
            if (world != null && addDraw(world, door, 0, target)) {
                addDraw(world, door.above(), 0, target);
                // The whole doorway picks, not just the thin door panel.
                addBlockBox(door, 0, 2, target, null);
                realDoors.add(door);
            } else {
                addBox(door.getX(), door.getY(), door.getZ(), door.getX() + 1, door.getY() + 0.16,
                        door.getZ() + 1, DOOR, target, door.equals(target) ? "D" : null, 0, true);
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
            blockDraws.add(new BlockDraw(wall.immutable(), state, 0, null, false, (float) WALL_HEIGHT));
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
    private void addFurniture(@Nullable ClientLevel world, @Nullable BlockPos pos, int color, String label) {
        if (pos == null) return;
        if (world != null && addDraw(world, pos, 0, null)) {
            addBlockBox(pos, 0, 1, null, null);
            return;
        }
        addBox(pos.getX() + 0.05, pos.getY(), pos.getZ() + 0.05, pos.getX() + 0.95, pos.getY() + 0.9,
                pos.getZ() + 0.95, color, null, label, 0, true);
    }

    /** Queues the world's block at {@code pos} for drawing; false when there is nothing to draw. */
    private boolean addDraw(ClientLevel world, BlockPos pos, double dy, @Nullable BlockPos target) {
        BlockState state = world.getBlockState(pos);
        RenderShape shape = state.getRenderShape();
        if (state.isAir() || shape == RenderShape.INVISIBLE) return false;
        blockDraws.add(new BlockDraw(pos.immutable(), state, dy, target,
                shape == RenderShape.ENTITYBLOCK_ANIMATED, 1f));
        return true;
    }

    /** An invisible full-cell box behind a drawn block: bounds, label occlusion and clicking. */
    private void addBlockBox(BlockPos pos, double y0, double y1, @Nullable BlockPos target, @Nullable String label) {
        addBox(pos.getX(), pos.getY() + y0, pos.getZ(), pos.getX() + 1, pos.getY() + y1, pos.getZ() + 1,
                0, target, label, 0, false);
    }

    private void addBox(double x0, double y0, double z0, double x1, double y1, double z1, int color,
                        @Nullable BlockPos target, @Nullable String label, int hiddenSides, boolean visible) {
        boxes.add(new Box(x0 - roomX, y0 - roomY, z0 - roomZ, x1 - roomX, y1 - roomY, z1 - roomZ,
                color, target, label, hiddenSides, visible));
    }

    /** Level changes (a step up or down) still count as the room continuing. */
    private static boolean isInside(Set<BlockPos> inside, BlockPos pos) {
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
    // Layout
    // =========================================================================

    private int viewLeft() { return leftPos + PAD + 1; }
    private int viewTop() { return topPos + HEADER_H + 1; }
    private int viewRight() { return sideLeft() - 7; }
    private int viewBottom() { return topPos + imageHeight - PAD - FOOTER_H - 1; }
    private int sideLeft() { return leftPos + imageWidth - PAD - SIDE_W; }
    private int sideTop() { return topPos + HEADER_H; }
    private int sideBottom() { return topPos + imageHeight - PAD; }
    private int contentX() { return sideLeft() + 6; }
    private int contentWidth() { return SIDE_W - 12; }
    private int cellWidth() { return (contentWidth() - CELL_GAP) / 2; }
    private int cellX(int index) { return contentX() + index % 2 * (cellWidth() + CELL_GAP); }
    private int cellY(int index) { return gridTop() + index / 2 * (CELL_H + CELL_GAP) - gridScroll; }
    private int gridTop() { return sideTop() + (roomMode() ? GRID_Y : CONTENT_Y); }
    /**
     * The grid and its note scroll between {@link #gridTop()} and here, so on a big GUI scale the
     * last categories never end up below the window. The card and switches above stay put.
     */
    private int gridBandBottom() { return sideBottom() - 4; }
    private int gridBandHeight() { return Math.max(1, gridBandBottom() - gridTop()); }
    private boolean inGridBand(double mouseY) { return mouseY >= gridTop() && mouseY < gridBandBottom(); }
    private int gridScrollMax() { return Math.max(0, gridContentHeight - gridBandHeight()); }
    private boolean gridScrolls() { return filtersShown() && gridScrollMax() > 0; }
    /** In the panel's right margin, where the Items tab draws its bar. */
    private int gridBarX() { return contentX() + contentWidth() + 1; }
    /** Same thumb size as {@link StorageUi#scrollbar}, so dragging matches what is drawn. */
    private int gridBarThumb() {
        int visible = gridBandHeight();
        int total = Math.max(1, visible + gridScrollMax());
        return Math.min(visible, Math.max(18, visible * visible / total));
    }

    /** Moves the grid so the scroll bar's thumb centres on the cursor. */
    private void scrollGridToMouse(double mouseY) {
        int max = gridScrollMax();
        int travel = gridBandHeight() - gridBarThumb();
        double along = (mouseY - gridTop() - gridBarThumb() / 2.0) / Math.max(1, travel);
        gridScroll = Mth.clamp((int) Math.round(along * max), 0, max);
    }
    private int resetWidth() { return 26 + font.width(resetLabel()); }
    /** Reset view sits in the schematic's top-right corner, so the header tabs can use the whole row. */
    private int resetX() { return viewRight() - 4 - resetWidth(); }
    private int resetY() { return viewTop() + 4; }
    private boolean fromMayor() { return menu.mayorId() >= 0; }
    private int mayorWidth() { return 26 + font.width(mayorLabel()); }
    private int mayorX() { return leftPos + imageWidth - PAD - mayorWidth(); }
    /** Where the header tabs must end: before Back to Mayor when there is one, else the panel's edge. */
    private int tabsRight() { return fromMayor() ? mayorX() : leftPos + imageWidth - PAD + TAB_GAP; }
    /** The header tabs split the space between the title and Reset view evenly, equal gaps around. */
    private int tabWidth() {
        int start = leftPos + PAD + 20 + font.width(title) + TAB_GAP;
        int tabs = Page.values().length;
        return Math.max(40, (tabsRight() - TAB_GAP - start - (tabs - 1) * TAB_GAP) / tabs);
    }
    private int tabX(Page target) {
        int start = leftPos + PAD + 20 + font.width(title) + TAB_GAP;
        return start + target.ordinal() * (tabWidth() + TAB_GAP);
    }

    /** The Stock tab: orders for the blacksmith and the cook. */
    private boolean orderPage() {
        return page == Page.STOCK;
    }

    /** One selected container whose item frame decides for it: no category or item pick applies. */
    private boolean framedSelection() {
        List<StorageRoomMenu.ContainerMarker> markers = selectedMarkers();
        return markers.size() == 1 && !markers.getFirst().framed().isEmpty();
    }

    /** The category grid: for the door, or the Category tab of a normal selection. */
    private boolean filtersShown() {
        if (page != Page.CONTAINERS) return false;
        if (roomMode()) return !menu.containers().isEmpty();
        return !selection.isEmpty() && !framedSelection() && panelTab == PanelTab.CATEGORY;
    }

    private boolean pickerShown() {
        return page == Page.CONTAINERS && !roomMode() && !selection.isEmpty() && !framedSelection()
                && panelTab == PanelTab.ITEMS;
    }

    private int pickerTop() { return sideTop() + CONTENT_Y + 20; }
    private int pickerRows() { return Math.max(1, (sideBottom() - 18 - pickerTop()) / PICK_CELL); }

    private void refreshPicker() {
        if (!pickerDirty) return;
        pickerDirty = false;
        pickerItems.clear();
        String query = itemQuery.trim().toLowerCase(java.util.Locale.ROOT);
        Set<Item> picked = new HashSet<>();
        for (StorageRoomMenu.ContainerMarker marker : selectedMarkers()) picked.addAll(marker.chosen());
        for (Item item : menu.villageItems()) {
            String name = itemNames.computeIfAbsent(item, key -> key.getDescription().getString());
            if (query.isEmpty() || name.toLowerCase(java.util.Locale.ROOT).contains(query)
                    || net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString().contains(query)) {
                pickerItems.add(item);
            }
        }
        pickerItems.sort(java.util.Comparator.comparing((Item item) -> !picked.contains(item))
                .thenComparing(item -> itemNames.get(item)));
    }

    /** Index into {@link #pickerItems} under the cursor, or -1. */
    private int pickerItemAt(double mouseX, double mouseY) {
        if (!pickerShown()) return -1;
        int column = (int) Math.floor((mouseX - contentX()) / PICK_CELL);
        int row = (int) Math.floor((mouseY - pickerTop()) / PICK_CELL);
        if (column < 0 || column >= PICK_COLUMNS || row < 0 || row >= pickerRows()) return -1;
        int index = (itemScroll + row) * PICK_COLUMNS + column;
        return index < pickerItems.size() ? index : -1;
    }

    private int filterCellAt(double mouseX, double mouseY) {
        if (!filtersShown() || !inGridBand(mouseY)) return -1;
        for (int i = 0; i < StorageBuilding.DepositFilter.values().length; i++) {
            if (inside(mouseX, mouseY, cellX(i), cellY(i), cellWidth(), CELL_H)) return i;
        }
        return -1;
    }

    private boolean insideView(double x, double y) {
        return page == Page.CONTAINERS
                && x >= viewLeft() && x < viewRight() && y >= viewTop() && y < viewBottom();
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // =========================================================================
    // Projection
    // =========================================================================

    /** Scale that fits the whole room at any yaw. Measured at the default tilt so tilting never rescales. */
    private double fitScale() {
        double halfWidth = (viewRight() - viewLeft()) / 2.0 - 12;
        double height = viewBottom() - viewTop() - 24;
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
        originX = (viewLeft() + viewRight()) / 2.0 + panX;
        originY = (viewTop() + viewBottom()) / 2.0 + panY + (minY + maxY) / 2 * cosPitch * viewScale;
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

        // Soft contact shadow round each chest: a ring on the floor fading outward.
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

    /** The container or door whose visible surface is under the cursor. */
    @Nullable private BlockPos pick(double mouseX, double mouseY) {
        if (!insideView(mouseX, mouseY)) return null;
        if (inside(mouseX, mouseY, resetX(), resetY(), resetWidth(), 20)) return null;
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
        return hit;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        itemSearch.visible = pickerShown();
        if (!itemSearch.visible) itemSearch.setFocused(false);
        dishSearch.visible = orderPage();
        if (!dishSearch.visible) dishSearch.setFocused(false);

        graphics.fill(x - 2, y - 2, x + imageWidth + 2, y + imageHeight + 2, OUTER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        graphics.renderFakeItem(new ItemStack(Items.CHEST), x + PAD, y + 6);
        graphics.drawString(font, title, x + PAD + 20, y + 10, TEXT, false);
        drawTab(graphics, Page.CONTAINERS, Tr.s("screen.dynamicvillages.storage.tab.containers", "Containers"), Items.CHEST, mouseX, mouseY);
        drawTab(graphics, Page.STOCK, Tr.s("screen.dynamicvillages.storage.tab.stock", "Stock"), Items.PUMPKIN_PIE, mouseX, mouseY);
        if (fromMayor()) {
            headerButton(graphics, mayorX(), mayorWidth(), Items.WRITABLE_BOOK, mayorLabel(), false,
                    inside(mouseX, mouseY, mayorX(), topPos + 4, mayorWidth(), 20));
        }

        if (orderPage()) {
            stockPage.layout(x + PAD, y + HEADER_H, x + imageWidth - PAD, y + imageHeight - PAD);
            stockPage.render(graphics, mouseX, mouseY);
            return;
        }
        graphics.fill(viewLeft() - 1, viewTop() - 1, viewRight() + 1, viewBottom() + 1, PANEL_BORDER);
        graphics.fill(viewLeft(), viewTop(), viewRight(), viewBottom(), VIEW);
        renderSchematic(graphics);
        drawResetButton(graphics, mouseX, mouseY);
        int hintWidth = viewRight() - viewLeft();
        String hint = Tr.s("screen.dynamicvillages.storage.view_hint.long", "Drag to rotate  •  Right/middle-drag to pan  •  Scroll to zoom  •  Shift+click to multi-select");
        if (font.width(hint) > hintWidth) hint = Tr.s("screen.dynamicvillages.storage.view_hint.medium", "Drag: rotate • Right-drag: pan • Scroll: zoom • Shift+click: multi-select");
        if (font.width(hint) > hintWidth) hint = Tr.s("screen.dynamicvillages.storage.view_hint.short", "Rotate: drag • Pan: right-drag • Zoom: scroll • Multi: shift+click");
        graphics.drawString(font, trim(hint, hintWidth), viewLeft(), viewBottom() + 5, MUTED, false);

        graphics.fill(sideLeft(), sideTop(), sideLeft() + SIDE_W, sideBottom(), PANEL_DARK);
        renderSidePanel(graphics, mouseX, mouseY);
    }

    private void drawTab(GuiGraphics graphics, Page target, String label, Item icon, int mouseX, int mouseY) {
        int x = tabX(target), width = tabWidth();
        headerButton(graphics, x, width, icon, label, page == target,
                inside(mouseX, mouseY, x, topPos + 4, width, 20));
    }

    private void drawResetButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = resetX(), width = resetWidth();
        headerButton(graphics, x, resetY(), width, Items.COMPASS, resetLabel(), false,
                inside(mouseX, mouseY, x, resetY(), width, 20));
    }

    /** Every header control looks the same: bordered, icon and label centred, green when selected. */
    private void headerButton(GuiGraphics graphics, int x, int width, Item icon, String label,
                              boolean selected, boolean hover) {
        headerButton(graphics, x, topPos + 4, width, icon, label, selected, hover);
    }

    private void headerButton(GuiGraphics graphics, int x, int y, int width, Item icon, String label,
                              boolean selected, boolean hover) {
        graphics.fill(x, y, x + width, y + 20, hover && !selected ? BUTTON_HOVER_BORDER : PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 19, selected ? ROW_SELECTED : hover ? ROW_HOVER : ROW);
        if (selected) graphics.fill(x + 1, y + 17, x + width - 1, y + 19, ACCENT);
        int content = x + (width - 20 - font.width(label)) / 2;
        graphics.renderFakeItem(new ItemStack(icon), content, y + 2);
        graphics.drawString(font, label, content + 20, y + 6, TEXT, false);
    }

    private void renderSidePanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = contentX(), width = contentWidth(), y = sideTop() + 6;
        List<StorageRoomMenu.ContainerMarker> markers = menu.containers();
        List<StorageRoomMenu.ContainerMarker> picked = selectedMarkers();
        StorageRoomMenu.ContainerMarker marker = picked.size() == 1 ? picked.getFirst() : null;
        Item icon;
        String name, detail, extra;
        if (roomMode()) {
            Set<StorageBuilding.DepositFilter> shared = roomFilter();
            int kept = keptCount();
            icon = Items.OAK_DOOR;
            name = Tr.s("screen.dynamicvillages.storage.whole_room", "Whole room");
            detail = markers.size() == 1 ? Tr.s("screen.dynamicvillages.storage.one_container", "1 container")
                    : Tr.s("screen.dynamicvillages.storage.containers", "%s containers", markers.size());
            extra = markers.isEmpty() ? Tr.s("screen.dynamicvillages.storage.nothing_to_set", "Nothing to set")
                    : kept == markers.size() ? Tr.s("screen.dynamicvillages.storage.all_kept", "All frames or locked")
                    : shared == null ? Tr.s("screen.dynamicvillages.storage.mixed_filters", "Mixed filters")
                    : Tr.s("screen.dynamicvillages.storage.all_accept", "All accept %s", StorageBuilding.DepositFilter.labels(shared));
        } else if (marker != null) {
            icon = marker.barrel() ? Items.BARREL : Items.CHEST;
            name = containerName(marker);
            detail = Tr.s("screen.dynamicvillages.storage.items_in_slots", "%1$s items in %2$s slots", marker.items(), marker.slots());
            extra = Tr.s("screen.dynamicvillages.storage.at", "At %s", marker.pos().toShortString());
        } else if (!picked.isEmpty()) {
            icon = Items.CHEST;
            name = Tr.s("screen.dynamicvillages.storage.containers", "%s containers", picked.size());
            detail = Tr.s("screen.dynamicvillages.storage.changes_apply_all", "Changes apply to all");
            extra = Tr.s("screen.dynamicvillages.storage.shift_click_toggles", "Shift+click toggles");
        } else {
            icon = Items.CHEST;
            name = Tr.s("screen.dynamicvillages.storage.nothing_selected", "Nothing selected");
            String[] how = Tr.s("screen.dynamicvillages.storage.nothing_selected.desc", "Click a container, or\nthe door for all").split("\n", 2);
            detail = how[0];
            extra = how.length > 1 ? how[1] : "";
        }
        graphics.fill(x, y, x + width, y + CARD_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + CARD_H - 1, roomMode() ? ROW_SELECTED : ROW);
        graphics.renderFakeItem(new ItemStack(icon), x + 7, y + 12);
        graphics.drawString(font, trim(name, width - 36), x + 30, y + 6, TEXT, false);
        graphics.drawString(font, trim(detail, width - 36), x + 30, y + 17, MUTED, false);
        graphics.drawString(font, trim(extra, width - 36), x + 30, y + 28, MUTED, false);

        if (roomMode()) {
            if (!filtersShown()) return;
            graphics.drawString(font, Tr.s("screen.dynamicvillages.storage.set_every", "Set every container to"), x, gridTop() - 12, TEXT, false);
            renderGridBand(graphics, roomTargets(), !mayChange(), !mayChange() ? lookOnlyNote() : Tr.s("screen.dynamicvillages.storage.set_every.desc", "Overrides the categories and picked items of every chest and barrel here; click several to combine them. Locked ones and ones with an item frame keep their own."),
                    mouseX, mouseY);
            return;
        }
        if (picked.isEmpty()) return;

        // Lock switch.
        boolean allLocked = picked.stream().allMatch(StorageRoomMenu.ContainerMarker::locked);
        boolean anyLocked = picked.stream().anyMatch(StorageRoomMenu.ContainerMarker::locked);
        int lockY = sideTop() + LOCK_Y;
        graphics.drawString(font, Tr.s("screen.dynamicvillages.storage.lock", "Lock settings"), x, lockY + 4, TEXT, false);
        int toggleX = x + width - 44;
        StorageUi.toggle(graphics, font, toggleX, lockY, 44, 16,
                allLocked ? Tr.s("screen.dynamicvillages.storage.lock.on", "On") : anyLocked ? Tr.s("screen.dynamicvillages.storage.lock.some", "Some") : Tr.s("screen.dynamicvillages.storage.lock.off", "Off"), allLocked,
                mayChange() && inside(mouseX, mouseY, toggleX, lockY, 44, 16));

        if (framedSelection()) {
            renderFramedItems(graphics, picked.getFirst(), x, width);
            return;
        }

        int segmentY = sideTop() + SEGMENT_Y, half = (width - 2) / 2;
        StorageUi.segment(graphics, font, x, segmentY, half, 16, Tr.s("screen.dynamicvillages.storage.tab.category", "Category"), panelTab == PanelTab.CATEGORY,
                inside(mouseX, mouseY, x, segmentY, half, 16));
        StorageUi.segment(graphics, font, x + half + 2, segmentY, half, 16, Tr.s("screen.dynamicvillages.storage.tab.items", "Items"), panelTab == PanelTab.ITEMS,
                inside(mouseX, mouseY, x + half + 2, segmentY, half, 16));

        if (panelTab == PanelTab.CATEGORY) {
            long withItems = picked.stream().filter(each -> !each.chosen().isEmpty()).count();
            String note = !mayChange() ? lookOnlyNote() : allLocked ?Tr.s("screen.dynamicvillages.storage.locked.desc", "Locked: unlock to change.")
                    : withItems > 0 ? (withItems == 1 && picked.size() == 1
                            ? Tr.s("screen.dynamicvillages.storage.picked_decide_one", "Picked items decide for this container; clear them in Items.")
                            : Tr.s("screen.dynamicvillages.storage.picked_decide", "Picked items decide for %s of these; clear them in Items.", withItems))
                    : filterUsers(picked, StorageBuilding.DepositFilter.NONE) == picked.size()
                    ? Tr.s("screen.dynamicvillages.storage.note.nothing", "Villagers never put anything here but can still take what is inside.")
                    : Tr.s("screen.dynamicvillages.storage.note.category", "Villagers bring matching items here and can still take what is inside. Click several categories to combine them.");
            renderGridBand(graphics, picked, allLocked || !mayChange(), note, mouseX, mouseY);
        } else {
            renderPicker(graphics, picked, allLocked || !mayChange(), mouseX, mouseY);
        }
    }

    /**
     * The category grid and its note, clipped to the band down to the panel's bottom. When they
     * do not fit (a short window), they scroll and a bar shows in the right margin.
     */
    private void renderGridBand(GuiGraphics graphics, List<StorageRoomMenu.ContainerMarker> markers,
                                boolean locked, String note, int mouseX, int mouseY) {
        gridScroll = Mth.clamp(gridScroll, 0, gridScrollMax());
        int top = gridTop(), bottom = gridBandBottom();
        int end;
        graphics.enableScissor(sideLeft() + 1, top, sideLeft() + SIDE_W - 1, bottom);
        try {
            renderFilterGrid(graphics, markers, locked, mouseX, mouseY);
            end = drawNote(graphics, note, contentX(),
                    cellY(StorageBuilding.DepositFilter.values().length - 1) + CELL_H + 6, contentWidth(), Integer.MAX_VALUE);
        } finally {
            graphics.disableScissor();
        }
        gridContentHeight = end - (top - gridScroll);
        gridScroll = Mth.clamp(gridScroll, 0, gridScrollMax());
        if (gridScrollMax() > 0) {
            StorageUi.scrollbar(graphics, gridBarX(), top, gridBandHeight(), gridScroll, gridScrollMax(), gridBandHeight());
        }
    }

    /**
     * The category grid for {@code markers}: a category all of them take is highlighted, one
     * only some take gets a dim bar. A locked grid is dimmed. Cells scrolled out of the band never light up.
     */
    private void renderFilterGrid(GuiGraphics graphics, List<StorageRoomMenu.ContainerMarker> markers,
                                  boolean locked, int mouseX, int mouseY) {
        StorageBuilding.DepositFilter[] filters = StorageBuilding.DepositFilter.values();
        int cellWidth = cellWidth();
        for (int i = 0; i < filters.length; i++) {
            int cx = cellX(i), cy = cellY(i);
            int users = filterUsers(markers, filters[i]);
            boolean chosen = users > 0 && users == markers.size();
            boolean some = users > 0 && !chosen;
            boolean hover = !locked && inGridBand(mouseY) && inside(mouseX, mouseY, cx, cy, cellWidth, CELL_H);
            graphics.fill(cx, cy, cx + cellWidth, cy + CELL_H, PANEL_BORDER);
            graphics.fill(cx + 1, cy + 1, cx + cellWidth - 1, cy + CELL_H - 1,
                    chosen ? ROW_SELECTED : locked ? StorageUi.ROW_DISABLED : hover ? ROW_HOVER : ROW);
            if (chosen || some) {
                graphics.fill(cx + 1, cy + CELL_H - 2, cx + cellWidth - 1, cy + CELL_H,
                        chosen ? ACCENT : (ACCENT & 0x00FFFFFF) | 0x70000000);
            }
            graphics.renderFakeItem(new ItemStack(filterIcon(filters[i])), cx + 1, cy + 1);
            int labelWidth = cellWidth - 21;
            if (roomMode()) {
                // How many containers the door reaches use this category now.
                if (users > 0) {
                    String text = Integer.toString(users);
                    graphics.drawString(font, text, cx + cellWidth - 4 - font.width(text), cy + 5, MUTED, false);
                    labelWidth -= font.width(text) + 3;
                }
            }
            graphics.drawString(font, trim(filters[i].shown(), labelWidth), cx + 19, cy + 5,
                    chosen || some || hover ? TEXT : MUTED, false);
        }
    }

    /** Items tab: the village's items; the picked ones are all the container takes. */
    private void renderPicker(GuiGraphics graphics, List<StorageRoomMenu.ContainerMarker> picked,
                              boolean locked, int mouseX, int mouseY) {
        int x = contentX(), width = contentWidth();
        itemSearch.setX(x);
        itemSearch.setY(sideTop() + CONTENT_Y);
        itemSearch.setWidth(width);
        refreshPicker();
        int rows = pickerRows();
        int totalRows = (pickerItems.size() + PICK_COLUMNS - 1) / PICK_COLUMNS;
        itemScroll = Mth.clamp(itemScroll, 0, Math.max(0, totalRows - rows));
        int top = pickerTop();
        if (pickerItems.isEmpty()) {
            graphics.drawString(font, menu.villageItems().isEmpty() ? Tr.s("screen.dynamicvillages.storage.empty", "Storage is empty") : Tr.s("screen.dynamicvillages.storage.no_item_matches", "No item matches"),
                    x, top + 4, MUTED, false);
        }
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < PICK_COLUMNS; column++) {
                int index = (itemScroll + row) * PICK_COLUMNS + column;
                if (index >= pickerItems.size()) break;
                Item item = pickerItems.get(index);
                int cx = x + column * PICK_CELL, cy = top + row * PICK_CELL;
                long holders = picked.stream().filter(each -> each.chosen().contains(item)).count();
                boolean all = holders == picked.size(), some = holders > 0;
                boolean hover = inside(mouseX, mouseY, cx, cy, PICK_CELL, PICK_CELL);
                graphics.fill(cx, cy, cx + PICK_CELL, cy + PICK_CELL,
                        all ? ACCENT : some ? StorageUi.GOLD : hover ? BUTTON_HOVER_BORDER : PANEL_BORDER);
                graphics.fill(cx + 1, cy + 1, cx + PICK_CELL - 1, cy + PICK_CELL - 1,
                        some ? ROW_SELECTED : hover ? ROW_HOVER : ROW);
                graphics.renderFakeItem(new ItemStack(item), cx + 2, cy + 2);
            }
        }
        if (totalRows > rows) {
            StorageUi.scrollbar(graphics, x + width + 1, top, rows * PICK_CELL, itemScroll, totalRows - rows, rows);
        }

        int footerY = sideBottom() - 15;
        long count = picked.stream().mapToLong(each -> each.chosen().size()).max().orElse(0);
        String summary = !mayChange() ? Tr.s("screen.dynamicvillages.storage.claim_look", "Look only")
                : locked ? Tr.s("screen.dynamicvillages.storage.locked", "Locked") : count == 0 ? Tr.s("screen.dynamicvillages.storage.takes_category", "Takes its category")
                : Tr.s("screen.dynamicvillages.storage.picked", "%s picked", count);
        graphics.drawString(font, summary, x, footerY + 3, locked ? StorageUi.GOLD : MUTED, false);
        int clearX = x + width - 36;
        StorageUi.button(graphics, font, clearX, footerY, 36, 13, Tr.s("screen.dynamicvillages.storage.clear", "Clear"),
                inside(mouseX, mouseY, clearX, footerY, 36, 13), !locked && count > 0);
    }

    /** A container with an item frame takes only the framed items; categories do not apply. */
    private void renderFramedItems(GuiGraphics graphics, StorageRoomMenu.ContainerMarker marker, int x, int width) {
        int y = sideTop() + SEGMENT_Y + 12;
        graphics.drawString(font, Tr.s("screen.dynamicvillages.storage.framed", "Only accepts (item frame)"), x, y - 12, TEXT, false);
        int shown = 0;
        for (Item item : marker.framed()) {
            if (y + CELL_H > sideBottom() - 40) break;
            StorageUi.row(graphics, x, y, width, CELL_H, true, false);
            graphics.renderFakeItem(new ItemStack(item), x + 1, y + 1);
            graphics.drawString(font, trim(item.getDescription().getString(), width - 24), x + 20, y + 5,
                    TEXT, false);
            y += CELL_H + CELL_GAP;
            shown++;
        }
        if (shown < marker.framed().size()) {
            graphics.drawString(font, Tr.s("screen.dynamicvillages.storage.more", "+%s more", marker.framed().size() - shown), x, y + 2, MUTED, false);
            y += 12;
        }
        drawNote(graphics, Tr.s("screen.dynamicvillages.storage.framed.desc", "Set by the item frame on this container. Take the item out of the frame to use a category or picked items again."), x, y + 6, width, sideBottom() - 4);
    }

    /** Draws {@code note} wrapped to {@code width}, stopping at {@code bottom}; returns the y below its last line. */
    private int drawNote(GuiGraphics graphics, String note, int x, int y, int width, int bottom) {
        for (FormattedCharSequence line : font.split(Component.literal(note), width)) {
            if (y + font.lineHeight > bottom) break;
            graphics.drawString(font, line, x, y, MUTED, false);
            y += font.lineHeight + 1;
        }
        return y;
    }

    private void renderSchematic(GuiGraphics graphics) {
        updateView();
        graphics.enableScissor(viewLeft(), viewTop(), viewRight(), viewBottom());
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
        graphics.flush();
        Lighting.setupFor3DItems();
        // Chest shadows go on after the floor is down, so they blend over it.
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
     * one depth range. Chests go through their block-entity renderer, which also joins the
     * halves of a double chest; everything else through its block model.
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
        // Chests are lit like entities. Aim that light the way the world's is, rather than the
        // GUI's fixed item light, so they shade like the blocks around them as the room turns.
        // Restored by renderSchematic once the batch is drawn.
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
        // Chests: live, so lids animate and a double chest joins its halves.
        for (BlockDraw draw : blockDraws) {
            if (!draw.entity()) continue;
            float[] tint = tint(draw.target());
            MultiBufferSource source = type -> new ViewConsumer(buffers.getBuffer(type), tint[0], tint[1], tint[2]);
            pose.pushPose();
            pose.translate(draw.pos().getX() - roomX, draw.pos().getY() - roomY + draw.dy(),
                    draw.pos().getZ() - roomZ);
            BlockEntity entity = world.getBlockEntity(draw.pos());
            if (entity == null || minecraft.getBlockEntityRenderDispatcher().renderItem(entity, pose, source,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY)) {
                minecraft.getBlockRenderer().renderSingleBlock(draw.state(), pose, source,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, world.getModelData(draw.pos()), null);
            }
            pose.popPose();
        }
        pose.popPose();
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
        return RenderType.create("dynamicvillages_storage_view_" + name, DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS, 262144, false, transparency != RenderStateShard.NO_TRANSPARENCY,
                RenderType.CompositeState.builder()
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setShaderState(shader)
                        .setTextureState(RenderStateShard.BLOCK_SHEET)
                        .setTransparencyState(transparency)
                        .createCompositeState(true));
    }

    /** The un-mipmapped view type for a chunk layer; anything unusual keeps its moving-block type. */
    private static RenderType viewRenderType(RenderType chunkLayer) {
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
    private static ModelData modelData(ClientLevel world, BakedModel model, BlockDraw draw) {
        ModelData data = world.getModelData(draw.pos());
        return model.getModelData(world, draw.pos(), draw.state(), data == null ? ModelData.EMPTY : data);
    }

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
            // The block entity's model data, as the world draws it: Sophisticated barrels take
            // their wood and trim from it.
            ModelData data = modelData(world, model, draw);
            for (RenderType type : model.getRenderTypes(draw.state(), RandomSource.create(seed), data)) {
                Recorder recorder = byKey.computeIfAbsent(new RecordingKey(viewRenderType(type), draw.target()),
                        key -> new Recorder());
                if (draw.cropTop() >= 1f) {
                    tesselator.tesselateBlock(withoutWalls, model, draw.state(), draw.pos(), pose, recorder, true,
                            RandomSource.create(), seed, OverlayTexture.NO_OVERLAY, data, type);
                } else {
                    Recorder whole = new Recorder();
                    tesselator.tesselateBlock(withWalls, model, draw.state(), draw.pos(), pose, whole, true,
                            RandomSource.create(), seed, OverlayTexture.NO_OVERLAY, data, type);
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

    /** Stores vertices as the tesselator emits them, and replays them through a view. */
    private static final class Recorder implements VertexConsumer {
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

    /**
     * Whether {@code target} lights up as selected: the shift-selected containers, or for the
     * door the door itself and every container its override reaches.
     */
    private boolean isChosen(BlockPos target) {
        if (target.equals(menu.doorPos())) return roomMode();
        return roomMode() ? overriddenByRoom(target) : selection.contains(target);
    }

    private float[] tint(@Nullable BlockPos target) {
        if (target == null) return NO_TINT;
        boolean chosen = isChosen(target);
        boolean hover = target.equals(hovered);
        if (chosen) return hover ? SELECTED_HOVER_TINT : SELECTED_TINT;
        return hover ? HOVER_TINT : NO_TINT;
    }

    /**
     * Tints a real block for selection and forces full brightness: a double chest otherwise
     * picks up the world's light and turns dark in an unlit room.
     */
    private record ViewConsumer(VertexConsumer inner, float red, float green, float blue) implements VertexConsumer {
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

    private int boxColor(Box box) {
        BlockPos target = box.target();
        if (target == null) return box.color();
        int color = isChosen(target) ? ACCENT : box.color();
        return target.equals(hovered) ? lighten(color) : color;
    }

    private static int shade(int color, double factor) {
        int r = (int)(((color >> 16) & 255) * factor);
        int g = (int)(((color >> 8) & 255) * factor);
        int b = (int)((color & 255) * factor);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int lighten(int color) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        return 0xFF000000 | (r + (255 - r) / 4) << 16 | (g + (255 - g) / 4) << 8 | (b + (255 - b) / 4);
    }

    private String trim(String value, int width) {
        if (width <= 6) return "";
        return font.width(value) <= width ? value : font.plainSubstrByWidth(value, width - 6) + "...";
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // super.render already draws the background (and with it the schematic) once.
        hovered = dragButton == -1 ? pick(mouseX, mouseY) : null;
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (orderPage()) {
            List<Component> lines = stockPage.tooltip(mouseX, mouseY);
            if (lines != null) graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
        int pick = pickerItemAt(mouseX, mouseY);
        if (pick >= 0) {
            Item item = pickerItems.get(pick);
            boolean all = !selection.isEmpty()
                    && selectedMarkers().stream().allMatch(marker -> marker.chosen().contains(item));
            graphics.renderComponentTooltip(font, List.of(Component.literal(itemNames.get(item)),
                    (!mayChange() ? Tr.c("screen.dynamicvillages.storage.claim_locked", "%s has not let you pick items here.",
                                    menu.rights().ownerName())
                            : all ? Tr.c("screen.dynamicvillages.storage.picked.remove", "Picked: click to remove")
                            : Tr.c("screen.dynamicvillages.storage.picked.add", "Click to pick: the container then takes only picked items"))
                            .withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
            return;
        }
        if (hovered == null) return;
        if (hovered.equals(menu.doorPos())) {
            graphics.renderComponentTooltip(font, List.of(Tr.c("screen.dynamicvillages.room.door", "Door"),
                    Tr.c("screen.dynamicvillages.storage.door.desc", "Click to set every container at once").withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
            return;
        }
        StorageRoomMenu.ContainerMarker marker = marker(hovered);
        if (marker == null) return;
        String accepts = !marker.framed().isEmpty() ? Tr.s("screen.dynamicvillages.storage.only_framed", "Only: %s (item frame)", framedNames(marker))
                : !marker.chosen().isEmpty() ? Tr.s("screen.dynamicvillages.storage.only_picked", "Only: %s picked items", marker.chosen().size())
                : Tr.s("screen.dynamicvillages.storage.accepts", "Accepts: %s", StorageBuilding.DepositFilter.labels(marker.filters()));
        List<Component> lines = new ArrayList<>(List.of(
                Component.literal(containerName(marker)),
                Component.literal(accepts).withStyle(ChatFormatting.GRAY),
                Tr.c("screen.dynamicvillages.storage.items", "%s items", marker.items()).withStyle(ChatFormatting.GRAY)));
        if (marker.locked()) lines.add(Tr.c("screen.dynamicvillages.storage.locked", "Locked").withStyle(ChatFormatting.GOLD));
        if (!roomMode()) lines.add(Tr.c("screen.dynamicvillages.storage.shift_click_more", "Shift+click to select more").withStyle(ChatFormatting.DARK_GRAY));
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Typing in a search box must not close the screen (E) or trigger other keys.
        if (getFocused() instanceof EditBox box && box.isVisible()
                && keyCode != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            box.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Everything is drawn in screen coordinates by renderBg.
    }

    // =========================================================================
    // Input
    // =========================================================================

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Clicking anywhere else takes the focus off a search box.
        if (getFocused() instanceof EditBox box && !box.isMouseOver(mouseX, mouseY)) setFocused(null);
        if (button == 0) {
            if (fromMayor() && inside(mouseX, mouseY, mayorX(), topPos + 4, mayorWidth(), 20)) {
                MayorStatsScreen.returnToBuilding(menu.mayorId(), menu.frameId());
                PacketDistributor.sendToServer(new MayorBuildingPayloads.BackToMayor(menu.mayorId()));
                return true;
            }
            for (Page target : Page.values()) {
                if (inside(mouseX, mouseY, tabX(target), topPos + 4, tabWidth(), 20)) {
                    page = target;
                    gridScroll = 0;
                    if (orderPage()) stockPage.refresh();
                    return true;
                }
            }
            if (orderPage()) {
                if (stockPage.mouseClicked(mouseX, mouseY, hasShiftDown())) return true;
                return super.mouseClicked(mouseX, mouseY, button);
            }
            if (inside(mouseX, mouseY, resetX(), resetY(), resetWidth(), 20)) {
                yaw = DEFAULT_YAW;
                pitch = DEFAULT_PITCH;
                zoom = 1;
                panX = panY = 0;
                viewDirty = true;
                return true;
            }
            if (gridScrolls() && inside(mouseX, mouseY, gridBarX() - 1, gridTop(), 6, gridBandHeight())) {
                draggingGridBar = true;
                scrollGridToMouse(mouseY);
                return true;
            }
            if (clickContainerPanel(mouseX, mouseY)) return true;
        }
        // Left drag rotates; right or middle drag pans.
        if (page == Page.CONTAINERS && button <= 2 && insideView(mouseX, mouseY)) {
            dragButton = button;
            dragDistance = 0;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Clicks on the side panel of the Containers page. */
    private boolean clickContainerPanel(double mouseX, double mouseY) {
        List<StorageRoomMenu.ContainerMarker> picked = selectedMarkers();
        boolean allLocked = !picked.isEmpty() && picked.stream().allMatch(StorageRoomMenu.ContainerMarker::locked);
        int cell = filterCellAt(mouseX, mouseY);
        if (cell >= 0) {
            if (!allLocked) applyFilter(StorageBuilding.DepositFilter.values()[cell]);
            return true;
        }
        if (roomMode() || picked.isEmpty()) return false;
        int x = contentX(), width = contentWidth();
        if (inside(mouseX, mouseY, x + width - 44, sideTop() + LOCK_Y, 44, 16)) {
            toggleLock();
            return true;
        }
        if (framedSelection()) return false;
        int segmentY = sideTop() + SEGMENT_Y, half = (width - 2) / 2;
        if (inside(mouseX, mouseY, x, segmentY, half, 16)) {
            panelTab = PanelTab.CATEGORY;
            gridScroll = 0;
            return true;
        }
        if (inside(mouseX, mouseY, x + half + 2, segmentY, half, 16)) {
            panelTab = PanelTab.ITEMS;
            gridScroll = 0;
            pickerDirty = true;
            return true;
        }
        if (!pickerShown()) return false;
        int item = pickerItemAt(mouseX, mouseY);
        if (item >= 0) {
            if (!allLocked) togglePicked(pickerItems.get(item));
            return true;
        }
        if (inside(mouseX, mouseY, x + width - 36, sideBottom() - 15, 36, 13)) {
            if (!allLocked) clearPicked();
            return true;
        }
        return false;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button,
                                           double dragX, double dragY) {
        if (draggingGridBar) {
            scrollGridToMouse(mouseY);
            return true;
        }
        if (button != dragButton) return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
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

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingGridBar && button == 0) {
            draggingGridBar = false;
            return true;
        }
        if (button == dragButton) {
            dragButton = -1;
            // A click rather than a drag selects.
            if (button == 0 && dragDistance < 4) {
                BlockPos hit = pick(mouseX, mouseY);
                if (hit != null) select(hit, hasShiftDown());
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (orderPage()) {
            return stockPage.mouseScrolled(mouseX, mouseY, scrollY)
                    || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (pickerShown() && inside(mouseX, mouseY, contentX(), pickerTop(), contentWidth(),
                pickerRows() * PICK_CELL)) {
            itemScroll = Math.max(0, itemScroll + (scrollY > 0 ? -1 : 1));
            return true;
        }
        if (gridScrolls() && inside(mouseX, mouseY, sideLeft(), sideTop(), SIDE_W, sideBottom() - sideTop())) {
            gridScroll = Mth.clamp(gridScroll + (scrollY < 0 ? GRID_SCROLL_STEP : -GRID_SCROLL_STEP), 0, gridScrollMax());
            return true;
        }
        if (!insideView(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        double next = Mth.clamp(zoom * Math.pow(1.15, scrollY), MIN_ZOOM, MAX_ZOOM);
        // Zoom toward the cursor: the point under it stays put.
        double ratio = next / zoom;
        double centerX = (viewLeft() + viewRight()) / 2.0, centerY = (viewTop() + viewBottom()) / 2.0;
        panX = mouseX - centerX - (mouseX - centerX - panX) * ratio;
        panY = mouseY - centerY - (mouseY - centerY - panY) * ratio;
        zoom = next;
        viewDirty = true;
        return true;
    }
}
