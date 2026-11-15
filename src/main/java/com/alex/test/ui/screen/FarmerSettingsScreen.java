package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.ui.FarmerSettingsMenu;
import com.alex.test.network.FarmerSettingsPayloads;
import com.alex.test.village.villagers.farmer.crops.HarvestTool;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.lumberjack.AxeUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/** Compact Farmer-only settings screen matching the Lumberjack tab layout. */
public final class FarmerSettingsScreen extends AbstractContainerScreen<FarmerSettingsMenu> {

    private static final int WIDTH = 302;
    private static final int HEIGHT = 205;
    private static final int CONTENT_X = 8;
    private static final int TAB_Y = 24;
    private static final int TAB_W = 92;
    private static final int TAB_H = 23;
    private static final int TAB_GAP = 5;
    private static final int LIST_TOP = 57;
    private static final int LIST_BOTTOM = 195;
    private static final int ROW_HEIGHT = 23;
    private static final int SCROLLBAR_BOTTOM_PADDING = 3;
    private static final int BODY = 0xFF242424;
    private static final int INSET = 0xFF111111;
    private static final int BORDER = 0xFF121212;
    private static final int TAB_BORDER = 0xFF3B3B3B;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int GREEN = 0xFF30D158;
    private static final int RED = 0xFFFF453A;
    private static final int ROW = 0xFF292929;
    private static final int ROW_HOVER = 0xFF383838;
    private static final int TAB_SELECTED = 0xFF3B4E3E;
    private static final int ACCENT = 0xFF6BAF72;

    /** Behaviour tab, top to bottom; the numbers point into {@link FarmerSettingsMenu#behavior()}. */
    private static final BehaviourLayout BEHAVIOUR = BehaviourLayout.of(
            BehaviourLayout.works(),
            BehaviourLayout.setting(0),   // Planting
            BehaviourLayout.setting(1),   // Harvesting
            BehaviourLayout.setting(2),   // Tilling
            BehaviourLayout.setting(3),   // Fertilizing
            BehaviourLayout.setting(4),   // Composting
            BehaviourLayout.heading("Additional Behaviour"),
            BehaviourLayout.setting(5));  // Wooden Tool Crafting

    private int activeTab;
    private int plantScroll;
    private int equipmentScroll;
    private int behaviorScroll;
    private boolean draggingScrollbar;

    public FarmerSettingsScreen(FarmerSettingsMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        titleLabelX = 10;
        titleLabelY = 9;
        inventoryLabelY = -1000;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        panel(graphics, leftPos, topPos, WIDTH, HEIGHT);
        tab(graphics, leftPos + CONTENT_X, topPos + TAB_Y, TAB_W, Tr.s("screen.dynamicvillages.farmer_settings.tab.plants", "Plants"),
                Items.WHEAT_SEEDS, activeTab == 0, mouseX, mouseY);
        tab(graphics, leftPos + CONTENT_X + TAB_W + TAB_GAP, topPos + TAB_Y, TAB_W,
                Tr.s("screen.dynamicvillages.settings.tab.equipment", "Equipment"), Items.IRON_HOE, activeTab == 1, mouseX, mouseY);
        tab(graphics, leftPos + CONTENT_X + (TAB_W + TAB_GAP) * 2, topPos + TAB_Y,
                TAB_W, Tr.s("screen.dynamicvillages.settings.tab.behaviour", "Behaviour"), Items.COMPASS, activeTab == 2, mouseX, mouseY);

        graphics.fill(leftPos + CONTENT_X, topPos + LIST_TOP - 2,
                leftPos + WIDTH - 8, topPos + LIST_BOTTOM, INSET);
        List<FarmerSettingsMenu.Setting> rows = currentRows();
        int scroll = currentScroll();
        int rowX = leftPos + 10;
        int rowWidth = rowWidth();
        for (int visible = 0; visible < visibleRows(); visible++) {
            int index = scroll + visible;
            if (index >= rowCount()) break;
            int rowY = topPos + LIST_TOP + visible * ROW_HEIGHT;
            BehaviourLayout.Entry entry = activeTab == 2 ? BEHAVIOUR.at(index) : null;
            if (entry != null && entry.kind() == BehaviourLayout.Kind.WORKS) {
                row(graphics, rowX, rowY, rowWidth, entry.shown(), menu.works(), mouseX, mouseY);
            } else if (entry != null && entry.kind() == BehaviourLayout.Kind.HEADING) {
                SettingsHeading.draw(graphics, font, rowX, rowY, rowWidth, entry.shown());
            } else {
                int rowIndex = activeTab == 2 ? entry.setting() : index;
                FarmerSettingsMenu.Setting setting = rows.get(rowIndex);
                if (activeTab == 0) {
                    plantRow(graphics, rowX, rowY, rowWidth, SettingNames.item(setting.id(), setting.name()), setting.mode(),
                            plantInfo(rowIndex), mouseX, mouseY);
                } else {
                    row(graphics, rowX, rowY, rowWidth, equipmentName(setting), setting.enabled(),
                            mouseX, mouseY);
                }
            }
        }
        drawScrollbar(graphics, scroll);
    }

