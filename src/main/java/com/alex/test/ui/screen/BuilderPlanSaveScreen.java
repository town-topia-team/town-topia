package com.alex.test.ui.screen;

import com.alex.test.client.CopiedBlueprintSaver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static com.alex.test.ui.screen.StorageUi.DENIED;
import static com.alex.test.ui.screen.StorageUi.MUTED;
import static com.alex.test.ui.screen.StorageUi.OUTER;
import static com.alex.test.ui.screen.StorageUi.PANEL;
import static com.alex.test.ui.screen.StorageUi.PANEL_BORDER;
import static com.alex.test.ui.screen.StorageUi.TEXT;
import static com.alex.test.ui.screen.StorageUi.inside;

/**
 * "Save this copy": the name box a copying Builder's plan opens on sneak + right click.
 * Save writes the file to the Copied folder like the desk does, then the plan goes away
 * and the desk opens on the Copied tab with it picked.
 */
final class BuilderPlanSaveScreen extends Screen {
    private static final int W = 240, H = 92, BUTTON_H = 20, GAP = 6;
    private static final String SAVE = "Save", CANCEL = "Cancel";

    private final byte[] bytes;
    private String name;
    @Nullable private EditBox box;
    @Nullable private CompletableFuture<Path> saving;
    @Nullable private String error;

    BuilderPlanSaveScreen(String name, byte[] bytes) {
        super(Component.literal("Save this copy"));
        this.name = name;
        this.bytes = bytes;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }
    private int buttonY() { return top() + H - 8 - BUTTON_H; }
    private int buttonW(String label) { return font.width(label) + 20; }
    private int buttonX(String label) {
        int total = buttonW(SAVE) + GAP + buttonW(CANCEL);
        int x = left() + (W - total) / 2;
        return label.equals(SAVE) ? x : x + buttonW(SAVE) + GAP;
    }

    @Override
    protected void init() {
        box = new EditBox(font, left() + 12, top() + 24, W - 24, 18, Component.literal("Name"));
        box.setMaxLength(64);
        box.setValue(name);
        box.setResponder(value -> name = value);
        addRenderableWidget(box);
        setInitialFocus(box);
    }

    @Override
    public void tick() {
        if (saving == null || !saving.isDone()) return;
        try {
            Path path = saving.join();
            onClose();
            BuilderPlanClient.finishCopy(path);
        } catch (RuntimeException e) {
            error = "The file could not be saved. Check that the game folder can be written to.";
        }
        saving = null;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = left(), y = top();
        graphics.fill(x - 1, y - 1, x + W + 1, y + H + 1, OUTER);
        graphics.fill(x, y, x + W, y + H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + W - 1, y + H - 1, PANEL);
        graphics.drawCenteredString(font, "Save this copy as", x + W / 2, y + 9, TEXT);
        super.render(graphics, mouseX, mouseY, partialTick);
        String note = error != null ? error : saving != null ? "Saving..." : "It goes to the desk's Copied tab.";
        graphics.drawCenteredString(font, StorageUi.trim(font, note, W - 16), x + W / 2, y + 46,
                error != null ? DENIED : MUTED);
        for (String label : new String[] {SAVE, CANCEL}) {
            int bx = buttonX(label);
            StorageUi.button(graphics, font, bx, buttonY(), buttonW(label), BUTTON_H, label,
                    inside(mouseX, mouseY, bx, buttonY(), buttonW(label), BUTTON_H),
                    saving == null && (!label.equals(SAVE) || !name.isBlank()));
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // No blur: the copy's box stays in sight.
    }

    private void save() {
        if (saving != null || name.isBlank()) return;
        error = null;
        saving = CopiedBlueprintSaver.save(Minecraft.getInstance().gameDirectory.toPath(), name, bytes);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inside(mouseX, mouseY, buttonX(SAVE), buttonY(), buttonW(SAVE), BUTTON_H)) {
            save();
            return true;
        }
        if (button == 0 && inside(mouseX, mouseY, buttonX(CANCEL), buttonY(), buttonW(CANCEL), BUTTON_H)) {
            if (saving == null) onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
