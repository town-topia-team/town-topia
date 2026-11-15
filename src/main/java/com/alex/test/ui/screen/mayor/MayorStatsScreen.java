package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import com.alex.test.client.ClientRaidInfo;
import com.alex.test.client.ClientVillageLog;
import com.alex.test.client.VillagerTheme;
import com.alex.test.network.RaidPayloads;
import com.alex.test.town.BuildingInstanceEntry;
import com.alex.test.ui.MayorStatsMenu;
import com.alex.test.ui.screen.WikiPanel;
import com.alex.test.village.claims.ClaimLock;
import com.alex.test.village.claims.ClaimRank;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector2i;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.List;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * Minecraft-style village management screen opened by the Mayor. This file is the window: its frame,
 * title, Wiki button and tabs, fitting a small screen, and handing drawing, clicks and scrolling to the
 * open tab ({@link InfoTab}, {@link ShopTab}, {@link SettingsTab}, {@link ThemeTab}, {@link PerformanceTab},
 * {@link LogTab}, {@link LandTab}).
 */
public final class MayorStatsScreen extends AbstractContainerScreen<MayorStatsMenu> {

    /** The tabs along the top, in their order; a player sees {@link #tabs} of them. */
    enum Tab { INFO, SHOP, SETTINGS, LAND, THEME, PERFORMANCE, LOG }

    /** What the body shows right now; each fits a small screen its own way. */
    enum Page { BUILDINGS, BUILDING_DETAIL, VILLAGERS, VILLAGER_DETAIL, SHOP, RULES, PROFESSIONS, MINER, LAND, THEME, THEME_SET, PERFORMANCE, LOG, WIKI }

    /** Set by the house screen's "Back to Mayor", so the Mayor reopens on that house. */
    private record ReturnTarget(int mayorId, java.util.UUID buildingId, long madeAt) {}

    private static ReturnTarget returnTarget;

    /** Called by the house screen just before it asks the server to reopen the Mayor. */
    public static void returnToBuilding(int mayorId, java.util.UUID buildingId) {
        returnTarget = new ReturnTarget(mayorId, buildingId, System.currentTimeMillis());
    }

    /** {@code /mayorwidth}: widths tried for a test, not saved; 0 = the usual 380 and 420. */
    private static int testWidth;
    private static int testThemeWidth;

    public static void setTestWidth(int width) {
        testWidth = width;
    }

    public static void setTestThemeWidth(int width) {
        testThemeWidth = width;
    }

    /** The width wanted on every tab, or on the Theme tab and a set's page, before the screen's limit. */
    public static int wantedWidth(boolean theme) {
        if (theme) return testThemeWidth > 0 ? testThemeWidth : THEME_WIDTH;
        return testWidth > 0 ? testWidth : NORMAL_WIDTH;
    }

    /**
     * The window's width on a screen this many GUI pixels wide: the wanted width (380, or 420 on the Theme tab and a
     * set's page) where the screen has room, keeping 4 free on each side, never under 356. Even, so whatever is
     * centred inside lands on whole pixels.
     */
    public static int windowWidthFor(int screenWidth, boolean theme) {
        int w = Math.max(MIN_WIDTH, Math.min(wantedWidth(theme), screenWidth - 2 * SCREEN_MARGIN));
        return w - w % 2;
    }

    /** The Theme tab and a set's page are wider than the other tabs. */
    private boolean themeWide() {
        return tab == Tab.THEME || themeSet.isOpen();
    }

    private static final int TAB_Y = 24;
    /** The open tab shows its icon and name; the others only their icon (name on hover). */
    private static final int OPEN_TAB_W = 110;
    private static final int ICON_TAB_W = 40;
    /** Where the open tab's name starts: right of its icon. */
    private static final int OPEN_TAB_LABEL_X = 27;
    private static final int TAB_H = 23;
    /** With six tabs in a 356 window the row is exactly as wide as the body under it (340); seven shrink the icons. */
    private static final int TAB_GAP = 6;
    /** The Wiki button, on the title line above the last tab. */
    private static final int WIKI_BUTTON_Y = 3;
    private static final int WIKI_BUTTON_W = 54;
    private static final int WIKI_BUTTON_H = 18;
    /** The Wiki button's green, as the guide book's wiki button. */
    private static final int WIKI_BUTTON_FILL = 0xFF3B4E3E;
    private static final int WIKI_BUTTON_HOVER = 0xFF4E6F53;
    /** Free space kept above and below the window when it has to shrink. */
    private static final int SCREEN_MARGIN = 4;
    /** The window never shrinks below this; lists keep at least two rows. */
    private static final int MIN_FRAME_HEIGHT = 200;
    /** A tooltip's background and border reach this far past its text. */
    private static final int TIP_BORDER = 4;
    private static final int TIP_MOUSE_GAP = 12;

