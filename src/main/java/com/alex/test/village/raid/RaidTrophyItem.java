package com.alex.test.village.raid;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * A raid trophy: the first win against each kind of raid, and every fifth
 * after, earns one. Hung in an item frame inside the Town Hall, each
 * different trophy makes the village more famous (once per kind).
 */
public final class RaidTrophyItem extends Item {

    private final RaidFaction faction;

    public RaidTrophyItem(RaidFaction faction, Properties properties) {
        super(properties);
        this.faction = faction;
    }

    public RaidFaction faction() {
        return faction;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Won by beating the " + faction.label().toLowerCase(java.util.Locale.ROOT) + ".")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Hang it in an item frame inside").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("the Town Hall: +2 fame, once").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("for each kind of trophy.").withStyle(ChatFormatting.GRAY));
    }
}
