package com.alex.test;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Translatable text with its English written right at the call site.
 *
 * <pre>
 * player.sendSystemMessage(Tr.c("chat.dynamicvillages.village.founded", "Your village has been founded!"));
 * graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.earned", "Earned: %s emeralds", total), x, y, colour);
 * </pre>
 *
 * The English is the fallback: a key missing from a language file shows the
 * English, never the raw key. {@code tools/i18n/update_lang.py} copies every
 * key and its English into {@code en_us.json}, which is what translators see,
 * so the call sites must use a literal key and literal English (string
 * literals joined with {@code +} are fine). Placeholders are Minecraft's own:
 * {@code %s}, or {@code %1$s, %2$s} when a translation may reorder them.
 */
public final class Tr {

    private Tr() {}

    /**
     * A component that each player's own game translates. Use it for anything
     * sent from the server (chat, action bar, item tooltips, entity names).
     */
    public static MutableComponent c(String key, String english, Object... args) {
        return Component.translatableWithFallback(key, english, args);
    }

    /**
     * A plain string in the current language, for client-side screens that
     * draw strings. On a dedicated server this is always the English.
     */
    public static String s(String key, String english, Object... args) {
        String pattern = Language.getInstance().getOrDefault(key, english);
        if (args.length == 0) return pattern;
        try {
            return String.format(pattern, args);
        } catch (java.util.IllegalFormatException badTranslation) {
            // A translator broke the placeholders: fall back to the English.
            return String.format(english, args);
        }
    }

    /**
     * Tooltip lines from one translatable text: "\n" in the English (or the
     * translation) starts a new line, so translators decide where lines break.
     */
    public static java.util.List<Component> lines(String key, String english, Object... args) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        for (String line : s(key, english, args).split("\n", -1)) out.add(Component.literal(line));
        return out;
    }

    /** A plain string key lookup with no English of its own (keys built at runtime). */
    public static String sOr(String key, String fallback) {
        return Language.getInstance().getOrDefault(key, fallback);
    }
}
