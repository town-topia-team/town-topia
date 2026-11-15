package com.alex.test.mixin;

import com.alex.test.client.ClientCostLog;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts and times every batch sent to the graphics card ({@code RenderType.draw}: one upload
 * and one draw call) for the {@code [ClientCost]} lines ({@link ClientCostLog}). One flag check
 * while logging is off. {@code require = 0}: if another mod or a Minecraft update changes the
 * method, the count stays 0 and the game runs on.
 */
@Mixin(RenderType.class)
public abstract class RenderTypeDrawMixin {

    @Inject(method = "draw(Lcom/mojang/blaze3d/vertex/MeshData;)V", at = @At("HEAD"), require = 0)
    private void dynamicvillages$drawStart(MeshData meshData, CallbackInfo ci) {
        ClientCostLog.drawStart();
    }

    @Inject(method = "draw(Lcom/mojang/blaze3d/vertex/MeshData;)V", at = @At("RETURN"), require = 0)
    private void dynamicvillages$drawEnd(MeshData meshData, CallbackInfo ci) {
        ClientCostLog.drawEnd();
    }
}
