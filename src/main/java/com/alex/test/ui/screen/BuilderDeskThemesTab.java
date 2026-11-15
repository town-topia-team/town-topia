package com.alex.test.ui.screen;

import com.alex.test.client.BlueprintFiles;
import com.alex.test.client.BlueprintThemes;
import com.alex.test.network.BuilderPlanPayloads;
import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.alex.test.village.villagers.builder.blueprint.BlueprintException;
import com.alex.test.village.villagers.builder.blueprint.BlueprintMaterials;
import com.alex.test.village.villagers.builder.blueprint.BlueprintRoomGuess;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.alex.test.ui.screen.StorageUi.ACCENT;
import static com.alex.test.ui.screen.StorageUi.DENIED;
import static com.alex.test.ui.screen.StorageUi.GOLD;
import static com.alex.test.ui.screen.StorageUi.MUTED;
import static com.alex.test.ui.screen.StorageUi.PANEL_DARK;
import static com.alex.test.ui.screen.StorageUi.TEXT;
import static com.alex.test.ui.screen.StorageUi.inside;

/**
 * The desk's Themes tab: ready-made buildings from theme packs ({@link BlueprintThemes}).
 * Left: the packs and their credits. Middle: the picked pack's buildings. Right: the picked
 * building in 3D, who made it, and two buttons: Show on map (the Build tab, with it on the
 * village map) and Preview in world (a Builder's plan with it, like the Build tab's).
 */
final class BuilderDeskThemesTab {
    private static final int ROW_H = 24, PACK_H = 30, GAP = 8, DETAILS_H = 56, BUTTON_H = 20;
    private static final String MAP_LABEL = "Show on map", WORLD_LABEL = "Preview in world",
            FOLDER_LABEL = "Open themes folder";

    /** Hands a read building to the Build tab, with the village map showing. */
    interface MapHandoff {
        void show(BlueprintFiles.Entry entry, Blueprint blueprint, @Nullable BlueprintMaterials.Result materials,
                  @Nullable TokenType guess, @Nullable BlueprintPreviewView.Prepared prepared);
    }

    /** A building read in full, or why it could not be. */
    private record Picked(BlueprintFiles.Entry entry, @Nullable Blueprint blueprint,
                          @Nullable BlueprintMaterials.Result materials, @Nullable TokenType guess,
                          @Nullable BlueprintPreviewView.Prepared preview, @Nullable String error) {}

    private final BuilderDeskMenu menu;
    private final BlueprintThemes themes;
    private final BlueprintPreviewView preview = new BlueprintPreviewView();
    private final MapHandoff handoff;
    private Font font;

    @Nullable private String themeId;
    @Nullable private BlueprintThemes.Building selected;
    @Nullable private CompletableFuture<Picked> loading;
    @Nullable private Picked picked;
    private int packScroll, buildingScroll;
    /** List lines (type and size) of the shown pack's buildings, read one at a time. */
    private final Map<Path, BlueprintFiles.Summary> summaries = new HashMap<>();
    @Nullable private CompletableFuture<BlueprintFiles.Summary> summarising;
    @Nullable private Path summarisingKey;

    private int left, top, right, bottom;

    BuilderDeskThemesTab(BuilderDeskMenu menu, MapHandoff handoff) {
        this.menu = menu;
        this.handoff = handoff;
        this.font = Minecraft.getInstance().font;
        this.themes = new BlueprintThemes(Minecraft.getInstance().gameDirectory.toPath());
        themes.refresh();
    }

    void tick() {
        themes.poll();
        if (themeId == null && !themes.themes().isEmpty()) themeId = themes.themes().get(0).id();
        if (loading != null && loading.isDone()) {
            Picked result = loading.isCompletedExceptionally() || loading.isCancelled() ? null : loading.join();
            loading = null;
            if (result != null && selected != null && result.entry().equals(selected.entry())) {
                picked = result;
                preview.show(result.preview());
            }
        }
        tickSummaries();
    }

    void close() {
        themes.close();
        if (loading != null) loading.cancel(false);
        if (summarising != null) summarising.cancel(false);
        loading = null;
        summarising = null;
        preview.close();
    }

    @Nullable
    private BlueprintThemes.Theme theme() {
        for (BlueprintThemes.Theme theme : themes.themes()) {
            if (theme.id().equals(themeId)) return theme;
        }
        return null;
    }

    private void pickTheme(BlueprintThemes.Theme theme) {
        if (theme.id().equals(themeId)) return;
        themeId = theme.id();
        buildingScroll = 0;
    }

