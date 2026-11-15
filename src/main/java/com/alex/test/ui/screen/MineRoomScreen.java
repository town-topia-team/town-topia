package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.network.MayorBuildingPayloads;
import com.alex.test.network.MineRoomPayloads;
import com.alex.test.ui.MineRoomMenu;
import com.alex.test.ui.screen.mayor.MayorStatsScreen;
import com.alex.test.village.villagers.miner.MineSettings;
import com.alex.test.village.villagers.miner.MineShaftPlan;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One mine: its status, and its own settings (side tunnels, main tunnel, pause)
 * in a panel beside a 3D view of the mine. Each click sends the new settings; the
 * panel shows them at once and the server's answer follows. When the window is too
 * short for them (a big GUI scale), the settings above the Pause button scroll.
 */
public final class MineRoomScreen extends AbstractContainerScreen<MineRoomMenu> {
    private static final int PAD = 8, HEADER_H = 28, FOOTER_H = 16, SIDE_W = 196, CARD_H = 40;
    private static final int LINE = 10, LABEL_W = 66, TAB_GAP = 6;
    private static final int STEP_H = 14, STEP_BUTTON = 16, STEP_VALUE = 66;
    private static final int PAUSE_H = 18;
    private static final int BAR_W = 4, SCROLL_STEP = 12;

    private static final int PAUSED = 0xFFE6A23C;
    private static final int DRUID = 0xFF5DAE8B;
    private static final int IDLE = 0xFF6B7275;

    // View colours. Box colours are drawn solid, so rock still to dig is a dark tile, not a see-through one.
    private static final int UNDUG = 0xFF2E2E2E;
    private static final int ORE = 0xFFD9A441;
    private static final int NEXT_MINE = 0xFFD9534F;
    private static final int BORDER = 0xFFE6C84C;
    private static final int WATER = 0xFF3F76E4;
    private static final int LAVA = 0xFFE07B26;
    private static final int MINER = 0xFF8B5A2B;
    private static final float[] HOVER_TINT = {1f, 0.95f, 0.8f};
    private static final float[] WATER_TINT = {0.55f, 0.7f, 1f};
    private static final float[] WATER_HOVER_TINT = {0.7f, 0.82f, 1f};
    private static final float[] LAVA_TINT = {1f, 0.62f, 0.35f};
    private static final float[] LAVA_HOVER_TINT = {1f, 0.75f, 0.5f};

    // Rock behind the panels: vanilla deepslate, one texture pixel two GUI pixels wide, darkened so
    // each panel keeps its old flat colour on average (deepslate averages 80 grey).
    private static final ResourceLocation ROCK = ResourceLocation.withDefaultNamespace("textures/block/deepslate.png");
    private static final int ROCK_TILE = 32;
    private static final int ROCK_SHADE = 0xCC141414;      // about StorageUi.PANEL
    private static final int ROCK_SHADE_DARK = 0xD5080808; // about StorageUi.PANEL_DARK
    // 1 px edges that make panels and buttons look raised or set in; see-through, so any fill works.
    private static final int EDGE_LIGHT = 0x1CFFFFFF;
    private static final int EDGE_DARK = 0x59000000;
    private static final int WELL = 0x80000000;

    private static String mayorLabel() { return Tr.s("screen.dynamicvillages.storage.back_to_mayor", "Back to Mayor"); }
    private static String resetLabel() { return Tr.s("screen.dynamicvillages.storage.reset_view", "Reset view"); }
    private static String entranceLabel() { return Tr.s("screen.dynamicvillages.mine.view.entrance", "Entrance"); }

    /** A control or line drawn this frame: where it is, what a click does, what hovering says. */
    private record Hit(int x, int y, int width, int height, @Nullable Runnable action, List<Component> tooltip) {}

    /** One colour in the key under the view. */
    private record KeyEntry(int color, String label) {}

    /** The colour key, wrapped to the view's width; laid out at the start of every frame. */
    private List<List<KeyEntry>> keyLines = List.of();

    /** Rebuilt every frame by the side panel; clicks and tooltips read the last frame's. */
    private final List<Hit> hits = new ArrayList<>();
    /** Only the part of a control inside this band is recorded: the settings' visible band while they are drawn. */
    private int hitTop = Integer.MIN_VALUE, hitBottom = Integer.MAX_VALUE;

    /** What each thing in the view says on hover, by the block it stands for. */
    private final Map<BlockPos, List<Component>> viewTips = new HashMap<>();
    /** Sealed blocks in the view, true for lava: they are tinted blue or orange. */
    private final Map<BlockPos, Boolean> sealTargets = new HashMap<>();

    private final RoomSchematicView view = new RoomSchematicView(new RoomSchematicView.Styler() {
        @Override public float[] tint(BlockPos target, boolean hovered) {
            Boolean lava = sealTargets.get(target);
            if (lava == null) return hovered ? HOVER_TINT : RoomSchematicView.NO_TINT;
            if (lava) return hovered ? LAVA_HOVER_TINT : LAVA_TINT;
            return hovered ? WATER_HOVER_TINT : WATER_TINT;
        }
        @Override public int boxColor(BlockPos target, int base, boolean hovered) {
            return hovered ? RoomSchematicView.lighten(base) : base;
        }
    });

    private int seenVersion;
    /** The snapshot the view was last built from, and whether from the client's own blocks. */
    @Nullable private MineRoomMenu.Snapshot builtFrom;
    private boolean builtReal;
    /** How far the side panel's settings are scrolled up, in GUI pixels. */
    private int panelScroll;
    /** Height of the scrolling part of the side panel, measured when it was last drawn. */
    private int panelContentHeight;
    /** The side panel's scroll bar is held by the mouse. */
    private boolean draggingBar;

