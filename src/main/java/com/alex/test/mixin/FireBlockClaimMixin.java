package com.alex.test.mixin;

import com.alex.test.village.claims.LandGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Village claims round 10: fire on an owned village's land with its fire guard on ({@link LandGuard}). Fire there
 * burns nothing and goes out on its own (its tick is {@code LandGuard.tameFireTick}); a fire next to the land
 * neither burns a block on it nor lights a spot on it. Start-of-method hooks only (see this folder's notes), and
 * {@code require = 0}: should another mod change these methods, fire is simply not guarded.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockClaimMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void towntopia$guardedTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                       CallbackInfo ci) {
        if (!LandGuard.guards(level, pos, LandGuard.Kind.FIRE)) return;
        ci.cancel();
        LandGuard.tameFireTick((FireBlock) (Object) this, state, level, pos, random);
    }

    /** A burning neighbour on guarded land is neither burnt away nor set alight. */
    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true, require = 0)
    private void towntopia$guardedBurn(Level level, BlockPos pos, int chance, RandomSource random, int age,
                                       Direction face, CallbackInfo ci) {
        if (LandGuard.guards(level, pos, LandGuard.Kind.FIRE)) ci.cancel();
    }

    /** An empty spot on guarded land never catches fire (asked only for empty spots, the cheap test first). */
    @Inject(method = "getIgniteOdds(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void towntopia$guardedSpread(LevelReader level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (level.isEmptyBlock(pos) && LandGuard.guards(level, pos, LandGuard.Kind.FIRE)) cir.setReturnValue(0);
    }
}
