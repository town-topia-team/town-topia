package com.alex.test.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/** One entity's tracking entry ({@link ChunkMapAccessor}): who sees it, and what sends its changes. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {

    @Accessor("serverEntity")
    ServerEntity dv$serverEntity();

    @Accessor("seenBy")
    Set<ServerPlayerConnection> dv$seenBy();
}
