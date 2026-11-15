package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import com.alex.test.network.MayorTradePayloads;
import com.alex.test.network.RaidPayloads;
import com.alex.test.village.DefenderTeamRules;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * The Settings tab: Village rules | Professions on top. Village rules has a page of its own, Raids, that
 * shows instead of the rules while open. It is a flag here, so the screen's page stays RULES and nothing
 * resets the scroll by itself: it opens at the top and gives back {@link #rulesScroll}.
 * (Performance was such a page too; since 10-22 it is its own tab, {@link PerformanceTab}.)
 */
final class SettingsTab extends MayorPart {

    enum Section { VILLAGE, PROFESSIONS }

    private Section section = Section.VILLAGE;
    /** Village rules > Raids row clicked: the Raids page (history, fame) shows instead of the rules. */
    private boolean raidsOpen;
    /**
     * How far the Village rules page was scrolled when Raids or Professions opened: Raids opens at the top
     * (its back link would be hidden under the tabs), and every way back to the rules lands where you were.
     */
    private int rulesScroll;

    private final VillageRulesPage rules;
    private final RaidsPage raids;
    private final ProfessionSettingsPage professions;

    SettingsTab(MayorStatsScreen screen) {
        super(screen);
        rules = new VillageRulesPage(screen, this);
        raids = new RaidsPage(screen, this);
        professions = new ProfessionSettingsPage(screen, this);
    }

    ProfessionSettingsPage professions() {
        return professions;
    }

    Section section() {
        return section;
    }

    MayorStatsScreen.Page page() {
        return section == Section.VILLAGE ? MayorStatsScreen.Page.RULES
                : professions.subPageOpen() ? MayorStatsScreen.Page.MINER : MayorStatsScreen.Page.PROFESSIONS;
    }

    /** The bottom of what Village rules shows now (the rules or Raids), for the screen's page scroll. */
    int pageBottom() {
        if (raidsOpen) return raids.contentBottom();
        return VillageRulesPage.RULES_BOTTOM;
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + 6;
        int y = top();
        drawSegment(graphics, x, y + 61, sectionButtonW(),
                section == Section.VILLAGE, Tr.s("screen.dynamicvillages.mayor.settings.village_rules", "Village rules"), mouseX, mouseY);
        drawSegment(graphics, secondSectionX(x), y + 61, sectionButtonW(),
                section == Section.PROFESSIONS, Tr.s("screen.dynamicvillages.mayor.professions_tab", "Professions"), mouseX, mouseY);

        if (section == Section.PROFESSIONS) {
            professions.render(graphics, mouseX, mouseY);
        } else if (raidsOpen) {
            raids.render(graphics);
        } else {
            rules.render(graphics, mouseX, mouseY);
        }
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        int contentX = left() + CONTENT_X + 6;
        int y = top();
        if (inside(mouseX, mouseY, contentX, y + 61, sectionButtonW(), SUBNAV_H)) {
            // Back from Raids or Professions to the rules, where you were.
            boolean backToRules = section == Section.PROFESSIONS || raidsOpen;
            section = Section.VILLAGE;
            raidsOpen = false;
            professions.closeSubPages();
            if (backToRules) screen.setPageScroll(rulesScroll);
            return true;
        }
        if (inside(mouseX, mouseY, secondSectionX(contentX), y + 61, sectionButtonW(), SUBNAV_H)) {
            // Leaving the rules themselves: remember where they were (Raids already did).
            if (section == Section.VILLAGE && !raidsOpen) rulesScroll = screen.pageScroll();
            section = Section.PROFESSIONS;
            return true;
        }
        if (section == Section.PROFESSIONS) return professions.mouseClicked(mouseX, mouseY, button);
        // The Raids page: only its "< Village rules" link does anything.
        if (raidsOpen) return raids.mouseClicked(mouseX, mouseY);
        return rules.mouseClicked(mouseX, mouseY, button);
    }

    /** Opened at the top, whatever the rules page was scrolled to. */
    void openRaids() {
        raidsOpen = true;
        rulesScroll = screen.pageScroll();
        screen.setPageScroll(0);
        PacketDistributor.sendToServer(new RaidPayloads.RequestInfo(menu.mayorId()));
    }

    void closeRaids() {
        raidsOpen = false;
        screen.setPageScroll(rulesScroll);
    }

    /** Sends the defender team rules to the server and shows them at once (Village rules and a defender job's page). */
    void sendDefenderTeams(DefenderTeamRules teams) {
        PacketDistributor.sendToServer(new MayorTradePayloads.SetDefenderTeams(menu.mayorId(), teams));
        menu.setDefenderTeamsLocally(teams);
    }
}
