package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import com.alex.test.client.ClientRaidInfo;
import com.alex.test.village.raid.RaidOmenMath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.List;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/** Settings > Village rules > Raids: how things stand, fame, and the last raids. */
final class RaidsPage extends MayorPart {

    /** The page's top, its "< Village rules" link. */
    private static final int PAGE_Y = 88;

    private final SettingsTab settings;

    RaidsPage(MayorStatsScreen screen, SettingsTab settings) {
        super(screen);
        this.settings = settings;
    }

    void render(GuiGraphics graphics) {
        walk(graphics);
    }

    /**
     * The page's bottom edge, from the window's top, for the screen's page scroll: a long raid list
     * scrolls, a short page does not. Measured by the same steps that draw it, so the two agree.
     */
    int contentBottom() {
        return walk(null) - top();
    }

    /** Lays the page out from its top, drawing it when {@code graphics} is given; returns its last line's bottom. */
    private int walk(@Nullable GuiGraphics graphics) {
        boolean draw = graphics != null;
        int x = left() + CONTENT_X + 6;
        int y = top() + PAGE_Y;
        int width = innerW();
        int mayor = menu.mayorId();
        if (draw) {
            graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.village_rules", "< Village rules"), x, y, GOLD, false);
            graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.raids", "Raids"), x, y + 18, TEXT, false);
        }
        int lineY = y + 31;
        for (FormattedCharSequence line : font.split(Component.literal(ClientRaidInfo.status(mayor)), width - 4)) {
            if (draw) graphics.drawString(font, line, x + 2, lineY, MUTED, false);
            lineY += 10;
        }

        lineY += 6;
        if (draw) {
            int fame = ClientRaidInfo.fame(mayor);
            graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.raids.fame_value", "Fame %s / 100", fame),
                    x, lineY, TEXT, false);
            int barX = x + 92;
            int barW = width - 94;
            graphics.fill(barX, lineY + 1, barX + barW, lineY + 7, PANEL_BORDER);
            graphics.fill(barX + 1, lineY + 2, barX + 1 + Math.round((barW - 2) * Math.min(1f, fame / 100f)),
                    lineY + 6, GOLD);
        }
        lineY += 12;
        for (FormattedCharSequence line : font.split(Tr.c("screen.dynamicvillages.mayor.raids.fame", "Won raids make the village famous: more nomads come, and bigger raids. Fame fades slowly."),
                width - 4)) {
            if (draw) graphics.drawString(font, line, x + 2, lineY, MUTED, false);
            lineY += 10;
        }

        lineY += 6;
        if (draw) {
            graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.past_raids", "Past raids"), x, lineY, GOLD, false);
            String totals = ClientRaidInfo.totals(mayor);
            graphics.drawString(font, totals, x + width - font.width(totals), lineY, MUTED, false);
        }
        lineY += 13;
        int bestOmen = ClientRaidInfo.bestOmen(mayor);
        if (bestOmen > 0) {
            if (draw) {
                graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.raids.best_omen", "Strongest Bad Omen beaten: %s", RaidOmenMath.roman(bestOmen)),
                        x + 2, lineY, MUTED, false);
            }
            lineY += 13;
        }
        List<String> history = ClientRaidInfo.history(mayor);
        if (history.isEmpty()) {
            if (draw) graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.none_yet", "None yet."), x + 2, lineY, MUTED, false);
            return lineY + 9;
        }
        for (String entry : history) {
            if (draw) {
                graphics.fill(x, lineY - 2, x + width, lineY + 10, ROW);
                graphics.drawString(font, trim(entry, width - 6), x + 3, lineY, TEXT, false);
            }
            lineY += 13;
        }
        // The last row's background ends 3 px above the next row's start.
        return lineY - 3;
    }

    /** Only the "< Village rules" link does anything; the rest of the page swallows nothing. */
    boolean mouseClicked(double mouseX, double mouseY) {
        if (inside(mouseX, mouseY, left() + CONTENT_X + 6, top() + PAGE_Y, 90, 12)) {
            settings.closeRaids();
            return true;
        }
        return false;
    }
}
