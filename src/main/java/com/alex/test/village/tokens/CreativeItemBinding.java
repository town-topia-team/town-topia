package com.alex.test.village.tokens;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Items a creative player takes from the creative menu are issued by the nearest village,
 * as if bought from its mayor. The creative client is already trusted to create any stack,
 * so this grants nothing a creative player could not forge.
 */
public final class CreativeItemBinding {
    /** How far outside a village's bounds a creative player may be and still be issued its goods. */
    public static final int NEARBY_BLOCKS = 128;

    private CreativeItemBinding() {}

    /** Mutates {@code stack} in place. Already-bound stacks keep their owner. */
    public static void bindToNearestVillage(ServerPlayer player, ItemStack stack) {
        if (!player.isCreative() || stack.isEmpty() || VillageTokenBinding.isBound(stack)) return;
        // A fresh Town Hall founds a new village; bound to a neighbour it would be a rejected copy.
        if (ItemFrameTokenRegistry.tokenType(stack) == ItemFrameTokenRegistry.TokenType.TOWN_HALL) return;
        VillageManager village = VillageManagerRegistry.get(player.server)
                .findNearestVillage(player.serverLevel(), player.blockPosition(), NEARBY_BLOCKS);
        if (village != null) VillageTokenBinding.bind(stack, village);
    }
}