    private void pickBuilding(BlueprintThemes.Building building) {
        if (selected != null && selected.entry().equals(building.entry())) return;
        selected = building;
        picked = null;
        preview.show(null);
        if (loading != null) loading.cancel(false);
        BlueprintFiles.Entry entry = building.entry();
        loading = CompletableFuture.supplyAsync(() -> read(entry), Util.backgroundExecutor());
    }

    /** Background thread: read, count and prepare the 3D view. */
    private static Picked read(BlueprintFiles.Entry entry) {
        try {
            Blueprint blueprint = BlueprintFiles.read(entry);
            return new Picked(entry, blueprint, BlueprintMaterials.count(blueprint), BlueprintRoomGuess.guess(blueprint),
                    BlueprintPreviewView.prepare(blueprint), null);
        } catch (BlueprintException e) {
            return new Picked(entry, null, null, null, null, e.getMessage());
        } catch (RuntimeException e) {
            return new Picked(entry, null, null, null, null, "This building can't be read.");
        }
    }

    private void tickSummaries() {
        if (summarising != null) {
            if (!summarising.isDone()) return;
            if (!summarising.isCompletedExceptionally() && !summarising.isCancelled() && summarisingKey != null) {
                summaries.put(summarisingKey, summarising.join());
            }
            summarising = null;
            summarisingKey = null;
        }
        BlueprintThemes.Theme theme = theme();
        if (theme == null) return;
        for (BlueprintThemes.Building building : theme.buildings()) {
            BlueprintFiles.Entry entry = building.entry();
            if (summaries.containsKey(entry.path())) continue;
            summarisingKey = entry.path();
            summarising = CompletableFuture.supplyAsync(() -> BlueprintFiles.summarise(entry), Util.backgroundExecutor());
            return;
        }
    }

    private boolean ready() {
        return picked != null && picked.blueprint() != null;
    }

    private boolean canUseMap() {
        return ready() && menu.snapshot().border() != null;
    }

    private void showOnMap() {
        if (!canUseMap()) return;
        handoff.show(picked.entry(), picked.blueprint(), picked.materials(), picked.guess(), picked.preview());
    }

    /** A Builder's plan with this building, like the Build tab's Preview in world; the server closes the desk. */
    private void previewInWorld() {
        if (!canUseMap()) return;
        BuilderPlanClient.start(new BuilderPlanClient.Carried(menu.villagerId(), picked.entry(), picked.blueprint(),
                picked.materials(), picked.guess(), picked.preview(), menu.snapshot().border(),
                menu.snapshot().buildings(), Rotation.NONE, Mirror.NONE, null));
        PacketDistributor.sendToServer(new BuilderPlanPayloads.TakePlan(menu.villagerId(), picked.blueprint().name(),
                false));
    }

    private void openFolder() {
        Path folder = BlueprintThemes.testFolder(Minecraft.getInstance().gameDirectory.toPath());
        try {
            Files.createDirectories(folder);
        } catch (Exception e) {
            return;
        }
        Util.getPlatform().openFile(folder.toFile());
    }

    // =========================================================================
    // Layout
    // =========================================================================

    private int packW() { return Mth.clamp((right - left) / 4, 110, 150); }
    private int packRight() { return left + packW(); }
    private int listLeft() { return packRight() + GAP; }
    private int listRight() { return listLeft() + Mth.clamp((right - left) / 4, 120, 165); }
    private int viewLeft() { return listRight() + GAP; }
    private int viewBottom() { return bottom - DETAILS_H - GAP; }
    private int detailsTop() { return bottom - DETAILS_H; }
    private int folderY() { return bottom - 6 - BUTTON_H; }
    private int packTop() { return top + 20; }
    private int packBottom() { return folderY() - 6; }
    // The two buttons: one even row along the bottom of the details, 8 px in from each side.
    private int buttonW() { return (right - viewLeft() - 16 - 4) / 2; }
    private int buttonY() { return bottom - 6 - BUTTON_H; }
    private int worldX() { return viewLeft() + 8; }
    private int mapX() { return right - 8 - buttonW(); }

    /** The pack's name and note above its buildings; its height. */
    private int listHeaderH(BlueprintThemes.Theme theme) {
        int lines = 1 + (theme.problem() != null ? 1 : 0);
        return 6 + lines * 10 + 6;
    }