    private void tab(GuiGraphics graphics, int x, int y, int width, String label, Item icon,
                     boolean selected, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + TAB_H;
        int color = selected ? TAB_SELECTED : hovered ? ROW_HOVER : ROW;
        graphics.fill(x, y, x + width, y + TAB_H, TAB_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + TAB_H, color);
        if (selected) graphics.fill(x + 1, y + TAB_H - 2, x + width - 1, y + TAB_H, ACCENT);
        graphics.renderFakeItem(new ItemStack(icon), x + 6, y + 3);
        graphics.drawString(font, label, x + 27, y + 8, TEXT, false);
    }

    private void row(GuiGraphics graphics, int x, int y, int width, String label,
                     boolean enabled, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 20;
        graphics.fill(x, y, x + width, y + 20, hovered ? 0xFF303030 : 0xFF282828);
        graphics.drawString(font, fit(label, width - 78), x + 7, y + 6, TEXT, false);
        int switchX = x + width - 43;
        graphics.fill(switchX, y + 4, switchX + 34, y + 16, enabled ? GREEN : 0xFF71312E);
        int knobX = enabled ? switchX + 22 : switchX + 2;
        graphics.fill(knobX, y + 5, knobX + 10, y + 15, 0xFFFFFFFF);
        graphics.drawString(font, enabled ? SettingNames.on() : SettingNames.off(), switchX - 24, y + 6,
                enabled ? GREEN : RED, false);
    }

    private void plantRow(GuiGraphics graphics, int x, int y, int width, String label,
                          int mode, FarmerSettingsMenu.PlantInfo info, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 20;
        graphics.fill(x, y, x + width, y + 20, hovered ? 0xFF303030 : 0xFF282828);
        graphics.drawString(font, fit(label, width - 128), x + 7, y + 6, TEXT, false);
        Item toolIcon = toolIcon(info.harvestTool());
        // The harvest tool, always on show beside the mode button.
        if (toolIcon != null) graphics.renderFakeItem(new ItemStack(toolIcon), x + width - 116, y + 2);

        String action = switch (mode) {
            case FarmerSettings.PLANT_REPLANT -> Tr.s("screen.dynamicvillages.settings.replant", "Replant");
            case FarmerSettings.PLANT_CUT_ONLY -> Tr.s("screen.dynamicvillages.farmer_settings.harvest_only", "Harvest only");
            default -> Tr.s("screen.dynamicvillages.settings.leave", "Leave");
        };
        int accentColor = switch (mode) {
            case FarmerSettings.PLANT_REPLANT -> ACCENT;
            case FarmerSettings.PLANT_CUT_ONLY -> 0xFFE6D49A;
            default -> RED;
        };
        int actionX = x + width - 96;
        graphics.fill(actionX, y + 1, actionX + 88, y + 19, 0xFF454545);
        graphics.fill(actionX + 1, y + 2, actionX + 87, y + 18,
                hovered ? ROW_HOVER : ROW);
        graphics.fill(actionX + 1, y + 16, actionX + 87, y + 18, accentColor);
        graphics.drawString(font, action,
                actionX + (88 - font.width(action)) / 2, y + 6,
                mode == FarmerSettings.PLANT_LEAVE ? 0xFFCCCCCC : TEXT, false);
    }

