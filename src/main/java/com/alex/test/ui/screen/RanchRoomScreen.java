package com.alex.test.ui.screen;

import com.alex.test.ModItems;
import com.alex.test.Tr;
import com.alex.test.network.MayorBuildingPayloads;
import com.alex.test.ui.RanchRoomMenu;
import com.alex.test.ui.RanchRoomMenu.Beast;
import com.alex.test.ui.RanchRoomMenu.Snapshot;
import com.alex.test.ui.screen.mayor.MayorStatsScreen;
import com.alex.test.village.villagers.butcher.behavior.ButcherActivity;
import com.alex.test.village.villagers.rancher.RanchChecks;
import com.alex.test.village.villagers.rancher.RanchHappenings;
import com.alex.test.village.villagers.rancher.RanchKeepers;
import com.alex.test.village.villagers.rancher.RanchOutside;
import com.alex.test.village.villagers.rancher.RanchRoundUp;
import com.alex.test.village.villagers.rancher.RanchStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Ranch, looked at: a rotatable view of the pen with its fence, gate and pond as they stand,
 * and the animals inside where they are: the game's own live models when loaded (round 9), else small
 * coloured figures (rounds 1-4 of docs/plans/2026-10-24-ranch-window.md). Hovering names each, with
 * its wool, egg or hide, its breeding or growing up, and who leads it; a mark under it shows love or
 * something to collect.
 * The Animals tab counts each kind against the room and says why it is bred or not; the Rancher
 * tab shows each Rancher's job now, switches and pack; the Outside tab lists the pen's strays and
 * the loose animals near it with why each is or is not led in (round 5), and those close to the pen
 * stand in the view with a red or grey mark. The Checks tab (round 6) lists what helps the pen work:
 * its size, gate, grass and light, and where its extra animals go. Under the Rancher cards, the pen's
 * last few happenings (round 7). Opened from the Mayor's Buildings page it has Back to Mayor (round 8).
 * Look only.
 */
public final class RanchRoomScreen extends AbstractContainerScreen<RanchRoomMenu> {
    private static final int PAD = 8, HEADER_H = 28, FOOTER_H = 16, SIDE_W = 204, CARD_H = 40, KIND_H = 25;
    /** Over this many floor tiles the pen is drawn plain, without the world's blocks: recording them is slow. */
    private static final int MAX_REAL_TILES = 1500;
    /** Tiles a pen should have; a smaller closed pen still works (RanchBuilding.REQUIRED_TILES). */
    private static final int RECOMMENDED_TILES = 12;
    /** Animals listed when hovering one tile; more say "and N more". */
    private static final int MAX_HOVER_ANIMALS = 4;
    private static String resetLabel() { return Tr.s("screen.dynamicvillages.storage.reset_view", "Reset view"); }
    private static String mayorLabel() { return Tr.s("screen.dynamicvillages.storage.back_to_mayor", "Back to Mayor"); }

    private static final int WATER = 0xFF3F76E4;
    private static final int TOKEN = 0xFFE6D49A;
    private static final int OTHER = 0xFF8C8C8C;
    private static final int MOOSHROOM = 0xFFA52A1E;
    private static final int NOT_READY = 0xFFC7342B;
    /** Marks under a figure: something to collect now (egg, hide, wool), or in love. */
    private static final int READY = 0xFF80C71F;
    private static final int IN_LOVE = 0xFFF38BAA;
    /** A sheared sheep's bare skin. */
    private static final int SHEARED = 0xFFD9C7B0;
    /** Marks under animals outside the fence: one of this pen's that got out, a loose one of no pen. */
    private static final int STRAY = 0xFFE05A4E;
    private static final int LOOSE = 0xFF9A9A9A;
    /** The Checks tab's squares, as the Butchery window's: fine, to fix, a tip, for your information. */
    private static final int CHECK_OK = StorageUi.ACCENT;
    private static final int CHECK_BAD = NOT_READY;
    private static final int CHECK_TIP = 0xFFE6B84A;
    private static final int CHECK_NONE = 0xFF6E6E6E;
    /** One line of the check list; colour 0 is a heading. */
    private record Check(int color, String text) {}
    /**
     * What the client's world shows at the pen, read once a second: whether it was loaded, the gate
     * is a fence gate and open, stairs or slabs at it, grass tiles and tiles a mob could spawn on.
     */
    private record BlockScan(boolean seen, boolean gate, boolean open, int snags, int grass, int dark) {
        static final BlockScan UNSEEN = new BlockScan(false, false, false, 0, 0, 0);
    }

    /** The four kinds the Rancher keeps, in the order the card lists them, with their figure's colours. */
    private record Kind(EntityType<?> type, Item egg, int body, int head) {}
    private static final List<Kind> KINDS = List.of(
            new Kind(EntityType.COW, Items.COW_SPAWN_EGG, 0xFF4B3621, 0xFF5E4630),
            new Kind(EntityType.SHEEP, Items.SHEEP_SPAWN_EGG, 0xFFEDEDED, 0xFFD9C7B0),
            new Kind(EntityType.PIG, Items.PIG_SPAWN_EGG, 0xFFF0A5A2, 0xFFF0A5A2),
            new Kind(EntityType.CHICKEN, Items.CHICKEN_SPAWN_EGG, 0xFFF5D547, 0xFFF5D547));

    /** A figure's size in blocks, adult: body length and width, leg height, back height, head size. */
    private record Figure(double length, double width, double legs, double top, double head) {}
    private static final Figure COW_FIGURE = new Figure(1.0, 0.62, 0.5, 1.2, 0.45);
    private static final Figure SHEEP_FIGURE = new Figure(0.9, 0.62, 0.45, 1.1, 0.4);
    private static final Figure PIG_FIGURE = new Figure(0.85, 0.6, 0.3, 0.8, 0.45);
    private static final Figure CHICKEN_FIGURE = new Figure(0.4, 0.3, 0.3, 0.6, 0.25);

    private static final float[] HOVER_TINT = {1f, 0.95f, 0.8f};

    private final RoomSchematicView view = new RoomSchematicView(new RoomSchematicView.Styler() {
        @Override public float[] tint(BlockPos target, boolean hovered) {
            return hovered ? HOVER_TINT : RoomSchematicView.NO_TINT;
        }
        @Override public int boxColor(BlockPos target, int base, boolean hovered) {
            return hovered ? RoomSchematicView.lighten(base) : base;
        }
    });

    private int seenVersion;
    /** The blocks the last build queued, so a build where only animals moved keeps the recorded blocks. */
    @Nullable private List<Object> builtBlocks;
    /** Whether the last build drew the pen plain (too big, or the world is not loaded here). */
    private boolean drawnPlain;
    /** Game time a mark under an animal comes or goes (an egg falls due, love ends): build again then. */
    private long nextMarkChange = Long.MAX_VALUE;
    /** A hover area of the side panel as last drawn, with its tooltip. */
    private record Tip(int x, int y, int width, int height, Supplier<List<Component>> lines) {}
    /** Rebuilt each frame while drawing, so what is hovered is what was drawn. */
    private final List<Tip> tips = new ArrayList<>();
    /** The side panel's tab: 0 the animals, 1 the Ranchers, 2 the animals outside, 3 the check list. */
    private int tab;
    private int lastMouseX, lastMouseY;
    private BlockScan scan = BlockScan.UNSEEN;
    private int scanTicks;

    /** An on/off chip in the Rancher tab (look only) and its tooltip. */
    private record Chip(String label, boolean on, List<Component> tip) {}
    /** Chips laid out in rows: each chip's offset, and the height they take. */
    private record Flow(int[] xs, int[] ys, int height) {}
    private static final int CHIP_H = 11, TAB_H = 16, LATELY_H = 11;

    public RanchRoomScreen(RanchRoomMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 600;
        imageHeight = 350;
        titleLabelY = -1000;
        inventoryLabelY = -1000;
        seenVersion = menu.version();
        buildPen();
    }

    @Override protected void init() {
        imageWidth = Math.min(600, width - 16);
        imageHeight = Math.min(350, height - 16);
        super.init();
    }

    // =========================================================================
    // Pen model
    // =========================================================================

    private Snapshot pen() {
        return menu.snapshot();
    }