    private int listTop() {
        BlueprintThemes.Theme theme = theme();
        return top + (theme == null ? 20 : listHeaderH(theme));
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    void render(GuiGraphics graphics, Font font, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        this.font = font;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        renderPacks(graphics, mouseX, mouseY);
        renderBuildings(graphics, mouseX, mouseY);
        renderView(graphics, mouseX, mouseY);
        renderDetails(graphics, mouseX, mouseY);
    }

    private void renderPacks(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(left, top, packRight(), bottom, PANEL_DARK);
        graphics.drawString(font, "Theme packs", left + 8, top + 6, ACCENT, false);
        List<BlueprintThemes.Theme> all = themes.themes();
        int x = left + 6, width = packW() - 12;
        if (all.isEmpty()) {
            String text = themes.loaded() ? "No theme packs yet." : "Looking for themes...";
            graphics.drawString(font, StorageUi.trim(font, text, width), x + 2, packTop() + 4, MUTED, false);
        }
        int maxScroll = packMaxScroll();
        packScroll = Mth.clamp(packScroll, 0, maxScroll);
        int rowWidth = maxScroll > 0 ? width - 6 : width;
        graphics.enableScissor(left, packTop(), packRight(), packBottom());
        int y = packTop() - packScroll;
        for (BlueprintThemes.Theme theme : all) {
            if (y + PACK_H >= packTop() && y < packBottom()) {
                boolean hover = inside(mouseX, mouseY, x, y, rowWidth, PACK_H) && mouseY >= packTop() && mouseY < packBottom();
                StorageUi.row(graphics, x, y, rowWidth, PACK_H, theme.id().equals(themeId), hover);
                graphics.renderFakeItem(new ItemStack(theme.icon()), x + 4, y + (PACK_H - 16) / 2);
                graphics.drawString(font, StorageUi.trim(font, theme.name(), rowWidth - 28), x + 24, y + 5, TEXT, false);
                String detail = (theme.author().isEmpty() ? "" : "by " + theme.author() + " · ")
                        + theme.buildings().size() + (theme.buildings().size() == 1 ? " building" : " buildings");
                graphics.drawString(font, StorageUi.trim(font, detail, rowWidth - 28), x + 24, y + 16, MUTED, false);
            }
            y += PACK_H + 2;
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            StorageUi.scrollbar(graphics, packRight() - 8, packTop(), packBottom() - packTop(), packScroll, maxScroll,
                    packBottom() - packTop());
        }
        StorageUi.button(graphics, font, x, folderY(), width, BUTTON_H, StorageUi.trim(font, FOLDER_LABEL, width - 6),
                inside(mouseX, mouseY, x, folderY(), width, BUTTON_H), true);
    }

    private void renderBuildings(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(listLeft(), top, listRight(), bottom, PANEL_DARK);
        BlueprintThemes.Theme theme = theme();
        int x = listLeft() + 6, width = listRight() - listLeft() - 12;
        if (theme == null) return;
        int y = top + 6;
        graphics.drawString(font, StorageUi.trim(font, theme.name(), width), x + 2, y, ACCENT, false);
        y += 10;
        if (theme.problem() != null) {
            graphics.drawString(font, StorageUi.trim(font, theme.problem(), width), x + 2, y, GOLD, false);
        }
        int listTop = listTop(), listBottom = bottom - 6;
        List<BlueprintThemes.Building> buildings = theme.buildings();
        int maxScroll = buildingMaxScroll(theme);
        buildingScroll = Mth.clamp(buildingScroll, 0, maxScroll);
        int rowWidth = maxScroll > 0 ? width - 6 : width;
        graphics.enableScissor(listLeft(), listTop, listRight(), listBottom);
        y = listTop - buildingScroll;
        for (BlueprintThemes.Building building : buildings) {
            if (y + ROW_H >= listTop && y < listBottom) {
                boolean hover = inside(mouseX, mouseY, x, y, rowWidth, ROW_H) && mouseY >= listTop && mouseY < listBottom;
                boolean isSelected = selected != null && selected.entry().equals(building.entry());
                StorageUi.row(graphics, x, y, rowWidth, ROW_H, isSelected, hover);
                graphics.drawString(font, StorageUi.trim(font, building.name(), rowWidth - 8), x + 4, y + 3, TEXT, false);
                BlueprintFiles.Summary summary = summaries.get(building.entry().path());
                String detail;
                int color = MUTED;
                if (summary == null) {
                    detail = "Reading...";
                } else if (summary.error() != null) {
                    detail = "Can't read";
                    color = DENIED;
                } else {
                    detail = BlueprintRoomGuess.displayName(summary.guess()) + " · " + summary.sizeX() + "×"
                            + summary.sizeZ();
                }
                graphics.drawString(font, StorageUi.trim(font, detail, rowWidth - 8), x + 4, y + 13, color, false);
            }
            y += ROW_H + 2;
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            StorageUi.scrollbar(graphics, listRight() - 8, listTop, listBottom - listTop, buildingScroll, maxScroll,
                    listBottom - listTop);
        }
    }

    /** How far the pack list scrolls; 0 when every pack fits. */
    private int packMaxScroll() {
        return Math.max(0, themes.themes().size() * (PACK_H + 2) - (packBottom() - packTop()));
    }

    /** How far the building list scrolls; 0 when every building fits. */
    private int buildingMaxScroll(BlueprintThemes.Theme theme) {
        return Math.max(0, theme.buildings().size() * (ROW_H + 2) - (bottom - 6 - listTop()));
    }

    private void renderView(GuiGraphics graphics, int mouseX, int mouseY) {
        int vl = viewLeft() + 1, vt = top + 1, vr = right - 1, vb = viewBottom() - 1;
        preview.setViewport(vl, vt, vr, vb);
        preview.render(graphics);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        int cx = (vl + vr) / 2, cy = (vt + vb) / 2;
        if (themes.loaded() && themes.themes().isEmpty()) {
            String[] lines = {"No theme packs yet.", "Make a folder in the themes folder,",
                    "put building files in it, then open the desk again."};
            for (int i = 0; i < lines.length; i++) {
                graphics.drawCenteredString(font, StorageUi.trim(font, lines[i], vr - vl - 8), cx, cy - 14 + i * 12,
                        i == 0 ? TEXT : MUTED);
            }
        } else if (selected == null) {
            graphics.drawCenteredString(font, "Pick a building to see it here.", cx, cy - 4, MUTED);
        } else if (picked == null) {
            graphics.drawCenteredString(font, "Reading the building...", cx, cy - 4, MUTED);
        } else if (picked.error() != null) {
            // Why, under it: the details strip has no line for it.
            graphics.drawCenteredString(font, "No preview", cx, cy - 10, MUTED);
            graphics.drawCenteredString(font, StorageUi.trim(font, picked.error(), vr - vl - 8), cx, cy + 2, DENIED);
        }
        if (preview.shown() != null) {
            graphics.drawString(font, "Drag to spin it · scroll to zoom", vl + 4, vb - 12, MUTED, false);
            preview.renderLayers(graphics, font, mouseX, mouseY);
            String note = preview.layerNote();
            if (note != null) graphics.drawString(font, note, vl + 4, vt + 4, MUTED, false);
        }
        graphics.pose().popPose();
    }

    /**
     * The picked building under the view: name and size, who made it, then the two buttons as
     * one even row along the bottom. Every line gets the whole width.
     */
    private void renderDetails(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = viewLeft();
        graphics.fill(x, detailsTop(), right, bottom, PANEL_DARK);
        boolean usable = canUseMap();
        int buttonW = buttonW();
        StorageUi.button(graphics, font, worldX(), buttonY(), buttonW, BUTTON_H,
                StorageUi.trim(font, WORLD_LABEL, buttonW - 6),
                inside(mouseX, mouseY, worldX(), buttonY(), buttonW, BUTTON_H), usable);
        StorageUi.button(graphics, font, mapX(), buttonY(), buttonW, BUTTON_H,
                StorageUi.trim(font, MAP_LABEL, buttonW - 6),
                inside(mouseX, mouseY, mapX(), buttonY(), buttonW, BUTTON_H), usable);
        int textX = x + 8, textW = right - 8 - textX, y = detailsTop() + 6;
        if (selected == null) {
            graphics.drawString(font, StorageUi.trim(font, "Pick a building in the middle list.", textW), textX, y,
                    MUTED, false);
            return;
        }
        // Name on the left, size on the right of the same line.
        String size = picked != null && picked.blueprint() != null ? String.format(Locale.ROOT, "%d×%d×%d",
                picked.blueprint().sizeX(), picked.blueprint().sizeY(), picked.blueprint().sizeZ()) : "";
        int sizeW = font.width(size);
        graphics.drawString(font, StorageUi.trim(font, selected.name(), textW - sizeW - 6), textX, y, TEXT, false);
        graphics.drawString(font, size, textX + textW - sizeW, y, MUTED, false);
        String credit = selected.author().isEmpty() ? "Maker not given" : "Made by " + selected.author();
        graphics.drawString(font, StorageUi.trim(font, credit, textW), textX, y + 12, selected.author().isEmpty()
                ? MUTED : ACCENT, false);
    }

    // =========================================================================
    // Tooltips and input
    // =========================================================================

    @Nullable
    List<Component> tooltip(int mouseX, int mouseY) {
        if (preview.overLayers(mouseX, mouseY)) return preview.layersTooltip();
        int width = packW() - 12;
        if (inside(mouseX, mouseY, left + 6, folderY(), width, BUTTON_H)) {
            return List.of(Component.literal(FOLDER_LABEL),
                    gray("Make a folder in it for your theme and"),
                    gray("put building files inside. Open the desk"),
                    gray("again and your theme shows up here."));
        }
        if (inside(mouseX, mouseY, mapX(), buttonY(), buttonW(), BUTTON_H)) {
            List<Component> out = new ArrayList<>(List.of(Component.literal(MAP_LABEL),
                    gray("Opens the Build tab with this building"),
                    gray("on your village map, ready to place.")));
            if (!canUseMap()) out.add(red(ready() ? "The village map isn't ready yet." : "Pick a building first."));
            return out;
        }
        if (inside(mouseX, mouseY, worldX(), buttonY(), buttonW(), BUTTON_H)) {
            List<Component> out = new ArrayList<>(List.of(Component.literal(WORLD_LABEL),
                    gray("Gives you a plan to hold in your hand."),
                    gray("Look at the ground to see the building there.")));
            if (!canUseMap()) out.add(red(ready() ? "The village map isn't ready yet." : "Pick a building first."));
            return out;
        }
        BlueprintThemes.Theme theme = packAt(mouseX, mouseY);
        if (theme != null) {
            List<Component> out = new ArrayList<>();
            out.add(Component.literal(theme.name()));
            if (!theme.author().isEmpty()) out.add(gray("Made by " + theme.author()));
            out.add(gray(theme.where().equals("Test folder") ? "From your themes folder" : "Comes with the game"));
            return out;
        }
        return null;
    }

    private static Component gray(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static Component red(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    @Nullable
    private BlueprintThemes.Theme packAt(double mouseX, double mouseY) {
        int x = left + 6, width = packW() - 12 - (packMaxScroll() > 0 ? 6 : 0);
        if (mouseY < packTop() || mouseY >= packBottom()) return null;
        int y = packTop() - packScroll;
        for (BlueprintThemes.Theme theme : themes.themes()) {
            if (inside(mouseX, mouseY, x, y, width, PACK_H)) return theme;
            y += PACK_H + 2;
        }
        return null;
    }

    @Nullable
    private BlueprintThemes.Building buildingAt(double mouseX, double mouseY) {
        BlueprintThemes.Theme theme = theme();
        if (theme == null) return null;
        int x = listLeft() + 6, width = listRight() - listLeft() - 12 - (buildingMaxScroll(theme) > 0 ? 6 : 0);
        if (mouseY < listTop() || mouseY >= bottom - 6) return null;
        int y = listTop() - buildingScroll;
        for (BlueprintThemes.Building building : theme.buildings()) {
            if (inside(mouseX, mouseY, x, y, width, ROW_H)) return building;
            y += ROW_H + 2;
        }
        return null;
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (preview.clickLayers(mouseX, mouseY)) return true;
            if (inside(mouseX, mouseY, left + 6, folderY(), packW() - 12, BUTTON_H)) {
                openFolder();
                return true;
            }
            if (inside(mouseX, mouseY, mapX(), buttonY(), buttonW(), BUTTON_H)) {
                showOnMap();
                return true;
            }
            if (inside(mouseX, mouseY, worldX(), buttonY(), buttonW(), BUTTON_H)) {
                previewInWorld();
                return true;
            }
            BlueprintThemes.Theme theme = packAt(mouseX, mouseY);
            if (theme != null) {
                pickTheme(theme);
                return true;
            }
            BlueprintThemes.Building building = buildingAt(mouseX, mouseY);
            if (building != null) {
                pickBuilding(building);
                return true;
            }
        }
        return preview.beginDrag(mouseX, mouseY, button);
    }

    boolean mouseDragged(int button, double dragX, double dragY) {
        return preview.drag(button, dragX, dragY);
    }

    boolean mouseReleased(int button) {
        return preview.endDrag(button);
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        int step = (int) Math.round(scrollY * 14);
        if (mouseX >= left && mouseX < packRight()) {
            packScroll = Math.max(0, packScroll - step);
            return true;
        }
        if (mouseX >= listLeft() && mouseX < listRight()) {
            buildingScroll = Math.max(0, buildingScroll - step);
            return true;
        }
        // Shift + scroll moves the Layers one layer; plain scroll zooms (as on the village map).
        if (Screen.hasShiftDown() && preview.shown() != null && preview.contains(mouseX, mouseY)) {
            preview.layerStep(scrollY > 0 ? 1 : -1);
            return true;
        }
        return preview.zoomAt(mouseX, mouseY, scrollY);
    }
}
