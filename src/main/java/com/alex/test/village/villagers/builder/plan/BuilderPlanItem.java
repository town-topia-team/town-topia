package com.alex.test.village.villagers.builder.plan;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The Builder's plan: a rolled-up blueprint the desk hands out so the player can place a
 * blueprint in the real world. Everything it shows and does is on the client
 * ({@code ui.screen.BuilderPlanClient}); the server only keeps it from lasting
 * ({@link BuilderPlans}): it never lies on the ground, and a plan that is no longer
 * the player's active one disappears from their inventory.
 */
public final class BuilderPlanItem extends Item {
    /**
     * Set by the client while the plan in hand is pinned on a spot that fits (or a copy is
     * ready to save): the item glows. Only read when items are drawn, so only on a client.
     */
    public static volatile boolean glow;

    @Override
    public boolean isFoil(net.minecraft.world.item.ItemStack stack) {
        return glow;
    }


    public BuilderPlanItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) return;
        BuilderPlans.tickStack(stack, player, selected || player.getOffhandItem() == stack);
    }

    /** Thrown, dropped on death or spat out of anything: the plan is put away. */
    @Override
    public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        if (!entity.level().isClientSide) BuilderPlans.dropped(stack);
        entity.discard();
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        if (BuilderPlans.isCopyPlan(stack)) {
            lines.add(Component.literal("Right click a building to copy it.").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Gone once the copy is saved.").withStyle(ChatFormatting.GRAY));
            return;
        }
        String name = BuilderPlans.blueprintName(stack);
        if (name != null) lines.add(Component.literal("Blueprint: " + name).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Hold it to see the building where you look.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Gone once you confirm a spot.").withStyle(ChatFormatting.GRAY));
    }
}
