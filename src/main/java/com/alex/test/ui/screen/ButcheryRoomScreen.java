package com.alex.test.ui.screen;

import com.alex.test.ModItems;
import com.alex.test.Tr;
import com.alex.test.ui.ButcheryRoomMenu;
import com.alex.test.ui.ButcheryRoomMenu.Snapshot;
import com.alex.test.network.ButcheryRoomPayloads;
import com.alex.test.network.MayorBuildingPayloads;
import com.alex.test.ui.screen.mayor.MayorStatsScreen;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.ButcheryTrouble;
import com.alex.test.village.villagers.butcher.ButcheryChecks;
import com.alex.test.village.villagers.butcher.behavior.ButcherActivity;
import com.alex.test.village.villagers.butcher.lead.LeadDoorways;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One Butchery, looked at: a rotatable cutaway of the room with its chopping blocks and grindstone
 * as they stand, and coloured tiles for where the butcher stands (blue), where the led animal waits
 * (pink) and the first tile inside the door (green). Hovering names each. Look only.
 * The last give-ups (round 2) are crosses where the animal stood, in the room or by the door, and
 * a "Recent trouble" list on the side; hovering either says what helps.
 */
public final class ButcheryRoomScreen extends AbstractContainerScreen<ButcheryRoomMenu> {
    private static final int PAD = 8, HEADER_H = 28, FOOTER_H = 16, SIDE_W = 176, CARD_H = 40;
    private static String resetLabel() { return Tr.s("screen.dynamicvillages.storage.reset_view", "Reset view"); }
    private static String mayorLabel() { return Tr.s("screen.dynamicvillages.storage.back_to_mayor", "Back to Mayor"); }
    private static String tryAgainLabel() { return Tr.s("screen.dynamicvillages.butchery.try_again", "Try again now"); }
    private static String automaticLabel() { return Tr.s("screen.dynamicvillages.butchery.automatic", "Automatic"); }

    /** Village claims round 7b: the note a player below Officer sees instead of the controls. */
    private String lookOnlyNote() {
        return Tr.s("screen.dynamicvillages.butchery.claim_look_only", "Look only: %s has not let you change this Butchery.",
                menu.rights().ownerName());
    }

    /** Village claims round 7b: a greyed button's tooltip says who may press it. */
    private void addLookOnlyTip(List<FormattedCharSequence> tip) {
        if (menu.rights().mayChange()) return;
        tip.addAll(font.split(Tr.c("screen.dynamicvillages.butchery.claim_locked", "%s has not let you press this.",
                menu.rights().ownerName()).withStyle(ChatFormatting.RED), TIP_W));
    }

    /** Tile and key colours. The chopping block's swatch is stripped oak, as it is drawn as itself. */
    private static final int BUTCHER = 0xFF3AB3DA;
    private static final int ANIMAL = 0xFFF38BAA;
    private static final int WAY_IN = 0xFF80C71F;
    /** Another tile the butcher could stand on: click it to pick it (round 8). */
    private static final int CAN_STAND = 0xFFA9DDEE;
    private static final int CHOPPING = 0xFFB8945F;
    private static final int GRINDSTONE = 0xFF8B8B8B;
    private static final int NOT_READY = 0xFFC7342B;
    /** Give-up crosses: the room can help (red), or nothing to fix there (grey). */
    private static final int TROUBLE = 0xFFE5392B;
    private static final int TROUBLE_OTHER = 0xFF9A9A9A;
    /** Tooltip text wraps at this width. */
    private static final int TIP_W = 200;

    private static final float[] HOVER_TINT = {1f, 0.95f, 0.8f};
    private static final float[] UNUSABLE_TINT = {1f, 0.5f, 0.45f};
    private static final float[] UNUSABLE_HOVER_TINT = {1f, 0.68f, 0.6f};
    /** Stairs or slabs under a tile: an orange frame round it. */
    private static final int SNAG = 0xFFF9801D;
    /** Check list markers: fine, needs fixing, a tip. */
    private static final int CHECK_OK = StorageUi.ACCENT;
    private static final int CHECK_BAD = NOT_READY;
    private static final int CHECK_TIP = 0xFFE6B84A;
    /** Nothing wrong, nothing to do (grey text). */
    private static final int CHECK_NONE = 0xFF6E6E6E;
    private static final int TAB_H = 16;

    /** The way in, as the client's world shows it; UNKNOWN when it is not loaded. */
    private enum DoorKind { UNKNOWN, OPENING, SINGLE, DOUBLE, GATE }

    /** One line of the check list; {@code color} 0 is a heading. */
    private record Check(int color, String text) {}

    private final RoomSchematicView view = new RoomSchematicView(new RoomSchematicView.Styler() {
        @Override public float[] tint(BlockPos target, boolean hovered) {
            // A chopping block with no free floor beside it is drawn red (round 4).
            if (unusableBlocks.contains(target)) return hovered ? UNUSABLE_HOVER_TINT : UNUSABLE_TINT;
            return hovered ? HOVER_TINT : RoomSchematicView.NO_TINT;
        }
        @Override public int boxColor(BlockPos target, int base, boolean hovered) {
            return hovered ? RoomSchematicView.lighten(base) : base;
        }
    });

    private int seenVersion;

    /** A "Recent trouble" row as drawn last frame, for hovering. */
    private record TroubleRow(int x, int y, int width, int height, ButcheryTrouble trouble) {}
    private final List<TroubleRow> troubleRows = new ArrayList<>();
    /** Where "Try again now" was drawn last frame; -1 when it is not shown. */
    private int tryAgainY = -1;
    /** Where "Automatic" was drawn last frame (round 8); -1 when it is not shown. */
    private int automaticY = -1;
    /** The side panel's tab: the room's notes, the check list (round 4), the pens' animals (round 5). */
    private static final int TAB_ROOM = 0, TAB_CHECKS = 1, TAB_ANIMALS = 2, TABS = 3;
    private int tab = TAB_ROOM;
    /** From the client's world at the last rebuild: stair or slab tiles (room floor and outside the door), the door. */
    private final Set<BlockPos> snags = new HashSet<>();
    private final Set<BlockPos> unusableBlocks = new HashSet<>();
    private DoorKind doorKind = DoorKind.UNKNOWN;

    public ButcheryRoomScreen(ButcheryRoomMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 600;
        imageHeight = 350;
        titleLabelY = -1000;
        inventoryLabelY = -1000;
        seenVersion = menu.version();
        buildRoom();
    }

    @Override protected void init() {
        imageWidth = Math.min(600, width - 16);
        imageHeight = Math.min(350, height - 16);
        super.init();
    }

    // =========================================================================
    // Room model
    // =========================================================================

    private Snapshot room() {
        return menu.snapshot();
    }

