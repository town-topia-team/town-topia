package com.alex.test.village.raid.warlord;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * The Warlord's Helm: what the Illager Warlord leaves where it falls. A trophy
 * to show off, not armour.
 */
public final class WarlordHelmItem extends Item {

    public WarlordHelmItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Taken from the Illager Warlord.").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Hang it in an item frame").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("to show you beat it.").withStyle(ChatFormatting.GRAY));
    }
}
