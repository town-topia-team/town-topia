package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.network.HouseRoomPayloads;
import com.alex.test.network.MayorBuildingPayloads;
import com.alex.test.ui.HouseRoomMenu;
import com.alex.test.ui.HouseRoomMenu.BedMarker;
import com.alex.test.ui.HouseRoomMenu.BedStatus;
import com.alex.test.ui.HouseRoomMenu.HomeKind;
import com.alex.test.ui.HouseRoomMenu.MayorNight;
import com.alex.test.ui.HouseRoomMenu.Resident;
import com.alex.test.ui.screen.mayor.MayorStatsScreen;
import com.alex.test.village.stats.CrowdingBalance;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * House management: a rotatable cutaway of the house with every bed coloured by who holds it,
 * next to a panel for choosing who sleeps where. Green beds are claimed, orange ones free,
 * dimmed ones free but over the token's capacity, red ones a claim whose bed is gone.
 */
public final class HouseRoomScreen extends AbstractContainerScreen<HouseRoomMenu> {
    private static final int PAD = 8, HEADER_H = 28, FOOTER_H = 16, SIDE_W = 176;
    private static final int CARD_H = 40, ROW_H = 20, LIST_Y = 88, TAB_GAP = 6;
    private static final int BAR_W = 4;
    private static String resetLabel() { return Tr.s("screen.dynamicvillages.storage.reset_view", "Reset view"); }
    private static String mayorLabel() { return Tr.s("screen.dynamicvillages.storage.back_to_mayor", "Back to Mayor"); }

    // Beds are drawn in the dye of their state; the key and list swatches match those dyes.
    private static final int CLAIMED = 0xFF80C71F;
    private static final int FREE = 0xFFF9801D;
    private static final int FULL = 0xFF6B7275;
    private static final int MISSING = 0xFFC7342B;
    private static final int RESERVED = 0xFF3AB3DA;
    private static final int OCCUPIED = 0xFF8932B8;
    /** A Mage Tower's study station and focus crystal, where the world cannot be drawn. */
    private static final int STUDY = 0xFF8C5A2B;
    private static final int CRYSTAL = 0xFFA46BE0;

    private static final float[] SELECTED_TINT = {1f, 0.9f, 0.5f};
    private static final float[] SELECTED_HOVER_TINT = {1f, 0.95f, 0.7f};
    private static final float[] HOVER_TINT = {1f, 0.95f, 0.8f};
    /** The beds that are not the chosen one, while one is chosen. */
    private static final float[] OTHER_TINT = {0.6f, 0.6f, 0.6f};
    private static final double BED_HEIGHT = 0.5625;

    private final RoomSchematicView view = new RoomSchematicView(new RoomSchematicView.Styler() {
        @Override public float[] tint(BlockPos target, boolean hovered) {
            return targetTint(target, hovered);
        }
        @Override public int boxColor(BlockPos target, int base, boolean hovered) {
            return targetColor(target, base, hovered);
        }
    });

    /** A bed head, or the door for the whole house. */
    private BlockPos selected;
    private int seenVersion;
    /** Floor levels of a house with an upstairs, as {min y, max y}; one entry for a flat house. */
    private final List<int[]> storeys = new ArrayList<>();
    /** Index into {@link #storeys}, or -1 for every floor at once. */
    private int storey = -1;
    private EditBox search;
    private String query = "";
    private int listScroll;
    /** The list's scroll bar is held by the mouse. */
    private boolean draggingBar;

    public HouseRoomScreen(HouseRoomMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 600;
        imageHeight = 350;
        titleLabelY = -1000;
        inventoryLabelY = -1000;
        selected = menu.doorPos();
        findStoreys();
        seenVersion = menu.version();
        buildRoom();
    }