    private void buildRoom() {
        view.clear();
        Snapshot room = room();
        ClientLevel world = Minecraft.getInstance().level;
        ClientLevel source = world != null && world.isLoaded(menu.doorPos()) ? world : null;

        Set<BlockPos> objects = new HashSet<>(room.choppingBlocks());
        if (room.grindstone() != null) objects.add(room.grindstone());
        List<BlockPos> doors = RoomSchematicView.doorCells(source, menu.doorPos());
        Set<BlockPos> floor = RoomSchematicView.fillFloor(room.floor(), objects);
        Set<BlockPos> inside = new HashSet<>(floor);
        inside.addAll(objects);
        inside.addAll(doors);
        view.centerOn(inside);
        view.addShell(source, floor, inside, doors, menu.doorPos(), "D");

        // Carpets and other flat coverings lie in the cells villagers stand in.
        if (source != null) {
            for (BlockPos tile : floor) {
                if (objects.contains(tile)) continue;
                BlockState state = source.getBlockState(tile);
                if (state.isAir() || state.getShape(source, tile).isEmpty()
                        || state.getShape(source, tile).max(Direction.Axis.Y) > 0.25) continue;
                view.addDraw(source, tile, 0, null);
            }
        }

        for (BlockPos block : objects) {
            if (source != null && view.addDraw(source, block, 0, block)) {
                view.addBlockBox(block, 0, 1, block, null);
            } else {
                boolean chopping = room.choppingBlocks().contains(block);
                view.addBox(block.getX() + 0.1, block.getY(), block.getZ() + 0.1, block.getX() + 0.9,
                        block.getY() + 0.8, block.getZ() + 0.9, chopping ? CHOPPING : GRINDSTONE, block,
                        null, 0, true);
            }
        }

        // One pad a tile: the butcher's spot first, then the animal's, then the way in.
        Set<BlockPos> padded = new HashSet<>();
        addPad(source, room.killSpot(), BUTCHER, padded);
        addPad(source, room.animalSpot(), ANIMAL, padded);
        addPad(source, room.entry(), WAY_IN, padded);
        // Round 8: every other tile the butcher could stand on, to click.
        for (BlockPos tile : room.standSpots()) addPad(source, tile, CAN_STAND, padded);

        // A cross where an animal was given up, in the room or by the door: red when the room
        // or the way in can help, grey when not (danger came first, a player's pet).
        Map<BlockPos, Boolean> crosses = new LinkedHashMap<>();
        for (ButcheryTrouble trouble : room.troubles()) {
            if (trouble.place() == ButcheryTrouble.Place.WAY) continue;
            crosses.merge(trouble.where(), trouble.kind().roomTrouble(), Boolean::logicalOr);
        }
        crosses.forEach((tile, red) -> addCross(source, tile, red ? TROUBLE : TROUBLE_OTHER));

        // Round 4: what the client's world shows of the check list.
        unusableBlocks.clear();
        unusableBlocks.addAll(room.choppingBlocks());
        unusableBlocks.removeAll(room.checks().usableBlocks());
        snags.clear();
        doorKind = DoorKind.UNKNOWN;
        if (source != null) {
            for (BlockPos tile : room.floor()) {
                if (isSnag(source, tile)) snags.add(tile);
            }
            for (BlockPos tile : room.checks().outside()) {
                if (source.isLoaded(tile) && isSnag(source, tile)) snags.add(tile);
            }
            BlockState door = source.getBlockState(menu.doorPos());
            doorKind = door.getBlock() instanceof FenceGateBlock ? DoorKind.GATE
                    : doors.size() > 1 ? DoorKind.DOUBLE
                    : door.getBlock() instanceof DoorBlock ? DoorKind.SINGLE
                    : DoorKind.OPENING;
        }
        for (BlockPos tile : snags) addFrame(source, tile, SNAG);
        view.finish();
    }

    /** A stair or a bottom slab in the tile or under it: led animals snag on half steps. */
    private static boolean isSnag(ClientLevel world, BlockPos tile) {
        return halfStep(world.getBlockState(tile)) || halfStep(world.getBlockState(tile.below()));
    }

