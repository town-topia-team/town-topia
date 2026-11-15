package com.alex.test.client;

import com.alex.test.Tr;
import com.alex.test.ui.screen.VillagerScreen;
import com.alex.test.village.tokens.PlayerGear;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.tokens.VillageTokenBinding;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * How villagers treat a player's food, tools and armor, said only where it
 * matters: on the villager's own screen and on a storage room chest's slots.
 * Nothing is written on the item; everywhere else its tooltip is vanilla.
 */
public final class PlayerGoodsTooltip {
    private PlayerGoodsTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || VillageTokenBinding.isBound(stack) || !inVillageView()) return;
        String[] lines = linesFor(stack);
        // Straight under the item name, above vanilla's "When in Main Hand" block.
        int at = Math.min(1, event.getToolTip().size());
        for (String line : lines) {
            event.getToolTip().add(at++, Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
    }

    private static String[] linesFor(ItemStack stack) {
        // Cake is a block, not food, to the game: villagers only eat the village's own at a feast.
        if (stack.is(net.minecraft.world.item.Items.CAKE)) {
            return Tr.s("tooltip.dynamicvillages.player_goods.cake",
                    "Villagers will not eat cake from a player.\nThey only eat the village's own cake at a feast.")
                    .split("\n");
        }
        // Not the ominous bottle: food to the game, but no villager drinks it, so nothing to say.
        if (VillageItemOwnership.isVillagerFood(stack)) {
            return (VillageItemOwnership.isPlayerFood(stack)
                    ? Tr.s("tooltip.dynamicvillages.player_goods.food",
                            "From a player, not the village.\nVillagers will eat it.\n"
                                    + "It fills them up less than village food.")
                    : Tr.s("tooltip.dynamicvillages.player_goods.golden_food",
                            "Villagers will not eat golden food from a player.")).split("\n");
        }
        if (PlayerGear.isArrow(stack)) {
            return Tr.s("tooltip.dynamicvillages.player_goods.arrow",
                    "From a player, not the village.\nVillagers will shoot it.\n"
                            + "At archery practice, every second one breaks.").split("\n");
        }
        PlayerGear.Penalty penalty = PlayerGear.penalty(stack);
        if (penalty != PlayerGear.Penalty.NONE) {
            boolean full = penalty == PlayerGear.Penalty.FULL;
            // The enchantment line only when there is an enchantment to weaken.
            String text;
            if (stack.isEnchanted()) {
                text = full
                        ? Tr.s("tooltip.dynamicvillages.player_goods.gear.enchanted_full",
                                "From a player, not the village.\nVillagers will use it, but:\n"
                                        + "its enchantments are half as strong,\nand it wears out twice as fast.")
                        : Tr.s("tooltip.dynamicvillages.player_goods.gear.enchanted",
                                "From a player, not the village.\nVillagers will use it, but:\n"
                                        + "its enchantments are a little weaker,\nand it wears out a bit faster.");
            } else {
                text = full
                        ? Tr.s("tooltip.dynamicvillages.player_goods.gear.full",
                                "From a player, not the village.\nVillagers will use it,\n"
                                        + "but it wears out twice as fast.")
                        : Tr.s("tooltip.dynamicvillages.player_goods.gear",
                                "From a player, not the village.\nVillagers will use it,\n"
                                        + "but it wears out a bit faster.");
            }
            return text.split("\n");
        }
        if (stack.isDamageableItem()) {
            return Tr.s("tooltip.dynamicvillages.player_goods.too_good",
                    "Villagers will not use this.\nThey only use player tools and armor up to iron.")
                    .split("\n");
        }
        return new String[0];
    }

    /**
     * The villager's screen, or a storage room chest (or the storage room's
     * own screen) with the mouse over one of its slots, not the player's
     * inventory below it.
     */
    private static boolean inVillageView() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof VillagerScreen) return true;
        if (!(screen instanceof AbstractContainerScreen<?> container)
                || !(screen instanceof com.alex.test.ui.screen.StorageRoomScreen
                        || StorageChestButton.isStorageRoomScreen(screen))) return false;
        Slot hovered = container.getSlotUnderMouse();
        return hovered != null && !(hovered.container instanceof Inventory);
    }
}
