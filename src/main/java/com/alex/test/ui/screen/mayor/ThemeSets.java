package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import com.alex.test.client.VillagerTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * What the Theme tab's cards and a set's own page ({@link ThemePackView}) both show about a skin set: its name, look,
 * icon, who made it, how far it is painted, and the Use button with its hover text.
 */
final class ThemeSets {

    private ThemeSets() {}

    static final int BUTTON_H = 18;
    private static final int BUTTON_MIN_W = 44;

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    static boolean inUse(VillagerTheme set) {
        return VillagerTheme.current() == set;
    }

    /** A set with no skin painted yet cannot be used: every villager would look just as in Classic. */
    static boolean usable(VillagerTheme set) {
        return set.paintedCount() > 0;
    }

    /** The set's name. CLASSIC is the base look every set falls back to, shown as "Victorian English" since 10-23 (saved name unchanged). */
    static String name(VillagerTheme set) {
        return switch (set) {
            case CLASSIC -> Tr.s("screen.dynamicvillages.mayor.theme.classic", "Victorian English");
            case LONDON -> Tr.s("screen.dynamicvillages.mayor.theme.london", "Victorian London");
            case MODERN -> Tr.s("screen.dynamicvillages.mayor.theme.modern", "Modern");
            case RUSTIC -> Tr.s("screen.dynamicvillages.mayor.theme.rustic", "Rustic");
        };
    }

    /** The name, or a shorter one ("Victorian") where the whole name is wider than {@code width}. */
    static String name(VillagerTheme set, int width) {
        String name = name(set);
        if (font().width(name) <= width) return name;
        if (set == VillagerTheme.LONDON) return Tr.s("screen.dynamicvillages.mayor.theme.london_short", "London");
        if (set != VillagerTheme.CLASSIC) return name;
        return Tr.s("screen.dynamicvillages.mayor.theme.classic_short", "Victorian");
    }

    /** Who made the set's skins (a name, never translated), on its card, page and hover; null: no credit line. */
    @Nullable
    static String artist(VillagerTheme set) {
        return switch (set) {
            case CLASSIC, LONDON -> null;
            case MODERN -> "@Mello";
            case RUSTIC -> "@Neth";
        };
    }

    /** "Skins by @Mello", or "by @Mello" where that is wider than {@code width}; null for a set with no artist. */
    @Nullable
    static String creditLine(VillagerTheme set, int width) {
        String artist = artist(set);
        if (artist == null) return null;
        String line = credit(artist);
        return font().width(line) <= width ? line
                : Tr.s("screen.dynamicvillages.mayor.theme.credit_short", "by %s", artist);
    }

    private static String credit(String artist) {
        return Tr.s("screen.dynamicvillages.mayor.theme.credit", "Skins by %s", artist);
    }

    static Item icon(VillagerTheme set) {
        return switch (set) {
            case CLASSIC -> Items.VILLAGER_SPAWN_EGG;
            case LONDON -> Items.LANTERN;
            case MODERN -> Items.PAINTING;
            case RUSTIC -> Items.HAY_BLOCK;
        };
    }

    /** "12 of 40 skins": how many of today's skin files the set has its own picture for. */
    static String paintedLine(VillagerTheme set) {
        return Tr.s("screen.dynamicvillages.mayor.theme.painted", "%s of %s skins", set.paintedCount(), VillagerTheme.skinCount());
    }

    /** "12 of 40": the same, for a card too narrow for the whole line. */
    static String paintedLineShort(VillagerTheme set) {
        return Tr.s("screen.dynamicvillages.mayor.theme.painted_short", "%s of %s", set.paintedCount(), VillagerTheme.skinCount());
    }

    /** The hover over a set's name: what the set looks like, and how far it is painted. */
    static Component[] about(VillagerTheme set) {
        int painted = set.paintedCount();
        int total = VillagerTheme.skinCount();
        Component[] look = linesOf(switch (set) {
            case CLASSIC -> Tr.s("screen.dynamicvillages.mayor.theme.classic_about",
                    "Six jobs in Victorian English\nclothes and hats. Other jobs\nkeep their first look.");
            case LONDON -> Tr.s("screen.dynamicvillages.mayor.theme.london_about",
                    "Every job in sooty, foggy\nLondon dress, with its own\nhats, packs and gear in 3D.");
            case MODERN -> Tr.s("screen.dynamicvillages.mayor.theme.modern_about", "Bright skins\nin today's clothes.");
            case RUSTIC -> Tr.s("screen.dynamicvillages.mayor.theme.rustic_about", "Warm, worn, earthy skins.");
        });
        String artist = artist(set);
        if (artist != null) look = join(look, linesOf(credit(artist)));
        if (painted <= 0) {
            // In use with nothing painted: the config was set by hand, or a pack with the skins was switched off.
            return join(look, linesOf(inUse(set)
                    ? Tr.s("screen.dynamicvillages.mayor.theme.none_painted_in_use",
                            "No skins are painted yet,\nso every villager still looks %s.", name(VillagerTheme.CLASSIC))
                    : Tr.s("screen.dynamicvillages.mayor.theme.none_painted",
                            "No skins are painted yet,\nso this set can't be used.")));
        }
        if (set != VillagerTheme.CLASSIC && painted < total) {
            return join(look, linesOf(Tr.s("screen.dynamicvillages.mayor.theme.partly_painted",
                    "A skin not painted yet\nkeeps its %s look.", name(VillagerTheme.CLASSIC))));
        }
        return look;
    }

    // ---------------------------------------------------------------------
    // The Use button
    // ---------------------------------------------------------------------

    static String buttonLabel(VillagerTheme set) {
        return inUse(set) ? Tr.s("screen.dynamicvillages.mayor.theme.in_use", "In use")
                : Tr.s("screen.dynamicvillages.mayor.theme.use", "Use");
    }

