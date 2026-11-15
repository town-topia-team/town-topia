package com.alex.test.village.tokens;

import com.alex.test.Tr;
import com.alex.test.ModItems;
import com.alex.test.village.stats.FoodCatalog;
import com.alex.test.village.stats.HungerSystem;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Presentation only; item use and trading always read the binding data. */
public final class VillageItemTooltipEvents {
    private VillageItemTooltipEvents() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        // One token, two ways to place it; say so where the player is holding it.
        if (event.getItemStack().is(ModItems.STALL.get())) {
            addLines(event, Tr.s("tooltip.dynamicvillages.stall",
                    "Beside a door: a stall building\nOn its own: an open-air stall"));
        }
        // The two tokens players most often hang in the wrong place: how to build for them.
        if (event.getItemStack().is(ModItems.WATCHTOWER.get())) {
            addLines(event, Tr.s("tooltip.dynamicvillages.watchtower",
                    "Build a floor 5+ blocks high.\nAdd a ladder or stairs up to it.\n"
                            + "Hang this in a frame on that floor,\nnear the middle."));
        }
        // What the tavern is for besides the bard: visitors' evenings and guest beds.
        if (event.getItemStack().is(ModItems.TAVERN.get())) {
            addLines(event, Tr.s("tooltip.dynamicvillages.tavern",
                    "Build a room with a door and a note block.\nHang this in a frame next to the door.\n"
                            + "Visitors spend their evenings here.\nBeds inside are guest beds for them."));
        }
        if (event.getItemStack().is(ModItems.ARCHERY_RANGE.get())) {
            addLines(event, Tr.s("tooltip.dynamicvillages.archery_range",
                    "Fence in a yard with a gate.\nHang this in a frame next to the gate.\n"
                            + "Put target blocks inside it.\nIndoors: a room with a door works too."));
        }
        VillageTokenBinding binding = VillageTokenBinding.read(event.getItemStack());
        boolean emerald = event.getItemStack().is(com.alex.test.town.MayorShop.CURRENCY);
        // The Mayor's shop takes only a village's own emeralds; say so on the emerald itself.
        if (emerald && binding == null) {
            event.getToolTip().add(Tr.c("tooltip.dynamicvillages.plain_emerald",
                    "Plain emerald: a Mayor will not take it.").withStyle(ChatFormatting.RED));
            addLines(event, Tr.s("tooltip.dynamicvillages.plain_emerald.desc",
                    "Only emeralds your village earned,\nwith its green name and ID, buy tokens."));
        }
        if (binding == null) return;
        // Meal lines only on the village's own food: a vanilla item stays vanilla.
        addMealLine(event);
        event.getToolTip().set(0, event.getItemStack().getItem()
                .getName(event.getItemStack()).copy().withStyle(ChatFormatting.GREEN));
        event.getToolTip().add(1, Tr.c("tooltip.dynamicvillages.village_id", "ID: %s",
                binding.townHallId().toString().substring(0, 8)).withStyle(ChatFormatting.GREEN));
        if (emerald) {
            addLines(event, Tr.s("tooltip.dynamicvillages.village_emerald",
                    "Spend it at this village's Mayor.\nOther villages' Mayors will not take it."));
        }
        if (VillageTokenBinding.isMarkedReplaced(event.getItemStack())) {
            event.getToolTip().add(2, Tr.c("tooltip.dynamicvillages.replaced_token",
                    "Replaced by a newer Town Hall token.").withStyle(ChatFormatting.RED));
            event.getToolTip().add(3, Tr.c("tooltip.dynamicvillages.replaced_token.desc",
                    "This one no longer works.").withStyle(ChatFormatting.RED));
        }
    }

    /** Grey tooltip lines; "\n" in the (translated) text starts a new line. */
    private static void addLines(ItemTooltipEvent event, String text) {
        for (String line : text.split("\n")) {
            event.getToolTip().add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * What a villager makes of this village food: who made it (the cook, or
     * another villager at plain quality), its tier, hunger and taste.
     */
    private static void addMealLine(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        FoodProperties food = VillageItemOwnership.villagerFood(stack, null);
        if (food == null) return;
        VillageFood.Maker maker = VillageFood.maker(stack);
        if (maker == VillageFood.Maker.CHEF) {
            event.getToolTip().add(Tr.c("tooltip.dynamicvillages.meal.by_cook", "Made by the cook")
                    .withStyle(ChatFormatting.GOLD));
        } else if (maker == VillageFood.Maker.VILLAGER) {
            event.getToolTip().add(Tr.c("tooltip.dynamicvillages.meal.by_villager",
                    "Made by a villager, not the cook").withStyle(ChatFormatting.GRAY));
            event.getToolTip().add(Tr.c("tooltip.dynamicvillages.meal.plain_quality",
                    "Plain quality: less filling, less tasty").withStyle(ChatFormatting.DARK_GRAY));
        }
        FoodCatalog.Tier tier = FoodCatalog.tier(stack, food);
        float taste = FoodCatalog.taste(stack, food);
        int hunger = Math.round(HungerSystem.hungerRestoredByFood(food));
        ChatFormatting color = taste >= 3f ? ChatFormatting.GOLD
                : taste > 0f ? ChatFormatting.GRAY : ChatFormatting.RED;
        event.getToolTip().add(Tr.c("tooltip.dynamicvillages.meal",
                "Villager meal: %1$s · %2$s hunger · taste %3$s",
                tier.shown(), hunger, String.format(Locale.ROOT, "%+.1f", taste)).withStyle(color));
    }
}
