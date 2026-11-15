package com.alex.test.mixin;

import com.alex.test.village.farawake.ChunkStallWatch;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times the server thread's wait for a chunk in {@code ServerChunkCache.getChunk} (a block read
 * in a chunk that is not loaded: Minecraft loads it on the spot and waits). Only counted while a
 * village ticks: {@link ChunkStallWatch}, the "stalls" part of the {@code [FarAwake]} line.
 * {@code require = 0}: if a Minecraft update moves the wait, the count stays 0, the game runs on.
 *
 * <p>Priority 1100, above the default 1000: Lithium {@code @Overwrite}s {@code getChunk} (its wait
 * moved to {@code getChunkBlocking}), and Mixin refuses an INVOKE hook into a method another mixin
 * replaced unless the hook's priority is higher. That refusal is a hard crash at server start, which
 * {@code require = 0} does not cover. With 1100 the hook finds no wait in Lithium's copy and skips
 * itself: with Lithium the count stays 0. Without Lithium nothing changes.
 */
@Mixin(value = ServerChunkCache.class, priority = 1100)
public abstract class ServerChunkCacheStallMixin {

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"),
            require = 0)
    private void dynamicvillages$stallStart(int x, int z, ChunkStatus status, boolean load,
                                            CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkStallWatch.beforeWait();
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"),
            require = 0)
    private void dynamicvillages$stallEnd(int x, int z, ChunkStatus status, boolean load,
                                          CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkStallWatch.afterWait();
    }
}
