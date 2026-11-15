package com.alex.test.mixin;

import com.alex.test.village.tokens.FrameTokenChanges;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {

    /** Every change of a frame's item comes through here: punched out, shot out, swapped, commands. */
    @Inject(method = "setItem(Lnet/minecraft/world/item/ItemStack;Z)V", at = @At("HEAD"))
    private void dynamicvillages$tokenLeaving(ItemStack stack, boolean updateNeighbours, CallbackInfo ci) {
        FrameTokenChanges.beforeItemChange((ItemFrame) (Object) this, stack);
    }
}
