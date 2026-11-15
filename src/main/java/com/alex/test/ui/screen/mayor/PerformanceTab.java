package com.alex.test.ui.screen.mayor;

import com.alex.test.ClientConfig;
import com.alex.test.Tr;
import com.alex.test.performance.PerformanceOption.Group;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Items;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * The Performance tab: Server | This computer on top, as the Settings tab's two buttons. Server is
 * {@link MayorPerformancePage} (world-wide options, server owner only). This computer holds the settings saved
 * on this computer only ({@link ClientConfig}): today "Villager limbs" (moved here from Village rules, 10-22).
 */
final class PerformanceTab extends MayorPart {

    /** The page under the two buttons, where the Settings tab's pages start too. */
    private static final int PAGE_Y = 88;
    /** This computer's first row, from the page's top: under two lines, as on the Server side. */
    private static final int CLIENT_ROWS_Y = 24;

    private Group section = Group.SERVER;
    /** Ticks since the Server page last asked the server. */
    private int askTicks;
    private final MayorPerformancePage server;

    PerformanceTab(MayorStatsScreen screen) {
        super(screen);
        server = new MayorPerformancePage(screen);
    }

    /** The tab was opened: drop the last answer (maybe another world's), then ask. */
    void opened() {
        askTicks = 0;
        server.open();
    }

    /** The bottom of what the tab shows now, for the screen's page scroll. */
    int pageBottom() {
        return section == Group.SERVER ? PAGE_Y + MayorPerformancePage.height() : PAGE_Y + CLIENT_ROWS_Y + RULE_ROW_H;
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + 6;
        int y = top();
        int width = innerW();
        drawSegment(graphics, x, y + 61, sectionButtonW(), section == Group.SERVER, Group.SERVER.title(), mouseX, mouseY);
        drawSegment(graphics, secondSectionX(x), y + 61, sectionButtonW(), section == Group.CLIENT, Group.CLIENT.title(), mouseX, mouseY);
        if (section == Group.SERVER) {
            server.render(graphics, x, y + PAGE_Y, width, mouseX, mouseY);
        } else {
            renderThisComputer(graphics, x, y + PAGE_Y, width, mouseX, mouseY);
        }
    }

    /** Settings saved on this computer: they change only this player's game, at once, and need no server. */
    private void renderThisComputer(GuiGraphics graphics, int x, int y, int width, int mouseX, int mouseY) {
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.performance.client_text",
                "These change only your own game, on this computer."), width - 4), x + 2, y, MUTED, false);
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.performance.client_others",
                "Other players pick their own."), width - 4), x + 2, y + 11, DIM, false);
        int rowY = y + CLIENT_ROWS_Y;
        ClientConfig.LimbStyle limbs = ClientConfig.limbStyle();
        drawRuleRow(graphics, x, rowY, width, Items.ARMOR_STAND, Tr.s("screen.dynamicvillages.mayor.rule.limbs", "Villager limbs"),
                limbs == ClientConfig.LimbStyle.SMOOTH ? Tr.s("screen.dynamicvillages.mayor.rule.limbs.smooth", "Arms and legs bend in a curve")
                        : Tr.s("screen.dynamicvillages.mayor.rule.limbs.blocky", "Straight boxes, like classic"),
                ruleWord(limbs.label()), limbs == ClientConfig.LimbStyle.SMOOTH, mouseX, mouseY);
        if (inside(mouseX, mouseY, x, rowY, width, RULE_ROW_H)) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.tip.limbs", "How villagers' arms and legs look.\nSmooth: they bend in a curve.\nBlocky: straight boxes, like\nclassic Minecraft. Only you see it."))));
        }
    }

    boolean mouseClicked(double mouseX, double mouseY) {
        int x = left() + CONTENT_X + 6;
        int y = top();
        if (inside(mouseX, mouseY, x, y + 61, sectionButtonW(), SUBNAV_H)) {
            // Back to Server: ask at once instead of showing a value up to 2 s old.
            if (section != Group.SERVER) {
                section = Group.SERVER;
                askTicks = 0;
                server.ask();
            }
            return true;
        }
        if (inside(mouseX, mouseY, secondSectionX(x), y + 61, sectionButtonW(), SUBNAV_H)) {
            section = Group.CLIENT;
            return true;
        }
        if (section == Group.CLIENT) {
            int width = innerW();
            // The row's button, where drawRuleRow puts it (as on Village rules, where this row was before).
            if (inside(mouseX, mouseY, x + width - RULE_TOGGLE_W - 6, y + PAGE_Y + CLIENT_ROWS_Y + 5, RULE_TOGGLE_W, 18)) {
                ClientConfig.setLimbStyle(ClientConfig.limbStyle().next());
                return true;
            }
            return false;
        }
        return server.mouseClicked(mouseX, mouseY, x, y + PAGE_Y, innerW());
    }

    /** Every client tick while this tab shows: the Server page asks again every 2 s (a command or the safety cut-out may change a value). */
    void tick() {
        if (section != Group.SERVER) return;
        if (++askTicks >= 40) {
            askTicks = 0;
            server.ask();
        }
    }
}
