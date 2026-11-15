package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import com.alex.test.network.MayorTradePayloads;
import com.alex.test.village.claims.ClaimLock;
import com.alex.test.village.claims.ClaimRank;
import com.alex.test.village.claims.ClaimRights;
import com.alex.test.village.claims.LandGuard;
import com.alex.test.village.claims.LandView;
import com.alex.test.village.claims.VillageClaims;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * The Land tab (village claims): Map, People, Rights and Protection, picked with the buttons on top.
 * <ul>
 * <li>Map (round 3, look only): the village's square and the land round it (the Town Hall, you, the gold edge,
 *     neighbour villages), the owner and the land's size. The ground is read from the chunks this game has loaded,
 *     again every 2 s.</li>
 * <li>People (round 4): the owner and the listed players with their ranks. The owner adds players (online ones with
 *     one click, anyone by typed name), changes ranks, takes people off, hands the village over or gives it up; the
 *     server checks and answers with the new list ({@code MayorStatsMenu.applyPeopleResult}). Others only look.</li>
 * <li>Rights (round 9): the grid of what each rank may do here ({@link ClaimRights}). The owner switches Officer,
 *     Friend and Visitor; Owner (all yes) and Hostile (all no) are fixed. Answered as a People change. Others look.</li>
 * <li>Protection (round 10): the village's land guards ({@link LandGuard}: explosions, fire, endermen), on by
 *     default; the owner turns each on or off. Answered as a People change. Others look.</li>
 * </ul>
 * Everything but the ground is the Mayor's opening data ({@link LandView}).
 */
final class LandTab extends MayorPart {

    enum Section { MAP, PEOPLE, RIGHTS, PROTECT }

    /** The section buttons, where the Settings tab has its two. */
    private static final int SECTION_Y = 61;
    /** Where the map and the People list start, under the section buttons. */
    private static final int CONTENT_Y = SECTION_Y + SUBNAV_H + 6;
    /** One chunk on the map, in GUI pixels: as big as the body's height allows, within these. */
    private static final int MAX_CELL = 8;
    private static final int MIN_CELL = 3;
    /** Space round the map and between the map and the column beside it. */
    private static final int GAP = 6;
    /** The column beside the map never gets narrower than this; the map shrinks first. */
    private static final int SIDE_MIN_W = 112;
    private static final int RESAMPLE_TICKS = 40;
    private static final int UNLOADED = 0xFF1C1C1C;
    private static final int NEIGHBOUR_EDGE = 0xFFB8B8B8;
    private static final int PAUSED_EDGE = 0xFF6E6E6E;
    private static final int OWNER_CARD_H = 36;
    private static final int LINE_H = 11;
    /** The People list's rows. */
    private static final int ROW_H = 20;
    private static final int ROW_STEP = 22;
    private static final int RANK_BUTTON_W = 58;
    private static final int REMOVE_BUTTON_W = 50;
    private static final int ADD_ROW_BUTTON_W = 40;
    private static final int SMALL_BUTTON_H = 14;
    /** The owner's controls under the list: the typed name and its Add, then Hand over and Give up. */
    private static final int ADD_NAME_W = 60;
    private static final int CONTROL_H = 18;
    /** A second click on Hand over or Give up within this many ticks does it. */
    private static final int CONFIRM_TICKS = 60;
    /** The Rights grid: its rank words on top, the act column at least this wide, the rank columns at most this. */
    private static final int GRID_HEADER_H = 12;
    private static final int GRID_LABEL_MIN = 96;
    private static final int GRID_COL_MAX = 48;
    private static final int GRID_CELL_W = 34;
    /** A grid row: as tall as the body allows, within these. */
    private static final int GRID_ROW_MAX = 20;
    private static final int GRID_ROW_MIN = 10;
    /**
     * The Protection page: a Settings-style row per guard (icon, title, one line, On/Off at the right), as tall as the
     * body allows within these, GAP apart. Icon and button sit the same 6 in from each side.
     */
    private static final int GUARD_CARD_MIN = 22;
    private static final int GUARD_CARD_MAX = 40;
    private static final int GUARD_EDGE = 6;
    private static final int GUARD_TEXT_X = 28;
    private static final int GUARD_BUTTON_W = RULE_TOGGLE_W;
    private static final int GUARD_BUTTON_H = 18;
    /** The grid's columns, left to right. */
    private static final List<ClaimRank> GRID_RANKS = List.of(ClaimRank.OWNER, ClaimRank.OFFICER, ClaimRank.FRIEND,
            ClaimRank.VISITOR, ClaimRank.HOSTILE);

    private Section section = Section.MAP;
    /** The ground per chunk of the view, row by row, {@link #groundSub} x {@link #groundSub} colours each. */
    @Nullable private int[] ground;
    private int groundSub;
    private int ticks;
    private int peopleScroll;
    /** The listed player picked for Hand over; null for none. */
    @Nullable private UUID picked;
    /** Hand over / Give up was clicked once and waits for the second click until this tick (0 = not waiting). */
    private int handOverUntil;
    private int giveUpUntil;
    /** A change was sent; until its answer (or {@link #WAIT_TICKS}) the line under the list says so. */
    private boolean waiting;
    private int waitingSince;
    /** The page that sent the last change: its answer shows only there. */
    @Nullable private Section replyPage;
    /** No answer in 5 s (window too far from the Mayor, say): the buttons work again. */
    private static final int WAIT_TICKS = 100;
    private int seenLandChanges;
    /** The typed name, kept here because a window resize makes a new box ({@link #makeNameBox}). */
    private String typedName = "";
    @Nullable private EditBox nameBox;

    /** One line of the People list. */
    private enum Kind { OWNER, MEMBER, HEADER, ONLINE, NOTE }

    private record Row(Kind kind, @Nullable UUID id, String name, ClaimRank rank) {}

    final ScrollList peopleList = new ScrollList() {
        @Override public boolean shown() { return screen.tab() == MayorStatsScreen.Tab.LAND && section == Section.PEOPLE; }
        @Override public int x() { return left() + CONTENT_X + GAP; }
        @Override public int y() { return top() + CONTENT_Y; }
        @Override public int width() { return listW(true); }
        @Override public int height() { return peopleListH(); }
        @Override public boolean sideBar() { return true; }
        @Override public int scroll() { return peopleScroll; }
        @Override public void setScroll(int value) { peopleScroll = value; }
        @Override public int maxScroll() { return Math.max(0, rows().size() - visibleRows()); }
        @Override public int thumbHeight() { return peopleThumbH(rows().size()); }
    };

    LandTab(MayorStatsScreen screen) {
        super(screen);
    }

    /** The tab was clicked open: read the ground again on the next frame. */
    void opened() {
        ground = null;
    }

    /** While the tab shows: chunks load and blocks change, so the ground is read again every 2 s. */
    void tick() {
        if (++ticks % RESAMPLE_TICKS == 0) ground = null;
    }

    // ---------------------------------------------------------------------
    // Who is looking
    // ---------------------------------------------------------------------

