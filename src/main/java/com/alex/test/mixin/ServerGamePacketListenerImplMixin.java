package com.alex.test.mixin;

import com.alex.test.village.tokens.CreativeItemBinding;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    /** Runs on the server thread after the creative check, before the stack reaches a slot or is dropped. */
    @ModifyVariable(method = "handleSetCreativeModeSlot", at = @At("STORE"), ordinal = 0)
    private ItemStack dynamicvillages$bindCreativeStack(ItemStack stack) {
        CreativeItemBinding.bindToNearestVillage(player, stack);
        return stack;
    }
}
