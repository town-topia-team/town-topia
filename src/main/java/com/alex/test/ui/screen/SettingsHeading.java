package com.alex.test.ui.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A heading line on a settings tab ("Farming Behaviour", "Additional
 * Behaviour"). Plain text with a rule after it: no row box, no switch, no
 * hover text, and the screens skip it when clicking.
 */
final class SettingsHeading {

    private static final int TEXT = 0xFF6BAF72;
    private static final int RULE = 0xFF3B3B3B;

    private SettingsHeading() {}

    /** Draws the heading in a 20 px tall slot, like the rows around it. */
    static void draw(GuiGraphics graphics, Font font, int x, int y, int width, String label) {
        int textX = x + 4;
        graphics.drawString(font, label, textX, y + 8, TEXT, false);
        int ruleX = textX + font.width(label) + 6;
        if (ruleX < x + width - 4) graphics.fill(ruleX, y + 12, x + width - 4, y + 13, RULE);
    }
}