    @Nullable
    private static UUID me() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null ? player.getUUID() : null;
    }

    /** The owner looking at their own village: the People page has its buttons. */
    private boolean manages(LandView land) {
        UUID me = me();
        return me != null && me.equals(land.ownerId());
    }

    /** The typed-name box shows: People page, and the owner looking. */
    boolean nameBoxShown() {
        LandView land = menu.land();
        return section == Section.PEOPLE && land != null && manages(land);
    }

    EditBox makeNameBox() {
        EditBox box = new EditBox(font, left() + CONTENT_X + GAP, top() + CONTENT_Y, 100, CONTROL_H,
                Tr.c("screen.dynamicvillages.mayor.land.name_box", "Player name"));
        box.setHint(Tr.c("screen.dynamicvillages.mayor.land.name_hint", "Type a player's name..."));
        box.setMaxLength(16);
        box.setValue(typedName);
        box.setResponder(value -> typedName = value);
        nameBox = box;
        return box;
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        LandView land = menu.land();
        if (menu.landChanges() != seenLandChanges) {
            seenLandChanges = menu.landChanges();
            waiting = false;
        }
        if (waiting && ticks - waitingSince > WAIT_TICKS) waiting = false;
        int x = left() + CONTENT_X + GAP;
        if (land == null) {
            drawEmpty(graphics, left() + CONTENT_X, top() + BODY_Y, contentW(), bodyH(),
                    Tr.s("screen.dynamicvillages.mayor.land.no_land", "This village has no land yet"));
            return;
        }
        int y = top() + SECTION_Y;
        for (Section each : Section.values()) {
            drawSegment(graphics, sectionX(x, each), y, sectionW(), section == each, sectionLabel(each, land),
                    mouseX, mouseY);
        }
        switch (section) {
            case MAP -> renderMapPage(graphics, land, mouseX, mouseY);
            case PEOPLE -> renderPeoplePage(graphics, land, mouseX, mouseY);
            case RIGHTS -> renderRightsPage(graphics, land, mouseX, mouseY);
            case PROTECT -> renderGuardsPage(graphics, land, mouseX, mouseY);
        }
    }

    /** The section buttons share the row, 8 apart. */
    private int sectionW() {
        int count = Section.values().length;
        return (innerW() - 8 * (count - 1)) / count;
    }

    private int sectionX(int x, Section target) {
        return x + target.ordinal() * (sectionW() + 8);
    }

    private static String sectionLabel(Section target, LandView land) {
        return switch (target) {
            case MAP -> Tr.s("screen.dynamicvillages.mayor.land.section_map", "Map");
            case PEOPLE -> Tr.s("screen.dynamicvillages.mayor.land.section_people", "People (%s)", land.people().size()
                    + (land.ownerId() != null ? 1 : 0));
            case RIGHTS -> Tr.s("screen.dynamicvillages.mayor.land.section_rights", "Rights");
            case PROTECT -> Tr.s("screen.dynamicvillages.mayor.land.section_protect", "Protection");
        };
    }

    /** The dark body's height on this screen (the Land page keeps the window's height). */
    private int bodyH() {
        return screen.frameHeight() - CONTENT_X - BODY_Y;
    }

    /** The bottom of what the tab may draw on, from the window's top, GAP above the body's edge. */
    private int innerBottom() {
        return screen.frameHeight() - CONTENT_X - GAP;
    }

    // ---------------------------------------------------------------------
    // Map page
    // ---------------------------------------------------------------------

    private void renderMapPage(GuiGraphics graphics, LandView land, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + GAP;
        int y = top() + CONTENT_Y;
        LandView.Square view = land.view();
        int cell = cell(view);
        int mapW = view.widthChunks() * cell + 2;
        int mapH = view.depthChunks() * cell + 2;
        drawMap(graphics, land, view, cell, x, y, mapW, mapH, mouseX, mouseY);
        int sideX = x + mapW + GAP;
        drawSide(graphics, land, sideX, y, x + innerW() - sideX, mouseX, mouseY);
    }

    private int cell(LandView.Square view) {
        int cells = Math.max(view.widthChunks(), view.depthChunks());
        int room = Math.min(innerBottom() - CONTENT_Y, innerW() - SIDE_MIN_W - GAP) - 2;
        return Math.max(MIN_CELL, Math.min(MAX_CELL, room / Math.max(1, cells)));
    }

    private void drawMap(GuiGraphics graphics, LandView land, LandView.Square view, int cell,
                         int x, int y, int mapW, int mapH, int mouseX, int mouseY) {
        drawListFrame(graphics, x, y, mapW, mapH);
        int mx = x + 1;
        int my = y + 1;
        int width = view.widthChunks();
        int depth = view.depthChunks();
        if (ground == null || groundSub != subFor(cell) || ground.length != width * depth * groundSub * groundSub) {
            readGround(view, subFor(cell));
        }
        int sub = groundSub;
        int subPx = cell / sub;
        for (int cz = 0; cz < depth; cz++) {
            for (int cx = 0; cx < width; cx++) {
                boolean ours = land.square().contains(view.minX() + cx, view.minZ() + cz);
                for (int sz = 0; sz < sub; sz++) {
                    for (int sx = 0; sx < sub; sx++) {
                        int colour = ground[((cz * width + cx) * sub + sz) * sub + sx];
                        // Land outside this village is drawn darker, so its square stands out.
                        if (!ours) colour = shade(colour, 0.5f);
                        int px = mx + cx * cell + sx * subPx;
                        int py = my + cz * cell + sz * subPx;
                        int w = sx == sub - 1 ? cell - sx * subPx : subPx;
                        int h = sz == sub - 1 ? cell - sz * subPx : subPx;
                        graphics.fill(px, py, px + w, py + h, colour);
                    }
                }
            }
        }
        for (LandView.Neighbour neighbour : land.neighbours()) {
            drawEdge(graphics, neighbour.square(), view, cell, mx, my, neighbour.paused() ? PAUSED_EDGE : NEIGHBOUR_EDGE);
        }
        drawEdge(graphics, land.square(), view, cell, mx, my, GOLD);

        int hallX = mapX(land.townHall().getX(), view, cell, mx);
        int hallY = mapY(land.townHall().getZ(), view, cell, my);
        drawMarker(graphics, hallX, hallY, GOLD);
        LocalPlayer player = Minecraft.getInstance().player;
        int youX = Integer.MIN_VALUE;
        int youY = Integer.MIN_VALUE;
        if (player != null) {
            youX = mapX(player.getBlockX(), view, cell, mx);
            youY = mapY(player.getBlockZ(), view, cell, my);
            if (inside(youX, youY, mx, my, mapW - 2, mapH - 2)) drawMarker(graphics, youX, youY, TEXT);
        }

        if (inside(mouseX, mouseY, mx, my, mapW - 2, mapH - 2)) {
            int chunkX = view.minX() + (mouseX - mx) / cell;
            int chunkZ = view.minZ() + (mouseY - my) / cell;
            boolean onHall = Math.abs(mouseX - hallX) <= 3 && Math.abs(mouseY - hallY) <= 3;
            boolean onYou = Math.abs(mouseX - youX) <= 3 && Math.abs(mouseY - youY) <= 3;
            boolean unloaded = ground[(((mouseY - my) / cell * width + (mouseX - mx) / cell) * sub) * sub] == UNLOADED;
            screen.setDeferredTooltip(tooltipLines(mapTip(land, chunkX, chunkZ, onHall, onYou, unloaded)));
        }
    }

    /** The map's x for a block's x (its middle). */
    private static int mapX(int blockX, LandView.Square view, int cell, int mx) {
        return mx + Math.floorDiv((blockX - (view.minX() << 4)) * cell + cell / 2, 16);
    }

    private static int mapY(int blockZ, LandView.Square view, int cell, int my) {
        return my + Math.floorDiv((blockZ - (view.minZ() << 4)) * cell + cell / 2, 16);
    }

    /** A 5 x 5 dot with a dark rim, centred on the spot, like a map marker. */
    private static void drawMarker(GuiGraphics graphics, int x, int y, int colour) {
        graphics.fill(x - 2, y - 2, x + 3, y + 3, OUTER);
        graphics.fill(x - 1, y - 1, x + 2, y + 2, colour);
    }

    /** A square's edge, one pixel inside its outer chunks; a side cut off by the map's edge is left out. */
    private static void drawEdge(GuiGraphics graphics, LandView.Square square, LandView.Square view, int cell,
                                 int mx, int my, int colour) {
        if (!square.overlaps(view)) return;
        int minX = Math.max(square.minX(), view.minX());
        int maxX = Math.min(square.maxX(), view.maxX());
        int minZ = Math.max(square.minZ(), view.minZ());
        int maxZ = Math.min(square.maxZ(), view.maxZ());
        int x0 = mx + (minX - view.minX()) * cell;
        int x1 = mx + (maxX - view.minX() + 1) * cell;
        int y0 = my + (minZ - view.minZ()) * cell;
        int y1 = my + (maxZ - view.minZ() + 1) * cell;
        if (square.minX() >= view.minX()) graphics.fill(x0, y0, x0 + 1, y1, colour);
        if (square.maxX() <= view.maxX()) graphics.fill(x1 - 1, y0, x1, y1, colour);
        if (square.minZ() >= view.minZ()) graphics.fill(x0, y0, x1, y0 + 1, colour);
        if (square.maxZ() <= view.maxZ()) graphics.fill(x0, y1 - 1, x1, y1, colour);
    }

    private Component[] mapTip(LandView land, int chunkX, int chunkZ, boolean onHall, boolean onYou, boolean unloaded) {
        List<Component> lines = new ArrayList<>();
        if (onHall) lines.add(Component.literal(Tr.s("screen.dynamicvillages.mayor.land.tip_town_hall", "Town Hall"))
                .withStyle(ChatFormatting.GOLD));
        if (onYou) lines.add(Component.literal(Tr.s("screen.dynamicvillages.mayor.land.tip_you", "You are here"))
                .withStyle(ChatFormatting.WHITE));
        if (land.square().contains(chunkX, chunkZ)) {
            lines.add(Component.literal(Tr.s("screen.dynamicvillages.mayor.land.tip_ours", "This village's land")));
            lines.add(ownerLine(land.ownerId() != null ? land.ownerName() : ""));
        } else {
            LandView.Neighbour neighbour = neighbourAt(land, chunkX, chunkZ);
            if (neighbour != null) {
                lines.add(Component.literal(Tr.s("screen.dynamicvillages.mayor.land.tip_other", "Another village's land")));
                lines.add(ownerLine(neighbour.ownerName()));
                if (neighbour.paused()) lines.add(Component.literal(Tr.s("screen.dynamicvillages.mayor.land.tip_paused",
                        "Paused: its Town Hall token is out")).withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Component.literal(Tr.s("screen.dynamicvillages.mayor.land.tip_wild", "Nobody's land")));
            }
        }
        if (unloaded) lines.add(Component.literal(Tr.s("screen.dynamicvillages.mayor.land.tip_unloaded",
                "Too far from you to draw")).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(Tr.s("screen.dynamicvillages.mayor.land.tip_chunk", "Chunk %s, %s", chunkX, chunkZ))
                .withStyle(ChatFormatting.DARK_GRAY));
        return lines.toArray(Component[]::new);
    }

    private static Component ownerLine(String ownerName) {
        return Component.literal(ownerName.isEmpty()
                ? Tr.s("screen.dynamicvillages.mayor.land.tip_no_owner", "No owner")
                : Tr.s("screen.dynamicvillages.mayor.land.tip_owner", "Owner: %s", ownerName)).withStyle(ChatFormatting.GRAY);
    }

    @Nullable
    private static LandView.Neighbour neighbourAt(LandView land, int chunkX, int chunkZ) {
        for (LandView.Neighbour neighbour : land.neighbours()) {
            if (neighbour.square().contains(chunkX, chunkZ)) return neighbour;
        }
        return null;
    }

    /** The column beside the map: owner, land, where you are, how many people, the key. */
    private void drawSide(GuiGraphics graphics, LandView land, int x, int y, int w, int mouseX, int mouseY) {
        LocalPlayer player = Minecraft.getInstance().player;
        UUID me = me();
        boolean owned = land.ownerId() != null;

        // Owner card.
        graphics.fill(x, y, x + w, y + OWNER_CARD_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + OWNER_CARD_H - 1, ROW);
        drawFace(graphics, land.ownerId(), x + 6, y + 10, 16);
        int tx = x + 28;
        int tw = w - 32;
        graphics.drawString(font, trim(rankWord(ClaimRank.OWNER), tw), tx, y + 5, MUTED, false);
        graphics.drawString(font, trim(owned ? land.ownerName()
                : Tr.s("screen.dynamicvillages.mayor.land.nobody", "Nobody yet"), tw), tx, y + 15, owned ? TEXT : MUTED, false);
        if (owned && me != null) {
            ClaimRank mine = land.rankOf(me);
            graphics.drawString(font, trim(youAre(mine), tw), tx, y + 25, rankColour(mine), false);
            if (land.isVisitor(me) && inside(mouseX, mouseY, x, y, w, OWNER_CARD_H)) {
                screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.land.visiting_tip",
                        "Only the owner can change this village.\nYou can look at Info, Land and Log."))));
            }
        }

        // The land and where you are.
        int ly = y + OWNER_CARD_H + 6;
        String size = Tr.s("screen.dynamicvillages.mayor.land.size", "Land: %s x %s chunks",
                land.square().widthChunks(), land.square().depthChunks());
        graphics.drawString(font, trim(size, w), x, ly, TEXT, false);
        if (inside(mouseX, mouseY, x, ly - 1, w, LINE_H)) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.land.size_tip",
                    "Everything inside the gold edge,\nfrom the bottom of the world to the sky.\nVillages never overlap."))));
        }
        ly += LINE_H;
        graphics.drawString(font, trim(whereYouAre(land, player), w), x, ly, MUTED, false);
        ly += LINE_H;
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.land.people_count", "People listed: %s",
                land.people().size()), w), x, ly, MUTED, false);
        ly += LINE_H;
        if (!land.claimsOn()) {
            graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.land.claims_off", "Claims are off"), w),
                    x, ly, DENIED, false);
            if (inside(mouseX, mouseY, x, ly - 1, w, LINE_H)) {
                screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.land.claims_off_tip",
                        "Claims are off in this world, so\nnobody's land is kept safe.\nAn operator can turn them on\nwith /villager claim on."))));
            }
            ly += LINE_H;
        }

        // The key under it all, when the column has room for it.
        int keyY = top() + innerBottom() - 4 * LINE_H + 2;
        if (keyY >= ly + 4) drawKey(graphics, x, keyY, w);
    }

    private String whereYouAre(LandView land, @Nullable LocalPlayer player) {
        if (player == null) return "";
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;
        if (land.square().contains(chunkX, chunkZ)) {
            return Tr.s("screen.dynamicvillages.mayor.land.you_inside", "You: inside the land");
        }
        return land.view().contains(chunkX, chunkZ)
                ? Tr.s("screen.dynamicvillages.mayor.land.you_outside", "You: outside the land")
                : Tr.s("screen.dynamicvillages.mayor.land.you_far", "You: off the map");
    }

    private void drawKey(GuiGraphics graphics, int x, int y, int w) {
        keySquare(graphics, x, y, GOLD);
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.land.key_village", "This village"), w - 14),
                x + 14, y, MUTED, false);
        keySquare(graphics, x, y + LINE_H, NEIGHBOUR_EDGE);
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.land.key_other", "Another village"), w - 14),
                x + 14, y + LINE_H, MUTED, false);
        drawMarker(graphics, x + 4, y + 2 * LINE_H + 3, GOLD);
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.land.tip_town_hall", "Town Hall"), w - 14),
                x + 14, y + 2 * LINE_H, MUTED, false);
        drawMarker(graphics, x + 4, y + 3 * LINE_H + 3, TEXT);
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.land.key_you", "You"), w - 14),
                x + 14, y + 3 * LINE_H, MUTED, false);
    }

    /** An empty square in the edge's colour, as the map draws a village's edge. */
    private static void keySquare(GuiGraphics graphics, int x, int y, int colour) {
        graphics.fill(x, y, x + 9, y + 8, colour);
        graphics.fill(x + 1, y + 1, x + 8, y + 7, 0xFF111111);
    }

    /** The player's face when their game knows them (online), else a plain head. */
    private static void drawFace(GuiGraphics graphics, @Nullable UUID playerId, int x, int y, int size) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = playerId == null || connection == null ? null : connection.getPlayerInfo(playerId);
        if (info != null) {
            PlayerFaceRenderer.draw(graphics, info.getSkin(), x, y, size);
        } else if (size >= 16) {
            graphics.renderFakeItem(new ItemStack(Items.PLAYER_HEAD), x, y);
        } else {
            graphics.fill(x, y, x + size, y + size, DIM);
        }
    }

    // ---------------------------------------------------------------------
    // People page
    // ---------------------------------------------------------------------

    /** The list's height: down to the owner's controls (and the line above them), or to the note line. */
    private int peopleListH() {
        LandView land = menu.land();
        boolean controls = land != null && manages(land);
        int bottom = controls ? controlsTop() - LINE_H - 3 : innerBottom() - LINE_H - 3;
        return Math.max(ROW_STEP, bottom - CONTENT_Y);
    }

    /** From the window's top: the typed-name row; Hand over and Give up sit under it. */
    private int controlsTop() {
        return innerBottom() - CONTROL_H - 4 - CONTROL_H;
    }

    private int visibleRows() {
        return Math.max(1, peopleListH() / ROW_STEP);
    }

    private int peopleThumbH(int count) {
        return Math.max(18, peopleListH() * visibleRows() / Math.max(1, count));
    }

    /** The list: the owner, the listed players by rank, then (for the owner) who else is online. */
    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        LandView land = menu.land();
        if (land == null) return out;
        if (land.ownerId() == null) {
            out.add(new Row(Kind.NOTE, null, Tr.s("screen.dynamicvillages.mayor.land.no_people", "Nobody owns it yet"),
                    ClaimRank.VISITOR));
            return out;
        }
        out.add(new Row(Kind.OWNER, land.ownerId(), land.ownerName(), ClaimRank.OWNER));
        List<LandView.Person> people = new ArrayList<>(land.people());
        people.sort(Comparator.comparingInt(person -> ClaimRank.LISTED.indexOf(person.rank())));
        for (LandView.Person person : people) out.add(new Row(Kind.MEMBER, person.id(), person.name(), person.rank()));
        if (!manages(land)) return out;
        out.add(new Row(Kind.HEADER, null, Tr.s("screen.dynamicvillages.mayor.land.online_now", "Online now"),
                ClaimRank.VISITOR));
        int before = out.size();
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            List<PlayerInfo> online = new ArrayList<>(connection.getOnlinePlayers());
            online.sort(Comparator.comparing(info -> info.getProfile().getName(), String.CASE_INSENSITIVE_ORDER));
            for (PlayerInfo info : online) {
                UUID id = info.getProfile().getId();
                if (land.rankOf(id) != ClaimRank.VISITOR) continue;
                out.add(new Row(Kind.ONLINE, id, info.getProfile().getName(), ClaimRank.VISITOR));
            }
        }
        if (out.size() == before) {
            out.add(new Row(Kind.NOTE, null, Tr.s("screen.dynamicvillages.mayor.land.nobody_online",
                    "Nobody else is online"), ClaimRank.VISITOR));
        }
        return out;
    }

    private void renderPeoplePage(GuiGraphics graphics, LandView land, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + GAP;
        int listY = top() + CONTENT_Y;
        int listW = listW(true);
        int listH = peopleListH();
        boolean manages = manages(land);
        if (picked != null && land.rankOf(picked) == ClaimRank.VISITOR) picked = null;
        if (handOverUntil != 0 && ticks > handOverUntil) handOverUntil = 0;
        if (giveUpUntil != 0 && ticks > giveUpUntil) giveUpUntil = 0;

        drawListFrame(graphics, x, listY, listW, listH);
        List<Row> rows = rows();
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        peopleScroll = Math.min(peopleScroll, maxScroll);
        int rowX = x + SHOP_CARD_INSET;
        int rowW = listW - SHOP_CARD_INSET * 2;
        graphics.enableScissor(x + 1, listY + 1, x + listW - 1, listY + listH - 1);
        for (int i = 0; i < visibleRows(); i++) {
            int index = peopleScroll + i;
            if (index >= rows.size()) break;
            drawRow(graphics, rows.get(index), rowX, listY + 1 + i * ROW_STEP, rowW, manages, mouseX, mouseY,
                    inside(mouseX, mouseY, x, listY, listW, listH));
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int barX = x + listW + SHOP_SCROLLBAR_GAP;
            int thumbH = peopleThumbH(rows.size());
            int thumbY = listY + (listH - thumbH) * peopleScroll / maxScroll;
            graphics.fill(barX, listY, barX + SHOP_SCROLLBAR_W, listY + listH, 0xFF333333);
            graphics.fill(barX, thumbY, barX + SHOP_SCROLLBAR_W, thumbY + thumbH, 0xFF888888);
        }

        // The line under the list: the last answer, else where the ranks' rights are set.
        drawAnswerLine(graphics, x, listY + listH + 3,
                Tr.s("screen.dynamicvillages.mayor.land.rights_hint", "What each rank may do is set on the Rights page."));

        if (manages) drawControls(graphics, land, x, mouseX, mouseY);
    }

    private void drawAnswerLine(GuiGraphics graphics, int x, int y, String hint) {
        drawAnswerLine(graphics, x, y, hint, false);
    }

    /** Asking the Mayor, else this page's last answer, else the hint; {@code centred} under the page (Protection). */
    private void drawAnswerLine(GuiGraphics graphics, int x, int y, String hint, boolean centred) {
        String line = hint;
        int colour = DIM;
        if (waiting) {
            line = Tr.s("screen.dynamicvillages.mayor.land.waiting", "Asking the Mayor...");
            colour = MUTED;
        } else if (replyPage == section && menu.peopleReply() >= 0) {
            VillageClaims.PeopleReply reply = VillageClaims.PeopleReply.byIndex(menu.peopleReply());
            line = replyLine(reply, menu.peopleReplyName());
            colour = reply.success() ? ACCENT : DENIED;
        }
        String text = trim(line, innerW());
        graphics.drawString(font, text, centred ? x + (innerW() - font.width(text)) / 2 : x, y, colour, false);
    }

    // ---------------------------------------------------------------------
    // Rights page (round 9)
    // ---------------------------------------------------------------------

    private int gridColW() {
        return Math.min(GRID_COL_MAX, (innerW() - GRID_LABEL_MIN) / GRID_RANKS.size());
    }

    private int gridLabelW() {
        return innerW() - gridColW() * GRID_RANKS.size();
    }

    /** Header, eight rows and the answer line fit above the body's bottom (rows shrink on a small window). */
    private int gridRowStep() {
        int room = innerBottom() - CONTENT_Y - GRID_HEADER_H - LINE_H - 2;
        return Math.max(GRID_ROW_MIN, Math.min(GRID_ROW_MAX, room / ClaimRights.ROWS.size()));
    }

    private int gridCellW() {
        return Math.min(GRID_CELL_W, gridColW() - 6);
    }

    /** A cell's left edge: centred in its rank's column. */
    private int gridCellX(int x, int column) {
        return x + gridLabelW() + column * gridColW() + (gridColW() - gridCellW()) / 2;
    }

    private void renderRightsPage(GuiGraphics graphics, LandView land, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + GAP;
        int y = top() + CONTENT_Y;
        int colW = gridColW();
        int labelW = gridLabelW();
        int step = gridRowStep();
        int cellW = gridCellW();
        boolean manages = manages(land);
        ClaimRights rights = land.rights();
        for (int column = 0; column < GRID_RANKS.size(); column++) {
            ClaimRank rank = GRID_RANKS.get(column);
            int cx = x + labelW + column * colW;
            String word = trim(rankWord(rank), colW - 2);
            graphics.drawString(font, word, cx + (colW - font.width(word)) / 2, y + 2, MUTED, false);
            if (inside(mouseX, mouseY, cx, y, colW, GRID_HEADER_H)) screen.setDeferredTooltip(tooltipLines(rankTip(rank)));
        }
        int rowsY = y + GRID_HEADER_H;
        int h = step - 2;
        // The switches sit inside the row's frame like a Settings row's button; flush on a small window.
        int inset = Math.max(0, Math.min(2, (h - 10) / 2));
        for (int row = 0; row < ClaimRights.ROWS.size(); row++) {
            ClaimLock.Act act = ClaimRights.ROWS.get(row);
            int ry = rowsY + row * step;
            graphics.fill(x, ry, x + innerW(), ry + h, PANEL_BORDER);
            graphics.fill(x + 1, ry + 1, x + innerW() - 1, ry + h - 1, ROW);
            graphics.drawString(font, trim(actLabel(act), labelW - 10), x + 6, ry + (h - 8) / 2, TEXT, false);
            if (inside(mouseX, mouseY, x, ry, labelW, h)) {
                screen.setDeferredTooltip(tooltipLines(join(new Component[] {
                        Component.literal(actLabel(act)).withStyle(ChatFormatting.WHITE)}, linesOf(actTip(act)))));
            }
            for (int column = 0; column < GRID_RANKS.size(); column++) {
                ClaimRank rank = GRID_RANKS.get(column);
                int cx = gridCellX(x, column);
                boolean on = rights.allows(rank, act);
                boolean over = inside(mouseX, mouseY, cx, ry, cellW, h);
                drawCell(graphics, cx, ry + inset, cellW, h - inset * 2, on,
                        manages && ClaimRights.switchable(rank), over && !waiting);
                if (over) screen.setDeferredTooltip(tooltipLines(linesOf(cellTip(rank, act, on, manages))));
            }
        }
        String hint = sharedHint(land, manages,
                Tr.s("screen.dynamicvillages.mayor.land.rights_click", "Click Yes or No to change it. Owner and Hostile are fixed."));
        drawAnswerLine(graphics, x, rowsY + ClaimRights.ROWS.size() * step + 2, hint);
    }

    /** The Rights and Protection pages' line: nobody owns it, claims are off, the owner's own hint, or who may change it. */
    private String sharedHint(LandView land, boolean manages, String ownerHint) {
        if (land.ownerId() == null) return Tr.s("screen.dynamicvillages.mayor.land.no_people", "Nobody owns it yet");
        if (!land.claimsOn()) {
            return Tr.s("screen.dynamicvillages.mayor.land.claims_off_page", "Claims are off in this world, so none of this is used.");
        }
        return manages ? ownerHint
                : Tr.s("screen.dynamicvillages.mayor.land.rights_owner_only", "Only %s can change these.", land.ownerName());
    }

    /**
     * One switch, Yes or No, in the Settings buttons' look. Greyed when it cannot be changed: the Owner and Hostile
     * columns always, every cell for anyone but the owner.
     */
    private void drawCell(GuiGraphics graphics, int x, int y, int w, int h, boolean on, boolean enabled,
                          boolean hovered) {
        String word = on ? Tr.s("screen.dynamicvillages.mayor.land.right_yes", "Yes")
                : Tr.s("screen.dynamicvillages.mayor.land.right_no", "No");
        drawToggleButton(graphics, x, y, w, h, trim(word, w - 4), on, enabled, hovered);
    }

    // ---------------------------------------------------------------------
    // Protection page (round 10)
    // ---------------------------------------------------------------------

    /** A guard's row: title and one line, centred top to bottom; shorter on a small window. */
    private int guardCardH() {
        int count = LandGuard.Kind.values().length;
        int room = innerBottom() - CONTENT_Y - LINE_H - GAP - GAP * (count - 1);
        return Math.max(GUARD_CARD_MIN, Math.min(GUARD_CARD_MAX, room / count));
    }

    private int guardCardY(LandGuard.Kind kind) {
        return top() + CONTENT_Y + kind.ordinal() * (guardCardH() + GAP);
    }

    private int guardButtonX(int x) {
        return x + innerW() - GUARD_BUTTON_W - GUARD_EDGE;
    }

    private int guardButtonY(LandGuard.Kind kind) {
        return guardCardY(kind) + (guardCardH() - GUARD_BUTTON_H) / 2;
    }

    private void renderGuardsPage(GuiGraphics graphics, LandView land, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + GAP;
        int cardH = guardCardH();
        boolean manages = manages(land);
        int bx = guardButtonX(x);
        int textW = bx - GUARD_EDGE - (x + GUARD_TEXT_X);
        int bottom = top() + CONTENT_Y;
        for (LandGuard.Kind kind : LandGuard.Kind.values()) {
            int cy = guardCardY(kind);
            bottom = cy + cardH;
            graphics.fill(x, cy, x + innerW(), cy + cardH, PANEL_BORDER);
            graphics.fill(x + 1, cy + 1, x + innerW() - 1, cy + cardH - 1, ROW);
            graphics.renderFakeItem(new ItemStack(guardIcon(kind)), x + GUARD_EDGE, cy + (cardH - 16) / 2);
            // Title and line as one block (18 high to the line's baseline), the same space above and below.
            int titleY = cy + (cardH - 18) / 2;
            graphics.drawString(font, trim(guardTitle(kind), textW), x + GUARD_TEXT_X, titleY, TEXT, false);
            graphics.drawString(font, trim(guardLine(kind), textW), x + GUARD_TEXT_X, titleY + 11, MUTED, false);
            boolean on = land.guards(kind);
            int by = guardButtonY(kind);
            boolean over = inside(mouseX, mouseY, bx, by, GUARD_BUTTON_W, GUARD_BUTTON_H);
            drawToggleButton(graphics, bx, by, GUARD_BUTTON_W, GUARD_BUTTON_H,
                    trim(on ? Tr.s("screen.dynamicvillages.mayor.land.guard_on", "On")
                            : Tr.s("screen.dynamicvillages.mayor.land.guard_off", "Off"), GUARD_BUTTON_W - 4),
                    on, manages, over && !waiting);
            if (over) {
                screen.setDeferredTooltip(tooltipLines(linesOf(!manages
                        ? Tr.s("screen.dynamicvillages.mayor.land.guard_owner_only", "Only %s can change this.", land.ownerName())
                        : on ? Tr.s("screen.dynamicvillages.mayor.land.guard_click_off", "On now.\nClick to turn it off.")
                        : Tr.s("screen.dynamicvillages.mayor.land.guard_click_on", "Off now.\nClick to turn it on."))));
            } else if (inside(mouseX, mouseY, x, cy, innerW(), cardH)) {
                // The row says it in one line; the hover says all of it.
                screen.setDeferredTooltip(tooltipLines(join(new Component[] {
                        Component.literal(guardTitle(kind)).withStyle(ChatFormatting.WHITE)}, linesOf(guardText(kind)))));
            }
        }
        drawAnswerLine(graphics, x, bottom + GAP, sharedHint(land, manages,
                Tr.s("screen.dynamicvillages.mayor.land.guard_hint", "Raids still do damage: ravagers break leaves and crops.")),
                true);
    }

    private void drawRow(GuiGraphics graphics, Row row, int x, int y, int w, boolean manages,
                         int mouseX, int mouseY, boolean mouseInList) {
        boolean over = mouseInList && inside(mouseX, mouseY, x, y, w, ROW_H);
        switch (row.kind()) {
            case HEADER -> {
                graphics.drawString(font, trim(row.name(), w - 8), x + 4, y + 7, MUTED, false);
                graphics.fill(x + 4, y + ROW_H - 3, x + w - 4, y + ROW_H - 2, PANEL_BORDER);
                return;
            }
            case NOTE -> {
                graphics.drawString(font, trim(row.name(), w - 8), x + 4, y + 6, DIM, false);
                return;
            }
            default -> { }
        }
        boolean isPicked = row.kind() == Kind.MEMBER && row.id() != null && row.id().equals(picked);
        graphics.fill(x, y, x + w, y + ROW_H, isPicked ? ROW_SELECTED : over && manages && row.kind() == Kind.MEMBER
                ? ROW_HOVER : ROW);
        drawFace(graphics, row.id(), x + 3, y + 2, 16);
        int right = x + w - 4;
        int nameRoom;
        switch (row.kind()) {
            case OWNER -> {
                String word = rankWord(ClaimRank.OWNER);
                graphics.drawString(font, word, right - font.width(word), y + 6, GOLD, false);
                nameRoom = right - font.width(word) - 6 - (x + 24);
                if (over) screen.setDeferredTooltip(tooltipLines(rankTip(ClaimRank.OWNER)));
            }
            case MEMBER -> {
                if (manages) {
                    int removeX = right - REMOVE_BUTTON_W;
                    int rankX = removeX - 4 - RANK_BUTTON_W;
                    int by = y + (ROW_H - SMALL_BUTTON_H) / 2;
                    boolean onRank = over && inside(mouseX, mouseY, rankX, by, RANK_BUTTON_W, SMALL_BUTTON_H);
                    boolean onRemove = over && inside(mouseX, mouseY, removeX, by, REMOVE_BUTTON_W, SMALL_BUTTON_H);
                    drawSmallButton(graphics, rankX, by, RANK_BUTTON_W, rankWord(row.rank()), rankColour(row.rank()), true, onRank);
                    drawSmallButton(graphics, removeX, by, REMOVE_BUTTON_W,
                            Tr.s("screen.dynamicvillages.mayor.land.remove", "Remove"), MUTED, true, onRemove);
                    nameRoom = rankX - 6 - (x + 24);
                    if (onRank) {
                        screen.setDeferredTooltip(tooltipLines(join(rankTip(row.rank()), new Component[] {
                                Component.literal(Tr.s("screen.dynamicvillages.mayor.land.rank_click", "Click: make them %s.",
                                        rankWord(row.rank().nextListed()))).withStyle(ChatFormatting.GRAY)})));
                    } else if (onRemove) {
                        screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.land.remove_tip",
                                "Take %s off the list.\nThey are a Visitor here again.", row.name()))));
                    } else if (over && !isPicked) {
                        screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.land.pick_tip",
                                "Click to pick %s,\nthen Hand over below.", row.name()))));
                    }
                } else {
                    String word = rankWord(row.rank());
                    graphics.drawString(font, word, right - font.width(word), y + 6, rankColour(row.rank()), false);
                    nameRoom = right - font.width(word) - 6 - (x + 24);
                    if (over) screen.setDeferredTooltip(tooltipLines(rankTip(row.rank())));
                }
            }
            default -> {
                int addX = right - ADD_ROW_BUTTON_W;
                int by = y + (ROW_H - SMALL_BUTTON_H) / 2;
                boolean onAdd = over && inside(mouseX, mouseY, addX, by, ADD_ROW_BUTTON_W, SMALL_BUTTON_H);
                drawSmallButton(graphics, addX, by, ADD_ROW_BUTTON_W,
                        Tr.s("screen.dynamicvillages.mayor.land.add", "Add"), TEXT, !waiting, onAdd);
                nameRoom = addX - 6 - (x + 24);
                if (onAdd) {
                    screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.land.add_online_tip",
                            "Add %s as a Friend.\nChange the rank after.", row.name()))));
                }
            }
        }
        graphics.drawString(font, trim(row.name(), nameRoom), x + 24, y + 6, TEXT, false);
    }

    /** The owner's controls: a typed name and Add, then Hand over and Give up. */
    private void drawControls(GuiGraphics graphics, LandView land, int x, int mouseX, int mouseY) {
        int y = top() + controlsTop();
        int boxW = innerW() - ADD_NAME_W - 4;
        if (nameBox != null) {
            nameBox.setX(x);
            nameBox.setY(y);
            nameBox.setWidth(boxW);
        }
        boolean canAdd = !waiting && !typedName.isBlank();
        boolean onAdd = inside(mouseX, mouseY, x + boxW + 4, y, ADD_NAME_W, CONTROL_H);
        drawSmallButton(graphics, x + boxW + 4, y, ADD_NAME_W, CONTROL_H,
                Tr.s("screen.dynamicvillages.mayor.land.add", "Add"), TEXT, canAdd, onAdd);
        if (onAdd) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.land.add_name_tip",
                    "Add the player named in the box as a Friend.\nThey do not need to be online."))));
        }

        int by = y + CONTROL_H + 4;
        String pickedName = pickedName(land);
        boolean canHandOver = pickedName != null && land.rankOf(picked) != ClaimRank.HOSTILE && !waiting;
        String handLabel = handOverUntil != 0
                ? Tr.s("screen.dynamicvillages.mayor.land.sure", "Sure? Click again")
                : pickedName != null
                ? Tr.s("screen.dynamicvillages.mayor.land.hand_over_to", "Hand over to %s", pickedName)
                : Tr.s("screen.dynamicvillages.mayor.land.hand_over", "Hand over");
        boolean onHand = inside(mouseX, mouseY, x, by, sectionButtonW(), CONTROL_H);
        drawSmallButton(graphics, x, by, sectionButtonW(), CONTROL_H, handLabel,
                handOverUntil != 0 ? GOLD : TEXT, canHandOver, onHand);
        if (onHand) {
            screen.setDeferredTooltip(tooltipLines(linesOf(canHandOver
                    ? Tr.s("screen.dynamicvillages.mayor.land.hand_over_tip", "%s becomes the owner.\nYou stay on as an Officer.",
                    pickedName)
                    : Tr.s("screen.dynamicvillages.mayor.land.hand_over_off_tip",
                    "Click a person in the list first.\nThey must be an Officer or Friend."))));
        }
        int gx = secondSectionX(x);
        boolean onGive = inside(mouseX, mouseY, gx, by, sectionButtonW(), CONTROL_H);
        drawSmallButton(graphics, gx, by, sectionButtonW(), CONTROL_H, giveUpUntil != 0
                        ? Tr.s("screen.dynamicvillages.mayor.land.sure", "Sure? Click again")
                        : Tr.s("screen.dynamicvillages.mayor.land.give_up", "Give up the village"),
                giveUpUntil != 0 ? DENIED : TEXT, !waiting, onGive);
        if (onGive) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.land.give_up_tip",
                    "Nobody will own this village\nand the list is emptied."))));
        }
    }

    @Nullable
    private String pickedName(LandView land) {
        if (picked == null) return null;
        for (LandView.Person person : land.people()) {
            if (person.id().equals(picked)) return person.name();
        }
        return null;
    }

    private void drawSmallButton(GuiGraphics graphics, int x, int y, int w, String label, int colour,
                                 boolean enabled, boolean hovered) {
        drawSmallButton(graphics, x, y, w, SMALL_BUTTON_H, label, colour, enabled, hovered);
    }

    private void drawSmallButton(GuiGraphics graphics, int x, int y, int w, int h, String label, int colour,
                                 boolean enabled, boolean hovered) {
        graphics.fill(x, y, x + w, y + h, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, !enabled ? 0xFF1A1A1A : hovered ? ROW_HOVER : 0xFF222222);
        String text = trim(label, w - 6);
        graphics.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - 8) / 2, enabled ? colour : DIM, false);
    }

    // ---------------------------------------------------------------------
    // Words
    // ---------------------------------------------------------------------

    static String rankWord(ClaimRank rank) {
        return switch (rank) {
            case OWNER -> Tr.s("screen.dynamicvillages.mayor.land.rank.owner", "Owner");
            case OFFICER -> Tr.s("screen.dynamicvillages.mayor.land.rank.officer", "Officer");
            case FRIEND -> Tr.s("screen.dynamicvillages.mayor.land.rank.friend", "Friend");
            case VISITOR -> Tr.s("screen.dynamicvillages.mayor.land.rank.visitor", "Visitor");
            case HOSTILE -> Tr.s("screen.dynamicvillages.mayor.land.rank.hostile", "Hostile");
        };
    }

    private static int rankColour(ClaimRank rank) {
        return switch (rank) {
            case OWNER -> GOLD;
            case OFFICER -> ACCENT;
            case FRIEND -> TEXT;
            case VISITOR -> MUTED;
            case HOSTILE -> DENIED;
        };
    }

    /** Who a rank is; what it may do is the Rights page (round 9). */
    private static Component[] rankTip(ClaimRank rank) {
        String text = switch (rank) {
            case OWNER -> Tr.s("screen.dynamicvillages.mayor.land.rank.owner_tip",
                    "Can do everything here. The only one\nwho changes the list and the rights.");
            case OFFICER -> Tr.s("screen.dynamicvillages.mayor.land.rank.officer_tip",
                    "Your trusted helpers.\nWhat they may do is on the Rights page.");
            case FRIEND -> Tr.s("screen.dynamicvillages.mayor.land.rank.friend_tip",
                    "Players you trust.\nWhat they may do is on the Rights page.");
            case VISITOR -> Tr.s("screen.dynamicvillages.mayor.land.rank.visitor_tip",
                    "Anyone not on the list.\nWhat they may do is on the Rights page.");
            case HOSTILE -> Tr.s("screen.dynamicvillages.mayor.land.rank.hostile_tip",
                    "Can do nothing here.\nVillagers hide from them\nand guards fight them.");
        };
        return join(new Component[] {Component.literal(rankWord(rank)).withStyle(ChatFormatting.WHITE)}, linesOf(text));
    }

    private static String youAre(ClaimRank rank) {
        return switch (rank) {
            case OWNER -> Tr.s("screen.dynamicvillages.mayor.land.you_own", "That is you");
            case OFFICER -> Tr.s("screen.dynamicvillages.mayor.land.you_officer", "You are an Officer");
            case FRIEND -> Tr.s("screen.dynamicvillages.mayor.land.you_friend", "You are a Friend");
            case VISITOR -> Tr.s("screen.dynamicvillages.mayor.land.visiting", "You are visiting");
            case HOSTILE -> Tr.s("screen.dynamicvillages.mayor.land.you_hostile", "You are Hostile here");
        };
    }

    private static String replyLine(VillageClaims.PeopleReply reply, String name) {
        return switch (reply) {
            case ADDED -> Tr.s("screen.dynamicvillages.mayor.land.reply.added", "%s is on the list now.", name);
            case RANK_SET -> Tr.s("screen.dynamicvillages.mayor.land.reply.rank_set", "Rank changed for %s.", name);
            case REMOVED -> Tr.s("screen.dynamicvillages.mayor.land.reply.removed", "%s is off the list.", name);
            case HANDED_OVER -> Tr.s("screen.dynamicvillages.mayor.land.reply.handed_over", "%s owns this village now.", name);
            case GAVE_UP -> Tr.s("screen.dynamicvillages.mayor.land.reply.gave_up", "Nobody owns this village now.");
            case ALREADY_LISTED -> Tr.s("screen.dynamicvillages.mayor.land.reply.already", "%s is already on the list.", name);
            case NOT_FOUND -> Tr.s("screen.dynamicvillages.mayor.land.reply.not_found", "Could not find a player called %s.", name);
            case IS_OWNER -> Tr.s("screen.dynamicvillages.mayor.land.reply.is_owner", "%s owns this village.", name);
            case FULL -> Tr.s("screen.dynamicvillages.mayor.land.reply.full", "The list is full.");
            case NOT_OWNER -> Tr.s("screen.dynamicvillages.mayor.land.reply.not_owner", "Only the owner can change this.");
            case NOT_LISTED -> Tr.s("screen.dynamicvillages.mayor.land.reply.not_listed", "%s is not on the list.", name);
            case HOSTILE_HEIR -> Tr.s("screen.dynamicvillages.mayor.land.reply.hostile_heir",
                    "Make %s an Officer or Friend first.", name);
            case RIGHT_SET, GUARD_SET -> Tr.s("screen.dynamicvillages.mayor.land.reply.right_set", "Saved. It works at once.");
            case RIGHT_FIXED -> Tr.s("screen.dynamicvillages.mayor.land.reply.right_fixed",
                    "The Owner and Hostile columns cannot be changed.");
        };
    }

    /** A Protection card's title. */
    private static String guardTitle(LandGuard.Kind kind) {
        return switch (kind) {
            case EXPLOSIONS -> Tr.s("screen.dynamicvillages.mayor.land.guard.explosions", "Explosions break nothing");
            case FIRE -> Tr.s("screen.dynamicvillages.mayor.land.guard.fire", "Fire does not spread");
            case ENDERMEN -> Tr.s("screen.dynamicvillages.mayor.land.guard.endermen", "Endermen take nothing");
        };
    }

    /** A Protection row's one line under its title. */
    private static String guardLine(LandGuard.Kind kind) {
        return switch (kind) {
            case EXPLOSIONS -> Tr.s("screen.dynamicvillages.mayor.land.guard.explosions_line", "Creepers and TNT break no blocks here");
            case FIRE -> Tr.s("screen.dynamicvillages.mayor.land.guard.fire_line", "Fire here burns nothing and goes out");
            case ENDERMEN -> Tr.s("screen.dynamicvillages.mayor.land.guard.endermen_line", "Endermen leave the blocks here alone");
        };
    }

    private static Item guardIcon(LandGuard.Kind kind) {
        return switch (kind) {
            case EXPLOSIONS -> Items.TNT;
            case FIRE -> Items.FLINT_AND_STEEL;
            case ENDERMEN -> Items.ENDER_PEARL;
        };
    }

    /** A Protection row's hover: what it covers in full. */
    private static String guardText(LandGuard.Kind kind) {
        return switch (kind) {
            case EXPLOSIONS -> Tr.s("screen.dynamicvillages.mayor.land.guard.explosions_text",
                    "Creepers, TNT and fireballs break no blocks\nhere, nor item frames, armour stands or carts.");
            case FIRE -> Tr.s("screen.dynamicvillages.mayor.land.guard.fire_text",
                    "Fire burns nothing here and soon goes out.\nFireplaces on netherrack keep burning.");
            case ENDERMEN -> Tr.s("screen.dynamicvillages.mayor.land.guard.endermen_text",
                    "Endermen do not pick up or put down\nblocks on this land.");
        };
    }

    /** A Rights row's name. */
    private static String actLabel(ClaimLock.Act act) {
        return switch (act) {
            case BUILD -> Tr.s("screen.dynamicvillages.mayor.land.right.build", "Build and break");
            case USE -> Tr.s("screen.dynamicvillages.mayor.land.right.use", "Chests and blocks");
            case DOOR -> Tr.s("screen.dynamicvillages.mayor.land.right.door", "Doors and gates");
            case PEN_ANIMAL -> Tr.s("screen.dynamicvillages.mayor.land.right.pen_animal", "Pen animals");
            case SHOP -> Tr.s("screen.dynamicvillages.mayor.land.right.shop", "Mayor's Shop");
            case LOOK -> Tr.s("screen.dynamicvillages.mayor.land.right.look", "Look at windows");
            case MANAGE -> Tr.s("screen.dynamicvillages.mayor.land.right.manage", "Run the village");
            case HARM_RESIDENT -> Tr.s("screen.dynamicvillages.mayor.land.right.harm_resident", "Hurt villagers");
        };
    }

    /** What a Rights row covers, for its hover. */
    private static String actTip(ClaimLock.Act act) {
        return switch (act) {
            case BUILD -> Tr.s("screen.dynamicvillages.mayor.land.right.build_tip",
                    "Break and place blocks, use buckets\nand tools on blocks, trample crops.");
            case USE -> Tr.s("screen.dynamicvillages.mayor.land.right.use_tip",
                    "Chests, buttons, beds, bells, item frames,\narmour stands, chest carts, building tokens,\njob tokens and name tags on villagers.");
            case DOOR -> Tr.s("screen.dynamicvillages.mayor.land.right.door_tip",
                    "Open doors, trapdoors and fence gates.");
            case PEN_ANIMAL -> Tr.s("screen.dynamicvillages.mayor.land.right.pen_animal_tip",
                    "Hurt, lead, shear, milk, feed or ride\nthe animals in this village's pens.");
            case SHOP -> Tr.s("screen.dynamicvillages.mayor.land.right.shop_tip",
                    "Buy from the Mayor's Shop tab,\nand buy a new Town Hall token.");
            case LOOK -> Tr.s("screen.dynamicvillages.mayor.land.right.look_tip",
                    "Click a building token to look at its window.\nFor players who may not use chests and blocks;\nthe others open the windows anyway.");
            case MANAGE -> Tr.s("screen.dynamicvillages.mayor.land.right.manage_tip",
                    "The Mayor's Settings, village rules and raids,\nhow each villager works, room settings\nand who sleeps where.");
            case HARM_RESIDENT -> Tr.s("screen.dynamicvillages.mayor.land.right.harm_resident_tip",
                    "Hurt this village's villagers,\nits Mayor and its golems.");
        };
    }

    /** A Rights cell's hover: whether that rank may, and for the owner how to change it. */
    private static String cellTip(ClaimRank rank, ClaimLock.Act act, boolean on, boolean manages) {
        if (rank == ClaimRank.OWNER) {
            return Tr.s("screen.dynamicvillages.mayor.land.right.owner_cell", "The owner may always do this.");
        }
        if (rank == ClaimRank.HOSTILE) {
            return Tr.s("screen.dynamicvillages.mayor.land.right.hostile_cell", "Hostile players may never do this.");
        }
        String said = on
                ? Tr.s("screen.dynamicvillages.mayor.land.right.allowed", "%s may do this:\n%s.", rankPlural(rank), actLabel(act))
                : Tr.s("screen.dynamicvillages.mayor.land.right.refused", "%s may not do this:\n%s.", rankPlural(rank), actLabel(act));
        if (!manages) return said;
        return said + "\n" + (on ? Tr.s("screen.dynamicvillages.mayor.land.right.click_off", "Click to say No.")
                : Tr.s("screen.dynamicvillages.mayor.land.right.click_on", "Click to say Yes."));
    }

    private static String rankPlural(ClaimRank rank) {
        return switch (rank) {
            case OFFICER -> Tr.s("screen.dynamicvillages.mayor.land.rank.officers", "Officers");
            case FRIEND -> Tr.s("screen.dynamicvillages.mayor.land.rank.friends", "Friends");
            case VISITOR -> Tr.s("screen.dynamicvillages.mayor.land.rank.visitors", "Visitors");
            default -> rankWord(rank);
        };
    }

    // ---------------------------------------------------------------------
    // Clicks and keys
    // ---------------------------------------------------------------------

    boolean mouseClicked(double mouseX, double mouseY) {
        LandView land = menu.land();
        if (land == null) return false;
        int x = left() + CONTENT_X + GAP;
        int y = top() + SECTION_Y;
        for (Section each : Section.values()) {
            if (!inside(mouseX, mouseY, sectionX(x, each), y, sectionW(), SUBNAV_H)) continue;
            section = each;
            if (each == Section.MAP) ground = null;
            return true;
        }
        if (section == Section.RIGHTS) return clickRights(land, x, mouseX, mouseY);
        if (section == Section.PROTECT) return clickGuards(land, x, mouseX, mouseY);
        if (section != Section.PEOPLE) return false;
        boolean manages = manages(land);
        int listY = top() + CONTENT_Y;
        int listW = listW(true);
        if (inside(mouseX, mouseY, x, listY, listW, peopleListH())) {
            if (!manages || waiting) return true;
            List<Row> rows = rows();
            int index = peopleScroll + (int) ((mouseY - listY - 1) / ROW_STEP);
            if (index < 0 || index >= rows.size()) return true;
            Row row = rows.get(index);
            int rowX = x + SHOP_CARD_INSET;
            int rowY = listY + 1 + (index - peopleScroll) * ROW_STEP;
            int right = rowX + listW - SHOP_CARD_INSET * 2 - 4;
            int by = rowY + (ROW_H - SMALL_BUTTON_H) / 2;
            if (row.kind() == Kind.MEMBER) {
                int removeX = right - REMOVE_BUTTON_W;
                int rankX = removeX - 4 - RANK_BUTTON_W;
                if (inside(mouseX, mouseY, rankX, by, RANK_BUTTON_W, SMALL_BUTTON_H)) {
                    send(VillageClaims.PeopleAction.SET_RANK, row.id(), "", row.rank().nextListed());
                } else if (inside(mouseX, mouseY, removeX, by, REMOVE_BUTTON_W, SMALL_BUTTON_H)) {
                    if (row.id().equals(picked)) picked = null;
                    send(VillageClaims.PeopleAction.REMOVE, row.id(), "", ClaimRank.VISITOR);
                } else {
                    picked = row.id().equals(picked) ? null : row.id();
                    handOverUntil = 0;
                }
            } else if (row.kind() == Kind.ONLINE
                    && inside(mouseX, mouseY, right - ADD_ROW_BUTTON_W, by, ADD_ROW_BUTTON_W, SMALL_BUTTON_H)) {
                send(VillageClaims.PeopleAction.ADD_ONLINE, row.id(), "", ClaimRank.FRIEND);
            }
            return true;
        }
        if (!manages) return false;
        int cy = top() + controlsTop();
        int boxW = innerW() - ADD_NAME_W - 4;
        if (inside(mouseX, mouseY, x + boxW + 4, cy, ADD_NAME_W, CONTROL_H)) {
            submitName();
            return true;
        }
        int by = cy + CONTROL_H + 4;
        if (inside(mouseX, mouseY, x, by, sectionButtonW(), CONTROL_H)) {
            if (waiting || pickedName(land) == null || land.rankOf(picked) == ClaimRank.HOSTILE) return true;
            if (handOverUntil != 0) {
                handOverUntil = 0;
                send(VillageClaims.PeopleAction.HAND_OVER, picked, "", ClaimRank.VISITOR);
                picked = null;
            } else {
                handOverUntil = ticks + CONFIRM_TICKS;
            }
            return true;
        }
        if (inside(mouseX, mouseY, secondSectionX(x), by, sectionButtonW(), CONTROL_H)) {
            if (waiting) return true;
            if (giveUpUntil != 0) {
                giveUpUntil = 0;
                send(VillageClaims.PeopleAction.GIVE_UP, null, "", ClaimRank.VISITOR);
            } else {
                giveUpUntil = ticks + CONFIRM_TICKS;
            }
            return true;
        }
        return false;
    }

    /** Enter in the name box, or its Add button: list the typed player as a Friend. */
    void submitName() {
        String name = typedName.trim();
        if (waiting || name.isEmpty()) return;
        send(VillageClaims.PeopleAction.ADD_NAME, null, name, ClaimRank.FRIEND);
        typedName = "";
        if (nameBox != null) nameBox.setValue("");
    }

    private void send(VillageClaims.PeopleAction action, @Nullable UUID target, String name, ClaimRank rank) {
        waiting = true;
        waitingSince = ticks;
        replyPage = Section.PEOPLE;
        PacketDistributor.sendToServer(new MayorTradePayloads.ClaimPeople(screen.mayorId(), action.ordinal(),
                Optional.ofNullable(target), name, rank.ordinal()));
    }

    /** A click on a Protection switch: the owner turns that guard on or off. */
    private boolean clickGuards(LandView land, int x, double mouseX, double mouseY) {
        for (LandGuard.Kind kind : LandGuard.Kind.values()) {
            if (!inside(mouseX, mouseY, guardButtonX(x), guardButtonY(kind), GUARD_BUTTON_W, GUARD_BUTTON_H)) continue;
            if (manages(land) && !waiting) {
                waiting = true;
                waitingSince = ticks;
                replyPage = Section.PROTECT;
                PacketDistributor.sendToServer(new MayorTradePayloads.ClaimGuard(screen.mayorId(), kind.ordinal(),
                        !land.guards(kind)));
            }
            return true;
        }
        return false;
    }

    /** A click on the Rights grid: the owner flips an Officer, Friend or Visitor cell. */
    private boolean clickRights(LandView land, int x, double mouseX, double mouseY) {
        int rowsY = top() + CONTENT_Y + GRID_HEADER_H;
        int step = gridRowStep();
        if (!inside(mouseX, mouseY, x, rowsY, innerW(), step * ClaimRights.ROWS.size())) return false;
        if (!manages(land) || waiting) return true;
        int row = (int) ((mouseY - rowsY) / step);
        if (row < 0 || row >= ClaimRights.ROWS.size()) return true;
        ClaimLock.Act act = ClaimRights.ROWS.get(row);
        for (int column = 0; column < GRID_RANKS.size(); column++) {
            ClaimRank rank = GRID_RANKS.get(column);
            if (!ClaimRights.switchable(rank)
                    || !inside(mouseX, mouseY, gridCellX(x, column), rowsY + row * step, gridCellW(), step - 2)) continue;
            waiting = true;
            waitingSince = ticks;
            replyPage = Section.RIGHTS;
            PacketDistributor.sendToServer(new MayorTradePayloads.ClaimRight(screen.mayorId(), act.ordinal(),
                    rank.ordinal(), !land.rights().allows(rank, act)));
            return true;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // The ground
    // ---------------------------------------------------------------------

    /** Colours per chunk side: 2 x 2 when a chunk is 6 pixels or more, else 1. */
    private static int subFor(int cell) {
        return cell >= 6 ? 2 : 1;
    }

    /**
     * Reads the ground's colour as a vanilla map does: the top block's map colour, lighter where the ground rises
     * from the spot north of it and darker where it falls. Chunks this game has not loaded stay dark.
     */
    private void readGround(LandView.Square view, int sub) {
        ClientLevel level = Minecraft.getInstance().level;
        int width = view.widthChunks();
        int depth = view.depthChunks();
        int rows = depth * sub;
        int cols = width * sub;
        int[] colours = new int[rows * cols];
        int[] heights = new int[rows * cols];
        int step = 16 / sub;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cz = 0; cz < depth; cz++) {
            for (int cx = 0; cx < width; cx++) {
                LevelChunk chunk = level == null ? null
                        : level.getChunkSource().getChunk(view.minX() + cx, view.minZ() + cz, false);
                for (int sz = 0; sz < sub; sz++) {
                    for (int sx = 0; sx < sub; sx++) {
                        int cellIndex = (cz * sub + sz) * cols + cx * sub + sx;
                        if (chunk == null) {
                            colours[cellIndex] = 0;
                            heights[cellIndex] = Integer.MIN_VALUE;
                            continue;
                        }
                        int lx = sx * step + step / 2;
                        int lz = sz * step + step / 2;
                        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                        pos.set(chunk.getPos().getMinBlockX() + lx, top, chunk.getPos().getMinBlockZ() + lz);
                        BlockState state = chunk.getBlockState(pos);
                        MapColor mapColour = state.getMapColor(level, pos);
                        colours[cellIndex] = mapColour == MapColor.NONE ? 0 : mapColour.col;
                        heights[cellIndex] = top;
                    }
                }
            }
        }
        int[] out = new int[width * depth * sub * sub];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                int colour;
                if (heights[i] == Integer.MIN_VALUE || colours[i] == 0) {
                    colour = heights[i] == Integer.MIN_VALUE ? UNLOADED : 0xFF2A2A2A;
                } else {
                    int north = row > 0 ? heights[i - cols] : Integer.MIN_VALUE;
                    float light = north == Integer.MIN_VALUE || north == heights[i] ? 220f / 255f
                            : heights[i] > north ? 1f : 180f / 255f;
                    colour = shade(0xFF000000 | colours[i], light);
                }
                int cz = row / sub;
                int sz = row % sub;
                int cx = col / sub;
                int sx = col % sub;
                out[((cz * width + cx) * sub + sz) * sub + sx] = colour;
            }
        }
        ground = out;
        groundSub = sub;
    }

    private static int shade(int argb, float factor) {
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }
}
