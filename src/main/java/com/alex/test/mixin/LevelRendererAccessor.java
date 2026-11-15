package com.alex.test.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The chunk sections the camera can see this frame, as Minecraft worked them out for
 * drawing blocks (its occlusion graph). {@code client.SectionVisibility} reads it to
 * count villagers drawn inside sections nobody can see (plan step 0e).
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> getVisibleSections();
}