    private static boolean halfStep(BlockState state) {
        return state.getBlock() instanceof StairBlock
                || state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /** A thin frame round the edge of a tile, outside any pad on it. */
    private void addFrame(@Nullable ClientLevel world, BlockPos tile, int color) {
        double top = standTop(world, tile) + 0.01, y1 = top + 0.05;
        double x = tile.getX(), z = tile.getZ(), w = 0.07;
        view.addBox(x, top, z, x + 1, y1, z + w, color, tile, null, 0, true);
        view.addBox(x, top, z + 1 - w, x + 1, y1, z + 1, color, tile, null, 0, true);
        view.addBox(x, top, z + w, x + w, y1, z + 1 - w, color, tile, null, 0, true);
        view.addBox(x + 1 - w, top, z + w, x + 1, y1, z + 1 - w, color, tile, null, 0, true);
    }

    /** A pixel cross just above any pad on what a body stands on in {@code tile}. */
    private void addCross(@Nullable ClientLevel world, BlockPos tile, int color) {
        double top = standTop(world, tile) + 0.08;
        // Five squares down each diagonal, the middle one shared.
        for (int i = 0; i < 5; i++) {
            double a = 0.1 + i * 0.16, b = 0.1 + (4 - i) * 0.16;
            view.addBox(tile.getX() + a, top, tile.getZ() + a, tile.getX() + a + 0.16, top + 0.05,
                    tile.getZ() + a + 0.16, color, tile, null, 0, true);
            if (i == 2) continue;
            view.addBox(tile.getX() + a, top, tile.getZ() + b, tile.getX() + a + 0.16, top + 0.05,
                    tile.getZ() + b + 0.16, color, tile, null, 0, true);
        }
    }

    /** A thin coloured pad on what a body stands on in {@code tile}; hovering it names the tile. */
    private void addPad(@Nullable ClientLevel world, @Nullable BlockPos tile, int color, Set<BlockPos> padded) {
        if (tile == null || !padded.add(tile)) return;
        double top = standTop(world, tile);
        view.addBox(tile.getX() + 0.08, top + 0.01, tile.getZ() + 0.08, tile.getX() + 0.92, top + 0.07,
                tile.getZ() + 0.92, color, tile, null, 0, true);
    }

    /**
     * The top of what a body stands on in {@code tile}: a carpet in the tile itself, else the block
     * under it (a bottom slab is half a block lower than the tile). The flat slab drawn when the
     * world is not loaded tops out just under the tile.
     */
    private static double standTop(@Nullable ClientLevel world, BlockPos tile) {
        if (world == null) return tile.getY() - 0.03;
        VoxelShape here = world.getBlockState(tile).getCollisionShape(world, tile);
        if (!here.isEmpty() && here.max(Direction.Axis.Y) <= 0.25) return tile.getY() + here.max(Direction.Axis.Y);
        BlockPos below = tile.below();
        VoxelShape floor = world.getBlockState(below).getCollisionShape(world, below);
        return floor.isEmpty() ? tile.getY() : below.getY() + Math.min(1.0, floor.max(Direction.Axis.Y));
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
    /** Round 7: opened from the Mayor's Buildings page, so Back to Mayor shows left of Reset view. */
    private boolean fromMayor() { return menu.mayorId() >= 0; }
    private int mayorWidth() { return 26 + font.width(mayorLabel()); }
    private int mayorX() { return resetX() - 6 - mayorWidth(); }
    private int headerY() { return topPos + 4; }
    private int tabY() { return sideTop() + 6 + CARD_H + 8; }
    private int tabWidth() { return (contentWidth() - 2 * (TABS - 1)) / TABS; }
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
        graphics.renderFakeItem(new ItemStack(ModItems.BUTCHERY.get()), x + PAD, y + 6);
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
        renderSidePanel(graphics, mouseX, mouseY);
    }

    /** The colour key under the view, with the camera controls when there is room. */
    private void renderKey(GuiGraphics graphics) {
        int x = viewLeft(), y = viewBottom() + 5;
        x = keyEntry(graphics, x, y, BUTCHER, Tr.s("screen.dynamicvillages.butchery.key.butcher", "Butcher stands"));
        x = keyEntry(graphics, x, y, ANIMAL, Tr.s("screen.dynamicvillages.butchery.key.animal", "Animal waits"));
        x = keyEntry(graphics, x, y, WAY_IN, Tr.s("screen.dynamicvillages.butchery.key.way_in", "Way in"));
        x = keyEntry(graphics, x, y, CHOPPING, Tr.s("screen.dynamicvillages.butchery.key.chopping", "Chopping block"));
        if (room().standSpots().size() > 1) {
            x = keyEntry(graphics, x, y, CAN_STAND, Tr.s("screen.dynamicvillages.butchery.key.can_stand", "Could stand"));
        }
        if (room().troubles().stream().anyMatch(t -> t.place() != ButcheryTrouble.Place.WAY)) {
            x = keyEntry(graphics, x, y, TROUBLE, Tr.s("screen.dynamicvillages.butchery.key.trouble", "Given up"));
        }
        String hint = Tr.s("screen.dynamicvillages.storage.view_hint", "Drag: rotate • Right-drag: pan • Scroll: zoom");
        if (x + 8 + font.width(hint) <= viewRight()) {
            graphics.drawString(font, hint, viewRight() - font.width(hint), y, StorageUi.MUTED, false);
        }
    }

    private int keyEntry(GuiGraphics graphics, int x, int y, int color, String label) {
        graphics.fill(x, y, x + 8, y + 8, color);
        graphics.drawString(font, label, x + 11, y, StorageUi.MUTED, false);
        return x + 11 + font.width(label) + 10;
    }

    /** Header controls: bordered, icon and label centred. */
    private void headerButton(GuiGraphics graphics, int x, int width, @Nullable Item icon, String label, boolean hover) {
        button(graphics, x, headerY(), width, icon, label, hover);
    }

    /** A 20 px button: bordered, icon and label centred. */
    private void button(GuiGraphics graphics, int x, int y, int width, @Nullable Item icon, String label, boolean hover) {
        button(graphics, x, y, width, icon, label, hover, true);
    }

    /** Village claims round 7b: a greyed button (no hover, muted label) when {@code enabled} is false. */
    private void button(GuiGraphics graphics, int x, int y, int width, @Nullable Item icon, String label, boolean hover,
                        boolean enabled) {
        hover = hover && enabled;
        graphics.fill(x, y, x + width, y + 20, hover ? StorageUi.BUTTON_HOVER_BORDER : StorageUi.PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 19, hover ? StorageUi.ROW_HOVER : StorageUi.ROW);
        int iconWidth = icon == null ? 0 : 20;
        int content = x + (width - iconWidth - font.width(label)) / 2;
        if (icon != null) graphics.renderFakeItem(new ItemStack(icon), content, y + 2);
        graphics.drawString(font, label, content + iconWidth, y + 6, enabled ? StorageUi.TEXT : StorageUi.MUTED, false);
    }

    private void renderSidePanel(GuiGraphics graphics, int mouseX, int mouseY) {
        Snapshot room = room();
        troubleRows.clear();
        int x = contentX(), width = contentWidth(), y = sideTop() + 6;
        boolean paused = room.valid() && room.paused();
        String detail = !room.valid() ? Tr.s("screen.dynamicvillages.room.unavailable", "Room unavailable now")
                : paused ? Tr.s("screen.dynamicvillages.butchery.paused", "Paused until tomorrow")
                : room.ready() ? Tr.s("screen.dynamicvillages.butchery.ready", "Ready for the butcher")
                : room.choppingBlocks().isEmpty() ? Tr.s("screen.dynamicvillages.butchery.needs_log", "Needs a stripped log")
                : Tr.s("screen.dynamicvillages.butchery.needs_floor", "Needs open floor beside it");
        int blocks = room.choppingBlocks().size();
        String extra = blocks == 0 ? Tr.s("screen.dynamicvillages.butchery.no_blocks", "No chopping block")
                : blocks == 1 ? Tr.s("screen.dynamicvillages.butchery.one_block", "1 chopping block")
                : Tr.s("screen.dynamicvillages.butchery.blocks", "%s chopping blocks", blocks);
        StorageUi.row(graphics, x, y, width, CARD_H, false, false);
        graphics.fill(x + 1, y + 1, x + 3, y + CARD_H - 1, room.ready() && !paused ? StorageUi.ACCENT : NOT_READY);
        graphics.renderFakeItem(new ItemStack(Items.IRON_SWORD), x + 8, y + 12);
        graphics.drawString(font, trim(title.getString(), width - 36), x + 30, y + 6, StorageUi.TEXT, false);
        graphics.drawString(font, trim(detail, width - 36), x + 30, y + 17,
                room.ready() && !paused ? StorageUi.MUTED : StorageUi.DENIED, false);
        graphics.drawString(font, trim(extra, width - 36), x + 30, y + 28, StorageUi.MUTED, false);

        y += CARD_H + 8;
        tryAgainY = -1;
        automaticY = -1;
        if (room.valid()) {
            List<Check> checks = checks(room);
            long problems = checks.stream().filter(check -> check.color() == CHECK_BAD).count();
            String checksLabel = problems > 0
                    ? Tr.s("screen.dynamicvillages.butchery.tab.checks_count", "Checks (%s)", problems)
                    : Tr.s("screen.dynamicvillages.butchery.tab.checks", "Checks");
            String[] labels = {Tr.s("screen.dynamicvillages.butchery.tab.room", "Room"), checksLabel,
                    Tr.s("screen.dynamicvillages.butchery.tab.animals", "Animals")};
            for (int i = 0; i < TABS; i++) {
                StorageUi.segment(graphics, font, tabX(i), tabY(), tabWidth(), TAB_H, trim(labels[i], tabWidth() - 4),
                        tab == i, tab != i && inside(mouseX, mouseY, tabX(i), tabY(), tabWidth(), TAB_H));
            }
            y = tabY() + TAB_H + 6;
            if (tab == TAB_CHECKS) {
                renderChecks(graphics, checks, x, y, width);
                return;
            }
            if (tab == TAB_ANIMALS) {
                renderChecks(graphics, animals(room), x, y, width);
                return;
            }
        }
        if (paused) {
            y = drawNote(graphics, Tr.s("screen.dynamicvillages.butchery.note.paused", "%s animals were given up here within %s minutes, so the butcher brings no more until tomorrow morning. Fix what the crosses show, then try again.",
                    ButcheryBuilding.PAUSE_GIVE_UPS, ButcheryBuilding.PAUSE_WINDOW_TICKS / 1200), x, y, width, StorageUi.TEXT);
            if (y + 4 + 20 <= sideBottom() - 4) {
                tryAgainY = y + 4;
                button(graphics, x, tryAgainY, width, Items.LEAD, tryAgainLabel(),
                        inside(mouseX, mouseY, x, tryAgainY, width, 20), menu.rights().mayChange());
                y = tryAgainY + 20;
            }
            y += 8;
        }
        // First, so a short window cuts the notes, not these.
        if (!room.troubles().isEmpty()) y = renderTroubles(graphics, room, x, y, width, mouseX, mouseY) + 6;
        if (!room.valid()) {
            drawNote(graphics, Tr.s("screen.dynamicvillages.butchery.note.unavailable", "The room is not recognised right now. Check its door and walls, then right-click the token again."),
                    x, y, width, StorageUi.MUTED);
            return;
        }
        if (!room.checks().butchers().isEmpty()) y = renderToday(graphics, room, x, y, width) + 6;
        y = renderChoice(graphics, room, x, y, width, mouseX, mouseY);
        if (!room.ready()) {
            y = drawNote(graphics, room.choppingBlocks().isEmpty()
                    ? Tr.s("screen.dynamicvillages.butchery.note.no_log", "The butcher strikes on a chopping block: any stripped log or stripped wood inside the room.")
                    : Tr.s("screen.dynamicvillages.butchery.note.no_floor", "A chopping block needs a free floor tile beside it for the butcher, and one more free tile next to that for the animal."),
                    x, y, width, StorageUi.TEXT);
            drawNote(graphics, Tr.s("screen.dynamicvillages.butchery.note.until_ready", "Until then the butcher does not take animals here."),
                    x, y + 4, width, StorageUi.MUTED);
            return;
        }
        y = drawNote(graphics, Tr.s("screen.dynamicvillages.butchery.note.how", "The butcher leads an animal in through the door and over the green tile. The animal waits on the pink tile, and the butcher strikes from the blue tile beside the chopping block."),
                x, y, width, StorageUi.TEXT);
        if (room.animalSpot() != null && room.animalSpot().equals(room.entry())) {
            y = drawNote(graphics, Tr.s("screen.dynamicvillages.butchery.note.doorway", "The animal waits in the doorway, so the door stays open while it is there. A free tile further in works better."),
                    x, y + 4, width, StorageUi.GOLD);
        }
        if (room.workSpots() > 1) {
            y = drawNote(graphics, Tr.s("screen.dynamicvillages.butchery.note.spare", "Spare places to work: %s. They are used when someone stands on the first one or an item lies there.", room.workSpots() - 1),
                    x, y + 4, width, StorageUi.MUTED);
        }
        drawNote(graphics, room.grindstone() != null
                        ? Tr.s("screen.dynamicvillages.butchery.note.grindstone", "Grindstone: the butcher sharpens its sword here once a day.")
                        : Tr.s("screen.dynamicvillages.butchery.note.no_grindstone", "No grindstone. With one in the room, the butcher sharpens its sword once a day."),
                x, y + 4, width, StorageUi.MUTED);
    }

    /**
     * Round 6: each butcher nearby since the village morning, wherever it culled: the animals culled
     * and strays brought home, then how much of its sword is left and its last grindstone visit.
     * Returns the y below it.
     */
    private int renderToday(GuiGraphics graphics, Snapshot room, int x, int y, int width) {
        if (y + font.lineHeight > sideBottom() - 4) return y;
        graphics.drawString(font, Tr.s("screen.dynamicvillages.butchery.today.header", "Today"), x, y, StorageUi.TEXT, false);
        y += font.lineHeight + 3;
        for (ButcheryChecks.Butcher butcher : room.checks().butchers()) {
            String work = Tr.s("screen.dynamicvillages.butchery.today.culled", "%s: %s culled", butcher.name(), butcher.kills());
            if (butcher.strays() == 1) {
                work += Tr.s("screen.dynamicvillages.butchery.today.stray", ", 1 stray brought home");
            } else if (butcher.strays() > 1) {
                work += Tr.s("screen.dynamicvillages.butchery.today.strays", ", %s strays brought home", butcher.strays());
            }
            y = drawNote(graphics, work, x, y, width, StorageUi.TEXT);
            String sword = switch (butcher.sword()) {
                case ButcheryChecks.Butcher.NO_SWORD -> Tr.s("screen.dynamicvillages.butchery.today.no_sword", "No sword yet");
                case ButcheryChecks.Butcher.NO_WEAR -> Tr.s("screen.dynamicvillages.butchery.today.no_wear", "Sword does not wear");
                default -> Tr.s("screen.dynamicvillages.butchery.today.sword", "Sword %s%% left", butcher.sword());
            };
            String sharpened = switch (butcher.sharpened()) {
                case ButcheryChecks.Butcher.NEVER -> Tr.s("screen.dynamicvillages.butchery.today.never", "not sharpened yet");
                case 0 -> Tr.s("screen.dynamicvillages.butchery.today.sharp_today", "sharpened today");
                case 1 -> Tr.s("screen.dynamicvillages.butchery.today.sharp_yesterday", "sharpened yesterday");
                default -> Tr.s("screen.dynamicvillages.butchery.today.sharp_days", "sharpened %s days ago", butcher.sharpened());
            };
            y = drawNote(graphics, Tr.s("screen.dynamicvillages.butchery.today.gear", "%s, %s", sword, sharpened),
                    x + 6, y, width - 6, butcher.sword() == ButcheryChecks.Butcher.NO_SWORD ? StorageUi.GOLD : StorageUi.MUTED) + 2;
        }
        return y;
    }

    /**
     * Round 8: the butcher's spot the player picked, or how to pick one. A picked spot the room no
     * longer allows says why (the butcher uses the automatic one until it works again), and both show
     * the Automatic button. Returns the y below it.
     */
    private int renderChoice(GuiGraphics graphics, Snapshot room, int x, int y, int width, int mouseX, int mouseY) {
        // Village claims round 7b: below Officer the window is look only.
        if (!menu.rights().mayChange()) y = drawNote(graphics, lookOnlyNote(), x, y, width, StorageUi.DENIED) + 6;
        if (room.chosen() == null) {
            if (!room.ready() || room.standSpots().size() < 2 || !menu.rights().mayChange()) return y;
            return drawNote(graphics, Tr.s("screen.dynamicvillages.butchery.choice.hint", "Click a pale blue tile to pick where the butcher stands."),
                    x, y, width, StorageUi.MUTED) + 6;
        }
        String note = switch (room.choiceTrouble()) {
            case NONE -> Tr.s("screen.dynamicvillages.butchery.choice.picked", "You picked where the butcher stands (the blue tile).");
            case NOT_FLOOR -> Tr.s("screen.dynamicvillages.butchery.choice.not_floor", "The spot you picked is not room floor now, so the butcher uses another one until it is.");
            case NO_BLOCK -> Tr.s("screen.dynamicvillages.butchery.choice.no_block", "The spot you picked has no chopping block beside it now, so the butcher uses another one until it has.");
            case NO_ROOM_FOR_ANIMAL -> Tr.s("screen.dynamicvillages.butchery.choice.no_room", "The spot you picked has no free tile beside it for the animal now, so the butcher uses another one until it has.");
            case DOORWAY -> Tr.s("screen.dynamicvillages.butchery.choice.doorway", "The spot you picked is the first tile inside the door. With another place in the room, the butcher stands there instead, so the animal can come in.");
        };
        y = drawNote(graphics, note, x, y, width, room.choiceTrouble() == ButcheryBuilding.ChoiceTrouble.NONE ? StorageUi.TEXT : StorageUi.DENIED);
        if (y + 4 + 20 <= sideBottom() - 4) {
            automaticY = y + 4;
            button(graphics, x, automaticY, width, null, automaticLabel(), inside(mouseX, mouseY, x, automaticY, width, 20),
                    menu.rights().mayChange());
            y = automaticY + 20;
        }
        return y + 8;
    }

    /** The check list: a coloured square, then the line (green fine, red to fix, gold a tip). */
    private void renderChecks(GuiGraphics graphics, List<Check> checks, int x, int y, int width) {
        for (Check check : checks) {
            if (y + font.lineHeight > sideBottom() - 4) break;
            if (check.color() == 0) {
                graphics.drawString(font, trim(check.text(), width), x, y + 3, StorageUi.TEXT, false);
                y += font.lineHeight + 6;
                continue;
            }
            graphics.fill(x, y + 1, x + 6, y + 7, check.color());
            int text = check.color() == CHECK_BAD ? StorageUi.DENIED : check.color() == CHECK_TIP ? StorageUi.GOLD
                    : check.color() == CHECK_NONE ? StorageUi.MUTED : StorageUi.TEXT;
            y = drawNote(graphics, check.text(), x + 10, y, width - 10, text) + 3;
        }
    }

    /**
     * The Animals tab (round 5): each pen that brings its animals here, one line per kind, counted as
     * the butcher's pick counts (babies and players' animals left out, led and taken ones too). Each
     * butcher keeps its own number of adults and culls only the kinds it is allowed: butchers set
     * alike share a line, ones set differently get a line each.
     */
    private List<Check> animals(Snapshot room) {
        ButcheryChecks.Checks server = room.checks();
        List<Check> out = new ArrayList<>();
        if (server.pens().isEmpty()) {
            out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.butchery.animals.no_pen", "No pen brings its animals here now. The Checks tab says why.")));
            return out;
        }
        List<ButcheryChecks.Butcher> rules = server.butchers().stream().filter(ButcheryChecks.Butcher::workOn).toList();
        if (rules.isEmpty()) {
            out.add(new Check(CHECK_NONE, Tr.s("screen.dynamicvillages.butchery.animals.default", "No working butcher nearby: counted with the usual setting, keeping %s of each.", server.defaultKeep())));
            rules = List.of(new ButcheryChecks.Butcher("", "", true, server.defaultKeep(), List.of("*"), 0, 0,
                    ButcheryChecks.Butcher.NO_SWORD, ButcheryChecks.Butcher.NEVER));
        }
        for (ButcheryChecks.Pen pen : server.pens()) {
            out.add(new Check(0, Tr.s("screen.dynamicvillages.butchery.animals.pen", "Ranch, %s blocks from here", pen.distance())));
            if (pen.herds().isEmpty()) {
                out.add(new Check(CHECK_NONE, Tr.s("screen.dynamicvillages.butchery.animals.empty", "No grown animals with meat in the pen")));
            }
            for (ButcheryChecks.Herd herd : pen.herds()) {
                // Butchers set alike for this kind share one line.
                Map<String, List<String>> groups = new LinkedHashMap<>();
                Map<String, ButcheryChecks.Butcher> firstOf = new LinkedHashMap<>();
                for (ButcheryChecks.Butcher butcher : rules) {
                    boolean allowed = butcher.allowed().contains("*") || butcher.allowed().contains(herd.type());
                    String key = allowed ? "keep " + butcher.keep() : "off";
                    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(butcher.name());
                    firstOf.putIfAbsent(key, butcher);
                }
                for (Map.Entry<String, List<String>> group : groups.entrySet()) {
                    String label = herdName(herd.type());
                    if (groups.size() > 1) label = Tr.s("screen.dynamicvillages.butchery.animals.who", "%s (%s)", label, String.join(", ", group.getValue()));
                    out.add(herdLine(herd, label, group.getKey().equals("off") ? -1 : firstOf.get(group.getKey()).keep()));
                }
            }
            if (pen.neverCulled() > 0) {
                out.add(new Check(CHECK_NONE, Tr.s("screen.dynamicvillages.butchery.animals.never", "%s babies or players' animals, never culled", pen.neverCulled())));
            }
        }
        return out;
    }

    /** "Pigs: 3 adults, keeping 2, 1 to cull"; {@code keep} -1: the kind is not on the butcher's list. */
    private Check herdLine(ButcheryChecks.Herd herd, String label, int keep) {
        if (keep < 0) {
            return new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.butchery.animals.off", "%s: not on the Livestock to cull list", label));
        }
        String line = Tr.s("screen.dynamicvillages.butchery.animals.count", "%s: %s adults, keeping %s", label, herd.adults(), keep);
        if (herd.busy() > 0) line += Tr.s("screen.dynamicvillages.butchery.animals.busy", ", %s already taken", herd.busy());
        int toCull = herd.toCull(keep);
        if (toCull == 0) {
            return new Check(CHECK_NONE, line + Tr.s("screen.dynamicvillages.butchery.animals.nothing", ", nothing to cull"));
        }
        if (!herd.pickable()) {
            return new Check(CHECK_TIP, line + Tr.s("screen.dynamicvillages.butchery.animals.waiting", ", %s to cull once one is not breeding or tied up", toCull));
        }
        return new Check(CHECK_OK, line + Tr.s("screen.dynamicvillages.butchery.animals.cull", ", %s to cull", toCull));
    }

    /** "Pigs", "Sheep": a kind as a herd. */
    private static String herdName(String type) {
        return switch (type) {
            case "minecraft:cow" -> Tr.s("screen.dynamicvillages.butchery.kind.cows", "Cows");
            case "minecraft:sheep" -> Tr.s("screen.dynamicvillages.butchery.kind.sheep", "Sheep");
            case "minecraft:pig" -> Tr.s("screen.dynamicvillages.butchery.kind.pigs", "Pigs");
            case "minecraft:chicken" -> Tr.s("screen.dynamicvillages.butchery.kind.chickens", "Chickens");
            default -> animalName(type);
        };
    }

    /**
     * Everything that decides whether animals get here and in: the chopping block, the Ranch and
     * the way from its gate (the server's), the door and stairs or slabs (the client's world), and
     * each butcher near a player.
     */
    private List<Check> checks(Snapshot room) {
        ButcheryChecks.Checks server = room.checks();
        List<Check> out = new ArrayList<>();
        if (room.choppingBlocks().isEmpty()) {
            out.add(new Check(CHECK_BAD, Tr.s("screen.dynamicvillages.butchery.check.no_block", "No chopping block. Put a stripped log or stripped wood inside the room.")));
        } else if (server.usableBlocks().isEmpty()) {
            out.add(new Check(CHECK_BAD, Tr.s("screen.dynamicvillages.butchery.check.no_floor", "No free floor beside the chopping block. The butcher needs a tile beside it, and the animal one more.")));
        } else {
            out.add(new Check(CHECK_OK, Tr.s("screen.dynamicvillages.butchery.check.block_ok", "Chopping block with free floor beside it")));
            int left = room.choppingBlocks().size() - server.usableBlocks().size();
            if (left == 1) {
                out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.butchery.check.one_unused", "1 chopping block has no free floor beside it (red in the view).")));
            } else if (left > 1) {
                out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.butchery.check.some_unused", "%s chopping blocks have no free floor beside them (red in the view).", left)));
            }
        }

        int most = (int) ButcherActivity.MAX_BUTCHERY_DIST;
        switch (server.ranch()) {
            case NONE -> out.add(new Check(CHECK_BAD, Tr.s("screen.dynamicvillages.butchery.check.no_ranch", "No Ranch in the village yet. The butcher takes its animals from a Ranch.")));
            case TOO_FAR -> out.add(new Check(CHECK_BAD, Tr.s("screen.dynamicvillages.butchery.check.ranch_far", "The Ranch is too far: %s blocks from its gate, at most %s.", server.distance(), most)));
            case OTHER_NEARER -> out.add(room.ready()
                    ? new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.butchery.check.other_nearer", "Not used now: another Butchery is nearer the Ranch, so its animals go there."))
                    : new Check(CHECK_OK, Tr.s("screen.dynamicvillages.butchery.check.ranch_near", "A Ranch %s blocks from here", server.distance())));
            case USED -> out.add(new Check(CHECK_OK, server.ranchesUsing() > 1
                    ? Tr.s("screen.dynamicvillages.butchery.check.used_many", "Used by %s Ranches, the nearest %s blocks away", server.ranchesUsing(), server.distance())
                    : Tr.s("screen.dynamicvillages.butchery.check.used", "Used by the Ranch, %s blocks from its gate", server.distance())));
        }
        if (server.path() == ButcheryChecks.Path.OK) {
            out.add(new Check(CHECK_OK, Tr.s("screen.dynamicvillages.butchery.check.path_ok", "A way from the Ranch gate to the butcher's tile")));
        } else if (server.path() == ButcheryChecks.Path.NONE) {
            out.add(new Check(CHECK_BAD, Tr.s("screen.dynamicvillages.butchery.check.no_path", "No way from the Ranch gate to the butcher's tile. Look for closed iron doors, fences or high steps on the way.")));
        }

        switch (doorKind) {
            case DOUBLE -> out.add(new Check(CHECK_OK, Tr.s("screen.dynamicvillages.butchery.check.double_door", "Double door: animals walk in easily")));
            case GATE -> out.add(new Check(CHECK_OK, Tr.s("screen.dynamicvillages.butchery.check.gate", "Fence gate: animals walk in easily")));
            case SINGLE -> out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.butchery.check.single_door", "Single door: animals squeeze through slowly. A double door or a fence gate is easier.")));
            default -> { }
        }
        if (doorKind != DoorKind.UNKNOWN) {
            out.add(snags.isEmpty()
                    ? new Check(CHECK_OK, Tr.s("screen.dynamicvillages.butchery.check.flat", "Flat floor inside and outside the door"))
                    : new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.butchery.check.snags", "Stairs or slabs on %s tiles (orange): animals on a lead can snag on half steps.", snags.size())));
        }

        out.add(new Check(0, Tr.s("screen.dynamicvillages.butchery.check.butchers", "Butchers")));
        if (server.butchers().isEmpty()) {
            out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.butchery.check.no_butcher", "No butcher nearby. Give a villager the Butcher job; ones far away are not listed.")));
        }
        for (ButcheryChecks.Butcher butcher : server.butchers()) {
            if (!butcher.workOn()) {
                out.add(new Check(CHECK_TIP, Tr.s("screen.dynamicvillages.butchery.check.work_off", "%s: work is switched off", butcher.name())));
            } else if (!butcher.status().isEmpty()) {
                out.add(new Check(CHECK_BAD, Tr.s("screen.dynamicvillages.butchery.check.butcher_status", "%s: %s", butcher.name(), butcher.status())));
            } else {
                out.add(new Check(CHECK_OK, Tr.s("screen.dynamicvillages.butchery.check.butcher_ok", "%s: ready to work", butcher.name())));
            }
        }
        return out;
    }

    /** The "Recent trouble" list, newest first: what went wrong, where and how long ago. Returns the y below it. */
    private int renderTroubles(GuiGraphics graphics, Snapshot room, int x, int y, int width, int mouseX, int mouseY) {
        graphics.drawString(font, Tr.s("screen.dynamicvillages.butchery.trouble.header", "Recent trouble"),
                x, y, StorageUi.TEXT, false);
        y += font.lineHeight + 3;
        int height = 2 * (font.lineHeight + 1) + 3;
        for (ButcheryTrouble trouble : room.troubles()) {
            if (y + height > sideBottom() - 4) break;
            boolean roomTrouble = trouble.kind().roomTrouble();
            StorageUi.row(graphics, x, y, width, height, false, inside(mouseX, mouseY, x, y, width, height));
            graphics.fill(x + 1, y + 1, x + 3, y + height - 1, roomTrouble ? TROUBLE : TROUBLE_OTHER);
            graphics.drawString(font, trim(title(trouble), width - 10), x + 7, y + 3,
                    roomTrouble ? StorageUi.DENIED : StorageUi.TEXT, false);
            graphics.drawString(font, trim(when(trouble), width - 10), x + 7, y + 4 + font.lineHeight,
                    StorageUi.MUTED, false);
            troubleRows.add(new TroubleRow(x, y, width, height, trouble));
            y += height + 2;
        }
        return y;
    }

    /** "Pig got stuck": what went wrong, the animal named by its kind. */
    private static String title(ButcheryTrouble trouble) {
        String animal = animalName(trouble.animal());
        return switch (trouble.kind()) {
            case STUCK -> Tr.s("screen.dynamicvillages.butchery.trouble.stuck", "%s got stuck", animal);
            case DOOR_HELD -> Tr.s("screen.dynamicvillages.butchery.trouble.door_held", "%s was slow at a door", animal);
            case WOULD_NOT_COME_IN -> Tr.s("screen.dynamicvillages.butchery.trouble.not_in", "%s would not come in", animal);
            case NO_WAY -> Tr.s("screen.dynamicvillages.butchery.trouble.no_way", "%s: no way through", animal);
            case FELL_BEHIND -> Tr.s("screen.dynamicvillages.butchery.trouble.fell_behind", "%s fell behind", animal);
            case ROOM_BROKE -> Tr.s("screen.dynamicvillages.butchery.trouble.room_broke", "%s: room stopped working", animal);
            case WOULD_NOT_GO_DOWN -> Tr.s("screen.dynamicvillages.butchery.trouble.not_down", "%s would not go down", animal);
            case CALLED_AWAY -> Tr.s("screen.dynamicvillages.butchery.trouble.called_away", "%s: butcher called away", animal);
            case PLAYERS_ANIMAL -> Tr.s("screen.dynamicvillages.butchery.trouble.players", "%s belongs to a player", animal);
        };
    }

    /** What helps, for the tooltip. */
    private static String fix(ButcheryTrouble trouble) {
        return switch (trouble.kind()) {
            case STUCK -> Tr.s("screen.dynamicvillages.butchery.fix.stuck", "Stairs, slabs and fences snag animals on a lead. A flat floor and path work best.");
            case DOOR_HELD -> Tr.s("screen.dynamicvillages.butchery.fix.door_held", "It took over %s s to get through a door or gate. A double door or a fence gate is wider than one door.",
                    LeadDoorways.MAX_HOLD_TICKS / 20);
            case WOULD_NOT_COME_IN -> Tr.s("screen.dynamicvillages.butchery.fix.not_in", "It stopped outside and could not be put in. Leave the floor just inside the door free.");
            case NO_WAY -> Tr.s("screen.dynamicvillages.butchery.fix.no_way", "The butcher found no path here with the animal. Look for iron doors, high steps or water on the way.");
            case FELL_BEHIND -> Tr.s("screen.dynamicvillages.butchery.fix.fell_behind", "The animal fell too far behind on the way. A shorter, flatter path from the Ranch helps.");
            case ROOM_BROKE -> Tr.s("screen.dynamicvillages.butchery.fix.room_broke", "A door, a wall or the chopping block changed while the animal was on its way.");
            case WOULD_NOT_GO_DOWN -> Tr.s("screen.dynamicvillages.butchery.fix.not_down", "It took too many blows. A better or sharper sword helps.");
            case CALLED_AWAY -> Tr.s("screen.dynamicvillages.butchery.fix.called_away", "Something more urgent came first, like danger or the alarm bell. Nothing to fix in the room.");
            case PLAYERS_ANIMAL -> Tr.s("screen.dynamicvillages.butchery.fix.players", "It was named or saddled on the way. Pets and rides are never culled.");
        };
    }

    /** "By the door, 2 min ago". */
    private static String when(ButcheryTrouble trouble) {
        String place = switch (trouble.place()) {
            case ROOM -> Tr.s("screen.dynamicvillages.butchery.trouble.in_room", "In the room");
            case DOOR -> Tr.s("screen.dynamicvillages.butchery.trouble.by_door", "By the door");
            case WAY -> Tr.s("screen.dynamicvillages.butchery.trouble.on_way", "On the way");
        };
        return Tr.s("screen.dynamicvillages.butchery.trouble.when", "%s, %s", place, age(trouble));
    }

    /** How long ago, by game time (it runs on through /time set and stops while the game is paused). */
    private static String age(ButcheryTrouble trouble) {
        ClientLevel world = Minecraft.getInstance().level;
        long minutes = world == null ? 0 : Math.max(0, world.getGameTime() - trouble.gameTime()) / 1200;
        if (minutes < 1) return Tr.s("screen.dynamicvillages.butchery.trouble.age.now", "just now");
        if (minutes < 60) return Tr.s("screen.dynamicvillages.butchery.trouble.age.min", "%s min ago", minutes);
        return Tr.s("screen.dynamicvillages.butchery.trouble.age.hours", "%s h ago", minutes / 60);
    }

    /** The kind's own name ("Pig"), from its entity type id. */
    private static String animalName(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return id;
        return BuiltInRegistries.ENTITY_TYPE.getOptional(key).map(type -> type.getDescription().getString()).orElse(id);
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
        if (menu.version() != seenVersion) {
            seenVersion = menu.version();
            buildRoom();
        }
        // super.render draws the background, and with it the view, once.
        view.setViewport(viewLeft(), viewTop(), viewRight(), viewBottom());
        // A hovered trouble row lights up its cross (last frame's rows).
        ButcheryTrouble row = troubleRowAt(mouseX, mouseY);
        BlockPos picked = view.isDragging() ? null : view.pick(mouseX, mouseY);
        view.setHovered(row != null && row.place() != ButcheryTrouble.Place.WAY ? row.where() : picked);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);

        List<FormattedCharSequence> tip = new ArrayList<>();
        if (tryAgainY >= 0 && inside(mouseX, mouseY, contentX(), tryAgainY, contentWidth(), 20)) {
            tip.add(Component.literal(tryAgainLabel()).getVisualOrderText());
            tip.addAll(font.split(Tr.c("screen.dynamicvillages.butchery.try_again.desc",
                    "The butcher takes animals here again right away. Fix the room first, or they may get stuck again.")
                    .withStyle(ChatFormatting.GRAY), TIP_W));
            addLookOnlyTip(tip);
        } else if (automaticY >= 0 && inside(mouseX, mouseY, contentX(), automaticY, contentWidth(), 20)) {
            tip.add(Component.literal(automaticLabel()).getVisualOrderText());
            tip.addAll(font.split(Tr.c("screen.dynamicvillages.butchery.automatic.desc",
                    "The butcher picks its own spot again: the free tile nearest the door.")
                    .withStyle(ChatFormatting.GRAY), TIP_W));
            addLookOnlyTip(tip);
        } else if (row != null) {
            boolean roomTrouble = row.kind().roomTrouble();
            tip.add(Component.literal(title(row)).withStyle(roomTrouble ? ChatFormatting.RED : ChatFormatting.WHITE).getVisualOrderText());
            tip.add(Component.literal(when(row)).withStyle(ChatFormatting.GRAY).getVisualOrderText());
            tip.addAll(font.split(Component.literal(fix(row)).withStyle(ChatFormatting.GOLD), TIP_W));
        } else if (picked != null) {
            for (Component line : tooltipFor(picked)) tip.add(line.getVisualOrderText());
            addTroubleTips(tip, picked);
        }
        if (!tip.isEmpty()) graphics.renderTooltip(font, tip, mouseX, mouseY);
    }

    @Nullable
    private ButcheryTrouble troubleRowAt(double mouseX, double mouseY) {
        for (TroubleRow row : troubleRows) {
            if (inside(mouseX, mouseY, row.x(), row.y(), row.width(), row.height())) return row.trouble();
        }
        return null;
    }

    /** The give-ups on the hovered tile, newest first, then each kind's fix once. */
    private void addTroubleTips(List<FormattedCharSequence> tip, BlockPos tile) {
        List<ButcheryTrouble> here = room().troubles().stream()
                .filter(t -> t.place() != ButcheryTrouble.Place.WAY && t.where().equals(tile)).toList();
        if (here.isEmpty()) return;
        for (ButcheryTrouble trouble : here) {
            String line = Tr.s("screen.dynamicvillages.butchery.trouble.title_age", "%s, %s", title(trouble), age(trouble));
            tip.add(Component.literal(line).withStyle(trouble.kind().roomTrouble() ? ChatFormatting.RED : ChatFormatting.WHITE)
                    .getVisualOrderText());
        }
        Set<ButcheryTrouble.Kind> fixed = EnumSet.noneOf(ButcheryTrouble.Kind.class);
        for (ButcheryTrouble trouble : here) {
            if (fixed.add(trouble.kind())) tip.addAll(font.split(Component.literal(fix(trouble)).withStyle(ChatFormatting.GOLD), TIP_W));
        }
    }

    /** What the hovered door, tile or block is for. */
    private List<Component> tooltipFor(BlockPos hovered) {
        Snapshot room = room();
        List<Component> lines = new ArrayList<>();
        if (hovered.equals(menu.doorPos())) {
            lines.add(Tr.c("screen.dynamicvillages.room.door", "Door"));
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.door", "Animals are led in through here").withStyle(ChatFormatting.GRAY));
        } else if (hovered.equals(room.killSpot())) {
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.butcher", "Butcher's spot"));
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.butcher.desc", "The butcher stands here to strike").withStyle(ChatFormatting.GRAY));
        } else if (hovered.equals(room.animalSpot())) {
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.animal", "Animal's spot"));
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.animal.desc", "The led animal waits here, beside the butcher").withStyle(ChatFormatting.GRAY));
            if (hovered.equals(room.entry())) {
                lines.add(Tr.c("screen.dynamicvillages.butchery.tip.animal.doorway", "It is the first tile inside the door").withStyle(ChatFormatting.GOLD));
            }
        } else if (hovered.equals(room.entry())) {
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.way_in", "Way in"));
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.way_in.desc", "The first tile inside the door").withStyle(ChatFormatting.GRAY));
        } else if (room.choppingBlocks().contains(hovered)) {
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.chopping", "Chopping block"));
            ClientLevel world = Minecraft.getInstance().level;
            if (world != null && world.isLoaded(hovered)) {
                lines.add(world.getBlockState(hovered).getBlock().getName().withStyle(ChatFormatting.GRAY));
            }
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.chopping.desc", "The butcher strikes on it").withStyle(ChatFormatting.GRAY));
            if (unusableBlocks.contains(hovered)) {
                lines.add(Tr.c("screen.dynamicvillages.butchery.tip.chopping.unused", "No free floor beside it, so it is not used").withStyle(ChatFormatting.RED));
            }
        } else if (hovered.equals(room.grindstone())) {
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.grindstone", "Grindstone"));
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.grindstone.desc", "The butcher sharpens its sword here").withStyle(ChatFormatting.GRAY));
        } else if (room.standSpots().contains(hovered)) {
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.can_stand", "Another place to stand"));
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.can_stand.desc", "Beside a chopping block, with room for the animal").withStyle(ChatFormatting.GRAY));
        } else if (room.checks().outside().contains(hovered)
                || room.troubles().stream().anyMatch(t -> t.place() == ButcheryTrouble.Place.DOOR && t.where().equals(hovered))) {
            // A mark outside the room: the lines below say what it is.
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.outside", "Just outside the room"));
        }
        if (snags.contains(hovered)) {
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.snag", "Stairs or a slab here: animals on a lead can snag on it").withStyle(ChatFormatting.GOLD));
        }
        if (hovered.equals(room.chosen())) {
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.picked", "You picked this spot").withStyle(ChatFormatting.AQUA));
        } else if (room.valid() && room.standSpots().contains(hovered)) {
            lines.add(Tr.c("screen.dynamicvillages.butchery.tip.click_stand", "Click: the butcher stands here from now on").withStyle(ChatFormatting.AQUA));
        }
        return lines;
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
        if (button == 0 && room().valid()) {
            for (int i = 0; i < TABS; i++) {
                if (inside(mouseX, mouseY, tabX(i), tabY(), tabWidth(), TAB_H)) {
                    tab = i;
                    return true;
                }
            }
        }
        if (button == 0 && tryAgainY >= 0 && inside(mouseX, mouseY, contentX(), tryAgainY, contentWidth(), 20)) {
            if (!menu.rights().mayChange()) return true;
            PacketDistributor.sendToServer(new ButcheryRoomPayloads.TryAgain(menu.frameId()));
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
            return true;
        }
        if (button == 0 && automaticY >= 0 && inside(mouseX, mouseY, contentX(), automaticY, contentWidth(), 20)) {
            if (!menu.rights().mayChange()) return true;
            PacketDistributor.sendToServer(new ButcheryRoomPayloads.ChooseSpot(menu.frameId(), null));
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
            return true;
        }
        if (view.beginDrag(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (view.drag(button, dragX, dragY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (view.endDrag(button)) {
            // A click, not a drag: a tile the butcher can stand on becomes its spot (round 8).
            BlockPos hit = view.pick(mouseX, mouseY);
            Snapshot room = room();
            if (hit != null && room.valid() && room.standSpots().contains(hit) && !hit.equals(room.chosen())
                    && menu.rights().mayChange()) {
                PacketDistributor.sendToServer(new ButcheryRoomPayloads.ChooseSpot(menu.frameId(), hit));
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (view.zoomAt(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