    private Tab tab = Tab.INFO;
    /** The tabs this player sees, in row order (Info always first); see {@link #visibleTabs}. */
    private List<Tab> tabs;
    /** {@code menu.landChanges()} when {@link #tabs} was last worked out: a People answer can change the rank. */
    private int tabsLandChanges;
    private final InfoTab info;
    private final ShopTab shop;
    private final SettingsTab settings;
    private final ThemeTab theme;
    /** A skin set's own page, opened from a Theme tab card: while open it has the whole window, tabs hidden. */
    private final ThemePackView themeSet;
    private final PerformanceTab performance;
    private final LogTab logTab;
    private final LandTab land;
    /** Every list that scrolls, in a fixed order; each says itself whether it is on screen. */
    private final List<ScrollList> scrollLists;
    @Nullable private ScrollList draggingScrollbar;
    /** Info > Villagers: the search above the list (it matches professions). */
    private EditBox villagerSearch;
    private EditBox buildingSearch;
    /** Land > People, for the owner: a player's name to add. */
    private EditBox landNameBox;
    /** The window's height on this screen: 284, or less on a small GUI. */
    private int frameHeight = HEIGHT;
    /** The window's width on this screen, set in {@link #init}; every width inside comes from it. */
    private int windowW = MIN_WIDTH;
    /** How far a fixed page (rules, a villager, the miner) is scrolled on a small screen. */
    private int pageScroll;
    private Page lastPage;
    /** Hover text from inside the body, drawn after it so the body's clipping cannot cut it. */
    @Nullable private List<FormattedCharSequence> deferredTooltip;
    /** While true the Wiki view has the whole window and the tabs are hidden. */
    private boolean wikiOpen;
    private WikiPanel wiki;

    public MayorStatsScreen(MayorStatsMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = MIN_WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelY = 1000;
        info = new InfoTab(this);
        shop = new ShopTab(this);
        settings = new SettingsTab(this);
        theme = new ThemeTab(this);
        themeSet = new ThemePackView(this);
        performance = new PerformanceTab(this);
        logTab = new LogTab(this);
        land = new LandTab(this);
        tabs = visibleTabs(menu);
        scrollLists = List.of(info.buildings().list, info.buildings().detail, info.villagers().list,
                shop.list, settings.professions().list, logTab.list, land.peopleList);
        // Once per opening (not in init, which a window resize runs again): init asks for the fresh answer.
        ClientRaidInfo.forget();
        // The log is asked for when its tab opens; an answer kept from another world must not show meanwhile.
        ClientVillageLog.forget();
    }

