package com.alex.test.ui.screen;

import com.alex.test.network.BuildOrderPayloads;
import com.alex.test.ui.BuilderDeskMenu;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.util.List;

import static com.alex.test.ui.screen.StorageUi.ACCENT;
import static com.alex.test.ui.screen.StorageUi.BUTTON_HOVER_BORDER;
import static com.alex.test.ui.screen.StorageUi.OUTER;
import static com.alex.test.ui.screen.StorageUi.PANEL;
import static com.alex.test.ui.screen.StorageUi.PANEL_BORDER;
import static com.alex.test.ui.screen.StorageUi.ROW;
import static com.alex.test.ui.screen.StorageUi.ROW_HOVER;
import static com.alex.test.ui.screen.StorageUi.ROW_SELECTED;
import static com.alex.test.ui.screen.StorageUi.TEXT;
import static com.alex.test.ui.screen.StorageUi.inside;

/**
 * The Builder's desk: the Builder's own screen, opened from its villager screen.
 * Same frame, palette and header tabs as the storage room screen.
 *
 * <p>Build picks a blueprint and orders it ({@link BuilderDeskBuildTab}), Themes lists the theme
 * packs ({@link BuilderDeskThemesTab}), Projects shows the orders and the blocks each still needs
 * ({@link BuilderDeskProjectsTab}, 10-25), Settings shows the Builder's work settings in place
 * ({@link BuilderDeskSettingsTab}; the separate settings window no longer opens for a Builder).
 * This class draws the frame and header and hands each tab its body.
 */
public final class BuilderDeskScreen extends AbstractContainerScreen<BuilderDeskMenu> {
    static final int PAD = 8, HEADER_H = 28;
    private static final int TAB_GAP = 8, TAB_W = 84, MIN_TAB_W = 64;
    /** Gap around the Build tab in its full window view. */
    private static final int FULL_MARGIN = 4;

    private enum Tab {
        BUILD("Build", Items.SCAFFOLDING),
        THEMES("Themes", Items.BOOKSHELF),
        PROJECTS("Projects", Items.WRITABLE_BOOK),
        SETTINGS("Settings", Items.COMPARATOR);

        final String label;
        final Item icon;

