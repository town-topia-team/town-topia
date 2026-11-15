package com.alex.test.ui.screen;

import com.alex.test.network.BuilderPlanPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

import static com.alex.test.ui.screen.StorageUi.ACCENT;
import static com.alex.test.ui.screen.StorageUi.GOLD;
import static com.alex.test.ui.screen.StorageUi.MUTED;
import static com.alex.test.ui.screen.StorageUi.OUTER;
import static com.alex.test.ui.screen.StorageUi.PANEL;
import static com.alex.test.ui.screen.StorageUi.PANEL_BORDER;
import static com.alex.test.ui.screen.StorageUi.TEXT;
import static com.alex.test.ui.screen.StorageUi.inside;

/**
 * "Put Cottage here?": the small box a Builder's plan opens on sneak + right click.
 * Build it orders the blueprint here (the server ends the plan); To desk puts the plan away
 * and opens the Builder's desk with the ghost at this spot; Move goes back to placing.
 * In survival, with some of the blocks in the inventory, a "Give my blocks" tick box hands
 * them to the Builder with the order. The game keeps running behind it.
 */
final class BuilderPlanScreen extends Screen {
    /** The box grows by {@link #GIVE_H} while the carried line and tick box show. */
    private static final int W = 240, H = 128, GIVE_H = 28, BUTTON_H = 20, GAP = 6;
    private static final String BUILD = "Build it", DESK = "To desk", MOVE = "Move";
    static final String GIVE_LABEL = "Give my blocks";

    private final PlanWorldGhost ghost;
    @javax.annotation.Nullable private BuildOrderClient.Stock stock;
    /** "Give my blocks" is ticked; starts off. */
    private boolean give;