    /**
     * The pen as the view draws it: the floor's real blocks with the plants and carpets on them, the
     * fence round it standing whole, the gate, the pond, the token's spot and the animals. Where the
     * client has no blocks, or the pen is very big, the same as flat boxes.
     */
    private void buildPen() {
        Snapshot pen = pen();
        ClientLevel world = Minecraft.getInstance().level;
        boolean loaded = world != null && world.isLoaded(menu.gatePos());
        ClientLevel source = loaded && pen.floor().size() <= MAX_REAL_TILES ? world : null;
        drawnPlain = source == null;

        Set<BlockPos> floor = new LinkedHashSet<>(pen.floor());
        Set<BlockPos> water = new LinkedHashSet<>(pen.water());
        Set<BlockPos> penCells = new LinkedHashSet<>(floor);
        penCells.addAll(water);
        BlockPos gate = menu.gatePos();
        Set<BlockPos> gateCells = Set.of(gate, gate.above());
        List<BlockPos> fence = source == null ? List.of() : fenceCells(source, penCells, gateCells, pen.frame());
        List<BlockPos> coverings = source == null ? List.of() : coverings(source, floor);

        // Only animals moved: keep the recorded blocks, as recording them again is slow.
        List<Object> blocks = blockKey(source, pen, gate, fence, coverings);
        if (blocks.equals(builtBlocks)) view.clearKeepingBlocks();
        else view.clear();
        builtBlocks = blocks;

        Set<BlockPos> inside = new HashSet<>(penCells);
        inside.add(gate);
        Set<BlockPos> outline = new HashSet<>(inside);
        outline.addAll(fence);
        view.centerOn(outline);
        // A pen has no walls: with the world the fence is drawn whole below, else low ledges stand in for it.
        view.addShell(source, floor, inside, List.of(gate), gate, null, source == null);

        if (source != null) {
            for (BlockPos tile : coverings) view.addDraw(source, tile, 0, null);
            Set<BlockPos> drawn = new HashSet<>(fence);
            for (BlockPos post : fence) {
                view.addDraw(source, post, 0, null);
                view.addBlockBox(post, 0, 1, null, null);
                BlockPos base = post.below();
                if (!penCells.contains(base) && drawn.add(base) && view.addDraw(source, base, 0, null)) {
                    view.addBlockBox(base, 0, 1, null, null);
                }
            }
        }

        for (BlockPos spot : water) {
            view.addBox(spot.getX(), spot.getY() - 0.18, spot.getZ(), spot.getX() + 1, spot.getY() + 0.8,
                    spot.getZ() + 1, WATER, spot, null, 0, true);
        }
        BlockPos frame = pen.frame();
        view.addBox(frame.getX() + 0.34, frame.getY() + 0.34, frame.getZ() + 0.34, frame.getX() + 0.66,
                frame.getY() + 0.66, frame.getZ() + 0.66, TOKEN, frame, null, 0, true);

        long now = gameTime();
        nextMarkChange = Long.MAX_VALUE;
        for (Beast beast : pen.animals()) {
            addAnimal(beast, now, 0);
            if (beast.loveUntil() > now) nextMarkChange = Math.min(nextMarkChange, beast.loveUntil());
            if (hasProduct(beast) && beast.productAt() > now) nextMarkChange = Math.min(nextMarkChange, beast.productAt());
        }
        addOutsidersNear(outline, floor);
        view.finish();
        scan = scanBlocks();
    }

