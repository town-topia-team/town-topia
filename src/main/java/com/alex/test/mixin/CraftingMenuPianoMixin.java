package com.alex.test.mixin;

import com.alex.test.block.PianoLock;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CraftingMenu.class)
public abstract class CraftingMenuPianoMixin {

    /**
     * ZalithYT's Piano is crafted by its owners only (plan round 5): for anyone else the result slot is emptied
     * again, as if there were no recipe. The crafting table and the 2x2 grid in the inventory both come through
     * here. TAIL, so a mod that replaces this method cannot crash the game; both slot packets
     * leave in the same tick, so the result never shows.
     */
    @Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"))
    private static void dynamicvillages$pianoOwnersOnly(AbstractContainerMenu menu, Level level, Player player,
                                                       CraftingContainer craftSlots, ResultContainer resultSlots,
                                                       @Nullable RecipeHolder<CraftingRecipe> recipe, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (!PianoLock.isPianoItem(resultSlots.getItem(0)) || PianoLock.isOwner(serverPlayer)) return;
        resultSlots.setItem(0, ItemStack.EMPTY);
        menu.setRemoteSlot(0, ItemStack.EMPTY);
        serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(
                menu.containerId, menu.incrementStateId(), 0, ItemStack.EMPTY));
    }
}
