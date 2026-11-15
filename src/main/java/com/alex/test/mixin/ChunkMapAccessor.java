package com.alex.test.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets {@code village.farawake.FarSync} find the tracking entry of a far body the mod moved, so its new
 * place reaches the players who see it (Minecraft sends it only in entity-ticking range or on a
 * section change). The map's values are {@code ChunkMap.TrackedEntity}, which is package-private: read
 * them through {@link TrackedEntityAccessor}.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Accessor("entityMap")
    Int2ObjectMap<?> dv$entityMap();
}