    @Override
    protected void init() {
        // A small GUI (scale Auto on a 1080p or 720p screen) is shorter than the
        // full 284: the window then starts near the top and each page trims
        // list rows, or scrolls, to end above the screen's bottom.
        frameHeight = Math.min(HEIGHT, Math.max(MIN_FRAME_HEIGHT, height - 2 * SCREEN_MARGIN));
        imageHeight = frameHeight;
        // Before super.init, which centres the window by imageWidth, and before the search boxes take their width.
        windowW = windowWidthFor(width, themeWide());
        imageWidth = windowW;
        super.init();
        // The Village rules page's Raids row: its setting and status line come on their own.
        PacketDistributor.sendToServer(new RaidPayloads.RequestInfo(menu.mayorId()));
        villagerSearch = addRenderableWidget(info.villagers().makeSearch());
        buildingSearch = addRenderableWidget(info.buildings().makeSearch());
        landNameBox = addRenderableWidget(land.makeNameBox());
        landNameBox.visible = false;

        wiki = new WikiPanel(font, windowW, CONTENT_X);

        // Back from a house screen the Mayor opened: land on that house again.
        ReturnTarget target = returnTarget;
        returnTarget = null;
        if (target != null && target.mayorId() == menu.mayorId()
                && System.currentTimeMillis() - target.madeAt() < 10_000) {
            List<BuildingInstanceEntry> instances = menu.snapshot().buildingInstances();
            for (int i = 0; i < instances.size(); i++) {
                if (instances.get(i).id().equals(target.buildingId())) {
                    tab = Tab.INFO;
                    info.showBuilding(i);
                    break;
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // For the tabs and pages (same package)
    // ---------------------------------------------------------------------

    int left() {
        return leftPos;
    }

    /** Moved up by the page's scroll while a long page is drawn or clicked. */
    int top() {
        return topPos;
    }

    int windowW() {
        return windowW;
    }

    /** The dark body: the window less its edge on each side (340 in a 356 window). */
    int contentW() {
        return windowW - CONTENT_X * 2;
    }

    /** Inside the body, 6 from each side (328 in a 356 window). */
    int innerW() {
        return contentW() - 12;
    }

    /** Beside the scrollbar (322 in a 356 window), or as wide as {@link #innerW} for a list with none. */
    int listW(boolean scrolls) {
        return scrolls ? innerW() - SHOP_SCROLLBAR_GAP - SHOP_SCROLLBAR_W : innerW();
    }

    Tab tab() {
        return tab;
    }

    int mayorId() {
        return menu.mayorId();
    }

    void setDeferredTooltip(List<FormattedCharSequence> lines) {
        deferredTooltip = lines;
    }

    /** The window's height on this screen: 284, or less on a small GUI (never under 200). */
    int frameHeight() {
        return frameHeight;
    }

    /**
     * Opens a skin set's own page from the Theme tab (null: the "Your look" page), on that entry of
     * {@link VillagerPreview#ENTRIES} (or -1).
     */
    void openPack(@Nullable VillagerTheme set, int entry) {
        themeSet.open(set, entry);
        draggingScrollbar = null;
        villagerSearch.setFocused(false);
        buildingSearch.setFocused(false);
        setFocused(null);
    }

    int pageScroll() {
        return pageScroll;
    }

    /**
     * Sets the scroll of the page shown now; call it after the click that opened that page. It also marks
     * this page as the one drawn last, so the next frame does not see a new page and reset the scroll to 0.
     */
    void setPageScroll(int value) {
        lastPage = page();
        pageScroll = value;
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // A village paused without its Town Hall token gets only the paused page.
        if (menu.pausedTownHall() != null) {
            villagerSearch.visible = false;
            buildingSearch.visible = false;
            landNameBox.visible = false;
            MayorPausedPanel.render(graphics, font, menu, leftPos, topPos, windowW, mouseX, mouseY);
            return;
        }
        // A running village nobody owns (claims on) gets only its Claim page.
        if (menu.unclaimed()) {
            villagerSearch.visible = false;
            buildingSearch.visible = false;
            landNameBox.visible = false;
            MayorClaimPanel.render(graphics, font, menu, leftPos, topPos, windowW, mouseX, mouseY);
            return;
        }
        int x = leftPos;
        int y = topPos;
        if (wikiOpen) {
            villagerSearch.visible = false;
            buildingSearch.visible = false;
            landNameBox.visible = false;
            graphics.fill(x - 2, y - 2, x + windowW + 2, y + frameHeight + 2, OUTER);
            graphics.fill(x, y, x + windowW, y + frameHeight, PANEL);
            wiki.render(graphics, x, y, frameHeight, mouseX, mouseY);
            return;
        }
        if (themeSet.isOpen()) {
            villagerSearch.visible = false;
            buildingSearch.visible = false;
            landNameBox.visible = false;
            deferredTooltip = null;
            themeSet.render(graphics, mouseX, mouseY);
            return;
        }
        boolean showSearch = tab == Tab.INFO && info.villagerListShown();
        villagerSearch.visible = showSearch;
        if (!showSearch) villagerSearch.setFocused(false);
        boolean showBuildingSearch = tab == Tab.INFO && info.buildingListShown();
        buildingSearch.visible = showBuildingSearch;
        if (!showBuildingSearch) buildingSearch.setFocused(false);
        // A People answer can change this player's rank (handed the village over): the tabs follow it.
        if (menu.landChanges() != tabsLandChanges) {
            tabsLandChanges = menu.landChanges();
            tabs = visibleTabs(menu);
            if (!tabs.contains(tab)) tab = tabs.contains(Tab.LAND) ? Tab.LAND : Tab.INFO;
        }
        boolean showNameBox = tab == Tab.LAND && land.nameBoxShown();
        landNameBox.visible = showNameBox;
        if (!showNameBox) landNameBox.setFocused(false);

        deferredTooltip = null;
        Page page = page();
        if (page != lastPage) {
            lastPage = page;
            pageScroll = 0;
        }
        pageScroll = Math.min(pageScroll, pageScrollMax());

        int viewHeight = viewHeight();
        graphics.fill(x - 2, y - 2, x + windowW + 2, y + viewHeight + 2, OUTER);
        graphics.fill(x, y, x + windowW, y + viewHeight, PANEL);
        graphics.fill(x + CONTENT_X, y + BODY_Y,
                x + CONTENT_X + contentW(), y + bodyBottom(), PANEL_DARK);

        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.mayor_village_management", "Mayor - Village Management"), x + tabX(tabs.get(0)), y + 8, TEXT, false);
        drawWikiButton(graphics, x, y, mouseX, mouseY);
        for (Tab target : tabs) drawTab(graphics, target, x + tabX(target), y + TAB_Y, mouseX, mouseY);

        // A fixed page that does not fit scrolls under the tabs: drawn higher up
        // by the scroll amount, and always clipped to the body, at the top too,
        // or its natural height runs past the shorter window's bottom.
        boolean clipped = pageScrollMax() > 0;
        int scroll = pageScroll;
        if (clipped) {
            graphics.enableScissor(x + CONTENT_X, y + BODY_Y, x + CONTENT_X + contentW(), y + bodyBottom());
            topPos -= scroll;
        }
        try {
            switch (tab) {
                case INFO -> info.render(graphics, mouseX, mouseY);
                case SHOP -> shop.render(graphics, mouseX, mouseY);
                case SETTINGS -> settings.render(graphics, mouseX, mouseY);
                case PERFORMANCE -> performance.render(graphics, mouseX, mouseY);
                case LOG -> logTab.render(graphics, mouseX, mouseY);
                case THEME -> theme.render(graphics, mouseX, mouseY);
                case LAND -> land.render(graphics, mouseX, mouseY);
            }
        } finally {
            if (clipped) {
                topPos += scroll;
                graphics.disableScissor();
            }
        }
        int max = pageScrollMax();
        if (max > 0) {
            int trackY = y + BODY_Y + 4;
            int trackH = bodyBottom() - BODY_Y - 8;
            int thumbH = Math.max(16, trackH * trackH / (trackH + max));
            int thumbY = trackY + (trackH - thumbH) * pageScroll / max;
            int barX = x + CONTENT_X + contentW() - 4;
            graphics.fill(barX, trackY, barX + 3, trackY + trackH, 0xFF333333);
            graphics.fill(barX, thumbY, barX + 3, thumbY + thumbH, 0xFF888888);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Into or out of the Theme tab (or a set's page): the window takes its other width, centred again with its
        // search boxes, before it is drawn. As a game window resize does.
        if (windowWidthFor(width, themeWide()) != windowW) rebuildWidgets();
        super.render(graphics, mouseX, mouseY, partialTick);
        if (panelOnly()) return;
        if (wikiOpen) {
            wiki.renderTooltip(graphics, leftPos, topPos, mouseX, mouseY);
            return;
        }
        if (themeSet.isOpen()) {
            if (deferredTooltip != null) graphics.renderTooltip(font, deferredTooltip, windowTooltipPositioner(), mouseX, mouseY);
            return;
        }
        if (tab == Tab.INFO) info.renderTooltips(graphics, mouseX, mouseY);
        // An icon-only tab's name (or the open tab's, if a language makes it too long to show whole).
        Tab hoveredTab = tabAt(mouseX, mouseY);
        if (hoveredTab != null && (hoveredTab != tab || !openTabLabelFits())) {
            graphics.renderTooltip(font, tooltipLines(Component.literal(tabLabel(hoveredTab))),
                    windowTooltipPositioner(), mouseX, mouseY);
        }
        // Not for a row scrolled out of sight under the tabs or past the bottom.
        if (deferredTooltip != null && (pageScrollMax() <= 0 || inside(mouseX, mouseY,
                leftPos + CONTENT_X, topPos + BODY_Y, contentW(), bodyBottom() - BODY_Y))) {
            graphics.renderTooltip(font, deferredTooltip, windowTooltipPositioner(), mouseX, mouseY);
        }
    }

    /**
     * Puts a hover box to the right of the mouse (to the left when it does not
     * fit), and keeps all of it, border included, inside the Mayor window as it
     * is drawn now (shorter on a small GUI; a skin set's page is the same
     * window). A box too big for the window is only kept on the screen.
     */
    private ClientTooltipPositioner windowTooltipPositioner() {
        int left = leftPos;
        int width = windowW;
        return (screenWidth, screenHeight, mouseX, mouseY, tipWidth, tipHeight) -> {
            int minX = left + TIP_BORDER;
            int maxX = left + width - TIP_BORDER;
            int minY = topPos + TIP_BORDER;
            int maxY = topPos + viewHeight() - TIP_BORDER;
            if (tipWidth > maxX - minX || tipHeight > maxY - minY) {
                minX = TIP_BORDER;
                maxX = screenWidth - TIP_BORDER;
                minY = TIP_BORDER;
                maxY = screenHeight - TIP_BORDER;
            }
            int x = mouseX + TIP_MOUSE_GAP;
            if (x + tipWidth > maxX) x = mouseX - TIP_MOUSE_GAP - tipWidth;
            int y = mouseY - TIP_MOUSE_GAP;
            x = Math.max(minX, Math.min(x, maxX - tipWidth));
            y = Math.max(minY, Math.min(y, maxY - tipHeight));
            return new Vector2i(x, y);
        };
    }

    /** Right edge on the tab row's right end, so it sits just above the last tab (the body's edge). */
    private int wikiButtonX() {
        Tab last = tabs.get(tabs.size() - 1);
        return tabX(last) + tabW(last) - WIKI_BUTTON_W;
    }

    private void drawWikiButton(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        int bx = x + wikiButtonX();
        int by = y + WIKI_BUTTON_Y;
        boolean hovered = inside(mouseX, mouseY, bx, by, WIKI_BUTTON_W, WIKI_BUTTON_H);
        // Green like the guide book's wiki button, so it stands out from the grey controls.
        graphics.fill(bx, by, bx + WIKI_BUTTON_W, by + WIKI_BUTTON_H, ACCENT);
        graphics.fill(bx + 1, by + 1, bx + WIKI_BUTTON_W - 1, by + WIKI_BUTTON_H - 1,
                hovered ? WIKI_BUTTON_HOVER : WIKI_BUTTON_FILL);
        graphics.renderFakeItem(new ItemStack(Items.BOOK), bx + 2, by + 1);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.wiki", "Wiki"), bx + 21, by + 5, TEXT, false);
    }

    private void openWiki() {
        wikiOpen = true;
        draggingScrollbar = null;
        villagerSearch.setFocused(false);
        buildingSearch.setFocused(false);
        setFocused(null);
    }

    private void closeWiki() {
        wikiOpen = false;
    }

    /**
     * A tab's left edge from the window's: the row is centred, and every tab before this one takes its
     * width (wide when open) plus a gap. Drawing, clicking and hover text all use this and {@link #tabW},
     * so they always agree, also right after a click changed which tab is open.
     */
    private int tabX(Tab target) {
        int rowW = -TAB_GAP;
        for (Tab each : tabs) rowW += tabW(each) + TAB_GAP;
        int x = (windowW - rowW) / 2;
        for (Tab before : tabs) {
            if (before == target) break;
            x += tabW(before) + TAB_GAP;
        }
        return x;
    }

    /**
     * In a 356 window the row is the body's width (open tab 110, icons 40). A wider body's extra room is shared out
     * evenly: each tab gets a sixth, the open one also the pixels left over, so the row still ends with the body
     * (380 wide: open tab 114, icons 44; the 420 Theme tab: 124 and 50). Sized for the window as it is now, so on
     * Theme the row, the title and the Wiki button widen with the frame (asked 10-23: a narrower row looked off).
     * Seven tabs (Land, 10-24) do not fit at 40: the icon tabs narrow instead (380: 36, open 112; 356: 32, open 112).
     */
    private int tabW(Tab target) {
        int count = tabs.size();
        int icons = count - 1;
        int rowRoom = windowW - CONTENT_X * 2;
        int extra = rowRoom - (OPEN_TAB_W + icons * (ICON_TAB_W + TAB_GAP));
        if (extra < 0 && icons > 0) {
            int iconW = (rowRoom - OPEN_TAB_W - icons * TAB_GAP) / icons;
            return target == tab ? rowRoom - icons * (iconW + TAB_GAP) : iconW;
        }
        int share = Math.max(0, extra) / count;
        return target == tab ? OPEN_TAB_W + Math.max(0, extra) - share * icons : ICON_TAB_W + share;
    }

    /**
     * The tabs this player sees, in row order, by their rank here (village claims rounds 3-4; claims off or nobody's
     * village: all). Owner and Officer: all. Friend: Info, Shop, Land, Log. Visitor and Hostile: Info, Land, Log, to
     * look at. No Land tab for a village with no Town Hall.
     */
    private static List<Tab> visibleTabs(MayorStatsMenu menu) {
        boolean everything = may(menu, ClaimLock.Act.MANAGE);
        boolean shop = may(menu, ClaimLock.Act.SHOP);
        List<Tab> out = new java.util.ArrayList<>();
        for (Tab each : Tab.values()) {
            if (each == Tab.LAND && menu.land() == null) continue;
            boolean lookOnly = each == Tab.INFO || each == Tab.LAND || each == Tab.LOG;
            if (everything || lookOnly || (each == Tab.SHOP && shop)) out.add(each);
        }
        return List.copyOf(out);
    }

    /** This player's rank in the Mayor's village; Owner when claims are off or nobody owns it. */
    static ClaimRank myRank(MayorStatsMenu menu) {
        com.alex.test.village.claims.LandView landView = menu.land();
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        return landView == null || player == null ? ClaimRank.OWNER : landView.windowRank(player.getUUID());
    }

    /** Whether the server would take this from this player: the village's rights grid it checks too (round 9). */
    static boolean may(MayorStatsMenu menu, ClaimLock.Act act) {
        com.alex.test.village.claims.LandView landView = menu.land();
        return landView == null || landView.rights().allows(myRank(menu), act);
    }

    /** The owner's name for a greyed button's reason ("Only people Anna trusts..."). */
    static String ownerName(MayorStatsMenu menu) {
        return menu.land() != null ? menu.land().ownerName() : "";
    }

    /** The tab under the mouse, or null. Only for the tab row, which never scrolls. */
    @Nullable
    private Tab tabAt(double mouseX, double mouseY) {
        for (Tab target : tabs) {
            if (inside(mouseX, mouseY, leftPos + tabX(target), topPos + TAB_Y, tabW(target), TAB_H)) return target;
        }
        return null;
    }

    private static String tabLabel(Tab target) {
        return switch (target) {
            case INFO -> Tr.s("screen.dynamicvillages.mayor.tab.info", "Village Info");
            case SHOP -> Tr.s("screen.dynamicvillages.mayor.tab.shop", "Shop");
            case SETTINGS -> Tr.s("screen.dynamicvillages.mayor.tab.settings", "Settings");
            case LAND -> Tr.s("screen.dynamicvillages.mayor.tab.land", "Land");
            case THEME -> Tr.s("screen.dynamicvillages.mayor.tab.theme", "Theme");
            case PERFORMANCE -> Tr.s("screen.dynamicvillages.mayor.tab.performance", "Performance");
            case LOG -> Tr.s("screen.dynamicvillages.mayor.tab.log", "Village Log");
        };
    }

    private static Item tabIcon(Tab target) {
        return switch (target) {
            case INFO -> Items.WRITABLE_BOOK;
            case SHOP -> Items.EMERALD;
            case SETTINGS -> Items.COMPARATOR;
            case LAND -> Items.FILLED_MAP;
            case THEME -> Items.PAINTING;
            case PERFORMANCE -> Items.CLOCK;
            case LOG -> Items.LECTERN;
        };
    }

    private boolean openTabLabelFits() {
        return font.width(tabLabel(tab)) <= openTabLabelW();
    }

    /** Room for the open tab's name: right of its icon, inside its border. */
    private int openTabLabelW() {
        return tabW(tab) - OPEN_TAB_LABEL_X - 4;
    }

    private void drawTab(GuiGraphics graphics, Tab target, int x, int y, int mouseX, int mouseY) {
        boolean selected = tab == target;
        int w = tabW(target);
        boolean hovered = inside(mouseX, mouseY, x, y, w, TAB_H);
        int color = selected ? 0xFF3B4E3E : hovered ? ROW_HOVER : ROW;
        graphics.fill(x, y, x + w, y + TAB_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + TAB_H, color);
        ItemStack icon = new ItemStack(tabIcon(target));
        if (selected) {
            graphics.fill(x + 1, y + TAB_H - 2, x + w - 1, y + TAB_H, ACCENT);
            graphics.renderFakeItem(icon, x + 6, y + 3);
            graphics.drawString(font, trim(tabLabel(target), openTabLabelW()), x + OPEN_TAB_LABEL_X, y + 8, TEXT, false);
        } else {
            graphics.renderFakeItem(icon, x + (w - 16) / 2, y + 3);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // All labels use screen-relative coordinates in renderBg.
    }

    // ---------------------------------------------------------------------
    // Fitting small screens
    // ---------------------------------------------------------------------

    private Page page() {
        if (wikiOpen) return Page.WIKI;
        if (themeSet.isOpen()) return Page.THEME_SET;
        return switch (tab) {
            case INFO -> info.page();
            case SHOP -> Page.SHOP;
            case SETTINGS -> settings.page();
            case PERFORMANCE -> Page.PERFORMANCE;
            case LOG -> Page.LOG;
            case THEME -> Page.THEME;
            case LAND -> Page.LAND;
        };
    }

    /** Pixels the full 284-high window is taller than the screen allows. */
    int need() {
        return HEIGHT - frameHeight;
    }

    /** Whole rows a list gives up so its page fits, never leaving fewer than {@code minRows}. */
    int rowsLost(int step, int fullRows, int minRows) {
        int need = need();
        return need <= 0 ? 0 : Math.min(fullRows - minRows, (need + step - 1) / step);
    }

    /**
     * How much shorter than 284 this page is drawn. A list page loses whole
     * rows, and the window's bottom moves up with them, so no gap is left.
     * A fixed page keeps the window's height and scrolls instead.
     */
    private int pageCut() {
        return switch (page()) {
            case BUILDINGS -> info.buildings().listCut();
            case BUILDING_DETAIL -> info.buildings().detailCut();
            case VILLAGERS -> info.villagers().listCut();
            case SHOP -> shop.listCut();
            case LOG -> logTab.listCut();
            case PROFESSIONS -> settings.professions().listCut();
            // The Wiki view, the Theme tab, a skin set's page and the Land map (its cells shrink) fit the shortest window.
            case VILLAGER_DETAIL, RULES, MINER, LAND, THEME, THEME_SET, PERFORMANCE, WIKI -> need();
        };
    }

    private int viewHeight() {
        return HEIGHT - pageCut();
    }

    /** The body's bottom edge, the same gutter under it as at its sides. */
    private int bodyBottom() {
        return viewHeight() - CONTENT_X;
    }

    /** How far a fixed page scrolls: its content's bottom past the body's. */
    private int pageScrollMax() {
        int contentBottom = switch (page()) {
            case VILLAGER_DETAIL -> DETAIL_Y + info.villagers().cardH();
            case RULES -> settings.pageBottom();
            case MINER -> INFO_BOTTOM;
            case THEME -> theme.pageBottom();
            case PERFORMANCE -> performance.pageBottom();
            default -> 0;
        };
        return Math.max(0, contentBottom - (bodyBottom() - (HEIGHT - CONTENT_X - INFO_BOTTOM)));
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (wikiOpen && !panelOnly()) {
            // Esc goes back to the village instead of closing the Mayor at once.
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeWiki();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // Esc on a skin set's page goes back to the Theme tab, as from the Wiki view.
        if (themeSet.isOpen() && !panelOnly() && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            themeSet.close();
            return true;
        }
        if (villagerSearch != null && villagerSearch.visible && villagerSearch.isFocused()
                && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            villagerSearch.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (buildingSearch != null && buildingSearch.visible && buildingSearch.isFocused()
                && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            buildingSearch.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (landNameBox != null && landNameBox.visible && landNameBox.isFocused()
                && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) land.submitName();
            else landNameBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (menu.pausedTownHall() != null) {
            return MayorPausedPanel.mouseClicked(menu, leftPos, topPos, windowW, mouseX, mouseY)
                    || super.mouseClicked(mouseX, mouseY, button);
        }
        if (menu.unclaimed()) {
            return MayorClaimPanel.mouseClicked(menu, leftPos, topPos, windowW, mouseX, mouseY)
                    || super.mouseClicked(mouseX, mouseY, button);
        }
        if (wikiOpen) {
            WikiPanel.Click click = wiki.mouseClicked(this, mouseX, mouseY, button, leftPos, topPos);
            if (click == WikiPanel.Click.NONE) return super.mouseClicked(mouseX, mouseY, button);
            if (click == WikiPanel.Click.LEAVE) closeWiki();
            return true;
        }
        if (themeSet.isOpen()) {
            return themeSet.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
        }
        // A scrolled fixed page is drawn higher by pageScroll; clicks in the
        // body are measured the same way. Above the body only the tabs count.
        // Parts of the page clipped away above or below the body cannot be clicked.
        int scroll = pageScroll;
        if (pageScrollMax() > 0 && mouseY >= topPos + BODY_Y) {
            if (mouseY >= topPos + bodyBottom()) return super.mouseClicked(mouseX, mouseY, button);
            topPos -= scroll;
            try {
                return clickScreen(mouseX, mouseY, button);
            } finally {
                topPos += scroll;
            }
        }
        return clickScreen(mouseX, mouseY, button);
    }

    private boolean clickScreen(double mouseX, double mouseY, int button) {
        int x = leftPos;
        int y = topPos;
        if (button == 0) {
            ScrollList hitScrollbar = scrollbarAt(mouseX, mouseY);
            if (hitScrollbar != null) {
                draggingScrollbar = hitScrollbar;
                updateScrollFromMouse(hitScrollbar, mouseY);
                return true;
            }
        }

        if (button == 0 && inside(mouseX, mouseY, x + wikiButtonX(), y + WIKI_BUTTON_Y,
                WIKI_BUTTON_W, WIKI_BUTTON_H)) {
            openWiki();
            return true;
        }

        Tab clickedTab = tabAt(mouseX, mouseY);
        if (clickedTab != null) {
            boolean opened = clickedTab != tab;
            tab = clickedTab;
            draggingScrollbar = null;
            if (opened && tab == Tab.PERFORMANCE) performance.opened();
            if (opened && tab == Tab.LOG) logTab.opened();
            if (opened && tab == Tab.LAND) land.opened();
            return true;
        }

        // The open tab first; what it does not use goes to the screen's widgets.
        boolean used = switch (tab) {
            case INFO -> info.mouseClicked(mouseX, mouseY, button);
            case SHOP -> shop.mouseClicked(mouseX, mouseY);
            case SETTINGS -> settings.mouseClicked(mouseX, mouseY, button);
            case PERFORMANCE -> performance.mouseClicked(mouseX, mouseY);
            case LOG -> logTab.mouseClicked(mouseX, mouseY);
            case THEME -> theme.mouseClicked(mouseX, mouseY, button);
            case LAND -> land.mouseClicked(mouseX, mouseY);
        };
        return used || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingScrollbar != null) {
            updateScrollFromMouse(draggingScrollbar, mouseY);
            return true;
        }
        // After a press on a skin set page's big villager, mouse moves turn it.
        if (button == 0 && themeSet.isOpen() && themeSet.mouseDragged(dragX, dragY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar != null) {
            draggingScrollbar = null;
            return true;
        }
        // Wherever the button is let go, also outside the window, the villager stops turning.
        if (button == 0 && themeSet.mouseReleased()) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (wikiOpen) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (themeSet.isOpen()) {
            return themeSet.mouseScrolled(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int pageMax = pageScrollMax();
        if (pageMax > 0 && inside(mouseX, mouseY, leftPos + CONTENT_X, topPos + BODY_Y,
                contentW(), bodyBottom() - BODY_Y)) {
            pageScroll = Math.max(0, Math.min(pageMax, pageScroll + (scrollY < 0 ? 12 : -12)));
            return true;
        }
        ScrollList list = scrollListAt(mouseX, mouseY);
        if (list != null) {
            list.setScroll(clampScroll(list.scroll(), list.maxScroll(), scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** The list on screen under the mouse, its side bar included. */
    @Nullable
    private ScrollList scrollListAt(double mouseX, double mouseY) {
        for (ScrollList list : scrollLists) {
            if (!list.shown()) continue;
            int width = list.width()
                    + (list.sideBar() ? SHOP_SCROLLBAR_GAP + SHOP_SCROLLBAR_W : 0);
            if (inside(mouseX, mouseY, list.x(), list.y(), width, list.height())) {
                return list;
            }
        }
        return null;
    }

    /** The list on screen whose scrollbar is under the mouse. */
    @Nullable
    private ScrollList scrollbarAt(double mouseX, double mouseY) {
        for (ScrollList list : scrollLists) {
            if (!list.shown() || list.maxScroll() <= 0) continue;
            int x = list.x();
            int y = list.y();
            int width = list.width();
            int height = list.height();
            boolean hit = list.sideBar()
                    ? inside(mouseX, mouseY, x + width + SHOP_SCROLLBAR_GAP - 1, y, SHOP_SCROLLBAR_W + 2, height)
                    : inside(mouseX, mouseY, x + width - 8, y, 8, height);
            if (hit) return list;
        }
        return null;
    }

    /** Puts the thumb's middle under the mouse. */
    private static void updateScrollFromMouse(ScrollList list, double mouseY) {
        int max = list.maxScroll();
        if (max <= 0) return;
        int listY = list.y();
        int height = list.height();
        boolean side = list.sideBar();
        int trackY = side ? listY : listY + 4;
        int trackHeight = side ? height : height - 8;
        int thumbH = list.thumbHeight();
        double t = (mouseY - trackY - thumbH / 2.0) / Math.max(1, trackHeight - thumbH);
        list.setScroll(Math.max(0, Math.min(max, (int) Math.round(t * max))));
    }

    private static int clampScroll(int current, int max, double delta) {
        if (delta > 0) return Math.max(0, current - 1);
        if (delta < 0) return Math.min(max, current + 1);
        return current;
    }

    /** The paused page or the Claim page fills the whole window: no tabs, Wiki or tab ticks behind it. */
    private boolean panelOnly() {
        return menu.pausedTownHall() != null || menu.unclaimed();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // The open Performance tab asks the server again every 2 s, the Village Log every 5 s; the Theme
        // tab's and a skin set page's preview villagers age, so their idle clip plays.
        if (wikiOpen || panelOnly()) return;
        if (themeSet.isOpen()) themeSet.tick();
        else if (tab == Tab.PERFORMANCE) performance.tick();
        else if (tab == Tab.LOG) logTab.tick();
        else if (tab == Tab.THEME) theme.tick();
        else if (tab == Tab.LAND) land.tick();
    }
}