    public MineRoomScreen(MineRoomMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 600;
        imageHeight = 350;
        titleLabelY = -1000;
        inventoryLabelY = -1000;
        seenVersion = menu.version();
        buildMine();
    }

    @Override protected void init() {
        imageWidth = Math.min(600, width - 16);
        imageHeight = Math.min(350, height - 16);
        super.init();
    }

    // =========================================================================
    // Layout
    // =========================================================================

    private int viewLeft() { return leftPos + PAD + 1; }
    private int viewTop() { return topPos + HEADER_H + 1; }
    private int viewRight() { return sideLeft() - 7; }
    /** One line shorter for each extra line the colour key needs on a narrow window. */
    private int viewBottom() { return topPos + imageHeight - PAD - FOOTER_H - 1 - LINE * Math.max(0, keyLines.size() - 1); }
    private int sideLeft() { return leftPos + imageWidth - PAD - SIDE_W; }
    private int sideTop() { return topPos + HEADER_H; }
    private int sideBottom() { return topPos + imageHeight - PAD; }
    private int contentX() { return sideLeft() + 6; }
    /** Narrower while the settings scroll, so nothing runs under the scroll bar. */
    private int contentWidth() { return SIDE_W - 12 - (panelScrolls() ? BAR_W + 2 : 0); }
    private int pauseY() { return sideBottom() - 6 - PAUSE_H; }
    /**
     * The settings scroll between the panel's top edge and here, so on a big GUI scale none of
     * them ends up under the Pause button or below the window. The error line and Pause stay put.
     */
    private int scrollBottom() { return pauseY() - (shownMessage().isEmpty() ? 4 : 24); }
    private int panelScrollMax() { return Math.max(0, panelContentHeight - (scrollBottom() - sideTop())); }
    private boolean panelScrolls() { return panelScrollMax() > 0; }
    private int barX() { return contentX() + SIDE_W - 12 - BAR_W; }
    private int barTop() { return sideTop() + 6; }
    private int barHeight() { return Math.max(1, scrollBottom() - 2 - barTop()); }
    /** Same thumb size as {@link StorageUi#scrollbar}, so dragging matches what is drawn. */
    private int barThumb() {
        int visible = scrollBottom() - sideTop();
        int total = Math.max(1, visible + panelScrollMax());
        return Math.min(barHeight(), Math.max(18, barHeight() * visible / total));
    }

    /** Moves the settings so the scroll bar's thumb centres on the cursor. */
    private void scrollToMouse(double mouseY) {
        int max = panelScrollMax();
        int travel = barHeight() - barThumb();
        double along = (mouseY - barTop() - barThumb() / 2.0) / Math.max(1, travel);
        panelScroll = Mth.clamp((int) Math.round(along * max), 0, max);
    }
    private int headerY() { return topPos + 4; }
    private boolean fromMayor() { return menu.mayorId() >= 0; }
    private int mayorWidth() { return 26 + font.width(mayorLabel()); }
    private int resetWidth() { return 26 + font.width(resetLabel()); }
    private int resetX() { return leftPos + imageWidth - PAD - resetWidth(); }
    private int mayorX() { return resetX() - TAB_GAP - mayorWidth(); }

    // =========================================================================
    // Wording
    // =========================================================================

    private static String statusLine(MineRoomMenu.Snapshot mine) {
        if (mine.settings().paused()) return Tr.s("screen.dynamicvillages.mine.status.paused", "Paused: nobody works here");
        if (!mine.valid()) return Tr.s("screen.dynamicvillages.mine.status.invalid", "Not a working mine right now");
        if (!mine.holder().isEmpty()) {
            return mine.holderDruid()
                    ? Tr.s("screen.dynamicvillages.mine.status.druid", "Druid %s is regrowing it", mine.holder())
                    : Tr.s("screen.dynamicvillages.mine.status.worked_by", "Worked by %s", mine.holder());
        }
        if (mine.workedOut()) return Tr.s("screen.dynamicvillages.mine.status.worked_out", "Worked out");
        return Tr.s("screen.dynamicvillages.mine.status.free", "No miner here now");
    }

    private static int statusColor(MineRoomMenu.Snapshot mine) {
        if (mine.settings().paused()) return PAUSED;
        if (!mine.valid()) return StorageUi.DENIED;
        if (!mine.holder().isEmpty()) return mine.holderDruid() ? DRUID : StorageUi.ACCENT;
        return IDLE;
    }

    private static String progressLine(MineRoomMenu.Snapshot mine) {
        if (!mine.loaded()) return Tr.s("screen.dynamicvillages.mine.not_loaded", "Not loaded: too far away");
        return Tr.s("screen.dynamicvillages.mine.dug", "Dug %1$s of %2$s", mine.dug().size(), mine.planCells());
    }

    private static String sideValue(int length) {
        return length == 0 ? Tr.s("screen.dynamicvillages.mine.none", "None")
                : length == 1 ? Tr.s("screen.dynamicvillages.mine.one_block", "1 block")
                : Tr.s("screen.dynamicvillages.mine.blocks", "%s blocks", length);
    }

    private static String mainValue(int length) {
        return length >= MineShaftPlan.MAX_MAIN_LENGTH
                ? Tr.s("screen.dynamicvillages.mine.all_the_way", "All the way")
                : Tr.s("screen.dynamicvillages.mine.blocks", "%s blocks", length);
    }

    // =========================================================================
    // Settings
    // =========================================================================

    private MineSettings settings() {
        return menu.snapshot().settings();
    }

