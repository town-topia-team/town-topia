package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Items;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * The Village Info tab: the Buildings and Villagers cards on top, and under them the open one's page
 * ({@link BuildingsPage} or {@link VillagersPage}). The two pages link to each other: a resident's line
 * opens that villager, a villager's home opens that house.
 */
final class InfoTab extends MayorPart {

    enum Section { BUILDINGS, VILLAGERS }

    private Section section = Section.BUILDINGS;
    private final BuildingsPage buildings;
    private final VillagersPage villagers;

    InfoTab(MayorStatsScreen screen) {
        super(screen);
        buildings = new BuildingsPage(screen, this);
        villagers = new VillagersPage(screen, this);
    }

    BuildingsPage buildings() {
        return buildings;
    }

    VillagersPage villagers() {
        return villagers;
    }

    MayorStatsScreen.Page page() {
        return section == Section.BUILDINGS
                ? buildings.selected() >= 0 ? MayorStatsScreen.Page.BUILDING_DETAIL : MayorStatsScreen.Page.BUILDINGS
                : villagers.selected() >= 0 ? MayorStatsScreen.Page.VILLAGER_DETAIL : MayorStatsScreen.Page.VILLAGERS;
    }

    boolean buildingListShown() {
        return section == Section.BUILDINGS && buildings.selected() < 0;
    }

    boolean buildingDetailShown() {
        return section == Section.BUILDINGS && buildings.selected() >= 0;
    }

    boolean villagerListShown() {
        return section == Section.VILLAGERS && villagers.selected() < 0;
    }

    /** Opens one building's page (from the house screen's Back, or a villager's home). */
    void showBuilding(int index) {
        section = Section.BUILDINGS;
        buildings.select(index);
    }

    /** Opens one villager's card (from a resident's line on a house's page). */
    void showVillager(int index) {
        section = Section.VILLAGERS;
        villagers.select(index);
    }

    /**
     * The Buildings and Villagers cards: each as wide as two category buttons
     * and the gap between them, so their edges and middle gap line up with
     * the four buttons below.
     */
    private int infoCardW() {
        return buildings.categoryButtonW(0) * 2 + BuildingsPage.BUILDING_GAP;
    }

    /** From the Buildings card's left edge to the Villagers card's. */
    private int infoSecondCardOffset() {
        return infoCardW() + BuildingsPage.BUILDING_GAP;
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left() + CONTENT_X;
        int y = top();
        drawSummaryCard(graphics, x + 6, y + INFO_SELECTOR_Y, infoCardW(), Items.CHEST,
                Tr.s("screen.dynamicvillages.mayor.buildings", "Buildings"), Integer.toString(menu.snapshot().buildingCount()),
                section == Section.BUILDINGS, mouseX, mouseY);
        drawSummaryCard(graphics, x + 6 + infoSecondCardOffset(), y + INFO_SELECTOR_Y, infoCardW(),
                Items.EMERALD, Tr.s("screen.dynamicvillages.mayor.villagers", "Villagers"), Integer.toString(menu.snapshot().villagerCount()),
                section == Section.VILLAGERS, mouseX, mouseY);

        if (section == Section.BUILDINGS) buildings.render(graphics, mouseX, mouseY);
        else villagers.render(graphics, mouseX, mouseY);
    }

    /** Hover boxes drawn after the window (the screen's render), outside any clipping. */
    void renderTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (villagerListShown()) villagers.renderBadgeTooltip(graphics, mouseX, mouseY);
        if (buildingListShown()) buildings.renderProblemTooltip(graphics, mouseX, mouseY);
        if (buildingDetailShown()) buildings.renderHeaderStatTooltip(graphics, mouseX, mouseY);
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        int contentX = left() + CONTENT_X + 6;
        int y = top();
        if (inside(mouseX, mouseY, contentX, y + INFO_SELECTOR_Y, infoCardW(), INFO_SELECTOR_H)) {
            section = Section.BUILDINGS;
            buildings.back();
            return true;
        }
        if (inside(mouseX, mouseY, contentX + infoSecondCardOffset(),
                y + INFO_SELECTOR_Y, infoCardW(), INFO_SELECTOR_H)) {
            section = Section.VILLAGERS;
            return true;
        }
        if (section == Section.BUILDINGS) return button == 0 && buildings.mouseClicked(mouseX, mouseY);
        return villagers.mouseClicked(mouseX, mouseY, button);
    }
}
