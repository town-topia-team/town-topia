package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import com.alex.test.client.ClientPerformanceInfo;
import com.alex.test.network.PerformancePayloads;
import com.alex.test.performance.PerformanceCost;
import com.alex.test.performance.PerformanceOption;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * The Mayor's Performance tab, Server side ({@link PerformanceTab}): one row per {@link PerformanceOption}
 * of {@link PerformanceOption.Group#SERVER}, world-wide, the same in every village. Values come from the
 * server ({@link ClientPerformanceInfo}); until they do, every button is greyed. Only the server owner may
 * click; the server checks again ({@code PerformancePayloads}). The server owner also sees the server's
 * average tick and each running option's time (last 5 s, refreshed with every answer, every 2 s), the
 * worst tick in the hover.
 *
 * <p>Coordinates are the page's top-left as {@link PerformanceTab} draws it (scroll already taken
 * off), so drawing and clicking use the same numbers.
 */
final class MayorPerformancePage {

    /** The "whole world" line and the server tick (owner) or "owner only" line, from the page's top. */
    private static final int SERVER_TEXT_Y = 0;
    private static final int SERVER_TICK_Y = 11;
    /** First row, from the page's top. */
    private static final int ROWS_Y = 24;
    private static final int ROW_STEP = RULE_ROW_H + 2;
    /** Red for a time that eats a big share of the tick (as the screen's "denied" red). */
    private static final int HIGH = 0xFFE07A6E;

    private final MayorStatsScreen screen;

    MayorPerformancePage(MayorStatsScreen screen) {
        this.screen = screen;
    }

    /** The rows this page shows: the server's (a "This computer" group would need its own rows and storage). */
    private static List<PerformanceOption> rows() {
        return PerformanceOption.of(PerformanceOption.Group.SERVER);
    }

    /** From the page's top to the last row's bottom. */
    static int height() {
        return ROWS_Y + rows().size() * ROW_STEP;
    }

    /** The page opens: drop the last answer (maybe another world's), then ask. */
    void open() {
        ClientPerformanceInfo.forget();
        ask();
    }

    void ask() {
        PacketDistributor.sendToServer(new PerformancePayloads.RequestInfo(screen.mayorId()));
    }

    void render(GuiGraphics graphics, int x, int y, int width, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        int mayor = screen.mayorId();
        graphics.drawString(font, trim(Tr.s("screen.dynamicvillages.mayor.performance.whole_world",
                "These are for the whole world, in every village."), width - 4), x + 2, y + SERVER_TEXT_Y,
                MUTED, false);
        boolean known = ClientPerformanceInfo.isFor(mayor);
        if (known && !ClientPerformanceInfo.mayChange(mayor)) {
            graphics.drawString(font, trim(ownerOnly(), width - 4), x + 2, y + SERVER_TICK_Y,
                    DIM, false);
        } else {
            renderServerTick(graphics, font, mayor, x + 2, y + SERVER_TICK_Y, width - 4, mouseX, mouseY);
        }
        int rowY = y + ROWS_Y;
        for (PerformanceOption option : rows()) {
            Boolean on = ClientPerformanceInfo.value(mayor, option);
            boolean stopped = ClientPerformanceInfo.stopped(mayor, option);
            boolean enabled = enabled(mayor, option);
            String description = on == null
                    ? Tr.s("screen.dynamicvillages.mayor.performance.asking", "Asking the Mayor...")
                    : stopped ? enabled
                            ? Tr.s("screen.dynamicvillages.mayor.performance.stopped", "Stopped after errors, click to restart")
                            : Tr.s("screen.dynamicvillages.mayor.performance.stopped_short", "Stopped after errors")
                    : option.description(on);
            String label = on == null ? "..." : ruleWord(on ? "On" : "Off");
            drawRuleRow(graphics, x, rowY, width, option.icon(), option.title(), description, label,
                    on != null && on, enabled, mouseX, mouseY);
            PerformanceCost cost = shownCost(mayor, option);
            if (cost != null) {
                // On the title line, just left of the button; left out when a long title would run into it
                // (the hover still has it).
                String time = ms(cost.averageNanos());
                int timeX = x + width - RULE_TOGGLE_W - 6 - 5 - font.width(time);
                if (x + 28 + font.width(option.title()) + 6 <= timeX) {
                    graphics.drawString(font, time, timeX, rowY + 5,
                            loadColour(cost.averageNanos(), ClientPerformanceInfo.tickTargetNanos(mayor)), false);
                }
            }
            if (inside(mouseX, mouseY, x, rowY, width, RULE_ROW_H)) {
                screen.setDeferredTooltip(tooltipLines(tooltip(mayor, option)));
            }
            rowY += ROW_STEP;
        }
    }

