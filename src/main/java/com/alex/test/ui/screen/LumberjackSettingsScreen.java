package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.ui.LumberjackSettingsMenu;
import com.alex.test.network.LumberjackSettingsPayloads;
import com.alex.test.village.villagers.lumberjack.AxeUtils;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Compact Lumberjack-only settings screen using the Guard settings tab layout. */
public final class LumberjackSettingsScreen
        extends AbstractContainerScreen<LumberjackSettingsMenu> {

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

    /** Behaviour tab, top to bottom; the numbers point into {@link LumberjackSettingsMenu#behaviorSettings()}. */
    private static final BehaviourLayout BEHAVIOUR = BehaviourLayout.of(
            BehaviourLayout.works(),
            BehaviourLayout.setting(0),   // Planting
            BehaviourLayout.heading("Additional Behaviour"),
            BehaviourLayout.setting(1));  // Wooden Tool Crafting

    private int activeTab;
    private int treeScroll;
    private int equipmentScroll;
    private boolean draggingScrollbar;

    public LumberjackSettingsScreen(
            LumberjackSettingsMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        titleLabelX = 10;
        titleLabelY = 9;
        inventoryLabelY = -1000;
    }

    @Override
    protected void renderBg(
            GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        panel(graphics, leftPos, topPos, WIDTH, HEIGHT);
        tab(graphics, leftPos + CONTENT_X, topPos + TAB_Y, TAB_W, Tr.s("screen.dynamicvillages.lumberjack_settings.tab.trees", "Trees"), Items.OAK_SAPLING,
                activeTab == 0, mouseX, mouseY);
        tab(graphics, leftPos + CONTENT_X + TAB_W + TAB_GAP, topPos + TAB_Y, TAB_W, Tr.s("screen.dynamicvillages.settings.tab.equipment", "Equipment"),
                Items.IRON_AXE, activeTab == 1, mouseX, mouseY);
        tab(graphics, leftPos + CONTENT_X + (TAB_W + TAB_GAP) * 2, topPos + TAB_Y, TAB_W,
                Tr.s("screen.dynamicvillages.settings.tab.behaviour", "Behaviour"), Items.COMPASS, activeTab == 2, mouseX, mouseY);

        graphics.fill(
                leftPos + CONTENT_X, topPos + LIST_TOP - 2,
                leftPos + WIDTH - 8, topPos + LIST_BOTTOM,
                INSET);

        List<LumberjackSettingsMenu.TreeSetting> rows = currentRows();
        int scroll = currentScroll();
        int visibleRows = visibleRows();
        int rowX = leftPos + 10;
        int rowWidth = rowWidth();
        for (int visible = 0; visible < visibleRows; visible++) {
            int index = scroll + visible;
            if (index >= rowCount()) break;
            int rowY = topPos + LIST_TOP + visible * ROW_HEIGHT;
            if (activeTab == 2) {
                BehaviourLayout.Entry entry = BEHAVIOUR.at(index);
                if (entry.kind() == BehaviourLayout.Kind.WORKS) {
                    row(graphics, rowX, rowY, rowWidth,
                            entry.shown(), menu.works(), mouseX, mouseY);
                } else if (entry.kind() == BehaviourLayout.Kind.HEADING) {
                    SettingsHeading.draw(graphics, font, rowX, rowY, rowWidth, entry.shown());
                } else {
                    LumberjackSettingsMenu.TreeSetting setting =
                            menu.behaviorSettings().get(entry.setting());
                    row(graphics, rowX, rowY, rowWidth,
                            SettingNames.own(setting.id(), setting.name()), setting.enabled(), mouseX, mouseY);
                }
            } else {
                LumberjackSettingsMenu.TreeSetting setting = rows.get(index);
                if (activeTab == 0) {
                    treeRow(graphics, rowX, rowY, rowWidth,
                            setting.name(), setting.mode(), mouseX, mouseY);
                } else {
                    row(graphics, rowX, rowY, rowWidth,
                            equipmentName(setting), setting.enabled(), mouseX, mouseY);
                }
            }
        }

        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            int x = leftPos + WIDTH - 14;
            int height = LIST_BOTTOM - LIST_TOP - SCROLLBAR_BOTTOM_PADDING;
            graphics.fill(x, topPos + LIST_TOP, x + 4, topPos + LIST_TOP + height, 0xFF333333);
            int thumbHeight = Math.max(18, height * visibleRows / rowCount());
            int thumbY = topPos + LIST_TOP
                    + (height - thumbHeight) * scroll / maxScroll;
            graphics.fill(x, thumbY, x + 4, thumbY + thumbHeight, 0xFF888888);
        }
    }

    private void tab(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            String label,
            Item icon,
            boolean selected,
            int mouseX,
            int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + TAB_H;
        int color = selected ? TAB_SELECTED : hovered ? ROW_HOVER : ROW;
        graphics.fill(x, y, x + width, y + TAB_H, TAB_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + TAB_H, color);
        if (selected) graphics.fill(x + 1, y + TAB_H - 2, x + width - 1, y + TAB_H, ACCENT);
        graphics.renderFakeItem(new ItemStack(icon), x + 6, y + 3);
        graphics.drawString(font, label, x + 27, y + 8, TEXT, false);
    }

    private void row(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            String label,
            boolean enabled,
            int mouseX,
            int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + 20;
        graphics.fill(x, y, x + width, y + 20, hovered ? 0xFF303030 : 0xFF282828);
        graphics.drawString(font, fit(label, width - 78), x + 7, y + 6, TEXT, false);

        int switchX = x + width - 43;
        int switchColor = enabled ? GREEN : 0xFF71312E;
        graphics.fill(switchX, y + 4, switchX + 34, y + 16, switchColor);
        int knobX = enabled ? switchX + 22 : switchX + 2;
        graphics.fill(knobX, y + 5, knobX + 10, y + 15, 0xFFFFFFFF);
        graphics.drawString(
                font, enabled ? SettingNames.on() : SettingNames.off(), switchX - 24, y + 6,
                enabled ? GREEN : RED, false);
    }

    private void treeRow(
            GuiGraphics graphics, int x, int y, int width, String label, int mode,
            int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + 20;
        graphics.fill(x, y, x + width, y + 20, hovered ? 0xFF303030 : 0xFF282828);
        graphics.drawString(font, fit(label, width - 108), x + 7, y + 6, TEXT, false);

        String action = switch (mode) {
            case LumberjackSettings.TREE_CUT_AND_PLANT -> Tr.s("screen.dynamicvillages.settings.replant", "Replant");
            case LumberjackSettings.TREE_CUT_ONLY -> Tr.s("screen.dynamicvillages.lumberjack_settings.cut_only", "Cut only");
            default -> Tr.s("screen.dynamicvillages.settings.leave", "Leave");
        };
        int accentColor = switch (mode) {
            case LumberjackSettings.TREE_CUT_AND_PLANT -> ACCENT;
            case LumberjackSettings.TREE_CUT_ONLY -> 0xFFE6D49A;
            default -> RED;
        };
        int actionX = x + width - 96;
        graphics.fill(actionX, y + 1, actionX + 88, y + 19, 0xFF454545);
        graphics.fill(actionX + 1, y + 2, actionX + 87, y + 18,
                hovered ? ROW_HOVER : ROW);
        graphics.fill(actionX + 1, y + 16, actionX + 87, y + 18, accentColor);
        graphics.drawString(font, action,
                actionX + (88 - font.width(action)) / 2, y + 6,
                mode == LumberjackSettings.TREE_SKIP ? 0xFFCCCCCC : TEXT, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && maxScroll() > 0 && insideScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            updateScrollFromMouse(mouseY);
            return true;
        }

        if (button == 0 && mouseY >= topPos + TAB_Y && mouseY < topPos + TAB_Y + TAB_H) {
            if (mouseX >= leftPos + CONTENT_X && mouseX < leftPos + CONTENT_X + TAB_W) {
                activeTab = 0;
                return true;
            }
            if (mouseX >= leftPos + CONTENT_X + TAB_W + TAB_GAP
                    && mouseX < leftPos + CONTENT_X + TAB_W * 2 + TAB_GAP) {
                activeTab = 1;
                return true;
            }
            if (mouseX >= leftPos + CONTENT_X + (TAB_W + TAB_GAP) * 2
                    && mouseX < leftPos + CONTENT_X + TAB_W * 3 + TAB_GAP * 2) {
                activeTab = 2;
                return true;
            }
        }

        int rowX = leftPos + 10;
        int rowWidth = rowWidth();
        if (button == 0 && mouseX >= rowX && mouseX < rowX + rowWidth
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM) {
            int index = currentScroll() + ((int) mouseY - topPos - LIST_TOP) / ROW_HEIGHT;
            if (activeTab == 2) {
                BehaviourLayout.Entry entry = BEHAVIOUR.at(index);
                if (entry == null || entry.kind() == BehaviourLayout.Kind.HEADING) return true;
                if (entry.kind() == BehaviourLayout.Kind.WORKS) {
                    boolean enabled = !menu.works();
                    menu.setWorksClient(enabled);
                    PacketDistributor.sendToServer(
                            LumberjackSettingsPayloads.Update.works(menu.villagerId(), enabled));
                    return true;
                }
                int behaviorIndex = entry.setting();
                if (behaviorIndex >= 0 && behaviorIndex < menu.behaviorSettings().size()) {
                    LumberjackSettingsMenu.TreeSetting setting =
                            menu.behaviorSettings().get(behaviorIndex);
                    boolean enabled = !setting.enabled();
                    menu.setTreeClient(setting.id(), enabled ? 1 : 0);
                    PacketDistributor.sendToServer(
                            LumberjackSettingsPayloads.Update.setting(
                                    menu.villagerId(), setting.id(), enabled));
                    return true;
                }
            }

            List<LumberjackSettingsMenu.TreeSetting> rows = currentRows();
            if (index >= 0 && index < rows.size()) {
                LumberjackSettingsMenu.TreeSetting setting = rows.get(index);
                if (activeTab == 0) {
                    int mode = (setting.mode() + 1) % 3;
                    menu.setTreeClient(setting.id(), mode);
                    PacketDistributor.sendToServer(
                            LumberjackSettingsPayloads.Update.tree(
                                    menu.villagerId(), setting.id(), mode));
                } else {
                    boolean enabled = !setting.enabled();
                    menu.setTreeClient(setting.id(), enabled ? 1 : 0);
                    PacketDistributor.sendToServer(
                            LumberjackSettingsPayloads.Update.setting(
                                    menu.villagerId(), setting.id(), enabled));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(
            double mouseX, double mouseY, int button, double dragX, double dragY) {
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
    public boolean mouseScrolled(
            double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= leftPos + CONTENT_X
                && mouseX < leftPos + WIDTH - 8
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM) {
            setCurrentScroll(Math.max(0,
                    Math.min(maxScroll(), currentScroll() + (scrollY < 0 ? 1 : -1))));
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

    /** The hover text of the row under the mouse. */
    private List<Component> hoveredTooltip(int mouseX, int mouseY) {
        int rowX = leftPos + 10;
        if (mouseX < rowX || mouseX >= rowX + rowWidth()
                || mouseY < topPos + LIST_TOP || mouseY >= topPos + LIST_BOTTOM) return List.of();
        int index = currentScroll() + (mouseY - topPos - LIST_TOP) / ROW_HEIGHT;
        if (activeTab != 2) {
            List<LumberjackSettingsMenu.TreeSetting> rows = currentRows();
            if (index < 0 || index >= rows.size()) return List.of();
            return activeTab == 0 ? treeTooltip(rows.get(index).id()) : equipmentTooltip(rows.get(index).id());
        }
        BehaviourLayout.Entry entry = BEHAVIOUR.at(index);
        if (entry == null || entry.kind() == BehaviourLayout.Kind.HEADING) return List.of();
        if (entry.kind() == BehaviourLayout.Kind.WORKS) {
            return Tr.lines("screen.dynamicvillages.lumberjack_settings.working.desc",
                    "ON: chops trees for the village.\nOFF: stops all chopping, so no\nnew wood comes in.");
        }
        int behaviorIndex = entry.setting();
        if (behaviorIndex < 0 || behaviorIndex >= menu.behaviorSettings().size()) return List.of();
        return behaviorTooltip(menu.behaviorSettings().get(behaviorIndex).id());
    }

    private static List<Component> behaviorTooltip(net.minecraft.resources.ResourceLocation id) {
        if (LumberjackSettings.PLANT_SAPLINGS_SETTING_ID.equals(id)) {
            return Tr.lines("setting.dynamicvillages.plant_saplings.desc",
                    "ON: plants a sapling where it cut\na tree, so the forest grows back.\n"
                            + "OFF: never plants, even trees set\nto Replant on the Trees tab.");
        }
        if (LumberjackSettings.AXE_CRAFT_SETTING_ID.equals(id)) {
            return Tr.lines("setting.dynamicvillages.axe_allow_craft.desc",
                    "No axe in storage? Makes a wooden\none at a storage room crafting table.\n"
                            + "Wooden axe must also be allowed\non the Equipment tab.");
        }
        return List.of();
    }

    /** Trees that can grow big 2x2 trunks: which axe they need, and whether this lumberjack has one. */
    private List<Component> treeTooltip(net.minecraft.resources.ResourceLocation id) {
        if (!TreeStructure.canGrowTwoByTwo(id)) return List.of();
        List<Component> lines = new ArrayList<>(TreeStructure.alwaysTwoByTwo(id)
                ? Tr.lines("screen.dynamicvillages.lumberjack_settings.always_big_tree.desc",
                        "Always grows as a big 2x2 tree.\nNeeds an iron axe or better.")
                : Tr.lines("screen.dynamicvillages.lumberjack_settings.big_tree.desc",
                        "Big 2x2 ones need an iron axe\nor better. Small ones are fine\nwith any axe."));
        Item axe = menu.axe();
        if (axe == Items.AIR) {
            lines.add(Tr.c("screen.dynamicvillages.lumberjack_settings.axe_none",
                    "Its axe: none right now").withStyle(ChatFormatting.RED));
        } else if (AxeUtils.canFellBigTrees(axe)) {
            lines.add(Tr.c("screen.dynamicvillages.lumberjack_settings.axe_strong",
                    "Its axe: %s (strong enough)", new ItemStack(axe).getHoverName())
                    .withStyle(ChatFormatting.GREEN));
        } else {
            lines.add(Tr.c("screen.dynamicvillages.lumberjack_settings.axe_weak",
                    "Its axe: %s (too weak)", new ItemStack(axe).getHoverName())
                    .withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    /** Axe rows: whether that axe can fell big 2x2 trees. */
    private static List<Component> equipmentTooltip(net.minecraft.resources.ResourceLocation id) {
        LumberjackSettings.AxeTier tier = LumberjackSettings.AxeTier.bySettingId(id);
        if (tier == null) return List.of();
        if (AxeUtils.canFellBigTrees(tier.axeItem)) {
            return Tr.lines("screen.dynamicvillages.lumberjack_settings.axe_big_trees.desc",
                    "Cuts every tree, big 2x2\nones like dark oak too.");
        }
        return Tr.lines("screen.dynamicvillages.lumberjack_settings.axe_small_trees.desc",
                "Too weak for big 2x2 trees\nlike dark oak. Small trees\nare fine.");
    }

    /** Equipment rows: axes by their own names. */
    private static String equipmentName(LumberjackSettingsMenu.TreeSetting setting) {
        LumberjackSettings.AxeTier axe = LumberjackSettings.AxeTier.bySettingId(setting.id());
        if (axe != null) return SettingNames.tool(axe.axeItem, setting.id(), setting.name());
        return SettingNames.own(setting.id(), setting.name());
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, TEXT, false);
    }

    private int visibleRows() {
        return (LIST_BOTTOM - LIST_TOP) / ROW_HEIGHT;
    }

    private int maxScroll() {
        return Math.max(0, rowCount() - visibleRows());
    }

    private int rowWidth() {
        return maxScroll() > 0 ? WIDTH - 26 : WIDTH - 20;
    }

    private int rowCount() {
        if (activeTab == 0) return menu.treeSettings().size();
        if (activeTab == 1) return menu.equipmentSettings().size();
        return BEHAVIOUR.size();
    }

    private List<LumberjackSettingsMenu.TreeSetting> currentRows() {
        if (activeTab == 0) return menu.treeSettings();
        if (activeTab == 1) return menu.equipmentSettings();
        return List.of();
    }

    private int currentScroll() {
        if (activeTab == 0) return treeScroll;
        if (activeTab == 1) return equipmentScroll;
        return 0;
    }

    private void setCurrentScroll(int value) {
        if (activeTab == 0) treeScroll = value;
        else if (activeTab == 1) equipmentScroll = value;
    }

    private boolean insideScrollbar(double mouseX, double mouseY) {
        return mouseX >= leftPos + WIDTH - 16 && mouseX < leftPos + WIDTH - 8
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM;
    }

    private void updateScrollFromMouse(double mouseY) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return;
        int rowCount = rowCount();
        int trackY = topPos + LIST_TOP;
        int trackHeight = LIST_BOTTOM - LIST_TOP - SCROLLBAR_BOTTOM_PADDING;
        int thumbHeight = Math.max(18, trackHeight * visibleRows() / rowCount);
        double t = (mouseY - trackY - thumbHeight / 2.0) / Math.max(1, trackHeight - thumbHeight);
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