    /** Wide enough for its word, never more than {@code maxW}. */
    static int buttonW(VillagerTheme set, int maxW) {
        return Math.min(maxW, Math.max(BUTTON_MIN_W, font().width(buttonLabel(set)) + 12));
    }

    /** Whether a click here switches every villager to the set: on its Use button, not in use, something painted. */
    static boolean useClicked(VillagerTheme set, double mouseX, double mouseY, int x, int y, int w) {
        return !inUse(set) && usable(set) && inside(mouseX, mouseY, x, y, w, BUTTON_H);
    }

    /** "In use": a solid green badge. "Use": a button, greyed for a set with nothing painted. */
    static void drawButton(GuiGraphics graphics, VillagerTheme set, int x, int y, int w, int mouseX, int mouseY) {
        if (!inUse(set)) {
            drawPlainButton(graphics, buttonLabel(set), x, y, w, usable(set), PANEL_BORDER, mouseX, mouseY);
            return;
        }
        Font font = font();
        String label = trim(buttonLabel(set), w - 4);
        graphics.fill(x, y, x + w, y + BUTTON_H, ACCENT);
        graphics.drawString(font, label, x + (w - font.width(label)) / 2, y + 5, PANEL_DARK, false);
    }

    /** A button in a {@code border} frame, its word in the middle; greyed and never lit when it can't be clicked. */
    static void drawPlainButton(GuiGraphics graphics, String text, int x, int y, int w, boolean usable, int border,
                                int mouseX, int mouseY) {
        Font font = font();
        String label = trim(text, w - 4);
        boolean hovered = usable && inside(mouseX, mouseY, x, y, w, BUTTON_H);
        graphics.fill(x, y, x + w, y + BUTTON_H, PANEL_BORDER);
        if (border != PANEL_BORDER && usable) graphics.renderOutline(x, y, w, BUTTON_H, border);
        graphics.fill(x + 1, y + 1, x + w - 1, y + BUTTON_H - 1, !usable ? 0xFF1A1A1A : hovered ? ROW_HOVER : 0xFF222222);
        graphics.drawString(font, label, x + (w - font.width(label)) / 2, y + 5, usable ? TEXT : DIM, false);
    }

    /** "3 changes": how many skins the player changed for the set in use. */
    static String changesLine(int changes) {
        return changes == 1 ? Tr.s("screen.dynamicvillages.mayor.theme.change_one", "1 change")
                : Tr.s("screen.dynamicvillages.mayor.theme.changes", "%s changes", changes);
    }

    // ---------------------------------------------------------------------
    // "Your look": every skin as seen in the world (the set in use with the player's changes)
    // ---------------------------------------------------------------------

    static final Item LOOK_ICON = Items.SPYGLASS;

    static String lookName() {
        return Tr.s("screen.dynamicvillages.mayor.theme.look", "Your look");
    }

    static String lookTagline() {
        return Tr.s("screen.dynamicvillages.mayor.theme.look_tagline", "What you see now");
    }

    /** "Rustic skins": the set in use. */
    static String lookSetLine(VillagerTheme inUse) {
        return setLine(name(inUse));
    }

    /** The same, with the short name when the whole line is wider than {@code width}. */
    static String lookSetLine(VillagerTheme inUse, int width) {
        String line = lookSetLine(inUse);
        return font().width(line) <= width ? line : setLine(name(inUse, 0));
    }

    private static String setLine(String setName) {
        return Tr.s("screen.dynamicvillages.mayor.theme.look_set", "%s skins", setName);
    }

    /** "3 changes", or "No changes" while there are none. */
    static String lookChangesLine(VillagerTheme inUse) {
        int changes = VillagerTheme.changeCount(inUse);
        return changes > 0 ? changesLine(changes) : Tr.s("screen.dynamicvillages.mayor.theme.no_changes", "No changes");
    }

    /** The hover over "Your look"'s name. */
    static Component[] lookAbout(VillagerTheme inUse) {
        return linesOf(Tr.s("screen.dynamicvillages.mayor.theme.look_about",
                "Every skin as you see it now:\nthe %s skins and\nyour own changes.", name(inUse)));
    }

    /** The hover over "3 changes". */
    static Component[] changesTip(int changes) {
        return linesOf(Tr.s("screen.dynamicvillages.mayor.theme.changes_tip",
                "You gave %s of its skins\nanother set's picture.\nClick to see or undo them.", changes));
    }

    /** Broken by hand into lines shorter than the hover's wrap width, so no word is left alone on a line. */
    static Component[] buttonTip(VillagerTheme set) {
        String setName = name(set);
        if (inUse(set) && !usable(set)) {
            return linesOf(Tr.s("screen.dynamicvillages.mayor.theme.in_use_empty_tip",
                    "No %s skins are painted yet,\nso every villager still looks %s.", setName, name(VillagerTheme.CLASSIC)));
        }
        if (inUse(set)) {
            return linesOf(Tr.s("screen.dynamicvillages.mayor.theme.in_use_tip",
                    "Every villager you see\nwears the %s skins.\nOnly changes what you see.", setName));
        }
        if (!usable(set)) {
            return linesOf(Tr.s("screen.dynamicvillages.mayor.theme.cant_use_tip",
                    "No %s skins are painted yet,\nso there is nothing to switch to.", setName));
        }
        return join(linesOf(Tr.s("screen.dynamicvillages.mayor.theme.use_tip",
                        "Dress every villager you see\nin the %s skins.", setName)),
                linesOf(Tr.s("screen.dynamicvillages.mayor.theme.only_you_tip",
                        "Only changes what you see.\nOther players pick their own.")));
    }
}
