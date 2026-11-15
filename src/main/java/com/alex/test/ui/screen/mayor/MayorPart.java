package com.alex.test.ui.screen.mayor;

import com.alex.test.ui.MayorStatsMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * A piece of the Mayor window, a tab or one of its pages: the screen it draws in, the Mayor's data,
 * and the font. Positions are read from the screen each time, never kept: while a long page is
 * drawn or clicked on a small screen, the screen's top is moved up by the page's scroll.
 */
abstract class MayorPart {

    final MayorStatsScreen screen;
    final MayorStatsMenu menu;
    /** The screen's own font ({@code Screen.font} is protected; it is this same one). */
    final Font font = Minecraft.getInstance().font;

    MayorPart(MayorStatsScreen screen) {
        this.screen = screen;
        this.menu = screen.getMenu();
    }

    /** The window's left edge. */
    int left() {
        return screen.left();
    }

    /** The window's top edge, as the page is drawn or clicked right now (see the class note). */
    int top() {
        return screen.top();
    }

    /** The window's width on this screen; never keep it, nor any size worked out from it. */
    int windowW() {
        return screen.windowW();
    }

    /** The dark body under the tabs. */
    int contentW() {
        return screen.contentW();
    }

    /** Inside the body, 6 from each side: lists with their scrollbar, detail cards, Settings rows. */
    int innerW() {
        return screen.innerW();
    }

    /** A two-column list: beside its scrollbar, or also over the scrollbar's place when it has none. */
    int listW(boolean scrolls) {
        return screen.listW(scrolls);
    }

    /** The two section buttons side by side under the tabs (Settings, Performance): each this wide, 8 apart. */
    int sectionButtonW() {
        return (innerW() - 8) / 2;
    }

    /** The second section button's left edge, from the first one's. */
    int secondSectionX(int x) {
        return x + sectionButtonW() + 8;
    }
}