        Tab(String label, Item icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private Tab tab = Tab.BUILD;
    private final BuilderDeskBuildTab build;
    private final BuilderDeskSettingsTab settings;
    private final BuilderDeskThemesTab themes;
    private final BuilderDeskProjectsTab projects;

    public BuilderDeskScreen(BuilderDeskMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 600;
        imageHeight = 350;
        titleLabelY = -1000;
        inventoryLabelY = -1000;
        build = new BuilderDeskBuildTab(menu);
        build.onOrdered(() -> {
            build.releaseGpu();
            tab = Tab.PROJECTS;
        });
        settings = new BuilderDeskSettingsTab(menu);
        projects = new BuilderDeskProjectsTab(menu);
        // Show on map: the Build tab takes the theme building, with the village map showing.
        themes = new BuilderDeskThemesTab(menu, (entry, blueprint, materials, guess, prepared) -> {
            build.showTheme(entry, blueprint, materials, guess, prepared);
            tab = Tab.BUILD;
        });
    }

    @Override protected void init() {
        imageWidth = Math.min(600, width - 16);
        imageHeight = Math.min(350, height - 16);
        super.init();
        EditBox search = addRenderableWidget(new EditBox(font, 0, 0, BuilderDeskBuildTab.LIST_W - 12, 16,
                Component.literal("Search blueprints")));
        search.setHint(Component.literal("Search..."));
        search.setMaxLength(64);
        build.attach(font, search);
        // The name a copied building is saved under; the Build tab places and shows it.
        EditBox name = addRenderableWidget(new EditBox(font, 0, 0, 160, 16, Component.literal("Name of the copy")));
        name.setMaxLength(48);
        name.visible = false;
        build.attachName(name);
    }

    @Override protected void containerTick() {
        super.containerTick();
        build.tick();
        themes.tick();
        if (tab == Tab.PROJECTS) projects.tick();
    }

    @Override public void removed() {
        super.removed();
        // Stops file reading and drops the preview's recorded blocks.
        build.close();
        themes.close();
    }

    // =========================================================================
    // Layout
    // =========================================================================

    private int tabsRight() {
        return leftPos + imageWidth - PAD;
    }

    private int tabWidth() {
        int room = tabsRight() - (leftPos + PAD + 20 + 80 + TAB_GAP);
        int count = Tab.values().length;
        return Math.max(MIN_TAB_W, Math.min(TAB_W, (room - (count - 1) * TAB_GAP) / count));
    }

    private int tabX(Tab target) {
        int count = Tab.values().length;
        int start = tabsRight() - count * tabWidth() - (count - 1) * TAB_GAP;
        return start + target.ordinal() * (tabWidth() + TAB_GAP);
    }

    private int bodyLeft() { return leftPos + PAD; }
    private int bodyTop() { return topPos + HEADER_H; }
    private int bodyRight() { return leftPos + imageWidth - PAD; }
    private int bodyBottom() { return topPos + imageHeight - PAD; }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        if (tab == Tab.BUILD && build.fullView()) {
            // The Build tab over the whole window: no frame, header or list. Nothing of the
            // header is drawn first: item icons stay on top of a fill drawn over them.
            graphics.fill(0, 0, width, height, OUTER);
            build.setVisible(true);
            build.render(graphics, FULL_MARGIN, FULL_MARGIN, width - FULL_MARGIN, height - FULL_MARGIN, mouseX, mouseY);
            return;
        }
        int x = leftPos, y = topPos;
        graphics.fill(x - 2, y - 2, x + imageWidth + 2, y + imageHeight + 2, OUTER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        graphics.renderFakeItem(new ItemStack(Items.BRICKS), x + PAD, y + 6);
        int titleWidth = tabX(Tab.BUILD) - TAB_GAP - (x + PAD + 20);
        // Just "Builder's desk": the villager's name and level were clutter.
        graphics.drawString(font, StorageUi.trim(font, "Builder's desk", titleWidth), x + PAD + 20, y + 10,
                TEXT, false);
        for (Tab target : Tab.values()) {
            int tx = tabX(target), width = tabWidth();
            headerButton(graphics, font, tx, y + 4, width, target.icon, target.label, tab == target,
                    inside(mouseX, mouseY, tx, y + 4, width, 20));
        }

        build.setVisible(tab == Tab.BUILD);
        switch (tab) {
            case BUILD -> build.render(graphics, bodyLeft(), bodyTop(), bodyRight(), bodyBottom(), mouseX, mouseY);
            case THEMES -> themes.render(graphics, font, bodyLeft(), bodyTop(), bodyRight(), bodyBottom(), mouseX, mouseY);
            case PROJECTS -> projects.render(graphics, font, bodyLeft(), bodyTop(), bodyRight(), bodyBottom(),
                    mouseX, mouseY);
            case SETTINGS -> settings.render(graphics, font, bodyLeft(), bodyTop(), bodyRight(), bodyBottom(),
                    mouseX, mouseY);
        }
    }

    /** The server's answer to the Blocks list (see {@link BuildOrderClient#orderBlocks}). */
    void orderBlocks(BuildOrderPayloads.OrderBlocksReply reply) {
        projects.answer(reply);
    }

    /** The server's answer to Give or a take back (see {@link BuildOrderClient#notice}). */
    void notice(boolean ok, String message) {
        projects.notice(ok, message);
    }

    /** Every header control looks the same: bordered, icon and label centred, green when selected. */
    static void headerButton(GuiGraphics graphics, Font font, int x, int y, int width, Item icon, String label,
                             boolean selected, boolean hover) {
        graphics.fill(x, y, x + width, y + 20, hover && !selected ? BUTTON_HOVER_BORDER : PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 19, selected ? ROW_SELECTED : hover ? ROW_HOVER : ROW);
        if (selected) graphics.fill(x + 1, y + 17, x + width - 1, y + 19, ACCENT);
        String shown = StorageUi.trim(font, label, width - 24);
        int content = x + (width - 20 - font.width(shown)) / 2;
        graphics.renderFakeItem(new ItemStack(icon), content, y + 2);
        graphics.drawString(font, shown, content + 20, y + 6, TEXT, false);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (tab == Tab.BUILD) {
            List<Component> lines = build.tooltip(mouseX, mouseY);
            if (lines != null) graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        } else if (tab == Tab.THEMES) {
            List<Component> lines = themes.tooltip(mouseX, mouseY);
            if (lines != null) graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        } else if (tab == Tab.PROJECTS) {
            List<Component> lines = projects.tooltip(mouseX, mouseY);
            if (lines != null) graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        } else if (tab == Tab.SETTINGS) {
            String text = settings.tooltip(mouseX, mouseY);
            if (text != null) SettingsTooltip.render(graphics, font, text, mouseX, mouseY);
        }
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Everything is drawn in screen coordinates by renderBg.
    }

    // =========================================================================
    // Input
    // =========================================================================

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Clicking anywhere else takes the focus off the search box.
        if (getFocused() instanceof EditBox box && !box.isMouseOver(mouseX, mouseY)) setFocused(null);
        // The header is hidden in the full view; the view lies where the tabs would be.
        if (button == 0 && !(tab == Tab.BUILD && build.fullView())) {
            for (Tab target : Tab.values()) {
                if (inside(mouseX, mouseY, tabX(target), topPos + 4, tabWidth(), 20)) {
                    // The village model's GPU buffers go when the Build tab is left.
                    if (tab == Tab.BUILD && target != Tab.BUILD) build.releaseGpu();
                    tab = target;
                    return true;
                }
            }
            if (tab == Tab.SETTINGS && settings.mouseClicked(mouseX, mouseY, button)) return true;
            if (tab == Tab.PROJECTS && projects.mouseClicked(mouseX, mouseY, button)) return true;
        }
        if (tab == Tab.BUILD && build.mouseClicked(mouseX, mouseY, button)) return true;
        if (tab == Tab.THEMES && themes.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (tab == Tab.BUILD && build.mouseDragged(button, dragX, dragY)) return true;
        if (tab == Tab.THEMES && themes.mouseDragged(button, dragX, dragY)) return true;
        if (tab == Tab.PROJECTS && projects.mouseDragged(mouseY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (build.mouseReleased(button, mouseX, mouseY)) return true;
        if (themes.mouseReleased(button)) return true;
        if (projects.mouseReleased()) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Files dragged from the computer onto the game window go to the Build tab's drop map.
     * The window reports the drop with the mouse moved to where it was let go (Windows does);
     * from another tab that spot means nothing, so the first file just shows on the map.
     */
    @Override public void onFilesDrop(List<Path> paths) {
        boolean spotKnown = tab == Tab.BUILD;
        tab = Tab.BUILD;
        double mouseX = minecraft.mouseHandler.xpos() * width / Math.max(1, minecraft.getWindow().getScreenWidth());
        double mouseY = minecraft.mouseHandler.ypos() * height / Math.max(1, minecraft.getWindow().getScreenHeight());
        build.filesDropped(paths, spotKnown, mouseX, mouseY);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.BUILD && build.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (tab == Tab.THEMES && themes.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (tab == Tab.PROJECTS && projects.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Typing in the search box must not close the screen (E) or trigger other keys.
        if (getFocused() instanceof EditBox box && box.isVisible()
                && keyCode != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            box.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (tab == Tab.BUILD) {
            // Esc leaves the full view before it closes the desk; F opens and closes it.
            if (build.fullView() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                build.setFullView(false);
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_F) {
                build.setFullView(!build.fullView());
                return true;
            }
            if (build.keyPressed(keyCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
