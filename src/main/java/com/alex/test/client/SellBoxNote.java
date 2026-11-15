package com.alex.test.client;

import com.alex.test.network.SellBoxNotePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the sell box hint on a merchant stall barrel's screen, in a card just
 * above the window (below it when there is no room above), wrapped to fit the
 * GUI scale. It stays for as long as the barrel is open, sharp in front of the
 * blurred world, and the server resends it when the counts change.
 */
public final class SellBoxNote {
    private SellBoxNote() {}

    private static final int LINE_HEIGHT = 10;
    private static final int PAD = 5;
    /** Room for the emerald beside the heading. */
    private static final int ICON = 18;
    private static final int GAP = 3;
    /** A note waiting for its screen is dropped after this long. */
    private static final long PENDING_MS = 3000;

    private static int containerId = -1;
    private static List<String> lines = List.of();
    private static Screen screen;
    private static long receivedAt;

    public static void receive(SellBoxNotePayload payload) {
        containerId = payload.containerId();
        lines = payload.lines();
        receivedAt = System.currentTimeMillis();
        Screen current = Minecraft.getInstance().screen;
        screen = matches(current) ? current : null;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        // The note can arrive just before its screen opens.
        if (screen == null && !lines.isEmpty()
                && System.currentTimeMillis() - receivedAt <= PENDING_MS && matches(event.getScreen())) {
            screen = event.getScreen();
        }
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() == screen) {
            screen = null;
            lines = List.of();
            containerId = -1;
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (screen == null || event.getScreen() != screen || lines.isEmpty()
                || !(screen instanceof AbstractContainerScreen<?> container)) return;
        draw(event.getGuiGraphics(), container);
    }

    private static boolean matches(Screen candidate) {
        Minecraft minecraft = Minecraft.getInstance();
        return candidate instanceof AbstractContainerScreen<?> container && minecraft.player != null
                && container.getMenu().containerId == containerId
                && minecraft.player.containerMenu == container.getMenu();
    }

    private static void draw(GuiGraphics graphics, AbstractContainerScreen<?> container) {
        Font font = Minecraft.getInstance().font;
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        int top = container.getGuiTop();
        int bottom = top + container.getYSize();
        int centre = container.getGuiLeft() + container.getXSize() / 2;
        int maxWidth = screenWidth - 8;

        // Narrowest card that fits above the window, then below it; wider cards wrap into
        // fewer lines. With no room either side it sits at the top, over the window.
        int[] widths = {container.getXSize(), 240, 320, maxWidth};
        List<FormattedCharSequence> rows = null;
        int width = 0;
        int y = -1;
        for (boolean above : new boolean[] {true, false}) {
            for (int candidate : widths) {
                int inner = Math.min(candidate, maxWidth) - 2 * PAD;
                if (inner <= ICON) continue;
                List<FormattedCharSequence> wrapped = wrap(font, inner);
                int height = cardHeight(wrapped.size());
                if (above ? height <= top - GAP : bottom + GAP + height <= screenHeight) {
                    rows = wrapped;
                    width = inner + 2 * PAD;
                    y = above ? top - GAP - height : bottom + GAP;
                    break;
                }
            }
            if (rows != null) break;
        }
        boolean overWindow = rows == null;
        if (overWindow) {
            width = Math.max(ICON + 2 * PAD + 1, maxWidth);
            rows = wrap(font, width - 2 * PAD);
            y = 2;
        }
        int height = cardHeight(rows.size());
        int x = Math.max(4, Math.min(centre - width / 2, screenWidth - 4 - width));

        graphics.pose().pushPose();
        // In front of the window's slots and items when it has to sit over them.
        if (overWindow) graphics.pose().translate(0, 0, 400);
        // Same card as the storage token hint: dark border, grey rim, green rule.
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF080808);
        graphics.fill(x, y, x + width, y + height, 0xFF454545);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xF0202020);
        graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, 0xFF6BAF72);
        graphics.renderFakeItem(new ItemStack(Items.EMERALD), x + PAD - 1, y + 3);
        graphics.drawString(font, rows.get(0), x + PAD + ICON, y + 7, 0xFFE8B84A, false);
        for (int i = 1; i < rows.size(); i++) {
            graphics.drawString(font, rows.get(i), x + PAD, y + ICON + 3 + (i - 1) * LINE_HEIGHT,
                    0xFFF3F3F3, false);
        }
        graphics.pose().popPose();
    }

    /** The heading on its own row beside the emerald, then each line wrapped to {@code inner}. */
    private static List<FormattedCharSequence> wrap(Font font, int inner) {
        List<FormattedCharSequence> rows = new ArrayList<>();
        rows.add(Component.literal(lines.get(0)).getVisualOrderText());
        for (int i = 1; i < lines.size(); i++) rows.addAll(font.split(Component.literal(lines.get(i)), inner));
        return rows;
    }

    /** Heading row with the emerald, then one row per body line, then padding and the rule. */
    private static int cardHeight(int rows) {
        return ICON + 3 + (rows - 1) * LINE_HEIGHT + PAD;
    }
}