    @Override protected void init() {
        imageWidth = Math.min(600, width - 16);
        imageHeight = Math.min(350, height - 16);
        super.init();
        search = addRenderableWidget(new EditBox(font, 0, 0, SIDE_W - 12, 16, Tr.c("screen.dynamicvillages.room.search_villagers", "Search villagers")));
        search.setHint(Tr.c("screen.dynamicvillages.room.search_villagers.hint", "Search villagers..."));
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            listScroll = 0;
        });
    }

    // =========================================================================
    // State
    // =========================================================================

    private boolean houseMode() {
        return menu.doorPos().equals(selected);
    }

    /** "house", "barracks", "tower" or "town hall", for the screen's wording. */
    private String roomWord() {
        return menu.isBarracks() ? Tr.s("screen.dynamicvillages.room.word.barracks", "barracks")
                : menu.isMageTower() ? Tr.s("screen.dynamicvillages.room.word.tower", "tower")
                : menu.isTownHall() ? Tr.s("screen.dynamicvillages.room.word.town_hall", "town hall")
                : Tr.s("screen.dynamicvillages.room.word.house", "house");
    }

    /** The Mayor's bed (kept for good since 10-24), or null. */
    @Nullable private BedMarker mayorBed() {
        for (BedMarker bed : menu.beds()) if (bed.status() == BedStatus.CLAIMED) return bed;
        return null;
    }

    /** The Town Hall card's short line: the Mayor's bed, or where it sleeps tonight, kept short enough for the card. */
    private String mayorBedLine() {
        BedMarker bed = mayorBed();
        MayorNight night = menu.hall().night();
        // Its bed in use: the green bed says it, the card says nothing (user, 10-24).
        if (bed != null && night != MayorNight.FLOOR) return "";
        return switch (night) {
            case AWAKE -> Tr.s("screen.dynamicvillages.room.hall.no_bed_yet_short", "Mayor: no bed yet");
            case BED -> Tr.s("screen.dynamicvillages.room.hall.in_bed_short", "Mayor: asleep in a bed");
            case FLOOR -> Tr.s("screen.dynamicvillages.room.hall.floor_short", "Mayor: on the floor");
            case WAITING -> Tr.s("screen.dynamicvillages.room.hall.waiting_short", "Mayor: no spot free");
            case UNKNOWN -> Tr.s("screen.dynamicvillages.room.hall.no_bed", "No bed taken right now");
        };
    }

    /** The Town Hall's note under the bed list: the Mayor by name, its bed, and where it spends tonight. Returns the line below. */
    private int drawHallNote(GuiGraphics graphics, int x, int y, int width) {
        String name = menu.hall().mayorName();
        BedMarker bed = mayorBed();
        MayorNight night = menu.hall().night();
        // With its bed in use the card and the green bed say it all: no note (user, 10-24).
        String note = bed != null ? (night == MayorNight.FLOOR
                        ? Tr.s("screen.dynamicvillages.room.hall.note.bed_floor", "%1$s's bed is bed %2$s, but it cannot get to it tonight, so it sleeps on the hall floor.", name, bedNumber(bed))
                        : "")
                : switch (night) {
                    case AWAKE -> Tr.s("screen.dynamicvillages.room.hall.note.no_bed_yet", "%s has no bed yet. At bedtime it takes a free bed it can walk to.", name);
                    case BED -> Tr.s("screen.dynamicvillages.room.hall.note.in_bed", "%s is asleep in a bed.", name);
                    case FLOOR -> Tr.s("screen.dynamicvillages.room.hall.note.floor", "%s sleeps on the hall floor tonight: no bed it can walk to is free.", name);
                    case WAITING -> Tr.s("screen.dynamicvillages.room.hall.note.waiting", "%s has no free bed or floor spot tonight, so it waits standing in the hall.", name);
                    case UNKNOWN -> "";
                };
        if (note.isEmpty()) return y;
        for (FormattedCharSequence line : font.split(Component.literal(note), width)) {
            if (y + font.lineHeight > sideBottom() - 4) return y;
            graphics.drawString(font, line, x, y, StorageUi.TEXT, false);
            y += font.lineHeight + 1;
        }
        return y + 2;
    }

    /** A Mage Tower study station at {@code pos}, or null. */
    @Nullable private HouseRoomMenu.StudySpot studySpot(@Nullable BlockPos pos) {
        if (pos == null) return null;
        for (HouseRoomMenu.StudySpot spot : menu.tower().spots()) if (spot.pos().equals(pos)) return spot;
        return null;
    }

    @Nullable private BedMarker bed(@Nullable BlockPos head) {
        if (head == null) return null;
        for (BedMarker bed : menu.beds()) if (bed.head().equals(head)) return bed;
        return null;
    }

    private int bedNumber(BedMarker bed) {
        return menu.beds().indexOf(bed) + 1;
    }

    @Nullable private BedMarker selectedBed() {
        return houseMode() ? null : bed(selected);
    }

    private int claimedCount() {
        int count = 0;
        for (BedMarker bed : menu.beds()) {
            if (bed.status() == BedStatus.CLAIMED || bed.status() == BedStatus.MISSING
                    || bed.status() == BedStatus.CAPTAIN) count++;
        }
        return count;
    }

    private static int statusColor(BedStatus status) {
        return switch (status) {
            case CLAIMED -> CLAIMED;
            case FREE -> FREE;
            case FULL -> FULL;
            case MISSING -> MISSING;
            case RESERVED, CAPTAIN -> RESERVED;
            case OCCUPIED -> OCCUPIED;
        };
    }

    /** Whether the player may give {@code bed} to {@code resident}; the server has the final say. */
    private boolean canTake(BedMarker bed, Resident resident) {
        if (menu.inspectOnly()) return false;
        return switch (bed.status()) {
            case CLAIMED, FREE -> true;
            case FULL -> resident.home() == HomeKind.HERE;
            case MISSING, RESERVED, OCCUPIED, CAPTAIN -> false;
        };
    }

    /** Groups floor heights into storeys: a jump of three or more blocks starts a new one. */
    private void findStoreys() {
        storeys.clear();
        TreeSet<Integer> heights = new TreeSet<>();
        for (BlockPos tile : menu.floor()) heights.add(tile.getY());
        for (BedMarker bed : menu.beds()) heights.add(bed.head().getY());
        if (heights.isEmpty()) return;
        int start = heights.first(), previous = start;
        for (int y : heights) {
            if (y - previous >= 3) {
                storeys.add(new int[] {start, previous});
                start = y;
            }
            previous = y;
        }
        storeys.add(new int[] {start, previous});
    }

    private boolean shown(BlockPos pos) {
        if (storey < 0 || storey >= storeys.size()) return true;
        int[] range = storeys.get(storey);
        return pos.getY() >= range[0] && pos.getY() <= range[1];
    }

    // =========================================================================
    // Room model
    // =========================================================================

    private void buildRoom() {
        view.clear();
        ClientLevel world = Minecraft.getInstance().level;
        boolean real = world != null && world.isLoaded(menu.doorPos());
        ClientLevel source = real ? world : null;

        List<BedMarker> beds = new ArrayList<>();
        Set<BlockPos> objects = new HashSet<>();
        for (BedMarker bed : menu.beds()) {
            if (!shown(bed.head())) continue;
            beds.add(bed);
            if (bed.foot() != null) {
                objects.add(bed.head());
                objects.add(bed.foot());
            }
        }
        // A Mage Tower's study stations and crystals: drawn as they stand, and named on hover.
        List<BlockPos> towerPieces = new ArrayList<>();
        for (HouseRoomMenu.StudySpot spot : menu.tower().spots()) if (shown(spot.pos())) towerPieces.add(spot.pos());
        for (BlockPos crystal : menu.tower().crystals()) if (shown(crystal)) towerPieces.add(crystal);
        objects.addAll(towerPieces);
        List<BlockPos> tiles = new ArrayList<>();
        for (BlockPos tile : menu.floor()) if (shown(tile)) tiles.add(tile);
        List<BlockPos> doors = shown(menu.doorPos())
                ? RoomSchematicView.doorCells(source, menu.doorPos()) : List.of();

        Set<BlockPos> floor = RoomSchematicView.fillFloor(tiles, objects);
        Set<BlockPos> inside = new HashSet<>(floor);
        inside.addAll(objects);
        inside.addAll(doors);
        for (BedMarker bed : beds) inside.add(bed.head());
        view.centerOn(inside);
        view.addShell(source, floor, inside, doors, menu.doorPos(), "D");

        // Carpets and other flat coverings lie in the cells villagers stand in.
        if (source != null) {
            for (BlockPos tile : floor) {
                if (objects.contains(tile)) continue;
                BlockState state = source.getBlockState(tile);
                if (state.isAir() || state.getShape(source, tile).isEmpty()
                        || state.getShape(source, tile).max(net.minecraft.core.Direction.Axis.Y) > 0.25) continue;
                view.addDraw(source, tile, 0, null);
            }
        }

        for (BedMarker bed : beds) addBed(source, bed, floor);
        for (BlockPos piece : towerPieces) {
            boolean station = studySpot(piece) != null;
            if (source != null && view.addDraw(source, piece, 0, piece)) {
                view.addBlockBox(piece, 0, 1, piece, station ? "S" : null);
            } else {
                view.addBox(piece.getX() + 0.1, piece.getY(), piece.getZ() + 0.1, piece.getX() + 0.9,
                        piece.getY() + 0.75, piece.getZ() + 0.9, station ? STUDY : CRYSTAL, piece,
                        station ? "S" : null, 0, true);
            }
        }
        view.finish();
    }

    private static DyeColor statusDye(BedStatus status) {
        return switch (status) {
            case CLAIMED -> DyeColor.LIME;
            case FREE -> DyeColor.ORANGE;
            case FULL -> DyeColor.GRAY;
            case MISSING -> DyeColor.RED;
            case RESERVED, CAPTAIN -> DyeColor.LIGHT_BLUE;
            case OCCUPIED -> DyeColor.PURPLE;
        };
    }

    /**
     * The bed itself, repainted in the dye of its state. A claim whose bed is gone gets a red
     * bed where it stood, lying toward the open floor beside it.
     */
    private void addBed(@Nullable ClientLevel world, BedMarker bed, Set<BlockPos> floor) {
        BlockPos head = bed.head();
        String label = Integer.toString(bedNumber(bed));
        DyeColor dye = statusDye(bed.status());
        BlockPos foot = bed.foot();
        BlockState headState = world == null ? null : world.getBlockState(head);
        BlockState footState = world == null || foot == null ? null : world.getBlockState(foot);
        if (headState == null || !(headState.getBlock() instanceof BedBlock)) {
            headState = null;
            footState = null;
            if (world != null) {
                // Nothing left to copy: lay a stand-in bed toward the floor next to it.
                Direction toFoot = null;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if (floor.contains(head.relative(dir))) {
                        toFoot = dir;
                        break;
                    }
                }
                Direction facing = toFoot != null ? toFoot.getOpposite() : Direction.NORTH;
                BlockState base = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, facing);
                headState = base.setValue(BedBlock.PART, BedPart.HEAD);
                if (toFoot != null) {
                    foot = head.relative(toFoot);
                    footState = base.setValue(BedBlock.PART, BedPart.FOOT);
                }
            }
        } else if (footState != null && !(footState.getBlock() instanceof BedBlock)) {
            footState = null;
        }
        BlockPos other = foot != null && footState != null ? foot : head;
        double x0 = Math.min(head.getX(), other.getX()), x1 = Math.max(head.getX(), other.getX()) + 1;
        double z0 = Math.min(head.getZ(), other.getZ()), z1 = Math.max(head.getZ(), other.getZ()) + 1;
        double y = head.getY();
        if (headState == null) {
            // No world to draw from: a flat box in the state's colour.
            view.addBox(x0 + 0.03, y, z0 + 0.03, x1 - 0.03, y + BED_HEIGHT, z1 - 0.03,
                    statusColor(bed.status()), head, label, 0, true);
            return;
        }
        view.addStandIn(head, standIn(world, head, headState, dye), head);
        if (footState != null) view.addStandIn(foot, standIn(world, foot, footState, dye), head);
        view.addBox(x0, y, z0, x1, y + BED_HEIGHT, z1, 0, head, label, 0, false);
        view.addShadowFootprint(x0, y, z0, x1, z1);
    }

    private static BedBlockEntity standIn(ClientLevel world, BlockPos pos, BlockState state, DyeColor dye) {
        BedBlockEntity bed = new BedBlockEntity(pos, state, dye);
        bed.setLevel(world);
        return bed;
    }

    private float[] targetTint(BlockPos target, boolean hovered) {
        boolean chosen = target.equals(selected);
        if (chosen) {
            // The door lights up for the whole house; a chosen bed keeps its true colour.
            if (houseMode()) return hovered ? SELECTED_HOVER_TINT : SELECTED_TINT;
            return RoomSchematicView.NO_TINT;
        }
        if (hovered) return HOVER_TINT;
        return !houseMode() && bed(target) != null ? OTHER_TINT : RoomSchematicView.NO_TINT;
    }

    private int targetColor(BlockPos target, int base, boolean hovered) {
        if (target.equals(selected)) return RoomSchematicView.lighten(RoomSchematicView.lighten(base));
        return hovered ? RoomSchematicView.lighten(base) : base;
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
    private int headerY() { return topPos + 4; }
    private boolean fromMayor() { return menu.mayorId() >= 0; }
    private int mayorWidth() { return 26 + font.width(mayorLabel()); }
    private int mayorX() { return resetX() - TAB_GAP - mayorWidth(); }
    /** Where the floor tabs must stop: before the Mayor button when there is one, else before Reset. */
    private int tabsRight() { return (fromMayor() ? mayorX() : resetX()) - TAB_GAP; }

    private int tabCount() { return storeys.size() > 1 ? storeys.size() + 1 : 0; }
    private String tabLabel(int tab) {
        return tab == 0 ? Tr.s("screen.dynamicvillages.room.all_floors", "All floors") : Tr.s("screen.dynamicvillages.room.floor", "Floor %s", tab);
    }
    private int tabWidth(int tab) { return font.width(tabLabel(tab)) + 16; }
    private int tabX(int tab) {
        int x = leftPos + PAD + 20 + font.width(title) + TAB_GAP * 2;
        for (int i = 0; i < tab; i++) x += tabWidth(i) + TAB_GAP;
        return x;
    }

    /** The list under the card: beds in house mode, villagers for a bed. */
    private int listTop() { return sideTop() + (houseMode() ? 64 : LIST_Y); }
    private int listBottom() {
        if (houseMode()) {
            // Every bed gets a row when the panel is tall enough; the notes take what is left.
            int fit = Math.max(1, (sideBottom() - 4 - listTop()) / ROW_H);
            return listTop() + Math.min(Math.max(1, menu.beds().size()), fit) * ROW_H;
        }
        BedMarker bed = selectedBed();
        return sideBottom() - (bed != null && bed.owner() != null ? 22 : 6) - (shownMessage().isEmpty() ? 0 : 20);
    }
    private int listRows() { return Math.max(1, (listBottom() - listTop()) / ROW_H); }

    /** Whether the list holds more rows than it shows, and so has a scroll bar. */
    private boolean listOverflows() { return listShown() && listSize() > listRows(); }
    /** Rows stop short of the scroll bar when there is one. */
    private int rowWidth() { return contentWidth() - (listOverflows() ? BAR_W + 2 : 0); }
    private int barX() { return contentX() + contentWidth() - BAR_W; }
    /** The bar stops where the last row ends, not at the gap under it. */
    private int barHeight() { return listRows() * ROW_H - 2; }
    /** Same thumb size as {@link StorageUi#scrollbar}, so dragging matches what is drawn. */
    private int barThumb() {
        int total = Math.max(1, listSize());
        return Math.min(barHeight(), Math.max(18, barHeight() * listRows() / total));
    }

    /** Moves the list so the scroll bar's thumb centres on the cursor. */
    private void scrollToMouse(double mouseY) {
        int max = Math.max(0, listSize() - listRows());
        int travel = barHeight() - barThumb();
        double along = (mouseY - listTop() - barThumb() / 2.0) / Math.max(1, travel);
        listScroll = Mth.clamp((int) Math.round(along * max), 0, max);
    }

    private void drawListBar(GuiGraphics graphics) {
        if (!listOverflows()) return;
        StorageUi.scrollbar(graphics, barX(), listTop(), barHeight(), listScroll, listSize() - listRows(), listRows());
    }

    private List<Resident> filteredResidents() {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Resident> result = new ArrayList<>();
        for (Resident resident : menu.residents()) {
            if (needle.isEmpty() || resident.name().toLowerCase(Locale.ROOT).contains(needle)
                    || resident.profession().toLowerCase(Locale.ROOT).contains(needle)) result.add(resident);
        }
        return result;
    }

    private int listSize() {
        return houseMode() ? menu.beds().size() : filteredResidents().size();
    }

    /** Index into the current list under the cursor, or -1. */
    private int rowAt(double mouseX, double mouseY) {
        if (!listShown() || !inside(mouseX, mouseY, contentX(), listTop(), rowWidth(), listRows() * ROW_H)) {
            return -1;
        }
        int index = listScroll + (int) ((mouseY - listTop()) / ROW_H);
        return index < listSize() ? index : -1;
    }

    private boolean listShown() {
        BedMarker bed = selectedBed();
        return houseMode() || !menu.inspectOnly() && bed != null && bed.status() != BedStatus.MISSING;
    }

    private boolean freeButtonShown() {
        BedMarker bed = selectedBed();
        return !menu.inspectOnly() && bed != null && bed.owner() != null;
    }

    private int freeButtonY() { return sideBottom() - 20; }

    /**
     * The server's last refusal, or else, for a player who may only look (village claims round 7a), who may change
     * beds here. Barracks and Town Hall keep their own words: nobody hands out their beds but the Mayor's.
     */
    private String shownMessage() {
        if (!menu.message().isEmpty() || menu.mayChange()) return menu.message();
        if (menu.isBarracks()) return "";
        return Tr.s("screen.dynamicvillages.room.claim_look_only",
                "Look only: %s has not let you change beds here.", menu.lockOwner());
    }

    /** A Town Hall bed that is not the Mayor's: "Give to the Mayor" sits where Free sits for a house. */
    private boolean hallGiveShown() {
        BedMarker bed = selectedBed();
        return menu.isTownHall() && bed != null
                && (bed.status() == BedStatus.FREE || bed.status() == BedStatus.FULL || bed.status() == BedStatus.OCCUPIED);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        BedMarker bed = selectedBed();
        search.visible = !menu.inspectOnly() && bed != null && bed.status() != BedStatus.MISSING;
        if (!search.visible) search.setFocused(false);

        graphics.fill(x - 2, y - 2, x + imageWidth + 2, y + imageHeight + 2, StorageUi.OUTER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, StorageUi.PANEL);
        graphics.renderFakeItem(new ItemStack(menu.isMageTower() ? Items.ENCHANTING_TABLE
                : menu.isTownHall() ? Items.BELL : Items.RED_BED), x + PAD, y + 6);
        graphics.drawString(font, title, x + PAD + 20, y + 10, StorageUi.TEXT, false);
        for (int tab = 0; tab < tabCount(); tab++) {
            int tx = tabX(tab);
            if (tx + tabWidth(tab) > tabsRight()) break;
            headerButton(graphics, tx, tabWidth(tab), null, tabLabel(tab), storey == tab - 1,
                    inside(mouseX, mouseY, tx, headerY(), tabWidth(tab), 20));
        }
        headerButton(graphics, resetX(), resetWidth(), Items.COMPASS, resetLabel(), false,
                inside(mouseX, mouseY, resetX(), headerY(), resetWidth(), 20));
        if (fromMayor()) {
            headerButton(graphics, mayorX(), mayorWidth(), Items.WRITABLE_BOOK, mayorLabel(), false,
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
        x = keyEntry(graphics, x, y, CLAIMED, Tr.s("screen.dynamicvillages.room.key.claimed", "Claimed"));
        x = keyEntry(graphics, x, y, FREE, Tr.s("screen.dynamicvillages.room.free", "Free"));
        // "Full" in a barracks leaves room for its extra Captain entry.
        x = keyEntry(graphics, x, y, FULL, menu.isBarracks() ? Tr.s("screen.dynamicvillages.room.key.full", "Full")
                : menu.isMageTower() ? Tr.s("screen.dynamicvillages.room.tower_full", "Tower full")
                : menu.isTownHall() ? Tr.s("screen.dynamicvillages.room.hall.spare", "Spare")
                : Tr.s("screen.dynamicvillages.room.house_full", "House full"));
        x = keyEntry(graphics, x, y, MISSING, Tr.s("screen.dynamicvillages.room.key.missing", "Missing"));
        if (menu.isBarracks()) x = keyEntry(graphics, x, y, RESERVED, Tr.s("screen.dynamicvillages.room.key.captain", "Captain"));
        if (menu.beds().stream().anyMatch(bed -> bed.status() == BedStatus.OCCUPIED)) {
            x = keyEntry(graphics, x, y, OCCUPIED, Tr.s("screen.dynamicvillages.room.occupied", "Occupied"));
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

    /** Header controls: bordered, icon and label centred, green when selected. */
    private void headerButton(GuiGraphics graphics, int x, int width, @Nullable Item icon, String label,
                              boolean on, boolean hover) {
        int y = headerY();
        graphics.fill(x, y, x + width, y + 20, hover && !on ? StorageUi.BUTTON_HOVER_BORDER : StorageUi.PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 19,
                on ? StorageUi.ROW_SELECTED : hover ? StorageUi.ROW_HOVER : StorageUi.ROW);
        if (on) graphics.fill(x + 1, y + 17, x + width - 1, y + 19, StorageUi.ACCENT);
        int iconWidth = icon == null ? 0 : 20;
        int content = x + (width - iconWidth - font.width(label)) / 2;
        if (icon != null) graphics.renderFakeItem(new ItemStack(icon), content, y + 2);
        graphics.drawString(font, label, content + iconWidth, y + 6, StorageUi.TEXT, false);
    }

    private void renderSidePanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = contentX(), width = contentWidth(), y = sideTop() + 6;
        BedMarker bed = selectedBed();
        ItemStack icon;
        String name, detail, extra;
        int accent;
        if (bed == null) {
            icon = new ItemStack(Items.OAK_DOOR);
            name = Tr.s("screen.dynamicvillages.room.whole", "Whole %s", roomWord());
            detail = menu.isBarracks() ? Tr.s("screen.dynamicvillages.room.residents", "Residents: %1$s/%2$s", claimedCount(), menu.capacity())
                    : menu.isMageTower() ? Tr.s("screen.dynamicvillages.room.mages_sleeping", "Mages sleeping here: %1$s/%2$s", claimedCount(), menu.capacity())
                    : menu.isTownHall() ? mayorBedLine()
                    : Tr.s("screen.dynamicvillages.room.beds_claimed", "%1$s of %2$s beds claimed", claimedCount(), menu.capacity());
            extra = menu.houseValid() ? Tr.s("screen.dynamicvillages.room.beds_recognised", "%s beds recognised", menu.recognisedBeds())
                    : Tr.s("screen.dynamicvillages.room.unavailable", "Room unavailable now");
            accent = StorageUi.ACCENT;
        } else {
            icon = new ItemStack(bedItem(bed));
            name = Tr.s("screen.dynamicvillages.room.bed", "Bed %s", bedNumber(bed));
            detail = switch (bed.status()) {
                case CLAIMED -> bed.ownerName();
                case FREE -> Tr.s("screen.dynamicvillages.room.free", "Free");
                case FULL -> menu.isBarracks() ? Tr.s("screen.dynamicvillages.room.no_soldier_slots", "No soldier slots left")
                        : menu.isTownHall() ? Tr.s("screen.dynamicvillages.room.hall.spare", "Spare")
                        : Tr.s("screen.dynamicvillages.room.free_but_full", "Free, %s is full", roomWord());
                case MISSING -> Tr.s("screen.dynamicvillages.room.missing_owner", "Missing: %s", bed.ownerName());
                case RESERVED -> Tr.s("screen.dynamicvillages.room.reserved_captain", "Reserved for captain");
                case OCCUPIED -> Tr.s("screen.dynamicvillages.room.in_use_unclaimed", "In use, not claimed");
                case CAPTAIN -> Tr.s("screen.dynamicvillages.room.captain_owner", "%s (captain)", bed.ownerName());
            };
            // Short enough for the card (about 128 px): the old "Click here for the whole town hall" was cut off.
            extra = menu.isBarracks() ? Tr.s("screen.dynamicvillages.room.show_barracks", "Show all barracks beds")
                    : Tr.s("screen.dynamicvillages.room.show_whole", "Show the whole %s", roomWord());
            accent = statusColor(bed.status());
        }
        boolean cardHover = bed != null && inside(mouseX, mouseY, x, y, width, CARD_H);
        StorageUi.row(graphics, x, y, width, CARD_H, false, cardHover);
        graphics.fill(x + 1, y + 1, x + 3, y + CARD_H - 1, accent);
        graphics.renderFakeItem(icon, x + 8, y + 12);
        graphics.drawString(font, trim(name, width - 36), x + 30, y + 6, StorageUi.TEXT, false);
        graphics.drawString(font, trim(detail, width - 36), x + 30, y + 17, StorageUi.MUTED, false);
        graphics.drawString(font, trim(extra, width - 36), x + 30, y + 28, StorageUi.MUTED, false);

        if (bed == null) {
            renderBedList(graphics, mouseX, mouseY);
            int noteY = drawSpaceNote(graphics, x, listBottom() + 6, width);
            if (menu.isMageTower()) noteY = drawTowerNote(graphics, x, noteY, width);
            if (menu.isTownHall()) noteY = drawHallNote(graphics, x, noteY, width);
            String hint = menu.isBarracks()
                    ? (menu.captainSlotReserved() ? Tr.s("screen.dynamicvillages.room.hint.barracks_reserved", "One bed and resident slot are reserved for the captain.") + " " : "")
                            + Tr.s("screen.dynamicvillages.room.hint.barracks", "Maximum 10 residents, including the captain. Click a bed to inspect its claim.")
                    : menu.isMageTower()
                    ? Tr.s("screen.dynamicvillages.room.hint.tower", "Only mages sleep here, up to %s. A mage moves in when a bed is free and keeps it. Click a bed to choose which mage sleeps there.", menu.capacity())
                    : menu.isTownHall()
                    ? Tr.s("screen.dynamicvillages.room.hint.town_hall_give", "Only the Mayor sleeps here. Click a bed to give it to the Mayor.")
                    : Tr.s("screen.dynamicvillages.room.hint.house","Villagers claim a free bed the first night they need one and keep it. Click a bed to choose who sleeps there.");
            // The bed list comes first: a hint that would be cut off is left out instead.
            int hintLines = font.split(Component.literal(hint), width).size();
            if (noteY + 3 + (hintLines - 1) * (font.lineHeight + 1) + font.lineHeight <= sideBottom() - 4) {
                drawNote(graphics, hint, x, noteY + 3, width);
            }
            return;
        }
        if (menu.isBarracks()) {
            drawNote(graphics, switch (bed.status()) {
                case CLAIMED -> Tr.s("screen.dynamicvillages.room.barracks.claimed", "This bed belongs to %s. The claim stays between sleep periods.", bed.ownerName());
                case FREE -> Tr.s("screen.dynamicvillages.room.barracks.free", "Available for a captain, guard or archer when they can reach it.");
                case FULL -> menu.captainSlotReserved()
                        ? Tr.s("screen.dynamicvillages.room.barracks.full_reserved", "The nine soldier slots are taken. The tenth slot is kept for the captain.")
                        : Tr.s("screen.dynamicvillages.room.barracks.full", "This barracks already has 10 residents. Extra beds do not increase its capacity.");
                case MISSING -> Tr.s("screen.dynamicvillages.room.barracks.missing", "This resident's bed is gone or outside the recognised room. Restore the bed or provide another available bed.");
                case RESERVED -> Tr.s("screen.dynamicvillages.room.barracks.reserved", "Kept for the captain so a later recruit can move in without displacing a soldier.");
                case OCCUPIED -> Tr.s("screen.dynamicvillages.room.barracks.occupied", "Someone is sleeping here. It cannot be allocated until they leave.");
                case CAPTAIN -> Tr.s("screen.dynamicvillages.room.barracks.captain", "The captain's bed. It belongs to %s and stays theirs between sleep periods.", bed.ownerName());
            }, x, sideTop() + 52, width);
            return;
        }
        if (menu.isTownHall()) {
            drawNote(graphics, switch (bed.status()) {
                case CLAIMED -> Tr.s("screen.dynamicvillages.room.hall.claimed_name", "This is %s's bed.", bed.ownerName());
                case FULL -> Tr.s("screen.dynamicvillages.room.hall.spare.give", "Spare. The Mayor already has a bed, and nobody else sleeps in the Town Hall. Press Give to the Mayor and it sleeps here instead.");
                case MISSING -> Tr.s("screen.dynamicvillages.room.hall.missing", "%s's bed is gone. At bedtime the Mayor picks another free bed.", bed.ownerName());
                case OCCUPIED -> Tr.s("screen.dynamicvillages.room.hall.occupied", "Someone is lying in this bed, so the Mayor will not take it.");
                // A Town Hall has no captain, so RESERVED and CAPTAIN never come here.
                case FREE, RESERVED, CAPTAIN -> Tr.s("screen.dynamicvillages.room.hall.free", "Free. At bedtime the Mayor takes a free bed it can walk to.");
            }, x, sideTop() + 52, width);
            if (!shownMessage().isEmpty()) drawNote(graphics, shownMessage(), x, freeButtonY() - 20, width, StorageUi.DENIED);
            if (hallGiveShown()) {
                StorageUi.button(graphics, font, x, freeButtonY(), width, 14,
                        Tr.s("screen.dynamicvillages.room.hall.give", "Give to the Mayor"),
                        inside(mouseX, mouseY, x, freeButtonY(), width, 14),
                        menu.hall().mayorId() != null && menu.mayChange());
            }
            return;
        }
        if (bed.status() == BedStatus.MISSING) {
            drawNote(graphics, Tr.s("screen.dynamicvillages.room.missing.desc", "%1$s's bed is gone, or no longer inside the %2$s. Put a bed back here, or free the claim so they pick another bed at bedtime.", bed.ownerName(), roomWord()),
                    x, sideTop() + 52, width);
        } else {
            String heading = bed.status() == BedStatus.FULL
                    ? (menu.isMageTower() ? Tr.s("screen.dynamicvillages.room.move_mage_here", "Move a mage of this tower here")
                            : Tr.s("screen.dynamicvillages.room.move_villager_here", "Move a villager of this house here"))
                    : menu.isMageTower() ? Tr.s("screen.dynamicvillages.room.give_to_mage", "Give this bed to a mage") : Tr.s("screen.dynamicvillages.room.give_to", "Give this bed to");
            graphics.drawString(font, trim(heading, width), x, sideTop() + 52, StorageUi.TEXT, false);
            search.setX(x);
            search.setY(sideTop() + 64);
            search.setWidth(width);
            renderResidentList(graphics, bed, mouseX, mouseY);
        }
        if (!shownMessage().isEmpty()) {
            int messageY = (freeButtonShown() ? freeButtonY() : sideBottom()) - 20;
            drawNote(graphics, shownMessage(), x, messageY, width, StorageUi.DENIED);
        }
        if (freeButtonShown()) {
            String label = bed.status() == BedStatus.MISSING ? Tr.s("screen.dynamicvillages.room.free_claim", "Free the claim") : Tr.s("screen.dynamicvillages.room.free_bed", "Free this bed");
            StorageUi.button(graphics, font, x, freeButtonY(), width, 14, label,
                    inside(mouseX, mouseY, x, freeButtonY(), width, 14), menu.mayChange());
        }
    }

    private void renderBedList(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = contentX(), width = rowWidth();
        graphics.drawString(font, Tr.s("screen.dynamicvillages.room.beds", "Beds"), x, sideTop() + 52, StorageUi.TEXT, false);
        List<BedMarker> beds = menu.beds();
        if (beds.isEmpty()) {
            graphics.drawString(font, Tr.s("screen.dynamicvillages.room.no_beds", "No beds recognised"), x, listTop() + 4, StorageUi.MUTED, false);
            return;
        }
        int rows = listRows();
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, beds.size() - rows));
        for (int row = 0; row < rows && listScroll + row < beds.size(); row++) {
            BedMarker bed = beds.get(listScroll + row);
            int ry = listTop() + row * ROW_H;
            boolean hover = inside(mouseX, mouseY, x, ry, width, ROW_H - 2);
            StorageUi.row(graphics, x, ry, width, ROW_H - 2, false, hover);
            graphics.fill(x + 3, ry + 5, x + 11, ry + 13, statusColor(bed.status()));
            graphics.drawString(font, Tr.s("screen.dynamicvillages.room.bed", "Bed %s", bedNumber(bed)), x + 16, ry + 5, StorageUi.TEXT, false);
            String who = switch (bed.status()) {
                case CLAIMED -> bed.ownerName();
                case FREE -> Tr.s("screen.dynamicvillages.room.free", "Free");
                case FULL -> menu.isBarracks() ? Tr.s("screen.dynamicvillages.room.slots_full", "Slots full")
                        : menu.isMageTower() ? Tr.s("screen.dynamicvillages.room.tower_full", "Tower full")
                        : menu.isTownHall() ? Tr.s("screen.dynamicvillages.room.hall.spare", "Spare")
                        : Tr.s("screen.dynamicvillages.room.house_full", "House full");
                case MISSING -> Tr.s("screen.dynamicvillages.room.owner_missing", "%s (missing)", bed.ownerName());
                case RESERVED -> Tr.s("screen.dynamicvillages.room.for_captain", "For captain");
                case CAPTAIN -> Tr.s("screen.dynamicvillages.room.captain_owner", "%s (captain)", bed.ownerName());
                case OCCUPIED -> Tr.s("screen.dynamicvillages.room.in_use_unclaimed", "In use, not claimed");
            };
            int whoX = x + 16 + font.width(Tr.s("screen.dynamicvillages.room.bed", "Bed %s", "00")) + 8;
            graphics.drawString(font, trim(who, x + width - 4 - whoX), whoX, ry + 5,
                    bed.status() == BedStatus.CLAIMED || bed.status() == BedStatus.CAPTAIN ? StorageUi.TEXT : StorageUi.MUTED, false);
        }
        drawListBar(graphics);
    }

    private void renderResidentList(GuiGraphics graphics, BedMarker bed, int mouseX, int mouseY) {
        int x = contentX(), width = rowWidth();
        List<Resident> residents = filteredResidents();
        if (residents.isEmpty()) {
            graphics.drawString(font, menu.residents().isEmpty()
                            ? (menu.isMageTower() ? Tr.s("screen.dynamicvillages.room.no_mages", "No mages in the village")
                                    : Tr.s("screen.dynamicvillages.room.no_villagers", "No villagers in the village"))
                            : Tr.s("screen.dynamicvillages.room.no_match", "No villager matches"),
                    x, listTop() + 4, StorageUi.MUTED, false);
            return;
        }
        int rows = listRows();
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, residents.size() - rows));
        for (int row = 0; row < rows && listScroll + row < residents.size(); row++) {
            Resident resident = residents.get(listScroll + row);
            int ry = listTop() + row * ROW_H;
            boolean owner = resident.id().equals(bed.owner());
            boolean allowed = canTake(bed, resident);
            boolean hover = allowed && !owner && inside(mouseX, mouseY, x, ry, width, ROW_H - 2);
            graphics.fill(x, ry, x + width, ry + ROW_H - 2,
                    hover ? StorageUi.BUTTON_HOVER_BORDER : StorageUi.PANEL_BORDER);
            graphics.fill(x + 1, ry + 1, x + width - 1, ry + ROW_H - 3, owner ? StorageUi.ROW_SELECTED
                    : !allowed ? StorageUi.ROW_DISABLED : hover ? StorageUi.ROW_HOVER : StorageUi.ROW);
            if (owner) graphics.fill(x + 1, ry + ROW_H - 4, x + width - 1, ry + ROW_H - 2, StorageUi.ACCENT);
            graphics.renderFakeItem(new ItemStack(resident.icon()), x + 1, ry + 1);
            String tag = homeTag(resident);
            int tagColor = resident.home() == HomeKind.HERE ? CLAIMED
                    : resident.home() == HomeKind.NONE ? StorageUi.GOLD : StorageUi.MUTED;
            int tagWidth = font.width(tag);
            graphics.drawString(font, tag, x + width - 4 - tagWidth, ry + 5, tagColor, false);
            graphics.drawString(font, trim(resident.name(), width - 26 - tagWidth), x + 19, ry + 5,
                    allowed || owner ? StorageUi.TEXT : StorageUi.MUTED, false);
        }
        drawListBar(graphics);
    }

    private String homeTag(Resident resident) {
        return switch (resident.home()) {
            case HERE -> {
                BedMarker own = bed(resident.bed());
                yield own != null ? Tr.s("screen.dynamicvillages.room.bed", "Bed %s", bedNumber(own)) : Tr.s("screen.dynamicvillages.room.home.here", "Here");
            }
            case NONE -> Tr.s("screen.dynamicvillages.room.home.none", "No bed");
            case ELSEWHERE -> Tr.s("screen.dynamicvillages.room.home.elsewhere", "Elsewhere");
        };
    }

    /** The card's icon: a bed in the same colour as in the view. */
    private static Item bedItem(BedMarker bed) {
        return switch (bed.status()) {
            case CLAIMED -> Items.LIME_BED;
            case FREE -> Items.ORANGE_BED;
            case FULL -> Items.GRAY_BED;
            case MISSING -> Items.RED_BED;
            case RESERVED, CAPTAIN -> Items.LIGHT_BLUE_BED;
            case OCCUPIED -> Items.PURPLE_BED;
        };
    }

    /**
     * A crowding warning, only when there is one: red when more live here
     * than fit, gold when its beds would crowd it once all are taken. Returns
     * the line below it, or {@code y} when all is well or for a barracks.
     */
    private int drawSpaceNote(GuiGraphics graphics, int x, int y, int width) {
        // Defenders sleep in shifts, so a barracks' (or tower's) beds are never all slept in at once.
        // Only the Mayor sleeps in the Town Hall.
        if (menu.isBarracks() || menu.isMageTower() || menu.isTownHall()) return y;
        int fits = CrowdingBalance.fits(menu.space());
        if (fits == 0) return y;
        int living = claimedCount();
        int beds = Math.min(menu.capacity(), menu.recognisedBeds());
        String note;
        int color;
        if (living > fits) {
            note = CrowdingBalance.crampedLine(living) + " " + CrowdingBalance.fixLine(menu.lowCeilingTiles());
            color = StorageUi.DENIED;
        } else if (beds > fits) {
            note = CrowdingBalance.bedsLine(beds) + " " + CrowdingBalance.fixLine(menu.lowCeilingTiles());
            color = StorageUi.GOLD;
        } else {
            return y;
        }
        for (FormattedCharSequence line : font.split(Component.literal(note), width)) {
            if (y + font.lineHeight > sideBottom() - 4) break;
            graphics.drawString(font, line, x, y, color, false);
            y += font.lineHeight + 1;
        }
        return y;
    }

    /**
     * The Mage Tower's study: how fast mages learn here, from how many
     * bookshelves, and who studies at its stations now. Returns the line below.
     */
    private int drawTowerNote(GuiGraphics graphics, int x, int y, int width) {
        HouseRoomMenu.TowerInfo tower = menu.tower();
        List<String> lines = new ArrayList<>();
        String more = tower.bookshelves() < com.alex.test.village.building.MageTowerBuilding.MAX_USEFUL_BOOKSHELVES
                ? " " + Tr.s("screen.dynamicvillages.room.tower.more_shelves", "More shelves, up to %s, make it faster.", com.alex.test.village.building.MageTowerBuilding.MAX_USEFUL_BOOKSHELVES)
                : "";
        lines.add(Tr.s("screen.dynamicvillages.room.tower.study_speed", "Study speed %1$s%% from %2$s bookshelves.", tower.studyPercent(), tower.bookshelves()) + more);
        List<String> studying = new ArrayList<>();
        for (HouseRoomMenu.StudySpot spot : tower.spots()) if (!spot.user().isEmpty()) studying.add(spot.user());
        lines.add(studying.isEmpty()
                ? Tr.s("screen.dynamicvillages.room.tower.spots_idle", "Study spots: %s, nobody studying now.", tower.spots().size())
                : Tr.s("screen.dynamicvillages.room.tower.spots_busy", "Study spots: %1$s. Studying: %2$s.", tower.spots().size(), String.join(", ", studying)));
        for (String line : lines) {
            for (FormattedCharSequence part : font.split(Component.literal(line), width)) {
                if (y + font.lineHeight > sideBottom() - 4) return y;
                graphics.drawString(font, part, x, y, StorageUi.TEXT, false);
                y += font.lineHeight + 1;
            }
            y += 2;
        }
        return y;
    }

    private void drawNote(GuiGraphics graphics, String note, int x, int y, int width) {
        drawNote(graphics, note, x, y, width, StorageUi.MUTED);
    }

    private void drawNote(GuiGraphics graphics, String note, int x, int y, int width, int color) {
        for (FormattedCharSequence line : font.split(Component.literal(note), width)) {
            if (y + font.lineHeight > sideBottom() - 4) break;
            graphics.drawString(font, line, x, y, color, false);
            y += font.lineHeight + 1;
        }
    }

    private String trim(String value, int width) {
        return StorageUi.trim(font, value, width);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (menu.version() != seenVersion) {
            seenVersion = menu.version();
            if (!houseMode() && bed(selected) == null) selected = menu.doorPos();
            findStoreys();
            if (storey >= storeys.size()) storey = -1;
            buildRoom();
        }
        // super.render draws the background, and with it the view, once.
        view.setViewport(viewLeft(), viewTop(), viewRight(), viewBottom());
        view.setHovered(view.isDragging() ? null : view.pick(mouseX, mouseY));
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);

        int row = rowAt(mouseX, mouseY);
        if (row >= 0 && !houseMode()) {
            Resident resident = filteredResidents().get(row);
            BedMarker bed = selectedBed();
            List<Component> lines = new ArrayList<>(List.of(Component.literal(resident.name()),
                    Component.literal(resident.profession()).withStyle(ChatFormatting.GRAY),
                    Component.literal(switch (resident.home()) {
                        case HERE -> Tr.s("screen.dynamicvillages.room.sleeps_here", "Sleeps in this %s", roomWord());
                        case NONE -> Tr.s("screen.dynamicvillages.room.no_bed_yet", "Has no bed yet");
                        case ELSEWHERE -> menu.isMageTower() ? Tr.s("screen.dynamicvillages.room.sleeps_elsewhere", "Sleeps somewhere else")
                                : Tr.s("screen.dynamicvillages.room.sleeps_other_house", "Sleeps in another house");
                    }).withStyle(ChatFormatting.GRAY)));
            if (bed != null && resident.id().equals(bed.owner())) {
                lines.add(Tr.c("screen.dynamicvillages.room.their_bed", "This is their bed").withStyle(ChatFormatting.GREEN));
            } else if (bed != null && canTake(bed, resident)) {
                lines.add((bed.owner() != null && resident.home() == HomeKind.HERE
                        ? Tr.c("screen.dynamicvillages.room.click_swap", "Click to swap beds with %s", bed.ownerName())
                        : Tr.c("screen.dynamicvillages.room.click_give", "Click to give them this bed"))
                        .withStyle(ChatFormatting.DARK_GRAY));
            } else {
                lines.add((menu.isMageTower() ? Tr.c("screen.dynamicvillages.room.tower_is_full", "Tower is full") : Tr.c("screen.dynamicvillages.room.house_is_full", "House is full"))
                        .withStyle(ChatFormatting.GOLD));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
        if (hallGiveShown() && inside(mouseX, mouseY, contentX(), freeButtonY(), contentWidth(), 14)) {
            if (!menu.mayChange()) {
                graphics.renderComponentTooltip(font, List.of(Tr.c("screen.dynamicvillages.room.claim_look_only.tip",
                        "%s has not let you change beds here.", menu.lockOwner())), mouseX, mouseY);
                return;
            }
            graphics.renderComponentTooltip(font, menu.hall().mayorId() != null
                    ? List.of(Tr.c("screen.dynamicvillages.room.hall.give.tip1", "The Mayor gets this bed for good."),
                            Tr.c("screen.dynamicvillages.room.hall.give.tip2", "Its old bed comes free.").withStyle(ChatFormatting.GRAY))
                    : List.of(Tr.c("screen.dynamicvillages.room.hall.give.off1", "The Mayor is not nearby."),
                            Tr.c("screen.dynamicvillages.room.hall.give.off2", "Come closer to the Town Hall to change its bed.")
                                    .withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
            return;
        }
        BlockPos hovered = view.hovered();
        if (hovered == null) return;
        if (hovered.equals(menu.doorPos())) {
            graphics.renderComponentTooltip(font, List.of(Tr.c("screen.dynamicvillages.room.door", "Door"),
                    Tr.c("screen.dynamicvillages.room.click_whole_short", "Click for the whole %s", roomWord())
                            .withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
            return;
        }
        HouseRoomMenu.StudySpot spot = studySpot(hovered);
        if (spot != null) {
            graphics.renderComponentTooltip(font, List.of(
                    spot.lectern() ? Tr.c("screen.dynamicvillages.room.tower.spot_lectern", "Study spot (lectern)")
                            : Tr.c("screen.dynamicvillages.room.tower.spot_table", "Study spot (enchanting table)"),
                    (spot.user().isEmpty() ? Tr.c("screen.dynamicvillages.room.tower.spot_free", "Free: one mage studies here at a time")
                            : Tr.c("screen.dynamicvillages.room.tower.spot_busy", "%s is studying here", spot.user())).withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
            return;
        }
        if (menu.tower().crystals().contains(hovered)) {
            graphics.renderComponentTooltip(font, List.of(Tr.c("screen.dynamicvillages.room.tower.crystal", "Focus crystal"),
                    Tr.c("screen.dynamicvillages.room.tower.crystal.desc", "Glows while a mage studies").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
            return;
        }
        BedMarker bed = bed(hovered);
        if (bed == null) return;
        String state = switch (bed.status()) {
            case CLAIMED -> menu.isTownHall() ? Tr.s("screen.dynamicvillages.room.tip.hall_claimed_kept", "%s's bed", bed.ownerName())
                    : Tr.s("screen.dynamicvillages.room.tip.claimed", "Belongs to %s", bed.ownerName());
            case FREE -> menu.isBarracks() ? Tr.s("screen.dynamicvillages.room.tip.free_barracks", "Available for a captain, guard or archer")
                    : menu.isMageTower() ? Tr.s("screen.dynamicvillages.room.tip.free_tower", "Free: the next mage without a tower bed takes it")
                    : menu.isTownHall() ? Tr.s("screen.dynamicvillages.room.tip.free_hall", "Free: the Mayor takes one at bedtime")
                    : Tr.s("screen.dynamicvillages.room.tip.free_house", "Free: the next villager without a bed takes it");
            case FULL -> menu.isBarracks() ? (menu.captainSlotReserved()
                    ? Tr.s("screen.dynamicvillages.room.tip.full_reserved", "Soldier slots full; captain slot reserved")
                    : Tr.s("screen.dynamicvillages.room.tip.barracks_full", "Barracks full (10 of 10)"))
                    : menu.isTownHall() ? Tr.s("screen.dynamicvillages.room.tip.spare_hall_kept", "Spare: the Mayor already has a bed")
                    : Tr.s("screen.dynamicvillages.room.tip.full","Free, but the %1$s is full (%2$s of %3$s)", roomWord(), claimedCount(), menu.capacity());
            case MISSING -> Tr.s("screen.dynamicvillages.room.tip.missing", "%s's bed is gone", bed.ownerName());
            case RESERVED -> Tr.s("screen.dynamicvillages.room.tip.reserved", "Reserved for the captain");
            case OCCUPIED -> Tr.s("screen.dynamicvillages.room.tip.occupied", "Someone is sleeping here");
            case CAPTAIN -> Tr.s("screen.dynamicvillages.room.tip.captain", "Captain %s's bed", bed.ownerName());
        };
        List<Component> lines = new ArrayList<>(List.of(Tr.c("screen.dynamicvillages.room.bed", "Bed %s", bedNumber(bed)),
                Component.literal(state).withStyle(ChatFormatting.GRAY)));
        if (menu.isTownHall()) {
            // A hall with several beds looks like a bedroom for anyone; say plainly whose beds they are.
            lines.add(Tr.c("screen.dynamicvillages.room.tip.hall_only_mayor", "Only the Mayor sleeps in the Town Hall.")
                    .withStyle(ChatFormatting.GOLD));
            lines.add(Tr.c("screen.dynamicvillages.room.tip.hall_villagers", "Villagers sleep in their houses.")
                    .withStyle(ChatFormatting.GOLD));
        }
        lines.add((menu.isTownHall() && bed.status() != BedStatus.CLAIMED && bed.status() != BedStatus.MISSING
                ? Tr.c("screen.dynamicvillages.room.tip.click_hall_give", "Click to give this bed to the Mayor")
                : menu.inspectOnly() ? Tr.c("screen.dynamicvillages.room.tip.click_inspect", "Click to inspect this bed")
                : Tr.c("screen.dynamicvillages.room.tip.click_choose", "Click to choose who sleeps here"))
                .withStyle(ChatFormatting.DARK_GRAY));
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Everything is drawn in screen coordinates by renderBg.
    }

    // =========================================================================
    // Input
    // =========================================================================

    private void select(BlockPos target) {
        if (target.equals(selected)) return;
        selected = target;
        listScroll = 0;
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Typing in the search box must not close the screen (E) or trigger other keys.
        if (getFocused() instanceof EditBox box && box.isVisible()
                && keyCode != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            box.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (getFocused() instanceof EditBox box && !box.isMouseOver(mouseX, mouseY)) setFocused(null);
        if (button == 0) {
            for (int tab = 0; tab < tabCount(); tab++) {
                if (inside(mouseX, mouseY, tabX(tab), headerY(), tabWidth(tab), 20)) {
                    storey = tab - 1;
                    buildRoom();
                    return true;
                }
            }
            if (inside(mouseX, mouseY, resetX(), headerY(), resetWidth(), 20)) {
                view.resetCamera();
                return true;
            }
            if (fromMayor() && inside(mouseX, mouseY, mayorX(), headerY(), mayorWidth(), 20)) {
                MayorStatsScreen.returnToBuilding(menu.mayorId(), menu.frameId());
                PacketDistributor.sendToServer(new MayorBuildingPayloads.BackToMayor(menu.mayorId()));
                return true;
            }
            if (listOverflows() && inside(mouseX, mouseY, barX() - 1, listTop(), BAR_W + 2, barHeight())) {
                draggingBar = true;
                scrollToMouse(mouseY);
                return true;
            }
            if (clickSidePanel(mouseX, mouseY)) return true;
        }
        if (view.beginDrag(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean clickSidePanel(double mouseX, double mouseY) {
        int x = contentX(), width = contentWidth();
        BedMarker bed = selectedBed();
        if (bed != null && inside(mouseX, mouseY, x, sideTop() + 6, width, CARD_H)) {
            select(menu.doorPos());
            return true;
        }
        if (freeButtonShown() && inside(mouseX, mouseY, x, freeButtonY(), width, 14)) {
            if (menu.mayChange()) PacketDistributor.sendToServer(new HouseRoomPayloads.Unassign(menu.frameId(), bed.owner()));
            return true;
        }
        if (hallGiveShown() && inside(mouseX, mouseY, x, freeButtonY(), width, 14)) {
            UUID mayorId = menu.hall().mayorId();
            if (mayorId != null && menu.mayChange()) {
                PacketDistributor.sendToServer(new HouseRoomPayloads.Assign(menu.frameId(), bed.head(), mayorId));
            }
            return true;
        }
        int row = rowAt(mouseX, mouseY);
        if (row < 0) return false;
        if (houseMode()) {
            select(menu.beds().get(row).head());
            return true;
        }
        Resident resident = filteredResidents().get(row);
        if (bed != null && menu.mayChange() && canTake(bed, resident) && !resident.id().equals(bed.owner())) {
            PacketDistributor.sendToServer(new HouseRoomPayloads.Assign(menu.frameId(), bed.head(), resident.id()));
        }
        return true;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingBar) {
            scrollToMouse(mouseY);
            return true;
        }
        if (view.drag(button, dragX, dragY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingBar && button == 0) {
            draggingBar = false;
            return true;
        }
        if (view.endDrag(button)) {
            BlockPos hit = view.pick(mouseX, mouseY);
            // Only a bed or the door is chosen; a study spot or crystal only names itself on hover.
            if (hit != null && (hit.equals(menu.doorPos()) || bed(hit) != null)) select(hit);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (listShown() && inside(mouseX, mouseY, contentX(), listTop(), contentWidth(), listRows() * ROW_H)) {
            listScroll = Math.max(0, listScroll + (scrollY > 0 ? -1 : 1));
            return true;
        }
        if (view.zoomAt(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