    private void send(MineSettings next) {
        if (!menu.rights().mayChange()) return;
        MineSettings clamped = next.clamped();
        if (clamped.equals(settings().clamped())) return;
        menu.showSettings(clamped);
        PacketDistributor.sendToServer(new MineRoomPayloads.SetSettings(menu.frameId(), clamped));
    }

    private void followVillage(boolean follow) {
        MineSettings now = settings();
        if (now.sidesFollowVillage() == follow) return;
        // "This mine only" starts from the village's number.
        int sides = menu.snapshot().villageSides();
        send(follow ? new MineSettings(true, now.left(), now.right(), now.mainLength(), now.paused())
                : new MineSettings(false, sides, sides, now.mainLength(), now.paused()));
    }

    private void setLeft(int left) {
        MineSettings now = settings();
        send(new MineSettings(false, left, now.right(), now.mainLength(), now.paused()));
    }

    private void setRight(int right) {
        MineSettings now = settings();
        send(new MineSettings(false, now.left(), right, now.mainLength(), now.paused()));
    }

    private void setMain(int main) {
        MineSettings now = settings();
        send(new MineSettings(now.sidesFollowVillage(), now.left(), now.right(), main, now.paused()));
    }

    private void setPaused(boolean paused) {
        MineSettings now = settings();
        send(new MineSettings(now.sidesFollowVillage(), now.left(), now.right(), now.mainLength(), paused));
    }

    // =========================================================================
    // Mine model
    // =========================================================================

    /**
     * The mine as the view draws it: the dug tunnels' real floor, no walls and no roof;
     * the ore and sealed blocks the tunnels show, standing whole; dark tiles where rock
     * is still to be dug; posts where a tunnel stops early; the villagers working there. Where the
     * client has no blocks (too far away) the same as flat boxes. Nothing here loads a chunk.
     */
    private void buildMine() {
        MineRoomMenu.Snapshot mine = menu.snapshot();
        ClientLevel world = Minecraft.getInstance().level;
        ClientLevel source = mine.loaded() && world != null && world.isLoaded(mine.mouth()) ? world : null;
        // Only villagers moved: keep the recorded blocks, as recording them again is slow.
        if (sameBlocks(builtFrom, mine) && builtReal == (source != null)) view.clearKeepingBlocks();
        else view.clear();
        builtFrom = mine;
        builtReal = source != null;
        viewTips.clear();
        sealTargets.clear();
        if (!mine.loaded()) {
            view.finish();
            return;
        }

        BlockPos mouth = mine.mouth();
        Set<BlockPos> floor = new LinkedHashSet<>(mine.dug());
        Set<BlockPos> inside = new HashSet<>(floor);
        inside.add(mouth);
        List<BlockPos> planned = new ArrayList<>(mine.dug());
        planned.addAll(mine.undug());
        planned.add(mouth);
        view.centerOn(planned);
        // No walls (user 10-18: too many blocks): around a tunnel every block is rock, so the
        // rooms' cut-down rim turned the whole mine into one grey slab. Only the tunnel floors.
        view.addShell(source, floor, inside, List.of(mouth), mouth, entranceLabel(), false);
        tip(mouth, Component.literal(entranceLabel()),
                Tr.lines("screen.dynamicvillages.mine.view.entrance.tip", "Where the miners go in and out."));

        for (BlockPos cell : mine.undug()) {
            view.addBox(cell.getX(), cell.getY() - 0.18, cell.getZ(), cell.getX() + 1, cell.getY() - 0.03,
                    cell.getZ() + 1, UNDUG, null, null, 0, true);
        }
        for (BlockPos ore : mine.ores()) {
            // Ore under the floor is drawn by the floor as well; this tinted copy is queued later,
            // so it is drawn over it.
            if (source != null && view.addDraw(source, ore, 0, ore)) view.addBlockBox(ore, 0, 1, ore, null);
            else marker(ore, floor, ORE);
            BlockState state = source != null ? source.getBlockState(ore) : null;
            tip(ore, state != null && !state.isAir() ? state.getBlock().getName()
                            : Tr.c("screen.dynamicvillages.mine.view.ore", "Ore"),
                    Tr.lines("screen.dynamicvillages.mine.view.ore.tip", "The tunnel has uncovered this ore."));
        }
        for (MineRoomMenu.Seal seal : mine.seals()) {
            BlockPos pos = seal.pos();
            sealTargets.put(pos, seal.lava());
            if (source != null && view.addDraw(source, pos, 0, pos)) view.addBlockBox(pos, 0, 1, pos, null);
            else marker(pos, floor, seal.lava() ? LAVA : WATER);
            tip(pos, seal.lava() ? Tr.c("screen.dynamicvillages.mine.view.sealed_lava", "Sealed lava")
                            : Tr.c("screen.dynamicvillages.mine.view.sealed_water", "Sealed water"),
                    seal.lava() ? Tr.lines("screen.dynamicvillages.mine.view.sealed_lava.tip", "Cobblestone holds back lava here.")
                            : Tr.lines("screen.dynamicvillages.mine.view.sealed_water.tip", "Cobblestone holds back water here."));
        }
        for (MineShaftPlan.End end : mine.ends()) {
            BlockPos at = end.at();
            boolean nextMine = end.why() == MineShaftPlan.Stop.NEXT_MINE;
            view.addBox(at.getX() + 0.38, at.getY(), at.getZ() + 0.38, at.getX() + 0.62, at.getY() + 1.3,
                    at.getZ() + 0.62, nextMine ? NEXT_MINE : BORDER, at, null, 0, true);
            tip(at, nextMine ? Tr.c("screen.dynamicvillages.mine.view.stop_next_mine", "Stops here: the next mine's rock")
                            : Tr.c("screen.dynamicvillages.mine.view.stop_border", "Stops here: the village border"),
                    nextMine ? Tr.lines("screen.dynamicvillages.mine.view.stop_next_mine.tip",
                                    "This rock is closer to another mine,\nso that mine's miners dig it.")
                            : Tr.lines("screen.dynamicvillages.mine.view.stop_border.tip",
                                    "Past the village border,\nminers have nowhere to stand."));
        }
        // Last, so a villager's own words win when one stands on the entrance.
        for (MineRoomMenu.Worker worker : mine.workers()) {
            BlockPos at = worker.pos();
            view.addBox(at.getX() + 0.2, at.getY(), at.getZ() + 0.2, at.getX() + 0.8, at.getY() + 1.8,
                    at.getZ() + 0.8, worker.druid() ? DRUID : MINER, at, worker.name(), 0, true);
            tip(at, Component.literal(worker.name()), worker.druid()
                    ? Tr.lines("screen.dynamicvillages.mine.view.worker_druid", "Druid, regrowing the rock")
                    : Tr.lines("screen.dynamicvillages.mine.view.worker_miner", "Miner, working this mine"));
        }
        view.finish();
    }