    private void drawScrollbar(GuiGraphics graphics, int scroll) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return;
        int x = leftPos + WIDTH - 14;
        int height = LIST_BOTTOM - LIST_TOP - SCROLLBAR_BOTTOM_PADDING;
        graphics.fill(x, topPos + LIST_TOP, x + 4, topPos + LIST_TOP + height, 0xFF333333);
        int thumbHeight = Math.max(18, height * visibleRows() / rowCount());
        int thumbY = topPos + LIST_TOP + (height - thumbHeight) * scroll / maxScroll;
        graphics.fill(x, thumbY, x + 4, thumbY + thumbHeight, 0xFF888888);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && maxScroll() > 0 && insideScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            updateScrollFromMouse(mouseY);
            return true;
        }
        if (button == 0 && mouseY >= topPos + TAB_Y && mouseY < topPos + TAB_Y + TAB_H) {
            for (int tab = 0; tab < 3; tab++) {
                int x = leftPos + CONTENT_X + (TAB_W + TAB_GAP) * tab;
                if (mouseX >= x && mouseX < x + TAB_W) {
                    activeTab = tab;
                    return true;
                }
            }
        }
        int rowX = leftPos + 10;
        if (button == 0 && mouseX >= rowX && mouseX < rowX + rowWidth()
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM) {
            int index = currentScroll() + ((int) mouseY - topPos - LIST_TOP) / ROW_HEIGHT;
            if (index >= rowCount()) return true;
            int rowIndex = index;
            if (activeTab == 2) {
                BehaviourLayout.Entry entry = BEHAVIOUR.at(index);
                if (entry.kind() == BehaviourLayout.Kind.HEADING) return true;
                if (entry.kind() == BehaviourLayout.Kind.WORKS) {
                    boolean enabled = !menu.works();
                    menu.setWorksClient(enabled);
                    send(FarmerSettings.WORKS_SETTING_ID, enabled ? 1 : 0);
                    return true;
                }
                rowIndex = entry.setting();
            }
            List<FarmerSettingsMenu.Setting> rows = currentRows();
            if (rowIndex >= 0 && rowIndex < rows.size()) {
                FarmerSettingsMenu.Setting setting = rows.get(rowIndex);
                int mode = activeTab != 0 ? (setting.enabled() ? 0 : 1)
                        // Glow berries are never planted: Leave and Harvest only.
                        : plantInfo(rowIndex).harvestOnly()
                                ? (setting.mode() == FarmerSettings.PLANT_LEAVE
                                        ? FarmerSettings.PLANT_CUT_ONLY : FarmerSettings.PLANT_LEAVE)
                                : (setting.mode() + 1) % 3;
                menu.setClient(setting.id(), mode);
                send(setting.id(), mode);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void send(net.minecraft.resources.ResourceLocation id, int mode) {
        PacketDistributor.sendToServer(new FarmerSettingsPayloads.Update(
                menu.villagerId(), id, mode));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (button == 0 && draggingScrollbar) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= leftPos + CONTENT_X && mouseX < leftPos + WIDTH - 8
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM) {
            setCurrentScroll(Math.max(0, Math.min(maxScroll(),
                    currentScroll() + (scrollY < 0 ? 1 : -1))));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        List<Component> tooltip = hoveredTooltip(mouseX, mouseY);
        SettingsTooltip.render(graphics, font, tooltip, mouseX, mouseY);
    }

    /** The hover text of the row under the mouse, on whichever tab is open. */
    private List<Component> hoveredTooltip(int mouseX, int mouseY) {
        int rowX = leftPos + 10;
        if (mouseX < rowX || mouseX >= rowX + rowWidth()
                || mouseY < topPos + LIST_TOP || mouseY >= topPos + LIST_BOTTOM) return List.of();
        int index = currentScroll() + (mouseY - topPos - LIST_TOP) / ROW_HEIGHT;
        int rowIndex = index;
        if (activeTab == 2) {
            BehaviourLayout.Entry entry = BEHAVIOUR.at(index);
            if (entry == null || entry.kind() == BehaviourLayout.Kind.HEADING) return List.of();
            if (entry.kind() == BehaviourLayout.Kind.WORKS) {
                return Tr.lines("screen.dynamicvillages.farmer_settings.working.desc",
                        "ON: tends the fields for the village.\nOFF: stops all farm work, so no\nnew crops come in.");
            }
            rowIndex = entry.setting();
        }
        List<FarmerSettingsMenu.Setting> rows = currentRows();
        if (rowIndex < 0 || rowIndex >= rows.size()) return List.of();
        return switch (activeTab) {
            case 0 -> plantTooltip(plantInfo(rowIndex));
            case 1 -> equipmentTooltip(rows.get(rowIndex).id());
            default -> behaviorTooltip(rows.get(rowIndex).id());
        };
    }

    /** Which tool the crop's harvest takes, and whether this farmer has one. */
    private static List<Component> plantTooltip(FarmerSettingsMenu.PlantInfo info) {
        List<Component> lines = new java.util.ArrayList<>();
        switch (info.harvestTool()) {
            case SHEARS -> lines.add(Tr.c("screen.dynamicvillages.farmer_settings.needs_shears", "Needs shears to harvest"));
            case AXE -> lines.add(Tr.c("screen.dynamicvillages.farmer_settings.needs_axe", "Needs an axe to harvest"));
            case NONE -> {
            }
        }
        if (info.harvestTool() != HarvestTool.NONE && !info.hasTool()) {
            lines.add(Tr.c("screen.dynamicvillages.farmer_settings.has_no_tool", "Has none, fetch or forge one").withStyle(ChatFormatting.RED));
        }
        if (info.harvestOnly()) lines.add(Tr.c("screen.dynamicvillages.farmer_settings.never_planted", "Only harvested, never planted"));
        return lines;
    }

    private static List<Component> equipmentTooltip(net.minecraft.resources.ResourceLocation id) {
        if (FarmerSettings.SHEARS_SETTING_ID.equals(id)) {
            return Tr.lines("setting.dynamicvillages.farmer_shears.desc",
                    "Needed to harvest sweet berries,\ncocoa and glow berries");
        }
        FarmerSettings.AxeTier axe = FarmerSettings.AxeTier.bySettingId(id);
        if (axe != null && AxeUtils.canFellBigTrees(axe.axeItem)) {
            return Tr.lines("screen.dynamicvillages.farmer_settings.axe_big_trees.desc",
                    "Only used to harvest melons\nand pumpkins. Left in storage\nwhile a lumberjack needs it\nfor big trees.");
        }
        if (axe != null) {
            return Tr.lines("screen.dynamicvillages.farmer_settings.axe.desc", "Only used to harvest melons and pumpkins");
        }
        return List.of();
    }

    /** Explains the Behaviour switches whose name alone does not say enough. */
    private static List<Component> behaviorTooltip(net.minecraft.resources.ResourceLocation id) {
        if (FarmerSettings.TURN_OFF_PLANTING_SETTING_ID.equals(id)) {
            return Tr.lines("setting.dynamicvillages.farmer_turn_off_planting.desc",
                    "ON: plants seeds after each harvest,\nso the fields keep growing.\n"
                            + "OFF: plants nothing, even crops set\nto Replant on the Plants tab.");
        }
        if (FarmerSettings.HARVESTING_SETTING_ID.equals(id)) {
            return Tr.lines("setting.dynamicvillages.farmer_harvesting.desc",
                    "ON: picks ripe crops for the village.\nOFF: leaves every crop standing, even\n"
                            + "crops set to Replant or Harvest only\non the Plants tab.");
        }
        if (FarmerSettings.TILLING_SETTING_ID.equals(id)) {
            return Tr.lines("setting.dynamicvillages.farmer_tilling.desc",
                    "ON: turns soil into farmland with a\nhoe. With Planting off, it tills for\n"
                            + "the other farmers to plant.\nOFF: plants only on soil that is\n"
                            + "already farmland, leaves dirt alone.");
        }
        if (FarmerSettings.HOE_CRAFT_SETTING_ID.equals(id)) {
            return Tr.lines("setting.dynamicvillages.farmer_hoe_allow_craft.desc",
                    "No hoe or axe in storage? Makes a\nwooden one at a storage room crafting\n"
                            + "table. The wooden tool must also be\nallowed on the Equipment tab.");
        }
        if (FarmerSettings.USE_BONE_MEAL_SETTING_ID.equals(id)) {
            return Tr.lines("setting.dynamicvillages.farmer_use_bone_meal.desc",
                    "ON: uses bone meal from storage to make\ncrops grow faster, more when food is low.\n"
                            + "OFF: crops grow at normal speed.\nWith Planting, Harvesting and Tilling\n"
                            + "all off, it only spreads bone meal.");
        }
        if (FarmerSettings.COMPOST_SETTING_ID.equals(id)) {
            return Tr.lines("setting.dynamicvillages.farmer_compost.desc",
                    "ON: makes bone meal from spare seeds,\nsaplings and plant scraps in storage,\n"
                            + "at a composter by the fields or in\na storage room. Never uses food.");
        }
        return List.of();
    }

    /** Equipment and Behaviour rows: tools by their own names, our switches by key. */
    private static String equipmentName(FarmerSettingsMenu.Setting setting) {
        FarmerSettings.HoeTier hoe = FarmerSettings.HoeTier.bySettingId(setting.id());
        if (hoe != null) return SettingNames.tool(hoe.hoeItem, setting.id(), setting.name());
        FarmerSettings.AxeTier axe = FarmerSettings.AxeTier.bySettingId(setting.id());
        if (axe != null) return SettingNames.tool(axe.axeItem, setting.id(), setting.name());
        return SettingNames.own(setting.id(), setting.name());
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, TEXT, false);
    }

    private FarmerSettingsMenu.PlantInfo plantInfo(int rowIndex) {
        List<FarmerSettingsMenu.PlantInfo> info = menu.plantInfo();
        return rowIndex >= 0 && rowIndex < info.size()
                ? info.get(rowIndex) : new FarmerSettingsMenu.PlantInfo(0, true, false);
    }

    @javax.annotation.Nullable
    private static Item toolIcon(HarvestTool tool) {
        return switch (tool) {
            case SHEARS -> Items.SHEARS;
            case AXE -> Items.IRON_AXE;
            case NONE -> null;
        };
    }

    private List<FarmerSettingsMenu.Setting> currentRows() {
        return switch (activeTab) {
            case 0 -> menu.plants();
            case 1 -> menu.equipment();
            default -> menu.behavior();
        };
    }

    private int rowCount() {
        return activeTab == 2 ? BEHAVIOUR.size() : currentRows().size();
    }

    private int visibleRows() { return (LIST_BOTTOM - LIST_TOP) / ROW_HEIGHT; }
    private int maxScroll() { return Math.max(0, rowCount() - visibleRows()); }
    private int rowWidth() { return maxScroll() > 0 ? WIDTH - 26 : WIDTH - 20; }

    private int currentScroll() {
        return activeTab == 0 ? plantScroll : activeTab == 1 ? equipmentScroll : behaviorScroll;
    }

    private void setCurrentScroll(int value) {
        if (activeTab == 0) plantScroll = value;
        else if (activeTab == 1) equipmentScroll = value;
        else behaviorScroll = value;
    }

    private boolean insideScrollbar(double mouseX, double mouseY) {
        return mouseX >= leftPos + WIDTH - 16 && mouseX < leftPos + WIDTH - 8
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM;
    }

    private void updateScrollFromMouse(double mouseY) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return;
        int trackY = topPos + LIST_TOP;
        int trackHeight = LIST_BOTTOM - LIST_TOP - SCROLLBAR_BOTTOM_PADDING;
        int thumbHeight = Math.max(18, trackHeight * visibleRows() / rowCount());
        double t = (mouseY - trackY - thumbHeight / 2.0)
                / Math.max(1, trackHeight - thumbHeight);
        setCurrentScroll(Math.max(0, Math.min(maxScroll, (int) Math.round(t * maxScroll))));
    }

    private String fit(String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
    }

    private void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, BORDER);
        graphics.fill(x, y, x + width, y + height, BODY);
    }
}
