package com.alex.test.ui.screen;

import java.util.List;

import org.joml.Vector2ic;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.network.chat.Component;

/**
 * Draws a vanilla-looking tooltip with every line evenly spaced. Vanilla treats
 * the first line as an item name and leaves a gap under it, which splits our
 * wrapped sentences in the middle.
 */
public final class SettingsTooltip {
    private static final int LINE_HEIGHT = 10;

    /** Widest a line may run before it wraps; hand-broken lines stay well under it. */
    private static final int MAX_WIDTH = 220;

    private SettingsTooltip() {
    }

    /** Text with "\n" line breaks, each line wrapped again if it runs too wide. */
    public static void render(GuiGraphics graphics, Font font, String text, int mouseX, int mouseY) {
        if (text == null || text.isEmpty()) return;
        List<Component> lines = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n")) {
            for (net.minecraft.network.chat.FormattedText part : font.getSplitter()
                    .splitLines(paragraph, MAX_WIDTH, net.minecraft.network.chat.Style.EMPTY)) {
                lines.add(Component.literal(part.getString()));
            }
        }
        render(graphics, font, lines, mouseX, mouseY);
    }

    @SuppressWarnings("deprecation")
    public static void render(GuiGraphics graphics, Font font, List<Component> lines, int mouseX, int mouseY) {
        if (lines.isEmpty()) return;
        int widest = 0;
        for (Component line : lines) widest = Math.max(widest, font.width(line));
        int width = widest;
        int height = lines.size() * LINE_HEIGHT - 2;
        Vector2ic pos = DefaultTooltipPositioner.INSTANCE.positionTooltip(
                graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY, width, height);
        int x = pos.x();
        int y = pos.y();
        graphics.pose().pushPose();
        graphics.drawManaged(() -> TooltipRenderUtil.renderTooltipBackground(graphics, x, y, width, height, 400));
        graphics.pose().translate(0.0F, 0.0F, 400.0F);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), x, y + i * LINE_HEIGHT, 0xFFFFFFFF, true);
        }
        graphics.pose().popPose();
    }
}
