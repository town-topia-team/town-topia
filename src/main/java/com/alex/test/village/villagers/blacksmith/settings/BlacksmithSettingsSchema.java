package com.alex.test.village.villagers.blacksmith.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.List;

/**
 * Blacksmith's settings schema, following the generic settings framework
 * (see {@code HOW_TO_ADD_SETTINGS.md} and the reference implementation,
 * {@code ArcherSettingsSchema}).
 *
 * <p>Deliberately short. The smith decides what to make from the village
 * roster and the state of everyone's tools (see {@code ForgeOrderBook}); the
 * player only gets the few levers that change the answer in a way the smith
 * cannot guess: what to put first, how far up the material ladder to go, how
 * much of the precious stuff to leave alone, and whether old tools are melted
 * down. The old six per-category toggles are gone -- "Best material" covers
 * the question they answered.
 */
public final class BlacksmithSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String FOCUS = "focus";
    public static final String BEST_MATERIAL = "best_material";
    public static final String SAVE_MATERIALS = "save_materials";
    public static final String RECYCLE = "recycle_old_tools";
    /** With nothing to make, smelt the miner's spare raw iron and gold into ingots to sell. */
    public static final String SMELT_SPARE_ORE = "smelt_spare_ore";

    /** {@link #FOCUS} choices. */
    public static final int FOCUS_BALANCED = 0;
    public static final int FOCUS_WORKERS = 1;
    public static final int FOCUS_GUARDS = 2;

    /** {@link #BEST_MATERIAL} choices, in {@code ForgeOrderBook} tier order minus wood. */
    public static final int MATERIAL_STONE = 0;
    public static final int MATERIAL_IRON = 1;
    public static final int MATERIAL_DIAMOND = 2;
    public static final int MATERIAL_NETHERITE = 3;

    /** {@link #SAVE_MATERIALS} choices. */
    public static final int SAVE_NONE = 0;
    public static final int SAVE_SOME = 1;
    public static final int SAVE_LOTS = 2;

    /** Allow-list of every item the smith can make; entry ids are item registry paths. */
    public static final String PRODUCTS = "products";

    /** Item paths in the order the Products tab lists them. */
    public static final List<String> PRODUCT_IDS = List.of(
            "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe",
            "stone_axe", "iron_axe", "diamond_axe", "netherite_axe",
            "stone_hoe", "iron_hoe", "diamond_hoe", "netherite_hoe",
            "stone_sword", "iron_sword", "diamond_sword", "netherite_sword",
            "shield", "shears", "bucket",
            "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots",
            "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots",
            "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots",
            "netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots",
            "netherite_upgrade_smithing_template");

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.BLACKSMITH,
            "BlacksmithSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: forges, smelts and recycles.\n"
                                            + "Smelts raw gold when the cook\nwants golden food.\n"
                                            + "OFF: stops all of that and does\nother things."),
                            SettingsSchema.Row.behaviorToggle(
                                    RECYCLE, "Recycling", true)
                                    .describedAs("ON: melts down old tools nobody\nused for a day and keeps part\n"
                                            + "of the metal. Never touches\nenchanted tools or tools you stored."),
                            SettingsSchema.Row.behaviorToggle(
                                    SMELT_SPARE_ORE, "Smelting", true)
                                    .describedAs("ON: with nothing to make, smelts\nthe miner's raw iron, gold and\n"
                                            + "copper into ingots the tradesman\ncan sell to the merchant.\n"
                                            + "OFF: only smelts what it needs."))),
                    new SettingsSchema.Section("Priorities", List.of(
                            SettingsSchema.Row.choice(
                                    FOCUS, "Make first", FOCUS_BALANCED,
                                    List.of("Balanced", "Workers", "Guards"))
                                    .describedAs("What it makes once every missing\nor worn tool is replaced.\n"
                                            + "Balanced: miner's pickaxe, then\nguard gear.\n"
                                            + "Workers: tools before armour.\n"
                                            + "Guards: swords, shields and\narmour first."),
                            SettingsSchema.Row.choice(
                                    BEST_MATERIAL, "Best material", MATERIAL_NETHERITE,
                                    List.of("Stone", "Iron", "Diamond", "Netherite"))
                                    .describedAs("The best material it may use.\nIts skill must allow it too:\n"
                                            + "diamond from level 25, netherite\nfrom 75 with a smithing table."),
                            SettingsSchema.Row.choice(
                                    SAVE_MATERIALS, "Leave for me", SAVE_NONE,
                                    List.of("None", "Some", "Lots"))
                                    .describedAs("Iron and diamonds it leaves in\nstorage for you.\n"
                                            + "Some: 16 iron and 4 diamonds.\n"
                                            + "Lots: 64 iron and 16 diamonds."))),
                    new SettingsSchema.Section("Products", List.of(
                            SettingsSchema.Row.allowList(PRODUCTS, "Products", productEntries())
                                    .describedAs("ON: makes this item.\nOFF: never makes it, and makes\n"
                                            + "the next material down instead.")))));

    private static List<SettingsSchema.AllowListEntry> productEntries() {
        List<SettingsSchema.AllowListEntry> entries = new java.util.ArrayList<>();
        for (String id : PRODUCT_IDS) {
            entries.add(id.equals("bucket")
                    ? new SettingsSchema.AllowListEntry(id, label(id), true,
                            "For the farmers' wash cauldrons\nand the rancher's milk.\n"
                                    + "Only made when they need one.")
                    : new SettingsSchema.AllowListEntry(id, label(id), true));
        }
        return entries;
    }

    /** "diamond_pickaxe" -> "Diamond Pickaxe"; the template gets a short name. */
    private static String label(String id) {
        if (id.equals("netherite_upgrade_smithing_template")) return "Netherite Template (copy)";
        StringBuilder out = new StringBuilder();
        for (String word : id.split("_")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** Iron (ingots plus raw iron) the smith leaves in storage for the player. */
    public static int ironReserve(int saveChoice) {
        return switch (saveChoice) {
            case SAVE_SOME -> 16;
            case SAVE_LOTS -> 64;
            default -> 0;
        };
    }

    /** Diamonds the smith leaves in storage for the player. */
    public static int diamondReserve(int saveChoice) {
        return switch (saveChoice) {
            case SAVE_SOME -> 4;
            case SAVE_LOTS -> 16;
            default -> 0;
        };
    }

    private BlacksmithSettingsSchema() {}
}
