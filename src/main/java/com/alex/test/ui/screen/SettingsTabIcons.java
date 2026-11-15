package com.alex.test.ui.screen;

import com.alex.test.village.villagers.core.Profession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Map;

/**
 * The item shown on each tab of a job's settings screen, like the guard's
 * screen has: the job's own tool on its Behaviour tab, and one per tab name.
 */
final class SettingsTabIcons {

    private static final Map<String, Item> BY_TITLE = Map.ofEntries(
            Map.entry("Duty", Items.BELL),
            Map.entry("Arrows", Items.ARROW),
            Map.entry("Mobs", Items.ZOMBIE_HEAD),
            Map.entry("Monsters", Items.ZOMBIE_HEAD),
            Map.entry("Equipment", Items.IRON_CHESTPLATE),
            Map.entry("Trading", Items.EMERALD),
            Map.entry("Stock", Items.CHEST),
            Map.entry("Recipes", Items.BREAD),
            Map.entry("Products", Items.IRON_PICKAXE),
            Map.entry("Performance", Items.JUKEBOX),
            Map.entry("Music", Items.JUKEBOX),
            Map.entry("Priorities", Items.SMITHING_TABLE),
            Map.entry("Movement", Items.LEATHER_BOOTS),
            Map.entry("Livestock", Items.LEAD),
            Map.entry("Animals", Items.LEAD),
            Map.entry("Lesson", Items.BOOK),
            Map.entry("Inspection", Items.SPYGLASS),
            Map.entry("Healing", Items.GOLDEN_APPLE),
            Map.entry("Patients", Items.GLISTERING_MELON_SLICE),
            Map.entry("Fuel", Items.COAL),
            Map.entry("Enchantments", Items.ENCHANTED_BOOK),
            Map.entry("Buildings", Items.BRICKS),
            Map.entry("Spells", Items.ENCHANTED_BOOK),
            Map.entry("Reagents", Items.AMETHYST_SHARD));

    private SettingsTabIcons() {}

    static Item iconFor(Profession profession, String title) {
        if ("Behaviour".equals(title)) return generalIcon(profession);
        return BY_TITLE.getOrDefault(title, Items.PAPER);
    }

    private static Item generalIcon(Profession profession) {
        return switch (profession) {
            case ARCHER -> Items.BOW;
            case MAGE -> Items.AMETHYST_SHARD;
            case BUILDER -> Items.BRICKS;
            case BARD -> Items.NOTE_BLOCK;
            case BLACKSMITH -> Items.ANVIL;
            case BUTCHER -> Items.BEEF;
            case CLERIC -> Items.BREWING_STAND;
            case COOK -> Items.SMOKER;
            case DRUID -> Items.OAK_SAPLING;
            case ENCHANTER -> Items.ENCHANTING_TABLE;
            case MINER -> Items.IRON_PICKAXE;
            case RANCHER -> Items.WHEAT;
            case TEACHER -> Items.LECTERN;
            case TRADESMAN -> Items.EMERALD;
            default -> Items.COMPASS;
        };
    }
}