    /** True when both snapshots give the view the same blocks (villagers may differ). */
    private static boolean sameBlocks(@Nullable MineRoomMenu.Snapshot was, MineRoomMenu.Snapshot now) {
        return was != null && was.loaded() == now.loaded() && Objects.equals(was.mouth(), now.mouth())
                && was.dug().equals(now.dug()) && was.undug().equals(now.undug())
                && was.ores().equals(now.ores()) && was.seals().equals(now.seals());
    }

    /** A coloured stand-in for a block the client cannot show; a plate on the floor when it lies under it. */
    private void marker(BlockPos pos, Set<BlockPos> floor, int color) {
        double y0 = floor.contains(pos.above()) ? 0.96 : 0.2;
        double y1 = floor.contains(pos.above()) ? 1.03 : 0.8;
        view.addBox(pos.getX() + 0.2, pos.getY() + y0, pos.getZ() + 0.2, pos.getX() + 0.8, pos.getY() + y1,
                pos.getZ() + 0.8, color, pos, null, 0, true);
    }

    /** The hover text for a thing in the view: its name, then grey detail lines. Later calls win. */
    private void tip(BlockPos target, Component title, List<Component> detail) {
        List<Component> lines = new ArrayList<>();
        lines.add(title);
        for (Component line : detail) lines.add(line.copy().withStyle(ChatFormatting.GRAY));
        viewTips.put(target.immutable(), lines);
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        graphics.fill(x - 2, y - 2, x + imageWidth + 2, y + imageHeight + 2, StorageUi.OUTER);
        rock(graphics, x, y, x + imageWidth, y + imageHeight, ROCK_SHADE);
        raised(graphics, x, y, x + imageWidth, y + imageHeight);
        graphics.renderFakeItem(new ItemStack(Items.IRON_PICKAXE), x + PAD, y + 6);
        graphics.drawString(font, title, x + PAD + 20, y + 10, StorageUi.TEXT, true);
        headerButton(graphics, resetX(), resetWidth(), Items.COMPASS, resetLabel(),
                StorageUi.inside(mouseX, mouseY, resetX(), headerY(), resetWidth(), 20));
        if (fromMayor()) {
            headerButton(graphics, mayorX(), mayorWidth(), Items.WRITABLE_BOOK, mayorLabel(),
                    StorageUi.inside(mouseX, mouseY, mayorX(), headerY(), mayorWidth(), 20));
        }

        sunken(graphics, viewLeft() - 2, viewTop() - 2, viewRight() + 2, viewBottom() + 2);
        renderView(graphics);

        rock(graphics, sideLeft(), sideTop(), sideLeft() + SIDE_W, sideBottom(), ROCK_SHADE_DARK);
        sunken(graphics, sideLeft(), sideTop(), sideLeft() + SIDE_W, sideBottom());
        renderSidePanel(graphics, mouseX, mouseY);
    }

    /**
     * Deepslate tiled over a box and darkened. Lined up with the window's corner, so the side panel
     * carries on the main panel's rock face instead of starting a new one.
     */
    private void rock(GuiGraphics graphics, int x0, int y0, int x1, int y1, int shade) {
        graphics.blit(ROCK, x0, y0, x0 - leftPos, y0 - topPos, x1 - x0, y1 - y0, ROCK_TILE, ROCK_TILE);
        graphics.fill(x0, y0, x1, y1, shade);
    }

    /** A 1 px edge round a box: lit along the top and left, shaded along the bottom and right. */
    private static void raised(GuiGraphics graphics, int x0, int y0, int x1, int y1) {
        edges(graphics, x0, y0, x1, y1, EDGE_LIGHT, EDGE_DARK);
    }

    /** The other way round: the box looks set into the panel. */
    private static void sunken(GuiGraphics graphics, int x0, int y0, int x1, int y1) {
        edges(graphics, x0, y0, x1, y1, EDGE_DARK, EDGE_LIGHT);
    }

    private static void edges(GuiGraphics graphics, int x0, int y0, int x1, int y1, int topLeft, int bottomRight) {
        graphics.fill(x0, y0, x1, y0 + 1, topLeft);
        graphics.fill(x0, y0 + 1, x0 + 1, y1, topLeft);
        graphics.fill(x0 + 1, y1 - 1, x1, y1, bottomRight);
        graphics.fill(x1 - 1, y0 + 1, x1, y1 - 1, bottomRight);
    }