    /**
     * The animals outside the fence that stand within the circle the view already fits round the pen,
     * with a red or grey mark: drawing one further off would zoom the whole view out. The rest are
     * only in the Outside tab.
     */
    private void addOutsidersNear(Set<BlockPos> outline, Set<BlockPos> floor) {
        if (outline.isEmpty()) return;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : outline) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }
        int lowY = Integer.MAX_VALUE, highY = Integer.MIN_VALUE;
        for (BlockPos tile : floor) {
            lowY = Math.min(lowY, tile.getY());
            highY = Math.max(highY, tile.getY());
        }
        if (floor.isEmpty()) lowY = highY = menu.gatePos().getY();
        double cx = (minX + maxX + 1) / 2.0, cz = (minZ + maxZ + 1) / 2.0;
        double reach = Math.hypot((maxX + 1 - minX) / 2.0, (maxZ + 1 - minZ) / 2.0);
        for (RanchOutside.Outsider out : pen().outside().listed()) {
            if (Math.hypot(out.x() - cx, out.z() - cz) + 0.7 > reach) continue;
            if (out.y() < lowY - 3 || out.y() > highY + 4) continue;
            addAnimal(beastOf(out), 0, out.stray() ? STRAY : LOOSE);
        }
    }

    /** An animal outside, as the figure code draws one (no clocks: its mark is set by the caller). */
    private static Beast beastOf(RanchOutside.Outsider out) {
        return new Beast(out.type(), out.x(), out.y(), out.z(), out.facing(), out.baby(), 0, 0, 0, 0, out.wool(),
                "", "", "", false, out.entityId());
    }

    private static BlockPos tileOf(RanchOutside.Outsider out) {
        return BlockPos.containing(out.x(), out.y() + 0.2, out.z());
    }

    private static long gameTime() {
        ClientLevel world = Minecraft.getInstance().level;
        return world == null ? 0 : world.getGameTime();
    }

    /** A grown chicken (eggs) or cow (hides): the ones whose {@code productAt} is a clock. */
    private static boolean hasProduct(Beast beast) {
        if (beast.baby()) return false;
        EntityType<?> type = typeOf(beast);
        return type == EntityType.CHICKEN || type == EntityType.COW || type == EntityType.MOOSHROOM;
    }

    @Nullable private static EntityType<?> typeOf(Beast beast) {
        return EntityType.byString(beast.type()).orElse(null);
    }

    /** Something to collect from it now: a grown sheep's wool, a due egg or hide. */
    private static boolean readyToCollect(Beast beast, long now) {
        if (beast.baby()) return false;
        if (typeOf(beast) == EntityType.SHEEP) return beast.wool() >= 0;
        return hasProduct(beast) && beast.productAt() <= now;
    }

    /**
     * Everything the build reads from the world, in order: the pen's own lists and the state of every
     * block it queues. Equal keys queue the same blocks the same way.
     */
    private static List<Object> blockKey(@Nullable ClientLevel source, Snapshot pen, BlockPos gate,
                                         List<BlockPos> fence, List<BlockPos> coverings) {
        List<Object> key = new ArrayList<>();
        key.add(source != null);
        key.add(pen.floor());
        key.add(pen.water());
        key.add(fence);
        key.add(coverings);
        if (source == null) return key;
        for (BlockPos tile : pen.floor()) key.add(source.getBlockState(tile.below()));
        for (BlockPos tile : coverings) key.add(source.getBlockState(tile));
        for (BlockPos post : fence) {
            key.add(source.getBlockState(post));
            key.add(source.getBlockState(post.below()));
        }
        for (int dy = -1; dy <= 1; dy++) key.add(source.getBlockState(gate.above(dy)));
        return key;
    }

    /**
     * The fence round the pen: next to each floor or pond cell (corners too), the fence, wall or bars
     * standing there or one block up or down, else the block that closes the pen at that height (a
     * house wall, a hill). The gate and the token's cell are left out.
     */
    private static List<BlockPos> fenceCells(ClientLevel world, Set<BlockPos> pen, Set<BlockPos> gateCells,
                                             BlockPos frame) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (BlockPos cell : pen) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos next = cell.offset(dx, 0, dz);
                    if (RoomSchematicView.isInside(pen, next) || next.equals(frame)) continue;
                    BlockPos post = null;
                    for (int dy : new int[] {0, 1, -1}) {
                        BlockPos at = next.above(dy);
                        if (isFence(world.getBlockState(at))) {
                            post = at;
                            break;
                        }
                    }
                    if (post == null && !world.getBlockState(next).getCollisionShape(world, next).isEmpty()) post = next;
                    if (post == null || gateCells.contains(post)) continue;
                    found.add(post.immutable());
                    BlockPos up = post.above();
                    if (!gateCells.contains(up) && isFence(world.getBlockState(up))) found.add(up.immutable());
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static boolean isFence(BlockState state) {
        return state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof WallBlock || state.getBlock() instanceof IronBarsBlock;
    }

    /** Floor tiles holding grass, flowers, a carpet or snow: low or soft things in the cell animals stand in. */
    private static List<BlockPos> coverings(ClientLevel world, Set<BlockPos> floor) {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos tile : floor) {
            BlockState state = world.getBlockState(tile);
            if (state.isAir() || !state.getFluidState().isEmpty() || isFence(state)) continue;
            VoxelShape shape = state.getCollisionShape(world, tile);
            if (shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.25) found.add(tile);
        }
        return found;
    }

    /**
     * One animal: the game's own model of it, live, when the client has it loaded (round 9); else a small
     * figure: legs, body and a head on the side it looks to. Babies are smaller; a sheep is its wool's
     * colour, bare when sheared. A flat mark under it: {@code markColor} when not 0 (an animal outside),
     * else pink in love, else green when there is something to collect now.
     */
    private void addAnimal(Beast beast, long now, int markColor) {
        EntityType<?> type = typeOf(beast);
        Kind kind = kindOf(type);
        Figure figure = figureOf(type);
        double x = beast.x(), y = beast.y(), z = beast.z();
        BlockPos target = beast.tile();
        int mark = markColor != 0 ? markColor
                : beast.loveUntil() > now ? IN_LOVE : readyToCollect(beast, now) ? READY : 0;
        if (type != null && view.addEntity(beast.entityId(), type, target, mark)) {
            // Round 9: the client's own animal, live. This box only keeps the view's fit (no target: the
            // model is picked where it is now).
            EntityDimensions size = type.getDimensions();
            double half = size.width() / 2 * (beast.baby() ? 0.5 : 1);
            double tall = size.height() * (beast.baby() ? 0.5 : 1);
            view.addBox(x - half, y, z - half, x + half, y + tall, z + half, 0, null, null, 0, false);
            return;
        }
        if (figure == null) {
            // Not one of the Rancher's kinds: a plain grey block its size.
            EntityDimensions size = type != null ? type.getDimensions() : EntityDimensions.scalable(0.6f, 0.8f);
            double half = size.width() * 0.4 * (beast.baby() ? 0.55 : 1);
            double tall = size.height() * 0.85 * (beast.baby() ? 0.55 : 1);
            view.addBox(x - half, y, z - half, x + half, y + tall, z + half, OTHER, target, null, 0, true);
            view.addShadowFootprint(x - half, y, z - half, x + half, z + half);
            return;
        }
        double scale = beast.baby() ? 0.55 : 1;
        double head = figure.head() * (beast.baby() ? 0.75 : 1);
        double length = figure.length() * scale, width = figure.width() * scale;
        double legs = figure.legs() * scale, top = figure.top() * scale;
        Direction facing = Direction.from2DDataValue(beast.facing());
        boolean alongX = facing.getAxis() == Direction.Axis.X;
        double halfX = (alongX ? length : width) / 2, halfZ = (alongX ? width : length) / 2;
        int body = type == EntityType.MOOSHROOM ? MOOSHROOM
                : type == EntityType.SHEEP ? woolColor(beast.wool()) : kind.body();
        if (mark != 0) {
            double edge = 0.12;
            view.addBox(x - halfX - edge, y + 0.01, z - halfZ - edge, x + halfX + edge, y + 0.05, z + halfZ + edge,
                    mark, target, null, 0, true);
        }
        view.addBox(x - halfX * 0.8, y, z - halfZ * 0.8, x + halfX * 0.8, y + legs, z + halfZ * 0.8,
                dim(body), target, null, 0, true);
        view.addBox(x - halfX, y + legs, z - halfZ, x + halfX, y + top, z + halfZ, body, target, null, 0, true);
        double reach = length / 2 + head * 0.2;
        double hx = x + facing.getStepX() * reach, hz = z + facing.getStepZ() * reach;
        view.addBox(hx - head / 2, y + top - head * 0.75, hz - head / 2, hx + head / 2, y + top + head * 0.25,
                hz + head / 2, kind.head(), target, null, 0, true);
        view.addShadowFootprint(x - halfX, y, z - halfZ, x + halfX, z + halfZ);
    }

    /** The card's kind for this animal; a mooshroom is drawn as a cow. Null when the Rancher has no such kind. */
    @Nullable private static Kind kindOf(@Nullable EntityType<?> type) {
        EntityType<?> as = type == EntityType.MOOSHROOM ? EntityType.COW : type;
        for (Kind kind : KINDS) if (kind.type() == as) return kind;
        return null;
    }

    @Nullable private static Figure figureOf(@Nullable EntityType<?> type) {
        if (type == EntityType.COW || type == EntityType.MOOSHROOM) return COW_FIGURE;
        if (type == EntityType.SHEEP) return SHEEP_FIGURE;
        if (type == EntityType.PIG) return PIG_FIGURE;
        if (type == EntityType.CHICKEN) return CHICKEN_FIGURE;
        return null;
    }

    /** A woolly sheep's colour from its dye id; bare skin when sheared (-1). */
    private static int woolColor(int dyeId) {
        if (dyeId < 0) return SHEARED;
        return 0xFF000000 | DyeColor.byId(dyeId).getTextureDiffuseColor();
    }

    /** The legs: the body's colour, darker. */
    private static int dim(int color) {
        int r = (int) (((color >> 16) & 0xFF) * 0.72), g = (int) (((color >> 8) & 0xFF) * 0.72);
        int b = (int) ((color & 0xFF) * 0.72);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // =========================================================================
    // Counting
    // =========================================================================

    /** Adults and babies of one kind in the pen now: {adults, babies}. */
    private int[] herd(EntityType<?> type) {
        String id = EntityType.getKey(type).toString();
        int[] count = new int[2];
        for (Beast beast : pen().animals()) {
            if (beast.type().equals(id)) count[beast.baby() ? 1 : 0]++;
        }
        return count;
    }

    /** Animals in the pen that are none of the four kinds, by name, in the order first seen. */
    private Map<String, Integer> others() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Beast beast : pen().animals()) {
            EntityType<?> type = EntityType.byString(beast.type()).orElse(null);
            if (type != null && type != EntityType.MOOSHROOM && kindOf(type) != null) continue;
            String name = type != null ? type.getDescription().getString() : beast.type();
            counts.merge(name, 1, Integer::sum);
        }
        return counts;
    }

    private static String kindName(EntityType<?> type) {
        if (type == EntityType.COW) return Tr.s("screen.dynamicvillages.ranch.kind.cows", "Cows");
        if (type == EntityType.SHEEP) return Tr.s("screen.dynamicvillages.ranch.kind.sheep", "Sheep");
        if (type == EntityType.PIG) return Tr.s("screen.dynamicvillages.ranch.kind.pigs", "Pigs");
        return Tr.s("screen.dynamicvillages.ranch.kind.chickens", "Chickens");
    }

    private static String herdText(int adults, int babies) {
        if (adults + babies == 0) return Tr.s("screen.dynamicvillages.ranch.herd.none", "None yet");
        String grown = adults == 1 ? Tr.s("screen.dynamicvillages.ranch.herd.adult", "1 adult")
                : Tr.s("screen.dynamicvillages.ranch.herd.adults", "%s adults", adults);
        if (babies == 0) return grown;
        String young = babies == 1 ? Tr.s("screen.dynamicvillages.ranch.herd.baby", "1 baby")
                : Tr.s("screen.dynamicvillages.ranch.herd.babies", "%s babies", babies);
        return adults == 0 ? young : Tr.s("screen.dynamicvillages.ranch.herd.both", "%s, %s", grown, young);
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
    private int resetWidth() { return 26 + font.width(resetLabel()); }
    private int resetX() { return leftPos + imageWidth - PAD - resetWidth(); }
    /** Round 8: opened from the Mayor's Buildings page, so Back to Mayor shows left of Reset view. */
    private boolean fromMayor() { return menu.mayorId() >= 0; }
    private int mayorWidth() { return 26 + font.width(mayorLabel()); }
    private int mayorX() { return resetX() - 6 - mayorWidth(); }
    private int headerY() { return topPos + 4; }
    private int tabY() { return sideTop() + 6 + CARD_H + 6; }
    private int tabWidth() { return (contentWidth() - 6) / 4; }
    private int tabX(int tab) { return contentX() + tab * (tabWidth() + 2); }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        graphics.fill(x - 2, y - 2, x + imageWidth + 2, y + imageHeight + 2, StorageUi.OUTER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, StorageUi.PANEL);
        graphics.renderFakeItem(new ItemStack(ModItems.RANCH.get()), x + PAD, y + 6);
        graphics.drawString(font, title, x + PAD + 20, y + 10, StorageUi.TEXT, false);
        headerButton(graphics, resetX(), resetWidth(), Items.COMPASS, resetLabel(),
                inside(mouseX, mouseY, resetX(), headerY(), resetWidth(), 20));
        if (fromMayor()) {
            headerButton(graphics, mayorX(), mayorWidth(), Items.WRITABLE_BOOK, mayorLabel(),
                    inside(mouseX, mouseY, mayorX(), headerY(), mayorWidth(), 20));
        }

        view.setViewport(viewLeft(), viewTop(), viewRight(), viewBottom());
        view.render(graphics, font);
        renderKey(graphics);

        graphics.fill(sideLeft(), sideTop(), sideLeft() + SIDE_W, sideBottom(), StorageUi.PANEL_DARK);
        renderSidePanel(graphics);
    }

    /** The colour key under the view, with the camera controls when there is room. */
    private void renderKey(GuiGraphics graphics) {
        int x = viewLeft(), y = viewBottom() + 5;
        x = keyEntry(graphics, x, y, READY, Tr.s("screen.dynamicvillages.ranch.key.ready", "Ready to collect"));
        x = keyEntry(graphics, x, y, IN_LOVE, Tr.s("screen.dynamicvillages.ranch.key.in_love", "In love"));
        x = keyEntry(graphics, x, y, STRAY, Tr.s("screen.dynamicvillages.ranch.key.stray", "Got out"));
        x = keyEntry(graphics, x, y, LOOSE, Tr.s("screen.dynamicvillages.ranch.key.loose", "Loose"));
        x = keyEntry(graphics, x, y, WATER, Tr.s("screen.dynamicvillages.ranch.key.water", "Water"));
        x = keyEntry(graphics, x, y, TOKEN, Tr.s("screen.dynamicvillages.ranch.key.token", "Token"));
        String hint = Tr.s("screen.dynamicvillages.storage.view_hint", "Drag: rotate • Right-drag: pan • Scroll: zoom");
        if (x + 8 + font.width(hint) <= viewRight()) {
            graphics.drawString(font, hint, viewRight() - font.width(hint), y, StorageUi.MUTED, false);
        }
    }

    private int keyEntry(GuiGraphics graphics, int x, int y, int color, String label) {
        if (x + 11 + font.width(label) > viewRight()) return x;
        graphics.fill(x, y, x + 8, y + 8, color);
        graphics.drawString(font, label, x + 11, y, StorageUi.MUTED, false);
        return x + 11 + font.width(label) + 10;
    }

    /** Header controls: bordered, icon and label centred. */
    private void headerButton(GuiGraphics graphics, int x, int width, @Nullable Item icon, String label, boolean hover) {
        int y = headerY();
        graphics.fill(x, y, x + width, y + 20, hover ? StorageUi.BUTTON_HOVER_BORDER : StorageUi.PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 19, hover ? StorageUi.ROW_HOVER : StorageUi.ROW);
        int iconWidth = icon == null ? 0 : 20;
        int content = x + (width - iconWidth - font.width(label)) / 2;
        if (icon != null) graphics.renderFakeItem(new ItemStack(icon), content, y + 2);
        graphics.drawString(font, label, content + iconWidth, y + 6, StorageUi.TEXT, false);
    }

    private void renderSidePanel(GuiGraphics graphics) {
        Snapshot pen = pen();
        int x = contentX(), width = contentWidth(), y = sideTop() + 6;
        int tiles = pen.floor().size();
        String detail = pen.valid() ? Tr.s("screen.dynamicvillages.ranch.ready", "Pen ready")
                : Tr.s("screen.dynamicvillages.ranch.unavailable", "Pen unavailable now");
        String extra = Tr.s("screen.dynamicvillages.ranch.size", "%s tiles, room for %s of each kind",
                tiles, pen.capacity());
        StorageUi.row(graphics, x, y, width, CARD_H, false, false);
        graphics.fill(x + 1, y + 1, x + 3, y + CARD_H - 1, pen.valid() ? StorageUi.ACCENT : NOT_READY);
        graphics.renderFakeItem(new ItemStack(Items.WHEAT), x + 8, y + 12);
        graphics.drawString(font, trim(title.getString(), width - 36), x + 30, y + 6, StorageUi.TEXT, false);
        graphics.drawString(font, trim(detail, width - 36), x + 30, y + 17,
                pen.valid() ? StorageUi.MUTED : StorageUi.DENIED, false);
        graphics.drawString(font, trim(extra, width - 36), x + 30, y + 28, StorageUi.MUTED, false);

        y += CARD_H + 8;
        if (!pen.valid()) {
            drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.note.unavailable", "The pen is not recognised right now. Check that the fence is closed all round and the gate shuts, then right-click the token again."),
                    x, y, width, StorageUi.MUTED);
            return;
        }

        String[] labels = {
                Tr.s("screen.dynamicvillages.ranch.tab.animals", "Animals"),
                Tr.s("screen.dynamicvillages.ranch.tab.rancher", "Rancher"),
                Tr.s("screen.dynamicvillages.ranch.tab.outside", "Outside"),
                Tr.s("screen.dynamicvillages.ranch.tab.checks", "Checks")};
        for (int t = 0; t < labels.length; t++) {
            StorageUi.segment(graphics, font, tabX(t), tabY(), tabWidth(), TAB_H, trim(labels[t], tabWidth() - 2),
                    tab == t, tab != t && inside(lastMouseX, lastMouseY, tabX(t), tabY(), tabWidth(), TAB_H));
        }
        // A dot on the Outside tab: red when some of the pen's animals are out, grey for loose ones only.
        List<RanchOutside.Outsider> outside = pen.outside().listed();
        if (!outside.isEmpty()) tabDot(graphics, 2, outside.stream().anyMatch(RanchOutside.Outsider::stray) ? STRAY : LOOSE);
        // And on the Checks tab: red when something must be fixed, gold for a tip.
        List<Check> checks = checks();
        if (checks.stream().anyMatch(check -> check.color() == CHECK_BAD)) tabDot(graphics, 3, CHECK_BAD);
        else if (checks.stream().anyMatch(check -> check.color() == CHECK_TIP)) tabDot(graphics, 3, CHECK_TIP);
        y = tabY() + TAB_H + 6;
        if (tab == 1) {
            renderRanchers(graphics, x, y, width);
            return;
        }
        if (tab == 2) {
            renderOutside(graphics, x, y, width);
            return;
        }
        if (tab == 3) {
            renderChecks(graphics, checks, x, y, width);
            return;
        }

        long now = gameTime();
        for (int k = 0; k < KINDS.size(); k++) {
            Kind kind = KINDS.get(k);
            RanchStatus.KindLine line = lineFor(kind.type());
            List<String> why = line == null ? List.of()
                    : wrap(RanchStatus.text(line, now).getString(), width - 28, 2);
            int rowH = KIND_H + why.size() * 10;
            if (y + rowH > sideBottom() - 4) return;
            int[] herd = herd(kind.type());
            int heads = herd[0] + herd[1];
            boolean full = heads >= pen.capacity();
            boolean hover = inside(lastMouseX, lastMouseY, x, y, width, rowH);
            StorageUi.row(graphics, x, y, width, rowH, false, hover);
            tips.add(new Tip(x, y, width, rowH, () -> kindTooltip(kind)));
            graphics.renderFakeItem(new ItemStack(kind.egg()), x + 4, y + 4);
            String count = Tr.s("screen.dynamicvillages.ranch.count", "%s / %s", heads, pen.capacity());
            int countX = x + width - 5 - font.width(count);
            graphics.drawString(font, count, countX, y + 8, full ? StorageUi.GOLD : StorageUi.TEXT, false);
            // The colour its figures have in the view.
            graphics.fill(countX - 12, y + 9, countX - 5, y + 16, kind.body());
            graphics.drawString(font, trim(kindName(kind.type()), countX - x - 40), x + 24, y + 3, StorageUi.TEXT, false);
            graphics.drawString(font, trim(herdText(herd[0], herd[1]), countX - x - 40), x + 24, y + 13,
                    StorageUi.MUTED, false);
            int toneColor = line == null ? StorageUi.MUTED : toneColor(RanchStatus.tone(line.reason()));
            for (int i = 0; i < why.size(); i++) {
                graphics.drawString(font, why.get(i), x + 24, y + 23 + i * 10, toneColor, false);
            }
            y += rowH + 2;
        }

        String ready = readyNow(now);
        if (!ready.isEmpty()) y = drawNote(graphics, ready, x, y + 2, width, StorageUi.TEXT);
        RanchStatus.Products products = pen.status().products();
        if (products.collectingOff()) {
            y = drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.note.collecting_off", "Collecting is switched off for every Rancher: no wool, eggs or hides are gathered."),
                    x, y + 4, width, StorageUi.GOLD);
        }
        if (products.noShears()) {
            y = drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.note.no_shears", "Woolly sheep are waiting, but no Rancher has shears. A Rancher gets a pair when hired; shears are not fetched from storage."),
                    x, y + 4, width, StorageUi.GOLD);
        }

        Map<String, Integer> others = others();
        if (!others.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : others.entrySet()) {
                parts.add(Tr.s("screen.dynamicvillages.ranch.other_count", "%s (%s)", entry.getKey(), entry.getValue()));
            }
            y = drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.others", "Also here: %s", String.join(", ", parts)),
                    x, y + 2, width, StorageUi.MUTED);
        }
        y = drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.note.room", "Each animal needs 8 tiles of pen, and every pen holds at least 2 of each kind. Hover a kind for its food."),
                x, y + 4, width, StorageUi.MUTED);
        if (drawnPlain) {
            drawNote(graphics, tiles > MAX_REAL_TILES
                            ? Tr.s("screen.dynamicvillages.ranch.note.plain_big", "Big pen: drawn plain to keep the game smooth.")
                            : Tr.s("screen.dynamicvillages.ranch.note.plain_far", "Too far to see the blocks: drawn plain."),
                    x, y + 4, width, StorageUi.MUTED);
        }
    }

    @Nullable private RanchStatus.KindLine lineFor(EntityType<?> kind) {
        for (RanchStatus.KindLine line : pen().status().kinds()) if (line.kind() == kind) return line;
        return null;
    }

    private static int toneColor(RanchStatus.Tone tone) {
        return switch (tone) {
            case GOOD -> StorageUi.ACCENT;
            case WAIT -> StorageUi.GOLD;
            case BLOCKED -> StorageUi.DENIED;
        };
    }

    // =========================================================================
    // Rancher tab (round 4)
    // =========================================================================

    /**
     * One card per Rancher near a player (a note when there is none), then the pen's last few
     * happenings; room is kept for those, so many cards never push them off.
     */
    private void renderRanchers(GuiGraphics graphics, int x, int y, int width) {
        List<RanchKeepers.Keeper> keepers = pen().keepers();
        if (keepers.isEmpty()) {
            y = drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.keeper.none", "No Rancher works here yet. Give a Rancher Token to a villager without a job."),
                    x, y, width, StorageUi.GOLD);
            y = drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.keeper.far", "A Rancher far from every player is not listed."),
                    x, y + 4, width, StorageUi.MUTED);
            renderLately(graphics, x, y + 6, width);
            return;
        }
        int bottom = sideBottom() - 4 - latelyHeight();
        for (int i = 0; i < keepers.size(); i++) {
            RanchKeepers.Keeper keeper = keepers.get(i);
            List<Chip> switches = switches(keeper);
            List<Chip> kinds = kindChips(keeper);
            Flow switchFlow = flow(switches, width - 8), kindFlow = flow(kinds, width - 8);
            int height = 26 + switchFlow.height() + 3 + kindFlow.height() + 3 + 18;
            if (y + height > bottom) {
                y = drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.keeper.more", "%s more not shown: a smaller GUI scale fits them", keepers.size() - i),
                        x, y, width, StorageUi.MUTED);
                renderLately(graphics, x, y + 4, width);
                return;
            }
            StorageUi.row(graphics, x, y, width, height, false, false);
            graphics.renderFakeItem(new ItemStack(ModItems.RANCHER_TOKEN.get()), x + 4, y + 4);
            String doing = RanchKeepers.text(keeper).getString();
            graphics.drawString(font, trim(keeper.name(), width - 28), x + 24, y + 4, StorageUi.TEXT, false);
            graphics.drawString(font, trim(doing, width - 28), x + 24, y + 14,
                    RanchKeepers.working(keeper.doing()) ? StorageUi.ACCENT : StorageUi.MUTED, false);
            tips.add(new Tip(x, y, width, 24, () -> List.of(Component.literal(keeper.name()),
                    Component.literal(doing).withStyle(ChatFormatting.GRAY))));
            int chipsY = y + 26;
            drawChips(graphics, switches, switchFlow, x + 4, chipsY);
            chipsY += switchFlow.height() + 3;
            drawChips(graphics, kinds, kindFlow, x + 4, chipsY);
            renderPack(graphics, keeper, x + 4, chipsY + kindFlow.height() + 3);
            y += height + 4;
        }
        y = renderLately(graphics, x, y, width);
        drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.keeper.note", "Every Rancher looks after every pen of the village. Hover a chip to see what it does."),
                x, y + 4, width, StorageUi.MUTED);
    }

    // =========================================================================
    // Lately (round 7)
    // =========================================================================

    /** The height {@link #renderLately} takes: a heading and one row per line, nothing when there is none. */
    private int latelyHeight() {
        int lines = pen().recent().size();
        return lines == 0 ? 0 : font.lineHeight + 4 + lines * LATELY_H;
    }

    /**
     * The pen's last few happenings, newest first: one row each, how long ago on the right, gold for a
     * walk given up; hover for the whole line. Returns the y below it.
     */
    private int renderLately(GuiGraphics graphics, int x, int y, int width) {
        List<RanchHappenings.Happening> recent = pen().recent();
        if (recent.isEmpty() || y + font.lineHeight > sideBottom() - 4) return y;
        graphics.drawString(font, Tr.s("screen.dynamicvillages.ranch.happen.header", "Lately at this pen"), x, y, StorageUi.TEXT, false);
        y += font.lineHeight + 4;
        long now = gameTime();
        for (RanchHappenings.Happening line : recent) {
            if (y + LATELY_H > sideBottom() - 4) break;
            String text = RanchHappenings.text(line).getString();
            String ago = RanchHappenings.ago(now - line.at()).getString();
            int agoX = x + width - font.width(ago);
            boolean trouble = RanchHappenings.isTrouble(line);
            graphics.fill(x, y + 2, x + 4, y + 6, trouble ? CHECK_TIP : CHECK_NONE);
            graphics.drawString(font, trim(text, agoX - x - 12), x + 8, y, trouble ? StorageUi.GOLD : StorageUi.TEXT, false);
            graphics.drawString(font, ago, agoX, y, StorageUi.MUTED, false);
            tips.add(new Tip(x, y - 1, width, LATELY_H, () -> List.of(Component.literal(text),
                    Component.literal(ago).withStyle(ChatFormatting.GRAY))));
            y += LATELY_H;
        }
        return y;
    }

    /** Its switches as chips: Working, Collecting, Breeding, Round-up and its range. */
    private static List<Chip> switches(RanchKeepers.Keeper keeper) {
        String change = Tr.s("screen.dynamicvillages.ranch.keeper.change", "Change it in the Rancher's Settings.");
        return List.of(
                chip(Tr.s("screen.dynamicvillages.ranch.chip.work", "Working"), keeper.work(), keeper.work()
                        ? Tr.s("screen.dynamicvillages.ranch.chip.work.on", "Working is on: it looks after the ranch animals.")
                        : Tr.s("screen.dynamicvillages.ranch.chip.work.off", "Working is off: it does no ranch work at all."), change),
                chip(Tr.s("screen.dynamicvillages.ranch.chip.collect", "Collecting"), keeper.collecting(), keeper.collecting()
                        ? Tr.s("screen.dynamicvillages.ranch.chip.collect.on", "Collecting Goods is on: it shears sheep, gathers eggs, combs leather off cows and milks them.")
                        : Tr.s("screen.dynamicvillages.ranch.chip.collect.off", "Collecting Goods is off: no wool, eggs, leather or milk."), change),
                chip(Tr.s("screen.dynamicvillages.ranch.chip.breed", "Breeding"), keeper.breeding(), keeper.breeding()
                        ? Tr.s("screen.dynamicvillages.ranch.chip.breed.on", "Breeding is on: it feeds pairs so they have babies.")
                        : Tr.s("screen.dynamicvillages.ranch.chip.breed.off", "Breeding is off: the herd stops growing."), change),
                chip(Tr.s("screen.dynamicvillages.ranch.chip.round_up", "Round up %s", keeper.range()), keeper.roundUp(), keeper.roundUp()
                        ? Tr.s("screen.dynamicvillages.ranch.chip.round_up.on", "Rounding Up is on: it leads loose animals within %s blocks of the gate into the pen while there is room.", keeper.range())
                        : Tr.s("screen.dynamicvillages.ranch.chip.round_up.off", "Rounding Up is off: it only brings back animals that got out of a pen."), change));
    }

    /** One chip per kind: ticked in its Managed animals or not. */
    private static List<Chip> kindChips(RanchKeepers.Keeper keeper) {
        String change = Tr.s("screen.dynamicvillages.ranch.keeper.change", "Change it in the Rancher's Settings.");
        List<Chip> chips = new ArrayList<>();
        for (int i = 0; i < RanchStatus.KINDS.size(); i++) {
            String name = kindName(RanchStatus.KINDS.get(i));
            boolean on = keeper.manages(i);
            chips.add(chip(name, on, on
                    ? Tr.s("screen.dynamicvillages.ranch.chip.kind.on", "Ticked in Managed animals: it breeds them, collects from them and brings them back.")
                    : Tr.s("screen.dynamicvillages.ranch.chip.kind.off", "Unticked in Managed animals: it leaves them alone."), change));
        }
        return chips;
    }

    private static Chip chip(String label, boolean on, String what, String change) {
        return new Chip(label, on, List.of(Component.literal(label),
                Component.literal(what).withStyle(ChatFormatting.GRAY),
                Component.literal(change).withStyle(ChatFormatting.DARK_GRAY)));
    }

    private int chipWidth(Chip chip) {
        return font.width(chip.label()) + 8;
    }

    /** Left to right, a new row when the next chip does not fit in {@code width}. */
    private Flow flow(List<Chip> chips, int width) {
        int[] xs = new int[chips.size()], ys = new int[chips.size()];
        int cx = 0, cy = 0;
        for (int i = 0; i < chips.size(); i++) {
            int w = chipWidth(chips.get(i));
            if (cx > 0 && cx + w > width) {
                cx = 0;
                cy += CHIP_H + 2;
            }
            xs[i] = cx;
            ys[i] = cy;
            cx += w + 3;
        }
        return new Flow(xs, ys, chips.isEmpty() ? 0 : cy + CHIP_H);
    }

    private void drawChips(GuiGraphics graphics, List<Chip> chips, Flow flow, int x, int y) {
        for (int i = 0; i < chips.size(); i++) {
            Chip chip = chips.get(i);
            int cx = x + flow.xs()[i], cy = y + flow.ys()[i], w = chipWidth(chip);
            StorageUi.toggle(graphics, font, cx, cy, w, CHIP_H, chip.label(), chip.on(),
                    inside(lastMouseX, lastMouseY, cx, cy, w, CHIP_H));
            tips.add(new Tip(cx, cy, w, CHIP_H, chip::tip));
        }
    }

    /** Shears, lead and empty buckets in its pack, each an icon and a word. */
    private void renderPack(GuiGraphics graphics, RanchKeepers.Keeper keeper, int x, int y) {
        x = packItem(graphics, x, y, Items.SHEARS,
                keeper.shears() ? Tr.s("screen.dynamicvillages.ranch.pack.yes", "Yes") : Tr.s("screen.dynamicvillages.ranch.pack.none", "None"),
                keeper.shears() ? StorageUi.ACCENT : StorageUi.DENIED, keeper.shears()
                        ? Tr.lines("screen.dynamicvillages.ranch.pack.shears", "Shears\nIt has a pair, so it shears woolly sheep.")
                        : Tr.lines("screen.dynamicvillages.ranch.pack.no_shears", "No shears\nIt cannot shear sheep.\nA Rancher gets a pair when hired; shears are not fetched from storage."));
        String leadWord = switch (keeper.lead()) {
            case CARRIED -> Tr.s("screen.dynamicvillages.ranch.pack.yes", "Yes");
            case IN_STORAGE -> Tr.s("screen.dynamicvillages.ranch.pack.storage", "Storage");
            case NONE -> Tr.s("screen.dynamicvillages.ranch.pack.none", "None");
        };
        int leadColor = switch (keeper.lead()) {
            case CARRIED -> StorageUi.ACCENT;
            case IN_STORAGE -> StorageUi.GOLD;
            case NONE -> StorageUi.DENIED;
        };
        List<Component> leadTip = switch (keeper.lead()) {
            case CARRIED -> Tr.lines("screen.dynamicvillages.ranch.pack.lead", "Lead\nIt carries one to lead animals into the pen.");
            case IN_STORAGE -> Tr.lines("screen.dynamicvillages.ranch.pack.lead_storage", "No lead carried\nStorage has one: it fetches it when an animal needs leading.");
            case NONE -> Tr.lines("screen.dynamicvillages.ranch.pack.no_lead", "No lead\nNone in storage either, so it cannot lead animals.\nPut a lead in a storage chest.");
        };
        x = packItem(graphics, x, y, Items.LEAD, leadWord, leadColor, leadTip);
        packItem(graphics, x, y, Items.BUCKET, String.valueOf(keeper.buckets()),
                keeper.buckets() > 0 ? StorageUi.TEXT : StorageUi.MUTED,
                Tr.lines("screen.dynamicvillages.ranch.pack.buckets", "Empty buckets: %s\nIt milks a cow with each one.\nIt takes buckets from storage only when the Cook wants milk for a cake.", keeper.buckets()));
    }

    /** An item icon with its word after it; returns where the next one starts. */
    private int packItem(GuiGraphics graphics, int x, int y, Item icon, String word, int color, List<Component> tip) {
        graphics.renderFakeItem(new ItemStack(icon), x, y);
        graphics.drawString(font, word, x + 18, y + 4, color, false);
        int width = 18 + font.width(word);
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < tip.size(); i++) {
            lines.add(i == 0 ? tip.get(i) : tip.get(i).copy().withStyle(ChatFormatting.GRAY));
        }
        tips.add(new Tip(x, y, width, 16, () -> lines));
        return x + width + 8;
    }

    // =========================================================================
    // Outside tab (round 5)
    // =========================================================================

    /** One row per animal outside, this pen's own first, each with why it is or is not led in. */
    private void renderOutside(GuiGraphics graphics, int x, int y, int width) {
        RanchOutside.Result outside = pen().outside();
        List<RanchOutside.Outsider> list = outside.listed();
        if (list.isEmpty()) {
            drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.outside.none", "None of this pen's animals are out, and no loose cows, sheep, pigs or chickens are within %s blocks of the gate.", RanchRoundUp.MAX_RANGE),
                    x, y, width, StorageUi.MUTED);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            RanchOutside.Outsider out = list.get(i);
            RanchRoundUp.Why why = out.why();
            List<String> lines = wrap(RanchRoundUp.text(why).getString(), width - 28, 2);
            int rowH = 14 + lines.size() * 10 + 2;
            if (y + rowH > sideBottom() - 4) {
                drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.outside.more", "%s more not shown", list.size() - i + outside.more()),
                        x, y, width, StorageUi.MUTED);
                return;
            }
            StorageUi.row(graphics, x, y, width, rowH, false, inside(lastMouseX, lastMouseY, x, y, width, rowH));
            tips.add(new Tip(x, y, width, rowH, () -> outsiderTooltip(out)));
            EntityType<?> type = EntityType.byString(out.type()).orElse(null);
            Kind kind = kindOf(type);
            if (kind != null) graphics.renderFakeItem(new ItemStack(kind.egg()), x + 4, y + (rowH - 16) / 2);
            String far = Tr.s("screen.dynamicvillages.ranch.outside.blocks", "%s blocks", out.distance());
            int farX = x + width - 5 - font.width(far);
            graphics.drawString(font, far, farX, y + 3, StorageUi.MUTED, false);
            graphics.fill(farX - 12, y + 3, farX - 5, y + 10, out.stray() ? STRAY : LOOSE);
            graphics.drawString(font, trim(outsiderTitle(out, type), farX - x - 40), x + 24, y + 3, StorageUi.TEXT, false);
            int color = toneColor(RanchRoundUp.tone(why.reason()));
            for (int l = 0; l < lines.size(); l++) {
                graphics.drawString(font, lines.get(l), x + 24, y + 13 + l * 10, color, false);
            }
            y += rowH + 2;
        }
        if (outside.more() > 0) {
            y = drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.outside.more", "%s more not shown", outside.more()),
                    x, y, width, StorageUi.MUTED);
        }
        drawNote(graphics, Tr.s("screen.dynamicvillages.ranch.outside.note", "Red: this pen's own animals that got out. Grey: loose ones of no pen, led in while the pen has room."),
                x, y + 2, width, StorageUi.MUTED);
    }

    /** The hover lines of the animals outside standing on {@code tile} (at most a few). */
    private List<Component> outsidersOn(BlockPos tile) {
        List<Component> lines = new ArrayList<>();
        int shown = 0;
        for (RanchOutside.Outsider out : pen().outside().listed()) {
            if (!tileOf(out).equals(tile) || shown++ >= MAX_HOVER_ANIMALS) continue;
            lines.addAll(outsiderTooltip(out));
        }
        return lines;
    }

    /** "Cow, got out", "Sheep, loose, baby". */
    private static String outsiderTitle(RanchOutside.Outsider out, @Nullable EntityType<?> type) {
        String name = type != null ? type.getDescription().getString() : out.type();
        String what = out.stray() ? Tr.s("screen.dynamicvillages.ranch.outside.stray", "got out")
                : Tr.s("screen.dynamicvillages.ranch.outside.loose", "loose");
        String title = Tr.s("screen.dynamicvillages.ranch.herd.both", "%s, %s", name, what);
        return out.baby() ? Tr.s("screen.dynamicvillages.ranch.herd.both", "%s, %s", title,
                Tr.s("screen.dynamicvillages.ranch.tip.baby", "baby")) : title;
    }

    /** An animal outside, hovered in the list or the view: why, who leads it, where it stands. */
    private List<Component> outsiderTooltip(RanchOutside.Outsider out) {
        EntityType<?> type = EntityType.byString(out.type()).orElse(null);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(outsiderTitle(out, type)));
        lines.add(RanchRoundUp.text(out.why()).withColor(toneColor(RanchRoundUp.tone(out.why().reason()))));
        if (!out.leader().isEmpty()) {
            lines.add(Tr.c("screen.dynamicvillages.ranch.tip.led", "Being led by %s", who(out.leader())).withStyle(ChatFormatting.GRAY));
        }
        lines.add((out.stray()
                ? Tr.c("screen.dynamicvillages.ranch.outside.stray_desc", "One of this pen's animals: a Rancher, or an idle Butcher, leads it back in")
                : Tr.c("screen.dynamicvillages.ranch.outside.loose_desc", "Belongs to no pen: with Rounding Up on, a Rancher leads it in while there is room"))
                .withStyle(ChatFormatting.GRAY));
        if (out.letGo()) {
            lines.add(Tr.c("screen.dynamicvillages.ranch.outside.let_go", "A villager gave up leading it and let it go here").withStyle(ChatFormatting.GRAY));
        }
        lines.add(Tr.c("screen.dynamicvillages.ranch.outside.where", "%s blocks from the gate, at %s %s %s", out.distance(),
                (int) Math.floor(out.x()), (int) Math.floor(out.y()), (int) Math.floor(out.z())).withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    // =========================================================================
    // Checks tab (round 6)
    // =========================================================================

    /** A small dot in the top right corner of a tab. */
    private void tabDot(GuiGraphics graphics, int tab, int color) {
        int dotX = tabX(tab) + tabWidth() - 6;
        graphics.fill(dotX, tabY() + 2, dotX + 4, tabY() + 6, color);
    }

    /**
     * The check list: the pen's size, gate and token, half steps at the gate, grass for the sheep and
     * dark tiles (from the client's world), then where its extra animals go and what the Butcher keeps.
     */
    private List<Check> checks() {
        Snapshot pen = pen();
        List<Check> out = new ArrayList<>();
        out.add(new Check(0, Tr.s("screen.dynamicvillages.ranch.check.pen", "The pen")));
        int tiles = pen.floor().size();
        out.add(tiles >= RECOMMENDED_TILES
                ? new Check(CHECK_OK, Tr.s("screen.dynamicvillages.ranch.check.size", "%s tiles: big enough", tiles))
                : new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.ranch.note.small", "Small pen: it still holds 2 of each kind, but %s tiles or more is recommended.", RECOMMENDED_TILES)));
        if (!scan.seen()) {
            out.add(new Check(CHECK_NONE, Tr.s("screen.dynamicvillages.ranch.check.far", "Too far away to check the gate, grass and light. Come closer.")));
        } else {
            if (!scan.gate()) {
                out.add(new Check(CHECK_BAD, Tr.s("screen.dynamicvillages.ranch.check.no_gate", "No fence gate where the gate was. Put one back, or the pen stops working.")));
            } else if (scan.open()) {
                out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.ranch.check.gate_open", "The gate is open right now: animals can walk out.")));
            } else {
                out.add(new Check(CHECK_OK, Tr.s("screen.dynamicvillages.ranch.check.gate", "Fence gate, shut, with the token beside it")));
            }
            if (scan.gate()) {
                out.add(scan.snags() == 0
                        ? new Check(CHECK_OK, Tr.s("screen.dynamicvillages.ranch.check.flat", "Flat ground in and out of the gate"))
                        : new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.ranch.check.snags", "Stairs or slabs at the gate: animals on a lead can snag on half steps.")));
            }
            int[] sheep = herd(EntityType.SHEEP);
            if (sheep[0] + sheep[1] > 0) {
                out.add(scan.grass() == 0
                        ? new Check(CHECK_BAD, Tr.s("screen.dynamicvillages.ranch.check.no_grass", "No grass in the pen: sheared sheep only grow their wool back by eating grass."))
                        : new Check(CHECK_OK, Tr.s("screen.dynamicvillages.ranch.check.grass", "%s grass tiles for the sheep to eat", scan.grass())));
            } else if (scan.grass() == 0) {
                out.add(new Check(CHECK_NONE, Tr.s("screen.dynamicvillages.ranch.check.grass_later", "No grass: sheep kept here would need some to grow their wool back.")));
            }
            if (scan.dark() == 0) {
                out.add(new Check(CHECK_OK, Tr.s("screen.dynamicvillages.ranch.check.lit", "Lit up: no mobs spawn inside")));
            } else {
                out.add(new Check(CHECK_TIP, scan.dark() == 1
                        ? Tr.s("screen.dynamicvillages.ranch.check.dark_one", "1 tile has no torch light: mobs can spawn there at night.")
                        : Tr.s("screen.dynamicvillages.ranch.check.dark", "%s tiles have no torch light: mobs can spawn there at night.", scan.dark())));
            }
        }

        out.add(new Check(0, Tr.s("screen.dynamicvillages.ranch.check.extra", "Extra animals")));
        RanchChecks.Report server = pen.checks();
        int most = (int) ButcherActivity.MAX_BUTCHERY_DIST;
        switch (server.link()) {
            case NO_BUTCHERY -> out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.ranch.check.no_butchery", "No Butchery in the village: extra animals are never culled, so the pen stays full.")));
            case NO_CHOPPING_BLOCK -> out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.ranch.check.no_chopping", "No Butchery is ready: it needs a chopping block with free floor beside it. Until then extra animals are never culled.")));
            case TOO_FAR -> out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.ranch.check.butchery_far", "The nearest Butchery is %s blocks from the gate, more than %s: extra animals are never culled.", server.distance(), most)));
            case OK -> {
                out.add(new Check(CHECK_OK, Tr.s("screen.dynamicvillages.ranch.check.butchery", "Extra animals go to the Butchery %s blocks from the gate", server.distance())));
                if (server.paused()) {
                    out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.ranch.check.paused", "That Butchery is paused until tomorrow morning: animals were given up there. Its window says why.")));
                }
            }
        }
        if (server.link() != RanchChecks.Link.OK) return out;
        switch (server.butchers()) {
            case NONE -> out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.ranch.check.no_butcher", "No Butcher nearby, so none are culled now. A Butcher far from every player is not listed.")));
            case ALL_OFF -> out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.ranch.check.butcher_off", "The Butcher's Work switch is off: extra animals are not culled.")));
            case WORKING -> {
                for (int i = 0; i < RanchStatus.KINDS.size() && i < server.keep().size(); i++) {
                    out.add(keepLine(RanchStatus.KINDS.get(i), server.keep().get(i), pen.capacity()));
                }
            }
        }
        return out;
    }

    /** "Cows: the Butcher keeps 2 adults and takes the rest", or why none of that kind are ever taken. */
    private static Check keepLine(EntityType<?> kind, int keep, int capacity) {
        String name = kindName(kind);
        if (keep < 0) {
            return new Check(CHECK_NONE, Tr.s("screen.dynamicvillages.ranch.check.keep_never", "%s: never culled, not on the Butcher's Livestock to cull list", name));
        }
        if (keep >= capacity) {
            return new Check(CHECK_NONE, Tr.s("screen.dynamicvillages.ranch.check.keep_all", "%s: the Butcher keeps %s and the pen has room for %s, so none are ever extra", name, keep, capacity));
        }
        return new Check(CHECK_OK, Tr.s("screen.dynamicvillages.ranch.check.keep", "%s: the Butcher keeps %s adults and takes the rest", name, keep));
    }

    /**
     * Reads the pen's blocks from the client's world: the gate and whether it is open, stairs or
     * slabs two tiles either side of it along the way through, grass (a grass block under a tile or
     * short grass on it, what sheep eat) and tiles with no torch light a mob could stand on.
     */
    private BlockScan scanBlocks() {
        ClientLevel world = Minecraft.getInstance().level;
        BlockPos gate = menu.gatePos();
        if (world == null || !world.isLoaded(gate)) return BlockScan.UNSEEN;
        BlockState gateState = world.getBlockState(gate);
        boolean isGate = gateState.getBlock() instanceof FenceGateBlock;
        boolean open = isGate && gateState.getValue(FenceGateBlock.OPEN);
        int snags = 0;
        if (isGate) {
            Direction through = gateState.getValue(FenceGateBlock.FACING);
            for (int step = -2; step <= 2; step++) {
                BlockPos tile = gate.relative(through, step);
                if (world.isLoaded(tile) && isSnag(world, tile)) snags++;
            }
        }
        int grass = 0, dark = 0;
        for (BlockPos tile : pen().floor()) {
            if (!world.isLoaded(tile)) continue;
            BlockState cell = world.getBlockState(tile);
            BlockPos below = tile.below();
            BlockState ground = world.getBlockState(below);
            if (ground.is(Blocks.GRASS_BLOCK) || cell.is(Blocks.SHORT_GRASS)) grass++;
            if (world.getBrightness(LightLayer.BLOCK, tile) == 0 && cell.getCollisionShape(world, tile).isEmpty()
                    && cell.getFluidState().isEmpty() && ground.isFaceSturdy(world, below, Direction.UP)) {
                dark++;
            }
        }
        return new BlockScan(true, isGate, open, snags, grass, dark);
    }

    /** A stair or a bottom slab in the tile or under it: led animals snag on half steps (as the Butchery window). */
    private static boolean isSnag(ClientLevel world, BlockPos tile) {
        return halfStep(world.getBlockState(tile)) || halfStep(world.getBlockState(tile.below()));
    }

    private static boolean halfStep(BlockState state) {
        return state.getBlock() instanceof StairBlock
                || state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /** The check list: a coloured square, then the line (green fine, red to fix, gold a tip, grey to know). */
    private void renderChecks(GuiGraphics graphics, List<Check> checks, int x, int y, int width) {
        for (Check check : checks) {
            if (y + font.lineHeight > sideBottom() - 4) break;
            if (check.color() == 0) {
                graphics.drawString(font, trim(check.text(), width), x, y + 2, StorageUi.TEXT, false);
                y += font.lineHeight + 5;
                continue;
            }
            graphics.fill(x, y + 1, x + 6, y + 7, check.color());
            int text = check.color() == CHECK_BAD ? StorageUi.DENIED : check.color() == CHECK_TIP ? StorageUi.GOLD
                    : check.color() == CHECK_NONE ? StorageUi.MUTED : StorageUi.TEXT;
            y = drawNote(graphics, check.text(), x + 10, y, width - 10, text) + 3;
        }
    }

    /** "Ready to collect: 3 eggs, 2 sheep to shear, 1 hide", or empty when nothing is. */
    private String readyNow(long now) {
        int eggs = 0, sheep = 0, hides = 0;
        for (Beast beast : pen().animals()) {
            if (!readyToCollect(beast, now)) continue;
            EntityType<?> type = typeOf(beast);
            if (type == EntityType.CHICKEN) eggs++;
            else if (type == EntityType.SHEEP) sheep++;
            else hides++;
        }
        List<String> parts = new ArrayList<>();
        if (eggs > 0) parts.add(eggs == 1 ? Tr.s("screen.dynamicvillages.ranch.ready.egg", "1 egg")
                : Tr.s("screen.dynamicvillages.ranch.ready.eggs", "%s eggs", eggs));
        if (sheep > 0) parts.add(sheep == 1 ? Tr.s("screen.dynamicvillages.ranch.ready.sheep_one", "1 sheep to shear")
                : Tr.s("screen.dynamicvillages.ranch.ready.sheep", "%s sheep to shear", sheep));
        if (hides > 0) parts.add(hides == 1 ? Tr.s("screen.dynamicvillages.ranch.ready.hide", "1 hide")
                : Tr.s("screen.dynamicvillages.ranch.ready.hides", "%s hides", hides));
        return parts.isEmpty() ? "" : Tr.s("screen.dynamicvillages.ranch.ready.now", "Ready to collect: %s", String.join(", ", parts));
    }

    /** {@code text} broken at spaces into at most {@code maxLines} lines; the last one is cut to fit. */
    private List<String> wrap(String text, int width, int maxLines) {
        List<String> out = new ArrayList<>();
        String rest = text;
        while (!rest.isEmpty() && out.size() < maxLines) {
            if (out.size() == maxLines - 1 || font.width(rest) <= width) {
                out.add(trim(rest, width));
                break;
            }
            String cut = font.plainSubstrByWidth(rest, width);
            int space = cut.lastIndexOf(' ');
            if (space > 0) cut = cut.substring(0, space);
            out.add(cut);
            rest = rest.substring(cut.length()).stripLeading();
        }
        return out;
    }

    /** A kind's row hovered: its reason in full, its room, its food in storage and in the Rancher's pack. */
    private List<Component> kindTooltip(Kind kind) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(kindName(kind.type())));
        RanchStatus.KindLine line = lineFor(kind.type());
        if (line != null) {
            lines.add(RanchStatus.text(line, gameTime()).withColor(toneColor(RanchStatus.tone(line.reason()))));
        }
        int[] herd = herd(kind.type());
        lines.add(Tr.c("screen.dynamicvillages.ranch.tip.kind.room", "Room: %s of %s", herd[0] + herd[1],
                pen().capacity()).withStyle(ChatFormatting.GRAY));
        if (line == null) return lines;
        List<String> stocked = new ArrayList<>();
        for (RanchStatus.Stock stock : line.stock()) {
            if (stock.stored() > 0) stocked.add(Tr.s("screen.dynamicvillages.ranch.other_count", "%s (%s)",
                    stock.item().getDescription().getString(), stock.stored()));
        }
        lines.add((stocked.isEmpty()
                ? Tr.c("screen.dynamicvillages.ranch.tip.kind.stored_none", "None of its food in storage")
                : Tr.c("screen.dynamicvillages.ranch.tip.kind.stored", "In storage: %s", String.join(", ", stocked)))
                .withStyle(ChatFormatting.GRAY));
        if (line.perPair() > 0) {
            lines.add(Tr.c("screen.dynamicvillages.ranch.tip.kind.pack", "The Rancher carries %s, a pair eats %s",
                    line.carried(), line.perPair()).withStyle(ChatFormatting.GRAY));
        }
        if (kind.type() == EntityType.PIG) {
            lines.add(Tr.c("screen.dynamicvillages.ranch.tip.kind.kept", "The Rancher keeps up to 8 of each for the pigs, not for its lunch")
                    .withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    /** Wrapped text from {@code y}; returns the line below it. */
    private int drawNote(GuiGraphics graphics, String note, int x, int y, int width, int color) {
        for (FormattedCharSequence line : font.split(Component.literal(note), width)) {
            if (y + font.lineHeight > sideBottom() - 4) break;
            graphics.drawString(font, line, x, y, color, false);
            y += font.lineHeight + 1;
        }
        return y;
    }

    private String trim(String value, int width) {
        return StorageUi.trim(font, value, width);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (menu.version() != seenVersion || gameTime() >= nextMarkChange) {
            seenVersion = menu.version();
            buildPen();
        }
        // super.render draws the background, and with it the view, once.
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        tips.clear();
        view.setViewport(viewLeft(), viewTop(), viewRight(), viewBottom());
        view.setHovered(view.isDragging() ? null : view.pick(mouseX, mouseY));
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);

        // The last drawn first: a chip lies over its Rancher's card.
        for (int i = tips.size() - 1; i >= 0; i--) {
            Tip tip = tips.get(i);
            if (inside(mouseX, mouseY, tip.x(), tip.y(), tip.width(), tip.height())) {
                graphics.renderComponentTooltip(font, tip.lines().get(), mouseX, mouseY);
                return;
            }
        }
        BlockPos hovered = view.hovered();
        if (hovered == null) return;
        List<Component> lines = tooltipFor(hovered);
        if (!lines.isEmpty()) graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    /** The animals on the hovered tile, else what the gate, pond or token spot is. */
    private List<Component> tooltipFor(BlockPos hovered) {
        Snapshot pen = pen();
        List<Component> lines = new ArrayList<>();
        List<Beast> here = new ArrayList<>();
        for (Beast beast : pen.animals()) if (beast.tile().equals(hovered)) here.add(beast);
        if (!here.isEmpty()) {
            Set<String> notes = new LinkedHashSet<>();
            long now = gameTime();
            for (int i = 0; i < here.size() && i < MAX_HOVER_ANIMALS; i++) {
                Beast beast = here.get(i);
                EntityType<?> type = typeOf(beast);
                MutableComponent name = type != null ? type.getDescription().copy() : Component.literal(beast.type());
                lines.add(name.append(Component.literal(", ").append(beast.baby()
                        ? Tr.c("screen.dynamicvillages.ranch.tip.baby", "baby")
                        : Tr.c("screen.dynamicvillages.ranch.tip.adult", "adult")).withStyle(ChatFormatting.GRAY)));
                for (String detail : details(beast, type, now)) {
                    lines.add(Component.literal(detail).withStyle(ChatFormatting.GRAY));
                }
                if (type == EntityType.MOOSHROOM) {
                    notes.add(Tr.s("screen.dynamicvillages.ranch.tip.mooshroom", "The Rancher combs its hide and milks it, but never breeds it"));
                } else if (kindOf(type) == null) {
                    notes.add(Tr.s("screen.dynamicvillages.ranch.tip.other", "The Rancher leaves this animal alone"));
                }
                if (!beast.baby() && (type == EntityType.COW || type == EntityType.MOOSHROOM)) {
                    notes.add(pen.milkWanted() > 0
                            ? Tr.s("screen.dynamicvillages.ranch.tip.milk_now", "The Cook wants milk for a cake, so cows are milked now")
                            : Tr.s("screen.dynamicvillages.ranch.tip.milk_later", "Cows are milked only when the Cook wants milk for a cake"));
                }
            }
            if (here.size() > MAX_HOVER_ANIMALS) {
                lines.add(Tr.c("screen.dynamicvillages.ranch.tip.more", "and %s more", here.size() - MAX_HOVER_ANIMALS)
                        .withStyle(ChatFormatting.GRAY));
            }
            for (String note : notes) lines.add(Component.literal(note).withStyle(ChatFormatting.GRAY));
            lines.addAll(outsidersOn(hovered));
        } else if (!outsidersOn(hovered).isEmpty()) {
            lines.addAll(outsidersOn(hovered));
        } else if (hovered.equals(menu.gatePos())) {
            lines.add(Tr.c("screen.dynamicvillages.ranch.tip.gate", "Gate"));
            lines.add(Tr.c("screen.dynamicvillages.ranch.tip.gate.desc", "The Rancher and the animals go in and out here").withStyle(ChatFormatting.GRAY));
        } else if (hovered.equals(pen.frame())) {
            lines.add(Tr.c("screen.dynamicvillages.ranch.tip.token", "Ranch token"));
            lines.add(Tr.c("screen.dynamicvillages.ranch.tip.token.desc", "Right click it to open this window").withStyle(ChatFormatting.GRAY));
        } else if (pen.water().contains(hovered)) {
            lines.add(Tr.c("screen.dynamicvillages.ranch.tip.water", "Water"));
            lines.add(Tr.c("screen.dynamicvillages.ranch.tip.water.desc", "Part of the pen: an animal swimming here still counts as inside").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    /**
     * One animal's grey hover lines: a player's pet or ride, who leads or tends it, then for the
     * Rancher's kinds what it gives (wool, egg, hide) and where it stands with breeding or growing up.
     */
    private static List<String> details(Beast beast, @Nullable EntityType<?> type, long now) {
        List<String> out = new ArrayList<>();
        if (!beast.name().isEmpty()) {
            out.add(Tr.s("screen.dynamicvillages.ranch.tip.named", "Named %s: a player's pet, the Butcher never takes it", beast.name()));
        } else if (beast.saddled()) {
            out.add(Tr.s("screen.dynamicvillages.ranch.tip.saddled", "Saddled: a player's ride, the Butcher never takes it"));
        }
        if (RanchRoomMenu.FENCE_POST.equals(beast.leader())) {
            out.add(Tr.s("screen.dynamicvillages.ranch.tip.tied", "Tied to a fence post"));
        } else if (!beast.leader().isEmpty()) {
            out.add(Tr.s("screen.dynamicvillages.ranch.tip.led", "Being led by %s", who(beast.leader())));
        } else if (!beast.tender().isEmpty()) {
            out.add(Tr.s("screen.dynamicvillages.ranch.tip.tended", "%s is working on it", who(beast.tender())));
        }
        if (kindOf(type) == null) return out;
        if (!beast.baby()) {
            if (type == EntityType.SHEEP) {
                out.add(beast.wool() >= 0 ? Tr.s("screen.dynamicvillages.ranch.tip.woolly", "Woolly: ready to shear")
                        : Tr.s("screen.dynamicvillages.ranch.tip.sheared", "Sheared: it eats grass to grow its wool back"));
            } else if (type == EntityType.CHICKEN) {
                out.add(beast.productAt() <= now ? Tr.s("screen.dynamicvillages.ranch.tip.egg_ready", "Egg ready to collect")
                        : Tr.s("screen.dynamicvillages.ranch.tip.egg_in", "Next egg %s", in(beast.productAt() - now)));
            } else {
                out.add(beast.productAt() <= now ? Tr.s("screen.dynamicvillages.ranch.tip.hide_ready", "Hide ready to comb off")
                        : Tr.s("screen.dynamicvillages.ranch.tip.hide_in", "Next hide %s", in(beast.productAt() - now)));
            }
        }
        if (type == EntityType.MOOSHROOM) return out;
        if (beast.baby()) {
            out.add(beast.grownAt() > now ? Tr.s("screen.dynamicvillages.ranch.tip.grows", "Grows up %s", in(beast.grownAt() - now))
                    : Tr.s("screen.dynamicvillages.ranch.tip.grows_soon", "Grows up any moment now"));
        } else if (beast.loveUntil() > now) {
            out.add(Tr.s("screen.dynamicvillages.ranch.tip.in_love", "In love: looking for a mate"));
        } else if (beast.breedAt() > now) {
            out.add(Tr.s("screen.dynamicvillages.ranch.tip.rest", "Can breed again %s", in(beast.breedAt() - now)));
        } else {
            out.add(Tr.s("screen.dynamicvillages.ranch.tip.can_breed", "Ready to breed"));
        }
        return out;
    }

    /** "in about 4 minutes": real minutes, rounded (the same words as the reasons). */
    private static String in(long ticks) {
        return RanchStatus.in(ticks).getString();
    }

    /** A name from the server; "?" is someone without one. */
    private static String who(String name) {
        return "?".equals(name) ? Tr.s("screen.dynamicvillages.ranch.tip.someone", "someone") : name;
    }

    /** The gate, grass and light are read again once a second: a gate opened or a torch put down shows. */
    @Override protected void containerTick() {
        super.containerTick();
        if (++scanTicks >= 20) {
            scanTicks = 0;
            scan = scanBlocks();
        }
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Everything is drawn in screen coordinates by renderBg.
    }

    // =========================================================================
    // Input
    // =========================================================================

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inside(mouseX, mouseY, resetX(), headerY(), resetWidth(), 20)) {
            view.resetCamera();
            return true;
        }
        if (button == 0 && fromMayor() && inside(mouseX, mouseY, mayorX(), headerY(), mayorWidth(), 20)) {
            MayorStatsScreen.returnToBuilding(menu.mayorId(), menu.frameId());
            PacketDistributor.sendToServer(new MayorBuildingPayloads.BackToMayor(menu.mayorId()));
            return true;
        }
        if (button == 0 && pen().valid()) {
            for (int t = 0; t < 4; t++) {
                if (inside(mouseX, mouseY, tabX(t), tabY(), tabWidth(), TAB_H)) {
                    tab = t;
                    return true;
                }
            }
        }
        if (view.beginDrag(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (view.drag(button, dragX, dragY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        view.endDrag(button);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (view.zoomAt(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