    BuilderPlanScreen(PlanWorldGhost ghost) {
        super(Component.literal("Builder's plan"));
        this.ghost = ghost;
        var materials = ghost.carried().materials();
        if (materials != null && materials.total() > 0) {
            BuildOrderClient.stock(ghost.carried().villagerId(), materials, answer -> stock = answer);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Blocks of the blueprint in the player's inventory. */
    private int carry() {
        return BuildOrderClient.youCarry(ghost.carried().materials());
    }

    private int h() { return carry() > 0 ? H + GIVE_H : H; }
    private int left() { return (width - W) / 2; }
    private int top() { return (height - h()) / 2; }
    private int buttonY() { return top() + h() - 8 - BUTTON_H; }
    private int buttonW(String label) { return font.width(label) + 16; }
    private int tickX() { return left() + (W - StorageUi.tickBoxWidth(font, GIVE_LABEL)) / 2; }
    private int tickY() { return top() + 82; }

    private boolean overTick(double mouseX, double mouseY) {
        return carry() > 0 && inside(mouseX, mouseY, tickX(), tickY(), StorageUi.tickBoxWidth(font, GIVE_LABEL), 10);
    }

    private int buttonX(String label) {
        int total = buttonW(BUILD) + buttonW(DESK) + buttonW(MOVE) + 2 * GAP;
        int x = left() + (W - total) / 2;
        if (label.equals(BUILD)) return x;
        x += buttonW(BUILD) + GAP;
        if (label.equals(DESK)) return x;
        return x + buttonW(DESK) + GAP;
    }

    private boolean canBuild() {
        return ghost.valid() && !BuildOrderClient.busy();
    }

    private void order() {
        onClose();
        BuilderPlanClient.order(give && carry() > 0);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = left(), y = top(), h = h();
        graphics.fill(x - 1, y - 1, x + W + 1, y + h + 1, OUTER);
        graphics.fill(x, y, x + W, y + h, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + W - 1, y + h - 1, PANEL);
        graphics.drawCenteredString(font, StorageUi.trim(font, "Put " + ghost.name() + " here?", W - 16),
                x + W / 2, y + 8, TEXT);
        BlueprintGhost.Shape shape = ghost.shape();
        String facing = "Faces " + ghost.facing().getName()
                + (ghost.mirror() != net.minecraft.world.level.block.Mirror.NONE ? ", flipped" : "")
                + (shape == null ? "" : String.format(Locale.ROOT, " · %d×%d×%d", shape.sizeX(), shape.sizeY(),
                shape.sizeZ()));
        graphics.drawCenteredString(font, StorageUi.trim(font, facing, W - 16), x + W / 2, y + 22, MUTED);
        int blocked = ghost.inTheWay();
        String ground = blocked < 0 ? "Checking the ground..."
                : blocked == 0 ? "Nothing in the way"
                : blocked + (blocked == 1 ? " block" : " blocks") + " in the way. The Builder clears them.";
        graphics.drawCenteredString(font, StorageUi.trim(font, ground, W - 16), x + W / 2, y + 34,
                blocked > 0 ? GOLD : blocked == 0 ? ACCENT : MUTED);
        var materials = ghost.carried().materials();
        String cost = materials == null ? "Counting the blocks..."
                : String.format(Locale.ROOT, "Needs %,d blocks", materials.total())
                + (stock == null ? ", checking storage..." : stock.enough() ? ", storage has them all"
                : String.format(Locale.ROOT, ", storage has %,d", stock.have()));
        int costColor = stock != null && stock.enough() ? ACCENT : stock != null ? GOLD : TEXT;
        graphics.drawCenteredString(font, StorageUi.trim(font, cost, W - 16), x + W / 2, y + 48, costColor);
        String missing = stock == null ? null : stock.missingLine();
        if (missing != null) {
            graphics.drawCenteredString(font, StorageUi.trim(font, missing, W - 16), x + W / 2, y + 58, GOLD);
        }
        int carry = carry();
        int ly = y + 70;
        String help = "The Builder takes the blocks from the village storage. If some are missing, it waits until you add them.";
        if (carry > 0) {
            graphics.drawCenteredString(font, String.format(Locale.ROOT, "You carry %,d of them", carry), x + W / 2,
                    y + 70, TEXT);
            StorageUi.tickBox(graphics, font, tickX(), tickY(), GIVE_LABEL, give, overTick(mouseX, mouseY));
            help = "The Builder uses the village's blocks from storage. Tick Give my blocks to hand it yours too; "
                    + "they stay yours.";
            ly = y + 98;
        }
        for (FormattedCharSequence part : font.split(Component.literal(help), W - 20)) {
            if (ly > buttonY() - 10) break;
            graphics.drawCenteredString(font, part, x + W / 2, ly, MUTED);
            ly += 10;
        }
        for (String label : new String[] {BUILD, DESK, MOVE}) {
            int bx = buttonX(label);
            StorageUi.button(graphics, font, bx, buttonY(), buttonW(label), BUTTON_H, label,
                    inside(mouseX, mouseY, bx, buttonY(), buttonW(label), BUTTON_H), !label.equals(BUILD) || canBuild());
        }
        if (overTick(mouseX, mouseY)) graphics.renderComponentTooltip(font, giveTooltip(), mouseX, mouseY);
    }

    /** The "Give my blocks" tick box's tooltip (the desk's Build tab shows it too). */
    static List<Component> giveTooltip() {
        return List.of(Component.literal(GIVE_LABEL),
                Component.literal("Also hands the Builder the blocks this").withStyle(ChatFormatting.GRAY),
                Component.literal("order needs from your inventory.").withStyle(ChatFormatting.GRAY),
                Component.literal("They stay yours. Take them back on its desk.").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // No blur: the ghost behind the box stays in sight.
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        if (overTick(mouseX, mouseY)) {
            give = !give;
            return true;
        }
        if (inside(mouseX, mouseY, buttonX(BUILD), buttonY(), buttonW(BUILD), BUTTON_H)) {
            if (!canBuild()) return true;
            order();
            return true;
        }
        if (inside(mouseX, mouseY, buttonX(DESK), buttonY(), buttonW(DESK), BUTTON_H)) {
            onClose();
            BuilderPlanClient.act(BuilderPlanPayloads.CONFIRM);
            return true;
        }
        if (inside(mouseX, mouseY, buttonX(MOVE), buttonY(), buttonW(MOVE), BUTTON_H)) {
            onClose();
            ghost.togglePin();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && canBuild()) {
            order();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
