package com.alex.test.ui.screen.mayor;

/**
 * A list that scrolls by whole rows (or lines). {@link MayorStatsScreen} moves it with the mouse wheel
 * and its dragged bar; the page that owns it draws the list and the bar. A new list is one more entry
 * in the screen's {@code scrollLists}.
 */
interface ScrollList {

    /** On screen right now: its tab and its page are open. */
    boolean shown();

    int x();

    int y();

    /** The list's own width, without a bar beside it. */
    int width();

    int height();

    /** True: the mod's usual 4-wide bar beside the list. False: the thin bar inside its right edge. */
    boolean sideBar();

    int scroll();

    void setScroll(int value);

    int maxScroll();

    /** The bar's thumb as it is drawn, in pixels. */
    int thumbHeight();
}
