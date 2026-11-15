package com.alex.test.mixin;

import com.alex.test.entity.NpcEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A spectator's right-click on one of our villagers (nomads, merchants and the Mayor too) puts the
 * camera inside it, the same as a left-click. Minecraft would instead open the villager's window
 * through plain {@code openMenu}, which sends none of our opening data: the client then failed to
 * read it and was kicked out ("Network Protocol Error"). No event fires for this click
 * ({@code EntityInteract} is skipped for spectators), hence the mixin.
 */
@Mixin(Player.class)
public abstract class PlayerSpectateNpcMixin {

    @Inject(method = "interactOn(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"), cancellable = true)
    private void dynamicvillages$spectateNpc(Entity target, InteractionHand hand,
                                             CallbackInfoReturnable<InteractionResult> cir) {
        Player self = (Player) (Object) this;
        if (!self.isSpectator() || !(target instanceof NpcEntity)) return;
        // Both hands send the click; the second finds the camera already there and does nothing.
        if (self instanceof ServerPlayer serverPlayer) serverPlayer.setCamera(target);
        cir.setReturnValue(InteractionResult.PASS);
    }
}