    /**
     * "Server tick: 18.0 ms of 50 ms" for the server owner (the whole server's average tick, last 5 s); nothing
     * until the answer comes. Its hover says what the number means.
     */
    private void renderServerTick(GuiGraphics graphics, Font font, int mayor, int x, int y, int width,
                                  int mouseX, int mouseY) {
        long tick = ClientPerformanceInfo.tickNanos(mayor);
        long target = ClientPerformanceInfo.tickTargetNanos(mayor);
        if (tick < 0 || target <= 0) return;
        String line = trim(Tr.s("screen.dynamicvillages.mayor.performance.server_tick", "Server tick: %s of %s",
                ms(tick), ms(target)), width);
        int colour = tick > target ? HIGH : tick * 10 > target * 6 ? GOLD : MUTED;
        graphics.drawString(font, line, x, y, colour, false);
        if (inside(mouseX, mouseY, x, y - 1, font.width(line), 10)) {
            List<Component> lines = new ArrayList<>(Tr.lines("screen.dynamicvillages.mayor.performance.server_tick.tip",
                    "How long the server needs for one game tick,\naveraged over the last 5 seconds.\n"
                            + "Over %s the whole game slows down.", ms(target)));
            screen.setDeferredTooltip(tooltipLines(lines.toArray(Component[]::new)));
        }
    }

    /** The option's time, only while it is on and what it needs is on (the server sends it only to the owner). */
    private static @Nullable PerformanceCost shownCost(int mayor, PerformanceOption option) {
        if (!Boolean.TRUE.equals(ClientPerformanceInfo.value(mayor, option))) return null;
        PerformanceOption needs = option.needs();
        if (needs != null && !Boolean.TRUE.equals(ClientPerformanceInfo.value(mayor, needs))) return null;
        return ClientPerformanceInfo.cost(mayor, option);
    }

    /** "0.8 ms", "12 ms", "<0.1 ms". */
    private static String ms(long nanos) {
        double ms = nanos / 1_000_000.0;
        String number = nanos < 100_000 ? "<0.1"
                : ms < 10 ? String.format(Locale.ROOT, "%.1f", ms) : String.format(Locale.ROOT, "%.0f", ms);
        return Tr.s("screen.dynamicvillages.mayor.performance.ms", "%s ms", number);
    }

    /** Gray under a tenth of the tick, gold up to a quarter, red above. */
    private static int loadColour(long nanos, long target) {
        if (target <= 0) return MUTED;
        return nanos * 4 > target ? HIGH : nanos * 10 > target ? GOLD : MUTED;
    }

    /** True when the click was on this page (it does nothing else then). */
    boolean mouseClicked(double mouseX, double mouseY, int x, int y, int width) {
        int mayor = screen.mayorId();
        int buttonX = x + width - RULE_TOGGLE_W - 6;
        int rowY = y + ROWS_Y;
        for (PerformanceOption option : rows()) {
            if (inside(mouseX, mouseY, buttonX, rowY + 5, RULE_TOGGLE_W, 18)) {
                Boolean on = ClientPerformanceInfo.value(mayor, option);
                if (on != null && enabled(mayor, option)) {
                    // Stopped after errors shows off: a click turns it back on.
                    PacketDistributor.sendToServer(new PerformancePayloads.SetOption(mayor, option.key(), !on));
                    ClientPerformanceInfo.setLocally(mayor, option, !on);
                }
                return true;
            }
            rowY += ROW_STEP;
        }
        return false;
    }

    /** Known, the server owner, and the option it needs (if any) is on. */
    private static boolean enabled(int mayor, PerformanceOption option) {
        if (ClientPerformanceInfo.value(mayor, option) == null || !ClientPerformanceInfo.mayChange(mayor)) return false;
        PerformanceOption needs = option.needs();
        return needs == null || Boolean.TRUE.equals(ClientPerformanceInfo.value(mayor, needs));
    }

    /** The option's own lines, then one gray line on why it cannot be clicked (or that it stopped). */
    private static Component[] tooltip(int mayor, PerformanceOption option) {
        List<Component> lines = new ArrayList<>(option.hover());
        PerformanceCost cost = shownCost(mayor, option);
        if (cost != null) {
            // An option another one needs: that one's time is on its own row, not in this number.
            PerformanceOption dependent = null;
            for (PerformanceOption other : rows()) {
                if (other.needs() == option) dependent = other;
            }
            String used = dependent == null
                    ? Tr.s("screen.dynamicvillages.mayor.performance.cost_tip", "Uses %s a tick, %s at worst (last 5 s).",
                            ms(cost.averageNanos()), ms(cost.worstNanos()))
                    : Tr.s("screen.dynamicvillages.mayor.performance.cost_tip_apart",
                            "Uses %s a tick, %s at worst (last 5 s), not counting %s.",
                            ms(cost.averageNanos()), ms(cost.worstNanos()), dependent.title());
            lines.add(Component.literal(used).withStyle(ChatFormatting.GOLD));
        }
        PerformanceOption needs = option.needs();
        String why = null;
        if (ClientPerformanceInfo.isFor(mayor) && !ClientPerformanceInfo.mayChange(mayor)) {
            why = ownerOnly();
        } else if (needs != null && Boolean.FALSE.equals(ClientPerformanceInfo.value(mayor, needs))) {
            why = Tr.s("screen.dynamicvillages.mayor.performance.needs", "Turn on %s first.", needs.title());
        } else if (ClientPerformanceInfo.stopped(mayor, option)) {
            why = Tr.s("screen.dynamicvillages.mayor.performance.stopped_tip", "Switched off after errors; see the server log.");
        }
        if (why != null) lines.add(Component.literal(why).withStyle(ChatFormatting.GRAY));
        return lines.toArray(Component[]::new);
    }

    private static String ownerOnly() {
        return Tr.s("screen.dynamicvillages.mayor.performance.owner_only", "Only the server owner can change these.");
    }
}
