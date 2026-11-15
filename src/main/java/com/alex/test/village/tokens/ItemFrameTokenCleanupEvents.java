package com.alex.test.village.tokens;

import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Removes registration-only glint from token stacks that are no longer in item
 * frames, and marks Town Hall tokens a newer one has replaced.
 */
public final class ItemFrameTokenCleanupEvents {

    private ItemFrameTokenCleanupEvents() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ItemEntity itemEntity)) return;
        ItemStack cleaned = ItemFrameTokenRegistry.withoutRegistrationGlint(itemEntity.getItem());
        if (cleaned != itemEntity.getItem()) itemEntity.setItem(cleaned);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || player.tickCount % 20 != 0) return;

        Inventory inventory = player.getInventory();
        VillageManagerRegistry registry = player.getServer() == null ? null
                : VillageManagerRegistry.get(player.getServer());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            ItemStack cleaned = ItemFrameTokenRegistry.withoutRegistrationGlint(stack);
            if (cleaned != stack) inventory.setItem(slot, cleaned);
            markIfReplaced(inventory, slot, registry);
        }
    }

    /** Tells the tooltip that a newer Town Hall token has made this one useless. */
    private static void markIfReplaced(Inventory inventory, int slot, VillageManagerRegistry registry) {
        ItemStack stack = inventory.getItem(slot);
        // The registry check goes first: it turns away anything but a Town Hall token cheaply.
        if (registry == null || !registry.isReplacedTownHallToken(stack)
                || VillageTokenBinding.isMarkedReplaced(stack)) return;
        ItemStack marked = stack.copy();
        VillageTokenBinding.markReplaced(marked);
        inventory.setItem(slot, marked);
    }
}
