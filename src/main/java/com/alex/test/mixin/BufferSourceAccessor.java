package com.alex.test.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SequencedMap;

/**
 * Lets {@code client.CrowdBatching} see which render types Minecraft's main buffer
 * already keeps open all pass (items, shields, the enchantment shimmer). Those stay
 * with Minecraft: it sends them after the crowd's batches, so the shimmer still finds
 * the armor it lies on.
 */
@Mixin(MultiBufferSource.BufferSource.class)
public interface BufferSourceAccessor {

    @Accessor("fixedBuffers")
    SequencedMap<RenderType, ByteBufferBuilder> dv$fixedBuffers();
}
