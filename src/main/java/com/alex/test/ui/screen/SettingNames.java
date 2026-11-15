package com.alex.test.ui.screen;

import com.alex.test.Tr;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

/**
 * Row names on the Farmer, Lumberjack and Guard settings screens, in the
 * player's language. The server sends each row's English name; the screen
 * swaps in a translation when it can:
 * <ul>
 *   <li>tools, crops and mobs use the game's own names, which every language has;</li>
 *   <li>our own switches look up {@code setting.dynamicvillages.<id path>}, whose
 *       English the menus declare with {@code Tr.s(...)}.</li>
 * </ul>
 * Anything else (tree types from other mods, for one) keeps the name it was sent.
 */
final class SettingNames {

    private SettingNames() {}

    /** One of our own switches ("Planting", "Defending You"). */
    static String own(ResourceLocation id, String sent) {
        // Our language keys keep the old name whatever the id says (LegacyModId).
        return Tr.sOr("setting.dynamicvillages." + id.getPath(), sent);
    }

    /** A tool tier row: the tool's own name ("Wooden Hoe"). */
    static String tool(@Nullable Item tool, ResourceLocation id, String sent) {
        return tool != null ? tool.getDescription().getString() : own(id, sent);
    }

    /** A row whose id is an item id (the farmer's crops). */
    static String item(ResourceLocation id, String sent) {
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? sent : item.getDescription().getString();
    }

    /** A row whose id is a mob's id (the guard's targets). */
    static String mob(ResourceLocation id, String sent) {
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                .map(type -> type.getDescription().getString())
                .orElse(sent);
    }

    static String on() {
        return Tr.s("screen.dynamicvillages.settings.on", "ON");
    }

    static String off() {
        return Tr.s("screen.dynamicvillages.settings.off", "OFF");
    }
}
