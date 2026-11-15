package com.alex.test;

import com.alex.test.client.VillagerTheme;
import net.neoforged.neoforge.common.ModConfigSpec;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The player's own display choices, saved on their computer
 * ({@code config/towntopia-client.toml}), never in a world or village.
 */
public final class ClientConfig {

    /** How villagers' arms and legs are drawn. */
    public enum LimbStyle {
        /** One curved surface per limb, bending at the elbow and knee (BentLimb). */
        SMOOTH("Smooth"),
        /** Two straight boxes per limb, hinged at the elbow and knee. */
        BLOCKY("Blocky");

        private final String label;

        LimbStyle(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public LimbStyle next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.EnumValue<LimbStyle> LIMB_STYLE =
            BUILDER
                    .comment(
                            "How villagers' arms and legs look.",
                            "SMOOTH: they bend in a curve at the elbow and knee.",
                            "BLOCKY: straight boxes, like classic Minecraft.",
                            "Only changes what you see.")
                    .defineEnum("limbStyle", LimbStyle.SMOOTH);

    private static final ModConfigSpec.EnumValue<VillagerTheme> VILLAGER_THEME =
            BUILDER
                    .comment(
                            "Which set of villager skins you see.",
                            "CLASSIC: \"Victorian English\" on the Theme tab. Farmers, guards, archers,",
                            "lumberjacks, the Mayor and nomads in Victorian English clothes; other jobs keep their first look.",
                            "LONDON: \"Victorian London\": every job in sooty, foggy London dress, with its own 3D pieces.",
                            "MODERN: bright skins in today's clothes, by @Mello, the default.",
                            "RUSTIC: warm, worn, earthy skins, by @Neth, every job but the bard with its own 3D pieces.",
                            "A skin not painted yet keeps its CLASSIC look.",
                            "Only changes what you see.")
                    .defineEnum("villagerTheme", VillagerTheme.MODERN);

    /** Key of {@link #MODERN_DEFAULT_APPLIED}, left off the config screen ({@link #shownOnScreen}). */
    private static final String MODERN_DEFAULT_KEY = "modernDefaultApplied";

    private static final ModConfigSpec.BooleanValue MODERN_DEFAULT_APPLIED =
            BUILDER
                    .comment(
                            "Set by the game, leave it as it is. True once a skin set saved while CLASSIC was the",
                            "default has been moved to MODERN (once per file).")
                    .define(MODERN_DEFAULT_KEY, false);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> SKIN_CHANGES =
            BUILDER
                    .comment(
                            "Your own changes to the skin sets. Each set keeps its own: they are used while that set is in use.",
                            "One line per skin: \"<set in use>: <skin> = <set its picture comes from>\".",
                            "\"rustic: guard_2 = classic\" leaves Rustic's guard 2 out (those guards look Classic while Rustic is in use).",
                            "\"classic: cook = rustic\" brings Rustic's cook into Classic.",
                            "A skin is its file name without \"dynamic_villagers_\" (guard_2, farmer_female_1, architect, nitwit).",
                            "A line with an unknown set or skin is skipped. A set with no picture for a skin shows its Classic look.",
                            "Only changes what you see.")
                    .defineListAllowEmpty("skinChanges", List.of(), () -> "", line -> line instanceof String);

    private static final ModConfigSpec.BooleanValue SHOW_WORK_STATUS =
            BUILDER
                    .comment(
                            "Testing aid: show every nearby villager's name and what it is doing.",
                            "Set with /villagerstatus on|off. Only works for ops or with cheats on.")
                    .define("showWorkStatus", false);

    private static final ModConfigSpec.BooleanValue SHOW_TIPS =
            BUILDER
                    .comment(
                            "Show one tip about TownTopia in chat a few seconds after you join a world.",
                            "Only you see it.")
                    .define("showTips", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {}

    /** The chosen limb style; Smooth until the config has loaded. */
    public static LimbStyle limbStyle() {
        return SPEC.isLoaded() ? LIMB_STYLE.get() : LimbStyle.SMOOTH;
    }

    public static boolean blockyLimbs() {
        return limbStyle() == LimbStyle.BLOCKY;
    }

    /** Switches the limb style and writes it to the config file. */
    public static void setLimbStyle(LimbStyle style) {
        if (!SPEC.isLoaded() || LIMB_STYLE.get() == style) return;
        LIMB_STYLE.set(style);
        LIMB_STYLE.save();
    }

    /** The villager skin set chosen on this computer; Modern (the default) until the config has loaded. */
    public static VillagerTheme villagerTheme() {
        return SPEC.isLoaded() ? VILLAGER_THEME.get() : VillagerTheme.MODERN;
    }

    /**
     * Once per config file (10-25): a file saved while Victorian English (CLASSIC) was the default, by a build from
     * 10-22 to 10-25, switches to Modern, the default now. The file can't tell that from Victorian English picked on
     * purpose, so that pick moves too, this once. Then the flag is on and every pick is kept. Called when the config
     * loads, before {@link VillagerTheme#refresh}.
     */
    public static void applyModernDefaultOnce() {
        if (!SPEC.isLoaded() || MODERN_DEFAULT_APPLIED.get()) return;
        if (VILLAGER_THEME.get() == VillagerTheme.CLASSIC) VILLAGER_THEME.set(VillagerTheme.MODERN);
        MODERN_DEFAULT_APPLIED.set(true);
        SPEC.save();
    }

    /** False for settings the game keeps for itself (the one-time Modern switch): Mods > TownTopia > Config leaves them out. */
    public static boolean shownOnScreen(String key) {
        return !MODERN_DEFAULT_KEY.equals(key);
    }

    /** Switches the villager skin set, writes it to the config file and changes every villager at once. */
    public static void setVillagerTheme(VillagerTheme theme) {
        if (!SPEC.isLoaded() || VILLAGER_THEME.get() == theme) return;
        VILLAGER_THEME.set(theme);
        VILLAGER_THEME.save();
        VillagerTheme.refresh();
    }

    /** The player's own skin changes as written ({@link VillagerTheme#refresh} reads them); none until the config has loaded. */
    public static List<? extends String> skinChanges() {
        return SPEC.isLoaded() ? SKIN_CHANGES.get() : List.of();
    }

    /**
     * While {@code inUse} is in use, draws {@code skin} from {@code from} (null or {@code inUse} itself: its own picture
     * again). Writes it to the config file and changes every villager at once.
     */
    public static void setSkinChange(VillagerTheme inUse, String skin, @Nullable VillagerTheme from) {
        if (!SPEC.isLoaded()) return;
        setSkinChanges(VillagerTheme.withChange(SKIN_CHANGES.get(), inUse, skin, from));
    }

    /** Undoes every change to {@code inUse}: each of its skins wears its own picture again. */
    public static void clearSkinChanges(VillagerTheme inUse) {
        if (!SPEC.isLoaded()) return;
        setSkinChanges(VillagerTheme.withoutChanges(SKIN_CHANGES.get(), inUse));
    }

    private static void setSkinChanges(List<String> lines) {
        if (lines.equals(SKIN_CHANGES.get())) return;
        SKIN_CHANGES.set(lines);
        SKIN_CHANGES.save();
        VillagerTheme.refresh();
    }

    /** Whether a tip is shown after joining a world; on until the config has loaded. */
    public static boolean showTips() {
        return !SPEC.isLoaded() || SHOW_TIPS.get();
    }

    /** Whether {@code /villagerstatus} is on; off until the config has loaded. */
    public static boolean showWorkStatus() {
        return SPEC.isLoaded() && SHOW_WORK_STATUS.get();
    }

    /** Turns {@code /villagerstatus} on or off and writes it to the config file. */
    public static void setShowWorkStatus(boolean show) {
        if (!SPEC.isLoaded() || SHOW_WORK_STATUS.get() == show) return;
        SHOW_WORK_STATUS.set(show);
        SHOW_WORK_STATUS.save();
    }
}
