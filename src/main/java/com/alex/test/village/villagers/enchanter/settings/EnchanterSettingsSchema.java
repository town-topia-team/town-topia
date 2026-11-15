package com.alex.test.village.villagers.enchanter.settings;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.enchanter.EnchantBooks;
import com.alex.test.village.villagers.settings.SettingsSchema;

import java.util.List;

/**
 * Enchanter's settings schema, following the generic settings framework (see
 * {@code HOW_TO_ADD_SETTINGS.md} and the reference implementation,
 * {@code ArcherSettingsSchema}).
 *
 * <p>Every row here maps onto a real branch in {@code EnchanterActivity}: a
 * work-enabled gate, how much lapis lazuli to keep banked in storage, whether
 * each finished book goes to storage at once, whether it makes its own paper
 * and books, how many spare books to keep beyond what tools need, and which
 * enchantments it may write. What it writes, out of those, comes from the
 * village's tools ({@code EnchantNeeds}).
 */
public final class EnchanterSettingsSchema {

    public static final String WORK_ENABLED = "work_enabled";
    public static final String RESERVE_LAPIS = "reserve_lapis";
    public static final String AUTO_DEPOSIT = "auto_deposit";
    public static final String MAKE_BOOKS = "make_books";
    public static final String SPARE_BOOKS = "spare_books";
    public static final String SELL_BOOKS = "sell_books";
    public static final String ALLOWED_ENCHANTMENTS = "allowed_enchantments";

    public static final SettingsSchema SCHEMA = new SettingsSchema(
            Profession.ENCHANTER,
            "EnchanterSettings",
            List.of(
                    new SettingsSchema.Section("Behaviour", List.of(
                            SettingsSchema.Row.masterToggle(
                                    WORK_ENABLED, "Working", true)
                                    .describedAs("ON: makes enchanted books for\nthe village's tools.\n"
                                            + "OFF: makes none, and puts what\nit carries back in storage."),
                            SettingsSchema.Row.behaviorToggle(
                                    AUTO_DEPOSIT, "Storing Books", true)
                                    .describedAs("ON: takes each finished book\nstraight to storage.\n"
                                            + "OFF: carries them and banks\nthem when its work is done."),
                            SettingsSchema.Row.behaviorToggle(
                                    SELL_BOOKS, "Selling Top Books", true)
                                    .describedAs("ON: when every tool is covered,\nwrites top level books and sells\n"
                                            + "them to the visiting merchant.\nOFF: never sells books."),
                            SettingsSchema.Row.numericStepper(
                                    SPARE_BOOKS, "Spare books to keep", 0, 0, 3, 1)
                                    .describedAs("Extra books of each useful\nenchantment kept in storage\n"
                                            + "for new tools. At 0 it only\nmakes what tools need now."),
                            SettingsSchema.Row.numericStepper(
                                    RESERVE_LAPIS, "Lapis to leave for me", 8, 0, 64, 1)
                                    .describedAs("Lapis it leaves in storage for you.\n"
                                            + "Only lapis above this is used\nfor books."),
                            SettingsSchema.Row.header("Additional Behaviour"),
                            SettingsSchema.Row.behaviorToggle(
                                    MAKE_BOOKS, "Paper and Book Crafting", true)
                                    .describedAs("ON: presses sugar cane into paper\nand binds books with leather\n"
                                            + "when storage runs short of books.\nOFF: only uses books from storage."))),
                    new SettingsSchema.Section("Enchantments", List.of(
                            SettingsSchema.Row.allowList(
                                    ALLOWED_ENCHANTMENTS, "Enchantments to produce", enchantments())
                                    .describedAs("ON: makes books with this\nenchantment when a tool needs it.\n"
                                            + "OFF: never makes them.")))));

    private EnchanterSettingsSchema() {}

    /** Every enchantment the enchanter knows; some are learned later (see EnchanterBalance). */
    private static List<SettingsSchema.AllowListEntry> enchantments() {
        return EnchantBooks.KINDS.stream()
                .map(kind -> new SettingsSchema.AllowListEntry(kind.id(), kind.label(), true,
                        whatItDoes(kind) + (kind.unlockLevel() > 1
                                ? "\nLearned at level " + kind.unlockLevel() + "." : "")))
                .toList();
    }

    private static String whatItDoes(EnchantBooks.Kind kind) {
        return switch (kind.label()) {
            case "Unbreaking" -> "Tools and armour wear out\nslower.";
            case "Efficiency" -> "Pickaxes, axes and hoes\nwork faster.";
            case "Fortune" -> "Miners get more ore.";
            case "Mending" -> "Miners' pickaxes mend\nthemselves with experience.";
            case "Sharpness" -> "Guard swords hit harder.";
            case "Protection" -> "Armour blocks more damage.";
            case "Power" -> "Archer arrows hit harder.";
            case "Infinity" -> "Archers shoot without using\nup arrows.";
            default -> "";
        };
    }
}