    /**
     * Soft light and shade inside a bordered button or card. A pressed one (the chosen half of a
     * switch) only gets the shade on top, so it looks pushed in; its green bar below stays clean.
     */
    private static void bevel(GuiGraphics graphics, int x, int y, int width, int height, boolean pressed) {
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, pressed ? EDGE_DARK : EDGE_LIGHT);
        if (!pressed) graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, EDGE_DARK);
    }

    /** A section name with an engraved line running on to the panel's edge; hovering it explains the section. */
    private void heading(GuiGraphics graphics, String label, int x, int y, int width, List<Component> tooltip) {
        graphics.drawString(font, label, x, y, StorageUi.TEXT, false);
        hit(x, y - 1, width, 10, null, tooltip);
        int lineX = x + font.width(label) + 6;
        if (lineX >= x + width) return;
        graphics.fill(lineX, y + 4, x + width, y + 5, EDGE_DARK);
        graphics.fill(lineX, y + 5, x + width, y + 6, EDGE_LIGHT);
    }

    /** The 3D view of the mine with its colour key, or the note when the mine is too far away. */
    private void renderView(GuiGraphics graphics) {
        view.setViewport(viewLeft(), viewTop(), viewRight(), viewBottom());
        view.render(graphics, font);
        renderKey(graphics);
        if (!menu.snapshot().loaded()) {
            String note = Tr.s("screen.dynamicvillages.mine.view_not_loaded",
                    "The mine is too far away to show. Its settings still work.");
            int width = viewRight() - viewLeft() - 24;
            List<FormattedCharSequence> lines = font.split(Component.literal(note), width);
            int lineY = (viewTop() + viewBottom()) / 2 - lines.size() * LINE / 2;
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, (viewLeft() + viewRight() - font.width(line)) / 2, lineY,
                        StorageUi.MUTED, false);
                lineY += LINE;
            }
        }
    }

    /** The colour key's entries: only what this mine shows. */
    private List<KeyEntry> keyEntries() {
        MineRoomMenu.Snapshot mine = menu.snapshot();
        List<KeyEntry> entries = new ArrayList<>();
        if (!mine.loaded()) return entries;
        entries.add(new KeyEntry(RoomSchematicView.DOOR, entranceLabel()));
        if (!mine.undug().isEmpty()) entries.add(new KeyEntry(UNDUG, Tr.s("screen.dynamicvillages.mine.key.undug", "Not dug yet")));
        if (mine.ends().stream().anyMatch(end -> end.why() == MineShaftPlan.Stop.NEXT_MINE)) {
            entries.add(new KeyEntry(NEXT_MINE, Tr.s("screen.dynamicvillages.mine.key.next_mine", "Next mine")));
        }
        if (mine.ends().stream().anyMatch(end -> end.why() == MineShaftPlan.Stop.BORDER)) {
            entries.add(new KeyEntry(BORDER, Tr.s("screen.dynamicvillages.mine.key.border", "Village border")));
        }
        if (mine.seals().stream().anyMatch(seal -> !seal.lava())) {
            entries.add(new KeyEntry(WATER, Tr.s("screen.dynamicvillages.mine.view.sealed_water", "Sealed water")));
        }
        if (mine.seals().stream().anyMatch(MineRoomMenu.Seal::lava)) {
            entries.add(new KeyEntry(LAVA, Tr.s("screen.dynamicvillages.mine.view.sealed_lava", "Sealed lava")));
        }
        if (mine.workers().stream().anyMatch(worker -> !worker.druid())) {
            entries.add(new KeyEntry(MINER, Tr.s("screen.dynamicvillages.mine.key.miner", "Miner")));
        }
        if (mine.workers().stream().anyMatch(MineRoomMenu.Worker::druid)) {
            entries.add(new KeyEntry(DRUID, Tr.s("screen.dynamicvillages.mine.key.druid", "Druid")));
        }
        return entries;
    }

    /** The key's entries in lines as wide as the view: a narrow window gets more lines instead of losing entries. */
    private List<List<KeyEntry>> layoutKey() {
        List<List<KeyEntry>> lines = new ArrayList<>();
        List<KeyEntry> line = new ArrayList<>();
        int room = viewRight() - viewLeft(), x = 0;
        for (KeyEntry entry : keyEntries()) {
            int width = 11 + font.width(entry.label());
            if (!line.isEmpty() && x + width > room) {
                lines.add(line);
                line = new ArrayList<>();
                x = 0;
            }
            line.add(entry);
            x += width + 10;
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    /**
     * The colour key under the view; the camera controls after it when its last line has room
     * (the Reset view button's hover text says them too).
     */
    private void renderKey(GuiGraphics graphics) {
        if (keyLines.isEmpty()) return;
        int room = viewRight() - viewLeft(), x = viewLeft(), y = viewBottom() + 5;
        for (int i = 0; i < keyLines.size(); i++) {
            if (i > 0) y += LINE;
            x = viewLeft();
            for (KeyEntry entry : keyLines.get(i)) {
                x = keyEntry(graphics, x, y, entry.color(), trim(entry.label(), room - 11));
            }
        }
        String hint = Tr.s("screen.dynamicvillages.storage.view_hint", "Drag: rotate • Right-drag: pan • Scroll: zoom");
        if (x + 8 + font.width(hint) <= viewRight()) {
            graphics.drawString(font, hint, viewRight() - font.width(hint), y, StorageUi.MUTED, false);
        }
    }

    /** One key entry: a bordered swatch (the dark ones vanish otherwise) and its name. */
    private int keyEntry(GuiGraphics graphics, int x, int y, int color, String label) {
        graphics.fill(x, y, x + 8, y + 8, StorageUi.PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + 7, y + 7, color);
        graphics.drawString(font, label, x + 11, y, StorageUi.MUTED, false);
        return x + 11 + font.width(label) + 10;
    }

    /** Header button: bordered, icon and label centred, as the room screens. */
    private void headerButton(GuiGraphics graphics, int x, int width, Item icon, String label, boolean hover) {
        int y = headerY();
        graphics.fill(x, y, x + width, y + 20, hover ? StorageUi.BUTTON_HOVER_BORDER : StorageUi.PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 19, hover ? StorageUi.ROW_HOVER : StorageUi.ROW);
        bevel(graphics, x, y, width, 20, false);
        int content = x + (width - 20 - font.width(label)) / 2;
        graphics.renderFakeItem(new ItemStack(icon), content, y + 2);
        graphics.drawString(font, label, content + 20, y + 6, StorageUi.TEXT, false);
    }

    /** Records a control; only its part inside the visible band counts, so a hidden one takes no click or hover. */
    private void hit(int x, int y, int width, int height, @Nullable Runnable action, List<Component> tooltip) {
        int top = Math.max(y, hitTop), bottom = Math.min(y + height, hitBottom);
        if (bottom > top) hits.add(new Hit(x, top, width, bottom - top, action, tooltip));
    }

    private void renderSidePanel(GuiGraphics graphics, int mouseX, int mouseY) {
        hits.clear();
        MineRoomMenu.Snapshot mine = menu.snapshot();
        panelScroll = Mth.clamp(panelScroll, 0, panelScrollMax());
        int bandTop = sideTop() + 1, bandBottom = scrollBottom();
        // Settings scrolled out of the band are clipped and never light up under the mouse.
        int hoverY = mouseY >= bandTop && mouseY < bandBottom ? mouseY : Integer.MIN_VALUE / 2;
        int y = sideTop() + 6 - panelScroll;
        hitTop = bandTop;
        hitBottom = bandBottom;
        graphics.enableScissor(sideLeft() + 1, bandTop, sideLeft() + SIDE_W - 1, bandBottom);
        try {
            y = renderSettings(graphics, mine, y, mouseX, hoverY);
        } finally {
            graphics.disableScissor();
            hitTop = Integer.MIN_VALUE;
            hitBottom = Integer.MAX_VALUE;
        }
        panelContentHeight = y - (sideTop() - panelScroll);
        panelScroll = Mth.clamp(panelScroll, 0, panelScrollMax());
        if (panelScrolls()) {
            StorageUi.scrollbar(graphics, barX(), barTop(), barHeight(), panelScroll, panelScrollMax(),
                    scrollBottom() - sideTop());
        }

        int x = contentX(), width = SIDE_W - 12, pauseY = pauseY();
        if (!shownMessage().isEmpty()) {
            drawNote(graphics, shownMessage(), x, pauseY - 22, width, StorageUi.DENIED, sideBottom() - 4);
        }
        boolean paused = mine.settings().paused();
        button(graphics, x, pauseY, width, PAUSE_H,
                paused ? Tr.s("screen.dynamicvillages.mine.resume", "Resume this mine")
                        : Tr.s("screen.dynamicvillages.mine.pause", "Pause this mine"),
                true, mouseX, mouseY, () -> setPaused(!paused),
                paused ? Tr.lines("screen.dynamicvillages.mine.resume.tip",
                                "Let miners and druids work this mine again.")
                        : Tr.lines("screen.dynamicvillages.mine.pause.tip",
                                "Stop all work in this mine.\nMiners walk out and go to another mine,\nand druids stop regrowing it.\nNothing is mined here while you are away."));
    }

    /** The status card and every setting above the Pause button, from {@code y} down. Returns the line below. */
    private int renderSettings(GuiGraphics graphics, MineRoomMenu.Snapshot mine, int y, int mouseX, int mouseY) {
        MineSettings settings = mine.settings();
        int x = contentX(), width = contentWidth();

        StorageUi.row(graphics, x, y, width, CARD_H, false, false);
        bevel(graphics, x, y, width, CARD_H, false);
        graphics.fill(x + 1, y + 1, x + 3, y + CARD_H - 1, statusColor(mine));
        graphics.renderFakeItem(new ItemStack(Items.IRON_PICKAXE), x + 8, y + 12);
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mine.name", "Mine at Y %s", mine.shaftY()), width - 36),
                x + 30, y + 6, StorageUi.TEXT, false);
        graphics.drawString(font, trim(statusLine(mine), width - 36), x + 30, y + 17, StorageUi.MUTED, false);
        graphics.drawString(font, trim(progressLine(mine), width - 36), x + 30, y + 28, StorageUi.MUTED, false);
        y += CARD_H + 6;

        int top = y;
        y = drawRow(graphics, Tr.s("screen.dynamicvillages.mine.main_ores", "Main ores") + "\t" + mine.oresHere(),
                x, y, width);
        hit(x, top, width, y - top, null, Tr.lines("screen.dynamicvillages.mine.main_ores.tip",
                "The ores most common at this depth.\nMiners keep any ore their tunnels find, and plain\nstone now and then gives a bonus ore from this depth.\nOther ores are rare here: put a mine at another depth."));
        top = y;
        for (String row : mine.tunnel()) y = drawRow(graphics, row, x, y, width);
        if (y > top) {
            hit(x, top, width, y - top, null, Tr.lines("screen.dynamicvillages.mine.tunnels_now.tip",
                    "How far the tunnels really go.\nThe settings below are the most they go: the village\nborder or a nearer mine's rock can stop them sooner."));
        }
        y += 6;

        heading(graphics, Tr.s("screen.dynamicvillages.mine.side_tunnels", "Side tunnels"), x, y, width,
                Tr.lines("screen.dynamicvillages.mine.side_tunnels.tip",
                        "Short tunnels off the main tunnel, one every\n3 blocks, to the left and to the right.\nLonger side tunnels reach more ore."));
        y += 12;
        // One above the other: side by side, "Mayor's length: 12 blocks" does not fit.
        segment(graphics, x, y, width, Tr.s("screen.dynamicvillages.mine.mayor_length", "Mayor's length: %s", sideValue(mine.villageSides())),
                settings.sidesFollowVillage(), mouseX, mouseY, () -> followVillage(true),
                Tr.lines("screen.dynamicvillages.mine.mayor_length.tip",
                        "Use the side tunnel length set at the Mayor\n(Settings, Professions, Miner).\nChange it there and this mine changes too."));
        y += 18;
        segment(graphics, x, y, width, Tr.s("screen.dynamicvillages.mine.own_length", "Own length for this mine"),
                !settings.sidesFollowVillage(), mouseX, mouseY, () -> followVillage(false),
                Tr.lines("screen.dynamicvillages.mine.own_length.tip",
                        "Give this mine shorter side tunnels of its own,\nup to the Mayor's length. Left and right are\nas you look into the rock from the token."));
        y += 20;
        // The Mayor's number is the most a mine's own sides get (MineSettings.resolve).
        int max = Math.min(MinerSettingsSchema.MAX_SIDE_BRANCH_DISTANCE, mine.villageSides());
        if (settings.sidesFollowVillage()) {
            y = drawNote(graphics, Tr.s("screen.dynamicvillages.mine.follows",
                    "Both sides: %s, as set at the Mayor.", sideValue(mine.villageSides())),
                    x, y, width, StorageUi.MUTED, Integer.MAX_VALUE) + 4;
        } else {
            // Shown as dug: a stored number above the Mayor's is held down to it.
            int left = Math.min(settings.left(), max), right = Math.min(settings.right(), max);
            String limit = sideValue(max);
            y = stepper(graphics, x, y, width, Tr.s("screen.dynamicvillages.mine.left", "Left side"), sideValue(left),
                    left > 0, left < max, () -> setLeft(left - 1), () -> setLeft(left + 1), mouseX, mouseY,
                    Tr.lines("screen.dynamicvillages.mine.left.tip",
                            "How long the side tunnels on the left get,\nup to the Mayor's length (%s).\nMaking it shorter never fills tunnels\nalready dug.", limit));
            y = stepper(graphics, x, y, width, Tr.s("screen.dynamicvillages.mine.right", "Right side"), sideValue(right),
                    right > 0, right < max, () -> setRight(right - 1), () -> setRight(right + 1), mouseX, mouseY,
                    Tr.lines("screen.dynamicvillages.mine.right.tip",
                            "How long the side tunnels on the right get,\nup to the Mayor's length (%s).\nMaking it shorter never fills tunnels\nalready dug.", limit));
        }
        y += 4;

        heading(graphics, Tr.s("screen.dynamicvillages.mine.main_tunnel", "Main tunnel"), x, y, width,
                Tr.lines("screen.dynamicvillages.mine.main_tunnel.tip",
                        "The straight tunnel miners dig\nfrom the token into the rock."));
        y += 12;
        int main = settings.mainLength();
        return stepper(graphics, x, y, width, Tr.s("screen.dynamicvillages.mine.length", "How far in"), mainValue(main),
                main > MineSettings.MAIN_STEP, main < MineShaftPlan.MAX_MAIN_LENGTH,
                () -> setMain(main - MineSettings.MAIN_STEP), () -> setMain(main + MineSettings.MAIN_STEP),
                mouseX, mouseY,
                Tr.lines("screen.dynamicvillages.mine.main.tip",
                        "How far the main tunnel goes into the rock,\nup to 96 blocks (All the way). It stops sooner\nat the village border or a nearer mine's rock.\nMaking it shorter never fills it in."));
    }

    /** A "label\tvalue" line: the label muted on the left, the value wrapped beside it. */
    private int drawRow(GuiGraphics graphics, String row, int x, int y, int width) {
        int tab = row.indexOf('\t');
        String label = tab < 0 ? row : row.substring(0, tab);
        String value = tab < 0 ? "" : row.substring(tab + 1);
        graphics.drawString(font, trim(label, LABEL_W - 4), x, y, StorageUi.MUTED, false);
        List<FormattedCharSequence> lines = font.split(Component.literal(value), width - LABEL_W);
        int lineY = y;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, x + LABEL_W, lineY, StorageUi.TEXT, false);
            lineY += LINE;
        }
        return y + Math.max(1, lines.size()) * LINE + 1;
    }

    private void segment(GuiGraphics graphics, int x, int y, int width, String label, boolean selected,
                         int mouseX, int mouseY, Runnable action, List<Component> tooltip) {
        boolean live = !selected && menu.rights().mayChange();
        boolean hover = live && StorageUi.inside(mouseX, mouseY, x, y, width, 16);
        StorageUi.segment(graphics, font, x, y, width, 16, trim(label, width - 6), selected, hover);
        bevel(graphics, x, y, width, 16, selected);
        hit(x, y, width, 16, live ? action : null, tooltip);
    }

    /**
     * The server's last refusal, or else, for a player who may only look (village claims round 7b), who may
     * change this mine.
     */
    private String shownMessage() {
        if (!menu.message().isEmpty() || menu.rights().mayChange()) return menu.message();
        return Tr.s("screen.dynamicvillages.mine.claim_look_only",
                "Look only: %s has not let you change this mine.", menu.rights().ownerName());
    }

    /** A label, a minus button, the value, a plus button. Returns the line below. */
    private int stepper(GuiGraphics graphics, int x, int y, int width, String label, String value,
                        boolean canLower, boolean canRaise, Runnable lower, Runnable raise,
                        int mouseX, int mouseY, List<Component> tooltip) {
        int plusX = x + width - STEP_BUTTON;
        int valueX = plusX - STEP_VALUE;
        int minusX = valueX - STEP_BUTTON;
        graphics.drawString(font, trim(label, minusX - x - 4), x, y + 3, StorageUi.MUTED, false);
        button(graphics, minusX, y, STEP_BUTTON, STEP_H, "-", canLower, mouseX, mouseY, lower, tooltip);
        // The number sits in a dark well between the buttons, like a slot.
        graphics.fill(valueX + 2, y, valueX + STEP_VALUE - 2, y + STEP_H, WELL);
        sunken(graphics, valueX + 2, y, valueX + STEP_VALUE - 2, y + STEP_H);
        String shown = trim(value, STEP_VALUE - 8);
        graphics.drawString(font, shown, valueX + (STEP_VALUE - font.width(shown)) / 2, y + 3, StorageUi.TEXT, false);
        button(graphics, plusX, y, STEP_BUTTON, STEP_H, "+", canRaise, mouseX, mouseY, raise, tooltip);
        hit(x, y, minusX - x, STEP_H, null, tooltip);
        hit(valueX, y, STEP_VALUE, STEP_H, null, tooltip);
        return y + STEP_H + 4;
    }

    private void button(GuiGraphics graphics, int x, int y, int width, int height, String label, boolean enabled,
                        int mouseX, int mouseY, Runnable action, List<Component> tooltip) {
        enabled = enabled && menu.rights().mayChange();
        boolean hover = StorageUi.inside(mouseX, mouseY, x, y, width, height);
        StorageUi.button(graphics, font, x, y, width, height, label, hover, enabled);
        if (enabled) bevel(graphics, x, y, width, height, false);
        hit(x, y, width, height, enabled ? action : null, tooltip);
    }

    /** Wrapped text; returns the line below it. Stops before a line would pass {@code bottom}. */
    private int drawNote(GuiGraphics graphics, String note, int x, int y, int width, int color, int bottom) {
        for (FormattedCharSequence line : font.split(Component.literal(note), width)) {
            if (y + font.lineHeight > bottom) break;
            graphics.drawString(font, line, x, y, color, false);
            y += LINE;
        }
        return y;
    }

    private String trim(String value, int width) {
        return StorageUi.trim(font, value, width);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (menu.version() != seenVersion) {
            seenVersion = menu.version();
            buildMine();
        }
        // Before the viewport: the key's line count sets the view's bottom.
        keyLines = layoutKey();
        // super.render draws the background, and with it the view, once.
        view.setViewport(viewLeft(), viewTop(), viewRight(), viewBottom());
        view.setHovered(view.isDragging() ? null : view.pick(mouseX, mouseY));
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (StorageUi.inside(mouseX, mouseY, resetX(), headerY(), resetWidth(), 20)) {
            graphics.renderComponentTooltip(font, Tr.lines("screen.dynamicvillages.mine.reset_view.tip",
                    "Turns the camera back to where it started.\nDrag to turn the view, right drag to move it,\nand scroll to zoom in and out."),
                    mouseX, mouseY);
            return;
        }
        for (Hit hit : hits) {
            if (!hit.tooltip().isEmpty()
                    && StorageUi.inside(mouseX, mouseY, hit.x(), hit.y(), hit.width(), hit.height())) {
                graphics.renderComponentTooltip(font, hit.tooltip(), mouseX, mouseY);
                return;
            }
        }
        BlockPos hovered = view.hovered();
        List<Component> tip = hovered == null ? null : viewTips.get(hovered);
        if (tip != null) graphics.renderComponentTooltip(font, tip, mouseX, mouseY);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Everything is drawn in screen coordinates by renderBg.
    }

    // =========================================================================
    // Input
    // =========================================================================

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (StorageUi.inside(mouseX, mouseY, resetX(), headerY(), resetWidth(), 20)) {
                view.resetCamera();
                return true;
            }
            if (fromMayor() && StorageUi.inside(mouseX, mouseY, mayorX(), headerY(), mayorWidth(), 20)) {
                MayorStatsScreen.returnToBuilding(menu.mayorId(), menu.frameId());
                PacketDistributor.sendToServer(new MayorBuildingPayloads.BackToMayor(menu.mayorId()));
                return true;
            }
            if (panelScrolls() && StorageUi.inside(mouseX, mouseY, barX() - 1, barTop(), BAR_W + 2, barHeight())) {
                draggingBar = true;
                scrollToMouse(mouseY);
                return true;
            }
            for (Hit hit : hits) {
                if (hit.action() != null
                        && StorageUi.inside(mouseX, mouseY, hit.x(), hit.y(), hit.width(), hit.height())) {
                    hit.action().run();
                    return true;
                }
            }
        }
        if (view.beginDrag(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
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
        // A click in the view chooses nothing: hovering names things, the panel changes them.
        view.endDrag(button);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (panelScrolls() && StorageUi.inside(mouseX, mouseY, sideLeft(), sideTop(), SIDE_W, sideBottom() - sideTop())) {
            panelScroll = Mth.clamp(panelScroll + (scrollY < 0 ? SCROLL_STEP : -SCROLL_STEP), 0, panelScrollMax());
            return true;
        }
        if (view.zoomAt(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
